package me.gpipi

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.slf4j.LoggerFactory

private val statusPagesLog = LoggerFactory.getLogger("me.gpipi.StatusPages")

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            statusPagesLog.error(
                "Unhandled request failure: ${call.request.httpMethod.value} ${call.request.path()}",
                cause,
            )
            call.respondText(
                text = "Internal Server Error",
                status = HttpStatusCode.InternalServerError,
            )
        }
    }
}
