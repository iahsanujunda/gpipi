package me.gpipi.training.google

import io.ktor.server.config.ApplicationConfig

data class GoogleSettings(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
    val pickerApiKey: String,
    val appId: String,
    val credentialEncryptionKey: String,
) {
    val configured: Boolean
        get() = listOf(
            clientId,
            clientSecret,
            redirectUri,
            pickerApiKey,
            appId,
            credentialEncryptionKey,
        ).all(String::isNotBlank)

    fun missingConfiguration(): List<String> = buildList {
        if (clientId.isBlank()) add("GOOGLE_OAUTH_CLIENT_ID")
        if (clientSecret.isBlank()) add("GOOGLE_OAUTH_CLIENT_SECRET")
        if (redirectUri.isBlank()) add("GOOGLE_OAUTH_REDIRECT_URI")
        if (pickerApiKey.isBlank()) add("GOOGLE_PICKER_API_KEY")
        if (appId.isBlank()) add("GOOGLE_CLOUD_PROJECT_NUMBER")
        if (credentialEncryptionKey.isBlank()) add("GOOGLE_CREDENTIAL_ENCRYPTION_KEY")
    }
}

fun ApplicationConfig.googleSettings() = GoogleSettings(
    clientId = propertyOrNull("google.oauth.clientId")?.getString().orEmpty(),
    clientSecret = propertyOrNull("google.oauth.clientSecret")?.getString().orEmpty(),
    redirectUri = propertyOrNull("google.oauth.redirectUri")?.getString().orEmpty(),
    pickerApiKey = propertyOrNull("google.picker.apiKey")?.getString().orEmpty(),
    appId = propertyOrNull("google.picker.appId")?.getString().orEmpty(),
    credentialEncryptionKey = propertyOrNull("google.credentialEncryptionKey")?.getString().orEmpty(),
)
