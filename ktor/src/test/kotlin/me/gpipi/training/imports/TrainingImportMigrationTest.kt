package me.gpipi.training.imports

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
import me.gpipi.training.google.SheetTabGrid
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class TrainingImportMigrationTest : PersistenceTest() {
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    @Test
    fun `iteration 2 tables and one-week constraint columns exist`() {
        val tables = transaction(db) {
            exec(
                """
                select table_name
                from information_schema.tables
                where table_schema = 'public'
                  and table_name in (
                    'google_credential', 'google_oauth_state', 'training_import',
                    'training_import_tab', 'training_import_week',
                    'training_import_exercise_match', 'sheet_link',
                    'sheet_week_link', 'sheet_prescription_link'
                  )
                order by table_name
                """.trimIndent(),
            ) { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }.orEmpty()
        }
        assertEquals(9, tables.size)
    }

    @Test
    fun `apply creates only the selected prescribed week and never execution rows`() = runBlocking {
        val owner = "U-IMPORT-OWNER"
        val training = TrainingService(db, TrainingRepository())
        val programId = assertIs<ProgramCreateResult.Created>(
            training.createProgram(
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
        val repository = TrainingImportRepository()
        val now = OffsetDateTime.of(2026, 8, 10, 0, 0, 0, 0, ZoneOffset.UTC)
        val workoutId = dbQuery(db) { repository.workouts(owner, programId).single().id }
        val importId = dbQuery(db) {
            repository.createImport(
                owner,
                programId,
                "selected-sheet",
                "JUNDA – M1",
                listOf(SheetTabGrid(101, "Full Body 1", 1, 100, 20, emptyList())),
                now,
            )
        }
        dbQuery(db) {
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
        }
        val movement = ExtractedTrainingPrescription(
            movement = "DB romanian deadlift",
            movementAddress = "A75",
            executionTypeProposal = "REPS_PER_SIDE",
            sets = "3",
            reps = "8 each",
            sourceCells = ExtractedSourceCells(movement = "A75", sets = "C75", reps = "E75"),
        )
        val draft = TrainingPrescriptionExtraction(
            listOf(ExtractedTrainingGroup("STRAIGHT SET", "A74", "STRAIGHT_SET", listOf(movement))),
        )
        val snapshot = RedactedTrainingSourceSnapshot(
            prescription = PrescriptionPayload(
                TRAINING_EXTRACTION_CONTRACT_VERSION,
                5,
                SelectedRangePayload("A72:J91", 72, 91),
                PrescriptionColumnsPayload("A", "J"),
                emptyList(),
                emptyList(),
            ),
            executionLayout = listOf(ExecutionLayoutCell("K73", 73, 11, "Reps")),
        )
        dbQuery(db) {
            val week = repository.weeks(importId).single()
            repository.saveExtraction(
                week.id,
                json.encodeToString(draft),
                "provider/training-model",
                json.encodeToString(snapshot),
                "source-hash",
            )
            repository.initializeMatches(week.id, draft)
            repository.saveReviewedDraft(
                week.id,
                json.encodeToString(draft),
                listOf(
                    ReviewedTrainingPrescription(
                        movement = movement.movement,
                        movementAddress = movement.movementAddress,
                        sets = movement.sets,
                        reps = movement.reps,
                        sourceCells = movement.sourceCells,
                        decision = "CREATE",
                        newExerciseName = "Romanian deadlift",
                        executionType = "REPS_PER_SIDE",
                    ),
                ),
            )
            repository.transition(importId, "REVIEW", null, now)
        }

        val appliedWeek = dbQuery(db) {
            val header = requireNotNull(repository.header(owner, importId))
            val tab = repository.tabs(importId).single()
            val week = repository.weeks(importId).single()
            val matches = repository.matches(importId).associateBy { it.sourceMovementKey }
            repository.apply(owner, header, listOf(AppliedImportWorkout(tab, week, draft, matches)), now, json)
        }

        assertEquals(5, appliedWeek)
        val counts = transaction(db) {
            exec(
                """
                select
                    count(*) filter (where ww.week_number = 5) as selected_weeks,
                    count(*) filter (where ww.week_number in (2, 3, 4, 6)) as unrelated_weeks,
                    (select count(*) from training_session) as sessions,
                    (select count(*) from performed_exercise) as performed_exercises,
                    (select count(*) from performed_set) as performed_sets,
                    (select count(*) from sheet_prescription_link) as prescription_links
                from workout_week ww
                join workout w on w.id = ww.workout_id
                where w.program_id = '$programId'
                """.trimIndent(),
            ) { rs ->
                rs.next()
                listOf(
                    rs.getInt("selected_weeks"), rs.getInt("unrelated_weeks"), rs.getInt("sessions"),
                    rs.getInt("performed_exercises"), rs.getInt("performed_sets"), rs.getInt("prescription_links"),
                )
            }
        }
        assertEquals(listOf(1, 0, 0, 0, 0, 1), counts)
    }

}
