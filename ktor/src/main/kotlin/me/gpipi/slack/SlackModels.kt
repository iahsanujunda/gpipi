package me.gpipi.slack

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Minimal Events API envelope — enough for the `url_verification` handshake now.
 * The `event` payload (message text, user, channel, event_id) is added in iteration 2
 * when capture/dedup and the echo actually need it.
 */
@Serializable
data class SlackEnvelope(
    val type: String? = null,
    val challenge: String? = null,
    @SerialName("event_id") val eventId: String? = null,
    val event: SlackEvent? = null,
)

@Serializable
data class SlackEvent(
    val type: String? = null,
    val user: String? = null,
    val channel: String? = null,
    val text: String? = null,
    val ts: String? = null,
)