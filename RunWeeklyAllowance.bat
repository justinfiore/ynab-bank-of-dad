@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
pushd "%SCRIPT_DIR%"

if "%YNAB_ACCESS_TOKEN%"=="" (
  echo ERROR: YNAB_ACCESS_TOKEN is not set.
  exit /b 1
)

call gradlew.bat --no-daemon installDist || exit /b 1
call gradlew.bat --no-daemon run --args="--dry-run" || exit /b 1

popd
endlocal
