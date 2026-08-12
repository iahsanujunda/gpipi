package me.gpipi.category

import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import me.gpipi.generated.db.base.public1.BudgetCarryForward
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll

data class BudgetCarryForwardRecord(
    val id: UUID,
    val categoryId: UUID,
    val cadence: String,
    val sourceWindowStart: LocalDate,
    val sourceWindowEndExclusive: LocalDate,
    val targetWindowStart: LocalDate,
    val targetWindowEndExclusive: LocalDate,
    val amount: Long,
    val sourceBaseCap: Long,
    val sourceIncomingCarry: Long,
    val sourceSpent: Long,
    val createdByUserId: String,
    val createdAt: OffsetDateTime,
)

data class NewBudgetCarryForward(
    val categoryId: UUID,
    val cadence: String,
    val sourceWindowStart: LocalDate,
    val sourceWindowEndExclusive: LocalDate,
    val targetWindowStart: LocalDate,
    val targetWindowEndExclusive: LocalDate,
    val amount: Long,
    val sourceBaseCap: Long,
    val sourceIncomingCarry: Long,
    val sourceSpent: Long,
    val createdByUserId: String,
)

data class BudgetCarryForwardInsert(
    val record: BudgetCarryForwardRecord,
    val inserted: Boolean,
)

class BudgetCarryForwardRepository {
    fun findForTarget(
        categoryId: UUID,
        cadence: String,
        targetWindowStart: LocalDate,
    ): BudgetCarryForwardRecord? =
        BudgetCarryForward
            .selectAll()
            .where {
                (BudgetCarryForward.categoryId eq categoryId) and
                    (BudgetCarryForward.cadence eq cadence) and
                    (BudgetCarryForward.targetWindowStart eq targetWindowStart)
            }
            .singleOrNull()
            ?.let { row ->
                BudgetCarryForwardRecord(
                    id = row[BudgetCarryForward.id],
                    categoryId = row[BudgetCarryForward.categoryId],
                    cadence = row[BudgetCarryForward.cadence],
                    sourceWindowStart = row[BudgetCarryForward.sourceWindowStart],
                    sourceWindowEndExclusive = row[BudgetCarryForward.sourceWindowEndExclusive],
                    targetWindowStart = row[BudgetCarryForward.targetWindowStart],
                    targetWindowEndExclusive = row[BudgetCarryForward.targetWindowEndExclusive],
                    amount = row[BudgetCarryForward.amount],
                    sourceBaseCap = row[BudgetCarryForward.sourceBaseCap],
                    sourceIncomingCarry = row[BudgetCarryForward.sourceIncomingCarry],
                    sourceSpent = row[BudgetCarryForward.sourceSpent],
                    createdByUserId = row[BudgetCarryForward.createdByUserId],
                    createdAt = row[BudgetCarryForward.createdAt],
                )
            }

    fun insertOrFind(input: NewBudgetCarryForward): BudgetCarryForwardInsert {
        val id = UUID.randomUUID()
        val inserted = BudgetCarryForward.insertIgnore {
            it[BudgetCarryForward.id] = id
            it[BudgetCarryForward.categoryId] = input.categoryId
            it[BudgetCarryForward.cadence] = input.cadence
            it[BudgetCarryForward.sourceWindowStart] = input.sourceWindowStart
            it[BudgetCarryForward.sourceWindowEndExclusive] = input.sourceWindowEndExclusive
            it[BudgetCarryForward.targetWindowStart] = input.targetWindowStart
            it[BudgetCarryForward.targetWindowEndExclusive] = input.targetWindowEndExclusive
            it[BudgetCarryForward.amount] = input.amount
            it[BudgetCarryForward.sourceBaseCap] = input.sourceBaseCap
            it[BudgetCarryForward.sourceIncomingCarry] = input.sourceIncomingCarry
            it[BudgetCarryForward.sourceSpent] = input.sourceSpent
            it[BudgetCarryForward.createdByUserId] = input.createdByUserId
        }.insertedCount == 1

        return BudgetCarryForwardInsert(
            record = requireNotNull(
                findForTarget(input.categoryId, input.cadence, input.targetWindowStart),
            ) { "Carry-forward insert completed without a readable target record." },
            inserted = inserted,
        )
    }
}
