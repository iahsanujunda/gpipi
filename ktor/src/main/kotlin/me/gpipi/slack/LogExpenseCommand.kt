package me.gpipi.slack

import java.util.UUID
import me.gpipi.config.dbQuery
import me.gpipi.expense.ExpenseDraftRepository
import me.gpipi.extraction.ExtractionException
import me.gpipi.extraction.ExtractionService
import me.gpipi.inbound.InboundRepository
import org.jetbrains.exposed.v1.jdbc.Database

class LogExpenseCommand(
    private val db: Database,
    private val inboundRepo: InboundRepository,
    private val extractionService: ExtractionService,
    private val draftRepo: ExpenseDraftRepository,
    private val slack: SlackClient,
) : SlackCommand {
    // This is the explicit dispatcher default, so it is never selected by matching.
    override fun matches(body: String): Boolean = false

    override suspend fun handle(msg: SlackMessage, inboundMessageId: UUID) {
        val (extraction, categoryId, categories) = try {
            extractionService.extract(msg.text)
        } catch (ex: ExtractionException) {
            dbQuery(db) {
                inboundRepo.markFailed(inboundMessageId, ex.message)
            }
            slack.postMessage(msg.channelId, "Couldn't read that one, mind rephrasing?")
            return
        }

        val draftId = dbQuery(db) {
            draftRepo.insert(
                inboundMessageId = inboundMessageId,
                userId = msg.userId,
                channelId = msg.channelId,
                amount = extraction.amount,
                currency = extraction.currency,
                merchant = extraction.merchant,
                note = extraction.note,
                predictedCategoryId = categoryId,
                confidence = extraction.confidence,
                model = null,
            )
        }

        slack.postCard(
            channel = msg.channelId,
            text = "¥${extraction.amount} · ${extraction.category}",
            blocks = expenseCard(
                draftId = draftId,
                amount = extraction.amount,
                merchant = extraction.merchant,
                predictedCategoryId = categoryId,
                categories = categories,
            ),
        )
    }
}
