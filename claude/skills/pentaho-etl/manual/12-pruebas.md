# 12 · Tests: golden datasets for transformations and jobs

A **test** freezes what a transformation produces today and fails when a change alters it. It replaces the
input steps with fixed datasets, runs the transformation in a separate JVM (never inside Spoon) and compares
the output of chosen steps with **golden** CSV files, row by row. It is the grader's engine
(`manual/07-corrector.md`) for everyday work: same comparison, same test databases, same «case» format.
Tools: `test_create`, `test_run`, `test_run_all`, `test_list`; by hand, Spoon's **IDC** menu > **Tests…**
(*pruebas* = tests, *dorado* = golden). Since 0.7.1 a test can also be of a **job** (§4b): the whole job runs
like Kitchen and the outputs of its transformations, the tables and the files it leaves are compared.

## 1. When

- Before refactoring a transformation that works (renaming fields, replacing a Calculator, adding
  robustness): freeze its outputs first, change, run the tests.
- When the person says *que no se rompa*, *prueba de regresión*, *comprobar que sigue dando lo mismo*,
  *golden data*, *test this transformation*.
- After fixing a bug: a test with the corrected output keeps it fixed.

Do not freeze an output you have not checked: a test only says "same as the day it was created". Preview
the step first (`preview_step`) and confirm the numbers with the person.

## 2. The fast path (live)

1. `preview_step` on the step whose output matters (usually the last one of each branch, or the one before
   the writer). Check the rows.
2. `test_create` with `name`, `output_steps` and, when rows have an identity, `key_columns`
   (`{"Totales": ["producto"]}`): a changed value is then reported as *changed* with its key instead of one
   *missing* plus one *extra* row. It tests what is open (unsaved changes included) and writes:

   ```
   <folder of the .ktr>/tests/
   ├── <name>.test.json                 the test
   ├── datasets/<name>/<step>.csv       today's rows of each input step
   └── golden/<name>/<step>.csv         today's rows of each output step
   ```

   Defaults: every **source** step (no incoming hop) that leads to the outputs becomes a dataset, except
   Data grid and Generate rows (already fixed); every step that **writes** (Table output, Text file
   output…) gets `skip_step`, so creating and running the test writes nothing; variables the transformation
   uses are copied from Spoon as `set_variable` (never names that look like passwords). The response lists
   the files, the tweaks and `warnings` — read them to the person.
3. Change the transformation. `test_run` (`test`: the name) or `test_run_all`.
4. `failed`: read `message` and `case.tables[].comparison.examples` (`different`: key, column, expected,
   actual; `missing` / `extra`: rows). Explain the cause, fix, run again. If the change was **intended**,
   recreate the test (`test_create` with `replace: true`) — never edit golden files by hand to make a test
   pass.

`status` is `passed`, `failed` (it ran, something differs or the transformation failed; `case.run` has the
error and the log tail), `error` (the test could not run: invalid JSON, missing dataset, unsafe test
database) or `cancelled`.

## 3. The test file

`tests/<name>.test.json`, format `pentaho-live-test/1`, JSON Schema in
`docs/schemas/pentaho-live-test-1.schema.json` of the repository. Paths are relative to the test's folder.

```jsonc
{
  "format": "pentaho-live-test/1",
  "name": "kpi_tienda_categoria",
  "transformation": "../06_kpi_desde_ficheros.ktr",
  "environment": { "locale": "es_ES", "timezone": "Europe/Madrid", "encoding": "UTF-8",
                   "timeout_s": 120, "max_rows": 1000000 },
  "datasets": [ { "step": "Leer ventas (CSV)", "file": "datasets/kpi/leer_ventas_csv.csv",
                  "columns": [ { "name": "fecha", "type": "Date", "format": "dd/MM/yyyy" } /* … */ ] } ],
  "case": { "id": "kpi_tienda_categoria", "expected": "golden/kpi_tienda_categoria" },
  "outputs": [ { "step": "Redondear importe",
                 "columns": [ { "name": "importe", "type": "Number" } /* … */ ],
                 "compare": { "key_columns": ["tienda", "categoria"], "numeric_tolerance": 0.000001 },
                 "ignore_columns": [] } ],
  "golden_csv": { "separator": ",", "enclosure": "\"", "header": true, "encoding": "UTF-8" },
  "tweaks": [ { "type": "skip_step", "step": "Escribir KPI" },
              { "type": "set_variable", "name": "ANIO", "value": "2026" } ]
}
```

- **Datasets**: CSV with comma, double quotes and a header, values without format (numbers with `.`, dates
  `yyyy-MM-dd HH:mm:ss.SSS`, booleans `Y`/`N`). `columns` keep the type, mask, decimal and grouping symbols,
  length and precision the field had, and the rows are injected with them (the step becomes an Injector
  with the same name and its incoming hops are removed), so the next steps see exactly the same field.
- **Outputs**: compared like a grader pack (`compare` = the pack's `compare`): by `key_columns` or as a
  multiset (`ignore_row_order`), columns by name, numbers as numbers (`numeric_tolerance`, default 1e-6),
  extra columns fail (`extra_columns: penalize`). `ignore_columns` for load dates, UUIDs or the system time
  (`test_create` detects columns that change between two runs and adds them).
- **Only what is needed runs**: like a preview, steps that do not lead to a captured output are left out
  (`run_whole_transformation: true` to run everything).
- **Tweaks** (applied to the copy that runs, never to the file): `skip_step` (the step does nothing, its rows
  pass through: a Table output that does not write), `remove_step` (gone with its hops; if it receives error
  rows they end there), `set_variable`, `set_parameter`.
- **Environment**: the test JVM's locale, time zone and encoding (by default Spoon's at creation), so a
  `<decimal/>` left empty behaves the same on every computer. Its `KETTLE_HOME` is a temporary folder that gets
  a copy of the person's **`shared.xml` connections and `kettle.properties` variables** (0.7.1;
  `environment.user_kettle_home`, default `true`): a transformation that uses a shared connection or a
  `kettle.properties` variable runs like in Spoon. Nothing else is copied (no repositories, metastore or
  plugins), the copy is deleted with the run and never goes into a test, a golden file or the project, and the
  passwords (connections, and variables named like `*PASS*`, `*TOKEN*`, `*KEY*`, `*SECRET*`) are replaced by
  `********` in results and log tails. `"user_kettle_home": false` runs with an empty `KETTLE_HOME` (a test that
  must not depend on the machine); then set what it needs with `set_variable`.
- **Files** (`files[]`, 0.7.1): files the run leaves, read as CSV (the separator is detected when the test is
  created: `csv`) and compared like an output with `golden/<name>/file_<file>.csv`. The path may use run
  variables (`${Internal.Entry.Current.Directory}`, `${PENTAHO_LIVE_TEST_DIR}`: a temporary folder of each run);
  relative, it is relative to the test's folder. A file the run did not write (missing, or unchanged since
  before the run) fails as `not_written`: an old file is never compared. With `files` (or `tables`) the whole
  transformation runs, and with `files` `test_create` skips no writing step (what writes has to write) — point the output to
  `${PENTAHO_LIVE_TEST_DIR}` with a `set_variable` tweak if the real file must not be overwritten.
- Defaults are not written: `case` has no `weight`/`hidden` (the grader's pack still uses them; old tests that
  have them keep loading).

## 4. Databases

For transformations that read or write MySQL, MariaDB, PostgreSQL or H2, pass `test_databases` (the grader's
format, `manual/07-corrector.md` §5): before the run each test database is dropped, created again and filled
with its `scripts` and the case's `db_scripts`, and the transformation's connections are pointed there (by
name, database or type and host). Names must end in `_test`, `_prueba`, `_correccion` or `_grading`; the
original database is never touched; the password comes from `password_variable` or what the person typed in
the grader, never from the file. `tables` lists the tables read after the run and compared with
`golden/<name>/<table>.csv`. Steps whose connections all have a test database are neither skipped nor turned
into datasets: their data comes from the scripts.

**Schemas and qualified names** (0.7.1, like the grader, `manual/07-corrector.md` §5): a `schema` written in a
step (Table output, Insert/Update, Database lookup…) or a table name `gym_dw.ventas` that names the original
database (`original_database`, or the database the connection pointed to) goes to the test database, and so do
the qualified names in SQL (`SELECT … FROM gym_dw.ventas`, `USE gym_dw`) — only when they qualify another name:
a table, column or alias called like the database is left alone. In H2 and PostgreSQL a schema lives inside the
test database (create it in the scripts: `CREATE SCHEMA gym_dw` becomes `gym_dw_test` there too); in MySQL a
schema is a database, so another one is an `error`. SQL that would leave the test databases (another database,
`INTO OUTFILE`, `COPY … TO`…) is an `error` too: a test never touches a real database. `tables` may name
`schema.table` (`gym_dw.destino`). Without `test_databases`, the notes say which connections are the real ones.

## 4b. Jobs (0.7.1)

`test_create` with `job_file` (or the job open in Spoon) freezes a **job**. Steps are named
**`Entry :: Step`** — the Transformation entry and a step of its `.ktr` (`"Cargar ventas :: Leer ventas"`);
without the entry, the only entry that has that step. In the file each output, dataset and step tweak carries
`job_entry` (the entry name or the `.ktr` file name):

```jsonc
{ "format": "pentaho-live-test/1", "name": "carga", "job": "../job_carga_dw.kjb",
  "datasets": [ { "job_entry": "Hechos", "step": "Leer ventas", "file": "datasets/carga/hechos_leer_ventas.csv" } ],
  "outputs":  [ { "job_entry": "Hechos", "step": "Calcular importe" } ],
  "files":    [ { "path": "${PENTAHO_LIVE_TEST_DIR}/resumen.csv", "csv": { "separator": ";" } } ],
  "tweaks":   [ { "type": "skip_entry", "job_entry": "Enviar correo" },
                { "type": "skip_step", "job_entry": "Hechos", "step": "Cargar hechos" },
                { "type": "set_variable", "name": "DIR_SALIDA", "value": "${PENTAHO_LIVE_TEST_DIR}" } ] }
```

- The job runs entire in the test JVM (like Kitchen, with `timeout_s` and Cancel). Each transformation that
  starts is caught before it runs: its dataset steps become Injectors, its step tweaks apply, its connections go
  to the test databases (the job's own connections and SQL entries too), and its captured outputs keep the rows
  of the **last** time that entry ran. An entry the job never ran makes its outputs fail as `missing_step`.
- **`skip_entry`**: the entry does nothing and the job goes on by its success hop. `test_create` adds it by
  default to entries that talk to the outside (mail, FTP/SFTP, SSH, HTTP, SNMP, message boxes…), to SQL entries
  without a test database and — when the test compares neither `files` nor `tables` — to entries that create,
  move or delete files, and adds `skip_step` to every writing step of every transformation of the job: creating
  the test writes nothing. A Shell entry still runs (a warning says so). Read the `tweaks` it returns.
- Run on save: saving the job runs all its tests; saving a `.ktr` also runs the job tests (in its tests
  folders) whose job runs it. `pentaho-live test etl/carga.kjb` runs the tests of a job.
- Only top-level Transformation entries are offered when creating; an entry of a sub-job can still be named
  by hand in `job_entry`.

## 5. Run on save and the IDC menu

**IDC › Tests…** lists the tests of the active transformation in green or red, runs one or all in the
background (Cancel stops the test JVM), shows the row diff of the selected test and selects its failing
output on the canvas. **Create test from preview…** asks the same as `test_create` (the steps selected on the
canvas come checked as outputs). **Run on save** (off by default: it starts a Kettle JVM on every save) runs,
after each save, only the tests the change affects — a changed step that is a captured output or upstream of
one, stopping at datasets; all of them when unsure — and shows **✓ 12/12** or **✗ 1** on the IDC button,
with a balloon when one fails. `test_list` with `show_in_spoon: true` opens the window when the person asks.

**Without Spoon** (a terminal, or the CI that `pentaho-live init` writes, `manual/13-proyecto.md` §5):

```sh
pentaho-live test --all                       # every *.test.json under the current folder
pentaho-live test etl/ventas.ktr              # the tests of one transformation
pentaho-live test tests/ventas.test.json --format json
pentaho-live test --all --format github --summary "$GITHUB_STEP_SUMMARY"
```

Same engine and JVM as `test_run_all`; exit code 0 when every test passed (or there are none), 1 when one did
not, 2 for a usage error.

## 6. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `error`: *The step 'X' of the dataset … is not in …* | A dataset step was renamed or deleted: recreate the test or edit `datasets[].step` |
| `failed`: *'X' is no longer in the transformation* | A captured output was renamed: recreate the test |
| A new test warns *replaying the datasets does not give the same rows* | A source that was not frozen (a Table input on a real database, a file read by a step in the middle) or a non-deterministic step: freeze it too (`input_steps`) or add a test database |
| `failed` with the same numbers but other decimals | Locale: the test JVM uses `environment.locale`; declare the decimal symbol in the step (see `manual/03-errores-comunes.md`, D3) |
| `error`: *Test database …* | The server is down, the password is missing, or the name is not a test name (see §4) |
| Everything red after `KETTLE_HOME` changes | The test JVM copies the person's `shared.xml` connections and `kettle.properties` at each run: check them, or set `"user_kettle_home": false` and `set_variable` tweaks |
| `error`: *The test does not run: … uses the schema …* / *… names another database* | A step's schema or SQL would leave the test databases (MySQL): use the original database name (it is redirected) or a test database |
| Job test `failed`: *the job did not run the entry 'X'* | The entry is behind a condition that went the other way, or it was skipped; check `case.run.log` |
| `failed`: *the file 'x.csv' was not written by the run* | The writing step is skipped (`skip_step`) or the path resolves elsewhere: check `files[].path` and the tweaks |
