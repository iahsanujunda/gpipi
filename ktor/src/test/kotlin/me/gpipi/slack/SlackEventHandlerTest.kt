package me.gpipi.slack

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import me.gpipi.config.dbQuery
import me.gpipi.generated.db.base.public1.InboundMessage
import me.gpipi.inbound.InboundRepository
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.jdbc.selectAll

private class FakeCommand(
    val name: String,
    private val match: (String) -> Boolean,
) : SlackCommand {
    var calls = 0

    override fun matches(body: String): Boolean = match(body)

    override suspend fun handle(msg: SlackMessage, inboundMessageId: UUID) {
        calls++
    }
}

class SlackEventHandlerTest : PersistenceTest() {
    private val openCommand = FakeCommand("open") { it == "open" }
    private val defaultCommand = FakeCommand("default") { false }
    private val handler = SlackEventHandler(
        db = db,
        inboundRepo = InboundRepository(),
        commands = listOf(openCommand),
        default = defaultCommand,
    )

    private fun envelope(
        eventId: String = "Ev001",
        text: String? = "<@BOT> 1500 ramen",
        type: String = "app_mention",
    ) = SlackEnvelope(
        type = "event_callback",
        eventId = eventId,
        event = SlackEvent(
            type = type,
            user = "U1",
            channel = "C1",
            text = text,
            ts = "1751700000.000100",
        ),
    )

    private fun handle(payload: SlackEnvelope) = runBlocking {
        handler.handle(payload)
    }

    private fun inboundCount(): Long = runBlocking {
        dbQuery(db) { InboundMessage.selectAll().count() }
    }

    @Test
    fun `open body routes to the matching command`() {
        handle(envelope(text = "<@BOT> open"))

        assertEquals(1, openCommand.calls)
        assertEquals(0, defaultCommand.calls)
    }

    @Test
    fun `expense body routes to the default command`() {
        handle(envelope(text = "<@BOT> 1500 ramen"))

        assertEquals(0, openCommand.calls)
        assertEquals(1, defaultCommand.calls)
    }

    @Test
    fun `duplicate delivery is captured and dispatched only once`() {
        val payload = envelope(
            eventId = "EvDuplicate",
            text = "<@BOT> open",
        )

        handle(payload)
        handle(payload)

        assertEquals(1, openCommand.calls)
        assertEquals(0, defaultCommand.calls)
        assertEquals(1L, inboundCount())
    }

    @Test
    fun `invalid Slack events are ignored before capture and dispatch`() {
        handle(envelope(eventId = "EvBlank", text = "   "))
        handle(envelope(eventId = "EvWrongType", type = "message"))

        assertEquals(0L, inboundCount())
        assertEquals(0, openCommand.calls)
        assertEquals(0, defaultCommand.calls)
    }
}
