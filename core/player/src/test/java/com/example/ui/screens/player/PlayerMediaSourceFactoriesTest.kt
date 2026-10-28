package com.example.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSourceEventListener
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * La policy di errore dell'app deve essere applicata a OGNI MediaSource.Factory del player,
 * HLS compreso, e le tracce di sottotitolo esterne del `MediaItem` (Stremio e OpenSubtitles
 * scaricato in locale) devono arrivare DAVVERO alle sorgenti:
 *
 * - In Media3 il merge dei `SubtitleConfiguration` (`MergingMediaSource` +
 *   `SingleSampleMediaSource`) avviene SOLO dentro `DefaultMediaSourceFactory`: una
 *   `HlsMediaSource.Factory` usata direttamente dalla player scarta silenziosamente le
 *   configurazioni, e i sottotitoli esterni non compaiono mai (nessun errore, nessuna
 *   attivazione). Il ramo HLS deve quindi essere avvolto in
 *   [SubtitleMergingMediaSourceFactory].
 * - I sottotitoli scaricati da OpenSubtitles arrivano come URI `file://` su cache locale:
 *   la [DataSource.Factory] del player deve essere schema-aware
 *   ([DefaultDataSource.Factory] attorno alla factory HTTP) altrimenti il caricamento della
 *   traccia fallisce con UnsupportedSchemeException e la ripresa toglie proprio il
 *   sottotitolo scaricato.
 *
 * Il test legge campi privati interni di Media3 (`loadErrorHandlingPolicy` di
 * `HlsMediaSource.Factory`): se la libreria li rinomina va aggiornata la VERIFICA, non la
 * policy. Nessuna dipendenza da servizi esterni.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerMediaSourceFactoriesTest {

  private val dataSourceFactory = DefaultHttpDataSource.Factory()
  private val policy = PlayerLoadErrorHandlingPolicy()

  /** Factory progressiva fittizia: al test interessano identità d'istanza e MediaItem ricevuti. */
  private class StubMediaSourceFactory : MediaSource.Factory {
    var appliedPolicy: LoadErrorHandlingPolicy? = null
      private set
    var lastMediaItem: MediaItem? = null
      private set
    var lastWasCreated = false
      private set

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory = this

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
      appliedPolicy = policy
      return this
    }

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
      lastMediaItem = mediaItem
      lastWasCreated = true
      return EmptyMediaSource(mediaItem)
    }
  }

  /** MediaSource minima restituita dallo stub: nessun comportamento di playback. */
  private class EmptyMediaSource(private var item: MediaItem) : MediaSource {
    override fun getMediaItem(): MediaItem = item
    override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean = true
    override fun updateMediaItem(mediaItem: MediaItem) {
      item = mediaItem
    }

    override fun addEventListener(handler: android.os.Handler, listener: MediaSourceEventListener) {}
    override fun removeEventListener(listener: MediaSourceEventListener) {}
    override fun addDrmEventListener(handler: android.os.Handler, listener: androidx.media3.exoplayer.drm.DrmSessionEventListener) {}
    override fun removeDrmEventListener(listener: androidx.media3.exoplayer.drm.DrmSessionEventListener) {}
    override fun prepareSource(
      caller: MediaSource.MediaSourceCaller,
      transferListener: TransferListener?,
      playerId: PlayerId
    ) {
    }

    override fun maybeThrowSourceInfoRefreshError() {}
    override fun enable(caller: MediaSource.MediaSourceCaller) {}
    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
      throw UnsupportedOperationException("stub")

    override fun releasePeriod(mediaPeriod: MediaPeriod) {}
    override fun disable(caller: MediaSource.MediaSourceCaller) {}
    override fun releaseSource(caller: MediaSource.MediaSourceCaller) {}
  }

  private fun innerVideoFactory(factory: MediaSource.Factory): MediaSource.Factory {
    val field = SubtitleMergingMediaSourceFactory::class.java.getDeclaredField("videoFactory")
    field.isAccessible = true
    return field.get(factory) as MediaSource.Factory
  }

  private fun policyOfHlsFactory(factory: MediaSource.Factory): Any? {
    val field = HlsMediaSource.Factory::class.java.getDeclaredField("loadErrorHandlingPolicy")
    field.isAccessible = true
    return field.get(factory)
  }

  private fun wrapperPolicyOf(factory: MediaSource.Factory): Any? {
    val field = SubtitleMergingMediaSourceFactory::class.java.getDeclaredField("loadErrorHandlingPolicy")
    field.isAccessible = true
    return field.get(factory)
  }

  /** Sorgenti figlie del merge: video + una per ogni traccia sottotitoli esterna. */
  private fun mergedSources(source: MediaSource): List<MediaSource> {
    val field = source.javaClass.getDeclaredField("mediaSources")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return (field.get(source) as Array<MediaSource>).toList()
  }

  @Test
  fun ilRamoHlsUsaLaPolicyDiErroreDellApp() {
    val factory = resolvePlaybackMediaSourceFactory(
      isHlsStream = true,
      httpDataSourceFactory = dataSourceFactory,
      progressiveMediaSourceFactory = StubMediaSourceFactory(),
      loadErrorHandlingPolicy = policy
    )

    assertTrue(
      "il ramo HLS deve essere avvolto in SubtitleMergingMediaSourceFactory",
      factory is SubtitleMergingMediaSourceFactory
    )
    val wrapper = factory as SubtitleMergingMediaSourceFactory
    assertSame("la policy dell'app guida anche le tracce sottotitolo (SingleSample)", policy, wrapperPolicyOf(wrapper))
    val videoFactory = innerVideoFactory(wrapper)
    assertTrue("la sorgente video del ramo HLS resta una HlsMediaSource.Factory", videoFactory is HlsMediaSource.Factory)
    assertSame(
      "la factory HLS deve portare la policy dell'app, non quella di default di Media3",
      policy,
      policyOfHlsFactory(videoFactory)
    )
  }

  @Test
  fun ilRamoProgressivoMantieneLaFactoryDelVideoENoRaddoppiaIlMerge() {
    val stub = StubMediaSourceFactory()

    val factory = resolvePlaybackMediaSourceFactory(
      isHlsStream = false,
      httpDataSourceFactory = dataSourceFactory,
      progressiveMediaSourceFactory = stub,
      loadErrorHandlingPolicy = policy
    )

    assertTrue(factory is SubtitleMergingMediaSourceFactory)
    assertSame(stub, innerVideoFactory(factory))
    assertSame(
      "la policy dell'app guida anche le tracce sottotitolo del ramo progressivo",
      policy,
      wrapperPolicyOf(factory)
    )
  }

  @Test
  fun conConfigurazioniSottotitoloIlVideoVieneRicostruitoSenzaEIlMergeAVmenga() {
    val stub = StubMediaSourceFactory()
    val factory = resolvePlaybackMediaSourceFactory(
      isHlsStream = false,
      httpDataSourceFactory = dataSourceFactory,
      progressiveMediaSourceFactory = stub,
      loadErrorHandlingPolicy = policy
    )

    val item = MediaItem.Builder()
      .setUri(android.net.Uri.parse("https://video.test/stream.m3u8"))
      .setSubtitleConfigurations(
        listOf(
          MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse("file:///cache/os_42.srt"))
            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage("it")
            .setId("os-42")
            .build()
        )
      )
      .build()

    val source = factory.createMediaSource(item)

    assertTrue("la factory è stata usata per il video", stub.lastWasCreated)
    // Il video delegato NON deve più portare le configurazioni: il merge è della wrapper.
    val videoOnly = requireNotNull(stub.lastMediaItem)
    assertTrue(
      "il MediaItem video delegato deve avere le configurazioni sottotitolo SVUOTATE",
      videoOnly.localConfiguration!!.subtitleConfigurations.isEmpty()
    )
    assertTrue(
      "con sottotitoli esterni attesa una MergingMediaSource (video + traccia SingleSample)",
      source is MergingMediaSource
    )
  }

  @Test
  fun senzaConfigurazioniSottotitoloDelegaDirettamenteIlVideo() {
    val stub = StubMediaSourceFactory()
    val factory = resolvePlaybackMediaSourceFactory(
      isHlsStream = false,
      httpDataSourceFactory = dataSourceFactory,
      progressiveMediaSourceFactory = stub,
      loadErrorHandlingPolicy = policy
    )

    val item = MediaItem.fromUri(android.net.Uri.parse("https://video.test/movie.mp4"))
    factory.createMediaSource(item)

    assertTrue(stub.lastWasCreated)
    assertSame("senza sottotitoli il MediaItem passa al video senza modifiche", item, stub.lastMediaItem)
  }

  @Test
  fun laFactorySchemaAwareApreISottotitoliLocaliFile() {
    val cacheDir = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir
    val localSubtitleFile = java.io.File(cacheDir, "os_test_fix.srt")
    val payload = "1\n00:00:01,000 --> 00:00:02,000\nCiao\n".toByteArray(Charsets.UTF_8)
    localSubtitleFile.writeBytes(payload)

    val schemaAware: DataSource.Factory = DefaultDataSource.Factory(
      ApplicationProvider.getApplicationContext(),
      dataSourceFactory
    )

    val dataSource = schemaAware.createDataSource()
    // La player riceve "file://" + absolutePath (stesso schema di StreamNovaViewModel):
    // `Uri.fromFile` produce la forma canonica equivalente (file:///...).
    val dataSpec = DataSpec(android.net.Uri.fromFile(localSubtitleFile))
    val read = dataSource.open(dataSpec)
    val bytes = ByteArray(read.toInt())
    var total = 0
    while (total < bytes.size) {
      val n = dataSource.read(bytes, total, bytes.size - total)
      if (n < 0) break
      total += n
    }
    dataSource.close()

    assertEquals(payload.size.toLong(), read)
    assertTrue("il file:// locale viene letto via FileDataSource, byte per byte", bytes.contentEquals(payload))
  }

  // ── Regressione: la traccia sottotitoli deve essere transcodificata in "Cues" ──
  //
  // Nella versione Media3 installata (AAR fork NuvioTV) la decodifica dei sottotitoli
  // "legacy" e' DISABILITATA: il renderer di testo accetta solo il formato Cues
  // (application/x-media3-cues). Allegare un file SRT con il suo MIME type grezzo faceva
  // fallire il player con "Legacy decoding is disabled, can't handle application/x-subrip
  // samples" e quindi con schermo nero ed errore di riproduzione.

  @Test
  fun laTracciaSottotitoliEsternaUsaLaSorgenteProgressivaTranscodificata() {
    val stub = StubMediaSourceFactory()
    val factory = resolvePlaybackMediaSourceFactory(
      isHlsStream = false,
      httpDataSourceFactory = dataSourceFactory,
      progressiveMediaSourceFactory = stub,
      loadErrorHandlingPolicy = policy
    )

    val item = MediaItem.Builder()
      .setUri(android.net.Uri.parse("https://video.test/movie.mp4"))
      .setSubtitleConfigurations(
        listOf(
          MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse("file:///cache/os_42.srt"))
            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage("it")
            .setId("os-42")
            .build()
        )
      )
      .build()

    val source = factory.createMediaSource(item)
    val children = mergedSources(source)

    assertEquals("video + una sorgente per la traccia sottotitoli", 2, children.size)
    assertTrue(
      "la traccia sottotitoli deve usare ProgressiveMediaSource (transcodifica in Cues), " +
        "non SingleSampleMediaSource (decodifica legacy, disabilitata)",
      children[1] is ProgressiveMediaSource
    )
  }

  @Test
  fun unMimeTypeSottotitoliNonSupportatoRestaUnaSidecarCheNonInterrompeIlVideo() {
    val stub = StubMediaSourceFactory()
    val factory = resolvePlaybackMediaSourceFactory(
      isHlsStream = false,
      httpDataSourceFactory = dataSourceFactory,
      progressiveMediaSourceFactory = stub,
      loadErrorHandlingPolicy = policy
    )

    val item = MediaItem.Builder()
      .setUri(android.net.Uri.parse("https://video.test/movie.mp4"))
      .setSubtitleConfigurations(
        listOf(
          MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse("file:///cache/os_42.cues"))
            .setMimeType("application/x-media3-cues")
            .setLanguage("it")
            .setId("os-42")
            .build()
        )
      )
      .build()

    val source = factory.createMediaSource(item)
    val children = mergedSources(source)

    assertEquals(2, children.size)
    assertTrue(
      "un formato gia' in Cues resta una sidecar che chiude la traccia come end-of-stream",
      children[1] is SingleSampleMediaSource
    )
  }
}
