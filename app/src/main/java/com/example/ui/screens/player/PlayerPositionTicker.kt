package com.example.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import com.example.domain.model.IntroWindow
import com.example.ui.viewmodel.PlayerPlaybackState
import kotlinx.coroutines.delay

/** Cadenza del polling della posizione (invariata rispetto alla Fase 5). */
internal const val PLAYER_POSITION_POLL_INTERVAL_MS = 500L

/**
 * Orologio di riproduzione del player (Fase 6.1).
 *
 * Prima di questa classe `currentPosition`/`totalDuration` erano `MutableState` letti nel
 * corpo di `PlayerScreen`: ogni tick del polling (500 ms) invalidava l'INTERA schermata,
 * anche l'`AndroidView` del player e gli overlay che non mostrano il tempo.
 *
 * Ora lo stato vive in questo holder `@Stable` e viene letto solo dove serve davvero:
 * - dai composable foglia che mostrano posizione/tempo (timeline, minutaggio);
 * - dai flag booleani derivati (`rememberIntroActive`, `rememberNextEpisodePromptVisible`),
 *   che invalidano il lettore solo quando il BOOLEANO cambia, non ad ogni tick.
 *
 * `PlayerScreen` non legge più questi valori direttamente: il tick periodico non causa
 * più ricomposizioni ampie.
 */
@Stable
internal class PlayerPlaybackClock(
  initialPositionMs: Long,
  initialDurationMs: Long
) {
  /** Ultima posizione nota (ms). Scritta solo quando il valore cambia realmente. */
  var positionMs by mutableLongStateOf(initialPositionMs.coerceAtLeast(0L))
    private set

  /** Ultima durata nota (ms), sempre >= 1 per non basare overlay/countdown su durate fittizie. */
  var durationMs by mutableLongStateOf(initialDurationMs.coerceAtLeast(1L))
    private set

  /**
   * Aggiorna la posizione SOLO se diversa da quella già nota.
   *
   * @return true se lo stato è stato scritto (cioè se i lettori vanno invalidati).
   */
  fun updatePosition(candidateMs: Long): Boolean {
    val sanitized = candidateMs.coerceAtLeast(0L)
    if (sanitized == positionMs) return false
    positionMs = sanitized
    return true
  }

  /**
   * Aggiorna la durata solo con un valore reale: `C.TIME_UNSET` (-1) e 0 significano
   * "durata non ancora nota" e NON devono sovrascrivere l'ultimo valore valido.
   *
   * @return true se lo stato è stato scritto.
   */
  fun updateDuration(candidateMs: Long): Boolean {
    if (candidateMs <= 0L || candidateMs == C.TIME_UNSET) return false
    if (candidateMs == durationMs) return false
    durationMs = candidateMs
    return true
  }
}

/**
 * Proiezione dello stato del ViewModel usata in COMPOSIZIONE (Fase 6.1).
 *
 * `updatePlaybackPosition` emette un nuovo `PlayerPlaybackState` ogni 500 ms durante la
 * riproduzione. Il corpo di `PlayerScreen` legge lo stato per media/URL/tracce/sottotitoli,
 * quindi senza questa proiezione ogni tick lo ricomporrebbe per intero (AndroidView e HUD
 * inclusi) pur essendo cambiata solo la posizione — che ora vive in [PlayerPlaybackClock].
 *
 * La proiezione azzera i soli campi aggiornati dal polling. Essendo un `data class`,
 * `derivedStateOf` (uguaglianza strutturale) NON invalida i lettori finché cambia solo la
 * posizione; qualsiasi cambio di contenuto/sorgente/tracce resta visibile come prima.
 *
 * La posizione REALE resta disponibile dove serve davvero (creazione del player, cambio
 * episodio/sorgente) leggendo `viewModel.playbackState.value`.
 */
internal fun playbackStateWithoutPosition(state: PlayerPlaybackState): PlayerPlaybackState =
  if (state.currentPositionMs == 0L && state.bufferedPositionMs == 0L) {
    state
  } else {
    state.copy(currentPositionMs = 0L, bufferedPositionMs = 0L)
  }

/**
 * Sorgente della posizione: interfaccia minima attorno a `Player`.
 *
 * Esiste per rendere il ticker verificabile nei test JVM senza costruire un ExoPlayer
 * (o un `Player` finto): il test fornisce una sorgente con valori controllati.
 */
internal interface PlayerPositionSource {
  /** Vero quando la riproduzione sta avanzando (falso in pausa e durante il buffering). */
  val isPlayingNow: Boolean

  fun readPositionMs(): Long

  fun readDurationMs(): Long
}

/** Adattatore di produzione: legge direttamente dall'istanza ExoPlayer della schermata. */
internal class ExoPlayerPositionSource(private val player: Player) : PlayerPositionSource {
  override val isPlayingNow: Boolean get() = player.isPlaying
  override fun readPositionMs(): Long = player.currentPosition
  override fun readDurationMs(): Long = player.duration
}

/**
 * Polling della posizione (composable figlio, Fase 6.1).
 *
 * - Scrive nell'orologio condiviso SOLO i valori cambiati ([PlayerPlaybackClock]).
 * - In pausa il ciclo NON esiste: l'effetto esce subito e alla ripresa viene rilanciato
 *   (`isPlaying` è una chiave). Nessun polling aggiuntivo, nessun campionamento a vuoto.
 * - Durante l'avvio/buffering (schermata in stato "playing" ma ExoPlayer non ancora in
 *   avanzamento) il ciclo resta in attesa senza scrivere stato: identico al comportamento
 *   precedente, che aggiornava solo quando `exoPlayer.isPlaying` era vero.
 * - [onSample] riceve posizione e durata come PARAMETRI: la chiusura non deve leggere lo
 *   stato dal corpo di `PlayerScreen`, altrimenti l'isolamento sarebbe vano.
 *
 * @param playerKey istanza del player: la chiave riavvia il campionamento quando il player
 *   viene ricreato, esattamente come prima.
 * @param isPlaying stato `isPlaying` della schermata (aggiornato da `onIsPlayingChanged`).
 */
@Composable
internal fun PlayerPositionTicker(
  playerKey: Any?,
  isPlaying: Boolean,
  source: PlayerPositionSource,
  clock: PlayerPlaybackClock,
  pollIntervalMs: Long = PLAYER_POSITION_POLL_INTERVAL_MS,
  onSample: (positionMs: Long, durationMs: Long) -> Unit
) {
  val sample by rememberUpdatedState(onSample)
  LaunchedEffect(playerKey, isPlaying) {
    // Pausa (o fine riproduzione): nessun polling finché `isPlaying` non torna vero.
    // Il valore già pubblicato resta quello letto nell'ultimo campione, come prima.
    if (!isPlaying) return@LaunchedEffect
    while (true) {
      if (source.isPlayingNow) {
        clock.updateDuration(source.readDurationMs())
        clock.updatePosition(source.readPositionMs())
        sample(clock.positionMs, clock.durationMs)
      }
      delay(pollIntervalMs)
    }
  }
}

/**
 * `isIntroActive` come stato DERIVATO dal booleano: `PlayerScreen` lo legge in composizione,
 * ma viene invalidata solo quando il pulsante/banner "Salta intro" deve davvero comparire o
 * sparire — non ad ogni tick di posizione.
 */
@Composable
internal fun rememberIntroActive(
  clock: PlayerPlaybackClock,
  isIntroDismissed: Boolean,
  introWindow: IntroWindow?
): Boolean {
  val state = remember(clock, isIntroDismissed, introWindow) {
    derivedStateOf {
      !isIntroDismissed && PlayerIntroUtils.isButtonVisible(clock.positionMs, introWindow)
    }
  }
  return state.value
}

/**
 * `isNextEpisodePromptVisible` come stato DERIVATO dal booleano, per la stessa ragione:
 * la schermata si ricompone quando l'overlay "Prossimo Episodio" compare o scompare, non
 * ad ogni tick della posizione.
 */
@Composable
internal fun rememberNextEpisodePromptVisible(
  clock: PlayerPlaybackClock,
  isScrubbing: Boolean,
  playbackEnded: Boolean,
  isTvShow: Boolean,
  hasNextEpisode: Boolean,
  dismissed: Boolean,
  playbackFailed: Boolean,
  hasActiveStream: Boolean = true,
  autoplayBlocked: Boolean = false,
  sourceSelectionActive: Boolean = false
): Boolean {
  val state = remember(
    clock,
    isScrubbing,
    playbackEnded,
    isTvShow,
    hasNextEpisode,
    dismissed,
    playbackFailed,
    hasActiveStream,
    autoplayBlocked,
    sourceSelectionActive
  ) {
    derivedStateOf {
      !isScrubbing && NextEpisodePromptUtils.shouldShow(
        positionMs = clock.positionMs,
        durationMs = clock.durationMs,
        playbackEnded = playbackEnded,
        isTvShow = isTvShow,
        hasNextEpisode = hasNextEpisode,
        dismissed = dismissed,
        playbackFailed = playbackFailed,
        hasActiveStream = hasActiveStream,
        autoplayBlocked = autoplayBlocked,
        sourceSelectionActive = sourceSelectionActive
      )
    }
  }
  return state.value
}
