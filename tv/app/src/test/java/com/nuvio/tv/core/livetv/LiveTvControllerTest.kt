package com.nuvio.tv.core.livetv

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTvControllerTest {

    private class MemoryStorage : LiveTvStorage {
        val values = HashMap<String, String>()
        override suspend fun read(key: String): String? = values[key]
        override suspend fun write(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private val playlist = """
        #EXTM3U
        #EXTINF:-1 tvg-id="one" group-title="News",News One
        http://example.com/one.m3u8
        #EXTINF:-1 tvg-id="two" group-title="Sport",Sport Two
        http://example.com/two.m3u8
    """.trimIndent()

    private val source = LiveSource(LiveSourceType.M3U, "http://example.com/list.m3u")

    @Test
    fun `source survives being saved and read back`() {
        val xtream = LiveSource(LiveSourceType.XTREAM, "http://host:8080", "me", "p\"w", "http://guide")
        assertEquals(xtream, LiveTvCodec.decodeSource(LiveTvCodec.encodeSource(xtream)))
        assertNull(LiveTvCodec.decodeSource("not json"))
        assertNull(LiveTvCodec.decodeSource(null))
        assertEquals(listOf("a", "b"), LiveTvCodec.decodeIds(LiveTvCodec.encodeIds(listOf("a", "b", "a"))))
    }

    @Test
    fun `saving a source loads its channels and remembers it`() = runTest(UnconfinedTestDispatcher()) {
        val storage = MemoryStorage()
        val controller = LiveTvController(storage, LiveTvLoader({ playlist }, { 0L }), backgroundScope)
        controller.ensureChannels()
        assertTrue(controller.state.value.ready)
        assertNull(controller.state.value.source)

        controller.setSource(source.copy(url = "  http://example.com/list.m3u "))
        advanceUntilIdle()
        Thread.sleep(200)

        val state = controller.state.value
        assertEquals(listOf("News One", "Sport Two"), state.channels.map(LiveChannel::name))
        assertEquals(listOf("News", "Sport"), state.groups)
        assertFalse(state.loading)
        assertEquals(source, LiveTvCodec.decodeSource(storage.values["source"]))
    }

    @Test
    fun `a failed load reports why`() = runTest(UnconfinedTestDispatcher()) {
        val controller = LiveTvController(MemoryStorage(), LiveTvLoader({ "<html>" }, { 0L }), backgroundScope)
        controller.start()
        controller.setSource(source)
        Thread.sleep(200)
        assertEquals(LiveTvError.NOT_A_PLAYLIST, controller.state.value.error)
        assertTrue(controller.state.value.channels.isEmpty())
    }

    @Test
    fun `favorites recent channels and the tab setting are kept`() = runTest(UnconfinedTestDispatcher()) {
        val storage = MemoryStorage()
        storage.values["source"] = LiveTvCodec.encodeSource(source)
        val first = LiveTvController(storage, LiveTvLoader({ playlist }, { 0L }), backgroundScope)
        first.ensureChannels()
        Thread.sleep(200)
        val ids = first.state.value.channels.map(LiveChannel::id)
        first.toggleFavorite(ids[1])
        first.markWatched(ids[0])
        first.markWatched(ids[1])
        first.markWatched(ids[0])
        first.setTabVisible(false)

        val second = LiveTvController(storage, LiveTvLoader({ playlist }, { 0L }), backgroundScope)
        second.ensureChannels()
        Thread.sleep(200)
        val state = second.state.value
        assertEquals(setOf(ids[1]), state.favorites)
        assertEquals(listOf(ids[0], ids[1]), state.recent)
        assertFalse(state.tabVisible)
        assertEquals(listOf("Sport Two"), LiveTvController.visibleChannels(state, "", LiveTvSelection.FAVORITES).map(LiveChannel::name))
        assertEquals(listOf("News One", "Sport Two"), LiveTvController.visibleChannels(state, "", LiveTvSelection.RECENT).map(LiveChannel::name))
        assertEquals(listOf("News One"), LiveTvController.visibleChannels(state, "", "News").map(LiveChannel::name))
        assertEquals(listOf("Sport Two"), LiveTvController.visibleChannels(state, "sport", LiveTvSelection.ALL).map(LiveChannel::name))
    }
}
