package me.gpipi.slack

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
)
