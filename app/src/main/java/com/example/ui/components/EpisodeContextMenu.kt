package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaCyanGlow
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary

/** Tag semantico del menu contestuale episodio (regressioni UI). */
const val EPISODE_CONTEXT_MENU_TEST_TAG = "episode_context_menu"

/** Tag semantico della voce «Annulla» del menu contestuale episodio. */
const val EPISODE_CONTEXT_MENU_CANCEL_TEST_TAG = "episode_context_menu_cancel"

private const val EPISODE_MENU_FOCUS_MAX_ATTEMPTS = 30
private const val EPISODE_MENU_FOCUS_FALLBACK_ATTEMPTS = 15

/**
 * Menu contestuale della riga episodio (pressione prolungata del tasto centrale).
 *
 * Offre le stesse famiglie di azioni del menu delle card «Continua a guardare»:
 *  - marcare l'EPISODIO come visto / da vedere (voce che cambia in base allo stato);
 *  - riaprire la scelta delle fonti per QUELL'episodio (nessuna riproduzione automatica);
 *  - marcare l'intera STAGIONE come vista (stessa voce per tutti gli episodi);
 *  - Annulla / BACK per chiudere senza azioni.
 *
 * È un Dialog in una finestra separata (come [MediaContextMenu]): mentre è aperto la
 * scheda sottostante non riceve eventi D-pad/BACK, quindi il focus non può fuggire.
 * La stessa guardia del rilascio del long-press impedisce che il KeyUp della pressione
 * che ha aperto il menu venga letto come click sulla prima voce.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EpisodeContextMenu(
  media: MediaItem,
  episode: Episode,
  isWatched: Boolean,
  onToggleWatched: () -> Unit,
  onBrowseSources: () -> Unit,
  onMarkSeasonWatched: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier
) {
  // CHIUSURA ONE-SHOT: BACK, scrim, «Annulla» e una voce possono arrivare nello stesso
  // frame. Solo il primo percorso esegue l'azione e chiude.
  var completionDispatched by remember { mutableStateOf(false) }
  val latestOnDismiss by rememberUpdatedState(onDismiss)
  fun complete(action: (() -> Unit)? = null) {
    if (completionDispatched) return
    completionDispatched = true
    action?.invoke()
    latestOnDismiss()
  }

  // Il menu vive in una finestra Dialog separata: la sua composizione può ripartire dal
  // contesto di sistema (es. en-XC) invece che dal contesto localizzato forzato dall'app.
  // Catturiamo qui, nella composizione localizzata del chiamante, le risorse corrette e
  // le riapplichiamo dentro il Dialog: le voci seguono sempre la lingua della UI.
  val localizedContext = LocalContext.current
  val localizedConfiguration = LocalConfiguration.current

  Dialog(
    onDismissRequest = { complete() },
    properties = DialogProperties(
      dismissOnBackPress = true,
      dismissOnClickOutside = false,
      usePlatformDefaultWidth = false,
      decorFitsSystemWindows = false
    )
  ) {
    CompositionLocalProvider(
      LocalContext provides localizedContext,
      LocalConfiguration provides localizedConfiguration
    ) {
    val firstItemFocusRequester = remember { FocusRequester() }
    val cancelFocusRequester = remember { FocusRequester() }
    var menuHasFocus by remember { mutableStateOf(false) }

    // Il long-press che ha aperto il menu lascia il tasto ancora premuto: il KeyUp
    // consegnato alla prima voce non deve attivarla. Finché non arriva una pressione
    // NUOVA (`repeatCount == 0`), ogni KeyUp di conferma viene scartato.
    var freshConfirmKeyDown by remember { mutableStateOf(false) }

    val isConfirmKey = remember {
      { keyCode: Int ->
        keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
          keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
          keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
      }
    }

    DisposableEffect(Unit) {
      ModalOverlayState.onShown()
      onDispose { ModalOverlayState.onHidden() }
    }

    // Focus iniziale differito e VERIFICATO: requestFocus() non espone un esito, il nodo
    // della prima voce può non essere ancora agganciato al frame in cui il Dialog compare.
    LaunchedEffect(menuHasFocus) {
      if (menuHasFocus) return@LaunchedEffect
      var attempts = 0
      while (!menuHasFocus && attempts < EPISODE_MENU_FOCUS_MAX_ATTEMPTS) {
        attempts++
        try {
          firstItemFocusRequester.requestFocus()
        } catch (_: IllegalStateException) {
        }
        withFrameNanos { }
      }
      attempts = 0
      while (!menuHasFocus && attempts < EPISODE_MENU_FOCUS_FALLBACK_ATTEMPTS) {
        attempts++
        try {
          cancelFocusRequester.requestFocus()
        } catch (_: IllegalStateException) {
        }
        withFrameNanos { }
      }
    }

    BackHandler(enabled = true) {
      complete()
    }

    Box(
      modifier = modifier
        .fillMaxSize()
        .background(Color.Black.copy(alpha = 0.7f))
        .focusProperties { canFocus = false }
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null,
          onClick = { complete() }
        )
        .onPreviewKeyEvent { keyEvent ->
          val native = keyEvent.nativeKeyEvent
          if (!isConfirmKey(native.keyCode)) return@onPreviewKeyEvent false
          when (keyEvent.type) {
            KeyEventType.KeyDown -> {
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
          .width(420.dp)
          .shadow(elevation = 24.dp, shape = RoundedCornerShape(16.dp), spotColor = NovaCyan, ambientColor = NovaCyanGlow)
          .background(Color(0xFF1E1E24), RoundedCornerShape(16.dp))
          .border(1.dp, NovaCyan.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
          .focusProperties { exit = { FocusRequester.Cancel } }
          .focusGroup()
          .onFocusChanged { menuHasFocus = it.hasFocus }
          .testTag(EPISODE_CONTEXT_MENU_TEST_TAG)
      ) {
        // Intestazione: miniatura 16:9 dell'episodio + SxEx + titolo + nome serie.
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
        ) {
          CardMediaImage(
            primaryUrl = episode.thumbnailUrl,
            secondaryUrl = media.backdropUrl,
            fallbackRes = episode.thumbnailRes ?: media.backdropRes,
            contentDescription = episode.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
          )
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(
                Brush.verticalGradient(
                  colors = listOf(Color.Transparent, Color(0xFF1E1E24)),
                  startY = 30f
                )
              )
          )
          Column(
            modifier = Modifier
              .align(Alignment.BottomStart)
              .padding(16.dp)
          ) {
            Text(
              text = "S${episode.seasonNumber} · E${episode.episodeNumber}",
              color = NovaCyanBright,
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = episode.title,
              color = NovaTextPrimary,
              fontSize = 16.sp,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Text(
              text = media.title,
              color = NovaTextSecondary,
              fontSize = 12.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }

        Column(
          modifier = Modifier.padding(vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
          val items = listOf(
            EpisodeMenuAction(
              label = stringResource(
                if (isWatched) R.string.episode_context_mark_unwatched
                else R.string.episode_context_mark_watched
              ),
              icon = if (isWatched) Icons.Default.VisibilityOff else Icons.Default.Visibility,
              onClick = onToggleWatched
            ),
            EpisodeMenuAction(
              label = stringResource(R.string.episode_context_browse_sources),
              icon = Icons.Default.Search,
              onClick = onBrowseSources
            ),
            EpisodeMenuAction(
              label = stringResource(R.string.episode_context_mark_season_watched),
              icon = Icons.Default.DoneAll,
              onClick = onMarkSeasonWatched
            )
          )

          items.forEachIndexed { index, item ->
            TvFocusableBox(
              modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
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
              onClick = { complete(item.onClick) }
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
                Icon(
                  imageVector = item.icon,
                  contentDescription = null,
                  tint = if (isFocused) NovaCyanBright else NovaTextSecondary,
                  modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(14.dp))
                Text(
                  text = item.label,
                  color = if (isFocused) NovaCyanBright else NovaTextPrimary,
                  fontSize = 14.sp,
                  fontWeight = FontWeight.Bold
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
              .height(44.dp)
              .focusRequester(cancelFocusRequester)
              .focusProperties { down = FocusRequester.Cancel }
              .testTag(EPISODE_CONTEXT_MENU_CANCEL_TEST_TAG),
            shape = RoundedCornerShape(8.dp),
            focusedScale = 1.02f,
            onClick = { complete() }
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
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = if (isFocused) NovaCyanBright else NovaTextSecondary,
                modifier = Modifier.size(20.dp)
              )
              Spacer(modifier = Modifier.width(14.dp))
              Text(
                text = stringResource(R.string.episode_context_cancel),
                color = if (isFocused) NovaCyanBright else NovaTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
              )
            }
          }
        }
      }
    }
    }
  }
}

private data class EpisodeMenuAction(
  val label: String,
  val icon: ImageVector,
  val onClick: () -> Unit
)
