package me.gpipi.categorization

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.gpipi.config.dbQuery
import me.gpipi.expense.ExpenseRepository
import me.gpipi.extraction.Extraction
import me.gpipi.generated.db.base.public1.BudgetEnvelope
import me.gpipi.generated.db.base.public1.CategorizationEvent
import me.gpipi.generated.db.base.public1.Category
import me.gpipi.inbound.InboundRepository
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

class CategorizationEventRepositoryTest : PersistenceTest() {
    private val repo = CategorizationEventRepository()
    private val expenseRepository = ExpenseRepository()
    private val inboundRepository = InboundRepository()

    private fun <T> query(block: () -> T): T = runBlocking { dbQuery(db) { block() } }

    private fun givenInbound(eventId: String = "Ev001"): UUID = runBlocking {
        dbQuery(db) { inboundRepository.captureOrSkip(eventId, "U1", "C1", "7500 tokyu store", "1751700000.000100") }!!
    }

    private fun givenCategory(name: String): UUID = query {
        val envId = UUID.randomUUID()
        BudgetEnvelope.insert {
            it[BudgetEnvelope.id] = envId
            it[BudgetEnvelope.name] = "Env $name"
            it[BudgetEnvelope.period] = "MONTHLY"
            it[BudgetEnvelope.amount] = 60000L
        }
        val catId = UUID.randomUUID()
        Category.insert {
            it[Category.id] = catId
            it[Category.envelopeId] = envId
            it[Category.name] = name
            it[Category.description] = "desc for $name"
        }
        catId
    }

    private fun givenExpense(msgId: UUID, catId: UUID): UUID = query {
        expenseRepository.insert(
            Extraction(amount = 7500, currency = "JPY", merchant = "Tokyu Store",
                category = "Monthly Groceries", confidence = 0.7, note = null),
            inboundMessageId = msgId, userId = "U1", categoryId = catId,
        )
    }

    @Test
    fun `records a labeled event linking inbound, expense, and both categories`() {
        val msgId = givenInbound()
        val predicted = givenCategory("Convenience Store")
        val expenseId = givenExpense(msgId, predicted)

        val eventId = query {
            repo.insert(
                inboundMessageId = msgId, expenseId = expenseId,
                predictedCategoryId = predicted, finalCategoryId = predicted,
                confidence = 0.7, model = "qwen/qwen3-instruct",
            )
        }

        val row = query { CategorizationEvent.selectAll().single() }
        assertEquals(eventId, row[CategorizationEvent.id])
        assertEquals(msgId, row[CategorizationEvent.inboundMessageId])
        assertEquals(expenseId, row[CategorizationEvent.expenseId])
        assertEquals(predicted, row[CategorizationEvent.predictedCategoryId])
        assertEquals(predicted, row[CategorizationEvent.finalCategoryId])
        assertEquals("qwen/qwen3-instruct", row[CategorizationEvent.model])
        assertEquals(0.7, row[CategorizationEvent.confidence]!!.toDouble())
    }

    @Test
    fun `was_corrected is false when prediction equals final`() {
        val msgId = givenInbound()
        val cat = givenCategory("Convenience Store")
        val expenseId = givenExpense(msgId, cat)

        query { repo.insert(msgId, expenseId, predictedCategoryId = cat, finalCategoryId = cat, confidence = 0.9, model = null) }

        assertFalse(query { CategorizationEvent.selectAll().single()[CategorizationEvent.wasCorrected] })
    }

    @Test
    fun `was_corrected is true when the user changed the category`() {
        val msgId = givenInbound()
        val predicted = givenCategory("Convenience Store")
        val corrected = givenCategory("Monthly Groceries")
        val expenseId = givenExpense(msgId, corrected)   // expense carries the FINAL category

        query { repo.insert(msgId, expenseId, predictedCategoryId = predicted, finalCategoryId = corrected, confidence = 0.55, model = null) }

        val row = query { CategorizationEvent.selectAll().single() }
        assertTrue(row[CategorizationEvent.wasCorrected])
        assertEquals(predicted, row[CategorizationEvent.predictedCategoryId])
        assertEquals(corrected, row[CategorizationEvent.finalCategoryId])
    }

    @Test
    fun `nullable confidence and model persist as null`() {
        val msgId = givenInbound()
        val cat = givenCategory("Other")
        val expenseId = givenExpense(msgId, cat)

        query { repo.insert(msgId, expenseId, predictedCategoryId = cat, finalCategoryId = cat, confidence = null, model = null) }

        val row = query { CategorizationEvent.selectAll().single() }
        assertNull(row[CategorizationEvent.confidence])
        assertNull(row[CategorizationEvent.model])
    }
}
