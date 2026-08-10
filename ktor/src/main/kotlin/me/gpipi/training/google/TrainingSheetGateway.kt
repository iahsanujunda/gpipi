package me.gpipi.training.google

data class SheetCell(
    val row: Int,
    val column: Int,
    val address: String,
    val display: String,
)

data class SheetMergedRange(
    val startRow: Int,
    val endRow: Int,
    val startColumn: Int,
    val endColumn: Int,
    val a1: String,
)

data class SheetTabGrid(
    val sheetId: Long,
    val title: String,
    val position: Int,
    val rowCount: Int,
    val columnCount: Int,
    val cells: List<SheetCell>,
    val mergedRanges: List<SheetMergedRange> = emptyList(),
)

data class SheetDiscovery(
    val spreadsheetTitle: String,
    val tabs: List<SheetTabGrid>,
) {
    val weekNumbers: List<Int>
        get() = tabs.flatMap { it.weekLabels().keys }.distinct().sorted()
}

data class WeekRangeProposal(
    val sheetId: Long,
    val tabTitle: String,
    val tabPosition: Int,
    val present: Boolean,
    val startRow: Int?,
    val endRow: Int?,
    val executionBoundaryColumn: Int?,
    val executionHeaderAddress: String?,
    val executionHeaderValue: String?,
    val boundaryAmbiguous: Boolean,
)

interface TrainingSheetGateway {
    suspend fun discover(accessToken: String, spreadsheetId: String): SheetDiscovery

    suspend fun readSelectedRange(
        accessToken: String,
        spreadsheetId: String,
        sheetId: Long,
        tabTitle: String,
        startRow: Int,
        endRow: Int,
        executionBoundaryColumn: Int,
    ): SheetTabGrid
}

private val weekLabel = Regex("(?i)\\b(?:week|minggu)\\s*[-:]?\\s*(\\d+)\\b")
private val executionHeader = Regex("(?i)^(?:eksekusi|realisasi)(?:\\b.*)?$")

fun SheetTabGrid.weekLabels(): Map<Int, List<SheetCell>> = cells.mapNotNull { cell ->
    val number = weekLabel.find(cell.display.trim())?.groupValues?.get(1)?.toIntOrNull()
    number?.let { it to cell }
}.groupBy({ it.first }, { it.second })

fun SheetDiscovery.proposalsFor(weekNumber: Int): List<WeekRangeProposal> = tabs.map { tab ->
    val labels = tab.weekLabels()
    val selectedRows = labels[weekNumber].orEmpty().map(SheetCell::row)
    if (selectedRows.isEmpty()) {
        WeekRangeProposal(
            sheetId = tab.sheetId,
            tabTitle = tab.title,
            tabPosition = tab.position,
            present = false,
            startRow = null,
            endRow = null,
            executionBoundaryColumn = null,
            executionHeaderAddress = null,
            executionHeaderValue = null,
            boundaryAmbiguous = false,
        )
    } else {
        val start = selectedRows.min()
        val nextWeekRow = labels
            .filterKeys { it != weekNumber }
            .values.flatten().map(SheetCell::row)
            .filter { it > start }.minOrNull()
        val lastPopulatedRow = tab.cells.maxOfOrNull(SheetCell::row) ?: start
        val end = (nextWeekRow?.minus(1) ?: lastPopulatedRow).coerceAtLeast(start)
        val headers = tab.cells.filter {
            it.row in start..end && executionHeader.matches(it.display.trim())
        }.sortedWith(compareBy(SheetCell::row, SheetCell::column))
        val boundary = headers.firstOrNull()
        WeekRangeProposal(
            sheetId = tab.sheetId,
            tabTitle = tab.title,
            tabPosition = tab.position,
            present = true,
            startRow = start,
            endRow = end,
            executionBoundaryColumn = boundary?.column,
            executionHeaderAddress = boundary?.address,
            executionHeaderValue = boundary?.display,
            boundaryAmbiguous = headers.size != 1,
        )
    }
}

fun columnName(column: Int): String {
    require(column >= 1)
    var value = column
    return buildString {
        while (value > 0) {
            value--
            append(('A'.code + value % 26).toChar())
            value /= 26
        }
    }.reversed()
}

fun a1Address(row: Int, column: Int): String = "${columnName(column)}$row"
