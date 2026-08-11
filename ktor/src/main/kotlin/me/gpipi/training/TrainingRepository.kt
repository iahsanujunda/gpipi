package me.gpipi.training

import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import me.gpipi.generated.db.base.public1.Exercise
import me.gpipi.generated.db.base.public1.PerformedExercise
import me.gpipi.generated.db.base.public1.PerformedSet
import me.gpipi.generated.db.base.public1.Prescription
import me.gpipi.generated.db.base.public1.Program
import me.gpipi.generated.db.base.public1.TrainingSession
import me.gpipi.generated.db.base.public1.Workout
import me.gpipi.generated.db.base.public1.WorkoutGroup
import me.gpipi.generated.db.base.public1.WorkoutWeek
import org.jetbrains.exposed.v1.core.DecimalColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.javatime.JavaLocalDateColumnType
import org.jetbrains.exposed.v1.javatime.JavaOffsetDateTimeColumnType
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update

class TrainingRepository {
    fun exerciseCatalog(ownerUserId: String): List<ExerciseCatalogRecord> =
        rows(
            """
            select e.id, e.name, e.demo_url,
                   coalesce(string_agg(ea.alias, E'\u001f' order by lower(ea.alias)), '') as aliases
            from exercise e
            left join exercise_alias ea on ea.exercise_id = e.id and ea.owner_user_id = e.owner_user_id
            where e.owner_user_id = ?
            group by e.id, e.name, e.demo_url
            order by lower(e.name)
            """.trimIndent(),
            listOf(text(ownerUserId)),
        ) { rs ->
            ExerciseCatalogRecord(
                id = rs.getObject("id", UUID::class.java),
                name = rs.getString("name"),
                demoUrl = rs.getString("demo_url"),
                aliases = rs.getString("aliases").takeIf(String::isNotEmpty)?.split('\u001f').orEmpty(),
            )
        }

    fun activeProgram(ownerUserId: String): TrainingProgramRecord? =
        Program
            .select(Program.id, Program.name, Program.note, Program.startsOn, Program.active)
            .where { (Program.ownerUserId eq ownerUserId) and (Program.active eq true) }
            .map(::toProgramRecord)
            .singleOrNull()

    fun programs(ownerUserId: String): List<TrainingProgramRecord> =
        Program
            .select(Program.id, Program.name, Program.note, Program.startsOn, Program.active)
            .where { Program.ownerUserId eq ownerUserId }
            .orderBy(Program.active to SortOrder.DESC, Program.createdAt to SortOrder.DESC)
            .map(::toProgramRecord)

    fun activateProgram(ownerUserId: String, programId: UUID, now: OffsetDateTime): Boolean {
        val owns = Program
            .select(Program.id)
            .where { (Program.id eq programId) and (Program.ownerUserId eq ownerUserId) }
            .any()
        if (!owns) return false
        Program.update({ (Program.ownerUserId eq ownerUserId) and (Program.active eq true) }) {
            it[Program.active] = false
            it[Program.updatedAt] = now
        }
        Program.update({ Program.id eq programId }) {
            it[Program.active] = true
            it[Program.updatedAt] = now
        }
        return true
    }

    fun weekNumbers(programId: UUID): List<Int> =
        (Workout innerJoin WorkoutWeek)
            .select(WorkoutWeek.weekNumber)
            .where { Workout.programId eq programId }
            .withDistinct()
            .orderBy(WorkoutWeek.weekNumber)
            .map { it[WorkoutWeek.weekNumber] }

    // "Current" is the lowest authored week that is neither skipped nor already completed.
    // A completed session is the only resolution here; an in-progress or absent session still
    // leaves the week unresolved. There is one session per week, so "no completed session"
    // is expressible as a not-exists rather than a left join.
    fun currentWeekNumber(programId: UUID): Int? {
        val lowestWeek = WorkoutWeek.weekNumber.min()
        return (Workout innerJoin WorkoutWeek)
            .select(lowestWeek)
            .where {
                (Workout.programId eq programId) and
                    WorkoutWeek.skippedAt.isNull() and
                    notExists(
                        TrainingSession
                            .selectAll()
                            .where {
                                (TrainingSession.weekId eq WorkoutWeek.id) and
                                    (TrainingSession.status eq "COMPLETED")
                            },
                    )
            }
            .single()[lowestWeek]
    }

    fun weekWorkouts(programId: UUID, weekNumber: Int): List<WeekWorkoutRecord> =
        rows(
            """
            select
                ww.id as week_id,
                w.id as workout_id,
                w.name as workout_name,
                w.position as workout_position,
                case
                    when s.status = 'COMPLETED' then 'COMPLETED'
                    when ww.skipped_at is not null then 'SKIPPED'
                    when s.status = 'IN_PROGRESS' then 'IN_PROGRESS'
                    else 'NOT_STARTED'
                end as status,
                s.id as session_id,
                s.performed_on,
                s.updated_at,
                coalesce(active_sets.set_count, 0) as set_count
            from workout w
            join workout_week ww on ww.workout_id = w.id and ww.week_number = ?
            left join training_session s on s.week_id = ww.id
            left join (
                select pe.session_id, count(*)::integer as set_count
                from performed_exercise pe
                join performed_set ps on ps.performed_exercise_id = pe.id
                where ps.deleted_at is null
                group by pe.session_id
            ) active_sets on active_sets.session_id = s.id
            where w.program_id = ?
            order by
                case when s.status = 'IN_PROGRESS' then 0 else 1 end,
                w.position
            """.trimIndent(),
            listOf(integer(weekNumber), uuid(programId)),
        ) { rs ->
            WeekWorkoutRecord(
                weekId = rs.getObject("week_id", UUID::class.java),
                workoutId = rs.getObject("workout_id", UUID::class.java),
                workoutName = rs.getString("workout_name"),
                workoutPosition = rs.getInt("workout_position"),
                status = rs.getString("status"),
                sessionId = rs.getObject("session_id", UUID::class.java),
                performedOn = rs.getObject("performed_on", LocalDate::class.java),
                setCount = rs.getInt("set_count"),
                updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java),
            )
        }

    fun prescriptionExecutionType(
        ownerUserId: String,
        weekId: UUID,
        prescriptionId: UUID,
    ): String? =
        (Prescription innerJoin WorkoutGroup innerJoin WorkoutWeek innerJoin Workout innerJoin Program)
            .select(Prescription.executionType)
            .where {
                (Prescription.id eq prescriptionId) and
                    (WorkoutWeek.id eq weekId) and
                    (Program.ownerUserId eq ownerUserId) and
                    Prescription.archivedAt.isNull()
            }
            .map { it[Prescription.executionType] }
            .singleOrNull()

    fun workoutDetail(
        ownerUserId: String,
        weekNumber: Int,
        workoutId: UUID,
    ): WorkoutDetailRecord? {
        val header = rows(
            """
            select
                p.id as program_id, p.name as program_name, p.note as program_note,
                p.starts_on, p.active,
                w.id as workout_id, w.name as workout_name, w.note as workout_note,
                ww.id as week_id, ww.week_number, ww.skipped_at,
                s.id as session_id, s.performed_on, s.status as session_status,
                s.note as session_note, s.updated_at, s.completed_at
            from program p
            join workout w on w.program_id = p.id
            join workout_week ww on ww.workout_id = w.id
            left join training_session s on s.week_id = ww.id
            where p.owner_user_id = ? and p.active = true
              and w.id = ? and ww.week_number = ?
            """.trimIndent(),
            listOf(text(ownerUserId), uuid(workoutId), integer(weekNumber)),
        ) { rs ->
            DetailHeader(
                program = TrainingProgramRecord(
                    id = rs.getObject("program_id", UUID::class.java),
                    name = rs.getString("program_name"),
                    note = rs.getString("program_note"),
                    startsOn = rs.getObject("starts_on", LocalDate::class.java),
                    active = rs.getBoolean("active"),
                ),
                weekId = rs.getObject("week_id", UUID::class.java),
                weekNumber = rs.getInt("week_number"),
                skipped = rs.getObject("skipped_at", OffsetDateTime::class.java) != null,
                workoutId = rs.getObject("workout_id", UUID::class.java),
                workoutName = rs.getString("workout_name"),
                workoutNote = rs.getString("workout_note"),
                session = rs.getObject("session_id", UUID::class.java)?.let { sessionId ->
                    TrainingSessionRecord(
                        id = sessionId,
                        performedOn = rs.getObject("performed_on", LocalDate::class.java),
                        status = rs.getString("session_status"),
                        note = rs.getString("session_note"),
                        updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java),
                        completedAt = rs.getObject("completed_at", OffsetDateTime::class.java),
                    )
                },
            )
        }.singleOrNull() ?: return null

        val exerciseRows = if (header.session == null) {
            liveExercises(header.weekId)
        } else {
            snapshotExercises(header.session.id)
        }
        val setsByExercise = header.session?.let { activeSets(it.id) }.orEmpty()
            .groupBy { it.first }
            .mapValues { (_, values) -> values.map { it.second } }
        val groups = exerciseRows
            .groupBy { it.groupPosition to (it.groupLabel to it.groupKind) }
            .entries
            .sortedBy { it.key.first }
            .map { (key, exercises) ->
                WorkoutGroupExecutionRecord(
                    position = key.first,
                    label = key.second.first,
                    kind = key.second.second,
                    exercises = exercises.sortedBy(ExerciseRow::position).map { row ->
                        ExerciseExecutionRecord(
                            prescriptionId = row.prescriptionId,
                            performedExerciseId = row.performedExerciseId,
                            position = row.position,
                            exerciseName = row.exerciseName,
                            demoUrl = row.demoUrl,
                            executionType = row.executionType,
                            targetSets = row.targetSets,
                            targetRest = row.targetRest,
                            targetReps = row.targetReps,
                            targetLoad = row.targetLoad,
                            targetRir = row.targetRir,
                            targetTempo = row.targetTempo,
                            targetNote = row.targetNote,
                            executionNote = row.executionNote,
                            sets = row.performedExerciseId?.let(setsByExercise::get).orEmpty(),
                        )
                    },
                )
            }

        return WorkoutDetailRecord(
            program = header.program,
            currentWeekNumber = currentWeekNumber(header.program.id),
            weekId = header.weekId,
            weekNumber = header.weekNumber,
            skipped = header.skipped,
            workoutId = header.workoutId,
            workoutName = header.workoutName,
            workoutNote = header.workoutNote,
            session = header.session,
            groups = groups,
        )
    }

    fun ensureSessionWithSnapshots(
        ownerUserId: String,
        weekId: UUID,
        performedOn: LocalDate,
        now: OffsetDateTime,
    ): UUID? {
        val session = rows(
            """
            with owned_week as (
                select ww.id
                from workout_week ww
                join workout w on w.id = ww.workout_id
                join program p on p.id = w.program_id
                where ww.id = ? and p.owner_user_id = ?
            ), inserted as (
                insert into training_session (id, week_id, performed_on, started_at, updated_at)
                select ?, id, ?, ?, ? from owned_week
                on conflict (week_id) do nothing
                returning id
            )
            select id, true as created from inserted
            union all
            select s.id, false as created
            from training_session s
            join owned_week ow on ow.id = s.week_id
            limit 1
            """.trimIndent(),
            listOf(
                uuid(weekId), text(ownerUserId), uuid(UUID.randomUUID()),
                localDate(performedOn), offsetDateTime(now), offsetDateTime(now),
            ),
        ) { it.getObject("id", UUID::class.java) to it.getBoolean("created") }.singleOrNull() ?: return null

        val (sessionId, created) = session
        // Snapshot exactly once, when the session is first created. Re-running on every
        // action would renumber positions against already-snapshotted rows and collide on
        // (session_id, position); it would also leak later-authored prescriptions into an
        // already-started session. History renders from this frozen snapshot, so live
        // prescriptions may still be edited freely afterwards.
        if (created) {
            execute(
                """
                insert into performed_exercise (
                    id, session_id, exercise_id, prescription_id, position,
                    target_group_label, target_group_kind, target_exercise_name,
                    target_demo_url, target_execution_type, target_sets, target_rest,
                    target_reps, target_load, target_rir, target_tempo, target_note
                )
                select
                    gen_random_uuid(), ?, pr.exercise_id, pr.id,
                    row_number() over (order by wg.position, pr.position)::integer,
                    wg.label, wg.kind, e.name, e.demo_url, pr.execution_type,
                    pr.sets, pr.rest, pr.reps, pr.load, pr.rir, pr.tempo, pr.note
                from training_session s
                join workout_week ww on ww.id = s.week_id
                join workout_group wg on wg.week_id = ww.id
                join prescription pr on pr.group_id = wg.id and pr.archived_at is null
                join exercise e on e.id = pr.exercise_id
                where s.id = ?
                on conflict (session_id, prescription_id) do nothing
                """.trimIndent(),
                listOf(uuid(sessionId), uuid(sessionId)),
            )
        }
        return sessionId
    }

    fun upsertSet(
        ownerUserId: String,
        weekId: UUID,
        prescriptionId: UUID,
        setNumber: Int,
        input: SetInput,
        performedOn: LocalDate,
        now: OffsetDateTime,
    ): Boolean {
        val sessionId = ensureSessionWithSnapshots(ownerUserId, weekId, performedOn, now)
            ?: return false
        val exercise = rows(
            """
            select pe.id, pe.target_execution_type, pe.target_reps, pe.target_load,
                   pe.target_rir, pe.target_tempo
            from performed_exercise pe
            where pe.session_id = ? and pe.prescription_id = ?
            """.trimIndent(),
            listOf(uuid(sessionId), uuid(prescriptionId)),
        ) { rs ->
            SetTarget(
                id = rs.getObject("id", UUID::class.java),
                executionType = rs.getString("target_execution_type"),
                reps = rs.getString("target_reps"),
                load = rs.getString("target_load"),
                rir = rs.getString("target_rir"),
                tempo = rs.getString("target_tempo"),
            )
        }.singleOrNull() ?: return false

        val previousExecution = rows(
            """
            select ps.reps, ps.duration_s, ps.load, ps.rir, ps.deleted_at
            from performed_set ps
            where ps.performed_exercise_id = ? and ps.set_number = ?
            for update
            """.trimIndent(),
            listOf(uuid(exercise.id), integer(setNumber)),
        ) { rs ->
            SetExecutionState(
                reps = rs.getInt("reps").takeUnless { rs.wasNull() },
                durationSeconds = rs.getInt("duration_s").takeUnless { rs.wasNull() },
                load = rs.getBigDecimal("load"),
                rir = rs.getInt("rir").takeUnless { rs.wasNull() },
                deleted = rs.getObject("deleted_at", OffsetDateTime::class.java) != null,
            )
        }.singleOrNull()
        val executionChanged = previousExecution == null ||
            previousExecution.deleted ||
            previousExecution.reps != input.reps ||
            previousExecution.durationSeconds != input.durationSeconds ||
            !previousExecution.load.sameValueAs(input.load) ||
            previousExecution.rir != input.rir

        execute(
            """
            insert into performed_set (
                id, performed_exercise_id, set_number, reps, duration_s, load, rir, note,
                target_reps, target_load, target_rir, target_tempo, logged_at, updated_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (performed_exercise_id, set_number) do update set
                reps = excluded.reps,
                duration_s = excluded.duration_s,
                load = excluded.load,
                rir = excluded.rir,
                note = excluded.note,
                updated_at = excluded.updated_at,
                deleted_at = null
            """.trimIndent(),
            listOf(
                uuid(UUID.randomUUID()), uuid(exercise.id), integer(setNumber),
                nullableInteger(input.reps), nullableInteger(input.durationSeconds),
                nullableDecimal(input.load), nullableInteger(input.rir), nullableText(input.note),
                nullableText(exercise.reps), nullableText(exercise.load), nullableText(exercise.rir),
                nullableText(exercise.tempo), offsetDateTime(now), offsetDateTime(now),
            ),
        )
        if (executionChanged) {
            execute(
                """
                update training_session
                set updated_at = ?, execution_updated_at = ?
                where id = ?
                """.trimIndent(),
                listOf(offsetDateTime(now), offsetDateTime(now), uuid(sessionId)),
            )
        } else {
            execute(
                """update training_session set updated_at = ? where id = ?""",
                listOf(offsetDateTime(now), uuid(sessionId)),
            )
        }
        if (isSkipped(weekId)) restoreWeek(ownerUserId, weekId)
        return true
    }

    fun deleteSet(
        ownerUserId: String,
        weekId: UUID,
        prescriptionId: UUID,
        setNumber: Int,
        now: OffsetDateTime,
    ): Boolean {
        val exercise = ownedPerformedExercise(ownerUserId, weekId, prescriptionId) ?: return false
        val deleted = PerformedSet.update({
            (PerformedSet.performedExerciseId eq exercise.id) and
                (PerformedSet.setNumber eq setNumber) and
                PerformedSet.deletedAt.isNull()
        }) {
            it[PerformedSet.deletedAt] = now
            it[PerformedSet.updatedAt] = now
        }
        if (deleted == 0) return false
        TrainingSession.update({ TrainingSession.id eq exercise.sessionId }) {
            it[TrainingSession.updatedAt] = now
            it[TrainingSession.executionUpdatedAt] = now
        }
        return true
    }

    fun updateSessionMetadata(
        ownerUserId: String,
        weekId: UUID,
        performedOn: LocalDate,
        note: String?,
        now: OffsetDateTime,
    ): Boolean {
        val sessionId = ensureSessionWithSnapshots(ownerUserId, weekId, performedOn, now)
            ?: return false
        TrainingSession.update({ TrainingSession.id eq sessionId }) {
            it[TrainingSession.performedOn] = performedOn
            it[TrainingSession.note] = note
            it[TrainingSession.updatedAt] = now
        }
        return true
    }

    fun finishSession(
        ownerUserId: String,
        weekId: UUID,
        performedOn: LocalDate,
        now: OffsetDateTime,
    ): Boolean {
        val sessionId = ensureSessionWithSnapshots(ownerUserId, weekId, performedOn, now)
            ?: return false
        TrainingSession.update({ TrainingSession.id eq sessionId }) {
            it[TrainingSession.status] = "COMPLETED"
            it[TrainingSession.completedAt] = now
            it[TrainingSession.updatedAt] = now
        }
        WorkoutWeek.update({ WorkoutWeek.id eq weekId }) {
            it[WorkoutWeek.skippedAt] = null
        }
        return true
    }

    fun resumeSession(ownerUserId: String, weekId: UUID, now: OffsetDateTime): Boolean {
        val session = ownedSession(ownerUserId, weekId) ?: return false
        if (session.status != "COMPLETED") return false
        TrainingSession.update({ TrainingSession.id eq session.id }) {
            it[TrainingSession.status] = "IN_PROGRESS"
            it[TrainingSession.completedAt] = null
            it[TrainingSession.updatedAt] = now
        }
        return true
    }

    fun skipWeek(ownerUserId: String, weekId: UUID, now: OffsetDateTime): Boolean {
        val week = ownedWeek(ownerUserId, weekId) ?: return false
        if (week.skippedAt != null || hasCompletedSession(weekId)) return false
        WorkoutWeek.update({ WorkoutWeek.id eq weekId }) {
            it[WorkoutWeek.skippedAt] = now
        }
        return true
    }

    fun restoreWeek(ownerUserId: String, weekId: UUID): Boolean {
        val week = ownedWeek(ownerUserId, weekId) ?: return false
        if (week.skippedAt == null) return false
        WorkoutWeek.update({ WorkoutWeek.id eq weekId }) {
            it[WorkoutWeek.skippedAt] = null
        }
        return true
    }

    fun createProgram(ownerUserId: String, input: ProgramAuthoringInput, now: OffsetDateTime): UUID {
        Program.update({ (Program.ownerUserId eq ownerUserId) and (Program.active eq true) }) {
            it[Program.active] = false
            it[Program.updatedAt] = now
        }
        val programId = UUID.randomUUID()
        Program.insert {
            it[Program.id] = programId
            it[Program.ownerUserId] = ownerUserId
            it[Program.name] = input.name
            it[Program.note] = input.note
            it[Program.startsOn] = input.startsOn
            it[Program.createdAt] = now
            it[Program.updatedAt] = now
        }
        val newlyCreatedExercises = mutableMapOf<String, UUID>()
        input.workouts.forEachIndexed { workoutIndex, workout ->
            val workoutId = UUID.randomUUID()
            Workout.insert {
                it[Workout.id] = workoutId
                it[Workout.programId] = programId
                it[Workout.name] = workout.name
                it[Workout.note] = workout.note
                it[Workout.position] = workoutIndex + 1
            }
            workout.weeks.forEach { week ->
                val weekId = UUID.randomUUID()
                WorkoutWeek.insert {
                    it[WorkoutWeek.id] = weekId
                    it[WorkoutWeek.workoutId] = workoutId
                    it[WorkoutWeek.weekNumber] = week.weekNumber
                }
                insertGroups(ownerUserId, weekId, week.groups, newlyCreatedExercises)
            }
        }
        return programId
    }

    fun createWorkout(
        ownerUserId: String,
        programId: UUID,
        weekNumber: Int,
        input: WorkoutCreateInput,
    ): UUID? {
        val owns = Program
            .select(Program.id)
            .where {
                (Program.id eq programId) and
                    (Program.ownerUserId eq ownerUserId) and
                    (Program.active eq true)
            }
            .any()
        if (!owns) return null
        val maxPosition = Workout.position.max()
        val position = (
            Workout.select(maxPosition).where { Workout.programId eq programId }.single()[maxPosition] ?: 0
            ) + 1
        val workoutId = UUID.randomUUID()
        Workout.insert {
            it[Workout.id] = workoutId
            it[Workout.programId] = programId
            it[Workout.name] = input.name
            it[Workout.note] = input.note
            it[Workout.position] = position
        }
        val weekId = UUID.randomUUID()
        WorkoutWeek.insert {
            it[WorkoutWeek.id] = weekId
            it[WorkoutWeek.workoutId] = workoutId
            it[WorkoutWeek.weekNumber] = weekNumber
        }
        insertGroups(ownerUserId, weekId, input.groups, mutableMapOf())
        return workoutId
    }

    fun duplicateWeek(ownerUserId: String, workoutId: UUID, sourceWeek: Int, targetWeek: Int): UUID? {
        val owned = rows(
            """
            select w.id
            from workout w join program p on p.id = w.program_id
            where w.id = ? and p.owner_user_id = ?
            """.trimIndent(),
            listOf(uuid(workoutId), text(ownerUserId)),
        ) { it.getObject("id", UUID::class.java) }.singleOrNull() ?: return null
        val sourceId = rows(
            """select id from workout_week where workout_id = ? and week_number = ?""",
            listOf(uuid(owned), integer(sourceWeek)),
        ) { it.getObject("id", UUID::class.java) }.singleOrNull() ?: return null
        val targetId = insertId(
            """insert into workout_week (id, workout_id, week_number) values (?, ?, ?) returning id""",
            listOf(uuid(UUID.randomUUID()), uuid(owned), integer(targetWeek)),
        )
        execute(
            """
            with new_groups as (
                insert into workout_group (id, week_id, label, kind, position)
                select gen_random_uuid(), ?, label, kind, position
                from workout_group where week_id = ?
                returning id, label, kind, position
            )
            insert into prescription (
                id, group_id, exercise_id, position, execution_type,
                sets, rest, reps, load, rir, tempo, note
            )
            select gen_random_uuid(), ng.id, pr.exercise_id, pr.position, pr.execution_type,
                   pr.sets, pr.rest, pr.reps, pr.load, pr.rir, pr.tempo, pr.note
            from workout_group source_group
            join prescription pr on pr.group_id = source_group.id and pr.archived_at is null
            join new_groups ng on ng.position = source_group.position
            where source_group.week_id = ?
            """.trimIndent(),
            listOf(uuid(targetId), uuid(sourceId), uuid(sourceId)),
        )
        return targetId
    }

    private fun insertGroups(
        ownerUserId: String,
        weekId: UUID,
        groups: List<GroupAuthoringInput>,
        newlyCreatedExercises: MutableMap<String, UUID>,
    ) {
        groups.forEachIndexed { groupIndex, group ->
            val groupId = UUID.randomUUID()
            WorkoutGroup.insert {
                it[WorkoutGroup.id] = groupId
                it[WorkoutGroup.weekId] = weekId
                it[WorkoutGroup.label] = group.label
                it[WorkoutGroup.kind] = group.kind
                it[WorkoutGroup.position] = groupIndex + 1
            }
            group.prescriptions.forEachIndexed { prescriptionIndex, prescription ->
                val resolvedExerciseId = resolveExercise(ownerUserId, prescription, newlyCreatedExercises)
                Prescription.insert {
                    it[Prescription.id] = UUID.randomUUID()
                    it[Prescription.groupId] = groupId
                    it[Prescription.exerciseId] = resolvedExerciseId
                    it[Prescription.position] = prescriptionIndex + 1
                    it[Prescription.executionType] = prescription.executionType
                    it[Prescription.sets] = prescription.sets
                    it[Prescription.rest] = prescription.rest
                    it[Prescription.reps] = prescription.reps
                    it[Prescription.load] = prescription.load
                    it[Prescription.rir] = prescription.rir
                    it[Prescription.tempo] = prescription.tempo
                    it[Prescription.note] = prescription.note
                }
            }
        }
    }

    private fun resolveExercise(
        ownerUserId: String,
        input: PrescriptionAuthoringInput,
        newlyCreatedExercises: MutableMap<String, UUID>,
    ): UUID {
        if (input.exerciseId != null) {
            val owns = Exercise
                .select(Exercise.id)
                .where { (Exercise.id eq input.exerciseId) and (Exercise.ownerUserId eq ownerUserId) }
                .any()
            if (!owns) throw IllegalArgumentException("The selected exercise does not belong to this member.")
            return input.exerciseId
        }
        val normalizedName = input.exerciseName.trim().lowercase()
        newlyCreatedExercises[normalizedName]?.let { return it }
        val id = UUID.randomUUID()
        Exercise.insert {
            it[Exercise.id] = id
            it[Exercise.ownerUserId] = ownerUserId
            it[Exercise.name] = input.exerciseName
            it[Exercise.demoUrl] = input.demoUrl
        }
        newlyCreatedExercises[normalizedName] = id
        return id
    }

    private fun liveExercises(weekId: UUID): List<ExerciseRow> =
        (WorkoutGroup innerJoin Prescription innerJoin Exercise)
            .selectAll()
            .where { (WorkoutGroup.weekId eq weekId) and Prescription.archivedAt.isNull() }
            .orderBy(WorkoutGroup.position to SortOrder.ASC, Prescription.position to SortOrder.ASC)
            .map { row ->
                ExerciseRow(
                    groupPosition = row[WorkoutGroup.position],
                    groupLabel = row[WorkoutGroup.label],
                    groupKind = row[WorkoutGroup.kind],
                    prescriptionId = row[Prescription.id],
                    performedExerciseId = null,
                    position = row[Prescription.position],
                    exerciseName = row[Exercise.name],
                    demoUrl = row[Exercise.demoUrl],
                    executionType = row[Prescription.executionType],
                    targetSets = row[Prescription.sets],
                    targetRest = row[Prescription.rest],
                    targetReps = row[Prescription.reps],
                    targetLoad = row[Prescription.load],
                    targetRir = row[Prescription.rir],
                    targetTempo = row[Prescription.tempo],
                    targetNote = row[Prescription.note],
                    executionNote = null,
                )
            }

    private fun snapshotExercises(sessionId: UUID): List<ExerciseRow> =
        rows(
            """
            with ordered as (
                select pe.*,
                       lag(pe.target_group_label) over (order by pe.position) as previous_label,
                       lag(pe.target_group_kind) over (order by pe.position) as previous_kind
                from performed_exercise pe
                where pe.session_id = ?
            ), grouped as (
                select ordered.*,
                       sum(
                           case
                               when previous_label = target_group_label
                                and previous_kind = target_group_kind then 0
                               else 1
                           end
                       ) over (order by position)::integer as group_position
                from ordered
            )
            select group_position, target_group_label as group_label,
                   target_group_kind as group_kind, prescription_id,
                   id as performed_exercise_id, position,
                   target_exercise_name as exercise_name, target_demo_url as demo_url,
                   target_execution_type as execution_type, target_sets as sets,
                   target_rest as rest, target_reps as reps, target_load as load,
                   target_rir as rir, target_tempo as tempo, target_note as note,
                   note as execution_note
            from grouped
            order by position
            """.trimIndent(),
            listOf(uuid(sessionId)),
        ) { it.toExerciseRow() }

    private fun activeSets(sessionId: UUID): List<Pair<UUID, PerformedSetRecord>> =
        (PerformedExercise innerJoin PerformedSet)
            .selectAll()
            .where { (PerformedExercise.sessionId eq sessionId) and PerformedSet.deletedAt.isNull() }
            .orderBy(PerformedExercise.position to SortOrder.ASC, PerformedSet.setNumber to SortOrder.ASC)
            .map { row ->
                row[PerformedExercise.id] to PerformedSetRecord(
                    id = row[PerformedSet.id],
                    setNumber = row[PerformedSet.setNumber],
                    reps = row[PerformedSet.reps],
                    durationSeconds = row[PerformedSet.durationS],
                    load = row[PerformedSet.load],
                    rir = row[PerformedSet.rir],
                    note = row[PerformedSet.note],
                    targetReps = row[PerformedSet.targetReps],
                    targetLoad = row[PerformedSet.targetLoad],
                    targetRir = row[PerformedSet.targetRir],
                    targetTempo = row[PerformedSet.targetTempo],
                )
            }

    private fun toProgramRecord(row: org.jetbrains.exposed.v1.core.ResultRow) = TrainingProgramRecord(
        id = row[Program.id],
        name = row[Program.name],
        note = row[Program.note],
        startsOn = row[Program.startsOn],
        active = row[Program.active],
    )

    private fun java.sql.ResultSet.toExerciseRow() = ExerciseRow(
        groupPosition = getInt("group_position"),
        groupLabel = getString("group_label"),
        groupKind = getString("group_kind"),
        prescriptionId = getObject("prescription_id", UUID::class.java),
        performedExerciseId = getObject("performed_exercise_id", UUID::class.java),
        position = getInt("position"),
        exerciseName = getString("exercise_name"),
        demoUrl = getString("demo_url"),
        executionType = getString("execution_type"),
        targetSets = getString("sets"),
        targetRest = getString("rest"),
        targetReps = getString("reps"),
        targetLoad = getString("load"),
        targetRir = getString("rir"),
        targetTempo = getString("tempo"),
        targetNote = getString("note"),
        executionNote = getString("execution_note"),
    )

    private fun isSkipped(weekId: UUID): Boolean =
        WorkoutWeek
            .select(WorkoutWeek.skippedAt)
            .where { WorkoutWeek.id eq weekId }
            .singleOrNull()
            ?.get(WorkoutWeek.skippedAt) != null

    // Ownership resolvers: a mutation first confirms the row belongs to the authenticated
    // member (joining up to program), then updates it by id. A foreign id resolves to null,
    // which the caller reports as not-found.
    private fun ownedWeek(ownerUserId: String, weekId: UUID): OwnedWeek? =
        (WorkoutWeek innerJoin Workout innerJoin Program)
            .select(WorkoutWeek.id, WorkoutWeek.skippedAt)
            .where { (WorkoutWeek.id eq weekId) and (Program.ownerUserId eq ownerUserId) }
            .map { OwnedWeek(it[WorkoutWeek.id], it[WorkoutWeek.skippedAt]) }
            .singleOrNull()

    private fun ownedSession(ownerUserId: String, weekId: UUID): OwnedSession? =
        (TrainingSession innerJoin WorkoutWeek innerJoin Workout innerJoin Program)
            .select(TrainingSession.id, TrainingSession.status)
            .where { (TrainingSession.weekId eq weekId) and (Program.ownerUserId eq ownerUserId) }
            .map { OwnedSession(it[TrainingSession.id], it[TrainingSession.status]) }
            .singleOrNull()

    private fun ownedPerformedExercise(
        ownerUserId: String,
        weekId: UUID,
        prescriptionId: UUID,
    ): OwnedPerformedExercise? =
        (PerformedExercise innerJoin TrainingSession innerJoin WorkoutWeek innerJoin Workout innerJoin Program)
            .select(PerformedExercise.id, PerformedExercise.sessionId)
            .where {
                (TrainingSession.weekId eq weekId) and
                    (PerformedExercise.prescriptionId eq prescriptionId) and
                    (Program.ownerUserId eq ownerUserId)
            }
            .map { OwnedPerformedExercise(it[PerformedExercise.id], it[PerformedExercise.sessionId]) }
            .singleOrNull()

    private fun hasCompletedSession(weekId: UUID): Boolean =
        TrainingSession
            .select(TrainingSession.id)
            .where { (TrainingSession.weekId eq weekId) and (TrainingSession.status eq "COMPLETED") }
            .any()

    private fun insertId(statement: String, args: List<Pair<IColumnType<*>, Any?>>): UUID =
        rows(statement, args) { it.getObject("id", UUID::class.java) }.single()

    private fun execute(statement: String, args: List<Pair<IColumnType<*>, Any?>>) {
        transaction().exec(statement, args, StatementType.UPDATE)
    }

    private fun <T> rows(
        statement: String,
        args: List<Pair<IColumnType<*>, Any?>>,
        transform: (java.sql.ResultSet) -> T,
    ): List<T> =
        transaction().exec(statement, args, StatementType.SELECT) { rs ->
            buildList {
                while (rs.next()) add(transform(rs))
            }
        }.orEmpty()

    private fun transaction() = checkNotNull(TransactionManager.currentOrNull())

    private fun text(value: String) = TextColumnType() to value
    private fun nullableText(value: String?) = TextColumnType() to value
    private fun uuid(value: UUID) = UUIDColumnType() to value
    private fun integer(value: Int) = IntegerColumnType() to value
    private fun nullableInteger(value: Int?) = IntegerColumnType() to value
    private fun localDate(value: LocalDate) = JavaLocalDateColumnType() to value
    private fun offsetDateTime(value: OffsetDateTime) = JavaOffsetDateTimeColumnType() to value
    private fun nullableDecimal(value: BigDecimal?) = DecimalColumnType(8, 2) to value

    private data class DetailHeader(
        val program: TrainingProgramRecord,
        val weekId: UUID,
        val weekNumber: Int,
        val skipped: Boolean,
        val workoutId: UUID,
        val workoutName: String,
        val workoutNote: String?,
        val session: TrainingSessionRecord?,
    )

    private data class ExerciseRow(
        val groupPosition: Int,
        val groupLabel: String,
        val groupKind: String,
        val prescriptionId: UUID,
        val performedExerciseId: UUID?,
        val position: Int,
        val exerciseName: String,
        val demoUrl: String?,
        val executionType: String,
        val targetSets: String?,
        val targetRest: String?,
        val targetReps: String?,
        val targetLoad: String?,
        val targetRir: String?,
        val targetTempo: String?,
        val targetNote: String?,
        val executionNote: String?,
    )

    private data class SetTarget(
        val id: UUID,
        val executionType: String,
        val reps: String?,
        val load: String?,
        val rir: String?,
        val tempo: String?,
    )

    private data class OwnedWeek(val id: UUID, val skippedAt: OffsetDateTime?)

    private data class OwnedSession(val id: UUID, val status: String)

    private data class OwnedPerformedExercise(val id: UUID, val sessionId: UUID)

    private data class SetExecutionState(
        val reps: Int?,
        val durationSeconds: Int?,
        val load: BigDecimal?,
        val rir: Int?,
        val deleted: Boolean,
    )
}

private fun BigDecimal?.sameValueAs(other: BigDecimal?): Boolean = when {
    this == null || other == null -> this == null && other == null
    else -> compareTo(other) == 0
}
