# P0/P1 (25-10-2026) — esecuzione del collaudo su Fire TV fisica (AFTMA08C15 / mt8696).
#
# Fa girare SeekPreviewRemoteScriptTest, che guida lo scrubbing via InputManager (adb shell
# input keyevent costerebbe ~2 s per iniezione e spezzerebbe la sessione di scrubbing), e
# raccoglie in parallelo il logcat completo + gli screenshot.

$adb = "C:\Users\miciuss\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$serial = "192.168.1.2:5555"
$out = "D:\VSCODE\Progetti\StreamNova\p0fix_verify"
$pkg = "com.aistudio.streamnova.tvapp"
$runner = "$pkg.test/androidx.test.runner.AndroidJUnitRunner"

if (!(Test-Path $out)) { New-Item -ItemType Directory -Force -Path $out | Out-Null }

Write-Output "== 0. dispositivo =="
& $adb connect $serial | Out-Null
& $adb devices
& $adb -s $serial shell "getprop ro.product.model; getprop ro.build.version.sdk; getprop ro.board.platform"

Write-Output "== 1. nessun override manuale (deve decidere la piattaforma) =="
& $adb -s $serial shell "run-as $pkg ls -la files 2>/dev/null | head -20"

Write-Output "== 2. pulizia stato =="
& $adb -s $serial shell "am force-stop $pkg"
& $adb -s $serial shell "rm -f /sdcard/direct_frame.png /sdcard/p0fix.mp4"
& $adb -s $serial logcat -c
& $adb -s $serial logcat -b crash -c

Write-Output "== 3. avvio cattura logcat e screenrecord =="
$logcat = Start-Process -FilePath $adb -ArgumentList "-s", $serial, "logcat", "-v", "time" -NoNewWindow -PassThru -RedirectStandardOutput "$out\logcat_raw.txt"
$rec = Start-Process -FilePath $adb -ArgumentList "-s", $serial, "shell", "screenrecord", "--time-limit", "300", "--size", "1280x720", "/sdcard/p0fix.mp4" -PassThru
Start-Sleep -Seconds 3

Write-Output "== 4. esecuzione dello script di collaudo =="
& $adb -s $serial shell "am instrument -w -r -e class com.example.SeekPreviewRemoteScriptTest#scrubbingRemoteScript $runner" > "$out\instrument.txt" 2>&1
Write-Output "   instrument completato (exit=$LASTEXITCODE)"

Write-Output "== 5. raccolta =="
& $adb -s $serial shell "pkill -2 screenrecord" | Out-Null
Start-Sleep -Seconds 5
if (!$rec.HasExited) { Stop-Process -Id $rec.Id -Force -ErrorAction SilentlyContinue }
Stop-Process -Id $logcat.Id -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2
& $adb -s $serial pull /sdcard/p0fix.mp4 "$out\p0fix.mp4" | Out-Null
& $adb -s $serial pull /sdcard/direct_frame.png "$out\direct_frame_last.png" 2>$null | Out-Null
& $adb -s $serial exec-out run-as $pkg tar -c files/seekproof > "$out\seekproof.tar" 2>$null
Push-Location $out
tar -xf seekproof.tar 2>$null
Pop-Location
& $adb -s $serial logcat -d -v time > "$out\logcat_full.txt"
& $adb -s $serial logcat -b crash -d -v time > "$out\crash_buffer.txt"
Write-Output "   PID dopo il collaudo: " (& $adb -s $serial shell "pidof $pkg")
Write-Output "== done =="
