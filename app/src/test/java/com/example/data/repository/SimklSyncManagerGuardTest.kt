package com.example.data.repository

import com.example.data.remote.simkl.SimklSyncStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guardie della sincronizzazione SIMKL: senza account collegato il gestore non deve
 * nemmeno provare a parlare con l'API (e non deve toccare nulla in locale).
 */
class SimklSyncManagerGuardTest {

  @Test
  fun senzaAccountCollegatoNonParteNessunaSincronizzazione() = runBlocking {
    val result = SimklSyncManager.sync(force = true)

    assertEquals(SimklSyncStatus.NOT_LOGGED_IN, result.status)
    assertEquals(0, result.adoptedTotal)
    assertEquals(0, result.unresolvedTitles.size)
  }
}
