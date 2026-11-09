package com.example.data.remote.simkl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Politica di merge tra il punto di pausa SIMKL e il progresso locale.
 *
 * Invarianti verificati qui: vince il più recente, mai una regressione a 0, mai una
 * conversione senza durata reale, nessuna scrittura quando è già tutto allineato.
 */
class SimklProgressMergePolicyTest {

  private fun movieEntry(
    percent: Double,
    pausedAt: Long? = 1_700_000_000_000L
  ) = SimklPlaybackEntry(
    playbackId = 1L,
    kind = SimklPlaybackKind.MOVIE,
    progressPercent = percent,
    pausedAtEpochMs = pausedAt,
    title = "Inception",
    year = 2010,
    tmdbId = 27205,
    imdbId = "tt1375666",
    simklId = 67890L
  )

  private fun episodeEntry(
    percent: Double,
    pausedAt: Long? = 1_700_000_000_000L,
    season: Int? = 1,
    episode: Int? = 5
  ) = SimklPlaybackEntry(
    playbackId = 2L,
    kind = SimklPlaybackKind.EPISODE,
    progressPercent = percent,
    pausedAtEpochMs = pausedAt,
    title = "Breaking Bad",
    year = 2008,
    tmdbId = 1396,
    imdbId = "tt0903747",
    simklId = 12345L,
    season = season,
    episode = episode
  )

  @Test
  fun adottaLaPosizioneSimklPiuRecenteConvertendoLaPercentuale() {
    val local = SimklLocalProgress(
      mediaId = "tmdb_m_27205",
      totalDurationMs = 60 * 60_000L, // 60 minuti
      progressMs = 120_000L, // 2 minuti visti in locale, più vecchi
      lastWatchedAtMs = 1_699_000_000_000L
    )

    val decision = SimklProgressMergePolicy.decide(movieEntry(45.5), local)

    assertTrue(decision.adopt)
    // 45.5% di 60 minuti
    assertEquals(1_638_000L, decision.progressMs)
    assertNull(decision.reason)
  }

  @Test
  fun nonRegredisceUnProgressoLocalePiuRecente() {
    val local = SimklLocalProgress(
      mediaId = "tmdb_m_27205",
      totalDurationMs = 60 * 60_000L,
      progressMs = 30 * 60_000L,
      lastWatchedAtMs = 1_700_500_000_000L // più recente della pausa SIMKL
    )

    val decision = SimklProgressMergePolicy.decide(movieEntry(45.5), local)

    assertFalse(decision.adopt)
    assertEquals(SimklDecisionReason.LOCAL_NEWER, decision.reason)
  }

  @Test
  fun senzaDurataRealeNonConverteLaPercentuale() {
    val local = SimklLocalProgress(
      mediaId = "tmdb_m_27205",
      totalDurationMs = 0L,
      progressMs = 0L,
      lastWatchedAtMs = null
    )

    val decision = SimklProgressMergePolicy.decide(movieEntry(45.5), local)

    assertFalse(decision.adopt)
    assertEquals(SimklDecisionReason.UNKNOWN_DURATION, decision.reason)
  }

  @Test
  fun nonScriveQuandoIlValoreEGiaAllineato() {
    val duration = 60 * 60_000L
    val target = (duration * 0.455).toLong()
    val local = SimklLocalProgress(
      mediaId = "tmdb_m_27205",
      totalDurationMs = duration,
      progressMs = target,
      lastWatchedAtMs = 1_699_000_000_000L
    )

    val decision = SimklProgressMergePolicy.decide(movieEntry(45.5), local)

    assertFalse("idempotenza: nessuna scrittura se è già allineato", decision.adopt)
    assertEquals(SimklDecisionReason.ALREADY_IN_SYNC, decision.reason)
  }

  @Test
  fun senzaDataDiPausaConservaIlProgressoLocaleSeEsiste() {
    val local = SimklLocalProgress(
      mediaId = "tmdb_m_27205",
      totalDurationMs = 60 * 60_000L,
      progressMs = 5 * 60_000L,
      lastWatchedAtMs = null
    )

    val conProgresso = SimklProgressMergePolicy.decide(movieEntry(45.5, pausedAt = null), local)
    assertFalse(conProgresso.adopt)
    assertEquals(SimklDecisionReason.UNKNOWN_TIMESTAMP, conProgresso.reason)

    // Nessun progresso locale: adottare è l'unica informazione disponibile.
    val senzaProgresso = SimklProgressMergePolicy.decide(
      movieEntry(45.5, pausedAt = null),
      local.copy(progressMs = 0L)
    )
    assertTrue(senzaProgresso.adopt)
  }

  @Test
  fun unaSessioneIncompletaNonToccaNulla() {
    val percentualeNulla = SimklProgressMergePolicy.decide(
      movieEntry(0.0),
      SimklLocalProgress("id", 60_000L, 0L, null)
    )
    assertFalse(percentualeNulla.adopt)
    assertEquals(SimklDecisionReason.INVALID_PROGRESS, percentualeNulla.reason)

    val episodioSenzaNumero = SimklProgressMergePolicy.decide(
      episodeEntry(50.0, season = null, episode = null),
      SimklLocalProgress("id", 45 * 60_000L, 0L, null)
    )
    assertFalse(episodioSenzaNumero.adopt)
    assertEquals(SimklDecisionReason.INCOMPLETE_SESSION, episodioSenzaNumero.reason)
  }

  @Test
  fun unTitoloNonRiconosciutoNonVieneInventato() {
    val decision = SimklProgressMergePolicy.decide(movieEntry(45.5), local = null)

    assertFalse(decision.adopt)
    assertEquals(SimklDecisionReason.UNRESOLVED_TITLE, decision.reason)
  }

  @Test
  fun unaPercentualeFuoriScalaVieneRifiutata() {
    val local = SimklLocalProgress("id", 60_000L, 0L, null)

    assertEquals(
      SimklDecisionReason.INVALID_PROGRESS,
      SimklProgressMergePolicy.decide(movieEntry(140.0), local).reason
    )
    assertEquals(
      SimklDecisionReason.INVALID_PROGRESS,
      SimklProgressMergePolicy.decide(movieEntry(-3.0), local).reason
    )
  }

  @Test
  fun unaRiVisioneInCorsoVieneAdottataAnchePerUnTitoloGiaVisto() {
    // La doc è esplicita: un item completato può riapparire come playback se la pausa è
    // successiva alla marcatura "visto". La posizione va mostrata lo stesso.
    val local = SimklLocalProgress(
      mediaId = "tmdb_m_27205",
      totalDurationMs = 60 * 60_000L,
      progressMs = 60 * 60_000L, // già finito
      lastWatchedAtMs = 1_699_000_000_000L
    )

    val decision = SimklProgressMergePolicy.decide(movieEntry(20.0), local)

    assertTrue(decision.adopt)
    assertEquals(12 * 60_000L, decision.progressMs)
  }

  @Test
  fun unNextToWatchAvviaLEpisodioSuccessivoSenzaInventareUnaPercentuale() {
    val entry = episodeEntry(0.0, season = 1, episode = 8).copy(
      isNextToWatch = true,
      origin = SimklResumeOrigin.LIBRARY
    )
    val local = SimklLocalProgress(
      mediaId = "tmdb_tv_1396",
      totalDurationMs = 0L,
      progressMs = 0L,
      lastWatchedAtMs = null
    )

    val decision = SimklProgressMergePolicy.decide(entry, local)

    assertTrue("il prossimo episodio è applicabile a 0%", decision.adopt)
    assertEquals(0L, decision.progressMs)
  }

  @Test
  fun unNextToWatchGiaApplicatoEIdempotente() {
    val entry = episodeEntry(0.0, season = 1, episode = 8).copy(
      isNextToWatch = true,
      origin = SimklResumeOrigin.LIBRARY
    )
    val local = SimklLocalProgress(
      mediaId = "tmdb_tv_1396",
      totalDurationMs = 0L,
      progressMs = 0L,
      lastWatchedAtMs = entry.pausedAtEpochMs
    )

    val decision = SimklProgressMergePolicy.decide(entry, local)

    assertFalse(decision.adopt)
    assertEquals(SimklDecisionReason.ALREADY_IN_SYNC, decision.reason)
  }

  @Test
  fun unaSessioneObsoletaNonRiportaIndietroLaSerie() {
    // In locale la serie è a S1E5, vista il 1 giugno; SIMKL ha una pausa vecchia su S1E2
    // del 14 maggio. La card non deve tornare a S1E2.
    val entry = episodeEntry(45.0, pausedAt = 1_716_000_000_000L, season = 1, episode = 2)
    val local = SimklLocalProgress(
      mediaId = "tmdb_tv_1396",
      totalDurationMs = 45 * 60_000L,
      progressMs = 0L,
      lastWatchedAtMs = null
    )
    val series = SimklSeriesState(
      season = 1,
      episode = 5,
      lastWatchedAtMs = 1_717_000_000_000L
    )

    val decision = SimklProgressMergePolicy.decide(entry, local, series)

    assertFalse(decision.adopt)
    assertEquals(SimklDecisionReason.LOCAL_NEWER, decision.reason)
  }

  @Test
  fun unaRiVisionePiuRecenteDiUnEpisodioPrecedenteVieneAdottata() {
    // La pausa su S1E2 è del 1 giugno, più recente del locale su S1E5 (14 maggio):
    // è l'ultima visione effettiva e deve vincere.
    val entry = episodeEntry(45.0, pausedAt = 1_717_000_000_000L, season = 1, episode = 2)
    val local = SimklLocalProgress(
      mediaId = "tmdb_tv_1396",
      totalDurationMs = 45 * 60_000L,
      progressMs = 0L,
      lastWatchedAtMs = null
    )
    val series = SimklSeriesState(
      season = 1,
      episode = 5,
      lastWatchedAtMs = 1_716_000_000_000L
    )

    val decision = SimklProgressMergePolicy.decide(entry, local, series)

    assertTrue(decision.adopt)
    assertEquals((45 * 60_000L * 0.45).toLong(), decision.progressMs)
  }

  @Test
  fun unaPosizioneMoltoVicinaAllInizioRestaAlmenoUnMillisecondo() {
    val local = SimklLocalProgress("id", 60 * 60_000L, 0L, null)

    val decision = SimklProgressMergePolicy.decide(movieEntry(0.0001), local)

    assertTrue(decision.adopt)
    assertTrue(decision.progressMs >= 1L)
  }
}
