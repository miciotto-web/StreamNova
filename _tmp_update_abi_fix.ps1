$ErrorActionPreference = 'Stop'
$root = 'D:\VSCODE\Progetti\StreamNova'
$dpath = Join-Path $root 'app\src\main\java\com\example\data\update\ApkDownloader.kt'
$rpath = Join-Path $root 'app\src\main\java\com\example\data\update\UpdateRepository.kt'
$tpath = Join-Path $root 'app\src\test\java\com\example\data\update\UpdateDecisionTest.kt'
$enc = New-Object System.Text.UTF8Encoding($false)
$d = [System.IO.File]::ReadAllText($dpath)
$old = @'
/**
 * Seleziona l'asset APK da scaricare: solo estensione .apk, con preferenza
 * per "app-release.apk". Restituisce null se la release non contiene APK.
 */
fun selectApkAsset(assets: List<ReleaseAsset>): ReleaseAsset? {
  val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
  return apkAssets.firstOrNull { it.name.equals("app-release.apk", ignoreCase = true) }
    ?: apkAssets.firstOrNull()
}
'@
$new = @'
/** Seleziona un APK compatibile con le ABI supportate dal dispositivo. */
fun selectApkAsset(assets: List<ReleaseAsset>, supportedAbis: List<String>): ReleaseAsset? {
  val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
  if (apkAssets.isEmpty()) return null
  apkAssets.firstOrNull { it.name.equals("app-release.apk", ignoreCase = true) }?.let { return it }
  val knownAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
  val abiAssets = apkAssets.mapNotNull { asset ->
    val stem = asset.name.substringBeforeLast('.', asset.name)
    val abi = knownAbis.firstOrNull { suffix ->
      stem.endsWith("-$suffix", ignoreCase = true) || stem.endsWith("-$suffix-release", ignoreCase = true)
    }
    if (abi == null) null else asset to abi
  }
  if (abiAssets.isNotEmpty()) {
    for (supportedAbi in supportedAbis) {
      abiAssets.firstOrNull { (_, abi) -> abi.equals(supportedAbi, ignoreCase = true) }?.let { return it.first }
    }
    return null
  }
  return apkAssets.singleOrNull()
}
'@
if (-not $d.Contains($old)) { throw 'selectApkAsset block not found; no files changed.' }
$d = $d.Replace($old, $new)
[System.IO.File]::WriteAllText($dpath, $d, $enc)
$r = [System.IO.File]::ReadAllText($rpath)
$nl = [Environment]::NewLine
$r = $r.Replace('import android.content.Context' + $nl, 'import android.content.Context' + $nl + 'import android.os.Build' + $nl)
$r = $r.Replace('internal fun evaluateUpdate(installed: AppVersion, latest: LatestRelease): UpdateCheckState {', 'internal fun evaluateUpdate(installed: AppVersion, latest: LatestRelease, supportedAbis: List<String> = listOf("arm64-v8a")): UpdateCheckState {')
$r = $r.Replace('val selected = selectApkAsset(latest.assets)', 'val selected = selectApkAsset(latest.assets, supportedAbis)')
$r = $r.Replace('evaluateUpdate(installed, latest).also { result ->', 'evaluateUpdate(installed, latest, Build.SUPPORTED_ABIS.toList()).also { result ->')
$r = $r.Replace('val asset = release?.let { selectApkAsset(it.assets) }', 'val asset = release?.let { selectApkAsset(it.assets, Build.SUPPORTED_ABIS.toList()) }')
[System.IO.File]::WriteAllText($rpath, $r, $enc)
$t = [System.IO.File]::ReadAllText($tpath)
$t = $t.Replace('selectApkAsset(assets))', 'selectApkAsset(assets, listOf("arm64-v8a")))')
$t = $t.Replace('selectApkAsset(listOf(apk("APP.APK")))', 'selectApkAsset(listOf(apk("APP.APK")), listOf("arm64-v8a"))')
$insert = @'

  @Test
  fun selectsApkMatchingFirstSupportedAbiInsteadOfFirstAsset() {
    val assets = listOf(apk("StreamNova-1.0.1-x86.apk"), apk("StreamNova-1.0.1-armeabi-v7a.apk"), apk("StreamNova-1.0.1-arm64-v8a.apk"), apk("StreamNova-1.0.1-x86_64.apk"))
    assertEquals("StreamNova-1.0.1-arm64-v8a.apk", selectApkAsset(assets, listOf("arm64-v8a", "armeabi-v7a"))?.name)
  }

  @Test
  fun returnsNullWhenNoSplitMatchesDeviceAbi() {
    val assets = listOf(apk("StreamNova-1.0.1-arm64-v8a.apk"), apk("StreamNova-1.0.1-x86_64.apk"))
    assertEquals(null, selectApkAsset(assets, listOf("armeabi-v7a")))
  }

  @Test
  fun ambiguousLegacyApksAreNotSelectedArbitrarily() {
    val assets = listOf(apk("release-one.apk"), apk("release-two.apk"))
    assertEquals(null, selectApkAsset(assets, listOf("arm64-v8a")))
  }

  @Test
  fun genericUniversalApkIsPreferred() {
    val assets = listOf(apk("StreamNova-1.0.1-x86.apk"), apk("app-release.apk"))
    assertEquals("app-release.apk", selectApkAsset(assets, listOf("arm64-v8a"))?.name)
  }
'@
$t = $t.Replace($nl + '}', $insert + $nl + '}')
[System.IO.File]::WriteAllText($tpath, $t, $enc)
Write-Output 'Updater selection and tests updated; no Git operations performed.'
Set-Location $root
.\gradlew.bat :app:testDebugUnitTest --rerun
