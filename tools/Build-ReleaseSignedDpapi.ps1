# Build-ReleaseSignedDpapi.ps1 — StreamNova: build release firmata usando le
# credenziali protette DPAPI preparate da Import-ReleaseKey.ps1 (modalita' A).
#
# Le password NON vengono mostrate, stampate o salvate: restano nella sola
# variabile d'ambiente del processo Gradle, poi vengono azzerate.
#
# USO:
#   powershell -ExecutionPolicy Bypass -File tools\Build-ReleaseSignedDpapi.ps1 [-RepoRoot <cartella progetto>]

param(
  [string]$RepoRoot = 'D:\VSCODE\Progetti\StreamNova_integration',
  # Keystore ufficiale della release (verifica Import-ReleaseKey.ps1).
  [string]$KeystorePath = (Join-Path $env:LOCALAPPDATA 'StreamNovaSigning\streamnova-release-v2.p12'),
  [string]$OfficialSha256 = '3175a7df6ea50a1be756c4a0b74c2623757128b620d28f40e678eed715fd2cd7',
  [string]$CredentialFile = (Join-Path $env:LOCALAPPDATA 'StreamNovaSigning\release-cred.dpapi.json')
)
$ErrorActionPreference = 'Stop'

function Write-Step([string]$Text) { Write-Host "== $Text" -ForegroundColor Cyan }
function Write-Ok([string]$Text)   { Write-Host "   OK   $Text" -ForegroundColor Green }
function Write-Bad([string]$Text)  { Write-Host "   FAIL $Text" -ForegroundColor Red }

function ConvertFrom-DpapiSecure([string]$Encrypted) {
  $sec = ConvertTo-SecureString -String $Encrypted
  $b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
  try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b) }
}

# ── 1/3 Credenziali DPAPI (solo processo corrente) ────────────────────────────
Write-Step 'Credenziali firma (DPAPI)'
if (-not (Test-Path -LiteralPath $CredentialFile)) {
  throw "Credenziali DPAPI assenti: $CredentialFile (eseguire Import-ReleaseKey.ps1 senza -BuildNow per crearle)"
}
$cred = Get-Content -LiteralPath $CredentialFile -Raw | ConvertFrom-Json
$storePwd = ConvertFrom-DpapiSecure $cred.storeSecret
$keyPwd   = ConvertFrom-DpapiSecure $cred.keySecret
if ([string]::IsNullOrEmpty($storePwd)) { throw 'STORE_PASSWORD decrittata vuota.' }
if (-not (Test-Path -LiteralPath $KeystorePath)) { throw "Keystore non trovato: $KeystorePath" }
Write-Ok "keystore: $KeystorePath (password caricate nel processo, mai stampate)"

# ── 2/3 Build release firmata ─────────────────────────────────────────────────
Write-Step 'Build release firmata (assembleRelease)'
$env:KEYSTORE_PATH  = $KeystorePath
$env:STORE_PASSWORD = $storePwd
$env:KEY_PASSWORD   = $keyPwd
Set-Location -LiteralPath $RepoRoot
& (Join-Path $RepoRoot 'gradlew.bat') :app:assembleRelease --console=plain
$buildCode = $LASTEXITCODE
$env:STORE_PASSWORD = $null
$env:KEY_PASSWORD   = $null
$env:KEYSTORE_PATH  = $null
$storePwd = $null; $keyPwd = $null
if ($buildCode -ne 0) { throw "Gradle assembleRelease fallito (exit code $buildCode)" }
Write-Ok 'build completata'

# ── 3/3 Verifica firma APK ────────────────────────────────────────────────────
Write-Step 'Verifica firma APK (apksigner)'
$sdkDir = ((Get-Content (Join-Path $RepoRoot 'local.properties') |
  Where-Object { $_ -match '^sdk\.dir=' }) -replace '^sdk\.dir=', '') -replace '\\\\', '\'
$bt = Get-ChildItem (Join-Path $sdkDir 'build-tools') -Directory |
  Where-Object { Test-Path (Join-Path $_.FullName 'apksigner.bat') } |
  Sort-Object Name -Descending | Select-Object -First 1
if ($null -eq $bt) { throw 'apksigner non trovato nei build-tools' }
$apksigner = Join-Path $bt.FullName 'apksigner.bat'
Write-Host ("   build-tools: " + $bt.Name)

$outDir = Join-Path $RepoRoot 'app\build\outputs\apk\release'
$apks = Get-ChildItem $outDir -Filter '*.apk' | Sort-Object Name
if ($apks.Count -eq 0) { throw "Nessun APK in $outDir" }
$allOk = $true
foreach ($apk in $apks) {
  $out = & $apksigner verify --print-certs $apk.FullName 2>&1
  $fp = ($out | Select-String -Pattern 'SHA-256 digest:').Line |
    ForEach-Object { ($_ -replace '.*SHA-256 digest:\s*', '').Trim() -replace ':', '' } |
    Select-Object -First 1
  if ($fp -eq $OfficialSha256.ToLower()) {
    Write-Ok ("{0}: firma ufficiale {1}" -f $apk.Name, $fp.Substring(0, 12) + '...')
  } else {
    $allOk = $false
    Write-Bad ("{0}: firma DIVERSA {1}" -f $apk.Name, $fp)
  }
}
if (-not $allOk) { throw 'Almeno un APK non porta il certificato ufficiale: NON installare.' }
Write-Host ''
Write-Ok 'Tutti gli APK release sono firmati con il certificato ufficiale.'
