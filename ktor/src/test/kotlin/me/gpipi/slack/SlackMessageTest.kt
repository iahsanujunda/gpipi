package me.gpipi.slack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlackMessageTest {
    private val event = SlackEvent(
        type = "app_mention",
        user = "U1",
        channel = "C1",
        text = "<@BOT>   open budget  ",
        ts = "1751700000.000100",
    )

    private fun envelope(
        eventId: String? = "Ev001",
        event: SlackEvent? = this.event,
    ) = SlackEnvelope(
        type = "event_callback",
        eventId = eventId,
        event = event,
    )

    @Test
    fun `from maps an app mention and extracts its trimmed body`() {
        val message = SlackMessage.from(envelope())

        assertEquals(
            SlackMessage(
                eventId = "Ev001",
                userId = "U1",
                channelId = "C1",
                ts = "1751700000.000100",
                text = "<@BOT>   open budget  ",
                body = "open budget",
            ),
            message,
        )
    }

    @Test
    fun `from rejects envelopes without a complete app mention`() {
        val invalidPayloads = mapOf(
            "event" to envelope(event = null),
            "event id" to envelope(eventId = null),
            "app mention type" to envelope(event = event.copy(type = "message")),
            "user" to envelope(event = event.copy(user = null)),
            "channel" to envelope(event = event.copy(channel = null)),
            "timestamp" to envelope(event = event.copy(ts = null)),
            "non-blank text" to envelope(event = event.copy(text = "  ")),
        )

        invalidPayloads.forEach { (requiredField, payload) ->
            assertNull(
                SlackMessage.from(payload),
                "expected a missing $requiredField to be rejected",
            )
        }
    }
}
