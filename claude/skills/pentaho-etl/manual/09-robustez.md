# 09 · Robustness: loads that tolerate dirty data

How to make a transformation **fault-tolerant** fast and right: every bad row goes to a rejects file with
its reason instead of stopping the load or vanishing, re-running never duplicates rows or moves keys, and a
broken input file proves it. Everything here is taken from `../patrones/06_carga_tolerante/`, which runs
with Kitchen 9.4.0.0-343 three times (clean file, clean file again, broken file) and is checked by
`../patrones/verificar.ps1 -Solo 06`. Tools: `lint_etl robustness=true` (detect), `harden_etl` (apply),
`stress_test` (prove). Words used in the files: `motivo` = reason, `campos_error` = failing fields,
`rechazos` = rejects.

## 1. What "robust" means here

Three guarantees that can be checked, plus two of design:

1. **No bad row stops the load**: the transformation ends with 0 errors whatever the input brings.
2. **No row is lost in silence**: every step that is not *declared* as a dropper hands on as many rows as
   it receives (through its normal output, its error hop or the false branch of its filter).
3. **Every rejected row has a reason**: it lands in a rejects file with a non-empty `motivo`.
4. **Idempotence**: running it again with the same file changes nothing (same rows, same surrogate keys).
5. **The input is a parameter**, so a broken file can be tried without editing the ETL.

The one-line balance you report to the person (pattern 06 with the broken file):
`22 read = 5 input rejects + 1 duplicate (declared) + 1 orphan + 1 fact reject + 14 loaded lines`.

## 2. When

- Before handing in or scheduling a load, and whenever the person says *que no se caiga*, *tolerar
  errores / fallos*, *datos sucios / erróneos*, *rechazos*, *que no duplique al volver a correr*.
- After `lint_etl robustness=true` gives a robustness score below 90.
- When a run aborts on one row ("couldn't convert", "Data truncation", "constraint failed") or the counts
  do not add up (rows read ≠ rows loaded and nobody knows where the rest went).

Do not make a load tolerant by **ignoring** errors (`ignore_errors`, `lenient`, dropping the error hop's
rows in a Dummy): a load that fails is better than one that loses rows without saying so. Tolerant always
means *rejected with a reason, in a file that someone reads*.

## 3. The fast path (live, with the MCP)

1. `lint_etl` with `robustness: true` (the reports' `quality` section runs them always, §9). Besides the
   normal result it returns `robustness: {score, grade, counts, penalties}` and findings of category
   `robustness`; each one names in `evidence.recipe` the `harden_etl` recipe that fixes it and in
   `evidence.anchor` the step. The main score does not change.
2. `harden_etl` (dry run by default): one proposal per planned recipe and step, with `level` and a text
   diff. By default it plans **only the `auto` recipes**; `recipes: ["all"]` plans every recipe (the
   `other_recipes` hint in the result says when there are more).
   - `auto` (R01 error hop to rejects, R02 reason, R11 wait for loads, with a Blocking step in front when
     the lookup and the load share a source) only changes what happens to rows that would abort the run
     today: apply them.
   - `confirm` (R03 required fields, R04 strict conversion, R06 dedupe, R08 orphans to rejects, R12 input
     parameter) changes which rows reach the outputs or the design: **show each one to the person**,
     explain what changes, and pass only the accepted keys in `confirm`.
   - `manual` (R05 safe split, R07 stable keys, R09 Insert/Update, R10 aggregate to the grain, R13
     defaults, R14 job guard) is **never applied**, whatever `confirm` holds: the proposal gives `how_to`
     and you build it by hand with §4 (`semantics_change: true` marks R07, R09 and R10, which change the
     meaning of keys or of the load: ask first). Do not tell the person a load is re-runnable until R07 and
     R09 are really done.
3. `harden_etl dry_run=false confirm=[…]`: what it applies is **one Ctrl+Z** in Spoon (the input parameter
   of R12 is outside the undo, the result says so). Then `validate`.
4. `stress_test`: breaks copies of the file inputs (missing fields, bad dates and numbers, duplicates,
   cut lines, extra separators, too long text, out of range, other encoding, empty file, header only),
   runs a **copy** of the transformation in a separate JVM and checks no abort, the per-step balance and
   the reasons. It runs only when every database is a SQLite/H2 file (copied to a sandbox). With MySQL,
   PostgreSQL… it does not connect: it returns the static checks, and `export_variants: "<folder>"` writes
   the broken files so the person runs them with the input parameter (as with a hand-made broken file).
5. Fix what `stress_test` reports (§9), `lint_etl robustness=true` again (target ≥ 90), `save`.

Learned on real class loads:
- R03 finds the required fields by name from the keys of the loads and lookups. When the input fields
  are renamed on the way (`CustID` → `cod_cliente` in a Select values), it finds none: pass
  `required_fields: {"<input step>": ["field", …]}`. Field names that are not Java identifiers
  (`Fecha de venta`) leave R03 as `manual`: rename them in the input first.
- To run `stress_test` on a MySQL load, copy it with the connection pointing to a SQLite file. SQLite takes
  one writer at a time: if several loads write in parallel, set *Make the transformation database
  transactional* (`unique_connections`) on the copy, or every load stops with "database is locked".
- A file input that reads a field as Number or Date stops the load on a badly written value; R01 gives it
  an error hop (the row arrives with that field null and Kettle's message as the reason).

Without the MCP, build it by hand with the recipes below and prove it with a broken file and Pan/Kitchen
(§8). The pattern is the fastest start: copy `patrones/06_carga_tolerante/` and adapt it.

## 4. The recipes by hand

Names of steps are free; the XML is what PDI 9.4 reads (fragments: the wrapper of every step is in
`01-formato-ktr.md`). Recipe ids are the ones of `harden_etl`.

### R01 `rejects_on_error` — error hop to a rejects file

The step that can fail per row (Select values that converts, Insert / update, Update, Table output on a
database that supports it, Calculator, JavaScript…) gets an error hop. Two pieces: the hop in `<order>`
and the block at the end of the `.ktr`:

```xml
<hop><from>Load dim_customer</from><to>Clean reason customers</to><enabled>Y</enabled></hop>
...
<step_error_handling>
  <error>
    <source_step>Load dim_customer</source_step><target_step>Clean reason customers</target_step>
    <is_enabled>Y</is_enabled><nr_valuename/>
    <descriptions_valuename>motivo</descriptions_valuename>
    <fields_valuename>campos_error</fields_valuename>
    <codes_valuename/><max_errors/><max_pct_errors/><min_pct_rows/>
  </error>
</step_error_handling>
```

The rejected row is the row the step **received** plus `motivo` (Kettle's message) and `campos_error`
(the fields it names; empty for database errors). Leave `max_errors` empty: a limit turns the rejects
back into an abort. An error hop does not count as an output for distribution.

Live: `set_error_handling` (or `harden_etl recipes=[rejects_on_error] steps=[…]`).

### The rejects queue: clean the reason, then write

Kettle's message has several lines and starts with a line break; written as is it breaks the CSV. One
Replace in string with three regex rules on `motivo` (in this order) and one on `campos_error`:

```xml
<fields>
  <field><in_stream_name>motivo</in_stream_name><out_stream_name/><use_regex>yes</use_regex>
    <replace_string>^\s+|\s+$</replace_string><replace_by_string/> ...</field>
  <field><in_stream_name>motivo</in_stream_name><out_stream_name/><use_regex>yes</use_regex>
    <replace_string>\s*[\r\n]+\s*</replace_string><replace_by_string> | </replace_by_string> ...</field>
  <field><in_stream_name>motivo</in_stream_name><out_stream_name/><use_regex>yes</use_regex>
    <replace_string>,\s*$</replace_string><replace_by_string/> ...</field>
  <field><in_stream_name>campos_error</in_stream_name><out_stream_name/><use_regex>yes</use_regex>
    <replace_string>,\s*$</replace_string><replace_by_string/> ...</field>
</fields>
```

(the `...` are `<set_empty_string>N</set_empty_string><replace_field_by_string/><whole_word>no</whole_word>
<case_sensitive>no</case_sensitive><is_unicode>no</is_unicode>`). Several rules on the same field are
applied one after the other. Then a Text file output: `;`, header, UTF-8, `create_parent_folder=Y`,
`append=N`, `do_not_open_new_file_init=N` and **declared fields** (the useful ones of the row + `motivo` +
`campos_error`). With no rejects the file is still created, empty (0 bytes, no header): an empty file
means "nothing rejected" and the next run does not leave yesterday's rejects behind. Pattern 06 writes to
`${Internal.Entry.Current.Directory}/salida/rechazos/rechazos_<flow>.csv`.

**One queue per row layout.** All the rows that reach a step must have the same fields in the same order.
The error rows of a step have *its input* layout + the error fields, so group the sources by layout:
the input checks and the type conversion share one queue (both see the raw String row), each load has its
own, the orphans their own. Never send two layouts to one Text file output.

### R02 `reject_reason` — a reason everywhere

An error hop without `descriptions_valuename` gives rejects without a reason: add `motivo` (and
`campos_error`). The false branch of a filter has no Kettle message: build it with a **User Defined Java
Expression** (Janino; faster than JavaScript and no Rhino):

```xml
<step><name>Reason missing fields</name><type>Janino</type> ...
  <formula><field_name>motivo</field_name>
    <formula_string>"Faltan campos obligatorios: " + (sale_id == null ? "sale_id, " : "") + (customer_id == null ? "customer_id, " : "")</formula_string>
    <value_type>String</value_type><value_length>-1</value_length><value_precision>-1</value_precision><replace_field/></formula>
  <formula><field_name>campos_error</field_name>
    <formula_string>(sale_id == null ? "sale_id, " : "") + (customer_id == null ? "customer_id, " : "")</formula_string>
    <value_type>String</value_type><value_length>-1</value_length><value_precision>-1</value_precision><replace_field/></formula>
```

The trailing `, ` is removed by the cleaning step. Inside `<formula_string>` plain double quotes are valid
XML; `<` and `&` must be escaped (`&lt;`, `&amp;`).

### R03 `required_fields` — check what must be there, right after the input

Read the file with **every field as String** and `trim_type=both` (CSV input), then a Filter rows:

```xml
<send_true_to>Convert types</send_true_to>
<send_false_to>Reason missing fields</send_false_to>
<compare><condition><negated>N</negated><conditions>
  <condition><negated>N</negated><leftvalue>sale_id</leftvalue><function>IS NOT NULL</function><rightvalue/></condition>
  <condition><negated>N</negated><operator>AND</operator><leftvalue>customer_id</leftvalue><function>IS NOT NULL</function><rightvalue/></condition>
</conditions></condition></compare>
```

In Kettle an empty string **is null** (`KETTLE_EMPTY_STRING_DIFFERS_FROM_NULL=N`), and with trim a value
of only spaces too. A cut line (`16;2026-10-06;C03`) arrives with the missing fields null: CSV input does
not fail on short lines, so this filter is what catches it. Required = what a key, a lookup or a NOT NULL
column needs; optional fields get a default instead (R13, If field value is null).

### R04 `strict_conversion` — convert after the checks, not lenient, with an error hop

A typed field in CSV input without an error hop aborts the run. CSV input can have one (pattern 01), but
then the bad value arrives as null in the rejected row (only the message keeps it) and the required-fields
check cannot run before the conversion. Read as String, check (R03), then convert in a Select values
(Meta-data tab) with an error hop (R01): the rejected row keeps the raw text and `campos_error` names the
field.

```xml
<meta><name>sale_date</name><rename>sale_date</rename><type>Date</type><length>-1</length><precision>-1</precision>
  <storage_type/><conversion_mask>yyyy-MM-dd</conversion_mask><date_format_lenient>false</date_format_lenient>
  <date_format_locale/><date_format_timezone/><lenient_string_to_number>false</lenient_string_to_number>
  <encoding/><decimal_symbol/><grouping_symbol/><currency_symbol/></meta>
<meta><name>amount</name><rename>amount</rename><type>Number</type><length>-2</length><precision>-2</precision>
  <storage_type/><conversion_mask>#.##</conversion_mask><date_format_lenient>false</date_format_lenient>
  <date_format_locale/><date_format_timezone/><lenient_string_to_number>false</lenient_string_to_number>
  <encoding/><decimal_symbol>.</decimal_symbol><grouping_symbol/><currency_symbol/></meta>
```

Always declare `decimal_symbol` (and the mask: `#.##` for Number, `#` for Integer) in a Number or Integer
conversion, as pattern 06 does. Left empty, the conversion may use the JVM locale (`,` on a Spanish
machine, the trap of manual 01 §6.3), and `lint_etl` does not check Select values metas for it.

Verified: `2026-02-31` → *couldn't convert string [2026-02-31] to a date using format [yyyy-MM-dd]*,
`mil` → *non-numeric character found at position 1 for value [mil]*, both with `campos_error` = the field.
With lenient on, a wrong date rolls over to another day and nothing fails: that is worse than a reject.

### R05 `safe_split` — no Field splitter on file data (manual)

Field splitter leaves fields empty or shifted when a value has fewer or more parts. Use a Regex evaluation
with one capture group per field and its result field (`<field>_ok`), then a Filter rows on the result:
false → reason "formato inesperado de <field>" → rejects.

### R06 `dedupe_before_load` — one row per natural key, declared

Unique rows (HashSet) by the natural key before every dimension load (the first one wins), and by the
business id before the fact if a line can come twice. Mark it as a deliberate drop (§6):

```xml
<step><name>Remove duplicate customers</name><type>UniqueRowsByHashSet</type> ...
  <store_values>N</store_values><reject_duplicate_row>N</reject_duplicate_row><error_description/>
  <fields><field><name>customer_id</name></field></fields>
  <attributes><group><name>pentaho-live-robust</name>
    <attribute><key>drops</key><value>duplicates</value></attribute>
    <attribute><key>reason</key><value>one row per customer_id before loading the dimension</value></attribute>
  </group></attributes>
```

To keep the duplicates instead, `reject_duplicate_row=Y` + `error_description` + an error hop to a queue
(pattern 02b). In the log the dropped duplicates show as `E=` (rejected), not as errors.

### R07 `stable_surrogate_keys` — re-running never moves a key

An Add sequence with a counter starts at 1 in every run: keys change when the order changes or a row
appears. The pattern 06 chain (no schema change):

```
dedupe → Look up existing key (Database lookup dim_customer: customer_id → existing_sk, no default)
       → Join max (Join rows, no condition) ← Max key (Table input: SELECT COALESCE(MAX(customer_sk), 0) AS max_sk FROM dim_customer)
       → Number new (Add sequence 1..n) → Compute key (Calculator: new_sk = max_sk + n [ADD, remove=Y];
         customer_sk = NVL(existing_sk, new_sk) [NVL]) → Load dim_customer (Insert / update, customer_sk update=N)
```

Existing customers keep their key; a new one gets `max + n` (keys stay unique but can have gaps: n counts
every row of the run). Two runs at the same time could give the same new key: schedule one at a time, or
use Dimension lookup / update with a technical key (pattern 04). The Database lookup reads the table that
the load writes **before** it (the load is downstream): that is wanted, not a race.

### R08 `orphans_to_rejects` — orphans arrive and are rejected with their reason

- Source SQL: `LEFT JOIN`, never an inner `JOIN`, so the orphans reach the ETL (rule
  `inner_join_in_source`).
- Database lookup / Stream lookup **without default** and `eat_row_on_failure=N`, then a Filter rows
  `customer_sk IS NOT NULL`: false → Janino `"El cliente " + customer_id + " no está en dim_customer"` →
  rejects file. Alternative when the orphan must be kept: default 0 and an Unknown member row (pattern 05).
- Verified: the customer whose dimension row was rejected (name too long) makes its sale an orphan, rejected
  with its reason; nothing disappears.

### R09 `idempotent_load` — Insert / update, not insert only

Table output without truncate inserts again on every run. Use Insert / update by the natural key (the
surrogate key `update=N`), or truncate if the table is staging. On SQLite, Table output has **no** error
handling at all (05-patrones.md); Insert / update has it, so it is also the way to get rejects there.
Insert / update does one SELECT per row: slower with millions of rows.

### R10 `aggregate_to_grain` — one row per fact key

Two lines of the same customer and day overwrite each other in an Insert / update keyed by
`(customer_sk, sale_date)`. Group first and declare it:

```xml
<step><name>Aggregate to grain</name><type>MemoryGroupBy</type> ...
  <give_back_row>N</give_back_row>
  <group><field><name>customer_sk</name></field><field><name>sale_date</name></field></group>
  <fields><field><aggregate>amount</aggregate><subject>amount</subject><type>SUM</type><valuefield/></field>
    <field><aggregate>quantity</aggregate><subject>quantity</subject><type>SUM</type><valuefield/></field></fields>
  <attributes><group><name>pentaho-live-robust</name><attribute><key>drops</key><value>aggregate</value></attribute></group></attributes>
```

Ask which aggregation is right (SUM, MAX, the last one): it changes the numbers.

### R11 `wait_for_loads` — lookups see the rows loaded in the same run

All the steps of a transformation start at once. A lookup of `dim_customer` in the fact branch can run
before the dimension load has written anything. Put a Block until steps finish naming the load:

```xml
<step><name>Wait for dim_customer</name><type>BlockUntilStepsFinish</type> ...
  <steps><step><name>Load dim_customer</name><CopyNr>0</CopyNr></step></steps>
```

**Deadlock trap**: if the fact branch and the dimension branch come from the same step (Convert types
copies to both), the waiting step stops reading, its input buffer (`size_rowset`, 10 000 rows) fills, the
common step blocks and the dimension never finishes. Pattern 06 puts a **Blocking step** with *pass all
rows* (`BlockingStep`, `pass_all_rows=Y`, spills to disk) before the wait, and `harden_etl` does the same
when it detects the shared source (`Buffer for <table>` → `Wait for <table>`). Simpler alternative: load the
dimensions in an earlier transformation of the job. With `unique_connections=Y` (one transaction) the
lookup reads the uncommitted rows of the same connection, which is what you want here.

### R12 `input_file_parameter` — the file is a parameter

```xml
<parameters><parameter><name>ARCHIVO_VENTAS</name><default_value>ventas.csv</default_value>
  <description>File to load from datos/</description></parameter></parameters>
...
<filename>${Internal.Entry.Current.Directory}/datos/${ARCHIVO_VENTAS}</filename>
```

Declare the same parameter in the job and pass it to the transformation entry (`pass_all_parameters=Y` +
the parameter row). Then `"/param:ARCHIVO_VENTAS=ventas_rotas.csv"` runs the broken file.

### R13 `default_optional` — tolerate with a default (on request only)

For fields that may be missing and are not keys, If field value is null with a default ("Desconocido",
0) instead of rejecting. Never for keys or required fields.

### R14 `job_guard` — the job around it (manual)

START → create `salida/` → `CREATE TABLE IF NOT EXISTS` → **File exists** `${…}/datos/${ARCHIVO_VENTAS}`
(red → Write to log "input file not found" → Abort) → the transformation → Write to log "rows that could
not be loaded are in salida/rechazos/" → Success; every other red hop → Abort, so Kitchen returns 1 only
when the load really failed (`failure_branch_ends_in_success` checks it). Rejected rows do not fail the job:
the closing log line (and your answer) must say where the rejects files are.

## 5. Rejects files, in short

- One per row layout; `motivo` always, `campos_error` when the error hop gives it.
- Created even when empty; overwritten each run (`append=N`), so they show *this* run's rejects.
- Clean the multi-line message before writing (§4, the queue).
- Somebody must read them: say where they are in the job's log and in your answer to the person.

## 6. Declared drops

Some steps remove rows on purpose: dedupe, a filter whose false rows are really not wanted, aggregation,
sampling. Declare them so the linter and `stress_test` count them as decisions, not losses:

- Kettle attributes on the step, group `pentaho-live-robust`: `drops` = `duplicates` / `filter` /
  `aggregate` / `sample`, and `reason`. They travel inside the `.ktr` (harden_etl writes them on what it
  adds).
- Or `suppress_lint` on `silent_filter_drop` (or `rejects_dead_end`) with the reason: silencing is declaring.
- `stress_test` accepts fewer rows out than in only for declared steps and for steps that aggregate by
  nature (Group by, Memory group by, Unique rows, Join rows…), and reports them ("drops 3, declared:
  duplicates").

## 7. Pitfalls (verified with PDI 9.4)

- **SQLite**: Table output has no error handling (the error hop is ignored and the run aborts): use
  Insert / update. SQLite does not enforce `VARCHAR(n)` nor `NUMERIC(p,s)`: to test "too long" and "out of
  range" use `CHECK (length(name) <= 40)` and `CHECK (amount BETWEEN 0 AND 99999)` (pattern 06); the error
  reaches the rejects as `[SQLITE_CONSTRAINT] Abort due to constraint violation (CHECK constraint failed:
  <table>)`. A CHECK failure inside a transaction (`unique_connections=Y`) rolls back only that statement:
  the rest commits.
- **MySQL / PostgreSQL**: an out-of-range value is an error only in strict mode; to be sure in the source
  query, `CAST(x AS DECIMAL(10,2))` makes an out-of-range value fail (and go to the rejects) instead of
  being cut.
- **Table output with an error hop** (databases that support it): PostgreSQL uses a savepoint per row and
  turns batch inserts off by itself; MySQL keeps batch mode (pattern 01's README).
- **Empty string = null** in Kettle; with trim, spaces too.
- **Lenient conversions** load wrong values without error: always `false`.
- **INNER JOIN in the source** loses the orphans before the ETL: `LEFT JOIN` + R08.
- **Block until steps finish** on a branch that shares its source with the load: deadlock above 10 000
  rows (R11).
- **Stable keys** are not safe with two concurrent runs (R07).
- **Log counters**: `E=` in `(I=, O=, R=, W=, U=, E=)` counts the rows a step **rejected** (error hop,
  duplicates), not run errors; a run with `E>0` on some steps can end OK. `O=` of Insert / update = rows
  inserted, `U=` = updated; on a second identical run both are 0.

## 8. Proving it by hand (no stress_test)

1. Write a broken copy of the input next to the real one (`datos/ventas_rotas.csv` in pattern 06): the good
   lines + one line per kind of breakage: empty required field, impossible date, number in letters, cut
   line, same key with other data, text longer than the column, amount out of range, exact duplicate line,
   spaces around values, an orphan.
2. Run the job twice with the clean file and once with the broken one
   (`"/param:ARCHIVO_VENTAS=ventas_rotas.csv"`): exit 0 every time.
3. Check: the second clean run inserts and updates nothing (`O=0, U=0` on the loads) and the keys are the
   same; with the broken file every broken line is in exactly one rejects file with a reason, the good
   lines are loaded, no existing key changed, and `read = loaded + rejected + declared drops` from the
   step lines of the log.

Pattern 06's numbers: clean → 5 customers, 10 fact rows, 0 rejects; again → same, `O=0 U=0`; broken (22
lines) → 5 input rejects (2 missing fields, 3 conversions), 1 customer rejected by CHECK, 1 orphan, 1 fact
rejected by CHECK, 1 duplicate dropped (declared), 12 fact rows, keys 1-5 unchanged.

## 9. Reading `lint_etl robustness=true` and `stress_test`

| Finding / check | What to do |
|---|---|
| `no_rejects_on_load`, `no_rejects_on_conversion` | R01 (+ R04: read as String, convert in Select values) |
| `rejects_without_reason` | R02 |
| `rejects_dead_end`, `dead_end` | end the branch in a rejects file, or declare the drop |
| `silent_filter_drop` | false branch to the rejects (R02), or declare `drops=filter` |
| `lookup_drops_rows`, `unchecked_lookup`, `inner_join_in_source` | R08 |
| `no_required_check` | R03 |
| `lenient_conversion` | R04 |
| `fragile_split` | R05 |
| `no_dedupe_before_load` | R06 |
| `counter_as_surrogate_key` | R07 |
| `insert_only_load`, `idempotent` (stress_test, rerun) | R09 (and R07 if the keys changed) |
| `fact_not_grouped` | R10 |
| `lookup_before_load_finishes` | R11 |
| `fixed_input_file` | R12 |
| `no_abort` failed | the step and message in the log: a missing error hop (R01) on the step that failed (a typed input: R01, or better R03 + R04) |
| `no_silent_loss` | the step named lost rows: a filter without false branch, a lookup that eats rows, a Dummy at the end of a branch |
| `rejects_have_reason` | R02 on the error hop or the filter that feeds that file |
| `bad_rows_rejected` | a broken value got through: a missing check (R03) or a lenient conversion (R04) |
| `encoding` | set the encoding of the input to the file's real one |
| `environment_problem` | the database file or a table it reads or loads does not exist. Run the job that creates it first, or point the test at a prepared copy. Alone it is not a robustness failure (`no_abort` is `not_evaluated`) |
| `environment_problem` **and** `no_abort` failed | both happened in the same run: a table is missing **and** a bad row aborted another step (the detail names it with the row's error). Fix both: the bad row as in `no_abort` above, the environment as in the row before |

**Verdict** (over all the variants): `fragile` if any check failed or warned, also when the same run had an
environment problem (a bad row is a robustness failure whatever the environment); `not_run` if nothing
failed or warned but some variant could not be judged because of the environment (or no variant ran);
`robust` otherwise. With `fragile` and `environment_problem` at the top, report both: first the bad row,
then what is missing to judge the rest.

The robustness score uses the same formula as the main one (25 / 8 / 2 per error / warning / info, capped
per rule) over these rules only; pattern 06 scores 100.

**In reports** (`build_report`, `04-informes.md`) the `quality` section always runs this family too (no
argument needed; `lint_etl` only with `robustness: true`): a **Robustness** indicator with the sub-score, the
robustness findings in a table of their own, where their missing points come from, and in its data the same
`robustness` block as `lint_etl`. The technical quality score and its counts do not include them. There is
no separate robustness section, and a job shows nothing of this.
