package me.gpipi.training.google

import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals

class GoogleOAuthClientTest {
    @Test
    fun `authorization URL contains the complete web-server OAuth request`() {
        val http = HttpClient()
        try {
            val settings = GoogleSettings(
                clientId = "client-id.apps.googleusercontent.com",
                clientSecret = "client-secret",
                redirectUri = "https://api.example.test/api/training/google/callback",
                pickerApiKey = "picker-key",
                appId = "123456789",
                credentialEncryptionKey = "configured-in-production",
            )

            val url = Url(GoogleOAuthClient(http, settings).authorizationUrl("one-use-state"))

            assertEquals("https", url.protocol.name)
            assertEquals("accounts.google.com", url.host)
            assertEquals("/o/oauth2/v2/auth", url.encodedPath)
            assertEquals("client-id.apps.googleusercontent.com", url.parameters["client_id"])
            assertEquals(settings.redirectUri, url.parameters["redirect_uri"])
            assertEquals("code", url.parameters["response_type"])
            assertEquals("https://www.googleapis.com/auth/drive.file", url.parameters["scope"])
            assertEquals("offline", url.parameters["access_type"])
            assertEquals("false", url.parameters["include_granted_scopes"])
            assertEquals("consent", url.parameters["prompt"])
            assertEquals("one-use-state", url.parameters["state"])
        } finally {
            http.close()
        }
    }
}
