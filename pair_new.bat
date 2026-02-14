@echo off
set ADB="C:\Users\jwcop\AppData\Local\Android\Sdk\platform-tools\adb.exe"
echo Attempting to pair...
echo 333804| %ADB% pair 100.125.110.103:33639
timeout /t 3 /nobreak >nul
echo.
echo Attempting to connect...
%ADB% connect 100.125.110.103:39267
timeout /t 2 /nobreak >nul
echo.
echo Checking devices...
%ADB% devices
