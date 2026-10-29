package com.aistudio.streamnova.mobile.ui

import com.example.data.model.MediaItem

/**
 * Regole pure del controllo genitori, indipendenti da Compose e dal contesto Android.
 *
 * Rispecchiano il comportamento TV:
 * - il filtro nasconde i titoli con classificazione >= soglia;
 * - un titolo senza classificazione non viene mai bloccato né inventato;
 * - il PIN è richiesto solo se il controllo è attivo, esiste un PIN salvato e il
 *   titolo non è già sbloccato.
 */
object ParentalControlPolicy {

  private const val FALLBACK_THRESHOLD = 18

  /** Soglia numerica letta dal livello persistito ("14", "16", "18" o "18+"). */
  fun thresholdOf(level: String): Int =
    level.trim().removeSuffix("+").toIntOrNull() ?: FALLBACK_THRESHOLD

  fun isRestricted(ageRating: Int?, enabled: Boolean, level: String): Boolean {
    if (!enabled) return false
    // Classificazione sconosciuta: non si inventa un rating e non si blocca il titolo.
    val rating = ageRating ?: return false
    return rating >= thresholdOf(level)
  }

  /**
   * Un titolo richiede il PIN quando è soggetto a restrizione, esiste un PIN
   * configurato e non è già stato sbloccato esplicitamente.
   */
  fun requiresPin(
    mediaId: String,
    ageRating: Int?,
    enabled: Boolean,
    level: String,
    hasPin: Boolean,
    unlockedMediaId: String?
  ): Boolean =
    hasPin && isRestricted(ageRating, enabled, level) && unlockedMediaId != mediaId

  fun isValidPin(pin: String): Boolean = pin.length == 4 && pin.all(Char::isDigit)

  /**
   * Attivazione e disattivazione richiedono sempre la verifica del PIN quando ne
   * esiste già uno. La prima attivazione richiede invece di impostare un PIN valido.
   */
  fun canChangeParentalEnabled(
    enabled: Boolean,
    hasSavedPin: Boolean,
    enteredPinValid: Boolean,
    enteredPinMatches: Boolean
  ): Boolean = if (hasSavedPin) enteredPinValid && enteredPinMatches else enabled && enteredPinValid

  /** Un nuovo PIN si può impostare solo partendo da un PIN già verificato. */
  fun canChangePin(
    hasSavedPin: Boolean,
    currentPinValid: Boolean,
    currentPinMatches: Boolean,
    newPinValid: Boolean
  ): Boolean =
    newPinValid && (!hasSavedPin || (currentPinValid && currentPinMatches))
}

/**
 * Filtro condiviso da Home, cataloghi, preferiti e risultati di ricerca.
 *
 * I titoli senza classificazione restano visibili: nessun dato viene inventato.
 */
fun List<MediaItem>.filterByParental(enabled: Boolean, level: String): List<MediaItem> {
  if (!enabled) return this
  val threshold = ParentalControlPolicy.thresholdOf(level)
  return filter { media -> media.ageRating?.let { it < threshold } ?: true }
}
