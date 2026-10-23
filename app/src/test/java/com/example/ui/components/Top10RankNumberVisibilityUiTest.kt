package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.example.data.model.MediaItem
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regressione Top 10 nelle righe Home: il numero di classifica NON è focusable, quindi il solo
 * reveal nativo del focus porta in vista il poster e trascura il numero. Se il `bringIntoView`
 * della card non copre l'intera larghezza del blocco (numero + gap + poster), dopo aver scorso
 * la riga a destra e essere tornati a sinistra il numero 1 resta fuori dal viewport della riga
 * (sotto la Sidebar).
 *
 * La card è montata con `rowKey != null`, cioè lo stesso percorso della Home: è esattamente il
 * gate `rowKey == null` a disabilitare il reveal, e questo test impedisce di reintrodurlo.
 *
 * Geometria: viewport da 640dp su card da 260dp (228dp di blocco + 32dp di spaziatura). Dopo due
 * passi a destra la riga scorre di 132dp ma la prima card resta parzialmente composta, come in
 * Home; al rientro a sinistra il reveal nativo del solo poster si ferma 80dp più avanti, lasciando
 * il numero fuori dal viewport.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h360dp-tvdpi")
class Top10RankNumberVisibilityUiTest {

  @get:Rule
  val rule = createComposeRule()

  private val firstPoster = FocusRequester()
  private val rowState = LazyListState()

  private val top10Films = List(10) { index ->
    MediaItem(
      id = "top10_film_$index",
      title = "Film ${index + 1}",
      synopsis = "",
      videoUrl = "",
      // year = 0: nessun nodo testuale numerico oltre al numero di classifica.
      year = 0
    )
  }

  @OptIn(ExperimentalTestApi::class)
  private fun pressOnFocusedCard(key: Key) {
    rule.onNode(isFocused()).performKeyInput { pressKey(key) }
    rule.waitForIdle()
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun numero1RestaNelViewportDopoAndataADestraERitornoASinistra() {
    rule.setContent {
      Box(modifier = Modifier.width(640.dp).height(360.dp)) {
        // Stessa catena della Home: TvFocusRow attorno alla LazyRow della sezione.
        TvFocusRow {
          LazyRow(
            state = rowState,
            modifier = Modifier
              .fillMaxSize()
              .testTag("top10_row"),
            contentPadding = PaddingValues(start = 24.dp, end = 32.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(Top10RowSpacing)
          ) {
            itemsIndexed(top10Films, key = { _, item -> item.id }) { index, item ->
              Top10RankedMediaCard(
                rank = index + 1,
                media = item,
                onClick = {},
                modifier = Modifier.testTag("top10_card_$index"),
                focusRequester = if (index == 0) firstPoster else null,
                rowKey = "section_top_10_film"
              )
            }
          }
        }
      }
    }
    rule.waitForIdle()

    firstPoster.requestFocus()
    rule.waitForIdle()

    // 1. Navigazione D-pad verso destra: la riga deve scorrere, altrimenti il reveal non
    //    verrebbe esercitato e l'asserzione finale sarebbe vacuamente vera.
    repeat(2) { pressOnFocusedCard(Key.DirectionRight) }
    assertTrue(
      "La navigazione a destra non ha scorso la riga: il reveal non sarebbe esercitato",
      rowState.firstVisibleItemIndex > 0 || rowState.firstVisibleItemScrollOffset > 0
    )

    // 2. Ritorno a sinistra fino alla prima card: qui il reveal deve riportare in vista anche
    //    il numero, che non è focusable e non viene considerato dal focus nativo.
    repeat(2) { pressOnFocusedCard(Key.DirectionLeft) }
    rule.waitForIdle()

    val rowLeft = rule.onNodeWithTag("top10_row").getUnclippedBoundsInRoot().left
    // Blocco numero + poster: se resta dentro la riga, il numero è visibile. I bounds sono di
    // layout (nessuna dipendenza dalle metriche del glifo).
    val cardLeft = rule.onNodeWithTag("top10_card_0").getUnclippedBoundsInRoot().left
    assertTrue(
      "Blocco numero + poster fuori dal viewport della riga (card=$cardLeft, row=$rowLeft): " +
        "il reveal deve coprire numero + gap + poster",
      cardLeft.value >= rowLeft.value - 0.5f
    )

    // Il numero è il primo elemento del blocco: se il glifo sborda dalla propria area riservata
    // (maxLines = 1, softWrap = false) resta comunque a ridosso del bordo, mai 80dp più a monte.
    val rankLeft = rule.onNodeWithText("1", useUnmergedTree = true)
      .getUnclippedBoundsInRoot().left
    assertTrue(
      "Numero 1 fuori dal viewport della riga (rank=$rankLeft, row=$rowLeft)",
      rankLeft.value >= rowLeft.value - RANK_GLYPH_SLACK_DP
    )
  }

  /**
   * Regressione "10" nelle card Top 10: a 115sp la coppia di cifre misura ~128dp, mentre l'area
   * del numero era larga 72dp (pensata per una cifra). Con `softWrap = false` e l'overflow
   * predefinito `TextOverflow.Clip` la seconda cifra finiva fuori dall'area di disegno e la card
   * mostrava soltanto "1".
   *
   * Le metriche dei glifi in Robolectric non riproducono il taglio, quindi il test verifica la
   * geometria che lo rende impossibile: la card "10" deve riservare al numero almeno lo spazio
   * della seconda cifra (~0,55em) in più rispetto a una card a una cifra. Con l'area fissa
   * precedente le due card avevano la stessa larghezza. In più il test controlla che le posizioni
   * 1-9 mantengano l'area attuale e che il numero resti dentro l'area riservata, a sinistra del
   * gap verso il poster (quindi non tagliato né sovrapposto alla locandina).
   */
  @Test
  fun numero10MostraEntrambeLeCifreDentroLAreaRiservata() {
    val firstMedia = MediaItem(
      id = "top10_first",
      title = "Primo",
      synopsis = "",
      videoUrl = "",
      year = 0
    )
    val tenthMedia = MediaItem(
      id = "top10_tenth",
      title = "Decimo",
      synopsis = "",
      videoUrl = "",
      year = 0
    )

    rule.setContent {
      Row(modifier = Modifier.width(640.dp).height(360.dp)) {
        Top10RankedMediaCard(
          rank = 1,
          media = firstMedia,
          onClick = {},
          modifier = Modifier.testTag("top10_card_1")
        )
        Top10RankedMediaCard(
          rank = 10,
          media = tenthMedia,
          onClick = {},
          modifier = Modifier.testTag("top10_card_10")
        )
      }
    }
    rule.waitForIdle()

    val oneCard = rule.onNodeWithTag("top10_card_1").getUnclippedBoundsInRoot()
    val tenCard = rule.onNodeWithTag("top10_card_10").getUnclippedBoundsInRoot()
    // Esiste un nodo con l'etichetta completa "10": la Text riceve entrambe le cifre.
    val ten = rule.onNodeWithText("10", useUnmergedTree = true)
      .getUnclippedBoundsInRoot()
    // getUnclippedBoundsInRoot() restituisce DpRect: larghezze e area riservata vanno confrontate in dp.
    val singleDigitCardWidth = oneCard.right.value - oneCard.left.value
    val twoDigitCardWidth = tenCard.right.value - tenCard.left.value
    val secondDigitWidth = with(rule.density) {
      Top10RankFontSize.toDp() * DIGIT_ADVANCE_EM
    }
    val reservedWidth = top10RankAreaWidth(10).value

    assertTrue(
      "Le posizioni 1-9 devono mantenere la stessa area del numero",
      top10RankAreaWidth(1) == top10RankAreaWidth(9)
    )
    assertTrue(
      "La card 10 non riserva spazio per la seconda cifra (card10=${twoDigitCardWidth}dp, " +
        "card1=${singleDigitCardWidth}dp, seconda cifra attesa=${secondDigitWidth.value}dp): " +
        "il numero verrebbe tagliato a \"1\"",
      twoDigitCardWidth - singleDigitCardWidth >= secondDigitWidth.value
    )
    assertTrue(
      "Il numero 10 sborda dall'area riservata (ten=$ten, card=$tenCard, " +
        "reserved=${reservedWidth}dp): non deve essere tagliato né coprire il poster",
      ten.left.value >= tenCard.left.value - 0.5f &&
        ten.right.value <= tenCard.left.value + reservedWidth + 0.5f
    )
  }

  private companion object {
    /**
     * Tolleranza per l'eventuale sbordo del glifo del numero oltre la propria area riservata
     * (dipende dalle metriche del font). Il difetto da intercettare è di ~80dp, quindi questo
     * margine non indebolisce l'asserzione.
     */
    const val RANK_GLYPH_SLACK_DP = 16f

    /**
     * Avanzamento orizzontale prudenziale di una cifra in em: in Roboto Black è ~0,556em.
     * Serve a quantificare lo spazio che la seconda cifra di "10" deve avere nella card.
     */
    const val DIGIT_ADVANCE_EM = 0.55f
  }
}
