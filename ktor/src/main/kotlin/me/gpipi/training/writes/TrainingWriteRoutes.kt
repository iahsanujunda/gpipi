package me.gpipi.training.writes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.util.UUID
import kotlinx.serialization.Serializable
import me.gpipi.UserSession
import me.gpipi.training.google.GoogleConnectionService
import me.gpipi.training.google.GoogleIntegrationException

@Serializable private data class TrainingWriteApiError(val message: String)

fun Route.trainingWriteApiRoutes(
    google: GoogleConnectionService,
    writes: TrainingWriteService,
) {
    route("/api/training") {
        get("/sessions/{sessionId}/write-destination") {
            val actor = call.writeActorId() ?: return@get
            val session = call.writeUuid("sessionId") ?: return@get
            call.respondWrite(writes.destination(actor, session))
        }

        get("/sessions/{sessionId}/write-status") {
            val actor = call.writeActorId() ?: return@get
            val session = call.writeUuid("sessionId") ?: return@get
            call.respondWrite(writes.status(actor, session))
        }

        post("/sessions/{sessionId}/writes") {
            val actor = call.writeActorId() ?: return@post
            val session = call.writeUuid("sessionId") ?: return@post
            val request = call.receive<StartTrainingWriteRequest>()
            val spreadsheetId = try {
                request.selectionToken?.let { google.resolveSheetSelection(actor, it).spreadsheetId }
            } catch (ex: GoogleIntegrationException) {
                call.respond(HttpStatusCode.BadRequest, TrainingWriteApiError(ex.message.orEmpty()))
                return@post
            }
            call.respondWrite(writes.start(actor, session, spreadsheetId), HttpStatusCode.Created)
        }

        get("/writes/{writeId}") {
            val actor = call.writeActorId() ?: return@get
            val write = call.writeUuid("writeId") ?: return@get
            call.respondWrite(writes.get(actor, write))
        }

        post("/writes/{writeId}/selection") {
            val actor = call.writeActorId() ?: return@post
            val write = call.writeUuid("writeId") ?: return@post
            call.respondWrite(writes.beginSelection(actor, write))
        }

        put("/writes/{writeId}/tab") {
            val actor = call.writeActorId() ?: return@put
            val write = call.writeUuid("writeId") ?: return@put
            val request = call.receive<ChooseTrainingWriteTabRequest>()
            call.respondWrite(writes.chooseTab(actor, write, request.tabKey))
        }

        put("/writes/{writeId}/week") {
            val actor = call.writeActorId() ?: return@put
            val write = call.writeUuid("writeId") ?: return@put
            val request = call.receive<ChooseTrainingWriteWeekRequest>()
            call.respondWrite(writes.chooseWeek(actor, write, request.weekNumber))
        }

        put("/writes/{writeId}/matches") {
            val actor = call.writeActorId() ?: return@put
            val write = call.writeUuid("writeId") ?: return@put
            call.respondWrite(writes.confirmMatches(actor, write, call.receive()))
        }

        post("/writes/{writeId}/preview") {
            val actor = call.writeActorId() ?: return@post
            val write = call.writeUuid("writeId") ?: return@post
            call.respondWrite(writes.prepare(actor, write))
        }

        post("/writes/{writeId}/confirm") {
            val actor = call.writeActorId() ?: return@post
            val write = call.writeUuid("writeId") ?: return@post
            call.respondWrite(writes.confirm(actor, write))
        }

        post("/writes/{writeId}/verify") {
            val actor = call.writeActorId() ?: return@post
            val write = call.writeUuid("writeId") ?: return@post
            call.respondWrite(writes.verify(actor, write))
        }
    }
}

private suspend fun ApplicationCall.writeActorId(): String? {
    val value = principal<UserSession>()?.userId
    if (value == null) respond(HttpStatusCode.Unauthorized)
    return value
}

private suspend fun ApplicationCall.writeUuid(name: String): UUID? {
    val value = runCatching { parameters[name]?.let(UUID::fromString) }.getOrNull()
    if (value == null) respond(HttpStatusCode.BadRequest, TrainingWriteApiError("'$name' must be a UUID."))
    return value
}

private suspend fun <T : Any> ApplicationCall.respondWrite(
    result: TrainingWriteResult<T>,
    successStatus: HttpStatusCode = HttpStatusCode.OK,
) {
    when (result) {
        is TrainingWriteResult.Ok -> respond(successStatus, result.value as Any)
        TrainingWriteResult.NotFound -> respond(HttpStatusCode.NotFound, TrainingWriteApiError("Training write not found."))
        is TrainingWriteResult.Invalid -> respond(HttpStatusCode.BadRequest, TrainingWriteApiError(result.message))
        is TrainingWriteResult.Conflict -> respond(HttpStatusCode.Conflict, TrainingWriteApiError(result.message))
        is TrainingWriteResult.Unavailable -> respond(HttpStatusCode.ServiceUnavailable, TrainingWriteApiError(result.message))
    }
}
