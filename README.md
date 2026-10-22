<p align="center">
  <img src="docs/images/pentaho-live-icono.svg" alt="" width="88"><br><br>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/pentaho-live-oscuro.svg">
    <img src="docs/images/pentaho-live-claro.svg" alt="IDC Studios | Pentaho Live" height="34">
  </picture>
</p>

<p align="center">
  <strong>Claude builds your Pentaho Data Integration (Spoon) ETLs live, while you watch.</strong><br>
  A Spoon plugin that runs a local MCP server, plus a Claude Code plugin with the skill to use it.
</p>

<p align="center">
  <a href="https://github.com/IvI2511/pentaho-mcp/releases/latest"><img src="https://img.shields.io/github/v/release/IvI2511/pentaho-mcp?label=release&color=22d3a6" alt="Latest release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-4f8cff" alt="MIT license"></a>
  <a href="#install"><img src="https://img.shields.io/badge/Claude%20Code-plugin-2c3542?logo=claude&logoColor=white" alt="Claude Code plugin"></a>
  <img src="https://img.shields.io/badge/PDI-9.4%20CE-3f74d8" alt="PDI 9.4 CE">
  <img src="https://img.shields.io/badge/MCP-local%20HTTP-1c9a80" alt="MCP">
</p>

<p align="center">
  <strong>English</strong> · <a href="README.es.md">Español</a> · <a href="README.pt.md">Português</a>
</p>

> [!TIP]
> **Are you an agent (Claude Code, Codex…) asked to install this?** Follow [`INSTALL.md`](INSTALL.md).

<!-- DEMO GIF: replace this comment and the line below with
<p align="center"><img src="docs/images/demo.gif" alt="Claude building a transformation live in Spoon" width="860"></p>
-->
<p align="center"><em>Demo GIF coming soon: Claude building a transformation while each step appears in Spoon.</em></p>

## What it does

Open Spoon, ask Claude for an ETL in plain words, and watch it happen on the canvas: Claude adds the
steps, connects and configures them, checks the fields each one outputs, validates, previews real rows
and runs the transformation. Every change goes through Spoon's own undo, so **Ctrl+Z** always works.

**Reports.** Spoon gets a new **Reports** perspective that documents the open transformation or job by
itself: cover, flow diagram, field lineage, data dictionary, target table model, data profile, run
metrics with charts, validation, technical quality, connections and parameters. It exports HTML, PDF,
Markdown (for Obsidian or GitHub), PowerPoint, Word, Excel and images of every diagram, one document or
several combined into one file, with your name, institution, logo and colors. Claude can build the same
report for you and add a short narrative written from the real numbers.

**Quality.** Claude can also review an ETL for what Kettle lets through without an error: Group by or
Merge join without sorting, the empty decimal symbol that turns `2.50` into `250` on a computer set to
Spanish, hardcoded paths, passwords stored in the file, a failure branch that ends in Success. You get a
0-100 technical quality score that shows where every lost point comes from, and the safe fixes are applied
as one Ctrl+Z (Claude asks first about the ones that change what the ETL does). The same check is a section
of the report.

**Grading.** For teachers: build an exercise pack from your solution (a visible case and a hidden one with
other data, the expected output, rules and a rubric) and grade a whole folder of submissions. Each one runs in
a separate JVM, never inside Spoon, and its output is compared row by row with the expected one: you get a
grade with the failed checks, a hint for each typical mistake (unsorted Group by, empty decimal symbol,
duplicates left in, hardcoded paths), feedback for every student without the hidden case's values, and a
gradebook for Excel. Spoon gets a **Grader** perspective with the same, plus a wizard that builds the pack
from the open transformation.

**Class mode.** Claude can also *teach* an ETL: it builds the transformation slowly, one step per click
on **Next ▶** in a new **Lesson** tab, and explains every step with a numbered note on the canvas (what it
does, why it is there, the typical mistake), with the first rows after each step. Made for a projected
class or for learning alone; afterwards the notes can be hidden for the exercise and the lesson becomes a
handout in PDF or Word. Three tested lessons (filter, Sort + Group by, Stream lookup vs Merge join) come
with the skill in English, Spanish and Portuguese.

No Spoon open? The skill still helps: Claude writes the `.ktr` / `.kjb` file and checks it with Pan or
Kitchen.

## What you can ask

| You say… | Claude… |
|---|---|
| *"Build a transformation that reads `ventas.csv`, adds the city from `clientes.csv`, sums the amount per city and writes a CSV."* | creates it step by step on the canvas, validates it and previews the result |
| *"Add a Filter rows after the CSV input that keeps rows with saldo > 1000 and preview the result."* | inserts the step in the open transformation and shows you the rows that pass |
| *"Connect to my PostgreSQL database and load this file into the `clientes` table."* | adds the connection (you type the password in Spoon), lists tables and columns, maps the fields |
| *"Make a job that runs these two transformations in order and stops if the first one fails."* | builds the job with its entries and success / failure hops |
| *"The canvas is a mess, tidy it up."* | lays out the steps left to right, with no crossed lines |
| *"Why does this `.ktr` fail in Pan?"* | reads it, finds the problem with the verified error list and fixes it |
| *"Check this transformation for bad practices before I hand it in, and fix what's safe."* | runs the quality check, explains each finding, applies the safe fixes in one Ctrl+Z and asks before the rest |
| *"Teach the Stream lookup lesson step by step, beginner level, in Spanish."* | builds it one step per click on Next ▶, with a numbered note on the canvas for every step and the rows it produces |
| *"Make a report of this transformation for my assignment, as PDF and PowerPoint, with a short conclusion."* | builds the report (lineage, data dictionary, metrics…), writes the conclusion from the real data and exports both files |
| *"Make a grading pack from this solution with a hidden case, then grade the submissions in `C:\TP3\entregas`."* | runs the solution to get the expected output, grades every submission in separate JVMs and tells you the grades, who failed what and why; the gradebook and each student's feedback are in the results folder |

## How it works

```mermaid
flowchart LR
    you(["You"])
    subgraph agent ["Claude Code"]
        claude["Claude"]
        skill["pentaho-etl skill<br/>.ktr/.kjb format, step catalog"]
    end
    subgraph pc ["Spoon (PDI 9.4) on your computer"]
        plugin["Pentaho Live plugin<br/>local MCP server"]
        canvas["Spoon canvas"]
        engine["Kettle engine<br/>validate, preview, run"]
    end
    you -- "asks" --> claude
    claude -. "reads" .-> skill
    claude <-- "MCP over HTTP<br/>127.0.0.1:8765" --> plugin
    plugin <--> canvas
    plugin <--> engine
    canvas -- "you watch every step appear" --> you
```

- The **Spoon plugin** is a Kettle lifecycle plugin. When Spoon opens, it starts an MCP server on
  `127.0.0.1:8765` with 66 tools, and applies every edit on Spoon's UI thread with undo support.
  The same jar adds the **Reports** and **Grader** perspectives to Spoon's perspective switcher.
- The **Claude Code plugin `pentaho-live`** connects Claude to that server and adds the `pentaho-etl`
  skill, so Claude knows the exact step ids and XML instead of guessing.

## Install

**Easiest: ask your agent.** In Claude Code or Codex, type:

> install IvI2511/pentaho-mcp

It follows [`INSTALL.md`](INSTALL.md): asks whether you already have Pentaho, downloads what's missing,
installs the Spoon plugin and configures itself.

| If you… | Download from the [latest release](https://github.com/IvI2511/pentaho-mcp/releases/latest) | Windows script |
|---|---|---|
| **already have PDI** | `pentaho-mcp.zip` (the Spoon plugin, ~11 MB with the PowerPoint library): unzip into `data-integration/plugins/` | `instalar.ps1 -PdiHome <data-integration>` |
| **don't have PDI** | `pdi-ce-9.4-con-pentaho-mcp.zip` (PDI 9.4 CE with the plugin already in place, ~370 MB) | `instalar.ps1 -InstalarPdi` |
| **prefer a Windows installer** | `Instalador_PentahoLive_X.Y.Z.exe`: PDI 9.4 CE + plugin + Java 21, or just the plugin into your PDI; Start menu shortcut and the `pentaho-live` command. Not signed yet: on the SmartScreen warning, **More info → Run anyway** ([details](INSTALL.md#option-c--windows-installer-exe)) | not needed |

Then open Spoon, restart Claude Code (or run `/reload-plugins`) and ask for your first ETL.

<details>
<summary><strong>Manual install and other agents</strong></summary>

**Claude Code plugin:**

```
/plugin marketplace add IvI2511/pentaho-mcp
/plugin install pentaho-live@pentaho-tools
```

In the Claude desktop app: **Settings → Plugins → Add → marketplace** `IvI2511/pentaho-mcp`, then
install **Pentaho Live** from **Discover**.

**Spoon plugin with the script, Windows (PowerShell):**

```powershell
Invoke-WebRequest -UseBasicParsing https://raw.githubusercontent.com/IvI2511/pentaho-mcp/main/instalar.ps1 -OutFile $env:TEMP\instalar-pentaho-mcp.ps1
powershell -ExecutionPolicy Bypass -File $env:TEMP\instalar-pentaho-mcp.ps1 -PdiHome "C:\path\to\data-integration"
```

Without `-PdiHome` the script searches the usual folders; `-InstalarPdi` downloads PDI 9.4 CE with the
plugin in place.

**macOS / Linux:**

```bash
curl -fsSL https://raw.githubusercontent.com/IvI2511/pentaho-mcp/main/instalar.sh -o /tmp/instalar-pentaho-mcp.sh
bash /tmp/instalar-pentaho-mcp.sh --pdi "/path/to/data-integration"   # or --instalar-pdi
```

**Codex:** `codex mcp add pentaho --url http://127.0.0.1:8765/mcp`, and copy
`claude/skills/pentaho-etl` to `~/.codex/skills/pentaho-etl`.

**Check it:** with Spoon open, `curl http://127.0.0.1:8765/ping` answers `{"ok":true,"spoon":true,...}`.

**Different port:** run the script with `-Puerto 8770` (it sets `PENTAHO_MCP_PORT` for Spoon) and set
the plugin option `mcp_url` to `http://127.0.0.1:8770/mcp` in `/config`.

Exit codes, troubleshooting and the full step list: [`INSTALL.md`](INSTALL.md).

</details>

## What's inside

| Piece | What it is | Where |
|---|---|---|
| **Spoon plugin** | Kettle `@LifecyclePlugin` that starts the MCP server when Spoon opens, and the Reports and Grader perspectives (`@SpoonPlugin`) in the same jar (Java 8 bytecode, built against PDI 9.4) | [`spoon-plugin/`](spoon-plugin/) |
| **Skill `pentaho-etl`** | `.ktr` / `.kjb` format, catalog of 216 steps and 77 job entries generated from a real install, an annotated guide to the most used steps, runnable examples and the errors Pan and Kitchen actually give | [`claude/skills/pentaho-etl/`](claude/skills/pentaho-etl/) |
| **MCP connection `pentaho`** | Points Claude Code at the Spoon plugin (address configurable with `mcp_url`) | [`claude/.mcp.json`](claude/.mcp.json) |
| **Installers** | Find or download PDI, install the Spoon plugin, configure Claude Code and Codex | [`instalar.ps1`](instalar.ps1), [`instalar.sh`](instalar.sh) |

<details>
<summary><strong>What the MCP tools cover</strong></summary>

| Area | What Claude can do |
|---|---|
| Documents | See what's open, create, open, save, switch and close transformations and jobs |
| Steps and hops | Find a step type and its default XML, add, configure, rename, move, delete and connect steps; enable or disable hops; choose whether a step copies or distributes rows |
| Jobs | Add, configure and connect job entries; set success / failure / unconditional hops |
| Fields | List the fields each step outputs, before configuring the next one |
| Checks | Validate, preview real rows from any step |
| Quality | Check good practices beyond validation with a 0-100 score (optionally reading the sources to prove a problem), apply the proposed fixes, silence a rule where it is deliberate |
| Class mode | Play a lesson step by step with notes on the canvas and a Lesson tab (guided, automatic or manual pace), follow it, go back, hide, show or remove its notes, and turn it into a handout |
| Running | Run a transformation or job, follow its status and logs, stop it |
| Databases | List connection types, add, test and delete connections, browse tables and columns, run SQL queries (read-only unless you ask for writes) |
| Parameters | List, set and delete parameters |
| Canvas | Add notes, select steps, tidy the layout, take a screenshot of the canvas |
| Grading | Build an exercise pack from a solution (cases, hidden cases, expected output, rules, rubric, a copy for students), validate it, grade one submission or a whole folder in separate JVMs, follow the job, get the gradebook and each student's feedback |
| Reports | Build the full report of a transformation or job (HTML, PDF, Markdown, PowerPoint, Word, Excel, images, JSON), alone or several documents combined, with lineage, data dictionary, metrics, technical quality and an optional narrative; read its data; open it in Spoon's Reports perspective; a quick Markdown report with a Mermaid diagram |

</details>

## Privacy and security

Everything runs on your computer. There are no servers, accounts, analytics or telemetry.

- The MCP connection only goes to `http://127.0.0.1:8765/mcp`. The Spoon plugin listens on the loopback
  interface only and rejects requests from web pages of other origins.
- Database passwords are masked before anything is returned to Claude. The recommended way is to type
  passwords yourself in Spoon.
- Files are written only where you ask Claude to save; transformations run only when you ask.
- Reports never include passwords. They can include host names, file paths and, in the data profile and the
  deep quality check, real values from your data (you can hide them in the report settings). The profile and the "Measure" option
  run a copy of the transformation only when you ask, and that copy writes its outputs like a normal run.
- The quality check (`lint_etl`) does not run the transformation. Its deep mode reads up to 5000 rows of
  your sources only when you ask, through previews limited to read-only steps, and its findings can quote
  values it read.
- The grader runs the solution and the students' submissions only when you ask, each one in a separate
  Java process with its own empty Kettle settings, on copies of the data; it never modifies a submission and
  writes results only to the folder you choose. Trust mode (letting dangerous steps run) exists only in
  Spoon's Grader perspective, never over MCP.
- What the tools return (steps, field names, logs, preview rows) becomes part of your conversation with
  Claude, like anything else you share.

Full policy: [`PRIVACY.md`](PRIVACY.md).

## Compatibility

| | Status |
|---|---|
| Pentaho Data Integration | **9.4 CE** (tested with 9.4.0.0-343) |
| Java | **11 or later** (tested with Java 21) |
| Windows | Tested |
| macOS | Tested on macOS 15 and 26 (GitHub Actions): Apple chip under Rosetta 2 with an x64 Java, and Intel. Install, Pan and every MCP call work; Spoon's window still needs a check on a real Mac. [Steps](INSTALL.md#macos-and-linux) |
| Linux | Tested on Ubuntu 24.04 and 26.04 (GitHub Actions); needs GTK 2 (`libgtk2.0-0t64`) |
| Agents | Claude Code (CLI and desktop app); Codex through the MCP URL and the skill |

## Limitations

- **ChatGPT on the web can't connect**: it only uses public HTTPS MCP servers, and this one listens on
  `127.0.0.1` on purpose. In claude.ai chat only the skill is useful; live editing needs an agent on the
  computer where Spoon runs.
- **No MySQL, MariaDB, SQL Server or Oracle drivers** ship with PDI (PostgreSQL, SQLite and H2 do). Copy the connector `.jar` into
  `data-integration/lib/` and restart Spoon (with MySQL Connector/J 8+ the regular MySQL connection type
  works).
- The bundled PDI 9.4 CE has no OSGi container (karaf), so the big-data and streaming steps that depend on
  it are missing. The 216 steps in the catalog are there.
- Open Spoon before starting the agent; if the agent started first, restart it so it sees the tools.
- Grader: running a submission is running code someone else wrote. The separate process and the step
  policy (code, network and file-deletion steps are blocked) reduce the risk, but it is **not a real
  sandbox**: for submissions from strangers, use a virtual machine. It grades transformations (`.ktr`);
  jobs are not graded yet.
- Reports: the PDF and the diagram images use **Microsoft Edge or Google Chrome** (Windows ships Edge).
  Without either, the PDF falls back to the browser's print dialog and PowerPoint shows the lineage and
  table diagrams as tables. The preview inside Spoon uses the old Internet Explorer engine that PDI
  bundles, so it has no interactive diagrams: **Open in browser** shows the full report.
- Class mode works on **transformations only** (not jobs yet), one lesson per transformation; beyond 12
  steps it warns and suggests splitting it. A lesson that previews rows needs the `.ktr` saved next to its data first.

## New in 0.5.1

The **Reports** perspective in Spoon and three MCP tools to go with it: `build_report` (writes the report
in any format), `get_report_data` (the report data as JSON, so Claude can write a narrative from real
numbers) and `show_report` (opens the report in Spoon). Reports export to HTML, PDF, Markdown, PowerPoint,
**Word**, **Excel** and **images** of every diagram; several documents (a job and its transformations, the
exercises of an assignment) can go in one file with a cover and a table of contents. Flow and lineage
diagrams wrap into rows so they fill a page or a slide. See [`CHANGELOG.md`](CHANGELOG.md).

Also new:

- **[`ejemplo/`](ejemplo/)**, a complete university-style ETL exercise («Grano de Oro», four coffee shops,
  dirty data in CSV, Excel and JSON loaded into a SQLite star schema). It shows the whole thing: the ETL
  built live on the Spoon canvas by the MCP ([GIF](ejemplo/capturas/construccion-en-vivo.gif)), checked to
  the exact number, and documented by the Reports tab ([PDF](ejemplo/informes/04_hechos_ventas.pdf)).
  In Spanish, with a [demo script](ejemplo/guion-demo.md).
- **Ready-made patterns** in the skill (`claude/skills/pentaho-etl/patrones/`): CSV to staging,
  deduplication, incremental load by date, SCD type 2 dimension and fact load with surrogate keys, each
  one tested with Kitchen.
- **Arrange canvas** button and right-click item on Spoon's transformation and job canvases (one Ctrl+Z).
- **`set_error_handling`** (58 tools): the error handling of a step, as in Spoon's "Define error
  handling...", so Claude can route rejects with a reason field.

## Development

| What | How |
|---|---|
| Build the Spoon plugin | `powershell -ExecutionPolicy Bypass -File spoon-plugin\build.ps1 -PdiHome <data-integration>` → `spoon-plugin/dist/` |
| Validate the Claude plugin | `claude plugin validate --strict claude` and `claude plugin validate --strict .` |
| Regenerate the step catalog | `bash tools/generador-catalogo/generar.sh <data-integration>` |
| Internal design (Spoon API, threads, dialogs to avoid) | [`docs/api-spoon.md`](docs/api-spoon.md) (Spanish) |

Contributions are welcome: see [`CONTRIBUTING.md`](CONTRIBUTING.md). The internal docs and code comments
are written in Spanish; the skill, the MCP tools, the plugin listing and the user docs are in English.

## License

[MIT](LICENSE) © IvI2511

---

<sub>Independent community project. Not affiliated with, endorsed by or supported by Hitachi Vantara.
Pentaho is a trademark of Hitachi Vantara. PDI 9.4 CE is distributed by Hitachi Vantara under the Apache
2.0 license.</sub>
