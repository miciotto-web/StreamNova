package com.aistudio.streamnova.mobile.ui

import com.example.data.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regole pure del controllo genitori. Nessun Android, nessun Compose: i casi
 * coprono filtro per età, classificazioni sconosciute, validazione e verifica PIN.
 */
class ParentalControlPolicyTest {

  private fun media(id: String, ageRating: Int?) = MediaItem(
    id = id,
    title = id,
    synopsis = "",
    videoUrl = "",
    ageRating = ageRating
  )

  // ── Filtro per età ──────────────────────────────────────────────────────────

  @Test
  fun `age filter hides titles at or above the threshold`() {
    val child = media("child", 7)
    val teen = media("teen", 14)
    val adult = media("adult", 18)

    assertEquals(
      listOf("child"),
      listOf(child, teen, adult).filterByParental(true, "14").map { it.id }
    )
    assertEquals(
      listOf("child", "teen"),
      listOf(child, teen, adult).filterByParental(true, "18").map { it.id }
    )
  }

  @Test
  fun `unknown classification stays visible instead of being invented`() {
    val unknown = media("unknown", null)
    val adult = media("adult", 18)

    assertEquals(
      listOf("unknown"),
      listOf(unknown, adult).filterByParental(true, "18").map { it.id }
    )
    assertFalse(ParentalControlPolicy.isRestricted(null, enabled = true, level = "14"))
  }

  @Test
  fun `disabled parental control does not filter anything`() {
    val items = listOf(media("child", 7), media("adult", 18), media("unknown", null))
    assertEquals(3, items.filterByParental(false, "18").size)
    assertFalse(ParentalControlPolicy.isRestricted(18, enabled = false, level = "18"))
  }

  @Test
  fun `invalid level falls back safely to eighteen`() {
    assertEquals(18, ParentalControlPolicy.thresholdOf("invalid"))
    assertEquals(18, ParentalControlPolicy.thresholdOf(""))
    assertFalse(ParentalControlPolicy.isRestricted(17, true, "invalid"))
    assertTrue(ParentalControlPolicy.isRestricted(18, true, "invalid"))
  }

  @Test
  fun `level accepts the persisted plus suffix`() {
    assertEquals(18, ParentalControlPolicy.thresholdOf("18+"))
    assertEquals(16, ParentalControlPolicy.thresholdOf("16"))
  }

  // ── Validazione PIN ────────────────────────────────────────────────────────

  @Test
  fun `pin must contain exactly four digits`() {
    assertTrue(ParentalControlPolicy.isValidPin("0427"))
    assertFalse(ParentalControlPolicy.isValidPin("427"))
    assertFalse(ParentalControlPolicy.isValidPin("04270"))
    assertFalse(ParentalControlPolicy.isValidPin("04a7"))
    assertFalse(ParentalControlPolicy.isValidPin("    "))
    assertFalse(ParentalControlPolicy.isValidPin(""))
  }

  // ── Richiesta del PIN ──────────────────────────────────────────────────────

  @Test
  fun `restricted title requires pin only when enabled with a saved pin and not unlocked`() {
    assertTrue(
      ParentalControlPolicy.requiresPin("film-18", 18, true, "18", true, null)
    )
    assertFalse(
      ParentalControlPolicy.requiresPin("film-18", 18, false, "18", true, null)
    )
    assertFalse(
      ParentalControlPolicy.requiresPin("film-18", 18, true, "18", true, "film-18")
    )
    assertTrue(
      ParentalControlPolicy.requiresPin("film-18", 18, true, "18", true, "another-film")
    )
    assertFalse(
      ParentalControlPolicy.requiresPin("film-16", 16, true, "18", true, null)
    )
    assertFalse(
      ParentalControlPolicy.requiresPin("film-18", null, true, "18", true, null)
    )
  }

  @Test
  fun `pin is not requested when no pin has been configured`() {
    // Parità TV: senza PIN salvato il controllo non può chiedere una verifica
    // impossibile; l'attivazione richiede comunque di impostarne uno.
    assertFalse(ParentalControlPolicy.requiresPin("film-18", 18, true, "18", false, null))
  }

  // ── Attivazione, disattivazione e cambio PIN ───────────────────────────────

  @Test
  fun `first activation requires a valid new pin`() {
    assertTrue(ParentalControlPolicy.canChangeParentalEnabled(true, false, true, false))
    assertFalse(ParentalControlPolicy.canChangeParentalEnabled(true, false, false, false))
  }

  @Test
  fun `enabling and disabling require the matching pin when one is configured`() {
    assertTrue(ParentalControlPolicy.canChangeParentalEnabled(false, true, true, true))
    assertTrue(ParentalControlPolicy.canChangeParentalEnabled(true, true, true, true))
    assertFalse(ParentalControlPolicy.canChangeParentalEnabled(false, true, true, false))
    assertFalse(ParentalControlPolicy.canChangeParentalEnabled(true, true, true, false))
    assertFalse(ParentalControlPolicy.canChangeParentalEnabled(false, true, false, false))
  }

  @Test
  fun `changing pin requires the current pin and a valid new one`() {
    assertTrue(ParentalControlPolicy.canChangePin(true, true, true, true))
    assertFalse(ParentalControlPolicy.canChangePin(true, true, false, true))
    assertFalse(ParentalControlPolicy.canChangePin(true, true, true, false))
    assertFalse(ParentalControlPolicy.canChangePin(true, false, false, true))
    assertTrue(ParentalControlPolicy.canChangePin(false, false, false, true))
    assertFalse(ParentalControlPolicy.canChangePin(false, false, false, false))
  }
}
