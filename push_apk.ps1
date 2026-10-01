$builtApk = "app\build\outputs\apk\debug\app-debug.apk"
$destApk = "apk\dronmxE.apk"
if (-not (Test-Path "apk")) { New-Item -ItemType Directory -Path "apk" | Out-Null }
if (Test-Path $builtApk) {
    Copy-Item -Path $builtApk -Destination $destApk -Force
}

# Siempre guardar en F:\DJI_3S_AIR\apk
$fDir = "F:\DJI_3S_AIR\apk"
if (Test-Path "F:\") {
    if (-not (Test-Path $fDir)) {
        New-Item -ItemType Directory -Path $fDir -Force | Out-Null
    }
    Copy-Item -Path $destApk -Destination (Join-Path $fDir "dronmxE.apk") -Force
    Write-Output "✅ Guardado en F:\DJI_3S_AIR\apk\dronmxE.apk"
}

# Push to DJI RC 2 via MTP
$shell = New-Object -ComObject Shell.Application
$pc = $shell.Namespace(17)
$rc2 = $pc.Items() | Where-Object { $_.Name -like '*DJI RC 2*' -or $_.Name -like '*RC 2*' }
if ($rc2) {
    Write-Output "Found RC 2 device: $($rc2.Name)"
    $rc2Folder = $rc2.GetFolder
    function Find-Folder($f, $n) { 
        foreach ($i in $f.Items()) { 
            if ($i.IsFolder -and $i.Name -ieq $n) { return $i.GetFolder } 
        } 
        return $null 
    }
    $storage = Find-Folder $rc2Folder 'Almacenamiento interno compartido'
    if (-not $storage) { $storage = Find-Folder $rc2Folder 'Internal shared storage' }
    if ($storage) {
        $helpers = Find-Folder $storage 'helpers'
        if ($helpers) {
            $destItem = "$PSScriptRoot\$destApk"
            Write-Output "Copying to RC 2 /helpers..."
            $helpers.CopyHere($destItem, 16)
            Write-Output "✅ dronmxE.apk copied to DJI RC 2 /helpers folder!"
        }
        $dl = Find-Folder $storage 'Download'
        if ($dl) {
            $dl.CopyHere("$PSScriptRoot\$destApk", 16)
            Write-Output "✅ dronmxE.apk copied to DJI RC 2 /Download folder!"
        }
    }
}
