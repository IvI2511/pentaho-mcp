# Installing Pentaho Live — guide for agents (Claude Code, Codex…) and people

> **Agent:** if the user asked you to "install IvI2511/pentaho-mcp" (or similar), follow this guide end
> to end. Talk to the user in their language. Explain each step in one line before doing it. Run the
> commands yourself if you have permission; otherwise give them to the user to paste. Never ask for
> passwords or tokens.

## What gets installed

| Piece | Purpose | Where it ends up |
|---|---|---|
| **Spoon plugin** (`pentaho-mcp.zip`) | When Spoon opens, it starts the MCP server at `http://127.0.0.1:8765/mcp`: the agent edits the open transformation and the user watches the nodes appear. It also adds the **Reports** perspective (HTML, PDF, Markdown and PowerPoint reports) and the **Grader** perspective (exercise grading) | `data-integration/plugins/pentaho-mcp/` (only there: Spoon does not look for perspectives in `~/.kettle/plugins`) |
| **Claude Code plugin** (marketplace `pentaho-tools`, plugin `pentaho-live`) | The `pentaho-etl` skill (.ktr/.kjb format, 216-step catalog) + the MCP connection | managed by Claude Code |
| **Codex** (optional) | The MCP by URL + the same skill | `~/.codex/config.toml`, `~/.codex/skills/pentaho-etl/` |

Requirements: **Java 11 or later** (tested with 21) and, without PDI, ~450 MB of disk. On macOS with an
Apple chip (M1–M4), Spoon runs under Rosetta 2 with an x64 Java (see [macOS and Linux](#macos-and-linux)).

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

The destination folder (asked before the type) is Pentaho Live's own: the launcher, the `pentaho-live`
command and the uninstaller go there (and, in the full install, PDI and Java). Keep the default, or any
folder outside your PDI: the installer does not accept one inside a PDI, or the folder that holds your
`data-integration`. For **Plugin only**, your `data-integration` folder is asked for on a later page. A
0.4.1 that was installed inside a PDI moves to the default folder when you update: the installer takes
that folder off the PATH and deletes what it had put there (a full install's PDI and Java too, and the
old plugin if this time it goes into another PDI or you choose Full); anything else stays as it is.

It also creates a **Pentaho Live** shortcut in the Start menu (and, if you tick it, on the desktop). The
shortcut shows a splash screen at once and keeps it until Spoon's window appears (30-60 seconds); Esc
closes the splash, not Spoon. The installer puts the `pentaho-live` command on the user PATH, and, if it
finds Claude Code or Codex on the PATH, offers to configure them (it runs `instalar.ps1 -SoloAgentes`,
step 4 only). If Spoon is open, it asks you to close it first. Uninstall it from **Settings → Apps** (it
also takes `pentaho-live` off the PATH).

> **"Windows protected your PC"**: the installer is not code-signed yet, so SmartScreen may stop it.
> Click **More info → Run anyway**. If you'd rather not, the zips are still in every release: use
> Option A or B.

Agents (with the user's permission): `Instalador_PentahoLive_X.Y.Z.exe /VERYSILENT /SUPPRESSMSGBOXES`
does a full install; add `/TYPE=pluginonly /PDIHOME="C:\path\to\data-integration"` for plugin only, and
`/MERGETASKS="!claude,!codex"` to skip the Claude Code / Codex setup (on by default when the agent is on
the PATH) and keep the desktop shortcut as it is (on by default; an update keeps the previous choice).
`/TASKS=""` skips the setup and the desktop shortcut, and **on an update it deletes the desktop shortcut**
the user had. Run
it from PowerShell or cmd: **Git Bash rewrites `/VERYSILENT` as a path** (`C:/Program Files/Git/VERYSILENT`)
and the normal wizard opens and waits for clicks. From Git Bash, prefix the command with
`MSYS_NO_PATHCONV=1`. Silent uninstall: `unins000.exe /VERYSILENT` in the install folder.

## macOS and Linux

Tested on GitHub Actions (macOS 15 and 26 with an Apple chip, macOS 15 Intel, Ubuntu 24.04 and 26.04):
install, Pan, opening Spoon and every MCP call work. Details: [`docs/plataformas.md`](docs/plataformas.md).

**Mac with an Apple chip (M1–M4).** PDI 9.4 only ships the x86_64 graphics library for macOS, and
`spoon.sh` stops on arm64 with *"this Mac platform [arm64] is not yet supported"* (exit code 0, so check
the output). Spoon has to run under Rosetta 2 with an x64 Java. In step 3 the script offers to install
both (Rosetta 2 with `softwareupdate`, which accepts Apple's license, and Temurin 21 x64 into
`~/pentaho/java-x64`, checked with the SHA-256 from the Adoptium API) and does it only after an explicit
`y`. Then it leaves `spoon-rosetta.sh` next to `spoon.sh`, which opens Spoon that way; "Done. Next" prints it:
```bash
bash /tmp/instalar-pentaho-mcp.sh --instalar-pdi      # step 3 asks "Do it now? [y/N]": y
~/pentaho/data-integration/spoon-rosetta.sh
```
It only asks when it runs in a terminal. Without one (an agent, `curl | bash`) it installs nothing and says
so: rerun with `--rosetta` to install them without asking. By hand, the same thing is:
```bash
softwareupdate --install-rosetta --agree-to-license
mkdir -p ~/pentaho/java-x64
curl -fL https://api.adoptium.net/v3/binary/latest/21/ga/mac/x64/jdk/hotspot/normal/eclipse | tar -xz -C ~/pentaho/java-x64 --strip-components 1
PENTAHO_JAVA_HOME=~/pentaho/java-x64/Contents/Home arch -x86_64 /bin/sh ~/pentaho/data-integration/spoon.sh
```
An arm64 Java fails to load SWT (`incompatible architecture`). Double-clicking `Data Integration.app`
stops the same way; from Terminal it opens with
`open --arch x86_64 --env PENTAHO_JAVA_HOME=$HOME/pentaho/java-x64/Contents/Home ~/pentaho/data-integration/Data\ Integration.app`.

**Mac with an Intel chip.** Any 64-bit Java 11+, then `~/pentaho/data-integration/spoon.sh`.

**Linux.** PDI 9.4's SWT uses GTK 2: `sudo apt install libgtk2.0-0t64` (older releases: `libgtk2.0-0`).
At startup Spoon warns that `libwebkitgtk-1.0` is missing: click **Close**; only the built-in browser
(welcome page, help) is unavailable, and that package no longer exists in current Ubuntu.

**Releases up to 0.4.1:** the PDI zip lost its Unix permissions; `instalar.sh` gives them back to the
`.sh` scripts, `spoon.command` and the `.app`.

In GitHub's macOS VMs Spoon's main window opens blank (toolbar only) while the MCP keeps working; if
that happens on your Mac, please [open an issue](https://github.com/IvI2511/pentaho-mcp/issues).

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
and the plugin; `-SinClaude` / `-SinCodex` leave one out). On a Mac with an Apple chip, `instalar.sh --rosetta`
installs Rosetta 2 and an x64 Java without asking (without a terminal it never installs them on its own). In the Claude desktop app (no CLI), the plugin
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
3. Check: `curl http://127.0.0.1:8765/ping` → `{"ok":true,"spoon":true,"tools":73}`, or call the
   `status` tool of the `pentaho` MCP.
4. Suggest a first try: *"build a transformation that reads a CSV, filters it and writes the result"*.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| The `pentaho` MCP says "connection refused" | Spoon is closed, or the plugin isn't in `plugins/` | Open Spoon; check `plugins/pentaho-mcp/`. If Pentaho Live was installed with the Windows installer, the agent can start Spoon itself with `pentaho-live` (on the PATH; in Git Bash too): **ask the user first**, since it opens a window. It returns right away and does nothing if Spoon is already open; Spoon takes 30-60 s, then reconnect the MCP (`/mcp` in Claude Code) |
| `ping` answers but the agent sees no tools | The agent connected before Spoon was open | Restart the agent |
| Port 8765 is taken | Another program uses it | Script with `-Puerto 8770` (sets `PENTAHO_MCP_PORT` for Spoon); in Claude Code set the plugin option `mcp_url` to `http://127.0.0.1:8770/mcp` in `/config` |
| Spoon won't open | No Java | Install Java 11+ (`winget install EclipseAdoptium.Temurin.21.JRE`) |
| `spoon.sh` prints "this Mac platform [arm64] is not yet supported" | Mac with an Apple chip | Rosetta 2 + x64 Java + `arch -x86_64` (see [macOS and Linux](#macos-and-linux)) |
| Linux: Spoon won't open, GTK errors | PDI 9.4's SWT needs GTK 2 | `sudo apt install libgtk2.0-0t64` |
| Opening `Spoon.bat` shows **Open File - Security Warning** ("unknown publisher") | PDI was unzipped from a zip downloaded from the internet, and Windows marks those files | The Pentaho Live shortcut and `pentaho-live` don't trigger it. For `Spoon.bat` itself: before unzipping, right-click the zip → **Properties** → tick **Unblock**; already unzipped, in PowerShell `Get-ChildItem -Recurse "C:\path\to\data-integration" \| Unblock-File`, or **Properties** → **Unblock** on each `.bat` |
| Driver error connecting to MySQL / SQL Server / Oracle | PDI doesn't ship those drivers | Copy the connector `.jar` into `data-integration/lib/` and restart Spoon (MySQL: `mysql-connector-j-*.jar`, regular MySQL connection type) |
| Spoon shows no **Reports** perspective (or misbehaves after the update) | The plugin is outside `data-integration/plugins/`, or the perspective was turned off | Check the folder; `PENTAHO_LIVE_REPORTS=off` in `~/.kettle/kettle.properties` turns the perspective off without touching the MCP: remove that line to bring it back |
| Spoon shows no **Grader** perspective | Same as Reports, or it was turned off | `PENTAHO_LIVE_GRADER=off` in `~/.kettle/kettle.properties` hides it; the grading tools (`grade_exercise`, `grade_submissions`…) keep working |
| The PDF comes out as `<name>.imprimir.html` | No Edge or Chrome on the computer | Open that file and choose "Save as PDF" in the print dialog, or install Edge/Chrome |
| ChatGPT (web) can't connect | ChatGPT only uses public HTTPS MCP servers; this one listens on `127.0.0.1` on purpose | Use Claude Code or Codex on the same computer |
