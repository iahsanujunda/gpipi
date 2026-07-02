package me.gpipi.slack

import io.ktor.http.headersOf
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlackSignatureTest {

    private val secret = "8f742231b10e8888abcd99yyyzzz85a5"
    private val body = """{"type":"event_callback","event":{"text":"just paid 1500jpy for ramen"}}"""

    private fun headersFor(timestamp: String, signature: String) = headersOf(
        "X-Slack-Request-Timestamp" to listOf(timestamp),
        "X-Slack-Signature" to listOf(signature),
    )

    @Test
    fun `accepts a correctly signed fresh request`() {
        val ts = Instant.now().epochSecond
        val sig = slackSignature(secret, ts, body)
        assertTrue(verifySlackSignature(headersFor(ts.toString(), sig), body, secret))
    }

    @Test
    fun `rejects a tampered body`() {
        val ts = Instant.now().epochSecond
        val sig = slackSignature(secret, ts, body)
        assertFalse(verifySlackSignature(headersFor(ts.toString(), sig), body + "x", secret))
    }

    @Test
    fun `rejects a signature made with the wrong secret`() {
        val ts = Instant.now().epochSecond
        val sig = slackSignature("attacker-guessed-secret", ts, body)
        assertFalse(verifySlackSignature(headersFor(ts.toString(), sig), body, secret))
    }

    @Test
    fun `rejects a stale timestamp even when the signature is valid`() {
        // Correctly signed, but outside the 5-minute replay window.
        val ts = Instant.now().epochSecond - 6 * 60
        val sig = slackSignature(secret, ts, body)
        assertFalse(verifySlackSignature(headersFor(ts.toString(), sig), body, secret))
    }

    @Test
    fun `rejects a missing signature header`() {
        val ts = Instant.now().epochSecond
        val headers = headersOf("X-Slack-Request-Timestamp", ts.toString())
        assertFalse(verifySlackSignature(headers, body, secret))
    }

    @Test
    fun `rejects a missing timestamp header`() {
        val sig = slackSignature(secret, Instant.now().epochSecond, body)
        assertFalse(verifySlackSignature(headersOf("X-Slack-Signature", sig), body, secret))
    }

    @Test
    fun `rejects a malformed timestamp`() {
        val sig = slackSignature(secret, Instant.now().epochSecond, body)
        assertFalse(verifySlackSignature(headersFor("not-a-number", sig), body, secret))
    }
}
