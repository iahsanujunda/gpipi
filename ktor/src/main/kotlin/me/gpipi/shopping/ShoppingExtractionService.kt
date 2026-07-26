package me.gpipi.shopping

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.gpipi.ai.ExtractionSpec
import me.gpipi.ai.OpenRouterClient
import me.gpipi.ai.extractStructured

class ShoppingExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal const val SYSTEM_PROMPT = """
You extract shopping-list items from a short message. The message is already just the item
payload, the "list add" prefix has been stripped, e.g. you receive "milk and eggs" not
"list add milk and eggs".

The message may name several items at once. Return one array entry per item.

Return JSON matching the provided schema.

Rules:
- item: A concise name for the thing itself, preserving the user's language and script.
  Do not translate it. Move quantity and other qualifiers into quantity/note.
- quantity: The amount or count, as free text, when stated. "1kg", "2 packs", "a few". Otherwise null.
- note: Any other qualifier not captured by quantity — brand, size, occasion, purpose.
  Otherwise null.
  Example: "diapers size L for night" → item "diapers", quantity null, note "size L, for night".
- Messages may be in Indonesian, English, Japanese, or a mixture (e.g. "susu", "pasir kucing", "牛乳", "卵").
  Extract the item/quantity/note distinction the same way regardless of language.
"""

class ShoppingExtractionService(
    private val orClient: OpenRouterClient,
) {
    fun buildSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("items") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("item")     { put("type", "string") }
                        putJsonObject("quantity") { putJsonArray("type") { add("string"); add("null") } }
                        putJsonObject("note")     { putJsonArray("type") { add("string"); add("null") } }
                    }
                    putJsonArray("required") {
                        add("item")
                        add("quantity")
                        add("note")
                    }
                    put("additionalProperties", false)
                }
            }
        }
        putJsonArray("required") { add("items") }
        put("additionalProperties", false)
    }

    suspend fun extract(text: String): ShoppingExtraction {
        val extraction = orClient.extractStructured(
            spec = ExtractionSpec(
                name = "shopping_extraction",
                systemPrompt = SYSTEM_PROMPT,
                schema = buildSchema(),
                deserializer = ShoppingExtraction.serializer(),
            ),
            userMessage = text,
            wrap = ::ShoppingExtractionException,
        ).value

        if (extraction.items.isEmpty()) {
            throw ShoppingExtractionException("Extraction returned no items")
        }
        if (extraction.items.any { it.item.isBlank() }) {
            throw ShoppingExtractionException("Extraction returned a blank item name")
        }

        return extraction
    }
}
