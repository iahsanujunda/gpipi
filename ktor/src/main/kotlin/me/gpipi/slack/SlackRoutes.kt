package me.gpipi.slack

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.application
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database

private val json = Json { ignoreUnknownKeys = true }

/**
 * Slack Events API endpoint. Signature verification lives INSIDE this group (not as a global
 * plugin) on purpose — so public routes like `/health` can answer 200 without a Slack signature.
 */
fun Route.slackRoutes(signingSecret: String, db: Database) {
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
            handleEvent(payload, db)
        }
    }
}

private suspend fun handleEvent(payload: SlackEnvelope, db: Database) {
    // TODO(iter-1): echo text back once the Slack client exists.
    // TODO(iter-2): capture to inbound_message (dedup on event_id) + extract via db.
}
