@echo off
setlocal
reset
cat %USERPROFILE%/AppData/Local/JetBrains/RustRover2025.3/log/idea.log | grep %*
endlocal
