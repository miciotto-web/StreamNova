# Hash percettivo (average-hash 12x8) degli screenshot di verifica: conferma numericamente
# che le scene mostrate a schermo cambiarono davvero fra le posizioni, in particolare andando
# a ritroso (Bug 2) — gli screenshot sono la proiezione reale del contenuto overlay.
Add-Type -AssemblyName System.Drawing
$dir = "D:\VSCODE\Progetti\StreamNova\p0fix_verify"
$files = Get-ChildItem $dir -Filter 's0*.png' | Sort-Object Name
foreach ($f in $files) {
  $bmp = [System.Drawing.Bitmap]::new($f.FullName)
  $w = $bmp.Width; $h = $bmp.Height
  # scala 12x8 per quantizzare il contenuto in modo grosso e stabile
  $small = New-Object 'double[,]' 12,8
  for ($y=0; $y -lt 8; $y++) {
    for ($x=0; $x -lt 12; $x++) {
      # campiona il centro della cella (evita bordi/gradiente del velo inferiore)
      $sx = [int](($x + 0.5) * $w / 12)
      $sy = [int](($y + 0.5) * $h * 0.8 / 8)
      $px = $bmp.GetPixel($sx, $sy)
      $small[$x,$y] = 0.299*$px.R + 0.587*$px.G + 0.114*$px.B
    }
  }
  $flat = @(); $mean = 0.0
  for ($y=0; $y -lt 8; $y++) { for ($x=0; $x -lt 12; $x++) { $flat += $small[$x,$y]; $mean += $small[$x,$y] } }
  $mean /= $flat.Count
  $hash = ($flat | ForEach-Object { if ($_ -ge $mean) {'1'} else {'0'} }) -join ''
  $hex = ''
  for ($i=0; $i -lt $hash.Length; $i+=4) { $hex += '{0:X}' -f [Convert]::ToInt32($hash.Substring($i,4),2) }
  $lum = [math]::Round(($flat | Measure-Object -Average).Average,1)
  Write-Output ("{0,-22} {1} luminositaMedia={2}" -f $f.Name, $hex, $lum)
  $bmp.Dispose()
}
