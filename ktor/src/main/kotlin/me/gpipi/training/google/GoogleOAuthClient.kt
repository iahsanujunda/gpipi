package me.gpipi.training.google

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.parameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

class GoogleIntegrationException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Serializable
data class GoogleTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val scope: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
)

class GoogleOAuthClient(
    private val http: HttpClient,
    private val settings: GoogleSettings,
    private val authorizationEndpoint: String = "https://accounts.google.com/o/oauth2/v2/auth",
    private val tokenEndpoint: String = "https://oauth2.googleapis.com/token",
    private val revokeEndpoint: String = "https://oauth2.googleapis.com/revoke",
) {
    fun authorizationUrl(state: String): String {
        require(settings.configured) { "Google integration is not configured." }
        return URLBuilder(authorizationEndpoint).apply {
            parameters {
                append("client_id", settings.clientId)
                append("redirect_uri", settings.redirectUri)
                append("response_type", "code")
                append("scope", DRIVE_FILE_SCOPE)
                append("access_type", "offline")
                append("include_granted_scopes", "false")
                append("prompt", "consent")
                append("state", state)
            }
        }.buildString()
    }

    suspend fun exchangeCode(code: String): GoogleTokenResponse = tokenRequest(
        Parameters.build {
            append("client_id", settings.clientId)
            append("client_secret", settings.clientSecret)
            append("code", code)
            append("grant_type", "authorization_code")
            append("redirect_uri", settings.redirectUri)
        },
    )

    suspend fun refresh(refreshToken: String): GoogleTokenResponse = tokenRequest(
        Parameters.build {
            append("client_id", settings.clientId)
            append("client_secret", settings.clientSecret)
            append("refresh_token", refreshToken)
            append("grant_type", "refresh_token")
        },
    )

    suspend fun revoke(refreshToken: String) {
        try {
            http.submitForm(
                url = revokeEndpoint,
                formParameters = Parameters.build { append("token", refreshToken) },
            )
        } catch (ex: Exception) {
            throw GoogleIntegrationException("Google credential revocation failed.", ex)
        }
    }

    private suspend fun tokenRequest(parameters: Parameters): GoogleTokenResponse = try {
        http.submitForm(url = tokenEndpoint, formParameters = parameters).body()
    } catch (ex: Exception) {
        throw GoogleIntegrationException("Google token exchange failed.", ex)
    }
}
