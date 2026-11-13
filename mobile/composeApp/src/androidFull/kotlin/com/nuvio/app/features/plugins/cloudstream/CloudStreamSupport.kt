package com.nuvio.app.features.plugins.cloudstream

import android.content.Context
import android.os.Build
import android.util.Log
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.ignoreAllSSLErrors
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tmdb.buildTmdbUrl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cache
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import java.io.File
import java.security.Security
import java.util.concurrent.ConcurrentHashMap

/** Collects the lines an extension run reports. The phone only logs them. */
class TestDiagnostics(val steps: MutableList<String> = mutableListOf()) {
    fun addStep(step: String) {
        steps.add(step)
        Log.d("CloudStream", step)
    }
}

/** Picks the entry that matches a requested season and episode, or null when none does. */
fun matchPluginEpisodeIndex(entries: List<Pair<Int?, Int?>>, season: Int?, episode: Int?): Int? {
    if (entries.isEmpty() || episode == null) return null
    if (season != null) {
        val exact = entries.indexOfFirst { (s, e) -> s == season && e == episode }
        return exact.takeIf { it >= 0 }
    }
    val absolute = entries.indexOfFirst { (s, e) -> e == episode && s == null }
    if (absolute >= 0) return absolute
    return entries.indexOfFirst { (_, e) -> e == episode }.takeIf { it >= 0 }
}

/** What the runner needs to know about a title to search for it. */
data class CloudStreamTitleInfo(
    val localizedTitle: String?,
    val originalTitle: String?,
    val releaseInfo: String?,
    val alternativeTitles: List<String>,
)

/** Title lookups for the runner, backed by the phone's TMDB settings. */
object CloudStreamTmdb {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchEnrichment(tmdbId: String, isMovie: Boolean): CloudStreamTitleInfo? = runCatching {
        val apiKey = TmdbSettingsRepository.effectiveApiKey()
        val kind = if (isMovie) "movie" else "tv"
        val url = buildTmdbUrl(
            endpoint = "$kind/$tmdbId",
            apiKey = apiKey,
            query = mapOf("append_to_response" to "alternative_titles"),
        )
        val root = json.parseToJsonElement(httpGetText(url)).jsonObject
        fun text(key: String) = root[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        val alternatives = (root["alternative_titles"] as? JsonObject)
            ?.let { it["titles"] ?: it["results"] }
            ?.let { it as? JsonArray }
            .orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull }
        CloudStreamTitleInfo(
            localizedTitle = text(if (isMovie) "title" else "name"),
            originalTitle = text(if (isMovie) "original_title" else "original_name"),
            releaseInfo = text(if (isMovie) "release_date" else "first_air_date"),
            alternativeTitles = alternatives,
        )
    }.getOrNull()

    suspend fun tmdbToImdb(tmdbId: Int, mediaType: String): String? = TmdbService.tmdbToImdb(tmdbId, mediaType)
}

/** One-time setup that extensions expect before they run: crypto provider and shared HTTP client. */
object CloudStreamRuntime {
    @Volatile private var appContext: Context? = null
    @Volatile private var initialized = false
    private val cookies = ConcurrentHashMap<String, List<Cookie>>()

    val context: Context? get() = appContext

    fun attach(context: Context) {
        appContext = context.applicationContext
        AcraApplication.context = context.applicationContext
    }

    fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val context = appContext ?: return
            try {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
            } catch (e: Exception) {
                Log.w("CloudStream", "Failed to install Conscrypt: ${e.message}")
            }
            try {
                app.baseClient = OkHttpClient.Builder()
                    .cookieJar(object : CookieJar {
                        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                            this@CloudStreamRuntime.cookies[url.host] = cookies
                        }

                        override fun loadForRequest(url: HttpUrl): List<Cookie> =
                            cookies[url.host].orEmpty().filter { it.matches(url) }
                    })
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .ignoreAllSSLErrors()
                    .cache(Cache(File(context.cacheDir, "cs_http_cache"), 50L * 1024L * 1024L))
                    .build()
            } catch (e: Throwable) {
                Log.w("CloudStream", "Failed to initialize the HTTP client (API ${Build.VERSION.SDK_INT}): ${e.message}")
            }
            initialized = true
        }
    }
}
