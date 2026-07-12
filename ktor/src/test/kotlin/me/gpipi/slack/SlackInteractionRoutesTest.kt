package me.gpipi.slack

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import me.gpipi.support.configureWithTestDb

/**
 * Plumbing tests for /slack/interactions: signature gate + 3s ack + payload decode. The confirm
 * WRITE behavior is owned by SlackInteractionHandlerTest; here we only prove the route decodes a
 * url-encoded `payload=` form body and always answers 200 once the signature checks out. Since the
 * real work is launched async against random draft ids, nothing reaches Slack.
 */
class SlackInteractionRoutesTest {

    private val secret = "test-signing-secret"

    private fun ApplicationTestBuilder.bootWithSecret() = configureWithTestDb(secret)

    /** Slack sends interactivity as `application/x-www-form-urlencoded`: a single url-encoded `payload=` field. */
    private fun formBody(payloadJson: String) = "payload=" + URLEncoder.encode(payloadJson, "UTF-8")

    private fun confirmPayload(
        draftId: String = UUID.randomUUID().toString(),
        categoryId: String = UUID.randomUUID().toString(),
    ) = """
        {"type":"block_actions",
         "actions":[{"type":"button","action_id":"confirm_expense","value":"$draftId"}],
         "state":{"values":{"expense_confirm":{"category_select":{"selected_option":{"value":"$categoryId"}}}}}}
    """.trimIndent()

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
