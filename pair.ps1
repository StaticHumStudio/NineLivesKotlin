$code = "703995"
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = "C:\Users\jwcop\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$psi.Arguments = "pair 100.125.110.103:37855"
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true

$process = [System.Diagnostics.Process]::Start($psi)
Start-Sleep -Milliseconds 500
$process.StandardInput.WriteLine($code)
$process.StandardInput.Close()
$process.WaitForExit()

Write-Host $process.StandardOutput.ReadToEnd()
Write-Host $process.StandardError.ReadToEnd()
