package me.gpipi.extraction

import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.gpipi.ai.AiException
import me.gpipi.ai.OpenRouterClient
import me.gpipi.category.CategoryRepository
import me.gpipi.category.CategoryRow
import me.gpipi.config.dbQuery
import me.gpipi.generated.db.base.public1.BudgetEnvelope
import me.gpipi.generated.db.base.public1.Category
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.jdbc.insert

/**
 * Real Testcontainers DB (extract() opens a transaction via the cache loader), mocked
 * OpenRouterClient so nothing hits the network. The pure builder functions need neither.
 */
class ExtractionServiceTest : PersistenceTest() {
    private val orClient = mockk<OpenRouterClient>()

    private fun <T> query(block: () -> T): T = runBlocking { dbQuery(db) { block() } }
    private fun service() = ExtractionService(db, CategoryRepository(), orClient)

    private fun seedCategory(name: String, description: String = "desc"): UUID = query {
        val envId = UUID.randomUUID()
        BudgetEnvelope.insert {
            it[BudgetEnvelope.id] = envId
            it[BudgetEnvelope.name] = "Env $name"
            it[BudgetEnvelope.period] = "MONTHLY"
            it[BudgetEnvelope.amount] = 50000L
        }
        val catId = UUID.randomUUID()
        Category.insert {
            it[Category.id] = catId
            it[Category.envelopeId] = envId
            it[Category.name] = name
            it[Category.description] = description
        }
        catId
    }

    private fun okJson(category: String) =
        """{"amount":1500,"currency":"JPY","merchant":null,"category":"$category","confidence":0.9,"note":null}"""

    // --- pure builders (no DB, no network) ---

    private val cats = listOf(
        CategoryRow(UUID.randomUUID(), "Eating Out", "restaurants, cafes"),
        CategoryRow(UUID.randomUUID(), "Transport", "trains, buses"),
    )

    @Test
    fun `buildSystemPrompt injects the category list and clears the placeholder`() {
        val prompt = service().buildSystemPrompt(cats)

        assertTrue("- Eating Out — restaurants, cafes" in prompt)
        assertTrue("- Transport — trains, buses" in prompt)
        assertTrue("{{CATEGORIES}}" !in prompt)
    }

    @Test
    fun `buildExtractionSchema builds a category enum of the active names`() {
        val schema = service().buildExtractionSchema(cats)

        val enum = schema["properties"]!!.jsonObject["category"]!!
            .jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }

        assertEquals(listOf("Eating Out", "Transport"), enum)
    }

    // --- extract() orchestration ---

    @Test
    fun `extract parses the content and resolves the category_id`() {
        val eatingOutId = seedCategory("Eating Out")
        coEvery { orClient.chat(any(), any(), any()) } returns okJson("Eating Out")

        val (x, categoryId) = runBlocking { service().extract("1500 for ramen") }

        assertEquals(1500L, x.amount)
        assertEquals("Eating Out", x.category)
        assertEquals(eatingOutId, categoryId)
    }

    @Test
    fun `extract wraps an AiException as ExtractionException`() {
        seedCategory("Eating Out")
        coEvery { orClient.chat(any(), any(), any()) } throws AiException("network down")

        assertFailsWith<ExtractionException> {
            runBlocking { service().extract("1500 for ramen") }
        }
    }

    @Test
    fun `extract wraps malformed JSON as ExtractionException`() {
        seedCategory("Eating Out")
        coEvery { orClient.chat(any(), any(), any()) } returns "not json at all {"

        assertFailsWith<ExtractionException> {
            runBlocking { service().extract("1500 for ramen") }
        }
    }

    @Test
    fun `extract caches the category list across calls`() {
        seedCategory("Eating Out")
        val spyRepo = spyk(CategoryRepository())
        val svc = ExtractionService(db, spyRepo, orClient)
        coEvery { orClient.chat(any(), any(), any()) } returns okJson("Eating Out")

        runBlocking { svc.extract("1500 ramen") }
        runBlocking { svc.extract("800 coffee") }

        verify(exactly = 1) { spyRepo.findActive() }   // second call is a cache hit
    }
}
