package me.gpipi.training.writes

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.gpipi.config.dbQuery
import me.gpipi.support.PersistenceTest
import me.gpipi.training.GroupAuthoringInput
import me.gpipi.training.PrescriptionAuthoringInput
import me.gpipi.training.ProgramAuthoringInput
import me.gpipi.training.ProgramCreateResult
import me.gpipi.training.SetInput
import me.gpipi.training.TrainingReadResult
import me.gpipi.training.TrainingRepository
import me.gpipi.training.TrainingService
import me.gpipi.training.WeekAuthoringInput
import me.gpipi.training.WorkoutAuthoringInput
import kotlin.test.assertIs

class TrainingWriteRepositoryTest : PersistenceTest() {
    @Test
    fun `completed session creates an owner-bound durable write attempt`() = runBlocking {
        val owner = "U-WRITE"
        val training = TrainingService(db, TrainingRepository())
        val created = assertIs<ProgramCreateResult.Created>(
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
                                                    exerciseName = "Barbell RDL",
                                                    createExercise = true,
                                                    executionType = "REPS",
                                                    sets = "3",
                                                    reps = "8",
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
        )
        val overview = assertIs<TrainingReadResult.Found<*>>(training.overview(owner, 1)).value
            as me.gpipi.training.WeekOverviewRecord
        val workout = overview.workouts.single()
        val detail = assertIs<TrainingReadResult.Found<*>>(
            training.workoutDetail(owner, 1, workout.workoutId),
        ).value as me.gpipi.training.WorkoutDetailRecord
        val movement = detail.groups.single().exercises.single()
        training.putSet(
            owner,
            workout.weekId,
            movement.prescriptionId,
            1,
            SetInput(8, null, BigDecimal("7.5"), 2, null),
        )
        training.finish(owner, workout.weekId)
        val completed = assertIs<TrainingReadResult.Found<*>>(
            training.workoutDetail(owner, 1, workout.workoutId),
        ).value as me.gpipi.training.WorkoutDetailRecord
        val sessionId = assertNotNull(completed.session).id
        val repository = TrainingWriteRepository()
        val source = assertNotNull(dbQuery(db) { repository.source(owner, sessionId) })
        val now = OffsetDateTime.of(2026, 8, 13, 0, 0, 0, 0, ZoneOffset.UTC)
        val snapshot = Json.encodeToString(WriteDiscoverySnapshot("JUNDA – M1", listOf(1, 5)))

        val attemptId = dbQuery(db) {
            repository.createAttempt(
                source, "sheet-id-12345", "JUNDA – M1", listOf(1, 5), snapshot,
                status = "NEEDS_WEEK", detail = null, now = now,
            )
        }
        val stored = assertNotNull(dbQuery(db) { repository.attempt(owner, attemptId) })

        assertEquals(created.id, stored.programId)
        assertEquals(sessionId, stored.sessionId)
        assertEquals(listOf(1, 5), stored.availableWeekNumbers)
        assertEquals("NEEDS_WEEK", stored.status)
        assertNull(dbQuery(db) { repository.attempt("U-OTHER", attemptId) })
    }
}
