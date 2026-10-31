package com.example.ui.screens.splash

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.R
import kotlinx.coroutines.delay

private const val TAG = "SplashIntro"

/**
 * Intro animata ufficiale di StreamNova: il logo 3D viene riprodotto una sola
 * volta, a schermo intero e senza audio, subito dopo lo splash nativo.
 *
 * Sequenza:
 *  1. la riproduzione parte SOLO quando la finestra dell'app è visibile e il
 *     surface del player esiste (altrimenti il clock Media3 avanzerebbe dietro
 *     lo splash nativo e il video comincerebbe a metà);
 *  2. il filmato viene riprodotto INTEGRALMENTE (~5 s), senza tagli né accelerazioni;
 *  3. a `STATE_ENDED` l'ultimo fotogramma resta fermo per
 *     [SPLASH_POST_VIDEO_PAUSE_MS] (4 s);
 *  4. solo allora la splash viene smontata e compare la Home.
 *
 * Il player resta vivo durante la pausa (niente `release()`), così la
 * SurfaceView continua a mostrare l'ultimo frame. La Home è già composta sotto
 * l'overlay per tutta la sequenza: al termine non c'è nulla da ricaricare e non
 * compare alcun frame nero.
 *
 * Errore reale o impossibilità di avviare il video chiudono subito l'intro:
 * nessun watchdog interrompe una riproduzione che ha già mostrato un frame.
 *
 * @param onFinished invocata quando la splash può essere smontata.
 */
@Composable
fun SplashVideoScreen(
  onFinished: () -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val appContext = remember(context) { context.applicationContext }

  // Un solo player per composizione: le recomposition non devono istanziare un
  // secondo decoder. `runCatching` copre i dispositivi senza Media3 utilizzabile.
  val player = remember(appContext) { createSplashPlayer(appContext) }

  SplashVideoStage(player = player, onFinished = onFinished, modifier = modifier)
}

/**
 * Corpo della splash separato dalla creazione del player: con [player] nullo
 * (costruzione fallita) conclude subito l'intro, così l'app non resta mai
 * ferma su una schermata vuota.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun SplashVideoStage(
  player: Player?,
  onFinished: () -> Unit,
  modifier: Modifier = Modifier
) {
  // L'ultimo `onFinished` ricevuto, non quello catturato alla prima composizione.
  val currentOnFinished by rememberUpdatedState(onFinished)
  val lifecycleOwner = LocalLifecycleOwner.current
  val backgroundColor = colorResource(R.color.splash_video_background)

  // Stato della sequenza (video → fine → pausa → Home). Vive nel `remember`,
  // quindi le ricomposizioni Compose non azzerano il cronometro della pausa.
  val timeline = remember {
    SplashIntroTimeline(
      foreground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
      onFinished = { currentOnFinished() }
    )
  }
  // Primo piano, fine video e avvio come stato Compose: fanno (ri)partire gli
  // effetti quando cambiano. Durate e conteggi restano nel timeline.
  var isForeground by remember {
    mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
  }
  var videoStarted by remember { mutableStateOf(false) }
  var videoEnded by remember { mutableStateOf(false) }
  // Finestra disegnata e surface video pronto: senza ENTRAMBI il player non
  // deve partire, altrimenti il tempo scorre mentre lo schermo non mostra nulla.
  var windowVisible by remember { mutableStateOf(false) }
  var surfaceReady by remember { mutableStateOf(false) }
  // Pausa post-video e app passata in background: il surface viene distrutto e
  // l'ultimo frame non può essere ridisegnato al rientro. Si mostra allora il
  // logo statico ufficiale, identico alla composizione finale del video.
  var showHoldLogo by remember { mutableStateOf(false) }

  if (player == null) {
    // Nessun player disponibile: la Home deve comparire subito.
    LaunchedEffect(Unit) { timeline.onPlaybackError() }
    Box(modifier = modifier.fillMaxSize().background(backgroundColor))
    return
  }

  // Errore, fallback di avvio e fine pausa passano tutti dalla timeline, che
  // smonta la splash una sola volta. Rilascio del player (e quindi del decoder)
  // solo nel dispose, cioè a sequenza conclusa.
  val context = LocalContext.current
  val splashVideoUri = remember(context) {
    // URI di risorsa locale: la riproduzione passa dal DefaultDataSource di
    // ExoPlayer, senza rete e senza file temporanei su disco.
    Uri.parse("android.resource://${context.packageName}/${R.raw.splash_intro}")
  }
  DisposableEffect(player, splashVideoUri) {
    val listener = object : Player.Listener {
      override fun onRenderedFirstFrame() {
        // Solo per il fallback "video non avviabile": l'avvio della
        // riproduzione è già stato registrato quando è partito `play()`.
        timeline.onFirstFrameRendered()
      }

      override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState != Player.STATE_ENDED) return
        // Callback duplicate ignorate: la pausa parte una sola volta.
        if (timeline.hasPlaybackEnded) return
        timeline.onPlaybackEnded()
        videoEnded = true
        val now = SystemClock.elapsedRealtime()
        Log.i(
          TAG,
          "SPLASH_VIDEO_ENDED t=$now durata_video_ms=${timeline.videoPlaybackDurationMs() ?: -1}"
        )
        Log.i(TAG, "SPLASH_POST_VIDEO_DELAY_STARTED t=$now")
      }

      override fun onPlayerError(error: PlaybackException) {
        Log.w(TAG, "Intro non riproducibile (${error.errorCodeName}); ingresso diretto nella Home", error)
        timeline.onPlaybackError()
      }
    }
    player.addListener(listener)
    runCatching {
      player.setMediaItem(MediaItem.fromUri(splashVideoUri))
      // Il player viene preparato ma NON parte: l'avvio è rimandato a quando
      // finestra e surface sono realmente visibili (vedi effetto sotto).
      player.playWhenReady = false
      player.prepare()
    }.onFailure {
      Log.e(TAG, "Avvio dell'intro fallito; ingresso diretto nella Home", it)
      timeline.onPlaybackError()
    }
    onDispose {
      player.removeListener(listener)
      player.release()
    }
  }

  // Primo frame prodotto dalla finestra: da qui lo splash nativo è stato
  // sostituito dall'app e la splash video è realmente a schermo.
  LaunchedEffect(Unit) {
    withFrameNanos { }
    windowVisible = true
  }

  // Avvio della riproduzione: solo con finestra visibile, surface pronto e app
  // in primo piano. Evita che Media3 consumi secondi di filmato dietro lo
  // splash nativo (causa della Home "immediata" dopo l'avvio).
  val shouldStartVideo = windowVisible && surfaceReady && isForeground && !videoEnded
  LaunchedEffect(shouldStartVideo) {
    if (!shouldStartVideo || videoStarted || timeline.isFinished) return@LaunchedEffect
    videoStarted = true
    timeline.onPlaybackStarted()
    Log.i(TAG, "SPLASH_VIDEO_STARTED t=${SystemClock.elapsedRealtime()}")
    player.play()
  }

  // Pausa post-video: parte SOLO dopo STATE_ENDED, dura almeno
  // SPLASH_POST_VIDEO_PAUSE_MS di tempo EFFETTIVO e si sospende in background.
  // Al termine è la timeline a consentire la transizione alla Home.
  LaunchedEffect(videoEnded, isForeground) {
    if (!videoEnded || !isForeground) return@LaunchedEffect
    while (!timeline.isFinished) {
      val remaining = timeline.remainingPostVideoPauseMs()
      if (remaining > 0L) {
        delay(remaining)
        continue
      }
      // Log PRIMA della transizione: HOME_VISIBLE deve seguire questo evento.
      Log.i(
        TAG,
        "SPLASH_POST_VIDEO_DELAY_COMPLETED t=${SystemClock.elapsedRealtime()} " +
          "pausa_ms=${timeline.postVideoPauseElapsedMs()}"
      )
      timeline.onPostVideoPauseElapsed()
      break
    }
  }

  // Fallback di avvio: il limite decorre da quando la riproduzione è partita.
  // Se dopo SPLASH_VIDEO_START_TIMEOUT_MS non è mai comparso un frame, il video
  // non è avviabile e si entra nella Home. Con un frame già mostrato non fa
  // nulla: non può interrompere una riproduzione funzionante.
  LaunchedEffect(videoStarted) {
    if (!videoStarted) return@LaunchedEffect
    delay(SPLASH_VIDEO_START_TIMEOUT_MS)
    if (!timeline.isFinished && !timeline.hasRenderedFirstFrame) {
      Log.w(TAG, "Nessun frame dell'intro entro il limite di avvio; ingresso nella Home")
    }
    timeline.onNoFirstFrameTimeout()
  }

  // Ciclo di vita: l'intro non continua a decodificare in background e, al
  // ritorno in primo piano, riprende dal punto lasciato senza ripartire da zero.
  // Il cronometro della pausa segue la stessa regola: in background non scorre.
  DisposableEffect(player, lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_STOP -> {
          if (timeline.hasPlaybackEnded && !timeline.isFinished) showHoldLogo = true
          isForeground = false
          timeline.setForeground(false)
          player.pause()
        }
        Lifecycle.Event.ON_START -> {
          isForeground = true
          timeline.setForeground(true)
          // Dopo la fine del video il player resta fermo sull'ultimo frame
          // durante la pausa: `play()` lo farebbe ripartire da zero.
          if (videoStarted && !timeline.isFinished && !timeline.hasPlaybackEnded) {
            player.play()
          }
        }
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  // Callback del surface: la riproduzione attende che esista davvero.
  val surfaceCallback = remember {
    object : SurfaceHolder.Callback {
      override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
      }

      override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceReady = true
      }

      override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
      }
    }
  }

  Box(modifier = modifier.fillMaxSize().background(backgroundColor)) {
    if (showHoldLogo) {
      // Fine video e surface svuotato dal passaggio in background (una
      // SurfaceView ricreata senza frame mostrerebbe nero): si mostra il logo
      // ufficiale sulla tinta del filmato fino al termine della pausa.
      Image(
        painter = painterResource(R.drawable.streamnova_logo),
        contentDescription = null,
        modifier = Modifier
          .align(Alignment.Center)
          .fillMaxWidth(0.5f),
        contentScale = ContentScale.Fit
      )
    } else {
      val splashPlayer = player
      AndroidView(
        factory = { viewContext ->
          PlayerView(viewContext).apply {
            // Nessuna interfaccia di riproduzione: niente controller, niente
            // pulsanti, niente barra di avanzamento.
            useController = false
            controllerAutoShow = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            // 16:9 preservato: il filmato non viene mai deformato per riempire
            // lo schermo, l'eventuale spazio residuo resta dello sfondo.
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            // Prima del primo frame si vede il fondo del video, non il nero.
            setShutterBackgroundColor(viewContext.getColor(R.color.splash_video_background))
            // La splash non è interattiva: il focus resta alla Home sottostante.
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
            // Nessun salvaschermo durante video e pausa.
            keepScreenOn = true
            layoutParams = FrameLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT
            )
            this.player = splashPlayer
            (videoSurfaceView as? SurfaceView)?.holder?.addCallback(surfaceCallback)
          }
        },
        update = { view -> view.player = splashPlayer },
        onRelease = { view ->
          (view.videoSurfaceView as? SurfaceView)?.holder?.removeCallback(surfaceCallback)
          view.player = null
        },
        modifier = Modifier.fillMaxSize()
      )
    }
  }
}

/**
 * Player dedicato all'intro: muto, senza ripetizione, creato sul contesto
 * applicativo (la splash è a schermo intero e non ha bisogno dell'Activity).
 */
private fun createSplashPlayer(context: Context): Player? = runCatching {
  ExoPlayer.Builder(context).build().apply {
    // Muto prima del prepare: il filmato non ha tracce audio, ma l'intro deve
    // restare silenziosa anche se la risorsa venisse sostituita in futuro.
    volume = 0f
    // Il filmato si riproduce una sola volta: a fine riproduzione si entra
    // nella Home, non si riparte.
    repeatMode = Player.REPEAT_MODE_OFF
  }
}.onFailure {
  Log.e(TAG, "Player dell'intro non disponibile; ingresso diretto nella Home", it)
}.getOrNull()
