package me.gpipi.category

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.serialization.Serializable
import me.gpipi.UserSession

@Serializable
data class UpsertBudgetRequest(
    val name: String,
    val description: String,
    val period: String,
    val amount: Long,
    val active: Boolean = true,
    val slackLoggable: Boolean = true,
    val accountId: String,
)

@Serializable
private data class CreatedBudgetResponse(val id: String)

@Serializable
private data class BudgetApiError(val message: String)

@Serializable
data class ApplyCarryForwardRequest(
    val targetWindowStart: String,
    val expectedAmount: Long,
)

@Serializable
data class CarryForwardResponse(
    val categoryId: String,
    val targetWindowStart: String,
    val amount: Long,
    val effectiveAllowance: Long,
    val replayed: Boolean,
)

fun Route.budgetApiRoutes(
    service: BudgetService,
    clock: Clock = Clock.systemUTC(),
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

        post("/categories/{id}/carry-forward") {
            val id = call.parameters["id"].toUuidOrNull()
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    BudgetApiError("'id' must be a UUID."),
                )
            val actorId = call.principal<UserSession>()?.userId
                ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val request = call.receive<ApplyCarryForwardRequest>()
            call.respondCarryForwardResult(
                service.applyCarryForward(
                    categoryId = id,
                    targetWindowStart = request.targetWindowStart,
                    expectedAmount = request.expectedAmount,
                    actorId = actorId,
                ),
            )
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
            } ?: LocalDate.now(clock.withZone(DEFAULT_BUDGET_ZONE))
            call.respond(service.spendVsCap(date))
        }
    }
}

private suspend fun ApplicationCall.respondCarryForwardResult(result: CarryForwardResult) {
    when (result) {
        is CarryForwardResult.Applied -> {
            val write = result.write
            respond(
                if (write.replayed) HttpStatusCode.OK else HttpStatusCode.Created,
                CarryForwardResponse(
                    categoryId = write.categoryId,
                    targetWindowStart = write.targetWindowStart,
                    amount = write.amount,
                    effectiveAllowance = write.effectiveAllowance,
                    replayed = write.replayed,
                ),
            )
        }

        CarryForwardResult.NotFound ->
            respond(HttpStatusCode.NotFound, BudgetApiError("Budget line not found."))

        is CarryForwardResult.Invalid ->
            respond(HttpStatusCode.BadRequest, BudgetApiError(result.message))

        is CarryForwardResult.Conflict ->
            respond(HttpStatusCode.Conflict, BudgetApiError(result.message))
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
