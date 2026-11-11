package com.example.data.repository

import com.example.data.repository.SimklRepository.SimklScrobbleEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L'outbox degli scrobble deve sopravvivere al riavvio del processo e restare legato
 * all'account e alla sessione che lo hanno prodotto.
 */
class SimklScrobbleOutboxStoreTest {

  private fun entry(
    id: String = "e1",
    accountId: Long? = 42L,
    epoch: Int = 3,
    event: SimklScrobbleEvent = SimklScrobbleEvent.STOP
  ) = SimklOutboxEntry(
    id = id,
    accountId = accountId,
    sessionEpoch = epoch,
    event = event,
    media = SimklMediaPayload(
      title = "La Casa Senza Fine",
      year = 2020,
      tmdbId = 5502,
      imdbId = "tt5502000",
      episode = SimklEpisodePayload(2, 4),
      mediaId = "tmdb_tv_5502"
    ),
    progressPercent = 64.5,
    createdAt = 1_700_000_000_000L,
    attempts = 1,
    lockRetries = 2,
    nextAttemptAt = 1_700_000_020_000L
  )

  @Test
  fun roundTripPreservaAccountSessioneEventoEProgresso() {
    val original = entry()
    val decoded = SimklScrobbleOutboxStore.decode(
      SimklScrobbleOutboxStore.encode(listOf(original))
    ).single()

    assertEquals(original.id, decoded.id)
    assertEquals(42L, decoded.accountId)
    assertEquals(3, decoded.sessionEpoch)
    assertEquals(SimklScrobbleEvent.STOP, decoded.event)
    assertEquals("La Casa Senza Fine", decoded.media.title)
    assertEquals(5502, decoded.media.tmdbId)
    assertEquals("tt5502000", decoded.media.imdbId)
    assertEquals(2, decoded.media.episode?.season)
    assertEquals(4, decoded.media.episode?.number)
    assertEquals("tmdb_tv_5502", decoded.media.mediaId)
    assertEquals(64.5, decoded.progressPercent, 0.0)
    assertEquals(1_700_000_000_000L, decoded.createdAt)
    assertEquals(1, decoded.attempts)
    assertEquals(2, decoded.lockRetries)
    assertEquals(1_700_000_020_000L, decoded.nextAttemptAt)
  }

  @Test
  fun piuEventiRestanoInOrdineEUnTitoloNonRompeliRecord() {
    val first = entry(id = "start", event = SimklScrobbleEvent.START)
    val second = entry(id = "stop", event = SimklScrobbleEvent.STOP).copy(
      media = entry().media.copy(title = "Titolo\ncon separatore\u001Finterno")
    )

    val decoded = SimklScrobbleOutboxStore.decode(
      SimklScrobbleOutboxStore.encode(listOf(first, second))
    )

    assertEquals(listOf("start", "stop"), decoded.map { it.id })
    assertTrue(
      "newline e separatore non devono spezzare il record",
      decoded[1].media.title.contains("Titolo") &&
        !decoded[1].media.title.contains('\n')
    )
  }

  @Test
  fun unRecordCorrottoNonImpedisceDiLeggereGliAltri() {
    val raw = SimklScrobbleOutboxStore.encode(listOf(entry())) + "\nrecord-non-valido"
    assertEquals(1, SimklScrobbleOutboxStore.decode(raw).size)
  }

  @Test
  fun outboxVuotaONonLeggibileNonInventaEventi() {
    assertTrue(SimklScrobbleOutboxStore.decode(null).isEmpty())
    assertTrue(SimklScrobbleOutboxStore.decode("").isEmpty())
    assertTrue(SimklScrobbleOutboxStore.decode("   ").isEmpty())
  }
}
