package me.gpipi.slack

import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.gpipi.category.CategoryRow

// Block Kit identifiers — MUST match what slackInteractionRoutes reads out of the payload,
// or every Confirm is silently ignored.
const val CARD_BLOCK_ID = "expense_confirm"
const val CATEGORY_ACTION_ID = "category_select"
const val CONFIRM_ACTION_ID = "confirm_expense"

/** One `{ text, value }` option for the category dropdown: name shown, id carried. */
private fun categoryOption(category: CategoryRow): JsonObject = buildJsonObject {
    putJsonObject("text") {
        put("type", "plain_text")
        put("text", category.name)
    }
    put("value", category.id.toString())
}

/**
 * The editable expense card: a summary line, a category dropdown pre-filled with the model's
 * prediction, and a Confirm button carrying the draft id. Changing the dropdown or tapping
 * Confirm both POST to /slack/interactions.
 */
fun expenseCard(
    draftId: UUID,
    amount: Long,
    merchant: String?,
    predictedCategoryId: UUID,
    categories: List<CategoryRow>,
): JsonArray {
    val predicted = categories.first { it.id == predictedCategoryId }
    val summary = "*¥$amount*" + (merchant?.let { " · $it" } ?: "")

    return buildJsonArray {
        // Summary section: "*¥510* · conbini"
        addJsonObject {
            put("type", "section")
            putJsonObject("text") {
                put("type", "mrkdwn")
                put("text", summary)
            }
        }
        // Actions row: dropdown + Confirm
        addJsonObject {
            put("type", "actions")
            put("block_id", CARD_BLOCK_ID)
            putJsonArray("elements") {
                addJsonObject {
                    put("type", "static_select")
                    put("action_id", CATEGORY_ACTION_ID)
                    put("initial_option", categoryOption(predicted))
                    putJsonArray("options") {
                        categories.forEach { add(categoryOption(it)) }
                    }
                }
                addJsonObject {
                    put("type", "button")
                    put("action_id", CONFIRM_ACTION_ID)
                    put("style", "primary")
                    put("value", draftId.toString())
                    putJsonObject("text") {
                        put("type", "plain_text")
                        put("text", "Confirm")
                    }
                }
            }
        }
    }
}
