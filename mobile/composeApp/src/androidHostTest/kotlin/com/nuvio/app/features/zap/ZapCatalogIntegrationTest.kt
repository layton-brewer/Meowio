package com.nuvio.app.features.zap

import android.app.Application
import android.content.Context
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonStorage
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.LibraryStorage
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ZapCatalogIntegrationTest {
    @BeforeTest
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        listOf("nuvio_addons", "nuvio_library").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        AddonStorage.initialize(context)
        LibraryStorage.initialize(context)
        AddonRepository.clearLocalState()
        LibraryRepository.clearLocalState()
        LibraryRepository.toggleLocalSaved(LibraryItem(
            id = "saved", type = "movie", name = "Saved library title",
            poster = "https://example.test/saved.jpg", savedAtEpochMs = 1L,
        ))
        assertTrue(LibraryRepository.isSaved("saved", "movie"))
    }

    @AfterTest
    fun tearDown() {
        AddonRepository.clearLocalState()
        LibraryRepository.clearLocalState()
    }

    @Test
    fun configuredCatalogsSupplyPicksAndLibraryNeverSuppliesFallback(): Unit = runBlocking {
        MockWebServer().use { server ->
            val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    paths += path
                    return when (path) {
                        "/configured/manifest.json" -> MockResponse().setBody("""
                            {"id":"aggregator","name":"Configured aggregator","version":"1.0.0",
                             "resources":["catalog"],"types":["movie","series"],"catalogs":[
                               {"id":"movies","type":"movie","name":"Movies"},
                               {"id":"shows","type":"series","name":"Shows"}]}
                        """.trimIndent())
                        "/configured/catalog/movie/movies.json" -> MockResponse().setBody("""
                            {"metas":[
                              {"id":"saved","type":"movie","name":"Saved library title","poster":"https://example.test/saved.jpg"},
                              {"id":"new-movie","type":"movie","name":"Catalog movie","poster":"https://example.test/movie.jpg"}]}
                        """.trimIndent())
                        "/configured/catalog/series/shows.json" -> MockResponse().setBody("""
                            {"metas":[{"id":"new-show","type":"series","name":"Catalog show","poster":"https://example.test/show.jpg"}]}
                        """.trimIndent())
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            AddonStorage.saveInstalledAddonUrls(ProfileRepository.activeProfileId, listOf(server.url("/configured/manifest.json").toString()))
            val pool = ZapPool(ZapFilters(hideWatched = false))
            withTimeout(15_000) {
                val picks = List(2) { assertIs<ZapResult.Found>(pool.next()).title }
                assertEquals(setOf("new-movie", "new-show"), picks.map { it.id }.toSet())
                assertEquals(setOf("Configured aggregator · Movies", "Configured aggregator · Shows"), picks.map { it.sourceName }.toSet())
                assertEquals(ZapResult.Empty(ZapEmpty.NoMatches), pool.next())
            }
            assertEquals(setOf("/configured/manifest.json", "/configured/catalog/movie/movies.json", "/configured/catalog/series/shows.json"), paths.toSet())
            assertEquals(3, paths.size)
        }
    }

    @Test
    fun noCatalogsDoesNotFallBackToSavedLibrary(): Unit = runBlocking {
        assertEquals(ZapResult.Empty(ZapEmpty.NoSources), ZapPool(ZapFilters()).next())
    }
}
