<#
  correr.ps1 - runs a .ktr with Pan or a .kjb with Kitchen (PDI 9.4, Windows).

  Usage:
    .\correr.ps1 01_generar_constantes.ktr
    .\correr.ps1 02_filtrar_clientes.ktr -Param SALDO_MINIMO=1000
    .\correr.ps1 10_job_secuencia.kjb -Nivel Detailed -Ingles

  - Uses an isolated KETTLE_HOME so the user's ~/.kettle is left untouched.
  - Removes NoDefaultCurrentDirectoryInExePath: if that variable is set
    (Claude Code sets it), Pan.bat cannot find Spoon.bat and fails with
    '"Spoon.bat" no se reconoce como un comando interno o externo'
    (Spanish-locale cmd: "'Spoon.bat' is not recognized as an internal or external command").
  - -Ingles forces English log messages (useful for searching errors).
  - Returns the Pan/Kitchen exit code (0 = OK; see 03-errores-comunes.md).
#>
param(
  [Parameter(Mandatory = $true)][string]$Archivo,
  [ValidateSet('Nothing', 'Error', 'Minimal', 'Basic', 'Detailed', 'Debug', 'Rowlevel')]
  [string]$Nivel = 'Basic',
  [string[]]$Param = @(),
  [switch]$Ingles,
  [string]$PdiHome = $env:PDI_HOME,
  [string]$KettleHome = (Join-Path $env:TEMP 'kettle-home-pruebas')
)

if (-not $PdiHome) { throw "Pass -PdiHome <path to data-integration> or set the PDI_HOME variable." }
$ruta = (Resolve-Path -LiteralPath $Archivo).Path
Remove-Item Env:NoDefaultCurrentDirectoryInExePath -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force (Join-Path $KettleHome ".kettle") | Out-Null
$env:KETTLE_HOME = $KettleHome
if ($Ingles) {
  # No inner quotes: Spoon.bat does  if "%PENTAHO_DI_JAVA_OPTIONS%"==""  and quotes break that test.
  $env:PENTAHO_DI_JAVA_OPTIONS = '-Xms1024m -Xmx2048m -Duser.language=en -Duser.country=US'
}

if ($ruta -like '*.kjb') { $bat = Join-Path $PdiHome 'Kitchen.bat' } else { $bat = Join-Path $PdiHome 'Pan.bat' }

$argumentos = @("/file:$ruta", "/level:$Nivel")
# Literal quotes are mandatory: cmd splits the arguments of a .bat at '=' (and at ',' and ';'),
# so an unquoted /param:X=1 reaches Pan as two arguments -> "Command line option not understood" (exit 8).
foreach ($p in $Param) { $argumentos += ('"/param:' + $p + '"') }

# Invoked via 'cmd /c call' because '& Pan.bat' from PowerShell loses the exit code
# (it always returns 0). With 'call' the Pan/Kitchen exit code comes through intact.
& cmd.exe /c call $bat @argumentos
$codigo = $LASTEXITCODE
Write-Host "EXIT CODE = $codigo"
exit $codigo
