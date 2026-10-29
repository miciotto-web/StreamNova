package com.aistudio.streamnova.mobile.ui

/** Riproduzione in attesa della verifica PIN. */
data class PendingPlayback(
  val mediaId: String,
  val season: Int? = null,
  val episode: Int? = null
)

/** Esito di una richiesta di riproduzione. */
sealed interface PlaybackRequestResult {
  data class Allowed(
    val mediaId: String,
    val season: Int?,
    val episode: Int?
  ) : PlaybackRequestResult

  data object PinRequired : PlaybackRequestResult
}

/** Esito della verifica del PIN. */
sealed interface PinVerificationResult {
  data class Unlocked(
    val mediaId: String,
    val season: Int?,
    val episode: Int?
  ) : PinVerificationResult

  data object WrongPin : PinVerificationResult

  data object NoPendingPlayback : PinVerificationResult
}

/**
 * Coordina richiesta di riproduzione, blocco per titolo e verifica del PIN.
 *
 * Non conosce Compose né la persistenza: la verifica viene iniettata dal chiamante
 * (`AppSettingsRepository.verifyParentalControlPin`). Lo stato di sblocco è solo in
 * memoria, vale per UN titolo e viene azzerato quando si richiede un titolo diverso
 * o quando si chiude il player. Una verifica errata non sblocca nulla.
 */
class ParentalPlaybackController {

  var unlockedMediaId: String? = null
    private set

  var pendingPlayback: PendingPlayback? = null
    private set

  fun requestPlayback(
    mediaId: String,
    ageRating: Int?,
    enabled: Boolean,
    level: String,
    hasPin: Boolean,
    season: Int? = null,
    episode: Int? = null
  ): PlaybackRequestResult {
    // Cambio titolo: lo sblocco precedente non può autorizzare il nuovo titolo.
    if (unlockedMediaId != null && unlockedMediaId != mediaId) {
      unlockedMediaId = null
    }

    val requiresPin = ParentalControlPolicy.requiresPin(
      mediaId = mediaId,
      ageRating = ageRating,
      enabled = enabled,
      level = level,
      hasPin = hasPin,
      unlockedMediaId = unlockedMediaId
    )

    return if (requiresPin) {
      pendingPlayback = PendingPlayback(mediaId, season, episode)
      PlaybackRequestResult.PinRequired
    } else {
      pendingPlayback = null
      PlaybackRequestResult.Allowed(mediaId, season, episode)
    }
  }

  /**
   * Verifica il PIN della riproduzione in attesa. Un PIN malformato o errato NON
   * avvia nulla e lascia la richiesta in attesa per un nuovo tentativo.
   */
  fun verifyPin(pin: String, verifySavedPin: (String) -> Boolean): PinVerificationResult {
    val pending = pendingPlayback ?: return PinVerificationResult.NoPendingPlayback
    if (!ParentalControlPolicy.isValidPin(pin) || !verifySavedPin(pin)) {
      return PinVerificationResult.WrongPin
    }
    unlockedMediaId = pending.mediaId
    pendingPlayback = null
    return PinVerificationResult.Unlocked(pending.mediaId, pending.season, pending.episode)
  }

  /** Annullamento esplicito: non sblocca il titolo e scarta la richiesta in attesa. */
  fun cancelPin() {
    pendingPlayback = null
  }

  /** Blocca di nuovo tutto (chiusura player o uscita dal titolo). */
  fun lock() {
    unlockedMediaId = null
    pendingPlayback = null
  }
}
