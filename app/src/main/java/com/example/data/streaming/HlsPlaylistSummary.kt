package com.example.data.streaming

import com.example.data.streaming.diagnostics.playback.HlsDiagnosticsReport

/**
 * Tipo di playlist HLS rilevata dal parser.
 *
 *  - [MASTER]: master playlist (contiene `#EXT-X-STREAM-INF`);
 *  - [MEDIA]: media playlist (contiene segmenti `#EXTINF`);
 *  - [NOT_HLS]: testo che non inizia con `#EXTM3U` (pagina HTML, JSON, …);
 *  - [UNKNOWN]: non classificabile (prefisso valido ma nessuna direttiva utile).
 */
enum class HlsPlaylistKind { MASTER, MEDIA, NOT_HLS, UNKNOWN }

/**
 * Mappatura esplicita verso il tipo di playlist del report di riproduzione: i
 * due enum restano separati (uno descrive il testo analizzato, l'altro il
 * report), la conversione è totale e senza default nascosti.
 */
fun HlsPlaylistKind.toReportPlaylistKind(): HlsDiagnosticsReport.PlaylistKind = when (this) {
  HlsPlaylistKind.MASTER -> HlsDiagnosticsReport.PlaylistKind.MASTER
  HlsPlaylistKind.MEDIA -> HlsDiagnosticsReport.PlaylistKind.MEDIA
  HlsPlaylistKind.NOT_HLS -> HlsDiagnosticsReport.PlaylistKind.NOT_HLS
  HlsPlaylistKind.UNKNOWN -> HlsDiagnosticsReport.PlaylistKind.UNKNOWN
}

/**
 * Rendition dichiarata da una direttiva `#EXT-X-MEDIA`.
 *
 * [type] è il valore letterale dell'attributo (`AUDIO`, `SUBTITLES`, `CLOSED-CAPTIONS`).
 * I campi mancanti restano `null`: nessun metadato inventato.
 */
data class HlsRendition(
  val type: String,
  val name: String?,
  val language: String?,
  val isDefault: Boolean,
  val uri: String?
)

/**
 * Sintesi strutturata di una playlist HLS, a supporto della diagnostica.
 *
 * Distinguere è ESSENZIALE per il report di riproduzione:
 *  - [declaredResolutionHeight]: altezza dichiarata dalla playlist (varianti o
 *    `RESOLUTION` della variante scelta);
 *  - la qualità della variante selezionata e quella rilevata dal player non
 *    fanno parte di questo modello: sono campi del report, alimentati dal
 *    collector in riproduzione.
 *
 * Solo dati effettivamente presenti nel testo: nessuna qualità dedotta.
 */
data class HlsPlaylistSummary(
  val kind: HlsPlaylistKind,
  /** Varianti della master (vuoto per una media playlist). */
  val variants: List<HlsVariant>,
  /** Altezza convenzionale massima dichiarata dalle varianti (null se assente). */
  val declaredResolutionHeight: Int?,
  /** Codec dichiarati negli attributi `CODECS=` delle varianti (ordine di apparizione). */
  val declaredCodecs: List<String>,
  val audioRenditions: List<HlsRendition>,
  val subtitleRenditions: List<HlsRendition>,
  /** Numero di segmenti `#EXTINF` di una media playlist (0 per una master). */
  val segmentCount: Int,
  /** Somma delle durate `#EXTINF` in secondi (null se non dichiarate). */
  val totalDurationSec: Double?,
  /** `#EXT-X-TARGETDURATION` in secondi (null se assente). */
  val targetDurationSec: Int?,
  /** Presente di un segmento di inizializzazione `#EXT-X-MAP`. */
  val hasInitSegment: Boolean,
  /**
   * `true` solo per una media playlist **senza** `#EXT-X-ENDLIST` (VOD/live
   * dichiarati esplicitamente dal testo; l'assenza di indizi NON è una prova).
   */
  val isLiveStream: Boolean?
)

/**
 * Parser di sintesi HLS: **solo lettura**, additive rispetto a [HlsPlaylist]
 * (che resta il parser usato dai provider e dal verificatore).
 *
 * Nessuna modifica alla logica dei resolver: qui non si sceglie nulla, si
 * descrive ciò che il testo dichiara.
 */
object HlsPlaylistSummaryParser {

  fun analyze(playlist: String, playlistUrl: String): HlsPlaylistSummary {
    if (!HlsPlaylist.isHls(playlist)) {
      return empty(HlsPlaylistKind.NOT_HLS)
    }
    val lines = playlist.lineSequence().map { it.trim() }.toList()

    val variants = HlsPlaylist.variants(playlist, playlistUrl)
    val isMaster = variants.isNotEmpty()

    val audio = mutableListOf<HlsRendition>()
    val subtitles = mutableListOf<HlsRendition>()
    var segmentCount = 0
    var totalDuration = 0.0
    var hasDurations = false
    var targetDuration: Int? = null
    var hasInitSegment = false
    var hasEndList = false

    for (line in lines) {
      when {
        line.startsWith("#EXT-X-MEDIA:") -> {
          val rendition = parseMediaRendition(line, playlistUrl) ?: continue
          when (rendition.type.uppercase()) {
            "AUDIO" -> audio += rendition
            "SUBTITLES" -> subtitles += rendition
          }
        }
        line.startsWith("#EXTINF") -> {
          segmentCount++
          val seconds = line.substringAfter(':', "").trim()
            .substringBefore(',').toDoubleOrNull()
          if (seconds != null && seconds >= 0) {
            totalDuration += seconds
            hasDurations = true
          }
        }
        line.startsWith("#EXT-X-TARGETDURATION:") -> {
          targetDuration = line.substringAfter(':').trim().toIntOrNull() ?: targetDuration
        }
        line.startsWith("#EXT-X-MAP") -> hasInitSegment = true
        line.startsWith("#EXT-X-ENDLIST") -> hasEndList = true
      }
    }

    val declaredHeight = variants.mapNotNull { it.height }.maxOrNull()
    val declaredCodecs = variants
      .flatMap { it.codecs }
      .distinct()

    return HlsPlaylistSummary(
      kind = if (isMaster) HlsPlaylistKind.MASTER else HlsPlaylistKind.MEDIA,
      variants = variants,
      declaredResolutionHeight = declaredHeight,
      declaredCodecs = declaredCodecs,
      audioRenditions = audio,
      subtitleRenditions = subtitles,
      segmentCount = segmentCount,
      totalDurationSec = if (hasDurations) totalDuration else null,
      targetDurationSec = targetDuration,
      hasInitSegment = hasInitSegment,
      // VOD dichiarato con ENDLIST; una master non dichiara né live né VOD.
      isLiveStream = if (!isMaster) !hasEndList else null
    )
  }

  /** `#EXT-X-MEDIA:TYPE=AUDIO,NAME="Italiano",LANGUAGE=it,DEFAULT=YES,URI="..."` */
  private fun parseMediaRendition(line: String, playlistUrl: String): HlsRendition? {
    val attributes = line.substringAfter(':', "")
    if (attributes.isBlank()) return null
    val type = attributeValue(attributes, "TYPE") ?: return null
    val name = attributeValue(attributes, "NAME")
    val language = attributeValue(attributes, "LANGUAGE")
    val isDefault = attributeValue(attributes, "DEFAULT")?.equals("YES", ignoreCase = true) == true
    val uri = attributeValue(attributes, "URI")?.let { HlsPlaylist.resolveUri(playlistUrl, it) }
    return HlsRendition(type = type, name = name, language = language, isDefault = isDefault, uri = uri)
  }

  private fun attributeValue(attributes: String, name: String): String? {
    val regex = ATTRIBUTE_REGEX_CACHE.getOrPut(name) {
      Regex("""(?i)(?:^|,)\s*$name\s*=\s*("(?:[^"]*)"|[^,]*)""")
    }
    val match = regex.find(attributes) ?: return null
    return match.groupValues[1].removeSurrounding("\"").trim().takeIf { it.isNotEmpty() }
  }

  private fun empty(kind: HlsPlaylistKind) = HlsPlaylistSummary(
    kind = kind,
    variants = emptyList(),
    declaredResolutionHeight = null,
    declaredCodecs = emptyList(),
    audioRenditions = emptyList(),
    subtitleRenditions = emptyList(),
    segmentCount = 0,
    totalDurationSec = null,
    targetDurationSec = null,
    hasInitSegment = false,
    isLiveStream = null
  )

  private val ATTRIBUTE_REGEX_CACHE = java.util.concurrent.ConcurrentHashMap<String, Regex>()
}
