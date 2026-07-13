package me.gpipi.slack

import me.gpipi.config.dbQuery
import me.gpipi.expense.ExpenseDraftRepository
import me.gpipi.extraction.ExtractionException
import me.gpipi.extraction.ExtractionService
import me.gpipi.inbound.InboundRepository
import org.jetbrains.exposed.v1.jdbc.Database

class SlackEventHandler(
    private val db: Database,
    private val inboundRepo: InboundRepository,
    private val extractionService: ExtractionService,
    private val draftRepo: ExpenseDraftRepository,
    private val slack: SlackClient
) {
    suspend fun handle(payload: SlackEnvelope) {
        val event = payload.event ?: return
        val eventId = payload.eventId ?: return
        val (user, channel, ts) = Triple(event.user, event.channel, event.ts)
        val text = event.text

        if (event.type != "app_mention" || user == null || channel == null || ts == null || text.isNullOrBlank()) return

        val msgId = dbQuery(db) {
            inboundRepo.captureOrSkip(eventId, user, channel, text, ts)
        } ?: return

        // categories is the cached active list extract() already loaded — reused for the card
        // dropdown so we don't re-query. Inbound stays RECEIVED; the expense is written on Confirm.
        val (x, categoryId, categories) = try {
            extractionService.extract(text)
        } catch (ex: ExtractionException) {
            dbQuery(db) { inboundRepo.markFailed(msgId, ex.message) }
            slack.postMessage(channel, "Couldn't read that one, mind rephrasing?")
            return
        }

        val draftId = dbQuery(db) {
            draftRepo.insert(
                inboundMessageId = msgId,
                userId = user,
                channelId = channel,
                amount = x.amount,
                currency = x.currency,
                merchant = x.merchant,
                note = x.note,
                predictedCategoryId = categoryId,
                confidence = x.confidence,
                model = null,
            )
        }

        slack.postCard(
            channel,
            text = "¥${x.amount} · ${x.category}",
            blocks = expenseCard(draftId, x.amount, x.merchant, categoryId, categories),
        )
    }
}
