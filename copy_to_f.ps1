$fixedTargetDir = "F:\DJI_3S_AIR\apk"
if (-not (Test-Path $fixedTargetDir)) {
    New-Item -ItemType Directory -Path $fixedTargetDir -Force | Out-Null
}

$srcApk = Join-Path $PSScriptRoot "apk\dronmxE.apk"
if (Test-Path $srcApk) {
    $targetFile = Join-Path $fixedTargetDir "dronmxE.apk"
    Copy-Item -Path $srcApk -Destination $targetFile -Force
    Write-Output "✅ Copiado con éxito a: $targetFile ($((Get-Item $targetFile).Length) bytes)"
} else {
    Write-Output "⚠️ No se encontró $srcApk"
}

Get-ChildItem -Path $fixedTargetDir
