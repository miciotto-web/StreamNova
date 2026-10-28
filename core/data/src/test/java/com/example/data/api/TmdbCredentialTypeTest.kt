package com.example.data.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TMDB accetta due tipi di credenziale con **trasporti diversi**: la API key v3
 * viaggia nel parametro di query `api_key`, mentre il Read Access Token v4 è un
 * JWT e va inviato come header `Authorization: Bearer`.
 *
 * Inviare un Read Access Token valido in `api_key` produce un 401 sistematico su
 * ogni chiamata, che a valle viene interpretato come "titolo non trovato": per
 * questo il client deve riconoscere i due formati.
 */
class TmdbCredentialTypeTest {

  @Test
  fun readAccessTokenV4RiconosciutoComeJwt() {
    // Header JWT reale: `{"alg":"HS256","typ":"JWT"}` in base64url.
    val token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9" +
      ".eyJzdWIiOiIxMjM0NTY3ODkwIn0" +
      ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
    assertTrue(TmdbApiClient.looksLikeReadAccessToken(token))
  }

  @Test
  fun apiKeyV3EsadecimaleNonScambiataPerToken() {
    // Formato reale di una API key v3: 32 caratteri esadecimali, nessun punto.
    assertFalse(TmdbApiClient.looksLikeReadAccessToken("a1b2c3d4e5f60718293a4b5c6d7e8f90"))
  }

  @Test
  fun credenzialeAssenteNonEUnToken() {
    assertFalse(TmdbApiClient.looksLikeReadAccessToken(""))
  }

  @Test
  fun stringaCheIniziaConEyJMaNonJwtNonEUnToken() {
    assertFalse(TmdbApiClient.looksLikeReadAccessToken("eyJnot-a-jwt"))
  }
}
