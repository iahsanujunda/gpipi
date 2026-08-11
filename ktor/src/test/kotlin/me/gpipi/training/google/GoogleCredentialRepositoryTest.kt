package me.gpipi.training.google

import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class GoogleCredentialRepositoryTest : PersistenceTest() {
    private val repository = GoogleCredentialRepository()
    private val now = OffsetDateTime.parse("2026-08-11T03:00:00Z")

    @Test
    fun `oauth state is consumed once and returns its owner and path`() {
        transaction(db) {
            repository.saveState(
                rawState = "one-use-state",
                userId = "U-OAUTH",
                returnPath = "/training/program/import",
                expiresAt = now.plusMinutes(10),
                now = now,
            )

            assertEquals(
                ConsumedGoogleOAuthState("U-OAUTH", "/training/program/import"),
                repository.consumeState("one-use-state", now.plusMinutes(1)),
            )
            assertEquals(null, repository.consumeState("one-use-state", now.plusMinutes(1)))
        }
    }

    @Test
    fun `revoking a credential returns its encrypted refresh token`() {
        transaction(db) {
            repository.saveCredential(
                userId = "U-OAUTH",
                encryptedRefreshToken = "encrypted-refresh-token",
                scope = "drive.file",
                now = now,
            )

            assertEquals("encrypted-refresh-token", repository.revoke("U-OAUTH", now.plusMinutes(1)))
            assertEquals(null, repository.revoke("U-OAUTH", now.plusMinutes(2)))
        }
    }
}
