package com.example.data.repository

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Identità dei titoli: la regola con cui si riconosce che due schede del catalogo sono lo
 * stesso contenuto. È il presupposto della riga «Continua a guardare» a card singola: se
 * questa deduplicazione sbaglia, l'utente vede due volte lo stesso film (caso reale: una
 * scheda TMDB `tmdb_m_1288069` e una scheda Cinemeta `tt6262770` dello stesso titolo).
 *
 * Le chiavi forti (`tmdb`/`imdb`) servono allo STATO UTENTE e non devono mai unire per
 * titolo; le chiavi a schermo aggiungono titolo + anno e servono alla deduplicazione visiva.
 */
class MediaIdentityTest {

  private fun film(
    id: String,
    tmdbId: Int? = null,
    imdbId: String? = null,
    titolo: String = "Tenzing",
    titoloOriginale: String = "",
    anno: Int = 2026,
    progressoMs: Long = 0L,
    vistoIl: Long? = null,
    preferito: Boolean = false,
    tipo: MediaType = MediaType.FILM
  ) = MediaItem(
    id = id,
    title = titolo,
    originalTitle = titoloOriginale,
    synopsis = "",
    videoUrl = "",
    tmdbId = tmdbId,
    imdbId = imdbId,
    type = tipo,
    year = anno,
    currentProgressMs = progressoMs,
    lastWatchedAt = vistoIl,
    isFavorite = preferito
  )

  /** Le due schede reali del caso Tenzing: stessa pellicola, id di cataloghi diversi. */
  private val schedaTmdb = film("tmdb_m_1288069", tmdbId = 1288069, progressoMs = 1_112_842, vistoIl = 1_000L)
  private val schedaCinemeta = film("tt6262770", tmdbId = 1288069, progressoMs = 1_176_348, vistoIl = 2_000L)

  @Test
  fun dueSchedeConLoStessoTmdbSonoUnSoloTitolo() {
    assertTrue(MediaIdentity.isSameTitle(schedaTmdb, schedaCinemeta))
    assertTrue(chiaviFortiInComune(schedaTmdb, schedaCinemeta))
  }

  private fun chiaviFortiInComune(a: MediaItem, b: MediaItem): Boolean =
    MediaIdentity.strongKeys(a).any { it in MediaIdentity.strongKeys(b) }

  @Test
  fun lImdbSiRicavaDallIdQuandoIlCampoNonEPersistito() {
    // `CachedMediaItemEntity` non ha una colonna imdbId: al riavvio il campo è vuoto e
    // l'unica traccia è l'id (`tt…`), che è come il catalogo Stremio identifica i titoli.
    val soloId = film("tt6262770")
    assertEquals("tt6262770", MediaIdentity.imdbIdOf(soloId))
    assertTrue(MediaIdentity.strongKeys(soloId).contains("film|imdb:tt6262770"))
    assertNull(MediaIdentity.imdbIdOf(film("tmdb_m_1", tmdbId = 1)))
  }

  @Test
  fun laDeduplicazioneTieneLaSchedaConIlProgressoPiuRecente() {
    val altro = film("tmdb_m_99", tmdbId = 99, titolo = "Altro Film", progressoMs = 5_000, vistoIl = 500L)

    val risultato = MediaIdentity.dedupeByProgress(listOf(schedaTmdb, altro, schedaCinemeta))

    assertEquals("una card per titolo, nell'ordine d'ingresso", listOf("tmdb_m_99", "tt6262770"), risultato.map { it.id })
    assertEquals("vince il progresso più recente", 1_176_348L, risultato.first { it.id == "tt6262770" }.currentProgressMs)
  }

  @Test
  fun unaSchedaCoinvoltaNonNascondeIlProgressoDiQuellaPiuRecente() {
    // La scheda TMDB è la prima in lista: se la deduplicazione tenesse "la prima vista",
    // il progresso mostrato sarebbe quello vecchio.
    val risultato = MediaIdentity.dedupeByProgress(listOf(schedaTmdb, schedaCinemeta))
    assertEquals(1, risultato.size)
    assertEquals(1_176_348L, risultato.single().currentProgressMs)
  }

  @Test
  fun senzaProgressoNonCEUnaCardInContinuaAGuardare() {
    val maiAvviato = film("tmdb_m_7", tmdbId = 7, titolo = "Mai Avviato")
    assertTrue(MediaIdentity.dedupeByProgress(listOf(maiAvviato)).isEmpty())
  }

  @Test
  fun ilTitoloNonUnisceLoStatoUtenteMaUnisceLaCard() {
    val senzaId = film("schede_a", titolo = "Film Omonimo", anno = 2020)
    val senzaId2 = film("scheda_b", titolo = "Film OMONIMO!", anno = 2020)

    assertTrue("a schermo è lo stesso titolo", MediaIdentity.isSameTitle(senzaId, senzaId2))
    assertTrue("ma senza identificativi forti non si unisce lo stato", MediaIdentity.strongKeys(senzaId).isEmpty())
    assertEquals("titolo+anno+punteggiatura ignorata", 1, MediaIdentity.dedupeByProgress(
      listOf(senzaId.copy(currentProgressMs = 10, lastWatchedAt = 1L), senzaId2.copy(currentProgressMs = 20, lastWatchedAt = 2L))
    ).size)
  }

  @Test
  fun unRemakeNonSiConfondeConLOriginale() {
    val originale = film("tmdb_m_10", tmdbId = 10, titolo = "Il Corvo", anno = 1994)
    val remake = film("tmdb_m_11", tmdbId = 11, titolo = "Il Corvo", anno = 2024)

    assertFalse(MediaIdentity.isSameTitle(originale, remake))
    assertEquals(2, MediaIdentity.dedupeByProgress(
      listOf(originale.copy(currentProgressMs = 1, lastWatchedAt = 1L), remake.copy(currentProgressMs = 1, lastWatchedAt = 2L))
    ).size)
  }

  @Test
  fun loStessoIdTmdbDiUnFilmEUnaSerieNonSiUniscono() {
    val filmItem = film("tmdb_m_500", tmdbId = 500, progressoMs = 10, vistoIl = 1L)
    val serieItem = film("tmdb_tv_500", tmdbId = 500, progressoMs = 20, vistoIl = 2L, tipo = MediaType.SERIE_TV)

    assertFalse(MediaIdentity.isSameTitle(filmItem, serieItem))
    assertFalse(chiaviFortiInComune(filmItem, serieItem))
    assertEquals(2, MediaIdentity.dedupeByProgress(listOf(filmItem, serieItem)).size)
  }

  @Test
  fun ilTitoloOriginaleUnisceLaCardLocalizzata() {
    // TMDB in italiano e la sorgente Stremio in inglese: stesso contenuto, testi diversi.
    val italiano = film("tmdb_m_680", tmdbId = 680, titolo = "Pulp Fiction", titoloOriginale = "Pulp Fiction")
    val inglese = film("scheda_stremio", titolo = "Pulp Fiction", titoloOriginale = "Pulp Fiction")
    assertTrue(MediaIdentity.isSameTitle(italiano, inglese))

    val italiano2 = film("tmdb_m_681", tmdbId = 681, titolo = "Il Gladiatore", titoloOriginale = "Gladiator")
    val inglese2 = film("scheda_stremio_2", titolo = "Gladiator", titoloOriginale = "Gladiator")
    assertTrue(MediaIdentity.isSameTitle(italiano2, inglese2))
  }

  @Test
  fun iPreferitiSonoUnaCardPerTitolo() {
    val preferitoVecchio = schedaTmdb.copy(isFavorite = true, currentProgressMs = 0L, lastWatchedAt = null)
    val preferitoRecente = schedaCinemeta.copy(isFavorite = true)
    val nonPreferito = film("tmdb_m_42", tmdbId = 42, titolo = "Non Preferito", progressoMs = 100, vistoIl = 999L)

    val risultato = MediaIdentity.dedupeFavorites(listOf(preferitoVecchio, nonPreferito, preferitoRecente))

    assertEquals(listOf("tt6262770"), risultato.map { it.id })
  }

  @Test
  fun newestStateByIdTrovaLaRigaDaAggiornare() {
    val catalogo = listOf(schedaTmdb, schedaCinemeta)
    assertEquals("tt6262770", MediaIdentity.newestStateById(catalogo, "tmdb_m_1288069")?.id)
    assertEquals("tt6262770", MediaIdentity.newestStateById(catalogo, "tt6262770")?.id)
    assertNull(MediaIdentity.newestStateById(catalogo, "id-che-non-esiste"))
  }

  @Test
  fun senzaProgressoSiConservaLaSchedaIndicata() {
    // Nessun progresso da confrontare: si scrive sulla scheda che il chiamante ha matchato,
    // senza spostare lo stato a vuoto.
    val a = schedaTmdb.copy(currentProgressMs = 0L, lastWatchedAt = null)
    val b = schedaCinemeta.copy(currentProgressMs = 0L, lastWatchedAt = null)
    assertEquals("tmdb_m_1288069", MediaIdentity.newestStateById(listOf(a, b), "tmdb_m_1288069")?.id)
    assertEquals("tt6262770", MediaIdentity.newestStateById(listOf(a, b), "tt6262770")?.id)
  }

  @Test
  fun ilGruppoNonSiAllargaAOltreIlTitolo() {
    val catalogo = listOf(schedaTmdb, schedaCinemeta, film("tmdb_m_1", tmdbId = 1, titolo = "Un Altro"))
    val gruppo = MediaIdentity.groupById(catalogo, "tmdb_m_1288069")
    assertEquals(setOf("tmdb_m_1288069", "tt6262770"), gruppo.map { it.id }.toSet())
    assertTrue(MediaIdentity.groupById(catalogo, "sconosciuto").isEmpty())
  }
}
