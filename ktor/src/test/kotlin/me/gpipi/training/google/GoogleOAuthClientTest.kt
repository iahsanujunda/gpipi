package me.gpipi.training.google

import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoogleOAuthClientTest {
    @Test
    fun `authorization URL contains the complete web-server OAuth request`() {
        val http = HttpClient()
        try {
            val settings = GoogleSettings(
                clientId = "client-id.apps.googleusercontent.com",
                clientSecret = "client-secret",
                redirectUri = "https://api.example.test/api/training/google/callback",
                credentialEncryptionKey = "configured-in-production",
            )

            val url = Url(GoogleOAuthClient(http, settings).authorizationUrl("one-use-state"))

            assertEquals("https", url.protocol.name)
            assertEquals("accounts.google.com", url.host)
            assertEquals("/o/oauth2/v2/auth", url.encodedPath)
            assertEquals("client-id.apps.googleusercontent.com", url.parameters["client_id"])
            assertEquals(settings.redirectUri, url.parameters["redirect_uri"])
            assertEquals("code", url.parameters["response_type"])
            assertEquals(GOOGLE_TRAINING_SCOPES, url.parameters["scope"]?.split(' ')?.toSet())
            assertEquals("offline", url.parameters["access_type"])
            assertEquals("false", url.parameters["include_granted_scopes"])
            assertEquals("consent", url.parameters["prompt"])
            assertEquals("one-use-state", url.parameters["state"])
        } finally {
            http.close()
        }
    }

    @Test
    fun `token exchange accepts successful Google responses with additional fields`() = testApplication {
        application {
            routing {
                post("/oauth/token") {
                    call.respondText(
                        """{
                            "access_token": "access-token",
                            "expires_in": 3600,
                            "refresh_token": "refresh-token",
                            "scope": "scope-one scope-two",
                            "token_type": "Bearer",
                            "refresh_token_expires_in": 604800
                        }""",
                        ContentType.Application.Json,
                    )
                }
            }
        }

        val token = GoogleOAuthClient(
            http = createClient {},
            settings = settings(),
            tokenEndpoint = "/oauth/token",
        ).exchangeCode("authorization-code")

        assertEquals("access-token", token.accessToken)
        assertEquals("refresh-token", token.refreshToken)
    }

    @Test
    fun `token exchange exposes Google error without exposing the raw response`() = testApplication {
        application {
            routing {
                post("/oauth/token") {
                    call.respondText(
                        """{
                            "error": "invalid_client",
                            "error_description": "The OAuth client credentials are invalid.",
                            "internal_detail": "must-not-leak"
                        }""",
                        ContentType.Application.Json,
                        HttpStatusCode.Unauthorized,
                    )
                }
            }
        }

        val error = assertFailsWith<GoogleIntegrationException> {
            GoogleOAuthClient(
                http = createClient {},
                settings = settings(),
                tokenEndpoint = "/oauth/token",
            ).exchangeCode("authorization-code")
        }

        assertContains(error.message.orEmpty(), "invalid_client")
        assertContains(error.message.orEmpty(), "OAuth client credentials are invalid")
        assertEquals(false, error.message.orEmpty().contains("must-not-leak"))
    }

    private fun settings() = GoogleSettings(
        clientId = "client-id.apps.googleusercontent.com",
        clientSecret = "client-secret",
        redirectUri = "https://api.example.test/api/training/google/callback",
        credentialEncryptionKey = "configured-in-production",
    )
}
