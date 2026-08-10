package me.gpipi.training.google

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class GoogleCredentialCipher(
    base64Key: String,
    private val random: SecureRandom = SecureRandom(),
) {
    private val key = Base64.getDecoder().decode(base64Key).also {
        require(it.size == KEY_BYTES) {
            "GOOGLE_CREDENTIAL_ENCRYPTION_KEY must be standard Base64 for exactly 32 random bytes."
        }
    }

    fun encrypt(refreshToken: String): String {
        require(refreshToken.isNotBlank()) { "Google refresh token must not be blank." }
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        }
        val ciphertext = cipher.doFinal(refreshToken.toByteArray(StandardCharsets.UTF_8))
        return "v1.${encoder.encodeToString(nonce)}.${encoder.encodeToString(ciphertext)}"
    }

    fun decrypt(value: String): String {
        val parts = value.split('.')
        require(parts.size == 3 && parts[0] == "v1") { "Unsupported encrypted Google credential format." }
        val nonce = decoder.decode(parts[1])
        require(nonce.size == NONCE_BYTES) { "Invalid encrypted Google credential nonce." }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        }
        return String(cipher.doFinal(decoder.decode(parts[2])), StandardCharsets.UTF_8)
    }

    private companion object {
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val decoder: Base64.Decoder = Base64.getUrlDecoder()
    }
}
