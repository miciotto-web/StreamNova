package com.example.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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
 * di errore (Error, con toast + fallback demo).
 */
@Composable
fun StreamStatusOverlay(result: StreamResult, modifier: Modifier = Modifier) {
  val loading = result as? StreamResult.Loading ?: return

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
        text = loading.message,
        color = NovaTextPrimary,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold
      )
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = "VixSrc • Streaming HTTP diretto",
        color = NovaTextMuted,
        fontSize = 12.sp
      )
    }
  }
}
