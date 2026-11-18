package com.nuvio.tv.data.remote.api

import com.nuvio.tv.core.debrid.RealDebridAuth
import com.nuvio.tv.data.remote.dto.RealDebridAddTorrentDto
import com.nuvio.tv.data.remote.dto.RealDebridDeviceCodeDto
import com.nuvio.tv.data.remote.dto.RealDebridDeviceCredentialsDto
import com.nuvio.tv.data.remote.dto.RealDebridTokenDto
import com.nuvio.tv.data.remote.dto.RealDebridTorrentInfoDto
import com.nuvio.tv.data.remote.dto.RealDebridUnrestrictLinkDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface RealDebridApi {
    @GET("user")
    suspend fun getUser(
        @Header("Authorization") authorization: String
    ): Response<ResponseBody>

    @FormUrlEncoded
    @POST("torrents/addMagnet")
    suspend fun addMagnet(
        @Header("Authorization") authorization: String,
        @Field("magnet") magnet: String
    ): Response<RealDebridAddTorrentDto>

    @GET("torrents/info/{id}")
    suspend fun getTorrentInfo(
        @Header("Authorization") authorization: String,
        @Path("id") id: String
    ): Response<RealDebridTorrentInfoDto>

    @FormUrlEncoded
    @POST("torrents/selectFiles/{id}")
    suspend fun selectFiles(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Field("files") files: String
    ): Response<ResponseBody>

    @FormUrlEncoded
    @POST("unrestrict/link")
    suspend fun unrestrictLink(
        @Header("Authorization") authorization: String,
        @Field("link") link: String
    ): Response<RealDebridUnrestrictLinkDto>

    @DELETE("torrents/delete/{id}")
    suspend fun deleteTorrent(
        @Header("Authorization") authorization: String,
        @Path("id") id: String
    ): Response<ResponseBody>

    @GET("torrents")
    suspend fun listTorrents(
        @Header("Authorization") authorization: String,
        @Query("limit") limit: Int = 1000
    ): Response<List<RealDebridTorrentInfoDto>>

    @GET("https://api.real-debrid.com/oauth/v2/device/code")
    suspend fun startDeviceAuthorization(
        @Query("client_id") clientId: String = RealDebridAuth.OPEN_SOURCE_CLIENT_ID,
        @Query("new_credentials") newCredentials: String = "yes"
    ): Response<RealDebridDeviceCodeDto>

    @GET("https://api.real-debrid.com/oauth/v2/device/credentials")
    suspend fun deviceCredentials(
        @Query("code") deviceCode: String,
        @Query("client_id") clientId: String = RealDebridAuth.OPEN_SOURCE_CLIENT_ID
    ): Response<RealDebridDeviceCredentialsDto>

    /** Exchanges a device code or a refresh token (both go in `code`) for an access token. */
    @FormUrlEncoded
    @POST("https://api.real-debrid.com/oauth/v2/token")
    suspend fun token(
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String,
        @Field("code") code: String,
        @Field("grant_type") grantType: String = "http://oauth.net/grant_type/device/1.0"
    ): Response<RealDebridTokenDto>
}
