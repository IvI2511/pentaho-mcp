# Corrector de ejercicios: diseño (Pentaho Live 0.6.0)

Diseño del **corrector de ejercicios** de la fase «Calidad y clases» (`docs/roadmap.md`): el docente
arma un *pack* con el enunciado, los datos, la salida esperada, reglas y una rúbrica; el plugin corre
la transformación del alumno sobre esos datos, compara filas y devuelve la nota con lo que falló.
Sale de un prototipo headless del 20-oct-2026 sin abrir Spoon (Kettle 9.4.0.0-343, Java 21,
`--release 8`), revisado el mismo día con entregas armadas para romperlo. Reusa Informes
(`docs/informes-diseno.md`) y el linter (`docs/linter-diseno.md`) sin tocar sus archivos. Marcas:

| Marca | Qué significa |
|---|---|
| **[P]** | Probado con el prototipo, con los jars reales de PDI 9.4, fuera de Spoon. **[P·rev]**: probado en la revisión (`scratchpad\v06\corrector-review`). |
| **[S]** | Ya funciona en el plugin 0.4.0 (`RunMotor.preview`, `ejecutarTrans`, `validar`). |
| **[B]** | Leído en el código o el bytecode (`javap -c`) de PDI o del plugin. No se ejecutó. |
| **[NP]** | No probado. Probarlo antes de darlo por bueno. |

## 0. Decisiones en una tabla

| Tema | Decisión |
|---|---|
| Qué es | Corrección **determinista, sin IA**: correr, capturar, comparar, puntuar. Claude puede explicar una devolución o ayudar a armar el pack, pero la nota la calcula el motor. |
| Pack | Carpeta o `.zip` con `pack.json` (formato `pentaho-live-exercise-pack/1`). Variante de un solo `.json` con los datos adentro para ejercicios chicos. |
| Casos | Uno o más casos (datos + salida esperada). Los `hidden` corren igual pero la devolución no muestra sus valores ni su log: atrapan la salida «dibujada» a mano y el acierto de casualidad. |
| Dónde corre | **Nunca en la JVM de Spoon** (tampoco el análisis estático). Una JVM hija (worker) con el classpath de PDI + el jar del plugin, `KETTLE_HOME` vacío, locale, zona y `file.encoding` del pack, `java.io.tmpdir` en el sandbox, `-Xmx` y timeout duro. |
| Entradas | Copia del `.ktr` en un sandbox por caso. Toda ruta que termina en el nombre de un archivo del pack pasa a `${Internal.Entry.Current.Directory}/datos/<archivo>` (nunca el nombre del step ni valores de datos). Cualquier otra lectura fuera del sandbox bloquea (`unexpected_input`). El archivo del alumno no se toca. |
| Salidas | Se redirigen a `salida/` del sandbox; la que toma el nombre de un campo se bloquea. Captura `file` (solo Text file output, leído con **su** separador, encierro y encoding), `step` (RowListener sobre el step de salida) o `query` (SQL del pack sobre la copia de la base SQLite del pack). |
| Comparación | Por clave si el pack la da; si no, multiconjunto o posición. Columnas por nombre y **tipos de la solución** guardados en el pack: los números del alumno se leen con el formato que él declaró (coma decimal incluida). Tolerancia, trim, mayúsculas; ejemplos de lo que falta, sobra, difiere y claves repetidas; sugerencia de renombre. |
| Nota | Rúbrica de checks con puntos (`runs`, `columns`, `row_count`, `values`, `rule:<id>`, `lint:<id>`), crédito parcial, promedio ponderado por caso, escala 10. |
| Seguridad | Política por tipo de step: seguro / condicional (lo habilita el pack) / peligroso (solo «modo confianza», que se activa **solo en la UI**) / desconocido (se bloquea). Conexiones ajenas al pack que use algún step: bloqueadas. **No es un sandbox real** y la UI lo dice. |
| Resultado | Un JSON (`pentaho-live-grade-result/1`) es el contrato. Lo dibujan los renderizadores de Informes (0.5.0, mismo jar): `CorreccionAInforme` lo pasa a `Informe → Secciones → Bloques` → HTML, PDF, MD o PPTX. |
| MCP | `create_exercise_pack`, `validate_exercise_pack`, `grade_exercise`, `grade_submissions`, `get_grading_status`. |
| Spoon | Pestaña **Corrector** en la perspectiva de Informes **si** su WP1 agrega pestañas (hoy no tiene); si no, perspectiva propia. «Crear pack desde esta transformación». |
| Planilla | CSV para Excel en español (BOM, `;`, coma decimal, celdas escapadas) y `.xlsx` con el POI que Informes copia a `plugins/pentaho-mcp/lib`. |

## 1. Lo que se probó

Prototipo de ~1.800 líneas (clases `Tabla`, `Comparador`, `Politica`, `Sandbox`, `Ejecutor`,
`Corrector`, `Informe`; fuera del repo). Solución: `manual/ejemplos/03_ventas_por_producto.ktr`.

| # | Qué | Resultado |
|---|---|---|
| P1 | Salida esperada desde la solución, dos casos: `visible` (el `ventas.csv` del ejemplo) y `oculto` (12 ventas nuevas, otro producto) | 4 filas cada uno; `visible` coincide con el README (Café 20.00, Medialuna 21.60, Tostado 9.50, Té 14.40) [P] |
| P2 | Lote con **una JVM hija por entrega** (6 entregas) | 78 s en total. `KettleEnvironment.init()` tarda 5,8–7,0 s por JVM; cada caso corre en 0,14–0,30 s [P] |
| P3 | **Un worker** que corrige varias seguidas (5, sin la del bucle) | init 6,3 s una sola vez; después 0,35–1,0 s por entrega (2 casos). Mismas notas que P2 [P]. Sin padre vigilando: el vigilante con worker reusado es [NP] |
| P4 | Captura `step` vs `file` | Mismas notas (9,7 y 6,5). En `step` los números llegan sin formato (`7.5` contra `5.00`): se comparan como números [P] |
| P5 | Captura `query` sobre SQLite: plantilla copiada al sandbox, conexión reescrita, Table output permitido por el pack | Correcta 10,0; con MAX 5,5. La plantilla del pack quedó vacía [P] |
| P6 | Rutas absolutas de la PC del alumno (`C:\Users\ana\Escritorio\PDI\ventas.csv`, salida `…\resultado`) | Redirigidas, anotadas en la devolución, corrió en el sandbox [P] |
| P7 | Modified JavaScript sin permiso del pack | `blocked`: no se ejecutó nada [P] |
| P8 | Hop circular (error D2 del manual) | Timeout por caso (15 s); `stopAll` + `killAll` alcanzan, el proceso termina solo [P] |
| P9 | `<decimal/>` vacío al leer el CSV (error D3) | **6,0 con `es_ES` y 10,0 con `en_US`** para la misma entrega [P]. El locale se pasó a mano: el prototipo no lee `policy.locale` |
| P10 | `KETTLE_HOME` vacío en la hija | `init()` solo crea `.kettle/kettle.properties`. El MCP del plugin **no** arranca en la hija: es `@LifecyclePlugin` y eso lo dispara solo Spoon [P] |
| P11 | Hija con el launcher de PDI (`-jar launcher/launcher.jar -lib … -main <clase>`) | Anda, pero `-lib` reemplaza `libswt` y el log se llena de `ClassNotFoundException` de diálogos (inofensivo). Mejor `-cp` explícito [P] |
| P12 | Windows: `Transformer` con `StreamResult(File)` dejó abierta la copia del `.ktr` | No se podía borrar el sandbox; se arregla con un `FileOutputStream` propio [P] |
| P13 | Fechas en SQLite vía Table output | INTEGER (epoch ms de la medianoche **local**). El SQL del pack convierte (`date(x/1000,'unixepoch','localtime')`) y depende de la zona [P] |
| P14 | ana con el CSV input llamado `ventas.csv` | **2,0**: la regla de entradas reescribió el `<name>` del step, el hop quedó colgando → `init_failed` [P·rev] |
| P15 | ana + una conexión MySQL que ningún step usa | **2,0**: los dos casos `blocked`, pero la entrega quedó `graded` [P·rev] |
| P16 | ana con `<decimal/>` vacío en el Text file output | Escribe `20,00`: **6,0 con `es_ES`, 10,0 con `en_US`** (se comparó como texto) [P·rev] |
| P17 | ana con `<encoding/>` vacío en la salida | 10,0 con `file.encoding=UTF-8`; **8,5 con el charset nativo** (`COMPAT`, como Java 8/11): `Café` y `Té` no casan [P·rev] |
| P18 | Generate rows «never ending» → Text file output, timeout 5 s | 1,8 M filas y **54 MB** antes del corte: `max_output_mb` medido al final llega tarde [P·rev] |
| P19 | `.ktr` con `<!DOCTYPE>` y entidad externa | `new TransMeta` ya la rechaza en 9.4 («DOCTYPE is disallowed»): sin XXE [P·rev] |
| P20 | ana + un Generate rows dibujado **sin ningún hop** | **Corre** (W=7): `getTransHopSteps(false)` suma los steps dibujados sin hops [B]. El manual (D7) y `orphan_step` del linter dicen que no [P·rev] |

Las entregas del prototipo (rúbrica: runs 1, columns 2, row_count 1, values 4, usa Group by 1, sin
código 0,5, ≤ 8 steps 0,5; escala 10; **sin compuertas**):

| Entrega | Qué tiene | Estado | Nota | Qué dice la devolución |
|---|---|---|---|---|
| ana_orden_distinto | Correcta; Sort descendente (salen Té, Tostado, Medialuna, Café) y rutas de su PC | graded | **10,0** | 4/4 iguales en los dos casos; notas de redirección |
| bruno_agregado_mal | `importe_medio` con MAX en vez de AVERAGE | graded | **6,5** | values: visible 25 %, oculto 0 %. «Café · importe_medio · esperado 5.00 · tuyo 7.50» y 2 más |
| carla_falta_columna | Sin `ultima_venta` en el Text file output | graded | **9,7** | columns 5/6. Pista: «Falta la columna ultima_venta: revisa la lista de campos del step de salida» |
| diego_javascript | Importe calculado con Modified JavaScript | blocked | **1,5** | No se ejecutó; falla «sin código»; suman Group by y ≤ 8 steps |
| elena_sin_ordenar | Group by sin Sort rows (D5) | graded | **5,5** | 10 filas contra 4; claves repetidas Café ×4, Té ×3, Medialuna ×2; pista del Sort rows |
| fede_bucle | Hop circular (D2) | timeout | **2,0** | Solo los puntos de reglas (ver compuertas, §5) |
| gabi_renombra (extra) | `importe_medio` → `promedio` | graded | 9,7 | «La columna importe_medio no está, pero promedio tiene los mismos valores: ¿la renombraste?» |
| hugo_decimal (extra) | `<decimal/>` vacío (D3) | graded | 6,0 | «Algunos importes salen multiplicados por 100: declara el símbolo decimal» |

**No probado [NP]:** jobs `.kjb`; pack en `.zip` o `.json` único; nada en Spoon (UI, classpath de la
hija desde Spoon); las tools MCP; `.xlsx`; comparación sin clave (todas las pruebas usaron clave),
orden estricto y mayúsculas; `extra_columns`, `parameters`, `requires`, `policy.locale/timezone/
encoding`, `max_output_mb` (el prototipo no los lee); `error_handling` y `lint:*`; D1/D2 previos;
`unexpected_input`; redirección de Excel/JSON/XML output (en el código, sin caso); casos en paralelo.
Las pistas el prototipo las arma al renderizar, no en el JSON.

## 2. El pack

### 2.1 Estructura

```
ventas-por-producto/              (o ventas-por-producto.zip con lo mismo adentro)
├── pack.json                     la definición (§2.2)
├── enunciado.md                  lo que lee el alumno
├── datos/<caso>/ventas.csv       entradas, una carpeta por caso
├── esperado/<caso>.csv           salida esperada por caso (la escribe create_exercise_pack)
├── plantilla/dw.sqlite           solo captura «query»: base vacía con las tablas destino
└── solucion/03_….ktr             solo en la copia del docente
```

**Copia para alumnos** (`create_exercise_pack` con `student_copy`): sin `solucion/`, sin `esperado/`
ni `datos/` de los ocultos, y con un `pack.json` **sin** esos casos ni `solution` (si no, no valida).
Con eso el alumno se autocorrige contra el caso visible. Todas las rutas de `pack.json` (y las claves
de `inline`) son relativas y quedan dentro del pack: `..` o una ruta absoluta → pack inválido.

### 2.2 `pack.json`

```jsonc
{
  "format": "pentaho-live-exercise-pack/1",
  "id": "ventas-por-producto", "title": "Ejercicio 3 - Ventas por producto", "language": "es",
  "statement": "enunciado.md", "kind": "transformation",          // o "job" [NP]
  "solution": "solucion/03_ventas_por_producto.ktr",
  "inputs": ["ventas.csv"],                                        // nombres que se redirigen
  "parameters": { "FECHA_CORTE": "2024-05-31" },                  // opcional: se pasan si la entrega los declara [NP]
  "cases": [
    { "id": "visible", "inputs_dir": "datos/visible", "expected": "esperado/visible.csv", "weight": 1 },
    { "id": "oculto",  "inputs_dir": "datos/oculto",  "expected": "esperado/oculto.csv",  "weight": 1, "hidden": true }
  ],
  "output": {
    "capture": "file",                       // file (solo Text file output) | step | query
    "step": "Escribir totales",              // el de la solución; null = regla del §3 paso 5
    "columns": [ { "name": "producto", "type": "String" }, { "name": "importe_total", "type": "Number" } /* … */ ],
    "expected_csv": { "separator": ";", "enclosure": "\"", "header": true },
    "database": { "template": "plantilla/dw.sqlite", "type": "SQLITE" },     // solo query
    "sql": "SELECT producto, ROUND(importe_total, 2) AS importe_total FROM totales"  // solo query
  },
  "compare": {
    "key_columns": ["producto"], "ignore_column_order": true, "ignore_row_order": true,
    "numeric_tolerance": 0.01, "relative_tolerance": 0, "trim": true, "case_sensitive": true,
    "date_formats": ["yyyy-MM-dd", "dd/MM/yyyy"],   // [NP] normaliza fechas escritas con otra máscara
    "strict_format": false,                          // [NP] true: coma decimal u otra máscara restan
    "extra_columns": "warn",                         // warn | penalize [NP]
    "max_examples": 5
  },
  "rules": [
    { "id": "usa_group_by", "type": "must_use", "steps": ["GroupBy", "MemoryGroupBy"], "label": "Usa un Group by" },
    { "id": "sin_codigo", "type": "must_not_use", "steps": ["ScriptValueMod", "UserDefinedJavaClass", "Janino"] },
    { "id": "max_pasos", "type": "max_steps", "value": 8 },
    { "id": "errores", "type": "error_handling", "steps": ["CsvInput"] },  // [NP] propia: el linter la podó
    { "id": "orden", "type": "lint", "rule": "unsorted_input" }           // [NP] id del linter 0.6
  ],
  "rubric": [
    { "check": "runs", "points": 1 }, { "check": "columns", "points": 2 },
    { "check": "row_count", "points": 1 }, { "check": "values", "points": 4 },
    { "check": "rule:usa_group_by", "points": 1, "requires": ["runs"] },  // [NP] con la compuerta: fede 1,0 y diego 0,5
    { "check": "rule:sin_codigo", "points": 0.5 }, { "check": "rule:max_pasos", "points": 0.5 }
  ],
  "scale": 10,
  "policy": { "allow_steps": [], "unknown_steps": "block", "timeout_s": 60, "max_rows": 100000,
              "max_output_mb": 50, "locale": "es_ES", "timezone": "Europe/Madrid", "encoding": "UTF-8" }
}
```

`output.columns` la escribe `create_exercise_pack` con los tipos de la salida de la solución.
**Un solo `.json`** [NP]: los mismos campos más `"inline": {"datos/visible/ventas.csv": "texto…"}`
(tope 2 MB), para pegar un ejercicio en el aula virtual.

## 3. Cómo se corrige una entrega

Todo pasa en la hija, también el análisis estático (calcular campos puede tocar una base).

1. **Cargar** con nuestro parser (DOCTYPE prohibido) para el sandbox y con `new TransMeta(ruta)`, que
   en 9.4 ya rechaza DOCTYPE (P19). Si no carga: `load_failed` y la razón [P].
2. **Análisis estático** sin ejecutar: política (§7), reglas, `max_steps`, `lint:*` y los chequeos de
   errores silenciosos [NP]: **D1 sobre el XML crudo**, porque `loadXML` funde los steps con el mismo
   nombre (`findStep`, que ignora mayúsculas, + `addOrReplaceStep` [B]); D2 con `hasLoop(StepMeta)`
   para cada step [B]. Son las reglas `duplicate_step_name` y `cycle` del linter: se usa `LintMotor`
   si ya está. Con D1/D2 no se ejecuta. El resultado parcial se escribe **antes** de ejecutar: si la
   hija muere, el padre igual tiene las reglas [P].
3. **Sandbox por caso**: `datos/` (copia de solo lectura de las entradas del caso), `salida/`, `db/`
   (copia de la plantilla), `tmp/` y la copia reescrita del `.ktr`:
   - **Entradas** [P]: toda hoja de texto de un `<step>` o de un `<default_value>` cuyo valor, con
     `\` → `/`, es `<archivo del pack>` o termina en `/<archivo>` (sin mayúsculas) pasa a
     `${Internal.Entry.Current.Directory}/datos/<archivo>`, sin conocer el tipo de step. **Excepciones**
     [NP]: `<name>` y `<description>` del step y las hojas bajo `<fields>`/`<data>` (Data grid, Add
     constants). Después se verifica que nombres de steps y extremos de hops no cambiaron (P14).
   - **Salidas a archivo**: `file/name` de Text file output [P] y de Excel output, Excel writer, JSON y
     XML output [B] → `${…}/salida/<nombre base>`; si dos chocan, sufijo `_<step>`. **`fileNameInField=Y`
     bloquea** (`unexpected_output`): escribiría donde diga un campo, hasta `output_dir/<alumno>.json`.
   - **Conexiones** [P]: con captura `query`, toda conexión SQLite pasa a `db/<plantilla>`. Una de otro
     tipo bloquea el caso **solo si un step la nombra**; las que no usa nadie se sacan con una nota (P15).
   - **Entradas inesperadas** [NP, **obligatorio**]: toda lectura no redirigida que, con variables
     resueltas, cae fuera del sandbox o usa un esquema VFS que no es `file:` (http, ftp, sftp, zip…) →
     `blocked`, `reason: unexpected_input`. Fuente: `getResourceDependencies()` (como
     `ReportMotor.archivosDe` [S]) + las hojas con tag de archivo (la heurística de `absolute_path` del
     linter). Así nadie lee el `esperado/` del docente ni el `_sandbox/` de un compañero del mismo lote.
     Las entradas que toman el nombre de un campo (Text file input `acceptingFilenames`, CSV input
     `filenameField` [B, javap]; JSON/XML input con fuente en campo o URL [NP]) no se pueden revisar: bloquean.
   - **Parámetros** [NP]: los del pack se pasan con `setParameterValue` si la entrega los declara.
4. **Ejecutar** con la secuencia de `RunMotor.ejecutarTrans` [S] (`setInternalKettleVariables`,
   `copyParametersFrom`, `activateParameters`, `prepareExecution`, `startThreads`, tope `timeout_s`,
   `stopAll` y `killAll` [P]) pero con **bucle propio**: `ejecutarTrans` no deja enganchar el RowListener
   entre `prepareExecution` y `startThreads` ni vigilar `salida/`. Se reusan sus helpers (`padreDeLog`,
   `prepararVariables`, `esperarFin`, `metricas`, `colaLog`) sin modificar `RunMotor`. Si
   `prepareExecution` falla: `init_failed` con la primera línea y la cola del log.
5. **Capturar** del step de salida: `output.step`; si falta en la entrega, el de salida que comparta
   más columnas con las esperadas; empate → aviso `output_ambiguous` (el prototipo agarra el primero
   del XML: con una salida de errores puede corregir la equivocada).
   - `file` [P], **solo Text file output**: leído con **su** separador, encierro, encabezado y
     `<encoding>` (vacío = el `file.encoding` del pack, P17). No se le resta a quien usó `,` como
     separador si el enunciado no lo pide; `strict_format` [NP] sí. Nombre real con `buildFilename`
     (fecha, hora, nro. de step); con `split`, aviso. Excel/JSON/XML output: usar `step`.
   - `step` [P]: `RowListener.rowWrittenEvent` sobre todas las copias del step (como `preview` [S]),
     `cloneRow`, proyectado a los campos declarados del Text file output. Números a `BigDecimal` plano
     y fechas a `yyyy-MM-dd`. Para salidas que no son archivos (Dummy final) o no son CSV.
   - `query` [P]: JDBC directo (`sqlite-jdbc` de `lib/`) sobre `db/<plantilla>` con el SQL del pack.
6. **Comparar** (§4) contra `esperado/<caso>.csv` (con `expected_csv`) y borrar `salida/` del caso;
   el sandbox del caso visible se guarda para «Abrir su .ktr» (§9).
7. **Puntuar** (§5) y **escribir** el resultado (§6).

**Jobs** [NP]: copiar la carpeta entera de la entrega y reescribir cada `.ktr`/`.kjb`; captura `file` o
`query` (los Trans se crean adentro de `JobEntryTrans`: no hay RowListener fácil); reglas y política
sobre todas las transformaciones del job. Lo mismo vale para Mapping/TransExecutor en una transformación.

## 4. Comparación

- **Columnas**: por nombre (sin mayúsculas, con trim) si `ignore_column_order`; si no, por posición
  y se avisa `order_differs`. Resultado: `missing`, `extra`, `columns_score = presentes/esperadas` [P].
- **Filas con clave** [P]: por cada fila esperada: falta, **clave repetida** (no cuenta: es la firma del
  Group by sin Sort) o se comparan las columnas comunes; claves del alumno que no existen: sobran.
  `create_exercise_pack` verifica que la clave sea **única** en la salida esperada de cada caso; si no,
  avisa y propone comparar sin clave (si no, la propia solución sacaría «claves repetidas»).
- **Sin clave** [NP]: multiconjunto (emparejamiento voraz, O(n·m), alcanza para clase; con más de
  5.000 filas, ordenar y recorrer) o por posición si el orden importa.
- **Celdas**: vacío = nulo [P]. Las columnas numéricas según `output.columns` se comparan como números
  (`|a−b| ≤ max(tol, rel·max)` [P]); el texto del alumno se convierte con **el formato que declaró** en
  ese campo del Text file output (máscara, decimal, miles; vacío = el locale de la hija): `20,00` en
  `es_ES` vale 20 (P16) [NP]. Las de texto, como texto con trim y mayúsculas según el pack (hoy
  «si las dos parecen números» iguala `007` con `7`). Fechas con otra máscara: `date_formats` [NP].
- **Puntajes** [P]: `values_score = iguales / max(esperadas, iguales + sobrantes)`, `row_count_ok`. Las
  columnas que faltan **no** restan en `values` (ya restan en `columns`): carla saca 9,7 y no 6.
- **Renombres** [P]: columna que falta y otra que sobra con ≥ 80 % de los mismos valores por clave.
- **Ejemplos**: los primeros `max_examples` de cada tipo; en los casos ocultos solo van conteos.

## 5. Rúbrica y nota

| Check | Por caso | Notas |
|---|---|---|
| `runs` | 1 si `run.state = finished` y 0 errores | cualquier otro estado (§6) → 0 |
| `columns` / `row_count` / `values` | `columns_score` / 1 si coincide / `values_score` | 0 si no corrió; `values` con crédito parcial |
| `rule:<id>` | 1/0, no depende del caso | `must_use` exige el step **conectado por un hop habilitado** [P]; mejor, ancestro del step de salida (`findPreviousSteps` [B]): un Group by colgado de la entrada no suma [NP] |
| `lint:<id>` | 1 si no hay hallazgos ≥ warning de esa regla (contrato del linter §9) | `deep=false` (el profundo lee archivos) e **ignorando** los atributos `pentaho-live-lint` de la entrega: si no, el alumno silencia la regla que se evalúa [NP] |

Cada check de salida se promedia entre casos según `weight` [P]. Nota = Σ ganados / Σ puntos × escala,
redondeada a 0,1 [P]. **Compuertas** (`requires: ["runs"]`) [NP]: el prototipo no las tiene y le dio
2,0 a una entrega que no termina (fede) por sus reglas. **Ajuste manual**: la planilla guarda
`nota_motor`, `ajuste` y `comentario` del docente; la nota final es la ajustada.

## 6. Resultado y devolución

```jsonc
{ "format": "pentaho-live-grade-result/1", "pack": "ventas-por-producto", "student": "bruno_agregado_mal",
  "file": "bruno_agregado_mal.ktr", "status": "graded", "grade": 6.5, "scale": 10,
  "checks": [ { "check": "values", "label": "Valores correctos", "points": 4, "earned": 0.5, "passed": false,
                "detail": "visible 25%, oculto 0%" } ],
  "blocked_steps": [], "rules": { "usa_group_by": { "passed": true, "detail": "Found: [Totales por producto]" } },
  "cases": [ { "id": "visible", "hidden": false, "redirections": ["output 'Escribir totales': … -> salida/…"],
      "run": { "state": "finished", "seconds": 0.27, "errors": 0, "output_step": "Escribir totales",
               "captured_from": "file salida/03_ventas_por_producto.csv", "metrics": [ … ], "log": "…" },
      "comparison": { "columns": { "missing": [], "extra": [], "renamed_suggestions": [] },
        "rows": { "expected": 4, "actual": 4, "matched": 1, "different": 3, "missing": 0, "extra": 0, "duplicate_keys": 0 },
        "examples": { "different": [ { "key": "Café", "column": "importe_medio", "expected": "5.00", "actual": "7.50" } ],
                      "missing": [], "extra": [], "duplicate_keys": [] } } } ],
  "hints": [ "…" ], "timing": { "kettle_init_s": 6.0, "process_s": 8.0 } }
```

**Estados** (una sola lista para código, UI y planilla). Entrega (`status`): `graded` · `blocked`
(ningún caso corrió: política, conexión, `unexpected_input`/`_output`, D1, D2) · `timeout` (venció
algún caso) · `killed_timeout` (el padre mató al worker) · `load_failed` · `crashed`; `running` solo
en el parcial. Caso (`run.state`): `finished` · `finished_with_errors` · `timeout` · `too_many_rows` ·
`output_too_big` · `init_failed` · `start_failed` · `no_output_step` · `blocked`, con `reason`.

**Dos lectores.** `<alumno>.json` es el registro completo del docente. La devolución del alumno
(MD/HTML/PDF) no lleva, de los ocultos, valores, ejemplos ni `log` (un error de conversión imprime
filas de entrada): solo conteos y pistas.

**Pistas** [P, salvo marca]: patrones de `03-errores-comunes.md` en una frase para el alumno; van en
el JSON y se deduplican entre casos.

| Patrón en el resultado | Pista |
|---|---|
| Claves repetidas y hay Group by | D5: Group by agrupa filas consecutivas → Sort rows antes, o Memory group by |
| Valores ×100 exactos | D3: declarar el decimal `.` al leer |
| Diferencias que son el precio redondeado | D4: en Calculator, el campo decimal en `field_a` [NP] |
| Columna que falta / otra que sobra con los mismos valores | renombre |
| Números con coma decimal (aceptados) | aviso sin descuento, salvo `strict_format` [NP] |
| Claves con `�` o mojibake (`CafÃ©`) | declarar `UTF-8` en el step de salida (P17) [NP] |
| Step bloqueado / lectura fuera del pack | «no está permitido en este ejercicio» / «lee solo los datos del ejercicio» |
| `hasLoop` / nombres repetidos | D2 / D1, sin ejecutar [NP] |
| Merge join con claves perdidas | D6: ordenar las dos entradas [NP] |

**Render.** El JSON es el contrato; todo texto del alumno (steps, archivos, valores) se escapa.
Informes (0.5.0) sale antes y viene en el mismo jar: `CorreccionAInforme` arma un `Informe` con
secciones «Nota» (Indicadores), «Criterios» (Tabla), «Pistas» (Lista/Aviso), una por caso (Indicadores
+ Tabla de diferencias) y «Notas del corrector», y lo exporta con `InformeServicio.exportar(inf, tema,
formato, destino, progreso)` (HTML, HTML liviano para el `Browser` IE11, PDF, MD, PPTX; `Tema` de
`Ajustes.cargar()`). Curso: `Grafico` `barras_h` (distribución de notas, checks más fallados). No hay
renderizador propio: solo un `.md` mínimo en `CorrectorNota` para pruebas sin Informes.

## 7. Seguridad e integridad

Correr la `.ktr` de un alumno es correr código ajeno: Execute SQL, Shell, JavaScript (Rhino llega a
`java.lang.Runtime`), User Defined Java Class, Janino. La política reduce el riesgo; no lo elimina.

| Nivel | Tipos (id de plugin) | Qué pasa |
|---|---|---|
| Seguro | entradas de archivo y generadas (CsvInput, TextFileInput, ExcelInput, DataGrid, RowGenerator, GetVariable…), transformación (Calculator, SortRows, GroupBy, MemoryGroupBy, FilterRows, SelectValues, StreamLookup, MergeJoin, Constant, Dummy, Unique, ValueMapper, StringOperations, SwitchCase, Formula, Denormaliser…), salidas a archivo (redirigidas) | Corre, salvo las variantes de §3 (nombre desde un campo o URL) |
| Condicional | TableInput/TableOutput/InsertUpdate/Update/Delete/DBLookup/DBJoin/ExecSQL/ExecSQLRow/DimensionLookup/CombinationLookup/SynchronizeAfterMerge (solo contra la base del pack), SetVariable, Mapping/SimpleMapping/TransExecutor (solo `.ktr` de la entrega; política y reescritura **entran** en ellos [NP]); ScriptValueMod (**es ejecutar código**: la UI lo marca en rojo) | Corre si `allow_steps` del pack lo nombra [P] |
| Peligroso | UserDefinedJavaClass, Janino, ExecProcess, ShellScript, Mail, HTTP, HTTPPOST, Rest, WebServiceLookup, SSH, ProcessFiles, DeleteFile, RuleExecutor, MetaInject, JobExecutor, Socket*, S3FileOutputPlugin, SQLFileOutput, PropertyOutput, TextFileOutputLegacy (`is_command` corre un comando [B])… Jobs: SHELL, SQL, EVAL, FTP*, SFTP*, HTTP, MAIL, DELETE_* | Nunca, salvo «modo confianza» que el docente activa **en la UI** por corrida (el JSON lo registra) |
| Desconocido / plugin faltante | lo que no está en las listas | `unknown_steps`: `block` por defecto [P] |

La política mira **todos** los steps: un step suelto sin ningún hop también corre (P20). Solo no corre
el que tiene todos sus hops deshabilitados.

- **Proceso aparte** [P]: un `System.exit`, un hilo que no para o un `OutOfMemoryError` no tiran Spoon.
  Vigilante del padre **por entrega**: `timeout_s × casos + 60 s` desde que el worker la toma →
  `destroyForcibly` [P con JVM por entrega; NP con worker reusado].
- **Worker reciclado**: sirve varias entregas (P3), pero se descarta después de una que usó steps
  condicionales, que pasó el timeout o que cambió variables de JVM [NP].
- **`KETTLE_HOME` vacío** [P]: la entrega no ve el `shared.xml` ni el `kettle.properties` del docente.
- **Entorno fijo** (`-Duser.language/-Duser.country/-Duser.timezone/-Dfile.encoding` del pack): P9 y
  P16 dan 6,0 o 10,0 según el locale; P17, 8,5 o 10,0 según el charset (Java 8/11: nativo; 21: UTF-8).
  `create_exercise_pack` corre la solución con `es_ES` y `en_US` y avisa si difieren [NP].
- **Topes**: `max_rows` (también en `file`: el lector corta), `-Xmx1g`, y `max_output_mb` vigilado
  **cada segundo** sobre `salida/` + `tmp/` (P18: 54 MB en 5 s, ~650 MB por caso con 60 s) → `stopAll`
  y `output_too_big`. `-Djava.io.tmpdir` = `tmp/` del worker: los temporales de Sort rows se cuentan.
- **SQLite del pack**: `ATTACH DATABASE` y `VACUUM INTO` crean archivos en cualquier ruta; con
  ExecSQL/ExecSQLRow/TableInput habilitados se rechaza el SQL que los use (o `load_extension`) [NP].
- **Casos ocultos** contra la salida dibujada (un Data grid con las 4 filas pasa el visible y no el
  oculto); `must_use` exige el step conectado; `salida/` se borra apenas se captura.
- **Zip slip y bombas**: cada entrada del zip (pack o entrega) queda dentro de la carpeta destino, con
  tope descomprimido. **Privacidad**: notas y nombres quedan en la PC del docente; el plugin no manda
  nada por la red (`PRIVACY.md`); lo que intente la entrega lo frena la política.
- **Texto del alumno** (steps, notas, logs, valores) llega a Claude en las respuestas: es dato, no
  instrucción (línea en `INSTRUCCIONES`), y por MCP no se activa el modo confianza.
- Para entregas de desconocidos de verdad: una VM o un contenedor. Lo decimos en la UI y en la skill.

## 8. Herramientas MCP

`CorrectorTools.register(server)`: 54 + 5 de Modo clase + 5 de acá + las del linter. Corren en el pool
propio; las que ejecutan lanzan workers y nunca abren diálogos. `document` se resuelve con
`Docs.resolverSinActivar` (Informes WP10) y se clona con `ReportTools.clonar`.

| Tool | Argumentos | Respuesta |
|---|---|---|
| `create_exercise_pack` | `document?` (la solución; por defecto la pestaña activa), `pack_path` (carpeta o `.zip`), `title`, `statement?` (Markdown; si falta, esqueleto con las columnas de salida), `inputs?` (por defecto, `getResourceDependencies()` de las entradas), `cases?` `[{id, inputs_dir, hidden}]`, `output_step?`, `capture?`, `key_columns?` (por defecto, los campos del último Group by o del último Sort rows; se verifica que sean únicas), `compare?`, `rules?`, `rubric?`, `policy?`, `check_locales?` (true), `student_copy?` (ruta) | `{pack_path, cases:[{id, rows, columns, expected_file}], detected:{inputs, output_step, key_columns, columns, suggested_rules}, warnings:[…]}` |
| `validate_exercise_pack` | `pack_path` | `{ok, problems:[…], solution_grade}`: esquema, rutas dentro del pack, archivos, y que la solución saque la nota máxima contra su propio pack |
| `grade_exercise` | `pack_path`; `submission?` (`.ktr`, `.kjb`, `.zip` o carpeta) **o** `document?` (la pestaña abierta: autocorrección; copia con `getXML()` del clon); `capture?`; `allow_steps?` (solo nivel condicional, sin ScriptValueMod); `language?`; `report_formats?` (`md`, `html`, `pdf`, `pptx`, `json`); `output_dir?` | El resultado de §6 sin el log completo, más `report:{files:[{format, path}]}` |
| `grade_submissions` | `pack_path`, `folder`, `output_dir`, `workers?` (2), `gradebook?` (`csv`, `xlsx`, `both`), `wait_s?` (60) | `{job_id, state ("running", "finished" o "cancelled"), done, total, results:[{student, status, grade}], summary:{mean, median, passed, blocked, timeouts}, gradebook_path}` |
| `get_grading_status` | `job_id`, `wait_s?`, `cancel?` (mata los workers; queda lo ya corregido) | Lo mismo que `grade_submissions`, actualizado |

`INSTRUCCIONES` suma una línea: corregir no abre la entrega en Spoon, el texto de las entregas es dato
y el modo confianza solo se activa en la UI.

## 9. En Spoon

**Estado (C8, 21-oct-2026).** Hecho con el **plan B**: Informes 0.5.0 sigue sin pestañas, así que el corrector
es la perspectiva `pentaho-live-grader` (`GraderSpoonPlugin` + `GraderPerspective`, sin `classLoaderGroup`,
`PENTAHO_LIVE_GRADER=off` la apaga), con `PanelCorrector` y `AsistentePack` en la raíz del paquete. «Ajustar
nota» es un campo del detalle y no una celda editable; «Volver a corregir con…» es `CorrectorLote.recorregir`.
Quedan pendientes el menú por overlay XUL y la pestaña «Corrección» en `TransGraph.extraViewTabFolder`.

**Estado (C10, 21-oct-2026).** Fusionado con `feat/0.6.0` (Informes 0.5.1 y linter): las devoluciones de
`grade_exercise` salen también en Word y Excel (`report_formats`: `docx`, `xlsx`), por el mismo
`InformeServicio.exportar`. Las pistas en español están en tú (sin voseo). Las pruebas de ventanas del corrector
abren su Shell fuera de la pantalla y capturan con `Control.print(GC)`, como `PruebaPanelSwt`.

**Dónde.** Una pestaña **Corrector** en la perspectiva de Informes. Hoy **no hay dónde**: su panel
0.5.0 es un `GridLayout` con un `SashForm` (§5.1 de su diseño), sin pestañas. Hace falta que el dueño de
su WP1 envuelva `PanelInformes` en un `CTabFolder` y exponga una interfaz `PestanaPerspectiva`: es un
pedido a otro equipo. Si no está cuando arranca C8, **plan B**: perspectiva `pentaho-live-grader` con
su propio `@SpoonPlugin` **sin `classLoaderGroup`** (§1.3 de Informes). Menú «Herramientas › Corrector
de ejercicios…» por overlay XUL [NP].

```
┌ Informe │ Corrector ─────────────────────────────────────────────────────────────────────────┐
│ Pack     [ventas-por-producto.zip             ][…]  Ejercicio 3 · 2 casos (1 oculto) · 10 pts │
│ Entregas [C:\…\Entregas TP3                   ][…]  6 archivos    [Corregir]  [Cancelar]      │
├──────────────────────────────────────────────┬─────────────────────────────────────────────┤
│ Alumno         Nota  Estado    Run Col Fil Val│  Corrección: bruno_agregado_mal.ktr         │
│ ana            10,0  bien       ●   ●   ●   ● │  Nota 6,5 / 10                              │
│ bruno           6,5  bien       ●   ●   ●   ◐ │  Valores correctos 0,5/4 (visible 25 %, …)  │
│ carla           9,7  bien       ●   ◐   ●   ● │  Café · importe_medio · 5.00 · 7.50         │
│ diego           1,5  bloqueada  ○   ○   ○   ○ │  …  (Browser: devolución HTML estática)     │
│ elena           5,5  bien       ●   ●   ○   ◐ │                                             │
│ fede            2,0  timeout    ○   ○   ○   ○ │ [Abrir su .ktr] [Volver a corregir con…]    │
├──────────────────────────────────────────────┴─────────────────────────────────────────────┤
│ ▓▓▓▓▓░ 5/6 · media 5,9 · 1 bloqueada · 1 timeout   [Exportar notas ▾] [Exportar devoluciones] │
└────────────────────────────────────────────────────────────────────────────────────────────┘
```

- La tabla se llena a medida que terminan los workers; se ordena por columna; doble clic = detalle.
- **Abrir su .ktr** abre la copia reescrita del **caso visible**, que se conserva con sus `datos/` en
  `output_dir/_abiertos/<alumno>/` (los ocultos se borran), como pestaña nueva y no el original, para
  no guardar encima de la entrega. Por clic: `loadPerspective(MainSpoonPerspective.ID)` y
  `spoon.openFile` (como `open_file`).
- **Volver a corregir con…** (permitir steps o modo confianza; queda registrado) y **ajustar nota**
  (celda editable + comentario → columnas `ajuste`/`comentario` de la planilla).
- **Crear pack desde esta transformación** (botón y acción sobre la pestaña activa): asistente de 4
  pasos (entradas detectadas, step de salida y claves, reglas sugeridas, rúbrica) que llama a lo mismo
  que `create_exercise_pack` y muestra la salida esperada antes de guardar.
- **Alumno**: con la copia para alumnos, «Corregir» sobre la pestaña activa da la devolución del caso
  visible. A evaluar: una pestaña «Corrección» en `TransGraph.extraViewTabFolder`, como «Lección» de
  Modo clase, para no salir del lienzo.
- Reglas de Informes que valen acá: diálogos solo por clic del usuario, todo en `try/catch
  (Throwable)`, nada pesado en el hilo de UI, textos de `Rotulos` en en/es/pt.

## 10. Lote y planilla

- **Pool de workers** [P para 1 worker y para JVM por entrega]: `workers` JVMs (2 por defecto), cada
  una con una cola. ~6 s de arranque por worker + ~0,5 s por entrega chica: 40 entregas con 2 workers
  ≈ 30 s, contra ≈ 5 min con una JVM por entrega. Cada cuelgue suma `timeout_s × casos` + ~6 s.
- **Lanzar la hija**: cwd = `<PDI>` y `-DKETTLE_PLUGIN_BASE_FOLDERS=<PDI>/plugins` (si no, faltan los
  steps de `plugins/`, como JSON o Excel); classpath `<PDI>/classes;<PDI>/lib/*;<PDI>/libswt/<os>/*;
  <PDI>/plugins/pentaho-mcp/*.jar`. `<PDI>` sale de la carpeta del plugin
  (`PluginInterface.getPluginDirectory()` → `../..`) [NP]: en Spoon, `java.class.path` es solo el
  launcher. `java` = `java.home/bin/java`. Flags: los que puso `Spoon.bat`, leídos de
  `RuntimeMXBean.getInputArguments()` (`--add-opens`, `-Djava.locale.providers=COMPAT,SPI`,
  `-Djava.endorsed.dirs`): con Java 8 no hay `--add-opens` (esa JVM no arranca con el flag) y con 9+
  está también `COMPAT,SPI`, que el prototipo no usó [B, `Spoon.bat`].
- **Protocolo**: el padre escribe `trabajo.json` y lanza `java … io.github.ivi2511.pentahomcp.CorrectorMain
  trabajo.json`; la hija escribe cada `<alumno>.json` (primero el parcial) de forma atómica (temporal +
  rename) y el padre mira esos archivos. stdout/stderr van a `_logs/worker-N.log`: un `Write to log` del
  alumno no imita el protocolo ni traba un pipe. `CorrectorMain` también corre por línea de comandos
  (base de las pruebas de regresión de 0.7).
- **Identidad**: nombre del archivo o de la subcarpeta; opcional `alumnos.csv` (`archivo;alumno;email`).
  Varios `.ktr` en una subcarpeta: el pack dice cuál (`submission_file`) o el único con salida. Dos
  entregas con la misma identidad (`ana.ktr` y `ana.kjb`): sufijo `_2` y aviso, nunca se pisa.
- **Planilla** [P en CSV]: UTF-8 con BOM, `;`, coma decimal. Columnas `alumno;archivo;estado;nota;
  <un check por columna>;segundos;devolucion` + `nota_motor;ajuste;comentario`. Texto entre comillas
  y `'` delante si empieza con `= + - @`: el nombre de archivo lo elige el alumno (el prototipo no
  escapa y un `;` corre las columnas). `.xlsx` con POI XSSF en el pool de Informes, con el context
  classloader del plugin (su riesgo 12); sin POI en el classpath, CSV solo.
- Carpeta de salida: `notas.csv`/`.xlsx`, `<alumno>.json|md|html`, `_logs/`, `_abiertos/` y `_sandbox/`
  (se borra al terminar salvo `keep_sandbox`; en Windows, cuando la hija salió: P12).

## 11. Riesgos y preguntas abiertas

| Riesgo | Mitigación |
|---|---|
| No es un sandbox real (JavaScript, UDJC, Janino si se habilitan) | Política, proceso aparte, `KETTLE_HOME` vacío, modo confianza solo por UI; VM para desconocidos |
| Resultado distinto según locale, zona y charset (P9, P13, P16, P17) | El pack fija `locale`, `timezone` y `encoding`; números con el formato del alumno; doble corrida de la solución |
| Rutas que no se pueden redirigir (nombre armado en el flujo, nombre desde un campo, comodines, Get file names) | Parámetros del pack; bloqueo con `unexpected_input`/`_output` y el motivo en la devolución |
| Reescritura genérica que toca lo que no es ruta (P14) | Excepciones de §3 y chequeo de nombres y hops después |
| Formatos que el enunciado no fijó (fechas, miles, coma decimal) | Tipos del pack, formato del alumno, `date_formats`; `strict_format` si el docente lo quiere |
| Menores: proyección de `step` solo para Text file output; 6 s por JVM; archivos trabados en Windows (P12) | Fila completa, documentado; pool de workers (P3); streams propios y borrar cuando la hija salió |
| Depende de un gancho de pestañas que Informes no tiene | Pedido a su WP1; plan B: perspectiva propia |
| Se pisa con el linter (D1, D2, `unsorted_input`) y con las pruebas de regresión de 0.7 | Un solo motor de reglas (`LintMotor`) y un solo motor de corrida (`CorrectorMain`), reusados |
| Archivos compartidos con Modo clase, linter e Informes (`McpLifecycleListener`, `plugin.json`, `SKILL.md`, README, CHANGELOG; `manual/04-*` ya lo piden dos) | Una línea de registro por equipo; el coordinador fusiona y numera el manual |
| Jobs y bases que no son SQLite | 0.6: jobs con `file`/`query` sobre SQLite [NP]; MySQL, Postgres fuera de alcance |

Preguntas para Iván: ¿escala 10 con redondeo a 0,1 está bien para la UEM?; ¿las reglas suman aunque no
corra (fede 2,0) o van con compuerta por defecto?; ¿coma decimal u otra máscara de fecha restan por
defecto o solo con `strict_format`?; si el alumno renombró la entrada (`ventas (1).csv`) y hay una sola
de ese tipo, ¿se redirige con aviso o se bloquea?; ¿la autocorrección del alumno entra en 0.6?

## 12. Paquetes de trabajo

Convenciones de Informes §7.1: Java 8, identificadores en castellano, formato Pentaho, un solo dueño
por archivo, pruebas `Prueba*.java` en `spoon-plugin/src/test/java/io/github/ivi2511/pentahomcp/`
corridas por `test.ps1` (lo crea Informes WP0), nada escribe en `data-integration\` ni en el
`~\.kettle` real. Todo en `io.github.ivi2511.pentahomcp` (así ve `RunMotor`, `Docs` y `ReportTools`,
que son *package-private*) salvo los POJOs (`corrector.modelo`) y la UI SWT (`informes.ui`, o la raíz
en el plan B). Textos: `informes/textos/corrector_{en,es,pt}.properties`, área `corrector` de `Rotulos`.

| WP | Qué | Archivos (nuevos salvo *mod*) | Listo cuando |
|---|---|---|---|
| **C0** | Contrato | `corrector/modelo/{Pack,Caso,OpcionesComparar,Regla,ItemRubrica,PoliticaPack,ResultadoCorreccion,ResultadoCaso,Comparacion}.java` (POJOs Jackson, con los estados de §6), `src/test/resources/corrector/` (los 2 packs del prototipo, sus 10 entregas y las 7 de la revisión) | Ida y vuelta JSON del pack y de un resultado de muestra |
| **C1** | Pack | `CorrectorPack.java` (carpeta, zip con zip slip y tope, `.json` inline; esquema, rutas, archivos), `PruebaCorrectorPack.java` | Carga los tres formatos; rechaza `..`; errores legibles |
| **C2** | Comparación | `CorrectorTabla.java` (CSV con BOM, encierro, separador, encoding), `CorrectorComparar.java` (tipos del pack, formato del alumno), `PruebaCorrectorComparar.java` (sin Kettle: clave, multiconjunto, posición, tolerancias, renombres, duplicados, fechas, coma decimal) | Resultados del prototipo con tablas fijas; la salida de P16 da 10 |
| **C3** | Sandbox y política | `CorrectorSandbox.java` (reescritura con excepciones, conexiones usadas, `unexpected_input`/`_output`, chequeo de nombres y hops), `CorrectorPolitica.java` (niveles, D1 en el XML, D2, entradas desde campo, SQL de SQLite), `PruebaCorrectorSandbox.java` | ana redirigida; diego bloqueado; fede sin ejecutar; P14 y P15 dan 10; leer `../../<otro>/` bloquea |
| **C4** | Ejecución y worker | `CorrectorMotor.java` (un caso: bucle propio con helpers de `RunMotor`, vigilante de tamaño, captura `file`/`step`/`query`), `CorrectorMain.java` (main de la hija, `trabajo.json`), `CorrectorProcesos.java` (flags según Java, classpath, pool, vigilante por entrega, kill, reciclado), `PruebaCorrectorMotor.java` | Notas del §1 en una hija lanzada desde la prueba; P9 con el `locale` del pack; P17 nativo da 10; P18 corta en `max_output_mb` |
| **C5** | Nota | `CorrectorNota.java` (rúbrica, compuertas, `lint:*`, pistas al JSON, `.md` mínimo), `PruebaCorrectorNota.java` | Notas y pistas del prototipo desde resultados fijos (sin C4) |
| **C6** | Lote, planilla y creación | `CorrectorLote.java` (trabajos, progreso, `job_id`, cancelar), `CorrectorPlanilla.java` (CSV escapado; XLSX si hay POI), `CorrectorCrearPack.java` (entradas, salida, claves únicas y tipos; solución en es/en; copia para alumnos), `PruebaCorrectorLote.java` | 17 entregas con 2 workers; planilla abre en Excel; `=1+1;x.ktr` no rompe columnas; el pack creado valida |
| **C7** | MCP | `CorrectorTools.java` (5 tools); *mod* `McpLifecycleListener.java` (registro + una línea de instrucciones), `PruebaMcpCorrector.java` | Esquemas válidos; `grade_exercise` de punta a punta; sin modo confianza por MCP |
| **C8** | Spoon | `informes/ui/PanelCorrector.java`, `informes/ui/AsistentePack.java`; plan B `GraderSpoonPlugin.java` + `GraderPerspective.java` | Pestaña en el *harness* SWT de Informes; prueba en Spoon real (Iván) |
| **C9** | Puente con Informes | `CorreccionAInforme.java` | El resultado de bruno en HTML, HTML liviano (IE11), PDF y PPTX con los renderizadores de Informes |
| **C10** | Docs y skill | *mod* `README*.md`, `CHANGELOG.md`, `docs/roadmap.md`, `claude/skills/pentaho-etl/SKILL.md`; nuevo `manual/07-corrector.md` y el pack de ejemplo `ejemplo/pack-correccion/` con sus entregas de ejemplo (la `version` de `plugin.json` la sube el release) | Revisión de Iván |

**Dependencias:**

```
C0 ──► { C1, C2, C3, C5 }        (C2 y C5 no necesitan Kettle; C5 usa resultados fijos)
{ C1, C3 } ──► C4 ──► C6 ──► { C7, C8 }
C0 + Informes 0.5.0 ──► C9        (en paralelo desde el principio)
C5 usa LintMotor para lint:* y D1/D2 si el linter ya fusionó; si no, D1/D2 propios y lint:* = "not evaluated"
todo ──► C10
```

C8 depende además del gancho de pestañas de Informes; sin él, hace el plan B sin tocar sus archivos.
