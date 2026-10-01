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

# Prepare DF5F9C06-1155-4737-9376-B36B0DD6E9F8.kmz from Mision_10-01.kmz
$srcKmz = Join-Path $PSScriptRoot "Mision_10-01.kmz"
$targetName = "DF5F9C06-1155-4737-9376-B36B0DD6E9F8.kmz"
$tempKmz = Join-Path $PSScriptRoot $targetName
Copy-Item -Path $srcKmz -Destination $tempKmz -Force
Write-Output "Prepared source KMZ: $tempKmz ($(Get-Item $tempKmz | Select-Object -ExpandProperty Length) bytes)"

# Copy into real slot
Write-Output "Copying into DJI Fly real slot..."
$realSlot.CopyHere($tempKmz, 16)
Start-Sleep -Seconds 4

Write-Output "=== Items now in DJI Fly slot ==="
foreach ($i in $realSlot.Items()) {
    Write-Output "   -> $($i.Name) ($($i.Size) bytes)"
}

# Now delete the backup DF5F9C06_old.kmz
$oldItem = $realSlot.Items() | Where-Object { $_.Name -eq 'DF5F9C06_old.kmz' }
if ($oldItem) {
    try {
        $oldItem.Name = ".trash_old"
    } catch {}
}

Write-Output "✅ Inyección de Mision_10-01 completada exitosamente en DJI Fly!"
