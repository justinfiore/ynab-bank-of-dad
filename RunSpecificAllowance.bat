@echo off
setlocal EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
pushd "%SCRIPT_DIR%"

if "%YNAB_ACCESS_TOKEN%"=="" (
  echo ERROR: YNAB_ACCESS_TOKEN is not set.
  exit /b 1
)

if "%~1"=="" (
  echo Usage: RunSpecificAllowance.bat YYYY-MM-DD [optional-config-path]
  exit /b 1
)

set "JAVA_VERSION_LINE="
for /f "usebackq delims=" %%I in (`java -version 2^>^&1`) do (
  if not defined JAVA_VERSION_LINE set "JAVA_VERSION_LINE=%%I"
)
for /f "tokens=2 delims=.\"" %%I in ("!JAVA_VERSION_LINE!") do set "JAVA_MAJOR_VERSION=%%I"
if not defined JAVA_MAJOR_VERSION (
  echo ERROR: Java 25 or later is required.
  echo Make sure JAVA_HOME is set to a Java 25 installation and that the java on the PATH is Java 25.
  echo Detected java version:
  java -version 2^>^&1
  exit /b 1
)
if !JAVA_MAJOR_VERSION! LSS 25 (
  echo ERROR: Java 25 or later is required.
  echo Make sure JAVA_HOME is set to a Java 25 installation and that the java on the PATH is Java 25.
  echo Detected java version:
  java -version 2^>^&1
  exit /b 1
)

set "RUN_DATE=%~1"
set "CONFIG_ARGS="
if not "%~2"=="" set "CONFIG_ARGS=--config %~2"

call gradlew.bat --no-daemon installDist || exit /b 1
call gradlew.bat --no-daemon run --args="--date %RUN_DATE% %CONFIG_ARGS%" || exit /b 1

popd
endlocal
