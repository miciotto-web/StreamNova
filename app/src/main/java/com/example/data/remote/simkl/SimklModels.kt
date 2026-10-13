package com.example.data.remote.simkl

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SimklDeviceCode(
  @Json(name = "device_code") val deviceCode: String,
  @Json(name = "user_code") val userCode: String,
  @Json(name = "verification_uri") val verificationUri: String,
  @Json(name = "expires_in") val expiresIn: Long,
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

@JsonClass(generateAdapter = true)
data class SimklAccountState(
  val accessToken: String? = null,
  val accountId: Long? = null,
  val username: String? = null,
  val accountType: String? = null,
  val tokenExpiresAt: String? = null
) {
  val isLoggedIn: Boolean get() = !accessToken.isNullOrBlank()
}

@JsonClass(generateAdapter = true)
data class SimklPinState(
  val userCode: String,
  val verificationUri: String,
  val deviceCode: String,
  val expiresIn: Long,
  val interval: Long
)
