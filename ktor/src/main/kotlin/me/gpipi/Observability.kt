package me.gpipi

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.path
import java.util.UUID
import org.slf4j.event.Level

private const val REQUEST_ID_MDC_KEY = "request_id"
private const val MAX_REQUEST_ID_LENGTH = 128

fun Application.configureObservability() {
    install(CallId) {
        header(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify(::isSafeRequestId)
    }

    install(CallLogging) {
        level = Level.INFO
        disableDefaultColors()
        callIdMdc(REQUEST_ID_MDC_KEY)
        filter { call -> call.request.path() != "/health" }
    }
}

private fun isSafeRequestId(requestId: String): Boolean =
    requestId.length in 1..MAX_REQUEST_ID_LENGTH &&
        requestId.all { character ->
            character in 'a'..'z' ||
                character in 'A'..'Z' ||
                character in '0'..'9' ||
                character == '-' ||
                character == '_' ||
                character == '.'
        }
