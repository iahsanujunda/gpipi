package me.gpipi.training.google

import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import me.gpipi.config.dbQuery
import org.jetbrains.exposed.v1.jdbc.Database

private val ALLOWED_GOOGLE_OAUTH_RETURN_PATHS = setOf(
    "/training/program/import",
)

data class GoogleConnectionStatus(
    val configured: Boolean,
    val connected: Boolean,
    val requiresReconnect: Boolean,
    val connectedAt: OffsetDateTime?,
    val missingConfiguration: List<String>,
)

data class GoogleSheetOption(
    val selectionToken: String,
    val name: String,
    val modifiedAt: OffsetDateTime,
)

data class GoogleSheetOptionPage(
    val sheets: List<GoogleSheetOption>,
    val nextPageToken: String?,
)

class GoogleConnectionService(
    private val db: Database,
    private val repository: GoogleCredentialRepository,
    private val oauth: GoogleOAuthClient?,
    private val cipher: GoogleCredentialCipher?,
    private val settings: GoogleSettings,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
    private val drive: GoogleDriveSheetGateway? = null,
) {
    private val sheetSelections = cipher?.let { GoogleSheetSelectionCodec(it, clock) }

    suspend fun status(userId: String): GoogleConnectionStatus {
        val credential = dbQuery(db) { repository.credential(userId) }
        val hasCurrentScopes = credential?.scope?.let(::hasGoogleTrainingScopes) == true
        return GoogleConnectionStatus(
            configured = settings.configured,
            connected = settings.configured && credential != null && hasCurrentScopes,
            requiresReconnect = credential != null && !hasCurrentScopes,
            connectedAt = credential?.connectedAt,
            missingConfiguration = settings.missingConfiguration(),
        )
    }

    suspend fun beginConnection(userId: String, returnPath: String): String {
        val client = requireClient()
        require(returnPath in ALLOWED_GOOGLE_OAUTH_RETURN_PATHS) { "Google OAuth return path is not allowed." }
        val state = ByteArray(32).also(random::nextBytes).let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it)
        }
        val now = now()
        dbQuery(db) {
            repository.saveState(state, userId, returnPath, now.plusMinutes(10), now)
        }
        return client.authorizationUrl(state)
    }

    suspend fun completeConnection(
        authenticatedUserId: String,
        state: String,
        code: String,
    ): String {
        val client = requireClient()
        val stateRecord = dbQuery(db) { repository.consumeState(state, now()) }
            ?: throw GoogleIntegrationException("Google connection expired. Start again from Training.")
        if (stateRecord.userId != authenticatedUserId) {
            throw GoogleIntegrationException("Google connection belongs to a different signed-in member.")
        }
        val tokens = client.exchangeCode(code)
        val refreshToken = tokens.refreshToken
            ?: throw GoogleIntegrationException(
                "Google did not return a refresh token. Revoke the app in Google Account settings and connect again.",
            )
        val encrypted = requireCipher().encrypt(refreshToken)
        dbQuery(db) {
            repository.saveCredential(
                userId = authenticatedUserId,
                encryptedRefreshToken = encrypted,
                scope = tokens.scope?.takeIf(String::isNotBlank)
                    ?: GOOGLE_TRAINING_SCOPES.joinToString(" "),
                now = now(),
            )
        }
        return stateRecord.returnPath
    }

    suspend fun listSheets(userId: String, query: String, pageToken: String?): GoogleSheetOptionPage {
        val access = accessToken(userId)
        val page = requireDrive().listSheets(access.accessToken, query.trim(), pageToken)
        val codec = requireSheetSelections()
        return GoogleSheetOptionPage(
            sheets = page.sheets.map { sheet ->
                GoogleSheetOption(
                    selectionToken = codec.issue(userId, sheet.spreadsheetId),
                    name = sheet.name,
                    modifiedAt = sheet.modifiedAt,
                )
            },
            nextPageToken = page.nextPageToken,
        )
    }

    fun resolveSheetSelection(userId: String, selectionToken: String): SelectedGoogleSheet =
        requireSheetSelections().resolve(userId, selectionToken)

    suspend fun accessToken(userId: String): GoogleTokenResponse {
        val credential = dbQuery(db) { repository.credential(userId) }
            ?: throw GoogleIntegrationException("Connect Google before selecting or reading a Sheet.")
        if (!hasGoogleTrainingScopes(credential.scope)) {
            throw GoogleIntegrationException("Reconnect Google to approve Sheet search and read/write access.")
        }
        return requireClient().refresh(requireCipher().decrypt(credential.encryptedRefreshToken))
    }

    suspend fun disconnect(userId: String) {
        val encrypted = dbQuery(db) { repository.revoke(userId, now()) } ?: return
        val token = requireCipher().decrypt(encrypted)
        runCatching { requireClient().revoke(token) }
    }

    private fun requireClient(): GoogleOAuthClient = oauth
        ?: throw GoogleIntegrationException(
            "Google integration is not configured: ${settings.missingConfiguration().joinToString()}.",
        )

    private fun requireCipher(): GoogleCredentialCipher = cipher
        ?: throw GoogleIntegrationException("Google credential encryption is not configured.")

    private fun requireDrive(): GoogleDriveSheetGateway = drive
        ?: throw GoogleIntegrationException("Google Drive Sheet search is not configured.")

    private fun requireSheetSelections(): GoogleSheetSelectionCodec = sheetSelections
        ?: throw GoogleIntegrationException("Google Sheet selection encryption is not configured.")

    private fun now(): OffsetDateTime = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
}
