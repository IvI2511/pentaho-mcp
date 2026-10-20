# 05 · Ready-made patterns (tested ETLs to copy and adapt)

Reference ETLs for the problems that come up in every data-warehouse course and project. Each one is a
folder under `../patrones/` with the `.kjb`/`.ktr` files, small data in `datos/` and a `README.md` (what it
solves, when to use it, parameters, pitfalls, how to verify). They run with Kitchen against a local SQLite
database (the driver ships with PDI) and were **run twice** with Kitchen 9.4.0.0-343 + Java 21, with the
database checked afterwards: `../patrones/verificar.ps1` (Windows) / `verificar.sh` (macOS, Linux) repeat
it and print OK / FALLA per pattern.

**How to use them.** When the user asks for one of these problems, start from the pattern instead of a blank
file: copy the folder, then change the connection, the fields and the table names following the
"Adapting it" section of its README. Keep the structure (job with the DDL first, error branches to Abort,
paths with `${Internal.Entry.Current.Directory}`). Live with the MCP: `open_file` the `.kjb`/`.ktr`, adapt
it with `configure_step`, `save` under a new name.

## Index

| # | Folder | Problem | Key steps | Run | Evidence of the test |
|---|---|---|---|---|---|
| 01 | `01_csv_a_staging/` | Load a CSV into a staging table, repeatably, without letting a dirty row abort the load | CSV input (error hop → rejects file), Get system info, Select values (rename/drop), Table output (truncate) | `01_csv_a_staging.kjb` | 12 rows → 9 in `stg_customers`, 3 in `rechazos_lectura.csv` with line and reason; 2nd run still 9 |
| 02 | `02_deduplicacion/` | One row per key: keep the newest, or the first that arrives | Sort rows + Unique rows (count); Unique rows (HashSet) with duplicates to an error hop | `02_deduplicacion.kjb` | 10 rows → 6 per variant; A keeps the newest version, B the first of the file; 4 duplicates written out |
| 03 | `03_carga_incremental/` | Load only what changed since the last run (watermark by `updated_at`) | Table input + Set variables, Table input with `${VAR}`, Insert / update, SQL job entry | `03_preparar_origen.kjb` (test source), then `03_carga_incremental.kjb` | Run 1: 6 rows; new data; run 2: **4** rows (3 inserts, 1 update); run 3: 0 rows, watermark unchanged |
| 04 | `04_scd_tipo2/` | Dimension with history (SCD type 2): surrogate key, version, valid from/to | Dimension lookup/update (Insert = type 2, Punch through = type 1), Get variables | `04_scd_tipo2.kjb`, params `ARCHIVO_CLIENTES`, `FECHA_CARGA` | Day 1: 5 customers v1; day 2: customer 3 gets v2 and v1 is closed on 2026-10-15; email fixed in place; same snapshot again changes nothing |
| 05 | `05_hecho_con_claves/` | Fact table: natural keys → surrogate keys, unknown member, repeatable load | Calculator, Database lookup, Stream lookup, Insert / update, `unique_connections` | `05_hecho_con_claves.kjb`, param `LOTE` | Batch 1: 6 sales (unknown customer and product → key 0); batch 2: 1 corrected in place + 2 new = 8 |

## Conventions shared by all the patterns

- **The entry point is always a job.** Its first entries create `salida/` (`CREATE_FOLDER`) and the tables
  (`SQL` entry with `CREATE TABLE IF NOT EXISTS`): Table output, Insert / update and Dimension lookup/update
  need the table to exist. That is cleaner than an `ExecSQL` step inside the ktr (no ordering between the
  initialization of steps). Every entry has a red hop to `Abort`, so a failure means Kitchen exit 1.
- **Same connection name in the job and in the ktr** (`dwh`, and `source_db` in 03), declared in both files.
  SQLite files live in `salida/` of the pattern (`.gitignore`); deleting `salida/` resets the pattern.
- Step and entry names in English, unique; file names in Spanish, like the rest of the skill.
- No step has two normal outputs, so no `<distribute>` decision is needed; error hops (declared in
  `<step_error_handling>`) do not count as outputs for distribution.
- **Portable DDL**: `VARCHAR`, `INTEGER`, `NUMERIC(12,2)`, `DATE`/`TIMESTAMP`. The only SQLite-specific
  line is `INTEGER PRIMARY KEY AUTOINCREMENT` in 04 (PostgreSQL: `GENERATED ALWAYS AS IDENTITY` or
  `SERIAL`; MySQL: `INT AUTO_INCREMENT PRIMARY KEY`).

## SQLite with PDI 9.4: what the patterns found out

Verified while testing them; they matter as soon as you use SQLite as the target database:

- **Dates are written as INTEGER milliseconds since 1970** (sqlite-jdbc 3.27's default): read them with
  `datetime(col/1000, 'unixepoch', 'localtime')`. Comparing such a column with a text literal
  (`col > '2026-10-01'`) is false for every row, with no error. The incremental load (03) keeps its
  watermark as ISO text for that reason.
- **Connection options do not work:** `EXTRA_OPTION_SQLITE.date_class=TEXT` is appended to the file name
  (it creates `….sqlite;date_class=TEXT`).
- **Table output has no error handling with SQLite** (`SQLiteDatabaseMeta.supportsErrorHandling()` =
  false): the error hop is ignored and the first constraint violation aborts the run. Insert / update does
  support it. Catch bad rows before the database (01) or use another database.
- **Truncate is `DELETE FROM`.** It works; Table output runs it with the first row.
- **Reading and writing the same SQLite file in one ktr** worked in every test, also with separate
  connections (300 sales, Database lookup + Insert / update on the same file). Pattern 05 still sets
  *Make the transformation database transactional* (`<unique_connections>Y</unique_connections>`): one
  connection and one commit at the end (the transformation is a single transaction). With it, Table
  output does not use batch inserts.
- Dimension lookup/update with `autoinc` works on `INTEGER PRIMARY KEY AUTOINCREMENT` and inserts the
  "unknown" row with key 0 by itself.

## Reading the log of a pattern

Every step ends with a line with its counters; with a Spanish JVM it reads
`Procesamiento finalizado (I=…, O=…, R=…, W=…, U=…, E=…)` instead of `Finished processing (…)`: match the
counters, not the text. `U` is rows updated (Insert / update, Dimension lookup/update), `O` rows written to
the database or file. A step whose counters are all 0 prints that line only at `/level:Detailed`.
