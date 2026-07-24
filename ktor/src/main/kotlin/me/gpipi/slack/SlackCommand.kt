package me.gpipi.slack

import java.util.UUID

interface SlackCommand {
    fun matches(body: String): Boolean

    suspend fun handle(msg: SlackMessage, inboundMessageId: UUID)
}
