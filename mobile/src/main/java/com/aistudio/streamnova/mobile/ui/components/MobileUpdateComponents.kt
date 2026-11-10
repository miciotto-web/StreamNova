package com.aistudio.streamnova.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aistudio.streamnova.mobile.R
import com.example.data.update.ApkInstaller
import com.example.data.update.DownloadErrorReason
import com.example.data.update.UpdateCheckErrorReason
import com.example.data.update.UpdateCheckState
import com.example.data.update.UpdateDownloadState
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaGreen
import com.example.ui.theme.NovaRed
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import java.io.File

/**
 * Dialog dell'esito del controllo aggiornamenti MANUALE (Impostazioni Mobile).
 *
 * Port del `UpdateResultDialog` TV adattato al touch: stessa catena di stati di
 * [UpdateCheckState] e stesso flusso download APK verificato → avvio ESPLICITO
 * dell'installer (mai in automatico), con pulsanti touch al posto del D-pad.
 */
@Composable
fun MobileUpdateResultDialog(
  state: UpdateCheckState,
  downloadState: UpdateDownloadState,
  onDownload: () -> Unit,
  onDismiss: () -> Unit
) {
  MobileUpdateDialogContainer(title = stringResource(R.string.settings_check_updates), onDismiss) {
    when (state) {
      is UpdateCheckState.UpToDate -> {
        MobileUpdateVersionBlock(
          installedVersion = state.currentVersion,
          headline = stringResource(R.string.settings_update_upto_date),
          headlineColor = NovaGreen
        )
      }
      is UpdateCheckState.UpdateAvailable -> {
        MobileUpdateVersionBlock(
          installedVersion = state.currentVersion,
          headline = stringResource(R.string.settings_update_available, state.newVersion),
          headlineColor = NovaCyanBright
        )
        state.assetName?.let { assetName ->
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = stringResource(R.string.settings_update_asset_name, assetName),
            color = NovaTextMuted,
            fontSize = 12.sp
          )
        }
        MobileUpdateChangelogBlock(state.changelog)
        Spacer(modifier = Modifier.height(14.dp))
        MobileUpdateDownloadInstallSection(downloadState = downloadState, onDownload = onDownload)
      }
      is UpdateCheckState.VersionMismatch -> {
        MobileUpdateVersionBlock(
          installedVersion = state.currentVersion,
          headline = stringResource(
            R.string.settings_update_mismatch,
            state.tagVersion,
            state.assetVersion ?: "?"
          ),
          headlineColor = NovaRed
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
          text = mobileUpdateCheckErrorText(state.reason),
          color = NovaRed,
          fontSize = 14.sp
        )
      }
      // Idle/Checking: il dialog non è in scena in questi stati.
      else -> Unit
    }

    Spacer(modifier = Modifier.height(20.dp))
    MobileUpdateDialogButton(text = stringResource(R.string.action_close), onClick = onDismiss)
  }
}

/**
 * Popup dell'aggiornamento rilevato dal controllo AUTOMATICO di avvio.
 *
 * Port del `UpdatePromptDialog` TV: informa (versioni, note di rilascio) e
 * chiede con «Aggiorna» / «Più tardi». Nessun download parte da solo.
 */
@Composable
fun MobileUpdatePromptDialog(
  update: UpdateCheckState.UpdateAvailable,
  downloadState: UpdateDownloadState,
  onUpdate: () -> Unit,
  onLater: () -> Unit
) {
  MobileUpdateDialogContainer(
    title = stringResource(R.string.settings_update_prompt_title),
    onDismiss = onLater
  ) {
    Text(
      text = stringResource(R.string.settings_update_installed_version, update.currentVersion),
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
    MobileUpdateChangelogBlock(update.changelog)
    Spacer(modifier = Modifier.height(18.dp))

    if (downloadState is UpdateDownloadState.Idle) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        MobileUpdateDialogButton(
          text = stringResource(R.string.settings_update_prompt_update),
          onClick = onUpdate,
          modifier = Modifier.weight(1f)
        )
        MobileUpdateDialogButton(
          text = stringResource(R.string.settings_update_prompt_later),
          onClick = onLater,
          modifier = Modifier.weight(1f)
        )
      }
    } else {
      // Download in corso, completato o fallito: conduzione condivisa con il
      // dialog delle Impostazioni (progresso, «Installa», riprova).
      MobileUpdateDownloadInstallSection(downloadState = downloadState, onDownload = onUpdate)
      Spacer(modifier = Modifier.height(10.dp))
      MobileUpdateDialogButton(text = stringResource(R.string.action_close), onClick = onLater)
    }
  }
}

/** Contenitore comune dei dialog aggiornamenti (stile scheda, chiusura touch). */
@Composable
private fun MobileUpdateDialogContainer(
  title: String,
  onDismiss: () -> Unit,
  content: @Composable () -> Unit
) {
  Dialog(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier
        .width(360.dp)
        .background(NovaSurface, RoundedCornerShape(20.dp))
        .verticalScroll(rememberScrollState())
        .padding(20.dp)
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
          fontWeight = FontWeight.Bold,
          modifier = Modifier.weight(1f)
        )
        Icon(
          imageVector = Icons.Default.Close,
          contentDescription = stringResource(R.string.action_close),
          tint = NovaTextSecondary,
          modifier = Modifier
            .size(22.dp)
            .clickable(onClick = onDismiss)
        )
      }
      Spacer(modifier = Modifier.height(16.dp))
      content()
    }
  }
}

/** Blocco versione installata + titolo dell'esito (aggiornata / disponibile / errore). */
@Composable
private fun MobileUpdateVersionBlock(
  installedVersion: String,
  headline: String,
  headlineColor: androidx.compose.ui.graphics.Color
) {
  Text(
    text = stringResource(R.string.settings_update_installed_version, installedVersion),
    color = NovaTextSecondary,
    fontSize = 14.sp
  )
  Spacer(modifier = Modifier.height(8.dp))
  Text(
    text = headline,
    color = headlineColor,
    fontSize = 15.sp,
    fontWeight = FontWeight.SemiBold
  )
}

/**
 * Blocco download condiviso: progresso, completamento con «Installa» esplicito,
 * errore con riprova. Port del `UpdateDownloadInstallSection` TV senza il
 * cablaggio D-pad (focus requesters): i pulsanti sono touch.
 */
@Composable
fun MobileUpdateDownloadInstallSection(
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
      Text(
        text = stringResource(R.string.settings_update_download_complete),
        color = NovaGreen,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold
      )
      Spacer(modifier = Modifier.height(10.dp))
      if (canInstall) {
        MobileUpdateDialogButton(
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
          }
        )
      } else {
        Text(
          text = stringResource(R.string.settings_update_install_permission_needed),
          color = NovaTextSecondary,
          fontSize = 13.sp
        )
        Spacer(modifier = Modifier.height(10.dp))
        MobileUpdateDialogButton(
          text = stringResource(R.string.settings_update_install_permission_button),
          onClick = { ApkInstaller.openInstallPermissionSettings(context) }
        )
      }
      installErrorRes?.let { errorRes ->
        Spacer(modifier = Modifier.height(10.dp))
        Text(
          text = stringResource(errorRes),
          color = NovaRed,
          fontSize = 13.sp
        )
        if (errorRes == R.string.settings_update_install_failed_permission) {
          Spacer(modifier = Modifier.height(10.dp))
          MobileUpdateDialogButton(
            text = stringResource(R.string.settings_update_install_permission_button),
            onClick = { ApkInstaller.openInstallPermissionSettings(context) }
          )
        }
      }
    }
    is UpdateDownloadState.Error -> {
      Text(
        text = mobileUpdateDownloadErrorText(downloadState.reason),
        color = NovaRed,
        fontSize = 14.sp
      )
      Spacer(modifier = Modifier.height(10.dp))
      MobileUpdateDialogButton(
        text = stringResource(R.string.settings_update_download),
        onClick = onDownload
      )
    }
  }
}

/** Changelog della release in area scorrevole ad altezza contenuta. */
@Composable
private fun MobileUpdateChangelogBlock(changelog: String?) {
  if (changelog.isNullOrBlank()) return
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
      .heightIn(max = 160.dp)
      .background(NovaSurfaceVariant, RoundedCornerShape(10.dp))
      .verticalScroll(rememberScrollState())
      .padding(10.dp)
  ) {
    Text(
      text = changelog,
      color = NovaTextSecondary,
      fontSize = 13.sp
    )
  }
}

/** Pulsante touch dei dialog aggiornamenti (versione mobile di `UpdateDialogButton`). */
@Composable
private fun MobileUpdateDialogButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier.fillMaxWidth()
) {
  Box(
    modifier = modifier
      .background(NovaSurfaceVariant, RoundedCornerShape(12.dp))
      .clickable(onClick = onClick)
      .padding(vertical = 12.dp),
    contentAlignment = Alignment.Center
  ) {
    Text(
      text = text,
      color = NovaTextPrimary,
      fontSize = 14.sp,
      fontWeight = FontWeight.Bold,
      textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
  }
}

/** Messaggio localizzato del motivo di errore del controllo. */
@Composable
private fun mobileUpdateCheckErrorText(reason: UpdateCheckErrorReason): String = when (reason) {
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

/** Messaggio localizzato del motivo di errore del download. */
@Composable
private fun mobileUpdateDownloadErrorText(reason: DownloadErrorReason): String = when (reason) {
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
