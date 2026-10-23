package com.example.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.HomeCatalogs
import com.example.ui.components.FOCUS_ROW_ANY
import com.example.ui.components.LocalClearLastFocusedMedia
import com.example.ui.components.LocalFocusRestoreToken
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.LocalLastFocusedTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Navigazione verticale della Home sulla schermata REALE ([HomeMainBrowsingContent]) con il
 * sistema di focus di Compose (LazyColumn + righe LazyRow), sotto Robolectric.
 *
 * MODELLO DI RIFERIMENTO (porting NuvioTV, ModernHomeRows.kt + focusRestorer di Compose 1.7.2):
 * - lo step SINGOLO Su/Giù è della ricerca nativa del focus: la riga è un `focusGroup` con
 *   `focusRestorer`; con un candidato geometrico nella riga bersaglio vince la colonna
 *   ALLINEATA/p più vicina, la colonna RICORDATA della riga è il fallback del rientro;
 * - le finestre orizzontali delle righe NON vengono riallineate dalla navigazione verticale
 *   (il vettore orizzontale è il BringIntoView nativo, minimo necessario);
 * - il ripristino dal Dettaglio resta ROW-SCOPED: se il titolo non vive più nella riga
 *   salvata NON si caccia nelle altre righe.
 *
 * Copre i difetti segnalati sul dispositivo:
 * - A: il ritorno dal Dettaglio atterrava su un'altra riga (quella successiva);
 * - B: il Giù/Su andava sulla PRIMA card della riga adiacente invece che sulla colonna coerente;
 * - C: il Su finiva in posizioni imprevedibili;
 * - D: la Home scorreva senza trasferire il focus alla riga adiacente (colonna non composta);
 * - E: dopo il ripristino la Home si spostava verticalmente con il focus già corretto.
 * - F: la navigazione verticale spostava in orizzontale le righe attraversate.
 *
 * Fixture: nessun URL di immagine (niente rete) e viewport 640x900dp con Hero. La riga Trending
 * Film è composta (4 card visibili per riga), Trending Serie appena visibile e Popolari
 * Film/Serie NON composte: così il rientro nella riga adiacente e l'attraversamento di righe
 * fuori finestra vengono esercitati davvero.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h900dp-tvdpi")
class HomeFocusNavigationUiTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val ROW_TRENDING_FILM = "section_trending_film"
    const val ROW_TRENDING_SERIE = "section_trending_serie"
    const val ROW_POPOLARI_FILM = "section_popolari_film"
    const val ROW_POPOLARI_SERIE = "section_popolari_serie"

    /** Anno marcatore: identifica la riga di appartenenza di una card (nessun titolo duplicato). */
    const val YEAR_TRENDING_FILM = 2001
    const val YEAR_TRENDING_SERIE = 2002
    const val YEAR_POPOLARI_FILM = 2003
    const val YEAR_POPOLARI_SERIE = 2004

    const val CARDS_PER_ROW = 12
  }

  // ---------------------------------------------------------------- fixture

  private fun yearOf(row: String): Int = when (row) {
    ROW_TRENDING_FILM -> YEAR_TRENDING_FILM
    ROW_TRENDING_SERIE -> YEAR_TRENDING_SERIE
    ROW_POPOLARI_FILM -> YEAR_POPOLARI_FILM
    else -> YEAR_POPOLARI_SERIE
  }

  private fun film(row: String, index: Int, title: String): MediaItem = MediaItem(
    id = "$row-${index + 1}",
    title = title,
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = yearOf(row),
    tmdbId = index + 1
  )

  private fun row(rowKey: String, prefix: String): List<MediaItem> =
    List(CARDS_PER_ROW) { index -> film(rowKey, index, "$prefix ${index + 1}") }

  private fun catalogs(
    duplicatedTitle: String? = null,
    duplicatedId: String? = null
  ): HomeCatalogs {
    val trendingMovies = row(ROW_TRENDING_FILM, "TF").toMutableList()
    val popularMovies = row(ROW_POPOLARI_FILM, "PF").toMutableList()
    if (duplicatedTitle != null && duplicatedId != null) {
      // Stesso titolo (stesso id stabile) in DUE righe: solo la riga salvata nel target deve
      // ripristinare il focus. L'anno marcatore distingue le due occorrenze nel test.
      val inTrending = film(ROW_TRENDING_FILM, 2, duplicatedTitle).copy(id = duplicatedId, tmdbId = 7)
      val inPopular = film(ROW_POPOLARI_FILM, 4, duplicatedTitle).copy(id = duplicatedId, tmdbId = 7)
      trendingMovies[2] = inTrending
      popularMovies[4] = inPopular
    }
    return HomeCatalogs(
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
      trendingMovies = trendingMovies,
      trendingSeries = row(ROW_TRENDING_SERIE, "TS"),
      popularMovies = popularMovies,
      popularSeries = row(ROW_POPOLARI_SERIE, "PS")
    )
  }

  /** Stato di ripristino equivalente a quello fornito da MainActivity al ritorno dal Dettaglio. */
  private var restoreTarget by mutableStateOf<Pair<String, String>?>(null)
  private var restoreToken by mutableStateOf(0)

  /** Cataloghi osservabili: consentono di simulare ondate dati reali durante i test di ripristino. */
  private var liveCatalogs by mutableStateOf<HomeCatalogs?>(null)

  private fun renderHome(catalogs: HomeCatalogs) {
    renderLiveCatalogs(catalogs)
  }

  private fun renderLiveCatalogs(initial: HomeCatalogs) {
    liveCatalogs = initial
    rule.setContent {
      CompositionLocalProvider(
        LocalLastFocusedMediaId provides restoreTarget?.second,
        LocalLastFocusedTarget provides restoreTarget,
        LocalFocusRestoreToken provides restoreToken,
        // Stesso contratto del ViewModel: il target viene consumato quando la card lo riceve.
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

  /** Titolo della card a fuoco, con l'anno marcatore della riga. */
  private fun focusedCard() = focusedTexts()

  @OptIn(ExperimentalTestApi::class)
  private fun focusCard(title: String) {
    rule.onNode(hasText(title)).requestFocus()
    rule.waitForIdle()
  }

  /** Inietta il tasto sul nodo che ha DAVVERO il focus: dopo uno scroll la card di partenza
   *  può uscire dalla composizione e non essere più un bersaglio valido per l'iniezione. */
  @OptIn(ExperimentalTestApi::class)
  private fun pressKey(key: Key, times: Int = 1) {
    repeat(times) {
      rule.onNode(isFocused()).performKeyInput { pressKey(key) }
      rule.waitForIdle()
    }
  }

  /** Attende (e verifica) che la card a fuoco sia esattamente quella attesa. */
  @OptIn(ExperimentalTestApi::class)
  private fun assertFocusedCard(title: String, year: Int) {
    val expectedYear = year.toString()
    rule.waitUntil(timeoutMillis = 5_000) {
      focusedCard().let { it.contains(title) && it.contains(expectedYear) }
    }
    rule.onNode(isFocused()).assertTextContains(title)
    rule.onNode(isFocused()).assertTextContains(expectedYear)
  }

  @OptIn(ExperimentalTestApi::class)
  private fun focusedTopPx(): Float =
    rule.onNode(isFocused()).fetchSemanticsNode().boundsInRoot.top

  // ---------------------------------------------------------------- difetti B / C / D / F

  @Test
  fun giuDaRigaMaiVisitataAtterraSuUnaCardDellaRigaAdiacente() {
    renderHome(catalogs())

    // Primo Giù dalla riga Trending Film: la riga Trending Serie NON è mai stata visitata,
    // quindi la ricerca nativa entra nel gruppo riga e atterra su una card a inizio riga della
    // riga adiacente (finestre allineate: nessuna colonna arbitraria, mai una riga saltata).
    focusCard("TF 1")
    pressKey(Key.DirectionDown)

    assertFocusedCard("TS 1", YEAR_TRENDING_SERIE)
  }

  @Test
  fun ilSuPrendeLaCardGeometricamenteVicinaSopraEDiscendeSullaColonnaAllineata() {
    renderHome(catalogs())

    // DISCESA: Trending Serie non ancora visitata -> colonna iniziale (finestre allineate,
    // distanza orizzontale nulla).
    focusCard("TF 1")
    pressKey(Key.DirectionDown)
    assertFocusedCard("TS 1", YEAR_TRENDING_SERIE)

    // La riga TS avanza in orizzontale fino alla colonna 5 (memoria di riga aggiornata da
    // onFocusChanged, HomeRowFocusCoordinator.lastFocusedColByRow).
    pressKey(Key.DirectionRight, times = 5)
    assertFocusedCard("TS 6", YEAR_TRENDING_SERIE)

    // RISALITA dalla colonna 5 della riga: sopra TS6 non c'è alcuna card allineata della
    // riga TF (la sua finestra è ferma sulle colonne 0-3), quindi il Su sceglie la card
    // GEOMETRICAMENTE più vicina sopra: TS6 (x ~fine riga) -> TF4 (l'ultima visibile). La
    // finestra orizzontale della riga TF NON si sposta (difetto F).
    pressKey(Key.DirectionUp)
    assertFocusedCard("TF 4", YEAR_TRENDING_FILM)

    // NUOVA DISCESA da TF4: il rientro nella riga TS che era già stata visitata atterra su
    // una card DI QUELLA RIGA in modo deterministico (colonne allineate per via geometrica o
    // colonna ricordata dalla riga: entrambe vie Nuvio/focusRestorer) — mai su un'altra riga
    // e mai con il D-pad morto (difetti B, C e D).
    pressKey(Key.DirectionDown)
    rule.waitUntil(timeoutMillis = 5_000) { focusedCard().contains("2002") }
    rule.onNode(isFocused()).assertTextContains("2002")
  }

  @Test
  fun laNavigazioneVerticaleNonSpostaInOrizzontaleLaRigaDiDestinazione() {
    renderHome(catalogs())

    // DISFETTO F: Su/Giù tra righe allineate NON deve riallineare le finestre orizzontali.
    focusCard("TF 1")
    pressKey(Key.DirectionDown)
    assertFocusedCard("TS 1", YEAR_TRENDING_SERIE)
    val ts3LeftBefore = rule.onNode(hasText("TS 3")).fetchSemanticsNode().boundsInRoot.left

    pressKey(Key.DirectionUp)
    assertFocusedCard("TF 1", YEAR_TRENDING_FILM)
    pressKey(Key.DirectionDown)
    assertFocusedCard("TS 1", YEAR_TRENDING_SERIE)

    val ts3LeftAfter = rule.onNode(hasText("TS 3")).fetchSemanticsNode().boundsInRoot.left
    assertEquals(
      "La navigazione verticale ha spostato in orizzontale la riga di destinazione",
      ts3LeftBefore.toDouble(),
      ts3LeftAfter.toDouble(),
      1.0
    )
  }

  @Test
  fun giuNonSaltaRigheAncheQuandoLaRigaDiDestinazioneNonEComposta() {
    renderHome(catalogs())

    // Popolari Film/Serie sono sotto il viewport: le loro righe non sono composte e il
    // rientro deve comunque scendere UNA RIGA per volta (beyond-bounds della LazyColumn).
    focusCard("TF 1")
    pressKey(Key.DirectionDown)
    assertFocusedCard("TS 1", YEAR_TRENDING_SERIE)

    pressKey(Key.DirectionDown)
    assertFocusedCard("PF 1", YEAR_POPOLARI_FILM)

    pressKey(Key.DirectionDown)

    assertFocusedCard("PS 1", YEAR_POPOLARI_SERIE)
  }

  // ---------------------------------------------------------------- difetti A / E

  @Test
  fun ritornoDalDettaglioRipristinaLaCardDellaRigaDiOrigine() {
    val catalogs = catalogs(duplicatedTitle = "TF Doppio", duplicatedId = "tf-dup")
    restoreTarget = ROW_TRENDING_FILM to "tf-dup"
    restoreToken = 1

    renderHome(catalogs)

    // Lo stesso titolo esiste anche in Popolari (stesso id): vince la riga di origine, e
    // l'anno marcatore della card a fuoco è la prova che il focus NON è finito nell'altra riga.
    assertFocusedCard("TF Doppio", YEAR_TRENDING_FILM)
  }

  @Test
  fun ritornoDalDettaglioNonRiallineaLaHomeSeLaCardTargetEraGiaVisibile() {
    renderHome(catalogs())

    // Pre-stato: il focus è su una card della riga Trending Film (visibile, non in cima alla
    // Home) e la card target è nella STESSA riga, già completamente visibile. Il ripristino
    // deve solo spostare il focus, senza riallineare la riga in cima alla Home.
    focusCard("TF 1")
    assertFocusedCard("TF 1", YEAR_TRENDING_FILM)
    val targetTopBefore = rule.onNode(hasText("TF 3")).fetchSemanticsNode().boundsInRoot.top

    restoreTarget = ROW_TRENDING_FILM to "section_trending_film-3"
    restoreToken = 2

    assertFocusedCard("TF 3", YEAR_TRENDING_FILM)
    assertEquals(
      "La Home ha riallineato la riga del target invece di limitarsi a spostare il focus",
      targetTopBefore.toDouble(),
      focusedTopPx().toDouble(),
      120.0
    )
  }

  @Test
  fun ritornoDalDettaglioRaggiungeUnaRigaFuoriDalViewport() {
    renderHome(catalogs())

    // Il titolo salvato vive in Popolari Serie: riga mai composta finché non la si raggiunge.
    // Il ripristino deve comporre la riga, la colonna giusta e solo allora spostare il focus.
    restoreTarget = ROW_POPOLARI_SERIE to "section_popolari_serie-5"
    restoreToken = 3
    rule.waitForIdle()

    assertFocusedCard("PS 5", YEAR_POPOLARI_SERIE)
  }

  @Test
  fun targetDiRipristinoSenzaDestinazioneNonBloccaLaHome() {
    renderHome(catalogs())

    // Target salvato per un titolo che non è più in Home (o per una riga non più disegnata):
    // le card devono restare focalizzabili, altrimenti la Home resta intrappolata.
    restoreTarget = ROW_TRENDING_FILM to "titolo-scomparso"
    restoreToken = 1
    rule.waitForIdle()

    focusCard("TF 1")
    pressKey(Key.DirectionDown)

    assertFocusedCard("TS 1", YEAR_TRENDING_SERIE)
  }

  @Test
  fun titoloSparitoDallaRigaSalvataNonRipristinaSuUnAltraRiga() {
    renderHome(catalogs())

    // La riga salvata (Popolari Serie) esiste ma non contiene più il titolo (l'id appartiene
    // a Trending Film): politica Nuvio — il ripristino NON caccia in un'altra riga (era la
    // causa del difetto "rientro sulla card di un'altra riga"). Il target viene rilasciato e
    // l'ancora di recupero assegna un punto di partenza deterministico (Hero, prima riga
    // interamente visibile); la Home resta navigabile.
    restoreTarget = ROW_POPOLARI_SERIE to "section_trending_film-4"
    restoreToken = 1
    rule.waitForIdle()

    rule.waitUntil(timeoutMillis = 5_000) { focusedTexts().isNotEmpty() }

    rule.onNode(isFocused()).assertTextContains("HERO 1")
    assertFocusedCard("HERO 1", 2099)
  }

  @Test
  fun ilTargetSenzaRigaRipristinaLaCardVisibileConQuellId() {
    // Target senza riga esplicita (retrocompatibilità con le superfici a griglia): resta
    // valido per la card con quell'id, ovunque si trovi.
    restoreTarget = FOCUS_ROW_ANY to "section_trending_film-3"
    restoreToken = 1

    renderHome(catalogs())

    assertFocusedCard("TF 3", YEAR_TRENDING_FILM)
  }

  // ---------------------------------------------------------------- P2: ripristino cooperativo

  @Test
  fun ripristiniConsecutiviAtterranoSullaCardDelTargetPiuRecente() {
    renderHome(catalogs())

    restoreTarget = ROW_TRENDING_FILM to "section_trending_film-3"
    restoreToken = 2
    assertFocusedCard("TF 3", YEAR_TRENDING_FILM)

    // Altro rientro dal Dettaglio su un titolo diverso: la richiesta più recente vince e il
    // vecchio target (già consumato dal focus) non può riacquisire il focus.
    restoreTarget = ROW_TRENDING_SERIE to "section_trending_serie-2"
    restoreToken = 3
    assertFocusedCard("TS 2", YEAR_TRENDING_SERIE)
  }

  @Test
  fun titoloRiordinatoNellaRigaVieneRitrovatoAllaNuovaPosizione() {
    val base = catalogs()
    val target = base.trendingMovies.first { it.id == "section_trending_film-6" }
    renderLiveCatalogs(base)

    // Primo ripristino: il titolo vive a colonna 5 della riga Trending Film.
    restoreTarget = ROW_TRENDING_FILM to "section_trending_film-6"
    restoreToken = 1
    assertFocusedCard("TF 6", YEAR_TRENDING_FILM)

    // Ondata di catalogo: lo stesso titolo si sposta a colonna 0. Il ripristino successivo
    // deve risolvere l'INDICE tramite l'identità (P1) e atterrare sulla stessa card.
    liveCatalogs = base.copy(trendingMovies = listOf(target) + (base.trendingMovies - target))
    restoreTarget = ROW_TRENDING_FILM to "section_trending_film-6"
    restoreToken = 2
    assertFocusedCard("TF 6", YEAR_TRENDING_FILM)
  }

  @Test
  fun ripristinoSenzaDestinazioneAssegnaAncoraDiFocusEHomeRestaNavigabile() {
    renderHome(catalogs())

    // Titolo non più presente in nessuna riga: il ripristino termina senza destinazione e
    // senza focus → l'ancora di recupero deve assegnare una cella (mai sotto D-pad morto).
    restoreTarget = ROW_TRENDING_FILM to "titolo-scomparso"
    restoreToken = 1
    rule.waitForIdle()

    rule.waitUntil(timeoutMillis = 5_000) { focusedTexts().isNotEmpty() }

    // Il D-pad è vivo: un GIÙ dall'ancora (riga interamente visibile in cima: Hero) sposta
    // realmente il focus su un altro nodo.
    val anchorTop = focusedTopPx()
    pressKey(Key.DirectionDown)
    rule.waitForIdle()
    val afterDownTop = focusedTopPx()
    assertTrue(
      "D-pad morto dopo un ripristino fallito: il focus non si è spostato dall'ancora",
      kotlin.math.abs(anchorTop - afterDownTop) > 0.5f
    )
  }
}
