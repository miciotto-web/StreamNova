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
  }
}

/** DTO della GitHub Release (solo i campi necessari al rilevamento). */
data class GitHubReleaseDto(
  @Json(name = "tag_name") val tagName: String? = null,
  @Json(name = "name") val name: String? = null,
  @Json(name = "html_url") val htmlUrl: String? = null,
  @Json(name = "draft") val draft: Boolean? = null,
  @Json(name = "prerelease") val prerelease: Boolean? = null,
  @Json(name = "assets") val assets: List<GitHubReleaseAssetDto>? = null
)

/** DTO di un asset allegato alla GitHub Release. */
data class GitHubReleaseAssetDto(
  @Json(name = "name") val name: String? = null,
  @Json(name = "browser_download_url") val browserDownloadUrl: String? = null,
  @Json(name = "size") val size: Long? = null
)

/** Asset di una release con nome, URL di download e dimensione dichiarata. */
data class ReleaseAsset(
  val name: String,
  val downloadUrl: String,
  val size: Long?
)

/** Release più recente pubblicata sul repository ufficiale StreamNova. */
data class LatestRelease(
  val tagName: String,
  val version: AppVersion,
  val releaseUrl: String?,
  val assets: List<ReleaseAsset>
)

/** Errore del controllo: rete assente, timeout, risposta HTTP o JSON non valido. */
class UpdateCheckException(message: String, cause: Throwable? = null) :
  IOException(message, cause)

/**
 * Client HTTP dedicato alle GitHub Releases di [owner]/[repo].
 * Componente isolato: non viene mischiato con MediaRepository o lo streaming.
 */
class GitHubUpdateClient(
  owner: String = "miciotto-web",
  repo: String = "StreamNova"
) {

  private val latestReleaseUrl =
    "https://api.github.com/repos/$owner/$repo/releases/latest"

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
   * HTTP error, JSON non valido) vengono normalizzati in [UpdateCheckException].
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
          throw UpdateCheckException("HTTP ${response.code}")
        }
        response.body?.string() ?: throw UpdateCheckException("Risposta vuota")
      }
    } catch (e: UpdateCheckException) {
      throw e
    } catch (e: IOException) {
      throw UpdateCheckException(e.message ?: "Errore di rete", e)
    }

    val release = try {
      releaseAdapter.fromJson(responseBody)
        ?: throw UpdateCheckException("JSON non valido")
    } catch (e: UpdateCheckException) {
      throw e
    } catch (e: Exception) {
      throw UpdateCheckException("JSON non valido", e)
    }

    val tagName = release.tagName?.trim().orEmpty()
    val version = AppVersion.parse(tagName)
      ?: throw UpdateCheckException("Tag non valido: $tagName")

    val assets = release.assets.orEmpty()
      .mapNotNull { asset ->
        val name = asset.name?.trim().orEmpty()
        val url = asset.browserDownloadUrl?.trim().orEmpty()
        if (name.isEmpty() || url.isEmpty()) null
        else ReleaseAsset(name = name, downloadUrl = url, size = asset.size)
      }

    return LatestRelease(
      tagName = tagName,
      version = version,
      releaseUrl = release.htmlUrl,
      assets = assets
    )
  }
}
