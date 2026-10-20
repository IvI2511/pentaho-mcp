@echo off
REM Wrapper de build.ps1 (mismos parametros: -PdiHome "<ruta>"; la version sale del archivo VERSION)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
exit /b %ERRORLEVEL%
