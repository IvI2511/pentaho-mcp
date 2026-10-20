@echo off
REM Wrapper de build.ps1 (mismos parametros: -PdiHome "<ruta>" -Version x.y.z)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
exit /b %ERRORLEVEL%
