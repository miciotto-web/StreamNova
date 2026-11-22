package com.example.data.backup

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Archivio locale IN-APP dei backup: cartella privata dell'app
 * (`Android/data/<pkg>/files/StreamNova Backups`), scrivibile senza permessi e
 * senza Storage Access Framework.
 *
 * È il fallback deterministico per i dispositivi TV senza document provider
 * (es. Fire TV senza DocumentsUI), dove il picker SAF non può aprirsi. Gli export
 * locali SAFE restano la scelta primaria dove il picker esiste; questa cartella
 * è anche la lista sorgente dell'import fallback.
 *
 * Nessun dato lascia il dispositivo tramite questo archivio.
 */
object LocalBackupStore {

  const val BACKUPS_DIR_NAME = "StreamNova Backups"
  private const val TMP_SUFFIX = ".tmp"

  /** Cartella dei backup in-app (creata on demand, mai lanciata su filesystem non disponibili). */
  fun backupsDir(context: Context): File {
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    return File(base, BACKUPS_DIR_NAME).apply { mkdirs() }
  }

  /** Tutti i backup JSON in-app, dal più recente. */
  fun listBackups(context: Context): List<File> =
    backupsDir(context).listFiles { f -> f.isFile && f.name.endsWith(".json", ignoreCase = true) }
      ?.sortedByDescending { it.lastModified() }
      ?: emptyList()

  /** Descrizione leggibile per la lista import: nome, data e dimensione. */
  fun describe(file: File): String = buildString {
    append(SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date(file.lastModified())))
    append(" · ")
    append(formatSize(file.length()))
  }

  private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(Locale.US, bytes / 1_000_000f)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
  }

  /** Cancella un eventuale file temporaneo residuo (export interrotto prima del rename). */
  internal fun cleanupTempFiles(context: Context) {
    backupsDir(context).listFiles { f -> f.isFile && f.name.endsWith(TMP_SUFFIX) }
      ?.forEach { it.delete() }
  }
}
