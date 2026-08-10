package me.gpipi.training.imports

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.serialization.Serializable
import me.gpipi.UserSession
import me.gpipi.training.google.GoogleConnectionService
import me.gpipi.training.google.GoogleIntegrationException

@Serializable private data class ImportApiError(val message: String)
@Serializable private data class AppliedWeekResponse(val weekNumber: Int)

fun Route.trainingImportApiRoutes(
    google: GoogleConnectionService,
    imports: TrainingImportService,
    webBaseUrl: String,
) {
    route("/api/training") {
        get("/google/status") {
            val actorId = call.importActorId() ?: return@get
            val status = google.status(actorId)
            call.respond(
                GoogleConnectionStatusResponse(
                    configured = status.configured,
                    connected = status.connected,
                    connectedAt = status.connectedAt?.toString(),
                    missingConfiguration = status.missingConfiguration,
                ),
            )
        }

        get("/google/connect") {
            val actorId = call.importActorId() ?: return@get
            val returnPath = call.request.queryParameters["returnPath"] ?: "/training/program/import"
            try {
                call.respond(GoogleConnectResponse(google.beginConnection(actorId, returnPath)))
            } catch (ex: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ImportApiError(ex.message ?: "Invalid Google return path."))
            } catch (ex: GoogleIntegrationException) {
                call.respond(HttpStatusCode.ServiceUnavailable, ImportApiError(ex.message.orEmpty()))
            }
        }

        get("/google/callback") {
            val actorId = call.importActorId() ?: return@get
            val state = call.request.queryParameters["state"]
            val code = call.request.queryParameters["code"]
            val error = call.request.queryParameters["error"]
            if (error != null || state.isNullOrBlank() || code.isNullOrBlank()) {
                call.respondRedirect("${webBaseUrl.trimEnd('/')}/training/program/import?google=cancelled")
                return@get
            }
            try {
                val returnPath = google.completeConnection(actorId, state, code)
                call.respondRedirect("${webBaseUrl.trimEnd('/')}$returnPath?google=connected")
            } catch (ex: GoogleIntegrationException) {
                val reason = URLEncoder.encode(ex.message.orEmpty(), StandardCharsets.UTF_8)
                call.respondRedirect("${webBaseUrl.trimEnd('/')}/training/program/import?google=error&reason=$reason")
            }
        }

        get("/google/picker-token") {
            val actorId = call.importActorId() ?: return@get
            try {
                val token = google.pickerToken(actorId)
                call.respond(GooglePickerTokenResponse(token.accessToken, token.expiresIn, token.apiKey, token.appId))
            } catch (ex: GoogleIntegrationException) {
                call.respond(HttpStatusCode.ServiceUnavailable, ImportApiError(ex.message.orEmpty()))
            }
        }

        delete("/google/connection") {
            val actorId = call.importActorId() ?: return@delete
            google.disconnect(actorId)
            call.respond(HttpStatusCode.NoContent)
        }

        post("/programs/{programId}/imports") {
            val actorId = call.importActorId() ?: return@post
            val programId = call.importUuid("programId") ?: return@post
            val request = call.receive<StartTrainingImportRequest>()
            call.respondImport(imports.start(actorId, programId, request.spreadsheetId), HttpStatusCode.Created)
        }

        post("/imports") {
            val actorId = call.importActorId() ?: return@post
            val request = call.receive<StartTrainingImportRequest>()
            call.respondImport(imports.startNewProgram(actorId, request.spreadsheetId), HttpStatusCode.Created)
        }

        get("/imports/{importId}") {
            val actorId = call.importActorId() ?: return@get
            val importId = call.importUuid("importId") ?: return@get
            call.respondImport(imports.get(actorId, importId))
        }

        put("/imports/{importId}/program") {
            val actorId = call.importActorId() ?: return@put
            val importId = call.importUuid("importId") ?: return@put
            call.respondImport(imports.saveNewProgramDraft(actorId, importId, call.receive()))
        }

        put("/imports/{importId}/week") {
            val actorId = call.importActorId() ?: return@put
            val importId = call.importUuid("importId") ?: return@put
            val request = call.receive<ChooseTrainingWeekRequest>()
            call.respondImport(imports.chooseWeek(actorId, importId, request.weekNumber))
        }

        put("/imports/{importId}/mapping") {
            val actorId = call.importActorId() ?: return@put
            val importId = call.importUuid("importId") ?: return@put
            call.respondImport(imports.saveMapping(actorId, importId, call.receive()))
        }

        post("/imports/{importId}/extract") {
            val actorId = call.importActorId() ?: return@post
            val importId = call.importUuid("importId") ?: return@post
            call.respondImport(imports.extract(actorId, importId))
        }

        put("/imports/{importId}/review") {
            val actorId = call.importActorId() ?: return@put
            val importId = call.importUuid("importId") ?: return@put
            call.respondImport(imports.saveReview(actorId, importId, call.receive()))
        }

        post("/imports/{importId}/apply") {
            val actorId = call.importActorId() ?: return@post
            val importId = call.importUuid("importId") ?: return@post
            when (val result = imports.apply(actorId, importId)) {
                is TrainingImportResult.Ok -> call.respond(AppliedWeekResponse(result.value))
                TrainingImportResult.NotFound -> call.respond(HttpStatusCode.NotFound, ImportApiError("Training import not found."))
                is TrainingImportResult.Invalid -> call.respond(HttpStatusCode.BadRequest, ImportApiError(result.message))
                is TrainingImportResult.Conflict -> call.respond(HttpStatusCode.Conflict, ImportApiError(result.message))
            }
        }

        post("/imports/{importId}/cancel") {
            val actorId = call.importActorId() ?: return@post
            val importId = call.importUuid("importId") ?: return@post
            when (val result = imports.cancel(actorId, importId)) {
                is TrainingImportResult.Ok -> call.respond(HttpStatusCode.NoContent)
                TrainingImportResult.NotFound -> call.respond(HttpStatusCode.NotFound, ImportApiError("Training import not found."))
                is TrainingImportResult.Invalid -> call.respond(HttpStatusCode.BadRequest, ImportApiError(result.message))
                is TrainingImportResult.Conflict -> call.respond(HttpStatusCode.Conflict, ImportApiError(result.message))
            }
        }
    }
}

private suspend fun ApplicationCall.importActorId(): String? {
    val value = principal<UserSession>()?.userId
    if (value == null) respond(HttpStatusCode.Unauthorized)
    return value
}

private suspend fun ApplicationCall.importUuid(name: String): UUID? {
    val value = runCatching { parameters[name]?.let(UUID::fromString) }.getOrNull()
    if (value == null) respond(HttpStatusCode.BadRequest, ImportApiError("'$name' must be a UUID."))
    return value
}

private suspend fun <T : Any> ApplicationCall.respondImport(
    result: TrainingImportResult<T>,
    successStatus: HttpStatusCode = HttpStatusCode.OK,
) {
    when (result) {
        is TrainingImportResult.Ok -> respond(successStatus, result.value as Any)
        TrainingImportResult.NotFound -> respond(HttpStatusCode.NotFound, ImportApiError("Training import not found."))
        is TrainingImportResult.Invalid -> respond(HttpStatusCode.BadRequest, ImportApiError(result.message))
        is TrainingImportResult.Conflict -> respond(HttpStatusCode.Conflict, ImportApiError(result.message))
    }
}
