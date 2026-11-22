package com.example.data.storage

import android.content.Context
import android.util.Log
import coil.imageLoader
import com.example.data.image.ImageCache
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fotografia dell'occupazione di spazio gestita dall'app.
 *
 * Tutte le voci sono MISURATE sul filesystem al momento della scansione; non
 * esistono stime. [totalBytes] è la somma delle aree cancellabili: non include il
 * database Room (dati utente) né il DataStore delle preferenze.
 */
data class StorageBreakdown(
  /** Cache immagini Coil (poster, backdrop, loghi). */
  val imageCacheBytes: Long = 0L,
  /** Sottotitoli OpenSubtitles scaricati (`filesDir/online_subtitles`). */
  val onlineSubtitlesBytes: Long = 0L,
  /** Cache interna dell'app (`cacheDir`, esclusa la cache immagini). */
  val appCacheBytes: Long = 0L,
  /** Cache esterna (`externalCacheDir`), quando disponibile. */
  val externalCacheBytes: Long = 0L,
  /** File APK scaricati dall'aggiornamento in `cacheDir`. */
  val updateFilesBytes: Long = 0L
) {
  val totalBytes: Long
    get() = imageCacheBytes + onlineSubtitlesBytes + appCacheBytes + externalCacheBytes + updateFilesBytes

  fun describe(): String = buildString {
    append("Immagini ${StorageManager.formatBytes(imageCacheBytes)}")
    append(" · Sottotitoli ${StorageManager.formatBytes(onlineSubtitlesBytes)}")
    append(" · Cache ${StorageManager.formatBytes(appCacheBytes + externalCacheBytes)}")
    if (updateFilesBytes > 0) append(" · Aggiornamenti ${StorageManager.formatBytes(updateFilesBytes)}")
  }
}

/** Esito di una pulizia: byte realmente liberati (misurati prima/dopo). */
data class StorageClearResult(
  val clearedBytes: Long,
  val failedFiles: Int = 0
) {
  val freedLabel: String get() = StorageManager.formatBytes(clearedBytes)
}

/**
 * Storage Manager: misura e pulizia delle aree NON critiche.
 *
 * Regole di sicurezza:
 *  - la pulizia non tocca MAI database Room, preferenze DataStore, profili,
 *    addon installati o APK dell'app;
 *  - ogni operazione misura lo spazio PRIMA e DOPO: il valore mostrato è quello
 *    realmente liberato, non una stima;
 *  - un file non cancellabile (permessi, file in uso) non interrompe la pulizia:
 *    viene contato e riportato.
 */
object StorageManager {

  private const val TAG = "StorageManager"

  /** Directory dei sottotitoli scaricati (stessa costante del repository). */
  const val SUBTITLES_DIR = "online_subtitles"

  /** Scansione completa (IO): misura tutte le aree gestite. */
  suspend fun scan(context: Context): StorageBreakdown = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val imageDir = ImageCache.directory(appContext)
    val subtitlesDir = subtitlesDirectory(appContext)
    val cacheDir = appContext.cacheDir
    StorageBreakdown(
      imageCacheBytes = directorySizeBytes(imageDir),
      onlineSubtitlesBytes = directorySizeBytes(subtitlesDir),
      appCacheBytes = cacheChildrenSize(cacheDir, exclude = imageDir),
      externalCacheBytes = directorySizeBytes(appContext.externalCacheDir),
      updateFilesBytes = cacheDir.listFiles()
        ?.filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
        ?.sumOf { it.length() }
        ?: 0L
    )
  }

  /**
   * Svuota la cache immagini Coil (memoria + disco). La cache in memoria viene
   * liberata subito; la directory disco viene svuotata ricorsivamente perché il
   * DiskCache è condiviso con l'ImageLoader dell'app.
   */
  suspend fun clearImageCache(context: Context): StorageClearResult = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val dir = ImageCache.directory(appContext)
    val before = directorySizeBytes(dir)
    runCatching { appContext.imageLoader.memoryCache?.clear() }
      .onFailure { Log.w(TAG, "Pulizia memory cache immagini fallita: ${it.message}") }
    val failed = deleteChildren(dir)
    val after = directorySizeBytes(dir)
    Log.i(TAG, "Cache immagini: liberati ${before - after} byte")
    StorageClearResult(clearedBytes = (before - after).coerceAtLeast(0L), failedFiles = failed)
  }

  /**
   * Elimina i file dei sottotitoli scaricati. Le selezioni persistenti restano:
   * alla riapertura del contenuto il file viene riscaricato dal `fileId` salvato.
   */
  suspend fun clearOnlineSubtitles(context: Context): StorageClearResult =
    withContext(Dispatchers.IO) {
      val dir = subtitlesDirectory(context.applicationContext)
      val before = directorySizeBytes(dir)
      val failed = deleteChildren(dir)
      val after = directorySizeBytes(dir)
      Log.i(TAG, "Sottotitoli: liberati ${before - after} byte")
      StorageClearResult(clearedBytes = (before - after).coerceAtLeast(0L), failedFiles = failed)
    }

  /**
   * Svuota la cache temporanea (interna + esterna) TRANNE la cache immagini, che
   * ha un'azione dedicata. Non tocca database, preferenze né file utente.
   */
  suspend fun clearTransientCaches(context: Context): StorageClearResult =
    withContext(Dispatchers.IO) {
      val appContext = context.applicationContext
      val imageDir = ImageCache.directory(appContext)
      val cacheDir = appContext.cacheDir
      val externalDir = appContext.externalCacheDir
      val before = cacheChildrenSize(cacheDir, exclude = imageDir) +
        directorySizeBytes(externalDir)
      var failed = deleteChildren(cacheDir, exclude = imageDir)
      if (externalDir != null) failed += deleteChildren(externalDir)
      val after = cacheChildrenSize(cacheDir, exclude = imageDir) +
        directorySizeBytes(externalDir)
      Log.i(TAG, "Cache temporanea: liberati ${before - after} byte")
      StorageClearResult(clearedBytes = (before - after).coerceAtLeast(0L), failedFiles = failed)
    }

  /** Directory dei sottotitoli, creata se assente. */
  fun subtitlesDirectory(context: Context): File =
    File(context.applicationContext.filesDir, SUBTITLES_DIR).also { it.mkdirs() }

  /** Dimensione leggibile: "1.25 GB", "420.5 MB", "0 B". */
  fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L)
    return when {
      value >= 1024L * 1024 * 1024 ->
        String.format(Locale.US, "%.2f GB", value / (1024.0 * 1024 * 1024))
      value >= 1024L * 1024 ->
        String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024))
      value >= 1024 -> "${value / 1024} KB"
      else -> "$value B"
    }
  }

  /** Somma ricorsiva delle dimensioni dei file regolari. Directory assente = 0. */
  internal fun directorySizeBytes(dir: File?): Long {
    if (dir == null || !dir.exists()) return 0L
    return try {
      dir.walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }
    } catch (e: Exception) {
      Log.w(TAG, "Misura directory fallita (${dir.absolutePath}): ${e.message}")
      0L
    }
  }

  /** Somma dei figli diretti/ricorsivi escludendo [exclude] (e la sua sottostruttura). */
  private fun cacheChildrenSize(dir: File?, exclude: File?): Long {
    if (dir == null || !dir.exists()) return 0L
    val excludePath = exclude?.canonicalPath
    return dir.listFiles()?.sumOf { child ->
      if (excludePath != null && child.canonicalPath == excludePath) 0L
      else directorySizeBytes(child)
    } ?: 0L
  }

  /** Cancella i figli di [dir], restituisce il numero di file non cancellabili. */
  private fun deleteChildren(dir: File?, exclude: File? = null): Int {
    if (dir == null || !dir.exists()) return 0
    val excludePath = exclude?.canonicalPath
    var failed = 0
    dir.listFiles()?.forEach { child ->
      if (excludePath != null && child.canonicalPath == excludePath) return@forEach
      if (!child.deleteRecursively()) failed++
    }
    return failed
  }
}
