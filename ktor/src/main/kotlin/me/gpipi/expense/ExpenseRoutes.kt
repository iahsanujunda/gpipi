package me.gpipi.expense

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.serialization.Serializable
import me.gpipi.config.dbQuery
import org.jetbrains.exposed.v1.jdbc.Database

@Serializable
private data class ExpenseApiError(val message: String)

fun Route.expenseApiRoutes(
    db: Database,
    expenseRepo: ExpenseRepository,
) {
    get("/api/expenses") {
        val from = try {
            call.request.queryParameters["from"]?.let(OffsetDateTime::parse)
        } catch (_: DateTimeParseException) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                ExpenseApiError("'from' must be an ISO-8601 timestamp."),
            )
        }

        val to = try {
            call.request.queryParameters["to"]?.let(OffsetDateTime::parse)
        } catch (_: DateTimeParseException) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                ExpenseApiError("'to' must be an ISO-8601 timestamp."),
            )
        }

        val categoryId = try {
            call.request.queryParameters["categoryId"]?.let(UUID::fromString)
        } catch (_: IllegalArgumentException) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                ExpenseApiError("'categoryId' must be a UUID."),
            )
        }

        if (from != null && to != null && from.isAfter(to)) {
            return@get call.respond(
                HttpStatusCode.BadRequest,
                ExpenseApiError("'from' must not be after 'to'."),
            )
        }

        val rows = dbQuery(db) {
            expenseRepo.list(from, to, categoryId)
        }
        call.respond(rows)
    }
}
