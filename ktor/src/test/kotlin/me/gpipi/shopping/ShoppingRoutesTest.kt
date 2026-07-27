package me.gpipi.shopping

import io.ktor.client.call.body
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.auth.authenticate
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.gpipi.UserSession
import me.gpipi.configureSecurity
import me.gpipi.configureSerialization

class ShoppingRoutesTest {
    private val service = mockk<ShoppingService>()
    private val clock = Clock.fixed(
        Instant.parse("2026-07-27T00:00:00Z"),
        ZoneOffset.UTC,
    )

    private fun ApplicationTestBuilder.boot() {
        environment {
            config = MapApplicationConfig(
                "session.signKey" to "test-session-key",
            )
        }
        application {
            configureSecurity(clock)
            configureSerialization()
            routing {
                post("/test/login") {
                    call.sessions.set(
                        UserSession("U-web", clock.instant().epochSecond),
                    )
                    call.respond(HttpStatusCode.OK)
                }
                authenticate("auth-session") {
                    shoppingApiRoutes(service)
                }
            }
        }
    }

    private fun ApplicationTestBuilder.apiClient() = createClient {
        install(HttpCookies)
        install(ContentNegotiation) { json() }
    }

    private fun row(
        status: String = "PENDING",
        currentMutationId: UUID = UUID.randomUUID(),
    ) = ShoppingItemRow(
        id = UUID.randomUUID(),
        inboundMessageId = UUID.randomUUID(),
        item = "milk",
        quantity = "1 carton",
        note = "low fat",
        status = status,
        addedBy = "U-add",
        addedAt = OffsetDateTime.parse("2026-07-27T00:00:00Z"),
        boughtBy = null,
        boughtAt = null,
        removedBy = null,
        removedAt = null,
        currentMutationId = currentMutationId,
    )

    @Test
    fun `GET lists shopping items for an authenticated household member`() = testApplication {
        val row = row()
        coEvery { service.listAll() } returns listOf(row)
        boot()
        val client = apiClient()
        client.post("/test/login")

        val response = client.get("/api/shopping/items")

        assertEquals(HttpStatusCode.OK, response.status)
        val item = response.body<List<ShoppingItemResponse>>().single()
        assertEquals(row.id.toString(), item.id)
        assertEquals("milk", item.item)
        assertEquals("PENDING", item.status)
        assertEquals(row.currentMutationId.toString(), item.currentMutationId)
    }

    @Test
    fun `PUT edit forwards the authenticated actor and current item version`() = testApplication {
        val row = row()
        val request = EditShoppingItemRequest(
            item = "whole milk",
            quantity = "2 cartons",
            note = "for breakfast",
            currentMutationId = row.currentMutationId.toString(),
        )
        coEvery {
            service.editItem(
                row.id,
                row.currentMutationId,
                "U-web",
                ShoppingDraftItemInput(
                    "whole milk",
                    "2 cartons",
                    "for breakfast",
                ),
            )
        } returns ShoppingItemMutationResult.Updated(
            row.copy(
                item = request.item,
                quantity = request.quantity,
                note = request.note,
                currentMutationId = UUID.randomUUID(),
            ),
        )
        boot()
        val client = apiClient()
        client.post("/test/login")

        val response = client.put("/api/shopping/items/${row.id}") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("whole milk", response.body<ShoppingItemResponse>().item)
        coVerify(exactly = 1) {
            service.editItem(
                row.id,
                row.currentMutationId,
                "U-web",
                any(),
            )
        }
    }

    @Test
    fun `PUT remove maps a stale version to conflict`() = testApplication {
        val row = row()
        coEvery {
            service.removeItem(row.id, row.currentMutationId, "U-web")
        } returns ShoppingItemMutationResult.Conflict
        boot()
        val client = apiClient()
        client.post("/test/login")

        val response = client.put("/api/shopping/items/${row.id}/remove") {
            contentType(ContentType.Application.Json)
            setBody(VersionedShoppingItemRequest(row.currentMutationId.toString()))
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(
            "This shopping item changed since the page was loaded. Refresh and try again.",
            response.body<JsonObject>()["message"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `PUT restore returns the restored item`() = testApplication {
        val removed = row(status = "REMOVED")
        val restored = removed.copy(
            status = "PENDING",
            currentMutationId = UUID.randomUUID(),
        )
        coEvery {
            service.restoreItem(
                removed.id,
                removed.currentMutationId,
                "U-web",
            )
        } returns ShoppingItemMutationResult.Updated(restored)
        boot()
        val client = apiClient()
        client.post("/test/login")

        val response = client.put("/api/shopping/items/${removed.id}/restore") {
            contentType(ContentType.Application.Json)
            setBody(
                VersionedShoppingItemRequest(
                    removed.currentMutationId.toString(),
                ),
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("PENDING", response.body<ShoppingItemResponse>().status)
    }

    @Test
    fun `PUT rejects malformed ids before calling the service`() = testApplication {
        boot()
        val client = apiClient()
        client.post("/test/login")

        val response = client.put("/api/shopping/items/not-a-uuid/remove") {
            contentType(ContentType.Application.Json)
            setBody(VersionedShoppingItemRequest(UUID.randomUUID().toString()))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        coVerify(exactly = 0) { service.removeItem(any(), any(), any()) }
    }
}
