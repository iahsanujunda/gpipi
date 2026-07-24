package me.gpipi

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.session
import io.ktor.server.response.respond
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import java.time.Clock

@Serializable
data class UserSession(val userId: String, val issuedAt: Long)  // stateless payload

private const val SESSION_IDLE_TIMEOUT_SECONDS = 8L * 60 * 60
private const val SESSION_ABSOLUTE_TIMEOUT_SECONDS = 24L * 60 * 60

fun Application.configureSecurity(clock: Clock = Clock.systemUTC()) {
    val signKey = environment.config.property("session.signKey").getString().toByteArray()
    val isProd  = environment.config.propertyOrNull("app.env")?.getString().equals("PROD", ignoreCase = true)

    install(Sessions) {
        cookie<UserSession>("session") {
            cookie.httpOnly = true
            cookie.secure   = isProd
            cookie.path     = "/"
            cookie.maxAgeInSeconds = SESSION_IDLE_TIMEOUT_SECONDS
            cookie.extensions["SameSite"] = "Lax"
            transform(SessionTransportTransformerMessageAuthentication(signKey))  // sign, don't encrypt
        }
    }
    install(Authentication) {
        session<UserSession>("auth-session") {
            validate { session ->
                val ageSeconds = clock.instant().epochSecond - session.issuedAt
                if (ageSeconds >= 0 && ageSeconds < SESSION_ABSOLUTE_TIMEOUT_SECONDS) {
                    sessions.set(session)
                    session
                } else {
                    null
                }
            }
            challenge { call.respond(HttpStatusCode.Unauthorized) }
        }
    }
}
