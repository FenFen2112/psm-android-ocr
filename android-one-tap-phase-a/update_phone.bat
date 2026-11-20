@echo off
setlocal

set "JAVA_HOME=D:\Android Studio\jbr"
set "PATH=%JAVA_HOME%\bin;%PATH%"

cd /d "%~dp0"

call gradlew.bat --stop

timeout /t 2 >nul

call gradlew.bat --no-daemon installDebug

pause
