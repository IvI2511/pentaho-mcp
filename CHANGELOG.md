# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.8.0] - 2026-10-27

"De bloques a Python": 90 MCP tools.

### Added

- **Translate to Python** (IDC menu > *Translate to Python…*, and the `translate_to_python` / `translate_coverage`
  tools): a transformation (`.ktr`) or a job (`.kjb`) becomes readable **pandas** or **PySpark** code, as a `.py`
  script or a Jupyter **notebook** (`.ipynb`). The dialog asks for the format, the library and the language of the
  comments and Markdown cells (English, Spanish or Portuguese), and optionally a title, an author, an introduction of
  your own, an explanation per step, previews, exploratory-analysis (EDA) charts with matplotlib and notes on what
  differs from PDI. The notebook has a "how to run it" cell (the `pip install` line and the environment variables for
  database passwords, never the passwords) and a summary table; every step is marked **exact**, **approximate** or
  **to complete**.
- **Steps**: inputs (CSV, text with masks, fixed width, Excel, JSON, XML, table, data grid, generate rows,
  variables, system info, random values, file names, file content), fields and conditions (Filter rows with every
  condition, Switch / Case, Java filter, Join rows, Calculator with 55+ functions and PDI's rounding modes, Formula,
  User Defined Java Expression, simple JavaScript, Select values with date and number masks, String operations,
  Replace in string, Strings cut, Concat fields, Split fields / to rows, Value mapper, If null, Null if, Number range,
  Set value, Add constants / sequence / checksum, Regex evaluation, Data validator with its error hop, Clone row),
  rows and lookups (Sort rows, Unique rows, Group by with all 20 aggregates and PDI's consecutive-group semantics,
  Memory group by, Analytic query, Row (de)normaliser and flattener, Stream / Database lookup, Database join, Merge
  join, Merge rows, Multiway merge join, Append, Sorted merge, Fuzzy match, Sample / Reservoir sampling, Blocking,
  Abort, Detect empty / last row, rows to / from result) and outputs (text, Excel, JSON, XML, SQL file, table,
  insert / update, update, delete, synchronize after merge, SCD type 1/2 dimension, combination lookup, SQL scripts,
  write to log). Every translator was compared row by row with Kettle on small inputs, in pandas and in PySpark.
  What has no reasonable translation (sub-transformations, executors, complex JavaScript) stays a block to complete
  with the step's settings commented.
- **Jobs**: one function per entry (transformations and sub-jobs inside), a `main()` that follows the success,
  failure and unconditional hops (straight code, or a small state machine when there is a loop), Abort that stops the
  job, about 45 PDI 9.4 entries (files, zip, SQL, variables, evaluations, shell, HTTP, mail...) and Kitchen's exit
  code. Compared with Kitchen on the files and tables each job leaves.
- **The libraries, checked before running**: a `requirements.txt` is written (or completed, keeping what it had)
  next to the script or notebook with exactly what the code uses, including the database driver of each connection
  (`pymysql`, `psycopg2-binary`...); the code starts by checking them, so a missing library, or one too old, stops
  with a clear message in the chosen language and the `pip install` command, instead of a `ModuleNotFoundError`
  halfway through.
- **Verification without touching anything**: *Verify with the data* runs the transformation (skipping the steps
  that write, as the golden tests do) and a variant of the Python that dumps what it would write, and compares them.
- Connections become SQLAlchemy engines (pandas) or JDBC options (PySpark); passwords come from
  `PDI_PASSWORD_<CONNECTION>`, never from the `.ktr`, and no local path is written.
- `ejemplo/python/`: the Grano de Oro reference solution translated (all steps exact). The translated job, run on a
  copy of the data, passes the 29 checks of `comprobar_resultados.py`, like Kitchen.
- Manual chapter 14 (`manual/14-python.md`) and `docs/traductor-cobertura.md`.

### Fixed

- Translator robustness, checked on every step plugin of PDI 9.4 with its default settings and on every `.ktr` /
  `.kjb` of the repository, in pandas and PySpark, as `.py` and as notebook: a transformation whose hops make a loop
  no longer crashes the translation, and Stream / Database lookup without keys give valid PySpark.
- Select values: a type conversion that fails (text to number or date) sends the row to the error hop with PDI's
  error fields, in pandas and PySpark, instead of an empty error hop.
- Date and number masks: one converter for every step (quoted literals with `''`, `%` in literals, Y / k / K / u / w /
  z), currency, percent and sign in number masks on read; UTF-8 files with BOM; windows-1252 and ISO-8859-1 also in
  PySpark 4; doubled quotes inside an enclosure in PySpark's CSV reader.
- Initial capitals (Calculator and String operations) exactly as PDI (after any character that is not a letter,
  digit or `_`), in pandas and PySpark; Database lookup in pandas keeps whole numbers as integers when a key is not
  found; the "how to run it" line asks for SQLAlchemy 2.0.36 or later (pandas 3 refuses older ones).

## [0.7.1] - 2026-10-26

### Added

- **Linter autofixes for the profile rules**: `step_without_description` proposes a description from the step's
  (or entry's) type and configuration ("Lee clientes.csv (6 campos)", "Filtra filas con saldo >= 1000.0 AND
  activo = 'S'", "Runs the transformation 01_dim_tienda.ktr"; new fix kind `set_step_description`, with
  confirmation, editable in the IDC details window); `required_variable` on a connection host moves host, port and
  database to `${DB_HOST_<CONN>}`, `${DB_PORT_<CONN>}` and `${DB_NAME_<CONN>}` (the profile's variable plus the
  connection name) and gives the `kettle.properties` lines in the notes (`use_connection_variables`, with
  confirmation; the password is never read nor touched). Both are one Ctrl+Z, in a job too; `${DB_HOST_<CONN>}`
  satisfies a profile that asks for `DB_HOST`.
- `diff_documents`: `two_tabs` (with `show_on_canvas`) opens the other version in a new tab, as Compare… > Two tabs.
- **`plan_build` builds the orchestration job** (`job=true`, or `transformation=<orchestration.name>`): START,
  Check files exist of the file sources, Create folder of the outputs, a SQL entry with the `CREATE TABLE` of the
  target tables (derived from the transformations already built, on a temporary SQLite copy, never opening the
  user's database; `DROP` + `CREATE` for `load: overwrite`), one TRANS per transformation in dependency order
  (dimensions before facts, or `orchestration.order`), SUCCESS, the failure path of `on_failure` (Write to log +
  Abort, red hops from every entry; `continue` = black hops; an e-mail in `notify` = a placeholder) and a note. It
  goes to a new job document (close it to undo it), or, into the open job linked to the plan, adding only what is
  missing in **one Ctrl+Z** (a snapshot of the job canvas carried by one entry change: Spoon does not chain undo in
  jobs). `mode=sketch` leaves placeholders with TO DO notes; `fill_placeholder` on a job puts the planned entry
  (or the one passed). `plan_check` reports `job_placeholder_left` and `job_without_input_check`; the Design
  perspective builds the job sketch. The Grano de Oro job built from the plan has the structure of
  `solucion-referencia/job_carga_dw.kjb` and Kitchen runs it on a copy of `ejemplo/` (29 of 29 checks).
- **`mapping_import` proposes real steps** from the Rule, Default, Type and Validation cells (en/es/pt, read
  conservatively): String operations, Replace in string, Value mapper, Calculator (arithmetic, rounding, date
  parts), Select values (type and format), Stream lookup / Database lookup, If field value is null, Add constants
  and Filter rows with the failing rows to a rejects placeholder. What it does not fully understand stays a sketch
  placeholder and the row says `why`. Rows carry `proposal` and `steps`; apply is still one Ctrl+Z.
- **Golden-dataset tests for jobs.** `test_create` with `job_file` (or the job open in Spoon) freezes a `.kjb`:
  steps are named `"Entry :: Step"` (the Transformation entry and a step of its `.ktr`), and the test file gets
  `"job"` instead of `"transformation"` and a `job_entry` in each output, dataset and step tweak. The whole job
  runs like Kitchen in the test JVM; every transformation it starts is caught before it runs (datasets injected,
  tweaks applied, connections pointed to the test databases, outputs captured from the last run of each entry).
  New tweak **`skip_entry`** (the entry does nothing and the job goes on); by default `test_create` skips the
  entries that talk to the outside (mail, FTP/SFTP, SSH, HTTP…), SQL entries without a test database and, when
  nothing is compared but step outputs, the entries that change files and every writing step. `test_run`,
  `test_run_all`, `test_list` (new argument `job_file`), **IDC › Tests…** (now also for jobs), run on save (saving
  a job runs its tests; saving a `.ktr` also runs the job tests whose job runs it) and `pentaho-live test x.kjb`.
- **`files[]` in tests** (jobs and transformations): files the run leaves, read as CSV (separator detected at
  creation) and compared with a golden copy; a file the run did not write (missing or unchanged) fails as
  `not_written`. `${PENTAHO_LIVE_TEST_DIR}` is a temporary folder of each run, to send outputs there with a
  `set_variable` tweak. `test_create` has a new argument `files`.
- **The person's `KETTLE_HOME` in the test JVM**: its `shared.xml` connections and `kettle.properties` variables
  are copied to the test JVM's temporary `KETTLE_HOME` (`environment.user_kettle_home`, default `true`; never
  into a test file or the project; passwords and secret-looking variables are scrubbed from results and log
  tails). The grader keeps an empty `KETTLE_HOME` for students' submissions (`CorrectorProcesos.Opciones
  .kettleDelUsuario`, off).
- `pentaho-live test` speaks English, Spanish or Portuguese (`--lang`, or the system language) and every failing
  output names its first difference (key, column, expected, actual).

### Changed

- **Arrange canvas**: in a job, moving entries and notes is ONE Ctrl+Z (it was two: Spoon does not chain job undo
  actions); a job's shared failure handler (three or more "on failure" hops from one row, like Grano de Oro's
  "Registrar el error") goes below that row, where no hop crosses an entry name; many sources feeding the same step
  are spread over two staggered columns (PDI sample "Data Validator - all usecases": 2180 → 1508 px high); k
  sources that all feed the same two steps go in a row with one step above and one below (sample "Value Mapper -
  Same and new field": 15 crossings → 0). PDI samples: 30 → 15 crossings, still none through a step or a name.
- **Compare… list of changes**: sized to its columns and to the screen (0.7.0 opened it 702 px wide and cut the
  Detail column), remembers its size and column widths, and shows the full detail of a change in a tooltip. The
  other version's tab is named `[git HEAD: x.ktr] name` so a cut tab still tells them apart, and a hop's badge sits
  beside the middle of the hop instead of covering Spoon's condition icon.
- `pentaho-live textconv`: a password shows as `(hidden · #1a2b3c4d)`, a tag that changes when the password changes
  (HMAC with a random key kept in the clone's `.git/pentaho-live/textconv.key`), so a password change is visible
  in `git diff`; never the value nor its hash.
- **Schemas and qualified names go to the test database** in the tests and in the grader, for MySQL, MariaDB,
  PostgreSQL and H2: a step's `schema`, a `schema.table` name and qualified names in SQL (`club_dw.ventas`,
  `USE club_dw`) that name the original database (or the one the connection pointed to) are rewritten.
  Conservative: only names that qualify another one change, never a table, column or alias called like the
  database. In tests, a schema or SQL that would leave the test databases is an `error`; `tables[]` may name
  `schema.table`. Sub-transformations started by a job are redirected too.
- Test files no longer carry the case defaults `"weight": 1.0, "hidden": false` (old files still load; the
  grader's packs are unchanged). The example tests in `ejemplo/pruebas/` were cleaned.
- **CI of `pentaho-live init`, tried on real GitHub Actions runs** (a project with the skill's examples and the
  Grano de Oro tests, and a pull request that breaks a filter): the workflow template uses `actions/checkout@v7`,
  `setup-java@v6` and `cache@v6` (Node 24); the launchers pass `--add-opens` with
  `-XX:+IgnoreUnrecognizedVMOptions` instead of `JDK_JAVA_OPTIONS` (no «NOTE: Picked up» on stderr; Java 8 still
  works), and the test and grader JVMs copy that flag. The init README and manual 13 explain how to read a CI run.

### Fixed

- The dashed arrow of a removed hop on the canvas started at the source's centre and crossed its icon: it now goes
  from icon edge to icon edge, with an arrowhead, like Spoon's hops.
- **Field lineage of the steps that change values**: String operations, Replace in string, Strings cut, Concat
  fields, Split fields, Split field to rows, User defined Java expression, Modified JavaScript value, Value
  mapper, If field value is null, Null if, Number range, Set field value, Set field value to a constant, Add a
  checksum and Regex evaluation were «no change» (pass); now they are `calc` with what they do, in the own engine
  and in the metaverse path, so the mapping sheet's Rule and the lineage report section show the transformation.
- `test_create` with `tweaks: []` (and **Create test from preview…** with *Skip the steps that write* unchecked)
  applied the default tweaks anyway: an empty list did not reach the test JVM. `input_steps: []` likewise.
- `pentaho-live lint` without Spoon no longer floods stderr with about 1,800 lines of Kettle's «registering jar
  plugin file» and dialog `ClassNotFoundException`s.
- `required_variable`: a Y/N setting whose tag contains «path» is no longer taken for a file path.
- `run` on a job that ends in an **Abort** entry waited for the whole `wait_s` and reported `stopped_by_timeout`;
  it now returns as soon as the job stops (the same fix as in the job tests).

## [0.7.0] - 2026-10-26

### Added

- **Team conventions: the project's lint profile** (`.pentaho-live/lint.yaml`, or `.json` with the JSON Schema
  `lint.schema.json`), found going up the folders from the document's folder. It turns rules on or off and sets their
  severity, adds a `min_score`, a prefix per step or entry category (`naming`, by category or `by_type`), a required
  description per step, the variables that paths and database hosts have to use (`required_variables`) and the
  team's own rules as declarative patterns (`team_rules`: regex on the name, paths, SQL, description or XML, with a
  time limit). Four new rules that only run with a profile: `naming_convention` (automatic fix: rename, one Ctrl+Z,
  references updated; the proposed names of `default_step_name` carry the prefix), `step_without_description`,
  `required_variable` (with it, `absolute_path` proposes the profile's variable) and `team_pattern`. `lint_etl`
  and `apply_lint_fixes` use it on their own (new argument `profile`: a file, a folder or `none`) and return
  `profile: {name, path, min_score, passed}`; the IDC **Check quality…** dialog shows "Profile: name (path) ·
  minimum N: met"; the reports' quality section names it; the grader has a new rubric line `lint:profile` (the
  pack's own `.pentaho-live/lint.yaml`, passes from its `min_score`).
- New MCP tools **`lint_profile`** (the effective profile and where it was found) and **`project_init`**.
- **`pentaho-live init [folder]`** (also **IDC > Prepare project…** and `project_init`): a team project with
  only what is missing, never overwriting a file: `etl/`, `datasets/`, `tests/`, `contracts/`, `env/` with an
  example `dev.properties`, `.gitignore` (secrets, run outputs, SQLite outputs), `.gitattributes` (the readable-diff
  driver for `.ktr`/`.kjb` for `pentaho-live textconv`), a default course profile, a README
  with the conventions and `.github/workflows/pentaho-live.yml`: PDI CE 9.4 with Pentaho Live from the release,
  cached, and the linter with the profile's minimum score on every `.ktr`/`.kjb` under `etl/` (annotations and a
  job summary), the golden-dataset tests and the semantic diff of a pull request (below).
- **`pentaho-live lint <files|folders>`** (`--profile`, `--min-score`, `--format text|json|github`, `--summary`)
  and **`pentaho-live profile`**, without Spoon (`ProyectoMain`): exit code 1 when a file is below the minimum.
  `launcher/pentaho-live` runs them on macOS and Linux too, with `PDI_HOME`.
- Skill: `manual/13-proyecto.md` (the profile format, init, lint and CI).

- **Details window** in the IDC menu's **Check quality…** and **Harden…** dialogs: a double click (or Enter, or
  the new *Details…* button) on a finding or a recipe opens it in its own resizable window, with the title, a
  severity or level badge, where it is with **Go to step**, what happens, why it matters, how to fix it with the
  concrete values, the data of the finding, the **exact changes** its fix would make (a dry run of
  `apply_lint_fixes` with only that key, or of `harden_etl` with only that recipe, with the score it would get),
  an editable value when the fix takes one (a name, a path, a description, a parameter default, the variable of a
  password), a link to the manual section, and its actions: **Apply this fix / recipe** (one Ctrl+Z), **Silence
  this rule here** and **Ask Claude**, which copies to the clipboard a ready-made request with the rule, the step,
  the message and the key. Rules with no automatic fix show step-by-step instructions.
- **Check quality…**: every finding has a checkbox and the button is **Apply checked (n)**. Automatic fixes start
  checked; the ones that change what the ETL does (confirm), the ones with a proposed or chosen value and the
  robustness findings (with *Include robustness*, applying their `harden_etl` recipe) can be checked. The recipes
  are applied first and the fixes are chained to them: still one Ctrl+Z.
- New automatic fixes (with confirmation) in `apply_lint_fixes`: `disabled_hop` enables the hop,
  `orphan_step` / `orphan_entry` delete the loose step or entry, `parameter_without_default` sets the default
  (value), `select_star` names the columns of a `SELECT * FROM <one table>` (computed against the database),
  and `no_description` proposes a description (what the transformation reads and writes, or what the job runs).
- **Harden… > Test with dirty data** says before running what it will do: the inputs it will dirty (with their
  base rows), the variants, the checks, and whether it can run at all; when it cannot (a database that is not a
  SQLite/H2 file, such as MySQL) it says why and what to do, instead of an empty grid.
- The windows remember their size and the width of their columns (per user, in Spoon's properties); table cells
  show their full text as a tooltip.
- **Transformation tests with golden datasets** (0.7.0, P1): `tests/<name>.test.json` next to the `.ktr`
  (format `pentaho-live-test/1`, JSON Schema in `docs/schemas/pentaho-live-test-1.schema.json`). A test
  replaces input steps with fixed datasets (CSV in `tests/datasets/`, injected with the original field types
  and formats), runs the transformation in a separate JVM (only what leads to the captured outputs, like a
  preview) and compares the output of the chosen steps with golden CSV files (`tests/golden/`) row by row,
  by key or as a multiset, with ignored columns and tolerances: missing rows, extra rows and changed values.
  Tweaks: `skip_step` (a Table output that does not write), `remove_step`, `set_variable`, `set_parameter`.
  Disposable test databases (H2, MySQL, MariaDB, PostgreSQL) recreated from scripts before the run, and
  tables compared after it, exactly like the grader: the case, compare options, test databases and tables
  use the grader's pack format and engine (no grader file changed).
- Four MCP tools: `test_create` (snapshot mode: freezes today's input rows as datasets and the
  chosen outputs as golden data, skipping the steps that write, detecting columns that change between runs,
  copying the variables the transformation uses), `test_run`, `test_run_all` and `test_list` (with
  `show_in_spoon`). By default they test what is open in Spoon, unsaved changes included.
- **IDC › Tests…**: a non-modal window with the tests of the active transformation in green or red, Run /
  Run all in the background with Cancel, the row diff of a failing test (and its output selected on the
  canvas) and **Create test from preview…** (the steps selected on the canvas come checked).
- **Run on save** (0.7.0, P2; off by default, in the Tests… window): when a transformation is saved, only the
  tests affected by the change (its captured steps and their upstream, stopping at datasets; all of them when
  unsure) run in the background; the IDC button shows **✓ 12/12** or **✗ 1** with the details in its tooltip,
  and a balloon warns when one fails.
- The skill's manual gets `manual/12-pruebas.md`; `ejemplo/pruebas/` has tests for the Grano de Oro example.
- **Semantic diff of `.ktr`/`.kjb`** (`diff_documents`): compares a transformation or job
  open in Spoon (or a file) with its saved file (`compare_with=saved`, the unsaved changes), a git revision
  (`git:HEAD`, `git:HEAD~1`, a branch, a commit, through the system git) or another file, **by meaning**:
  steps or job entries added, removed or renamed (recognised by type, configuration, neighbours and position;
  the hops and references that only name the renamed step do not count), property changes as
  `before → after` with readable names (a Filter rows condition as one line,
  `saldo >= 1000.0 AND activo = 'S' → saldo > 1500 AND activo = 'S'`; field lists with added, removed and
  re-ordered fields; SQL and other multi-line values line by line; file paths, targets), hops (added, removed,
  enabled, error hop, job condition), error handling, connections, parameters, notes and document
  properties. Box moves are ignored unless `ignore_positions=false`. Passwords and users are never shown,
  not even encrypted: only that they changed. Output as JSON with a readable `text`, plain text or Markdown
  (for a pull request), in English, Spanish or Portuguese. Both sides are read the way Spoon writes them
  (loaded with Kettle), so a hand-written file does not show the default tags Kettle adds as changes.
- **IDC › Compare…** in Spoon: compare the active document with its saved file, a git revision (the last
  commits of the file are listed) or another file, and see the changes as **badges on the canvas** (`+`
  green, `~` amber, `»` blue for renamed; removed steps as red dashed ghosts where they were, removed hops as
  dashed lines) or in **two tabs**, the other version next to this one, both with badges. A list of changes
  opens next to it: clicking a change selects its step (or both ends of a hop), *Copy as Markdown* copies the
  diff. The badges are only drawing (a `TransPainterEnd`/`JobPainterEnd` extension): the document is not
  modified, and closing the list removes them. `diff_documents show_on_canvas=true` does the same from
  Claude, without taking the focus when Spoon is in the background.
- **Changes report section** (`sections=["changes"]`, new `compare_with` argument of `build_report` and
  `get_report_data`, which also adds the section): the table of changes by meaning against the saved file,
  git (`git:HEAD` by default) or another file. Never in the default selection.
- **`pentaho-live` git and console commands** (no Spoon; Java and the plugin jar of the PDI the launcher
  uses, or `PENTAHO_LIVE_JAR`): `normalize [--check]` writes `.ktr`/`.kjb` canonically (2-space indentation,
  LF, Kettle's own escaping, without `modified_user`/`modified_date`, attributes and parameters in a stable
  order; steps, hops, connections, notes and fields keep their order), so two saves without real changes
  give zero diff; `diff` (two files, or a file against `--rev`, exit 1 when they differ; text, Markdown or
  JSON); `textconv` for git's `diff.kettle.textconv`, so `git diff` shows one property per line instead of
  XML; `pre-commit` for an optional hook (`launcher/git/pre-commit`) that normalizes the staged files and
  stages them again; `git-setup` prints the `.gitattributes` and `git config` lines. Wired in
  `launcher/pentaho-live.cmd` and in `launcher/pentaho-live`, which on macOS and Linux runs these commands
  itself. The existing launcher options are unchanged.
- **Skill manual `11-versiones.md`** (also the MCP resource `pentaho://guide/versions`): when and how Claude
  uses `diff_documents`, how to read the result, Compare… in Spoon, the report section and the git setup.
- **ETL plan (design before building).** A small, versionable design file, `<name>.plan.yaml` (or `.plan.json`;
  JSON Schema in `docs/plano.schema.json`): goal, grain, full or incremental load, sources (format, location,
  volume, frequency, key, rejects plan), targets (kind, key, load type), business rules, stages (kind,
  transformation, after, reads, writes, lookup with key and what to do with orphans, rules, suggested step),
  orchestration, environment, risks and decisions. YAML is read with PDI's SnakeYAML in safe mode only (no Java
  tags) and written deterministically, so diffs stay clean. New MCP tools:
  - `plan_create` writes it (shape errors come back and nothing is written) and `plan_review` checks it without
    Spoon: missing grain, sources without a rejects plan, lookups without a key or without an orphans policy,
    loads that duplicate rows when re-run, upsert/SCD without a key, incremental load without a watermark,
    stages with no input or that lead nowhere, unused rules, missing orchestration or failure path… each with
    how to fix it and, when only the user knows, the question to ask them (en/es/pt).
  - `plan_get` returns the plan and its three views as Mermaid — **data flow**, **UML activity** of the
    orchestration and **components and deployment** — and writes the design document (HTML, Markdown, PDF,
    Word, Excel, images) with the report renderers and the own diagram engine.
  - `plan_build` builds one transformation of the plan on the canvas, animated and undone with **one Ctrl+Z**:
    `mode=sketch` (default) makes each stage a placeholder (a Dummy named like the stage with a colored TO DO
    note: what it reads, looks up, writes, its rules and the suggested step), `mode=steps` uses the suggested
    step types; `pace=lesson` plays it as a class-mode lesson. The transformation keeps a link to the plan.
  - `fill_placeholder` replaces a placeholder with the real step or a chain of steps, keeping its hops and
    removing its note (one Ctrl+Z).
  - `plan_check` reports the drift between the plan and what is built (open documents, or `<name>.ktr` and the
    job next to the plan): stages without a step, placeholders left, other step types, sources and targets no
    step names, a load that is not the planned one, orphans or bad rows the plan rejects with no error hop or
    rejects output, and a job that does not follow the orchestration. Warnings only.
- **Design perspective** in Spoon: open a plan, see its document with the three diagrams and the review, build
  the sketch in the linked (or a new) transformation, check the drift, export it. `PENTAHO_LIVE_DESIGN=off`
  turns it off.
- **Sketch mode**: the IDC menu shows «Sketch: 7 of 12 pieces built» for a transformation that came from a plan,
  and the new lint rule `placeholder_step` (warning) flags every placeholder left.
- **Source-to-target mapping sheet**, both ways: `mapping_export` (and the new report section `mapping`, never
  by default) writes one row per target field — source and target as `resource.field`, type, rule (from the
  field lineage), default (e.g. a Stream lookup's), validation (filters and error handling on the way),
  status, owner and notes — as Excel, Markdown, HTML, PDF or Word; `mapping_import` reads a filled sheet (xlsx
  or csv, headers in English, Spanish or Portuguese), says which rows are implemented, differ or are missing,
  and proposes steps for the missing ones (a Calculator copy, an Add constants, a placeholder with the rule),
  applied before the target step with one Ctrl+Z.
- IDC menu: **Design…** (the Design perspective with the plan of the active transformation) and **Mapping
  sheet…** (export, or check a filled sheet and apply the proposals).
- Skill: `manual/10-plano.md` (interviewing the user, the plan format, review codes, sketch, drift, mapping
  sheet), also the MCP resource `pentaho://guide/plan`. Example: `ejemplo/plano/` with the Grano de Oro plan,
  its design document and diagrams, and the mapping sheet of `04_hechos_ventas`.
- **The CI of `pentaho-live init` is complete** (P4): the workflow puts a `pentaho-live` command on the `PATH` and,
  on every pull request and push to `main`, lints `etl/` with the profile, runs the golden-dataset tests
  (`pentaho-live test --all`, also when the lint failed) and, on a pull request, fetches the base branch and writes
  the semantic diff of every changed `.ktr`/`.kjb` to the job summary (`pentaho-live diff --rev origin/<base>
  --format markdown`). Optional, off by default: the same diff as a pull-request comment (`gh pr comment`, edits its
  own comment), with `PENTAHO_LIVE_PR_COMMENT: "true"` and `pull-requests: write`.
- **`pentaho-live test [--all] [tests, .ktr files or folders]`**: the golden-dataset tests without Spoon, in a
  separate JVM like `test_run_all`; `--format text|json|github`, `--summary file.md`, `--timeout`; exit code 1 when
  one does not pass.
- **`pentaho-live diff --rev REV [folders]`** without a single file: every `.ktr`/`.kjb` changed since `REV`
  (default `HEAD`), by meaning, in text, Markdown or JSON; new and removed files get one line.
- MCP resources `pentaho://guide/tests` and `pentaho://guide/team-projects` (manuals 12 and 13, now also in the
  plugin's jar). In total 88 MCP tools (73 in 0.6.2).

### Changed

- Parameters and the document description are undoable: the parameter that `harden_etl`'s
  `input_file_parameter` recipe declares, and `apply_lint_fixes`' parameters, parameter defaults and description,
  go into the same Ctrl+Z as the rest of the call (and Ctrl+Y puts them back). The plan no longer says
  "[not undoable with Ctrl+Z]"; `undoable` is now true for those proposals and fixes.
- `password_in_file` proposes `${DB_PASSWORD_<CONNECTION>}` (was `${<CONNECTION>_PASSWORD}`) and is a confirm fix
  instead of asking for a value: the dialog shows the exact line to add to kettle.properties. The password itself
  is never read, copied, printed or logged.
- `duplicate_step_name` is evaluated when the document has unsaved changes too (it reads the file on disk, and
  the document in memory) instead of being listed as not evaluated.
- The texts of `apply_lint_fixes` (`change`, `reason`, `notes`) come in the requested `language` (English by
  default, as before). The dialogs show everything in Spoon's language, the diff lines of the `harden_etl` plan
  included (through MCP they stay in English, the same in every language).
- A deep check with nothing to read says so ("it only confirms unsorted input and the empty decimal symbol with
  data") instead of "0 probes in 0.0 s"; the checks that could not be evaluated show their reason.
- **Arrange canvas** (`arrange_canvas`, the canvas button and IDC menu item, and `build_steps` with
  `arrange`) uses a new layered layout. Before, it could leave crossing hops, hops drawn over other steps
  and steps on top of notes that could no longer be read. Now:
  - each level goes in a column, the main flow in straight lanes, the source of a lookup (Stream lookup,
    Append, Merge join… info streams) in the lane next to the step that reads it, the target of an error
    hop below its source, and separate flows in separate bands;
  - columns are spaced by the width of the step names measured with Spoon's canvas font, so names never
    overlap, and everything lands on Spoon's grid;
  - crossings are minimised (several barycenter sweeps and neighbour transpositions over virtual nodes),
    then a local search over the real drawing (straight lines from icon centre to icon centre) moves steps
    between lanes until no hop crosses another step or its name;
  - **notes are never left under a step, a name, a hop or another note**. With `notes=keep_near` (default)
    each note follows the steps it was next to (or on top of) and goes to the closest free spot around
    them; with `notes=margin` all notes go in a band above the drawing. Their size is measured as Spoon
    paints them (width/height and font of each note);
  - steps and notes move in the same undo group: one Ctrl+Z in a transformation (in a job, Spoon undoes
    the moved notes with a second Ctrl+Z); arranging an already arranged canvas moves nothing.
  - `arrange_canvas` takes optional `mode` (`readable`, the default, or `compact`) and `notes`
    (`keep_near` or `margin`), and returns `notes_moved` and the canvas metrics `before` and `after`
    (hop crossings, hops through steps and names, overlapping steps/names, steps over notes, hops through
    notes, overlapping notes). Lesson notes (class mode) keep using the class rule.
  - On the repository examples (31 files) and PDI's samples (208): no hop through a step, no covered note
    and no overlapping names after arranging; hop crossings 0.6.2 → now: 31 → 2 (repo) and 38 → 30 (PDI
    samples, as drawn by hand: 39).
- Exercise grader: **Create pack from this transformation…** redesigned for a teacher who is not a Pentaho expert.
  Five steps with the step list on the left (current, done, pending; click a done step to go back): **Solution and
  output**, **Test data**, **What is compared**, **Rules and grade**, **Review and save**. Every field has a line of
  help with an example under it, and what is wrong is shown in red right under the field instead of one long error at
  the bottom. Smart defaults: the title, the pack folder next to the solution, the capture in plain words (*the tables
  it loads into the database*, *the file written by the step «X»*, *the rows coming out of the last step*), so the
  common case is open, Next, Next, **Save pack** (it already works from step 3). The key and the columns not to
  compare are now boxes per column (**Identifies the row**, **Do not compare**), a table can be left out, and the rules
  and their points share one page with the total. The last step summarises what will be compared, the cases, the test
  databases, the grade and what students get. Spanish (tú), English and Portuguese. The pack format and the MCP tools
  do not change.
- Exercises with a database, in the wizard: one sentence explains why a test database is needed; the `.sql` scripts
  next to the solution are found and filled in by themselves (by the database or the tables each script names, the
  ones that create tables first, hidden-case scripts apart); **Test connection** only creates and drops the test
  database and says why it fails; **Generate the script from the current database…** reads the original database
  **read-only** (read-only connection, only `SELECT`/`SHOW` and JDBC metadata, a read-only user is enough) and writes a
  script with its tables in foreign-key order and their rows — the tables the solution loads go empty — for the
  teacher to review; the password is the one typed, kept in memory only.
- A case that does not finish cleanly (`finished_with_errors`, `init_failed`, `solution_failed` when creating a pack)
  now shows the whole Kettle error of the first step that failed — «Step»: message: cause, without the stack trace —
  instead of a single, often empty, line.

### Fixed

- Exercise grader: a solution or submission with **Join Rows (cartesian product)** or **Group by** («include all
  rows») failed inside the grader with «Error creating temporary file(s)»: the sandbox pointed their temporary folder
  to `${Internal.Entry.Current.Directory}/tmp`, which at run time is a `file:///` URL that `java.io.File` does not
  understand (Sort rows, which uses VFS, worked). The temporary folder of Sort rows, Join Rows, Group by and Blocking
  step now always goes to the local path of the case's `tmp/` folder, which exists, counts towards `max_output_mb`
  and is deleted with the sandbox, whatever the submission had (`%%java.io.tmpdir%%`, a folder of the student's or
  nothing).

### Tested

- `PruebaDiff`: pairs of versions of the skill examples modified in the test (condition, rename with and
  without other changes, not-a-rename, positions, field lists, parameters and error handling, multi-line SQL,
  notes, jobs) and passwords and users never present in text, Markdown, JSON or textconv.
- `PruebaNormalizador`: on every example and pattern of the skill the normalizer is idempotent, the
  semantic diff with the original is empty, Kettle loads the normalized file with the same `getXML()` except
  the two volatile fields, validation gives the same remarks, and running every example and pattern job
  gives the same results and output files; a Spoon save normalized is stable.
- `PruebaDiffGit`: a throwaway git repository: `diff --rev`, two files, JSON, `textconv` wired to
  `git diff` through `.gitattributes`, `normalize --check`, the pre-commit hook, `diff_documents` with
  `path`, the changes section.
- `PruebaDiffLienzo`: the badges of each side, the drawing with a recording GC, the painter extensions,
  the Compare… dialog and the list of changes off screen.

## [0.6.2] - 2026-10-25

### Added

- **IDC menu** in Spoon's main toolbar, right next to the perspective switcher (an `IDC` drop-down with the
  IDC Studios symbol), in Spoon's language (English, Spanish, Portuguese). Everything that so far was only
  reachable through Claude, by hand:
  - **Check quality…**: a non-modal window with the 0-100 score and grade of the active transformation or
    job, the findings (severity, rule, step or entry, message, how to fix it, whether it has an automatic
    fix); clicking one selects its step or entry on the canvas. **Apply automatic fixes** runs
    `apply_lint_fixes` with `all_auto` (one Ctrl+Z in a transformation), **Silence this rule here** runs
    `suppress_lint` on that step or entry with an optional reason, **Deep check (reads data)** is `lint_etl`
    `deep=true` with the same read-only rule, and **Include robustness** adds the robustness score. The
    check runs in the background and can be cancelled.
  - **Harden…**: the `harden_etl` plan (every recipe, dry run) as a list with checkboxes. Automatic
    proposals start checked; the ones that change which rows reach the outputs or the design of the load
    start unchecked and marked ⚠; manual ones cannot be checked and show how to do them. **Apply** is
    `harden_etl` `dry_run=false` with the checked ones (one Ctrl+Z). **Test with dirty data…** runs
    `stress_test` in the background with a progress bar, elapsed time and Cancel (which stops the test
    JVM), and shows the verdict, the checks of every variant and the messages, with an environment problem
    (a missing database or table) shown apart from robustness failures.
  - **Arrange canvas** (the same action as the canvas button), **Lesson** (shows the Lesson tab of the
    lesson loaded in the active transformation, or explains how to start one with Claude), the switch to
    every perspective of Spoon's own switcher (Data Integration, Reports, Grader…) and **About Pentaho
    Live** (version and a link to the repository).
  - The dialogs call the same code as the MCP tools, with the same arguments, and show their texts in
    Spoon's language. Still 73 tools.
- `harden_etl` and `stress_test` speak the requested language: the plan's why, how_to, warnings, skipped and
  not_applied reasons and notes (`harden_etl`), and the check messages, summaries, not_run reasons,
  environment problems, notes and next step (`stress_test`, new optional `language` argument) come in English,
  Spanish or Portuguese. The default is English, as before; JSON keys, recipe ids, levels, check ids, statuses
  and verdicts do not change.
- Exercise grader: **exercises with a database**. A pack (format `pentaho-live-exercise-pack/2`; packs without a
  database are still written in format 1) can declare `test_databases`, one disposable test database per server
  connection of the solution (MySQL, MariaDB, PostgreSQL or H2), with the pack's SQL scripts and per-case
  `db_scripts`. Before every case of every submission the grader drops and recreates each test database and runs
  the scripts (the names of the original databases in them are changed to the test ones), points the submission's
  connections there (by name, by database or by type and host) and never connects to the original databases. The
  new capture `tables` reads each declared table after the run and compares it like a file (key columns, ignored
  columns, tolerances, the solution's types); `executions: 2` runs a case twice to catch a load that duplicates rows
  on a re-run. With several workers each one gets its own test databases (`<database>_w1`, `_w2`).
- Safety rules for test databases, checked when a pack is validated or created, before a batch and in the worker:
  never the solution's own database, never a system database or one of `database_safety.deny`, and the name must
  end in a test suffix (`_correccion`, `_test`, `_prueba`, `_grading`) unless `allow_any_name`. A step whose schema
  or SQL names another database, or SQL that reads or writes server files or changes users or permissions, blocks
  the submission (`foreign_schema`, `forbidden_sql`).
- The password of a test database is never written to the pack or to any file: it comes from what the teacher types
  in Spoon (kept in memory until Spoon closes; **Grade** asks for it when it is not known) or from the Kettle
  variable `password_variable`, reaches the worker through the process environment and is scrubbed from results.
- "Create pack from this transformation…": a **Test database** box for each server connection of the solution
  (same server, port and user, the database with `_correccion`, password, scripts, hidden-case scripts, "run each
  submission twice"); **Capture** lists the tables the solution's output steps write and its file outputs; step 2
  edits the key and the ignored columns of each table and previews its expected output.
- MCP: `create_exercise_pack` takes `test_databases`, `tables`, `database_safety` and, per case, `db_scripts` and
  `executions` (it rejects any password); `capture` accepts `tables` in `create_exercise_pack`, `grade_exercise`
  and `grade_submissions`.

### Fixed

- A `harden_etl` dry run no longer touches the open document. It works on a copy, but Kettle's `realClone`
  shares the changed flag, the undo list, the change listeners, the attributes and the variables with the
  original, so a plan with proposals marked the transformation as changed in Spoon (and the «Harden…» dialog
  did the same just by opening). Every copy the tools make (dry runs, previews, runs, reports) now gets its
  own.
- Creating a pack from a solution that uses a MySQL connection failed with `solution_blocked`
  (`foreign_connection`): it now asks for a test database for it.
- The grader's errors and details are shown in the language of the UI (Spanish, English or Portuguese): the result
  keeps the English text in `detail` and adds `detail_key`/`detail_args`; the wizard, the panel and the reports
  translate them. Some Spanish texts of "Grade again with…" used *usted*; they now use *tú*.

## [0.6.1] - 2026-10-24

Bug fixes for the robustness tools.

### Fixed

- Reports: the «quality» section now runs the robustness rules too (until now only `lint_etl` with
  `robustness: true` did). Their findings go in a table of their own with their separate 0-100 robustness
  score and the points each rule takes away; the technical quality score and its error, warning and
  suggestion counts do not change. Jobs have no robustness rules and show nothing new.
- Robustness rules: a Unique rows step (the sorted one) now counts as removing duplicates, so
  `no_dedupe_before_load` no longer flags a load that already has one and `harden_etl` no longer proposes a
  second one. A source query whose inner joins only go to dimension tables (`dim_…`, `…_dim`), the usual
  way to read a star schema, is no longer flagged by `inner_join_in_source`. A Table output (which has no
  key) no longer gets "the duplicates of  first" with an empty key list.
- `harden_etl` on a crowded canvas: when there was no room below a step, its rejects steps went below
  everything and the error hop could run straight through other steps. The steps now go to the nearest free
  place whose new hops (from every step that sends rows to that rejects file) cross no step icon, label or
  note, or, if there is no such place, the fewest.
- `harden_etl` then Ctrl+Z: the saved `.ktr` is now identical to the original. Before, every step that got
  an error hop kept a disabled `<error>` block with no target after the undo.
- `stress_test`: when a run stops because a database or table is missing **and** a bad row also aborts
  another step, both are reported: `environment_problem` and the failed `no_abort` check naming the step
  and the row's error. The verdict is `fragile` (a bad row is a robustness failure whatever the
  environment); `not_run` is only for runs where the environment is the only problem.

## [0.6.0] - 2026-10-22

"Calidad y clases" (quality and classes): four new things Claude can do with an ETL, and 73 MCP tools in
total. The **linter** checks a transformation or job for what Kettle lets through and scores it 0-100.
The **exercise grader** grades a whole folder of students' transformations against a pack built from the
teacher's solution. **Class mode** builds a transformation one step at a time with an explanation on the
canvas. **Robustness** makes a load tolerate dirty data and proves it with broken copies of the inputs.

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
  inside the `.ktr` / `.kjb`, and silenced findings no longer lower the score.
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
- MCP tools for the grader: `create_exercise_pack` (from the solution open in Spoon or a `.ktr`: runs
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
  touched). Hidden notes travel inside the `.ktr`, so hiding is not secret.
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
    the recipe that fixes it. In reports, robustness findings live inside the «quality» section (there is
    no separate robustness section).
  - `harden_etl` ("Harden"): a plan of recipes with a text diff for each change, applied as **one Ctrl+Z**.
    `auto` recipes (error hop to a rejects file on loads, conversions and file inputs that read a field
    as a number or date, a reason for each rejected row, wait for the dimension loads before the lookups,
    with a Blocking step in front when both share a source so a big load cannot hang) apply by default; `confirm` ones (required fields, input file as a
    parameter, strict conversions, dedupe before a dimension, orphans to rejects) only with their key;
    `manual` ones (safe split, stable surrogate keys, Insert/Update instead of Table output, aggregate to
    the fact's grain) return the steps to follow. `dry_run` (the default) simulates the whole apply on a
    copy and returns the robustness score before and after. The steps it adds go to free space (a lane
    below their source step, spaced like the canvas layout, clear of other steps, notes and hops) and are
    named in the document's language, detected from its step names and notes (Spanish: «Limpiar motivo
    …», «Escribir rechazos …»); `language` overrides it.
  - `stress_test`: generates broken variants of the file inputs (missing fields, bad dates and numbers,
    duplicates, truncated lines, too long text, out of range numbers, empty file, header only...), runs a
    copy of the transformation on each one in a separate sandboxed JVM, and checks that it does not abort,
    that every row read is loaded, rejected with a reason or dropped on purpose, and, on a SQLite or H2
    database, that a re-run duplicates nothing and keeps the keys. If the run stops because the database
    file or a table it reads or loads does not exist, it reports an `environment_problem` (run the job
    that creates it first, or point the test at a prepared copy) and verdict `not_run`, not a robustness
    failure. It never connects to other databases
    (`not_run`, and `export_variants` writes the broken files to try by hand), never writes next to the
    original files, switches off database logging in the copies, and does not run steps that leave the
    machine or write where it cannot redirect them (HTTP, mail, SFTP, message queues, other output
    plugins).
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

[Unreleased]: https://github.com/IvI2511/pentaho-mcp/compare/v0.8.0...HEAD
[0.8.0]: https://github.com/IvI2511/pentaho-mcp/compare/v0.7.1...v0.8.0
[0.7.1]: https://github.com/IvI2511/pentaho-mcp/compare/v0.7.0...v0.7.1
[0.7.0]: https://github.com/IvI2511/pentaho-mcp/compare/v0.6.2...v0.7.0
[0.6.2]: https://github.com/IvI2511/pentaho-mcp/compare/v0.6.1...v0.6.2
[0.6.1]: https://github.com/IvI2511/pentaho-mcp/compare/v0.6.0...v0.6.1
[0.6.0]: https://github.com/IvI2511/pentaho-mcp/compare/v0.5.1...v0.6.0
[0.5.1]: https://github.com/IvI2511/pentaho-mcp/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/IvI2511/pentaho-mcp/compare/v0.4.2...v0.5.0
[0.4.2]: https://github.com/IvI2511/pentaho-mcp/compare/v0.4.1...v0.4.2
[0.4.1]: https://github.com/IvI2511/pentaho-mcp/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/IvI2511/pentaho-mcp/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/IvI2511/pentaho-mcp/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/IvI2511/pentaho-mcp/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/IvI2511/pentaho-mcp/releases/tag/v0.1.0
