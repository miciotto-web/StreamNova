package com.example.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.backup.BackupEnvelope
import com.example.data.backup.BackupManager
import com.example.data.storage.StorageBreakdown
import com.example.data.storage.StorageManager
import com.example.ui.theme.NovaTextSecondary
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sezione Impostazioni "Backup e ripristino".
 *
 * - **Esporta**: crea un file JSON con impostazioni, addon, libreria e (opzionale)
 *   account SIMKL. Le chiavi API sono ESCLUSE di default; l'utente può includerle
 *   con uno switch esplicito.
 * - **Ripristina**: legge e VALIDA il file, mostra un'anteprima (versione app,
 *   data, contenuto) e applica solo dopo conferma. La libreria è fusa senza
 *   cancellare i titoli presenti solo su questo dispositivo.
 */
@Composable
fun BackupRestoreSettingsSection(
  appVersionName: String,
  appVersionCode: Int
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var includeCredentials by remember { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }
  var status by remember { mutableStateOf<String?>(null) }
  var pendingEnvelope by remember { mutableStateOf<BackupEnvelope?>(null) }

  val exportLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/json")
  ) { uri: Uri? ->
    if (uri == null) return@rememberLauncherForActivityResult
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
      status = result.fold(
        onSuccess = { it.describe() },
        onFailure = { "Esportazione non riuscita: ${it.message}" }
      )
      Toast.makeText(context, status, Toast.LENGTH_LONG).show()
    }
  }

  val importLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
  ) { uri: Uri? ->
    if (uri == null) return@rememberLauncherForActivityResult
    busy = true
    scope.launch {
      val preview = BackupManager.preview(context, uri)
      busy = false
      preview.fold(
        onSuccess = { envelope -> pendingEnvelope = envelope },
        onFailure = { error ->
          status = error.message
          Toast.makeText(context, error.message, Toast.LENGTH_LONG).show()
        }
      )
    }
  }

  SettingsSection("Backup e ripristino") {
    SettingsRow(
      icon = Icons.Default.Save,
      title = "Esporta configurazione",
      value = if (busy) "Attendere…" else "File JSON",
      showChevron = true,
      onClick = {
        if (!busy) {
          val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
          exportLauncher.launch("StreamNova-backup-$stamp.json")
        }
      }
    )
    SettingsSwitchRow(
      title = "Includi credenziali",
      subtitle = "Chiavi API Debrid/OpenSubtitles e account SIMKL. Il file diventa sensibile: conservalo in luogo sicuro.",
      checked = includeCredentials,
      onCheckedChange = { includeCredentials = it }
    )
    SettingsRow(
      icon = Icons.Default.CloudUpload,
      title = "Ripristina da file",
      value = if (busy) "Attendere…" else "Scegli backup",
      showChevron = true,
      onClick = { if (!busy) importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
    )
    status?.let { message ->
      Text(
        text = message,
        color = NovaTextSecondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
      )
    }
  }

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
          busy = true
          scope.launch {
            val result = BackupManager.restore(context, toApply)
            busy = false
            status = result.fold(
              onSuccess = { it.describe() },
              onFailure = { it.message ?: "Ripristino non riuscito" }
            )
            Toast.makeText(context, status, Toast.LENGTH_LONG).show()
          }
        }) { Text("Ripristina") }
      },
      dismissButton = {
        TextButton(onClick = { pendingEnvelope = null }) { Text("Annulla") }
      }
    )
  }
}

private fun backupEnvelopeSummary(envelope: BackupEnvelope): String = buildString {
  append("Backup del ")
  append(
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY)
      .format(Date(envelope.exportedAtEpochMs))
  )
  if (envelope.appVersionName.isNotBlank()) {
    append(" · versione ${envelope.appVersionName}")
  }
  append("\nContenuto: ")
  val parts = buildList {
    if (envelope.payload.appSettings != null || envelope.payload.playbackSettings != null) {
      add("impostazioni")
    }
    if (envelope.payload.addons.isNotEmpty()) add("${envelope.payload.addons.size} addon")
    if (envelope.payload.mediaItems.isNotEmpty()) add("${envelope.payload.mediaItems.size} titoli")
    if (envelope.payload.episodeProgress.isNotEmpty()) add("${envelope.payload.episodeProgress.size} progressi episodi")
    if (envelope.payload.simklAccount != null) add("account SIMKL")
  }
  append(if (parts.isEmpty()) "—" else parts.joinToString(", "))
  if (envelope.includesCredentials) append(" · include credenziali")
}

/** Alias con nome dell'icona usata nella sezione (CloudUpload esiste nelle extended icons). */

/**
 * Sezione Impostazioni "Spazio e cache" (Storage Manager).
 *
 * Mostra le dimensioni REALI delle aree gestite e consente di liberarle una per
 * una, con conferma. Non tocca mai database, preferenze, profili o addon.
 */
@Composable
fun StorageSettingsSection() {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var breakdown by remember { mutableStateOf<StorageBreakdown?>(null) }
  var busy by remember { mutableStateOf(false) }
  var pendingAction by remember { mutableStateOf<StorageClearAction?>(null) }
  var status by remember { mutableStateOf<String?>(null) }

  suspend fun refresh() {
    breakdown = StorageManager.scan(context)
  }

  LaunchedEffect(Unit) { refresh() }

  fun runClear(action: StorageClearAction) {
    if (busy) return
    busy = true
    scope.launch {
      val result = when (action) {
        StorageClearAction.IMAGES -> StorageManager.clearImageCache(context)
        StorageClearAction.SUBTITLES -> StorageManager.clearOnlineSubtitles(context)
        StorageClearAction.TEMP -> StorageManager.clearTransientCaches(context)
      }
      busy = false
      status = "Liberati ${result.freedLabel}" +
        if (result.failedFiles > 0) " · ${result.failedFiles} file non eliminabili" else ""
      refresh()
      Toast.makeText(context, status, Toast.LENGTH_LONG).show()
    }
  }

  SettingsSection("Spazio e cache") {
    val current = breakdown
    SettingsRow(
      icon = Icons.Default.Image,
      title = "Cache immagini",
      value = if (busy) "…" else current?.let { StorageManager.formatBytes(it.imageCacheBytes) } ?: "Calcolo…",
      showChevron = true,
      onClick = { if (!busy) pendingAction = StorageClearAction.IMAGES }
    )
    SettingsRow(
      icon = Icons.Default.Subtitles,
      title = "Sottotitoli scaricati",
      value = if (busy) "…" else current?.let { StorageManager.formatBytes(it.onlineSubtitlesBytes) } ?: "Calcolo…",
      showChevron = true,
      onClick = { if (!busy) pendingAction = StorageClearAction.SUBTITLES }
    )
    SettingsRow(
      icon = Icons.Default.DeleteSweep,
      title = "Cache temporanea",
      value = if (busy) "…" else current?.let {
        StorageManager.formatBytes(it.appCacheBytes + it.externalCacheBytes + it.updateFilesBytes)
      } ?: "Calcolo…",
      showChevron = true,
      onClick = { if (!busy) pendingAction = StorageClearAction.TEMP }
    )
    SettingsRow(
      icon = Icons.Default.Refresh,
      title = "Ricalcola spazio",
      value = current?.let { StorageManager.formatBytes(it.totalBytes) } ?: null,
      onClick = { if (!busy) scope.launch { refresh() } }
    )
    status?.let { message ->
      Text(
        text = message,
        color = NovaTextSecondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
      )
    }
  }

  val action = pendingAction
  if (action != null) {
    AlertDialog(
      onDismissRequest = { pendingAction = null },
      title = { Text(action.title) },
      text = {
        Text(
          text = action.description,
          color = NovaTextSecondary,
          fontSize = 13.sp
        )
      },
      confirmButton = {
        TextButton(onClick = {
          pendingAction = null
          runClear(action)
        }) { Text("Svuota") }
      },
      dismissButton = {
        TextButton(onClick = { pendingAction = null }) { Text("Annulla") }
      }
    )
  }
}

private enum class StorageClearAction(
  val title: String,
  val description: String
) {
  IMAGES(
    "Svuotare la cache immagini?",
    "Le copertine verranno riscaricate alla prossima visualizzazione. Nessun dato utente viene toccato."
  ),
  SUBTITLES(
    "Eliminare i sottotitoli scaricati?",
    "I file verranno riscaricati alla riapertura del contenuto, se hai una selezione salvata. Le selezioni restano."
  ),
  TEMP(
    "Svuotare la cache temporanea?",
    "Vengono rimossi i file temporanei interni ed esterni (esclusa la cache immagini). Nessun dato utente viene toccato."
  )
}
