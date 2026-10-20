# Corpus de regresión del linter (docs/linter-diseno.md §3, WP7), sin Spoon: compila el plugin y el arnés
# (ArnesLint.java) contra PDI, arma las mutaciones (mutar.py) y corre el motor sobre los samples de PDI, los
# ejemplos de la skill y las mutaciones, y otra vez con el modo profundo los que tienen hallazgos con sonda (sus
# previews solo leen). Falla si cae el recall, si falla una prueba, si cambian los conteos de linea-base.tsv
# (-EscribirBase la rehace cuando el cambio es a propósito) o si el modo profundo no da lo de esperado-profundo.tsv.
#
# Uso:  powershell -ExecutionPolicy Bypass -File tools\lint-corpus\correr.ps1 [-PdiHome <ruta data-integration>]
#       [-Salida <carpeta>] [-KettleHome <carpeta>] [-Idioma en|es|pt] [-SinSamples] [-EscribirBase]
#       [-MemoriaMinGB 2]
# No escribe nada en PDI: todo va a -Salida (por defecto %TEMP%\pentaho-lint-corpus) y a -KettleHome (los logs
# de Kettle van a -Salida\logs: el log4j2.xml de PDI usa rutas relativas). Antes de javac y de java espera a
# tener -MemoriaMinGB libres; las dos JVM corren una después de la otra, con -Xmx1g.
param(
  [string]$PdiHome = $env:PDI_HOME,
  [string]$Salida = (Join-Path $env:TEMP "pentaho-lint-corpus"),
  [string]$KettleHome = (Join-Path $env:TEMP "kettle-home-lint"),
  [ValidateSet("en", "es", "pt")][string]$Idioma = "en",
  [switch]$SinSamples,
  [switch]$EscribirBase,
  [double]$MemoriaMinGB = 2
)

$ErrorActionPreference = "Stop"
if (-not $PdiHome) { throw "Pasa -PdiHome <ruta a data-integration> o defini la variable PDI_HOME." }
. (Join-Path $PSScriptRoot "jdk.ps1")
$jdkBin = Find-JdkBin
$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$plugin = Join-Path $repo "spoon-plugin"
$classes = Join-Path $Salida "classes"
$res = Join-Path $Salida "resultado"
$mut = Join-Path $Salida "mutaciones"

# --- compilar el plugin entero (LintEntorno usa RunMotor y TransOps) y el arnés
if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
New-Item -ItemType Directory -Force $classes, $res | Out-Null
$fuentes = @(Get-ChildItem (Join-Path $plugin "src\main\java") -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$fuentes += (Join-Path $PSScriptRoot "ArnesLint.java")
$argfile = Join-Path $Salida "fuentes.txt"
$fuentes | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' } | Set-Content -Encoding ASCII $argfile
$cp = (Join-Path $PdiHome "lib\*") + ";" + (Join-Path $PdiHome "libswt\win64\*")
# POI 4.1.1 (el PowerPoint de los informes, desde la 0.5.0): como build.ps1, de pdi-core-plugins\lib
$poi = @("poi-4.1.1.jar", "poi-ooxml-4.1.1.jar", "poi-ooxml-schemas-4.1.1.jar") | ForEach-Object {
  $p = Join-Path $PdiHome "plugins\pdi-core-plugins\lib\$_"
  if (-not (Test-Path $p)) { throw "Falta $p (POI 4.1.1 de PDI 9.4)" }
  $p
}
$cp = $cp + ";" + ($poi -join ";")
Wait-Memoria $MemoriaMinGB
& (Join-Path $jdkBin "javac.exe") -J-Xmx768m --release 8 -encoding UTF-8 -Xlint:-options -nowarn -cp $cp -d $classes "@$argfile"
if ($LASTEXITCODE -ne 0) { throw "javac fallo ($LASTEXITCODE)" }
Copy-Item -Recurse -Force (Join-Path $plugin "src\main\resources\*") $classes

# --- mutaciones
& python -I (Join-Path $PSScriptRoot "mutar.py") $mut
if ($LASTEXITCODE -ne 0) { throw "mutar.py fallo ($LASTEXITCODE)" }

# --- correr
New-Item -ItemType Directory -Force (Join-Path $KettleHome ".kettle") | Out-Null
$env:KETTLE_HOME = $KettleHome
$corpus = @()
if (-not $SinSamples) { $corpus += ("samples=" + (Join-Path $PdiHome "samples")) }
$corpus += ("skill=" + (Join-Path $repo "claude\skills\pentaho-etl\manual\ejemplos"))
$corpus += ("mutaciones=" + $mut)
$props = @("-Xmx1g", "-Dlint.lang=$Idioma", "-Dlint.esperado=$(Join-Path $PSScriptRoot 'esperado.tsv')",
  "-Dlint.profundo=$(Join-Path $PSScriptRoot 'esperado-profundo.tsv')",
  "-DKETTLE_HOME=$KettleHome", "-DPENTAHO_METASTORE_FOLDER=$KettleHome",
  "-DKETTLE_PLUGIN_BASE_FOLDERS=$(Join-Path $PdiHome 'plugins')", "-Djava.locale.providers=COMPAT,SPI")
if (-not $SinSamples) {
  $props += "-Dlint.base=$(Join-Path $PSScriptRoot 'linea-base.tsv')"
  if ($EscribirBase) { $props += "-Dlint.base.escribir=true" }
}
$opens = @("--add-opens", "java.base/java.net=ALL-UNNAMED", "--add-opens", "java.base/java.lang=ALL-UNNAMED",
  "--add-opens", "java.base/sun.net.www.protocol.jar=ALL-UNNAMED")
$runCp = "$classes;$(Join-Path $PdiHome 'lib\*');$(Join-Path $PdiHome 'classes')"
Wait-Memoria $MemoriaMinGB
Push-Location $Salida
try {
  & (Join-Path $jdkBin "java.exe") @props @opens -cp $runCp io.github.ivi2511.pentahomcp.ArnesLint $res @corpus
  $codigo = $LASTEXITCODE
} finally {
  Pop-Location
}
Write-Host ""
Write-Host "Resultados en $res (hallazgos.tsv, puntajes.tsv, no_evaluados.tsv, profundo.tsv, resumen.txt, json\)"
exit $codigo
