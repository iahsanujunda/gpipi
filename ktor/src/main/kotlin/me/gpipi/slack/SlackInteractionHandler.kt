package me.gpipi.slack

import java.util.UUID
import me.gpipi.categorization.CategorizationEventRepository
import me.gpipi.config.dbQuery
import me.gpipi.expense.ExpenseDraftRepository
import me.gpipi.expense.ExpenseRepository
import me.gpipi.inbound.InboundRepository
import me.gpipi.shopping.ShoppingItemText
import me.gpipi.shopping.ShoppingService
import org.jetbrains.exposed.v1.jdbc.Database

class SlackInteractionHandler(
    private val db: Database,
    private val draftRepo: ExpenseDraftRepository,
    private val expenseRepo: ExpenseRepository,
    private val inboundRepo: InboundRepository,
    private val eventRepo: CategorizationEventRepository,
    private val shoppingService: ShoppingService,
    private val slack: SlackClient
) {
    suspend fun handleConfirm(draftId: UUID, finalCategoryId: UUID, categoryName: String, responseUrl: String?) {
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
        val reply = "Recorded ✓  ¥${draft.amount} · $categoryName"
        if (responseUrl != null) slack.replaceCard(responseUrl, reply)
        else slack.postMessage(draft.channelId, reply)
    }

    suspend fun handleShoppingAddConfirm(
        draftId: UUID,
        actorId: String,
        responseUrl: String?,
    ) {
        val result = shoppingService.confirmAdd(draftId, actorId) ?: return
        val feedback = when {
            result.changed.isEmpty() -> "Already on the list"
            result.skipped.isEmpty() -> "Added ${describe(result.changed)} ✓"
            else -> "Added ${describe(result.changed)} ✓\nAlready on the list: ${describe(result.skipped)}"
        }
        val blocks = shoppingActionResultCard(feedback, result.mutationId)
        if (responseUrl != null) {
            slack.replaceCard(responseUrl, feedback, blocks)
        }
    }

    suspend fun handleShoppingAddCancel(
        draftId: UUID,
        responseUrl: String?,
    ) {
        if (!shoppingService.cancelAdd(draftId)) return
        if (responseUrl != null) {
            slack.replaceCard(
                responseUrl,
                "Nothing added",
                shoppingActionResultCard("Nothing added"),
            )
        }
    }

    suspend fun handleShoppingMarkBought(
        itemIds: Collection<UUID>,
        actorId: String,
        responseUrl: String?,
    ) {
        val result = shoppingService.markBought(itemIds, actorId)
        val feedback = if (result.changed.isEmpty()) {
            "No shopping items changed."
        } else {
            "${describe(result.changed)} marked bought ✓"
        }
        replaceShoppingList(responseUrl, feedback, result.mutationId)
    }

    suspend fun handleShoppingUndo(
        mutationId: UUID,
        actorId: String,
        responseUrl: String?,
    ) {
        val result = shoppingService.undo(mutationId, actorId)
        val feedback = when {
            result == null -> "Nothing to undo."
            result.changed.isEmpty() -> "Nothing to undo; those items changed afterward."
            result.targetKind == "ADD" ->
                "Removed ${describe(result.changed)} from the list." +
                    skippedUndoFeedback(result.skipped)

            else ->
                "Restored ${describe(result.changed)} to the list." +
                    skippedUndoFeedback(result.skipped)
        }
        replaceShoppingList(responseUrl, feedback, undoMutationId = null)
    }

    private suspend fun replaceShoppingList(
        responseUrl: String?,
        feedback: String,
        undoMutationId: UUID?,
    ) {
        if (responseUrl == null) return
        val pending = shoppingService.listPending().map {
            ShoppingListItem(it.id, it.item, it.quantity, it.note)
        }
        slack.replaceCard(
            responseUrl = responseUrl,
            text = feedback,
            blocks = shoppingListCard(
                items = pending,
                feedback = feedback,
                undoMutationId = undoMutationId,
            ),
        )
    }

    private fun describe(items: List<ShoppingItemText>): String =
        items.joinToString(" and ") {
            listOfNotNull(it.item, it.quantity, it.note).joinToString(" · ")
        }

    private fun skippedUndoFeedback(items: List<ShoppingItemText>): String =
        if (items.isEmpty()) {
            ""
        } else {
            " Skipped ${describe(items)} because they changed afterward or are already present."
        }
}
