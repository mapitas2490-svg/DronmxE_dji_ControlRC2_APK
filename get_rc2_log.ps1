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
    
    $localLog = Join-Path $PSScriptRoot "dronmxE_log.txt"
    if (Test-Path $localLog) { Remove-Item $localLog -Force }

    # Check helpers
    $helpers = Find-Folder $storage 'helpers'
    if ($helpers) {
        $logItem = $helpers.Items() | Where-Object { $_.Name -ieq 'dronmxE_log.txt' }
        if ($logItem) {
            Write-Output "Found in helpers ($($logItem.Size) bytes)"
            $shellDest = $shell.Namespace($PSScriptRoot)
            $shellDest.CopyHere($logItem, 16)
            Start-Sleep -Seconds 2
            Write-Output "Copied to $localLog"
        }
    }
    
    # Check Download if not copied
    if (-not (Test-Path $localLog)) {
        $dl = Find-Folder $storage 'Download'
        if ($dl) {
            $logItem = $dl.Items() | Where-Object { $_.Name -ieq 'dronmxE_log.txt' }
            if ($logItem) {
                Write-Output "Found in Download ($($logItem.Size) bytes)"
                $shellDest = $shell.Namespace($PSScriptRoot)
                $shellDest.CopyHere($logItem, 16)
                Start-Sleep -Seconds 2
            }
        }
    }
}
