package me.gpipi.category

import java.time.LocalDate
import java.time.ZoneId
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
    val cap: Long,
    val spent: Long,
    val remaining: Long,
)

class BudgetService(
    private val db: Database,
    private val categoryRepo: CategoryRepository,
    private val expenseRepo: ExpenseRepository,
    private val activeCategories: ActiveCategoryRebuilder,
    private val budgetZone: ZoneId = DEFAULT_BUDGET_ZONE,
) {
    private companion object {
        val SUPPORTED_PERIODS = setOf("WEEKLY", "MONTHLY")
    }

    suspend fun listBudgets(): List<BudgetRow> =
        dbQuery(db) {
            categoryRepo.listBudgets()
        }

    suspend fun create(request: UpsertBudgetRequest): BudgetMutationResult {
        validate(request)?.let { return it }

        return try {
            val id = dbQuery(db) {
                categoryRepo.create(
                    name = request.name,
                    description = request.description,
                    period = request.period,
                    amount = request.amount,
                    active = request.active,
                    slackLoggable = request.slackLoggable,
                )
            }
            activeCategories.advanceAndRebuild()
            BudgetMutationResult.Created(id)
        } catch (_: ExposedSQLException) {
            BudgetMutationResult.DuplicateName(request.name)
        }
    }

    suspend fun update(id: UUID, request: UpsertBudgetRequest): BudgetMutationResult {
        validate(request)?.let { return it }

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
                )
            }
        } catch (_: ExposedSQLException) {
            return BudgetMutationResult.DuplicateName(request.name)
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
            val spent = expenseRepo.sumAmount(
                UUID.fromString(b.id),
                bucket.startInclusive,
                bucket.endExclusive,
            )
            SpendRow(
                categoryId = b.id,
                name = b.name,
                period = b.period,
                windowStart = bucket.startInclusive.toLocalDate().toString(),
                windowEndExclusive = bucket.endExclusive.toLocalDate().toString(),
                cap = b.amount,
                spent = spent,
                remaining = b.amount - spent,
            )
        }
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

            else -> null
        }
}
