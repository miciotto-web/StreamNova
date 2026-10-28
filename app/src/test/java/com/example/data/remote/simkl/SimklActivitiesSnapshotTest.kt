package com.example.data.remote.simkl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate di sincronizzazione su `GET /sync/activities`.
 *
 * La documentazione è categorica: si confronta prima lo snapshot dei timestamp e si
 * scaricano solo i bucket mossi — mai un pull completo a timer.
 */
class SimklActivitiesSnapshotTest {

  /** Risposta reale della documentazione (utente attivo). */
  private val activeUser = mapOf(
    "all" to "2026-05-14T07:12:20Z",
    "settings" to mapOf("all" to "2026-02-15T22:53:03Z"),
    "tv_shows" to mapOf(
      "all" to "2026-05-14T06:49:56Z",
      "rated_at" to "2026-04-10T05:34:55Z",
      "playback" to "2026-04-11T10:36:01Z",
      "plantowatch" to "2026-04-10T05:42:42Z",
      "watching" to "2026-05-04T19:40:58Z",
      "completed" to "2026-04-11T11:50:21Z",
      "hold" to "2026-05-14T06:49:56Z",
      "dropped" to "2026-05-14T06:49:56Z",
      "removed_from_list" to "2026-04-10T05:34:55Z"
    ),
    "anime" to mapOf("all" to "2026-05-14T06:50:38Z", "playback" to null),
    "movies" to mapOf("all" to "2026-05-14T06:50:38Z", "playback" to "2026-05-13T20:00:00Z"),
    "custom_lists" to mapOf("lists" to mapOf("all" to "2026-05-14T07:12:20Z"))
  )

  @Test
  fun appiattisceIDominiNeiBucketUsatiDaiGate() {
    val snapshot = SimklActivitiesSnapshot.fromResponse(activeUser)

    assertEquals("2026-04-11T10:36:01Z", snapshot.value("tv_shows.playback"))
    assertEquals("2026-05-13T20:00:00Z", snapshot.value("movies.playback"))
    // Bucket a null (nessuna attività) non entrano: restano "assenti", non stringa "null".
    assertNull(snapshot.value("anime.playback"))
    assertEquals("2026-05-14T07:12:20Z", snapshot.value("all"))
  }

  @Test
  fun senzaSnapshotPrecedenteTuttoVaScaricato() {
    val snapshot = SimklActivitiesSnapshot.fromResponse(activeUser)

    assertTrue(snapshot.movedSince(null, SimklActivitiesSnapshot.PLAYBACK_KEYS))
    assertTrue(snapshot.movedSince(null, SimklActivitiesSnapshot.WATCHLIST_KEYS))
  }

  @Test
  fun nessunaModificaNeiBucketDiPlaybackSaltaIlPull() {
    val previous = SimklActivitiesSnapshot.fromResponse(activeUser)
    // Cambia solo la watchlist: i playback restano identici.
    val next = SimklActivitiesSnapshot.fromResponse(
      activeUser + mapOf(
        "tv_shows" to (activeUser.getValue("tv_shows") as Map<String, Any>) +
          mapOf("watching" to "2026-05-15T08:00:00Z")
      )
    )

    assertFalse(next.movedSince(previous, SimklActivitiesSnapshot.PLAYBACK_KEYS))
    assertTrue(next.movedSince(previous, SimklActivitiesSnapshot.WATCHLIST_KEYS))
  }

  @Test
  fun unNuovoPuntoDiPausaFaScattareIlPullDeiPlayback() {
    val previous = SimklActivitiesSnapshot.fromResponse(activeUser)
    val movies = activeUser.getValue("movies") as Map<String, Any>
    // Cambia SOLO `movies.playback`: la libreria (movies.all e gli altri bucket) è ferma.
    val next = SimklActivitiesSnapshot.fromResponse(
      activeUser + mapOf("movies" to (movies + mapOf("playback" to "2026-05-16T09:00:00Z")))
    )

    assertTrue(next.movedSince(previous, SimklActivitiesSnapshot.PLAYBACK_KEYS))
    assertFalse(next.movedSince(previous, SimklActivitiesSnapshot.WATCHLIST_KEYS))
  }

  @Test
  fun leChiaviDeiGateCopronoTuttiIDominiDocumentati() {
    // movies non ha `watching`/`hold` (documentato): se comparissero sarebbe un errore.
    assertFalse(SimklActivitiesSnapshot.WATCHLIST_KEYS.any { it.startsWith("movies.watching") })
    assertTrue(SimklActivitiesSnapshot.PLAYBACK_KEYS.contains("anime.playback"))
    assertTrue(SimklActivitiesSnapshot.WATCHLIST_KEYS.contains("tv_shows.removed_from_list"))
  }

  @Test
  fun loSnapshotSopravviveAUnGiroDiSerializzazione() {
    val snapshot = SimklActivitiesSnapshot.fromResponse(activeUser)
    val restored = SimklActivitiesSnapshot.fromStorageString(snapshot.toStorageString())

    assertEquals(snapshot.timestamps, restored?.timestamps)
    assertFalse(
      SimklActivitiesSnapshot.fromResponse(activeUser)
        .movedSince(restored, SimklActivitiesSnapshot.PLAYBACK_KEYS)
    )
    assertNull(SimklActivitiesSnapshot.fromStorageString(""))
    assertNull(SimklActivitiesSnapshot.fromStorageString(null))
  }
}
