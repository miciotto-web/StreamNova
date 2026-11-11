package com.example.data.repository

import com.example.data.local.CachedMediaItemEntity
import com.example.data.local.MEDIA_SOURCE_SIMKL
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.VideoResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo stato dell'utente deve sopravvivere a OGNI ricostruzione della scheda dai dati TMDB.
 *
 * Il difetto reale verificato sul campo: al primo refresh del catalogo dopo una
 * sincronizzazione SIMKL, `progressSource`/`favoriteSource` (`32 → 24`, `171 → 144`
 * righe su un account reale) e — per i builder che non copiavano affatto lo stato —
 * stagione/episodio di ripresa e `completedAt` venivano azzerati. La regola è: i dati
 * TMDB aggiornano i METADATI, mai lo stato dell'utente né la sua provenienza.
 */
class MediaUserStatePreservationTest {

  private fun schedaConStato(
    id: String = "tmdb_tv_1418",
    progresso: Long = 6_000L,
    stagione: Int? = 10,
    episodio: Int? = 19,
    vistoIl: Long? = 1_700_000_000_000L,
    inCw: Boolean = true,
    completatoIl: Long? = null,
    preferito: Boolean = true,
    favoriteSource: String? = MEDIA_SOURCE_SIMKL,
    progressSource: String? = MEDIA_SOURCE_SIMKL,
    importata: Boolean = false
  ): MediaItem = MediaItem(
    id = id,
    tmdbId = 1418,
    title = "The Big Bang Theory",
    synopsis = "",
    videoUrl = "https://stream.example/v.m3u8",
    resolution = VideoResolution.UHD_4K,
    type = MediaType.SERIE_TV,
    year = 2007,
    currentProgressMs = progresso,
    totalDurationMs = 1_080_000L,
    lastWatchedSeason = stagione,
    lastWatchedEpisode = episodio,
    lastWatchedAt = vistoIl,
    inContinueWatching = inCw,
    completedAt = completatoIl,
    isFavorite = preferito,
    favoriteSource = favoriteSource,
    progressSource = progressSource,
    importedFromSimkl = importata
  )

  @Test
  fun unaSchedaRicostruitaDaTmdbConservaLoStatoUtenteELaProvenienza() {
    val fresca = schedaConStato(
      progresso = 0L,
      stagione = null,
      episodio = null,
      vistoIl = null,
      inCw = false,
      preferito = false,
      favoriteSource = null,
      progressSource = null
    )

    val unita = fresca.withUserStateFrom(schedaConStato())

    assertEquals("posizione reale conservata", 6_000L, unita.currentProgressMs)
    assertEquals("punto di ripresa conservato", 10, unita.lastWatchedSeason)
    assertEquals(19, unita.lastWatchedEpisode)
    assertEquals("data di visione conservata", 1_700_000_000_000L, unita.lastWatchedAt)
    assertTrue("appartenenza a «Continua a guardare» conservata", unita.inContinueWatching)
    assertTrue("preferito conservato", unita.isFavorite)
    assertEquals("provenienza preferito conservata", MEDIA_SOURCE_SIMKL, unita.favoriteSource)
    assertEquals("provenienza progresso conservata", MEDIA_SOURCE_SIMKL, unita.progressSource)
    assertEquals("titolo e metadati restano quelli TMDB", "The Big Bang Theory", unita.title)
  }

  @Test
  fun unaSchedaNuovaNonAcquisaStatoInesistente() {
    val fresca = schedaConStato(
      progresso = 0L, stagione = null, episodio = null, vistoIl = null,
      inCw = false, preferito = false, favoriteSource = null, progressSource = null
    )

    val unita = fresca.withUserStateFrom(null)

    assertEquals(0L, unita.currentProgressMs)
    assertFalse(unita.inContinueWatching)
    assertFalse(unita.isFavorite)
    assertNull(unita.favoriteSource)
    assertNull(unita.progressSource)
  }

  @Test
  fun loStatoLocaleNonVieneCancellatoDaUnRefreshSenzaStato() {
    val locale = schedaConStato(
      progresso = 300_000L,
      stagione = 1,
      episodio = 4,
      vistoIl = 1_710_000_000_000L,
      favoriteSource = null,
      progressSource = null
    )

    val unita = locale.withUserStateFrom(
      schedaConStato(progresso = 0L, stagione = null, episodio = null, vistoIl = null, inCw = false)
    )

    assertEquals("il progresso locale vince sul valore fresco (0)", 300_000L, unita.currentProgressMs)
    assertEquals(1, unita.lastWatchedSeason)
    assertEquals(4, unita.lastWatchedEpisode)
  }

  @Test
  fun laRigaRoomPreservaAncheLaProvenienza() {
    val entity = CachedMediaItemEntity(
      id = "tmdb_m_603",
      tmdbId = 603,
      title = "Matrix",
      originalTitle = "",
      synopsis = "",
      videoUrl = "https://stream.example/v.m3u8",
      resolution = "UHD_4K",
      qualityTags = "4K",
      backdropUrl = null,
      posterUrl = null,
      logoUrl = null,
      type = "FILM",
      year = 1999,
      durationMinutes = 136,
      seasonsCount = null,
      rating = 8.7f,
      genres = "",
      director = null,
      cast = "",
      provider = null,
      currentProgressMs = 400_000L,
      totalDurationMs = 8_160_000L,
      favoriteSource = MEDIA_SOURCE_SIMKL,
      progressSource = MEDIA_SOURCE_SIMKL,
      importedFromSimkl = true
    )

    val unita = entity.preserveUserStateInto(
      MediaItem(
        id = "tmdb_m_603",
        tmdbId = 603,
        title = "Matrix",
        synopsis = "",
        videoUrl = "https://stream.example/v.m3u8",
        resolution = VideoResolution.UHD_4K,
        type = MediaType.FILM,
        year = 1999
      )
    )

    assertEquals("provenienza preferito conservata", MEDIA_SOURCE_SIMKL, unita.favoriteSource)
    assertEquals("provenienza progresso conservata", MEDIA_SOURCE_SIMKL, unita.progressSource)
    assertTrue("flag import conservato", unita.importedFromSimkl)
    assertEquals("posizione conservata", 400_000L, unita.currentProgressMs)
  }
}
