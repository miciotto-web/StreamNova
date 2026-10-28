package com.example.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test deterministici della politica di validazione degli URL di download.
 *
 * Non richiedono rete, non dipendono da una release pubblica e non usano
 * MockWebServer: validano la funzione usata sia dal controllo preliminare
 * sia dall'interceptor di rete, cosi' il comportamento resta verificabile
 * offline.
 */
class ApkDownloaderHostPolicyTest {

  @Test
  fun releaseAssetsRedirectHostIsAllowed() {
    // Host a cui GitHub reindirizza /releases/download/: era la causa del
    // blocco che produceva un errore HTTP fuorviante.
    assertTrue(
      isAllowedReleaseUrl(
        "https://release-assets.githubusercontent.com/github-production-release-asset/1234/abc-def"
      )
    )
  }

  @Test
  fun githubOfficialHostsAreAllowed() {
    assertTrue(isAllowedReleaseUrl("https://github.com/miciotto-web/StreamNova/releases/download/v1.0.0/app.apk"))
    assertTrue(isAllowedReleaseUrl("https://objects.githubusercontent.com/x"))
    assertTrue(isAllowedReleaseUrl("https://api.github.com/repos/miciotto-web/StreamNova/releases/latest"))
  }

  @Test
  fun redirectUrlWithSignedQueryStringIsAllowed() {
    // L'URL firmato reale contiene query string con firma e scadenza: la
    // validazione deve basarsi solo su schema e host, non sul resto.
    assertTrue(
      isAllowedReleaseUrl(
        "https://release-assets.githubusercontent.com/github-production-release-asset/1404847711/" +
          "be5fd6ef-2687-4961-a2cf-36a50dfc7fc9?sp=r&sv=2018-11-09&sig=ABC%2Bdef&se=2026-10-16T16%3A00%3A05Z"
      )
    )
  }

  @Test
  fun cleartextHttpIsRejected() {
    assertFalse(isAllowedReleaseUrl("http://github.com/miciotto-web/StreamNova/releases/download/v1.0.0/app.apk"))
    assertFalse(isAllowedReleaseUrl("http://release-assets.githubusercontent.com/x"))
  }

  @Test
  fun similarAndLookalikeHostsAreRejected() {
    val blocked = listOf(
      "https://github.com.evil.example/x",
      "https://evil.example/github.com",
      "https://notgithub.com/x",
      "https://mygithub.com/x",
      "https://release-assets.githubusercontent.com.evil.example/x",
      "https://evil-release-assets.githubusercontent.com/x",
      "https://githubx.com/x",
      "https://*.github.com/x",
      "https://github.co/x"
    )
    blocked.forEach { url ->
      assertFalse("Doveva essere bloccato: $url", isAllowedReleaseUrl(url))
    }
  }

  @Test
  fun userInfoTrickIsRejected() {
    // Con un confronto testuale la stringa inizia con l'host consentito:
    // l'host reale e' pero' evil.example.
    assertFalse(isAllowedReleaseUrl("https://github.com@evil.example/x"))
  }

  @Test
  fun malformedUrlsFailClosed() {
    val malformed = listOf(
      "",
      "   ",
      "non-una-url",
      "://github.com/x",
      "ftp://github.com/x",
      "https://",
      "https:///path"
    )
    malformed.forEach { url ->
      assertFalse("Doveva essere bloccato: '$url'", isAllowedReleaseUrl(url))
    }
  }

  @Test
  fun hostCaseAndTrailingDotAreNormalized() {
    // Il nome host e' case-insensitive e il punto finale e' valido in DNS:
    // entrambi devono normalizzare sull'host consentito.
    assertTrue(isAllowedReleaseUrl("HTTPS://GITHUB.COM/miciotto-web/StreamNova/releases/download/v1.0.0/app.apk"))
    assertTrue(isAllowedReleaseUrl("https://github.com./x"))
  }

  @Test
  fun safeHostForLogNeverLeaksQueryString() {
    // I log devono riportare solo l'host: niente firma, token o query string.
    val signed =
      "https://release-assets.githubusercontent.com/asset/1234?sig=SECRETSIGNATURE&se=2030-01-01"
    val host = safeHostForLog(signed)
    assertEquals("release-assets.githubusercontent.com", host)
    assertFalse(host.contains("SECRET"))
    assertFalse(host.contains("?"))
  }

  @Test
  fun safeHostForLogNeverEchoesRawInputWhenHostIsMissing() {
    // "non-una-url" e' un URI relativo valido ma senza host: la funzione
    // deve restituire un segnaposto, mai l'input grezzo.
    val value = safeHostForLog("non-una-url")
    assertEquals("host sconosciuto", value)
    assertFalse(value.contains("non-una-url"))

    // Input che java.net.URI rifiuta del tutto (autorita' mancante):
    // ramo diverso, sempre senza echo dell'input.
    val unparsable = safeHostForLog("http://")
    assertEquals("URL non valido", unparsable)
  }

  @Test
  fun safeHostForLogDropsUserInfoPathAndQuery() {
    // Nessuna credenziale, nessun path e nessuna query string devono finire
    // nel log: solo l'host dell'host effettivamente bloccato.
    val hostile = "https://user:secret@evil.example/deep/path?token=TOPSECRET&sig=ABC"
    val value = safeHostForLog(hostile)
    assertEquals("evil.example", value)
    assertFalse(value.contains("secret"))
    assertFalse(value.contains("TOPSECRET"))
    assertFalse(value.contains("/deep/path"))
  }

  @Test
  fun hostNotAllowedIsADistinctReasonFromRealHttpError() {
    // Un redirect bloccato non deve essere confuso con un vero errore HTTP,
    // cosi' il messaggio mostrato all'utente resta onesto.
    val hostileUrl = "https://user:secret@evil.example/path?token=TOPSECRET"
    val blocked = DownloadException(
      DownloadErrorReason.HOST_NOT_ALLOWED,
      "Host non consentito: ${safeHostForLog(hostileUrl)}"
    )
    val serverError = DownloadException(DownloadErrorReason.HTTP_ERROR, "HTTP 503")

    assertEquals(DownloadErrorReason.HOST_NOT_ALLOWED, blocked.reason)
    assertFalse(blocked.reason == serverError.reason)

    // Il messaggio riporta solo l'host del blocco: niente credenziali
    // userinfo, niente path, niente query string firmata.
    val message = blocked.message.orEmpty()
    assertTrue(message.contains("evil.example"))
    assertFalse(message.contains("TOPSECRET"))
    assertFalse(message.contains("secret"))
    assertFalse(message.contains("token="))
    assertFalse(message.contains("https://"))
  }

  @Test
  fun allowedHostsListIsExplicitAndMinimal() {
    assertEquals(
      setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "api.github.com"
      ),
      ALLOWED_RELEASE_HOSTS
    )
  }
}