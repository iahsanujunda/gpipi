package me.gpipi.training.writes

import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.gpipi.training.google.SheetValue

const val TRAINING_WRITE_MATCH_CONTRACT_VERSION = "training_write_match_v1"

data class WriteSource(
    val ownerUserId: String,
    val programId: UUID,
    val programName: String,
    val sessionId: UUID,
    val weekId: UUID,
    val sessionStatus: String,
    val weekNumber: Int,
    val workoutName: String,
    val executionUpdatedAt: OffsetDateTime?,
    val movements: List<WriteSourceMovement>,
)

data class WriteSourceMovement(
    val performedExerciseId: UUID,
    val prescriptionId: UUID,
    val position: Int,
    val groupLabel: String,
    val groupKind: String,
    val exerciseName: String,
    val executionType: String,
    val targetSets: String?,
    val targetRest: String?,
    val targetReps: String?,
    val targetLoad: String?,
    val targetRir: String?,
    val targetTempo: String?,
    val targetNote: String?,
    val sets: List<WriteSourceSet>,
)

data class WriteSourceSet(
    val id: UUID,
    val setNumber: Int,
    val reps: Int?,
    val durationSeconds: Int?,
    val load: String?,
    val rir: Int?,
    val deleted: Boolean,
)

@Serializable
data class WriteDiscoverySnapshot(
    val spreadsheetTitle: String,
    val availableWeekNumbers: List<Int> = emptyList(),
    val tabs: List<WriteDiscoveryTab> = emptyList(),
)

@Serializable
data class WriteDiscoveryTab(
    val key: String,
    val googleSheetId: Long,
    val title: String,
    val position: Int,
    val availableWeekNumbers: List<Int>,
)

@Serializable
data class WriteSheetCell(
    val address: String,
    val row: Int,
    val column: Int,
    val text: String,
)

@Serializable
data class WriteExecutionLayoutCell(
    val address: String,
    val row: Int,
    val column: Int,
    val label: String,
)

@Serializable
data class WriteCandidateTab(
    val key: String,
    val googleSheetId: Long,
    val title: String,
    val startRow: Int,
    val endRow: Int,
    val weekHeaderAddress: String,
    val weekHeaderValue: String,
    val executionBoundaryColumn: Int,
    val executionHeaderAddress: String,
    val executionHeaderValue: String,
    val prescriptionCells: List<WriteSheetCell>,
    val executionLayout: List<WriteExecutionLayoutCell>,
)

@Serializable
data class WriteMatchPayload(
    @SerialName("contract_version") val contractVersion: String,
    @SerialName("source_workout") val sourceWorkout: WriteMatchSourceWorkout,
    val candidates: List<WriteMatchCandidate>,
)

@Serializable
data class WriteMatchSourceWorkout(
    val key: String,
    val name: String,
    @SerialName("source_week_number") val sourceWeekNumber: Int,
    val movements: List<WriteMatchSourceMovement>,
)

@Serializable
data class WriteMatchSourceMovement(
    val key: String,
    val name: String,
    @SerialName("group_label") val groupLabel: String,
    @SerialName("group_kind") val groupKind: String,
    val position: Int,
    @SerialName("execution_type") val executionType: String,
    val sets: String?,
    val rest: String?,
    val reps: String?,
    val load: String?,
    val rir: String?,
    val tempo: String?,
    val note: String?,
)

@Serializable
data class WriteMatchCandidate(
    val key: String,
    val title: String,
    @SerialName("selected_range") val selectedRange: String,
    val cells: List<WriteSheetCell>,
)

@Serializable
data class WriteMatchOutput(
    @SerialName("matched_tab_key") val matchedTabKey: String? = null,
    val movements: List<WriteMatchMovementOutput>,
)

@Serializable
data class WriteMatchMovementOutput(
    @SerialName("source_movement_key") val sourceMovementKey: String,
    @SerialName("sheet_movement_address") val sheetMovementAddress: String? = null,
    @SerialName("sheet_movement_text") val sheetMovementText: String? = null,
)

@Serializable
data class WriteMatchingSnapshot(
    val candidates: List<WriteCandidateTab>,
    val input: WriteMatchPayload? = null,
    val output: WriteMatchOutput,
    val provenance: WriteResolvedProvenanceSnapshot? = null,
)

@Serializable
data class WriteResolvedProvenanceSnapshot(
    val tabTitle: String,
    val googleSheetId: Long,
    val startRow: Int,
    val endRow: Int,
    val sourceHash: String,
)

data class WriteImportProvenance(
    val spreadsheetId: String,
    val spreadsheetTitle: String,
    val googleSheetId: Long,
    val tabTitle: String,
    val startRow: Int,
    val endRow: Int,
    val executionBoundaryColumn: Int,
    val executionHeaderAddress: String,
    val executionHeaderValue: String,
    val sourceHash: String,
    val movements: List<WriteImportMovementProvenance>,
)

data class WriteImportProvenanceLookup(
    val provenance: WriteImportProvenance? = null,
    val resolutionFailure: String? = null,
)

data class WriteImportMovementProvenance(
    val performedExerciseId: UUID,
    val position: Int,
    val movementAddress: String,
    val movementText: String,
)

data class WriteAttemptRecord(
    val id: UUID,
    val programId: UUID,
    val sessionId: UUID,
    val sourceWeekNumber: Int,
    val sourceWorkoutName: String,
    val spreadsheetId: String,
    val spreadsheetTitle: String,
    val availableWeekNumbers: List<Int>,
    val discoverySnapshot: String,
    val targetWeekNumber: Int?,
    val targetGoogleSheetId: Long?,
    val targetTabTitle: String?,
    val targetWeekStartRow: Int?,
    val targetWeekEndRow: Int?,
    val targetWeekHeaderAddress: String?,
    val targetWeekHeaderValue: String?,
    val executionBoundaryColumn: Int?,
    val executionHeaderAddress: String?,
    val executionHeaderValue: String?,
    val matchingContractVersion: String?,
    val matchingModel: String?,
    val matchingSourceSnapshot: String?,
    val matchingSourceHash: String?,
    val executionProjectionHash: String?,
    val payloadHash: String?,
    val status: String,
    val apiCalled: Boolean,
    val createdAt: OffsetDateTime,
    val finishedAt: OffsetDateTime?,
    val detail: String?,
)

data class WriteMovementRecord(
    val id: UUID,
    val performedExerciseId: UUID,
    val position: Int,
    val sheetMovementAddress: String,
    val sheetMovementText: String,
    val matchSource: String,
    val confirmed: Boolean,
)

data class WriteCellRecord(
    val id: UUID,
    val movementId: UUID,
    val performedExerciseId: UUID,
    val performedSetId: UUID?,
    val setNumber: Int,
    val field: String,
    val row: Int,
    val column: Int,
    val address: String,
    val observedValue: SheetValue?,
    val observedDisplay: String?,
    val prewriteValue: SheetValue?,
    val prewriteDisplay: String?,
    val action: String,
    val proposedValue: SheetValue?,
    val verifiedValue: SheetValue?,
    val verifiedDisplay: String?,
)

data class PreparedCell(
    val performedExerciseId: UUID,
    val performedSetId: UUID?,
    val setNumber: Int,
    val field: String,
    val row: Int,
    val column: Int,
    val address: String,
    val observedValue: SheetValue?,
    val observedDisplay: String?,
    val action: String,
    val proposedValue: SheetValue?,
)

@Serializable
data class StartTrainingWriteRequest(val selectionToken: String? = null)

@Serializable
data class ChooseTrainingWriteWeekRequest(val weekNumber: Int)

@Serializable
data class ChooseTrainingWriteTabRequest(val tabKey: String)

@Serializable
data class ConfirmTrainingWriteMatchesRequest(
    val tabKey: String,
    val movements: List<ConfirmedTrainingWriteMovement>,
)

@Serializable
data class ConfirmedTrainingWriteMovement(
    val sourceMovementKey: String,
    val sheetMovementAddress: String,
)

@Serializable
data class TrainingWriteDestinationResponse(
    val sessionId: String,
    val linkedSheetTitle: String? = null,
    val googleConnected: Boolean,
)

@Serializable
data class TrainingWriteCandidateRowResponse(
    val address: String,
    val text: String,
)

@Serializable
data class TrainingWriteCandidateTabResponse(
    val key: String,
    val title: String,
    val rows: List<TrainingWriteCandidateRowResponse>,
)

@Serializable
data class TrainingWriteTabResponse(
    val key: String,
    val title: String,
)

@Serializable
data class TrainingWriteMatchResponse(
    val sourceMovementKey: String,
    val sourceName: String,
    val sourcePosition: Int,
    val sheetMovementAddress: String? = null,
    val sheetMovementText: String? = null,
    val matchSource: String? = null,
    val confirmed: Boolean = false,
)

@Serializable
data class TrainingWritePreviewCellResponse(
    val setNumber: Int,
    val field: String,
    val address: String,
    val current: String? = null,
    val proposed: String? = null,
    val action: String,
)

@Serializable
data class TrainingWritePreviewMovementResponse(
    val sourceMovementKey: String,
    val sourceName: String,
    val sheetMovementAddress: String,
    val cells: List<TrainingWritePreviewCellResponse>,
)

@Serializable
data class TrainingWriteResponse(
    val id: String,
    val sessionId: String,
    val sourceWeekNumber: Int,
    val sourceWorkoutName: String,
    val spreadsheetTitle: String,
    val availableWeekNumbers: List<Int>,
    val targetWeekNumber: Int? = null,
    val targetTabTitle: String? = null,
    val selectedTabKey: String? = null,
    val status: String,
    val detail: String? = null,
    val availableTabs: List<TrainingWriteTabResponse> = emptyList(),
    val candidateTabs: List<TrainingWriteCandidateTabResponse> = emptyList(),
    val matches: List<TrainingWriteMatchResponse> = emptyList(),
    val preview: List<TrainingWritePreviewMovementResponse> = emptyList(),
    val cellCount: Int = 0,
    val finishedAt: String? = null,
)

@Serializable
data class TrainingWriteStatusResponse(
    val state: String,
    val sheetTitle: String? = null,
    val targetWeekNumber: Int? = null,
    val finishedAt: String? = null,
    val attemptId: String? = null,
)

sealed interface TrainingWriteResult<out T> {
    data class Ok<T>(val value: T) : TrainingWriteResult<T>
    data object NotFound : TrainingWriteResult<Nothing>
    data class Invalid(val message: String) : TrainingWriteResult<Nothing>
    data class Conflict(val message: String) : TrainingWriteResult<Nothing>
    data class Unavailable(val message: String) : TrainingWriteResult<Nothing>
}
