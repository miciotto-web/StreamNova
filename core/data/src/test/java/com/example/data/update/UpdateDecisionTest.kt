package com.example.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Selezione dell'APK di una release e decisione del controllo aggiornamenti:
 * funzioni pure verificate senza rete e senza Android.
 *
 * La composizione usata dai test è quella reale di `UpdateRepository`:
 * `selectApkAsset` sceglie l'asset per le ABI del dispositivo, `evaluateUpdate`
 * decide sull'asset già scelto.
 *
 * Include il caso storico della release con tag `v1.0.0` e asset `V0.1.0`,
 * che NON deve essere proposta come aggiornamento.
 */
class UpdateDecisionTest {

  private val installed = AppVersion(0, 1, 1)

  /** ABI del dispositivo, nell'ordine di preferenza dichiarato dal sistema. */
  private val arm64Device = listOf("arm64-v8a", "armeabi-v7a")
  private val arm32Device = listOf("armeabi-v7a")
  private val intel64Device = listOf("x86_64", "x86")
  private val intel32Device = listOf("x86")

  /** Dispositivo la cui ABI non compare fra gli split pubblicati. */
  private val unlistedAbiDevice = listOf("riscv64")

  /** Nomi previsti per gli asset della prossima release (1.0.1). */
  private val plannedAssetNames = listOf(
    "StreamNova-1.0.1-arm64-v8a.apk",
    "StreamNova-1.0.1-armeabi-v7a.apk",
    "StreamNova-1.0.1-x86.apk",
    "StreamNova-1.0.1-x86_64.apk"
  )

  /** Nomi prodotti oggi da AGP con gli ABI split (release 1.0.0 pubblicata). */
  private val agpAssetNames = listOf(
    "StreamNova-arm64-v8a-release.apk",
    "StreamNova-armeabi-v7a-release.apk",
    "StreamNova-x86-release.apk",
    "StreamNova-x86_64-release.apk"
  )

  private fun release(
    tag: String,
    assets: List<ReleaseAsset>,
    body: String? = null
  ) = LatestRelease(
    tagName = tag,
    version = AppVersion.parse(tag)!!,
    releaseUrl = "https://github.com/miciotto-web/StreamNova/releases/tag/$tag",
    assets = assets,
    body = body
  )

  private fun apk(name: String, size: Long? = null, digest: String? = null) = ReleaseAsset(
    name = name,
    downloadUrl = "https://github.com/miciotto-web/StreamNova/releases/download/x/$name",
    size = size,
    digest = digest,
    version = AppVersion.fromAssetName(name)
  )

  private fun releaseOf(tag: String, assetNames: List<String>, body: String? = null) =
    release(tag, assetNames.map { apk(it) }, body)

  /** Composizione reale del controllo: selezione per ABI e poi decisione. */
  private fun check(
    release: LatestRelease,
    supportedAbis: List<String>,
    installedVersion: AppVersion = installed
  ): UpdateCheckState =
    evaluateUpdate(installedVersion, release, selectApkAsset(release.assets, supportedAbis))

  /**
   * Dispositivo come lo dichiara il sistema: l'elenco completo delle ABI
   * eseguibili e le due metà per larghezza di parola. `is64BitProcess` è la
   * larghezza di parola del processo dell'app installata (`Process.is64Bit()`).
   */
  private data class Device(
    val abis: List<String>,
    val abis32: List<String>,
    val abis64: List<String>
  ) {
    fun preferenceOrder(is64BitProcess: Boolean): List<String> =
      abiPreferenceOrder(is64BitProcess, abis, abis32, abis64)

    fun selectedAsset(release: LatestRelease, is64BitProcess: Boolean): ReleaseAsset? =
      selectApkAsset(release.assets, preferenceOrder(is64BitProcess))
  }

  /** Fire TV / box ARM a 64 bit: `arm64-v8a` è la prima ABI dichiarata. */
  private val arm64CapableArm = Device(
    abis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
    abis32 = listOf("armeabi-v7a", "armeabi"),
    abis64 = listOf("arm64-v8a")
  )

  /** Fire TV / box ARM che esegue solo codice a 32 bit. */
  private val armv7Only = Device(
    abis = listOf("armeabi-v7a", "armeabi"),
    abis32 = listOf("armeabi-v7a", "armeabi"),
    abis64 = emptyList()
  )

  /** Dispositivo Intel a 64 bit e dispositivo Intel solo a 32 bit. */
  private val x86_64Capable = Device(
    abis = listOf("x86_64", "x86"),
    abis32 = listOf("x86"),
    abis64 = listOf("x86_64")
  )
  private val x86Only = Device(
    abis = listOf("x86"),
    abis32 = listOf("x86"),
    abis64 = emptyList()
  )

  // ---------------------------------------------------------------- selezione

  @Test
  fun selectsTheSplitOfTheFirstSupportedAbiAndNotTheFirstAsset() {
    // Ordine degli asset deliberatamente "sbagliato": il primo file elencato da
    // GitHub è quello x86. La scelta deve seguire le ABI, non l'elenco.
    val release = releaseOf(
      "v1.0.1-build.1003",
      listOf(
        "StreamNova-1.0.1-x86.apk",
        "StreamNova-1.0.1-armeabi-v7a.apk",
        "StreamNova-1.0.1-arm64-v8a.apk",
        "StreamNova-1.0.1-x86_64.apk"
      )
    )

    assertEquals(
      "StreamNova-1.0.1-arm64-v8a.apk",
      selectApkAsset(release.assets, arm64Device)?.name
    )
  }

  @Test
  fun selectsTheArmV7SplitOnAnArmV7Device() {
    // Dispositivo a 32 bit: la prima (e unica) ABI supportata è armeabi-v7a,
    // anche se la release contiene lo split arm64.
    val release = releaseOf("v1.0.1-build.1003", plannedAssetNames)

    assertEquals(
      "StreamNova-1.0.1-armeabi-v7a.apk",
      selectApkAsset(release.assets, arm32Device)?.name
    )
  }

  @Test
  fun selectsX86AndX86_64SplitsForIntelDevices() {
    val release = releaseOf("v1.0.1-build.1003", plannedAssetNames)

    assertEquals("StreamNova-1.0.1-x86.apk", selectApkAsset(release.assets, intel32Device)?.name)
    assertEquals("StreamNova-1.0.1-x86_64.apk", selectApkAsset(release.assets, intel64Device)?.name)
  }

  @Test
  fun assetOrderNeverChangesTheSelectedApk() {
    val ordered = plannedAssetNames
    val permutations = listOf(
      ordered.reversed(),
      ordered.shuffled(Random(1003)),
      listOf(ordered[2], ordered[0], ordered[3], ordered[1])
    )
    assertTrue(
      "La prova deve includere ordini in cui lo split arm64 non è il primo",
      permutations.any { it.first() != "StreamNova-1.0.1-arm64-v8a.apk" }
    )

    permutations.forEach { names ->
      val assets = names.map { apk(it) }
      assertEquals(
        "Ordine: $names",
        "StreamNova-1.0.1-arm64-v8a.apk",
        selectApkAsset(assets, arm64Device)?.name
      )
      assertEquals(
        "Ordine: $names",
        "StreamNova-1.0.1-x86_64.apk",
        selectApkAsset(assets, intel64Device)?.name
      )
    }
  }

  @Test
  fun splitsAreRecognizedAlsoWithTheAgpReleaseSuffix() {
    // Nomi realmente pubblicati per la 1.0.0: `StreamNova-<abi>-release.apk`.
    val release = releaseOf("v1.0.0-build.1002", agpAssetNames)

    assertEquals(
      "StreamNova-arm64-v8a-release.apk",
      selectApkAsset(release.assets, arm64Device)?.name
    )
    assertEquals(
      "StreamNova-armeabi-v7a-release.apk",
      selectApkAsset(release.assets, arm32Device)?.name
    )
    assertEquals("StreamNova-x86-release.apk", selectApkAsset(release.assets, intel32Device)?.name)
    assertEquals(
      "StreamNova-x86_64-release.apk",
      selectApkAsset(release.assets, intel64Device)?.name
    )
  }

  @Test
  fun splitsAreRecognizedWhenTheyAlsoCarryTheVersionAndReleaseSuffix() {
    val assets = listOf(apk("StreamNova-1.0.1-arm64-v8a-release.apk"))

    assertEquals(
      "StreamNova-1.0.1-arm64-v8a-release.apk",
      selectApkAsset(assets, arm64Device)?.name
    )
  }

  @Test
  fun splitsRenamedWithCommonAbiAliasesStillMatchTheDevice() {
    // Rinominando a mano gli asset è facile scrivere "arm64" o "x86-64":
    // restano la sigla giusta per il dispositivo, non un nome sconosciuto.
    assertEquals(
      "StreamNova-1.0.1-arm64.apk",
      selectApkAsset(listOf(apk("StreamNova-1.0.1-arm64.apk")), arm64Device)?.name
    )
    assertEquals(
      "StreamNova-1.0.1-x86-64.apk",
      selectApkAsset(listOf(apk("StreamNova-1.0.1-x86-64.apk")), intel64Device)?.name
    )
    assertNull(selectApkAsset(listOf(apk("StreamNova-1.0.1-arm64.apk")), intel64Device))
  }

  @Test
  fun genericReleaseApkIsNotTreatedAsUniversalWhenTheReleaseHasSplits() {
    // `app-release.apk` non garantisce nulla sull'architettura: con gli split
    // presenti vince sempre lo split del dispositivo, e per un dispositivo la
    // cui ABI non è pubblicata non si sceglie nulla (mai un file a caso).
    val release = releaseOf("v1.0.1-build.1003", plannedAssetNames + "app-release.apk")

    assertEquals(
      "StreamNova-1.0.1-arm64-v8a.apk",
      selectApkAsset(release.assets, arm64Device)?.name
    )
    assertNull(selectApkAsset(release.assets, unlistedAbiDevice))
  }

  @Test
  fun explicitlyUniversalApkIsTheFallbackWhenNoSplitMatchesTheDevice() {
    val release = releaseOf(
      "v1.0.1-build.1003",
      plannedAssetNames + "StreamNova-1.0.1-universal.apk"
    )

    // Con lo split disponibile si scarica lo split: è il file esatto, più piccolo.
    assertEquals(
      "StreamNova-1.0.1-arm64-v8a.apk",
      selectApkAsset(release.assets, arm64Device)?.name
    )
    // Senza split per l'ABI del dispositivo, l'unico APK dichiarato universale
    // è l'unica scelta possibile (contiene tutte le ABI per costruzione).
    assertEquals(
      "StreamNova-1.0.1-universal.apk",
      selectApkAsset(release.assets, unlistedAbiDevice)?.name
    )
  }

  @Test
  fun ambiguousUniversalApksAreNotSelected() {
    val assets = listOf(
      apk("StreamNova-1.0.1-universal.apk"),
      apk("StreamNova-1.0.1-universal-release.apk")
    )

    assertNull(selectApkAsset(assets, arm64Device))
  }

  @Test
  fun twoAssetsForTheDeviceAbiAreAmbiguousAndNotSelected() {
    // Se la release pubblica due file per la stessa ABI, sceglierne uno
    // significherebbe dipendere dall'ordine di elenco di GitHub: la release è
    // ambigua e non si seleziona nulla, in qualunque ordine arrivi.
    val assets = listOf(
      apk("StreamNova-1.0.1-arm64-v8a.apk"),
      apk("StreamNova-1.0.1-arm64-v8a-release.apk"),
      apk("StreamNova-1.0.1-x86.apk")
    )

    assertNull(selectApkAsset(assets, arm64Device))
    assertNull(selectApkAsset(assets.reversed(), arm64Device))
  }

  @Test
  fun duplicatedAbisThatDoNotMatchTheDeviceDoNotBlockItsSplit() {
    val assets = listOf(
      apk("StreamNova-1.0.1-x86.apk"),
      apk("StreamNova-1.0.1-x86-release.apk"),
      apk("StreamNova-1.0.1-arm64-v8a.apk")
    )

    assertEquals(
      "StreamNova-1.0.1-arm64-v8a.apk",
      selectApkAsset(assets, arm64Device)?.name
    )
  }

  @Test
  fun anApkThatDoesNotDeclareItsAbiIsNotSelectedEvenWhenItIsTheOnlyOne() {
    // Il file rinominato a mano che sta nella root del progetto è lo split
    // armeabi-v7a: senza sigla nel nome non dichiara l'architettura, quindi non
    // viene offerto a nessun dispositivo — nemmeno a uno a 32 bit ARM che
    // potrebbe eseguirlo, perché la selezione non tira a indovinare.
    assertNull(selectApkAsset(listOf(apk("StreamNova_V1.0.1_.apk")), arm32Device))
    assertNull(selectApkAsset(listOf(apk("StreamNova_V1.0.1_.apk")), arm64Device))
    assertNull(selectApkAsset(listOf(apk("StreamNova-1.0.1.apk")), arm64Device))
    assertNull(selectApkAsset(listOf(apk("app-release.apk")), arm64Device))
  }

  @Test
  fun unknownAssetNamesAreNotSelectedArbitrarily() {
    val assets = listOf(apk("release-one.apk"), apk("release-two.apk"))

    assertNull(selectApkAsset(assets, arm64Device))
  }

  @Test
  fun nonApkAssetIsNeverSelectedForDownload() {
    val assets = listOf(
      ReleaseAsset("checksums.txt", "https://github.com/checksums.txt"),
      ReleaseAsset("changelog.md", "https://github.com/changelog.md")
    )

    assertNull(selectApkAsset(assets, arm64Device))
  }

  @Test
  fun selectApkAssetIsCaseInsensitive() {
    // Estensione e sigla ABI in maiuscolo restano riconoscibili.
    assertEquals(
      "STREAMNOVA-1.0.1-ARM64-V8A.APK",
      selectApkAsset(listOf(apk("STREAMNOVA-1.0.1-ARM64-V8A.APK")), arm64Device)?.name
    )
    assertNull(selectApkAsset(listOf(apk("APP.APK")), arm64Device))
  }

  // ---------------------------------------------------------------- decisione

  @Test
  fun higherVersionIsAnUpdate() {
    val state = check(
      releaseOf("v0.1.2", listOf("StreamNova-0.1.2-arm64-v8a.apk"), body = "fix"),
      arm64Device
    )

    val available = state as UpdateCheckState.UpdateAvailable
    assertEquals("0.1.1", available.currentVersion)
    assertEquals("0.1.2", available.newVersion)
    assertEquals("StreamNova-0.1.2-arm64-v8a.apk", available.assetName)
    assertEquals("fix", available.changelog)
  }

  @Test
  fun equalVersionIsUpToDate() {
    val state = check(
      releaseOf("v0.1.1", listOf("StreamNova-0.1.1-arm64-v8a.apk")),
      arm64Device
    )

    assertEquals("0.1.1", (state as UpdateCheckState.UpToDate).currentVersion)
  }

  @Test
  fun lowerVersionIsUpToDate() {
    val state = check(
      releaseOf("v0.1.0", listOf("StreamNova-0.1.0-arm64-v8a.apk")),
      arm64Device
    )

    assertTrue(state is UpdateCheckState.UpToDate)
  }

  @Test
  fun tagAndAssetVersionDisagreementIsAMismatch() {
    // Caso storico: tag v1.0.0 con asset V0.1.0. Il tag è numericamente
    // maggiore della versione installata, ma il file pubblicato non lo è:
    // l'aggiornamento non deve essere proposto.
    val state = check(
      releaseOf("v1.0.0", listOf("StreamNova-V0.1.0-arm64-v8a.apk")),
      arm64Device
    )

    val mismatch = state as UpdateCheckState.VersionMismatch
    assertEquals("0.1.1", mismatch.currentVersion)
    assertEquals("1.0.0", mismatch.tagVersion)
    assertEquals("0.1.0", mismatch.assetVersion)
    assertEquals("StreamNova-V0.1.0-arm64-v8a.apk", mismatch.assetName)
  }

  @Test
  fun assetVersionNewerThanTheTagIsAlsoAMismatch() {
    val state = check(
      releaseOf("v1.0.1-build.1003", listOf("StreamNova-1.0.2-arm64-v8a.apk")),
      arm64Device
    )

    assertEquals("1.0.2", (state as UpdateCheckState.VersionMismatch).assetVersion)
  }

  @Test
  fun mismatchIsDetectedOnAnyAssetOfTheRelease() {
    // Anche se un asset senza versione fosse il preferito, un asset ABI con
    // versione discordante rende la release incoerente.
    val state = check(
      releaseOf(
        "v1.0.0",
        listOf("app-release.apk", "StreamNova-0.1.0-arm64-v8a.apk")
      ),
      arm64Device
    )

    assertTrue(state is UpdateCheckState.VersionMismatch)
  }

  @Test
  fun matchingTagAndAssetVersionsAreAccepted() {
    val state = check(
      releaseOf("v0.1.2", listOf("StreamNova-0.1.2-armeabi-v7a.apk")),
      arm32Device
    )

    assertTrue(state is UpdateCheckState.UpdateAvailable)
  }

  @Test
  fun plannedReleaseOffersTheSplitOfThisDevice() {
    // Tag e nomi asset previsti per la 1.0.1: la versione viene letta dal nome
    // senza confondere le sigle ABI, e il file offerto è quello del dispositivo.
    val state = check(
      releaseOf("v1.0.1-build.1003", plannedAssetNames, body = "seek preview"),
      arm64Device,
      installedVersion = AppVersion.parse("1.0.0")!!
    )

    val available = state as UpdateCheckState.UpdateAvailable
    assertEquals("1.0.1", available.newVersion)
    assertEquals("StreamNova-1.0.1-arm64-v8a.apk", available.assetName)
    assertEquals("v1.0.1-build.1003", available.tagName)
    assertEquals("seek preview", available.changelog)
  }

  @Test
  fun assetWithoutExtractableVersionFallsBackToTheTag() {
    // Nome realmente pubblicato per la 1.0.0: il file non porta la versione, che
    // viene quindi presa dal tag della release.
    val state = check(
      releaseOf("v0.1.2", listOf("StreamNova-arm64-v8a-release.apk")),
      arm64Device
    )

    val available = state as UpdateCheckState.UpdateAvailable
    assertEquals("0.1.2", available.newVersion)
    assertEquals("StreamNova-arm64-v8a-release.apk", available.assetName)
  }

  @Test
  fun releaseWithoutAnyApkDoesNotOfferAnUpdate() {
    val state = check(
      release("v0.1.2", listOf(ReleaseAsset("notes.txt", "https://github.com/notes.txt"))),
      arm64Device
    )

    assertEquals(
      UpdateCheckErrorReason.NO_COMPATIBLE_APK,
      (state as UpdateCheckState.Error).reason
    )
  }

  @Test
  fun releaseWithOnlyIncompatibleAbisDoesNotOfferAnUpdate() {
    val state = check(releaseOf("v1.0.1-build.1003", plannedAssetNames), unlistedAbiDevice)

    assertEquals(
      UpdateCheckErrorReason.NO_COMPATIBLE_APK,
      (state as UpdateCheckState.Error).reason
    )
  }

  @Test
  fun ambiguousReleaseDoesNotOfferAnUpdate() {
    val state = check(
      releaseOf("v1.0.1-build.1003", listOf("release-one.apk", "release-two.apk")),
      arm64Device
    )

    assertEquals(
      UpdateCheckErrorReason.NO_COMPATIBLE_APK,
      (state as UpdateCheckState.Error).reason
    )
  }

  @Test
  fun aReleaseWithTwoAssetsForTheDeviceAbiDoesNotOfferAnUpdate() {
    // Release pubblicata due volte per la stessa ABI: nessun aggiornamento
    // offerto e nessun download, invece di un file scelto per ordine di elenco.
    val state = check(
      releaseOf(
        "v1.0.1-build.1003",
        listOf(
          "StreamNova-1.0.1-arm64-v8a.apk",
          "StreamNova-1.0.1-arm64-v8a-release.apk"
        )
      ),
      arm64Device
    )

    assertEquals(
      UpdateCheckErrorReason.NO_COMPATIBLE_APK,
      (state as UpdateCheckState.Error).reason
    )
  }

  @Test
  fun aSingleRenamedSplitDoesNotOfferAnUpdateToAnyDevice() {
    // Nome senza architettura: l'aggiornamento non viene proposto né al
    // dispositivo a 32 bit ARM che potrebbe eseguirlo né a quello a 64 bit.
    val state = check(releaseOf("v1.0.1-build.1003", listOf("StreamNova_V1.0.1_.apk")), arm32Device)

    assertEquals(
      UpdateCheckErrorReason.NO_COMPATIBLE_APK,
      (state as UpdateCheckState.Error).reason
    )
  }

  @Test
  fun anOlderReleaseWithoutCompatibleApkStaysUpToDate() {
    // Release non più recente: nessun aggiornamento da offrire, e l'assenza di
    // un APK compatibile non è un errore da mostrare all'utente.
    val state = check(releaseOf("v0.1.0", listOf("StreamNova-0.1.0-x86.apk")), arm64Device)

    assertTrue(state is UpdateCheckState.UpToDate)
  }

  // ------------------------------------------ ABI dell'installazione corrente

  @Test
  fun followingTheSystemOrderAloneWouldChangeTheArchitectureOfA32BitInstall() {
    // Contro-prova del difetto segnalato: con l'elenco del sistema così com'è
    // (l'unico input usato dalla selezione prima della correzione) a una app
    // installata a 32 bit viene offerto l'APK a 64 bit solo perché il sistema lo
    // dichiara eseguibile. È il caso "Fire TV a 64 bit con installazione a 32
    // bit": il file scelto cambierebbe architettura all'app con l'aggiornamento.
    val release = releaseOf("v1.0.1-build.1003", agpAssetNames)

    assertEquals(
      "StreamNova-arm64-v8a-release.apk",
      selectApkAsset(release.assets, arm64CapableArm.abis)?.name
    )
  }

  @Test
  fun a32BitInstallKeepsThe32BitSplitOnA64BitCapableDevice() {
    // Regresso del 2026-10-25: un dispositivo arm64 che esegue una installazione
    // a 32 bit (l'APK per Fire TV della 1.0.0 era armeabi-v7a). Il sistema
    // dichiara arm64-v8a per primo, ma l'app gira a 32 bit: l'aggiornamento deve
    // restare sull'architettura dell'installazione, non cambiarla.
    val release = releaseOf("v1.0.1-build.1003", agpAssetNames)

    assertEquals(
      "StreamNova-armeabi-v7a-release.apk",
      arm64CapableArm.selectedAsset(release, is64BitProcess = false)?.name
    )
  }

  @Test
  fun a64BitInstallKeepsThe64BitSplit() {
    // Stesso dispositivo, installazione a 64 bit: l'ordine del sistema resta
    // quello giusto e nulla cambia rispetto a prima.
    val release = releaseOf("v1.0.1-build.1003", agpAssetNames)

    assertEquals(
      "StreamNova-arm64-v8a-release.apk",
      arm64CapableArm.selectedAsset(release, is64BitProcess = true)?.name
    )
  }

  @Test
  fun aDeviceThatRunsOnly32BitArmGetsThe32BitSplitEitherWay() {
    // Fire TV solo a 32 bit: l'elenco del sistema contiene già solo ABI a 32 bit,
    // quindi la larghezza di parola del processo non cambia il risultato.
    val release = releaseOf("v1.0.1-build.1003", agpAssetNames)

    assertEquals(
      "StreamNova-armeabi-v7a-release.apk",
      armv7Only.selectedAsset(release, is64BitProcess = false)?.name
    )
  }

  @Test
  fun intelDevicesFollowTheSameWidthOfProcessRule() {
    val release = releaseOf("v1.0.1-build.1003", agpAssetNames)

    assertEquals(
      "StreamNova-x86-release.apk",
      x86_64Capable.selectedAsset(release, is64BitProcess = false)?.name
    )
    assertEquals(
      "StreamNova-x86_64-release.apk",
      x86_64Capable.selectedAsset(release, is64BitProcess = true)?.name
    )
    assertEquals(
      "StreamNova-x86-release.apk",
      x86Only.selectedAsset(release, is64BitProcess = false)?.name
    )
  }

  @Test
  fun thePreferredAbisAreTheOnesOfTheProcessWidthInSystemOrder() {
    assertEquals(
      listOf("armeabi-v7a", "armeabi", "arm64-v8a"),
      arm64CapableArm.preferenceOrder(is64BitProcess = false)
    )
    assertEquals(
      listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
      arm64CapableArm.preferenceOrder(is64BitProcess = true)
    )
    assertEquals(
      listOf("x86", "x86_64"),
      x86_64Capable.preferenceOrder(is64BitProcess = false)
    )
  }

  @Test
  fun theProcessWidthNeverMakesAnUndeclaredAbiSelectable() {
    // La CPU a 32 bit non aggiunge nulla a un elenco di sistema che dichiara
    // solo arm64-v8a: l'insieme delle ABI scegliibili resta quello dichiarato.
    val device = Device(
      abis = listOf("arm64-v8a"),
      abis32 = emptyList(),
      abis64 = listOf("arm64-v8a")
    )

    assertEquals(listOf("arm64-v8a"), device.preferenceOrder(is64BitProcess = false))
  }

  @Test
  fun anEmptyAbiListStaysEmptyAndSelectsNothing() {
    val device = Device(abis = emptyList(), abis32 = emptyList(), abis64 = emptyList())

    assertEquals(emptyList<String>(), device.preferenceOrder(is64BitProcess = false))
    assertEquals(emptyList<String>(), device.preferenceOrder(is64BitProcess = true))
    assertNull(device.selectedAsset(releaseOf("v1.0.1-build.1003", agpAssetNames), false))
  }

  @Test
  fun duplicatedOrBlankAbisDoNotDuplicateThePreferenceOrder() {
    val device = Device(
      abis = listOf(" arm64-v8a ", "arm64-v8a", "", "armeabi-v7a", "armeabi-v7a"),
      abis32 = listOf("armeabi-v7a", " "),
      abis64 = listOf("arm64-v8a")
    )

    assertEquals(
      listOf("armeabi-v7a", "arm64-v8a"),
      device.preferenceOrder(is64BitProcess = false)
    )
  }

  @Test
  fun a32BitInstallPicksTheSameSplitWhateverOrderGitHubListsTheAssets() {
    val permutations = listOf(
      agpAssetNames,
      agpAssetNames.reversed(),
      agpAssetNames.shuffled(Random(1004)),
      listOf(
        agpAssetNames[3],
        agpAssetNames[1],
        agpAssetNames[0],
        agpAssetNames[2]
      )
    )

    permutations.forEach { names ->
      val release = releaseOf("v1.0.1-build.1003", names)
      assertEquals(
        "Ordine: $names",
        "StreamNova-armeabi-v7a-release.apk",
        arm64CapableArm.selectedAsset(release, is64BitProcess = false)?.name
      )
    }
  }

  @Test
  fun aReleaseWithoutTheInstalledArchitectureFallsBackToTheOtherRunnableAbis() {
    // La release non pubblica più lo split a 32 bit ARM: l'aggiornamento resta
    // possibile con l'altra ABI che il sistema dichiara di eseguire, mai con una
    // che non compare nel suo elenco.
    val release = releaseOf("v1.0.1-build.1003", listOf("StreamNova-arm64-v8a-release.apk"))

    assertEquals(
      "StreamNova-arm64-v8a-release.apk",
      arm64CapableArm.selectedAsset(release, is64BitProcess = false)?.name
    )
  }

  @Test
  fun aReleaseWithOnlyUnexecutableSplitsIsNotOfferedToA32BitInstall() {
    // Dispositivo che esegue solo arm64-v8a e release che pubblica solo lo split
    // a 32 bit ARM: nessun APK eseguibile, nessuna proposta.
    val device = Device(
      abis = listOf("arm64-v8a"),
      abis32 = emptyList(),
      abis64 = listOf("arm64-v8a")
    )
    val release = releaseOf("v1.0.1-build.1003", listOf("StreamNova-armeabi-v7a-release.apk"))

    assertNull(device.selectedAsset(release, is64BitProcess = false))
    val state = evaluateUpdate(
      installed,
      release,
      device.selectedAsset(release, is64BitProcess = false)
    )
    assertEquals(
      UpdateCheckErrorReason.NO_COMPATIBLE_APK,
      (state as UpdateCheckState.Error).reason
    )
  }

  @Test
  fun duplicatedAssetsForTheInstalledAbiStopTheUpdateInsteadOfChangingArchitecture() {
    // Due file per la stessa ABI dell'installazione corrente: la scelta
    // dipenderebbe dall'ordine di elenco, quindi non si sceglie nulla — e non si
    // ripiega sullo split arm64, che cambierebbe architettura all'app.
    val release = releaseOf(
      "v1.0.1-build.1003",
      listOf(
        "StreamNova-armeabi-v7a-release.apk",
        "StreamNova-1.0.1-armeabi-v7a.apk",
        "StreamNova-arm64-v8a-release.apk"
      )
    )

    assertNull(arm64CapableArm.selectedAsset(release, is64BitProcess = false))
    val state = evaluateUpdate(
      installed,
      release,
      arm64CapableArm.selectedAsset(release, is64BitProcess = false)
    )
    assertEquals(
      UpdateCheckErrorReason.NO_COMPATIBLE_APK,
      (state as UpdateCheckState.Error).reason
    )
  }

  @Test
  fun theUniversalBridgeNamedAppReleaseApkIsInvisibleToThisSelection() {
    // La release 1.0.2 pubblica un APK universale chiamato esattamente
    // `app-release.apk` perché è il nome che l'updater della 1.0.0 preferisce:
    // quello è l'unico percorso con cui un'installazione ferma alla 1.0.0 può
    // aggiornarsi. Per questa selezione quel file non esiste — non dichiara
    // architettura, quindi non è né uno split né un universale dichiarato — e
    // resta in uso lo split del dispositivo. Se lo si rinominasse
    // `...-universal...` diventerebbe un ripiego selezionabile: il test fissa
    // che non lo sia.
    val release = releaseOf("v1.0.2-build.1004", agpAssetNames + "app-release.apk")

    assertEquals(
      "StreamNova-armeabi-v7a-release.apk",
      arm64CapableArm.selectedAsset(release, is64BitProcess = false)?.name
    )
    assertEquals(
      "StreamNova-arm64-v8a-release.apk",
      arm64CapableArm.selectedAsset(release, is64BitProcess = true)?.name
    )
    assertNull(
      selectApkAsset(listOf(apk("app-release.apk")), arm64CapableArm.preferenceOrder(false))
    )
  }

  @Test
  fun theUrlOfTheSplitChosenForA32BitInstallIsTheUrlOfThatSameAsset() {
    // Identità asset → URL anche sul percorso a 32 bit: l'URL scaricato è quello
    // dell'asset scelto, non quello di un altro split della release.
    val release = releaseOf("v1.0.1-build.1003", agpAssetNames)
    val selected = arm64CapableArm.selectedAsset(release, is64BitProcess = false)!!

    assertEquals("StreamNova-armeabi-v7a-release.apk", selected.name)
    assertTrue(
      "URL dell'asset scelto: ${selected.downloadUrl}",
      selected.downloadUrl.endsWith("/${selected.name}")
    )
    assertNotEquals(
      agpAssetNames.first { it.contains("arm64-v8a") },
      selected.downloadUrl.substringAfterLast('/')
    )
  }

  @Test
  fun thePlannedForRealReleaseOfA32BitFireTvIsTheArmV7Split() {
    // Nomi realmente pubblicati per la 1.0.1 (`StreamNova-<abi>-release.apk`,
    // nessuna versione nel nome) e installazione a 32 bit su dispositivo arm64.
    val release = releaseOf("v1.0.1-build.1003", agpAssetNames, body = "selezione per ABI")
    val state = evaluateUpdate(
      AppVersion.parse("1.0.0")!!,
      release,
      arm64CapableArm.selectedAsset(release, is64BitProcess = false)
    )

    val available = state as UpdateCheckState.UpdateAvailable
    assertEquals("1.0.1", available.newVersion)
    assertEquals("StreamNova-armeabi-v7a-release.apk", available.assetName)
    assertEquals("v1.0.1-build.1003", available.tagName)
  }

  // ------------------------------------------------- integrità dell'asset scelto

  @Test
  fun theSelectedSplitIsTheAssetWhoseSizeAndDigestAreVerified() {
    val payload = "contenuto dello split arm64".toByteArray(Charsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(payload)
      .joinToString("") { "%02x".format(it) }
    val release = release(
      "v1.0.1-build.1003",
      listOf(
        apk("StreamNova-1.0.1-x86.apk", size = 1L),
        apk(
          "StreamNova-1.0.1-arm64-v8a.apk",
          size = payload.size.toLong(),
          digest = "sha256:$digest"
        )
      )
    )
    val selected = selectApkAsset(release.assets, arm64Device)!!

    val file = File.createTempFile("streamnova-split", ".apk")
    try {
      file.writeBytes(payload)
      // Dimensione e SHA-256 dichiarati dall'asset scelto sono quelli verificati.
      verifyDownloadedApk(file, selected.size, selected.digest)

      val tampered = payload.copyOf().also { it[0] = (it[0] + 1).toByte() }
      file.writeBytes(tampered)
      val error = assertThrows(DownloadException::class.java) {
        verifyDownloadedApk(file, selected.size, selected.digest)
      }
      assertEquals(DownloadErrorReason.CHECKSUM_MISMATCH, error.reason)
    } finally {
      file.delete()
    }
  }
}
