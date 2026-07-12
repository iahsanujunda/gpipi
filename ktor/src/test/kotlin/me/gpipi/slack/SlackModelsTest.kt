package me.gpipi.slack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/**
 * The payload -> model mapping the interaction route relies on. Guards that a real block_actions
 * body exposes the draft id, the selected category's id AND display name, and the response_url —
 * the fields handleConfirm needs. The handler test bypasses this by calling handleConfirm directly.
 */
class SlackModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `block_actions payload exposes draft id, category id+name, and response_url`() {
        val payload = """
            {"type":"block_actions",
             "response_url":"https://hooks.slack.test/r",
             "actions":[{"type":"button","action_id":"confirm_expense","value":"draft-123"}],
             "state":{"values":{"expense_confirm":{"category_select":{"selected_option":{
                 "value":"cat-456","text":{"type":"plain_text","text":"Monthly Groceries"}}}}}}}
        """.trimIndent()

        val i = json.decodeFromString<Interaction>(payload)

        assertEquals("block_actions", i.type)
        assertEquals("https://hooks.slack.test/r", i.responseUrl)

        val action = i.actions.single()
        assertEquals("confirm_expense", action.actionId)
        assertEquals("draft-123", action.value)

        val selected = i.state!!.values.values.first()["category_select"]!!.selectedOption!!
        assertEquals("cat-456", selected.value)
        assertEquals("Monthly Groceries", selected.text!!.text)
    }
}
