package com.aistudio.streamnova.mobile.ui

import android.annotation.SuppressLint
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.Window
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.aistudio.streamnova.mobile.R
import com.example.ui.theme.NovaCyanBright

/**
 * User-Agent desktop/Chrome: neutralizza i tag da WebView (`wv`, `Version/4.0`)
 * presenti nella UA mobile, che YouTube filtra su alcuni flussi protetti.
 */
private const val TRAILER_USER_AGENT =
  "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

/**
 * L'iframe YouTube è un player 16:9. Il contenitore deve rispettare questo
 * rapporto: se la WebView riempie un viewport verticale, YouTube estende la
 * propria chrome per tutta l'altezza e il video resta in una fascia lontana
 * dal centro.
 */
private const val TRAILER_ASPECT_RATIO = 16f / 9f

/** Dimensione in dp del contenitore video, calcolata sull'area utile. */
internal data class TrailerPlayerSize(val widthDp: Float, val heightDp: Float)

/**
 * Dimensione 16:9 che entra nell'area disponibile: limitata dalla larghezza in
 * portrait, dall'altezza in landscape. Il chiamante la centra nell'area.
 */
internal fun trailerPlayerSize(
  availableWidthDp: Float,
  availableHeightDp: Float,
  aspectRatio: Float = TRAILER_ASPECT_RATIO
): TrailerPlayerSize {
  if (availableWidthDp <= 0f || availableHeightDp <= 0f) {
    return TrailerPlayerSize(0f, 0f)
  }
  val width = minOf(availableWidthDp, availableHeightDp * aspectRatio)
  return TrailerPlayerSize(width, width / aspectRatio)
}

/**
 * Overlay trailer interno alla scheda dettaglio.
 *
 * Ha lo stesso comportamento della TV 1.0.4: il video YouTube viene riprodotto
 * in-app (mai un Intent esterno), il loading e l'assenza di trailer hanno una
 * schermata dedicata, e la chiusura torna alla scheda senza cambiare la
 * navigazione. Il Dialog copre l'intera finestra, barre di sistema comprese.
 */
@Composable
fun MobileTrailerOverlay(
  state: MobileTrailerState,
  onClose: () -> Unit
) {
  if (state is MobileTrailerState.Idle) return

  // La finestra host cambia dimensione a ogni rotazione/ridimensionamento,
  // anche quando l'Activity non viene ricreata. È il trigger affidabile per
  // riallineare la finestra separata del Dialog.
  val hostView = LocalView.current
  var hostSize by remember { mutableStateOf(hostView.width to hostView.height) }
  DisposableEffect(hostView) {
    val listener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
      hostSize = view.width to view.height
    }
    hostView.addOnLayoutChangeListener(listener)
    onDispose { hostView.removeOnLayoutChangeListener(listener) }
  }

  Dialog(
    onDismissRequest = onClose,
    properties = DialogProperties(
      // La finestra viene dimensionata a MATCH_PARENT dal LaunchedEffect qui
      // sotto. Con usePlatformDefaultWidth=false DialogLayout fisserebbe la
      // finestra alla configurazione del primo layout e non la aggiornerebbe
      // alla rotazione, perché l'Activity non viene ricreata.
      usePlatformDefaultWidth = true,
      decorFitsSystemWindows = false,
      dismissOnClickOutside = false
    )
  ) {
    val view = LocalView.current
    LaunchedEffect(hostSize) {
      val dialogWindow = view.dialogWindow() ?: return@LaunchedEffect
      dialogWindow.configureTrailerWindow()
      dialogWindow.setLayout(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
    }
    // DialogWrapper riporta la finestra a WRAP_CONTENT nel primo SideEffect:
    // senza questo listener il dialog aperto in portrait si dimensionerebbe
    // sul contenuto (video + X) invece che sull'intero schermo.
    DisposableEffect(view) {
      val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        val dialogWindow = view.dialogWindow() ?: return@OnLayoutChangeListener
        val attributes = dialogWindow.attributes
        if (attributes.width != ViewGroup.LayoutParams.MATCH_PARENT ||
          attributes.height != ViewGroup.LayoutParams.MATCH_PARENT
        ) {
          dialogWindow.configureTrailerWindow()
          dialogWindow.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
          )
        }
      }
      view.addOnLayoutChangeListener(listener)
      onDispose { view.removeOnLayoutChangeListener(listener) }
    }

    BoxWithConstraints(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)
    ) {
      // Inset simmetrici: in landscape cutout e status bar possono avere
      // spessori diversi sui due lati. Usando il valore maggiore da entrambi i
      // lati il player resta centrato sullo schermo e fuori da notch e barre.
      val safeInset = WindowInsets.safeDrawing.asPaddingValues()
      val layoutDirection = LocalLayoutDirection.current
      val horizontalInset = maxOf(
        safeInset.calculateLeftPadding(layoutDirection),
        safeInset.calculateRightPadding(layoutDirection)
      )
      val verticalInset = maxOf(
        safeInset.calculateTopPadding(),
        safeInset.calculateBottomPadding()
      )
      val availableWidth = (maxWidth - horizontalInset * 2).coerceAtLeast(0.dp)
      val availableHeight = (maxHeight - verticalInset * 2).coerceAtLeast(0.dp)

      // Il player entra sempre nell'area utile mantenendo il 16:9: limitato
      // dalla larghezza in portrait, dall'altezza in landscape. Il box è
      // centrato, quindi il video non eredita offset dal Dettaglio.
      val playerSize = trailerPlayerSize(availableWidth.value, availableHeight.value)
      Box(
        modifier = Modifier
          .align(Alignment.Center)
          .size(width = playerSize.widthDp.dp, height = playerSize.heightDp.dp)
      ) {
        when (state) {
          is MobileTrailerState.Playing -> TrailerWebView(youtubeKey = state.youtubeKey)
          is MobileTrailerState.Loading -> TrailerStatus(
            text = stringResource(R.string.detail_trailer_loading),
            showSpinner = true
          )
          is MobileTrailerState.Error -> TrailerStatus(
            text = stringResource(R.string.detail_trailer_error),
            showSpinner = false
          )
          MobileTrailerState.Idle -> Unit
        }
      }

      // La X resta nell'angolo in alto a destra dell'area sicura: in portrait
      // è sopra il video, in landscape finisce nella banda nera laterale.
      Row(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .fillMaxWidth()
          .windowInsetsPadding(
            WindowInsets.safeDrawing.only(
              WindowInsetsSides.Top + WindowInsetsSides.Horizontal
            )
          )
          .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.End
      ) {
        CloseTrailerButton(onClose = onClose)
      }
    }
  }
}

/**
 * Finestra del Dialog che ospita questa composizione.
 *
 * `DialogLayout` (la root view del Dialog Compose) implementa
 * [DialogWindowProvider] ed è un antenato della view di composizione: risalire
 * la gerarchia è più stabile che assumere un parent diretto.
 */
private fun View.dialogWindow(): Window? {
  var current: ViewParent? = parent
  while (current != null) {
    if (current is DialogWindowProvider) return current.window
    current = current.parent
  }
  return null
}

/**
 * Rende la finestra del trailer realmente full-screen.
 *
 * La finestra di un Dialog viene di default adattata all'area sicura
 * (`fitInsetsTypes` include status/navigation bar): le zone di cutout e barre
 * resterebbero trasparenti, mostrando il Dettaglio sotto l'overlay. Layout in
 * screen + nessun fit degli insets + cutout `always` permettono allo sfondo
 * nero di coprire tutto lo schermo; gli insets restano leggibili dal contenuto
 * Compose, che li usa per centrare il player.
 */
private fun Window.configureTrailerWindow() {
  addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
  addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
  attributes = attributes.apply {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      setFitInsetsTypes(0)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      layoutInDisplayCutoutMode =
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }
  }
}

@Composable
private fun TrailerStatus(text: String, showSpinner: Boolean) {
  Column(
    modifier = Modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
    if (showSpinner) {
      CircularProgressIndicator(
        modifier = Modifier.size(44.dp),
        color = NovaCyanBright,
        strokeWidth = 3.dp
      )
      Spacer(modifier = Modifier.height(18.dp))
    }
    Text(
      text = text,
      color = Color.White,
      fontSize = 17.sp,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.padding(horizontal = 24.dp)
    )
  }
}

@Composable
private fun CloseTrailerButton(onClose: () -> Unit, modifier: Modifier = Modifier) {
  IconButton(
    onClick = onClose,
    modifier = modifier
      .size(48.dp)
      .background(Color.Black.copy(alpha = 0.62f), CircleShape)
      .border(1.dp, Color.White.copy(alpha = 0.38f), CircleShape)
  ) {
    Icon(
      imageVector = Icons.Default.Close,
      contentDescription = stringResource(R.string.action_close),
      tint = Color.White
    )
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TrailerWebView(youtubeKey: String) {
  var webView by remember { mutableStateOf<WebView?>(null) }
  var hasPageError by remember(youtubeKey) { mutableStateOf(false) }

  Box(modifier = Modifier.fillMaxSize()) {
    AndroidView(
      modifier = Modifier.fillMaxSize(),
      factory = { context ->
        WebView(context).apply {
          layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
          )
          setBackgroundColor(android.graphics.Color.BLACK)
          settings.javaScriptEnabled = true
          settings.domStorageEnabled = true
          settings.mediaPlaybackRequiresUserGesture = false
          settings.loadWithOverviewMode = true
          settings.useWideViewPort = true
          settings.userAgentString = TRAILER_USER_AGENT
          settings.setSupportZoom(false)
          webChromeClient = WebChromeClient()
          webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
              view: WebView?,
              request: WebResourceRequest?
            ): Boolean {
              val url = request?.url?.toString() ?: return true
              // false = carica in questa WebView; true = nessuna azione (mai browser esterno).
              return !isAllowedTrailerUrl(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
              hasPageError = false
            }

            override fun onReceivedError(
              view: WebView?,
              request: WebResourceRequest?,
              error: android.webkit.WebResourceError?
            ) {
              if (request?.isForMainFrame == true) hasPageError = true
            }
          }
          loadDataWithBaseURL(
            TRAILER_BASE_URL,
            trailerEmbedHtml(youtubeKey),
            "text/html",
            "UTF-8",
            null
          )
        }.also { webView = it }
      }
    )

    if (hasPageError) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.Black.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center
      ) {
        Text(
          text = stringResource(R.string.detail_trailer_error),
          color = Color.White,
          fontSize = 17.sp,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.padding(horizontal = 24.dp)
        )
      }
    }
  }

  DisposableEffect(youtubeKey) {
    onDispose {
      webView?.apply {
        stopLoading()
        loadUrl("about:blank")
        destroy()
      }
      webView = null
    }
  }
}
