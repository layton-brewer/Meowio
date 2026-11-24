package com.nuvio.tv.updater

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.updater.model.AppUpdate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal class NoEligibleUpdateException(channel: UpdateChannel) :
    IllegalStateException("No compatible APK release found for ${channel.storedValue} channel")

@Singleton
class UpdateRepository @Inject constructor(
    private val gitHubReleaseApi: GitHubReleaseApi,
    private val okHttpClient: OkHttpClient
) {

    suspend fun getLatestUpdate(channel: UpdateChannel): Result<AppUpdate> {
        val fromApi = getLatestUpdateFromApi(channel)
        if (fromApi.isSuccess || channel != UpdateChannel.STABLE || fromApi.exceptionOrNull() is NoEligibleUpdateException) {
            return fromApi
        }
        // The GitHub API allows 60 unauthenticated requests an hour per network, shared by every device at home.
        // When it refuses (or is unreachable), the release page redirect still names the latest tag.
        val fromReleasePage = getLatestUpdateFromReleasePage()
        return if (fromReleasePage.isSuccess) fromReleasePage else fromApi
    }

    private suspend fun getLatestUpdateFromReleasePage(): Result<AppUpdate> = withContext(Dispatchers.IO) {
        runCatching {
            val owner = BuildConfig.GITHUB_OWNER
            val repo = BuildConfig.GITHUB_REPO
            val client = okHttpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
            val request = Request.Builder()
                .url("https://github.com/$owner/$repo/releases/latest")
                .header("User-Agent", "Meowio")
                .build()
            val location = client.newCall(request).execute().use { it.header("Location") }
                ?: error("No latest release redirect")
            val tag = location.substringAfter("/releases/tag/", "").substringBefore('?').substringBefore('#')
            if (VersionUtils.parse(tag) == null || VersionUtils.isPrerelease(tag)) error("No stable release tag in $location")
            AppUpdate(
                tag = tag,
                title = "Meowio $tag",
                notes = "",
                releaseUrl = location,
                assetName = TV_APK_NAME,
                assetUrl = "https://github.com/$owner/$repo/releases/download/$tag/$TV_APK_NAME",
                assetSizeBytes = null
            )
        }
    }

    private suspend fun getLatestUpdateFromApi(channel: UpdateChannel): Result<AppUpdate> {
        return runCatching {
            val owner = BuildConfig.GITHUB_OWNER
            val repo = BuildConfig.GITHUB_REPO

            val releases = when (channel) {
                UpdateChannel.STABLE -> {
                    val response = gitHubReleaseApi.getLatestRelease(owner = owner, repo = repo)
                    if (!response.isSuccessful) {
                        error("GitHub API error: ${response.code()}")
                    }
                    listOf(response.body() ?: error("Empty GitHub release response"))
                }
                UpdateChannel.BETA -> {
                    val response = gitHubReleaseApi.getReleases(owner = owner, repo = repo)
                    if (!response.isSuccessful) {
                        error("GitHub API error: ${response.code()}")
                    }
                    response.body() ?: error("Empty GitHub release response")
                }
            }
            val releaseWithAsset = ReleaseSelector
                .eligibleReleases(releases, channel)
                .firstNotNullOfOrNull { release ->
                    AbiSelector.chooseBestApkAsset(release.assets)?.let { asset ->
                        release to asset
                    }
                }
                ?: throw NoEligibleUpdateException(channel)
            val (dto, asset) = releaseWithAsset

            val tag = dto.tagName?.takeIf { it.isNotBlank() }
                ?: dto.name?.takeIf { it.isNotBlank() }
                ?: error("Release has no tag/name")

            AppUpdate(
                tag = tag,
                title = dto.name?.takeIf { it.isNotBlank() } ?: tag,
                notes = dto.body.orEmpty(),
                releaseUrl = dto.htmlUrl,
                assetName = asset.name,
                assetUrl = asset.browserDownloadUrl,
                assetSizeBytes = asset.size
            )
        }
    }
}

private const val TV_APK_NAME = "Meowio-tv.apk"
