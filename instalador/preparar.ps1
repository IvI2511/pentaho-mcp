<#
  Deja en instalador\staging\ todo lo que empaqueta instalador.iss (el paso previo a compilar
  con Inno Setup 7). No compila el instalador: eso es abrir instalador.iss y apretar F9.

  Que arma:
    staging\data-integration\   copia limpia de PDI 9.4 (sin logs\, sin .ruff_cache, sin el
                                conector de MySQL, que no se puede redistribuir) con el plugin
                                recien compilado en plugins\pentaho-mcp\
    staging\plugin\pentaho-mcp\ el plugin solo, para la instalacion "solo el plugin"
    staging\jre\                Eclipse Temurin 21 (JRE, Windows x64), bajado de la API de Adoptium
                                y verificado con el SHA-256 que da la misma API
    staging\licenses\           la licencia y el NOTICE de Temurin
    staging\bin\                el lanzador pentaho-live.cmd (+ el de Git Bash)
    staging\asistente\          las imagenes del asistente (BMP), de generar_imagenes.py
    staging\pentaho-live.ico    el icono (generar_imagenes.py tambien regenera instalador\pentaho-live.ico)
    staging\LICENSE.txt, staging\instalar.ps1   del repo

  El zip del JRE queda en instalador\cache\ (con la respuesta de la API), asi no se baja cada vez.

  Uso:
    powershell -ExecutionPolicy Bypass -File instalador\preparar.ps1 -PdiHome "<...>\data-integration"

  Requiere: un JDK (para compilar el plugin, ver spoon-plugin\build.ps1), Python con
  playwright y pillow (pip install playwright pillow) y Google Chrome, y red la primera vez.
#>
param(
  [string]$PdiHome = $env:PDI_HOME,
  # Version fija de Temurin 21. Para cambiarla: ver los nombres en
  # https://api.adoptium.net/v3/info/release_names?version=[21,22)&release_type=ga&vendor=eclipse
  [string]$JreRelease = "jdk-21.0.12.1+1"
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # Invoke-WebRequest es lentisimo con la barra de progreso
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$aqui    = $PSScriptRoot
$repo    = Split-Path $aqui -Parent
$staging = Join-Path $aqui "staging"
$cache   = Join-Path $aqui "cache"

function Paso($texto) { Write-Host ""; Write-Host "== $texto" }
function Falla($texto) { throw "preparar.ps1: $texto" }

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
& (Join-Path $repo "spoon-plugin\build.ps1") -PdiHome $PdiHome
$distPlugin = Join-Path $repo "spoon-plugin\dist\pentaho-mcp"
if (-not (Test-Path (Join-Path $distPlugin "pentaho-mcp-core-$Version.jar"))) {
  Falla "build.ps1 no dejo pentaho-mcp-core-$Version.jar en $distPlugin"
}

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
$cmdTexto = [IO.File]::ReadAllText((Join-Path $aqui "bin\pentaho-live.cmd")) -replace "`r`n", "`n" -replace "`n", "`r`n"
[IO.File]::WriteAllText((Join-Path $destBin "pentaho-live.cmd"), $cmdTexto, [Text.Encoding]::ASCII)
$shTexto = [IO.File]::ReadAllText((Join-Path $aqui "bin\pentaho-live")) -replace "`r`n", "`n"
[IO.File]::WriteAllText((Join-Path $destBin "pentaho-live"), $shTexto, [Text.Encoding]::ASCII)
Copy-Item (Join-Path $repo "LICENSE") (Join-Path $staging "LICENSE.txt")
Copy-Item (Join-Path $repo "instalar.ps1") (Join-Path $staging "instalar.ps1")
Write-Host "   bin\pentaho-live.cmd, bin\pentaho-live, LICENSE.txt, instalar.ps1"

# ------------------------------------------------------------------ 6. imagenes
Paso "6. Icono e imagenes del asistente (generar_imagenes.py)"
$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) { $python = Get-Command py -ErrorAction SilentlyContinue }
if (-not $python) { Falla "No hay Python en el PATH (hace falta con: pip install playwright pillow)." }
& $python.Source (Join-Path $aqui "generar_imagenes.py") --salida (Join-Path $staging "asistente") --version $Version
if ($LASTEXITCODE -ne 0) { Falla "generar_imagenes.py fallo (codigo $LASTEXITCODE). Hace falta: pip install playwright pillow, y Google Chrome." }
Copy-Item (Join-Path $aqui "pentaho-live.ico") (Join-Path $staging "pentaho-live.ico")

# ------------------------------------------------------------------ 7. controles
Paso "7. Controles"
$problemas = @()

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
Write-Host "Listo: instalador\staging (version $Version)"
Write-Host ("   data-integration " + (& $mb $destPdi) + ", jre " + (& $mb $destJre) + ", total " + (& $mb $staging))
Write-Host "Siguiente: abrir instalador\instalador.iss con Inno Setup 7 y compilar (F9)."
Write-Host "Sale: instalador\Instalador_PentahoLive_$Version.exe"
