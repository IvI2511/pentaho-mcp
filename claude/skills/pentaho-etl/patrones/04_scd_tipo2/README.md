# 04 · Slowly changing dimension, type 2 (Dimension lookup/update)

**What it solves.** A dimension that keeps **history**: when a tracked attribute changes (a customer moves
to another city), the current row is closed and a new version is inserted, each one with its own surrogate
key and its validity range. Facts loaded on a date point to the version valid on that date. Attributes that
are corrections, not history (a mistyped email), are overwritten without creating versions.

**When to use it.** Customer, product, employee or store dimensions in a star schema, whenever a report
must answer "as it was then" (sales by the city the customer lived in *at the time of the sale*). For a
dimension without history (type 1 only) `Insert / update` or `Combination lookup/update` are simpler.

## Files

| File | What it is |
|---|---|
| `04_scd_tipo2.kjb` | **Entry point.** Creates `dim_customer` (+ lookup index) if needed and runs the ktr. Parameters `ARCHIVO_CLIENTES`, `FECHA_CARGA`, passed down to the ktr |
| `04_scd_tipo2.ktr` | CSV input (the full snapshot) → Get variables (`load_date` = `${FECHA_CARGA}` as Date) → Dimension lookup/update `dim_customer` |
| `datos/clientes_dia1.csv` | 5 customers |
| `datos/clientes_dia2.csv` | the same 5 with two changes (customer 2: email fixed; customer 3: Valencia → Barcelona) + customer 6, new |

| Parameter | Default | Meaning |
|---|---|---|
| `ARCHIVO_CLIENTES` | `clientes_dia1.csv` | snapshot to apply, inside `datos/` |
| `FECHA_CARGA` | `2026-10-01` | date the snapshot is valid from (`yyyy-MM-dd`): closes old versions and opens new ones |

Connection `dwh` = `salida/04_dwh.sqlite`.

## The table

| Column | Role | Who fills it |
|---|---|---|
| `customer_sk` | surrogate (technical) key, `INTEGER PRIMARY KEY AUTOINCREMENT` | the database (`creation_method=autoinc`) |
| `version` | 1, 2, 3… per `customer_id` | the step (`<version>`) |
| `valid_from`, `valid_to` | validity range `[from, to)` | the step (`<date><from>/<to>`) |
| `customer_id` | natural (business) key | the stream (`<key>`) |
| `city`, `segment` | **type 2** attributes: `<update>Insert</update>` | a change → new version |
| `name`, `email` | **type 1** attributes: `<update>Punch through</update>` | a change → overwritten in every version |

Change types (codes of `DimensionLookupMeta`): `Insert` = type 2 (new version), `Update` = type 1 only in
the **last** version, `Punch through` = type 1 in **all** versions, plus `DateInsertedOrUpdated`,
`DateInserted`, `DateUpdated`, `LastVersion` (technical columns filled without a stream field).

## How it works

For each incoming row the step looks up the version of `customer_id` valid at `load_date`
(`WHERE customer_id = ? AND ? >= valid_from AND ? < valid_to`) and compares the listed fields:

- not found → inserts version 1 from **1900-01-01** (`min_year`) to **2199-12-31** (`max_year`);
- found, a type-2 field differs → `UPDATE … SET valid_to = load_date` on the current version and inserts
  version + 1 from `load_date` to 2199-12-31, with a **new surrogate key**;
- found, only type-1 fields differ → updates them (Punch through: in all the versions of that customer);
- found, nothing differs → nothing is written.

It returns `customer_sk` on every row, so the same step also serves to look up the key (with `update=N`:
lookup only, what a fact load uses to find the version valid on the sale date).

## Adapting it

1. `<key>` (natural key), the list of `<field>` with their change type, `<return><name>` (surrogate key),
   `<version>` and the two date columns. **Only listed fields are compared**: a column you do not list
   neither versions nor gets updated.
2. The DDL: in PostgreSQL `customer_sk INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY` (or `SERIAL`), in
   MySQL `INT AUTO_INCREMENT PRIMARY KEY`; Spoon's **SQL** button on the step generates it for your
   database. Keep the index on `(natural key, valid_from, valid_to)`.
3. Where the date comes from: here a parameter, so a run is reproducible and a snapshot can be applied
   "as of" any date. With an empty `<date><name>` the step uses the system date.
4. The source must be a **full snapshot** of the dimension (or at least every changed row).

## Pitfalls (verified with PDI 9.4)

- **Two `<update>` tags.** The step-level one (`Y` = lookup + update, `N` = lookup only) and the one inside
  each `<field>` (change type). Swapping them silently changes the behaviour.
- **New members start in 1900, not on the load date:** customer 6, new on day 2, gets `valid_from`
  1900-01-01, so facts dated before its first load still find a version. To start on the load date:
  `<use_start_date_alternative>Y</use_start_date_alternative>` and `<start_date_alternative>` =
  `sysdate` | `trans_start` | `null` | `column_value` (+ `<start_date_field_name>`).
- **The "unknown" row appears by itself:** with `update=Y` the step inserts `customer_sk = 0, version = 1`
  with everything else NULL when the table does not have it (verified: first row of the table). Facts
  whose customer is not found point to it.
- **Rows missing from the snapshot are not closed.** The step only sees the rows that arrive: a customer
  deleted at the source stays open. Detecting deletions needs a separate step (Merge rows against the
  dimension, or a flag in the source).
- `autoinc` on SQLite needs `INTEGER PRIMARY KEY AUTOINCREMENT` (verified: the step inserts with
  `getGeneratedKeys`, log `SQL w/ return keys=[INSERT INTO dim_customer( version, valid_from, …`).
  `tablemax` (MAX + 1) is not safe with two loads at once; `sequence` is for PostgreSQL/Oracle.
- **Dates in SQLite are INTEGER milliseconds.** `valid_from` of a first version is `-2208992400000`
  (1900-01-01 00:00 in Madrid); query them with `datetime(valid_from/1000, 'unixepoch', 'localtime')`.
- The log: `U` counts updates (closing a version and type-1 changes), `O` inserts.

## How to verify

```powershell
..\verificar.ps1 -PdiHome '<…>\data-integration' -Solo 04
```

Three runs: day 1; day 2 with `ARCHIVO_CLIENTES=clientes_dia2.csv FECHA_CARGA=2026-10-15`; day 2 again.
Real result (20-oct-2026, PDI 9.4.0.0-343):

| Run | Log of `Update dim_customer` | `dim_customer` after |
|---|---|---|
| 1 (day 1) | `I=5, O=5, R=5, W=5, U=0` | 6 rows: the unknown row (sk 0) + customers 1-5, version 1, 1900-01-01 → 2199-12-31 |
| 2 (day 2) | `I=6, O=2, R=6, W=6, U=2` | 8 rows. Customer 3: sk 3 v1 Valencia, `valid_to` 2026-10-15; sk 6 v2 Barcelona, 2026-10-15 → 2199-12-31. Customer 2: one version, email `bruno.diaz@correo.example.com`. Customer 6: sk 7, v1 from 1900-01-01 |
| 3 (day 2 again) | `I=6, O=0, R=6, W=6, U=0` | still 8 rows: an unchanged snapshot writes nothing |

```sql
SELECT customer_sk, customer_id, version, city,
       datetime(valid_from/1000, 'unixepoch', 'localtime') AS valid_from,
       datetime(valid_to/1000,   'unixepoch', 'localtime') AS valid_to
FROM dim_customer ORDER BY customer_id, version;
```
