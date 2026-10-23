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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.requestFocus
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.ui.components.FOCUS_ROW_ANY
import com.example.ui.components.LocalClearLastFocusedMedia
import com.example.ui.components.LocalFocusRestoreToken
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.LocalLastFocusedTarget
import com.example.ui.components.ProviderConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stabilità della POSIZIONE VISIVA della griglia Provider ai ripetuti ritorni dal Dettaglio,
 * sulla schermata REALE ([ProviderScreen]) sotto Robolectric.
 *
 * Difetto coperto (osservato in emulatore): il focus tornava sulla card corretta, ma la sua
 * posizione visiva cambiava ad ogni rientro.
 *
 * Le tre grandezze vengono distinte, come richiesto dall'analisi:
 * - posizione del focus: ripristinata per identità (protocollo `confirmFocusRestore`), e qui
 *   verificata ad ogni ciclo;
 * - indice della card: è ciò che cambiava — il catalogo di origine viene ricostruito mentre il
 *   Dettaglio è aperto (i dettagli arricchiti vengono persistiti in Room e l'osservatore del
 *   DAO riemette `allMedia` riordinato per rating, `mapEntitiesToUiState`), e ogni
 *   ricostruzione ricollocava la card già a schermo;
 * - scroll state della griglia: preservato (`rememberSaveable` + SaveableStateProvider, come
 *   nel NavHost) e verificato dalle coordinate della card.
 *
 * Il round-trip è riprodotto come il NavHost: la composizione viene smontata e rimontata
 * mentre il catalogo di test viene riordinato.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class ProviderFocusRestoreStabilityTest {

  @get:Rule
  val rule = createComposeRule()

  private fun item(index: Int): MediaItem = MediaItem(
    id = "m%02d".format(index + 1),
    title = "Titolo %02d".format(index + 1),
    synopsis = "",
    videoUrl = "",
    type = if (index % 2 == 0) MediaType.FILM else MediaType.SERIE_TV,
    year = 2000 + index,
    tmdbId = index + 1,
    rating = 10f - index * 0.1f,
    provider = "netflix"
  )

  // ------------------------------------------------------------- harness

  private var showDetail by mutableStateOf(false)
  private var restoreTarget by mutableStateOf<String?>(null)
  private var restoreToken by mutableStateOf(0)
  private var liveItems by mutableStateOf<List<MediaItem>>(emptyList())

  private fun render(items: List<MediaItem>) {
    liveItems = items
    rule.setContent {
      val stateHolder = rememberSaveableStateHolder()
      // Stesso contratto di MainActivity: target row-scoped (FOCUS_ROW_ANY) + token dell'evento.
      // Come il NavHost: ogni destinazione ha il SUO SaveableStateProvider.
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
          stateHolder.SaveableStateProvider("provider") {
            ProviderScreen(
              provider = ProviderConstants.NETFLIX,
              allMedia = liveItems,
              onBackClick = {},
              onMediaClick = {},
              viewModel = null
            )
          }
        }
      }
    }
    rule.waitForIdle()
    // Warm-up del round-trip: la PRIMA composizione misura i testi con le metriche non ancora
    // stabilizzate. Un ciclo di smontaggio/rimontaggio allinea le misure, così le asserzioni
    // sulle coordinate possono restare esatte (1px).
    openDetailAndReturn(targetId = null)
    rule.waitForIdle()
  }

  /** Round-trip Provider -> Dettaglio -> BACK, con il catalogo che viene riordinato (come fa
   *  l'osservatore Room quando i dettagli arricchiti vengono persistiti). */
  private fun openDetailAndReturn(targetId: String?, churn: (List<MediaItem>) -> List<MediaItem> = { it }) {
    showDetail = true
    rule.waitForIdle()
    restoreTarget = targetId
    restoreToken += 1
    liveItems = churn(liveItems)
    showDetail = false
    rule.waitForIdle()
  }

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

  @OptIn(ExperimentalTestApi::class)
  private fun focusCard(title: String) {
    rule.onNode(hasText(title)).requestFocus()
    rule.waitForIdle()
  }

  private fun assertFocusedCard(title: String) {
    rule.waitUntil(timeoutMillis = 5_000) { focusedText()?.startsWith(title) == true }
    assertTrue(
      "Il focus non è tornato su $title ma su ${focusedText()}",
      focusedText()?.startsWith(title) == true
    )
  }

  // ------------------------------------------------------------- 4. stabilità posizione

  @Test
  fun ripetutiRitorniDalDettaglioNonVarianoLaPosizioneVisivaDellaCard() {
    render(List(30) { item(it) })

    val target = "Titolo 05"
    focusCard(target)
    val before = boundsOf(target)
    assertNotNull("La card di partenza non è visibile", before)

    // Ritorni consecutivi dalla STESSA card: ad ogni apertura il catalogo esce riordinato
    // (rating aggiornato dal Dettaglio), ma la card deve restare nella stessa posizione visiva.
    repeat(4) { cycle ->
      openDetailAndReturn(targetId = "m05", churn = reorderCatalog("m05"))

      assertFocusedCard(target)
      val after = boundsOf(target)
      assertNotNull("La card ripristinata non è più visibile al ciclo ${cycle + 1}", after)
      assertEquals(
        "Posizione verticale della card variata al ciclo ${cycle + 1}",
        before!!.top.toDouble(), after!!.top.toDouble(), 1.0
      )
      assertEquals(
        "Posizione orizzontale della card variata al ciclo ${cycle + 1}",
        before.left.toDouble(), after.left.toDouble(), 1.0
      )
    }
  }

  @Test
  fun ripristinoSuCardDiverseMantieneLaPosizioneDiCiascuna() {
    render(List(30) { item(it) })

    listOf("m03" to "Titolo 03", "m07" to "Titolo 07", "m03" to "Titolo 03").forEach { (id, title) ->
      focusCard(title)
      val before = boundsOf(title)
      openDetailAndReturn(targetId = id, churn = reorderCatalog(id))
      assertFocusedCard(title)
      val after = boundsOf(title)
      assertEquals(
        "Posizione verticale variata per $title",
        before!!.top.toDouble(), after!!.top.toDouble(), 1.0
      )
      assertEquals(
        "Posizione orizzontale variata per $title",
        before.left.toDouble(), after.left.toDouble(), 1.0
      )
    }
  }
}
