package com.example.data.torbox

import android.util.Log
import com.example.data.prefs.AppSettingsRepository
import java.net.URLEncoder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

object TorBoxResolver {

    private const val TAG = "TorBoxResolver"

    sealed class ResolveResult {
        data class Success(
            val url: String,
            val filename: String?,
            val videoSize: Long?
        ) : ResolveResult()
        data object MissingApiKey : ResolveResult()
        data object NotCached : ResolveResult()
        data object Stale : ResolveResult()
        data object Error : ResolveResult()
    }

    suspend fun resolve(
        infoHash: String,
        filename: String?,
        season: Int?,
        episode: Int?,
        sources: List<String>? = null
    ): ResolveResult {
        val apiKey = AppSettingsRepository.torBoxApiKey.value?.trim()
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "resolve: missing API key")
            return ResolveResult.MissingApiKey
        }

        val magnet = buildMagnetUri(infoHash, filename, sources)
            ?: return ResolveResult.Stale
        val authorization = "Bearer $apiKey"

        return try {
            Log.i("[StreamNova-TorBox-Debug]", "1. DATI STREAM DALL'ADDON (resolve): infoHash=$infoHash, filename=$filename, season=$season, episode=$episode")
            Log.d(TAG, "resolve: createTorrent hash=${infoHash.take(12)}...")
            val createStartMs = System.currentTimeMillis()
            val createResponse = TorBoxApiClient.api.createTorrent(
                authorization = authorization,
                magnet = magnet.toRequestBody("text/plain".toMediaType()),
                addOnlyIfCached = "true".toRequestBody("text/plain".toMediaType()),
                allowZip = "false".toRequestBody("text/plain".toMediaType())
            )
            Log.d(TAG, "resolve: createTorrent done in ${System.currentTimeMillis() - createStartMs}ms code=${createResponse.code()}")
            
            val torrentId = createResponse.body()?.data?.resolvedTorrentId()
            if (torrentId == null) {
                val errorCode = createResponse.code()
                return when (errorCode) {
                    401, 403 -> ResolveResult.Error
                    409 -> ResolveResult.NotCached
                    else -> ResolveResult.Stale
                }
            }

            Log.d(TAG, "resolve: getTorrent id=$torrentId")
            val getTorrentStartMs = System.currentTimeMillis()
            val torrentResponse = TorBoxApiClient.api.getTorrent(
                authorization = authorization,
                id = torrentId,
                bypassCache = true
            )
            Log.d(TAG, "resolve: getTorrent done in ${System.currentTimeMillis() - getTorrentStartMs}ms code=${torrentResponse.code()}")
            if (!torrentResponse.isSuccessful) return ResolveResult.Stale

            val files = torrentResponse.body()?.data?.files.orEmpty()
            Log.i("[StreamNova-TorBox-Debug]", "2. DATI TORBOX (getTorrent id=$torrentId restituisce ${files.size} file):")
            val videoExtensions = listOf(".mp4", ".mkv", ".webm", ".avi", ".mov", ".m4v", ".ts", ".m2ts", ".wmv", ".flv")
            for (f in files) {
                val isVideo = f.mimeType.orEmpty().startsWith("video/", ignoreCase = true) ||
                    videoExtensions.any { f.displayName().endsWith(it, ignoreCase = true) }
                if (isVideo) {
                    Log.i(
                        "[StreamNova-TorBox-Debug]",
                        "   - [VIDEO FILE] file.id=${f.id}, name='${f.name}', shortName='${f.shortName}', absolutePath='${f.absolutePath}', mimeType='${f.mimeType}', size=${f.size}"
                    )
                }
            }

            val file = TorBoxFileSelector.selectFile(files, filename, season, episode)
                ?: run {
                    Log.w("[StreamNova-TorBox-Debug]", "3. FILE SELEZIONATO: NESSUN FILE corrisponde ai criteri per filename='$filename' (S${season}E${episode})")
                    return ResolveResult.Stale
                }
            val fileId = file.id ?: return ResolveResult.Stale

            Log.i(
                "[StreamNova-TorBox-Debug]",
                "3. FILE SELEZIONATO: file.id=$fileId, nome='${file.displayName()}', path='${file.absolutePath ?: file.name}', mimeType='${file.mimeType}', size=${file.size}"
            )

            Log.d(TAG, "resolve: requestDownloadLink torrentId=$torrentId fileId=$fileId")
            val linkStartMs = System.currentTimeMillis()
            val linkResponse = TorBoxApiClient.api.requestDownloadLink(
                authorization = authorization,
                token = apiKey,
                torrentId = torrentId,
                fileId = fileId,
                zipLink = false,
                redirect = false,
                appendName = false
            )
            Log.d(TAG, "resolve: requestDownloadLink done in ${System.currentTimeMillis() - linkStartMs}ms code=${linkResponse.code()}")
            if (!linkResponse.isSuccessful) return ResolveResult.Stale

            val url = linkResponse.body()?.data?.takeIf { it.isNotBlank() }
                ?: return ResolveResult.Stale

            val sanitizedUrl = runCatching {
                val uri = android.net.Uri.parse(url)
                "${uri.scheme}://${uri.host}${uri.path}"
            }.getOrDefault("sanitized-url")
            Log.i("[StreamNova-TorBox-Debug]", "4. URL FINALE: $sanitizedUrl")

            ResolveResult.Success(
                url = url,
                filename = file.displayName().takeIf { it.isNotBlank() },
                videoSize = file.size
            )
        } catch (error: Exception) {
            Log.w(TAG, "resolve: failed with ${error::class.simpleName}: ${error.message}")
            ResolveResult.Error
        }
    }

    private fun buildMagnetUri(infoHash: String, filename: String?, sources: List<String>?): String? {
        val hash = infoHash.takeIf { it.isNotBlank() } ?: return null
        return buildString {
            append("magnet:?xt=urn:btih:")
            append(hash)
            filename?.trim()?.takeIf { it.isNotBlank() }?.let { fn ->
                append("&dn=")
                append(encode(fn))
            }
            sources.orEmpty()
                .mapNotNull { source -> source.trackerUrlOrNull() }
                .distinct()
                .forEach { tracker ->
                    append("&tr=")
                    append(encode(tracker))
                }
        }
    }

    private fun String.trackerUrlOrNull(): String? {
        val value = trim()
        if (value.isBlank() || value.startsWith("dht:", ignoreCase = true)) return null
        return value.removePrefix("tracker:").trim().takeIf { it.isNotBlank() }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8")
}
