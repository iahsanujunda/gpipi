package me.gpipi.category

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import me.gpipi.config.dbQuery
import me.gpipi.generated.db.base.public1.Category
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.jdbc.selectAll

class BudgetServiceTest : PersistenceTest() {
    private val service = BudgetService(db, CategoryRepository())

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
}
