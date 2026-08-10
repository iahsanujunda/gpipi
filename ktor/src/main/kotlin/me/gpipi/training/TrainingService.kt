package me.gpipi.training

import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import me.gpipi.config.dbQuery
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database

private val TRAINING_ZONE: ZoneId = ZoneId.of("Asia/Tokyo")

sealed interface TrainingReadResult<out T> {
    data class Found<T>(val value: T) : TrainingReadResult<T>
    data object NoActiveProgram : TrainingReadResult<Nothing>
    data object NotFound : TrainingReadResult<Nothing>
    data class Invalid(val message: String) : TrainingReadResult<Nothing>
}

sealed interface ProgramCreateResult {
    data class Created(val id: UUID) : ProgramCreateResult
    data class Invalid(val message: String) : ProgramCreateResult
    data class Conflict(val message: String) : ProgramCreateResult
}

sealed interface WorkoutCreateResult {
    data class Created(val id: UUID) : WorkoutCreateResult
    data object NotFound : WorkoutCreateResult
    data class Invalid(val message: String) : WorkoutCreateResult
    data class Conflict(val message: String) : WorkoutCreateResult
}

class TrainingService(
    private val db: Database,
    private val repository: TrainingRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val zoneId: ZoneId = TRAINING_ZONE,
) {
    suspend fun exercises(ownerUserId: String): List<ExerciseCatalogRecord> =
        dbQuery(db) { repository.exerciseCatalog(ownerUserId) }

    suspend fun programs(ownerUserId: String): List<TrainingProgramRecord> =
        dbQuery(db) { repository.programs(ownerUserId) }

    suspend fun activateProgram(ownerUserId: String, programId: UUID): TrainingMutationResult =
        dbQuery(db) {
            if (repository.activateProgram(ownerUserId, programId, now())) {
                TrainingMutationResult.Updated
            } else {
                TrainingMutationResult.NotFound
            }
        }

    suspend fun overview(
        ownerUserId: String,
        selectedWeekNumber: Int?,
    ): TrainingReadResult<WeekOverviewRecord> = dbQuery(db) {
        val program = repository.activeProgram(ownerUserId)
            ?: return@dbQuery TrainingReadResult.NoActiveProgram
        val weekNumbers = repository.weekNumbers(program.id)
        if (weekNumbers.isEmpty()) {
            val selected = selectedWeekNumber ?: 1
            if (selected != 1) {
                return@dbQuery TrainingReadResult.Invalid("Week $selected is not authored in this program.")
            }
            return@dbQuery TrainingReadResult.Found(
                WeekOverviewRecord(
                    program = program,
                    currentWeekNumber = 1,
                    selectedWeekNumber = 1,
                    availableWeekNumbers = listOf(1),
                    workouts = emptyList(),
                ),
            )
        }
        val current = repository.currentWeekNumber(program.id)
        val selected = selectedWeekNumber ?: current ?: weekNumbers.last()
        if (selected !in weekNumbers) {
            return@dbQuery TrainingReadResult.Invalid("Week $selected is not authored in this program.")
        }
        TrainingReadResult.Found(
            WeekOverviewRecord(
                program = program,
                currentWeekNumber = current,
                selectedWeekNumber = selected,
                availableWeekNumbers = weekNumbers,
                workouts = repository.weekWorkouts(program.id, selected),
            ),
        )
    }

    suspend fun workoutDetail(
        ownerUserId: String,
        weekNumber: Int,
        workoutId: UUID,
    ): TrainingReadResult<WorkoutDetailRecord> =
        if (weekNumber < 1) {
            TrainingReadResult.Invalid("'weekNumber' must be positive.")
        } else {
            dbQuery(db) {
                repository.workoutDetail(ownerUserId, weekNumber, workoutId)
                    ?.let { TrainingReadResult.Found(it) }
                    ?: TrainingReadResult.NotFound
            }
        }

    suspend fun putSet(
        ownerUserId: String,
        weekId: UUID,
        prescriptionId: UUID,
        setNumber: Int,
        input: SetInput,
    ): TrainingMutationResult {
        if (setNumber < 1) return TrainingMutationResult.Invalid("'setNumber' must be positive.")
        if (input.reps != null && input.reps < 0) {
            return TrainingMutationResult.Invalid("'reps' must not be negative.")
        }
        if (input.durationSeconds != null && input.durationSeconds < 0) {
            return TrainingMutationResult.Invalid("'durationSeconds' must not be negative.")
        }
        if (input.load != null && input.load.signum() < 0) {
            return TrainingMutationResult.Invalid("'load' must not be negative.")
        }
        if (input.rir != null && input.rir < 0) {
            return TrainingMutationResult.Invalid("'rir' must not be negative.")
        }
        if ((input.note?.length ?: 0) > 1000) {
            return TrainingMutationResult.Invalid("'note' must be 1000 characters or fewer.")
        }

        return dbQuery(db) {
            val executionType = repository.prescriptionExecutionType(ownerUserId, weekId, prescriptionId)
                ?: return@dbQuery TrainingMutationResult.NotFound
            val primaryValid = when (executionType) {
                "DURATION" -> input.durationSeconds != null && input.reps == null
                else -> input.reps != null && input.durationSeconds == null
            }
            if (!primaryValid) {
                val field = if (executionType == "DURATION") "durationSeconds" else "reps"
                return@dbQuery TrainingMutationResult.Invalid(
                    "Exactly '$field' must be supplied for this movement.",
                )
            }
            if (
                repository.upsertSet(
                    ownerUserId = ownerUserId,
                    weekId = weekId,
                    prescriptionId = prescriptionId,
                    setNumber = setNumber,
                    input = input.copy(note = input.note.normalized()),
                    performedOn = today(),
                    now = now(),
                )
            ) TrainingMutationResult.Updated else TrainingMutationResult.NotFound
        }
    }

    suspend fun deleteSet(
        ownerUserId: String,
        weekId: UUID,
        prescriptionId: UUID,
        setNumber: Int,
    ): TrainingMutationResult =
        if (setNumber < 1) {
            TrainingMutationResult.Invalid("'setNumber' must be positive.")
        } else {
            dbQuery(db) {
                if (repository.deleteSet(ownerUserId, weekId, prescriptionId, setNumber, now())) {
                    TrainingMutationResult.Updated
                } else {
                    TrainingMutationResult.NotFound
                }
            }
        }

    suspend fun updateSession(
        ownerUserId: String,
        weekId: UUID,
        performedOn: LocalDate,
        note: String?,
    ): TrainingMutationResult {
        if ((note?.length ?: 0) > 1000) {
            return TrainingMutationResult.Invalid("'note' must be 1000 characters or fewer.")
        }
        return dbQuery(db) {
            if (repository.updateSessionMetadata(ownerUserId, weekId, performedOn, note.normalized(), now())) {
                TrainingMutationResult.Updated
            } else {
                TrainingMutationResult.NotFound
            }
        }
    }

    suspend fun finish(ownerUserId: String, weekId: UUID): TrainingMutationResult = dbQuery(db) {
        if (repository.finishSession(ownerUserId, weekId, today(), now())) {
            TrainingMutationResult.Updated
        } else {
            TrainingMutationResult.NotFound
        }
    }

    suspend fun resume(ownerUserId: String, weekId: UUID): TrainingMutationResult = dbQuery(db) {
        if (repository.resumeSession(ownerUserId, weekId, now())) {
            TrainingMutationResult.Updated
        } else {
            TrainingMutationResult.NotFound
        }
    }

    suspend fun skip(ownerUserId: String, weekId: UUID): TrainingMutationResult = dbQuery(db) {
        if (repository.skipWeek(ownerUserId, weekId, now())) {
            TrainingMutationResult.Updated
        } else {
            TrainingMutationResult.NotFound
        }
    }

    suspend fun restore(ownerUserId: String, weekId: UUID): TrainingMutationResult = dbQuery(db) {
        if (repository.restoreWeek(ownerUserId, weekId)) {
            TrainingMutationResult.Updated
        } else {
            TrainingMutationResult.NotFound
        }
    }

    suspend fun createProgram(
        ownerUserId: String,
        input: ProgramAuthoringInput,
    ): ProgramCreateResult {
        validateProgram(input)?.let { return ProgramCreateResult.Invalid(it) }
        return try {
            val id = dbQuery(db) {
                repository.createProgram(ownerUserId, input.normalized(), now())
            }
            ProgramCreateResult.Created(id)
        } catch (ex: IllegalArgumentException) {
            ProgramCreateResult.Invalid(ex.message ?: "A selected exercise is invalid.")
        } catch (ex: ExposedSQLException) {
            if (ex.sqlState == "23505") {
                ProgramCreateResult.Conflict("The program contains duplicate positions, weeks, or exercise names.")
            } else {
                throw ex
            }
        }
    }

    suspend fun createWorkout(
        ownerUserId: String,
        programId: UUID,
        weekNumber: Int,
        input: WorkoutCreateInput,
    ): WorkoutCreateResult {
        if (weekNumber < 1) return WorkoutCreateResult.Invalid("'weekNumber' must be positive.")
        validateWorkout(input)?.let { return WorkoutCreateResult.Invalid(it) }
        return try {
            dbQuery(db) {
                val activeProgram = repository.activeProgram(ownerUserId)
                    ?: return@dbQuery WorkoutCreateResult.NotFound
                if (activeProgram.id != programId) return@dbQuery WorkoutCreateResult.NotFound

                val authoredWeeks = repository.weekNumbers(programId)
                val currentWeek = repository.currentWeekNumber(programId)
                    ?: if (authoredWeeks.isEmpty()) 1 else null
                if (currentWeek == null) {
                    return@dbQuery WorkoutCreateResult.Invalid(
                        "This program has no unresolved current week.",
                    )
                }
                if (weekNumber != currentWeek) {
                    return@dbQuery WorkoutCreateResult.Invalid(
                        "Workouts can only be added to current Week $currentWeek.",
                    )
                }

                repository.createWorkout(
                    ownerUserId,
                    programId,
                    weekNumber,
                    input.normalized(),
                )?.let(WorkoutCreateResult::Created) ?: WorkoutCreateResult.NotFound
            }
        } catch (ex: IllegalArgumentException) {
            WorkoutCreateResult.Invalid(ex.message ?: "A selected exercise is invalid.")
        } catch (ex: ExposedSQLException) {
            if (ex.sqlState == "23505") {
                WorkoutCreateResult.Conflict("The workout contains duplicate positions or exercise names.")
            } else {
                throw ex
            }
        }
    }

    suspend fun duplicateWeek(
        ownerUserId: String,
        workoutId: UUID,
        sourceWeek: Int,
        targetWeek: Int,
    ): TrainingMutationResult {
        if (sourceWeek < 1 || targetWeek < 1 || sourceWeek == targetWeek) {
            return TrainingMutationResult.Invalid("Source and target weeks must be different positive numbers.")
        }
        return try {
            dbQuery(db) {
                if (repository.duplicateWeek(ownerUserId, workoutId, sourceWeek, targetWeek) != null) {
                    TrainingMutationResult.Updated
                } else {
                    TrainingMutationResult.NotFound
                }
            }
        } catch (ex: ExposedSQLException) {
            if (ex.sqlState == "23505") {
                TrainingMutationResult.Invalid("Week $targetWeek already exists for this workout.")
            } else {
                throw ex
            }
        }
    }

    private fun validateProgram(input: ProgramAuthoringInput): String? {
        if (input.name.isBlank()) return "Program name must not be blank."
        if (input.name.length > 160) return "Program name must be 160 characters or fewer."
        input.workouts.forEach { workout ->
            if (workout.name.isBlank()) return "Workout names must not be blank."
            if (workout.weeks.isEmpty()) return "Each workout needs at least one authored week."
            if (workout.weeks.map { it.weekNumber }.distinct().size != workout.weeks.size) {
                return "Week numbers must be unique within a workout."
            }
            workout.weeks.forEach { week ->
                if (week.weekNumber < 1) return "Week numbers must be positive."
                if (week.groups.isEmpty()) return "Each authored week needs at least one group."
                week.groups.forEach { group ->
                    if (group.label.isBlank()) return "Group labels must not be blank."
                    if (group.kind !in setOf("STRAIGHT_SET", "SUPERSET")) {
                        return "Group kind must be STRAIGHT_SET or SUPERSET."
                    }
                    if (group.prescriptions.isEmpty()) return "Each group needs at least one movement."
                    group.prescriptions.forEach { prescription ->
                        if (prescription.exerciseName.isBlank()) return "Movement names must not be blank."
                        if ((prescription.exerciseId != null) == prescription.createExercise) {
                            return "Choose an existing exercise or explicitly create a new one."
                        }
                        if (prescription.executionType !in setOf("REPS", "REPS_PER_SIDE", "DURATION")) {
                            return "Every movement needs a valid execution type."
                        }
                    }
                }
            }
        }
        return null
    }

    private fun validateWorkout(input: WorkoutCreateInput): String? {
        if (input.name.isBlank()) return "Workout name must not be blank."
        if (input.name.length > 160) return "Workout name must be 160 characters or fewer."
        if (input.groups.isEmpty()) return "Add at least one group."
        input.groups.forEach { group ->
            if (group.label.isBlank()) return "Group labels must not be blank."
            if (group.kind !in setOf("STRAIGHT_SET", "SUPERSET")) {
                return "Group kind must be STRAIGHT_SET or SUPERSET."
            }
            if (group.prescriptions.isEmpty()) return "Each group needs at least one movement."
            group.prescriptions.forEach { prescription ->
                if (prescription.exerciseName.isBlank()) return "Movement names must not be blank."
                if ((prescription.exerciseId != null) == prescription.createExercise) {
                    return "Choose an existing exercise or explicitly create a new one."
                }
                if (prescription.executionType !in setOf("REPS", "REPS_PER_SIDE", "DURATION")) {
                    return "Every movement needs a valid execution type."
                }
            }
        }
        return null
    }

    private fun ProgramAuthoringInput.normalized() = copy(
        name = name.trim(),
        note = note.normalized(),
        workouts = workouts.map { workout ->
            workout.copy(
                name = workout.name.trim(),
                note = workout.note.normalized(),
                weeks = workout.weeks.map { week ->
                    week.copy(groups = week.groups.map { group ->
                        group.copy(
                            label = group.label.trim(),
                            prescriptions = group.prescriptions.map { prescription ->
                                prescription.copy(
                                    exerciseName = prescription.exerciseName.trim(),
                                    demoUrl = prescription.demoUrl.normalized(),
                                    sets = prescription.sets.normalized(),
                                    rest = prescription.rest.normalized(),
                                    reps = prescription.reps.normalized(),
                                    load = prescription.load.normalized(),
                                    rir = prescription.rir.normalized(),
                                    tempo = prescription.tempo.normalized(),
                                    note = prescription.note.normalized(),
                                )
                            },
                        )
                    })
                },
            )
        },
    )

    private fun WorkoutCreateInput.normalized() = copy(
        name = name.trim(),
        note = note.normalized(),
        groups = groups.map { group ->
            group.copy(
                label = group.label.trim(),
                prescriptions = group.prescriptions.map { prescription ->
                    prescription.copy(
                        exerciseName = prescription.exerciseName.trim(),
                        demoUrl = prescription.demoUrl.normalized(),
                        sets = prescription.sets.normalized(),
                        rest = prescription.rest.normalized(),
                        reps = prescription.reps.normalized(),
                        load = prescription.load.normalized(),
                        rir = prescription.rir.normalized(),
                        tempo = prescription.tempo.normalized(),
                        note = prescription.note.normalized(),
                    )
                },
            )
        },
    )

    private fun now(): OffsetDateTime = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
    private fun today(): LocalDate = LocalDate.now(clock.withZone(zoneId))
}

private fun String?.normalized(): String? = this?.trim()?.takeIf(String::isNotEmpty)
