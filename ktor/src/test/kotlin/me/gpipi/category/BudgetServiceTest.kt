package me.gpipi.category

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import me.gpipi.config.dbQuery
import me.gpipi.expense.ExpenseRepository
import me.gpipi.generated.db.base.public1.Category
import me.gpipi.generated.db.base.public1.Expense
import me.gpipi.inbound.InboundRepository
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

class BudgetServiceTest : PersistenceTest() {
    private val budgetZone = ZoneId.of("Asia/Tokyo")
    private val service = BudgetService(db, CategoryRepository(), ExpenseRepository(), budgetZone)
    private val inboundRepository = InboundRepository()

    private fun request(
        name: String = "Monthly Groceries",
        description: String = "Supermarket and pantry spending",
        period: String = "MONTHLY",
        amount: Long = 75_000L,
        active: Boolean = true,
        slackLoggable: Boolean = true,
    ) = UpsertBudgetRequest(
        name = name,
        description = description,
        period = period,
        amount = amount,
        active = active,
        slackLoggable = slackLoggable,
    )

    private suspend fun givenBudget(
        name: String,
        period: String,
        cap: Long,
    ): UUID =
        assertIs<BudgetMutationResult.Created>(
            service.create(
                request(
                    name = name,
                    period = period,
                    amount = cap,
                ),
            ),
        ).id

    private suspend fun givenExpense(
        categoryId: UUID,
        amount: Long,
        spentAt: OffsetDateTime,
        eventId: String,
    ) {
        val inboundMessageId = dbQuery(db) {
            inboundRepository.captureOrSkip(
                eventId,
                "U1",
                "C1",
                "$amount test expense",
                "1751700000.000100",
            )
        }!!

        dbQuery(db) {
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

    @Test
    fun `listBudgets returns mapped active budget lines`() = runBlocking {
        val created = assertIs<BudgetMutationResult.Created>(
            service.create(request(slackLoggable = false)),
        )

        val result = service.listBudgets().single()

        assertEquals(created.id.toString(), result.id)
        assertEquals("Monthly Groceries", result.name)
        assertEquals("Supermarket and pantry spending", result.description)
        assertEquals("MONTHLY", result.period)
        assertEquals(75_000L, result.amount)
        assertEquals(true, result.active)
        assertEquals(false, result.slackLoggable)
    }

    @Test
    fun `create maps a duplicate name to DuplicateName`() = runBlocking {
        assertIs<BudgetMutationResult.Created>(service.create(request()))

        assertEquals(
            BudgetMutationResult.DuplicateName("Monthly Groceries"),
            service.create(request(description = "Duplicate")),
        )
        assertEquals(
            1L,
            dbQuery(db) { Category.selectAll().count() },
        )
    }

    @Test
    fun `invalid requests are rejected before persistence`() = runBlocking {
        val invalidRequests = listOf(
            request(name = " "),
            request(description = ""),
            request(period = "YEARLY"),
            request(amount = -1L),
        )

        val results = invalidRequests.map { service.create(it) }

        assertEquals(
            listOf(
                BudgetMutationResult.Invalid("'name' must not be blank."),
                BudgetMutationResult.Invalid("'description' must not be blank."),
                BudgetMutationResult.Invalid("'period' must be WEEKLY or MONTHLY."),
                BudgetMutationResult.Invalid("'amount' must be zero or greater."),
            ),
            results,
        )
        assertEquals(
            0L,
            dbQuery(db) { Category.selectAll().count() },
        )
    }

    @Test
    fun `update distinguishes updated duplicate and unknown budget lines`() = runBlocking {
        val groceries = assertIs<BudgetMutationResult.Created>(service.create(request()))
        assertIs<BudgetMutationResult.Created>(service.create(request(name = "Transport")))

        assertEquals(
            BudgetMutationResult.Updated,
            service.update(
                groceries.id,
                request(
                    name = "Weekly Groceries",
                    period = "WEEKLY",
                    amount = 20_000L,
                    active = false,
                ),
            ),
        )
        assertEquals(
            BudgetMutationResult.DuplicateName("Transport"),
            service.update(groceries.id, request(name = "Transport")),
        )
        assertEquals(
            BudgetMutationResult.NotFound,
            service.update(UUID.randomUUID(), request(name = "Unknown")),
        )
    }

    @Test
    fun `deactivate only marks the budget inactive and is idempotent`() = runBlocking {
        val created = assertIs<BudgetMutationResult.Created>(service.create(request()))

        assertEquals(BudgetMutationResult.Updated, service.deactivate(created.id))
        assertEquals(BudgetMutationResult.Updated, service.deactivate(created.id))

        val row = dbQuery(db) { Category.selectAll().single() }
        assertEquals(false, row[Category.active])
        assertEquals("Monthly Groceries", row[Category.name])
        assertEquals("Supermarket and pantry spending", row[Category.description])
        assertEquals("MONTHLY", row[Category.period])
        assertEquals(75_000L, row[Category.amount])
        assertEquals(true, row[Category.slackLoggable])
        assertEquals(emptyList(), service.listBudgets())
    }

    @Test
    fun `deactivate returns NotFound for an unknown budget line`() = runBlocking {
        assertEquals(
            BudgetMutationResult.NotFound,
            service.deactivate(UUID.randomUUID()),
        )
    }

    @Test
    fun `spendVsCap totals expenses inside the bucket and calculates remaining`() = runBlocking {
        val categoryId = givenBudget(
            name = "Monthly Groceries",
            period = "MONTHLY",
            cap = 50_000L,
        )
        givenExpense(
            categoryId,
            9_000L,
            OffsetDateTime.parse("2026-06-30T23:59:59+09:00"),
            "Ev-before",
        )
        givenExpense(
            categoryId,
            10_000L,
            OffsetDateTime.parse("2026-07-01T00:00:00+09:00"),
            "Ev-start",
        )
        givenExpense(
            categoryId,
            5_000L,
            OffsetDateTime.parse("2026-07-31T23:59:59+09:00"),
            "Ev-inside",
        )
        givenExpense(
            categoryId,
            20_000L,
            OffsetDateTime.parse("2026-08-01T00:00:00+09:00"),
            "Ev-next-bucket",
        )

        val result = service.spendVsCap(LocalDate.of(2026, 7, 24)).single()

        assertEquals(categoryId.toString(), result.categoryId)
        assertEquals("2026-07-01", result.windowStart)
        assertEquals("2026-08-01", result.windowEndExclusive)
        assertEquals(15_000L, result.spent)
        assertEquals(35_000L, result.remaining)
    }

    @Test
    fun `spendVsCap reports zero spent and the full cap when there are no expenses`() = runBlocking {
        val categoryId = givenBudget(
            name = "Mortgage",
            period = "MONTHLY",
            cap = 120_000L,
        )

        val result = service.spendVsCap(LocalDate.of(2026, 7, 24)).single()

        assertEquals(categoryId.toString(), result.categoryId)
        assertEquals(0L, result.spent)
        assertEquals(120_000L, result.remaining)
    }

    @Test
    fun `spendVsCap buckets weekly and monthly categories independently`() = runBlocking {
        val weeklyId = givenBudget(
            name = "Eating Out",
            period = "WEEKLY",
            cap = 10_000L,
        )
        val monthlyId = givenBudget(
            name = "Monthly Groceries",
            period = "MONTHLY",
            cap = 20_000L,
        )

        givenExpense(
            weeklyId,
            900L,
            OffsetDateTime.parse("2026-07-12T23:59:59+09:00"),
            "Ev-weekly-before",
        )
        givenExpense(
            weeklyId,
            2_000L,
            OffsetDateTime.parse("2026-07-13T00:00:00+09:00"),
            "Ev-weekly-start",
        )
        givenExpense(
            weeklyId,
            4_000L,
            OffsetDateTime.parse("2026-07-20T00:00:00+09:00"),
            "Ev-weekly-end",
        )
        givenExpense(
            monthlyId,
            3_000L,
            OffsetDateTime.parse("2026-07-12T23:59:59+09:00"),
            "Ev-monthly-before-week",
        )
        givenExpense(
            monthlyId,
            5_000L,
            OffsetDateTime.parse("2026-07-20T00:00:00+09:00"),
            "Ev-monthly-after-week",
        )
        givenExpense(
            monthlyId,
            6_000L,
            OffsetDateTime.parse("2026-08-01T00:00:00+09:00"),
            "Ev-monthly-end",
        )

        val resultByName = service
            .spendVsCap(LocalDate.of(2026, 7, 15))
            .associateBy(SpendRow::name)

        assertEquals(2_000L, resultByName.getValue("Eating Out").spent)
        assertEquals(8_000L, resultByName.getValue("Eating Out").remaining)
        assertEquals("2026-07-13", resultByName.getValue("Eating Out").windowStart)
        assertEquals("2026-07-20", resultByName.getValue("Eating Out").windowEndExclusive)
        assertEquals(8_000L, resultByName.getValue("Monthly Groceries").spent)
        assertEquals(12_000L, resultByName.getValue("Monthly Groceries").remaining)
        assertEquals("2026-07-01", resultByName.getValue("Monthly Groceries").windowStart)
        assertEquals("2026-08-01", resultByName.getValue("Monthly Groceries").windowEndExclusive)
    }
}
