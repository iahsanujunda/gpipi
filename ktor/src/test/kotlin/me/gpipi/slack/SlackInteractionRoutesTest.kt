package me.gpipi.slack

import io.mockk.coVerify
import io.mockk.mockk
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.gpipi.categorization.CategorizationEventRepository
import me.gpipi.config.dbQuery
import me.gpipi.expense.ExpenseDraftRepository
import me.gpipi.expense.ExpenseRepository
import me.gpipi.generated.db.base.public1.Category
import me.gpipi.generated.db.base.public1.Expense
import me.gpipi.generated.db.base.public1.ExpenseDraft
import me.gpipi.generated.db.base.public1.InboundMessage
import me.gpipi.generated.db.base.public1.ShoppingAddDraft
import me.gpipi.generated.db.base.public1.ShoppingItem
import me.gpipi.generated.db.base.public1.ShoppingMutation
import me.gpipi.inbound.InboundRepository
import me.gpipi.shopping.ShoppingDraftItemInput
import me.gpipi.shopping.ShoppingRepository
import me.gpipi.shopping.ShoppingService
import me.gpipi.support.PersistenceTest
import me.gpipi.support.insertTestCategory
import me.gpipi.support.configureWithTestDb
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Plumbing tests for /slack/interactions: signature gate + 3s ack + payload decode. The confirm
 * WRITE behavior is covered deeply by SlackInteractionHandlerTest. The dispatch test here adds one
 * end-to-end route slice proving that a complete signed, form-encoded Slack payload reaches the real
 * handler with the selected category.
 */
class SlackInteractionRoutesTest : PersistenceTest() {

    private val secret = "test-signing-secret"

    private fun ApplicationTestBuilder.bootWithSecret() = configureWithTestDb(secret)

    /** Slack sends interactivity as `application/x-www-form-urlencoded`: a single url-encoded `payload=` field. */
    private fun formBody(payloadJson: String) = "payload=" + URLEncoder.encode(payloadJson, "UTF-8")

    private fun confirmPayload(
        draftId: String = UUID.randomUUID().toString(),
        categoryId: String = UUID.randomUUID().toString(),
        categoryName: String = "Monthly Groceries",
        responseUrl: String = "https://hooks.slack.test/response",
    ) = """
        {"type":"block_actions",
         "response_url":"$responseUrl",
         "actions":[{"type":"button","action_id":"confirm_expense","value":"$draftId"}],
         "state":{"values":{"expense_confirm":{"category_select":{"selected_option":{
             "value":"$categoryId","text":{"type":"plain_text","text":"$categoryName"}}}}}}}
    """.trimIndent()

    private fun <T> query(block: () -> T): T = runBlocking { dbQuery(db) { block() } }

    private fun givenCategory(name: String): UUID = query {
        insertTestCategory(name = name, description = "desc for $name", amount = 60_000L)
    }

    private fun givenInbound(): UUID = runBlocking {
        dbQuery(db) {
            InboundRepository().captureOrSkip(
                "EvRouteConfirm", "U1", "C1", "7500 tokyu store", "1751700000.000100"
            )
        }!!
    }

    private fun givenDraft(inboundId: UUID, predictedCategoryId: UUID): UUID = query {
        ExpenseDraftRepository().insert(
            inboundMessageId = inboundId,
            userId = "U1",
            channelId = "C1",
            amount = 7500,
            currency = "JPY",
            merchant = "Tokyu Store",
            note = null,
            predictedCategoryId = predictedCategoryId,
            confidence = 0.7,
            model = "qwen/qwen3-instruct",
        )
    }

    private fun givenShoppingDraft(
        eventId: String,
        vararg items: ShoppingDraftItemInput,
    ): UUID {
        val inboundId = query {
            InboundRepository().captureOrSkip(
                eventId,
                "U-sender",
                "C1",
                "list add ${items.joinToString(" and ") { it.item }}",
                "1751700000.000100",
            )
        }!!
        return query {
            ShoppingRepository().insertAddDraft(
                inboundId,
                "U-sender",
                "C1",
                items.toList(),
            )
        }
    }

    private fun shoppingHandler(slack: SlackClient): SlackInteractionHandler =
        SlackInteractionHandler(
            db = db,
            draftRepo = ExpenseDraftRepository(),
            expenseRepo = ExpenseRepository(),
            inboundRepo = InboundRepository(),
            eventRepo = CategorizationEventRepository(),
            shoppingService = ShoppingService(db, ShoppingRepository()),
            slack = slack,
        )

    private suspend fun ApplicationTestBuilder.postSigned(body: String): HttpStatusCode {
        val ts = Instant.now().epochSecond
        return client.post("/slack/interactions") {
            header("X-Slack-Request-Timestamp", ts.toString())
            header("X-Slack-Signature", slackSignature(secret, ts, body))
            setBody(body)
        }.status
    }

    @Test
    fun `unsigned interaction is rejected with 401`() = testApplication {
        bootWithSecret()
        val res = client.post("/slack/interactions") { setBody(formBody(confirmPayload())) }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `signed confirm interaction acks 200`() = testApplication {
        bootWithSecret()
        assertEquals(HttpStatusCode.OK, postSigned(formBody(confirmPayload())))
    }

    @Test
    fun `signed confirm dispatches the selected category through the real handler`() = testApplication {
        val inboundId = givenInbound()
        val predicted = givenCategory("Convenience Store")
        val corrected = givenCategory("Monthly Groceries")
        val draftId = givenDraft(inboundId, predicted)
        val slack = mockk<SlackClient>(relaxUnitFun = true)
        val handler = SlackInteractionHandler(
            db = db,
            draftRepo = ExpenseDraftRepository(),
            expenseRepo = ExpenseRepository(),
            inboundRepo = InboundRepository(),
            eventRepo = CategorizationEventRepository(),
            shoppingService = ShoppingService(db, ShoppingRepository()),
            slack = slack,
        )

        application {
            routing { slackInteractionRoutes(secret, handler) }
        }

        val body = formBody(
            confirmPayload(
                draftId = draftId.toString(),
                categoryId = corrected.toString(),
                categoryName = "Monthly Groceries",
            )
        )

        assertEquals(HttpStatusCode.OK, postSigned(body))
        coVerify(timeout = 2_000, exactly = 1) {
            slack.replaceCard(
                "https://hooks.slack.test/response",
                match { "Recorded" in it && "Monthly Groceries" in it },
            )
        }
        assertEquals(corrected, query { Expense.selectAll().single()[Expense.categoryId] })
    }

    @Test
    fun `signed dropdown change interaction acks 200 and is ignored`() = testApplication {
        bootWithSecret()
        // A bare category_select change (no confirm_expense action) still POSTs here.
        val payload = """
            {"type":"block_actions",
             "actions":[{"type":"static_select","action_id":"category_select"}],
             "state":{"values":{"expense_confirm":{"category_select":{"selected_option":{"value":"${UUID.randomUUID()}"}}}}}}
        """.trimIndent()
        assertEquals(HttpStatusCode.OK, postSigned(formBody(payload)))
    }

    @Test
    fun `signed not-an-expense interaction cancels the draft through the real handler`() =
        testApplication {
            val inboundId = givenInbound()
            val predicted = givenCategory("Convenience Store")
            val draftId = givenDraft(inboundId, predicted)
            val slack = mockk<SlackClient>(relaxUnitFun = true)
            application {
                routing { slackInteractionRoutes(secret, shoppingHandler(slack)) }
            }
            val payload = """
                {"type":"block_actions",
                 "response_url":"https://hooks.slack.test/response",
                 "actions":[{"type":"button","action_id":"cancel_expense","value":"$draftId"}]}
            """.trimIndent()

            assertEquals(HttpStatusCode.OK, postSigned(formBody(payload)))
            coVerify(timeout = 2_000, exactly = 1) {
                slack.replaceCard(
                    "https://hooks.slack.test/response",
                    "Nothing recorded — marked as not an expense.",
                )
            }
            assertEquals(
                "CANCELLED",
                query { ExpenseDraft.selectAll().single()[ExpenseDraft.status] },
            )
            assertEquals(
                "NON_EXPENSE",
                query { InboundMessage.selectAll().single()[InboundMessage.status] },
            )
            assertEquals(0L, query { Expense.selectAll().count() })
        }

    @Test
    fun `signed shopping Add interaction consumes the draft through the real handler`() = testApplication {
        val draftId = givenShoppingDraft(
            "EvRouteShoppingAdd",
            ShoppingDraftItemInput("milk"),
        )
        val slack = mockk<SlackClient>(relaxUnitFun = true)
        application {
            routing { slackInteractionRoutes(secret, shoppingHandler(slack)) }
        }
        val payload = """
            {"type":"block_actions",
             "user":{"id":"U-clicker"},
             "response_url":"https://hooks.slack.test/shopping",
             "actions":[{"type":"button","action_id":"confirm_shopping_add","value":"$draftId"}]}
        """.trimIndent()

        assertEquals(HttpStatusCode.OK, postSigned(formBody(payload)))
        coVerify(timeout = 2_000, exactly = 1) {
            slack.replaceCard(
                "https://hooks.slack.test/shopping",
                match { "Added" in it },
                any(),
            )
        }
        assertEquals(1L, query { ShoppingItem.selectAll().count() })
    }

    @Test
    fun `signed shopping Cancel interaction consumes the draft through the real handler`() = testApplication {
        val draftId = givenShoppingDraft(
            "EvRouteShoppingCancel",
            ShoppingDraftItemInput("bread"),
        )
        val slack = mockk<SlackClient>(relaxUnitFun = true)
        application {
            routing { slackInteractionRoutes(secret, shoppingHandler(slack)) }
        }
        val payload = """
            {"type":"block_actions",
             "user":{"id":"U-clicker"},
             "response_url":"https://hooks.slack.test/shopping",
             "actions":[{"type":"button","action_id":"cancel_shopping_add","value":"$draftId"}]}
        """.trimIndent()

        assertEquals(HttpStatusCode.OK, postSigned(formBody(payload)))
        coVerify(timeout = 2_000, exactly = 1) {
            slack.replaceCard(
                "https://hooks.slack.test/shopping",
                "Nothing added",
                any(),
            )
        }
        assertEquals(
            "CANCELLED",
            query { ShoppingAddDraft.selectAll().single()[ShoppingAddDraft.status] },
        )
    }

    @Test
    fun `signed checkbox interaction marks selected UUIDs bought`() = testApplication {
        val repository = ShoppingRepository()
        val shoppingService = ShoppingService(db, repository)
        val draftId = givenShoppingDraft(
            "EvRouteShoppingBought",
            ShoppingDraftItemInput("milk"),
            ShoppingDraftItemInput("eggs"),
        )
        checkNotNull(runBlocking { shoppingService.confirmAdd(draftId, "U-add") })
        val milkId = runBlocking {
            shoppingService.listPending().first { it.item == "milk" }.id
        }
        val slack = mockk<SlackClient>(relaxUnitFun = true)
        application {
            routing { slackInteractionRoutes(secret, shoppingHandler(slack)) }
        }
        val payload = """
            {"type":"block_actions",
             "user":{"id":"U-buyer"},
             "response_url":"https://hooks.slack.test/shopping",
             "actions":[{"type":"checkboxes","action_id":"shopping_mark_bought",
                 "selected_options":[{"value":"$milkId","text":{"type":"plain_text","text":"milk"}}]}]}
        """.trimIndent()

        assertEquals(HttpStatusCode.OK, postSigned(formBody(payload)))
        coVerify(timeout = 2_000, exactly = 1) {
            slack.replaceCard(
                "https://hooks.slack.test/shopping",
                match { "marked bought" in it },
                any(),
            )
        }
        assertEquals(
            "BOUGHT",
            query {
                ShoppingItem.selectAll()
                    .single { it[ShoppingItem.id] == milkId }[ShoppingItem.status]
            },
        )
    }

    @Test
    fun `signed Undo interaction restores a bought item through the real handler`() = testApplication {
        val repository = ShoppingRepository()
        val shoppingService = ShoppingService(db, repository)
        val draftId = givenShoppingDraft(
            "EvRouteShoppingUndo",
            ShoppingDraftItemInput("milk"),
        )
        checkNotNull(runBlocking { shoppingService.confirmAdd(draftId, "U-add") })
        val milkId = runBlocking { shoppingService.listPending().single().id }
        val mark = runBlocking { shoppingService.markBought(listOf(milkId), "U-buyer") }
        val mutationId = checkNotNull(mark.mutationId)
        val slack = mockk<SlackClient>(relaxUnitFun = true)
        application {
            routing { slackInteractionRoutes(secret, shoppingHandler(slack)) }
        }
        val payload = """
            {"type":"block_actions",
             "user":{"id":"U-undo"},
             "response_url":"https://hooks.slack.test/shopping",
             "actions":[{"type":"button","action_id":"undo_shopping_mutation","value":"$mutationId"}]}
        """.trimIndent()

        assertEquals(HttpStatusCode.OK, postSigned(formBody(payload)))
        coVerify(timeout = 2_000, exactly = 1) {
            slack.replaceCard(
                "https://hooks.slack.test/shopping",
                match { "Restored" in it },
                any(),
            )
        }
        assertEquals(
            "PENDING",
            query { ShoppingItem.selectAll().single()[ShoppingItem.status] },
        )
        assertTrue(
            query {
                ShoppingMutation.selectAll()
                    .any { it[ShoppingMutation.kind] == "UNDO_BOUGHT" }
            },
        )
    }

    @Test
    fun `signed but malformed payload still acks 200`() = testApplication {
        bootWithSecret()
        // We respond 200 before parsing; a garbage payload is logged-and-dropped, never surfaced.
        assertEquals(HttpStatusCode.OK, postSigned(formBody("not-json")))
    }
}
