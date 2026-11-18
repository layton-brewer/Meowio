package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaceholderVideoDetectorTest {
    @Test
    fun `known placeholder clip addresses are spotted`() {
        listOf(
            "https://torrentio.strem.fun/videos/downloading_v2.mp4",
            "https://torrentio.strem.fun/videos/failed_unexpected_v2.mp4",
            "https://torrentio.strem.fun/videos/limits_exceeded_v2.mp4",
            "https://mediafusion.elfhosted.com/static/exceptions/torrent_not_downloaded.mp4",
            "https://comet.example.com/assets/uncached.mp4",
            "https://addon.example.com/static/videos/download_failed.mp4?x=1",
        ).forEach { assertTrue(PlaceholderVideoDetector.isPlaceholderUrl(it), it) }
    }

    @Test
    fun `real debrid and media links are not placeholders`() {
        listOf(
            "https://abc.download.real-debrid.com/d/XYZ123/Movie.Name.2021.1080p.WEB-DL.mkv",
            "https://store-1.tb-cdn.st/dld/abc?token=1",
            "https://media.example.com/media/Trial.and.Error.S01E01.720p.mkv",
            "https://jellyfin.example.com/Videos/abc123/stream.mkv?static=true",
            "https://cdn.example.com/static/Downloaded.2019.mp4",
            "magnet:?xt=urn:btih:abc",
            null,
        ).forEach { assertFalse(PlaceholderVideoDetector.isPlaceholderUrl(it), it.toString()) }
    }

    @Test
    fun `a clip far shorter than the runtime is a placeholder`() {
        assertTrue(PlaceholderVideoDetector.isPlaceholderDuration(12_000L, 120))
        assertTrue(PlaceholderVideoDetector.isPlaceholderDuration(4L * 60_000L, 22))
        assertFalse(PlaceholderVideoDetector.isPlaceholderDuration(20L * 60_000L, 22))
        assertFalse(PlaceholderVideoDetector.isPlaceholderDuration(6L * 60_000L, 120))
    }

    @Test
    fun `short titles and unknown lengths are judged carefully`() {
        // Shorts and minisodes are never judged by length.
        assertFalse(PlaceholderVideoDetector.isPlaceholderDuration(60_000L, 5))
        // Without a runtime, only a very short clip counts.
        assertTrue(PlaceholderVideoDetector.isPlaceholderDuration(30_000L, null))
        assertFalse(PlaceholderVideoDetector.isPlaceholderDuration(3L * 60_000L, null))
        assertFalse(PlaceholderVideoDetector.isPlaceholderDuration(0L, 120))
    }

    @Test
    fun `trailers are left alone`() {
        assertFalse(
            PlaceholderVideoDetector.isPlaceholderPlayback(
                url = "https://cdn.example.com/trailer.mp4",
                durationMs = 90_000L,
                expectedRuntimeMinutes = 120,
                streamText = "Official Trailer",
            )
        )
    }

    @Test
    fun `runtime text is read`() {
        assertEquals(130, PlaceholderVideoDetector.runtimeMinutes("2h 10min"))
        assertEquals(45, PlaceholderVideoDetector.runtimeMinutes("45 min"))
        assertEquals(112, PlaceholderVideoDetector.runtimeMinutes("1:52"))
        assertEquals(null, PlaceholderVideoDetector.runtimeMinutes(""))
    }
}
