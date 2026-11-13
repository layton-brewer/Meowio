package com.nuvio.app.features.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One title the assistant suggests. */
data class AiPick(
    val title: String,
    val year: Int?,
    val isSeries: Boolean,
    val reason: String
)

enum class AiFailure { NO_KEY, BAD_KEY, RATE_LIMITED, DECLINED, NETWORK, UNEXPECTED }

sealed interface AiResult {
    data class Picks(val picks: List<AiPick>) : AiResult
    data class Failure(val kind: AiFailure, val detail: String? = null) : AiResult
}

/** What the assistant may know about the viewer. Titles only, nothing that identifies them. */
data class AiViewerContext(
    val recentlyWatched: List<String> = emptyList(),
    val library: List<String> = emptyList()
)

/**
 * Builds the request for, and reads the answer from, the Claude Messages API.
 *
 * The app ships without a key. The viewer enters their own, it stays on the device, and it is
 * only ever sent to [ENDPOINT]. This file is shared by the TV and phone apps and only the
 * package line differs, so keep it free of platform code.
 */
object AiAssistant {
    const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    const val DEFAULT_MODEL = "claude-opus-5-5"
    const val MAX_PICKS = 8
    private const val API_VERSION = "2026-10-21"
    private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
    private const val MAX_CONTEXT_TITLES = 25
    private const val MAX_PROMPT_CHARS = 600

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun headers(apiKey: String): Map<String, String> = mapOf(
        "x-api-key" to apiKey.trim(),
        "anthropic-version" to API_VERSION,
        "anthropic-beta" to FALLBACK_BETA,
        "content-type" to "application/json"
    )

    fun requestBody(prompt: String, context: AiViewerContext, model: String = DEFAULT_MODEL): String =
        buildJsonObject {
            put("model", model.trim().ifEmpty { DEFAULT_MODEL })
            put("max_tokens", 8000)
            // If the request is declined, let the service retry it on its standard fallback model.
            put("fallbacks", "default")
            put("system", SYSTEM_PROMPT)
            putJsonObject("output_config") {
                put("effort", "low")
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("schema", PICKS_SCHEMA)
                }
            }
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", userMessage(prompt, context))
                    }
                )
            }
        }.toString()

    internal fun userMessage(prompt: String, context: AiViewerContext): String = buildString {
        append("Request: ")
        append(prompt.trim().take(MAX_PROMPT_CHARS))
        val watched = context.recentlyWatched.cleanTitles()
        if (watched.isNotEmpty()) {
            append("\n\nRecently watched (do not suggest these): ")
            append(watched.joinToString("; "))
        }
        val saved = context.library.cleanTitles().filterNot { it in watched }
        if (saved.isNotEmpty()) {
            append("\n\nSaved to their library: ")
            append(saved.joinToString("; "))
        }
    }

    private fun List<String>.cleanTitles(): List<String> =
        map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_CONTEXT_TITLES)

    /** Turns an HTTP status and body into picks or a reason the viewer can act on. */
    fun parseResponse(status: Int, body: String): AiResult {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        if (status !in 200..299) {
            val message = (root?.get("error") as? JsonObject)?.get("message")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
            val kind = when (status) {
                401, 403 -> AiFailure.BAD_KEY
                429, 529 -> AiFailure.RATE_LIMITED
                else -> AiFailure.UNEXPECTED
            }
            return AiResult.Failure(kind, message)
        }
        if (root == null) return AiResult.Failure(AiFailure.UNEXPECTED)
        val stopReason = (root["stop_reason"] as? JsonPrimitive)?.contentOrNull
        if (stopReason == "refusal") return AiResult.Failure(AiFailure.DECLINED)
        val text = (root["content"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
            .joinToString("") { (it["text"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
        val picks = parsePicks(text)
        return if (picks.isEmpty()) AiResult.Failure(AiFailure.UNEXPECTED) else AiResult.Picks(picks)
    }

    internal fun parsePicks(text: String): List<AiPick> {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val root = runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }
            .getOrNull() ?: return emptyList()
        val items = runCatching { root["picks"]?.jsonArray }.getOrNull() ?: return emptyList()
        return items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val title = runCatching { item["title"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                ?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            AiPick(
                title = title,
                year = runCatching { item["year"]?.jsonPrimitive?.intOrNull }.getOrNull()
                    ?.takeIf { it in 1880..2100 },
                isSeries = runCatching { item["type"]?.jsonPrimitive?.contentOrNull }.getOrNull() == "series",
                reason = runCatching { item["reason"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                    ?.trim().orEmpty()
            )
        }.distinctBy { it.title.lowercase() to it.isSeries }.take(MAX_PICKS)
    }

    private const val SYSTEM_PROMPT =
        "You help someone decide what to watch in a streaming app. Suggest up to $MAX_PICKS real, " +
            "released movies or TV series that fit their request, best match first. Use each " +
            "title's most common English name so it can be found by search, and give one short " +
            "sentence on why it fits. Treat the titles they have watched or saved as hints about " +
            "their taste. If the request is not about finding something to watch, return no picks."

    private val PICKS_SCHEMA: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("picks") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("title") { put("type", "string") }
                        putJsonObject("year") { put("type", "integer") }
                        putJsonObject("type") {
                            put("type", "string")
                            put("enum", buildJsonArray { add(JsonPrimitive("movie")); add(JsonPrimitive("series")) })
                        }
                        putJsonObject("reason") { put("type", "string") }
                    }
                    put("required", buildJsonArray {
                        listOf("title", "year", "type", "reason").forEach { add(JsonPrimitive(it)) }
                    })
                    put("additionalProperties", false)
                }
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("picks")) })
        put("additionalProperties", false)
    }
}
