# 01 · CSV → staging

**What it solves.** Loads a delimited file into a staging table so that every run leaves exactly the rows of
the file. A dirty row (an id that is not a number, a date in another format, `12,5O` as an amount) goes to
a rejects file with its line number and the reason instead of aborting the whole load.

**When to use it.** The first hop of almost any ETL: a CSV export, a file from an FTP, a student dataset.
Staging is a 1:1 copy with clean names and types; transformations come later, from staging.

## Files

| File | What it is |
|---|---|
| `01_csv_a_staging.kjb` | **Entry point.** START → create `salida/` → `CREATE TABLE IF NOT EXISTS stg_customers` → the ktr → Success; any failure → Abort (Kitchen exit 1) |
| `01_csv_a_staging.ktr` | CSV input → Get system info (`load_ts`) → Select values → Table output (truncate); error hop of CSV input → Text file output |
| `datos/clientes.csv` | 12 customers with legacy column names (`CUST_ID;CUST_NAME;…;LEGACY_FLAG`), 3 deliberately bad rows |
| `salida/` | Created by the run: `01_staging.sqlite` and `rechazos_lectura.csv` (in `.gitignore`) |

No parameters. Connection `dwh` = SQLite file `salida/01_staging.sqlite`.

## How it works

```
Read customers CSV ──► Add load timestamp ──► Rename to staging columns ──► Load stg_customers
        │ (error hop)                                                          (truncate = Y)
        ▼
Write read rejects  →  salida/rechazos_lectura.csv
```

1. **CSV input** reads typed fields (`Integer`, `Date yyyy-MM-dd`, `Number` with `<decimal>.</decimal>`),
   `trim both`, and adds `source_line` (`<rownum_field>`). Its `step_error_handling` sends the rows it
   cannot convert to the rejects step, with `error_count`, `error_description`, `error_fields`, `error_codes`.
2. **Get system info** adds `load_ts` (`system date (fixed)`: the same value for the whole run).
3. **Select values** maps source names to staging names (`CUST_ID → customer_id`…) and, with
   `select_unspecified=N`, drops everything it does not list (`LEGACY_FLAG`). Table output with
   `specify_fields=N` needs stream names == column names, which is exactly what this step guarantees.
4. **Table output** with `<truncate>Y</truncate>` empties the table and inserts.

## Adapting it

1. The `<connection>` block (in the job **and** in the ktr, same name): type, host, database, user; leave
   the password for the user to type in Spoon.
2. The `<fields>` of CSV input: one per column, in file order, with the right type, `format`, `decimal`/`group`.
3. The pairs of Select values (`<name>` = CSV column, `<rename>` = staging column).
4. The DDL of the SQL entry "Create staging table" (types of your database).
5. The rejects step lists the CSV columns: keep it in sync with step 2.

## Pitfalls (verified with PDI 9.4)

- **SQLite: Table output has no error handling.** `SQLiteDatabaseMeta.supportsErrorHandling()` returns
  `false`, so an error hop on Table output is ignored and the first database error aborts the
  transformation (verified: duplicate primary key → `[SQLITE_CONSTRAINT] Abort due to constraint violation`,
  exit 1, nothing in the rejects file). That is why this pattern catches errors in CSV input, before the
  database. PostgreSQL and MySQL do support it (they inherit `supportsErrorHandling() = true`).
  PostgreSQL uses a savepoint per row and turns batch inserts off by itself when the error hop is on;
  MySQL keeps batch mode (`supportsErrorHandlingOnBatchUpdates() = true`).
- **The bad value is not in the reject row.** The field that could not be converted arrives as null
  (`CUST_ID` empty for `X5`); the original text is only inside `error_description` (`… for value [X5]`).
  If you need the raw value in its own column, read the column as `String` and convert it in Select values
  (Meta-data tab) with the error hop there.
- **`error_description` has line breaks.** Without `<enclosure_forced>Y</enclosure_forced>` in the rejects
  output, one rejected row becomes several CSV lines. With it, the file parses with any CSV reader.
- **`<do_not_open_new_file_init>N</do_not_open_new_file_init>` on purpose:** a run with no rejects still
  rewrites the file (header only). With `Y` the previous run's rejects stay there and look current.
- **`source_line` counts data rows, not file lines** (the header is not counted): `source_line=5` is the
  6th line of the file.
- **Truncate on SQLite is `DELETE FROM`** (`SQLiteDatabaseMeta.getTruncateTableStatement`). Table output
  truncates when the first row arrives (or at the end if none arrives), never during initialization.
- **Dates in SQLite are INTEGER milliseconds since 1970** (sqlite-jdbc's default): `signup_date` of
  customer 1 is `1736895600000`. Read them with `date(signup_date/1000, 'unixepoch', 'localtime')`. Extra
  options do not help: `EXTRA_OPTION_SQLITE.date_class=TEXT` ends up appended to the file name
  (verified: it creates `….sqlite;date_class=TEXT`).
- Without `<decimal>.</decimal>`, `1500.50` is read as `150050` on a Spanish-locale machine with no error
  (`manual/03-errores-comunes.md` D3); `verificar.py` checks for it.

## How to verify

```powershell
..\verificar.ps1 -PdiHome '<…>\data-integration' -Solo 01
```

It runs the job **twice** (the second run must leave the same 9 rows) and checks, among others:

| Check | Expected |
|---|---|
| Log of `Read customers CSV` | `W=9, E=3` (9 good rows, 3 to the error hop) |
| Log of `Load stg_customers` | `O=9, E=0` |
| `SELECT COUNT(*) FROM stg_customers` after two runs | `9` (truncate: not 18) |
| `salida/rechazos_lectura.csv` | 3 rows: `source_line` 5 (`X5`), 6 (`31/03/2025`), 8 (`12,5O`), `error_codes` = `CSVINPUT001` |
| Customer 1 | `balance = 1500.5`, `name = 'Ana Pérez'`; customer 4 trimmed (`'Diego Ruiz'`, `'Bilbao'`) |

By hand: `Kitchen.bat /file:<…>\01_csv_a_staging.kjb /level:Basic` with the recipe of `SKILL.md`, then
`sqlite3 salida/01_staging.sqlite "SELECT customer_id, name, balance FROM stg_customers"`.
