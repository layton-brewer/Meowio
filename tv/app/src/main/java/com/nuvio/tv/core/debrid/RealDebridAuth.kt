package com.nuvio.tv.core.debrid

import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

/**
 * A Real-Debrid credential is either a private API token (real-debrid.com/apitoken) or, after code
 * sign-in, "rdoauth:<clientId>:<clientSecret>:<refreshToken>". Call sites always send
 * "Bearer <credential>"; [Interceptor] swaps the second form for a short-lived access token and
 * refreshes it when it runs out, so nothing else needs to know.
 */
object RealDebridAuth {
    const val OPEN_SOURCE_CLIENT_ID = "X245A4XAIBGVM"
    private const val PREFIX = "rdoauth:"
    private val tokens = mutableMapOf<String, Pair<String, Long>>()

    fun encode(clientId: String, clientSecret: String, refreshToken: String): String =
        "$PREFIX$clientId:$clientSecret:$refreshToken"

    class Interceptor(private val tokenClient: OkHttpClient) : okhttp3.Interceptor {
        override fun intercept(chain: okhttp3.Interceptor.Chain): Response {
            val request = chain.request()
            val credential = request.header("Authorization")?.removePrefix("Bearer ")?.trim()
            if (credential == null || !credential.startsWith(PREFIX)) return chain.proceed(request)
            val token = accessToken(credential)
            return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
        }

        @Synchronized
        private fun accessToken(credential: String): String {
            val now = System.currentTimeMillis()
            tokens[credential]?.takeIf { it.second > now }?.let { return it.first }
            val parts = credential.removePrefix(PREFIX).split(':', limit = 3)
            if (parts.size != 3) throw java.io.IOException("Real-Debrid sign-in is damaged. Sign in again.")
            val body = FormBody.Builder()
                .add("client_id", parts[0])
                .add("client_secret", parts[1])
                .add("code", parts[2])
                .add("grant_type", "http://oauth.net/grant_type/device/1.0")
                .build()
            val response = tokenClient.newCall(
                Request.Builder().url("https://api.real-debrid.com/oauth/v2/token").post(body).build()
            ).execute()
            val json = response.use { it.body?.string().orEmpty().takeIf { _ -> it.isSuccessful } }
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            val token = json?.optString("access_token")?.takeIf { it.isNotBlank() }
                ?: throw java.io.IOException("Real-Debrid sign-in expired. Sign in again.")
            val lifetimeMs = ((json.optLong("expires_in", 3600L)) - 60L).coerceAtLeast(60L) * 1000L
            tokens[credential] = token to (now + lifetimeMs)
            return token
        }
    }
}
