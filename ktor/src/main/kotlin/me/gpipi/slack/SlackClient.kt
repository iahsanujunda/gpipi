package me.gpipi.slack

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType.Application
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

class SlackApiException(
    method: String,
    error: String,
    cause: Throwable? = null,
) : RuntimeException("$method failed: $error", cause)

class SlackClient(
    private val http: HttpClient,
    private val botToken: String,
    apiBaseUrl: String = "https://slack.com/api",
) {
    private val log = LoggerFactory.getLogger(SlackClient::class.java)
    private val apiBaseUrl = apiBaseUrl.trimEnd('/')

    private suspend fun postChat(method: String, body: JsonObject) {
        val res = http.post("$apiBaseUrl/$method") {
            bearerAuth(botToken); contentType(Application.Json); setBody(body)
        }
        val text = res.bodyAsText()
        val payload = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (ex: Exception) {
            log.warn("{} returned an invalid response", method)
            throw SlackApiException(method, "invalid_response", ex)
        }
        val ok = payload["ok"]?.jsonPrimitive?.booleanOrNull == true
        if (!ok) {
            val error = payload["error"]?.jsonPrimitive?.content ?: "unknown_error"
            log.warn("{} failed: {}", method, error)
            throw SlackApiException(method, error)
        }
    }

    suspend fun postMessage(channel: String, text: String) {
        val body = buildJsonObject { put("channel", channel); put("text", text) }
        postChat("chat.postMessage", body)
    }

    suspend fun postEphemeral(channel: String, user: String, text: String) {
        val body = buildJsonObject {
            put("channel", channel)
            put("user", user)
            put("text", text)
        }
        postChat("chat.postEphemeral", body)
    }

    suspend fun postEphemeralCard(channel: String, user: String, text: String, blocks: JsonArray) {
        val body = buildJsonObject {
            put("channel", channel)
            put("user", user)
            put("text", text)
            put("blocks", blocks)
        }
        postChat("chat.postEphemeral", body)
    }

    suspend fun postCard(channel: String, text: String, blocks: JsonArray) {
        val body = buildJsonObject { put("channel", channel); put("text", text); put("blocks", blocks) }
        postChat("chat.postMessage", body)
    }

    suspend fun replaceCard(responseUrl: String, text: String) {
        http.post(responseUrl) {
            contentType(Application.Json)
            setBody(buildJsonObject { put("replace_original", true); put("text", text) })
        }
    }

}
