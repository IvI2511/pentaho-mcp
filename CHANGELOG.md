# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Reports perspective in Spoon** (*Reports* / *Informes* / *Relatórios*, in the perspective switcher):
  builds the report of an open transformation or job, or of a `.ktr` / `.kjb` file without opening a tab.
  Sections: cover, summary, flow (the real canvas plus a Mermaid diagram), job flow, field lineage, data
  dictionary, target table model (optionally reading primary and foreign keys from the database), data
  profile, run metrics with bar, Gantt and line charts, validation, connections and parameters (never
  passwords), and conclusions. Every section has an automatic summary in English, Spanish or Portuguese;
  no AI is needed.
- Export to **HTML** (one self-contained offline file, interactive diagrams), **light HTML** (no
  JavaScript), **PDF** (through Edge or Chrome in headless mode), **Markdown** (a `.md` plus a folder
  with the images, for Obsidian and GitHub) and **PowerPoint** (16:9, with speaker notes).
- Report identity: author, institution, logo and colors, saved in
  `<KETTLE_HOME>/.kettle/pentaho-live/informes.json`. One-line footer "Pentaho Live · IDC Studios" and a
  faint IDC symbol watermark that can be turned off.
- MCP tools:
  - `build_report`: builds the report and writes it in one or more formats (`html`, `html_light`, `pdf`,
    `md`, `pptx`, `json`). Returns the files, the status of each section and the warnings. It does not
    switch tabs or perspectives. The data profile (`profile_step`) and `metrics=run_copy` run a copy of
    the transformation, only when asked.
  - `get_report_data`: the report data as JSON, without images, so the AI can write a narrative based on
    the real numbers.
  - `show_report`: opens the Reports perspective with the report of a document, only when the user asks.
  - The AI narrative (`narrative`: summary, text per section and conclusions, up to 2,000 characters
    each) is shown with a discreet "written with AI assistance" label that can be turned off.
- Skill guide `manual/04-informes.md`: how to build reports and write the narrative. MCP clients without
  the skill read it as the resource `pentaho://guide/reports`.
- A report never creates an empty SQLite, H2 or HSQLDB file: when a step uses a database file that does
  not exist, the fields, lineage and validation of the steps that depend on it are skipped and the
  section says why.
- `PENTAHO_LIVE_REPORTS=off` (system property or a line in `kettle.properties`) hides the perspective
  without uninstalling the MCP server.

### Changed

- `status` and every tool without `document` work while another perspective is in front (Reports,
  Schedule…): they read the selected Data Integration tab directly. Before, they answered "there is no
  active transformation". `new_transformation`, `new_job` and `open_file` bring Data Integration to the
  front first, like the editing tools.
- The Spoon plugin zip grows from ~0.4 MB to ~11 MB: it now carries Apache POI 4.1.1 for the PowerPoint
  export (Apache-2.0; see `THIRD-PARTY-NOTICES.md`).
- `generate_report` and `canvas_screenshot` are unchanged.
- At startup the plugin checks that the MCP server and the Reports perspective share one class loader,
  and logs a clear error if they don't (typically, the jar installed in two plugin folders).

### Fixed

- `test_connection` no longer warns that an existing SQLite or H2 file "did not exist" when its path is
  a `file:///…%20…` URI, as `${Internal.Entry.Current.Directory}` gives.

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

[Unreleased]: https://github.com/IvI2511/pentaho-mcp/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/IvI2511/pentaho-mcp/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/IvI2511/pentaho-mcp/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/IvI2511/pentaho-mcp/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/IvI2511/pentaho-mcp/releases/tag/v0.1.0
