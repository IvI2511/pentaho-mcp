# Compila el plugin Pentaho MCP contra la instalacion de PDI 9.4 y deja la carpeta lista
# para copiar a <PDI>\plugins\ en plugin\dist\pentaho-mcp\.
#
# Uso:  powershell -ExecutionPolicy Bypass -File spoon-plugin\build.ps1 [-PdiHome <ruta data-integration>]
#
# La version sale del archivo VERSION de la raiz del repo (fuente unica: tambien la leen
# instalador\instalador.iss y el plugin, por el recurso pentaho-mcp-version.txt del jar).
param(
  [string]$PdiHome = $env:PDI_HOME
)

$ErrorActionPreference = "Stop"
if (-not $PdiHome) { throw "Pasa -PdiHome <ruta a data-integration> o defini la variable PDI_HOME." }
$root  = $PSScriptRoot
$repo  = Split-Path $root -Parent
$src   = Join-Path $root "src\main\java"
$res   = Join-Path $root "src\main\resources"
$build = Join-Path $root "build"
$classes = Join-Path $build "classes"
$dist  = Join-Path $root "dist\pentaho-mcp"

# --- version: VERSION manda; plugin.json (la ficha del plugin de Claude) tiene que decir lo mismo
$versionFile = Join-Path $repo "VERSION"
if (-not (Test-Path $versionFile)) { throw "Falta $versionFile (una linea con la version, ej. 0.4.0)." }
$Version = (Get-Content -Raw $versionFile).Trim()
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw "VERSION no tiene la forma X.Y.Z: '$Version'" }
$pluginJson = Join-Path $repo "claude\.claude-plugin\plugin.json"
$versionClaude = (Get-Content -Raw -Encoding UTF8 $pluginJson | ConvertFrom-Json).version
if ($versionClaude -ne $Version) {
  throw "claude\.claude-plugin\plugin.json dice version $versionClaude y VERSION dice $Version. Igualalas antes de compilar."
}
Write-Host "Version: $Version"

# --- localizar el JDK (javac + jar). El shim de Oracle en PATH trae javac pero no jar.
function Find-JdkBin {
  if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\jar.exe"))) {
    return (Join-Path $env:JAVA_HOME "bin")
  }
  # via cmd: en PowerShell 5.1 el stderr de un exe con ErrorActionPreference=Stop corta el script
  $props = cmd /c "java -XshowSettings:properties -version 2>&1" | Out-String
  if ($props -match "java\.home = (.+)") {
    $home2 = $Matches[1].Trim()
    if (Test-Path (Join-Path $home2 "bin\jar.exe")) { return (Join-Path $home2 "bin") }
  }
  $cand = Get-ChildItem "C:\Program Files\Java" -Directory -Filter "jdk*" -ErrorAction SilentlyContinue |
          Sort-Object Name -Descending | Select-Object -First 1
  if ($cand -and (Test-Path (Join-Path $cand.FullName "bin\jar.exe"))) { return (Join-Path $cand.FullName "bin") }
  throw "No encuentro un JDK con jar.exe (defini JAVA_HOME)."
}
$jdkBin = Find-JdkBin
$javac = Join-Path $jdkBin "javac.exe"
$jar   = Join-Path $jdkBin "jar.exe"
Write-Host "JDK:  $jdkBin"
Write-Host "PDI:  $PdiHome"

foreach ($p in @("lib\kettle-core-9.4.0.0-343.jar", "lib\kettle-engine-9.4.0.0-343.jar", "lib\kettle-ui-swt-9.4.0.0-343.jar", "libswt\win64\swt.jar")) {
  if (-not (Test-Path (Join-Path $PdiHome $p))) { throw "Falta $p en $PdiHome" }
}
$cp = (Join-Path $PdiHome "lib\*") + ";" + (Join-Path $PdiHome "libswt\win64\*")

# --- limpiar
if (Test-Path $build) { Remove-Item -Recurse -Force $build }
if (Test-Path $dist)  { Remove-Item -Recurse -Force $dist }
New-Item -ItemType Directory -Force $classes | Out-Null
New-Item -ItemType Directory -Force (Join-Path $dist "lib") | Out-Null

# --- compilar (bytecode Java 8: PDI 9.4 es Java 8; Spoon corre aca con Java 21, que lo acepta)
$sources = Get-ChildItem $src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$argfile = Join-Path $build "sources.txt"
# javac lee el @argfile; rutas entre comillas y con / para que los espacios no rompan nada
$sources | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' } | Set-Content -Encoding ASCII $argfile
& $javac --release 8 -encoding UTF-8 -Xlint:-options -nowarn -cp $cp -d $classes "@$argfile"
if ($LASTEXITCODE -ne 0) { throw "javac fallo ($LASTEXITCODE)" }

if (Test-Path $res) { Copy-Item -Recurse -Force (Join-Path $res "*") $classes }

# --- la version, como recurso junto a las clases: McpLifecycleListener.VERSION la lee de ahi
$paquete = Join-Path $classes "io\github\ivi2511\pentahomcp"
[IO.File]::WriteAllText((Join-Path $paquete "pentaho-mcp-version.txt"), $Version, [Text.Encoding]::ASCII)

# --- catalogo y guias de la skill pentaho-etl dentro del jar, para los recursos MCP pentaho://...
# (McpResources los lee del classpath bajo pentaho-etl/, con la misma estructura que la skill).
# Se copian en cada build: la fuente unica sigue siendo claude\skills\pentaho-etl, no se duplican en git.
$skill = Join-Path $repo "claude\skills\pentaho-etl"
$catJar = Join-Path $classes "pentaho-etl"
foreach ($d in @("catalogo\steps", "catalogo\job-entries", "manual")) {
  New-Item -ItemType Directory -Force (Join-Path $catJar $d) | Out-Null
}
foreach ($f in @("catalogo\INDICE.md", "catalogo\steps-comunes.md", "manual\01-formato-ktr.md",
                 "manual\02-formato-kjb.md", "manual\03-errores-comunes.md")) {
  $from = Join-Path $skill $f
  if (-not (Test-Path $from)) { throw "Falta $from (catalogo de la skill pentaho-etl)" }
  Copy-Item -Force $from (Join-Path $catJar $f)
}
foreach ($d in @("catalogo\steps", "catalogo\job-entries")) {
  $xmls = @(Get-ChildItem (Join-Path $skill $d) -Filter *.xml -File)
  if ($xmls.Count -eq 0) { throw "No hay .xml en $(Join-Path $skill $d)" }
  $xmls | Copy-Item -Force -Destination (Join-Path $catJar $d)
  Write-Host ("Recursos: " + $xmls.Count + " .xml de " + $d)
}

# --- empaquetar
$manifest = Join-Path $build "MANIFEST.MF"
@(
  "Implementation-Title: pentaho-mcp",
  "Implementation-Version: $Version",
  ""
) | Set-Content -Encoding ASCII $manifest
$jarFile = Join-Path $dist "pentaho-mcp-core-$Version.jar"
& $jar --create --file $jarFile --manifest $manifest -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar fallo ($LASTEXITCODE)" }

@(
  '<?xml version="1.0" encoding="UTF-8"?>',
  "<version>$Version</version>"
) | Set-Content -Encoding UTF8 (Join-Path $dist "version.xml")

# --- splash oculto de "pentaho-live --launcher" (-Dorg.pentaho.di.laf.alt=plugins/pentaho-mcp/ui/...):
# el de PDI pasa a 1x1 mientras se ve el del lanzador y SenalLanzador lo deshace al arrancar. Rutas
# relativas a data-integration (Spoon.bat hace cd ahi), asi que van en plugins\pentaho-mcp\ui\.
$uiDist = Join-Path $dist "ui"
New-Item -ItemType Directory -Force $uiDist | Out-Null
foreach ($f in @("laf-pentaho-live.properties", "splash-vacio.png")) {
  $from = Join-Path $root "ui\$f"
  if (-not (Test-Path $from)) { throw "Falta $from (splash oculto del lanzador)" }
  Copy-Item -Force $from (Join-Path $uiDist $f)
}

# --- zip para el release de GitHub (se descomprime tal cual dentro de plugins\)
# Con .NET y no con Compress-Archive: el de PowerShell 5.1 guarda las rutas con "\", y en macOS/Linux
# unzip las extrae pero sale con codigo 1, asi que instalar.sh (set -e) cortaba en el paso 2.
$zip = Join-Path $root "dist\pentaho-mcp-$Version.zip"
if (Test-Path $zip) { Remove-Item -Force $zip }
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$distPadre = Split-Path $dist -Parent
$archivo = [IO.Compression.ZipFile]::Open($zip, [IO.Compression.ZipArchiveMode]::Create)
try {
  Get-ChildItem -Recurse $dist | Sort-Object FullName | ForEach-Object {
    $nombre = $_.FullName.Substring($distPadre.Length + 1).Replace([char]92, [char]47)
    if ($_.PSIsContainer) { [void]$archivo.CreateEntry("$nombre/") }
    else {
      [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $archivo, $_.FullName, $nombre, [IO.Compression.CompressionLevel]::Optimal)
    }
  }
} finally { $archivo.Dispose() }
# copia con nombre fijo: los instaladores la bajan de .../releases/latest/download/pentaho-mcp.zip
Copy-Item -Force $zip (Join-Path $root "dist\pentaho-mcp.zip")

Write-Host ""
Write-Host "OK -> $dist"
Write-Host "Zip -> $zip"
Get-ChildItem -Recurse $dist | ForEach-Object { Write-Host ("   " + $_.FullName.Substring($dist.Length)) }
Write-Host ""
Write-Host "Instalar: copiar la carpeta pentaho-mcp a `"$PdiHome\plugins\`" y reiniciar Spoon."
