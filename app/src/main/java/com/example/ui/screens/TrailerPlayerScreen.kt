package com.example.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.ui.components.ModalOverlayState
import com.example.ui.components.TvFocusableBox
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright

/**
 * URL dell'iframe YouTube embedded (mai `youtu.be`/`watch`, che aprirebbero l'app).
 *
 * Nessun header/parametro di referrer spoofato: `enablejsapi=1` abilita l'API
 * del player, `fs=0` disabilita il pulsante fullscreen nativo della WebView.
 *
 * Sottotitoli e annotazioni spenti di default:
 * - `cc_load_policy=0`: non carica i sottotitoli (sovrimpressione) anche quando
 *   il video li ha pubblicati;
 * - `cc_lang_pref=` vuoto: nessuna lingua sottotitoli forzata;
 * - `iv_load_policy=3`: rimuove annotazioni/pop-up a schermo.
 */
internal fun trailerEmbedUrl(youtubeKey: String): String =
  "https://www.youtube.com/embed/$youtubeKey?autoplay=1&enablejsapi=1&fs=0&rel=0" +
    "&playsinline=1&cc_load_policy=0&cc_lang_pref=&iv_load_policy=3"

/**
 * HTML autonomo dell'overlay trailer: un solo IFrame standard a tutto schermo.
 *
 * La pagina viene caricata con base `https://localhost` (vedi
 * [loadDataWithBaseURL]): l'origin della pagina diventa un valore neutro, senza
 * header Referer/Origin spoofati verso YouTube (che YouTube rifiuta con
 * "Questo video non e' disponibile" - errore 152/153).
 *
 * [youtubeKey] - ID del video YouTube.
 */
internal fun trailerEmbedHtml(youtubeKey: String): String = """
<!DOCTYPE html>
<html>
<head>
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; background-color: #000; }
        html, body { width: 100%; height: 100%; overflow: hidden; background: #000; }
        iframe { width: 100%; height: 100%; border: none; }
    </style>
</head>
<body>
    <iframe
        id="trailerPlayer"
        src="${trailerEmbedUrl(youtubeKey)}"
        allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
        allowfullscreen>
    </iframe>
</body>
</html>
""".trimIndent()

/**
 * Base URL neutra usata da `loadDataWithBaseURL` per la pagina dell'overlay.
 */
private const val TRAILER_BASE_URL = "https://localhost"

/**
 * User-Agent desktop/Chrome: neutralizza i tag da WebView (`wv`, `Version/4.0`)
 * presenti nella UA di Android TV / Fire OS, che YouTube filtra su certi flussi.
 */
private const val TRAILER_USER_AGENT =
  "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

/**
 * true se l'URL puo' essere caricato nella WebView del trailer.
 *
 * Accetta esclusivamente i domini dell'embed e dei suoi asset multimediali
 * (YouTube, i media googlevideo, gli immagini ytimg, Google e la base locale):
 * qualsiasi altro link viene bloccato in fase di navigazione, cosi' la
 * riproduzione del trailer resta SEMPRE dentro StreamNova e nessun Intent puo'
 * aprire il browser o l'app esterna di YouTube.
 */
internal fun isAllowedTrailerUrl(url: String): Boolean {
  val host = url.substringAfter("://").substringBefore("/").substringAfter("@").lowercase()
  return host == "localhost" ||
    host == "youtube.com" ||
    host.endsWith(".youtube.com") ||
    host == "youtube-nocookie.com" ||
    host.endsWith(".youtube-nocookie.com") ||
    host == "googlevideo.com" ||
    host.endsWith(".googlevideo.com") ||
    host == "ytimg.com" ||
    host.endsWith(".ytimg.com") ||
    host == "google.com" ||
    host.endsWith(".google.com")
}

/**
 * Player interno del trailer: riproduce il video YouTube in-app tramite iframe embedded.
 *
 * MAI un Intent verso l'app esterna di YouTube: la WebView resta confinata ai domini
 * dell'embed ([isAllowedTrailerUrl]) e il BACK del telecomando chiude il trailer.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TrailerPlayerScreen(
  youtubeKey: String,
  onClose: () -> Unit,
  modifier: Modifier = Modifier
) {
  BackHandler { onClose() }

  // Il modale non deve far scattare la gestione LEFT della Home sottostante.
  DisposableEffect(Unit) {
    ModalOverlayState.onShown()
    onDispose { ModalOverlayState.onHidden() }
  }

  var isLoading by remember(youtubeKey) { mutableStateOf(true) }
  var hasError by remember(youtubeKey) { mutableStateOf(false) }
  val closeRequester = remember { FocusRequester() }

  // Focus iniziale sul pulsante chiudi: il telecomando ha sempre un punto di uscita.
  LaunchedEffect(youtubeKey) {
    repeat(8) {
      withFrameNanos { }
      try {
        closeRequester.requestFocus()
        return@LaunchedEffect
      } catch (_: IllegalStateException) {
      }
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black),
    contentAlignment = Alignment.Center
  ) {
    AndroidView(
      modifier = Modifier.fillMaxSize(),
      factory = { context ->
        WebView(context).apply {
          layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
          )
          // Configurazione per l'embed YouTube: autoplay senza gesto utente e
          // storage abilitati. L'UA desktop nasconde i tag da WebView (`wv`,
          // `Version/4.0`) che YouTube filtra su certi flussi video protetti.
          settings.javaScriptEnabled = true
          settings.domStorageEnabled = true
          settings.mediaPlaybackRequiresUserGesture = false
          settings.loadWithOverviewMode = true
          settings.useWideViewPort = true
          settings.userAgentString = TRAILER_USER_AGENT
          settings.setSupportZoom(false)
          webChromeClient = WebChromeClient()
          webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
              val url = request?.url?.toString() ?: return true
              // false = carica l'URL in questa WebView (domini multimediali
              // YouTube/Google consentiti); true = lo gestisce l'host (qui:
              // nessuna azione, quindi nessun browser/app YouTube esterna).
              return !isAllowedTrailerUrl(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
              isLoading = false
            }

            override fun onReceivedError(
              view: WebView?,
              request: WebResourceRequest?,
              error: android.webkit.WebResourceError?
            ) {
              if (request?.isForMainFrame == true) {
                isLoading = false
                hasError = true
              }
            }
          }
          // Nessun header Referer/Origin spoofato: la pagina locale con iframe
          // standard viene caricata con origin neutro (https://localhost).
          loadDataWithBaseURL(
            TRAILER_BASE_URL,
            trailerEmbedHtml(youtubeKey),
            "text/html",
            "UTF-8",
            null
          )
        }
      },
      onRelease = { webView ->
        webView.stopLoading()
        webView.destroy()
      }
    )

    if (isLoading && !hasError) {
      CircularProgressIndicator(
        modifier = Modifier.size(48.dp),
        color = NovaCyanBright,
        strokeWidth = 3.dp
      )
    }

    if (hasError) {
      Text(
        text = stringResource(R.string.detail_trailer_error),
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold
      )
    }

    TvFocusableBox(
      modifier = Modifier
        .align(Alignment.TopEnd)
        .padding(24.dp)
        .focusRequester(closeRequester),
      shape = CircleShape,
      focusedScale = 1.1f,
      onClick = onClose
    ) { isFocused ->
      Box(
        modifier = Modifier
          .size(48.dp)
          .background(
            if (isFocused) NovaCyan.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.6f),
            CircleShape
          )
          .border(
            width = if (isFocused) 2.dp else 1.dp,
            color = if (isFocused) NovaCyanBright else Color.White.copy(alpha = 0.35f),
            shape = CircleShape
          ),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = Icons.Default.Close,
          contentDescription = stringResource(R.string.action_cancel),
          tint = if (isFocused) NovaCyanBright else Color.White,
          modifier = Modifier.size(24.dp)
        )
      }
    }
  }
}

/** Overlay "recupero trailer in corso" mostrato mentre TMDB restituisce la chiave YouTube. */
@Composable
fun TrailerLoadingOverlay(onClose: () -> Unit, modifier: Modifier = Modifier) {
  TrailerStatusOverlay(
    text = stringResource(R.string.detail_trailer_loading),
    showSpinner = true,
    onClose = onClose,
    modifier = modifier
  )
}

/** Overlay "trailer non disponibile" (nessun video TMDB o ID assente). */
@Composable
fun TrailerErrorOverlay(onClose: () -> Unit, modifier: Modifier = Modifier) {
  TrailerStatusOverlay(
    text = stringResource(R.string.detail_trailer_error),
    showSpinner = false,
    onClose = onClose,
    modifier = modifier
  )
}

@Composable
private fun TrailerStatusOverlay(
  text: String,
  showSpinner: Boolean,
  onClose: () -> Unit,
  modifier: Modifier = Modifier
) {
  BackHandler { onClose() }

  DisposableEffect(Unit) {
    ModalOverlayState.onShown()
    onDispose { ModalOverlayState.onHidden() }
  }

  val closeRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) {
    repeat(8) {
      withFrameNanos { }
      try {
        closeRequester.requestFocus()
        return@LaunchedEffect
      } catch (_: IllegalStateException) {
      }
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black.copy(alpha = 0.92f)),
    contentAlignment = Alignment.Center
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      if (showSpinner) {
        CircularProgressIndicator(
          modifier = Modifier.size(48.dp),
          color = NovaCyanBright,
          strokeWidth = 3.dp
        )
        Spacer(modifier = Modifier.padding(top = 18.dp))
      }
      Text(
        text = text,
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold
      )
    }

    TvFocusableBox(
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(bottom = 40.dp)
        .focusRequester(closeRequester),
      shape = CircleShape,
      focusedScale = 1.08f,
      onClick = onClose
    ) { isFocused ->
      Box(
        modifier = Modifier
          .size(52.dp)
          .background(
            if (isFocused) NovaCyan.copy(alpha = 0.45f) else Color.Black.copy(alpha = 0.6f),
            CircleShape
          )
          .border(
            width = if (isFocused) 2.dp else 1.dp,
            color = if (isFocused) NovaCyanBright else Color.White.copy(alpha = 0.35f),
            shape = CircleShape
          ),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = Icons.Default.Close,
          contentDescription = stringResource(R.string.action_cancel),
          tint = if (isFocused) NovaCyanBright else Color.White,
          modifier = Modifier.size(26.dp)
        )
      }
    }
  }
}
