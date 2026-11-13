package com.nuvio.tv.data.repository

import com.nuvio.tv.core.livetv.LiveTvController
import com.nuvio.tv.core.livetv.LiveTvLoader
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.LiveTvDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Connects the shared Live TV logic to this app's storage, network and profiles. */
@Singleton
class LiveTvRepository @Inject constructor(
    storage: LiveTvDataStore,
    profileManager: ProfileManager,
    // Providers are whatever address the user enters, often with broken certificates.
    @param:Named("addonPermissive") okHttpClient: OkHttpClient
) {
    private val client = okHttpClient.newBuilder()
        .cache(null)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val controller = LiveTvController(
        storage = storage,
        loader = LiveTvLoader(fetchText = ::fetchText, nowMs = System::currentTimeMillis),
        scope = scope
    )

    init {
        controller.start()
        scope.launch {
            profileManager.activeProfileId.drop(1).collect {
                controller.reset()
                controller.start()
            }
        }
    }

    private suspend fun fetchText(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                body.byteStream().use { stream -> readText(stream) }
            }
        }.getOrNull()
    }

    /** Reads at most [MAX_TEXT_BYTES], unpacking gzip files that servers send as plain downloads. */
    private fun readText(raw: InputStream): String {
        val pushback = PushbackInputStream(raw, 2)
        val magic = ByteArray(2)
        val read = pushback.read(magic, 0, 2)
        if (read > 0) pushback.unread(magic, 0, read)
        val gzip = read == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()
        val input: InputStream = if (gzip) GZIPInputStream(pushback) else pushback
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        try {
            while (out.size() < MAX_TEXT_BYTES) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_TEXT_BYTES - out.size()))
                if (count < 0) break
                out.write(buffer, 0, count)
            }
        } catch (e: java.io.IOException) {
            // A cut-off download still holds usable channels or programmes.
            if (out.size() == 0) throw e
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val MAX_TEXT_BYTES = 32 * 1024 * 1024
    }
}
