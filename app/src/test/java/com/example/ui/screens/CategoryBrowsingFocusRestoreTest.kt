package com.example.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.ui.components.FOCUS_ROW_ANY
import com.example.ui.components.LocalClearLastFocusedMedia
import com.example.ui.components.LocalFocusRestoreToken
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.LocalLastFocusedTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import kotlin.math.abs
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ripristino del focus delle sezioni Film e Serie TV ([CategoryBrowsingScreen]) al BACK dal
 * Dettaglio, sulla schermata REALE sotto Robolectric.
 *
 * Difetto coperto (osservato in emulatore): da qualsiasi card della collezione si apra il
 * Dettaglio e si torni indietro, il focus finiva SEMPRE sulla prima card della prima riga.
 * Due cause distinte, entrambe esercitate qui:
 * - l'identità della card non veniva salvata all'apertura del Dettaglio (nessun target di
 *   ripristino, quindi il focus cadeva sulla prima cella disponibile);
 * - il FocusRequester del ripristino era agganciato alla cella 0 della griglia, quindi anche
 *   con il target corretto il focus atterrava sempre sulla prima card.
 *
 * Il round-trip è riprodotto come il NavHost: la composizione viene smontata e rimontata
 * (SaveableStateProvider) mentre il catalogo di test viene riordinato, come fa l'osservatore
 * Room di `MediaRepository` (`mapEntitiesToUiState`: `sortedByDescending { rating }`) quando i
 * dettagli arricchiti dal Dettaglio vengono persistiti: senza ordine congelato la card
 * ripristinata cambiava riga/posizione visiva ad ogni rientro.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class CategoryBrowsingFocusRestoreTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val TITLE = "Tutti i film"
  }

  private fun item(index: Int, type: MediaType = MediaType.FILM): MediaItem = MediaItem(
    id = "m%02d".format(index + 1),
    title = "Titolo %02d".format(index + 1),
    synopsis = "",
    videoUrl = "",
    type = type,
    year = 2000 + index,
    tmdbId = index + 1,
    rating = 10f - index * 0.1f
  )

  // ------------------------------------------------------------- harness

  private var showDetail by mutableStateOf(false)
  private var restoreTarget by mutableStateOf<String?>(null)
  private var restoreToken by mutableStateOf(0)
  private var liveItems by mutableStateOf<List<MediaItem>>(emptyList())

  private fun render(items: List<MediaItem>, isTv: Boolean = false) {
    liveItems = items
    rule.setContent {
      val stateHolder = rememberSaveableStateHolder()
      // Stesso contratto di MainActivity al ritorno dal Dettaglio: target row-scoped
      // (FOCUS_ROW_ANY: la griglia è una superficie unica di focus) + token dell'evento.
      // Come il NavHost: ogni destinazione ha il SUO SaveableStateProvider, che salva lo stato
      // quando la destinazione viene smontata e lo ripristina al ritorno.
      CompositionLocalProvider(
        LocalLastFocusedMediaId provides restoreTarget,
        LocalLastFocusedTarget provides restoreTarget?.let { FOCUS_ROW_ANY to it },
        LocalFocusRestoreToken provides restoreToken,
        LocalClearLastFocusedMedia provides { restoreTarget = null }
      ) {
        if (showDetail) {
          stateHolder.SaveableStateProvider("detail") {
            Box(modifier = Modifier.fillMaxSize()) { Text("DETTAGLIO") }
          }
        } else {
          stateHolder.SaveableStateProvider("category_browsing") {
            CategoryBrowsingScreen(
              title = TITLE,
              items = liveItems,
              isTv = isTv,
              viewModel = null,
              onMediaClick = {},
              onLoadMore = null
            )
          }
        }
      }
    }
    rule.waitForIdle()
    // Warm-up del round-trip: la PRIMA composizione misura i testi con le metriche non ancora
    // stabilizzate e le righe della griglia risultano alte qualche px in più. Un ciclo di
    // smontaggio/rimontaggio allinea le misure, così le asserzioni sulle coordinate possono
    // restare esatte (1px) invece di assorbire l'artefatto nella tolleranza.
    openDetailAndReturn(targetId = null)
    rule.onNode(hasText(TITLE)).assertExists()
    rule.waitForIdle()
  }

  /**
   * Round-trip Categoria -> Dettaglio -> BACK: smonta e rimonta la schermata (come il
   * NavHost), salva il target come fa il click e avanza il token di ripristino come fa
   * `signalFocusRestore`. [churn] simula gli aggiornamenti di catalogo avvenuti mentre il
   * Dettaglio era aperto.
   */
  private fun openDetailAndReturn(
    targetId: String?,
    churn: (List<MediaItem>) -> List<MediaItem> = { it }
  ) {
    showDetail = true
    rule.waitForIdle()
    restoreTarget = targetId
    restoreToken += 1
    liveItems = churn(liveItems)
    showDetail = false
    rule.waitForIdle()
  }

  /** Riordino realistico del catalogo: il rating del titolo aperto cambia e la lista esce
   *  riordinata per rating (stessa sequenza di `mapEntitiesToUiState`). */
  private fun reorderCatalog(shiftingId: String): (List<MediaItem>) -> List<MediaItem> = { list ->
    list.map { if (it.id == shiftingId) it.copy(rating = it.rating - 0.15f) else it }
      .sortedByDescending { it.rating }
  }

  private fun focusedText(): String? = try {
    rule.onNode(isFocused()).fetchSemanticsNode().config
      .getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
  } catch (_: Throwable) {
    null
  }

  private fun boundsOf(title: String): Rect? = try {
    rule.onNode(hasText(title)).fetchSemanticsNode().boundsInRoot
  } catch (_: Throwable) {
    null
  }

  /** Card che condividono la stessa banda verticale: la stessa riga della griglia.
   *  Si confrontano i CENTRI: la card focalizzata è disegnata con scala 1.06 (grafica, non
   *  layout) e i suoi bound esterni si allargano, ma il centro resta quello di layout. */
  private fun rowMatesOf(title: String): Set<String> {
    val center = boundsOf(title)?.center?.y ?: return emptySet()
    return (1..18).map { "Titolo %02d".format(it) }
      .filter { other -> boundsOf(other)?.center?.y?.let { abs(it - center) <= 2f } == true }
      .toSet()
  }

  @OptIn(ExperimentalTestApi::class)
  private fun focusCard(title: String) {
    rule.onNode(hasText(title)).requestFocus()
    rule.waitForIdle()
  }

  /** Attende che il focus sia DAVVERO sulla card attesa (titolo del nodo focalizzato). */
  private fun assertFocusedCard(title: String) {
    rule.waitUntil(timeoutMillis = 5_000) { focusedText()?.startsWith(title) == true }
    assertTrue(
      "Il focus non è su $title ma su ${focusedText()}",
      focusedText()?.startsWith(title) == true
    )
  }

  // ------------------------------------------------------------- 1. Film

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun filmRipristinoSullaTerzaESullaQuartaCardStessaRiga() {
    render(List(18) { item(it) })

    // Terza card della collezione (indice 2).
    focusCard("Titolo 03")
    val thirdBefore = boundsOf("Titolo 03")
    assertNotNull(thirdBefore)
    openDetailAndReturn(targetId = "m03", churn = reorderCatalog("m03"))
    assertFocusedCard("Titolo 03")
    val thirdAfter = boundsOf("Titolo 03")
    assertNotNull(thirdAfter)
    assertEquals(
      "La terza card ripristinata non è più nella stessa riga",
      thirdBefore!!.top.toDouble(), thirdAfter!!.top.toDouble(), 1.0
    )
    assertEquals(
      "La terza card ripristinata non è più nella stessa posizione",
      thirdBefore.left.toDouble(), thirdAfter.left.toDouble(), 1.0
    )

    // Quarta card della collezione (indice 3): ritorno consecutivo su un'altra card.
    focusCard("Titolo 04")
    val fourthBefore = boundsOf("Titolo 04")
    openDetailAndReturn(targetId = "m04", churn = reorderCatalog("m04"))
    assertFocusedCard("Titolo 04")
    val fourthAfter = boundsOf("Titolo 04")
    assertEquals(
      "La quarta card ripristinata non è più nella stessa riga",
      fourthBefore!!.top.toDouble(), fourthAfter!!.top.toDouble(), 1.0
    )
    assertEquals(
      "La quarta card ripristinata non è più nella stessa posizione",
      fourthBefore.left.toDouble(), fourthAfter.left.toDouble(), 1.0
    )
  }

  // ------------------------------------------------------------- 2. Serie TV

  @Test
  fun serieTvRipristinoSuCardNonInizialeStessaRiga() {
    render(List(18) { item(it, type = MediaType.SERIE_TV) }, isTv = true)

    // Card non iniziale (indice 7, seconda riga della griglia).
    focusCard("Titolo 08")
    val before = boundsOf("Titolo 08")
    assertNotNull(before)
    val beforeRowMates = rowMatesOf("Titolo 08")

    openDetailAndReturn(targetId = "m08", churn = reorderCatalog("m08"))

    assertFocusedCard("Titolo 08")
    val after = boundsOf("Titolo 08")
    assertNotNull(after)
    // Stessa riga: la card condivide la banda verticale con le STESSE card di prima. Il
    // confronto è relativo: le metriche di misura del testo possono far variare di qualche px
    // l'altezza della riga tra composizioni (artefatto di misura dell'harness), mai la
    // composizione della riga stessa.
    assertTrue(
      "La riga di partenza non contiene le card attese (misura non valida)",
      beforeRowMates.containsAll(listOf("Titolo 08", "Titolo 09", "Titolo 10"))
    )
    assertEquals(
      "La card Serie TV ripristinata non è più nella stessa riga",
      beforeRowMates, rowMatesOf("Titolo 08")
    )
    assertEquals(
      "La card Serie TV ripristinata non è più nella stessa posizione",
      before!!.left.toDouble(), after!!.left.toDouble(), 1.0
    )
  }

  // ------------------------------------------------------------- 3. ripristini consecutivi

  @Test
  fun ripristiniConsecutiviSuCardDiverse() {
    render(List(18) { item(it) })

    listOf("m02" to "Titolo 02", "m06" to "Titolo 06", "m11" to "Titolo 11", "m03" to "Titolo 03")
      .forEach { (id, title) ->
        openDetailAndReturn(targetId = id)
        assertFocusedCard(title)
      }
  }

  // ------------------------------------------------------------- 6. target non disponibile

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun targetNonPiuDisponibileNessunSaltoAllaPrimaCard() {
    render(List(30) { item(it) })

    // La griglia arriva su una card profonda (indice 23, fuori dal viewport iniziale): il
    // ripristino la raggiunge con lo scroll.
    openDetailAndReturn(targetId = "m24")
    assertFocusedCard("Titolo 24")
    assertNull(
      "Il ripristino non ha spostato la griglia: la card profonda non era raggiungibile",
      boundsOf("Titolo 01")
    )

    // Al ritorno successivo il titolo salvato non esiste più nel catalogo.
    openDetailAndReturn(targetId = "m999")

    // Comportamento sicuro: nessun salto silenzioso alla prima card, la sezione resta quella
    // corrente e la posizione raggiunta della griglia non viene azzerata.
    rule.waitUntil(timeoutMillis = 5_000) { focusedText() != null }
    val focused = focusedText()
    assertNotNull("La griglia è rimasta senza alcun focus dopo un ripristino fallito", focused)
    assertTrue(
      "Il ripristino fallito è saltato silenziosamente alla prima card ($focused)",
      focused?.startsWith("Titolo 01") != true
    )
    assertNull(
      "La griglia è stata riportata all'inizio invece di conservare la posizione",
      boundsOf("Titolo 01")
    )

    // La griglia resta navigabile con il D-pad.
    val before = focusedText()
    rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
    rule.waitForIdle()
    assertTrue(
      "D-pad morto dopo un ripristino fallito",
      focusedText() != null && focusedText() != before
    )
  }

  // ------------------------------------------------------------- salvataggio dell'identità

  @Test
  fun ilClickSullaCardSalvaIlTargetPrimaDiAprireIlDettaglio() {
    val viewModel = com.example.ui.viewmodel.StreamNovaViewModel(
      ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
    )
    var clickedId: String? = null
    rule.setContent {
      CategoryBrowsingScreen(
        title = TITLE,
        items = List(6) { item(it) },
        isTv = false,
        viewModel = viewModel,
        onMediaClick = { clickedId = it.id }
      )
    }
    rule.waitForIdle()

    rule.onNode(hasText("Titolo 04")).performClick()
    rule.waitForIdle()

    assertEquals("Il click non è arrivato al navigatore", "m04", clickedId)
    assertEquals(
      "Il target di ripristino non è stato salvato al click",
      FOCUS_ROW_ANY to "m04",
      viewModel.lastFocusedTarget.value
    )
    assertEquals("m04", viewModel.lastFocusedMediaId.value)
  }
}
