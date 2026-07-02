package me.gpipi.slack

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Computes a valid Slack request signature — the test-side twin of [verifySlackSignature]. */
fun slackSignature(secret: String, timestamp: Long, body: String): String {
    val mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    }
    return "v0=" + mac.doFinal("v0:$timestamp:$body".toByteArray())
        .joinToString("") { "%02x".format(it) }
}
