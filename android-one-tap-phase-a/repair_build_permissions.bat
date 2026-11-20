@echo off
setlocal

cd /d "%~dp0"

echo Stopping Gradle daemons...
call gradlew.bat --stop

echo.
echo Taking ownership of app\build...
takeown /F app\build /R /D Y

echo.
echo Granting full control to the current user and Users group...
icacls app\build /grant "%USERNAME%:(OI)(CI)F" /T /C
icacls app\build /grant "*S-1-5-32-545:(OI)(CI)F" /T /C

echo.
echo Removing app\build...
rmdir /s /q app\build

if exist app\build (
    echo.
    echo FAILED: app\build still exists. Close Android Studio and all command windows, then run this file as Administrator again.
    pause
    exit /b 1
)

echo.
echo OK: app\build removed. Now run update_phone.bat.
pause
