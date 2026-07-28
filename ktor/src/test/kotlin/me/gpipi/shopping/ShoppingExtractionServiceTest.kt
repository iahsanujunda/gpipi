package me.gpipi.shopping

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.gpipi.ai.AiException
import me.gpipi.ai.ChatResult

class ShoppingExtractionServiceTest {
    private val orClient = mockk<me.gpipi.ai.OpenRouterClient>()
    private val testModel = "resolved/model-version"
    private fun service() = ShoppingExtractionService(orClient)

    private fun okResult(itemsJson: String) = ChatResult(
        content = """{"items":$itemsJson}""",
        model = testModel,
    )

    // --- pure builder ---

    @Test
    fun `buildSchema declares an items array`() {
        val itemsProp = service().buildSchema()["properties"]!!.jsonObject["items"]!!.jsonObject
        assertEquals("array", itemsProp["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildSchema requires nullable item properties in strict output`() {
        val itemSchema = service().buildSchema()["properties"]!!.jsonObject["items"]!!
            .jsonObject["items"]!!.jsonObject

        val required = itemSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }

        assertEquals(listOf("item", "quantity", "note"), required)
    }

    @Test
    fun `system prompt names all three languages`() {
        assertTrue("Indonesian" in SYSTEM_PROMPT)
        assertTrue("English" in SYSTEM_PROMPT)
        assertTrue("Japanese" in SYSTEM_PROMPT)
    }

    @Test
    fun `system prompt includes the qualifier example`() {
        assertTrue("diapers size L for night" in SYSTEM_PROMPT)
    }

    // --- extract() orchestration ---

    @Test
    fun `extract decodes multiple items in original order`() {
        coEvery { orClient.chat(any(), any(), any(), any()) } returns okResult(
            """[{"item":"milk","quantity":"1L","note":null},{"item":"eggs","quantity":null,"note":null}]"""
        )

        val result = runBlocking { service().extract("milk and eggs") }

        assertEquals(listOf("milk", "eggs"), result.items.map { it.item })
    }

    @Test
    fun `quantity and note decode as explicit nulls`() {
        coEvery { orClient.chat(any(), any(), any(), any()) } returns okResult(
            """[{"item":"eggs","quantity":null,"note":null}]"""
        )

        val result = runBlocking { service().extract("eggs") }

        assertNull(result.items.single().quantity)
        assertNull(result.items.single().note)
    }

    @Test
    fun `extract sends the shopping schema name to OpenRouter`() {
        coEvery { orClient.chat(any(), any(), any(), any()) } returns okResult(
            """[{"item":"milk","quantity":null,"note":null}]"""
        )

        runBlocking { service().extract("milk") }

        coVerify {
            orClient.chat(
                userMessage = "milk",
                systemPrompt = SYSTEM_PROMPT,
                schema = any(),
                schemaName = "shopping_extraction",
            )
        }
    }

    @Test
    fun `extract rejects an empty item array`() {
        coEvery { orClient.chat(any(), any(), any(), any()) } returns okResult("[]")

        assertFailsWith<ShoppingExtractionException> {
            runBlocking { service().extract("uh") }
        }
    }

    @Test
    fun `extract rejects a whitespace-only item name`() {
        coEvery { orClient.chat(any(), any(), any(), any()) } returns okResult(
            """[{"item":"   "}]"""
        )

        assertFailsWith<ShoppingExtractionException> {
            runBlocking { service().extract("???") }
        }
    }

    @Test
    fun `extract wraps an AiException as ShoppingExtractionException`() {
        coEvery { orClient.chat(any(), any(), any(), any()) } throws AiException("network down")

        assertFailsWith<ShoppingExtractionException> {
            runBlocking { service().extract("milk") }
        }
    }

    @Test
    fun `extract wraps malformed JSON as ShoppingExtractionException`() {
        coEvery { orClient.chat(any(), any(), any(), any()) } returns ChatResult(
            content = "not json at all { sensitive shopping text",
            model = testModel,
        )

        val failure = assertFailsWith<ShoppingExtractionException> {
            runBlocking { service().extract("milk") }
        }
        assertEquals("Extraction didn't match schema", failure.message)
    }
}
