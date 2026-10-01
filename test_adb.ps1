$adb = "C:\Users\edgar\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    $adbCmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCmd) { $adb = $adbCmd.Source }
}

if (Test-Path $adb) {
    Write-Output "Found ADB: $adb"
    & $adb devices
} else {
    Write-Output "ADB executable not found at $adb"
}
