package com.example.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.ui.components.ProviderConstants
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Navigazione D-pad VERTICALE nella griglia della schermata Provider (Fire TV).
 *
 * Difetto coperto (osservato su Fire TV reale): risalendo verso l'alto, arrivati alla
 * seconda riga il focus non passa alla prima riga ma salta alla fila dei filtri in alto.
 *
 * La griglia usa `GridCells.Adaptive(minSize = 150.dp)` con 16.dp di spaziatura e 32.dp di
 * contentPadding orizzontale. Su una finestra TV 960x540dp il numero di colonne è 5: gli
 * indici 0..4 sono la PRIMA riga, l'indice 5 è già nella SECONDA. La vecchia regola
 * `if (index < 6) up = <chip>` assegnava quindi il salto ai filtri anche a una card della
 * seconda riga: da lì il D-pad UP ignorava la prima riga.
 *
 * I test verificano il percorso completo in entrambe le direzioni, il limite superiore
 * reale, la navigazione orizzontale, la riga finale incompleta e la griglia aggiornata
 * dinamicamente da una nuova pagina di catalogo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-tvdpi")
class ProviderGridDpadNavigationTest {

  @get:Rule
  val rule = createComposeRule()

  private var liveItems by mutableStateOf<List<MediaItem>>(emptyList())

  private companion object {
    /** Colonne effettive della griglia a 960dp (896dp utili, celle da 150dp, gap 16dp). */
    const val COLUMNS = 5
  }

  private fun item(index: Int): MediaItem = MediaItem(
    id = "m%02d".format(index + 1),
    title = "Titolo %02d".format(index + 1),
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = 2000 + index,
    tmdbId = index + 1,
    rating = 10f - index * 0.05f,
    provider = "netflix"
  )

  private fun render(items: List<MediaItem>) {
    liveItems = items
    rule.setContent {
      Box(modifier = Modifier.fillMaxSize()) {
        ProviderScreen(
          provider = ProviderConstants.NETFLIX,
          allMedia = liveItems,
          onBackClick = {},
          onMediaClick = {},
          viewModel = null
        )
      }
    }
    rule.waitForIdle()
  }

  // ------------------------------------------------------------- helpers

  private fun focusedText(): String? = try {
    rule.onNode(isFocused()).fetchSemanticsNode().config
      .getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
  } catch (_: Throwable) {
    null
  }

  @OptIn(ExperimentalTestApi::class)
  private fun focusCard(title: String) {
    rule.onNode(hasText(title)).requestFocus()
    rule.waitForIdle()
  }

  @OptIn(ExperimentalTestApi::class)
  private fun press(key: Key, times: Int = 1) {
    repeat(times) {
      rule.onNode(isFocused()).performKeyInput { pressKey(key) }
      rule.waitForIdle()
    }
  }

  private fun assertFocused(title: String) {
    rule.waitUntil(timeoutMillis = 5_000) { focusedText()?.startsWith(title) == true }
    assertTrue(
      "Focus atteso su $title, trovato ${focusedText()}",
      focusedText()?.startsWith(title) == true
    )
  }

  private fun assertNotOnPoster() {
    val focusedNodes = rule.onAllNodes(isFocused(), useUnmergedTree = true)
      .fetchSemanticsNodes(atLeastOneRootRequired = false)
    assertTrue("Nessun nodo ha il focus dopo il D-pad", focusedNodes.isNotEmpty())
    val onPoster = focusedNodes.any { node ->
      node.config.getOrNull(SemanticsProperties.Text)
        ?.any { text -> text.text.startsWith("Titolo") } == true
    }
    assertTrue(
      "Il focus è ancora su una card della griglia invece che sulla riga dei filtri",
      !onPoster
    )
  }

  // ------------------------------------------------------------- percorsi verticali

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun dpadUpDallaSecondaRigaRaggiungeLaPrimaRigaSenzaSalti() {
    render(List(30) { item(it) })

    // Indice 5 = prima card della SECONDA riga (colonna 1). La card sopra è l'indice 0.
    focusCard("Titolo 06")
    press(Key.DirectionUp)

    assertFocused("Titolo 01")
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun dpadDownPoiUpPercorreTutteLeRigheSenzaSalti() {
    render(List(30) { item(it) })

    focusCard("Titolo 01")

    // Discesa: una riga per volta, stessa colonna.
    repeat(5) { row ->
      press(Key.DirectionDown)
      assertFocused("Titolo %02d".format((row + 1) * COLUMNS + 1))
    }

    // Ultima riga (indici 25..29): un altro DOWN non deve spostare il focus.
    press(Key.DirectionDown)
    assertFocused("Titolo 26")

    // Risalita: riga per riga fino alla prima, senza scavalcare nulla.
    for (row in 4 downTo 0) {
      press(Key.DirectionUp)
      assertFocused("Titolo %02d".format(row * COLUMNS + 1))
    }
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun dpadUpPassaDaPenultimaAUltimaRigaPrimaDiRaggiungereIFiltri() {
    render(List(30) { item(it) })

    // Scende fino alla QUINTA riga (indice 20), poi risale: il passaggio
    // quinta -> quarta riga non deve saltare nulla.
    focusCard("Titolo 01")
    press(Key.DirectionDown, times = 4)
    assertFocused("Titolo 21")
    press(Key.DirectionUp)
    assertFocused("Titolo 16")

    // Dall'ultima riga reale (25..29) si risale alla penultima e poi ancora.
    press(Key.DirectionDown, times = 5)
    assertFocused("Titolo 26")
    press(Key.DirectionUp)
    assertFocused("Titolo 21")
    press(Key.DirectionUp)
    assertFocused("Titolo 16")
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun iFiltriSuperioriRicevonoIlFocusSoloAlLimiteSuperioreDellaGriglia() {
    render(List(6) { item(it) })

    // Con 6 elementi la griglia ha due righe: dalla seconda riga UP deve restare nella griglia.
    focusCard("Titolo 06")
    press(Key.DirectionUp)
    assertFocused("Titolo 01")

    // Dalla PRIMA riga il limite superiore è reale: il focus passa ai filtri.
    press(Key.DirectionUp)
    assertNotOnPoster()
  }

  // ------------------------------------------------------------- orizzontale e righe corte

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun navigazioneOrizzontaleTraLeCard() {
    render(List(12) { item(it) })

    focusCard("Titolo 01")
    press(Key.DirectionRight)
    assertFocused("Titolo 02")
    press(Key.DirectionRight, times = 3)
    assertFocused("Titolo 05")
    // Bordo destro della prima riga: RIGHT non deve uscire dalla griglia.
    press(Key.DirectionRight)
    assertFocused("Titolo 05")
    press(Key.DirectionLeft, times = 4)
    assertFocused("Titolo 01")
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun ultimaRigaIncompletaNonSaltaColonne() {
    // 23 elementi: ultima riga con 3 card (indici 20, 21, 22).
    render(List(23) { item(it) })

    // Raggiunta la riga incompleta navigando (le card profonde non sono composte a schermo).
    focusCard("Titolo 01")
    press(Key.DirectionDown, times = 3)
    assertFocused("Titolo 16")
    press(Key.DirectionDown)
    assertFocused("Titolo 21")
    press(Key.DirectionRight)
    assertFocused("Titolo 22")
    press(Key.DirectionRight)
    assertFocused("Titolo 23")

    // Risalita dalla riga incompleta: la colonna della card corrente viene preservata
    // dove esiste nella riga superiore.
    press(Key.DirectionUp)
    assertFocused("Titolo 18")
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun grigliaConUnaSolaRiga() {
    render(List(4) { item(it) })

    focusCard("Titolo 01")
    press(Key.DirectionRight)
    assertFocused("Titolo 02")
    press(Key.DirectionRight, times = 3)
    assertFocused("Titolo 04")
    press(Key.DirectionRight)
    assertFocused("Titolo 04")

    press(Key.DirectionUp)
    assertNotOnPoster()
  }

  // ------------------------------------------------------------- aggiornamento dinamico

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun laGrigliaAggiornataDaUnaNuovaPaginaRestaNavigabileRigaPerRiga() {
    // Prima pagina piccola: 8 card (2 righe).
    render(List(8) { item(it) })

    focusCard("Titolo 06")
    press(Key.DirectionUp)
    assertFocused("Titolo 01")

    // Arriva la pagina successiva: la lista cresce senza rimescolare le card già a schermo.
    liveItems = List(23) { item(it) }
    rule.waitForIdle()

    focusCard("Titolo 06")
    press(Key.DirectionDown)
    assertFocused("Titolo 11")
    press(Key.DirectionUp)
    assertFocused("Titolo 06")
    press(Key.DirectionUp)
    assertFocused("Titolo 01")
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun laPrimaRigaUsaTutteELeSoleColonneEffettive() {
    render(List(12) { item(it) })

    // Ogni card della prima riga deve risalire alla card soprastante solo quando esiste;
    // per le card 0..4 il limite superiore è reale e il focus va ai filtri.
    for (index in 0 until COLUMNS) {
      focusCard("Titolo %02d".format(index + 1))
      press(Key.DirectionUp)
      assertNotOnPoster()
    }

    // La card 5 (prima della seconda riga) NON è più trattata come limite superiore.
    focusCard("Titolo 06")
    press(Key.DirectionUp)
    assertFocused("Titolo 01")
  }
}
