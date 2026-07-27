package me.gpipi.slack

import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.gpipi.category.CategoryRow
import me.gpipi.shopping.ShoppingItemText

// Block Kit identifiers — MUST match what slackInteractionRoutes reads out of the payload,
// or every Confirm is silently ignored.
const val CARD_BLOCK_ID = "expense_confirm"
const val CATEGORY_ACTION_ID = "category_select"
const val CONFIRM_ACTION_ID = "confirm_expense"
const val OPEN_BUDGET_BLOCK_ID = "open_budget"
const val OPEN_BUDGET_ACTION_ID = "open_budget_link"
const val SLACK_CHECKBOX_GROUP_MAX = 10   // Slack hard limit per checkboxes element
const val SLACK_OPTION_TEXT_MAX = 75      // Slack option-text limit
const val SHOPPING_MARK_BOUGHT_ACTION_ID = "shopping_mark_bought"
const val UNDO_SHOPPING_ACTION_ID = "undo_shopping_mutation"
const val CONFIRM_SHOPPING_ADD_ACTION_ID = "confirm_shopping_add"
const val CANCEL_SHOPPING_ADD_ACTION_ID = "cancel_shopping_add"

data class ShoppingListItem(
    val id: UUID,
    val item: String,
    val quantity: String? = null,
    val note: String? = null,
)

/** "milk" or "ground beef · 1kg · lean", truncated to Slack's 75-char option limit. */
private fun ShoppingListItem.label(): String {
    val full = shoppingLabel(item, quantity, note)

    return if (full.length <= SLACK_OPTION_TEXT_MAX) full
    else full.take(SLACK_OPTION_TEXT_MAX - 1).trimEnd() + "…"
}

private fun shoppingLabel(item: String, quantity: String?, note: String?): String =
    listOfNotNull(
        item,
        quantity?.takeIf(String::isNotBlank),
        note?.takeIf(String::isNotBlank),
    ).joinToString(" · ")

private fun mrkdwnEscape(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun JsonObjectBuilder.mrkdwnSection(text: String) {
    put("type", "section")
    putJsonObject("text") {
        put("type", "mrkdwn")
        put("text", text)
    }
}

/**
 * The pending-items list card. Each checkbox `value` is the row UUID, so a Mark Bought
 * interaction carries identity with nothing to match. More than 10 items split into
 * separate checkbox groups (Slack caps one element at 10).
 */
fun shoppingListCard(
    items: List<ShoppingListItem>,
    feedback: String? = null,
    undoMutationId: UUID? = null,
): JsonArray = buildJsonArray {
    addJsonObject { mrkdwnSection("*Shopping list*") }

    if (items.isEmpty()) {
        addJsonObject { mrkdwnSection("Nothing on the list yet.") }
    }

    items.chunked(SLACK_CHECKBOX_GROUP_MAX).forEachIndexed { groupIndex, group ->
        addJsonObject {
            put("type", "actions")
            put("block_id", "shopping_list_$groupIndex")
            putJsonArray("elements") {
                addJsonObject {
                    put("type", "checkboxes")
                    put("action_id", SHOPPING_MARK_BOUGHT_ACTION_ID)
                    putJsonArray("options") {
                        group.forEach { item ->
                            addJsonObject {
                                putJsonObject("text") {
                                    put("type", "plain_text")
                                    put("text", item.label())
                                }
                                put("value", item.id.toString())   // 36 chars, safely under limit
                            }
                        }
                    }
                }
            }
        }
    }

    feedback?.let { addJsonObject { mrkdwnSection(mrkdwnEscape(it)) } }

    undoMutationId?.let { mutationId ->
        addJsonObject {
            put("type", "actions")
            put("block_id", "shopping_undo")
            putJsonArray("elements") {
                addJsonObject {
                    put("type", "button")
                    put("action_id", UNDO_SHOPPING_ACTION_ID)
                    put("value", mutationId.toString())
                    putJsonObject("text") {
                        put("type", "plain_text")
                        put("text", "Undo")
                    }
                }
            }
        }
    }
}

fun shoppingAddConfirmationCard(
    draftId: UUID,
    items: List<ShoppingItemText>,
): JsonArray = buildJsonArray {
    val lines = items.joinToString("\n") {
        "• ${mrkdwnEscape(shoppingLabel(it.item, it.quantity, it.note))}"
    }
    addJsonObject {
        mrkdwnSection("*Add to shopping list?*\n$lines")
    }
    addJsonObject {
        put("type", "actions")
        put("block_id", "shopping_add_confirm")
        putJsonArray("elements") {
            addJsonObject {
                put("type", "button")
                put("action_id", CONFIRM_SHOPPING_ADD_ACTION_ID)
                put("style", "primary")
                put("value", draftId.toString())
                putJsonObject("text") {
                    put("type", "plain_text")
                    put("text", "Add items")
                }
            }
            addJsonObject {
                put("type", "button")
                put("action_id", CANCEL_SHOPPING_ADD_ACTION_ID)
                put("value", draftId.toString())
                putJsonObject("text") {
                    put("type", "plain_text")
                    put("text", "Cancel")
                }
            }
        }
    }
}

fun shoppingActionResultCard(
    feedback: String,
    undoMutationId: UUID? = null,
): JsonArray = buildJsonArray {
    addJsonObject { mrkdwnSection(mrkdwnEscape(feedback)) }
    undoMutationId?.let { mutationId ->
        addJsonObject {
            put("type", "actions")
            put("block_id", "shopping_undo")
            putJsonArray("elements") {
                addJsonObject {
                    put("type", "button")
                    put("action_id", UNDO_SHOPPING_ACTION_ID)
                    put("value", mutationId.toString())
                    putJsonObject("text") {
                        put("type", "plain_text")
                        put("text", "Undo")
                    }
                }
            }
        }
    }
}

/** One `{ text, value }` option for the category dropdown: name shown, id carried. */
private fun categoryOption(category: CategoryRow): JsonObject = buildJsonObject {
    putJsonObject("text") {
        put("type", "plain_text")
        put("text", category.name)
    }
    put("value", category.id.toString())
}

fun openBudgetCard(url: String): JsonArray = buildJsonArray {
    addJsonObject {
        put("type", "actions")
        put("block_id", OPEN_BUDGET_BLOCK_ID)
        putJsonArray("elements") {
            addJsonObject {
                put("type", "button")
                put("action_id", OPEN_BUDGET_ACTION_ID)
                put("style", "primary")
                put("url", url)
                put("accessibility_label", "Open your household budget")
                putJsonObject("text") {
                    put("type", "plain_text")
                    put("text", "Open budget")
                }
            }
        }
    }
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
