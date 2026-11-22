package com.example.data.streaming.diagnostics.playback

import java.util.Locale

/**
 * Formattazione del report del Nova Playback Inspector.
 *
 * Object **puro** (nessun tipo Android): le stesse funzioni alimentano il pannello
 * Compose, il testo copiato negli appunti e i test. Regole dichiarate:
 *  - un dato assente non viene mai inventato: si mostra "—";
 *  - le unità sono quelle leggibili (`Mbps`, `MB`, `kHz`), non valori grezzi;
 *  - il testo esportato non contiene URL completi (il modello registra solo host).
 */
object PlaybackInspectorFormatter {

  /** Bitrate leggibile: "12.4 Mbps", "850 kbps", "—" se non disponibile. */
  fun formatBitrate(bps: Long?): String {
    if (bps == null || bps <= 0) return "—"
    return when {
      bps >= 1_000_000 -> String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)
      bps >= 1_000 -> "${bps / 1_000} kbps"
      else -> "$bps bps"
    }
  }

  /** Dimensione leggibile: "1.25 GB", "420.5 MB", "—" se non disponibile. */
  fun formatBytes(bytes: Long?): String {
    if (bytes == null || bytes <= 0) return "—"
    return when {
      bytes >= 1024L * 1024 * 1024 ->
        String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
      bytes >= 1024L * 1024 ->
        String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
      bytes >= 1024 -> "${bytes / 1024} KB"
      else -> "$bytes B"
    }
  }

  /** Durata/posizione in h:mm:ss (o m:ss sotto l'ora); "—" se negativa. */
  fun formatDurationMs(ms: Long?): String {
    if (ms == null || ms < 0) return "—"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
      String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
      String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
  }

  /** "00:12:34 / 01:45:00 · 12%". */
  fun formatPosition(positionMs: Long?, durationMs: Long?): String {
    val position = formatDurationMs(positionMs)
    if (durationMs == null || durationMs <= 0 || positionMs == null || positionMs < 0) {
      return position
    }
    val percent = ((positionMs * 100.0) / durationMs).coerceIn(0.0, 100.0)
    return String.format(
      Locale.US,
      "%s / %s · %.0f%%",
      position,
      formatDurationMs(durationMs),
      percent
    )
  }

  /** "24 s (1%)" oppure "—". */
  fun formatBuffer(aheadMs: Long?, durationMs: Long?): String {
    if (aheadMs == null || aheadMs < 0) return "—"
    val seconds = aheadMs / 1000
    if (durationMs != null && durationMs > 0) {
      val percent = (aheadMs * 100.0 / durationMs).coerceIn(0.0, 100.0)
      return String.format(Locale.US, "%d s (%.0f%%)", seconds, percent)
    }
    return "$seconds s"
  }

  fun formatFrameRate(fps: Float?): String =
    if (fps == null || fps <= 0f) "—" else String.format(Locale.US, "%.2f fps", fps)

  fun formatFrequency(hz: Int?): String =
    if (hz == null || hz <= 0) "—" else String.format(Locale.US, "%.1f kHz", hz / 1000.0)

  /** Riga tecnica compatta di una traccia: codec · lingua · dettagli. */
  fun trackLabel(track: ObservedTrackFormat?): String {
    if (track == null) return "—"
    val parts = mutableListOf<String>()
    (track.codecs ?: track.sampleMimeType)?.takeIf { it.isNotBlank() }?.let { parts += it }
    track.language?.takeIf { it.isNotBlank() }?.let { parts += it }
    track.label?.takeIf { it.isNotBlank() }?.let { parts += it }
    if (track.width > 0 && track.height > 0) parts += "${track.width}x${track.height}"
    if (track.frameRate != null && track.frameRate > 0f) parts += formatFrameRate(track.frameRate)
    if (track.channelCount != null) parts += "${track.channelCount} canali"
    if (track.sampleRateHz != null) parts += formatFrequency(track.sampleRateHz)
    if (track.bitrateBps != null) parts += formatBitrate(track.bitrateBps)
    if (parts.isEmpty()) parts += if (track.hasTechnicalData) "formato non dichiarato" else "traccia presente"
    return parts.joinToString(" · ")
  }

  /** Testo multi-riga esportabile (appunti/condivisione) di UNA sessione. */
  fun buildReportText(report: HlsDiagnosticsReport): String = buildString {
    appendLine("StreamNova · Playback Inspector")
    appendLine("Sessione #${report.sessionId}" +
      (report.providerId?.let { " · provider $it" } ?: "") +
      (report.contentLabel?.let { " · $it" } ?: ""))
    appendLine("Stato: ${report.playbackState.name}")
    appendLine("Posizione: ${formatPosition(report.positionMs, report.durationMs)}")
    appendLine("Buffer: ${formatBuffer(report.bufferAheadMs, report.durationMs)}")
    appendLine(
      "Sorgente: ${sourceKindText(report)} · protocollo ${report.protocol.name.lowercase()}"
    )
    appendLine(
      "Qualità: dichiarata ${report.declaredResolution ?: "—"} · " +
        "selezionata ${report.selectedResolution ?: "—"} · " +
        "rilevata ${report.detectedResolution ?: "—"}"
    )
    appendLine("Video: ${trackLabel(report.videoTrack) }")
    appendLine("Codec (flusso): ${report.codec ?: "—"}")
    appendLine("Audio: ${trackLabel(report.audioTrack)}")
    appendLine("Sottotitoli: ${trackLabel(report.subtitleTrack)}")
    appendLine(
      "Rete: banda stimata ${formatBitrate(report.bandwidthEstimateBps.takeIf { it > 0 })} · " +
        "throughput ${formatBitrate(report.throughputBps?.times(8))} · " +
        "segmenti ${report.mediaSegmentCount} (${formatBytes(report.mediaSegmentBytesTotal)})"
    )
    appendLine(
      "Avvio: ${report.startupMs?.let { "$it ms" } ?: "—"} · " +
        "rebuffer: ${report.rebufferCount} (${report.rebufferTotalMs} ms) · " +
        "frame scartati: ${report.droppedVideoFrames}"
    )
    appendLine("Durata sessione: ${report.totalDurationMs} ms")
    if (report.httpEvents.isNotEmpty()) {
      appendLine("Ultime richieste HTTP:")
      report.httpEvents.takeLast(5).forEach { event ->
        appendLine(
          " · ${event.phase.name.lowercase()} ${event.hostId ?: "host sconosciuto"} " +
            "HTTP ${event.httpStatus ?: "ok"} · ${event.durationMs} ms · " +
            "${formatBytes(event.bytesLoaded)}" +
            if (event.isError) " · ERRORE" else ""
        )
      }
    }
    appendLine(
      "Diagnosi: ${report.finalDiagnosis?.let { classificationText(report) } ?: "nessun problema segnalato"}"
    )
  }.trimEnd()

  private fun sourceKindText(report: HlsDiagnosticsReport): String = when (report.sourceKind) {
    SourceKind.HLS -> when (report.playlistKind) {
      HlsDiagnosticsReport.PlaylistKind.MASTER -> "HLS master"
      HlsDiagnosticsReport.PlaylistKind.MEDIA -> "HLS media"
      HlsDiagnosticsReport.PlaylistKind.NOT_HLS -> "HLS non valido"
      HlsDiagnosticsReport.PlaylistKind.UNKNOWN -> "HLS"
    }
    SourceKind.PROGRESSIVE -> "File progressivo"
    SourceKind.UNKNOWN -> "Sconosciuta"
  }

  /** Etichetta neutra della diagnosi finale (categoria + temporaneità). */
  fun classificationText(report: HlsDiagnosticsReport): String {
    val diagnosis = report.finalDiagnosis ?: return "nessun problema segnalato"
    val kind = if (diagnosis.temporary) "condizione temporanea" else "evidenza definitiva"
    return buildString {
      append(diagnosis.category.name.lowercase().replace('_', ' '))
      append(" — ")
      append(kind)
      diagnosis.httpCode?.let { append(" · HTTP $it") }
      append(" · x${diagnosis.attemptCount}")
    }
  }
}
