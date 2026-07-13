package me.gpipi.slack

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
    fun `the block_id and action_ids match the interaction route constants`() {
        // Guard against drift: the route hardcodes these strings when reading the payload.
        assertTrue(CARD_BLOCK_ID == "expense_confirm")
        assertTrue(CATEGORY_ACTION_ID == "category_select")
        assertTrue(CONFIRM_ACTION_ID == "confirm_expense")
    }
}
