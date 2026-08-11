package me.gpipi.training.imports

import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.gpipi.generated.db.base.public1.Exercise
import me.gpipi.generated.db.base.public1.Program
import me.gpipi.generated.db.base.public1.SheetLink
import me.gpipi.generated.db.base.public1.TrainingImport
import me.gpipi.generated.db.base.public1.TrainingImportExerciseMatch
import me.gpipi.generated.db.base.public1.TrainingImportTab
import me.gpipi.generated.db.base.public1.TrainingImportWeek
import me.gpipi.generated.db.base.public1.Workout
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.javatime.JavaOffsetDateTimeColumnType
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update

class TrainingImportRepository {
    fun ownsProgram(ownerUserId: String, programId: UUID): Boolean =
        Program
            .select(Program.id)
            .where { (Program.id eq programId) and (Program.ownerUserId eq ownerUserId) }
            .any()

    fun workouts(ownerUserId: String, programId: UUID): List<WorkoutOption> =
        (Workout innerJoin Program)
            .select(Workout.id, Workout.name)
            .where { (Program.ownerUserId eq ownerUserId) and (Program.id eq programId) }
            .orderBy(Workout.position)
            .map { WorkoutOption(it[Workout.id], it[Workout.name]) }

    fun linkedSheet(ownerUserId: String, programId: UUID): LinkedTrainingSheet? =
        (SheetLink innerJoin Program)
            .select(SheetLink.spreadsheetId, SheetLink.spreadsheetTitle)
            .where {
                (SheetLink.programId eq programId) and
                    (Program.ownerUserId eq ownerUserId) and
                    SheetLink.replacedAt.isNull()
            }
            .map { LinkedTrainingSheet(it[SheetLink.spreadsheetId], it[SheetLink.spreadsheetTitle]) }
            .singleOrNull()

    fun createImport(
        ownerUserId: String,
        programId: UUID,
        spreadsheetId: String,
        spreadsheetTitle: String,
        tabs: List<me.gpipi.training.google.SheetTabGrid>,
        now: OffsetDateTime,
    ): UUID {
        val importId = createReadingImport(ownerUserId, programId, spreadsheetId, now)
        completeDiscovery(importId, spreadsheetTitle, tabs, now)
        return importId
    }

    fun createReadingImport(
        ownerUserId: String,
        programId: UUID,
        spreadsheetId: String,
        now: OffsetDateTime,
    ): UUID {
        if (!ownsProgram(ownerUserId, programId)) throw NoSuchElementException("Training program not found.")
        val id = UUID.randomUUID()
        TrainingImport.insert {
            it[TrainingImport.id] = id
            it[TrainingImport.ownerUserId] = ownerUserId
            it[TrainingImport.targetType] = "EXISTING_PROGRAM"
            it[TrainingImport.programId] = programId
            it[TrainingImport.spreadsheetId] = spreadsheetId
            it[TrainingImport.spreadsheetTitle] = "Reading selected Google Sheet"
            it[TrainingImport.state] = "READING"
            it[TrainingImport.createdAt] = now
            it[TrainingImport.updatedAt] = now
        }
        return id
    }

    fun completeDiscovery(
        importId: UUID,
        spreadsheetTitle: String,
        tabs: List<me.gpipi.training.google.SheetTabGrid>,
        now: OffsetDateTime,
    ) {
        TrainingImport.update({ (TrainingImport.id eq importId) and (TrainingImport.state eq "READING") }) {
            it[TrainingImport.spreadsheetTitle] = spreadsheetTitle
            it[TrainingImport.state] = "NEEDS_MAPPING"
            it[TrainingImport.errorDetail] = null
            it[TrainingImport.updatedAt] = now
        }
        tabs.forEach { tab ->
            TrainingImportTab.insert {
                it[TrainingImportTab.importId] = importId
                it[TrainingImportTab.googleSheetId] = tab.sheetId
                it[TrainingImportTab.tabTitle] = tab.title
                it[TrainingImportTab.position] = tab.position
            }
        }
    }

    fun header(ownerUserId: String, importId: UUID, lock: Boolean = false): TrainingImportHeader? = rows(
        """
        select ti.id, ti.owner_user_id, ti.program_id,
               coalesce(p.name, ti.spreadsheet_title) as program_name,
               ti.spreadsheet_id,
               ti.spreadsheet_title, ti.selected_week_number, ti.state, ti.error_detail,
               ti.created_at
        from training_import ti
        left join program p on p.id = ti.program_id
        where ti.id = ? and ti.owner_user_id = ?
        ${if (lock) "for update of ti" else ""}
        """.trimIndent(),
        listOf(uuid(importId), text(ownerUserId)),
    ) { rs ->
        TrainingImportHeader(
            id = rs.getObject("id", UUID::class.java),
            ownerUserId = rs.getString("owner_user_id"),
            programId = rs.getObject("program_id", UUID::class.java),
            programName = rs.getString("program_name"),
            spreadsheetId = rs.getString("spreadsheet_id"),
            spreadsheetTitle = rs.getString("spreadsheet_title"),
            selectedWeekNumber = rs.getInt("selected_week_number").takeUnless { rs.wasNull() },
            state = rs.getString("state"),
            errorDetail = rs.getString("error_detail"),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
        )
    }.singleOrNull()

    fun tabs(importId: UUID): List<TrainingImportTabRecord> =
        TrainingImportTab
            .selectAll()
            .where { TrainingImportTab.importId eq importId }
            .orderBy(TrainingImportTab.position)
            .map { row ->
                TrainingImportTabRecord(
                    id = row[TrainingImportTab.id],
                    importId = row[TrainingImportTab.importId],
                    googleSheetId = row[TrainingImportTab.googleSheetId],
                    tabTitle = row[TrainingImportTab.tabTitle],
                    decision = row[TrainingImportTab.decision],
                    targetWorkoutId = row[TrainingImportTab.targetWorkoutId],
                    newWorkoutName = row[TrainingImportTab.newWorkoutName],
                    position = row[TrainingImportTab.position],
                )
            }

    fun weeks(importId: UUID): List<TrainingImportWeekRecord> = rows(
        """
        select iw.*
        from training_import_week iw
        join training_import_tab it on it.id = iw.import_tab_id
        where it.import_id = ?
        order by it.position
        """.trimIndent(),
        listOf(uuid(importId)),
    ) { rs ->
        TrainingImportWeekRecord(
            id = rs.getObject("id", UUID::class.java),
            importTabId = rs.getObject("import_tab_id", UUID::class.java),
            weekNumber = rs.getInt("week_number"),
            startRow = rs.getInt("start_row"),
            endRow = rs.getInt("end_row"),
            executionBoundaryColumn = rs.getInt("execution_boundary_col"),
            executionHeaderAddress = rs.getString("execution_header_address"),
            executionHeaderValue = rs.getString("execution_header_value"),
            decision = rs.getString("decision"),
            extractedDraft = rs.getString("extracted_draft"),
            extractionContractVersion = rs.getString("extraction_contract_version"),
            extractionModel = rs.getString("extraction_model"),
            sourceSnapshot = rs.getString("source_snapshot"),
            sourceHash = rs.getString("source_hash"),
        )
    }

    fun matches(importId: UUID): List<TrainingImportMatchRecord> =
        (TrainingImportExerciseMatch innerJoin TrainingImportWeek innerJoin TrainingImportTab)
            .selectAll()
            .where { TrainingImportTab.importId eq importId }
            .orderBy(
                TrainingImportTab.position to SortOrder.ASC,
                TrainingImportExerciseMatch.sourceMovementKey to SortOrder.ASC,
            )
            .map { row ->
                TrainingImportMatchRecord(
                    importWeekId = row[TrainingImportExerciseMatch.importWeekId],
                    sourceMovementKey = row[TrainingImportExerciseMatch.sourceMovementKey],
                    sourceText = row[TrainingImportExerciseMatch.sourceText],
                    decision = row[TrainingImportExerciseMatch.decision],
                    exerciseId = row[TrainingImportExerciseMatch.exerciseId],
                    newExerciseName = row[TrainingImportExerciseMatch.newExerciseName],
                    executionType = row[TrainingImportExerciseMatch.executionType],
                    rememberAsAlias = row[TrainingImportExerciseMatch.rememberAsAlias],
                )
            }

    fun chooseWeek(importId: UUID, weekNumber: Int, now: OffsetDateTime) {
        execute(
            """
            update training_import
            set selected_week_number = ?, state = 'NEEDS_MAPPING', error_detail = null, updated_at = ?
            where id = ? and state not in ('APPLIED', 'CANCELLED')
            """.trimIndent(),
            listOf(integer(weekNumber), timestamp(now), uuid(importId)),
        )
        execute(
            "delete from training_import_week where import_tab_id in (select id from training_import_tab where import_id = ?)",
            listOf(uuid(importId)),
        )
        execute(
            """
            update training_import_tab
            set decision = null, target_workout_id = null, new_workout_name = null
            where import_id = ?
            """.trimIndent(),
            listOf(uuid(importId)),
        )
    }

    fun saveMapping(
        importId: UUID,
        selectedWeek: Int,
        mappings: List<ResolvedTabMapping>,
        now: OffsetDateTime,
    ) {
        execute(
            "delete from training_import_week where import_tab_id in (select id from training_import_tab where import_id = ?)",
            listOf(uuid(importId)),
        )
        mappings.forEach { mapping ->
            execute(
                """
                update training_import_tab
                set decision = ?, target_workout_id = ?, new_workout_name = ?
                where id = ? and import_id = ?
                """.trimIndent(),
                listOf(
                    text(mapping.decision), nullableUuid(mapping.targetWorkoutId),
                    nullableText(mapping.newWorkoutName), uuid(mapping.tabId), uuid(importId),
                ),
            )
            if (mapping.decision == "WORKOUT") {
                execute(
                    """
                    insert into training_import_week (
                        import_tab_id, week_number, start_row, end_row,
                        execution_boundary_col, execution_header_address,
                        execution_header_value, decision
                    ) values (?, ?, ?, ?, ?, ?, ?, 'KEEP')
                    """.trimIndent(),
                    listOf(
                        uuid(mapping.tabId), integer(selectedWeek), integer(checkNotNull(mapping.startRow)),
                        integer(checkNotNull(mapping.endRow)), integer(checkNotNull(mapping.executionBoundaryColumn)),
                        text(checkNotNull(mapping.executionHeaderAddress)),
                        text(checkNotNull(mapping.executionHeaderValue)),
                    ),
                )
            }
        }
        execute(
            "update training_import set state = 'NEEDS_MAPPING', error_detail = null, updated_at = ? where id = ?",
            listOf(timestamp(now), uuid(importId)),
        )
    }

    fun markExtracting(importId: UUID, now: OffsetDateTime) = transition(importId, "EXTRACTING", null, now)

    fun saveExtraction(
        weekId: UUID,
        draft: String,
        model: String,
        snapshot: String,
        hash: String,
    ) {
        execute(
            """
            update training_import_week set
                extracted_draft = ?::jsonb,
                extraction_contract_version = ?,
                extraction_model = ?,
                source_snapshot = ?::jsonb,
                source_hash = ?
            where id = ?
            """.trimIndent(),
            listOf(
                text(draft), text(TRAINING_EXTRACTION_CONTRACT_VERSION), text(model),
                text(snapshot), text(hash), uuid(weekId),
            ),
        )
    }

    fun initializeMatches(importWeekId: UUID, draft: TrainingPrescriptionExtraction) {
        TrainingImportExerciseMatch.deleteWhere { TrainingImportExerciseMatch.importWeekId eq importWeekId }
        draft.groups.flatMap(ExtractedTrainingGroup::prescriptions).forEach { movement ->
            TrainingImportExerciseMatch.insert {
                it[TrainingImportExerciseMatch.importWeekId] = importWeekId
                it[TrainingImportExerciseMatch.sourceMovementKey] = movement.movementAddress
                it[TrainingImportExerciseMatch.sourceText] = movement.movement
            }
        }
    }

    fun exactExerciseMatch(ownerUserId: String, sourceText: String): UUID? = rows(
        """
        select e.id
        from exercise e
        left join exercise_alias ea on ea.exercise_id = e.id and ea.owner_user_id = e.owner_user_id
        where e.owner_user_id = ?
          and (lower(btrim(e.name)) = lower(btrim(?)) or lower(btrim(ea.alias)) = lower(btrim(?)))
        order by case when lower(btrim(e.name)) = lower(btrim(?)) then 0 else 1 end
        limit 1
        """.trimIndent(),
        listOf(text(ownerUserId), text(sourceText), text(sourceText), text(sourceText)),
    ) { it.getObject("id", UUID::class.java) }.singleOrNull()

    fun proposeExactMatches(ownerUserId: String, importWeekId: UUID, draft: TrainingPrescriptionExtraction) {
        draft.groups.flatMap(ExtractedTrainingGroup::prescriptions).forEach { movement ->
            val exerciseId = exactExerciseMatch(ownerUserId, movement.movement)
            if (exerciseId != null) {
                execute(
                    """
                    update training_import_exercise_match
                    set exercise_id = ?
                    where import_week_id = ? and source_movement_key = ?
                    """.trimIndent(),
                    listOf(uuid(exerciseId), uuid(importWeekId), text(movement.movementAddress)),
                )
            }
        }
    }

    fun saveReviewedDraft(
        importWeekId: UUID,
        draftJson: String,
        movements: List<ReviewedTrainingPrescription>,
    ) {
        execute(
            "update training_import_week set extracted_draft = ?::jsonb where id = ?",
            listOf(text(draftJson), uuid(importWeekId)),
        )
        movements.forEach { movement ->
            execute(
                """
                update training_import_exercise_match set
                    source_text = ?, decision = ?, exercise_id = ?, new_exercise_name = ?,
                    execution_type = ?, remember_as_alias = ?
                where import_week_id = ? and source_movement_key = ?
                """.trimIndent(),
                listOf(
                    text(movement.movement), text(movement.decision),
                    nullableUuid(movement.exerciseId?.let(UUID::fromString)), nullableText(movement.newExerciseName),
                    nullableText(movement.executionType), bool(movement.rememberAsAlias),
                    uuid(importWeekId), text(movement.movementAddress),
                ),
            )
        }
    }

    fun transition(importId: UUID, state: String, error: String?, now: OffsetDateTime) {
        TrainingImport.update({ TrainingImport.id eq importId }) {
            it[TrainingImport.state] = state
            it[TrainingImport.errorDetail] = error
            it[TrainingImport.updatedAt] = now
        }
    }

    fun cancel(importId: UUID, now: OffsetDateTime) = transition(importId, "CANCELLED", null, now)

    fun apply(
        ownerUserId: String,
        header: TrainingImportHeader,
        workouts: List<AppliedImportWorkout>,
        now: OffsetDateTime,
        json: Json,
    ): Int {
        val locked = header(ownerUserId, header.id, lock = true)
            ?: throw NoSuchElementException("Training import not found.")
        require(locked.state == "REVIEW") { "Only a reviewed import can be applied." }
        val selectedWeek = checkNotNull(locked.selectedWeekNumber)
        require(workouts.isNotEmpty()) { "The import has no included workout." }
        val programId = checkNotNull(locked.programId) { "The target program is missing." }

        val currentLink = rows(
            "select id, spreadsheet_id from sheet_link where program_id = ? and replaced_at is null for update",
            listOf(uuid(programId)),
        ) { it.getObject("id", UUID::class.java) to it.getString("spreadsheet_id") }.singleOrNull()
        val sheetLinkId = if (currentLink?.second == locked.spreadsheetId) {
            execute(
                """
                update sheet_link set spreadsheet_title = ?, connected_by_user_id = ?, updated_at = ?
                where id = ?
                """.trimIndent(),
                listOf(text(locked.spreadsheetTitle), text(ownerUserId), timestamp(now), uuid(currentLink.first)),
            )
            currentLink.first
        } else {
            if (currentLink != null) {
                execute(
                    "update sheet_link set replaced_at = ?, updated_at = ? where id = ?",
                    listOf(timestamp(now), timestamp(now), uuid(currentLink.first)),
                )
            }
            rows(
                """
                insert into sheet_link (
                    program_id, spreadsheet_id, spreadsheet_title,
                    connected_by_user_id, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?)
                returning id
                """.trimIndent(),
                listOf(
                    uuid(programId), text(locked.spreadsheetId), text(locked.spreadsheetTitle),
                    text(ownerUserId), timestamp(now), timestamp(now),
                ),
            ) { it.getObject("id", UUID::class.java) }.single()
        }

        workouts.forEach { imported ->
            val workoutId = imported.tab.targetWorkoutId ?: findOrCreateWorkout(
                programId = programId,
                name = checkNotNull(imported.tab.newWorkoutName),
            ).also { createdId ->
                execute(
                    "update training_import_tab set target_workout_id = ?, new_workout_name = null where id = ?",
                    listOf(uuid(createdId), uuid(imported.tab.id)),
                )
            }
            val weekId = rows(
                """
                insert into workout_week (workout_id, week_number)
                values (?, ?)
                on conflict (workout_id, week_number) do update set week_number = excluded.week_number
                returning id
                """.trimIndent(),
                listOf(uuid(workoutId), integer(selectedWeek)),
            ) { it.getObject("id", UUID::class.java) }.single()

            execute(
                "update training_import_week set target_week_id = ? where id = ?",
                listOf(uuid(weekId), uuid(imported.week.id)),
            )
            rows(
                """
                insert into sheet_week_link (
                    week_id, sheet_link_id, google_sheet_id, tab_title,
                    week_start_row, week_end_row, execution_boundary_col,
                    execution_header_address, execution_header_value, source_snapshot, source_hash
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                on conflict (week_id) do update set
                    sheet_link_id = excluded.sheet_link_id,
                    google_sheet_id = excluded.google_sheet_id,
                    tab_title = excluded.tab_title,
                    week_start_row = excluded.week_start_row,
                    week_end_row = excluded.week_end_row,
                    execution_boundary_col = excluded.execution_boundary_col,
                    execution_header_address = excluded.execution_header_address,
                    execution_header_value = excluded.execution_header_value,
                    source_snapshot = excluded.source_snapshot,
                    source_hash = excluded.source_hash
                returning week_id
                """.trimIndent(),
                listOf(
                    uuid(weekId), uuid(sheetLinkId), long(imported.tab.googleSheetId), text(imported.tab.tabTitle),
                    integer(imported.week.startRow), integer(imported.week.endRow),
                    integer(imported.week.executionBoundaryColumn), text(imported.week.executionHeaderAddress),
                    text(imported.week.executionHeaderValue), text(checkNotNull(imported.week.sourceSnapshot)),
                    text(checkNotNull(imported.week.sourceHash)),
                ),
            ) { it.getObject("week_id", UUID::class.java) }

            val executionLayout = json.decodeFromString(
                RedactedTrainingSourceSnapshot.serializer(),
                checkNotNull(imported.week.sourceSnapshot),
            ).executionLayout
            imported.draft.groups.forEachIndexed { groupIndex, group ->
                val groupId = rows(
                    """
                    insert into workout_group (week_id, label, kind, position)
                    values (?, ?, ?, ?)
                    on conflict (week_id, position) do update set
                        label = excluded.label, kind = excluded.kind
                    returning id
                    """.trimIndent(),
                    listOf(uuid(weekId), text(group.label), text(group.kind), integer(groupIndex + 1)),
                ) { it.getObject("id", UUID::class.java) }.single()

                var includedPosition = 0
                group.prescriptions.forEach { movement ->
                    val match = imported.matches.getValue(movement.movementAddress)
                    if (match.decision == "EXCLUDE") return@forEach
                    includedPosition++
                    val exerciseId = when (match.decision) {
                        "MATCH" -> checkNotNull(match.exerciseId).also { requireOwnedExercise(ownerUserId, it) }
                        "CREATE" -> findOrCreateExercise(
                            ownerUserId,
                            checkNotNull(match.newExerciseName),
                            movement.demoUrl,
                            now,
                        )
                        else -> error("Every movement must be explicitly resolved before Apply.")
                    }
                    if (match.rememberAsAlias && !movement.movement.equals(exerciseName(exerciseId), ignoreCase = true)) {
                        execute(
                            """
                            insert into exercise_alias (exercise_id, owner_user_id, alias, created_at)
                            values (?, ?, ?, ?)
                            on conflict do nothing
                            """.trimIndent(),
                            listOf(uuid(exerciseId), text(ownerUserId), text(movement.movement), timestamp(now)),
                        )
                    }
                    val existingLinked = rows(
                        """
                        select spl.prescription_id
                        from sheet_prescription_link spl
                        where spl.sheet_week_id = ? and spl.movement_address = ?
                        """.trimIndent(),
                        listOf(uuid(weekId), text(movement.movementAddress)),
                    ) { it.getObject("prescription_id", UUID::class.java) }.singleOrNull()
                    val existingAtPosition = rows(
                        "select id from prescription where group_id = ? and position = ?",
                        listOf(uuid(groupId), integer(includedPosition)),
                    ) { it.getObject("id", UUID::class.java) }.singleOrNull()
                    val prescriptionId = existingLinked ?: existingAtPosition ?: UUID.randomUUID()
                    if (existingLinked != null || existingAtPosition != null) {
                        execute(
                            """
                            update prescription set
                                group_id = ?, exercise_id = ?, position = ?, execution_type = ?,
                                sets = ?, rest = ?, reps = ?, load = ?, rir = ?, tempo = ?, note = ?, archived_at = null
                            where id = ?
                            """.trimIndent(),
                            listOf(
                                uuid(groupId), uuid(exerciseId), integer(includedPosition),
                                text(checkNotNull(match.executionType)), nullableText(movement.sets),
                                nullableText(movement.rest), nullableText(movement.reps), nullableText(movement.load),
                                nullableText(movement.rir), nullableText(movement.tempo), nullableText(movement.note),
                                uuid(prescriptionId),
                            ),
                        )
                    } else {
                        execute(
                            """
                            insert into prescription (
                                id, group_id, exercise_id, position, execution_type,
                                sets, rest, reps, load, rir, tempo, note
                            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                            listOf(
                                uuid(prescriptionId), uuid(groupId), uuid(exerciseId), integer(includedPosition),
                                text(checkNotNull(match.executionType)), nullableText(movement.sets),
                                nullableText(movement.rest), nullableText(movement.reps), nullableText(movement.load),
                                nullableText(movement.rir), nullableText(movement.tempo), nullableText(movement.note),
                            ),
                        )
                    }
                    val sourceCellsJson = json.encodeToString(movement.sourceCells)
                    val executionCellsJson = json.encodeToString(
                        executionDestinations(executionLayout, movement.movementAddress),
                    )
                    execute(
                        """
                        insert into sheet_prescription_link (
                            prescription_id, sheet_week_id, movement_address, movement_text,
                            source_cells, execution_cells
                        ) values (?, ?, ?, ?, ?::jsonb, ?::jsonb)
                        on conflict (prescription_id) do update set
                            sheet_week_id = excluded.sheet_week_id,
                            movement_address = excluded.movement_address,
                            movement_text = excluded.movement_text,
                            source_cells = excluded.source_cells,
                            execution_cells = excluded.execution_cells
                        """.trimIndent(),
                        listOf(
                            uuid(prescriptionId), uuid(weekId), text(movement.movementAddress),
                            text(movement.movement), text(sourceCellsJson), text(executionCellsJson),
                        ),
                    )
                }
            }
        }
        execute(
            """
            update training_import
            set state = 'APPLIED', error_detail = null, applied_at = ?, updated_at = ?
            where id = ?
            """.trimIndent(),
            listOf(timestamp(now), timestamp(now), uuid(header.id)),
        )
        return selectedWeek
    }

    private fun findOrCreateWorkout(programId: UUID, name: String): UUID {
        val existing = rows(
            "select id from workout where program_id = ? and lower(btrim(name)) = lower(btrim(?))",
            listOf(uuid(programId), text(name)),
        ) { it.getObject("id", UUID::class.java) }.singleOrNull()
        if (existing != null) return existing
        return rows(
            """
            insert into workout (program_id, name, position)
            select ?, ?, coalesce(max(position), 0) + 1 from workout where program_id = ?
            returning id
            """.trimIndent(),
            listOf(uuid(programId), text(name), uuid(programId)),
        ) { it.getObject("id", UUID::class.java) }.single()
    }

    private fun findOrCreateExercise(
        ownerUserId: String,
        name: String,
        demoUrl: String?,
        now: OffsetDateTime,
    ): UUID = rows(
        "select id from exercise where owner_user_id = ? and lower(btrim(name)) = lower(btrim(?))",
        listOf(text(ownerUserId), text(name)),
    ) { it.getObject("id", UUID::class.java) }.singleOrNull() ?: rows(
        """
        insert into exercise (owner_user_id, name, demo_url, created_at)
        values (?, ?, ?, ?)
        returning id
        """.trimIndent(),
        listOf(text(ownerUserId), text(name), nullableText(demoUrl), timestamp(now)),
    ) { it.getObject("id", UUID::class.java) }.single()

    private fun requireOwnedExercise(ownerUserId: String, exerciseId: UUID) {
        val owned = Exercise
            .select(Exercise.id)
            .where { (Exercise.id eq exerciseId) and (Exercise.ownerUserId eq ownerUserId) }
            .any()
        require(owned) { "Selected exercise is not in this member's catalog." }
    }

    private fun exerciseName(exerciseId: UUID): String =
        Exercise
            .select(Exercise.name)
            .where { Exercise.id eq exerciseId }
            .single()[Exercise.name]

    private fun executionDestinations(
        layout: List<ExecutionLayoutCell>,
        movementAddress: String,
    ): List<ExecutionCellDestination> {
        val movementRow = Regex("^[A-Z]+(\\d+)$").matchEntire(movementAddress)
            ?.groupValues?.get(1)?.toInt() ?: return emptyList()
        val counters = mutableMapOf<String, Int>()
        return layout.sortedBy(ExecutionLayoutCell::column).mapNotNull { cell ->
            val field = when (cell.label.trim().lowercase()) {
                "rep", "reps" -> "REPS"
                "load", "kg" -> "LOAD"
                "rir" -> "RIR"
                else -> null
            } ?: return@mapNotNull null
            val setNumber = (counters[field] ?: 0) + 1
            counters[field] = setNumber
            ExecutionCellDestination(
                setNumber = setNumber,
                field = field,
                address = "${me.gpipi.training.google.columnName(cell.column)}$movementRow",
                row = movementRow,
                column = cell.column,
            )
        }
    }

    private fun execute(statement: String, args: List<Pair<IColumnType<*>, Any?>>) {
        transaction().exec(statement, args, StatementType.UPDATE)
    }

    private fun <T> rows(
        statement: String,
        args: List<Pair<IColumnType<*>, Any?>>,
        type: StatementType = StatementType.SELECT,
        transform: (java.sql.ResultSet) -> T,
    ): List<T> = transaction().exec(statement, args, type) { rs ->
        buildList { while (rs.next()) add(transform(rs)) }
    }.orEmpty()

    private fun transaction() = checkNotNull(TransactionManager.currentOrNull())
    private fun text(value: String) = TextColumnType() to value
    private fun nullableText(value: String?) = TextColumnType() to value
    private fun uuid(value: UUID) = UUIDColumnType() to value
    private fun nullableUuid(value: UUID?) = UUIDColumnType() to value
    private fun integer(value: Int) = IntegerColumnType() to value
    private fun long(value: Long) = LongColumnType() to value
    private fun bool(value: Boolean) = BooleanColumnType() to value
    private fun timestamp(value: OffsetDateTime) = JavaOffsetDateTimeColumnType() to value
}

data class ResolvedTabMapping(
    val tabId: UUID,
    val decision: String,
    val targetWorkoutId: UUID?,
    val newWorkoutName: String?,
    val startRow: Int?,
    val endRow: Int?,
    val executionBoundaryColumn: Int?,
    val executionHeaderAddress: String?,
    val executionHeaderValue: String?,
)

data class AppliedImportWorkout(
    val tab: TrainingImportTabRecord,
    val week: TrainingImportWeekRecord,
    val draft: TrainingPrescriptionExtraction,
    val matches: Map<String, TrainingImportMatchRecord>,
)

@kotlinx.serialization.Serializable
data class ExecutionCellDestination(
    val setNumber: Int,
    val field: String,
    val address: String,
    val row: Int,
    val column: Int,
)
