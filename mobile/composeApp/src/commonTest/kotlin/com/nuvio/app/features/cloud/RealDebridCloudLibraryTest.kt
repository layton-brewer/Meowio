package com.nuvio.app.features.cloud

import com.nuvio.app.features.debrid.RealDebridTorrentFileDto
import com.nuvio.app.features.debrid.RealDebridTorrentInfoDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealDebridCloudLibraryTest {
    @Test
    fun `selected files pair with links in order and unfinished torrents have no files`() {
        val torrent = RealDebridTorrentInfoDto(
            id = "T1",
            filename = "Show.S01",
            status = "downloaded",
            links = listOf("https://rd/l1", "https://rd/l2"),
        )
        val info = torrent.copy(
            files = listOf(
                RealDebridTorrentFileDto(id = 3, path = "/Show.S01E02.mkv", bytes = 20, selected = 1),
                RealDebridTorrentFileDto(id = 1, path = "/Show.S01E01.mkv", bytes = 10, selected = 1),
                RealDebridTorrentFileDto(id = 2, path = "/sample.txt", bytes = 1, selected = 0),
            ),
        )
        val item = realDebridCloudItem(torrent, info, "realdebrid", "Real-Debrid")!!
        assertEquals(listOf("Show.S01E01.mkv" to "https://rd/l1", "Show.S01E02.mkv" to "https://rd/l2"), item.files.map { it.name to it.id })

        val downloading = realDebridCloudItem(torrent.copy(status = "downloading", progress = 40.0), null, "realdebrid", "Real-Debrid")!!
        assertTrue(downloading.files.isEmpty())
        assertEquals(0.4f, downloading.progressFraction)
    }
}
