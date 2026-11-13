package com.nuvio.app.features.livetv

/**
 * The channels Live TV shows before the user adds a playlist or login of their own: the Free-TV
 * project's list of free-to-air and officially free streams, grouped by country.
 */
object LiveTvDefaults {
    const val PLAYLIST_URL = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"

    val source = LiveSource(type = LiveSourceType.M3U, url = PLAYLIST_URL)

    private val groupByCountry = mapOf(
        "US" to "USA", "GB" to "UK", "CA" to "Canada", "AU" to "Australia", "IE" to "Ireland",
        "DE" to "Germany", "FR" to "France", "ES" to "Spain", "IT" to "Italy", "PL" to "Poland",
        "NL" to "Netherlands", "BE" to "Belgium", "AT" to "Austria", "CH" to "Switzerland",
        "SE" to "Sweden", "NO" to "Norway", "DK" to "Denmark", "FI" to "Finland", "PT" to "Portugal",
        "BR" to "Brazil", "MX" to "Mexico", "AR" to "Argentina", "CL" to "Chile", "IN" to "India",
        "JP" to "日本 / Japan", "KR" to "Korea", "CN" to "China", "TR" to "Turkey", "GR" to "Greece",
        "RU" to "Russia", "UA" to "Ukraine", "HU" to "Hungary", "CZ" to "Czech Republic", "RO" to "Romania",
    )

    /** The built-in list's group for a two-letter country code, or null when it has none. */
    fun groupForCountry(countryCode: String?): String? = groupByCountry[countryCode?.trim()?.uppercase()]
}
