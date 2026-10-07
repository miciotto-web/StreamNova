package com.example.data.update

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/** Motivo di errore del download, mappato dalla UI sulle stringhe localizzate. */
enum class DownloadErrorReason {
  NO_APK_IN_RELEASE,
  NO_NETWORK,
  TIMEOUT,
  HTTP_ERROR,
  INTERRUPTED,
  FILE_MISSING,
  INSUFFICIENT_SPACE,
  UNKNOWN
}

/** Errore del download con motivo normalizzato per la UI. */
class DownloadException(
  val reason: DownloadErrorReason,
  message: String,
  cause: Throwable? = null
) : IOException(message, cause)

/**
 * Seleziona l'asset APK da scaricare: solo estensione .apk, con preferenza
 * per "app-release.apk". Restituisce null se la release non contiene APK.
 */
fun selectApkAsset(assets: List<ReleaseAsset>): ReleaseAsset? {
  val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
  return apkAssets.firstOrNull { it.name.equals("app-release.apk", ignoreCase = true) }
    ?: apkAssets.firstOrNull()
}

/**
 * Download dell'APK via OkHttp in un file privato dell'app (cache/updates),
 * adatto al FileProvider per l'installazione. Nessun accesso allo storage
 * esterno e nessun permesso legacy.
 */
class ApkDownloader {

  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(15, TimeUnit.MINUTES)
    .addNetworkInterceptor { chain ->
      val url = chain.request().url
      if (url.scheme != "https" || url.host !in ALLOWED_HOSTS) {
        throw DownloadException(
          DownloadErrorReason.HTTP_ERROR,
          "URL non consentito: $url"
        )
      }
      chain.proceed(chain.request())
    }
    .build()

  /**
   * Scarica [asset] restituendo il file APK verificato (esistente e > 0 byte).
   * [onProgress] viene invocato con la percentuale (0-100) o null se ignota.
   * Eccezioni normalizzate in [DownloadException]: nessun crash.
   */
  fun download(
    context: Context,
    asset: ReleaseAsset,
    onProgress: (Int?) -> Unit
  ): File {
    if (!asset.downloadUrl.startsWith("https://", ignoreCase = true) ||
      !isAllowedHost(asset.downloadUrl)
    ) {
      throw DownloadException(
        DownloadErrorReason.HTTP_ERROR,
        "URL asset non consentito: ${asset.downloadUrl}"
      )
    }

    val fileName = asset.name.substringAfterLast('/').ifBlank { "app-release.apk" }
    val dir = File(context.cacheDir, UPDATE_DIR)
    if (!dir.exists() && !dir.mkdirs()) {
      throw DownloadException(
        DownloadErrorReason.INSUFFICIENT_SPACE,
        "Impossibile creare la directory di download"
      )
    }

    val request = Request.Builder()
      .url(asset.downloadUrl)
      .header("Accept", "application/octet-stream")
      .header("User-Agent", "StreamNova-Updater")
      .get()
      .build()

    val target = File(dir, fileName)
    onProgress(null)

    try {
      httpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          throw DownloadException(
            DownloadErrorReason.HTTP_ERROR,
            "HTTP ${response.code}"
          )
        }
        val body = response.body
          ?: throw DownloadException(DownloadErrorReason.HTTP_ERROR, "Risposta vuota")
        val total = body.contentLength()
        val usable = dir.usableSpace
        if (total > 0 && usable > 0L && usable < total) {
          throw DownloadException(
            DownloadErrorReason.INSUFFICIENT_SPACE,
            "Spazio insufficiente per $total byte"
          )
        }

        if (target.exists()) target.delete()
        var downloaded = 0L
        var lastPercent = -1
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        body.byteStream().use { input ->
          FileOutputStream(target).use { output ->
            while (true) {
              val read = input.read(buffer)
              if (read == -1) break
              output.write(buffer, 0, read)
              downloaded += read
              if (total > 0) {
                val percent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                if (percent != lastPercent) {
                  lastPercent = percent
                  onProgress(percent)
                }
              }
            }
            output.flush()
          }
        }
      }
    } catch (e: DownloadException) {
      target.delete()
      throw e
    } catch (e: CancellationException) {
      target.delete()
      throw e
    } catch (e: Exception) {
      target.delete()
      throw mapToDownloadException(e)
    }

    if (!target.exists() || target.length() <= 0L) {
      target.delete()
      throw DownloadException(
        DownloadErrorReason.FILE_MISSING,
        "File APK mancante o vuoto"
      )
    }

    onProgress(100)
    return target
  }

  private fun mapToDownloadException(error: Exception): DownloadException = when (error) {
    is DownloadException -> error
    is UnknownHostException -> DownloadException(
      DownloadErrorReason.NO_NETWORK, "Rete assente", error
    )
    is java.net.SocketTimeoutException -> DownloadException(
      DownloadErrorReason.TIMEOUT, "Timeout", error
    )
    is ConnectException -> DownloadException(
      DownloadErrorReason.NO_NETWORK, "Connessione fallita", error
    )
    is IOException -> {
      val message = error.message.orEmpty()
      if (message.contains("space", ignoreCase = true) ||
        message.contains("ENOSPC", ignoreCase = true)
      ) {
        DownloadException(DownloadErrorReason.INSUFFICIENT_SPACE, message, error)
      } else {
        DownloadException(DownloadErrorReason.INTERRUPTED, message, error)
      }
    }
    else -> DownloadException(DownloadErrorReason.UNKNOWN, error.message ?: "Errore", error)
  }

  private fun isAllowedHost(url: String): Boolean {
    val host = url.substringAfter("://", "").substringBefore('/').substringBefore(':')
    return host in ALLOWED_HOSTS
  }

  companion object {
    const val UPDATE_DIR = "updates"

    /** Solo host GitHub ufficiali: la release StreamNova è l'unica sorgente valida. */
    private val ALLOWED_HOSTS = setOf(
      "github.com",
      "objects.githubusercontent.com",
      "api.github.com"
    )
  }
}
