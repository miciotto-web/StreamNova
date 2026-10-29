# HOTFIX P0 (24-10-2026) - verifica completa dello scrubbing su Fire TV fisica.
#
# Stato atteso: app GIA in riproduzione (HUD nascosto).
# Verifica: nessun crash nativo, nessuna ImageReader creata, scrubbing avanti/indietro,
# conferma con OK, annullamento con BACK, riproduzione ripresa + avvio pulito finale.

$adb = "C:\Users\miciuss\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$serial = "192.168.1.2:5555"
$out = "D:\VSCODE\Progetti\StreamNova"

function Sz([string]$s) { & $adb -s $serial shell $s }
function Shot([string]$name) {
    Sz "screencap -p /sdcard/$name.png" | Out-Null
    & $adb -s $serial pull "/sdcard/$name.png" "$out\$name.png" | Out-Null
}

Write-Host "0. Gate log della composizione corrente (buffer prima della pulizia)..."
& $adb -s $serial logcat -d -v time > "$out\hotfix_logcat_session.txt"

Write-Host "1. Attesa auto-hide HUD (8 s)..."
Start-Sleep -Seconds 8
Shot "hotfix_21_clean_video"

Write-Host "2. logcat pulito + screenrecord..."
& $adb -s $serial logcat -c
Sz "rm -f /sdcard/hotfix_scrub2.mp4" | Out-Null
$rec = Start-Process -FilePath $adb -ArgumentList "-s", $serial, "shell", "screenrecord", "--size", "1280x720", "/sdcard/hotfix_scrub2.mp4" -PassThru
Start-Sleep -Seconds 2

Write-Host "3. FASE 1 - 12 AVANTI (600 ms)..."
for ($i = 1; $i -le 12; $i++) {
    Sz "input keyevent 22" | Out-Null
    Start-Sleep -Milliseconds 600
    if ($i -eq 5) { Shot "hotfix_22_scrub_forward" }
}

Write-Host "4. FASE 2 - 12 INDIETRO (600 ms)..."
for ($i = 1; $i -le 12; $i++) {
    Sz "input keyevent 21" | Out-Null
    Start-Sleep -Milliseconds 600
    if ($i -eq 5) { Shot "hotfix_23_scrub_backward" }
}

Write-Host "5. FASE 3 - conferma con OK..."
Shot "hotfix_24_scrub_open"
Sz "input keyevent 23" | Out-Null
Start-Sleep -Seconds 5
Shot "hotfix_25_after_ok"
Start-Sleep -Seconds 6

Write-Host "6. FASE 4 - 4 AVANTI + BACK (annullamento)..."
for ($i = 1; $i -le 4; $i++) {
    Sz "input keyevent 22" | Out-Null
    Start-Sleep -Milliseconds 500
}
Shot "hotfix_26_scrub_back"
Sz "input keyevent 4" | Out-Null
Start-Sleep -Seconds 4
Shot "hotfix_27_after_back"

Write-Host "7. Stop screenrecord + raccolta log della sessione..."
Sz "pkill -2 screenrecord" | Out-Null
Start-Sleep -Seconds 4
if (!$rec.HasExited) { Stop-Process -Id $rec.Id -Force -ErrorAction SilentlyContinue }
& $adb -s $serial pull /sdcard/hotfix_scrub2.mp4 "$out\hotfix_scrub2.mp4" | Out-Null
& $adb -s $serial logcat -d -v time > "$out\hotfix_logcat_scrub.txt"
& $adb -s $serial logcat -b crash -d -v time > "$out\hotfix_logcat_crash.txt"

Write-Host "8. Uscita e rientro nel player (avvio/ripresa normali)..."
Sz "input keyevent 4" | Out-Null      # BACK: esce dal player verso il dettaglio
Start-Sleep -Seconds 4
& $adb -s $serial logcat -c
Sz "input keyevent 23" | Out-Null     # riprende la riproduzione dal dettaglio
Start-Sleep -Seconds 28
Shot "hotfix_28_relaunch_play"
& $adb -s $serial logcat -d -v time > "$out\hotfix_logcat_relaunch.txt"

Write-Host "9. Esito"
$scrub = "$out\hotfix_logcat_scrub.txt"
Write-Host ("   scrubbing avviati      : " + (Select-String -Path $scrub -Pattern "Scrubbing avviato" | Measure-Object).Count)
Write-Host ("   conferme (OK)          : " + (Select-String -Path $scrub -Pattern "Scrubbing confermato" | Measure-Object).Count)
Write-Host ("   annullamenti (BACK)    : " + (Select-String -Path $scrub -Pattern "Scrubbing annullato" | Measure-Object).Count)
Write-Host ("   anteprima non richiedib: " + (Select-String -Path $scrub -Pattern "anteprima non richiedibile" | Measure-Object).Count)
Write-Host ("   ImageReader creata     : " + (Select-String -Path $scrub -Pattern "ImageReader pronta" | Measure-Object).Count)
Write-Host ("   log plane (copyPlanes) : " + (Select-String -Path $scrub -Pattern "planes: format" | Measure-Object).Count)
Write-Host ("   SIGABRT/JNI/tombstone  : " + (Select-String -Path $scrub -Pattern "SIGABRT|JNI DETECTED ERROR|Fatal signal|nativeCreatePlanes" | Measure-Object).Count)
Write-Host ("   crash buffer (righe)   : " + ((Get-Content "$out\hotfix_logcat_crash.txt" | Measure-Object).Count))
Write-Host ("   gate alla ripartenza   : " + (Select-String -Path "$out\hotfix_logcat_relaunch.txt" -Pattern "estrazione disattivata" | Measure-Object).Count)
Write-Host ("   decoder avviati (relaun): " + (Select-String -Path "$out\hotfix_logcat_relaunch.txt" -Pattern "MediaCodecLogger" | Measure-Object).Count)
Write-Host ("   PID corrente           : " + (Sz "pidof com.aistudio.streamnova.tvapp"))

Write-Host "10. Fotogrammi dal video (1 fps)..."
if (Test-Path "$out\hotfix_scrub2_frames") { Remove-Item -Recurse -Force "$out\hotfix_scrub2_frames" }
New-Item -ItemType Directory -Force -Path "$out\hotfix_scrub2_frames" | Out-Null
ffmpeg -y -i "$out\hotfix_scrub2.mp4" -vf "fps=1" "$out\hotfix_scrub2_frames\frame_%03d.png" 2>$null

Write-Host "Verifica completata."
