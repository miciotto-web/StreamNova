package com.example.ui.screens

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.ui.components.ModalOverlayState
import com.example.ui.components.TvActionButton
import com.example.ui.components.requestFocusWhenAttached
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.theme.NovaSurface
import kotlinx.coroutines.launch

/** Tag del pannello disclaimer (regressioni UI). */
const val SETTINGS_DISCLAIMER_DIALOG_TAG = "settings_disclaimer_dialog"

/** Tag dell'area scorrevole del testo (regressioni UI). */
const val SETTINGS_DISCLAIMER_BODY_TAG = "settings_disclaimer_body"

/**
 * Pannello informativo «Disclaimer e responsabilità» (Impostazioni → Informazioni
 * legali).
 *
 * È UNICAMENTE informativo e di sola lettura: nessuno stato, nessuna preferenza e
 * nessun effetto su sorgenti, provider, player o sincronizzazione. La finestra è un
 * Dialog vero e proprio: mentre è aperta, la finestra delle Impostazioni non riceve
 * alcun evento D-pad/BACK, e BACK chiude il pannello (onDismissRequest) tornando alle
 * Impostazioni — stesso isolamento di TrackSelectionDialog e degli altri popup.
 *
 * Scorrimento D-pad: il blocco del testo è focalizzabile e consuma SU/GIÙ finché c'è
 * qualcosa da scorrere in quella direzione; ai bordi il tasto torna al grafo del
 * pannello (pattern di [UpdateChangelogBlock]), così la lettura non diventa una
 * trappola per il telecomando e l'azione «Chiudi» resta raggiungibile con il D-pad.
 * Il focus iniziale parte dal testo: il pannello si apre già pronto per la lettura
 * dall'inizio.
 */
@Composable
fun SettingsDisclaimerDialog(onDismiss: () -> Unit) {
  val scrollState = rememberScrollState()
  val scrollStepPx = with(LocalDensity.current) { 48.dp.toPx() }
  val scope = rememberCoroutineScope()
  val bodyFocusRequester = remember { FocusRequester() }

  // Un modale è aperto: come per gli altri popup delle Impostazioni, la gesture LEFT
  // dell'Activity resta disattivata finché il pannello è in scena.
  DisposableEffect(Unit) {
    ModalOverlayState.onShown()
    onDispose { ModalOverlayState.onHidden() }
  }

  // Focus iniziale esplicito: senza questa richiesta il telecomando non avrebbe alcun
  // elemento focale all'apertura del pannello. `requestFocusWhenAttached` ripete la
  // richiesta su qualche frame finché il nodo è agganciato (stesso approccio dei dialog
  // aggiornamento: la prima richiesta può arrivare prima del layout).
  LaunchedEffect(Unit) {
    requestFocusWhenAttached(bodyFocusRequester)
  }

  Dialog(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier
        .width(640.dp)
        .background(NovaSurface, RoundedCornerShape(20.dp))
        .border(1.5.dp, NovaCyan, RoundedCornerShape(20.dp))
        .padding(24.dp)
        .testTag(SETTINGS_DISCLAIMER_DIALOG_TAG)
    ) {
      // Intestazione: titolo del documento + chiusura rapida (click/touch), come negli
      // altri dialoghi delle Impostazioni (TrackSelectionDialog, UpdateResultDialog).
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = stringResource(R.string.settings_disclaimer_title).uppercase(),
          color = NovaTextPrimary,
          fontSize = 17.sp,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.weight(1f)
        )
        Icon(
          imageVector = Icons.Default.Close,
          contentDescription = stringResource(R.string.action_close),
          tint = NovaTextSecondary,
          modifier = Modifier
            .size(22.dp)
            // Chiusura rapida per click/touch (parità con gli altri dialog), ma NON un
            // target del D-pad: su TV il testo è l'unico target del corpo, e SU dal
            // bordo superiore della lettura non deve atterrare sull'icona — l'azione
            // dedicata al telecomando è «Chiudi», sotto al pannello.
            .focusProperties { canFocus = false }
            .clickable { onDismiss() }
        )
      }

      Spacer(modifier = Modifier.height(16.dp))

      // Corpo: area scorrevole ad altezza contenuta (leggibile su Full HD e 4K senza
      // far crescere il pannello a dismisura). Il testo è composto per intero
      // (verticalScroll non è lazy): nessun paragrafo viene tagliato o ricomposto a
      // scorrere, e le asserzioni dei test restano valide fuori viewport.
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 380.dp)
          .verticalScroll(scrollState)
          .focusRequester(bodyFocusRequester)
          .focusable()
          .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
              Key.DirectionDown -> {
                if (scrollState.value >= scrollState.maxValue) {
                  false
                } else {
                  scope.launch { scrollState.animateScrollBy(scrollStepPx) }
                  true
                }
              }
              Key.DirectionUp -> {
                if (scrollState.value <= 0) {
                  false
                } else {
                  scope.launch { scrollState.animateScrollBy(-scrollStepPx) }
                  true
                }
              }
              else -> false
            }
          }
          .testTag(SETTINGS_DISCLAIMER_BODY_TAG)
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          DisclaimerParagraph(R.string.settings_disclaimer_p1)
          DisclaimerParagraph(R.string.settings_disclaimer_p2)
          DisclaimerParagraph(R.string.settings_disclaimer_p3)
          DisclaimerParagraph(R.string.settings_disclaimer_p4)
          DisclaimerParagraph(R.string.settings_disclaimer_p5)
          DisclaimerParagraph(R.string.settings_disclaimer_p6)
          DisclaimerParagraph(R.string.settings_disclaimer_p7)
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      // Azione «Indietro» coerente con le altre schermate (stesso pulsante dei dialog
      // profili): chiude il pannello, e il focus torna alla riga che l'ha aperto.
      TvActionButton(
        text = stringResource(R.string.action_close),
        isPrimary = true,
        onClick = onDismiss,
        modifier = Modifier.align(Alignment.CenterHorizontally)
      )
    }
  }
}

/**
 * Paragrafo del disclaimer: legge la risorsa come testo STILIZZATO (i segmenti
 * `<b>` del testo ufficiale arrivano come span di grassetto) e li riproduce come
 * gerarchia visiva — corpo in secondario, frasi evidenziate in pieno e in grassetto.
 *
 * `getText()` (non `getString()`) è ciò che preserva gli span: la conversione in
 * [AnnotatedString] mantiene lo stesso flusso di testo del documento, senza spezzare
 * i paragrafi in più risorse.
 */
@Composable
private fun DisclaimerParagraph(@StringRes id: Int) {
  val context = LocalContext.current
  val styled = remember(id) { context.resources.getText(id) }
  Text(
    text = styled.toDisclaimerAnnotatedString(),
    color = NovaTextSecondary,
    fontSize = 15.sp,
    lineHeight = 23.sp
  )
}

/**
 * Converte il testo stilizzato della risorsa in [AnnotatedString]: i segmenti
 * grassetto (markup `<b>` della risorsa) passano a pieno titolo e SemiBold, il resto
 * resta nel colore secondario del paragrafo.
 */
private fun CharSequence.toDisclaimerAnnotatedString(): AnnotatedString {
  val spanned = this as? Spanned ?: return AnnotatedString(this.toString())
  return buildAnnotatedString {
    append(spanned.toString())
    for (span in spanned.getSpans(0, spanned.length, Any::class.java)) {
      if (span is StyleSpan && (span.style and Typeface.BOLD) != 0) {
        addStyle(
          SpanStyle(fontWeight = FontWeight.SemiBold, color = NovaTextPrimary),
          spanned.getSpanStart(span),
          spanned.getSpanEnd(span)
        )
      }
    }
  }
}
