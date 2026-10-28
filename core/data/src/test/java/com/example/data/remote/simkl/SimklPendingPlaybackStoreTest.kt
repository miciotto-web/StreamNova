package com.example.data.remote.simkl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistenza delle voci di playback rimandate.
 *
 * Il formato è di servizio (DataStore, nessuna libreria JSON), quindi va verificato
 * esplicitamente: una decodifica che perde un campo o che scarta una riga renderebbe
 * irrecuperabile proprio il punto di ripresa che questa memoria serve a non perdere.
 *
 * La **chiave** deve sopravvivere al salvataggio: è ciò con cui la voce rimandata viene
 * riconosciuta nella risposta fresca di SIMKL (niente doppioni) e ritrovata nel giro
 * successivo (conteggio dei tentativi).
 */
class SimklPendingPlaybackStoreTest {

  private fun episodio(playbackId: Long? = 31, season: Int = 2, episode: Int = 4) = SimklPlaybackEntry(
    playbackId = playbackId,
    kind = SimklPlaybackKind.EPISODE,
    progressPercent = 47.5,
    pausedAtEpochMs = SimklPlaybackParser.parseIsoUtcToEpochMs("2026-05-20T10:30:00Z"),
    title = "La Casa Senza Fine",
    year = 2020,
    tmdbId = 5502,
    imdbId = "tt5502000",
    simklId = 990011,
    season = season,
    episode = episode,
    episodeTitle = "Capitolo Quattro",
    isAnime = false
  )

  private fun film(playbackId: Long? = 12) = SimklPlaybackEntry(
    playbackId = playbackId,
    kind = SimklPlaybackKind.MOVIE,
    progressPercent = 12.25,
    pausedAtEpochMs = null,
    title = "Il Ladro di Sogni",
    year = null,
    tmdbId = null,
    imdbId = null,
    simklId = 5501
  )

  @Test
  fun unEpisodioRimandatoTornaIdentico() {
    val voci = listOf(SimklPendingPlayback(episodio(), attempts = 3))

    val decodificate = SimklPendingPlaybackStore.decode(SimklPendingPlaybackStore.encode(voci))

    assertEquals(1, decodificate.size)
    assertEquals(voci[0].attempts, decodificate[0].attempts)
    assertEquals(voci[0].entry, decodificate[0].entry)
  }

  @Test
  fun unFilmRimandatoTornaIdenticoAncheSenzaCampiEpisodio() {
    val voci = listOf(SimklPendingPlayback(film(), attempts = 1))

    val decodificate = SimklPendingPlaybackStore.decode(SimklPendingPlaybackStore.encode(voci))

    assertEquals(voci[0].entry, decodificate[0].entry)
    assertEquals(1, decodificate[0].attempts)
  }

  @Test
  fun piuVociRestanoSeparateEInOrdine() {
    val voci = listOf(
      SimklPendingPlayback(episodio(playbackId = 31), attempts = 1),
      SimklPendingPlayback(film(playbackId = 32), attempts = 2)
    )

    val decodificate = SimklPendingPlaybackStore.decode(SimklPendingPlaybackStore.encode(voci))

    assertEquals(listOf(1, 2), decodificate.map { it.attempts })
    assertEquals(listOf(episodio(playbackId = 31), film(playbackId = 32)), decodificate.map { it.entry })
  }

  @Test
  fun unTitoloConCaratteriSpecialiNonRompeIlFormato() {
    val strano = film().copy(title = "L'adattamento 100% \"difficile\" — Atto 1°")
    val voci = listOf(SimklPendingPlayback(strano, attempts = 1))

    val decodificate = SimklPendingPlaybackStore.decode(SimklPendingPlaybackStore.encode(voci))

    assertEquals(strano.title, decodificate[0].entry.title)
  }

  @Test
  fun nessunaVoceENessunDatoNonSonoUnErrore() {
    assertEquals(emptyList<SimklPendingPlayback>(), SimklPendingPlaybackStore.decode(null))
    assertEquals(emptyList<SimklPendingPlayback>(), SimklPendingPlaybackStore.decode(""))
    assertEquals("", SimklPendingPlaybackStore.encode(emptyList()))
  }

  @Test
  fun unaRigaRovinataVieneScartataSenzaPerdereLeAltre() {
    val valida = SimklPendingPlaybackStore.encode(listOf(SimklPendingPlayback(episodio(), attempts = 2)))
    val corrotta = "2\u001F31\u001FMAI_UN_TIPO\u001Fx\u001Fy\u001Fz"

    val decodificate = SimklPendingPlaybackStore.decode("$corrotta\n$valida\n2\u001F33\u001FEPISODE")

    assertEquals("solo la voce leggibile", 1, decodificate.size)
    assertEquals(2, decodificate[0].attempts)
  }

  @Test
  fun laChiaveSopravviveAlSalvataggio() {
    // È l'invariante su cui si regge il recupero: la voce rimandata deve essere
    // riconoscibile — quindi non duplicata — quando SIMKL la restituisce di nuovo.
    val originale = episodio()
    val riletta = SimklPendingPlaybackStore.decode(
      SimklPendingPlaybackStore.encode(listOf(SimklPendingPlayback(originale, attempts = 1)))
    )[0].entry

    assertEquals(originale.resumeKey, riletta.resumeKey)
  }

  @Test
  fun laChiaveUsaIlPlaybackIdQuandoCEeGliIdentificativiAltrimenti() {
    // Con `playbackId` la chiave è l'id della sessione: la stessa sessione riletta (anche
    // con dati aggiornati) resta una voce sola.
    assertEquals("id:31", episodio(playbackId = 31).resumeKey)
    assertEquals(episodio(playbackId = 31).resumeKey, episodio(playbackId = 31, season = 3).resumeKey)

    // Senza id la chiave si costruisce da titolo + identificativi + episodio: episodi
    // diversi non si confondono, la stessa sessione riletta resta la stessa voce.
    val senzaId = episodio(playbackId = null)
    assertTrue(senzaId.resumeKey.startsWith("k:"))
    assertEquals(senzaId.resumeKey, senzaId.copy().resumeKey)
    assertNotEquals(senzaId.resumeKey, episodio(playbackId = null, season = 2, episode = 5).resumeKey)
    assertNotEquals(senzaId.resumeKey, film(playbackId = null).resumeKey)
  }
}
