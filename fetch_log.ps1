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

$helpers = Find-Folder $storage 'helpers'
if ($helpers) {
    Write-Output "=== Inside /helpers ==="
    foreach ($item in $helpers.Items()) {
        Write-Output "Name: $($item.Name) | Date: $($item.ModifyDate)"
        if ($item.Name -like '*log*') {
            $shellDest = $shell.Namespace($PSScriptRoot)
            $shellDest.CopyHere($item, 16)
            Write-Output "COPIED $($item.Name) to local directory"
        }
    }
}

$waypoint = Find-Folder $storage '.waypoint'
if ($waypoint) {
    Write-Output "=== Inside /.waypoint ==="
    foreach ($item in $waypoint.Items()) {
        Write-Output "Name: $($item.Name) | IsFolder: $($item.IsFolder)"
    }
}

$djigov5 = Find-Folder $storage '.dji.go.v5'
if ($djigov5) {
    Write-Output "=== Inside /.dji.go.v5 ==="
    foreach ($item in $djigov5.Items()) {
        Write-Output "Name: $($item.Name) | IsFolder: $($item.IsFolder)"
    }
}
