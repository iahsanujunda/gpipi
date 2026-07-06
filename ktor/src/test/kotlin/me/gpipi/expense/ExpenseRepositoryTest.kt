package me.gpipi.expense

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import me.gpipi.config.dbQuery
import me.gpipi.extraction.Extraction
import me.gpipi.generated.db.base.public1.Expense
import me.gpipi.inbound.InboundRepository
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.selectAll

class ExpenseRepositoryTest : PersistenceTest() {
    private val expenseRepository = ExpenseRepository()
    private val inboundRepository = InboundRepository()

    private fun givenInbound(eventId: String = "Ev001"): UUID = runBlocking {
        dbQuery(db) { inboundRepository.captureOrSkip(eventId, "U1", "C1", "1500 ramen", "1751700000.000100") }!!
    }

    private fun <T> query(block: () -> T): T = runBlocking { dbQuery(db) { block() } }

    private fun extraction() = Extraction(
        amount = 1500, currency = "JPY", merchant = "Ito Yokado",
        category = "Monthly Groceries", confidence = 0.9, note = null,
    )

    @Test
    fun `insert writes an expense linked to inbound message`() {
        val msgId = givenInbound()
        val expenseId = query { expenseRepository.insert(extraction(), inboundMessageId = msgId, userId = "U1") }
        val row = query { Expense.selectAll().single() }

        assertEquals(expenseId, row[Expense.id])
        assertEquals(msgId, row[Expense.inboundMessageId])  // FK link is the key assertion
        assertEquals(1500L, row[Expense.amount])
        assertEquals("Monthly Groceries", row[Expense.category])
        assertEquals("Ito Yokado", row[Expense.merchant])
    }

    @Test
    fun `apply default currency JPY, source SLACK, timestamps set`() {
        val msgId = givenInbound()
        query { expenseRepository.insert(extraction(), msgId, "U1") }

        val row = query { Expense.selectAll().single() }

        assertEquals("JPY", row[Expense.currency])
        assertEquals("SLACK", row[Expense.source1])

        assertNotNull(row[Expense.spentAt])
        assertNotNull(row[Expense.createdAt])
    }

    @Test
    fun `nullable merchant and note persist as null`() {
        val msgId = givenInbound()
        query { expenseRepository.insert(extraction().copy(merchant = null, note = null), msgId, "U1") }

        val row = query { Expense.selectAll().single() }

        assertNull(row[Expense.merchant])
        assertNull(row[Expense.note])
    }

    @Test
    fun `insert with unknown inbound message id violates the FK`() {
        assertFailsWith<ExposedSQLException> {
            query { expenseRepository.insert(extraction(), inboundMessageId = UUID.randomUUID(), userId = "U1") }
        }
    }

}