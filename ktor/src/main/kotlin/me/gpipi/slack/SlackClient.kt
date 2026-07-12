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

class SlackClient(
    private val http: HttpClient,
    private val botToken: String,
) {
    private val log = LoggerFactory.getLogger(SlackClient::class.java)

    private suspend fun chatPostMessage(body: JsonObject) {
        val res = http.post("https://slack.com/api/chat.postMessage") {
            bearerAuth(botToken); contentType(Application.Json); setBody(body)
        }
        val text = res.bodyAsText()
        val ok = Json.parseToJsonElement(text).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == true
        if (!ok) log.warn("chat.postMessage failed: ${text.take(200)}")
    }

    suspend fun postMessage(channel: String, text: String) {
        val body = buildJsonObject { put("channel", channel); put("text", text) }
        this.chatPostMessage(body)
    }

    suspend fun postCard(channel: String, text: String, blocks: JsonArray) {
        val body = buildJsonObject { put("channel", channel); put("text", text); put("blocks", blocks) }
        this.chatPostMessage(body)
    }
}