package me.gpipi.slack

import io.mockk.coEvery
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import me.gpipi.config.dbQuery
import me.gpipi.generated.db.base.public1.InboundMessage
import me.gpipi.generated.db.base.public1.ShoppingAddDraft
import me.gpipi.inbound.InboundRepository
import me.gpipi.shopping.ShoppingExtractedItem
import me.gpipi.shopping.ShoppingExtraction
import me.gpipi.shopping.ShoppingExtractionService
import me.gpipi.shopping.ShoppingRepository
import me.gpipi.shopping.ShoppingService
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.jdbc.selectAll

private class PendingDefaultCommand : SlackCommand {
    var calls = 0

    override fun matches(body: String) = false

    override suspend fun handle(
        msg: SlackMessage,
        inboundMessageId: UUID,
    ): SlackCommandOutcome {
        calls++
        return SlackCommandOutcome.Pending
    }
}

class ShoppingCommandRoutingTest : PersistenceTest() {
    private val extractionService = mockk<ShoppingExtractionService>()
    private val slack = mockk<SlackClient>(relaxUnitFun = true)
    private val repository = ShoppingRepository()
    private val default = PendingDefaultCommand()
    private val handler = SlackEventHandler(
        db = db,
        inboundRepo = InboundRepository(),
        commands = listOf(
            ShoppingAddCommand(db, extractionService, repository, slack),
            ShoppingShowCommand(ShoppingService(db, repository), slack),
        ),
        default = default,
    )

    private fun envelope(eventId: String, body: String) =
        SlackEnvelope(
            type = "event_callback",
            eventId = eventId,
            event = SlackEvent(
                type = "app_mention",
                user = "U1",
                channel = "C1",
                text = "<@BOT> $body",
                ts = "1751700000.000100",
            ),
        )

    private fun status(): String = runBlocking {
        dbQuery(db) {
            InboundMessage.selectAll().single()[InboundMessage.status]
        }
    }

    @Test
    fun `list reaches COMMAND through the deterministic dispatcher`() = runBlocking {
        handler.handle(envelope("EvListRoute", "list"))

        assertEquals("COMMAND", status())
        assertEquals(0, default.calls)
    }

    @Test
    fun `list add persists its draft then reaches COMMAND`() = runBlocking {
        coEvery { extractionService.extract("milk") } returns ShoppingExtraction(
            listOf(ShoppingExtractedItem("milk")),
        )

        handler.handle(envelope("EvListAddRoute", "list add milk"))

        assertEquals("COMMAND", status())
        assertEquals(
            1L,
            dbQuery(db) { ShoppingAddDraft.selectAll().count() },
        )
        assertEquals(0, default.calls)
    }

    @Test
    fun `expense-like messages still fall through to the unchanged default`() = runBlocking {
        handler.handle(envelope("EvExpenseRoute", "add 500 for lunch"))

        assertEquals("RECEIVED", status())
        assertEquals(1, default.calls)
        assertEquals(
            0L,
            dbQuery(db) { ShoppingAddDraft.selectAll().count() },
        )
    }
}
