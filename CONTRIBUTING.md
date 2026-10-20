# Contributing

Thanks for helping. Bug reports, ideas and pull requests are all welcome: open an
[issue](https://github.com/IvI2511/pentaho-mcp/issues) first for anything big, so we can agree on the
approach.

Open pull requests against `main`. Code comments and `docs/api-spoon.md` are in Spanish; the skill,
the user-facing docs, the plugin listing and everything the MCP tools expose to the AI (tool and
argument names, response keys, descriptions, error messages) are in English. Write docs and comments in
whichever you prefer, but name new tools and arguments in English.

## Repository layout

| Path | What |
|---|---|
| `spoon-plugin/` | The Spoon plugin (Java 8 bytecode): MCP server and tools |
| `claude/` | The Claude Code plugin `pentaho-live`: manifest, MCP connection, skill `pentaho-etl` |
| `.claude-plugin/marketplace.json` | The `pentaho-tools` marketplace that publishes `claude/` |
| `tools/generador-catalogo/` | Generates the step and job entry catalog from a PDI install |
| `instalar.ps1`, `instalar.sh` | Installers (see [`INSTALL.md`](INSTALL.md)) |
| `docs/api-spoon.md` | Internal design: Spoon API, threading, dialogs to avoid |

## Build the Spoon plugin

You need a JDK (with `javac` and `jar`; set `JAVA_HOME` if it isn't found) and a PDI 9.4 install to
compile against.

```powershell
powershell -ExecutionPolicy Bypass -File spoon-plugin\build.ps1 -PdiHome "C:\path\to\data-integration"
```

It leaves `spoon-plugin/dist/pentaho-mcp/` (copy it into `data-integration/plugins/` and restart Spoon)
plus the release zips `pentaho-mcp-<version>.zip` and `pentaho-mcp.zip`. `spoon-plugin\build.bat` takes
the same parameters. The version comes from `-Version` (default in `build.ps1`); keep it in sync with
`VERSION` in `McpLifecycleListener.java`.

Test live: open Spoon, check `curl http://127.0.0.1:8765/ping`, and try the change from Claude Code.
Before touching how the plugin talks to Spoon, read [`docs/api-spoon.md`](docs/api-spoon.md): every
edit must run on the UI thread and some Spoon calls open modal dialogs.

## Validate the Claude Code plugin

```bash
claude plugin validate --strict claude
claude plugin validate --strict .
```

**Any change inside `claude/` bumps `version` in `claude/.claude-plugin/plugin.json`**, otherwise
installed copies don't pick up the change. The plugin is submitted to Anthropic's plugin directory, so keep its
`README.md` accurate and in English.

The skill's runnable examples live in `claude/skills/pentaho-etl/manual/ejemplos/`; run them with
`correr.ps1` (it uses Pan / Kitchen from `-PdiHome` or `PDI_HOME`) after changing anything they depend on.

## Regenerate the step catalog

From Git Bash on Windows, against a PDI 9.4 install:

```bash
bash tools/generador-catalogo/generar.sh "C:/path/to/data-integration"
```

It compiles the generator, rewrites `claude/skills/pentaho-etl/catalogo/` (`steps.json`, `steps/`,
`job-entries.json`, `job-entries/`) and rebuilds `INDICE.md` with `indice.py`. It uses a throwaway
`KETTLE_HOME`, never your `~/.kettle`. Since it changes `claude/`, bump the plugin version.

## Pull requests

- Keep them focused; one topic per PR.
- Add a line to [`CHANGELOG.md`](CHANGELOG.md) under `[Unreleased]`.
- No personal paths, credentials or private data in code, examples or docs.
- By contributing you agree that your work is released under the [MIT license](LICENSE).
