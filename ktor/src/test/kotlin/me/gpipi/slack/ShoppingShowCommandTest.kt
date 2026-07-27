package me.gpipi.slack

import io.mockk.coVerify
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.gpipi.config.dbQuery
import me.gpipi.inbound.InboundRepository
import me.gpipi.shopping.ShoppingDraftItemInput
import me.gpipi.shopping.ShoppingRepository
import me.gpipi.shopping.ShoppingService
import me.gpipi.support.PersistenceTest

class ShoppingShowCommandTest : PersistenceTest() {
    private val repository = ShoppingRepository()
    private val shoppingService = ShoppingService(db, repository)
    private val slack = mockk<SlackClient>(relaxUnitFun = true)
    private val command = ShoppingShowCommand(shoppingService, slack)
    private val message = SlackMessage(
        eventId = "EvList",
        userId = "U1",
        channelId = "C1",
        ts = "1751700000.000100",
        text = "<@BOT> list",
        body = "list",
    )

    private fun <T> query(block: () -> T): T =
        runBlocking { dbQuery(db) { block() } }

    private fun givenPending(vararg items: String) = runBlocking {
        val inboundId = query {
            InboundRepository().captureOrSkip(
                "EvSeed",
                "U1",
                "C1",
                "list add ${items.joinToString(" and ")}",
                "1751700000.000100",
            )
        }!!
        val draftId = query {
            repository.insertAddDraft(
                inboundId,
                "U1",
                "C1",
                items.map(::ShoppingDraftItemInput),
            )
        }
        checkNotNull(shoppingService.confirmAdd(draftId, "U1"))
    }

    @Test
    fun `matcher accepts exactly list`() {
        assertTrue(command.matches("list"))
        assertTrue(command.matches("LIST"))
        assertFalse(command.matches("list add milk"))
        assertFalse(command.matches("list please"))
        assertFalse(command.matches("listening to music"))
    }

    @Test
    fun `empty list posts a friendly canonical card and completes`() = runBlocking {
        val outcome = command.handle(message, UUID.randomUUID())

        assertEquals(SlackCommandOutcome.Completed, outcome)
        coVerify(exactly = 1) {
            slack.postCard(
                channel = "C1",
                text = "Shopping list is empty",
                blocks = match { "Nothing on the list yet." in it.toString() },
            )
        }
    }

    @Test
    fun `show renders pending items only`() = runBlocking {
        givenPending("milk", "eggs")
        val milkId = shoppingService.listPending().first { it.item == "milk" }.id
        shoppingService.markBought(listOf(milkId), "U-buyer")

        val outcome = command.handle(message, UUID.randomUUID())

        assertEquals(SlackCommandOutcome.Completed, outcome)
        coVerify(exactly = 1) {
            slack.postCard(
                channel = "C1",
                text = "Shopping list",
                blocks = match {
                    val rendered = it.toString()
                    "eggs" in rendered && "milk" !in rendered
                },
            )
        }
    }
}
