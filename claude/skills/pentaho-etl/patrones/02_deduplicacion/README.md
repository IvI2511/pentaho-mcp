# 02 · Deduplication

**What it solves.** One row per key from a source that repeats them: an export with exact duplicates, or
with several versions of the same customer. Two variants, because *which* duplicate survives depends on
the step:

| Variant | Steps | Who survives | Needs sorted input | Memory |
|---|---|---|---|---|
| **A** `02a_ordenar_y_unique.ktr` | Sort rows → Unique rows | the first row **of the sort order**: with `updated_at` descending, the **newest** | yes (that is what Sort rows is for) | Sort rows spills to disk: any volume |
| **B** `02b_unique_hashset.ktr` | Unique rows (HashSet) | the first row **in arrival order** (file order) | no | every key seen stays in memory until the end |

## When to use each one

- **Sort rows + Unique rows** when you must choose the survivor (latest `updated_at`, highest amount, a
  non-null email first…: put it as the second sort key), when the volume is large, or when the input
  already comes sorted (a `Table input` with `ORDER BY`: then Unique rows alone is enough). It also counts
  the copies (`count_rows`).
- **Unique rows (HashSet)** when "the first one that arrives" is good enough (or the duplicates are
  exact), the number of distinct keys fits in memory, and you want to keep the original order or avoid the
  cost of a sort. It can send the discarded duplicates to an error hop.
- Exact duplicates only (whole row repeated): list every column as a key (Unique rows also compares the
  whole row when its `<fields>` is empty).
- Sort rows has its own `<unique_rows>Y</unique_rows>`, but it compares **all the sort keys**: sorted by
  `customer_id, updated_at` it only removes rows equal in both. It does not replace Unique rows here.
- In SQL, if the source is a database: `ROW_NUMBER() OVER (PARTITION BY key ORDER BY updated_at DESC) = 1`
  in the Table input is usually the cheapest option.

## Files

| File | What it is |
|---|---|
| `02_deduplicacion.kjb` | **Entry point.** Creates both tables, runs 02a and then 02b |
| `02a_ordenar_y_unique.ktr` | CSV input → Sort rows (`customer_id` asc, `updated_at` **desc**) → Unique rows (`customer_id`, `count_rows=Y` → `copies_found`) → Table output `customers_latest` (truncate) |
| `02b_unique_hashset.ktr` | CSV input → Unique rows (HashSet) (`customer_id`, `store_values=Y`, `reject_duplicate_row=Y`) → Table output `customers_first_seen`; error hop → `salida/02b_duplicados.csv` |
| `datos/clientes_duplicados.csv` | 10 rows, 6 customers: 2 exact duplicates (1, 5) and 2 customers with an older and a newer version (2 moved Sevilla → Cádiz, 3 changed email) |

No parameters. Connection `dwh` = `salida/02_dedup.sqlite`.

## Adapting it

- The key: `<fields><field><name>` of Unique rows / Unique rows (HashSet), and the **first** field of
  Sort rows (same key, same order).
- The survivor (variant A): the second and following fields of Sort rows, with `<ascending>`.
- Both tables have `PRIMARY KEY (customer_id)`: if deduplication ever let a duplicate through, the load
  fails loudly instead of silently keeping two rows.

## Pitfalls (verified with PDI 9.4)

- **Unique rows only compares consecutive rows.** Without the sort it lets duplicates through with no
  error (same family as Group by without sorting, `manual/03-errores-comunes.md` D5).
- **The HashSet survivor is the first row of the file, not the newest**: with this data, customer 2 keeps
  `Sevilla` and customer 3 keeps `carla.old@example.com` in `customers_first_seen` (variant A keeps
  `Cádiz` and `carla.nunez@example.com`).
- **`store_values=Y` in the HashSet variant.** It keeps the key values to compare them. With `N` only the
  hash code is compared (`RowKey.equals` returns `true` without looking at the values), so two different
  keys with the same hash code are taken as one and a row is lost. More memory, but correct.
- **`E` of the HashSet step counts each rejected row twice:** the run shows
  `First seen per customer … W=6, E=8` for the 4 rows that reach the duplicates file (`R=4`). Count the
  rows of the error target, not `E`.
- `reject_duplicate_row=Y` without an error hop: the duplicates simply disappear. With the hop they go to
  the file with the text of `<error_description>`.
- `count_rows` (variant A) emits each row when its group ends: the field holds the total of the group
  (`copies_found`: 2 for customers 1, 2, 3, 5; 1 for 4 and 6).

## How to verify

```powershell
..\verificar.ps1 -PdiHome '<…>\data-integration' -Solo 02
```

| Check | Expected |
|---|---|
| `customers_latest` | 6 rows; customer 2 in `Cádiz`, customer 3 with `carla.nunez@example.com`; `copies_found` 2,2,2,1,2,1 |
| `customers_first_seen` | 6 rows; customer 2 in `Sevilla`, customer 3 with `carla.old@example.com` |
| `salida/02b_duplicados.csv` | 4 rows: customers 1, 3, 2, 5 (in file order), `error_description = duplicate customer_id` |
| Log | `Keep first row per customer … R=10, W=6`; `First seen per customer … R=10, W=6`; `Write discarded duplicates … R=4` |
