package me.gpipi.slack

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.runBlocking
import me.gpipi.config.dbQuery
import me.gpipi.expense.ExpenseDraftRepository
import me.gpipi.generated.db.base.public1.ExpenseDraft
import me.gpipi.category.CategoryRow
import me.gpipi.extraction.Extraction
import me.gpipi.extraction.ExtractionException
import me.gpipi.extraction.ExtractionResult
import me.gpipi.extraction.ExtractionService
import me.gpipi.generated.db.base.public1.Category
import me.gpipi.generated.db.base.public1.Expense
import me.gpipi.generated.db.base.public1.InboundMessage
import me.gpipi.inbound.InboundRepository
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Handler-level tests: real repos + Testcontainers DB (so status transitions and the FK link are
 * genuinely exercised), mocked HTTP edges (OpenRouter, Slack) so nothing hits the network.
 * The route→launch plumbing is covered separately in SlackRoutesTest; here we drive handle() directly.
 */
class SlackEventHandlerTest : PersistenceTest() {

    private val testCategoryId: UUID = UUID.randomUUID()
    private val extractionService = mockk<ExtractionService>()
    private val slack = mockk<SlackClient>(relaxUnitFun = true)   // postMessage returns Unit → relaxed
    private val handler = SlackEventHandler(db, InboundRepository(), extractionService, ExpenseDraftRepository(), slack)

    // Superclass @BeforeEach (clean) runs first, then this seeds the FK target.
    @BeforeEach fun seedCategory() {
        runBlocking {
            dbQuery(db) {
                Category.insert {
                    it[Category.id] = testCategoryId
                    it[Category.name] = "Eating Out"
                    it[Category.description] = "restaurants, cafes, ramen"
                    it[Category.amount] = 15000L
                    it[Category.period] = "WEEKLY"
                    it[Category.slackLoggable] = true
                }
            }
        }
    }

    private fun envelope(eventId: String = "Ev001", text: String? = "1500 for ramen") = SlackEnvelope(
        type = "event_callback",
        eventId = eventId,
        event = SlackEvent(type = "app_mention", user = "U1", channel = "C1", text = text, ts = "1751700000.000100"),
    )

    private val ramen = Extraction(
        amount = 1500, currency = "JPY", merchant = null,
        category = "Eating Out", confidence = 0.9, note = null,
    )
    private val ramenResult get() = ExtractionResult(
        ramen, testCategoryId, listOf(CategoryRow(testCategoryId, "Eating Out", "restaurants, cafes, ramen")),
    )

    private fun run(payload: SlackEnvelope) = runBlocking { handler.handle(payload) }
    private fun <T> query(block: () -> T): T = runBlocking { dbQuery(db) { block() } }

    @Test
    fun `happy path writes a pending draft and posts the card, no expense yet`() {
        coEvery { extractionService.extract(any()) } returns ramenResult

        run(envelope())

        // Mention path no longer records — it waits for Confirm. Inbound stays RECEIVED, no expense.
        val inbound = query { InboundMessage.selectAll().single() }
        assertEquals("RECEIVED", inbound[InboundMessage.status])
        assertEquals(0L, query { Expense.selectAll().count() })

        val draft = query { ExpenseDraft.selectAll().single() }
        assertEquals(inbound[InboundMessage.id], draft[ExpenseDraft.inboundMessageId])  // FK link
        assertEquals(1500L, draft[ExpenseDraft.amount])
        assertEquals(testCategoryId, draft[ExpenseDraft.predictedCategoryId])
        assertEquals("PENDING", draft[ExpenseDraft.status])
        coVerify { slack.postCard("C1", any(), any()) }
    }

    @Test
    fun `failed extraction marks FAILED_PARSE, keeps text, writes no expense, posts apology`() {
        coEvery { extractionService.extract(any()) } throws ExtractionException("bad json")

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
        coEvery { extractionService.extract(any()) } returns ramenResult

        run(envelope("EvDup"))
        run(envelope("EvDup"))   // Slack retry of an already-captured event

        assertEquals(1L, query { InboundMessage.selectAll().count() })
        assertEquals(1L, query { ExpenseDraft.selectAll().count() })
        coVerify(exactly = 1) { extractionService.extract(any()) }
        coVerify(exactly = 1) { slack.postCard(any(), any(), any()) }
    }

    @Test
    fun `non-app_mention event is ignored before capture`() {
        run(envelope().copy(event = envelope().event!!.copy(type = "message")))

        assertEquals(0L, query { InboundMessage.selectAll().count() })
        coVerify(exactly = 0) { extractionService.extract(any()) }
    }

    @Test
    fun `blank text mention is ignored before capture`() {
        run(envelope(text = "   "))

        assertEquals(0L, query { InboundMessage.selectAll().count() })
        coVerify(exactly = 0) { extractionService.extract(any()) }
    }
}
