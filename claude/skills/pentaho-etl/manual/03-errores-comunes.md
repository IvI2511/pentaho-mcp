# 03 · Common errors (triggered and verified)

Every case in this file was triggered on purpose by breaking one of the examples and running it with Pan/Kitchen 9.4 + Java 21. The messages are **verbatim** (long paths are cut with `…`). The broken variants were left in the session scratchpad; to reproduce one, apply the indicated change to the corresponding example.

About language: step messages almost always come out **in English** even when the JVM is in Spanish; what changes is the "frame" (`Unexpected error` → `Error desconocido`, `Error initializing step` → `Error inicializando paso`, `Transformation detected one or more steps with errors.` → `Transformación detectada …`). For 100 % English logs: `PENTAHO_DI_JAVA_OPTIONS=-Xms1024m -Xmx2048m -Duser.language=en -Duser.country=US` (this is what `ejemplos/correr.ps1 -Ingles` does).

---

## A. Observed exit codes

| Exit | Pan | Kitchen |
|---|---|---|
| 0 | Finished without errors | Finished OK |
| 1 | Error in some step during execution; also a nonexistent `.ktr` file or malformed XML | The job finished with an error (`Kitchen - ERROR … : Finished with errors`) |
| 2 | Unexpected error while preparing (e.g. hop to a nonexistent step → NullPointerException) | — |
| 3 | `Unable to prepare and initialize this transformation` (step without `copies`, nonexistent step type, invalid value in Generate rows…) | — |
| 7 | Empty `/file:` → `ERROR: No repository provided, can't load transformation.` It is also the "normal" code of `/listparam` (lists and exits without running) | Same with `/listparam` |
| 8 | `Command line option not understood` (typical: `/param:X=1` without quotes from cmd) | — |
| 9 | Pan with no arguments (prints the options help) | — |

**Important:** exit 0 does **not** guarantee that the result is correct. See section D (silent errors).

---

## B. Environment errors (Windows / launchers)

### B1. `"Spoon.bat" no se reconoce como un comando interno o externo`

(Spanish-locale Windows for "'Spoon.bat' is not recognized as an internal or external command".)

```
Pan.bat : "Spoon.bat" no se reconoce como un comando interno o externo,
programa o archivo por lotes ejecutable.
```
**Cause:** the environment variable `NoDefaultCurrentDirectoryInExePath` is set (Claude Code sets it in its shells). `Pan.bat` does `call Spoon.bat …` without a path, and with that variable cmd does not search the current folder.
**Fix:** in the same process, before calling Pan/Kitchen: PowerShell `Remove-Item Env:NoDefaultCurrentDirectoryInExePath -ErrorAction SilentlyContinue`; cmd `set NoDefaultCurrentDirectoryInExePath=`.

### B2. Pan "finishes fine" (exit 0) even though it failed

**Cause:** from PowerShell, `& 'Pan.bat' …` always leaves `$LASTEXITCODE = 0` (Pan's code is lost when the `.bat` exits).
**Fix:** invoke it with `call` inside cmd: `& cmd.exe /c call $bat @argumentos` → `$LASTEXITCODE` carries the real code (verified: 3 instead of 0). From a `.cmd`: `call Pan.bat … & echo %ERRORLEVEL%`.

### B3. `Command line option not understood` (exit 8) when passing parameters

```
Pan - ERROR (version 9.4.0.0-343, …) : Command Line Options
Pan - ERROR (version 9.4.0.0-343, …) : Command line option not understood
```
**Cause:** cmd splits the arguments of a `.bat` at `=` (also at `,` and `;`). `/param:SALDO_MINIMO=2000` arrives as two arguments.
**Fix:** **literal** quotes around the whole argument: `"/param:SALDO_MINIMO=2000"`. In PowerShell: `('"/param:' + $p + '"')`. It also works with spaces in the value (`"/param:ARCHIVO_SALIDA=prueba param"`, verified).

### B4. Java 21

No fix was needed: the 9.4 `Spoon.bat` detects that it is not Java 8 and adds `--add-opens java.base/java.net=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/sun.net.www.protocol.jar=ALL-UNNAMED` and `-Djava.locale.providers=COMPAT,SPI` on its own. A harmless warning appears: `WARNING: COMPAT locale provider will be removed in a future release`. Startup ≈ 7–10 s per run.

### B5. Nonexistent file

```
Processing has stopped because of an error:
The transformation path C:\no\existe.ktr is invalid, and will not run successfully.
```
Exit 1. If the path ended up empty (`/file:` with nothing after it): `ERROR: No repository provided, can't load transformation.` (exit 7).

---

## C. Format errors that Pan detects

### C1. Misspelled step type (`<type>AddConstants</type>` instead of `Constant`)

```
Agregar constantes.0 - ERROR (…) : Can't run transformation due to plugin missing
Agregar constantes.0 - ERROR (…) : Error initializing step [Agregar constantes]
01_generar_constantes - ERROR (…) : Step [Agregar constantes.0] failed to initialize!
Unable to prepare and initialize this transformation
```
Exit 3. **Fix:** use the exact plugin ID (case-sensitive) → `../catalogo/`. In jobs the equivalent (`<type>EXITO</type>`) gives `Success - ERROR (…) : Can't run job due to plugin missing` (exit 1).

### C2. Hop to a nonexistent step (`<to>Escribir CSV2</to>`)

```
java.lang.NullPointerException: Cannot invoke "org.pentaho.di.trans.step.StepMeta.isMapping()" because "nextStep" is null
```
Exit 2, without saying which hop. **Fix:** every `from`/`to` must match a `<step><name>` character by character. Validate this before running. In jobs: `Cannot invoke "org.pentaho.di.job.JobHopMeta.isUnconditional()" because "hi" is null` (exit 1).

### C3. Step without `<copies>`

```
Unable to prepare and initialize this transformation
```
Exit 3, with no further detail (with `/level:Detailed` you can see it stops at `Allocating rowsets for step 0 --> Generar`). **Fix:** `<copies>1</copies>` on every step.

### C4. Nonexistent field in Select values (`<name>provincia</name>`)

```
Elegir columnas.0 - ERROR (…) : Couldn't find field 'provincia' in row!
java.lang.NullPointerException: Cannot load from object array because "rowData" is null
```
Exit 1.

### C5. Nonexistent field in Filter rows (`<leftvalue>balance</leftvalue>`)

```
Activos con saldo.0 - Fields 'balance' used in the condition are not found in input from previous steps
```
Exit 1.

### C6. Nonexistent field in Text file output

```
Escribir totales.0 - Field [unidades_vendidas] couldn't be found in the input stream!
```
Exit 1.

### C7. Nonexistent field in Sort rows

```
Ordenar por producto.0 - The field articulo specified in the "Ordenar por producto" step is not in the steps input stream.
```
Exit 1.

**Fix for C4–C7:** trace the field name back to its origin (CSV header, Select values `<rename>`, Group by `<aggregate>`, Calculator `field_name`, Stream lookup `<rename>`, Merge join `_1` suffix). Names are case-sensitive.

### C8. Malformed XML: unescaped `<` (`<function><=</function>`)

```
[Fatal Error] :188:24: The content of elements must consist of well-formed character data or markup.
Processing has stopped because of an error:
Unable to read file [file:///C:/…/e08_xml_lt_sin_escapar.ktr].
Error reading information from input stream
… org.xml.sax.SAXParseException; lineNumber: 188; columnNumber: 24; The content of elements must consist of well-formed character data or markup.
```
Exit 1. **Fix:** `<function>&lt;=</function>`.

### C9. Malformed XML: unescaped `&` (`Pérez & Cía`)

```
[Fatal Error] :214:24: The entity name must immediately follow the '&' in the entity reference.
```
Exit 1. **Fix:** `Pérez &amp; Cía`.

### C10. `.ktr` saved in ANSI/cp1252 with accented characters

```
[Fatal Error] :98:23: Invalid byte 2 of 2-byte UTF-8 sequence.
```
Exit 1. **Fix:** save as UTF-8 (with or without BOM; with BOM it works, verified).

### C11. Misspelled field type (`<type>Int</type>`)

```
Generar filas.0 - Please set a value for the missing field(s) type.
```
Exit 1. **Fix:** exactly `String`, `Integer`, `Number`, `BigNumber`, `Date`, `Timestamp`, `Boolean`, `Binary`.

### C12. Invalid value for the type in Generate rows (`Integer` with `cuarenta`, Spanish for "forty")

```
Generar filas.0 - ERROR (…) : Couldn't parse Integer field [entero] with value [cuarenta] --> org.pentaho.di.core.exception.KettleValueException:
… couldn't convert String to number : non-numeric character found at position 1 for value [cuarenta]
Generar filas.0 - ERROR (…) : Error initializing step [Generar filas]
Unable to prepare and initialize this transformation
```
Exit 3.

### C13. Nonexistent input file (CSV input)

```
Leer clientes.0 - An error occurred while creating field mapping
Leer clientes.0 - Could not read from "file:///C:/…/datos/clientes_NO_EXISTE.csv" because it is not a file.
… java.io.FileNotFoundException: C:\…\clientes_NO_EXISTE.csv (El sistema no puede encontrar el archivo especificado)
```
Exit 1. (The OS message in parentheses is Spanish-locale Windows for "The system cannot find the file specified".)

### C14. Nonexistent variable in a path (`${CARPETA_DATOS}/clientes.csv`)

```
Could not read from "file:///C:/pdi/data-integration/${CARPETA_DATOS}/clientes.csv" because it is not a file.
```
Exit 1. The variable stays literal and the path is resolved against the PDI folder. **Fix:** declare it as a parameter with a default, pass it with `/param:`, or define it in `kettle.properties`.

### C15. Relative path without a variable (`datos/clientes.csv`)

```
Could not read from "file:///C:/pdi/data-integration/datos/clientes.csv" because it is not a file.
```
Exit 1. It is resolved against the installation folder (`user.dir`), not against the folder of the `.ktr`. **Fix:** `${Internal.Entry.Current.Directory}/datos/clientes.csv`. In a job, a bare path to a ktr gives `The transformation path 01_generar_constantes.ktr is invalid, and will not run successfully.`

### C16. Date format different from the file's (mask `dd/MM/yyyy`, data `2023-01-15`)

```
Leer clientes.0 - There were 1 conversion errors on line 2
fecha_alta String : couldn't convert string [2023-01-15] to a date using format [dd/MM/yyyy] on offset location 4
```
Exit 1. **Fix:** the correct mask, or `step_error_handling` to divert the bad rows (example 06).

### C17. Wrongly declared decimal symbol (`<decimal>,</decimal>` with data `2.50`)

```
Leer ventas.0 - There were 1 conversion errors on line 2
precio_unitario String : couldn't convert String to number : non-numeric character found at position 2 for value [2.50]
```
Exit 1. (Careful: with an **empty** `<decimal/>` it does not fail, see D3.)

### C18. Stream lookup with a `<from>` that does not match the lookup step

```
Buscar cliente.0 - ERROR (…) : No lookup step specified.
Buscar cliente.0 - ERROR (…) : Unable to read data from lookup-stream.
```
Exit 1. **Fix:** `<from>` = exact name of the step that comes in through the second hop.

### C19. Merge join whose `step2` has no hop into the join

```
Unir clientes y ventas.0 - Unable to find specified source step with name 'Ordenar ventas'.
```
Exit 1. **Fix:** `step1`/`step2` must be exactly the two steps with an incoming hop into the Merge join.

### C20. `send_true_to` points to a step with no hop from the filter

```
Activos con saldo.0 - Step [Escribir filtrados] is invalid as target.
```
Exit 1. **Fix:** `send_true_to`/`send_false_to` can only name steps to which the Filter rows step has a direct hop.

### C21. Nonexistent output folder with `create_parent_folder=N`

```
Escribir CSV.0 - Error creating output file! Parent directory [file:///C:/…/no_existe] does not exist.
```
Exit 1. **Fix:** `<create_parent_folder>Y</create_parent_folder>`.

### C22. Two streams with different layouts entering the same step

Without safe mode it fails wherever the mismatch shows up (here, in the output): `Escribir ventas con cliente.0 - Field [venta_id] couldn't be found in the input stream!` (exit 1). With `/safemode`:

```
org.pentaho.di.core.exception.KettleRowException:
We detected rows with varying number of fields, this is not allowed in a transformation.  The first row contained 8 fields, another one contained 6 : [cliente_id Integer], [nombre String], …
```
**Fix:** all hops entering the same step (except the info streams of lookups/joins) must carry the same fields in the same order. To debug, run with `/safemode`.

### C23. Text file output with no declared fields + a source with huge lengths (SQLite)

```
Escribir consulta.0 - ERROR (…) : UnexpectedError:
Escribir consulta.0 - ERROR (…) : java.lang.OutOfMemoryError: Requested array size exceeds VM limit
```
Exit 1. The output pads each String to its metadata length; the SQLite driver reports TEXT with a length of ~2³¹. **Fix:** declare the output fields with `<length>-1</length>`.

### C24. Job without a valid START

```
10_job_secuencia - ERROR (…) : A serious error occurred during job execution:
10_job_secuencia - Couldn't find starting point in this job.
Kitchen - ERROR (…) : Finished with errors
```
Exit 1. It happens if the entry is not literally named `START`, or if it lacks `<draw>Y</draw>` (even when it has `<start>Y</start>`). **Fix:** `<name>START</name><type>SPECIAL</type><start>Y</start><draw>Y</draw>`.

### C25. Job that calls a nonexistent ktr

```
Ventas por producto - ERROR (…) : Unable to run job 10_job_secuencia. The Ventas por producto has an error. The transformation path file:///C:/…/03_NO_EXISTE.ktr is invalid, and will not run successfully.
```
The entry fails; if there is a red hop, execution continues along it (in the example, to `Abortar`), exit 1.

### C26. Ktr that fails inside a job

You see the step error (e.g. C7) and then `03_ventas_por_producto - ERROR (…) : Errors detected!`; the TRANS entry ends with `result=[false]` and follows the red hop if there is one. Without a red hop: `Kitchen - ERROR (…) : Finished with errors`, exit 1.

---

## D. Silent errors (exit 0, wrong result)

These are the dangerous ones: Pan says it finished fine.

### D1. Duplicate step names → a step disappears (or Pan hangs)

Two cases tested:

- Example 02 with the Dummy `Descartados` renamed to `Escribir filtrados` (the same name as the Text file output): **exit 0**, but only one of the two steps exists; the hops of both were resolved to the Text file output, which wrote 8 rows (the accepted **and** the discarded ones) instead of 4. The Dummy does not even appear in the log.
- Example 01 with `Agregar constantes` renamed to `Generar filas`: the hop became `Generar filas → Generar filas` (from a step to itself) and Pan stayed at `Dispatching started for transformation [...]` forever, with no error.

**Validate that names are unique before running.**

### D2. Circular hop → infinite loop

A backwards hop (`Escribir CSV → Agregar constantes`) makes the rows circulate endlessly: `Escribir CSV.0 - linenr 300000` and climbing. There is no cycle detection. **Validate that the graph is acyclic.**

### D3. Empty `<decimal/>` with a Spanish locale → numbers ×100

CSV with `2.50`, Number field with empty `<format/>` and `<decimal/>`: with the JVM in es_ES the `.` is taken as the thousands separator and the value is read as **250**. Example 03 gave `importe_total` 2000.00 instead of 20.00. With the JVM in English it comes out right, so the same `.ktr` changes its result depending on the machine. **Always set `<decimal>.</decimal>` and `<group/>` (or whatever applies) explicitly.**

### D4. Calculator: Integer × Number rounds

`MULTIPLY` with `field_a=cantidad` (Integer) and `field_b=precio_unitario` (Number): the result was computed as an integer, rounding the price (2.50→3, 1.80→2, 1.20→1, 4.75→5). Café: 24.00 instead of 20.00. **Fix:** put the decimal field in `field_a` (or convert it beforehand with Select values `meta`).

### D5. Group by without sorting first

Group by groups **consecutive** rows with the same key. Without a prior Sort rows, example 03 gave 10 "groups" (one per row) instead of 4. **Fix:** Sort rows by the same keys before Group by (or use Memory group by, `MemoryGroupBy`, which does not need sorted input).

### D6. Merge join with unsorted inputs

Without the Sort rows steps, the LEFT OUTER of example 05 lost matches (customer 1 ended up with one sale instead of two; customers 4 and 8 showed up with no sales). **Fix:** Sort rows ascending by the key on **both** inputs.

### D7. Disabled hop / step without hops

With `<enabled>N</enabled>` on the only hop into `Escribir CSV`, that step **does not run** (it does not even appear in the log) and nothing is written. A step with no hop at all in a transformation that does have hops does not run either.

### D8. Text file output with no declared fields → padding

With an empty `<fields>` all fields are written, but Strings come out padded with spaces up to their metadata length (`Ana Pérez                                         ;Madrid                        ;`). See C23 for the extreme case.

### D9. Variables in Generate rows / Add constants / Filter rows values

`${ARCHIVO_SALIDA}` as the value of an Add constants field comes out literally in the CSV. **Fix:** Get variables (`GetVariable`).

### D10. Error branch that ends in SUCCESS

If the red hop (`evaluation=N`) of a failed TRANS leads to `SUCCESS`, Kitchen returns exit 0. **Fix:** end error branches in `ABORT`.

### D11. `${Internal.Job.Filename.Directory}` inside a ktr run with Pan

Its value is the literal text `Parent Job File Directory`. **Fix:** use `${Internal.Entry.Current.Directory}`.

---

## E. Pre-run validation checklist (to implement in a generator)

1. Well-formed XML, in UTF-8; `&`, `<` escaped.
2. Unique step names; unique entry names.
3. Every hop `from`/`to` exists; no cycles (in a ktr).
4. Every step has `<copies>1</copies>` and `<GUI>…<draw>Y</draw></GUI>`.
5. Every step takes part in at least one enabled hop (if there are hops).
6. Internal references consistent with the hops: `send_true_to`/`send_false_to`, Stream lookup `<from>`, Merge join `step1`/`step2`, `step_error_handling` `source_step`/`target_step` (+ normal hop).
7. Step types and field types with exact names.
8. Numeric fields read from text with explicit `decimal`/`group`; outputs with declared fields and `length -1`.
9. Sort before Group by / Merge join.
10. Paths with `${Internal.Entry.Current.Directory}`; parameters declared with a default.
11. Job: `START` entry (`SPECIAL`, `start=Y`, `draw=Y`), unconditional hop from START, error branches to `ABORT`.
12. Run with Pan/Kitchen, check the exit code **and** the counts `Finished processing (I=…, O=…, R=…, W=…, U=…, E=…)` (E>0 = errors in that step; W of the output = rows written).
