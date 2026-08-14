package me.gpipi.training.writes

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.gpipi.config.dbQuery
import me.gpipi.training.google.GoogleConnectionService
import me.gpipi.training.google.GoogleIntegrationException
import me.gpipi.training.google.GoogleSheetWriteRejectedException
import me.gpipi.training.google.SheetCell
import me.gpipi.training.google.SheetDiscovery
import me.gpipi.training.google.SheetTabGrid
import me.gpipi.training.google.SheetValue
import me.gpipi.training.google.SheetValueUpdate
import me.gpipi.training.google.TrainingSheetGateway
import me.gpipi.training.google.WeekRangeProposal
import me.gpipi.training.google.proposalsFor
import me.gpipi.training.google.weekLabels
import me.gpipi.training.google.weekNumber
import org.jetbrains.exposed.v1.jdbc.Database

class TrainingWriteService(
    private val db: Database,
    private val repository: TrainingWriteRepository,
    private val google: GoogleConnectionService,
    private val sheets: TrainingSheetGateway,
    private val matcher: TrainingWriteMatchingService,
    private val clock: Clock = Clock.systemUTC(),
    private val json: Json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = true },
) {
    suspend fun destination(
        ownerUserId: String,
        sessionId: UUID,
    ): TrainingWriteResult<TrainingWriteDestinationResponse> {
        val source = dbQuery(db) { repository.source(ownerUserId, sessionId) }
            ?: return TrainingWriteResult.NotFound
        if (source.sessionStatus != "COMPLETED") {
            return TrainingWriteResult.Conflict("Finish this workout before writing it to Google Sheets.")
        }
        val link = dbQuery(db) { repository.linkedSheet(ownerUserId, source.programId) }
        val status = google.status(ownerUserId)
        return TrainingWriteResult.Ok(
            TrainingWriteDestinationResponse(
                sessionId = sessionId.toString(),
                linkedSheetTitle = link?.second,
                googleConnected = status.connected,
            ),
        )
    }

    suspend fun start(
        ownerUserId: String,
        sessionId: UUID,
        selectedSpreadsheetId: String? = null,
    ): TrainingWriteResult<TrainingWriteResponse> {
        val source = dbQuery(db) { repository.source(ownerUserId, sessionId) }
            ?: return TrainingWriteResult.NotFound
        if (source.sessionStatus != "COMPLETED") {
            return TrainingWriteResult.Conflict("Finish this workout before writing it to Google Sheets.")
        }
        return try {
            val access = google.accessToken(ownerUserId)
            if (selectedSpreadsheetId == null) {
                val lookup = dbQuery(db) { repository.importProvenance(source) }
                val provenance = lookup.provenance
                if (provenance != null) {
                    try {
                        return resolveImported(source, provenance, access.accessToken)
                    } catch (ex: IllegalArgumentException) {
                        return startSelection(source, access.accessToken, provenance.spreadsheetId, ex.message)
                    }
                }
                if (lookup.resolutionFailure != null) {
                    val linked = dbQuery(db) { repository.linkedSheet(ownerUserId, source.programId) }
                        ?: return TrainingWriteResult.Invalid("Choose a Google Sheet for this workout.")
                    return startSelection(source, access.accessToken, linked.first, lookup.resolutionFailure)
                }
            }
            val linked = dbQuery(db) { repository.linkedSheet(ownerUserId, source.programId) }
            val spreadsheetId = selectedSpreadsheetId ?: linked?.first
                ?: return TrainingWriteResult.Invalid("Choose a Google Sheet for this workout.")
            startSelection(source, access.accessToken, spreadsheetId, null)
        } catch (ex: GoogleIntegrationException) {
            TrainingWriteResult.Unavailable(ex.message.orEmpty())
        }
    }

    suspend fun beginSelection(
        ownerUserId: String,
        attemptId: UUID,
    ): TrainingWriteResult<TrainingWriteResponse> {
        val attempt = dbQuery(db) { repository.attempt(ownerUserId, attemptId) }
            ?: return TrainingWriteResult.NotFound
        if (attempt.status != "RESOLVED") {
            return TrainingWriteResult.Conflict("Only a resolved import destination can be edited in place.")
        }
        return try {
            val access = google.accessToken(ownerUserId)
            val discovery = sheets.discover(access.accessToken, attempt.spreadsheetId)
            val snapshot = discoverySnapshot(discovery)
            if (snapshot.tabs.isEmpty()) {
                return TrainingWriteResult.Invalid("No visible Week or Minggu labels were found in this Sheet.")
            }
            dbQuery(db) {
                repository.resetSelection(
                    attemptId = attemptId,
                    discoverySnapshot = json.encodeToString(snapshot),
                    detail = null,
                    now = now(),
                )
            }
            get(ownerUserId, attemptId)
        } catch (ex: GoogleIntegrationException) {
            TrainingWriteResult.Unavailable(ex.message.orEmpty())
        }
    }

    suspend fun chooseTab(
        ownerUserId: String,
        attemptId: UUID,
        tabKey: String,
    ): TrainingWriteResult<TrainingWriteResponse> {
        val attempt = dbQuery(db) { repository.attempt(ownerUserId, attemptId) }
            ?: return TrainingWriteResult.NotFound
        if (attempt.status != "NEEDS_TAB") {
            return TrainingWriteResult.Conflict("Start a new destination selection before changing the Sheet tab.")
        }
        val snapshot = decodeDiscovery(attempt)
            ?: return TrainingWriteResult.Invalid("The Sheet tab list is missing. Scan the Sheet again.")
        val tab = snapshot.tabs.singleOrNull { it.key == tabKey }
            ?: return TrainingWriteResult.Invalid("Choose one of the scanned Sheet tabs.")
        dbQuery(db) { repository.chooseTab(attemptId, tab, now()) }
        return get(ownerUserId, attemptId)
    }

    suspend fun chooseWeek(
        ownerUserId: String,
        attemptId: UUID,
        weekNumber: Int,
    ): TrainingWriteResult<TrainingWriteResponse> {
        if (weekNumber < 1) return TrainingWriteResult.Invalid("Choose a positive Sheet week number.")
        val attempt = dbQuery(db) { repository.attempt(ownerUserId, attemptId) }
            ?: return TrainingWriteResult.NotFound
        if (attempt.status !in setOf("NEEDS_WEEK", "REVIEW", "MATCHING")) {
            return TrainingWriteResult.Conflict("Start a new Sheet scan before changing this destination week.")
        }
        val source = dbQuery(db) { repository.source(ownerUserId, attempt.sessionId) }
            ?: return TrainingWriteResult.NotFound
        if (source.sessionStatus != "COMPLETED") {
            return TrainingWriteResult.Conflict("Finish this workout again before matching it.")
        }
        return try {
            dbQuery(db) { repository.beginMatching(attemptId, weekNumber, now()) }
            val access = google.accessToken(ownerUserId)
            val discovery = sheets.discover(access.accessToken, attempt.spreadsheetId)
            val selectedTab = discovery.tabs.singleOrNull { it.sheetId == attempt.targetGoogleSheetId }
            if (selectedTab == null) {
                val snapshot = discoverySnapshot(discovery)
                dbQuery(db) {
                    repository.resetSelection(
                        attemptId, json.encodeToString(snapshot), "The selected Sheet tab is no longer available.", now(),
                    )
                }
                return get(ownerUserId, attemptId)
            }
            if (weekNumber !in selectedTab.weekLabels().keys) {
                dbQuery(db) { repository.transition(attemptId, "NEEDS_WEEK", "Sheet Week $weekNumber is no longer available.", now()) }
                return TrainingWriteResult.Invalid("Sheet Week $weekNumber is no longer available in ${selectedTab.title}.")
            }
            val proposal = discovery.proposalsFor(weekNumber).singleOrNull {
                it.present && it.sheetId == selectedTab.sheetId
            }
            if (proposal == null) {
                return matchingFailure(attemptId, "No workout range was found for Sheet Week $weekNumber.")
            }
            if (proposal.boundaryAmbiguous || proposal.startRow == null || proposal.endRow == null ||
                proposal.executionBoundaryColumn == null || proposal.executionHeaderAddress == null ||
                proposal.weekHeaderAddress == null
            ) {
                val message = "No clear execution column in ${proposal.tabTitle} for Sheet Week $weekNumber."
                val snapshot = discoverySnapshot(discovery)
                dbQuery(db) {
                    repository.resetSelection(attemptId, json.encodeToString(snapshot), message, now())
                }
                return get(ownerUserId, attemptId)
            }
            val grid = sheets.readSelectedRange(
                accessToken = access.accessToken,
                spreadsheetId = attempt.spreadsheetId,
                sheetId = proposal.sheetId,
                tabTitle = proposal.tabTitle,
                startRow = checkNotNull(proposal.startRow),
                endRow = checkNotNull(proposal.endRow),
                executionBoundaryColumn = checkNotNull(proposal.executionBoundaryColumn),
            )
            val candidates = listOf(candidate(proposal, grid))
            val matched = matcher.match(source, candidates)
            val selected = matched.output.matchedTabKey?.let { key -> candidates.single { it.key == key } }
            val proposed = if (selected == null) emptyList() else matched.output.movements.mapNotNull { result ->
                val address = result.sheetMovementAddress ?: return@mapNotNull null
                val movement = source.movements.single {
                    it.performedExerciseId.toString() == result.sourceMovementKey
                }
                WriteMovementRecord(
                    id = UUID.randomUUID(),
                    performedExerciseId = movement.performedExerciseId,
                    position = movement.position,
                    sheetMovementAddress = address,
                    sheetMovementText = checkNotNull(result.sheetMovementText),
                    matchSource = "MODEL",
                    confirmed = false,
                )
            }
            val snapshot = WriteMatchingSnapshot(candidates, matched.input, matched.output)
            val serialized = json.encodeToString(snapshot)
            dbQuery(db) {
                repository.saveMatching(
                    attemptId = attemptId,
                    targetWeek = weekNumber,
                    candidate = selected,
                    contractVersion = TRAINING_WRITE_MATCH_CONTRACT_VERSION,
                    model = matched.model,
                    snapshot = serialized,
                    sourceHash = sha256(serialized),
                    proposed = proposed,
                    now = now(),
                )
            }
            get(ownerUserId, attemptId)
        } catch (ex: GoogleIntegrationException) {
            dbQuery(db) { repository.transition(attemptId, "FAILED", ex.message, now()) }
            TrainingWriteResult.Unavailable(ex.message.orEmpty())
        } catch (ex: IllegalArgumentException) {
            matchingFailure(attemptId, ex.message ?: "The workout could not be matched safely.")
        }
    }

    suspend fun confirmMatches(
        ownerUserId: String,
        attemptId: UUID,
        request: ConfirmTrainingWriteMatchesRequest,
    ): TrainingWriteResult<TrainingWriteResponse> {
        val attempt = dbQuery(db) { repository.attempt(ownerUserId, attemptId) }
            ?: return TrainingWriteResult.NotFound
        if (attempt.status != "REVIEW") {
            return TrainingWriteResult.Conflict("This write is not waiting for match review.")
        }
        val source = dbQuery(db) { repository.source(ownerUserId, attempt.sessionId) }
            ?: return TrainingWriteResult.NotFound
        val snapshot = attempt.matchingSourceSnapshot?.let {
            json.decodeFromString(WriteMatchingSnapshot.serializer(), it)
        } ?: return TrainingWriteResult.Invalid("The matching evidence is missing. Scan the Sheet again.")
        val candidate = snapshot.candidates.singleOrNull { it.key == request.tabKey }
            ?: return TrainingWriteResult.Invalid("Choose one of the scanned Sheet workouts.")
        val requestedKeys = request.movements.map(ConfirmedTrainingWriteMovement::sourceMovementKey)
        if (requestedKeys.size != requestedKeys.toSet().size ||
            requestedKeys.toSet() != source.movements.map { it.performedExerciseId.toString() }.toSet()
        ) {
            return TrainingWriteResult.Invalid("Match every app movement exactly once.")
        }
        val cells = candidate.prescriptionCells.associateBy(WriteSheetCell::address)
        val addresses = request.movements.map(ConfirmedTrainingWriteMovement::sheetMovementAddress)
        if (addresses.size != addresses.toSet().size) {
            return TrainingWriteResult.Invalid("One Sheet row cannot match more than one app movement.")
        }
        val modelBySource = snapshot.output.movements.associateBy(WriteMatchMovementOutput::sourceMovementKey)
        val confirmed = request.movements.map { input ->
            val movement = source.movements.single { it.performedExerciseId.toString() == input.sourceMovementKey }
            val cell = cells[input.sheetMovementAddress]
                ?: return TrainingWriteResult.Invalid("${input.sheetMovementAddress} is outside the selected prescription range.")
            val model = modelBySource[input.sourceMovementKey]
            WriteMovementRecord(
                id = UUID.randomUUID(),
                performedExerciseId = movement.performedExerciseId,
                position = movement.position,
                sheetMovementAddress = cell.address,
                sheetMovementText = cell.text,
                matchSource = if (
                    snapshot.output.matchedTabKey == candidate.key &&
                    model?.sheetMovementAddress == cell.address && model.sheetMovementText == cell.text
                ) "MODEL" else "MANUAL",
                confirmed = true,
            )
        }
        val finalOutput = WriteMatchOutput(
            matchedTabKey = candidate.key,
            movements = confirmed.map {
                WriteMatchMovementOutput(
                    sourceMovementKey = it.performedExerciseId.toString(),
                    sheetMovementAddress = it.sheetMovementAddress,
                    sheetMovementText = it.sheetMovementText,
                )
            },
        )
        val updated = snapshot.copy(output = finalOutput)
        val serialized = json.encodeToString(updated)
        dbQuery(db) {
            repository.confirmMatches(
                attemptId, candidate, confirmed, serialized, sha256(serialized), now(),
            )
        }
        return get(ownerUserId, attemptId)
    }

    suspend fun prepare(
        ownerUserId: String,
        attemptId: UUID,
    ): TrainingWriteResult<TrainingWriteResponse> {
        val attempt = dbQuery(db) { repository.attempt(ownerUserId, attemptId) }
            ?: return TrainingWriteResult.NotFound
        if (attempt.status !in setOf("REVIEW", "RESOLVED")) {
            return TrainingWriteResult.Conflict("Review every match before previewing execution.")
        }
        val source = dbQuery(db) { repository.source(ownerUserId, attempt.sessionId) }
            ?: return TrainingWriteResult.NotFound
        if (source.sessionStatus != "COMPLETED") {
            return TrainingWriteResult.Conflict("Finish this workout again before previewing execution.")
        }
        val movements = dbQuery(db) { repository.movements(attemptId) }
        if (movements.size != source.movements.size || movements.any { !it.confirmed }) {
            return TrainingWriteResult.Invalid("Match every app movement before previewing execution.")
        }
        val snapshot = decodeMatching(attempt)
            ?: return TrainingWriteResult.Invalid("The matching evidence is missing. Scan the Sheet again.")
        val candidate = selectedCandidate(attempt, snapshot)
            ?: return TrainingWriteResult.Invalid("Choose one Sheet workout before previewing execution.")
        return try {
            val access = google.accessToken(ownerUserId)
            val grid = sheets.readSelectedRange(
                access.accessToken, attempt.spreadsheetId, candidate.googleSheetId, candidate.title,
                candidate.startRow, candidate.endRow, candidate.executionBoundaryColumn,
            )
            validateAnchors(candidate, movements, grid)
            val cells = projection(source, movements, candidate, grid)
            val projectionHash = projectionHash(source, movements, cells)
            val payloadHash = payloadHash(cells)
            dbQuery(db) { repository.savePrepared(attemptId, cells, projectionHash, payloadHash, now()) }
            get(ownerUserId, attemptId)
        } catch (ex: IllegalArgumentException) {
            TrainingWriteResult.Invalid(ex.message.orEmpty())
        } catch (ex: GoogleIntegrationException) {
            TrainingWriteResult.Unavailable(ex.message.orEmpty())
        }
    }

    suspend fun confirm(
        ownerUserId: String,
        attemptId: UUID,
    ): TrainingWriteResult<TrainingWriteResponse> {
        var attempt = dbQuery(db) { repository.attempt(ownerUserId, attemptId) }
            ?: return TrainingWriteResult.NotFound
        if (attempt.status == "SUCCEEDED") return get(ownerUserId, attemptId)
        if (attempt.status == "UNKNOWN") {
            return TrainingWriteResult.Conflict("Verify the uncertain Sheet result before starting another write.")
        }
        if (attempt.status != "PREPARED") {
            return TrainingWriteResult.Conflict("Create a fresh execution preview before writing.")
        }
        val source = dbQuery(db) { repository.source(ownerUserId, attempt.sessionId) }
            ?: return TrainingWriteResult.NotFound
        if (source.sessionStatus != "COMPLETED") {
            return TrainingWriteResult.Conflict("Finish this workout again before writing.")
        }
        val movements = dbQuery(db) { repository.movements(attemptId) }
        val cells = dbQuery(db) { repository.cells(attemptId) }
        val currentHash = projectionHash(source, movements, preparedFromStored(source, cells))
        if (currentHash != attempt.executionProjectionHash) {
            return TrainingWriteResult.Conflict("Workout execution changed. Create a fresh preview before writing.")
        }
        if (!dbQuery(db) { repository.claimPrepared(attemptId, now()) }) {
            return TrainingWriteResult.Conflict("This write is already being confirmed.")
        }
        attempt = checkNotNull(dbQuery(db) { repository.attempt(ownerUserId, attemptId) })
        val snapshot = checkNotNull(decodeMatching(attempt))
        val candidate = checkNotNull(selectedCandidate(attempt, snapshot))
        val access = try {
            google.accessToken(ownerUserId)
        } catch (ex: GoogleIntegrationException) {
            dbQuery(db) { repository.transition(attemptId, "FAILED", ex.message, now()) }
            return TrainingWriteResult.Unavailable(ex.message.orEmpty())
        }
        val prewrite = try {
            sheets.readSelectedRange(
                access.accessToken, attempt.spreadsheetId, candidate.googleSheetId, candidate.title,
                candidate.startRow, candidate.endRow, candidate.executionBoundaryColumn,
            )
        } catch (ex: GoogleIntegrationException) {
            dbQuery(db) { repository.transition(attemptId, "FAILED", ex.message, now()) }
            return TrainingWriteResult.Unavailable(ex.message.orEmpty())
        }
        try {
            validateAnchors(candidate, movements, prewrite)
        } catch (ex: IllegalArgumentException) {
            dbQuery(db) { repository.transition(attemptId, "DRIFT_ABORTED", ex.message, now()) }
            return get(ownerUserId, attemptId)
        }
        val prewriteValues = valuesAt(prewrite, cells.map(WriteCellRecord::address))
        dbQuery(db) { repository.savePrewrite(attemptId, prewriteValues, now()) }
        val updates = cells.filter { cell ->
            !sameSheetValue(prewriteValues[cell.address]?.first, cell.proposedValue)
        }.map { SheetValueUpdate(it.row, it.column, it.proposedValue) }
        dbQuery(db) { repository.markSending(attemptId, updates.isNotEmpty(), now()) }
        if (updates.isNotEmpty()) {
            try {
                sheets.batchUpdateValues(
                    access.accessToken, attempt.spreadsheetId, candidate.googleSheetId, updates,
                )
            } catch (ex: GoogleSheetWriteRejectedException) {
                dbQuery(db) { repository.transition(attemptId, "FAILED", ex.message, now()) }
                return get(ownerUserId, attemptId)
            } catch (ex: Exception) {
                dbQuery(db) { repository.transition(attemptId, "UNKNOWN", "Check the Sheet before trying again.", now()) }
                return get(ownerUserId, attemptId)
            }
        }
        return verifyAfterSend(ownerUserId, attemptId, attempt, candidate, movements, cells, updates.isNotEmpty(), access.accessToken)
    }

    suspend fun verify(
        ownerUserId: String,
        attemptId: UUID,
    ): TrainingWriteResult<TrainingWriteResponse> {
        val attempt = dbQuery(db) { repository.attempt(ownerUserId, attemptId) }
            ?: return TrainingWriteResult.NotFound
        if (attempt.status != "UNKNOWN") {
            return if (attempt.status == "SUCCEEDED") get(ownerUserId, attemptId)
            else TrainingWriteResult.Conflict("Only an uncertain write result needs verification.")
        }
        val movements = dbQuery(db) { repository.movements(attemptId) }
        val cells = dbQuery(db) { repository.cells(attemptId) }
        val snapshot = checkNotNull(decodeMatching(attempt))
        val candidate = checkNotNull(selectedCandidate(attempt, snapshot))
        return try {
            val access = google.accessToken(ownerUserId)
            verifyAfterSend(ownerUserId, attemptId, attempt, candidate, movements, cells, attempt.apiCalled, access.accessToken)
        } catch (ex: GoogleIntegrationException) {
            TrainingWriteResult.Unavailable(ex.message.orEmpty())
        }
    }

    suspend fun status(ownerUserId: String, sessionId: UUID): TrainingWriteResult<TrainingWriteStatusResponse> {
        val source = dbQuery(db) { repository.source(ownerUserId, sessionId) }
            ?: return TrainingWriteResult.NotFound
        return TrainingWriteResult.Ok(
            dbQuery(db) { repository.syncStatus(ownerUserId, sessionId) }
                ?: TrainingWriteStatusResponse(state = "NOT_WRITTEN"),
        )
    }

    suspend fun get(ownerUserId: String, attemptId: UUID): TrainingWriteResult<TrainingWriteResponse> {
        val attempt = dbQuery(db) { repository.attempt(ownerUserId, attemptId) }
            ?: return TrainingWriteResult.NotFound
        val source = dbQuery(db) { repository.source(ownerUserId, attempt.sessionId) }
            ?: return TrainingWriteResult.NotFound
        val storedMovements = dbQuery(db) { repository.movements(attemptId) }
        val storedBySource = storedMovements.associateBy(WriteMovementRecord::performedExerciseId)
        val snapshot = decodeMatching(attempt)
        val discovery = decodeDiscovery(attempt)
        val candidateTabs = snapshot?.candidates.orEmpty().map { candidate ->
            TrainingWriteCandidateTabResponse(
                key = candidate.key,
                title = candidate.title,
                rows = candidate.prescriptionCells
                    .filter { it.text.isNotBlank() && it.address != candidate.weekHeaderAddress }
                    .sortedWith(compareBy(WriteSheetCell::row, WriteSheetCell::column))
                    .map { TrainingWriteCandidateRowResponse(it.address, it.text) },
            )
        }
        val cells = dbQuery(db) { repository.cells(attemptId) }
        val cellsBySource = cells.groupBy(WriteCellRecord::performedExerciseId)
        return TrainingWriteResult.Ok(
            TrainingWriteResponse(
                id = attempt.id.toString(),
                sessionId = attempt.sessionId.toString(),
                sourceWeekNumber = attempt.sourceWeekNumber,
                sourceWorkoutName = attempt.sourceWorkoutName,
                spreadsheetTitle = attempt.spreadsheetTitle,
                availableWeekNumbers = attempt.availableWeekNumbers,
                targetWeekNumber = attempt.targetWeekNumber,
                targetTabTitle = attempt.targetTabTitle,
                selectedTabKey = snapshot?.candidates?.singleOrNull {
                    it.googleSheetId == attempt.targetGoogleSheetId
                }?.key ?: discovery?.tabs?.singleOrNull {
                    it.googleSheetId == attempt.targetGoogleSheetId
                }?.key,
                status = attempt.status,
                detail = attempt.detail,
                availableTabs = discovery?.tabs.orEmpty().map {
                    TrainingWriteTabResponse(key = it.key, title = it.title)
                },
                candidateTabs = candidateTabs,
                matches = source.movements.map { movement ->
                    val stored = storedBySource[movement.performedExerciseId]
                    TrainingWriteMatchResponse(
                        sourceMovementKey = movement.performedExerciseId.toString(),
                        sourceName = movement.exerciseName,
                        sourcePosition = movement.position,
                        sheetMovementAddress = stored?.sheetMovementAddress,
                        sheetMovementText = stored?.sheetMovementText,
                        matchSource = stored?.matchSource,
                        confirmed = stored?.confirmed ?: false,
                    )
                },
                preview = source.movements.mapNotNull { movement ->
                    val stored = storedBySource[movement.performedExerciseId] ?: return@mapNotNull null
                    val movementCells = cellsBySource[movement.performedExerciseId].orEmpty()
                    if (movementCells.isEmpty()) return@mapNotNull null
                    TrainingWritePreviewMovementResponse(
                        sourceMovementKey = movement.performedExerciseId.toString(),
                        sourceName = movement.exerciseName,
                        sheetMovementAddress = stored.sheetMovementAddress,
                        cells = movementCells.map { cell ->
                            TrainingWritePreviewCellResponse(
                                setNumber = cell.setNumber,
                                field = cell.field,
                                address = cell.address,
                                current = cell.observedDisplay ?: displayValue(cell.observedValue),
                                proposed = displayValue(cell.proposedValue),
                                action = cell.action,
                            )
                        },
                    )
                },
                cellCount = cells.size,
                finishedAt = attempt.finishedAt?.toString(),
            ),
        )
    }

    private suspend fun startSelection(
        source: WriteSource,
        accessToken: String,
        spreadsheetId: String,
        detail: String?,
    ): TrainingWriteResult<TrainingWriteResponse> {
        val discovery = sheets.discover(accessToken, spreadsheetId)
        val snapshot = discoverySnapshot(discovery)
        if (snapshot.tabs.isEmpty()) {
            return TrainingWriteResult.Invalid("No visible Week or Minggu labels were found in this Sheet.")
        }
        val attemptId = dbQuery(db) {
            repository.createAttempt(
                source = source,
                spreadsheetId = spreadsheetId,
                spreadsheetTitle = discovery.spreadsheetTitle,
                availableWeeks = emptyList(),
                discoverySnapshot = json.encodeToString(snapshot),
                status = "NEEDS_TAB",
                detail = detail,
                now = now(),
            )
        }
        return get(source.ownerUserId, attemptId)
    }

    private suspend fun resolveImported(
        source: WriteSource,
        provenance: WriteImportProvenance,
        accessToken: String,
    ): TrainingWriteResult<TrainingWriteResponse> {
        require(provenance.movements.size == source.movements.size) {
            "A workout movement no longer has an imported Sheet row."
        }
        require(provenance.movements.map(WriteImportMovementProvenance::movementAddress).distinct().size ==
            provenance.movements.size
        ) { "Two workout movements point to the same imported Sheet row." }
        val grid = sheets.readSelectedRange(
            accessToken = accessToken,
            spreadsheetId = provenance.spreadsheetId,
            sheetId = provenance.googleSheetId,
            tabTitle = provenance.tabTitle,
            startRow = provenance.startRow,
            endRow = provenance.endRow,
            executionBoundaryColumn = provenance.executionBoundaryColumn,
        )
        require(grid.sheetId == provenance.googleSheetId) { "The imported Sheet tab is no longer available." }
        val weekHeader = grid.cells
            .filter {
                it.row in provenance.startRow..provenance.endRow &&
                    it.column < provenance.executionBoundaryColumn && it.weekNumber() != null
            }
            .minWithOrNull(compareBy(SheetCell::row, SheetCell::column))
            ?: throw IllegalArgumentException("The imported Sheet range no longer has a Week or Minggu label.")
        val targetWeek = checkNotNull(weekHeader.weekNumber())
        val proposal = WeekRangeProposal(
            sheetId = provenance.googleSheetId,
            tabTitle = provenance.tabTitle,
            tabPosition = grid.position,
            present = true,
            startRow = provenance.startRow,
            endRow = provenance.endRow,
            weekHeaderAddress = weekHeader.address,
            weekHeaderValue = weekHeader.display,
            executionBoundaryColumn = provenance.executionBoundaryColumn,
            executionHeaderAddress = provenance.executionHeaderAddress,
            executionHeaderValue = provenance.executionHeaderValue,
            boundaryAmbiguous = false,
        )
        val candidate = candidate(proposal, grid)
        val movements = provenance.movements.map { movement ->
            WriteMovementRecord(
                id = UUID.randomUUID(),
                performedExerciseId = movement.performedExerciseId,
                position = movement.position,
                sheetMovementAddress = movement.movementAddress,
                sheetMovementText = movement.movementText,
                matchSource = "IMPORT",
                confirmed = true,
            )
        }
        validateAnchors(candidate, movements, grid)
        val output = WriteMatchOutput(
            matchedTabKey = candidate.key,
            movements = movements.map { movement ->
                WriteMatchMovementOutput(
                    sourceMovementKey = movement.performedExerciseId.toString(),
                    sheetMovementAddress = movement.sheetMovementAddress,
                    sheetMovementText = movement.sheetMovementText,
                )
            },
        )
        val matchingSnapshot = WriteMatchingSnapshot(
            candidates = listOf(candidate),
            output = output,
            provenance = WriteResolvedProvenanceSnapshot(
                tabTitle = provenance.tabTitle,
                googleSheetId = provenance.googleSheetId,
                startRow = provenance.startRow,
                endRow = provenance.endRow,
                sourceHash = provenance.sourceHash,
            ),
        )
        val serializedMatching = json.encodeToString(matchingSnapshot)
        val discovery = WriteDiscoverySnapshot(
            spreadsheetTitle = provenance.spreadsheetTitle,
            availableWeekNumbers = listOf(targetWeek),
            tabs = listOf(
                WriteDiscoveryTab(
                    key = candidate.key,
                    googleSheetId = provenance.googleSheetId,
                    title = provenance.tabTitle,
                    position = grid.position,
                    availableWeekNumbers = listOf(targetWeek),
                ),
            ),
        )
        val attemptId = dbQuery(db) {
            val created = repository.createAttempt(
                source = source,
                spreadsheetId = provenance.spreadsheetId,
                spreadsheetTitle = provenance.spreadsheetTitle,
                availableWeeks = listOf(targetWeek),
                discoverySnapshot = json.encodeToString(discovery),
                status = "SCANNING",
                detail = null,
                now = now(),
            )
            repository.saveResolved(
                attemptId = created,
                targetWeek = targetWeek,
                candidate = candidate,
                snapshot = serializedMatching,
                sourceHash = provenance.sourceHash,
                movements = movements,
                now = now(),
            )
            created
        }
        return get(source.ownerUserId, attemptId)
    }

    private fun discoverySnapshot(discovery: SheetDiscovery): WriteDiscoverySnapshot {
        val tabs = discovery.tabs.mapNotNull { tab ->
            val weeks = tab.weekLabels().keys.sorted()
            if (weeks.isEmpty()) null else WriteDiscoveryTab(
                key = "tab-${tab.sheetId}",
                googleSheetId = tab.sheetId,
                title = tab.title,
                position = tab.position,
                availableWeekNumbers = weeks,
            )
        }.sortedBy(WriteDiscoveryTab::position)
        return WriteDiscoverySnapshot(
            spreadsheetTitle = discovery.spreadsheetTitle,
            availableWeekNumbers = tabs.flatMap(WriteDiscoveryTab::availableWeekNumbers).distinct().sorted(),
            tabs = tabs,
        )
    }

    private fun decodeDiscovery(attempt: WriteAttemptRecord): WriteDiscoverySnapshot? =
        runCatching { json.decodeFromString(WriteDiscoverySnapshot.serializer(), attempt.discoverySnapshot) }.getOrNull()

    private suspend fun verifyAfterSend(
        ownerUserId: String,
        attemptId: UUID,
        attempt: WriteAttemptRecord,
        candidate: WriteCandidateTab,
        movements: List<WriteMovementRecord>,
        cells: List<WriteCellRecord>,
        apiCalled: Boolean,
        accessToken: String,
    ): TrainingWriteResult<TrainingWriteResponse> {
        val verified = try {
            sheets.readSelectedRange(
                accessToken, attempt.spreadsheetId, candidate.googleSheetId, candidate.title,
                candidate.startRow, candidate.endRow, candidate.executionBoundaryColumn,
            )
        } catch (ex: Exception) {
            dbQuery(db) { repository.transition(attemptId, "UNKNOWN", "Check the Sheet before trying again.", now()) }
            return get(ownerUserId, attemptId)
        }
        val values = valuesAt(verified, cells.map(WriteCellRecord::address))
        val different = cells.filter { !sameSheetValue(values[it.address]?.first, it.proposedValue) }
        val state = if (different.isEmpty()) "SUCCEEDED" else "VERIFY_CONFLICT"
        val detail = if (different.isEmpty()) null else "${different.size} Sheet cells differ from the reviewed execution. Scan the Sheet again."
        dbQuery(db) {
            repository.saveVerification(attemptId, values, state, apiCalled, detail, now())
            if (state == "SUCCEEDED") repository.updateDefaultSheet(attempt, ownerUserId, now())
        }
        return get(ownerUserId, attemptId)
    }

    private suspend fun matchingFailure(
        attemptId: UUID,
        message: String,
    ): TrainingWriteResult<TrainingWriteResponse> {
        dbQuery(db) { repository.transition(attemptId, "FAILED", message, now()) }
        return TrainingWriteResult.Invalid(message)
    }

    private fun candidate(proposal: WeekRangeProposal, grid: SheetTabGrid): WriteCandidateTab {
        val start = checkNotNull(proposal.startRow)
        val end = checkNotNull(proposal.endRow)
        val boundary = checkNotNull(proposal.executionBoundaryColumn)
        val layoutLabel = Regex(
            "(?i)^(?:eksekusi|realisasi)(?:\\b.*)?$|^(?:set\\s*\\d+|reps?|load|rir|kg|time|duration|durasi)$",
        )
        return WriteCandidateTab(
            key = "tab-${proposal.sheetId}",
            googleSheetId = proposal.sheetId,
            title = proposal.tabTitle,
            startRow = start,
            endRow = end,
            weekHeaderAddress = checkNotNull(proposal.weekHeaderAddress),
            weekHeaderValue = checkNotNull(proposal.weekHeaderValue),
            executionBoundaryColumn = boundary,
            executionHeaderAddress = checkNotNull(proposal.executionHeaderAddress),
            executionHeaderValue = checkNotNull(proposal.executionHeaderValue),
            prescriptionCells = grid.cells.filter {
                it.row in start..end && it.column < boundary && it.display.isNotBlank()
            }.map { WriteSheetCell(it.address, it.row, it.column, it.display) },
            executionLayout = grid.cells.filter {
                it.row in start..end && it.column >= boundary && layoutLabel.matches(it.display.trim())
            }.map { WriteExecutionLayoutCell(it.address, it.row, it.column, it.display) },
        )
    }

    private fun projection(
        source: WriteSource,
        movements: List<WriteMovementRecord>,
        candidate: WriteCandidateTab,
        grid: SheetTabGrid,
    ): List<PreparedCell> {
        val destinations = executionDestinations(candidate.executionLayout)
        val primarySlots = destinations.filter { it.field == "REPS" }.maxOfOrNull { it.setNumber } ?: 0
        require(primarySlots > 0) { "No execution set columns were found in ${candidate.title}." }
        val sourceById = source.movements.associateBy(WriteSourceMovement::performedExerciseId)
        val observed = grid.cells.associateBy(SheetCell::address)
        return movements.sortedBy(WriteMovementRecord::position).flatMap { match ->
            val movement = sourceById.getValue(match.performedExerciseId)
            val activeSets = movement.sets.filterNot(WriteSourceSet::deleted).associateBy(WriteSourceSet::setNumber)
            val overflow = activeSets.keys.filter { it > primarySlots }
            require(overflow.isEmpty()) {
                "${movement.exerciseName} has Set ${overflow.min()} but ${candidate.title} only has $primarySlots execution slots."
            }
            val row = addressRow(match.sheetMovementAddress)
            destinations.map { destination ->
                val set = activeSets[destination.setNumber]
                val value = proposedValue(movement.executionType, set, destination.field)
                val cellAddress = "${me.gpipi.training.google.columnName(destination.column)}$row"
                val current = observed[cellAddress]
                PreparedCell(
                    performedExerciseId = movement.performedExerciseId,
                    performedSetId = set?.id,
                    setNumber = destination.setNumber,
                    field = destination.field,
                    row = row,
                    column = destination.column,
                    address = cellAddress,
                    observedValue = current?.userEnteredValue,
                    observedDisplay = current?.display,
                    action = if (value == null) "CLEAR" else "WRITE",
                    proposedValue = value,
                )
            }
        }
    }

    private fun executionDestinations(layout: List<WriteExecutionLayoutCell>): List<ExecutionDestination> {
        val labelledColumns = layout.mapNotNull { cell ->
            val field = when (cell.label.trim().lowercase()) {
                    "rep", "reps", "time", "duration", "durasi" -> "REPS"
                    "load", "kg" -> "LOAD"
                    "rir" -> "RIR"
                    else -> null
                } ?: return@mapNotNull null
            field to cell.column
        }.distinct().sortedWith(compareBy<Pair<String, Int>> { it.first }.thenBy { it.second })
        return labelledColumns.groupBy({ it.first }, { it.second }).flatMap { (field, columns) ->
            columns.sorted().mapIndexed { index, column -> ExecutionDestination(index + 1, field, column) }
        }
    }

    private fun proposedValue(executionType: String, set: WriteSourceSet?, field: String): SheetValue? {
        if (set == null) return null
        return when (field) {
            "REPS" -> if (executionType == "DURATION") {
                set.durationSeconds?.let { SheetValue("STRING", "$it sec") }
            } else {
                set.reps?.let { SheetValue("NUMBER", it.toString()) }
            }
            "LOAD" -> set.load?.let { SheetValue("NUMBER", it) }
            "RIR" -> set.rir?.let { SheetValue("NUMBER", it.toString()) }
            else -> null
        }
    }

    private fun preparedFromStored(source: WriteSource, cells: List<WriteCellRecord>): List<PreparedCell> {
        val sourceById = source.movements.associateBy(WriteSourceMovement::performedExerciseId)
        return cells.map { cell ->
            val movement = sourceById.getValue(cell.performedExerciseId)
            val set = movement.sets.singleOrNull { it.setNumber == cell.setNumber && !it.deleted }
            val proposed = proposedValue(movement.executionType, set, cell.field)
            PreparedCell(
                performedExerciseId = cell.performedExerciseId,
                performedSetId = set?.id,
                setNumber = cell.setNumber,
                field = cell.field,
                row = cell.row,
                column = cell.column,
                address = cell.address,
                observedValue = cell.observedValue,
                observedDisplay = cell.observedDisplay,
                action = if (proposed == null) "CLEAR" else "WRITE",
                proposedValue = proposed,
            )
        }
    }

    private fun validateAnchors(
        candidate: WriteCandidateTab,
        movements: List<WriteMovementRecord>,
        grid: SheetTabGrid,
    ) {
        require(grid.sheetId == candidate.googleSheetId) { "The selected Sheet tab changed. Scan the Sheet again." }
        val current = grid.cells.associateBy(SheetCell::address)
        require(current[candidate.weekHeaderAddress]?.display == candidate.weekHeaderValue) {
            "The Sheet week moved. Scan the Sheet again."
        }
        require(current[candidate.executionHeaderAddress]?.display == candidate.executionHeaderValue) {
            "The execution columns moved. Scan the Sheet again."
        }
        movements.forEach { movement ->
            require(current[movement.sheetMovementAddress]?.display == movement.sheetMovementText) {
                "${movement.sheetMovementText} moved in the Sheet. Scan the Sheet again."
            }
            val cell = current.getValue(movement.sheetMovementAddress)
            require(cell.row in candidate.startRow..candidate.endRow && cell.column < candidate.executionBoundaryColumn) {
                "A matched movement left the selected workout range. Scan the Sheet again."
            }
        }
    }

    private fun projectionHash(
        source: WriteSource,
        movements: List<WriteMovementRecord>,
        cells: List<PreparedCell>,
    ): String = sha256(
        buildString {
            append(source.sessionId).append('|').append(source.sessionStatus).append('|')
            source.movements.sortedBy(WriteSourceMovement::position).forEach { movement ->
                append(movement.performedExerciseId).append(':').append(movement.position).append(':')
                    .append(movement.executionType).append(';')
                movement.sets.sortedBy(WriteSourceSet::setNumber).forEach { set ->
                    append(set.id).append(':').append(set.setNumber).append(':').append(set.deleted).append(':')
                        .append(set.reps).append(':').append(set.durationSeconds).append(':')
                        .append(set.load).append(':').append(set.rir).append(';')
                }
            }
            movements.sortedBy(WriteMovementRecord::position).forEach {
                append(it.performedExerciseId).append(':').append(it.sheetMovementAddress).append(':')
                    .append(it.sheetMovementText).append(';')
            }
            cells.sortedWith(compareBy(PreparedCell::row, PreparedCell::column)).forEach {
                append(it.performedExerciseId).append(':').append(it.setNumber).append(':').append(it.field)
                    .append(':').append(it.address).append(':').append(it.action).append(':')
                    .append(it.proposedValue?.type).append(':').append(it.proposedValue?.value).append(';')
            }
        },
    )

    private fun payloadHash(cells: List<PreparedCell>): String = sha256(
        cells.sortedWith(compareBy(PreparedCell::row, PreparedCell::column)).joinToString("|") {
            "${it.address}:${it.action}:${it.proposedValue?.type}:${it.proposedValue?.value}"
        },
    )

    private fun valuesAt(grid: SheetTabGrid, addresses: List<String>): Map<String, Pair<SheetValue?, String?>> {
        val byAddress = grid.cells.associateBy(SheetCell::address)
        return addresses.associateWith { address ->
            val cell = byAddress[address]
            cell?.userEnteredValue to cell?.display
        }
    }

    private fun sameSheetValue(left: SheetValue?, right: SheetValue?): Boolean {
        if (left == null || right == null) return left == right
        if (left.type != right.type) return false
        return if (left.type == "NUMBER") {
            runCatching { BigDecimal(left.value).compareTo(BigDecimal(right.value)) == 0 }.getOrDefault(false)
        } else left.value == right.value
    }

    private fun displayValue(value: SheetValue?): String? = when (value?.type) {
        null -> null
        "BOOLEAN" -> value.value.lowercase()
        else -> value.value
    }

    private fun decodeMatching(attempt: WriteAttemptRecord): WriteMatchingSnapshot? =
        attempt.matchingSourceSnapshot?.let {
            json.decodeFromString(WriteMatchingSnapshot.serializer(), it)
        }

    private fun selectedCandidate(
        attempt: WriteAttemptRecord,
        snapshot: WriteMatchingSnapshot,
    ): WriteCandidateTab? = snapshot.candidates.singleOrNull { it.googleSheetId == attempt.targetGoogleSheetId }

    private fun addressRow(address: String): Int = Regex("^[A-Z]+(\\d+)$")
        .matchEntire(address)?.groupValues?.get(1)?.toIntOrNull()
        ?: throw IllegalArgumentException("A matched movement has an invalid Sheet address.")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun now(): OffsetDateTime = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
}

private data class ExecutionDestination(val setNumber: Int, val field: String, val column: Int)
