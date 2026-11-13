package com.nuvio.app.features.plugins.cloudstream

import android.util.Log
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private const val TAG = "ExternalRepoParser"

@Serializable
data class ExternalPluginEntry(
    val name: String,
    val internalName: String,
    val description: String? = null,
    val version: Int = 1,
    val apiVersion: Int = 1,
    val status: Int = 1,
    val authors: List<String>? = null,
    val tvTypes: List<String>? = null,
    val iconUrl: String? = null,
    val url: String,
    val fileSize: Long? = null,
    val repositoryUrl: String? = null,
)

@Serializable
private data class ExternalRepoManifest(
    val name: String = "External Repository",
    val description: String? = null,
    val pluginLists: List<String> = emptyList(),
)

data class ExternalRepoParseResult(
    val name: String,
    val description: String?,
    val plugins: List<ExternalPluginEntry>,
)

/**
 * Reads CloudStream repositories, which come in two shapes: a manifest whose `pluginLists`
 * point at separate plugin lists, or a plain list of plugins.
 */
object ExternalRepoParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val listSerializer = ListSerializer(ExternalPluginEntry.serializer())

    /** Null when the address is not a CloudStream repository. */
    suspend fun tryParse(url: String): ExternalRepoParseResult? {
        val body = fetch(url)?.trim() ?: return null
        if (body.contains("\"pluginLists\"")) {
            val manifest = runCatching { json.decodeFromString(ExternalRepoManifest.serializer(), body) }.getOrNull()
            if (manifest != null && manifest.pluginLists.isNotEmpty()) {
                val plugins = coroutineScope {
                    manifest.pluginLists.map { listUrl ->
                        async { fetchPluginList(resolve(url, listUrl)).orEmpty() }
                    }.awaitAll().flatten()
                }
                return ExternalRepoParseResult(manifest.name, manifest.description, plugins)
            }
        }
        if (body.startsWith("[")) {
            val plugins = runCatching { json.decodeFromString(listSerializer, body) }.getOrNull()
            if (!plugins.isNullOrEmpty() && plugins.first().internalName.isNotBlank()) {
                return ExternalRepoParseResult(inferName(url), null, plugins)
            }
        }
        return null
    }

    private suspend fun fetchPluginList(url: String): List<ExternalPluginEntry>? {
        val body = fetch(url) ?: return null
        return runCatching { json.decodeFromString(listSerializer, body.trim()) }
            .onFailure { Log.e(TAG, "Failed to parse plugin list from $url: ${it.message}") }
            .getOrNull()
    }

    private suspend fun fetch(url: String): String? = runCatching {
        httpGetTextWithHeaders(url, mapOf("User-Agent" to "Meowio/1.0"))
    }.onFailure { Log.e(TAG, "Failed to fetch $url: ${it.message}") }.getOrNull()

    private fun resolve(baseUrl: String, relative: String): String =
        if (relative.startsWith("http://") || relative.startsWith("https://")) relative
        else baseUrl.substringBeforeLast("/") + "/" + relative

    private fun inferName(url: String): String =
        url.substringAfter("://").substringBefore("?").split("/").filter { it.isNotBlank() }
            .lastOrNull()?.removeSuffix(".json") ?: "External Repository"
}
