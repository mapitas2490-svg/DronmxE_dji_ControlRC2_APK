$shell = New-Object -ComObject Shell.Application
$pc = $shell.Namespace(17)
$rc2 = $pc.Items() | Where-Object { $_.Name -like '*DJI RC 2*' -or $_.Name -like '*RC 2*' }
$rc2Folder = $rc2.GetFolder
function Find-Folder($f, $n) { 
    foreach ($i in $f.Items()) { 
        if ($i.IsFolder -and $i.Name -ieq $n) { return $i.GetFolder } 
    } 
    return $null 
}
$storage = Find-Folder $rc2Folder 'Almacenamiento interno compartido'
$android = Find-Folder $storage 'Android'
$data = Find-Folder $android 'data'
$djigo = Find-Folder $data 'dji.go.v5'
$files = Find-Folder $djigo 'files'
$wp = Find-Folder $files 'waypoint'
$realSlot = Find-Folder $wp 'DF5F9C06-1155-4737-9376-B36B0DD6E9F8'

$tempDir = "C:\Users\edgar\AppData\Local\Temp\check_dji_wp"
if (Test-Path $tempDir) { Remove-Item $tempDir -Recurse -Force }
New-Item -ItemType Directory -Path $tempDir | Out-Null

$kmzItem = $realSlot.Items() | Where-Object { $_.Name -like '*.kmz' }
Write-Output "Found in slot: $($kmzItem.Name)"

$destFolder = $shell.Namespace($tempDir)
$destFolder.CopyHere($kmzItem, 16)
Start-Sleep -Seconds 3

$copiedFile = Get-ChildItem -Path $tempDir -Filter "*.kmz"
if ($copiedFile) {
    Write-Output "Copied to PC: $($copiedFile.FullName) ($($copiedFile.Length) bytes)"
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($copiedFile.FullName)
    foreach ($e in $zip.Entries) {
        Write-Output "   Entry: $($e.FullName) ($($e.Length) bytes)"
    }
    $waylines = $zip.Entries | Where-Object { $_.Name -like '*waylines*' }
    if ($waylines) {
        $stream = $waylines.Open()
        $reader = New-Object System.IO.StreamReader($stream)
        $txt = $reader.ReadToEnd()
        $reader.Close()
        Write-Output "Waylines length in DJI Fly: $($txt.Length)"
        Write-Output "Sample waylines: $($txt.Substring(0, [Math]::Min(300, $txt.Length)))"
    }
    $zip.Dispose()
} else {
    Write-Output "File was NOT copied to PC!"
}
