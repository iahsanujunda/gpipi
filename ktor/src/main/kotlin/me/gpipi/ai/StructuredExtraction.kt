package me.gpipi.ai

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal val extractionJson = Json { ignoreUnknownKeys = true }

data class ExtractionSpec<T>(
    val name: String,
    val systemPrompt: String,
    val schema: JsonObject,
    val deserializer: DeserializationStrategy<T>,
)

data class ExtractionOutcome<T>(
    val value: T,
    val model: String,
)

suspend fun <T> OpenRouterClient.extractStructured(
    spec: ExtractionSpec<T>,
    userMessage: String,
    wrap: (String, Throwable?) -> Exception,
): ExtractionOutcome<T> {
    val completion = try {
        chat(
            userMessage = userMessage,
            systemPrompt = spec.systemPrompt,
            schema = spec.schema,
            schemaName = spec.name,
        )
    } catch (ex: AiException) {
        throw wrap("AI call failed: ${ex.message}", ex)
    }

    val value = try {
        extractionJson.decodeFromString(spec.deserializer, completion.content)
    } catch (ex: SerializationException) {
        throw wrap("Extraction didn't match schema", ex)
    }

    return ExtractionOutcome(value, completion.model)
}
