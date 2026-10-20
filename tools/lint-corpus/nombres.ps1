# Genera la lista de nombres por defecto de steps y entradas en en/es/pt que usa el linter
# (default_step_name / default_entry_name): un arranque de Kettle por idioma, porque PluginRegistry solo trae
# los nombres del idioma con que arranca. Deja spoon-plugin\src\main\resources\...\lint\nombres_por_defecto.txt,
# que se versiona y build.ps1 copia al jar con el resto de los recursos. Correrlo al cambiar de versión de PDI.
#
# Uso:  powershell -ExecutionPolicy Bypass -File tools\lint-corpus\nombres.ps1 [-PdiHome <ruta data-integration>]
param(
  [string]$PdiHome = $env:PDI_HOME,
  [string]$Trabajo = (Join-Path $env:TEMP "pentaho-lint-nombres"),
  [string]$KettleHome = (Join-Path $env:TEMP "kettle-home-lint"),
  [double]$MemoriaMinGB = 2
)

$ErrorActionPreference = "Stop"
if (-not $PdiHome) { throw "Pasa -PdiHome <ruta a data-integration> o defini la variable PDI_HOME." }
. (Join-Path $PSScriptRoot "jdk.ps1")
$jdkBin = Find-JdkBin
$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$destino = Join-Path $repo "spoon-plugin\src\main\resources\io\github\ivi2511\pentahomcp\lint\nombres_por_defecto.txt"

$classes = Join-Path $Trabajo "classes"
New-Item -ItemType Directory -Force $classes | Out-Null
New-Item -ItemType Directory -Force (Join-Path $KettleHome ".kettle") | Out-Null
$cp = (Join-Path $PdiHome "lib\*")
Wait-Memoria $MemoriaMinGB
& (Join-Path $jdkBin "javac.exe") --release 8 -encoding UTF-8 -nowarn -Xlint:-options -cp $cp -d $classes (Join-Path $PSScriptRoot "VolcarNombres.java")
if ($LASTEXITCODE -ne 0) { throw "javac fallo ($LASTEXITCODE)" }

$todos = New-Object System.Collections.Generic.SortedSet[string] ([StringComparer]::Ordinal)
foreach ($l in @(@("en", "US"), @("es", "ES"), @("pt", "BR"))) {
  $out = Join-Path $Trabajo ("nombres_" + $l[0] + ".txt")
  Write-Host "Kettle en $($l[0])_$($l[1])..."
  Wait-Memoria $MemoriaMinGB
  Push-Location $Trabajo
  try {
    & (Join-Path $jdkBin "java.exe") -Xmx1g "-Duser.language=$($l[0])" "-Duser.country=$($l[1])" `
      "-DKETTLE_HOME=$KettleHome" "-DPENTAHO_METASTORE_FOLDER=$KettleHome" "-DKETTLE_PLUGIN_BASE_FOLDERS=$(Join-Path $PdiHome 'plugins')" `
      --add-opens java.base/java.net=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED `
      --add-opens java.base/sun.net.www.protocol.jar=ALL-UNNAMED `
      -cp "$classes;$(Join-Path $PdiHome 'lib\*');$(Join-Path $PdiHome 'classes')" VolcarNombres $out
    if ($LASTEXITCODE -ne 0) { throw "VolcarNombres fallo ($LASTEXITCODE)" }
  } finally {
    Pop-Location
  }
  foreach ($x in [IO.File]::ReadAllLines($out, [Text.Encoding]::UTF8)) {
    if ($x -match "^[SE]`t.") { [void]$todos.Add($x) }
  }
}

$cab = @(
  "# Nombres por defecto de steps (S) y entradas de job (E) de PDI 9.4 en en/es/pt, para default_step_name y",
  "# default_entry_name. Lo genera tools/lint-corpus/nombres.ps1 (un arranque de Kettle por idioma): no editar a mano."
)
$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllLines($destino, [string[]]($cab + @($todos)), $utf8)
Write-Host ("OK -> $destino (" + $todos.Count + " nombres)")
