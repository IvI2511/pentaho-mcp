"""Checks of the patterns: queries the SQLite database each pattern leaves in salida/ and the Pan/Kitchen log.

Called by verificar.ps1 / verificar.sh after each run (standard library only, Python 3.8+):

    python -I verificar.py <check> <pattern folder> <kitchen log>

<check> is the pattern number plus the run, e.g. 03-2 = pattern 03 after its second run. Prints one line per
assertion and exits with 1 if any of them fails. Dates written by PDI into SQLite are INTEGER milliseconds
since 1970 (see 05-patrones.md); they are converted with the local time zone, the same one the JVM used.
"""
import csv
import datetime as dt
import os
import re
import sqlite3
import sys

STEP_LINE = re.compile(
    r" - (?P<step>[^\r\n]+?)\.\d+ - [^\r\n(]*\(I=(\d+), O=(\d+), R=(\d+), W=(\d+), U=(\d+), E=(\d+)\)")

failures = 0


def check(condition, message):
    global failures
    print(("  ok    " if condition else "  FALLA ") + message)
    if not condition:
        failures += 1


def step_counts(log_path, step):
    """Counts of the LAST 'finished' line of a step: dict with I, O, R, W, U, E (the line is localized,
    'Finished processing' / 'Procesamiento finalizado', so only the counters are matched)."""
    with open(log_path, encoding="utf-8", errors="replace") as fh:
        text = fh.read()
    found = [m for m in STEP_LINE.finditer(text) if m.group("step").strip() == step]
    if not found:
        return None
    return dict(zip("IORWUE", (int(g) for g in found[-1].groups()[1:])))


def check_step(log_path, step, **expected):
    counts = step_counts(log_path, step)
    if counts is None:
        # A step whose counters are all 0 writes its summary line only at /level:Detailed.
        check(all(v == 0 for v in expected.values()),
              f"'{step}' has no summary line in the log (= processed 0 rows)")
        return
    for key, value in expected.items():
        check(counts[key] == value, f"'{step}' {key}={counts[key]} (expected {value})")


def local_date(millis):
    return dt.datetime.fromtimestamp(millis / 1000).date()


def db(folder, name):
    path = os.path.join(folder, "salida", name)
    if not os.path.exists(path):
        check(False, f"{path} does not exist")
        sys.exit(1)
    con = sqlite3.connect(path)
    con.row_factory = sqlite3.Row
    return con


def read_csv(path):
    with open(path, encoding="utf-8", newline="") as fh:
        return list(csv.DictReader(fh, delimiter=";"))


# ----------------------------------------------------------------------------------------------- 01

def check_01(folder, log):
    con = db(folder, "01_staging.sqlite")
    rows = {r["customer_id"]: r for r in con.execute("SELECT * FROM stg_customers")}
    check(len(rows) == 9 and con.execute("SELECT COUNT(*) FROM stg_customers").fetchone()[0] == 9,
          "stg_customers has 9 rows after two runs (truncate: the load is repeatable)")
    check(sorted(rows) == [1, 2, 3, 4, 7, 9, 10, 11, 12], f"loaded ids {sorted(rows)}")
    check(abs(rows[1]["balance"] - 1500.50) < 1e-9, f"balance of 1 = {rows[1]['balance']} (1500.50, not 150050)")
    check(rows[1]["name"] == "Ana Pérez", "UTF-8 survives in the name of customer 1")
    check(rows[4]["name"] == "Diego Ruiz" and rows[4]["city"] == "Bilbao", "trim both on name and city")
    check(local_date(rows[1]["signup_date"]) == dt.date(2025, 1, 15), "signup_date of 1 = 2025-01-15")
    check(all(r["load_ts"] for r in rows.values()), "load_ts filled in every row")
    cols = [c[1] for c in con.execute("PRAGMA table_info(stg_customers)")]
    check("LEGACY_FLAG" not in cols, "LEGACY_FLAG is not loaded (Select values drops it)")
    rejects = read_csv(os.path.join(folder, "salida", "rechazos_lectura.csv"))
    check(sorted(int(r["source_line"]) for r in rejects) == [5, 6, 8],
          f"rejects file: lines {[r['source_line'] for r in rejects]} (expected 5, 6, 8)")
    check(all(r["error_codes"] == "CSVINPUT001" for r in rejects), "every reject has error_codes CSVINPUT001")
    check_step(log, "Read customers CSV", W=9, E=3)
    check_step(log, "Write read rejects", R=3)
    check_step(log, "Load stg_customers", O=9, E=0)


# ----------------------------------------------------------------------------------------------- 02

def check_02(folder, log):
    con = db(folder, "02_dedup.sqlite")
    latest = {r["customer_id"]: r for r in con.execute("SELECT * FROM customers_latest")}
    first = {r["customer_id"]: r for r in con.execute("SELECT * FROM customers_first_seen")}
    check(sorted(latest) == [1, 2, 3, 4, 5, 6], f"customers_latest: one row per customer ({len(latest)})")
    check(latest[2]["city"] == "Cádiz" and latest[3]["email"] == "carla.nunez@example.com",
          "Sort rows + Unique rows keeps the NEWEST version (2 -> Cadiz, 3 -> carla.nunez@)")
    copies = {k: v["copies_found"] for k, v in latest.items()}
    check(copies == {1: 2, 2: 2, 3: 2, 4: 1, 5: 2, 6: 1}, f"copies_found {copies}")
    check(sorted(first) == [1, 2, 3, 4, 5, 6], f"customers_first_seen: one row per customer ({len(first)})")
    check(first[2]["city"] == "Sevilla" and first[3]["email"] == "carla.old@example.com",
          "Unique rows (HashSet) keeps the FIRST row of the file (2 -> Sevilla, 3 -> carla.old@)")
    dups = read_csv(os.path.join(folder, "salida", "02b_duplicados.csv"))
    check([int(r["customer_id"]) for r in dups] == [1, 3, 2, 5], "02b_duplicados.csv: the 4 discarded rows")
    check_step(log, "Keep first row per customer", R=10, W=6)
    check_step(log, "First seen per customer", R=10, W=6)
    check_step(log, "Write discarded duplicates", R=4)


# ----------------------------------------------------------------------------------------------- 03

def check_03(folder, log, run):
    con = db(folder, "03_dwh.sqlite")
    wm = con.execute("SELECT last_watermark FROM etl_control WHERE table_name = 'orders'").fetchone()[0]
    orders = {r["order_id"]: r for r in con.execute("SELECT * FROM stg_orders")}
    if run == 1:
        check(len(orders) == 6, f"stg_orders has {len(orders)} rows (expected 6: the whole batch 1)")
        check(wm == "2026-10-03 19:45:00", f"watermark = {wm} (max updated_at of batch 1)")
        check_step(log, "Read source delta", W=6)
    elif run == 2:
        check(len(orders) == 9, f"stg_orders has {len(orders)} rows (expected 9 = 6 + 3 new)")
        check(orders[1002]["status"] == "PAID" and orders[1002]["updated_at"] == "2026-10-04 09:00:00",
              "order 1002 was UPDATED (PENDING -> PAID), not duplicated")
        check(wm == "2026-10-05 10:30:00", f"watermark = {wm} (max updated_at of batch 2)")
        check_step(log, "Read source delta", W=4)
        check_step(log, "Upsert stg_orders", R=4)
    else:
        check(len(orders) == 9, f"stg_orders still has {len(orders)} rows")
        check(wm == "2026-10-05 10:30:00", f"watermark unchanged = {wm}")
        check_step(log, "Read source delta", W=0)


# ----------------------------------------------------------------------------------------------- 04

def check_04(folder, log, run):
    con = db(folder, "04_dwh.sqlite")
    rows = [dict(r) for r in con.execute("SELECT * FROM dim_customer ORDER BY customer_sk")]
    by_id = {}
    for r in rows:
        by_id.setdefault(r["customer_id"], []).append(r)
    min_ms = -2177452800000  # 1901-01-01 UTC: anything before it is the 1900-01-01 "min date"
    unknown = by_id.get(None, [])
    check(len(unknown) == 1 and unknown[0]["customer_sk"] == 0,
          "one 'unknown' row with customer_sk 0 (inserted by Dimension lookup/update)")

    def is_open(r):
        return r["valid_to"] is not None and local_date(r["valid_to"]).year == 2199

    if run == 1:
        check(len(rows) == 6, f"dim_customer has {len(rows)} rows (expected 6 = unknown + 5)")
        check(all(len(by_id[i]) == 1 and by_id[i][0]["version"] == 1 for i in range(1, 6)),
              "customers 1-5 have exactly version 1")
        check(all(by_id[i][0]["valid_from"] < min_ms and is_open(by_id[i][0]) for i in range(1, 6)),
              "first versions go from 1900-01-01 to 2199-12-31")
        return
    check(len(rows) == 8, f"dim_customer has {len(rows)} rows (expected 8 = 6 + new version of 3 + customer 6)")
    v = sorted(by_id[3], key=lambda r: r["version"])
    check(len(v) == 2, f"customer 3 has {len(v)} versions (expected 2)")
    if len(v) == 2:
        check(v[0]["city"] == "Valencia" and v[1]["city"] == "Barcelona", "v1 Valencia, v2 Barcelona")
        check(v[0]["valid_to"] == v[1]["valid_from"] and local_date(v[1]["valid_from"]) == dt.date(2026, 10, 15),
              "v1 closed on 2026-10-15 and v2 starts on 2026-10-15")
        check(is_open(v[1]) and not is_open(v[0]), "only v2 is open (valid_to = 2199-12-31)")
        check(v[1]["customer_sk"] > v[0]["customer_sk"], "the new version has a new surrogate key")
    check(len(by_id[2]) == 1 and by_id[2][0]["email"] == "bruno.diaz@correo.example.com",
          "customer 2: email corrected in place (Punch through), still one version")
    check(len(by_id.get(6, [])) == 1 and by_id[6][0]["valid_from"] < min_ms,
          "customer 6 is new: version 1 from 1900-01-01 (no start date alternative)")
    if run == 3:
        # Same snapshot again: everything compared, nothing inserted or updated.
        check_step(log, "Update dim_customer", R=6, O=0, U=0)


# ----------------------------------------------------------------------------------------------- 05

def check_05(folder, log, run):
    con = db(folder, "05_dwh.sqlite")
    fact = {r["sale_id"]: r for r in con.execute("SELECT * FROM fact_sales")}
    check(fact[5001]["customer_sk"] == 11 and fact[5001]["product_sk"] == 21,
          "sale 5001: natural keys (1, P-100) -> surrogate keys (11, 21)")
    check(abs(fact[5001]["amount"] - 9.0) < 1e-9, f"sale 5001 amount = {fact[5001]['amount']} (2 x 4.50)")
    check(fact[5005]["customer_sk"] == 0, "sale 5005: unknown customer 9 -> customer_sk 0, the sale is kept")
    check(fact[5006]["product_sk"] == 0, "sale 5006: unknown product P-999 -> product_sk 0, the sale is kept")
    if run == 1:
        check(len(fact) == 6, f"fact_sales has {len(fact)} rows (expected 6)")
        check_step(log, "Upsert fact_sales", R=6, O=6)
    else:
        check(len(fact) == 8, f"fact_sales has {len(fact)} rows (expected 8 = 6 + 2 new)")
        check(fact[5003]["quantity"] == 5 and abs(fact[5003]["amount"] - 39.5) < 1e-9,
              "sale 5003 corrected in place: quantity 5, amount 39.50")
        check(fact[5007]["customer_sk"] == 12 and fact[5007]["product_sk"] == 21, "sale 5007 -> (12, 21)")
        check_step(log, "Upsert fact_sales", R=3, O=2, U=1)


# ----------------------------------------------------------------------------------------------- 06

# The clean file gives these customers and keys (first appearance order) and 10 rows of fact_sales
# (one per customer and day: lines 1+3 and 11+12 are added up).
KEYS_06 = {"C01": 1, "C02": 2, "C03": 3, "C04": 4, "C05": 5}


_rejects_read = {}


def rejects_06(folder, name):
    """Data rows of salida/rechazos/<name>.csv (the file exists even with no rejects; then it is empty).
    Every row must carry a one-line reason (checked once per file)."""
    if name in _rejects_read:
        return _rejects_read[name]
    path = os.path.join(folder, "salida", "rechazos", name + ".csv")
    if not os.path.exists(path):
        check(False, f"{path} exists (it is created even when nothing is rejected)")
        return []
    with open(path, encoding="utf-8", newline="") as fh:
        rows = list(csv.DictReader(fh, delimiter=";"))
    for r in rows:
        first = next(iter(r.values()))
        motivo = (r.get("motivo") or "").strip()
        check(motivo != "" and "\n" not in motivo and not motivo.startswith("|"),
              f"{name}: row {first} has a one-line reason: {motivo[:70]}")
    _rejects_read[name] = rows
    return rows


def check_06(folder, log, run):
    con = db(folder, "06_dwh.sqlite")
    keys = {r["customer_id"]: r["customer_sk"] for r in con.execute("SELECT * FROM dim_customer")}
    check(keys == KEYS_06, f"dim_customer = {keys} (5 customers, same customer_sk in every run)")
    fact = {(r["customer_sk"], local_date(r["sale_date"]).isoformat()): r for r in con.execute("SELECT * FROM fact_sales")}
    first = fact.get((1, "2026-10-01"))
    check(first is not None and abs(first["amount"] - 15.75) < 1e-9 and first["quantity"] == 2,
          "fact (C01, 2026-10-01) = 10.50 + 5.25, quantity 2: aggregated to the grain before the load")
    names = {n: len(rejects_06(folder, n)) for n in
             ("rechazos_ventas", "rechazos_clientes", "rechazos_huerfanos", "rechazos_hechos")}
    if run in (1, 2):
        check(len(fact) == 10, f"fact_sales has {len(fact)} rows (expected 10)")
        check(abs(sum(r["amount"] for r in fact.values()) - 133.44) < 1e-6, "total amount 133.44")
        check(all(v == 0 for v in names.values()), f"no rejects with the clean file: {names}")
        if run == 1:
            check_step(log, "Load dim_customer", O=5, U=0, E=0)
            check_step(log, "Load fact_sales", O=10, U=0, E=0)
        else:
            # Re-running is idempotent: nothing inserted, nothing updated, the keys did not move.
            check_step(log, "Load dim_customer", O=0, U=0, E=0)
            check_step(log, "Load fact_sales", O=0, U=0, E=0)
        return
    # Run 3: the broken file (the 12 clean lines + 10 broken ones), after two clean runs. Exit 0.
    check(len(fact) == 12, f"fact_sales has {len(fact)} rows (expected 12 = 10 + C01 and C03 on new days)")
    check((1, "2026-10-06") in fact and (3, "2026-10-07") in fact,
          "the good new lines are loaded: 17 (repeated customer with other data) and 21 (spaces)")
    check((2, "2026-10-06") not in fact, "line 19 (amount 150000, out of range) is not in fact_sales")
    check(names == {"rechazos_ventas": 5, "rechazos_clientes": 1, "rechazos_huerfanos": 1, "rechazos_hechos": 1},
          f"rejects per file = {names} (expected ventas 5, clientes 1, huerfanos 1, hechos 1)")
    ventas = {r["sale_id"].strip(): r for r in rejects_06(folder, "rechazos_ventas")}
    check(set(ventas) == {"13", "14", "15", "16", "22"}, f"rejected lines {sorted(ventas)} = 13, 14, 15, 16, 22")
    check(ventas.get("13", {}).get("campos_error") == "customer_id", "line 13: campos_error = customer_id")
    check(ventas.get("14", {}).get("campos_error") == "sale_date", "line 14 (2026-02-31): campos_error = sale_date")
    clientes = rejects_06(folder, "rechazos_clientes")
    check(bool(clientes) and "CHECK" in clientes[0]["motivo"], "C06 (60 characters): rejected by the CHECK of name")
    # Balance of the run, from the log: lines read = rejected + declared drops + loaded.
    read = step_counts(log, "Read sales")
    check(read is not None and read["W"] == 22, f"Read sales wrote 22 rows ({read})")
    check_step(log, "Remove duplicate sales", W=16, E=1)       # line 20 = line 2 again: declared drop
    check_step(log, "Aggregate to grain", R=15, W=13)          # 15 sale lines -> 13 (customer, day)
    check_step(log, "Load fact_sales", O=2, U=0, E=1)          # 2 new, 10 unchanged, 1 rejected
    check_step(log, "Load dim_customer", O=0, U=0, E=1)        # keys of C01..C05 unchanged
    check(22 == 5 + 1 + 1 + 1 + 14, "22 read = 5 input rejects + 1 duplicate + 1 orphan + 1 fact reject + 14 loaded lines")


def main(argv):
    if len(argv) != 4:
        print(__doc__)
        return 2
    name, folder, log = argv[1], argv[2], argv[3]
    pattern, _, run = name.partition("-")
    run = int(run or 1)
    {"01": lambda: check_01(folder, log), "02": lambda: check_02(folder, log),
     "03": lambda: check_03(folder, log, run), "04": lambda: check_04(folder, log, run),
     "05": lambda: check_05(folder, log, run), "06": lambda: check_06(folder, log, run)}[pattern]()
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
