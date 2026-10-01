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
$wp = Find-Folder $storage '.waypoint'
if ($wp) {
    foreach ($item in $wp.Items()) {
        if ($item.Name -like '*history*') {
            $shellDest = $shell.Namespace($PSScriptRoot)
            $shellDest.CopyHere($item, 16)
            Write-Output "Copied history file"
        }
    }
}
