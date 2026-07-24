package me.gpipi.slack

import java.util.UUID
import kotlinx.coroutines.CancellationException
import me.gpipi.auth.AuthService

class OpenBudgetCommand(
    private val authService: AuthService,
    private val slack: SlackClient,
    webBaseUrl: String,
) : SlackCommand {
    private val webBaseUrl = webBaseUrl.trimEnd('/')

    override fun matches(body: String): Boolean =
        body.equals("open", ignoreCase = true) ||
            body.startsWith("open ", ignoreCase = true)

    override suspend fun handle(msg: SlackMessage, inboundMessageId: UUID) {
        // The dispatcher has already captured this inbound id, helps with deduplication.
        val rawNonce = try {
            authService.mint(msg.userId)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            slack.postEphemeral(
                channel = msg.channelId,
                user = msg.userId,
                text = "Couldn't open your budget right now — try again shortly.",
            )
            return
        }

        slack.postEphemeral(
            channel = msg.channelId,
            user = msg.userId,
            text = "Open your budget: $webBaseUrl/enter#$rawNonce",
        )
    }
}
