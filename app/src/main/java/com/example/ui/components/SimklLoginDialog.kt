package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.remote.simkl.SimklLoginPrompt
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaOrange
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import kotlinx.coroutines.delay

// Palette del pannello, allineata al riferimento: blu notte desaturato, wordmark e link
// in azzurro tenue, codice in bianco.
private val SimklPanelBg = Color(0xFF16243E)
private val SimklPanelBorder = Color(0xFF2E4A73)
private val SimklWordmarkColor = Color(0xFFA9C7E6)
private val SimklLinkColor = Color(0xFF9EC1E0)

/**
 * Tag dei due pulsanti. Servono ai test per verificare il focus reale: lo sfondo del pannello
 * è un nodo semantico che unisce i discendenti (ha un'azione di click che blocca i tocchi),
 * quindi il testo di un pulsante non è leggibile dal nodo che possiede il focus.
 */
internal const val SIMKL_LOGIN_ANNULLA_TAG = "simkl_login_annulla"
internal const val SIMKL_LOGIN_RETRY_TAG = "simkl_login_nuovo_codice"

/**
 * Login SIMKL per TV: QR grande, codice temporaneo e attesa dell'approvazione.
 *
 * Composizione (riferimento di interfaccia): wordmark Simkl, istruzione in italiano, QR ad
 * alto contrasto su card bianca, codice, indirizzo di verifica, conto alla rovescia, stato
 * dell'attesa e pulsante "Annulla" centrato. Alla scadenza (o dopo un errore) compare
 * "Nuovo codice" **senza chiudere il pannello**.
 *
 * Il testo dell'istruzione dipende da cosa contiene davvero il QR ([SimklLoginPrompt.qrPrefillsCode]):
 * "il codice è già inserito" si dice solo quando SIMKL ha restituito `verification_uri_complete`,
 * altrimenti si chiede di digitare il codice.
 *
 * Il pannello scala con l'altezza disponibile: su una TV 1080p lo spazio utile è di circa
 * 960×540 dp, quindi le misure fisse di un mockup da telefono non entrerebbero.
 *
 * Focus D-pad: grafo esplicito fra i pulsanti con `Cancel` su tutte le altre direzioni, così
 * il focus non può uscire dal pannello; nessun "focus trap" che riassegna il focus a ogni
 * frame (ruberebbe la pressione del tasto centrale al pulsante).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SimklLoginDialog(
  state: SimklLoginUiState,
  onRetry: () -> Unit,
  onDismiss: () -> Unit,
  nowMs: () -> Long = System::currentTimeMillis
) {
  val prompt = state.prompt
  var secondsLeft by remember(prompt?.expiresAtEpochMs) {
    mutableStateOf(prompt?.secondsLeft(nowMs()) ?: 0L)
  }

  // Conto alla rovescia in tempo reale. Non fa nulla da solo: il polling si ferma per conto
  // suo alla scadenza, e il pannello offre un nuovo codice.
  LaunchedEffect(prompt?.expiresAtEpochMs) {
    val activePrompt = prompt ?: return@LaunchedEffect
    while (true) {
      val left = activePrompt.secondsLeft(nowMs())
      secondsLeft = left
      if (left <= 0L) break
      delay(1_000L)
    }
  }

  // Segnala all'Activity che un modale in-window è aperto (gesture LEFT disattivata).
  DisposableEffect(Unit) {
    ModalOverlayState.onShown()
    onDispose { ModalOverlayState.onHidden() }
  }

  BackHandler { onDismiss() }

  // Focus iniziale sull'azione utile: "Nuovo codice" se il codice attuale non è più
  // utilizzabile, altrimenti "Annulla" (unica azione mentre si attende).
  val annullaFocusRequester = remember { FocusRequester() }
  val retryFocusRequester = remember { FocusRequester() }
  LaunchedEffect(state.canRetry, prompt) {
    withFrameNanos { }
    runCatching {
      if (state.canRetry) retryFocusRequester.requestFocus() else annullaFocusRequester.requestFocus()
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black.copy(alpha = 0.78f))
      .focusProperties { canFocus = false }
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null
      ) { /* blocca i tocchi sullo sfondo */ },
    contentAlignment = Alignment.Center
  ) {
    BoxWithConstraints(
      modifier = Modifier
        .widthIn(max = 620.dp)
        .fillMaxWidth()
        .padding(horizontal = 24.dp),
      contentAlignment = Alignment.Center
    ) {
      // Il QR è l'elemento che deve restare grande: prende un terzo dell'altezza utile,
      // con un minimo leggibile e un massimo oltre il quale non serve ingrandire.
      val qrSize = (maxHeight * 0.34f).coerceIn(132.dp, 216.dp)

      Column(
        modifier = Modifier
          .background(SimklPanelBg, RoundedCornerShape(24.dp))
          .border(1.dp, SimklPanelBorder, RoundedCornerShape(24.dp))
          .padding(horizontal = 28.dp, vertical = 20.dp)
          .focusGroup(),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        SimklWordmark()

        if (prompt != null) {
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = simklLoginInstruction(prompt),
            color = NovaTextPrimary,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
          )
        }

        Spacer(modifier = Modifier.height(14.dp))

        QrSlot(prompt = prompt, isError = state.isError, size = qrSize)

        if (prompt != null) {
          Spacer(modifier = Modifier.height(12.dp))
          Text(
            text = prompt.userCode,
            color = NovaTextPrimary,
            fontSize = 38.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 3.sp,
            textAlign = TextAlign.Center
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = prompt.verificationUri,
            color = SimklLinkColor,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = simklCountdownLabel(secondsLeft),
            color = if (secondsLeft > 0L) NovaTextPrimary else NovaOrange,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
          )
        }

        val statusText = when {
          state.statusMessage != null -> state.statusMessage
          state.waiting && prompt != null -> SIMKL_WAITING_APPROVAL_MESSAGE
          else -> null
        }
        if (statusText != null) {
          Spacer(modifier = Modifier.height(10.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
          ) {
            if (state.waiting) {
              CircularProgressIndicator(
                color = NovaCyanBright,
                modifier = Modifier.size(15.dp),
                strokeWidth = 2.dp
              )
              Spacer(modifier = Modifier.size(9.dp))
            }
            Text(
              text = statusText,
              color = if (state.isError) NovaOrange else NovaCyanBright,
              fontSize = 14.sp,
              lineHeight = 19.sp,
              textAlign = TextAlign.Center
            )
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
          verticalAlignment = Alignment.CenterVertically
        ) {
          TvFocusableBox(
            shape = RoundedCornerShape(50),
            focusedScale = 1.05f,
            onClick = onDismiss,
            modifier = Modifier
              .focusRequester(annullaFocusRequester)
              .testTag(SIMKL_LOGIN_ANNULLA_TAG)
              .focusProperties {
                // Nessuna via d'uscita dal pannello: solo i pulsanti tra loro.
                up = FocusRequester.Cancel
                down = FocusRequester.Cancel
                left = FocusRequester.Cancel
                if (state.canRetry) right = retryFocusRequester else right = FocusRequester.Cancel
              }
          ) { isFocused ->
            Text(
              text = "Annulla",
              color = if (isFocused) SimklPanelBg else NovaTextSecondary,
              fontSize = 14.sp,
              fontWeight = FontWeight.SemiBold,
              textAlign = TextAlign.Center,
              modifier = Modifier
                .background(
                  if (isFocused) NovaCyanBright else NovaSurfaceVariant,
                  RoundedCornerShape(50)
                )
                .padding(horizontal = 26.dp, vertical = 10.dp)
            )
          }
          if (state.canRetry) {
            TvFocusableBox(
              shape = RoundedCornerShape(50),
              focusedScale = 1.05f,
              onClick = onRetry,
              modifier = Modifier
                .focusRequester(retryFocusRequester)
                .testTag(SIMKL_LOGIN_RETRY_TAG)
                .focusProperties {
                  up = FocusRequester.Cancel
                  down = FocusRequester.Cancel
                  right = FocusRequester.Cancel
                  left = annullaFocusRequester
                }
            ) { isFocused ->
              // Sfondo e bordo stanno sul contenuto del pulsante (icona + testo): se
              // stessero solo sul Text, la pillola resterebbe più stretta dell'anello di
              // focus disegnato da TvFocusableBox.
              Row(
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
                  .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Refresh,
                  contentDescription = null,
                  tint = if (isFocused) SimklPanelBg else NovaCyanBright,
                  modifier = Modifier.size(15.dp)
                )
                Text(
                  text = "Nuovo codice",
                  color = if (isFocused) SimklPanelBg else NovaCyanBright,
                  fontSize = 14.sp,
                  fontWeight = FontWeight.Bold,
                  textAlign = TextAlign.Center
                )
              }
            }
          }
        }
      }
    }
  }
}

/** Wordmark Simkl del pannello: logo testuale, nessun asset di terze parti nel repository. */
@Composable
private fun SimklWordmark() {
  Row(verticalAlignment = Alignment.Top) {
    Text(
      text = "SIMKL",
      color = SimklWordmarkColor,
      fontSize = 30.sp,
      fontWeight = FontWeight.Black,
      letterSpacing = 2.sp
    )
    Text(
      text = "™",
      color = SimklWordmarkColor,
      fontSize = 10.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(top = 2.dp)
    )
  }
}

/**
 * Area del QR: la card bianca ad alto contrasto quando il codice esiste, altrimenti un
 * segnaposto della stessa misura (rotella mentre il codice viene richiesto) così il
 * pannello non cambia dimensione passando da uno stato all'altro.
 */
@Composable
private fun QrSlot(prompt: SimklLoginPrompt?, isError: Boolean, size: Dp) {
  val cardModifier = Modifier
    .size(size)
    .background(
      if (prompt != null) Color.White else NovaSurfaceVariant,
      RoundedCornerShape(14.dp)
    )
    .padding(size * 0.06f)
  if (prompt != null) {
    Box(modifier = cardModifier) {
      QrCodeImage(
        value = prompt.qrPayload,
        contentDescription = "QR di accesso SIMKL",
        modifier = Modifier.fillMaxSize()
      )
    }
    return
  }
  Box(modifier = cardModifier, contentAlignment = Alignment.Center) {
    if (!isError) {
      CircularProgressIndicator(color = NovaCyanBright, modifier = Modifier.size(26.dp), strokeWidth = 3.dp)
    } else {
      Text(
        text = "Nessun codice",
        color = NovaTextSecondary,
        fontSize = 13.sp,
        textAlign = TextAlign.Center
      )
    }
  }
}

/** Istruzione mostrata sotto il wordmark, coerente con il contenuto reale del QR. */
internal fun simklLoginInstruction(prompt: SimklLoginPrompt): String =
  if (prompt.qrPrefillsCode) {
    "Inquadra il QR con il telefono: la pagina di verifica Simkl si apre con il codice già " +
      "inserito e ti resta solo da confermare l'accesso."
  } else {
    "Apri la pagina di verifica Simkl e inserisci questo codice."
  }

/** "Il codice scade in 14m 41s" / "in 45s" / "Il codice è scaduto.", come nel riferimento. */
internal fun simklCountdownLabel(secondsLeft: Long): String = when {
  secondsLeft <= 0L -> "Il codice è scaduto."
  secondsLeft >= 60L -> "Il codice scade in ${secondsLeft / 60}m ${secondsLeft % 60}s"
  else -> "Il codice scade in ${secondsLeft}s"
}
