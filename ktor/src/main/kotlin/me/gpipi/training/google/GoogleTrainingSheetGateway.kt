package me.gpipi.training.google

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class GoogleTrainingSheetGateway(
    private val http: HttpClient,
    apiBaseUrl: String = "https://sheets.googleapis.com/v4",
) : TrainingSheetGateway {
    private val base = apiBaseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun discover(accessToken: String, spreadsheetId: String): SheetDiscovery {
        val root = getSpreadsheet(
            accessToken = accessToken,
            spreadsheetId = spreadsheetId,
            includeGridData = true,
            fields = DISCOVERY_FIELDS,
        )
        return SheetDiscovery(
            spreadsheetTitle = root.objectAt("properties")?.string("title")
                ?: throw GoogleIntegrationException("The selected spreadsheet has no title."),
            tabs = root.array("sheets").mapIndexed { index, element ->
                parseTab(element.jsonObject, index)
            }.sortedBy(SheetTabGrid::position),
        )
    }

    override suspend fun readSelectedRange(
        accessToken: String,
        spreadsheetId: String,
        sheetId: Long,
        tabTitle: String,
        startRow: Int,
        endRow: Int,
        executionBoundaryColumn: Int,
    ): SheetTabGrid {
        require(startRow >= 1 && endRow >= startRow)
        require(executionBoundaryColumn >= 1)
        val escapedTitle = tabTitle.replace("'", "''")
        val root = getSpreadsheet(
            accessToken = accessToken,
            spreadsheetId = spreadsheetId,
            includeGridData = true,
            fields = RANGE_FIELDS,
            range = "'$escapedTitle'!${startRow}:$endRow",
        )
        val tab = root.array("sheets").firstOrNull()?.jsonObject
            ?: throw GoogleIntegrationException("Google returned no data for the selected workout range.")
        val parsed = parseTab(tab, 0)
        if (parsed.sheetId != sheetId) {
            throw GoogleIntegrationException("The selected Google Sheet tab changed. Choose the week again.")
        }
        return parsed
    }

    override suspend fun batchUpdateValues(
        accessToken: String,
        spreadsheetId: String,
        sheetId: Long,
        updates: List<SheetValueUpdate>,
    ) {
        if (updates.isEmpty()) return
        val body = buildJsonObject {
            putJsonArray("requests") {
                updates.sortedWith(compareBy(SheetValueUpdate::row, SheetValueUpdate::column)).forEach { update ->
                    add(buildJsonObject {
                        putJsonObject("updateCells") {
                            putJsonObject("range") {
                                put("sheetId", sheetId)
                                put("startRowIndex", update.row - 1)
                                put("endRowIndex", update.row)
                                put("startColumnIndex", update.column - 1)
                                put("endColumnIndex", update.column)
                            }
                            putJsonArray("rows") {
                                add(buildJsonObject {
                                    putJsonArray("values") {
                                        add(buildJsonObject {
                                            update.value?.let { value ->
                                                putJsonObject("userEnteredValue") {
                                                    when (value.type) {
                                                        "NUMBER" -> put("numberValue", value.value.toDouble())
                                                        "BOOLEAN" -> put("boolValue", value.value.toBooleanStrict())
                                                        "FORMULA" -> put("formulaValue", value.value)
                                                        else -> put("stringValue", value.value)
                                                    }
                                                }
                                            }
                                        })
                                    }
                                })
                            }
                            put("fields", "userEnteredValue")
                        }
                    })
                }
            }
        }
        val response = try {
            http.post("$base/spreadsheets/$spreadsheetId:batchUpdate") {
                bearerAuth(accessToken)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        } catch (ex: Exception) {
            throw GoogleIntegrationException("Google Sheets could not be reached while writing.", ex)
        }
        if (!response.status.isSuccess()) {
            throw GoogleSheetWriteRejectedException("Google Sheets rejected the write (${response.status.value}).")
        }
    }

    private suspend fun getSpreadsheet(
        accessToken: String,
        spreadsheetId: String,
        includeGridData: Boolean,
        fields: String,
        range: String? = null,
    ): JsonObject {
        val response = try {
            http.get("$base/spreadsheets/$spreadsheetId") {
                bearerAuth(accessToken)
                parameter("includeGridData", includeGridData)
                parameter("fields", fields)
                range?.let { parameter("ranges", it) }
            }
        } catch (ex: Exception) {
            throw GoogleIntegrationException("Google Sheets could not be reached.", ex)
        }
        if (!response.status.isSuccess()) {
            throw GoogleIntegrationException(
                "Google Sheets rejected the read (${response.status.value}). Reconnect or choose the file again.",
            )
        }
        return try {
            json.parseToJsonElement(response.bodyAsText()).jsonObject
        } catch (ex: Exception) {
            throw GoogleIntegrationException("Google Sheets returned an unreadable response.", ex)
        }
    }

    private fun parseTab(tab: JsonObject, fallbackPosition: Int): SheetTabGrid {
        val properties = tab.objectAt("properties") ?: JsonObject(emptyMap())
        val title = properties.string("title") ?: "Untitled tab"
        val data = tab.array("data")
        val cells = buildList {
            data.forEach { gridElement ->
                val grid = gridElement.jsonObject
                val startRow = grid.int("startRow") ?: 0
                val startColumn = grid.int("startColumn") ?: 0
                grid.array("rowData").forEachIndexed { rowOffset, rowElement ->
                    rowElement.jsonObject.array("values").forEachIndexed { columnOffset, cellElement ->
                        val cell = cellElement.jsonObject
                        val entered = cell.objectAt("userEnteredValue")?.toSheetValue()
                        val display = cell.string("formattedValue").orEmpty()
                        if (display.isNotEmpty() || entered != null) {
                            val row = startRow + rowOffset + 1
                            val column = startColumn + columnOffset + 1
                            add(SheetCell(row, column, a1Address(row, column), display, entered))
                        }
                    }
                }
            }
        }
        val merges = tab.array("merges").mapNotNull { element ->
            val range = element.jsonObject
            val startRow = (range.int("startRowIndex") ?: return@mapNotNull null) + 1
            val endRow = range.int("endRowIndex") ?: return@mapNotNull null
            val startColumn = (range.int("startColumnIndex") ?: return@mapNotNull null) + 1
            val endColumn = range.int("endColumnIndex") ?: return@mapNotNull null
            SheetMergedRange(
                startRow = startRow,
                endRow = endRow,
                startColumn = startColumn,
                endColumn = endColumn,
                a1 = "${a1Address(startRow, startColumn)}:${a1Address(endRow, endColumn)}",
            )
        }
        val gridProperties = properties.objectAt("gridProperties")
        return SheetTabGrid(
            sheetId = properties.long("sheetId")
                ?: throw GoogleIntegrationException("A Google Sheet tab had no stable numeric ID."),
            title = title,
            position = (properties.int("index") ?: fallbackPosition) + 1,
            rowCount = gridProperties?.int("rowCount") ?: (cells.maxOfOrNull(SheetCell::row) ?: 0),
            columnCount = gridProperties?.int("columnCount") ?: (cells.maxOfOrNull(SheetCell::column) ?: 0),
            cells = cells.sortedWith(compareBy(SheetCell::row, SheetCell::column)),
            mergedRanges = merges,
        )
    }

    private fun JsonObject.array(name: String): JsonArray = get(name) as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.objectAt(name: String): JsonObject? = get(name) as? JsonObject
    private fun JsonObject.string(name: String): String? = get(name)?.jsonPrimitive?.content
    private fun JsonObject.int(name: String): Int? = get(name)?.jsonPrimitive?.intOrNull
    private fun JsonObject.long(name: String): Long? = get(name)?.jsonPrimitive?.longOrNull
    private fun JsonObject.toSheetValue(): SheetValue? = when {
        get("numberValue")?.jsonPrimitive?.doubleOrNull != null ->
            SheetValue("NUMBER", getValue("numberValue").jsonPrimitive.content)
        get("stringValue") != null -> SheetValue("STRING", getValue("stringValue").jsonPrimitive.content)
        get("boolValue")?.jsonPrimitive?.booleanOrNull != null ->
            SheetValue("BOOLEAN", getValue("boolValue").jsonPrimitive.content)
        get("formulaValue") != null -> SheetValue("FORMULA", getValue("formulaValue").jsonPrimitive.content)
        else -> null
    }

    private companion object {
        const val DISCOVERY_FIELDS = "properties(title),sheets(properties(sheetId,title,index,gridProperties(rowCount,columnCount)),data(startRow,startColumn,rowData(values(formattedValue))))"
        const val RANGE_FIELDS = "sheets(properties(sheetId,title,index,gridProperties(rowCount,columnCount)),merges,data(startRow,startColumn,rowData(values(formattedValue,userEnteredValue))))"
    }
}
