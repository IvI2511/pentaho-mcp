; ====================================================================
;  instalador.iss - Script de Inno Setup 7 para "Pentaho Live" (IDC Studios):
;  Pentaho Data Integration 9.4 CE + el plugin de Spoon con el servidor MCP
;  adentro + Java 21 (Eclipse Temurin), o solo el plugin en un PDI que ya
;  este instalado.
;
;  Como usar:
;    1) powershell -ExecutionPolicy Bypass -File instalador\preparar.ps1 -PdiHome "<...>\data-integration"
;       -> deja todo en instalador\staging\ (PDI limpio, plugin, JRE, lanzador
;          IDCLauncher.exe con su splash, pentaho-live, imagenes)
;    2) Abrir este archivo con Inno Setup 7 -> Build > Compile (F9)
;    3) El instalador queda en:
;       instalador\Instalador_PentahoLive_<version>.exe
;  Lo compila Ivan (regla del portafolio): este script no se compila con ISCC
;  desde un agente, salvo que Ivan lo pida.
;
;  Para probar sin tocar la instalacion real: ISCC /DPRUEBA instalador.iss
;  -> Instalador_PentahoLive_<version>_prueba.exe, con otro AppId y el nombre
;  "Pentaho Live (prueba)" (su propia carpeta, menu Inicio, acceso y clave de
;  desinstalacion). El PATH del usuario si es el real: guardarlo antes.
;
;  Va en instalador\ y no en la raiz (la convencion 04 lo pone en la raiz de la
;  app): la raiz de este repo es el marketplace publico de Claude Code. Por eso
;  las rutas son relativas a instalador\ y la version se lee de ..\VERSION.
; ====================================================================

#ifdef PRUEBA
  #define MyAppName "Pentaho Live (prueba)"
  #define MyAppId "{{E1982040-3618-4ADA-B1F8-BA6601BCA88A}"
  #define MyOutputSuffix "_prueba"
#else
  #define MyAppName "Pentaho Live"
  #define MyAppId "{{54C78290-359B-45E0-BB3A-2D69FB54CAE6}"
  #define MyOutputSuffix ""
#endif
; La version se lee del archivo VERSION de la raiz del repo (fuente unica:
; tambien la leen spoon-plugin\build.ps1 y el plugin).
#define VerHandle FileOpen(AddBackslash(SourcePath) + "..\VERSION")
#define MyAppVersion Trim(FileRead(VerHandle))
#expr FileClose(VerHandle)
#define MyAppPublisher "IDC Studios"
#define MyAppURL "https://github.com/IvI2511/pentaho-mcp"
#define MyDocsURL "https://github.com/IvI2511/pentaho-mcp/blob/main/INSTALL.md"

#if !FileExists(AddBackslash(SourcePath) + "staging\data-integration\Spoon.bat")
  #error Falta instalador\staging: correr antes instalador\preparar.ps1 -PdiHome "<...>\data-integration"
#endif
#if !FileExists(AddBackslash(SourcePath) + "staging\bin\IDCLauncher.exe")
  #error Falta staging\bin\IDCLauncher.exe (el lanzador): correr de nuevo instalador\preparar.ps1
#endif

[Setup]
AppId={#MyAppId}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}/issues
AppUpdatesURL={#MyAppURL}/releases
VersionInfoVersion={#MyAppVersion}
VersionInfoCompany={#MyAppPublisher}
VersionInfoProductName={#MyAppName}
VersionInfoDescription={#MyAppName} Setup
DefaultDirName={autopf}\{#MyAppName}
; Al actualizar se reusa la carpeta de la vez anterior sin mostrar la pagina de
; destino (UsePreviousAppDir y DisableDirPage ya son asi por defecto; van escritos
; porque [Code] cuenta con eso). Si esa carpeta cae dentro de un PDI (la 0.4.1 lo
; permitia), InitializeWizard la cambia por la de por defecto, que igual se ve en
; "Listo para instalar".
UsePreviousAppDir=yes
DisableDirPage=auto
AlwaysShowDirOnReadyPage=yes
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
SetupIconFile=staging\pentaho-live.ico
UninstallDisplayIcon={app}\pentaho-live.ico
OutputDir=.
OutputBaseFilename=Instalador_PentahoLive_{#MyAppVersion}{#MyOutputSuffix}
Compression=lzma2
SolidCompression=yes
PrivilegesRequired=lowest
; El JRE y el SWT de PDI son de 64 bits.
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
WizardStyle=modern
; La pagina de bienvenida viene APAGADA por defecto con
; WizardStyle=modern, y es la unica donde se ve el banner.
DisableWelcomePage=no
WizardImageFile=staging\asistente\banner.bmp,staging\asistente\banner@2x.bmp
WizardSmallImageFile=staging\asistente\logo.bmp,staging\asistente\logo@2x.bmp
; El idioma sale del de Windows; el dialogo solo aparece si no coincide con ninguno.
ShowLanguageDialog=auto
; {app}\bin va al PATH del usuario (lo hace [Code]): avisar a Explorer al terminar.
ChangesEnvironment=yes
; Log en %TEMP%\Setup Log *.txt (incluye la salida de instalar.ps1 -SoloAgentes).
SetupLogging=yes

[Languages]
Name: "en"; MessagesFile: "compiler:Default.isl"
Name: "es"; MessagesFile: "compiler:Languages\Spanish.isl"
Name: "pt"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"

[Messages]
; La pagina de destino sale ANTES de la del tipo: con la 0.4.1 se leia como "donde
; va el plugin" y hubo quien eligio una carpeta dentro de su PDI.
en.SelectDirLabel3=Setup will put [name] in this folder: the launcher, the pentaho-live command and the uninstaller (and, in the full install, PDI and Java). Use a folder of its own, not one inside Pentaho Data Integration: for “Plugin only”, the data-integration folder is asked for later.
es.SelectDirLabel3=El instalador pondrá [name] en esta carpeta: el lanzador, el comando pentaho-live y el desinstalador (y, en la completa, PDI y Java). Use una carpeta propia, no una dentro de Pentaho Data Integration: para «Solo el plugin», la carpeta data-integration se pide después.
pt.SelectDirLabel3=O instalador vai colocar o [name] nesta pasta: o lançador, o comando pentaho-live e o desinstalador (e, na completa, o PDI e o Java). Use uma pasta própria, não uma dentro do Pentaho Data Integration: para “Somente o plugin”, a pasta data-integration é pedida depois.

[CustomMessages]
en.DirInPdi=This folder is inside a Pentaho Data Integration:%n%n%1%n%nChoose a folder of its own for Pentaho Live (for example, the one Setup suggests). To put only the plugin into your PDI, choose “Plugin only” on the next page: the data-integration folder is asked for later.
es.DirInPdi=Esta carpeta está dentro de un Pentaho Data Integration:%n%n%1%n%nElija una carpeta propia para Pentaho Live (por ejemplo, la que propone el instalador). Para poner solo el plugin en su PDI, elija «Solo el plugin» en la página siguiente: la carpeta data-integration se pide después.
pt.DirInPdi=Esta pasta está dentro de um Pentaho Data Integration:%n%n%1%n%nEscolha uma pasta própria para o Pentaho Live (por exemplo, a que o instalador sugere). Para colocar somente o plugin no seu PDI, escolha “Somente o plugin” na próxima página: a pasta data-integration é pedida depois.
en.DirHasPdi=This folder contains a Pentaho Data Integration (a data-integration folder):%n%n%1%n%nChoose a folder of its own for Pentaho Live (for example, the one Setup suggests). To put only the plugin into your PDI, choose “Plugin only” on the next page: the data-integration folder is asked for later.
es.DirHasPdi=Esta carpeta contiene un Pentaho Data Integration (una carpeta data-integration):%n%n%1%n%nElija una carpeta propia para Pentaho Live (por ejemplo, la que propone el instalador). Para poner solo el plugin en su PDI, elija «Solo el plugin» en la página siguiente: la carpeta data-integration se pide después.
pt.DirHasPdi=Esta pasta contém um Pentaho Data Integration (uma pasta data-integration):%n%n%1%n%nEscolha uma pasta própria para o Pentaho Live (por exemplo, a que o instalador sugere). Para colocar somente o plugin no seu PDI, escolha “Somente o plugin” na próxima página: a pasta data-integration é pedida depois.
en.TypeFull=Full: Pentaho Data Integration 9.4 CE + Pentaho Live + Java 21 (recommended)
es.TypeFull=Completa: Pentaho Data Integration 9.4 CE + Pentaho Live + Java 21 (recomendada)
pt.TypeFull=Completa: Pentaho Data Integration 9.4 CE + Pentaho Live + Java 21 (recomendada)
en.TypePlugin=Plugin only, into a PDI 9.4 already installed on this computer
es.TypePlugin=Solo el plugin, en un PDI 9.4 que ya esté instalado en este equipo
pt.TypePlugin=Somente o plugin, em um PDI 9.4 já instalado neste computador
en.CompBundle=Pentaho Data Integration 9.4 CE with the Pentaho Live plugin, and Java 21 (Eclipse Temurin)
es.CompBundle=Pentaho Data Integration 9.4 CE con el plugin Pentaho Live, y Java 21 (Eclipse Temurin)
pt.CompBundle=Pentaho Data Integration 9.4 CE com o plugin Pentaho Live, e Java 21 (Eclipse Temurin)
en.CompPlugin=Pentaho Live plugin, into your PDI (you choose its folder on the next page)
es.CompPlugin=Plugin Pentaho Live, en su PDI (la carpeta se elige en la página siguiente)
pt.CompPlugin=Plugin Pentaho Live, no seu PDI (a pasta é escolhida na próxima página)
en.PdiPageCaption=Your Pentaho Data Integration
es.PdiPageCaption=Su Pentaho Data Integration
pt.PdiPageCaption=Seu Pentaho Data Integration
en.PdiPageDescription=Where is the data-integration folder of your PDI 9.4?
es.PdiPageDescription=¿Dónde está la carpeta data-integration de su PDI 9.4?
pt.PdiPageDescription=Onde está a pasta data-integration do seu PDI 9.4?
en.PdiPageSubCaption=Select the data-integration folder (the one that has Spoon.bat), then click Next. Setup replaces plugins\pentaho-mcp in it with this version.
es.PdiPageSubCaption=Seleccione la carpeta data-integration (la que tiene Spoon.bat) y haga clic en Siguiente. El instalador reemplaza ahí plugins\pentaho-mcp por esta versión.
pt.PdiPageSubCaption=Selecione a pasta data-integration (a que tem o Spoon.bat) e clique em Avançar. O instalador substitui ali plugins\pentaho-mcp por esta versão.
en.PdiNotFound=This folder has no Spoon.bat:%n%n%1%n%nSelect the data-integration folder of your Pentaho Data Integration.
es.PdiNotFound=Esta carpeta no tiene Spoon.bat:%n%n%1%n%nSeleccione la carpeta data-integration de su Pentaho Data Integration.
pt.PdiNotFound=Esta pasta não tem Spoon.bat:%n%n%1%n%nSelecione a pasta data-integration do seu Pentaho Data Integration.
en.PdiNot94=This PDI does not look like version 9.4 (there is no lib\kettle-core-9.4*.jar in it). Pentaho Live is built and tested for PDI 9.4.%n%nInstall the plugin there anyway?
es.PdiNot94=Este PDI no parece ser la versión 9.4 (no tiene lib\kettle-core-9.4*.jar). Pentaho Live está hecho y probado para PDI 9.4.%n%n¿Instalar el plugin igual?
pt.PdiNot94=Este PDI não parece ser a versão 9.4 (não tem lib\kettle-core-9.4*.jar). O Pentaho Live foi feito e testado para o PDI 9.4.%n%nInstalar o plugin mesmo assim?
en.ReadyPdi=Pentaho Data Integration (the plugin goes into plugins\pentaho-mcp):
es.ReadyPdi=Pentaho Data Integration (el plugin va en plugins\pentaho-mcp):
pt.ReadyPdi=Pentaho Data Integration (o plugin vai em plugins\pentaho-mcp):
en.SpoonOpen=Spoon is open (%1).%n%nWindows locks the plugin files while Spoon runs. Save your work, close Spoon (and any Pan or Kitchen started from that PDI), then click Retry.
es.SpoonOpen=Spoon está abierto (%1).%n%nWindows traba los archivos del plugin mientras Spoon corre. Guarde su trabajo, cierre Spoon (y cualquier Pan o Kitchen de ese PDI) y haga clic en Reintentar.
pt.SpoonOpen=O Spoon está aberto (%1).%n%nO Windows trava os arquivos do plugin enquanto o Spoon roda. Salve seu trabalho, feche o Spoon (e qualquer Pan ou Kitchen desse PDI) e clique em Repetir.
en.SpoonMcp=the Pentaho Live MCP answers on 127.0.0.1:%1
es.SpoonMcp=el MCP de Pentaho Live contesta en 127.0.0.1:%1
pt.SpoonMcp=o MCP do Pentaho Live responde em 127.0.0.1:%1
en.SpoonLock=%1 is in use
es.SpoonLock=%1 está en uso
pt.SpoonLock=%1 está em uso
en.SpoonOpenAbort=Spoon is open. Close it and run Setup again.
es.SpoonOpenAbort=Spoon está abierto. Ciérrelo y vuelva a ejecutar el instalador.
pt.SpoonOpenAbort=O Spoon está aberto. Feche-o e execute o instalador de novo.
en.GroupAgents=Connect your AI agent (found on this computer):
es.GroupAgents=Conectar su agente de IA (encontrado en este equipo):
pt.GroupAgents=Conectar seu agente de IA (encontrado neste computador):
en.TaskClaude=Configure &Claude Code (pentaho-live plugin)
es.TaskClaude=Configurar &Claude Code (plugin pentaho-live)
pt.TaskClaude=Configurar o &Claude Code (plugin pentaho-live)
en.TaskCodex=Configure Co&dex (MCP and skill)
es.TaskCodex=Configurar Co&dex (MCP y skill)
pt.TaskCodex=Configurar o Co&dex (MCP e skill)
en.ConfiguringClaude=Configuring Claude Code...
es.ConfiguringClaude=Configurando Claude Code...
pt.ConfiguringClaude=Configurando o Claude Code...
en.ConfiguringCodex=Configuring Codex...
es.ConfiguringCodex=Configurando Codex...
pt.ConfiguringCodex=Configurando o Codex...
en.DocsShortcut=Pentaho Live — Documentation
es.DocsShortcut=Pentaho Live — Documentación
pt.DocsShortcut=Pentaho Live — Documentação
en.ShortcutComment=Opens Spoon (Pentaho Data Integration) with Pentaho Live
es.ShortcutComment=Abre Spoon (Pentaho Data Integration) con Pentaho Live
pt.ShortcutComment=Abre o Spoon (Pentaho Data Integration) com o Pentaho Live
en.LaunchNow=Open Pentaho Live now
es.LaunchNow=Abrir Pentaho Live ahora
pt.LaunchNow=Abrir o Pentaho Live agora

[Types]
Name: "full"; Description: "{cm:TypeFull}"
Name: "pluginonly"; Description: "{cm:TypePlugin}"

[Components]
Name: "bundle"; Description: "{cm:CompBundle}"; Types: full
Name: "plugin"; Description: "{cm:CompPlugin}"; Types: pluginonly

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"
; Solo se ofrecen si el agente esta en el PATH; instalar.ps1 -SoloAgentes vuelve a mirarlo y, si no
; lo encuentra, lo saltea sin error.
Name: "claude"; Description: "{cm:TaskClaude}"; GroupDescription: "{cm:GroupAgents}"; Check: AgenteEnPath('claude')
Name: "codex"; Description: "{cm:TaskCodex}"; GroupDescription: "{cm:GroupAgents}"; Check: AgenteEnPath('codex')

[InstallDelete]
; El jar del plugin cambia de nombre con cada version: si queda el viejo, Spoon
; carga el plugin dos veces. Se borra la carpeta entera antes de copiar la nueva.
Type: filesandordirs; Name: "{app}\data-integration\plugins\pentaho-mcp"; Components: bundle
Type: filesandordirs; Name: "{code:CarpetaPluginExterna}"; Components: plugin
; Lo reescribe [Code] si es "solo el plugin"; en la completa no tiene que quedar uno viejo.
Type: files; Name: "{app}\bin\pdi-home.txt"
; Si esta vez no se pidio el acceso del escritorio, no queda el de una version anterior.
Type: files; Name: "{autodesktop}\{#MyAppName}.lnk"; Tasks: not desktopicon

[Files]
; Primero lo chico: con SolidCompression, para llegar a un archivo hay que
; descomprimir todo lo anterior, y "solo el plugin" no tiene que pasar por PDI.
Source: "staging\plugin\pentaho-mcp\*"; DestDir: "{code:CarpetaPluginExterna}"; Components: plugin; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "staging\bin\pentaho-live.cmd"; DestDir: "{app}\bin"; Flags: ignoreversion
Source: "staging\bin\pentaho-live"; DestDir: "{app}\bin"; Flags: ignoreversion
; El lanzador de los accesos (IDCLauncher, convencion 03): splash al instante y
; corre bin\pentaho-live.cmd --launcher. Lo compila preparar.ps1.
Source: "staging\bin\IDCLauncher.exe"; DestDir: "{app}\bin"; Flags: ignoreversion
Source: "staging\bin\idc-launcher.ini"; DestDir: "{app}\bin"; Flags: ignoreversion
Source: "staging\bin\idc-launcher.png"; DestDir: "{app}\bin"; Flags: ignoreversion
Source: "staging\pentaho-live.ico"; DestDir: "{app}"; Flags: ignoreversion
Source: "staging\instalar.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "staging\LICENSE.txt"; DestDir: "{app}"; Flags: ignoreversion
; La completa: PDI 9.4 CE (con su LICENSE.txt, y el plugin ya en plugins\pentaho-mcp) y el JRE.
Source: "staging\licenses\*"; DestDir: "{app}\licenses"; Components: bundle; Flags: ignoreversion
Source: "staging\jre\*"; DestDir: "{app}\jre"; Components: bundle; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "staging\data-integration\*"; DestDir: "{app}\data-integration"; Components: bundle; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
; Los accesos abren el lanzador: su splash se ve enseguida y se cierra cuando aparece
; la ventana de Spoon (30-60 s). El lanzador corre pentaho-live.cmd sin consola.
Name: "{group}\{#MyAppName}"; Filename: "{app}\bin\IDCLauncher.exe"; WorkingDir: "{app}\bin"; IconFilename: "{app}\pentaho-live.ico"; Comment: "{cm:ShortcutComment}"
Name: "{group}\{cm:DocsShortcut}"; Filename: "{#MyDocsURL}"
Name: "{group}\{cm:UninstallProgram,{#MyAppName}}"; Filename: "{uninstallexe}"; IconFilename: "{app}\pentaho-live.ico"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\bin\IDCLauncher.exe"; WorkingDir: "{app}\bin"; IconFilename: "{app}\pentaho-live.ico"; Comment: "{cm:ShortcutComment}"; Tasks: desktopicon

[Run]
; Configurar los agentes: solo el paso 4 de instalar.ps1 (sin PDI ni plugin).
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\instalar.ps1"" -SoloAgentes -SinCodex"; StatusMsg: "{cm:ConfiguringClaude}"; Tasks: claude; Flags: runhidden logoutput
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\instalar.ps1"" -SoloAgentes -SinClaude"; StatusMsg: "{cm:ConfiguringCodex}"; Tasks: codex; Flags: runhidden logoutput
Filename: "{app}\bin\IDCLauncher.exe"; WorkingDir: "{app}\bin"; Description: "{cm:LaunchNow}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
Type: files; Name: "{app}\bin\pdi-home.txt"
; Los logs que escribe Spoon de la instalacion completa.
Type: filesandordirs; Name: "{app}\data-integration\logs"

; OJO: [Code] va AL FINAL de todo. Lo que sigue es Pascal, no ini: un comentario
; con ";" ahi abajo lo toma como codigo y la compilacion muere con
; "'BEGIN' expected". Los comentarios de Pascal van con //.
[Code]
const
  // TFileStream: fmOpenRead ($0000) or fmShareExclusive ($0010). Abrir asi falla si
  // otro proceso tiene el archivo abierto, que es lo que hace Spoon con sus .jar.
  ModoLecturaExclusiva = $0010;
  // Para avisar a Explorer que cambio el PATH (HWND_BROADCAST, WM_SETTINGCHANGE,
  // SMTO_ABORTIFHUNG). Con nombres propios por si Inno ya define los de Windows.
  VentanaTodas = $FFFF;
  MensajeCambioConfig = $001A;
  NoEsperarColgadas = $0002;
  ClaveEntorno = 'Environment';

var
  PaginaPdi: TInputDirWizardPage;
  // La carpeta de una instalacion anterior que quedo dentro de un PDI (la 0.4.1 lo
  // permitia), o ''. Se repara en ssPostInstall.
  AppAnterior: String;

// API de Windows (user32, del sistema: ninguna DLL de terceros).
function EnviarMensajeConEspera(Ventana: HWND; Codigo: UINT; WP: WPARAM; LP: String;
  Banderas, Espera: UINT; var Resultado: DWORD_PTR): LRESULT;
  external 'SendMessageTimeoutW@user32.dll stdcall';
// Atributos de archivo (kernel32): para sacarle el "solo lectura" a una carpeta antes de borrarla.
function ObtenerAtributos(Ruta: String): DWORD;
  external 'GetFileAttributesW@kernel32.dll stdcall';
function PonerAtributos(Ruta: String; Atributos: DWORD): BOOL;
  external 'SetFileAttributesW@kernel32.dll stdcall';

// ------------------------------------------------------------ PDI

function EsPdi(const Carpeta: String): Boolean;
begin
  Result := False;
  if Trim(Carpeta) <> '' then
    Result := FileExists(AddBackslash(Carpeta) + 'Spoon.bat');
end;

function EsPdi94(const Carpeta: String): Boolean;
var
  Hallado: TFindRec;
begin
  Result := FindFirst(AddBackslash(Carpeta) + 'lib\kettle-core-9.4*.jar', Hallado);
  if Result then
    FindClose(Hallado);
end;

// True si la carpeta esta dentro de un Pentaho Data Integration: ella o alguna de
// las de arriba tiene Spoon.bat, o una carpeta de la ruta se llama data-integration.
// La carpeta de instalacion no puede caer ahi.
function DentroDePdi(const Ruta: String): Boolean;
var
  Carpeta, Arriba: String;
  Vueltas: Integer;
begin
  Result := False;
  Carpeta := Trim(Ruta);
  StringChangeEx(Carpeta, '/', '\', True);
  Carpeta := RemoveBackslashUnlessRoot(Carpeta);
  if Carpeta = '' then
    Exit;
  if Pos('\data-integration\', LowerCase(AddBackslash(Carpeta))) > 0 then begin
    Result := True;
    Exit;
  end;
  Vueltas := 0;
  while Vueltas < 64 do begin
    if EsPdi(Carpeta) then begin
      Result := True;
      Exit;
    end;
    Arriba := ExtractFileDir(Carpeta);
    if (Arriba = '') or (CompareText(Arriba, Carpeta) = 0) then
      Exit;
    Carpeta := Arriba;
    Vueltas := Vueltas + 1;
  end;
end;

// True si el unins000.dat de la carpeta es de esta app: Inno guarda el AppId en la cabecera,
// desde el byte 64. Asi una prueba (/DPRUEBA, otro AppId) no toma por suya la instalacion
// real, ni al reves.
function DesinstaladorNuestro(const Carpeta: String): Boolean;
var
  Datos: AnsiString;
  Texto, Id: String;
begin
  Result := False;
  Id := ExpandConstant('{#MyAppId}');
  if not LoadStringFromFile(AddBackslash(Carpeta) + 'unins000.dat', Datos) then
    Exit;
  Texto := Datos;
  Result := CompareText(Copy(Texto, 65, Length(Id)), Id) = 0;
end;

// True si la carpeta tiene adentro un PDI (data-integration\Spoon.bat) que no es de una
// instalacion anterior de Pentaho Live: con la completa, [Files] copiaria PDI encima del
// del usuario y al desinstalar lo borraria. La completa anterior trae el suyo, y tiene
// bin\pentaho-live.cmd o nuestro desinstalador.
function ContienePdiAjeno(const Ruta: String): Boolean;
var
  Carpeta: String;
begin
  Result := False;
  Carpeta := Trim(Ruta);
  StringChangeEx(Carpeta, '/', '\', True);
  Carpeta := RemoveBackslashUnlessRoot(Carpeta);
  if Carpeta = '' then
    Exit;
  if not EsPdi(AddBackslash(Carpeta) + 'data-integration') then
    Exit;
  Result := not FileExists(AddBackslash(Carpeta) + 'bin\pentaho-live.cmd') and not DesinstaladorNuestro(Carpeta);
end;

// El data-integration elegido en la pagina propia ("solo el plugin").
function PdiElegido: String;
begin
  Result := RemoveBackslashUnlessRoot(Trim(PaginaPdi.Values[0]));
end;

// {code:...} de [Files] e [InstallDelete] para "solo el plugin". Si no hubiera PDI
// elegido (no deberia pasar: lo validan NextButtonClick y PrepareToInstall) devuelve
// una carpeta dentro de {tmp} y nunca una ruta relativa.
function CarpetaPluginExterna(Param: String): String;
begin
  if EsPdi(PdiElegido) then
    Result := AddBackslash(PdiElegido) + 'plugins\pentaho-mcp'
  else
    Result := ExpandConstant('{tmp}\sin-pdi\pentaho-mcp');
end;

// El PDI que usa esta instalacion, en el instalador.
function PdiDeEstaInstalacion: String;
begin
  if WizardIsComponentSelected('plugin') then
    Result := PdiElegido
  else
    Result := ExpandConstant('{app}\data-integration');
end;

// El PDI de la instalacion, en el desinstalador: bin\pdi-home.txt o el que vino con ella.
function PdiInstalado: String;
var
  Lineas: TArrayOfString;
begin
  Result := ExpandConstant('{app}\data-integration');
  if LoadStringsFromFile(ExpandConstant('{app}\bin\pdi-home.txt'), Lineas) then
    if GetArrayLength(Lineas) > 0 then
      if Trim(Lineas[0]) <> '' then
        Result := RemoveBackslashUnlessRoot(Trim(Lineas[0]));
end;

// ------------------------------------------------------------ Spoon abierto

// Un mensaje de [CustomMessages], con un respaldo en ingles si no estuviera (se usa
// tambien desde el desinstalador, y ahi un error cortaria la desinstalacion).
function Mensaje(const Nombre, Respaldo: String): String;
begin
  try
    Result := CustomMessage(Nombre);
  except
    Result := Respaldo;
  end;
end;

// True si el MCP de Pentaho Live contesta: hay un Spoon abierto con el plugin.
function McpContesta(var Puerto: String): Boolean;
var
  Http: Variant;
begin
  Result := False;
  Puerto := Trim(GetEnv('PENTAHO_MCP_PORT'));
  if (StrToIntDef(Puerto, 0) < 1) or (StrToIntDef(Puerto, 0) > 65535) then
    Puerto := '8765';
  try
    Http := CreateOleObject('WinHttp.WinHttpRequest.5.1');
    Http.SetTimeouts(1000, 1000, 1000, 2000);
    Http.Open('GET', 'http://127.0.0.1:' + Puerto + '/ping', False);
    Http.Send('');
    Result := (Http.Status = 200);
  except
    Result := False;
  end;
end;

// True si otro proceso tiene abierto el archivo.
function ArchivoTrabado(const Archivo: String): Boolean;
var
  Flujo: TFileStream;
begin
  Result := False;
  if not FileExists(Archivo) then
    Exit;
  try
    Flujo := TFileStream.Create(Archivo, ModoLecturaExclusiva);
    Flujo.Free;
  except
    Result := True;
  end;
end;

// El primer .jar de ese PDI que esta en uso (launcher.jar lo abren Spoon, Pan y
// Kitchen; los del plugin, Spoon), o '' si no hay ninguno.
function JarTrabado(const Pdi: String): String;
var
  Hallado: TFindRec;
  Carpeta: String;
  Seguir: Boolean;
begin
  Result := '';
  if Trim(Pdi) = '' then
    Exit;
  if ArchivoTrabado(AddBackslash(Pdi) + 'launcher\launcher.jar') then begin
    Result := AddBackslash(Pdi) + 'launcher\launcher.jar';
    Exit;
  end;
  Carpeta := AddBackslash(Pdi) + 'plugins\pentaho-mcp\';
  if FindFirst(Carpeta + '*.jar', Hallado) then begin
    Seguir := True;
    while Seguir do begin
      if ArchivoTrabado(Carpeta + Hallado.Name) then begin
        Result := Carpeta + Hallado.Name;
        Seguir := False;
      end else
        Seguir := FindNext(Hallado);
    end;
    FindClose(Hallado);
  end;
end;

function SpoonAbierto(const Pdi: String; var Detalle: String): Boolean;
var
  Puerto, Trabado: String;
begin
  Detalle := '';
  if McpContesta(Puerto) then
    Detalle := FmtMessage(Mensaje('SpoonMcp', 'the Pentaho Live MCP answers on 127.0.0.1:%1'), [Puerto])
  else begin
    Trabado := JarTrabado(Pdi);
    if Trabado <> '' then
      Detalle := FmtMessage(Mensaje('SpoonLock', '%1 is in use'), [Trabado]);
  end;
  Result := Detalle <> '';
end;

function PedirReintentar(const Texto: String): Boolean;
begin
  if IsUninstaller then begin
    if UninstallSilent then
      Result := False
    else
      Result := MsgBox(Texto, mbError, MB_RETRYCANCEL) = IDRETRY;
  end else
    Result := SuppressibleMsgBox(Texto, mbError, MB_RETRYCANCEL, IDCANCEL) = IDRETRY;
end;

// Pide cerrar Spoon hasta que este cerrado (True) o el usuario cancele (False).
function EsperarQueCierrenSpoon(const Pdi: String): Boolean;
var
  Detalle: String;
begin
  Result := True;
  while Result and SpoonAbierto(Pdi, Detalle) do
    Result := PedirReintentar(FmtMessage(Mensaje('SpoonOpen',
      'Spoon is open (%1).' + #13#10#13#10 + 'Close Spoon, then click Retry.'), [Detalle]));
end;

// ------------------------------------------------------------ PATH del usuario

function NormalizarRuta(const S: String): String;
begin
  Result := Uppercase(RemoveBackslash(Trim(S)));
end;

// Saca de la lista de rutas (separadas por ";") la que es Carpeta, sin importar
// mayusculas ni la barra final. Quitadas dice cuantas veces estaba.
function QuitarDeLista(const Lista, Carpeta: String; var Quitadas: Integer): String;
var
  Resto, Item: String;
  P: Integer;
begin
  Result := '';
  Quitadas := 0;
  Resto := Lista;
  while Resto <> '' do begin
    P := Pos(';', Resto);
    if P > 0 then begin
      Item := Copy(Resto, 1, P - 1);
      Delete(Resto, 1, P);
    end else begin
      Item := Resto;
      Resto := '';
    end;
    if Trim(Item) <> '' then begin
      if NormalizarRuta(Item) = NormalizarRuta(Carpeta) then
        Quitadas := Quitadas + 1
      else begin
        if Result <> '' then
          Result := Result + ';';
        Result := Result + Item;
      end;
    end;
  end;
end;

procedure AvisarCambioDeEntorno;
var
  Resultado: DWORD_PTR;
begin
  EnviarMensajeConEspera(VentanaTodas, MensajeCambioConfig, 0, ClaveEntorno, NoEsperarColgadas, 5000, Resultado);
end;

procedure AgregarAlPath(const Carpeta: String);
var
  Actual: String;
  Quitadas: Integer;
begin
  if not RegQueryStringValue(HKEY_CURRENT_USER, ClaveEntorno, 'Path', Actual) then
    Actual := '';
  QuitarDeLista(Actual, Carpeta, Quitadas);
  if Quitadas > 0 then begin
    Log('PATH del usuario: ya tenia ' + Carpeta);
    Exit;
  end;
  if (Actual <> '') and (Copy(Actual, Length(Actual), 1) <> ';') then
    Actual := Actual + ';';
  if RegWriteExpandStringValue(HKEY_CURRENT_USER, ClaveEntorno, 'Path', Actual + Carpeta) then
    Log('PATH del usuario: agregado ' + Carpeta)
  else
    Log('PATH del usuario: no se pudo agregar ' + Carpeta);
end;

procedure QuitarDelPath(const Carpeta: String);
var
  Actual, Nuevo: String;
  Quitadas: Integer;
begin
  if not RegQueryStringValue(HKEY_CURRENT_USER, ClaveEntorno, 'Path', Actual) then
    Exit;
  Nuevo := QuitarDeLista(Actual, Carpeta, Quitadas);
  if Quitadas = 0 then
    Exit;
  if RegWriteExpandStringValue(HKEY_CURRENT_USER, ClaveEntorno, 'Path', Nuevo) then
    Log('PATH del usuario: quitado ' + Carpeta)
  else
    Log('PATH del usuario: no se pudo quitar ' + Carpeta);
end;

// ------------------------------------------------------------ instalaciones dentro de un PDI

// La 0.4.1 dejaba elegir una carpeta de instalacion dentro de un PDI (p. ej.
// <PDI>\plugins\Pentaho Live). Esta version se instala en otra y repara esas: saca su
// bin del PATH y borra lo que habia puesto ese instalador (que se queda sin su
// desinstalador). Lo demas no se toca.

procedure BorrarSiEsta(const Archivo: String);
begin
  if not FileExists(Archivo) then
    Exit;
  if DeleteFile(Archivo) then
    Log('  borrado ' + Archivo)
  else
    Log('  no se pudo borrar ' + Archivo);
end;

// Borra una carpeta entera que puso el instalador anterior.
procedure BorrarArbol(const Carpeta: String);
var
  Intento: Integer;
begin
  if not DirExists(Carpeta) then
    Exit;
  // DelTree con la ruta extendida (\\?\...): Setup no es longPathAware y, con la ruta normal,
  // no borra los archivos de 260 caracteres o mas (error 3), que un PDI dentro de
  // <PDI>\plugins\Pentaho Live tiene enseguida. Hasta 5 intentos por si algo esta en uso.
  for Intento := 1 to 5 do begin
    if DelTree(PathConvertNormalToSuper(Carpeta), True, True, True) or not DirExists(Carpeta) then begin
      Log('  borrada ' + Carpeta);
      Exit;
    end;
    Sleep(600);
  end;
  Log('  no se pudo borrar entera (archivos en uso?) ' + Carpeta);
end;

// La completa vieja: su PDI (con el plugin), su Java y sus licencias. Sin el desinstalador
// viejo nadie las borraria, y el Spoon del PDI de arriba recorreria ese otro PDI dentro de
// sus plugins. Solo si estan las tres senales de la completa y la carpeta no es un PDI ella
// misma (ahi un jre\ podria ser el Java del usuario).
procedure BorrarCompletaVieja(const Carpeta: String);
var
  Base: String;
begin
  Base := AddBackslash(Carpeta);
  if EsPdi(Carpeta) then
    Exit;
  if not EsPdi(Base + 'data-integration') then
    Exit;
  if not DirExists(Base + 'data-integration\plugins\pentaho-mcp') then
    Exit;
  if not FileExists(Base + 'jre\bin\javaw.exe') then
    Exit;
  Log('  es una instalacion completa: se borran su PDI y su Java');
  BorrarArbol(Base + 'data-integration');
  BorrarArbol(Base + 'jre');
  BorrarArbol(Base + 'licenses');
end;

// El plugin que puso la vieja ("solo el plugin") en el PDI de su bin\pdi-home.txt, si esta
// vez no va ahi (paso a la completa, u otro PDI): sin el desinstalador viejo quedaria para
// siempre. Solo si tiene el jar de Pentaho Live y Spoon no lo esta usando.
procedure BorrarPluginViejo(const Carpeta: String);
var
  Lineas: TArrayOfString;
  Pdi, Plugin, Trabado: String;
  Hallado: TFindRec;
begin
  if not LoadStringsFromFile(AddBackslash(Carpeta) + 'bin\pdi-home.txt', Lineas) then
    Exit;
  if GetArrayLength(Lineas) = 0 then
    Exit;
  Pdi := RemoveBackslashUnlessRoot(Trim(Lineas[0]));
  if not EsPdi(Pdi) then
    Exit;
  if WizardIsComponentSelected('plugin') and (NormalizarRuta(Pdi) = NormalizarRuta(PdiElegido)) then
    Exit;
  Plugin := AddBackslash(Pdi) + 'plugins\pentaho-mcp';
  if not FindFirst(Plugin + '\pentaho-mcp-core-*.jar', Hallado) then
    Exit;
  FindClose(Hallado);
  Trabado := JarTrabado(Pdi);
  if Trabado <> '' then begin
    Log('  queda el plugin viejo en ' + Plugin + ': ' + Trabado + ' esta en uso');
    Exit;
  end;
  Log('  el plugin viejo no va en el PDI de esta instalacion:');
  BorrarArbol(Plugin);
end;

// Deja en el log lo que quedo en una carpeta que no se pudo borrar.
// RemoveDir de una carpeta que deberia estar vacia. OneDrive marca sus carpetas de solo lectura
// (ReadOnly + ReparsePoint) y RemoveDirectory las rechaza con "acceso denegado" aunque esten vacias
// (EXT-465): se les saca el atributo y se reintenta, por si OneDrive sigue sincronizando el borrado.
function QuitarCarpetaVacia(const Carpeta: String): Boolean;
var
  Intento: Integer;
  Atributos: DWORD;
begin
  Result := not DirExists(Carpeta);
  Intento := 0;
  while (not Result) and (Intento < 5) do begin
    Intento := Intento + 1;
    Atributos := ObtenerAtributos(Carpeta);
    if (Atributos <> $FFFFFFFF) and ((Atributos and 1) <> 0) then
      PonerAtributos(Carpeta, Atributos and not 1);
    Result := RemoveDir(Carpeta) or not DirExists(Carpeta);
    if not Result then
      Sleep(400);
  end;
end;

procedure LoguearLoQueQueda(const Carpeta: String);
var
  Hallado: TFindRec;
begin
  if not DirExists(Carpeta) then
    Exit;
  if EsPdi(Carpeta) then begin
    Log('  queda ' + Carpeta + ' (es el data-integration de un PDI)');
    Exit;
  end;
  if FindFirst(AddBackslash(Carpeta) + '*', Hallado) then begin
    try
      repeat
        if (Hallado.Name <> '.') and (Hallado.Name <> '..') then
          Log('  queda (no lo puso el instalador, o esta en uso; no se toca): ' + AddBackslash(Carpeta) + Hallado.Name);
      until not FindNext(Hallado);
    finally
      FindClose(Hallado);
    end;
  end;
end;

procedure RepararCarpetaVieja(const Carpeta: String);
var
  Base, Bin: String;
begin
  Log('Instalacion anterior dentro de un PDI, se repara: ' + Carpeta);
  Base := AddBackslash(Carpeta);
  Bin := Base + 'bin';
  // Solo sale del PATH el bin de esta carpeta, nunca otro "...\Pentaho Live\bin".
  QuitarDelPath(Bin);
  // Antes de borrar bin\pdi-home.txt, que dice en que PDI quedo el plugin viejo.
  BorrarPluginViejo(Carpeta);
  BorrarCompletaVieja(Carpeta);
  BorrarSiEsta(Base + 'unins000.exe');
  BorrarSiEsta(Base + 'unins000.dat');
  BorrarSiEsta(Base + 'unins000.msg');
  BorrarSiEsta(Base + 'pentaho-live.ico');
  BorrarSiEsta(Base + 'instalar.ps1');
  // Si la carpeta vieja es el data-integration mismo, el LICENSE.txt es el de PDI.
  if not EsPdi(Carpeta) then
    BorrarSiEsta(Base + 'LICENSE.txt');
  BorrarSiEsta(Bin + '\pentaho-live.cmd');
  BorrarSiEsta(Bin + '\pentaho-live');
  BorrarSiEsta(Bin + '\pdi-home.txt');
  BorrarSiEsta(Bin + '\IDCLauncher.exe');
  BorrarSiEsta(Bin + '\idc-launcher.ini');
  BorrarSiEsta(Bin + '\idc-launcher.png');
  if DirExists(Bin) then
    if not QuitarCarpetaVacia(Bin) then
      LoguearLoQueQueda(Bin);
  if QuitarCarpetaVacia(Carpeta) then
    Log('  borrada ' + Carpeta)
  else
    LoguearLoQueQueda(Carpeta);
end;

// Repara Carpeta una sola vez (Hechas lleva las ya vistas) y nunca la de esta instalacion.
procedure RepararUna(const Carpeta: String; var Hechas: String);
var
  Clave: String;
begin
  Clave := NormalizarRuta(Carpeta);
  if (Clave = '') or (Pos('|' + Clave + '|', Hechas) > 0) then
    Exit;
  Hechas := Hechas + Clave + '|';
  if Clave = NormalizarRuta(ExpandConstant('{app}')) then begin
    Log('Instalacion anterior en la misma carpeta, no se repara: ' + Carpeta);
    Exit;
  end;
  RepararCarpetaVieja(Carpeta);
end;

// Suma Carpeta a la lista si es una instalacion de Pentaho Live (unins000.exe y bin\pentaho-live.cmd).
procedure SumarSiEsInstalacion(const Carpeta: String; var Halladas: TArrayOfString);
begin
  if FileExists(AddBackslash(Carpeta) + 'unins000.exe') and FileExists(AddBackslash(Carpeta) + 'bin\pentaho-live.cmd') then begin
    SetArrayLength(Halladas, GetArrayLength(Halladas) + 1);
    Halladas[GetArrayLength(Halladas) - 1] := Carpeta;
  end;
end;

// Las carpetas viejas: la de la instalacion anterior (AppAnterior); en "solo el plugin",
// cada subcarpeta de <PDI>\plugins\ con unins000.exe y bin\pentaho-live.cmd; y, con
// cualquier tipo, las que siguen en el PATH del usuario (por si falta la clave de
// desinstalacion, y con ella AppAnterior): un ...\bin cuya carpeta cae dentro de un PDI y
// tiene el desinstalador de esta app. Del PATH no se toma nada que este fuera de un PDI.
procedure RepararInstalacionesEnPdi;
var
  Hallado: TFindRec;
  Plugins, Rutas, Item, Carpeta: String;
  Halladas: TArrayOfString;
  Hechas: String;
  I, P: Integer;
begin
  Hechas := '|';
  if AppAnterior <> '' then
    RepararUna(AppAnterior, Hechas);
  // Primero la lista y despues las reparaciones: no borrar (ni cambiar el PATH) mientras se recorre.
  SetArrayLength(Halladas, 0);
  if WizardIsComponentSelected('plugin') then begin
    Plugins := AddBackslash(PdiElegido) + 'plugins\';
    if FindFirst(Plugins + '*', Hallado) then begin
      try
        repeat
          if ((Hallado.Attributes and FILE_ATTRIBUTE_DIRECTORY) <> 0) and (Hallado.Name <> '.') and
             (Hallado.Name <> '..') then
            SumarSiEsInstalacion(Plugins + Hallado.Name, Halladas);
        until not FindNext(Hallado);
      finally
        FindClose(Hallado);
      end;
    end;
  end;
  if not RegQueryStringValue(HKEY_CURRENT_USER, ClaveEntorno, 'Path', Rutas) then
    Rutas := '';
  while Rutas <> '' do begin
    P := Pos(';', Rutas);
    if P > 0 then begin
      Item := Copy(Rutas, 1, P - 1);
      Delete(Rutas, 1, P);
    end else begin
      Item := Rutas;
      Rutas := '';
    end;
    StringChangeEx(Item, '"', '', True);
    Item := RemoveBackslash(Trim(Item));
    if (Item <> '') and (CompareText(ExtractFileName(Item), 'bin') = 0) then begin
      Carpeta := ExtractFileDir(Item);
      if (Carpeta <> '') and DentroDePdi(Carpeta) and DesinstaladorNuestro(Carpeta) then
        SumarSiEsInstalacion(Carpeta, Halladas);
    end;
  end;
  for I := 0 to GetArrayLength(Halladas) - 1 do
    RepararUna(Halladas[I], Hechas);
end;

// ------------------------------------------------------------ agentes

// Check de las tareas "Configurar Claude Code / Codex": el agente esta en el PATH.
function AgenteEnPath(Nombre: String): Boolean;
var
  Rutas: String;
begin
  Rutas := GetEnv('PATH');
  Result := (FileSearch(Nombre + '.exe', Rutas) <> '') or (FileSearch(Nombre + '.cmd', Rutas) <> '') or
    (FileSearch(Nombre + '.bat', Rutas) <> '');
end;

// ------------------------------------------------------------ asistente

// True si vino /DIR= por linea de comandos (instalacion desatendida).
function VinoParametroDir: Boolean;
var
  I: Integer;
begin
  Result := False;
  for I := 1 to ParamCount do
    if (CompareText(Copy(ParamStr(I), 1, 5), '/DIR=') = 0) or (CompareText(Copy(ParamStr(I), 1, 5), '-DIR=') = 0) then
      Result := True;
end;

procedure InitializeWizard;
var
  Previo, Anterior: String;
begin
  PaginaPdi := CreateInputDirPage(wpSelectComponents, CustomMessage('PdiPageCaption'),
    CustomMessage('PdiPageDescription'), CustomMessage('PdiPageSubCaption'), False,
    SetupMessage(msgNewFolderName));
  PaginaPdi.Add('');
  // /PDIHOME="..." por linea de comandos (instalacion desatendida), la de la vez
  // anterior, o la variable PDI_HOME.
  Previo := ExpandConstant('{param:PDIHOME|}');
  if Previo = '' then
    Previo := GetPreviousData('PdiHome', '');
  if Previo = '' then
    Previo := GetEnv('PDI_HOME');
  PaginaPdi.Values[0] := Previo;

  // Una instalacion anterior dentro de un PDI se repara en ssPostInstall (aunque
  // ahora venga otra carpeta con /DIR=).
  Anterior := RemoveBackslashUnlessRoot(Trim(WizardForm.PrevAppDir));
  if (Anterior <> '') and DentroDePdi(Anterior) then
    AppAnterior := Anterior;
  // Al actualizar, la pagina de destino no se muestra (DisableDirPage=auto) y DirEdit trae
  // la carpeta de la vez anterior: si cae dentro de un PDI, va la de por defecto. La
  // carpeta nueva se ve en "Listo para instalar". Con /DIR= manda el parametro y, si
  // tambien cae dentro de un PDI, PrepareToInstall corta.
  if (not VinoParametroDir) and DentroDePdi(WizardForm.DirEdit.Text) then begin
    if AppAnterior = '' then
      AppAnterior := RemoveBackslashUnlessRoot(Trim(WizardForm.DirEdit.Text));
    WizardForm.DirEdit.Text := ExpandConstant('{autopf}\{#MyAppName}');
    Log('La carpeta anterior (' + AppAnterior + ') esta dentro de un PDI: se instala en ' +
      WizardForm.DirEdit.Text + ' y la vieja se repara al terminar.');
  end;
end;

procedure RegisterPreviousData(PreviousDataKey: Integer);
begin
  if WizardIsComponentSelected('plugin') then
    SetPreviousData(PreviousDataKey, 'PdiHome', PdiElegido);
end;

function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := False;
  if PageID = PaginaPdi.ID then
    Result := not WizardIsComponentSelected('plugin');
end;

function NextButtonClick(CurPageID: Integer): Boolean;
var
  Carpeta: String;
begin
  Result := True;
  // La carpeta de instalacion no puede ir dentro de un PDI (el plugin va en la
  // pagina propia, despues del tipo) ni ser la que contiene uno.
  if CurPageID = wpSelectDir then begin
    if DentroDePdi(WizardDirValue) then begin
      SuppressibleMsgBox(FmtMessage(CustomMessage('DirInPdi'), [WizardDirValue]), mbError, MB_OK, IDOK);
      Result := False;
    end else if ContienePdiAjeno(WizardDirValue) then begin
      SuppressibleMsgBox(FmtMessage(CustomMessage('DirHasPdi'), [WizardDirValue]), mbError, MB_OK, IDOK);
      Result := False;
    end;
    Exit;
  end;
  if CurPageID <> PaginaPdi.ID then
    Exit;
  Carpeta := PdiElegido;
  // Si eligieron la carpeta de arriba (la que contiene data-integration), se corrige sola.
  if (not EsPdi(Carpeta)) and (Carpeta <> '') then
    if EsPdi(AddBackslash(Carpeta) + 'data-integration') then
      Carpeta := AddBackslash(Carpeta) + 'data-integration';
  if not EsPdi(Carpeta) then begin
    SuppressibleMsgBox(FmtMessage(CustomMessage('PdiNotFound'), [Carpeta]), mbError, MB_OK, IDOK);
    Result := False;
    Exit;
  end;
  if not EsPdi94(Carpeta) then
    Result := SuppressibleMsgBox(CustomMessage('PdiNot94'), mbConfirmation, MB_YESNO, IDYES) = IDYES;
  if Result then
    PaginaPdi.Values[0] := Carpeta;
end;

function UpdateReadyMemo(Space, NewLine, MemoUserInfoInfo, MemoDirInfo, MemoTypeInfo,
  MemoComponentsInfo, MemoGroupInfo, MemoTasksInfo: String): String;
begin
  Result := '';
  if MemoDirInfo <> '' then
    Result := Result + MemoDirInfo + NewLine + NewLine;
  if MemoTypeInfo <> '' then
    Result := Result + MemoTypeInfo + NewLine + NewLine;
  if MemoComponentsInfo <> '' then
    Result := Result + MemoComponentsInfo + NewLine + NewLine;
  if WizardIsComponentSelected('plugin') then
    Result := Result + CustomMessage('ReadyPdi') + NewLine + Space + PdiElegido + NewLine + NewLine;
  if MemoGroupInfo <> '' then
    Result := Result + MemoGroupInfo + NewLine + NewLine;
  if MemoTasksInfo <> '' then
    Result := Result + MemoTasksInfo + NewLine + NewLine;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  Result := '';
  // Por si la pagina de destino no se mostro (/DIR= en una instalacion desatendida).
  if DentroDePdi(WizardDirValue) then begin
    Result := FmtMessage(CustomMessage('DirInPdi'), [WizardDirValue]);
    Exit;
  end;
  if ContienePdiAjeno(WizardDirValue) then begin
    Result := FmtMessage(CustomMessage('DirHasPdi'), [WizardDirValue]);
    Exit;
  end;
  // Por si la pagina no se mostro (instalacion desatendida con /TYPE=pluginonly).
  if WizardIsComponentSelected('plugin') and not EsPdi(PdiElegido) then begin
    Result := FmtMessage(CustomMessage('PdiNotFound'), [PdiElegido]);
    Exit;
  end;
  if not EsperarQueCierrenSpoon(PdiDeEstaInstalacion) then begin
    Result := CustomMessage('SpoonOpenAbort');
    Exit;
  end;
  // Una completa anterior dentro de un PDI se borra al terminar: su Spoon tampoco puede
  // estar abierto.
  if (AppAnterior <> '') and EsPdi(AddBackslash(AppAnterior) + 'data-integration') then
    if not EsperarQueCierrenSpoon(AddBackslash(AppAnterior) + 'data-integration') then
      Result := CustomMessage('SpoonOpenAbort');
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  Lineas: TArrayOfString;
begin
  if CurStep <> ssPostInstall then
    Exit;
  // El lanzador lee de aca que PDI abrir (UTF-8: la ruta puede tener acentos).
  if WizardIsComponentSelected('plugin') then begin
    SetArrayLength(Lineas, 1);
    Lineas[0] := PdiElegido;
    if not SaveStringsToUTF8FileWithoutBOM(ExpandConstant('{app}\bin\pdi-home.txt'), Lineas, False) then
      Log('No se pudo escribir bin\pdi-home.txt');
  end;
  // Antes de agregar el bin nuevo al PATH: saca los de las carpetas viejas.
  RepararInstalacionesEnPdi;
  // pentaho-live en el PATH del usuario, para que un agente pueda abrir Spoon.
  AgregarAlPath(ExpandConstant('{app}\bin'));
  AvisarCambioDeEntorno;
end;

// ------------------------------------------------------------ desinstalador

function InitializeUninstall: Boolean;
begin
  Result := EsperarQueCierrenSpoon(PdiInstalado);
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usPostUninstall then begin
    QuitarDelPath(ExpandConstant('{app}\bin'));
    AvisarCambioDeEntorno;
  end;
end;
