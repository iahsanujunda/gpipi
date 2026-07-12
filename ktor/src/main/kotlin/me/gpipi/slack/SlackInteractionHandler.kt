package me.gpipi.slack

import java.util.UUID
import me.gpipi.categorization.CategorizationEventRepository
import me.gpipi.config.dbQuery
import me.gpipi.expense.ExpenseDraftRepository
import me.gpipi.expense.ExpenseRepository
import me.gpipi.inbound.InboundRepository
import org.jetbrains.exposed.v1.jdbc.Database

class SlackInteractionHandler(
    private val db: Database,
    private val draftRepo: ExpenseDraftRepository,
    private val expenseRepo: ExpenseRepository,
    private val inboundRepo: InboundRepository,
    private val eventRepo: CategorizationEventRepository,
    private val slack: SlackClient
) {
    suspend fun handleConfirm(draftId: UUID, finalCategoryId: UUID) {
        val draft = dbQuery(this.db) {
            val d = draftRepo.consumeIfPending(draftId) ?: return@dbQuery null
            val expenseId = expenseRepo.insert(
                inboundMessageId = d.inboundMessageId,
                userId = d.userId,
                amount = d.amount,
                currency = d.currency,
                merchant = d.merchant,
                note = d.note,
                categoryId = finalCategoryId
            )
            eventRepo.insert(
                inboundMessageId    = d.inboundMessageId,
                expenseId           = expenseId,
                predictedCategoryId = d.predictedCategoryId,
                finalCategoryId     = finalCategoryId,
                confidence          = d.confidence?.toDouble(),
                model               = d.model,
            )
            inboundRepo.markRecorded(d.inboundMessageId)
            d
        } ?: return
        slack.postMessage(draft.channelId, "Recorded ✓  ¥${draft.amount} · …")
    }
}