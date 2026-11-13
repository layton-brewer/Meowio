package com.nuvio.app.features.plugins

/** Scrapers from CloudStream repositories carry this prefix in their id. */
internal const val CLOUDSTREAM_SCRAPER_PREFIX = "cs3:"

internal fun PluginScraper.isCloudStream(): Boolean = id.startsWith(CLOUDSTREAM_SCRAPER_PREFIX)

/** Runs CloudStream extensions. Only the Android full build provides one. */
internal interface CloudStreamEngine {
    suspend fun isRepository(url: String): Boolean

    /** Reads a CloudStream repository and downloads its extensions. Null when [url] is not one. */
    suspend fun loadRepository(
        url: String,
        previous: Map<String, PluginScraper>,
    ): Pair<PluginRepositoryItem, List<PluginScraper>>?

    suspend fun execute(
        scraper: PluginScraper,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
    ): List<PluginRuntimeResult>
}

/** Where the plugin list reaches CloudStream. Without an engine it finds no repositories and no streams. */
internal object CloudStreamBridge {
    var engine: CloudStreamEngine? = null

    suspend fun isRepository(url: String): Boolean = engine?.isRepository(url) == true

    suspend fun loadRepository(
        url: String,
        previous: Map<String, PluginScraper>,
    ): Pair<PluginRepositoryItem, List<PluginScraper>>? = engine?.loadRepository(url, previous)

    suspend fun execute(
        scraper: PluginScraper,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
    ): List<PluginRuntimeResult> = engine?.execute(scraper, tmdbId, mediaType, season, episode) ?: emptyList()
}
