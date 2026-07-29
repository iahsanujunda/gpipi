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
import me.gpipi.support.insertTestAccount
import me.gpipi.support.insertTestCategory
import me.gpipi.support.testCategoryAccountId
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

class ExpenseRepositoryTest : PersistenceTest() {
    private val expenseRepository = ExpenseRepository()
    private val inboundRepository = InboundRepository()

    private fun givenInbound(
        eventId: String = "Ev001",
        text: String? = "1500 ramen",
    ): UUID = runBlocking {
        dbQuery(db) { inboundRepository.captureOrSkip(eventId, "U1", "C1", text, "1751700000.000100") }!!
    }

    private fun givenCategory(name: String = "Monthly Groceries"): UUID = runBlocking {
        dbQuery(db) {
            insertTestCategory(
                name = name,
                description = "supermarket runs, bulk shopping",
            )
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
                it[Expense.accountId] = testCategoryAccountId(categoryId)
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
        assertEquals(query { testCategoryAccountId(catId) }, row[Expense.accountId])
    }

    @Test
    fun `expense snapshots its wallet when the category is later rerouted`() {
        val originalAccount = query { insertTestAccount("Original wallet") }
        val newAccount = query { insertTestAccount("New wallet") }
        val categoryId = query {
            insertTestCategory(name = "Rerouted budget", accountId = originalAccount)
        }
        val messageId = givenInbound()

        val expenseId = query {
            expenseRepository.insert(extraction(), messageId, "U1", categoryId)
        }
        query {
            Category.update({ Category.id eq categoryId }) {
                it[Category.accountId] = newAccount
            }
        }

        val expenseAccount = query {
            Expense.selectAll().where { Expense.id eq expenseId }.single()[Expense.accountId]
        }
        assertEquals(originalAccount, expenseAccount)
        assertEquals(newAccount, query { testCategoryAccountId(categoryId) })
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
    fun `list exposes a decoded description supplied in the Slack message`() {
        val msgId = givenInbound(text = "<@U123> ¥1,500 for ramen &amp; gyoza &lt;late&gt;")
        val catId = givenCategory("Eating Out")
        query {
            expenseRepository.insert(
                extraction().copy(amount = 1_500, merchant = null),
                msgId,
                "U1",
                catId,
            )
        }

        val expense = query { expenseRepository.list(from = null, to = null, categoryId = null).single() }

        assertEquals("ramen & gyoza <late>", expense.description)
    }

    @Test
    fun `description removes the extracted amount when Slack text puts it last`() {
        assertEquals("jidouki", expenseDescription("jidouki 150", note = null, amount = 150))
        assertEquals(
            "mipi popok etc",
            expenseDescription("mipi popok etc ¥1,476 JPY", note = null, amount = 1_476),
        )
        assertEquals(
            "shinjuku halal",
            expenseDescription("<@U123> shinjuku halal 9 699円.", note = null, amount = 9_699),
        )
        assertEquals("cycle park", expenseDescription("cycle park 200.", note = null, amount = 200))
    }

    @Test
    fun `description preserves other numbers and text without the extracted amount`() {
        assertEquals(
            "Route 150 cafe",
            expenseDescription("Route 150 cafe 800", note = null, amount = 800),
        )
        assertEquals(
            "topup pasmo",
            expenseDescription("topup pasmo", note = null, amount = 1_000),
        )
        assertEquals(
            "Studio 150",
            expenseDescription("Studio 150", note = null, amount = 200),
        )
    }

    @Test
    fun `list falls back to the extracted note when Slack text has expired`() {
        val msgId = givenInbound(text = null)
        val catId = givenCategory("Eating Out")
        query {
            expenseRepository.insert(
                extraction().copy(merchant = null, note = "ramen &amp; gyoza"),
                msgId,
                "U1",
                catId,
            )
        }

        val expense = query { expenseRepository.list(from = null, to = null, categoryId = null).single() }

        assertEquals("ramen & gyoza", expense.description)
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
