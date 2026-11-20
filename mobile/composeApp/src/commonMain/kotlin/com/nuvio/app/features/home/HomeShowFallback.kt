package com.nuvio.app.features.home

import com.nuvio.app.features.watchprogress.ContinueWatchingItem

/**
 * Continue Watching cards whose title is still the raw id (for example "tt0773262"), or that have
 * no art, borrow the show's name and art from any other known card of the same title: another
 * episode, a cached card, or a Next Up card. Episode details are never borrowed.
 */
internal fun List<ContinueWatchingItem>.withShowLevelFallbacks(
    extraSources: Collection<ContinueWatchingItem> = emptyList(),
): List<ContinueWatchingItem> {
    if (none { it.needsShowLevelFallback() }) return this
    val fallbackByContentId = linkedMapOf<String, ContinueWatchingItem>()
    (this + extraSources).forEach { item ->
        if (item.parentMetaId.isBlank() || item.hasPlaceholderShowTitle()) return@forEach
        val existing = fallbackByContentId[item.parentMetaId]
        if (existing == null || item.showArtScore() > existing.showArtScore()) {
            fallbackByContentId[item.parentMetaId] = item
        }
    }
    if (fallbackByContentId.isEmpty()) return this
    return map { item ->
        val fallback = fallbackByContentId[item.parentMetaId]
        if (fallback == null || !item.needsShowLevelFallback()) item else item.withShowLevelFallback(fallback)
    }
}

private fun ContinueWatchingItem.needsShowLevelFallback(): Boolean =
    hasPlaceholderShowTitle() || (poster.isNullOrBlank() && background.isNullOrBlank())

private fun ContinueWatchingItem.hasPlaceholderShowTitle(): Boolean {
    val normalized = title.trim()
    return normalized.isEmpty() || normalized.equals(parentMetaId.trim(), ignoreCase = true)
}

private fun ContinueWatchingItem.showArtScore(): Int =
    listOf(poster, background, logo).count { !it.isNullOrBlank() }

private fun String?.nonBlankOr(fallback: String?): String? =
    this?.takeIf { it.isNotBlank() } ?: fallback?.takeIf { it.isNotBlank() }

private fun ContinueWatchingItem.withShowLevelFallback(fallback: ContinueWatchingItem): ContinueWatchingItem {
    val resolvedPoster = poster.nonBlankOr(fallback.poster)
    val resolvedBackground = background.nonBlankOr(fallback.background)
    return copy(
        title = if (hasPlaceholderShowTitle()) fallback.title else title,
        poster = resolvedPoster,
        background = resolvedBackground,
        logo = logo.nonBlankOr(fallback.logo),
        imageUrl = imageUrl.nonBlankOr(resolvedBackground ?: resolvedPoster),
    )
}
