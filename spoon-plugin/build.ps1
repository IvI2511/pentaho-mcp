# Compila el plugin Pentaho MCP contra la instalacion de PDI 9.4 y deja la carpeta lista
# para copiar a <PDI>\plugins\ en plugin\dist\pentaho-mcp\.
#
# Uso:  powershell -ExecutionPolicy Bypass -File spoon-plugin\build.ps1 [-PdiHome <ruta data-integration>]
param(
  [string]$PdiHome = $env:PDI_HOME,
  [string]$Version = "0.3.1"
)

$ErrorActionPreference = "Stop"
if (-not $PdiHome) { throw "Pasa -PdiHome <ruta a data-integration> o defini la variable PDI_HOME." }
$root  = $PSScriptRoot
$src   = Join-Path $root "src\main\java"
$res   = Join-Path $root "src\main\resources"
$build = Join-Path $root "build"
$classes = Join-Path $build "classes"
$dist  = Join-Path $root "dist\pentaho-mcp"

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

# --- zip para el release de GitHub (se descomprime tal cual dentro de plugins\)
$zip = Join-Path $root "dist\pentaho-mcp-$Version.zip"
if (Test-Path $zip) { Remove-Item -Force $zip }
Compress-Archive -Path $dist -DestinationPath $zip
# copia con nombre fijo: los instaladores la bajan de .../releases/latest/download/pentaho-mcp.zip
Copy-Item -Force $zip (Join-Path $root "dist\pentaho-mcp.zip")

Write-Host ""
Write-Host "OK -> $dist"
Write-Host "Zip -> $zip"
Get-ChildItem -Recurse $dist | ForEach-Object { Write-Host ("   " + $_.FullName.Substring($dist.Length)) }
Write-Host ""
Write-Host "Instalar: copiar la carpeta pentaho-mcp a `"$PdiHome\plugins\`" y reiniciar Spoon."
