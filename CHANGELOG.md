# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Planned as **0.4.0**.

### Added

- `generate_report`: a Markdown report of the open ETL, with a Mermaid diagram of its steps and hops.
- `canvas_screenshot`: an image of the Spoon canvas.
- `build_steps`: builds several steps in a single call, animated on the canvas.
- The step and job entry catalog is available as MCP resources.
- Plugin icon and [privacy policy](PRIVACY.md) for the Claude Code plugin listing.
- README in English, Spanish and Portuguese, this changelog, contributing guide and GitHub issue / pull
  request templates.

### Changed

- Tool names are now in English (for example `estado` → `status`).
- Each tool call is a single undo step: one Ctrl+Z in Spoon reverts everything a tool did.
- The skill manual no longer uses a `${DB_PASS}` example that read like a machine credential.

### Fixed

- `validate` reports each warning once. Kettle repeats the same warning (for example "field name with
  spaces") for every step the field goes through: a real transformation showed 62 warnings for 14
  distinct issues.

## [0.3.0] - 2026-10-20

### Added

- Output mode for steps with several outputs: new `modo_salida` tool and option on `conectar` /
  `agregar_step`.
- `validar` warns about steps that distribute rows among their outputs.

### Changed

- **Steps with several outputs now copy rows to every output by default.** Before, they distributed rows
  round-robin and each output got only part of them.
- `ordenar_lienzo` no longer leaves crossed lines: the longest branch stays straight and side branches go
  below.
- The Claude Code plugin is now **`pentaho-live`** (was `pentaho`):
  `/plugin install pentaho-live@pentaho-tools`. It lives in `claude/`, with an English listing and README.
- The MCP address is configurable with the `mcp_url` plugin option (default
  `http://127.0.0.1:8765/mcp`).
- The Spoon plugin sources moved to `spoon-plugin/`. **Updating:** delete the old
  `plugins/pentaho-mcp/` folder before unzipping, because the jar name changed.

## [0.2.0] - 2026-10-20

### Added

- `instalar.ps1` (Windows) and `instalar.sh` (macOS / Linux): find PDI or download it, install the Spoon
  plugin and configure Claude Code and Codex. They print `== Paso N` per stage and use exit codes an
  agent can act on.
- `INSTALL.md`, written so an agent asked to "install IvI2511/pentaho-mcp" can follow it end to end.
- Release asset `pentaho-mcp.zip` with a fixed name, for `/releases/latest/download`.
- Release asset `pdi-ce-9.4-con-pentaho-mcp.zip`: Pentaho Data Integration 9.4.0.0-343 Community Edition
  (Apache 2.0, Hitachi Vantara), unchanged except for `plugins/pentaho-mcp/` already in place.

### Notes

- Tested with PDI 9.4 CE on Java 21 on Windows. The macOS / Linux installer is not tested yet.

## [0.1.0] - 2026-10-20

### Added

- Spoon plugin: a Kettle `@LifecyclePlugin` that starts an MCP server on `127.0.0.1:8765` with about 50
  tools: documents, steps and hops, jobs, fields, validation, preview, run, database connections and
  parameters. Tested live in Spoon 9.4.
- Skill `pentaho-etl`: `.ktr` / `.kjb` format, common errors verified with Pan / Kitchen, runnable
  examples and a catalog of 216 steps and 77 job entries generated from a real install.
- The repository is a Claude Code plugin and marketplace (`pentaho-tools`); the plugin was installed as
  `pentaho@pentaho-tools`.

### Notes

- Tested with PDI 9.4.0.0-343 CE on Java 21 (Windows).

[Unreleased]: https://github.com/IvI2511/pentaho-mcp/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/IvI2511/pentaho-mcp/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/IvI2511/pentaho-mcp/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/IvI2511/pentaho-mcp/releases/tag/v0.1.0
