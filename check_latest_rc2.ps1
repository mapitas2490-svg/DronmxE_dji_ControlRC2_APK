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

$dl = Find-Folder $storage 'Download'
Write-Output "=== Latest files in Download ==="
foreach ($i in $dl.Items()) {
    Write-Output "File: $($i.Name) ($($i.Size) bytes) modified $($i.ModifyDate)"
}

$helpers = Find-Folder $storage 'helpers'
Write-Output "=== Latest files in helpers ==="
foreach ($i in $helpers.Items()) {
    Write-Output "File: $($i.Name) ($($i.Size) bytes) modified $($i.ModifyDate)"
}
