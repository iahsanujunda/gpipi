package me.gpipi.slack

import io.mockk.coVerify
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.gpipi.categorization.CategorizationEventRepository
import me.gpipi.config.dbQuery
import me.gpipi.expense.ExpenseDraftRepository
import me.gpipi.expense.ExpenseRepository
import me.gpipi.generated.db.base.public1.ShoppingAddDraft
import me.gpipi.generated.db.base.public1.ShoppingItem
import me.gpipi.generated.db.base.public1.ShoppingMutation
import me.gpipi.inbound.InboundRepository
import me.gpipi.shopping.ShoppingDraftItemInput
import me.gpipi.shopping.ShoppingRepository
import me.gpipi.shopping.ShoppingService
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.jdbc.selectAll

class ShoppingInteractionHandlerTest : PersistenceTest() {
    private val repository = ShoppingRepository()
    private val shoppingService = ShoppingService(db, repository)
    private val slack = mockk<SlackClient>(relaxUnitFun = true)
    private val handler = SlackInteractionHandler(
        db = db,
        draftRepo = ExpenseDraftRepository(),
        expenseRepo = ExpenseRepository(),
        inboundRepo = InboundRepository(),
        eventRepo = CategorizationEventRepository(),
        shoppingService = shoppingService,
        slack = slack,
    )
    private val responseUrl = "https://hooks.slack.test/shopping"

    private fun <T> query(block: () -> T): T =
        runBlocking { dbQuery(db) { block() } }

    private fun givenDraft(
        eventId: String,
        vararg items: ShoppingDraftItemInput,
    ): UUID {
        val inboundId = query {
            InboundRepository().captureOrSkip(
                eventId,
                "U-sender",
                "C1",
                "list add ${items.joinToString(" and ") { it.item }}",
                "1751700000.000100",
            )
        }!!
        return query {
            repository.insertAddDraft(
                inboundId,
                "U-sender",
                "C1",
                items.toList(),
            )
        }
    }

    @Test
    fun `confirm add creates items and replaces the confirmation with Undo`() = runBlocking {
        val draftId = givenDraft(
            "EvInteractionAdd",
            ShoppingDraftItemInput("milk"),
            ShoppingDraftItemInput("eggs", "2 packs"),
        )

        handler.handleShoppingAddConfirm(draftId, "U-clicker", responseUrl)

        assertEquals(2L, query { ShoppingItem.selectAll().count() })
        assertEquals(
            "CONFIRMED",
            query { ShoppingAddDraft.selectAll().single()[ShoppingAddDraft.status] },
        )
        coVerify(exactly = 1) {
            slack.replaceCard(
                responseUrl,
                match { "Added" in it },
                match { UNDO_SHOPPING_ACTION_ID in it.toString() },
            )
        }
    }

    @Test
    fun `double Cancel consumes the draft and replaces the card once`() = runBlocking {
        val draftId = givenDraft(
            "EvInteractionCancel",
            ShoppingDraftItemInput("bread"),
        )

        handler.handleShoppingAddCancel(draftId, responseUrl)
        handler.handleShoppingAddCancel(draftId, responseUrl)

        assertEquals(
            "CANCELLED",
            query { ShoppingAddDraft.selectAll().single()[ShoppingAddDraft.status] },
        )
        assertEquals(0L, query { ShoppingItem.selectAll().count() })
        coVerify(exactly = 1) {
            slack.replaceCard(
                responseUrl,
                "Nothing added",
                any(),
            )
        }
    }

    @Test
    fun `Mark Bought and Undo re-render canonical state and retain history`() = runBlocking {
        val draftId = givenDraft(
            "EvInteractionBought",
            ShoppingDraftItemInput("milk"),
            ShoppingDraftItemInput("eggs"),
        )
        val add = assertNotNull(shoppingService.confirmAdd(draftId, "U-add"))
        val milkId = shoppingService.listPending().first { it.item == "milk" }.id

        handler.handleShoppingMarkBought(
            itemIds = listOf(milkId),
            actorId = "U-buyer",
            responseUrl = responseUrl,
        )

        val boughtRow = query {
            ShoppingItem.selectAll()
                .first { it[ShoppingItem.id] == milkId }
        }
        assertEquals("BOUGHT", boughtRow[ShoppingItem.status])
        val markMutationId = query {
            ShoppingMutation.selectAll()
                .single { it[ShoppingMutation.kind] == "MARK_BOUGHT" }
                .get(ShoppingMutation.id)
        }

        handler.handleShoppingUndo(markMutationId, "U-undo", responseUrl)

        assertEquals(
            setOf("milk", "eggs"),
            shoppingService.listPending().map { it.item }.toSet(),
        )
        assertEquals(
            setOf("ADD", "MARK_BOUGHT", "UNDO_BOUGHT"),
            query {
                ShoppingMutation.selectAll()
                    .map { it[ShoppingMutation.kind] }
                    .toSet()
            },
        )
        assertEquals(2L, query { ShoppingItem.selectAll().count() })
        assertTrue(add.mutationId != null)
        coVerify(exactly = 2) {
            slack.replaceCard(
                responseUrl,
                any(),
                any(),
            )
        }
    }

    @Test
    fun `partial Undo reports both restored and skipped items`() = runBlocking {
        val originalDraft = givenDraft(
            "EvPartialUndoOriginal",
            ShoppingDraftItemInput("milk"),
            ShoppingDraftItemInput("eggs"),
        )
        assertNotNull(shoppingService.confirmAdd(originalDraft, "U-add"))
        val bought = shoppingService.markBought(
            shoppingService.listPending().map { it.id },
            "U-buyer",
        )
        val replacementDraft = givenDraft(
            "EvPartialUndoReplacement",
            ShoppingDraftItemInput("milk"),
        )
        assertNotNull(shoppingService.confirmAdd(replacementDraft, "U-add-again"))

        handler.handleShoppingUndo(
            mutationId = assertNotNull(bought.mutationId),
            actorId = "U-undo",
            responseUrl = responseUrl,
        )

        coVerify {
            slack.replaceCard(
                responseUrl,
                match {
                    "Restored eggs" in it &&
                        "Skipped milk" in it
                },
                any(),
            )
        }
    }
}
