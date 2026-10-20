<#
  Instala Pentaho MCP en Windows (PowerShell 5.1 o posterior).

  Hace, en orden (cada paso imprime "== Paso N"):
    1. Encuentra Pentaho Data Integration (data-integration\Spoon.bat), o lo instala entero con -InstalarPdi.
    2. Baja el plugin de Spoon del ultimo release y lo deja en data-integration\plugins\pentaho-mcp\.
    3. Revisa que haya Java.
    4. Configura los agentes que encuentre: Claude Code (plugin del marketplace) y Codex (MCP + skill).

  Uso:
    .\instalar.ps1                                  # busca PDI solo
    .\instalar.ps1 -PdiHome "C:\pentaho\data-integration"
    .\instalar.ps1 -InstalarPdi [-Destino "C:\pentaho"]   # no tiene PDI: baja PDI 9.4 CE con el plugin ya puesto
    .\instalar.ps1 -SinClaude -SinCodex             # solo el plugin de Spoon

  Codigos de salida (para agentes):
    0 = listo
    2 = encontro varias instalaciones de PDI: preguntar cual y repetir con -PdiHome
    3 = no encontro PDI: preguntar donde esta (-PdiHome) o si se instala (-InstalarPdi)
    4 = Spoon esta abierto y traba el plugin viejo: pedir que lo cierre y repetir
    1 = otro error (el mensaje dice cual)
#>
param(
  [string]$PdiHome,
  [switch]$InstalarPdi,
  [string]$Destino = (Join-Path $env:USERPROFILE "Pentaho"),
  [switch]$SinClaude,
  [switch]$SinCodex,
  [int]$Puerto = 8765
)

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$ProgressPreference = "SilentlyContinue"   # Invoke-WebRequest es lentisimo con la barra de progreso

$repo = "IvI2511/pentaho-mcp"
$base = "https://github.com/$repo/releases/latest/download"
$tmp = Join-Path $env:TEMP ("pentaho-mcp-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
New-Item -ItemType Directory -Force $tmp | Out-Null

function Paso($n, $texto) { Write-Host ""; Write-Host "== Paso $n - $texto" }
function Bajar($url, $destino) {
  Write-Host "   bajando $url"
  Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $destino
}
function Salir($codigo, $mensaje) {
  Write-Host ""
  Write-Host $mensaje
  Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
  exit $codigo
}

# ------------------------------------------------------------------ 1. PDI
Paso 1 "Pentaho Data Integration"
if ($InstalarPdi) {
  $zip = Join-Path $tmp "pdi.zip"
  Write-Host "   PDI 9.4 CE con el plugin ya instalado (~420 MB), a $Destino"
  Bajar "$base/pdi-ce-9.4-con-pentaho-mcp.zip" $zip
  New-Item -ItemType Directory -Force $Destino | Out-Null
  Expand-Archive -Path $zip -DestinationPath $Destino -Force
  $PdiHome = Join-Path $Destino "data-integration"
} elseif (-not $PdiHome) {
  if ($env:PDI_HOME) {
    $PdiHome = $env:PDI_HOME
  } else {
    Write-Host "   buscando data-integration\Spoon.bat (puede tardar un poco)..."
    # Solo carpetas (mucho mas rapido que listar archivos); sin AppData, que es enorme y nunca tiene PDI.
    $raices = @("C:\pentaho", "C:\Program Files", "C:\Program Files (x86)",
      (Join-Path $env:USERPROFILE "Downloads"), (Join-Path $env:USERPROFILE "Documents"),
      (Join-Path $env:USERPROFILE "Desktop"), $env:OneDrive) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -Unique
    $hallados = @()
    foreach ($r in $raices) {
      $hallados += Get-ChildItem -Path $r -Filter "data-integration" -Recurse -Depth 10 -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName "Spoon.bat") } | ForEach-Object { $_.FullName }
    }
    # Y carpetas sueltas en la raiz de C:\ (C:\pdi-ce-9.4\data-integration...)
    $hallados += Get-ChildItem -Path "C:\" -Filter "data-integration" -Recurse -Depth 2 -Directory -ErrorAction SilentlyContinue |
      Where-Object { Test-Path (Join-Path $_.FullName "Spoon.bat") } | ForEach-Object { $_.FullName }
    $hallados = @($hallados | Select-Object -Unique)
    if ($hallados.Count -eq 0) {
      Salir 3 "No encontre Pentaho Data Integration. Si lo tenes, repeti con -PdiHome <carpeta data-integration>; si no, con -InstalarPdi."
    }
    if ($hallados.Count -gt 1) {
      Salir 2 ("Encontre varias instalaciones de PDI; repeti con -PdiHome eligiendo una:`n   " + ($hallados -join "`n   "))
    }
    $PdiHome = $hallados[0]
  }
}
$PdiHome = (Resolve-Path $PdiHome).Path
if (-not (Test-Path (Join-Path $PdiHome "Spoon.bat"))) {
  Salir 1 "$PdiHome no parece una carpeta data-integration (no tiene Spoon.bat)."
}
Write-Host "   PDI: $PdiHome"

# ------------------------------------------------------------------ 2. plugin de Spoon
Paso 2 "plugin de Spoon (plugins\pentaho-mcp)"
$plugins = Join-Path $PdiHome "plugins"
$destPlugin = Join-Path $plugins "pentaho-mcp"
if ($InstalarPdi) {
  Write-Host "   ya viene adentro del PDI que se bajo"
} else {
  $zip = Join-Path $tmp "pentaho-mcp.zip"
  Bajar "$base/pentaho-mcp.zip" $zip
  Expand-Archive -Path $zip -DestinationPath $tmp -Force
  try {
    if (Test-Path $destPlugin) { Remove-Item -Recurse -Force $destPlugin }
    Copy-Item -Recurse (Join-Path $tmp "pentaho-mcp") $destPlugin
  } catch {
    Salir 4 "No pude reemplazar $destPlugin : Spoon debe estar abierto y traba el .jar. Cerra Spoon y volve a correr el instalador."
  }
  Write-Host "   instalado en $destPlugin"
}

# ------------------------------------------------------------------ 3. Java
Paso 3 "Java"
$java = Get-Command java -ErrorAction SilentlyContinue
if ($java) {
  $v = (cmd /c "java -version 2>&1" | Select-Object -First 1)
  Write-Host "   $v"
} else {
  Write-Host "   NO hay Java en el PATH: Spoon no va a abrir. Instalalo (11 o posterior), por ejemplo:"
  Write-Host "   winget install EclipseAdoptium.Temurin.21.JRE"
}

# ------------------------------------------------------------------ 4. agentes
Paso 4 "agentes (Claude Code / Codex)"
$url = "http://127.0.0.1:$Puerto/mcp"
if ($Puerto -ne 8765) {
  [Environment]::SetEnvironmentVariable("PENTAHO_MCP_PORT", "$Puerto", "User")
  Write-Host "   PENTAHO_MCP_PORT=$Puerto guardado en las variables del usuario (lo leen Spoon y Claude Code)"
}

if (-not $SinClaude -and (Get-Command claude -ErrorAction SilentlyContinue)) {
  $lista = (cmd /c "claude plugin list 2>&1") | Out-String
  if ($lista -match "pentaho@pentaho-tools") {
    Write-Host "   Claude Code: el plugin ya estaba instalado (para actualizarlo: claude plugin update pentaho@pentaho-tools)"
  } else {
    cmd /c "claude plugin marketplace add $repo 2>&1" | ForEach-Object { "   $_" }
    cmd /c "claude plugin install pentaho@pentaho-tools --scope user 2>&1" | ForEach-Object { "   $_" }
  }
} elseif (-not $SinClaude) {
  Write-Host "   Claude Code no esta en el PATH: se saltea (desde la app: Plugins > Agregar > marketplace $repo)"
}

if (-not $SinCodex -and (Get-Command codex -ErrorAction SilentlyContinue)) {
  $mcps = (cmd /c "codex mcp list 2>&1") | Out-String
  if ($mcps -match "pentaho") {
    Write-Host "   Codex: el MCP 'pentaho' ya estaba configurado"
  } else {
    cmd /c "codex mcp add pentaho --url $url 2>&1" | ForEach-Object { "   $_" }
  }
  $codexHome = if ($env:CODEX_HOME) { $env:CODEX_HOME } else { Join-Path $env:USERPROFILE ".codex" }
  $zip = Join-Path $tmp "repo.zip"
  Bajar "https://github.com/$repo/archive/refs/heads/main.zip" $zip
  Expand-Archive -Path $zip -DestinationPath $tmp -Force
  $skillDest = Join-Path $codexHome "skills\pentaho-etl"
  if (Test-Path $skillDest) { Remove-Item -Recurse -Force $skillDest }
  New-Item -ItemType Directory -Force (Split-Path $skillDest) | Out-Null
  Copy-Item -Recurse (Join-Path $tmp "pentaho-mcp-main\skills\pentaho-etl") $skillDest
  Write-Host "   Codex: skill en $skillDest (si no aparece, activar skills en config.toml y reiniciar Codex)"
} elseif (-not $SinCodex) {
  Write-Host "   Codex no esta en el PATH: se saltea"
}

Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
Write-Host ""
Write-Host "Listo. Falta:"
Write-Host "  1. Abrir (o reiniciar) Spoon: $PdiHome\Spoon.bat"
Write-Host "  2. Reiniciar el agente (Claude Code / Codex) para que tome el MCP."
Write-Host "  3. Probar: curl.exe http://127.0.0.1:$Puerto/ping  ->  {""ok"":true,...}"
exit 0
