# Pentaho Live (unofficial)

> **Are you an agent (Claude Code, Codex…) asked to install this?** Follow [`INSTALL.md`](INSTALL.md).

[Español](README.es.md)

Ask Claude to build ETLs in **Pentaho Data Integration 9.4 (Spoon)** while you watch: it adds steps,
connects and configures them, validates, previews rows and runs the transformation in the Spoon window
you have open. Every change can be undone with Ctrl+Z.

Independent community project, not affiliated with or endorsed by Hitachi Vantara. Pentaho is a
trademark of Hitachi Vantara.

## Two pieces

| Piece | What it is | Where |
|---|---|---|
| **Spoon plugin** | A Kettle `@LifecyclePlugin` that starts an MCP server on `127.0.0.1:8765` (~50 tools) when Spoon opens | [`spoon-plugin/`](spoon-plugin/) (Java 8, built against a PDI install) |
| **Claude Code plugin `pentaho-live`** | The `pentaho-etl` skill (.ktr/.kjb format, catalog of 216 steps and 77 job entries generated from a real install, verified errors, runnable examples) and the MCP connection | [`claude/`](claude/), published through this repo's marketplace `pentaho-tools` |

The skill also works alone: without Spoon open, Claude writes the `.ktr` and checks it with Pan.

## Install

**Easiest: ask your agent.** In Claude Code or Codex type:

> install IvI2511/pentaho-mcp

It follows [`INSTALL.md`](INSTALL.md): asks whether you already have Pentaho, downloads what's missing,
installs the Spoon plugin and configures itself.

| If you… | Download | Script |
|---|---|---|
| **already have PDI** | `pentaho-mcp.zip` (the Spoon plugin, ~140 KB), unzip into `data-integration/plugins/` | `instalar.ps1 -PdiHome <data-integration>` |
| **don't have PDI** | `pdi-ce-9.4-con-pentaho-mcp.zip` (PDI 9.4 CE with the plugin in place, ~370 MB) | `instalar.ps1 -InstalarPdi` |

Both are in the [latest release](https://github.com/IvI2511/pentaho-mcp/releases/latest). Manual
steps, macOS/Linux (`instalar.sh`) and troubleshooting: [`INSTALL.md`](INSTALL.md).

Claude Code by hand:
```
/plugin marketplace add IvI2511/pentaho-mcp
/plugin install pentaho-live@pentaho-tools
```

MySQL, MariaDB, SQL Server and Oracle drivers don't ship with PDI: copy the connector `.jar` into
`data-integration/lib/`. With MySQL Connector/J 8+ the regular MySQL connection type works.

## Development

| What | How |
|---|---|
| Build the Spoon plugin | `powershell -ExecutionPolicy Bypass -File spoon-plugin\build.ps1 -PdiHome <data-integration>` → `spoon-plugin/dist/` (folder + release zips) |
| Regenerate the step catalog | `bash tools/generador-catalogo/generar.sh <data-integration>` |
| Validate the Claude plugin | `claude plugin validate --strict claude` and `claude plugin validate --strict .` |
| Internal design (Spoon API, threads, dialogs to avoid) | [`docs/api-spoon.md`](docs/api-spoon.md) (Spanish) |

Quick check without Claude: `curl http://127.0.0.1:8765/ping`.

## License

MIT.
