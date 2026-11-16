package com.nuvio.app.features.search

import com.nuvio.app.features.addons.*
import kotlin.test.*

class DiscoverCatalogSourcesTest {
    @Test fun configuredAggregatorCatalogsKeepTheirOwnUrlsAndIds() {
        val url = "https://catalog.example/user-config/manifest.json"
        val manifest = AddonManifest("aggregator", "My catalogs", "", "1", resources = emptyList(), types = listOf("movie"),
            catalogs = listOf(AddonCatalog("movie", "provider:popular", "Popular"),
                AddonCatalog("movie", "provider:history", "History movies"),
                AddonCatalog("movie", "search", "Search", listOf(AddonExtraProperty("search", true)))), transportUrl = url)
        val sources = buildDiscoverSources(listOf(ManagedAddon(url, manifest)))
        assertEquals(listOf("provider:popular", "provider:history"), sources.map { it.catalogId })
        assertTrue(sources.all { it.manifestUrl == url })
    }
    @Test fun requiredGenresArePreservedForRequests() {
        val url = "https://catalog.example/manifest.json"
        val manifest = AddonManifest("catalog", "Catalog", "", "1", resources = emptyList(), types = listOf("series"),
            catalogs = listOf(AddonCatalog("series", "shows", "Shows", listOf(AddonExtraProperty("genre", true, listOf("Drama"))))), transportUrl = url)
        val source = buildDiscoverSources(listOf(ManagedAddon(url, manifest))).single()
        assertTrue(source.genreRequired)
        assertEquals(listOf("Drama"), source.genreOptions)
    }
}
