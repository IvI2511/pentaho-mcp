# Lanzador y arreglos del instalador: diseño (Pentaho Live 0.4.2)

Contrato para tres implementadores en paralelo (lanzador **L**, plugin Java **J**, instalador **I**) y un tester
**T**. Sale del reporte de Iván sobre la 0.4.1 («solo el plugin» en su PDI) y de dos investigaciones del
20-oct-2026 hechas sin abrir Spoon: javap de PDI 9.4 + SWT 3.115, y pruebas de MOTW con `.bat` marcados.
Fuera de alcance: la pestaña Informes es la **0.5.0** (la 0.4.1 es solo el MCP).

## 1. Diagnóstico

1. **«Advertencia de seguridad / Publicador desconocido» para `Spoon.bat`** en cada arranque. Los `.bat` del PDI
   tienen `Zone.Identifier` ZoneId=3 (salieron de un zip bajado) y `pentaho-live.cmd` los abre con
   `Start-Process -FilePath Spoon.bat` (ShellExecuteEx → chequeo de zona). Probado: `Start-Process` de
   `cmd.exe /d /s /c ""<Spoon.bat>""` (oculto) no pregunta, vuelve enseguida y anda con espacios, `ñ` y `( ) &`.
2. **No aparece el lanzador IDC** durante la carga (30-60 s): el acceso abre un `.cmd` minimizado.
3. **Carpeta de instalación dentro del PDI** (`...\data-integration\plugins\Pentaho Live`): la página de carpeta va
   antes que la del tipo y se lee como «dónde va el plugin». En la 0.4.2, `UsePreviousAppDir` +
   `DisableDirPage=auto` reutilizarían esa carpeta sin mostrar la página.
4. La clave `{54C78290-...}_is1` **existe** (leída por WMI). Claude Code lee HKCU\Software por la capa MSIX de Claude
   Desktop, donde las pruebas de la 0.4.1 la dejaron borrada. **Sin código.** HKCU\Environment y los archivos son reales.

## 2. Flujo nuevo

```
acceso / "Abrir ahora" → {app}\bin\IDCLauncher.exe   (splash 760×400 al instante)
  └ cmd.exe /d /s /c ""{app}\bin\pentaho-live.cmd" --launcher"   (sin ventana; hereda IDC_LAUNCHER_READY_FILE)
      ├ sin PDI      → crea la señal, MessageBox propio, exit 1 → el launcher cierra sin error
      ├ Spoon abierto (/ping del MCP) → crea la señal, trae Spoon al frente si puede, exit 0
      └ OPT += -Dorg.pentaho.di.laf.alt=plugins/pentaho-mcp/ui/laf-pentaho-live.properties (splash de PDI 1×1)
        Start-Process cmd.exe /d /s /c ""Spoon.bat"" (oculto) → exit 0 → el launcher SIGUE esperando
          └ javaw → Spoon.main → onStart → SenalLanzador restaura el look&feel y sondea
               → crea la señal al ver una ventana SWT visible nueva (o a los 15 s) → el launcher cierra
```
Sin launcher (un agente corre `pentaho-live`) nada cambia respecto de la 0.4.1, salvo la ruta por `cmd.exe`.

## 3. L · Launcher (copia de la plantilla del portafolio, extendida)

Extensiones **compatibles**: sin las claves nuevas, una app que hoy usa la plantilla se comporta igual. Se hacen
primero en la plantilla (`Convenciones Apps\plantillas\launcher\IDCLauncher.cs`, `plantillas\tools\generar_splash.py`)
y se copian **idénticas** al cookiecutter (lo exige `tests\test_splash_convention.py`) y al repo.
`build_launcher.bat` **no se toca**: `verificar_builds.py` lo compara byte a byte en todas las apps.

| Clave ini | Valores (default = hoy) | Comportamiento |
|---|---|---|
| `target` | nombre sin ruta: `.exe` (hoy) **o `.cmd`/`.bat`** | `.cmd/.bat`: `FileName=%SystemRoot%\System32\cmd.exe` (no `%ComSpec%`, que se puede cambiar), `Arguments=/d /s /c ""<ruta>" <args>"`, `UseShellExecute=false`, `CreateNoWindow=true`. Tiene que seguir dentro de la carpeta del launcher. Ruta con `%` → error amable (cmd expandiría variables). |
| `target_args` | vacío | Argumentos fijos, antes de los reenviados. Solo `[A-Za-z0-9 _=./:-]`; otro carácter invalida el ini. |
| `child` | `app` \| `handoff` | `handoff` exige `ready=signal`. Si el hijo sale con 0 **no** cierra: deja de mirarlo y sigue esperando la señal o `fallback_ms`. |
| `quiet_exit_codes` | vacío | Lista, p. ej. `1,2`. Hijo que sale con uno de esos (cualquier `child`) → cierre normal sin error ni log: ya mostró su mensaje. Otro ≠0 antes de 10 s → error amable, como hoy. |
| `language` | `es` \| `en` \| `pt` \| `auto` | Idioma del error amable. `auto` mira `CurrentUICulture`: es→es, pt→pt, resto→en. El error de `Program.Main` (ini ilegible) sigue en `es`. |
| `topmost` | `yes` \| `no` | `no`: ventana normal **con botón en la barra de tareas** (`Text=name`, ícono del exe), para cargas largas. |

Error amable: `es` sin cambios. `en`: «The application could not be opened» / «{name} could not be started.\n\nTry
opening it again. If it keeps happening, contact IDC support.». `pt`: «Não foi possível abrir o aplicativo» /
«Não foi possível iniciar {name}.\n\nTente abri-lo novamente. Se acontecer de novo, avise o suporte IDC.».
`--self-test` valida todas las claves (exit 2 si algo está mal). Esc sigue cerrando el splash.

`generar_splash.py` suma `--estado` (default `INICIANDO APLICACIÓN`) y `--ficha` (default `Windows  ·  Offline`; la
línea queda `v{versión}  ·  {ficha}`); con los defaults el PNG sale idéntico byte a byte. Documentar todo en
`03-build-exe-bat.md` («Launcher animado de arranque») y sumar un test (los argumentos nuevos cambian el PNG).

**En el repo:** `instalador\launcher\IDCLauncher.cs` y `generar_splash.py` (copias idénticas), `fuentes\`
(`Outfit-Medium.ttf`, `Outfit-SemiBold.ttf`, `OFL.txt`) e `idc-launcher.ini` (ASCII):
```ini
# Lanzador de Pentaho Live (IDCLauncher, convencion 03). Lo compila instalador\preparar.ps1.
target=pentaho-live.cmd
target_args=--launcher
name=Pentaho Live
ready=signal
child=handoff
quiet_exit_codes=1
language=auto
topmost=yes
minimum_display_ms=1200
fallback_ms=180000
```
`fallback_ms=180000`: el PDI de Iván tarda 30-60 s y el splash de PDI queda oculto, así que cerrar antes dejaría la
pantalla vacía; con `topmost=no` y Esc, la espera no estorba. Ejecutable: **`IDCLauncher.exe` en `{app}\bin`**,
junto a `pentaho-live.cmd` (`target` va sin ruta). Queda en el PATH del usuario, lo que es inocuo.

## 4. J · Plugin: `SenalLanzador.java` (nuevo) + un gancho

`McpLifecycleListener.java` cambia **dos líneas**: `SenalLanzador.programar();` como primera línea de `onStart`
(antes del `try` del servidor: avisa aunque el MCP no arranque) y `VERSION_RESPALDO = "0.4.2"`. Nada más ahí: lo
toca otra rama. `final class SenalLanzador` (paquete `io.github.ivi2511.pentahomcp`, Java 8, lambdas sí).
`static void programar()` **nunca lanza** (todo en `catch (Throwable)`) y hace:
1. **Restaurar el look&feel.** Si `System.getProperty("org.pentaho.di.laf.alt")` termina en `laf-pentaho-live.properties`:
   `LAFFactory.getHandler(PropertyHandler.class).loadProps("ui/laf.properties")` (público, hace `addFirst`).
   **Obligatorio:** Ayuda → Acerca de usa `new Splash(display, shell)` con el mismo `splash_image` y solo se cierra con
   un clic sobre la imagen; con 1×1 sería un modal invisible que traba Spoon. El splash de arranque ya cargó su imagen.
2. **Validar** `System.getenv("IDC_LAUNCHER_READY_FILE")`: no vacío, absoluto, nombre
   `ready-\d+-[0-9a-fA-F]{32}\.signal`, padre `Launcher`, abuelo `IDC` (sin mayúsculas). No se compara con
   `%TEMP%`: el launcher usa `GetTempPath` (TMP antes que TEMP, a veces nombres 8.3) y sería frágil; alcanza porque
   el archivo es vacío y solo se crea si no existe. Ausente → nada; inválido → `logBasic` con el nombre y nada más.
3. **Sondear** en el hilo de UI (`Display.getCurrent()`, si no `UiThread.display()`, si no escribir ya): guardar las
   `Shell` visibles (el splash de PDI) y `display.timerExec(250, this)`. Listo = una `Shell` visible, no dispuesta y
   **no** guardada (la ventana principal o un diálogo de arranque, p. ej. el login de repositorio). A los 15 s de
   `programar()` escribe igual (cubre un MessageBox nativo de `createContents`, que el timer atraviesa). Corta si
   `display.isDisposed()`. `timerExec` y no `asyncExec`: corre después de pintar.
4. **Gancho de salida** (`Runtime.addShutdownHook`): escribe si no se escribió y pasaron **≤ 300 s** desde
   `programar()` (lo máximo que espera el launcher); si no, no deja basura en `%TEMP%`.
5. **Escribir** una sola vez (`synchronized` + bandera): `Files.createFile(ruta)`; `FileAlreadyExistsException` =
   ya estaba. Log `logDetailed` al programar y al escribir (motivo: ventana / plazo / salida, y los ms); las
   excepciones a `logError`, nunca hacia Spoon.

**Interfaz:** `spoon-plugin\ui\laf-pentaho-live.properties` (`splash_image=plugins/pentaho-mcp/ui/splash-vacio.png`)
y `spoon-plugin\ui\splash-vacio.png` (PNG 1×1 transparente), copiados del experimento `splashtest`.
`spoon-plugin\build.ps1` los copia a `dist\pentaho-mcp\ui\` (y al zip) y corta si faltan. Rutas relativas:
`Spoon.bat` hace `cd` a `data-integration`.

## 5. I · Instalador, `pentaho-live` y documentación

**`instalador\bin\pentaho-live.cmd`** (CRLF, comentarios en inglés):
- **`--launcher`** (implica `--gui`). Exit codes iguales: 0 = arrancando o ya abierto, 1 = sin PDI. Ayuda y cabecera
  la nombran («used by IDCLauncher.exe»).
- **`:signal_ready`**: solo si `IDC_LAUNCHER_READY_FILE` está definida, termina en `.signal` y su carpeta se llama
  `Launcher` → `type nul > "%IDC_LAUNCHER_READY_FILE%"`. En `--launcher` va **antes** de cualquier MessageBox.
- **`:no_pdi`** en `--launcher`: señal, MessageBox con dueño TopMost
  (`$f=New-Object Windows.Forms.Form; $f.TopMost=$true; [Windows.Forms.MessageBox]::Show($f,...)`), exit 1.
- **`:already_open`** en `--launcher`: señal, sin MessageBox; al frente sin costo extra: primer `javaw`/`java` con
  `MainWindowTitle -match 'Spoon'` → `(New-Object -ComObject WScript.Shell).AppActivate($pid)`, errores ignorados.
- **Splash de PDI**: solo en `--launcher`, con la señal válida y si existe
  `%PL_PDI%\plugins\pentaho-mcp\ui\laf-pentaho-live.properties`, agregar
  `-Dorg.pentaho.di.laf.alt=plugins/pentaho-mcp/ui/laf-pentaho-live.properties` a `OPT`, respetando un `OPT` previo
  (`Spoon.bat` lo pone delante). Sin launcher, nunca: no quedaría ningún splash.
- **Arranque**: `Start-Process -FilePath "$env:SystemRoot\System32\cmd.exe" -ArgumentList '/d','/s','/c',('{0}{0}{1}{0}{0}' -f [char]34, $env:PL_SPOON) -WorkingDirectory $env:PL_PDI -WindowStyle Hidden -ErrorAction Stop`.
  El respaldo `start "Spoon" ...` no cambia. Comentar por qué `cmd.exe` (MOTW) y `/s` (`( ) &`).
- **Dry run**: `would run: cmd.exe /d /s /c ""<Spoon.bat>"" (PowerShell Start-Process, hidden)`, más
  `launcher: yes|no` y `PDI splash: hidden (OPT ...)|shown`. **`instalador\bin\pentaho-live`** (LF): sin cambios.

**`instalador\instalador.iss`:**
- **`#ifdef PRUEBA`**: `MyAppName "Pentaho Live (prueba)"`, AppId `{{E1982040-3618-4ADA-B1F8-BA6601BCA88A}`, salida
  `Instalador_PentahoLive_<v>_prueba`; sin `PRUEBA`, lo de siempre. `DefaultDirName={autopf}\{#MyAppName}`.
  Cabecera: `ISCC /DPRUEBA instalador.iss` para probar.
- **Archivos**: `#error` si falta `staging\bin\IDCLauncher.exe`; `[Files]` suma `IDCLauncher.exe`,
  `idc-launcher.ini`, `idc-launcher.png` → `{app}\bin` (`ignoreversion`).
- **Accesos**: `[Icons]` de menú Inicio y escritorio con `Filename: "{app}\bin\IDCLauncher.exe"`, sin `Parameters`,
  `WorkingDir: "{app}\bin"`, `IconFilename: "{app}\pentaho-live.ico"`, **sin** `runminimized`. `[Run]` «Abrir
  ahora»: `{app}\bin\IDCLauncher.exe`, `Flags: nowait postinstall skipifsilent`. Más
  `[InstallDelete] Type: files; Name: "{autodesktop}\{#MyAppName}.lnk"; Tasks: not desktopicon`.
- **`DentroDePdi(Ruta)`**: la ruta o un ancestro tiene `Spoon.bat`, o un segmento es `data-integration` (sin
  mayúsculas). En `NextButtonClick(wpSelectDir)` rechaza con `DirInPdi`. En `InitializeWizard`, si
  `WizardForm.DirEdit.Text` (la carpeta anterior) cae dentro de un PDI y no vino `/DIR=`: guardar `AppAnterior`,
  poner `{autopf}\{#MyAppName}` y loguearlo (la página sigue oculta por `DisableDirPage=auto`; la carpeta nueva se
  ve en «Listo para instalar»). En `PrepareToInstall`, si `WizardDirValue` cae dentro (un `/DIR=` silencioso),
  aborta con `DirInPdi`.
- **Reparación** en `ssPostInstall`, **antes** de `AgregarAlPath`. Carpetas viejas: `AppAnterior` y, en «solo el
  plugin», cada subcarpeta de `<PdiElegido>\plugins\` con `unins000.exe` y `bin\pentaho-live.cmd`, distinta de
  `{app}`. Para cada una: `QuitarDelPath(<vieja>\bin)`; borrar solo lo conocido (`unins000.exe/.dat/.msg`,
  `pentaho-live.ico`, `instalar.ps1`, `LICENSE.txt` y en `bin\`: `pentaho-live.cmd`, `pentaho-live`,
  `pdi-home.txt`, `IDCLauncher.exe`, `idc-launcher.ini`, `idc-launcher.png`); `RemoveDir` de `bin` y de la
  carpeta; lo que quede se loguea y no se toca. Solo salen del PATH las rutas de carpetas reparadas, nunca
  cualquier `\Pentaho Live\bin`: así una prueba con un PDI falso no toca el PATH real de Iván.
- Sin código para la clave de desinstalación (§1.4) y sin archivo centinela.
- **Textos** (en/pt: traducción directa, con el tono de los mensajes existentes). `[Messages] SelectDirLabel3`, es:
  «El instalador pondrá [name] en esta carpeta: el lanzador, el comando pentaho-live y el desinstalador (y, en la
  completa, PDI y Java). Use una carpeta propia, no una dentro de Pentaho Data Integration: para «Solo el plugin»,
  la carpeta data-integration se pide después.» `[CustomMessages] DirInPdi` (`%1` = carpeta), es: «Esta carpeta
  está dentro de un Pentaho Data Integration:%n%n%1%n%nElija una carpeta propia para Pentaho Live (por ejemplo, la
  que propone el instalador). Para poner solo el plugin en su PDI, elija «Solo el plugin» en la página siguiente:
  la carpeta data-integration se pide después.»

**`instalador\preparar.ps1`**: paso nuevo después de las imágenes (usa el `.ico` recién generado).
1. **Compilar** con el `csc` de .NET Framework 4 (`Framework64`, si no `Framework`) como `build_launcher.bat`:
   `/target:winexe /optimize+ /platform:anycpu`, `System`, `System.Drawing`, `System.Windows.Forms`,
   `/win32icon:instalador\pentaho-live.ico`, `/out:staging\bin\IDCLauncher.exe`, y un `AssemblyInfo.cs` generado en
   `instalador\cache\` (Title/Product `Pentaho Live`, Company `IDC Studios`, Description `Pentaho Live launcher`,
   versión `X.Y.Z.0`, InformationalVersion `X.Y.Z`). No `build_launcher.bat`: espera la salida de PyInstaller,
   copia `..\VERSION` y es la canónica que se compara entre apps.
2. **Copiar** `idc-launcher.ini` y **generar** `staging\bin\idc-launcher.png`:
   `instalador\launcher\generar_splash.py --titulo "Pentaho Live" --subtitulo "Claude builds your ETLs live, in Spoon." --version X.Y.Z --icono instalador\pentaho-live.ico --rotulo "Pentaho Data Integration 9.4" --estado "OPENING SPOON" --espera "This takes about 30-60 seconds<…>" --ficha "Windows  <·>  Spoon 9.4  <·>  MCP"`
   (en inglés, como el banner del asistente; `·` y `…` con `[char]0x00B7`/`[char]0x2026`: el `.ps1` sigue ASCII).
3. **`--self-test`**: es GUI → `Start-Process -Wait -PassThru` y mirar `ExitCode` (con `&` no espera).
4. **Controles**: los tres archivos en `staging\bin`; PNG de 760×400; `FileVersion` = `X.Y.Z.0`;
   `ui\laf-pentaho-live.properties` y `ui\splash-vacio.png` en las dos copias del plugin.

**Versión y docs:** `VERSION` y `claude\.claude-plugin\plugin.json` → `0.4.2`. `CHANGELOG.md` `## [0.4.2] - 2026-10-20`
(la sesión principal corrige la fecha si el release sale otro día) + enlaces. **Added**: splash del lanzador hasta que
aparece Spoon (el de PDI se oculta solo ahí; «Acerca de» no cambia); `pentaho-live --launcher`. **Fixed**: sin aviso de
seguridad de `Spoon.bat` en un PDI de un zip bajado; la carpeta de instalación no puede quedar dentro de un PDI y la
página lo explica; una 0.4.1 instalada ahí se mueve sola. `INSTALL.md` (Opción C): carpeta propia, splash, mudanza y una
fila de problemas para el aviso al abrir `Spoon.bat` a mano (desbloquear el zip o Propiedades → Desbloquear).
`docs/roadmap.md`: una línea de la 0.4.2. Notas de Pentaho (`CLAUDE.md`): lanzador, `PRUEBA`, `PENTAHO_MCP_PORT`, `_is1` por WMI.

## 6. Propiedad de archivos (ninguno con dos dueños)

| Dueño | Archivos |
|---|---|
| **L** | `instalador/launcher/IDCLauncher.cs`, `generar_splash.py`, `idc-launcher.ini`, `fuentes/*`. En Convenciones Apps: `plantillas/launcher/IDCLauncher.cs`, `plantillas/tools/generar_splash.py`, esas dos rutas en `cookiecutter-app/{{cookiecutter.carpeta}}/`, `03-build-exe-bat.md`, `tests/test_splash_convention.py` |
| **J** | `spoon-plugin/src/main/java/.../SenalLanzador.java` (nuevo), `.../McpLifecycleListener.java` (2 líneas), `spoon-plugin/ui/*` (nuevo), `spoon-plugin/build.ps1` |
| **I** | `instalador/bin/pentaho-live.cmd`, `instalador/bin/pentaho-live`, `instalador/instalador.iss`, `instalador/preparar.ps1`, `VERSION`, `claude/.claude-plugin/plugin.json`, `CHANGELOG.md`, `INSTALL.md`, `docs/roadmap.md`, `Pentaho/CLAUDE.md` |
| sin cambios | `instalador/generar_imagenes.py`, `README*.md`, `instalar.ps1`, `build_launcher.bat` |

Interfaces: archivo de señal (§3-§5); `plugins/pentaho-mcp/ui/laf-pentaho-live.properties` (J lo entrega, I lo usa);
argumentos de `generar_splash.py` (L los define, I los usa); exit 1 de `pentaho-live.cmd` ↔ `quiet_exit_codes=1`.

## 7. T · Plan de pruebas

Reglas: no abrir el Spoon real; no tocar el PDI de Iván, su carpeta `Pentaho Live`, su menú Inicio, su escritorio ni
`~\.kettle`; lo falso va en el scratchpad (`<wf042>`); siempre `PENTAHO_MCP_PORT=59999` (si el Spoon de Iván está
abierto en 8765, el `.cmd` y el instalador lo detectarían); avisar que el launcher muestra ventanas unos segundos.

- **M1 MOTW.** `Spoon.bat` falso (CRLF, `Zone.Identifier` ZoneId=3) que hace `start` de un exe sin marca que escribe un
  marcador, en carpetas con espacio, `ñ` y `( ) &` (reusar `motw\`). `pentaho-live.cmd` nuevo con `pdi-home.txt` ahí,
  desde Git Bash (`out=$(cmd //c ...)`): vuelve en < 10 s, aparece el marcador y no queda PowerShell colgado.
- **L1 feliz.** `{app}\bin` falso (`staging\bin` + `pdi-home.txt`) y PDI falso con `plugins\pentaho-mcp\ui\`; su
  `Spoon.bat` (marcado) arranca PowerShell oculto que guarda `OPT` e `IDC_LAUNCHER_READY_FILE`, espera 4 s y crea la
  señal. Esperado: splash enseguida, sin aviso, cierre a ~4-5 s, `OPT` con el `laf.alt`, señal borrada al cerrar.
- **L2 sin señal:** ini de prueba con `fallback_ms=5000` → cierra a ~5 s. **L3 sin PDI:** el MessageBox se ve por
  encima del splash; al cerrarlo (WM_CLOSE) el launcher ya se fue, sin error ni línea nueva en `launcher.log`.
  **L4 ya abierto:** listener HTTP en 59998 (con `PENTAHO_MCP_PORT=59998` solo acá) que contesta `/ping` con
  `{"ok":true}` → cierra tras ~1,2 s y no corre `Spoon.bat`. **L5 error:** un `.cmd` que sale con 3 → error amable
  en el idioma de Windows; cerrarlo matando el proceso.
- **L6 compatibilidad:** la plantilla nueva con un ini viejo (`.exe`, sin claves nuevas) pasa `--self-test` y un
  `.exe` que sale con 0 cierra el launcher enseguida, como hoy. `--self-test` da 2 con `child=handoff`+`ready=window`,
  `target=a\b.cmd`, `target=x.ps1`, `language=xx`, `quiet_exit_codes=a`, `target_args` con `&`. `pytest tests` de
  la convención en verde y `verificar_builds.py` sin diferencias nuevas.
- **J1** (arnés SWT como `splashtest`, jars del PDI de prueba, `KettleLogStore.init()` primero, variable de entorno
  en la JVM): ventana nueva al segundo → señal en < 0,5 s; sin ventana → ~15 s; nombre inválido o variable ausente →
  ni archivo ni excepción; JVM que sale antes → la escribe el gancho. **J2:** con `-Dorg.pentaho.di.laf.alt=...`,
  `BasePropertyHandler.getProperty("splash_image")` da el PNG 1×1 antes de `programar()` y el original después, y un
  `Splash(display, shell)` posterior mide 700×500. `build.ps1` deja `dist\pentaho-mcp\ui\` y el zip con los dos.
- **I1** (solo con autorización, §8): `preparar.ps1 -PdiHome <PDI de prueba>` y `ISCC /DPRUEBA instalador.iss`.
  Antes, guardar en `<wf042>` el valor crudo de `HKCU\Environment\Path` (`GetValue(...,'DoNotExpandEnvironmentNames')`
  + tipo). Estado roto falso en `<wf042>\inst042\pdi\data-integration`: `Spoon.bat`, `lib\kettle-core-9.4.0.0-343.jar`
  vacío, `plugins\Pentaho Live\{unins000.exe,unins000.dat,pentaho-live.ico,bin\pentaho-live.cmd,bin\pdi-home.txt}`.
  En el PATH, su `...\plugins\Pentaho Live\bin` y un señuelo `<wf042>\inst042\otro\Pentaho Live\bin` (con
  `pentaho-live.cmd`) que **no** debe salir.
- **I2** desde PowerShell (no Git Bash), `Start-Process -Wait`: `/VERYSILENT /SUPPRESSMSGBOXES /NORESTART
  /TYPE=pluginonly /PDIHOME=<falso> /TASKS="" /DIR=<wf042>\inst042\app /LOG=...` (`/TASKS=""` siempre: las tareas
  claude/codex configurarían el Claude Code real). Verificar: los tres archivos del launcher y `pdi-home.txt` en
  `{app}\bin`; `plugins\pentaho-mcp\ui\` en el PDI falso; `plugins\Pentaho Live` borrada; PATH sin la entrada vieja,
  con el señuelo y con `{app}\bin`; el acceso «Pentaho Live (prueba)» apunta a `IDCLauncher.exe` (leer el `.lnk` con
  `WScript.Shell`); la clave `{E1982040-...}_is1` con `reg query` (misma vista que Setup).
- **I3** `/TYPE=full /DIR=<falso>\plugins\Otra` → falla sin instalar nada. **I4 mudanza:** recrear el estado roto, poner
  a mano en la clave de prueba `_is1` `Inno Setup: App Path` = carpeta dentro del PDI falso e instalar sin `/DIR` →
  `{app}` = `%LOCALAPPDATA%\Programs\Pentaho Live (prueba)`, mudanza en el log, carpeta vieja reparada.
- **I5** desinstalar cada instalación (`unins000.exe /VERYSILENT /SUPPRESSMSGBOXES`, esperar hasta 60 s a que
  desaparezca `{app}`): sin carpeta, sin menú Inicio «(prueba)», sin clave, sin `{app}\bin` en el PATH. Después
  **restaurar el PATH byte a byte** desde la copia y compararlo. Borrar los falsos y el `_prueba.exe`.
- **I6** compilación real (sin `PRUEBA`) → `Instalador_PentahoLive_0.4.2.exe` con FileVersion 0.4.2. **No** se
  ejecuta: lo prueba Iván en su PDI real (aviso de seguridad, splash, mudanza, Acerca de).

## 8. Preguntas abiertas para Iván

**Respondidas por Iván el 20-oct-2026, 18:55:** (1) sí, Claude compila los dos (prueba y real); (2) el splash
va **siempre encima** (`topmost=yes`, como en las otras apps IDC; lo aplica la sesión principal al cerrar el
workflow); (3) queda como está.

1. **¿Autoriza compilar la 0.4.2 con ISCC** (prueba y real)? Las notas de Pentaho: la 0.4.1 se compiló con permiso y
   «para otra versión, preguntar». Sin permiso, T llega hasta `preparar.ps1` y compila él (F9; `/DPRUEBA` la de prueba).
2. **Splash no fijo encima (`topmost=no`, con botón en la barra):** la carga dura 30-60 s y no debería tapar otras
   ventanas. Si lo quiere como en las otras apps, es `topmost=yes` en el ini.
3. **Abrir `Spoon.bat` a mano seguirá mostrando el aviso** (sus `.bat` vienen de un zip bajado); Pentaho Live ya no lo
   dispara. Quitarlo ahí lo hace él (Propiedades → Desbloquear o `Unblock-File`): el instalador no toca su PDI.

## 9. Ajustes tras la revisión (20-oct-2026)

- `:no_pdi` (`--launcher`): la señal la crea el PowerShell justo antes de `MessageBox.Show` (cargar Windows Forms
  tarda ~1,5 s); antes el splash se cerraba y la caja aparecía sola después. Sin `call :signal_ready` al volver:
  el launcher ya la borró y el exit 1 (`quiet_exit_codes=1`) lo cierra igual.
- Arranque: `-WorkingDirectory ([Management.Automation.WildcardPattern]::Escape($env:PL_PDI))`: `Start-Process`
  la toma como comodín y una carpeta con `[` o `]` caía en el respaldo `start` (consola minimizada que queda).
- `SenalLanzador.escribir`: no escribe si el lanzador ya cerró (pid del nombre `ready-{pid}-…`,
  `ProcessHandle` por reflexión; en Java 8 se escribe como antes). Así no queda basura en `%TEMP%` tras Esc,
  el botón de la barra o `fallback_ms`.
- Reparación: también borra la completa vieja (`data-integration` con `plugins\pentaho-mcp`, `jre`, `licenses`;
  nunca si la carpeta vieja es un PDI ella misma) y el plugin viejo del PDI de su `pdi-home.txt` si esta vez no va
  ahí (y Spoon no lo usa); `PrepareToInstall` pide cerrar el Spoon de la completa vieja. Sin clave `_is1`,
  encuentra la vieja por el PATH: un `...\bin` dentro de un PDI cuyo `unins000.dat` tenga **este** AppId
  (cabecera, byte 64), así `/DPRUEBA` nunca toma la instalación real. Sigue sin salir del PATH nada que no se repare.
- `ContienePdiAjeno`: tampoco se acepta la carpeta que contiene un `data-integration` (con `Spoon.bat`) que no es
  de una instalación anterior (sin `bin\pentaho-live.cmd` ni nuestro desinstalador); mensaje `DirHasPdi`.
- INSTALL.md: para agentes, `/MERGETASKS="!claude,!codex"`; `/TASKS=""` en una actualización borra el acceso del
  escritorio (`[InstallDelete] ... Tasks: not desktopicon`).
