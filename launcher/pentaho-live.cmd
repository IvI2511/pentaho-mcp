@echo off
rem ===========================================================================
rem  pentaho-live - opens Spoon (Pentaho Data Integration 9.4) with the
rem  Pentaho Live plugin, and returns right away.
rem
rem  The Windows installer puts this file in <install folder>\bin and adds that
rem  folder to the user's PATH, so an agent (Claude Code, Codex...) can run:
rem      pentaho-live
rem  (Git Bash finds the "pentaho-live" shell script next to this file.)
rem  The Start menu and desktop shortcuts open IDCLauncher.exe, next to this
rem  file: it shows a splash screen at once and runs "pentaho-live --launcher".
rem
rem  Which PDI: the folder written in bin\pdi-home.txt ("plugin only" install),
rem  otherwise <install folder>\data-integration (full install).
rem  Which Java: <install folder>\jre if it exists (full install); otherwise
rem  PDI's own search (PENTAHO_JAVA_HOME, JAVA_HOME, the PATH...).
rem
rem  Git and console commands (no Spoon; Java and the plugin jar of that PDI):
rem      pentaho-live normalize [--check] <files or folders>
rem      pentaho-live diff [--format text|markdown|json] <before> <after>
rem      pentaho-live diff [--rev REV] <file>
rem      pentaho-live diff [--rev REV] [folders]  every .ktr/.kjb changed since REV
rem      pentaho-live textconv <file>        (git: diff.kettle.textconv)
rem      pentaho-live pre-commit             (the optional pre-commit hook)
rem      pentaho-live git-setup              (the .gitattributes and git config lines)
rem  They exit with Java's code: 0 ok, 1 differences or not normalized, 2 error.
rem
rem  Options:
rem      --gui       report with a message box instead of console text
rem      --launcher  used by IDCLauncher.exe (implies --gui): creates the
rem                  launcher's ready file if Spoon is already open or there is
rem                  no PDI; otherwise hides PDI's own splash screen (the
rem                  launcher's is on screen) and leaves the ready file to the
rem                  plugin, which creates it when Spoon's window appears
rem      --help      this help
rem  Environment:
rem      PENTAHO_LIVE_DRY_RUN=1   only print what it would do; starts nothing
rem      PENTAHO_MCP_PORT         MCP port to check (default 8765)
rem      IDC_LAUNCHER_READY_FILE  set by IDCLauncher.exe (used with --launcher)
rem      PENTAHO_LIVE_PDI         a data-integration folder to use instead of
rem                               bin\pdi-home.txt (CI, a second PDI)
rem      PENTAHO_LIVE_JAR         git and console commands: the plugin jar (or a
rem                               folder of jars) instead of <PDI>\plugins\pentaho-mcp
rem
rem  Exit codes: 0 = Spoon is starting, or was already open
rem              1 = no PDI (data-integration\Spoon.bat) found
rem
rem  Project commands (no Spoon; block [0.7 project] at the end of this file):
rem      pentaho-live init [folder]         team project template (only what is missing)
rem      pentaho-live lint <files|folders>  lint with the project's profile; exit 1 below its min_score
rem      pentaho-live profile [folder]      the lint profile that applies there
rem      pentaho-live test [--all] [paths]  golden-dataset tests in a separate JVM; exit 1 if one fails
rem ===========================================================================
setlocal EnableExtensions DisableDelayedExpansion

set "PL_BIN=%~dp0"
for %%I in ("%~dp0..") do set "PL_APP=%%~fI"
set "PL_PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"

rem --- [0.7 project] init / lint / profile / test: they skip the options below and go to :pl_proyecto
set "PL_PROYECTO="
if /i "%~1"=="init" set "PL_PROYECTO=1"
if /i "%~1"=="lint" set "PL_PROYECTO=1"
if /i "%~1"=="profile" set "PL_PROYECTO=1"
if /i "%~1"=="test" set "PL_PROYECTO=1"
if defined PL_PROYECTO goto :args_done

rem --- Options (anything else is ignored)
set "PL_GUI="
set "PL_LAUNCHER="
rem     The git and console commands skip the options and go to :tool below.
set "PL_TOOL="
for %%C in (normalize diff textconv pre-commit git-setup) do if /i "%~1"=="%%C" set "PL_TOOL=1"
if defined PL_TOOL goto :args_done
:next_arg
if "%~1"=="" goto :args_done
if /i "%~1"=="--gui" set "PL_GUI=1"
if /i "%~1"=="--launcher" set "PL_LAUNCHER=1"
if /i "%~1"=="--help" goto :help
if /i "%~1"=="-h" goto :help
if /i "%~1"=="/?" goto :help
shift
goto :next_arg
:args_done
if defined PL_LAUNCHER set "PL_GUI=1"

rem --- PDI: bin\pdi-home.txt (UTF-8, written by the installer) or the bundled one
set "PL_PDI="
set "PL_PDI_FROM=bundled with Pentaho Live"
if not defined PENTAHO_LIVE_PDI goto :pdi_file
set "PL_PDI=%PENTAHO_LIVE_PDI%"
set "PL_PDI_FROM=PENTAHO_LIVE_PDI"
goto :pdi_default
:pdi_file
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
if defined PL_PROYECTO goto :pl_proyecto
if defined PL_TOOL goto :tool

rem --- Is Spoon with the plugin already open? (the MCP answers /ping)
set "PL_PORT=8765"
if defined PENTAHO_MCP_PORT set "PL_PORT=%PENTAHO_MCP_PORT%"
set "PL_OPEN="
if not exist "%SystemRoot%\System32\curl.exe" goto :ping_done
"%SystemRoot%\System32\curl.exe" -s -m 2 "http://127.0.0.1:%PL_PORT%/ping" 2>nul | "%SystemRoot%\System32\findstr.exe" /c:"\"ok\":true" >nul 2>&1
if not errorlevel 1 set "PL_OPEN=1"
:ping_done

rem --- Started by IDCLauncher.exe: its ready file, and PDI's own splash screen.
rem     The launcher's splash is already on screen, so PDI's would be a second
rem     one: this look-and-feel file swaps PDI's splash image for a 1x1
rem     transparent PNG (the plugin puts the real one back for Help, About).
rem     Only with a ready file, or nothing would close the launcher's splash
rem     when Spoon appears. Spoon.bat puts an OPT set here before its own Java
rem     options, and the path is relative to data-integration, where it runs.
set "PL_SIGNAL="
set "PL_SPLASH=shown"
if not defined PL_LAUNCHER goto :launcher_done
call :check_signal
if not defined PL_SIGNAL goto :launcher_done
if not exist "%PL_PDI%\plugins\pentaho-mcp\ui\laf-pentaho-live.properties" goto :launcher_done
set "PL_LAF=-Dorg.pentaho.di.laf.alt=plugins/pentaho-mcp/ui/laf-pentaho-live.properties"
if defined OPT set "OPT=%OPT% %PL_LAF%"
if not defined OPT set "OPT=%PL_LAF%"
set "PL_SPLASH=hidden"
:launcher_done

if "%PENTAHO_LIVE_DRY_RUN%"=="1" goto :dry_run
if not exist "%PL_SPOON%" goto :no_pdi
if defined PL_OPEN goto :already_open

rem --- Start Spoon, through cmd.exe and PowerShell's Start-Process:
rem     - cmd.exe runs Spoon.bat, and not Start-Process on Spoon.bat itself:
rem       that is ShellExecute on the .bat, and Windows checks its zone first.
rem       The .bat files of a PDI zip downloaded from the internet carry the
rem       mark of the web, so every launch showed "Open File - Security
rem       Warning". cmd.exe runs the .bat without that check.
rem     - /s with the doubled quotes: cmd drops the outer pair and runs the
rem       quoted path as it is, so folders with spaces, parentheses or
rem       ampersands work. /d skips any AutoRun command.
rem     - Start-Process (ShellExecute), and not "start" or "call": Spoon.bat
rem       ends in "start javaw", and anything started with "start" inherits
rem       this console's handles, so an agent that ran pentaho-live would wait
rem       until Spoon is closed.
rem     - -WorkingDirectory is escaped: Start-Process reads it as a wildcard
rem       path, and a folder with [ or ] in its name would not be found.
rem     The hidden window is only Spoon.bat's console; Spoon itself opens normally.
"%PL_PS%" -NoProfile -NonInteractive -Command "try { Start-Process -FilePath (Join-Path $env:SystemRoot 'System32\cmd.exe') -ArgumentList '/d','/s','/c',('{0}{0}{1}{0}{0}' -f [char]34, $env:PL_SPOON) -WorkingDirectory ([Management.Automation.WildcardPattern]::Escape($env:PL_PDI)) -WindowStyle Hidden -ErrorAction Stop } catch { exit 1 }" >nul 2>&1
if errorlevel 1 start "Spoon" /d "%PL_PDI%" /min "%PL_SPOON%"
echo Pentaho Live: starting Spoon from "%PL_PDI%".
echo It takes about 30-60 seconds; then the MCP answers at http://127.0.0.1:%PL_PORT%/mcp
exit /b 0

:already_open
echo Pentaho Live: Spoon is already open (the MCP answers at http://127.0.0.1:%PL_PORT%/mcp). Not opening another one.
if not defined PL_LAUNCHER goto :already_open_gui
call :signal_ready
rem From the launcher: no message, just bring Spoon to the front if Windows lets us.
"%PL_PS%" -NoProfile -NonInteractive -Command "try { $p = Get-Process javaw, java -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -match 'Spoon' } | Select-Object -First 1; if ($p) { [void](New-Object -ComObject WScript.Shell).AppActivate($p.Id) } } catch { }" >nul 2>&1
exit /b 0
:already_open_gui
if defined PL_GUI "%PL_PS%" -NoProfile -NonInteractive -Command "Add-Type -AssemblyName System.Windows.Forms; [void][System.Windows.Forms.MessageBox]::Show('Pentaho Live (Spoon) is already open. Look for the Spoon window on the taskbar.', 'Pentaho Live', 'OK', 'Information')" >nul 2>&1
exit /b 0

:no_pdi
echo Pentaho Live: no Spoon.bat in "%PL_PDI%".
echo Reinstall Pentaho Live, or fix the data-integration folder in "%PL_BIN%pdi-home.txt".
rem The message box gets a topmost owner, so it shows above every other window.
rem From the launcher, PowerShell creates the ready file right before showing it
rem (loading Windows Forms takes about 1.5 s), so the launcher's splash closes
rem behind the message and not before it. No ready file here afterwards: the
rem launcher has deleted it by then, and exit code 1 closes it anyway
rem (quiet_exit_codes=1 in idc-launcher.ini).
if defined PL_GUI "%PL_PS%" -NoProfile -NonInteractive -Command "Add-Type -AssemblyName System.Windows.Forms; $f = New-Object System.Windows.Forms.Form; $f.TopMost = $true; if ($env:PL_SIGNAL) { try { [IO.File]::Open($env:IDC_LAUNCHER_READY_FILE, 'CreateNew').Dispose() } catch { } }; [void][System.Windows.Forms.MessageBox]::Show($f, 'Pentaho Data Integration was not found (no Spoon.bat in ' + $env:PL_PDI + '). Reinstall Pentaho Live.', 'Pentaho Live', 'OK', 'Error')" >nul 2>&1
exit /b 1

:dry_run
echo [dry run] Pentaho Live launcher - nothing is started
echo   install folder:     "%PL_APP%"
echo   PDI (from %PL_PDI_FROM%): "%PL_PDI%"
if exist "%PL_SPOON%" (echo   Spoon.bat:          found) else (echo   Spoon.bat:          MISSING - would exit with code 1)
echo   Java:               "%PL_JAVA_FROM%"
if defined PL_OPEN (echo   MCP port %PL_PORT%:      answers - Spoon is already open, would not open another one) else (echo   MCP port %PL_PORT%:      no answer - Spoon is closed)
rem Delayed expansion only to print paths and OPT, which may hold ( ) or ampersands.
setlocal EnableDelayedExpansion
echo   would run:          cmd.exe /d /s /c ""!PL_SPOON!"" (PowerShell Start-Process, hidden)
if defined PL_LAUNCHER (echo   launcher:           yes) else (echo   launcher:           no)
if defined PL_LAUNCHER if not defined PL_SIGNAL echo   ready file:         none or not IDCLauncher's - not used
if "!PL_SPLASH!"=="hidden" (echo   PDI splash:         hidden ^(OPT !OPT!^)) else (echo   PDI splash:         shown)
endlocal
if not exist "%PL_SPOON%" exit /b 1
exit /b 0

:help
echo Usage: pentaho-live [--gui] [--launcher]
echo        pentaho-live normalize^|diff^|textconv^|pre-commit^|git-setup ...  (git and console commands, no Spoon)
echo   Opens Spoon (Pentaho Data Integration 9.4) with the Pentaho Live plugin and returns right away.
echo   If Spoon is already open (the MCP answers on 127.0.0.1:%%PENTAHO_MCP_PORT%%, default 8765), it does not open another one.
echo   --gui                   report with a message box
echo   --launcher              used by IDCLauncher.exe, the splash screen of the shortcuts (implies --gui)
echo   PENTAHO_LIVE_DRY_RUN=1  only print what it would do
echo Project commands (no Spoon needed):
echo   pentaho-live init [folder] [--name N] [--lang en^|es^|pt] [--dry-run]
echo   pentaho-live lint ^<files or folders^> [--profile path^|none] [--min-score N] [--format text^|json^|github]
echo   pentaho-live profile [folder]
exit /b 0

rem --- IDCLauncher.exe closes its splash screen when its ready file exists. Only
rem     a path shaped like the launcher's is used: absolute, a .signal file, in
rem     a folder called Launcher. PL_SIGNAL=1 when it is.
:check_signal
set "PL_SIGNAL="
if not defined IDC_LAUNCHER_READY_FILE exit /b 0
for /f "delims=" %%F in ("%IDC_LAUNCHER_READY_FILE%") do if /i "%%~xF"==".signal" if /i "%%~fF"=="%%F" for %%D in ("%%~dpF.") do if /i "%%~nxD"=="Launcher" set "PL_SIGNAL=1"
exit /b 0

rem --- Creates the ready file (empty; never overwrites one).
:signal_ready
if not defined PL_SIGNAL exit /b 0
if exist "%IDC_LAUNCHER_READY_FILE%" exit /b 0
(type nul > "%IDC_LAUNCHER_READY_FILE%") 2>nul
exit /b 0

rem ===========================================================================
rem  [0.7 project] pentaho-live init | lint | profile | test
rem  io.github.ivi2511.pentahomcp.ProyectoMain, without Spoon: the classpath is
rem  data-integration\lib plus the plugin (plugins\pentaho-mcp), Kettle's own
rem  plugins come from data-integration\plugins. The arguments go as they are.
rem  Java: <install folder>\jre (full install), else PENTAHO_JAVA_HOME, JAVA_HOME
rem  or the java of the PATH. Exit code: the one of ProyectoMain (0 ok, 1 a file
rem  below the minimum score or a test that fails, 2 usage or profile error).
rem ===========================================================================
:pl_proyecto
if not exist "%PL_PDI%\plugins\pentaho-mcp" goto :pl_proyecto_sin_plugin
set "PL_JAVA=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "PL_JAVA=%JAVA_HOME%\bin\java.exe"
if defined PENTAHO_JAVA_HOME if exist "%PENTAHO_JAVA_HOME%\bin\java.exe" set "PL_JAVA=%PENTAHO_JAVA_HOME%\bin\java.exe"
if exist "%PL_APP%\jre\bin\java.exe" set "PL_JAVA=%PL_APP%\jre\bin\java.exe"
set "PL_PCP=%PL_PDI%\lib\*;%PL_PDI%\plugins\pentaho-mcp\*;%PL_PDI%\plugins\pentaho-mcp\lib\*;%PL_PDI%\classes"
rem Kettle needs these opens on Java 17+. Java 8 does not know --add-opens: IgnoreUnrecognizedVMOptions
rem makes it skip them (JDK_JAVA_OPTIONS did the same, but Java 9+ printed "NOTE: Picked up..." on stderr).
set "PL_OPENS=-XX:+IgnoreUnrecognizedVMOptions --add-opens=java.base/java.net=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/sun.net.www.protocol.jar=ALL-UNNAMED"
if "%PENTAHO_LIVE_DRY_RUN%"=="1" goto :pl_proyecto_ensayo
"%PL_JAVA%" %PL_OPENS% -Xmx1g -Djava.awt.headless=true -Dfile.encoding=UTF-8 "-DKETTLE_PLUGIN_BASE_FOLDERS=%PL_PDI%\plugins" -cp "%PL_PCP%" io.github.ivi2511.pentahomcp.ProyectoMain %*
exit /b %ERRORLEVEL%
:pl_proyecto_ensayo
setlocal EnableDelayedExpansion
echo [dry run] "!PL_JAVA!" !PL_OPENS! -Xmx1g -Djava.awt.headless=true -cp "!PL_PCP!" io.github.ivi2511.pentahomcp.ProyectoMain %*
endlocal
exit /b 0
:pl_proyecto_sin_plugin
echo Pentaho Live: no plugin in "%PL_PDI%\plugins\pentaho-mcp" (PDI from %PL_PDI_FROM%). Reinstall Pentaho Live.
exit /b 2

rem --- Git and console commands: Pentaho Live's jar with Java, without Spoon or a
rem     console window of its own. Java: the bundled JRE, PENTAHO_JAVA_HOME,
rem     JAVA_HOME, then the PATH. The jar: PENTAHO_LIVE_JAR or the plugin folder of
rem     the PDI above. The console shows the UTF-8 output with code page 65001,
rem     put back afterwards.
:tool
set "PL_JAVA="
if exist "%PL_APP%\jre\bin\java.exe" set "PL_JAVA=%PL_APP%\jre\bin\java.exe"
if not defined PL_JAVA if defined PENTAHO_JAVA_HOME if exist "%PENTAHO_JAVA_HOME%\bin\java.exe" set "PL_JAVA=%PENTAHO_JAVA_HOME%\bin\java.exe"
if not defined PL_JAVA if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "PL_JAVA=%JAVA_HOME%\bin\java.exe"
if not defined PL_JAVA set "PL_JAVA=java"
set "PL_CP=%PL_PDI%\plugins\pentaho-mcp\*"
if defined PENTAHO_LIVE_JAR set "PL_CP=%PENTAHO_LIVE_JAR%"
if defined PENTAHO_LIVE_JAR goto :tool_run
if exist "%PL_PDI%\plugins\pentaho-mcp" goto :tool_run
echo Pentaho Live: no plugin in "%PL_PDI%\plugins\pentaho-mcp". Reinstall Pentaho Live, or set PENTAHO_LIVE_JAR to its pentaho-mcp-core jar. 1>&2
exit /b 2
:tool_run
set "PL_CP_OLD="
for /f "tokens=2 delims=:." %%C in ('chcp') do set "PL_CP_OLD=%%C"
if defined PL_CP_OLD set "PL_CP_OLD=%PL_CP_OLD: =%"
chcp 65001 >nul 2>&1
"%PL_JAVA%" -Xmx512m -Dfile.encoding=UTF-8 -cp "%PL_CP%" io.github.ivi2511.pentahomcp.diff.LineaComandos %*
set "PL_EXIT=%ERRORLEVEL%"
if defined PL_CP_OLD chcp %PL_CP_OLD% >nul 2>&1
exit /b %PL_EXIT%
