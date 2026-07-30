package me.gpipi.slack

import java.util.UUID
import kotlinx.coroutines.CancellationException
import me.gpipi.shopping.ShoppingService
import org.slf4j.LoggerFactory

private val shoppingShowLog = LoggerFactory.getLogger(ShoppingShowCommand::class.java)

class ShoppingShowCommand(
    private val shoppingService: ShoppingService,
    private val slack: SlackClient,
) : SlackCommand {
    override fun matches(body: String): Boolean =
        body.equals("list", ignoreCase = true)

    override suspend fun handle(
        msg: SlackMessage,
        inboundMessageId: UUID,
    ): SlackCommandOutcome {
        return try {
            val items = shoppingService.listPending().map {
                ShoppingListItem(it.id, it.item, it.quantity, it.note)
            }
            slack.postCard(
                channel = msg.channelId,
                text = if (items.isEmpty()) "Shopping list is empty" else "Shopping list",
                blocks = shoppingListCard(items),
            )
            SlackCommandOutcome.Completed
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            shoppingShowLog.error("Shopping list command failed", ex)
            try {
                slack.postMessage(
                    msg.channelId,
                    "Couldn't open the shopping list right now — try again shortly.",
                )
            } catch (feedbackFailure: CancellationException) {
                throw feedbackFailure
            } catch (feedbackFailure: Exception) {
                shoppingShowLog.warn("Could not send shopping list failure feedback", feedbackFailure)
            }
            SlackCommandOutcome.Failed(ex.commandFailureReason())
        }
    }
}
