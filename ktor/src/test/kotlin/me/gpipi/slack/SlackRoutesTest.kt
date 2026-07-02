package me.gpipi.slack

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails

class SlackRoutesTest {

    private val secret = "test-signing-secret"

    /** Boots the real application.conf module chain with a known signing secret injected. */
    private fun ApplicationTestBuilder.bootWithSecret() = configure {
        put("slack.signingSecret", secret)
    }

    @Test
    fun `unsigned request is rejected with 401`() = testApplication {
        bootWithSecret()
        val res = client.post("/slack/events") {
            setBody("""{"type":"event_callback"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun `signed url_verification echoes the challenge`() = testApplication {
        bootWithSecret()
        // The exact handshake Slack sends when you set the Request URL.
        val body = """{"type":"url_verification","challenge":"abc123"}"""
        val ts = Instant.now().epochSecond
        val res = client.post("/slack/events") {
            header("X-Slack-Request-Timestamp", ts.toString())
            header("X-Slack-Signature", slackSignature(secret, ts, body))
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("abc123", res.bodyAsText())
    }

    @Test
    fun `signed retry short-circuits to 200`() = testApplication {
        bootWithSecret()
        val body = """{"type":"event_callback"}"""
        val ts = Instant.now().epochSecond
        val res = client.post("/slack/events") {
            header("X-Slack-Request-Timestamp", ts.toString())
            header("X-Slack-Signature", slackSignature(secret, ts, body))
            header("X-Slack-Retry-Num", "1")
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, res.status)
    }

    @Test
    fun `signed event acks 200`() = testApplication {
        bootWithSecret()
        val body = """{"type":"event_callback"}"""
        val ts = Instant.now().epochSecond
        val res = client.post("/slack/events") {
            header("X-Slack-Request-Timestamp", ts.toString())
            header("X-Slack-Signature", slackSignature(secret, ts, body))
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, res.status)
    }

    @Test
    fun `refuses to boot when the signing secret is blank`() = testApplication {
        // Regression guard for the misconfigured-boot class of bug: a blank secret must fail
        // startup loudly, never run silently unverifiable.
        configure {
            put("slack.signingSecret", "")
        }
        val ex = assertFails { startApplication() }
        assertContains(ex.message.orEmpty(), "SLACK_SIGNING_SECRET")
    }
}
