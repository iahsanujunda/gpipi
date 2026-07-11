package me.gpipi.category

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import me.gpipi.config.dbQuery
import me.gpipi.generated.db.base.public1.BudgetEnvelope
import me.gpipi.generated.db.base.public1.Category
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.jdbc.insert

class CategoryRepositoryTest : PersistenceTest() {
    private val repo = CategoryRepository()

    private fun <T> query(block: () -> T): T = runBlocking { dbQuery(db) { block() } }

    private fun seedEnvelope(): UUID = query {
        val id = UUID.randomUUID()
        BudgetEnvelope.insert {
            it[BudgetEnvelope.id] = id
            it[BudgetEnvelope.name] = "Test Envelope"
            it[BudgetEnvelope.period] = "MONTHLY"
            it[BudgetEnvelope.amount] = 50000L
        }
        id
    }

    private fun seedCategory(envId: UUID, name: String, description: String, active: Boolean): UUID = query {
        val id = UUID.randomUUID()
        Category.insert {
            it[Category.id] = id
            it[Category.envelopeId] = envId
            it[Category.name] = name
            it[Category.description] = description
            it[Category.active] = active
        }
        id
    }

    @Test
    fun `findActive returns only active categories`() {
        val envId = seedEnvelope()
        val activeId = seedCategory(envId, "Eating Out", "restaurants, cafes", active = true)
        seedCategory(envId, "Retired", "no longer used", active = false)

        val result = query { repo.findActive() }

        assertEquals(1, result.size)
        assertEquals(activeId, result.single().id)
        assertEquals("Eating Out", result.single().name)
        assertEquals("restaurants, cafes", result.single().description)
    }

    @Test
    fun `findActive maps id name description across multiple rows`() {
        val envId = seedEnvelope()
        seedCategory(envId, "Eating Out", "restaurants", active = true)
        seedCategory(envId, "Transport", "trains, buses", active = true)

        val byName = query { repo.findActive() }.associateBy { it.name }

        assertEquals(2, byName.size)
        assertEquals("restaurants", byName["Eating Out"]?.description)
        assertEquals("trains, buses", byName["Transport"]?.description)
    }

    @Test
    fun `findActive returns empty when nothing is active`() {
        val envId = seedEnvelope()
        seedCategory(envId, "Retired", "gone", active = false)

        assertEquals(emptyList(), query { repo.findActive() })
    }
}
