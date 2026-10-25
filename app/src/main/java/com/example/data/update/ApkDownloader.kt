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
 * Sigle ABI riconoscibili nel nome di un asset, nell'ordine in cui vanno
 * confrontate: le sigle più lunghe vengono prima, così `x86_64` non viene mai
 * letto come `x86` e `armeabi-v7a` non come `armeabi`.
 */
private val KNOWN_ABI_TAGS = listOf(
  "arm64-v8a",
  "armeabi-v7a",
  "armeabi",
  "x86_64",
  "x86-64",
  "x86",
  "arm64",
  "mips64",
  "mips",
  "riscv64"
)

/** Sigla delimitata da separatori: evita di leggere `x86` dentro `x86_64`. */
private fun tokenPattern(token: String): Regex =
  Regex("(^|[^0-9a-z])${Regex.escape(token)}($|[^0-9a-z])")

private val ABI_TAG_PATTERNS: List<Pair<String, Regex>> =
  KNOWN_ABI_TAGS.map { it to tokenPattern(it) }

/**
 * Alias accettati nei nomi rinominati a mano, ricondotti alla sigla ABI di
 * Android: `arm64` è `arm64-v8a` e `x86-64` è `x86_64`, quindi un asset
 * ribattezzato così resta utilizzabile dal dispositivo giusto.
 */
private val ABI_ALIASES = mapOf(
  "arm64" to "arm64-v8a",
  "x86-64" to "x86_64"
)

/** Sigla ABI canonica: confronto fra nome dell'asset e ABI del dispositivo. */
private fun canonicalAbi(abi: String): String {
  val normalized = abi.trim().lowercase()
  return ABI_ALIASES[normalized] ?: normalized
}

private val UNIVERSAL_TAG_PATTERN = tokenPattern("universal")

/** Nome asset senza estensione, in minuscolo: base dei riconoscimenti. */
private fun assetStem(assetName: String): String =
  assetName.substringBeforeLast('.', assetName).lowercase()

/** Sigla ABI dichiarata nel nome dell'asset, o null se il nome non la contiene. */
private fun abiTagOf(assetName: String): String? {
  val stem = assetStem(assetName)
  return ABI_TAG_PATTERNS.firstOrNull { (_, pattern) -> pattern.containsMatchIn(stem) }?.first
}

/** true se il nome dichiara esplicitamente un APK universale (`...-universal...`). */
private fun isMarkedUniversal(assetName: String): Boolean =
  UNIVERSAL_TAG_PATTERN.containsMatchIn(assetStem(assetName))

/** Sigle ripulite, non vuote e senza duplicati: input omogeneo per l'ordinamento. */
private fun List<String>.cleanAbis(): List<String> =
  map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/**
 * Ordine di preferenza delle ABI con cui [selectApkAsset] sceglie l'APK.
 *
 * [supportedAbis] è l'elenco dichiarato dal sistema (`Build.SUPPORTED_ABIS`) e
 * resta l'**insieme chiuso** delle ABI scegliibili: ogni APK che esce dalla
 * selezione appartiene per costruzione a questo elenco, quindi il sistema
 * riesce a eseguirlo. [supported32BitAbis] e [supported64BitAbis] sono lo
 * stesso elenco separato per larghezza di parola (`Build.SUPPORTED_32_BIT_ABIS`,
 * `Build.SUPPORTED_64_BIT_ABIS`).
 *
 * [installedIs64BitProcess] è la larghezza di parola **del processo di questa
 * app** (`android.os.Process.is64Bit()`), non della CPU. Su una Fire TV a 64 bit
 * che esegue una installazione a 32 bit il sistema dichiara `arm64-v8a` prima di
 * `armeabi-v7a`: seguendo l'ordine del sistema si offrirebbe a una app a 32 bit
 * l'APK a 64 bit, cioè un cambio di architettura durante l'aggiornamento. Le ABI
 * della stessa larghezza di parola del processo vengono quindi messe davanti,
 * nell'ordine dichiarato dal sistema: l'aggiornamento resta sull'architettura su
 * cui l'app gira già. Se la release non pubblica più un APK per quella
 * architettura si ripiega sulle altre ABI dichiarate dal sistema — mai su una
 * ABI che il sistema non esegue.
 */
internal fun abiPreferenceOrder(
  installedIs64BitProcess: Boolean,
  supportedAbis: List<String>,
  supported32BitAbis: List<String>,
  supported64BitAbis: List<String>
): List<String> {
  val declared32 = supported32BitAbis.cleanAbis()
  val declared64 = supported64BitAbis.cleanAbis()
  val runnable = supportedAbis.cleanAbis().ifEmpty { (declared32 + declared64).distinct() }
  val sameWidthOfProcess = if (installedIs64BitProcess) declared64 else declared32
  val preferred = runnable.filter { abi ->
    sameWidthOfProcess.any { canonicalAbi(it) == canonicalAbi(abi) }
  }
  if (preferred.isEmpty()) return runnable
  return preferred + runnable.filterNot { abi ->
    preferred.any { canonicalAbi(it) == canonicalAbi(abi) }
  }
}

/**
 * Seleziona l'APK della release compatibile con le ABI del dispositivo.
 * Funzione pura e deterministica: a parità di asset e di ABI il risultato è
 * sempre lo stesso, e l'ordine con cui GitHub elenca gli asset non conta.
 *
 * Regole, in ordine di priorità:
 *  1. gli asset con sigla ABI nel nome (`StreamNova-1.0.1-arm64-v8a.apk`,
 *     `StreamNova-arm64-v8a-release.apk`) sono gli unici che dichiarano
 *     l'architettura: si sceglie quello corrispondente alla prima ABI
 *     dell'elenco ricevuto. L'elenco è quello prodotto da [abiPreferenceOrder],
 *     che mette davanti le ABI della larghezza di parola del processo di questa
 *     app e poi segue l'ordine dichiarato dal sistema. Se per quell'ABI la
 *     release pubblica due file, la scelta dipenderebbe dall'ordine di elenco:
 *     la release è ambigua e non si seleziona nulla;
 *  2. se nessuno split corrisponde al dispositivo si ripiega su un APK
 *     dichiarato esplicitamente universale (`...-universal...`), che per
 *     costruzione contiene tutte le ABI; se ce n'è più d'uno è ambiguo e non
 *     viene scelto;
 *  3. in ogni altro caso restituisce null — nessun APK, soli APK di ABI
 *     incompatibili, oppure asset che non dichiarano né architettura né
 *     universalità — e il chiamante non avvia alcun download. Mai "il primo
 *     APK disponibile".
 *
 * Un file che non dichiara la propria architettura non viene mai scelto, anche
 * quando è l'unico APK della release: `app-release.apk` è il caso storico, e
 * uno split ribattezzato a mano verrebbe offerto a tutti i dispositivi.
 *
 * Dalla 1.0.2 la release pubblica anche un APK universale con quel nome esatto
 * (`isUniversalApk = true` in `app/build.gradle.kts`): serve **solo** all'updater
 * della 1.0.0, che sceglie per nome o per posizione in elenco e non conosce né le
 * ABI né l'universalità dichiarata. Restando senza sigla ABI, quel file non è
 * selezionabile da qui e un client aggiornato continua a usare il proprio split:
 * è il motivo per cui l'APK universale non si rinomina `...-universal...` — così
 * lo prenderebbe anche la selezione nuova, che deve invece restare ancorata
 * all'architettura del dispositivo.
 */
fun selectApkAsset(assets: List<ReleaseAsset>, supportedAbis: List<String>): ReleaseAsset? {
  val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
  if (apkAssets.isEmpty()) return null

  val splits = apkAssets.mapNotNull { asset -> abiTagOf(asset.name)?.let { asset to it } }
  val universal = apkAssets.filter { isMarkedUniversal(it.name) }

  if (splits.isNotEmpty()) {
    supportedAbis.forEach { deviceAbi ->
      val matching = splits.filter { (_, abi) -> canonicalAbi(abi) == canonicalAbi(deviceAbi) }
      if (matching.isNotEmpty()) {
        // Due asset con la stessa ABI: sceglierne uno significherebbe dipendere
        // dall'ordine di elenco di GitHub, quindi non si sceglie nulla.
        return matching.singleOrNull()?.first
      }
    }
  }

  // Nessuno split per questo dispositivo: resta l'APK dichiarato universale.
  return universal.singleOrNull()
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
