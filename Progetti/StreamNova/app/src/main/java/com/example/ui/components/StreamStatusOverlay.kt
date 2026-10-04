package com.example.ui.components

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import com.example.R
import com.example.data.prefs.StreamingEngineMode
import com.example.data.streaming.StreamResult
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary

/**
 * Overlay a schermo intero mostrato mentre il provider streaming sta estraendo
 * le sorgenti ([StreamResult.Loading]).
 *
 * Non contiene elementi focusabili: non sottrae il focus D-Pad alla schermata
 * sottostante e sparisce non appena il player viene aperto (Success) o in caso
 * di errore (Error, con messaggio all'utente).
 */
@Composable
fun StreamStatusOverlay(
  modifier: Modifier = Modifier,
  message: String? = null,
  streamingEngineMode: StreamingEngineMode = StreamingEngineMode.HTTP_WEB
) {
  DisposableEffect(Unit) {
    onDispose {
      Log.d("BACK_TRACE", "STREAMSTATUSOVERLAY: passa da Loading ad altro stato (disposed)")
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .zIndex(50f)
      .background(Color.Black.copy(alpha = 0.78f)),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier
        .background(NovaSurface, RoundedCornerShape(16.dp))
        .border(1.5.dp, NovaCyan, RoundedCornerShape(16.dp))
        .padding(horizontal = 40.dp, vertical = 32.dp)
    ) {
      CircularProgressIndicator(
        color = NovaCyanBright,
        modifier = Modifier.size(46.dp)
      )
      Spacer(modifier = Modifier.height(18.dp))
      Text(
        text = if (message.isNullOrBlank() || message == "Ricerca sorgenti in corso...") {
          stringResource(R.string.stream_status_searching_sources)
        } else {
          message
        },
        color = NovaTextPrimary,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold
      )
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = when (streamingEngineMode) {
          StreamingEngineMode.DEBRID_TORBOX -> stringResource(R.string.stream_status_torbox_stremio)
          StreamingEngineMode.HTTP_WEB -> stringResource(R.string.stream_status_vixsrc_http)
        },
        color = NovaTextMuted,
        fontSize = 12.sp
      )
    }
  }
}

@Composable
fun StreamStatusOverlay(
  result: StreamResult,
  modifier: Modifier = Modifier,
  streamingEngineMode: StreamingEngineMode = StreamingEngineMode.HTTP_WEB
) {
  val loading = result as? StreamResult.Loading ?: run {
    Log.d("BACK_TRACE", "STREAMSTATUSOVERLAY: passa da Loading ad altro stato ($result)")
    return
  }
  StreamStatusOverlay(
    modifier = modifier,
    message = loading.message,
    streamingEngineMode = streamingEngineMode
  )
}
