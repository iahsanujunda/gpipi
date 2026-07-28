package me.gpipi.slack

import java.util.UUID
import kotlinx.coroutines.CancellationException
import me.gpipi.auth.AuthService
import org.slf4j.LoggerFactory

private val openBudgetLog = LoggerFactory.getLogger(OpenBudgetCommand::class.java)

class OpenBudgetCommand(
    private val authService: AuthService,
    private val slack: SlackClient,
    webBaseUrl: String,
) : SlackCommand {
    private val webBaseUrl = webBaseUrl.trimEnd('/')

    override fun matches(body: String): Boolean =
        body.equals("open", ignoreCase = true) ||
            body.startsWith("open ", ignoreCase = true)

    override suspend fun handle(
        msg: SlackMessage,
        inboundMessageId: UUID,
    ): SlackCommandOutcome {
        return try {
            val rawNonce = authService.mint(msg.userId)
            val enterUrl = "$webBaseUrl/enter#$rawNonce"
            slack.postEphemeralCard(
                channel = msg.channelId,
                user = msg.userId,
                text = "Open your household budget",
                blocks = openBudgetCard(enterUrl),
            )
            SlackCommandOutcome.Completed
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            openBudgetLog.error("Open budget command failed", ex)
            postFailureFeedback(msg)
            SlackCommandOutcome.Failed(ex.commandFailureReason())
        }
    }

    private suspend fun postFailureFeedback(msg: SlackMessage) {
        try {
            slack.postEphemeral(
                channel = msg.channelId,
                user = msg.userId,
                text = "Couldn't open your budget right now — try again shortly.",
            )
        } catch (ex: CancellationException) {
            throw ex
        } catch (feedbackFailure: Exception) {
            openBudgetLog.warn("Could not send open budget failure feedback", feedbackFailure)
        }
    }
}
