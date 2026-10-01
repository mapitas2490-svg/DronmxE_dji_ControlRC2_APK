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

$item = $realSlot.Items() | Where-Object { $_.Name -like '*.kmz' }
if ($item) {
    $dest = Join-Path $PSScriptRoot "test_from_djifly.kmz"
    if (Test-Path $dest) { Remove-Item $dest -Force }
    $shellDest = $shell.Namespace($PSScriptRoot)
    $shellDest.CopyHere($item, 16)
    Start-Sleep -Seconds 3
    Write-Output "File in DJI Fly slot size: $((Get-Item $dest).Length) bytes"
}
