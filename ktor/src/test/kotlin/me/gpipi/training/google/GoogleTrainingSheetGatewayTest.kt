package me.gpipi.training.google

import io.ktor.http.HttpHeaders
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class GoogleTrainingSheetGatewayTest {
    @Test
    fun `reads typed values and writes only user-entered values`() = testApplication {
        application {
            routing {
                get("/v4/spreadsheets/sheet-123") {
                    assertEquals("Bearer access-token", call.request.headers[HttpHeaders.Authorization])
                    call.respondText(
                        """{
                          "sheets": [{
                            "properties": {
                              "sheetId": 101,
                              "title": "Full Body 1",
                              "index": 0,
                              "gridProperties": {"rowCount": 40, "columnCount": 20}
                            },
                            "data": [{
                              "startRow": 13,
                              "startColumn": 10,
                              "rowData": [{"values": [
                                {"formattedValue": "10", "userEnteredValue": {"numberValue": 10}},
                                {"formattedValue": "copied", "userEnteredValue": {"stringValue": "copied"}}
                              ]}]
                            }]
                          }]
                        }""".trimIndent(),
                    )
                }
                post("/v4/spreadsheets/sheet-123:batchUpdate") {
                    assertEquals("Bearer access-token", call.request.headers[HttpHeaders.Authorization])
                    val body = call.receiveText()
                    assertContains(body, "\"numberValue\":8.0")
                    assertContains(body, "\"fields\":\"userEnteredValue\"")
                    assertContains(body, "\"startRowIndex\":13")
                    call.respondText("{}")
                }
            }
        }

        val gateway = GoogleTrainingSheetGateway(createClient {}, "/v4")
        val grid = gateway.readSelectedRange("access-token", "sheet-123", 101, "Full Body 1", 14, 20, 11)

        assertEquals(SheetValue("NUMBER", "10"), grid.cells.single { it.address == "K14" }.userEnteredValue)
        assertEquals(SheetValue("STRING", "copied"), grid.cells.single { it.address == "L14" }.userEnteredValue)

        gateway.batchUpdateValues(
            "access-token",
            "sheet-123",
            101,
            listOf(
                SheetValueUpdate(14, 11, SheetValue("NUMBER", "8")),
                SheetValueUpdate(14, 12, null),
            ),
        )
    }
}
