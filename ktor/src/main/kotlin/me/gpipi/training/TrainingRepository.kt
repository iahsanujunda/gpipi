package me.gpipi.training

import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import org.jetbrains.exposed.v1.core.DecimalColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.javatime.JavaLocalDateColumnType
import org.jetbrains.exposed.v1.javatime.JavaOffsetDateTimeColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

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
        rows(
            """
            select id, name, note, starts_on, active
            from program
            where owner_user_id = ? and active = true
            """.trimIndent(),
            listOf(text(ownerUserId)),
        ) { rs ->
            TrainingProgramRecord(
                id = rs.getObject("id", UUID::class.java),
                name = rs.getString("name"),
                note = rs.getString("note"),
                startsOn = rs.getObject("starts_on", LocalDate::class.java),
                active = rs.getBoolean("active"),
            )
        }.singleOrNull()

    fun programs(ownerUserId: String): List<TrainingProgramRecord> =
        rows(
            """
            select id, name, note, starts_on, active
            from program
            where owner_user_id = ?
            order by active desc, created_at desc
            """.trimIndent(),
            listOf(text(ownerUserId)),
        ) { rs ->
            TrainingProgramRecord(
                id = rs.getObject("id", UUID::class.java),
                name = rs.getString("name"),
                note = rs.getString("note"),
                startsOn = rs.getObject("starts_on", LocalDate::class.java),
                active = rs.getBoolean("active"),
            )
        }

    fun activateProgram(ownerUserId: String, programId: UUID, now: OffsetDateTime): Boolean {
        val owned = rows(
            """select id from program where id = ? and owner_user_id = ?""",
            listOf(uuid(programId), text(ownerUserId)),
        ) { it.getObject("id", UUID::class.java) }.singleOrNull() ?: return false
        execute(
            """update program set active = false, updated_at = ? where owner_user_id = ? and active""",
            listOf(offsetDateTime(now), text(ownerUserId)),
        )
        execute(
            """update program set active = true, updated_at = ? where id = ?""",
            listOf(offsetDateTime(now), uuid(owned)),
        )
        return true
    }

    fun weekNumbers(programId: UUID): List<Int> =
        rows(
            """
            select distinct ww.week_number
            from workout_week ww
            join workout w on w.id = ww.workout_id
            where w.program_id = ?
            order by ww.week_number
            """.trimIndent(),
            listOf(uuid(programId)),
        ) { it.getInt("week_number") }

    fun currentWeekNumber(programId: UUID): Int? =
        rows(
            """
            select min(ww.week_number) as week_number
            from workout_week ww
            join workout w on w.id = ww.workout_id
            left join training_session s on s.week_id = ww.id
            where w.program_id = ?
              and ww.skipped_at is null
              and (s.id is null or s.status <> 'COMPLETED')
            """.trimIndent(),
            listOf(uuid(programId)),
        ) { rs -> rs.getInt("week_number").takeUnless { rs.wasNull() } }.singleOrNull()

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
        rows(
            """
            select pr.execution_type
            from prescription pr
            join workout_group wg on wg.id = pr.group_id
            join workout_week ww on ww.id = wg.week_id
            join workout w on w.id = ww.workout_id
            join program p on p.id = w.program_id
            where pr.id = ? and ww.id = ? and p.owner_user_id = ?
              and pr.archived_at is null
            """.trimIndent(),
            listOf(uuid(prescriptionId), uuid(weekId), text(ownerUserId)),
        ) { it.getString("execution_type") }.singleOrNull()

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
        val sessionId = rows(
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
            select id from inserted
            union all
            select s.id
            from training_session s
            join owned_week ow on ow.id = s.week_id
            limit 1
            """.trimIndent(),
            listOf(
                uuid(weekId), text(ownerUserId), uuid(UUID.randomUUID()),
                localDate(performedOn), offsetDateTime(now), offsetDateTime(now),
            ),
        ) { it.getObject("id", UUID::class.java) }.singleOrNull() ?: return null

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
        val updated = rows(
            """
            update performed_set ps
            set deleted_at = ?, updated_at = ?
            from performed_exercise pe
            join training_session s on s.id = pe.session_id
            join workout_week ww on ww.id = s.week_id
            join workout w on w.id = ww.workout_id
            join program p on p.id = w.program_id
            where ps.performed_exercise_id = pe.id
              and ww.id = ? and pe.prescription_id = ? and ps.set_number = ?
              and ps.deleted_at is null and p.owner_user_id = ?
            returning s.id
            """.trimIndent(),
            listOf(
                offsetDateTime(now), offsetDateTime(now), uuid(weekId),
                uuid(prescriptionId), integer(setNumber), text(ownerUserId),
            ),
        ) { it.getObject("id", UUID::class.java) }.singleOrNull() ?: return false
        execute(
            """update training_session set updated_at = ?, execution_updated_at = ? where id = ?""",
            listOf(offsetDateTime(now), offsetDateTime(now), uuid(updated)),
        )
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
        execute(
            """update training_session set performed_on = ?, note = ?, updated_at = ? where id = ?""",
            listOf(localDate(performedOn), nullableText(note), offsetDateTime(now), uuid(sessionId)),
        )
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
        execute(
            """
            update training_session
            set status = 'COMPLETED', completed_at = ?, updated_at = ?
            where id = ?
            """.trimIndent(),
            listOf(offsetDateTime(now), offsetDateTime(now), uuid(sessionId)),
        )
        execute(
            """update workout_week set skipped_at = null where id = ?""",
            listOf(uuid(weekId)),
        )
        return true
    }

    fun resumeSession(ownerUserId: String, weekId: UUID, now: OffsetDateTime): Boolean =
        rows(
            """
            update training_session s
            set status = 'IN_PROGRESS', completed_at = null, updated_at = ?
            from workout_week ww
            join workout w on w.id = ww.workout_id
            join program p on p.id = w.program_id
            where s.week_id = ww.id and ww.id = ? and p.owner_user_id = ?
              and s.status = 'COMPLETED'
            returning s.id
            """.trimIndent(),
            listOf(offsetDateTime(now), uuid(weekId), text(ownerUserId)),
        ) { it.getObject("id", UUID::class.java) }.isNotEmpty()

    fun skipWeek(ownerUserId: String, weekId: UUID, now: OffsetDateTime): Boolean =
        rows(
            """
            update workout_week ww
            set skipped_at = ?
            from workout w
            join program p on p.id = w.program_id
            where ww.workout_id = w.id and ww.id = ? and p.owner_user_id = ?
              and ww.skipped_at is null
              and not exists (
                select 1 from training_session s where s.week_id = ww.id and s.status = 'COMPLETED'
              )
            returning ww.id
            """.trimIndent(),
            listOf(offsetDateTime(now), uuid(weekId), text(ownerUserId)),
        ) { it.getObject("id", UUID::class.java) }.isNotEmpty()

    fun restoreWeek(ownerUserId: String, weekId: UUID): Boolean =
        rows(
            """
            update workout_week ww
            set skipped_at = null
            from workout w
            join program p on p.id = w.program_id
            where ww.workout_id = w.id and ww.id = ? and p.owner_user_id = ?
              and ww.skipped_at is not null
            returning ww.id
            """.trimIndent(),
            listOf(uuid(weekId), text(ownerUserId)),
        ) { it.getObject("id", UUID::class.java) }.isNotEmpty()

    fun createProgram(ownerUserId: String, input: ProgramAuthoringInput, now: OffsetDateTime): UUID {
        execute(
            """update program set active = false, updated_at = ? where owner_user_id = ? and active = true""",
            listOf(offsetDateTime(now), text(ownerUserId)),
        )
        val programId = insertId(
            """
            insert into program (id, owner_user_id, name, note, starts_on, active, created_at, updated_at)
            values (?, ?, ?, ?, ?, true, ?, ?)
            returning id
            """.trimIndent(),
            listOf(
                uuid(UUID.randomUUID()), text(ownerUserId), text(input.name), nullableText(input.note),
                nullableLocalDate(input.startsOn), offsetDateTime(now), offsetDateTime(now),
            ),
        )
        val newlyCreatedExercises = mutableMapOf<String, UUID>()
        input.workouts.forEachIndexed { workoutIndex, workout ->
            val workoutId = insertId(
                """
                insert into workout (id, program_id, name, note, position)
                values (?, ?, ?, ?, ?) returning id
                """.trimIndent(),
                listOf(
                    uuid(UUID.randomUUID()), uuid(programId), text(workout.name),
                    nullableText(workout.note), integer(workoutIndex + 1),
                ),
            )
            workout.weeks.forEach { week ->
                val weekId = insertId(
                    """insert into workout_week (id, workout_id, week_number) values (?, ?, ?) returning id""",
                    listOf(uuid(UUID.randomUUID()), uuid(workoutId), integer(week.weekNumber)),
                )
                insertGroups(ownerUserId, weekId, week.groups, newlyCreatedExercises)
            }
        }
        return programId
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
            val groupId = insertId(
                """
                insert into workout_group (id, week_id, label, kind, position)
                values (?, ?, ?, ?, ?) returning id
                """.trimIndent(),
                listOf(
                    uuid(UUID.randomUUID()), uuid(weekId), text(group.label), text(group.kind),
                    integer(groupIndex + 1),
                ),
            )
            group.prescriptions.forEachIndexed { prescriptionIndex, prescription ->
                val exerciseId = resolveExercise(ownerUserId, prescription, newlyCreatedExercises)
                execute(
                    """
                    insert into prescription (
                        id, group_id, exercise_id, position, execution_type,
                        sets, rest, reps, load, rir, tempo, note
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    listOf(
                        uuid(UUID.randomUUID()), uuid(groupId), uuid(exerciseId), integer(prescriptionIndex + 1),
                        text(prescription.executionType), nullableText(prescription.sets),
                        nullableText(prescription.rest), nullableText(prescription.reps),
                        nullableText(prescription.load), nullableText(prescription.rir),
                        nullableText(prescription.tempo), nullableText(prescription.note),
                    ),
                )
            }
        }
    }

    private fun resolveExercise(
        ownerUserId: String,
        input: PrescriptionAuthoringInput,
        newlyCreatedExercises: MutableMap<String, UUID>,
    ): UUID {
        if (input.exerciseId != null) {
            return rows(
                """select id from exercise where id = ? and owner_user_id = ?""",
                listOf(uuid(input.exerciseId), text(ownerUserId)),
            ) { it.getObject("id", UUID::class.java) }.singleOrNull()
                ?: throw IllegalArgumentException("The selected exercise does not belong to this member.")
        }
        val normalizedName = input.exerciseName.trim().lowercase()
        newlyCreatedExercises[normalizedName]?.let { return it }
        val id = insertId(
            """insert into exercise (id, owner_user_id, name, demo_url) values (?, ?, ?, ?) returning id""",
            listOf(
                uuid(UUID.randomUUID()), text(ownerUserId), text(input.exerciseName),
                nullableText(input.demoUrl),
            ),
        )
        newlyCreatedExercises[normalizedName] = id
        return id
    }

    private fun liveExercises(weekId: UUID): List<ExerciseRow> =
        rows(
            """
            select wg.position as group_position, wg.label as group_label, wg.kind as group_kind,
                   pr.id as prescription_id, null::uuid as performed_exercise_id,
                   pr.position, e.name as exercise_name, e.demo_url, pr.execution_type,
                   pr.sets, pr.rest, pr.reps, pr.load, pr.rir, pr.tempo, pr.note,
                   null::text as execution_note
            from workout_group wg
            join prescription pr on pr.group_id = wg.id and pr.archived_at is null
            join exercise e on e.id = pr.exercise_id
            where wg.week_id = ?
            order by wg.position, pr.position
            """.trimIndent(),
            listOf(uuid(weekId)),
        ) { it.toExerciseRow() }

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
        rows(
            """
            select ps.*, pe.id as performed_exercise_id
            from performed_exercise pe
            join performed_set ps on ps.performed_exercise_id = pe.id
            where pe.session_id = ? and ps.deleted_at is null
            order by pe.position, ps.set_number
            """.trimIndent(),
            listOf(uuid(sessionId)),
        ) { rs ->
            rs.getObject("performed_exercise_id", UUID::class.java) to PerformedSetRecord(
                id = rs.getObject("id", UUID::class.java),
                setNumber = rs.getInt("set_number"),
                reps = rs.getInt("reps").takeUnless { rs.wasNull() },
                durationSeconds = rs.getInt("duration_s").takeUnless { rs.wasNull() },
                load = rs.getBigDecimal("load"),
                rir = rs.getInt("rir").takeUnless { rs.wasNull() },
                note = rs.getString("note"),
                targetReps = rs.getString("target_reps"),
                targetLoad = rs.getString("target_load"),
                targetRir = rs.getString("target_rir"),
                targetTempo = rs.getString("target_tempo"),
            )
        }

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
        rows(
            """select skipped_at is not null as skipped from workout_week where id = ?""",
            listOf(uuid(weekId)),
        ) { it.getBoolean("skipped") }.singleOrNull() == true

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
    private fun nullableLocalDate(value: LocalDate?) = JavaLocalDateColumnType() to value
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
