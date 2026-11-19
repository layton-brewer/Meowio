package com.nuvio.app.features.watching.application

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.sortedPlayableEpisodes
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.toEpisodeWatchedItem
import com.nuvio.app.features.watched.toSeriesWatchedItem
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * "Mark entire show as unwatched": wipes every watched mark and every saved
 * position for one title so it can be rewatched from the start with tracking
 * working again. Watched removals go through WatchedRepository, which pushes
 * them to Meowio sync and to connected trackers (Trakt, Simkl, MDBList), and
 * progress removals go through WatchProgressRepository, which deletes them
 * remotely too, so nothing comes back on the next sync.
 */
object UnwatchAll {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun isSeriesLike(type: String): Boolean =
        type.trim().lowercase() in setOf("series", "show", "tv", "tvshow", "anime")

    /** True when anything about this title is still marked watched or has a saved position. */
    fun hasAnyHistory(meta: MetaDetails): Boolean {
        val watched = WatchedRepository.uiState.value.items.any { it.id == meta.id }
        val progress = WatchProgressRepository.uiState.value.entries.any { it.parentMetaId == meta.id }
        return watched || progress
    }

    fun unwatchAll(meta: MetaDetails) {
        if (!isSeriesLike(meta.type)) {
            WatchedRepository.unmarkWatched(meta.toSeriesWatchedItem())
            WatchProgressRepository.removeProgress(contentId = meta.id)
            return
        }

        val episodeItems = meta.sortedPlayableEpisodes().map(meta::toEpisodeWatchedItem)
        val knownEpisodes = episodeItems.mapTo(hashSetOf()) { it.season to it.episode }
        // Episodes watched elsewhere that this add-on's episode list doesn't have (e.g. renumbered specials).
        val extraItems = WatchedRepository.uiState.value.items.filter { item ->
            item.id == meta.id &&
                item.season != null &&
                item.episode != null &&
                (item.season to item.episode) !in knownEpisodes
        }
        val items: List<WatchedItem> = buildList {
            add(meta.toSeriesWatchedItem())
            addAll(episodeItems)
            addAll(extraItems)
        }
        WatchedRepository.unmarkWatched(items)
        WatchProgressRepository.removeProgress(contentId = meta.id)
        WatchedRepository.updateFullyWatchedSeries(
            id = meta.id,
            type = meta.type,
            isFullyWatched = false,
        )
    }

    /** Same as [unwatchAll], started from a Continue Watching card (loads the episode list first). */
    fun unwatchAll(item: ContinueWatchingItem) {
        scope.launch {
            val meta = runCatching {
                MetaDetailsRepository.fetch(type = item.parentMetaType, id = item.parentMetaId)
            }.getOrNull()
            if (meta != null) {
                unwatchAll(meta)
            } else {
                WatchedRepository.unmarkWatched(
                    WatchedItem(
                        id = item.parentMetaId,
                        type = item.parentMetaType,
                        name = item.title,
                        markedAtEpochMs = 0L,
                    ),
                )
                WatchProgressRepository.removeProgress(contentId = item.parentMetaId)
            }
        }
    }
}
