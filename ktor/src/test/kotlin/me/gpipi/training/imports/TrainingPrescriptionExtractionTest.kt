package me.gpipi.training.imports

import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import me.gpipi.ai.OpenRouterClient
import me.gpipi.training.google.SheetCell
import me.gpipi.training.google.SheetTabGrid

class TrainingPrescriptionExtractionTest {
    private val service = TrainingPrescriptionExtractionService(mockk<OpenRouterClient>())

    @Test
    fun `sanitized payload excludes copied execution values and every other week`() {
        val grid = SheetTabGrid(
            sheetId = 12,
            title = "Full Body 1",
            position = 1,
            rowCount = 100,
            columnCount = 16,
            cells = listOf(
                SheetCell(70, 1, "A70", "Week 4"),
                SheetCell(72, 1, "A72", "Week 5"),
                SheetCell(73, 1, "A73", "STRAIGHT SET"),
                SheetCell(74, 1, "A74", "DB romanian deadlift"),
                SheetCell(74, 3, "C74", "3"),
                SheetCell(74, 5, "E74", "8 each"),
                SheetCell(72, 11, "K72", "Eksekusi Week 5"),
                SheetCell(74, 11, "K74", "10"),
                SheetCell(74, 12, "L74", "7 kg"),
                SheetCell(92, 1, "A92", "Week 6"),
            ),
        )

        val payload = service.buildPayload(grid, 5, 72, 91, 11)
        val addresses = payload.rows.flatMap(SheetRowPayload::cells).map(SheetCellPayload::address)

        assertEquals(listOf("A72", "A73", "A74", "C74", "E74"), addresses)
        assertFalse(addresses.any { it.startsWith("K") || it.startsWith("L") })
        assertFalse(payload.rows.flatMap(SheetRowPayload::cells).any { it.display == "10" || it.display == "7 kg" })
    }

    @Test
    fun `server rejects normalized or invented values even after schema success`() {
        val payload = PrescriptionPayload(
            contractVersion = TRAINING_EXTRACTION_CONTRACT_VERSION,
            selectedWeekNumber = 5,
            selectedRange = SelectedRangePayload("A1:J3", 1, 3),
            prescriptionColumns = PrescriptionColumnsPayload("A", "J"),
            rows = listOf(
                SheetRowPayload(1, listOf(SheetCellPayload("A1", 1, "STRAIGHT SET"))),
                SheetRowPayload(2, listOf(
                    SheetCellPayload("A2", 1, "DB romanian deadlift"),
                    SheetCellPayload("D2", 4, "45-60sec"),
                )),
            ),
            mergedRanges = emptyList(),
        )
        val invented = TrainingPrescriptionExtraction(listOf(
            ExtractedTrainingGroup(
                label = "STRAIGHT SET",
                labelAddress = "A1",
                kind = "STRAIGHT_SET",
                prescriptions = listOf(
                    ExtractedTrainingPrescription(
                        movement = "DB Romanian deadlift",
                        movementAddress = "A2",
                        rest = "45–60 sec",
                        sourceCells = ExtractedSourceCells(movement = "A2", rest = "D2"),
                    ),
                ),
            ),
        ))

        assertFailsWith<TrainingExtractionException> { service.validate(invented, payload) }
    }
}
