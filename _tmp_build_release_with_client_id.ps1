# Build della variante RELEASE firmata, in locale, con le credenziali protette da DPAPI.
#
# Le password non vengono mai stampate: si leggono dal file cifrato creato da
# Import-ReleaseKey.ps1 (opzione A) e restano solo nelle variabili d'ambiente del processo
# di build. Nessuna pubblicazione: l'APK resta in app\build\outputs\apk\release.
#
# Uso:  powershell -ExecutionPolicy Bypass -File _tmp_build_release_with_client_id.ps1
$ErrorActionPreference = 'Stop'
$repoRoot = 'D:\VSCODE\Progetti\StreamNova'
$credFile = Join-Path $env:LOCALAPPDATA 'StreamNovaSigning\release-cred.dpapi.json'

if (-not (Test-Path -LiteralPath $credFile)) { throw "credenziali DPAPI non trovate: $credFile" }
$cred = Get-Content -LiteralPath $credFile -Raw | ConvertFrom-Json

function ConvertFrom-SecureStringPlain([string]$cipher) {
  $secure = ConvertTo-SecureString -String $cipher
  $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
  try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
  finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

$env:KEYSTORE_PATH = $cred.keystorePath
$env:STORE_PASSWORD = ConvertFrom-SecureStringPlain $cred.storeSecret
$env:KEY_PASSWORD = ConvertFrom-SecureStringPlain $cred.keySecret

Write-Host "== credenziali caricate (keystore: $(Split-Path -Leaf $cred.keystorePath), alias: $($cred.alias)) =="
Write-Host "== certificato atteso: $($cred.sha256.Substring(0,16))... =="

try {
  Push-Location $repoRoot
  & .\gradlew.bat :app:assembleRelease --console=plain
  $code = $LASTEXITCODE
} finally {
  Pop-Location
  $env:STORE_PASSWORD = $null
  $env:KEY_PASSWORD = $null
  $env:KEYSTORE_PATH = $null
}
Write-Host "== assembleRelease exit code: $code =="
exit $code
