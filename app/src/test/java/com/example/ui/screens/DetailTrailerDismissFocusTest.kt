package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.ui.components.TvFocusableBox
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ritorno dal TRAILER interno al Dettaglio.
 *
 * Difetto coperto: il trailer è un overlay di MainActivity composto SOPRA il NavHost,
 * quindi [DetailScreen] resta composto sotto di esso e NESSUNO dei suoi effetti di focus
 * ri-parte alla chiusura. Smontando il nodo focalizzato del trailer (il pulsante
 * «Chiudi»), il fallback di focus di Compose atterrava sulla SINOSSI (primo nodo
 * focusabile della scheda) invece che sul pulsante «Riproduci».
 *
 * Meccanismo reale riprodotto dall'harness:
 *  - la scheda parte con il focus su «Riproduci», poi D-pad destro fino al pulsante
 *    Trailer (due passaggi: Cuore, Occhio; il flag `hasUserNavigatedAway` è quindi true,
 *    come nel caso reale segnalato);
 *  - OK apre uno stub fedele dell'overlay [TrailerPlayerScreen] che SI PRENDE il focus
 *    sul pulsante «Chiudi» come l'originale;
 *  - la chiusura simula `closeTrailer()`: overlay smontato E token
 *    `trailerDismissedToken` avanzato nella STESSA snapshot.
 *
 * Copre inoltre la non-regressione della Fase 4 (chiusura del selettore sorgenti) e la
 * non interferenza dei due token, oltre al caso di un token ereditato all'ingresso in
 * composizione (che non deve strappare il focus al ripristino del cast).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class DetailTrailerDismissFocusTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val SELECT_SOURCE_LABEL_IT = "Seleziona fonte"
    const val SELECT_SOURCE_LABEL_EN = "Select source"
    const val PLAY_LABEL_IT = "Riproduci"
    const val PLAY_LABEL_EN = "Play"
    const val SYNOPSIS_TEXT = "Sinossi della scheda, troncata a quattro righe."
    const val CAST_ACTOR = "Anna Rossi"
    const val STUB_TRAILER_CLOSE = "stub_trailer_close"
    const val STUB_CHIP_ALL = "stub_source_chip_all"
    const val STUB_CHIP_CANCEL = "stub_source_chip_cancel"
  }

  // Stato dell'harness: gli overlay e i token di chiusura, esattamente come MainActivity
  // osserva `trailerState`/`showSourceDialog` e i rispettivi token dal ViewModel.
  private var showTrailerOverlay by mutableStateOf(false)
  private var trailerDismissedToken by mutableIntStateOf(0)
  private var showSourceOverlay by mutableStateOf(false)
  private var sourceDialogDismissedToken by mutableIntStateOf(0)
  private var targetCastMemberId by mutableStateOf<Int?>(null)

  private var trailerOpens = 0
  private var playClicks = 0

  /**
   * Nel ritorno reale da PersonDetail il flag `hasUserNavigatedAway` è true (saveable) e
   * l'effetto di focus iniziale non ri-parte quando il target del cast viene consumato.
   * Nell'harness invece parte da false: per riprodurre fedelmente quel flusso il target
   * NON viene consumato (`onClearTargetCastMember = null`), così nessun altro effetto
   * rincorre il ripristino del cast.
   */
  private var clearCastTargetOnRestore = true

  private fun movie(withCast: Boolean = false, withSynopsis: Boolean = true) = MediaItem(
    id = "tt0117731",
    title = "Toy Story",
    synopsis = if (withSynopsis) SYNOPSIS_TEXT else "",
    videoUrl = "",
    type = MediaType.FILM,
    year = 1995,
    tmdbId = 862,
    cast = if (withCast) listOf(CAST_ACTOR) else emptyList()
  )

  private fun series(withSynopsis: Boolean = true) = MediaItem(
    id = "tt0944947",
    title = "Game of Thrones",
    synopsis = if (withSynopsis) SYNOPSIS_TEXT else "",
    videoUrl = "",
    type = MediaType.SERIE_TV,
    year = 2011,
    tmdbId = 1399,
    seasonsCount = 1,
    seasonEpisodesCount = mapOf(1 to 8)
  )

  // ── Matcher ───────────────────────────────────────────────────────────────────────

  /**
   * Nei film l'etichetta del pulsante è «Riproduci»/«Play»; nelle serie è
   * «Riproduci S1 E1»/«Play S1 E1», quindi il confronto è per sottostringa come nelle
   * altre regressioni UI.
   */
  private fun focusablePlay() =
    isFocusable() and hasAnyDescendant(
      hasText(PLAY_LABEL_IT, substring = true) or hasText(PLAY_LABEL_EN, substring = true)
    )

  private fun focusableSynopsis() =
    isFocusable() and hasAnyDescendant(hasText(SYNOPSIS_TEXT))

  private fun focusableSelectSource() =
    isFocusable() and
      hasAnyDescendant(
        hasContentDescription(SELECT_SOURCE_LABEL_IT) or hasContentDescription(SELECT_SOURCE_LABEL_EN)
      )

  private fun focusableCastActor() =
    isFocusable() and hasAnyDescendant(hasText(CAST_ACTOR))

  private fun stubTrailerClose() = hasTestTag(STUB_TRAILER_CLOSE)
  private fun stubChipAll() = hasTestTag(STUB_CHIP_ALL)
  private fun stubChipCancel() = hasTestTag(STUB_CHIP_CANCEL)

  private fun pressDpad(key: Key) {
    rule.onNode(isFocused()).performKeyInput { pressKey(key) }
    rule.waitForIdle()
  }

  private fun assertNodeFocused(
    matcher: SemanticsMatcher,
    step: String,
    useUnmergedTree: Boolean = false
  ) {
    rule.onNode(matcher, useUnmergedTree = useUnmergedTree).assertIsFocused()
  }

  /** true se il nodo descritto da [matcher] detiene il focus (per le attese con waitUntil). */
  private fun isFocusedNode(
    matcher: SemanticsMatcher,
    useUnmergedTree: Boolean = false
  ): Boolean =
    try {
      rule.onNode(matcher, useUnmergedTree = useUnmergedTree).assertIsFocused()
      true
    } catch (_: AssertionError) {
      false
    } catch (_: IllegalStateException) {
      false
    }

  private fun focusedLabels(): String =
    rule.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().joinToString(" | ") { node ->
      val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("/") { it.text }
      val desc = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("/")
      "text=$text desc=$desc"
    }

  private fun focusedNodes(): List<SemanticsNode> =
    rule.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes()

  /** BACK di sistema: attraversa l'OnBackPressedDispatcher (i BackHandler composti). */
  private fun pressSystemBack() {
    val composeRule = rule as? AndroidComposeTestRule<*, *> ?: return
    rule.runOnUiThread {
      (composeRule.activity as? androidx.activity.ComponentActivity)
        ?.onBackPressedDispatcher
        ?.onBackPressed()
    }
    rule.waitForIdle()
  }

  // ── Harness: scheda di dettaglio + stub degli overlay ─────────────────────────────

  @Composable
  private fun App(media: MediaItem) {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0F14))) {
      DetailScreen(
        media = media,
        allMedia = listOf(media),
        onBackClick = {},
        onPlayClick = { _, _ -> playClicks++ },
        onToggleFavorite = {},
        onPlayTrailer = {
          trailerOpens++
          showTrailerOverlay = true
        },
        onSelectSourceClick = { _, _ ->
          showSourceOverlay = true
        },
        onCastMemberClick = {},
        targetCastMemberId = targetCastMemberId,
        onClearTargetCastMember = if (clearCastTargetOnRestore) {
          { targetCastMemberId = null }
        } else {
          null
        },
        sourceDialogDismissedToken = sourceDialogDismissedToken,
        trailerDismissedToken = trailerDismissedToken
      )
      if (showTrailerOverlay) {
        TrailerOverlayStub()
      }
      if (showSourceOverlay) {
        SourceSelectorStub()
      }
    }
  }

  private fun closeTrailerInHarness() {
    // Stessa snapshot di `StreamNovaViewModel.closeTrailer()`: overlayer smontato e
    // token del trailer avanzato insieme.
    showTrailerOverlay = false
    trailerDismissedToken += 1
  }

  /**
   * Stub fedele del comportamento di focus di [TrailerPlayerScreen]: overlay a tutto
   * schermo che alla composizione SI PRENDE il focus sul pulsante «Chiudi» (unico nodo
   * focusabile, come nell'originale dove la WebView non è focusabile) e chiude con BACK.
   * La chiusura applica la stessa snapshot di `closeTrailer()`.
   */
  @Composable
  private fun TrailerOverlayStub() {
    val closeRequester = remember { FocusRequester() }

    BackHandler { closeTrailerInHarness() }

    LaunchedEffect(Unit) {
      repeat(8) {
        withFrameNanos { }
        try {
          closeRequester.requestFocus()
          return@LaunchedEffect
        } catch (_: IllegalStateException) {
          delay(20)
        }
      }
    }

    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)
        .focusGroup(),
      contentAlignment = Alignment.Center
    ) {
      TvFocusableBox(
        modifier = Modifier
          .testTag(STUB_TRAILER_CLOSE)
          .focusRequester(closeRequester),
        shape = CircleShape,
        onClick = { closeTrailerInHarness() }
      ) { isFocused ->
        Box(
          modifier = Modifier
            .background(
              if (isFocused) Color(0xFF155E75) else Color(0xFF1E293B),
              CircleShape
            )
            .padding(16.dp)
        ) {
          Text(text = "Chiudi", color = Color.White)
        }
      }
    }
  }

  @Composable
  private fun StubChip(
    tag: String,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
  ) {
    TvFocusableBox(
      modifier = modifier.testTag(tag),
      shape = RoundedCornerShape(50),
      onClick = onClick
    ) { isFocused ->
      Box(
        modifier = Modifier
          .background(
            if (isFocused) Color(0xFF155E75) else Color(0xFF1E293B),
            RoundedCornerShape(50)
          )
          .padding(horizontal = 16.dp, vertical = 8.dp)
      ) {
        Text(
          text = label,
          color = if (isFocused) Color.White else Color(0xFF94A3B8)
        )
      }
    }
  }

  /**
   * Stub del selettore delle sorgenti (Fase 4): stesso comportamento di focus di
   * [com.example.ui.components.TorBoxSourceSelectionScreen] e stessa snapshot di
   * `dismissSourceDialog()` alla chiusura, per verificare che il ripristino del focus
   * dalle sorgenti continui a funzionare e non interferisca con quello del trailer.
   */
  @Composable
  private fun SourceSelectorStub() {
    val firstChipRequester = remember { FocusRequester() }

    BackHandler {
      showSourceOverlay = false
      sourceDialogDismissedToken += 1
    }

    LaunchedEffect(Unit) {
      repeat(8) {
        withFrameNanos { }
        try {
          firstChipRequester.requestFocus()
          return@LaunchedEffect
        } catch (_: IllegalStateException) {
          delay(20)
        }
      }
    }

    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color(0xFF101820))
        .focusGroup(),
      contentAlignment = Alignment.Center
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        StubChip(
          tag = STUB_CHIP_ALL,
          label = "Tutto",
          modifier = Modifier.focusRequester(firstChipRequester),
          onClick = {}
        )
        StubChip(
          tag = STUB_CHIP_CANCEL,
          label = "Annulla",
          onClick = {
            showSourceOverlay = false
            sourceDialogDismissedToken += 1
          }
        )
      }
    }
  }

  private fun render(media: MediaItem) {
    showTrailerOverlay = false
    trailerDismissedToken = 0
    showSourceOverlay = false
    sourceDialogDismissedToken = 0
    targetCastMemberId = null
    clearCastTargetOnRestore = true
    trailerOpens = 0
    playClicks = 0
    rule.setContent { App(media) }
    rule.waitForIdle()
  }

  /** Percorso D-pad: Riproduci -> Cuore -> Occhio -> Trailer. */
  private fun focusTrailerButton() {
    repeat(3) { pressDpad(Key.DirectionRight) }
  }

  // ── 1. Regressione principale: BACK dal trailer riporta il focus su «Riproduci» ────

  @Test
  fun ilBackDalTrailerRiportaIlFocusSuRiproduci() {
    render(movie())

    assertNodeFocused(focusablePlay(), "focus iniziale")
    focusTrailerButton()

    // OK apre il trailer: lo stub si prende il focus sul pulsante «Chiudi».
    pressDpad(Key.DirectionCenter)
    assertEquals(1, trailerOpens)
    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(stubTrailerClose()) }
    assertTrue(
      "col trailer aperto il focus deve stare nell'overlay (trovato: ${focusedLabels()})",
      focusedNodes().none { focusablePlay().matches(it) }
    )

    // BACK del telecomando: `closeTrailer()` -> overlay smontato + token avanzato.
    pressSystemBack()

    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(focusablePlay()) }
    assertNodeFocused(focusablePlay(), "ritorno dal trailer con BACK")
    assertTrue(
      "il focus NON deve atterrare sulla sinossi (trovato: ${focusedLabels()})",
      focusedNodes().none { focusableSynopsis().matches(it) }
    )

    // Nessun effetto successivo deve ri-sovrascrivere il focus (es. la sinossi).
    rule.waitForIdle()
    assertNodeFocused(focusablePlay(), "focus stabile su «Riproduci» dopo l'assestamento")
    assertEquals("La chiusura del trailer non avvia la riproduzione", 0, playClicks)
  }

  @Test
  fun ilPulsanteChiudiDelTrailerRiportaIlFocusSuRiproduci() {
    render(movie())

    focusTrailerButton()
    pressDpad(Key.DirectionCenter)
    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(stubTrailerClose()) }

    // OK sul pulsante «Chiudi» (già focalizzato): stessa chiusura via `onClose`.
    pressDpad(Key.DirectionCenter)

    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(focusablePlay()) }
    assertNodeFocused(focusablePlay(), "ritorno dal trailer con «Chiudi»")
    assertTrue(
      "il focus NON deve atterrare sulla sinossi (trovato: ${focusedLabels()})",
      focusedNodes().none { focusableSynopsis().matches(it) }
    )
  }

  // ── 2. Serie TV: stesso ripristino del film ────────────────────────────────────────

  @Test
  fun ilBackDalTrailerRiportaIlFocusSuRiproduciAnchePerLeSerie() {
    render(series())

    assertNodeFocused(focusablePlay(), "focus iniziale della serie")
    focusTrailerButton()
    pressDpad(Key.DirectionCenter)
    assertEquals(1, trailerOpens)
    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(stubTrailerClose()) }

    pressSystemBack()

    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(focusablePlay()) }
    assertNodeFocused(focusablePlay(), "ritorno dal trailer per una serie TV")
    assertTrue(
      "il focus NON deve atterrare sulla sinossi per una serie TV (trovato: ${focusedLabels()})",
      focusedNodes().none { focusableSynopsis().matches(it) }
    )
  }

  // ── 3. Non-regressione Fase 4 e indipendenza dei due ripristini ────────────────────

  /**
   * Percorso completo: chiusura del selettore sorgenti (Fase 4) -> focus su «Riproduci»;
   * poi apertura e chiusura del trailer -> di nuovo focus su «Riproduci». I due token
   * avanzano ciascuno sul proprio canale e nessuno consuma il segnale dell'altro.
   */
  @Test
  fun laChiusuraDelSelettoreSorgentiContinuaARipristinareIlFocusEDistingueIToken() {
    render(movie())

    assertNodeFocused(focusablePlay(), "focus iniziale")
    // Riproduci -> Cuore -> Occhio -> Trailer -> Seleziona fonte.
    repeat(4) { pressDpad(Key.DirectionRight) }
    assertNodeFocused(focusableSelectSource(), "pulsante «Seleziona fonte» raggiunto")

    pressDpad(Key.DirectionCenter)
    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(stubChipAll()) }
    // Chiusura con BACK: `onDismiss` -> `dismissSourceDialog()` (token sorgenti).
    pressSystemBack()
    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(focusablePlay()) }
    assertNodeFocused(focusablePlay(), "ritorno dal selettore sorgenti")
    assertEquals(1, sourceDialogDismissedToken)
    assertEquals("Il selettore sorgenti non deve avanzare il token del trailer", 0, trailerDismissedToken)

    // Ora il trailer: chiusura -> ripristino indipendente.
    focusTrailerButton()
    pressDpad(Key.DirectionCenter)
    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(stubTrailerClose()) }
    pressSystemBack()
    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(focusablePlay()) }
    assertNodeFocused(focusablePlay(), "ritorno dal trailer dopo la chiusura del selettore")
    assertEquals(1, trailerDismissedToken)
    assertEquals("Il trailer non deve avanzare il token delle sorgenti", 1, sourceDialogDismissedToken)
  }

  /**
   * Un token del trailer avanzato PRIMA dell'ingresso in composizione è ereditato
   * (apertura della scheda dopo una chiusura precedente): non deve strappare il focus al
   * ripristino del membro del cast, che è il meccanismo già esistente.
   */
  @Test
  fun unTokenDelTrailerEreditatoAllIngressoNonStrappaIlFocusAlCast() {
    targetCastMemberId = 1000 // id assegnato dal DetailScreen al primo membro del cast
    trailerDismissedToken = 2 // avanzato da chiusure precedenti: ereditato, NON consumato
    clearCastTargetOnRestore = false

    rule.setContent { App(movie(withCast = true, withSynopsis = false)) }
    rule.waitForIdle()

    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(focusableCastActor(), useUnmergedTree = true) }
    assertNodeFocused(focusableCastActor(), "ripristino del focus sul membro del cast", useUnmergedTree = true)
    assertTrue(
      "il token ereditato NON deve portare il focus su «Riproduci»",
      focusedNodes().none { focusablePlay().matches(it) }
    )
  }

  /**
   * Senza alcuna chiusura del trailer nessun effetto impone il focus: la catena storica
   * SU (sinossi) / GIÙ («Riproduci») resta invariata.
   */
  @Test
  fun senzaChiusureDelTrailerLaCatenaStoricaRestaInvariata() {
    render(movie())

    assertNodeFocused(focusablePlay(), "focus iniziale")
    pressDpad(Key.DirectionUp)
    assertNodeFocused(focusableSynopsis(), "sinossi focalizzata", useUnmergedTree = true)
    pressDpad(Key.DirectionDown)
    assertNodeFocused(focusablePlay(), "ritorno alla pulsantiera")
    assertEquals(0, trailerDismissedToken)
  }
}
