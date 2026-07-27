package me.gpipi.slack

import java.util.UUID

// THROWAWAY prototype (phase3.md "Getting started" step 1): renders hardcoded checkbox cards
// so we can validate Slack's interaction shape — chunking at 10, rapid taps, weak connectivity,
// stale cards, and the post-action Undo affordance — before building any schema around it.
// Delete this whole file once a genuine checkbox payload has been captured as a route fixture.
//
// `list proto`    → 3-item card (single checkbox group)
// `list proto 12` → 12-item card (forces a 10 + 2 split across two groups)
// `list proto done` → feedback + fake Undo affordance
class ListPrototypeCommand(private val slack: SlackClient) : SlackCommand {
    override fun matches(body: String): Boolean =
        body.equals("list proto", ignoreCase = true) ||
            body.equals("list proto 12", ignoreCase = true) ||
            body.equals("list proto done", ignoreCase = true)

    override suspend fun handle(msg: SlackMessage, inboundMessageId: UUID): SlackCommandOutcome {
        val card = when {
            msg.body.equals("list proto 12", ignoreCase = true) ->
                shoppingListCard(TWELVE_ITEMS)
            msg.body.equals("list proto done", ignoreCase = true) ->
                shoppingListCard(
                    items = THREE_ITEMS.drop(1),
                    feedback = "Milk marked bought ✓",
                    undoMutationId = PROTOTYPE_MUTATION_ID,
                )
            else -> shoppingListCard(THREE_ITEMS)
        }
        slack.postCard(msg.channelId, "Shopping list", card)
        return SlackCommandOutcome.Completed
    }
}

// Stable literal ids: re-renders must reference the same rows, or a captured payload points at
// items that no longer exist on the next draw and fake Undo becomes impossible to reason about.
private fun item(
    id: String,
    name: String,
    quantity: String? = null,
    note: String? = null,
) = ShoppingListItem(UUID.fromString(id), name, quantity, note)

private val PROTOTYPE_MUTATION_ID =
    UUID.fromString("00000000-0000-0000-0000-0000000000ff")

private val THREE_ITEMS = listOf(
    item("00000000-0000-0000-0000-000000000001", "milk"),
    item("00000000-0000-0000-0000-000000000002", "ground beef", "1kg"),
    item(
        id = "00000000-0000-0000-0000-000000000003",
        name = "diapers",
        note = "size L, for night",
    ),
)

private val TWELVE_ITEMS = listOf(
    item("00000000-0000-0000-0000-0000000000a1", "milk"),
    item("00000000-0000-0000-0000-0000000000a2", "ground beef", "1kg"),
    item(
        id = "00000000-0000-0000-0000-0000000000a3",
        name = "diapers",
        note = "size L, for night",
    ),
    item("00000000-0000-0000-0000-0000000000a4", "eggs", "2 packs"),
    item("00000000-0000-0000-0000-0000000000a5", "bread"),
    item("00000000-0000-0000-0000-0000000000a6", "butter"),
    item("00000000-0000-0000-0000-0000000000a7", "rice", "5kg"),
    item("00000000-0000-0000-0000-0000000000a8", "pasir kucing"),
    item("00000000-0000-0000-0000-0000000000a9", "bananas"),
    item("00000000-0000-0000-0000-0000000000aa", "coffee beans"),
    item("00000000-0000-0000-0000-0000000000ab", "dish soap"),
    item("00000000-0000-0000-0000-0000000000ac", "onions", "3"),
)
