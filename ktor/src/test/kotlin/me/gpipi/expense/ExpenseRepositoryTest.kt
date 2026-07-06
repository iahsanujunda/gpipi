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
        val row = query { Expenses.selectAll().single() }

        assertEquals(expenseId, row[Expenses.id].value)
        assertEquals(msgId, row[Expenses.inboundMessageId].value)  // FK link is the key assertion
        assertEquals(1500L, row[Expenses.amount])
        assertEquals("Monthly Groceries", row[Expenses.category])
        assertEquals("Ito Yokado", row[Expenses.merchant])
    }

    @Test
    fun `apply default currency JPY, source SLACK, timestamps set`() {
        val msgId = givenInbound()
        query { expenseRepository.insert(extraction(), msgId, "U1") }

        val row = query { Expenses.selectAll().single() }

        assertEquals("JPY", row[Expenses.currency])
        assertEquals("SLACK", row[Expenses.sourceCol])

        assertNotNull(row[Expenses.spentAt])
        assertNotNull(row[Expenses.createdAt])
    }

    @Test
    fun `nullable merchant and note persist as null`() {
        val msgId = givenInbound()
        query { expenseRepository.insert(extraction().copy(merchant = null, note = null), msgId, "U1") }

        val row = query { Expenses.selectAll().single() }

        assertNull(row[Expenses.merchant])
        assertNull(row[Expenses.note])
    }

    @Test
    fun `insert with unknown inbound message id violates the FK`() {
        assertFailsWith<ExposedSQLException> {
            query { expenseRepository.insert(extraction(), inboundMessageId = UUID.randomUUID(), userId = "U1") }
        }
    }

}