package me.gpipi.extraction

import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.gpipi.ai.AiException
import me.gpipi.ai.ChatResult
import me.gpipi.ai.OpenRouterClient
import me.gpipi.category.ActiveCategoryCatalog
import me.gpipi.category.BudgetMutationResult
import me.gpipi.category.BudgetService
import me.gpipi.category.CategoryRepository
import me.gpipi.category.CategoryRow
import me.gpipi.category.UpsertBudgetRequest
import me.gpipi.config.dbQuery
import me.gpipi.expense.ExpenseRepository
import me.gpipi.generated.db.base.public1.Category
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.jdbc.insert

/**
 * Real Testcontainers DB (extract() opens a transaction via the cache loader), mocked
 * OpenRouterClient so nothing hits the network. The pure builder functions need neither.
 */
class ExtractionServiceTest : PersistenceTest() {
    private val orClient = mockk<OpenRouterClient>()
    private val testModel = "resolved/model-version"

    private fun <T> query(block: () -> T): T = runBlocking { dbQuery(db) { block() } }
    private fun service() = ExtractionService(
        activeCategories = ActiveCategoryCatalog(db, CategoryRepository()),
        orClient = orClient,
    )

    private fun seedCategory(name: String, description: String = "desc"): UUID = query {
        val catId = UUID.randomUUID()
        Category.insert {
            it[Category.id] = catId
            it[Category.name] = name
            it[Category.description] = description
            it[Category.period] = "MONTHLY"
            it[Category.amount] = 50000L
            it[Category.slackLoggable] = true
        }
        catId
    }

    private fun okJson(category: String) =
        """{"amount":1500,"currency":"JPY","merchant":null,"category":"$category","confidence":0.9,"note":null}"""

    private fun okResult(category: String) = ChatResult(
        content = okJson(category),
        model = testModel,
    )

    private fun budgetRequest(
        name: String = "Monthly Groceries",
        description: String,
        amount: Long = 75_000L,
        slackLoggable: Boolean = true,
    ) = UpsertBudgetRequest(
        name = name,
        description = description,
        period = "MONTHLY",
        amount = amount,
        slackLoggable = slackLoggable,
    )

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
        assertTrue("Leisure" !in prompt)
        assertTrue("Sapi mupi" !in prompt)
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
        coEvery { orClient.chat(any(), any(), any()) } returns okResult("Eating Out")

        val result = runBlocking { service().extract("1500 for ramen") }

        assertEquals(1500L, result.extraction.amount)
        assertEquals("Eating Out", result.extraction.category)
        assertEquals(eatingOutId, result.categoryId)
        assertEquals(testModel, result.model)
    }

    @Test
    fun `next extraction sees edited descriptions and Slack eligibility`() = runBlocking {
        val repository = CategoryRepository()
        val catalog = ActiveCategoryCatalog(db, repository)
        val budgetService = BudgetService(
            db = db,
            categoryRepo = repository,
            expenseRepo = ExpenseRepository(),
            activeCategories = catalog,
        )
        val extractionService = ExtractionService(catalog, orClient)
        val groceries = assertIs<BudgetMutationResult.Created>(
            budgetService.create(
                budgetRequest(description = "Old categorization hint"),
            ),
        )
        assertIs<BudgetMutationResult.Created>(
            budgetService.create(
                budgetRequest(
                    name = "Transport",
                    description = "Trains and buses",
                    amount = 20_000L,
                ),
            ),
        )
        coEvery { orClient.chat(any(), any(), any()) } returnsMany listOf(
            okResult("Monthly Groceries"),
            okResult("Monthly Groceries"),
            okResult("Transport"),
        )

        extractionService.extract("warm the active-category catalog")

        assertEquals(
            BudgetMutationResult.Updated,
            budgetService.update(
                groceries.id,
                budgetRequest(description = "New categorization hint"),
            ),
        )
        extractionService.extract("first extraction after the description edit")

        assertEquals(
            BudgetMutationResult.Updated,
            budgetService.update(
                groceries.id,
                budgetRequest(
                    description = "New categorization hint",
                    slackLoggable = false,
                ),
            ),
        )
        extractionService.extract("first extraction after the eligibility edit")

        coVerifyOrder {
            orClient.chat(
                any(),
                match {
                    "- Monthly Groceries — Old categorization hint" in it
                },
                match {
                    categoryNames(it) == setOf("Monthly Groceries", "Transport")
                },
            )
            orClient.chat(
                any(),
                match {
                    "- Monthly Groceries — New categorization hint" in it &&
                        "Old categorization hint" !in it
                },
                match {
                    categoryNames(it) == setOf("Monthly Groceries", "Transport")
                },
            )
            orClient.chat(
                any(),
                match {
                    "Monthly Groceries" !in it &&
                        "- Transport — Trains and buses" in it
                },
                match {
                    categoryNames(it) == setOf("Transport")
                },
            )
        }
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
        coEvery { orClient.chat(any(), any(), any()) } returns ChatResult(
            content = "not json at all {",
            model = testModel,
        )

        assertFailsWith<ExtractionException> {
            runBlocking { service().extract("1500 for ramen") }
        }
    }

    private fun categoryNames(schema: JsonObject): Set<String> =
        schema["properties"]!!.jsonObject["category"]!!
            .jsonObject["enum"]!!.jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()
}
