@echo off
set ADB="C:\Users\jwcop\AppData\Local\Android\Sdk\platform-tools\adb.exe"
echo 324976| %ADB% pair 100.125.110.103:37855
timeout /t 2 /nobreak >nul
%ADB% connect 100.125.110.103:39267
%ADB% devices
