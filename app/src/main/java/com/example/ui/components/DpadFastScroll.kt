package com.example.ui.components

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Port del sistema di navigazione D-pad di NuvioTV per tasti TENUTI (file originali:
 * `DpadFastScrollModifier.kt` e `DpadThrottleModifier.kt`), adattato alle righe della Home
 * StreamNova.
 *
 * Comportamento (identico a quello spedito in produzione da Nuvio):
 *
 * - PRIMA PRESSIONE (repeatCount == 0) di qualunque tasto direzionale: l'evento NON viene
 *   consumato, così lo step singolo è governato dalla RICERCA NATIVA del focus di Compose
 *   (ingresso nei `focusGroup` di riga con `focusRestorer` + BringIntoView).
 * - DPAD_SU/GIÙ ripetuti (tasto tenuto): takeover del viewport — il focus resta CONGELATO sulla
 *   card di partenza mentre la lista scorre a velocità costante pilotata a frame; sul rilascio
 *   (o al bordo, o dopo [DEFAULT_END_TIMEOUT_MS] di silenzio) [resolveVerticalLanding] decide
 *   la riga di atterraggio e la colonna RICORDATA della riga a cui ridare il focus.
 * - DPAD_SX/DX ripetuti: smontano un eventuale drag verticale in corso e passano attraverso
 *   [dpadRepeatThrottle], che consuma i ripetuti al gate orizzontale e chiama
 *   `focusManager.moveFocus` a ritmo controllato (uno step di card per tick).
 *
 * [shouldEngage] è l'adattamento StreamNova: il takeover verticale non parte quando la
 * superficie focalizzata non è una riga della lista (es. l'Hero, che gestisce i propri tasti
 * con il coordinatore dedicato).
 */

/**
 * Velocità del drag verticale, in dp al secondo: tarata perché tenere premuto GIÙ sulla Home
 * dia lo scorrimento continuo e leggibile di un launcher TV nativo.
 */
private const val DEFAULT_VERTICAL_VELOCITY_DP_PER_SEC = 3200f

/**
 * Massimo intervallo di silenzio tra tasti prima che il drag sia smontato autonomamente.
 * ACTION_UP è il segnale primario di rilascio; questa guardia serve solo quando il focus
 * abbandona il contenitore (IME di sistema) e l'UP non arriva mai.
 */
private const val DEFAULT_END_TIMEOUT_MS = 160L

/**
 * Tetto al delta di un singolo frame: un lungo stallone (GC, layout thrash) non deve
 * teletrasportare la lista di centinaia di dp al frame successivo. Circa tre frame a 60 Hz.
 */
private const val DEFAULT_MAX_FRAME_DT_SEC = 0.048f

private enum class FastScrollMode { None, Vertical }

/** Estensione di una riga visibile nella lista verticale, per la geometria di atterraggio. */
internal data class DpadVisibleRowBounds(val key: String, val offset: Int, val size: Int)

/**
 * Riga di atterraggio del fast-scroll verticale: geometria di `resolveVerticalLanding` di
 * NuvioTV (`ModernHomeRowsList.kt`), estratta come funzione pura e testabile.
 *
 * - tenendo GIÙ e l'ultima riga è già INTERAMENTE in fondo: la riga di atterraggio è quella;
 * - tenendo SU e la prima riga visibile è myopia oltre la metà fuori dal bordo: quella riga;
 * - altrimenti la prima riga il cui bordo superiore ha raggiunto (o superato) la cima.
 *
 * Ritorna `null` se nessuna riga visibile appartiene alle righe note ([rowKeysInOrder]).
 */
internal fun resolveFastScrollLandingRowKey(
  visibleRows: List<DpadVisibleRowBounds>,
  viewportEndOffset: Int,
  sign: Int,
  rowKeysInOrder: List<String>
): String? {
  val lastRowKey = rowKeysInOrder.lastOrNull()
  val lastRowAtBottom = lastRowKey != null &&
    visibleRows.lastOrNull { it.key == lastRowKey }?.let {
      it.offset + it.size <= viewportEndOffset
    } == true
  val upwardTopRow: DpadVisibleRowBounds? = if (sign < 0) {
    visibleRows.firstOrNull()?.takeIf { it.offset > -it.size / 2 }
  } else {
    null
  }
  val candidate = when {
    lastRowAtBottom -> lastRowKey
    upwardTopRow != null -> upwardTopRow.key
    else -> visibleRows.firstOrNull { it.offset >= 0 }?.key
  } ?: return null
  return candidate.takeIf { rowKeysInOrder.contains(it) }
}

fun Modifier.dpadVerticalFastScroll(
  scrollableState: ScrollableState,
  resolveVerticalLanding: (sign: Int) -> String?,
  shouldEngage: () -> Boolean = { true },
  onFastScrollingChanged: (Boolean) -> Unit = {},
  shouldHaltForward: () -> Boolean = { false },
  horizontalGateMs: Long = 80L,
  verticalVelocityDpPerSec: Float = DEFAULT_VERTICAL_VELOCITY_DP_PER_SEC,
  endTimeoutMs: Long = DEFAULT_END_TIMEOUT_MS,
  maxFrameDtSec: Float = DEFAULT_MAX_FRAME_DT_SEC,
): Modifier = composed {
  val density = LocalDensity.current
  val scope = rememberCoroutineScope()

  // Atomics al posto di mutableState: nessun observatore diretto; l'unica osservabilità è il
  // flag di fast-scroll, esposto via [onFastScrollingChanged].
  val jobRef = remember { AtomicReference<Job?>(null) }
  val endTimerRef = remember { AtomicReference<Job?>(null) }
  val modeRef = remember { AtomicReference(FastScrollMode.None) }
  val directionRef = remember { AtomicInteger(0) }
  val isActiveRef = remember { AtomicReference(false) }

  DisposableEffect(Unit) {
    onDispose {
      jobRef.getAndSet(null)?.cancel()
      endTimerRef.getAndSet(null)?.cancel()
    }
  }

  onPreviewKeyEvent { event ->
    val native = event.nativeKeyEvent
    val keyCode = native.keyCode
    val isHoriz = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
      keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
    val isVert = keyCode == KeyEvent.KEYCODE_DPAD_UP ||
      keyCode == KeyEvent.KEYCODE_DPAD_DOWN

    fun setActive(value: Boolean) {
      if (isActiveRef.getAndSet(value) != value) {
        onFastScrollingChanged(value)
      }
    }

    fun endFastScroll() {
      val mode = modeRef.getAndSet(FastScrollMode.None)
      val direction = directionRef.getAndSet(0)
      jobRef.getAndSet(null)?.cancel()
      endTimerRef.getAndSet(null)?.cancel()
      setActive(false)
      if (mode == FastScrollMode.Vertical) {
        // L'atterraggio avviene via stato (richiesta pendente → completamento autorevole).
        resolveVerticalLanding(if (direction == 0) 1 else direction)
      }
    }

    if (!isHoriz && !isVert) return@onPreviewKeyEvent false

    // Rilascio: stop del drag (atterraggio incluso in endFastScroll) e avvio della gestione
    // predefinita, così ogni altro ascoltatore di ACTION_UP continua a vederla.
    if (native.action == KeyEvent.ACTION_UP) {
      if (modeRef.get() != FastScrollMode.None) endFastScroll()
      return@onPreviewKeyEvent false
    }

    if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false

    // Prima pressione (non un ripetuto) — passa SEMPRE oltre: la ricerca nativa del focus
    // sposta il focus di esattamente una cella. Il fast-scroll si attiva solo oltre la
    // soglia di ripetizione di sistema.
    if (native.repeatCount == 0) return@onPreviewKeyEvent false

    if (isHoriz) {
      // Un drag verticale in corso viene smontato prima che l'utente navighi sull'altro asse;
      // la throttle orizzontale-installata in fondo a questo modifier governa lo step.
      if (modeRef.get() != FastScrollMode.None) endFastScroll()
      return@onPreviewKeyEvent false
    }

    // Ripetuto verticale: takeover, a meno che la superficie focalizzata non sia fuori dal
    // perimetro delle righe (es. Hero): lì i tasti restano alla gestione dedicata.
    if (isVert && !shouldEngage()) return@onPreviewKeyEvent false

    val sign = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
    val needsStart = modeRef.get() != FastScrollMode.Vertical ||
      directionRef.get() != sign ||
      jobRef.get()?.isActive != true

    if (needsStart) {
      jobRef.getAndSet(null)?.cancel()

      // Non attivare un nuovo ciclo se la lista non può muoversi in questa direzione: senza
      // questa guardia, tenendo premuto GIÙ dopo l'atterraggio sull'ultima riga il flag
      // lampeggia al ritmo dei ripetuti e il chrome di focus sfarfalla.
      val atScrollEdge = (sign > 0 && !scrollableState.canScrollForward) ||
        (sign < 0 && !scrollableState.canScrollBackward)
      val halted = sign > 0 && shouldHaltForward()
      if (atScrollEdge || halted) {
        if (modeRef.get() != FastScrollMode.None) endFastScroll()
        return@onPreviewKeyEvent true
      }

      modeRef.set(FastScrollMode.Vertical)
      directionRef.set(sign)
      setActive(true)

      val velocityPxPerSec = with(density) { verticalVelocityDpPerSec.dp.toPx() }

      jobRef.set(
        scope.launch {
          try {
            scrollableState.scroll {
              var lastFrame = withFrameNanos { it }
              while (true) {
                val now = withFrameNanos { it }
                val dtSec = ((now - lastFrame) / 1_000_000_000f)
                  .coerceAtMost(maxFrameDtSec)
                lastFrame = now

                if (sign > 0 && shouldHaltForward()) break

                val delta = sign * velocityPxPerSec * dtSec
                val consumed = scrollBy(delta)
                if (consumed == 0f && delta != 0f) break
              }
            }
            // Il ciclo è uscito perché la lista non può più muoversi (o l'halt è scattato):
            // atterraggio immediato, senza attendere ACTION_UP o il timer di sicurezza.
            endFastScroll()
          } catch (_: CancellationException) {
            // atteso su rilascio / cambio asse
          }
        }
      )
    }

    // (Re)arma il timer di sicurezza: se ACTION_UP si perde (IME, passaggio in background)
    // il drag termina comunque dopo un breve idle.
    endTimerRef.getAndSet(null)?.cancel()
    endTimerRef.set(
      scope.launch {
        delay(endTimeoutMs)
        endFastScroll()
      }
    )

    true
  }.dpadRepeatThrottle(
    horizontalGateMs = horizontalGateMs,
    // I ripetuti verticali sono completamente consumati dal preview handler qui sopra quando
    // il takeover è attivo; Long.MAX_VALUE rende la throttle un puro gate orizzontale.
    verticalGateMs = Long.MAX_VALUE,
  )
}

/**
 * Port di `DpadThrottleModifier.kt` di NuvioTV: limita i ripetuti dei tasti direzionali tenuti
 * per evitare sovraccarico HWUI e scatti di focus, consumando i ripetuti rapidi e muovendo il
 * focus a ritmo controllato con `focusManager.moveFocus`.
 *
 * I ripetuti verticali normalmente non arrivano qui (consumati dal takeover del fast-scroll
 * quando attivo): [verticalGateMs] a [Long.MAX_VALUE] li trattiene comunque, senza effetti
 * collaterali verticali accidentali.
 */
fun Modifier.dpadRepeatThrottle(
  horizontalGateMs: Long = 80L,
  verticalGateMs: Long = 112L
): Modifier = composed {
  val focusManager = LocalFocusManager.current
  val lastRepeatTime = remember { longArrayOf(0L) }

  onPreviewKeyEvent { event ->
    val native = event.nativeKeyEvent
    if (native.action == KeyEvent.ACTION_DOWN &&
      native.repeatCount > 0 &&
      (native.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
        native.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
        native.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
        native.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
    ) {
      val isVertical = native.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
        native.keyCode == KeyEvent.KEYCODE_DPAD_UP
      val gateMs = if (isVertical) verticalGateMs else horizontalGateMs
      val now = SystemClock.uptimeMillis()
      if (now - lastRepeatTime[0] < gateMs) {
        return@onPreviewKeyEvent true
      }
      lastRepeatTime[0] = now
      val direction = when (native.keyCode) {
        KeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
        KeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
        KeyEvent.KEYCODE_DPAD_LEFT -> FocusDirection.Left
        KeyEvent.KEYCODE_DPAD_RIGHT -> FocusDirection.Right
        else -> null
      }
      if (direction != null) focusManager.moveFocus(direction)
      return@onPreviewKeyEvent true
    }
    false
  }
}
