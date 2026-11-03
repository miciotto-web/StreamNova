package com.aistudio.streamnova.mobile.ui

import com.example.domain.model.IntroWindow
import java.util.Locale

/**
 * Politiche pure della UI del player Mobile (Fase 8.4).
 *
 * Nessuna dipendenza da Compose, dal Context o da Media3: le decisioni che la UI
 * applica (auto-hide, seek, etichette, zoom, autoplay) sono verificabili nei test
 * JVM e restano coerenti tra le diverse configurazioni del player.
 */
object MobilePlayerControlsPolicy {

  /** Intervallo di inattività prima che l'intera UI sparisca (4 secondi). */
  const val AUTO_HIDE_MS = 4_000L

  /** L'avviso di blocco (icona lucchetto) sparisce dopo 3 secondi. */
  const val LOCK_HINT_AUTO_HIDE_MS = 3_000L

  /** Passo dei pulsanti indietro/avanti e del doppio tap. */
  const val SEEK_STEP_MS = 10_000L

  /** Velocità offerte dal pannello Velocità. */
  val SPEED_OPTIONS: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
}

/**
 * Pannello impostazioni aperto sopra il player.
 *
 * `ROOT` è l'elenco principale; i valori successivi sono i sottopannelli. Un solo
 * pannello può essere aperto alla volta e nessuno di essi chiude la riproduzione.
 */
enum class MobilePlayerPanel {
  NONE,
  ROOT,
  QUALITY,
  AUDIO,
  SUBTITLES,
  SPEED,
  SERVERS,
  BUFFERING,
  DECODER,
  GESTURES,
  SCREEN_ON,
  ZOOM;

  val isOpen: Boolean get() = this != NONE
}

/** Modalità di adattamento video ciclate dal pulsante zoom. */
enum class MobileVideoScale {
  FIT,
  FILL,
  ZOOM,
  FIXED_WIDTH,
  FIXED_HEIGHT
}

/** Ordine di ciclo del pulsante zoom, identico a quello del player TV. */
fun MobileVideoScale.next(): MobileVideoScale = when (this) {
  MobileVideoScale.FIT -> MobileVideoScale.FILL
  MobileVideoScale.FILL -> MobileVideoScale.ZOOM
  MobileVideoScale.ZOOM -> MobileVideoScale.FIXED_WIDTH
  MobileVideoScale.FIXED_WIDTH -> MobileVideoScale.FIXED_HEIGHT
  MobileVideoScale.FIXED_HEIGHT -> MobileVideoScale.FIT
}

/**
 * L'auto-hide scatta solo quando la UI è visibile, il video sta andando, non c'è
 * un pannello aperto, non si sta trascinando la seek bar e non c'è un errore da
 * leggere. In pausa la UI resta visibile: nasconderla renderebbe il player
 * apparentemente bloccato.
 */
fun shouldAutoHidePlayerControls(
  controlsVisible: Boolean,
  isPlaying: Boolean,
  isLocked: Boolean,
  panel: MobilePlayerPanel,
  isScrubbing: Boolean,
  hasPlaybackError: Boolean
): Boolean {
  if (!controlsVisible) return false
  if (!isPlaying) return false
  if (isLocked) return false
  if (panel.isOpen) return false
  if (isScrubbing) return false
  if (hasPlaybackError) return false
  return true
}

/** Con i controlli bloccati l'unico elemento mostrabile è l'icona di sblocco. */
fun shouldShowLockedHint(lockHintVisible: Boolean, isLocked: Boolean): Boolean =
  isLocked && lockHintVisible

/** Il tasto Back del sistema deve funzionare anche con la UI nascosta. */
fun shouldHandleSystemBack(isPlayerVisible: Boolean): Boolean = isPlayerVisible

/** Nuova posizione dopo un salto, sempre dentro [0, duration]. */
fun playerSeekTarget(positionMs: Long, deltaMs: Long, durationMs: Long): Long {
  val upperBound = if (durationMs > 0L) durationMs else Long.MAX_VALUE
  return (positionMs + deltaMs).coerceIn(0L, upperBound)
}

/** `H:MM:SS` sopra l'ora, `MM:SS` sotto; i valori negativi diventano 0:00. */
fun formatPlayerTime(positionMs: Long): String {
  val totalSeconds = (positionMs.coerceAtLeast(0L) / 1_000L)
  val hours = totalSeconds / 3_600L
  val minutes = (totalSeconds % 3_600L) / 60L
  val seconds = totalSeconds % 60L
  return if (hours > 0L) {
    String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
  } else {
    String.format(Locale.US, "%02d:%02d", minutes, seconds)
  }
}

/** Etichetta breve di una risoluzione video reale (mai una stima della sorgente). */
fun playerQualityLabel(height: Int?): String = when {
  height == null || height <= 0 -> "Auto"
  height >= 2_160 -> "4K"
  height >= 1_080 -> "1080p"
  height >= 720 -> "720p"
  height >= 480 -> "480p"
  else -> "${height}p"
}

/**
 * Riepilogo della riga Qualità: con Auto si mostra anche la traccia video in uso
 * (es. `Auto • 1080p`), con una scelta manuale solo la risoluzione scelta.
 */
fun playerQualitySummary(isAuto: Boolean, currentHeight: Int?): String =
  if (isAuto) {
    "Auto • ${playerQualityLabel(currentHeight)}"
  } else {
    playerQualityLabel(currentHeight)
  }

/** Etichetta velocità: `0.75x`, `1.0x`, `1.25x`… */
fun formatPlayerSpeed(speed: Float): String {
  val rounded = (speed * 100f).toInt() / 100f
  val text = if (rounded == rounded.toInt().toFloat()) {
    String.format(Locale.US, "%.1f", rounded)
  } else {
    rounded.toString()
  }
  return "${text}x"
}

/** Velocità corrente arrotondata all'opzione più vicina (per il valore iniziale). */
fun nearestPlayerSpeed(speed: Float): Float =
  MobilePlayerControlsPolicy.SPEED_OPTIONS.minByOrNull { kotlin.math.abs(it - speed) }
    ?: 1.0f

/**
 * Il pulsante «Salta intro» segue le stesse regole del player TV: visibile solo
 * dentro una finestra reale e con 2,5 s di margine prima della fine, così la
 * sigla non viene tagliata. Nessuna stima euristica quando la finestra manca.
 */
fun shouldShowSkipIntro(positionMs: Long, window: IntroWindow?): Boolean {
  if (window == null || !window.isValid) return false
  return positionMs >= window.startMs &&
    positionMs < (window.endMs - INTRO_BUTTON_END_MARGIN_MS)
}

/** Margine prima della fine della sigla, allineato a `PlayerIntroUtils` della TV. */
const val INTRO_BUTTON_END_MARGIN_MS = 2_500L

/**
 * Avvio automatico dell'episodio successivo.
 *
 * [alreadyTriggered] è la guardia contro i molteplici eventi di fine riproduzione
 * (STATE_ENDED può arrivare più volte per lo stesso episodio): il primo evento
 * consuma il diritto all'avanzamento, i successivi vengono ignorati.
 */
fun shouldAutoPlayNextEpisode(
  enabled: Boolean,
  isTvShow: Boolean,
  hasNextEpisode: Boolean,
  alreadyTriggered: Boolean
): Boolean = enabled && isTvShow && hasNextEpisode && !alreadyTriggered

/** Lo schermo resta acceso durante la riproduzione; in pausa solo se richiesto. */
fun shouldKeepPlayerScreenOn(isPlaying: Boolean, keepScreenOnInPause: Boolean): Boolean =
  isPlaying || keepScreenOnInPause

/** Lingua leggibile di una traccia (codice ISO → nome), senza dipendere da Compose. */
fun playerTrackLanguageLabel(language: String?, label: String?): String {
  val code = language?.trim().orEmpty()
  val cleanLabel = label?.trim().orEmpty()
  // La lingua della traccia è l'informazione primaria (es. "Italiano"); il
  // label del container (spesso "AC3 5.1" o "SDH") resta come dettaglio.
  if (code.isNotEmpty() && !code.equals("und", ignoreCase = true)) {
    return com.example.domain.model.Subtitle(
      id = "",
      url = "",
      lang = code,
      addonName = ""
    ).getDisplayLanguage()
  }
  if (cleanLabel.isNotEmpty()) return cleanLabel
  return "—"
}

/** Formato compatto di una traccia audio/sottotitolo (`Italiano • AC-3 • 5.1`). */
fun playerTrackDetail(vararg parts: String?): String =
  parts.mapNotNull { it?.trim()?.takeIf { value -> value.isNotEmpty() } }
    .joinToString(" • ")
