package me.gpipi.training.google

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrainingSheetGatewayTest {
    @Test
    fun `week discovery proposes only the selected week range and execution boundary`() {
        val workout = SheetTabGrid(
            sheetId = 123,
            title = "Full Body 1",
            position = 1,
            rowCount = 120,
            columnCount = 18,
            cells = listOf(
                SheetCell(10, 1, "A10", "Week 4"),
                SheetCell(11, 1, "A11", "Goblet squat"),
                SheetCell(30, 1, "A30", "WEEK 5"),
                SheetCell(30, 11, "K30", "Eksekusi Week 5"),
                SheetCell(31, 1, "A31", "DB romanian deadlift"),
                SheetCell(31, 11, "K31", "10"),
                SheetCell(50, 1, "A50", "Minggu 6"),
            ),
        )
        val notes = SheetTabGrid(
            sheetId = 456,
            title = "Macro Check In",
            position = 2,
            rowCount = 20,
            columnCount = 8,
            cells = listOf(SheetCell(1, 1, "A1", "Body weight")),
        )
        val discovery = SheetDiscovery("JUNDA – M1", listOf(workout, notes))

        assertEquals(listOf(4, 5, 6), discovery.weekNumbers)
        val proposals = discovery.proposalsFor(5)
        proposals[0].let {
            assertTrue(it.present)
            assertEquals(30, it.startRow)
            assertEquals(49, it.endRow)
            assertEquals(11, it.executionBoundaryColumn)
            assertEquals("K30", it.executionHeaderAddress)
            assertFalse(it.boundaryAmbiguous)
        }
        proposals[1].let {
            assertFalse(it.present)
            assertNull(it.startRow)
            assertNull(it.executionBoundaryColumn)
        }
    }
}
