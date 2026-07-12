package me.gpipi.expense

import java.util.UUID
import me.gpipi.extraction.Extraction
import me.gpipi.generated.db.base.public1.Expense
import org.jetbrains.exposed.v1.jdbc.insert

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
}