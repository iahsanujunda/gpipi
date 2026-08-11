package me.gpipi.training.google

import io.ktor.http.HttpHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class GoogleDriveSheetClientTest {
    @Test
    fun `lists only native Sheets in recently modified order with search and pagination`() = testApplication {
        application {
            routing {
                get("/drive/v3/files") {
                    assertEquals("Bearer access-token", call.request.headers[HttpHeaders.Authorization])
                    assertEquals("modifiedTime desc,name", call.request.queryParameters["orderBy"])
                    assertEquals("next-page", call.request.queryParameters["pageToken"])
                    assertContains(
                        call.request.queryParameters["q"].orEmpty(),
                        "mimeType = 'application/vnd.google-apps.spreadsheet'",
                    )
                    assertContains(call.request.queryParameters["q"].orEmpty(), "name contains 'Junda'")
                    call.respondText(
                        """{
                            "nextPageToken": "another-page",
                            "files": [{
                                "id": "sheet-id-12345",
                                "name": "JUNDA – M1",
                                "modifiedTime": "2026-08-08T10:30:00Z"
                            }]
                        }""",
                    )
                }
            }
        }

        val page = GoogleDriveSheetClient(createClient {}, "/drive/v3")
            .listSheets("access-token", "Junda", "next-page")

        assertEquals("another-page", page.nextPageToken)
        assertEquals(1, page.sheets.size)
        assertEquals("sheet-id-12345", page.sheets.single().spreadsheetId)
        assertEquals("JUNDA – M1", page.sheets.single().name)
    }
}
