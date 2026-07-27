package me.gpipi.shopping

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.util.UUID
import kotlinx.serialization.Serializable
import me.gpipi.UserSession

@Serializable
data class EditShoppingItemRequest(
    val item: String,
    val quantity: String? = null,
    val note: String? = null,
    val currentMutationId: String,
)

@Serializable
data class VersionedShoppingItemRequest(
    val currentMutationId: String,
)

@Serializable
data class ShoppingItemResponse(
    val id: String,
    val item: String,
    val quantity: String?,
    val note: String?,
    val status: String,
    val addedBy: String,
    val addedAt: String,
    val boughtBy: String?,
    val boughtAt: String?,
    val removedBy: String?,
    val removedAt: String?,
    val currentMutationId: String,
)

@Serializable
private data class ShoppingApiError(val message: String)

fun Route.shoppingApiRoutes(service: ShoppingService) {
    route("/api/shopping/items") {
        get {
            call.respond(service.listAll().map(ShoppingItemRow::toResponse))
        }

        put("/{id}") {
            val id = call.requireItemId() ?: return@put
            val actorId = call.requireActorId() ?: return@put
            val request = call.receive<EditShoppingItemRequest>()
            val expectedMutationId = call.requireMutationId(request.currentMutationId)
                ?: return@put

            call.respondShoppingResult(
                service.editItem(
                    id = id,
                    expectedMutationId = expectedMutationId,
                    actorId = actorId,
                    input = ShoppingDraftItemInput(
                        item = request.item,
                        quantity = request.quantity,
                        note = request.note,
                    ),
                ),
            )
        }

        put("/{id}/remove") {
            val id = call.requireItemId() ?: return@put
            val actorId = call.requireActorId() ?: return@put
            val request = call.receive<VersionedShoppingItemRequest>()
            val expectedMutationId = call.requireMutationId(request.currentMutationId)
                ?: return@put

            call.respondShoppingResult(
                service.removeItem(id, expectedMutationId, actorId),
            )
        }

        put("/{id}/restore") {
            val id = call.requireItemId() ?: return@put
            val actorId = call.requireActorId() ?: return@put
            val request = call.receive<VersionedShoppingItemRequest>()
            val expectedMutationId = call.requireMutationId(request.currentMutationId)
                ?: return@put

            call.respondShoppingResult(
                service.restoreItem(id, expectedMutationId, actorId),
            )
        }
    }
}

private suspend fun ApplicationCall.requireItemId(): UUID? {
    val id = parameters["id"].toUuidOrNull()
    if (id == null) {
        respond(
            HttpStatusCode.BadRequest,
            ShoppingApiError("'id' must be a UUID."),
        )
    }
    return id
}

private suspend fun ApplicationCall.requireMutationId(value: String): UUID? {
    val id = value.toUuidOrNull()
    if (id == null) {
        respond(
            HttpStatusCode.BadRequest,
            ShoppingApiError("'currentMutationId' must be a UUID."),
        )
    }
    return id
}

private suspend fun ApplicationCall.requireActorId(): String? {
    val actorId = principal<UserSession>()?.userId
    if (actorId == null) respond(HttpStatusCode.Unauthorized)
    return actorId
}

private suspend fun ApplicationCall.respondShoppingResult(
    result: ShoppingItemMutationResult,
) {
    when (result) {
        is ShoppingItemMutationResult.Updated ->
            respond(HttpStatusCode.OK, result.item.toResponse())

        ShoppingItemMutationResult.NotFound ->
            respond(
                HttpStatusCode.NotFound,
                ShoppingApiError("Shopping item not found."),
            )

        ShoppingItemMutationResult.Conflict ->
            respond(
                HttpStatusCode.Conflict,
                ShoppingApiError(
                    "This shopping item changed since the page was loaded. Refresh and try again.",
                ),
            )

        ShoppingItemMutationResult.DuplicatePendingItem ->
            respond(
                HttpStatusCode.Conflict,
                ShoppingApiError("An identical item is already active."),
            )

        is ShoppingItemMutationResult.Invalid ->
            respond(
                HttpStatusCode.BadRequest,
                ShoppingApiError(result.message),
            )
    }
}

private fun ShoppingItemRow.toResponse() =
    ShoppingItemResponse(
        id = id.toString(),
        item = item,
        quantity = quantity,
        note = note,
        status = status,
        addedBy = addedBy,
        addedAt = addedAt.toString(),
        boughtBy = boughtBy,
        boughtAt = boughtAt?.toString(),
        removedBy = removedBy,
        removedAt = removedAt?.toString(),
        currentMutationId = currentMutationId.toString(),
    )

private fun String?.toUuidOrNull(): UUID? =
    try {
        this?.let(UUID::fromString)
    } catch (_: IllegalArgumentException) {
        null
    }
