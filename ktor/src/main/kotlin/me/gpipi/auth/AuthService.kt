package me.gpipi.auth

import me.gpipi.config.dbQuery
import org.jetbrains.exposed.v1.jdbc.Database
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.time.Clock
import java.util.Base64
import java.util.HexFormat

class AuthService (
    private val db: Database,
    private val nonceRepo: AuthNonceRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val secureRandom: SecureRandom = SecureRandom()
) {
    private companion object {
        const val NONCE_SIZE_BYTES = 32
    }

    private fun secureRandomToken(): String {
        val bytes = ByteArray(NONCE_SIZE_BYTES)
        secureRandom.nextBytes(bytes)

        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes)
    }

    private fun hash(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }

    suspend fun mint(userId: String): String {
        val rawNonce = secureRandomToken()
        val expiresAt = OffsetDateTime.now(clock).plusMinutes(10)

        dbQuery(db) {
            nonceRepo.insert(
                nonceHash = hash(rawNonce),
                userId = userId,
                expiresAt = expiresAt
            )
        }
        return rawNonce
    }

    suspend fun redeem(rawNonce: String): String? {
        if (rawNonce.isBlank()) return null

        return dbQuery(db) {
            nonceRepo.consume(
                nonceHash = hash(rawNonce),
                now = OffsetDateTime.now(clock)
            )
        }
    }
}
