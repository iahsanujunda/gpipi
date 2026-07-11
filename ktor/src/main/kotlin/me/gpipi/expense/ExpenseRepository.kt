package me.gpipi.expense

import java.util.UUID
import me.gpipi.extraction.Extraction
import me.gpipi.generated.db.base.public1.Expense
import org.jetbrains.exposed.v1.jdbc.insert

class ExpenseRepository {
    fun insert(x: Extraction, inboundMessageId: UUID, userId: String, categoryId: UUID): UUID {
        val id = UUID.randomUUID()
        Expense.insert {
            it[Expense.id]               = id
            it[Expense.inboundMessageId] = inboundMessageId
            it[Expense.userId]           = userId
            it[Expense.amount]           = x.amount
            it[Expense.currency]         = x.currency
            it[Expense.categoryId]       = categoryId
            it[Expense.merchant]         = x.merchant
            it[Expense.note]             = x.note
        }
        return id
    }
}