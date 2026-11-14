package com.example.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.MediaRepository
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regressioni del restyling «In evidenza» (FeaturedCinemaCard):
 *
 * 1. card non selezionata: locandina verticale, nessun metadato né sinossi;
 * 2. card selezionata: espansione 16:9, riga metadati + sinossi sotto la scheda;
 * 3. serie TV: segmento «N Stagioni» nella riga metadati;
 * 4. cambio selezione: la scheda precedente torna verticale, la nuova si espande;
 * 5. numeri a due cifre: area del numero più larga delle posizioni a una cifra.
 *
 * Geometria attesa della cella: area numero (78dp a una cifra) + card (148dp verticale,
 * 391dp in 16:9). Le immagini non arrivano (URL null): la catena di fallback della card
 * mostra il segnaposto, senza rete — il test verifica layout e testi, non bitmap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-tvdpi")
class FeaturedCinemaCardTest {

  @get:Rule
  val rule = createComposeRule()

  private fun film(index: Int) = MediaItem(
    id = "top10_film_$index",
    title = "Film $index",
    synopsis = "Sinossi del film $index per la verifica del restyling.",
    videoUrl = "",
    type = MediaType.FILM,
    year = 2026,
    genres = listOf("Azione", "Thriller")
  )

  private fun series(index: Int) = MediaItem(
    id = "top10_serie_$index",
    title = "Serie $index",
    synopsis = "Sinossi della serie $index per la verifica del restyling.",
    videoUrl = "",
    type = MediaType.SERIE_TV,
    year = 2026,
    genres = listOf("Dramma")
  )

  private fun cellWidthOf(tag: String): Float =
    rule.onNodeWithTag(tag).getUnclippedBoundsInRoot().let { it.right.value - it.left.value }

  /** Larghezza attesa della cella a una cifra: area numero + card. */
  private fun expectedCellWidth(cardWidth: Int): Float =
    (78 + cardWidth).toFloat()

  @Test
  fun cardaNonSelezionataRestaVerticaleSenzaMetadati() {
    val media = film(1)
    rule.setContent {
      Row(modifier = Modifier.width(960.dp).height(400.dp)) {
        FeaturedCinemaCard(
          rank = 1,
          media = media,
          onClick = {},
          modifier = Modifier.testTag("featured_cell_1")
        )
      }
    }
    rule.waitForIdle()

    assertEquals(
      "La card non selezionata deve mantenere la locandina verticale (148dp)",
      expectedCellWidth(148),
      cellWidthOf("featured_cell_1"),
      0.5f
    )
    // Nessuna scheda dettagliata su un titolo mai selezionato: né metadati né sinossi.
    assertTrue(
      "La sinossi non deve comparire sulla card non selezionata",
      rule.onAllNodesWithText(media.synopsis, substring = true).fetchSemanticsNodes().isEmpty()
    )
    assertTrue(
      "La riga metadati non deve comparire sulla card non selezionata",
      rule.onAllNodesWithText("Azione · 2026").fetchSemanticsNodes().isEmpty()
    )
  }

  @Test
  fun cardaSelezionataSiEspandeA16x9ConMetadatiESinossi() {
    val media = film(1)
    val meta = MediaRepository.FeaturedCardMeta(certification = "TV-MA")
    val firstFocus = FocusRequester()
    rule.setContent {
      Row(modifier = Modifier.width(960.dp).height(400.dp)) {
        FeaturedCinemaCard(
          rank = 1,
          media = media,
          onClick = {},
          meta = meta,
          onRequestMeta = {},
          focusRequester = firstFocus,
          modifier = Modifier.testTag("featured_cell_1")
        )
      }
    }
    rule.waitForIdle()
    firstFocus.requestFocus()
    rule.waitForIdle()

    assertEquals(
      "La card selezionata deve espandersi a 16:9 sull'altezza della locandina (391dp)",
      expectedCellWidth(391),
      cellWidthOf("featured_cell_1"),
      0.5f
    )
    // Riga metadati GENERE · ANNO · CLASSIFICAZIONE (il film non ha stagioni).
    rule.onAllNodesWithText("Azione · 2026 · TV-MA", useUnmergedTree = true)
      .fetchSemanticsNodes()
      .also { assertTrue("Riga metadati mancante sotto la scheda 16:9", it.isNotEmpty()) }
    // Sinossi reale dei metadati, sotto la riga metadati.
    rule.onAllNodesWithText(media.synopsis, substring = true, useUnmergedTree = true)
      .fetchSemanticsNodes()
      .also { assertTrue("Sinossi mancante sotto i metadati", it.isNotEmpty()) }
    // Il numero di classifica è disegnato su canvas con contentDescription dedicata:
    // resta individuabile (accessibilità) anche a scheda espansa.
    rule.onNodeWithContentDescription("Posizione 1 in classifica")
      .assertExists("Il numero di classifica deve restare visibile a scheda espansa")
  }

  @Test
  fun serieSelezionataMostraLeStagioniNellaRigaMetadati() {
    val media = series(1)
    val meta = MediaRepository.FeaturedCardMeta(certification = "TV-14", seasonsCount = 3)
    val firstFocus = FocusRequester()
    rule.setContent {
      Row(modifier = Modifier.width(960.dp).height(400.dp)) {
        FeaturedCinemaCard(
          rank = 1,
          media = media,
          onClick = {},
          meta = meta,
          onRequestMeta = {},
          focusRequester = firstFocus,
          modifier = Modifier.testTag("featured_cell_1")
        )
      }
    }
    rule.waitForIdle()
    firstFocus.requestFocus()
    rule.waitForIdle()

    // Il segmento «stagioni» usa la stringa localizzata dell'app: il test la risolve dal
    // contesto (Robolectric può girare con locale EN), senza hardcodare la lingua.
    val seasonsLabel = ApplicationProvider.getApplicationContext<Context>()
      .getString(R.string.hero_seasons_count, 3)
    rule.onAllNodesWithText("Dramma · 2026 · $seasonsLabel · TV-14", useUnmergedTree = true)
      .fetchSemanticsNodes()
      .also {
        assertTrue(
          "La riga metadati della serie deve includere genere, anno, stagioni e classificazione",
          it.isNotEmpty()
        )
      }
  }

  @Test
  fun cambioSelezioneRiportaLaCardPrecedenteAlPosterVerticale() {
    val first = film(1)
    val second = film(2)
    val firstFocus = FocusRequester()
    val secondFocus = FocusRequester()
    rule.setContent {
      Row(modifier = Modifier.width(960.dp).height(400.dp)) {
        FeaturedCinemaCard(
          rank = 1,
          media = first,
          onClick = {},
          onRequestMeta = {},
          focusRequester = firstFocus,
          modifier = Modifier.testTag("featured_cell_1")
        )
        FeaturedCinemaCard(
          rank = 2,
          media = second,
          onClick = {},
          onRequestMeta = {},
          focusRequester = secondFocus,
          modifier = Modifier.testTag("featured_cell_2")
        )
      }
    }
    rule.waitForIdle()
    firstFocus.requestFocus()
    rule.waitForIdle()
    assertEquals(
      "La prima card selezionata deve essere espansa",
      expectedCellWidth(391),
      cellWidthOf("featured_cell_1"),
      0.5f
    )

    secondFocus.requestFocus()
    rule.waitForIdle()

    assertEquals(
      "La scheda precedente deve tornare locandina verticale quando la selezione si sposta",
      expectedCellWidth(148),
      cellWidthOf("featured_cell_1"),
      0.5f
    )
    assertEquals(
      "La nuova selezione deve espandersi a 16:9",
      expectedCellWidth(391),
      cellWidthOf("featured_cell_2"),
      0.5f
    )
  }

  @Test
  fun numeroADueCifreRiservaPiuSpazioDiQuelloAUnaCifra() {
    rule.setContent {
      Row(modifier = Modifier.width(960.dp).height(400.dp)) {
        FeaturedCinemaCard(
          rank = 1,
          media = film(1),
          onClick = {},
          modifier = Modifier.testTag("featured_cell_1")
        )
        FeaturedCinemaCard(
          rank = 10,
          media = film(10),
          onClick = {},
          modifier = Modifier.testTag("featured_cell_10")
        )
      }
    }
    rule.waitForIdle()

    val singleDigitWidth = cellWidthOf("featured_cell_1")
    val twoDigitWidth = cellWidthOf("featured_cell_10")
    assertTrue(
      "Le posizioni 1-9 devono condividere la stessa area del numero",
      featuredRankAreaWidth(1) == featuredRankAreaWidth(9)
    )
    assertTrue(
      "L'area del numero «10» deve contenere la seconda cifra (cella10=$twoDigitWidth, cella1=$singleDigitWidth)",
      twoDigitWidth - singleDigitWidth >= (150 - 78) - 0.5f
    )
  }
}
