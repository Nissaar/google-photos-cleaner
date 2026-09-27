package xyz.photocleaner.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builds the positional argument arrays Google's RPCs take.
 *
 * Built as JSON values, never by gluing strings: an album name is user text, and
 * hand-written escaping missed control characters, which produced invalid JSON.
 */
internal object RpcArgs {

    fun of(vararg values: Any?): JsonArray = JsonArray(values.map(::element))

    private fun element(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map(::element))
        // Loud on purpose. Silently sending null to a mutation RPC could change what
        // it does to someone's library.
        else -> throw IllegalArgumentException("Unsupported RPC argument: ${value::class}")
    }
}
