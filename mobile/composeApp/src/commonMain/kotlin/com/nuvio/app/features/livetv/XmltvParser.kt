package com.nuvio.app.features.livetv

/**
 * Reads an XMLTV programme guide and keeps only the next few hours for each channel, so even a
 * very large guide costs little memory once parsed.
 */
object XmltvParser {

    private val PROGRAMME = Regex("""<programme\b([^>]*)>(.*?)</programme>""", RegexOption.DOT_MATCHES_ALL)
    private val ATTRIBUTE = Regex("""([A-Za-z_-]+)\s*=\s*"([^"]*)"""")
    private val TITLE = Regex("""<title\b[^>]*>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
    private val DESCRIPTION = Regex("""<desc\b[^>]*>(.*?)</desc>""", RegexOption.DOT_MATCHES_ALL)

    /** How far ahead the guide keeps programmes. */
    const val WINDOW_MS = 6L * 60 * 60 * 1000
    /** At most this many programmes per channel, so a guide with very short slots stays small. */
    const val MAX_PER_CHANNEL = 12
    const val MAX_DESCRIPTION = 240
    /** Descriptions are kept only for programmes starting this soon, to save memory on big guides. */
    private const val DESCRIPTION_AHEAD_MS = 90L * 60 * 1000

    /** Channel guide id (lower case) to what is on now and next at [nowMs]. */
    fun parseNowNext(xml: String, nowMs: Long): Map<String, LiveNowNext> =
        Reader(nowMs).also { it.feed(xml) }.result()

    /**
     * Reads a guide piece by piece, so a guide of hundreds of megabytes never has to sit in
     * memory. Pass [wanted] (lower-case guide ids) to keep only those channels.
     */
    class Reader(
        private val nowMs: Long,
        private val wanted: Set<String>? = null,
        /** When given, guide channels are matched to playlist channels by id and by name. */
        private val matcher: GuideMatcher? = null,
    ) {
        private val pending = StringBuilder()
        private val found = HashMap<String, MutableList<LiveProgramme>>()

        fun feed(chunk: String) {
            pending.append(chunk)
            var position = 0
            while (true) {
                val programme = pending.indexOf(OPEN, position)
                val channel = if (matcher != null) pending.indexOf(CHANNEL_OPEN, position) else -1
                val isChannel = channel >= 0 && (programme < 0 || channel < programme)
                val start = if (isChannel) channel else programme
                if (start < 0) {
                    // Keep a few characters in case an opening tag is split across two pieces.
                    position = maxOf(position, pending.length - OPEN.length)
                    break
                }
                if (isChannel) {
                    val tagEnd = pending.indexOf(">", start)
                    if (tagEnd < 0) {
                        position = start
                        break
                    }
                    if (pending[tagEnd - 1] == '/') {
                        // <channel id="x"/>: nothing more to read.
                        position = tagEnd + 1
                        readChannel(pending.substring(start, position))
                        continue
                    }
                    val end = pending.indexOf(CHANNEL_CLOSE, tagEnd)
                    if (end < 0) {
                        // A channel entry is short; one that never closes is skipped rather than
                        // holding up the programmes after it.
                        if (pending.length - start > MAX_CHANNEL_ELEMENT) {
                            position = tagEnd + 1
                            continue
                        }
                        position = start
                        break
                    }
                    position = end + CHANNEL_CLOSE.length
                    readChannel(pending.substring(start, position))
                    continue
                }
                val end = pending.indexOf(CLOSE, start)
                if (end < 0) {
                    position = start
                    break
                }
                position = end + CLOSE.length
                PROGRAMME.find(pending.substring(start, position))?.let(::accept)
            }
            pending.deleteRange(0, position)
            // A broken guide with an element that never closes must not grow without limit.
            if (pending.length > MAX_PENDING) pending.clear()
        }

        fun result(): Map<String, LiveNowNext> =
            found.mapNotNull { (channel, programmes) -> LiveNowNext.of(programmes, nowMs)?.let { channel to it } }.toMap()

        /** A `<channel>` entry: its id and the names it goes by, used to match playlist channels. */
        private fun readChannel(element: String) {
            val id = CHANNEL_ID.find(element)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty) ?: return
            val names = DISPLAY_NAME.findAll(element).map { decodeEntities(it.groupValues[1]).trim() }
                .filter(String::isNotEmpty).toList()
            matcher?.register(decodeEntities(id), names)
        }

        private fun accept(match: MatchResult) {
            val attributes = ATTRIBUTE.findAll(match.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
            val channel = attributes["channel"]?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return
            if (wanted != null && channel !in wanted) return
            if (matcher != null && !matcher.wants(channel)) return
            val start = parseTime(attributes["start"]) ?: return
            val stop = parseTime(attributes["stop"]) ?: return
            if (stop <= nowMs || start > nowMs + WINDOW_MS || stop <= start) return
            val body = match.groupValues[2]
            val title = TITLE.find(body)?.groupValues?.get(1)?.let(::decodeEntities)?.trim()
                ?.takeIf(String::isNotEmpty) ?: return
            val description = if (start > nowMs + DESCRIPTION_AHEAD_MS) {
                null
            } else {
                DESCRIPTION.find(body)?.groupValues?.get(1)?.let(::decodeEntities)?.trim()
                    ?.take(MAX_DESCRIPTION)?.takeIf(String::isNotEmpty)
            }
            val programme = LiveProgramme(title = title, startMs = start, stopMs = stop, description = description)
            val list = found.getOrPut(channel) { ArrayList() }
            if (list.any { it.startMs == start }) return
            list += programme
            if (list.size > MAX_PER_CHANNEL) {
                // Keep the earliest ones: those are what the guide shows first.
                list.sortBy { it.startMs }
                list.removeAt(list.lastIndex)
            }
        }
    }

    private val CHANNEL_ID = Regex("""<channel\b[^>]*\bid\s*=\s*"([^"]*)"""")
    private val DISPLAY_NAME = Regex("""<display-name\b[^>]*>(.*?)</display-name>""", RegexOption.DOT_MATCHES_ALL)
    private const val CHANNEL_OPEN = "<channel "
    private const val CHANNEL_CLOSE = "</channel>"
    private const val MAX_CHANNEL_ELEMENT = 64 * 1024
    private const val OPEN = "<programme"
    private const val CLOSE = "</programme>"
    private const val MAX_PENDING = 1_000_000

    /** XMLTV time: `YYYYMMDDhhmmss +hhmm`. Seconds and the offset are optional. */
    internal fun parseTime(value: String?): Long? {
        val text = value?.trim() ?: return null
        val digits = text.takeWhile(Char::isDigit)
        if (digits.length < 12) return null
        val year = digits.substring(0, 4).toInt()
        val month = digits.substring(4, 6).toInt()
        val day = digits.substring(6, 8).toInt()
        val hour = digits.substring(8, 10).toInt()
        val minute = digits.substring(10, 12).toInt()
        val second = if (digits.length >= 14) digits.substring(12, 14).toInt() else 0
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null

        val offsetText = text.substring(digits.length).trim()
        val offsetMinutes = if (offsetText.length >= 5 && (offsetText[0] == '+' || offsetText[0] == '-')) {
            val hours = offsetText.substring(1, 3).toIntOrNull() ?: return null
            val minutes = offsetText.substring(3, 5).toIntOrNull() ?: return null
            (hours * 60 + minutes) * if (offsetText[0] == '-') -1 else 1
        } else {
            0
        }
        val seconds = daysFromCivil(year, month, day) * 86_400L + hour * 3_600L + minute * 60L + second
        return (seconds - offsetMinutes * 60L) * 1000L
    }

    /** Days since 1970-01-01 for a calendar date (proleptic Gregorian). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val dayOfYear = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097L + dayOfEra - 719_468L
    }

    private fun decodeEntities(text: String): String =
        text.replace("<![CDATA[", "").replace("]]>", "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&")
}

/**
 * Links guide channels to playlist channels the way NuvioPlusIPTV does: by the playlist's tvg-id
 * first, then by name, comparing the guide's display names and its id read as a name ("Buzzr.us"
 * is "buzzr") with the channel's name and tvg-id. Guide ids are compared in lower case.
 */
class GuideMatcher(channels: List<LiveChannel>) {
    private val byId = HashMap<String, MutableList<String>>()
    private val byName = HashMap<String, MutableList<String>>()
    /** Guide id to the playlist channels it was matched to, and whether by id. */
    private val matched = HashMap<String, Pair<Set<String>, Boolean>>()

    init {
        channels.forEach { channel ->
            channel.guideId?.trim()?.lowercase()?.takeIf(String::isNotEmpty)?.let {
                byId.getOrPut(it) { ArrayList() } += channel.id
            }
            val keys = listOfNotNull(normalize(channel.name), channel.guideId?.let(::idName))
            keys.filter { it.length >= MIN_NAME }.distinct().forEach { key ->
                byName.getOrPut(key) { ArrayList() }.let { if (channel.id !in it) it += channel.id }
            }
        }
    }

    /** Records a guide channel from the guide's channel list. */
    fun register(guideId: String, displayNames: List<String>) {
        val id = guideId.trim().lowercase()
        if (id in matched) return
        matched[id] = match(id, displayNames)
    }

    /** True when programmes of guide channel [guideId] (lower case) belong to a playlist channel. */
    fun wants(guideId: String): Boolean =
        matched.getOrPut(guideId) { match(guideId, emptyList()) }.first.isNotEmpty()

    /**
     * Playlist channel ids to the guide channel each should show, from the guide channels in
     * [available]. A match by id wins over a match by name.
     */
    fun assign(available: Set<String>): Map<String, String> {
        val result = HashMap<String, String>()
        val byIdFirst = matched.entries.filter { it.key in available }.sortedByDescending { it.value.second }
        byIdFirst.forEach { (guideId, value) ->
            value.first.forEach { channelId -> if (channelId !in result) result[channelId] = guideId }
        }
        return result
    }

    private fun match(id: String, displayNames: List<String>): Pair<Set<String>, Boolean> {
        byId[id]?.let { return it.toSet() to true }
        val keys = (displayNames.map(::normalize) + idName(id)).filter { it.length >= MIN_NAME }.distinct()
        val channels = keys.flatMap { byName[it].orEmpty() }.toSet()
        return channels to false
    }

    companion object {
        private const val MIN_NAME = 3
        private val QUALITY = Regex("""\b(fhd|uhd|hd|sd|4k|8k|hevc|h265|h264|1080p|720p|backup|raw)\b""")
        private val PREFIX = Regex("""^[a-z]{2,3}\s*[:|]\s*""")
        private val BRACKETED = Regex("""\[.*?]|\(.*?\)""")
        private val COUNTRY_END = Regex("""(\.(com|co|net|tv))?\.[a-z]{2}\d?$""", RegexOption.IGNORE_CASE)

        /** A channel name reduced to letters and digits: "US: CNBC HD (East)" is "cnbc". */
        fun normalize(name: String): String {
            var text = name.lowercase().trim()
            text = text.replace(PREFIX, "").replace(BRACKETED, " ").replace(QUALITY, " ")
            return text.filter { it in 'a'..'z' || it in '0'..'9' || (it.isLetterOrDigit() && it.code > 0x7F && it.code !in 0x2460..0x24FF) }
        }

        /** A guide id or tvg-id read as a name: "GloboSP.br" is "globosp". */
        fun idName(id: String): String = normalize(id.trim().replace(COUNTRY_END, ""))
    }
}
