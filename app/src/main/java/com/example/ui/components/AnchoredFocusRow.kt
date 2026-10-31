package com.example.ui.components

import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Navigazione orizzontale "a fuoco fisso" delle file della Home TV.
 *
 * MODELLO
 * - Il riquadro di selezione è ANCORATO a sinistra: la card selezionata viene portata al
 *   margine sinistro della riga (lo stesso offset del primo poster) e lì resta.
 * - Premendo RIGHT/LEFT è la RIGA a scorrere sotto il riquadro, di esattamente un passo
 *   (larghezza card + spaziatura); il focus reale viene spostato sulla card che arriva
 *   nell'ancora, quindi il poster evidenziato è sempre quello davvero focalizzato
 *   (nessun overlay slegato dal focus di Compose).
 * - Durante lo scorrimento le decorazioni di focus delle card sono sospese e il riquadro
 *   ancorato è disegnato dall'overlay [AnchoredSelectionFrame]: il riquadro non si muove,
 *   sono i poster a scorrere sotto di esso. Al termine dell'animazione l'overlay sfuma e
 *   la decorazione reale della card focalizzata (stessa geometria) prende il posto.
 *
 * PERCHÉ SERVE UN OVERLAY: se la cornice fosse solo quella della card, viaggerebbe con la
 * card dalla posizione i+1 all'ancora, cioè il riquadro si sposterebbe visibilmente a ogni
 * pressione. L'overlay è sincronizzato con la card che ha DAVVERO il focus (il suo indice è
 * il target dello step) e viene mostrato solo durante la transizione.
 *
 * EVENTI RIPETUTI: finché uno step è in volo ogni RIGHT/LEFT viene consumato (nessuna
 * animazione accavallata). I ripetuti orizzontali arrivano qui solo perché
 * `dpadVerticalFastScroll` delega le file ancorate (vedi [AnchoredRowKeyState]).
 */

/** Direzione di uno step orizzontale della fila ancorata. */
enum class AnchoredRowDirection { PREVIOUS, NEXT }

/**
 * Geometria del riquadro di selezione di una riga.
 *
 * - [Poster]: il riquadro coincide con la locandina 2:3 (prima elementare della card).
 * - [WholeItem]: il riquadro coincide con l'intera card (16:9 «Continua a guardare»,
 *   card backdrop standard con titolo sotto).
 */
sealed interface AnchoredCardFrame {
  val width: Dp

  data class Poster(override val width: Dp) : AnchoredCardFrame

  data class WholeItem(override val width: Dp) : AnchoredCardFrame
}

/** Stile del riquadro ancorato: deve combaciare con la decorazione di focus della card. */
enum class AnchoredFrameStyle {
  /** Alone Neon + bordo luminoso (PosterMediaCard / StandardMediaCard). */
  NeonGlow,

  /** Bordo a gradiente ciano (TvFocusableBox usato da ContinueWatchingCard). */
  GradientBorder
}

/** Richiesta di step in volo: direzione + indice di destinazione. */
data class AnchoredStepRequest(
  val direction: AnchoredRowDirection,
  val targetIndex: Int
)

/**
 * Indice di destinazione di uno step, oppure `null` ai confini (primo/ultimo poster).
 * Funzione pura per i test.
 */
internal fun resolveAnchoredStepTarget(
  currentIndex: Int,
  itemCount: Int,
  direction: AnchoredRowDirection
): Int? {
  if (itemCount <= 0) return null
  val current = currentIndex.coerceIn(0, itemCount - 1)
  val target = when (direction) {
    AnchoredRowDirection.NEXT -> current + 1
    AnchoredRowDirection.PREVIOUS -> current - 1
  }
  return target.takeIf { it in 0 until itemCount }
}

/**
 * Stato condiviso tra la fila ancorata e `MainActivity`.
 *
 * I tasti D-pad raggiungono PRIMA il preview di `MainActivity`, che con la sidebar chiusa
 * intercetta ogni LEFT per spostare il focus sulla sidebar: senza questo stato il LEFT non
 * raggiungerebbe mai la riga e non sarebbe possibile tornare al poster precedente. Stesso
 * modello già usato da [HeroCarouselState].
 *
 * Registra l'ULTIMA riga ancorata che ha ricevuto il focus: solo quella può decidere se il
 * LEFT appartiene alla riga (indice > 0) o alla navigazione laterale (indice 0).
 */
object AnchoredRowKeyState {

  private var focusedRow: AnchoredRowController? = null

  /** `true` se il LEFT deve essere lasciato passare dal preview di `MainActivity`. */
  val consumesLeftKey: Boolean
    get() = focusedRow?.let { it.rowHasFocus && it.wantsLeftKey } == true

  /** `true` se il focus è su una riga ancorata (i ripetuti orizzontali spettano a lei). */
  val hasFocusedRow: Boolean
    get() = focusedRow?.rowHasFocus == true

  fun onRowFocused(row: AnchoredRowController) {
    focusedRow = row
  }

  fun onRowFocusLost(row: AnchoredRowController) {
    if (focusedRow === row) focusedRow = null
  }

  /** Solo per i test: chiave della riga ancorata che detiene il focus. */
  internal val focusedRowKeyForTest: String?
    get() = focusedRow?.rowKey
}

/** Controller osservabile di una riga ancorata. */
class AnchoredRowController(val rowKey: String) {

  /** Indice della card che ha DAVVERO il focus in questa riga. */
  var currentIndex by mutableIntStateOf(0)
    private set

  /** Step orizzontale in volo, `null` a riposo. Osservabile: guida l'effetto di animazione. */
  var pendingStep by mutableStateOf<AnchoredStepRequest?>(null)
    private set

  /** `true` quando la riga (il suo gruppo di focus) contiene il focus. */
  var rowHasFocus by mutableStateOf(false)
    private set

  val isTransitioning: Boolean
    get() = pendingStep != null

  val wantsLeftKey: Boolean
    get() = isTransitioning || currentIndex > 0

  /** Aggiornato dal `onFocusChanged` di ogni card della riga. */
  fun onCardFocused(index: Int) {
    currentIndex = index
    AnchoredRowKeyState.onRowFocused(this)
  }

  /** Focus entrato/uscito dalla riga (a livello di gruppo). */
  fun onRowFocusChanged(focused: Boolean) {
    rowHasFocus = focused
    if (focused) {
      AnchoredRowKeyState.onRowFocused(this)
    } else {
      AnchoredRowKeyState.onRowFocusLost(this)
      // Un movimento verticale durante l'animazione non deve trascinare il focus della riga
      // al termine dello scroll: lo step viene annullato e la riga torna sull'indice reale.
      pendingStep = null
    }
  }

  /**
   * Avvia uno step. Ritorna `null` se un passo è già in volo (protezione dai ripetuti).
   */
  internal fun beginStep(targetIndex: Int, direction: AnchoredRowDirection): AnchoredStepRequest? {
    if (pendingStep != null) return null
    val request = AnchoredStepRequest(direction, targetIndex)
    pendingStep = request
    return request
  }

  internal fun finishStep(request: AnchoredStepRequest) {
    if (pendingStep === request) pendingStep = null
  }
}

/**
 * `true` durante uno step della riga: le card sospendono la propria decorazione di focus
 * (bordo/alone/scala) per lasciare il riquadro ancorato fermo.
 */
val LocalAnchoredRowFocusDecorationSuppressed = compositionLocalOf { false }

/** Controller della riga ancorata corrente, consumato da `Modifier.rowFocusProperties`. */
val LocalAnchoredRowController = compositionLocalOf<AnchoredRowController?> { null }

/** Tag semantico del riquadro ancorato (verifiche di regressione UI). */
internal fun anchoredSelectionFrameTestTag(rowKey: String): String =
  "anchored_selection_frame_$rowKey"

/**
 * Bring-into-view NEUTRO per le file ancorate: lo scorrimento orizzontale è governato solo
 * da questo componente. Il reveal nativo (compreso il rect esplicito di alcune card) non
 * deve muovere la riga, altrimenti compete con l'animazione dello step.
 */
@OptIn(ExperimentalFoundationApi::class)
private object AnchoredRowBringIntoViewSpec : BringIntoViewSpec {
  override val scrollAnimationSpec: AnimationSpec<Float> =
    tween(durationMillis = ANCHORED_STEP_DURATION_MS, easing = FastOutSlowInEasing)

  override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

private const val ANCHORED_STEP_DURATION_MS = 200
private const val ANCHORED_HANDOFF_FADE_MS = 160
private const val ANCHORED_FOCUS_CONFIRM_TIMEOUT_MS = 120L
private const val ANCHORED_MOUNT_TIMEOUT_MS = 300L
private const val ANCHORED_FOCUS_ATTEMPTS = 4
private const val ANCHORED_ALIGN_TOLERANCE_PX = 1f

/**
 * Riga orizzontale con riquadro di selezione ancorato a sinistra.
 *
 * @param rowKey chiave logica della riga (registro globale del LEFT + tag di test).
 * @param itemCount numero di card della riga.
 * @param rowState stato di scroll persistente della LazyRow.
 * @param contentPadding padding con cui era disegnata la riga (lo start definisce l'ancora).
 * @param horizontalSpacing spaziatura tra le card (uguale all'`Arrangement`).
 * @param frame geometria del riquadro di selezione.
 * @param frameStyle stile del riquadro (deve combaciare con la decorazione della card).
 * @param focusedScale scala applicata dalla card al focus (riprodotta dall'overlay).
 * @param onRequestFocus richiesta di focus sulla card [index] (requester del coordinatore).
 * @param onIsIndexFocused conferma REALE del focus sulla card [index].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AnchoredFocusRow(
  rowKey: String,
  itemCount: Int,
  rowState: LazyListState,
  contentPadding: PaddingValues,
  horizontalSpacing: Dp,
  frame: AnchoredCardFrame,
  frameStyle: AnchoredFrameStyle,
  focusedScale: Float,
  onRequestFocus: (Int) -> Unit,
  onIsIndexFocused: (Int) -> Boolean,
  onIsIndexAttached: (Int) -> Boolean,
  /**
   * Indice della card salvata quando la Home sta ripristinando il focus al ritorno dal
   * Dettaglio (`null` fuori dal ripristino). La riga porta quella card ESATTAMENTE sull'ancora
   * appena compare nel layout, senza aspettare il focus: è il riallineamento richiesto dal
   * rientro, e vale anche se la riga si è rimontata con una finestra diversa.
   */
  restoreIndex: Int? = null,
  modifier: Modifier = Modifier,
  content: LazyListScope.() -> Unit
) {
  val controller = remember(rowKey) { AnchoredRowController(rowKey) }
  val density = LocalDensity.current
  val layoutDirection = LocalLayoutDirection.current

  val startPadding = contentPadding.calculateStartPadding(layoutDirection)
  val endPadding = contentPadding.calculateEndPadding(layoutDirection)
  val topPadding = contentPadding.calculateTopPadding()
  val bottomPadding = contentPadding.calculateBottomPadding()

  // La riga viene ritagliata a sinistra esattamente dove finisce la card precedente: così il
  // margine dell'ancora resta pulito e non appare uno spicchio della card che precede.
  val clipInset = (startPadding - horizontalSpacing).coerceAtLeast(0.dp)
  val innerStart = startPadding - clipInset

  val spacingPx = with(density) { horizontalSpacing.toPx() }
  val itemWidthPx = with(density) { frame.width.toPx() }
  val stepPitchPx = itemWidthPx + spacingPx
  val frameXPx = with(density) { startPadding.toPx() }
  val topPx = with(density) { topPadding.toPx() }
  val bottomPx = with(density) { bottomPadding.toPx() }

  var rowHeightPx by remember { mutableIntStateOf(0) }
  val frameAlpha = remember(rowKey) { Animatable(0f) }

  DisposableEffect(controller) {
    onDispose { AnchoredRowKeyState.onRowFocusLost(controller) }
  }

  /**
   * Porta la card [index] esattamente sull'ancora.
   *
   * In coordinate di contenuto della LazyList l'ancora è l'offset 0: `scrollToItem(index)`
   * lascia la card al content padding iniziale, cioè proprio la posizione del primo poster.
   */
  suspend fun alignToAnchor(index: Int) {
    if (index !in 0 until itemCount) return
    val info = rowState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (info != null) {
      if (abs(info.offset) > ANCHORED_ALIGN_TOLERANCE_PX) {
        rowState.scrollBy(info.offset.toFloat())
      }
    } else {
      rowState.scrollToItem(index)
    }
  }

  suspend fun scrollIndexToAnchorAnimated(index: Int, fromIndex: Int) {
    val info = rowState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    val delta = when {
      info != null -> info.offset.toFloat()
      index > fromIndex -> stepPitchPx
      else -> -stepPitchPx
    }
    if (abs(delta) >= 0.5f) {
      rowState.animateScrollBy(
        delta,
        tween(durationMillis = ANCHORED_STEP_DURATION_MS, easing = FastOutSlowInEasing)
      )
    }
    // Arrotondamenti o clamp del range di scroll: chiude il posizionamento sull'ancora.
    val settled = rowState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (settled != null && abs(settled.offset) > ANCHORED_ALIGN_TOLERANCE_PX) {
      rowState.scrollBy(settled.offset.toFloat())
    }
  }

  // Step in volo: eseguito da un unico effetto per riga. Se la riga perde il focus (movimento
  // verticale, apertura del popup, cambio schermata) `pendingStep` viene azzerato e l'effetto
  // viene cancellato; il `finally` riallinea la riga sull'indice che ha DAVVERO il focus.
  val pendingStep = controller.pendingStep
  LaunchedEffect(pendingStep, itemCount) {
    val request = pendingStep ?: return@LaunchedEffect
    var completed = false
    try {
      completed = executeAnchoredStep(
        request = request,
        rowState = rowState,
        currentIndex = { controller.currentIndex },
        itemCount = itemCount,
        onRequestFocus = onRequestFocus,
        onIsIndexFocused = onIsIndexFocused,
        onIsIndexAttached = onIsIndexAttached,
        scrollToAnchorAnimated = { index, from -> scrollIndexToAnchorAnimated(index, from) }
      )
    } finally {
      if (!completed) {
        withContext(NonCancellable) { alignToAnchor(controller.currentIndex) }
      }
      controller.finishStep(request)
    }
  }

  // Rientro/atterraggio su una card non ancorata (navigazione verticale, ripristino dal
  // Dettaglio, riordino della lista): la si porta subito sull'ancora, senza animazione.
  LaunchedEffect(controller.currentIndex, controller.rowHasFocus, controller.isTransitioning, itemCount) {
    if (!controller.rowHasFocus || controller.isTransitioning) return@LaunchedEffect
    alignToAnchor(controller.currentIndex)
  }

  /**
   * RIPRISTINO DAL DETTAGLIO: la card salvata deve tornare ESATTAMENTE sull'ancora fissa.
   *
   * Perché un effetto dedicato (e non il solo focus): al ritorno la LazyRow può rimontarsi con
   * una finestra diversa da quella della card salvata, e il protocollo della Home chiede il
   * focus PRIMA che la riga abbia finito di assestarsi. Qui la card viene portata sull'ancora
   * in modo deterministico (`scrollToItem` la compone e la posiziona sull'ancora se è fuori
   * finestra, `scrollBy` la riallinea se è già composta), così il focus la raggiunge ferma nel
   * punto giusto. L'effetto vive solo finché il ripristino è in corso: quando la richiesta
   * viene soddisfatta `restoreIndex` torna `null` e la navigazione normale (step orizzontali
   * inclusi) riprende il controllo.
   */
  LaunchedEffect(restoreIndex, itemCount) {
    val index = restoreIndex ?: return@LaunchedEffect
    if (index !in 0 until itemCount) return@LaunchedEffect
    alignToAnchor(index)
  }

  // Riordino della lista con la riga focalizzata: il focus segue la CARD (key stabile), quindi
  // l'indice selezionato viene riallineato per identità e la riga riporta quella card
  // sull'ancora. Nessun indice inventato quando la card non è più in riga.
  var selectedKey by remember(rowKey) { mutableStateOf<Any?>(null) }
  var selectedKeyIndex by remember(rowKey) { mutableIntStateOf(-1) }
  LaunchedEffect(controller.currentIndex, controller.rowHasFocus) {
    if (!controller.rowHasFocus) return@LaunchedEffect
    val info = rowState.layoutInfo.visibleItemsInfo
      .firstOrNull { it.index == controller.currentIndex }
    if (info != null) {
      selectedKey = info.key
      selectedKeyIndex = controller.currentIndex
    }
  }
  LaunchedEffect(rowState, controller) {
    snapshotFlow {
      val key = selectedKey ?: return@snapshotFlow null
      // La chiave vale solo per l'indice con cui è stata registrata: durante il passaggio di
      // focus da una card all'altra la coppia può essere ancora quella precedente e non deve
      // riportare indietro la selezione.
      if (selectedKeyIndex != controller.currentIndex) return@snapshotFlow null
      rowState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.index
    }.collect { relocated ->
      if (relocated != null &&
        relocated != controller.currentIndex &&
        !controller.isTransitioning &&
        itemCount > 0
      ) {
        controller.onCardFocused(relocated)
        if (controller.rowHasFocus) alignToAnchor(relocated)
      }
    }
  }

  // Overlay: subito opaco allo start dello step, in dissolvenza al termine (la decorazione
  // reale della card riappare sotto, con la stessa geometria).
  LaunchedEffect(controller.isTransitioning) {
    if (controller.isTransitioning) {
      frameAlpha.snapTo(1f)
    } else if (frameAlpha.value > 0f) {
      frameAlpha.animateTo(
        0f,
        tween(durationMillis = ANCHORED_HANDOFF_FADE_MS, easing = FastOutSlowInEasing)
      )
    }
  }

  BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    val rowWidth = if (maxWidth.value.isFinite()) maxWidth - clipInset else 0.dp
    // Padding finale extra: consente anche all'ULTIMA card di raggiungere l'ancora (senza,
    // il range di scroll si esaurirebbe prima e il riquadro si sposterebbe sul fondo fila).
    val extraEnd = if (rowWidth > 0.dp) {
      (rowWidth - innerStart - frame.width - endPadding).coerceAtLeast(0.dp)
    } else {
      0.dp
    }
    val effectiveEnd = endPadding + extraEnd
    val effectiveContentPadding = PaddingValues(
      start = innerStart,
      top = topPadding,
      end = effectiveEnd,
      bottom = bottomPadding
    )

    val frameHeightPx = when (frame) {
      is AnchoredCardFrame.Poster -> itemWidthPx * 1.5f
      is AnchoredCardFrame.WholeItem -> (rowHeightPx - topPx - bottomPx).coerceAtLeast(0f)
    }
    val overlayVisible = (controller.isTransitioning || frameAlpha.value > 0.001f) &&
      rowHeightPx > 0 &&
      itemCount > 0
    val overlayAlpha = if (controller.isTransitioning) 1f else frameAlpha.value

    CompositionLocalProvider(
      LocalAnchoredRowController provides controller,
      LocalAnchoredRowFocusDecorationSuppressed provides controller.isTransitioning,
      LocalBringIntoViewSpec provides AnchoredRowBringIntoViewSpec
    ) {
      LazyRow(
        state = rowState,
        modifier = modifier
          .fillMaxWidth()
          .padding(start = clipInset)
          .clipToBounds()
          .onSizeChanged { rowHeightPx = it.height }
          .onFocusChanged { controller.onRowFocusChanged(it.hasFocus) }
          .anchoredStepKeys(controller = controller, itemCount = itemCount),
        contentPadding = effectiveContentPadding,
        horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
        // Il viewport si muove solo tramite gli step ancorati: nessun drag/scroll libero può
        // scollare la card selezionata dall'ancora.
        userScrollEnabled = false,
        content = content
      )
    }

    if (overlayVisible) {
      AnchoredSelectionFrame(
        alpha = overlayAlpha,
        style = frameStyle,
        scale = focusedScale,
        modifier = Modifier
          .offset { IntOffset(frameXPx.roundToInt(), topPx.roundToInt()) }
          .size(
            width = frame.width,
            height = with(density) { frameHeightPx.toDp() }
          )
          .zIndex(20f)
          .testTag(anchoredSelectionFrameTestTag(rowKey))
      )
    }
  }
}

/**
 * Gestione dei tasti orizzontali della riga ancorata.
 *
 * - RIGHT all'ultima card: consumato (il focus non esce dalla fila).
 * - LEFT alla prima card: NON consumato, così `MainActivity`/la sidebar continuano a gestire
 *   l'uscita a sinistra come prima.
 * - Durante uno step ogni RIGHT/LEFT è consumato: nessuna animazione accavallata.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.anchoredStepKeys(
  controller: AnchoredRowController,
  itemCount: Int
): Modifier = onPreviewKeyEvent { event ->
  val native = event.nativeKeyEvent
  val keyCode = native.keyCode
  val direction = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_RIGHT -> AnchoredRowDirection.NEXT
    KeyEvent.KEYCODE_DPAD_LEFT -> AnchoredRowDirection.PREVIOUS
    else -> return@onPreviewKeyEvent false
  }

  val isFirstItemLeft = direction == AnchoredRowDirection.PREVIOUS &&
    !controller.isTransitioning &&
    controller.currentIndex == 0

  if (native.action == KeyEvent.ACTION_UP) {
    // Il rilascio appartiene alla riga esattamente quando il DOWN apparteneva alla riga.
    return@onPreviewKeyEvent !isFirstItemLeft
  }
  if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
  if (isFirstItemLeft) return@onPreviewKeyEvent false

  // Protezione dai ripetuti durante l'animazione: il tasto viene assorbito.
  if (controller.isTransitioning) return@onPreviewKeyEvent true

  val target = resolveAnchoredStepTarget(controller.currentIndex, itemCount, direction)
    ?: return@onPreviewKeyEvent true // confine della fila: niente spostamenti errati
  controller.beginStep(target, direction)
  true
}

/**
 * Esecuzione di uno step: sposta il focus reale e scorre la riga sotto il riquadro ancorato.
 *
 * L'ordine dipende dalla direzione perché `requestFocus` può essere emesso solo su un nodo
 * già composto:
 * - NEXT: la card successiva è composta (è visibile subito a destra dell'ancora) → prima il
 *   focus, poi lo scroll; la card che esce non è più focalizzata.
 * - PREVIOUS: la card precedente è ritagliata dal bordo sinistro (riquadro pulito) → prima lo
 *   scroll, che la porta in vista, poi il focus; la card di partenza resta focalizzata e
 *   visibile per tutta l'animazione.
 */
private suspend fun executeAnchoredStep(
  request: AnchoredStepRequest,
  rowState: LazyListState,
  currentIndex: () -> Int,
  itemCount: Int,
  onRequestFocus: (Int) -> Unit,
  onIsIndexFocused: (Int) -> Boolean,
  onIsIndexAttached: (Int) -> Boolean,
  scrollToAnchorAnimated: suspend (index: Int, fromIndex: Int) -> Unit
): Boolean {
  val target = request.targetIndex
  if (target !in 0 until itemCount) return false
  val from = currentIndex()

  suspend fun awaitAttached(): Boolean =
    withTimeoutOrNull(ANCHORED_MOUNT_TIMEOUT_MS) {
      snapshotFlow { onIsIndexAttached(target) }.first { it }
    } ?: false

  suspend fun requestConfirmed(): Boolean {
    // La card può essere stata appena composta dallo scroll: il requester viene agganciato
    // nel frame di composizione. Pochi tentativi (non un loop infinito) coprono quel ritardo.
    repeat(ANCHORED_FOCUS_ATTEMPTS) {
      try {
        onRequestFocus(target)
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (_: Exception) {
        // Requester non ancora inizializzato: si riprova al frame successivo.
      }
      val confirmed = withTimeoutOrNull(ANCHORED_FOCUS_CONFIRM_TIMEOUT_MS) {
        snapshotFlow { onIsIndexFocused(target) }.first { it }
      } ?: false
      if (confirmed) return true
      withFrameNanos { }
    }
    return false
  }

  return when (request.direction) {
    AnchoredRowDirection.NEXT -> {
      val isMounted = rowState.layoutInfo.visibleItemsInfo.any { it.index == target }
      if (!isMounted) {
        // Card fuori finestra (viewport molto stretto): la si porta subito all'ancora e si
        // sposta il focus. Nessuna animazione possibile su un nodo non composto.
        rowState.scrollToItem(target)
        awaitAttached()
        requestConfirmed()
      } else {
        if (!requestConfirmed()) return false
        scrollToAnchorAnimated(target, from)
        true
      }
    }

    AnchoredRowDirection.PREVIOUS -> {
      scrollToAnchorAnimated(target, from)
      awaitAttached()
      requestConfirmed()
    }
  }
}

/**
 * Riquadro di selezione ancorato. Riproduce la decorazione di focus della card selezionata
 * (alone/bordo Neon oppure bordo a gradiente) alla posizione fissa dell'ancora.
 */
@Composable
private fun AnchoredSelectionFrame(
  alpha: Float,
  style: AnchoredFrameStyle,
  scale: Float,
  modifier: Modifier = Modifier
) {
  val density = LocalDensity.current
  val cornerPx = with(density) { NeonCardCorner.toPx() }
  val glowSpreadPx = with(density) { NeonCardGlowSpread.toPx() }
  val borderPx = with(density) { NeonCardBorderWidth.toPx() }

  Canvas(
    modifier = modifier.graphicsLayer {
      scaleX = scale
      scaleY = scale
    }
  ) {
    when (style) {
      AnchoredFrameStyle.NeonGlow -> {
        drawNeonGlow(
          cornerRadiusPx = cornerPx,
          color = NovaCyanBright,
          spreadPx = glowSpreadPx,
          intensity = alpha
        )
        drawNeonBorder(
          cornerRadiusPx = cornerPx,
          color = NovaCyanBright,
          widthPx = borderPx,
          intensity = alpha
        )
      }

      AnchoredFrameStyle.GradientBorder -> {
        // Stessa geometria di Modifier.border: tratto INSET di metà spessore nei bounds.
        val inset = borderPx / 2f
        val innerRadius = (cornerPx - inset).coerceAtLeast(0f)
        drawRoundRect(
          brush = Brush.horizontalGradient(
            listOf(
              NovaCyan.copy(alpha = alpha),
              NovaCyanBright.copy(alpha = alpha)
            )
          ),
          topLeft = Offset(inset, inset),
          size = Size(
            (size.width - borderPx).coerceAtLeast(0f),
            (size.height - borderPx).coerceAtLeast(0f)
          ),
          cornerRadius = CornerRadius(innerRadius, innerRadius),
          style = Stroke(width = borderPx)
        )
      }
    }
  }
}
