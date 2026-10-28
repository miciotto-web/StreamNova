package com.example.ui.screens.player

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.chunk.MediaChunk
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Isteresi della selezione video: la soglia di AUMENTO qualità di `AdaptiveTrackSelection`.
 *
 * Causa dimostrata dell'oscillazione delle rendition (il "buffering continuo" con
 * riconfigurazione del decoder a ogni cambio di formato): con la soglia a 2.000 ms del port
 * di NuvioTV il selettore può risalire di rendition appena 2 s di buffer sono presenti,
 * quindi subito dopo lo svuotamento che aveva causato il downshift. Questi test
 * ricostruiscono la sequenza reale letta nei log del Fire TV (1080p → crollo di banda →
 * 480p → il buffer si ricompone → risalita immediata a 1080p) e dimostrano che il valore di
 * produzione (10.000 ms, stock Media3) spegne la risalita prematura **senza** impedire di
 * raggiungere la qualità massima quando il buffer è di nuovo sano.
 *
 * Il test pilota un vero `AdaptiveTrackSelection`, non una copia delle sue regole.
 *
 * Test JVM puro, senza Robolectric: l'orologio di Media3 serve solo a escludere rendition
 * messe in blacklist (nessuna nei pochi passi di questo test) quindi il valore di default
 * delle API Android nei test unitari (`isReturnDefaultValues = true`) non altera la sequenza.
 */
class PlayerTrackAdaptationHysteresisTest {

  private companion object {
    /**
     * Rendition alta: 1920x1080 @ 4,5 Mbps.
     *
     * L'ordine NON è quello delle varianti HLS (che nei log del player sono crescenti), ma
     * quello della definizione adattiva di Media3: `AdaptiveTrackSelection` sceglie la
     * PRIMA rendition che entra nella banda allocata, quindi la definizione va ordinata
     * dalla qualità più alta alla più bassa (è DefaultTrackSelector a costruirla così).
     */
    const val HIGH_INDEX = 0

    /** Rendition bassa: 854x480 @ 1,2 Mbps (la più bassa delle sorgenti vixsrc dei log). */
    const val LOW_INDEX = 1

    const val HIGH_QUALITY_BANDWIDTH_BPS = 10_000_000L
    const val LOW_QUALITY_BANDWIDTH_BPS = 2_000_000L
  }

  private val formats = arrayOf(
    Format.Builder()
      .setSampleMimeType(MimeTypes.VIDEO_H264)
      .setCodecs("avc1.640028")
      .setWidth(1920)
      .setHeight(1080)
      .setAverageBitrate(4_500_000)
      .build(),
    Format.Builder()
      .setSampleMimeType(MimeTypes.VIDEO_H264)
      .setCodecs("avc1.640028")
      .setWidth(854)
      .setHeight(480)
      .setAverageBitrate(1_200_000)
      .build()
  )

  private class FakeBandwidthMeter(var estimateBps: Long) : BandwidthMeter {
    override fun getBitrateEstimate(): Long = estimateBps
    override fun getTransferListener(): TransferListener? = null
    override fun addEventListener(handler: Handler, eventListener: BandwidthMeter.EventListener) = Unit
    override fun removeEventListener(eventListener: BandwidthMeter.EventListener) = Unit
  }

  /** Crea la selezione adattiva reale usando la factory di produzione (o una soglia esplicita). */
  private fun selection(minBufferForQualityIncreaseMs: Int, meter: FakeBandwidthMeter): ExoTrackSelection {
    val selections = NuvioTrackSelector
      .adaptiveTrackSelectionFactory(minBufferForQualityIncreaseMs)
      .createTrackSelections(
        // Le rendition adattive sono entrambe nella stessa TrackGroup: l'indice 0 è il
        // 480p, l'indice 1 il 1080p. La lunghezza dei track va dichiarata esplicitamente:
        // con `tracks` vuoto la factory di Media3 restituisce `null` per quel definition.
        arrayOf(ExoTrackSelection.Definition(TrackGroup(*formats), 0, 1)),
        meter,
        MediaSource.MediaPeriodId(Any()),
        Timeline.EMPTY
      )
    return requireNotNull(selections)[0]!!
  }

  /**
   * Un passo del selettore: `bufferedDurationUs` è l'unico ingresso che conta per l'isteresi,
   * `availableDurationUs` resta `C.TIME_UNSET` perché un VOD non ha live edge.
   */
  private fun step(selection: ExoTrackSelection, bufferedMs: Long): Int {
    selection.updateSelectedTrack(
      /* playbackPositionUs = */ 0L,
      /* bufferedDurationUs = */ bufferedMs * 1_000L,
      /* availableDurationUs = */ C.TIME_UNSET,
      /* queue = */ emptyList<MediaChunk>(),
      /* mediaChunkIterators = */ emptyArray<MediaChunkIterator>()
    )
    return selection.selectedIndex
  }

  @Test
  fun ilValoreDiProduzioneEQuelloStockDiMedia3() {
    assertEquals(10_000, NuvioTrackSelector.qualityIncreaseMinBufferMs)
  }

  @Test
  fun laBandaAltaSelezionaLaRendition1080p() {
    val selection = selection(NuvioTrackSelector.qualityIncreaseMinBufferMs, FakeBandwidthMeter(HIGH_QUALITY_BANDWIDTH_BPS))
    assertEquals(HIGH_INDEX, step(selection, 30_000L))
  }

  @Test
  fun ilCrolloDiBandaFaScendereSubitoAl480p() {
    val meter = FakeBandwidthMeter(HIGH_QUALITY_BANDWIDTH_BPS)
    val selection = selection(NuvioTrackSelector.qualityIncreaseMinBufferMs, meter)

    assertEquals(HIGH_INDEX, step(selection, 30_000L))

    // Banda insufficiente per i 4,5 Mbps e buffer quasi vuoto: il selettore scende SUBITO
    // (non trattiene il 1080p come farebbe con un buffer lungo).
    meter.estimateBps = LOW_QUALITY_BANDWIDTH_BPS
    assertEquals(LOW_INDEX, step(selection, 1_000L))
  }

  @Test
  fun conLaSogliaDiProduzioneIlBufferCortoNonAutorizzaLaRisalita() {
    val meter = FakeBandwidthMeter(HIGH_QUALITY_BANDWIDTH_BPS)
    val selection = selection(NuvioTrackSelector.qualityIncreaseMinBufferMs, meter)

    assertEquals(HIGH_INDEX, step(selection, 30_000L))
    meter.estimateBps = LOW_QUALITY_BANDWIDTH_BPS
    assertEquals(LOW_INDEX, step(selection, 1_000L))

    // La banda è tornata ma il buffer è di soli 3 s: con la soglia di produzione (10 s)
    // la rendition resta quella bassa, quindi non c'è il ping-pong che riconfigura il decoder.
    meter.estimateBps = HIGH_QUALITY_BANDWIDTH_BPS
    assertEquals(LOW_INDEX, step(selection, 3_000L))
  }

  @Test
  fun conLaSogliaVecchiaDiNuvioIlBufferCortoFacevaRisalireSubito() {
    val meter = FakeBandwidthMeter(HIGH_QUALITY_BANDWIDTH_BPS)
    // 2.000 ms = valore del port di NuvioTV, sostituito in Fase 2.
    val selection = selection(2_000, meter)

    assertEquals(HIGH_INDEX, step(selection, 30_000L))
    meter.estimateBps = LOW_QUALITY_BANDWIDTH_BPS
    assertEquals(LOW_INDEX, step(selection, 1_000L))

    // Stessa identica sequenza, ma con la soglia vecchia: 3 s di buffer bastano per
    // risalire a 1080p → è questa la risalita prematura che nei log produceva 7-8
    // riconfigurazioni di decoder al minuto.
    meter.estimateBps = HIGH_QUALITY_BANDWIDTH_BPS
    assertEquals(HIGH_INDEX, step(selection, 3_000L))
  }

  @Test
  fun ilBufferSanoNonImpedisceIlRitornoAllAltaQualita() {
    val meter = FakeBandwidthMeter(HIGH_QUALITY_BANDWIDTH_BPS)
    val selection = selection(NuvioTrackSelector.qualityIncreaseMinBufferMs, meter)

    assertEquals(HIGH_INDEX, step(selection, 30_000L))
    meter.estimateBps = LOW_QUALITY_BANDWIDTH_BPS
    assertEquals(LOW_INDEX, step(selection, 1_000L))

    // Banda stabile e buffer ricostruito (12 s > 10 s): il 1080p torna selezionabile.
    meter.estimateBps = HIGH_QUALITY_BANDWIDTH_BPS
    assertEquals(LOW_INDEX, step(selection, 3_000L))
    assertEquals(HIGH_INDEX, step(selection, 12_000L))
  }

  @Test
  fun laSogliaDiProduzioneENonNegativaEMaggioreDiQuellaVecchia() {
    assertTrue(NuvioTrackSelector.qualityIncreaseMinBufferMs >= 10_000)
  }
}
