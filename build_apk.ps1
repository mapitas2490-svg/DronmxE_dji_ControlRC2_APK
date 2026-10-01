$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$gradleBat = "C:\Users\edgar\.gradle\wrapper\dists\gradle-8.5-bin\5t9huq95ubn472n8rpzujfbqh\gradle-8.5\bin\gradle.bat"

Write-Output "--- Compilando dronmxE (Debug Pre-signed APK) ---"
& $gradleBat assembleDebug

$builtApk = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"
$localApk = Join-Path $PSScriptRoot "apk\dronmxE.apk"

if (Test-Path $builtApk) {
    if (-not (Test-Path (Join-Path $PSScriptRoot "apk"))) {
        New-Item -ItemType Directory -Path (Join-Path $PSScriptRoot "apk") -Force | Out-Null
    }
    Copy-Item -Path $builtApk -Destination $localApk -Force
    Write-Output "✅ Guardado local: $localApk"

    # Siempre guardar en F:\DJI_3S_AIR\apk
    $fDir = "F:\DJI_3S_AIR\apk"
    if (Test-Path "F:\") {
        if (-not (Test-Path $fDir)) {
            New-Item -ItemType Directory -Path $fDir -Force | Out-Null
        }
        $fApk = Join-Path $fDir "dronmxE.apk"
        Copy-Item -Path $builtApk -Destination $fApk -Force
        Write-Output "✅ Guardado en F:\DJI_3S_AIR\apk: $fApk"
    }
}
