package me.gpipi.observability

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.instrumentation.ktor.v3_0.KtorClientTelemetry
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.withContext
import me.gpipi.configureStatusPages
import me.gpipi.support.configureWithTestDb

class ObservabilityTest {

    @Test
    fun `real application exposes trace-correlated HTTP and JVM metrics`() = testApplication {
        configureWithTestDb()

        val health = client.get("/health") {
            headers.append(HttpHeaders.Origin, "https://budget.test")
        }
        val traceId = assertNotNull(health.headers[TRACE_ID_HEADER])
        assertTrue(traceId.matches(Regex("[0-9a-f]{32}")))
        assertContains(
            assertNotNull(health.headers[HttpHeaders.AccessControlExposeHeaders]),
            TRACE_ID_HEADER,
        )

        val metrics = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, metrics.status)
        val body = metrics.bodyAsText()
        assertContains(body, "ktor_http_server_requests")
        assertContains(body, "jvm_memory_used_bytes")
        assertContains(body, "hikaricp_connections")
    }

    @Test
    fun `unhandled failures return a safe body and a usable trace id`() = testApplication {
        application {
            configureObservability()
            configureStatusPages()
            routing {
                get("/boom") {
                    error("database password must never reach the response")
                }
            }
        }

        val response = client.get("/boom")

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("Internal server error", response.bodyAsText())
        assertFalse("database password" in response.bodyAsText())
        assertTrue(
            assertNotNull(response.headers[TRACE_ID_HEADER])
                .matches(Regex("[0-9a-f]{32}")),
        )
    }

    @Test
    fun `Ktor client propagates the active trace to a downstream server`() = testApplication {
        application {
            configureObservability()
            routing {
                get("/downstream") {
                    call.respondText(call.request.header("traceparent").orEmpty())
                }
            }
        }
        val tracedClient = createClient {
            install(KtorClientTelemetry) {
                setOpenTelemetry(TelemetryRuntime.openTelemetry)
            }
        }
        val root = TelemetryRuntime.tracer("me.gpipi.test")
            .spanBuilder("test.root")
            .startSpan()

        try {
            val traceparent = withContext(
                Context.root().with(root).asContextElement(),
            ) {
                tracedClient.get("/downstream").bodyAsText()
            }

            assertContains(traceparent, root.spanContext.traceId)
        } finally {
            root.end()
            tracedClient.close()
        }
    }

    @Test
    fun `Slack background work retains its parent trace and records a metric`() {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val observability = AppObservability(TelemetryRuntime.openTelemetry, registry)
        val parent = TelemetryRuntime.tracer("me.gpipi.test")
            .spanBuilder("slack.request")
            .startSpan()

        try {
            kotlinx.coroutines.runBlocking(
                Context.root().with(parent).asContextElement(),
            ) {
                observability.slackBackground("event") {
                    val processing = Span.current().spanContext
                    assertEquals(parent.spanContext.traceId, processing.traceId)
                    assertFalse(parent.spanContext.spanId == processing.spanId)
                }
            }
        } finally {
            parent.end()
        }

        val metrics = registry.scrape()
        assertContains(metrics, "gpipi_slack_background_seconds_count")
        assertContains(metrics, "kind=\"event\"")
        assertContains(metrics, "outcome=\"success\"")
    }
}
