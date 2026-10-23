package com.example.data.streaming.providers

import com.example.data.streaming.extractors.MaxStreamExtractor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Matching titoli brevi, anno, shortener con/senza `www.` e header MaxStream. */
class HosterResolverMatchingTest {

  @Test
  fun titoliBreviRichiedonoUnMatchEsatto() {
    assertTrue(HosterResolver.matchScore("it-2017", "It", 2017) > 0)
    assertTrue(HosterResolver.matchScore("up-2009", "Up", 2009) > 0)
    assertTrue(HosterResolver.matchScore("io-e-te-2010", "Io", 2010) > 0)

    // "It" dentro "little" o dentro una parola più lunga non è un match.
    assertEquals(0, HosterResolver.matchScore("little-women-2019", "It", 2019))
    assertEquals(0, HosterResolver.matchScore("l-ultimo-bacio", "Io", 2020))
  }

  @Test
  fun annoCorrispondenteDaUnBonusNonUnFiltroRigido() {
    val matching = HosterResolver.matchScore("dune-2021", "Dune", 2021)
    val different = HosterResolver.matchScore("dune-1984", "Dune", 2021)
    val absent = HosterResolver.matchScore("dune", "Dune", null)

    assertTrue(matching > different)
    assertTrue(different > 0)
    assertTrue(absent > 0)
    assertTrue(matching >= absent)
  }

  @Test
  fun titoloLungoConPunteggiaturaEAnnoNonVienePerso() {
    // Caso reale: "Occhio, malocchio, prezzemolo e finocchio" (TMDB 55493).
    val title = "Occhio, malocchio, prezzemolo e finocchio"

    assertEquals("occhio malocchio prezzemolo e finocchio", HosterResolver.normalize(title))

    val right = HosterResolver.matchScore(
      "occhio-malocchio-prezzemolo-e-finocchio-hd-1983",
      title,
      1983
    )
    val wrongYear = HosterResolver.matchScore(
      "occhio-malocchio-prezzemolo-e-finocchio-hd-2024",
      title,
      1983
    )

    assertTrue("il titolo normalizzato deve produrre un match", right > 0)
    assertTrue("l'anno corretto deve essere preferito", right > wrongYear)
  }

  @Test
  fun shortenerStayonlineAccettaWwwESoloIlDominioReale() {
    assertNotNull(HosterResolver.parseStayOnline("https://www.stayonline.pro/l/ABC123"))
    assertNotNull(HosterResolver.parseStayOnline("http://stayonline.pro/e/abc123"))
    assertNull(HosterResolver.parseStayOnline("https://stayonline.pro.example.org/l/ABC123"))
    assertNull(HosterResolver.parseStayOnline("https://notstayonline.pro/l/ABC123"))
    assertNull(HosterResolver.parseStayOnline("https://stayonline.pro/l/"))
  }

  @Test
  fun endpointStayonlineDipendonoDaLPercorso() {
    val embed = HosterResolver.parseStayOnline("https://www.stayonline.pro/e/CODE")!!
    assertEquals("https://www.stayonline.pro", embed.origin)
    assertEquals("linkEmbedView.php", embed.endpoint)
    assertEquals("CODE", embed.code)

    val link = HosterResolver.parseStayOnline("https://stayonline.pro/l/CODE")!!
    assertEquals("linkView.php", link.endpoint)
  }

  @Test
  fun uprotAccettaWwwERifiutaDominiIngannevoli() {
    assertTrue(HosterResolver.isUprotCaptcha("https://www.uprot.net/msf/CODE"))
    assertTrue(HosterResolver.isUprotCaptcha("http://uprot.net/msei/CODE"))
    assertFalse(HosterResolver.isUprotCaptcha("https://uprot.net.example.org/msf/CODE"))
    assertFalse(HosterResolver.isUprotCaptcha("https://notuprot.net/msf/CODE"))
  }

  @Test
  fun stayonlineCheRisolveSuUprotVieneClassificatoComeChallenge() {
    // Caso reale CB01: il POST di stayonline restituisce uprot.net/msfi|msei.
    // Prima del fix il valore veniva restituito come "risolto" e finiva nel
    // ramo "hoster non supportato", perdendo la causa (CAPTCHA).
    val link = HosterResolver.parseStayOnline("https://stayonline.pro/l/CODE123")!!
    val body = """{"status":"success","data":{"value":"https:\/\/uprot.net\/msfi\/ZXhhbXBsZQ=="}}"""

    val result = HosterResolver.parseStayOnlineResponse(link, body)

    assertTrue(result is HosterResolver.ShortLinkResolution.Failed)
    assertEquals(
      com.example.data.streaming.diagnostics.StreamAttemptOutcome.BLOCKED_BY_CHALLENGE,
      (result as HosterResolver.ShortLinkResolution.Failed).outcome
    )
  }

  @Test
  fun stayonlineCheRisolveSuMixdropRestituisceUrlRisolto() {
    val link = HosterResolver.parseStayOnline("https://stayonline.pro/l/CODE123")!!
    val body = """{"status":"success","data":{"value":"https:\/\/m1xdrop.net\/f\/o7llre4nalq30m"}}"""

    val result = HosterResolver.parseStayOnlineResponse(link, body)

    assertEquals(
      HosterResolver.ShortLinkResolution.Resolved("https://m1xdrop.net/f/o7llre4nalq30m"),
      result
    )
  }

  @Test
  fun uprotProtettoDaCaptchaVieneScartatoSenzaRete() = runBlocking {
    assertNull(HosterResolver.resolveShortLink("https://www.uprot.net/msf/CODE", "https://example.test/"))
  }

  @Test
  fun streamwishHaUnEstrattoreIntegratoEVieneDichiaratoSupportato() = runBlocking {
    // StreamWish è riconosciuto e mappato su StreamWishExtractor (estrazione
    // verificata offline in StreamWishExtractorTest): la pipeline puo' seguirlo.
    val extractor = HosterResolver.extractorFor("https://streamwish.to/e/x")
    assertTrue(HosterResolver.isHosterUrl("https://streamwish.to/e/x"))
    assertTrue(extractor is com.example.data.streaming.extractors.StreamWishExtractor)
    assertTrue(HosterResolver.isSupportedHosterUrl("https://streamwish.to/e/x"))

    // Tutti i domini osservati della famiglia restano riconosciuti...
    for (domain in listOf("streamwish.to", "streamwish.com", "streamwish.sx", "streamwish.xyz")) {
      assertNotNull("dominio $domain senza estrattore", HosterResolver.extractorFor("https://www.$domain/e/x"))
    }
    // ...ma i domini impersonatori no.
    assertNull(HosterResolver.extractorFor("https://notstreamwish.to/e/x"))
    assertNull(HosterResolver.extractorFor("https://streamwish.to.example.org/e/x"))
  }

  @Test
  fun hosterSoloRiconosciutiNonSonoDichiaratiSupportati() {
    // clicka.cc non è mappato: nessun supporto dichiarato.
    assertFalse(HosterResolver.isHosterUrl("https://clicka.cc/abc"))
    assertNull(HosterResolver.extractorFor("https://clicka.cc/abc"))
  }

  @Test
  fun riconosceIDominiMixdropAttiviENonQuelliImpostori() {
    // m1xdrop.net redirige su mxdrop.click/mixdrop.ag: l'URL finale deve
    // restare associato all'estrattore Mixdrop anche dopo il redirect.
    assertTrue(HosterResolver.isHosterUrl("https://mxdrop.click/e/ABC"))
    assertTrue(HosterResolver.isHosterUrl("https://mixdrop.ag/e/ABC"))
    assertTrue(HosterResolver.isSupportedHosterUrl("https://mxdrop.click/e/ABC"))
    assertNotNull(HosterResolver.extractorFor("https://mxdrop.click/e/ABC"))

    assertFalse(HosterResolver.isHosterUrl("https://notmixdrop.ag/e/ABC"))
    assertFalse(HosterResolver.isHosterUrl("https://mixdrop.ag.example.org/e/ABC"))
    assertNull(HosterResolver.extractorFor("https://notmixdrop.ag/e/ABC"))
  }

  @Test
  fun canonicalizzaLaPaginaFileMixdropSullEmbed() {
    // `/f/` e `/e/` sono lo stesso video: senza canonicalizzazione veniva
    // estratto due volte e l'utente vedeva due sorgenti identiche.
    assertEquals(
      "https://m1xdrop.net/e/CODE",
      HosterResolver.canonicalResolvedUrl("https://m1xdrop.net/f/CODE")
    )
    assertEquals(
      "https://mxdrop.click/e/CODE?x=1",
      HosterResolver.canonicalResolvedUrl("https://mxdrop.click/f/CODE?x=1")
    )
    // Nessuna riscrittura su hoster diversi o path non Mixdrop.
    assertEquals(
      "https://maxstream.video/f/CODE",
      HosterResolver.canonicalResolvedUrl("https://maxstream.video/f/CODE")
    )
    assertEquals(
      "https://m1xdrop.net/e/CODE",
      HosterResolver.canonicalResolvedUrl("https://m1xdrop.net/e/CODE")
    )
  }

  @Test
  fun accettaLabelConSuffissoDiQualitaOAudio() {
    assertTrue(HosterResolver.matchesHosterLabel("Maxstream"))
    assertTrue(HosterResolver.matchesHosterLabel("Maxstream HD"))
    assertTrue(HosterResolver.matchesHosterLabel("Mixdrop HD (ITA)"))
    assertTrue(HosterResolver.matchesHosterLabel("Mixdrop 1080p"))
    assertFalse(HosterResolver.matchesHosterLabel("Server 2 (ITA)"))
    assertFalse(HosterResolver.matchesHosterLabel("Maxstream gratis"))
  }

  @Test
  fun refererMaxStreamUsaLaOriginEffettivaSoloPerIDominiAmmessi() {
    val extractor = MaxStreamExtractor()

    assertEquals("https://maxstream.video/", extractor.maxStreamReferer("https://maxstream.video/e/x"))
    assertEquals("https://www.maxstream.net/", extractor.maxStreamReferer("https://www.maxstream.net/e/x"))
    assertEquals("https://cdn.maxstream.org/", extractor.maxStreamReferer("https://cdn.maxstream.org/e/x"))
    assertEquals(
      "https://maxstream.video/",
      extractor.maxStreamReferer("https://maxstream.video.evil.com/e/x")
    )
    assertEquals(
      "https://maxstream.video/",
      extractor.maxStreamReferer("https://notmaxstream.video/e/x")
    )
  }

  @Test
  fun refererMaxStreamNonInventaUnOriginDaUrlAssenteOMalformato() {
    val extractor = MaxStreamExtractor()

    assertEquals("https://maxstream.video/", extractor.maxStreamReferer(""))
    assertEquals("https://maxstream.video/", extractor.maxStreamReferer("   "))
    assertEquals("https://maxstream.video/", extractor.maxStreamReferer("non un url"))
    assertEquals("https://maxstream.video/", extractor.maxStreamReferer("https://"))
    assertEquals("https://maxstream.video/", extractor.maxStreamReferer("/e/senza-host"))
    // Fallback esplicito del chiamante, usato quando l'URL non è interpretabile.
    assertEquals("https://fallback.example/", extractor.maxStreamReferer("", "https://fallback.example/"))
  }
}
