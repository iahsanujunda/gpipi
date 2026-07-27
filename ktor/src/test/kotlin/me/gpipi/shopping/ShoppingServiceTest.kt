package me.gpipi.shopping

import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import me.gpipi.config.dbQuery
import me.gpipi.generated.db.base.public1.ShoppingAddDraft
import me.gpipi.generated.db.base.public1.ShoppingItem
import me.gpipi.generated.db.base.public1.ShoppingMutation
import me.gpipi.generated.db.base.public1.ShoppingMutationItem
import me.gpipi.inbound.InboundRepository
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.jdbc.selectAll

class ShoppingServiceTest : PersistenceTest() {
    private val repository = ShoppingRepository()
    private val instant = Instant.parse("2026-07-27T00:00:00Z")
    private val occurredAt = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
    private val service = ShoppingService(
        db = db,
        repository = repository,
        clock = Clock.fixed(instant, ZoneOffset.UTC),
    )

    private fun <T> query(block: () -> T): T =
        runBlocking { dbQuery(db) { block() } }

    private fun givenDraft(
        eventId: String,
        items: List<ShoppingDraftItemInput>,
        userId: String = "U-sender",
    ): UUID {
        val inboundId = query {
            InboundRepository().captureOrSkip(
                eventId,
                userId,
                "C1",
                "list add ${items.joinToString(" and ") { it.item }}",
                "1751700000.000100",
            )
        }!!
        return query {
            repository.insertAddDraft(inboundId, userId, "C1", items)
        }
    }

    @Test
    fun `confirm add consumes the draft and records an attributable ADD mutation`() = runBlocking {
        val draftId = givenDraft(
            "EvAdd",
            listOf(
                ShoppingDraftItemInput("milk", "1L", "low fat"),
                ShoppingDraftItemInput("eggs"),
            ),
        )

        val result = assertNotNull(service.confirmAdd(draftId, "U-clicker"))

        assertEquals(listOf("milk", "eggs"), result.changed.map { it.item })
        assertTrue(result.skipped.isEmpty())
        val mutationId = assertNotNull(result.mutationId)

        val draft = query { ShoppingAddDraft.selectAll().single() }
        assertEquals("CONFIRMED", draft[ShoppingAddDraft.status])
        assertEquals(occurredAt, draft[ShoppingAddDraft.completedAt])

        val items = query {
            ShoppingItem.selectAll().orderBy(ShoppingItem.item).toList()
        }
        assertEquals(2, items.size)
        assertTrue(items.all { it[ShoppingItem.status] == "PENDING" })
        assertTrue(items.all { it[ShoppingItem.addedBy] == "U-sender" })
        assertTrue(items.all { it[ShoppingItem.currentMutationId] == mutationId })

        val mutation = query { ShoppingMutation.selectAll().single() }
        assertEquals("ADD", mutation[ShoppingMutation.kind])
        assertEquals("U-clicker", mutation[ShoppingMutation.actorId])
        assertEquals(
            2L,
            query { ShoppingMutationItem.selectAll().count() },
        )
    }

    @Test
    fun `normalized duplicates are skipped while different qualifiers remain distinct`() = runBlocking {
        val firstDraft = givenDraft(
            "EvFirst",
            listOf(ShoppingDraftItemInput("Milk")),
        )
        assertNotNull(service.confirmAdd(firstDraft, "U1"))

        val secondDraft = givenDraft(
            "EvSecond",
            listOf(
                ShoppingDraftItemInput(" milk ", quantity = " "),
                ShoppingDraftItemInput("MILK"),
                ShoppingDraftItemInput("milk", quantity = "2 cartons"),
            ),
        )

        val result = assertNotNull(service.confirmAdd(secondDraft, "U2"))

        assertEquals(listOf("milk"), result.changed.map { it.item })
        assertEquals(listOf("2 cartons"), result.changed.map { it.quantity })
        assertEquals(2, result.skipped.size)
        assertEquals(2L, query { ShoppingItem.selectAll().count() })
        assertEquals(
            setOf(null, "2 cartons"),
            query { ShoppingItem.selectAll().map { it[ShoppingItem.quantity] }.toSet() },
        )
    }

    @Test
    fun `two concurrent confirms consume a draft once`() = runBlocking {
        val draftId = givenDraft(
            "EvConcurrent",
            listOf(ShoppingDraftItemInput("milk")),
        )

        val results = coroutineScope {
            listOf(
                async { service.confirmAdd(draftId, "U1") },
                async { service.confirmAdd(draftId, "U2") },
            ).awaitAll()
        }

        assertEquals(1, results.count { it != null })
        assertEquals(1L, query { ShoppingItem.selectAll().count() })
        assertEquals(1L, query { ShoppingMutation.selectAll().count() })
    }

    @Test
    fun `concurrent drafts with the same identity converge on one pending row`() = runBlocking {
        val firstDraft = givenDraft(
            "EvConcurrentFirst",
            listOf(ShoppingDraftItemInput(" Milk ", note = "low fat")),
        )
        val secondDraft = givenDraft(
            "EvConcurrentSecond",
            listOf(ShoppingDraftItemInput("milk", note = " LOW FAT ")),
        )

        val results = coroutineScope {
            listOf(
                async { service.confirmAdd(firstDraft, "U1") },
                async { service.confirmAdd(secondDraft, "U2") },
            ).awaitAll().map(::checkNotNull)
        }

        assertEquals(1, results.sumOf { it.changed.size })
        assertEquals(1, results.sumOf { it.skipped.size })
        assertEquals(1L, query { ShoppingItem.selectAll().count() })
        assertEquals(1L, query { ShoppingMutation.selectAll().count() })
    }

    @Test
    fun `cancel consumes a draft once without creating items`() = runBlocking {
        val draftId = givenDraft(
            "EvCancel",
            listOf(ShoppingDraftItemInput("bread")),
        )

        assertTrue(service.cancelAdd(draftId))
        assertEquals(false, service.cancelAdd(draftId))

        val draft = query { ShoppingAddDraft.selectAll().single() }
        assertEquals("CANCELLED", draft[ShoppingAddDraft.status])
        assertEquals(occurredAt, draft[ShoppingAddDraft.completedAt])
        assertEquals(0L, query { ShoppingItem.selectAll().count() })
    }

    @Test
    fun `mark bought is idempotent and records only the first transition`() = runBlocking {
        val draftId = givenDraft(
            "EvBought",
            listOf(ShoppingDraftItemInput("milk")),
        )
        assertNotNull(service.confirmAdd(draftId, "U-add"))
        val itemId = service.listPending().single().id

        val first = service.markBought(listOf(itemId), "U-buyer")
        val second = service.markBought(listOf(itemId), "U-buyer")

        assertEquals(listOf("milk"), first.changed.map { it.item })
        assertNotNull(first.mutationId)
        assertTrue(second.changed.isEmpty())
        assertNull(second.mutationId)

        val item = query { ShoppingItem.selectAll().single() }
        assertEquals("BOUGHT", item[ShoppingItem.status])
        assertEquals("U-buyer", item[ShoppingItem.boughtBy])
        assertEquals(occurredAt, item[ShoppingItem.boughtAt])
        assertEquals(2L, query { ShoppingMutation.selectAll().count() })
    }

    @Test
    fun `Undo Add removes an unchanged pending row and repeated Undo is a no-op`() = runBlocking {
        val draftId = givenDraft(
            "EvUndoAdd",
            listOf(ShoppingDraftItemInput("milk")),
        )
        val add = assertNotNull(service.confirmAdd(draftId, "U-add"))

        val first = assertNotNull(service.undo(assertNotNull(add.mutationId), "U-undo"))
        val second = service.undo(add.mutationId, "U-undo")

        assertEquals("ADD", first.targetKind)
        assertEquals(listOf("milk"), first.changed.map { it.item })
        assertNotNull(first.mutationId)
        assertNull(second)

        val item = query { ShoppingItem.selectAll().single() }
        assertEquals("REMOVED", item[ShoppingItem.status])
        assertEquals("U-undo", item[ShoppingItem.removedBy])
        assertEquals(occurredAt, item[ShoppingItem.removedAt])
        assertEquals(1L, query { ShoppingItem.selectAll().count() })
    }

    @Test
    fun `Undo Bought partially restores eligible rows without duplicating a newer pending identity`() = runBlocking {
        val firstDraft = givenDraft(
            "EvUndoBought",
            listOf(
                ShoppingDraftItemInput("milk"),
                ShoppingDraftItemInput("eggs"),
            ),
        )
        assertNotNull(service.confirmAdd(firstDraft, "U-add"))
        val originalIds = service.listPending().map { it.id }
        val bought = service.markBought(originalIds, "U-buyer")

        val newerDraft = givenDraft(
            "EvNewMilk",
            listOf(ShoppingDraftItemInput("milk")),
        )
        assertNotNull(service.confirmAdd(newerDraft, "U-add-2"))

        val undo = assertNotNull(
            service.undo(assertNotNull(bought.mutationId), "U-undo"),
        )

        assertEquals(listOf("eggs"), undo.changed.map { it.item })
        assertEquals(listOf("milk"), undo.skipped.map { it.item })
        assertNotNull(undo.mutationId)
        assertEquals(
            setOf("eggs", "milk"),
            service.listPending().map { it.item }.toSet(),
        )
        assertEquals(3L, query { ShoppingItem.selectAll().count() })
    }

    @Test
    fun `old Undo Add cannot reverse an item changed by Mark Bought`() = runBlocking {
        val draftId = givenDraft(
            "EvStaleUndoAdd",
            listOf(ShoppingDraftItemInput("milk")),
        )
        val add = assertNotNull(service.confirmAdd(draftId, "U-add"))
        val itemId = service.listPending().single().id
        service.markBought(listOf(itemId), "U-buyer")

        val undo = assertNotNull(
            service.undo(assertNotNull(add.mutationId), "U-stale"),
        )

        assertTrue(undo.changed.isEmpty())
        assertNull(undo.mutationId)
        assertEquals(
            "BOUGHT",
            query { ShoppingItem.selectAll().single()[ShoppingItem.status] },
        )
        assertEquals(2L, query { ShoppingMutation.selectAll().count() })
    }

    @Test
    fun `stale repeated Undo Bought cannot reverse a newer bought transition`() = runBlocking {
        val draftId = givenDraft(
            "EvStaleUndoBought",
            listOf(ShoppingDraftItemInput("milk")),
        )
        assertNotNull(service.confirmAdd(draftId, "U-add"))
        val itemId = service.listPending().single().id
        val firstMark = service.markBought(listOf(itemId), "U-buyer")
        assertNotNull(service.undo(assertNotNull(firstMark.mutationId), "U-undo"))
        val secondMark = service.markBought(listOf(itemId), "U-buyer-2")

        val staleUndo = service.undo(firstMark.mutationId, "U-stale")

        assertNull(staleUndo)
        assertNotNull(secondMark.mutationId)
        assertEquals(
            "BOUGHT",
            query { ShoppingItem.selectAll().single()[ShoppingItem.status] },
        )
    }
}
