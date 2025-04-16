cd C:\Users\Justin\Documents\workspace\YNABApiUtils\
set YNAB_ACCESS_TOKEN=f29371b2aa962c538e01f0599a7806a921252c262923e74eceab1bf8e83dacdf
call gradlew.bat --no-daemon installDist

cd build\install\YNABApiUtils\bin
YNABApiUtils.bat -d 2024-12-27
