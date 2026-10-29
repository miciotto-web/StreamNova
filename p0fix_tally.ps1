$d = 'app/build/test-results/testDebugUnitTest'
$tot = 0; $fail = 0
Get-ChildItem $d -Filter '*.xml' |
  Where-Object { $_.LastWriteTime -gt (Get-Date).AddMinutes(-8) } |
  ForEach-Object {
    $x = [xml](Get-Content $_.FullName -Encoding UTF8)
    $tot += [int]$x.testsuite.tests
    $fail += [int]$x.testsuite.failures + [int]$x.testsuite.errors
    $name = $x.testsuite.name -replace 'com.example.ui.screens.player.', ''
    '{0,-44} tests={1,-3} fail={2}' -f $name, $x.testsuite.tests, $x.testsuite.failures
  }
"--- TOTALE tests=$tot falliti=$fail"
