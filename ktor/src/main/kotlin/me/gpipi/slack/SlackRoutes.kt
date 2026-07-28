package me.gpipi.slack

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import java.net.URLDecoder
import java.util.UUID
import kotlin.text.Charsets.UTF_8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import me.gpipi.observability.AppObservability
import me.gpipi.observability.AppObservabilityKey

private val json = Json { ignoreUnknownKeys = true }

/**
 * Slack Events API endpoint. Signature verification lives INSIDE this group (not as a global
 * plugin) on purpose — so public routes like `/health` can answer 200 without a Slack signature.
 */
fun Route.slackRoutes(signingSecret: String, handler: SlackEventHandler) {
    post("/slack/events") {
        val observability = call.application.attributes.getOrNull(AppObservabilityKey)
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

        // Capture the request span before responding. The server span ends with the ACK, while
        // its context remains the parent of the application-scoped processing span.
        val parentContext = Context.current()
        val app = call.application

        // ACK within 3s — nothing heavy runs before this line.
        call.respond(HttpStatusCode.OK)

        // App-scoped launch so the work survives the response returning; the request's own
        // scope would cancel it the moment we respond.
        app.launch(parentContext.asContextElement()) {
            runSlackBackground(observability, "event") {
                payload.eventId?.let {
                    Span.current().setAttribute("gpipi.slack.event_id", it)
                }
                handler.handle(payload)
            }.onFailure { cause ->
                app.log.error("Slack event processing failed", cause)
            }
        }
    }
}

fun Route.slackInteractionRoutes(signingSecret: String, handler: SlackInteractionHandler) {
    post("/slack/interactions") {
        val observability = call.application.attributes.getOrNull(AppObservabilityKey)
        val raw = call.receiveText()

        if (!verifySlackSignature(call.request.headers, raw, signingSecret)) {
            call.respond(HttpStatusCode.Unauthorized); return@post
        }

        val parentContext = Context.current()
        val app = call.application

        // ACK within 3s — everything below runs after the response returns.
        call.respond(HttpStatusCode.OK)

        // Parse + dispatch inside the launch so a malformed payload can't throw after we've
        // already responded — log and drop instead.
        app.launch(parentContext.asContextElement()) {
            runSlackBackground(observability, "interaction") {
                val payloadJson = URLDecoder.decode(raw.removePrefix("payload="), UTF_8)
                val interaction = json.decodeFromString<Interaction>(payloadJson)
                if (interaction.type != "block_actions") return@runSlackBackground

                val action = interaction.actions.firstOrNull() ?: return@runSlackBackground
                action.actionId?.let {
                    Span.current().setAttribute("gpipi.slack.action", it)
                }
                when (action.actionId) {
                    CONFIRM_ACTION_ID -> {
                        val draftId = action.value?.let(UUID::fromString)
                            ?: return@runSlackBackground
                        val selected = interaction.state?.values?.values
                            ?.firstNotNullOfOrNull {
                                block -> block[CATEGORY_ACTION_ID]?.selectedOption
                            } ?: return@runSlackBackground
                        val categoryId = selected.value?.let(UUID::fromString)
                            ?: return@runSlackBackground
                        val categoryName = selected.text?.text ?: return@runSlackBackground
                        handler.handleConfirm(
                            draftId,
                            categoryId,
                            categoryName,
                            interaction.responseUrl,
                        )
                    }

                    CANCEL_EXPENSE_ACTION_ID -> {
                        val draftId = action.value?.let(UUID::fromString)
                            ?: return@runSlackBackground
                        handler.handleExpenseCancel(draftId, interaction.responseUrl)
                    }

                    CONFIRM_SHOPPING_ADD_ACTION_ID -> {
                        val draftId = action.value?.let(UUID::fromString)
                            ?: return@runSlackBackground
                        val actorId = interaction.user?.id ?: return@runSlackBackground
                        handler.handleShoppingAddConfirm(
                            draftId,
                            actorId,
                            interaction.responseUrl,
                        )
                    }

                    CANCEL_SHOPPING_ADD_ACTION_ID -> {
                        val draftId = action.value?.let(UUID::fromString)
                            ?: return@runSlackBackground
                        handler.handleShoppingAddCancel(draftId, interaction.responseUrl)
                    }

                    SHOPPING_MARK_BOUGHT_ACTION_ID -> {
                        val actorId = interaction.user?.id ?: return@runSlackBackground
                        val itemIds = action.selectedOptions.mapNotNull {
                            it.value?.let(UUID::fromString)
                        }
                        handler.handleShoppingMarkBought(
                            itemIds,
                            actorId,
                            interaction.responseUrl,
                        )
                    }

                    UNDO_SHOPPING_ACTION_ID -> {
                        val mutationId = action.value?.let(UUID::fromString)
                            ?: return@runSlackBackground
                        val actorId = interaction.user?.id ?: return@runSlackBackground
                        handler.handleShoppingUndo(
                            mutationId,
                            actorId,
                            interaction.responseUrl,
                        )
                    }
                }
            }.onFailure { cause ->
                app.log.warn("Slack interaction processing failed", cause)
            }
        }
    }
}

private suspend fun runSlackBackground(
    observability: AppObservability?,
    kind: String,
    block: suspend () -> Unit,
): Result<Unit> =
    try {
        if (observability == null) {
            block()
        } else {
            observability.slackBackground(kind, block)
        }
        Result.success(Unit)
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Exception) {
        Result.failure(cause)
    }
