# 03 · Errores comunes (provocados y verificados)

Cada caso de este archivo se provocó a propósito rompiendo uno de los ejemplos y corriéndolo con Pan/Kitchen 9.4 + Java 21. Los mensajes son **literales** (se cortan rutas largas con `…`). Las variantes rotas quedaron en el scratchpad de la sesión; para reproducir alguno, aplicá el cambio indicado sobre el ejemplo correspondiente.

Sobre el idioma: los mensajes de los steps salen casi siempre **en inglés** aunque la JVM esté en español; lo que cambia es el "marco" (`Unexpected error` → `Error desconocido`, `Error initializing step` → `Error inicializando paso`, `Transformation detected one or more steps with errors.` → `Transformación detectada …`). Para logs 100 % en inglés: `PENTAHO_DI_JAVA_OPTIONS=-Xms1024m -Xmx2048m -Duser.language=en -Duser.country=US` (lo hace `ejemplos/correr.ps1 -Ingles`).

---

## A. Códigos de salida observados

| Exit | Pan | Kitchen |
|---|---|---|
| 0 | Terminó sin errores | Terminó OK |
| 1 | Error en algún step durante la ejecución; también archivo `.ktr` inexistente o XML mal formado | El job terminó con error (`Kitchen - ERROR … : Finished with errors`) |
| 2 | Error inesperado al preparar (p. ej. hop a step inexistente → NullPointerException) | — |
| 3 | `Unable to prepare and initialize this transformation` (step sin `copies`, tipo de step inexistente, valor inválido en Generate rows…) | — |
| 7 | `/file:` vacío → `ERROR: No repository provided, can't load transformation.` También es el código "normal" de `/listparam` (lista y sale sin ejecutar) | Idem con `/listparam` |
| 8 | `Command line option not understood` (típico: `/param:X=1` sin comillas desde cmd) | — |
| 9 | Pan sin argumentos (imprime la ayuda de opciones) | — |

**Importante:** exit 0 **no** garantiza que el resultado sea correcto. Ver la sección D (errores silenciosos).

---

## B. Errores del entorno (Windows / lanzadores)

### B1. `"Spoon.bat" no se reconoce como un comando interno o externo`

```
Pan.bat : "Spoon.bat" no se reconoce como un comando interno o externo,
programa o archivo por lotes ejecutable.
```
**Causa:** la variable de entorno `NoDefaultCurrentDirectoryInExePath` está definida (Claude Code la define en sus shells). `Pan.bat` hace `call Spoon.bat …` sin ruta, y con esa variable cmd no busca en la carpeta actual.
**Arreglo:** en el mismo proceso, antes de llamar a Pan/Kitchen: PowerShell `Remove-Item Env:NoDefaultCurrentDirectoryInExePath -ErrorAction SilentlyContinue`; cmd `set NoDefaultCurrentDirectoryInExePath=`.

### B2. Pan "termina bien" (exit 0) aunque falló

**Causa:** desde PowerShell, `& 'Pan.bat' …` siempre deja `$LASTEXITCODE = 0` (el código de Pan se pierde al salir del `.bat`).
**Arreglo:** invocar con `call` dentro de cmd: `& cmd.exe /c call $bat @argumentos` → `$LASTEXITCODE` trae el código real (verificado: 3 en vez de 0). Desde un `.cmd`: `call Pan.bat … & echo %ERRORLEVEL%`.

### B3. `Command line option not understood` (exit 8) al pasar parámetros

```
Pan - ERROR (version 9.4.0.0-343, …) : Command Line Options
Pan - ERROR (version 9.4.0.0-343, …) : Command line option not understood
```
**Causa:** cmd parte los argumentos de un `.bat` en `=` (también en `,` y `;`). `/param:SALDO_MINIMO=2000` llega como dos argumentos.
**Arreglo:** comillas **literales** alrededor de todo el argumento: `"/param:SALDO_MINIMO=2000"`. En PowerShell: `('"/param:' + $p + '"')`. Con espacios en el valor también funciona (`"/param:ARCHIVO_SALIDA=prueba param"`, verificado).

### B4. Java 21

No hizo falta ningún arreglo: el `Spoon.bat` de 9.4 detecta que no es Java 8 y agrega solo `--add-opens java.base/java.net=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/sun.net.www.protocol.jar=ALL-UNNAMED` y `-Djava.locale.providers=COMPAT,SPI`. Aparece un aviso inofensivo: `WARNING: COMPAT locale provider will be removed in a future release`. Arranque ≈ 7–10 s por ejecución.

### B5. Archivo inexistente

```
Processing has stopped because of an error:
The transformation path C:\no\existe.ktr is invalid, and will not run successfully.
```
Exit 1. Si la ruta quedó vacía (`/file:` sin nada): `ERROR: No repository provided, can't load transformation.` (exit 7).

---

## C. Errores de formato que Pan detecta

### C1. Tipo de step mal escrito (`<type>AddConstants</type>` en vez de `Constant`)

```
Agregar constantes.0 - ERROR (…) : Can't run transformation due to plugin missing
Agregar constantes.0 - ERROR (…) : Error initializing step [Agregar constantes]
01_generar_constantes - ERROR (…) : Step [Agregar constantes.0] failed to initialize!
Unable to prepare and initialize this transformation
```
Exit 3. **Arreglo:** usar el ID exacto del plugin (case-sensitive) → `../catalogo/`. En jobs el equivalente (`<type>EXITO</type>`) da `Success - ERROR (…) : Can't run job due to plugin missing` (exit 1).

### C2. Hop hacia un step inexistente (`<to>Escribir CSV2</to>`)

```
java.lang.NullPointerException: Cannot invoke "org.pentaho.di.trans.step.StepMeta.isMapping()" because "nextStep" is null
```
Exit 2, sin decir qué hop. **Arreglo:** cada `from`/`to` tiene que coincidir carácter por carácter con un `<step><name>`. Validá esto antes de correr. En jobs: `Cannot invoke "org.pentaho.di.job.JobHopMeta.isUnconditional()" because "hi" is null` (exit 1).

### C3. Step sin `<copies>`

```
Unable to prepare and initialize this transformation
```
Exit 3, sin más detalle (con `/level:Detailed` se ve que se corta en `Allocating rowsets for step 0 --> Generar`). **Arreglo:** `<copies>1</copies>` en cada step.

### C4. Campo inexistente en Select values (`<name>provincia</name>`)

```
Elegir columnas.0 - ERROR (…) : Couldn't find field 'provincia' in row!
java.lang.NullPointerException: Cannot load from object array because "rowData" is null
```
Exit 1.

### C5. Campo inexistente en Filter rows (`<leftvalue>balance</leftvalue>`)

```
Activos con saldo.0 - Fields 'balance' used in the condition are not found in input from previous steps
```
Exit 1.

### C6. Campo inexistente en Text file output

```
Escribir totales.0 - Field [unidades_vendidas] couldn't be found in the input stream!
```
Exit 1.

### C7. Campo inexistente en Sort rows

```
Ordenar por producto.0 - The field articulo specified in the "Ordenar por producto" step is not in the steps input stream.
```
Exit 1.

**Arreglo C4–C7:** seguí el nombre del campo desde su origen (header del CSV, `<rename>` de Select values, `<aggregate>` de Group by, `field_name` de Calculator, `<rename>` de Stream lookup, sufijo `_1` de Merge join). Los nombres son case-sensitive.

### C8. XML mal formado: `<` sin escapar (`<function><=</function>`)

```
[Fatal Error] :188:24: The content of elements must consist of well-formed character data or markup.
Processing has stopped because of an error:
Unable to read file [file:///C:/…/e08_xml_lt_sin_escapar.ktr].
Error reading information from input stream
… org.xml.sax.SAXParseException; lineNumber: 188; columnNumber: 24; The content of elements must consist of well-formed character data or markup.
```
Exit 1. **Arreglo:** `<function>&lt;=</function>`.

### C9. XML mal formado: `&` sin escapar (`Pérez & Cía`)

```
[Fatal Error] :214:24: The entity name must immediately follow the '&' in the entity reference.
```
Exit 1. **Arreglo:** `Pérez &amp; Cía`.

### C10. `.ktr` guardado en ANSI/cp1252 con tildes

```
[Fatal Error] :98:23: Invalid byte 2 of 2-byte UTF-8 sequence.
```
Exit 1. **Arreglo:** guardar en UTF-8 (con o sin BOM; con BOM funciona, verificado).

### C11. Tipo de campo mal escrito (`<type>Int</type>`)

```
Generar filas.0 - Please set a value for the missing field(s) type.
```
Exit 1. **Arreglo:** `String`, `Integer`, `Number`, `BigNumber`, `Date`, `Timestamp`, `Boolean`, `Binary` exactos.

### C12. Valor inválido para el tipo en Generate rows (`Integer` con `cuarenta`)

```
Generar filas.0 - ERROR (…) : Couldn't parse Integer field [entero] with value [cuarenta] --> org.pentaho.di.core.exception.KettleValueException:
… couldn't convert String to number : non-numeric character found at position 1 for value [cuarenta]
Generar filas.0 - ERROR (…) : Error initializing step [Generar filas]
Unable to prepare and initialize this transformation
```
Exit 3.

### C13. Archivo de entrada inexistente (CSV input)

```
Leer clientes.0 - An error occurred while creating field mapping
Leer clientes.0 - Could not read from "file:///C:/…/datos/clientes_NO_EXISTE.csv" because it is not a file.
… java.io.FileNotFoundException: C:\…\clientes_NO_EXISTE.csv (El sistema no puede encontrar el archivo especificado)
```
Exit 1.

### C14. Variable que no existe en una ruta (`${CARPETA_DATOS}/clientes.csv`)

```
Could not read from "file:///C:/pdi/data-integration/${CARPETA_DATOS}/clientes.csv" because it is not a file.
```
Exit 1. La variable queda literal y la ruta se resuelve contra la carpeta de PDI. **Arreglo:** declararla como parámetro con default, pasarla con `/param:`, o definirla en `kettle.properties`.

### C15. Ruta relativa sin variable (`datos/clientes.csv`)

```
Could not read from "file:///C:/pdi/data-integration/datos/clientes.csv" because it is not a file.
```
Exit 1. Se resuelve contra la carpeta de instalación (`user.dir`), no contra la del `.ktr`. **Arreglo:** `${Internal.Entry.Current.Directory}/datos/clientes.csv`. En un job, la ruta pelada a una ktr da `The transformation path 01_generar_constantes.ktr is invalid, and will not run successfully.`

### C16. Formato de fecha distinto al del archivo (máscara `dd/MM/yyyy`, dato `2023-01-15`)

```
Leer clientes.0 - There were 1 conversion errors on line 2
fecha_alta String : couldn't convert string [2023-01-15] to a date using format [dd/MM/yyyy] on offset location 4
```
Exit 1. **Arreglo:** máscara correcta, o `step_error_handling` para desviar las filas malas (ejemplo 06).

### C17. Símbolo decimal mal declarado (`<decimal>,</decimal>` con datos `2.50`)

```
Leer ventas.0 - There were 1 conversion errors on line 2
precio_unitario String : couldn't convert String to number : non-numeric character found at position 2 for value [2.50]
```
Exit 1. (Ojo: con `<decimal/>` **vacío** no falla, ver D3.)

### C18. Stream lookup con `<from>` que no coincide con el step de lookup

```
Buscar cliente.0 - ERROR (…) : No lookup step specified.
Buscar cliente.0 - ERROR (…) : Unable to read data from lookup-stream.
```
Exit 1. **Arreglo:** `<from>` = nombre exacto del step que entra por el segundo hop.

### C19. Merge join cuyo `step2` no tiene hop hacia el join

```
Unir clientes y ventas.0 - Unable to find specified source step with name 'Ordenar ventas'.
```
Exit 1. **Arreglo:** `step1`/`step2` tienen que ser exactamente los dos steps con hop entrante al Merge join.

### C20. `send_true_to` apunta a un step sin hop desde el filtro

```
Activos con saldo.0 - Step [Escribir filtrados] is invalid as target.
```
Exit 1. **Arreglo:** `send_true_to`/`send_false_to` solo pueden nombrar steps a los que el Filter rows tenga un hop directo.

### C21. Carpeta de salida inexistente con `create_parent_folder=N`

```
Escribir CSV.0 - Error creating output file! Parent directory [file:///C:/…/no_existe] does not exist.
```
Exit 1. **Arreglo:** `<create_parent_folder>Y</create_parent_folder>`.

### C22. Dos flujos con layout distinto entrando al mismo step

Sin safe mode falla donde se nota (acá en el output): `Escribir ventas con cliente.0 - Field [venta_id] couldn't be found in the input stream!` (exit 1). Con `/safemode`:

```
org.pentaho.di.core.exception.KettleRowException:
We detected rows with varying number of fields, this is not allowed in a transformation.  The first row contained 8 fields, another one contained 6 : [cliente_id Integer], [nombre String], …
```
**Arreglo:** que todos los hops que entran a un mismo step (salvo info streams de lookup/join) traigan los mismos campos en el mismo orden. Para depurar, correr con `/safemode`.

### C23. Text file output sin campos declarados + fuente con largos enormes (SQLite)

```
Escribir consulta.0 - ERROR (…) : UnexpectedError:
Escribir consulta.0 - ERROR (…) : java.lang.OutOfMemoryError: Requested array size exceeds VM limit
```
Exit 1. El output rellena cada String a su largo de metadato; el driver SQLite informa TEXT con largo ~2³¹. **Arreglo:** declarar los campos del output con `<length>-1</length>`.

### C24. Job sin START válido

```
10_job_secuencia - ERROR (…) : A serious error occurred during job execution:
10_job_secuencia - Couldn't find starting point in this job.
Kitchen - ERROR (…) : Finished with errors
```
Exit 1. Pasa si la entry no se llama literalmente `START`, o si le falta `<draw>Y</draw>` (aunque tenga `<start>Y</start>`). **Arreglo:** `<name>START</name><type>SPECIAL</type><start>Y</start><draw>Y</draw>`.

### C25. Job que llama a una ktr inexistente

```
Ventas por producto - ERROR (…) : Unable to run job 10_job_secuencia. The Ventas por producto has an error. The transformation path file:///C:/…/03_NO_EXISTE.ktr is invalid, and will not run successfully.
```
La entry falla; si hay hop rojo se sigue por ahí (en el ejemplo, a `Abortar`), exit 1.

### C26. Ktr que falla dentro de un job

Se ve el error del step (p. ej. C7) y luego `03_ventas_por_producto - ERROR (…) : Errors detected!`; la entry TRANS termina `result=[false]` y sigue el hop rojo si existe. Sin hop rojo: `Kitchen - ERROR (…) : Finished with errors`, exit 1.

---

## D. Errores silenciosos (exit 0, resultado mal)

Estos son los peligrosos: Pan dice que terminó bien.

### D1. Nombres de step duplicados → un step desaparece (o Pan se cuelga)

Dos casos probados:

- Ejemplo 02 con el Dummy `Descartados` renombrado a `Escribir filtrados` (mismo nombre que el Text file output): **exit 0**, pero solo existe uno de los dos steps; los hops de ambos se resolvieron al Text file output, que escribió 8 filas (las aceptadas **y** las descartadas) en vez de 4. El Dummy ni aparece en el log.
- Ejemplo 01 con `Agregar constantes` renombrado a `Generar filas`: el hop quedó `Generar filas → Generar filas` (de un step a sí mismo) y Pan se quedó en `Dispatching started for transformation [...]` para siempre, sin error.

**Validá unicidad de nombres antes de correr.**

### D2. Hop circular → loop infinito

Un hop hacia atrás (`Escribir CSV → Agregar constantes`) hace circular las filas sin fin: `Escribir CSV.0 - linenr 300000` y subiendo. No hay detección de ciclos. **Validá que el grafo sea acíclico.**

### D3. `<decimal/>` vacío con locale español → números ×100

CSV con `2.50`, campo Number con `<format/>` y `<decimal/>` vacíos: con la JVM en es_ES el `.` se toma como separador de miles y se lee **250**. El ejemplo 03 dio `importe_total` 2000.00 en vez de 20.00. Con la JVM en inglés da bien, así que el mismo `.ktr` cambia de resultado según la máquina. **Siempre `<decimal>.</decimal>` y `<group/>` (o lo que corresponda) explícitos.**

### D4. Calculator: Integer × Number redondea

`MULTIPLY` con `field_a=cantidad` (Integer) y `field_b=precio_unitario` (Number): el resultado se calculó como entero redondeando el precio (2.50→3, 1.80→2, 1.20→1, 4.75→5). Café: 24.00 en vez de 20.00. **Arreglo:** el campo decimal en `field_a` (o convertir antes con Select values `meta`).

### D5. Group by sin ordenar antes

Group by agrupa filas **consecutivas** con la misma clave. Sin Sort rows previo, el ejemplo 03 dio 10 "grupos" (uno por fila) en vez de 4. **Arreglo:** Sort rows por las mismas claves antes de Group by (o usar Memory group by, `MemoryGroupBy`, que no necesita orden).

### D6. Merge join con entradas sin ordenar

Sin los Sort rows, el LEFT OUTER del ejemplo 05 perdió matches (el cliente 1 quedó con una venta en vez de dos; los clientes 4 y 8 aparecieron sin ventas). **Arreglo:** Sort rows ascendente por la clave en **las dos** entradas.

### D7. Hop deshabilitado / step sin hops

Con `<enabled>N</enabled>` en el único hop hacia `Escribir CSV`, ese step **no se ejecuta** (ni aparece en el log) y no se escribe nada. Un step sin ningún hop en una transformación que sí tiene hops tampoco corre.

### D8. Text file output sin campos declarados → padding

Con `<fields>` vacío se escriben todos los campos, pero los String salen rellenados con espacios hasta su largo de metadato (`Ana Pérez                                         ;Madrid                        ;`). Ver C23 para el caso extremo.

### D9. Variables en valores de Generate rows / Add constants / Filter rows

`${ARCHIVO_SALIDA}` como valor de un campo de Add constants sale literal en el CSV. **Arreglo:** Get variables (`GetVariable`).

### D10. Rama de error que termina en SUCCESS

Si el hop rojo (`evaluation=N`) de una TRANS que falló lleva a `SUCCESS`, Kitchen devuelve exit 0. **Arreglo:** terminar las ramas de error en `ABORT`.

### D11. `${Internal.Job.Filename.Directory}` dentro de una ktr corrida con Pan

Vale el texto literal `Parent Job File Directory`. **Arreglo:** usar `${Internal.Entry.Current.Directory}`.

---

## E. Checklist de validación previa (para implementar en un generador)

1. XML bien formado y en UTF-8; `&`, `<` escapados.
2. Nombres de step únicos; nombres de entry únicos.
3. Todo `from`/`to` de hop existe; ningún ciclo (en ktr).
4. Todo step con `<copies>1</copies>` y `<GUI>…<draw>Y</draw></GUI>`.
5. Todo step participa de al menos un hop habilitado (si hay hops).
6. Referencias internas coherentes con los hops: `send_true_to`/`send_false_to`, Stream lookup `<from>`, Merge join `step1`/`step2`, `step_error_handling` `source_step`/`target_step` (+ hop normal).
7. Tipos de step y de campo con nombres exactos.
8. Campos numéricos leídos de texto con `decimal`/`group` explícitos; outputs con campos declarados y `length -1`.
9. Sort antes de Group by / Merge join.
10. Rutas con `${Internal.Entry.Current.Directory}`; parámetros declarados con default.
11. Job: entry `START` (`SPECIAL`, `start=Y`, `draw=Y`), hop desde START incondicional, ramas de error a `ABORT`.
12. Correr con Pan/Kitchen, chequear exit code **y** los conteos `Finished processing (I=…, O=…, R=…, W=…, U=…, E=…)` (E>0 = errores en ese step; W del output = filas escritas).
