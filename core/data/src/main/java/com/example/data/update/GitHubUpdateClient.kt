package com.example.data.update

import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Versione numerica (major/minor/patch) usata per confrontare la versione
 * installata con il tag di una release GitHub in modo puramente numerico.
 */
data class AppVersion(
  val major: Int,
  val minor: Int,
  val patch: Int
) : Comparable<AppVersion> {

  override fun compareTo(other: AppVersion): Int =
    compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

  /** Rappresentazione canonica "major.minor.patch", indipendente dal prefisso del tag. */
  override fun toString(): String = "$major.$minor.$patch"

  companion object {
    /**
     * Accetta tag con o senza prefisso "v" ("v0.1.0", "0.1.0"), versioni
     * parziali ("1.0") e qualificatori successivi ("1.0.0-beta.1").
     * Restituisce null se la versione non è numerica.
     */
    fun parse(raw: String?): AppVersion? {
      if (raw.isNullOrBlank()) return null
      var core = raw.trim()
      if (core.startsWith("v") || core.startsWith("V")) core = core.substring(1)
      core = core.substringBefore('-').substringBefore('+')
      val parts = core.split('.').filter { it.isNotEmpty() }
      if (parts.isEmpty() || parts.size > 3) return null
      val numbers = IntArray(3)
      parts.forEachIndexed { index, part ->
        val digits = part.takeWhile { it.isDigit() }
        if (digits.isEmpty()) return null
        numbers[index] = digits.toIntOrNull() ?: return null
      }
      return AppVersion(numbers[0], numbers[1], numbers[2])
    }

    /**
     * Estrae la versione dal NOME di un asset APK, quando presente in modo
     * affidabile (es. "StreamNova-V0.1.0-arm64-v8a.apk" -> 0.1.0).
     *
     * Heuristica deliberatamente conservativa: sono accettati solo token
     * delimitati non alfanumerici con almeno due componenti numeriche separate
     * da un punto ("v1.0.0", "0.1.1"). Così le sigle ABI ("arm64-v8a",
     * "armeabi-v7a", "x86_64") non vengono mai interpretate come versioni.
     * Restituisce null quando il nome non contiene una versione (es.
     * "app-release.apk"): in quel caso non c'è nulla da confrontare con il tag.
     */
    fun fromAssetName(assetName: String?): AppVersion? {
      val name = assetName?.trim().orEmpty()
      if (name.isEmpty()) return null
      val stem = name.substringBeforeLast('.', name)
      val match = VERSION_IN_NAME.find(stem) ?: return null
      val major = match.groupValues[1].toIntOrNull() ?: return null
      val minor = match.groupValues[2].toIntOrNull() ?: return null
      val patch = match.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
      return AppVersion(major, minor, patch)
    }

    /**
     * Delimitatore non alfanumerico ai due lati: impedisce che una cifra interna
     * a una sigla (es. "x86_64") venga letta come inizio di versione.
     */
    private val VERSION_IN_NAME =
      Regex("""(?<![0-9A-Za-z])[vV]?(\d+)\.(\d+)(?:\.(\d+))?(?![0-9A-Za-z])""")
  }
}

/** DTO della GitHub Release (solo i campi necessari al rilevamento). */
data class GitHubReleaseDto(
  @Json(name = "tag_name") val tagName: String? = null,
  @Json(name = "name") val name: String? = null,
  @Json(name = "body") val body: String? = null,
  @Json(name = "html_url") val htmlUrl: String? = null,
  @Json(name = "draft") val draft: Boolean? = null,
  @Json(name = "prerelease") val prerelease: Boolean? = null,
  @Json(name = "assets") val assets: List<GitHubReleaseAssetDto>? = null
)

/** DTO di un asset allegato alla GitHub Release. */
data class GitHubReleaseAssetDto(
  @Json(name = "name") val name: String? = null,
  @Json(name = "browser_download_url") val browserDownloadUrl: String? = null,
  @Json(name = "size") val size: Long? = null,
  // Digest dichiarato da GitHub nel formato "sha256:<hex>". Assente per gli
  // asset pubblicati prima che GitHub introducesse il campo.
  @Json(name = "digest") val digest: String? = null
)

/**
 * Asset di una release con nome, URL di download, dimensione dichiarata,
 * digest opzionale e versione estratta dal nome dell'asset (null se assente).
 */
data class ReleaseAsset(
  val name: String,
  val downloadUrl: String,
  val size: Long? = null,
  val digest: String? = null,
  val version: AppVersion? = null
)

/** Release più recente pubblicata sul repository ufficiale StreamNova. */
data class LatestRelease(
  val tagName: String,
  val version: AppVersion,
  val releaseUrl: String?,
  val assets: List<ReleaseAsset>,
  /** Changelog della release (campo `body` di GitHub), se presente. */
  val body: String? = null
)

/** Motivo tipizzato del fallimento del controllo aggiornamenti. */
enum class UpdateCheckErrorReason {
  /** 403/429: limite di richieste GitHub o accesso negato. */
  RATE_LIMITED,

  /** 404: nessuna release pubblicata o repository non disponibile. */
  NOT_FOUND,

  /** Errore di rete: assenza di connessione, timeout, host irraggiungibile. */
  NETWORK,

  /** Release incoerente: JSON non valido, tag non numerico o campi mancanti. */
  INVALID_RELEASE,

  /**
   * Release più recente che non offre un APK installabile su questo
   * dispositivo: nessun asset APK, oppure solo asset di ABI incompatibili o
   * ambigui. L'aggiornamento non viene proposto e nessun download parte.
   */
  NO_COMPATIBLE_APK,

  /** Fallimento non altrimenti classificabile. */
  UNKNOWN
}

/** Errore del controllo: rete assente, timeout, risposta HTTP o JSON non valido. */
class UpdateCheckException(
  val reason: UpdateCheckErrorReason,
  message: String,
  cause: Throwable? = null
) : IOException(message, cause)

/** Traduce un codice HTTP della GitHub API in un motivo tipizzato. */
internal fun updateCheckErrorReasonForCode(code: Int): UpdateCheckErrorReason = when (code) {
  403, 429 -> UpdateCheckErrorReason.RATE_LIMITED
  404 -> UpdateCheckErrorReason.NOT_FOUND
  else -> UpdateCheckErrorReason.UNKNOWN
}

/**
 * Client HTTP dedicato alle GitHub Releases di [owner]/[repo].
 * Componente isolato: non viene mischiato con MediaRepository o lo streaming.
 *
 * [apiBaseUrl] è iniettabile per i test (MockWebServer): in produzione resta
 * l'endpoint pubblico di GitHub.
 */
class GitHubUpdateClient(
  owner: String = "miciotto-web",
  repo: String = "StreamNova",
  apiBaseUrl: String = "https://api.github.com"
) {

  private val latestReleaseUrl =
    "${apiBaseUrl.trimEnd('/')}/repos/$owner/$repo/releases/latest"

  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    .build()

  private val moshi = Moshi.Builder()
    .add(KotlinJsonAdapterFactory())
    .build()

  private val releaseAdapter = moshi.adapter(GitHubReleaseDto::class.java)

  /**
   * Recupera la release più recente. Tutti gli errori (timeout, assenza rete,
   * HTTP error, JSON non valido) vengono normalizzati in [UpdateCheckException]
   * con un [UpdateCheckException.reason] tipizzato per la UI.
   */
  fun fetchLatestRelease(): LatestRelease {
    val request = Request.Builder()
      .url(latestReleaseUrl)
      .header("Accept", "application/vnd.github+json")
      .header("User-Agent", "StreamNova-Updater")
      .get()
      .build()

    val responseBody = try {
      httpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          throw UpdateCheckException(
            updateCheckErrorReasonForCode(response.code),
            "HTTP ${response.code}"
          )
        }
        response.body?.string()
          ?: throw UpdateCheckException(UpdateCheckErrorReason.INVALID_RELEASE, "Risposta vuota")
      }
    } catch (e: UpdateCheckException) {
      throw e
    } catch (e: IOException) {
      throw UpdateCheckException(
        UpdateCheckErrorReason.NETWORK,
        e.message ?: "Errore di rete",
        e
      )
    }

    val release = try {
      releaseAdapter.fromJson(responseBody)
        ?: throw UpdateCheckException(
          UpdateCheckErrorReason.INVALID_RELEASE,
          "JSON non valido"
        )
    } catch (e: UpdateCheckException) {
      throw e
    } catch (e: Exception) {
      throw UpdateCheckException(UpdateCheckErrorReason.INVALID_RELEASE, "JSON non valido", e)
    }

    val tagName = release.tagName?.trim().orEmpty()
    val version = AppVersion.parse(tagName)
      ?: throw UpdateCheckException(
        UpdateCheckErrorReason.INVALID_RELEASE,
        "Tag non valido: $tagName"
      )

    val assets = release.assets.orEmpty()
      .mapNotNull { asset ->
        val name = asset.name?.trim().orEmpty()
        val url = asset.browserDownloadUrl?.trim().orEmpty()
        if (name.isEmpty() || url.isEmpty()) null
        else ReleaseAsset(
          name = name,
          downloadUrl = url,
          size = asset.size,
          digest = asset.digest?.trim()?.takeIf { it.isNotEmpty() },
          version = AppVersion.fromAssetName(name)
        )
      }

    return LatestRelease(
      tagName = tagName,
      version = version,
      releaseUrl = release.htmlUrl,
      assets = assets,
      body = release.body?.takeIf { it.isNotBlank() }
    )
  }
}
