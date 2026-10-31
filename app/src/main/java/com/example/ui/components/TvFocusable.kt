package com.example.ui.components

import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaCyanGlow
import com.example.ui.theme.NovaGold
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.theme.NovaTextMuted
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Durata della pressione prolungata del tasto di conferma su una card TV. */
internal const val TV_LONG_PRESS_TIMEOUT_MS = 400L

/** Esito del rilascio del tasto di conferma. */
internal enum class TvConfirmRelease {
  /** Il rilascio appartiene a una pressione normale: va eseguito [onClick]. */
  CLICK,

  /**
   * Il rilascio è già stato consumato (fine di una pressione prolungata, oppure pressione
   * iniziata quando la card non aveva il focus): nessun click.
   */
  CONSUMED
}

/**
 * Macchina a stati della pressione del tasto di conferma su una card TV.
 *
 * Perché non basta un booleano "tasto premuto": gli eventi RIPETUTI di un tasto tenuto
 * (`repeatCount > 0`) arrivano anche quando il focus è stato spostato e poi è tornato sulla
 * card. Senza una distinzione tra pressione NUOVA e ripetizione, una pressione prolungata che
 * ha già aperto il popup poteva ripartire al rientro del focus e aprire il popup una seconda
 * volta. Qui:
 * - solo una pressione nuova (`repeatCount == 0`) arma il timer di pressione prolungata;
 * - una pressione prolungata scatta una sola volta per pressione ([onLongPressTimeout]);
 * - la perdita di focus mentre il tasto è ancora premuto consuma quella pressione: il suo
 *   rilascio (che può arrivare al nodo dopo un rientro del focus) non produce un click.
 *
 * Classe pura (nessuna dipendenza da Compose) per essere verificabile nei test unitari.
 */
internal class TvConfirmPressTracker {
  var longPressTriggered: Boolean = false
    private set

  var keyDownActive: Boolean = false
    private set

  /** Job del timer di pressione prolungata (la cancellazione non è gestita qui). */
  var longPressJob: Job? = null

  /**
   * Inizio di una pressione.
   *
   * @return `true` solo se va armato il timer di pressione prolungata: solo per una pressione
   *   NUOVA. Le ripetizioni del tasto tenuto non riarmano mai nulla.
   */
  fun onKeyDown(repeatCount: Int): Boolean {
    if (repeatCount != 0) return false
    keyDownActive = true
    longPressTriggered = false
    return true
  }

  /**
   * Il timer è scaduto: la pressione prolungata scatta solo se la pressione è ancora in corso
   * (il tasto non è stato rilasciato nel frattempo) e non è già scattata per questa pressione.
   */
  fun onLongPressTimeout(): Boolean {
    if (!keyDownActive || longPressTriggered) return false
    longPressTriggered = true
    return true
  }

  /** Rilascio del tasto: decide se è il termine di una pressione prolungata o un click. */
  fun onKeyUp(): TvConfirmRelease {
    val consumed = longPressTriggered || !keyDownActive
    longPressTriggered = false
    keyDownActive = false
    return if (consumed) TvConfirmRelease.CONSUMED else TvConfirmRelease.CLICK
  }

  /**
   * La card perde il focus: la pressione in volo (se c'è) è consumata, così il rilascio che
   * arriva dopo un eventuale rientro del focus non vale come click.
   */
  fun onFocusLost() {
    if (keyDownActive) longPressTriggered = true
    keyDownActive = false
  }
}

@Composable
fun TvFocusableBox(
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(12.dp),
  focusedScale: Float = 1.08f,
  focusedBorderColor: Color = NovaCyanBright,
  unfocusedBorderColor: Color = Color.Transparent,
  borderWidth: Dp = 2.5.dp,
  onClick: () -> Unit = {},
  onLongPress: (() -> Unit)? = null,
  content: @Composable BoxScope.(isFocused: Boolean) -> Unit
) {
  var isFocused by remember { mutableStateOf(false) }
  // Nelle righe ancorate il riquadro di selezione è disegnato dall'overlay fisso durante lo
  // step: bordo e scala della card restano spenti per non far "viaggiare" la cornice.
  val focusDecorationSuppressed = LocalAnchoredRowFocusDecorationSuppressed.current
  val showFocusDecoration = isFocused && !focusDecorationSuppressed
  val scale by animateFloatAsState(
    targetValue = if (showFocusDecoration) focusedScale else 1f,
    // All'inizio dello step la cornice della card che esce si spegne di colpo: un fade
    // mentre la card scorre sarebbe una seconda cornice in movimento.
    animationSpec = if (focusDecorationSuppressed) snap() else tween(durationMillis = 180),
    label = "TvFocusScale"
  )
  val coroutineScope = rememberCoroutineScope()
  val pressTracker = remember { TvConfirmPressTracker() }

  val isConfirmKey = remember {
    { keyCode: Int ->
      keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
      keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
      keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
    }
  }

  Box(
    modifier = modifier
      .zIndex(if (showFocusDecoration) 10f else 1f)
      // Punto 2 – Zoom via graphicsLayer: l'animazione della scala non ricomposizione
      // la UI (la lambda legge lo stato direttamente in fase di draw/rendering).
      .graphicsLayer {
        scaleX = scale
        scaleY = scale
      }
      .onFocusChanged {
        isFocused = it.isFocused
        if (!it.isFocused) {
          pressTracker.longPressJob?.cancel()
          pressTracker.longPressJob = null
          pressTracker.onFocusLost()
        }
      }
      .onKeyEvent { keyEvent ->
        val native = keyEvent.nativeKeyEvent
        if (!isConfirmKey(native.keyCode)) return@onKeyEvent false

        when (keyEvent.type) {
          KeyEventType.KeyDown -> {
            // Solo una pressione NUOVA (repeatCount == 0) arma il timer: le ripetizioni del
            // tasto tenuto non devono mai far scattare una seconda pressione prolungata.
            if (pressTracker.onKeyDown(native.repeatCount)) {
              pressTracker.longPressJob?.cancel()
              pressTracker.longPressJob = null
              if (onLongPress != null) {
                pressTracker.longPressJob = coroutineScope.launch {
                  delay(TV_LONG_PRESS_TIMEOUT_MS)
                  if (pressTracker.onLongPressTimeout()) {
                    Log.d("TvFocusableBox", "LONG PRESS TRIGGERED!")
                    onLongPress.invoke()
                  }
                }
              }
            }
            true
          }

          KeyEventType.KeyUp -> {
            pressTracker.longPressJob?.cancel()
            pressTracker.longPressJob = null
            when (pressTracker.onKeyUp()) {
              TvConfirmRelease.CONSUMED -> {
                Log.d("TvFocusableBox", "KeyUp: consumed after long press")
                true
              }
              TvConfirmRelease.CLICK -> {
                Log.d("TvFocusableBox", "KeyUp: invoking normal onClick")
                onClick()
                true
              }
            }
          }

          else -> false
        }
      }
      .focusable()
      .then(
        if (showFocusDecoration) {
          // L'evidenziazione del focus è bordo ciano + scala: NESSUNA ombra portata.
          // L'elevazione disegna l'ombra-silhouette della piattaforma spostata verso il
          // basso, che su elementi di menu (pill e righe) appare come una BARRA SCURA nella
          // parte inferiore dell'elemento evidenziato — e con `spotColor`/`ambientColor`
          // ignorati sotto API 28 è nera piena. È il difetto risolto a monte anche da
          // [HeroBanner] e dalla sinossi del Dettaglio ("banda netta"/"fascia scura").
          // Il bordo e la scala bastano come indicatore di focus per il D-pad.
          Modifier.border(
            BorderStroke(
              width = borderWidth,
              brush = Brush.horizontalGradient(
                listOf(NovaCyan, NovaCyanBright)
              )
            ),
            shape = shape
          )
        } else if (unfocusedBorderColor != Color.Transparent) {
          Modifier.border(borderWidth, unfocusedBorderColor, shape)
        } else {
          Modifier
        }
      )
      .clip(shape)
  ) {
    content(isFocused)
  }
}

@Composable
fun TvActionButton(
  text: String,
  icon: ImageVector? = null,
  isPrimary: Boolean = true,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  TvFocusableBox(
    modifier = modifier,
    shape = RoundedCornerShape(50),
    focusedScale = 1.05f,
    onClick = onClick
  ) { isFocused ->
    val bgBrush = when {
      isFocused && isPrimary -> Brush.horizontalGradient(listOf(NovaCyan, NovaCyanBright))
      isFocused && !isPrimary -> Brush.horizontalGradient(listOf(NovaSurfaceVariant, NovaCyan.copy(alpha = 0.35f)))
      isPrimary -> Brush.horizontalGradient(listOf(NovaCyan.copy(alpha = 0.95f), NovaCyan))
      else -> Brush.horizontalGradient(listOf(Color(0xFF1E2638).copy(alpha = 0.9f), Color(0xFF161E30).copy(alpha = 0.9f)))
    }

    val contentColor = when {
      isPrimary -> Color.Black
      isFocused -> NovaCyanBright
      else -> NovaTextPrimary
    }

    Row(
      modifier = Modifier
        .background(bgBrush, RoundedCornerShape(50))
        .border(
          width = if (isFocused) 1.5.dp else 1.dp,
          color = when {
            isFocused -> NovaCyanBright
            isPrimary -> Color.Transparent
            else -> Color(0x33475569)
          },
          shape = RoundedCornerShape(50)
        )
        .padding(horizontal = 16.dp, vertical = 9.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center
    ) {
      if (icon != null) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = contentColor,
          modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
      }
      Text(
        text = text,
        color = contentColor,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold
      )
    }
  }
}

@Composable
fun QualityBadge(
  text: String,
  modifier: Modifier = Modifier,
  isHighlighted: Boolean = false,
  leadingIcon: ImageVector? = null,
  leadingIconTint: Color = if (isHighlighted) NovaCyanBright else NovaTextPrimary
) {
  Row(
    modifier = modifier
      .background(
        color = if (isHighlighted) NovaCyan.copy(alpha = 0.2f) else Color(0x3320293A),
        shape = RoundedCornerShape(4.dp)
      )
      .border(
        width = 1.dp,
        color = if (isHighlighted) NovaCyan.copy(alpha = 0.7f) else Color(0x4464748B),
        shape = RoundedCornerShape(4.dp)
      )
      .padding(horizontal = 6.dp, vertical = 3.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp)
  ) {
    if (leadingIcon != null) {
      Icon(
        imageVector = leadingIcon,
        contentDescription = null,
        tint = leadingIconTint,
        modifier = Modifier.size(12.dp)
      )
    }
    Text(
      text = text,
      color = if (isHighlighted) NovaCyanBright else NovaTextPrimary,
      fontSize = 11.sp,
      fontWeight = FontWeight.SemiBold,
      letterSpacing = 0.5.sp
    )
  }
}

data class ContextMenuItem(
  val label: String,
  val icon: ImageVector? = null,
  val onClick: () -> Unit
)

@Composable
fun TvContextMenu(
  items: List<ContextMenuItem>,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier
) {
  BackHandler(enabled = true) {
    onDismiss()
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black.copy(alpha = 0.4f))
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onDismiss
      ),
    contentAlignment = Alignment.Center
  ) {
    Column(
      modifier = Modifier
        .background(NovaSurfaceVariant, RoundedCornerShape(16.dp))
        .border(1.5.dp, NovaCyan, RoundedCornerShape(16.dp))
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      items.forEach { item ->
        TvFocusableBox(
          modifier = Modifier
            .fillMaxSize()
            .width(240.dp),
          shape = RoundedCornerShape(8.dp),
          focusedScale = 1.02f,
          onClick = {
            item.onClick()
            onDismiss()
          }
        ) { isFocused ->
          Row(
            modifier = Modifier
              .background(
                if (isFocused) NovaCyan.copy(alpha = 0.15f) else Color.Transparent,
                RoundedCornerShape(8.dp)
              )
              .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
          ) {
            if (item.icon != null) {
              Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = if (isFocused) NovaCyanBright else NovaTextPrimary,
                modifier = Modifier.size(20.dp)
              )
              Spacer(modifier = Modifier.width(12.dp))
            }
            Text(
              text = item.label,
              color = if (isFocused) NovaCyanBright else NovaTextPrimary,
              fontSize = 14.sp,
              fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium
            )
          }
        }
      }
    }
  }
}

/** Tag semantico del contenitore del menu contestuale delle card (regressioni UI). */
const val MEDIA_CONTEXT_MENU_TEST_TAG = "media_context_menu"

/** Tag semantico della voce «Annulla» del menu contestuale. */
const val MEDIA_CONTEXT_MENU_CANCEL_TEST_TAG = "media_context_menu_cancel"

/**
 * Frame massimi in cui si ritenta il focus sul primo elemento del menu. Il conteggio è in
 * FRAME (non in millisecondi): su un dispositivo lento ogni frame vale più tempo, quindi la
 * finestra reale si allarga senza aggiungere ritardi fissi. Nessun ciclo infinito.
 */
private const val POPUP_FOCUS_MAX_ATTEMPTS = 30

/** Frame massimi del fallback «Annulla» (sempre DENTRO il popup). */
private const val POPUP_FOCUS_FALLBACK_ATTEMPTS = 15

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MediaContextMenu(
  media: MediaItem,
  onPlay: () -> Unit,
  onDetails: () -> Unit,
  onRemoveFromContinueWatching: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier
) {
  // CHIUSURA ONE-SHOT.
  //
  // BACK, click sullo scrim, «Annulla» e una voce possono arrivare nello stesso frame (o il
  // Dialog può emettere `onDismissRequest` mentre l'azione sta navigando). Solo il primo
  // percorso esegue l'azione e chiude; ogni evento successivo è ignorato: nessuna doppia
  // riproduzione, doppia navigazione o doppia rimozione da «Continua a guardare».
  var completionDispatched by remember { mutableStateOf(false) }
  val latestOnDismiss by rememberUpdatedState(onDismiss)
  fun complete(action: (() -> Unit)? = null) {
    if (completionDispatched) return
    completionDispatched = true
    action?.invoke()
    latestOnDismiss()
  }

  // VERO MODALE, NON UN OVERLAY IN-WINDOW.
  //
  // Il menu vive in una finestra di Dialog separata: mentre quella finestra ha il focus, la
  // finestra della Home NON riceve alcun evento DPAD/BACK. L'isolamento è quindi garantito
  // dal sistema delle finestre e non da una richiesta di focus che un effetto della Home
  // può scavalcare nel frame successivo (causa reale del difetto «popup visibile ma focus
  // ancora sulla Home», con BACK che finiva sul doppio-Back di uscita dell'app).
  Dialog(
    onDismissRequest = { complete() },
    properties = DialogProperties(
      dismissOnBackPress = true,
      dismissOnClickOutside = false,
      usePlatformDefaultWidth = false,
      decorFitsSystemWindows = false
    )
  ) {
    val firstItemFocusRequester = remember { FocusRequester() }
    val cancelFocusRequester = remember { FocusRequester() }

    // PROVA REALE che il focus è nel popup: `requestFocus()` non espone un esito e non lancia
    // eccezioni quando il nodo è agganciato ma la richiesta viene scartata. Solo
    // `onFocusChanged` (hasFocus del gruppo) conferma il trasferimento.
    var menuHasFocus by remember { mutableStateOf(false) }

    // Il long-press che ha aperto questo menu lascia il tasto di conferma ANCORA PREMUTO: al
    // rilascio il KeyUp viene consegnato all'item già a fuoco e, senza guardia, il suo
    // TvFocusableBox lo leggerebbe come un click normale (avvio della riproduzione o altra
    // azione implicita). Questo flag ricorda se dopo l'apertura è arrivata una pressione
    // NUOVA (`repeatCount == 0`): finché non arriva, ogni KeyUp di conferma è il rilascio del
    // long-press e va scartato, qualunque sia la durata della pressione. Non è un ritardo
    // fisso: la prima pressione genuina riabilita subito l'attivazione.
    var freshConfirmKeyDown by remember { mutableStateOf(false) }

    val isConfirmKey = remember {
      { keyCode: Int ->
        keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
        keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
      }
    }

    // Segnala all'Activity che un modale in-window è aperto: mentre lo è, la gesture LEFT
    // (che apre la sidebar) resta disattivata e il focus non può fuggire dal menu.
    DisposableEffect(Unit) {
      ModalOverlayState.onShown()
      onDispose { ModalOverlayState.onHidden() }
    }

    // FOCUS INIZIALE DIFFERITO E VERIFICATO.
    //
    // `requestFocus()` non espone un esito: il nodo del primo item può non essere ancora
    // agganciato nel frame in cui il Dialog compare. Qui la richiesta si ripete entro un numero
    // LIMITATO di frame finché `onFocusChanged` conferma che il menu ha davvero il focus. Nella
    // finestra del Dialog nessun effetto della Home può contendere il focus: il ciclo copre solo
    // il ritardo di aggancio dei nodi.
    LaunchedEffect(menuHasFocus) {
      if (menuHasFocus) return@LaunchedEffect

      var attempts = 0
      while (!menuHasFocus && attempts < POPUP_FOCUS_MAX_ATTEMPTS) {
        attempts++
        try {
          firstItemFocusRequester.requestFocus()
        } catch (_: IllegalStateException) {
          // Requester non ancora agganciato al nodo: si ritenta al frame successivo.
        }
        withFrameNanos { }
      }

      // Fallback DENTRO il popup: se il primo item non è raggiungibile, «Annulla» è comunque
      // un elemento del menu e permette di chiuderlo con il D-pad.
      attempts = 0
      while (!menuHasFocus && attempts < POPUP_FOCUS_FALLBACK_ATTEMPTS) {
        attempts++
        try {
          cancelFocusRequester.requestFocus()
        } catch (_: IllegalStateException) {
        }
        withFrameNanos { }
      }
    }

    // BACK è gestito dal Dialog (dismissOnBackPress) e da questa callback, registrata DOPO
    // quella interna del Dialog: è quindi la prima a scattare e chiude UNA sola volta tramite
    // `complete()`. La Home non vede mai questo BACK: vive in un'altra finestra.
    BackHandler(enabled = true) {
      complete()
    }

    Box(
      modifier = modifier
        .fillMaxSize()
        .background(Color.Black.copy(alpha = 0.7f))
        // Lo scrim NON è un target di focus: `clickable` da solo è focalizzabile in modalità
        // non-touch (TV) e la ricerca geometrica del D-pad poteva atterrarci, lasciando il
        // cursore su una superficie invisibile del popup.
        .focusProperties { canFocus = false }
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = { complete() }
        )
        .onPreviewKeyEvent { keyEvent ->
          // GUARDIA DEL RILASCIO DEL LONG-PRESS (vedi `freshConfirmKeyDown`).
          //
          // In PREVIEW perché è l'unico punto che vede il tasto prima degli item del menu: nel
          // bubble gli item consumano il KeyUp per conto proprio e questo handler non lo
          // riceverebbe mai.
          val native = keyEvent.nativeKeyEvent
          if (!isConfirmKey(native.keyCode)) return@onPreviewKeyEvent false
          when (keyEvent.type) {
            KeyEventType.KeyDown -> {
              // Solo la pressione iniziale è "nuova": le ripetizioni del tasto tenuto premuto
              // non devono riabilitare l'attivazione, altrimenti il rilascio del long-press
              // tornerebbe a valere come click.
              if (native.repeatCount == 0) freshConfirmKeyDown = true
              false
            }

            KeyEventType.KeyUp -> {
              if (freshConfirmKeyDown) {
                freshConfirmKeyDown = false
                false
              } else {
                true
              }
            }

            else -> false
          }
        },
      contentAlignment = Alignment.Center
    ) {
      Column(
        modifier = Modifier
          .width(320.dp)
          .shadow(elevation = 24.dp, shape = RoundedCornerShape(16.dp), spotColor = NovaCyan, ambientColor = NovaCyanGlow)
          .background(Color(0xFF1E1E24), RoundedCornerShape(16.dp))
          .border(1.dp, NovaCyan.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
          // Il menu è un gruppo di focus CHIUSO: la navigazione D-pad resta tra le sue voci e
          // non può uscire verso le card della Home o la sidebar sottostanti. `exit = Cancel`
          // blocca ogni uscita dal gruppo; le voci di bordo annullano anche le direzioni
          // senza vicino.
          .focusProperties { exit = { FocusRequester.Cancel } }
          .focusGroup()
          .onFocusChanged { menuHasFocus = it.hasFocus }
          .testTag(MEDIA_CONTEXT_MENU_TEST_TAG)
      ) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
        ) {
          CardMediaImage(
            primaryUrl = media.backdropUrl,
            secondaryUrl = media.posterUrl,
            fallbackRes = (media.backdropRes ?: media.posterRes)?.takeIf { it != R.drawable.banner_dune },

            contentDescription = media.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
          )
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(
                Brush.verticalGradient(
                  colors = listOf(Color.Transparent, Color(0xFF1E1E24)),
                  startY = 40f
                )
              )
          )
          Column(
            modifier = Modifier
              .align(Alignment.BottomStart)
              .padding(16.dp)
          ) {
            Text(
              text = media.title,
              color = NovaTextPrimary,
              fontSize = 16.sp,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            if (media.type == MediaType.SERIE_TV && media.lastWatchedEpisode != null) {
              Text(
                text = "S${media.lastWatchedSeason ?: 1}:E${media.lastWatchedEpisode}",
                color = NovaTextSecondary,
                fontSize = 12.sp
              )
            }
          }
        }

        Column(
          modifier = Modifier
            .padding(vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
          val menuItems = listOf(
            ContextMenuItem(
              label = if (media.currentProgressMs > 0) stringResource(R.string.context_resume) else stringResource(R.string.action_play),
              icon = Icons.Default.PlayArrow,
              onClick = onPlay
            ),
            ContextMenuItem(
              label = stringResource(R.string.context_details),
              icon = Icons.Default.Info,
              onClick = onDetails
            ),
            ContextMenuItem(
              label = stringResource(R.string.context_remove_continue_watching),
              icon = Icons.Default.Close,
              onClick = onRemoveFromContinueWatching
            )
          )

          menuItems.forEachIndexed { index, item ->
            TvFocusableBox(
              modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                // Confini espliciti del gruppo: nessuna direzione senza vicino può uscire dal
                // popup (l'ultima voce scende su «Annulla» con la ricerca geometrica).
                .focusProperties {
                  left = FocusRequester.Cancel
                  right = FocusRequester.Cancel
                  if (index == 0) up = FocusRequester.Cancel
                }
                .then(
                  if (index == 0) Modifier.focusRequester(firstItemFocusRequester)
                  else Modifier
                ),
              shape = RoundedCornerShape(8.dp),
              focusedScale = 1.02f,
              onClick = {
                complete(item.onClick)
              }
            ) { isFocused ->
              Row(
                modifier = Modifier
                  .fillMaxSize()
                  .background(
                    if (isFocused) NovaCyan.copy(alpha = 0.15f) else Color.Transparent,
                    RoundedCornerShape(8.dp)
                  )
                  .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                item.icon?.let { icon ->
                  Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isFocused) NovaCyanBright else NovaTextSecondary,
                    modifier = Modifier.size(20.dp)
                  )
                  Spacer(modifier = Modifier.width(14.dp))
                }
                Text(
                  text = item.label,
                  color = if (isFocused) NovaCyanBright else NovaTextPrimary,
                  fontSize = 14.sp,
                  fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium
                )
              }
            }
          }
        }

        Box(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
          TvFocusableBox(
            modifier = Modifier
              .fillMaxWidth()
              .focusRequester(cancelFocusRequester)
              .testTag(MEDIA_CONTEXT_MENU_CANCEL_TEST_TAG)
              // Ultimo elemento del gruppo: nessuna uscita verso il basso o i lati.
              .focusProperties {
                down = FocusRequester.Cancel
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
              },
            shape = RoundedCornerShape(8.dp),
            focusedScale = 1.02f,
            onClick = {
              complete()
            }
          ) { isFocused ->
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .background(
                  if (isFocused) NovaCyan.copy(alpha = 0.1f) else Color.Transparent,
                  RoundedCornerShape(8.dp)
                )
                .padding(vertical = 12.dp),
              contentAlignment = Alignment.Center
            ) {
              Text(
                text = stringResource(R.string.action_cancel),
                color = if (isFocused) NovaCyanBright else NovaTextMuted,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
              )
            }
          }
        }
      }
    }
  }
}
