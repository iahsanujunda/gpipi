package me.gpipi.slack

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import me.gpipi.category.CategoryRow
import me.gpipi.config.dbQuery
import me.gpipi.expense.ExpenseDraftRepository
import me.gpipi.extraction.Extraction
import me.gpipi.extraction.ExtractionException
import me.gpipi.extraction.ExtractionResult
import me.gpipi.extraction.ExtractionService
import me.gpipi.generated.db.base.public1.Category
import me.gpipi.generated.db.base.public1.ExpenseDraft
import me.gpipi.generated.db.base.public1.InboundMessage
import me.gpipi.inbound.InboundRepository
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

class LogExpenseCommandTest : PersistenceTest() {
    private val categoryId = UUID.randomUUID()
    private val inboundRepo = InboundRepository()
    private val draftRepo = ExpenseDraftRepository()
    private val extractionService = mockk<ExtractionService>()
    private val slack = mockk<SlackClient>(relaxUnitFun = true)
    private val command = LogExpenseCommand(
        db = db,
        inboundRepo = inboundRepo,
        extractionService = extractionService,
        draftRepo = draftRepo,
        slack = slack,
    )

    private val message = SlackMessage(
        eventId = "Ev001",
        userId = "U1",
        channelId = "C1",
        ts = "1751700000.000100",
        text = "<@BOT> 1500 for ramen",
        body = "1500 for ramen",
    )

    private fun <T> query(block: () -> T): T = runBlocking {
        dbQuery(db) { block() }
    }

    private fun givenCategory() = query {
        Category.insert {
            it[Category.id] = categoryId
            it[Category.name] = "Eating Out"
            it[Category.description] = "restaurants, cafes, ramen"
            it[Category.amount] = 15_000L
            it[Category.period] = "WEEKLY"
            it[Category.slackLoggable] = true
        }
    }

    private fun givenInboundMessage(): UUID = runBlocking {
        dbQuery(db) {
            inboundRepo.captureOrSkip(
                eventId = message.eventId,
                userId = message.userId,
                channelId = message.channelId,
                text = message.text,
                slackTs = message.ts,
            )
        }!!
    }

    @Test
    fun `matches is always false because this command is the dispatcher default`() {
        assertFalse(command.matches(message.body))
    }

    @Test
    fun `handle creates a pending draft and posts its card for a captured inbound message`() {
        givenCategory()
        val inboundMessageId = givenInboundMessage()
        coEvery { extractionService.extract(message.text) } returns ExtractionResult(
            extraction = Extraction(
                amount = 1_500,
                currency = "JPY",
                merchant = null,
                category = "Eating Out",
                confidence = 0.9,
                note = null,
            ),
            categoryId = categoryId,
            categories = listOf(CategoryRow(categoryId, "Eating Out", "restaurants, cafes, ramen")),
        )

        runBlocking {
            command.handle(message, inboundMessageId)
        }

        assertEquals(1L, query { InboundMessage.selectAll().count() })
        assertEquals("RECEIVED", query { InboundMessage.selectAll().single()[InboundMessage.status] })
        val draft = query { ExpenseDraft.selectAll().single() }
        assertEquals(inboundMessageId, draft[ExpenseDraft.inboundMessageId])
        assertEquals(message.userId, draft[ExpenseDraft.userId])
        assertEquals(message.channelId, draft[ExpenseDraft.channelId])
        assertEquals(1_500L, draft[ExpenseDraft.amount])
        coVerify(exactly = 1) { slack.postCard("C1", any(), any()) }
    }

    @Test
    fun `handle marks extraction failure and posts an apology`() {
        val inboundMessageId = givenInboundMessage()
        coEvery { extractionService.extract(message.text) } throws ExtractionException("bad json")

        runBlocking {
            command.handle(message, inboundMessageId)
        }

        val inbound = query { InboundMessage.selectAll().single() }
        assertEquals("FAILED_PARSE", inbound[InboundMessage.status])
        assertEquals("bad json", inbound[InboundMessage.failReason])
        assertEquals(0L, query { ExpenseDraft.selectAll().count() })
        coVerify(exactly = 1) {
            slack.postMessage("C1", "Couldn't read that one, mind rephrasing?")
        }
    }
}
