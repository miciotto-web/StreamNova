$adb = "C:\Users\miciuss\AppData\Local\Android\Sdk\platform-tools\adb.exe"

Write-Host "=== STREAMNOVA SEEK PREVIEW EXTRACTOR E2E TEST ==="
Write-Host "Cleaning up old test files..."
& $adb shell "rm -f /sdcard/collaudo_e2e.mp4 /sdcard/test_rec.mp4"
if (Test-Path "collaudo_e2e_frames") {
    Remove-Item -Recurse -Force "collaudo_e2e_frames"
}
New-Item -ItemType Directory -Force -Path "collaudo_e2e_frames" | Out-Null

Write-Host "1. Resuming playback of Below (HLS stream)..."
& $adb shell input keyevent 23
Write-Host "Waiting 7s for stream to play and HUD to auto-hide..."
Start-Sleep -Seconds 7

Write-Host "2. Clearing logcat..."
& $adb logcat -c

Write-Host "3. Starting screenrecord in background..."
$recProc = Start-Process -FilePath $adb -ArgumentList "shell screenrecord --size 1280x720 /sdcard/collaudo_e2e.mp4" -PassThru
Start-Sleep -Seconds 2

Write-Host "--- PHASE 1: 12 Rapid Forward Presses (DPAD_RIGHT, 600ms cadence) ---"
for ($i = 1; $i -le 12; $i++) {
    $now = Get-Date -Format "HH:mm:ss.fff"
    Write-Host "[$now] Forward press #$i"
    & $adb shell input keyevent 22
    Start-Sleep -Milliseconds 600
}

Write-Host "--- PHASE 2: 12 Rapid Backward Presses (DPAD_LEFT, 600ms cadence) ---"
for ($i = 1; $i -le 12; $i++) {
    $now = Get-Date -Format "HH:mm:ss.fff"
    Write-Host "[$now] Backward press #$i"
    & $adb shell input keyevent 21
    Start-Sleep -Milliseconds 600
}

Write-Host "Pausing 650ms to allow final preview decoding..."
Start-Sleep -Milliseconds 650

Write-Host "--- PHASE 3: Confirm Seek (DPAD_CENTER / OK) ---"
$now = Get-Date -Format "HH:mm:ss.fff"
Write-Host "[$now] Confirming seek with DPAD_CENTER (OK)"
& $adb shell input keyevent 23

Write-Host "Waiting 5s for seek completion and playback resumption..."
Start-Sleep -Seconds 5

Write-Host "Waiting 7s for HUD auto-hide..."
Start-Sleep -Seconds 7

Write-Host "--- PHASE 4: BACK Button Scrub Cancellation Test ---"
Write-Host "Scrubbing 4 presses forward and sending KEYCODE_BACK while scrubbing is active..."
$now = Get-Date -Format "HH:mm:ss.fff"
Write-Host "[$now] Sending scrub + cancel sequence"
& $adb shell "input keyevent 22; sleep 0.4; input keyevent 22; sleep 0.4; input keyevent 22; sleep 0.4; input keyevent 22; sleep 0.3; input keyevent 4"

Write-Host "Waiting 4s to observe uninterrupted playback after cancel..."
Start-Sleep -Seconds 4

Write-Host "Stopping screenrecord cleanly via SIGINT..."
& $adb shell "pkill -2 screenrecord"
Write-Host "Waiting 4s for screenrecord to finalize MP4 container..."
Start-Sleep -Seconds 4

if (!$recProc.HasExited) {
    Stop-Process -Id $recProc.Id -Force -ErrorAction SilentlyContinue
}
Start-Sleep -Seconds 1

Write-Host "Pulling screenrecord..."
& $adb pull /sdcard/collaudo_e2e.mp4 collaudo_e2e.mp4

Write-Host "Dumping logcat..."
& $adb logcat -d > collaudo_logcat.txt

Write-Host "Extracting video frames (1 fps)..."
& ffmpeg -y -i collaudo_e2e.mp4 -vf "fps=1" collaudo_e2e_frames/frame_%03d.png

Write-Host "=== TEST SEQUENCE COMPLETE ==="
