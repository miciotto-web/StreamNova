package com.example.data.streaming

import com.example.data.prefs.PlaybackSettings
import com.example.data.prefs.PreferredResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contratto verificabile della Smart Source Selection.
 *
 * Tutti i casi usano sorgenti costruite a mano: il motore è puro e non deve
 * dipendere da rete, provider o Android.
 */
class SmartSourceSelectionTest {

  private fun source(
    title: String,
    quality: String = "1080p",
    declared: String = quality,
    isItalian: Boolean = false,
    cached: CacheState = CacheState.Unknown,
    availability: SourceAvailability = SourceAvailability.Unknown,
    codec: String? = null,
    details: String? = null,
    providerId: String? = null,
    addonInstallId: String? = null,
    url: String? = "https://example.test/$title"
  ): StreamSource = StreamSource(
    streamUrl = url,
    quality = quality,
    declaredQuality = declared,
    serverName = title,
    isItalian = isItalian,
    cacheState = cached,
    availability = availability,
    codec = codec,
    details = details,
    releaseTitle = title,
    sourceProviderId = providerId,
    addonInstallId = addonInstallId
  )

  @Test
  fun `verified source beats unverified source of same quality`() {
    val verified = source("verified", availability = SourceAvailability.Verified)
    val unknown = source("unknown")
    val ranked = SmartSourceSelector.rank(listOf(unknown, verified))
    assertEquals("verified", ranked.first().source.serverName)
    assertTrue(ranked.first().total > ranked.last().total)
  }

  @Test
  fun `unavailable source is never auto selected and goes last`() {
    val broken = source("broken", availability = SourceAvailability.Unavailable)
    val ok = source("ok")
    val best = SmartSourceSelector.best(listOf(broken, ok))
    assertNotNull(best)
    assertEquals("ok", best!!.source.serverName)
    val ranked = SmartSourceSelector.rank(listOf(ok, broken))
    assertEquals("broken", ranked.last().source.serverName)
  }

  @Test
  fun `cached source wins at equal quality`() {
    val cached = source("cached", cached = CacheState.Cached)
    val notCached = source("not-cached", cached = CacheState.NotCached)
    assertEquals(
      "cached",
      SmartSourceSelector.best(listOf(notCached, cached))!!.source.serverName
    )
  }

  @Test
  fun `unknown cache state is not treated as cached`() {
    val cached = SmartSourceSelector.score(source("cached", cached = CacheState.Cached), SmartSourcePolicy())
    val unknown = SmartSourceSelector.score(source("unknown", cached = CacheState.Unknown), SmartSourcePolicy())
    assertTrue(cached.total > unknown.total)
  }

  @Test
  fun `preferred 1080p outranks 4K`() {
    val uhd = source("uhd", quality = "4K", declared = "4K")
    val fhd = source("fhd", quality = "1080p", declared = "1080p")
    val policy = SmartSourcePolicy(preferredResolution = PreferredResolution.FULL_HD_1080P)
    assertEquals("fhd", SmartSourceSelector.best(listOf(uhd, fhd), policy)!!.source.serverName)
  }

  @Test
  fun `auto preference ranks 4K first`() {
    val uhd = source("uhd", quality = "4K", declared = "4K")
    val fhd = source("fhd", quality = "1080p", declared = "1080p")
    val policy = SmartSourcePolicy(preferredResolution = PreferredResolution.AUTO)
    assertEquals("uhd", SmartSourceSelector.best(listOf(fhd, uhd), policy)!!.source.serverName)
  }

  @Test
  fun `italian audio preference changes the winner`() {
    // Stessa preferenza 1080p: la sorgente italiana 720p batte l'inglese 1080p
    // SOLO quando "privilegia italiano" è attivo.
    val english1080 = source("eng-1080", quality = "1080p", declared = "1080p")
    val italian720 = source("ita-720", quality = "720p", declared = "720p", isItalian = true)
    val withItalian = SmartSourcePolicy(
      preferredResolution = PreferredResolution.FULL_HD_1080P,
      prioritizeItalianAudio = true
    )
    val withoutItalian = withItalian.copy(prioritizeItalianAudio = false)
    assertEquals(
      "ita-720",
      SmartSourceSelector.best(listOf(english1080, italian720), withItalian)!!.source.serverName
    )
    assertEquals(
      "eng-1080",
      SmartSourceSelector.best(listOf(english1080, italian720), withoutItalian)!!.source.serverName
    )
  }

  @Test
  fun `trusted addon wins against unreachable addon`() {
    val healthy = source("healthy", addonInstallId = "addon-a")
    val dead = source("dead", addonInstallId = "addon-b")
    val policy = SmartSourcePolicy(
      addonTrust = mapOf("addon-a" to AddonTrust.HEALTHY, "addon-b" to AddonTrust.UNREACHABLE)
    )
    assertEquals("healthy", SmartSourceSelector.best(listOf(dead, healthy), policy)!!.source.serverName)
  }

  @Test
  fun `preferred provider is favored`() {
    val preferred = source("preferred", providerId = "provider-a")
    val other = source("other", providerId = "provider-b")
    val policy = SmartSourcePolicy(preferredProviderId = "provider-a")
    assertEquals("preferred", SmartSourceSelector.best(listOf(other, preferred), policy)!!.source.serverName)
  }

  @Test
  fun `suspicious tiny 4K loses against a sane 1080p file`() {
    val fake4k = source("fake-4k", quality = "4K", declared = "4K", details = "900 MB")
    val sane1080 = source("sane-1080", quality = "1080p", declared = "1080p", details = "4.2 GB")
    val policy = SmartSourcePolicy(preferredResolution = PreferredResolution.FULL_HD_1080P)
    assertEquals("sane-1080", SmartSourceSelector.best(listOf(fake4k, sane1080), policy)!!.source.serverName)
  }

  @Test
  fun `equal scores keep input order`() {
    val a = source("a")
    val b = source("b")
    val ranked = SmartSourceSelector.rank(listOf(a, b))
    assertEquals(listOf("a", "b"), ranked.map { it.source.serverName })
  }

  @Test
  fun `best returns null for empty list`() {
    assertNull(SmartSourceSelector.best(emptyList()))
  }

  @Test
  fun `unavailable only list has no best but keeps ranking`() {
    val only = source("only", availability = SourceAvailability.Unavailable)
    assertNull(SmartSourceSelector.best(listOf(only)))
    assertEquals(1, SmartSourceSelector.rank(listOf(only)).size)
  }

  @Test
  fun `explain lists the strongest reasons`() {
    val verified = source(
      "best",
      quality = "1080p",
      cached = CacheState.Cached,
      availability = SourceAvailability.Verified,
      codec = "HEVC"
    )
    val score = SmartSourceSelector.score(verified, SmartSourcePolicy())
    val text = SmartSourceSelector.explain(score)
    assertTrue(text.contains("score ${score.total}"))
    assertTrue(text.contains("Riproducibilità verificata"))
    assertTrue(text.contains("File in cache Debrid"))
    assertFalse(score.reasons.isEmpty())
  }

  @Test
  fun `policy from settings mirrors playback preferences`() {
    val settings = PlaybackSettings(
      preferredResolution = PreferredResolution.HD_720P,
      prioritizeItalianAudio = false
    )
    val policy = SmartSourcePolicy.fromSettings(settings, preferredProviderId = "p1")
    assertEquals(PreferredResolution.HD_720P, policy.preferredResolution)
    assertFalse(policy.prioritizeItalianAudio)
    assertEquals("p1", policy.preferredProviderId)
  }

  @Test
  fun `codec and hdr metadata add transparent points`() {
    val hevcHdr = source(
      "hevc-hdr",
      codec = "HEVC",
      details = "Dolby Vision 12.4 GB"
    )
    val plain = source("plain")
    val hevcScore = SmartSourceSelector.score(hevcHdr, SmartSourcePolicy())
    val plainScore = SmartSourceSelector.score(plain, SmartSourcePolicy())
    assertTrue(hevcScore.total > plainScore.total)
    assertTrue(hevcScore.reasons.any { it.label.startsWith("HDR dichiarato") })
  }
}
