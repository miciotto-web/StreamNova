package com.example.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.CatalogSection
import com.example.data.repository.CatalogSourceOrigin
import com.example.data.repository.HomeCatalogs
import com.example.data.repository.TmdbHomeCatalogProvider
import com.example.ui.components.LocalClearLastFocusedMedia
import com.example.ui.components.LocalFocusRestoreToken
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.LocalLastFocusedTarget
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regressione FOCUS/SCROLLING della Home con le NUOVE righe TMDB
 * ("Serie TV in onda", "Novità in digitale", "Novità HBO") renderizzate come
 * [HomeCatalogs.extraSections].
 *
 * Requisito: la migrazione dati non deve cambiare il comportamento della Home.
 * Questi test usano la schermata REALE ([HomeMainBrowsingContent]) e il sistema di
 * focus Compose, quindi verificano che:
 *  - il D-pad Su/Giù attraversi le nuove righe una per una, senza saltarne;
 *  - Destra/Sinistra scorrano dentro la riga extra;
 *  - un refresh dei cataloghi (nuova lista con gli stessi id) non faccia perdere il
 *    focus dalla card corrente;
 *  - il rientro dal Dettaglio ripristini una card dentro una riga extra.
 *
 * I limiti di questa verifica sono documentati nel report: sono test Robolectric,
 * non una prova su dispositivo fisico (vietato installare in questa fase).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h900dp-tvdpi")
class HomeExtraRowsFocusUiTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val ROW_POPULAR_FILM = "section_popolari_film"
    const val ROW_POPULAR_SERIE = "section_popolari_serie"
    const val ROW_ON_AIR = "section_${TmdbHomeCatalogProvider.SECTION_ON_AIR_SERIES}"
    const val ROW_DIGITAL = "section_${TmdbHomeCatalogProvider.SECTION_DIGITAL_NEW}"
    const val ROW_HBO = "section_${TmdbHomeCatalogProvider.SECTION_HBO_NEW}"

    const val YEAR_POPULAR_FILM = 2101
    const val YEAR_POPULAR_SERIE = 2102
    const val YEAR_ON_AIR = 2201
    const val YEAR_DIGITAL = 2202
    const val YEAR_HBO = 2203

    const val CARDS_PER_ROW = 6
  }

  private fun media(
    rowKey: String,
    index: Int,
    title: String,
    type: MediaType,
    year: Int
  ) = MediaItem(
    id = "$rowKey-${index + 1}",
    title = title,
    synopsis = "",
    videoUrl = "",
    type = type,
    year = year,
    tmdbId = index + 1
  )

  private fun row(
    rowKey: String,
    prefix: String,
    type: MediaType,
    year: Int,
    count: Int = CARDS_PER_ROW
  ): List<MediaItem> = List(count) { index ->
    media(rowKey, index, "$prefix ${index + 1}", type, year)
  }

  private fun extraSection(
    id: String,
    title: String,
    type: MediaType,
    items: List<MediaItem>
  ) = CatalogSection(
    id = id,
    title = title,
    addonId = null,
    addonName = null,
    catalogId = id,
    mediaType = type,
    origin = CatalogSourceOrigin.TMDB_NATIVE,
    items = items
  )

  /**
   * Home senza Hero: le righe Popolari sono subito composte e il test può partire
   * dalla prima riga. Le tre righe native seguono le due righe Popolari.
   */
  private fun catalogs(onAirCardCount: Int = CARDS_PER_ROW): HomeCatalogs = HomeCatalogs(
    heroItems = emptyList(),
    popularMovies = row(ROW_POPULAR_FILM, "PF", MediaType.FILM, YEAR_POPULAR_FILM),
    popularSeries = row(ROW_POPULAR_SERIE, "PS", MediaType.SERIE_TV, YEAR_POPULAR_SERIE),
    extraSections = listOf(
      extraSection(
        TmdbHomeCatalogProvider.SECTION_ON_AIR_SERIES,
        "Serie TV in onda",
        MediaType.SERIE_TV,
        row(ROW_ON_AIR, "OA", MediaType.SERIE_TV, YEAR_ON_AIR, onAirCardCount)
      ),
      extraSection(
        TmdbHomeCatalogProvider.SECTION_DIGITAL_NEW,
        "Novità in digitale",
        MediaType.FILM,
        row(ROW_DIGITAL, "DG", MediaType.FILM, YEAR_DIGITAL)
      ),
      extraSection(
        TmdbHomeCatalogProvider.SECTION_HBO_NEW,
        "Novità HBO",
        MediaType.SERIE_TV,
        row(ROW_HBO, "HB", MediaType.SERIE_TV, YEAR_HBO)
      )
    )
  )

  private var restoreTarget by mutableStateOf<Pair<String, String>?>(null)
  private var restoreToken by mutableStateOf(0)
  private var liveCatalogs by mutableStateOf<HomeCatalogs?>(null)

  private fun renderHome(catalogs: HomeCatalogs) {
    liveCatalogs = catalogs
    rule.setContent {
      CompositionLocalProvider(
        LocalLastFocusedMediaId provides restoreTarget?.second,
        LocalLastFocusedTarget provides restoreTarget,
        LocalFocusRestoreToken provides restoreToken,
        LocalClearLastFocusedMedia provides { restoreTarget = null }
      ) {
        HomeMainBrowsingContent(
          allMedia = emptyList(),
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

  // ---------------------------------------------------------------- helper

  private fun focusedTexts(): List<String> =
    rule.onAllNodes(isFocused()).fetchSemanticsNodes()
      .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList() }

  @OptIn(ExperimentalTestApi::class)
  private fun focusCard(title: String) {
    rule.onNode(hasText(title)).requestFocus()
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
    val expectedYear = year.toString()
    rule.waitUntil(timeoutMillis = 5_000) {
      focusedTexts().let { it.contains(title) && it.contains(expectedYear) }
    }
    rule.onNode(isFocused()).assertTextContains(title)
    rule.onNode(isFocused()).assertTextContains(expectedYear)
  }

  /**
   * Porta il focus sulla prima card di "Serie TV in onda" partendo da una riga
   * composta: richiedere il focus direttamente a una card fuori viewport non è
   * possibile (non è composta), si usa la navigazione D-pad reale.
   */
  private fun focusOnAirFirst() {
    focusCard("PF 1")
    pressKey(Key.DirectionDown)
    assertFocusedCard("PS 1", YEAR_POPULAR_SERIE)
    pressKey(Key.DirectionDown)
    assertFocusedCard("OA 1", YEAR_ON_AIR)
  }

  /** Porta il focus sulla prima card di "Novità HBO" tramite D-pad. */
  private fun focusHboFirst() {
    focusOnAirFirst()
    pressKey(Key.DirectionDown)
    assertFocusedCard("DG 1", YEAR_DIGITAL)
    pressKey(Key.DirectionDown)
    assertFocusedCard("HB 1", YEAR_HBO)
  }

  // ---------------------------------------------------------------- test

  @Test
  fun giuAttraversaLeNuoveRigheTmdbUnaPerUna() {
    renderHome(catalogs())

    focusCard("PF 1")
    assertFocusedCard("PF 1", YEAR_POPULAR_FILM)

    pressKey(Key.DirectionDown)
    assertFocusedCard("PS 1", YEAR_POPULAR_SERIE)

    pressKey(Key.DirectionDown)
    assertFocusedCard("OA 1", YEAR_ON_AIR)

    pressKey(Key.DirectionDown)
    assertFocusedCard("DG 1", YEAR_DIGITAL)

    pressKey(Key.DirectionDown)
    assertFocusedCard("HB 1", YEAR_HBO)
  }

  @Test
  fun suRipercorreLeNuoveRigheInSensoInverso() {
    renderHome(catalogs())

    focusHboFirst()

    pressKey(Key.DirectionUp)
    assertFocusedCard("DG 1", YEAR_DIGITAL)

    pressKey(Key.DirectionUp)
    assertFocusedCard("OA 1", YEAR_ON_AIR)

    pressKey(Key.DirectionUp)
    assertFocusedCard("PS 1", YEAR_POPULAR_SERIE)
  }

  @Test
  fun destraESinistraScorronoDentroUnaRigaExtra() {
    renderHome(catalogs())

    focusOnAirFirst()

    pressKey(Key.DirectionRight, times = 3)
    assertFocusedCard("OA 4", YEAR_ON_AIR)

    pressKey(Key.DirectionLeft)
    assertFocusedCard("OA 3", YEAR_ON_AIR)
  }

  @Test
  fun unRefreshDeiCataloghiNonFaPerdereIlFocusSullaRigaExtra() {
    renderHome(catalogs())

    focusOnAirFirst()
    pressKey(Key.DirectionRight)
    assertFocusedCard("OA 2", YEAR_ON_AIR)

    // Ondata dati successiva (refresh TMDB): stessa riga, stessa key della card,
    // lista più lunga. Il focus non deve saltare né sulla riga adiacente né a inizio riga.
    liveCatalogs = catalogs(onAirCardCount = CARDS_PER_ROW + 2)
    rule.waitForIdle()

    assertFocusedCard("OA 2", YEAR_ON_AIR)

    // La navigazione successiva riparte dalla card che aveva il focus.
    pressKey(Key.DirectionRight)
    assertFocusedCard("OA 3", YEAR_ON_AIR)
  }

  @Test
  fun unaRigaNativaNonMostraMaiIlBadgeCinemeta() {
    // Regressione: un addonName legacy popolato su una sezione TMDB non deve
    // produrre il badge "• Cinemeta" sopra contenuti del provider nativo.
    val catalogsWithLegacyName = catalogs().let { base ->
      base.copy(extraSections = base.extraSections.map { it.copy(addonName = "Cinemeta") })
    }

    renderHome(catalogsWithLegacyName)

    rule.onAllNodes(hasText("Cinemeta", substring = true)).assertCountEquals(0)
  }

  @Test
  fun ilRientroDalDettaglioRipristinaUnaCardDellaRigaHbo() {
    renderHome(catalogs())

    // Stesso ordine dell'hook di produzione: la Home è già composta e il target di
    // ripristino arriva dopo (stato osservabile), non alla prima composizione.
    restoreTarget = ROW_HBO to "$ROW_HBO-3"
    restoreToken = 1
    rule.waitForIdle()

    assertFocusedCard("HB 3", YEAR_HBO)
  }
}
