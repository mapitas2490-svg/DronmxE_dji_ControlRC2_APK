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
if (-not $storage) { $storage = Find-Folder $rc2Folder 'Internal shared storage' }

$dl = Find-Folder $storage 'Download'
if ($dl) {
    $item = $dl.Items() | Where-Object { $_.Name -like '*10-01*' -or $_.Name -like '*10_01*' }
    if ($item) {
        Write-Output "Found in Download: $($item.Name)"
        $dest = Join-Path $PSScriptRoot "Mision_10-01.kmz"
        $shellDest = $shell.Namespace($PSScriptRoot)
        $shellDest.CopyHere($item, 16)
        Start-Sleep -Seconds 2
        Write-Output "Copied to $dest ($(Get-Item $dest | Select-Object -ExpandProperty Length) bytes)"
    }
}
