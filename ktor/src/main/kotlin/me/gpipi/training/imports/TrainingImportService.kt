package me.gpipi.training.imports

import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.gpipi.config.dbQuery
import me.gpipi.training.google.GoogleConnectionService
import me.gpipi.training.google.TrainingSheetGateway
import me.gpipi.training.google.proposalsFor
import org.jetbrains.exposed.v1.jdbc.Database

sealed interface TrainingImportResult<out T> {
    data class Ok<T>(val value: T) : TrainingImportResult<T>
    data object NotFound : TrainingImportResult<Nothing>
    data class Invalid(val message: String) : TrainingImportResult<Nothing>
    data class Conflict(val message: String) : TrainingImportResult<Nothing>
}

class TrainingImportService(
    private val db: Database,
    private val repository: TrainingImportRepository,
    private val google: GoogleConnectionService,
    private val sheets: TrainingSheetGateway,
    private val extractor: TrainingPrescriptionExtractionService,
    private val clock: Clock = Clock.systemUTC(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true },
) {
    suspend fun start(
        ownerUserId: String,
        programId: UUID,
        spreadsheetId: String,
    ): TrainingImportResult<StartTrainingImportResponse> {
        if (!spreadsheetId.matches(Regex("[A-Za-z0-9_-]{10,200}"))) {
            return TrainingImportResult.Invalid("Choose a valid Google Sheet through Picker.")
        }
        val owned = dbQuery(db) { repository.ownsProgram(ownerUserId, programId) }
        if (!owned) return TrainingImportResult.NotFound
        val linkedSheet = dbQuery(db) { repository.linkedSheet(ownerUserId, programId) }
        val importId = dbQuery(db) {
            repository.createReadingImport(ownerUserId, programId, spreadsheetId.trim(), now())
        }
        return try {
            val access = google.accessToken(ownerUserId)
            val discovery = sheets.discover(access.accessToken, spreadsheetId.trim())
            if (discovery.tabs.isEmpty()) throw IllegalArgumentException("The selected spreadsheet has no tabs.")
            if (discovery.weekNumbers.isEmpty()) {
                throw IllegalArgumentException("No visible Week or Minggu labels were found in the selected Sheet.")
            }
            dbQuery(db) { repository.completeDiscovery(importId, discovery.spreadsheetTitle, discovery.tabs, now()) }
            TrainingImportResult.Ok(
                StartTrainingImportResponse(
                    importId = importId.toString(),
                    spreadsheetTitle = discovery.spreadsheetTitle,
                    availableWeekNumbers = discovery.weekNumbers,
                    replacesLinkedSheet = linkedSheet != null && linkedSheet.spreadsheetId != spreadsheetId.trim(),
                ),
            )
        } catch (ex: Exception) {
            val detail = when (ex) {
                is IllegalArgumentException -> ex.message
                else -> "The selected Google Sheet could not be read. Reconnect or choose it again."
            }.orEmpty().take(500)
            dbQuery(db) { repository.transition(importId, "FAILED", detail, now()) }
            TrainingImportResult.Invalid(detail)
        }
    }

    suspend fun chooseWeek(
        ownerUserId: String,
        importId: UUID,
        weekNumber: Int,
    ): TrainingImportResult<TrainingWeekChoiceResponse> {
        if (weekNumber < 1) return TrainingImportResult.Invalid("Week number must be positive.")
        val header = dbQuery(db) { repository.header(ownerUserId, importId) }
            ?: return TrainingImportResult.NotFound
        if (header.state in setOf("APPLIED", "CANCELLED")) {
            return TrainingImportResult.Conflict("This import is already ${header.state.lowercase()}.")
        }
        val access = google.accessToken(ownerUserId)
        val discovery = sheets.discover(access.accessToken, header.spreadsheetId)
        if (weekNumber !in discovery.weekNumbers) {
            return TrainingImportResult.Invalid("Week $weekNumber is no longer present in the selected Sheet.")
        }
        dbQuery(db) { repository.chooseWeek(importId, weekNumber, now()) }
        val workouts = header.programId?.let { programId ->
            dbQuery(db) { repository.workouts(ownerUserId, programId) }
        }.orEmpty()
        return TrainingImportResult.Ok(
            TrainingWeekChoiceResponse(
                importId = importId.toString(),
                selectedWeekNumber = weekNumber,
                tabs = discovery.proposalsFor(weekNumber).map { proposal ->
                    TrainingRangeProposalResponse(
                        googleSheetId = proposal.sheetId,
                        tabTitle = proposal.tabTitle,
                        present = proposal.present,
                        startRow = proposal.startRow,
                        endRow = proposal.endRow,
                        executionBoundaryColumn = proposal.executionBoundaryColumn,
                        executionHeaderAddress = proposal.executionHeaderAddress,
                        executionHeaderValue = proposal.executionHeaderValue,
                        boundaryAmbiguous = proposal.boundaryAmbiguous,
                    )
                },
                workouts = workouts.map { WorkoutOptionResponse(it.id.toString(), it.name) },
            ),
        )
    }

    suspend fun saveMapping(
        ownerUserId: String,
        importId: UUID,
        request: SaveTrainingMappingRequest,
    ): TrainingImportResult<TrainingImportResponse> {
        val header = dbQuery(db) { repository.header(ownerUserId, importId) }
            ?: return TrainingImportResult.NotFound
        val selectedWeek = header.selectedWeekNumber
            ?: return TrainingImportResult.Invalid("Choose one week before confirming its workout ranges.")
        if (header.state in setOf("APPLIED", "CANCELLED")) {
            return TrainingImportResult.Conflict("This import is already ${header.state.lowercase()}.")
        }
        val storedTabs = dbQuery(db) { repository.tabs(importId) }
        if (request.tabs.map { it.googleSheetId }.toSet() != storedTabs.map { it.googleSheetId }.toSet()) {
            return TrainingImportResult.Invalid("Confirm or exclude every Sheet tab.")
        }
        val workoutIds = header.programId?.let { programId ->
            dbQuery(db) { repository.workouts(ownerUserId, programId) }
        }.orEmpty().map { it.id }.toSet()
        val bySheet = storedTabs.associateBy { it.googleSheetId }
        val resolved = mutableListOf<ResolvedTabMapping>()
        request.tabs.forEach { input ->
            val tab = bySheet.getValue(input.googleSheetId)
            if (input.decision !in setOf("WORKOUT", "EXCLUDE")) {
                return TrainingImportResult.Invalid("Every tab decision must be WORKOUT or EXCLUDE.")
            }
            if (input.decision == "WORKOUT") {
                val targetId = input.targetWorkoutId?.let {
                    runCatching { UUID.fromString(it) }.getOrNull()
                        ?: return TrainingImportResult.Invalid("A target workout ID is invalid.")
                }
                val newName = input.newWorkoutName?.trim()?.takeIf(String::isNotEmpty)
                if ((targetId != null) == (newName != null)) {
                    return TrainingImportResult.Invalid("Map ${tab.tabTitle} to an existing workout or create one new workout.")
                }
                if (targetId != null && targetId !in workoutIds) {
                    return TrainingImportResult.Invalid("The selected target workout is not in this program.")
                }
                val start = input.startRow
                val end = input.endRow
                val boundary = input.executionBoundaryColumn
                if (start == null || end == null || start < 1 || end < start) {
                    return TrainingImportResult.Invalid("Confirm a valid row range for ${tab.tabTitle}.")
                }
                if (boundary == null || boundary <= 1) {
                    return TrainingImportResult.Invalid("Confirm the first execution column for ${tab.tabTitle}.")
                }
                if (input.executionHeaderAddress.isNullOrBlank() || input.executionHeaderValue.isNullOrBlank()) {
                    return TrainingImportResult.Invalid("Confirm the execution header for ${tab.tabTitle}.")
                }
                val headerMatch = Regex("^([A-Z]+)([1-9]\\d*)$").matchEntire(input.executionHeaderAddress.uppercase())
                    ?: return TrainingImportResult.Invalid("Use an A1 address for the execution header in ${tab.tabTitle}.")
                val expectedColumn = me.gpipi.training.google.columnName(boundary)
                val headerRow = headerMatch.groupValues[2].toInt()
                if (headerMatch.groupValues[1] != expectedColumn || headerRow !in start..end) {
                    return TrainingImportResult.Invalid("The execution header for ${tab.tabTitle} must be inside the confirmed range and boundary column.")
                }
                resolved += ResolvedTabMapping(
                    tabId = tab.id,
                    decision = input.decision,
                    targetWorkoutId = targetId,
                    newWorkoutName = newName,
                    startRow = start,
                    endRow = end,
                    executionBoundaryColumn = boundary,
                    executionHeaderAddress = input.executionHeaderAddress.uppercase(),
                    executionHeaderValue = input.executionHeaderValue,
                )
            } else {
                resolved += ResolvedTabMapping(tab.id, input.decision, null, null, null, null, null, null, null)
            }
        }
        if (resolved.none { it.decision == "WORKOUT" }) {
            return TrainingImportResult.Invalid("Include at least one workout tab for Week $selectedWeek.")
        }
        dbQuery(db) { repository.saveMapping(importId, selectedWeek, resolved, now()) }
        return get(ownerUserId, importId)
    }

    suspend fun extract(ownerUserId: String, importId: UUID): TrainingImportResult<TrainingImportResponse> {
        val header = dbQuery(db) { repository.header(ownerUserId, importId) }
            ?: return TrainingImportResult.NotFound
        if (header.state in setOf("APPLIED", "CANCELLED")) {
            return TrainingImportResult.Conflict("This import is already ${header.state.lowercase()}.")
        }
        val selectedWeek = header.selectedWeekNumber
            ?: return TrainingImportResult.Invalid("Choose a week before extraction.")
        val tabs = dbQuery(db) { repository.tabs(importId) }
        val weeks = dbQuery(db) { repository.weeks(importId) }
        if (weeks.isEmpty() || tabs.any { it.decision == null }) {
            return TrainingImportResult.Invalid("Confirm every tab and included workout range before extraction.")
        }
        dbQuery(db) { repository.markExtracting(importId, now()) }
        return try {
            val access = google.accessToken(ownerUserId)
            for (week in weeks) {
                val tab = tabs.single { it.id == week.importTabId }
                val grid = sheets.readSelectedRange(
                    accessToken = access.accessToken,
                    spreadsheetId = header.spreadsheetId,
                    sheetId = tab.googleSheetId,
                    tabTitle = tab.tabTitle,
                    startRow = week.startRow,
                    endRow = week.endRow,
                    executionBoundaryColumn = week.executionBoundaryColumn,
                )
                val observedHeader = grid.cells.singleOrNull { it.address == week.executionHeaderAddress }
                if (observedHeader?.column != week.executionBoundaryColumn || observedHeader.display != week.executionHeaderValue) {
                    throw TrainingExtractionException(
                        "The execution header in ${tab.tabTitle} changed. Confirm the Week $selectedWeek range again.",
                    )
                }
                val result = extractor.extract(
                    grid = grid,
                    selectedWeekNumber = selectedWeek,
                    startRow = week.startRow,
                    endRow = week.endRow,
                    executionBoundaryColumn = week.executionBoundaryColumn,
                )
                dbQuery(db) {
                    repository.saveExtraction(
                        weekId = week.id,
                        draft = json.encodeToString(result.draft),
                        model = result.model,
                        snapshot = result.sourceSnapshot,
                        hash = result.sourceHash,
                    )
                    repository.initializeMatches(week.id, result.draft)
                    repository.proposeExactMatches(ownerUserId, week.id, result.draft)
                }
            }
            dbQuery(db) { repository.transition(importId, "REVIEW", null, now()) }
            get(ownerUserId, importId)
        } catch (ex: Exception) {
            val detail = when (ex) {
                is TrainingExtractionException -> ex.message
                else -> "Week $selectedWeek extraction failed. Check the confirmed range and try again."
            }.orEmpty().take(500)
            dbQuery(db) { repository.transition(importId, "FAILED", detail, now()) }
            TrainingImportResult.Invalid(detail)
        }
    }

    suspend fun saveReview(
        ownerUserId: String,
        importId: UUID,
        request: SaveTrainingReviewRequest,
    ): TrainingImportResult<TrainingImportResponse> {
        val header = dbQuery(db) { repository.header(ownerUserId, importId) }
            ?: return TrainingImportResult.NotFound
        if (header.state != "REVIEW") return TrainingImportResult.Conflict("Extract the selected week before reviewing it.")
        val weeks = dbQuery(db) { repository.weeks(importId) }
        val expectedWeekIds = weeks.map { it.id }.toSet()
        val requestedIds = request.workouts.mapNotNull { runCatching { UUID.fromString(it.importWeekId) }.getOrNull() }.toSet()
        if (requestedIds != expectedWeekIds) {
            return TrainingImportResult.Invalid("Review every included workout in the selected week.")
        }
        for (review in request.workouts) {
            val weekId = UUID.fromString(review.importWeekId)
            val storedWeek = weeks.single { it.id == weekId }
            val original = storedWeek.extractedDraft?.let {
                json.decodeFromString(TrainingPrescriptionExtraction.serializer(), it)
            } ?: return TrainingImportResult.Invalid("An extracted workout draft is missing.")
            val originalKeys = original.groups.flatMap { it.prescriptions }.map { it.movementAddress }.toSet()
            val reviewedMovements = review.groups.flatMap(ReviewedTrainingGroup::prescriptions)
            if (reviewedMovements.map { it.movementAddress }.toSet() != originalKeys) {
                return TrainingImportResult.Invalid("Review cannot add or lose extracted movement source keys.")
            }
            review.groups.forEach { group ->
                if (group.label.isBlank() || group.kind !in setOf("STRAIGHT_SET", "SUPERSET")) {
                    return TrainingImportResult.Invalid("Every included group needs a label and valid kind.")
                }
            }
            reviewedMovements.forEach { movement ->
                if (movement.decision !in setOf("MATCH", "CREATE", "EXCLUDE")) {
                    return TrainingImportResult.Invalid("Resolve every movement as match, create, or exclude.")
                }
                if (movement.decision != "EXCLUDE" && movement.executionType !in setOf("REPS", "REPS_PER_SIDE", "DURATION")) {
                    return TrainingImportResult.Invalid("Confirm an execution type for every included movement.")
                }
                if (movement.decision == "MATCH" && movement.exerciseId == null) {
                    return TrainingImportResult.Invalid("Choose an existing exercise for ${movement.movement}.")
                }
                if (movement.decision == "CREATE" && movement.newExerciseName.isNullOrBlank()) {
                    return TrainingImportResult.Invalid("Name the new exercise for ${movement.movement}.")
                }
            }
            val persisted = TrainingPrescriptionExtraction(
                groups = review.groups.map { group ->
                    ExtractedTrainingGroup(
                        label = group.label,
                        labelAddress = group.labelAddress,
                        kind = group.kind,
                        prescriptions = group.prescriptions.map { movement ->
                            ExtractedTrainingPrescription(
                                movement = movement.movement,
                                movementAddress = movement.movementAddress,
                                executionTypeProposal = movement.executionType,
                                demoUrl = movement.demoUrl,
                                sets = movement.sets,
                                rest = movement.rest,
                                reps = movement.reps,
                                load = movement.load,
                                rir = movement.rir,
                                tempo = movement.tempo,
                                note = movement.note,
                                sourceCells = movement.sourceCells,
                            )
                        },
                    )
                },
            )
            dbQuery(db) {
                repository.saveReviewedDraft(weekId, json.encodeToString(persisted), reviewedMovements)
            }
        }
        return get(ownerUserId, importId)
    }

    suspend fun cancel(ownerUserId: String, importId: UUID): TrainingImportResult<Unit> {
        val header = dbQuery(db) { repository.header(ownerUserId, importId) }
            ?: return TrainingImportResult.NotFound
        if (header.state == "APPLIED") return TrainingImportResult.Conflict("An applied import cannot be cancelled.")
        dbQuery(db) { repository.cancel(importId, now()) }
        return TrainingImportResult.Ok(Unit)
    }

    suspend fun apply(ownerUserId: String, importId: UUID): TrainingImportResult<Int> {
        val header = dbQuery(db) { repository.header(ownerUserId, importId) }
            ?: return TrainingImportResult.NotFound
        if (header.state == "APPLIED") {
            return TrainingImportResult.Conflict("This import has already been applied.")
        }
        if (header.state != "REVIEW") {
            return TrainingImportResult.Conflict("Finish human review before applying this week.")
        }
        val tabs = dbQuery(db) { repository.tabs(importId) }
        val weeks = dbQuery(db) { repository.weeks(importId) }
        val matches = dbQuery(db) { repository.matches(importId) }.groupBy { it.importWeekId }
        val applied = mutableListOf<AppliedImportWorkout>()
        for (week in weeks) {
            val tab = tabs.single { it.id == week.importTabId }
            val draftJson = week.extractedDraft
                ?: return TrainingImportResult.Invalid("${tab.tabTitle} has not been extracted.")
            if (week.sourceSnapshot == null || week.sourceHash == null) {
                return TrainingImportResult.Invalid("${tab.tabTitle} is missing verified source provenance.")
            }
            val draft = json.decodeFromString(TrainingPrescriptionExtraction.serializer(), draftJson)
            val decisions = matches[week.id].orEmpty().associateBy { it.sourceMovementKey }
            val sourceKeys = draft.groups.flatMap { it.prescriptions }.map { it.movementAddress }.toSet()
            if (decisions.keys != sourceKeys) {
                return TrainingImportResult.Invalid("Review every movement in ${tab.tabTitle}.")
            }
            decisions.values.forEach { match ->
                if (match.decision !in setOf("MATCH", "CREATE", "EXCLUDE")) {
                    return TrainingImportResult.Invalid("Review every movement in ${tab.tabTitle}.")
                }
                if (match.decision != "EXCLUDE" && match.executionType !in setOf("REPS", "REPS_PER_SIDE", "DURATION")) {
                    return TrainingImportResult.Invalid("Confirm every execution type in ${tab.tabTitle}.")
                }
            }
            applied += AppliedImportWorkout(tab, week, draft, decisions)
        }
        return try {
            val selectedWeek = dbQuery(db) {
                repository.apply(ownerUserId, header, applied, now(), json)
            }
            TrainingImportResult.Ok(selectedWeek)
        } catch (ex: IllegalArgumentException) {
            TrainingImportResult.Invalid(ex.message ?: "The reviewed week could not be applied.")
        } catch (ex: IllegalStateException) {
            TrainingImportResult.Conflict(ex.message ?: "The import changed before it was applied.")
        }
    }

    suspend fun get(ownerUserId: String, importId: UUID): TrainingImportResult<TrainingImportResponse> {
        val header = dbQuery(db) { repository.header(ownerUserId, importId) }
            ?: return TrainingImportResult.NotFound
        val tabs = dbQuery(db) { repository.tabs(importId) }
        val weeks = dbQuery(db) { repository.weeks(importId) }.associateBy { it.importTabId }
        val matches = dbQuery(db) { repository.matches(importId) }.groupBy { it.importWeekId }
        return TrainingImportResult.Ok(
            TrainingImportResponse(
                id = importId.toString(),
                programId = header.programId?.toString(),
                programName = header.programName,
                spreadsheetTitle = header.spreadsheetTitle,
                selectedWeekNumber = header.selectedWeekNumber,
                state = header.state,
                errorDetail = header.errorDetail,
                tabs = tabs.map { tab ->
                    val week = weeks[tab.id]
                    val decisions = week?.let { matches[it.id].orEmpty().associateBy { match -> match.sourceMovementKey } }.orEmpty()
                    val draft = week?.extractedDraft?.let {
                        json.decodeFromString(TrainingPrescriptionExtraction.serializer(), it)
                    }
                    TrainingImportTabResponse(
                        importWeekId = week?.id?.toString(),
                        googleSheetId = tab.googleSheetId,
                        tabTitle = tab.tabTitle,
                        decision = tab.decision,
                        targetWorkoutId = tab.targetWorkoutId?.toString(),
                        newWorkoutName = tab.newWorkoutName,
                        startRow = week?.startRow,
                        endRow = week?.endRow,
                        executionBoundaryColumn = week?.executionBoundaryColumn,
                        extractionModel = week?.extractionModel,
                        groups = draft?.groups?.map { group ->
                            TrainingReviewGroupResponse(
                                label = group.label,
                                labelAddress = group.labelAddress,
                                kind = group.kind,
                                prescriptions = group.prescriptions.map { movement ->
                                    val decision = decisions[movement.movementAddress]
                                    TrainingReviewPrescriptionResponse(
                                        movement = movement.movement,
                                        movementAddress = movement.movementAddress,
                                        executionTypeProposal = movement.executionTypeProposal,
                                        demoUrl = movement.demoUrl,
                                        sets = movement.sets,
                                        rest = movement.rest,
                                        reps = movement.reps,
                                        load = movement.load,
                                        rir = movement.rir,
                                        tempo = movement.tempo,
                                        note = movement.note,
                                        sourceCells = movement.sourceCells,
                                        decision = decision?.decision,
                                        exerciseId = decision?.exerciseId?.toString(),
                                        newExerciseName = decision?.newExerciseName,
                                        executionType = decision?.executionType,
                                        rememberAsAlias = decision?.rememberAsAlias ?: true,
                                    )
                                },
                            )
                        }.orEmpty(),
                    )
                },
            ),
        )
    }

    private fun now(): OffsetDateTime = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
}
