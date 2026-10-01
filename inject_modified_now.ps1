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

# Source is the newly modified 80KB mission
$srcKmz = "C:\Users\edgar\AppData\Local\Temp\check_modified\mision_fotogrametria.kmz"
if (-not (Test-Path $srcKmz)) {
    # fallback to local
    $srcKmz = Join-Path $PSScriptRoot "Mision_10-01.kmz"
}
$targetName = "DF5F9C06-1155-4737-9376-B36B0DD6E9F8.kmz"
$tempKmz = Join-Path $PSScriptRoot $targetName
Copy-Item -Path $srcKmz -Destination $tempKmz -Force
Write-Output "Prepared modified KMZ: $tempKmz ($(Get-Item $tempKmz | Select-Object -ExpandProperty Length) bytes)"

# Rename current KMZ in slot so MTP doesn't skip copying
$currentKmz = $realSlot.Items() | Where-Object { $_.Name -eq $targetName }
if ($currentKmz) {
    try {
        $currentKmz.Name = "DF5F9C06_backup.kmz"
        Start-Sleep -Seconds 1
    } catch {}
}

# Copy newly modified KMZ into DJI Fly slot
Write-Output "Copying modified mission to DJI Fly slot..."
$realSlot.CopyHere($tempKmz, 16)
Start-Sleep -Seconds 4

# Remove backup
$backup = $realSlot.Items() | Where-Object { $_.Name -eq 'DF5F9C06_backup.kmz' }
if ($backup) {
    try { $backup.Name = ".trash" } catch {}
}

Write-Output "=== Items currently in DJI Fly slot ==="
foreach ($i in $realSlot.Items()) {
    Write-Output "   -> $($i.Name) ($($i.Size) bytes)"
}

Write-Output "✅ ¡Misión modificada inyectada exitosamente en DJI Fly!"
