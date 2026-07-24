package me.gpipi.slack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlackMessageTest {
    private val event = SlackEvent(
        type = "app_mention",
        user = "U1",
        channel = "C1",
        text = "<@U1> open budget",
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
    fun `valid app mention maps to a message with the mention stripped from its body`() {
        val message = SlackMessage.from(envelope())

        assertEquals(
            SlackMessage(
                eventId = "Ev001",
                userId = "U1",
                channelId = "C1",
                ts = "1751700000.000100",
                text = "<@U1> open budget",
                body = "open budget",
            ),
            message,
        )
    }

    @Test
    fun `non-app mention returns null`() {
        assertNull(SlackMessage.from(envelope(event = event.copy(type = "message"))))
    }

    @Test
    fun `blank text returns null`() {
        assertNull(SlackMessage.from(envelope(event = event.copy(text = "  "))))
    }

    @Test
    fun `missing user returns null`() {
        assertNull(SlackMessage.from(envelope(event = event.copy(user = null))))
    }

    @Test
    fun `missing channel returns null`() {
        assertNull(SlackMessage.from(envelope(event = event.copy(channel = null))))
    }

    @Test
    fun `missing timestamp returns null`() {
        assertNull(SlackMessage.from(envelope(event = event.copy(ts = null))))
    }

    @Test
    fun `missing event id returns null`() {
        assertNull(SlackMessage.from(envelope(eventId = null)))
    }

    @Test
    fun `missing event returns null`() {
        assertNull(SlackMessage.from(envelope(event = null)))
    }
}
