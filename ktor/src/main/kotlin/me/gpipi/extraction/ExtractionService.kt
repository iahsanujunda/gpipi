package me.gpipi.extraction

import com.github.benmanes.caffeine.cache.Caffeine
import com.sksamuel.aedile.core.asCache
import com.sksamuel.aedile.core.expireAfterWrite
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.gpipi.ai.AiException
import me.gpipi.ai.OpenRouterClient
import me.gpipi.category.CategoryRepository
import me.gpipi.category.CategoryRow
import me.gpipi.config.dbQuery
import org.jetbrains.exposed.v1.jdbc.Database

private val json = Json { ignoreUnknownKeys = true }

private const val SYSTEM_PROMPT_TEMPLATE = """
You extract a single household expense from a short casual message (English, Japanese, or mixed).

Return JSON matching the schema. Rules:
- amount: integer yen. "1500jpy", "¥1,500", "1500円" all → 1500.
- merchant: the shop/place if named (keep original form: "Ito Yokado", "セブン"), else null.
- category: choose exactly one from the list below by best fit.
  - If the message explicitly names a spend type (e.g. "groceries", "lunch", "train", "medicine"),
    that stated intent decides the category — even when the merchant's usual type differs.
    Example: "groceries at seven eleven" → Monthly Groceries (the user said groceries, though
    7-Eleven is normally a convenience store).
  - Use the merchant to decide the category only when no spend type is stated.
    Example: "510 at seven eleven" → Convenience Store.
- confidence: 0-1. Lower it when the merchant is unknown or the category is a guess.
- note: anything the user added that isn't amount/merchant/category, else null.

Categories:
{{CATEGORIES}}"""

class ExtractionService(
    private val db: Database,
    private val categoryRepo: CategoryRepository,
    private val orClient: OpenRouterClient,
) {
    private val categoryCache = Caffeine
        .newBuilder()
        .expireAfterWrite(5.minutes)
        .asCache<Unit, List<CategoryRow>>()

    fun buildSystemPrompt(categories: List<CategoryRow>): String =
        SYSTEM_PROMPT_TEMPLATE.replace(
            "{{CATEGORIES}}",
            categories.joinToString("\n") { "- ${it.name} — ${it.description}" }
        )

    fun buildExtractionSchema(categories: List<CategoryRow>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("amount")     { put("type", "integer") }
            putJsonObject("currency")   { put("type", "string"); putJsonArray("enum") { add("JPY") } }
            putJsonObject("merchant")   { putJsonArray("type") { add("string"); add("null") } }
            putJsonObject("category")   { put("type", "string"); putJsonArray("enum") { categories.forEach { add(it.name) } } }
            putJsonObject("confidence") { put("type", "number"); put("minimum", 0); put("maximum", 1) }
            putJsonObject("note")       { putJsonArray("type") { add("string"); add("null") } }
        }
        putJsonArray("required") { add("amount"); add("currency"); add("category"); add("confidence") }
        put("additionalProperties", false)
    }


    suspend fun extract(text: String): ExtractionResult {
        val categories = this.categoryCache.get(Unit) { dbQuery(db) { categoryRepo.findActive() } }
        val content = try {
            orClient.chat(text, buildSystemPrompt(categories), buildExtractionSchema(categories))
        } catch (ex: AiException) {
            throw ExtractionException("AI call failed: ${ex.message}", ex)
        }
        val x = try {
            json.decodeFromString<Extraction>(content)
        } catch (ex: SerializationException) {
            throw ExtractionException("Extraction didn't match schema: ${content.take(200)}", ex)
        }
        val categoryId = categories.firstOrNull { it.name == x.category }?.id
            ?: throw ExtractionException("Model returned unknown category '${x.category}'")
        // Hand back the cached category list too — the confirmation card's dropdown needs every
        // active (id, name), and re-fetching would waste the load extract() already did.
        return ExtractionResult(x, categoryId, categories)
    }
}

/**
 * The result of an extraction: the parsed record, the resolved category id, and the active
 * category list used to build the prompt — reused by the confirmation card so it doesn't re-query.
 * Component order keeps `val (x, categoryId) = extract(...)` destructuring working.
 */
data class ExtractionResult(
    val extraction: Extraction,
    val categoryId: UUID,
    val categories: List<CategoryRow>,
)