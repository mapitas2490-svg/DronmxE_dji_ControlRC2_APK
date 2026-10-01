# Check F:\ drive
Write-Output "=== Checking F:\ for mision_10_01 ==="
Get-ChildItem -Path "F:\" -Recurse -Filter "*10_01*" -ErrorAction SilentlyContinue | Select-Object FullName, Length, LastWriteTime

# Check local workspace
Write-Output "=== Checking Local for mision_10_01 ==="
Get-ChildItem -Path $PSScriptRoot -Recurse -Filter "*10_01*" -ErrorAction SilentlyContinue | Select-Object FullName, Length, LastWriteTime

# Check all KMZ files on RC 2
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
    
    function List-Kmz($folder, $depth) {
        if ($depth -gt 4) { return }
        foreach ($item in $folder.Items()) {
            if ($item.Name -like '*.kmz' -or $item.Name -like '*mision*') {
                Write-Output "RC2 KMZ/MISION: $($item.Name) ($($item.Size) bytes) in $($folder.Title)"
            }
            if ($item.IsFolder -and -not ($item.Name -in @('DCIM', 'Pictures', 'Movies', 'Music', 'Alarms', 'Ringtones', 'Audiobooks', 'Podcasts', 'Notifications'))) {
                List-Kmz $item.GetFolder ($depth + 1)
            }
        }
    }
    Write-Output "=== All KMZ or mission files on RC 2 ==="
    List-Kmz $storage 0
}
