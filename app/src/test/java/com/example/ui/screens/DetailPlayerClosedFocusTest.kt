package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ritorno dal PLAYER al Dettaglio: ripristino del focus su «Riproduci».
 *
 * Difetto coperto: il player è un overlay composto SOPRA il NavHost, quindi [DetailScreen]
 * resta composto sotto di esso e NESSUNO dei suoi effetti di focus ri-parte alla chiusura.
 * Smontando il nodo focalizzato del player, il fallback di focus di Compose atterrava sul
 * PRIMO nodo focusabile della scheda (la sinossi) invece che sul pulsante «Riproduci».
 *
 * Meccanismo reale riprodotto dall'harness:
 *  - la scheda parte con il focus su «Riproduci», poi D-pad destro fino al pulsante Trailer
 *    (`hasUserNavigatedAway` diventa true, come nel caso reale segnalato);
 *  - OK su «Riproduci» apre uno stub fedele del player: overlay a tutto schermo che SI
 *    PRENDE il focus (nodo di riposo focalizzabile, come PlayerScreen);
 *  - la chiusura simula `closePlayer()`: overlay smontato E token `playerClosedToken`
 *    avanzato nella STESSA snapshot (ClosePlayerTokenState).
 *
 * Copre inoltre la non-regressione dei canali già esistenti (token del selettore sorgenti e
 * del trailer) e il caso di un token ereditato all'ingresso in composizione (scheda aperta
 * dopo una chiusura precedente), che non deve strappare il focus al ripristino del cast.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class DetailPlayerClosedFocusTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val PLAYER_TAG = "stub_player_overlay"
    const val PLAY_LABEL_IT = "Riproduci"
    const val PLAY_LABEL_EN = "Play"
    const val SYNOPSIS_TEXT = "Sinossi della scheda, troncata a quattro righe."
    const val CAST_ACTOR = "Anna Rossi"
  }

  // Stato dell'harness: overlay del player e token di chiusura, come MainActivity/ViewModel.
  private var showPlayerOverlay by mutableStateOf(false)
  private var playerClosedToken by mutableIntStateOf(0)
  private var trailerDismissedToken by mutableIntStateOf(0)
  private var sourceDialogDismissedToken by mutableIntStateOf(0)
  private var showTrailerOverlay by mutableStateOf(false)
  private var targetCastMemberId by mutableStateOf<Int?>(null)
  private var clearCastTargetOnRestore = true
  private var playClicks = 0

  private fun movie(withCast: Boolean = false) = MediaItem(
    id = "tt0117731",
    title = "Toy Story",
    synopsis = SYNOPSIS_TEXT,
    videoUrl = "",
    type = MediaType.FILM,
    year = 1995,
    tmdbId = 862,
    cast = if (withCast) listOf(CAST_ACTOR) else emptyList()
  )

  private fun series() = MediaItem(
    id = "tt0944947",
    title = "Game of Thrones",
    synopsis = SYNOPSIS_TEXT,
    videoUrl = "",
    type = MediaType.SERIE_TV,
    year = 2011,
    tmdbId = 1399,
    seasonsCount = 1,
    seasonEpisodesCount = mapOf(1 to 3)
  )

  // ── Matcher ───────────────────────────────────────────────────────────────────────

  private fun focusablePlay() =
    isFocusable() and hasAnyDescendant(
      hasText(PLAY_LABEL_IT, substring = true) or hasText(PLAY_LABEL_EN, substring = true)
    )

  private fun focusableSynopsis() =
    isFocusable() and hasAnyDescendant(hasText(SYNOPSIS_TEXT))

  private fun focusableCastActor() =
    isFocusable() and hasAnyDescendant(hasText(CAST_ACTOR))

  private fun pressDpad(key: Key) {
    rule.onNode(isFocused(), useUnmergedTree = true).performKeyInput { pressKey(key) }
    rule.waitForIdle()
  }

  private fun isFocusedNode(
    matcher: SemanticsMatcher,
    useUnmergedTree: Boolean = false
  ): Boolean = try {
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
      "text=$text"
    }

  private fun pressSystemBack() {
    val composeRule = rule as? AndroidComposeTestRule<*, *> ?: return
    rule.runOnUiThread {
      (composeRule.activity as? androidx.activity.ComponentActivity)
        ?.onBackPressedDispatcher
        ?.onBackPressed()
    }
    rule.waitForIdle()
  }

  // ── Harness ───────────────────────────────────────────────────────────────────────

  @Composable
  private fun App(media: MediaItem) {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0F14))) {
      DetailScreen(
        media = media,
        allMedia = listOf(media),
        onBackClick = {},
        onPlayClick = { _, _ ->
          playClicks++
          showPlayerOverlay = true
        },
        onToggleFavorite = {},
        onPlayTrailer = { showTrailerOverlay = true },
        onSelectSourceClick = null,
        sourceDialogDismissedToken = sourceDialogDismissedToken,
        trailerDismissedToken = trailerDismissedToken,
        playerClosedToken = playerClosedToken,
        onCastMemberClick = {},
        targetCastMemberId = targetCastMemberId,
        onClearTargetCastMember = if (clearCastTargetOnRestore) {
          { targetCastMemberId = null }
        } else {
          null
        }
      )
      if (showPlayerOverlay) {
        PlayerOverlayStub()
      }
      if (showTrailerOverlay) {
        TrailerOverlayStub()
      }
    }
  }

  /** Stessa snapshot di `closePlayer()`: overlay smontato + token avanzato insieme. */
  private fun closePlayerInHarness() {
    showPlayerOverlay = false
    playerClosedToken += 1
  }

  /**
   * Stub fedele del contratto di focus di PlayerScreen: overlay a tutto schermo con il nodo
   * di riposo focalizzabile che prende il focus all'ingresso; BACK chiude con la snapshot di
   * `closePlayer()`.
   */
  @Composable
  private fun PlayerOverlayStub() {
    val idleRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }

    BackHandler(enabled = true) { closePlayerInHarness() }

    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)
        .testTag(PLAYER_TAG)
    ) {
      Box(
        modifier = Modifier
          .size(1.dp)
          .focusRequester(idleRequester)
          .focusable()
      )
      LaunchedEffect(Unit) {
        repeat(20) {
          try {
            idleRequester.requestFocus()
            return@LaunchedEffect
          } catch (_: IllegalStateException) {
            withFrameNanos { }
          }
        }
      }
    }
  }

  /** Stub del trailer interno (overlay globale): chiude avanzando il PROPRIO token. */
  @Composable
  private fun TrailerOverlayStub() {
    val closeRequester = remember { FocusRequester() }

    BackHandler(enabled = true) {
      showTrailerOverlay = false
      trailerDismissedToken += 1
    }

    LaunchedEffect(Unit) {
      repeat(20) {
        try {
          closeRequester.requestFocus()
          return@LaunchedEffect
        } catch (_: IllegalStateException) {
          withFrameNanos { }
        }
      }
    }

    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)
        .focusable()
        .focusRequester(closeRequester)
    ) {
      Text(text = "Chiudi", color = Color.White)
    }
  }

  private fun render(media: MediaItem) {
    showPlayerOverlay = false
    playerClosedToken = 0
    trailerDismissedToken = 0
    sourceDialogDismissedToken = 0
    showTrailerOverlay = false
    targetCastMemberId = null
    clearCastTargetOnRestore = true
    playClicks = 0
    rule.setContent { App(media) }
    rule.waitForIdle()
  }

  /** OK su «Riproduci» (già focalizzato): il player si apre e prende il focus. */
  private fun openPlayerFromPlayButton() {
    pressDpad(Key.DirectionCenter)
    assertEquals("Il pulsante «Riproduci» deve aprire il player", 1, playClicks)
    rule.waitUntil(timeoutMillis = 5_000) {
      rule.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().any { node ->
        generateSequence(node) { it.parent }
          .any { it.config.getOrNull(SemanticsProperties.TestTag) == PLAYER_TAG }
      }
    }
  }

  // ── 1. Film ───────────────────────────────────────────────────────────────────────

  @Test
  fun ilBackDalPlayerRiportaIlFocusSuRiproduci() {
    render(movie())

    assertTrue("focus iniziale non su «Riproduci»", isFocusedNode(focusablePlay()))
    // L'utente naviga con il D-pad prima di riprodurre (come nel caso reale segnalato).
    pressDpad(Key.DirectionRight)
    pressDpad(Key.DirectionLeft)
    assertTrue("focus non tornato su «Riproduci»", isFocusedNode(focusablePlay()))

    openPlayerFromPlayButton()
    assertTrue(
      "il player deve detenere il focus (trovato: ${focusedLabels()})",
      rule.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().any { node ->
        generateSequence(node) { it.parent }
          .any { it.config.getOrNull(SemanticsProperties.TestTag) == PLAYER_TAG }
      }
    )

    pressSystemBack()

    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(focusablePlay()) }
    assertTrue(
      "il focus NON deve atterrare sulla sinossi (trovato: ${focusedLabels()})",
      !isFocusedNode(focusableSynopsis(), useUnmergedTree = true)
    )
    assertFalse("il player è ancora composto", showPlayerOverlay)

    // Il focus non deve essere sovrascritto da un effetto successivo.
    rule.waitForIdle()
    assertTrue("focus perso su «Riproduci» dopo l'assestamento", isFocusedNode(focusablePlay()))
  }

  // ── 2. Serie TV ───────────────────────────────────────────────────────────────────

  @Test
  fun ilBackDalPlayerRiportaIlFocusSuRiproduciAnchePerLeSerie() {
    render(series())

    assertTrue("focus iniziale non su «Riproduci» (serie)", isFocusedNode(focusablePlay()))
    openPlayerFromPlayButton()
    pressSystemBack()

    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(focusablePlay()) }
    assertTrue(
      "il focus NON deve atterrare sulla sinossi per una serie (trovato: ${focusedLabels()})",
      !isFocusedNode(focusableSynopsis(), useUnmergedTree = true)
    )
  }

  // ── 3. Canali indipendenti ────────────────────────────────────────────────────────

  /**
   * Dopo il rientro dal player il canale del trailer resta operativo e indipendente: la
   * chiusura del trailer riporta ancora il focus su «Riproduci» senza consumare il segnale
   * del player (che è già stato consumato in precedenza).
   */
  @Test
  fun ilRipristinoDalPlayerNonConsumaIlCanaleDelTrailer() {
    render(movie())

    openPlayerFromPlayButton()
    pressSystemBack()
    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(focusablePlay()) }
    assertEquals(1, playerClosedToken)

    // Riproduci -> Cuore -> Occhio -> Trailer.
    repeat(3) { pressDpad(Key.DirectionRight) }
    pressDpad(Key.DirectionCenter)
    rule.waitUntil(timeoutMillis = 5_000) { showTrailerOverlay }
    pressSystemBack()

    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(focusablePlay()) }
    assertEquals("Il trailer deve avanzare il proprio token", 1, trailerDismissedToken)
    assertEquals("Il token del player non deve avanzare con il trailer", 1, playerClosedToken)
    assertEquals(0, sourceDialogDismissedToken)
  }

  // ── 4. Token ereditato ────────────────────────────────────────────────────────────

  /**
   * Un token del player avanzato PRIMA dell'ingresso in composizione è ereditato (scheda
   * aperta dopo la chiusura del player): non deve strappare il focus al ripristino del
   * membro del cast, che è il meccanismo già esistente.
   */
  @Test
  fun unTokenDelPlayerEreditatoAllIngressoNonStrappaIlFocusAlCast() {
    targetCastMemberId = 1000 // id assegnato dal DetailScreen al primo membro del cast
    playerClosedToken = 4 // avanzato da chiusure precedenti: ereditato, NON consumato
    clearCastTargetOnRestore = false

    rule.setContent { App(movie(withCast = true)) }
    rule.waitForIdle()

    rule.waitUntil(timeoutMillis = 5_000) {
      isFocusedNode(focusableCastActor(), useUnmergedTree = true)
    }
    assertTrue(
      "il token ereditato NON deve portare il focus su «Riproduci» (trovato: ${focusedLabels()})",
      !isFocusedNode(focusablePlay())
    )
  }
}
