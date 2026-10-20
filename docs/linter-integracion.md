# Linter de ETL: integración (Pentaho Live 0.6.0)

Qué falta para que el motor del linter (rama `feat/linter`) llegue a Spoon, con los cambios exactos en los
archivos compartidos. El diseño es `docs/linter-diseno.md`; este documento no lo repite.
Fecha: 20-oct-2026. Estado: **motor estático hecho y probado sin Spoon**; nada registrado todavía.

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

## 3. Lo que falta escribir

| WP | Qué | Dónde | Necesita |
|---|---|---|---|
| WP3 | Modo profundo: lista blanca (ya está en `LintEntorno.soloLectura`), sondas de orden y de decimal (la columna como String, con el parser del step) y prueba diferencial, presupuesto total. Sube/baja severidades en el `Resultado` (`Hallazgo.deep`) y llena `Resultado.deep`. | `LintSondas.java` (paquete base) | un método **nuevo** `RunMotor.filas( TransMeta copia, String step, int max, long ms )` que devuelva `RowMetaInterface` + filas + si terminó solo (aditivo: `preview` no cambia) |
| WP5 | `apply_lint_fixes` (resuelve todas las keys antes de tocar nada, renombres al final, un `UndoGrupo` en transformaciones) y `suppress_lint` (`Supresiones.agregar/quitar` + `setChanged()` + `UndoGrupo` en steps). Propuestas de nombre sin IA (51 % de los nombres por defecto) para llenar `Arreglo.preview` de `default_step_name`. | `LintArreglos.java`, `LintPropuestas.java` (paquete base) | `TransOps`, `UndoGrupo`, `JobSoporte` |
| WP6 | Sección «Calidad» del informe | `SecCalidad.java` (paquete base) | pedidos a Informes: `SeccionId.QUALITY`, `Pedido.calidadProfunda`, registro en `InformeServicio`, check y `LocationListener` en el panel, `quality_deep` en `build_report` |
| WP7 | Skill: sección «Calidad» en `SKILL.md` y los ids de regla al lado de D1-D11 en `03-errores-comunes.md` (con las correcciones de §12 del diseño); `CHANGELOG.md`; conteo de herramientas en los README | `claude/` (sube `version` del `plugin.json`) | — |

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
prueba (puntaje, keys, supresiones, textos, filtros, propuestas de ruta) o si cambian los conteos de
`linea-base.tsv`. Con `-EscribirBase` rehace la línea base (cuando el cambio es a propósito). No escribe en la
carpeta de PDI (los logs de Kettle van a la carpeta de salida) y, antes de cada JVM, espera a tener
`-MemoriaMinGB` libres (2 por defecto); el arnés corre con `-Xmx1g`.
