package me.gpipi.health

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.application
import io.ktor.server.application.log
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CancellationException
import me.gpipi.config.dbQuery
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/**
 * Health routes — infrastructure, not a feature. Public, deliberately outside Slack verification.
 *
 * `/health` is liveness: cheap and dependency-free (no DB, no external calls), because Render pings
 * it for deploy gating and a keep-alive pinger hits it often — a DB round-trip here would hammer
 * Postgres and let a transient blip cycle the instance.
 *
 * `/health/ready` is readiness: a single `SELECT 1` proving the DB is reachable. Kept separate from
 * liveness precisely so the frequent keep-alive ping stays off the database.
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
            call.application.log.warn("Readiness check failed", ex)
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("status" to "down"))
        }
    }
}
