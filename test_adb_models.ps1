$adb = "C:\Users\edgar\AppData\Local\Android\Sdk\platform-tools\adb.exe"
Write-Output "=== 192.168.1.67:43389 ==="
& $adb -s 192.168.1.67:43389 shell getprop ro.product.model
& $adb -s 192.168.1.67:43389 shell getprop ro.product.device

Write-Output "=== d7ee3ca3 ==="
& $adb -s d7ee3ca3 shell getprop ro.product.model
& $adb -s d7ee3ca3 shell getprop ro.product.device
