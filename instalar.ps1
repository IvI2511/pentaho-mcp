<#
  Installs Pentaho MCP on Windows (PowerShell 5.1 or later).

  In order (each step prints "== Step N"):
    1. Finds Pentaho Data Integration (data-integration\Spoon.bat), or installs all of it with -InstalarPdi.
    2. Downloads the Spoon plugin from the latest release into data-integration\plugins\pentaho-mcp\.
    3. Checks that Java is there.
    4. Configures the agents it finds: Claude Code (marketplace plugin) and Codex (MCP + skill).
       With -SoloAgentes it does only this step (the Windows installer uses it).

  Usage:
    .\instalar.ps1                                  # finds PDI by itself
    .\instalar.ps1 -PdiHome "C:\pentaho\data-integration"
    .\instalar.ps1 -InstalarPdi [-Destino "C:\pentaho"]   # no PDI yet: downloads PDI 9.4 CE with the plugin in place
    .\instalar.ps1 -SinClaude -SinCodex             # only the Spoon plugin
    .\instalar.ps1 -SoloAgentes [-SinCodex]         # only step 4: Claude Code / Codex, no PDI or plugin
    .\instalar.ps1 -Puerto 8770                     # another port (sets PENTAHO_MCP_PORT for Spoon)

  Exit codes (for agents):
    0 = done
    2 = found several PDI installs: ask which one and rerun with -PdiHome
    3 = no PDI found: ask where it is (-PdiHome) or whether to install it (-InstalarPdi)
    4 = Spoon is open and locks the old plugin: ask the user to close it and rerun
    1 = other error (the message says which)
#>
param(
  [string]$PdiHome,
  [switch]$InstalarPdi,
  [string]$Destino = (Join-Path $env:USERPROFILE "Pentaho"),
  [switch]$SinClaude,
  [switch]$SinCodex,
  [switch]$SoloAgentes,
  [int]$Puerto = 8765
)

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$ProgressPreference = "SilentlyContinue"   # Invoke-WebRequest is very slow with the progress bar

$repo = "IvI2511/pentaho-mcp"
$base = "https://github.com/$repo/releases/latest/download"
$tmp = Join-Path $env:TEMP ("pentaho-mcp-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
New-Item -ItemType Directory -Force $tmp | Out-Null

function Paso($n, $texto) { Write-Host ""; Write-Host "== Step $n - $texto" }
function Bajar($url, $destino) {
  Write-Host "   downloading $url"
  Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $destino
}
function Salir($codigo, $mensaje) {
  Write-Host ""
  Write-Host $mensaje
  Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
  exit $codigo
}

# ------------------------------------------------------------------ 1-3. PDI, plugin and Java
if ($SoloAgentes) {
  Write-Host "-SoloAgentes: skipping steps 1-3 (PDI, Spoon plugin, Java); only the agents are configured."
} else {
# ------------------------------------------------------------------ 1. PDI
Paso 1 "Pentaho Data Integration"
if ($InstalarPdi) {
  $zip = Join-Path $tmp "pdi.zip"
  Write-Host "   PDI 9.4 CE with the plugin already installed (~420 MB), into $Destino"
  Bajar "$base/pdi-ce-9.4-con-pentaho-mcp.zip" $zip
  New-Item -ItemType Directory -Force $Destino | Out-Null
  Expand-Archive -Path $zip -DestinationPath $Destino -Force
  $PdiHome = Join-Path $Destino "data-integration"
} elseif (-not $PdiHome) {
  if ($env:PDI_HOME) {
    $PdiHome = $env:PDI_HOME
  } else {
    Write-Host "   searching for data-integration\Spoon.bat (this can take a while)..."
    # Folders only (much faster than listing files); no AppData, which is huge and never has PDI.
    $raices = @("C:\pentaho", "C:\Program Files", "C:\Program Files (x86)",
      (Join-Path $env:USERPROFILE "Downloads"), (Join-Path $env:USERPROFILE "Documents"),
      (Join-Path $env:USERPROFILE "Desktop"), $env:OneDrive) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -Unique
    $hallados = @()
    foreach ($r in $raices) {
      $hallados += Get-ChildItem -Path $r -Filter "data-integration" -Recurse -Depth 10 -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName "Spoon.bat") } | ForEach-Object { $_.FullName }
    }
    # And loose folders at the root of C:\ (C:\pdi-ce-9.4\data-integration...)
    $hallados += Get-ChildItem -Path "C:\" -Filter "data-integration" -Recurse -Depth 2 -Directory -ErrorAction SilentlyContinue |
      Where-Object { Test-Path (Join-Path $_.FullName "Spoon.bat") } | ForEach-Object { $_.FullName }
    $hallados = @($hallados | Select-Object -Unique)
    if ($hallados.Count -eq 0) {
      Salir 3 "Pentaho Data Integration not found. If you have it, rerun with -PdiHome <data-integration folder>; if not, with -InstalarPdi."
    }
    if ($hallados.Count -gt 1) {
      Salir 2 ("Found several PDI installs; rerun with -PdiHome and pick one:`n   " + ($hallados -join "`n   "))
    }
    $PdiHome = $hallados[0]
  }
}
$PdiHome = (Resolve-Path $PdiHome).Path
if (-not (Test-Path (Join-Path $PdiHome "Spoon.bat"))) {
  Salir 1 "$PdiHome does not look like a data-integration folder (it has no Spoon.bat)."
}
Write-Host "   PDI: $PdiHome"

# ------------------------------------------------------------------ 2. Spoon plugin
Paso 2 "Spoon plugin (plugins\pentaho-mcp)"
$plugins = Join-Path $PdiHome "plugins"
$destPlugin = Join-Path $plugins "pentaho-mcp"
if ($InstalarPdi) {
  Write-Host "   already included in the downloaded PDI"
} else {
  $zip = Join-Path $tmp "pentaho-mcp.zip"
  Bajar "$base/pentaho-mcp.zip" $zip
  Expand-Archive -Path $zip -DestinationPath $tmp -Force
  try {
    if (Test-Path $destPlugin) { Remove-Item -Recurse -Force $destPlugin }
    Copy-Item -Recurse (Join-Path $tmp "pentaho-mcp") $destPlugin
  } catch {
    Salir 4 "Could not replace $destPlugin : Spoon must be open and is locking the .jar. Close Spoon and run the installer again."
  }
  Write-Host "   installed in $destPlugin"
}

# ------------------------------------------------------------------ 3. Java
Paso 3 "Java"
$java = Get-Command java -ErrorAction SilentlyContinue
if ($java) {
  $v = (cmd /c "java -version 2>&1" | Select-Object -First 1)
  Write-Host "   $v"
} else {
  Write-Host "   NO Java on the PATH: Spoon will not open. Install it (11 or later), for example:"
  Write-Host "   winget install EclipseAdoptium.Temurin.21.JRE"
}
}   # end of steps 1-3 (skipped with -SoloAgentes)

# ------------------------------------------------------------------ 4. agents
Paso 4 "agents (Claude Code / Codex)"
$url = "http://127.0.0.1:$Puerto/mcp"
if ($Puerto -ne 8765) {
  [Environment]::SetEnvironmentVariable("PENTAHO_MCP_PORT", "$Puerto", "User")
  Write-Host "   PENTAHO_MCP_PORT=$Puerto saved in the user environment variables (Spoon reads it)"
  Write-Host "   In Claude Code, set the 'mcp_url' option of the pentaho-live plugin to $url (in /config)"
}

if (-not $SinClaude -and (Get-Command claude -ErrorAction SilentlyContinue)) {
  $lista = (cmd /c "claude plugin list 2>&1") | Out-String
  if ($lista -match "pentaho-live@pentaho-tools") {
    Write-Host "   Claude Code: the plugin was already installed (to update it: claude plugin update pentaho-live@pentaho-tools)"
  } else {
    cmd /c "claude plugin marketplace add $repo 2>&1" | ForEach-Object { "   $_" }
    cmd /c "claude plugin install pentaho-live@pentaho-tools --scope user 2>&1" | ForEach-Object { "   $_" }
  }
} elseif (-not $SinClaude) {
  Write-Host "   Claude Code is not on the PATH: skipped (in the desktop app: Settings > Plugins > Add > marketplace $repo)"
}

if (-not $SinCodex -and (Get-Command codex -ErrorAction SilentlyContinue)) {
  $mcps = (cmd /c "codex mcp list 2>&1") | Out-String
  if ($mcps -match "pentaho") {
    Write-Host "   Codex: the 'pentaho' MCP was already configured"
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
  Copy-Item -Recurse (Join-Path $tmp "pentaho-mcp-main\claude\skills\pentaho-etl") $skillDest
  Write-Host "   Codex: skill in $skillDest (if it does not show up, enable skills in config.toml and restart Codex)"
} elseif (-not $SinCodex) {
  Write-Host "   Codex is not on the PATH: skipped"
}

Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
Write-Host ""
Write-Host "Done. Next:"
if ($SoloAgentes) {
  Write-Host "  1. Open (or restart) Spoon (with the Windows installer: pentaho-live, or the Pentaho Live shortcut)"
} else {
  Write-Host "  1. Open (or restart) Spoon: $PdiHome\Spoon.bat"
}
Write-Host "  2. Restart the agent (Claude Code / Codex) so it picks up the MCP."
Write-Host "  3. Check: curl.exe http://127.0.0.1:$Puerto/ping  ->  {""ok"":true,...}"
exit 0
