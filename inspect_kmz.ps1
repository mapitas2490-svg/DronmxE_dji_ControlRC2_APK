$zipPath = Join-Path $PSScriptRoot "Mision_10-01.kmz"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
Write-Output "=== Entries inside Mision_10-01.kmz ==="
foreach ($entry in $zip.Entries) {
    Write-Output "File: $($entry.FullName) - Size: $($entry.Length)"
}
$waylines = $zip.Entries | Where-Object { $_.Name -like '*waylines*' }
if ($waylines) {
    $stream = $waylines.Open()
    $reader = New-Object System.IO.StreamReader($stream)
    $text = $reader.ReadToEnd()
    $reader.Close()
    Write-Output "=== First 500 chars of waylines.wpml ==="
    Write-Output $text.Substring(0, [Math]::Min(500, $text.Length))
}
$zip.Dispose()
