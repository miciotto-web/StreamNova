package com.example.data.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Avvio dell'installer Android per l'APK scaricato, tramite
 * ACTION_VIEW + content URI (FileProvider): mai file:// e mai
 * installazioni silenzio.
 */
object ApkInstaller {

  private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

  /** true se l'utente ha già autorizzato l'installazione da questa sorgente. */
  fun canInstall(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.packageManager.canRequestPackageInstalls()
    } else {
      true
    }
  }

  /**
   * Avvia l'installer di sistema per [apkFile]. Restituisce false se il file
   * è mancante/vuoto o manca l'autorizzazione ad installare da questa sorgente.
   */
  fun launch(context: Context, apkFile: File): Boolean {
    if (!apkFile.exists() || apkFile.length() <= 0L) return false
    if (!canInstall(context)) return false
    return try {
      val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        apkFile
      )
      val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, APK_MIME_TYPE)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      true
    } catch (e: ActivityNotFoundException) {
      false
    } catch (e: IllegalArgumentException) {
      false
    }
  }

  /** Apre le impostazioni di sistema per consentire l'installazione da questa sorgente. */
  fun openInstallPermissionSettings(context: Context) {
    try {
      val intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}")
      ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
      // Schermata non disponibile: il messaggio in UI resta comunque visibile
    }
  }
}
