package me.gpipi.expense

import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import me.gpipi.config.dbQuery
import me.gpipi.extraction.Extraction
import me.gpipi.generated.db.base.public1.Category
import me.gpipi.generated.db.base.public1.Expense
import me.gpipi.inbound.InboundRepository
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

class ExpenseRepositoryTest : PersistenceTest() {
    private val expenseRepository = ExpenseRepository()
    private val inboundRepository = InboundRepository()

    private fun givenInbound(eventId: String = "Ev001"): UUID = runBlocking {
        dbQuery(db) { inboundRepository.captureOrSkip(eventId, "U1", "C1", "1500 ramen", "1751700000.000100") }!!
    }

    private fun givenCategory(name: String = "Monthly Groceries"): UUID = runBlocking {
        dbQuery(db) {
            val catId = UUID.randomUUID()
            Category.insert {
                it[Category.id] = catId
                it[Category.name] = name
                it[Category.description] = "supermarket runs, bulk shopping"
                it[Category.period] = "MONTHLY"
                it[Category.amount] = 50000L
                it[Category.slackLoggable] = true
            }
            catId
        }
    }

    private fun givenExpense(
        categoryId: UUID,
        amount: Long,
        spentAt: OffsetDateTime,
        eventId: String,
    ) {
        val inboundMessageId = givenInbound(eventId)
        query {
            Expense.insert {
                it[Expense.inboundMessageId] = inboundMessageId
                it[Expense.userId] = "U1"
                it[Expense.amount] = amount
                it[Expense.currency] = "JPY"
                it[Expense.categoryId] = categoryId
                it[Expense.spentAt] = spentAt
            }
        }
    }

    private fun <T> query(block: () -> T): T = runBlocking { dbQuery(db) { block() } }

    private fun extraction() = Extraction(
        amount = 1500, currency = "JPY", merchant = "Ito Yokado",
        category = "Monthly Groceries", confidence = 0.9, note = null,
    )

    @Test
    fun `insert writes an expense linked to inbound message`() {
        val msgId = givenInbound()
        val catId = givenCategory()
        val expenseId = query { expenseRepository.insert(extraction(), inboundMessageId = msgId, userId = "U1", categoryId = catId) }
        val row = query { Expense.selectAll().single() }

        assertEquals(expenseId, row[Expense.id])
        assertEquals(msgId, row[Expense.inboundMessageId])  // FK link is the key assertion
        assertEquals(1500L, row[Expense.amount])
        assertEquals("Ito Yokado", row[Expense.merchant])
        assertEquals(catId, row[Expense.categoryId])
    }

    @Test
    fun `apply default currency JPY, source SLACK, timestamps set`() {
        val msgId = givenInbound()
        val catId = givenCategory()
        query { expenseRepository.insert(extraction(), msgId, "U1", catId) }

        val row = query { Expense.selectAll().single() }

        assertEquals("JPY", row[Expense.currency])
        assertEquals("SLACK", row[Expense.source1])

        assertNotNull(row[Expense.spentAt])
        assertNotNull(row[Expense.createdAt])
    }

    @Test
    fun `nullable merchant and note persist as null`() {
        val msgId = givenInbound()
        val catId = givenCategory()
        query { expenseRepository.insert(extraction().copy(merchant = null, note = null), msgId, "U1", catId) }

        val row = query { Expense.selectAll().single() }

        assertNull(row[Expense.merchant])
        assertNull(row[Expense.note])
    }

    @Test
    fun `insert with unknown inbound message id violates the FK`() {
        val catId = givenCategory()
        assertFailsWith<ExposedSQLException> {
            query { expenseRepository.insert(extraction(), inboundMessageId = UUID.randomUUID(), userId = "U1", categoryId = catId) }
        }
    }

    @Test
    fun `sumAmount includes the start and excludes the end of the period`() {
        val categoryId = givenCategory()
        val from = OffsetDateTime.parse("2026-07-01T00:00:00+09:00")
        val to = OffsetDateTime.parse("2026-08-01T00:00:00+09:00")
        givenExpense(categoryId, 1_000L, from, "Ev-start")
        givenExpense(categoryId, 2_000L, to.minusSeconds(1), "Ev-inside")
        givenExpense(categoryId, 4_000L, to, "Ev-next-period")

        val total = query {
            expenseRepository.sumAmount(categoryId, from, to)
        }

        assertEquals(3_000L, total)
    }

    @Test
    fun `sumAmount only includes expenses from the requested category`() {
        val groceriesId = givenCategory("Monthly Groceries")
        val transportId = givenCategory("Transport")
        val from = OffsetDateTime.parse("2026-07-01T00:00:00+09:00")
        val to = OffsetDateTime.parse("2026-08-01T00:00:00+09:00")
        givenExpense(groceriesId, 1_000L, from.plusDays(1), "Ev-groceries")
        givenExpense(transportId, 8_000L, from.plusDays(1), "Ev-transport")

        val total = query {
            expenseRepository.sumAmount(groceriesId, from, to)
        }

        assertEquals(1_000L, total)
    }

    @Test
    fun `sumAmount returns zero when no expenses match`() {
        val categoryId = givenCategory()
        val from = OffsetDateTime.parse("2026-07-01T00:00:00+09:00")
        val to = OffsetDateTime.parse("2026-08-01T00:00:00+09:00")

        val total = query {
            expenseRepository.sumAmount(categoryId, from, to)
        }

        assertEquals(0L, total)
    }

    @Test
    fun `sumAmount rejects an inverted period`() {
        val from = OffsetDateTime.parse("2026-08-01T00:00:00+09:00")
        val to = OffsetDateTime.parse("2026-07-01T00:00:00+09:00")

        val error = assertFailsWith<IllegalArgumentException> {
            query {
                expenseRepository.sumAmount(UUID.randomUUID(), from, to)
            }
        }

        assertEquals(
            "fromInclusive must not be after toExclusive",
            error.message,
        )
    }

}
