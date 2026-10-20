# Installing Pentaho Live — guide for agents (Claude Code, Codex…) and people

> **Agent:** if the user asked you to "install IvI2511/pentaho-mcp" (or similar), follow this guide end
> to end. Talk to the user in their language. Explain each step in one line before doing it. Run the
> commands yourself if you have permission; otherwise give them to the user to paste. Never ask for
> passwords or tokens.

## What gets installed

| Piece | Purpose | Where it ends up |
|---|---|---|
| **Spoon plugin** (`pentaho-mcp.zip`) | When Spoon opens, it starts the MCP server at `http://127.0.0.1:8765/mcp`: the agent edits the open transformation and the user watches the nodes appear. It also adds the **Reports** perspective (HTML, PDF, Markdown and PowerPoint reports) | `data-integration/plugins/pentaho-mcp/` (only there: Spoon does not look for perspectives in `~/.kettle/plugins`) |
| **Claude Code plugin** (marketplace `pentaho-tools`, plugin `pentaho-live`) | The `pentaho-etl` skill (.ktr/.kjb format, 216-step catalog) + the MCP connection | managed by Claude Code |
| **Codex** (optional) | The MCP by URL + the same skill | `~/.codex/config.toml`, `~/.codex/skills/pentaho-etl/` |

Requirements: **Java 11 or later** (tested with 21) and, without PDI, ~450 MB of disk.

## Step 0 — Ask the user (one question)

**"Do you already have Pentaho Data Integration (Spoon) installed? If so, where is the `data-integration` folder?"**

- **Yes, and knows where** → Option A with `-PdiHome`.
- **Yes, but doesn't know where** → Option A without a path (the script searches).
- **No** → Option B (downloads PDI 9.4 CE with the plugin already in place).

On Windows, a person who prefers a regular installer can use Option C instead (it covers both cases).

If Spoon is open, ask them to close it first (Windows locks the plugin's `.jar`).

## Option A — Has PDI: add the plugin

**Windows (PowerShell):**
```powershell
Invoke-WebRequest -UseBasicParsing https://raw.githubusercontent.com/IvI2511/pentaho-mcp/main/instalar.ps1 -OutFile $env:TEMP\instalar-pentaho-mcp.ps1
powershell -ExecutionPolicy Bypass -File $env:TEMP\instalar-pentaho-mcp.ps1 -PdiHome "C:\path\to\data-integration"
```
(without `-PdiHome` it searches Downloads, Documents, Desktop, OneDrive, `C:\pentaho` and Program Files)

**macOS / Linux:**
```bash
curl -fsSL https://raw.githubusercontent.com/IvI2511/pentaho-mcp/main/instalar.sh -o /tmp/instalar-pentaho-mcp.sh
bash /tmp/instalar-pentaho-mcp.sh --pdi "/path/to/data-integration"
```

## Option B — No PDI: download PDI 9.4 CE with the plugin

**Windows:** the same script with `-InstalarPdi` (default target `%USERPROFILE%\Pentaho`; change it with `-Destino`):
```powershell
powershell -ExecutionPolicy Bypass -File $env:TEMP\instalar-pentaho-mcp.ps1 -InstalarPdi
```
**macOS / Linux:** `bash /tmp/instalar-pentaho-mcp.sh --instalar-pdi`

It is Pentaho Data Integration 9.4.0.0-343 Community Edition (Apache 2.0, by Hitachi Vantara),
unchanged except for `plugins/pentaho-mcp/`. It has no OSGi container (karaf), so the big-data and
streaming steps that depend on it are missing; the 216 steps in the catalog are there.

## Option C — Windows installer (.exe)

For people on Windows who prefer a regular installer. Download `Instalador_PentahoLive_X.Y.Z.exe` from
the [latest release](https://github.com/IvI2511/pentaho-mcp/releases/latest) and run it. It installs for
the current user (no administrator rights) and asks for one of two types:

| Type | What it installs |
|---|---|
| **Full** (default) | PDI 9.4 CE with the Spoon plugin in place, plus Java 21 (Eclipse Temurin), in `%LOCALAPPDATA%\Programs\Pentaho Live`: no Java needed on the computer |
| **Plugin only** | Just the Spoon plugin, into the `data-integration` folder of a PDI 9.4 you already have (it asks for the folder and replaces any older `plugins\pentaho-mcp`) |

It also creates a **Pentaho Live** shortcut in the Start menu (and, if you tick it, on the desktop),
puts the `pentaho-live` command on the user PATH, and, if it finds Claude Code or Codex on the PATH,
offers to configure them (it runs `instalar.ps1 -SoloAgentes`, step 4 only). If Spoon is open, it asks
you to close it first. Uninstall it from **Settings → Apps** (it also takes `pentaho-live` off the PATH).

> **"Windows protected your PC"**: the installer is not code-signed yet, so SmartScreen may stop it.
> Click **More info → Run anyway**. If you'd rather not, the zips are still in every release: use
> Option A or B.

Agents (with the user's permission): `Instalador_PentahoLive_X.Y.Z.exe /VERYSILENT /SUPPRESSMSGBOXES`
does a full install; add `/TYPE=pluginonly /PDIHOME="C:\path\to\data-integration"` for plugin only, and
`/TASKS=""` to skip the desktop shortcut and the Claude Code / Codex setup (both are on by default). Run
it from PowerShell or cmd: **Git Bash rewrites `/VERYSILENT` as a path** (`C:/Program Files/Git/VERYSILENT`)
and the normal wizard opens and waits for clicks. From Git Bash, prefix the command with
`MSYS_NO_PATHCONV=1`. Silent uninstall: `unins000.exe /VERYSILENT` in the install folder.

## What the script returns (for the agent)

It prints `== Step N - ...` for each stage and exits with a code:

| Code | Meaning | What to do |
|---|---|---|
| 0 | Done | Go to "After installing" |
| 2 | Several PDI installs found (it lists them) | Ask which one and rerun with `-PdiHome` / `--pdi` |
| 3 | No PDI found | Ask for the path, or use Option B |
| 4 | Spoon is open and locks the plugin | Ask the user to close Spoon and rerun |
| 1 | Other error (the message says which) | Show it to the user |

Step 4 configures only the agents it finds on the PATH (`-SoloAgentes` runs only that step, skipping PDI
and the plugin; `-SinClaude` / `-SinCodex` leave one out). In the Claude desktop app (no CLI), the plugin
is added from **Settings → Plugins → Add → marketplace** `IvI2511/pentaho-mcp`, then install
**Pentaho Live** from **Discover**.

## By hand (no script)

1. Download `pentaho-mcp.zip` from https://github.com/IvI2511/pentaho-mcp/releases/latest and unzip it
   inside `data-integration/plugins/` → you must end up with `plugins/pentaho-mcp/pentaho-mcp-core-*.jar`.
   (No PDI: download `pdi-ce-9.4-con-pentaho-mcp.zip` from the same release and unzip it anywhere.)
2. Claude Code:
   ```
   /plugin marketplace add IvI2511/pentaho-mcp
   /plugin install pentaho-live@pentaho-tools
   ```
3. Codex: `codex mcp add pentaho --url http://127.0.0.1:8765/mcp`, and copy the repo's
   `claude/skills/pentaho-etl` folder to `~/.codex/skills/pentaho-etl`.

## After installing

1. The user opens Spoon (`Spoon.bat` on Windows, `spoon.sh` on macOS/Linux; with the Windows installer,
   the **Pentaho Live** shortcut or the `pentaho-live` command).
2. Restart the agent (or `/reload-plugins` in Claude Code) so it connects to the MCP.
3. Check: `curl http://127.0.0.1:8765/ping` → `{"ok":true,"spoon":true,"tools":57}`, or call the
   `status` tool of the `pentaho` MCP.
4. Suggest a first try: *"build a transformation that reads a CSV, filters it and writes the result"*.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| The `pentaho` MCP says "connection refused" | Spoon is closed, or the plugin isn't in `plugins/` | Open Spoon; check `plugins/pentaho-mcp/`. If Pentaho Live was installed with the Windows installer, the agent can start Spoon itself with `pentaho-live` (on the PATH; in Git Bash too): **ask the user first**, since it opens a window. It returns right away and does nothing if Spoon is already open; Spoon takes 30-60 s, then reconnect the MCP (`/mcp` in Claude Code) |
| `ping` answers but the agent sees no tools | The agent connected before Spoon was open | Restart the agent |
| Port 8765 is taken | Another program uses it | Script with `-Puerto 8770` (sets `PENTAHO_MCP_PORT` for Spoon); in Claude Code set the plugin option `mcp_url` to `http://127.0.0.1:8770/mcp` in `/config` |
| Spoon won't open | No Java | Install Java 11+ (`winget install EclipseAdoptium.Temurin.21.JRE`) |
| Driver error connecting to MySQL / SQL Server / Oracle | PDI doesn't ship those drivers | Copy the connector `.jar` into `data-integration/lib/` and restart Spoon (MySQL: `mysql-connector-j-*.jar`, regular MySQL connection type) |
| Spoon shows no **Reports** perspective (or misbehaves after the update) | The plugin is outside `data-integration/plugins/`, or the perspective was turned off | Check the folder; `PENTAHO_LIVE_REPORTS=off` in `~/.kettle/kettle.properties` turns the perspective off without touching the MCP: remove that line to bring it back |
| The PDF comes out as `<name>.imprimir.html` | No Edge or Chrome on the computer | Open that file and choose "Save as PDF" in the print dialog, or install Edge/Chrome |
| ChatGPT (web) can't connect | ChatGPT only uses public HTTPS MCP servers; this one listens on `127.0.0.1` on purpose | Use Claude Code or Codex on the same computer |
