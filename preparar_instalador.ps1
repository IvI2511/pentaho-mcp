<#
  Deja en build\instalador\staging\ todo lo que empaqueta instalador.iss (el paso previo a compilar
  con Inno Setup 7; este script y el .iss van en la raiz del repo, como en las otras apps).
  No compila el instalador: eso es abrir instalador.iss y apretar F9.

  Que arma:
    staging\data-integration\   copia limpia de PDI 9.4 (sin logs\, sin .ruff_cache, sin el
                                conector de MySQL, que no se puede redistribuir) con el plugin
                                recien compilado en plugins\pentaho-mcp\
    staging\plugin\pentaho-mcp\ el plugin solo, para la instalacion "solo el plugin"
    staging\jre\                Eclipse Temurin 21 (JRE, Windows x64), bajado de la API de Adoptium
                                y verificado con el SHA-256 que da la misma API
    staging\licenses\           la licencia y el NOTICE de Temurin
    staging\bin\                el comando pentaho-live.cmd (+ el de Git Bash) y el lanzador de los
                                accesos: IDCLauncher.exe, idc-launcher.ini e idc-launcher.png (su
                                splash), de launcher\ (el .exe va compilado: su codigo es
                                privado; -FuenteLanzador lo recompila desde la plantilla)
    staging\asistente\          las imagenes del asistente (BMP), de tools\instalador\generar_imagenes.py
    staging\pentaho-live.ico    el icono (tools\instalador\generar_imagenes.py tambien regenera launcher\pentaho-live.ico)
    staging\LICENSE.txt, staging\instalar.ps1   del repo

  El zip del JRE queda en build\instalador\cache\ (con la respuesta de la API), asi no se baja cada vez.
  build\ no se versiona; en instalador\ queda solo el Instalador_PentahoLive_X.Y.Z.exe que compila Inno Setup.

  Uso:
    powershell -ExecutionPolicy Bypass -File preparar_instalador.ps1 -PdiHome "<...>\data-integration"
    (con -SinCompilar usa el plugin de spoon-plugin\dist\pentaho-mcp sin volver a correr build.ps1)

  Requiere: un JDK (para compilar el plugin, ver spoon-plugin\build.ps1), Python con
  playwright y pillow (pip install playwright pillow) y Google Chrome, con -FuenteLanzador el csc.exe de .NET
  Framework 4 (viene con Windows) y red la primera vez.
#>
param(
  [string]$PdiHome = $env:PDI_HOME,
  # Version fija de Temurin 21. Para cambiarla: ver los nombres en
  # https://api.adoptium.net/v3/info/release_names?version=[21,22)&release_type=ga&vendor=eclipse
  [string]$JreRelease = "jdk-21.0.12.1+1",
  # La carpeta plantillas del portafolio de IDC Studios (privada): con ella se recompilan
  # launcher\IDCLauncher.exe e idc-launcher.png. Sin ella se usan los del repo.
  [string]$FuenteLanzador = $env:IDC_PLANTILLAS,
  # No corre spoon-plugin\build.ps1: usa el plugin que ya dejo en spoon-plugin\dist\pentaho-mcp
  # (tiene que ser el de VERSION). Para cuando el build se acaba de hacer o no se puede repetir.
  [switch]$SinCompilar
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # Invoke-WebRequest es lentisimo con la barra de progreso
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$repo    = $PSScriptRoot
$staging = Join-Path $repo "build\instalador\staging"
$cache   = Join-Path $repo "build\instalador\cache"
# launcher\: lo que va a {app}\bin (IDCLauncher, su .ini y su splash, los comandos pentaho-live) y el icono
$dirLanzador = Join-Path $repo "launcher"
$ico     = Join-Path $dirLanzador "pentaho-live.ico"
$generador = Join-Path $repo "tools\instalador\generar_imagenes.py"

function Paso($texto) { Write-Host ""; Write-Host "== $texto" }
function Falla($texto) { throw "preparar_instalador.ps1: $texto" }

# ------------------------------------------------------------------ version y PDI
$Version = (Get-Content -Raw (Join-Path $repo "VERSION")).Trim()
if ($Version -notmatch '^\d+\.\d+\.\d+$') { Falla "VERSION no tiene la forma X.Y.Z: '$Version'" }
if (-not $PdiHome) { Falla "Pasa -PdiHome <ruta a data-integration> o defini PDI_HOME." }
$PdiHome = (Resolve-Path $PdiHome).Path.TrimEnd('\')
if (-not (Test-Path (Join-Path $PdiHome "Spoon.bat"))) { Falla "$PdiHome no tiene Spoon.bat." }
Write-Host "Pentaho Live $Version"
Write-Host "PDI de origen: $PdiHome"

# ------------------------------------------------------------------ 1. plugin
Paso "1. Plugin de Spoon (spoon-plugin\build.ps1)"
if ($SinCompilar) {
  Write-Host "   -SinCompilar: se usa el de spoon-plugin\dist\pentaho-mcp tal como esta"
} else {
  & (Join-Path $repo "spoon-plugin\build.ps1") -PdiHome $PdiHome
}
$distPlugin = Join-Path $repo "spoon-plugin\dist\pentaho-mcp"
if (-not (Test-Path (Join-Path $distPlugin "pentaho-mcp-core-$Version.jar"))) {
  Falla "no esta pentaho-mcp-core-$Version.jar en $distPlugin (correr spoon-plugin\build.ps1)"
}
# Uno solo: un jar de otra version al lado cargaria dos veces las mismas clases en Spoon.
$jars = @(Get-ChildItem $distPlugin -Filter "pentaho-mcp-core-*.jar")
if ($jars.Count -ne 1) { Falla "en $distPlugin hay $($jars.Count) pentaho-mcp-core-*.jar (tiene que haber uno)" }

# ------------------------------------------------------------------ 2. staging limpio + PDI
Paso "2. Copia limpia de PDI"
if (Test-Path $staging) { Remove-Item -Recurse -Force $staging }
New-Item -ItemType Directory -Force $staging | Out-Null
$destPdi = Join-Path $staging "data-integration"
# /XD con nombre excluye esa carpeta en cualquier nivel; plugins\pentaho-mcp va con ruta completa
# (se pone despues el recien compilado). /XF: el conector de MySQL no se puede redistribuir.
$rc = & robocopy.exe $PdiHome $destPdi /E /R:1 /W:1 /NFL /NDL /NJH /NJS /NP `
  /XD logs .ruff_cache __pycache__ .git .claude (Join-Path $PdiHome "plugins\pentaho-mcp") `
  /XF "mysql-connector*.jar" "*.log" "hs_err_pid*" "Thumbs.db" ".DS_Store"
if ($LASTEXITCODE -ge 8) { Falla "robocopy fallo copiando PDI (codigo $LASTEXITCODE)" }
$global:LASTEXITCODE = 0
Write-Host "   $destPdi"

Paso "3. Plugin en la copia y suelto"
$pluginEnPdi = Join-Path $destPdi "plugins\pentaho-mcp"
$pluginSuelto = Join-Path $staging "plugin\pentaho-mcp"
foreach ($d in @($pluginEnPdi, $pluginSuelto)) {
  New-Item -ItemType Directory -Force (Split-Path $d) | Out-Null
  Copy-Item -Recurse $distPlugin $d
  Write-Host "   $d"
}

# ------------------------------------------------------------------ 4. JRE Temurin 21
Paso "4. JRE Eclipse Temurin 21 ($JreRelease)"
New-Item -ItemType Directory -Force $cache | Out-Null
$apiCache = Join-Path $cache ($JreRelease.Replace('+', '_') + ".json")
if (-not (Test-Path $apiCache)) {
  $api = "https://api.adoptium.net/v3/assets/release_name/eclipse/" + [Uri]::EscapeDataString($JreRelease) +
         "?architecture=x64&heap_size=normal&image_type=jre&jvm_impl=hotspot&os=windows&project=jdk"
  Write-Host "   API: $api"
  Invoke-WebRequest -UseBasicParsing -Uri $api -OutFile $apiCache
}
$release = Get-Content -Raw $apiCache | ConvertFrom-Json
$bin = @($release.binaries | Where-Object {
  $_.os -eq "windows" -and $_.architecture -eq "x64" -and $_.image_type -eq "jre" -and
  $_.jvm_impl -eq "hotspot" -and $_.heap_size -eq "normal" })
if ($bin.Count -ne 1) { Remove-Item $apiCache; Falla "La API no devolvio un unico JRE x64 de Windows para $JreRelease." }
$paquete = $bin[0].package
$sha = $paquete.checksum.ToLowerInvariant()
if ($sha -notmatch '^[0-9a-f]{64}$') { Falla "La API no trajo un SHA-256 valido para $($paquete.name)." }
if ($paquete.link -notmatch '^https://github\.com/adoptium/') { Falla "Enlace inesperado para el JRE: $($paquete.link)" }
$zipJre = Join-Path $cache $paquete.name
function Sha256($ruta) { (Get-FileHash -Algorithm SHA256 $ruta).Hash.ToLowerInvariant() }
if ((Test-Path $zipJre) -and ((Sha256 $zipJre) -eq $sha)) {
  Write-Host "   en cache: $($paquete.name)"
} else {
  Write-Host "   bajando $($paquete.link) ($([math]::Round($paquete.size / 1MB)) MB)"
  $parcial = "$zipJre.part"
  Invoke-WebRequest -UseBasicParsing -Uri $paquete.link -OutFile $parcial
  $real = Sha256 $parcial
  if ($real -ne $sha) {
    Remove-Item -Force $parcial
    Falla "SHA-256 del JRE no coincide: esperado $sha, bajado $real"
  }
  Move-Item -Force $parcial $zipJre
}
Write-Host "   SHA-256 verificado: $sha"
$tmpJre = Join-Path $cache "extraer-jre"
if (Test-Path $tmpJre) { Remove-Item -Recurse -Force $tmpJre }
Expand-Archive -Path $zipJre -DestinationPath $tmpJre
$raiz = @(Get-ChildItem $tmpJre -Directory)
if ($raiz.Count -ne 1) { Falla "El zip del JRE no tiene una sola carpeta raiz." }
$destJre = Join-Path $staging "jre"
Move-Item $raiz[0].FullName $destJre
Remove-Item -Recurse -Force $tmpJre
$javaVersion = (cmd /c "`"$destJre\bin\java.exe`" -version 2>&1") -join "`n"
if ($LASTEXITCODE -ne 0) { Falla "staging\jre\bin\java.exe -version fallo:`n$javaVersion" }
Write-Host ($javaVersion -split "`n" | ForEach-Object { "   $_" } | Out-String).TrimEnd()

$licencias = Join-Path $staging "licenses"
New-Item -ItemType Directory -Force $licencias | Out-Null
$licJava = Join-Path $destJre "legal\java.base\LICENSE"
if (-not (Test-Path $licJava)) { Falla "El JRE no trae legal\java.base\LICENSE." }
Copy-Item $licJava (Join-Path $licencias "Temurin-LICENSE.txt")
if (Test-Path (Join-Path $destJre "NOTICE")) { Copy-Item (Join-Path $destJre "NOTICE") (Join-Path $licencias "Temurin-NOTICE.txt") }
@(
  "Eclipse Temurin $($release.release_name) (JRE, Windows x64), by the Eclipse Foundation / Adoptium.",
  "Included unchanged in the jre folder. Source and license: https://adoptium.net",
  "License: GPLv2 with the Classpath Exception (Temurin-LICENSE.txt); third-party notices are in jre\legal\."
) | Set-Content -Encoding ASCII (Join-Path $licencias "Temurin-README.txt")

# ------------------------------------------------------------------ 5. lanzador y archivos del repo
Paso "5. Lanzador y archivos del repo"
$destBin = Join-Path $staging "bin"
New-Item -ItemType Directory -Force $destBin | Out-Null
# El .cmd siempre con CRLF (cmd se confunde con los saltos de etiqueta si viene con LF solo);
# el script de Git Bash siempre con LF.
$cmdTexto = [IO.File]::ReadAllText((Join-Path $dirLanzador "pentaho-live.cmd")) -replace "`r`n", "`n" -replace "`n", "`r`n"
[IO.File]::WriteAllText((Join-Path $destBin "pentaho-live.cmd"), $cmdTexto, [Text.Encoding]::ASCII)
$shTexto = [IO.File]::ReadAllText((Join-Path $dirLanzador "pentaho-live")) -replace "`r`n", "`n"
[IO.File]::WriteAllText((Join-Path $destBin "pentaho-live"), $shTexto, [Text.Encoding]::ASCII)
Copy-Item (Join-Path $repo "LICENSE") (Join-Path $staging "LICENSE.txt")
Copy-Item (Join-Path $repo "instalar.ps1") (Join-Path $staging "instalar.ps1")
Write-Host "   bin\pentaho-live.cmd, bin\pentaho-live, LICENSE.txt, instalar.ps1"

# ------------------------------------------------------------------ 6. imagenes
Paso "6. Icono e imagenes del asistente (generar_imagenes.py)"
$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) { $python = Get-Command py -ErrorAction SilentlyContinue }
if (-not $python) { Falla "No hay Python en el PATH (hace falta con: pip install playwright pillow)." }
& $python.Source $generador --salida (Join-Path $staging "asistente") --version $Version
if ($LASTEXITCODE -ne 0) { Falla "generar_imagenes.py fallo (codigo $LASTEXITCODE). Hace falta: pip install playwright pillow, y Google Chrome." }
Copy-Item $ico (Join-Path $staging "pentaho-live.ico")

# ------------------------------------------------------------------ 7. lanzador de los accesos
# IDCLauncher (convencion 03 del portafolio): el splash de 760x400 que se ve enseguida al abrir
# el acceso, mientras pentaho-live.cmd --launcher arranca Spoon. Va despues de las imagenes
# porque lleva el .ico recien generado. No se usa build_launcher.bat: espera la salida de
# PyInstaller, copia ..\VERSION y es la copia canonica que se compara entre las apps.
Paso "7. Lanzador IDCLauncher.exe y su splash"
# Su codigo es privado (la plantilla del portafolio de IDC Studios, no MIT; ver
# launcher\NOTICE.md): en el repo van el .exe ya compilado y su splash. Con
# -FuenteLanzador <carpeta plantillas del portafolio> se recompilan los dos y se actualizan en
# launcher\. Hay que hacerlo en cada version: los dos llevan el numero.
$exeRepo = Join-Path $dirLanzador "IDCLauncher.exe"
$pngRepo = Join-Path $dirLanzador "idc-launcher.png"
$recompilado = $false
if ($FuenteLanzador) {
  $fuenteCs = Join-Path $FuenteLanzador "launcher\IDCLauncher.cs"
  $fuenteSplash = Join-Path $FuenteLanzador "tools\generar_splash.py"
  foreach ($f in @($fuenteCs, $fuenteSplash)) {
    if (-not (Test-Path $f)) { Falla "falta $f (-FuenteLanzador es la carpeta plantillas del portafolio)" }
  }
  $csc = Join-Path $env:WINDIR "Microsoft.NET\Framework64\v4.0.30319\csc.exe"
  if (-not (Test-Path $csc)) { $csc = Join-Path $env:WINDIR "Microsoft.NET\Framework\v4.0.30319\csc.exe" }
  if (-not (Test-Path $csc)) { Falla "Windows no tiene el compilador de .NET Framework 4 (csc.exe)." }
  # La version del .exe (la que muestran sus propiedades), en un .cs generado: fuente unica, VERSION.
  New-Item -ItemType Directory -Force $cache | Out-Null
  $asmInfo = Join-Path $cache "AssemblyInfo.cs"
  @(
    'using System.Reflection;',
    '[assembly: AssemblyTitle("Pentaho Live")]',
    '[assembly: AssemblyProduct("Pentaho Live")]',
    '[assembly: AssemblyCompany("IDC Studios")]',
    '[assembly: AssemblyDescription("Pentaho Live launcher")]',
    '[assembly: AssemblyCopyright("(c) Ivan Castano - IDC Studios. Not covered by the MIT license.")]',
    "[assembly: AssemblyVersion(""$Version.0"")]",
    "[assembly: AssemblyFileVersion(""$Version.0"")]",
    "[assembly: AssemblyInformationalVersion(""$Version"")]"
  ) | Set-Content -Encoding ASCII $asmInfo
  # csc escribe sus errores en la salida normal: se captura esa y nada de 2>&1 (con Stop, en
  # PowerShell 5.1 una linea de stderr redirigida corta el script antes de ver el codigo).
  $salidaCsc = & $csc /nologo /target:winexe /optimize+ /platform:anycpu `
    /reference:System.dll /reference:System.Drawing.dll /reference:System.Windows.Forms.dll `
    "/win32icon:$ico" "/out:$exeRepo" $fuenteCs $asmInfo
  if ($LASTEXITCODE -ne 0) { Falla ("csc no compilo IDCLauncher.cs (codigo $LASTEXITCODE):`n" + ($salidaCsc -join "`n")) }
  # El splash, en ingles como el banner del asistente. El punto medio y los puntos suspensivos van
  # con [char]: este .ps1 es ASCII (PowerShell 5.1 lo leeria como ANSI).
  $punto = [char]0x00B7
  $suspensivos = [char]0x2026
  & $python.Source $fuenteSplash --titulo "Pentaho Live" `
    --subtitulo "Claude builds your ETLs live, in Spoon." --version $Version `
    --icono $ico --salida $pngRepo `
    --rotulo "Pentaho Data Integration 9.4" --estado "OPENING SPOON" `
    --espera "This takes about 30-60 seconds$suspensivos" --ficha "Windows  $punto  Spoon 9.4  $punto  MCP"
  if ($LASTEXITCODE -ne 0) { Falla "generar_splash.py fallo (codigo $LASTEXITCODE). Hace falta: pip install pillow." }
  $recompilado = $true
  Write-Host "   recompilados desde $FuenteLanzador : launcher\IDCLauncher.exe e idc-launcher.png"
}
foreach ($f in @("IDCLauncher.exe", "idc-launcher.png", "idc-launcher.ini")) {
  if (-not (Test-Path (Join-Path $dirLanzador $f))) { Falla "falta launcher\$f" }
  Copy-Item (Join-Path $dirLanzador $f) (Join-Path $destBin $f)
  Write-Host "   bin\$f"
}
$lanzador = Join-Path $destBin "IDCLauncher.exe"
$splash = Join-Path $destBin "idc-launcher.png"
# El lanzador valida su propio .ini. Es una app de ventanas: con & no se espera a que termine.
$autoPrueba = Start-Process -FilePath $lanzador -Wait -PassThru `
  -ArgumentList "--self-test", ('"' + (Join-Path $destBin "idc-launcher.ini") + '"')
if ($autoPrueba.ExitCode -ne 0) { Falla "IDCLauncher.exe --self-test rechazo bin\idc-launcher.ini (codigo $($autoPrueba.ExitCode))." }
Write-Host "   --self-test: idc-launcher.ini valido"

# ------------------------------------------------------------------ 8. controles
Paso "8. Controles"
$problemas = @()

# El lanzador: los tres archivos, el splash de 760x400 y la version en el .exe.
foreach ($f in @("pentaho-live.cmd", "pentaho-live", "IDCLauncher.exe", "idc-launcher.ini", "idc-launcher.png")) {
  if (-not (Test-Path (Join-Path $destBin $f))) { $problemas += "falta bin\$f" }
}
function TamanoPng($ruta) {
  $b = [IO.File]::ReadAllBytes($ruta)
  if ($b.Length -lt 24 -or $b[1] -ne 0x50 -or $b[2] -ne 0x4E -or $b[3] -ne 0x47) { return "(no es un PNG)" }
  $ancho = ([int]$b[16] -shl 24) -bor ([int]$b[17] -shl 16) -bor ([int]$b[18] -shl 8) -bor [int]$b[19]
  $alto = ([int]$b[20] -shl 24) -bor ([int]$b[21] -shl 16) -bor ([int]$b[22] -shl 8) -bor [int]$b[23]
  "{0}x{1}" -f $ancho, $alto
}
if (Test-Path $splash) {
  $t = TamanoPng $splash
  Write-Host "   bin\idc-launcher.png : $t"
  if ($t -ne "760x400") { $problemas += "bin\idc-launcher.png mide $t (se esperaba 760x400)" }
}
if (Test-Path $lanzador) {
  $versionExe = (Get-Item $lanzador).VersionInfo.FileVersion
  Write-Host "   bin\IDCLauncher.exe : version $versionExe"
  if ($versionExe -ne "$Version.0") {
    $texto = "IDCLauncher.exe dice version $versionExe (se esperaba $Version.0): recompilarlo con -FuenteLanzador"
    # Sin la fuente privada no se puede: para quien arma desde el repo publico es solo un aviso.
    if ($recompilado) { $problemas += $texto } else { Write-Host "   AVISO: $texto" }
  }
}
# El splash de PDI oculto (laf-pentaho-live.properties + el PNG de 1x1) en las dos copias del plugin.
foreach ($d in @($pluginEnPdi, $pluginSuelto)) {
  foreach ($f in @("ui\laf-pentaho-live.properties", "ui\splash-vacio.png")) {
    if (-not (Test-Path (Join-Path $d $f))) { $problemas += "falta $f en " + $d.Substring($staging.Length + 1) }
  }
}

$mysql = @(Get-ChildItem -Recurse -File $staging -Filter "mysql-connector*.jar")
if ($mysql.Count) { $problemas += "conector de MySQL en staging: " + ($mysql.FullName -join ", ") }
$sobras = @(Get-ChildItem -Recurse -Directory -Force $staging | Where-Object { $_.Name -in @("logs", ".ruff_cache", "__pycache__") })
if ($sobras.Count) { $problemas += "carpetas que no van: " + ($sobras.FullName -join ", ") }
if (@(Get-ChildItem $pluginEnPdi -Filter "pentaho-mcp-core-*.jar").Count -ne 1) { $problemas += "plugins\pentaho-mcp no tiene un solo jar" }

# Rutas de esta maquina en los archivos de texto (el instalador es publico).
# OneDrive solo como parte de una ruta (instalar.ps1 nombra $env:OneDrive, y eso no es una ruta).
$patrones = @([regex]::Escape($env:USERPROFILE), [regex]::Escape($repo), [regex]::Escape($PdiHome),
              '[A-Za-z]:\\[^\r\n"''<>|]*OneDrive')
if ($env:USERNAME) { $patrones += "\b" + [regex]::Escape($env:USERNAME) + "\b" }
$regex = [regex]::new(($patrones -join "|"), "IgnoreCase")
$extTexto = @(".txt", ".xml", ".properties", ".bat", ".cmd", ".sh", ".ps1", ".json", ".md", ".html",
              ".htm", ".js", ".css", ".ini", ".cfg", ".conf", ".policy", ".mf", ".ktr", ".kjb", ".csv", "")
$hallazgos = Get-ChildItem -Recurse -File $staging | Where-Object {
  $extTexto -contains $_.Extension.ToLowerInvariant() -and $_.Length -lt 4MB
} | ForEach-Object {
  $texto = [IO.File]::ReadAllText($_.FullName)
  if ($regex.IsMatch($texto)) { $_.FullName.Substring($staging.Length + 1) }
}
if ($hallazgos) { $problemas += "rutas de esta maquina en: " + (@($hallazgos) -join ", ") }

function TamanosIco($ruta) {
  $b = [IO.File]::ReadAllBytes($ruta)
  $n = [BitConverter]::ToUInt16($b, 4)
  0..($n - 1) | ForEach-Object { $t = $b[6 + 16 * $_]; if ($t -eq 0) { 256 } else { [int]$t } } | Sort-Object
}
$icoTamanos = @(TamanosIco (Join-Path $staging "pentaho-live.ico"))
$faltan = @(16, 24, 32, 48, 64, 128, 256 | Where-Object { $icoTamanos -notcontains $_ })
if ($faltan.Count) { $problemas += "al .ico le faltan tamanos: $($faltan -join ', ')" }
Write-Host "   .ico: $($icoTamanos -join ', ') px"

function TamanoBmp($ruta) {
  $b = [IO.File]::ReadAllBytes($ruta)
  "{0}x{1}" -f [BitConverter]::ToInt32($b, 18), [Math]::Abs([BitConverter]::ToInt32($b, 22))
}
$esperados = @{ "banner.bmp" = "164x314"; "banner@2x.bmp" = "328x628"; "logo.bmp" = "55x58"; "logo@2x.bmp" = "110x116" }
foreach ($k in $esperados.Keys) {
  $r = Join-Path $staging "asistente\$k"
  if (-not (Test-Path $r)) { $problemas += "falta asistente\$k"; continue }
  $t = TamanoBmp $r
  Write-Host "   asistente\$k : $t"
  if ($t -ne $esperados[$k]) { $problemas += "asistente\$k mide $t (se esperaba $($esperados[$k]))" }
}

if ($problemas.Count) {
  Write-Host ""
  $problemas | ForEach-Object { Write-Host "   PROBLEMA: $_" }
  Falla "staging no quedo bien (ver arriba)."
}

$mb = { param($d) "{0:N0} MB" -f ((Get-ChildItem -Recurse -File $d | Measure-Object Length -Sum).Sum / 1MB) }
Write-Host ""
Write-Host "Listo: build\instalador\staging (version $Version)"
Write-Host ("   data-integration " + (& $mb $destPdi) + ", jre " + (& $mb $destJre) + ", total " + (& $mb $staging))
Write-Host "Siguiente: abrir instalador.iss (raiz del repo) con Inno Setup 7 y compilar (F9)."
Write-Host "Sale: instalador\Instalador_PentahoLive_$Version.exe"
