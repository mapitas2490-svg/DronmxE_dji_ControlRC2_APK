$shell = New-Object -ComObject Shell.Application
$pc = $shell.Namespace(17)
$rc2 = $pc.Items() | Where-Object { $_.Name -like '*DJI RC 2*' -or $_.Name -like '*RC 2*' }
if ($rc2) {
    $rc2Folder = $rc2.GetFolder
    function Find-Folder($f, $n) { 
        foreach ($i in $f.Items()) { 
            if ($i.IsFolder -and $i.Name -ieq $n) { return $i.GetFolder } 
        } 
        return $null 
    }
    $storage = Find-Folder $rc2Folder 'Almacenamiento interno compartido'
    if (-not $storage) { $storage = Find-Folder $rc2Folder 'Internal shared storage' }
    
    $android = Find-Folder $storage 'Android'
    $data = Find-Folder $android 'data'
    $djigo = Find-Folder $data 'dji.go.v5'
    $files = Find-Folder $djigo 'files'
    $wp = Find-Folder $files 'waypoint'
    $realSlot = Find-Folder $wp 'DF5F9C06-1155-4737-9376-B36B0DD6E9F8'
    
    if ($realSlot) {
        Write-Output "Found target DJI Fly slot: $($realSlot.Title)"
        
        # Prepare the file named DF5F9C06-1155-4737-9376-B36B0DD6E9F8.kmz from Mision_10-01.kmz
        $srcKmz = Join-Path $PSScriptRoot "Mision_10-01.kmz"
        $tempKmz = Join-Path $PSScriptRoot "DF5F9C06-1155-4737-9376-B36B0DD6E9F8.kmz"
        Copy-Item -Path $srcKmz -Destination $tempKmz -Force
        
        # Copy to the real slot on RC 2
        Write-Output "Injecting into DJI Fly via MTP..."
        $realSlot.CopyHere($tempKmz, 16)
        Start-Sleep -Seconds 3
        
        Write-Output "=== Items currently in real slot ==="
        foreach ($item in $realSlot.Items()) {
            Write-Output "File: $($item.Name) ($($item.Size) bytes)"
        }
        Write-Output "✅ ¡Misión Mision_10-01 inyectada con éxito en DJI Fly!"
    } else {
        Write-Output "Target slot not found in Android/data/dji.go.v5/files/waypoint"
    }
}
