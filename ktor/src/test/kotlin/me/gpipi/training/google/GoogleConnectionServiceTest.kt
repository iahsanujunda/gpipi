package me.gpipi.training.google

import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.gpipi.config.dbQuery
import me.gpipi.support.PersistenceTest

class GoogleConnectionServiceTest : PersistenceTest() {
    private val oauth = mockk<GoogleOAuthClient> {
        every { authorizationUrl(any()) } answers { "https://accounts.test/auth?state=${firstArg<String>()}" }
    }
    private val settings = GoogleSettings(
        clientId = "client-id",
        clientSecret = "client-secret",
        redirectUri = "https://app.test/api/training/google/callback",
        credentialEncryptionKey = "configured-in-production",
    )
    private val service = GoogleConnectionService(
        db = db,
        repository = GoogleCredentialRepository(),
        oauth = oauth,
        cipher = null,
        settings = settings,
        clock = Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC),
    )

    @Test
    fun `oauth can return to the training import entry point`() = runBlocking {
        val authorizationUrl = service.beginConnection("U-OAUTH", "/training/program/import")
        assertTrue(authorizationUrl.startsWith("https://accounts.test/auth?state="))
    }

    @Test
    fun `oauth can return to one completed workout write page`() = runBlocking {
        val authorizationUrl = service.beginConnection(
            "U-OAUTH",
            "/training/weeks/3/workouts/61000000-0000-0000-0000-000000000001/write",
        )
        assertTrue(authorizationUrl.startsWith("https://accounts.test/auth?state="))
    }

    @Test
    fun `oauth rejects the removed new-program import return path`() = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            service.beginConnection("U-OAUTH", "/training/program/import/new")
        }
    }

    @Test
    fun `oauth rejects return paths outside the training import allowlist`() = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            service.beginConnection("U-OAUTH", "//attacker.test")
        }
    }

    @Test
    fun `a legacy drive file connection requires reconnect`() = runBlocking {
        dbQuery(db) {
            GoogleCredentialRepository().saveCredential(
                userId = "U-LEGACY",
                encryptedRefreshToken = "encrypted-token",
                scope = "https://www.googleapis.com/auth/drive.file",
                now = java.time.OffsetDateTime.parse("2026-08-11T00:00:00Z"),
            )
        }

        val status = service.status("U-LEGACY")

        assertFalse(status.connected)
        assertTrue(status.requiresReconnect)
    }
}
