package me.gpipi.slack

import me.gpipi.config.dbQuery
import me.gpipi.inbound.InboundRepository
import org.jetbrains.exposed.v1.jdbc.Database

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

        val command = commands.firstOrNull { it.matches(msg.body) } ?: default
        command.handle(msg, msgId)
    }
}
