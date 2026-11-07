package com.example.data.prefs

import androidx.compose.runtime.Immutable
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.domain.model.Subtitle
import java.io.File

/**
 * Salvataggio persistente della selezione di un sottotitolo OpenSubtitles per un contenuto.
 *
 * Ogni istanza è univoca per film o episodio singolo, memorizzata in [AppSettingsRepository]
 * e sopravvive alla chiusura del player e al riavvio dell'app.
 *
 * @param contentId Identificativo univoco del contenuto:
 *                  - Film: `media.id` (es. "tmdb:123456" o "tt1234567")
 *                  - Episodio: `media.id::season:{season}:episode:{episode}` (es. "tmdb:123456::season:1:episode:5")
 * @param fileId Identificativo del file OpenSubtitles v3 (`attributes.files[].file_id`).
 *              Usato per re-scaricare il file se quello locale non è più disponibile.
 * @param lang Codice lingua del sottotitolo (es. "it", "en").
 * @param label Etichetta visualizzata all'utente (es. "Italiano", "Forzato").
 * @param localFilePath Percorso assoluto al file locale sottotitolo (file://).
 *                      Può essere nullo se il file è stato eliminato.
 * @param timestamp Unix timestamp (millisecondi) dell'ultimo salvataggio.
 */
@Immutable
data class PersistentSubtitleSelection(
  val contentId: String,
  val fileId: Long,
  val lang: String,
  val label: String?,
  val localFilePath: String?,
  val timestamp: Long
)

/**
 * Contratto dello store delle selezioni persistenti.
 *
 * L'interfaccia permette al ViewModel di essere verificato in unit test JVM con un fake,
 * senza dipendere da Android DataStore.
 */
interface PersistentSubtitleStore {

  /** Selezione salvata per il contenuto, oppure null. */
  fun getSelection(mediaItem: MediaItem, episode: Episode?): PersistentSubtitleSelection?

  /**
   * Come [getSelection], ma attende la prima lettura da disco.
   *
   * È il punto di ingresso usato all'apertura del player: dopo un riavvio completo
   * dell'app la cache in memoria può non essere ancora popolata, mentre il dato su
   * disco è già disponibile.
   */
  suspend fun getSelectionAwait(mediaItem: MediaItem, episode: Episode?): PersistentSubtitleSelection?

  /** Salva (o sostituisce) la selezione OpenSubtitles del contenuto. */
  fun saveSelection(
    mediaItem: MediaItem,
    episode: Episode?,
    subtitle: Subtitle,
    localFile: File?
  )

  /** Rimuove la selezione OpenSubtitles del contenuto (traccia nativa o "Nessuno"). */
  fun clearSelection(mediaItem: MediaItem, episode: Episode?)

  /** File locale ancora esistente per la selezione, oppure null. */
  fun getLocalFile(selection: PersistentSubtitleSelection): File?

  /** Ricostruisce il [Subtitle] da allegare al player a partire dalla selezione. */
  fun toSubtitle(selection: PersistentSubtitleSelection): Subtitle

  /** Directory permanente dei file sottotitolo (`filesDir/online_subtitles`). */
  fun subtitlesDir(): File?
}

/**
 * Regole pure sulla persistenza delle selezioni, verificabili senza Android.
 */
object PersistentSubtitleSelectionPolicy {

  /**
   * Indica se una scelta manuale nel menu tracce deve rimuovere l'associazione
   * OpenSubtitles salvata.
   *
   * - Nessuna selezione salvata → nulla da rimuovere.
   * - La traccia scelta è la stessa traccia persistente (stesso id) → l'associazione
   *   resta valida.
   * - Qualsiasi altra traccia (nativa, Stremio, oppure "Nessuno" con id nullo) →
   *   l'associazione precedente va rimossa.
   */
  fun shouldClearOnManualSelection(
    saved: PersistentSubtitleSelection?,
    selectedTrackId: String?
  ): Boolean {
    if (saved == null) return false
    return selectedTrackId != saved.contentId
  }
}

/**
 * Chiave di contenuto per la persistenza dei sottotitoli.
 *
 * Formato:
 * - Film: `media.id` (es. "tmdb:123456", "tt1234567")
 * - Episodio: `media.id::season:{seasonNumber}:episode:{episodeNumber}` (es. "tmdb:123456::season:1:episode:5")
 */
object SubtitleContentKey {

  /**
   * Genera la chiave di contenuto per un film.
   */
  fun forMovie(mediaId: String): String = mediaId

  /**
   * Genera la chiave di contenuto per un episodio.
   */
  fun forEpisode(mediaId: String, seasonNumber: Int, episodeNumber: Int): String =
    "$mediaId::season:$seasonNumber:episode:$episodeNumber"

  /**
   * Genera la chiave del contenuto in riproduzione: episodio se presente, film altrimenti.
   */
  fun forContent(mediaId: String, episode: Episode?): String =
    if (episode != null) {
      forEpisode(mediaId, episode.seasonNumber, episode.episodeNumber)
    } else {
      forMovie(mediaId)
    }

  /**
   * Estrae la chiave film (senza parte episodio) da una chiave completa.
   * Restituisce null se non è una chiave film.
   */
  fun extractMediaId(key: String): String? {
    if (key.contains("::season:") && key.contains(":episode:")) {
      return key.substringBefore("::season:")
    }
    return key
  }

  /**
   * Estrae stagione e numero episodio da una chiave episodio.
   * Restituisce null se non è una chiave episodio.
   */
  fun extractEpisodeNumbers(key: String): Pair<Int, Int>? {
    if (!key.contains("::season:") || !key.contains(":episode:")) return null
    val seasonStr = key.substringAfter("::season:").substringBefore(":episode:")
    val episodeStr = key.substringAfter(":episode:")
    val season = seasonStr.toIntOrNull() ?: return null
    val episode = episodeStr.toIntOrNull() ?: return null
    return season to episode
  }
}
