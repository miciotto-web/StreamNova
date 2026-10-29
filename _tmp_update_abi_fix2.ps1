$ErrorActionPreference='Stop'
$root='D:\VSCODE\Progetti\StreamNova'
$rpath=Join-Path $root 'app\src\main\java\com\example\data\update\UpdateRepository.kt'
$tpath=Join-Path $root 'app\src\test\java\com\example\data\update\UpdateDecisionTest.kt'
$enc=New-Object System.Text.UTF8Encoding($false)
$r=[IO.File]::ReadAllText($rpath)
if ($r -notmatch 'import android\.os\.Build') { $r=$r.Replace('import android.content.Context', 'import android.content.Context' + [Environment]::NewLine + 'import android.os.Build') }
[IO.File]::WriteAllText($rpath,$r,$enc)
$t=[IO.File]::ReadAllText($tpath)
$t=$t.Replace('selectApkAsset(assets)?.name', 'selectApkAsset(assets, listOf("arm64-v8a"))?.name')
$t=$t.Replace('evaluateUpdate(installed, release("v0.1.2", listOf(apk("StreamNova-0.1.2-armeabi-v7a.apk"))))', 'evaluateUpdate(installed, release("v0.1.2", listOf(apk("StreamNova-0.1.2-armeabi-v7a.apk"))), listOf("armeabi-v7a"))')
$insert=@'

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
if ($t -notmatch 'selectsApkMatchingFirstSupportedAbiInsteadOfFirstAsset') {
  $idx=$t.LastIndexOf('}')
  if($idx -lt 0){throw 'Cannot find class closing brace'}
  $t=$t.Insert($idx,$insert + [Environment]::NewLine)
}
[IO.File]::WriteAllText($tpath,$t,$enc)
Write-Output 'Corrected Build import and added ABI selection tests.'
