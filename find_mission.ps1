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
    
    function Search-Recurse($folder, $depth) {
        if ($depth -gt 5) { return }
        foreach ($item in $folder.Items()) {
            if ($item.Name -like '*mision_10_01*' -or $item.Name -like '*10_01*') {
                Write-Output "FOUND: $($item.Path) | $($item.Name) | $($item.Size) bytes | $($item.ModifyDate)"
            }
            if ($item.IsFolder -and -not ($item.Name -in @('DCIM', 'Pictures', 'Movies', 'Music', 'Alarms', 'Ringtones', 'Audiobooks', 'Podcasts', 'Notifications'))) {
                Search-Recurse $item.GetFolder ($depth + 1)
            }
        }
    }
    
    Write-Output "Searching for mision_10_01 on RC 2..."
    Search-Recurse $storage 0
    Write-Output "Done searching."
} else {
    Write-Output "RC 2 not found."
}
