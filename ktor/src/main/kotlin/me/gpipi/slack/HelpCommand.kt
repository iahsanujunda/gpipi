package me.gpipi.slack

import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

private val helpCommandLog = LoggerFactory.getLogger(HelpCommand::class.java)

/**
 * Answers "what can this bot do?" with a private command cheat sheet. It touches nothing but
 * Slack — no database, no LLM — so it stays useful even when every other path is degraded.
 */
class HelpCommand(
    private val slack: SlackClient,
) : SlackCommand {
    override fun matches(body: String): Boolean =
        body.trim().lowercase() in TRIGGERS

    override suspend fun handle(
        msg: SlackMessage,
        inboundMessageId: UUID,
    ): SlackCommandOutcome {
        return try {
            slack.postEphemeralCard(
                channel = msg.channelId,
                user = msg.userId,
                text = "What I can do",
                blocks = helpCard(),
            )
            SlackCommandOutcome.Completed
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            helpCommandLog.error("Help command failed", ex)
            SlackCommandOutcome.Failed(ex.commandFailureReason())
        }
    }

    private companion object {
        val TRIGGERS = setOf("help", "?", "hi", "hello", "commands")
    }
}
