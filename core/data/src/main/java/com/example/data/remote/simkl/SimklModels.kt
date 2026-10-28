package com.example.data.remote.simkl

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SimklDeviceCode(
  @Json(name = "device_code") val deviceCode: String,
  @Json(name = "user_code") val userCode: String,
  @Json(name = "verification_uri") val verificationUri: String,
  // Il protocollo documenta `expires_in`; quando manca non si perde il codice appena
  // ottenuto: vale la finestra documentata di 15 minuti, come per il flusso V2.
  @Json(name = "expires_in") val expiresIn: Long = 900L,
  val interval: Long = 5L
)

@JsonClass(generateAdapter = true)
data class SimklPinPollResponse(
  @Json(name = "access_token") val accessToken: String? = null,
  @Json(name = "expires_at") val expiresAt: String? = null,
  // Shape "d'emergenza": se il codice originale e' stato consumato o scaduto,
  // SIMKL risponde con la stessa risposta di creazione PIN (nuovo user_code +
  // device_code). Serve per fermare subito il polling invece di girare fino al
  // timeout indovinando il codice giusto. Vedi docs AUTH V1 PIN flow.
  @Json(name = "device_code") val deviceCode: String? = null,
  @Json(name = "user_code") val userCode: String? = null,
  @Json(name = "verification_uri") val verificationUri: String? = null,
  val error: String? = null,
  val message: String? = null,
  val result: String? = null
)

@JsonClass(generateAdapter = true)
data class SimklUserSettingsResponse(
  val user: SimklUser? = null,
  val account: SimklAccount? = null
)

@JsonClass(generateAdapter = true)
data class SimklUser(val name: String? = null)

@JsonClass(generateAdapter = true)
data class SimklAccount(val id: Long? = null, val type: String? = null)

@JsonClass(generateAdapter = true)
data class SimklIds(
  val tmdb: String? = null,
  val imdb: String? = null
)

@JsonClass(generateAdapter = true)
data class SimklMovie(
  val title: String,
  val year: Int? = null,
  val ids: SimklIds
)

@JsonClass(generateAdapter = true)
data class SimklShow(
  val title: String,
  val year: Int? = null,
  val ids: SimklIds
)

@JsonClass(generateAdapter = true)
data class SimklEpisode(
  val season: Int,
  val episode: Int
)

/**
 * Stato dell'account in memoria: i singoli campi sono persistiti per chiave in DataStore
 * (non esiste un adapter Moshi per questa classe).
 */
data class SimklAccountState(
  val accessToken: String? = null,
  val refreshToken: String? = null,
  val accountId: Long? = null,
  val username: String? = null,
  val accountType: String? = null,
  val tokenExpiresAt: String? = null,
  /** Scadenza dell'access token in epoch millis (solo V2: è l'unico flusso con refresh). */
  val tokenExpiresAtEpochMs: Long? = null,
  /** Scope concesso (solo V2): se manca `media:write` il token è di sola lettura. */
  val scope: String? = null,
  /** Flusso con cui è stato ottenuto il token: determina refresh e revoca. */
  val flow: SimklAuthFlow? = null,
  /** Il refresh V2 è stato rifiutato: serve una nuova autorizzazione (token conservati). */
  val sessionExpired: Boolean = false
) {
  val isLoggedIn: Boolean get() = !accessToken.isNullOrBlank()

  /**
   * Token V2 senza `media:write`: legge tutto ma prende 403 alla prima scrittura
   * (scrobble, cronologia). Va segnalato all'utente, non nascosto.
   */
  val isReadOnly: Boolean
    get() = flow == SimklAuthFlow.V2_DEVICE && scope?.contains("media:write") != true
}

@JsonClass(generateAdapter = true)
data class SimklPinState(
  val userCode: String,
  val verificationUri: String,
  val deviceCode: String,
  val expiresIn: Long,
  val interval: Long
)

// ── AUTH V2 (RFC 8628 device flow) ─────────────────────────────────────────────
// docs: api.simkl.org/api-reference/simkl/oauth2-device e .../oauth2-token

/** Risposta di `POST /oauth2/device`. `verification_uri_complete` precompila il codice. */
@JsonClass(generateAdapter = true)
data class SimklDeviceAuthorization(
  @Json(name = "device_code") val deviceCode: String,
  @Json(name = "user_code") val userCode: String,
  @Json(name = "verification_uri") val verificationUri: String,
  @Json(name = "verification_uri_complete") val verificationUriComplete: String? = null,
  @Json(name = "expires_in") val expiresIn: Long = 900L,
  val interval: Long = 5L
)

/**
 * Risposta di `POST /oauth2/token` (grant `device_code` o `refresh_token`).
 * Tutti i campi sono opzionali: in caso di errore SIMKL risponde con l'envelope
 * RFC 6749 ([SimklOAuthError]) e un body che non ha `access_token`.
 */
@JsonClass(generateAdapter = true)
data class SimklTokenResponse(
  @Json(name = "access_token") val accessToken: String? = null,
  @Json(name = "refresh_token") val refreshToken: String? = null,
  @Json(name = "token_type") val tokenType: String? = null,
  @Json(name = "expires_in") val expiresIn: Long? = null,
  val scope: String? = null
)

/** Envelope d'errore RFC 6749 5.2 usato da tutti gli endpoint oauth2. */
@JsonClass(generateAdapter = true)
data class SimklOAuthError(
  val error: String? = null,
  @Json(name = "error_description") val errorDescription: String? = null
)
