package com.example.ui.screens.player

import com.example.data.model.Episode
import com.example.ui.viewmodel.NextEpisodeAutoplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test della logica **pura** dell'overlay "Prossimo Episodio":
 * finestra degli ultimi 35 secondi, STATE_ENDED, chiusura esplicita e guardie su serie TV,
 * episodio successivo e sessione fallita.
 */
class NextEpisodePromptUtilsTest {

  private val hourDurationMs = 1_400_000L

  private fun shouldShow(
    positionMs: Long = 1_000L,
    durationMs: Long = hourDurationMs,
    playbackEnded: Boolean = false,
    isTvShow: Boolean = true,
    hasNextEpisode: Boolean = true,
    dismissed: Boolean = false,
    playbackFailed: Boolean = false,
    hasActiveStream: Boolean = true,
    autoplayBlocked: Boolean = false,
    sourceSelectionActive: Boolean = false
  ): Boolean = NextEpisodePromptUtils.shouldShow(
    positionMs = positionMs,
    durationMs = durationMs,
    playbackEnded = playbackEnded,
    isTvShow = isTvShow,
    hasNextEpisode = hasNextEpisode,
    dismissed = dismissed,
    playbackFailed = playbackFailed,
    hasActiveStream = hasActiveStream,
    autoplayBlocked = autoplayBlocked,
    sourceSelectionActive = sourceSelectionActive
  )

  @Test
  fun `non mostra overlay a inizio episodio`() {
    assertFalse(shouldShow(positionMs = 1_000L))
  }

  @Test
  fun `attiva overlay esattamente a 35 secondi dalla fine`() {
    assertTrue(shouldShow(positionMs = hourDurationMs - NextEpisodePromptUtils.PROMPT_WINDOW_MS))
  }

  @Test
  fun `attiva overlay dentro la finestra degli ultimi 35 secondi`() {
    assertTrue(shouldShow(positionMs = hourDurationMs - 1_000L))
  }

  @Test
  fun `non attiva overlay un istante prima della finestra`() {
    assertFalse(shouldShow(positionMs = hourDurationMs - NextEpisodePromptUtils.PROMPT_WINDOW_MS - 1))
  }

  @Test
  fun `attiva overlay a STATE_ENDED`() {
    assertTrue(shouldShow(playbackEnded = true))
  }

  @Test
  fun `mai overlay per un film`() {
    assertFalse(shouldShow(isTvShow = false))
    assertFalse(shouldShow(isTvShow = false, playbackEnded = true))
  }

  @Test
  fun `mai overlay senza episodio successivo`() {
    assertFalse(shouldShow(hasNextEpisode = false))
    assertFalse(shouldShow(hasNextEpisode = false, playbackEnded = true))
  }

  @Test
  fun `chiusura esplicita nasconde overlay anche a fine episodio`() {
    assertFalse(shouldShow(positionMs = hourDurationMs - 1_000L, dismissed = true))
    assertFalse(shouldShow(playbackEnded = true, dismissed = true))
  }

  @Test
  fun `sessione fallita non mostra overlay`() {
    assertFalse(shouldShow(positionMs = hourDurationMs - 1_000L, playbackFailed = true))
    assertFalse(shouldShow(playbackEnded = true, playbackFailed = true))
  }

  // ── Anti-loop: clock obsoleto, ricerca in corso e fallimento terminale ──────

  @Test
  fun `stream assente non mostra overlay anche se il clock e a fine episodio`() {
    // Dopo il cambio puntata `videoUrl` è null: il clock è fermo alla fine
    // dell'episodio PRECEDENTE e non può dimostrare che quello nuovo sia finito.
    assertFalse(shouldShow(positionMs = hourDurationMs - 1_000L, hasActiveStream = false))
    assertFalse(shouldShow(playbackEnded = true, hasActiveStream = false))
  }

  @Test
  fun `ricerca della stessa fonte in corso blocca overlay e countdown`() {
    assertFalse(shouldShow(positionMs = hourDurationMs - 1_000L, autoplayBlocked = true))
    assertFalse(shouldShow(playbackEnded = true, autoplayBlocked = true))
  }

  @Test
  fun `autoplay fallito non mostra piu overlay nemmeno a fine episodio`() {
    // Stato terminale: stessa finestra temporale di un popup valido, ma nessuno
    // scenario deve riarmare il countdown (era la causa del loop tra episodi).
    assertFalse(
      shouldShow(
        positionMs = hourDurationMs,
        durationMs = hourDurationMs,
        playbackEnded = true,
        autoplayBlocked = true
      )
    )
  }

  @Test
  fun `selezione sorgenti aperta non mostra overlay`() {
    assertFalse(shouldShow(positionMs = hourDurationMs - 1_000L, sourceSelectionActive = true))
    assertFalse(shouldShow(playbackEnded = true, sourceSelectionActive = true))
  }

  @Test
  fun `comportamento normale invariato con stream attivo e autoplay libero`() {
    assertTrue(
      shouldShow(
        positionMs = hourDurationMs - 1_000L,
        hasActiveStream = true,
        autoplayBlocked = false,
        sourceSelectionActive = false
      )
    )
  }

  // ── Messaggio "Nessuna fonte disponibile" (stato terminale) ────────────────

  private fun shouldShowUnavailable(
    state: NextEpisodeAutoplayState,
    sourceSelectionActive: Boolean = false,
    resolutionLoading: Boolean = false
  ): Boolean = NextEpisodePromptUtils.shouldShowUnavailableCard(
    autoplayState = state,
    sourceSelectionActive = sourceSelectionActive,
    resolutionLoading = resolutionLoading
  )

  @Test
  fun `fonte non disponibile mostra il messaggio con la scelta manuale`() {
    assertTrue(shouldShowUnavailable(NextEpisodeAutoplayState.SOURCE_UNAVAILABLE))
    assertTrue(shouldShowUnavailable(NextEpisodeAutoplayState.MANUAL_SELECTION_REQUIRED))
  }

  @Test
  fun `il messaggio non compare negli stati normali o durante la ricerca`() {
    assertFalse(shouldShowUnavailable(NextEpisodeAutoplayState.IDLE))
    assertFalse(shouldShowUnavailable(NextEpisodeAutoplayState.SEARCHING_SAME_SOURCE))
    assertFalse(shouldShowUnavailable(NextEpisodeAutoplayState.STARTED))
  }

  @Test
  fun `il messaggio non copre la selezione sorgenti ne il caricamento manuale`() {
    assertFalse(
      shouldShowUnavailable(
        NextEpisodeAutoplayState.SOURCE_UNAVAILABLE,
        sourceSelectionActive = true
      )
    )
    assertFalse(
      shouldShowUnavailable(
        NextEpisodeAutoplayState.MANUAL_SELECTION_REQUIRED,
        resolutionLoading = true
      )
    )
  }

  @Test
  fun `episodi piu brevi della finestra si attivano solo a STATE_ENDED`() {
    assertFalse(shouldShow(positionMs = 5_000L, durationMs = 20_000L))
    assertTrue(shouldShow(positionMs = 20_000L, durationMs = 20_000L, playbackEnded = true))
  }

  @Test
  fun `durata sconosciuta non attiva la finestra temporale`() {
    assertFalse(shouldShow(positionMs = 1_000L, durationMs = 0L))
  }

  @Test
  fun `countdown di ventinove secondi`() {
    assertEquals(29, NextEpisodePromptUtils.COUNTDOWN_SECONDS)
  }

  @Test
  fun `finestra di attivazione di trentacinque secondi`() {
    assertEquals(35_000L, NextEpisodePromptUtils.PROMPT_WINDOW_MS)
  }

  // ── Countdown del popup: 29 secondi effettivi ──────────────────────────────

  private fun countdownStart(
    promptVisible: Boolean = true,
    autoPlayNextEpisodeEnabled: Boolean = true,
    autoplayBlocked: Boolean = false
  ): Int = NextEpisodePromptUtils.countdownStartSeconds(
    promptVisible = promptVisible,
    autoPlayNextEpisodeEnabled = autoPlayNextEpisodeEnabled,
    autoplayBlocked = autoplayBlocked
  )

  /** Replica il ciclo del player: un tick al secondo fino alla scadenza. */
  private fun remainingPerSecond(startSeconds: Int): List<Int> {
    val timeline = mutableListOf(startSeconds)
    var remaining = startSeconds
    while (!NextEpisodePromptUtils.isCountdownExpired(remaining)) {
      remaining = NextEpisodePromptUtils.countdownAfterTick(remaining)
      timeline += remaining
    }
    return timeline
  }

  @Test
  fun `il countdown parte da ventinove secondi con popup e autoplay attivi`() {
    assertEquals(29, countdownStart(promptVisible = true, autoPlayNextEpisodeEnabled = true))
  }

  @Test
  fun `il countdown sta per intero nella finestra di attivazione`() {
    // Se il countdown fosse più lungo della finestra, il popup sparirebbe prima
    // dell'avvio automatico e il tempo mostrato non sarebbe quello effettivo.
    assertTrue(NextEpisodePromptUtils.COUNTDOWN_SECONDS * 1_000L <= NextEpisodePromptUtils.PROMPT_WINDOW_MS)
  }

  @Test
  fun `avvio automatico solo dopo la scadenza dei ventinove secondi`() {
    val timeline = remainingPerSecond(countdownStart())

    // Il tempo mostrato nel popup è esattamente quello effettivo: 29, 28, ..., 0.
    assertEquals((29 downTo 0).toList(), timeline)
    assertFalse("Dopo 28 secondi non deve ancora partire", NextEpisodePromptUtils.isCountdownExpired(timeline[28]))
    assertEquals(1, timeline[28])
    assertTrue("Solo allo scadere del ventinovesimo secondo parte", NextEpisodePromptUtils.isCountdownExpired(timeline[29]))
    assertEquals(0, timeline[29])
  }

  @Test
  fun `popup chiuso o utente che interviene annulla il countdown`() {
    // BACK/close e "Guarda ora" rendono il popup non visibile: il timer non parte
    // e i secondi visualizzati restano a 0 (nessun avvio automatico).
    assertEquals(0, countdownStart(promptVisible = false))
    assertEquals(0, countdownStart(promptVisible = false, autoplayBlocked = true))
  }

  @Test
  fun `autoplay disattivato non mostra countdown`() {
    assertEquals(0, countdownStart(autoPlayNextEpisodeEnabled = false))
  }

  @Test
  fun `nessun countdown negli stati terminali Debrid`() {
    listOf(
      NextEpisodeAutoplayState.SOURCE_UNAVAILABLE,
      NextEpisodeAutoplayState.MANUAL_SELECTION_REQUIRED
    ).forEach { state ->
      assertTrue("$state è terminale", state.isFailure)
      assertEquals(
        "Nessun countdown in $state",
        0,
        countdownStart(autoplayBlocked = state.blocksAutomaticAdvance)
      )
    }
  }

  @Test
  fun `ricerca della stessa fonte sospende il countdown`() {
    assertEquals(
      0,
      countdownStart(autoplayBlocked = NextEpisodeAutoplayState.SEARCHING_SAME_SOURCE.blocksAutomaticAdvance)
    )
  }

  @Test
  fun `anti-loop invariato con il countdown a ventinove secondi`() {
    // Popup mai visibile (quindi countdown mai armato) su clock obsoleto, durante la
    // ricerca della stessa fonte e dopo il fallimento terminale.
    assertFalse(shouldShow(positionMs = hourDurationMs - 1_000L, hasActiveStream = false))
    assertFalse(shouldShow(playbackEnded = true, autoplayBlocked = true))
    assertEquals(0, countdownStart(autoplayBlocked = true))
  }

  // ── resolveNextEpisode ─────────────────────────────────────────────────────

  private fun ep(season: Int, number: Int): Episode = Episode(
    id = "series_s${season}e${number}",
    seasonNumber = season,
    episodeNumber = number,
    title = "Episodio $number",
    synopsis = "",
    durationMinutes = 50,
    videoUrl = ""
  )

  @Test
  fun `next risolve il successivo nella stessa stagione`() {
    val episodes = listOf(ep(1, 1), ep(1, 2), ep(1, 3))
    val next = NextEpisodePromptUtils.resolveNextEpisode(episodes, ep(1, 2))
    assertEquals(3, next?.episodeNumber)
    assertEquals(1, next?.seasonNumber)
  }

  @Test
  fun `next passa alla stagione successiva con l'ultimo episodio della stagione`() {
    val episodes = listOf(ep(1, 9), ep(1, 10), ep(2, 1), ep(2, 2))
    val next = NextEpisodePromptUtils.resolveNextEpisode(episodes, ep(1, 10))
    assertEquals(2, next?.seasonNumber)
    assertEquals(1, next?.episodeNumber)
  }

  @Test
  fun `next e null dopo l'ultimo episodio della serie`() {
    val episodes = listOf(ep(1, 1), ep(1, 2))
    assertNull(NextEpisodePromptUtils.resolveNextEpisode(episodes, ep(1, 2)))
  }

  @Test
  fun `next usa il numero piu basso della stagione successiva se manca l'E1`() {
    val episodes = listOf(ep(2, 2), ep(2, 3))
    val next = NextEpisodePromptUtils.resolveNextEpisode(episodes, ep(1, 10))
    assertEquals(2, next?.seasonNumber)
    assertEquals(2, next?.episodeNumber)
  }

  @Test
  fun `next funziona con liste non ordinate`() {
    val episodes = listOf(ep(2, 1), ep(1, 2), ep(1, 1))
    val next = NextEpisodePromptUtils.resolveNextEpisode(episodes, ep(1, 1))
    assertEquals(1, next?.seasonNumber)
    assertEquals(2, next?.episodeNumber)
  }

  @Test
  fun `next e null con lista vuota o episodio corrente assente`() {
    assertNull(NextEpisodePromptUtils.resolveNextEpisode(emptyList(), ep(1, 1)))
    assertNull(NextEpisodePromptUtils.resolveNextEpisode(listOf(ep(1, 1)), null))
  }
}
