package me.gpipi.health

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlin.coroutines.cancellation.CancellationException
import me.gpipi.config.dbQuery
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("me.gpipi.health.HealthRoutes")

/**
 * Infrastructure, not a feature. Public — deliberately outside Slack verification.
 *
 * `/health` (liveness) must stay cheap and dependency-free (no DB, no external calls): Render
 * pings it for deploy gating and a keep-alive pinger hits it often, so a DB round-trip here
 * would hammer Postgres. `/health/ready` (readiness) is the one that touches the DB — a single
 * SELECT 1 through dbQuery — and answers 503 when the pool can't reach Postgres.
 */
fun Route.healthRoutes(db: Database) {
    get("/health") {
        call.respond(HttpStatusCode.OK, mapOf("status" to "up"))
    }
    get("/health/ready") {
        try {
            dbQuery(db) { TransactionManager.current().exec("SELECT 1") }
            call.respond(HttpStatusCode.OK, mapOf("status" to "ready"))
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            log.warn("readiness check failed: ${ex.message}")
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to "unavailable"))
        }
    }
}
