package me.gpipi.training

import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import me.gpipi.support.PersistenceTest
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class TrainingServiceTest : PersistenceTest() {
    private val service = TrainingService(
        db = db,
        repository = TrainingRepository(),
        clock = Clock.fixed(Instant.parse("2026-08-10T03:00:00Z"), ZoneOffset.UTC),
    )

    @Test
    fun `program details create an empty current week and workouts are added separately`() = runBlocking {
        val program = assertIs<ProgramCreateResult.Created>(
            service.createProgram(
                OWNER,
                ProgramAuthoringInput(
                    name = "M2",
                    note = "Pregnancy strength block",
                    startsOn = java.time.LocalDate.parse("2026-08-11"),
                    workouts = emptyList(),
                ),
            ),
        )

        val emptyWeek = found(service.overview(OWNER, null))
        assertEquals(program.id, emptyWeek.program.id)
        assertEquals(1, emptyWeek.currentWeekNumber)
        assertEquals(1, emptyWeek.selectedWeekNumber)
        assertEquals(listOf(1), emptyWeek.availableWeekNumbers)
        assertEquals(emptyList(), emptyWeek.workouts)

        assertIs<WorkoutCreateResult.Created>(
            service.createWorkout(
                OWNER,
                program.id,
                1,
                workoutInput(),
            ),
        )

        val authoredWeek = found(service.overview(OWNER, 1))
        assertEquals(listOf("Full Body 1"), authoredWeek.workouts.map { it.workoutName })
        assertEquals(1, authoredWeek.currentWeekNumber)
    }

    @Test
    fun `manual workout creation is restricted to the active current week`() = runBlocking {
        val program = assertIs<ProgramCreateResult.Created>(
            service.createProgram(OWNER, ProgramAuthoringInput(name = "M2", workouts = emptyList())),
        )

        assertIs<WorkoutCreateResult.Invalid>(
            service.createWorkout(OWNER, program.id, 2, workoutInput()),
        )
        assertEquals(
            WorkoutCreateResult.NotFound,
            service.createWorkout("U-other", program.id, 1, workoutInput()),
        )
        assertEquals(emptyList(), found(service.overview(OWNER, 1)).workouts)
    }

    @Test
    fun `current week advances only after every workout in the week is resolved`() = runBlocking {
        createProgram()
        val weekOne = found(service.overview(OWNER, null))
        assertEquals(1, weekOne.currentWeekNumber)

        val strength = weekOne.workouts.single { it.workoutName == "Strength A" }
        val conditioning = weekOne.workouts.single { it.workoutName == "Conditioning" }

        assertEquals(TrainingMutationResult.Updated, service.finish(OWNER, strength.weekId))
        assertEquals(1, found(service.overview(OWNER, null)).currentWeekNumber)

        assertEquals(TrainingMutationResult.Updated, service.skip(OWNER, conditioning.weekId))
        val weekTwo = found(service.overview(OWNER, null))
        assertEquals(2, weekTwo.currentWeekNumber)
        assertEquals(2, weekTwo.selectedWeekNumber)
    }

    @Test
    fun `first execution snapshots every prescription and set slots stay stable after delete`() = runBlocking {
        createProgram()
        val workout = found(service.overview(OWNER, 1)).workouts
            .single { it.workoutName == "Strength A" }
        val before = detail(workout.workoutId)
        val squat = before.groups.flatMap { it.exercises }.single { it.exerciseName == "Squat" }
        val carry = before.groups.flatMap { it.exercises }.single { it.exerciseName == "Suitcase carry" }

        assertNull(before.session)
        assertEquals(emptyList(), squat.sets)
        assertEquals(emptyList(), carry.sets)

        assertEquals(
            TrainingMutationResult.Updated,
            service.putSet(OWNER, workout.weekId, squat.prescriptionId, 1, reps(8)),
        )

        val afterFirstSet = detail(workout.workoutId)
        assertEquals(2, afterFirstSet.groups.flatMap { it.exercises }.size)
        assertEquals(
            emptyList(),
            afterFirstSet.groups.flatMap { it.exercises }
                .single { it.exerciseName == "Suitcase carry" }.sets,
        )

        transaction(db) {
            exec("update exercise set name = 'Trainer changed squat' where name = 'Squat'")
            exec("update prescription set reps = '12' where id = '${squat.prescriptionId}'")
        }

        val historical = detail(workout.workoutId)
        val historicalSquat = historical.groups.flatMap { it.exercises }.first()
        assertEquals("Squat", historicalSquat.exerciseName)
        assertEquals("8-10", historicalSquat.targetReps)

        service.putSet(OWNER, workout.weekId, squat.prescriptionId, 2, reps(9))
        assertEquals(
            TrainingMutationResult.Updated,
            service.deleteSet(OWNER, workout.weekId, squat.prescriptionId, 1),
        )
        assertEquals(listOf(2), detail(workout.workoutId).setsFor(squat.prescriptionId).map { it.setNumber })

        service.putSet(OWNER, workout.weekId, squat.prescriptionId, 1, reps(7))
        assertEquals(
            listOf(1 to 7, 2 to 9),
            detail(workout.workoutId).setsFor(squat.prescriptionId).map { it.setNumber to it.reps },
        )

        service.finish(OWNER, workout.weekId)
        service.putSet(OWNER, workout.weekId, squat.prescriptionId, 1, reps(10))
        val editedCompletedSession = detail(workout.workoutId)
        assertEquals("COMPLETED", editedCompletedSession.session?.status)
        assertEquals(10, editedCompletedSession.setsFor(squat.prescriptionId).first().reps)
    }

    @Test
    fun `a prescription inserted after logging starts neither crashes logging nor enters the frozen session`() = runBlocking {
        createProgram()
        val workout = found(service.overview(OWNER, 1)).workouts.single { it.workoutName == "Strength A" }
        val exercises = detail(workout.workoutId).groups.flatMap { it.exercises }
        val squat = exercises.single { it.exerciseName == "Squat" }
        val carry = exercises.single { it.exerciseName == "Suitcase carry" }

        // Start the session: this snapshots Squat (position 1) and Suitcase carry (position 2).
        assertEquals(
            TrainingMutationResult.Updated,
            service.putSet(OWNER, workout.weekId, squat.prescriptionId, 1, reps(8)),
        )

        // A trainer fine-tune (or a sync) inserts a movement *between* the two existing ones
        // after the session already exists. Re-deriving snapshot positions would have collided
        // on (session_id, position); the movement must simply be ignored by the frozen session.
        val newExerciseId = UUID.randomUUID()
        val newPrescriptionId = UUID.randomUUID()
        transaction(db) {
            exec("insert into exercise (id, owner_user_id, name) values ('$newExerciseId', '$OWNER', 'Trainer added row')")
            exec("update prescription set position = 3 where id = '${carry.prescriptionId}'")
            exec(
                """
                insert into prescription (id, group_id, exercise_id, position, execution_type, reps)
                select '$newPrescriptionId', group_id, '$newExerciseId', 2, 'REPS', '5'
                from prescription where id = '${squat.prescriptionId}'
                """.trimIndent(),
            )
        }

        // Logging another set must still succeed rather than throwing a position collision.
        assertEquals(
            TrainingMutationResult.Updated,
            service.putSet(OWNER, workout.weekId, squat.prescriptionId, 2, reps(9)),
        )

        val frozen = detail(workout.workoutId).groups.flatMap { it.exercises }
        assertEquals(listOf("Squat", "Suitcase carry"), frozen.map { it.exerciseName })
        assertNull(frozen.singleOrNull { it.prescriptionId == newPrescriptionId })
    }

    @Test
    fun `execution types accept only their matching primary measure`() = runBlocking {
        createProgram()
        val week = found(service.overview(OWNER, 1))
        val strength = week.workouts.single { it.workoutName == "Strength A" }
        val carry = detail(strength.workoutId).groups.flatMap { it.exercises }
            .single { it.executionType == "REPS_PER_SIDE" }
        val conditioning = week.workouts.single { it.workoutName == "Conditioning" }
        val plank = detail(conditioning.workoutId).groups.flatMap { it.exercises }
            .single { it.executionType == "DURATION" }

        assertEquals(
            TrainingMutationResult.Updated,
            service.putSet(OWNER, strength.weekId, carry.prescriptionId, 1, reps(10)),
        )
        assertIs<TrainingMutationResult.Invalid>(
            service.putSet(
                OWNER,
                strength.weekId,
                carry.prescriptionId,
                2,
                SetInput(null, 30, null, null, null),
            ),
        )
        assertEquals(
            TrainingMutationResult.Updated,
            service.putSet(
                OWNER,
                conditioning.weekId,
                plank.prescriptionId,
                1,
                SetInput(null, 45, null, null, null),
            ),
        )
    }

    @Test
    fun `training records are isolated by authenticated owner`() = runBlocking {
        createProgram()
        val workout = found(service.overview(OWNER, 1)).workouts.first()
        val prescription = detail(workout.workoutId).groups.first().exercises.first()

        assertEquals(TrainingReadResult.NoActiveProgram, service.overview("U-other", null))
        assertEquals(
            TrainingReadResult.NotFound,
            service.workoutDetail("U-other", 1, workout.workoutId),
        )
        assertEquals(
            TrainingMutationResult.NotFound,
            service.putSet("U-other", workout.weekId, prescription.prescriptionId, 1, reps(8)),
        )
    }

    @Test
    fun `a previous program can be made active again without losing either block`() = runBlocking {
        val first = assertIs<ProgramCreateResult.Created>(
            service.createProgram(OWNER, programInput()),
        )
        val second = assertIs<ProgramCreateResult.Created>(
            service.createProgram(OWNER, programInput(name = "M2", movementPrefix = "M2 ")),
        )

        assertEquals(second.id, service.programs(OWNER).single { it.active }.id)
        assertEquals(TrainingMutationResult.Updated, service.activateProgram(OWNER, first.id))

        val programs = service.programs(OWNER)
        assertEquals(2, programs.size)
        assertEquals(first.id, programs.single { it.active }.id)
        assertEquals(false, programs.single { it.id == second.id }.active)
    }

    private suspend fun createProgram() {
        assertIs<ProgramCreateResult.Created>(service.createProgram(OWNER, programInput()))
    }

    private suspend fun detail(workoutId: UUID): WorkoutDetailRecord =
        found(service.workoutDetail(OWNER, 1, workoutId))

    private fun WorkoutDetailRecord.setsFor(prescriptionId: UUID) = groups
        .flatMap { it.exercises }
        .single { it.prescriptionId == prescriptionId }
        .sets

    private fun reps(value: Int) = SetInput(
        reps = value,
        durationSeconds = null,
        load = BigDecimal("20.0"),
        rir = 2,
        note = null,
    )

    private fun programInput(name: String = "M1", movementPrefix: String = "") = ProgramAuthoringInput(
        name = name,
        startsOn = java.time.LocalDate.parse("2026-08-03"),
        workouts = listOf(
            WorkoutAuthoringInput(
                name = "Strength A",
                weeks = listOf(1, 2).map { week ->
                    WeekAuthoringInput(
                        weekNumber = week,
                        groups = listOf(
                            GroupAuthoringInput(
                                label = "A",
                                kind = "SUPERSET",
                                prescriptions = listOf(
                                    PrescriptionAuthoringInput(
                                        exerciseName = "${movementPrefix}Squat",
                                        createExercise = true,
                                        executionType = "REPS",
                                        sets = "3",
                                        reps = "8-10",
                                        load = "20 kg",
                                    ),
                                    PrescriptionAuthoringInput(
                                        exerciseName = "${movementPrefix}Suitcase carry",
                                        createExercise = true,
                                        executionType = "REPS_PER_SIDE",
                                        sets = "3",
                                        reps = "10 / side",
                                    ),
                                ),
                            ),
                        ),
                    )
                },
            ),
            WorkoutAuthoringInput(
                name = "Conditioning",
                weeks = listOf(1, 2).map { week ->
                    WeekAuthoringInput(
                        weekNumber = week,
                        groups = listOf(
                            GroupAuthoringInput(
                                label = "A",
                                kind = "STRAIGHT_SET",
                                prescriptions = listOf(
                                    PrescriptionAuthoringInput(
                                        exerciseName = "${movementPrefix}Plank",
                                        createExercise = true,
                                        executionType = "DURATION",
                                        sets = "3",
                                        rest = "60 sec",
                                    ),
                                ),
                            ),
                        ),
                    )
                },
            ),
        ),
    )

    private fun workoutInput() = WorkoutCreateInput(
        name = "Full Body 1",
        note = "Record a side view",
        groups = listOf(
            GroupAuthoringInput(
                label = "A",
                kind = "STRAIGHT_SET",
                prescriptions = listOf(
                    PrescriptionAuthoringInput(
                        exerciseName = "Goblet squat",
                        createExercise = true,
                        executionType = "REPS",
                        sets = "3",
                        reps = "10-12",
                    ),
                ),
            ),
        ),
    )

    private fun <T> found(result: TrainingReadResult<T>): T =
        assertIs<TrainingReadResult.Found<T>>(result).value

    private companion object {
        const val OWNER = "U-junda"
    }
}
