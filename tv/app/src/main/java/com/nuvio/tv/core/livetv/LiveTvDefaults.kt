package com.nuvio.tv.core.livetv

/**
 * The channels Live TV shows before the user adds a playlist or login of their own. They come
 * from free, legal services and public lists, merged into one channel list with one guide:
 *
 * - Pluto TV, Samsung TV Plus, Plex and Roku: the free ad-supported channels those services
 *   stream to anyone, listed with their own programme guides by i.mjh.nz.
 * - The Free-TV project: free-to-air and officially free streams, grouped by country.
 * - iptv-org: the community list of publicly available streams for the device's country.
 */
object LiveTvDefaults {
    const val PLAYLIST_URL = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"

    /** The Free-TV list. Its guide is loaded one country group at a time. */
    val source = LiveSource(type = LiveSourceType.M3U, url = PLAYLIST_URL, name = "Free-TV", id = "builtin-freetv")

    private const val MJH = "https://i.mjh.nz/"
    /** A copy of i.mjh.nz's guide files on GitHub, tried when i.mjh.nz cannot be reached. */
    private const val MJH_MIRROR = "https://raw.githubusercontent.com/matthuisman/i.mjh.nz/master/"

    /** Countries each free service streams to, by lower-case country code (UK is "gb"). */
    private val plutoCountries = setOf("us", "ar", "br", "ca", "cl", "de", "dk", "es", "fr", "gb", "it", "mx", "no", "se")
    private val samsungCountries = setOf("us", "at", "ca", "ch", "de", "es", "fr", "gb", "in", "it", "kr")
    private val plexCountries = setOf("us", "au", "ca", "es", "fr", "gb", "mx", "nz")

    /** Ids of built-in sources start with this, so they are never mixed up with the user's. */
    const val BUILT_IN_PREFIX = "builtin-"

    fun isBuiltIn(source: LiveSource): Boolean = source.id.startsWith(BUILT_IN_PREFIX)

    /** The country code the free services use for [countryCode]: "gb" for the UK, "us" when unknown. */
    fun serviceCountry(countryCode: String?): String {
        val code = countryCode?.trim()?.lowercase().orEmpty()
        return when {
            code == "uk" -> "gb"
            code.length == 2 -> code
            else -> "us"
        }
    }

    /**
     * Every built-in source for a device in [countryCode], in the order they are listed: the
     * free streaming services first (they are reliable and have full guides), then Free-TV,
     * then iptv-org. Services that do not stream to the country are left out; a country none of
     * them covers gets the US line-up, which plays worldwide for most channels.
     */
    fun builtInSources(countryCode: String?): List<LiveSource> {
        val code = serviceCountry(countryCode)
        val covered = code in plutoCountries || code in samsungCountries || code in plexCountries
        val sources = mutableListOf<LiveSource>()
        fun service(name: String, folder: String, countries: Set<String>) {
            val region = when {
                code in countries -> code
                // Another service has the country's own line-up, so a foreign one would only add noise.
                covered -> return
                else -> "us"
            }
            sources += LiveSource(
                type = LiveSourceType.M3U,
                url = "$MJH$folder/$region.m3u8",
                guideUrl = "$MJH$folder/$region.xml.gz",
                name = name,
                id = "$BUILT_IN_PREFIX${folder.lowercase()}",
            )
        }
        service("Pluto TV", "PlutoTV", plutoCountries)
        service("Samsung TV Plus", "SamsungTVPlus", samsungCountries)
        service("Plex", "Plex", plexCountries)
        if (code == "us" || !covered) {
            sources += LiveSource(
                type = LiveSourceType.M3U,
                url = "${MJH}Roku/all.m3u8",
                guideUrl = "${MJH}Roku/all.xml.gz",
                name = "Roku",
                id = "${BUILT_IN_PREFIX}roku",
            )
        }
        sources += source
        sources += LiveSource(
            type = LiveSourceType.M3U,
            url = "https://iptv-org.github.io/iptv/countries/$code.m3u",
            name = "iptv-org",
            id = "${BUILT_IN_PREFIX}iptvorg",
        )
        return sources
    }

    /**
     * Guide files to read for a built-in [source] besides the one it names. iptv-org publishes no
     * guide, so it gets the country's epgshare01 guide, matched to its channels by id and name.
     * Its channels that the free services also carry get those programmes from the controller.
     */
    fun extraGuideUrls(source: LiveSource): List<String> {
        if (source.id != "${BUILT_IN_PREFIX}iptvorg") return emptyList()
        val code = source.url.substringAfterLast('/').substringBefore('.')
        val tag = if (code == "gb") "UK" else code.uppercase()
        return listOf("https://epgshare01.online/epgshare01/epg_ripper_${tag}1.xml.gz")
    }

    /** The free services' guides for a country code, small files with exact channel names. */
    private fun serviceGuideUrls(code: String): List<String> {
        val urls = mutableListOf<String>()
        if (code in plutoCountries) urls += "${MJH}PlutoTV/$code.xml.gz"
        if (code in samsungCountries) urls += "${MJH}SamsungTVPlus/$code.xml.gz"
        if (code in plexCountries) urls += "${MJH}Plex/$code.xml.gz"
        if (code == "us") urls += "${MJH}Roku/all.xml.gz"
        return urls
    }

    /**
     * Other addresses for the same guide file, tried in order when one cannot be read. i.mjh.nz
     * files also live on GitHub, which some networks reach when they cannot reach i.mjh.nz.
     */
    fun guideMirrors(url: String): List<String> =
        if (url.startsWith(MJH)) listOf(url, MJH_MIRROR + url.removePrefix(MJH)) else listOf(url)

    /** iptv-org files groups as "News;General": the first one is the group shown. */
    fun cleanGroup(source: LiveSource, group: String?): String? =
        if (source.id == "${BUILT_IN_PREFIX}iptvorg") group?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() && it != "Undefined" } else group

    /**
     * Sent when downloading playlists and guides, as NuvioPlusIPTV does. Some guide hosts refuse
     * the plain Java or OkHttp agent.
     */
    const val DOWNLOAD_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

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
    fun guideUrlForGroup(group: String, guideUrls: List<String>): String? =
        guideUrlsForGroup(group, guideUrls).firstOrNull()

    /** Every guide file for [group], such as `epg_ripper_US1.xml.gz` and `epg_ripper_US2.xml.gz`. */
    fun guideUrlsForGroup(group: String, guideUrls: List<String>): List<String> {
        val tag = guideTagByGroup[group] ?: return emptyList()
        val pattern = Regex("""_${tag}\d\.xml(\.gz)?$""")
        return guideUrls.filter { pattern.containsMatchIn(it.substringBefore('?')) }
    }

    /**
     * Other guide files to read for [group] besides [guideUrlsForGroup], so one host being slow,
     * blocked or short of a channel does not leave the guide empty. They are the free streaming
     * services' guides for the group's country: small files, matched to channels by name.
     */
    fun fallbackGuideUrls(group: String): List<String> {
        val tag = guideTagByGroup[group] ?: return emptyList()
        return serviceGuideUrls(if (tag == "UK") "gb" else tag.lowercase())
    }
}
