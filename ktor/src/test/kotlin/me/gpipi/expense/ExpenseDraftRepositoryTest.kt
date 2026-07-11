package me.gpipi.expense

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import me.gpipi.config.dbQuery
import me.gpipi.generated.db.base.public1.BudgetEnvelope
import me.gpipi.generated.db.base.public1.Category
import me.gpipi.generated.db.base.public1.ExpenseDraft
import me.gpipi.inbound.InboundRepository
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

class ExpenseDraftRepositoryTest : PersistenceTest() {
    private val draftRepository = ExpenseDraftRepository()
    private val inboundRepository = InboundRepository()

    private fun givenInbound(eventId: String = "Ev001"): UUID = runBlocking {
        dbQuery(db) { inboundRepository.captureOrSkip(eventId, "U1", "C1", "510 conbini", "1751700000.000100") }!!
    }

    private fun givenCategory(name: String = "Convenience Store"): UUID = runBlocking {
        dbQuery(db) {
            val envId = UUID.randomUUID()
            BudgetEnvelope.insert {
                it[BudgetEnvelope.id] = envId
                it[BudgetEnvelope.name] = "Test Envelope $name"
                it[BudgetEnvelope.period] = "WEEKLY"
                it[BudgetEnvelope.amount] = 15000L
            }
            val catId = UUID.randomUUID()
            Category.insert {
                it[Category.id] = catId
                it[Category.envelopeId] = envId
                it[Category.name] = name
                it[Category.description] = "konbini, small quick purchases"
            }
            catId
        }
    }

    private fun <T> query(block: () -> T): T = runBlocking { dbQuery(db) { block() } }

    private fun insertDraft(msgId: UUID, catId: UUID, merchant: String? = "conbini", note: String? = null): UUID =
        query {
            draftRepository.insert(
                inboundMessageId = msgId,
                userId = "U1",
                channelId = "C1",
                amount = 510,
                currency = "JPY",
                merchant = merchant,
                note = note,
                predictedCategoryId = catId,
                confidence = 0.9,
                model = "qwen/qwen3-instruct",
            )
        }

    @Test
    fun `insert writes a draft with status PENDING and the extracted fields`() {
        val msgId = givenInbound()
        val catId = givenCategory()
        val draftId = insertDraft(msgId, catId)

        val row = query { ExpenseDraft.selectAll().single() }

        assertEquals(draftId, row[ExpenseDraft.id])
        assertEquals(msgId, row[ExpenseDraft.inboundMessageId])
        assertEquals(catId, row[ExpenseDraft.predictedCategoryId])
        assertEquals(510L, row[ExpenseDraft.amount])
        assertEquals("conbini", row[ExpenseDraft.merchant])
        assertEquals("PENDING", row[ExpenseDraft.status])
        assertEquals(0.9, row[ExpenseDraft.confidence]!!.toDouble())
        assertNotNull(row[ExpenseDraft.createdAt])
    }

    @Test
    fun `nullable merchant note and confidence persist as null`() {
        val msgId = givenInbound()
        val catId = givenCategory()
        query {
            draftRepository.insert(
                inboundMessageId = msgId, userId = "U1", channelId = "C1",
                amount = 2000, currency = "JPY", merchant = null, note = null,
                predictedCategoryId = catId, confidence = null, model = null,
            )
        }

        val row = query { ExpenseDraft.selectAll().single() }
        assertNull(row[ExpenseDraft.merchant])
        assertNull(row[ExpenseDraft.note])
        assertNull(row[ExpenseDraft.confidence])
        assertNull(row[ExpenseDraft.model])
    }

    @Test
    fun `consumeIfPending flips to CONFIRMED and returns the row`() {
        val msgId = givenInbound()
        val catId = givenCategory()
        val draftId = insertDraft(msgId, catId)

        val consumed = query { draftRepository.consumeIfPending(draftId) }

        assertNotNull(consumed)
        assertEquals(draftId, consumed.id)
        assertEquals(msgId, consumed.inboundMessageId)
        assertEquals(catId, consumed.predictedCategoryId)
        assertEquals(510L, consumed.amount)
        assertEquals("conbini", consumed.merchant)
        assertEquals("C1", consumed.channelId)
        assertEquals(0.9, consumed.confidence!!.toDouble())

        val status = query { ExpenseDraft.selectAll().single()[ExpenseDraft.status] }
        assertEquals("CONFIRMED", status)
    }

    @Test
    fun `consumeIfPending a second time returns null - double-tap guard`() {
        val msgId = givenInbound()
        val catId = givenCategory()
        val draftId = insertDraft(msgId, catId)

        assertNotNull(query { draftRepository.consumeIfPending(draftId) })
        assertNull(query { draftRepository.consumeIfPending(draftId) })

        // the row is still there, still CONFIRMED — only its status transition is single-shot
        assertEquals(1, query { ExpenseDraft.selectAll().count() })
        assertEquals("CONFIRMED", query { ExpenseDraft.selectAll().single()[ExpenseDraft.status] })
    }

    @Test
    fun `consumeIfPending on an unknown id returns null`() {
        assertNull(query { draftRepository.consumeIfPending(UUID.randomUUID()) })
    }
}
