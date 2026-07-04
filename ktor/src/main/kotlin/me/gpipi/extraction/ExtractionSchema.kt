package me.gpipi.extraction

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

val EXTRACTION_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("amount")     { put("type", "integer") }
        putJsonObject("currency")   { put("type", "string"); putJsonArray("enum") { add("JPY") } }
        putJsonObject("merchant")   { putJsonArray("type") { add("string"); add("null") } }
        putJsonObject("category")   { put("type", "string") }
        putJsonObject("confidence") { put("type", "number"); put("minimum", 0); put("maximum", 1) }
        putJsonObject("note")       { putJsonArray("type") { add("string"); add("null") } }
    }
    putJsonArray("required") { add("amount"); add("currency"); add("category"); add("confidence") }
    put("additionalProperties", false)
}