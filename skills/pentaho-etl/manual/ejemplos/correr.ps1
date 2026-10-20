<#
  correr.ps1 - ejecuta una .ktr con Pan o una .kjb con Kitchen (PDI 9.4, Windows).

  Uso:
    .\correr.ps1 01_generar_constantes.ktr
    .\correr.ps1 02_filtrar_clientes.ktr -Param SALDO_MINIMO=1000
    .\correr.ps1 10_job_secuencia.kjb -Nivel Detailed -Ingles

  - Usa un KETTLE_HOME aislado para no tocar ~/.kettle del usuario.
  - Quita NoDefaultCurrentDirectoryInExePath: si esa variable esta definida
    (Claude Code la define), Pan.bat no encuentra Spoon.bat y falla con
    '"Spoon.bat" no se reconoce como un comando interno o externo'.
  - -Ingles fuerza mensajes de log en ingles (utiles para buscar errores).
  - Devuelve el exit code de Pan/Kitchen (0 = OK; ver 03-errores-comunes.md).
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

if (-not $PdiHome) { throw "Pasa -PdiHome <ruta a data-integration> o defini la variable PDI_HOME." }
$ruta = (Resolve-Path -LiteralPath $Archivo).Path
Remove-Item Env:NoDefaultCurrentDirectoryInExePath -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force (Join-Path $KettleHome ".kettle") | Out-Null
$env:KETTLE_HOME = $KettleHome
if ($Ingles) {
  # Sin comillas internas: Spoon.bat hace  if "%PENTAHO_DI_JAVA_OPTIONS%"==""  y las comillas lo rompen.
  $env:PENTAHO_DI_JAVA_OPTIONS = '-Xms1024m -Xmx2048m -Duser.language=en -Duser.country=US'
}

if ($ruta -like '*.kjb') { $bat = Join-Path $PdiHome 'Kitchen.bat' } else { $bat = Join-Path $PdiHome 'Pan.bat' }

$argumentos = @("/file:$ruta", "/level:$Nivel")
# Comillas literales obligatorias: cmd parte los argumentos de un .bat en '=' (y en ',' y ';'),
# asi que /param:X=1 sin comillas llega a Pan como dos argumentos -> "Command line option not understood" (exit 8).
foreach ($p in $Param) { $argumentos += ('"/param:' + $p + '"') }

# Se invoca via 'cmd /c call' porque '& Pan.bat' desde PowerShell pierde el exit code
# (siempre devuelve 0). Con 'call' el codigo de Pan/Kitchen llega intacto.
& cmd.exe /c call $bat @argumentos
$codigo = $LASTEXITCODE
Write-Host "EXIT CODE = $codigo"
exit $codigo
