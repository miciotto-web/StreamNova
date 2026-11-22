package com.example.ui.screens.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.streaming.diagnostics.playback.DiagnosisConfidence
import com.example.data.streaming.diagnostics.playback.HlsErrorCategory
import com.example.data.streaming.diagnostics.playback.HlsDiagnosticsReport
import com.example.data.streaming.diagnostics.playback.HlsPlaybackSession
import com.example.data.streaming.diagnostics.playback.PlaybackDiagnosis
import com.example.data.streaming.diagnostics.playback.PlaybackInspectorFormatter
import com.example.data.streaming.diagnostics.playback.PlaybackStateKind
import com.example.data.streaming.diagnostics.playback.SourceKind
import kotlinx.coroutines.delay

/**
 * Nova Playback Inspector.
 *
 * Pannello tecnico consultabile durante la riproduzione. Legge la sessione
 * corrente dal [com.example.data.streaming.diagnostics.playback.PlaybackDiagnosticsRegistry]
 * e mostra dati REALI osservati dal player, aggiornati ogni secondo:
 *
 *  - riproduzione: stato, posizione/durata, buffer, avvio, rebuffer, frame scartati;
 *  - video: qualità dichiarata/selezionata/rilevata, codec, fps, bitrate;
 *  - audio: codec, lingua, canali, frequenza, bitrate;
 *  - sottotitoli: traccia attiva o "nessuna traccia";
 *  - rete: banda stimata, throughput dei segmenti, byte scaricati;
 *  - sorgente: provider, tipo/protocollo, host delle richieste;
 *  - diagnosi finale e ultimi errori classificati.
 *
 * È un dialog passivo: non modifica il player né la selezione delle sorgenti.
 * Il pulsante "Copia" esporta il report in forma testuale (utile per assistenza).
 */
@Composable
fun PlaybackDiagnosticsPanel(
  session: HlsPlaybackSession?,
  onDismiss: () -> Unit
) {
  if (session == null) return
  val context = LocalContext.current

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
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(
          onClick = {
            copyReport(context, PlaybackInspectorFormatter.buildReportText(report))
          }
        ) { Text("Copia") }
        TextButton(onClick = onDismiss) { Text("Chiudi") }
      }
    },
    title = {
      Text(
        text = "Nova Playback Inspector",
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
        // ── Riproduzione ────────────────────────────────────────────────────
        SectionLabel("Riproduzione")
        DiagRow("Stato", stateLabel(report.playbackState))
        DiagRow(
          "Posizione",
          PlaybackInspectorFormatter.formatPosition(report.positionMs, report.durationMs)
        )
        DiagRow(
          "Buffer",
          PlaybackInspectorFormatter.formatBuffer(report.bufferAheadMs, report.durationMs)
        )
        DiagRow(
          "Avvio",
          report.startupMs?.let { "$it ms" }
            ?: if (report.playbackState == PlaybackStateKind.READY) "primo frame non ancora renderizzato" else "—"
        )
        DiagRow(
          "Rebuffer",
          if (report.rebufferCount > 0) "${report.rebufferCount} eventi · ${report.rebufferTotalMs} ms totali" else "nessun rebuffer"
        )
        DiagRow("Frame scartati", "${report.droppedVideoFrames}")

        // ── Video ───────────────────────────────────────────────────────────
        Spacer(modifier = Modifier.height(2.dp))
        SectionLabel("Video")
        DiagRow("Qualità dichiarata", report.declaredResolution ?: "—")
        DiagRow("Qualità selezionata", report.selectedResolution ?: "—")
        DiagRow("Qualità rilevata", report.detectedResolution ?: "—")
        DiagRow("Codec (flusso)", report.codec ?: "—")
        DiagRow("Traccia attiva", PlaybackInspectorFormatter.trackLabel(report.videoTrack))

        // ── Audio e sottotitoli ─────────────────────────────────────────────
        Spacer(modifier = Modifier.height(2.dp))
        SectionLabel("Audio")
        DiagRow("Traccia attiva", PlaybackInspectorFormatter.trackLabel(report.audioTrack))
        Spacer(modifier = Modifier.height(2.dp))
        SectionLabel("Sottotitoli")
        DiagRow(
          "Traccia attiva",
          report.subtitleTrack?.let { PlaybackInspectorFormatter.trackLabel(it) }
            ?: "nessuna traccia selezionata"
        )

        // ── Rete ────────────────────────────────────────────────────────────
        Spacer(modifier = Modifier.height(2.dp))
        SectionLabel("Rete")
        DiagRow(
          "Banda stimata",
          PlaybackInspectorFormatter.formatBitrate(
            report.bandwidthEstimateBps.takeIf { it > 0 }
          )
        )
        DiagRow(
          "Throughput segmenti",
          PlaybackInspectorFormatter.formatBitrate(report.throughputBps?.times(8))
        )
        if (report.mediaSegmentCount > 0) {
          DiagRow(
            "Segmenti",
            "${report.mediaSegmentCount} completati · " +
              PlaybackInspectorFormatter.formatBytes(report.mediaSegmentBytesTotal)
          )
        }
        report.httpEvents.lastOrNull()?.let { event ->
          DiagRow(
            "Ultima richiesta",
            "${event.phase.name.lowercase()} · ${event.hostId ?: "host sconosciuto"} · " +
              "${event.durationMs} ms" + (event.httpStatus?.let { " · HTTP $it" } ?: "")
          )
        }

        // ── Sorgente ────────────────────────────────────────────────────────
        Spacer(modifier = Modifier.height(2.dp))
        SectionLabel("Sorgente")
        DiagRow("Provider", report.providerId ?: "—")
        DiagRow("Contenuto", report.contentLabel ?: "—")
        DiagRow("Tipo", sourceKindLabel(report))
        DiagRow(
          "Durata sessione",
          "${report.totalDurationMs} ms · sessione #${report.sessionId}"
        )

        // ── Diagnosi ────────────────────────────────────────────────────────
        Spacer(modifier = Modifier.height(4.dp))
        SectionLabel("Classificazione")
        Text(
          text = PlaybackInspectorFormatter.classificationText(report),
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

/** Copia il report negli appunti (testo semplice, nessun URL completo). */
private fun copyReport(context: Context, text: String) {
  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
  clipboard.setPrimaryClip(ClipData.newPlainText("StreamNova Playback Inspector", text))
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
  SourceKind.HLS ->
    "HLS (${playlistKindLabel(report.playlistKind)}, ${report.protocol.name.lowercase()})"
  SourceKind.PROGRESSIVE ->
    "Progressivo (${report.protocol.name.lowercase()})"
  SourceKind.UNKNOWN -> "—"
}

private fun playlistKindLabel(kind: HlsDiagnosticsReport.PlaylistKind): String = when (kind) {
  HlsDiagnosticsReport.PlaylistKind.MASTER -> "master"
  HlsDiagnosticsReport.PlaylistKind.MEDIA -> "media"
  HlsDiagnosticsReport.PlaylistKind.NOT_HLS -> "non HLS"
  HlsDiagnosticsReport.PlaylistKind.UNKNOWN -> "tipo ignoto"
}

private fun stateLabel(state: PlaybackStateKind): String = when (state) {
  PlaybackStateKind.IDLE -> "IDLE"
  PlaybackStateKind.BUFFERING -> "BUFFERING"
  PlaybackStateKind.READY -> "READY"
  PlaybackStateKind.ENDED -> "ENDED"
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

private const val MAX_RECENT_DIAGNOSES = 5
