# Compila main + test contra PDI 9.4 y corre las pruebas headless (sin Spoon) con el runner Pruebas.
#
# Uso:
#   powershell -ExecutionPolicy Bypass -File spoon-plugin\test.ps1 [-PdiHome <data-integration>]
#       [-Salida <carpeta de build>] [-Solo <PruebaX[,PruebaY.metodo]>] [-SinCompilar]
#
#   -PdiHome   data-integration de PDI 9.4. Por defecto PDI_HOME, si no "<repo>\..\PDI 9.4\data-integration".
#   -Salida    carpeta propia de esta corrida (default spoon-plugin\build\test). Adentro quedan classes\,
#              test-classes\ y prueba\ (cwd de la JVM, KETTLE_HOME, user.home y lo que escriben las pruebas).
#              Agentes en paralelo: cada uno con su -Salida, por ejemplo spoon-plugin\build\wp3.
#   -Solo      filtro de clases o metodos separados por coma (PruebaModelo, PruebaHtml.pruebaEscapes).
#   -SinCompilar  reusa lo compilado en -Salida.
#
# Nunca escribe en data-integration ni en el ~\.kettle real. Codigo de salida: el del runner
# (0 todo bien, 1 alguna falla, 2 ninguna prueba coincide), o 3 si falla la compilacion o el bytecode.
param(
  [string]$PdiHome = $env:PDI_HOME,
  [string]$Salida = "",
  [string]$Solo = "",
  [switch]$SinCompilar
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
if (-not $PdiHome) {
  $cand = Join-Path (Split-Path (Split-Path $root -Parent) -Parent) "PDI 9.4\data-integration"
  if (Test-Path $cand) { $PdiHome = $cand }
}
if (-not $PdiHome -or -not (Test-Path (Join-Path $PdiHome "lib\kettle-engine-9.4.0.0-343.jar"))) {
  throw "Pasa -PdiHome <ruta a data-integration de PDI 9.4> o defini PDI_HOME."
}
$PdiHome = (Resolve-Path $PdiHome).Path
if (-not $Salida) { $Salida = Join-Path $root "build\test" }
New-Item -ItemType Directory -Force $Salida | Out-Null
$Salida = (Resolve-Path $Salida).Path
if ($Salida.StartsWith($PdiHome, [StringComparison]::OrdinalIgnoreCase)) { throw "-Salida no puede estar dentro de data-integration" }

$classes     = Join-Path $Salida "classes"
$testClasses = Join-Path $Salida "test-classes"
$prueba      = Join-Path $Salida "prueba"
$srcMain     = Join-Path $root "src\main\java"
$resMain     = Join-Path $root "src\main\resources"
$srcTest     = Join-Path $root "src\test\java"
$resTest     = Join-Path $root "src\test\resources"

# --- JDK (javac + java). Igual que build.ps1.
function Find-JdkBin {
  if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\javac.exe"))) {
    return (Join-Path $env:JAVA_HOME "bin")
  }
  $props = cmd /c "java -XshowSettings:properties -version 2>&1" | Out-String
  if ($props -match "java\.home = (.+)") {
    $home2 = $Matches[1].Trim()
    if (Test-Path (Join-Path $home2 "bin\javac.exe")) { return (Join-Path $home2 "bin") }
  }
  $cand = Get-ChildItem "C:\Program Files\Java" -Directory -Filter "jdk*" -ErrorAction SilentlyContinue |
          Sort-Object Name -Descending | Select-Object -First 1
  if ($cand -and (Test-Path (Join-Path $cand.FullName "bin\javac.exe"))) { return (Join-Path $cand.FullName "bin") }
  throw "No encuentro un JDK con javac.exe (defini JAVA_HOME)."
}
$jdkBin = Find-JdkBin
$javac = Join-Path $jdkBin "javac.exe"
$java  = Join-Path $jdkBin "java.exe"

# --- classpath de PDI: lib\*, libswt\win64\*, classes\ y POI 4.1.1 (va en el lib\ del plugin)
$poiDir = Join-Path $PdiHome "plugins\pdi-core-plugins\lib"
$poi = @("poi-4.1.1.jar", "poi-ooxml-4.1.1.jar", "poi-ooxml-schemas-4.1.1.jar") | ForEach-Object {
  $p = Join-Path $poiDir $_
  if (-not (Test-Path $p)) { throw "Falta $p" }
  $p
}
$cpPdi = @((Join-Path $PdiHome "lib\*"), (Join-Path $PdiHome "libswt\win64\*"), (Join-Path $PdiHome "classes")) + $poi
$cpPdi = $cpPdi -join ";"

# javac y java escriben en stderr: que PowerShell 5.1 no lo convierta en error que corta el script.
# Muestra la salida a medida que llega y devuelve solo el codigo de salida.
function Invoke-Nativo([string]$exe, [string[]]$argumentos) {
  $prev = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  try {
    & $exe @argumentos 2>&1 | ForEach-Object {
      if ($_ -is [System.Management.Automation.ErrorRecord]) { Write-Host $_.Exception.Message } else { Write-Host "$_" }
    }
    return $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $prev
  }
}

function Write-ArgFile([string]$dir, [string]$archivo) {
  $fuentes = @(Get-ChildItem $dir -Recurse -Filter *.java | ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' })
  $fuentes | Set-Content -Encoding ASCII $archivo
  return $fuentes.Count
}

if (-not $SinCompilar) {
  foreach ($d in @($classes, $testClasses)) {
    if (Test-Path $d) { Remove-Item -Recurse -Force $d }
    New-Item -ItemType Directory -Force $d | Out-Null
  }
  Write-Host "JDK:    $jdkBin"
  Write-Host "PDI:    $PdiHome"
  Write-Host "Salida: $Salida"

  # --- main
  $argMain = Join-Path $Salida "fuentes-main.txt"
  $n = Write-ArgFile $srcMain $argMain
  Write-Host "javac main ($n archivos)..."
  $code = Invoke-Nativo $javac @("--release", "8", "-encoding", "UTF-8", "-Xlint:-options", "-nowarn", "-cp", $cpPdi, "-d", $classes, "@$argMain")
  if ($code -ne 0) { Write-Host "COMPILACION FALLIDA (main)"; exit 3 }
  if (Test-Path $resMain) { Copy-Item -Recurse -Force (Join-Path $resMain "*") $classes }

  # --- test
  $argTest = Join-Path $Salida "fuentes-test.txt"
  $n = Write-ArgFile $srcTest $argTest
  Write-Host "javac test ($n archivos)..."
  $code = Invoke-Nativo $javac @("--release", "8", "-encoding", "UTF-8", "-Xlint:-options", "-nowarn", "-cp", ($classes + ";" + $cpPdi), "-d", $testClasses, "@$argTest")
  if ($code -ne 0) { Write-Host "COMPILACION FALLIDA (test)"; exit 3 }
  if (Test-Path $resTest) { Copy-Item -Recurse -Force (Join-Path $resTest "*") $testClasses }

  # --- bytecode Java 8 (major 52) en todas las clases
  $malas = @()
  foreach ($f in Get-ChildItem @($classes, $testClasses) -Recurse -Filter *.class) {
    $fs = [System.IO.File]::OpenRead($f.FullName)
    try { $b = New-Object byte[] 8; [void]$fs.Read($b, 0, 8) } finally { $fs.Close() }
    $major = [int]$b[6] * 256 + [int]$b[7]
    if ($major -ne 52) { $malas += "$($f.FullName) (major $major)" }
  }
  if ($malas.Count -gt 0) { Write-Host "BYTECODE QUE NO ES JAVA 8:"; $malas | ForEach-Object { Write-Host "  $_" }; exit 3 }
  Write-Host "Bytecode: todas las clases con major 52 (Java 8)."
} elseif (-not (Test-Path $testClasses)) {
  throw "-SinCompilar pero no hay $testClasses"
}

# --- entorno aislado: KETTLE_HOME, user.home y metastore propios, cwd en prueba\
New-Item -ItemType Directory -Force $prueba | Out-Null
$kettleHome = Join-Path $prueba "kettle-home"
if (Test-Path $kettleHome) { Remove-Item -Recurse -Force $kettleHome }
New-Item -ItemType Directory -Force (Join-Path $kettleHome ".kettle") | Out-Null
$userHome = Join-Path $prueba "home"
New-Item -ItemType Directory -Force $userHome | Out-Null
$metastore = Join-Path $prueba "metastore"
New-Item -ItemType Directory -Force $metastore | Out-Null

# classpath de la corrida; claude\skills da el catalogo pentaho-etl/ que McpResources lee del classpath
$cpRun = $testClasses + ";" + $classes
$skills = Join-Path (Split-Path $root -Parent) "claude\skills"
if (Test-Path (Join-Path $skills "pentaho-etl")) { $cpRun += ";" + $skills }
$cpRun += ";" + $cpPdi

$jvm = @(
  "-Xmx2048m",
  "--add-opens", "java.base/java.net=ALL-UNNAMED",
  "--add-opens", "java.base/java.lang=ALL-UNNAMED",
  "--add-opens", "java.base/sun.net.www.protocol.jar=ALL-UNNAMED",
  "-Djava.locale.providers=COMPAT,SPI",
  "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
  "-Djava.awt.headless=true",
  "-Djava.library.path=$(Join-Path $PdiHome 'libswt\win64')",
  "-DKETTLE_HOME=$kettleHome",
  "-DKETTLE_PLUGIN_BASE_FOLDERS=$(Join-Path $PdiHome 'plugins')",
  "-DPENTAHO_METASTORE_FOLDER=$metastore",
  "-Duser.home=$userHome",
  "-Dpruebas.dir=$prueba",
  "-Dpruebas.recursos=$resTest",
  "-Dpruebas.pdi=$PdiHome",
  "-cp", $cpRun,
  "io.github.ivi2511.pentahomcp.Pruebas"
)
if ($Solo) { $jvm += $Solo }

$prevKettle = $env:KETTLE_HOME
$prevOut = [Console]::OutputEncoding
$prevLoc = Get-Location
try {
  $env:KETTLE_HOME = $kettleHome   # Const.getKettleDirectory mira primero la variable de entorno
  [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
  Set-Location $prueba
  Write-Host "Corriendo pruebas$(if ($Solo) { " ($Solo)" })..."
  $code = Invoke-Nativo $java $jvm
} finally {
  Set-Location $prevLoc
  $env:KETTLE_HOME = $prevKettle
  [Console]::OutputEncoding = $prevOut
}
exit $code
