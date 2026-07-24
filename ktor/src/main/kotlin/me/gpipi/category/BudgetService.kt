package me.gpipi.category

import java.util.UUID
import me.gpipi.config.dbQuery
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database

sealed interface BudgetMutationResult {
    data class Created(val id: UUID) : BudgetMutationResult

    data object Updated : BudgetMutationResult

    data object NotFound : BudgetMutationResult

    data class Invalid(val message: String) : BudgetMutationResult

    data class DuplicateName(val name: String) : BudgetMutationResult
}

class BudgetService(
    private val db: Database,
    private val categoryRepo: CategoryRepository,
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

        return if (updated) BudgetMutationResult.Updated else BudgetMutationResult.NotFound
    }

    suspend fun deactivate(id: UUID): BudgetMutationResult {
        val updated = dbQuery(db) {
            categoryRepo.deactivate(id)
        }
        return if (updated) BudgetMutationResult.Updated else BudgetMutationResult.NotFound
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
