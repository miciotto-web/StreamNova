package com.aistudio.streamnova.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Coordinamento tra richiesta di riproduzione e PIN del singolo titolo.
 *
 * I test coprono i casi richiesti: PIN corretto, PIN errato, annullamento, accesso
 * dopo lo sblocco, isolamento tra titoli, controllo genitori disattivato.
 */
class ParentalPlaybackControllerTest {

  private val restrictedRating = 18
  private val level = "18"

  private fun controller() = ParentalPlaybackController()

  private fun request(
    controller: ParentalPlaybackController,
    mediaId: String = "film-a",
    ageRating: Int? = restrictedRating,
    season: Int? = null,
    episode: Int? = null
  ) = controller.requestPlayback(
    mediaId = mediaId,
    ageRating = ageRating,
    enabled = true,
    level = level,
    hasPin = true,
    season = season,
    episode = episode
  )

  @Test
  fun `restricted title asks for the pin before playing`() {
    val controller = controller()

    assertEquals(PlaybackRequestResult.PinRequired, request(controller))
    assertEquals(PendingPlayback("film-a"), controller.pendingPlayback)
    assertNull(controller.unlockedMediaId)
  }

  @Test
  fun `correct pin unlocks only the requested title and returns its playback`() {
    val controller = controller()
    request(controller, mediaId = "series-a", season = 2, episode = 5)

    val outcome = controller.verifyPin("0427") { it == "0427" }

    assertEquals(PinVerificationResult.Unlocked("series-a", 2, 5), outcome)
    assertEquals("series-a", controller.unlockedMediaId)
    assertNull(controller.pendingPlayback)
  }

  @Test
  fun `wrong pin does not unlock and keeps the pending request for a retry`() {
    val controller = controller()
    request(controller)

    val outcome = controller.verifyPin("0000") { it == "0427" }

    assertEquals(PinVerificationResult.WrongPin, outcome)
    assertNull(controller.unlockedMediaId)
    assertEquals(PendingPlayback("film-a"), controller.pendingPlayback)
    // Un secondo tentativo senza PIN resta bloccato.
    assertEquals(PlaybackRequestResult.PinRequired, request(controller))
  }

  @Test
  fun `malformed pin is rejected without even asking the saved verifier`() {
    val controller = controller()
    request(controller)
    var verifierCalls = 0

    val outcome = controller.verifyPin("42") {
      verifierCalls++
      true
    }

    assertEquals(PinVerificationResult.WrongPin, outcome)
    assertEquals(0, verifierCalls)
    assertNull(controller.unlockedMediaId)
  }

  @Test
  fun `cancelling the dialog does not unlock the title`() {
    val controller = controller()
    request(controller)

    controller.cancelPin()

    assertNull(controller.pendingPlayback)
    assertNull(controller.unlockedMediaId)
    assertEquals(PlaybackRequestResult.PinRequired, request(controller))
  }

  @Test
  fun `after a successful unlock the same title plays without asking again`() {
    val controller = controller()
    request(controller, mediaId = "film-a")
    controller.verifyPin("0427") { it == "0427" }

    val result = request(controller, mediaId = "film-a")

    assertEquals(PlaybackRequestResult.Allowed("film-a", null, null), result)
  }

  @Test
  fun `unlocking one title never unlocks another title`() {
    val controller = controller()
    request(controller, mediaId = "film-a")
    controller.verifyPin("0427") { it == "0427" }

    assertEquals(PlaybackRequestResult.PinRequired, request(controller, mediaId = "film-b"))
    assertNull(controller.unlockedMediaId)
  }

  @Test
  fun `switching to another title discards the previous unlock`() {
    val controller = controller()
    request(controller, mediaId = "film-a")
    controller.verifyPin("0427") { it == "0427" }

    // Il titolo B è innocuo: viene riprodotto, ma lo sblocco di A decade.
    val otherTitle = controller.requestPlayback(
      mediaId = "film-b",
      ageRating = 7,
      enabled = true,
      level = level,
      hasPin = true
    )
    assertEquals(PlaybackRequestResult.Allowed("film-b", null, null), otherTitle)
    assertNull(controller.unlockedMediaId)

    // Tornando su A serve di nuovo il PIN.
    assertEquals(PlaybackRequestResult.PinRequired, request(controller, mediaId = "film-a"))
  }

  @Test
  fun `disabling parental control allows playback without any pin`() {
    val controller = controller()

    val result = controller.requestPlayback(
      mediaId = "film-a",
      ageRating = 18,
      enabled = false,
      level = level,
      hasPin = true
    )

    assertEquals(PlaybackRequestResult.Allowed("film-a", null, null), result)
    assertNull(controller.pendingPlayback)
  }

  @Test
  fun `titles without a classification are never invented as restricted`() {
    val controller = controller()

    val result = controller.requestPlayback(
      mediaId = "unknown-rating",
      ageRating = null,
      enabled = true,
      level = level,
      hasPin = true
    )

    assertEquals(PlaybackRequestResult.Allowed("unknown-rating", null, null), result)
  }

  @Test
  fun `titles below the threshold play without a pin`() {
    val controller = controller()

    assertEquals(
      PlaybackRequestResult.Allowed("teen", null, null),
      request(controller, mediaId = "teen", ageRating = 16)
    )
  }

  @Test
  fun `no configured pin cannot block playback with an impossible request`() {
    val controller = controller()

    val result = controller.requestPlayback(
      mediaId = "film-a",
      ageRating = 18,
      enabled = true,
      level = level,
      hasPin = false
    )

    assertEquals(PlaybackRequestResult.Allowed("film-a", null, null), result)
  }

  @Test
  fun `verify without a pending request does nothing`() {
    val controller = controller()

    assertEquals(
      PinVerificationResult.NoPendingPlayback,
      controller.verifyPin("0427") { true }
    )
    assertNull(controller.unlockedMediaId)
  }

  @Test
  fun `locking on player close removes the unlock`() {
    val controller = controller()
    request(controller, mediaId = "film-a")
    controller.verifyPin("0427") { it == "0427" }

    controller.lock()

    assertNull(controller.unlockedMediaId)
    assertNull(controller.pendingPlayback)
    assertFalse(controller.verifyPin("0427") { true } is PinVerificationResult.Unlocked)
  }
}
