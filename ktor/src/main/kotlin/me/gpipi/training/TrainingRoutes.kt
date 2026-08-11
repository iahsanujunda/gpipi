package me.gpipi.training

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.serialization.Serializable
import me.gpipi.UserSession

@Serializable
data class TrainingProgramResponse(
    val id: String,
    val name: String,
    val note: String?,
    val startsOn: String?,
    val active: Boolean,
)

@Serializable
data class ExerciseCatalogResponse(
    val id: String,
    val name: String,
    val demoUrl: String?,
    val aliases: List<String>,
)

@Serializable
data class WeekWorkoutResponse(
    val weekId: String,
    val workoutId: String,
    val workoutName: String,
    val status: String,
    val sessionId: String?,
    val performedOn: String?,
    val setCount: Int,
    val updatedAt: String?,
)

@Serializable
data class WeekOverviewResponse(
    val program: TrainingProgramResponse,
    val currentWeekNumber: Int?,
    val selectedWeekNumber: Int,
    val availableWeekNumbers: List<Int>,
    val workouts: List<WeekWorkoutResponse>,
)

@Serializable
data class PerformedSetResponse(
    val id: String,
    val setNumber: Int,
    val reps: Int?,
    val durationSeconds: Int?,
    val load: String?,
    val rir: Int?,
    val note: String?,
    val targetReps: String?,
    val targetLoad: String?,
    val targetRir: String?,
    val targetTempo: String?,
)

@Serializable
data class ExerciseExecutionResponse(
    val prescriptionId: String,
    val performedExerciseId: String?,
    val position: Int,
    val exerciseName: String,
    val demoUrl: String?,
    val executionType: String,
    val targetSets: String?,
    val targetRest: String?,
    val targetReps: String?,
    val targetLoad: String?,
    val targetRir: String?,
    val targetTempo: String?,
    val targetNote: String?,
    val executionNote: String?,
    val sets: List<PerformedSetResponse>,
)

@Serializable
data class WorkoutGroupResponse(
    val position: Int,
    val label: String,
    val kind: String,
    val exercises: List<ExerciseExecutionResponse>,
)

@Serializable
data class SessionResponse(
    val id: String,
    val performedOn: String,
    val status: String,
    val note: String?,
    val updatedAt: String,
    val completedAt: String?,
)

@Serializable
data class WorkoutDetailResponse(
    val program: TrainingProgramResponse,
    val currentWeekNumber: Int?,
    val weekId: String,
    val weekNumber: Int,
    val skipped: Boolean,
    val workoutId: String,
    val workoutName: String,
    val workoutNote: String?,
    val session: SessionResponse?,
    val groups: List<WorkoutGroupResponse>,
)

@Serializable
data class PutSetRequest(
    val reps: Int? = null,
    val durationSeconds: Int? = null,
    val load: String? = null,
    val rir: Int? = null,
    val note: String? = null,
)

@Serializable
data class UpdateSessionRequest(val performedOn: String, val note: String? = null)

@Serializable
data class PrescriptionAuthoringRequest(
    val exerciseName: String,
    val exerciseId: String? = null,
    val createExercise: Boolean = false,
    val demoUrl: String? = null,
    val executionType: String,
    val sets: String? = null,
    val rest: String? = null,
    val reps: String? = null,
    val load: String? = null,
    val rir: String? = null,
    val tempo: String? = null,
    val note: String? = null,
)

@Serializable
data class GroupAuthoringRequest(
    val label: String,
    val kind: String,
    val prescriptions: List<PrescriptionAuthoringRequest>,
)

@Serializable
data class CreateProgramRequest(
    val name: String,
    val note: String? = null,
    val startsOn: String? = null,
)

@Serializable
data class CreateWorkoutRequest(
    val name: String,
    val note: String? = null,
    val groups: List<GroupAuthoringRequest>,
)

@Serializable
data class DuplicateWeekRequest(val sourceWeek: Int, val targetWeek: Int)

@Serializable
private data class TrainingApiError(val message: String)

@Serializable
private data class CreatedResponse(val id: String)

fun Route.trainingApiRoutes(service: TrainingService) {
    route("/api/training") {
        get("/exercises") {
            val actorId = call.actorId() ?: return@get
            call.respond(service.exercises(actorId).map { it.toResponse() })
        }

        get("/programs") {
            val actorId = call.actorId() ?: return@get
            call.respond(service.programs(actorId).map { it.toResponse() })
        }

        put("/programs/{programId}/activate") {
            val actorId = call.actorId() ?: return@put
            val programId = call.uuid("programId") ?: return@put
            call.respondMutation(service.activateProgram(actorId, programId))
        }

        put("/programs/{programId}") {
            val actorId = call.actorId() ?: return@put
            val programId = call.uuid("programId") ?: return@put
            val request = call.receive<CreateProgramRequest>()
            val startsOn = request.startsOn?.let {
                try {
                    LocalDate.parse(it)
                } catch (_: DateTimeParseException) {
                    call.respond(HttpStatusCode.BadRequest, TrainingApiError("'startsOn' must be YYYY-MM-DD."))
                    return@put
                }
            }
            call.respondMutation(service.updateProgram(actorId, programId, request.toInput(startsOn)))
        }

        get {
            val actorId = call.actorId() ?: return@get
            val week = call.request.queryParameters["week"]?.toIntOrNull()
            if (call.request.queryParameters["week"] != null && week == null) {
                call.respond(HttpStatusCode.BadRequest, TrainingApiError("'week' must be an integer."))
                return@get
            }
            call.respondRead(service.overview(actorId, week)) { it.toResponse() }
        }

        get("/weeks/{weekNumber}/workouts/{workoutId}") {
            val actorId = call.actorId() ?: return@get
            val week = call.positiveInt("weekNumber") ?: return@get
            val workoutId = call.uuid("workoutId") ?: return@get
            call.respondRead(service.workoutDetail(actorId, week, workoutId)) { it.toResponse() }
        }

        put("/weeks/{weekId}/prescriptions/{prescriptionId}/sets/{setNumber}") {
            val actorId = call.actorId() ?: return@put
            val weekId = call.uuid("weekId") ?: return@put
            val prescriptionId = call.uuid("prescriptionId") ?: return@put
            val setNumber = call.positiveInt("setNumber") ?: return@put
            val request = call.receive<PutSetRequest>()
            val load = request.load?.let {
                try {
                    BigDecimal(it)
                } catch (_: NumberFormatException) {
                    call.respond(HttpStatusCode.BadRequest, TrainingApiError("'load' must be numeric."))
                    return@put
                }
            }
            call.respondMutation(
                service.putSet(
                    actorId,
                    weekId,
                    prescriptionId,
                    setNumber,
                    SetInput(request.reps, request.durationSeconds, load, request.rir, request.note),
                ),
            )
        }

        delete("/weeks/{weekId}/prescriptions/{prescriptionId}/sets/{setNumber}") {
            val actorId = call.actorId() ?: return@delete
            val weekId = call.uuid("weekId") ?: return@delete
            val prescriptionId = call.uuid("prescriptionId") ?: return@delete
            val setNumber = call.positiveInt("setNumber") ?: return@delete
            call.respondMutation(service.deleteSet(actorId, weekId, prescriptionId, setNumber))
        }

        put("/weeks/{weekId}/session") {
            val actorId = call.actorId() ?: return@put
            val weekId = call.uuid("weekId") ?: return@put
            val request = call.receive<UpdateSessionRequest>()
            val date = try {
                LocalDate.parse(request.performedOn)
            } catch (_: DateTimeParseException) {
                call.respond(HttpStatusCode.BadRequest, TrainingApiError("'performedOn' must be YYYY-MM-DD."))
                return@put
            }
            call.respondMutation(service.updateSession(actorId, weekId, date, request.note))
        }

        put("/weeks/{weekId}/finish") {
            val actorId = call.actorId() ?: return@put
            val weekId = call.uuid("weekId") ?: return@put
            call.respondMutation(service.finish(actorId, weekId))
        }

        put("/weeks/{weekId}/resume") {
            val actorId = call.actorId() ?: return@put
            val weekId = call.uuid("weekId") ?: return@put
            call.respondMutation(service.resume(actorId, weekId))
        }

        put("/weeks/{weekId}/skip") {
            val actorId = call.actorId() ?: return@put
            val weekId = call.uuid("weekId") ?: return@put
            call.respondMutation(service.skip(actorId, weekId))
        }

        put("/weeks/{weekId}/restore") {
            val actorId = call.actorId() ?: return@put
            val weekId = call.uuid("weekId") ?: return@put
            call.respondMutation(service.restore(actorId, weekId))
        }

        post("/programs") {
            val actorId = call.actorId() ?: return@post
            val request = call.receive<CreateProgramRequest>()
            val startsOn = request.startsOn?.let {
                try {
                    LocalDate.parse(it)
                } catch (_: DateTimeParseException) {
                    call.respond(HttpStatusCode.BadRequest, TrainingApiError("'startsOn' must be YYYY-MM-DD."))
                    return@post
                }
            }
            val input = try {
                request.toInput(startsOn)
            } catch (_: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, TrainingApiError("'exerciseId' must be a UUID."))
                return@post
            }
            when (val result = service.createProgram(actorId, input)) {
                is ProgramCreateResult.Created ->
                    call.respond(HttpStatusCode.Created, CreatedResponse(result.id.toString()))
                is ProgramCreateResult.Invalid ->
                    call.respond(HttpStatusCode.BadRequest, TrainingApiError(result.message))
                is ProgramCreateResult.Conflict ->
                    call.respond(HttpStatusCode.Conflict, TrainingApiError(result.message))
            }
        }

        post("/programs/{programId}/weeks/{weekNumber}/workouts") {
            val actorId = call.actorId() ?: return@post
            val programId = call.uuid("programId") ?: return@post
            val weekNumber = call.positiveInt("weekNumber") ?: return@post
            val request = call.receive<CreateWorkoutRequest>()
            val input = try {
                request.toInput()
            } catch (_: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, TrainingApiError("'exerciseId' must be a UUID."))
                return@post
            }
            when (val result = service.createWorkout(actorId, programId, weekNumber, input)) {
                is WorkoutCreateResult.Created ->
                    call.respond(HttpStatusCode.Created, CreatedResponse(result.id.toString()))
                WorkoutCreateResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, TrainingApiError("Training record not found."))
                is WorkoutCreateResult.Invalid ->
                    call.respond(HttpStatusCode.BadRequest, TrainingApiError(result.message))
                is WorkoutCreateResult.Conflict ->
                    call.respond(HttpStatusCode.Conflict, TrainingApiError(result.message))
            }
        }

        post("/workouts/{workoutId}/weeks/duplicate") {
            val actorId = call.actorId() ?: return@post
            val workoutId = call.uuid("workoutId") ?: return@post
            val request = call.receive<DuplicateWeekRequest>()
            call.respondMutation(
                service.duplicateWeek(actorId, workoutId, request.sourceWeek, request.targetWeek),
            )
        }
    }
}

private suspend fun ApplicationCall.actorId(): String? {
    val value = principal<UserSession>()?.userId
    if (value == null) respond(HttpStatusCode.Unauthorized)
    return value
}

private suspend fun ApplicationCall.uuid(name: String): UUID? {
    val value = try {
        parameters[name]?.let(UUID::fromString)
    } catch (_: IllegalArgumentException) {
        null
    }
    if (value == null) respond(HttpStatusCode.BadRequest, TrainingApiError("'$name' must be a UUID."))
    return value
}

private suspend fun ApplicationCall.positiveInt(name: String): Int? {
    val value = parameters[name]?.toIntOrNull()?.takeIf { it > 0 }
    if (value == null) respond(HttpStatusCode.BadRequest, TrainingApiError("'$name' must be positive."))
    return value
}

private suspend fun ApplicationCall.respondMutation(result: TrainingMutationResult) {
    when (result) {
        TrainingMutationResult.Updated -> respond(HttpStatusCode.NoContent)
        TrainingMutationResult.NotFound ->
            respond(HttpStatusCode.NotFound, TrainingApiError("Training record not found."))
        is TrainingMutationResult.Invalid ->
            respond(HttpStatusCode.BadRequest, TrainingApiError(result.message))
    }
}

private suspend fun <T, R : Any> ApplicationCall.respondRead(
    result: TrainingReadResult<T>,
    transform: (T) -> R,
) {
    when (result) {
        is TrainingReadResult.Found -> respond(message = transform(result.value) as Any)
        TrainingReadResult.NoActiveProgram -> respond(HttpStatusCode.NoContent)
        TrainingReadResult.NotFound ->
            respond(HttpStatusCode.NotFound, TrainingApiError("Training record not found."))
        is TrainingReadResult.Invalid ->
            respond(HttpStatusCode.BadRequest, TrainingApiError(result.message))
    }
}

private fun TrainingProgramRecord.toResponse() = TrainingProgramResponse(
    id.toString(), name, note, startsOn?.toString(), active,
)

private fun ExerciseCatalogRecord.toResponse() = ExerciseCatalogResponse(
    id.toString(), name, demoUrl, aliases,
)

private fun WeekOverviewRecord.toResponse() = WeekOverviewResponse(
    program = program.toResponse(),
    currentWeekNumber = currentWeekNumber,
    selectedWeekNumber = selectedWeekNumber,
    availableWeekNumbers = availableWeekNumbers,
    workouts = workouts.map {
        WeekWorkoutResponse(
            weekId = it.weekId.toString(),
            workoutId = it.workoutId.toString(),
            workoutName = it.workoutName,
            status = it.status,
            sessionId = it.sessionId?.toString(),
            performedOn = it.performedOn?.toString(),
            setCount = it.setCount,
            updatedAt = it.updatedAt?.toString(),
        )
    },
)

private fun WorkoutDetailRecord.toResponse() = WorkoutDetailResponse(
    program = program.toResponse(),
    currentWeekNumber = currentWeekNumber,
    weekId = weekId.toString(),
    weekNumber = weekNumber,
    skipped = skipped,
    workoutId = workoutId.toString(),
    workoutName = workoutName,
    workoutNote = workoutNote,
    session = session?.let {
        SessionResponse(
            it.id.toString(), it.performedOn.toString(), it.status, it.note,
            it.updatedAt.toString(), it.completedAt?.toString(),
        )
    },
    groups = groups.map { group ->
        WorkoutGroupResponse(
            position = group.position,
            label = group.label,
            kind = group.kind,
            exercises = group.exercises.map { exercise ->
                ExerciseExecutionResponse(
                    prescriptionId = exercise.prescriptionId.toString(),
                    performedExerciseId = exercise.performedExerciseId?.toString(),
                    position = exercise.position,
                    exerciseName = exercise.exerciseName,
                    demoUrl = exercise.demoUrl,
                    executionType = exercise.executionType,
                    targetSets = exercise.targetSets,
                    targetRest = exercise.targetRest,
                    targetReps = exercise.targetReps,
                    targetLoad = exercise.targetLoad,
                    targetRir = exercise.targetRir,
                    targetTempo = exercise.targetTempo,
                    targetNote = exercise.targetNote,
                    executionNote = exercise.executionNote,
                    sets = exercise.sets.map { set ->
                        PerformedSetResponse(
                            set.id.toString(), set.setNumber, set.reps, set.durationSeconds,
                            set.load?.stripTrailingZeros()?.toPlainString(), set.rir, set.note,
                            set.targetReps, set.targetLoad, set.targetRir, set.targetTempo,
                        )
                    },
                )
            },
        )
    },
)

private fun CreateProgramRequest.toInput(startsOn: LocalDate?) = ProgramAuthoringInput(
    name = name,
    note = note,
    startsOn = startsOn,
    workouts = emptyList(),
)

private fun CreateWorkoutRequest.toInput() = WorkoutCreateInput(
    name = name,
    note = note,
    groups = groups.map { group ->
        GroupAuthoringInput(
            label = group.label,
            kind = group.kind,
            prescriptions = group.prescriptions.map { it.toInput() },
        )
    },
)

private fun PrescriptionAuthoringRequest.toInput() = PrescriptionAuthoringInput(
    exerciseName = exerciseName,
    exerciseId = exerciseId?.let(UUID::fromString),
    createExercise = createExercise,
    demoUrl = demoUrl,
    executionType = executionType,
    sets = sets,
    rest = rest,
    reps = reps,
    load = load,
    rir = rir,
    tempo = tempo,
    note = note,
)
