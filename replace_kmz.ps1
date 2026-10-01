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

$kmzItem = $realSlot.Items() | Where-Object { $_.Name -like '*.kmz' }
if ($kmzItem) {
    Write-Output "Deleting old KMZ on RC 2: $($kmzItem.Name)..."
    # To delete silently without UI confirmation dialog:
    $deleteVerb = $kmzItem.Verbs() | Where-Object { $_.Name -match 'Eliminar|delete' }
    if ($deleteVerb) {
        Write-Output "Invoking $($deleteVerb.Name)"
        $deleteVerb.DoIt()
        Start-Sleep -Seconds 2
    }
}

Write-Output "Items in real slot after delete attempt:"
foreach ($item in $realSlot.Items()) {
    Write-Output "   -> $($item.Name)"
}

$remaining = $realSlot.Items() | Where-Object { $_.Name -like '*.kmz' }
if (-not $remaining) {
    Write-Output "Old KMZ successfully deleted! Now copying Mision_10-01 as DF5F9C06-1155-4737-9376-B36B0DD6E9F8.kmz..."
    $srcKmz = Join-Path $PSScriptRoot "Mision_10-01.kmz"
    $tempKmz = Join-Path $PSScriptRoot "DF5F9C06-1155-4737-9376-B36B0DD6E9F8.kmz"
    Copy-Item -Path $srcKmz -Destination $tempKmz -Force
    
    $realSlot.CopyHere($tempKmz, 16)
    Start-Sleep -Seconds 3
    
    Write-Output "Items in real slot after copy:"
    foreach ($item in $realSlot.Items()) {
        Write-Output "   -> $($item.Name)"
    }
}
