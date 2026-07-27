package me.gpipi.slack

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.gpipi.config.dbQuery
import me.gpipi.generated.db.base.public1.ShoppingAddDraft
import me.gpipi.generated.db.base.public1.ShoppingAddDraftItem
import me.gpipi.inbound.InboundRepository
import me.gpipi.shopping.ShoppingExtractedItem
import me.gpipi.shopping.ShoppingExtraction
import me.gpipi.shopping.ShoppingExtractionException
import me.gpipi.shopping.ShoppingExtractionService
import me.gpipi.shopping.ShoppingRepository
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.jdbc.selectAll

class ShoppingAddCommandTest : PersistenceTest() {
    private val extractionService = mockk<ShoppingExtractionService>()
    private val slack = mockk<SlackClient>(relaxUnitFun = true)
    private val command = ShoppingAddCommand(
        db = db,
        extractionService = extractionService,
        repository = ShoppingRepository(),
        slack = slack,
    )
    private val message = SlackMessage(
        eventId = "EvShoppingAdd",
        userId = "U1",
        channelId = "C1",
        ts = "1751700000.000100",
        text = "<@BOT> list add milk and eggs",
        body = "list add milk and eggs",
    )

    private fun <T> query(block: () -> T): T =
        runBlocking { dbQuery(db) { block() } }

    private fun givenInbound(): UUID = query {
        InboundRepository().captureOrSkip(
            message.eventId,
            message.userId,
            message.channelId,
            message.text,
            message.ts,
        )
    }!!

    @Test
    fun `matcher accepts only list add with a nonblank payload`() {
        assertTrue(command.matches("list add milk"))
        assertTrue(command.matches("LIST ADD susu"))
        assertFalse(command.matches("list add"))
        assertFalse(command.matches("list add   "))
        assertFalse(command.matches("list"))
        assertFalse(command.matches("add 500 for lunch"))
        assertFalse(command.matches("need to log 500"))
        assertFalse(command.matches("listening to music"))
        assertFalse(command.matches("list address"))
    }

    @Test
    fun `handle extracts the payload then persists a draft before posting Add and Cancel`() = runBlocking {
        val inboundId = givenInbound()
        coEvery { extractionService.extract("milk and eggs") } returns ShoppingExtraction(
            listOf(
                ShoppingExtractedItem("milk", "1L", "low fat"),
                ShoppingExtractedItem("eggs"),
            ),
        )

        val outcome = command.handle(message, inboundId)

        assertEquals(SlackCommandOutcome.Completed, outcome)
        val draft = query { ShoppingAddDraft.selectAll().single() }
        assertEquals(inboundId, draft[ShoppingAddDraft.inboundMessageId])
        assertEquals("PENDING", draft[ShoppingAddDraft.status])
        assertEquals(
            listOf("milk", "eggs"),
            query {
                ShoppingAddDraftItem.selectAll()
                    .orderBy(ShoppingAddDraftItem.position)
                    .map { it[ShoppingAddDraftItem.item] }
            },
        )
        coVerify(exactly = 1) { extractionService.extract("milk and eggs") }
        coVerify(exactly = 1) {
            slack.postCard(
                channel = "C1",
                text = "Confirm shopping-list items",
                blocks = match {
                    val rendered = it.toString()
                    CONFIRM_SHOPPING_ADD_ACTION_ID in rendered &&
                        CANCEL_SHOPPING_ADD_ACTION_ID in rendered
                },
            )
        }
    }

    @Test
    fun `extraction failure returns a terminal failure without creating a draft`() = runBlocking {
        val inboundId = givenInbound()
        coEvery {
            extractionService.extract("milk and eggs")
        } throws ShoppingExtractionException("bad model output")

        val outcome = command.handle(message, inboundId)

        assertEquals(
            SlackCommandOutcome.Failed("bad model output"),
            outcome,
        )
        assertEquals(0L, query { ShoppingAddDraft.selectAll().count() })
        coVerify(exactly = 1) {
            slack.postMessage(
                "C1",
                "Couldn't read those shopping items — try rephrasing them.",
            )
        }
        coVerify(exactly = 0) { slack.postCard(any(), any(), any()) }
    }
}
