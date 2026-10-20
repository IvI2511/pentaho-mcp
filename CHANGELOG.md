# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.4.2] - 2026-10-20

### Added

- Windows installer: the **Pentaho Live** shortcuts open a splash screen right away and keep it until
  Spoon's window appears (it takes 30-60 seconds), instead of a minimized console and nothing on screen.
  It is the launcher of the IDC Studios apps (Esc closes it); it ships compiled and is not under the
  MIT license (see `instalador/launcher/NOTICE.md`). PDI's own splash screen is hidden only when
  Spoon is opened this way; `Spoon.bat` and Help → About still show it.
- `pentaho-live --launcher`, the mode the launcher uses: Spoon's plugin tells it when the window is up.

### Fixed

- Opening Pentaho Live no longer shows Windows' **Open File - Security Warning** ("unknown publisher")
  for `Spoon.bat` when the PDI came from a zip downloaded from the internet: `pentaho-live` now runs
  `Spoon.bat` through `cmd.exe`. Opening `Spoon.bat` by hand still shows it (see
  [INSTALL.md](INSTALL.md#troubleshooting) to remove it).
- Windows installer: the install folder can no longer be inside a PDI folder, or the folder that holds
  a PDI's `data-integration`, and the destination page says what goes there (for **Plugin only**, the
  `data-integration` folder is asked for on a later page). A 0.4.1 installed inside a PDI moves on update
  to the default folder, `%LOCALAPPDATA%\Programs\Pentaho Live`: the installer takes that folder off the
  PATH and deletes what it had put there (a full install's PDI and Java too, and the old plugin if this
  time it goes into another PDI or you choose Full), and leaves everything else in the PDI as it is.
- The `pdi-ce-9.4-con-pentaho-mcp.zip` of the release keeps the execute permissions, so `spoon.sh` and the
  macOS app run after unzipping (the 0.4.0 and 0.4.1 zips, built on Windows, lost them).
- The plugin zips (`pentaho-mcp.zip`, `pentaho-mcp-X.Y.Z.zip`) store paths with `/`: PowerShell's
  `Compress-Archive` wrote `\`, and on macOS/Linux `unzip` extracted them but exited with code 1, so
  `instalar.sh --pdi` stopped at step 2.

## [0.4.1] - 2026-10-20

### Added

- Windows installer, `Instalador_PentahoLive_X.Y.Z.exe` (Inno Setup, per user, no administrator rights),
  in English, Spanish and Portuguese. Two types: **Full** (PDI 9.4 CE with the plugin in place and Java 21,
  Eclipse Temurin, so no Java is needed on the computer) and **Plugin only**, into an existing PDI 9.4
  folder that it asks for. It adds Start menu (and optional desktop) shortcuts, asks to close Spoon before
  installing or uninstalling, and, if Claude Code or Codex are on the PATH, offers to configure them. Not
  code-signed yet: on the SmartScreen warning, **More info → Run anyway**. The zips stay in every release.
- `pentaho-live` command, installed by the Windows installer on the user PATH: opens Spoon and returns
  right away, and does nothing if Spoon is already open. An agent can use it to start Spoon when the MCP
  doesn't connect (asking the user first). `PENTAHO_LIVE_DRY_RUN=1` only prints what it would do.
- `instalar.ps1 -SoloAgentes`: configures only Claude Code / Codex, skipping PDI and the Spoon plugin.

### Changed

- The version lives in a single `VERSION` file. `spoon-plugin/build.ps1` reads it (its `-Version`
  parameter is gone), stops if `claude/.claude-plugin/plugin.json` says another version, and writes it into
  the jar, where the Spoon plugin reads it.
- Author shown in the Claude Code plugin, the marketplace and the licence: **Iván Castaño · IDC Studios**.

### Fixed

- Line endings are stored as they are (`.gitattributes` with `-text`): batch files keep CRLF and the
  bash scripts (`instalar.sh`, the `pentaho-live` launcher) keep LF, so they run on macOS/Linux.

## [0.4.0] - 2026-10-20

### Added

- `generate_report`: a Markdown report of the open ETL, with a Mermaid diagram of its steps and hops.
- `canvas_screenshot`: an image of the Spoon canvas.
- `build_steps`: builds several steps in a single call, animated on the canvas.
- The step and job entry catalog is available as MCP resources.
- Plugin icon and [privacy policy](PRIVACY.md) for the Claude Code plugin listing.
- README in English, Spanish and Portuguese, this changelog, contributing guide and GitHub issue / pull
  request templates.

### Changed

- **Breaking: the whole MCP surface is now in English**: tool names, argument names, enum values,
  response keys, tool descriptions, error messages and the server instructions. Prompts, notes or
  scripts that call the old names must use the new ones. Renamed tools (`preview_step` keeps its name):
  - Documents: `estado` → `status`, `nueva_transformacion` → `new_transformation`, `nuevo_job` →
    `new_job`, `abrir_archivo` → `open_file`, `guardar` → `save`, `activar_documento` →
    `activate_document`, `cerrar_documento` → `close_document`, `ver_documento` → `get_document`.
  - Steps and hops: `tipos_de_step` → `list_step_types`, `plantilla_step` → `get_step_template`,
    `agregar_step` → `add_step`, `configurar_step` → `configure_step`, `ver_step` → `get_step`,
    `renombrar_step` → `rename_step`, `mover_step` → `move_step`, `borrar_step` → `delete_step`,
    `conectar` → `connect_steps`, `desconectar` → `disconnect_steps`, `habilitar_hop` →
    `set_hop_enabled`, `modo_salida` → `set_output_mode`, `campos_step` → `get_step_fields`.
  - Canvas: `agregar_nota` → `add_note`, `seleccionar` → `select_steps`, `ordenar_lienzo` →
    `arrange_canvas`.
  - Jobs: `tipos_de_entrada` → `list_entry_types`, `plantilla_entrada` → `get_entry_template`,
    `agregar_entrada` → `add_entry`, `configurar_entrada` → `configure_entry`, `ver_entrada` →
    `get_entry`, `renombrar_entrada` → `rename_entry`, `mover_entrada` → `move_entry`,
    `borrar_entrada` → `delete_entry`, `conectar_entradas` → `connect_entries`,
    `desconectar_entradas` → `disconnect_entries`, `cambiar_condicion` → `set_hop_condition`.
  - Checks and runs: `validar` → `validate`, `ejecutar` → `run`, `estado_ejecucion` →
    `get_run_status`, `detener` → `stop_run`.
  - Databases: `tipos_de_base` → `list_database_types`, `listar_conexiones` → `list_connections`,
    `agregar_conexion` → `add_connection`, `probar_conexion` → `test_connection`, `borrar_conexion` →
    `delete_connection`, `tablas` → `list_tables`, `columnas` → `list_columns`, `consultar_sql` →
    `run_sql`.
  - Parameters: `parametros` → `list_parameters`, `definir_parametro` → `set_parameter`,
    `borrar_parametro` → `delete_parameter`.
  - Arguments and values follow the same idea, for example `documento` → `document`, `despues_de` →
    `after`, `nombre` → `name`, `tipo` → `type`, `desde` / `hacia` → `from` / `to`, `condicion` →
    `condition` (`siempre` / `si_ok` / `si_falla` → `always` / `on_success` / `on_failure`), output mode
    `copiar` / `repartir` → `copy` / `distribute`, run mode `visible` / `segundo_plano` → `visible` /
    `background`. Transformations are reported as `transformation`.
  - A few responses changed shape, not just names: `add_step` and `connect_steps` report the output mode
    of a step with several outputs as `output_mode_of: {step, output_mode}` (was a
    `modo_salida_de_<step>` key); `add_note` returns the note position as `index` (was `nota`);
    `get_step` and `get_entry` both list hops as `incoming_hops` / `outgoing_hops`; the matches of
    `list_step_types` and `list_entry_types` both come under `types`.
  - `get_step_fields` (`side`) and `run` (`mode`) now reject unknown values. Before, anything other than
    `entrada` meant output and anything other than `segundo_plano` meant visible.
- The `pentaho-etl` skill is entirely in English, `SKILL.md` included (file names unchanged); it covers
  `build_steps`, `generate_report`, `canvas_screenshot` and the MCP resources.
- The installers print their messages in English; the stage marker is now `== Step N - ...` (was
  `== Paso N - ...`). Parameters (`-PdiHome`, `-InstalarPdi`, `--instalar-pdi`…) and exit codes are
  unchanged.
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

[Unreleased]: https://github.com/IvI2511/pentaho-mcp/compare/v0.4.2...HEAD
[0.4.2]: https://github.com/IvI2511/pentaho-mcp/compare/v0.4.1...v0.4.2
[0.4.1]: https://github.com/IvI2511/pentaho-mcp/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/IvI2511/pentaho-mcp/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/IvI2511/pentaho-mcp/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/IvI2511/pentaho-mcp/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/IvI2511/pentaho-mcp/releases/tag/v0.1.0
