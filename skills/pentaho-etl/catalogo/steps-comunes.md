# Steps más usados en PDI 9.4 — guía anotada para escribir `.ktr` a mano

Guía de los ~45 steps que aparecen en un ETL típico de curso de Big Data. Complementa a `INDICE.md`
(lista completa con ids) y a `steps/<id>.xml` (XML por defecto que genera la propia instalación).
El formato general del `.ktr` (cabecera `<info>`, `<connection>`, `<order>`/hops, `<notepads>`…) está en `manual/`.

**Cómo están armados los ejemplos.** Cada bloque XML muestra **solo el fragmento propio del step**, es decir,
lo que va dentro de `<step>` *después* del envoltorio común. Un step completo es:

```xml
<step>
  <name>Leer ventas</name>            <!-- único dentro de la transformación; los hops lo referencian -->
  <type>CsvInput</type>               <!-- ID del plugin, ver INDICE.md -->
  <description/>
  <distribute>Y</distribute>
  <custom_distribution/>
  <copies>1</copies>
  <partitioning><method>none</method><schema_name/></partitioning>
  <!-- ======= FRAGMENTO PROPIO DEL STEP (lo que muestran los ejemplos de abajo) ======= -->
  <attributes/>
  <cluster_schema/>
  <remotesteps><input></input><output></output></remotesteps>
  <GUI><xloc>100</xloc><yloc>100</yloc><draw>Y</draw></GUI>
</step>
```

Origen de cada ejemplo: se indica `samples/...` cuando sale de los ejemplos de la instalación
(`data-integration/samples/`), o *por defecto* cuando sale de `steps/<id>.xml` / de rellenar la meta vía API.
Todos los tags de esta guía se verificaron contra los samples o contra el `getXML()` real de la 9.4
(ver «Verificación» al final). Algunas partes se acortaron (`...`) para que se lean mejor.

---

## Reglas que valen para todos

1. **`<type>` = id del plugin**, sensible a mayúsculas (`CsvInput`, no `CSV file input` ni `csvinput`).
2. **Partí del XML por defecto** (`steps/<id>.xml`) y cambiá lo necesario. Al cargar, cada step lee sus
   tags con `XMLHandler.getTagValue`; un tag ausente casi siempre se vuelve `null` → valor por defecto, pero no
   en todos (algunos steps tiran NPE o se quedan en un estado raro). Copiar el bloque completo es lo seguro.
3. **Tipos de campo** (`<type>` dentro de `<field>`): `String`, `Integer`, `Number`, `BigNumber`, `Date`,
   `Timestamp`, `Boolean`, `Binary`, `Internet Address`, `Serializable`, `None`. `<length>`/`<precision>` `-1` = sin definir.
   Formatos: fecha `yyyy-MM-dd`, `dd/MM/yyyy HH:mm:ss`; número `#,##0.00`, `#.#`, `0`.
4. **Ojo: un mismo `<field>` puede contener `<name>` y `<type>`** que no tienen nada que ver con el
   `<name>`/`<type>` del step. Si procesás el XML con regex, ancla por profundidad; mejor usar un parser DOM.
5. **Steps que nombran a otros steps** (lookup, merge, filtros, switch, append…) necesitan **las dos cosas**:
   el nombre del otro step en su tag **y** el hop correspondiente en `<order>`. Si el nombre del tag no coincide
   exactamente con un `<name>` existente, Kettle lo ignora en silencio (o falla al verificar).
   | Step | Tag(s) que nombran steps | Tipo de stream |
   |---|---|---|
   | `StreamLookup` | `<from>` | *info* (el step de lookup) |
   | `MergeJoin` | `<step1>`, `<step2>` | *info* ×2 |
   | `MergeRows` | `<reference>`, `<compare>` | *info* ×2 |
   | `JoinRows` | `<main>` (opcional) | entrada principal |
   | `Append` | `<head_name>`, `<tail_name>` | *info* ×2 |
   | `FilterRows` | `<send_true_to>`, `<send_false_to>` | *targets* |
   | `SwitchCase` | `<target_step>` de cada `<case>`, `<default_target_step>` | *targets* |
   | `TableInput` | `<lookup>` (step que alimenta los `?`) | *info* |
6. **Escapes XML.** Spoon escribe `=` como `&#x3d;`, `<` como `&#x3c;`, `>` como `&gt;`. Al escribir a mano basta con
   escapar `<` y `&` (`&lt;`, `&amp;`); `=` y `>` pueden ir tal cual.
7. **Rutas.** Usar `${Internal.Entry.Current.Directory}` o `${Internal.Transformation.Filename.Directory}` para
   rutas relativas al `.ktr` (los samples usan la segunda). Barras `/` funcionan también en Windows.
8. **Conexiones.** `<connection>NOMBRE</connection>` dentro de un step referencia un bloque
   `<connection><name>NOMBRE</name>...` a nivel de `<transformation>` (ver `manual/`). El nombre debe coincidir.
9. **Hops**: `<order><hop><from>A</from><to>B</to><enabled>Y</enabled></hop>...</order>`.

---

## Entrada

### CSV file input — `CsvInput`
Lee un CSV/fichero delimitado de forma rápida (un solo fichero, NIO). El step más usado para cargar datasets.
Ejemplo: `samples/transformations/Closure generator - standard mondrian sample.ktr`
```xml
<filename>${Internal.Transformation.Filename.Directory}/files/closure_input.txt</filename>
<filename_field/>
<rownum_field/>
<include_filename>N</include_filename>
<separator>;</separator>
<enclosure>"</enclosure>
<header>Y</header>
<buffer_size>50000</buffer_size>
<lazy_conversion>N</lazy_conversion>
<add_filename_result>N</add_filename_result>
<parallel>N</parallel>
<newline_possible>Y</newline_possible>
<encoding>UTF-8</encoding>
<fields>
  <field>
    <name>supervisor_id</name>
    <type>Integer</type>
    <format/>
    <currency/><decimal/><group/>
    <length>-1</length>
    <precision>-1</precision>
    <trim_type>none</trim_type>
  </field>
  <field>
    <name>name</name>
    <type>String</type>
    <format/><currency/><decimal/><group/>
    <length>-1</length><precision>-1</precision>
    <trim_type>both</trim_type>
  </field>
</fields>
```
**Trampas.** Los `<field>` se asignan **por posición**, no por nombre de cabecera: el orden debe ser el de las
columnas del fichero (el nombre puede ser cualquiera). `<header>Y</header>` solo salta la primera línea.
`<lazy_conversion>Y</lazy_conversion>` deja los valores como bytes sin convertir (rápido, pero luego
Calculator/JS/lookups pueden fallar o comparar mal: dejar en `N` salvo que sepas lo que hacés).
Separador tab: el carácter TAB literal, escrito `<separator>&#x9;</separator>` (en Spoon, botón «Insert TAB»).
Fechas: `<type>Date</type>` + `<format>yyyy-MM-dd</format>`; si el formato no coincide, el step aborta.
Decimales con coma: `<decimal>,</decimal><group>.</group>`. No admite comodines ni varios ficheros → `TextFileInput`.

### Text file input — `TextFileInput`
Lector general: varios ficheros, comodines (regex), ancho fijo, comprimidos, filtros, gestión de errores.
Ejemplo: `samples/transformations/Getting Started Transformation.ktr` (acortado)
```xml
<accept_filenames>N</accept_filenames>
<separator>,</separator>
<enclosure>"</enclosure>
<header>Y</header>
<nr_headerlines>1</nr_headerlines>
<footer>N</footer>
<noempty>Y</noempty>
<format>mixed</format>             <!-- DOS | Unix | mixed -->
<encoding/>
<file>
  <name>${Internal.Transformation.Filename.Directory}/files/Zipssortedbycitystate.csv</name>
  <filemask/>                       <!-- regex Java, p.ej. ventas_.*\.csv (con <name> = carpeta) -->
  <exclude_filemask/>
  <file_required>N</file_required>
  <include_subfolders>N</include_subfolders>
  <type>CSV</type>                  <!-- CSV | Fixed -->
  <compression>None</compression>
</file>
<filters></filters>
<fields>
  <field>
    <name>CITY</name>
    <type>String</type>
    <format/><currency>$</currency><decimal>.</decimal><group>,</group>
    <nullif>-</nullif>
    <ifnull/>
    <position>-1</position>
    <length>25</length>
    <precision>-1</precision>
    <trim_type>none</trim_type>
    <repeat>N</repeat>
  </field>
</fields>
<limit>0</limit>
<error_ignored>N</error_ignored>
...
```
**Trampas.** El tipo de fichero va **dentro** de `<file>` (`<type>CSV</type>`), no al nivel del step. Si se ponen
varios ficheros, `<file>` lleva varios `<name>`/`<filemask>`… en paralelo (mismo orden). `<filemask>` es **regex**,
no glob (`.*\.csv`, no `*.csv`). Tiene muchísimos tags (85 líneas por defecto): copiar `steps/TextFileInput.xml`.

### Microsoft Excel input — `ExcelInput`
Lee hojas de `.xls`, `.xlsx` u `.ods`.
Ejemplo: `samples/transformations/XML Join - Create a multilayer XML file.ktr` (acortado)
```xml
<header>Y</header>
<noempty>Y</noempty>
<stoponempty>N</stoponempty>
<limit>0</limit>
<encoding/>
<add_to_result_filenames>Y</add_to_result_filenames>
<accept_filenames>N</accept_filenames>
<file>
  <name>${Internal.Transformation.Filename.Directory}/files/datos.xlsx</name>
  <filemask/>
  <exclude_filemask/>
  <file_required>N</file_required>
  <include_subfolders>N</include_subfolders>
</file>
<fields>
  <field>
    <name>orderNumber</name>
    <type>Integer</type>
    <length>-1</length>
    <precision>-1</precision>
    <trim_type>none</trim_type>
    <repeat>N</repeat>
    <format/><currency/><decimal/><group/>
  </field>
</fields>
<sheets>
  <sheet>
    <name>Hoja1</name>
    <startrow>0</startrow>
    <startcol>0</startcol>
  </sheet>
</sheets>
<strict_types>N</strict_types>
<error_ignored>N</error_ignored>
...
<spreadsheet_type>POI</spreadsheet_type>
```
**Trampas.** `<spreadsheet_type>`: `JXL` (el **por defecto**, solo `.xls` viejo), `POI` (`.xls` y `.xlsx`),
`SAX_POI` (`.xlsx` grandes, streaming), `ODS`. **Para `.xlsx` hay que poner `POI` o `SAX_POI`**; con JXL falla.
Sin `<sheets>` lee todas las hojas. `<startrow>`/`<startcol>` empiezan en 0. Los campos se asignan por posición
desde `startcol`, con `<header>Y</header>` la primera fila es cabecera.

### Table input — `TableInput`
Ejecuta un `SELECT` contra una conexión y emite las filas.
Ejemplo: `samples/jobs/process all tables/Process one table.ktr`
```xml
<connection>SampleDataAdmin.H2</connection>
<sql>SELECT count(*) as NrRows FROM ${TABLENAME}</sql>
<limit>0</limit>
<lookup/>                              <!-- step que alimenta los ? del SQL (info stream) -->
<execute_each_row>N</execute_each_row>
<variables_active>Y</variables_active> <!-- sustituir ${VAR} en el SQL -->
<lazy_conversion_active>N</lazy_conversion_active>
```
**Trampas.** Sin `<variables_active>Y</variables_active>` los `${...}` se mandan literales a la BD. Con
parámetros `?`: poner el step proveedor en `<lookup>` + hop, y `<execute_each_row>Y</execute_each_row>` si hay
que ejecutar una vez por fila. Escapar `<` en el SQL (`&lt;`). No lleva `<fields>`: la estructura sale de la BD.

### Generate rows — `RowGenerator`
Genera N filas con campos constantes (útil para disparar un flujo o pruebas). El step más usado en samples (196).
Ejemplo: `samples/transformations/Value Mapper - Same and new field.ktr`
```xml
<fields>
  <field>
    <name>A</name>
    <type>String</type>
    <format/>
    <currency/><decimal/><group/>
    <nullif>Five</nullif>          <!-- ¡el VALOR del campo va en <nullif>! -->
    <length>-1</length>
    <precision>-1</precision>
    <set_empty_string>N</set_empty_string>
  </field>
</fields>
<limit>1</limit>
<never_ending>N</never_ending>
<interval_in_ms/>
<row_time_field/>
<last_time_field/>
```
**Trampa clásica.** El valor constante se guarda en **`<nullif>`** (nombre histórico). Igual en `Constant` y
`DataGrid` no (este usa `<data>`). Para cadena vacía: `<set_empty_string>Y</set_empty_string>`.
`<limit>` admite variables (`${N}`).

### Data grid — `DataGrid`
Tabla de datos fija escrita en el propio step (dimensiones pequeñas, mapeos, tests).
Ejemplo: `samples/transformations/Rules Accumulator - Solve Golfer Rule.ktr`
```xml
<fields>
  <field>
    <name>position</name>
    <type>Integer</type>
    <format/><currency/><decimal/><group/>
    <length>-1</length>
    <precision>-1</precision>
    <set_empty_string>N</set_empty_string>
  </field>
</fields>
<data>
  <line><item>1</item></line>
  <line><item>2</item></line>
</data>
```
**Trampas.** Cada `<line>` lleva un `<item>` por campo, **en el mismo orden** que `<fields>`. Los valores se
parsean con el `<format>` del campo (fechas: poner `<format>yyyy-MM-dd</format>`).

### JSON input — `JsonInput`
Extrae campos de JSON con JSONPath, desde fichero o desde un campo del stream.
Ejemplo (desde campo): `samples/transformations/JsonInput - read incoming stream.ktr` (acortado)
```xml
<include>N</include>
<include_field/>
<rownum>N</rownum>
<addresultfile>N</addresultfile>
<readurl>N</readurl>
<removeSourceField>N</removeSourceField>
<IsIgnoreEmptyFile>N</IsIgnoreEmptyFile>
<doNotFailIfNoFile>N</doNotFailIfNoFile>
<ignoreMissingPath>N</ignoreMissingPath>
<rownum_field/>
<file>
  <name/>                           <!-- leer de fichero: ruta aquí e <IsInFields>N -->
  <filemask/>
  <exclude_filemask/>
  <file_required>N</file_required>
  <include_subfolders>N</include_subfolders>
</file>
<fields>
  <field>
    <name>title</name>
    <path>$..title</path>
    <type>String</type>
    <format/><currency/><decimal/><group/>
    <length>-1</length><precision>-1</precision>
    <trim_type>none</trim_type>
    <repeat>N</repeat>
  </field>
</fields>
<limit>0</limit>
<IsInFields>Y</IsInFields>          <!-- Y = el JSON viene en el campo <valueField> -->
<IsAFile>N</IsAFile>                <!-- Y = <valueField> contiene una RUTA de fichero -->
<valueField>Content</valueField>
...
```
**Trampas.** Todos los paths de un mismo step deben devolver el **mismo número de elementos** (se combinan por
índice); si no, error «data structure mismatch» o filas desalineadas. `$..x` (deep scan) devuelve todos los `x`
de cualquier nivel. Para claves que pueden faltar: `<ignoreMissingPath>Y</ignoreMissingPath>`.

### Get system info — `SystemInfo`
Añade fechas del sistema, argumentos, nombre de la transformación, hostname…
Ejemplo: `samples/transformations/Get System Info - Command Line Argument and filter.ktr`
```xml
<fields>
  <field>
    <name>hoy</name>
    <type>system date (variable)</type>
  </field>
  <field>
    <name>arg1</name>
    <type>command line argument 1</type>
  </field>
</fields>
```
**Trampas.** `<type>` aquí **no es un tipo de dato** sino el código del dato del sistema, en inglés y con
espacios: `system date (variable)`, `system date (fixed)`, `today start`, `yesterday start`,
`last month start`, `Hostname`, `IP address`, `Current PID`, `command line argument 1`…(valores vistos en samples).
Si no tiene hop de entrada genera **una** fila; si tiene, añade los campos a cada fila.

### Get variables — `GetVariable`
Convierte variables/parámetros (`${VAR}`) en campos.
Ejemplo: `samples/transformations/GetXMLData - Differents Options.ktr`
```xml
<fields>
  <field>
    <name>filename</name>
    <variable>${Internal.Transformation.Filename.Directory}/files/test_xml_file.xml</variable>
    <type>String</type>
    <format/><currency/><decimal/><group/>
    <length>-1</length><precision>-1</precision>
    <trim_type>none</trim_type>
  </field>
</fields>
```
**Trampa.** `<variable>` lleva la expresión **con** `${}` (se puede concatenar texto). Igual que `SystemInfo`:
sin entrada genera una fila.

---

## Salida

### Text file output — `TextFileOutput`
Escribe CSV/delimitado.
Ejemplo: `samples/jobs/arguments2/writeback.ktr` + campos de `samples/transformations/General - Change log processing 2.ktr`
```xml
<separator>;</separator>
<enclosure>"</enclosure>
<enclosure_forced>N</enclosure_forced>
<enclosure_fix_disabled>N</enclosure_fix_disabled>
<header>Y</header>
<footer>N</footer>
<format>UNIX</format>              <!-- DOS | UNIX | CR | None : fin de línea -->
<compression>None</compression>
<encoding>UTF-8</encoding>
<endedLine/>
<fileNameInField>N</fileNameInField>
<fileNameField/>
<create_parent_folder>Y</create_parent_folder>
<file>
  <name>${Internal.Transformation.Filename.Directory}/output/resultado</name>  <!-- SIN extensión -->
  <is_command>N</is_command>
  <servlet_output>N</servlet_output>
  <do_not_open_new_file_init>N</do_not_open_new_file_init>
  <extention>csv</extention>       <!-- sic: "extention" -->
  <append>N</append>
  <split>N</split>
  <haspartno>N</haspartno>
  <add_date>N</add_date>
  <add_time>N</add_time>
  <SpecifyFormat>N</SpecifyFormat>
  <date_time_format/>
  <add_to_result_filenames>Y</add_to_result_filenames>
  <pad>N</pad>
  <fast_dump>N</fast_dump>
  <splitevery>0</splitevery>
</file>
<fields>
  <field>
    <name>id</name>
    <type>Integer</type>
    <format>0</format>
    <currency/><decimal/><group/>
    <nullif/>
    <trim_type>none</trim_type>
    <length>-1</length>
    <precision>-1</precision>
  </field>
</fields>
```
**Trampas.** Nombre y extensión van separados (`<name>` sin extensión + `<extention>`, con la errata). Con
`<fields>` vacío escribe **todos** los campos del stream en su orden. Números: sin `<format>` puede salir
`1234.0` o con separadores locales; poner `<format>0</format>` / `<format>#.##</format>`. Si `<split>`/`<haspartno>`
están en `Y` el fichero se llama `resultado_0.csv`.

### Microsoft Excel writer — `TypeExitExcelWriterStep`
Escribe `.xlsx`/`.xls` (POI): plantillas, hojas existentes, append. **Es el que hay que usar para `.xlsx`.**
Por defecto + estructura de `<field>` (no hay samples):
```xml
<header>Y</header>
<footer>N</footer>
<makeSheetActive>Y</makeSheetActive>
<startingCell>A1</startingCell>
<appendOmitHeader>N</appendOmitHeader>
<appendOffset>0</appendOffset>
<appendEmpty>0</appendEmpty>
<rowWritingMethod>overwrite</rowWritingMethod>
<forceFormulaRecalculation>N</forceFormulaRecalculation>
<leaveExistingStylesUnchanged>N</leaveExistingStylesUnchanged>
<appendLines>N</appendLines>
<add_to_result_filenames>Y</add_to_result_filenames>
<file>
  <name>${Internal.Transformation.Filename.Directory}/output/informe</name>
  <extention>xlsx</extention>
  <do_not_open_newfile_init>N</do_not_open_newfile_init>
  <split>N</split>
  <add_date>N</add_date>
  <add_time>N</add_time>
  <SpecifyFormat>N</SpecifyFormat>
  <date_time_format/>
  <sheetname>Resumen</sheetname>
  <autosizecolums>N</autosizecolums>
  <stream_data>N</stream_data>       <!-- Y = SXSSF, para muchos miles de filas -->
  <protect_sheet>N</protect_sheet>
  <password>Encrypted </password>
  <protected_by/>
  <splitevery>0</splitevery>
  <if_file_exists>new</if_file_exists>   <!-- new | reuse -->
  <if_sheet_exists>new</if_sheet_exists> <!-- new | reuse -->
</file>
<template>
  <enabled>N</enabled>
  <sheet_enabled>N</sheet_enabled>
  <filename>template.xls</filename>
  <sheetname/>
  <hidden>N</hidden>
</template>
<fields>
  <field>
    <name>total</name>
    <type>Number</type>
    <format>#,##0.00</format>
    <title>Total</title>
    <titleStyleCell/>
    <styleCell/>
    <commentField/>
    <commentAuthorField/>
    <formula>N</formula>
    <hyperlinkField/>
  </field>
</fields>
```
**Trampas.** El id no se parece al nombre («Microsoft Excel writer» = `TypeExitExcelWriterStep`). `<extention>`
decide el formato (`xlsx`/`xls`). `<if_file_exists>new</if_file_exists>` **sobrescribe** el fichero.

### Microsoft Excel output — `ExcelOutput`
Writer antiguo basado en JXL: **solo `.xls`**. Para algo nuevo usar `TypeExitExcelWriterStep`.
Ejemplo: `samples/jobs/arguments/Run transformation.ktr` (campos) + por defecto
```xml
<header>Y</header>
<footer>N</footer>
<encoding/>
<append>N</append>
<add_to_result_filenames>Y</add_to_result_filenames>
<file>
  <name>${Internal.Transformation.Filename.Directory}/output/salida</name>
  <extention>xls</extention>
  <sheetname>Sheet1</sheetname>
  ...                                <!-- resto como en steps/ExcelOutput.xml -->
</file>
<template><enabled>N</enabled><append>N</append><filename>template.xls</filename></template>
<fields>
  <field><name>One</name><type>String</type><format/></field>
</fields>
<custom>...</custom>                 <!-- fuentes/colores: copiar del XML por defecto -->
```

### Table output — `TableOutput`
INSERT masivo en una tabla.
Ejemplo: `samples/transformations/Getting Started Transformation.ktr`
```xml
<connection>SampleDataAdmin.H2</connection>
<schema/>
<table>SALES_DATA</table>
<commit>1000</commit>
<truncate>Y</truncate>               <!-- TRUNCATE antes de insertar -->
<ignore_errors>N</ignore_errors>
<use_batch>Y</use_batch>
<specify_fields>N</specify_fields>
<partitioning_enabled>N</partitioning_enabled>
<partitioning_field/>
<partitioning_daily>N</partitioning_daily>
<partitioning_monthly>Y</partitioning_monthly>
<tablename_in_field>N</tablename_in_field>
<tablename_field/>
<tablename_in_table>Y</tablename_in_table>
<return_keys>N</return_keys>
<return_field/>
<fields>
</fields>
```
Con mapeo explícito (`<specify_fields>Y</specify_fields>`):
```xml
<fields>
  <field><column_name>CUSTOMER_ID</column_name><stream_name>id</stream_name></field>
</fields>
```
**Trampas.** Con `<specify_fields>N</specify_fields>` los **nombres del stream deben coincidir** con las columnas
(y no puede haber campos de más). La tabla **debe existir** (el SQL de creación lo genera Spoon con el botón «SQL»;
en un ETL automático usar `ExecSQL` antes o el job entry `SQL`). Solo inserta: para upsert, `InsertUpdate`.

### Insert / update — `InsertUpdate`
Upsert: busca por clave; si no existe inserta, si existe actualiza los campos marcados.
Ejemplo: `samples/transformations/User Defined Java Class - Real-time search on Twitter.ktr`
```xml
<connection>Sampledata</connection>
<commit>100</commit>
<update_bypassed>N</update_bypassed>   <!-- Y = solo inserta, nunca actualiza -->
<lookup>
  <schema/>
  <table>pentaho_tweets</table>
  <key>
    <name>id</name>                    <!-- campo del stream -->
    <field>id</field>                  <!-- columna de la tabla -->
    <condition>=</condition>
    <name2/>                           <!-- 2º campo para BETWEEN -->
  </key>
  <value>
    <name>id</name>                    <!-- columna de la tabla -->
    <rename>id</rename>                <!-- campo del stream -->
    <update>N</update>                 <!-- N = solo en el INSERT -->
  </value>
  <value>
    <name>tweet</name>
    <rename>tweet</rename>
    <update>Y</update>
  </value>
</lookup>
```
**Trampas.** Ojo al sentido de `<name>`: en `<key>` es el **campo del stream** y `<field>` la columna; en
`<value>` es al revés (`<name>` = columna, `<rename>` = campo del stream). Las columnas clave también deben estar
en `<value>` (con `<update>N</update>`) para que se inserten. Es lento (una consulta por fila): sin índice en
la clave, se arrastra.

### Update — `Update`
Solo UPDATE por clave (no inserta).
Ejemplo: `samples/jobs/changelog-groups/process per group.ktr`
```xml
<connection>SampleDataAdmin.H2</connection>
<skip_lookup>N</skip_lookup>
<commit>100</commit>
<use_batch>N</use_batch>
<error_ignored>N</error_ignored>      <!-- Y = no fallar si la clave no existe -->
<ignore_flag_field/>
<lookup>
  <schema/>
  <table>CHANGELOG</table>
  <key><name>ID</name><field>ID</field><condition>=</condition><name2/></key>
  <value><name>FOO</name><rename>FOO</rename></value>
</lookup>
```
**Trampa.** Por defecto **falla** si no encuentra la fila (`<error_ignored>N</error_ignored>`). `<value>` no
lleva `<update>` (todos se actualizan).

### JSON output — `JsonOutput`
Genera JSON (a fichero, a un campo, o ambos).
Ejemplo: `samples/transformations/Servlet Data Example.ktr` (acortado)
```xml
<outputValue>outputValue</outputValue>  <!-- nombre del campo de salida si operation_type la usa -->
<jsonBloc>data</jsonBloc>               <!-- nombre del array raíz: {"data":[...]} -->
<nrRowsInBloc>500</nrRowsInBloc>        <!-- filas por bloque; 0 = todas en uno -->
<operation_type>both</operation_type>   <!-- writetofile | outputvalue | both -->
<compatibility_mode>N</compatibility_mode>
<encoding>UTF-8</encoding>
<addtoresult>N</addtoresult>
<file>
  <name>${Internal.Transformation.Filename.Directory}/output/datos</name>
  <extention>json</extention>
  <append>N</append>
  <split>N</split>
  <haspartno>N</haspartno>
  <add_date>N</add_date>
  <add_time>N</add_time>
  <create_parent_folder>Y</create_parent_folder>
  <DoNotOpenNewFileInit>N</DoNotOpenNewFileInit>
  <servlet_output>N</servlet_output>
</file>
<fields>
  <field><name>id</name><element>id</element></field>
  <field><name>name</name><element>nombre</element></field>
</fields>
```
**Trampas.** Solo produce estructura **plana** `{"bloc":[{...},{...}]}`; anidar requiere JS o varios pasos.
Con `<nrRowsInBloc>` > 0 escribe varios bloques en el mismo fichero (JSON no válido si hay más de uno): usar `0`
para un único array. `<element>` es la clave en el JSON; `<name>` el campo del stream.

### Write to log — `WriteToLog`
Escribe campos/mensaje en el log (depuración).
Ejemplo: `samples/transformations/real-time-streaming/SingleThreaderTestOverlappingSub.ktr`
```xml
<loglevel>log_level_basic</loglevel>   <!-- log_level_nothing|error|minimal|basic|detailed|debug|rowlevel -->
<displayHeader>N</displayHeader>
<logmessage>Fila retrasada</logmessage>
<fields>
  <field><name>counter</name></field>
</fields>
```
**Trampa.** Si el nivel del step es más detallado que el de la ejecución (p.ej. `log_level_detailed` y Pan con
`-level=Basic`) no se ve nada. Con `<fields>` vacío loguea todos los campos.

---

## Transformar

### Add constants — `Constant`
Añade campos con valor fijo.
Ejemplo: `samples/transformations/XML Input Stream (StAX) Test 2 - Element Blocks.ktr`
```xml
<fields>
  <field>
    <name>item_type</name>
    <type>String</type>
    <format/><currency/><decimal/><group/>
    <nullif>del</nullif>               <!-- el VALOR va en <nullif>, como en RowGenerator -->
    <length>-1</length>
    <precision>-1</precision>
    <set_empty_string>N</set_empty_string>
  </field>
</fields>
```
**Trampa.** No sustituye variables: `${VAR}` queda literal → usar `GetVariable`.

### Add sequence — `Sequence`
Añade un contador (o una secuencia de BD).
Ejemplo: `samples/transformations/transformation-executor/trans-executor-parent.ktr`
```xml
<valuename>test_id</valuename>
<use_database>N</use_database>
<connection/>
<schema/>
<seqname>SEQ_</seqname>
<use_counter>Y</use_counter>
<counter_name/>
<start_at>1</start_at>
<increment_by>1</increment_by>
<max_value>999999999</max_value>
```
**Trampa.** Con varias copias del step (`<copies>` > 1) cada copia cuenta por su lado (ids repetidos) salvo que
compartan `<counter_name>`. El valor es `Integer`.

### Calculator — `Calculator`
Cálculos predefinidos entre hasta 3 campos (A, B, C): aritmética, fechas, strings, hashes.
Ejemplo: `samples/transformations/Rounding.ktr`
```xml
<calculation>
  <field_name>precio_iva</field_name>
  <calc_type>MULTIPLY</calc_type>
  <field_a>precio</field_a>
  <field_b>factor_iva</field_b>
  <field_c/>
  <value_type>Number</value_type>
  <value_length>-1</value_length>
  <value_precision>2</value_precision>
  <remove>N</remove>                  <!-- Y = campo temporal, no sale del step -->
  <conversion_mask/>
  <decimal_symbol/>
  <grouping_symbol/>
  <currency_symbol/>
</calculation>
<calculation>
  <field_name>anio</field_name>
  <calc_type>YEAR_OF_DATE</calc_type>
  <field_a>fecha</field_a>
  <field_b/><field_c/>
  <value_type>Integer</value_type>
  <value_length>-1</value_length>
  <value_precision>-1</value_precision>
  <remove>N</remove>
  <conversion_mask/>
</calculation>
```
Cada cálculo es un `<calculation>` **directamente** dentro del fragmento (no hay `<fields>` envolvente).
Códigos de `<calc_type>` útiles (verificados en `CalculatorMetaFunction`): `CONSTANT`, `COPY_FIELD`, `ADD`,
`SUBTRACT`, `MULTIPLY`, `DIVIDE`, `SQUARE`, `SQUARE_ROOT`, `PERCENT_1` (A/B*100), `PERCENT_2` (A-A*B/100),
`PERCENT_3` (A+A*B/100), `ADD3`, `ABS`, `CEIL`, `FLOOR`, `ROUND_1`, `ROUND_2` (A con B decimales),
`ROUND_STD_1`, `ROUND_STD_2`, `REMAINDER`, `NVL` (A si no es null, si no B), `ADD_DAYS`, `ADD_MONTHS`,
`ADD_HOURS`, `DATE_DIFF` (días B-A), `YEAR_OF_DATE`, `MONTH_OF_DATE`, `DAY_OF_MONTH`, `DAY_OF_WEEK`,
`DAY_OF_YEAR`, `WEEK_OF_YEAR`, `QUARTER_OF_DATE`, `REMOVE_TIME_FROM_DATE`, `UPPER_CASE`, `LOWER_CASE`,
`INIT_CAP`, `STRING_LEN`, `GET_ONLY_DIGITS`, `REMOVE_DIGITS`, `MD5`, `SHA1`, `CRC32`, `LEVENSHTEIN_DISTANCE`.
**Trampas.** Un cálculo puede usar el resultado de uno anterior del mismo step (orden importa). Los tipos de A y B
deben ser compatibles (`DIVIDE` de dos Integer da Integer: truncado → poner `<value_type>Number</value_type>`).
`CONSTANT` toma el valor de `<field_a>`. Para expresiones libres: `Formula` o `Janino`.

### Formula — `Formula`
Expresiones estilo hoja de cálculo (LibFormula / OpenFormula).
Estructura (por defecto + `FormulaMetaFunction`; no hay samples):
```xml
<formula>
  <field_name>margen</field_name>
  <formula_string>IF([ventas] = 0; 0; ([ventas] - [coste]) / [ventas])</formula_string>
  <value_type>Number</value_type>
  <value_length>-1</value_length>
  <value_precision>-1</value_precision>
  <replace_field/>                     <!-- nombre de un campo existente a sobreescribir -->
</formula>
```
**Trampas.** Campos entre corchetes `[campo]`; separador de argumentos **`;`** (no coma); strings con comillas
dobles; concatenar con `&`. Un `<formula>` por campo, directamente en el fragmento. Más lento que Calculator;
para lógica Java usar `Janino`.

### User defined Java expression — `Janino`
Expresión Java compilada por campo (rápida, tipada).
Ejemplo: `samples/transformations/XML Input Stream (StAX) Test 3 - Attribute Groups.ktr`
```xml
<formula>
  <field_name>group_element_id</field_name>
  <formula_string>(xml_data_name.equals("ID")) ? xml_element_id : xml_parent_element_id</formula_string>
  <value_type>Integer</value_type>
  <value_length>-1</value_length>
  <value_precision>-1</value_precision>
  <replace_field/>
</formula>
```
**Trampas.** Los campos se usan por nombre como variables Java (`String`, `Long` para Integer, `Double` para Number,
`java.util.Date`, `BigDecimal`). Integer de Kettle = `Long`: `edad + 1` da `long`, y el `<value_type>` debe casar
con el tipo Java que devuelve la expresión. Nulls: comprobar `campo == null` antes de usar. Comparar strings con
`.equals`, no `==`. Escapar `<`, `&&` → `&lt;`, `&amp;&amp;`.

### Select values — `SelectValues`
Seleccionar/renombrar/reordenar campos (pestaña 1), quitar campos (2), cambiar tipo/formato (3, «Meta-data»).
Ejemplos: `samples/transformations/XML Input Stream (StAX) Test 2 - Element Blocks.ktr` y
`samples/transformations/Denormaliser - capturing last state timestamp.ktr`
```xml
<fields>
  <field>
    <name>xml_data_value</name>
    <rename>item_product_id</rename>
    <length>-2</length>
    <precision>-2</precision>
  </field>
  <field>
    <name>item_type</name>
  </field>
  <select_unspecified>N</select_unspecified>   <!-- Y = mantener también los no listados -->
  <remove>
    <name>group</name>
  </remove>
  <meta>
    <name>fecha_txt</name>
    <rename>fecha</rename>
    <type>Date</type>
    <length>-2</length>
    <precision>-2</precision>
    <conversion_mask>dd/MM/yyyy</conversion_mask>
    <date_format_lenient>false</date_format_lenient>
    <date_format_locale/>
    <date_format_timezone/>
    <lenient_string_to_number>false</lenient_string_to_number>
    <encoding/>
    <decimal_symbol/>
    <grouping_symbol/>
    <currency_symbol/>
    <storage_type/>
  </meta>
</fields>
```
**Trampas.** `<field>`, `<select_unspecified>`, `<remove>` y `<meta>` van **todos dentro de `<fields>`**.
Si hay algún `<field>`, **solo salen esos** (en ese orden) salvo `<select_unspecified>Y`. `-2` = no cambiar
longitud/precisión. En `<meta>` el `<name>` es el nombre **tras** el renombrado de la pestaña 1 si se usaron
ambas en el mismo step (mejor usar un step por operación). Cambiar `<type>` en `<meta>` convierte el valor
(String→Date usa `<conversion_mask>`).

### Sort rows — `SortRows`
Ordena el stream (en memoria + ficheros temporales).
Ejemplo: `samples/transformations/Analytic Query - Random Value Example.ktr`
```xml
<directory>%%java.io.tmpdir%%</directory>
<prefix>out</prefix>
<sort_size>1000000</sort_size>
<free_memory/>
<compress>N</compress>
<compress_variable/>
<unique_rows>N</unique_rows>          <!-- Y = además elimina duplicados por la clave -->
<fields>
  <field>
    <name>groupseq</name>
    <ascending>Y</ascending>
    <case_sensitive>N</case_sensitive>
    <collator_enabled>N</collator_enabled>
    <collator_strength>0</collator_strength>
    <presorted>N</presorted>
  </field>
</fields>
```
**Trampas.** Es un step **bloqueante** (no emite hasta leer todo). Requisito previo de `MergeJoin`, `MergeRows`,
`GroupBy`, `Unique`, `Denormaliser`: hay que ordenar **por las mismas claves y en el mismo orden**.
`%%java.io.tmpdir%%` es sintaxis de variable de sistema válida (no tocar).

### Unique rows — `Unique`
Elimina duplicados **consecutivos** (entrada ordenada).
Ejemplo: `samples/transformations/DistinctCount.ktr`
```xml
<count_rows>N</count_rows>
<count_field/>
<reject_duplicate_row>N</reject_duplicate_row>
<error_description/>
<fields>
  <field>
    <name>v1</name>
    <case_insensitive>N</case_insensitive>
  </field>
</fields>
```
**Trampas.** **Requiere entrada ordenada** por esos campos (si no, solo quita repetidos contiguos). Con `<fields>`
vacío compara la fila entera. Sin ordenar: `UniqueRowsByHashSet` (mismo esquema de `<fields><field><name>`, usa memoria).

### Group by — `GroupBy`
Agregaciones por grupo (SUM, COUNT, AVG…).
Ejemplo: `samples/transformations/Calculate median and percentiles using the group by steps.ktr` +
`samples/transformations/real-time-streaming/Twitter JSON parsing and hashtag aggregation.ktr`
```xml
<all_rows>N</all_rows>
<ignore_aggregate>N</ignore_aggregate>
<field_ignore/>
<directory>%%java.io.tmpdir%%</directory>
<prefix>grp</prefix>
<add_linenr>N</add_linenr>
<linenr_fieldname/>
<give_back_row>N</give_back_row>
<group>
  <field><name>provincia</name></field>
</group>
<fields>
  <field>
    <aggregate>total_ventas</aggregate>  <!-- nombre del campo resultado -->
    <subject>importe</subject>           <!-- campo sobre el que se agrega -->
    <type>SUM</type>
    <valuefield/>                         <!-- separador para CONCAT_STRING, percentil para PERCENTILE -->
  </field>
  <field>
    <aggregate>n</aggregate>
    <subject>id</subject>
    <type>COUNT_ALL</type>
    <valuefield/>
  </field>
</fields>
```
Códigos `<type>`: `SUM`, `AVERAGE`, `MEDIAN`, `PERCENTILE`, `MIN`, `MAX`, `COUNT_ALL`, `COUNT_DISTINCT`, `COUNT_ANY`,
`CONCAT_COMMA`, `CONCAT_STRING`, `FIRST`, `LAST`, `FIRST_INCL_NULL`, `LAST_INCL_NULL`, `CUM_SUM`, `CUM_AVG`,
`STD_DEV`, `STD_DEV_SAMPLE`, `PERCENTILE_NEAREST_RANK`.
**Trampas.** **Requiere entrada ordenada por los campos de `<group>`** (pon un `SortRows` antes); si no, salen
grupos repetidos sin error. Ojo: `<type>` aquí es la agregación, no el tipo de dato. `COUNT_ALL` cuenta filas
con valor no nulo en `<subject>` (`COUNT_ANY` cuenta todas). Sin `<group>` → un único total.

### Memory group by — `MemoryGroupBy`
Igual que Group by pero con hash en memoria: **no requiere orden**.
Ejemplo: `samples/transformations/real-time-streaming/Twitter JSON parsing and hashtag aggregation.ktr`
```xml
<give_back_row>N</give_back_row>
<group>
  <field><name>hashtag</name></field>
</group>
<fields>
  <field>
    <aggregate>count</aggregate>
    <subject>id</subject>
    <type>COUNT_ALL</type>
    <valuefield/>
  </field>
  <field>
    <aggregate>date_from</aggregate>
    <subject>created_at</subject>
    <type>MIN</type>
    <valuefield/>
  </field>
</fields>
```
**Trampas.** Mismos códigos que `GroupBy` menos `CUM_SUM`, `CUM_AVG`, `STD_DEV_SAMPLE`, `PERCENTILE_NEAREST_RANK`.
Todo el resultado en RAM; el orden de salida no está garantizado.

### Value mapper — `ValueMapper`
Mapea valores de un campo a otros (diccionario fijo).
Ejemplo: `samples/transformations/Automatic Documentation Output - Generate Kettle HTML Documenation.ktr`
```xml
<field_to_use>extension</field_to_use>
<target_field>FileType</target_field>  <!-- vacío = sobrescribe field_to_use -->
<non_match_default/>                   <!-- valor si no hay coincidencia (vacío = deja el original / null) -->
<fields>
  <field><source_value>ktr</source_value><target_value>Transformation</target_value></field>
  <field><source_value>kjb</source_value><target_value>Job</target_value></field>
</fields>
```
**Trampa.** Solo strings; para mapear `null` usar `<source_value/>` vacío.

### String operations — `StringOperations`
Trim, mayúsculas/minúsculas, padding, InitCap, quitar dígitos/caracteres especiales, escapes.
Estructura (vía API, sin samples):
```xml
<fields>
  <field>
    <in_stream_name>nombre</in_stream_name>
    <out_stream_name/>                    <!-- vacío = sobrescribe el campo -->
    <trim_type>both</trim_type>           <!-- none | left | right | both -->
    <lower_upper>upper</lower_upper>      <!-- none | lower | upper -->
    <padding_type>none</padding_type>     <!-- none | left | right -->
    <pad_char/>
    <pad_len/>
    <init_cap>no</init_cap>               <!-- no | yes -->
    <mask_xml>none</mask_xml>             <!-- none | escapexml | cdata | unescapexml | escapesql | escapehtml | unescapehtml -->
    <digits>none</digits>                 <!-- none | digits_only | remove_digits -->
    <remove_special_characters>none</remove_special_characters> <!-- none | cr | lf | crlf | tab | espace -->
  </field>
</fields>
```
**Trampa.** Los valores son códigos en minúsculas y en inglés (`yes`/`no`, no `Y`/`N`). Solo campos String.

### Replace in string — `ReplaceString`
Buscar/reemplazar (literal o regex) en campos String.
Ejemplo: `samples/transformations/XML Input Stream (StAX) Test 5 - Performance Test Data for Element Blocks.ktr`
```xml
<fields>
  <field>
    <in_stream_name>telefono</in_stream_name>
    <out_stream_name>telefono_limpio</out_stream_name>
    <use_regex>yes</use_regex>
    <replace_string>[^0-9]</replace_string>
    <replace_by_string/>
    <set_empty_string>N</set_empty_string>
    <replace_field_by_string/>            <!-- reemplazar por el valor de otro campo -->
    <whole_word>no</whole_word>
    <case_sensitive>no</case_sensitive>
    <is_unicode>no</is_unicode>
  </field>
</fields>
```
**Trampas.** `yes`/`no` (no `Y`/`N`), salvo `<set_empty_string>` que es `Y`/`N`. Con regex, grupos en el
reemplazo como `$1`. Para borrar: `<replace_by_string/>` vacío.

### Split fields — `FieldSplitter`
Parte un campo en varios por delimitador (columnas nuevas).
Ejemplo: `samples/transformations/Row Normaliser - turn a single row into 3 rows.ktr`
```xml
<splitfield>Field1</splitfield>
<delimiter>;</delimiter>
<enclosure/>
<fields>
  <field>
    <name>FieldI</name>
    <id/>
    <idrem>N</idrem>
    <type>String</type>
    <format/>
    <group/><decimal/><currency/>
    <length>-1</length>
    <precision>-1</precision>
    <nullif/>
    <ifnull/>
    <trimtype>none</trimtype>            <!-- sic: trimtype, sin guion bajo -->
  </field>
</fields>
```
**Trampas.** El campo original **desaparece**. Si hay menos trozos que `<field>`, los sobrantes salen null.
Para partir en **filas** (no columnas) usar `SplitFieldToRows3` («Split field to rows»).

### Concat fields — `ConcatFields`
Concatena campos en uno nuevo (comparte meta con Text file output).
Ejemplo: `samples/jobs/process all tables/Get list of tables.ktr` (acortado: el bloque `<file>` sobra pero debe estar)
```xml
<separator>.</separator>
<enclosure>"</enclosure>
<enclosure_forced>N</enclosure_forced>
...                                     <!-- igual que TextFileOutput: header, format, <file>... -->
<fields>
  <field><name>TABLE_SCHEMA</name><type>String</type><format/><currency/><decimal/><group/><nullif/>
    <trim_type>none</trim_type><length>-1</length><precision>-1</precision></field>
  <field><name>TABLE_NAME</name><type>String</type><format/><currency/><decimal/><group/><nullif/>
    <trim_type>none</trim_type><length>-1</length><precision>-1</precision></field>
</fields>
<ConcatFields>
  <targetFieldName>full_name</targetFieldName>
  <targetFieldLength>0</targetFieldLength>
  <removeSelectedFields>N</removeSelectedFields>
</ConcatFields>
```
**Trampas.** El nombre del campo resultado está en el bloque final `<ConcatFields><targetFieldName>`. Con
`<enclosure>"</enclosure>` y `<enclosure_forced>N` solo entrecomilla si el valor contiene el separador.
Alternativa simple: `Formula` con `[a] & " " & [b]` o Calculator `ADD3`/JS.

### If field value is null — `IfNull`
Reemplaza nulls por un valor, para todos los campos, por tipo o por campo.
Estructura (por defecto + API):
```xml
<replaceAllByValue/>                   <!-- valor para TODOS los campos (si selectFields=N y selectValuesType=N) -->
<replaceAllMask/>
<selectFields>Y</selectFields>         <!-- Y = usar la lista <fields> -->
<selectValuesType>N</selectValuesType> <!-- Y = usar la lista <valuetypes> -->
<setEmptyStringAll>N</setEmptyStringAll>
<valuetypes>
  <valuetype><name>Integer</name><value>0</value><mask/><set_type_empty_string>N</set_type_empty_string></valuetype>
</valuetypes>
<fields>
  <field><name>provincia</name><value>DESCONOCIDA</value><mask/><set_empty_string>N</set_empty_string></field>
</fields>
```
**Trampa.** Los flags deciden qué lista se usa; si `selectFields` y `selectValuesType` son `N`, solo aplica
`replaceAllByValue`. En fechas, `<mask>` es el formato del valor.

### Row normaliser — `Normaliser`
Columnas → filas (unpivot): `q1,q2,q3,q4` → 4 filas con `quarter` y `revenue`.
Ejemplo: `samples/transformations/Row Normalizer - Simple example.ktr`
```xml
<typefield>quarter</typefield>         <!-- campo nuevo con el "tipo" (valor de <value>) -->
<fields>
  <field><name>q1</name><value>1</value><norm>revenue</norm></field>
  <field><name>q2</name><value>2</value><norm>revenue</norm></field>
  <field><name>q3</name><value>3</value><norm>revenue</norm></field>
  <field><name>q4</name><value>4</value><norm>revenue</norm></field>
</fields>
```
**Trampa.** Id `Normaliser` (con *s*, ortografía británica). Campos con el mismo `<norm>` deben tener tipo compatible.

### Row denormaliser — `Denormaliser`
Filas → columnas (pivot) por una clave.
Ejemplo: `samples/transformations/General - parsing coded data.ktr`
```xml
<key_field>code</key_field>            <!-- campo cuyo valor decide la columna destino -->
<group>
  <field><name>id</name></field>
</group>
<fields>
  <field>
    <field_name>value</field_name>     <!-- campo con el valor -->
    <key_value>1</key_value>           <!-- cuando key_field = 1 ... -->
    <target_name>colour</target_name>  <!-- ... va a la columna colour -->
    <target_type>String</target_type>
    <target_format/>
    <target_length>-1</target_length>
    <target_precision>-1</target_precision>
    <target_decimal_symbol/>
    <target_grouping_symbol/>
    <target_currency_symbol/>
    <target_null_string/>
    <target_aggregation_type>-</target_aggregation_type>  <!-- - | SUM | AVERAGE | MIN | MAX | COUNT_ALL | CONCAT_COMMA -->
  </field>
</fields>
```
**Trampa.** **Requiere entrada ordenada por los campos de `<group>`** (igual que GroupBy). Si hay varias filas
con la misma clave en un grupo, sin agregación se queda con la última.

---

## Flujo

### Filter rows — `FilterRows`
Divide el stream según una condición: true → un step, false → otro.
Ejemplo: `samples/transformations/XML Input Stream (StAX) Test 2 - Element Blocks.ktr` (condición simple)
```xml
<send_true_to>Filas válidas</send_true_to>
<send_false_to>Filas descartadas</send_false_to>
<compare>
  <condition>
    <negated>N</negated>
    <leftvalue>xml_data_name</leftvalue>
    <function>=</function>
    <rightvalue/>                       <!-- nombre de OTRO campo para comparar campo con campo -->
    <value>                             <!-- o una constante -->
      <name>constant</name>
      <type>String</type>
      <text>Value</text>
      <length>-1</length>
      <precision>-1</precision>
      <isnull>N</isnull>
      <mask/>
    </value>
  </condition>
</compare>
```
Condición compuesta — `samples/transformations/HL7_test_multiple_files.ktr`:
```xml
<compare>
  <condition>
    <negated>N</negated>
    <conditions>
      <condition>
        <negated>N</negated>
        <leftvalue>Value</leftvalue>
        <function>IS NOT NULL</function>
        <rightvalue/>
      </condition>
      <condition>
        <negated>N</negated>
        <operator>AND</operator>        <!-- desde la 2ª subcondición: AND | OR | AND NOT | OR NOT | XOR -->
        <leftvalue>ParentGroup</leftvalue>
        <function>=</function>
        <rightvalue/>
        <value><name>constant</name><type>String</type><text>ADT_A01</text>
          <length>-1</length><precision>-1</precision><isnull>N</isnull><mask/></value>
      </condition>
    </conditions>
  </condition>
</compare>
```
`<function>`: `=`, `<>`, `<`, `<=`, `>`, `>=`, `REGEXP`, `IS NULL`, `IS NOT NULL`, `IN LIST`, `CONTAINS`,
`STARTS WITH`, `ENDS WITH`, `LIKE`, `TRUE`. (Spoon los escribe escapados: `&#x3d;`, `&#x3c;&#x3e;`…)
**Trampas.** Los destinos deben ser **nombres exactos de steps** y además existir el hop `FilterRows → destino`
para cada uno (en Spoon se ven como hops verde/rojo). Con ambos vacíos, solo pasan las filas *true* a todos los
hops de salida. Si pones solo `<send_true_to>`, las false se descartan. La constante: el tipo en
`<value><type>` debe ser compatible con el campo (comparar Integer con `<type>String</type>` da resultados raros);
para números usar `<type>Integer</type>` / `Number`, para fechas `Date` + `<mask>yyyy/MM/dd HH:mm:ss.SSS</mask>`.
`<` en `<function>` debe escribirse `&lt;`.

### Switch / case — `SwitchCase`
Enruta cada fila a un step según el valor de un campo (varios destinos).
Ejemplo: `samples/transformations/Switch-Case - substring example.ktr`
```xml
<fieldname>text</fieldname>
<use_contains>Y</use_contains>          <!-- Y = "contiene", N = igualdad -->
<case_value_type>String</case_value_type>
<case_value_format/>
<case_value_decimal/>
<case_value_group/>
<default_target_step>others</default_target_step>
<cases>
  <case><value>world</value><target_step>world</target_step></case>
  <case><value>hello</value><target_step>hello</target_step></case>
</cases>
```
**Trampas.** Cada `<target_step>` y el `<default_target_step>` necesitan su hop desde el Switch/case. Sin default,
las filas que no casan se pierden. Varios valores pueden ir al mismo destino (un `<case>` por valor).

### Append streams — `Append`
Concatena dos streams: primero todas las filas de *head*, luego las de *tail*.
Ejemplo: `samples/transformations/XML Input Stream (StAX) Test 5 - Performance Test Data for Element Blocks.ktr`
```xml
<head_name>Append streams 2</head_name>
<tail_name>Select values 2</tail_name>
```
**Trampas.** Ambos streams deben tener **la misma estructura** (mismos campos, mismo orden y tipos) y deben
existir los dos hops. Para unir más de dos, o sin importar el orden, basta con apuntar varios hops a un mismo step
(p.ej. un `Dummy`): Kettle mezcla las entradas (también exige estructura idéntica).

### Dummy (do nothing) — `Dummy`
No hace nada: punto de unión, final de rama o marcador.
Ejemplo: `samples/transformations/Get File Names - Remove extension.ktr` — fragmento **vacío** (solo envoltorio).

### Abort — `Abort`
Aborta la transformación cuando le llegan filas (validaciones).
Ejemplo: `samples/transformations/Rounding.ktr` + por defecto 9.4
```xml
<row_threshold>0</row_threshold>       <!-- abortar al recibir más de N filas -->
<message>Valor no esperado</message>
<always_log_rows>Y</always_log_rows>
<abort_option>ABORT_WITH_ERROR</abort_option>  <!-- ABORT | ABORT_WITH_ERROR | SAFE_STOP -->
```
**Trampa.** `ABORT` (el valor por defecto) detiene la transformación pero no suma errores; para que el job
la trate como fallida (y siga por el hop de error) usar `ABORT_WITH_ERROR`. `<abort_option>` no aparece en los samples (son anteriores a 9.x).

---

## Búsquedas y uniones

### Stream lookup — `StreamLookup`
Enriquece el stream principal buscando en otro stream cargado en memoria (join «lookup» en memoria).
Ejemplo: `samples/transformations/Stream lookup - basics.ktr`
```xml
<from>Tab B</from>                      <!-- step que provee la tabla de lookup (info stream) -->
<input_sorted>N</input_sorted>
<preserve_memory>N</preserve_memory>
<sorted_list>N</sorted_list>
<integer_pair>N</integer_pair>
<lookup>
  <key>
    <name>Mandt</name>                  <!-- campo del stream PRINCIPAL -->
    <field>B_Mandt</field>              <!-- campo del stream de LOOKUP -->
  </key>
  <value>
    <name>B_Zusatz</name>               <!-- campo del lookup a traer -->
    <rename>B_Zusatz</rename>           <!-- nombre en la salida -->
    <default/>                          <!-- valor si no hay match -->
    <type>String</type>
  </value>
</lookup>
```
**Trampas.** El step de lookup se indica en **`<from>`** (no existe `<lookup_step>`), y además hace falta el hop
`Tab B → Stream lookup`; el stream principal es el otro hop de entrada. Si hay varias coincidencias en el lookup
se queda con una sola (no multiplica filas). Sin match → campos con `<default>` o null (no filtra filas: para inner join filtrar
después por null). Todo el stream de lookup va a memoria.

### Database lookup — `DBLookup`
Busca valores en una tabla de BD por cada fila (con caché opcional).
Estructura (por defecto + API; no hay samples):
```xml
<connection>dwh</connection>
<cache>Y</cache>
<cache_load_all>N</cache_load_all>
<cache_size>5000</cache_size>
<lookup>
  <schema/>
  <table>dim_cliente</table>
  <orderby/>
  <fail_on_multiple>N</fail_on_multiple>
  <eat_row_on_failure>N</eat_row_on_failure>  <!-- Y = descarta filas sin match (inner join) -->
  <key>
    <name>id_cliente</name>             <!-- campo del stream -->
    <field>cliente_id</field>           <!-- columna de la tabla -->
    <condition>=</condition>            <!-- = | <> | < | <= | > | >= | LIKE | BETWEEN | IS NULL | IS NOT NULL -->
    <name2/>
  </key>
  <value>
    <name>sk_cliente</name>             <!-- columna a devolver -->
    <rename>sk_cliente</rename>         <!-- nombre en el stream -->
    <default>-1</default>
    <type>Integer</type>
  </value>
</lookup>
```
**Trampa.** Aquí `<name>`/`<field>` del `<key>` siguen la misma convención que InsertUpdate (stream / tabla), y en
`<value>` `<name>` es la columna. Sin índice en la tabla, lento. El texto por defecto de `<table>` depende del
locale («lookup table» / «tabla de búsqueda»): siempre sobrescribirlo.

### Merge join — `MergeJoin`
Join clásico (INNER, LEFT/RIGHT/FULL OUTER) entre dos streams **ordenados**.
Ejemplo: `samples/transformations/Merge Join - Join 2 sorted data sets.ktr`
```xml
<join_type>FULL OUTER</join_type>       <!-- INNER | LEFT OUTER | RIGHT OUTER | FULL OUTER -->
<step1>Text file input</step1>          <!-- stream izquierdo -->
<step2>Text file input 2</step2>        <!-- stream derecho -->
<keys_1><key>pid</key></keys_1>
<keys_2><key>pid</key></keys_2>
```
**Trampas.** **Ambas entradas deben estar ordenadas por las claves** (mismo orden, ascendente) — sin `SortRows`
previo da resultados incorrectos **sin error**. Hops desde `step1` y `step2`. Si los dos streams tienen campos con
el mismo nombre, el del derecho se renombra con sufijo `_1`. Claves emparejadas por posición en `keys_1`/`keys_2`.

### Merge rows (diff) — `MergeRows`
Compara un stream de referencia con uno nuevo y marca cada fila: `identical`, `changed`, `new`, `deleted`.
Ejemplo: `samples/transformations/Merge rows - mergs 2 streams of data and add a flag.ktr`
```xml
<keys><key>pid</key></keys>
<values><value>value</value></values>
<flag_field>flagfield</flag_field>
<reference>Text file input</reference>   <!-- stream "viejo" -->
<compare>Text file input 2</compare>     <!-- stream "nuevo" -->
<compare>
</compare>
```
**Trampas.** Sí: hay **dos** tags `<compare>`; el primero es el nombre del step a comparar, el segundo (vacío)
es un bloque de condición heredado. Ambas entradas **ordenadas por las claves** y con la misma estructura.
Suele seguirse de `SwitchCase` sobre `flagfield` o de `SynchronizeAfterMerge`.

### Join rows (cartesian product) — `JoinRows`
Producto cartesiano de todas las entradas, con condición opcional.
Ejemplo: `samples/transformations/Calculate median and percentiles using the group by steps.ktr`
```xml
<directory>%%java.io.tmpdir%%</directory>
<prefix>out</prefix>
<cache_size>500</cache_size>
<main>group</main>                      <!-- step "principal" (se lee en streaming; los otros se cachean) -->
<compare>
  <condition>
    <negated>N</negated>
    <leftvalue/>
    <function>=</function>
    <rightvalue/>
  </condition>
</compare>
```
**Trampas.** Uso típico: añadir a cada fila un único valor agregado (total, máximo…) que viene de otra rama de 1
fila. Con streams grandes explota (N×M). La condición vacía (como arriba) = sin filtro.

---

## Scripting y BD

### Modified Java Script Value — `ScriptValueMod`
JavaScript (Rhino) por fila; muy usado en samples (55).
Ejemplo: `samples/transformations/XML Join - Create a multilayer XML file.ktr`
```xml
<compatible>N</compatible>
<optimizationLevel>9</optimizationLevel>
<jsScripts>
  <jsScript>
    <jsScript_type>0</jsScript_type>    <!-- 0 = script de transformación (por fila), 1 = start, 2 = end -->
    <jsScript_name>Script 1</jsScript_name>
    <jsScript_script>var nombre_completo = nombre + " " + apellido;
var es_mayor = edad &gt;= 18;</jsScript_script>
  </jsScript>
</jsScripts>
<fields>
  <field>
    <name>nombre_completo</name>        <!-- variable JS -->
    <rename>nombre_completo</rename>    <!-- campo de salida -->
    <type>String</type>
    <length>-1</length>
    <precision>-1</precision>
    <replace>N</replace>                <!-- Y = sobrescribe el campo existente con ese nombre -->
  </field>
</fields>
```
**Trampas.** Las variables nuevas solo salen si están en `<fields>`. `<compatible>Y</compatible>` es el modo viejo
(`campo.getString()`, como en el sample); con `N` los campos son valores JS directos. Integer de Kettle llega como
número JS (double): para `<type>Integer</type>` se trunca. Escapar `<`, `&&` en el XML. Lento: preferir
Calculator/Formula/Janino cuando alcancen.

### Execute SQL script — `ExecSQL`
Ejecuta SQL en la inicialización (DDL, truncates) o una vez por fila.
Ejemplo: `samples/transformations/Table Output - Tablename in field.ktr`
```xml
<connection>SampleDataAdmin.H2</connection>
<execute_each_row>N</execute_each_row>
<single_statement>N</single_statement>
<replace_variables>Y</replace_variables>
<quoteString>N</quoteString>
<sql>CREATE TABLE IF NOT EXISTS ventas (id INT, total DECIMAL(10,2));
TRUNCATE TABLE ventas;</sql>
<set_params>N</set_params>
<insert_field/>
<update_field/>
<delete_field/>
<read_field/>
<arguments>
</arguments>
```
**Trampas.** Con `<execute_each_row>N</execute_each_row>` el SQL corre **en la inicialización del step**
(antes de que fluyan filas), pero las inicializaciones de todos los steps van en paralelo: no hay orden entre
varios `ExecSQL` ni frente a otros steps que toquen la BD en su init. Para DDL previo, lo limpio es el job entry
`SQL` antes del `TRANS`. Varias sentencias separadas por `;`.
`<replace_variables>Y` para usar `${VAR}`.

### Dimension lookup/update — `DimensionLookup`
Dimensión lenta (SCD tipo 1/2): busca/inserta/versiona y devuelve la clave técnica.
Ejemplo: `samples/jobs/Slowly Changing Dimension/DimensionLookup - update dimension table 2.ktr`
```xml
<schema/>
<table>D_CUSTOMER</table>
<connection>SampleDataAdmin.H2</connection>
<commit>100</commit>
<update>Y</update>                      <!-- Y = lookup + update; N = solo lookup -->
<fields>
  <key>
    <name>id</name>                     <!-- clave natural en el stream -->
    <lookup>id</lookup>                 <!-- columna en la dimensión -->
  </key>
  <date>
    <name>updated</name>                <!-- campo fecha del stream (vacío = fecha del sistema) -->
    <from>date_from</from>
    <to>date_to</to>
  </date>
  <field>
    <name>Name</name>
    <lookup>Name</lookup>
    <update>Insert</update>             <!-- Insert = SCD2 (nueva versión) | Update = SCD1 | Punch through | LastVersion... -->
  </field>
  <return>
    <name>customer_tk</name>            <!-- clave técnica (surrogate) -->
    <rename/>
    <creation_method>autoinc</creation_method>  <!-- autoinc | tablemax | sequence -->
    <use_autoinc>Y</use_autoinc>
    <version>version</version>
  </return>
</fields>
<sequence/>
<min_year>1900</min_year>
<max_year>2199</max_year>
<cache_size>5000</cache_size>
<preload_cache>N</preload_cache>
<use_start_date_alternative>N</use_start_date_alternative>
<start_date_alternative>none</start_date_alternative>
<start_date_field_name/>
<useBatch>N</useBatch>
```
**Trampas.** Hay **dos** `<update>`: el del nivel del step (modo) y el de cada `<field>` (tipo de cambio). La tabla
necesita las columnas técnicas (`customer_tk`, `version`, `date_from`, `date_to`); Spoon las crea con «SQL».
Con `autoinc` la columna debe ser autoincremental en la BD.

### Combination lookup/update — `CombinationLookup`
Dimensión «junk»/tipo 1 por combinación de claves: devuelve (o crea) la clave técnica.
Ejemplo: `samples/transformations/Combination Lookup - Create new ids.ktr`
```xml
<schema/>
<table>dim_territorio</table>
<connection>dwh</connection>
<commit>100</commit>
<cache_size>9999</cache_size>
<replace>N</replace>                    <!-- Y = quitar los campos clave del stream -->
<preloadCache>N</preloadCache>
<crc>N</crc>
<crcfield>hashcode</crcfield>
<fields>
  <key><name>Territory</name><lookup>Territory</lookup></key>
  <return>
    <name>id</name>
    <creation_method>tablemax</creation_method>
    <use_autoinc>N</use_autoinc>
  </return>
</fields>
<sequence/>
<last_update_field/>
```
**Trampa.** No versiona (no hay fechas): una combinación nueva → fila nueva. `tablemax` no es seguro con
cargas concurrentes.

---

## Variables

### Set variables — `SetVariable`
Convierte un campo de la (única) fila en variable para el job padre/raíz/JVM.
Ejemplo: `samples/jobs/process all tables/set variables.ktr` + por defecto 9.4
```xml
<fields>
  <field>
    <field_name>tablename</field_name>
    <variable_name>TABLENAME</variable_name>
    <variable_type>ROOT_JOB</variable_type>  <!-- JVM | PARENT_JOB | GP_JOB | ROOT_JOB -->
    <default_value/>
  </field>
</fields>
<use_formatting>Y</use_formatting>
```
**Trampas.** Debe recibir **exactamente una fila** (con más, falla). La variable **no** es visible en la misma
transformación que la define (las variables se resuelven al arrancar): usarla en la siguiente transformación del
job. `PARENT_JOB` suele ser lo correcto.

---

## Job entries más usados (`.kjb`)

Los `<entry>` tienen otro envoltorio: `<name>`, `<description>`, `<type>`, `<attributes/>`, fragmento, y al final
`<parallel>N</parallel><draw>Y</draw><nr>0</nr><xloc>..</xloc><yloc>..</yloc><attributes_kjc/>`.

| Entry | `<type>` | Notas |
|---|---|---|
| Start | `SPECIAL` | `<start>Y</start><dummy>N</dummy>` + `<repeat>N</repeat><schedulerType>0</schedulerType>…`. Debe existir exactamente uno. |
| Dummy | `SPECIAL` | `<start>N</start><dummy>Y</dummy>` |
| Transformation | `TRANS` | `<specification_method>filename</specification_method><filename>${Internal.Entry.Current.Directory}/carga.ktr</filename>`… (ver `job-entries/TRANS.xml`) |
| Job | `JOB` | `<specification_method>filename</specification_method><filename>…kjb</filename>` |
| Success | `SUCCESS` | fragmento vacío |
| Abort job | `ABORT` | `<message>` |
| SQL | `SQL` | `<sql>`, `<connection>`, `<useVariableSubstitution>` |
| Set variables | `SET_VARIABLES` | |
| Write to log | `WRITE_TO_LOG` | `<logmessage>`, `<loglevel>` |
| Check if file exists | `FILE_EXISTS` | `<filename>` |

Hops de job (`<hops><hop>`): `<from>`, `<to>`, `<from_nr>0</from_nr>`, `<to_nr>0</to_nr>`, `<enabled>Y</enabled>`,
`<evaluation>Y</evaluation>` (Y = solo si OK, N = solo si falla), `<unconditional>N</unconditional>` (Y = siempre;
el hop que sale de START es incondicional). Ejemplo completo: `samples/jobs/Slowly Changing Dimension/create - populate - update slowly changing dimension.kjb`.

---

## Verificación

- Todos los ids de esta guía existen en `steps.json` (registro de la 9.4 instalada).
- Tags verificados con `grep` sobre `data-integration/samples/**/*.ktr|kjb` o, para steps sin samples
  (`Formula`, `StringOperations`, `IfNull`, `DBLookup`, `TypeExitExcelWriterStep`, campos de `SetVariable`,
  `<abort_option>`, `<is_unicode>`, `<ConcatFields>`), contra el `getXML()` real de la 9.4 vía la API
  (rellenando la meta) o contra las cadenas de la clase `*Meta`.
- Los valores enumerados (`calc_type`, tipos de GroupBy, `spreadsheet_type`, códigos de StringOperations,
  `variable_type`) salen de las constantes de las clases de `kettle-engine-9.4.0.0-343.jar` / `excel-plugins`.
