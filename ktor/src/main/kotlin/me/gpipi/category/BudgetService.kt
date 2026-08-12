package me.gpipi.category

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.serialization.Serializable
import me.gpipi.config.dbQuery
import me.gpipi.expense.ExpenseRepository
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database

internal val DEFAULT_BUDGET_ZONE: ZoneId = ZoneId.of("Asia/Tokyo")

sealed interface BudgetMutationResult {
    data class Created(val id: UUID) : BudgetMutationResult

    data object Updated : BudgetMutationResult

    data object NotFound : BudgetMutationResult

    data class Invalid(val message: String) : BudgetMutationResult

    data class DuplicateName(val name: String) : BudgetMutationResult
}

@Serializable
data class SpendRow(
    val categoryId: String,
    val name: String,
    val period: String,
    val windowStart: String,
    val windowEndExclusive: String,
    val baseCap: Long,
    val appliedCarry: Long,
    val effectiveAllowance: Long,
    val spent: Long,
    val remaining: Long,
    val carryForward: CarryForwardRow? = null,
)

@Serializable
data class CarryForwardRow(
    val status: String,
    val amount: Long,
    val sourceWindowStart: String,
    val sourceWindowEndExclusive: String,
    val sourceAllowance: Long,
    val sourceSpent: Long,
)

data class CarryForwardWrite(
    val categoryId: String,
    val targetWindowStart: String,
    val amount: Long,
    val effectiveAllowance: Long,
    val replayed: Boolean,
)

sealed interface CarryForwardResult {
    data class Applied(val write: CarryForwardWrite) : CarryForwardResult

    data object NotFound : CarryForwardResult

    data class Invalid(val message: String) : CarryForwardResult

    data class Conflict(val message: String) : CarryForwardResult
}

class BudgetService(
    private val db: Database,
    private val categoryRepo: CategoryRepository,
    private val expenseRepo: ExpenseRepository,
    private val carryForwardRepo: BudgetCarryForwardRepository = BudgetCarryForwardRepository(),
    private val activeCategories: ActiveCategoryRebuilder,
    private val budgetZone: ZoneId = DEFAULT_BUDGET_ZONE,
    private val clock: Clock = Clock.systemUTC(),
) {
    private companion object {
        const val UNIQUE_VIOLATION_SQL_STATE = "23505"
        const val FOREIGN_KEY_VIOLATION_SQL_STATE = "23503"
        val SUPPORTED_PERIODS = setOf("WEEKLY", "MONTHLY")
    }

    suspend fun listBudgets(): List<BudgetRow> =
        dbQuery(db) {
            categoryRepo.listBudgets()
        }

    suspend fun create(request: UpsertBudgetRequest): BudgetMutationResult {
        validate(request)?.let { return it }
        val accountId = UUID.fromString(request.accountId)

        return try {
            val id = dbQuery(db) {
                categoryRepo.create(
                    name = request.name,
                    description = request.description,
                    period = request.period,
                    amount = request.amount,
                    active = request.active,
                    slackLoggable = request.slackLoggable,
                    accountId = accountId,
                )
            }
            activeCategories.advanceAndRebuild()
            BudgetMutationResult.Created(id)
        } catch (ex: ExposedSQLException) {
            when (ex.sqlState) {
                UNIQUE_VIOLATION_SQL_STATE ->
                    BudgetMutationResult.DuplicateName(request.name)

                FOREIGN_KEY_VIOLATION_SQL_STATE ->
                    BudgetMutationResult.Invalid("Selected wallet was not found.")

                else -> throw ex
            }
        }
    }

    suspend fun update(id: UUID, request: UpsertBudgetRequest): BudgetMutationResult {
        validate(request)?.let { return it }
        val accountId = UUID.fromString(request.accountId)

        val updated = try {
            dbQuery(db) {
                categoryRepo.update(
                    id = id,
                    name = request.name,
                    description = request.description,
                    period = request.period,
                    amount = request.amount,
                    active = request.active,
                    slackLoggable = request.slackLoggable,
                    accountId = accountId,
                )
            }
        } catch (ex: ExposedSQLException) {
            when (ex.sqlState) {
                UNIQUE_VIOLATION_SQL_STATE ->
                    return BudgetMutationResult.DuplicateName(request.name)

                FOREIGN_KEY_VIOLATION_SQL_STATE ->
                    return BudgetMutationResult.Invalid("Selected wallet was not found.")
            }
            throw ex
        }

        return if (updated) {
            activeCategories.advanceAndRebuild()
            BudgetMutationResult.Updated
        } else {
            BudgetMutationResult.NotFound
        }
    }

    suspend fun deactivate(id: UUID): BudgetMutationResult {
        val updated = dbQuery(db) {
            categoryRepo.deactivate(id)
        }
        return if (updated) {
            activeCategories.advanceAndRebuild()
            BudgetMutationResult.Updated
        } else {
            BudgetMutationResult.NotFound
        }
    }

    suspend fun spendVsCap(date: LocalDate): List<SpendRow> = dbQuery(db) {
        categoryRepo.listBudgets().map { b ->
            val period = requireNotNull(BudgetPeriod.from(b.period)) {
                "Unknown budget period: ${b.period}"
            }
            val bucket = period.bucketFor(date, budgetZone)
            val categoryId = UUID.fromString(b.id)
            val applied = carryForwardRepo.findForTarget(
                categoryId,
                b.period,
                bucket.startInclusive.toLocalDate(),
            )
            val appliedCarry = applied?.amount ?: 0L
            val effectiveAllowance = b.amount + appliedCarry
            val spent = expenseRepo.sumAmount(
                categoryId,
                bucket.startInclusive,
                bucket.endExclusive,
            )
            SpendRow(
                categoryId = b.id,
                name = b.name,
                period = b.period,
                windowStart = bucket.startInclusive.toLocalDate().toString(),
                windowEndExclusive = bucket.endExclusive.toLocalDate().toString(),
                baseCap = b.amount,
                appliedCarry = appliedCarry,
                effectiveAllowance = effectiveAllowance,
                spent = spent,
                remaining = effectiveAllowance - spent,
                carryForward = carryForwardFor(
                    budget = b,
                    period = period,
                    targetBucket = bucket,
                    applied = applied,
                    selectedDate = date,
                ),
            )
        }
    }

    suspend fun applyCarryForward(
        categoryId: UUID,
        targetWindowStart: String,
        expectedAmount: Long,
        actorId: String,
    ): CarryForwardResult {
        val requestedTarget = try {
            LocalDate.parse(targetWindowStart)
        } catch (_: DateTimeParseException) {
            return CarryForwardResult.Invalid("'targetWindowStart' must be an ISO-8601 date (YYYY-MM-DD).")
        }
        if (expectedAmount == 0L) {
            return CarryForwardResult.Invalid("There is no previous balance to carry forward.")
        }
        if (actorId.isBlank()) {
            return CarryForwardResult.Invalid("The applying user must not be blank.")
        }

        return dbQuery(db) {
            val budget = categoryRepo.listBudgets().singleOrNull { it.id == categoryId.toString() }
                ?: return@dbQuery CarryForwardResult.NotFound
            if (budget.amount == 0L) {
                return@dbQuery CarryForwardResult.Invalid("A budget line with no cap cannot carry a balance forward.")
            }
            val period = requireNotNull(BudgetPeriod.from(budget.period)) {
                "Unknown budget period: ${budget.period}"
            }
            val currentBucket = period.bucketFor(
                LocalDate.now(clock.withZone(budgetZone)),
                budgetZone,
            )
            val currentStart = currentBucket.startInclusive.toLocalDate()
            if (requestedTarget != currentStart) {
                return@dbQuery CarryForwardResult.Conflict(
                    "The selected budget period is no longer current. Refresh and review the carry-forward again.",
                )
            }

            val existing = carryForwardRepo.findForTarget(categoryId, budget.period, currentStart)
            if (existing != null) {
                return@dbQuery if (existing.amount == expectedAmount) {
                    CarryForwardResult.Applied(existing.toWrite(budget.amount, replayed = true))
                } else {
                    CarryForwardResult.Conflict("A carry-forward has already been applied to this period.")
                }
            }

            val sourceBucket = period.bucketFor(currentStart.minusDays(1), budgetZone)
            val sourceStart = sourceBucket.startInclusive.toLocalDate()
            val sourceIncomingCarry = carryForwardRepo
                .findForTarget(categoryId, budget.period, sourceStart)
                ?.amount
                ?: 0L
            val sourceSpent = expenseRepo.sumAmount(
                categoryId,
                sourceBucket.startInclusive,
                sourceBucket.endExclusive,
            )
            val amount = budget.amount + sourceIncomingCarry - sourceSpent
            if (amount == 0L) {
                return@dbQuery CarryForwardResult.Invalid("There is no previous balance to carry forward.")
            }
            if (amount != expectedAmount) {
                return@dbQuery CarryForwardResult.Conflict(
                    "The previous balance changed. Refresh and review the carry-forward again.",
                )
            }

            val inserted = carryForwardRepo.insertOrFind(
                NewBudgetCarryForward(
                    categoryId = categoryId,
                    cadence = budget.period,
                    sourceWindowStart = sourceStart,
                    sourceWindowEndExclusive = sourceBucket.endExclusive.toLocalDate(),
                    targetWindowStart = currentStart,
                    targetWindowEndExclusive = currentBucket.endExclusive.toLocalDate(),
                    amount = amount,
                    sourceBaseCap = budget.amount,
                    sourceIncomingCarry = sourceIncomingCarry,
                    sourceSpent = sourceSpent,
                    createdByUserId = actorId,
                ),
            )
            if (inserted.record.amount != expectedAmount) {
                CarryForwardResult.Conflict("A carry-forward has already been applied to this period.")
            } else {
                CarryForwardResult.Applied(
                    inserted.record.toWrite(budget.amount, replayed = !inserted.inserted),
                )
            }
        }
    }

    private fun carryForwardFor(
        budget: BudgetRow,
        period: BudgetPeriod,
        targetBucket: BudgetBucket,
        applied: BudgetCarryForwardRecord?,
        selectedDate: LocalDate,
    ): CarryForwardRow? {
        if (applied != null) {
            return CarryForwardRow(
                status = "APPLIED",
                amount = applied.amount,
                sourceWindowStart = applied.sourceWindowStart.toString(),
                sourceWindowEndExclusive = applied.sourceWindowEndExclusive.toString(),
                sourceAllowance = applied.sourceBaseCap + applied.sourceIncomingCarry,
                sourceSpent = applied.sourceSpent,
            )
        }
        if (budget.amount == 0L) return null

        val currentBucket = period.bucketFor(
            LocalDate.now(clock.withZone(budgetZone)),
            budgetZone,
        )
        if (targetBucket.startInclusive != currentBucket.startInclusive) return null
        if (period.bucketFor(selectedDate, budgetZone).startInclusive != currentBucket.startInclusive) return null

        val categoryId = UUID.fromString(budget.id)
        val sourceBucket = period.bucketFor(
            targetBucket.startInclusive.toLocalDate().minusDays(1),
            budgetZone,
        )
        val sourceIncomingCarry = carryForwardRepo.findForTarget(
            categoryId,
            budget.period,
            sourceBucket.startInclusive.toLocalDate(),
        )?.amount ?: 0L
        val sourceSpent = expenseRepo.sumAmount(
            categoryId,
            sourceBucket.startInclusive,
            sourceBucket.endExclusive,
        )
        val amount = budget.amount + sourceIncomingCarry - sourceSpent
        if (amount == 0L) return null

        return CarryForwardRow(
            status = "AVAILABLE",
            amount = amount,
            sourceWindowStart = sourceBucket.startInclusive.toLocalDate().toString(),
            sourceWindowEndExclusive = sourceBucket.endExclusive.toLocalDate().toString(),
            sourceAllowance = budget.amount + sourceIncomingCarry,
            sourceSpent = sourceSpent,
        )
    }

    private fun validate(request: UpsertBudgetRequest): BudgetMutationResult.Invalid? =
        when {
            request.name.isBlank() ->
                BudgetMutationResult.Invalid("'name' must not be blank.")

            request.description.isBlank() ->
                BudgetMutationResult.Invalid("'description' must not be blank.")

            request.period !in SUPPORTED_PERIODS ->
                BudgetMutationResult.Invalid("'period' must be WEEKLY or MONTHLY.")

            request.amount < 0 ->
                BudgetMutationResult.Invalid("'amount' must be zero or greater.")

            request.accountId.toUuidOrNull() == null ->
                BudgetMutationResult.Invalid("'accountId' must be a UUID.")

            else -> null
        }
}

private fun BudgetCarryForwardRecord.toWrite(
    baseCap: Long,
    replayed: Boolean,
): CarryForwardWrite = CarryForwardWrite(
    categoryId = categoryId.toString(),
    targetWindowStart = targetWindowStart.toString(),
    amount = amount,
    effectiveAllowance = baseCap + amount,
    replayed = replayed,
)

private fun String.toUuidOrNull(): UUID? =
    try {
        UUID.fromString(this)
    } catch (_: IllegalArgumentException) {
        null
    }
