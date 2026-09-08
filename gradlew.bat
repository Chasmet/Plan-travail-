@echo off
setlocal
set GRADLE_VERSION=8.7
set BASE_DIR=%~dp0
set CACHE_DIR=%BASE_DIR%.gradle-bootstrap
set ZIP=%CACHE_DIR%\gradle-%GRADLE_VERSION%-bin.zip
set DIST=%CACHE_DIR%\gradle-%GRADLE_VERSION%
if not exist "%DIST%\bin\gradle.bat" (
  if not exist "%CACHE_DIR%" mkdir "%CACHE_DIR%"
  if not exist "%ZIP%" powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ZIP%'"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Force -Path '%ZIP%' -DestinationPath '%CACHE_DIR%'"
)
call "%DIST%\bin\gradle.bat" %*
endlocal
