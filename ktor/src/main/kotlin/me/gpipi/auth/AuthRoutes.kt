package me.gpipi.auth

import me.gpipi.UserSession
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import kotlinx.serialization.Serializable
import java.time.Clock

@Serializable
data class RedeemRequest(val nonce: String)

@Serializable
data class SessionResponse(val userId: String)

@Serializable
private data class AuthErrorResponse(val message: String)

fun Route.authRoutes(
    auth: AuthService,
    clock: Clock = Clock.systemUTC(),
    ) {
    route("/api/auth") {
        post("/redeem") {
            val request = call.receive<RedeemRequest>()

            if (request.nonce.isBlank() || request.nonce.length > 256) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    AuthErrorResponse("Nonce is missing or malformed."),
                )
            }

            val userId = auth.redeem(request.nonce)
                ?: return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    AuthErrorResponse("Invalid nonce")
                )

            call.sessions.set(
                UserSession(
                    userId = userId,
                    issuedAt = clock.instant().epochSecond,
                    ),
            )
            call.respond(
                HttpStatusCode.OK,
                SessionResponse(userId),
            )
        }

        authenticate("auth-session") {
            get("/session") {
                val session = call.principal<UserSession>()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)

                call.respond(SessionResponse(session.userId))
            }

            post("/logout") {
                call.sessions.clear<UserSession>()
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
