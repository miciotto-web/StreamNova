package com.example.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasRequestFocusAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.requestFocus
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.HomeCatalogs
import com.example.ui.components.AnchoredRowKeyState
import com.example.ui.components.LocalClearLastFocusedMedia
import com.example.ui.components.LocalFocusRestoreToken
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.LocalLastFocusedTarget
import com.example.ui.components.anchoredSelectionFrameTestTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Criterio di accettazione del "fuoco fisso" nelle file di poster della Home:
 *
 * - premendo RIGHT/LEFT il riquadro di focus resta FERMO nella parte sinistra della fila
 *   (la card focalizzata ha sempre lo stesso `boundsInRoot.left`);
 * - sono i poster a scorrere sotto il riquadro (il focus reale cambia card a ogni pressione);
 * - ai confini il focus non esce dalla fila né si sposta in modo errato;
 * - la navigazione verticale, il ripristino dal Dettaglio e il riordino della lista
 *   continuano a funzionare con l'indice selezionato sempre corrispondente alla card a fuoco.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h900dp-tvdpi")
class AnchoredHomeFocusUiTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val ROW_TRENDING_FILM = "section_trending_film"
    const val ROW_TRENDING_SERIE = "section_trending_serie"
    const val YEAR_TRENDING_FILM = 2001
    const val YEAR_TRENDING_SERIE = 2002
    const val CARDS_PER_ROW = 12
    const val ANCHOR_TOLERANCE_PX = 1.5f
  }

  // ---------------------------------------------------------------- fixture

  private fun film(row: String, index: Int, title: String, year: Int): MediaItem = MediaItem(
    id = "$row-${index + 1}",
    title = title,
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = year,
    tmdbId = index + 1
  )

  private fun row(rowKey: String, prefix: String, year: Int): List<MediaItem> =
    List(CARDS_PER_ROW) { index -> film(rowKey, index, "$prefix ${index + 1}", year) }

  private fun catalogs(trendingMovies: List<MediaItem>? = null): HomeCatalogs = HomeCatalogs(
    heroItems = List(3) { index ->
      MediaItem(
        id = "hero-$index",
        title = "HERO ${index + 1}",
        synopsis = "",
        videoUrl = "",
        type = MediaType.FILM,
        year = 2099,
        tmdbId = 900 + index
      )
    },
    trendingMovies = trendingMovies ?: row(ROW_TRENDING_FILM, "TF", YEAR_TRENDING_FILM),
    trendingSeries = row(ROW_TRENDING_SERIE, "TS", YEAR_TRENDING_SERIE),
    popularMovies = row("section_popolari_film", "PF", 2003),
    popularSeries = row("section_popolari_serie", "PS", 2004)
  )

  private var restoreTarget by mutableStateOf<Pair<String, String>?>(null)
  private var restoreToken by mutableStateOf(0)
  private var liveCatalogs by mutableStateOf<HomeCatalogs?>(null)

  private fun renderHome(catalogs: HomeCatalogs, allMedia: List<MediaItem> = emptyList()) {
    liveCatalogs = catalogs
    rule.setContent {
      CompositionLocalProvider(
        LocalLastFocusedMediaId provides restoreTarget?.second,
        LocalLastFocusedTarget provides restoreTarget,
        LocalFocusRestoreToken provides restoreToken,
        LocalClearLastFocusedMedia provides { restoreTarget = null }
      ) {
        HomeMainBrowsingContent(
          allMedia = allMedia,
          homeCatalogs = liveCatalogs,
          onMediaClick = {},
          onPlayClick = {},
          onRemoveFromContinueWatching = {},
          viewModel = null
        )
      }
    }
    rule.waitForIdle()
  }

  /** Card 16:9 della riga «Continua a guardare»: ordinate per `lastWatchedAt` decrescente. */
  private fun cwItems(count: Int = 8): List<MediaItem> = List(count) { index ->
    MediaItem(
      id = "cw-${index + 1}",
      title = "CW ${index + 1}",
      synopsis = "",
      videoUrl = "",
      type = MediaType.FILM,
      year = 2200 + index,
      tmdbId = 7000 + index,
      currentProgressMs = 10 * 60_000L,
      totalDurationMs = 100 * 60_000L,
      lastWatchedAt = 10_000L - index * 1_000L,
      // Card di «Continua a guardare»: dalla v12 il default del flag è `false`.
      inContinueWatching = true
    )
  }

  // ---------------------------------------------------------------- helper

  private fun collectTexts(node: SemanticsNode): List<String> =
    (node.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()) +
      node.children.flatMap { collectTexts(it) }

  private fun focusedTexts(): List<String> =
    rule.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes()
      .flatMap { collectTexts(it) }

  @OptIn(ExperimentalTestApi::class)
  private fun focusCard(title: String) {
    // La card «Continua a guardare» senza artwork mostra lo stesso titolo anche nel fallback:
    // il nodo da focalizzare è la CARD focalizzabile che contiene quel testo, non i singoli Text.
    rule.onNode(
      hasAnyDescendant(hasText(title)) and hasRequestFocusAction(),
      useUnmergedTree = true
    ).requestFocus()
    rule.waitForIdle()
  }

  @OptIn(ExperimentalTestApi::class)
  private fun pressKey(key: Key, times: Int = 1) {
    repeat(times) {
      rule.onNode(isFocused()).performKeyInput { pressKey(key) }
      rule.waitForIdle()
    }
  }

  @OptIn(ExperimentalTestApi::class)
  private fun assertFocusedCard(title: String, year: Int) {
    rule.waitUntil(timeoutMillis = 5_000) {
      focusedTexts().let { it.contains(title) && it.contains(year.toString()) }
    }
  }

  @OptIn(ExperimentalTestApi::class)
  private fun focusedFrameLeftPx(): Float =
    rule.onNode(isFocused()).fetchSemanticsNode().boundsInRoot.left

  @OptIn(ExperimentalTestApi::class)
  private fun assertFrameAtAnchor(anchorPx: Float, context: String) {
    val current = focusedFrameLeftPx()
    assertEquals(
      "$context: il riquadro di focus si è spostato (card focalizzata a x=$current, ancora a x=$anchorPx)",
      anchorPx.toDouble(),
      current.toDouble(),
      ANCHOR_TOLERANCE_PX.toDouble()
    )
  }

  // ---------------------------------------------------------------- 10 x RIGHT / 10 x LEFT

  @Test
  fun dieciPressioniDestraMantengonoIlRiquadroFermoSullAncora() {
    renderHome(catalogs())
    focusCard("TF 1")
    assertFocusedCard("TF 1", YEAR_TRENDING_FILM)
    val anchorLeft = focusedFrameLeftPx()

    repeat(10) { step ->
      pressKey(Key.DirectionRight)
      assertFocusedCard("TF ${step + 2}", YEAR_TRENDING_FILM)
      assertFrameAtAnchor(anchorLeft, "RIGHT #${step + 1}")
    }
  }

  @Test
  fun dieciPressioniSinistraMantengonoIlRiquadroFermoSullAncora() {
    renderHome(catalogs())
    focusCard("TF 1")
    val anchorLeft = focusedFrameLeftPx()
    pressKey(Key.DirectionRight, times = 10)
    assertFocusedCard("TF 11", YEAR_TRENDING_FILM)

    repeat(10) { step ->
      pressKey(Key.DirectionLeft)
      assertFocusedCard("TF ${10 - step}", YEAR_TRENDING_FILM)
      assertFrameAtAnchor(anchorLeft, "LEFT #${step + 1}")
    }
    assertFocusedCard("TF 1", YEAR_TRENDING_FILM)
  }

  // ---------------------------------------------------------------- confini

  @Test
  fun laRigaContinuaAGuardareUsaLoStessoFuocoAncorato() {
    renderHome(catalogs(), allMedia = cwItems())
    focusCard("CW 1")
    assertFocusedCard("CW 1", 2200)
    val anchorLeft = focusedFrameLeftPx()

    repeat(4) { step ->
      pressKey(Key.DirectionRight)
      assertFocusedCard("CW ${step + 2}", 2201 + step)
      assertFrameAtAnchor(anchorLeft, "CW RIGHT #${step + 1}")
    }

    repeat(4) { step ->
      pressKey(Key.DirectionLeft)
      assertFocusedCard("CW ${4 - step}", 2199 + (4 - step))
      assertFrameAtAnchor(anchorLeft, "CW LEFT #${step + 1}")
    }
  }

  @Test
  fun sinistraSullaPrimaCardNonSpostaIlFocus() {
    renderHome(catalogs())
    focusCard("TF 1")
    val anchorLeft = focusedFrameLeftPx()

    pressKey(Key.DirectionLeft)

    assertFocusedCard("TF 1", YEAR_TRENDING_FILM)
    assertFrameAtAnchor(anchorLeft, "LEFT sul primo poster")
  }

  @Test
  fun destraSullUltimaCardNonEsceDallaFila() {
    renderHome(catalogs())
    focusCard("TF 1")
    val anchorLeft = focusedFrameLeftPx()
    pressKey(Key.DirectionRight, times = CARDS_PER_ROW - 1)
    assertFocusedCard("TF $CARDS_PER_ROW", YEAR_TRENDING_FILM)

    pressKey(Key.DirectionRight)

    assertFocusedCard("TF $CARDS_PER_ROW", YEAR_TRENDING_FILM)
    assertFrameAtAnchor(anchorLeft, "RIGHT sull'ultimo poster")
  }

  // ---------------------------------------------------------------- protezione ripetuti

  @Test
  fun duePressioniRavvicinateDuranteLAnimazioneProduconoUnSoloStep() {
    renderHome(catalogs())
    focusCard("TF 1")
    val anchorLeft = focusedFrameLeftPx()

    // Due RIGHT nello stesso blocco: la seconda arriva mentre il primo step è in volo.
    rule.onNode(isFocused()).performKeyInput {
      pressKey(Key.DirectionRight)
      pressKey(Key.DirectionRight)
    }
    rule.waitForIdle()

    assertFocusedCard("TF 2", YEAR_TRENDING_FILM)
    assertFrameAtAnchor(anchorLeft, "doppio RIGHT ravvicinato")

    // La riga resta viva: il passo successivo è ammesso.
    pressKey(Key.DirectionRight)
    assertFocusedCard("TF 3", YEAR_TRENDING_FILM)
    assertFrameAtAnchor(anchorLeft, "RIGHT dopo il doppio ravvicinato")
  }

  @Test
  fun ilRiquadroAncoratoRestaFermoDuranteLAnimazione() {
    renderHome(catalogs())
    focusCard("TF 1")
    val frameTag = anchoredSelectionFrameTestTag(ROW_TRENDING_FILM)
    // L'ancora delle righe poster coincide con il content padding iniziale (32dp): il
    // riquadro deve essere disegnato lì, non sulla posizione corrente della card.
    val expectedAnchorPx = with(rule.density) { 32.dp.toPx() }
    val expectedWidthPx = with(rule.density) { 150.dp.toPx() }

    rule.mainClock.autoAdvance = false
    rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
    rule.mainClock.advanceTimeByFrame()
    rule.waitForIdle()
    repeat(2) { rule.mainClock.advanceTimeByFrame() }
    rule.mainClock.advanceTimeBy(80)

    // Overlay composto durante lo step: geometria FISSA sull'ancora, indipendente dalla card
    // che sta scorrendo sotto di esso.
    val overlay = rule.onNodeWithTag(frameTag, useUnmergedTree = true).fetchSemanticsNode()
    assertEquals(
      "il riquadro ancorato non è sull'ancora sinistra della fila",
      expectedAnchorPx.toDouble(),
      overlay.boundsInRoot.left.toDouble(),
      2.0
    )
    assertEquals(
      "il riquadro ancorato non ha la geometria del poster",
      expectedWidthPx.toDouble(),
      overlay.boundsInRoot.width.toDouble(),
      2.0
    )

    rule.mainClock.autoAdvance = true
    rule.waitForIdle()
    assertFocusedCard("TF 2", YEAR_TRENDING_FILM)
    rule.onNodeWithTag(frameTag, useUnmergedTree = true).assertDoesNotExist()
  }

  // ---------------------------------------------------------------- navigazione verticale

  @Test
  fun laNavigazioneVerticaleRestaFunzionanteDopoGliStepAncorati() {
    renderHome(catalogs())
    focusCard("TF 1")
    val anchorLeft = focusedFrameLeftPx()
    pressKey(Key.DirectionRight, times = 5)
    assertFocusedCard("TF 6", YEAR_TRENDING_FILM)

    pressKey(Key.DirectionDown)
    rule.waitUntil(timeoutMillis = 5_000) { focusedTexts().contains(YEAR_TRENDING_SERIE.toString()) }
    assertTrue(
      "il Giù deve restare nella riga adiacente (Serie TV)",
      focusedTexts().any { it.startsWith("TS ") }
    )

    pressKey(Key.DirectionUp)
    rule.waitUntil(timeoutMillis = 5_000) { focusedTexts().contains(YEAR_TRENDING_FILM.toString()) }
    assertTrue(
      "il Su deve tornare nella riga Film, sulla card selezionata dall'ancora",
      focusedTexts().any { it.startsWith("TF ") }
    )
    assertFrameAtAnchor(anchorLeft, "rientro verticale nella riga Film")
  }

  // ---------------------------------------------------------------- ripristino e ricomposizione

  @Test
  fun ilRipristinoDalDettaglioPortaLaCardSalvataSullAncora() {
    renderHome(catalogs())
    focusCard("TF 1")
    val anchorLeft = focusedFrameLeftPx()

    restoreTarget = ROW_TRENDING_FILM to "section_trending_film-8"
    restoreToken = 1

    assertFocusedCard("TF 8", YEAR_TRENDING_FILM)
    assertFrameAtAnchor(anchorLeft, "ripristino dal Dettaglio")
  }

  @Test
  fun ilRiordinoDellaListaMantieneSelezionataLaStessaCard() {
    val base = catalogs()
    renderHome(base)
    focusCard("TF 1")
    val anchorLeft = focusedFrameLeftPx()
    pressKey(Key.DirectionRight, times = 2)
    assertFocusedCard("TF 3", YEAR_TRENDING_FILM)

    // Ondata catalogo: TF 3 (stesso id) passa alla prima posizione.
    val moved = base.trendingMovies.first { it.id == "section_trending_film-3" }
    liveCatalogs = base.copy(trendingMovies = listOf(moved) + (base.trendingMovies - moved))
    rule.waitForIdle()

    assertFocusedCard("TF 3", YEAR_TRENDING_FILM)
    assertFrameAtAnchor(anchorLeft, "riordino della lista")
  }

  @Test
  fun loStatoDelLeftSegueLaRigaAncorataCheHaIlFocus() {
    renderHome(catalogs())
    focusCard("TF 1")
    assertFalse("sulla prima card il LEFT deve restare alla navigazione laterale", AnchoredRowKeyState.consumesLeftKey)

    pressKey(Key.DirectionRight)
    assertTrue("oltre la prima card il LEFT appartiene alla riga", AnchoredRowKeyState.consumesLeftKey)

    // Giù in un'altra riga ancorata: lo stato globale passa alla riga di destinazione.
    pressKey(Key.DirectionDown)
    rule.waitUntil(timeoutMillis = 5_000) {
      AnchoredRowKeyState.focusedRowKeyForTest == ROW_TRENDING_SERIE
    }
    assertTrue(AnchoredRowKeyState.consumesLeftKey)

    // Risalita fuori dalle righe ancorate (Film -> hub provider): la riga non deve restare
    // registrata, altrimenti il LEFT non tornerebbe più alla navigazione laterale.
    pressKey(Key.DirectionUp)
    pressKey(Key.DirectionUp)
    rule.waitUntil(timeoutMillis = 5_000) { !AnchoredRowKeyState.hasFocusedRow }
    assertFalse(
      "con il focus fuori dalle righe ancorate il LEFT deve tornare alla navigazione laterale",
      AnchoredRowKeyState.hasFocusedRow
    )
  }
}
