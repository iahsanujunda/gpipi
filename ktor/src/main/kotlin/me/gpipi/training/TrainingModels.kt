package me.gpipi.training

import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

data class TrainingProgramRecord(
    val id: UUID,
    val name: String,
    val note: String?,
    val startsOn: LocalDate?,
    val active: Boolean,
)

data class ExerciseCatalogRecord(
    val id: UUID,
    val name: String,
    val demoUrl: String?,
    val aliases: List<String>,
)

data class WeekWorkoutRecord(
    val weekId: UUID,
    val workoutId: UUID,
    val workoutName: String,
    val workoutPosition: Int,
    val status: String,
    val sessionId: UUID?,
    val performedOn: LocalDate?,
    val setCount: Int,
    val updatedAt: OffsetDateTime?,
)

data class WeekOverviewRecord(
    val program: TrainingProgramRecord,
    val currentWeekNumber: Int?,
    val selectedWeekNumber: Int,
    val availableWeekNumbers: List<Int>,
    val workouts: List<WeekWorkoutRecord>,
)

data class PerformedSetRecord(
    val id: UUID,
    val setNumber: Int,
    val reps: Int?,
    val durationSeconds: Int?,
    val load: BigDecimal?,
    val rir: Int?,
    val note: String?,
    val targetReps: String?,
    val targetLoad: String?,
    val targetRir: String?,
    val targetTempo: String?,
)

data class ExerciseExecutionRecord(
    val prescriptionId: UUID,
    val performedExerciseId: UUID?,
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
    val sets: List<PerformedSetRecord>,
)

data class WorkoutGroupExecutionRecord(
    val position: Int,
    val label: String,
    val kind: String,
    val exercises: List<ExerciseExecutionRecord>,
)

data class TrainingSessionRecord(
    val id: UUID,
    val performedOn: LocalDate,
    val status: String,
    val note: String?,
    val updatedAt: OffsetDateTime,
    val completedAt: OffsetDateTime?,
)

data class WorkoutDetailRecord(
    val program: TrainingProgramRecord,
    val currentWeekNumber: Int?,
    val weekId: UUID,
    val weekNumber: Int,
    val skipped: Boolean,
    val workoutId: UUID,
    val workoutName: String,
    val workoutNote: String?,
    val session: TrainingSessionRecord?,
    val groups: List<WorkoutGroupExecutionRecord>,
)

data class SetInput(
    val reps: Int?,
    val durationSeconds: Int?,
    val load: BigDecimal?,
    val rir: Int?,
    val note: String?,
)

data class PrescriptionAuthoringInput(
    val exerciseName: String,
    val exerciseId: UUID? = null,
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

data class GroupAuthoringInput(
    val label: String,
    val kind: String,
    val prescriptions: List<PrescriptionAuthoringInput>,
)

data class WeekAuthoringInput(
    val weekNumber: Int,
    val groups: List<GroupAuthoringInput>,
)

data class WorkoutAuthoringInput(
    val name: String,
    val note: String? = null,
    val weeks: List<WeekAuthoringInput>,
)

data class ProgramAuthoringInput(
    val name: String,
    val note: String? = null,
    val startsOn: LocalDate? = null,
    val workouts: List<WorkoutAuthoringInput>,
)

sealed interface TrainingMutationResult {
    data object Updated : TrainingMutationResult
    data object NotFound : TrainingMutationResult
    data class Invalid(val message: String) : TrainingMutationResult
}
