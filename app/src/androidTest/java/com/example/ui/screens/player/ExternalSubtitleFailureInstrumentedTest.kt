package com.example.ui.screens.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * PROVA SULL'EMULATORE del comportamento del player con una traccia sottotitoli
 * esterna, usando la factory Media3 REALE dell'app ([resolvePlaybackMediaSourceFactory])
 * e la stessa [PlayerLoadErrorHandlingPolicy].
 *
 * Il video e le sidecar remote sono serviti dal server di prova sull'host e raggiunti
 * dall'emulatore tramite `adb reverse tcp:8099 tcp:8099`: lo scenario e' deterministico
 * e non dipende dalla rete pubblica.
 *
 * Verifica:
 *  1. una traccia sottotitoli esterna valida viene DECODIFICATA (non e' piu' un errore
 *     fatale "legacy decoding is disabled") e il video prosegue;
 *  2. una traccia esterna che FALLISCE viene attribuita alla traccia (non alla sorgente
 *     video): il video continua a riprodurre e non diventa nero;
 *  3. un errore della SORGENTE VIDEO resta un errore della sorgente video: la gestione
 *     normale continua ad applicarsi.
 *
 * Media3 impone l'accesso al player dal main thread: ogni interazione con il player
 * passa quindi da [onMain].
 */
@RunWith(AndroidJUnit4::class)
class ExternalSubtitleFailureInstrumentedTest {

  private val mainHandler = Handler(Looper.getMainLooper())

  private val targetContext: Context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

  private fun onMain(block: () -> Unit) {
    val done = CountDownLatch(1)
    mainHandler.post {
      try {
        block()
      } finally {
        done.countDown()
      }
    }
    assertTrue("Timeout nell'esecuzione sul main thread", done.await(30, TimeUnit.SECONDS))
  }

  /** Factory identica a quella dell'app: HLS/progressiva + merge delle sidecar. */
  private fun buildProductionPlayer(context: Context): ExoPlayer {
    val httpFactory = DefaultHttpDataSource.Factory()
      .setUserAgent("StreamNova-Test/1.0")
      .setAllowCrossProtocolRedirects(true)
    val playbackDataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
    val policy = PlayerLoadErrorHandlingPolicy()
    val progressiveFactory = DefaultMediaSourceFactory(context)
      .setDataSourceFactory(playbackDataSourceFactory)
      .setLoadErrorHandlingPolicy(policy)
    val playbackFactory = resolvePlaybackMediaSourceFactory(
      isHlsStream = false,
      httpDataSourceFactory = playbackDataSourceFactory,
      progressiveMediaSourceFactory = progressiveFactory,
      loadErrorHandlingPolicy = policy
    )
    return ExoPlayer.Builder(context)
      .setMediaSourceFactory(playbackFactory)
      .build()
  }

  private fun writeFile(context: Context, name: String, content: ByteArray): File =
    File(context.cacheDir, name).apply { writeBytes(content) }

  /** Descrizione sanitizzata dell'errore: nessun URL completo, nessun header. */
  private fun describe(error: PlaybackException): String {
    val exo = error as? ExoPlaybackException
    val chain = generateSequence<Throwable>(error) { it.cause }
      .take(6)
      .mapIndexed { i, c -> "#$i ${c.javaClass.name}: ${c.message?.take(160).orEmpty()}" }
      .toList()
    return "code=${error.errorCodeName}(${error.errorCode}) " +
      "exoType=${exo?.type ?: "n/a"} renderer=${exo?.rendererName ?: "n/a"} " +
      "mime=${exo?.rendererFormat?.sampleMimeType ?: "n/a"} " +
      "causeChain=[${chain.joinToString(" << ")}]"
  }

  private fun textTrackInfo(tracks: Tracks): String {
    val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT } ?: return "none"
    val format = group.getTrackFormat(0)
    return "lang=${format.language} selected=${group.isTrackSelected(0)} mime=${format.sampleMimeType}"
  }

  // ── 1. Traccia esterna valida: viene decodificata e il video prosegue ─────────

  @Test
  fun tracciaEsternaValida_vieneDecodificataEIlVideoProsegue() {
    val context = targetContext
    val player = buildProductionPlayer(context)
    val errorLatch = CountDownLatch(1)
    var captured: PlaybackException? = null
    onMain {
      player.addListener(object : Player.Listener {
        override fun onPlayerError(e: PlaybackException) {
          captured = e
          errorLatch.countDown()
        }
      })
      player.setMediaItem(
        MediaItem.Builder()
          .setUri(Uri.parse("$BASE/video.mp4"))
          .setSubtitleConfigurations(
            listOf(
              MediaItem.SubtitleConfiguration.Builder(Uri.parse("$BASE/sub_ok.srt"))
                .setMimeType("application/x-subrip")
                .setLanguage("it")
                .setId("os-ok")
                .build()
            )
          )
          .build()
      )
      player.prepare()
      player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .setPreferredTextLanguage("it")
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .build()
      player.play()
    }
    val errored = errorLatch.await(25, TimeUnit.SECONDS)
    Thread.sleep(5000)

    var state = 0
    var position = 0L
    var tracks = Tracks(emptyList())
    onMain {
      state = player.playbackState
      position = player.currentPosition
      tracks = player.currentTracks
      player.release()
    }
    val textTrack = textTrackInfo(tracks)
    Log.i(TAG, "VALID: errored=$errored error=${captured?.let { describe(it) } ?: "none"} " +
      "state=$state pos=$position textTrack=[$textTrack]")

    assertFalse("una traccia esterna valida non deve generare errori (errore=${captured?.let { describe(it) }})", errored)
    // Il video di prova dura 6 secondi: puo' essere ancora in riproduzione (READY) o
    // gia' finito (ENDED). In entrambi i casi deve aver ADVANZATO e non essere andato
    // in errore.
    assertTrue(
      "il video deve essere in riproduzione o concluso (state=$state)",
      state == Player.STATE_READY || state == Player.STATE_ENDED
    )
    assertTrue("il video deve avanzare (pos=$position)", position > 0L)
    assertTrue("la traccia di testo esterna deve essere presente: $textTrack", textTrack != "none")
    assertTrue(
      "la traccia di testo esterna deve essere selezionata e in formato Cues: $textTrack",
      textTrack.contains("selected=true") && textTrack.contains("application/x-media3-cues")
    )
  }

  // ── 2. Traccia esterna che fallisce: il video continua, non diventa nero ──────

  @Test
  fun tracciaEsternaCheFallisce_ilVideoContinuaANonDiventareNero() {
    val context = targetContext
    val player = buildProductionPlayer(context)
    val errorLatch = CountDownLatch(1)
    var captured: PlaybackException? = null
    onMain {
      player.addListener(object : Player.Listener {
        override fun onPlayerError(e: PlaybackException) {
          captured = e
          errorLatch.countDown()
        }
      })
      player.setMediaItem(
        MediaItem.Builder()
          .setUri(Uri.parse("$BASE/video.mp4"))
          .setSubtitleConfigurations(
            listOf(
              // La sidecar risponde 404: il caricamento della TRACCIA fallisce.
              MediaItem.SubtitleConfiguration.Builder(Uri.parse("$BASE/sub_404.srt"))
                .setMimeType("application/x-subrip")
                .setLanguage("it")
                .setId("os-404")
                .build()
            )
          )
          .build()
      )
      player.prepare()
      player.play()
    }
    val errored = errorLatch.await(25, TimeUnit.SECONDS)
    assertTrue("lo scenario deve produrre un errore (nessun errore: il test non prova niente)", errored)

    val error = requireNotNull(captured)
    // ── Attribuzione: lo stesso codice usato dal gestore degli errori del player ──
    // Nota: quando il fallimento avviene durante il prepare il player non ha una
    // timeline, quindi `currentMediaItem` e' null: l'elenco delle tracce allegate e'
    // quello del modello (aggiornato), come nel player reale.
    val subtitle = com.example.domain.model.Subtitle(
      id = "os-404",
      url = "$BASE/sub_404.srt",
      lang = "ita",
      addonName = "OpenSubtitles v3"
    )
    val attachedUris = listOf(subtitle.url)
    val videoUri = "$BASE/video.mp4"
    val verdict = PlayerExternalSubtitleAttribution.attribute(
      error = error,
      videoUri = videoUri,
      externalSubtitleUris = attachedUris
    )
    Log.i(TAG, "FAILING: error=${describe(error)} attribution=${verdict.attribution} " +
      "failedSubtitle=${verdict.failedSubtitleUri != null} " +
      "attachedUris=${attachedUris.size} " +
      "failingUri=${PlayerExternalSubtitleAttribution.failingUriOf(error)} " +
      "videoUri=${videoUri}")

    assertEquals(
      "un errore di una traccia esterna deve essere attribuito alla traccia, non alla sorgente video",
      PlayerExternalSubtitleAttribution.Attribution.EXTERNAL_SUBTITLE,
      verdict.attribution
    )

    // ── Recupero: lo stesso codice usato dal gestore degli errori del player ────
    val action = PlayerSubtitleErrorRecovery.decide(
      failedSubtitleUri = verdict.failedSubtitleUri,
      attachedUris = attachedUris,
      streamProvidedIds = emptySet(),
      recoveriesExhausted = false
    )
    val remaining = PlayerSubtitleErrorRecovery.remainingSubtitles(
      action = action,
      subtitles = listOf(subtitle),
      failedSubtitleUri = verdict.failedSubtitleUri
    )
    var wasPlaying = false
    onMain { wasPlaying = player.playWhenReady }
    val recovered = onMainGet {
      PlayerSubtitleErrorRecovery.apply(
        player = player,
        action = action,
        remainingSubtitles = remaining,
        videoUrl = videoUri,
        wasPlaying = wasPlaying
      )
    }
    assertTrue("il player deve essere ripreso", recovered)

    // Il video deve ripartire e proseguire: NESSUN cambio sorgente, NESSuno schermo nero.
    Thread.sleep(6000)
    var state = 0
    var position = 0L
    onMain {
      state = player.playbackState
      position = player.currentPosition
      player.release()
    }
    Log.i(TAG, "FAILING-RECOVERED: action=$action state=$state pos=$position")
    assertTrue(
      "il video deve essere in riproduzione dopo il recupero (state=$state)",
      state == Player.STATE_READY || state == Player.STATE_ENDED
    )
    assertTrue("il video deve avanzare dopo il recupero (pos=$position)", position > 0L)
  }

  // ── 3. Un errore della sorgente video resta un errore della sorgente video ────

  @Test
  fun erroreDellaSorgenteVideo_RestaAttribuitoAlVideo() {
    val context = targetContext
    val player = buildProductionPlayer(context)
    val errorLatch = CountDownLatch(1)
    var captured: PlaybackException? = null
    onMain {
      player.addListener(object : Player.Listener {
        override fun onPlayerError(e: PlaybackException) {
          captured = e
          errorLatch.countDown()
        }
      })
      // Il VIDEO risponde 404: l'errore NON deve essere mascherato come errore
      // sottotitoli, altrimenti un video irraggiungibile sembrerebbe un problema di
      // sottotitoli.
      player.setMediaItem(MediaItem.Builder().setUri(Uri.parse("$BASE/video_404.mp4")).build())
      player.prepare()
      player.play()
    }
    val errored = errorLatch.await(25, TimeUnit.SECONDS)
    val error = requireNotNull(captured)

    var currentItem: MediaItem? = null
    onMain { currentItem = player.currentMediaItem }
    val attachedUris = currentItem?.localConfiguration?.subtitleConfigurations
      ?.map { it.uri.toString() }.orEmpty()
    val videoUri = currentItem?.localConfiguration?.uri?.toString() ?: "$BASE/video_404.mp4"
    val verdict = PlayerExternalSubtitleAttribution.attribute(
      error = error,
      videoUri = videoUri,
      externalSubtitleUris = attachedUris
    )
    Log.i(TAG, "VIDEO-ERROR: errored=$errored error=${describe(error)} attribution=${verdict.attribution}")

    assertTrue("un video irraggiungibile deve produrre un errore", errored)
    assertFalse(
      "un errore della sorgente video NON deve essere attribuito ai sottotitoli",
      verdict.isExternalSubtitle
    )
    assertEquals(
      PlayerExternalSubtitleAttribution.Attribution.VIDEO_SOURCE,
      verdict.attribution
    )
    onMain { player.release() }
  }

  private fun onMainGet(block: () -> Boolean): Boolean {
    var value = false
    onMain { value = block() }
    return value
  }

  companion object {
    private const val TAG = "SN_SUB_REPRO"
    private const val BASE = "http://127.0.0.1:8099"
  }
}
