package com.example.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.streaming.diagnostics.playback.DiagnosisConfidence
import com.example.data.streaming.diagnostics.playback.HlsErrorCategory
import com.example.data.streaming.diagnostics.playback.HlsPlaybackSession
import com.example.data.streaming.diagnostics.playback.HlsDiagnosticsReport
import com.example.data.streaming.diagnostics.playback.PlaybackDiagnosis
import kotlinx.coroutines.delay

/**
 * Pannello tecnico diagnostico della riproduzione (FASE 8 del piano HLS
 * Diagnostics Engine).
 *
 * Interfaccia DISCRETA: è un dialog consultabile, non un componente nel flusso
 * di controllo del player. Legge la sessione corrente dal
 * [PlaybackDiagnosticsRegistry] e mostra: provider, stato dello stream,
 * qualità (dichiarata/selezionata/rilevata), tempo di avvio, buffering, ultimi
 * errori con classificazione.
 *
 * Nessun dato sensibile: la sessione non contiene URL completi, token o
 * credenziali (verificato dai test del report).
 */
@Composable
fun PlaybackDiagnosticsPanel(
  session: HlsPlaybackSession?,
  onDismiss: () -> Unit
) {
  if (session == null) return

  // Aggiornamento ~1s mentre il pannello è aperto: il report è letto live.
  var tick by remember { mutableLongStateOf(0L) }
  LaunchedEffect(session.sessionId) {
    while (true) {
      delay(1_000L)
      tick++
    }
  }
  val report = remember(tick, session.sessionId) { session.buildReport() }

  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = {
      TextButton(onClick = onDismiss) { Text("Chiudi") }
    },
    title = {
      Text(
        text = "Diagnostica riproduzione",
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold
      )
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        DiagRow("Provider", report.providerId ?: "—")
        DiagRow("Contenuto", report.contentLabel ?: "—")
        DiagRow("Sorgente", sourceKindLabel(report))
        DiagRow("Stato", stateLabel(report.playbackState))
        DiagRow("Qualità dichiarata", report.declaredResolution ?: "—")
        DiagRow("Qualità selezionata", report.selectedResolution ?: "—")
        DiagRow("Qualità rilevata", report.detectedResolution ?: "—")
        DiagRow("Codec", report.codec ?: "—")
        DiagRow(
          "Avvio",
          report.startupMs?.let { "${it} ms" } ?: if (report.playbackState == com.example.data.streaming.diagnostics.playback.PlaybackStateKind.READY) "primo frame non ancora renderizzato" else "—"
        )
        DiagRow(
          "Buffering",
          if (report.rebufferCount > 0) "${report.rebufferCount} eventi · ${report.rebufferTotalMs} ms totali" else "nessun rebuffer"
        )
        if (report.mediaSegmentCount > 0) {
          DiagRow(
            "Segmenti",
            "${report.mediaSegmentCount} completati · ${(report.mediaSegmentBytesTotal / 1024)} KB"
          )
        }
        DiagRow(
          "Durata sessione",
          "${report.totalDurationMs} ms · sessione #${report.sessionId}"
        )

        Spacer(modifier = Modifier.height(4.dp))
        SectionLabel("Classificazione")
        Text(
          text = finalClassificationLabel(report),
          fontSize = 12.sp,
          fontFamily = FontFamily.Monospace,
          color = if (report.finalDiagnosis == null) Color(0xFF86EFAC) else Color(0xFFFCA5A5),
          modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(6.dp))
            .padding(8.dp)
        )

        val recent = report.diagnoses.takeLast(MAX_RECENT_DIAGNOSES).reversed()
        if (recent.isNotEmpty()) {
          Spacer(modifier = Modifier.height(4.dp))
          SectionLabel("Ultimi errori (${report.diagnoses.size} totali)")
          recent.forEach { diagnosis ->
            DiagnosiRow(diagnosis)
          }
        }

        val lastEvent = report.lastSuccessfulEvent
        if (lastEvent != null) {
          Spacer(modifier = Modifier.height(4.dp))
          DiagRow("Ultimo evento riuscito", lastEvent)
        }
      }
    }
  )
}

@Composable
private fun DiagRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.Top
  ) {
    Text(
      text = label,
      fontSize = 12.sp,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    )
    Text(
      text = value,
      fontSize = 12.sp,
      fontFamily = FontFamily.Monospace,
      fontWeight = FontWeight.Medium,
      modifier = Modifier.padding(start = 12.dp)
    )
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(
    text = text,
    fontSize = 12.sp,
    fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.primary
  )
}

@Composable
private fun DiagnosiRow(diagnosis: PlaybackDiagnosis) {
  Text(
    text = buildString {
      append(categoryLabel(diagnosis.category))
      append(" · fase=${diagnosis.phase.name.lowercase()}")
      diagnosis.httpCode?.let { append(" · HTTP $it") }
      append(" · x${diagnosis.attemptCount}")
      append(" · ${if (diagnosis.temporary) "temporaneo" else "definitivo"}")
      append(" · conf=${confidenceLabel(diagnosis.confidence)}")
      diagnosis.technicalMessage?.let { append("\n") ; append(it) }
    },
    fontSize = 11.sp,
    fontFamily = FontFamily.Monospace,
    color = Color(0xFFFCA5A5),
    modifier = Modifier
      .fillMaxWidth()
      .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(6.dp))
      .padding(8.dp)
  )
}

private fun sourceKindLabel(report: HlsDiagnosticsReport): String = when (report.sourceKind) {
  com.example.data.streaming.diagnostics.playback.SourceKind.HLS ->
    "HLS (${playlistKindLabel(report.playlistKind)}, ${report.protocol.name.lowercase()})"
  com.example.data.streaming.diagnostics.playback.SourceKind.PROGRESSIVE ->
    "Progressivo (${report.protocol.name.lowercase()})"
  com.example.data.streaming.diagnostics.playback.SourceKind.UNKNOWN -> "—"
}

private fun playlistKindLabel(kind: HlsDiagnosticsReport.PlaylistKind): String = when (kind) {
  HlsDiagnosticsReport.PlaylistKind.MASTER -> "master"
  HlsDiagnosticsReport.PlaylistKind.MEDIA -> "media"
  HlsDiagnosticsReport.PlaylistKind.NOT_HLS -> "non HLS"
  HlsDiagnosticsReport.PlaylistKind.UNKNOWN -> "tipo ignoto"
}

private fun stateLabel(state: com.example.data.streaming.diagnostics.playback.PlaybackStateKind): String =
  when (state) {
    com.example.data.streaming.diagnostics.playback.PlaybackStateKind.IDLE -> "IDLE"
    com.example.data.streaming.diagnostics.playback.PlaybackStateKind.BUFFERING -> "BUFFERING"
    com.example.data.streaming.diagnostics.playback.PlaybackStateKind.READY -> "READY"
    com.example.data.streaming.diagnostics.playback.PlaybackStateKind.ENDED -> "ENDED"
  }

private fun categoryLabel(category: HlsErrorCategory): String = when (category) {
  HlsErrorCategory.NETWORK_ERROR -> "Rete"
  HlsErrorCategory.HTTP_ERROR -> "HTTP"
  HlsErrorCategory.TLS_ERROR -> "TLS"
  HlsErrorCategory.MANIFEST_ERROR -> "Manifest"
  HlsErrorCategory.SEGMENT_ERROR -> "Segmento"
  HlsErrorCategory.SOURCE_UNAVAILABLE -> "Sorgente non disponibile"
  HlsErrorCategory.AUTHORIZATION_ERROR -> "Autorizzazione"
  HlsErrorCategory.DECODER_ERROR -> "Decoder"
  HlsErrorCategory.FORMAT_UNSUPPORTED -> "Formato non supportato"
  HlsErrorCategory.BUFFERING_TIMEOUT -> "Timeout buffering"
  HlsErrorCategory.UNKNOWN_ERROR -> "Sconosciuto"
}

private fun confidenceLabel(confidence: DiagnosisConfidence): String = when (confidence) {
  DiagnosisConfidence.HIGH -> "alta"
  DiagnosisConfidence.MEDIUM -> "media"
  DiagnosisConfidence.LOW -> "bassa"
}

private fun finalClassificationLabel(report: HlsDiagnosticsReport): String {
  val diagnosis = report.finalDiagnosis ?: return "Nessun problema segnalato"
  val kind = if (diagnosis.temporary) "condizione temporanea" else "evidenza definitiva"
  val retryHint = if (diagnosis.temporary) " · un nuovo tentativo è legittimo" else ""
  return buildString {
    append(categoryLabel(diagnosis.category))
    append(" — ")
    append(kind)
    append(retryHint)
    diagnosis.httpCode?.let { append(" · HTTP $it") }
    append(" · x${diagnosis.attemptCount}")
  }
}

private const val MAX_RECENT_DIAGNOSES = 5
