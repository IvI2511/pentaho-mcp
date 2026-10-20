# Ejemplos verificados (PDI 9.4)

Transformaciones y jobs escritos a mano y corridos sin error con Pan/Kitchen 9.4.0.0-343 + Java 21. Todas las rutas son relativas a esta carpeta (`${Internal.Entry.Current.Directory}`), así que la carpeta se puede mover entera. Las salidas se escriben en `salida/` (se crea sola; está en `.gitignore`).

| Archivo | Qué muestra | Salida esperada |
|---|---|---|
| `01_generar_constantes.ktr` | Generate rows (un campo de cada tipo) → Add constants → Text file output. Parámetro `ARCHIVO_SALIDA`. Notepad. | `salida/01_constantes.csv`, 5 filas |
| `02_filtrar_clientes.ktr` | CSV input → Filter rows (AND, rama true/false) → Select values (rename + cambio de formato) → Text file output; rama false a Dummy | `salida/02_clientes_filtrados.csv`, 4 filas (ids 1, 5, 7, 8) |
| `03_ventas_por_producto.ktr` | CSV input → Calculator → Sort rows → Group by (SUM, COUNT_ALL, AVERAGE, MAX) → Text file output | `salida/03_ventas_por_producto.csv`, 4 productos (Café 20.00, Medialuna 21.60, Tostado 9.50, Té 14.40) |
| `04_ventas_con_cliente.ktr` | Stream lookup de dos fuentes CSV, con default para la venta sin cliente | `salida/04_ventas_con_cliente.csv`, 10 filas (la 108 con `DESCONOCIDO`) |
| `05_clientes_con_ventas.ktr` | Merge join LEFT OUTER de dos fuentes ordenadas + Select values (remove `cliente_id_1`) | `salida/05_clientes_con_ventas.csv`, 10 filas (Fabián sin ventas) |
| `06_errores_y_parametros.ktr` | `step_error_handling` en CSV input, Get variables con parámetro `SALDO_MINIMO`, Filter rows campo contra campo | `salida/06_validos.csv` (2 filas con el default 1000) y `salida/06_errores.csv` (3 filas rechazadas con diagnóstico) |
| `07_conexion_sqlite.ktr` | Bloque `<connection>` (SQLite nativo) + Table input | `salida/07_consulta_sqlite.csv` (3 filas) y `salida/demo.sqlite` vacío |
| `10_job_secuencia.kjb` | Job: START → TRANS 01 (con parámetro) → TRANS 03 → Success; ramas rojas a Abort | `salida/01_desde_job.csv` y `salida/03_ventas_por_producto.csv` |
| `11_job_padre.kjb` | Job: START → Write to log (con parámetro `FECHA_PROCESO`) → JOB `10_job_secuencia.kjb` → Success | lo mismo que el 10 + línea de log |

Datos de entrada en `datos/`: `clientes.csv` (8 clientes), `ventas.csv` (10 ventas, una con cliente inexistente), `clientes_con_errores.csv` (3 filas con errores a propósito). Todos UTF-8, separador `;`, decimal `.`, fechas `yyyy-MM-dd`.

## Cómo correrlos

### Opción 1: `correr.ps1` (recomendada)

Desde PowerShell, en esta carpeta:

```powershell
.\correr.ps1 01_generar_constantes.ktr
.\correr.ps1 01_generar_constantes.ktr -Param ARCHIVO_SALIDA=otro_nombre
.\correr.ps1 06_errores_y_parametros.ktr -Param SALDO_MINIMO=2000
.\correr.ps1 10_job_secuencia.kjb
.\correr.ps1 11_job_padre.kjb -Param FECHA_PROCESO=2026-12-31 -Nivel Detailed
.\correr.ps1 03_ventas_por_producto.ktr -Ingles     # log en inglés
```

Si la política de ejecución lo bloquea: `powershell -ExecutionPolicy Bypass -File .\correr.ps1 01_generar_constantes.ktr`.

El script elige Pan o Kitchen por la extensión, usa el `KETTLE_HOME` aislado (`C:\pdi\kettle-home-pruebas`), quita `NoDefaultCurrentDirectoryInExePath`, pone comillas a los `/param:` y devuelve el exit code real (`EXIT CODE = 0` = OK).

### Opción 2: a mano desde PowerShell

```powershell
Remove-Item Env:NoDefaultCurrentDirectoryInExePath -ErrorAction SilentlyContinue
$env:KETTLE_HOME = 'C:\pdi\kettle-home-pruebas'
$pdi = 'C:\pdi\data-integration'
$ej  = 'C:\ruta\al\repo\skills\pentaho-etl\manual\ejemplos'

# Transformación
& cmd.exe /c call "$pdi\Pan.bat" "/file:$ej\02_filtrar_clientes.ktr" /level:Basic
$LASTEXITCODE

# Con parámetro: comillas literales alrededor de /param:NOMBRE=valor
& cmd.exe /c call "$pdi\Pan.bat" "/file:$ej\06_errores_y_parametros.ktr" /level:Basic '"/param:SALDO_MINIMO=2000"'

# Job
& cmd.exe /c call "$pdi\Kitchen.bat" "/file:$ej\10_job_secuencia.kjb" /level:Basic
```

Por qué `cmd /c call`: con `& Pan.bat` PowerShell pierde el exit code (siempre 0). Por qué quitar `NoDefaultCurrentDirectoryInExePath`: si está definida, `Pan.bat` no encuentra `Spoon.bat` (`"Spoon.bat" no se reconoce como un comando interno o externo`). Detalles en `../03-errores-comunes.md`.

### Opción 3: cmd.exe

```bat
set NoDefaultCurrentDirectoryInExePath=
set KETTLE_HOME=C:\pdi\kettle-home-pruebas
call "C:\pdi\data-integration\Pan.bat" /file:"C:\ruta\al\repo\skills\pentaho-etl\manual\ejemplos\01_generar_constantes.ktr" /level:Basic "/param:ARCHIVO_SALIDA=desde_cmd"
echo %ERRORLEVEL%
```

## Cómo leer el resultado

Cada step termina con una línea así:

```
Escribir filtrados.0 - Finished processing (I=0, O=5, R=4, W=4, U=0, E=0)
```

`R` = filas leídas de steps anteriores, `W` = escritas a steps siguientes, `I` = leídas de archivo/base, `O` = escritas a archivo/base (incluye la línea de header), `E` = errores. Una transformación sana tiene `E=0` en todos los steps (salvo los que desvían filas por error handling a propósito: en el ejemplo 06 el CSV input muestra `E=3`) y exit code 0.

Opciones útiles de Pan/Kitchen: `/level:` (`Nothing`, `Error`, `Minimal`, `Basic`, `Detailed`, `Debug`, `Rowlevel`), `/param:NOMBRE=valor`, `/listparam` (lista los parámetros declarados y sale con exit 7 sin ejecutar: `Parameter: ARCHIVO_SALIDA=, default=01_constantes : …`), `/safemode` (detecta mezcla de layouts), `/logfile:ruta`.
