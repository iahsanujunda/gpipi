package me.gpipi.training.writes

import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.gpipi.training.google.SheetValue
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.javatime.JavaOffsetDateTimeColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

class TrainingWriteRepository(
    private val json: Json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = true },
) {
    fun source(ownerUserId: String, sessionId: UUID): WriteSource? {
        val header = rows(
            """
            select p.id as program_id, p.name as program_name, s.id as session_id,
                   s.status, s.execution_updated_at, ww.week_number, w.name as workout_name
            from training_session s
            join workout_week ww on ww.id = s.week_id
            join workout w on w.id = ww.workout_id
            join program p on p.id = w.program_id
            where s.id = ? and p.owner_user_id = ?
            """.trimIndent(),
            listOf(uuid(sessionId), text(ownerUserId)),
        ) { rs ->
            SourceHeader(
                programId = rs.getObject("program_id", UUID::class.java),
                programName = rs.getString("program_name"),
                sessionId = rs.getObject("session_id", UUID::class.java),
                status = rs.getString("status"),
                executionUpdatedAt = rs.getObject("execution_updated_at", OffsetDateTime::class.java),
                weekNumber = rs.getInt("week_number"),
                workoutName = rs.getString("workout_name"),
            )
        }.singleOrNull() ?: return null

        val movements = rows(
            """
            select pe.id, pe.position, pe.target_group_label, pe.target_group_kind,
                   pe.target_exercise_name, pe.target_execution_type, pe.target_sets,
                   pe.target_rest, pe.target_reps, pe.target_load, pe.target_rir,
                   pe.target_tempo, pe.target_note
            from performed_exercise pe
            where pe.session_id = ?
            order by pe.position
            """.trimIndent(),
            listOf(uuid(sessionId)),
        ) { rs ->
            WriteSourceMovement(
                performedExerciseId = rs.getObject("id", UUID::class.java),
                position = rs.getInt("position"),
                groupLabel = rs.getString("target_group_label"),
                groupKind = rs.getString("target_group_kind"),
                exerciseName = rs.getString("target_exercise_name"),
                executionType = rs.getString("target_execution_type"),
                targetSets = rs.getString("target_sets"),
                targetRest = rs.getString("target_rest"),
                targetReps = rs.getString("target_reps"),
                targetLoad = rs.getString("target_load"),
                targetRir = rs.getString("target_rir"),
                targetTempo = rs.getString("target_tempo"),
                targetNote = rs.getString("target_note"),
                sets = emptyList(),
            )
        }
        val sets = rows(
            """
            select ps.performed_exercise_id, ps.id, ps.set_number, ps.reps, ps.duration_s,
                   ps.load, ps.rir, ps.deleted_at
            from performed_set ps
            join performed_exercise pe on pe.id = ps.performed_exercise_id
            where pe.session_id = ?
            order by pe.position, ps.set_number
            """.trimIndent(),
            listOf(uuid(sessionId)),
        ) { rs ->
            rs.getObject("performed_exercise_id", UUID::class.java) to WriteSourceSet(
                id = rs.getObject("id", UUID::class.java),
                setNumber = rs.getInt("set_number"),
                reps = rs.getInt("reps").takeUnless { rs.wasNull() },
                durationSeconds = rs.getInt("duration_s").takeUnless { rs.wasNull() },
                load = rs.getBigDecimal("load")?.stripTrailingZeros()?.toPlainString(),
                rir = rs.getInt("rir").takeUnless { rs.wasNull() },
                deleted = rs.getObject("deleted_at", OffsetDateTime::class.java) != null,
            )
        }.groupBy({ it.first }, { it.second })
        return WriteSource(
            ownerUserId = ownerUserId,
            programId = header.programId,
            programName = header.programName,
            sessionId = header.sessionId,
            sessionStatus = header.status,
            weekNumber = header.weekNumber,
            workoutName = header.workoutName,
            executionUpdatedAt = header.executionUpdatedAt,
            movements = movements.map { it.copy(sets = sets[it.performedExerciseId].orEmpty()) },
        )
    }

    fun linkedSheet(ownerUserId: String, programId: UUID): Pair<String, String>? = rows(
        """
        select sl.spreadsheet_id, sl.spreadsheet_title
        from sheet_link sl
        join program p on p.id = sl.program_id
        where sl.program_id = ? and sl.replaced_at is null and p.owner_user_id = ?
        """.trimIndent(),
        listOf(uuid(programId), text(ownerUserId)),
    ) { it.getString("spreadsheet_id") to it.getString("spreadsheet_title") }.singleOrNull()

    fun createAttempt(
        source: WriteSource,
        spreadsheetId: String,
        spreadsheetTitle: String,
        availableWeeks: List<Int>,
        discoverySnapshot: String,
        now: OffsetDateTime,
    ): UUID = rows(
        """
        insert into sheet_write (
            program_id, session_id, source_week_number, source_workout_name,
            spreadsheet_id, spreadsheet_title, available_week_numbers, discovery_snapshot,
            written_by_user_id, idempotency_key, status, created_at, status_updated_at
        ) values (?, ?, ?, ?, ?, ?, ?::integer[], ?::jsonb, ?, ?, 'NEEDS_WEEK', ?, ?)
        returning id
        """.trimIndent(),
        listOf(
            uuid(source.programId), uuid(source.sessionId), integer(source.weekNumber), text(source.workoutName),
            text(spreadsheetId), text(spreadsheetTitle), integerArray(availableWeeks), text(discoverySnapshot),
            text(source.ownerUserId), uuid(UUID.randomUUID()), timestamp(now), timestamp(now),
        ),
    ) { it.getObject("id", UUID::class.java) }.single()

    fun attempt(ownerUserId: String, attemptId: UUID): WriteAttemptRecord? = rows(
        """
        select sw.*
        from sheet_write sw
        join program p on p.id = sw.program_id
        where sw.id = ? and sw.written_by_user_id = ? and p.owner_user_id = ?
        """.trimIndent(),
        listOf(uuid(attemptId), text(ownerUserId), text(ownerUserId)),
    ) { it.toAttempt() }.singleOrNull()

    fun modelMatches(attemptId: UUID): List<WriteMovementRecord> = movements(attemptId)

    fun beginMatching(attemptId: UUID, targetWeek: Int, now: OffsetDateTime) {
        execute(
            """
            update sheet_write set target_week_number = ?, status = 'MATCHING',
                detail = null, status_updated_at = ? where id = ?
            """.trimIndent(),
            listOf(integer(targetWeek), timestamp(now), uuid(attemptId)),
        )
    }

    fun saveMatching(
        attemptId: UUID,
        targetWeek: Int,
        candidate: WriteCandidateTab?,
        contractVersion: String,
        model: String,
        snapshot: String,
        sourceHash: String,
        proposed: List<WriteMovementRecord>,
        now: OffsetDateTime,
    ) {
        execute("delete from sheet_write_movement where sheet_write_id = ?", listOf(uuid(attemptId)))
        proposed.forEach { movement -> insertMovement(attemptId, movement) }
        execute(
            """
            update sheet_write set
                target_week_number = ?, target_google_sheet_id = ?, target_tab_title = ?,
                target_week_start_row = ?, target_week_end_row = ?,
                target_week_header_address = ?, target_week_header_value = ?,
                execution_boundary_col = ?, execution_header_address = ?, execution_header_value = ?,
                matching_contract_version = ?, matching_model = ?,
                matching_source_snapshot = ?::jsonb, matching_source_hash = ?,
                status = 'REVIEW', detail = null, status_updated_at = ?
            where id = ?
            """.trimIndent(),
            listOf(
                integer(targetWeek), nullableLong(candidate?.googleSheetId), nullableText(candidate?.title),
                nullableInteger(candidate?.startRow), nullableInteger(candidate?.endRow),
                nullableText(candidate?.weekHeaderAddress), nullableText(candidate?.weekHeaderValue),
                nullableInteger(candidate?.executionBoundaryColumn), nullableText(candidate?.executionHeaderAddress),
                nullableText(candidate?.executionHeaderValue), text(contractVersion), text(model), text(snapshot),
                text(sourceHash), timestamp(now), uuid(attemptId),
            ),
        )
    }

    fun confirmMatches(
        attemptId: UUID,
        candidate: WriteCandidateTab,
        confirmed: List<WriteMovementRecord>,
        updatedSnapshot: String,
        sourceHash: String,
        now: OffsetDateTime,
    ) {
        execute("delete from sheet_write_cell where sheet_write_movement_id in (select id from sheet_write_movement where sheet_write_id = ?)", listOf(uuid(attemptId)))
        execute("delete from sheet_write_movement where sheet_write_id = ?", listOf(uuid(attemptId)))
        confirmed.forEach { insertMovement(attemptId, it.copy(confirmed = true)) }
        execute(
            """
            update sheet_write set target_google_sheet_id = ?, target_tab_title = ?,
                target_week_start_row = ?, target_week_end_row = ?,
                target_week_header_address = ?, target_week_header_value = ?,
                execution_boundary_col = ?, execution_header_address = ?, execution_header_value = ?,
                matching_source_snapshot = ?::jsonb, matching_source_hash = ?,
                status = 'REVIEW', detail = null, status_updated_at = ?
            where id = ?
            """.trimIndent(),
            listOf(
                long(candidate.googleSheetId), text(candidate.title), integer(candidate.startRow), integer(candidate.endRow),
                text(candidate.weekHeaderAddress), text(candidate.weekHeaderValue), integer(candidate.executionBoundaryColumn),
                text(candidate.executionHeaderAddress), text(candidate.executionHeaderValue), text(updatedSnapshot),
                text(sourceHash), timestamp(now), uuid(attemptId),
            ),
        )
    }

    fun movements(attemptId: UUID): List<WriteMovementRecord> = rows(
        """
        select id, performed_exercise_id, position, sheet_movement_address,
               sheet_movement_text, match_source, confirmed
        from sheet_write_movement where sheet_write_id = ? order by position
        """.trimIndent(),
        listOf(uuid(attemptId)),
    ) { rs ->
        WriteMovementRecord(
            id = rs.getObject("id", UUID::class.java),
            performedExerciseId = rs.getObject("performed_exercise_id", UUID::class.java),
            position = rs.getInt("position"),
            sheetMovementAddress = rs.getString("sheet_movement_address"),
            sheetMovementText = rs.getString("sheet_movement_text"),
            matchSource = rs.getString("match_source"),
            confirmed = rs.getBoolean("confirmed"),
        )
    }

    fun savePrepared(
        attemptId: UUID,
        cells: List<PreparedCell>,
        projectionHash: String,
        payloadHash: String,
        now: OffsetDateTime,
    ) {
        val movements = movements(attemptId).associateBy { it.performedExerciseId }
        execute("delete from sheet_write_cell where sheet_write_movement_id in (select id from sheet_write_movement where sheet_write_id = ?)", listOf(uuid(attemptId)))
        cells.forEach { cell ->
            val movementId = movements.getValue(cell.performedExerciseId).id
            execute(
                """
                insert into sheet_write_cell (
                    sheet_write_movement_id, performed_set_id, set_number, field,
                    row_index, column_index, cell_address,
                    observed_user_entered_value, observed_formatted_value,
                    action, proposed_user_entered_value
                ) values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb)
                """.trimIndent(),
                listOf(
                    uuid(movementId), nullableUuid(cell.performedSetId), integer(cell.setNumber), text(cell.field),
                    integer(cell.row - 1), integer(cell.column - 1), text(cell.address),
                    nullableJson(cell.observedValue), nullableText(cell.observedDisplay), text(cell.action),
                    nullableJson(cell.proposedValue),
                ),
            )
        }
        execute(
            """
            update sheet_write set execution_projection_hash = ?, payload_hash = ?,
                status = 'PREPARED', detail = null, status_updated_at = ? where id = ?
            """.trimIndent(),
            listOf(text(projectionHash), text(payloadHash), timestamp(now), uuid(attemptId)),
        )
    }

    fun cells(attemptId: UUID): List<WriteCellRecord> = rows(
        """
        select c.*, m.performed_exercise_id
        from sheet_write_cell c
        join sheet_write_movement m on m.id = c.sheet_write_movement_id
        where m.sheet_write_id = ?
        order by m.position, c.set_number,
                 case c.field when 'REPS' then 1 when 'LOAD' then 2 else 3 end
        """.trimIndent(),
        listOf(uuid(attemptId)),
    ) { rs ->
        WriteCellRecord(
            id = rs.getObject("id", UUID::class.java),
            movementId = rs.getObject("sheet_write_movement_id", UUID::class.java),
            performedExerciseId = rs.getObject("performed_exercise_id", UUID::class.java),
            performedSetId = rs.getObject("performed_set_id", UUID::class.java),
            setNumber = rs.getInt("set_number"),
            field = rs.getString("field"),
            row = rs.getInt("row_index") + 1,
            column = rs.getInt("column_index") + 1,
            address = rs.getString("cell_address"),
            observedValue = rs.jsonValue("observed_user_entered_value"),
            observedDisplay = rs.getString("observed_formatted_value"),
            prewriteValue = rs.jsonValue("prewrite_user_entered_value"),
            prewriteDisplay = rs.getString("prewrite_formatted_value"),
            action = rs.getString("action"),
            proposedValue = rs.jsonValue("proposed_user_entered_value"),
            verifiedValue = rs.jsonValue("verified_user_entered_value"),
            verifiedDisplay = rs.getString("verified_formatted_value"),
        )
    }

    fun claimPrepared(attemptId: UUID, now: OffsetDateTime): Boolean = rows(
        """
        update sheet_write set status = 'VALIDATING', status_updated_at = ?
        where id = ? and status = 'PREPARED'
        returning id
        """.trimIndent(),
        listOf(timestamp(now), uuid(attemptId)),
    ) { it.getObject("id", UUID::class.java) }.size == 1

    fun markSending(attemptId: UUID, apiCalled: Boolean, now: OffsetDateTime) {
        execute(
            """
            update sheet_write set status = 'SENDING', api_called = ?, detail = null,
                status_updated_at = ? where id = ?
            """.trimIndent(),
            listOf(bool(apiCalled), timestamp(now), uuid(attemptId)),
        )
    }

    fun savePrewrite(attemptId: UUID, values: Map<String, Pair<SheetValue?, String?>>, now: OffsetDateTime) {
        values.forEach { (address, value) ->
            execute(
                """
                update sheet_write_cell c set prewrite_user_entered_value = ?::jsonb,
                    prewrite_formatted_value = ?
                from sheet_write_movement m
                where c.sheet_write_movement_id = m.id and m.sheet_write_id = ? and c.cell_address = ?
                """.trimIndent(),
                listOf(nullableJson(value.first), nullableText(value.second), uuid(attemptId), text(address)),
            )
        }
        execute("update sheet_write set status_updated_at = ? where id = ?", listOf(timestamp(now), uuid(attemptId)))
    }

    fun saveVerification(
        attemptId: UUID,
        values: Map<String, Pair<SheetValue?, String?>>,
        status: String,
        apiCalled: Boolean,
        detail: String?,
        now: OffsetDateTime,
    ) {
        values.forEach { (address, value) ->
            execute(
                """
                update sheet_write_cell c set verified_user_entered_value = ?::jsonb,
                    verified_formatted_value = ?, verified_at = ?
                from sheet_write_movement m
                where c.sheet_write_movement_id = m.id and m.sheet_write_id = ? and c.cell_address = ?
                """.trimIndent(),
                listOf(nullableJson(value.first), nullableText(value.second), timestamp(now), uuid(attemptId), text(address)),
            )
        }
        execute(
            """
            update sheet_write set status = ?, api_called = ?, detail = ?,
                status_updated_at = ?, finished_at = case when ? = 'SUCCEEDED' then ? else finished_at end
            where id = ?
            """.trimIndent(),
            listOf(text(status), bool(apiCalled), nullableText(detail), timestamp(now), text(status), timestamp(now), uuid(attemptId)),
        )
    }

    fun transition(attemptId: UUID, status: String, detail: String?, now: OffsetDateTime) {
        execute(
            "update sheet_write set status = ?, detail = ?, status_updated_at = ? where id = ?",
            listOf(text(status), nullableText(detail), timestamp(now), uuid(attemptId)),
        )
    }

    fun updateDefaultSheet(attempt: WriteAttemptRecord, ownerUserId: String, now: OffsetDateTime) {
        val current = linkedSheet(ownerUserId, attempt.programId)
        if (current?.first == attempt.spreadsheetId) return
        execute(
            "update sheet_link set replaced_at = ?, updated_at = ? where program_id = ? and replaced_at is null",
            listOf(timestamp(now), timestamp(now), uuid(attempt.programId)),
        )
        execute(
            """
            insert into sheet_link (
                program_id, spreadsheet_id, spreadsheet_title, connected_by_user_id, created_at, updated_at
            ) values (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf(
                uuid(attempt.programId), text(attempt.spreadsheetId), text(attempt.spreadsheetTitle),
                text(ownerUserId), timestamp(now), timestamp(now),
            ),
        )
    }

    fun syncStatus(ownerUserId: String, sessionId: UUID): TrainingWriteStatusResponse? = rows(
        """
        select sw.id, sw.status, sw.spreadsheet_title, sw.target_week_number, sw.finished_at,
               s.execution_updated_at
        from sheet_write sw
        join training_session s on s.id = sw.session_id
        join workout_week ww on ww.id = s.week_id
        join workout w on w.id = ww.workout_id
        join program p on p.id = w.program_id
        where sw.session_id = ? and p.owner_user_id = ?
          and sw.status in ('SUCCEEDED', 'UNKNOWN', 'VERIFY_CONFLICT')
        order by sw.created_at desc limit 1
        """.trimIndent(),
        listOf(uuid(sessionId), text(ownerUserId)),
    ) { rs ->
        val status = rs.getString("status")
        val finished = rs.getObject("finished_at", OffsetDateTime::class.java)
        val executionUpdated = rs.getObject("execution_updated_at", OffsetDateTime::class.java)
        val state = when {
            status == "UNKNOWN" -> "UNKNOWN"
            status == "VERIFY_CONFLICT" -> "VERIFY_CONFLICT"
            finished != null && executionUpdated != null && executionUpdated.isAfter(finished) -> "CHANGED"
            else -> "WRITTEN"
        }
        TrainingWriteStatusResponse(
            state = state,
            sheetTitle = rs.getString("spreadsheet_title"),
            targetWeekNumber = rs.getInt("target_week_number").takeUnless { rs.wasNull() },
            finishedAt = finished?.toString(),
            attemptId = rs.getObject("id", UUID::class.java).toString(),
        )
    }.singleOrNull()

    private fun insertMovement(attemptId: UUID, movement: WriteMovementRecord) {
        execute(
            """
            insert into sheet_write_movement (
                id, sheet_write_id, performed_exercise_id, position, sheet_movement_address,
                sheet_movement_text, match_source, confirmed
            ) values (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf(
                uuid(movement.id), uuid(attemptId), uuid(movement.performedExerciseId), integer(movement.position),
                text(movement.sheetMovementAddress), text(movement.sheetMovementText), text(movement.matchSource),
                bool(movement.confirmed),
            ),
        )
    }

    private fun java.sql.ResultSet.toAttempt() = WriteAttemptRecord(
        id = getObject("id", UUID::class.java),
        programId = getObject("program_id", UUID::class.java),
        sessionId = getObject("session_id", UUID::class.java),
        sourceWeekNumber = getInt("source_week_number"),
        sourceWorkoutName = getString("source_workout_name"),
        spreadsheetId = getString("spreadsheet_id"),
        spreadsheetTitle = getString("spreadsheet_title"),
        availableWeekNumbers = (getArray("available_week_numbers")?.array as? Array<*>)
            .orEmpty().map { (it as Number).toInt() },
        discoverySnapshot = getString("discovery_snapshot"),
        targetWeekNumber = getInt("target_week_number").takeUnless { wasNull() },
        targetGoogleSheetId = getLong("target_google_sheet_id").takeUnless { wasNull() },
        targetTabTitle = getString("target_tab_title"),
        targetWeekStartRow = getInt("target_week_start_row").takeUnless { wasNull() },
        targetWeekEndRow = getInt("target_week_end_row").takeUnless { wasNull() },
        targetWeekHeaderAddress = getString("target_week_header_address"),
        targetWeekHeaderValue = getString("target_week_header_value"),
        executionBoundaryColumn = getInt("execution_boundary_col").takeUnless { wasNull() },
        executionHeaderAddress = getString("execution_header_address"),
        executionHeaderValue = getString("execution_header_value"),
        matchingContractVersion = getString("matching_contract_version"),
        matchingModel = getString("matching_model"),
        matchingSourceSnapshot = getString("matching_source_snapshot"),
        matchingSourceHash = getString("matching_source_hash"),
        executionProjectionHash = getString("execution_projection_hash"),
        payloadHash = getString("payload_hash"),
        status = getString("status"),
        apiCalled = getBoolean("api_called"),
        createdAt = getObject("created_at", OffsetDateTime::class.java),
        finishedAt = getObject("finished_at", OffsetDateTime::class.java),
        detail = getString("detail"),
    )

    private fun java.sql.ResultSet.jsonValue(column: String): SheetValue? = getString(column)?.let {
        json.decodeFromString(SheetValue.serializer(), it)
    }

    private fun nullableJson(value: SheetValue?): Pair<IColumnType<*>, Any?> =
        TextColumnType() to value?.let { json.encodeToString(it) }

    private fun execute(statement: String, args: List<Pair<IColumnType<*>, Any?>>) {
        transaction().exec(statement, args, StatementType.UPDATE)
    }

    private fun <T> rows(
        statement: String,
        args: List<Pair<IColumnType<*>, Any?>>,
        transform: (java.sql.ResultSet) -> T,
    ): List<T> = transaction().exec(statement, args, StatementType.SELECT) { rs ->
        buildList { while (rs.next()) add(transform(rs)) }
    }.orEmpty()

    private fun transaction() = checkNotNull(TransactionManager.currentOrNull())
    private fun text(value: String) = TextColumnType() to value
    private fun nullableText(value: String?) = TextColumnType() to value
    private fun uuid(value: UUID) = UUIDColumnType() to value
    private fun nullableUuid(value: UUID?) = UUIDColumnType() to value
    private fun integer(value: Int) = IntegerColumnType() to value
    private fun nullableInteger(value: Int?) = IntegerColumnType() to value
    private fun long(value: Long) = LongColumnType() to value
    private fun nullableLong(value: Long?) = LongColumnType() to value
    private fun bool(value: Boolean) = BooleanColumnType() to value
    private fun timestamp(value: OffsetDateTime) = JavaOffsetDateTimeColumnType() to value
    private fun integerArray(values: List<Int>) = TextColumnType() to "{${values.joinToString(",")}}"
}

private data class SourceHeader(
    val programId: UUID,
    val programName: String,
    val sessionId: UUID,
    val status: String,
    val executionUpdatedAt: OffsetDateTime?,
    val weekNumber: Int,
    val workoutName: String,
)
