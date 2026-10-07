package com.example.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.example.domain.model.Subtitle

/**
 * Descrizione pura di una traccia sottotitolo esterna, pronta per diventare una
 * [MediaItem.SubtitleConfiguration].
 *
 * È separata da [MediaItem.SubtitleConfiguration] perché `Uri.parse` è un'API Android:
 * tenendo qui i valori grezzi il mapping resta verificabile in test JVM.
 */
internal data class SubtitleTrackSpec(
  val id: String,
  val url: String,
  val language: String,
  val label: String?,
  val mimeType: String,
  val roleFlags: Int = 0,
  val selectionFlags: Int = 0
)

/**
 * Costruisce le [MediaItem.SubtitleConfiguration] per i sottotitoli esterni (Stremio e OpenSubtitles v3).
 *
 * Ordine di precedenza:
 * 1. Sottotitoli embedded / stream-provided dichiarati dallo stream Stremio (`isStreamProvided == true`);
 * 2. Altri sottotitoli Stremio da endpoint;
 * 3. Sottotitoli OpenSubtitles v3 come tracce aggiuntive.
 */
internal object PlayerSubtitleMediaItemBuilder {

  /**
   * Rileva il corretto MIME type per Media3 a partire dall'URL e dall'etichetta del sottotitolo.
   * Supporta esplicitamente SubRip (.srt), WebVTT (.vtt, .webvtt), ASS/SSA (.ass, .ssa) e TTML.
   * Se l'estensione manca (es. link API download OpenSubtitles), ispeziona query string e label,
   * con fallback su SubRip (MimeTypes.APPLICATION_SUBRIP).
   */
  fun detectMimeType(url: String, label: String? = null): String {
    val normalizedPath = url
      .substringBefore('#')
      .substringBefore('?')
      .trimEnd('/')
      .lowercase()

    if (normalizedPath.endsWith(".vtt") || normalizedPath.endsWith(".webvtt")) {
      return MimeTypes.TEXT_VTT
    }
    if (normalizedPath.endsWith(".srt")) {
      return MimeTypes.APPLICATION_SUBRIP
    }
    if (normalizedPath.endsWith(".ass") || normalizedPath.endsWith(".ssa")) {
      return MimeTypes.TEXT_SSA
    }
    if (normalizedPath.endsWith(".ttml") || normalizedPath.endsWith(".dfxp")) {
      return MimeTypes.APPLICATION_TTML
    }

    val lowerUrl = url.lowercase()
    val lowerLabel = label?.lowercase().orEmpty()
    return when {
      lowerUrl.contains(".vtt") || lowerUrl.contains("format=vtt") || lowerUrl.contains("sub_format=vtt") || lowerLabel.contains(".vtt") ->
        MimeTypes.TEXT_VTT
      lowerUrl.contains(".ass") || lowerUrl.contains(".ssa") || lowerLabel.contains(".ass") ->
        MimeTypes.TEXT_SSA
      lowerUrl.contains(".ttml") || lowerLabel.contains(".ttml") ->
        MimeTypes.APPLICATION_TTML
      else ->
        MimeTypes.APPLICATION_SUBRIP
    }
  }

  /**
   * Mappa un [Subtitle] interno nella sua descrizione Media3.
   *
   * Riconosce ed imposta i flag per non udenti (SDH / CC) e forzati, oltre alla lingua
   * normalizzata e al MIME type corretto.
   *
   * @return `null` se il sottotitolo non è scaricabile (URL vuoto).
   */
  fun toSpec(subtitle: Subtitle): SubtitleTrackSpec? {
    val url = subtitle.url.trim()
    if (url.isEmpty()) return null
    val label = subtitle.label?.trim()?.takeIf { it.isNotEmpty() }
    val labelLower = label?.lowercase().orEmpty()

    val isSdh = labelLower.contains("[sdh]") ||
        labelLower.contains("sdh") ||
        labelLower.contains("hearing impaired") ||
        labelLower.contains("non udenti")

    val isForced = labelLower.contains("forced") ||
        labelLower.contains("forzato") ||
        labelLower.contains("forzati")

    val roleFlags = if (isSdh) {
      C.ROLE_FLAG_SUBTITLE or C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND or C.ROLE_FLAG_TRANSCRIBES_DIALOG
    } else {
      C.ROLE_FLAG_SUBTITLE
    }

    val selectionFlags = if (isForced) {
      C.SELECTION_FLAG_FORCED
    } else {
      0
    }

    val mimeType = detectMimeType(url, label)

    return SubtitleTrackSpec(
      id = subtitle.id,
      url = url,
      language = PlayerSubtitleUtils.normalizeLanguageCode(subtitle.lang),
      label = label,
      mimeType = mimeType,
      roleFlags = roleFlags,
      selectionFlags = selectionFlags
    )
  }

  /**
   * Elenco dei [SubtitleTrackSpec] utilizzabili, garantendo la precedenza:
   * 1. Sottotitoli embedded / stream-provided Stremio;
   * 2. Sottotitoli Stremio endpoint;
   * 3. Sottotitoli OpenSubtitles v3 aggiuntivi.
   */
  fun toSpecs(subtitles: List<Subtitle>): List<SubtitleTrackSpec> {
    val (streamProvided, external) = subtitles.partition { it.isStreamProvided }
    val (stremioExternal, openSubtitles) = external.partition { it.addonName != "OpenSubtitles v3" }
    val orderedSubtitles = streamProvided + stremioExternal + openSubtitles
    return orderedSubtitles.mapNotNull { toSpec(it) }
  }

  /** Conversione in [MediaItem.SubtitleConfiguration], stesso ordine di [toSpecs]. */
  fun toConfigurations(subtitles: List<Subtitle>): List<MediaItem.SubtitleConfiguration> =
    toSpecs(subtitles).map { spec ->
      MediaItem.SubtitleConfiguration.Builder(Uri.parse(spec.url))
        .setMimeType(spec.mimeType)
        .setLanguage(spec.language)
        .setLabel(spec.label)
        .setId(spec.id)
        .setRoleFlags(spec.roleFlags)
        .setSelectionFlags(spec.selectionFlags)
        .build()
    }

  /**
   * Allega i sottotitoli esterni al [MediaItem] del video.
   *
   * Con lista vuota non aggiunge alcuna `SubtitleConfiguration`: le tracce embedded
   * del video restano le uniche presenti, esattamente come prima.
   */
  fun buildMediaItem(uri: String, subtitles: List<Subtitle>): MediaItem {
    val configurations = toConfigurations(subtitles)
    val builder = MediaItem.Builder().setUri(Uri.parse(uri))
    if (configurations.isNotEmpty()) {
      builder.setSubtitleConfigurations(configurations)
    }
    return builder.build()
  }
}
