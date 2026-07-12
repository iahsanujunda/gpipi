package me.gpipi.slack

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.net.URLDecoder
import java.util.UUID
import kotlin.text.Charsets.UTF_8
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/**
 * Slack Events API endpoint. Signature verification lives INSIDE this group (not as a global
 * plugin) on purpose — so public routes like `/health` can answer 200 without a Slack signature.
 */
fun Route.slackRoutes(signingSecret: String, handler: SlackEventHandler) {
    post("/slack/events") {
        val raw = call.receiveText()

        // Verify against the RAW body, before deserializing anything.
        if (!verifySlackSignature(call.request.headers, raw, signingSecret)) {
            call.respond(HttpStatusCode.Unauthorized)
            return@post
        }

        val payload = json.decodeFromString<SlackEnvelope>(raw)

        // One-time setup handshake — echo the challenge back verbatim.
        if (payload.type == "url_verification") {
            call.respondText(payload.challenge.orEmpty())
            return@post
        }

        // Slack retries on any non-200 within 3s and marks it with X-Slack-Retry-Num;
        // short-circuit retries to 200 (real dedup on event_id lands in iter 2).
        if (call.request.headers["X-Slack-Retry-Num"] != null) {
            call.respond(HttpStatusCode.OK)
            return@post
        }

        // ACK within 3s — nothing heavy runs before this line.
        call.respond(HttpStatusCode.OK)

        // App-scoped launch so the work survives the response returning; the request's own
        // scope would cancel it the moment we respond.
        call.application.launch {
            handler.handle(payload)
        }
    }
}

fun Route.slackInteractionRoutes(signingSecret: String, handler: SlackInteractionHandler) {
    post("/slack/interactions") {
        val raw = call.receiveText()

        if (!verifySlackSignature(call.request.headers, raw, signingSecret)) {
            call.respond(HttpStatusCode.Unauthorized); return@post
        }

        // ACK within 3s — everything below runs after the response returns.
        call.respond(HttpStatusCode.OK)

        // Parse + dispatch inside the launch so a malformed payload can't throw after we've
        // already responded — log and drop instead.
        call.application.launch {
            try {
                val payloadJson = URLDecoder.decode(raw.removePrefix("payload="), UTF_8)
                val interaction = json.decodeFromString<Interaction>(payloadJson)
                if (interaction.type != "block_actions") return@launch

                // Only the Confirm button acts; a bare dropdown change fires block_actions too — ignore it.
                val confirm = interaction.actions.firstOrNull { it.actionId == "confirm_expense" } ?: return@launch
                val draftId = confirm.value?.let(UUID::fromString) ?: return@launch
                val categoryId = interaction.state?.values?.values
                    ?.firstNotNullOfOrNull { block -> block["category_select"]?.selectedOption?.value }
                    ?.let(UUID::fromString) ?: return@launch

                handler.handleConfirm(draftId, finalCategoryId = categoryId)
            } catch (ex: Exception) {
                call.application.log.warn("Dropping malformed Slack interaction: ${ex.message}")
            }
        }
    }
}
