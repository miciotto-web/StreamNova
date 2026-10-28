package com.example.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.domain.model.Subtitle

/**
 * Recupero della riproduzione video dopo un errore attribuito a una **traccia
 * sottotitoli esterna**.
 *
 * Regola fondamentale: un errore che riguarda una traccia esterna NON cambia la
 * sorgente e NON interrompe il video. La traccia problematica viene rimossa (o, se non
 * e' identificabile, vengono staccate tutte le tracce esterne) e la riproduzione
 * riprende.
 *
 * Il componente e' separato dal composable perche':
 *  - la decisione e' pura e verificabile in test JVM;
 *  - l'applicazione al player reale e' verificabile su emulatore con lo STESSO codice
 *    che gira in produzione.
 *
 * Nota: quando il fallimento avviene durante il `prepare()` il player non ha ancora una
 * timeline, quindi `currentMediaItem` e' `null`: per questo il `MediaItem` viene
 * RICOSTRUITO dall'elenco dei sottotitoli (che e' sempre aggiornato) e non letto dal
 * player.
 */
@UnstableApi
internal object PlayerSubtitleErrorRecovery {

  /** Azione di recupero da applicare al player. */
  enum class Action {
    /** Stacca la traccia fallita e riprende con le altre. */
    DROP_FAILED_TRACK,

    /** La sorgente della traccia fallita non e' piu' allegata: riprende il video. */
    RESUME_VIDEO,

    /** Stacca ogni traccia sottotitoli esterna e riprende. */
    DROP_ALL_EXTERNAL_TRACKS,

    /** Non ci sono tracce esterne da staccare: disattiva il renderer di testo. */
    DISABLE_TEXT_RENDERER
  }

  /**
   * Decide l'azione di recupero.
   *
   * @param failedSubtitleUri URI della traccia fallita, quando identificabile.
   * @param attachedUris URI delle tracce sottotitoli esterne allegate al `MediaItem`.
   * @param streamProvidedIds id delle tracce dichiarate dal flusso (embedded): vengono
   *   conservate quando la traccia fallita non e' identificabile con precisione.
   * @param recoveriesExhausted `true` quando i tentativi di ripresa sono esauriti: in
   *   quel caso si stacca ogni traccia esterna, cosi' l'errore non puo' ripetersi.
   */
  fun decide(
    failedSubtitleUri: String?,
    attachedUris: List<String>,
    streamProvidedIds: Set<String>,
    recoveriesExhausted: Boolean
  ): Action {
    val failingAttached = failedSubtitleUri?.let { uri -> attachedUris.firstOrNull { it == uri } }
    if (failingAttached != null) {
      return if (recoveriesExhausted) Action.DROP_ALL_EXTERNAL_TRACKS else Action.DROP_FAILED_TRACK
    }
    if (failedSubtitleUri != null) {
      // La traccia fallita non e' piu' allegata: il video basta riprenderlo.
      return if (recoveriesExhausted) Action.DROP_ALL_EXTERNAL_TRACKS else Action.RESUME_VIDEO
    }
    // Errore di una traccia sottotitoli non identificabile con precisione: si staccano
    // le tracce esterne che non sono dichiarate dal flusso.
    return when {
      recoveriesExhausted && attachedUris.isNotEmpty() -> Action.DROP_ALL_EXTERNAL_TRACKS
      attachedUris.size > streamProvidedIds.size -> Action.DROP_FAILED_TRACK
      attachedUris.isNotEmpty() -> Action.DROP_ALL_EXTERNAL_TRACKS
      else -> Action.DISABLE_TEXT_RENDERER
    }
  }

  /**
   * Sottotitoli da tenere allegati al `MediaItem` dopo il recupero.
   *
   * @param action azione decisa da [decide].
   * @param subtitles sottotitoli attualmente allegati (elenco aggiornato).
   * @param failedSubtitleUri URI della traccia fallita, quando identificabile.
   */
  fun remainingSubtitles(
    action: Action,
    subtitles: List<Subtitle>,
    failedSubtitleUri: String?
  ): List<Subtitle> = when (action) {
    Action.DROP_FAILED_TRACK -> when {
      failedSubtitleUri != null ->
        subtitles.filterNot { sameUri(it.url, failedSubtitleUri) }
      else ->
        subtitles.filter { it.isStreamProvided }
    }
    Action.DROP_ALL_EXTERNAL_TRACKS -> subtitles.filter { it.isStreamProvided }
    else -> subtitles
  }

  /**
   * Applica il recupero al player.
   *
   * @param remainingSubtitles sottotitoli da tenere allegati (vedi [remainingSubtitles]).
   * @param videoUrl URL della sorgente video da ricaricare con le tracce rimaste.
   * @return `true` se il player e' stato ripreso: l'errore va considerato gestito e non
   *   deve essere inoltrato ai gestori della sorgente video.
   */
  fun apply(
    player: ExoPlayer,
    action: Action,
    remainingSubtitles: List<Subtitle>,
    videoUrl: String,
    wasPlaying: Boolean
  ): Boolean {
    val currentPos = player.currentPosition
    when (action) {
      Action.DROP_FAILED_TRACK, Action.DROP_ALL_EXTERNAL_TRACKS -> {
        if (videoUrl.isBlank()) return false
        player.setMediaItem(
          PlayerSubtitleMediaItemBuilder.buildMediaItem(videoUrl, remainingSubtitles)
        )
        if (currentPos > 0) player.seekTo(currentPos)
      }
      Action.RESUME_VIDEO -> Unit
      Action.DISABLE_TEXT_RENDERER -> {
        player.trackSelectionParameters = player.trackSelectionParameters
          .buildUpon()
          .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
          .build()
      }
    }
    player.prepare()
    player.playWhenReady = wasPlaying
    return true
  }

  /** Confronto tra URI: query string e frammento non contano, lo schema no. */
  private fun sameUri(a: String, b: String): Boolean = normalizeUri(a) == normalizeUri(b)

  private fun normalizeUri(raw: String): String {
    val trimmed = raw.trim().substringBefore('#').substringBefore('?')
    val schemeEnd = trimmed.indexOf("://")
    return if (schemeEnd > 0) {
      trimmed.substring(0, schemeEnd).lowercase() + trimmed.substring(schemeEnd)
    } else {
      trimmed
    }
  }
}
