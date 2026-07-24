package me.gpipi.category

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class UpsertBudgetRequest(
    val name: String,
    val description: String,
    val period: String,
    val amount: Long,
    val active: Boolean = true,
    val slackLoggable: Boolean = true,
)

@Serializable
private data class CreatedBudgetResponse(val id: String)

@Serializable
private data class BudgetApiError(val message: String)

fun Route.budgetApiRoutes(
    service: BudgetService,
) {
    route("/api/budgets") {
        get {
            call.respond(service.listBudgets())
        }

        post("/categories") {
            call.respondBudgetResult(service.create(call.receive()))
        }

        put("/categories/{id}") {
            val id = call.parameters["id"].toUuidOrNull()
                ?: return@put call.respond(
                    HttpStatusCode.BadRequest,
                    BudgetApiError("'id' must be a UUID."),
                )

            call.respondBudgetResult(service.update(id, call.receive()))
        }

        put("/categories/{id}/deactivate") {
            val id = call.parameters["id"].toUuidOrNull()
                ?: return@put call.respond(
                    HttpStatusCode.BadRequest,
                    BudgetApiError("'id' must be a UUID."),
                )

            call.respondBudgetResult(service.deactivate(id))
        }

        get("/spend") {
            val date = call.request.queryParameters["date"]?.let {
                try {
                    LocalDate.parse(it)
                } catch (_: DateTimeParseException) {
                    return@get call.respond(
                        HttpStatusCode.BadRequest,
                        BudgetApiError("'date' must be an ISO-8601 date (YYYY-MM-DD)."),
                    )
                }
            } ?: LocalDate.now()
            call.respond(service.spendVsCap(date))
        }
    }
}

private fun String?.toUuidOrNull(): UUID? =
    try {
        this?.let(UUID::fromString)
    } catch (_: IllegalArgumentException) {
        null
    }

private suspend fun ApplicationCall.respondBudgetResult(result: BudgetMutationResult) {
    when (result) {
        is BudgetMutationResult.Created ->
            respond(HttpStatusCode.Created, CreatedBudgetResponse(result.id.toString()))

        BudgetMutationResult.Updated ->
            respond(HttpStatusCode.NoContent)

        BudgetMutationResult.NotFound ->
            respond(HttpStatusCode.NotFound)

        is BudgetMutationResult.Invalid ->
            respond(HttpStatusCode.BadRequest, BudgetApiError(result.message))

        is BudgetMutationResult.DuplicateName ->
            respond(
                HttpStatusCode.Conflict,
                BudgetApiError("A budget line named '${result.name}' already exists."),
            )
    }
}
