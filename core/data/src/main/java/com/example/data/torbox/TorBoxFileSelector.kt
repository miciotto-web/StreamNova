package com.example.data.torbox

import android.util.Log

object TorBoxFileSelector {

    private const val TAG = "TorBoxFileSelector"

    fun selectFile(
        files: List<TorboxTorrentFileDto>,
        filename: String?,
        season: Int?,
        episode: Int?
    ): TorboxTorrentFileDto? = selectDebridFile(
        files = files,
        filename = filename,
        season = season,
        episode = episode,
        path = { file ->
            listOfNotNull(file.name, file.absolutePath, file.shortName)
                .firstOrNull { it.isNotBlank() }.orEmpty()
        },
        isPlayable = {
            it.mimeType.orEmpty().startsWith("video/", ignoreCase = true) || it.displayName().hasDebridVideoExtension()
        },
        size = { it.size ?: 0L }
    )

    private fun <T> selectDebridFile(
        files: List<T>,
        filename: String?,
        season: Int?,
        episode: Int?,
        path: (T) -> String,
        isPlayable: (T) -> Boolean,
        size: (T) -> Long,
    ): T? {
        val playable = files.filter(isPlayable)
        if (playable.isEmpty()) return null

        val names = listOfNotNull(filename)
            .map { it.normalizedPath() }
            .filter { it.isNotBlank() }
            .distinct()

        for (name in names) {
            val matches = playable.matchingFiles(name, path)
            if (matches.isNotEmpty()) return matches.singleOrNull()
        }

        val episodePattern = buildEpisodePattern(season, episode)
        if (episodePattern != null) {
            val matches = playable.filter {
                episodePattern.containsMatchIn(path(it).normalizedPath().substringAfterLast('/'))
            }
            if (matches.isNotEmpty()) return matches.singleOrNull()
        }

        if (names.isNotEmpty() || episodePattern != null) return null

        return playable.maxByOrNull(size)
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

    private fun buildEpisodePattern(season: Int?, episode: Int?): Regex? {
        if (season == null || episode == null) return null
        return Regex(
            "(?<![a-z0-9])(?:s0*${season}e0*${episode}|0*${season}x0*${episode})(?![0-9])",
            RegexOption.IGNORE_CASE,
        )
    }

    internal fun String.hasDebridVideoExtension(): Boolean = videoExtensions.any { endsWith(it, ignoreCase = true) }

    private val videoExtensions = setOf(
        ".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v",
        ".ts", ".m2ts", ".wmv", ".flv"
    )
}
