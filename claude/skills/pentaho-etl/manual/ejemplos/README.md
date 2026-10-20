# Verified examples (PDI 9.4)

Transformations and jobs written by hand and run without errors with Pan/Kitchen 9.4.0.0-343 + Java 21. All paths are relative to this folder (`${Internal.Entry.Current.Directory}`), so the whole folder can be moved as a unit. Outputs are written to `salida/` (it is created automatically; it is listed in `.gitignore`).

| File | What it shows | Expected output |
|---|---|---|
| `01_generar_constantes.ktr` | Generate rows (one field of each type) → Add constants → Text file output. Parameter `ARCHIVO_SALIDA`. Notepad. | `salida/01_constantes.csv`, 5 rows |
| `02_filtrar_clientes.ktr` | CSV input → Filter rows (AND, true/false branch) → Select values (rename + format change) → Text file output; false branch to Dummy | `salida/02_clientes_filtrados.csv`, 4 rows (ids 1, 5, 7, 8) |
| `03_ventas_por_producto.ktr` | CSV input → Calculator → Sort rows → Group by (SUM, COUNT_ALL, AVERAGE, MAX) → Text file output | `salida/03_ventas_por_producto.csv`, 4 products (Café 20.00, Medialuna 21.60, Tostado 9.50, Té 14.40) |
| `04_ventas_con_cliente.ktr` | Stream lookup from two CSV sources, with a default for the sale that has no customer | `salida/04_ventas_con_cliente.csv`, 10 rows (sale 108 with `DESCONOCIDO`) |
| `05_clientes_con_ventas.ktr` | Merge join LEFT OUTER of two sorted sources + Select values (remove `cliente_id_1`) | `salida/05_clientes_con_ventas.csv`, 10 rows (Fabián with no sales) |
| `06_errores_y_parametros.ktr` | `step_error_handling` on CSV input, Get variables with parameter `SALDO_MINIMO`, Filter rows comparing field against field | `salida/06_validos.csv` (2 rows with the default 1000) and `salida/06_errores.csv` (3 rejected rows with diagnostics) |
| `07_conexion_sqlite.ktr` | `<connection>` block (native SQLite) + Table input | `salida/07_consulta_sqlite.csv` (3 rows) and an empty `salida/demo.sqlite` |
| `10_job_secuencia.kjb` | Job: START → TRANS 01 (with parameter) → TRANS 03 → Success; red branches to Abort | `salida/01_desde_job.csv` and `salida/03_ventas_por_producto.csv` |
| `11_job_padre.kjb` | Job: START → Write to log (with parameter `FECHA_PROCESO`) → JOB `10_job_secuencia.kjb` → Success | the same as 10 + a log line |

Input data in `datos/`: `clientes.csv` (8 customers), `ventas.csv` (10 sales, one with a non-existent customer), `clientes_con_errores.csv` (3 rows with deliberate errors). All UTF-8, separator `;`, decimal `.`, dates `yyyy-MM-dd`.

## How to run them

### Option 1: `correr.ps1` (recommended)

From PowerShell, in this folder:

```powershell
.\correr.ps1 01_generar_constantes.ktr
.\correr.ps1 01_generar_constantes.ktr -Param ARCHIVO_SALIDA=otro_nombre
.\correr.ps1 06_errores_y_parametros.ktr -Param SALDO_MINIMO=2000
.\correr.ps1 10_job_secuencia.kjb
.\correr.ps1 11_job_padre.kjb -Param FECHA_PROCESO=2026-12-31 -Nivel Detailed
.\correr.ps1 03_ventas_por_producto.ktr -Ingles     # log in English
```

If the execution policy blocks it: `powershell -ExecutionPolicy Bypass -File .\correr.ps1 01_generar_constantes.ktr`.

The script picks Pan or Kitchen based on the file extension, uses the isolated `KETTLE_HOME` (`C:\pdi\kettle-home-pruebas`), removes `NoDefaultCurrentDirectoryInExePath`, wraps the `/param:` arguments in quotes and returns the real exit code (`EXIT CODE = 0` = OK).

### Option 2: by hand from PowerShell

```powershell
Remove-Item Env:NoDefaultCurrentDirectoryInExePath -ErrorAction SilentlyContinue
$env:KETTLE_HOME = 'C:\pdi\kettle-home-pruebas'
$pdi = 'C:\pdi\data-integration'
$ej  = 'C:\ruta\al\repo\skills\pentaho-etl\manual\ejemplos'

# Transformation
& cmd.exe /c call "$pdi\Pan.bat" "/file:$ej\02_filtrar_clientes.ktr" /level:Basic
$LASTEXITCODE

# With a parameter: literal quotes around /param:NOMBRE=valor
& cmd.exe /c call "$pdi\Pan.bat" "/file:$ej\06_errores_y_parametros.ktr" /level:Basic '"/param:SALDO_MINIMO=2000"'

# Job
& cmd.exe /c call "$pdi\Kitchen.bat" "/file:$ej\10_job_secuencia.kjb" /level:Basic
```

Why `cmd /c call`: with `& Pan.bat` PowerShell loses the exit code (always 0). Why remove `NoDefaultCurrentDirectoryInExePath`: if it is set, `Pan.bat` cannot find `Spoon.bat` (`"Spoon.bat" no se reconoce como un comando interno o externo`, i.e. "'Spoon.bat' is not recognized as an internal or external command"). Details in `../03-errores-comunes.md`.

### Option 3: cmd.exe

```bat
set NoDefaultCurrentDirectoryInExePath=
set KETTLE_HOME=C:\pdi\kettle-home-pruebas
call "C:\pdi\data-integration\Pan.bat" /file:"C:\ruta\al\repo\skills\pentaho-etl\manual\ejemplos\01_generar_constantes.ktr" /level:Basic "/param:ARCHIVO_SALIDA=desde_cmd"
echo %ERRORLEVEL%
```

## How to read the result

Each step finishes with a line like this:

```
Escribir filtrados.0 - Finished processing (I=0, O=5, R=4, W=4, U=0, E=0)
```

`R` = rows read from previous steps, `W` = rows written to following steps, `I` = rows read from file/database, `O` = rows written to file/database (includes the header line), `E` = errors. A healthy transformation has `E=0` in every step (except the ones that deliberately divert rows through error handling: in example 06 the CSV input shows `E=3`) and exit code 0.

Useful Pan/Kitchen options: `/level:` (`Nothing`, `Error`, `Minimal`, `Basic`, `Detailed`, `Debug`, `Rowlevel`), `/param:NOMBRE=valor`, `/listparam` (lists the declared parameters and exits with exit code 7 without running anything: `Parameter: ARCHIVO_SALIDA=, default=01_constantes : …`), `/safemode` (detects mixed row layouts), `/logfile:ruta`.
