package me.gpipi.training.google

import java.time.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class SelectedGoogleSheet(val spreadsheetId: String)

class GoogleSheetSelectionCodec(
    private val cipher: GoogleCredentialCipher,
    private val clock: Clock,
    private val json: Json = Json,
) {
    fun issue(userId: String, spreadsheetId: String): String = cipher.encrypt(
        json.encodeToString(
            SelectionPayload(
                userId = userId,
                spreadsheetId = spreadsheetId,
                expiresAtEpochSecond = clock.instant().plusSeconds(TTL_SECONDS).epochSecond,
            ),
        ),
    )

    fun resolve(userId: String, token: String): SelectedGoogleSheet = try {
        val payload = json.decodeFromString<SelectionPayload>(cipher.decrypt(token))
        require(payload.version == VERSION)
        require(payload.userId == userId)
        require(payload.expiresAtEpochSecond >= clock.instant().epochSecond)
        require(payload.spreadsheetId.matches(SPREADSHEET_ID))
        SelectedGoogleSheet(payload.spreadsheetId)
    } catch (ex: Exception) {
        throw GoogleIntegrationException("Sheet selection expired. Choose the Sheet again.", ex)
    }

    @Serializable
    private data class SelectionPayload(
        val version: Int = VERSION,
        val userId: String,
        val spreadsheetId: String,
        val expiresAtEpochSecond: Long,
    )

    private companion object {
        const val VERSION = 1
        const val TTL_SECONDS = 10 * 60L
        val SPREADSHEET_ID = Regex("[A-Za-z0-9_-]{10,200}")
    }
}
