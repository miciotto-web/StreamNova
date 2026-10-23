package com.example.data.update

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/** Motivo di errore del download, mappato dalla UI sulle stringhe localizzate. */
enum class DownloadErrorReason {
  NO_APK_IN_RELEASE,
  NO_NETWORK,
  TIMEOUT,
  HTTP_ERROR,
  HOST_NOT_ALLOWED,
  INTERRUPTED,
  FILE_MISSING,
  INSUFFICIENT_SPACE,

  /** Il file scaricato non ha la dimensione dichiarata dall'asset: download troncato. */
  SIZE_MISMATCH,

  /** Il digest SHA-256 dichiarato dall'asset non corrisponde al file scaricato. */
  CHECKSUM_MISMATCH,

  UNKNOWN
}

/**
 * Host GitHub autorizzati al download delle release.
 *
 * `release-assets.githubusercontent.com` e' l'host a cui GitHub reindirizza
 * `github.com/.../releases/download/...`: senza questo host l'interceptor di
 * rete bloccava il redirect e l'utente leggeva un errore HTTP fuorviante.
 * Elenco esplicito e chiuso: niente wildcard, niente domini generici.
 */
internal val ALLOWED_RELEASE_HOSTS = setOf(
  "github.com",
  "objects.githubusercontent.com",
  "release-assets.githubusercontent.com",
  "api.github.com"
)

/**
 * Politica di validazione di un URL di download. I default sono quelli di
 * produzione (HTTPS obbligatorio + soli host GitHub): i parametri esistono solo
 * perché il test del download completo possa puntare a un MockWebServer locale
 * senza indebolire il comportamento reale, che resta verificato dal test della
 * politica predefinita.
 */
internal data class ReleaseUrlPolicy(
  val allowedHosts: Set<String>,
  val requireHttps: Boolean
)

/** Politica di produzione: immutabile, HTTPS obbligatorio, solo host GitHub. */
internal val DEFAULT_RELEASE_POLICY = ReleaseUrlPolicy(
  allowedHosts = ALLOWED_RELEASE_HOSTS,
  requireHttps = true
)

/**
 * Politica di validazione di un URL di download: HTTPS esclusivo (salvo policy
 * di test) e host presente nell'elenco consentito.
 *
 * L'host viene estratto con [java.net.URI], che separa correttamente
 * userinfo, porta e percorso: un URL come `https://github.com@evil.example/x`
 * restituisce `evil.example` e viene quindi respinto, mentre un confronto
 * testuale sulla stringa grezza potrebbe approvarlo per errore.
 *
 * Restituisce false per qualsiasi URL non parsabile: fall closed.
 */
internal fun isAllowedReleaseUrl(
  url: String,
  policy: ReleaseUrlPolicy = DEFAULT_RELEASE_POLICY
): Boolean {
  val uri = try {
    java.net.URI(url.trim())
  } catch (_: Exception) {
    return false
  }
  val scheme = uri.scheme?.lowercase()
  val host = uri.host?.lowercase()?.removeSuffix(".")
  val schemeAllowed = if (policy.requireHttps) {
    scheme == "https"
  } else {
    scheme == "https" || scheme == "http"
  }
  return schemeAllowed && host != null && host in policy.allowedHosts
}

/**
 * Host (non l'URL completo) di una destinazione bloccata, da usare nei log:
 * non registra query string firmate, token o path.
 */
internal fun safeHostForLog(url: String): String = try {
  java.net.URI(url.trim()).host?.lowercase() ?: "host sconosciuto"
} catch (_: Exception) {
  "URL non valido"
}

/** Errore del download con motivo normalizzato per la UI. */
class DownloadException(
  val reason: DownloadErrorReason,
  message: String,
  cause: Throwable? = null
) : IOException(message, cause)

/**
 * Estrae l'hash SHA-256 atteso da un digest GitHub nel formato `sha256:<hex>`.
 * Restituisce null se il digest è assente o in un formato non verificabile
 * (algoritmo diverso, valore non esadecimale, lunghezza errata): in quel caso
 * non si inventa alcun valore atteso e la verifica del digest viene saltata.
 */
internal fun parseSha256Digest(digest: String?): String? {
  val value = digest?.trim().orEmpty()
  if (value.isEmpty()) return null
  val separator = value.indexOf(':')
  if (separator <= 0 || separator == value.length - 1) return null
  val algorithm = value.substring(0, separator).trim()
  if (!algorithm.equals("sha256", ignoreCase = true)) return null
  val hex = value.substring(separator + 1).trim().lowercase()
  if (hex.length != 64 || hex.any { it !in "0123456789abcdef" }) return null
  return hex
}

/** SHA-256 esadecimale (minuscolo) del contenuto di [file]. */
internal fun sha256Hex(file: File): String {
  val digest = MessageDigest.getInstance("SHA-256")
  file.inputStream().use { input ->
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
      val read = input.read(buffer)
      if (read == -1) break
      digest.update(buffer, 0, read)
    }
  }
  return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Verifica l'integrità del file scaricato: esistente, non vuoto, della
 * dimensione dichiarata (quando nota) e con il digest SHA-256 atteso (quando
 * GitHub lo fornisce). Lancia [DownloadException] con motivo tipizzato; il
 * chiamante elimina il file creato da questa operazione, così un file troncato
 * o corrotto non resta mai presentato come valido.
 */
internal fun verifyDownloadedApk(
  file: File,
  expectedSize: Long?,
  expectedDigest: String?
) {
  if (!file.exists() || file.length() <= 0L) {
    throw DownloadException(
      DownloadErrorReason.FILE_MISSING,
      "File APK mancante o vuoto"
    )
  }
  if (expectedSize != null && expectedSize > 0L && file.length() != expectedSize) {
    throw DownloadException(
      DownloadErrorReason.SIZE_MISMATCH,
      "Dimensione non corrispondente: attesi $expectedSize byte, trovati ${file.length()}"
    )
  }
  val expected = parseSha256Digest(expectedDigest) ?: return
  val actual = sha256Hex(file)
  if (!actual.equals(expected, ignoreCase = true)) {
    throw DownloadException(
      DownloadErrorReason.CHECKSUM_MISMATCH,
      "Digest SHA-256 non corrispondente"
    )
  }
}

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
class ApkDownloader(
  allowedHosts: Set<String> = ALLOWED_RELEASE_HOSTS,
  requireHttps: Boolean = true
) {

  private val policy = ReleaseUrlPolicy(allowedHosts = allowedHosts, requireHttps = requireHttps)

  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(15, TimeUnit.MINUTES)
    .addNetworkInterceptor { chain ->
      // Valutato su OGNI destinazione effettiva, redirect inclusi: l'header
      // Location non viene mai accettato alla cieca.
      val url = chain.request().url.toString()
      if (!isAllowedReleaseUrl(url, policy)) {
        throw DownloadException(
          DownloadErrorReason.HOST_NOT_ALLOWED,
          "Host non consentito: ${safeHostForLog(url)}"
        )
      }
      chain.proceed(chain.request())
    }
    .build()

  /**
   * Scarica [asset] restituendo il file APK verificato (esistente, non vuoto,
   * della dimensione dichiarata e con il digest SHA-256 atteso se fornito).
   * [onProgress] viene invocato con la percentuale (0-100) o null se ignota;
   * la progressione è monotona crescente.
   * Eccezioni normalizzate in [DownloadException]: nessun crash.
   */
  fun download(
    context: Context,
    asset: ReleaseAsset,
    onProgress: (Int?) -> Unit
  ): File {
    if (!isAllowedReleaseUrl(asset.downloadUrl, policy)) {
      throw DownloadException(
        DownloadErrorReason.HOST_NOT_ALLOWED,
        "Host asset non consentito: ${safeHostForLog(asset.downloadUrl)}"
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
        // Dimensione attesa: prima il Content-Length della risposta, poi la
        // dimensione dichiarata dall'asset. Senza né l'una né l'altra la
        // percentuale resta ignota, ma il download procede comunque.
        val total = body.contentLength().takeIf { it > 0L }
          ?: asset.size?.takeIf { it > 0L }
          ?: -1L
        val expectedSize = total.takeIf { it > 0L }

        val usable = dir.usableSpace
        if (expectedSize != null && usable > 0L) {
          // Margine prudente (10% + 16 MB) per scrittura del file e installazione.
          val required = expectedSize + expectedSize / 10 + MIN_FREE_MARGIN_BYTES
          if (usable < required) {
            throw DownloadException(
              DownloadErrorReason.INSUFFICIENT_SPACE,
              "Spazio insufficiente per $expectedSize byte"
            )
          }
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
              if (total > 0L) {
                val percent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                // Monotona: mai un valore uguale o inferiore al precedente.
                if (percent > lastPercent) {
                  lastPercent = percent
                  onProgress(percent)
                }
              }
            }
            output.flush()
          }
        }
      }

      // Verifica finale: un file troncato, vuoto o con digest errato viene
      // eliminato e segnalato, e l'installer non viene mai avviato.
      verifyDownloadedApk(
        file = target,
        expectedSize = asset.size,
        expectedDigest = asset.digest
      )
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

  companion object {
    const val UPDATE_DIR = "updates"

    /** Margine di spazio oltre al 10%: overhead di scrittura e installazione. */
    private const val MIN_FREE_MARGIN_BYTES = 16L * 1024L * 1024L
  }
}
