package com.example.ui.screens

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.data.update.ApkInstaller
import com.example.data.update.DownloadErrorReason
import com.example.data.update.UpdateCheckErrorReason
import com.example.data.update.UpdateCheckState
import com.example.data.update.UpdateDownloadState
import com.example.ui.components.ModalOverlayState
import com.example.ui.components.TvFocusableBox
import com.example.ui.components.requestFocusWhenAttached
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaGreen
import com.example.ui.theme.NovaRed
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import kotlinx.coroutines.launch
import java.io.File

/**
 * Guardia sul tasto di conferma di un modale che compare SENZA un'azione
 * dell'utente: il rilascio di una pressione iniziata altrove (es. durante la
 * splash) verrebbe consegnato al nuovo modale e letto come click, cioè una scelta
 * che l'utente non ha fatto.
 *
 * Accetta solo il rilascio di una pressione NUOVA — un `KeyDown` con
 * `repeatCount == 0` visto dopo l'apertura — esattamente come fa il menu
 * contestuale delle card ([com.example.ui.components.MediaContextMenu]).
 *
 * [onKeyEvent] restituisce true quando l'evento va consumato: il chiamante deve
 * fermarne la propagazione, altrimenti il rilascio "vecchio" raggiunge comunque
 * il pulsante a fuoco.
 */
internal class ConfirmKeyGuard {

  private var freshPress = false

  /** true se [keyCode] è un tasto di conferma del telecomando. */
  fun isConfirmKey(keyCode: Int): Boolean = keyCode in CONFIRM_KEYS

  /**
   * Registra un evento di tasto e decide se consumarlo.
   *
   * [repeatCount] è quello dell'evento nativo: solo la pressione iniziale è
   * "nuova", le ripetizioni di un tasto tenuto premuto non riabilitano il click.
   */
  fun onKeyEvent(keyCode: Int, action: Int, repeatCount: Int): Boolean = when {
    !isConfirmKey(keyCode) -> false
    action == android.view.KeyEvent.ACTION_DOWN -> {
      if (repeatCount == 0) freshPress = true
      false
    }
    action == android.view.KeyEvent.ACTION_UP -> {
      if (freshPress) {
        freshPress = false
        false
      } else {
        true
      }
    }
    else -> false
  }

  companion object {
    private val CONFIRM_KEYS = setOf(
      android.view.KeyEvent.KEYCODE_DPAD_CENTER,
      android.view.KeyEvent.KEYCODE_ENTER,
      android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
    )
  }
}

/**
 * Popup dell'aggiornamento rilevato dal controllo automatico di avvio.
 *
 * Informa e chiede: versione installata, versione disponibile, note di rilascio
 * quando la release le pubblica, e le due scelte «Aggiorna» e «Più tardi».
 * Nessun download parte da solo: il pulsante di aggiornamento è l'unica via, e
 * l'installer di sistema resta subordinato a un secondo click esplicito
 * («Installa»), come nel dialog delle Impostazioni.
 *
 * Il popup compare senza che l'utente l'abbia aperto: la guardia sul tasto di
 * conferma (stesso schema di [com.example.ui.components.MediaContextMenu])
 * scarta il rilascio di una pressione iniziata prima dell'apertura, così un tasto
 * ancora premuto altrove non sceglie al posto dell'utente.
 */
@Composable
internal fun UpdatePromptDialog(
  update: UpdateCheckState.UpdateAvailable,
  downloadState: UpdateDownloadState,
  onUpdate: () -> Unit,
  onLater: () -> Unit
) {
  val confirmKeyGuard = remember { ConfirmKeyGuard() }
  val updateFocusRequester = remember { FocusRequester() }

  Dialog(onDismissRequest = onLater) {
    Column(
      modifier = Modifier
        .width(420.dp)
        .background(NovaSurface, RoundedCornerShape(20.dp))
        .border(1.5.dp, NovaCyan, RoundedCornerShape(20.dp))
        // In PREVIEW: è l'unico punto che vede il tasto prima del pulsante a fuoco,
        // che consuma da sé il rilascio del tasto di conferma.
        .onPreviewKeyEvent { keyEvent ->
          confirmKeyGuard.onKeyEvent(
            keyCode = keyEvent.nativeKeyEvent.keyCode,
            action = keyEvent.nativeKeyEvent.action,
            repeatCount = keyEvent.nativeKeyEvent.repeatCount
          )
        }
        .padding(24.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = stringResource(R.string.settings_update_prompt_title),
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
            .clickable { onLater() }
        )
      }

      Spacer(modifier = Modifier.height(16.dp))

      Text(
        text = stringResource(
          R.string.settings_update_installed_version,
          update.currentVersion
        ),
        color = NovaTextSecondary,
        fontSize = 14.sp
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = stringResource(R.string.settings_update_available, update.newVersion),
        color = NovaCyanBright,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold
      )

      UpdateChangelogBlock(update.changelog)

      Spacer(modifier = Modifier.height(18.dp))

      if (downloadState is UpdateDownloadState.Idle) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          UpdateDialogButton(
            text = stringResource(R.string.settings_update_prompt_update),
            onClick = onUpdate,
            modifier = Modifier
              .weight(1f)
              .focusRequester(updateFocusRequester)
          )
          UpdateDialogButton(
            text = stringResource(R.string.settings_update_prompt_later),
            onClick = onLater,
            modifier = Modifier.weight(1f)
          )
        }
        // Focus sulla scelta utile: è la ragione per cui il popup esiste, e chi vuole
        // rimandare ha «Più tardi» a un tasto di distanza.
        LaunchedEffect(Unit) {
          requestFocusWhenAttached(updateFocusRequester)
        }
      } else {
        // Download in corso, completato o fallito: la conduzione passa al blocco
        // condiviso con il dialog delle Impostazioni (progresso, «Installa», riprova).
        UpdateDownloadInstallSection(downloadState = downloadState, onDownload = onUpdate)
        Spacer(modifier = Modifier.height(10.dp))
        UpdateDialogButton(
          text = stringResource(R.string.action_close),
          onClick = onLater
        )
      }
    }
  }
}

/**
 * Dialog con l'esito del controllo aggiornamenti MANUALE (Impostazioni) e, quando
 * disponibile, il flusso download APK verificato + avvio ESPLICITO dell'installer.
 *
 * L'installer di sistema non viene mai aperto in automatico: parte solo alla
 * pressione del pulsante "Installa", dopo che il file è stato scaricato e
 * verificato.
 */
@Composable
internal fun UpdateResultDialog(
  state: UpdateCheckState,
  downloadState: UpdateDownloadState,
  onDownload: () -> Unit,
  onDismiss: () -> Unit
) {
  // Un modale è aperto: il popup dell'aggiornamento automatico resta in attesa finché
  // questo dialog è in scena, invece di comparire sopra di esso.
  DisposableEffect(Unit) {
    ModalOverlayState.onShown()
    onDispose { ModalOverlayState.onHidden() }
  }

  Dialog(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier
        .width(380.dp)
        .background(NovaSurface, RoundedCornerShape(20.dp))
        .border(1.5.dp, NovaCyan, RoundedCornerShape(20.dp))
        .padding(24.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = stringResource(R.string.settings_check_updates),
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
            .clickable { onDismiss() }
        )
      }

      Spacer(modifier = Modifier.height(16.dp))

      when (state) {
        is UpdateCheckState.UpToDate -> {
          Text(
            text = stringResource(
              R.string.settings_update_installed_version,
              state.currentVersion
            ),
            color = NovaTextSecondary,
            fontSize = 14.sp
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = stringResource(R.string.settings_update_upto_date),
            color = NovaGreen,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
          )
        }
        is UpdateCheckState.UpdateAvailable -> {
          Text(
            text = stringResource(
              R.string.settings_update_installed_version,
              state.currentVersion
            ),
            color = NovaTextSecondary,
            fontSize = 14.sp
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = stringResource(R.string.settings_update_available, state.newVersion),
            color = NovaCyanBright,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
          )
          state.assetName?.let { assetName ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = stringResource(R.string.settings_update_asset_name, assetName),
              color = NovaTextMuted,
              fontSize = 12.sp
            )
          }

          // Changelog della release: area scorrevole di altezza contenuta,
          // leggibile con il D-pad su Android TV.
          UpdateChangelogBlock(state.changelog)

          Spacer(modifier = Modifier.height(14.dp))

          if (downloadState is UpdateDownloadState.Idle) {
            val downloadFocusRequester = remember { FocusRequester() }
            UpdateDialogButton(
              text = stringResource(R.string.settings_update_download),
              onClick = onDownload,
              modifier = Modifier
                .fillMaxWidth()
                .focusRequester(downloadFocusRequester)
            )
            LaunchedEffect(Unit) {
              requestFocusWhenAttached(downloadFocusRequester)
            }
          } else {
            UpdateDownloadInstallSection(
              downloadState = downloadState,
              onDownload = onDownload
            )
          }
        }
        is UpdateCheckState.VersionMismatch -> {
          Text(
            text = stringResource(
              R.string.settings_update_installed_version,
              state.currentVersion
            ),
            color = NovaTextSecondary,
            fontSize = 14.sp
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = stringResource(
              R.string.settings_update_mismatch,
              state.tagVersion,
              state.assetVersion ?: "?"
            ),
            color = NovaRed,
            fontSize = 14.sp
          )
          state.assetName?.let { assetName ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = stringResource(R.string.settings_update_asset_name, assetName),
              color = NovaTextMuted,
              fontSize = 12.sp
            )
          }
        }
        is UpdateCheckState.Error -> {
          Text(
            text = updateCheckErrorText(state.reason),
            color = NovaRed,
            fontSize = 14.sp
          )
        }
        else -> Unit
      }

      Spacer(modifier = Modifier.height(20.dp))

      UpdateDialogButton(text = stringResource(R.string.action_close), onClick = onDismiss)
    }
  }
}

/**
 * Blocco condiviso del download: progresso, completamento con il pulsante
 * «Installa», errore con «riprova». Usato sia dal popup di avvio sia dal dialog
 * delle Impostazioni, così la verifica del file e l'avvio ESPLICITO dell'installer
 * hanno un'unica implementazione.
 *
 * Lo stato [UpdateDownloadState.Idle] non è gestito qui: il pulsante che avvia il
 * download appartiene al chiamante, che ne sceglie l'etichetta.
 */
@Composable
internal fun UpdateDownloadInstallSection(
  downloadState: UpdateDownloadState,
  onDownload: () -> Unit
) {
  val context = LocalContext.current
  val canInstall = ApkInstaller.canInstall(context)
  // Esito dell'ultimo tentativo ESPLICITO di avvio installer: resta visibile
  // così l'utente non finisce in uno stato apparentemente completato.
  var installErrorRes by remember { mutableStateOf<Int?>(null) }

  when (downloadState) {
    UpdateDownloadState.Idle -> Unit
    is UpdateDownloadState.Downloading -> {
      val percent = downloadState.percent
      if (percent != null) {
        LinearProgressIndicator(
          progress = { percent / 100f },
          modifier = Modifier.fillMaxWidth()
        )
      } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      }
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = stringResource(R.string.settings_update_downloading) +
          if (percent != null) " $percent%" else "",
        color = NovaTextSecondary,
        fontSize = 13.sp
      )
    }
    is UpdateDownloadState.Completed -> {
      val installFocusRequester = remember { FocusRequester() }
      Text(
        text = stringResource(R.string.settings_update_download_complete),
        color = NovaGreen,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold
      )
      Spacer(modifier = Modifier.height(10.dp))
      if (canInstall) {
        UpdateDialogButton(
          text = stringResource(R.string.settings_update_install),
          onClick = {
            val apk = File(downloadState.apkPath)
            val launched = ApkInstaller.launch(context, apk)
            installErrorRes = when {
              launched -> null
              !apk.exists() || apk.length() <= 0L ->
                R.string.settings_update_install_failed_file
              !ApkInstaller.canInstall(context) ->
                R.string.settings_update_install_failed_permission
              else -> R.string.settings_update_install_failed_generic
            }
          },
          modifier = Modifier
            .fillMaxWidth()
            .focusRequester(installFocusRequester)
        )
        // Il download appena finito lascia il focus sul nodo del pulsante che è sparito:
        // senza questa richiesta il D-pad non troverebbe più nulla da attivare.
        LaunchedEffect(Unit) {
          requestFocusWhenAttached(installFocusRequester)
        }
      } else {
        Text(
          text = stringResource(R.string.settings_update_install_permission_needed),
          color = NovaTextSecondary,
          fontSize = 13.sp
        )
        Spacer(modifier = Modifier.height(10.dp))
        UpdateDialogButton(
          text = stringResource(R.string.settings_update_install_permission_button),
          onClick = { ApkInstaller.openInstallPermissionSettings(context) }
        )
      }
      // Esito dell'ultimo tentativo ESPLICITO di avvio installer: resta visibile
      // così l'utente non finisce in uno stato apparentemente completato.
      installErrorRes?.let { errorRes ->
        Spacer(modifier = Modifier.height(10.dp))
        Text(
          text = stringResource(errorRes),
          color = NovaRed,
          fontSize = 13.sp
        )
        if (errorRes == R.string.settings_update_install_failed_permission) {
          Spacer(modifier = Modifier.height(10.dp))
          UpdateDialogButton(
            text = stringResource(R.string.settings_update_install_permission_button),
            onClick = { ApkInstaller.openInstallPermissionSettings(context) }
          )
        }
      }
    }
    is UpdateDownloadState.Error -> {
      Text(
        text = downloadErrorText(downloadState.reason),
        color = NovaRed,
        fontSize = 14.sp
      )
      Spacer(modifier = Modifier.height(10.dp))
      UpdateDialogButton(
        text = stringResource(R.string.settings_update_download),
        onClick = onDownload
      )
    }
  }
}

/** Pulsante standard dei dialog: evidenziato ciano quando è in focus (D-pad). */
@Composable
private fun UpdateDialogButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier.fillMaxWidth()
) {
  TvFocusableBox(
    shape = RoundedCornerShape(12.dp),
    focusedScale = 1.02f,
    onClick = onClick,
    modifier = modifier
  ) { isFocused ->
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          if (isFocused) NovaCyanBright else NovaSurfaceVariant,
          RoundedCornerShape(12.dp)
        )
        .padding(vertical = 10.dp),
      contentAlignment = Alignment.Center
    ) {
      Text(
        text = text,
        // Fondo ciano pieno in focus: il testo passa all'inchiostro scuro dello sfondo.
        color = if (isFocused) NovaBackground else NovaTextPrimary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold
      )
    }
  }
}

/**
 * Changelog della release in area scorrevole ad altezza contenuta: leggibile su
 * Android TV con il D-pad senza far crescere il dialog a dismisura.
 *
 * Il blocco è focalizzabile e consuma SU/ GIÙ per scorrere, ma solo finché c'è
 * qualcosa da scorrere in quella direzione: arrivato in fondo il tasto torna al
 * grafo del dialog, così la lettura non diventa una trappola per il telecomando.
 */
@Composable
internal fun UpdateChangelogBlock(changelog: String?) {
  if (changelog.isNullOrBlank()) return
  val scrollState = rememberScrollState()
  val scrollStepPx = with(LocalDensity.current) { 48.dp.toPx() }
  val scope = rememberCoroutineScope()

  Spacer(modifier = Modifier.height(12.dp))
  Text(
    text = stringResource(R.string.settings_update_changelog),
    color = NovaTextPrimary,
    fontSize = 14.sp,
    fontWeight = FontWeight.Bold
  )
  Spacer(modifier = Modifier.height(6.dp))
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(max = 180.dp)
      .background(NovaSurfaceVariant, RoundedCornerShape(10.dp))
      .verticalScroll(scrollState)
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
      .padding(10.dp)
  ) {
    Text(
      text = changelog,
      color = NovaTextSecondary,
      fontSize = 13.sp
    )
  }
}

/** Messaggio localizzato del motivo di errore del download. */
@Composable
private fun downloadErrorText(reason: DownloadErrorReason): String = when (reason) {
  DownloadErrorReason.NO_APK_IN_RELEASE ->
    stringResource(R.string.settings_update_dl_error_no_apk)
  DownloadErrorReason.NO_NETWORK ->
    stringResource(R.string.settings_update_dl_error_network)
  DownloadErrorReason.TIMEOUT ->
    stringResource(R.string.settings_update_dl_error_timeout)
  DownloadErrorReason.HTTP_ERROR ->
    stringResource(R.string.settings_update_dl_error_http)
  DownloadErrorReason.HOST_NOT_ALLOWED ->
    stringResource(R.string.settings_update_dl_error_host)
  DownloadErrorReason.INTERRUPTED ->
    stringResource(R.string.settings_update_dl_error_interrupted)
  DownloadErrorReason.FILE_MISSING ->
    stringResource(R.string.settings_update_dl_error_file)
  DownloadErrorReason.INSUFFICIENT_SPACE ->
    stringResource(R.string.settings_update_dl_error_space)
  DownloadErrorReason.SIZE_MISMATCH ->
    stringResource(R.string.settings_update_dl_error_size)
  DownloadErrorReason.CHECKSUM_MISMATCH ->
    stringResource(R.string.settings_update_dl_error_checksum)
  DownloadErrorReason.UNKNOWN ->
    stringResource(R.string.settings_update_dl_error_generic)
}

/** Messaggio localizzato del motivo di errore del controllo aggiornamenti. */
@Composable
private fun updateCheckErrorText(reason: UpdateCheckErrorReason): String = when (reason) {
  UpdateCheckErrorReason.RATE_LIMITED ->
    stringResource(R.string.settings_update_error_rate_limited)
  UpdateCheckErrorReason.NOT_FOUND ->
    stringResource(R.string.settings_update_error_not_found)
  UpdateCheckErrorReason.NETWORK ->
    stringResource(R.string.settings_update_error_network)
  UpdateCheckErrorReason.INVALID_RELEASE ->
    stringResource(R.string.settings_update_error_invalid_release)
  UpdateCheckErrorReason.NO_COMPATIBLE_APK ->
    stringResource(R.string.settings_update_error_no_compatible_apk)
  UpdateCheckErrorReason.UNKNOWN ->
    stringResource(R.string.settings_update_error)
}
