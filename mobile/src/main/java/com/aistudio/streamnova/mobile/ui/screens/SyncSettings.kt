package com.aistudio.streamnova.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aistudio.streamnova.mobile.R
import com.aistudio.streamnova.mobile.ui.components.MobileSettingsNote
import com.aistudio.streamnova.mobile.ui.components.MobileSettingsRow
import com.aistudio.streamnova.mobile.ui.components.MobileSettingsSection
import com.aistudio.streamnova.mobile.ui.components.MobileSettingsSwitchRow
import com.aistudio.streamnova.mobile.ui.components.MobileTextInputDialog
import com.example.data.sync.SyncCategory
import com.example.data.sync.SyncDirection
import com.example.data.sync.SyncIncomingRequest
import com.example.data.sync.SyncManager
import com.example.data.sync.SyncPeerDevice
import com.example.data.sync.SyncTransferState
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaGreen
import com.example.ui.theme.NovaOrange
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import kotlinx.coroutines.launch

/**
 * Sezione Impostazioni «Sincronizzazione»: replica la configurazione completa
 * (impostazioni, addon, account SIMKL, preferiti e progressi) su altri dispositivi
 * StreamNova della stessa rete.
 *
 * Porting mobile della sezione TV ([com.example.ui.screens.SyncSettingsSection] di
 * StreamNova TV): stessa logica [SyncManager], stessi flussi e stessi dialog di
 * trasferimento/_approvazione, adattati all'interazione touch (AlertDialog invece
 * degli overlay con focus trap del D-pad).
 *
 * Il dispositivo con la Sync attiva annuncia se stesso sulla rete (NSD) e vede gli
 * altri; un trasferimento parte SOLO dopo l'approvazione esplicita sul dispositivo
 * che possiede i dati (dialog [SyncApprovalDialog], globale via [SyncApprovalOverlay]).
 */
@Composable
fun SyncSettingsSection() {
  val scope = rememberCoroutineScope()

  val enabled by SyncManager.enabled.collectAsStateWithLifecycle()
  val deviceName by SyncManager.deviceName.collectAsStateWithLifecycle()
  val localEndpoint by SyncManager.localEndpoint.collectAsStateWithLifecycle()
  val devices by SyncManager.devices.collectAsStateWithLifecycle()
  val transferState by SyncManager.transferState.collectAsStateWithLifecycle()

  var showDeviceNameDialog by remember { mutableStateOf(false) }
  var showManualIpDialog by remember { mutableStateOf(false) }
  var transferPeer by remember { mutableStateOf<SyncPeerDevice?>(null) }

  LaunchedEffect(enabled) {
    if (!enabled) transferPeer = null
  }

  MobileSettingsSection(title = stringResource(R.string.settings_sync_section)) {
    MobileSettingsSwitchRow(
      icon = Icons.Default.Sync,
      title = stringResource(R.string.settings_sync_mode),
      checked = enabled,
      onCheckedChange = { SyncManager.setEnabled(it) }
    )
    MobileSettingsNote(text = stringResource(R.string.settings_sync_mode_desc))
    if (enabled) {
      MobileSettingsRow(
        icon = Icons.Default.Sync,
        title = stringResource(R.string.settings_sync_this_device),
        value = localEndpoint ?: stringResource(R.string.settings_sync_activating),
        valueColor = if (localEndpoint != null) NovaGreen else NovaTextSecondary,
        onClick = null
      )
      MobileSettingsRow(
        icon = Icons.Default.Devices,
        title = stringResource(R.string.settings_sync_device_name),
        value = deviceName,
        onClick = { showDeviceNameDialog = true }
      )
      MobileSettingsRow(
        icon = Icons.Default.Refresh,
        title = stringResource(R.string.settings_sync_search_devices),
        value = when {
          devices.isEmpty() -> stringResource(R.string.settings_sync_no_devices)
          devices.size == 1 -> stringResource(R.string.settings_sync_one_device)
          else -> stringResource(R.string.settings_sync_n_devices, devices.size)
        },
        onClick = { SyncManager.refreshDevices() }
      )
      devices.forEach { peer ->
        MobileSettingsRow(
          icon = Icons.Default.Tv,
          title = peer.displayName,
          value = peer.endpoint,
          onClick = { transferPeer = peer }
        )
      }
      MobileSettingsRow(
        icon = Icons.Default.Add,
        title = stringResource(R.string.settings_sync_add_ip),
        value = null,
        onClick = { showManualIpDialog = true }
      )
      MobileSettingsNote(text = stringResource(R.string.settings_sync_note))
    }
  }

  if (showDeviceNameDialog) {
    MobileTextInputDialog(
      title = stringResource(R.string.settings_sync_name_dialog_title),
      hint = stringResource(R.string.settings_sync_name_hint),
      initialText = deviceName,
      onConfirm = { name ->
        showDeviceNameDialog = false
        SyncManager.setDeviceName(name)
      },
      onDismiss = { showDeviceNameDialog = false }
    )
  }

  if (showManualIpDialog) {
    MobileTextInputDialog(
      title = stringResource(R.string.settings_sync_add_ip_title),
      hint = stringResource(R.string.settings_sync_hint_ip),
      initialText = "",
      onConfirm = { input ->
        showManualIpDialog = false
        scope.launch { SyncManager.addManualDevice(input) }
      },
      onDismiss = { showManualIpDialog = false }
    )
  }

  transferPeer?.let { peer ->
    SyncTransferDialog(
      peer = peer,
      onDismiss = { transferPeer = null }
    )
  }

  when (val state = transferState) {
    is SyncTransferState.WaitingApproval,
    is SyncTransferState.Running ->
      SyncTransferStateDialog(
        title = stringResource(R.string.settings_sync_state_running_title),
        message = when (state) {
          is SyncTransferState.WaitingApproval ->
            stringResource(R.string.settings_sync_state_waiting, state.peerName)
          is SyncTransferState.Running -> state.phase
          else -> ""
        },
        messageColor = NovaCyanBright,
        confirmLabel = stringResource(R.string.action_cancel),
        destructive = true,
        onConfirm = { SyncManager.cancelTransfer() },
        onDismiss = { SyncManager.cancelTransfer() }
      )

    is SyncTransferState.Denied ->
      SyncTransferStateDialog(
        title = stringResource(R.string.settings_sync_state_denied_title),
        message = stringResource(R.string.settings_sync_state_denied, state.peerName),
        messageColor = NovaOrange,
        confirmLabel = stringResource(R.string.action_close),
        onConfirm = { SyncManager.dismissTransferResult() },
        onDismiss = { SyncManager.dismissTransferResult() }
      )

    is SyncTransferState.Completed ->
      SyncTransferStateDialog(
        title = stringResource(R.string.settings_sync_state_completed_title),
        message = state.summary,
        messageColor = NovaGreen,
        confirmLabel = stringResource(R.string.action_close),
        onConfirm = { SyncManager.dismissTransferResult() },
        onDismiss = { SyncManager.dismissTransferResult() }
      )

    is SyncTransferState.Failed ->
      SyncTransferStateDialog(
        title = stringResource(R.string.settings_sync_state_failed_title),
        message = state.message,
        messageColor = NovaOrange,
        confirmLabel = stringResource(R.string.action_close),
        onConfirm = { SyncManager.dismissTransferResult() },
        onDismiss = { SyncManager.dismissTransferResult() }
      )

    SyncTransferState.Idle -> Unit
  }
}

/** Etichette localizzate delle categorie mostrate nel dialog di trasferimento. */
private fun categoryLabel(context: android.content.Context, category: SyncCategory): String =
  when (category) {
    SyncCategory.SETTINGS -> context.getString(R.string.settings_sync_cat_settings)
    SyncCategory.ADDONS -> context.getString(R.string.settings_sync_cat_addons)
    SyncCategory.SIMKL -> context.getString(R.string.settings_sync_cat_simkl)
    SyncCategory.LIBRARY -> context.getString(R.string.settings_sync_cat_library)
  }

/**
 * Overlay GLOBALE della richiesta di sincronizzazione in arrivo: composto alla radice
 * dell'app, mostra il dialog di approvazione ovunque si trovi l'utente — la richiesta
 * scade dopo due minuti (TTL) e non può vivere solo dentro Impostazioni, dove
 * l'utente potrebbe non guardare in quel momento.
 */
@Composable
fun SyncApprovalOverlay() {
  val incomingRequest by SyncManager.incomingRequest.collectAsStateWithLifecycle()
  incomingRequest?.let { request ->
    SyncApprovalDialog(
      request = request,
      onApprove = { SyncManager.approveIncoming() },
      onDeny = { SyncManager.denyIncoming() }
    )
  }
}

/**
 * Dialog di avvio trasferimento: direzione (importa/invia) e categorie da portare.
 * In entrambe le direzioni l'approvazione finale spetta all'altro dispositivo.
 */
@Composable
private fun SyncTransferDialog(
  peer: SyncPeerDevice,
  onDismiss: () -> Unit
) {
  var direction by remember { mutableStateOf(SyncDirection.PULL) }
  var selected by remember {
    mutableStateOf(SyncCategory.entries.associateWith { true })
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = stringResource(R.string.settings_sync_transfer_title, peer.displayName),
        color = NovaTextPrimary
      )
    },
    text = {
      Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        DirectionRow(
          title = stringResource(R.string.settings_sync_import),
          subtitle = stringResource(R.string.settings_sync_import_desc, peer.displayName),
          selected = direction == SyncDirection.PULL,
          onClick = { direction = SyncDirection.PULL }
        )
        DirectionRow(
          title = stringResource(R.string.settings_sync_push),
          subtitle = stringResource(R.string.settings_sync_push_desc, peer.displayName),
          selected = direction == SyncDirection.PUSH,
          onClick = { direction = SyncDirection.PUSH }
        )

        Spacer(modifier = Modifier.height(14.dp))
        Text(
          text = stringResource(R.string.settings_sync_what),
          color = NovaTextSecondary,
          fontSize = 13.sp,
          fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        val dialogContext = LocalContext.current
        SyncCategory.entries.forEach { category ->
          CategoryCheckRow(
            label = categoryLabel(dialogContext, category),
            checked = selected[category] == true,
            onToggle = { selected = selected + (category to !(selected[category] == true)) }
          )
        }
      }
    },
    confirmButton = {
      TextButton(
        enabled = selected.values.any { it },
        onClick = {
          val categories = selected.filterValues { it }.keys
          onDismiss()
          SyncManager.startTransfer(peer, direction, categories)
        }
      ) {
        Text(stringResource(R.string.settings_sync_start), color = NovaCyan)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.action_cancel), color = NovaTextSecondary)
      }
    },
    containerColor = NovaSurfaceVariant
  )
}

/** Dialog di approvazione sul dispositivo che possiede i dati. */
@Composable
private fun SyncApprovalDialog(
  request: SyncIncomingRequest,
  onApprove: () -> Unit,
  onDeny: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDeny,
    title = {
      Text(
        text = stringResource(R.string.settings_sync_approval_title),
        color = NovaTextPrimary
      )
    },
    text = {
      Column {
        Text(
          text = when (request.direction) {
            SyncDirection.PULL ->
              stringResource(R.string.settings_sync_approval_pull, request.peerDeviceName)
            SyncDirection.PUSH ->
              stringResource(R.string.settings_sync_approval_push, request.peerDeviceName)
          },
          color = NovaTextPrimary,
          fontSize = 14.sp,
          lineHeight = 20.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        val approvalContext = LocalContext.current
        Text(
          text = request.categories.joinToString(", ") { categoryLabel(approvalContext, it) },
          color = NovaCyanBright,
          fontSize = 13.sp
        )
      }
    },
    confirmButton = {
      TextButton(onClick = onApprove) {
        Text(stringResource(R.string.settings_sync_transfer_confirm), color = NovaCyan)
      }
    },
    dismissButton = {
      TextButton(onClick = onDeny) {
        Text(stringResource(R.string.settings_sync_deny), color = NovaTextSecondary)
      }
    },
    containerColor = NovaSurfaceVariant
  )
}

/** Dialog di stato del trasferimento avviato da questo dispositivo. */
@Composable
private fun SyncTransferStateDialog(
  title: String,
  message: String,
  messageColor: Color,
  confirmLabel: String,
  destructive: Boolean = false,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title, color = NovaTextPrimary) },
    text = {
      Text(
        text = message,
        color = messageColor,
        fontSize = 14.sp,
        lineHeight = 20.sp
      )
    },
    confirmButton = {
      TextButton(onClick = onConfirm) {
        Text(
          text = confirmLabel,
          color = if (destructive) NovaOrange else NovaCyan
        )
      }
    },
    containerColor = NovaSurfaceVariant
  )
}

/** Riga di selezione direzione (radio) del dialog di trasferimento. */
@Composable
private fun DirectionRow(
  title: String,
  subtitle: String,
  selected: Boolean,
  onClick: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .selectable(selected = selected, onClick = onClick)
      .padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    RadioButton(
      selected = selected,
      onClick = onClick,
      colors = RadioButtonDefaults.colors(
        selectedColor = NovaCyanBright,
        unselectedColor = NovaTextSecondary
      )
    )
    Spacer(modifier = Modifier.width(4.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        color = NovaTextPrimary,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = subtitle,
        color = NovaTextSecondary,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

/** Riga casella di controllo per le categorie di trasferimento. */
@Composable
private fun CategoryCheckRow(
  label: String,
  checked: Boolean,
  onToggle: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onToggle)
      .padding(vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Checkbox(
      checked = checked,
      onCheckedChange = { onToggle() },
      colors = CheckboxDefaults.colors(
        checkedColor = NovaCyanBright,
        uncheckedColor = NovaTextSecondary
      )
    )
    Spacer(modifier = Modifier.width(4.dp))
    Text(
      text = label,
      color = NovaTextPrimary,
      fontSize = 14.sp
    )
  }
}
