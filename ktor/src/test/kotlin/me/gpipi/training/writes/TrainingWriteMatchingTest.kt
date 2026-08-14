package me.gpipi.training.writes

import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith
import me.gpipi.ai.OpenRouterClient

class TrainingWriteMatchingTest {
    private val firstId = UUID.randomUUID()
    private val secondId = UUID.randomUUID()
    private val source = WriteSource(
        ownerUserId = "U-WRITE",
        programId = UUID.randomUUID(),
        programName = "M1",
        sessionId = UUID.randomUUID(),
        weekId = UUID.randomUUID(),
        sessionStatus = "COMPLETED",
        weekNumber = 3,
        workoutName = "Full Body 1",
        executionUpdatedAt = null,
        movements = listOf(
            movement(firstId, 1, "Barbell RDL"),
            movement(secondId, 2, "Hollow hold"),
        ),
    )
    private val candidate = WriteCandidateTab(
        key = "tab-101",
        googleSheetId = 101,
        title = "Full Body WO 1",
        startRow = 10,
        endRow = 20,
        weekHeaderAddress = "A10",
        weekHeaderValue = "Week 5",
        executionBoundaryColumn = 11,
        executionHeaderAddress = "K10",
        executionHeaderValue = "Eksekusi Week 5",
        prescriptionCells = listOf(
            WriteSheetCell("B14", 14, 2, "Romanian Deadlift"),
            WriteSheetCell("B15", 15, 2, "Hollow body hold"),
        ),
        executionLayout = emptyList(),
    )
    private val matcher = TrainingWriteMatchingService(mockk<OpenRouterClient>())

    @Test
    fun `validator rejects invented cell text and duplicate remote rows`() {
        assertFailsWith<IllegalArgumentException> {
            matcher.validate(
                source,
                listOf(candidate),
                WriteMatchOutput(
                    "tab-101",
                    listOf(
                        WriteMatchMovementOutput(firstId.toString(), "B14", "RDL normalized"),
                        WriteMatchMovementOutput(secondId.toString(), "B15", "Hollow body hold"),
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            matcher.validate(
                source,
                listOf(candidate),
                WriteMatchOutput(
                    "tab-101",
                    listOf(
                        WriteMatchMovementOutput(firstId.toString(), "B14", "Romanian Deadlift"),
                        WriteMatchMovementOutput(secondId.toString(), "B14", "Romanian Deadlift"),
                    ),
                ),
            )
        }
    }

    @Test
    fun `validator accepts exact one-to-one citations and unmatched movement`() {
        matcher.validate(
            source,
            listOf(candidate),
            WriteMatchOutput(
                "tab-101",
                listOf(
                    WriteMatchMovementOutput(firstId.toString(), "B14", "Romanian Deadlift"),
                    WriteMatchMovementOutput(secondId.toString(), null, null),
                ),
            ),
        )
    }

    private fun movement(id: UUID, position: Int, name: String) = WriteSourceMovement(
        performedExerciseId = id,
        prescriptionId = UUID.randomUUID(),
        position = position,
        groupLabel = "A",
        groupKind = "STRAIGHT_SET",
        exerciseName = name,
        executionType = "REPS",
        targetSets = "3",
        targetRest = null,
        targetReps = "8",
        targetLoad = null,
        targetRir = null,
        targetTempo = null,
        targetNote = null,
        sets = emptyList(),
    )
}
