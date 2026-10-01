$shell = New-Object -ComObject Shell.Application
$pc = $shell.Namespace(17)
$rc2 = $pc.Items() | Where-Object { $_.Name -like '*DJI RC 2*' -or $_.Name -like '*RC 2*' }
$rc2Folder = $rc2.GetFolder
function Find-Folder($f, $n) { 
    foreach ($i in $f.Items()) { 
        if ($i.IsFolder -and $i.Name -ieq $n) { 
            return $i.GetFolder 
        } 
    }
    return $null 
}
$storage = Find-Folder $rc2Folder 'Almacenamiento interno compartido'
if (-not $storage) { $storage = Find-Folder $rc2Folder 'Internal shared storage' }
$h = Find-Folder $storage 'helpers'
if ($h) {
    Write-Output "=== Items in helpers ==="
    foreach ($item in $h.Items()) {
        Write-Output "File: $($item.Name) ($($item.Size))"
    }
}
