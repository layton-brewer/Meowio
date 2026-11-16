package com.nuvio.app.features.search

import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonExtraProperty
import com.nuvio.app.features.catalog.supportsPagination

internal fun buildDiscoverSources(addons: List<ManagedAddon>): List<DiscoverCatalogOption> =
    addons.mapNotNull { addon ->
        val manifest = addon.manifest ?: return@mapNotNull null
        addon to manifest
    }.flatMap { (addon, manifest) ->
        manifest.catalogs
            .filter { catalog -> catalog.supportsDiscover() }
            .map { catalog ->
                val genreExtra = catalog.genreExtra()
                DiscoverCatalogOption(
                    key = "${manifest.id}:${catalog.type}:${catalog.id}",
                    addonName = addon.displayTitle,
                    manifestUrl = addon.manifestUrl,
                    type = catalog.type,
                    catalogId = catalog.id,
                    catalogName = catalog.name,
                    genreOptions = genreExtra?.options.orEmpty(),
                    genreRequired = genreExtra?.isRequired == true,
                    supportsPagination = catalog.supportsPagination(),
                )
            }
    }

private fun AddonCatalog.supportsDiscover(): Boolean {
    if (extra.any { property -> property.name == "search" && property.isRequired }) {
        return false
    }

    return extra.none { property ->
        when (property.name) {
            "genre" -> property.isRequired && property.options.isEmpty()
            "skip" -> false
            "search" -> false
            else -> property.isRequired
        }
    }
}

private fun AddonCatalog.genreExtra(): AddonExtraProperty? =
    extra.firstOrNull { property -> property.name == "genre" }

