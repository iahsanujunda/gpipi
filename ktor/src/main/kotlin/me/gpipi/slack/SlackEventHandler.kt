package me.gpipi.slack

import me.gpipi.config.dbQuery
import me.gpipi.expense.ExpenseRepository
import me.gpipi.extraction.ExtractionException
import me.gpipi.extraction.OpenRouterClient
import me.gpipi.inbound.InboundRepository
import org.jetbrains.exposed.v1.jdbc.Database

class SlackEventHandler(
    private val db: Database,
    private val inboundRepo: InboundRepository,
    private val expenseRepo: ExpenseRepository,
    private val orClient: OpenRouterClient,
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

        val x = try {
            orClient.extract(text)
        } catch (ex: ExtractionException) {
            dbQuery(db) { inboundRepo.markFailed(msgId, ex.message) }
            slack.postMessage(channel, "Couldn't read that one, mind rephrasing?")
            return
        }

        dbQuery(db) {
            expenseRepo.insert(x, inboundMessageId = msgId, userId = user)
            inboundRepo.markRecorded(msgId)
        }

        slack.postMessage(channel, "Recorded ✓  ¥${x.amount} · ${x.category}")
    }
}