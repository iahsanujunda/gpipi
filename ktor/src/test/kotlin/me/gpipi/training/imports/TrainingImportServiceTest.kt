package me.gpipi.training.imports

import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.gpipi.ai.ChatResult
import me.gpipi.ai.OpenRouterClient
import me.gpipi.config.dbQuery
import me.gpipi.support.PersistenceTest
import me.gpipi.training.GroupAuthoringInput
import me.gpipi.training.PrescriptionAuthoringInput
import me.gpipi.training.ProgramAuthoringInput
import me.gpipi.training.ProgramCreateResult
import me.gpipi.training.TrainingRepository
import me.gpipi.training.TrainingService
import me.gpipi.training.WeekAuthoringInput
import me.gpipi.training.WorkoutAuthoringInput
import me.gpipi.training.google.GoogleConnectionService
import me.gpipi.training.google.GoogleCredentialCipher
import me.gpipi.training.google.GoogleCredentialRepository
import me.gpipi.training.google.GoogleOAuthClient
import me.gpipi.training.google.GoogleSettings
import me.gpipi.training.google.GoogleTokenResponse
import me.gpipi.training.google.SheetCell
import me.gpipi.training.google.SheetDiscovery
import me.gpipi.training.google.SheetTabGrid
import me.gpipi.training.google.TrainingSheetGateway

class TrainingImportServiceTest : PersistenceTest() {
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private val clock = Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC)
    private val now = OffsetDateTime.now(clock)
    private val repository = TrainingImportRepository()
    private val settings = GoogleSettings(
        clientId = "client-id",
        clientSecret = "client-secret",
        redirectUri = "https://app.test/api/training/google/callback",
        pickerApiKey = "picker-key",
        appId = "123456789",
        credentialEncryptionKey = "unused-in-service",
    )

    @Test
    fun `every import action rejects a member who does not own the import`() = runBlocking {
        val owner = "U-IMPORT-OWNER"
        val programId = createProgramWithWorkout(owner)
        val importId = seedMappedImport(owner, programId)
        // Google, Sheets, and the model are never reached: ownership fails first. Any call
        // to them would throw from these unstubbed mocks and fail the test loudly.
        val service = buildService(
            google = GoogleConnectionService(db, GoogleCredentialRepository(), null, null, settings, clock),
            sheets = mockk(),
            client = mockk(),
        )
        val other = "U-OTHER"

        assertEquals(TrainingImportResult.NotFound, service.get(other, importId))
        assertEquals(TrainingImportResult.NotFound, service.chooseWeek(other, importId, 5))
        assertEquals(TrainingImportResult.NotFound, service.saveMapping(other, importId, SaveTrainingMappingRequest(emptyList())))
        assertEquals(TrainingImportResult.NotFound, service.extract(other, importId))
        assertEquals(TrainingImportResult.NotFound, service.saveReview(other, importId, SaveTrainingReviewRequest(emptyList())))
        assertEquals(TrainingImportResult.NotFound, service.apply(other, importId))
        assertEquals(TrainingImportResult.NotFound, service.cancel(other, importId))
        // start against a program the caller does not own is also not found.
        assertEquals(TrainingImportResult.NotFound, service.start(other, programId, "spreadsheet01"))

        // The owner's import is untouched by the rejected attempts.
        assertIs<TrainingImportResult.Ok<TrainingImportResponse>>(service.get(owner, importId))
    }

    @Test
    fun `extraction sends no execution values to the model and persists none in the snapshot`() = runBlocking {
        val owner = "U-EXTRACT-OWNER"
        val programId = createProgramWithWorkout(owner)
        val importId = seedMappedImport(owner, programId)
        dbQuery(db) {
            GoogleCredentialRepository().saveCredential(
                owner,
                credentialCipher().encrypt("refresh-token"),
                "https://www.googleapis.com/auth/drive.file",
                now,
            )
        }

        val userMessage = slot<String>()
        val client = mockk<OpenRouterClient>()
        coEvery { client.chat(capture(userMessage), any(), any(), any()) } returns
            ChatResult(json.encodeToString(TrainingPrescriptionExtraction.serializer(), extractedDraft()), "test-model")
        val oauth = mockk<GoogleOAuthClient>()
        coEvery { oauth.refresh(any()) } returns GoogleTokenResponse("access-token", 3600)
        val google = GoogleConnectionService(db, GoogleCredentialRepository(), oauth, credentialCipher(), settings, clock)
        val service = buildService(google = google, sheets = FakeSheetGateway(gridWithExecutionValues()), client = client)

        val result = service.extract(owner, importId)
        assertIs<TrainingImportResult.Ok<TrainingImportResponse>>(result)

        // The model request carried the prescription cell but neither execution value.
        assertTrue(userMessage.captured.contains("8 each"))
        assertFalse(userMessage.captured.contains(EXECUTION_REPS))
        assertFalse(userMessage.captured.contains(EXECUTION_LOAD))

        // The persisted source snapshot keeps the prescription and the execution header for
        // later write-back, but no execution value.
        val snapshot = dbQuery(db) { repository.weeks(importId).single().sourceSnapshot }
        assertNotNull(snapshot)
        assertTrue(snapshot.contains("8 each"))
        assertTrue(snapshot.contains("Eksekusi Week 5"))
        assertFalse(snapshot.contains(EXECUTION_REPS))
        assertFalse(snapshot.contains(EXECUTION_LOAD))
    }

    @Test
    fun `chooseWeek rejects a non-positive week before any Google read`() = runBlocking {
        val owner = "U-WEEK-OWNER"
        val importId = seedMappedImport(owner, createProgramWithWorkout(owner))
        val service = rejectingService()

        assertIs<TrainingImportResult.Invalid>(service.chooseWeek(owner, importId, 0))
    }

    @Test
    fun `saveMapping rejects unconfirmed tabs, a foreign workout, an out-of-range header, and an all-excluded map`() =
        runBlocking {
            val owner = "U-MAP-OWNER"
            val programId = createProgramWithWorkout(owner)
            val importId = seedMappedImport(owner, programId)
            val workoutId = dbQuery(db) { repository.workouts(owner, programId).single().id }.toString()
            val service = rejectingService()

            // The request must cover exactly the discovered tabs (google_sheet_id 101).
            assertIs<TrainingImportResult.Invalid>(
                service.saveMapping(owner, importId, SaveTrainingMappingRequest(emptyList())),
            )
            // A WORKOUT tab pointed at a workout that is not in this program.
            assertIs<TrainingImportResult.Invalid>(
                service.saveMapping(
                    owner,
                    importId,
                    SaveTrainingMappingRequest(listOf(mappingRequest(targetWorkoutId = UUID.randomUUID().toString()))),
                ),
            )
            // Execution header outside the confirmed row range.
            assertIs<TrainingImportResult.Invalid>(
                service.saveMapping(
                    owner,
                    importId,
                    SaveTrainingMappingRequest(
                        listOf(mappingRequest(targetWorkoutId = workoutId, executionHeaderAddress = "K200")),
                    ),
                ),
            )
            // No included workout tab at all.
            assertIs<TrainingImportResult.Invalid>(
                service.saveMapping(
                    owner,
                    importId,
                    SaveTrainingMappingRequest(listOf(TrainingTabMappingRequest(googleSheetId = 101, decision = "EXCLUDE"))),
                ),
            )
        }

    @Test
    fun `apply refuses an import that has not reached review`() = runBlocking {
        val owner = "U-APPLY-OWNER"
        val importId = seedMappedImport(owner, createProgramWithWorkout(owner))

        assertIs<TrainingImportResult.Conflict>(rejectingService().apply(owner, importId))
    }

    @Test
    fun `review and apply reject bad weeks, altered movement keys, and unresolved decisions`() = runBlocking {
        val owner = "U-REVIEW-OWNER"
        val programId = createProgramWithWorkout(owner)
        val importId = seedMappedImport(owner, programId)
        val service = extractingService(owner)
        assertIs<TrainingImportResult.Ok<TrainingImportResponse>>(service.extract(owner, importId))
        val weekId = dbQuery(db) { repository.weeks(importId).single().id }.toString()

        // A review that names a week not in this import.
        assertIs<TrainingImportResult.Invalid>(
            service.saveReview(owner, importId, reviewRequest(UUID.randomUUID().toString(), movementAddress = "A74", decision = "CREATE", newExerciseName = "RDL", executionType = "REPS")),
        )
        // A review that changes an extracted movement source key.
        assertIs<TrainingImportResult.Invalid>(
            service.saveReview(owner, importId, reviewRequest(weekId, movementAddress = "A99", decision = "CREATE", newExerciseName = "RDL", executionType = "REPS")),
        )
        // MATCH without an exercise, and a missing execution type.
        assertIs<TrainingImportResult.Invalid>(
            service.saveReview(owner, importId, reviewRequest(weekId, movementAddress = "A74", decision = "MATCH", exerciseId = null, executionType = "REPS")),
        )
        assertIs<TrainingImportResult.Invalid>(
            service.saveReview(owner, importId, reviewRequest(weekId, movementAddress = "A74", decision = "CREATE", newExerciseName = "RDL", executionType = null)),
        )
        // Apply before any movement decision is resolved.
        assertIs<TrainingImportResult.Invalid>(service.apply(owner, importId))
    }

    private fun mappingRequest(
        targetWorkoutId: String? = null,
        executionHeaderAddress: String = "K72",
    ) = TrainingTabMappingRequest(
        googleSheetId = 101,
        decision = "WORKOUT",
        targetWorkoutId = targetWorkoutId,
        newWorkoutName = if (targetWorkoutId == null) "New workout" else null,
        startRow = 72,
        endRow = 91,
        executionBoundaryColumn = 11,
        executionHeaderAddress = executionHeaderAddress,
        executionHeaderValue = "Eksekusi Week 5",
    )

    private fun reviewRequest(
        importWeekId: String,
        movementAddress: String,
        decision: String,
        exerciseId: String? = null,
        newExerciseName: String? = null,
        executionType: String? = null,
    ) = SaveTrainingReviewRequest(
        listOf(
            ReviewedWorkoutDraft(
                importWeekId = importWeekId,
                groups = listOf(
                    ReviewedTrainingGroup(
                        label = "STRAIGHT SET",
                        labelAddress = "A73",
                        kind = "STRAIGHT_SET",
                        prescriptions = listOf(
                            ReviewedTrainingPrescription(
                                movement = "DB romanian deadlift",
                                movementAddress = movementAddress,
                                sets = "3",
                                reps = "8 each",
                                sourceCells = ExtractedSourceCells(movement = "A74", sets = "C74", reps = "E74"),
                                decision = decision,
                                exerciseId = exerciseId,
                                newExerciseName = newExerciseName,
                                executionType = executionType,
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun rejectingService() = buildService(
        google = GoogleConnectionService(db, GoogleCredentialRepository(), null, null, settings, clock),
        sheets = mockk(),
        client = mockk(),
    )

    private suspend fun extractingService(owner: String): TrainingImportService {
        dbQuery(db) {
            GoogleCredentialRepository().saveCredential(
                owner,
                credentialCipher().encrypt("refresh-token"),
                "https://www.googleapis.com/auth/drive.file",
                now,
            )
        }
        val client = mockk<OpenRouterClient>()
        coEvery { client.chat(any(), any(), any(), any()) } returns
            ChatResult(json.encodeToString(TrainingPrescriptionExtraction.serializer(), extractedDraft()), "test-model")
        val oauth = mockk<GoogleOAuthClient>()
        coEvery { oauth.refresh(any()) } returns GoogleTokenResponse("access-token", 3600)
        val google = GoogleConnectionService(db, GoogleCredentialRepository(), oauth, credentialCipher(), settings, clock)
        return buildService(google, FakeSheetGateway(gridWithExecutionValues()), client)
    }

    private fun buildService(
        google: GoogleConnectionService,
        sheets: TrainingSheetGateway,
        client: OpenRouterClient,
    ) = TrainingImportService(
        db = db,
        repository = repository,
        google = google,
        sheets = sheets,
        extractor = TrainingPrescriptionExtractionService(client),
        clock = clock,
        json = json,
    )

    private fun credentialCipher() =
        GoogleCredentialCipher(Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))

    private suspend fun createProgramWithWorkout(owner: String): UUID =
        assertIs<ProgramCreateResult.Created>(
            TrainingService(db, TrainingRepository()).createProgram(
                owner,
                ProgramAuthoringInput(
                    name = "M1",
                    workouts = listOf(
                        WorkoutAuthoringInput(
                            name = "Full Body 1",
                            weeks = listOf(
                                WeekAuthoringInput(
                                    1,
                                    listOf(
                                        GroupAuthoringInput(
                                            "A",
                                            "STRAIGHT_SET",
                                            listOf(
                                                PrescriptionAuthoringInput(
                                                    exerciseName = "Existing movement",
                                                    createExercise = true,
                                                    executionType = "REPS",
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ).id

    private suspend fun seedMappedImport(owner: String, programId: UUID): UUID = dbQuery(db) {
        val workoutId = repository.workouts(owner, programId).single().id
        val importId = repository.createImport(
            owner,
            programId,
            "spreadsheet01",
            "JUNDA – M1",
            listOf(SheetTabGrid(101, "Full Body 1", 1, 100, 20, emptyList())),
            now,
        )
        repository.chooseWeek(importId, 5, now)
        val tab = repository.tabs(importId).single()
        repository.saveMapping(
            importId,
            5,
            listOf(
                ResolvedTabMapping(
                    tabId = tab.id,
                    decision = "WORKOUT",
                    targetWorkoutId = workoutId,
                    newWorkoutName = null,
                    startRow = 72,
                    endRow = 91,
                    executionBoundaryColumn = 11,
                    executionHeaderAddress = "K72",
                    executionHeaderValue = "Eksekusi Week 5",
                ),
            ),
            now,
        )
        importId
    }

    private fun gridWithExecutionValues() = SheetTabGrid(
        sheetId = 101,
        title = "Full Body 1",
        position = 1,
        rowCount = 100,
        columnCount = 20,
        cells = listOf(
            SheetCell(72, 1, "A72", "Week 5"),
            SheetCell(73, 1, "A73", "STRAIGHT SET"),
            SheetCell(74, 1, "A74", "DB romanian deadlift"),
            SheetCell(74, 3, "C74", "3"),
            SheetCell(74, 5, "E74", "8 each"),
            SheetCell(72, 11, "K72", "Eksekusi Week 5"),
            SheetCell(73, 11, "K73", "Reps"),
            SheetCell(74, 11, "K74", EXECUTION_REPS),
            SheetCell(74, 12, "L74", EXECUTION_LOAD),
        ),
    )

    private fun extractedDraft() = TrainingPrescriptionExtraction(
        listOf(
            ExtractedTrainingGroup(
                label = "STRAIGHT SET",
                labelAddress = "A73",
                kind = "STRAIGHT_SET",
                prescriptions = listOf(
                    ExtractedTrainingPrescription(
                        movement = "DB romanian deadlift",
                        movementAddress = "A74",
                        executionTypeProposal = "REPS_PER_SIDE",
                        sets = "3",
                        reps = "8 each",
                        sourceCells = ExtractedSourceCells(movement = "A74", sets = "C74", reps = "E74"),
                    ),
                ),
            ),
        ),
    )

    private class FakeSheetGateway(private val grid: SheetTabGrid) : TrainingSheetGateway {
        override suspend fun discover(accessToken: String, spreadsheetId: String) =
            SheetDiscovery("Full Body", listOf(grid))

        override suspend fun readSelectedRange(
            accessToken: String,
            spreadsheetId: String,
            sheetId: Long,
            tabTitle: String,
            startRow: Int,
            endRow: Int,
            executionBoundaryColumn: Int,
        ) = grid
    }

    private companion object {
        const val EXECUTION_REPS = "EXECREPS9"
        const val EXECUTION_LOAD = "EXECLOAD9"
    }
}
