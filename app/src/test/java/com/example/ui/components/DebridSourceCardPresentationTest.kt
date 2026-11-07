package com.example.ui.components

import com.example.R
import com.example.data.streaming.StreamSource
import com.example.domain.model.Subtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica il mapping di presentazione della card Debrid: ogni campo mostrato deve
 * derivare da un metadato reale della sorgente e i campi assenti non devono produrre
 * valori inventati.
 */
class DebridSourceCardPresentationTest {

  private fun source(
    releaseTitle: String? = null,
    details: String? = null,
    instantTag: String? = null,
    serverName: String = "IlCorsaroViola 🧲 2160p TB Instant",
    addonName: String? = "IlCorsaroViola",
    quality: String = "4K",
    declaredQuality: String = quality,
    verifiedHeight: Int? = null,
    codec: String? = null,
    releaseType: String? = null,
    isItalian: Boolean = false,
    infoHash: String? = null,
    addonId: String? = null,
    addonLogo: String? = null,
    addonInstallId: String? = null,
    subtitles: List<Subtitle> = emptyList(),
  ) = StreamSource(
    streamUrl = null,
    quality = quality,
    declaredQuality = declaredQuality,
    serverName = serverName,
    verifiedHeight = verifiedHeight,
    isItalian = isItalian,
    addonName = addonName,
    addonId = addonId,
    addonLogo = addonLogo,
    addonInstallId = addonInstallId,
    instantTag = instantTag,
    releaseTitle = releaseTitle,
    details = details,
    infoHash = infoHash,
    codec = codec,
    releaseType = releaseType,
    subtitles = subtitles,
  )

  private val referenceSource = source(
    releaseTitle = "Toy.Story.5.2026.2160p.iT.WEB-DL.DV.HDR10+.MULTi[Ben The Men].mkv",
    details = "📹 HEVC | 🔊 DDP5.1 | 👤 382 💾 23.80 GB",
    instantTag = "2160p TB Instant",
    releaseType = "WEB-DL",
    codec = "HEVC",
    isItalian = true,
    infoHash = "abc123",
    subtitles = listOf(Subtitle(id = "1", url = "u", lang = "it", addonName = "x")),
  )

  @Test
  fun headlineUsesInstantTagThenResolutionFallback() {
    assertEquals("2160p TB Instant", DebridSourceCardPresentation.headline(referenceSource))
    assertEquals(
      "1080p",
      DebridSourceCardPresentation.headline(source(quality = "1080p", instantTag = null))
    )
  }

  @Test
  fun fileNameIsTheRealReleaseTitle() {
    assertEquals(
      "Toy.Story.5.2026.2160p.iT.WEB-DL.DV.HDR10+.MULTi[Ben The Men].mkv",
      DebridSourceCardPresentation.fileName(referenceSource)
    )
  }

  @Test
  fun sizeLabelFormatsRealBytesAndSupportsDecimals() {
    // 23.80 GB == 23.80 * 1024^3 byte (dato dichiarato in details).
    assertEquals("23.80 GB", DebridSourceCardPresentation.sizeLabel(referenceSource, decimals = 2))
    assertEquals("23.8 GB", DebridSourceCardPresentation.sizeLabel(referenceSource, decimals = 1))
  }

  @Test
  fun sizeLabelIsNullWhenNoSizeMetadataExists() {
    assertNull(DebridSourceCardPresentation.sizeLabel(source(details = null, releaseTitle = null)))
  }

  @Test
  fun peersAndAudioFormatComeFromDetails() {
    assertEquals("382", DebridSourceCardPresentation.peersLabel(referenceSource))
    assertEquals("DDP5.1", DebridSourceCardPresentation.audioFormatLabel(referenceSource))
    assertNull(DebridSourceCardPresentation.peersLabel(source(details = null)))
    assertNull(DebridSourceCardPresentation.audioFormatLabel(source(details = "16.4 GB")))
  }

  @Test
  fun audioFormatDoesNotSwallowLanguagesOrSizeFromDetails() {
    val icv = source(details = "🌐 IT · EN | 🔊 2.0 🇬🇧 ITALIAN / ENGLISH 📦 20.29 GB | 👤 44")
    assertEquals("2.0", DebridSourceCardPresentation.audioFormatLabel(icv))
    assertEquals("44", DebridSourceCardPresentation.peersLabel(icv))
  }

  @Test
  fun audioLanguagesUseRealTokensAndItalianFlag() {
    assertEquals(listOf("IT"), DebridSourceCardPresentation.audioLanguageCodes(referenceSource))

    val itaEng = source(releaseTitle = "Movie.1080p.ITA-ENG.mkv", isItalian = true)
    assertEquals(
      listOf("IT", "EN"),
      DebridSourceCardPresentation.audioLanguageCodes(itaEng)
    )

    val unknown = source(releaseTitle = "Movie.1080p.mkv", isItalian = false)
    assertTrue(DebridSourceCardPresentation.audioLanguageCodes(unknown).isEmpty())
  }

  @Test
  fun subtitleLanguagesUseDeclaredSubtitlesOnly() {
    assertEquals(
      listOf("IT"),
      DebridSourceCardPresentation.subtitleLanguageCodes(referenceSource)
    )
    assertTrue(DebridSourceCardPresentation.subtitleLanguageCodes(source()).isEmpty())
  }

  @Test
  fun technicalBadgesReflectTheReferenceRelease() {
    val meta = DebridSourceCardPresentation
    assertTrue(meta.hasWebDl(referenceSource))
    assertTrue(meta.isUltraHd(referenceSource))
    assertTrue(meta.isHevc(referenceSource))
    assertTrue(meta.hasDolbyDigitalPlus(referenceSource))
    assertEquals(
      listOf(HdrBadgeLabel.DOLBY_VISION, HdrBadgeLabel.HDR10_PLUS),
      meta.hdrBadges(referenceSource)
    )
  }

  @Test
  fun dvTokenIsNotConfusedWithDvd() {
    val dvd = source(releaseTitle = "Movie.2001.DVDRip.XviD.avi", releaseType = "DVD")
    assertTrue(DebridSourceCardPresentation.hdrBadges(dvd).isEmpty())
    assertTrue(!DebridSourceCardPresentation.hasWebDl(dvd))
  }

  @Test
  fun av1SourceDoesNotShowHevcBadge() {
    val av1 = source(releaseTitle = "Movie.1080p.AV1.mkv", codec = "AV1/VP9", quality = "1080p")
    assertTrue(!DebridSourceCardPresentation.isHevc(av1))
  }

  @Test
  fun providerIsTorBoxOnlyWhenDataIdentifiesIt() {
    assertEquals(DebridProvider.TORBOX, DebridSourceCardPresentation.providerOf(referenceSource))
    assertEquals(
      DebridProvider.TORBOX,
      DebridSourceCardPresentation.providerOf(source(infoHash = null, instantTag = "1080p TB Instant"))
    )
    assertNull(
      DebridSourceCardPresentation.providerOf(
        source(
          instantTag = "",
          addonName = "Addon",
          serverName = "Addon",
          infoHash = null
        )
      )
    )
  }

  // ── Identità dell'ADDON (separata dal provider Debrid) ──────────────────────

  @Test
  fun corsaroViolaSourceKeepsItsIdentityAndUsesItsOwnLogo() {
    val corsaro = source(
      addonName = "IlCorsaroViola 🧲🇮🇹",
      addonId = "community.ilcorsaroviola.ita",
      addonInstallId = "https://icv.stremio-italia.eu/config-base64",
      infoHash = "abc123",
    )

    val identity = DebridSourceCardPresentation.addonIdentityOf(corsaro)

    assertEquals("IlCorsaroViola 🧲🇮🇹", identity.name)
    assertEquals(R.drawable.logo_addon_ilcorsaroviola, identity.logoResId)
    assertNull(identity.logoUrl)
    assertTrue(identity.hasLogo)
    // Il servizio Debrid resta un dato separato.
    assertEquals(DebridProvider.TORBOX, DebridSourceCardPresentation.providerOf(corsaro))

    // Anche un'installazione già persistita (senza manifest id/logo) mantiene
    // l'identità tramite il nome esatto dell'addon.
    val legacyInstall = source(addonName = "IlCorsaroViola 🧲🇮🇹", infoHash = "abc123")
    assertEquals(
      R.drawable.logo_addon_ilcorsaroviola,
      DebridSourceCardPresentation.addonIdentityOf(legacyInstall).logoResId
    )
  }

  @Test
  fun torrentioSourceUsesTorrentioLogoForEveryConfiguration() {
    val global = source(
      addonName = "Torrentio",
      addonId = "com.stremio.torrentio.addon",
      addonInstallId = "https://torrentio.strem.fun/language=italian",
    )
    val italian = source(
      addonName = "Torrentio 🇮🇹",
      addonId = "com.stremio.torrentio.addon",
      addonInstallId = "https://torrentioita.stremio-italia.eu/config-base64",
    )

    assertEquals(
      R.drawable.logo_addon_torrentio,
      DebridSourceCardPresentation.addonIdentityOf(global).logoResId
    )
    assertEquals(
      R.drawable.logo_addon_torrentio,
      DebridSourceCardPresentation.addonIdentityOf(italian).logoResId
    )
    // Installazione legacy: nessun addonId/logo persistito, nome esatto come identità.
    assertEquals(
      R.drawable.logo_addon_torrentio,
      DebridSourceCardPresentation.addonIdentityOf(
        source(addonName = "Torrentio 🇮🇹", infoHash = "abc123")
      ).logoResId
    )
  }

  @Test
  fun otherAddonKeepsItsIdentityAndManifestLogo() {
    val comet = source(
      addonName = "Comet | ElfHosted",
      addonId = "com.comet.addon",
      addonInstallId = "https://comet.elfhosted.com/config",
      addonLogo = "https://comet.elfhosted.com/assets/logo.png",
      infoHash = "abc123",
    )

    val identity = DebridSourceCardPresentation.addonIdentityOf(comet)

    assertEquals("Comet | ElfHosted", identity.name)
    assertEquals("https://comet.elfhosted.com/assets/logo.png", identity.logoUrl)
    assertNull(identity.logoResId)
    assertTrue(identity.hasLogo)
  }

  @Test
  fun addonWithoutIdentifiableLogoFallsBackWithoutWrongAttribution() {
    val unknown = source(
      addonName = "Mystery Addon",
      addonId = "org.example.mystery",
      addonInstallId = "https://mystery.example/config",
      infoHash = "abc123",
    )

    val identity = DebridSourceCardPresentation.addonIdentityOf(unknown)

    assertEquals("Mystery Addon", identity.name)
    assertNull(identity.logoUrl)
    assertNull(identity.logoResId)
    assertFalse(identity.hasLogo)
    assertEquals("MA", DebridSourceCardPresentation.initials(identity.name))
    // Il logo TorBox non viene mai usato come logo dell'addon.
    assertEquals(DebridProvider.TORBOX, DebridSourceCardPresentation.providerOf(unknown))
  }

  @Test
  fun infoHashAloneNeverSelectsTheTorBoxLogoAsAddonLogo() {
    val torrentWithoutAddon = source(
      addonName = null,
      serverName = "TorBox 🧲 2160p TB Instant",
      infoHash = "abc123",
    )

    val identity = DebridSourceCardPresentation.addonIdentityOf(torrentWithoutAddon)

    assertNull(identity.logoUrl)
    assertNull(identity.logoResId)
    assertFalse(identity.hasLogo)
    // La sorgente è torrent e il servizio Debrid è TorBox, ma l'addon resta ignoto.
    assertEquals(DebridProvider.TORBOX, DebridSourceCardPresentation.providerOf(torrentWithoutAddon))
  }

  @Test
  fun manifestLogoHasPriorityAndBundledAssetRemainsAsFallback() {
    val torrentio = source(
      addonName = "Torrentio",
      addonId = "com.stremio.torrentio.addon",
      addonInstallId = "https://torrentio.strem.fun/config",
      addonLogo = "https://torrentio.strem.fun/images/logo_v1.png",
    )

    val identity = DebridSourceCardPresentation.addonIdentityOf(torrentio)

    assertEquals("https://torrentio.strem.fun/images/logo_v1.png", identity.logoUrl)
    assertEquals(R.drawable.logo_addon_torrentio, identity.logoResId)
  }

  @Test
  fun relativeManifestLogoIsResolvedAgainstTheAddonBaseUrl() {
    val addon = source(
      addonName = "Custom Addon",
      addonLogo = "/assets/logo.png",
      addonInstallId = "https://addon.example/config/path",
    )

    assertEquals(
      "https://addon.example/assets/logo.png",
      DebridSourceCardPresentation.addonIdentityOf(addon).logoUrl
    )
  }

  @Test
  fun addonIdentityFallsBackToServerNameOnlyForTheText() {
    val noAddonName = source(
      addonName = null,
      serverName = "Some Source 🧲 1080p TB Instant",
      infoHash = null,
    )

    val identity = DebridSourceCardPresentation.addonIdentityOf(noAddonName)

    assertEquals("Some Source", identity.name)
    assertFalse(identity.hasLogo)
  }

  @Test
  fun initialsAreDeterministicAndSafe() {
    assertEquals("IC", DebridSourceCardPresentation.initials("IlCorsaroViola"))
    assertEquals("TB", DebridSourceCardPresentation.initials("TorBox"))
    assertEquals("?", DebridSourceCardPresentation.initials("---"))
  }

  @Test
  fun languageCodeMappingKeepsUnknownLabels() {
    assertEquals("IT", DebridSourceCardPresentation.languageCode("Italiano"))
    assertEquals("EN", DebridSourceCardPresentation.languageCode("English"))
    assertEquals("XX", DebridSourceCardPresentation.languageCode("xx"))
  }
}
