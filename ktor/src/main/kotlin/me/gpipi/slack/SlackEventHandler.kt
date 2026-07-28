package me.gpipi.slack

import kotlinx.coroutines.CancellationException
import me.gpipi.config.dbQuery
import me.gpipi.inbound.InboundRepository
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory

private val slackEventLog = LoggerFactory.getLogger(SlackEventHandler::class.java)

class SlackEventHandler(
    private val db: Database,
    private val inboundRepo: InboundRepository,
    private val commands: List<SlackCommand>,
    private val default: SlackCommand,
) {
    suspend fun handle(payload: SlackEnvelope) {
        val msg = SlackMessage.from(payload) ?: return

        val msgId = dbQuery(db) {
            inboundRepo.captureOrSkip(
                eventId = msg.eventId,
                userId = msg.userId,
                channelId = msg.channelId,
                text = msg.text,
                slackTs = msg.ts,
            )
        } ?: return

        val command = commands.firstOrNull { it.matches(msg.body) }
        if (command == null) {
            default.handle(msg, msgId)
            return
        }

        val outcome = try {
            command.handle(msg, msgId)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            slackEventLog.error("Slack command ${command::class.simpleName} failed unexpectedly", ex)
            SlackCommandOutcome.Failed(ex.commandFailureReason())
        }

        dbQuery(db) {
            when (outcome) {
                SlackCommandOutcome.Completed -> inboundRepo.markCommand(msgId)
                is SlackCommandOutcome.Failed -> inboundRepo.markCommandFailed(msgId, outcome.reason)
                SlackCommandOutcome.Pending -> inboundRepo.markCommandFailed(
                    msgId,
                    "Deterministic command returned a pending outcome.",
                )
            }
        }
    }
}
