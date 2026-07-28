package me.gpipi

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.opentelemetry.api.trace.Span
import me.gpipi.observability.TRACE_ID_HEADER

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            val spanContext = Span.current().spanContext
            val traceId = spanContext.takeIf { it.isValid }?.traceId
            if (traceId != null) {
                call.response.header(TRACE_ID_HEADER, traceId)
            }
            call.application.log.error(
                "Unhandled request failure${traceId?.let { " trace_id=$it" }.orEmpty()}",
                cause,
            )
            call.respondText(
                text = "Internal server error",
                status = HttpStatusCode.InternalServerError,
            )
        }
    }
}
