package me.gpipi.training.google

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class GoogleCredentialCipherTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })

    @Test
    fun `refresh tokens are authenticated encrypted and use a fresh nonce`() {
        val cipher = GoogleCredentialCipher(key)
        val first = cipher.encrypt("refresh-token-value")
        val second = cipher.encrypt("refresh-token-value")

        assertNotEquals(first, second)
        assertEquals("refresh-token-value", cipher.decrypt(first))
        assertEquals("refresh-token-value", cipher.decrypt(second))
        val parts = first.split('.').toMutableList()
        val ciphertext = parts[2]
        parts[2] = ciphertext.replaceRange(2, 3, if (ciphertext[2] == 'A') "B" else "A")
        assertFailsWith<Exception> { cipher.decrypt(parts.joinToString(".")) }
    }

    @Test
    fun `encryption key must be exactly 256 bits`() {
        val shortKey = Base64.getEncoder().encodeToString(ByteArray(16))
        assertFailsWith<IllegalArgumentException> { GoogleCredentialCipher(shortKey) }
    }
}
