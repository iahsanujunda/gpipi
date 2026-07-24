package me.gpipi

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.session
import io.ktor.server.response.respond
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.cookie
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

@Serializable
data class UserSession(val userId: String, val issuedAt: Long)  // stateless payload

fun Application.configureSecurity() {
    val signKey = environment.config.property("session.signKey").getString().toByteArray()
    val isProd  = environment.config.propertyOrNull("app.env")?.getString().equals("PROD", ignoreCase = true)

    install(Sessions) {
        cookie<UserSession>("session") {
            cookie.httpOnly = true
            cookie.secure   = isProd
            cookie.path     = "/"
            cookie.maxAgeInSeconds = 30L * 60
            cookie.extensions["SameSite"] = "Lax"
            transform(SessionTransportTransformerMessageAuthentication(signKey))  // sign, don't encrypt
        }
    }
    install(Authentication) {
        session<UserSession>("auth-session") {
            validate { it }                                  // presence = valid (stateless)
            challenge { call.respond(HttpStatusCode.Unauthorized) }
        }
    }
}
