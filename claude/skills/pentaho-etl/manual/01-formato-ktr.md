# 01 · Transformation format (.ktr) — PDI 9.4

Manual for an AI that has to **hand-write** `.ktr` files that run with Pan (and open in Spoon) on the first try. Everything marked "verified" was tested with Pan 9.4.0.0-343 + Java 21 on this machine; everything marked "samples" comes from inspecting the 189 `.ktr` files in `data-integration/samples/`; everything marked "code" comes from disassembling the classes in `lib/kettle-engine-9.4.0.0-343.jar` with `javap`.

Complete working examples: `ejemplos/01…07_*.ktr`. Copy from there rather than inventing.

---

## 0. Quick checklist (read it before generating anything)

1. First line: `<?xml version="1.0" encoding="UTF-8"?>` and the file **saved as UTF-8** (with or without BOM, both work). If you save it as ANSI/cp1252 and it contains accented characters, Pan blows up (`Invalid byte 2 of 2-byte UTF-8 sequence`).
2. Root `<transformation>`. Child order as Spoon writes it: `info`, `notepads`, `connection`*, `order`, `step`*, `step_error_handling`, `slave-step-copy-partition-distribution`, `slave_transformation`, `attributes`.
3. **Every `<step>` carries `<copies>1</copies>`**. If it is missing, the step ends up with 0 copies and Pan aborts with `Unable to prepare and initialize this transformation` (exit 3). It is the only "obvious" tag that has no useful default.
4. **Every `<step>` carries `<GUI><xloc>…</xloc><yloc>…</yloc><draw>Y</draw></GUI>`**. Without `<draw>Y</draw>` the step still runs in Pan but stays **hidden** in Spoon (the code does `"Y".equalsIgnoreCase(draw)`; absent = not drawn).
5. Step names must be **unique** and **exactly identical** (case, spaces, accents) in `<hop><from>/<to>`, in `send_true_to`, in Stream lookup's `<from>`, in Merge join's `step1/step2` and in `step_error_handling`. A duplicated name makes one of the two steps disappear silently (the hops resolve to the other one), and a circular hop **hangs Pan forever**. Neither of the two raises an error.
6. Escape `<` `>` `&` inside values: `&lt;` `&gt;` `&amp;`. Typical case: `<function>&lt;=</function>` in Filter rows.
7. File paths: always `${Internal.Entry.Current.Directory}/…` (it resolves to the `.ktr`'s folder, both with Pan and when called from a job). Never bare relative paths: they are resolved against the PDI installation folder.
8. Give numeric fields explicit `<decimal>` and `<group>`. With an empty `<decimal/>` the machine's locale is used (here it is Spanish): `2.50` is read as **250** without any error.
9. In Text file output **declare the fields** with `<length>-1</length>`. With an empty `<fields>` it writes everything, but it pads Strings to their declared length (with spaces), and with a SQLite source it even blows up with `OutOfMemoryError`.
10. Every participating step must be joined by enabled hops. A "loose" step in a transformation that has hops **is not executed** (no warning).

---

## 1. Minimal skeleton

### 1.1 The bare minimum Pan accepts (verified, NOT recommended)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<transformation>
  <info><name>min</name></info>
  <order>
    <hop><from>Generar</from><to>Nada</to></hop>
  </order>
  <step>
    <name>Generar</name>
    <type>RowGenerator</type>
    <copies>1</copies>
    <fields><field><name>x</name><type>Integer</type><nullif>7</nullif></field></fields>
    <limit>3</limit>
  </step>
  <step>
    <name>Nada</name>
    <type>Dummy</type>
    <copies>1</copies>
  </step>
</transformation>
```

It runs fine (`Nada.0 - Finished processing (I=0, O=0, R=3, W=3 …)`). Pan does not complain even without `<info>`. But in Spoon the steps show up hidden (no `draw`) and stacked at (0,0). **Use it only for quick tests.**

### 1.2 Recommended template (the one the examples use)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<transformation>
  <info>
    <name>mi_transformacion</name>
    <description>Una línea</description>
    <extended_description>Texto largo opcional</extended_description>
    <trans_version/>
    <trans_type>Normal</trans_type>
    <directory>/</directory>
    <parameters>
    </parameters>
    <size_rowset>10000</size_rowset>
    <sleep_time_empty>50</sleep_time_empty>
    <sleep_time_full>50</sleep_time_full>
    <unique_connections>N</unique_connections>
    <feedback_shown>Y</feedback_shown>
    <feedback_size>50000</feedback_size>
    <using_thread_priorities>Y</using_thread_priorities>
    <shared_objects_file/>
    <capture_step_performance>N</capture_step_performance>
    <step_performance_capturing_delay>1000</step_performance_capturing_delay>
    <step_performance_capturing_size_limit>100</step_performance_capturing_size_limit>
    <dependencies>
    </dependencies>
    <partitionschemas>
    </partitionschemas>
    <slaveservers>
    </slaveservers>
    <clusterschemas>
    </clusterschemas>
    <created_user>-</created_user>
    <created_date>2026/10/20 10:00:00.000</created_date>
    <modified_user>-</modified_user>
    <modified_date>2026/10/20 10:00:00.000</modified_date>
    <key_for_session_key/>
    <is_key_private>N</is_key_private>
  </info>
  <notepads>
  </notepads>
  <!-- <connection>…</connection> goes here, if needed -->
  <order>
    <!-- hops -->
  </order>
  <!-- steps -->
  <step_error_handling>
  </step_error_handling>
  <slave-step-copy-partition-distribution>
  </slave-step-copy-partition-distribution>
  <slave_transformation>N</slave_transformation>
  <attributes/>
</transformation>
```

---

## 2. `<info>`

| Tag | Required? | Notes |
|---|---|---|
| `name` | Recommended | Logical name (it appears in the log: `<name> - Dispatching started…`). It does not have to match the file name. If it is missing, the log uses the path. |
| `description`, `extended_description` | No | Free text. Escape `<`, `>`, `&`. |
| `trans_version` | No | Empty. |
| `trans_type` | No | `Normal` (default). `SerialSingleThreaded` also exists; do not use it. |
| `trans_status` | No | Appears in 84/189 samples (`0`). Can be omitted. |
| `directory` | No | `/` (repository directory; irrelevant for files). |
| `parameters` | No | Named parameters, see §8. |
| `log` | No | Huge block of log tables (`trans-log-table`, `perf-log-table`, …). Spoon always writes it; **omit it**: Pan does not need it and Spoon fills in defaults when opening the file. |
| `maxdate` | No | Legacy. Omit it. |
| `size_rowset` | No | Size of the buffer between steps. Default 10000. |
| `sleep_time_empty`, `sleep_time_full` | No | 50. |
| `unique_connections` | No | `Y` = a single connection/transaction per database for the whole transformation. |
| `feedback_shown`, `feedback_size` | No | How often (every N rows) it logs "linenr". |
| `using_thread_priorities` | No | `Y`. |
| `shared_objects_file` | No | Empty. |
| `capture_step_performance` and related | No | `N`. |
| `dependencies`, `partitionschemas`, `slaveservers`, `clusterschemas` | No | Empty. |
| `created_*`, `modified_*` | No | Format `yyyy/MM/dd HH:mm:ss.SSS`. Spoon writes them with entities (`2011&#x2f;12&#x2f;06`); raw text works too. |
| `key_for_session_key`, `is_key_private` | No | Empty / `N`. |

---

## 3. `<notepads>` (notes on the canvas)

```xml
<notepads>
  <notepad>
    <note>Texto de la nota.
Puede tener saltos de línea literales.</note>
    <xloc>64</xloc>
    <yloc>16</yloc>
    <width>380</width>
    <heigth>40</heigth>          <!-- yes: "heigth", with the typo. "height" is ignored -->
    <fontname/>
    <fontsize>-1</fontsize>
    <fontbold>N</fontbold>
    <fontitalic>N</fontitalic>
    <fontcolorred>0</fontcolorred><fontcolorgreen>0</fontcolorgreen><fontcolorblue>0</fontcolorblue>
    <backgroundcolorred>255</backgroundcolorred><backgroundcolorgreen>205</backgroundcolorgreen><backgroundcolorblue>112</backgroundcolorblue>
    <bordercolorred>100</bordercolorred><bordercolorgreen>100</bordercolorgreen><bordercolorblue>100</bordercolorblue>
    <drawshadow>Y</drawshadow>
  </notepad>
</notepads>
```

Spoon saves line breaks as `&#xa;`; a literal line break works too (example 01). Notes do not affect execution.

---

## 4. `<order>` and `<hop>`

```xml
<order>
  <hop>
    <from>Leer clientes</from>
    <to>Activos con saldo</to>
    <enabled>Y</enabled>
  </hop>
</order>
```

- `from`/`to`: exact step name. If `to` (or `from`) does not exist: `java.lang.NullPointerException: Cannot invoke "org.pentaho.di.trans.step.StepMeta.isMapping()" because "nextStep" is null` (exit 2).
- `enabled`: defaults to **Y** if missing (code). With `N` the hop does not exist for execution purposes, and if a step is left without enabled hops **it is not executed**.
- A step with several output hops **copies** or **distributes** rows depending on the source step's `<distribute>` (see §5). Exceptions: Filter rows/Switch case use their own targets; error hops are declared in `step_error_handling`.
- Several **input** hops into the same step mix their rows: they must all have the **same layout** (same fields, same order and type). Otherwise it fails further down with odd errors (`Field [x] couldn't be found in the input stream!`); with `/safemode` Pan detects it: `We detected rows with varying number of fields, this is not allowed in a transformation.` To join different streams use Merge join / Stream lookup / Join rows; to stack identical streams, Append streams or direct hops.
- 2-input steps with an "info stream" (Stream lookup, Merge join, Merge rows, Append…), in addition to the hops, **name the other step inside their XML** (`<from>`, `<step1>/<step2>`…). Hop and name must match.
- Duplicate hops (same from/to twice): Pan ignores them without error (verified). Do not generate them anyway.
- **There is no cycle detection**: a hop that goes backwards makes rows circulate forever (verified: 300,000 rows in 6 min until I killed it).
- The order of the `<hop>` and `<step>` elements in the file does not matter (in the samples the steps appear in alphabetical order; the examples are in flow order and work just the same).

---

## 5. `<step>`

### 5.1 Common tags (all steps)

```xml
<step>
  <name>Leer clientes</name>
  <type>CsvInput</type>
  <description/>
  <distribute>Y</distribute>
  <custom_distribution/>
  <copies>1</copies>
  <partitioning>
    <method>none</method>
    <schema_name/>
  </partitioning>

  <!-- ... tags specific to the step type (see catalogo/) ... -->

  <attributes/>
  <cluster_schema/>
  <remotesteps>
    <input>
    </input>
    <output>
    </output>
  </remotesteps>
  <GUI>
    <xloc>96</xloc>
    <yloc>112</yloc>
    <draw>Y</draw>
  </GUI>
</step>
```

| Tag | Required | Default if missing (code) | Notes |
|---|---|---|---|
| `name` | **Yes** | — | Unique within the transformation. May contain spaces, accents, parentheses. Escape `&`, `<`. |
| `type` | **Yes** | — | Plugin ID, **case-sensitive** (`CsvInput`, `TextFileOutput`, `FilterRows`, `SelectValues`, `SortRows`, `GroupBy`, `StreamLookup`, `MergeJoin`, `RowGenerator`, `Constant`, `Dummy`…). A nonexistent ID does not fail on load: it creates a "missing" step and at execution time gives `Can't run transformation due to plugin missing` (exit 3). ID catalog → `../catalogo/`. Watch out: the ID is not always the GUI name ("Add constants" = `Constant`, "Generate rows" = `RowGenerator`, "Modified Java Script Value" = `ScriptValueMod`, "Get variables" = `GetVariable`, "Write to log" = `WriteToLog`). |
| `description` | No | empty | |
| `distribute` | No | **N** (= copy) | `Y` = distributes rows round-robin across several output hops; `N` = copies each row to all of them. Spoon writes `Y` by default. It only matters if the step has more than one "normal" output hop. |
| `custom_distribution` | No | empty | |
| `copies` | **Yes in practice** | **0** → the transformation does not initialize | Always `1`. More than 1 = parallelism (rows distributed across copies; breaks the order). |
| `partitioning` | No | `none` | |
| `attributes` | No | | Spoon 9.x writes `<attributes/>`. |
| `cluster_schema`, `remotesteps` | No | | Empty. |
| `GUI/xloc`, `GUI/yloc` | Not for Pan | 0,0 | Icon position in Spoon (top-left corner). |
| `GUI/draw` | Not for Pan, **Yes for Spoon** | **N** (hidden) | Always `Y`. |

### 5.2 Layout (samples convention)

Measured over the 785 hops in the samples:

- Horizontal spacing between connected steps on the same row: almost always **128–192 px**, the most common being **160** (77 hops), then 144 and 128. Values are multiples of 16 (Spoon "snaps" to a 16 px grid).
- Vertical spacing between branches: **80–112 px** (96 the most common).
- Minimum coordinates ~32; 80% of the steps fall between x=97–622 and y=76–400.

Convention for generating:

- Main flow from **left to right** at `yloc=112`, `xloc = 96, 256, 416, 576, 736…` (increments of 160).
- Secondary branches (the "false" branch of a filter, the lookup stream, an error hop) **below**, at `yloc = 224` (increment of 112), aligned in x with the step they come out of or go into.
- Second input stream of a join: below the first one, at the same x, with the join centered between both (see example 05: inputs at y=112 and y=224, join at y=160).
- Notes on top (`yloc` 16–48).

---

## 6. Field declaration

Many steps (CSV input, Text file input/output, Generate rows, Add constants, Get variables, Select values "meta", Data grid…) have a `<fields><field>…</field></fields>` block. The tags vary a bit per step, but the core is the same:

```xml
<field>
  <name>saldo</name>
  <type>Number</type>
  <format>#.##</format>       <!-- conversion mask text<->value -->
  <currency/>                 <!-- currency symbol -->
  <decimal>.</decimal>        <!-- decimal symbol: DECLARE IT -->
  <group/>                    <!-- thousands symbol: DECLARE IT (empty = none) -->
  <length>-1</length>         <!-- -1 = no length -->
  <precision>-1</precision>   <!-- -1 = no precision -->
  <trim_type>both</trim_type> <!-- none | left | right | both -->
</field>
```

### 6.1 Types (`<type>`) — exact names

| Type | Java | Use |
|---|---|---|
| `String` | String | Text. |
| `Integer` | Long (64 bits) | Integers. |
| `Number` | Double | Floating-point decimals. |
| `BigNumber` | BigDecimal | Exact decimals (amounts). |
| `Date` | java.util.Date | Date with ms precision. |
| `Timestamp` | java.sql.Timestamp | Date-time with nanos. |
| `Boolean` | Boolean | Read from the text `Y`/`N` (Kettle's code also accepts `true`/`yes`/`1`); in Text file output it comes out as `Y`/`N` (verified). |
| `Binary` | byte[] | Blobs. |
| `Internet Address`, `Serializable` | | Rare. |

A misspelled type (`Int`, `integer`, `Decimal`…) does not fail on load: the field is left without a type and the step fails with `Please set a value for the missing field(s) type.` Respect the capitalization.

### 6.2 `format` (mask)

- Dates: `java.text.SimpleDateFormat` patterns: `yyyy-MM-dd`, `dd/MM/yyyy`, `yyyy-MM-dd HH:mm:ss`, `yyyy-MM-dd HH:mm:ss.SSS`, `yyyyMMdd`. `MM` = month, `mm` = minutes.
- Numbers: `java.text.DecimalFormat` patterns: `#`, `0`, `0.00`, `#,##0.00`, `#.##`. In the mask, `.` and `,` are **positions** (decimal/thousands); the actual character read/written comes from `<decimal>` and `<group>`. Verified example (02): mask `#,##0.00` + `<decimal>,</decimal><group>.</group>` writes `1.500,50`.
- In inputs, the mask is used to **parse**; in outputs, to **format**. In an output, if you set no mask the value's "native" format is used (risk of odd notation); always set `0`, `0.00`, `yyyy-MM-dd`, etc.

### 6.3 `decimal` and `group` — the locale trap

If `<decimal>` is empty, Kettle uses the symbols of the **JVM locale**. On this machine (es_ES) the decimal symbol is `,` and the thousands symbol is `.`, so the CSV value `2.50` was read as **250** — with no warning (verified, output ×100). With the JVM in English the same file gives 2.5. Rule: **on every Number/BigNumber/Integer field read from text, declare explicit `<decimal>` and `<group>`**.

### 6.4 `length` and `precision`

- `-1` = undefined. It is what you want almost always.
- In inputs, `length` on a String is carried along as metadata. In **Text file output** the length of the output field (or the inherited one if you do not declare fields) is used to **pad with spaces** (`Ana Pérez                                         ;`). With a SQLite query whose TEXT comes with a length of 2³¹, the padding requested a gigantic array: `java.lang.OutOfMemoryError: Requested array size exceeds VM limit`. Rule: in outputs, declare every field with `<length>-1</length>`.
- `precision` on a Number = decimals for rounding/formatting. In Select values `-2` means "do not change".

### 6.5 `trim_type`

`none`, `left`, `right`, `both`. In inputs `both` is advisable, to strip spaces.

### 6.6 Per-step quirks (verified)

- **Generate rows (`RowGenerator`) and Add constants (`Constant`)**: the field value goes in **`<nullif>`** (yes, the tag is named that even though it is the "Value"). `<set_empty_string>Y</set_empty_string>` + `<nullif/>` = empty string instead of null. These values do **not** substitute variables: `${X}` comes out literally.
- **Text file output**: the file is `<file><name>` **without extension** + `<extention>csv</extention>` (with the typo "extention"). `<create_parent_folder>Y</create_parent_folder>` creates the folder; with `N` and a nonexistent folder: `Error creating output file! Parent directory [...] does not exist.` `<do_not_open_new_file_init>Y</do_not_open_new_file_init>` avoids creating the empty file if no rows arrive. `<format>UNIX</format>` (LF) or `DOS` (CRLF). `<encoding>UTF-8</encoding>`.
- **CSV input (`CsvInput`)**: the file goes in `<filename>` (not in `<file><name>` as in Text file input). `<lazy_conversion>N</lazy_conversion>` so it converts types while reading (with `Y` the conversion is deferred and errors show up in another step). `<header>Y</header>` skips the first line (log: `Header row skipped in file …`).
- **Calculator**: the type of the calculation is decided by **field A**. `MULTIPLY` with an Integer `field_a` and a Number `field_b` converts B to an integer **by rounding**: 3 × 2.50 gave 9 (= 3 × 3), with no error. Put the Number/BigNumber in `field_a` (verified in example 03).
- **Filter rows**: condition in `<compare><condition>`. Simple: `leftvalue` + `function` + (`rightvalue` = another field **or** a constant `<value>`). Compound: a `<condition>` with `<conditions>` inside and `<operator>AND|OR</operator>` from the second subcondition on. Functions: `=`, `&lt;&gt;`, `&lt;`, `&lt;=`, `&gt;`, `&gt;=`, `REGEXP`, `IS NULL`, `IS NOT NULL`, `IN LIST`, `CONTAINS`, `STARTS WITH`, `ENDS WITH`, `LIKE`, `TRUE`. Empty `send_true_to`/`send_false_to` = rows that do not match are discarded and rows that match continue through **all** output hops.
- **Select values**: `<fields>` contains, in this order, the `<field>` entries to select (with `<rename>`), `<select_unspecified>N</select_unspecified>`, the `<remove>` entries and the `<meta>` entries (type/format change). A nonexistent field in `<field>` gives `Couldn't find field 'x' in row!`.
- **Merge join**: fields of the second stream whose names clash come out with the suffix `_1` (`cliente_id_1`).

---

## 7. `<connection>` (databases)

It goes at root level, **after `<notepads>` and before `<order>`**. Steps reference it by name (`<connection>sqlite_demo</connection>`).

```xml
<connection>
  <name>sqlite_demo</name>
  <server/>                          <!-- host; empty for embedded SQLite/H2 -->
  <type>SQLITE</type>                <!-- type ID, see the list below -->
  <access>Native</access>            <!-- Native (JDBC) | JNDI | ODBC -->
  <database>${Internal.Entry.Current.Directory}/salida/demo.sqlite</database>
  <port>-1</port>                    <!-- port; -1 if not applicable -->
  <username/>
  <password/>                        <!-- plain text or "Encrypted 2be98…" -->
  <servername/>
  <data_tablespace/>
  <index_tablespace/>
  <attributes>
    <attribute><code>PORT_NUMBER</code><attribute>-1</attribute></attribute>
    <attribute><code>PRESERVE_RESERVED_WORD_CASE</code><attribute>Y</attribute></attribute>
    <attribute><code>SUPPORTS_BOOLEAN_DATA_TYPE</code><attribute>Y</attribute></attribute>
    <attribute><code>SUPPORTS_TIMESTAMP_DATA_TYPE</code><attribute>Y</attribute></attribute>
    <attribute><code>USE_POOLING</code><attribute>N</attribute></attribute>
  </attributes>
</connection>
```

- Verified with SQLite (example 07): the variable in `<database>` is resolved and the `.sqlite` file is created on connecting.
- Available `<type>` IDs (from `kettle-database-types.xml`): `AS/400`, `CACHE`, `DB2`, `DBASE`, `DERBY`, `EXASOL4`, `EXTENDB`, `FIREBIRD`, `GENERIC`, `GREENPLUM`, `INFINIDB`, `SQLBASE`, `H2`, `HYPERSONIC`, `INFOBRIGHT`, `INFORMIX`, `INGRES`, `VECTORWISE`, `INTERBASE`, `KINGBASEES`, `LucidDB`, `MARIADB`, `MONETDB`, `MSACCESS`, `MSSQLNATIVE`, `MSSQL`, `MYSQL`, `MONDRIAN`, `NEOVIEW`, `NETEZZA`, `ORACLE`, `ORACLERDB`, `POSTGRESQL`, `REDSHIFT`, `REMEDY-AR-SYSTEM`, `SAPDB`, `SQLITE`, `SYBASE`, `SYBASEIQ`, `TERADATA`, `UNIVERSE`, `VERTICA`, `VERTICA5`, `GOOGLEBIGQUERY`, `SNOWFLAKEHV`, `AZURESQLDB`.
- JDBC drivers present in `lib/`: `h2-2.1.210`, `sqlite-jdbc-3.27.2.1`, `postgresql-42.2.23`, `hsqldb-2.3.2`, `derby`. There is **no** MySQL/MariaDB, SQL Server or Oracle driver: you have to copy the driver's `.jar` into `lib/`.
- Passwords: they can be left in plain text or obfuscated with `Encr.bat -kettle <clave>` (`<clave>` = the password) → it prints `Encrypted 2be98afc86aa7f2e4cb0aab73cc97bbd5` (this is reversible obfuscation, not encryption). When generating a `.ktr`, do not write real passwords: leave `<password/>` empty and let the user enter it themselves in the connection dialog in Spoon.
- With `<access>JNDI</access>` (what almost all the samples use) the connection details come from `simple-jndi/jdbc.properties`; for generated files prefer `Native`.

---

## 8. Parameters and variables

### 8.1 Declaring parameters

```xml
<info>
  …
  <parameters>
    <parameter>
      <name>SALDO_MINIMO</name>
      <default_value>1000</default_value>
      <description>Saldo mínimo para pasar el filtro</description>
    </parameter>
  </parameters>
```

They are passed with Pan as `"/param:SALDO_MINIMO=2000"` (see the quoting notes in `03-errores-comunes.md`), or from a job (the TRANS § in `02-formato-kjb.md`). If they are not passed, `default_value` applies. Verified in examples 01 and 06.

### 8.2 Using variables

- Syntax `${NOMBRE}` (there is also `%%NOMBRE%%`, Windows style; `SortRows` uses `%%java.io.tmpdir%%` by default).
- They are substituted **only in the fields that Spoon marks with the `$` symbol** (file paths, SQL if `variables_active=Y`, database names, the `<variable>` of Get variables, log messages…). **Not** in: values of Generate rows/Add constants, Filter rows constants, field names. Verified: `${ARCHIVO_SALIDA}` in a `<nullif>` of Add constants came out literally.
- To compare against a parameter inside the flow: a **Get variables** step (`GetVariable`) that creates a field holding the value of `${PARAM}` (typed), followed by Filter rows comparing field against field (`<rightvalue>umbral</rightvalue>`). Example 06.
- Nonexistent variable: it stays **literal** in the path and the path is resolved against the PDI folder: `Could not read from "file:///C:/…/data-integration/${CARPETA_DATOS}/clientes.csv" because it is not a file.`
- Global variables: `KETTLE_HOME/.kettle/kettle.properties` (`NOMBRE=valor`). To test without touching the user's configuration, point `KETTLE_HOME` to a folder of your own.

### 8.3 Useful internal variables (verified values)

| Variable | With Pan (standalone ktr) | ktr called from a job |
|---|---|---|
| `${Internal.Entry.Current.Directory}` | `file:///C:/…/carpeta-del-ktr` | `file:///C:/…/carpeta-del-ktr` (the **ktr**'s folder, not the job's; verified with a job in a different folder) |
| `${Internal.Transformation.Filename.Directory}` | `file:///C:/…/carpeta-del-ktr` | same |
| `${Internal.Transformation.Filename.Name}` | `e37_valores_internos.ktr` | same |
| `${Internal.Job.Filename.Directory}` | **`Parent Job File Directory`** (literal text, useless) | the job's folder |
| `${user.dir}` | PDI installation folder (`…\data-integration`) | same |
| `${KETTLE_HOME}` | the configured KETTLE_HOME | same |

Conclusions:

- Use **`${Internal.Entry.Current.Directory}`** in `.ktr` and `.kjb` files: it is the one that works in both contexts. `Internal.Transformation.Filename.Directory` also works in a ktr (the samples use it); `Internal.Job.Filename.Directory` **never** inside a ktr.
- The values are VFS URIs (`file:///…`, spaces as `%20`). They work in every PDI file step, but they are **not** usable as-is to pass to a shell script.
- Separator: `/` works on Windows. The samples also use `${file.separator}` or `\`.

---

## 9. Per-step error handling (`<step_error_handling>`)

It diverts the rows that fail in a step to another step instead of aborting. Two pieces, both mandatory:

1. A **normal hop** from the source step to the error step, in `<order>`.
2. An `<error>` in `<step_error_handling>` (at root level, after the `<step>` elements):

```xml
<step_error_handling>
  <error>
    <source_step>Leer clientes con errores</source_step>
    <target_step>Escribir errores</target_step>
    <is_enabled>Y</is_enabled>
    <nr_valuename>num_errores</nr_valuename>              <!-- field with the number of errors -->
    <descriptions_valuename>descripcion_error</descriptions_valuename>
    <fields_valuename>campos_error</fields_valuename>
    <codes_valuename>codigos_error</codes_valuename>
    <max_errors/>            <!-- empty or -1 = no limit -->
    <max_pct_errors/>
    <min_pct_rows/>
  </error>
</step_error_handling>
```

- Verified (example 06): CSV input with 3 bad rows (id `X4`, balance `no-es-numero`, date `30/09/2023`) → 3 rows to the error step (`E=3`), the rest continue. Exit 0.
- The error row carries **all the source fields** (those that could not be converted are null) **plus** the 4 diagnostic fields. `descripcion_error` is multi-line (e.g.: `fecha_alta String : couldn't convert string [30/09/2023] to a date using format [yyyy-MM-dd] on offset location 2`), `codigos_error` = `CSVINPUT001`.
- Not every step supports error handling. Verified here: CSV input. According to the GUI, it is also available in Text file input, Select values, Calculator, Table output and Insert/update, among others; Filter rows does not need it (it has true/false). Before using it on another step, confirm it in `../catalogo/`.
- Without error handling, the first bad row aborts the step and the transformation: `There were 1 conversion errors on line 2` (exit 1).

---

## 10. Encoding, escapes and XML

- Spoon writes many characters as numeric entities (`&#x2f;` = `/`, `&#x24;&#x7b;` = `${`, `&#x3b;` = `;`, `&#x22;` = `"`, `&#x20ac;` = €, `&#xa;` = line break). **It is not necessary**: raw XML in UTF-8 works the same (all the examples are raw, with `ñ`, accented characters and `€`).
- The only mandatory thing is escaping `&` → `&amp;`, `<` → `&lt;` (and, for tidiness, `>` → `&gt;`). Exact error messages otherwise:
  - `<function><=</function>` → `[Fatal Error] :188:24: The content of elements must consist of well-formed character data or markup.`
  - `Pérez & Cía` → `The entity name must immediately follow the '&' in the entity reference.`
- Tab separator in CSV: write it as an entity, `<separator>&#x9;</separator>` (I did not verify other forms).
- The `encoding` of data files (CSV/Text) is independent of the `.ktr`'s encoding: declare `<encoding>UTF-8</encoding>` in inputs and outputs.

---

## 11. Templates for the steps used in the examples

I do not repeat each step's XML here: it is complete and verified in `ejemplos/`. Map:

| Step (GUI) | `type` | Example |
|---|---|---|
| Generate rows | `RowGenerator` | 01 |
| Add constants | `Constant` | 01 |
| Text file output | `TextFileOutput` | 01–07 |
| CSV file input | `CsvInput` | 02–06 |
| Filter rows (compound condition, true/false) | `FilterRows` | 02 |
| Filter rows (field against field, no targets) | `FilterRows` | 06 |
| Select values (select + rename + meta) | `SelectValues` | 02 |
| Select values (remove) | `SelectValues` | 05 |
| Dummy | `Dummy` | 02 |
| Calculator | `Calculator` | 03 |
| Sort rows | `SortRows` | 03, 05 |
| Group by | `GroupBy` | 03 |
| Stream lookup | `StreamLookup` | 04 |
| Merge join | `MergeJoin` | 05 |
| Get variables | `GetVariable` | 06 |
| Table input + connection | `TableInput` | 07 |

For the rest of the steps (IDs and tags) → `../catalogo/`.
