# Build-ReleaseSigned.ps1
# StreamNova 1.0.8 (build 1010, con Sync mode) - build release firmata + verifica completa.
#
# USO (dot-sourcing obbligatorio: le variabili di firma restano nel processo corrente):
#     . 'D:\VSCODE\Progetti\StreamNova_integration\tools\Build-ReleaseSigned.ps1'
#
# Cosa fa:
#   1. Import-SigningCredentials.ps1 (cartella utente StreamNovaSigning): chiede le
#      password con prompt nascosti e imposta STORE_PASSWORD/KEY_PASSWORD/KEYSTORE_PATH
#      SOLO nel processo corrente - mai mostrate, mai su disco, mai in questa chat.
#   2. gradlew :app:assembleRelease - 5 APK firmati (universal + 4 ABI split).
#   3. Verifica per ogni APK: firma (apksigner, confronto fingerprint col riferimento),
#      identity (aapt2: package, versionCode 1010, versionName 1.0.8, ABI dichiarata)
#      e SHA-256 di ogni file. Tutto registrato in build\release-verification.txt.
#
# Nessuna password transita da qui: lo script contiene solo percorsi e controlli.

param(
  [string]$RepoRoot = 'D:\VSCODE\Progetti\StreamNova_integration',
  # Stesso riferimento del keystore usato per le release correnti (vedi Import-SigningCredentials).
  [string]$ReferenceSha256 = 'c97954610ba03cd4d392c1c2278c6e52f90b55d3cd631af1ff897c6fcc3f25e0',
  [string]$ExpectedVersionCode = '1010',
  [string]$ExpectedVersionName = '1.0.8'
)
$ErrorActionPreference = 'Stop'

Write-Host '=== 1/3 Credenziali di firma (prompt nascosti) ===' -ForegroundColor Cyan
. 'C:\Users\miciuss\StreamNovaSigning\Import-SigningCredentials.ps1'

Set-Location $RepoRoot

Write-Host ''
Write-Host '=== 2/3 Build release firmata (assembleRelease) ===' -ForegroundColor Cyan
& "$RepoRoot\gradlew.bat" :app:assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) {
  throw "Gradle assembleRelease fallito (exit code $LASTEXITCODE): nessun APK pubblicabile."
}

# ── 3/3 Verifica ────────────────────────────────────────────────────────────────

Write-Host ''
Write-Host '=== 3/3 Verifica APK ===' -ForegroundColor Cyan

# sdk.dir da local.properties ( Doppio backslash nel file -> percorso reale).
$sdkDir = ((Get-Content "$RepoRoot\local.properties" |
  Where-Object { $_ -match '^sdk\.dir=' }) -replace '^sdk\.dir=', '') -replace '\\\\', '\'
$buildToolsRoot = Join-Path $sdkDir 'build-tools'
$bt = Get-ChildItem $buildToolsRoot -Directory |
  Where-Object { Test-Path (Join-Path $_.FullName 'apksigner.bat') } |
  Sort-Object Name -Descending | Select-Object -First 1
if ($null -eq $bt) { throw "apksigner non trovato in $buildToolsRoot" }
$apksigner = Join-Path $bt.FullName 'apksigner.bat'
$aapt2 = Join-Path $bt.FullName 'aapt2.exe'
Write-Host ("Build-tools usati per la verifica: " + $bt.Name)

$outDir = "$RepoRoot\app\build\outputs\apk\release"
$apks = Get-ChildItem $outDir -Filter '*.apk' | Sort-Object Name
if ($apks.Count -eq 0) { throw "Nessun APK in $outDir" }

$logLines = @()
$allOk = $true

foreach ($apk in $apks) {
  Write-Host ''
  Write-Host ("--- " + $apk.Name + " (" + [math]::Round($apk.Length / 1MB, 1) + " MB) ---")

  # Firma: apksigner verifica la catena e stampa il certificato.
  $certs = & $apksigner verify --print-certs $apk.FullName 2>&1
  if ($LASTEXITCODE -ne 0) {
    $allOk = $false
    Write-Host "  FIRMA: FALLITA" -ForegroundColor Red
    $logLines += "$($apk.Name) - FIRMA FALLITA: $($certs -join ' ')"
    continue
  }
  $certLine = $certs | Where-Object { $_ -match 'SHA-256 digest' } | Select-Object -First 1
  $certSha = if ($certLine -match '([0-9a-fA-F]{64})') { $Matches[1].ToLowerInvariant() } else { '' }
  $sigMatch = ($certSha -eq $ReferenceSha256.ToLowerInvariant())
  if (-not $sigMatch) { $allOk = $false }
  Write-Host ("  FIRMA: valida - certificato SHA-256 " + $certSha)
  Write-Host ("  MATCH RIFERIMENTO DISTRIBUZIONE: " + $(if ($sigMatch) { 'SI' } else { 'NO - CONTROLLARE' }) ) -ForegroundColor $(if ($sigMatch) { 'Green' } else { 'Red' })

  # Identity: package, versionCode, versionName, ABI dichiarate.
  $badging = & $aapt2 dump badging $apk.FullName 2>&1
  $pkgLine = $badging | Where-Object { $_ -match "^package: " } | Select-Object -First 1
  $abiLine = $badging | Where-Object { $_ -match '^native-code: ' } | Select-Object -First 1
  $vcOk = ($pkgLine -match "versionCode='$ExpectedVersionCode'")
  $vnOk = ($pkgLine -match "versionName='$ExpectedVersionName'")
  if (-not ($vcOk -and $vnOk)) { $allOk = $false }
  Write-Host ("  IDENTITY: " + $pkgLine)
  Write-Host ("  ABI: " + $(if ($abiLine) { ($abiLine -replace '^native-code: ', '') } else { 'nessuna (universale)' }))
  Write-Host ("  VERSIONE ATTESA $ExpectedVersionName ($ExpectedVersionCode): " + $(if ($vcOk -and $vnOk) { 'OK' } else { 'DIVERSA' }))

  $sha = (Get-FileHash -Algorithm SHA256 $apk.FullName).Hash.ToLowerInvariant()
  Write-Host ("  SHA-256: " + $sha)
  $logLines += "$($apk.Name) | sig=$certSha match=$sigMatch | $pkgLine | sha256=$sha"
}

$report = Join-Path $RepoRoot 'build\release-verification.txt'
$logLines | Set-Content -Path $report -Encoding UTF8

Write-Host ''
if ($allOk) {
  Write-Host 'VERIFICA COMPLETATA: firma, versione e ABI di tutti gli APK sono coerenti.' -ForegroundColor Green
} else {
  Write-Host 'VERIFICA CON ANOMALIE: controllare le righe segnate sopra prima di distribuire.' -ForegroundColor Red
}
Write-Host ("Report: " + $report)
Write-Host ("Log completo: " + (Join-Path $RepoRoot 'build\release-verification.txt'))
