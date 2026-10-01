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

$djigov5 = Find-Folder $storage '.dji.go.v5'
if ($djigov5) {
    Write-Output "=== Inside /.dji.go.v5 ==="
    $wp = Find-Folder $djigov5 'waypoint'
    if ($wp) {
        Write-Output "=== Inside /.dji.go.v5/waypoint ==="
        foreach ($item in $wp.Items()) {
            Write-Output "Item: $($item.Name) | IsFolder: $($item.IsFolder) | Size: $($item.Size) | Date: $($item.ModifyDate)"
        }
    }
}

$android = Find-Folder $storage 'Android'
if ($android) {
    $data = Find-Folder $android 'data'
    if ($data) {
        $djigo = Find-Folder $data 'dji.go.v5'
        if ($djigo) {
            $files = Find-Folder $djigo 'files'
            if ($files) {
                $wp = Find-Folder $files 'waypoint'
                if ($wp) {
                    Write-Output "=== Inside /Android/data/dji.go.v5/files/waypoint ==="
                    foreach ($item in $wp.Items()) {
                        Write-Output "Mission: $($item.Name) | IsFolder: $($item.IsFolder) | Date: $($item.ModifyDate)"
                    }
                }
            }
        }
    }
}
