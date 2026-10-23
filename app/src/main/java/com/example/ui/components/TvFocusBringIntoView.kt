package com.example.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Bring-into-view spec TV condiviso per contenitori scrollabili (LazyVerticalGrid, LazyColumn).
 *
 * Risolve i problemi di navigazione TV con telecomando (D-pad):
 * 1. Risalita verticale (D-Pad UP): garantisce che la prima riga di poster o qualsiasi card
 *    mantenga sempre visibile l'header con il titolo della sezione (offset di almeno 48-64dp).
 *    Il titolo della sezione non viene mai tagliato né spinto fuori dallo schermo.
 * 2. Spostamenti orizzontali (LEFT/RIGHT): tra card della STESSA riga, il calcolo verticale
 *    restituisce ESATTAMENTE 0f, azzerando qualsiasi micro-sobbalzo o scatto verticale.
 * 3. Discesa verticale (D-Pad DOWN): porta la riga in vista garantendo margine inferiore adeguato.
 */
@OptIn(ExperimentalFoundationApi::class)
open class TvFocusBringIntoViewSpec(
  private val parentFraction: Float = 0.3f,
  private val topHeaderOffsetPx: Float = 96f,
  private val bottomMarginPx: Float = 48f
) : BringIntoViewSpec {

  companion object : TvFocusBringIntoViewSpec(
    parentFraction = 0.3f,
    topHeaderOffsetPx = 96f,
    bottomMarginPx = 48f
  )

  override val scrollAnimationSpec: AnimationSpec<Float> =
    tween(durationMillis = 150, easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f))

  override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
    if (size <= 0f || containerSize <= 0f) return 0f

    val trailingEdge = offset + size

    // Tolleranza minima per precisione subpixel float (1px)
    // Se l'elemento e il suo margine sopra per l'header sono già completamente visibili nel viewport,
    // azzeriamo lo scroll (0f) per evitare sobbalzi durante spostamenti LEFT/RIGHT o ricalcoli di scroll.
    val isCompletelyVisible = (offset >= topHeaderOffsetPx - 1f) && (trailingEdge <= containerSize - bottomMarginPx + 1f)
    if (isCompletelyVisible) {
      return 0f
    }

    // Caso A: L'elemento si trova troppo in alto o risale verso l'alto (D-Pad UP).
    // Per non tagliare o far sparire l'header/titolo della sezione, manteniamo sempre
    // almeno `topHeaderOffsetPx` sopra la card focalizzata.
    if (offset < topHeaderOffsetPx) {
      return offset - topHeaderOffsetPx
    }

    // Caso B: L'elemento si trova troppo in basso (D-Pad DOWN).
    // Scrolliamo verso il basso per mostrare la card completa con margine inferiore.
    if (trailingEdge > containerSize - bottomMarginPx) {
      val scrollDownDistance = trailingEdge - (containerSize - bottomMarginPx)
      // Evitiamo che uno scroll down eccessivo spinga la parte alta della riga oltre topHeaderOffsetPx
      val maxScrollDown = (offset - topHeaderOffsetPx).coerceAtLeast(0f)
      return if (size > (containerSize - topHeaderOffsetPx - bottomMarginPx)) {
        offset - topHeaderOffsetPx
      } else {
        scrollDownDistance.coerceAtMost(maxScrollDown)
      }
    }

    return 0f
  }
}

/**
 * Bring-into-view neutro per le righe orizzontali (LazyRow) annidate dentro contenitori
 * verticali protetti da un guard (come [TvFocusBringIntoViewSpec] con `return 0f`).
 *
 * Il guard dei sistemi TV azzera `calculateScrollDistance` sui tasti LEFT/RIGHT per tenere la
 * testata ferma: se lo stesso spec arriva anche alla LazyRow, lo scorrimento orizzontale viene
 * bloccato e i poster non avanzano mai verso destra. Fornendo questo spec SOLO all'interno della
 * riga, la LazyRow rivela i poster successivi con lo scorrimento nativo (asse X), mentre il
 * contenitore verticale esterno resta governato dal proprio guard: la testata non si muove.
 */
@OptIn(ExperimentalFoundationApi::class)
object TvFocusRowBringIntoViewSpec : BringIntoViewSpec {

  override val scrollAnimationSpec: AnimationSpec<Float> =
    tween(durationMillis = 150, easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f))

  override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
    if (size <= 0f || containerSize <= 0f) return 0f

    val trailingEdge = offset + size
    return when {
      // Elemento fuori dal bordo iniziale: riportalo in vista (valore negativo).
      offset < 0f -> offset
      // Elemento fuori dal bordo finale: scorri in avanti solo quanto basta (positivo).
      trailingEdge > containerSize -> trailingEdge - containerSize
      // Gi completamente visibile: nessuno scorrimento, niente sobbalzi.
      else -> 0f
    }
  }
}

/**
 * Bring-into-view TV per GRIGLIE di poster (LazyVerticalGrid) il cui viewport e' appena piu'
 * alto di una riga di card (schermate Cerca/Provider: 1-2 righe visibili).
 *
 * Perche' non riusa [TvFocusBringIntoViewSpec]: quello spec aggancia la riga focalizzata a un
 * BORDO del viewport (D-pad DOWN -> fondo area risultati, UP -> 64dp dall'alto). Con un
 * viewport che contiene poco piu' di una riga questo produce lo scorrimento difettoso
 * segnalato: la riga in ingresso resta incollata al bordo inferiore, la riga precedente resta
 * tagliata a meta' sull'altro bordo e le card sembrano tagliate/posizionate male.
 *
 * Questo spec invece porta la riga focalizzata AL CENTRO del viewport quando la riga ENTRA in
 * scena (non era interamente visibile all'inizio della richiesta):
 * 1. le card in ingresso si fermano al centro, completamente visibili, con lo spazio libero
 *    distribuito sopra e sotto;
 * 2. le righe precedenti escono progressivamente dal bordo superiore (e quelle successive da
 *    quello inferiore) tramite il normale scorrimento, senza sovrapposizioni;
 * 3. il clipping avviene solo ai confini reali del viewport: la riga focalizzata non tocca mai
 *    un bordo, quindi non viene tagliata;
 * 4. se la riga era GIA' interamente visibile quando riceve il focus (es. si preme LEFT/RIGHT
 *    nella stessa riga, o la card e' ripristinata dal Dettaglio), lo spec restituisce 0f e la
 *    griglia non si muove: nessun micro-sobbalzo verticale.
 *
 * Il ritorno a 0f e' anche il segnale che Compose usa per considerare soddisfatta la richiesta
 * bring-into-view: durante il centraggio della riga in ingresso va quindi continuato a
 * restituire la distanza anche quando la riga e' gia' "visibile", altrimenti l'animazione si
 * fermerebbe a meta' strada incollando la riga al bordo inferiore.
 *
 * Il centraggio e' clampato NATIVAMENTE dal range di scroll della griglia, quindi la prima e
 * l'ultima riga restano ai bordi (con il contentPadding inferiore) senza mai tagliare le card
 * che devono essere visualizzate per intero. Se una riga fosse piu' alta del viewport (non
 * centrabile) viene allineata in alto.
 *
 * L'istanza e' VOLUTAMENTE stateful (una per griglia, creata da [TvFocusGridBringIntoView]):
 * lo stato ricorda solo se e' in corso il centraggio di una riga in ingresso.
 */
@OptIn(ExperimentalFoundationApi::class)
class TvFocusGridBringIntoViewSpec : BringIntoViewSpec {

  /**
   * Tolleranza subpixel: il centraggio deve essere preciso. Su un TV 1080p il viewport della
   * griglia e' solo poco piu' alto di una riga di card e l'ingrandimento del focus (1.06)
   * consuma quasi tutto lo spazio libero: fermarsi a qualche pixel dal centro farebbe uscire
   * la card focalizzata dal bordo inferiore (anno tagliato). Gli spostamenti orizzontali non
   * dipendono da questa tolleranza (v. il ramo "riga gia' interamente visibile").
   */
  private val centerTolerancePx: Float = 1f

  /**
   * Meta' dell'ingrandimento applicato alla card focalizzata ([PosterMediaCard] scala a 1.06
   * attorno al centro): il rect bring-into-view della card in focus e' quello GIA' scalato,
   * quindi il suo bordo superiore e' spostato in alto di `size * 0.03`. Compensando, il centro
   * VISIVO della riga coincide con il centro del viewport.
   */
  private val focusScaleHalfGrowth: Float = 0.03f

  /** True mentre stiamo centrando una riga che e' entrata in scena dal bordo. */
  private var centering = false

  override val scrollAnimationSpec: AnimationSpec<Float> =
    tween(durationMillis = 150, easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f))

  override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
    if (size <= 0f || containerSize <= 0f) {
      centering = false
      return 0f
    }

    // Riga piu' alta del viewport: non e' centrabile, allineala in alto.
    if (size >= containerSize) {
      centering = false
      return offset
    }

    val centeredOffset = (containerSize - size) / 2f - size * focusScaleHalfGrowth
    val distance = offset - centeredOffset

    if (centering) {
      if (abs(distance) <= centerTolerancePx) {
        centering = false
        return 0f
      }
      return distance
    }

    // Riga gia' interamente visibile: nessuno scroll (LEFT/RIGHT, ripristino dal Dettaglio,
    // riposizionamenti di layout). Solo una riga in ingresso va centrata.
    val fullyVisible = offset >= -1f && offset + size <= containerSize + 1f
    if (fullyVisible) return 0f

    centering = true
    return distance
  }
}

/**
 * Applica [TvFocusGridBringIntoViewSpec] a una griglia di card (LazyVerticalGrid). Da usare
 * al posto di [TvFocusBringIntoView] quando il viewport contiene solo poche righe di poster.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvFocusGridBringIntoView(content: @Composable () -> Unit) {
  val spec = remember { TvFocusGridBringIntoViewSpec() }
  CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
    content()
  }
}

/**
 * Fornisce [TvFocusRowBringIntoViewSpec] al contenuto: va avvolto attorno a ogni LazyRow
 * orizzontale collocata dentro uno schermo con guard verticale, affinche' il D-pad LEFT/RIGHT
 * scorra nativamente la riga fino all'ultimo elemento.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvFocusRow(content: @Composable () -> Unit) {
  CompositionLocalProvider(LocalBringIntoViewSpec provides TvFocusRowBringIntoViewSpec) {
    content()
  }
}

/**
 * Applica [TvFocusBringIntoViewSpec] a un contenitore scrollabile (es. LazyColumn, LazyVerticalGrid).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvFocusBringIntoView(
  topOffsetDp: Dp = 64.dp,
  bottomMarginDp: Dp = 48.dp,
  content: @Composable () -> Unit
) {
  val density = LocalDensity.current
  val topOffsetPx = with(density) { topOffsetDp.toPx() }
  val bottomMarginPx = with(density) { bottomMarginDp.toPx() }
  val spec = remember(topOffsetPx, bottomMarginPx) {
    TvFocusBringIntoViewSpec(
      parentFraction = 0.3f,
      topHeaderOffsetPx = topOffsetPx,
      bottomMarginPx = bottomMarginPx
    )
  }
  CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
    content()
  }
}
