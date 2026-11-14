package com.nuvio.tv.updater

import com.nuvio.tv.data.remote.dto.GitHubAssetDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AbiSelectorTest {

    private fun asset(name: String) = GitHubAssetDto(
        name = name,
        browserDownloadUrl = "https://example.com/$name",
        size = 1L
    )

    @Test
    fun `picks the tv build from a release that also carries the phone build`() {
        val chosen = AbiSelector.chooseBestApkAsset(
            listOf(asset("Meowio-mobile.apk"), asset("Meowio-tv.apk"))
        )
        assertEquals("Meowio-tv.apk", chosen?.name)
    }

    @Test
    fun `never offers the phone build to a tv`() {
        assertNull(AbiSelector.chooseBestApkAsset(listOf(asset("Meowio-mobile.apk"))))
    }
}
