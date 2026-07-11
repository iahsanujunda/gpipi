package me.gpipi.slack

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import me.gpipi.config.dbQuery
import me.gpipi.expense.ExpenseRepository
import me.gpipi.extraction.Extraction
import me.gpipi.extraction.ExtractionException
import me.gpipi.extraction.OpenRouterClient
import me.gpipi.generated.db.base.public1.Expense
import me.gpipi.generated.db.base.public1.InboundMessage
import me.gpipi.inbound.InboundRepository
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Handler-level tests: real repos + Testcontainers DB (so status transitions and the FK link are
 * genuinely exercised), mocked HTTP edges (OpenRouter, Slack) so nothing hits the network.
 * The route→launch plumbing is covered separately in SlackRoutesTest; here we drive handle() directly.
 */
class SlackEventHandlerTest : PersistenceTest() {

    private val orClient = mockk<OpenRouterClient>()
    private val slack = mockk<SlackClient>(relaxUnitFun = true)   // postMessage returns Unit → relaxed
    private val handler = SlackEventHandler(db, InboundRepository(), ExpenseRepository(), orClient, slack)

    private fun envelope(eventId: String = "Ev001", text: String? = "1500 for ramen") = SlackEnvelope(
        type = "event_callback",
        eventId = eventId,
        event = SlackEvent(type = "app_mention", user = "U1", channel = "C1", text = text, ts = "1751700000.000100"),
    )

    private val ramen = Extraction(
        amount = 1500, currency = "JPY", merchant = null,
        category = "Eating Out", confidence = 0.9, note = null,
    )

    private fun run(payload: SlackEnvelope) = runBlocking { handler.handle(payload) }
    private fun <T> query(block: () -> T): T = runBlocking { dbQuery(db) { block() } }

    @Test
    fun `happy path records the expense and posts confirmation`() {
        coEvery { orClient.extract(any()) } returns ramen

        run(envelope())

        val inbound = query { InboundMessage.selectAll().single() }
        val expense = query { Expense.selectAll().single() }
        assertEquals("RECORDED", inbound[InboundMessage.status])
        assertEquals(inbound[InboundMessage.id], expense[Expense.inboundMessageId])  // FK link
        assertEquals(1500L, expense[Expense.amount])
        assertEquals("Eating Out", expense[Expense.category])
        coVerify { slack.postMessage("C1", match { "Recorded" in it && "1500" in it }) }
    }

    @Test
    fun `failed extraction marks FAILED_PARSE, keeps text, writes no expense, posts apology`() {
        coEvery { orClient.extract(any()) } throws ExtractionException("bad json")

        run(envelope())

        val inbound = query { InboundMessage.selectAll().single() }
        assertEquals("FAILED_PARSE", inbound[InboundMessage.status])
        assertEquals("bad json", inbound[InboundMessage.failReason])
        assertEquals("1500 for ramen", inbound[InboundMessage.text])   // raw text kept for debugging
        assertEquals(0L, query { Expense.selectAll().count() })
        coVerify { slack.postMessage("C1", any()) }
    }

    @Test
    fun `duplicate delivery is processed once`() {
        coEvery { orClient.extract(any()) } returns ramen

        run(envelope("EvDup"))
        run(envelope("EvDup"))   // Slack retry of an already-captured event

        assertEquals(1L, query { InboundMessage.selectAll().count() })
        assertEquals(1L, query { Expense.selectAll().count() })
        coVerify(exactly = 1) { orClient.extract(any()) }
        coVerify(exactly = 1) { slack.postMessage(any(), any()) }
    }

    @Test
    fun `non-app_mention event is ignored before capture`() {
        run(envelope().copy(event = envelope().event!!.copy(type = "message")))

        assertEquals(0L, query { InboundMessage.selectAll().count() })
        coVerify(exactly = 0) { orClient.extract(any()) }
    }

    @Test
    fun `blank text mention is ignored before capture`() {
        run(envelope(text = "   "))

        assertEquals(0L, query { InboundMessage.selectAll().count() })
        coVerify(exactly = 0) { orClient.extract(any()) }
    }
}
