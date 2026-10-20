; ====================================================================
;  instalador.iss - Script de Inno Setup 7 para "Pentaho Live" (IDC Studios):
;  Pentaho Data Integration 9.4 CE + el plugin de Spoon con el servidor MCP
;  adentro + Java 21 (Eclipse Temurin), o solo el plugin en un PDI que ya
;  este instalado.
;
;  Como usar:
;    1) powershell -ExecutionPolicy Bypass -File instalador\preparar.ps1 -PdiHome "<...>\data-integration"
;       -> deja todo en instalador\staging\ (PDI limpio, plugin, JRE, lanzador, imagenes)
;    2) Abrir este archivo con Inno Setup 7 -> Build > Compile (F9)
;    3) El instalador queda en:
;       instalador\Instalador_PentahoLive_<version>.exe
;  Lo compila Ivan (regla del portafolio): este script no se compila con ISCC
;  desde un agente.
;
;  Va en instalador\ y no en la raiz (la convencion 04 lo pone en la raiz de la
;  app): la raiz de este repo es el marketplace publico de Claude Code. Por eso
;  las rutas son relativas a instalador\ y la version se lee de ..\VERSION.
; ====================================================================

#define MyAppName "Pentaho Live"
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

[Setup]
AppId={{54C78290-359B-45E0-BB3A-2D69FB54CAE6}
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
DefaultDirName={autopf}\Pentaho Live
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
SetupIconFile=staging\pentaho-live.ico
UninstallDisplayIcon={app}\pentaho-live.ico
OutputDir=.
OutputBaseFilename=Instalador_PentahoLive_{#MyAppVersion}
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

[CustomMessages]
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

[Files]
; Primero lo chico: con SolidCompression, para llegar a un archivo hay que
; descomprimir todo lo anterior, y "solo el plugin" no tiene que pasar por PDI.
Source: "staging\plugin\pentaho-mcp\*"; DestDir: "{code:CarpetaPluginExterna}"; Components: plugin; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "staging\bin\pentaho-live.cmd"; DestDir: "{app}\bin"; Flags: ignoreversion
Source: "staging\bin\pentaho-live"; DestDir: "{app}\bin"; Flags: ignoreversion
Source: "staging\pentaho-live.ico"; DestDir: "{app}"; Flags: ignoreversion
Source: "staging\instalar.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "staging\LICENSE.txt"; DestDir: "{app}"; Flags: ignoreversion
; La completa: PDI 9.4 CE (con su LICENSE.txt, y el plugin ya en plugins\pentaho-mcp) y el JRE.
Source: "staging\licenses\*"; DestDir: "{app}\licenses"; Components: bundle; Flags: ignoreversion
Source: "staging\jre\*"; DestDir: "{app}\jre"; Components: bundle; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "staging\data-integration\*"; DestDir: "{app}\data-integration"; Components: bundle; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
; El lanzador minimizado: la consola solo dura lo que tarda en arrancar Spoon.
Name: "{group}\{#MyAppName}"; Filename: "{app}\bin\pentaho-live.cmd"; Parameters: "--gui"; WorkingDir: "{app}\bin"; IconFilename: "{app}\pentaho-live.ico"; Comment: "{cm:ShortcutComment}"; Flags: runminimized
Name: "{group}\{cm:DocsShortcut}"; Filename: "{#MyDocsURL}"
Name: "{group}\{cm:UninstallProgram,{#MyAppName}}"; Filename: "{uninstallexe}"; IconFilename: "{app}\pentaho-live.ico"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\bin\pentaho-live.cmd"; Parameters: "--gui"; WorkingDir: "{app}\bin"; IconFilename: "{app}\pentaho-live.ico"; Comment: "{cm:ShortcutComment}"; Tasks: desktopicon; Flags: runminimized

[Run]
; Configurar los agentes: solo el paso 4 de instalar.ps1 (sin PDI ni plugin).
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\instalar.ps1"" -SoloAgentes -SinCodex"; StatusMsg: "{cm:ConfiguringClaude}"; Tasks: claude; Flags: runhidden logoutput
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\instalar.ps1"" -SoloAgentes -SinClaude"; StatusMsg: "{cm:ConfiguringCodex}"; Tasks: codex; Flags: runhidden logoutput
Filename: "{app}\bin\pentaho-live.cmd"; Parameters: "--gui"; WorkingDir: "{app}\bin"; Description: "{cm:LaunchNow}"; Flags: nowait postinstall skipifsilent runminimized

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

// API de Windows (user32, del sistema: ninguna DLL de terceros).
function EnviarMensajeConEspera(Ventana: HWND; Codigo: UINT; WP: WPARAM; LP: String;
  Banderas, Espera: UINT; var Resultado: DWORD_PTR): LRESULT;
  external 'SendMessageTimeoutW@user32.dll stdcall';

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

procedure InitializeWizard;
var
  Previo: String;
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
  // Por si la pagina no se mostro (instalacion desatendida con /TYPE=pluginonly).
  if WizardIsComponentSelected('plugin') and not EsPdi(PdiElegido) then begin
    Result := FmtMessage(CustomMessage('PdiNotFound'), [PdiElegido]);
    Exit;
  end;
  if not EsperarQueCierrenSpoon(PdiDeEstaInstalacion) then
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
