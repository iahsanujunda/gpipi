package me.gpipi.slack

import java.util.UUID

sealed interface SlackCommandOutcome {
    data object Pending : SlackCommandOutcome
    data object Completed : SlackCommandOutcome
    data class Failed(val reason: String) : SlackCommandOutcome
}

internal fun Throwable.commandFailureReason(): String =
    message?.takeIf(String::isNotBlank)
        ?: this::class.simpleName
        ?: "Unknown command failure"

interface SlackCommand {
    fun matches(body: String): Boolean

    suspend fun handle(msg: SlackMessage, inboundMessageId: UUID): SlackCommandOutcome
}
