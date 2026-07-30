package me.gpipi.account

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
import java.util.UUID
import kotlinx.serialization.Serializable
import me.gpipi.UserSession

@Serializable
data class AccountResponse(
    val id: String,
    val name: String,
    val description: String?,
    val balance: Long,
    val assignedBudgetCount: Int,
)

@Serializable
data class AssignedBudgetResponse(
    val id: String,
    val name: String,
    val period: String,
    val amount: Long,
)

@Serializable
data class AccountDetailResponse(
    val account: AccountResponse,
    val assignedBudgets: List<AssignedBudgetResponse>,
)

@Serializable
data class UpsertAccountRequest(
    val name: String,
    val description: String? = null,
)

@Serializable
data class MovementInputRequest(
    val fromAccountId: String? = null,
    val toAccountId: String? = null,
    val amount: Long,
    val occurredOn: String,
    val note: String? = null,
)

@Serializable
data class CreateMovementRequest(
    val idempotencyKey: String,
    val fromAccountId: String? = null,
    val toAccountId: String? = null,
    val amount: Long,
    val occurredOn: String,
    val note: String? = null,
)

@Serializable
data class BalanceProjectionResponse(
    val accountId: String,
    val name: String,
    val balanceBefore: Long,
    val delta: Long,
    val balanceAfter: Long,
)

@Serializable
data class MovementPreviewResponse(
    val calculatedAt: String,
    val accounts: List<BalanceProjectionResponse>,
)

@Serializable
data class MovementResponse(
    val id: String,
    val idempotencyKey: String,
    val fromAccountId: String?,
    val toAccountId: String?,
    val amount: Long,
    val occurredAt: String,
    val note: String?,
    val createdByUserId: String,
    val createdAt: String,
)

@Serializable
data class MovementWriteResponse(
    val movement: MovementResponse,
    val calculatedAt: String,
    val accounts: List<BalanceProjectionResponse>,
)

@Serializable
data class AccountTransactionResponse(
    val kind: String,
    val id: String,
    val occurredAt: String,
    val signedAmount: Long,
    val merchant: String? = null,
    val description: String? = null,
    val categoryName: String? = null,
    val note: String? = null,
    val direction: String? = null,
    val counterpartyAccountId: String? = null,
    val counterpartyName: String? = null,
)

@Serializable
data class AccountTransactionsResponse(
    val items: List<AccountTransactionResponse>,
    val nextCursor: String? = null,
)

@Serializable
private data class CreatedAccountResponse(val id: String)

@Serializable
private data class AccountApiError(val message: String)

fun Route.accountApiRoutes(service: AccountService) {
    route("/api/accounts") {
        get {
            call.respond(service.listAccounts().map(AccountRecord::toResponse))
        }

        post {
            val request = call.receive<UpsertAccountRequest>()
            call.respondAccountMutation(
                service.createAccount(request.name, request.description),
            )
        }

        get("/{id}") {
            val id = call.requireAccountId() ?: return@get
            val detail = service.accountDetail(id)
                ?: return@get call.respond(
                    HttpStatusCode.NotFound,
                    AccountApiError("Wallet not found."),
                )
            call.respond(detail.toResponse())
        }

        put("/{id}") {
            val id = call.requireAccountId() ?: return@put
            val request = call.receive<UpsertAccountRequest>()
            call.respondAccountMutation(
                service.updateAccount(id, request.name, request.description),
            )
        }

        get("/{id}/transactions") {
            val id = call.requireAccountId() ?: return@get
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()
            if (call.request.queryParameters["limit"] != null && limit == null) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    AccountApiError("'limit' must be an integer."),
                )
            }
            when (
                val result = service.transactions(
                    id = id,
                    limit = limit,
                    cursor = call.request.queryParameters["cursor"],
                )
            ) {
                is AccountTransactionsResult.Found ->
                    call.respond(
                        AccountTransactionsResponse(
                            items = result.items.map(AccountTransactionRecord::toResponse),
                            nextCursor = result.nextCursor,
                        ),
                    )

                AccountTransactionsResult.NotFound ->
                    call.respond(
                        HttpStatusCode.NotFound,
                        AccountApiError("Wallet not found."),
                    )

                is AccountTransactionsResult.Invalid ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AccountApiError(result.message),
                    )
            }
        }
    }

    route("/api/money-movements") {
        post("/preview") {
            val request = call.receive<MovementInputRequest>()
            call.respondMovementResult(service.preview(request.toInput()))
        }

        post {
            val actorId = call.principal<UserSession>()?.userId
                ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val request = call.receive<CreateMovementRequest>()
            call.respondMovementResult(
                service.record(
                    idempotencyKey = request.idempotencyKey,
                    input = request.toInput(),
                    actorId = actorId,
                ),
            )
        }
    }
}

private suspend fun ApplicationCall.requireAccountId(): UUID? {
    val id = parameters["id"].toUuidOrNull()
    if (id == null) {
        respond(
            HttpStatusCode.BadRequest,
            AccountApiError("'id' must be a UUID."),
        )
    }
    return id
}

private suspend fun ApplicationCall.respondAccountMutation(result: AccountMutationResult) {
    when (result) {
        is AccountMutationResult.Created ->
            respond(
                HttpStatusCode.Created,
                CreatedAccountResponse(result.id.toString()),
            )

        AccountMutationResult.Updated ->
            respond(HttpStatusCode.NoContent)

        AccountMutationResult.NotFound ->
            respond(
                HttpStatusCode.NotFound,
                AccountApiError("Wallet not found."),
            )

        is AccountMutationResult.Invalid ->
            respond(
                HttpStatusCode.BadRequest,
                AccountApiError(result.message),
            )

        is AccountMutationResult.DuplicateName ->
            respond(
                HttpStatusCode.Conflict,
                AccountApiError("A wallet named '${result.name}' already exists."),
            )
    }
}

private suspend fun ApplicationCall.respondMovementResult(result: MovementResult) {
    when (result) {
        is MovementResult.Previewed ->
            respond(result.preview.toResponse())

        is MovementResult.Recorded ->
            respond(
                if (result.write.replayed) HttpStatusCode.OK else HttpStatusCode.Created,
                result.write.toResponse(),
            )

        is MovementResult.Invalid ->
            respond(
                HttpStatusCode.BadRequest,
                AccountApiError(result.message),
            )

        is MovementResult.Conflict ->
            respond(
                HttpStatusCode.Conflict,
                AccountApiError(result.message),
            )
    }
}

private fun MovementInputRequest.toInput() =
    MovementInput(
        fromAccountId = fromAccountId,
        toAccountId = toAccountId,
        amount = amount,
        occurredOn = occurredOn,
        note = note,
    )

private fun CreateMovementRequest.toInput() =
    MovementInput(
        fromAccountId = fromAccountId,
        toAccountId = toAccountId,
        amount = amount,
        occurredOn = occurredOn,
        note = note,
    )

private fun AccountRecord.toResponse() =
    AccountResponse(
        id = id.toString(),
        name = name,
        description = description,
        balance = balance,
        assignedBudgetCount = assignedBudgetCount,
    )

private fun AccountDetailRecord.toResponse() =
    AccountDetailResponse(
        account = account.toResponse(),
        assignedBudgets = assignedBudgets.map {
            AssignedBudgetResponse(
                id = it.id.toString(),
                name = it.name,
                period = it.period,
                amount = it.amount,
            )
        },
    )

private fun AccountTransactionRecord.toResponse(): AccountTransactionResponse =
    when (this) {
        is AccountTransactionRecord.Expense ->
            AccountTransactionResponse(
                kind = "EXPENSE",
                id = id.toString(),
                occurredAt = occurredAt.toString(),
                signedAmount = signedAmount,
                merchant = merchant,
                description = description,
                categoryName = categoryName,
                note = note,
            )

        is AccountTransactionRecord.Movement ->
            AccountTransactionResponse(
                kind = "MONEY_MOVEMENT",
                id = id.toString(),
                occurredAt = occurredAt.toString(),
                signedAmount = signedAmount,
                direction = direction,
                counterpartyAccountId = counterpartyAccountId?.toString(),
                counterpartyName = counterpartyName,
                note = note,
            )
    }

private fun MovementPreviewRecord.toResponse() =
    MovementPreviewResponse(
        calculatedAt = calculatedAt.toString(),
        accounts = accounts.map(BalanceProjectionRecord::toResponse),
    )

private fun MovementWriteRecord.toResponse() =
    MovementWriteResponse(
        movement = movement.toResponse(),
        calculatedAt = calculatedAt.toString(),
        accounts = accounts.map(BalanceProjectionRecord::toResponse),
    )

private fun MovementRecord.toResponse() =
    MovementResponse(
        id = id.toString(),
        idempotencyKey = idempotencyKey.toString(),
        fromAccountId = fromAccountId?.toString(),
        toAccountId = toAccountId?.toString(),
        amount = amount,
        occurredAt = occurredAt.toString(),
        note = note,
        createdByUserId = createdByUserId,
        createdAt = createdAt.toString(),
    )

private fun BalanceProjectionRecord.toResponse() =
    BalanceProjectionResponse(
        accountId = accountId.toString(),
        name = name,
        balanceBefore = balanceBefore,
        delta = delta,
        balanceAfter = balanceAfter,
    )

private fun String?.toUuidOrNull(): UUID? =
    try {
        this?.let(UUID::fromString)
    } catch (_: IllegalArgumentException) {
        null
    }
