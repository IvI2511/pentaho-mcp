# Robustez de ETL: diseño (Pentaho Live 0.6.0)

Diseño de la **robustez automática**: que una transformación tolere filas sucias sin pararse y sin
perder filas en silencio (cada fila mala va a un archivo de rechazos con su `motivo`), y que eso lo haga
la herramienta o la IA sola, rápido y bien. Cuatro piezas que comparten un mismo análisis del grafo:

1. una **guía** para la IA (`manual/09-robustez.md`) y un **patrón** listo (`patrones/06_carga_tolerante`);
2. una familia de **reglas del linter** (`robustness`) que detecta lo que falta;
3. la herramienta **`harden_etl`** («Robustecer»): plan con diferencias y aplicación en **un Ctrl+Z**;
4. la herramienta **`stress_test`**: prueba con datos sucios en una JVM hija que demuestra que no se
   pierde ninguna fila (leídas = cargadas + rechazadas + descartadas declaradas).

Fecha: 21-oct-2026. Estado: **diseño**; no se tocó código del plugin. Rama `feat/robustez` (desde
`feat/0.6.0` = linter + 0.5.1).

**Estado al 22-oct-2026: implementado** en `feat/robustez` (sin publicar; entra en la 0.6.0). Hecho: la
guía 09 (R01-R14) y el patrón 06; las 17 reglas `robustness` (§3); `harden_etl` con las recetas `auto` y
`confirm` aplicables (R01 también en las entradas de archivo con campos tipados) y las `manual` como
`how_to`; `stress_test` con la JVM hija, el sandbox, el balance y `rerun` sobre SQLite/H2. Suite completa
verde. La prueba de punta a punta sobre copias de dos cargas de clase y del ejemplo Grano de Oro: las
reglas encuentran lo que se arregló a mano, el plan propone lo mismo salvo lo que es `manual`, y
`stress_test` pasa de abortar a no perder filas (lo que queda, en el informe de la prueba). **No hecho**
(para la 0.6.1): el diálogo «Robustecer…» (§7); aplicar las recetas manuales (claves estables,
Insert/Update, agregar al grano, partir sin fallar); deducir los obligatorios siguiendo los renombres de
Select values; R03 con nombres de campo que no son identificadores Java (hoy queda `manual`); el
`encoding` y `dead_end` de §5; la sección de robustez de los Informes; unir el grafo de las tres piezas y
los lanzadores de JVM hija con el corrector.

Marcas: **[P]** probado (en la evidencia, en los patrones o con Pan/Kitchen) · **[B]** leído del bytecode de
PDI 9.4 o del código del plugin · **[S]** ya existe en el plugin · **[NP]** no probado: lo verifica el
paquete de trabajo que lo usa antes de construir encima (§11).

---

## 0. Decisiones en una tabla

| Tema | Decisión |
|---|---|
| Qué es «robusto» | Tres garantías comprobables: **(1)** ninguna fila mala para la carga (la transformación termina con 0 errores); **(2)** ninguna fila se pierde en silencio: cada step que no está **declarado** como descartador entrega tantas filas como recibe (por el flujo normal, por la rama de error o por la rama falsa de un filtro); **(3)** cada fila rechazada llega a un archivo con un `motivo` no vacío. Más dos de diseño: volver a correr no duplica filas ni cambia claves (**idempotencia**), y la entrada es un parámetro (se prueba con un archivo roto). |
| Recetas | 14 recetas (§2), cada una con detección estática sobre `TransMeta` + XML del step (como el linter), lo que agrega y su **nivel**: `auto` (solo cambia el destino de filas que hoy **abortan** la carga; las buenas llegan igual), `confirm` (cambia qué filas llegan o el diseño: la persona dice que sí) o `manual` (la herramienta no lo hace: da el cómo y, si puede, un `build_steps` listo). Claves estables, Insert/Update, agregar al grano del hecho y miembro desconocido **cambian la semántica**: son `confirm` con `semantics_change: true` y **nunca** se aplican sin la key en `confirm`. |
| Reglas | Familia nueva `robustness` (17 reglas, §3) en `lint/reglas/ReglasRobustez`. **Apagadas por defecto** en `lint_etl`: el puntaje general y su calibración (208 samples, §4 del linter) no cambian. Con `robustness: true` corren y dan un **sub-puntaje aparte** (`robustness: {score, grade, penalties}`, mismo cálculo que el general). Cada hallazgo nombra la receta que lo arregla (`evidence.recipe`). |
| `harden_etl` | `dry_run=true` por defecto: devuelve el **plan** (propuestas con key `harden:<receta>:<step>`, nivel, por qué, cambios y un diff de texto). Con `dry_run=false` aplica las `auto` pedidas y las `confirm` cuyas keys vengan en `confirm`, todo con **un `UndoGrupo` = un Ctrl+Z** [S: `UndoGrupo`, `TransLote`, `LintArreglos.Ordenar`]. Analiza en un hilo de trabajo sobre una copia (los campos pueden ir a la BD) y aplica en el hilo de UI resolviendo todo antes de tocar nada (como `apply_lint_fixes`). Solo transformaciones. |
| Rechazos | Una **cola de rechazo** por grupo de filas con el **mismo formato** (Kettle exige el mismo layout en las filas que llegan a un step): `Limpiar motivo` (Replace in string: una línea, sin espacios al borde) → `Escribir rechazos <flujo>` (Text file output, campos declarados, `;`, cabecera, UTF-8, crea la carpeta, **crea el archivo aunque no haya rechazos** [P]). Campos: los de la fila + `motivo` (+ `campos_error` en hops de error). Archivo por defecto `${Internal.Entry.Current.Directory}/rechazos/rechazos_{flow}.csv`. |
| Motivo | En hops de error, la descripción de Kettle (`descriptions_valuename = motivo`, `fields_valuename = campos_error`). En la rama falsa de un filtro, un **User Defined Java Expression** (Janino) que lista lo que falta: sin JavaScript (más rápido, sin Rhino y aceptado por la política del corrector). |
| Declarar descartes | Un step que **a propósito** quita filas (deduplicar, filtro sin rama falsa, agrupar) se declara con atributos de Kettle `pentaho-live-robust/drops` (`duplicates`/`filter`/`aggregate`/`sample`) y `…/reason`: viajan en el `.ktr` [P: mismo mecanismo que `pentaho-live-lint`]. `harden_etl` los escribe en lo que agrega; la persona los pone con `suppress_lint` sobre `silent_filter_drop` (silenciar = declarar). `stress_test` acepta pérdidas solo en steps declarados o agregadores por naturaleza. |
| `stress_test` | Genera variantes rotas de las **entradas de archivo** (campos que faltan, fechas y números malos, duplicados, líneas cortadas, separadores de más, espacios, texto larguísimo, fuera de rango, otra codificación, archivo vacío, solo cabecera) a partir de las primeras `rows` filas reales, corre una **copia** en una **JVM hija** (KETTLE_HOME, `user.home` y `java.io.tmpdir` propios y vacíos, entradas y salidas redirigidas a un sandbox) y comprueba no-aborto, **balance por step**, motivos y, si la BD es un archivo, **idempotencia** (dos corridas = mismas filas y mismas claves). |
| Bases de datos | `stress_test` corre solo si **todas** las conexiones que usa algún step son **SQLite o H2 de archivo**: se copian al sandbox y se reescribe la conexión. Cualquier otra (MySQL, PostgreSQL…) → **no corre**: análisis estático, el plan de variantes y, si se pide `export_variants`, los archivos rotos escritos en una carpeta para que la persona los pruebe a mano (como hizo con su archivo roto). Nunca se conecta a una BD que no sea un archivo del sandbox. |
| Hija | Código propio (`EstresMain`, `EstresProcesos`) siguiendo `CorrectorProcesos` (que está en `feat/corrector`, sin fusionar): mismo classpath y flags, una hija por variante, `-Xmx512m`, timeout duro. **0.6.1: unificar** con el corrector (un solo lanzador). No es un sandbox de seguridad: es el ETL de la propia persona; pasos que salen de la máquina (HTTP, mail, FTP, ejecutar procesos) bloquean la corrida. |
| Skill | `manual/09-robustez.md` (en inglés, como 05 y 06): cuándo y cómo, las recetas a mano, `harden_etl`, `stress_test`, el patrón. Patrón `06_carga_tolerante` (SQLite, Kitchen, dos corridas + archivo roto) verificado con `verificar.ps1` como 01-05. |
| Spoon | Botón e ítem de menú **«Robustecer…»** junto a «Ordenar lienzo» (solo transformaciones): diálogo con el plan (casillas, nivel, diff), «Aplicar» (un Ctrl+Z) y «Probar con datos sucios…». Opcional si falta tiempo. |
| Herramientas | +2 (`harden_etl`, `stress_test`): 61 → **63** en `feat/robustez`. |

## 1. De dónde sale: el ETL de clase robustecido a mano

La evidencia (no se copia al repo: está en la carpeta de la persona) es una transformación de clase que
carga un pequeño modelo en estrella en MySQL: dimensiones de cliente, producto y fecha y un hecho de
ventas, a partir de un archivo de texto y de tres consultas a una BD de origen. Se robusteció a mano con
Claude en una tarde: de **26 steps, 27 hops y 0 manejos de error** a **48 steps, 51 hops y 4 manejos de
error** [P, contado en el XML]. Lo que cambió, y la receta que lo automatiza:

| Cambio a mano | Para qué | Receta |
|---|---|---|
| Filter rows «¿… completo?» (`IS NOT NULL` en 4 campos) justo después del CSV input; rama falsa → JavaScript que arma «Faltan campos obligatorios: A, B» → rechazos | Una línea cortada o un campo vacío no llega a la carga | R03 `required_fields` |
| Select values String → Date/Number con `date_format_lenient=false`, `lenient_string_to_number=false` y **hop de error** con `motivo` → la misma cola de rechazos | `31/12/2021` con máscara `MMMM d, yyyy` o `mil` como número se rechazan con el mensaje de Kettle en vez de abortar o colarse | R04 `strict_conversion` + R01 |
| Field splitter de la dirección → Regex evaluation con grupo de captura y campo resultado (`<campo>_ok`) | Una dirección sin código postal no rompe ni corre columnas | R05 `safe_split` |
| Unique rows (HashSet) por la clave natural antes de cada dimensión | Un cliente repetido en dos fuentes no se carga dos veces ni pisa su clave | R06 `dedupe_before_load` |
| Sequence 1..n → Database lookup de la clave existente + Table input `COALESCE(MAX(pk),0)` + Join rows + Sequence + Calculator (`max + n`, `NVL(existente, nueva)`) | **Claves subrogadas estables**: volver a correr no cambia las claves (antes el contador empezaba en 1 cada vez) | R07 `stable_surrogate_keys` |
| Insert/Update en las 4 cargas (ya estaba) | Volver a correr no duplica filas | R09 `idempotent_load` |
| SQL de ventas con `LEFT JOIN` en vez de `JOIN`; Filter rows «¿<hecho> completo?» (`pk_* IS NOT NULL`) → JavaScript con el motivo («el cliente X no está en la dimensión») → rechazos | Una venta huérfana llega y se rechaza con su motivo en vez de desaparecer en el JOIN | R08 `orphans_to_rejects` + regla `inner_join_in_source` |
| Block until steps finish antes de cargar el hecho (ya estaba) | Las búsquedas ven las dimensiones ya cargadas | R11 `wait_for_loads` |
| Memory group by por las claves del hecho, `SUM` de importe y cantidad | Dos líneas de la misma venta no se pisan en el Insert/Update | R10 `aggregate_to_grain` |
| Hops de error en las 4 cargas (texto demasiado largo, fuera de rango, restricciones) → rechazos | Un error de BD no tumba la carga entera | R01 `rejects_on_error` |
| Replace in string antes de cada archivo de rechazos (`^\s+|\s+$` y saltos de línea → ` | `) | El mensaje de Kettle trae varias líneas: rompería el CSV | cola de R01 |
| Parámetro con el nombre del archivo de entrada + un archivo roto a mano en `pruebas/` | Probar con datos sucios sin tocar el ETL | R12 `input_file_parameter` + `stress_test` |
| Filter rows de fecha válida **sin rama falsa** | Descarte a propósito: las ventas sin fecha se rechazan más adelante con su motivo | declaración (`drops=filter`) |
| Job: File exists → transformación → Write to log (ok / falla) → Abort | Sin archivo no se corre; si falla, Kitchen da 1 | R14 `job_guard` (manual) |
| `CAST(… AS DECIMAL(10,2))` en el SQL de origen | Un importe fuera de rango da error (y va a rechazos) en vez de recortarse | nota de la guía |

El archivo roto que armó (17 líneas) mezcla exactamente los casos que `stress_test` genera solo:
espacios alrededor de los valores, dirección sin código postal, clave vacía, campos obligatorios vacíos,
fecha en otro formato, número en letras, línea cortada (3 de 6 campos), clave repetida con otros datos,
texto más largo que la columna, importe fuera de rango, código desconocido y una línea duplicada exacta.

**Lo que la herramienta hace mejor que la versión a mano** (y la guía lo explica):

- El motivo de un filtro con **Janino** en vez de JavaScript, y `campos_error` en los hops de error (qué
  campo falló).
- Las claves nuevas: la versión a mano numera **todas** las filas y usa `max + n` solo para las nuevas,
  así que deja huecos (las claves son estables y únicas, pero no consecutivas). La receta R07 lo
  documenta y lo mantiene en la v1 (es correcto y más simple); numerar solo las nuevas (filtro antes del
  Sequence) queda como opción `dense_keys` [NP].
- Las colas de rechazo se agrupan **por formato de fila** de forma automática (a mano funcionó porque cada
  hop de error salía justo del step siguiente al filtro).

El segundo ETL de clase (dos CSV unidos → archivo) no tiene rechazos: tolera con **valores por defecto**
(If field value is null → «Desconocido») y recorte de espacios. Eso es R13 `default_optional`.

## 2. Catálogo de recetas

Niveles: **auto** = entra en el conjunto por defecto de `harden_etl` y se aplica sin confirmación porque
solo cambia el destino de filas que hoy abortan la carga (las buenas salen idénticas); **confirm** =
cambia qué filas llegan a las salidas o el diseño: se aplica solo con su key en `confirm`;
`semantics_change: true` además lo marca en rojo en el diálogo y en el plan («cambia el significado de
las claves / de la carga»); **manual** = la herramienta no lo aplica: devuelve `how_to` y, si puede, un
`build_steps` listo para que la IA lo revise y lo mande.

Todas las recetas que agregan steps: nombres en el idioma de `language` (por defecto el de Spoon), únicos
(sufijo ` 2`, ` 3`… como `TransOps`), ubicados junto a su ancla (a la derecha o debajo, sin mover lo
existente; `arrange: true` ordena el lienzo al final) y marcados con el atributo
`pentaho-live-robust/added_by = harden_etl:<receta>` (para que el diálogo y `stress_test` los
reconozcan y para deshacer a mano si hiciera falta).

### 2.0 Resumen

| # | id | Nivel | Qué agrega | Regla que la pide |
|---|---|---|---|---|
| R01 | `rejects_on_error` | auto | Manejo de errores del step → cola de rechazo (`motivo`, `campos_error`) | `no_rejects_on_load`, `no_rejects_on_conversion`, `rejects_dead_end` |
| R02 | `reject_reason` | auto | `motivo`/`campos_error` en un manejo de errores que no los tiene | `rejects_without_reason` |
| R03 | `required_fields` | confirm | Filter rows de obligatorios tras la entrada + Janino con el motivo → rechazos | `no_required_check` |
| R04 | `strict_conversion` | confirm | `lenient=false` en las conversiones + R01 sobre el step que convierte | `lenient_conversion`, `no_rejects_on_conversion` |
| R05 | `safe_split` | manual (v1) | Field splitter → Regex evaluation con grupos y campo resultado | `fragile_split` |
| R06 | `dedupe_before_load` | confirm | Unique rows (HashSet) por la clave natural antes de la carga; duplicados declarados (o a rechazos) | `no_dedupe_before_load` |
| R07 | `stable_surrogate_keys` | confirm, semántica | Lookup de la clave existente + máximo + numeración de las nuevas | `counter_as_surrogate_key` |
| R08 | `orphans_to_rejects` | confirm | Filtro de claves resueltas tras las búsquedas + Janino («X no está en la dimensión») → rechazos; o miembro desconocido | `unchecked_lookup`, `lookup_drops_rows` |
| R09 | `idempotent_load` | confirm, semántica | Table output → Insert/Update por la clave natural (o truncar, si es staging) | `insert_only_load` |
| R10 | `aggregate_to_grain` | confirm, semántica | Memory group by por las claves del hecho, SUM de las medidas, antes de la carga | `fact_not_grouped` |
| R11 | `wait_for_loads` | auto | Block until steps finish (los que escriben la tabla) antes de la búsqueda | `lookup_before_load_finishes` |
| R12 | `input_file_parameter` | confirm | Parámetro con el nombre del archivo de entrada (default = el actual) | `fixed_input_file` |
| R13 | `default_optional` | confirm | If field value is null con un valor por defecto en campos **no** obligatorios | — (solo a pedido) |
| R14 | `job_guard` | manual | Job: File exists → transformación → aviso; ramas rojas → Write to log → Abort | `failure_branch_ends_in_success` [S] |

### 2.1 R01 `rejects_on_error` (auto)

- **Detección.** Step con `supportsErrorHandling()` [B] y sin `StepErrorMeta` habilitado cuyo tipo
  puede fallar **por fila**: cargas (`TableOutput`, `InsertUpdate`, `Update`, `Delete`,
  `SynchronizeAfterMerge`, `DimensionLookup`, `CombinationLookup`), conversiones (`SelectValues` con
  metadatos que cambian el tipo, `CsvInput`/`TextFileInput` con campos no String) y cálculos que pueden
  lanzar (`Calculator`, `Janino`, `ScriptValueMod`, `JsonInput`, `XMLInputStream`, `DBLookup` con
  `fail_on_multiple`). Además, para que no sea ruido, el step tiene que estar en un flujo que nace de una
  entrada (archivo o BD) y no ya en una rama de rechazo. `TableOutput` sobre SQLite **no soporta** manejo
  de errores [P, `05-patrones.md`]: la propuesta pasa a R09 (Insert/Update sí lo soporta [P]).
- **Qué agrega.** `StepErrorMeta` habilitado: destino = la cola de rechazo de su grupo,
  `descriptions_valuename = motivo`, `fields_valuename = campos_error`, sin `max_errors` (los límites
  vacíos). La **cola** (una por grupo de filas con el mismo formato, ver abajo): `Limpiar motivo <flujo>`
  (`ReplaceString`, dos reglas regex sobre `motivo`: `^\s+|\s+$` → vacío y `\s*[\r\n]+\s*` → ` | ` [P]) →
  `Escribir rechazos <flujo>` (`TextFileOutput`: `;`, `"`, cabecera, `UTF-8`, `create_parent_folder=Y`,
  `append=N`, `do_not_open_new_file_init=N` (crea el archivo vacío si no hay rechazos [P]),
  `add_to_result_filenames=Y`, **campos declarados**: los que llegan, Strings con `length -1`, números y
  fechas con su formato, como `DeclararCampos` del linter [S]).
- **Grupos.** Los campos de la fila de error son los de **entrada** del step que falla (Kettle manda la
  fila que recibió) + los campos de error [B: `putError(getInputRowMeta(), …)`]. Se agrupan los
  orígenes cuyo `RowMeta` resultante tiene los mismos nombres y tipos en el mismo orden; cada grupo, una
  cola. Nombre del flujo (`{flow}`): la entrada de la que viene (el primer ancestro sin entradas por la
  rama principal), normalizada a minúsculas sin espacios; si un flujo tiene varios grupos, `_2`, `_3`.
- **Deshacer.** Steps nuevos + hops nuevos + cambio del `StepMeta` del origen (con copia propia del
  `StepErrorMeta`: `StepMeta.clone` lo comparte [S, `set_error_handling`]) en el mismo `UndoGrupo`.
- **Pegas.** `TableOutput` con lotes (`use_batch=Y`) y manejo de errores: comprobar si Kettle desactiva
  los lotes o rechaza el lote entero [NP]. Con `unique_connections=Y` (transformación transaccional) un
  error de BD puede dejar la transacción abortada en algunas BD [NP]: la propuesta lo avisa.

### 2.2 R02 `reject_reason` (auto)

- **Detección.** `StepErrorMeta` habilitado sin `descriptions_valuename`; o rama falsa de un Filter rows
  que termina en una salida de archivo sin ningún campo que parezca un motivo (`motivo`, `reason`,
  `error*`, `causa`) en la fila.
- **Qué agrega.** En el hop de error: `motivo` (y `campos_error` si falta). En la rama falsa: un Janino
  con `motivo` = «No cumple: <condición del filtro en texto>» (la condición sale de `Condition.toString()`
  [NP: formato]). Si el destino es un Text file output **con campos declarados**, se agrega `motivo` a sus
  campos (si no, no se escribiría).
- **Deshacer.** Cambio de `StepMeta` del origen y del escritor; step nuevo en la rama falsa.

### 2.3 R03 `required_fields` (confirm)

- **Detección.** Entrada de archivo (o Table input) cuyos campos llegan como **clave** a algo de abajo
  (claves de Insert/Update, Update, Delete, Database lookup, Stream lookup, Merge join, Group by,
  Unique rows) sin pasar por un Filter rows / Data validator que mire si son nulos. El origen de cada
  campo sale de `ValueMetaInterface.getOrigin()` en `getPrevStepFields` [NP: que los renombres de Select
  values lo conserven]; los renombres se siguen como en `unsorted_input` [S].
- **Qué agrega.** Entre la entrada y su primer step: `¿<flujo> completo?` (Filter rows, `IS NOT NULL` en
  cada obligatorio unidos por AND; `send_true_to` = el step de antes; `send_false_to` = el Janino) →
  `Motivo <flujo> incompleto` (User Defined Java Expression: `motivo` String =
  `"Faltan campos obligatorios: " + (A == null ? "A, " : "") + …`, y `campos_error` igual sin prefijo;
  la cola recorta la coma final con una tercera regla `,\s*$`) → la cola de rechazo del flujo. Si la
  entrada tenía varias salidas, se inserta un Dummy «<flujo> completo» después del filtro que reparte a
  las salidas de antes (en copia). La lista de obligatorios: `required_fields[<step>]` si viene; si no,
  la detectada (y el plan la muestra para que la persona la corrija).
- **Por qué confirm.** Una fila con la clave vacía hoy puede cargarse (Insert/Update inserta una fila con
  clave nula): después se rechaza. Es lo que se quiere casi siempre, pero cambia la salida.
- **Nota.** En Kettle la cadena vacía es nula por defecto (`KETTLE_EMPTY_STRING_DIFFERS_FROM_NULL=N`)
  [B]: con `trim_type=both` en la entrada, `"  "` también cuenta como faltante.

### 2.4 R04 `strict_conversion` (confirm)

- **Detección.** `SelectValues` (pestaña Metadatos) o entrada de archivo con fechas/números cuyo
  `date_format_lenient` o `lenient_string_to_number` es `Y` (o falta: el default es lenient [NP]); y
  conversiones sin manejo de errores (eso es R01).
- **Qué agrega.** `lenient=false` en esas conversiones + R01 sobre el step. Con lenient, `31/02/2021` pasa
  a ser el 3 de marzo y `12abc` puede leerse como 12 [NP]: la fila se carga mal **sin error**. Después se
  rechaza con el mensaje de Kettle.
- **Por qué confirm.** Cambia qué filas llegan (las mal escritas dejan de colarse).

### 2.5 R05 `safe_split` (manual en la v1)

- **Detección.** `FieldSplitter` (o `StringCut`) sobre un campo que viene de una entrada de archivo, sin
  manejo de errores. Qué hace Field splitter con más o menos partes de las declaradas: [NP] (el paquete
  lo prueba primero: si falla, R01 alcanza y es auto; si deja nulos o corre valores, es manual).
- **Cómo (para la IA).** Reemplazar por `RegexEval` con un grupo de captura por campo,
  `allowcapturegroups=Y`, campo resultado (`<campo>_ok`) y Filter rows sobre ese campo → rechazos con
  «formato inesperado de <campo>». El plan trae el `build_steps` y los hops a quitar.

### 2.6 R06 `dedupe_before_load` (confirm)

- **Detección.** Carga con claves (Insert/Update, Table output a una tabla, Dimension lookup/update)
  alimentada por un **Append** de 2+ fuentes o por una entrada de archivo, sin `Unique rows`,
  `UniqueRowsByHashSet`, `Group by` o `Memory group by` aguas arriba que cubra sus claves naturales
  (claves de la carga; en Dimension lookup, `keys`).
- **Qué agrega.** `Quitar <entidad> repetidos` (`UniqueRowsByHashSet` por las claves naturales, gana la
  primera) delante de la carga, declarado (`drops=duplicates`). Con `duplicates: "reject"` en vez de
  `"drop"` (default), `reject_duplicate_row=Y` + `error_description` + hop de error → cola de rechazo
  (como el ejemplo Grano de Oro) [S, `ejemplo/`].
- **Por qué confirm.** Cuál de los repetidos gana cambia lo que se carga (la primera que llega).

### 2.7 R07 `stable_surrogate_keys` (confirm, semántica)

- **Detección.** `Sequence` con contador (`use_counter=Y`, `use_database=N`) cuyo campo llega como
  columna a una carga de dimensión (Insert/Update, Table output) o como su clave: el contador vuelve a 1
  en cada corrida, así que las claves cambian si cambia el orden o aparece una fila nueva, y los hechos
  ya cargados apuntan a otro miembro.
- **Qué agrega** (la versión de la evidencia, probada [P]):
  ```
  … ─► Buscar clave <dim> (Database lookup: tabla dim, clave natural → pk_existente)
           ─► Añadir máximo <dim> (Join rows, sin condición) ◄── Máximo pk <dim> (Table input:
                                                                  SELECT COALESCE(MAX(pk),0) AS max_pk FROM dim)
           ─► Numerar <dim> (Sequence 1..n) ─► Calcular clave <dim> (Calculator: pk_nueva = max_pk + n,
              pk = NVL(pk_existente, pk_nueva); quitar temporales) ─► la carga
  ```
  El Sequence de antes se elimina (el `pk` lo da el Calculator con el mismo nombre de campo). Con
  `dense_keys: true` [NP], un Filter rows numera solo las filas sin clave (sin huecos).
- **Por qué semántica.** Cambia cómo se generan las claves; dos corridas a la vez podrían dar la misma
  clave nueva (no hay bloqueo). La alternativa de Kettle es Dimension lookup/update o Combination
  lookup/update con clave técnica (patrón 04): la guía la nombra; la receta no cambia el esquema.
- **Necesita.** La conexión y la tabla de la carga, la clave natural (`keys[<carga>]` o la clave del
  Insert/Update que no es la `pk`) y la columna `pk`. Si no puede deducirlas: `manual`.

### 2.8 R08 `orphans_to_rejects` (confirm)

- **Detección.** Campos devueltos por `StreamLookup`/`DBLookup` (sin valor por defecto) que llegan a
  la clave o a una columna de una carga sin un Filter rows que mire si son nulos; o `DBLookup` con
  `eat_row_on_failure=Y` (descarta la fila sin rastro); o `MergeJoin` `INNER` antes de una carga.
- **Qué agrega.** Tras la última búsqueda: `¿<hecho> completo?` (Filter rows, `IS NOT NULL` en las claves
  buscadas) → falsa: `Motivo <hecho> incompleto` (Janino: «el <clave natural> X no está en <tabla>»
  por cada búsqueda que falló, `"<campo> vacío"` si la natural ya era nula) → cola de rechazo. Con
  `orphans: "unknown_member"`: en vez del filtro, valor por defecto 0 en las búsquedas (el miembro
  desconocido debe existir: patrón 05) y nada se rechaza. `eat_row_on_failure` pasa a `N`.
- **Origen SQL.** Un `JOIN` interno en el Table input de origen hace desaparecer los huérfanos **antes**:
  la regla `inner_join_in_source` lo avisa; reescribir el SQL a `LEFT JOIN` es de la IA (`manual`).

### 2.9 R09 `idempotent_load` (confirm, semántica)

- **Detección.** `TableOutput` sin `truncate` a una tabla que no parece staging (`stg_`, `tmp_`,
  `staging`): volver a correr duplica filas o choca con la PK.
- **Qué agrega.** Reemplaza el step por un `InsertUpdate` con el mismo nombre, posición, hops y manejo de
  errores: claves = `keys[<carga>]` o la clave natural deducida (campos que vienen de la entrada y no de
  una secuencia o una búsqueda); todos los demás campos se actualizan, la `pk` subrogada no
  (`update=N`, como la evidencia). Alternativa `truncate` si la tabla es de staging.
- **Deshacer.** Borrado del step viejo + alta del nuevo + hops: en un `UndoGrupo` (el par fijo
  DELETE_HOP/DELETE_STEP de Spoon se respeta [S, `UndoGrupo`]).
- **Pega.** Insert/Update hace un SELECT por fila: más lento con millones. La propuesta lo dice.

### 2.10 R10 `aggregate_to_grain` (confirm, semántica)

- **Detección.** `InsertUpdate` con 2+ claves (o `TableOutput` a una tabla de hechos) cuya entrada no
  pasa por un Group by / Memory group by cuyas claves cubran las de la carga: dos filas con la misma
  clave se pisan (gana la última) o chocan con la PK.
- **Qué agrega.** `Agrupar <hecho>` (Memory group by: claves de la carga; `SUM` de los campos numéricos
  que no son clave; los no numéricos que no son clave: `FIRST` [NP: nombre del tipo en MemoryGroupBy])
  delante de la carga, declarado `drops=aggregate`. La persona confirma la agregación (puede querer MAX o
  promedio).

### 2.11 R11 `wait_for_loads` (auto)

- **Detección.** `DBLookup`/`DBJoin`/Table input con `${…}` que lee la tabla `T` de la conexión `C`, y
  otro step de la misma transformación escribe `T` en `C`, sin un `BlockUntilStepsFinish` aguas arriba de
  la búsqueda que nombre a ese escritor. En Kettle todos los steps arrancan a la vez: la búsqueda puede
  correr antes de que la dimensión esté cargada.
- **Qué agrega.** `Esperar <T>` (Block until steps finish con los escritores) delante de la búsqueda.
  No cambia datos, solo el orden: auto. Si la búsqueda está en la rama principal de muchas filas y los
  escritores dependen de esa misma rama, sería un bloqueo mutuo → la propuesta se descarta (`skipped`,
  «would deadlock») [NP: verificar con un caso].
- **Nota.** El escritor confirma al terminar (`commit`); con `unique_connections=Y` todo es una sola
  transacción y la búsqueda ve lo no confirmado de la misma conexión [NP].

### 2.12 R12 `input_file_parameter` (confirm)

- **Detección.** Entrada de archivo cuyo nombre no usa ningún parámetro ni variable (aparte de
  `${Internal.Entry.Current.Directory}`).
- **Qué agrega.** Parámetro `ARCHIVO_<FLUJO>` con el nombre actual como default, y la ruta pasa a
  `…/${ARCHIVO_<FLUJO>}`. **Fuera del Ctrl+Z**: los parámetros no entran en el undo de Spoon
  (`undoable: false`, igual que `add_parameter` del linter [S]); el plan lo dice. Si la ruta es absoluta,
  primero `absolute_path` del linter.

### 2.13 R13 `default_optional` (confirm, solo a pedido)

- **Qué agrega.** `If field value is null` con un valor por defecto («Desconocido», 0) en los campos que
  la persona nombra en `defaults` (nunca claves). Tolerar con un valor es la alternativa a rechazar
  cuando el campo no es obligatorio. Sin detección: no hay forma estática de saber qué es opcional.

### 2.14 R14 `job_guard` (manual)

Guía y patrón, no herramienta (en jobs no hay un solo Ctrl+Z [S, `JobSoporte`]): START → File exists
(la entrada) → transformación → Write to log «revisa rechazos/…» → Success; las ramas rojas → Write to
log con el motivo → Abort (Kitchen da 1). Lo que ya cubre el linter: `failure_branch_ends_in_success`.

## 3. Reglas del linter (familia `robustness`)

Categoría nueva `robustness` (`Regla.ROBUSTNESS`). Todas `apagadaPorDefecto()`: no corren en `lint_etl`
sin más y nunca tocan el puntaje general. Corren con `robustness: true` (o nombradas en `rules`) y
entonces dan el **sub-puntaje de robustez** (mismo `Puntaje`: pesos 25/8/2, tope por regla, compuerta
74 si hay un E, bandas A-D) en un bloque aparte del `Resultado`. Ninguna es E: robustez es una
propiedad de diseño, no un error del resultado. Ninguna depende de la máquina salvo donde se dice.

| id | Sev | Qué detecta | Cómo (estático) | Receta |
|---|---|---|---|---|
| `no_rejects_on_load` | W | Carga a BD sin manejo de errores: un texto largo o un valor fuera de rango tumba la carga entera | Tipos de carga de R01, `supportsErrorHandling()`, `StepErrorMeta` nulo o deshabilitado. Si es `TableOutput` sobre SQLite: mensaje propio («SQLite: usa Insert/Update») | R01 (R09 en SQLite) |
| `no_rejects_on_conversion` | W | Conversión de tipos (Select values Metadatos, entrada con campos tipados) sin manejo de errores | XML de `fields/meta` con `type` ≠ String desde String, o `<field><type>` ≠ String en la entrada | R01 + R04 |
| `rejects_without_reason` | W | Rechazos sin motivo: hop de error sin `descriptions_valuename`, o rama falsa a un archivo sin campo de motivo | `StepErrorMeta`; nombres de campo (`getPrevStepFields`, `conCampos()`) | R02 |
| `rejects_dead_end` | W | Filas rechazadas que no van a ningún archivo: el hop de error o la rama falsa termina en un Dummy o en un step sin salida que no escribe | Recorrido desde el destino del hop de error / `send_false_to` hasta los terminales; terminal que no es salida | R01 |
| `silent_filter_drop` | I | Filter rows (o Switch/case sin default) **sin rama falsa** en un flujo que termina en una carga o salida: descarta sin rastro | `send_false_to` vacío; recorrido aguas abajo. **Silenciarla = declararla** (`drops=filter`) | declarar o R02 |
| `lookup_drops_rows` | W | Database lookup con `eat_row_on_failure=Y`, Merge join `INNER` delante de una carga: los huérfanos desaparecen | XML | R08 |
| `unchecked_lookup` | W | Clave devuelta por una búsqueda, sin default, que llega a una carga sin un filtro de nulos | campos `value/rename` del lookup; recorrido hasta la carga buscando Filter rows con `IS NOT NULL` sobre ese campo | R08 |
| `inner_join_in_source` | I | Table input con `JOIN` interno (`JOIN`/`INNER JOIN` sin `LEFT`/`RIGHT`/`FULL`) que alimenta una carga: los huérfanos se pierden en el origen | Regex sobre `<sql>` sin comentarios; `env: false` | manual |
| `no_required_check` | I | Claves que vienen de una entrada y llegan a una carga o búsqueda sin ningún control de nulos | §2.3 (`conCampos()`) | R03 |
| `lenient_conversion` [NP] | W | Fecha o número convertido con lenient: valores mal escritos se cuelan sin error | `date_format_lenient`/`lenient_string_to_number` = Y o ausente | R04 |
| `fragile_split` [NP] | I | Field splitter sobre datos de archivo sin manejo de errores | XML + recorrido | R05 |
| `no_dedupe_before_load` | I | Carga por clave alimentada por un Append o un archivo, sin deduplicar por esas claves | §2.6 | R06 |
| `counter_as_surrogate_key` | W | Sequence con contador como clave de una dimensión: cambia en cada corrida | §2.7 | R07 |
| `insert_only_load` | I | Table output sin truncar a una tabla que no es staging: volver a correr duplica | §2.9 | R09 |
| `fact_not_grouped` | I | Carga con 2+ claves sin agrupar antes por ellas: filas que se pisan | §2.10 | R10 |
| `lookup_before_load_finishes` | W | Búsqueda en una tabla que la misma transformación escribe, sin Block until steps finish | §2.11; conexión por nombre y tabla sin distinguir mayúsculas | R11 |
| `fixed_input_file` | I | Entrada de archivo sin parámetro en el nombre: no se puede probar con otro archivo | §2.12 | R12 |

- **Selección.** `Opciones.robustez` (nuevo). `Catalogo.seleccionar` agrega las de la familia si está
  activo. `LintTools`: argumento `robustness` (bool, default false), descripción en inglés.
- **Resultado.** `Resultado.robustez` = `{score, grade, counts, penalties}` sobre los hallazgos de la
  familia; los hallazgos van en `findings` como siempre (con `category: "robustness"`). JSON:
  `"robustness": { "score": 62, "grade": "C", "counts": {…}, "penalties": [ … ] }`.
- **Hallazgo → receta.** `evidence.recipe = "<id de receta>"` y `evidence.anchor = "<step>"`; `fix` nulo
  (no van por `apply_lint_fixes`: el `how_to_fix` dice «harden_etl recipes=[…] steps=[…]»). Así
  `LintArreglos` no se toca.
- **Declaraciones.** Una regla no marca un step con `pentaho-live-robust/drops` (o `added_by`) en lo que
  ese atributo cubre. `suppress_lint` de `silent_filter_drop` escribe además `drops=filter` (lo hace la
  regla al leer la supresión: no hace falta tocar `suppress_lint`).
- **Calibración** (la hace el paquete A sobre el corpus del linter, `scratchpad\v06\linter\`): la versión
  «antes» de la evidencia debe dar ≤ 74 con ≥ 6 reglas distintas; la «después», ≥ 90; el patrón 06,
  100; los patrones 01-05 y los ejemplos de la skill, sin `W` falsos (los `I` se revisan a mano).

## 4. `harden_etl`

**Descripción (inglés, para el esquema MCP):** «Makes a transformation fault-tolerant: every bad row
goes to a rejects file with a 'motivo' column instead of stopping the load or vanishing. Returns a PLAN
first (dry_run=true, the default): one proposal per recipe and step, with its level and a text diff.
level=auto proposals only change what happens to rows that would abort the run today; level=confirm
proposals change which rows reach the outputs or the design (semantics_change=true: surrogate keys,
Insert/Update, aggregation): show them to the person and pass the accepted keys in 'confirm'. Applying
(dry_run=false) is ONE Ctrl+Z in Spoon. Then run stress_test to prove that no row is lost.»

| Argumento | Tipo | Default |
|---|---|---|
| `document` | id/nombre (`Docs.resolverSinActivar`) | activo |
| `recipes` | string[] de ids, o `["all"]` | las `auto` que aplican; `all` = todas en el plan (las `confirm` solo se aplican con su key en `confirm`) |
| `steps` | string[] | todos: limita a propuestas cuyo ancla es uno de estos steps |
| `rejects_file` | string (plantilla con `{flow}`) | `${Internal.Entry.Current.Directory}/rechazos/rechazos_{flow}.csv` |
| `required_fields` | `{ "<entrada>": ["campo", …] }` | detectados (R03) |
| `keys` | `{ "<carga>": ["clave natural", …] }` | detectadas (R06, R07, R09) |
| `duplicates` | `drop` / `reject` | `drop` |
| `orphans` | `reject` / `unknown_member` | `reject` |
| `defaults` | `{ "<campo>": "valor" }` | — (solo R13) |
| `confirm` | string[] de keys del plan, o `["all"]` | — |
| `dry_run` | bool | **true** |
| `arrange` | bool | false (ubica lo nuevo junto a su ancla; true = `arrange_canvas` al final, en el mismo Ctrl+Z) |
| `language` | `en`/`es`/`pt` | el de Spoon |

```json
{ "document": "T1", "dry_run": true,
  "robustness_before": { "score": 41, "grade": "D" },
  "proposals": [
    { "key": "harden:rejects_on_error:Load fact_sales", "recipe": "rejects_on_error", "level": "auto",
      "semantics_change": false, "step": "Load fact_sales",
      "why": "A database error (text too long, value out of range) stops the whole load.",
      "changes": [
        { "op": "set_error_handling", "step": "Load fact_sales", "target": "Clean reason sales",
          "description_field": "motivo", "fields_field": "campos_error" },
        { "op": "add_step", "name": "Clean reason sales", "type": "ReplaceString", "near": "Load fact_sales" },
        { "op": "add_step", "name": "Write rejects sales", "type": "TextFileOutput",
          "file": "${Internal.Entry.Current.Directory}/rechazos/rechazos_sales.csv" },
        { "op": "add_hop", "from": "Clean reason sales", "to": "Write rejects sales" } ],
      "diff": "~ Load fact_sales: error handling -> Clean reason sales (motivo, campos_error)\n+ Clean reason sales (Replace in string)\n+ Write rejects sales (Text file output) rechazos/rechazos_sales.csv\n+ hop Clean reason sales -> Write rejects sales" },
    { "key": "harden:stable_surrogate_keys:Customer key", "recipe": "stable_surrogate_keys",
      "level": "confirm", "semantics_change": true, "step": "Customer key", "needs": [], "…": "…" } ],
  "skipped": [ { "recipe": "wait_for_loads", "step": "Look up customer_sk", "reason": "would deadlock: …" } ],
  "next": "Show the confirm proposals to the person; then call again with dry_run=false and confirm=[keys]." }
```

Con `dry_run=false` se agregan `applied: [keys]`, `skipped` (con el motivo de cada una que no se pudo:
«no longer applies», «needs keys[Load dim_customer]»…), `undo_steps: 1`, `robustness_after` y
`next: "Run stress_test to check that no row is lost."`.

**Cómo corre** (igual que `apply_lint_fixes` [S]):

1. **Analizar** (hilo de trabajo, sobre `ReportTools.clonar`): `Flujos.de(tm)` (§10, compartido), las
   reglas de la familia (para el `robustness_before` y para saber qué recetas aplican), y cada
   `Receta.proponer(...)` → `Propuesta` con `Cambio`s **por nombre** (nada de objetos del clon). Los
   campos (`getPrevStepFields`) se calculan aquí, con el timeout del linter.
2. **Elegir**: `recipes`, `steps`, `confirm`; las `confirm` sin key van al plan pero no se aplican.
3. **Aplicar** (hilo de UI, documento vivo, `Docs.ui`): resolver todos los nombres a objetos **antes de
   tocar nada** (si algo ya no está → esa propuesta a `skipped`); aplicar en orden fijo: (a) cambios de
   steps existentes (`cambio` con copia propia de `StepErrorMeta`), (b) borrados de hops (`borrado`),
   (c) steps nuevos (`nuevo`, como `TransLote.agregar`), (d) hops nuevos en una acción, (e) borrado y
   alta de los steps reemplazados (R09), (f) atributos `pentaho-live-robust/*`, (g) `arrange` si se
   pidió. Todo en **un `UndoGrupo`**; los parámetros (R12) fuera, avisado. `Docs.touched`.
4. **Volver a medir**: reglas de la familia sobre el resultado → `robustness_after`.

Varias recetas sobre el mismo flujo se componen: la cola de rechazo de un grupo se crea una vez y la
reutilizan R01, R02, R03, R04 y R08; el orden de inserción en un mismo hop es fijo (R03 pegado a la
entrada, luego conversiones, luego dedupe, búsquedas, R08, R10, R11, carga).

## 5. `stress_test`

**Descripción (inglés):** «Dirty-data test: generates broken variants of the transformation's file
inputs (missing fields, bad dates and numbers, duplicates, truncated lines, extra separators, wrong
encoding, empty file…) from their first rows, runs a COPY of the transformation on each one in a
separate sandboxed JVM (its own KETTLE_HOME; inputs and outputs redirected to a temporary folder) and
checks that it does not abort, that no row is lost (rows read = rows loaded + rejected + dropped by steps
declared as droppers) and that every rejected row has a reason. It only runs when every database the
transformation uses is a SQLite/H2 file (copied to the sandbox); otherwise it returns the static checks
and can export the broken files with export_variants so the person can try them by hand. It never
connects to any other database. Takes up to timeout_s per variant.»

| Argumento | Tipo | Default |
|---|---|---|
| `document` | id/nombre | activo |
| `variants` | string[] | `["combined","empty_file","header_only","rerun"]`; `["all"]` = cada tipo por separado + esos |
| `rows` | int | 50 (máx. 5000): filas reales que se toman de cada entrada como base |
| `inputs` | string[] | todas las entradas de archivo |
| `required_fields` | igual que `harden_etl` | los de R03 |
| `timeout_s` | int | 60 por variante (máx. 600) |
| `export_variants` | carpeta | — (escribe ahí los archivos rotos, sin pisar nada que exista) |
| `keep_sandbox` | bool | false |
| `language` | `en`/`es`/`pt` | el de Spoon |

### 5.1 Variantes

Se parte de las primeras `rows` líneas de datos reales de cada entrada (con el parser del propio step:
separador, encierro, cabecera, encoding [como el modo profundo del linter, S]). Cada variante es una copia
con filas rotas **agregadas** (no reemplazadas: así la base sigue sirviendo de control), anotadas en un
manifiesto (línea → tipo):

| id | Qué inyecta | Debe |
|---|---|---|
| `missing_fields` | cada obligatorio vacío una vez; una fila con todo vacío | rechazar |
| `bad_dates` | `31/02/2021`, `2021-13-45`, `no es fecha`, el formato de otra máscara | rechazar (o convertir bien si es otra máscara válida: se informa) |
| `bad_numbers` | `mil`, `12abc`, `1.234,56` donde se espera `.`, `NaN` | rechazar |
| `duplicates` | 3 filas idénticas a otras + 2 con la misma clave y otros datos | no duplicar: se descartan (declarado) o se rechazan |
| `truncated_lines` | línea cortada tras el 2º separador | rechazar |
| `extra_separators` | un separador y un valor de más al final y en medio (sin encierro) | rechazar o informar (qué hace CSV input con columnas de más: [NP]) |
| `whitespace` | espacios y tabuladores alrededor de los valores | cargar igual (con `trim`) o informar |
| `too_long_text` | 5000 caracteres en cada campo de texto | rechazar en la carga (BD) o informar |
| `out_of_range` | `1e15`, `-1`, `0` en numéricos | rechazar en la carga o informar |
| `wrong_encoding` | el archivo en la otra codificación (UTF-8 ↔ ISO-8859-1) con tildes y eñes | no abortar; aviso si aparece `U+FFFD` o mojibake en las salidas |
| `empty_file` | 0 bytes | no abortar, 0 filas cargadas |
| `header_only` | solo la cabecera | no abortar, 0 filas cargadas |
| `combined` | todo lo anterior salvo encoding/vacío en un solo archivo (como el archivo roto de la evidencia) | todo lo anterior |
| `rerun` | la base limpia dos veces sobre la misma BD del sandbox | 2ª corrida: mismas filas por tabla y mismas claves |

Entradas de ancho fijo, Excel y JSON: `not_evaluated` en la v1 (solo CSV input y Text file input con
separador). Si el archivo real no existe: `not_evaluated` con el motivo (la v1 no inventa datos).

### 5.2 Sandbox y política

`<java.io.tmpdir>/pentaho-live-estres/<id>/`: `entrada/` (variantes), `salida/<variante>/`, `db/`,
`kettle_home/`, `home/`, `tmp/`, `logs/`, `resultado/`. Se borra al terminar salvo `keep_sandbox` (en
Windows, cuando la hija ya salió [P, corrector P12]).

- **Copia del `.ktr`** (XML con DOCTYPE prohibido, como el corrector): rutas de las entradas → la
  variante (si la ruta usa un parámetro, se pasa el parámetro; si no, se reescribe la hoja); salidas de
  archivo (Text file output, Excel output, JSON output, XML output…) → `salida/<variante>/<step>.<ext>`
  (si el nombre sale de un campo: bloquea); conexiones SQLite/H2 de archivo → copia del archivo en `db/`
  (vacío si no existe; entonces las tablas no están y la corrida lo dirá) y se reescribe
  `<database>`; **cualquier otra conexión usada por algún step → no corre** (`not_run_reason` con el
  nombre y el tipo). Las rutas se resuelven con las variables del documento y
  `Internal.Entry.Current.Directory` = la carpeta del `.ktr` original.
- **Política de steps**: bloquean la corrida los que salen de la máquina o ejecutan cosas: HTTP/REST/
  Web services, Mail, FTP/SFTP, Execute a process, SSH, Shell (si existe como step), Metadata injection,
  Transformation/Job executor y Mapping (la v1 no sigue subtransformaciones), Execute SQL script/row
  (salvo sobre una conexión del sandbox), y cualquier step cuyo plugin falta. JavaScript, Janino y UDJC
  corren (es el código de la persona) con una nota: «no es un sandbox de seguridad».
  *Implementado (tras la revisión)*: lista de bloqueo con los ids verificados en `kettle-steps.xml` de 9.4
  (`HTTP`, no `Http`; `SFTPPut`, `SyslogMessage`, `MailInput`, `WebServiceAvailable`, `TextFileOutputLegacy`…)
  y los plugins conocidos (LDAP, Elasticsearch, Kafka, JMS, MQTT, Mongo, Cassandra, HBase, Avro, Parquet,
  S3…), más una red de seguridad: un id con «output», «producer», «consumer», «bulk», «loader» o «executor»
  que el sandbox no sabe redirigir bloquea. Una lista blanca de todos los steps queda para 0.6.1.
- **Registro en BD**: la copia vacía la conexión y la tabla de las tablas de log de `<info><log>` (transformación,
  steps, rendimiento, canales, métricas) y la conexión de `<maxdate>`: la hija nunca escribe en la BD de logs
  de verdad.
- **Memoria**: antes de lanzar, si la memoria física libre es < 1 GB, espera hasta 2 min y si no hay,
  `not_run` («not enough free memory»). Una hija a la vez.

### 5.3 La hija y lo que mide

`EstresProcesos` lanza `java` (javaw en Windows) con el classpath y los flags que usa `CorrectorProcesos`
(jar del plugin y su `lib/`, `<PDI>/classes`, `<PDI>/lib/*`, `<PDI>/libswt/<os>/*`,
`-DKETTLE_PLUGIN_BASE_FOLDERS=<PDI>/plugins`, los `--add-opens` del padre), `KETTLE_HOME`, `user.home` y
`java.io.tmpdir` del sandbox (también como variables de entorno, quitando `KETTLE_*`, `JAVA_TOOL_OPTIONS`
y `_JAVA_OPTIONS`), `-Xmx512m`, directorio de trabajo = el sandbox, salida a `logs/<variante>.log`.
`EstresMain <trabajo.json>` corre **una variante**: `KettleEnvironment.init()`, `new TransMeta(copia)`,
parámetros, un `RowListener` por step que cuenta `rowWrittenEvent` y `errorRowWrittenEvent`, ejecuta con
nivel `BASIC`, espera, y escribe `resultado/<variante>.json`:

```json
{ "variant": "combined", "finished": true, "errors": 0, "timed_out": false, "ms": 2140,
  "error_message": null, "log_tail": "…",
  "steps": [ { "name": "Read sales", "type": "CsvInput", "copy": 0, "read": 0, "input": 31, "written": 31,
               "output": 0, "updated": 0, "rejected": 0, "errors": 0, "put": 31, "put_error": 0 } ],
  "tables": [ { "connection": "dwh", "table": "dim_customer", "rows": 12, "keys_hash": "…", "rows_hash": "…" } ] }
```

Las tablas (`rerun`) las cuenta la hija sobre la copia de la BD con `Database` de Kettle: filas, y un hash
de las claves (claves de Insert/Update o columnas `pk*`/`sk*`/`*_id`) y de todas las filas, ordenadas.

### 5.4 Comprobaciones (en el padre, `Balance`, sin Kettle: se prueba con JSON fijos)

| id | Sev | Qué |
|---|---|---|
| `no_abort` | E | Terminó, `errors = 0`, sin timeout |
| `input_complete` | W | Filas que la entrada sacó (`input`/`put`) = líneas de datos de la variante (sin cabecera ni líneas vacías). Menos = la entrada se comió filas (encierro abierto, línea cortada pegada a la siguiente) |
| `no_silent_loss` | E | Para cada step **no declarado** y que no agrega por naturaleza: `entra = sale`, con `entra = read − filas de sus flujos info` y `sale = put + put_error`. Las filas info = `put` del step info si tiene una sola salida o copia; con reparto o varias → `not_evaluated` en ese step. Lo que falta, con el nombre del step y cuántas |
| `dead_end` | W | Filas que llegan a un terminal que no escribe nada (Dummy, filtro sin destino) sin estar declarado |
| `rejects_have_reason` | W | Cada archivo de rechazo (escritores en ramas de rechazo según `Flujos`) se lee con **su** separador y encoding: toda fila tiene un campo de motivo no vacío |
| `bad_rows_rejected` | W | Filas que debían rechazarse según el manifiesto ≤ rechazadas + descartadas declaradas; si no, «N filas rotas llegaron a la salida» (con los valores marcadores que se encuentren en las salidas de archivo) |
| `idempotent` | E / W | `rerun`: E si alguna tabla tiene más filas en la 2ª corrida; W si cambia el hash de claves con las mismas filas (claves inestables) |
| `encoding` | W | `wrong_encoding`: `U+FFFD` o secuencias típicas de mojibake (`Ã©`, `Ã±`) en las salidas |

Agregadores por naturaleza (cambian la cantidad y no es pérdida): Group by, Memory group by, Unique rows,
Unique rows (HashSet), Row denormaliser, Row normaliser (expande), Split field to rows, Join rows, Merge
join, Sorted merge, Append/Merge (suman), Analytic query, Sample rows; se informan como «agrupa 31 → 12».
Declarados (`drops`): se informan como «descarta 3 (declarado: duplicates)».

**Resumen** por variante: «31 leídas = 17 cargadas + 12 rechazadas + 2 descartadas (declarado) + **0
perdidas**». `verdict`: `robust` (ninguna E ni W), `fragile` (alguna), `not_run`.

```json
{ "document": "T1", "ran": true, "verdict": "fragile",
  "static": { "robustness": { "score": 62, "grade": "C" }, "inputs": [ "Read sales" ] },
  "variants": [ { "id": "combined",
      "inputs": { "Read sales": { "lines": 31, "injected": { "missing_fields": 5, "bad_dates": 4, "…": 0 } } },
      "run": { "finished": true, "aborted": false, "ms": 2140 },
      "balance": { "read": 31, "loaded": 17, "rejected": 12, "declared_drops": [ { "step": "Remove duplicate customers", "rows": 2, "declaration": "duplicates" } ],
                   "lost": [], "dead_ends": [] },
      "rejects": [ { "step": "Write rejects sales", "rows": 12, "without_reason": 0, "sample_reasons": [ "Faltan campos obligatorios: Fecha" ] } ],
      "checks": [ { "id": "no_abort", "status": "pass" }, { "id": "bad_rows_rejected", "status": "warn", "message": "2 broken rows reached the outputs" } ] } ],
  "summary": { "passed": 3, "warned": 1, "failed": 0 }, "next": "harden_etl recipes=[strict_conversion] …" }
```

### 5.5 Informes

Sección `robustness` (después de `quality`; id nuevo en `SeccionId`, títulos en `secciones_*`): bloque
**Indicadores** (sub-puntaje de robustez, veredicto de la última prueba, filas perdidas, rechazos sin
motivo), **Tabla** de comprobaciones por variante y **Tabla** de recetas sugeridas. Las reglas corren
siempre (estáticas y baratas); la prueba con datos solo si `build_report` trae `stress_test: true`
(`Pedido.robustezEstres`, nuevo) o si hay una prueba **en memoria** del mismo documento con el mismo hash
del XML (la última de `stress_test`). Fuera de la vista previa automática (como `quality`). Opcional:
último del paquete C.

## 6. Skill

### 6.1 `manual/09-robustez.md` (en inglés)

1. **What "robust" means here** — the three guarantees + idempotence; the one-line balance.
2. **When** — before handing in or scheduling a load; after `lint_etl` gives a low robustness score; when
   the person says "que no se caiga", "tolerar errores", "datos sucios", "rechazos".
3. **The fast path (live, with the MCP)** — `lint_etl robustness=true` → `harden_etl` (dry run) → show
   the `confirm` proposals → `harden_etl dry_run=false confirm=[…]` → `stress_test` → fix what it reports
   → `save`. With a non-file database: `stress_test export_variants=…` and run it by hand with the
   parameter.
4. **The recipes by hand** (no MCP, writing XML): one subsection per recipe with the exact XML (error
   handling block `<step_error_handling>`, Filter rows with `send_false_to`, Janino expression, the
   rejects tail, Unique rows HashSet, the stable-key chain, Insert/Update, Memory group by, Block until
   steps finish, the parameter) — taken from pattern 06, which is tested.
5. **Rejects files** — one per row layout; `motivo` + `campos_error`; always created (empty = no
   rejects); cleaning the multi-line Kettle message; never mix layouts in one step.
6. **Declared drops** — filters without false branch, dedupe, aggregation; `suppress_lint` on
   `silent_filter_drop`; what `stress_test` accepts.
7. **Pitfalls** — SQLite: Table output has no error handling (use Insert/Update) and does not enforce
   VARCHAR length (use `CHECK` constraints to test "too long"); empty string = null; lenient
   conversions; INNER JOIN in the source SQL; `CAST(… AS DECIMAL(p,s))` so out-of-range values error out
   instead of being cut; Block until steps finish vs `unique_connections`; stable keys and concurrent runs.
8. **The job around it** (R14) — File exists, warnings, Abort; checking that the rejects file is empty.
9. **Reading `stress_test`** — every check, what to do for each failure (→ which recipe).

Más: `SKILL.md` (una sección corta «Robustness» + fila en la tabla de guías), `05-patrones.md` (fila 06),
`03-errores-comunes.md` (una línea que apunta a 09), `build.ps1` y `tools/ci/compilar-plugin.sh` (copiar
`manual/09-robustez.md`), `McpResources` (recurso `pentaho://guide/robustness`), `PruebaLint` o la prueba
de recursos (que exista).

### 6.2 Patrón `patrones/06_carga_tolerante/`

Inglés en nombres de steps (convención de los patrones), archivos en castellano. SQLite
`salida/06_dwh.sqlite`, conexión `dwh`.

| Archivo | Qué es |
|---|---|
| `06_carga_tolerante.kjb` | START → crear `salida/` → SQL `CREATE TABLE IF NOT EXISTS dim_customer (customer_sk INTEGER PRIMARY KEY, customer_id VARCHAR(10) NOT NULL UNIQUE, name VARCHAR(40) CHECK(length(name) <= 40), city VARCHAR(30))`, `fact_sales (customer_sk INTEGER NOT NULL, sale_date DATE NOT NULL, amount NUMERIC(10,2) CHECK(amount BETWEEN 0 AND 99999), quantity INTEGER, PRIMARY KEY (customer_sk, sale_date))` → File exists `${ARCHIVO_VENTAS}` → ktr → Write to log; ramas rojas → Abort. Parámetro `ARCHIVO_VENTAS` |
| `06_carga_tolerante.ktr` | CSV input (todo String, `trim`) → `Required fields?` → `Convert types` (Select values, lenient N, hop de error) → `Remove duplicate customers` (HashSet, declarado) → `Look up existing key` + `Max key` + `Join max` + `Number new` + `Compute key` → `Load dim_customer` (Insert/Update, hop de error) ; ventas: → `Look up customer_sk` (Database lookup tras `Wait for dim_customer`) → `Customer found?` → `Aggregate to grain` (Memory group by) → `Load fact_sales` (Insert/Update, hop de error); cada rama de rechazo → `Clean reason` → `Write rejects <flow>` |
| `datos/ventas.csv` | 12 filas limpias (clientes y ventas en un archivo plano, como la evidencia) |
| `datos/ventas_rotas.csv` | las 12 + 10 rotas a mano: campo vacío, fecha mala, número en letras, línea cortada, cliente repetido con otros datos, nombre de 60 caracteres (CHECK), importe fuera de rango (CHECK), línea duplicada exacta, venta de un cliente sin datos |
| `README.md` | como los demás: qué resuelve, cuándo, cómo funciona (diagrama ASCII), parámetros, adaptarlo, pegas, cómo verificarlo |

**Verificación** (`verificar.py 06-1/06-2/06-3`, `verificar.ps1`/`.sh` con tres corridas): (1) archivo
limpio: 0 rechazos, N clientes y M ventas; (2) **otra vez** el limpio: mismas filas y **mismas
`customer_sk`** (idempotente y claves estables); (3) el roto: Kitchen exit 0, rechazos con motivo por cada
fila rota (cuenta exacta), las buenas cargadas, ninguna clave existente cambia, y la suma
`leídas = cargadas + rechazadas + descartadas` desde las líneas del log (`I=, O=, R=, W=, U=, E=`).
Además `stress_test` sobre el ktr del patrón debe dar `robust` (prueba del paquete C, cuando esté).

## 7. Spoon: «Robustecer…»

Ítem junto a «Ordenar lienzo» en la barra del lienzo de **transformaciones** y en el menú contextual del
fondo (mismo mecanismo de `OrdenarLienzo.instalarDesde`: decorar los `TransGraph` ya creados [S]). Abre un
diálogo SWT (prueba fuera de pantalla con `Control.print`, como `PruebaPanelSwt` [S]):

- arriba, el sub-puntaje de robustez y el veredicto de la última prueba (si hay);
- una lista con casilla por propuesta (las `auto` marcadas, las `confirm` sin marcar; las de
  `semantics_change` con un aviso), nivel, step y por qué; abajo, el diff de la seleccionada;
- botones **Aplicar** (llama a la misma `RobustecerAplicar` que la herramienta: un Ctrl+Z),
  **Probar con datos sucios…** (corre `stress_test` en segundo plano con progreso y muestra el resumen)
  y **Cerrar**. Textos en/es/pt en `informes/textos/robustez_*.properties` (área `robustez`).

Si no da el tiempo, queda fuera de la 0.6.0: todo se puede hacer por MCP.

## 8. Con las otras piezas de la 0.6.0

- **Linter**: la familia es parte del catálogo (`pentaho://lint/rules` la lista con `off_by_default`).
  No cambia el puntaje general ni `all_auto`. `db_output_without_error_handling` (podada, I) queda como
  está; con `robustness: true` y pedida a la vez, las dos pueden marcar el mismo step (aceptado).
- **Corrector**: un pack puede puntuar robustez con `lint:<id>` de esta familia (son estáticas,
  `env: false`); un criterio `stress` (correr `stress_test` sobre la entrega) queda para la 0.6.1, cuando
  las dos hijas se unifiquen.
- **Modo clase**: una lección «Robustecer» puede mostrar el plan de `harden_etl` como notas y aplicar
  receta por receta (`steps`); no hay contrato nuevo.
- **Informes**: §5.5. `quality` no muestra la familia (la muestra `robustness`).

## 9. Riesgos y preguntas abiertas

| Riesgo | Mitigación |
|---|---|
| Rechazar «sin mirar» es peor que fallar (lo dijo el diseño del linter al podar `db_output_without_error_handling`) | Rechazos siempre a un archivo con motivo, creado aunque esté vacío; la guía y el patrón comprueban en el job que esté vacío o lo avisan; `stress_test` exige motivo |
| La receta de claves estables con dos corridas a la vez | `semantics_change`, aviso en el plan, alternativa Dimension lookup/update en la guía |
| Layouts distintos en una misma cola | Grupos por `RowMeta` idéntico; si no se pueden calcular los campos (BD caída, timeout), la propuesta va a `skipped` («fields unknown») |
| Bloqueo mutuo con Block until steps finish | Detección de ciclo en el grafo «espera a» + flujo; `skipped` |
| Balance con flujos info y reparto | `not_evaluated` por step, nunca un falso «perdidas» |
| La hija y la memoria (15 GB, varias JVM esta noche) | Una hija a la vez, `-Xmx512m`, espera de memoria libre |
| Duplicar el lanzador del corrector | Copia mínima marcada `// 0.6.1: unificar con CorrectorProcesos` |
| `stress_test` sobre BD no-archivo no corre (el caso real de la evidencia es MySQL) | `export_variants` + la guía: copia con SQLite (patrón 06) o prueba a mano con el parámetro |

Abiertas: ¿`harden_etl` también para jobs (R14) en la 0.6.1, con varios Ctrl+Z? ¿Un modo «una cola por
transformación» (layout uniforme `paso;motivo;datos`) para quien prefiera un solo archivo?

## 10. Paquetes de trabajo (3 agentes en paralelo en la misma worktree)

Convenciones: Java 8, identificadores en castellano, formato Pentaho, `Prueba*.java`, textos en/es/pt,
nada escribe en `data-integration\` ni en `~\.kettle`. Lógica pura en el subpaquete
`io.github.ivi2511.pentahomcp.robustez` (sin Spoon: se prueba sin GUI); lo que necesita clases
*package-private* (`TransOps`, `UndoGrupo`, `Docs`, `RunMotor`, `LintEntorno`) en el paquete base.

**Reglas de convivencia en la misma worktree** (`…\Pentaho MCP - robustez`):

- **Un solo dueño por archivo** (tablas de abajo). Si necesitas un cambio en un archivo ajeno, déjalo en
  tu informe; no lo edites.
- **Compilar**: nunca dos `build.ps1` a la vez. Cerrojo: crear `spoon-plugin\build\.cerrojo-robustez`
  en exclusiva antes de `build.ps1`/`test.ps1` sin `-SinCompilar` (PowerShell:
  `New-Item -ItemType File` falla si existe → esperar 30 s y reintentar) y borrarlo al terminar. Pruebas
  solo con `-Solo <tus clases>`.
- **Git**: `git add <tus archivos>` (nunca `-A` ni `.`), commit con el trailer; si `index.lock` existe,
  esperar y reintentar. Nada de push, rebase ni merge.
- **Primeros 30 minutos**: cada agente commitea los **esqueletos** que los otros usan (abajo, «contrato
  temprano») para que todo compile desde el principio.

### Contrato temprano (lo primero que commitea cada uno)

- **A** — `robustez/Flujos.java` (API completa, implementación puede ir mejorando):
  ```java
  public final class Flujos {
    public static Flujos de( TransMeta tm );
    public List<StepMeta> entradasArchivo();     // CsvInput, TextFileInput, ExcelInput, JsonInput…
    public List<StepMeta> entradasBd();          // TableInput…
    public List<StepMeta> cargas();              // TableOutput, InsertUpdate, Update, Delete, DimensionLookup…
    public List<StepMeta> salidasArchivo();      // TextFileOutput, ExcelOutput, TypeExitExcelWriterStep…
    public boolean enRamaDeRechazo( StepMeta s );   // aguas abajo de un hop de error o de send_false_to
    public List<StepMeta> escritoresDeRechazo();
    public StepMeta entradaDe( StepMeta s );     // la entrada (sin entradas) de su flujo principal, o null
    public String nombreFlujo( StepMeta s );     // normalizado: minúsculas, sin espacios ni tildes
    public Set<StepMeta> arriba( StepMeta s, boolean conInfo );
    public List<StepMeta> infos( StepMeta s );   // los que le mandan filas por un flujo info
    public static String declaracion( StepMeta s );   // pentaho-live-robust/drops o null
    public static boolean agregaPorNaturaleza( String stepId );
  }
  public final class Declaraciones {            // grupo de atributos y claves
    public static final String GRUPO = "pentaho-live-robust", DROPS = "drops", REASON = "reason",
      ADDED_BY = "added_by";
  }
  ```
  y `lint/Opciones.robustez` + `Regla.ROBUSTNESS`.
- **B** — `robustez/Receta.java`, `robustez/Propuesta.java`, `robustez/Cambio.java` (modelo del plan,
  JSON con Jackson), `RobustezTools.java` con `register(McpHttpServer)` y el esquema de `harden_etl`
  (cuerpo «not implemented yet» al principio), y el registro en `McpLifecycleListener` **de las dos
  herramientas** (llama a `EstresTools.register`).
- **C** — `EstresTools.java` con `register(McpHttpServer)` y el esquema de `stress_test` (cuerpo «not
  implemented yet»), `robustez/EstresResultado.java` (modelo §5.3-§5.4).

### Paquetes

| Agente | Qué | Archivos (nuevos salvo *mod*) | Listo cuando |
|---|---|---|---|
| **A · Reglas, guía y patrón** | Flujos y declaraciones; las 17 reglas (prioridad: `no_rejects_on_load`, `no_rejects_on_conversion`, `rejects_without_reason`, `rejects_dead_end`, `silent_filter_drop`, `unchecked_lookup`, `counter_as_surrogate_key`, `lookup_before_load_finishes`, `no_required_check`, `fixed_input_file`; luego el resto); sub-puntaje; argumento `robustness`; textos; patrón 06; manual 09; docs | `robustez/{Flujos,Declaraciones}.java`, `lint/reglas/ReglasRobustez.java`, *mod* `lint/{Catalogo,Opciones,Regla,Resultado,Puntaje,LintJson}.java`, *mod* `LintTools.java`, *mod* `lint/textos/lint_{en,es,pt}.properties`, `PruebaLintRobustez.java`, `src/test/resources/robustez/*.ktr` (sintéticos: «antes» y «después» con nombres genéricos, **nunca** copias de la evidencia), `claude/skills/pentaho-etl/patrones/06_carga_tolerante/**`, *mod* `patrones/verificar.{py,ps1,sh}`, `manual/09-robustez.md`, *mod* `manual/05-patrones.md`, `manual/03-errores-comunes.md`, `SKILL.md`, *mod* `spoon-plugin/build.ps1`, `tools/ci/compilar-plugin.sh`, `McpResources.java`, `CHANGELOG.md` ([Unreleased]), `README{,.es,.pt}.md` (filas de las 2 herramientas) | Calibración de §3 cumplida; `PruebaLintRobustez` verde; el patrón 06 da OK en `verificar.ps1 -Solo 06` con PDI A (3 corridas); el resto de `PruebaLint` sigue verde (el puntaje general no cambia) |
| **B · `harden_etl`** | Recetas P1: R01, R02, R11 (auto), R03, R04, R06, R12 (confirm); P2: R08, R07, R09, R10 (confirm con apply), R05/R13 manual; plan con diff; aplicación con un `UndoGrupo`; atributos; `robustness_before/after` (usa las reglas de A cuando estén; si no, `null`) ; diálogo «Robustecer…» (opcional, al final) | `robustez/{Receta,Propuesta,Cambio,Recetas}.java`, `robustez/recetas/*.java`, `RobustecerAnalisis.java` (hilo de trabajo), `RobustecerAplicar.java` (UI), `RobustezTools.java`, *mod* `McpLifecycleListener.java` (registro de las 2 + 2 líneas de instrucciones), *mod* `PruebaMcpInformes.java` (`TOOLS = 63`, esquemas de las 2), `PruebaRobustecer.java` (sin GUI: `UndoGrupo.sinSpoon`, sobre los `.ktr` sintéticos de A: plan, aplicar, **deshacer** y comparar el XML con el original), opcional `RobustecerDialogo.java` + *mod* `OrdenarLienzo.java` + `informes/textos/robustez_{en,es,pt}.properties` | El «antes» sintético robustecido con `recipes=all, confirm=all` pasa a ≥ 90 de robustez y corre con Pan (copia SQLite) sin abortar; deshacer devuelve el XML original (salvo parámetros); `PruebaMcpInformes` verde |
| **C · `stress_test`** | Variantes; sandbox y política; hija; balance y comprobaciones; resultado JSON; `export_variants`; sección `robustness` de Informes (opcional, al final) | `robustez/{Variantes,Balance,EstresResultado,Manifiesto}.java`, `EstresSandbox.java`, `EstresProcesos.java`, `EstresMain.java`, `EstresTools.java`, `PruebaEstresVariantes.java` y `PruebaEstresBalance.java` (sin Kettle), `PruebaEstres.java` (de punta a punta con una hija sobre el patrón 06 o un ktr sintético con SQLite: `robust`; sobre el «antes» sintético: `fragile` con las pérdidas nombradas), opcional `SecRobustez.java` + *mod* `informes/SeccionId.java`, `InformeServicio.java`, `informes/ui/GrupoSecciones.java`, `informes/Pedido.java`, `ReportTools.java` (`stress_test` en `build_report`), `informes/textos/secciones_*.properties`, `PruebaModelo.java` | Las comprobaciones de §5.4 con JSON fijos; una corrida real en la hija con `combined`, `empty_file`, `header_only`, `rerun`; rechaza una transformación con MySQL sin conectarse (`not_run`) y `export_variants` escribe los archivos |

**Dependencias:**

```
A: Flujos + Declaraciones + Opciones.robustez (30 min) ──► B (recetas usan Flujos) y C (balance y rechazos usan Flujos)
A: ReglasRobustez ──► B (robustness_before/after; hasta entonces null)
A: .ktr sintéticos (1 h) ──► B y C (pruebas)
A: patrón 06 ──► C (prueba de punta a punta «robust»)
B y C no dependen entre sí (salvo el registro en McpLifecycleListener, que hace B con el esqueleto de C)
```

**Prioridades si falta tiempo** (a la hora límite, commitear lo que funciona detrás de defaults seguros):
A: reglas P1 + sub-puntaje + manual 09 + patrón 06 (aunque sin todas las reglas). B: R01/R02/R11/R03 con
apply y deshacer; el resto como `manual` con `build_steps` en el plan. C: variantes + hija + `no_abort`
+ `no_silent_loss` + `rejects_have_reason`; `rerun`, `encoding` e Informes después. El diálogo de Spoon y
la sección de Informes son lo primero que se cae.

## 11. Verificar primero ([NP] que sostienen el diseño)

Cada uno, con un `.ktr` mínimo y Pan de PDI A (KETTLE_HOME propio), antes de construir encima:

1. Error rows de Select values / Insert/Update llevan la fila **de entrada** del step + los campos de
   error en ese orden (B, para los grupos de colas).
2. `getRowFrom` (flujos info de Stream lookup) incrementa `linesRead` y llama a `rowReadEvent` (C, balance).
3. `putRow` con varias salidas en copia cuenta **una** vez en `linesWritten` y en `rowWrittenEvent` (C).
4. `date_format_lenient` / `lenient_string_to_number`: default y efecto con `31/02/2021` y `12abc` (A, B).
5. Field splitter con menos y con más partes de las declaradas: ¿falla, deja nulos o corre valores? (A, B).
6. CSV input con columnas de más y con una línea cortada; archivo de 0 bytes con `header=Y` (C).
7. Insert/Update sobre SQLite con `CHECK` violado → fila al hop de error con el mensaje (A, patrón 06).
8. `TableOutput` con `use_batch=Y` + manejo de errores en una BD que lo soporta (B, nota de R01).
9. Block until steps finish + Database lookup de la tabla que escribe un Insert/Update de la misma
   transformación: ¿ve las filas? con y sin `unique_connections` (B, R11).
10. `Condition.toString()` legible para el motivo de R02 (B).
11. Janino (`Janino`, «User Defined Java Expression») con `?:` y concatenación de nulos en 9.4 (A, B).
