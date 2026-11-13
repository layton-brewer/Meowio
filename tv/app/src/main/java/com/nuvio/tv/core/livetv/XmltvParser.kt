package com.nuvio.tv.core.livetv

/**
 * Reads an XMLTV programme guide and keeps only what is on now and next for each channel, so
 * even a very large guide costs little memory once parsed.
 */
object XmltvParser {

    private val PROGRAMME = Regex("""<programme\b([^>]*)>(.*?)</programme>""", RegexOption.DOT_MATCHES_ALL)
    private val ATTRIBUTE = Regex("""([A-Za-z_-]+)\s*=\s*"([^"]*)"""")
    private val TITLE = Regex("""<title\b[^>]*>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)

    /** Channel guide id (lower case) to what is on now and next at [nowMs]. */
    fun parseNowNext(xml: String, nowMs: Long): Map<String, LiveNowNext> {
        val result = HashMap<String, LiveNowNext>()
        PROGRAMME.findAll(xml).forEach { match ->
            val attributes = ATTRIBUTE.findAll(match.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
            val channel = attributes["channel"]?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return@forEach
            val start = parseTime(attributes["start"]) ?: return@forEach
            val stop = parseTime(attributes["stop"]) ?: return@forEach
            if (stop <= nowMs) return@forEach
            val title = TITLE.find(match.groupValues[2])?.groupValues?.get(1)?.let(::decodeEntities)?.trim()
                ?.takeIf(String::isNotEmpty) ?: return@forEach
            val programme = LiveProgramme(title = title, startMs = start, stopMs = stop)
            val current = result[channel] ?: LiveNowNext()
            result[channel] = if (start <= nowMs) {
                current.copy(now = programme)
            } else if (current.next == null || start < current.next.startMs) {
                current.copy(next = programme)
            } else {
                current
            }
        }
        return result
    }

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
