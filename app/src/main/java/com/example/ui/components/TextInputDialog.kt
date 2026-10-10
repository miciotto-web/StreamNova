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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import com.example.R
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary

/**
 * Dialogo con campo di testo per Telecomando TV (D-pad).
 *
 * Usato per incollare/inserire la **chiave API TorBox** nelle Impostazioni e
 * l'**URL del manifest** di uno Stremio Addon nella schermata Addon.
 *
 * - All'apertura il focus va al campo e si richiede la tastiera (su Android TV
 *   la tastiera software compare come overlay e si compila con il telecomando).
 * - SU/GIÙ dal campo spostano il focus sui pulsanti; INVIO conferma.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TextInputDialog(
  title: String,
  hint: String,
  initialText: String = "",
  confirmLabel: String? = null,
  onConfirm: (String) -> Unit,
  onDismiss: () -> Unit
) {
  val resolvedConfirmLabel = confirmLabel ?: stringResource(R.string.action_save)
  var text by remember { mutableStateOf(initialText) }
  val fieldFocusRequester = remember { FocusRequester() }
  val confirmFocusRequester = remember { FocusRequester() }
  val cancelFocusRequester = remember { FocusRequester() }
  val focusManager = LocalFocusManager.current

  // Segnala all'Activity che un modale in-window è aperto: mentre lo è, la gesture
  // LEFT (che apre la sidebar) va disattivata per non far fuggire il focus.
  DisposableEffect(Unit) {
    ModalOverlayState.onShown()
    onDispose { ModalOverlayState.onHidden() }
  }

  // Rete di sicurezza per BACK: se il tasto arriva come evento di sistema (e non come
  // KeyEvent consumato dall'overlay), il dialog si chiude comunque in modo pulito.
  // Mentre l'IME è aperto è il sistema a consumare il primo BACK (chiude la tastiera).
  BackHandler { onDismiss() }

  // Focus immediato sul campo: apre la tastiera del telecomando/IME.
  LaunchedEffect(Unit) {
    withFrameNanos { }
    try {
      fieldFocusRequester.requestFocus()
    } catch (_: Exception) {
    }
  }

  // FOCUS TRAP: traccia se il focus è ancora dentro il popup. Se esce (es. dopo che
  // la tastiera a schermo è stata chiusa con BACK e il focus è caduto sugli elementi
  // sottostanti delle Impostazioni) viene riportato sul campo di testo, così è sempre
  // possibile riaprire la tastiera.
  var focusInsideDialog by remember { mutableStateOf(false) }
  LaunchedEffect(focusInsideDialog) {
    if (focusInsideDialog) return@LaunchedEffect
    withFrameNanos { }
    try {
      fieldFocusRequester.requestFocus()
    } catch (_: Exception) {
    }
  }

  // OVERLAY IN-WINDOW (nessuna seconda Window di sistema): il dialog vive nella stessa
  // finestra della MainActivity. Così, quando l'IME si chiude con BACK, Android TV non
  // può riassegnare il focus a un'altra finestra e il D-pad resta confinato al popup.
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black.copy(alpha = 0.75f))
      // Lo scrim NON è un target di focus: serve solo a bloccare i tocchi sullo sfondo.
      .focusProperties { canFocus = false }
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null
      ) { /* no-op: impedisce di raggiungere l'interfaccia sottostante */ }
      // BACK chiude il popup (dopo che l'IME ha consumato il primo BACK della tastiera).
      .onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyUp && event.key == Key.Back) {
          onDismiss()
          true
        } else {
          false
        }
      },
    contentAlignment = Alignment.Center
  ) {
    Column(
      modifier = Modifier
        .widthIn(max = 500.dp)
        .fillMaxWidth()
        .padding(horizontal = 24.dp)
        .background(NovaSurface, RoundedCornerShape(20.dp))
        .border(1.5.dp, NovaCyan, RoundedCornerShape(20.dp))
        .padding(24.dp)
        // Delimita il gruppo di navigazione D-pad: il focus resta tra campo e pulsanti.
        .focusGroup()
        .onFocusChanged { focusInsideDialog = it.hasFocus }
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = title,
          color = NovaTextPrimary,
          fontSize = 18.sp,
          fontWeight = FontWeight.Bold
        )
        Icon(
          imageVector = Icons.Default.Close,
          contentDescription = stringResource(R.string.action_close),
          tint = NovaTextSecondary,
          modifier = Modifier
            .size(22.dp)
            // UP dal tasto chiudi non deve uscire dalla Card.
            .focusProperties {
              up = FocusRequester.Cancel
              down = fieldFocusRequester
              left = FocusRequester.Cancel
              right = FocusRequester.Cancel
            }
            .clickable { onDismiss() }
        )
      }

      Spacer(modifier = Modifier.height(14.dp))

      BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = TextStyle(
          color = NovaTextPrimary,
          fontSize = 15.sp,
          fontWeight = FontWeight.Medium
        ),
        cursorBrush = SolidColor(NovaCyanBright),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
          if (text.isNotBlank()) onConfirm(text.trim()) else onDismiss()
        }),
        modifier = Modifier
          .fillMaxWidth()
          .focusRequester(fieldFocusRequester)
          // LEFT/RIGHT non devono portare il focus fuori dalla Card.
          .focusProperties {
            left = FocusRequester.Cancel
            right = FocusRequester.Cancel
          }
          // SU/GIÙ escono dal campo verso i pulsanti (il cursore non si sposta).
          .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown &&
              (event.key == Key.DirectionDown || event.key == Key.DirectionUp)
            ) {
              val moved = focusManager.moveFocus(
                if (event.key == Key.DirectionDown) FocusDirection.Down else FocusDirection.Up
              )
              // Fallback: se la ricerca geometrica non trova nulla, scendi comunque
              // sul pulsante di conferma (nessuna fuga verso lo sfondo).
              if (!moved && event.key == Key.DirectionDown) {
                try {
                  confirmFocusRequester.requestFocus()
                } catch (_: Exception) {
                }
              }
              true
            } else {
              false
            }
          }
          .onKeyEvent { event ->
            if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
              if (event.type == KeyEventType.KeyUp && text.isNotBlank()) {
                onConfirm(text.trim())
              }
              true
            } else {
              false
            }
          },
        decorationBox = { innerTextField ->
          Box0(
            modifier = Modifier
              .fillMaxWidth()
              .background(NovaSurfaceVariant, RoundedCornerShape(10.dp))
              .border(1.dp, NovaCyan.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
              .padding(horizontal = 14.dp, vertical = 12.dp)
          ) {
            if (text.isEmpty()) {
              Text(
                text = hint,
                color = NovaTextMuted,
                fontSize = 14.sp
              )
            }
            innerTextField()
          }
        }
      )

      Spacer(modifier = Modifier.height(20.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically
      ) {
        TvFocusableBox(
          modifier = Modifier
            .focusRequester(cancelFocusRequester)
            .focusProperties {
              // Nessuna uscita dalla Card: DOWN e LEFT annullati, UP torna al campo.
              down = FocusRequester.Cancel
              left = FocusRequester.Cancel
              up = fieldFocusRequester
            },
          shape = RoundedCornerShape(50),
          focusedScale = 1.05f,
          onClick = onDismiss
        ) { isFocused ->
          Text(
            text = stringResource(R.string.action_cancel),
            color = if (isFocused) NovaCyanBright else NovaTextSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
              .background(
                if (isFocused) NovaCyan.copy(alpha = 0.18f) else NovaSurfaceVariant,
                RoundedCornerShape(50)
              )
              .padding(horizontal = 20.dp, vertical = 10.dp)
          )
        }
        TvFocusableBox(
          modifier = Modifier
            .focusRequester(confirmFocusRequester)
            .focusProperties {
              // Nessuna uscita dalla Card: DOWN e RIGHT annullati, UP torna al campo.
              down = FocusRequester.Cancel
              right = FocusRequester.Cancel
              up = fieldFocusRequester
            },
          shape = RoundedCornerShape(50),
          focusedScale = 1.05f,
          onClick = {
            if (text.isNotBlank()) onConfirm(text.trim()) else onDismiss()
          }
        ) { isFocused ->
          Text(
            text = resolvedConfirmLabel,
            color = if (isFocused) NovaSurface else NovaCyanBright,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
              .background(
                if (isFocused) NovaCyanBright else NovaCyan.copy(alpha = 0.16f),
                RoundedCornerShape(50)
              )
              .border(
                1.dp,
                if (isFocused) NovaCyanBright else NovaCyan,
                RoundedCornerShape(50)
              )
              .padding(horizontal = 22.dp, vertical = 10.dp)
          )
        }
      }
    }
  }
}

/** Box interno minimo per il decorationBox (allinea testo/placeholder). */
@Composable
private fun Box0(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  androidx.compose.foundation.layout.Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
    content()
  }
}
