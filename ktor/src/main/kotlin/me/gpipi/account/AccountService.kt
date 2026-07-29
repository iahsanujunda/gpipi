package me.gpipi.account

import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.UUID
import me.gpipi.config.dbQuery
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database

private val HOUSEHOLD_ZONE: ZoneId = ZoneId.of("Asia/Tokyo")

sealed interface AccountMutationResult {
    data class Created(val id: UUID) : AccountMutationResult
    data object Updated : AccountMutationResult
    data object NotFound : AccountMutationResult
    data class Invalid(val message: String) : AccountMutationResult
    data class DuplicateName(val name: String) : AccountMutationResult
}

data class AccountDetailRecord(
    val account: AccountRecord,
    val assignedBudgets: List<AssignedBudgetRecord>,
)

sealed interface AccountTransactionsResult {
    data class Found(
        val items: List<AccountTransactionRecord>,
        val nextCursor: String?,
    ) : AccountTransactionsResult

    data object NotFound : AccountTransactionsResult
    data class Invalid(val message: String) : AccountTransactionsResult
}

data class MovementInput(
    val fromAccountId: String?,
    val toAccountId: String?,
    val amount: Long,
    val occurredOn: String,
    val note: String?,
)

data class ValidatedMovement(
    val fromAccountId: UUID?,
    val toAccountId: UUID?,
    val amount: Long,
    val occurredOn: LocalDate,
    val occurredAt: OffsetDateTime,
    val note: String?,
)

private sealed interface MovementValidation {
    data class Valid(val movement: ValidatedMovement) : MovementValidation
    data class Invalid(val message: String) : MovementValidation
}

data class BalanceProjectionRecord(
    val accountId: UUID,
    val name: String,
    val balanceBefore: Long,
    val delta: Long,
    val balanceAfter: Long,
)

data class MovementPreviewRecord(
    val calculatedAt: OffsetDateTime,
    val accounts: List<BalanceProjectionRecord>,
)

data class MovementWriteRecord(
    val movement: MovementRecord,
    val calculatedAt: OffsetDateTime,
    val accounts: List<BalanceProjectionRecord>,
    val replayed: Boolean,
)

sealed interface MovementResult {
    data class Previewed(val preview: MovementPreviewRecord) : MovementResult
    data class Recorded(val write: MovementWriteRecord) : MovementResult
    data class Invalid(val message: String) : MovementResult
    data class Conflict(val message: String) : MovementResult
}

class AccountService(
    private val db: Database,
    private val repository: AccountRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val householdZone: ZoneId = HOUSEHOLD_ZONE,
) {
    private companion object {
        const val UNIQUE_VIOLATION_SQL_STATE = "23505"
        const val MAX_NAME_LENGTH = 120
        const val MAX_DESCRIPTION_LENGTH = 500
        const val MAX_NOTE_LENGTH = 500
    }

    suspend fun listAccounts(): List<AccountRecord> =
        dbQuery(db) { repository.listAccounts() }

    suspend fun accountDetail(id: UUID): AccountDetailRecord? =
        dbQuery(db) {
            val account = repository.findAccount(id) ?: return@dbQuery null
            AccountDetailRecord(
                account = account,
                assignedBudgets = repository.listAssignedBudgets(id),
            )
        }

    suspend fun createAccount(
        name: String,
        description: String?,
    ): AccountMutationResult {
        val normalizedName = name.trim()
        val normalizedDescription = description.normalizedOptional()
        validateAccount(normalizedName, normalizedDescription)?.let { return it }

        return try {
            val id = dbQuery(db) {
                repository.create(normalizedName, normalizedDescription)
            }
            AccountMutationResult.Created(id)
        } catch (ex: ExposedSQLException) {
            if (ex.sqlState == UNIQUE_VIOLATION_SQL_STATE) {
                AccountMutationResult.DuplicateName(normalizedName)
            } else {
                throw ex
            }
        }
    }

    suspend fun updateAccount(
        id: UUID,
        name: String,
        description: String?,
    ): AccountMutationResult {
        val normalizedName = name.trim()
        val normalizedDescription = description.normalizedOptional()
        validateAccount(normalizedName, normalizedDescription)?.let { return it }

        return try {
            val updated = dbQuery(db) {
                repository.update(
                    id = id,
                    name = normalizedName,
                    description = normalizedDescription,
                    updatedAt = now(),
                )
            }
            if (updated) AccountMutationResult.Updated else AccountMutationResult.NotFound
        } catch (ex: ExposedSQLException) {
            if (ex.sqlState == UNIQUE_VIOLATION_SQL_STATE) {
                AccountMutationResult.DuplicateName(normalizedName)
            } else {
                throw ex
            }
        }
    }

    suspend fun transactions(
        id: UUID,
        limit: Int?,
        cursor: String?,
    ): AccountTransactionsResult {
        val pageSize = limit ?: 50
        if (pageSize !in 1..100) {
            return AccountTransactionsResult.Invalid("'limit' must be between 1 and 100.")
        }

        val decodedCursor = if (cursor == null) {
            null
        } else {
            try {
                repository.decodeCursor(cursor)
            } catch (_: IllegalArgumentException) {
                return AccountTransactionsResult.Invalid("'cursor' is invalid.")
            } catch (_: DateTimeParseException) {
                return AccountTransactionsResult.Invalid("'cursor' is invalid.")
            }
        }

        return dbQuery(db) {
            if (!repository.exists(id)) return@dbQuery AccountTransactionsResult.NotFound
            val rows = repository.listTransactions(id, pageSize + 1, decodedCursor)
            val items = rows.take(pageSize)
            AccountTransactionsResult.Found(
                items = items,
                nextCursor = if (rows.size > pageSize) {
                    repository.encodeCursor(items.last())
                } else {
                    null
                },
            )
        }
    }

    suspend fun preview(input: MovementInput): MovementResult =
        dbQuery(db) {
            val validated = when (val validation = validateMovement(input)) {
                is MovementValidation.Valid -> validation.movement
                is MovementValidation.Invalid ->
                    return@dbQuery MovementResult.Invalid(validation.message)
            }
            MovementResult.Previewed(
                MovementPreviewRecord(
                    calculatedAt = now(),
                    accounts = projections(validated, afterWrite = false),
                ),
            )
        }

    suspend fun record(
        idempotencyKey: String,
        input: MovementInput,
        actorId: String,
    ): MovementResult {
        val key = idempotencyKey.toUuidOrNull()
            ?: return MovementResult.Invalid("'idempotencyKey' must be a UUID.")

        return dbQuery(db) {
            val validated = when (val validation = validateMovement(input)) {
                is MovementValidation.Valid -> validation.movement
                is MovementValidation.Invalid ->
                    return@dbQuery MovementResult.Invalid(validation.message)
            }

            val insertedId = repository.insertMovementIgnore(
                idempotencyKey = key,
                fromAccountId = validated.fromAccountId,
                toAccountId = validated.toAccountId,
                amount = validated.amount,
                occurredAt = validated.occurredAt,
                note = validated.note,
                createdByUserId = actorId,
            )
            val movement = checkNotNull(repository.findMovementByIdempotencyKey(key)) {
                "Money movement insert/replay did not resolve its idempotency key."
            }
            val replayed = insertedId == null
            if (replayed && !movement.matches(actorId, validated)) {
                return@dbQuery MovementResult.Conflict(
                    "This idempotency key was already used for a different money movement.",
                )
            }

            MovementResult.Recorded(
                MovementWriteRecord(
                    movement = movement,
                    calculatedAt = now(),
                    accounts = projections(validated, afterWrite = true),
                    replayed = replayed,
                ),
            )
        }
    }

    /**
     * Called only inside the request's existing database transaction. Keeping preview and write
     * on this single routine prevents their account/date/amount rules from drifting.
     */
    private fun validateMovement(input: MovementInput): MovementValidation {
        val fromId = input.fromAccountId.toUuidOrNull()
        if (input.fromAccountId != null && fromId == null) {
            return MovementValidation.Invalid("'fromAccountId' must be a UUID.")
        }
        val toId = input.toAccountId.toUuidOrNull()
        if (input.toAccountId != null && toId == null) {
            return MovementValidation.Invalid("'toAccountId' must be a UUID.")
        }
        if (fromId == null && toId == null) {
            return MovementValidation.Invalid("At least one endpoint must be a tracked wallet.")
        }
        if (fromId != null && fromId == toId) {
            return MovementValidation.Invalid("From and To must be different.")
        }
        if (input.amount <= 0) {
            return MovementValidation.Invalid("'amount' must be greater than zero.")
        }

        val occurredOn = try {
            LocalDate.parse(input.occurredOn)
        } catch (_: DateTimeParseException) {
            return MovementValidation.Invalid("'occurredOn' must be an ISO date (YYYY-MM-DD).")
        }
        val today = LocalDate.now(clock.withZone(householdZone))
        if (occurredOn > today) {
            return MovementValidation.Invalid("Future money movements are not allowed.")
        }

        val note = input.note.normalizedOptional()
        if (note != null && note.length > MAX_NOTE_LENGTH) {
            return MovementValidation.Invalid("'note' must be $MAX_NOTE_LENGTH characters or fewer.")
        }
        if (fromId != null && !repository.exists(fromId)) {
            return MovementValidation.Invalid("From wallet was not found.")
        }
        if (toId != null && !repository.exists(toId)) {
            return MovementValidation.Invalid("To wallet was not found.")
        }

        val current = now()
        val occurredAt = if (occurredOn == today) {
            current
        } else {
            occurredOn.atStartOfDay(householdZone).toOffsetDateTime()
        }
        return MovementValidation.Valid(
            ValidatedMovement(
                fromAccountId = fromId,
                toAccountId = toId,
                amount = input.amount,
                occurredOn = occurredOn,
                occurredAt = occurredAt,
                note = note,
            ),
        )
    }

    private fun projections(
        movement: ValidatedMovement,
        afterWrite: Boolean,
    ): List<BalanceProjectionRecord> {
        val deltas = linkedMapOf<UUID, Long>()
        movement.fromAccountId?.let { deltas[it] = -movement.amount }
        movement.toAccountId?.let { deltas[it] = (deltas[it] ?: 0L) + movement.amount }
        val accounts = repository.findAccounts(deltas.keys)

        return deltas.map { (accountId, delta) ->
            val account = checkNotNull(accounts[accountId])
            val current = account.balance
            val before = if (afterWrite) current - delta else current
            BalanceProjectionRecord(
                accountId = accountId,
                name = account.name,
                balanceBefore = before,
                delta = delta,
                balanceAfter = before + delta,
            )
        }
    }

    private fun MovementRecord.matches(
        actorId: String,
        movement: ValidatedMovement,
    ): Boolean =
        createdByUserId == actorId &&
            fromAccountId == movement.fromAccountId &&
            toAccountId == movement.toAccountId &&
            amount == movement.amount &&
            occurredAt.atZoneSameInstant(householdZone).toLocalDate() == movement.occurredOn &&
            note == movement.note

    private fun validateAccount(
        name: String,
        description: String?,
    ): AccountMutationResult.Invalid? =
        when {
            name.isBlank() ->
                AccountMutationResult.Invalid("'name' must not be blank.")

            name.length > MAX_NAME_LENGTH ->
                AccountMutationResult.Invalid("'name' must be $MAX_NAME_LENGTH characters or fewer.")

            description != null && description.length > MAX_DESCRIPTION_LENGTH ->
                AccountMutationResult.Invalid(
                    "'description' must be $MAX_DESCRIPTION_LENGTH characters or fewer.",
                )

            else -> null
        }

    private fun now(): OffsetDateTime =
        OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
}

private fun String?.normalizedOptional(): String? =
    this?.trim()?.takeIf(String::isNotEmpty)

private fun String?.toUuidOrNull(): UUID? =
    try {
        this?.let(UUID::fromString)
    } catch (_: IllegalArgumentException) {
        null
    }
