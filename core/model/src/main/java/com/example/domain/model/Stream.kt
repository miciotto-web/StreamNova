package com.example.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class Stream(
    val name: String?,
    val title: String?,
    val description: String?,
    val url: String?,
    val ytId: String?,
    val infoHash: String?,
    val fileIdx: Int?,
    val externalUrl: String?,
    val behaviorHints: StreamBehaviorHints?,
    val addonName: String,
    val addonLogo: String?,
    val sources: List<String>? = null,
    val quality: String? = null,
    val qualityValue: Int = -1,
    val clientResolve: StreamClientResolve? = null,
    val debridCacheStatus: StreamDebridCacheStatus? = null,
    val badges: List<StreamBadge> = emptyList(),
    val subtitles: List<Subtitle> = emptyList()
) {
    fun getStreamUrl(): String? =
        listOfNotNull(url, externalUrl)
            .firstOrNull { !it.isMagnetLink() && !it.isTorrentUrl() }

    fun torrentMagnetUri(): String? =
        listOfNotNull(url, externalUrl)
            .firstOrNull { it.isMagnetLink() }

    fun isTorrent(): Boolean =
        !isDirectDebrid() &&
            getStreamUrl().isNullOrBlank() &&
            (!infoHash.isNullOrBlank() || !torrentMagnetUri().isNullOrBlank() || hasTorrentUrl())

    fun needsLocalDebridResolve(): Boolean =
        isTorrent() && getStreamUrl().isNullOrBlank()

    fun getEffectiveInfoHash(): String? =
        infoHash?.takeIf { it.isNotBlank() }
            ?: clientResolve?.infoHash?.takeIf { it.isNotBlank() }
            ?: url?.let { extractInfoHashFromTorrentUrl(it) ?: extractInfoHashFromMagnetLink(it) }
            ?: externalUrl?.let { extractInfoHashFromTorrentUrl(it) ?: extractInfoHashFromMagnetLink(it) }

    fun getEffectiveFileIdx(): Int? =
        fileIdx ?: clientResolve?.fileIdx
            ?: url?.let { extractFileIdxFromTorrentUrl(it) } ?: externalUrl?.let { extractFileIdxFromTorrentUrl(it) }

    private fun String.isTorrentUrl(): Boolean =
        this.trimStart().startsWith("torrent:", ignoreCase = true)

    private fun hasTorrentUrl(): Boolean =
        url?.isTorrentUrl() == true || externalUrl?.isTorrentUrl() == true

    private fun extractInfoHashFromTorrentUrl(url: String): String? {
        if (!url.startsWith("torrent:", ignoreCase = true)) return null
        val clean = url.substringAfter("torrent://").substringAfter("torrent:")
            .substringBefore('?')
            .trimEnd('/')
        val hash = clean.substringBefore('/')
        return hash.takeIf { it.length == 40 || it.length == 32 }
    }

    private fun extractInfoHashFromMagnetLink(url: String): String? {
        if (!url.startsWith("magnet:", ignoreCase = true)) return null
        val btih = url.substringAfter("urn:btih:", "")
        if (btih.isBlank()) return null
        val hash = btih.substringBefore('&').substringBefore('?')
        return hash.takeIf { it.length == 40 || it.length == 32 }
    }

    private fun extractFileIdxFromTorrentUrl(url: String): Int? {
        if (!url.startsWith("torrent:", ignoreCase = true)) return null
        val clean = url.substringAfter("torrent://").substringAfter("torrent:")
            .substringBefore('?')
            .trimEnd('/')
        val idxStr = clean.substringAfter('/', "").substringBefore('/')
        return idxStr.toIntOrNull()
    }

    fun isDirectDebrid(): Boolean {
        val resolve = clientResolve ?: return false
        return resolve.type.equals("debrid", ignoreCase = true) &&
            resolve.isCached == true
    }

    fun isYouTube(): Boolean = ytId != null

    fun youTubeIdToResolve(): String? =
        ytId?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.takeIf { getStreamUrl() == null && !isTorrent() && !isDirectDebrid() }

    fun isExternal(): Boolean = externalUrl != null && url == null && !externalUrl.isMagnetLink()

    fun getDisplayNameOrNull(): String? = name ?: title ?: description

    fun getDisplayName(): String = getDisplayNameOrNull() ?: "Unknown Stream"

    fun getDisplayDescription(): String? = description ?: title

    fun stableKey(occurrence: Int = 0): String = buildString {
        append(addonName)
        append('\u0000')
        append(url ?: infoHash ?: clientResolve?.infoHash ?: ytId ?: externalUrl ?: "")
        append('\u0000')
        append(getEffectiveFileIdx() ?: "")
        append('\u0000')
        append(name ?: "")
        append('\u0000')
        append(title ?: "")
        append('\u0000')
        append(description ?: "")
        append('\u0000')
        append(quality ?: "")
        append('\u0000')
        append(sources.orEmpty().joinToString("|"))
        append('\u0000')
        append(occurrence)
    }
}

@Immutable
data class StreamBadge(
    val name: String,
    val imageURL: String = "",
    val tagColor: String = "",
    val tagStyle: String = "",
    val textColor: String = "",
    val borderColor: String = ""
)

@Immutable
data class StreamDebridCacheStatus(
    val providerId: String,
    val providerName: String,
    val state: StreamDebridCacheState,
    val cachedName: String? = null,
    val cachedSize: Long? = null
)

enum class StreamDebridCacheState {
    CHECKING,
    CACHED,
    NOT_CACHED,
    UNKNOWN
}

@Immutable
data class StreamBehaviorHints(
    val notWebReady: Boolean?,
    val bingeGroup: String?,
    val countryWhitelist: List<String>?,
    val proxyHeaders: ProxyHeaders?,
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val filename: String? = null
)

@Immutable
data class StreamClientResolve(
    val type: String?,
    val infoHash: String?,
    val fileIdx: Int?,
    val magnetUri: String?,
    val sources: List<String>?,
    val torrentName: String?,
    val filename: String?,
    val mediaType: String?,
    val mediaId: String?,
    val mediaOnlyId: String?,
    val title: String?,
    val season: Int?,
    val episode: Int?,
    val service: String?,
    val serviceIndex: Int?,
    val serviceExtension: String?,
    val isCached: Boolean?,
    val stream: StreamClientResolveStream? = null
)

@Immutable
data class StreamClientResolveStream(
    val raw: StreamClientResolveRaw?
)

@Immutable
data class StreamClientResolveRaw(
    val torrentName: String?,
    val filename: String?,
    val size: Long?,
    val folderSize: Long?,
    val tracker: String?,
    val indexer: String?,
    val network: String?,
    val parsed: StreamClientResolveParsed?
)

@Immutable
data class StreamClientResolveParsed(
    val rawTitle: String?,
    val parsedTitle: String?,
    val year: Int?,
    val resolution: String?,
    val seasons: List<Int>?,
    val episodes: List<Int>?,
    val quality: String?,
    val hdr: List<String>?,
    val codec: String?,
    val audio: List<String>?,
    val channels: List<String>?,
    val languages: List<String>?,
    val group: String?,
    val network: String?,
    val edition: String?,
    val duration: Long?,
    val bitDepth: String?,
    val extended: Boolean?,
    val theatrical: Boolean?,
    val remastered: Boolean?,
    val unrated: Boolean?
)

@Immutable
data class ProxyHeaders(
    val request: Map<String, String>?,
    val response: Map<String, String>?
)

@Immutable
data class AddonStreams(
    val addonName: String,
    val addonLogo: String?,
    val streams: List<Stream>
)

private fun String?.isMagnetLink(): Boolean =
    this?.trimStart()?.startsWith("magnet:", ignoreCase = true) == true
