# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **ETL linter**, `lint_etl`: checks a transformation or job for what Kettle lets through, beyond `validate`.
  28 rules (two more are off by default): Group by, Merge join, Unique rows and the other steps that need
  sorted input, the empty decimal symbol, Calculator Integer × Number, variables in Add constants or Generate
  rows, Text file output without fields, absolute and relative paths, passwords stored in the file, a job's
  failure branch that ends in Success, a START entry with another name, default step names, no description…
  Each finding says how to fix it and whether `apply_lint_fixes` can. The result has a 0-100 technical
  quality score (grades A-D) and the points each rule takes away; any error caps it at 74. It opens no
  dialogs, does not run the transformation and never connects to a SQLite, H2 or HSQLDB file that does not
  exist (Kettle would create it empty); `include_kettle_checks` adds Spoon's own Verify, which never changes
  the score and skips the steps that would touch such a file.
- Deep mode, `lint_etl` with `deep=true`: reads up to 5000 rows of the sources to confirm or rule out the
  unsorted input and decimal symbol findings. An unsorted input is proven by running the step with and
  without a Sort rows before it ("gives 4 rows instead of 10"), and becomes an error; sorted data or integer
  values, with the whole input read, lower the finding to info. It runs only through steps known to be read-only (a
  Table input only with a plain SELECT/WITH query and no SQL run on connect), writes nothing to the log tables,
  never creates a missing SQLite, H2 or HSQLDB file, and `deep_timeout_s` (default 60) caps it. In a report,
  `"mostrar_valores": false` keeps the values it read out of the quality section.
- `apply_lint_fixes`: applies the fixes `lint_etl` proposes, by key. `all_auto` takes the safe ones
  (proposed names for default step and entry names, START renamed, Calculator operands swapped); the rest
  (a Sort rows or an `ORDER BY`, copy mode, paths, the decimal symbol, output fields, the failure hop to an
  Abort, a `${VARIABLE}` for a password) only by key, after asking the person. In a transformation the whole
  call is one Ctrl+Z; in a job, one per change. `dry_run` returns the new score without changing anything.
- `suppress_lint`: silences a rule on a step, a job entry or the whole document, with a reason. It is stored
  inside the `.ktr` / `.kjb`, and silenced findings no longer lower the score (61 tools).
- MCP resource `pentaho://lint/rules`: the rule catalog (severity, category, what, why and how to fix in
  English, Spanish and Portuguese) and the score formula.
- Reports: **Technical quality** section (`quality`, right after validation, on by default) with the score,
  one notice per error, the findings with the proposed fixes, where the missing points come from and what
  could not be checked. It is in every export, Word, Excel and combined reports included. `build_report`,
  `get_report_data` and `show_report` take `quality_deep` (with `documents` it applies to each of them), and
  the Reports panel has a "Deep check (reads data)" box under the section.
- Skill: a Quality section in `SKILL.md` and the guide `manual/06-calidad.md` (when to run the linter, the
  score, the deep mode, fixing and silencing). MCP clients without the skill read it as the resource
  `pentaho://guide/quality`.
- `tools/lint-corpus/`: regression harness that runs the linter over PDI's samples, the skill examples and 27
  copies of the examples broken on purpose; it fails if a known error is missed or the counts change.
- **Exercise grader**: grades students' transformations against an exercise **pack** (`pack.json` with the
  statement, the input data of each case, the expected output, rules, a rubric and a policy; a folder, a `.zip`
  or a single `.json` with the data inline). Grading is deterministic, with no AI: each submission runs in a
  **separate JVM** (a pool of workers, never inside Spoon) with an empty `KETTLE_HOME` and the pack's locale,
  timezone and encoding; its file inputs are redirected to each case's data and its file outputs to a sandbox,
  and its output (the Text file output, the rows of a step, or an SQL over a copy of an SQLite template) is
  compared with the expected one: by key or as a multiset, with the solution's types, the student's own number
  format, a numeric tolerance, examples of what is missing, extra or different, repeated keys and renamed
  columns. A **hidden case** runs like the others but its values never reach the student. The rubric scores
  `runs`, `columns`, `row_count`, `values` (partial credit, weighted by case), pack rules (`must_use`,
  `must_not_use`, `max_steps`, `error_handling`) and linter rules (`lint:<id>`), with gates (`requires`), on a
  scale of 10. The result (`pentaho-live-grade-result/1`) has one sentence of advice for each typical mistake
  (Group by without sorting, values ×100 from an empty decimal symbol, a renamed column, mojibake, a blocked
  step…). Step policy: safe steps run, conditional ones (database steps against the pack's database, Set
  variables, mappings, Modified JavaScript) only if the pack allows them, dangerous ones (Java, shell, network,
  mail, deleting files) only in trust mode, which exists only in Spoon's UI; reads outside the exercise, output
  names taken from a field, foreign connections, duplicate step names and circular hops block the submission
  before anything runs. Limits per case: timeout, rows and output size. It is not a real sandbox, and the
  documentation says so.
- MCP tools for the grader (66 tools): `create_exercise_pack` (from the solution open in Spoon or a `.ktr`: runs
  it in a child JVM to write the expected output, detects the inputs, the output step, the columns and a unique
  key, suggests rules and a rubric, checks the solution with the other locale and writes a copy for students
  without the solution or the hidden cases), `validate_exercise_pack` (schema, paths, files, and the solution
  graded against its own pack), `grade_exercise` (one submission: a `.ktr`, a `.zip`, a folder or the open
  transformation, with feedback as Markdown, HTML, PDF, PowerPoint, Word or Excel), `grade_submissions` and
  `get_grading_status` (a whole folder in the background; Moodle folder names and an optional `alumnos.csv`).
  Trust mode cannot be enabled over MCP, and `allow_steps` only takes conditional steps.
- Gradebook: `notas.csv` (for Excel in Spanish: BOM, `;`, decimal comma, every text cell defused) and
  `notas.xlsx` (the grade as a formula over the engine's grade and the teacher's adjustment, a summary sheet),
  keeping the teacher's adjustments and comments when the folder is graded again; per student, the full
  `<student>.json` and the feedback `<student>.md` and `<student>.html`, which never show a hidden case's values.
  The feedback and the course report (grade distribution, most failed criteria) are drawn by the Reports
  renderers.
- **Grader** perspective in Spoon (Corrector / Corretor): pick the pack and the submissions folder, grade, and
  the table fills in as the workers finish (one mark per check, sortable); the selected grading as a report, as
  the teacher or as the student sees it; the teacher's adjustment and comment; **Open their .ktr** (the copy that
  ran the visible case, never the original); **Grade again with…** (more steps allowed, or trust mode, recorded
  in the result); the gradebook, the course report and the feedback of every student as HTML and PDF; and
  **Create pack from this transformation…**, a 4-step wizard that shows the detected inputs, the output step,
  the key and the expected output before saving. `PENTAHO_LIVE_GRADER=off` hides it.
- Skill: a grading section in `SKILL.md` and the guide `manual/07-corrector.md`, also the MCP resource
  `pentaho://guide/grading`.
- `ejemplo/pack-correccion/`: a grading pack built from «Grano de Oro» (sales by shop and category from the CSV,
  the Excel and the JSON, with a hidden case generated by `ejemplo/herramientas/generar_caso_oculto.py`), its
  solution `solucion-referencia/06_kpi_desde_ficheros.ktr` (10, same numbers as `datos/resultado_esperado.json`),
  the copy for students, four submissions with typical mistakes in `ejemplo/entregas-ejemplo/` (absolute paths
  9.5, no deduplication 5.3, empty decimal symbol 6.0, Group by without Sort rows 5.0) and what the grader wrote
  for them in `ejemplo/informes/correccion/`.
- **Class mode**: Claude builds a transformation slowly and explains it on the canvas, for a projected class
  or for someone learning alone. `lesson_start` takes the whole lesson (steps with `config_xml` plus what/why,
  watch-out, tip, question and preview rows; beginner or intermediate; en/es/pt), checks it and plans the
  layout before touching anything, and Spoon then plays it one step at a time. Each step appears in its final
  place with a numbered note ("N · Name (Type) / What / Why") and, if asked, a watch-out with the manual's
  error code, a tip, a question and a preview of the rows. Each step is one Ctrl+Z. Paces: `guided` (waits for
  "Next ▶"), `auto` (every few seconds, with pause) and `manual`. The notes never overlap the steps, hops or
  other notes (placed in bands above and below the flow); notes the user already had are kept.
- **Lesson** tab in the transformation's execution results panel: the list of steps, the full text of the
  current one, its preview rows, and ◀ Back (a real Ctrl+Z of the last step), Pause/Resume, Next ▶, Restart
  and Finish; A-/A+ change the size of the tab's text. Closing the results panel pauses the lesson; opening it
  again brings the tab back and resumes it.
- `lesson_next`, `lesson_status` (with `wait_s`, so Claude can follow the class without spending tokens on
  every click), `lesson_control` (pause, resume, back, goto, restart, stop) and `lesson_notes` (list, add,
  hide, show, remove, restyle for a projector, relayout; one Ctrl+Z each, and notes the user wrote are never
  touched). Hidden notes travel inside the `.ktr`, so hiding is not secret (66 tools).
- Reports: **Lesson** section (`lesson`, never on by default): the handout of the lesson, with the canvas and
  its notes, every step with what, why, watch-out, tip, question and glossary entry, the preview rows seen in
  class, the exercise and a table of the common errors of the step types used. After restarting Spoon it is
  rebuilt from the notes stored in the `.ktr`, without the rows.
- Class glossary (`catalogo/glosario-clase.json`, ~45 common steps in en/es/pt: what it does, an analogy and
  its typical errors) for `watch_out: "auto"`, also as the MCP resource `pentaho://glossary/{lang}/{id}`.
- Skill: a Class mode section in `SKILL.md`, the guide `manual/08-modo-clase.md` (also the resource
  `pentaho://guide/class-mode`) and three tested lessons for Big Data 1 in `lecciones/`, in es/en/pt: L1
  first ETL (filter), L2 Sort rows + Group by, L3 Stream lookup vs Merge join. A plugin test plays each one to
  the end without Spoon and checks the numbers of the result.
- Demo: `ejemplo/leccion/`, an 8-step lesson on the "Grano de Oro" sales (standardise, remove duplicates
  through an error hop, 60 rejects with their reason), and part 4 of `ejemplo/guion-demo.md`.
- **Robustness**: make a load tolerate dirty data, so that a bad row goes to a rejects file with its reason
  (`motivo`) instead of stopping the load or vanishing. Four pieces that share one analysis of the graph:
  - Linter family `robustness` (17 rules, off by default): loads and type conversions without an error hop,
    rejects without a reason or that end in a Dummy, a Filter rows that drops rows without a false branch,
    lookups that lose or do not check orphans, an inner join in the source SQL, no check of required
    fields, lenient conversions, a split that can fail, no dedupe before loading a dimension, a Sequence
    used as surrogate key (keys change on every run), Table output that duplicates rows on a re-run, a
    fact not grouped to its key, a lookup that reads a table before its load finishes, a fixed input file.
    `lint_etl` with `robustness: true` adds a separate robustness score and never changes the main one. A
    step can declare a deliberate drop (duplicates, a filter) and is then not flagged. Each finding names
    the recipe that fixes it.
  - `harden_etl` ("Harden"): a plan of recipes with a text diff for each change, applied as **one Ctrl+Z**.
    `auto` recipes (error hop to a rejects file on loads, conversions and file inputs that read a field
    as a number or date, a reason for each rejected row, wait for the dimension loads before the lookups,
    with a Blocking step in front when both share a source so a big load cannot hang) apply by default; `confirm` ones (required fields, input file as a
    parameter, strict conversions, dedupe before a dimension, orphans to rejects) only with their key;
    `manual` ones (safe split, stable surrogate keys, Insert/Update instead of Table output, aggregate to
    the fact's grain) return the steps to follow. `dry_run` (the default) simulates the whole apply on a
    copy and returns the robustness score before and after.
  - `stress_test`: generates broken variants of the file inputs (missing fields, bad dates and numbers,
    duplicates, truncated lines, too long text, out of range numbers, empty file, header only...), runs a
    copy of the transformation on each one in a separate sandboxed JVM, and checks that it does not abort,
    that every row read is loaded, rejected with a reason or dropped on purpose, and, on a SQLite or H2
    database, that a re-run duplicates nothing and keeps the keys. It never connects to other databases
    (`not_run`, and `export_variants` writes the broken files to try by hand), never writes next to the
    original files, switches off database logging in the copies, and does not run steps that leave the
    machine or write where it cannot redirect them (HTTP, mail, SFTP, message queues, other output
    plugins) (63 tools).
  - Skill: the guide `manual/09-robustez.md` (recipes R01-R14 with the exact XML, also served as
    `pentaho://guide/robustness`) and the pattern `patrones/06_carga_tolerante` (a load with stable keys,
    Insert/Update, orphans and database errors to rejects, proven clean, re-run and broken with Kitchen).

### Changed

- With lesson notes in the transformation, `arrange_canvas` and `build_steps` re-place the steps and the
  lesson notes together with the class rule (same Ctrl+Z), and `get_document` marks the lesson notes instead
  of returning their whole text.

- `validate` runs its own structural checks through the linter. Its messages now name the step, and the
  report's validation section shows them in the report's language. A step that distributes rows among
  outputs of the same type (deliberate load balancing) is no longer reported, hidden job entries
  (`draw=N`, common in old jobs) are no longer flagged as having no hops, and a disabled hop in a job says
  that the job never reaches the entry that way.
- Skill: `manual/03-errores-comunes.md` gives the linter rule next to each error it catches, and corrects
  three entries. C24: Kitchen finds the START entry by name ignoring case (`Start` works). D10: the red
  branch of a condition such as File exists is its "else", and what an entry in between does matters. D6:
  Kettle's advice to "Insert a Dummy step" for an input in several copies hides the unsorted rows instead of
  fixing them.

### Fixed

- `rename_step` now also updates the steps that name the renamed step as plain text: Prioritize streams,
  Block this step until steps finish, Steps metrics, Multiway merge join, XML join and Join rows (they kept
  the old name and failed at the next run).
- Reports: a table cell with a very long word (a path, a `${…}` value) no longer pushes the table past the
  edge of the PDF page, where the last column was cut off.
- Reports panel: "Preview ready (1 section)" in the singular.

## [0.5.1] - 2026-10-21

### Added

- Reports: export to **Word** (`.docx`, with a clickable table of contents, numbered headings and page
  numbers), **Excel** (`.xlsx`, one sheet per report table plus an index sheet) and **images** (a folder
  with a PNG and an SVG of every diagram and chart, ready to paste in slides). In the Reports tab and in
  `build_report` (`docx`, `xlsx`, `images`).
- Reports of **several documents at once**: in the Reports tab, a *Documents* group lists the open
  transformations and jobs (and *Add the job's transformations* adds the ones a job runs); export them as
  separate files or **all in one file** with a cover, a two-level table of contents and one numbered
  chapter per document. In `build_report`: `documents`, `include_job_transformations` and `combine`.
- Data profile of **several steps** in one run (a list with checkboxes in the Reports tab;
  `profile_steps` in `build_report`, up to 10).
- **Arrange canvas (Pentaho Live)** in Spoon: a button on the transformation and job canvas toolbars and
  an item in the canvas right-click menu run the same auto-layout as `arrange_canvas` (one Ctrl+Z).
- Field lineage: a short "what is this for" paragraph at the top of the section.

### Changed

- Flow, job and lineage diagrams are drawn by Pentaho Live itself instead of Mermaid: a long chain of
  steps wraps into rows, so the diagram comes out roughly square and fills the page or the slide; a very
  large diagram is split into numbered parts; lineage lines are ordered to cross as little as possible.
  The Mermaid code is still in the Markdown export. The target table model (ER) still uses Mermaid.
- PowerPoint: the data-dictionary annex and the reference tables are left out by default (they stay in
  the HTML, PDF and Word); a long table takes at most two slides (a 26-step class exercise went from
  47 slides to 14).
- The "rows written per interval" chart is now **Pace of each step during the run**, with an
  explanation, and only appears when the run is long enough to say something.
- Run timeline (Gantt): when there are too many rows, the fastest ones are left out (and named), never
  the slowest.
- The "What it does" column of the step list shows file paths relative to the folder of the `.ktr` /
  `.kjb`, so a shared report does not carry the author's local folders.
- Windows installer sources reorganised: `instalador.iss` and `preparar_instalador.ps1` at the repo root,
  `launcher/` (launcher, `pentaho-live` commands and icon), `tools/instalador/` (image generator and
  fonts); build intermediates in `build/instalador/` and only the `Instalador_*.exe` in `instalador/`.
  `preparar_instalador.ps1 -SinCompilar` reuses the plugin already built in `spoon-plugin/dist/`.

### Fixed

- A confirmation dialog of the Reports tab showed a literal `%n%n`.
- PDF: a diagram taller than the page no longer leaves a page with only the heading and a page with an
  empty frame; tall figures are scaled to fit under their heading.
- Field lineage now follows **every** input of a step with several input hops (Append, Merge join,
  Stream lookup...), instead of stopping at the first one; Calculator constants show their value
  instead of "1 (?)".
- Technical errors of the lineage engine (`metaverse`) go to the log, not into the report.
- Validation messages that PDI 9.4 delivers in Spanish with a replacement character instead of an
  accented vowel ("más", "carácter") are repaired for the known words.

## [0.5.0] - 2026-10-21

### Added

- `ejemplo/`: «Grano de Oro», a complete ETL exercise in Spanish (statement, dirty data in CSV, Excel and
  JSON with known error counts, a reference solution checked with Kitchen, and `comprobar_resultados.py`).
  It includes the ETL built live in Spoon by the MCP, the GIF of the build, the reports made by the Reports
  tab and a demo script (`guion-demo.md`).
- Skill: ready-made ETL patterns in `patrones/` (CSV to staging, deduplication, incremental load by date,
  SCD type 2 dimension, fact load with surrogate keys), each one tested with Kitchen, indexed in
  `manual/05-patrones.md`.
- `set_error_handling` (58 tools): configures a step's error handling, as in Spoon's "Define error
  handling...": the target step, the extra fields of the failing rows (count, description, field names,
  codes) and the limits. It creates the error hop if needed and is a single Ctrl+Z.
- `instalar.sh` on a Mac with an Apple chip (M1–M4): step 3 says what it would install (Rosetta 2 and
  Temurin 21 x64 in `~/pentaho/java-x64`, checked with the SHA-256 from the Adoptium API) and does it only
  after an explicit `y`, or with `--rosetta`; it leaves `spoon-rosetta.sh` next to `spoon.sh`.
- Automatic test on GitHub Actions (`.github/workflows/plataformas.yml`): install, Pan, Spoon and every MCP
  call on macOS 15/26 (Apple chip and Intel) and Ubuntu 24.04/26.04, with the release or with the plugin
  built from the branch (`plugin: rama`). Results in [`docs/plataformas.md`](docs/plataformas.md).

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

- Windows installer: repairing a 0.4.1 installed inside a PDI in OneDrive no longer leaves the empty
  `plugins\Pentaho Live\bin` folder behind (OneDrive marks its folders read-only and Windows refuses to
  remove them).
- `close_document` left the closed tab visible in Spoon (an orphan tab with no document behind it).
- Reports: the field lineage gave a SQLite or H2 database inside a folder with spaces as missing (the
  folder variable is a URI with `%20`), so the lineage came out partial.
- `instalar.sh --pdi` works with the plugin zips of 0.4.0 and 0.4.1 (backslash paths, folders without the
  execute bit) and gives the `.sh` scripts, `spoon.command` and the macOS app their permissions back.

- `test_connection` no longer warns that an existing SQLite or H2 file "did not exist" when its path is
  a `file:///…%20…` URI, as `${Internal.Entry.Current.Directory}` gives.

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

[Unreleased]: https://github.com/IvI2511/pentaho-mcp/compare/v0.5.0...HEAD
[0.5.0]: https://github.com/IvI2511/pentaho-mcp/compare/v0.4.2...v0.5.0
[0.4.2]: https://github.com/IvI2511/pentaho-mcp/compare/v0.4.1...v0.4.2
[0.4.1]: https://github.com/IvI2511/pentaho-mcp/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/IvI2511/pentaho-mcp/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/IvI2511/pentaho-mcp/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/IvI2511/pentaho-mcp/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/IvI2511/pentaho-mcp/releases/tag/v0.1.0
