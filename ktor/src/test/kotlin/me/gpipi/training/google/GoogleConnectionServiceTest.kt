package me.gpipi.training.google

import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import me.gpipi.support.PersistenceTest

class GoogleConnectionServiceTest : PersistenceTest() {
    private val oauth = mockk<GoogleOAuthClient> {
        every { authorizationUrl(any()) } answers { "https://accounts.test/auth?state=${firstArg<String>()}" }
    }
    private val settings = GoogleSettings(
        clientId = "client-id",
        clientSecret = "client-secret",
        redirectUri = "https://app.test/api/training/google/callback",
        pickerApiKey = "picker-key",
        appId = "123456789",
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
    fun `oauth can return to either training import entry point`() = runBlocking {
        listOf(
            "/training/program/import",
            "/training/program/import/new",
        ).forEachIndexed { index, returnPath ->
            val authorizationUrl = service.beginConnection("U-OAUTH-$index", returnPath)
            assertTrue(authorizationUrl.startsWith("https://accounts.test/auth?state="))
        }
    }

    @Test
    fun `oauth rejects return paths outside the training import allowlist`() = runBlocking {
        assertFailsWith<IllegalArgumentException> {
            service.beginConnection("U-OAUTH", "//attacker.test")
        }
    }
}
