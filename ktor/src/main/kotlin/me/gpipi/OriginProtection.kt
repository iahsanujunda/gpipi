package me.gpipi

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond

private val UNSAFE_METHODS = setOf(
    HttpMethod.Post,
    HttpMethod.Put,
    HttpMethod.Patch,
    HttpMethod.Delete,
)

/**
 * Cookie authentication requires an explicit browser-origin check for state-changing API calls.
 * Slack routes are outside /api and continue to use their request-signature boundary.
 */
fun Application.configureOriginProtection() {
    val trustedOrigin = environment.config.property("web.baseUrl")
        .getString()
        .trimEnd('/')

    intercept(ApplicationCallPipeline.Plugins) {
        if (
            call.request.path().startsWith("/api/") &&
            call.request.httpMethod in UNSAFE_METHODS &&
            call.request.headers[HttpHeaders.Origin] != trustedOrigin
        ) {
            call.respond(
                HttpStatusCode.Forbidden,
                mapOf("message" to "Request origin is not allowed."),
            )
            finish()
        }
    }
}
