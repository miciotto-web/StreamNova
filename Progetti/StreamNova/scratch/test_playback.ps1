$adb = "C:\Users\miciuss\AppData\Local\Android\Sdk\platform-tools\adb.exe"

function Send-Key($keycode) {
    & $adb -s emulator-5554 shell input keyevent $keycode
    Start-Sleep -Milliseconds 400
}

function Dump-Screen() {
    & $adb -s emulator-5554 shell uiautomator dump /sdcard/dump.xml | Out-Null
    $xml = & $adb -s emulator-5554 shell cat /sdcard/dump.xml
    return $xml
}

function Get-Focused-Node() {
    $xml = Dump-Screen
    if ($xml -match 'focused="true"[^>]*bounds="([^"]+)"') {
        return $matches[1]
    }
    return ""
}

Write-Host "=== TEST STARTED ==="
