package me.gpipi.expense

import java.util.UUID
import me.gpipi.extraction.Extraction
import me.gpipi.generated.db.base.public1.Expense
import org.jetbrains.exposed.v1.jdbc.insert

class ExpenseRepository {
    fun insert(x: Extraction, inboundMessageId: UUID, userId: String): UUID {
        val id = UUID.randomUUID()
        Expense.insert {
            it[Expense.id]               = id                // client-side, overrides gen_random_uuid()
            it[Expense.inboundMessageId] = inboundMessageId  // plain Column<UUID>, no EntityID wrap
            it[Expense.userId]   = userId
            it[Expense.amount]   = x.amount
            it[Expense.currency] = x.currency
            it[Expense.category] = x.category
            it[Expense.merchant] = x.merchant
            it[Expense.note]     = x.note
            // spentAt / source1 / createdAt → DB defaults (now / SLACK / now)
        }
        return id
    }
}