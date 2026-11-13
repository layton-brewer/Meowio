package com.nuvio.app.features.livetv

/**
 * The channels Live TV shows before the user adds a playlist or login of their own: the Free-TV
 * project's list of free-to-air and officially free streams, grouped by country.
 */
object LiveTvDefaults {
    const val PLAYLIST_URL = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"

    val source = LiveSource(type = LiveSourceType.M3U, url = PLAYLIST_URL)

    /** Group name in the built-in list to the country tag its guide files are named with. */
    private val guideTagByGroup = mapOf(
        "USA" to "US", "UK" to "UK", "Canada" to "CA", "Australia" to "AU", "Ireland" to "IE",
        "Germany" to "DE", "France" to "FR", "Spain" to "ES", "Italy" to "IT", "Poland" to "PL",
        "Netherlands" to "NL", "Belgium" to "BE", "Austria" to "AT", "Switzerland" to "CH",
        "Sweden" to "SE", "Norway" to "NO", "Denmark" to "DK", "Finland" to "FI", "Portugal" to "PT",
        "Brazil" to "BR", "Mexico" to "MX", "Argentina" to "AR", "Chile" to "CL", "India" to "IN",
        "日本 / Japan" to "JP", "Korea" to "KR", "Turkey" to "TR", "Greece" to "GR", "Hungary" to "HU",
        "Czech Republic" to "CZ", "Romania" to "RO", "Croatia" to "HR", "Albania" to "AL",
        "Bosnia and Herzegovina" to "BA", "Saudi Arabia" to "SA", "Nigeria" to "NG", "Egypt" to "EG",
        "Lithuania" to "LT", "Latvia" to "LV", "Serbia" to "RS", "Slovakia" to "SK", "Israel" to "IL",
        "Peru" to "PE", "Cyprus" to "CY", "Costa Rica" to "CR", "Bulgaria" to "BG", "Kenya" to "KE",
        "Hong Kong" to "HK", "Malta" to "MT", "Indonesia" to "ID", "Dominican Republic" to "DO",
    )

    /** Groups without a guide file that still deserve to open first in their country. */
    private val extraGroupByCountry = mapOf("CN" to "China", "RU" to "Russia", "UA" to "Ukraine")

    /** The built-in list's group for a two-letter country code, or null when it has none. */
    fun groupForCountry(countryCode: String?): String? {
        val code = countryCode?.trim()?.uppercase() ?: return null
        val tag = if (code == "GB") "UK" else code
        return guideTagByGroup.entries.firstOrNull { it.value == tag }?.key ?: extraGroupByCountry[code]
    }

    /**
     * The guide file for [group] among the many a worldwide playlist announces. They are named
     * by country, for example `epg_ripper_US1.xml.gz`.
     */
    fun guideUrlForGroup(group: String, guideUrls: List<String>): String? {
        val tag = guideTagByGroup[group] ?: return null
        val pattern = Regex("""_${tag}\d\.xml(\.gz)?$""")
        return guideUrls.firstOrNull { pattern.containsMatchIn(it.substringBefore('?')) }
    }
}
