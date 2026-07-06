package me.gpipi.expense

import java.util.UUID
import me.gpipi.extraction.Extraction
import me.gpipi.inbound.InboundMessages
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insertAndGetId

class ExpenseRepository {
    fun insert(x: Extraction, inboundMessageId: UUID, userId: String): UUID =
        Expenses.insertAndGetId {
            it[Expenses.inboundMessageId] = EntityID(inboundMessageId, InboundMessages)
            it[Expenses.userId] = userId
            it[Expenses.amount] = x.amount
            it[Expenses.currency] = x.currency
            it[Expenses.category] = x.category
            it[Expenses.merchant] = x.merchant
            it[Expenses.note] = x.note
        }.value
}