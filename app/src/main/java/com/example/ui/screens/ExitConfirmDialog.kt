package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.ui.components.ModalOverlayState
import com.example.ui.components.TvFocusableBox
import com.example.ui.components.requestFocusWhenAttached
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaRed
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary

/**
 * Popup di conferma dell'uscita dall'app, mostrato al posto del vecchio «doppio BACK».
 *
 * Compare sulla Home alla prima pressione di BACK: l'uscita avviene solo con la scelta
 * esplicita «Esci», mentre «Annulla» (a fuoco all'apertura, scelta non distruttiva) e un
 * BACK chiudono il popup lasciando l'app aperta.
 *
 * Vive in una finestra [Dialog] separata: mentre è aperto i tasti non raggiungono la Home
 * sottostante e il BACK è gestito qui ([BackHandler] + `onDismissRequest`), quindi non può
 * riattivare il gestore di uscita della Home.
 */
@Composable
fun ExitConfirmDialog(
  onConfirm: () -> Unit,
  onDismiss: () -> Unit
) {
  val cancelFocusRequester = remember { FocusRequester() }

  // Il popup è un modale in-window: finché è aperto la Home non deve eseguire gesture o
  // azioni delle card se un tasto arrivasse prima che la finestra del Dialog abbia il focus.
  DisposableEffect(Unit) {
    ModalOverlayState.onShown()
    onDispose { ModalOverlayState.onHidden() }
  }

  // Il BACK equivale ad annullare: il popup si chiude e l'app resta aperta.
  BackHandler { onDismiss() }

  Dialog(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier
        .width(420.dp)
        .background(NovaSurface, RoundedCornerShape(20.dp))
        .border(1.5.dp, NovaCyan, RoundedCornerShape(20.dp))
        .padding(24.dp)
    ) {
      Text(
        text = stringResource(R.string.exit_confirm_title),
        color = NovaTextPrimary,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold
      )

      Spacer(modifier = Modifier.height(10.dp))

      Text(
        text = stringResource(R.string.exit_confirm_message),
        color = NovaTextSecondary,
        fontSize = 14.sp
      )

      Spacer(modifier = Modifier.height(22.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        ExitDialogButton(
          text = stringResource(R.string.action_cancel),
          onClick = onDismiss,
          modifier = Modifier
            .weight(1f)
            .focusRequester(cancelFocusRequester)
        )
        ExitDialogButton(
          text = stringResource(R.string.action_exit),
          onClick = onConfirm,
          accentColor = NovaRed,
          modifier = Modifier.weight(1f)
        )
      }
    }

    // Focus iniziale su «Annulla»: un OK involontario non chiude l'app.
    LaunchedEffect(Unit) {
      requestFocusWhenAttached(cancelFocusRequester)
    }
  }
}

/** Pulsante del popup di uscita: pieno nel colore d'accento quando è a fuoco (D-pad). */
@Composable
private fun ExitDialogButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier.fillMaxWidth(),
  accentColor: Color = NovaCyanBright
) {
  TvFocusableBox(
    shape = RoundedCornerShape(12.dp),
    focusedScale = 1.02f,
    focusedBorderColor = accentColor,
    onClick = onClick,
    modifier = modifier
  ) { isFocused ->
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          if (isFocused) accentColor else NovaSurfaceVariant,
          RoundedCornerShape(12.dp)
        )
        .padding(vertical = 11.dp),
      contentAlignment = Alignment.Center
    ) {
      Text(
        text = text,
        // Fondo pieno in focus: il testo passa all'inchiostro scuro dello sfondo, tranne
        // sull'uscita (rosso) dove il bianco resta più leggibile.
        color = if (isFocused && accentColor != NovaRed) NovaBackground else NovaTextPrimary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold
      )
    }
  }
}
