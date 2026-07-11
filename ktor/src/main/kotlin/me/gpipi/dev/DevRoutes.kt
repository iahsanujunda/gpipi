package me.gpipi.dev

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import me.gpipi.extraction.ExtractionException
import me.gpipi.extraction.OpenRouterClient

fun Route.devRoutes(orClient: OpenRouterClient) {
    post("/dev/extract") {
        val text = call.receiveText()
        if (text.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ExtractError("empty body"))
        try {
            call.respond(orClient.extract(text))
        } catch (ex: ExtractionException) {
            call.respond(HttpStatusCode.UnprocessableEntity, ExtractError(ex.message))
        }
    }
}

@Serializable data class ExtractError(val error: String?)
