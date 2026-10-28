package com.example.data.streaming

import com.example.data.prefs.PlaybackSettings
import com.example.data.prefs.PreferredResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica l'ordinamento deterministico dei risultati TorBox:
 * cache → risoluzione → qualità/metadati → dimensione → criterio stabile.
 */
class TorBoxSourceOrderingTest {

  private fun source(
    serverName: String,
    quality: String = "1080p",
    cache: CacheState = CacheState.Cached,
    codec: String? = null,
    releaseType: String? = null,
    releaseTitle: String = "${serverName}.mkv",
    details: String? = null,
    declaredQuality: String = quality,
    infoHash: String? = null,
    isItalian: Boolean = false,
  ) = StreamSource(
    streamUrl = null,
    quality = quality,
    declaredQuality = declaredQuality,
    serverName = serverName,
    releaseTitle = releaseTitle,
    details = details,
    codec = codec,
    releaseType = releaseType,
    cacheState = cache,
    infoHash = infoHash,
    isItalian = isItalian,
  )

  @Test
  fun cachedResultsComeBeforeNotCachedEvenAtLowerResolution() {
    val cached1080 = source("cached-1080", quality = "1080p", cache = CacheState.Cached)
    val notCached4k = source("notcached-4k", quality = "4K", cache = CacheState.NotCached)

    val ordered = TorBoxSourceOrdering.sort(listOf(notCached4k, cached1080))

    assertEquals("cached-1080", ordered[0].serverName)
    assertEquals("notcached-4k", ordered[1].serverName)
  }

  @Test
  fun unknownCacheStateIsNeverTreatedAsCached() {
    val cached720 = source("cached-720", quality = "720p", cache = CacheState.Cached)
    val unknown4k = source("unknown-4k", quality = "4K", cache = CacheState.Unknown)

    val ordered = TorBoxSourceOrdering.sort(listOf(unknown4k, cached720))

    assertEquals("cached-720", ordered[0].serverName)
    assertEquals("unknown-4k", ordered[1].serverName)
  }

  @Test
  fun higherResolutionComesFirst() {
    val s4k = source("s4k", quality = "4K")
    val s1080 = source("s1080", quality = "1080p")
    val s720 = source("s720", quality = "720p")
    val sAuto = source("sauto", quality = "Auto")

    val ordered = TorBoxSourceOrdering.sort(listOf(s720, sAuto, s4k, s1080))

    assertEquals(listOf("s4k", "s1080", "s720", "sauto"), ordered.map { it.serverName })
  }

  @Test
  fun autoQualityIsRankedBelowKnownResolutions() {
    val auto = source("auto", quality = "Auto")
    val sd = source("sd", quality = "480p")

    val ordered = TorBoxSourceOrdering.sort(listOf(auto, sd))

    assertEquals(listOf("sd", "auto"), ordered.map { it.serverName })
  }

  @Test
  fun sameResolutionPrefersBetterVideoMetadata() {
    val hevcHdr = source(
      serverName = "hevc-hdr",
      quality = "4K",
      codec = "HEVC",
      releaseType = "HDR",
    )
    val h264 = source(
      serverName = "h264",
      quality = "4K",
      codec = "H264",
    )

    val ordered = TorBoxSourceOrdering.sort(listOf(h264, hevcHdr))

    assertEquals("hevc-hdr", ordered[0].serverName)
    assertEquals("h264", ordered[1].serverName)
  }

  @Test
  fun sameResolutionAndMetadataPrefersBiggerFileSize() {
    val small = source(
      serverName = "small",
      quality = "1080p",
      releaseTitle = "Movie.1080p.mkv",
      details = "1.00 GB | 👤 10",
    )
    val big = source(
      serverName = "big",
      quality = "1080p",
      releaseTitle = "Movie.1080p.mkv",
      details = "2.50 GB | 👤 10",
    )

    val ordered = TorBoxSourceOrdering.sort(listOf(small, big))

    assertEquals("big", ordered[0].serverName)
    assertEquals("small", ordered[1].serverName)
  }

  @Test
  fun missingMetadataDoesNotCrashAndKeepsStableOrder() {
    val a = source(serverName = "alpha", quality = "1080p", releaseTitle = "alpha.mkv")
    val b = source(serverName = "beta", quality = "1080p", releaseTitle = "beta.mkv")

    // Input invertito: l'ordine deve restare stabile (criterio finale: nome file).
    val ordered = TorBoxSourceOrdering.sort(listOf(b, a))

    assertEquals(listOf("alpha", "beta"), ordered.map { it.serverName })
  }

  @Test
  fun equalCandidatesKeepDeterministicOrderRegardlessOfInput() {
    val x = source(serverName = "x", quality = "1080p", releaseTitle = "x.mkv", infoHash = "aa")
    val y = source(serverName = "y", quality = "1080p", releaseTitle = "y.mkv", infoHash = "bb")

    val first = TorBoxSourceOrdering.sort(listOf(x, y)).map { it.serverName }
    val second = TorBoxSourceOrdering.sort(listOf(y, x)).map { it.serverName }

    assertEquals(first, second)
    assertEquals(listOf("x", "y"), first)
  }

  @Test
  fun parseSizeBytesHandlesUnitsAndMissingValues() {
    assertEquals(2L * 1024 * 1024 * 1024, TorBoxSourceOrdering.parseSizeBytes("2 GB"))
    assertEquals((700L * 1024 * 1024), TorBoxSourceOrdering.parseSizeBytes("700 MB"))
    assertEquals(null, TorBoxSourceOrdering.parseSizeBytes("nessuna dimensione"))
    assertEquals(null, TorBoxSourceOrdering.parseSizeBytes(null))
  }

  @Test
  fun cacheStateDerivesFromLegacyIsCachedFlag() {
    assertEquals(CacheState.Cached, StreamSource(serverName = "a", isCached = true).cacheState)
    assertEquals(CacheState.Unknown, StreamSource(serverName = "b").cacheState)
  }

  @Test
  fun audioLanguagesAreDetectedWithoutInventingMissingOnes() {
    val itaEng = source(
      serverName = "multi",
      releaseTitle = "Movie.ITA-ENG.1080p.mkv",
    )
    assertTrue(itaEng.detectedAudioLanguages.containsAll(listOf("Italiano", "English")))

    val noAudio = source(serverName = "plain", releaseTitle = "Movie.1080p.mkv")
    assertEquals(emptyList<String>(), noAudio.detectedAudioLanguages)
  }

  @Test
  fun italianAudioPrefersItalianStreamsWhenEnabled() {
    val italian1080 = source(
      serverName = "ita-1080",
      quality = "1080p",
      isItalian = true
    )
    val english1080 = source(
      serverName = "eng-1080",
      quality = "1080p",
      isItalian = false
    )
    val settings = PlaybackSettings(prioritizeItalianAudio = true)

    val ordered = TorBoxSourceOrdering.sort(listOf(english1080, italian1080), settings)

    assertEquals("ita-1080", ordered[0].serverName)
    assertEquals("eng-1080", ordered[1].serverName)
  }

  @Test
  fun italianPriorityDoesNotOverrideCache() {
    val englishCached1080 = source(
      serverName = "eng-cached",
      quality = "1080p",
      cache = CacheState.Cached,
      isItalian = false
    )
    val italianNotCached1080 = source(
      serverName = "ita-uncached",
      quality = "1080p",
      cache = CacheState.Unknown,
      isItalian = true
    )
    val settings = PlaybackSettings(prioritizeItalianAudio = true)

    val ordered = TorBoxSourceOrdering.sort(listOf(italianNotCached1080, englishCached1080), settings)

    assertEquals("eng-cached", ordered[0].serverName)
    assertEquals("ita-uncached", ordered[1].serverName)
  }

  @Test
  fun resolution1080pHasPriorityOver4KWhenPreferred() {
    val s4k = source("s4k", quality = "4K")
    val s1080 = source("s1080", quality = "1080p")
    val settings = PlaybackSettings(preferredResolution = PreferredResolution.FULL_HD_1080P)

    val ordered = TorBoxSourceOrdering.sort(listOf(s4k, s1080), settings)

    assertEquals("s1080", ordered[0].serverName)
    assertEquals("s4k", ordered[1].serverName)
  }

  @Test
  fun resolution4KHasPriorityWhenPreferred() {
    val s4k = source("s4k", quality = "4K")
    val s1080 = source("s1080", quality = "1080p")
    val settings = PlaybackSettings(preferredResolution = PreferredResolution.UHD_4K)

    val ordered = TorBoxSourceOrdering.sort(listOf(s1080, s4k), settings)

    assertEquals("s4k", ordered[0].serverName)
    assertEquals("s1080", ordered[1].serverName)
  }
  @Test
  fun resolution720pHasPriorityOver1080pWhenPreferred() {
    val s1080 = source("s1080", quality = "1080p")
    val s720 = source("s720", quality = "720p")
    val settings = PlaybackSettings(preferredResolution = PreferredResolution.HD_720P)

    val ordered = TorBoxSourceOrdering.sort(listOf(s1080, s720), settings)

    assertEquals("s720", ordered[0].serverName)
    assertEquals("s1080", ordered[1].serverName)
  }

  @Test
  fun defaultResolutionOrderMaintainsClassicPriority() {
    val s4k = source("s4k", quality = "4K")
    val s1080 = source("s1080", quality = "1080p")
    val s720 = source("s720", quality = "720p")
    val settings = PlaybackSettings(preferredResolution = PreferredResolution.AUTO)

    val ordered = TorBoxSourceOrdering.sort(listOf(s720, s1080, s4k), settings)

    assertEquals(listOf("s4k", "s1080", "s720"), ordered.map { it.serverName })
  }
}
