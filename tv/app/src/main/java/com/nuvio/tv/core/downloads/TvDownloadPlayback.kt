package com.nuvio.tv.core.downloads

import kotlinx.coroutines.flow.MutableStateFlow

/** Carries a finished download chosen in settings over to navigation, which opens the player. */
object TvDownloadPlayback {
    val pending = MutableStateFlow<TvDownload?>(null)
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface TvDownloadEntryPoint {
    fun tvDownloadManager(): TvDownloadManager
}
