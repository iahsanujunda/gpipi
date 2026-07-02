package me.gpipi.slack

import io.ktor.http.Headers
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

fun verifySlackSignature(headers: Headers, rawBody: String, signingSecret: String): Boolean {
    val timestamp = headers["X-Slack-Request-Timestamp"]?.toLongOrNull() ?: return false
    // Replay guard — reject anything older than 5 minutes.
    if (abs(Instant.now().epochSecond - timestamp) > 60 * 5) return false

    val baseString = "v0:$timestamp:$rawBody"
    val mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(signingSecret.toByteArray(), "HmacSHA256"))
    }
    val computed = "v0=" + mac.doFinal(baseString.toByteArray()).toHexString()
    val provided = headers["X-Slack-Signature"] ?: return false
    return MessageDigest.isEqual(computed.toByteArray(), provided.toByteArray()) // constant-time
}
