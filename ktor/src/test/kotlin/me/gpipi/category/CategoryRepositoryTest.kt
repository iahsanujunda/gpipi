package me.gpipi.category

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import me.gpipi.config.dbQuery
import me.gpipi.generated.db.base.public1.Category
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.jdbc.insert

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
}
