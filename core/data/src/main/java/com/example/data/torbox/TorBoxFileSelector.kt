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

        // L'episodio richiesto è il vincolo AUTOREVOLE: quando è noto si cerca
        // PRIMA il file SxxExx/NxN. Il match per nome va usato solo per i torrent
        // senza episodio (film) o quando il pattern non trova nulla: in un torrent
        // di stagione il primo file (o il nome suggerito) può essere un episodio
        // diverso da quello richiesto, e restituirlo avviava la puntata sbagliata.
        val episodePattern = buildEpisodePattern(season, episode)
        if (episodePattern != null) {
            val matches = playable.filter {
                episodePattern.containsMatchIn(path(it).normalizedPath().substringAfterLast('/'))
            }
            if (matches.isNotEmpty()) {
                // Più file per lo stesso episodio (pack multi-qualità, es. 720p+1080p):
                // vince il più grande, che è la qualità più alta — non un sample/trailer
                // (sempre più piccoli). `singleOrNull` falliva l'intero sblocco.
                return matches.maxByOrNull(size)
            }
            // Torrent con un solo file video: è il contenuto richiesto anche quando il
            // nome non riporta stagione/episodio (release rinominata). Se però il nome
            // dichiara un ALTRO episodio, non va usato: meglio nessun file che quello
            // sbagliato.
            val only = playable.singleOrNull()
            val onlyName = only?.let { path(it).normalizedPath().substringAfterLast('/') }
            if (only != null && onlyName != null && !ANY_EPISODE_MARKER.containsMatchIn(onlyName)) {
                return only
            }
        }

        for (name in names) {
            val matches = playable.matchingFiles(name, path)
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

    /**
     * Qualunque marker di episodio (`S01E02`, `1x02`) in un nome file: serve a non
     * usare un file che dichiara esplicitamente un episodio diverso da quello richiesto.
     */
    private val ANY_EPISODE_MARKER = Regex(
        "(?<![a-z0-9])(?:s\\d{1,2}e\\d{1,3}|\\d{1,2}x\\d{1,3})(?![0-9])",
        RegexOption.IGNORE_CASE,
    )

    internal fun String.hasDebridVideoExtension(): Boolean = videoExtensions.any { endsWith(it, ignoreCase = true) }

    private val videoExtensions = setOf(
        ".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v",
        ".ts", ".m2ts", ".wmv", ".flv"
    )
}
