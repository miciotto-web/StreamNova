$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$outDir = "d:\VSCODE\Progetti\StreamNova\collaudo_seek_full"
if (Test-Path $outDir) { Remove-Item -Recurse -Force $outDir }
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

Write-Host "1. Attesa auto-hide HUD (6 secondi)..."
Start-Sleep -Seconds 6

Write-Host "2. Pulizia logcat e avvio screenrecord..."
& $adb logcat -c
& $adb shell rm -f /sdcard/scrub_run.mp4
# Avvio screenrecord in background tramite Start-Process
$recProc = Start-Process -FilePath $adb -ArgumentList "shell", "screenrecord", "--size", "1280x720", "--bit-rate", "4000000", "/sdcard/scrub_run.mp4" -PassThru
Start-Sleep -Milliseconds 800

Write-Host "3. Inizio sequenza scrubbing: 10 pressioni AVANTI (ogni 450ms)..."
$timestamps = @()
for ($i = 1; $i -le 10; $i++) {
    $t = [System.Diagnostics.Stopwatch]::GetTimestamp()
    & $adb shell input keyevent 22 # KEYCODE_DPAD_RIGHT
    $timestamps += "FWD $i at $t"
    Start-Sleep -Milliseconds 450
}

Write-Host "4. Sequenza scrubbing: 10 pressioni INDIETRO (ogni 450ms)..."
for ($i = 1; $i -le 10; $i++) {
    $t = [System.Diagnostics.Stopwatch]::GetTimestamp()
    & $adb shell input keyevent 21 # KEYCODE_DPAD_LEFT
    $timestamps += "BWD $i at $t"
    Start-Sleep -Milliseconds 450
}

Write-Host "5. Pausa di 400ms prima di OK (overlay ancora aperto)..."
Start-Sleep -Milliseconds 400

Write-Host "6. Conferma seek con OK (DPAD_CENTER)..."
& $adb shell input keyevent 23 # DPAD_CENTER
Start-Sleep -Seconds 3

Write-Host "7. Test BACK: avvio nuovo scrubbing (5 pressioni avanti)..."
for ($i = 1; $i -le 5; $i++) {
    & $adb shell input keyevent 22 # DPAD_RIGHT
    Start-Sleep -Milliseconds 450
}
Start-Sleep -Milliseconds 200
Write-Host "Annullamento scrubbing con BACK..."
& $adb shell input keyevent 4 # KEYCODE_BACK
Start-Sleep -Seconds 3

Write-Host "8. Stop screenrecord..."
& $adb shell pkill -INT screenrecord
Start-Sleep -Seconds 2
if (!$recProc.HasExited) { Stop-Process -Id $recProc.Id -Force -ErrorAction SilentlyContinue }

Write-Host "9. Salvataggio logcat e registrazione video..."
& $adb logcat -d -s SeekPreview SeekPreviewSrc PlayerScrub PlayerScreen > "$outDir\logcat.txt"
& $adb pull /sdcard/scrub_run.mp4 "$outDir\scrub_run.mp4"

Write-Host "10. Estrazione fotogrammi video (1 frame ogni 0.5s)..."
& ffmpeg -i "$outDir\scrub_run.mp4" -vf "fps=2" "$outDir\frame_%03d.png" -y

Write-Host "Collaudo completato con successo."
