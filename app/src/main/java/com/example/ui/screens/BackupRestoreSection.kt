package com.example.ui.screens

import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.BuildConfig
import com.example.data.backup.BackupEnvelope
import com.example.data.backup.BackupManager
import com.example.data.backup.LocalBackupStore
import com.example.data.backup.google.DriveBackupEntry
import com.example.data.backup.google.DriveExportProgress
import com.example.data.backup.google.GoogleDriveAuthRepository
import com.example.data.backup.google.GoogleDriveAuthState
import com.example.data.backup.google.GoogleDriveBackupManager
import com.example.data.backup.google.GoogleDriveFilesClient
import com.example.ui.components.TvFocusableBox
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Sezione Impostazioni "Backup e ripristino".
 *
 * - **Esporta configurazione**: apre prima il dialog «Dove vuoi salvare il backup?»
 *   (Locale / Google Drive). Locale usa Storage Access Framework quando il
 *   dispositivo ha un picker; su dispositivi TV senza document provider (es. Fire
 *   TV) non crasha più: cade sul salvataggio nella cartella in-app
 *   `StreamNova Backups`, con scrittura atomica.
 * - **Google Drive**: Drive PERSONALE dell'utente via OAuth Device Authorization
 *   Grant (l'utente autorizza su google.com/device da qualsiasi altro dispositivo).
 *   I backup restano nella cartella `StreamNova Backups` del Drive dell'utente;
 *   nessun backend, nessun Drive dello sviluppatore.
 * - **Ripristina da file**: dialog «Da dove vuoi importare?» (Locale / Google Drive).
 *   Entrambe le sorgenti convergono sulla STESSA pipeline: lettura → validazione →
 *   anteprima → conferma esplicita → restore.
 *
 * Stati UI: operazione singola alla volta (guard `busy`), annullamenti gestiti,
 * errori mostrati senza crash. Il lancio dei picker è protetto: un intent senza
 * handler produce il fallback, mai ActivityNotFoundException.
 */
@Composable
fun BackupRestoreSettingsSection(
  appVersionName: String,
  appVersionCode: Int
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val clientId = remember { googleDriveClientId() }
  val driveConfigured = clientId.isNotBlank()

  var includeCredentials by remember { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }
  var status by remember { mutableStateOf<String?>(null) }
  var pendingEnvelope by remember { mutableStateOf<BackupEnvelope?>(null) }

  // ── Dialog attivo ──────────────────────────────────────────────────────────
  var dialog by remember { mutableStateOf<BackupDialog>(BackupDialog.NONE) }
  var localBackups by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
  var driveBackups by remember { mutableStateOf<List<DriveBackupEntry>>(emptyList()) }
  var driveListLoading by remember { mutableStateOf(false) }
  var connectJob by remember { mutableStateOf<Job?>(null) }
  val authState by GoogleDriveAuthRepository.authState.collectAsState()

  fun closeDialog() {
    dialog = BackupDialog.NONE
  }

  fun showStatus(message: String?) {
    status = message
    if (!message.isNullOrBlank()) {
      Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
  }

  // ── Launcher SAF (scelta PRIMARIA dove il picker esiste) ───────────────────
  val exportLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/json")
  ) { uri: Uri? ->
    if (uri == null) return@rememberLauncherForActivityResult // annullamento: nessun effetto
    busy = true
    scope.launch {
      val result = BackupManager.exportTo(
        context = context,
        uri = uri,
        includeCredentials = includeCredentials,
        appVersionName = appVersionName,
        appVersionCode = appVersionCode
      )
      busy = false
      showStatus(
        result.fold(
          onSuccess = { it.describe() },
          onFailure = { "Esportazione non riuscita: ${it.message}" }
        )
      )
    }
  }

  val importLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
  ) { uri: Uri? ->
    if (uri == null) return@rememberLauncherForActivityResult // annullamento
    busy = true
    scope.launch {
      val preview = BackupManager.preview(context, uri)
      busy = false
      preview.fold(
        onSuccess = { envelope -> pendingEnvelope = envelope },
        onFailure = { error -> showStatus(error.message) }
      )
    }
  }

  fun exportToAppFolder() {
    busy = true
    scope.launch {
      val target = java.io.File(
        LocalBackupStore.backupsDir(context),
        BackupManager.suggestedBackupFileName()
      )
      val result = BackupManager.exportToFile(
        context = context,
        target = target,
        includeCredentials = includeCredentials,
        appVersionName = appVersionName,
        appVersionCode = appVersionCode
      )
      busy = false
      showStatus(
        result.fold(
          onSuccess = {
            "Backup salvato sul dispositivo: ${target.absolutePath}"
          },
          onFailure = { "Esportazione non riuscita: ${it.message}" }
        )
      )
    }
  }

  fun openLocalImportList() {
    val entries = LocalBackupStore.listBackups(context).map { file ->
      FileEntry(file.name, LocalBackupStore.describe(file), file)
    }
    localBackups = entries
    dialog = BackupDialog.LOCAL_IMPORT_LIST
  }

  fun launchSafExport() {
    try {
      exportLauncher.launch(BackupManager.suggestedBackupFileName())
    } catch (e: ActivityNotFoundException) {
      // Dispositivo senza document provider (es. Fire TV): salvataggio nella
      // cartella in-app, stessa validazione e stessa atomicità dell'export SAF.
      exportToAppFolder()
    } catch (e: Exception) {
      exportToAppFolder()
    }
  }

  fun launchSafImport() {
    try {
      importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
    } catch (e: ActivityNotFoundException) {
      openLocalImportList()
    } catch (e: Exception) {
      openLocalImportList()
    }
  }

  // ── Operazioni Google Drive ────────────────────────────────────────────────

  fun runDriveExport() {
    busy = true
    scope.launch {
      val fileName = BackupManager.suggestedBackupFileName()
      val result = GoogleDriveBackupManager.exportToDrive(
        clientId = clientId,
        fileName = fileName,
        includeCredentials = includeCredentials,
        appVersionName = appVersionName,
        appVersionCode = appVersionCode
      ) { progress ->
        // Avanzamento visibile nella sezione (scritto sul thread UI).
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
          status = when (progress) {
            is DriveExportProgress.PreparingFolder -> "Preparazione della cartella Drive…"
            is DriveExportProgress.Uploading -> "Caricamento di $fileName sul tuo Drive…"
            is DriveExportProgress.Done -> null
          }
        }
      }
      busy = false
      showStatus(
        result.fold(
          onSuccess = { entry ->
            "Backup salvato nel tuo Google Drive (${entry.fileName}, ${entry.sizeBytes / 1024} KB)"
          },
          onFailure = { "Caricamento su Google Drive non riuscito: ${GoogleDriveBackupManager.describe(it)}" }
        )
      )
    }
  }

  fun runDriveImport() {
    busy = true
    driveListLoading = true
    scope.launch {
      val result = GoogleDriveBackupManager.listDriveBackups(clientId)
      driveListLoading = false
      busy = false
      result.fold(
        onSuccess = { entries ->
          driveBackups = entries
          dialog = BackupDialog.DRIVE_IMPORT_LIST
        },
        onFailure = {
          showStatus("Lettura dal tuo Google Drive non riuscita: ${GoogleDriveBackupManager.describe(it)}")
        }
      )
    }
  }

  /**
   * Avvia l'azione Drive DOPO il collegamento: il dialog di collegamento resta
   * aperto durante l'attesa e, al successo, l'azione parte automaticamente.
   */
  fun connectThenRun(action: DriveAction) {
    if (busy || connectJob?.isActive == true) return
    busy = true
    scope.launch {
      val grant = GoogleDriveAuthRepository.startDeviceFlow(clientId)
      grant.fold(
        onSuccess = { g ->
          dialog = BackupDialog.DRIVE_CONNECT
          connectJob = scope.launch {
            val result = GoogleDriveAuthRepository.awaitAuthorization(clientId, g)
            result.fold(
              onSuccess = {
                closeDialog()
                when (action) {
                  DriveAction.EXPORT -> runDriveExport()
                  DriveAction.IMPORT -> runDriveImport()
                }
              },
              onFailure = { e ->
                closeDialog()
                busy = false
                showStatus("Collegamento Google non riuscito: ${e.message}")
              }
            )
          }
        },
        onFailure = { e ->
          busy = false
          showStatus("Collegamento Google non riuscito: ${e.message ?: "Client ID non configurato in questa build"}")
        }
      )
    }
  }

  fun onDriveChosen(action: DriveAction) {
    when {
      !driveConfigured -> showStatus(
        "Google Drive non è disponibile in questa build di StreamNova: serve la " +
          "configurazione sviluppatore (GOOGLE_DRIVE_CLIENT_ID, vedi report di rilascio)."
      )
      else -> scope.launch {
        val connected = GoogleDriveAuthRepository.isConnected()
        if (connected) {
          when (action) {
            DriveAction.EXPORT -> runDriveExport()
            DriveAction.IMPORT -> runDriveImport()
          }
        } else {
          connectThenRun(action)
        }
      }
    }
  }

  // ── UI della sezione ───────────────────────────────────────────────────────

  SettingsSection("Backup e ripristino") {
    SettingsRow(
      icon = Icons.Default.Save,
      title = "Esporta configurazione",
      value = if (busy) "Attendere…" else "File JSON",
      showChevron = true,
      onClick = {
        if (!busy) {
          status = null
          dialog = BackupDialog.EXPORT_DESTINATION
        }
      }
    )
    SettingsSwitchRow(
      title = "Includi credenziali",
      subtitle = "Chiavi API Debrid/OpenSubtitles e account SIMKL. Il file diventa sensibile: conservalo in luogo sicuro.",
      checked = includeCredentials,
      onCheckedChange = { if (!busy) includeCredentials = it }
    )
    SettingsRow(
      icon = Icons.Default.CloudUpload,
      title = "Ripristina da file",
      value = if (busy) "Attendere…" else "Scegli backup",
      showChevron = true,
      onClick = {
        if (!busy) {
          status = null
          dialog = BackupDialog.IMPORT_SOURCE
        }
      }
    )
    val connected = authState
    if (connected is GoogleDriveAuthState.Connected) {
      SettingsRow(
        icon = Icons.Default.Cloud,
        title = "Account Google collegato",
        value = connected.email ?: "Collegato",
        onClick = {
          if (!busy) {
            busy = true
            scope.launch {
              val result = GoogleDriveBackupManager.disconnect()
              busy = false
              showStatus(
                result.fold(
                  onSuccess = { "Account Google scollegato. I backup restano nel tuo Drive." },
                  onFailure = { "Disconnessione eseguita (revoca su Google non confermata)." }
                )
              )
            }
          }
        }
      )
    }
    status?.let { message ->
      Text(
        text = message,
        color = NovaTextSecondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
      )
    }
  }

  // ── Dialog: destinazione export ────────────────────────────────────────────
  when (dialog) {
    BackupDialog.EXPORT_DESTINATION -> BackupChoiceDialog(
      title = "Dove vuoi salvare il backup?",
      options = listOf(
        BackupChoiceOption(
          label = "Locale",
          description = "Salva il backup sul dispositivo o in una cartella scelta da te.",
          icon = Icons.Default.Folder,
          onSelect = { closeDialog(); launchSafExport() }
        ),
        BackupChoiceOption(
          label = "Google Drive",
          description = "Salva il backup nel tuo Google Drive personale.",
          icon = Icons.Default.Cloud,
          onSelect = { closeDialog(); onDriveChosen(DriveAction.EXPORT) }
        )
      ),
      onDismiss = { closeDialog() }
    )

    BackupDialog.IMPORT_SOURCE -> BackupChoiceDialog(
      title = "Da dove vuoi importare?",
      options = listOf(
        BackupChoiceOption(
          label = "Locale",
          description = "Seleziona un file JSON dal dispositivo.",
          icon = Icons.Default.Folder,
          onSelect = { closeDialog(); launchSafImport() }
        ),
        BackupChoiceOption(
          label = "Google Drive",
          description = "Scegli un backup dal tuo Google Drive personale.",
          icon = Icons.Default.Cloud,
          onSelect = { closeDialog(); onDriveChosen(DriveAction.IMPORT) }
        )
      ),
      onDismiss = { closeDialog() }
    )

    BackupDialog.LOCAL_IMPORT_LIST -> BackupFileListDialog(
      title = "Backup sul dispositivo",
      files = localBackups,
      onPick = { entry ->
        closeDialog()
        busy = true
        scope.launch {
          val preview = BackupManager.previewFile(entry.file)
          busy = false
          preview.fold(
            onSuccess = { envelope -> pendingEnvelope = envelope },
            onFailure = { error -> showStatus(error.message) }
          )
        }
      },
      onDismiss = { closeDialog() }
    )

    BackupDialog.DRIVE_CONNECT -> DriveConnectDialog(
      authState = authState,
      onCancel = {
        GoogleDriveAuthRepository.cancelConnecting()
        connectJob?.cancel()
        connectJob = null
        closeDialog()
        busy = false
        showStatus("Collegamento Google annullato.")
      }
    )

    BackupDialog.DRIVE_IMPORT_LIST -> DriveFileListDialog(
      title = "Backup nel tuo Google Drive",
      loading = driveListLoading,
      files = driveBackups,
      onPick = { entry ->
        closeDialog()
        busy = true
        scope.launch {
          val preview = GoogleDriveBackupManager.downloadBackup(clientId, entry)
          busy = false
          preview.fold(
            onSuccess = { envelope -> pendingEnvelope = envelope },
            onFailure = { error ->
              showStatus("Download del backup non riuscito: ${GoogleDriveBackupManager.describe(error)}")
            }
          )
        }
      },
      onDismiss = { closeDialog() }
    )

    BackupDialog.NONE -> {}
  }

  // ── Anteprima + conferma esplicita del restore (pipeline comune) ───────────
  val envelope = pendingEnvelope
  if (envelope != null) {
    AlertDialog(
      onDismissRequest = { pendingEnvelope = null },
      title = { Text("Ripristinare questo backup?") },
      text = {
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Text(
            text = backupEnvelopeSummary(envelope),
            color = NovaTextSecondary,
            fontSize = 13.sp
          )
          Text(
            text = "L'operazione sovrascrive impostazioni e addon con quelli del file; " +
              "la libreria viene unita senza eliminare i titoli locali.",
            color = NovaTextSecondary,
            fontSize = 12.sp
          )
        }
      },
      confirmButton = {
        TextButton(onClick = {
          val toApply = envelope
          pendingEnvelope = null
          if (busy) return@TextButton
          busy = true
          scope.launch {
            val result = BackupManager.restore(context, toApply)
            busy = false
            showStatus(
              result.fold(
                onSuccess = { it.describe() },
                onFailure = { it.message ?: "Ripristino non riuscito" }
              )
            )
          }
        }) { Text("Ripristina") }
      },
      dismissButton = {
        TextButton(onClick = { pendingEnvelope = null }) { Text("Annulla") }
      }
    )
  }
}

/** Azione Drive sospesa durante il collegamento dell'account. */
private enum class DriveAction { EXPORT, IMPORT }

/** Dialog aperto nella sezione Backup e ripristino. */
private enum class BackupDialog { NONE, EXPORT_DESTINATION, IMPORT_SOURCE, LOCAL_IMPORT_LIST, DRIVE_CONNECT, DRIVE_IMPORT_LIST }

/** Voce della lista dei backup locali in-app. */
private data class FileEntry(val name: String, val description: String, val file: java.io.File)

/** Client ID Google della build: vuoto/placeholder = funzionalità non configurata. */
private fun googleDriveClientId(): String =
  BuildConfig.ENV_GOOGLE_DRIVE_CLIENT_ID.trim()
    .takeUnless { it.isBlank() || it.startsWith("DEFAULT_") } ?: ""

// ─────────────────────────────────────────────────────────────────────────────
// Dialog condivisi (pattern MediaContextMenu: finestra modale reale, focus group
// chiuso, focus iniziale differito e verificato, BACK gestito dal Dialog).
// ─────────────────────────────────────────────────────────────────────────────

/** Voce di scelta dei dialog di destinazione/sorgente. */
private data class BackupChoiceOption(
  val label: String,
  val description: String,
  val icon: ImageVector,
  val onSelect: () -> Unit
)

private const val BACKUP_DIALOG_FOCUS_MAX_ATTEMPTS = 30

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BackupChoiceDialog(
  title: String,
  options: List<BackupChoiceOption>,
  onDismiss: () -> Unit
) {
  val localizedContext = LocalContext.current
  val localizedConfiguration = LocalConfiguration.current
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(
      dismissOnBackPress = true,
      dismissOnClickOutside = false,
      usePlatformDefaultWidth = false
    )
  ) {
    androidx.compose.runtime.CompositionLocalProvider(
      androidx.compose.ui.platform.LocalContext provides localizedContext,
      androidx.compose.ui.platform.LocalConfiguration provides localizedConfiguration
    ) {
      val firstFocus = remember { FocusRequester() }
      var groupHasFocus by remember { mutableStateOf(false) }

      // Focus iniziale differito e VERIFICATO (come MediaContextMenu): la richiesta
      // si ripete per un numero limitato di frame finché il gruppo ha davvero il focus.
      LaunchedEffect(groupHasFocus) {
        var attempts = 0
        while (!groupHasFocus && attempts < BACKUP_DIALOG_FOCUS_MAX_ATTEMPTS) {
          attempts++
          try {
            firstFocus.requestFocus()
          } catch (_: IllegalStateException) {
          }
          withFrameNanos { }
        }
      }

      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
      ) {
        Column(
          modifier = Modifier
            .width(420.dp)
            .background(Color(0xFF1E1E24), RoundedCornerShape(16.dp))
            .border(1.dp, NovaCyan.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(20.dp)
            .focusGroup()
            .onFocusChanged { groupHasFocus = it.hasFocus }
        ) {
          Text(
            text = title,
            color = NovaTextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
          )
          Spacer(Modifier.height(14.dp))
          options.forEachIndexed { index, option ->
            TvFocusableBox(
              modifier = Modifier
                .fillMaxWidth()
                .then(
                  if (index == 0) Modifier.focusRequester(firstFocus) else Modifier
                )
                .then(
                  when (index) {
                    0 -> Modifier.focusProperties { up = FocusRequester.Cancel; left = FocusRequester.Cancel; right = FocusRequester.Cancel }
                    options.lastIndex -> Modifier.focusProperties { down = FocusRequester.Cancel; left = FocusRequester.Cancel; right = FocusRequester.Cancel }
                    else -> Modifier.focusProperties { left = FocusRequester.Cancel; right = FocusRequester.Cancel }
                  }
                ),
              shape = RoundedCornerShape(10.dp),
              focusedScale = 1.02f,
              onClick = option.onSelect
            ) { isFocused ->
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(
                    if (isFocused) NovaCyan.copy(alpha = 0.15f) else Color.Transparent,
                    RoundedCornerShape(10.dp)
                  )
                  .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Icon(
                  imageVector = option.icon,
                  contentDescription = null,
                  tint = if (isFocused) NovaCyanBright else NovaTextMuted,
                  modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(14.dp))
                Column {
                  Text(
                    text = option.label,
                    color = if (isFocused) NovaCyanBright else NovaTextPrimary,
                    fontSize = 15.sp,
                    fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal
                  )
                  Text(
                    text = option.description,
                    color = NovaTextSecondary,
                    fontSize = 12.sp
                  )
                }
              }
            }
            Spacer(Modifier.height(8.dp))
          }
          Text(
            text = "BACK per annullare",
            color = NovaTextMuted,
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
          )
        }
      }
    }
  }
}

/** Lista dei backup JSON nella cartella in-app (import fallback senza SAF). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BackupFileListDialog(
  title: String,
  files: List<FileEntry>,
  onPick: (FileEntry) -> Unit,
  onDismiss: () -> Unit
) {
  val localizedContext = LocalContext.current
  val localizedConfiguration = LocalConfiguration.current
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(
      dismissOnBackPress = true,
      dismissOnClickOutside = false,
      usePlatformDefaultWidth = false
    )
  ) {
    androidx.compose.runtime.CompositionLocalProvider(
      androidx.compose.ui.platform.LocalContext provides localizedContext,
      androidx.compose.ui.platform.LocalConfiguration provides localizedConfiguration
    ) {
      val firstFocus = remember { FocusRequester() }
      var groupHasFocus by remember { mutableStateOf(false) }
      LaunchedEffect(groupHasFocus) {
        var attempts = 0
        while (!groupHasFocus && attempts < BACKUP_DIALOG_FOCUS_MAX_ATTEMPTS) {
          attempts++
          try {
            firstFocus.requestFocus()
          } catch (_: IllegalStateException) {
          }
          withFrameNanos { }
        }
      }
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
      ) {
        Column(
          modifier = Modifier
            .width(520.dp)
            .background(Color(0xFF1E1E24), RoundedCornerShape(16.dp))
            .border(1.dp, NovaCyan.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(20.dp)
            .focusGroup()
            .onFocusChanged { groupHasFocus = it.hasFocus }
        ) {
          Text(
            text = title,
            color = NovaTextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
          )
          Spacer(Modifier.height(10.dp))
          if (files.isEmpty()) {
            Text(
              text = "Nessun backup trovato nella cartella " +
                "\"${LocalBackupStore.BACKUPS_DIR_NAME}\" dell'app. Esporta un backup prima di ripristinarlo.",
              color = NovaTextSecondary,
              fontSize = 13.sp
            )
          } else {
            files.take(8).forEachIndexed { index, entry ->
              TvFocusableBox(
                modifier = Modifier
                  .fillMaxWidth()
                  .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                shape = RoundedCornerShape(10.dp),
                focusedScale = 1.02f,
                onClick = { onPick(entry) }
              ) { isFocused ->
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .background(
                      if (isFocused) NovaCyan.copy(alpha = 0.15f) else Color.Transparent,
                      RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = null,
                    tint = if (isFocused) NovaCyanBright else NovaTextMuted,
                    modifier = Modifier.size(20.dp)
                  )
                  Spacer(Modifier.width(12.dp))
                  Column {
                    Text(
                      text = entry.name,
                      color = if (isFocused) NovaCyanBright else NovaTextPrimary,
                      fontSize = 13.sp,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis
                    )
                    Text(
                      text = entry.description,
                      color = NovaTextSecondary,
                      fontSize = 11.sp
                    )
                  }
                }
              }
              Spacer(Modifier.height(6.dp))
            }
          }
          Text(
            text = "BACK per annullare",
            color = NovaTextMuted,
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
          )
        }
      }
    }
  }
}

/** Dialog del collegamento Google: user_code + URL di verifica (Device Authorization Grant). */
@Composable
private fun DriveConnectDialog(
  authState: GoogleDriveAuthState,
  onCancel: () -> Unit
) {
  androidx.compose.ui.window.Dialog(
    onDismissRequest = onCancel,
    properties = DialogProperties(
      dismissOnBackPress = true,
      dismissOnClickOutside = false,
      usePlatformDefaultWidth = false
    )
  ) {
    val connecting = authState as? GoogleDriveAuthState.Connecting
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black.copy(alpha = 0.7f)),
      contentAlignment = Alignment.Center
    ) {
      Column(
        modifier = Modifier
          .width(520.dp)
          .background(Color(0xFF1E1E24), RoundedCornerShape(16.dp))
          .border(1.dp, NovaCyan.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
          .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = "Collega il tuo account Google",
          color = NovaTextPrimary,
          fontSize = 17.sp,
          fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))
        Text(
          text = "1. Su un telefono o computer apri:",
          color = NovaTextSecondary,
          fontSize = 13.sp
        )
        Text(
          text = connecting?.verificationUrl ?: "https://google.com/device",
          color = NovaCyanBright,
          fontSize = 15.sp,
          fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))
        Text(
          text = "2. Inserisci questo codice:",
          color = NovaTextSecondary,
          fontSize = 13.sp
        )
        Text(
          text = connecting?.userCode ?: "…",
          color = NovaTextPrimary,
          fontSize = 26.sp,
          fontWeight = FontWeight.Bold,
          fontFamily = FontFamily.Monospace,
          modifier = Modifier
            .background(Color(0xFF161E30), RoundedCornerShape(8.dp))
            .padding(horizontal = 20.dp, vertical = 8.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
          text = "In attesa dell'autorizzazione…",
          color = NovaTextMuted,
          fontSize = 12.sp
        )
        Spacer(Modifier.height(14.dp))
        TvFocusableBox(
          modifier = Modifier.width(220.dp),
          shape = RoundedCornerShape(8.dp),
          focusedScale = 1.02f,
          onClick = onCancel
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
              text = "Annulla",
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

/** Lista dei backup presenti nel Drive personale dell'utente, dal più recente. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DriveFileListDialog(
  title: String,
  loading: Boolean,
  files: List<DriveBackupEntry>,
  onPick: (DriveBackupEntry) -> Unit,
  onDismiss: () -> Unit
) {
  val localizedContext = LocalContext.current
  val localizedConfiguration = LocalConfiguration.current
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(
      dismissOnBackPress = true,
      dismissOnClickOutside = false,
      usePlatformDefaultWidth = false
    )
  ) {
    androidx.compose.runtime.CompositionLocalProvider(
      androidx.compose.ui.platform.LocalContext provides localizedContext,
      androidx.compose.ui.platform.LocalConfiguration provides localizedConfiguration
    ) {
      val firstFocus = remember { FocusRequester() }
      var groupHasFocus by remember { mutableStateOf(false) }
      LaunchedEffect(groupHasFocus) {
        var attempts = 0
        while (!groupHasFocus && attempts < BACKUP_DIALOG_FOCUS_MAX_ATTEMPTS) {
          attempts++
          try {
            firstFocus.requestFocus()
          } catch (_: IllegalStateException) {
          }
          withFrameNanos { }
        }
      }
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
      ) {
        Column(
          modifier = Modifier
            .width(520.dp)
            .background(Color(0xFF1E1E24), RoundedCornerShape(16.dp))
            .border(1.dp, NovaCyan.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
            .padding(20.dp)
            .focusGroup()
            .onFocusChanged { groupHasFocus = it.hasFocus }
        ) {
          Text(
            text = title,
            color = NovaTextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
          )
          Spacer(Modifier.height(10.dp))
          when {
            loading -> Text(
              text = "Lettura dal tuo Google Drive…",
              color = NovaTextSecondary,
              fontSize = 13.sp
            )
            files.isEmpty() -> Text(
              text = "Nessun backup trovato nella cartella \"${GoogleDriveFilesClient.FOLDER_NAME}\" " +
                "del tuo Drive. Esporta un backup prima di ripristinarlo.",
              color = NovaTextSecondary,
              fontSize = 13.sp
            )
            else -> files.take(8).forEachIndexed { index, entry ->
              TvFocusableBox(
                modifier = Modifier
                  .fillMaxWidth()
                  .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                shape = RoundedCornerShape(10.dp),
                focusedScale = 1.02f,
                onClick = { onPick(entry) }
              ) { isFocused ->
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .background(
                      if (isFocused) NovaCyan.copy(alpha = 0.15f) else Color.Transparent,
                      RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = null,
                    tint = if (isFocused) NovaCyanBright else NovaTextMuted,
                    modifier = Modifier.size(20.dp)
                  )
                  Spacer(Modifier.width(12.dp))
                  Column {
                    Text(
                      text = entry.fileName,
                      color = if (isFocused) NovaCyanBright else NovaTextPrimary,
                      fontSize = 13.sp,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis
                    )
                    Text(
                      text = describeDriveEntry(entry),
                      color = NovaTextSecondary,
                      fontSize = 11.sp
                    )
                  }
                }
              }
              Spacer(Modifier.height(6.dp))
            }
          }
          Text(
            text = "BACK per annullare",
            color = NovaTextMuted,
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
          )
        }
      }
    }
  }
}

private fun describeDriveEntry(entry: DriveBackupEntry): String = buildString {
  append(
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY)
      .format(Date(entry.modifiedTimeMs))
  )
  if (entry.sizeBytes > 0) {
    append(" · ${entry.sizeBytes / 1024} KB")
  }
}
