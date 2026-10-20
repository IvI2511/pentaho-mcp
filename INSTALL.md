# Installing Pentaho Live — guide for agents (Claude Code, Codex…) and people

> **Agent:** if the user asked you to "install IvI2511/pentaho-mcp" (or similar), follow this guide end
> to end. Talk to the user in their language. Explain each step in one line before doing it. Run the
> commands yourself if you have permission; otherwise give them to the user to paste. Never ask for
> passwords or tokens.

## What gets installed

| Piece | Purpose | Where it ends up |
|---|---|---|
| **Spoon plugin** (`pentaho-mcp.zip`) | When Spoon opens, it starts the MCP server at `http://127.0.0.1:8765/mcp`: the agent edits the open transformation and the user watches the nodes appear | `data-integration/plugins/pentaho-mcp/` |
| **Claude Code plugin** (marketplace `pentaho-tools`, plugin `pentaho-live`) | The `pentaho-etl` skill (.ktr/.kjb format, 216-step catalog) + the MCP connection | managed by Claude Code |
| **Codex** (optional) | The MCP by URL + the same skill | `~/.codex/config.toml`, `~/.codex/skills/pentaho-etl/` |

Requirements: **Java 11 or later** (tested with 21) and, without PDI, ~450 MB of disk.

## Step 0 — Ask the user (one question)

**"Do you already have Pentaho Data Integration (Spoon) installed? If so, where is the `data-integration` folder?"**

- **Yes, and knows where** → Option A with `-PdiHome`.
- **Yes, but doesn't know where** → Option A without a path (the script searches).
- **No** → Option B (downloads PDI 9.4 CE with the plugin already in place).

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

## What the script returns (for the agent)

It prints `== Paso N - ...` for each stage and exits with a code:

| Code | Meaning | What to do |
|---|---|---|
| 0 | Done | Go to "After installing" |
| 2 | Several PDI installs found (it lists them) | Ask which one and rerun with `-PdiHome` / `--pdi` |
| 3 | No PDI found | Ask for the path, or use Option B |
| 4 | Spoon is open and locks the plugin | Ask the user to close Spoon and rerun |
| 1 | Other error (the message says which) | Show it to the user |

Step 4 configures only the agents it finds on the PATH. In the Claude desktop app (no CLI), the plugin
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

1. The user opens Spoon (`Spoon.bat` on Windows, `spoon.sh` on macOS/Linux).
2. Restart the agent (or `/reload-plugins` in Claude Code) so it connects to the MCP.
3. Check: `curl http://127.0.0.1:8765/ping` → `{"ok":true,"spoon":true,"tools":50}`, or call the
   `estado` tool of the `pentaho` MCP.
4. Suggest a first try: *"build a transformation that reads a CSV, filters it and writes the result"*.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| The `pentaho` MCP says "connection refused" | Spoon is closed, or the plugin isn't in `plugins/` | Open Spoon; check `plugins/pentaho-mcp/` |
| `ping` answers but the agent sees no tools | The agent connected before Spoon was open | Restart the agent |
| Port 8765 is taken | Another program uses it | Script with `-Puerto 8770` (sets `PENTAHO_MCP_PORT` for Spoon); in Claude Code set the plugin option `mcp_url` to `http://127.0.0.1:8770/mcp` in `/config` |
| Spoon won't open | No Java | Install Java 11+ (`winget install EclipseAdoptium.Temurin.21.JRE`) |
| Driver error connecting to MySQL / SQL Server / Oracle | PDI doesn't ship those drivers | Copy the connector `.jar` into `data-integration/lib/` and restart Spoon (MySQL: `mysql-connector-j-*.jar`, regular MySQL connection type) |
| ChatGPT (web) can't connect | ChatGPT only uses public HTTPS MCP servers; this one listens on `127.0.0.1` on purpose | Use Claude Code or Codex on the same computer |
