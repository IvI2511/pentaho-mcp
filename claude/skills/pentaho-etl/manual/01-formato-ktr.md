# 01 · Formato de transformaciones (.ktr) — PDI 9.4

Manual para una IA que tiene que **escribir a mano** archivos `.ktr` que corran con Pan (y abran en Spoon) a la primera. Todo lo que dice "verificado" se probó con Pan 9.4.0.0-343 + Java 21 en esta máquina; lo que dice "samples" sale de mirar los 189 `.ktr` de `data-integration/samples/`; lo que dice "código" sale de desensamblar las clases de `lib/kettle-engine-9.4.0.0-343.jar` con `javap`.

Ejemplos completos que funcionan: `ejemplos/01…07_*.ktr`. Copiá de ahí antes que inventar.

---

## 0. Checklist rápido (leelo antes de generar nada)

1. Primera línea: `<?xml version="1.0" encoding="UTF-8"?>` y el archivo **guardado en UTF-8** (con o sin BOM, las dos andan). Si lo guardás en ANSI/cp1252 y tiene tildes, Pan revienta (`Invalid byte 2 of 2-byte UTF-8 sequence`).
2. Raíz `<transformation>`. Orden de hijos que escribe Spoon: `info`, `notepads`, `connection`*, `order`, `step`*, `step_error_handling`, `slave-step-copy-partition-distribution`, `slave_transformation`, `attributes`.
3. **Cada `<step>` lleva `<copies>1</copies>`**. Si falta, el step queda con 0 copias y Pan aborta con `Unable to prepare and initialize this transformation` (exit 3). Es el único tag "obvio" que no tiene default útil.
4. **Cada `<step>` lleva `<GUI><xloc>…</xloc><yloc>…</yloc><draw>Y</draw></GUI>`**. Sin `<draw>Y</draw>` el step corre igual en Pan pero queda **oculto** en Spoon (el código hace `"Y".equalsIgnoreCase(draw)`, ausente = no dibujado).
5. Nombres de step **únicos** y **exactamente iguales** (mayúsculas, espacios, tildes) en `<hop><from>/<to>`, en `send_true_to`, en el `<from>` de Stream lookup, en `step1/step2` de Merge join y en `step_error_handling`. Un nombre duplicado hace que uno de los dos steps desaparezca sin aviso (los hops se resuelven al otro), y un hop circular **cuelga Pan para siempre**. Ninguno de los dos da error.
6. Escapá `<` `>` `&` dentro de valores: `&lt;` `&gt;` `&amp;`. Típico: `<function>&lt;=</function>` en Filter rows.
7. Rutas a archivos: siempre `${Internal.Entry.Current.Directory}/…` (se resuelve a la carpeta del `.ktr`, tanto con Pan como llamado desde un job). Nunca rutas relativas peladas: se resuelven contra la carpeta de instalación de PDI.
8. Declarales a los campos numéricos `<decimal>` y `<group>` explícitos. Con `<decimal/>` vacío se usa el locale de la máquina (acá es español): `2.50` se lee como **250** sin ningún error.
9. En Text file output **declará los campos** con `<length>-1</length>`. Con `<fields>` vacío escribe todo, pero rellena los String al largo declarado (espacios) y con una fuente SQLite hasta revienta con `OutOfMemoryError`.
10. Todos los steps que participan tienen que estar unidos por hops habilitados. Un step "suelto" en una transformación que tiene hops **no se ejecuta** (no avisa).

---

## 1. Esqueleto mínimo

### 1.1 Lo mínimo que Pan acepta (verificado, NO recomendado)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<transformation>
  <info><name>min</name></info>
  <order>
    <hop><from>Generar</from><to>Nada</to></hop>
  </order>
  <step>
    <name>Generar</name>
    <type>RowGenerator</type>
    <copies>1</copies>
    <fields><field><name>x</name><type>Integer</type><nullif>7</nullif></field></fields>
    <limit>3</limit>
  </step>
  <step>
    <name>Nada</name>
    <type>Dummy</type>
    <copies>1</copies>
  </step>
</transformation>
```

Corre bien (`Nada.0 - Finished processing (I=0, O=0, R=3, W=3 …)`). Incluso sin `<info>` Pan no se queja. Pero en Spoon los steps aparecen ocultos (sin `draw`) y apilados en (0,0). **Usalo solo para pruebas rápidas.**

### 1.2 Plantilla recomendada (la que usan los ejemplos)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<transformation>
  <info>
    <name>mi_transformacion</name>
    <description>Una línea</description>
    <extended_description>Texto largo opcional</extended_description>
    <trans_version/>
    <trans_type>Normal</trans_type>
    <directory>/</directory>
    <parameters>
    </parameters>
    <size_rowset>10000</size_rowset>
    <sleep_time_empty>50</sleep_time_empty>
    <sleep_time_full>50</sleep_time_full>
    <unique_connections>N</unique_connections>
    <feedback_shown>Y</feedback_shown>
    <feedback_size>50000</feedback_size>
    <using_thread_priorities>Y</using_thread_priorities>
    <shared_objects_file/>
    <capture_step_performance>N</capture_step_performance>
    <step_performance_capturing_delay>1000</step_performance_capturing_delay>
    <step_performance_capturing_size_limit>100</step_performance_capturing_size_limit>
    <dependencies>
    </dependencies>
    <partitionschemas>
    </partitionschemas>
    <slaveservers>
    </slaveservers>
    <clusterschemas>
    </clusterschemas>
    <created_user>-</created_user>
    <created_date>2026/10/20 10:00:00.000</created_date>
    <modified_user>-</modified_user>
    <modified_date>2026/10/20 10:00:00.000</modified_date>
    <key_for_session_key/>
    <is_key_private>N</is_key_private>
  </info>
  <notepads>
  </notepads>
  <!-- <connection>…</connection> si hace falta, acá -->
  <order>
    <!-- hops -->
  </order>
  <!-- steps -->
  <step_error_handling>
  </step_error_handling>
  <slave-step-copy-partition-distribution>
  </slave-step-copy-partition-distribution>
  <slave_transformation>N</slave_transformation>
  <attributes/>
</transformation>
```

---

## 2. `<info>`

| Tag | ¿Obligatorio? | Notas |
|---|---|---|
| `name` | Recomendado | Nombre lógico (aparece en el log: `<name> - Dispatching started…`). No tiene que coincidir con el nombre de archivo. Si falta, el log usa la ruta. |
| `description`, `extended_description` | No | Texto libre. Escapá `<`, `>`, `&`. |
| `trans_version` | No | Vacío. |
| `trans_type` | No | `Normal` (default). Existe `SerialSingleThreaded`, no lo uses. |
| `trans_status` | No | Aparece en 84/189 samples (`0`). Omitible. |
| `directory` | No | `/` (directorio de repositorio; irrelevante para archivos). |
| `parameters` | No | Parámetros con nombre, ver §8. |
| `log` | No | Bloque enorme de tablas de log (`trans-log-table`, `perf-log-table`, …). Spoon lo escribe siempre; **omitilo**: Pan no lo necesita y Spoon pone defaults al abrir. |
| `maxdate` | No | Legacy. Omitilo. |
| `size_rowset` | No | Tamaño del buffer entre steps. Default 10000. |
| `sleep_time_empty`, `sleep_time_full` | No | 50. |
| `unique_connections` | No | `Y` = una sola conexión/transacción por base para toda la transformación. |
| `feedback_shown`, `feedback_size` | No | Cada cuántas filas loguea "linenr". |
| `using_thread_priorities` | No | `Y`. |
| `shared_objects_file` | No | Vacío. |
| `capture_step_performance` y afines | No | `N`. |
| `dependencies`, `partitionschemas`, `slaveservers`, `clusterschemas` | No | Vacíos. |
| `created_*`, `modified_*` | No | Formato `yyyy/MM/dd HH:mm:ss.SSS`. Spoon los escribe con entidades (`2011&#x2f;12&#x2f;06`); en crudo también vale. |
| `key_for_session_key`, `is_key_private` | No | Vacío / `N`. |

---

## 3. `<notepads>` (notas en el lienzo)

```xml
<notepads>
  <notepad>
    <note>Texto de la nota.
Puede tener saltos de línea literales.</note>
    <xloc>64</xloc>
    <yloc>16</yloc>
    <width>380</width>
    <heigth>40</heigth>          <!-- sí: "heigth", con la errata. "height" se ignora -->
    <fontname/>
    <fontsize>-1</fontsize>
    <fontbold>N</fontbold>
    <fontitalic>N</fontitalic>
    <fontcolorred>0</fontcolorred><fontcolorgreen>0</fontcolorgreen><fontcolorblue>0</fontcolorblue>
    <backgroundcolorred>255</backgroundcolorred><backgroundcolorgreen>205</backgroundcolorgreen><backgroundcolorblue>112</backgroundcolorblue>
    <bordercolorred>100</bordercolorred><bordercolorgreen>100</bordercolorgreen><bordercolorblue>100</bordercolorblue>
    <drawshadow>Y</drawshadow>
  </notepad>
</notepads>
```

Spoon guarda los saltos de línea como `&#xa;`; un salto literal también funciona (ejemplo 01). Las notas no afectan la ejecución.

---

## 4. `<order>` y `<hop>`

```xml
<order>
  <hop>
    <from>Leer clientes</from>
    <to>Activos con saldo</to>
    <enabled>Y</enabled>
  </hop>
</order>
```

- `from`/`to`: nombre exacto del step. Si `to` (o `from`) no existe: `java.lang.NullPointerException: Cannot invoke "org.pentaho.di.trans.step.StepMeta.isMapping()" because "nextStep" is null` (exit 2).
- `enabled`: default **Y** si falta (código). Con `N` el hop no existe a efectos de ejecución, y si un step queda sin hops habilitados **no se ejecuta**.
- Un step con varios hops de salida **copia** o **reparte** según `<distribute>` del step origen (ver §5). Excepciones: Filter rows/Switch case usan sus propios targets; los hops de error se declaran en `step_error_handling`.
- Varios hops **de entrada** a un mismo step mezclan filas: todas tienen que tener el **mismo layout** (mismos campos, mismo orden y tipo). Si no, falla más adelante con errores raros (`Field [x] couldn't be found in the input stream!`); con `/safemode` Pan lo detecta: `We detected rows with varying number of fields, this is not allowed in a transformation.` Para unir flujos distintos usá Merge join / Stream lookup / Join rows; para apilar flujos iguales, Append streams o hops directos.
- Steps de 2 entradas con "info stream" (Stream lookup, Merge join, Merge rows, Append…) además de los hops **nombran al otro step dentro de su XML** (`<from>`, `<step1>/<step2>`…). Hop y nombre tienen que coincidir.
- Hops duplicados (mismo from/to dos veces): Pan los ignora sin error (verificado). No los generes igual.
- **No hay detección de ciclos**: un hop que vuelve hacia atrás hace que las filas circulen infinitamente (verificado: 300.000 filas en 6 min hasta que lo maté).
- El orden de los `<hop>` y de los `<step>` en el archivo no importa (en los samples los steps aparecen en orden alfabético; los ejemplos van en orden de flujo y andan igual).

---

## 5. `<step>`

### 5.1 Tags comunes (todos los steps)

```xml
<step>
  <name>Leer clientes</name>
  <type>CsvInput</type>
  <description/>
  <distribute>Y</distribute>
  <custom_distribution/>
  <copies>1</copies>
  <partitioning>
    <method>none</method>
    <schema_name/>
  </partitioning>

  <!-- ... tags propios del tipo de step (ver catalogo/) ... -->

  <attributes/>
  <cluster_schema/>
  <remotesteps>
    <input>
    </input>
    <output>
    </output>
  </remotesteps>
  <GUI>
    <xloc>96</xloc>
    <yloc>112</yloc>
    <draw>Y</draw>
  </GUI>
</step>
```

| Tag | Obligatorio | Default si falta (código) | Notas |
|---|---|---|---|
| `name` | **Sí** | — | Único en la transformación. Puede tener espacios, tildes, paréntesis. Escapá `&`, `<`. |
| `type` | **Sí** | — | ID del plugin, **case-sensitive** (`CsvInput`, `TextFileOutput`, `FilterRows`, `SelectValues`, `SortRows`, `GroupBy`, `StreamLookup`, `MergeJoin`, `RowGenerator`, `Constant`, `Dummy`…). Un ID inexistente no falla al cargar: crea un step "missing" y al ejecutar da `Can't run transformation due to plugin missing` (exit 3). Catálogo de IDs → `../catalogo/`. Ojo: el ID no siempre es el nombre de la GUI ("Add constants" = `Constant`, "Generate rows" = `RowGenerator`, "Modified Java Script Value" = `ScriptValueMod`, "Get variables" = `GetVariable`, "Write to log" = `WriteToLog`). |
| `description` | No | vacío | |
| `distribute` | No | **N** (= copiar) | `Y` = reparte filas round-robin entre varios hops de salida; `N` = copia cada fila a todos. Spoon escribe `Y` por defecto. Solo importa si el step tiene más de un hop de salida "normal". |
| `custom_distribution` | No | vacío | |
| `copies` | **Sí en la práctica** | **0** → la transformación no inicializa | Siempre `1`. Más de 1 = paralelismo (filas repartidas entre copias; rompe el orden). |
| `partitioning` | No | `none` | |
| `attributes` | No | | Spoon 9.x escribe `<attributes/>`. |
| `cluster_schema`, `remotesteps` | No | | Vacíos. |
| `GUI/xloc`, `GUI/yloc` | No para Pan | 0,0 | Posición del ícono en Spoon (esquina superior izquierda). |
| `GUI/draw` | No para Pan, **Sí para Spoon** | **N** (oculto) | Siempre `Y`. |

### 5.2 Layout (convención de los samples)

Medido sobre los 785 hops de los samples:

- Separación horizontal entre steps conectados en la misma fila: casi siempre **128–192 px**, la más común **160** (77 hops), luego 144 y 128. Valores múltiplos de 16 (Spoon "pega" a una grilla de 16).
- Separación vertical entre ramas: **80–112 px** (96 la más común).
- Coordenadas mínimas ~32; el 80 % de los steps cae entre x=97–622 e y=76–400.

Convención para generar:

- Flujo principal de **izquierda a derecha** en `yloc=112`, `xloc = 96, 256, 416, 576, 736…` (paso 160).
- Ramas secundarias (rama "false" de un filtro, flujo de lookup, hop de error) **debajo**, `yloc = 224` (paso 112), alineadas en x con el step del que salen o al que entran.
- Segundo flujo de entrada de un join: debajo del primero, en la misma x, y el join centrado entre ambos (ver ejemplo 05: entradas en y=112 y y=224, join en y=160).
- Notas arriba (`yloc` 16–48).

---

## 6. Declaración de campos

Muchos steps (CSV input, Text file input/output, Generate rows, Add constants, Get variables, Select values "meta", Data grid…) tienen un bloque `<fields><field>…</field></fields>`. Los tags varían un poco por step, pero el núcleo es el mismo:

```xml
<field>
  <name>saldo</name>
  <type>Number</type>
  <format>#.##</format>       <!-- máscara de conversión texto<->valor -->
  <currency/>                 <!-- símbolo de moneda -->
  <decimal>.</decimal>        <!-- símbolo decimal: DECLARALO -->
  <group/>                    <!-- símbolo de miles: DECLARALO (vacío = ninguno) -->
  <length>-1</length>         <!-- -1 = sin largo -->
  <precision>-1</precision>   <!-- -1 = sin precisión -->
  <trim_type>both</trim_type> <!-- none | left | right | both -->
</field>
```

### 6.1 Tipos (`<type>`) — nombres exactos

| Tipo | Java | Uso |
|---|---|---|
| `String` | String | Texto. |
| `Integer` | Long (64 bits) | Enteros. |
| `Number` | Double | Decimales en coma flotante. |
| `BigNumber` | BigDecimal | Decimales exactos (importes). |
| `Date` | java.util.Date | Fecha con precisión de ms. |
| `Timestamp` | java.sql.Timestamp | Fecha-hora con nanos. |
| `Boolean` | Boolean | Se lee de texto `Y`/`N` (el código de Kettle también acepta `true`/`yes`/`1`); en Text file output sale como `Y`/`N` (verificado). |
| `Binary` | byte[] | Blobs. |
| `Internet Address`, `Serializable` | | Raros. |

Un tipo mal escrito (`Int`, `integer`, `Decimal`…) no da error al cargar: el campo queda sin tipo y el step falla con `Please set a value for the missing field(s) type.` Respetá mayúsculas.

### 6.2 `format` (máscara)

- Fechas: patrones de `java.text.SimpleDateFormat`: `yyyy-MM-dd`, `dd/MM/yyyy`, `yyyy-MM-dd HH:mm:ss`, `yyyy-MM-dd HH:mm:ss.SSS`, `yyyyMMdd`. `MM` = mes, `mm` = minutos.
- Números: patrones de `java.text.DecimalFormat`: `#`, `0`, `0.00`, `#,##0.00`, `#.##`. En la máscara `.` y `,` son **posiciones** (decimal/miles); el carácter real que se lee/escribe sale de `<decimal>` y `<group>`. Ejemplo verificado (02): máscara `#,##0.00` + `<decimal>,</decimal><group>.</group>` escribe `1.500,50`.
- En inputs, la máscara se usa para **parsear**; en outputs, para **formatear**. En el output, si no ponés máscara se usa el formato "nativo" del valor (riesgo de notación rara); poné siempre `0`, `0.00`, `yyyy-MM-dd`, etc.

### 6.3 `decimal` y `group` — trampa del locale

Si `<decimal>` está vacío, Kettle usa los símbolos del **locale de la JVM**. En esta máquina (es_ES) el decimal es `,` y el de miles `.`, así que el CSV `2.50` se leyó como **250** — sin warning (verificado, salida ×100). Con la JVM en inglés el mismo archivo da 2.5. Regla: **en todo campo Number/BigNumber/Integer leído de texto, declarar `<decimal>` y `<group>` explícitos**.

### 6.4 `length` y `precision`

- `-1` = indefinido. Es lo que conviene casi siempre.
- En inputs, `length` en String se arrastra como metadato. En **Text file output** el largo del campo de salida (o el heredado si no declarás campos) se usa para **rellenar con espacios** (`Ana Pérez                                         ;`). Con una consulta SQLite cuyo TEXT viene con largo 2³¹, el padding pidió un array gigante: `java.lang.OutOfMemoryError: Requested array size exceeds VM limit`. Regla: en outputs declarar cada campo con `<length>-1</length>`.
- `precision` en Number = decimales para redondeo/formato. En Select values `-2` significa "no cambiar".

### 6.5 `trim_type`

`none`, `left`, `right`, `both`. En inputs conviene `both` para limpiar espacios.

### 6.6 Rarezas por step (verificadas)

- **Generate rows (`RowGenerator`) y Add constants (`Constant`)**: el valor del campo va en **`<nullif>`** (sí, el tag se llama así aunque es "Value"). `<set_empty_string>Y</set_empty_string>` + `<nullif/>` = string vacío en vez de null. Estos valores **no** sustituyen variables: `${X}` sale literal.
- **Text file output**: el archivo es `<file><name>` **sin extensión** + `<extention>csv</extention>` (con la errata "extention"). `<create_parent_folder>Y</create_parent_folder>` crea la carpeta; con `N` y carpeta inexistente: `Error creating output file! Parent directory [...] does not exist.` `<do_not_open_new_file_init>Y</do_not_open_new_file_init>` evita crear el archivo vacío si no llegan filas. `<format>UNIX</format>` (LF) o `DOS` (CRLF). `<encoding>UTF-8</encoding>`.
- **CSV input (`CsvInput`)**: el archivo va en `<filename>` (no en `<file><name>` como Text file input). `<lazy_conversion>N</lazy_conversion>` para que convierta tipos al leer (con `Y` la conversión se difiere y los errores aparecen en otro step). `<header>Y</header>` saltea la primera línea (log: `Header row skipped in file …`).
- **Calculator**: el tipo del cálculo lo decide **el campo A**. `MULTIPLY` con `field_a` Integer y `field_b` Number convierte B a entero **redondeando**: 3 × 2.50 dio 9 (= 3 × 3), sin error. Poné el Number/BigNumber en `field_a` (verificado en el ejemplo 03).
- **Filter rows**: condición en `<compare><condition>`. Simple: `leftvalue` + `function` + (`rightvalue` = otro campo **o** `<value>` constante). Compuesta: un `<condition>` con `<conditions>` adentro y `<operator>AND|OR</operator>` desde la segunda subcondición. Funciones: `=`, `&lt;&gt;`, `&lt;`, `&lt;=`, `&gt;`, `&gt;=`, `REGEXP`, `IS NULL`, `IS NOT NULL`, `IN LIST`, `CONTAINS`, `STARTS WITH`, `ENDS WITH`, `LIKE`, `TRUE`. `send_true_to`/`send_false_to` vacíos = las filas que no cumplen se descartan y las que cumplen siguen por **todos** los hops de salida.
- **Select values**: `<fields>` contiene, en este orden, los `<field>` a seleccionar (con `<rename>`), `<select_unspecified>N</select_unspecified>`, los `<remove>` y los `<meta>` (cambio de tipo/formato). Un campo inexistente en `<field>` da `Couldn't find field 'x' in row!`.
- **Merge join**: los campos del segundo flujo que choquen de nombre salen con sufijo `_1` (`cliente_id_1`).

---

## 7. `<connection>` (bases de datos)

Va a nivel raíz, **después de `<notepads>` y antes de `<order>`**. Los steps la referencian por nombre (`<connection>sqlite_demo</connection>`).

```xml
<connection>
  <name>sqlite_demo</name>
  <server/>                          <!-- host; vacío para SQLite/H2 embebido -->
  <type>SQLITE</type>                <!-- ID del tipo, ver lista abajo -->
  <access>Native</access>            <!-- Native (JDBC) | JNDI | ODBC -->
  <database>${Internal.Entry.Current.Directory}/salida/demo.sqlite</database>
  <port>-1</port>                    <!-- puerto; -1 si no aplica -->
  <username/>
  <password/>                        <!-- texto plano o "Encrypted 2be98…" -->
  <servername/>
  <data_tablespace/>
  <index_tablespace/>
  <attributes>
    <attribute><code>PORT_NUMBER</code><attribute>-1</attribute></attribute>
    <attribute><code>PRESERVE_RESERVED_WORD_CASE</code><attribute>Y</attribute></attribute>
    <attribute><code>SUPPORTS_BOOLEAN_DATA_TYPE</code><attribute>Y</attribute></attribute>
    <attribute><code>SUPPORTS_TIMESTAMP_DATA_TYPE</code><attribute>Y</attribute></attribute>
    <attribute><code>USE_POOLING</code><attribute>N</attribute></attribute>
  </attributes>
</connection>
```

- Verificado con SQLite (ejemplo 07): la variable en `<database>` se resuelve y el archivo `.sqlite` se crea al conectar.
- IDs de `<type>` disponibles (de `kettle-database-types.xml`): `AS/400`, `CACHE`, `DB2`, `DBASE`, `DERBY`, `EXASOL4`, `EXTENDB`, `FIREBIRD`, `GENERIC`, `GREENPLUM`, `INFINIDB`, `SQLBASE`, `H2`, `HYPERSONIC`, `INFOBRIGHT`, `INFORMIX`, `INGRES`, `VECTORWISE`, `INTERBASE`, `KINGBASEES`, `LucidDB`, `MARIADB`, `MONETDB`, `MSACCESS`, `MSSQLNATIVE`, `MSSQL`, `MYSQL`, `MONDRIAN`, `NEOVIEW`, `NETEZZA`, `ORACLE`, `ORACLERDB`, `POSTGRESQL`, `REDSHIFT`, `REMEDY-AR-SYSTEM`, `SAPDB`, `SQLITE`, `SYBASE`, `SYBASEIQ`, `TERADATA`, `UNIVERSE`, `VERTICA`, `VERTICA5`, `GOOGLEBIGQUERY`, `SNOWFLAKEHV`, `AZURESQLDB`.
- Drivers JDBC presentes en `lib/`: `h2-2.1.210`, `sqlite-jdbc-3.27.2.1`, `postgresql-42.2.23`, `hsqldb-2.3.2`, `derby`. **No hay** MySQL/MariaDB, SQL Server ni Oracle: hay que copiar el `.jar` del driver a `lib/`.
- Contraseñas: se pueden dejar en texto plano o ofuscar con `Encr.bat -kettle <clave>` → imprime `Encrypted 2be98afc86aa7f2e4cb0aab73cc97bbd5` (es ofuscación reversible, no cifrado). Al generar un `.ktr`, no escribas contraseñas reales: dejá `<password/>` vacío y que el usuario la cargue él en el diálogo de la conexión en Spoon.
- Con `<access>JNDI</access>` (lo que usan casi todos los samples) los datos de conexión salen de `simple-jndi/jdbc.properties`; para archivos generados preferí `Native`.

---

## 8. Parámetros y variables

### 8.1 Declarar parámetros

```xml
<info>
  …
  <parameters>
    <parameter>
      <name>SALDO_MINIMO</name>
      <default_value>1000</default_value>
      <description>Saldo mínimo para pasar el filtro</description>
    </parameter>
  </parameters>
```

Se pasan con Pan `"/param:SALDO_MINIMO=2000"` (ver comillas en `03-errores-comunes.md`), o desde un job (§ de TRANS en `02-formato-kjb.md`). Si no se pasan, vale `default_value`. Verificado en ejemplos 01 y 06.

### 8.2 Usar variables

- Sintaxis `${NOMBRE}` (también existe `%%NOMBRE%%`, estilo Windows; `SortRows` usa `%%java.io.tmpdir%%` por default).
- Se sustituyen **solo en los campos que Spoon marca con el símbolo `$`** (rutas de archivo, SQL si `variables_active=Y`, nombres de base, `<variable>` de Get variables, mensajes de log…). **No** en: valores de Generate rows/Add constants, constantes de Filter rows, nombres de campo. Verificado: `${ARCHIVO_SALIDA}` en un `<nullif>` de Add constants salió literal.
- Para comparar contra un parámetro dentro del flujo: step **Get variables** (`GetVariable`) que crea un campo con el valor de `${PARAM}` (tipado), y después Filter rows campo-contra-campo (`<rightvalue>umbral</rightvalue>`). Ejemplo 06.
- Variable inexistente: queda **literal** en la ruta y la ruta se resuelve contra la carpeta de PDI: `Could not read from "file:///C:/…/data-integration/${CARPETA_DATOS}/clientes.csv" because it is not a file.`
- Variables globales: `KETTLE_HOME/.kettle/kettle.properties` (`NOMBRE=valor`). Para probar sin tocar la configuración del usuario, apuntar `KETTLE_HOME` a una carpeta propia.

### 8.3 Variables internas útiles (valores verificados)

| Variable | Con Pan (ktr suelta) | ktr llamada desde un job |
|---|---|---|
| `${Internal.Entry.Current.Directory}` | `file:///C:/…/carpeta-del-ktr` | `file:///C:/…/carpeta-del-ktr` (la del **ktr**, no la del job; verificado con un job en otra carpeta) |
| `${Internal.Transformation.Filename.Directory}` | `file:///C:/…/carpeta-del-ktr` | idem |
| `${Internal.Transformation.Filename.Name}` | `e37_valores_internos.ktr` | idem |
| `${Internal.Job.Filename.Directory}` | **`Parent Job File Directory`** (texto literal, inútil) | carpeta del job |
| `${user.dir}` | carpeta de instalación de PDI (`…\data-integration`) | idem |
| `${KETTLE_HOME}` | el KETTLE_HOME configurado | idem |

Conclusiones:

- Usá **`${Internal.Entry.Current.Directory}`** en `.ktr` y `.kjb`: es la que anda en los dos contextos. `Internal.Transformation.Filename.Directory` también anda en ktr (los samples la usan); `Internal.Job.Filename.Directory` **nunca** dentro de un ktr.
- Los valores son URIs VFS (`file:///…`, espacios como `%20`). Funcionan en todo step de archivos de PDI, pero **no** sirven tal cual para pasarlos a un script de shell.
- Separador: `/` funciona en Windows. Los samples también usan `${file.separator}` o `\`.

---

## 9. Manejo de errores por step (`<step_error_handling>`)

Desvía las filas que fallan en un step hacia otro step en lugar de abortar. Dos piezas, las dos obligatorias:

1. Un **hop normal** desde el step origen al step de error, en `<order>`.
2. Un `<error>` en `<step_error_handling>` (a nivel raíz, después de los `<step>`):

```xml
<step_error_handling>
  <error>
    <source_step>Leer clientes con errores</source_step>
    <target_step>Escribir errores</target_step>
    <is_enabled>Y</is_enabled>
    <nr_valuename>num_errores</nr_valuename>              <!-- campo con la cantidad de errores -->
    <descriptions_valuename>descripcion_error</descriptions_valuename>
    <fields_valuename>campos_error</fields_valuename>
    <codes_valuename>codigos_error</codes_valuename>
    <max_errors/>            <!-- vacío o -1 = sin límite -->
    <max_pct_errors/>
    <min_pct_rows/>
  </error>
</step_error_handling>
```

- Verificado (ejemplo 06): CSV input con 3 filas malas (id `X4`, saldo `no-es-numero`, fecha `30/09/2023`) → 3 filas al step de error (`E=3`), el resto sigue. Exit 0.
- La fila de error trae **todos los campos del origen** (los que no se pudieron convertir quedan null) **más** los 4 campos de diagnóstico. `descripcion_error` es multilínea (ej.: `fecha_alta String : couldn't convert string [30/09/2023] to a date using format [yyyy-MM-dd] on offset location 2`), `codigos_error` = `CSVINPUT001`.
- No todos los steps soportan error handling. Verificado acá: CSV input. Según la GUI también lo tienen, entre otros, Text file input, Select values, Calculator, Table output e Insert/update; Filter rows no lo necesita (tiene true/false). Antes de usarlo en otro step, confirmalo en `../catalogo/`.
- Sin error handling, la primera fila mala aborta el step y la transformación: `There were 1 conversion errors on line 2` (exit 1).

---

## 10. Encoding, escapes y XML

- Spoon escribe muchos caracteres como entidades numéricas (`&#x2f;` = `/`, `&#x24;&#x7b;` = `${`, `&#x3b;` = `;`, `&#x22;` = `"`, `&#x20ac;` = €, `&#xa;` = salto de línea). **No hace falta**: XML en crudo en UTF-8 funciona igual (todos los ejemplos van en crudo, con `ñ`, tildes y `€`).
- Lo único obligatorio es escapar `&` → `&amp;`, `<` → `&lt;` (y por prolijidad `>` → `&gt;`). Errores literales si no:
  - `<function><=</function>` → `[Fatal Error] :188:24: The content of elements must consist of well-formed character data or markup.`
  - `Pérez & Cía` → `The entity name must immediately follow the '&' in the entity reference.`
- Separador tab en CSV: escribilo como entidad, `<separator>&#x9;</separator>` (no verifiqué otras formas).
- `encoding` de archivos de datos (CSV/Text) es independiente del encoding del `.ktr`: declarar `<encoding>UTF-8</encoding>` en inputs y outputs.

---

## 11. Plantillas de los steps usados en los ejemplos

No repito acá el XML de cada step: está completo y verificado en `ejemplos/`. Mapa:

| Step (GUI) | `type` | Ejemplo |
|---|---|---|
| Generate rows | `RowGenerator` | 01 |
| Add constants | `Constant` | 01 |
| Text file output | `TextFileOutput` | 01–07 |
| CSV file input | `CsvInput` | 02–06 |
| Filter rows (condición compuesta, true/false) | `FilterRows` | 02 |
| Filter rows (campo contra campo, sin targets) | `FilterRows` | 06 |
| Select values (select + rename + meta) | `SelectValues` | 02 |
| Select values (remove) | `SelectValues` | 05 |
| Dummy | `Dummy` | 02 |
| Calculator | `Calculator` | 03 |
| Sort rows | `SortRows` | 03, 05 |
| Group by | `GroupBy` | 03 |
| Stream lookup | `StreamLookup` | 04 |
| Merge join | `MergeJoin` | 05 |
| Get variables | `GetVariable` | 06 |
| Table input + conexión | `TableInput` | 07 |

Para el resto de los steps (IDs y tags) → `../catalogo/`.
