@echo off
setlocal
reset
cat %USERPROFILE%/AppData/Local/JetBrains/IntelliJIdea2026.1/log/jcef.log | grep %*
endlocal