package me.gpipi.training.imports

import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.Serializable

data class TrainingImportHeader(
    val id: UUID,
    val ownerUserId: String,
    val targetType: String,
    val programId: UUID?,
    val programName: String,
    val newProgramNote: String?,
    val newProgramStartsOn: LocalDate?,
    val newProgramConfirmedAt: OffsetDateTime?,
    val spreadsheetId: String,
    val spreadsheetTitle: String,
    val selectedWeekNumber: Int?,
    val state: String,
    val errorDetail: String?,
    val createdAt: OffsetDateTime,
)

data class TrainingImportTabRecord(
    val id: UUID,
    val importId: UUID,
    val googleSheetId: Long,
    val tabTitle: String,
    val decision: String?,
    val targetWorkoutId: UUID?,
    val newWorkoutName: String?,
    val position: Int,
)

data class TrainingImportWeekRecord(
    val id: UUID,
    val importTabId: UUID,
    val weekNumber: Int,
    val startRow: Int,
    val endRow: Int,
    val executionBoundaryColumn: Int,
    val executionHeaderAddress: String,
    val executionHeaderValue: String,
    val decision: String?,
    val extractedDraft: String?,
    val extractionContractVersion: String?,
    val extractionModel: String?,
    val sourceSnapshot: String?,
    val sourceHash: String?,
)

data class TrainingImportMatchRecord(
    val importWeekId: UUID,
    val sourceMovementKey: String,
    val sourceText: String,
    val decision: String?,
    val exerciseId: UUID?,
    val newExerciseName: String?,
    val executionType: String?,
    val rememberAsAlias: Boolean,
)

data class WorkoutOption(val id: UUID, val name: String)
data class LinkedTrainingSheet(val spreadsheetId: String, val spreadsheetTitle: String)

@Serializable
data class StartTrainingImportRequest(val spreadsheetId: String)

@Serializable
data class SaveNewProgramDraftRequest(
    val name: String,
    val note: String? = null,
    val startsOn: String? = null,
)

@Serializable
data class ChooseTrainingWeekRequest(val weekNumber: Int)

@Serializable
data class SaveTrainingMappingRequest(val tabs: List<TrainingTabMappingRequest>)

@Serializable
data class TrainingTabMappingRequest(
    val googleSheetId: Long,
    val decision: String,
    val targetWorkoutId: String? = null,
    val newWorkoutName: String? = null,
    val startRow: Int? = null,
    val endRow: Int? = null,
    val executionBoundaryColumn: Int? = null,
    val executionHeaderAddress: String? = null,
    val executionHeaderValue: String? = null,
)

@Serializable
data class SaveTrainingReviewRequest(val workouts: List<ReviewedWorkoutDraft>)

@Serializable
data class ReviewedWorkoutDraft(
    val importWeekId: String,
    val groups: List<ReviewedTrainingGroup>,
)

@Serializable
data class ReviewedTrainingGroup(
    val label: String,
    val labelAddress: String,
    val kind: String,
    val prescriptions: List<ReviewedTrainingPrescription>,
)

@Serializable
data class ReviewedTrainingPrescription(
    val movement: String,
    val movementAddress: String,
    val demoUrl: String? = null,
    val sets: String? = null,
    val rest: String? = null,
    val reps: String? = null,
    val load: String? = null,
    val rir: String? = null,
    val tempo: String? = null,
    val note: String? = null,
    val sourceCells: ExtractedSourceCells,
    val decision: String,
    val exerciseId: String? = null,
    val newExerciseName: String? = null,
    val executionType: String? = null,
    val rememberAsAlias: Boolean = true,
)

@Serializable
data class GoogleConnectionStatusResponse(
    val configured: Boolean,
    val connected: Boolean,
    val connectedAt: String? = null,
    val missingConfiguration: List<String> = emptyList(),
)

@Serializable data class GoogleConnectResponse(val authorizationUrl: String)

@Serializable
data class GooglePickerTokenResponse(
    val accessToken: String,
    val expiresIn: Long,
    val apiKey: String,
    val appId: String,
)

@Serializable
data class StartTrainingImportResponse(
    val importId: String,
    val spreadsheetTitle: String,
    val availableWeekNumbers: List<Int>,
    val replacesLinkedSheet: Boolean,
    val targetType: String,
    val suggestedProgramName: String? = null,
)


@Serializable
data class TrainingWeekChoiceResponse(
    val importId: String,
    val selectedWeekNumber: Int,
    val tabs: List<TrainingRangeProposalResponse>,
    val workouts: List<WorkoutOptionResponse>,
)

@Serializable
data class TrainingRangeProposalResponse(
    val googleSheetId: Long,
    val tabTitle: String,
    val present: Boolean,
    val startRow: Int? = null,
    val endRow: Int? = null,
    val executionBoundaryColumn: Int? = null,
    val executionHeaderAddress: String? = null,
    val executionHeaderValue: String? = null,
    val boundaryAmbiguous: Boolean,
)

@Serializable data class WorkoutOptionResponse(val id: String, val name: String)

@Serializable
data class TrainingImportResponse(
    val id: String,
    val targetType: String,
    val programId: String?,
    val programName: String,
    val programNote: String? = null,
    val programStartsOn: String? = null,
    val spreadsheetTitle: String,
    val selectedWeekNumber: Int?,
    val state: String,
    val errorDetail: String? = null,
    val tabs: List<TrainingImportTabResponse>,
)

@Serializable
data class TrainingImportTabResponse(
    val importWeekId: String? = null,
    val googleSheetId: Long,
    val tabTitle: String,
    val decision: String? = null,
    val targetWorkoutId: String? = null,
    val newWorkoutName: String? = null,
    val startRow: Int? = null,
    val endRow: Int? = null,
    val executionBoundaryColumn: Int? = null,
    val extractionModel: String? = null,
    val groups: List<TrainingReviewGroupResponse> = emptyList(),
)

@Serializable
data class TrainingReviewGroupResponse(
    val label: String,
    val labelAddress: String,
    val kind: String,
    val prescriptions: List<TrainingReviewPrescriptionResponse>,
)

@Serializable
data class TrainingReviewPrescriptionResponse(
    val movement: String,
    val movementAddress: String,
    val executionTypeProposal: String? = null,
    val demoUrl: String? = null,
    val sets: String? = null,
    val rest: String? = null,
    val reps: String? = null,
    val load: String? = null,
    val rir: String? = null,
    val tempo: String? = null,
    val note: String? = null,
    val sourceCells: ExtractedSourceCells,
    val decision: String? = null,
    val exerciseId: String? = null,
    val newExerciseName: String? = null,
    val executionType: String? = null,
    val rememberAsAlias: Boolean = true,
)
