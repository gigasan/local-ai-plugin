@echo off
setlocal
reset
cat %USERPROFILE%/AppData/Local/JetBrains/IntelliJIdea2026.1/log/idea.log | grep %*
endlocal