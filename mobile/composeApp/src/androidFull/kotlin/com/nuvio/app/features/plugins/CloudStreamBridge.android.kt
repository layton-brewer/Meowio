package com.nuvio.app.features.plugins

import com.lagradost.cloudstream3.TvType
import com.nuvio.app.features.plugins.cloudstream.CloudStreamRuntime
import com.nuvio.app.features.plugins.cloudstream.ExternalExtensionLoader
import com.nuvio.app.features.plugins.cloudstream.ExternalExtensionRunner
import com.nuvio.app.features.plugins.cloudstream.ExternalRepoParser
import com.nuvio.app.features.plugins.cloudstream.toNuvioType
import com.nuvio.app.features.plugins.cloudstream.tvTypeFromString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val DOWNLOAD_PARALLELISM = 4
private const val EXECUTION_TIMEOUT_MS = 120_000L

internal object AndroidCloudStreamEngine : CloudStreamEngine {
    private val loader: ExternalExtensionLoader? by lazy {
        CloudStreamRuntime.context?.let { ExternalExtensionLoader(it) }
    }
    private val runner: ExternalExtensionRunner? by lazy { loader?.let { ExternalExtensionRunner(it) } }

    override suspend fun isRepository(url: String): Boolean =
        loader != null && ExternalRepoParser.tryParse(url) != null

    override suspend fun loadRepository(
        url: String,
        previous: Map<String, PluginScraper>,
    ): Pair<PluginRepositoryItem, List<PluginScraper>>? = withContext(Dispatchers.IO) {
        val extensionLoader = loader ?: return@withContext null
        val parsed = ExternalRepoParser.tryParse(url) ?: return@withContext null
        val gate = Semaphore(DOWNLOAD_PARALLELISM)
        val scrapers = coroutineScope {
            parsed.plugins.map { plugin ->
                async {
                    gate.withPermit {
                        val scraperId = "$CLOUDSTREAM_SCRAPER_PREFIX$url:${plugin.internalName}"
                        val file = runCatching { extensionLoader.downloadExtension(scraperId, plugin.url) }.getOrNull()
                            ?: return@withPermit null
                        val types = plugin.tvTypes.orEmpty()
                            .mapNotNull { tvTypeFromString(it) }
                            .map(TvType::toNuvioType)
                            .distinct()
                            .ifEmpty { listOf("movie", "tv") }
                        val manifestEnabled = plugin.status == 1
                        file.let {
                            PluginScraper(
                                id = scraperId,
                                repositoryUrl = url,
                                name = plugin.name,
                                description = plugin.description.orEmpty(),
                                version = plugin.version.toString(),
                                filename = plugin.url,
                                supportedTypes = types,
                                enabled = previous[scraperId]?.enabled ?: manifestEnabled,
                                manifestEnabled = manifestEnabled,
                                logo = plugin.iconUrl,
                                code = "cloudstream",
                            )
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val repo = PluginRepositoryItem(
            manifestUrl = url,
            name = parsed.name,
            description = parsed.description,
            version = null,
            scraperCount = scrapers.size,
            lastUpdated = System.currentTimeMillis(),
        )
        repo to scrapers
    }

    override suspend fun execute(
        scraper: PluginScraper,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
    ): List<PluginRuntimeResult> {
        val extensionRunner = runner ?: return emptyList()
        val results = withTimeoutOrNull(EXECUTION_TIMEOUT_MS) {
            extensionRunner.execute(scraper.id, tmdbId, mediaType, season, episode)
        } ?: emptyList()
        return results.map { it.copy(provider = scraper.name) }
    }
}
