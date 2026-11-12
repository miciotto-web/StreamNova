package com.example.ui.screens

import com.example.ui.components.HomeFocusOrigin
import com.example.ui.components.HomeSidebarReturnTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ritorno dalla sidebar sul percorso AUTOREVOLE della Home
 * ([HomeRowFocusCoordinator.requestReturnToOrigin]).
 *
 * Verifica che la destinazione pubblicata come richiesta pendente (la stessa che
 * `HomeVerticalFocusSync` trasforma in focus reale) sia quella dell'elemento di provenienza:
 * identità prima della posizione, fallback controllati nella stessa zona della Home e mai un
 * salto arbitrario all'Hero quando l'elemento non esiste più.
 */
class HomeRowReturnToOriginTest {

  private val rows = listOf("hero_banner", "section_providers_hub", "section_trending_film")

  private val counts = mapOf(
    "hero_banner" to 1,
    "section_providers_hub" to 5,
    "section_trending_film" to 12
  )

  private val identities = mapOf(
    "hero_banner" to emptyList<String>(),
    "section_providers_hub" to listOf("netflix", "hbo", "disney", "prime", "apple"),
    "section_trending_film" to List(12) { "m$it" }
  )

  private fun coordinator() = HomeRowFocusCoordinator(rows, counts, identities)

  private fun origin(
    rowKey: String,
    colIndex: Int,
    identity: String? = null,
    rowOrdinal: Int = rows.indexOf(rowKey)
  ) = HomeFocusOrigin(
    rowKey = rowKey,
    colIndex = colIndex,
    identity = identity,
    cardKey = null,
    rowOrdinal = rowOrdinal,
    listFirstVisibleIndex = 0,
    listFirstVisibleOffset = 0
  )

  @Test
  fun rigaPiattaforme_richiestaPendenteSulloStessoProvider() {
    val c = coordinator()

    val target = c.requestReturnToOrigin(
      origin("section_providers_hub", colIndex = 0, identity = "netflix")
    )

    assertEquals(HomeSidebarReturnTarget("section_providers_hub", 0), target)
    assertEquals("section_providers_hub" to 0, c.pendingFocusRequest)
    // L'identità accompagna la richiesta: una ondata dati può ancora riallinearla.
    assertEquals("netflix", c.pendingFocusIdentity)
  }

  @Test
  fun elementoCentraleDellaRiga_richiestaPendenteSulSuoIndice() {
    val c = coordinator()

    c.requestReturnToOrigin(origin("section_providers_hub", colIndex = 2, identity = "disney"))

    assertEquals("section_providers_hub" to 2, c.pendingFocusRequest)
  }

  @Test
  fun catalogoRiordinato_laRichiestaSegueLIdentitaNonLIndice() {
    val c = coordinator()

    // La riga si riordina mentre la richiesta è in volo.
    c.requestReturnToOrigin(origin("section_trending_film", colIndex = 3, identity = "m3"))
    c.updateRows(
      rows,
      counts,
      identities.toMutableMap().apply {
        put("section_trending_film", List(12) { "m${(it + 4) % 12}" })
      }
    )

    // "m3" è ora in colonna 11: la richiesta è stata riallineata al contenuto.
    assertEquals("section_trending_film" to 11, c.pendingFocusRequest)
  }

  @Test
  fun elementoNonPiuPresente_fallbackSullaColonnaClampataDellaStessaRiga() {
    val c = coordinator()

    c.requestReturnToOrigin(origin("section_trending_film", colIndex = 9, identity = "sparito"))

    assertEquals("section_trending_film" to 9, c.pendingFocusRequest)

    // Riga accorciata: clamp all'ultimo indice valido, sempre nella stessa riga.
    c.updateRows(rows, counts.toMutableMap().apply { put("section_trending_film", 4) }, identities)
    assertEquals("section_trending_film" to 3, c.pendingFocusRequest)
  }

  @Test
  fun rigaSparita_stessoContenutoInAltraRiga_richiestaSullAltraRiga() {
    val c = coordinator()
    c.updateRows(
      listOf("hero_banner", "section_for_you_film"),
      mapOf("hero_banner" to 1, "section_for_you_film" to 12),
      mapOf("hero_banner" to emptyList(), "section_for_you_film" to identities.getValue("section_trending_film"))
    )

    c.requestReturnToOrigin(origin("section_trending_film", colIndex = 5, identity = "m5", rowOrdinal = 1))

    assertEquals("section_for_you_film" to 5, c.pendingFocusRequest)
  }

  @Test
  fun contenutoETrigaSpariti_fallbackSullaRigaPiuVicinaNonSullHero() {
    val c = coordinator()
    c.updateRows(
      listOf("hero_banner", "section_popular_film"),
      mapOf("hero_banner" to 1, "section_popular_film" to 8),
      mapOf("hero_banner" to emptyList(), "section_popular_film" to List(8) { "p$it" })
    )

    // Provenienza da una riga che non esiste più, in una posizione NON iniziale.
    c.requestReturnToOrigin(origin("section_scomparsa", colIndex = 3, identity = "ignoto", rowOrdinal = 2))

    assertEquals("section_popular_film" to 0, c.pendingFocusRequest)
  }

  @Test
  fun homeSenzaRighe_nessunRitorno() {
    val c = HomeRowFocusCoordinator(
      emptyList(),
      emptyMap(),
      emptyMap()
    )

    assertNull(c.requestReturnToOrigin(origin("section_providers_hub", 0, "netflix", 0)))
    assertNull(c.pendingFocusRequest)
  }

  @Test
  fun cellaMontata_ilRitornoUsaIlRequesterDellaCella() {
    val c = coordinator()
    c.registerCell("section_providers_hub", 2)
    val requester = c.getRequester("section_providers_hub", 2)

    // Senza un FocusOwner (test JVM) `requestFocus` solleva: il coordinatore lo assorbe e
    // lascia comunque la richiesta pendente, così il protocollo può completarla.
    val target = c.requestReturnToOrigin(origin("section_providers_hub", 2, "disney"))

    assertEquals(HomeSidebarReturnTarget("section_providers_hub", 2), target)
    assertEquals(requester, c.getRequester("section_providers_hub", 2))
    assertEquals("section_providers_hub" to 2, c.pendingFocusRequest)
  }
}
