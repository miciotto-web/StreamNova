package com.example.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import androidx.media3.extractor.text.SubtitleParser
import com.google.common.collect.ImmutableList

/**
 * Scelta della `MediaSource.Factory` di riproduzione (HLS o progressiva).
 *
 * Estratta dal composable del player perché la policy di errore applicata al ramo HLS è
 * un requisito verificabile in test JVM: nel ramo HLS la factory NON deve mai restare
 * quella di default di Media3, altrimenti il player usa il retry stock (3 tentativi con
 * backoff) e tratta come fatali:
 *
 *  - il fallimento di una traccia sottotitoli esterna (`PlayerLoadErrorHandlingPolicy`
 *    la riconosce come traccia testo e la fa scartare senza interrompere il video);
 *  - il 404 di un segmento di UNA rendition, che va invece esclusa lasciando riprodurre
 *    le altre rendition dello stesso flusso;
 *  - i 400/401/403/404/410 su manifest e playlist, che l'app vuole far fallire subito per
 *    passare alla sorgente successiva (fallback automatico già esistente).
 *
 * @param isHlsStream `true` quando la sorgente è HLS (m3u8 o hint della sorgente).
 * @param httpDataSourceFactory DataSource.Factory condivisa del player (headers inclusi).
 *   Deve essere schema-aware ([androidx.media3.datasource.DefaultDataSource.Factory] che
 *   incapsula quella HTTP): le tracce sottotitolo scaricate localmente usano `file://`,
 *   che DefaultHttpDataSource NON sa aprire (`UnsupportedSchemeException`).
 * @param progressiveMediaSourceFactory factory progressiva già configurata con la policy.
 * @param loadErrorHandlingPolicy policy dell'app, applicata al ramo HLS.
 */
internal fun resolvePlaybackMediaSourceFactory(
  isHlsStream: Boolean,
  httpDataSourceFactory: DataSource.Factory,
  progressiveMediaSourceFactory: MediaSource.Factory,
  loadErrorHandlingPolicy: LoadErrorHandlingPolicy
): MediaSource.Factory {
  val videoFactory =
    if (isHlsStream) {
      HlsMediaSource.Factory(httpDataSourceFactory)
        .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
    } else {
      progressiveMediaSourceFactory
    }
  return SubtitleMergingMediaSourceFactory(
    videoFactory = videoFactory,
    subtitleDataSourceFactory = httpDataSourceFactory,
    loadErrorHandlingPolicy = loadErrorHandlingPolicy
  )
}

/**
 * `MediaSource.Factory` che effettua il merge delle tracce esterne di sottotitoli del
 * `MediaItem` ([MediaItem.SubtitleConfiguration]) attorno alla sorgente video.
 *
 * In Media3 l'unico punto in cui i `SubtitleConfiguration` vengono uniti al video
 * (`MergingMediaSource` + sorgente della traccia) è `DefaultMediaSourceFactory`.
 * Una factory "specifica" come `HlsMediaSource.Factory` usata direttamente sulla player
 * SCARTA silenziosamente le configurazioni: i sottotitoli esterni (Stremio e OpenSubtitles
 * scaricato) non arrivano mai al render di testo. Questa factory replica il comportamento
 * di `DefaultMediaSourceFactory` **della versione Media3 effettivamente installata nel
 * repo** (i AAR fork NuvioTV in `app/libs/`), così resta invariata la selezione
 * HLS/progressiva e i suoi header/policy.
 *
 * ## Perché la traccia viene transcodificata in "Cues"
 *
 * Nella versione installata la decodifica dei sottotitoli "legacy" è DISABILITATA: il
 * renderer di testo accetta esclusivamente il formato Cues
 * (`application/x-media3-cues`). Allegare un file SRT/VTT/TTML/SSA con il suo MIME type
 * grezzo fa quindi fallire il player con
 * `IllegalStateException: Legacy decoding is disabled, can't handle
 * application/x-subrip samples (expected application/x-media3-cues)`,
 * riportata come `ERROR_CODE_FAILED_RUNTIME_CHECK`.
 *
 * `DefaultMediaSourceFactory` risolve il problema TRANSCODIFICANDO: quando il MIME type
 * della configurazione è supportato da un `SubtitleParser`, il file viene letto da un
 * `SubtitleExtractor` che produce campioni Cues, e la traccia viene esposta con MIME type
 * `application/x-media3-cues`. Questa factory fa esattamente lo stesso, replicando la
 * sequenza verificata sul bytecode dell'AAR:
 *
 *  1. `Format` costruita dalla `SubtitleConfiguration` (mime, lingua, flag, label, id);
 *  2. `Format` Cues derivata (`sampleMimeType = application/x-media3-cues`,
 *     `codecs` = mime originale, `cueReplacementBehavior` dal parser);
 *  3. `ProgressiveMediaSource` con un `ExtractorsFactory` che produce il
 *     `SubtitleExtractor` (parser + format della traccia).
 *
 * I formati non riconosciuti da nessun parser (nessun MIME type noto) restano su
 * `SingleSampleMediaSource`, che chiude la sola traccia come end-of-stream senza toccare
 * la riproduzione del video.
 */
@UnstableApi
internal class SubtitleMergingMediaSourceFactory(
  private val videoFactory: MediaSource.Factory,
  private val subtitleDataSourceFactory: DataSource.Factory,
  private val loadErrorHandlingPolicy: LoadErrorHandlingPolicy?
) : MediaSource.Factory {

  private val subtitleParserFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory()

  override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
    videoFactory.setDrmSessionManagerProvider(provider)
    return this
  }

  override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
    videoFactory.setLoadErrorHandlingPolicy(policy)
    return this
  }

  override fun getSupportedTypes(): IntArray = videoFactory.supportedTypes

  override fun createMediaSource(mediaItem: MediaItem): MediaSource {
    val localConfiguration = mediaItem.localConfiguration ?: return videoFactory.createMediaSource(mediaItem)
    val subtitleConfigurations = localConfiguration.subtitleConfigurations
    if (subtitleConfigurations.isEmpty()) {
      return videoFactory.createMediaSource(mediaItem)
    }

    // Il video delegato NON deve più portare le configurazioni: il merge è di questa
    // factory, la stessa sequenza di DefaultMediaSourceFactory.
    val videoOnly = mediaItem.buildUpon()
      .setSubtitleConfigurations(ImmutableList.of())
      .build()
    val sources = ArrayList<MediaSource>(subtitleConfigurations.size + 1)
    sources.add(videoFactory.createMediaSource(videoOnly))
    subtitleConfigurations.forEach { configuration ->
      sources.add(createSubtitleMediaSource(configuration))
    }
    return MergingMediaSource(*sources.toTypedArray())
  }

  /**
   * Sorgente Media3 di UNA traccia sottotitoli esterna.
   *
   * Formati supportati da un `SubtitleParser` (SRT, WebVTT, TTML, SSA/ASS): il file
   * viene transcodificato in Cues, unico formato accettato dal renderer di testo della
   * versione Media3 installata.
   *
   * Formati non supportati: la traccia viene trattata come sidecar "già serializzato" e
   * ogni errore di caricamento la chiude come end-of-stream, senza toccare il video.
   */
  private fun createSubtitleMediaSource(configuration: MediaItem.SubtitleConfiguration): MediaSource {
    val format = Format.Builder()
      .setSampleMimeType(configuration.mimeType ?: UNKNOWN_SUBTITLE_MIME_TYPE)
      .setLanguage(configuration.language)
      .setSelectionFlags(configuration.selectionFlags)
      .setRoleFlags(configuration.roleFlags)
      .setLabel(configuration.label)
      .setId(configuration.id)
      .build()

    if (subtitleParserFactory.supportsFormat(format)) {
      val cuesFormat = format.buildUpon()
        .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
        .setCodecs(format.sampleMimeType)
        .setCueReplacementBehavior(subtitleParserFactory.getCueReplacementBehavior(format))
        .build()
      val extractorsFactory = ExtractorsFactory {
        arrayOf(SubtitleExtractor(subtitleParserFactory.create(format), format))
      }
      val subtitleFactory = ProgressiveMediaSource.Factory(subtitleDataSourceFactory, extractorsFactory)
        .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy ?: DefaultLoadErrorHandlingPolicy())
      return subtitleFactory.createMediaSource(MediaItem.fromUri(configuration.uri.toString()))
    }

    val singleSampleFactory = SingleSampleMediaSource.Factory(subtitleDataSourceFactory)
    // Un errore di caricamento della traccia esterna chiude SOLO quella traccia come
    // end-of-stream, senza retry e senza toccare la riproduzione del video.
    singleSampleFactory.setTreatLoadErrorsAsEndOfStream(true)
    loadErrorHandlingPolicy?.let { singleSampleFactory.setLoadErrorHandlingPolicy(it) }
    return singleSampleFactory.createMediaSource(configuration, C.TIME_UNSET)
  }

  companion object {
    /** MIME type usato da Media3 quando la configurazione non ne dichiara uno. */
    private const val UNKNOWN_SUBTITLE_MIME_TYPE = "text/x-unknown"
  }
}
