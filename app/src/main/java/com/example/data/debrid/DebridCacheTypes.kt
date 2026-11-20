package com.example.data.debrid

/**
 * Tipi condivisi dai repository dei servizi Debrid (Real-Debrid, Premiumize e
 * future integrazioni). TorBox resta sul proprio package storico
 * (`com.example.data.torbox`) con contratto identico a [DebridCacheGateway].
 */

/** File di un torrent/container conosciuto da un servizio Debrid. */
data class DebridFile(
  val name: String? = null,
  val size: Long? = null
) {
  private val isVideo: Boolean
    get() {
      val n = name?.lowercase() ?: return false
      if (n.isBlank()) return false
      if (EXCLUDED_PATTERNS.any { n.contains(it) }) return false
      return VIDEO_EXTENSIONS.any { n.endsWith(it) }
    }

  /** >= 0 per i file video (dimensione), -1 per i non video. */
  val videoScore: Long
    get() = if (isVideo) (size ?: 0L) else -1L

  companion object {
    private val VIDEO_EXTENSIONS = listOf(
      ".mkv", ".mp4", ".avi", ".mov", ".wmv", ".flv", ".webm", ".m4v", ".mpg", ".mpeg", ".ts"
    )

    private val EXCLUDED_PATTERNS = listOf(
      "sample", "trailer", "subtitle", "subtitles", "srt", "nfo",
      "nofile", "poster", "folder", "info", "description"
    )
  }
}

/** Stato di cache di un singolo infoHash normalizzato su un servizio Debrid. */
data class DebridCacheEntry(
  val cached: Boolean,
  val files: List<DebridFile> = emptyList()
)

/**
 * Contratto minimo di un gateway Debrid usato dai provider di stream
 * (controparte del torrent-only `TorBoxGateway` storico di TorBox).
 */
interface DebridCacheGateway {
  /** true se il servizio può essere usato (toggle ON + chiave API presente). */
  fun isConfigured(): Boolean

  /** Verifica in batch la cache istantanea degli infoHash normalizzati. */
  suspend fun checkCached(hashes: List<String>): Map<String, DebridCacheEntry>
}

/**
 * Matcher stagione/episodio condiviso dai repository Debrid (Real-Debrid,
 * Premiumize). Stessi pattern di TorBoxRepository: match case-insensitive sui
 * nomi file (`S01E01`, `1x01`, `01x01`, …) per selezionare l'episodio giusto
 * dentro un torrent di stagione.
 */
object DebridEpisodeMatcher {

  /**
   * Cerca il file dell'episodio richiesto tra [files].
   *
   * Se più file corrispondono (es. qualità multiple), preferisce quello con più
   * token della firma della release corrente ([preferTokens]), così il prossimo
   * episodio resta sulla stessa qualità del precedente.
   *
   * @return il file dell'episodio richiesto, oppure null se non presente.
   */
  fun findEpisodeFile(
    files: List<DebridFile>,
    season: Int,
    episode: Int,
    preferTokens: List<String> = emptyList()
  ): DebridFile? {
    val patterns = buildSeasonEpisodePatterns(season, episode)
    val matches = files.filter { file ->
      val name = file.name?.lowercase() ?: return@filter false
      patterns.any { pattern -> name.contains(pattern) }
    }
    if (matches.isEmpty()) return null
    val tokens = preferTokens.map { it.trim().lowercase() }.filter { it.isNotBlank() }
    if (matches.size == 1 || tokens.isEmpty()) return matches.first()
    return matches.maxByOrNull { file ->
      val name = file.name?.lowercase() ?: ""
      tokens.count { token -> name.contains(token) }
    } ?: matches.first()
  }

  /** Seleziona il file video "migliore": unico video valido o dimensione maggiore. */
  fun selectBestVideo(files: List<DebridFile>): DebridFile? =
    files.filter { it.videoScore >= 0 }.maxByOrNull { it.videoScore }

  /** Pattern SxxExx / NxN usati per il match (stesso set di TorBoxRepository). */
  fun buildSeasonEpisodePatterns(season: Int, episode: Int): List<String> {
    val s = season.toString()
    val e = episode.toString()
    return listOf(
      "s${s.padStart(2, '0')}e${e.padStart(2, '0')}",
      "s${s.padStart(2, '0')}e$e",
      "s$s e$e",
      "${s}x${e.padStart(2, '0')}",
      "${s}x$e",
      "${s}${e.padStart(2, '0')}",
      "[$s.$e]",
      "${s}_$e",
      "Season.$s.Episode.$e",
      "Episode.$e",
    )
  }
}
