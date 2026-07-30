package me.gpipi.slack

import java.util.UUID
import kotlinx.coroutines.CancellationException
import me.gpipi.config.dbQuery
import me.gpipi.shopping.ShoppingDraftItemInput
import me.gpipi.shopping.ShoppingExtractionException
import me.gpipi.shopping.ShoppingExtractionService
import me.gpipi.shopping.ShoppingItemText
import me.gpipi.shopping.ShoppingRepository
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory

private val shoppingAddLog = LoggerFactory.getLogger(ShoppingAddCommand::class.java)

class ShoppingAddCommand(
    private val db: Database,
    private val extractionService: ShoppingExtractionService,
    private val repository: ShoppingRepository,
    private val slack: SlackClient,
) : SlackCommand {
    override fun matches(body: String): Boolean =
        body.startsWith(PREFIX, ignoreCase = true) &&
            body.drop(PREFIX.length).isNotBlank()

    override suspend fun handle(
        msg: SlackMessage,
        inboundMessageId: UUID,
    ): SlackCommandOutcome {
        return try {
            val extraction = extractionService.extract(
                msg.body.drop(PREFIX.length).trim(),
            )
            val inputs = extraction.items.map {
                ShoppingDraftItemInput(it.item, it.quantity, it.note)
            }
            val draftId = dbQuery(db) {
                repository.insertAddDraft(
                    inboundMessageId = inboundMessageId,
                    userId = msg.userId,
                    channelId = msg.channelId,
                    items = inputs,
                )
            }
            slack.postCard(
                channel = msg.channelId,
                text = "Confirm shopping-list items",
                blocks = shoppingAddConfirmationCard(
                    draftId = draftId,
                    items = inputs.map {
                        ShoppingItemText(it.item, it.quantity, it.note)
                    },
                ),
            )
            SlackCommandOutcome.Completed
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            shoppingAddLog.error("Shopping add command failed", ex)
            postFailureFeedback(msg, ex)
            SlackCommandOutcome.Failed(ex.commandFailureReason())
        }
    }

    private suspend fun postFailureFeedback(msg: SlackMessage, cause: Exception) {
        val text = if (cause is ShoppingExtractionException) {
            "Couldn't read those shopping items — try rephrasing them."
        } else {
            "Couldn't prepare the shopping list right now — try again shortly."
        }
        try {
            slack.postMessage(msg.channelId, text)
        } catch (ex: CancellationException) {
            throw ex
        } catch (feedbackFailure: Exception) {
            shoppingAddLog.warn("Could not send shopping add failure feedback", feedbackFailure)
        }
    }

    private companion object {
        const val PREFIX = "list add "
    }
}
