package me.gpipi.expense

import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.Serializable
import me.gpipi.extraction.Extraction
import me.gpipi.generated.db.base.public1.Category
import me.gpipi.generated.db.base.public1.Expense
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select

@Serializable
data class ExpenseRow(
    val id: String,
    val amount: Long,
    val merchant: String?,
    val spentAt: String,
    val categoryName: String,
)

class ExpenseRepository {
    fun insert(x: Extraction, inboundMessageId: UUID, userId: String, categoryId: UUID): UUID {
        return this.insert(
            inboundMessageId = inboundMessageId,
            userId = userId,
            amount = x.amount,
            currency = x.currency,
            merchant = x.merchant,
            note = x.note,
            categoryId = categoryId,
        )
    }

    fun insert(
        inboundMessageId: UUID,
        userId: String,
        amount: Long,
        currency: String,
        merchant: String?,
        note: String?,
        categoryId: UUID,
    ): UUID {
        val id = UUID.randomUUID()
        Expense.insert {
            it[Expense.id]               = id
            it[Expense.inboundMessageId] = inboundMessageId
            it[Expense.userId]           = userId
            it[Expense.amount]           = amount
            it[Expense.currency]         = currency
            it[Expense.categoryId]       = categoryId
            it[Expense.merchant]         = merchant
            it[Expense.note]             = note
        }
        return id
    }

    fun list(
        from: OffsetDateTime?,
        to: OffsetDateTime?,
        categoryId: UUID?,
    ): List<ExpenseRow> {
        val query = (Expense innerJoin Category)
            .select(
                Expense.id,
                Expense.amount,
                Expense.merchant,
                Expense.spentAt,
                Category.name,
            )
            .where { Op.TRUE }

        from?.let { query.andWhere { Expense.spentAt greaterEq it } }
        to?.let { query.andWhere { Expense.spentAt lessEq it } }
        categoryId?.let { query.andWhere { Expense.categoryId eq it } }

        return query
            .orderBy(Expense.spentAt to SortOrder.DESC)
            .map {
                ExpenseRow(
                    id = it[Expense.id].toString(),
                    amount = it[Expense.amount],
                    merchant = it[Expense.merchant],
                    spentAt = it[Expense.spentAt].toString(),
                    categoryName = it[Category.name],
                )
            }
    }

    fun sumAmount(
        categoryId: UUID,
        fromInclusive: OffsetDateTime,
        toExclusive: OffsetDateTime,
    ): Long {
        require(fromInclusive <= toExclusive) { "fromInclusive must not be after toExclusive" }

        val totalAmount = Expense.amount.sum()

        return Expense
            .select(totalAmount)
            .where {
                (Expense.categoryId eq categoryId) and
                    (Expense.spentAt greaterEq fromInclusive) and
                    (Expense.spentAt less toExclusive)
            }
            .single()[totalAmount]
            ?: 0L
    }
}
