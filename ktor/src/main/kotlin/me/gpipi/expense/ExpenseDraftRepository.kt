package me.gpipi.expense

import java.math.BigDecimal
import java.util.UUID
import me.gpipi.generated.db.base.public1.ExpenseDraft
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

data class ExpenseDraftRow(
    val id: UUID,
    val inboundMessageId: UUID,
    val userId: String,
    val channelId: String,
    val amount: Long,
    val currency: String,
    val merchant: String?,
    val note: String?,
    val predictedCategoryId: UUID,
    val confidence: BigDecimal?,
    val model: String?,
)

class ExpenseDraftRepository {
    fun insert(
        inboundMessageId: UUID,
        userId: String,
        channelId: String,
        amount: Long,
        currency: String,
        merchant: String?,
        note: String?,
        predictedCategoryId: UUID,
        confidence: Double?,
        model: String?,
    ): UUID {
        val id = UUID.randomUUID()
        ExpenseDraft.insert {
            it[ExpenseDraft.id]                  = id
            it[ExpenseDraft.inboundMessageId]    = inboundMessageId
            it[ExpenseDraft.userId]              = userId
            it[ExpenseDraft.channelId]           = channelId
            it[ExpenseDraft.amount]              = amount
            it[ExpenseDraft.currency]            = currency
            it[ExpenseDraft.merchant]            = merchant
            it[ExpenseDraft.note]                = note
            it[ExpenseDraft.predictedCategoryId] = predictedCategoryId
            it[ExpenseDraft.confidence]          = confidence?.toBigDecimal()
            it[ExpenseDraft.model]               = model
        }
        return id
    }

    fun consumeIfPending(id: UUID): ExpenseDraftRow? {
        val updated = ExpenseDraft.update(
            { (ExpenseDraft.id eq id) and (ExpenseDraft.status eq "PENDING") }
        ) { it[status] = "CONFIRMED" }
        if (updated == 0) return null
        return ExpenseDraft.selectAll()
            .where { ExpenseDraft.id eq id }
            .single()
            .let { r ->
                ExpenseDraftRow(
                    id = r[ExpenseDraft.id],
                    inboundMessageId = r[ExpenseDraft.inboundMessageId],
                    userId = r[ExpenseDraft.userId],
                    channelId = r[ExpenseDraft.channelId],
                    amount = r[ExpenseDraft.amount],
                    currency = r[ExpenseDraft.currency],
                    merchant = r[ExpenseDraft.merchant],
                    note = r[ExpenseDraft.note],
                    predictedCategoryId = r[ExpenseDraft.predictedCategoryId],
                    confidence = r[ExpenseDraft.confidence],
                    model = r[ExpenseDraft.model],
                )
            }
    }
}
