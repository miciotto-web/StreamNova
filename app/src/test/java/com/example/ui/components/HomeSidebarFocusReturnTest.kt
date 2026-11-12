package com.example.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prove della RISOLUZIONE del ritorno dalla sidebar (riga + identità + colonna).
 *
 * Il contratto è deterministico e indipendente dalla geometria: stessa riga se esiste,
 * elemento ritrovato per IDENTITÀ stabile (un riordino non cambia la card), fallback
 * controllati e mai un salto arbitrario all'Hero.
 */
class HomeSidebarFocusReturnTest {

  private fun origin(
    rowKey: String,
    colIndex: Int,
    identity: String? = null,
    rowOrdinal: Int = 0
  ) = HomeFocusOrigin(
    rowKey = rowKey,
    colIndex = colIndex,
    identity = identity,
    cardKey = identity?.let { "card_$it" },
    rowOrdinal = rowOrdinal,
    listFirstVisibleIndex = 0,
    listFirstVisibleOffset = 0
  )

  private val rows = listOf("hero_banner", "section_providers_hub", "section_trending_film")

  private val counts = mapOf(
    "hero_banner" to 1,
    "section_providers_hub" to 5,
    "section_trending_film" to 10
  )

  private val identities = mapOf(
    "hero_banner" to emptyList<String>(),
    "section_providers_hub" to listOf("netflix", "apple_tv", "disney", "prime", "hbo"),
    "section_trending_film" to List(10) { "m$it" }
  )

  @Test
  fun rigaAncoraPresente_ripristinaLoStessoElementoNellaStessaRiga() {
    val target = resolveHomeSidebarReturnTarget(
      origin = origin("section_providers_hub", colIndex = 0, identity = "netflix", rowOrdinal = 1),
      activeRowKeys = rows,
      rowItemCounts = counts,
      rowIdentities = identities
    )

    assertEquals(HomeSidebarReturnTarget("section_providers_hub", 0), target)
  }

  @Test
  fun riordinoDellaRiga_ripristinaPerIdentitaNonPerIndice() {
    // Il catalogo si è riordinato mentre l'utente era nella sidebar: "m7" non è più la
    // colonna 3 ma la 8. Il focus deve seguire la CARD, non la posizione ricordata.
    val reordered = identities.toMutableMap().apply {
      put("section_trending_film", List(10) { "m${(it + 5) % 10}" })
    }

    val target = resolveHomeSidebarReturnTarget(
      origin = origin("section_trending_film", colIndex = 3, identity = "m7", rowOrdinal = 2),
      activeRowKeys = rows,
      rowItemCounts = counts,
      rowIdentities = reordered
    )

    assertEquals(HomeSidebarReturnTarget("section_trending_film", 2), target)
  }

  @Test
  fun elementoNonPiuInRiga_fallbackSullaColonnaClampataDellaStessaRiga() {
    val target = resolveHomeSidebarReturnTarget(
      origin = origin("section_trending_film", colIndex = 9, identity = "sparito", rowOrdinal = 2),
      activeRowKeys = rows,
      rowItemCounts = counts,
      rowIdentities = identities
    )

    assertEquals(HomeSidebarReturnTarget("section_trending_film", 9), target)

    // Riga accorciata: la colonna ricordata viene clampata, mai un indice fuori range.
    val shorterCounts = counts.toMutableMap().apply { put("section_trending_film", 3) }
    val clamped = resolveHomeSidebarReturnTarget(
      origin = origin("section_trending_film", colIndex = 9, identity = "sparito", rowOrdinal = 2),
      activeRowKeys = rows,
      rowItemCounts = shorterCounts,
      rowIdentities = identities
    )
    assertEquals(HomeSidebarReturnTarget("section_trending_film", 2), clamped)
  }

  @Test
  fun rigaSparita_stessoContenutoInAltraRiga_segueLIdentita() {
    val target = resolveHomeSidebarReturnTarget(
      origin = origin("section_scomparsa", colIndex = 4, identity = "m3", rowOrdinal = 1),
      activeRowKeys = rows,
      rowItemCounts = counts,
      rowIdentities = identities
    )

    assertEquals(HomeSidebarReturnTarget("section_trending_film", 3), target)
  }

  @Test
  fun rigaEContenutoSpariti_rigaDisegnataPiuVicina_maiSaltoArbitrario() {
    // Provenienza: terza riga (ordinale 2). Le righe successive alla prima sono ancora
    // disegnate: il fallback resta nella zona di provenienza, non sull'Hero.
    val target = resolveHomeSidebarReturnTarget(
      origin = origin("section_scomparsa", colIndex = 4, identity = "ignoto", rowOrdinal = 2),
      activeRowKeys = rows,
      rowItemCounts = counts,
      rowIdentities = identities
    )

    assertEquals(HomeSidebarReturnTarget("section_trending_film", 0), target)
  }

  @Test
  fun rigaSparitaSotto_siRipiegaSullaRigaSopraNonSullHero() {
    val target = resolveHomeSidebarReturnTarget(
      origin = origin("section_scomparsa", colIndex = 2, identity = null, rowOrdinal = 2),
      activeRowKeys = listOf("hero_banner", "section_providers_hub"),
      rowItemCounts = mapOf("hero_banner" to 1, "section_providers_hub" to 5),
      rowIdentities = identities
    )

    // Ordinale 2 non esiste più: si scende (nessuna riga oltre) e poi si risale alla riga
    // immediatamente sopra la posizione di provenienza (l'hub provider), non all'Hero.
    assertEquals(HomeSidebarReturnTarget("section_providers_hub", 0), target)
  }

  @Test
  fun provenienzaDallHero_ripristinaHero() {
    val target = resolveHomeSidebarReturnTarget(
      origin = origin("hero_banner", colIndex = 0, identity = null, rowOrdinal = 0),
      activeRowKeys = rows,
      rowItemCounts = counts,
      rowIdentities = identities
    )

    assertEquals(HomeSidebarReturnTarget("hero_banner", 0), target)
  }

  @Test
  fun nessunaRigaDisponibile_nessunaDestinazione() {
    assertNull(
      resolveHomeSidebarReturnTarget(
        origin = origin("section_providers_hub", colIndex = 0, identity = "netflix", rowOrdinal = 1),
        activeRowKeys = emptyList(),
        rowItemCounts = emptyMap(),
        rowIdentities = emptyMap()
      )
    )

    assertNull(
      resolveHomeSidebarReturnTarget(
        origin = origin("section_providers_hub", colIndex = 0, identity = "netflix", rowOrdinal = 1),
        activeRowKeys = listOf("hero_banner", "section_providers_hub"),
        rowItemCounts = mapOf("hero_banner" to 0, "section_providers_hub" to 0),
        rowIdentities = emptyMap()
      )
    )
  }

  @Test
  fun rigaVuota_equivaleARigaAssente() {
    val target = resolveHomeSidebarReturnTarget(
      origin = origin("section_trending_film", colIndex = 6, identity = "m6", rowOrdinal = 2),
      activeRowKeys = rows,
      rowItemCounts = counts.toMutableMap().apply { put("section_trending_film", 0) },
      rowIdentities = identities
    )

    assertEquals(HomeSidebarReturnTarget("section_providers_hub", 0), target)
  }

  // ------------------------------------------------------------------ ponte Home <-> sidebar

  @Test
  fun ponteSenzaHomeComposta_nonCatturaENonAccettaRitorni() {
    val link = HomeSidebarFocusLink()

    assertNull(link.captureOrigin())
    assertFalse(link.requestReturn(origin("hero_banner", 0)))
  }

  @Test
  fun ponteInstallaLeLambdaDellaHomeERegistraIlCicloDiVitaDelRitorno() {
    val link = HomeSidebarFocusLink()
    val captured = origin("section_providers_hub", 0, "netflix", 1)
    var handlerCalls = 0

    link.attach(
      originProvider = { captured },
      returnHandler = { requested ->
        handlerCalls++
        assertEquals(captured, requested)
        true
      }
    )

    assertEquals(captured, link.captureOrigin())
    assertTrue(link.requestReturn(captured))
    assertEquals(1, handlerCalls)

    link.detach()
    assertNull(link.captureOrigin())
    assertFalse(link.requestReturn(captured))
  }

  @Test
  fun ponte_conRitornoRifiutato_nonRestaInVolo() {
    val link = HomeSidebarFocusLink()
    link.attach(originProvider = { null }, returnHandler = { false })

    assertFalse(link.requestReturn(origin("hero_banner", 0)))
  }
}
