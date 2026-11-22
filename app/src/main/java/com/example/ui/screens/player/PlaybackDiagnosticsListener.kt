package com.example.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import com.example.data.streaming.StreamResolution
import com.example.data.streaming.diagnostics.playback.HlsPlaybackSession
import com.example.data.streaming.diagnostics.playback.ObservedTrackFormat
import com.example.data.streaming.diagnostics.playback.PlaybackErrorInput
import com.example.data.streaming.diagnostics.playback.PlaybackPhase
import com.example.data.streaming.diagnostics.playback.PlaybackStateKind
import com.example.data.streaming.diagnostics.playback.SourceKind

/**
 * Traduttore degli eventi Media3 verso [HlsPlaybackSession].
 *
 * NON decide nulla e non altera il comportamento del player: osserva e
 * inoltra. È l'unico punto di traduzione Media3 → diagnostica (niente
 * duplicazione con [StreamNova4KDiag], che resta un logger di supporto).
 *
 * Limiti dichiarati (limiti Media3, non nasconduti):
 *  - `C.DATA_TYPE_MANIFEST` copre SIA il manifest iniziale SIA le playlist di
 *    variante/canale: le richieste "initial" sono registrate come
 *    [PlaybackPhase.MANIFEST], senza dedurre il tipo da confronti di URI;
 *  - il codice di risposta HTTP non è esposto dal fork per i caricamenti
 *    riusciti: su successo [com.example.data.streaming.diagnostics.playback.SessionHttpEvent.httpStatus]
 *    resta null (lo status entra nei report solo sugli errori);
 *  - il numero di redirect non è esposto da Media3: resta 0.
 *
 * Gli errori di codec **non fatali** (onVideoCodecError/onAudioCodecError)
 * vengono ignorati qui: la diagnostica li ha già in forma di log
 * (StreamNova4KDiag) e un errore decoder che interrompe la riproduzione
 * arriva comunque come onPlayerError con i codici DECODING o AUDIO_TRACK:
 * registrarli due volte gonfierebbe i contatori senza aggiungere evidenze.
 */
@UnstableApi
class PlaybackDiagnosticsListener(
  /**
   * Sessione corrente letta a ogni evento (A-01): il player è riusato tra
   * riproduzioni, la sessione no — il provider segue la riproduzione senza
   * ri-agganciare il listener.
   */
  private val sessionProvider: () -> HlsPlaybackSession?
) : AnalyticsListener {

  /** Fase dell'ultimo caricamento osservato, usata per attribuire gli errori. */
  private var lastLoadPhase: PlaybackPhase = PlaybackPhase.UNKNOWN

  /**
   * Ultimo errore di caricamento già registrato in sessione (A-02): lo stesso
   * fallimento terminale arriva due volte — [onLoadError] durante il caricamento
   * e [onPlayerError] alla propagazione alla riproduzione — e va classificato
   * UNA sola volta, altrimenti `attemptCount` conta callback, non fallimenti.
   */
  private var lastRecordedLoadError: IOException? = null

  override fun onLoadStarted(
    eventTime: AnalyticsListener.EventTime,
    loadEventInfo: LoadEventInfo,
    mediaLoadData: MediaLoadData
  ) {
    lastLoadPhase = phaseOf(mediaLoadData)
  }

  override fun onLoadCompleted(
    eventTime: AnalyticsListener.EventTime,
    loadEventInfo: LoadEventInfo,
    mediaLoadData: MediaLoadData
  ) {
    val session = sessionProvider() ?: return
    val phase = phaseOf(mediaLoadData)
    lastLoadPhase = phase
    session.onHttpCompleted(
      url = loadEventInfo.uri.toString(),
      phase = phase,
      httpStatus = null,
      durationMs = loadEventInfo.loadDurationMs,
      contentType = contentTypeOf(loadEventInfo),
      bytesLoaded = loadEventInfo.bytesLoaded,
      redirectCount = 0,
      isError = false
    )
  }

  override fun onLoadError(
    eventTime: AnalyticsListener.EventTime,
    loadEventInfo: LoadEventInfo,
    mediaLoadData: MediaLoadData,
    error: IOException,
    wasCanceled: Boolean
  ) {
    val phase = phaseOf(mediaLoadData)
    lastLoadPhase = phase
    val session = sessionProvider() ?: return
    session.onHttpCompleted(
      url = loadEventInfo.uri.toString(),
      phase = phase,
      httpStatus = responseCodeOf(error),
      durationMs = loadEventInfo.loadDurationMs,
      contentType = contentTypeOf(loadEventInfo),
      bytesLoaded = loadEventInfo.bytesLoaded,
      redirectCount = 0,
      isError = true
    )
    if (wasCanceled) return
    session.onError(
      input = PlaybackErrorInput(
        media3ErrorCode = null,
        httpStatus = responseCodeOf(error),
        exceptionClassNames = classChainOf(error),
        exceptionMessage = error.message,
        phase = phase,
        canceled = false
      ),
      phase = phase
    )
    lastRecordedLoadError = error
  }

  override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
    val session = sessionProvider() ?: return
    // Il fork esponde posizione e buffer direttamente in EventTime (niente
    // riferimento al player: gli eventi restano pura osservazione).
    session.onPlayerStateChanged(
      newState = when (state) {
        Player.STATE_BUFFERING -> PlaybackStateKind.BUFFERING
        Player.STATE_READY -> PlaybackStateKind.READY
        Player.STATE_ENDED -> PlaybackStateKind.ENDED
        else -> PlaybackStateKind.IDLE
      },
      positionMs = eventTime.currentPlaybackPositionMs,
      bufferedDurationMs = eventTime.totalBufferedDurationMs
    )
  }

  override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
    val exoError = error as? ExoPlaybackException
    // A-02: se la causa dell'errore player è l'eccezione di load già registrata
    // da [onLoadError], è la STESSA occorrenza fisica che ha terminato la
    // riproduzione: non va riclassificata (contatore gonfiato e confidenza
    // elevata al primo fallimento). Gli errori senza quell'identità (es.
    // behind-live-window, runtime) continuano a essere classificati.
    if (exoError?.type == ExoPlaybackException.TYPE_SOURCE &&
      generateSequence<Throwable>(error) { it.cause }.take(MAX_CAUSE_DEPTH)
        .any { it === lastRecordedLoadError }
    ) {
      return
    }
    val session = sessionProvider() ?: return
    val phase = when (exoError?.type) {
      ExoPlaybackException.TYPE_RENDERER -> PlaybackPhase.DECODE
      else -> lastLoadPhase
    }
    session.onError(
      input = PlaybackErrorInput(
        media3ErrorCode = error.errorCode,
        httpStatus = responseCodeOf(error),
        exceptionClassNames = classChainOf(error),
        exceptionMessage = error.message,
        phase = phase,
        canceled = false
      ),
      phase = phase
    )
  }

  override fun onVideoSizeChanged(
    eventTime: AnalyticsListener.EventTime,
    videoSize: VideoSize
  ) {
    val session = sessionProvider() ?: return
    if (videoSize.width > 0 || videoSize.height > 0) {
      session.onVideoSizeChanged(videoSize.width, videoSize.height)
    }
  }

  override fun onRenderedFirstFrame(
    eventTime: AnalyticsListener.EventTime,
    output: Any,
    renderTimeMs: Long
  ) {
    sessionProvider()?.onFirstFrameRendered()
  }

  override fun onTracksChanged(
    eventTime: AnalyticsListener.EventTime,
    tracks: Tracks
  ) {
    val session = sessionProvider() ?: return
    var video: ObservedTrackFormat? = null
    var audio: ObservedTrackFormat? = null
    var subtitle: ObservedTrackFormat? = null

    for (group in tracks.groups) {
      if (group.length == 0) continue
      var selectedIndex = -1
      for (index in 0 until group.length) {
        if (group.isTrackSelected(index)) {
          selectedIndex = index
          break
        }
      }
      if (selectedIndex < 0) continue
      val format = group.getTrackFormat(selectedIndex)
      val observed = format.toObservedTrack(group.type)
      when (group.type) {
        C.TRACK_TYPE_VIDEO -> {
          video = observed
          // Il formato della traccia video selezionata decide l'etichetta della
          // variante: etichette dal punto UNICO (StreamResolution), mai soglie locali.
          session.setSelectedResolution(
            StreamResolution.labelForMeasured(format.width, format.height)
          )
          session.onActiveTrackCodec(
            sampleMimeType = format.sampleMimeType,
            codecsString = format.codecs
          )
        }
        C.TRACK_TYPE_AUDIO -> audio = observed
        C.TRACK_TYPE_TEXT -> subtitle = observed
      }
    }
    session.onObservedTracks(video = video, audio = audio, subtitle = subtitle)
  }

  // --------------------------------------------------------------------------
  // Nova Playback Inspector: metriche live
  // --------------------------------------------------------------------------

  override fun onDroppedVideoFrames(
    eventTime: AnalyticsListener.EventTime,
    droppedFrames: Int,
    elapsedMs: Long
  ) {
    sessionProvider()?.onDroppedVideoFrames(droppedFrames)
  }

  override fun onBandwidthEstimate(
    eventTime: AnalyticsListener.EventTime,
    totalLoadTimeMs: Int,
    totalBytesLoaded: Long,
    bitrateEstimate: Long
  ) {
    sessionProvider()?.onBandwidthEstimate(bitrateEstimate)
  }

  /** Traduce il `Format` Media3 nel modello osservabile del pannello Inspector. */
  private fun androidx.media3.common.Format.toObservedTrack(
    trackType: Int
  ): ObservedTrackFormat {
    val kind = when (trackType) {
      C.TRACK_TYPE_VIDEO -> "video"
      C.TRACK_TYPE_AUDIO -> "audio"
      C.TRACK_TYPE_TEXT -> "text"
      else -> "other"
    }
    return ObservedTrackFormat(
      kind = kind,
      codecs = codecs,
      sampleMimeType = sampleMimeType,
      language = language,
      label = label,
      width = width,
      height = height,
      frameRate = frameRate.takeIf { it > 0f },
      bitrateBps = bitrate.takeIf { it > 0 }?.toLong(),
      channelCount = channelCount.takeIf { it > 0 },
      sampleRateHz = sampleRate.takeIf { it > 0 }
    )
  }

  // --------------------------------------------------------------------------
  // Traduzioni
  // --------------------------------------------------------------------------

  /** Fase di caricamento dal `dataType` Media3 (limiti dichiarati in alto). */
  private fun phaseOf(mediaLoadData: MediaLoadData): PlaybackPhase = when (mediaLoadData.dataType) {
    C.DATA_TYPE_MANIFEST -> PlaybackPhase.MANIFEST
    C.DATA_TYPE_MEDIA_INITIALIZATION -> PlaybackPhase.INIT_SEGMENT
    C.DATA_TYPE_MEDIA -> PlaybackPhase.MEDIA_SEGMENT
    else -> PlaybackPhase.UNKNOWN
  }

  /** Content-Type della risposta (il fork espone gli header in LoadEventInfo). */
  private fun contentTypeOf(loadEventInfo: LoadEventInfo): String? = try {
    loadEventInfo.responseHeaders.entries
      .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
      ?.value?.firstOrNull()
  } catch (e: Exception) {
    null
  }

  /** Codice HTTP solo per gli errori di risposta (`InvalidResponseCodeException`). */
  private fun responseCodeOf(error: Throwable): Int? = generateSequence<Throwable>(error) { it.cause }
    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
    .firstOrNull()
    ?.responseCode

  /** Catena delle classi di eccezione (dall'errore verso la causa radice). */
  private fun classChainOf(error: Throwable): List<String> =
    generateSequence<Throwable>(error) { it.cause }.take(MAX_CAUSE_DEPTH).map { it.javaClass.simpleName }.toList()

  private companion object {
    /** Profondità massima della catena cause registrata. */
    const val MAX_CAUSE_DEPTH = 8
  }
}

/** Kind di sorgente derivato dall'URL corrente (usato all'aggancio del listener). */
fun sourceKindOf(isHls: Boolean): SourceKind =
  if (isHls) SourceKind.HLS else SourceKind.PROGRESSIVE
