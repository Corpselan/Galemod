@echo off
setlocal
cd /d "%~dp0"
call gradlew.bat runClient -Pketest --offline --console=plain
if errorlevel 1 pause
endlocal
