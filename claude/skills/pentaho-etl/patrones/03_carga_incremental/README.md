# 03 · Incremental load by date (watermark)

**What it solves.** Each run loads **only the source rows that changed since the previous run**, instead of
the whole table. A control table keeps the last `updated_at` loaded (the *watermark*); the job reads it,
extracts the window, loads it and, only if all of that worked, moves the watermark forward.

**When to use it.** A source table that grows or changes and has a reliable "last modified" column
(`updated_at`, `fecha_modificacion`, an increasing id). Big tables, frequent runs, or a source you must not
read in full every time. If the source has no such column, a full load + compare (Merge rows) is the
alternative.

## Files

| File | What it is |
|---|---|
| `03_carga_incremental.kjb` | **Entry point, the pattern.** START → create `salida/` → DDL + seed of `etl_control` → `03a` → Write to log (the window) → `03b` → SQL "Save new watermark" → Success; any failure → Abort, watermark untouched |
| `03a_leer_marcas.ktr` | Two flows: `etl_control.last_watermark` → Set variables `LAST_WM`; `MAX(updated_at)` of the source → Set variables `NEW_WM` (both `PARENT_JOB`) |
| `03b_cargar_delta.ktr` | Table input `WHERE updated_at > '${LAST_WM}' AND updated_at <= '${NEW_WM}'` → Insert / update `stg_orders` by `order_id`. Parameters `LAST_WM`, `NEW_WM` (defaults = full load) |
| `03_preparar_origen.kjb` + `03_origen_cargar_lote.ktr` | **Test scaffolding**, not part of the pattern: plays the source application. Parameter `LOTE` loads `datos/pedidos_lote<LOTE>.csv` into `src_orders` (upsert) |
| `datos/pedidos_lote1.csv` | 6 orders, `updated_at` from 2026-10-01 09:15 to 2026-10-03 19:45 |
| `datos/pedidos_lote2.csv` | 3 new orders + order 1002 modified (`PENDING → PAID`, newer `updated_at`) |

Connections: `source_db` = `salida/03_origen.sqlite` (the "operational" system), `dwh` =
`salida/03_dwh.sqlite` (control table + `stg_orders`). Two files on purpose: the watermark lives in the
target, the delta is read from the source, as with two real servers.

## How it works

```
etl_control.last_watermark ──► LAST_WM          MAX(src_orders.updated_at) ──► NEW_WM   (03a, read first)
                       │                                    │
                       └──► SELECT … WHERE updated_at > LAST_WM AND updated_at <= NEW_WM ──► Insert/update (03b)
                                                            │
                                     only on success: UPDATE etl_control SET last_watermark = NEW_WM
```

- The window is **(LAST_WM, NEW_WM]**: exclusive below (the last row loaded is not loaded again), inclusive
  above.
- **NEW_WM is read before extracting.** A row the source writes while the load runs (with a later
  `updated_at`) is outside the window and goes in the next run. Using `MAX(updated_at)` of what was loaded,
  or "now", can skip it forever.
- **The watermark is saved last** and only on the green hop: if `03b` fails, the job aborts and the next run
  retries exactly the same window. The `UPDATE` also has `AND last_watermark < '${NEW_WM}'`: it never moves
  backwards (e.g. if the source is emptied).
- **Insert / update, not Table output:** a modified order comes back with a newer `updated_at`; a plain
  insert would break the primary key (or duplicate the order if there is none).
- The variables travel like this: Set variables (`PARENT_JOB`) → the job → the TRANS entry passes them as
  parameters (`<parameter><name>LAST_WM</name><value>${LAST_WM}</value>`) → `03b` declares them. Run alone
  with Pan, `03b` uses its defaults (`1900-01-01 00:00:00` … `9999-12-31 23:59:59`) = a full load.

## Adapting it

1. The two connections (or one, if source and control table are the same database: then 03a can be a
   single query).
2. Table and column names in `03a`, `03b` and the DDL; the `table_name` key of `etl_control` (one row per
   loaded table: the same control table serves all your incremental loads).
3. In PostgreSQL/MySQL keep `updated_at` and `last_watermark` as `TIMESTAMP`: the literal
   `'2026-10-03 19:45:00'` in the `WHERE` compares as a timestamp. Format of the variable: the format mask
   of the field that reaches Set variables (`use_formatting=Y`).
4. In MySQL/Oracle the seed `INSERT … SELECT … WHERE NOT EXISTS (…)` needs `FROM DUAL` after the `SELECT`.

## Pitfalls (verified with PDI 9.4)

- **SQLite has no date type.** If `updated_at` were read as `Date`, PDI would write it as milliseconds
  since 1970 (an INTEGER in a `TIMESTAMP` column, the text `'1790…'` in a `VARCHAR` one) and
  `updated_at > '2026-10-03 19:45:00'` is false for every row in both cases (checked with `sqlite3`): the
  delta would be **always empty, with no error**. That is why the source and the watermark keep ISO text
  (`yyyy-MM-dd HH:mm:ss`, which sorts like a date).
- **Set variables needs exactly one row** and the variable is not visible in the same transformation
  (`03a` only sets, `03b` uses). `<variable_type>PARENT_JOB</variable_type>`: the job that runs `03a`.
- **Passing the variables explicitly is not required, but keep it:** with the two `<parameter>` removed
  from the TRANS entry, `03b` still received `LAST_WM`/`NEW_WM` from the job (the inherited variable wins
  over the parameter's default; verified: second run with no new data, 0 rows). Listing them documents the
  dependency, and declaring them in `03b` lets you run it alone with `"/param:LAST_WM=…"`.
- **A step with no rows writes no summary line** at `/level:Basic` (PDI logs all-zero counters only at
  Detailed): in the third run `Read source delta` does not appear in the log. `verificar.py` takes that as 0.
- `CURRENT_TIMESTAMP` in SQLite is UTC (`last_run` is two hours behind Madrid in summer).
- `Replace variables in script` (`<variables_active>Y</variables_active>`) in the Table input and
  `useVariableSubstitution=T` in the SQL entry: without them `${LAST_WM}` reaches the database literally.

## How to verify

```powershell
..\verificar.ps1 -PdiHome '<…>\data-integration' -Solo 03
```

Real run (20-oct-2026, PDI 9.4.0.0-343, Java 21):

| Step | Log | Database after |
|---|---|---|
| source batch 1, **run 1** | `Delta window for orders: updated_at > 1900-01-01 00:00:00 AND <= 2026-10-03 19:45:00`; `Read source delta … W=6`; `Upsert stg_orders … O=6, U=0` | `stg_orders` 6 rows; watermark `2026-10-03 19:45:00` |
| source batch 2, **run 2** | `… > 2026-10-03 19:45:00 AND <= 2026-10-05 10:30:00`; `Read source delta … W=4`; `Upsert stg_orders … O=3, U=1` | 9 rows; order 1002 `PAID` (updated, not duplicated); watermark `2026-10-05 10:30:00` |
| no new data, **run 3** | `… > 2026-10-05 10:30:00 AND <= 2026-10-05 10:30:00`; `Read source delta` reads nothing | still 9 rows; watermark unchanged |

By hand, in this order: `03_preparar_origen.kjb "/param:LOTE=1"`, `03_carga_incremental.kjb`,
`03_preparar_origen.kjb "/param:LOTE=2"`, `03_carga_incremental.kjb`; then
`sqlite3 salida/03_dwh.sqlite "SELECT * FROM etl_control"`.
