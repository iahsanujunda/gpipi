package me.gpipi.category

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.gpipi.config.dbQuery
import me.gpipi.generated.db.base.public1.Category
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

class CategoryRepositoryTest : PersistenceTest() {
    private val repo = CategoryRepository()

    private fun <T> query(block: () -> T): T = runBlocking { dbQuery(db) { block() } }

    private fun seedCategory(
        name: String,
        description: String,
        active: Boolean,
        slackLoggable: Boolean = true,
    ): UUID = query {
        val id = UUID.randomUUID()
        Category.insert {
            it[Category.id] = id
            it[Category.name] = name
            it[Category.description] = description
            it[Category.period] = "MONTHLY"
            it[Category.amount] = 50000L
            it[Category.slackLoggable] = slackLoggable
            it[Category.active] = active
        }
        id
    }

    @Test
    fun `findActive returns only active Slack-loggable categories`() {
        val activeId = seedCategory("Eating Out", "restaurants, cafes", active = true)
        seedCategory("Retired", "no longer used", active = false)
        seedCategory("Mortgage", "fixed monthly obligation", active = true, slackLoggable = false)

        val result = query { repo.findActive() }

        assertEquals(1, result.size)
        assertEquals(activeId, result.single().id)
        assertEquals("Eating Out", result.single().name)
        assertEquals("restaurants, cafes", result.single().description)
    }

    @Test
    fun `findActive maps id name description across multiple rows`() {
        seedCategory("Eating Out", "restaurants", active = true)
        seedCategory("Transport", "trains, buses", active = true)

        val byName = query { repo.findActive() }.associateBy { it.name }

        assertEquals(2, byName.size)
        assertEquals("restaurants", byName["Eating Out"]?.description)
        assertEquals("trains, buses", byName["Transport"]?.description)
    }

    @Test
    fun `findActive returns empty when nothing is eligible`() {
        seedCategory("Retired", "gone", active = false)
        seedCategory("Mortgage", "fixed monthly obligation", active = true, slackLoggable = false)

        assertEquals(emptyList(), query { repo.findActive() })
    }

    @Test
    fun `create persists a new budget line`() {
        val id = query {
            repo.create(
                name = "Monthly Groceries",
                description = "Supermarket and pantry spending",
                period = "MONTHLY",
                amount = 75_000L,
                active = true,
                slackLoggable = false,
            )
        }

        val row = query { Category.selectAll().single() }

        assertEquals(id, row[Category.id])
        assertEquals("Monthly Groceries", row[Category.name])
        assertEquals("Supermarket and pantry spending", row[Category.description])
        assertEquals("MONTHLY", row[Category.period])
        assertEquals(75_000L, row[Category.amount])
        assertEquals(true, row[Category.active])
        assertEquals(false, row[Category.slackLoggable])
    }

    @Test
    fun `create rejects a duplicate name`() {
        query {
            repo.create(
                name = "Transport",
                description = "Trains and buses",
                period = "MONTHLY",
                amount = 20_000L,
                active = true,
                slackLoggable = true,
            )
        }

        assertFailsWith<ExposedSQLException> {
            query {
                repo.create(
                    name = "Transport",
                    description = "Duplicate budget line",
                    period = "MONTHLY",
                    amount = 30_000L,
                    active = true,
                    slackLoggable = false,
                )
            }
        }

        assertEquals(1L, query { Category.selectAll().count() })
    }

    @Test
    fun `update changes the row and is idempotent on repeat calls`() {
        val id = seedCategory(
            name = "Groceries",
            description = "Old description",
            active = true,
            slackLoggable = true,
        )

        fun update(): Boolean = query {
            repo.update(
                id = id,
                name = "Monthly Groceries",
                description = "Supermarket and pantry spending",
                period = "WEEKLY",
                amount = 80_000L,
                active = false,
                slackLoggable = false,
            )
        }

        assertTrue(update())
        assertTrue(update())

        val row = query { Category.selectAll().single() }
        assertEquals(id, row[Category.id])
        assertEquals("Monthly Groceries", row[Category.name])
        assertEquals("Supermarket and pantry spending", row[Category.description])
        assertEquals("WEEKLY", row[Category.period])
        assertEquals(80_000L, row[Category.amount])
        assertEquals(false, row[Category.active])
        assertEquals(false, row[Category.slackLoggable])
    }

    @Test
    fun `update on unknown id returns false`() {
        val updated = query {
            repo.update(
                id = UUID.randomUUID(),
                name = "Unknown",
                description = "Does not exist",
                period = "MONTHLY",
                amount = 10_000L,
                active = true,
                slackLoggable = true,
            )
        }

        assertFalse(updated)
        assertEquals(0L, query { Category.selectAll().count() })
    }
}
