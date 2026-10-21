# Linter de ETL: integración (Pentaho Live 0.6.0)

Qué falta para que el motor del linter (rama `feat/linter`) llegue a Spoon, con los cambios exactos en los
archivos compartidos. El diseño es `docs/linter-diseno.md`; este documento no lo repite.
Fecha: 20-oct-2026. Estado: **motor estático hecho y probado sin Spoon**. **WP3 a WP7 integrados** en
`feat/calidad` (sobre `dev` 0.5.0, que ya trae Informes): lo que cambió respecto de este plan está en §2.5, §2.6,
§2.7, §2.8 y §2.9.

## 1. Qué hay en la rama

Solo archivos nuevos: no se tocó ningún archivo que cambien la 0.4.2 (`dev`) o Informes (`feat/informes`).

| Archivo | Qué es |
|---|---|
| `spoon-plugin/src/main/java/io/github/ivi2511/pentahomcp/lint/` | El motor (WP0): `Regla`, `ReglaBase`, `Sev`, `Hallazgo`, `Arreglo`, `Resultado`, `Penalidad`, `NoEvaluado`, `Profundo`, `Opciones`, `Entorno`, `TiempoAgotado`, `Ctx`, `LintMotor`, `Puntaje`, `Catalogo`, `Textos`, `LintJson`, `Supresiones`, `NombresPorDefecto`, `ArchivoCrudo`, `Rutas`, `Nodos`. Solo usa la API de Kettle (y Jackson, que viene con PDI). |
| `…/lint/reglas/` | Las reglas (WP1 y WP2), una clase por familia: `ReglasEstructura`, `ReglasJob`, `ReglasOrden`, `ReglasCalculo`, `ReglasRutas`, `ReglasSeguridad`, `ReglasEstilo`. 31 ids (los 30 del §2 más `kettle_check`). |
| `spoon-plugin/src/main/resources/io/github/ivi2511/pentahomcp/lint/textos/lint_{en,es,pt}.properties` | Título, por qué, arreglo y mensajes de cada regla (UTF-8). El arnés controla que los tres tengan las mismas claves. |
| `…/resources/…/lint/nombres_por_defecto.txt` | Nombres por defecto de steps y entradas en en/es/pt. Lo genera `tools/lint-corpus/nombres.ps1`. |
| `spoon-plugin/src/main/java/io/github/ivi2511/pentahomcp/LintEntorno.java` | El `Entorno` del plugin (paquete base): campos con timeout en el pool de `RunMotor`, `TransOps.salidasNormales`, lista blanca de solo lectura (§3.3). |
| `…/pentahomcp/LintTools.java` | `lint_etl` (estático) y el texto del recurso `pentaho://lint/rules`. **Sin registrar.** |
| `tools/lint-corpus/` | Corpus de regresión (WP7): `mutar.py` (22 mutaciones + 4 jobs), `esperado.tsv`, `ArnesLint.java`, `correr.ps1`, `linea-base.tsv`, `nombres.ps1` + `VolcarNombres.java`, `jdk.ps1`. |

`build.ps1` no necesita cambios: compila todo `src/main/java` (subpaquetes incluidos) y copia `src/main/resources`
al jar, así que los textos y la lista de nombres viajan solos. `nombres.ps1` se corre a mano al cambiar de
versión de PDI (no en cada build: arranca Kettle tres veces) y su salida se versiona.

## 2. Cambios en archivos compartidos (WP4)

### 2.1 `McpLifecycleListener.java`: registrar las herramientas (1 línea)

En `onStart`, después de `ReportTools.register( server );`:

```java
      LintTools.register( server );
```

Suma 1 herramienta (`lint_etl`); con WP5 serán 3 (`apply_lint_fixes`, `suppress_lint`).

### 2.2 `McpResources.java`: el recurso `pentaho://lint/rules` (1 entrada)

`McpResources` sirve archivos del classpath (`Doc`); el catálogo del linter es JSON armado en el momento. Dos
cambios chicos:

1. En la lista de recursos (`register`, donde arma cada `Doc` con `uri`, `name`, `title`, `description`,
   `mimeType`), agregar uno más:
   ```java
   // uri pentaho://lint/rules · name lint-rules · mimeType application/json
   // title "Lint rules (catalog)"
   // description "The rules of lint_etl: id, severity, category, whether it is off by default or
   //   depends on the computer, and what/why/how to fix in en, es and pt; plus the scoring formula."
   ```
2. En `leer( String uri )`, antes de buscar en `DOCS`:
   ```java
   if ( "pentaho://lint/rules".equals( uri ) ) {
     ObjectNode res = Tools.obj();
     res.putArray( "contents" ).addObject().put( "uri", uri ).put( "mimeType", "application/json" )
       .put( "text", LintTools.catalogo() );
     return res;
   }
   ```
   (`leer` no declara excepciones: envolver `LintTools.catalogo()` en un try que devuelva el error como texto.)

### 2.3 `RunMotor.chequeosPropios`: `validate` usa el motor (§1, WP4)

Hoy `validate` suma a `checkSteps` sus propios chequeos. Pasan a ser reglas del motor con el mismo criterio:

| `chequeosPropios` hoy | Regla |
|---|---|
| plugin que falta (step y entrada) | `missing_plugin` |
| step sin hops | `orphan_step` |
| 2+ salidas en modo repartir | `distribute_multiple_outputs` |
| hop con un extremo que no existe | `broken_hop` |
| hop deshabilitado (trans y job) | `disabled_hop` |
| job sin START | `job_without_start` |
| entrada sin hops | `orphan_entry` (**cambia**: ya no marca las entradas ocultas `draw=N` de jobs viejos, 21 FP en los samples) |
| entrada inalcanzable desde START | `unreachable_entry` |

Cambio propuesto (los dos `chequeosPropios` quedan en uno; `anotar` y `tieneAdvertencia` siguen igual):

```java
  private static final Set<String> REGLAS_DE_VALIDATE = new LinkedHashSet<String>( Arrays.asList(
    "missing_plugin", "orphan_step", "distribute_multiple_outputs", "broken_hop", "disabled_hop",
    "job_without_start", "orphan_entry", "unreachable_entry" ) );

  private static void chequeosPropios( AbstractMeta copia, Map<String, ArrayNode> porStep, int[] cuenta, boolean detalle ) {
    Opciones op = new Opciones();
    op.rules = REGLAS_DE_VALIDATE;
    op.honorSuppressions = false; // validate no se silencia
    op.entorno = new LintEntorno( copia );
    Resultado r = LintMotor.analizar( copia, op );
    for ( Hallazgo h : r.findings ) {
      String fuente = h.step != null ? h.step : h.entry != null ? h.entry : "(general)";
      if ( h.rule.equals( "orphan_step" ) && tieneAdvertencia( porStep, fuente ) ) {
        continue; // Kettle ya avisa «no se utiliza este paso»
      }
      int tipo = h.severity == Sev.ERROR ? CheckResultInterface.TYPE_RESULT_ERROR : CheckResultInterface.TYPE_RESULT_WARNING;
      anotar( porStep, cuenta, fuente, tipo, h.message, detalle );
    }
  }
```

Diferencias visibles para quien usa `validate`: los textos salen de `lint_en.properties` (dicen lo mismo con
otras palabras), `distribute_multiple_outputs` a steps del mismo tipo baja a info (no se anota) y las entradas
ocultas dejan de aparecer. **Avisar a Informes**: su sección `validation` muestra lo de `validate`.

### 2.4 `Docs.resolverSinActivar` (Informes WP10)

`LintTools.copia` usa `Docs.resolver`, que activa la pestaña. Cuando entre `Docs.resolverSinActivar`, cambiar
esa línea (§6 del diseño: el lint no trae al usuario de vuelta a «Data Integration»).

### 2.5 Cómo quedó en `feat/calidad` (21-oct-2026)

- **Herramientas**: `LintTools.register` después de `ReportTools.register`: 59 tools (`PruebaMcpInformes.TOOLS`).
  Las instrucciones del servidor nombran `lint_etl` en el paso (5). `LintTools.copia` ya usa
  `Docs.resolverSinActivar` (`dev` lo trae).
- **Recurso**: `McpResources.LINT_REGLAS`. Sale en `resources/list` (`application/json`, con `size`) y en
  `resources/read`; el JSON se arma una vez y queda en la caché de la instancia. Si no se pudiera armar, `read`
  devuelve el error como `text/plain` y `list` no lo muestra.
- **`validate`**: un solo `chequeosPropios( AbstractMeta … )` con `RunMotor.REGLAS_DE_VALIDATE`, sin
  supresiones y con `minSeverity = WARNING`. El código de §2.3 anotaba los `info` como advertencia, así que el
  reparto entre steps del mismo tipo se habría seguido anotando: con el filtro deja de salir, como dice el texto.
  `dev` ya tenía `TextosChequeo` (Informes traduce los chequeos propios): se quedó, con dos métodos `default`.
  `idioma()` elige el idioma de `lint_*.properties` (Informes pasa el del informe) y `hallazgo(h)` arma el
  renglón. `TEXTOS_MCP` le agrega «use set_output_mode with 'copy'» al reparto, como antes, y `texto()` solo da
  `kettle_fallo`. Se borraron las claves `val.propio.*` de `estructura_*.properties`, salvo `kettle_fallo`.
- **Pruebas**: `PruebaLint` (nueva): el recurso, `validate` en jobs (la entrada oculta no sale, la dibujada sí,
  hop deshabilitado e inalcanzables, job sin START) y en transformaciones (hop roto, step suelto) y `lint_etl`
  sin Spoon sobre el ejemplo 03. `PruebaEstructura.pruebaValidacion`: el texto esperado en castellano es el del
  linter, y se suman pt y el texto de la tool. `PruebaMcpInformes`: 59 tools, argumentos de `lint_etl` y sus
  errores sin Spoon.
- **Texto nuevo** `disabled_hop.msg.job` (en/es/pt): en un job no pasan filas, así que el hop deshabilitado de
  un job dice que el job nunca llega a la entrada por ese camino (lo muestra `validate`).
- **Arnés**: `correr.ps1` compila también con POI 4.1.1 (desde la 0.5.0 el plugin lo importa para el PowerPoint).
  Sobre `feat/calidad`: recall 27/27, 164 pruebas, 0 fallas, conteos iguales a `linea-base.tsv`.

### 2.6 WP5 en `feat/calidad` (21-oct-2026): arreglos, silenciar y propuestas

Archivos nuevos en el paquete base: `LintArreglos.java` (la lógica, sin Spoon), `LintPropuestas.java` (nombres sin
IA). `LintTools` registra `apply_lint_fixes` y `suppress_lint` (61 tools) y `lint_etl` ya devuelve las propuestas
en `fix.preview`. Cambios chicos en archivos compartidos: `Rutas.Ruta.elemento` (la hoja del XML, para cambiarle el
texto), las claves `name.*` en `lint_{en,es,pt}.properties` y una cláusula en las instrucciones del servidor.

- **Flujo de `apply_lint_fixes`**: copia en la UI (con la huella = `getXML().hashCode()` del documento vivo) →
  hilo de trabajo: las reglas, las propuestas y la elección (y los campos de `declare_output_fields`, que pueden ir
  a la BD) → UI: si la huella cambió, error («the document changed while it was being analyzed»); si no, se
  resuelven **todas** las keys a objetos (steps, entradas, hops, conexiones) con sus precondiciones y recién
  después se aplica, con los renombres al final, todo en un `UndoGrupo` → otra pasada de las reglas para
  `score_after`. `dry_run` hace lo mismo sobre la copia. Una key que no sale va a `skipped` («no longer found»).
- **Qué se aplica**: `all_auto` = los `auto` sin `confirm` ni `needs_value` de los hallazgos visibles (nunca los
  silenciados). Una key explícita aplica cualquier arreglo (la descripción le pide al cliente que confirme los
  `confirm`); `needs_value` sin `values[key]` va a `skipped` con la propuesta si la hay. Una key de `values` cuenta
  como pedida. Las keys nunca se parten por comas (los nombres de step pueden tenerlas).
- **Cada arreglo** (todos registran su cambio en el grupo; los steps se releen desde su XML cambiado, como
  `TransOps.renombrar`):
  - `swap_operands`, `set_output_mode`, `set_decimal` (solo `.` o `,`; si el agrupador era el mismo símbolo, lo
    vacía), `declare_output_fields` (Strings con largo −1, el resto como llega; se saltea si Kettle no calcula
    ningún campo, p. ej. detrás de un Transformation executor).
  - `rename` (`TransOps.renombrar`: targets de Filter rows, `<from>` de Stream lookup, `step1/step2`, manejo de
    errores) y `rename_entry`; rechaza un nombre que ya existe o que también es un nombre por defecto.
  - `replace_value` en rutas: precondición = el valor que vio la regla. La propuesta con `DIR_DATOS` declara el
    parámetro (fuera del undo, lo dice `notes`); si `DIR_DATOS` ya es otra carpeta (en el documento o en otro
    arreglo de la misma llamada) se saltea: en `m09` la entrada y la salida están en carpetas distintas.
  - `replace_value` en contraseñas: solo acepta una variable (`${X}` o `%%X%%`), nunca una clave. Conexión: undo
    en transformaciones; en jobs no (`SpoonJobDelegate.undoJobAction` no tiene `CHANGE_CONNECTION` [B]); slave
    server: nunca (tampoco `SpoonTransformationDelegate` deshace `CHANGE_SLAVE` [B]). `undoable` lo dice por key.
  - `insert_sort`: Sort rows (ascendente y sensible a mayúsculas como `ValueMeta.compare`; Sorted merge conserva
    sus sentidos) entre la fuente y el consumidor, y `step1/step2/reference/compare` repuntados; con varias
    entradas, uno solo que las junta. Se saltea si el hop es de error o si la fuente manda a destinos concretos
    (Filter rows, Switch/case). **ORDER BY** en lugar del Sort rows solo si el Table input es la entrada directa,
    el consumidor agrupa (no Merge join/rows ni Sorted merge: la collation de la BD puede no ser la de Kettle), las
    claves son identificadores y la consulta es un SELECT sin ORDER BY/LIMIT/UNION/variables.
  - `retarget_hop`: el hop rojo va al Abort que ya haya (si no hay ya un hop a él) o a uno nuevo debajo del
    Success (`name.abort`); si el Success queda sin hops, lo dice.
  - `set_description` y `add_parameter`: fuera del undo de Spoon (`undoable:false` y una nota).
- **`undo_steps`**: 1 en transformaciones si hubo algún cambio deshacible; en jobs, una por acción
  (`UndoGrupo.acciones()`; Spoon no encadena en jobs). `score_after` se calcula como lo vería `lint_etl` después:
  con el documento sin guardar (las reglas de modo archivo se juzgan en memoria; lo dice `notes`).
- **`suppress_lint`** (`rule` o `*`; `kettle_check` no): step → `UndoGrupo` (`StepMeta.replaceMeta` copia los
  atributos con `copyStringMap` [B]; la prueba confirma que el «antes» del undo no queda silenciado); entrada →
  todas sus copias y **sin undo** (`JobEntryCopy.replaceMeta` no copia los atributos [B]: un Ctrl+Z no lo
  quitaría); documento → sin undo. Siempre `setChanged()`. Valida que la regla sea del tipo de documento.
- **`LintPropuestas`**: archivo que lee o escribe (sin carpeta ni extensión; nada si es una variable o viene de un
  campo), tabla (FROM del Table input, `<table>` de las salidas a BD), claves de Sort rows, Group by, Unique,
  Merge join/rows y Stream lookup, campos de la condición de Filter rows, resultados de Calculator y Formula, Add
  constants, Generate rows (con el límite), Select values, Get variables, Value mapper, Switch/case, Split; las
  entradas TRANS/JOB por su archivo. Únicos entre sí y con el documento («Leer clientes 3»). En los corpus:
  **205 de 387** nombres por defecto (52 %; el diseño midió 51 % con el prototipo).
- **Pruebas**: `PruebaArreglos` (9, sin Spoon): keys resueltas antes de tocar nada y renombres al final, keys
  viejas y objetos que desaparecen o cambian entre el análisis y la aplicación, huella, argumentos, propuestas,
  referencias tras renombrar (y guardar y volver a abrir), supresiones ida y vuelta en `.ktr` y `.kjb`, Sort rows y
  ORDER BY, y los demás arreglos. `PruebaMcpInformes`: 61 tools, argumentos y errores sin Spoon de las dos nuevas.
  Arnés: `pruebasArreglos` aplica sobre una copia de cada documento de los tres corpus todos los arreglos que no
  piden un valor y falla si uno revienta, si una key aplicada sigue saliendo, si baja el puntaje o si el documento
  no se vuelve a abrir desde su XML: 244 documentos, 143 con cambios, 121 suben de puntaje, 0 bajan.

### 2.7 WP3 en `feat/calidad` (21-oct-2026): el modo profundo

`lint_etl` con `deep=true` corre sondas que **leen** las fuentes y suben o bajan `unsorted_input` y
`empty_decimal_symbol` antes del puntaje. Archivos nuevos: `lint/Sondas.java` (la interfaz: `lint/` no ve `RunMotor`)
y `LintSondas.java` (paquete base). Cambios chicos en archivos compartidos: `RunMotor.filas` (nuevo, aditivo),
`Opciones.sondas`, `Profundo` (constantes de `Hallazgo.deep` y contadores), el gancho en `LintMotor.analizar`, la
evidencia `cause` de `ReglasOrden`, `LintEntorno.soloLectura` y los textos `deep.*` en en/es/pt.

- **Dónde corre**: `LintMotor.analizar` llama a `Opciones.sondas` **entre las reglas y el puntaje**, con los hallazgos
  de las dos reglas que no están silenciados (también los que esconden `min_severity` o `steps`: el puntaje los
  cuenta). Así lo que sube o baja ya cuenta en `score`, `counts` y los filtros. No corre en jobs, con un ciclo ni sin
  sondas (`deep.reason` lo dice: los Informes pueden llamar al motor sin `LintSondas`).
- **`RunMotor.filas( copia, step, max, ms[, cancelado] )`**: como `preview` (recorta igual), pero devuelve los objetos
  de Kettle (`RowMeta` y filas), `estado` y `completa` = terminó sola, sin errores y sin descartar filas. El init va en
  otro hilo con el mismo plazo (una BD que no contesta no se come la sonda). `errorDeLog` elige el renglón útil del log
  (el de la conversión que trae el valor, si lo hay). `preview` no cambió.
- **Lista blanca**: cada copia se recorta al step que se mira y corre solo si **todos** los que quedan pasan
  `Entorno.soloLectura`. Se agregaron dos condiciones: el plugin no falta y el step no escribe archivos de líneas malas
  (un `*_destination_directory` con algo: Text file input y Excel input con manejo de errores). `HL7Input` (parsea un
  campo) entró en la lista.
- **Orden**: una muestra por hallazgo (hasta 5000 filas de la entrada) comparando claves consecutivas con
  `ValueMeta.compare`: grupos juntos para agrupar; ascendente (o el sentido de Sorted merge) si el consumidor compara.
  Ordenada, leída entera y con orden fijo → **info** (`sorted`); con la causa `copies`, `merges` o `multi` (la
  evidencia `cause` que ahora pone `ReglasOrden`) o cortada → queda (`sorted_sample`). Desordenada → **diferencial**,
  una por consumidor: el consumidor tal cual y en otra copia con un Sort rows delante de **cada** entrada marcada (el
  `Ordenar` de `LintArreglos`, sin undo), filas comparadas como multiconjunto de textos. Distintas → **error**
  (`confirmed`); iguales → queda (`same_result`, «funciona por casualidad»); si una corrida no termina o falla, queda
  (`unsorted`, con el motivo de Kettle). Con `RandomValue`, `SystemInfo` o `ReservoirSampling` en el camino no hay
  diferencial. `multi` (varios hops) va directo a la diferencial. `data` (Data grid) ya es error: no se sondea.
- **Decimal**: el step con ese campo como String (y sin formato), releído desde su XML: la columna la parte el parser
  del propio step (ancho fijo incluido; el prototipo leía 0 valores). Hasta 2000 filas; cuentan los valores con algún
  dígito. Con `.` o `,` → **error** (`separators`), con el símbolo que usan los datos en `fix.preview` y, si queda
  tiempo, cómo lo lee esta JVM (`deep_read_as` o `deep_read_error`, con el locale). Enteros y leída entera → **info**
  (`integers`); cortada → queda; 0 valores → no evaluado. `apply_lint_fixes` vuelve a correr solo el estático: el
  símbolo hay que pasarlo en `values[key]` (la preview del profundo lo dice).
- **Lo que no se pudo** va a `not_evaluated` (`deep_not_read_only`, `deep_budget`, `deep_cancelled`, `deep_failed`,
  `deep_no_data`) y el hallazgo queda con la severidad del estático (`deep: "not_evaluated"`, `evidence.deep_reason`).
  Lo que cambia de severidad pasa a `env: true` y guarda `evidence.static_severity`; el mensaje suma «Deep check: …».
- **Presupuesto**: 30 s por sonda (la diferencial, 30 s entre sus dos corridas) y `deep_timeout_s` en total; la espera
  de `lint_etl` es la del estático más ese presupuesto más 20 s para parar la última preview. `Resultado.deep`: `ran`,
  `ms`, `budget_s`, `probes` (previews corridas), `raised`, `lowered`, `unchanged`, `not_evaluated`.
- **Pruebas**: `PruebaProfundo` (15, sin Spoon): m03, m04, m16, m17, m01, m20 rehechas sobre la copia de los ejemplos,
  los samples «Merge Join - Join 2 sorted data sets», «XML file with substitutes» y «Textfile input - fixed length»,
  la lista blanca (un HTTP client en el camino), la entrada que escribe archivos de error, el presupuesto en 0,
  cuándo no corre, `RunMotor.filas` y las utilidades (desorden, separadores). Suite completa: 203 ok.
- **Arnés**: `pruebasProfundo` corre otra vez, con `deep=true`, los documentos de los tres corpus que tienen hallazgos
  con sonda (`profundo.tsv`) y compara con `esperado-profundo.tsv` (23 casos deterministas; afuera los que dependen de
  cómo se mezclan los hilos: Unique - Case insensitive, Unique - Duplicate, m16). No entra en `linea-base.tsv`.
  Resultado (Windows en es-ES, 39 previews en ~6 s):

  | Corpus | Estático | Con modo profundo |
  |---|---|---|
  | samples (208) | 96 · 175/29/4/0 | 96 · 180/25/3/0 |
  | mutaciones (27) | 92 · 16/2/7/2 | 74 · 12/1/12/2 |

  `unsorted_input` en los samples: 6 → info (las mismas 6 que el prototipo, de 6 a 146 filas), 2 quedan por orden no
  determinista y 2 por mismo resultado («XML file with substitutes»), como en el §3.3 del diseño. Mutaciones: m03, m17
  y m04 (`step2`) → error con la diferencial (10 → 4, 10 → 4, 8 → 10 filas); m04 `step1` → info (clientes.csv ya viene
  ordenado); m16 queda (Kettle se niega a correr el Merge join con la entrada en 2 copias). `empty_decimal_symbol`: los
  8 de ancho fijo de los samples → info (enteros: 417 y 447 valores); m01 y m20 → error (en es-ES, m20 lee `2.50` como
  250.0 y m01 falla con `#.##`); y el sample «Excel Output - Write different data types» → **error**: el BigNumber
  `989873947.2984839` de Generate rows se lee **9898739472984839** en es-ES. Contra la calibración del diseño
  (177/30/1/0): el C de más es ese sample y el de `missing_plugin` (Spark, §5); los 4 de ancho fijo pasan de B a A
  porque ahora se leen sus valores (el prototipo los dejaba en warning con 0 valores leídos).

### 2.8 WP6 en `feat/calidad` (21-oct-2026): la sección «Calidad técnica» de los Informes

`SecCalidad.java` (paquete base, como los `Sec*`) corre el motor sobre la copia de la captura, igual que `lint_etl`, en
el idioma del informe. Informes ya es parte de `dev`, así que los «pedidos a Informes» del §11 del diseño se hicieron
acá mismo, con cambios chicos en sus archivos:

- **Contrato**: `SeccionId.CALIDAD = "quality"`, en `ORDEN` justo **después de `validation`** (13 secciones); títulos
  «Technical quality» / «Calidad técnica» / «Qualidade técnica» (área `secciones`: el riesgo «el puntaje se lee como
  nota» del §10). `Pedido.calidadProfunda` (boolean, apagado). Textos nuevos en el área `calidad_{en,es,pt}`; los de
  las reglas siguen saliendo de `lint_{lang}.properties`.
- **`InformeServicio`**: registra `SecCalidad` después de `SecValidacion`; quality va **marcada por defecto** (§8) con
  costo `fields` (no es «barata»: sin plazo queda omitida). `armarAhora` le pasa a `SecCalidad.armar( ctx,
  conValidacion )` si validation ya salió en ese informe (`salio`: ok o parcial). Un `tema.secciones` guardado antes
  (`informes.json` con la lista) no la tiene: hay que marcarla y guardar otra vez.
- **Entorno**: `SecCalidad.EntornoInforme` pide los campos al `Contexto` del informe (memo y presupuesto de 60 s
  compartidos con linaje y diccionario, y nunca toca una base de archivo que no existe); cuando el contexto corta por
  tiempo lo pasa como `TiempoAgotado` (el linter deja de pedir). La lista blanca y las salidas normales son las de
  `LintEntorno`; `cancelado` sigue al pedido. Con `calidadProfunda`: `Opciones.deep`, `Opciones.sondas = new
  LintSondas()` y un presupuesto de `min(60 s, lo que queda − 25 s)`. Todo en `RunMotor.enSegundoPlano` con el tope
  de la sección; si se pasa, la sección queda en error con su motivo.
- **`LintEntorno.soloLectura`** ahora rechaza también los steps que se conectarían a una base de archivo que **no
  existe** (`creaBase`: SQLite/H2/HSQLDB, `SecDestino.archivoFaltante`): una preview del modo profundo la crearía
  vacía. Vale también para `lint_etl` con `deep=true`.
- **Bloques**: Indicadores (`quality-indicadores`: «Calidad técnica» 82/100 con la banda; A verde, C amarillo, D rojo;
  errores, advertencias, sugerencias y, si hay, silenciados); con la revisión profunda, un párrafo con lo que hizo
  (`quality-profundo`, o por qué no corrió: un job, nada que revisar); un **Aviso** por error (como mucho 5 y uno que
  cuenta el resto); la tabla **Hallazgos** (`quality-hallazgos`: Severidad · Regla · Dónde · Qué pasa · Cómo se
  arregla, hasta 150 filas, con «(Depende de esta máquina.)» en los `env` y «Propuesta: …» cuando el arreglo trae
  `preview`: nombres de `LintPropuestas.completar`, rutas, el símbolo decimal); **De dónde salen los puntos**
  (`quality-puntos`, con la fórmula al pie, el tope de las sugerencias y la compuerta de 74 si se aplicaron) y **Lo que
  no se pudo revisar** (`quality-no-evaluado`; el motivo `deep_budget` se reescribe sin nombrar `deep_timeout_s`).
  Sin hallazgos, un párrafo. `datos` = `LintJson.resultado` + `section`, `deep_requested` y
  `keys_shown_in_validation`. Resumen: «Puntaje 82/100 (B): 2 advertencias y 1 sugerencia. Sin errores. Lo que más
  resta: «Ruta absoluta» (8 puntos).» (el de Resumen lo junta).
- **Sin repetir `validation`**: con las dos secciones, la tabla omite los hallazgos de `RunMotor.REGLAS_DE_VALIDATE`
  con warning o error (los info no los da `validate`) y lo dice en la nota; el puntaje y la tabla de puntos los
  cuentan igual. Si validation quedó en error u omitida, no se omite nada.
- **Estados**: parcial si alguna regla quedó `cancelled` (el informe se canceló o se quedó sin tiempo: el puntaje solo
  cuenta lo revisado); error si el análisis no terminó en el tope.
- **Renderizadores**: sin cambios. Son bloques genéricos (Indicadores, Parrafo, Aviso, Tabla con alineación, nota y
  pie) que HTML, PDF, Markdown y PPTX ya dibujan; `ModeloMuestra` suma una sección quality de muestra.
- **Panel**: el check de quality más un sub-check «Revisión profunda (lee datos)» (`calidad_profunda`), apagado en
  cada documento nuevo y deshabilitado en jobs; marcarlo marca quality. `pedido()` lo pasa solo en la vista completa y
  al exportar: la vista liviana automática no lleva quality (`PanelInformes.LIVIANAS`). `show_report` con
  `quality_deep` lo marca (y sin él lo apaga). **No hecho**: el `LocationListener` de la vista previa (clic en un
  hallazgo → seleccionar el step) ni el botón/menú «Revisar calidad» (§8 del diseño, [NP]).
- **MCP**: `build_report`, `get_report_data` y `show_report` aceptan `quality` en `sections` (el enum sale de
  `SeccionId.ORDEN`) y `quality_deep` (bool; con `sections` explícitas agrega quality). Las descripciones dicen que
  `quality_deep` solo lee las fuentes con previews de solo lectura. Las instrucciones del servidor nombran el puntaje
  de calidad entre lo que documenta `build_report`. Sin herramientas nuevas: `TOOLS` sigue en 61.
- **Pruebas**: `PruebaCalidad` (9, sin Spoon): la sección sobre 03 (el mismo puntaje que `lint_etl`), una
  transformación con problemas en en/es/pt (step suelto, ruta absoluta, nombre por defecto con su propuesta), sin
  repetir validation, validation que no salió, el modo profundo sobre m03 (error con la prueba, aviso, compuerta), un
  job, el informe entero en HTML liviano/MD/PPTX/JSON y `get_report_data`, 07 sin crear la base SQLite y el entorno
  (`TiempoAgotado`, cancelación, textos). Además `PruebaServicio`, `PruebaModelo`, `PruebaMcpInformes` y
  `PruebaPanelSwt` cuentan la sección nueva y el check.

### 2.9 WP7 en `feat/calidad` (21-oct-2026): documentación

- **Skill**: sección «Quality: `lint_etl`» en `SKILL.md` (cuándo correrlo, el puntaje, el modo profundo, arreglar y
  silenciar, la sección de los informes) y la fila en «Where everything is»; guía nueva `manual/06-calidad.md` (qué
  herramienta, cuándo, cómo leer el resultado con una salida real de m09, el puntaje, la tabla de las 31 reglas con su
  arreglo y su código D/C, modo profundo, `apply_lint_fixes`, `suppress_lint`, la sección de informes y problemas
  frecuentes). `04-informes.md` suma `quality` a la tabla de secciones y a «dónde están los datos».
- **`03-errores-comunes.md`**: el id de regla al lado de D1-D11 y de C1, C2, C13, C14, C15, C23 y C24, con las
  correcciones del §12 del diseño: C24 (START sin importar mayúsculas: es el nombre, no `<start>Y</start>`), D10 (las
  condiciones y la entrada del medio, lo de la revisión con Kitchen), D6 (el «Insert a Dummy step» de Kettle, con el
  mensaje literal de la sonda de m16) y, de paso, D1 (mayúsculas), D2 (`StackOverflowError`) y D4 (división entera).
  Un renglón nuevo en el checklist §E.
- **Recurso** `pentaho://guide/quality` (la guía 06, como `pentaho://guide/reports`): una entrada en
  `McpResources.DOCS` y el archivo en la lista de `build.ps1` (y en `tools/ci/compilar-plugin.sh`, que además no
  copiaba `04-informes.md`). `PruebaLint.pruebaGuiaCalidad` controla que la guía esté en `resources/list` y que nombre
  las 3 herramientas y **todos** los ids de `Catalogo`, y que cada D1-D11 de `03-errores-comunes.md` lleve un id que
  existe.
- **README** (los tres y `claude/README.md`): párrafo «Calidad», fila en la tabla de herramientas y en «qué le podés
  pedir», 61 herramientas, y una línea de privacidad (el modo profundo lee datos y los hallazgos pueden citarlos;
  también en `PRIVACY.md`). `INSTALL.md`: `"tools":61` en el ping. `CHANGELOG.md`: `[Unreleased]` con Added y
  Changed. `docs/roadmap.md`: el linter, hecho. **Sin subir versiones** (`VERSION` y `plugin.json` siguen en 0.5.0:
  eso es del release).

### 2.10 Prueba en vivo en Spoon (21-oct-2026)

Con el Spoon de pruebas (su propio `KETTLE_HOME`, puerto 8766) y el plugin de esta rama, todo por el MCP:

- `tools/list` da **61** herramientas; `resources/list` trae `pentaho://lint/rules` y `pentaho://guide/quality`; las
  instrucciones del servidor nombran `lint_etl` y `apply_lint_fixes`.
- **`ejemplo/construido-en-vivo/`**: las seis dan **100/A**, estático y profundo (el profundo no tiene nada que
  revisar). `validate` limpio en las cinco transformaciones. El job da un error de Kettle, «filename cannot be null»
  en «Crear carpeta salida»: ya estaba en `dev` (el `check()` de *Create a folder* lee una propiedad `filename` que esa
  entrada no tiene) y el informe del job de 0.5.0 lo muestra igual.
- **Copia rota a propósito** (CSV con ruta absoluta y símbolo decimal vacío, Table input SQLite con contraseña en claro,
  Stream lookup → Group by sin ordenar, Text file output con ruta absoluta, nombres por defecto, sin descripción; un job
  con la rama roja a Success): **37/D** estático y **3/D** profundo («con un Sort rows delante, Group by da 3 filas en
  lugar de 6»; «en esta máquina (es-ES) 3.50 se lee como 350.0»); el job, 71/C.
- **`apply_lint_fixes`**: `all_auto` (5 nombres) 37 → 58 (sin guardar, la contraseña se juzga en memoria, donde Spoon la
  tiene `Encrypted`: aviso); con las rutas, el Sort rows, «.», `${TIENDAS_DB_PASSWORD}` y la descripción, **100/A**,
  y sigue en 100 después de guardar. La transformación corre y da los totales correctos. **Un Ctrl+Z** (mandado a la
  ventana de Spoon) deshizo los cinco renombres, incluido el `<from>` del Stream lookup, y Ctrl+Y los rehízo. El job:
  71 → 73 → 92 (queda «Success 2» sin hops, como avisa la nota) → 100 al borrarlo; corre bien.
- **`suppress_lint`** en un step, en el documento, `*` en un step y en una entrada de job; rechaza `kettle_check`,
  reglas y steps que no existen y `entry` en una transformación. Las supresiones siguen ahí al guardar y reabrir, y
  `remove` también; Ctrl+Z deshace la de un step.
- **Informes**: `build_report` (PDF y Markdown, es/pt/en, con `quality_deep`) de la copia rota y de `04_hechos_ventas`
  (100/A, sin rutas personales); `show_report` solo con `quality` muestra 3/100 Banda D con el modo profundo.

Arreglado a raíz de la prueba (con su prueba automática):

- `lint_etl` estático (y `apply_lint_fixes`, que vuelve a correr las reglas) **creaba vacía** una base SQLite que
  faltaba si su carpeta existía: un Table input se conecta para dar sus campos. `LintEntorno` ahora mira
  `SecDestino.baseFaltantePara` antes de calcular campos, como `ContextoKettle`, y lanza `lint.BaseFaltante`; `Ctx` lo
  traduce como `ne.fields_db_missing` (`PruebaLint.pruebaLintBaseQueFalta`).
- PDF: la tabla de hallazgos se salía de la página cuando una celda tenía una palabra larga (la propuesta
  `«${Internal.Entry.Current.Directory}/../malo/datos/…»`): Chromium achicaba todo el informe (hasta ×1,33) y recortaba
  el resto de «Cómo se arregla». En las celdas con una tira de más de 30 caracteres sin espacios, `Html.escCortable`
  pone `<wbr>` después de `/ \ . _ -` (y cada 20 caracteres si no hay ninguno), y la clase `largo` suma
  `overflow-wrap: break-word`. Vale para todas las tablas: el informe de `04_hechos_ventas` también salía achicado por
  la ruta de su conexión (`PruebaHtml.pruebaPalabrasLargas`).
- pt: «Senha em texto puro **em a** conexão» → «**na** conexão» (los `word.*` en pt llevan la preposición contraída).
- Panel: «Vista previa lista (1 secciones)» → «(1 sección)».
- `deep.nothing` decía «no hay hallazgos de unsorted_input ni de empty_decimal_symbol», y el párrafo sale tal cual en
  el informe: ahora nombra las reglas con palabras («entrada sin ordenar», «símbolo decimal vacío»).

Queda abierto (no se tocó):

- Con una ruta absoluta en el documento, el informe la muestra entera (`C:/Users/<usuario>/…`) en «Calidad técnica» y
  ya antes en «Diagrama de flujo» («Lee: …»): es el hallazgo, pero si el informe se entrega convendría acortar la
  carpeta personal.
- El error de Kettle del job de ejemplo (arriba) hace que `validate` del job no salga limpio.
- El PDF de `04_hechos_ventas` todavía sale apenas achicado (cuerpo en 9,4 pt en vez de 9,7): otro bloque es un poco
  más ancho que la página; no se buscó cuál.

## 3. Lo que falta escribir

| WP | Qué | Dónde | Necesita |
|---|---|---|---|
| ~~WP3~~ | **Hecho** (§2.7): modo profundo (`deep=true`), sondas de orden, diferencial y decimal, lista blanca y presupuesto. | `LintSondas.java`, `lint/Sondas.java`, `RunMotor.filas` | — |
| ~~WP5~~ | **Hecho** (§2.6): `apply_lint_fixes`, `suppress_lint` y las propuestas de nombre. | `LintArreglos.java`, `LintPropuestas.java` | — |
| ~~WP6~~ | **Hecho** (§2.8): sección «Calidad técnica» (`quality`, `quality_deep`). Queda abierto el `LocationListener` (clic en un hallazgo) y el botón «Revisar calidad». | `SecCalidad.java` y los cambios chicos de Informes | — |
| ~~WP7~~ | **Hecho** (§2.9): sección «Quality» en `SKILL.md`, guía `manual/06-calidad.md`, ids de regla en `03-errores-comunes.md` (con las correcciones de §12 del diseño), `CHANGELOG.md`, README e `INSTALL.md`. La versión del `plugin.json` la sube el release. | `claude/`, README, `CHANGELOG.md` | — |

## 4. Decisiones de esta rama

- **`Regla` = un id.** El §5 dice «una clase por familia; cada una declara sus ids» y a la vez le da a `Regla`
  un solo `id()`. Quedó así: cada regla es una clase chica (con `ReglaBase` para las banderas) y la familia
  (`ReglasRutas`…) las declara en `todas()`. `Catalogo` fija el orden: `cycle` primero, `kettle_check` al final.
- **`Regla` tiene más banderas** que el §5: `apagada()`, `env()` y `puntua()`, para que el catálogo, el
  puntaje y el corrector no tengan listas aparte.
- **`Opciones.sinGuardar`** (nuevo): quien llama dice si el documento tiene cambios sin guardar; con eso las
  reglas de modo archivo quedan en `not_evaluated` y las contraseñas se juzgan en memoria (siempre W).
  `LintTools` lo toma de `hasChanged()` en el hilo de UI.
- **`kettle_check` va aparte** (`Resultado.kettleChecks`, `kettle_checks` en el JSON): «se listan aparte» (§4).
  Los `counts` no lo incluyen.
- **El puntaje no depende de los filtros**: `min_severity` y `steps` solo cambian qué se lista
  (`hidden_by_filters` dice cuántos quedaron afuera). Lo que sí cambia el puntaje es `rules`.
- **`score_caps`** en el JSON solo si se aplicó un tope: `info_points` (antes del tope de 10) y `error_gate` (74).
- **Keys**: `rule:ámbito:objeto:atributo[#n]`. El atributo es el nombre del campo (decimal, constante), el del
  resultado de la Calculator, el tag de la ruta (`filename`, `file/name`), `step1`/`step2` en Merge join,
  `reference`/`compare` en Merge rows, `input` en el resto. `#n` lo pone la regla cuando el mismo tag se repite
  en un step (dos `<file><name>`: `#1`, `#2`, estable aunque se arregle uno); si aun así dos keys chocan, el
  motor las numera.
- **Hops**: ámbito `hop`, objeto `A -> B`; se silencian en el step (o la entrada) de origen.
- **`cycle` implícito**: si `rules` pide una regla que calcula campos, `cycle` corre igual (para no desbordar la
  pila) pero sus hallazgos no se muestran.
- **Campos**: `Ctx` memoiza por step (también los fallos). Si uno se pasa del timeout (`TiempoAgotado`), no se
  piden más campos en ese análisis: el hilo que espera a la BD sigue vivo y otra espera solo suma tiempo.
- **`unsorted_input`, lo que el prototipo no hacía** ([NP] en el diseño, ahora hecho): Merge join, Merge rows y
  Sorted merge exigen las claves en el mismo orden y el sentido que esperan (ascendente; en Sorted merge, el
  de sus campos); Unique rows sin campos compara la fila entera (claves = todos los campos que llegan); el
  `ORDER BY` de un Table input se parte en columnas y sentidos (si tiene expresiones o posiciones, alcanza con
  que nombre las claves, como el prototipo). Lo que no se puede juzgar va a `not_evaluated` (`order_unknown`).
- **`failure_branch_ends_in_success`**: E si el hop rojo va directo a Success; W si llega por hops
  incondicionales; por hops verdes no se marca. Los orígenes de la categoría *Conditions* y los que evalúan se
  excluyen **por id** (el nombre de la categoría depende del idioma de Spoon).
- **`missing_input_file`** [NP]: solo steps de la categoría Input que leen archivos (por id), con
  `getResourceDependencies`; se saltean comodines, variables sin resolver, esquemas remotos y los nombres que
  vienen de un campo.
- **`password_in_file` en memoria**: no cuenta las conexiones y slave servers compartidos (`shared.xml`), que no
  están en el archivo.
- **`default_step_name`**: el arreglo sale como `rename` con `needs_value` hasta que `LintPropuestas` (WP5) ponga
  el nombre; `confirm` solo para Mapping input/output (los nombra otro archivo).
- **Textos**: `{marcador}` simple, sin `MessageFormat` (los apóstrofos no se escapan). El cómo se arregla
  genérico (`<id>.fix`) puede usar la evidencia del hallazgo (`{keys}`, `{file}`).
- **Lista blanca de solo lectura** (`LintEntorno.SOLO_LECTURA`): armada con las categorías de los 216 tipos de
  step de 9.4, dejando afuera todo lo que ejecuta código (JavaScript, UDJC, Java filter, User defined Java
  expression), habla con la red (HTTP, REST, mail, Salesforce, colas), puede escribir en una BD (Add sequence,
  Database join, Dynamic SQL, Call DB procedure) o corre otras transformaciones. Sin probar contra datos [NP].

## 5. Resultados del arnés (20-oct-2026)

Kettle 9.4 + Java 21, `-Xmx1g`, sin Spoon. 244 documentos (208 samples, 9 de la skill, 27 del corpus de
mutaciones: las 22 de la calibración + 5 jobs de rama roja). **Recall 27/27; 163 pruebas, 0 fallas.** Tiempo:
los 208 samples en ~1,1 s (~5 ms por documento, máx. 54 ms), más ~6 s de arranque de Kettle.

| Corpus | Mediana | A / B / C / D |
|---|---|---|
| samples (208) | 96 | 175 / 29 / 4 / 0 |
| skill (9) | 100 | 9 / 0 / 0 / 0 |
| mutaciones (22 + 5 jobs) | 92 | 16 / 2 / 7 / 2 |

Contra los números del diseño (§4, `final_v2`: samples 177/30/1/0) la diferencia es el **modo profundo**, que
todavía no existe: recalculando `final_v2` sobre los mismos hallazgos del prototipo pero sin las sondas, el
prototipo da 176/29/3/0 (las 6 `unsorted_input` de archivos ya ordenados quedan W en lugar de bajar a I). Y,
comparando **documento por documento** con ese prototipo estático, los conteos de todas las reglas en los samples
son iguales; solo cambian tres puntajes, por las reglas que el prototipo no tenía:

- samples `jobs/Spark Submit/Spark submit.kjb`: 100 → 74 por `missing_plugin` (la entrada de Spark no está en
  la copia de PDI; hoy lo dice `validate`).
- mutaciones m08 (50 → 42) y m09 (84 → 76): `missing_input_file` [NP], cierto en los dos (la ruta relativa se
  resuelve contra la carpeta de trabajo; `C:/Users/alumno/etl` no existe).

Las mutaciones dan la severidad esperada sin modo profundo (`unsorted_input` y `empty_decimal_symbol` como W;
`m15` como I a propósito) y los cuatro jobs de la revisión se comportan como dice el §2: File exists → rojo →
Success y rojo → Write to log (→ verde → Success) no se marcan; rojo → Write to log → incondicional → Success da W.
Lo que no se puede juzgar sin correr queda en `not_evaluated`: 7 `unsorted_input` en los samples
(`order_unknown`, las mismas 7 que el prototipo: JavaScript, UDJC, Join rows o Row normaliser en el camino, o una
clave que se crea después del Sort rows) y las 3 reglas con campos en la mutación del ciclo.

## 6. Cómo se prueba

```
powershell -ExecutionPolicy Bypass -File tools\lint-corpus\correr.ps1 -PdiHome "<...>\data-integration" -KettleHome "<carpeta propia>"
```

Compila el plugin y el arnés en `%TEMP%\pentaho-lint-corpus`, arma las mutaciones y corre el motor sobre los
samples de PDI, los ejemplos de la skill y las mutaciones. Falla si cae el recall (`esperado.tsv`), si falla una
prueba (puntaje, keys, supresiones, textos, filtros, propuestas de ruta, los arreglos de `apply_lint_fixes` sobre
cada documento y la cobertura de las propuestas de nombre, ≥ 40 %), si cambian los conteos de
`linea-base.tsv` o si el modo profundo no da lo de `esperado-profundo.tsv` (sus previews solo leen: van por la
lista blanca, como en Spoon). Con `-EscribirBase` rehace la línea base (cuando el cambio es a propósito). No escribe en la
carpeta de PDI (los logs de Kettle van a la carpeta de salida) y, antes de cada JVM, espera a tener
`-MemoriaMinGB` libres (2 por defecto); el arnés corre con `-Xmx1g`.
