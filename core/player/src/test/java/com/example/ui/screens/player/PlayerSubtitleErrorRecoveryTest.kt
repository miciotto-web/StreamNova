package com.example.ui.screens.player

import com.example.domain.model.Subtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressione della decisione di recupero dopo un errore di una traccia sottotitoli
 * esterna.
 *
 * Obiettivo: un errore attribuito con certezza al sottotitolo NON deve mai portare al
 * cambio sorgente ne' allo schermo nero. La traccia problematica viene staccata e il
 * video riprende; le tracce esterne che NON sono la causa restano allegate.
 */
class PlayerSubtitleErrorRecoveryTest {

  private val failedUri = "file:///cache/os_42.srt"
  private val otherUri = "https://subs.test/other.srt"

  private fun subtitle(id: String, url: String, streamProvided: Boolean = false) = Subtitle(
    id = id,
    url = url,
    lang = "ita",
    addonName = "OpenSubtitles v3",
    isStreamProvided = streamProvided
  )

  // ── Decisione ────────────────────────────────────────────────────────────────

  @Test
  fun tracciaFallitaAllegataVieneStaccata() {
    val action = PlayerSubtitleErrorRecovery.decide(
      failedSubtitleUri = failedUri,
      attachedUris = listOf(failedUri, otherUri),
      streamProvidedIds = emptySet(),
      recoveriesExhausted = false
    )

    assertEquals(PlayerSubtitleErrorRecovery.Action.DROP_FAILED_TRACK, action)
  }

  @Test
  fun tentativiEsauritiStaccanoTutteLeTracceEsterne() {
    val action = PlayerSubtitleErrorRecovery.decide(
      failedSubtitleUri = failedUri,
      attachedUris = listOf(failedUri, otherUri),
      streamProvidedIds = emptySet(),
      recoveriesExhausted = true
    )

    assertEquals(PlayerSubtitleErrorRecovery.Action.DROP_ALL_EXTERNAL_TRACKS, action)
  }

  @Test
  fun tracciaFallitaNonPiuAllegataRiprendeIlVideo() {
    val action = PlayerSubtitleErrorRecovery.decide(
      failedSubtitleUri = failedUri,
      attachedUris = listOf(otherUri),
      streamProvidedIds = emptySet(),
      recoveriesExhausted = false
    )

    assertEquals(PlayerSubtitleErrorRecovery.Action.RESUME_VIDEO, action)
  }

  @Test
  fun tracciaNonIdentificabileStaccaLeTracceEsterneNonDelFlusso() {
    val action = PlayerSubtitleErrorRecovery.decide(
      failedSubtitleUri = null,
      attachedUris = listOf(failedUri, otherUri),
      streamProvidedIds = emptySet(),
      recoveriesExhausted = false
    )

    assertEquals(PlayerSubtitleErrorRecovery.Action.DROP_FAILED_TRACK, action)
  }

  @Test
  fun tracciaNonIdentificabileConservaLeTracceDelFlusso() {
    val action = PlayerSubtitleErrorRecovery.decide(
      failedSubtitleUri = null,
      attachedUris = listOf(failedUri),
      streamProvidedIds = setOf("os-42"),
      recoveriesExhausted = false
    )

    // La sola traccia allegata e' dichiarata dal flusso: non c'e' nulla di rimovibile
    // che non sia essa, quindi si stacca tutto per garantire che l'errore non si ripeta.
    assertEquals(PlayerSubtitleErrorRecovery.Action.DROP_ALL_EXTERNAL_TRACKS, action)
  }

  @Test
  fun nessunaTracciaEsternaAllegataDisattivaIlRendererDiTesto() {
    val action = PlayerSubtitleErrorRecovery.decide(
      failedSubtitleUri = null,
      attachedUris = emptyList(),
      streamProvidedIds = emptySet(),
      recoveriesExhausted = false
    )

    assertEquals(PlayerSubtitleErrorRecovery.Action.DISABLE_TEXT_RENDERER, action)
  }

  // ── Sottotitoli conservati ────────────────────────────────────────────────────

  @Test
  fun staccandoLaTracciaFallitaLeAltreRestanoAllegate() {
    val subtitles = listOf(
      subtitle("os-42", failedUri),
      subtitle("stremio-1", otherUri)
    )

    val remaining = PlayerSubtitleErrorRecovery.remainingSubtitles(
      action = PlayerSubtitleErrorRecovery.Action.DROP_FAILED_TRACK,
      subtitles = subtitles,
      failedSubtitleUri = failedUri
    )

    assertEquals(1, remaining.size)
    assertEquals(otherUri, remaining.first().url)
  }

  @Test
  fun staccandoTutteLeTracceEsterneRestanoSoloQuelleDelFlusso() {
    val subtitles = listOf(
      subtitle("os-42", failedUri),
      subtitle("stremio-1", otherUri, streamProvided = true)
    )

    val remaining = PlayerSubtitleErrorRecovery.remainingSubtitles(
      action = PlayerSubtitleErrorRecovery.Action.DROP_ALL_EXTERNAL_TRACKS,
      subtitles = subtitles,
      failedSubtitleUri = failedUri
    )

    assertEquals(1, remaining.size)
    assertEquals("stremio-1", remaining.first().id)
  }

  @Test
  fun conTracciaNonIdentificabileRestanoLeTracceDelFlusso() {
    val subtitles = listOf(
      subtitle("os-42", failedUri),
      subtitle("stremio-1", otherUri, streamProvided = true)
    )

    val remaining = PlayerSubtitleErrorRecovery.remainingSubtitles(
      action = PlayerSubtitleErrorRecovery.Action.DROP_FAILED_TRACK,
      subtitles = subtitles,
      failedSubtitleUri = null
    )

    assertEquals(1, remaining.size)
    assertEquals("stremio-1", remaining.first().id)
  }

  @Test
  fun ilConfrontoDegliUriIgnoraQueryStringEFrammento() {
    val subtitles = listOf(subtitle("os-42", "file:///cache/os_42.srt?token=abc"))

    val remaining = PlayerSubtitleErrorRecovery.remainingSubtitles(
      action = PlayerSubtitleErrorRecovery.Action.DROP_FAILED_TRACK,
      subtitles = subtitles,
      failedSubtitleUri = "file:///cache/os_42.srt"
    )

    assertTrue("la traccia con la query string deve essere riconosciuta come fallita", remaining.isEmpty())
  }
}
