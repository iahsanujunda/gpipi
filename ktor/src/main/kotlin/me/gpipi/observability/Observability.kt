package me.gpipi.observability

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Timer
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import kotlinx.coroutines.withContext
import org.slf4j.event.Level

const val TRACE_ID_HEADER = "X-Trace-Id"

/**
 * One SDK for the process. Tests start many Ktor applications in the same JVM, so the SDK cannot
 * be tied to an individual Application lifecycle. The JVM shutdown hook flushes the batch exporter.
 *
 * Export is deliberately disabled by default. Standard OTEL_* environment variables have higher
 * precedence than these defaults, so production enables OTLP with configuration rather than code.
 */
object TelemetryRuntime {
    private val sdk: OpenTelemetrySdk by lazy {
        AutoConfiguredOpenTelemetrySdk.builder()
            .addPropertiesSupplier(::configurationDefaults)
            .build()
            .openTelemetrySdk
            .also { telemetry ->
                Runtime.getRuntime().addShutdownHook(
                    Thread({ telemetry.close() }, "opentelemetry-shutdown"),
                )
            }
    }

    private fun configurationDefaults(): Map<String, String> =
        buildMap {
            putAll(
                mapOf(
                    "otel.service.name" to "gpipi-bot",
                    "otel.traces.exporter" to "none",
                    "otel.metrics.exporter" to "none",
                    "otel.logs.exporter" to "none",
                    "otel.propagators" to "tracecontext,baggage",
                ),
            )
            // dotenv-kotlin exposes .env values as uppercase system properties. Native
            // environment variables are already understood by the SDK and override this map.
            mapOf(
                "OTEL_SERVICE_NAME" to "otel.service.name",
                "OTEL_TRACES_EXPORTER" to "otel.traces.exporter",
                "OTEL_EXPORTER_OTLP_ENDPOINT" to "otel.exporter.otlp.endpoint",
                "OTEL_EXPORTER_OTLP_PROTOCOL" to "otel.exporter.otlp.protocol",
                "OTEL_EXPORTER_OTLP_HEADERS" to "otel.exporter.otlp.headers",
                "OTEL_TRACES_SAMPLER" to "otel.traces.sampler",
                "OTEL_TRACES_SAMPLER_ARG" to "otel.traces.sampler.arg",
            ).forEach { (environmentName, propertyName) ->
                System.getProperty(environmentName)
                    ?.takeIf(String::isNotBlank)
                    ?.let { put(propertyName, it) }
            }
        }

    val openTelemetry: OpenTelemetry
        get() = sdk

    fun tracer(scope: String): Tracer = sdk.getTracer(scope)
}

class AppObservability internal constructor(
    val openTelemetry: OpenTelemetry,
    val meterRegistry: PrometheusMeterRegistry,
) {
    private val tracer = openTelemetry.getTracer("me.gpipi")

    suspend fun <T> span(
        name: String,
        kind: SpanKind = SpanKind.INTERNAL,
        configure: (Span) -> Unit = {},
        block: suspend () -> T,
    ): T {
        val parent = Context.current()
        val span = tracer.spanBuilder(name)
            .setParent(parent)
            .setSpanKind(kind)
            .startSpan()
        configure(span)

        return try {
            withContext(parent.with(span).asContextElement()) {
                block()
            }
        } catch (cause: Throwable) {
            span.recordException(cause)
            span.setStatus(StatusCode.ERROR)
            throw cause
        } finally {
            span.end()
        }
    }

    suspend fun <T> slackBackground(
        kind: String,
        block: suspend () -> T,
    ): T {
        val sample = Timer.start(meterRegistry)
        var outcome = "success"
        try {
            return span("slack.$kind.process", SpanKind.CONSUMER) {
                block()
            }
        } catch (cause: Throwable) {
            outcome = "error"
            throw cause
        } finally {
            sample.stop(
                Timer.builder("gpipi.slack.background")
                    .description("Slack work performed after the HTTP acknowledgement")
                    .tag("kind", kind)
                    .tag("outcome", outcome)
                    .register(meterRegistry),
            )
        }
    }

    fun recordSlackCommand(command: String, outcome: String) {
        Counter.builder("gpipi.slack.commands")
            .description("Terminal outcome returned by Slack command handling")
            .tag("command", command)
            .tag("outcome", outcome)
            .register(meterRegistry)
            .increment()
    }
}

val AppObservabilityKey = AttributeKey<AppObservability>("gpipi-observability")

private val TraceIdResponseHeader = createApplicationPlugin("TraceIdResponseHeader") {
    onCall { call ->
        val spanContext = Span.current().spanContext
        if (spanContext.isValid) {
            call.response.header(TRACE_ID_HEADER, spanContext.traceId)
        }
    }
}

fun Application.configureObservability() {
    val openTelemetry = TelemetryRuntime.openTelemetry
    val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    attributes.put(
        AppObservabilityKey,
        AppObservability(openTelemetry, meterRegistry),
    )

    // Must be the first telemetry/logging plugin so its context is available to later hooks.
    install(KtorServerTelemetry) {
        setOpenTelemetry(openTelemetry)
    }
    install(TraceIdResponseHeader)
    install(MicrometerMetrics) {
        registry = meterRegistry
    }
    install(CallLogging) {
        level = Level.INFO
        filter { call ->
            call.request.path() !in setOf("/health", "/health/ready", "/metrics")
        }
    }

    routing {
        get("/metrics") {
            call.respondText(
                text = meterRegistry.scrape(),
                contentType = ContentType.parse("text/plain; version=0.0.4; charset=utf-8"),
            )
        }
    }

    log.info("Observability initialized; configure OTEL_TRACES_EXPORTER=otlp to export traces")
}
