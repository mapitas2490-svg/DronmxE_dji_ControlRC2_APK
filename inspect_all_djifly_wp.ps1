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
    
    Write-Output "=== Folders in waypoint ==="
    foreach ($item in $wp.Items()) {
        Write-Output "Item: $($item.Name) | IsFolder=$($item.IsFolder) | Date=$($item.ModifyDate)"
        if ($item.IsFolder) {
            $sub = $item.GetFolder
            foreach ($subItem in $sub.Items()) {
                Write-Output "   -> $($subItem.Name) ($($subItem.Size) bytes)"
            }
        }
    }
}
