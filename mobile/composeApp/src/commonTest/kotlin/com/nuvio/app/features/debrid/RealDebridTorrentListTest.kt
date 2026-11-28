package com.nuvio.app.features.debrid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RealDebridTorrentListTest {
    @Test
    fun fractionalProgressAndOddEntriesDoNotEmptyTheList() {
        val raw = """
            [
              {"id":"A","filename":"Show.S01","hash":"aa","bytes":100,"progress":100,"status":"downloaded","links":["https://real-debrid.com/d/A"]},
              {"id":"B","filename":"Movie","hash":"bb","bytes":200,"progress":12.5,"status":"downloading","links":[]},
              {"id":"C","filename":"Odd","bytes":"not a number","status":"downloaded"}
            ]
        """.trimIndent()
        val list = RealDebridApiClient.parseTorrentList(raw)!!
        assertEquals(listOf("A", "B"), list.map { it.id })
        assertEquals(12.5, list[1].progress)
    }

    @Test
    fun emptyAnswerIsAnEmptyListAndGarbageIsNull() {
        assertEquals(0, RealDebridApiClient.parseTorrentList("")!!.size)
        assertNull(RealDebridApiClient.parseTorrentList("<html>error</html>"))
    }
}
