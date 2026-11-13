package com.nuvio.app.features.livetv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Turns the saved Live TV settings into text and back. */
object LiveTvCodec {

    fun encodeSource(source: LiveSource): String = buildJsonObject {
        put("type", source.type.name)
        put("url", source.url)
        put("username", source.username)
        put("password", source.password)
        put("guideUrl", source.guideUrl)
    }.toString()

    fun decodeSource(text: String?): LiveSource? {
        if (text.isNullOrBlank()) return null
        val json = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val type = LiveSourceType.entries.firstOrNull { it.name == json.text("type") } ?: return null
        val url = json.text("url").trim()
        if (url.isEmpty()) return null
        return LiveSource(
            type = type,
            url = url,
            username = json.text("username"),
            password = json.text("password"),
            guideUrl = json.text("guideUrl"),
        )
    }

    fun encodeIds(ids: Collection<String>): String = buildJsonArray {
        ids.forEach { add(JsonPrimitive(it)) }
    }.toString()

    /** Identifiers in saved order, without blanks or repeats. */
    fun decodeIds(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val json = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonArray ?: return emptyList()
        return json.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
            .filter(String::isNotBlank)
            .distinct()
    }

    private fun JsonObject.text(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content.orEmpty()
}
