package me.gpipi.training.google

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

data class GoogleSheetCandidate(
    val spreadsheetId: String,
    val name: String,
    val modifiedAt: OffsetDateTime,
)

data class GoogleSheetCandidatePage(
    val sheets: List<GoogleSheetCandidate>,
    val nextPageToken: String?,
)

interface GoogleDriveSheetGateway {
    suspend fun listSheets(
        accessToken: String,
        query: String,
        pageToken: String?,
    ): GoogleSheetCandidatePage
}

class GoogleDriveSheetClient(
    private val http: HttpClient,
    apiBaseUrl: String = "https://www.googleapis.com/drive/v3",
) : GoogleDriveSheetGateway {
    private val base = apiBaseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listSheets(
        accessToken: String,
        query: String,
        pageToken: String?,
    ): GoogleSheetCandidatePage {
        require(query.length <= MAX_QUERY_LENGTH) { "Sheet search is too long." }
        require(pageToken == null || pageToken.length <= MAX_PAGE_TOKEN_LENGTH) { "Sheet page token is invalid." }
        val response = try {
            http.get("$base/files") {
                bearerAuth(accessToken)
                parameter("q", sheetQuery(query))
                parameter("spaces", "drive")
                parameter("corpora", "user")
                parameter("orderBy", "modifiedTime desc,name")
                parameter("pageSize", PAGE_SIZE)
                parameter("fields", "nextPageToken,files(id,name,modifiedTime)")
                pageToken?.let { parameter("pageToken", it) }
            }
        } catch (ex: Exception) {
            throw GoogleIntegrationException("Google Drive could not be reached.", ex)
        }
        if (!response.status.isSuccess()) {
            throw GoogleIntegrationException(
                "Google Drive rejected the Sheet list (${response.status.value}). Reconnect Google and try again.",
            )
        }
        val root = try {
            json.parseToJsonElement(response.bodyAsText()) as JsonObject
        } catch (ex: Exception) {
            throw GoogleIntegrationException("Google Drive returned an unreadable Sheet list.", ex)
        }
        val sheets = (root["files"] as? JsonArray).orEmpty().mapNotNull { element ->
            val file = element as? JsonObject ?: return@mapNotNull null
            val id = file["id"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val name = file["name"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val modifiedAt = runCatching {
                OffsetDateTime.parse(file["modifiedTime"]?.jsonPrimitive?.content)
            }.getOrNull() ?: return@mapNotNull null
            GoogleSheetCandidate(id, name, modifiedAt)
        }
        return GoogleSheetCandidatePage(
            sheets = sheets,
            nextPageToken = root["nextPageToken"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank),
        )
    }

    private fun sheetQuery(query: String): String = buildString {
        append("mimeType = 'application/vnd.google-apps.spreadsheet' and trashed = false")
        query.trim().takeIf(String::isNotEmpty)?.let {
            append(" and name contains '")
            append(it.replace("\\", "\\\\").replace("'", "\\'"))
            append("'")
        }
    }

    private companion object {
        const val PAGE_SIZE = 20
        const val MAX_QUERY_LENGTH = 100
        const val MAX_PAGE_TOKEN_LENGTH = 2_000
    }
}
