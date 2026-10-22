# Linter de ETL: diseño (Pentaho Live 0.6.0)

Diseño del **linter** de la fase «Calidad y clases» (`docs/roadmap.md`): revisa una transformación o un
job y marca lo que Kettle deja pasar (rutas fijas, contraseñas, Group by sin ordenar, la trampa del
decimal…), con un puntaje 0-100 transparente y arreglos que se deshacen con un solo Ctrl+Z (en
transformaciones; en jobs Spoon no encadena acciones).
Fecha: 20-oct-2026. Estado: **diseño + prototipo headless**; no se tocó código del plugin.

Marcas: **[P]** probado con el prototipo o con Pan/Kitchen · **[B]** leído del bytecode de PDI 9.4 · **[S]** ya
existe en el plugin 0.4 · **[NP]** no probado.
Prototipo (descartable): `%TEMP%\claude\…\scratchpad\v06\linter\` → `src/Lint.java` (motor, 30 reglas con las
podadas, y sondas), `mutar.py`, `final.py` (severidad final y puntaje), `autofix.py`, `res_final/hits.tsv`
(838 hallazgos crudos). Ids en kebab-case en el prototipo y en snake_case en el plugin (`lint:<id>`).
**Revisión adversarial (20-oct):** pruebas extra (Kitchen, atributos en jobs, `checkSteps`, `final.py`) en
`scratchpad\v06\linter-review\`; lo corregido está en el texto, lo abierto en §13.

---

## 0. Decisiones en una tabla

| Tema | Decisión |
|---|---|
| Qué es | Un motor de reglas propio (`lint/`, solo API de Kettle) que corre sobre una **copia desligada** del meta (`ReportTools.clonar` [S], como `validate`), en segundo plano, sin diálogos. No reemplaza a `validate`: lo complementa y comparte código. Lo que necesita clases *package-private* del plugin (`RunMotor`, `TransOps`, `UndoGrupo`, `Docs`) vive en el paquete base (§11). |
| Fuente de verdad | El **XML de cada step** (`StepMeta.getXML()`) más el grafo de `TransMeta`/`JobMeta`. Así las reglas no dependen de `instanceof` (los steps de plugins viven en otros classloaders) y valen igual para el meta vivo y para un archivo. Los tipos de campo salen de `getPrevStepFields` con timeout. |
| Dos niveles | **Estático** (siempre; ~0,1 s por documento si no hay que calcular campos contra una BD) y **profundo** (`deep=true`): previews recortadas que **leen** fuentes, para confirmar o descartar lo que el estático no puede saber (orden real, separador decimal). Solo corren si **todos** los steps del recorte están en una lista blanca de solo lectura (§3.3). |
| Severidades | `error` = el resultado sale mal, no corre o deja una contraseña en claro · `warning` = frágil o depende de la máquina · `info` = estilo/documentación. El modo profundo puede subir o bajar la severidad de un hallazgo concreto. |
| Puntaje | 100 − penalidades por regla con tope, tope global para `info`, y **compuerta**: con un `error` el máximo es 74 (§4). `kettle_check` no resta. |
| Arreglos | Cada hallazgo trae `fix` (automático, con o sin confirmación) o `how_to_fix` (a mano). `apply_lint_fixes` aplica varios con `UndoGrupo`: **un Ctrl+Z en transformaciones**; en jobs, uno por acción (Spoon no encadena, ver `JobSoporte`). |
| Silenciar | Atributos de Kettle (`setAttribute("pentaho-live-lint","ignore",…)`) en el step/entrada o en el documento: viajan dentro del `.ktr` y del `.kjb` [P]. `setAttribute` no marca el documento como cambiado [P]: hay que llamar `setChanged()`. |
| En Spoon | Sección **«Calidad»** del informe (perspectiva Reports). Clic en un hallazgo = seleccionar el step y menú «Revisar calidad»: **[NP]**, dependen de piezas del equipo de Informes (§8). |

## 1. Qué agrega sobre `validate` / `checkSteps`

Se corrió `TransMeta.checkSteps` (el «Verificar» de Spoon) sobre las 19 transformaciones del corpus de
mutaciones (§3.1), cada una con **un** error conocido de `03-errores-comunes.md` [P, re-corrido]: detectó
**3** (hop deshabilitado y nombres duplicados, ambos de forma indirecta, y el step huérfano); en `m10` da 3
errores, pero de **conexión** a la BD. Con el ciclo (D2) **revienta con `StackOverflowError`** [P]. El
prototipo del linter detectó las **21** (19 ktr + 2 kjb; `m15` solo como `info`, a propósito). `validate` ya
suma huérfanos, hops deshabilitados, reparto, plugins que faltan, hops rotos, job sin START y entradas
inalcanzables [S]: pasan al motor del linter (ids al final de §2) y `validate` las llama desde ahí.

## 2. Catálogo de reglas

Severidad: E error, W warning, I info. «Auto» = `apply_lint_fixes` lo hace sin preguntar (entra en
`all_auto`): no cambia datos ni estructura del lienzo. «Auto+c» = lo hace, pero el cliente confirma antes con
la persona (lo exige la descripción): cambia filas, formato de salida, rutas o steps. «Asistido» = falta un
valor de Claude o de la persona (`values[key]`) y después va como auto+c.

| id | Sev | Qué detecta y por qué importa | Cómo se detecta (metadatos) | Arreglo |
|---|---|---|---|---|
| `unsorted_input` | W (E con prueba) | Group by, Merge join, Unique rows, Merge rows (diff), Row denormaliser, Analytic query o Sorted merge reciben filas **no ordenadas por la clave**: grupos partidos o cruces perdidos, con exit 0 (D5, D6). | Sube por la entrada principal (`findPreviousSteps(s,false)`; en Merge join, `step1`/`step2`) hasta: **Sort rows** con las claves como prefijo (ok; si no, «ordena por otra clave»); **Table input** con `ORDER BY` que nombra las claves (ok); Generate rows (constante, ok); **Data grid** (mira los datos); Group by/Merge join/Sorted merge con esas claves (ok); fuente sin orden (W); step con >1 copia o >1 entrada (W: orden no determinista). Select values traduce renombres. Si una clave no existe a la salida del Sort (la crea un step posterior) o hay Memory group by, script, Mapping o Join rows en el camino: **no se juzga**. Para agrupar (Group by, Unique, Denormaliser, Analytic) alcanza el conjunto de claves como prefijo; para **Merge join, Merge rows y Sorted merge** además orden **ascendente** y las claves **en el mismo orden** (comparan con `RowMeta.compare`) [NP: el prototipo solo mira el conjunto]. Unique rows sin campos compara la fila entera: claves = todas (el prototipo la saltea). `ORDER BY` con claves String puede no coincidir con la comparación de Kettle (collation de la BD): ok, con nota en Merge join [NP]. Profundo: §3.3. | auto+c: insertar Sort rows por las claves delante de cada entrada (y repuntar `step1/step2`). Nunca en `all_auto`: el estático tiene 60 % de FP con archivos ya ordenados. |
| `calculator_integer_first` | E / W | Calculator `ADD/SUBTRACT/MULTIPLY/DIVIDE` con **A Integer y B Number/BigNumber**: B se redondea (D4; 3 × 2,5 = 9). `DIVIDE` Integer/Integer con resultado Number: **división entera** (101/3 = 33) (W). | Tipos con `getPrevStepFields` (timeout 20 s) + los resultados anteriores de la misma Calculator. `ValueDataUtil.multiply/divide` eligen la operación por el tipo de A (`ldiv` para Integer) [B]. | auto: `MULTIPLY`/`ADD` → intercambiar A y B. `SUBTRACT`/`DIVIDE`: a mano (pasar A a Number con Select values → Metadatos). |
| `empty_decimal_symbol` | W (E o I con profundo) | Number/BigNumber leído de texto (CSV, Text, Fixed input) con **decimal vacío**: usa el separador del locale de la máquina. En es_ES, `2.50` se lee **250** si no hay formato, o falla si el formato es `#.##` (D3). Data grid/Generate rows: solo si el valor literal trae `.` o `,`. Salida: Text file output sin campos escribe `20,0` en es_ES (efecto [P]; la regla del lado salida, [NP]). | `<field>` con `<type>` Number o BigNumber y `<decimal/>` vacío. Profundo: cuenta valores con `.`/`,` en esa columna (hasta 2000 filas): con separadores → E (aunque en esta JVM lea bien, el resultado depende del locale del lanzador); todos enteros → I **solo si leyó la entrada entera**. Leer la columna con el parser del propio step (preview de una copia con ese campo como String), no partiendo líneas a mano: el prototipo leyó **0 valores** de los archivos de ancho fijo [P] (§3.2). 0 valores → `not_evaluated`, queda W. | auto+c: `decimal` = `.` o `,` según el archivo; `group` vacío, o el otro separador si la columna trae los dos (`1.234,56`) [NP]. |
| `variable_in_constant` | E | `${X}`/`%%X%%` en el valor de Add constants o Generate rows: sale **literal** (D9). | `<nullif>` con variable. **No** en Data grid (lo resuelve el consumidor, p. ej. Job executor: 2/2 falsos positivos). | a mano: Get variables. |
| `text_output_without_fields` | W / I | Text file output **sin campos declarados**: Strings rellenados con espacios hasta su longitud (D8) y `OutOfMemoryError` con longitudes de SQLite (C23). | `<fields>` vacío. W si llega algún String con longitud > 0 (`getPrevStepFields`); si no, I (hoy no rellena). | auto+c: declarar los campos que llegan; Strings con `length -1`, números y fechas con el formato y la precisión que traen (cambia el archivo de salida: alguien puede querer el ancho fijo). |
| `duplicate_step_name` | E | Dos steps con el mismo nombre **ignorando mayúsculas**: `TransMeta.findStep` usa `equalsIgnoreCase` [B]; al cargar uno desaparece (D1). | Solo en el XML del archivo (en memoria ya no se ve). Sin recortar espacios: `"X"` y `"X "` son distintos para Kettle. | a mano (en el `.ktr`). |
| `cycle` | E | Hop circular: loop infinito (D2); además `checkSteps` y `getPrevStepFields` caen con `StackOverflowError` [P]. | `TransMeta.hasLoop(step)`. **Corre primero**: si hay ciclo, las reglas que calculan campos no corren. | a mano. |
| `failure_branch_ends_in_success` | E / W | La rama roja de un job termina en **Success**: Kitchen devuelve 0 aunque falló (D10). E si es directo; W si llega por hops **incondicionales**. Por hops verdes no se marca: depende de la entrada del medio (Write to log arrastra el `false` y Kitchen da 1 [P, revisión]). | Hops con `evaluation=N` cuyo origen **no es una condición**: se excluyen la categoría *Conditions* (File exists, Simple evaluation, Table exists…) y `EVAL`, `PING`, `TELNET`, `XSD_VALIDATOR`, `DTD_VALIDATOR`, `XML_WELL_FORMED`, `WAIT_FOR_FILE`, `WAIT_FOR_SQL`, `CONNECTED_TO_REPOSITORY`: su rama roja es el «si no» (File exists → rojo → Success = exit 0 legítimo [P, revisión]). | auto+c: cambiar el destino por un Abort. |
| `start_not_named_start` | E | El job no arranca: `Job.execute` busca `findJobEntry("START",0,false)` → nombre START **sin importar mayúsculas**, `nr` 0 y dibujada [B] (C24). | Hay una entrada `isStart()` pero `findJobEntry("START",0,false)` es null. | auto: renombrar a `START`. |
| `job_variable_in_transformation` | W | `${Internal.Job.*}` dentro de un `.ktr`: con Pan vale «Parent Job File Directory» (D11). | Texto de los atributos de archivo del step. | auto+c: `${Internal.Entry.Current.Directory}`. Cambia el significado si el job que la llama está en otra carpeta. |
| `disabled_hop` | W | Hop deshabilitado: el destino no corre (D7). | `!hop.isEnabled()`. | a mano (suele ser a propósito). |
| `orphan_step` / `orphan_entry` | W | Step o entrada **dibujada** sin hops: nunca corre. | `!isStepUsedInTransHops` / `!isEntryUsedInHops` y `isDrawn()`. | a mano. |
| `distribute_multiple_outputs` | W (I) | 2+ salidas en modo **repartir**: cada salida recibe una parte. I si todas las salidas son el mismo tipo de step (reparto de carga a propósito). | `isDistributes()` + `salidasNormales` (como `TransOps`). | auto+c: modo copiar (cambia cuántas filas recibe cada salida). |
| `absolute_path` | W | Ruta fija (`C:\…`, `\\servidor\…`, `file:///…`, `/home/…`): solo funciona en esa máquina. | Hojas del XML del step/entrada cuyo tag es de archivo (`file`, `dir`, `folder`, `path`, `<file><name>`; sin `xpath`, `field`, `mask`, `extension`…) con valor que empieza con unidad, UNC, `file:` o `/x/y`. Sin tags de rutas **remotas** (`remote*`, `ftp*`, `sftp*`, servidor, URL): `/home/u/in` en un SFTP es del servidor [NP]. | auto+c: `${Internal.Entry.Current.Directory}/<relativa>` si queda a ≤2 niveles; si no, parámetro `DIR_DATOS` con ese default. |
| `relative_path` | E | Ruta relativa sin variable: se resuelve contra la carpeta de PDI, no la del `.ktr` (C15). | Igual que arriba; tiene `/`, `\` o extensión conocida; no empieza con `${`, `%%`, `esquema://` ni `/x` de un segmento (rutas de repositorio). | auto+c: anteponer `${Internal.Entry.Current.Directory}/`. |
| `missing_input_file` [NP] | W | Archivo de entrada que no existe tras resolver variables (el prototipo encontró `ventas_por_ciudad.ktr` apuntando a una carpeta que ya no existe). | `getResourceDependencies` + `KettleVFS.getFileObject(...).exists()`, solo steps de entrada. | a mano. |
| `undeclared_variable` | I | `${X}` usada que no es parámetro, ni `Internal.*`, ni está en `kettle.properties`/variables de Spoon: si nadie la define queda literal (C14). | Regex sobre el XML de los steps. | asistido: declarar parámetro con default. |
| `parameter_without_default` | I | Parámetro sin default (con Pan queda vacío). No cuenta si el nombre es de credencial (`PASS`, `USER`, `TOKEN`, `SECRET`, `KEY`). | `listParameters` + `getParameterDefault`. | a mano. |
| `password_in_file` | E / W | Contraseña guardada en el archivo: **en claro** (E) u «`Encrypted 2be98…`» (W: es ofuscación reversible). `${VAR}` está bien. Conexiones, slave servers y steps (FTP, mail…). | Modo archivo: tags `*password*` del XML crudo. En memoria `DatabaseMeta.getPassword()` ya viene descifrada y Spoon guarda siempre `Encrypted`: ahí es W. | asistido: `${<CONEXION>_PASSWORD}` + explicar `kettle.properties`; nunca escribe la clave en otro lado. |
| `default_step_name` / `default_entry_name` | I | Nombre por defecto del plugin, con o sin número («Text file output 2»): el diagrama no explica nada. | Nombres por defecto en en/es/pt (293 steps y entradas × 3 idiomas) + `\s+\d+$`. `PluginRegistry` solo tiene los del idioma con que arrancó Spoon: la lista en/es/pt se genera al compilar (como hizo el prototipo, un arranque por idioma) y se le suman los del registro (plugins de terceros). | auto (dentro del documento); auto+c si el step lo nombra otro archivo (Mapping input/output, destino de Metadata injection). Claude puede pasar nombres mejores. |
| `no_description` | I | Sin descripción ni notas. | `getDescription`, `getExtendedDescription`, `nrNotes`. | asistido: Claude la redacta. |
| `select_star` | I | Table input con `SELECT *`: el diseño cambia si cambia la tabla. | Regex sobre `<sql>`. | a mano. |
| `kettle_check` | E / W | Lo que dice `checkSteps`/`checkJobEntries` (opcional, `include_kettle_checks`). **No resta puntos ni activa la compuerta**: mezcla problemas del entorno (en `m10` da E por no poder conectarse a la BD [P]). | `RunMotor.validar`. | según Kettle. |
| `missing_plugin` | E | Step o entrada cuyo plugin falta: no corre. | `isMissing()` [S, hoy en `validate`]. | a mano. |
| `broken_hop` | E | Hop con un extremo que no existe. | `getFromStep()/getToStep()` null [S]. | a mano. |
| `job_without_start` | E | Job sin entrada START. | `findStart()` null [S]. | a mano. |
| `unreachable_entry` | W | Entrada con hops a la que no se llega desde START por hops habilitados: nunca corre. | Recorrido por nombre+nr [S]. | a mano. |
| `db_output_without_error_handling`, `job_entry_without_failure_hop` | I | Las podadas de §2.1: **apagadas por defecto** y fuera del puntaje; solo corren si se piden en `rules` (corrector `error_handling`, modo clase). | Las del prototipo. | — |

Cada hallazgo marca `env: true` si depende de la máquina (`missing_input_file`, `undeclared_variable`,
`kettle_check`, lo que cambie el modo profundo): el corrector no los usa (§9). Las reglas de **modo archivo**
(`duplicate_step_name`, contraseña en claro) leen el disco: sobre un documento abierto solo corren si no tiene
cambios sin guardar (`hasChanged()` / `Captura.sinGuardar`); si no, `not_evaluated` (el disco ya no es lo que se ve).

### 2.1 Reglas podadas (con números del prototipo)

| Regla | Por qué no |
|---|---|
| TRANS/JOB sin rama de error (25 hallazgos) | Sin hop rojo el job ya termina con error y Kitchen devuelve 1: no hay falla silenciosa. Queda como regla apagada (`job_entry_without_failure_hop`) para modo clase y corrector. |
| Salida a BD sin manejo de errores (23) | Fallar entero es el comportamiento correcto por defecto; desviar errores sin mirarlos es peor. Regla apagada (`db_output_without_error_handling`): es lo que el corrector llama `error_handling`. |
| Lógica duplicada (19) | ≥60 % legítimo: dos Sort rows iguales antes de un Merge join, el mismo mapping llamado varias veces. |
| Conexión sin usar (2) | 2/2 falsos positivos: la usa código JavaScript/UDJC. |
| Stream lookup con fuente enorme (0) | «Enorme» no se sabe estáticamente. |
| Conversión perezosa (1 CSV con `lazy=Y` en 189 samples, inofensivo) | No se pudo mostrar un daño en 9.4 [NP]; candidata para un experimento en la 0.7. |
| Variable en Data grid (2) | 2/2 falsos positivos (ver `variable_in_constant`). |
| Campos de más hacia la salida | Text file output con campos declarados ignora los demás; Table output falla fuerte. Poco valor. |

## 3. Prototipo y números

### 3.1 Corpus y método

`Lint.java` corre con `KettleEnvironment.init`, cwd = `data-integration`, sin Spoon. Carga cada archivo
con `new TransMeta(path)` / `new JobMeta(path,null)` (como lo vería el plugin) y además lee el XML crudo.

| Corpus | Docs | Steps / entradas |
|---|---|---|
| `samples` de PDI 9.4 | 189 ktr + 19 kjb | 977 / 96 |
| Ejemplos de la skill | 7 + 2 | 31 / 9 |
| Trabajos reales (solo lectura): `pruebas-mcp` (4) y Big Data 1 (`Ejercicio ETL` 3, `assets/etl` 3) | 10 | 144 / 4 |
| **Mutaciones** (`mutar.py`): copias de los ejemplos con **un** error conocido cada una (D1–D11, C15, C24, rutas, contraseña, copias, clave equivocada) | 20 + 2 | — |

Tiempo: las 249 corridas, estático + profundo, en **24 s** (unos 10 s son el arranque de Kettle) [P].
Falsos positivos (FP): se miró **cada** hallazgo de las reglas E/W (son pocos) y una muestra de las I.

### 3.2 Resultados por regla (versión final del prototipo)

| Regla | samples (hallazgos/archivos) | reales | skill | mutaciones | FP y decisión |
|---|---|---|---|---|---|
| `absolute_path` | 1/1 | **10/4** | 0 | 2/1 | 0/11 (sin contar mutaciones). Todos los de los trabajos reales son verdaderos (incluida la copia de `Entrega`). |
| `relative_path` | 0 | 0 | 0 | 2/1 | v1 tuvo 1 FP (directorio de repositorio `/transformations`): corregido. |
| `unsorted_input` | 10/7 estático | 0 | 0 | 5/4 | Estático: 6/10 eran archivos **ya ordenados** (FP). Con profundo: 6 → I (en los 6 se leyó la entrada entera: 6 a 146 filas), 2 W (varias entradas, no determinista), 2 W (desordenado pero mismo resultado). → **W + modo profundo**. |
| `calculator_integer_first` | 0 (16 Calculator) | 0 (2 sin evaluar: MySQL apagado) | 0 | 3/2 | Exacta por construcción [B] para las 4 operaciones; las compuestas (`A*B/100`, `A+B*C`…) no se revisan [NP]. |
| `empty_decimal_symbol` | 9/5 | 4/2 | 0 | 2/2 | En las mutaciones hay `2.50` → E. Reales: 2 valores leídos, enteros (3500) → I. **Samples (8 de 9): el conteo crudo leyó 0 valores** (ancho fijo, que el prototipo no parte) y `final.py` lo tomó como «sin separadores» → I; la única evidencia era el primer valor (14, 26). Corregido: quedan W (`not_evaluated`). 1 W sin probar (BigNumber literal en Generate rows). |
| `text_output_without_fields` | 12/9 (7 W, 5 I) | 0 | 1 I | 2/2 | El ejemplo 06 de la skill cae en I: hoy no rellena. |
| `password_in_file` | 81/12 W | 2/1 W | 0 | 1/1 E | Ciertos todos (hay contraseñas en el archivo); ruidoso (un sample tiene 13 slave servers) → tope por regla. |
| `disabled_hop` / `orphan_step` | 7/5 · 3/3 | 0 | 0 | 1/1 · 1/1 | Ciertos (demos a propósito). `orphan_entry` v1: **21 FP** (entradas ocultas `draw=N` de jobs viejos) → corregido. |
| `distribute_multiple_outputs` | 1/1 → I | 0 | 0 | 1/1 | El sample reparte a dos steps iguales a propósito. |
| `start_not_named_start` | 0 | 0 | 0 | 1/1 | v1: **7/7 FP** («Start» con mayúscula; Kettle no distingue) → corregido con el bytecode. |
| `duplicate_step_name` | 0 | 0 | 0 | 1/1 | v1: 3 FP por recortar espacios → corregido. |
| `variable_in_constant`, `cycle` (2 hallazgos: un step por extremo), `failure_branch_ends_in_success`, `job_variable_in_transformation` | 0 | 0 | 0 | 1 archivo c/u | `failure_branch…`: 0 en samples, pero no se probó con orígenes de condición (ver la regla). |
| `undeclared_variable` | 7/5 | 0 | 0 | — | 3 las define un job padre, 4 esperan `kettle.properties` → I. (El prototipo la emitía como W; la calibración de §4 se rehizo con I.) |
| `parameter_without_default` | 6/3 | 0 | 0 | — | +2 credenciales excluidas. |
| `default_step_name` | **382/165** (39 % de los steps) | 3 | 1 | 4/4 | Ciertos; estilo de demo → I con tope. |
| `no_description` / `select_star` / `default_entry_name` | 104 · 1 · 7 | 7/10 · 0 · 0 | 0 | — | I. |

**Recall en mutaciones: 21/21** (más la variante `m15b`). `checkSteps`: 3/19 (§1).

### 3.3 Modo profundo: lo que agregó

- **Orden (`unsorted_input`)**: preview recortada de la entrada del consumidor (hasta 5000 filas, 30 s),
  comparando claves consecutivas con `ValueMeta.compare` (agrupado para Group by/Unique; ascendente para
  Merge join/Merge rows). Si encuentra desorden, corre la **prueba diferencial**: el consumidor tal cual
  y en una copia con un Sort rows insertado; compara las filas como multiconjunto [P]:
  `m03` Group by **10 filas vs 4** ordenando antes; `m04` Merge join **8 vs 10**; `m17` 10 vs 4 → **E**.
  El sample «XML file with substitutes» tiene las entradas desordenadas pero da **el mismo resultado**
  (las dos vienen en el mismo orden) → W «anda por casualidad». Con varias copias o varias entradas una
  muestra ordenada no prueba nada (orden no determinista) → sigue W.
- **Decimal**: cuenta separadores en la columna cruda; además informa cómo la lee Kettle con el locale de
  Spoon. Con `-Duser.language=es`: `m20` (sin formato) lee **250.0**; `m01` (formato `#.##`) **falla**
  con `couldn't convert String to number`; con `en_US`, 2.5 en ambos [P]. El conteo crudo falló en
  silencio con ancho fijo (0 valores, §2).
- **Bajadas a I y presupuesto**: una muestra ordenada (o sin decimales) baja a I solo si la preview
  **terminó sola**; si cortó por 5000 filas o por tiempo, queda W. Cada sonda 30 s y todo el modo profundo
  **60 s**; lo que no entra va a `not_evaluated` («deep budget») y la llamada MCP no se cuelga.
- **Seguridad**: recorta la copia al step y lo que lo alimenta (`RunMotor.recortar` [S]) y corre solo si
  **todos** los steps que quedan están en una **lista blanca** de solo lectura (entradas de archivo, Table
  input, Data grid, Generate rows; transformación, filtro, orden, joins y búsquedas en memoria). Una lista
  negra no alcanza: `RunMotor.escribe` (lista + categoría *Output*/*Bulk*) deja pasar en 9.4 Call DB
  procedure, Dynamic SQL row, REST/HTTP client, Web services lookup, Trans/Job executor, Mapping, Metadata
  injection, SSH, Zip file, Change file encoding, SFTP put, Set session variables, JavaScript/UDJC,
  productores JMS/Kafka/MQTT, Cassandra output y consumidores de colas [P, registro de 9.4 en la revisión].

### 3.4 Pruebas de que los problemas existen

| Qué | Cómo | Resultado |
|---|---|---|
| D4 y división entera | preview de `m21` | `cantidad(3) × precio(2.5)` = **9.0** (debía 7,5); `1 × 1.8` = 2.0; `101 / 3` → **33.0** [P] |
| D3 | preview de `m20` en es_ES | `2.50` → **250.0** [P] |
| D8 (relleno) | Pan sobre `m15b` (String con longitud 30) y `m15` (longitud −1) | `Café                          ;8;20,0` vs `Café;8;20,0` [P] |
| Número de salida según locale | la misma corrida de Pan (Windows en español) | `20,0` en vez de `20.0` [P] |
| Merge join con entrada en 2 copias | preview de `m16` | Kettle falla: «can't be executed in multiple (2) copies. **Insert a Dummy step**» [P]. Ojo: con el Dummy el error desaparece y el desorden pasa a ser **silencioso** (el linter lo sigue marcando). |
| Atributos para silenciar | `setAttribute` en step y documento → `getXML` → recargar; igual en entrada y job (revisión) | sobreviven el viaje en `.ktr` y `.kjb` [P]; no marcan el documento como cambiado [P] |

### 3.5 Lo que el prototipo enseñó (va al código)

1. `realClone` **comparte** `previousStepCache` con el original y sus hops apuntan a los `StepMeta` del
   original [P]. No es nuevo: `RunMotor.desligar` ya lo resuelve [S]; el linter (y la sonda diferencial)
   usa `ReportTools.clonar`/`desligar`, nunca `realClone` suelto.
2. Ciclo primero: con un ciclo, `checkSteps` y `getPrevStepFields` desbordan la pila [P].
3. `findStep` y `findJobEntry` ignoran mayúsculas [B]; las entradas ocultas (`draw=N`) no son huérfanas (§12).

## 4. Puntaje

Pesos: E = 25, W = 8, I = 2. Por regla: `min(tope, Σ pesos de sus hallazgos)`, con tope = 2 × el peso de su
peor severidad (50 / 16 / 4). Suma de todas las `info` con tope 10 (el estilo solo nunca baja de 90).
`puntaje = max(0, 100 − Σ)`; **si hay algún E, máximo 74**. Bandas: **A** 90-100 · **B** 75-89 · **C** 50-74 · **D** <50.
Las reglas `kettle_check` **no restan**: se listan aparte (dependen del entorno; un E de conexión
bajaría a 74 un archivo correcto). Los hallazgos silenciados o «no evaluados» no restan y se listan.

Calibración sobre el corpus (`final.py`) [P], rehecha en la revisión con `undeclared_variable` como I y
los decimales con 0 valores leídos como W (`linter-review\final_v2.py`; antes: samples 179/28/1/0):

| Corpus | Mediana | A / B / C / D |
|---|---|---|
| samples (208) | 96 | 177 / 30 / 1 / 0 |
| skill (9) | 100 | 9 / 0 / 0 / 0 |
| reales (10) | 89 | 5 / 5 / 0 / 0 — los tres de Big Data 1 dan **80**: `Ejercicio 1` y su copia en `Entrega` por 3 rutas absolutas (16), sin descripción y un nombre por defecto; `Ejercicio 2` por la contraseña `Encrypted` de 2 conexiones (16) |
| mutaciones (22) | 74 | 7 / 1 / 13 / 1 — toda mutación con un E queda ≤ 74; las que solo tienen W o I, 84-98 |

Salida (MCP y sección Calidad): `score`, `grade`, `counts` y `penalties: [{rule, severity, hits, points}]`
ordenadas por puntos, para que se vea **de dónde sale** cada punto que falta.

## 5. Modelo de datos

```java
package io.github.ivi2511.pentahomcp.lint;
public interface Regla {                     // una clase por familia; cada una declara sus ids
  String id(); Sev severidad(); String categoria();   // correctness | portability | security | structure | style
  boolean paraTrans(); boolean paraJobs(); boolean necesitaCampos(); boolean soloArchivo();
  void revisar( Ctx ctx, List<Hallazgo> out ) throws Exception;     // worker, sobre la copia
}
public final class Hallazgo { public String rule, severity, category, key;  // key: ver abajo
  public String step, entry, object, field, attribute, message, howToFix;   // textos ya traducidos
  public boolean env;                                                        // depende de la máquina (§2)
  public Map<String,String> evidence; public Arreglo fix; public String deep; /* resultado de la sonda */ }
public final class Arreglo { public String kind;      // swap_operands | set_output_mode | rename | replace_value
  public boolean auto, confirm; public String preview; //  | set_decimal | declare_output_fields | insert_sort
  public Map<String,String> params; public boolean undoable; //  | rename_entry | retarget_hop
}                                                      //  | set_description | add_parameter (asistidos)
public final class Resultado { public String document, name, kind, rulesVersion; public int score; public String grade;
  public Map<String,Integer> counts; public List<Penalidad> penalties; public List<Hallazgo> findings;
  public List<Hallazgo> suppressed; public List<NoEvaluado> notEvaluated; public Profundo deep; }
public final class Opciones { public Set<String> rules; public String minSeverity = "info"; public boolean deep;
  public Set<String> steps; /* solo hallazgos sobre estos steps (modo clase) */ public boolean kettleChecks;
  public boolean honorSuppressions = true;  /* el corrector pasa false */  public long deepBudgetMs = 60000;
  public String lang = "en"; public Entorno entorno; /* lo que el motor pide al plugin, abajo */ }
public interface Entorno {   // la implementa LintEntorno en el paquete base: lint/ no ve clases package-private
  RowMetaInterface salida( StepMeta s ) throws KettleStepException;   // memo + timeout (Informes: ctx.salida)
  RowMetaInterface entrada( StepMeta s ) throws KettleStepException;  // ídem (ctx.entrada)
  int salidasNormales( TransMeta tm, StepMeta s );                    // TransOps
  boolean soloLectura( StepMeta s );                                  // lista blanca de §3.3
  boolean cancelado(); long msRestantes(); }
```

**`key`** = `<rule>:<ámbito>:<objeto>:<atributo>[#n]` (ámbito `step`, `entry`, `hop`, `connection`, `slave`
o `doc`; `#n` si hay varios en el mismo lugar: cálculos de una Calculator, campos de un CSV, conexiones). Con
`rule:step:atributo` solo, el sample de 13 slave servers daría 13 keys iguales. Se compara por igualdad (un
`:` en un nombre no molesta). `apply_lint_fixes` resuelve todas las keys a objetos antes de tocar nada y
aplica los renombres al final (cambian las keys de ese step).

`Ctx` memoiza el XML parseado de cada step, entradas principales e info, fuentes y campos (por el
`Entorno`: por defecto `getPrevStepFields` con timeout en el pool de `RunMotor`; los Informes pasan
`ctx.salida/entrada`). El timeout no mata el hilo que espera a la BD: el memo evita repetir la espera.
Textos en `lint/textos/lint_{en,es,pt}.properties` (`<id>.msg`, `<id>.fix`, con `{step}`, `{keys}`…); las
propuestas de nombre de §7 también van por idioma.

## 6. Herramientas MCP

**`lint_etl`** — «Checks good practices beyond `validate`: hardcoded paths, passwords in the file, Group
by / Merge join without sorting, Calculator Integer×Number, the empty decimal symbol trap, outputs
without fields, default names… Returns a 0-100 score with where every lost point comes from, findings per
step with how to fix them, and which ones `apply_lint_fixes` can fix. `deep=true` also reads up to 5000
rows of the sources to confirm order and decimals, only when every step it would run is known to be
read-only (otherwise the finding says it was not evaluated); it can take up to `deep_timeout_s`.»

| Argumento | Tipo | Default |
|---|---|---|
| `document` | id/nombre (se resuelve con `Docs.resolverSinActivar`, de Informes WP10: no trae al usuario de vuelta a «Data Integration») | activo |
| `deep` | bool | false |
| `deep_timeout_s` | int (máx. 300) | 60 |
| `rules` | string[] (ids) | todas las habilitadas |
| `min_severity` | `info`/`warning`/`error` | `info` |
| `steps` | string[] | todos |
| `include_kettle_checks` | bool | false (puede conectarse a la BD) |
| `language` | `en`/`es`/`pt` | `en` |

```json
{ "document": "T1", "name": "Ejercicio 1", "kind": "transformation", "rules_version": "0.6.0",
  "score": 80, "grade": "B", "counts": { "error": 0, "warning": 3, "info": 2 },
  "penalties": [ { "rule": "absolute_path", "severity": "warning", "hits": 3, "points": 16 },
                 { "rule": "no_description", "severity": "info", "hits": 1, "points": 2 } ],
  "findings": [ { "key": "absolute_path:step:CSV 1:filename", "rule": "absolute_path", "severity": "warning",
      "category": "portability", "step": "CSV 1", "attribute": "filename", "env": false,
      "message": "Absolute path C:\\…\\fichero1.csv: it only works on this computer.",
      "how_to_fix": "Use ${Internal.Entry.Current.Directory} and a path relative to the .ktr.",
      "fix": { "kind": "replace_value", "auto": true, "confirm": true, "undoable": true,
               "preview": "${Internal.Entry.Current.Directory}/Ficheros ejercicio 1/fichero1.csv" } } ],
  "suppressed": [], "not_evaluated": [ { "rule": "calculator_integer_first", "step": "…", "reason": "fields unknown: connection failed" } ],
  "deep": { "ran": false } }
```

**`apply_lint_fixes`** — `document?`, `keys` (string[] de `key`, o `["all_auto"]` = solo los `auto` sin
`confirm`), `values?` (objeto `key → valor elegido`, p. ej. un nombre mejor o la ruta propuesta),
`dry_run?`. Vuelve a correr las reglas sobre una copia nueva (worker), aplica en el hilo de UI con **un
`UndoGrupo`** y devuelve `{applied:[{key, change}], skipped:[{key, reason}], undo_steps, score_before,
score_after}` (`undo_steps` = 1 en transformaciones; en jobs, una por acción). Sin estado entre llamadas: una
key que ya no aparece va a `skipped` («no longer found»).

**`suppress_lint`** — `document?`, `rule`, `step?` o `entry?`, `reason?`, `remove?`. Escribe
`pentaho-live-lint/ignore` (ids) y `…/reason` en el step, la entrada o el documento, llama `setChanged()` y,
en un step, registra el cambio en `UndoGrupo`. Los hops no tienen atributos: `disabled_hop` se silencia en
el step de origen o en el documento.

**Recurso** `pentaho://lint/rules`: el catálogo (id, severidad, categoría, si está apagada, qué/por qué/arreglo
en 3 idiomas). Herramientas: 54 hoy, 57 con Informes, 67 con modo clase y corrector; el linter suma 3 → **70**.

## 7. Arreglos automáticos

| Arreglo | Reglas | Un Ctrl+Z | Notas |
|---|---|---|---|
| Modo copiar (auto+c) | `distribute_multiple_outputs` | sí (`addUndoChange` del StepMeta) | ya existe en `set_output_mode`. |
| Intercambiar A y B (auto) | `calculator_integer_first` (ADD/MULTIPLY) | sí | cambio de StepMeta; cambia el resultado, pero al valor que la persona escribió. |
| Insertar Sort rows (auto+c) | `unsorted_input` | sí (`nuevo` step + hops + cambio del consumidor) | el prototipo hace esto en la prueba diferencial; repuntar `step1/step2` del Merge join por nombre. Si la fuente es Table input, proponer `ORDER BY` en su lugar. |
| Declarar campos (auto+c) | `text_output_without_fields` | sí | campos de `getPrevStepFields`; Strings con `length -1`. |
| Decimal explícito (auto+c) | `empty_decimal_symbol` | sí | confirmar `.` o `,` (el modo profundo lo sugiere). |
| Reemplazar ruta (auto+c) | `absolute_path`, `relative_path`, `job_variable_in_transformation` | sí | el documento debe tener archivo. Propuestas reales [P]: BD1 `Ejercicio 1.ktr` → `${Internal.Entry.Current.Directory}/Ficheros ejercicio 1/fichero1.csv`; la copia en `Entrega\` → `…/../Ficheros ejercicio 1/…`; `/tmp/json.txt` → parámetro `DIR_DATOS`. |
| Renombrar (auto) | `default_step_name`, `start_not_named_start` | sí (rename de `TransOps` actualiza `send_true_to`, `from`, `step1/2`, error handling) | Propuestas sin IA para el **51 %** de los 386 nombres por defecto del corpus [P] («Sort rows» → «Ordenar por f1», «CSV file input» → «Leer customers-100», «Filter rows» → «Filtrar por id, name»); Dummy, Denormaliser, System info quedan para Claude o la persona. El rename no alcanza referencias desde **otros archivos** (Mapping, Metadata injection): ahí, auto+c. |
| Cambiar destino a Abort | `failure_branch_ends_in_success` | jobs: varios Ctrl+Z (ver `JobSoporte`) | confirmar. |
| Silenciar | `suppress_lint` | step: sí [P: el clon conserva el atributo]; entrada: [NP]; documento: no | `setChanged()` siempre. |

Fuera del undo de Spoon (lo dice `undoable:false`): descripción del documento, parámetros, atributos del
documento [NP: confirmar si `TransAction` cubre el meta]. Nunca se aplican arreglos sin `keys` explícitas.

## 8. Cómo lo ve una persona en Spoon

- **Sección «Calidad» del informe** (perspectiva Reports, id `quality`, marcada por defecto, costo:
  cálculo de campos, así que queda **fuera de la vista previa liviana automática**, como `validation`).
  Bloques del modelo de Informes: **Indicadores** (puntaje y banda, errores, advertencias, sugerencias),
  **Tabla** (Severidad · Regla · Dónde · Qué pasa · Cómo se arregla), un **Aviso** por cada error y
  `datos` = el `Resultado` en JSON. Resumen automático:
  «Puntaje 80/100 (B): 3 advertencias (rutas absolutas en 3 steps) y 2 sugerencias. Sin errores.»
  Respeta `ctx.cancelado()`/`msRestantes()`. Un check «Revisión profunda (lee datos)» en el panel, apagado
  por defecto: necesita un campo nuevo en `Pedido` (contrato de Informes, ver §9).
- **Sin duplicar `validation`**: si las dos secciones van en el mismo informe, la tabla de Calidad omite
  las reglas que también da `validate` (huérfanos, hops deshabilitados, reparto, plugin faltante, hop roto,
  START, inalcanzables) y lo dice al pie; el puntaje sí las cuenta.
- **Clic en un hallazgo** → «Data Integration» con el step seleccionado (lo de `select_steps`). **[NP]**: la
  vista previa de Informes es **HTML estático sin JS (IE11)** cargado con `setUrl`; hace falta un
  `LocationListener.changing` en su `VistaPrevia` (Informes WP1) que atrape `plive://select?doc=…&step=…`.
  «Arreglar»/«Silenciar» por fila esperan a eso; en la v1 los arreglos van por MCP. Los exportados no
  llevan enlaces `plive://`.
- **Menú** «Herramientas → Revisar calidad» **[NP]**: el plugin no tiene XUL e Informes decidió no usarlo;
  haría falta un overlay en `ReportsSpoonPlugin.applyToContainer` (de ellos). Sin XUL: un botón en la barra
  del panel de Informes que arma solo la sección Calidad.

## 9. Con las otras piezas de la 0.6.0

- **Corrector**: `lint:<id>` = 1 si no hay hallazgos ≥ warning de esa regla. Su `sort_before_group_by` es
  `unsorted_input` y su `error_handling`, `db_output_without_error_handling` (apagada, se pide por `rules`);
  ids congelados con `rules_version`. Corre con `honorSuppressions=false` (si no, el alumno se silencia y
  aprueba), `deep=true` y **sin reglas `env`**, para que la nota no dependa de la máquina. Los textos de los
  hallazgos (steps, rutas, SQL) son del alumno: datos, no instrucciones.
- **Modo clase**: `LintMotor.analizar(copia, opciones{steps=[nuevo], minSeverity=warning})` para
  `watch_out:"auto"`; las reglas apagadas (rama de error, salida a BD sin errores) se piden ahí, como
  sugerencias.
- **Informes** (0.5.0, ya publicado): el recolector es nuestro y, por sus reglas, va en el **paquete base**
  como los `Sec*` → `SecCalidad.java`. Lo demás se pide a su coordinador: `SeccionId.QUALITY` y
  `Pedido.calidadProfunda` (contrato WP0), registro y orden en `InformeServicio` (WP2), check y
  `LocationListener` del panel (WP1), y `quality_deep` en `build_report`.

## 10. Riesgos

| Riesgo | Mitigación |
|---|---|
| FP de `unsorted_input` con archivos ya ordenados (6/10 en samples) | W, mensaje «salvo que el archivo ya venga ordenado por {keys}», modo profundo, silenciar. |
| El modo profundo lee datos reales (tiempo, BD, privacidad) y podría tener efectos | 5000 filas, 30 s por sonda y 60 s en total, cancelable; **lista blanca** de steps de solo lectura (§3.3); la evidencia muestra solo las claves de 2 filas, que igual llegan a Claude. |
| `getPrevStepFields` se conecta a la BD | timeout y memo compartidos; la regla queda en `not_evaluated` con el motivo. |
| Nombres por defecto dependen del idioma de Spoon | lista en/es/pt generada al compilar + los nombres del registro en el idioma actual (el registro solo tiene uno). |
| El puntaje se lee como nota | rotularlo «calidad técnica», mostrar la fórmula y las penalidades; la nota es del corrector. |
| Puntaje distinto según la máquina | `kettle_check` no resta; reglas `env` marcadas; el corrector no las usa. |
| `password_in_file` W con `Encrypted` es lo que Spoon guarda por defecto: todo `.ktr` con una conexión con clave pierde 16 | es cierto (ofuscación reversible); el mensaje explica `${VAR}` + `kettle.properties`; se puede silenciar por documento. |
| El código de `lint/` no ve `RunMotor`, `TransOps`, `UndoGrupo`, `Docs` (todas *package-private*) | motor puro en `lint/` + `lint.Entorno`; lo que las usa vive en el paquete base (§11), como hizo Informes. |
| Choque con otros equipos | archivos propios en `lint/` y `Lint*.java`/`SecCalidad.java` en el paquete base; de archivos compartidos solo una línea en `McpLifecycleListener` y en `McpResources`, `RunMotor.chequeosPropios` y un método **nuevo** en `RunMotor` (no se refactoriza `preview`, que usan Informes y modo clase). Nada en `informes/`. |
| Contraseñas: en memoria no se distingue claro de `Encrypted` | en memoria siempre W; E solo en modo archivo. Nunca se muestran valores. |

## 11. Paquetes de trabajo

Regla de paquetes: `…pentahomcp.lint` (y `lint.reglas`) solo usa Kettle y `lint.Entorno`: un subpaquete no ve
las clases *package-private* del paquete base. Lo que toca `RunMotor`, `TransOps`, `UndoGrupo`, `JobSoporte`,
`Docs`, `ReportTools` o `Tools` va en el paquete base con prefijo `Lint` (como los `Sec*` de Informes).

| WP | Qué | Archivos | Depende |
|---|---|---|---|
| WP0 | Contrato y motor: interfaces, `Entorno`, `Ctx` (XML memo, grafo, campos por `Entorno`), orden de reglas (ciclo primero; si hay ciclo no corren campos, `kettle_check` ni sondas), keys, puntaje, textos; implementación del entorno | `lint/{Regla,Hallazgo,Arreglo,Resultado,Opciones,Entorno,Ctx,LintMotor,Puntaje}.java`, `lint/textos/lint_{en,es,pt}.properties`; `LintEntorno.java` (paquete base) | — |
| WP1 | Reglas de correctitud y estructura (incluidas las 4 que hoy da `validate` y las 2 apagadas) | `lint/reglas/{ReglasOrden,ReglasCalculo,ReglasEstructura,ReglasJob}.java` | WP0 |
| WP2 | Portabilidad, seguridad, estilo; nombres por defecto en/es/pt (lista generada + registro); modo archivo (XML crudo, solo sin cambios sin guardar) | `lint/reglas/{ReglasRutas,ReglasSeguridad,ReglasEstilo}.java`, `lint/{NombresPorDefecto,ArchivoCrudo}.java`, `lint/nombres_por_defecto.txt`, `tools/lint-corpus/nombres.ps1` (un arranque por idioma) | WP0 |
| WP3 | Modo profundo: lista blanca, sondas de orden, decimal (columna como String) y diferencial, presupuesto total | `LintSondas.java` (paquete base: `RunMotor.recortar`, `enSegundoPlano`, `ReportTools.clonar`); método nuevo `RunMotor.filas(copia, step, max, ms)` que devuelve `RowMeta` + filas y si terminó solo (aditivo: `preview` no cambia) | WP0, WP1 |
| WP4 | Herramientas y recurso; `validate` usa el motor y deja de marcar entradas ocultas (cambia también la sección `validation` de Informes: avisar) | `LintTools.java` (nuevo), `McpLifecycleListener.java` (1 línea), `McpResources.java` (1 entrada), `RunMotor.chequeosPropios` | WP0-2; `Docs.resolverSinActivar` (Informes WP10) |
| WP5 | Arreglos, silenciar y propuestas (nombres por idioma, rutas) | `LintArreglos.java`, `LintPropuestas.java` (paquete base); reusa `TransOps`, `UndoGrupo`, `JobSoporte` | WP4 |
| WP6 | Sección Calidad | `SecCalidad.java` (paquete base). **Pedidos a Informes** (no se tocan sus archivos): `SeccionId.QUALITY`, `Pedido.calidadProfunda`, registro en `InformeServicio`, check y `LocationListener` en el panel, `quality_deep` en `build_report`, botón o overlay de menú | WP0, Informes 0.5.0 |
| WP7 | Corpus de regresión y documentación | `tools/lint-corpus/{mutar.py,correr.ps1}` (las 22 mutaciones + samples + los 2 jobs de la revisión; falla si cae el recall o cambian los conteos); skill: sección «Calidad» en `SKILL.md` y los ids de regla al lado de D1-D11 en `03-errores-comunes.md`; `CHANGELOG.md` | WP1-5 |

Orden: WP0 → (WP1 ∥ WP2) → WP4 → (WP3 ∥ WP5) → WP6 → WP7. WP1+WP2+WP4 ya dan una 0.6.0 útil sin modo
profundo ni arreglos. Sin los pedidos a Informes, WP6 sale igual (sin check profundo ni clics, sin menú).

## 12. Hallazgos colaterales (para otros dueños)

- `validate` marca como «Has no hops» las entradas ocultas (`draw=N`) de jobs viejos: 21 en los samples.
- El mensaje de Kettle para un Merge join con entrada en varias copias recomienda «Insert a Dummy step»,
  que **silencia** el error y deja el desorden (vale para `03-errores-comunes.md`).
- Un sample oficial («XML file with substitutes») hace Merge join sobre entradas no ordenadas y funciona
  por casualidad.
- `03-errores-comunes.md`: C24 dice «literalmente» `START`, pero `findJobEntry` usa `equalsIgnoreCase` [B]
  («Start» anda en 7 jobs de samples); D10 vale para entradas que **fallan**, no para condiciones, y Write
  to log en la rama roja arrastra el `false` [P, revisión].
- `RunMotor.escribe` (el aviso de `preview_step`) no reconoce los steps con efectos de §3.3.

## 13. Abierto (después de la revisión)

Sin probar (marcados [NP] arriba): orden ascendente y de claves en Merge join, `ORDER BY` con collation;
la lista blanca contra los 216 tipos de step de 9.4; la columna del decimal leída como String (ancho fijo,
comillas, varios archivos) y el caso `1.234,56`; los tags remotos de `absolute_path`; el undo de silenciar
en entradas y documentos; todo lo de Spoon (§8). Por decidir: `suppress_lint` como acción de
`apply_lint_fixes` si 70 herramientas pesan, y un argumento `file` en `lint_etl` para un `.ktr` sin abrir
(el motor lo permite y al corrector le sirve).
