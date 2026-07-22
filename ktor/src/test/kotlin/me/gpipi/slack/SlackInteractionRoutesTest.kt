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
import kotlinx.coroutines.runBlocking
import me.gpipi.categorization.CategorizationEventRepository
import me.gpipi.config.dbQuery
import me.gpipi.expense.ExpenseDraftRepository
import me.gpipi.expense.ExpenseRepository
import me.gpipi.generated.db.base.public1.Category
import me.gpipi.generated.db.base.public1.Expense
import me.gpipi.inbound.InboundRepository
import me.gpipi.support.PersistenceTest
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
        val id = UUID.randomUUID()
        Category.insert {
            it[Category.id] = id
            it[Category.name] = name
            it[Category.description] = "desc for $name"
            it[Category.period] = "MONTHLY"
            it[Category.amount] = 60000L
            it[Category.slackLoggable] = true
        }
        id
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
    fun `signed but malformed payload still acks 200`() = testApplication {
        bootWithSecret()
        // We respond 200 before parsing; a garbage payload is logged-and-dropped, never surfaced.
        assertEquals(HttpStatusCode.OK, postSigned(formBody("not-json")))
    }
}
