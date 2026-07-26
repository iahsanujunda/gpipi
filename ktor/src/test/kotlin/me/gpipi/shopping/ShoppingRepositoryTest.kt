package me.gpipi.shopping

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import me.gpipi.config.dbQuery
import me.gpipi.generated.db.base.public1.ShoppingAddDraft
import me.gpipi.generated.db.base.public1.ShoppingAddDraftItem
import me.gpipi.inbound.InboundRepository
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.selectAll

class ShoppingRepositoryTest : PersistenceTest() {
    private val shoppingRepository = ShoppingRepository()
    private val inboundRepository = InboundRepository()

    private fun <T> query(block: () -> T): T = runBlocking { dbQuery(db) { block() } }

    private fun givenInbound(eventId: String = "Ev001"): UUID = query {
        inboundRepository.captureOrSkip(eventId, "U1", "C1", "list add milk and eggs", "1751700000.000100")!!
    }

    private val twoItems = listOf(
        ShoppingDraftItemInput(item = "milk", quantity = "1L", note = "low fat"),
        ShoppingDraftItemInput(item = "eggs"),
    )

    @Test
    fun `insertAddDraft then findAddDraft round-trips the draft and its ordered items`() {
        val msgId = givenInbound()

        val draftId = query {
            shoppingRepository.insertAddDraft(
                inboundMessageId = msgId, userId = "U1", channelId = "C1", items = twoItems,
            )
        }
        val draft = query { shoppingRepository.findAddDraft(draftId) }

        assertNotNull(draft)
        assertEquals(draftId, draft.id)
        assertEquals(msgId, draft.inboundMessageId)
        assertEquals("U1", draft.userId)
        assertEquals("C1", draft.channelId)
        assertEquals("PENDING", draft.status)
        assertNotNull(draft.createdAt)
        assertNull(draft.completedAt)

        assertEquals(listOf(0, 1), draft.items.map { it.position })
        assertEquals(listOf("milk", "eggs"), draft.items.map { it.item })

        val milk = draft.items[0]
        assertEquals("1L", milk.quantity)
        assertEquals("low fat", milk.note)

        val eggs = draft.items[1]
        assertNull(eggs.quantity)
        assertNull(eggs.note)
    }

    @Test
    fun `findAddDraft on an unknown id returns null`() {
        assertNull(query { shoppingRepository.findAddDraft(UUID.randomUUID()) })
    }

    @Test
    fun `insertAddDraft rejects an empty item list before writing a parent`() {
        val msgId = givenInbound()

        assertFailsWith<IllegalArgumentException> {
            query {
                shoppingRepository.insertAddDraft(
                    inboundMessageId = msgId, userId = "U1", channelId = "C1", items = emptyList(),
                )
            }
        }

        assertEquals(0, query { ShoppingAddDraft.selectAll().count() })
    }

    @Test
    fun `a blank item is rejected by the database and the parent rolls back`() {
        val msgId = givenInbound()

        val exception = assertFailsWith<ExposedSQLException> {
            query {
                shoppingRepository.insertAddDraft(
                    inboundMessageId = msgId, userId = "U1", channelId = "C1",
                    items = listOf(
                        ShoppingDraftItemInput(item = "milk"),
                        ShoppingDraftItemInput(item = "   "),
                    ),
                )
            }
        }
        assertContains(exception.message.orEmpty(), "shopping_add_draft_item_item_not_blank")

        // the whole dbQuery is one transaction — the good first item goes with the bad second one
        assertEquals(0, query { ShoppingAddDraft.selectAll().count() })
        assertEquals(0, query { ShoppingAddDraftItem.selectAll().count() })
    }

    @Test
    fun `reusing an inbound message id is rejected by the unique constraint`() {
        val msgId = givenInbound()

        query {
            shoppingRepository.insertAddDraft(
                inboundMessageId = msgId, userId = "U1", channelId = "C1", items = twoItems,
            )
        }

        val exception = assertFailsWith<ExposedSQLException> {
            query {
                shoppingRepository.insertAddDraft(
                    inboundMessageId = msgId, userId = "U1", channelId = "C1",
                    items = listOf(ShoppingDraftItemInput(item = "bread")),
                )
            }
        }
        assertContains(exception.message.orEmpty(), "shopping_add_draft_inbound_message_unique")

        assertEquals(1, query { ShoppingAddDraft.selectAll().count() })
    }
}
