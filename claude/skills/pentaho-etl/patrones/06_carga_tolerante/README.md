# 06 · Fault-tolerant load (rejects with a reason, stable keys, re-runnable)

**What it solves.** A flat sales file loads a small star schema (`dim_customer`, `fact_sales`) and **no
bad row stops the load or vanishes**: missing required fields, impossible dates, numbers in letters, cut
lines, orphan sales and database errors (text too long, amount out of range) each go to a rejects file
with `motivo` (why) and `campos_error` (which fields). Duplicates and the aggregation to the fact grain are
**declared** drops. Running it again with the same file changes nothing: same rows, same `customer_sk`.

**When to use it.** Whenever a load must survive dirty input: a class exercise that must "tolerar fallos y
datos erróneos", a nightly load that cannot stop for one row, any load you will run more than once. The
recipes behind every step are explained in `../../manual/09-robustez.md`; `harden_etl` applies them to an
existing transformation and `stress_test` proves them.

## Files

| File | What it is |
|---|---|
| `06_carga_tolerante.kjb` | Entry point: creates `salida/`, the tables (with `CHECK` constraints), checks that the input file exists (red → log + Abort), runs the ktr, logs where the rejects are. Parameter `ARCHIVO_VENTAS` |
| `06_carga_tolerante.ktr` | The load (26 steps, below) |
| `datos/ventas.csv` | 12 clean lines: 5 customers, 10 (customer, day) pairs |
| `datos/ventas_rotas.csv` | The same 12 + 10 broken on purpose (one per kind of breakage, see "How to verify") |

Output: `salida/06_dwh.sqlite` (connection `dwh`), `salida/rechazos/rechazos_{ventas,clientes,huerfanos,hechos}.csv`
(always created; empty = nothing rejected), `salida/logs/` when run by `verificar.ps1`.

## How it works

```
Read sales (CSV, all String, trim) ─► Required fields? ─false─► Reason missing fields (Janino) ─┐
                                          │true                                                  ├─► Clean reason input ─► Write rejects input
                                          ▼                                                      │
                                     Convert types (Select values, lenient = false) ─error hop──┘
                                          │ copy to both branches
          ┌───────────────────────────────┴───────────────────────────────────┐
          ▼ dimension                                                          ▼ fact
 Remove duplicate customers (HashSet, declared)                      Remove duplicate sales (HashSet, declared)
 Look up existing key (DB lookup → existing_sk)   Max key (SQL)      Buffer sales (Blocking step, pass all rows)
 Join max ◄───────────────────────────────────────┘                  Wait for dim_customer (Block until steps finish)
 Number new (Add sequence) → Compute key (max_sk + n, NVL)           Look up customer_sk (DB lookup, no default)
 Load dim_customer (Insert / update) ─error─► Clean ─► Write         Customer found? ─false─► Reason orphan ─► Write rejects orphans
                                     rejects customers                Aggregate to grain (Memory group by, declared)
                                                                      Load fact_sales (Insert / update) ─error─► Clean ─► Write rejects sales
```

- **Read as String, check, then convert.** A typed CSV input can only reject a line with the bad value
  turned into null (pattern 01). Here every field is a String; `Required fields?` (`IS NOT NULL`, an empty string is null
  in Kettle) catches empty and cut lines, and `Convert types` converts with lenient off and an error hop:
  `2026-02-31` and `mil` are rejected with Kettle's message, they do not abort nor load wrong.
- **One rejects queue per row layout**: the input checks and the conversion share one (both see the raw
  String row), each load has its own, the orphans theirs. `Clean reason …` (Replace in string, 3 regex
  rules) turns Kettle's multi-line message into one line.
- **Stable keys**: an existing customer keeps the key it has in `dim_customer`; a new one gets
  `max(customer_sk) + n`. The Insert / update never updates `customer_sk`. Re-running: same keys.
- **Orphans**: the sale of a customer that is not in the dimension (here C06, rejected by the CHECK of
  `name`) is rejected with "El cliente C06 no está en dim_customer" instead of vanishing.
- **Wait for the dimension** before looking up keys, with a Blocking step first: without it, more than
  10 000 rows (the hop buffer) would deadlock, because both branches come from `Convert types`.
- **Aggregate to the grain** `(customer_sk, sale_date)` before the fact Insert / update, so two lines of
  the same customer and day are added up instead of overwriting each other.
- `unique_connections=Y`: one SQLite connection, one commit at the end; a CHECK failure rolls back only
  that statement and goes to the rejects.

## Parameters

| Name | Default | Meaning |
|---|---|---|
| `ARCHIVO_VENTAS` | `ventas.csv` | File to load from `datos/`; `ventas_rotas.csv` is the dirty-data test |

## Adapting it

1. Change the connection `dwh` in the `.kjb` **and** the `.ktr`, and the DDL of `Create tables` (keep the
   `CHECK`s or use real column lengths on a database that enforces them).
2. Fields: the CSV input reads everything as String; list the required ones in `Required fields?` and in
   the two Janino formulas, and the conversions in `Convert types`.
3. Rejects: one Text file output per row layout, with the fields you want to see + `motivo` +
   `campos_error`. Never send two layouts to the same writer.
4. Each extra dimension: a copy of the dimension branch (dedupe → existing key → max → number → NVL →
   Insert / update), and a lookup + check in the fact branch after `Wait for …` (name every dimension load in
   the Block until steps finish).
5. Keep the declarations (`pentaho-live-robust` attributes on the dedupe and the aggregation): they tell
   `lint_etl robustness=true` and `stress_test` that those drops are on purpose.

## Pitfalls (verified with PDI 9.4)

- SQLite does not enforce `VARCHAR(40)` or `NUMERIC(10,2)`: the `CHECK` constraints are what makes "too
  long" and "out of range" fail (and reach the rejects as `[SQLITE_CONSTRAINT] … CHECK constraint failed`).
- Table output on SQLite has no error handling: the loads are Insert / update (05-patrones.md).
- The rejects files are created at the start of the run even when empty (0 bytes, no header).
- In the log, `E=` is the rows a step rejected (error hop, duplicates), not errors: the run still ends OK.
- New keys can have gaps (n numbers every customer of the run). Do not run two loads at the same time.

## How to verify

`..\verificar.ps1 -PdiHome <data-integration> -Solo 06` (or `verificar.sh 06`) runs the job three times and
checks the database, the rejects files and the log:

| Run | Input | Expected |
|---|---|---|
| 1 | `ventas.csv` | exit 0; `dim_customer` C01-C05 → keys 1-5; 10 rows in `fact_sales` (C01 on 2026-10-01 = 10.50 + 5.25); 4 empty rejects files |
| 2 | `ventas.csv` again | exit 0; nothing inserted or updated (`O=0 U=0` on both loads); same keys |
| 3 | `ventas_rotas.csv` | exit 0; `rechazos_ventas` 5 (13 empty customer, 16 cut line, 14 `2026-02-31`, 15 `mil`, 22 `dos`), `rechazos_clientes` 1 (C06, 60 characters), `rechazos_huerfanos` 1 (sale 18 of C06), `rechazos_hechos` 1 (19, 150000.00); line 20 (= line 2) dropped as a declared duplicate; 17 (same customer with other data) and 21 (spaces) loaded; 12 rows in `fact_sales`; keys 1-5 unchanged |

Balance of run 3: `22 read = 5 input rejects + 1 duplicate + 1 orphan + 1 fact reject + 14 loaded lines`
(`Aggregate to grain` gets those 14 lines + line 19 and gives 13 groups; the group of line 19 is rejected
by the CHECK, the other 12 are the 10 rows already loaded, unchanged, and 2 new ones).
`lint_etl robustness=true` gives this transformation a robustness score of 100.
