package me.gpipi.training.writes

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import me.gpipi.support.PersistenceTest
import me.gpipi.config.dbQuery
import me.gpipi.training.GroupAuthoringInput
import me.gpipi.training.PrescriptionAuthoringInput
import me.gpipi.training.ProgramAuthoringInput
import me.gpipi.training.ProgramCreateResult
import me.gpipi.training.SetInput
import me.gpipi.training.TrainingReadResult
import me.gpipi.training.TrainingRepository
import me.gpipi.training.TrainingService
import me.gpipi.training.WeekAuthoringInput
import me.gpipi.training.WeekOverviewRecord
import me.gpipi.training.WorkoutAuthoringInput
import me.gpipi.training.WorkoutDetailRecord
import me.gpipi.training.google.GoogleConnectionService
import me.gpipi.training.google.GoogleTokenResponse
import me.gpipi.training.google.SheetCell
import me.gpipi.training.google.SheetDiscovery
import me.gpipi.training.google.SheetTabGrid
import me.gpipi.training.google.SheetValue
import me.gpipi.training.google.SheetValueUpdate
import me.gpipi.training.google.TrainingSheetGateway
import me.gpipi.training.google.a1Address
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

class TrainingWriteServiceTest : PersistenceTest() {
    @Test
    fun `one completed workout replaces one chosen Sheet week and verifies every target`() = runBlocking {
        val clock = Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"), ZoneOffset.UTC)
        val owner = "U-WRITE"
        val training = TrainingService(db, TrainingRepository(), clock)
        assertIs<ProgramCreateResult.Created>(training.createProgram(owner, programInput()))
        val overview = found<WeekOverviewRecord>(training.overview(owner, 1))
        val workout = overview.workouts.single()
        val before = found<WorkoutDetailRecord>(training.workoutDetail(owner, 1, workout.workoutId))
        val movement = before.groups.single().exercises.single()
        training.putSet(
            owner,
            workout.weekId,
            movement.prescriptionId,
            1,
            SetInput(8, null, BigDecimal("7.5"), 2, null),
        )
        training.finish(owner, workout.weekId)
        val completed = found<WorkoutDetailRecord>(training.workoutDetail(owner, 1, workout.workoutId))
        val sourceMovementId = completed.groups.single().exercises.single().performedExerciseId!!
        val sessionId = completed.session!!.id

        val gateway = FakeWriteSheetGateway()
        val google = mockk<GoogleConnectionService>()
        coEvery { google.accessToken(owner) } returns GoogleTokenResponse("access-token", 3600)
        val matcher = mockk<TrainingWriteMatchingService>()
        coEvery { matcher.match(any(), any()) } answers {
            val source = firstArg<WriteSource>()
            val candidates = secondArg<List<WriteCandidateTab>>()
            val input = WriteMatchPayload(
                TRAINING_WRITE_MATCH_CONTRACT_VERSION,
                WriteMatchSourceWorkout(source.sessionId.toString(), source.workoutName, source.weekNumber, emptyList()),
                emptyList(),
            )
            TrainingWriteMatchResult(
                input,
                WriteMatchOutput(
                    matchedTabKey = candidates.single().key,
                    movements = listOf(
                        WriteMatchMovementOutput(sourceMovementId.toString(), "B14", "Romanian Deadlift"),
                    ),
                ),
                "provider/write-model",
            )
        }
        val service = TrainingWriteService(
            db, TrainingWriteRepository(), google, gateway, matcher, clock,
        )

        val started = ok(service.start(owner, sessionId, "sheet-id-12345"))
        assertEquals("NEEDS_TAB", started.status)
        assertEquals(listOf("Full Body WO 1", "Macro Check In"), started.availableTabs.map { it.title })

        val tabChosen = ok(
            service.chooseTab(owner, java.util.UUID.fromString(started.id), "tab-101"),
        )
        assertEquals("NEEDS_WEEK", tabChosen.status)
        assertEquals(listOf(5), tabChosen.availableWeekNumbers)

        val matched = ok(service.chooseWeek(owner, java.util.UUID.fromString(started.id), 5))
        assertEquals("REVIEW", matched.status)
        assertEquals("B14", matched.matches.single().sheetMovementAddress)

        val reviewed = ok(
            service.confirmMatches(
                owner,
                java.util.UUID.fromString(started.id),
                ConfirmTrainingWriteMatchesRequest(
                    tabKey = "tab-101",
                    movements = listOf(ConfirmedTrainingWriteMovement(sourceMovementId.toString(), "B14")),
                ),
            ),
        )
        assertEquals(true, reviewed.matches.single().confirmed)

        val preview = ok(service.prepare(owner, java.util.UUID.fromString(started.id)))
        assertEquals("PREPARED", preview.status)
        assertEquals(6, preview.cellCount)
        assertEquals(3, preview.preview.single().cells.count { it.action == "CLEAR" })

        val written = ok(service.confirm(owner, java.util.UUID.fromString(started.id)))
        assertEquals("SUCCEEDED", written.status)
        assertEquals(SheetValue("NUMBER", "8"), gateway.value("K14"))
        assertEquals(SheetValue("NUMBER", "7.5"), gateway.value("L14"))
        assertEquals(SheetValue("NUMBER", "2"), gateway.value("M14"))
        assertEquals(null, gateway.value("N14"))
        assertEquals(null, gateway.value("O14"))
        assertEquals(null, gateway.value("P14"))

        val repeated = ok(service.confirm(owner, java.util.UUID.fromString(started.id)))
        assertEquals("SUCCEEDED", repeated.status)
        assertEquals(1, gateway.batchCount)

        val retry = ok(service.start(owner, sessionId, "sheet-id-12345"))
        ok(service.chooseTab(owner, UUID.fromString(retry.id), "tab-202"))
        val unreadable = ok(service.chooseWeek(owner, UUID.fromString(retry.id), 5))
        assertEquals("NEEDS_TAB", unreadable.status)
        assertEquals("No clear execution column in Macro Check In for Sheet Week 5.", unreadable.detail)
    }

    @Test
    fun `imported workout resolves from provenance without discovery or model matching`() = runBlocking {
        val clock = Clock.fixed(Instant.parse("2026-08-13T00:00:00Z"), ZoneOffset.UTC)
        val owner = "U-IMPORTED-WRITE"
        val training = TrainingService(db, TrainingRepository(), clock)
        assertIs<ProgramCreateResult.Created>(training.createProgram(owner, programInput()))
        val overview = found<WeekOverviewRecord>(training.overview(owner, 1))
        val workout = overview.workouts.single()
        val before = found<WorkoutDetailRecord>(training.workoutDetail(owner, 1, workout.workoutId))
        val movement = before.groups.single().exercises.single()
        training.putSet(owner, workout.weekId, movement.prescriptionId, 1, SetInput(8, null, BigDecimal("7.5"), 2, null))
        training.finish(owner, workout.weekId)
        val completed = found<WorkoutDetailRecord>(training.workoutDetail(owner, 1, workout.workoutId))
        val performedMovementId = completed.groups.single().exercises.single().performedExerciseId!!
        val sessionId = completed.session!!.id
        val sheetLinkId = UUID.randomUUID()
        dbQuery(db) {
            val transaction = checkNotNull(TransactionManager.currentOrNull())
            transaction.exec(
                """
                insert into sheet_link (
                    id, program_id, spreadsheet_id, spreadsheet_title, connected_by_user_id,
                    created_at, updated_at
                ) values (
                    '$sheetLinkId', '${completed.program.id}', 'sheet-id-12345', 'JUNDA – M1', '$owner',
                    '2026-08-13T00:00:00Z', '2026-08-13T00:00:00Z'
                )
                """.trimIndent(),
            )
            transaction.exec(
                """
                insert into sheet_week_link (
                    week_id, sheet_link_id, google_sheet_id, tab_title, week_start_row, week_end_row,
                    execution_boundary_col, execution_header_address, execution_header_value,
                    source_snapshot, source_hash
                ) values (
                    '${workout.weekId}', '$sheetLinkId', 101, 'Full Body WO 1', 10, 20,
                    11, 'K10', 'Eksekusi Week 5', '{}'::jsonb, 'import-source-hash'
                )
                """.trimIndent(),
            )
            transaction.exec(
                """
                insert into sheet_prescription_link (
                    prescription_id, sheet_week_id, movement_address, movement_text,
                    source_cells, execution_cells
                ) values (
                    '${movement.prescriptionId}', '${workout.weekId}', 'B14', 'Romanian Deadlift',
                    '{}'::jsonb, '[]'::jsonb
                )
                """.trimIndent(),
            )
        }

        val gateway = FakeWriteSheetGateway()
        val google = mockk<GoogleConnectionService>()
        coEvery { google.accessToken(owner) } returns GoogleTokenResponse("access-token", 3600)
        val matcher = mockk<TrainingWriteMatchingService>()
        val service = TrainingWriteService(db, TrainingWriteRepository(), google, gateway, matcher, clock)

        val resolved = ok(service.start(owner, sessionId))

        assertEquals("RESOLVED", resolved.status)
        assertEquals("IMPORT", resolved.matches.single().matchSource)
        assertEquals(performedMovementId.toString(), resolved.matches.single().sourceMovementKey)
        assertEquals("Full Body WO 1", resolved.targetTabTitle)
        assertEquals(5, resolved.targetWeekNumber)
        assertEquals(0, gateway.discoveryCount)
        assertEquals(1, gateway.readCount)
        coVerify(exactly = 0) { matcher.match(any(), any()) }

        val preview = ok(service.prepare(owner, UUID.fromString(resolved.id)))
        assertEquals("PREPARED", preview.status)

        val resolvedAgain = ok(service.start(owner, sessionId))
        val selection = ok(service.beginSelection(owner, UUID.fromString(resolvedAgain.id)))
        assertEquals("NEEDS_TAB", selection.status)
        assertEquals(listOf("Full Body WO 1", "Macro Check In"), selection.availableTabs.map { it.title })
    }

    private fun programInput() = ProgramAuthoringInput(
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
                                        exerciseName = "Barbell RDL",
                                        createExercise = true,
                                        executionType = "REPS",
                                        sets = "2",
                                        reps = "8",
                                        load = "7.5 kg",
                                        rir = "2",
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> found(result: TrainingReadResult<T>): T =
        (result as TrainingReadResult.Found<T>).value

    private fun <T> ok(result: TrainingWriteResult<T>): T =
        assertIs<TrainingWriteResult.Ok<T>>(result).value
}

private class FakeWriteSheetGateway : TrainingSheetGateway {
    var batchCount = 0
        private set
    var discoveryCount = 0
        private set
    var readCount = 0
        private set
    private val values = linkedMapOf(
        "A10" to SheetCell(10, 1, "A10", "Week 5", SheetValue("STRING", "Week 5")),
        "K10" to SheetCell(10, 11, "K10", "Eksekusi Week 5", SheetValue("STRING", "Eksekusi Week 5")),
        "K11" to SheetCell(11, 11, "K11", "Reps", SheetValue("STRING", "Reps")),
        "L11" to SheetCell(11, 12, "L11", "Load", SheetValue("STRING", "Load")),
        "M11" to SheetCell(11, 13, "M11", "RIR", SheetValue("STRING", "RIR")),
        "N11" to SheetCell(11, 14, "N11", "Reps", SheetValue("STRING", "Reps")),
        "O11" to SheetCell(11, 15, "O11", "Load", SheetValue("STRING", "Load")),
        "P11" to SheetCell(11, 16, "P11", "RIR", SheetValue("STRING", "RIR")),
        "B14" to SheetCell(14, 2, "B14", "Romanian Deadlift", SheetValue("STRING", "Romanian Deadlift")),
        "K14" to SheetCell(14, 11, "K14", "10", SheetValue("NUMBER", "10")),
        "L14" to SheetCell(14, 12, "L14", "5", SheetValue("NUMBER", "5")),
        "M14" to SheetCell(14, 13, "M14", "3", SheetValue("NUMBER", "3")),
        "N14" to SheetCell(14, 14, "N14", "10", SheetValue("NUMBER", "10")),
        "O14" to SheetCell(14, 15, "O14", "5", SheetValue("NUMBER", "5")),
        "P14" to SheetCell(14, 16, "P14", "3", SheetValue("NUMBER", "3")),
    )

    override suspend fun discover(accessToken: String, spreadsheetId: String): SheetDiscovery {
        discoveryCount++
        return SheetDiscovery("JUNDA – M1", listOf(grid(), macroGrid()))
    }

    override suspend fun readSelectedRange(
        accessToken: String,
        spreadsheetId: String,
        sheetId: Long,
        tabTitle: String,
        startRow: Int,
        endRow: Int,
        executionBoundaryColumn: Int,
    ): SheetTabGrid {
        readCount++
        return grid()
    }

    override suspend fun batchUpdateValues(
        accessToken: String,
        spreadsheetId: String,
        sheetId: Long,
        updates: List<SheetValueUpdate>,
    ) {
        batchCount++
        updates.forEach { update ->
            val address = a1Address(update.row, update.column)
            if (update.value == null) {
                values.remove(address)
            } else {
                values[address] = SheetCell(update.row, update.column, address, update.value.value, update.value)
            }
        }
    }

    fun value(address: String) = values[address]?.userEnteredValue

    private fun grid() = SheetTabGrid(
        sheetId = 101,
        title = "Full Body WO 1",
        position = 1,
        rowCount = 40,
        columnCount = 20,
        cells = values.values.toList(),
    )

    private fun macroGrid() = SheetTabGrid(
        sheetId = 202,
        title = "Macro Check In",
        position = 2,
        rowCount = 40,
        columnCount = 8,
        cells = listOf(
            SheetCell(3, 1, "A3", "Week 5", SheetValue("STRING", "Week 5")),
            SheetCell(4, 1, "A4", "Body weight", SheetValue("STRING", "Body weight")),
        ),
    )
}
