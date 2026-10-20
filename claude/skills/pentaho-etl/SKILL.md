---
name: pentaho-etl
description: Build, modify, review, document or debug Pentaho Data Integration 9.4 (Spoon/Kettle) ETLs — .ktr transformations and .kjb jobs. Use when the user asks to build a Pentaho/PDI/Kettle/Spoon transformation or job, add steps (CSV input, Filter rows, Group by, Stream lookup, Table output...), connect to a database from Pentaho, write a report of an ETL, or find out why a .ktr/.kjb fails. Works live through the "pentaho" MCP (Spoon open) or by writing the XML and checking it with Pan/Kitchen.
---

# Pentaho Data Integration 9.4 — ETLs

Everything in this folder is **verified against a real PDI 9.4.0.0-343 (CE) install**: the step ids
come from the `PluginRegistry`, the examples run with Pan/Kitchen and the errors were triggered on
purpose. Don't invent tags: look them up here.

## Two ways to work

**A. Live, with the `pentaho` MCP** (if its tools are available: `status`, `add_step`…). The user has
Spoon open and watches every node appear. This is the preferred way.

If the `pentaho` MCP is listed but fails to connect, Spoon is closed or the Spoon plugin is missing:
`pentaho-mcp.zip` from the latest release of https://github.com/IvI2511/pentaho-mcp, unzipped into
`data-integration/plugins/`, then restart Spoon (full guide: the repo's `INSTALL.md`). It listens on
`127.0.0.1:8765`; with another port (`PENTAHO_MCP_PORT` in Spoon) change the plugin option `mcp_url`
in `/config`. If Pentaho Live was installed with the Windows installer, `pentaho-live` (on the PATH)
starts Spoon and returns right away: ask the user first (it opens a window), wait 30-60 s, reconnect.

1. `status` → what is open (ids T1, J1…). `new_transformation` / `open_file` if needed. The editing
   tools take an optional `document` (id or name); without it they work on the active tab.
2. Build left to right. **A whole flow or a whole stretch** (input → transforms → output, branches,
   lookups): `build_steps` in **one call**. It checks everything before touching the canvas (if
   something is wrong nothing changes and you get the full list of problems), animates the nodes so
   the user watches them appear, arranges the canvas at the end and is a single Ctrl+Z. Per step:
   `type`, `name`, `config_xml`, `after`; `hops` for what `after` can't express (lookup stream, second
   input of a join, the other branch of Filter rows, error hops with `error=true`).
   **One step at a time** (a one-off change to an existing flow):
   - `list_step_types search=...` if you don't know the exact id (the `<type>` is NOT the name shown
     on screen).
   - `get_step_template` to see the tags; send in `add_step` **only the tags that change** (the rest
     keeps its default) and `after=<previous step>` so it is placed and connected automatically.
   - `get_step_fields` of the previous step **before** configuring the next one: that way you use the
     real column names, not the ones you assume.
3. `validate`, then `preview_step` on the last step to see real rows.
4. `canvas_screenshot` returns a PNG of the canvas: use it to check the drawing (overlapping steps,
   crossing hops). `arrange_canvas` if there are branches or lookups.
5. `save` with a `.ktr`/`.kjb` path. `run mode=visible` if the user wants to watch it run;
   `get_run_status` for status and log, `stop_run` to stop it.
6. If something goes wrong: `get_step`, `configure_step` (merges over the current config),
   `delete_step`. The user can undo any change of yours with Ctrl+Z (one Ctrl+Z per tool call).

Jobs: `new_job`, `add_entry type=START` first, then `TRANS`/`JOB`/`SUCCESS` entries with `after` and
`condition` (`always` | `on_success` | `on_failure`). `set_hop_condition` changes it later.

Databases: `list_database_types`, `add_connection` (better: the user types the password in Spoon),
`test_connection`, `list_tables` / `list_columns`, `run_sql` (read-only unless `allow_write=true`).

**Document the ETL:** `generate_report` writes a Markdown report with a Mermaid diagram of the steps
and hops, a table of what each step does, output fields, connections (never passwords), parameters
and notes. `language` = `en` | `es` | `pt`; with `path` it saves a `.md`, without it returns the text.
Use it when the user wants to hand in or document the ETL (assignment, Obsidian note, README).

**Clients without this skill** (another MCP client, Codex without the skill folder): the server also
exposes these guides and the catalog as MCP resources, `pentaho://guide/...` (the manuals) and
`pentaho://catalog/...` (index of step / entry ids and the default XML of each one). With the skill
loaded, read the local files below instead: same content.

**B. Writing files** (no MCP, or to generate many at once): write the `.ktr`/`.kjb` in UTF-8
starting from the recommended template in `manual/01-formato-ktr.md` §1.2 and check it with Pan or
Kitchen (see below). The user opens it in Spoon when you are done.

## Where everything is

| I need… | Read |
|---|---|
| Golden rules and the skeleton of a `.ktr` | `manual/01-formato-ktr.md` (start with §0, the checklist) |
| Jobs: START, TRANS, hops with a condition | `manual/02-formato-kjb.md` |
| A Pan/Kitchen error, or an odd result with exit 0 | `manual/03-errores-comunes.md` |
| The exact id of a step or entry, by category | `catalogo/INDICE.md` |
| How to configure a common step, with an example and its pitfalls | `catalogo/steps-comunes.md` (~45 steps) |
| The default XML of ANY step / entry | `catalogo/steps/<id>.xml`, `catalogo/job-entries/<id>.xml` |
| Complete examples that run | `manual/ejemplos/` (README with what each one does) |

## The pitfalls that cost the most (details in the manual)

- `<type>` is the **plugin id**: Add constants = `Constant`, Add sequence = `Sequence`,
  User defined Java expression = `Janino`, Excel writer = `TypeExitExcelWriterStep`. A job's START
  and Dummy are `<type>SPECIAL</type>`.
- Every `<step>` needs `<copies>1</copies>` and `<GUI>…<draw>Y</draw></GUI>`; without `copies` Pan
  fails without explanation, without `draw` the step is not shown in Spoon.
- **Unique** step names: a duplicate silently makes a step disappear.
- In the file format, a step with **2 or more outputs** distributes the rows among them by default
  (round robin, `<distribute>Y</distribute>`): each output gets only part. If every output must get
  all the rows, `<distribute>N</distribute>` (live: `set_output_mode mode=copy`, which is what the MCP
  sets by default when it creates the second output). Doesn't apply to Filter rows or Switch/case,
  which send rows to specific targets.
- **Group by** and **Merge join** need their input **sorted** by the key (Sort rows before);
  otherwise they give wrong results with no error.
- **Stream lookup** references the lookup step with `<from>` (there is no `<lookup_step>`) and also
  needs the hop from that step. Same for Filter rows (`send_true_to`/`send_false_to`) and
  Switch/case: the reference in the XML **and** the hop.
- Numbers: declare `<decimal>.</decimal>` explicitly; left empty with a Spanish locale, `2.50` is read
  as 250.
- In Generate rows and Add constants the value goes in `<nullif>`.
- Paths always relative, with `${Internal.Entry.Current.Directory}/…` (the file's own folder).
- Excel `.xlsx`: `<spreadsheet_type>POI</spreadsheet_type>` (the default, JXL, only reads `.xls`).
- Escape `&` and `<` in the XML (`&amp;`, `&lt;`).
- Bundled JDBC drivers: PostgreSQL, H2, SQLite, HSQLDB, Derby and others. MySQL, MariaDB, SQL Server
  and Oracle are **not included**: copy the connector `.jar` into `data-integration/lib/` and restart
  Spoon. With MySQL Connector/J 8 or later (`mysql-connector-j-*.jar`) the regular `MYSQL` type is
  enough: PDI 9.4 uses `com.mysql.cj.jdbc.Driver` when it is there (verified with
  `list_database_types`); no need for "Generic database".

## Verify with Pan / Kitchen (Windows, from PowerShell)

```powershell
Remove-Item Env:NoDefaultCurrentDirectoryInExePath -ErrorAction SilentlyContinue
& cmd.exe /c call '<data-integration>\Pan.bat' "/file:<path.ktr>" /level:Basic '"/param:X=1"'
# jobs: Kitchen.bat with the same arguments
```

- Without removing `NoDefaultCurrentDirectoryInExePath`, `Pan.bat` doesn't find `Spoon.bat`.
- With `& Pan.bat` directly, `$LASTEXITCODE` is 0 even when it fails: use `cmd /c call`.
- `/param` goes in double quotes, otherwise cmd cuts it at the `=`.
- Exit 0 is not enough: read the output (see "Silent errors" in `03-errores-comunes.md`).
- To leave the user's configuration (`~/.kettle`) alone, set `KETTLE_HOME` to a folder of your own.
