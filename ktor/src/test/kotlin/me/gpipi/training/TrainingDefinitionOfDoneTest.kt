package me.gpipi.training

import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Behaviours mandated by the iteration-1 Definition of Done that the existing suite does not
 * exercise. Each test asserts the behaviour named in the DoD rather than an incidental
 * implementation detail.
 */
class TrainingDefinitionOfDoneTest : PersistenceTest() {
    private val clock = MutableClock(Instant.parse("2026-08-10T03:00:00Z"))
    private val service = TrainingService(
        db = db,
        repository = TrainingRepository(),
        clock = clock,
    )

    // ---- Gap A: Resume workout ----------------------------------------------------------------

    @Test
    fun `resume reopens a completed session and makes its week current again`() = runBlocking {
        createProgram(listOf("Strength A"))
        val (workoutId, weekId, prescriptionId) = week1("Strength A")

        service.putSet(OWNER, weekId, prescriptionId, 1, reps(8))
        assertEquals(TrainingMutationResult.Updated, service.finish(OWNER, weekId))

        val completed = session(workoutId)
        assertEquals("COMPLETED", completed.status)
        assertNotNull(completed.completedAt)
        // Single workout: once its week is completed, the next authored week becomes current.
        assertEquals(2, found(service.overview(OWNER, null)).currentWeekNumber)

        clock.advance(60)
        assertEquals(TrainingMutationResult.Updated, service.resume(OWNER, weekId))

        val resumed = session(workoutId)
        assertEquals("IN_PROGRESS", resumed.status)
        assertNull(resumed.completedAt)
        // Reopening restores the week as the current one; resume is not a calendar derivation.
        assertEquals(1, found(service.overview(OWNER, null)).currentWeekNumber)
    }

    // ---- Gap B: execution vs metadata timestamp split (the iteration-3 sync hinge) -------------

    @Test
    fun `execution edits advance execution_updated_at but metadata edits advance only updated_at`() =
        runBlocking {
            createProgram(listOf("Strength A"))
            val (_, weekId, prescriptionId) = week1("Strength A")

            val t0 = clock.instant()
            service.putSet(OWNER, weekId, prescriptionId, 1, reps(8))
            stamps(weekId).let {
                assertEquals(t0, it.updatedAt, "logging a set advances session updated_at")
                assertEquals(t0, it.executionUpdatedAt, "logging a set advances execution_updated_at")
            }

            clock.advance(60)
            val t1 = clock.instant()
            service.updateSession(OWNER, weekId, LocalDate.parse("2026-07-01"), "moved to July")
            stamps(weekId).let {
                assertEquals(t1, it.updatedAt, "a metadata edit advances updated_at")
                assertEquals(
                    t0,
                    it.executionUpdatedAt,
                    "a metadata-only edit must NOT advance execution_updated_at",
                )
            }

            clock.advance(60)
            val t2 = clock.instant()
            service.putSet(OWNER, weekId, prescriptionId, 1, reps(9))
            stamps(weekId).let {
                assertEquals(t2, it.updatedAt, "editing a set advances updated_at")
                assertEquals(t2, it.executionUpdatedAt, "editing a set's reps advances execution_updated_at")
            }
        }

    @Test
    fun `editing only a set note does not mark the session unsynced`() = runBlocking {
        // DoD: "Editing metadata that is not written to the sheet, such as ... a set note,
        // updates updated_at only and does not by itself require another sheet write."
        createProgram(listOf("Strength A"))
        val (_, weekId, prescriptionId) = week1("Strength A")

        val loggedAt = clock.instant()
        service.putSet(OWNER, weekId, prescriptionId, 1, reps(8, note = null))
        assertEquals(loggedAt, stamps(weekId).executionUpdatedAt)
        assertEquals(loggedAt, setUpdatedAt(weekId, prescriptionId, 1))

        clock.advance(60)
        val noteEditedAt = clock.instant()
        // Same measured values, only the note changes.
        service.putSet(OWNER, weekId, prescriptionId, 1, reps(8, note = "felt strong"))

        stamps(weekId).let {
            assertEquals(noteEditedAt, it.updatedAt, "a set-note edit still advances updated_at")
            assertEquals(
                loggedAt,
                it.executionUpdatedAt,
                "a set-note-only edit must not re-mark the session unsynced",
            )
        }
        assertEquals(
            noteEditedAt,
            setUpdatedAt(weekId, prescriptionId, 1),
            "a set-note edit advances the performed set's own updated_at",
        )
    }

    // ---- Gap C: skip / restore / completed-vs-skipped ------------------------------------------

    @Test
    fun `logging a skipped week restores it automatically`() = runBlocking {
        createProgram(listOf("Strength A"))
        val (workoutId, weekId, prescriptionId) = week1("Strength A")

        assertEquals(TrainingMutationResult.Updated, service.skip(OWNER, weekId))
        assertNotNull(skippedAt(weekId))

        assertEquals(
            TrainingMutationResult.Updated,
            service.putSet(OWNER, weekId, prescriptionId, 1, reps(8)),
        )

        assertNull(skippedAt(weekId), "logging into a skipped week clears skipped_at")
        assertEquals(1, setCount(workoutId, prescriptionId), "the logged set persists after auto-restore")
    }

    @Test
    fun `a completed week cannot also be skipped`() = runBlocking {
        createProgram(listOf("Strength A"))
        val (_, weekId, prescriptionId) = week1("Strength A")

        service.putSet(OWNER, weekId, prescriptionId, 1, reps(8))
        assertEquals(TrainingMutationResult.Updated, service.finish(OWNER, weekId))

        assertNotEquals(
            TrainingMutationResult.Updated,
            service.skip(OWNER, weekId),
            "skip must be refused on a week with a completed session",
        )
        assertNull(skippedAt(weekId))
    }

    @Test
    fun `finishing a skipped week restores it before completing`() = runBlocking {
        createProgram(listOf("Strength A"))
        val (workoutId, weekId, _) = week1("Strength A")

        assertEquals(TrainingMutationResult.Updated, service.skip(OWNER, weekId))
        assertEquals(
            TrainingMutationResult.Updated,
            service.finish(OWNER, weekId),
            "finishing is an explicit claim that the skipped workout was performed",
        )

        assertEquals("COMPLETED", session(workoutId).status)
        assertNull(skippedAt(weekId), "finishing a skipped workout restores it atomically")
    }

    // ---- Gap D: no fixed-cadence assumption (1 and 3 workouts) ---------------------------------

    @Test
    fun `a one-workout block advances after its single workout is resolved`() = runBlocking {
        createProgram(listOf("Only"))
        assertEquals(1, found(service.overview(OWNER, 1)).workouts.size)
        val (_, weekId, _) = week1("Only")

        assertEquals(1, found(service.overview(OWNER, null)).currentWeekNumber)
        assertEquals(TrainingMutationResult.Updated, service.finish(OWNER, weekId))
        assertEquals(2, found(service.overview(OWNER, null)).currentWeekNumber)
    }

    @Test
    fun `a three-workout block stays on the week until all three are resolved`() = runBlocking {
        createProgram(listOf("Day A", "Day B", "Day C"))
        assertEquals(3, found(service.overview(OWNER, 1)).workouts.size)

        val (_, weekA, _) = week1("Day A")
        val (_, weekB, _) = week1("Day B")
        val (_, weekC, _) = week1("Day C")

        assertEquals(TrainingMutationResult.Updated, service.finish(OWNER, weekA))
        assertEquals(1, found(service.overview(OWNER, null)).currentWeekNumber, "one of three resolved")

        assertEquals(TrainingMutationResult.Updated, service.skip(OWNER, weekB))
        assertEquals(1, found(service.overview(OWNER, null)).currentWeekNumber, "two of three resolved")

        assertEquals(TrainingMutationResult.Updated, service.finish(OWNER, weekC))
        assertEquals(2, found(service.overview(OWNER, null)).currentWeekNumber, "all three resolved")
    }

    // ---- helpers ------------------------------------------------------------------------------

    private suspend fun createProgram(workoutNames: List<String>) {
        assertIs<ProgramCreateResult.Created>(
            service.createProgram(OWNER, programInput(workoutNames)),
        )
    }

    /** workoutId, week-1 weekId, week-1 first prescriptionId */
    private suspend fun week1(workoutName: String): Triple<UUID, UUID, UUID> {
        val workout = found(service.overview(OWNER, 1)).workouts.single { it.workoutName == workoutName }
        val detail = found(service.workoutDetail(OWNER, 1, workout.workoutId))
        val prescriptionId = detail.groups.first().exercises.first().prescriptionId
        return Triple(workout.workoutId, detail.weekId, prescriptionId)
    }

    private suspend fun session(workoutId: UUID): TrainingSessionRecord =
        found(service.workoutDetail(OWNER, 1, workoutId)).session
            ?: error("expected a session for $workoutId")

    private suspend fun setCount(workoutId: UUID, prescriptionId: UUID): Int =
        found(service.workoutDetail(OWNER, 1, workoutId))
            .groups.flatMap { it.exercises }
            .single { it.prescriptionId == prescriptionId }
            .sets.size

    private fun stamps(weekId: UUID): SessionStamps = transaction(db) {
        exec(
            "select updated_at, execution_updated_at from training_session where week_id = '$weekId'",
        ) { rs ->
            if (rs.next()) {
                SessionStamps(
                    updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
                    executionUpdatedAt = rs.getObject("execution_updated_at", OffsetDateTime::class.java)
                        ?.toInstant(),
                )
            } else {
                null
            }
        }
    } ?: error("no session for week $weekId")

    private fun skippedAt(weekId: UUID): OffsetDateTime? = transaction(db) {
        exec("select skipped_at from workout_week where id = '$weekId'") { rs ->
            if (rs.next()) rs.getObject("skipped_at", OffsetDateTime::class.java) else null
        }
    }

    private fun setUpdatedAt(weekId: UUID, prescriptionId: UUID, setNumber: Int): Instant =
        transaction(db) {
            exec(
                """
                select ps.updated_at
                from performed_set ps
                join performed_exercise pe on pe.id = ps.performed_exercise_id
                join training_session s on s.id = pe.session_id
                where s.week_id = '$weekId'
                  and pe.prescription_id = '$prescriptionId'
                  and ps.set_number = $setNumber
                """.trimIndent(),
            ) { rs ->
                if (rs.next()) rs.getObject("updated_at", OffsetDateTime::class.java).toInstant()
                else null
            }
        } ?: error("no set $setNumber for prescription $prescriptionId")

    private fun reps(value: Int, note: String? = null) = SetInput(
        reps = value,
        durationSeconds = null,
        load = BigDecimal("20.0"),
        rir = 2,
        note = note,
    )

    private fun programInput(workoutNames: List<String>) = ProgramAuthoringInput(
        name = "M1",
        startsOn = LocalDate.parse("2026-08-03"),
        workouts = workoutNames.map { workoutName ->
            WorkoutAuthoringInput(
                name = workoutName,
                weeks = listOf(1, 2).map { week ->
                    WeekAuthoringInput(
                        weekNumber = week,
                        groups = listOf(
                            GroupAuthoringInput(
                                label = "A",
                                kind = "STRAIGHT_SET",
                                prescriptions = listOf(
                                    PrescriptionAuthoringInput(
                                        exerciseName = "$workoutName movement",
                                        createExercise = true,
                                        executionType = "REPS",
                                        sets = "3",
                                        reps = "8-10",
                                        load = "20 kg",
                                    ),
                                ),
                            ),
                        ),
                    )
                },
            )
        },
    )

    private fun <T> found(result: TrainingReadResult<T>): T =
        assertIs<TrainingReadResult.Found<T>>(result).value

    private data class SessionStamps(val updatedAt: Instant, val executionUpdatedAt: Instant?)

    private class MutableClock(var current: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
        fun advance(seconds: Long) { current = current.plusSeconds(seconds) }
        override fun getZone(): ZoneId = zone
        override fun withZone(newZone: ZoneId): Clock = object : Clock() {
            override fun getZone(): ZoneId = newZone
            override fun withZone(z: ZoneId): Clock = this@MutableClock.withZone(z)
            override fun instant(): Instant = this@MutableClock.current
        }
        override fun instant(): Instant = current
    }

    private companion object {
        const val OWNER = "U-junda"
    }
}
