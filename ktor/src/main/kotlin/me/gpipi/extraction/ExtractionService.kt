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
- category: choose exactly one from the list below. Decide in this priority order:
  1. Explicit intent wins — highest priority. If the user names a category or states a purpose
     that matches one (by category name, e.g. "... for leisure", or by a purpose that fits a
     category's description, e.g. "cat vet", "potluck with friends"), use that category even when
     the merchant or the item alone would normally imply a different one.
       "5000 ramen for leisure" → Leisure   (the user said "leisure"; ramen alone would be eating out)
       "5000 for cat vet"        → Sapi mupi (a vet visit for the cat matches that category)
  2. Otherwise, infer the category from the merchant or the item bought.
       "5000 ramen" → the eating-out category · "510 at seven eleven" → the convenience-store category
  3. If it is still unclear, pick the closest fit and lower the confidence.
  Match against the category descriptions below, not just the names — the description carries the
  disambiguation signal (e.g. which category covers the cat, or vet visits).
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