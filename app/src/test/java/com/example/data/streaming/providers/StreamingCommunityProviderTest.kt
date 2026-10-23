package com.example.data.streaming.providers

import com.example.data.streaming.diagnostics.StreamAttempt
import com.example.data.streaming.diagnostics.StreamAttemptOutcome
import com.example.data.streaming.diagnostics.StreamDiagnosticSink
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Test di StreamingCommunityProvider.
 *
 * I fixture JSON/HTML riproducono **byte per byte la forma** delle risposte del
 * deployment attuale (`streaming-community.farm`, verificato con richieste reali
 * il 2026-10-21); [FakeScSite] è un server locale che serve quei fixture per
 * esercitare il percorso HTTP del provider (ricerca archive, pagina `/watching`,
 * rifugio sui pattern storici, errori HTTP/DNS) senza uscire in rete.
 *
 * Un test verde qui **non** dimostra che il sito reale restituisca sorgenti:
 * quello resta verificabile solo a runtime sul device.
 */
class StreamingCommunityProviderTest {

  // --- 1) parsing della risposta dell'API archive ---------------------------

  /** Forma reale di `/api/v1/web/archive` (risposta di "The Diplomat"). */
  private val archiveResponse = """
    {"ok":true,"items":[
      {"id":27416,"legacyId":27416,"slug":"the-diplomat","title":"The Diplomat","posterUrl":"https://img.streaming-community.farm/t/p/w500/v6f9FUDDQfGIv8MLRQwlL0zvRjI.jpg","year":2023,"durationMin":0,"rating":"7.59","genres":["Dramma"],"trailerKey":null,"imdbId":"tt17491088","scTrailer":false,"upcoming":false,"kind":"series","plot":""},
      {"id":4027,"legacyId":4027,"slug":"diplomacy-una-notte-per-salvare-parigi","title":"Diplomacy - Una notte per salvare Parigi","year":2014,"rating":"6.1","genres":["Storico","Dramma"],"imdbId":"tt2407380","kind":"movie","plot":"Parigi, 1944."}
    ],"count":2}
  """.trimIndent()

  /** Risposta reale per una query senza riscontri: HTTP 200 con array vuoto. */
  private val archiveEmptyResponse = """{"ok":true,"items":[],"count":0}"""

  @Test
  fun parseArchiveItemsEstraeCampiDeiTitoli() {
    val items = StreamingCommunityProvider.parseArchiveItems(archiveResponse)

    assertEquals(2, items.size)
    val series = items[0]
    assertEquals("27416", series.id)
    assertEquals("the-diplomat", series.slug)
    assertEquals("The Diplomat", series.title)
    assertEquals(2023, series.year)
    assertEquals("series", series.kind)
    assertEquals("tt17491088", series.imdbId)

    val movie = items[1]
    assertEquals("4027", movie.id)
    assertEquals("diplomacy-una-notte-per-salvare-parigi", movie.slug)
    assertEquals("Diplomacy - Una notte per salvare Parigi", movie.title)
    assertEquals(2014, movie.year)
    assertEquals("movie", movie.kind)
  }

  @Test
  fun parseArchiveItemsTolleraRispostaVuotaEArrayNidificati() {
    // items vuoto -> nessun candidato (il provider lo tratta come esito definitivo)
    assertTrue(StreamingCommunityProvider.parseArchiveItems(archiveEmptyResponse).isEmpty())
    // array annidato dentro un item: lo split degli oggetti non deve sbilanciarsi
    val nested = """{"ok":true,"items":[{"id":1,"slug":"a","title":"A","year":2020,"kind":"movie","genres":["x","y"],"meta":{"a":[1,2]},"imdbId":"tt1"}],"count":1}"""
    assertEquals(1, StreamingCommunityProvider.parseArchiveItems(nested).size)
    // corpo non JSON -> nessun candidato, nessuna eccezione
    assertTrue(StreamingCommunityProvider.parseArchiveItems("<html>parcheggio</html>").isEmpty())
  }

  @Test
  fun parseArchiveItemsGestisceSequenzeDiEscapeNelTitolo() {
    val body = """{"ok":true,"items":[{"id":9,"slug":"lenigma","title":"L\"enigma \/ L\u0026altro","year":1999,"kind":"movie","imdbId":"tt9"}]}"""
    val item = StreamingCommunityProvider.parseArchiveItems(body).single()
    assertEquals("L\"enigma / L&altro", item.title)
  }

  @Test
  fun unescapeJsonGestisceLeSequenzeRiconosciute() {
    assertEquals("a\"b", StreamingCommunityProvider.unescapeJson("""a\"b"""))
    assertEquals("a/b", StreamingCommunityProvider.unescapeJson("""a\/b"""))
    assertEquals("a\\b", StreamingCommunityProvider.unescapeJson("""a\\b"""))
    assertEquals("a&b", StreamingCommunityProvider.unescapeJson("""a\u0026b"""))
    assertEquals("senza escape", StreamingCommunityProvider.unescapeJson("senza escape"))
    // sequenza \u troncata: non deve lanciare
    assertNotNull(StreamingCommunityProvider.unescapeJson("""a\u00"""))
  }

  // --- 2) riconoscimento del deployment vs pagina di parcheggio -------------

  @Test
  fun isArchiveEnvelopeDistingueApiDaPaginaHtml() {
    assertTrue(StreamingCommunityProvider.isArchiveEnvelope(archiveResponse))
    // busta valida con esito vuoto: resta una risposta definitiva dell'API
    assertTrue(StreamingCommunityProvider.isArchiveEnvelope(archiveEmptyResponse))
    assertTrue(StreamingCommunityProvider.isArchiveEnvelope("  {\"ok\":true,\"items\":[]}"))

    // pagine di parcheggio/errore realmente osservate sui mirror morti
    assertFalse(StreamingCommunityProvider.isArchiveEnvelope(parkingPage))
    assertFalse(StreamingCommunityProvider.isArchiveEnvelope(nextErrorPage))
    assertFalse(StreamingCommunityProvider.isArchiveEnvelope(""))
  }

  /** Pagina reale di un mirror parcheggiato ("Buy this domain", 200 OK). */
  private val parkingPage = """
    <!DOCTYPE html>
    <html lang="italian" data-theme="dark"><head><meta charset="UTF-8">
    <title>streamingcommunity.click</title>
    <meta name="description" content="Find the best information and most relevant links on all topics related to streamingcommunity.click. Buy this domain.">
    </head><body><div class="loading"><h1>streamingcommunity.click</h1></div></body></html>
  """.trimIndent()

  /** Pagina 404 di Next.js con markup HTML (non una busta JSON). */
  private val nextErrorPage = """<!DOCTYPE html><html id="__next_error__"><head><meta charSet="utf-8"/></head><body></body></html>"""

  // --- 3) scelta del titolo e composizione dell'URL -------------------------

  @Test
  fun titleUrlForUsaIlPatternDichiaratoDalSito() {
    val candidate = TitleCandidate(
      id = "27416", slug = "the-diplomat", title = "The Diplomat",
      year = 2023, kind = "series", imdbId = "tt17491088"
    )
    assertEquals(
      "https://streaming-community.farm/titles/27416-the-diplomat-streaming",
      StreamingCommunityProvider.titleUrlFor("https://streaming-community.farm", candidate)
    )
    // origin con barra finale: nessuna doppia barra
    assertEquals(
      "https://streaming-community.farm/titles/27416-the-diplomat-streaming",
      StreamingCommunityProvider.titleUrlFor("https://streaming-community.farm/", candidate)
    )
  }

  @Test
  fun pickBestTitleDisambiguaConLAnno() {
    // Due voci con lo stesso titolo: vince quella con l'anno coerente col film richiesto.
    val candidates = listOf(
      TitleCandidate("21881", "spider-man-beyond-the-spider-verse", "Spider-Man: Beyond the Spider-Verse", 2027, "movie", "tt16360004"),
      TitleCandidate("32167", "spider-man-brand-new-day", "Spider-Man: Brand New Day", 2026, "movie", "tt1"),
      TitleCandidate("999", "spider-man-2", "Spider-Man 2", 2004, "movie", "tt2")
    )
    val chosen = StreamingCommunityProvider.pickBestTitle(candidates, "Spider-Man: Brand New Day", 2026)
    assertEquals("32167", chosen?.id)

    // Anno del catalogo sfasato di un anno (uscita vs distribuzione): resta valido.
    val loose = listOf(
      TitleCandidate("1", "un-altro-film", "Un altro film", 2019, "movie", ""),
      TitleCandidate("2", "the-diplomat", "The Diplomat", 2023, "series", "")
    )
    assertEquals("2", StreamingCommunityProvider.pickBestTitle(loose, "The Diplomat", 2022)?.id)
  }

  @Test
  fun pickBestTitleSenzaParoleInComuneUsaIlPrimoRisultatoDellApi() {
    val candidates = listOf(
      TitleCandidate("25901", "il-nome-della-rosa", "Il nome della rosa", 1986, "movie", ""),
      TitleCandidate("25902", "altro", "Altro", 2001, "movie", "")
    )
    // L'API ha già filtrato per rilevanza: la prima voce è la sua risposta.
    assertEquals("25901", StreamingCommunityProvider.pickBestTitle(candidates, "Nome della rosa", 1986)?.id)
    assertEquals("25901", StreamingCommunityProvider.pickBestTitle(candidates, "titolo inesistente xyz", 2020)?.id)
    assertNull(StreamingCommunityProvider.pickBestTitle(emptyList(), "The Diplomat", 2023))
  }

  @Test
  fun scoreOfNonPremiaLAnnoQuandoMancanoParoleInComune() {
    val unrelated = TitleCandidate("1", "titolo-diverso", "Titolo diverso", 2023, "movie", "")
    assertEquals(0, StreamingCommunityProvider.scoreOf(unrelated, "The Diplomat", 2023))
    val matching = TitleCandidate("2", "the-diplomat", "The Diplomat", 2023, "series", "")
    assertTrue(StreamingCommunityProvider.scoreOf(matching, "The Diplomat", 2023) > 0)
  }

  // --- 4) estrazione dell'iframe del player --------------------------------

  /** Forma reale della pagina `/titles/{id}-{slug}/watching`. */
  private val watchingPage = """
    <!DOCTYPE html><html lang="it"><body><div id="app"><main>
    <div class="tc-hero-trailer"><iframe class="tc-hero-scframe" src="https://v.vidxgo.co/17491088?content=trailer&amp;player=false&amp;info=0" title="The Diplomat - Trailer" allow="autoplay"></iframe></div>
    <div class="tc-player-frame"><iframe src="https://v.vidxgo.co/17491088/1/1?se=0&amp;info=0" title="The Diplomat - Player" allow="autoplay; fullscreen" allowFullScreen=""></iframe></div>
    </main></div></body></html>
  """.trimIndent()

  @Test
  fun playerEmbedUrlScartaIlTrailerEPrendeIlPlayer() {
    val embed = StreamingCommunityProvider.playerEmbedUrl(
      watchingPage,
      "https://streaming-community.farm/titles/27416-the-diplomat-streaming/watching?s=1&e=1"
    )
    assertEquals("https://v.vidxgo.co/17491088/1/1?se=0&info=0", embed)
  }

  /** Il markup scrive `&amp;` nei parametri: l'URL da richiedere è senza entità. */
  @Test
  fun unescapeHtmlEntitiesRipristinaIParametriDellUrl() {
    assertEquals(
      "https://v.vidxgo.co/17491088/1/1?se=0&info=0",
      StreamingCommunityProvider.unescapeHtmlEntities("https://v.vidxgo.co/17491088/1/1?se=0&amp;info=0")
    )
    assertEquals("a&b", StreamingCommunityProvider.unescapeHtmlEntities("a&#38;b"))
    assertEquals("https://x/y?a=1", StreamingCommunityProvider.unescapeHtmlEntities("https://x/y?a=1"))
  }

  @Test
  fun playerEmbedUrlRisolvePercorsiRelativiEPaginaSenzaPlayer() {
    val relative = """<html><body><iframe src="/embed/player/123"></iframe></body></html>"""
    assertEquals(
      "https://streaming-community.farm/embed/player/123",
      StreamingCommunityProvider.playerEmbedUrl(relative, "https://streaming-community.farm/titles/1-x/watching")
    )
    assertNull(StreamingCommunityProvider.playerEmbedUrl("<html><body>niente embed</body></html>", "https://x/"))
    // solo trailer: nessun embed di player
    val onlyTrailer = """<iframe src="https://v.vidxgo.co/1?content=trailer&player=false&info=0"></iframe>"""
    assertNull(StreamingCommunityProvider.playerEmbedUrl(onlyTrailer, "https://x/"))
  }

  // --- 5) percorso HTTP del provider su un sito finto ---------------------

  @Test
  fun titoloAssenteNelCatalogoEUnEsitoDefinitivoSenzaPatternStorici() {
    FakeScSite(routes = mapOf("/api/v1/web/archive" to { 200 to archiveEmptyResponse })).use { site ->
      val error = failWith(listOf(site.baseUrl), "Titolo Inesistente", 2020)

      assertTrue("messaggio inatteso: ${error.message}", error.message!!.contains("titolo assente dal catalogo"))
      // La ricerca archive è la risposta ufficiale del sito: nessun pattern HTML.
      assertEquals(1, site.paths().size)
      assertTrue(
        "atteso il solo percorso archive, ottenuto ${site.paths()}",
        site.paths().single().startsWith("/api/v1/web/archive?q=")
      )
    }
  }

  @Test
  fun paginaDiParcheggioVieneSaltataSenzaFingereUnTitolo() {
    // Un mirror morto risponde 200 con una pagina di parcheggio su ogni percorso.
    FakeScSite(routes = emptyMap()).use { site ->
      val error = failWith(listOf(site.baseUrl), "The Diplomat", 2023)

      assertTrue("messaggio inatteso: ${error.message}", error.message!!.contains("nessuna pagina titolo"))
      // Una sola richiesta per l'API + i pattern storici, senza GET sulla pagina titolo.
      assertFalse(site.paths().any { it.contains("/titles/") })
    }
  }

  @Test
  fun sorgenteDirettaDellaPaginaWatchingDiventaUnoStreamSource() {
    val playlist = "https://cdn.example/master.m3u8?token=abc"
    FakeScSite(
      routes = mapOf(
        "/api/v1/web/archive" to { 200 to archiveResponse },
        "/titles/27416-the-diplomat-streaming/watching" to {
          200 to """<html><body><script>var source = {"file":"$playlist"};</script></body></html>"""
        }
      )
    ).use { site ->
      val sources = runBlocking {
        StreamingCommunityProvider(listOf(site.baseUrl)).getStreams(
          tmdbId = 119051, isTv = true, season = 1, episode = 1,
          title = "The Diplomat", year = 2023
        )
      }

      assertEquals(1, sources.size)
      val source = sources.single()
      assertEquals(playlist, source.streamUrl)
      assertEquals("StreamingCommunity", source.serverName)
      assertEquals("Auto", source.quality)
      // Nessuna altezza inventata: la playlist non e' sondata dall'estrazione.
      assertNull(source.verifiedHeight)
      // Referer e Origin devono puntare all'origin del mirror (molti CDN rispondono 403 senza)
      assertEquals("${site.baseUrl}/", source.headers["Referer"])
      assertEquals(site.baseUrl, source.headers["Origin"])
      assertNotNull(source.headers["User-Agent"])
      assertTrue(site.paths().any { it == "/titles/27416-the-diplomat-streaming/watching?s=1&e=1" })
    }
  }

  @Test
  fun sorgenteMp4SenzaRisoluzioneVerificataHaQualitaAuto() {
    // Un filename che *accenna* a "1080p" non e' una verifica: senza probe la
    // qualita' resta "Auto" e nessuna altezza viene dichiarata (nessuna
    // etichetta inventata, vedi difetto storico del provider).
    val attempts = Collections.synchronizedList(mutableListOf<StreamAttempt>())
    val sink = StreamDiagnosticSink { attempt -> attempts += attempt }
    val mp4 = "https://cdn.example/file_1080p.mp4"
    FakeScSite(
      routes = mapOf(
        "/api/v1/web/archive" to { 200 to archiveResponse },
        "/titles/27416-the-diplomat-streaming/watching" to {
          200 to """<html><body><script>var source = {"file":"$mp4"};</script></body></html>"""
        }
      )
    ).use { site ->
      val sources = runBlocking {
        StreamingCommunityProvider(listOf(site.baseUrl), sink).getStreams(
          tmdbId = 119051, isTv = true, season = 1, episode = 1,
          title = "The Diplomat", year = 2023
        )
      }

      val source = sources.single()
      assertEquals(mp4, source.streamUrl)
      assertEquals("StreamingCommunity", source.serverName)
      assertEquals("Auto", source.quality)
      assertNull(source.verifiedHeight)
      assertEquals("${site.baseUrl}/", source.headers["Referer"])
      assertEquals(site.baseUrl, source.headers["Origin"])

      // La diagnostica per-mirror registra il successo con solo l'host.
      val record = attempts.single { it.providerId == StreamingCommunityProvider.PROVIDER_ID }
      assertEquals(StreamAttemptOutcome.SUCCESS, record.outcome)
      assertEquals("127.0.0.1", record.mirrorId)
      assertFalse("il mirrorId deve essere un host, non un URL", record.mirrorId!!.contains("/"))
    }
  }

  @Test
  fun rispostaSenzaSorgentiProduceErroreDefinitivo() {
    // Pagine 200 ma prive di media e di player: errore esplicito del mirror,
    // nessuna sorgente inventata e nessun fallback su dati assenti.
    FakeScSite(
      routes = mapOf(
        "/api/v1/web/archive" to { 200 to archiveResponse },
        "/titles/27416-the-diplomat-streaming/watching" to { 200 to "<html><body></body></html>" },
        "/titles/27416-the-diplomat-streaming" to { 200 to "<html><body></body></html>" }
      )
    ).use { site ->
      val error = failWith(listOf(site.baseUrl), "The Diplomat", 2023)

      assertTrue(
        "messaggio inatteso: ${error.message}",
        error.message!!.contains("pagina titolo senza sorgenti leggibili")
      )
    }
  }

  @Test
  fun rispostaHttpFallitaDelPlayerRestaSanificataNellaDiagnostica() {
    // L'embed del player risponde 403 (percorso con "play") e la richiesta
    // insegna un token: il record diagnostico porta solo l'host del mirror e
    // il motivo, mai l'URL completo della richiesta o i suoi parametri.
    val attempts = Collections.synchronizedList(mutableListOf<StreamAttempt>())
    val sink = StreamDiagnosticSink { attempt -> attempts += attempt }
    var embedBase = ""
    FakeScSite(
      routes = mapOf(
        "/api/v1/web/archive" to { 200 to archiveResponse },
        "/titles/27416-the-diplomat-streaming/watching" to {
          200 to """<html><body><div class="tc-player-frame"><iframe src="$embedBase/player/17491088/1/1?se=0&token=SEGRETO"></iframe></div></body></html>"""
        }
      )
    ).use { site ->
      embedBase = site.baseUrl

      val caught = runCatching {
        runBlocking {
          StreamingCommunityProvider(listOf(site.baseUrl), sink).getStreams(
            tmdbId = 119051, isTv = true, season = 1, episode = 1,
            title = "The Diplomat", year = 2023
          )
        }
      }
      caught.getOrNull()?.let { fail("attesa IOException, ottenute ${it.size} sorgenti") }
      val error = caught.exceptionOrNull()!!

      assertTrue(
        "atteso il 403 del player: ${error.message}",
        error.message!!.contains("403")
      )

      val record = attempts.single { it.providerId == StreamingCommunityProvider.PROVIDER_ID }
      assertEquals(StreamAttemptOutcome.EXTRACTION_FAILED, record.outcome)
      assertEquals("127.0.0.1", record.mirrorId)
      val message = record.message.orEmpty()
      assertTrue("atteso il contesto del player: $message", message.contains("player") && message.contains("403"))
      val fullUrlRegex = Regex("""https?://[^/\s"']+/\S""")
      assertFalse("nessun URL completo nel record: $message", fullUrlRegex.containsMatchIn(message))
      assertFalse("nessun token sensibile nel record: $message", message.contains("SEGRETO"))
    }
  }

  @Test
  fun leSorgentiDelPlayerUsanoIlRefererEOriginDelPlayerStesso() {
    var playerBase = ""
    FakeScSite(
      routes = mapOf(
        "/embed/x" to { 200 to """<html><video src="https://cdn.example/player/master.m3u8"></video></html>""" }
      )
    ).use { player ->
      playerBase = player.baseUrl
      FakeScSite(
        routes = mapOf(
          "/api/v1/web/archive" to { 200 to archiveResponse },
          "/titles/27416-the-diplomat-streaming/watching" to {
            200 to """<div class="tc-player-frame"><iframe src="$playerBase/embed/x"></iframe></div>"""
          }
        )
      ).use { catalog ->
        val sources = runBlocking {
          StreamingCommunityProvider(listOf(catalog.baseUrl)).getStreams(
            tmdbId = 119051, isTv = true, season = 1, episode = 1,
            title = "The Diplomat", year = 2023
          )
        }

        val source = sources.single()
        assertEquals("https://cdn.example/player/master.m3u8", source.streamUrl)
        // Il CDN del player non accetta il Referer del catalogo: servono quelli
        // dell'origin che ha servito la pagina del player.
        assertEquals("$playerBase/", source.headers["Referer"])
        assertEquals(playerBase, source.headers["Origin"])
        assertNotNull(source.headers["User-Agent"])
      }
    }
  }

  @Test
  fun playerProtettoRiportaIlCodiceHttpInveceDiUnErroreGenerico() {
    // L'iframe del player punta al sito finto, che risponde 403 sui percorsi
    // "play": riproduce il gate anti-bot osservato sul player reale.
    var embedBase = ""
    FakeScSite(
      routes = mapOf(
        "/api/v1/web/archive" to { 200 to archiveResponse },
        "/titles/27416-the-diplomat-streaming/watching" to {
          200 to """<html><body><div class="tc-player-frame"><iframe src="$embedBase/player/17491088/1/1?se=0"></iframe></div></body></html>"""
        }
      )
    ).use { site ->
      embedBase = site.baseUrl
      val error = failWith(listOf(site.baseUrl), "The Diplomat", 2023, isTv = true)

      assertTrue(
        "atteso il dettaglio del player nel messaggio, ottenuto: ${error.message}",
        error.message!!.contains("player") && error.message!!.contains("403")
      )
    }
  }

  @Test
  fun erroreHttpSuUnMirrorNonFermaGliAltri() {
    FakeScSite(routes = mapOf("/api/v1/web/archive" to { 503 to "unavailable" })).use { broken ->
      FakeScSite(
        routes = mapOf(
          "/api/v1/web/archive" to { 200 to archiveResponse },
          "/titles/27416-the-diplomat-streaming/watching" to { 200 to """<script>{"file":"https://cdn.example/b.m3u8"}</script>""" }
        )
      ).use { working ->
        val sources = runBlocking {
          StreamingCommunityProvider(listOf(broken.baseUrl, working.baseUrl)).getStreams(
            tmdbId = 119051, isTv = true, season = 1, episode = 1,
            title = "The Diplomat", year = 2023
          )
        }
        assertEquals(1, sources.size)
        assertEquals("https://cdn.example/b.m3u8", sources.single().streamUrl)
      }
    }
  }

  @Test
  fun erroreDiConnessioneRiportaIlMirrorETuttiITentativi() {
    // Porta chiusa su loopback: errore definitivo immediato (nessuna attesa di 15s).
    val unreachable = "http://127.0.0.1:1"
    val error = failWith(listOf(unreachable), "The Diplomat", 2023)

    assertTrue("atteso 'non risolvibile': ${error.message}", error.message!!.contains("non risolvibile"))
    assertTrue("elenco mirror mancante: ${error.message}", error.message!!.contains(unreachable))
  }

  /**
   * Il player è hostato fuori dal catalogo: un suo blocco non cambia cambiando
   * mirror, e gli alias dello stesso sito ripeterebbero la stessa catena.
   */
  @Test
  fun playerBloccatoInterrompeLIterazioneDeiMirror() {
    var embedBase = ""
    FakeScSite(
      routes = mapOf(
        "/api/v1/web/archive" to { 200 to archiveResponse },
        "/titles/27416-the-diplomat-streaming/watching" to {
          200 to """<div class="tc-player-frame"><iframe src="$embedBase/player/17491088/1/1?se=0"></iframe></div>"""
        }
      )
    ).use { primary ->
      embedBase = primary.baseUrl
      FakeScSite(routes = mapOf("/api/v1/web/archive" to { 200 to archiveResponse })).use { alias ->
        val error = failWith(listOf(primary.baseUrl, alias.baseUrl), "The Diplomat", 2023, isTv = true)

        assertTrue("atteso il 403 del player: ${error.message}", error.message!!.contains("403"))
        // Il secondo mirror non viene mai interrogato: stesso esito garantito.
        assertTrue("alias interrogato inutilmente: ${alias.paths()}", alias.paths().isEmpty())
      }
    }
  }

  @Test
  fun aliasDelloStessoCatalogoNonVieneInterrogatoDueVolte() {
    // Il mirror alias risponde 302 verso il dominio canonico: l'origin finale è
    // già stato interrogato, quindi non si ripete la catena.
    var canonical = ""
    FakeScSite(routes = mapOf("/api/v1/web/archive" to { 200 to archiveResponse })).use { primary ->
      canonical = primary.baseUrl
      FakeScSite(redirects = mapOf("/api/v1/web/archive" to "$canonical/api/v1/web/archive")).use { alias ->
        val error = failWith(listOf(primary.baseUrl, alias.baseUrl), "The Diplomat", 2023)

        assertTrue("atteso il riconoscimento dell'alias: ${error.message}", error.message!!.contains("alias di"))
        // Un solo GET sull'alias (quello che scopre il redirect), nessuna pagina titolo.
        assertEquals(1, alias.paths().size)
        assertFalse(alias.paths().any { it.contains("/titles/") })
      }
    }
  }

  @Test
  fun titoloMancanteNonInterrogaLaRete() {
    val error = failWith(listOf("http://127.0.0.1:1"), title = null, year = null)
    assertTrue("messaggio inatteso: ${error.message}", error.message!!.contains("Titolo non disponibile"))
  }

  // --- helper --------------------------------------------------------------

  /** Esegue la risoluzione attendendosi un'IOException e la ritorna. */
  private fun failWith(
    mirrors: List<String>,
    title: String?,
    year: Int?,
    isTv: Boolean = false
  ): Throwable {
    val result = runCatching {
      runBlocking {
        StreamingCommunityProvider(mirrors).getStreams(
          tmdbId = 1, isTv = isTv, season = 1, episode = 1, title = title, year = year
        )
      }
    }
    result.getOrNull()?.let { fail("atteso errore, ottenute ${it.size} sorgenti") }
    val error = result.exceptionOrNull()!!
    assertTrue("attesa IOException, ottenuta ${error.javaClass.name}", error is java.io.IOException)
    return error
  }

  /**
   * Sito StreamingCommunity finto: mappa percorso -> risposta. I percorsi non
   * mappati rispondono 404; quelli in [redirects] rispondono 302 verso l'URL
   * indicato (come fanno i mirror alias reali); i percorsi che contengono "play"
   * rispondono 403 (come il player protetto reale).
   */
  private class FakeScSite(
    private val routes: Map<String, () -> Pair<Int, String>> = emptyMap(),
    private val redirects: Map<String, String> = emptyMap()
  ) : Closeable {

    private val recorded = Collections.synchronizedList(mutableListOf<String>())
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
      server.createContext("/") { exchange ->
        val uri = exchange.requestURI
        val pathWithQuery = uri.path + (uri.query?.let { "?$it" } ?: "")
        recorded += pathWithQuery

        val location = redirects[uri.path]
        if (location != null) {
          exchange.responseHeaders.add("Location", location)
          exchange.sendResponseHeaders(302, -1)
          return@createContext
        }

        val response = routes[uri.path]?.invoke()
          ?: if (uri.path.contains("play")) 403 to "Access Denied" else 404 to "not found"
        val bytes = response.second.toByteArray()
        exchange.responseHeaders.add("Content-Type", if (uri.path.contains("/api/")) "application/json" else "text/html")
        exchange.sendResponseHeaders(response.first, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
      }
      server.executor = null
      server.start()
    }

    fun paths(): List<String> = recorded.toList()

    override fun close() = server.stop(0)
  }
}
