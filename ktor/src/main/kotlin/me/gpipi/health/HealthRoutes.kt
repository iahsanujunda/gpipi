package me.gpipi.health

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Liveness — infrastructure, not a feature. Must stay cheap and dependency-free (no DB, no
 * external calls): Render pings it for deploy gating and a keep-alive pinger hits it often, so a
 * DB round-trip here would hammer Postgres. Public — deliberately outside Slack verification.
 */
fun Route.healthRoutes() {
    get("/health") {
        call.respond(HttpStatusCode.OK, mapOf("status" to "up"))
    }
    // iter 2: /health/ready does a SELECT 1 via dbQuery (readiness)
}
