@echo off
setlocal

set CONFIG_PATH=%~1
if "%CONFIG_PATH%"=="" set CONFIG_PATH=config.yaml

set STATE_DB_PATH=%~2
if "%STATE_DB_PATH%"=="" set STATE_DB_PATH=syncstate.db

if "%JAVA_HOME%"=="" (
  echo JAVA_HOME must be set before running the syncer.
  exit /b 1
)

call gradlew.bat runSyncer --args="--dry-run --config %CONFIG_PATH% --sync-state-db-path %STATE_DB_PATH% --max-cycles 1"
