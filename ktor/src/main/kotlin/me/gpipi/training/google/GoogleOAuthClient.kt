package me.gpipi.training.google

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val DRIVE_METADATA_SCOPE = "https://www.googleapis.com/auth/drive.metadata.readonly"
const val SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets"
val GOOGLE_TRAINING_SCOPES = setOf(DRIVE_METADATA_SCOPE, SHEETS_SCOPE)

fun hasGoogleTrainingScopes(scope: String): Boolean = scope
    .split(Regex("\\s+"))
    .filter(String::isNotBlank)
    .toSet()
    .containsAll(GOOGLE_TRAINING_SCOPES)

class GoogleIntegrationException(message: String, cause: Throwable? = null) : Exception(message, cause)

class GoogleSheetWriteRejectedException(message: String) : Exception(message)

@Serializable
data class GoogleTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val scope: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
)

@Serializable
private data class GoogleTokenErrorResponse(
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

class GoogleOAuthClient(
    private val http: HttpClient,
    private val settings: GoogleSettings,
    private val authorizationEndpoint: String = "https://accounts.google.com/o/oauth2/v2/auth",
    private val tokenEndpoint: String = "https://oauth2.googleapis.com/token",
    private val revokeEndpoint: String = "https://oauth2.googleapis.com/revoke",
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun authorizationUrl(state: String): String {
        require(settings.configured) { "Google integration is not configured." }
        return URLBuilder(authorizationEndpoint).apply {
            parameters.append("client_id", settings.clientId)
            parameters.append("redirect_uri", settings.redirectUri)
            parameters.append("response_type", "code")
            parameters.append("scope", GOOGLE_TRAINING_SCOPES.joinToString(" "))
            parameters.append("access_type", "offline")
            parameters.append("include_granted_scopes", "false")
            parameters.append("prompt", "consent")
            parameters.append("state", state)
        }.buildString()
    }

    suspend fun exchangeCode(code: String): GoogleTokenResponse = tokenRequest(
        operation = "exchange",
        parameters = Parameters.build {
            append("client_id", settings.clientId)
            append("client_secret", settings.clientSecret)
            append("code", code)
            append("grant_type", "authorization_code")
            append("redirect_uri", settings.redirectUri)
        },
    )

    suspend fun refresh(refreshToken: String): GoogleTokenResponse = tokenRequest(
        operation = "refresh",
        parameters = Parameters.build {
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

    private suspend fun tokenRequest(operation: String, parameters: Parameters): GoogleTokenResponse {
        val response = try {
            http.submitForm(url = tokenEndpoint, formParameters = parameters)
        } catch (ex: Exception) {
            throw GoogleIntegrationException("Google token $operation could not reach Google.", ex)
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val googleError = runCatching { json.decodeFromString<GoogleTokenErrorResponse>(body) }.getOrNull()
            val code = googleError?.error?.takeIf { it.matches(Regex("[a-z_]{1,64}")) }
            val description = googleError?.errorDescription
                ?.replace(Regex("[\\r\\n]+"), " ")
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.take(240)
            val status = response.status.takeUnless { it == HttpStatusCode.BadRequest }
                ?.let { "HTTP ${it.value}" }
            val reason = listOfNotNull(code, status).joinToString(" · ").takeIf(String::isNotEmpty)
            val suffix = buildString {
                reason?.let { append(" ($it)") }
                description?.let { append(": $it") }
                append('.')
            }
            throw GoogleIntegrationException("Google token $operation failed$suffix")
        }
        return try {
            json.decodeFromString<GoogleTokenResponse>(body)
        } catch (ex: Exception) {
            throw GoogleIntegrationException("Google token $operation returned an unreadable response.", ex)
        }
    }
}
