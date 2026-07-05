package me.gpipi.slack

import me.gpipi.extraction.OpenRouterClient
import me.gpipi.inbound.InboundRepository
import org.jetbrains.exposed.v1.jdbc.Database

class SlackEventHandler(
    private val db: Database,
    private val inboundRepo: InboundRepository,
    private val orClient: OpenRouterClient,
    private val slack: SlackClient
) {
    suspend fun handle(payload: SlackEnvelope) {
        val event = payload.event ?: return
    }
}