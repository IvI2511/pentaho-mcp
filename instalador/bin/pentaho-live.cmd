@echo off
rem ===========================================================================
rem  pentaho-live - opens Spoon (Pentaho Data Integration 9.4) with the
rem  Pentaho Live plugin, and returns right away.
rem
rem  The Windows installer puts this file in <install folder>\bin and adds that
rem  folder to the user's PATH, so an agent (Claude Code, Codex...) can run:
rem      pentaho-live
rem  (Git Bash finds the "pentaho-live" shell script next to this file.)
rem
rem  Which PDI: the folder written in bin\pdi-home.txt ("plugin only" install),
rem  otherwise <install folder>\data-integration (full install).
rem  Which Java: <install folder>\jre if it exists (full install); otherwise
rem  PDI's own search (PENTAHO_JAVA_HOME, JAVA_HOME, the PATH...).
rem
rem  Options:
rem      --gui     report with a message box instead of console text
rem                (the shortcuts use it: their console is minimized)
rem      --help    this help
rem  Environment:
rem      PENTAHO_LIVE_DRY_RUN=1  only print what it would do; starts nothing
rem      PENTAHO_MCP_PORT        MCP port to check (default 8765)
rem
rem  Exit codes: 0 = Spoon is starting, or was already open
rem              1 = no PDI (data-integration\Spoon.bat) found
rem ===========================================================================
setlocal EnableExtensions DisableDelayedExpansion

set "PL_BIN=%~dp0"
for %%I in ("%~dp0..") do set "PL_APP=%%~fI"
set "PL_PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"

set "PL_GUI="
if /i "%~1"=="--gui" set "PL_GUI=1"
if /i "%~1"=="--help" goto :help
if /i "%~1"=="-h" goto :help
if /i "%~1"=="/?" goto :help

rem --- PDI: bin\pdi-home.txt (UTF-8, written by the installer) or the bundled one
set "PL_PDI="
set "PL_PDI_FROM=bundled with Pentaho Live"
if not exist "%PL_BIN%pdi-home.txt" goto :pdi_default
set "PL_CP="
for /f "tokens=2 delims=:." %%C in ('chcp') do set "PL_CP=%%C"
if defined PL_CP set "PL_CP=%PL_CP: =%"
chcp 65001 >nul 2>&1
set /p PL_PDI=<"%PL_BIN%pdi-home.txt"
if defined PL_CP chcp %PL_CP% >nul 2>&1
set "PL_PDI_FROM=bin\pdi-home.txt"
:pdi_default
if not defined PL_PDI set "PL_PDI=%PL_APP%\data-integration"
if "%PL_PDI:~-1%"=="\" set "PL_PDI=%PL_PDI:~0,-1%"
set "PL_SPOON=%PL_PDI%\Spoon.bat"

rem --- Java: the JRE that comes with the full install
set "PL_JAVA_FROM=PDI's own search (PENTAHO_JAVA_HOME, JAVA_HOME, PATH)"
if not exist "%PL_APP%\jre\bin\javaw.exe" goto :java_done
set "PENTAHO_JAVA_HOME=%PL_APP%\jre"
set "PL_JAVA_FROM=%PL_APP%\jre"
:java_done

rem --- Is Spoon with the plugin already open? (the MCP answers /ping)
set "PL_PORT=8765"
if defined PENTAHO_MCP_PORT set "PL_PORT=%PENTAHO_MCP_PORT%"
set "PL_OPEN="
if not exist "%SystemRoot%\System32\curl.exe" goto :ping_done
"%SystemRoot%\System32\curl.exe" -s -m 2 "http://127.0.0.1:%PL_PORT%/ping" 2>nul | "%SystemRoot%\System32\findstr.exe" /c:"\"ok\":true" >nul 2>&1
if not errorlevel 1 set "PL_OPEN=1"
:ping_done

if "%PENTAHO_LIVE_DRY_RUN%"=="1" goto :dry_run
if not exist "%PL_SPOON%" goto :no_pdi
if defined PL_OPEN goto :already_open

rem --- Start Spoon. Through PowerShell's Start-Process (ShellExecute) and not with
rem     "start" or "call": Spoon.bat ends in "start javaw", and anything started
rem     with "start" inherits this console's handles, so an agent that ran
rem     pentaho-live would wait until Spoon is closed. The hidden window is only
rem     Spoon.bat's console; Spoon itself opens normally.
"%PL_PS%" -NoProfile -NonInteractive -Command "try { Start-Process -FilePath $env:PL_SPOON -WorkingDirectory $env:PL_PDI -WindowStyle Hidden -ErrorAction Stop } catch { exit 1 }" >nul 2>&1
if errorlevel 1 start "Spoon" /d "%PL_PDI%" /min "%PL_SPOON%"
echo Pentaho Live: starting Spoon from "%PL_PDI%".
echo It takes about 30-60 seconds; then the MCP answers at http://127.0.0.1:%PL_PORT%/mcp
exit /b 0

:already_open
echo Pentaho Live: Spoon is already open (the MCP answers at http://127.0.0.1:%PL_PORT%/mcp). Not opening another one.
if defined PL_GUI "%PL_PS%" -NoProfile -NonInteractive -Command "Add-Type -AssemblyName System.Windows.Forms; [void][System.Windows.Forms.MessageBox]::Show('Pentaho Live (Spoon) is already open. Look for the Spoon window on the taskbar.', 'Pentaho Live', 'OK', 'Information')" >nul 2>&1
exit /b 0

:no_pdi
echo Pentaho Live: no Spoon.bat in "%PL_PDI%".
echo Reinstall Pentaho Live, or fix the data-integration folder in "%PL_BIN%pdi-home.txt".
if defined PL_GUI "%PL_PS%" -NoProfile -NonInteractive -Command "Add-Type -AssemblyName System.Windows.Forms; [void][System.Windows.Forms.MessageBox]::Show('Pentaho Data Integration was not found (no Spoon.bat in ' + $env:PL_PDI + '). Reinstall Pentaho Live.', 'Pentaho Live', 'OK', 'Error')" >nul 2>&1
exit /b 1

:dry_run
echo [dry run] Pentaho Live launcher - nothing is started
echo   install folder:     "%PL_APP%"
echo   PDI (from %PL_PDI_FROM%): "%PL_PDI%"
if exist "%PL_SPOON%" (echo   Spoon.bat:          found) else (echo   Spoon.bat:          MISSING - would exit with code 1)
echo   Java:               "%PL_JAVA_FROM%"
if defined PL_OPEN (echo   MCP port %PL_PORT%:      answers - Spoon is already open, would not open another one) else (echo   MCP port %PL_PORT%:      no answer - Spoon is closed)
echo   would run:          PowerShell Start-Process -FilePath "%PL_SPOON%" -WorkingDirectory "%PL_PDI%" -WindowStyle Hidden
if not exist "%PL_SPOON%" exit /b 1
exit /b 0

:help
echo Usage: pentaho-live [--gui]
echo   Opens Spoon (Pentaho Data Integration 9.4) with the Pentaho Live plugin and returns right away.
echo   If Spoon is already open (the MCP answers on 127.0.0.1:%%PENTAHO_MCP_PORT%%, default 8765), it does not open another one.
echo   --gui                   report with a message box (used by the shortcuts)
echo   PENTAHO_LIVE_DRY_RUN=1  only print what it would do
exit /b 0
