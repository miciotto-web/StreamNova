# P0/P1 (25-10-2026) — verifica DEFINITIVA dello scrubbing su Fire TV fisica.
#
# Copre il piano di verifica richiesto:
#   A.1 scrubbing in avanti per piu posizioni
#   A.2 scrubbing allindietro per piu posizioni
#   A.3 alternanza avanti/indietro senza chiudere loverlay
#   A.4 movimento rapido seguito da arresto improvviso
#   A.5 attesa dopo larresto (frame finale)
#   A.6 conferma con OK e annullamento con BACK
#
# Per ogni sequenza raccoglie: posizione richiesta, PTS decodificato, timestamp mostrato,
# latenza, hash, direzione, cache hit/miss, motivo di rifiuto (righe "frame:" del controller),
# piu screenshot e screenrecord.
#
# Stato atteso: app GIA in riproduzione (HUD nascosto), sorgente HLS che riproduce il problema.

$adb = "C:\Users\miciuss\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$serial = "192.168.1.2:5555"
$out = "D:\VSCODE\Progetti\StreamNova\p0fix_verify"

if (!(Test-Path $out)) { New-Item -ItemType Directory -Force -Path $out | Out-Null }

function Sz([string]$s) { & $adb -s $serial shell $s }
function Shot([string]$name) {
    Sz "screencap -p /sdcard/$name.png" | Out-Null
    & $adb -s $serial pull "/sdcard/$name.png" "$out\$name.png" | Out-Null
}

Write-Host "0. Stato del device e processo corrente"
& $adb -s $serial connect $serial | Out-Null
$pidBefore = (Sz "pidof com.aistudio.streamnova.tvapp").Trim()
Write-Host ("   PID prima: " + $pidBefore)
Sz "rm -f /sdcard/p0fix.mp4 /sdcard/direct_frame.png" | Out-Null
& $adb -s $serial logcat -c

Write-Host "1. screenrecord + logcat puliti"
$rec = Start-Process -FilePath $adb -ArgumentList "-s", $serial, "shell", "screenrecord", "--time-limit", "180", "--size", "1280x720", "/sdcard/p0fix.mp4" -PassThru
Start-Sleep -Seconds 2

Write-Host "2. A.1 - 10 AVANTI (600 ms tra pressioni, come un utente reale)"
for ($i = 1; $i -le 10; $i++) {
    Sz "input keyevent 22" | Out-Null
    Start-Sleep -Milliseconds 600
    if ($i -eq 5) { Shot "a1_forward_mid" }
}
Shot "a1_forward_end"
Start-Sleep -Seconds 3
Shot "a1_forward_settled"

Write-Host "3. A.2 - 10 INDIETRO"
for ($i = 1; $i -le 10; $i++) {
    Sz "input keyevent 21" | Out-Null
    Start-Sleep -Milliseconds 600
    if ($i -eq 5) { Shot "a2_backward_mid" }
}
Shot "a2_backward_end"
Start-Sleep -Seconds 3
Shot "a2_backward_settled"

Write-Host "4. A.3 - alternanza AVANTI/INDIETRO senza chiudere loverlay"
foreach ($key in @(22, 22, 21, 21, 21, 22, 21, 22, 22, 22)) {
    Sz "input keyevent $key" | Out-Null
    Start-Sleep -Milliseconds 600
}
Shot "a3_alternating"
Start-Sleep -Seconds 3
Shot "a3_alternating_settled"

Write-Host "5. A.4 - movimento rapido (250 ms) poi arresto improvviso"
for ($i = 1; $i -le 12; $i++) {
    Sz "input keyevent 22" | Out-Null
    Start-Sleep -Milliseconds 250
}
Shot "a4_fast_stop"
Write-Host "   A.5 - attesa dopo larresto: il frame finale deve assestarsi"
Start-Sleep -Seconds 6
Shot "a5_final_frame"

Write-Host "6. A.6a - conferma con OK"
Sz "input keyevent 23" | Out-Null
Start-Sleep -Seconds 5
Shot "a6_after_ok"

Write-Host "7. A.6b - 4 AVANTI poi BACK (annullamento)"
for ($i = 1; $i -le 4; $i++) {
    Sz "input keyevent 22" | Out-Null
    Start-Sleep -Milliseconds 500
}
Shot "a7_before_back"
Sz "input keyevent 4" | Out-Null
Start-Sleep -Seconds 5
Shot "a7_after_back"

Write-Host "8. Raccolta evidenze"
Sz "pkill -2 screenrecord" | Out-Null
Start-Sleep -Seconds 4
if (!$rec.HasExited) { Stop-Process -Id $rec.Id -Force -ErrorAction SilentlyContinue }
& $adb -s $serial pull /sdcard/p0fix.mp4 "$out\p0fix.mp4" | Out-Null
& $adb -s $serial pull /sdcard/direct_frame.png "$out\direct_frame_last.png" 2>$null | Out-Null
& $adb -s $serial logcat -d -v time > "$out\p0fix_logcat.txt"
& $adb -s $serial logcat -b crash -d -v time > "$out\p0fix_crash.txt"
$pidAfter = (Sz "pidof com.aistudio.streamnova.tvapp").Trim()

Write-Host "9. Estrazione dei fotogrammi dal video (2 fps)"
if (!(Test-Path "$out\frames")) { New-Item -ItemType Directory -Force -Path "$out\frames" | Out-Null }
ffmpeg -y -i "$out\p0fix.mp4" -vf "fps=2" "$out\frames\frame_%03d.png" 2>$null | Out-Null

Write-Host ""
Write-Host "===================== ESITO ====================="
$log = "$out\p0fix_logcat.txt"
$frames = Select-String -Path $log -Pattern 'SeekPreview: frame:'
Write-Host ("   fotogrammi pubblicati      : " + (($frames | Where-Object { $_.Line -match 'pubblicato=si' }).Count))
Write-Host ("   fotogrammi rifiutati       : " + (($frames | Where-Object { $_.Line -match 'pubblicato=no' }).Count))
Write-Host ("   render approssimazione=true: " + ((Select-String -Path $log -Pattern 'render:.*approssimazione=true').Count))
Write-Host ("   cache-hit                  : " + ((Select-String -Path $log -Pattern 'cache=hit').Count))
Write-Host ("   cache-miss                 : " + ((Select-String -Path $log -Pattern 'cache=miss').Count))
Write-Host ("   richieste avanti           : " + ((Select-String -Path $log -Pattern 'verso=avanti').Count))
Write-Host ("   richieste indietro         : " + ((Select-String -Path $log -Pattern 'verso=indietro').Count))
Write-Host ("   conferme OK                : " + ((Select-String -Path $log -Pattern 'Scrubbing confermato').Count))
Write-Host ("   annullamenti BACK          : " + ((Select-String -Path $log -Pattern 'Scrubbing annullato').Count))
Write-Host ("   ImageReader creata         : " + ((Select-String -Path $log -Pattern 'ImageReader pronta').Count))
Write-Host ("   log plane (copyPlanes)     : " + ((Select-String -Path $log -Pattern 'planes: format').Count))
Write-Host ("   SIGABRT/JNI/Fatal signal   : " + ((Select-String -Path $log -Pattern 'SIGABRT|JNI DETECTED ERROR|Fatal signal|nativeCreatePlanes').Count))
Write-Host ("   righe nel crash buffer     : " + ((Get-Content "$out\p0fix_crash.txt" | Measure-Object).Count))
Write-Host ("   PID prima / dopo           : $pidBefore / $pidAfter")
Write-Host "================================================="
Write-Host "Evidenze in: $out"

