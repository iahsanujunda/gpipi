package me.gpipi.slack

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.gpipi.category.CategoryRow

class SlackCardsTest {

    private val conbini = CategoryRow(UUID.randomUUID(), "Convenience Store", "konbini")
    private val groceries = CategoryRow(UUID.randomUUID(), "Monthly Groceries", "supermarket")
    private val categories = listOf(conbini, groceries)
    private val draftId = UUID.randomUUID()

    private fun card(merchant: String? = "conbini") =
        expenseCard(draftId, amount = 510, merchant = merchant,
            predictedCategoryId = conbini.id, categories = categories)

    private fun checkboxBlocks(card: JsonArray): List<JsonObject> =
        card.mapNotNull { element ->
            val block = element.jsonObject
            val firstElement = block["elements"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: return@mapNotNull null
            block.takeIf {
                firstElement["type"]?.jsonPrimitive?.content == "checkboxes"
            }
        }

    private fun checkboxElements(card: JsonArray): List<JsonObject> =
        checkboxBlocks(card).map { it["elements"]!!.jsonArray.single().jsonObject }

    private fun options(card: JsonArray) =
        checkboxElements(card).flatMap { it["options"]!!.jsonArray }

    @Test
    fun `shopping card renders one checkbox group for three items`() {
        val items = List(3) { index ->
            ShoppingListItem(UUID(0, index + 1L), "item ${index + 1}")
        }

        val groups = checkboxElements(shoppingListCard(items))

        assertEquals(1, groups.size)
        assertEquals(3, groups.single()["options"]!!.jsonArray.size)
        assertEquals(
            SHOPPING_MARK_BOUGHT_ACTION_ID,
            groups.single()["action_id"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `shopping card chunks twelve items into groups of ten and two`() {
        val items = List(12) { index ->
            ShoppingListItem(UUID(0, index + 1L), "item ${index + 1}")
        }

        val card = shoppingListCard(items)
        val blocks = checkboxBlocks(card)
        val groups = checkboxElements(card)

        assertEquals(listOf(10, 2), groups.map { it["options"]!!.jsonArray.size })
        assertEquals(2, blocks.map { it["block_id"]!!.jsonPrimitive.content }.distinct().size)
        assertTrue(
            groups.all {
                it["action_id"]!!.jsonPrimitive.content == SHOPPING_MARK_BOUGHT_ACTION_ID
            },
        )
    }

    @Test
    fun `shopping options carry item UUIDs`() {
        val items = listOf(
            ShoppingListItem(UUID(0, 1), "milk"),
            ShoppingListItem(UUID(0, 2), "eggs"),
        )

        val values = options(shoppingListCard(items))
            .map { it.jsonObject["value"]!!.jsonPrimitive.content }

        assertEquals(items.map { it.id.toString() }, values)
    }

    @Test
    fun `shopping labels include nonblank quantity and note`() {
        val items = listOf(
            ShoppingListItem(UUID(0, 1), "milk"),
            ShoppingListItem(UUID(0, 2), "ground beef", quantity = "1kg"),
            ShoppingListItem(UUID(0, 3), "diapers", note = "size L, for night"),
            ShoppingListItem(
                UUID(0, 4),
                "milk",
                quantity = "2 cartons",
                note = "low fat",
            ),
            ShoppingListItem(UUID(0, 5), "bread", quantity = " ", note = ""),
        )

        val labels = options(shoppingListCard(items)).map {
            it.jsonObject["text"]!!.jsonObject["text"]!!.jsonPrimitive.content
        }

        assertEquals(
            listOf(
                "milk",
                "ground beef · 1kg",
                "diapers · size L, for night",
                "milk · 2 cartons · low fat",
                "bread",
            ),
            labels,
        )
    }

    @Test
    fun `shopping option labels respect the Slack limit`() {
        val card = shoppingListCard(
            listOf(ShoppingListItem(UUID(0, 1), "x".repeat(100))),
        )

        val label = options(card).single()
            .jsonObject["text"]!!.jsonObject["text"]!!.jsonPrimitive.content

        assertEquals(SLACK_OPTION_TEXT_MAX, label.length)
        assertTrue(label.endsWith("…"))
    }

    @Test
    fun `shopping card renders feedback and Undo for a mutation`() {
        val mutationId = UUID.randomUUID()
        val card = shoppingListCard(
            items = listOf(ShoppingListItem(UUID(0, 1), "eggs")),
            feedback = "Milk marked bought ✓",
            undoMutationId = mutationId,
        )

        val sectionTexts = card.mapNotNull {
            it.jsonObject["text"]?.jsonObject?.get("text")?.jsonPrimitive?.content
        }
        val undo = card.mapNotNull {
            it.jsonObject["elements"]?.jsonArray?.singleOrNull()?.jsonObject
        }.single { it["action_id"]?.jsonPrimitive?.content == UNDO_SHOPPING_ACTION_ID }

        assertTrue("Milk marked bought ✓" in sectionTexts)
        assertEquals(mutationId.toString(), undo["value"]!!.jsonPrimitive.content)
        assertEquals("Undo", undo["text"]!!.jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `shopping card omits Undo when there is no mutation`() {
        val card = shoppingListCard(
            items = listOf(ShoppingListItem(UUID(0, 1), "milk")),
            feedback = "Already on the list",
        )

        val actionIds = card.flatMap {
            it.jsonObject["elements"]?.jsonArray.orEmpty()
        }.mapNotNull {
            it.jsonObject["action_id"]?.jsonPrimitive?.content
        }

        assertTrue(UNDO_SHOPPING_ACTION_ID !in actionIds)
    }

    @Test
    fun `summary section shows amount and merchant`() {
        val section = card()[0].jsonObject
        assertEquals("section", section["type"]!!.jsonPrimitive.content)
        val text = section["text"]!!.jsonObject
        assertEquals("mrkdwn", text["type"]!!.jsonPrimitive.content)
        assertEquals("*¥510* · conbini", text["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `summary omits the middot when merchant is null`() {
        val text = card(merchant = null)[0].jsonObject["text"]!!.jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("*¥510*", text)
    }

    @Test
    fun `actions block carries the id the route reads`() {
        val actions = card()[1].jsonObject
        assertEquals("actions", actions["type"]!!.jsonPrimitive.content)
        assertEquals(CARD_BLOCK_ID, actions["block_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `dropdown has one option per category, pre-filled with the prediction`() {
        val select = card()[1].jsonObject["elements"]!!.jsonArray[0].jsonObject
        assertEquals("static_select", select["type"]!!.jsonPrimitive.content)
        assertEquals(CATEGORY_ACTION_ID, select["action_id"]!!.jsonPrimitive.content)

        val options = select["options"]!!.jsonArray
        assertEquals(2, options.size)
        assertEquals(
            categories.map { it.name },
            options.map { it.jsonObject["text"]!!.jsonObject["text"]!!.jsonPrimitive.content },
        )
        assertEquals(
            categories.map { it.id.toString() },
            options.map { it.jsonObject["value"]!!.jsonPrimitive.content },
        )

        // initial_option is the predicted category (conbini)
        val initial = select["initial_option"]!!.jsonObject
        assertEquals("Convenience Store", initial["text"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(conbini.id.toString(), initial["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `confirm button carries the draft id and the action id the route reads`() {
        val button = card()[1].jsonObject["elements"]!!.jsonArray[1].jsonObject
        assertEquals("button", button["type"]!!.jsonPrimitive.content)
        assertEquals(CONFIRM_ACTION_ID, button["action_id"]!!.jsonPrimitive.content)
        assertEquals("primary", button["style"]!!.jsonPrimitive.content)
        assertEquals(draftId.toString(), button["value"]!!.jsonPrimitive.content)
        assertEquals("Confirm", button["text"]!!.jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `open budget card contains a single private link button`() {
        val actions = openBudgetCard("https://budget.test/enter#raw-nonce").single().jsonObject
        val button = actions["elements"]!!.jsonArray.single().jsonObject

        assertEquals("actions", actions["type"]!!.jsonPrimitive.content)
        assertEquals(OPEN_BUDGET_BLOCK_ID, actions["block_id"]!!.jsonPrimitive.content)
        assertEquals("button", button["type"]!!.jsonPrimitive.content)
        assertEquals(OPEN_BUDGET_ACTION_ID, button["action_id"]!!.jsonPrimitive.content)
        assertEquals("primary", button["style"]!!.jsonPrimitive.content)
        assertEquals(
            "https://budget.test/enter#raw-nonce",
            button["url"]!!.jsonPrimitive.content,
        )
        assertEquals("Open budget", button["text"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(
            "Open your household budget",
            button["accessibility_label"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `the block_id and action_ids match the interaction route constants`() {
        // Guard against drift: the route hardcodes these strings when reading the payload.
        assertTrue(CARD_BLOCK_ID == "expense_confirm")
        assertTrue(CATEGORY_ACTION_ID == "category_select")
        assertTrue(CONFIRM_ACTION_ID == "confirm_expense")
    }
}
