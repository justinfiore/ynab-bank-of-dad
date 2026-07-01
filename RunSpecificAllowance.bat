@echo off
setlocal

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

set "RUN_DATE=%~1"
set "CONFIG_ARGS="
if not "%~2"=="" set "CONFIG_ARGS=--config %~2"

call gradlew.bat --no-daemon installDist || exit /b 1
call gradlew.bat --no-daemon run --args="--dry-run --date %RUN_DATE% %CONFIG_ARGS%" || exit /b 1

popd
endlocal
