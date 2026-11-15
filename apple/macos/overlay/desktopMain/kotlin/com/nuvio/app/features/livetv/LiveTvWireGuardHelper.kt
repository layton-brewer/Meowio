package com.nuvio.app.features.livetv

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The Live TV VPN on Mac and Windows. WireGuard runs in a small helper program shipped inside the
 * app (native/livetv-vpn, the same engine as the phone and TV apps). It runs on a private network
 * stack, needs no driver or administrator rights, and never becomes a system VPN: it is reachable
 * only as a password-protected proxy on 127.0.0.1, which only the Live TV relay uses.
 */
internal object LiveTvWireGuardHelper {
    private val resourceDir: String? by lazy {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val cpu = if (arch.contains("aarch64") || arch.contains("arm64")) "arm64" else "x86_64"
        when {
            os.contains("win") -> "windows-x86_64"
            os.contains("mac") -> "macos-$cpu"
            else -> null
        }
    }

    private val resourceName: String?
        get() = resourceDir?.let { "/livetv-vpn/$it/meowio-vpn" + if (it.startsWith("windows")) ".exe" else "" }

    /** The helper written out next to the app's data, or null when this build does not include it. */
    private val executable: File? by lazy {
        val name = resourceName ?: return@lazy null
        val stream = LiveTvWireGuardHelper::class.java.getResourceAsStream(name) ?: return@lazy null
        runCatching {
            stream.use { input ->
                val dir = DesktopStorage.rootDir.resolve("livetv-vpn").also { Files.createDirectories(it) }
                val target = dir.resolve(name.substringAfterLast('/'))
                val bytes = input.readBytes()
                // Rewritten only when it changed, so a running copy is not replaced.
                if (!Files.exists(target) || Files.size(target) != bytes.size.toLong() || !Files.readAllBytes(target).contentEquals(bytes)) {
                    val temp = Files.createTempFile(dir, "helper", ".tmp")
                    Files.write(temp, bytes)
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                }
                target.toFile().apply { setExecutable(true) }
            }
        }.getOrNull()
    }

    val available: Boolean get() = executable != null

    /** Null when [text] is a WireGuard file the Live TV VPN can use, otherwise the reason it cannot. */
    fun problem(text: String): String? {
        val exe = executable ?: return "WireGuard is not included in this build. Use an HTTP proxy instead."
        return try {
            val process = ProcessBuilder(exe.path, "check").redirectErrorStream(true).start()
            process.outputStream.use { it.write(text.toByteArray()) }
            val output = process.inputStream.bufferedReader().readText().trim()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return "The WireGuard check did not finish."
            }
            if (process.exitValue() == 0) null else output.ifBlank { "WireGuard configuration is invalid." }
        } catch (e: IOException) {
            "WireGuard could not be checked on this computer."
        }
    }

    /** Starts the helper with [config]; it stays up until [LiveTvRelay.Tunnel.close] is called. */
    @Throws(IOException::class)
    fun start(config: String, protectHttps: Boolean): LiveTvRelay.Tunnel {
        val exe = executable ?: throw IOException("WireGuard is not included in this build.")
        val secret = ByteArray(24).also(SecureRandom()::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
        val process = ProcessBuilder(exe.path, "run").redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val request = buildJsonObject {
            put("config", config)
            put("protectHttps", protectHttps)
            put("token", token)
        }
        process.outputStream.apply {
            write((request.toString() + "\n").toByteArray())
            flush()
        }
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val line = try {
            val future = java.util.concurrent.CompletableFuture.supplyAsync { reader.readLine() }
            future.get(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            process.destroyForcibly()
            throw IOException("The Live TV VPN could not connect.")
        }
        val reply = runCatching { Json.parseToJsonElement(line ?: "") as? JsonObject }.getOrNull()
        val port = reply?.get("port")?.jsonPrimitive?.int
        if (port == null || port <= 0) {
            process.destroyForcibly()
            val reason = reply?.get("error")?.jsonPrimitive?.content
            throw IOException(reason?.takeIf { it.isNotBlank() } ?: "The Live TV VPN could not connect.")
        }
        return LiveTvRelay.Tunnel(
            proxy = LiveProxy("127.0.0.1", port, "nuvio", token),
            close = {
                runCatching {
                    process.outputStream.write("quit\n".toByteArray())
                    process.outputStream.flush()
                    process.outputStream.close()
                }
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
            },
        )
    }
}
