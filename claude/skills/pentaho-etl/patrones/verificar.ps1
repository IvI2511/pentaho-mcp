<#
  verificar.ps1 - runs every pattern of this folder with Kitchen (PDI 9.4, Windows) and checks the
  result in its SQLite database with verificar.py. Prints OK / FALLA per pattern.

  Usage, from PowerShell in this folder:
    .\verificar.ps1 -PdiHome 'C:\pdi\data-integration'
    .\verificar.ps1 -Solo 03,04                       # only some patterns (PDI_HOME set)
  If the execution policy blocks it:
    powershell -ExecutionPolicy Bypass -File .\verificar.ps1 -PdiHome 'C:\pdi\data-integration'

  - Every pattern starts from scratch: its salida\ folder is deleted first.
  - KETTLE_HOME points to a folder of its own, so the user's ~\.kettle is left alone.
  - One JVM at a time; before each one it waits until there are -MinFreeMB of free memory
    (at most -MaxWaitMinutes, then it goes on anyway).
  - The log of every run stays in <pattern>\salida\logs\.
  - Needs Python 3 (standard library only) for the checks.
#>
param(
  [string]$PdiHome = $env:PDI_HOME,
  [string]$KettleHome = (Join-Path $env:TEMP 'kettle-home-patrones'),
  [string]$Python = '',
  [string[]]$Solo = @(),
  [int]$MinFreeMB = 2048,
  [int]$MaxWaitMinutes = 10,
  [string]$JavaOptions = '-Xms256m -Xmx1024m'
)

$aqui = $PSScriptRoot
if (-not $PdiHome) { throw "Pass -PdiHome <path to data-integration> or set the PDI_HOME variable." }
$kitchen = Join-Path $PdiHome 'Kitchen.bat'
if (-not (Test-Path -LiteralPath $kitchen)) { throw "Kitchen.bat not found in $PdiHome" }

if (-not $Python) {
  foreach ($cand in @('python', 'python3', 'py')) {
    if (Get-Command $cand -ErrorAction SilentlyContinue) {
      & $cand -c "import sqlite3" 2>$null
      if ($LASTEXITCODE -eq 0) { $Python = $cand; break }
    }
  }
  if (-not $Python) { throw "Python 3 not found: pass -Python <path to python.exe>." }
}

# Same environment as the recipe in SKILL.md ("Verify with Pan / Kitchen").
Remove-Item Env:NoDefaultCurrentDirectoryInExePath -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force (Join-Path $KettleHome '.kettle') | Out-Null
$env:KETTLE_HOME = $KettleHome
# Small data: a smaller heap than the default 1-2 GB. No inner quotes (Spoon.bat tests the variable with "").
$env:PENTAHO_DI_JAVA_OPTIONS = $JavaOptions

function Wait-FreeMemory {
  $limite = (Get-Date).AddMinutes($MaxWaitMinutes)
  while ($true) {
    $libreMB = [int]((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1KB)
    if ($libreMB -ge $MinFreeMB) { return }
    if ((Get-Date) -gt $limite) {
      Write-Host "    (only $libreMB MB free after $MaxWaitMinutes min: running anyway)"
      return
    }
    Write-Host "    waiting for memory: $libreMB MB free, $MinFreeMB needed..."
    Start-Sleep -Seconds 15
  }
}

# Runs one job and returns its exit code. Kettle's own log goes to salida\logs\<Nombre>.log
# (/logfile), the console output (JVM warnings included) to <Nombre>.console.txt.
function Invoke-Kitchen([string]$Patron, [string]$Job, [string]$Nombre, [string[]]$Params = @()) {
  Wait-FreeMemory
  $carpeta = Join-Path $aqui $Patron
  $logs = Join-Path $carpeta 'salida\logs'
  New-Item -ItemType Directory -Force $logs | Out-Null
  $log = Join-Path $logs "$Nombre.log"
  $consola = Join-Path $logs "$Nombre.console.txt"
  $argumentos = @(('"/file:' + (Join-Path $carpeta $Job) + '"'), '/level:Basic', ('"/logfile:' + $log + '"'))
  # Literal quotes: cmd splits the arguments of a .bat at '=' (03-errores-comunes.md, B3).
  foreach ($p in $Params) { $argumentos += ('"/param:' + $p + '"') }
  $eap = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  # 'cmd /c call' keeps the real exit code ('& Kitchen.bat' always gives 0).
  & cmd.exe /c call $kitchen @argumentos *> $consola
  $codigo = $LASTEXITCODE
  $ErrorActionPreference = $eap
  $script:ultimoLog = $log
  Write-Host ("    {0,-28} exit {1}" -f $Nombre, $codigo)
  return $codigo
}

function Test-Patron([string]$Chequeo, [string]$Patron) {
  # Out-Host: the lines of the check must not end up in the function's return value.
  & $Python -I (Join-Path $aqui 'verificar.py') $Chequeo (Join-Path $aqui $Patron) $script:ultimoLog | Out-Host
  return ($LASTEXITCODE -eq 0)
}

# Each step: a Kitchen run (Job, Nombre, Params) optionally followed by a check (Chequeo).
$patrones = [ordered]@{
  '01' = @{ Carpeta = '01_csv_a_staging'; Pasos = @(
      @{ Job = '01_csv_a_staging.kjb'; Nombre = 'run1' },
      @{ Job = '01_csv_a_staging.kjb'; Nombre = 'run2'; Chequeo = '01' }) }
  '02' = @{ Carpeta = '02_deduplicacion'; Pasos = @(
      @{ Job = '02_deduplicacion.kjb'; Nombre = 'run1'; Chequeo = '02' }) }
  '03' = @{ Carpeta = '03_carga_incremental'; Pasos = @(
      @{ Job = '03_preparar_origen.kjb'; Nombre = 'source_batch1'; Params = @('LOTE=1') },
      @{ Job = '03_carga_incremental.kjb'; Nombre = 'run1'; Chequeo = '03-1' },
      @{ Job = '03_preparar_origen.kjb'; Nombre = 'source_batch2'; Params = @('LOTE=2') },
      @{ Job = '03_carga_incremental.kjb'; Nombre = 'run2'; Chequeo = '03-2' },
      @{ Job = '03_carga_incremental.kjb'; Nombre = 'run3_no_new_data'; Chequeo = '03-3' }) }
  '04' = @{ Carpeta = '04_scd_tipo2'; Pasos = @(
      @{ Job = '04_scd_tipo2.kjb'; Nombre = 'run1_day1'; Chequeo = '04-1' },
      @{ Job = '04_scd_tipo2.kjb'; Nombre = 'run2_day2'; Chequeo = '04-2'
         Params = @('ARCHIVO_CLIENTES=clientes_dia2.csv', 'FECHA_CARGA=2026-10-15') },
      @{ Job = '04_scd_tipo2.kjb'; Nombre = 'run3_day2_again'; Chequeo = '04-3'
         Params = @('ARCHIVO_CLIENTES=clientes_dia2.csv', 'FECHA_CARGA=2026-10-15') }) }
  '05' = @{ Carpeta = '05_hecho_con_claves'; Pasos = @(
      @{ Job = '05_hecho_con_claves.kjb'; Nombre = 'run1_batch1'; Chequeo = '05-1'; Params = @('LOTE=1') },
      @{ Job = '05_hecho_con_claves.kjb'; Nombre = 'run2_batch2'; Chequeo = '05-2'; Params = @('LOTE=2') }) }
}

$resultados = [ordered]@{}
foreach ($id in $patrones.Keys) {
  if ($Solo.Count -gt 0 -and $Solo -notcontains $id) { continue }
  $pat = $patrones[$id]
  Write-Host ""
  Write-Host "== $($pat.Carpeta)"
  Remove-Item -Recurse -Force (Join-Path (Join-Path $aqui $pat.Carpeta) 'salida') -ErrorAction SilentlyContinue
  $ok = $true
  foreach ($paso in $pat.Pasos) {
    $params = @(); if ($paso.Params) { $params = $paso.Params }
    $codigo = Invoke-Kitchen $pat.Carpeta $paso.Job $paso.Nombre $params
    if ($codigo -ne 0) {
      Write-Host "    Kitchen ended with exit ${codigo}: see $script:ultimoLog"
      $ok = $false; break
    }
    if ($paso.Chequeo -and -not (Test-Patron $paso.Chequeo $pat.Carpeta)) { $ok = $false }
  }
  $resultados[$pat.Carpeta] = $ok
}

Write-Host ""
Write-Host "== Summary"
foreach ($k in $resultados.Keys) {
  if ($resultados[$k]) { $estado = 'OK' } else { $estado = 'FALLA' }
  Write-Host ("  {0,-6} {1}" -f $estado, $k)
}
if ($resultados.Values -contains $false) { exit 1 }
exit 0
