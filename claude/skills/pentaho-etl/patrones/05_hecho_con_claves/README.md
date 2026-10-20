# 05 · Fact load with surrogate key lookups and upsert

**What it solves.** Loads a fact table of a star schema: every sale arrives with **natural keys**
(`customer_id`, `product_code`) and is stored with the **surrogate keys** of the dimensions
(`customer_sk`, `product_sk`). A key the dimension does not know does not drop the sale: it goes to the
"Unknown" member (key 0). The load is repeatable: a corrected sale overwrites its row instead of being
inserted twice.

**When to use it.** After the dimensions are loaded (pattern 04 or a type-1 load), to fill
`fact_sales`, `fact_orders`… from a file or a staging table.

## Files

| File | What it is |
|---|---|
| `05_hecho_con_claves.kjb` | **Entry point.** Creates `dim_customer`, `dim_product`, `fact_sales` and the two Unknown members (key 0) if needed → seeds the demo dimensions → the ktr. Parameter `LOTE` |
| `05_hecho_con_claves.ktr` | CSV input → Calculator (`amount`) → Database lookup (`customer_sk`) → Stream lookup (`product_sk`, from a Table input of `dim_product`) → Insert / update `fact_sales` by `sale_id` |
| `datos/ventas_lote1.csv` | 6 sales; 5005 has customer 9 (not in the dimension), 5006 product `P-999` (not in the dimension) |
| `datos/ventas_lote2.csv` | sale 5003 corrected (quantity 4 → 5) + 2 new sales |

| Parameter | Default | Meaning |
|---|---|---|
| `LOTE` | `1` | batch to load: `datos/ventas_lote<LOTE>.csv` |

Connection `dwh` = `salida/05_dwh.sqlite`. The entry "Seed demo dimensions" is **scaffolding**: it writes
4 customers (`customer_sk` 11-14) and 4 products (`product_sk` 21-24) so that the surrogate keys are
visibly different from the natural ones. In a real project each dimension has its own ETL and runs first.

## How it works

```
Read sales CSV ─► Compute amount ─► Look up customer_sk ─► Look up product_sk ─► Upsert fact_sales
                                     (Database lookup,       ▲  (Stream lookup)    (Insert / update
                                      dim_customer)          │                      by sale_id)
                                              Read dim_product keys (Table input)
```

- **Database lookup** runs `SELECT customer_sk FROM dim_customer WHERE customer_id = ?` per row, with a
  cache (`<cache>Y</cache>`, size 0 = no limit): each distinct customer is asked once. Good for big
  dimensions of which only a few keys appear in the batch.
- **Stream lookup** reads all of `dim_product` once (Table input) and searches it in memory. Good for small
  or medium dimensions, or when the dimension comes from another database or a file. Remember: `<from>`
  names the lookup step **and** the hop `Read dim_product keys → Look up product_sk` is required.
- Both return **0** when the key is not found (`<default>0</default>`): the Unknown member seeded by the
  DDL. Use `<eat_row_on_failure>Y</eat_row_on_failure>` (Database lookup) only if a sale without a customer
  must really be discarded.
- **Insert / update** by `sale_id`: inserts new sales, updates the changed ones (`<update>Y</update>` on
  every value except the key). Running the same batch twice leaves the same rows.
- **Make the transformation database transactional** (`<unique_connections>Y</unique_connections>`): the
  three steps that touch the SQLite file share one connection and commit once at the end.

## Adapting it

1. One lookup per dimension of the fact: natural key of the stream (`<key><name>`) → column of the
   dimension (`<key><field>`), and the surrogate key in `<value>`. Database lookup: `<name>` in `<key>` is
   the stream field and `<field>` the column; in `<value>`, `<name>` is the column.
2. **SCD type 2 dimension** (pattern 04): look up the version valid on the fact date with
   `DimensionLookup` and `<update>N</update>` (lookup only), the natural key in `<key>` and the fact date
   (`sale_date`) in `<date><name>`. A plain Database lookup by natural key would return any version.
3. The fact key of Insert / update (`<key>`): the natural key of the fact (`sale_id`, or
   `order_id + line`); every measure and foreign key in `<value>` with `<update>Y</update>`.
4. Money: `Number` is a double; for exact amounts use `BigNumber` in CSV input and Calculator.

## Pitfalls (verified with PDI 9.4)

- **Calculator: the decimal field goes in `field_a`.** `MULTIPLY` takes its type from A: `unit_price`
  (Number) × `quantity` (Integer) gives `9.0` for 2 × 4.50; the other way round it rounds the price
  (`manual/03-errores-comunes.md` D4).
- **Insert / update in the log:** `O` = inserted, `U` = updated (run 2: `O=2, U=1`). `R` is every row it
  compared, written or not.
- **The Unknown member must exist before the facts**: the DDL seeds it with
  `INSERT … SELECT 0, … WHERE NOT EXISTS (…)`, idempotent (in MySQL/Oracle add `FROM DUAL`). Without it the
  facts would point to a key 0 that does not exist.
- **Why `unique_connections=Y`:** one connection to the SQLite file instead of three, and one commit at
  the end of the batch: the whole transformation is a single transaction. It is a precaution, not the
  fix for an error seen here: the same load with `N` and 300 sales also finished without a lock. Side
  effect to know: with this option Table output turns batch inserts off (`TableOutput.init`); this
  pattern has no Table output.
- `sale_date` is stored as INTEGER milliseconds in SQLite (see `manual/05-patrones.md`).

## How to verify

```powershell
..\verificar.ps1 -PdiHome '<…>\data-integration' -Solo 05
```

Two runs, `LOTE=1` then `LOTE=2`. Real result (20-oct-2026, PDI 9.4.0.0-343):

| Run | Log | `fact_sales` after |
|---|---|---|
| 1 (`LOTE=1`) | `Look up customer_sk … I=4, R=6`; `Look up product_sk … R=11, W=6`; `Upsert fact_sales … I=6, O=6, U=0` | 6 rows. 5001 → `customer_sk` 11, `product_sk` 21, amount 9.0; 5005 → `customer_sk` **0** (customer 9 unknown); 5006 → `product_sk` **0** (`P-999` unknown) |
| 2 (`LOTE=2`) | `Upsert fact_sales … I=3, O=2, R=3, W=3, U=1` | 8 rows. 5003 quantity 5, amount 39.5 (**updated, not duplicated**); 5007 → (12, 21), amount 27.0 |

Reading the counters: Database lookup read 4 rows from the database for 6 sales (customer 1 came from the
cache the second time, customer 9 returned nothing); Stream lookup `R=11` = 6 sales + the 5 rows of
`dim_product` (Unknown included).

```sql
SELECT f.sale_id, c.name, p.product_name, f.quantity, f.amount
FROM fact_sales f
JOIN dim_customer c ON c.customer_sk = f.customer_sk
JOIN dim_product  p ON p.product_sk  = f.product_sk
ORDER BY f.sale_id;
```
