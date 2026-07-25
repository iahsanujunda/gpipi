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
    var outcome: SlackCommandOutcome = SlackCommandOutcome.Completed,
    private val match: (String) -> Boolean,
) : SlackCommand {
    var calls = 0
    var failure: Exception? = null

    override fun matches(body: String): Boolean = match(body)

    override suspend fun handle(
        msg: SlackMessage,
        inboundMessageId: UUID,
    ): SlackCommandOutcome {
        calls++
        failure?.let { throw it }
        return outcome
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

    private fun inboundStatus(): Pair<String, String?> = runBlocking {
        dbQuery(db) {
            val inbound = InboundMessage.selectAll().single()
            inbound[InboundMessage.status] to inbound[InboundMessage.failReason]
        }
    }

    @Test
    fun `open body routes to the matching command and reaches COMMAND`() {
        handle(envelope(text = "<@BOT> open"))

        assertEquals(1, openCommand.calls)
        assertEquals(0, defaultCommand.calls)
        assertEquals("COMMAND" to null, inboundStatus())
    }

    @Test
    fun `expense body routes to the default command and remains pending`() {
        defaultCommand.outcome = SlackCommandOutcome.Pending

        handle(envelope(text = "<@BOT> 1500 ramen"))

        assertEquals(0, openCommand.calls)
        assertEquals(1, defaultCommand.calls)
        assertEquals("RECEIVED" to null, inboundStatus())
    }

    @Test
    fun `failed deterministic command reaches FAILED_COMMAND`() {
        openCommand.outcome = SlackCommandOutcome.Failed("nonce mint failed")

        handle(envelope(text = "<@BOT> open"))

        assertEquals("FAILED_COMMAND" to "nonce mint failed", inboundStatus())
    }

    @Test
    fun `unexpected deterministic command exception reaches FAILED_COMMAND`() {
        openCommand.failure = IllegalStateException("unexpected failure")

        handle(envelope(text = "<@BOT> open"))

        assertEquals("FAILED_COMMAND" to "unexpected failure", inboundStatus())
    }

    @Test
    fun `pending outcome from deterministic command is terminalized as a failure`() {
        openCommand.outcome = SlackCommandOutcome.Pending

        handle(envelope(text = "<@BOT> open"))

        assertEquals(
            "FAILED_COMMAND" to "Deterministic command returned a pending outcome.",
            inboundStatus(),
        )
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
        assertEquals("COMMAND" to null, inboundStatus())
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
