@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.ui.screens

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.sync.SyncCategory
import com.example.data.sync.SyncDirection
import com.example.data.sync.SyncIncomingRequest
import com.example.data.sync.SyncManager
import com.example.data.sync.SyncPeerDevice
import com.example.data.sync.SyncTransferState
import com.example.ui.components.ModalOverlayState
import com.example.ui.components.TvFocusableBox
import com.example.ui.components.TextInputDialog
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaGreen
import com.example.ui.theme.NovaOrange
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import kotlinx.coroutines.launch

/**
 * Sezione Impostazioni «Sincronizzazione»: replica la configurazione completa
 * (impostazioni, addon, account SIMKL, preferiti e progressi) su altri dispositivi
 * StreamNova della stessa rete.
 *
 * Il dispositivo con la Sync attiva annuncia se stesso sulla rete (NSD) e vede gli
 * altri; un trasferimento parte SOLO dopo l'approvazione esplicita sul dispositivo
 * che possiede i dati (dialog [SyncApprovalDialog]).
 */
@Composable
fun SyncSettingsSection() {
  val scope = rememberCoroutineScope()

  val enabled by SyncManager.enabled.collectAsState()
  val deviceName by SyncManager.deviceName.collectAsState()
  val localEndpoint by SyncManager.localEndpoint.collectAsState()
  val devices by SyncManager.devices.collectAsState()
  val transferState by SyncManager.transferState.collectAsState()

  var showDeviceNameDialog by remember { mutableStateOf(false) }
  var showManualIpDialog by remember { mutableStateOf(false) }
  var transferPeer by remember { mutableStateOf<SyncPeerDevice?>(null) }

  LaunchedEffect(enabled) {
    if (!enabled) transferPeer = null
  }

  SettingsSection(title = "Sincronizzazione") {
    SettingsSwitchRow(
      title = "Modalità Sync",
      subtitle = "Rendi la tua configurazione disponibile agli altri dispositivi StreamNova " +
        "sulla stessa rete (Wi‑Fi/Ethernet)",
      checked = enabled,
      onCheckedChange = { SyncManager.setEnabled(it) }
    )
    if (enabled) {
      SettingsRow(
        icon = Icons.Default.Sync,
        title = "Questo dispositivo",
        value = localEndpoint ?: "Attivazione…",
        valueColor = if (localEndpoint != null) NovaGreen else NovaTextSecondary,
        onClick = null
      )
      SettingsRow(
        icon = Icons.Default.Devices,
        title = "Nome dispositivo",
        value = deviceName,
        onClick = { showDeviceNameDialog = true },
        showChevron = true
      )
      SettingsRow(
        icon = Icons.Default.Refresh,
        title = "Cerca dispositivi",
        value = when {
          devices.isEmpty() -> "Nessun dispositivo trovato"
          devices.size == 1 -> "1 dispositivo"
          else -> "${devices.size} dispositivi"
        },
        onClick = { SyncManager.refreshDevices() },
        showChevron = true
      )
      devices.forEach { peer ->
        SettingsRow(
          icon = Icons.Default.Tv,
          title = peer.displayName,
          value = peer.endpoint,
          onClick = { transferPeer = peer },
          showChevron = true
        )
      }
      SettingsRow(
        icon = Icons.Default.Add,
        title = "Aggiungi per indirizzo IP",
        value = null,
        onClick = { showManualIpDialog = true },
        showChevron = true
      )
      Text(
        text = "Attiva la Sync anche sull'altro dispositivo: i dispositivi si trovano da soli. " +
          "Ogni trasferimento deve essere approvato sul dispositivo che possiede i dati.",
        color = NovaTextSecondary,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
      )
    }
  }

  if (showDeviceNameDialog) {
    TextInputDialog(
      title = "Nome dispositivo",
      hint = "Es. TV Soggiorno",
      initialText = deviceName,
      onConfirm = { name ->
        showDeviceNameDialog = false
        SyncManager.setDeviceName(name)
      },
      onDismiss = { showDeviceNameDialog = false }
    )
  }

  if (showManualIpDialog) {
    TextInputDialog(
      title = "Aggiungi dispositivo per IP",
      hint = "Es. 192.168.1.24",
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
        title = "Trasferimento in corso",
        message = when (state) {
          is SyncTransferState.WaitingApproval ->
            "In attesa di approvazione su «${state.peerName}». " +
              "Conferma il trasferimento sull'altro dispositivo."
          is SyncTransferState.Running -> state.phase
          else -> ""
        },
        valueColor = NovaCyanBright,
        confirmLabel = "Annulla",
        destructive = true,
        onConfirm = { SyncManager.cancelTransfer() },
        onDismiss = { SyncManager.cancelTransfer() }
      )

    is SyncTransferState.Denied ->
      SyncTransferStateDialog(
        title = "Trasferimento rifiutato",
        message = "«${state.peerName}» ha rifiutato il trasferimento.",
        confirmLabel = "Chiudi",
        valueColor = NovaOrange,
        onConfirm = { SyncManager.dismissTransferResult() },
        onDismiss = { SyncManager.dismissTransferResult() }
      )

    is SyncTransferState.Completed ->
      SyncTransferStateDialog(
        title = "Trasferimento completato",
        message = state.summary,
        confirmLabel = "Chiudi",
        valueColor = NovaGreen,
        onConfirm = { SyncManager.dismissTransferResult() },
        onDismiss = { SyncManager.dismissTransferResult() }
      )

    is SyncTransferState.Failed ->
      SyncTransferStateDialog(
        title = "Trasferimento non riuscito",
        message = state.message,
        confirmLabel = "Chiudi",
        valueColor = NovaOrange,
        onConfirm = { SyncManager.dismissTransferResult() },
        onDismiss = { SyncManager.dismissTransferResult() }
      )

    SyncTransferState.Idle -> Unit
  }
}

/** Etichette delle categorie mostrate nelle caselle del dialog di trasferimento. */
private fun categoryLabel(category: SyncCategory): String = when (category) {
  SyncCategory.SETTINGS -> "Impostazioni e chiavi API"
  SyncCategory.ADDONS -> "Addon Stremio"
  SyncCategory.SIMKL -> "Account SIMKL"
  SyncCategory.LIBRARY -> "Preferiti e progressi"
}

/**
 * Overlay GLOBALE della richiesta di sincronizzazione in arrivo: composto alla radice
 * dell'Activity, mostra il dialog di approvazione ovunque si trovi l'utente — la
 * richiesta scade dopo due minuti (TTL) e non può vivere solo dentro Impostazioni,
 * dove l'utente potrebbe non guardare in quel momento.
 */
@Composable
fun SyncApprovalOverlay() {
  val incomingRequest by SyncManager.incomingRequest.collectAsState()
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
 *
 * `internal` per i test di navigabilità remote-safe (stesso package dei test UI).
 */
@Composable
internal fun SyncTransferDialog(
  peer: SyncPeerDevice,
  onDismiss: () -> Unit
) {
  var direction by remember { mutableStateOf(SyncDirection.PULL) }
  var selected by remember {
    mutableStateOf(SyncCategory.entries.associateWith { true })
  }
  val initialFocus = remember { FocusRequester() }

  SyncDialogScaffold(
    title = "Sincronizza con «${peer.displayName}»",
    onDismiss = onDismiss,
    initialFocus = initialFocus
  ) {
    DirectionRow(
      title = "Importa dal dispositivo",
      subtitle = "Copia la configurazione di «${peer.displayName}» su questo dispositivo",
      selected = direction == SyncDirection.PULL,
      onClick = { direction = SyncDirection.PULL },
      modifier = Modifier
        .focusRequester(initialFocus)
        // Primo controllo del dialog: sopra non c'è nulla di azionabile, il D-pad non
        // deve tentare la fuga verso lo sfondo.
        .focusProperties { up = FocusRequester.Cancel }
    )
    DirectionRow(
      title = "Invia la tua configurazione",
      subtitle = "Replica la configurazione di questo dispositivo su «${peer.displayName}»",
      selected = direction == SyncDirection.PUSH,
      onClick = { direction = SyncDirection.PUSH }
    )

    Spacer(modifier = Modifier.height(14.dp))
    Text(
      text = "Cosa trasferire",
      color = NovaTextSecondary,
      fontSize = 13.sp,
      fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(6.dp))
    SyncCategory.entries.forEach { category ->
      CategoryCheckRow(
        label = categoryLabel(category),
        checked = selected[category] == true,
        onToggle = { selected = selected + (category to !(selected[category] == true)) }
      )
    }

    Spacer(modifier = Modifier.height(20.dp))
    SyncDialogButtons(
      cancelLabel = "Annulla",
      confirmLabel = "Avvia",
      confirmEnabled = selected.values.any { it },
      onCancel = onDismiss,
      onConfirm = {
        val categories = selected.filterValues { it }.keys
        onDismiss()
        SyncManager.startTransfer(peer, direction, categories)
      }
    )
  }
}

/** Dialog di approvazione sul dispositivo che possiede i dati.
 *
 *  `internal` per i test di navigabilità remote-safe (stesso package dei test UI).
 */
@Composable
internal fun SyncApprovalDialog(
  request: SyncIncomingRequest,
  onApprove: () -> Unit,
  onDeny: () -> Unit
) {
  val initialFocus = remember { FocusRequester() }
  val directionText = when (request.direction) {
    SyncDirection.PULL ->
      "«${request.peerDeviceName}» vuole copiare la tua configurazione."
    SyncDirection.PUSH ->
      "«${request.peerDeviceName}» vuole inviarti la sua configurazione: " +
        "le impostazioni correnti di questo dispositivo verranno sostituite."
  }
  SyncDialogScaffold(
    title = "Richiesta di sincronizzazione",
    onDismiss = onDeny,
    initialFocus = initialFocus
  ) {
    Text(
      text = directionText,
      color = NovaTextPrimary,
      fontSize = 14.sp,
      lineHeight = 20.sp
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = request.categories.joinToString(", ") { categoryLabel(it) },
      color = NovaCyanBright,
      fontSize = 13.sp
    )
    Spacer(modifier = Modifier.height(20.dp))
    SyncDialogButtons(
      cancelLabel = "Rifiuta",
      confirmLabel = "Trasferisci",
      confirmEnabled = true,
      // I pulsanti sono gli unici controlli del dialog: UP non ha dove andare.
      cancelUp = true,
      confirmFocusRequester = initialFocus,
      onCancel = onDeny,
      onConfirm = onApprove
    )
  }
}

/** Dialog di stato del trasferimento avviato da questo dispositivo. */
@Composable
private fun SyncTransferStateDialog(
  title: String,
  message: String,
  confirmLabel: String,
  valueColor: Color,
  destructive: Boolean = false,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit
) {
  val initialFocus = remember { FocusRequester() }
  SyncDialogScaffold(
    title = title,
    onDismiss = onDismiss,
    initialFocus = initialFocus
  ) {
    Text(
      text = message,
      color = valueColor,
      fontSize = 14.sp,
      lineHeight = 20.sp
    )
    Spacer(modifier = Modifier.height(20.dp))
    SyncDialogButtons(
      cancelLabel = null,
      confirmLabel = confirmLabel,
      confirmEnabled = true,
      destructive = destructive,
      // Pulsante unico del dialog: UP non ha dove andare.
      cancelUp = true,
      confirmFocusRequester = initialFocus,
      onCancel = onDismiss,
      onConfirm = onConfirm
    )
  }
}

/** Tentativi di cattura del focus iniziale nei dialog Sync (un tentativo per frame). */
private const val SYNC_DIALOG_FOCUS_MAX_ATTEMPTS = 30

/**
 * Struttura comune dei dialog Sync.
 *
 * È un VERO [androidx.compose.ui.window.Dialog] (finestra separata), non un overlay
 * composto nella finestra dell'Activity: è la stessa decisione presa per il menu
 * contestuale degli episodi ([com.example.ui.components.EpisodeContextMenu]) dopo
 * aver scoperto che con gli overlay in-window il focus poteva restare dietro lo
 * scrim — sul telecomando il popup appariva "bloccato" e il D-pad non navigava nulla.
 *
 * Contratto remote-safe (stesso dell'EpisodeContextMenu):
 *  - il focus iniziale è RICHIESTO E VERIFICATO con retry per frame ([initialFocus]
 *    va collegato al primo controllo azionabile del dialog);
 *  - il contenitore ha `exit = Cancel`: il focus non può uscire dalla card, qualunque
 *    sia la direzione premuta;
 *  - BACK chiude la finestra ([DialogProperties.dismissOnBackPress]) chiamando
 *    [onDismiss] tramite `onDismissRequest`.
 */
@Composable
private fun SyncDialogScaffold(
  title: String,
  onDismiss: () -> Unit,
  initialFocus: FocusRequester,
  content: @Composable () -> Unit
) {
  DisposableEffect(Unit) {
    ModalOverlayState.onShown()
    onDispose { ModalOverlayState.onHidden() }
  }

  var dialogHasFocus by remember { mutableStateOf(false) }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(
      dismissOnBackPress = true,
      dismissOnClickOutside = false,
      usePlatformDefaultWidth = false,
      decorFitsSystemWindows = false
    )
  ) {
    // Focus iniziale differito e VERIFICATO: requestFocus() non espone un esito, il
    // nodo del primo controllo può non essere ancora agganciato al frame in cui la
    // finestra compare. Si riprova a ogni frame finché il focus non entra nel dialog.
    LaunchedEffect(dialogHasFocus) {
      if (dialogHasFocus) return@LaunchedEffect
      var attempts = 0
      while (!dialogHasFocus && attempts < SYNC_DIALOG_FOCUS_MAX_ATTEMPTS) {
        attempts++
        try {
          initialFocus.requestFocus()
        } catch (_: IllegalStateException) {
        }
        withFrameNanos { }
      }
    }

    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black.copy(alpha = 0.75f))
        .focusProperties { canFocus = false }
        .clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null
        ) { onDismiss() },
      contentAlignment = Alignment.Center
    ) {
      Column(
        modifier = Modifier
          .widthIn(max = 560.dp)
          .fillMaxWidth()
          .padding(horizontal = 24.dp)
          .background(NovaSurface, RoundedCornerShape(20.dp))
          .border(1.5.dp, NovaCyan, RoundedCornerShape(20.dp))
          .padding(24.dp)
          .focusProperties { exit = { FocusRequester.Cancel } }
          .focusGroup()
          .onFocusChanged { dialogHasFocus = it.hasFocus }
      ) {
        Text(
          text = title,
          color = NovaTextPrimary,
          fontSize = 18.sp,
          fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(14.dp))
        content()
      }
    }
  }
}

/** Riga di selezione direzione (radio): un solo target del D-pad. */
@Composable
private fun DirectionRow(
  title: String,
  subtitle: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  TvFocusableBox(
    shape = RoundedCornerShape(10.dp),
    focusedScale = 1.01f,
    onClick = onClick,
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = 3.dp)
  ) { isFocused ->
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          if (isFocused) NovaSurfaceVariant else Color.Transparent,
          RoundedCornerShape(10.dp)
        )
        .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      RadioIndicator(selected = selected, isFocused = isFocused)
      Spacer(modifier = Modifier.width(12.dp))
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
          maxLines = 2
        )
      }
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
  TvFocusableBox(
    shape = RoundedCornerShape(10.dp),
    focusedScale = 1.01f,
    onClick = onToggle,
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 2.dp)
  ) { isFocused ->
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          if (isFocused) NovaSurfaceVariant else Color.Transparent,
          RoundedCornerShape(10.dp)
        )
        .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
        modifier = Modifier
          .size(18.dp)
          .background(
            if (checked) NovaCyan else Color.Transparent,
            RoundedCornerShape(4.dp)
          )
          .border(1.dp, if (checked) NovaCyan else NovaTextMuted, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center
      ) {
        if (checked) {
          Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = NovaSurface,
            modifier = Modifier.size(14.dp)
          )
        }
      }
      Spacer(modifier = Modifier.width(12.dp))
      Text(
        text = label,
        color = NovaTextPrimary,
        fontSize = 14.sp
      )
    }
  }
}

/** Indicatore radio delle direzioni. */
@Composable
private fun RadioIndicator(selected: Boolean, isFocused: Boolean) {
  Box(
    modifier = Modifier
      .size(18.dp)
      .border(
        2.dp,
        when {
          selected -> NovaCyanBright
          isFocused -> NovaTextPrimary
          else -> NovaTextMuted
        },
        CircleShape
      ),
    contentAlignment = Alignment.Center
  ) {
    if (selected) {
      Box(
        modifier = Modifier
          .size(9.dp)
          .background(NovaCyanBright, CircleShape)
      )
    }
  }
}

/** Coppia di pulsanti Annulla / Conferma condivisa dai dialog Sync.
 *
 *  @param confirmFocusRequester se fornito, il pulsante di conferma diventa il target
 *         del focus iniziale del dialog (il chiamante lo passa anche a
 *         [SyncDialogScaffold], che lo richiede al primo frame).
 *  @param cancelUp con true, UP sui pulsanti è annullato: da usare quando i pulsanti
 *         sono gli unici controlli del dialog (approvazione, esito).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun SyncDialogButtons(
  cancelLabel: String?,
  confirmLabel: String,
  confirmEnabled: Boolean,
  destructive: Boolean = false,
  cancelUp: Boolean = false,
  confirmFocusRequester: FocusRequester? = null,
  onCancel: () -> Unit,
  onConfirm: () -> Unit
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
    verticalAlignment = Alignment.CenterVertically
  ) {
    if (cancelLabel != null) {
      TvFocusableBox(
        modifier = Modifier.focusProperties {
          down = FocusRequester.Cancel
          left = FocusRequester.Cancel
          if (cancelUp) up = FocusRequester.Cancel
        },
        shape = RoundedCornerShape(50),
        focusedScale = 1.05f,
        onClick = onCancel
      ) { isFocused ->
        Text(
          text = cancelLabel,
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
    }
    TvFocusableBox(
      modifier = Modifier
        .then(
          if (confirmFocusRequester != null) {
            Modifier.focusRequester(confirmFocusRequester)
          } else {
            Modifier
          }
        )
        .focusProperties {
          down = FocusRequester.Cancel
          right = FocusRequester.Cancel
          if (cancelUp) up = FocusRequester.Cancel
        },
      shape = RoundedCornerShape(50),
      focusedScale = 1.05f,
      onClick = { if (confirmEnabled) onConfirm() }
    ) { isFocused ->
      Text(
        text = confirmLabel,
        color = if (isFocused) NovaSurface else NovaCyanBright,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
          .background(
            when {
              !confirmEnabled -> NovaSurfaceVariant
              isFocused && destructive -> NovaOrange
              isFocused -> NovaCyanBright
              else -> NovaCyan.copy(alpha = 0.16f)
            },
            RoundedCornerShape(50)
          )
          .border(
            1.dp,
            when {
              !confirmEnabled -> NovaTextMuted
              destructive -> NovaOrange
              else -> NovaCyanBright
            },
            RoundedCornerShape(50)
          )
          .padding(horizontal = 22.dp, vertical = 10.dp)
      )
    }
  }
}
