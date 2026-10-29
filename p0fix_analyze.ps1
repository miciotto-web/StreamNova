# P0/P1 (25-10-2026) — analisi delle evidenze raccolte su Fire TV fisica (p0fix_verify\).
# Estrae i tre gruppi richiesti dal piano di collaudo A.1–A.6:
#   (1) gate di regressione: nessun crash nativo, nessuna ImageReader creata, PID stabile;
#   (2) evidenza dei frame: pos richiesta vs PTS vs pos mostrata, hash, cache, scarti;
#   (3) coerenza temporale: direzione dell'anteprima rispetto alla direzione richiesta.

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$dir = "D:\VSCODE\Progetti\StreamNova\p0fix_verify"

Write-Output "=== FILES ==="
Get-ChildItem $dir | Select-Object Name, Length | Format-Table -AutoSize | Out-String

Write-Output "=== (1) GATE DI REGRESSIONE ==="
$pat = @('SIGABRT', 'JNI DETECTED ERROR', 'Fatal signal', 'nativeCreatePlanes',
         'ImageReader pronta', 'planes: format', 'ANR in com.aistudio')
foreach ($p in $pat) {
  $c = (Select-String -Path "$dir\logcat_full.txt" -Pattern $p -ErrorAction SilentlyContinue | Measure-Object).Count
  Write-Output ("  {0,-26} {1}" -f $p, $c)
}
$crash = (Get-Content "$dir\crash_buffer.txt" -ErrorAction SilentlyContinue | Measure-Object -Line).Lines
Write-Output ("  righe nel buffer crash:   {0}" -f $crash)

Write-Output ""
Write-Output "=== (2) FRAME: pubblicazioni e scarti ==="
$frames = Select-String -Path "$dir\logcat_full.txt" -Pattern 'frame: ' -ErrorAction SilentlyContinue |
          ForEach-Object { $_.Line.Trim() }
Write-Output ("  righe 'frame:': {0}" -f ($frames | Measure-Object).Count)
$frames | Select-Object -First 60

Write-Output ""
Write-Output "=== render (frame effettivamente mostrati) ==="
$renders = Select-String -Path "$dir\logcat_full.txt" -Pattern 'render: pos=' -ErrorAction SilentlyContinue |
           ForEach-Object { $_.Line.Trim() }
Write-Output ("  render: {0}" -f ($renders | Measure-Object).Count)
$renders | Select-Object -First 40

Write-Output ""
Write-Output "=== (3) SESSIONI DI SCRUBBING ==="
Select-String -Path "$dir\logcat_full.txt" -Pattern 'Scrubbing avviato|Scrubbing confermato' -ErrorAction SilentlyContinue |
  ForEach-Object { $_.Line.Trim() }
