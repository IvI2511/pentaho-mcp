# Pruebas headless del modo clase (WP8): lote de .ktr anotados y pintados con el TransPainter real, pruebas
# funcionales de las notas de la leccion (undo, registro, ocultar/mostrar) y Pan sobre las copias anotadas y con
# las notas ocultas de los ejemplos de la skill. No va al jar del plugin.
#
# Uso:
#   powershell -ExecutionPolicy Bypass -File tools\pruebas-clase\correr.ps1 -PdiHome <data-integration>
#       [-Salida <carpeta>] [-Jar <jar del plugin>] [-SinPan]
#
#   -PdiHome  data-integration de PDI 9.4 (por defecto PDI_HOME). Se leen samples\transformations (solo lectura).
#   -Salida   carpeta de esta corrida (por defecto %TEMP%\pruebas-clase). Adentro: classes\, kettle-home\, home\,
#             ejemplos\ (copia de manual/ejemplos con *_clase.ktr y *_oculta.ktr), png\, lote.tsv, pan.tsv y logs.
#   -Jar      jar con las clases del plugin. Si no se pasa (o no existe), compila spoon-plugin\src\main\java del
#             ultimo commit (git archive HEAD, asi no lo rompe trabajo a medias de otro) en <Salida>\plugin-classes
#             y arma <Salida>\pentaho-mcp-clases.jar (no corre build.ps1).
#   -ArbolDeTrabajo  compila el plugin desde los archivos tal como estan (sin commitear) en vez de HEAD.
#   -Variantes  solo esas variantes del lote, separadas por coma (icon32_compacta, icon48_compacta,
#             icon32_sin_nota, icon32_acercar, icon48_acercar); vacio = todas.
#   -SinPan   no corre la fase de Pan.
#   -SoloPan  solo la fase de Pan, sobre los ejemplos\ que dejo una corrida anterior en la misma -Salida.
#   -PanFiltro  comodin sobre el nombre de los ejemplos que pasan por Pan (por defecto *; p. ej. 07*).
#
# Codigo de salida: 0 si no hay choques, fallas funcionales ni diferencias en Pan; 1 si las hay; 3 si no compila.
# Nunca escribe en data-integration, en el repo ni en el ~\.kettle real (KETTLE_HOME y user.home propios).
param(
  [string]$PdiHome = $env:PDI_HOME,
  [string]$Salida = "",
  [string]$Jar = "",
  [string]$Variantes = "",
  [switch]$ArbolDeTrabajo,
  [switch]$SinPan,
  [switch]$SoloPan,
  [string]$PanFiltro = "*"
)

$ErrorActionPreference = "Stop"
$aqui = $PSScriptRoot
$repo = (Resolve-Path (Join-Path $aqui "..\..")).Path
if (-not $PdiHome -or -not (Test-Path (Join-Path $PdiHome "lib\kettle-engine-9.4.0.0-343.jar"))) {
  throw "Pasa -PdiHome <ruta a data-integration de PDI 9.4> o define PDI_HOME."
}
$PdiHome = (Resolve-Path $PdiHome).Path
if (-not $Salida) { $Salida = Join-Path $env:TEMP "pruebas-clase" }
New-Item -ItemType Directory -Force $Salida | Out-Null
$Salida = (Resolve-Path $Salida).Path
if ($Salida.StartsWith($PdiHome, [StringComparison]::OrdinalIgnoreCase)) { throw "-Salida no puede estar dentro de data-integration" }

# --- JDK
function Find-JdkBin {
  if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\javac.exe"))) { return (Join-Path $env:JAVA_HOME "bin") }
  $props = cmd /c "java -XshowSettings:properties -version 2>&1" | Out-String
  if ($props -match "java\.home = (.+)") {
    $h = $Matches[1].Trim()
    if (Test-Path (Join-Path $h "bin\javac.exe")) { return (Join-Path $h "bin") }
  }
  throw "No encuentro un JDK con javac.exe (define JAVA_HOME)."
}
$jdkBin = Find-JdkBin
$javac = Join-Path $jdkBin "javac.exe"
$java = Join-Path $jdkBin "java.exe"
$jarExe = Join-Path $jdkBin "jar.exe"

$poiDir = Join-Path $PdiHome "plugins\pdi-core-plugins\lib"
$poi = @("poi-4.1.1.jar", "poi-ooxml-4.1.1.jar", "poi-ooxml-schemas-4.1.1.jar") | ForEach-Object { Join-Path $poiDir $_ }
$cpPdi = (@((Join-Path $PdiHome "lib\*"), (Join-Path $PdiHome "libswt\win64\*"), (Join-Path $PdiHome "classes")) + $poi) -join ";"

function Invoke-Nativo([string]$exe, [string[]]$argumentos, [string]$log = "") {
  $prev = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  try {
    & $exe @argumentos 2>&1 | ForEach-Object {
      $l = if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.Exception.Message } else { "$_" }
      if ($log) { Add-Content -LiteralPath $log -Value $l -Encoding UTF8 }
      Write-Host $l
    }
    return $LASTEXITCODE
  } finally { $ErrorActionPreference = $prev }
}

# Una JVM a la vez y con memoria: espera a tener 1,5 GB libres (cada 60 s, hasta 20 min).
function Esperar-Memoria {
  for ($i = 0; $i -le 20; $i++) {
    $libre = (Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1MB
    if ($libre -ge 1.5) { return }
    Write-Host ("Memoria libre {0:N2} GB < 1,5 GB: espero 60 s..." -f $libre)
    Start-Sleep -Seconds 60
  }
  Write-Host "Sigo con poca memoria libre despues de 20 minutos."
}

function Write-ArgFile([string[]]$dirs, [string]$archivo) {
  $f = @(foreach ($d in $dirs) { Get-ChildItem $d -Recurse -Filter *.java | ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' } })
  $f | Set-Content -Encoding ASCII $archivo
  return $f.Count
}

$ej = Join-Path $Salida "ejemplos"
$kettleHome = Join-Path $Salida "kettle-home"
$codRunner = 0
if (-not $SoloPan) {
# --- 1. jar del plugin (sin build.ps1)
if (-not $Jar -or -not (Test-Path $Jar)) {
  $Jar = Join-Path $Salida "pentaho-mcp-clases.jar"
  $pc = Join-Path $Salida "plugin-classes"
  if (Test-Path $pc) { Remove-Item -Recurse -Force $pc }
  New-Item -ItemType Directory -Force $pc | Out-Null
  $srcPlugin = Join-Path $repo "spoon-plugin\src\main"
  if (-not $ArbolDeTrabajo) {
    $head = Join-Path $Salida "src-head"
    if (Test-Path $head) { Remove-Item -Recurse -Force $head }
    New-Item -ItemType Directory -Force $head | Out-Null
    $tar = Join-Path $Salida "src-head.tar"
    & git -C $repo archive --format=tar -o $tar HEAD spoon-plugin/src/main
    if ($LASTEXITCODE -ne 0) { Write-Host "git archive fallo"; exit 3 }
    & tar -xf $tar -C $head
    if ($LASTEXITCODE -ne 0) { Write-Host "tar fallo"; exit 3 }
    $srcPlugin = Join-Path $head "spoon-plugin\src\main"
    Write-Host "Plugin desde HEAD $(& git -C $repo rev-parse --short HEAD)"
  }
  $n = Write-ArgFile @(Join-Path $srcPlugin "java") (Join-Path $Salida "fuentes-plugin.txt")
  Esperar-Memoria
  Write-Host "javac plugin ($n archivos)..."
  $code = Invoke-Nativo $javac @("--release", "8", "-encoding", "UTF-8", "-Xlint:-options", "-nowarn", "-cp", $cpPdi, "-d", $pc, "@$(Join-Path $Salida 'fuentes-plugin.txt')")
  if ($code -ne 0) { Write-Host "COMPILACION FALLIDA (plugin)"; exit 3 }
  $res = Join-Path $srcPlugin "resources"
  if (Test-Path $res) { Copy-Item -Recurse -Force (Join-Path $res "*") $pc }
  if (Test-Path $Jar) { Remove-Item -Force $Jar }
  $code = Invoke-Nativo $jarExe @("cf", $Jar, "-C", $pc, ".")
  if ($code -ne 0) { Write-Host "NO SE PUDO ARMAR EL JAR"; exit 3 }
}
$Jar = (Resolve-Path $Jar).Path
Write-Host "Jar del plugin: $Jar"

# --- 2. el runner
$classes = Join-Path $Salida "classes"
if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
New-Item -ItemType Directory -Force $classes | Out-Null
$n = Write-ArgFile @(Join-Path $aqui "src") (Join-Path $Salida "fuentes-runner.txt")
Esperar-Memoria
$code = Invoke-Nativo $javac @("--release", "8", "-encoding", "UTF-8", "-Xlint:-options", "-nowarn", "-cp", "$Jar;$cpPdi", "-d", $classes, "@$(Join-Path $Salida 'fuentes-runner.txt')")
if ($code -ne 0) { Write-Host "COMPILACION FALLIDA (runner)"; exit 3 }

# --- 3. copia de los ejemplos de la skill (los .ktr y datos\; lo que escriben va a su salida\)
$ejOrig = Join-Path $repo "claude\skills\pentaho-etl\manual\ejemplos"
$ej = Join-Path $Salida "ejemplos"
if (Test-Path $ej) { Remove-Item -Recurse -Force $ej }
New-Item -ItemType Directory -Force $ej | Out-Null
Copy-Item (Join-Path $ejOrig "*.ktr") $ej
Copy-Item -Recurse (Join-Path $ejOrig "datos") (Join-Path $ej "datos")

# --- 4. entorno aislado y corrida
$kettleHome = Join-Path $Salida "kettle-home"
if (Test-Path $kettleHome) { Remove-Item -Recurse -Force $kettleHome }
New-Item -ItemType Directory -Force (Join-Path $kettleHome ".kettle") | Out-Null
$userHome = Join-Path $Salida "home"
New-Item -ItemType Directory -Force $userHome | Out-Null
$metastore = Join-Path $Salida "metastore"
New-Item -ItemType Directory -Force $metastore | Out-Null
$skills = Join-Path $repo "claude\skills"
$lote = (Join-Path $PdiHome "samples\transformations") + ";" + $ejOrig
$log = Join-Path $Salida "runner.log"
if (Test-Path $log) { Remove-Item -Force $log }

$jvm = @(
  "-Xmx2048m",
  "--add-opens", "java.base/java.net=ALL-UNNAMED",
  "--add-opens", "java.base/java.lang=ALL-UNNAMED",
  "--add-opens", "java.base/sun.net.www.protocol.jar=ALL-UNNAMED",
  "-Djava.locale.providers=COMPAT,SPI",
  "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
  "-Djava.library.path=$(Join-Path $PdiHome 'libswt\win64')",
  "-DKETTLE_HOME=$kettleHome",
  "-DKETTLE_PLUGIN_BASE_FOLDERS=$(Join-Path $PdiHome 'plugins')",
  "-DPENTAHO_METASTORE_FOLDER=$metastore",
  "-Duser.home=$userHome",
  "-Dclase.salida=$Salida",
  "-Dclase.lote=$lote",
  "-Dclase.ejemplos=$ej",
  "-Dclase.variantes=$Variantes",
  "-cp", "$classes;$Jar;$skills;$cpPdi",
  "io.github.ivi2511.pentahomcp.PruebasClase"
)
$prevKettle = $env:KETTLE_HOME
$prevOut = [Console]::OutputEncoding
$prevLoc = Get-Location
try {
  $env:KETTLE_HOME = $kettleHome
  [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
  Set-Location $Salida
  Esperar-Memoria
  Write-Host "Corriendo PruebasClase..."
  $codRunner = Invoke-Nativo $java $jvm $log
} finally {
  Set-Location $prevLoc
  $env:KETTLE_HOME = $prevKettle
  [Console]::OutputEncoding = $prevOut
}
Write-Host "PruebasClase: codigo $codRunner"
if ($SinPan) { exit $codRunner }
} elseif (-not (Test-Path (Join-Path $ej "*_clase.ktr"))) {
  throw "-SoloPan: no hay *_clase.ktr en $ej (corre antes sin -SoloPan)"
}

# --- 5. Pan: original, anotada y con las notas ocultas deben terminar igual (codigo, filas por step y archivos)
function Huella-Salida([string]$dir) {
  if (-not (Test-Path $dir)) { return "" }
  $l = foreach ($f in Get-ChildItem $dir -Recurse -File | Sort-Object FullName) {
    $rel = $f.FullName.Substring($dir.Length)
    if ($f.Extension -in @(".csv", ".txt", ".xml", ".json")) { "$rel=" + (Get-FileHash -Algorithm SHA256 $f.FullName).Hash }
    else { "$rel=existe" }   # binarios (sqlite): solo que este
  }
  return ($l -join ";")
}
function Pasos-Pan([string]$texto) {
  # "Finished processing" o su traduccion ("Procesamiento finalizado"): el mensaje sale en el idioma de Windows
  $m = [regex]::Matches($texto, "- (.+?\.\d+) - [^\(\r\n]*\(I=(\d+), O=(\d+), R=(\d+), W=(\d+), U=(\d+), E=(\d+)\)")
  return (@($m | ForEach-Object { $_.Groups[1].Value.Trim() + " W=" + $_.Groups[5].Value + " E=" + $_.Groups[7].Value }) | Sort-Object) -join "; "
}
function Correr-Pan([string]$ktr) {
  Esperar-Memoria
  Remove-Item Env:NoDefaultCurrentDirectoryInExePath -ErrorAction SilentlyContinue
  $prevK = $env:KETTLE_HOME
  $env:KETTLE_HOME = $kettleHome
  $prev = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  try {
    $out = & cmd.exe /c call (Join-Path $PdiHome "Pan.bat") "/file:$ktr" "/level:Basic" 2>&1 | ForEach-Object { "$_" }
    $code = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $prev
    $env:KETTLE_HOME = $prevK
  }
  return @{ code = $code; texto = ($out -join "`n") }
}

$panTsv = Join-Path $Salida "pan.tsv"
$panLog = Join-Path $Salida "pan.log"
if ($PanFiltro -eq "*") {
  "ejemplo`tvariante`texit`tpasos`tsalida_igual" | Set-Content -Encoding UTF8 $panTsv
  if (Test-Path $panLog) { Remove-Item -Force $panLog }
}
$difPan = 0
foreach ($orig in Get-ChildItem $ej -Filter *.ktr | Where-Object { $_.Name -notmatch "_(clase|oculta)\.ktr$" -and $_.BaseName -like $PanFiltro } | Sort-Object Name) {
  $base = $orig.BaseName
  # (no se llama $variantes: PowerShell no distingue mayusculas y pisaria el parametro -Variantes)
  $copias = @(@("original", $orig.FullName), @("clase", (Join-Path $ej "$($base)_clase.ktr")), @("oculta", (Join-Path $ej "$($base)_oculta.ktr")))
  $ref = $null
  foreach ($v in $copias) {
    if (-not (Test-Path $v[1])) { Write-Host "  FALTA $($v[1])"; $difPan++; continue }
    $sal = Join-Path $ej "salida"
    if (Test-Path $sal) { Remove-Item -Recurse -Force $sal }
    New-Item -ItemType Directory -Force $sal | Out-Null   # el 07 abre salida/demo.sqlite: sin la carpeta, exit 3
    $r = Correr-Pan $v[1]
    Add-Content -LiteralPath $panLog -Value "===== $base $($v[0]) (exit $($r.code))`n$($r.texto)" -Encoding UTF8
    $pasos = Pasos-Pan $r.texto
    $hs = Huella-Salida $sal
    if ($null -eq $ref) {
      $ref = @{ code = $r.code; pasos = $pasos; salida = $hs }
      $igual = "referencia"
    } else {
      $ok = ($r.code -eq $ref.code) -and ($pasos -eq $ref.pasos) -and ($hs -eq $ref.salida)
      $igual = if ($ok) { "si" } else { "NO" }
      if (-not $ok) { $difPan++ }
    }
    if ($r.code -ne 0) { $difPan++ }
    "$base`t$($v[0])`t$($r.code)`t$pasos`t$igual" | Add-Content -Encoding UTF8 $panTsv
    Write-Host ("  Pan {0,-28} {1,-8} exit={2} igual={3}  {4}" -f $base, $v[0], $r.code, $igual, $pasos)
  }
}
Write-Host "Pan: diferencias o salidas distintas de 0 = $difPan"
if ($codRunner -ne 0) { exit $codRunner }
if ($difPan -ne 0) { exit 1 }
exit 0
