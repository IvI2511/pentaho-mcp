# 14 · Translating to Python: pandas, PySpark, scripts and notebooks

Since 0.8.0 a transformation (`.ktr`) or a job (`.kjb`) can be **translated to Python**: readable pandas or
PySpark code, as a `.py` script or a Jupyter notebook (`.ipynb`). It works like Scratch: the ETL is built with
blocks in Spoon and the translator writes the same logic as code, one block per step, with an explanation of
what each step does and a note wherever Python behaves differently from PDI. The result can be **verified**:
the transformation runs in PDI and the Python runs on the same inputs, and their outputs are compared row by
row, without writing anything to the person's files or databases.
Tools: `translate_to_python`, `translate_coverage`; by hand, Spoon's **IDC** menu > **Translate to Python…**
(*traducir* = translate).

---

## 1. When

- The person asks to *convert*, *translate*, *export* or *pasar* an ETL to Python, pandas, PySpark, a
  notebook or Jupyter (*pásame esta transformación a pandas*, *quiero un notebook de este job*).
- To show a teacher or a team what an ETL does in code, or to move a load to a Python / Spark platform.
- To learn: the notebook explains each step next to its code, with previews of the data.

**Always ask** what the person did not say: a **`.py` script or a `.ipynb` notebook**, and **pandas or
PySpark**. Do not choose for them. pandas is the right default for course-sized data; PySpark is for Big Data
subjects or when the data does not fit in memory.

## 2. What it produces

**A transformation** becomes one program: the steps in execution order (topological order of the hops, ties
broken by position on the canvas), each one a **block** that reads the DataFrames of the steps before it and
leaves its own output in `df_<step>`.

**A job** becomes one script (or notebook) with **a function per entry** (a Transformation entry's function holds
the body of its translation; a sub-job becomes its own set of functions) and a `main()` that follows the job's
hops: success (`if ok:`), failure (`if not ok:`) and unconditional, returning True like Kitchen's exit code 0.
Entries with a translation (Success, Abort, Write to log, Set variables, file and folder entries, SQL, evaluations,
mail, HTTP, shell…) get their code; the rest are MANUAL. Job translations are not verified with the data (only
transformations are), and previews and EDA apply to transformations only.

Each block has a **status**:

| Status | Meaning |
|---|---|
| **exact** | Does the same as PDI with the same data |
| **approximate** | Does the same except for known differences, written as a `NOTE:` (rounding, formats, order, nulls…) |
| **manual** | There is no reasonable translation (custom Java, User Defined Java Class, plugins…): a `# TODO` skeleton with the step settings commented out (never passwords); the rows pass through unchanged so the rest still runs |

The summary at the end, and the tool's response, count the blocks in each status. Which step and entry ids
have a translation: `translate_coverage`, or `docs/traductor-cobertura.md` in the repository (generated from
the translators' registry by the test suite, so it is always current).

### Conventions of the generated code

| Name | What it is |
|---|---|
| `AQUI` | Folder of the script or notebook. It is saved next to the `.ktr`, so it is the folder of the `.ktr`: relative paths keep working |
| `VARS` | Dict with the Kettle variables and parameters used: `"${DIR}/ventas.csv"` becomes `f"{VARS['DIR']}/ventas.csv"`. Each one is `os.environ.get(name, default)`: the parameter's default value, overridable from the environment. `Internal.Entry.Current.Directory` and `Internal.Transformation.Filename.Directory` are `AQUI` |
| `df_<step>` | Main output of each step (identifier without accents or symbols: *Añadir máximo* → `df_anadir_maximo`). A step with several targets (Filter rows true/false, Switch / Case, error hop) has one variable per target |
| `engine_<conn>` | pandas: a SQLAlchemy engine per connection (`mysql+pymysql`, `postgresql+psycopg2`, `mssql+pyodbc`, `oracle+oracledb`, `sqlite`) |
| `jdbc_<conn>` | PySpark: a dict with `url`, `user` and `password` for `spark.read.jdbc` / `.write.jdbc` |
| `PDI_PASSWORD_<CONN>` | Environment variable with the password of a connection (`PDI_PASSWORD_CLUB_DW`). The password of the `.ktr` is **never** written to the code |
| `spark`, `F`, `T`, `Window` | PySpark: the SparkSession created in the preamble, `pyspark.sql.functions`, `pyspark.sql.types`, `Window` |
| `pd`, `np` | pandas, numpy |

Helper functions that a step needs (rounding modes, SCD type 2, insert/update) are defined once, in the
preamble, before the first block.

## 3. The tools

### `translate_to_python`

| Argument | Meaning |
|---|---|
| `document` / `file` | The open transformation or job (default: the active tab), or a `.ktr` / `.kjb` on disk |
| `format` | `py` or `ipynb`. **Ask the person** if they did not say |
| `engine` | `pandas` (default) or `pyspark`. **Ask** if they did not say |
| `language` | `es`, `en` or `pt`: comments of the `.py` and text cells of the notebook (default: Spoon's language) |
| `title`, `author` | Title (default: the name of the transformation or job) and author (default: none) |
| `intro` | The person's own introduction, in Markdown (it goes after the title) |
| `explanations` | One explanation per step: what it does (default true) |
| `previews` | A preview of each DataFrame in the notebook: `head(10)` / `show(10)` (default true) |
| `eda` | Exploratory analysis charts with matplotlib (default false), §4 |
| `notes` | The notes on what differs from PDI (default true) |
| `output` | Where to write. Default: next to the `.ktr`, `<name>.py` / `<name>.ipynb`, and `<name>_pyspark.py` / `<name>_pyspark.ipynb` for PySpark |
| `overwrite` | An existing file is not replaced unless `true` |
| `verify` | Also run PDI and the Python and compare (§6). Suggest it when Python is available |

It returns the `path` written, the counts per status (`exact`, `approximate`, `manual`), the list of steps
(name, type, status, notes) and, with `verify`, the verification result. Tell the person the counts and the
manual and approximate steps with their notes: those are the ones to review.

```json
{"document": "ventas.ktr", "format": "ipynb", "engine": "pandas", "language": "es",
 "title": "Ventas por producto", "author": "Grupo 3", "eda": true, "verify": true}
```

### `translate_coverage`

Which step ids and job-entry ids have a translator, with their family (inputs, outputs, fields, rows and
flows, lookups, scripting, jobs…) and their status for pandas and for PySpark. Use it before promising that an
ETL translates cleanly, or to answer *¿qué steps traduce?*.

### In Spoon

**IDC › Translate to Python…** asks for the format (`.py` script or `.ipynb` notebook), the library (pandas
or PySpark), the language (Spoon's by default), the title, the author, the introduction (several lines), the
options *explanations*, *previews*, *EDA charts*, *notes* and *verify with the data* (enabled only when Python
is found), and the output file (with *Browse…*). After generating it shows the summary (exact / approximate /
to complete, and the verification) with **Open folder** and **Open file**.

## 4. The notebook

1. **Title** cell: title, author, source file, date and coverage (how many steps exact, approximate, manual).
2. The **introduction**, if any.
3. **How to run it**: the `pip install` line with the libraries the code uses (`pandas`, `sqlalchemy`,
   `pymysql`, `openpyxl`, `pyspark`, `matplotlib`…) and the environment variables to set
   (`PDI_PASSWORD_<CONN>`, the parameters in `VARS`).
4. The **preamble**: imports, SparkSession, `AQUI`, `VARS`, connections and helper functions.
5. **Per step**: a Markdown cell (number, name, type, status, what it does, its notes) and a code cell (the
   block plus `df.head(10)` in pandas or `df.show(10)` in PySpark when previews are on).
6. With `eda`: an **Exploratory analysis** section for the inputs and the final outputs: shape, dtypes,
   nulls per column, `describe()`, histograms of the numeric columns and a bar chart of the top categories,
   plus the charts some steps suggest (totals of a Group by, for example). In PySpark the charts use a
   limited `toPandas()` (a sample), never the whole DataFrame.
7. A **summary** table: step, type, status.

The **`.py` script** has the same content as comments: a docstring with title, author, source and coverage,
the how-to-run lines, a comment block per step with its explanation and notes. With `eda` the charts go into
an optional function at the end, off by default (the comment above it says how to turn it on).

The notebook is valid nbformat 4.5 and runs top to bottom with *Run all* (the tests run it with `nbclient`).

## 5. PDI and pandas are not the same

The translator respects PDI's semantics and writes a note when it cannot:

| PDI | Python |
|---|---|
| An empty string is **null** (default `KETTLE_EMPTY_STRING_DIFFERS_FROM_NULL=N`) | Inputs read empty fields as nulls, as PDI does; where a step still compares with `""`, a note says so |
| Integer | nullable `Int64` (pandas) / `LongType` (PySpark) |
| Number | `float` / `DoubleType` |
| BigNumber | `decimal.Decimal` (object column) / `DecimalType` |
| Date | `datetime64[ns]` / `TimestampType` |
| Boolean, String | `boolean` / `BooleanType`, `string` / `StringType` |
| Conversion masks are Java `SimpleDateFormat` and `DecimalFormat` (`dd/MM/yyyy`, `#,##0.00` with decimal and grouping symbols) | Converted to `strftime` patterns and to explicit decimal / thousands handling (Spark date patterns in PySpark) |
| Calculator and Number range rounding modes (`ROUND_HALF_EVEN`, `HALF_UP`…) | A helper with `decimal` for the exact mode (Python's `round()` is half-even, `np.round` too) |
| Sort rows is stable | `sort_values(kind="stable")`; Spark sorts are not stable: a note |
| **Group by** groups **consecutive** rows: it needs sorted input | `groupby` groups globally. When the input is not sorted by the group fields, the results differ: a note (sort first, as PDI needs anyway) |
| Rows sent through an **error hop** | Python stops at the first error: the error target gets an empty DataFrame, with a note |
| A step that **distributes** rows to several targets (round robin) | `iloc[i::n]` / a modulo filter, with a note; *copy* goes the same DataFrame to every target |
| Row-by-row order across parallel branches | Not reproduced: compare by key, not by position |

pandas code works on **pandas 2.x and 3.x** (copy-on-write: no chained assignment; the string dtype);
PySpark code on **3.5+ and 4.x**.

## 6. Verification

`verify: true` (or *verify with the data* in the dialog) checks that the Python does what the transformation
does, **without writing to the person's files or databases**:

1. **PDI** runs the transformation in a separate JVM, like `test_create` (`manual/12-pruebas.md`): every step
   that writes (Table output, Text file output, Insert / Update, dimension lookups…) is skipped and the rows
   it would have written are captured; the final steps are captured too.
2. **Python** runs a variant of the generated code in a temporary folder: blocks that **read** run as they
   are (they only read), blocks that **write** are replaced by a dump of the DataFrame they would have
   written to a CSV in the temporary folder.
3. The two are compared with the golden-test engine (numbers with tolerance, dates as ISO, null vs "").

Per output the result is **equal**, **different** (with the first differing rows: row, column, PDI value,
Python value) or **not verified**, with the reason:

| Not verified because… | What to do |
|---|---|
| No Python found | Install Python 3 or set `PENTAHO_LIVE_PYTHON` to the interpreter (a venv's `python.exe`) |
| pandas (or PySpark) is not installed in that Python | `pip install pandas` / `pip install pyspark`, in the same interpreter |
| A database input without password | Save the password in the connection, or set `PDI_PASSWORD_<CONN>` |
| PDI did not run cleanly | Fix the transformation first (`run_transformation`, `manual/03-errores-comunes.md`) |
| A manual block on the way | Complete the `TODO`; the output after it cannot match |
| Python failed | The error is in the result: often a missing driver (`pymysql`, `psycopg2`) or a file path |

Python: the environment variable `PENTAHO_LIVE_PYTHON`, if set; otherwise `python` on the `PATH`. An
*approximate* step can still verify as equal with the data at hand: the note says when it would not.

## 7. PySpark notes

- The preamble creates the SparkSession; on Windows it binds to `localhost` (`spark.driver.host`,
  `spark.driver.bindAddress`), because a computer name with `_` breaks Spark's driver.
- Spark needs **Java 17 or later**: set `JAVA_HOME` to that JDK before running the script or starting
  Jupyter. PDI 9.4 itself runs on Java 8 or 11; they can live side by side.
- On Windows Spark warns about **`winutils.exe` / `HADOOP_HOME`**. The warning is harmless for reading files
  and for the CSV outputs, which the translator writes through pandas (`toPandas()`); writing with
  `df.write` to a local folder does need `winutils.exe`.
- Spark is slow to start (several seconds): normal. For course-sized data pandas is faster.
- Row order is not guaranteed after a shuffle (joins, group by): sort before comparing or previewing.

## 8. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| A step is *manual* | It has no translator (`translate_coverage`) or it is custom code (Modified Java Script, UDJC): complete the `TODO` from the commented settings |
| `FileNotFoundError` on an input | The script is not next to the `.ktr`, or a variable in `VARS` has no default: set it with an environment variable |
| `KeyError` on a column | The fields of the step could not be computed (a database that was not reachable when translating): check the block against the step |
| A connection fails with an authentication error | `PDI_PASSWORD_<CONN>` is not set in the environment of the script or of Jupyter |
| `Java gateway process exited` (PySpark) | `JAVA_HOME` does not point to a Java 17+ JDK |
| Numbers differ in the last decimal | Rounding mode or float vs BigNumber: read the step's note |
