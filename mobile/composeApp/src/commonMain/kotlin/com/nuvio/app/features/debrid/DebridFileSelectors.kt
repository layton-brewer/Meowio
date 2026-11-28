package com.nuvio.app.features.debrid

import com.nuvio.app.features.streams.StreamClientResolve

internal class TorboxFileSelector {
    fun selectFile(
        files: List<TorboxTorrentFileDto>,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?,
    ): TorboxTorrentFileDto? = selectDebridFile(
        files = files,
        resolve = resolve,
        season = season,
        episode = episode,
        path = { it.displayName() },
        isPlayable = {
            it.mimeType.orEmpty().startsWith("video/", ignoreCase = true) || it.displayName().hasVideoExtension()
        },
        size = { it.size ?: 0L },
    )
}

internal class RealDebridFileSelector {
    fun selectFile(
        files: List<RealDebridTorrentFileDto>,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?,
    ): RealDebridTorrentFileDto? = selectDebridFile(
        files = files,
        resolve = resolve,
        season = season,
        episode = episode,
        path = { it.path.orEmpty() },
        isPlayable = { it.displayName().hasVideoExtension() },
        size = { it.bytes ?: 0L },
    )
}

internal class PremiumizeDirectDownloadFileSelector {
    fun selectFile(
        files: List<PremiumizeDirectDownloadFileDto>,
        resolve: StreamClientResolve,
        season: Int?,
        episode: Int?,
    ): PremiumizeDirectDownloadFileDto? = selectDebridFile(
        files = files,
        resolve = resolve,
        season = season,
        episode = episode,
        path = { it.path.orEmpty() },
        isPlayable = { !it.link.isNullOrBlank() && it.displayName().hasVideoExtension() },
        size = { it.size ?: 0L },
    )
}

internal fun PremiumizeDirectDownloadFileDto.displayName(): String =
    path.orEmpty().substringAfterLast('/').substringAfterLast('\\').ifBlank { path.orEmpty() }

/**
 * Picks the one file to play or download from a torrent, the way you would on the debrid site:
 * the add-on's file name first, then the episode's SxxEyy tag, then the add-on's file index, and
 * for packs whose files carry only an episode number ("Episode 01.mkv", "Show - 01.mkv") the
 * episode number inside the right season folder. Samples and extras are never picked.
 */
private fun <T> selectDebridFile(
    files: List<T>,
    resolve: StreamClientResolve,
    season: Int?,
    episode: Int?,
    path: (T) -> String,
    isPlayable: (T) -> Boolean,
    size: (T) -> Long,
): T? {
    val playable = files.filter(isPlayable)
    if (playable.isEmpty()) return null

    val names = listOfNotNull(resolve.filename, resolve.stream?.raw?.filename)
        .map { it.normalizedPath() }
        .filter { it.isNotBlank() }
        .distinct()
    val wantedSeason = season ?: resolve.season
    val wantedEpisode = episode ?: resolve.episode
    for (name in names) {
        val matches = playable.matchingFiles(name, path)
        if (matches.size > 1 && wantedSeason != null && wantedEpisode != null) {
            // The same file name in several season folders: take the one in this season's folder.
            matches.filter { wantedSeason in path(it).seasonsMentioned() }.singleOrNull()?.let { return it }
        }
        if (matches.isNotEmpty()) return matches.singleOrNull()
    }

    if (wantedSeason != null && wantedEpisode != null) {
        // Samples and extras inside a pack are never the episode.
        val main = playable.filterNot { path(it).isExtraFile() }.ifEmpty { playable }
        val tagged = main.filter { (wantedSeason to wantedEpisode) in path(it).episodeTags() }
        if (tagged.isNotEmpty()) return tagged.singleOrNull()

        // The add-on's index points at the episode inside the pack; use it when that file
        // carries no episode tag of its own (packs named "Episode 01.mkv" and the like).
        resolve.fileIdx?.let { index -> files.getOrNull(index) }
            ?.takeIf { isPlayable(it) && it in main && path(it).episodeTags().isEmpty() }
            ?.let { return it }

        // Files named only by episode number: use the season folder, or the pack holding one season.
        val packSeasons = main.flatMap { path(it).seasonsMentioned() }.toSet()
        val numbered = main.filter { file ->
            val filePath = path(file).normalizedPath()
            if (filePath.episodeTags().isNotEmpty()) return@filter false
            val fileSeasons = filePath.seasonsMentioned()
            val seasonOk = if (fileSeasons.isNotEmpty()) {
                wantedSeason in fileSeasons
            } else {
                packSeasons.isEmpty() || packSeasons == setOf(wantedSeason)
            }
            seasonOk && filePath.substringAfterLast('/').hasEpisodeNumber(wantedEpisode)
        }
        return numbered.maxByOrNull(size)
    }

    if (names.isNotEmpty()) return null

    resolve.fileIdx?.let { index ->
        return files.getOrNull(index)?.takeIf(isPlayable)
    }

    return playable.maxByOrNull(size)
}

private val EXTRA_FILE_REGEX = Regex(
    "(?:^|[^a-z0-9])(?:sample|trailer|featurette|extras?|behind[ ._-]the[ ._-]scenes|deleted[ ._-]scenes?)(?:[^a-z0-9]|$)",
    RegexOption.IGNORE_CASE,
)

private fun String.isExtraFile(): Boolean = EXTRA_FILE_REGEX.containsMatchIn(normalizedPath().substringAfterLast('/')) ||
    normalizedPath().split('/').dropLast(1).any { segment ->
        segment.equals("sample", true) || segment.equals("samples", true) ||
            segment.equals("extras", true) || segment.equals("featurettes", true)
    }

private val EPISODE_TAG_REGEX = Regex(
    "(?<![a-z0-9])(?:s(\\d{1,2})[ ._-]?e(\\d{1,3})|(\\d{1,2})x(\\d{2,3})|season[ ._-]?(\\d{1,2})[ ._-]?episode[ ._-]?(\\d{1,3}))(?![0-9])",
    RegexOption.IGNORE_CASE,
)

/** Every (season, episode) tag in the file's own name, like S01E02, S01 E02, 1x02. */
private fun String.episodeTags(): List<Pair<Int, Int>> =
    EPISODE_TAG_REGEX.findAll(normalizedPath().substringAfterLast('/')).mapNotNull { match ->
        val groups = match.groupValues
        val s = (groups[1].ifEmpty { groups[3] }.ifEmpty { groups[5] }).toIntOrNull()
        val e = (groups[2].ifEmpty { groups[4] }.ifEmpty { groups[6] }).toIntOrNull()
        if (s != null && e != null) s to e else null
    }.toList()

private val SEASON_REGEX = Regex(
    "(?<![a-z0-9])(?:season|series|saison|staffel|temporada)[ ._-]?(\\d{1,2})(?![0-9])|(?<![a-z0-9])s(\\d{1,2})(?![0-9e])",
    RegexOption.IGNORE_CASE,
)

/**
 * Seasons named by the file's own tags, or else by the nearest folder that names exactly one
 * season ("Season 2/", "S02/"). A folder naming a range ("S01-S05") says nothing about the file.
 */
private fun String.seasonsMentioned(): Set<Int> {
    val normalized = normalizedPath()
    val fromTags = normalized.episodeTags().map { it.first }.toSet()
    if (fromTags.isNotEmpty()) return fromTags
    for (segment in normalized.split('/').dropLast(1).asReversed()) {
        val seasons = SEASON_REGEX.findAll(segment).mapNotNull { match ->
            match.groupValues[1].ifEmpty { match.groupValues[2] }.toIntOrNull()
        }.toSet()
        if (seasons.size == 1) return seasons
        if (seasons.size > 1) return emptySet()
    }
    return emptySet()
}

/** True when a file name carries only an episode number equal to [episode] ("Episode 3", "E03", "Show - 03"). */
private fun String.hasEpisodeNumber(episode: Int): Boolean {
    val base = substringBeforeLast('.')
    val patterns = listOf(
        "(?<![a-z0-9])(?:episode|ep|e)[ ._-]?0*$episode(?![0-9])",
        "(?:^|\\s)-\\s*0*$episode(?![0-9])",
        "^0*$episode(?![0-9])(?:[ ._-]|$)",
    )
    return patterns.any { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(base) }
}

private fun String.normalizedPath(): String = trim().replace('\\', '/').removePrefix("/")

private fun <T> List<T>.matchingFiles(name: String, path: (T) -> String): List<T> {
    for (ignoreCase in listOf(false, true)) {
        val matches = filter {
            val filePath = path(it).normalizedPath()
            filePath.equals(name, ignoreCase = ignoreCase) ||
                (name.contains('/') && filePath.endsWith("/$name", ignoreCase = ignoreCase))
        }
        if (matches.isNotEmpty()) return matches
    }
    val basename = name.substringAfterLast('/')
    for (ignoreCase in listOf(false, true)) {
        val matches = filter {
            path(it).normalizedPath().substringAfterLast('/').equals(basename, ignoreCase = ignoreCase)
        }
        if (matches.isNotEmpty()) return matches
    }
    return emptyList()
}

/** A real video file of a torrent (not a sample, trailer or extra). */
internal fun String.isMainVideoFile(): Boolean = hasVideoExtension() && !isExtraFile()

private fun String.hasVideoExtension(): Boolean = videoExtensions.any { endsWith(it, ignoreCase = true) }

private val videoExtensions = setOf(
    ".mp4",
    ".mkv",
    ".webm",
    ".avi",
    ".mov",
    ".m4v",
    ".ts",
    ".m2ts",
    ".wmv",
    ".flv",
)
