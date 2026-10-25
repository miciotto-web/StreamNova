package com.example.ui.screens.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Estrattore di fotogrammi **dedicato**: un secondo [ExoPlayer] che decodifica la stessa
 * sorgente del player principale e renderizza su una [ImageReader] fuori schermo.
 *
 * Perché non la superficie del player principale (vedi diagnosi):
 * `PixelCopy` dalla superficie del player in uso richiede di spostare il player stesso su
 * ogni posizione. Su HLS ogni seek costa 0.5-1.7 s (fetch segmento + flush decoder): il
 * worker pubblica un frame ogni ~1 s, sempre per una posizione già superata dallo slider,
 * e il timestamp mostrato non è quello del fotogramma. Con un decoder separato il player
 * principale non viene mai toccato (pausa/seek/ripresa restano quelli dello scrubbing) e
 * il frame costa 60-300 ms, con la possibilità di prefetchare le posizioni adiacenti.
 *
 * Compatibilità: usa lo **stesso** [MediaSource.Factory] e la stessa
 * `DataSource.Factory` del player principale, quindi eredita URL, header HTTP, cookie,
 * redirect cross-protocol, proxy, HLS/DASH e DRM esattamente come il player che sta
 * riproducendo. Non introduce dipendenze: è tutto Media3 già presente.
 *
 * Limiti dichiarati:
 * - apre una seconda connessione alla sorgente durante lo scrubbing (la riproduzione è in
 *   pausa, quindi non c'è contesa di banda con il player principale, ma un hoster che
 *   limita le sessioni concorrenti può rifiutarla: in quel caso `load` restituisce null e
 *   l'anteprima mostra lo stato onesto "non disponibile");
 * - il decoder video viene allocato solo alla prima richiesta e rilasciato con [release];
 *   il primo frame di una sessione include il prepare (~0.5-1.5 s su rete reale);
 * - per sorgenti DRM il secondo player apre una seconda sessione DRM: se il server limita
 *   le sessioni concorrenti la cattura fallisce (nessuna immagine sbagliata mostrata).
 *
 * Il fotogramma pubblicato porta la **posizione realmente decodificata** letta dal
 * timestamp di presentazione del renderer (`onVideoFrameAboutToBeRendered`), mai la
 * posizione richiesta: se il decoder atterra su un keyframe precedente, la miniatura lo
 * dichiara invece di etichettare un'immagine vecchia con un tempo nuovo.
 *
 * **Verifica di piattaforma.** Su alcune implementazioni di Fire OS la lettura delle plane
 * della `Image` non è un'operazione recuperabile: `image.planes` aborta il processo nel codice
 * nativo di `libmedia_jni` (vedi [SeekPreviewCaptureSupport]). Dove la piattaforma è
 * dichiarata non compatibile questa classe **non crea la ImageReader** e ricade sul percorso
 * GL [SeekPreviewGlFrameGrabber] (SurfaceTexture + `glReadPixels`): stesso decoder dedicato,
 * stesso contratto, ma nessuna `Image` e nessuna plane. L'anteprima resta quindi REALE anche
 * su Fire TV; solo un fallimento del percorso GL (o l'override `seekpreview.force-off`)
 * lascia [isAvailable] falso e il placeholder, senza bloccare né il player principale.
 */
@OptIn(UnstableApi::class)
internal class PlayerSeekPreviewExtractor(
  private val context: Context,
  private val mediaItem: MediaItem,
  private val mediaSourceFactory: MediaSource.Factory,
  private val maxWidthPx: Int = MAX_CAPTURE_WIDTH_PX,
  private val prepareTimeoutMs: Long = PREPARE_TIMEOUT_MS,
  private val frameTimeoutMs: Long = FRAME_TIMEOUT_MS
) : SeekPreviewSource {

  /**
   * Vero quando il percorso ImageReader è utilizzabile qui (piattaforma sicura, oppure
   * override `seekpreview.force-gl` assente). Falso su Fire OS/mt8696: in quel caso la
   * classe usa il percorso GL e il processo non può essere terminato da `image.planes`.
   */
  private val useImageReader =
    SeekPreviewCaptureSupport.isCaptureSupported(context.filesDir) &&
      !SeekPreviewCaptureSupport.isGlForced(context.filesDir)

  /** Override `seekpreview.force-off`: anteprima disattivata del tutto (placeholder). */
  private val previewDisabled = SeekPreviewCaptureSupport.overrideFrom(context.filesDir) == false

  /** Percorso GL: creato alla prima richiesta; [glUnavailable] se la piattaforma non lo consente. */
  private var grabber: SeekPreviewGlFrameGrabber? = null

  @Volatile
  private var glUnavailable = false

  private var player: ExoPlayer? = null
  private var reader: ImageReader? = null
  private var imageThread: HandlerThread? = null
  private var prepared = false
  private var prepareFailed = false
  private var frameSignal = AtomicInteger(0)

  /** PTS (µs) dell'ultimo frame che il renderer sta per presentare; UNSET = sconosciuto. */
  @Volatile
  private var lastFramePtsUs: Long = C.TIME_UNSET

  /** Formato video corrente (rapporto d'aspetto per dimensionare la ImageReader). */
  @Volatile
  private var videoFormat: Format? = null

  /** Ultimo frame estratto: evita di ripetere un seek identico (es. prefetch duplicato). */
  private var lastFrame: SeekPreviewFrame? = null

  @Volatile
  private var released = false

  private val mainHandler = Handler(Looper.getMainLooper())

  /**
   * Serializza le catture sul decoder dedicato.
   *
   * Il secondo ExoPlayer è UNO SOLO: due catture in concorrenza (warm-up e richiesta primaria,
   * o un worker residuo) si ruberebbero il seek a vicenda, e il frame che arriva potrebbe
   * essere quello dell'altra posizione con il PTS dell'una e i pixel dell'altra. Meglio una
   * coda esplicita che un'interleaving silenzioso.
   */
  private val captureMutex = Mutex()

  /** Thread dei dump diagnostici: mai sul main thread, dove ogni scrittura è un frame perso. */
  private val dumpLock = Any()

  @Volatile
  private var dumpExecutorField: java.util.concurrent.ExecutorService? = null

  /** Creata alla prima occorrenza; null dopo [release]. */
  private fun dumpExecutor(): java.util.concurrent.ExecutorService? = synchronized(dumpLock) {
    if (released) return null
    dumpExecutorField
      ?: java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "seek-preview-dump").apply { isDaemon = true }
      }.also { dumpExecutorField = it }
  }

  init {
    when {
      previewDisabled ->
        Log.w(TAG, "preview: anteprima disattivata da override ${SeekPreviewCaptureSupport.FORCE_OFF_FILE}")
      useImageReader ->
        Log.i(TAG, "preview: cattura via ImageReader (piattaforma compatibile)")
      else ->
        Log.i(
          TAG,
          "preview: ImageReader non compatibile su questa piattaforma " +
            "(manufacturer=${Build.MANUFACTURER}, board=${Build.BOARD}, hardware=${Build.HARDWARE}, " +
            "API ${Build.VERSION.SDK_INT}): cattura via GL (SurfaceTexture + glReadPixels), " +
            "nessuna ImageReader creata"
        )
    }
  }

  override val isAvailable: Boolean
    get() = !released && !previewDisabled && (useImageReader || !glUnavailable)

  override suspend fun load(positionMs: Long): SeekPreviewFrame? {
    if (released || previewDisabled) return null
    // Una cattura alla volta: il decoder dedicato è uno solo e il seek non ammette concorrenza.
    return captureMutex.withLock { loadInternal(positionMs) }
  }

  /**
   * Scalda decoder dedicato e superficie di cattura SENZA cercare una posizione.
   *
   * Il `prepare()` del secondo ExoPlayer costa ~5.7 s misurati sulla Fire TV mt8696 (manifest
   * HLS + primo segmento). Dentro la prima cattura teneva il worker occupato per dieci pressioni
   * di fila, e il primo frame arrivato a video era quello di una posizione abbandonata cento
   * secondi prima. Scaldare all'apertura dello scrubbing tiene quel costo fuori dalla coda delle
   * catture, così il worker resta libero di inseguire la posizione corrente.
   */
  override suspend fun prewarm() {
    if (released || previewDisabled) return
    captureMutex.withLock {
      withContext(Dispatchers.Main.immediate) {
        if (released) return@withContext
        val p = ensurePlayer() ?: return@withContext
        val captureReady = if (useImageReader) {
          ensureReader(p)
          reader != null
        } else {
          ensureGrabber(p)
        }
        Log.i(
          TAG,
          "preview: warm-up completato (cattura=${if (captureReady) "pronta" else "non disponibile"}, " +
            "durata=${p.duration}ms)"
        )
      }
    }
  }

  /** Corpo della cattura, eseguito sotto [captureMutex]: una sola istanza in volo per sorgente. */
  private suspend fun loadInternal(positionMs: Long): SeekPreviewFrame? {
    if (released || previewDisabled) return null
    return withContext(Dispatchers.Main.immediate) {
      if (released) return@withContext null
      val requestedMs = positionMs.coerceAtLeast(0L)
      val p = ensurePlayer() ?: return@withContext null
      val captureReady = if (useImageReader) {
        ensureReader(p)
        reader != null
      } else {
        ensureGrabber(p)
      }
      if (!captureReady) {
        Log.w(TAG, "preview: nessuna superficie di cattura disponibile (pos=${requestedMs}ms)")
        return@withContext null
      }

      // Richiesta identica all'ultimo frame: nessun nuovo seek (non produrrebbe frame).
      lastFrame?.let { cached ->
        if (!cached.bitmap.isRecycled && cached.positionMs == requestedMs) {
          Log.i(TAG, "preview: riuso frame identico pos=${requestedMs}ms")
          return@withContext cached
        }
      }

      val playerBeforeMs = p.currentPosition
      if (useImageReader) {
        drainReader()
      } else {
        // SVUOTA la coda dei buffer già presentati dal decoder, non solo il contatore: sono
        // immagini del punto di partenza, e `updateTexImage()` le restituisce volentieri anche
        // dopo il seek. Era il motivo per cui, invertendo, i pixel restavano gli stessi mentre
        // il timestamp dichiarava la posizione nuova.
        val drained = grabber?.drainPendingBuffers() ?: 0
        if (drained > 0) {
          Log.d(TAG, "preview: scaricati $drained buffer pre-seek dalla superficie GL")
        }
        grabber?.frameSignal?.set(0)
      }
      lastFramePtsUs = C.TIME_UNSET
      val seekIssuedAtMs = SystemClock.elapsedRealtime()
      p.seekTo(requestedMs)
      val framePresented = awaitFrame(seekIssuedAtMs, requestedMs, playerBeforeMs)
      if (!framePresented) {
        Log.w(
          TAG,
          "preview: nessun frame presentato entro ${frameTimeoutMs}ms (richiesta=${requestedMs}ms, " +
            "player=${p.currentPosition}ms): nessuna pubblicazione"
        )
        return@withContext null
      }
      val captured = if (useImageReader) {
        acquireFromReader(requestedMs)
      } else {
        acquireFromGrabber(requestedMs)
      }
      if (captured == null) {
        Log.w(
          TAG,
          "preview: frame segnalato ma non acquisito (richiesta=${requestedMs}ms): nessuna pubblicazione"
        )
        return@withContext null
      }
      val bitmap = captured.bitmap
      // Il dump diagnostico resta, ma NON sul thread del player: `compress` PNG su /sdcard
      // dentro la cattura costava decine di millisecondi sul main thread a ogni pressione,
      // cioè esattamente il tempo in cui il worker dovrebbe già essere sul seek successivo.
      dumpFrameAsync(bitmap, requestedMs)
      val playerAfterMs = p.currentPosition
      val decodedMs = captured.decodedMs
      // PTS autorevole solo se il renderer l'ha davvero dichiarato: `null` significa "pixel
      // reali, provenienza non verificabile", e il controller lo pubblica come approssimazione
      // invece di spacciarlo per il fotogramma della posizione richiesta.
      val ptsAuthoritative = decodedMs != null
      val positionOfFrame = decodedMs ?: playerAfterMs
      val elapsed = SystemClock.elapsedRealtime() - seekIssuedAtMs
      // L'impronta si calcola UNA volta e FUORI dal thread del player (scala la bitmap e fa
      // 64 getPixel): viene passata al frame, così controller e overlay la leggono senza
      // rifarla. Prima veniva ricomputata tre volte, di cui una sul main thread.
      val hash = withContext(Dispatchers.Default) { SeekPreviewFrameProbe.contentHash(bitmap) }
      Log.i(
        TAG,
        "preview: richiesta=${requestedMs}ms decodificata=${decodedMs ?: "sconosciuta"}ms " +
          "ptsAutorevole=$ptsAuthoritative " +
          "playerPrima=${playerBeforeMs}ms playerDopo=${playerAfterMs}ms buffered=${p.bufferedPosition}ms " +
          "primoFrame=${if (framePresented) "si" else "no"} latenza=${elapsed}ms " +
          "bitmap=${bitmap.width}x${bitmap.height} hash=$hash"
      )
      Log.i(
        "SeekSync",
        "[SeekSync] EXTRACTOR_CAPTURED: requested=${requestedMs}ms decodedPts=${decodedMs ?: -1L}ms " +
          "playerAfter=${playerAfterMs}ms ptsAuthoritative=$ptsAuthoritative latency=${elapsed}ms"
      )
      SeekPreviewFrame(
        bitmap = bitmap,
        positionMs = positionOfFrame,
        ptsAuthoritative = ptsAuthoritative,
        contentHash = hash
      ).also { lastFrame = it }
    }
  }

  /** Bitmap acquisita più la posizione decodificata da cui proviene (null = ignota). */
  private class CapturedFrame(val bitmap: Bitmap, val decodedMs: Long?)

  // ---------------------------------------------------------------------------
  // Acquisizione del frame: ImageReader (YUV) oppure GL (SurfaceTexture)
  // ---------------------------------------------------------------------------

  /**
   * Lettura del frame dalla ImageReader: è qui — e solo qui — che avviene `image.planes`,
   * l'operazione che su Fire OS abortisce il processo. Questo ramo gira esclusivamente su
   * piattaforme dichiarate compatibili ([SeekPreviewCaptureSupport]).
   */
  private suspend fun acquireFromReader(requestedMs: Long): CapturedFrame? {
    val readerNow = reader ?: return null
    val image = runCatching { readerNow.acquireLatestImage() }.getOrNull()
    if (image == null) {
      Log.w(TAG, "preview: frame segnalato ma nessuna immagine disponibile (pos=${requestedMs}ms)")
      return null
    }
    val width = image.width
    val height = image.height
    val yuv = try {
      copyPlanes(image)
    } finally {
      image.close()
    }
    val bitmap = withContext(Dispatchers.Default) { yuvToBitmap(yuv, width, height) }
    if (bitmap == null) {
      Log.w(TAG, "preview: conversione YUV fallita (${width}x$height, pos=${requestedMs}ms)")
      return null
    }
    return CapturedFrame(bitmap, lastFramePtsUs.takeIf { it != C.TIME_UNSET }?.div(1_000L))
  }

  /**
   * Lettura del frame dal percorso GL: `updateTexImage` porta la texture sul frame più
   * recente del decoder e `glReadPixels` ne legge il contenuto. La posizione è il PTS del
   * renderer (`VideoFrameMetadataListener`), che è il PTS reale del frame nel tempo del
   * media; il timestamp grezzo del buffer SurfaceTexture resta solo diagnostico perché
   * vive nel clock della sorgente (non allineato alla posizione del media).
   */
  private suspend fun acquireFromGrabber(requestedMs: Long): CapturedFrame? {
    val g = grabber ?: return null
    val result = try {
      g.grab()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Throwable) {
      Log.e(TAG, "preview: cattura GL fallita (pos=${requestedMs}ms)", error)
      null
    } ?: return null
    val rendererMs = lastFramePtsUs.takeIf { it != C.TIME_UNSET }?.div(1_000L)
    Log.i(
      TAG,
      "preview: cattura GL richiesta=${requestedMs}ms bufferTs=${result.timestampMs}ms " +
        "rendererPts=${rendererMs}ms bitmap=${result.bitmap.width}x${result.bitmap.height}"
    )
    return CapturedFrame(result.bitmap, rendererMs)
  }

  // ---------------------------------------------------------------------------
  // Player dedicato
  // ---------------------------------------------------------------------------

  private suspend fun ensurePlayer(): ExoPlayer? {
    if (released) return null
    if (prepareFailed) return null
    player?.let { if (prepared) return it }
    val p = player ?: run {
      val trackSelector = DefaultTrackSelector(context).apply {
        parameters = buildUponParameters()
          .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
          .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
          .build()
      }
      val renderersFactory = DefaultRenderersFactory(context)
        .setEnableDecoderFallback(true)
      ExoPlayer.Builder(context, renderersFactory)
        .setMediaSourceFactory(mediaSourceFactory)
        .setTrackSelector(trackSelector)
        .build()
        .apply {
          volume = 0f
          playWhenReady = false
          setAudioAttributes(
            androidx.media3.common.AudioAttributes.DEFAULT,
            /* handleAudioFocus = */ false
          )
          setWakeMode(C.WAKE_MODE_NONE)
          setVideoFrameMetadataListener(VideoFrameMetadataListener { presentationTimeUs, _, format, _ ->
            lastFramePtsUs = presentationTimeUs
            Log.i(TAG, "preview: renderer presenta pts=${presentationTimeUs}us")
            if (format.width > 0 && format.height > 0) {
              this@PlayerSeekPreviewExtractor.videoFormat = format
            }
          })
          addAnalyticsListener(object : AnalyticsListener {
            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: androidx.media3.common.PlaybackException) {
              Log.w(TAG, "preview: errore del decoder dedicato ${error.errorCodeName}", error)
            }
          })
          setMediaItem(mediaItem)
          prepare()
        }
        .also { player = it }
    }
    val deadline = SystemClock.elapsedRealtime() + prepareTimeoutMs
    while (!released && SystemClock.elapsedRealtime() < deadline) {
      if (p.playerError != null) break
      if (p.playbackState == androidx.media3.common.Player.STATE_READY) {
        prepared = true
        Log.i(
          TAG,
          "preview: decoder dedicato pronto (durata=${p.duration}ms, " +
            "video=${p.videoFormat?.width}x${p.videoFormat?.height})"
        )
        return p
      }
      delay(POLL_MS)
    }
    prepareFailed = true
    Log.w(
      TAG,
      "preview: decoder dedicato non pronto entro ${prepareTimeoutMs}ms " +
        "(state=${p.playbackState}, errore=${p.playerError?.errorCodeName})"
    )
    return null
  }

  /**
   * ImageReader dimensionata sul rapporto d'aspetto reale del video: il codec scala il
   * frame nella superficie senza deformarlo. Se il formato non è ancora noto si parte da
   * 16:9 e si ricrea alla prima occasione utile.
   */
  private fun ensureReader(p: ExoPlayer) {
    val format = videoFormat ?: p.videoFormat
    if (format != null && format.width > 0 && format.height > 0) videoFormat = format
    val targetHeight = targetHeightFor(format)
    val current = reader
    if (current != null && kotlin.math.abs(current.height - targetHeight) <= 2) return
    val width = maxWidthPx
    val height = targetHeight
    val newReader = runCatching {
      ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, MAX_IMAGES)
    }.getOrElse {
      Log.e(TAG, "preview: ImageReader non creabile (${width}x$height)", it)
      return
    }
    val thread = imageThread ?: HandlerThread("seek-preview-image").apply { start() }.also { imageThread = it }
    newReader.setOnImageAvailableListener({ frameSignal.incrementAndGet() }, Handler(thread.looper))
    p.setVideoSurface(newReader.surface)
    val old = reader
    reader = newReader
    if (old != null) {
      // La vecchia superficie è stata sostituita: il codec la rilascia al prossimo frame.
      runCatching { old.close() }
    }
    Log.i(
      TAG,
      "preview: ImageReader pronta ${width}x$height " +
        "(video=${format?.width}x${format?.height}, aspect=${format?.let { it.width.toFloat() / it.height }})"
    )
  }

  /**
   * Percorso GL: crea (una volta sola) il [SeekPreviewGlFrameGrabber] e gli collega il
   * decoder dedicato. Nessuna `Image` viene mai creata: su Fire OS è l'unica strada che non
   * termina il processo. Se la piattaforma non supporta la cattura GL la classe segnala
   * `glUnavailable` e da qui in poi `load` restituisce subito null (placeholder onesto).
   */
  private suspend fun ensureGrabber(p: ExoPlayer): Boolean {
    if (glUnavailable) return false
    grabber?.let { return true }
    val format = videoFormat ?: p.videoFormat
    if (format != null && format.width > 0 && format.height > 0) videoFormat = format
    val targetHeight = targetHeightFor(format)
    val width = maxWidthPx
    val height = targetHeight
    val videoWidth = format?.width?.takeIf { it > 0 } ?: 1280
    val videoHeight = format?.height?.takeIf { it > 0 } ?: 720
    val g = SeekPreviewGlFrameGrabber()
    val prepared = g.prepare(videoWidth, videoHeight, width, height)
    if (!prepared) {
      g.release()
      glUnavailable = true
      Log.w(TAG, "preview: cattura GL non disponibile: anteprima disabilitata senza crash")
      return false
    }
    grabber = g
    p.setVideoSurface(g.surface())
    Log.i(
      TAG,
      "preview: grabber GL pronto ${width}x$height " +
        "(video=${format?.width}x${format?.height}, aspect=${format?.let { it.width.toFloat() / it.height }})"
    )
    return true
  }

  private fun targetHeightFor(format: Format?): Int {
    val width = format?.width?.takeIf { it > 0 } ?: return (maxWidthPx * 9 / 16)
    val height = format.height.takeIf { it > 0 } ?: return (maxWidthPx * 9 / 16)
    val scaled = (maxWidthPx.toLong() * height / width).toInt().coerceIn(MIN_CAPTURE_HEIGHT_PX, maxWidthPx)
    return (scaled / 2) * 2
  }

  /**
   * Attende il frame del seek appena emesso.
   *
   * **Un solo gate, per entrambi i backend.** Prima, sul percorso GL — quello che gira su
   * Fire TV — la funzione restituiva `true` al primo segnale di frame disponibile senza
   * confrontare niente con la posizione richiesta (`if (!useImageReader) return true`): i
   * buffer accodati prima del seek venivano accettati come se fossero il frame nuovo, e i loro
   * pixel finivano etichettati con `playerAfterMs`, cioè con la posizione richiesta. Da qui
   * derivano insieme il fotogramma fermo all'indietro e il frame "di un'altra scena" in avanti.
   *
   * Ora si aspetta che il PTS dichiarato dal renderer appartenga davvero al seek
   * ([ptsBelongsToSeek]). Alla scadenza, se il PTS c'è ma non appartiene al seek, oppure non
   * c'è proprio ma un frame è arrivato dopo il seek, si accetta comunque: il frame viene però
   * marcato con PTS non autorevole e il controller lo pubblica come **approssimazione**, mai
   * come esito della posizione richiesta.
   */
  private suspend fun awaitFrame(
    seekIssuedAtMs: Long,
    requestedPositionMs: Long,
    preSeekPositionMs: Long
  ): Boolean {
    val deadline = SystemClock.elapsedRealtime() + frameTimeoutMs
    // Il segnale dei frame ARRIVATI è quello del backend di cattura attivo: la ImageReader
    // segnala via [frameSignal] locale, il percorso GL via [SeekPreviewGlFrameGrabber.frameSignal].
    // Pollare il contatore sbagliato faceva terminare ogni cattura per timeout pur con il
    // frame già decodificato e renderizzato.
    val signal = if (useImageReader) frameSignal else (grabber?.frameSignal ?: frameSignal)
    val signalAtSeek = signal.get()
    var rejected = 0
    var lastRejectedMs = Long.MIN_VALUE
    while (!released && SystemClock.elapsedRealtime() < deadline) {
      currentCoroutineContext().ensureActive()
      val ptsUs = lastFramePtsUs
      if (ptsUs != C.TIME_UNSET) {
        val decodedMs = ptsUs / 1_000L
        if (ptsBelongsToSeek(decodedMs, requestedPositionMs, preSeekPositionMs)) {
          if (rejected > 0) {
            Log.i(
              TAG,
              "preview: frame accettato richiesta=${requestedPositionMs}ms dopo $rejected scarti " +
                "(ultimo scartato=${lastRejectedMs}ms)"
            )
          }
          // Il renderer HA PRESENTATO il frame: il buffer è (a breve) nella coda della
          // superficie anche se il callback onFrameAvailable non è arrivato — su alcune
          // piattaforme MTK la notifica della coda non è affidabile e non può essere un
          // prerequisito. Si attende solo il tempo di atterraggio e si cattura.
          delay(GL_BUFFER_LANDING_MS)
          return true
        }
        // PTS noto ma non del seek: è il frame del punto di partenza ancora in transito.
        // Si SCARTA e si continua ad aspettare, invece di mostrare un'immagine sbagliata.
        rejected++
        lastRejectedMs = decodedMs
      }
      delay(POLL_MS)
    }
    val ptsUs = lastFramePtsUs
    if (ptsUs != C.TIME_UNSET) {
      Log.w(
        TAG,
        "preview: frame con pts=${ptsUs / 1_000L}ms fuori dalla finestra del seek " +
          "(richiesta=${requestedPositionMs}ms, partenza=${preSeekPositionMs}ms, " +
          "scarti=$rejected): pubblicato senza pts autorevole (approssimazione)"
      )
      return true
    }
    if (signal.get() > signalAtSeek) {
      Log.w(
        TAG,
        "preview: frame arrivato dopo il seek ma senza metadata dal renderer " +
          "(richiesta=${requestedPositionMs}ms): posizione non verificabile, approssimazione"
      )
      return true
    }
    Log.w(
      TAG,
      "preview: nessun frame entro ${frameTimeoutMs}ms (richiesta=${requestedPositionMs}ms, " +
        "partenza=${preSeekPositionMs}ms, scarti=$rejected, ultimoScartato=${lastRejectedMs}ms)"
    )
    return false
  }

  /**
   * Vero se il PTS presentato appartiene al seek appena emesso e non al punto di partenza.
   *
   * Due condizioni, entrambe prive di riferimento al verso dello scrubbing:
   * 1. il frame è dentro la finestra di atterraggio del seek: un decoder HLS può attestarsi
   *    qualche frame dopo il target o, senza seek accurato, sul keyframe precedente;
   * 2. il frame è più vicino al target che al punto da cui siamo partiti.
   *
   * Manca la condizione 2 nella versione precedente, e la tolleranza era 10 s — esattamente un
   * passo di scrubbing: il frame del punto di partenza rientrava nella finestra della richiesta
   * fatta un passo prima e veniva accettato come se fosse quello nuovo. Ecco il fotogramma fisso
   * quando si inverte.
   */
  internal fun ptsBelongsToSeek(ptsMs: Long, requestedMs: Long, preSeekMs: Long): Boolean =
    SeekPreviewSeekGate.belongsToSeek(ptsMs, requestedMs, preSeekMs)

  private fun drainReader() {
    val current = reader ?: return
    while (true) {
      val image = runCatching { current.acquireLatestImage() }.getOrNull() ?: break
      image.close()
    }
    frameSignal.set(0)
  }

  /**
   * Scrive il fotogramma su `/sdcard/direct_frame.png` per il confronto visivo a posteriori.
   *
   * Percorso e contenuto sono quelli di sempre (l'ultimo fotogramma catturato), ma la scrittura
   * avviene su un thread dedicato: dentro `loadInternal` era un `compress` PNG sul main thread a
   * ogni pressione, cioè proprio il tempo che il worker dovrebbe passare sul seek successivo.
   */
  private fun dumpFrameAsync(bitmap: Bitmap, requestedMs: Long) {
    val executor = dumpExecutor() ?: return
    runCatching {
      executor.execute {
        runCatching {
          java.io.File("/sdcard/direct_frame.png").outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
          }
        }.onFailure {
          Log.d(TAG, "preview: dump diagnostico omesso (richiesta=${requestedMs}ms): ${it.message}")
        }
      }
    }
  }

  override fun release() {
    if (released) return
    released = true
    val cleanup = {
      runCatching { player?.setVideoSurface(null) }
      runCatching { player?.release() }
      player = null
      prepared = false
      runCatching { reader?.close() }
      reader = null
      imageThread?.quitSafely()
      imageThread = null
      runCatching { grabber?.release() }
      grabber = null
      lastFrame = null
      synchronized(dumpLock) {
        dumpExecutorField?.shutdownNow()
        dumpExecutorField = null
      }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) cleanup() else mainHandler.post(cleanup)
  }

  // ---------------------------------------------------------------------------
  // YUV -> ARGB
  // ---------------------------------------------------------------------------

  private class YuvPlanes(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
    val yRowStride: Int,
    val yPixelStride: Int,
    val uRowStride: Int,
    val uPixelStride: Int,
    val vRowStride: Int,
    val vPixelStride: Int
  )

  private fun copyPlanes(image: Image): YuvPlanes {
    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    Log.i(
      TAG,
      "planes: format=${image.format} ${image.width}x${image.height} " +
        "Y=(stride=${yPlane.rowStride},pix=${yPlane.pixelStride},rem=${yPlane.buffer.remaining()}) " +
        "U=(stride=${uPlane.rowStride},pix=${uPlane.pixelStride},rem=${uPlane.buffer.remaining()}) " +
        "V=(stride=${vPlane.rowStride},pix=${vPlane.pixelStride},rem=${vPlane.buffer.remaining()})"
    )
    return YuvPlanes(
      width = image.width,
      height = image.height,
      y = yPlane.buffer.toByteArray(),
      u = uPlane.buffer.toByteArray(),
      v = vPlane.buffer.toByteArray(),
      yRowStride = yPlane.rowStride,
      yPixelStride = yPlane.pixelStride,
      uRowStride = uPlane.rowStride,
      uPixelStride = uPlane.pixelStride,
      vRowStride = vPlane.rowStride,
      vPixelStride = vPlane.pixelStride
    )
  }

  private fun java.nio.ByteBuffer.toByteArray(): ByteArray {
    val duplicate = duplicate()
    val bytes = ByteArray(remaining())
    duplicate.get(bytes)
    return bytes
  }

  private fun yuvToBitmap(frame: YuvPlanes, width: Int, height: Int): Bitmap? {
    if (frame.width <= 0 || frame.height <= 0) return null
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
      val yRow = y * frame.yRowStride
      val uvRow = y / 2
      for (x in 0 until width) {
        val yIndex = yRow + x * frame.yPixelStride
        if (yIndex >= frame.y.size) continue
        val yValue = frame.y[yIndex].toInt() and 0xFF
        val uvIndex = uvRow * frame.uRowStride + (x / 2) * frame.uPixelStride
        val u = (frame.u.getOrElse(uvIndex) { 128.toByte() }.toInt() and 0xFF) - 128
        val vIndex = uvRow * frame.vRowStride + (x / 2) * frame.vPixelStride
        val v = (frame.v.getOrElse(vIndex) { 128.toByte() }.toInt() and 0xFF) - 128
        val c = (yValue - 16).coerceAtLeast(0)
        val r = ((298 * c + 409 * v + 128) shr 8).coerceIn(0, 255)
        val g = ((298 * c - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
        val b = ((298 * c + 516 * u + 128) shr 8).coerceIn(0, 255)
        pixels[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
      }
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap
  }

  private companion object {
    const val TAG = "SeekPreviewSrc"

    /** Larghezza massima della miniatura: oltre non serve e costa RAM/conversione. */
    const val MAX_CAPTURE_WIDTH_PX = 480
    const val MIN_CAPTURE_HEIGHT_PX = 120
    const val MAX_IMAGES = 2
    const val POLL_MS = 10L

    /**
     * Tempo di atterraggio del buffer nella superficie dopo la presentazione del renderer.
     * Su alcune piattaforme MTK la notifica `onFrameAvailable` della coda non è affidabile e
     * non può quindi essere un prerequisito della cattura.
     */
    const val GL_BUFFER_LANDING_MS = 80L

    /** Il primo prepare include manifest HLS + prima parte del segmento. */
    const val PREPARE_TIMEOUT_MS = 8_000L

    /** Un seek su HLS reale (fetch segmento + decodifica) richiede 0.5-2 s fuori buffer. */
    const val FRAME_TIMEOUT_MS = 4_000L
  }
}
