$shell = New-Object -ComObject Shell.Application
$pc = $shell.Namespace(17)
$rc2 = $pc.Items() | Where-Object { $_.Name -like '*DJI RC 2*' -or $_.Name -like '*RC 2*' }
$rc2Folder = $rc2.GetFolder
function Find-Folder($f, $n) { foreach ($i in $f.Items()) { if ($i.IsFolder -and $i.Name -ieq $n) { return $i.GetFolder } }; return $null }
$storage = Find-Folder $rc2Folder 'Almacenamiento interno compartido'
$dl = Find-Folder $storage 'Download'

$tempDir = "C:\Users\edgar\AppData\Local\Temp\check_modified"
if (Test-Path $tempDir) { Remove-Item $tempDir -Recurse -Force }
New-Item -ItemType Directory -Path $tempDir | Out-Null
$destFolder = $shell.Namespace($tempDir)

foreach ($name in @('Mision_10-01.kmz', 'mision_fotogrametria.kmz')) {
    $item = $dl.Items() | Where-Object { $_.Name -ieq $name }
    if ($item) {
        $destFolder.CopyHere($item, 16)
    }
}
Start-Sleep -Seconds 3

Get-ChildItem -Path $tempDir | Select-Object Name, Length, LastWriteTime
