# 02 · Formato de jobs (.kjb) — PDI 9.4

Un job orquesta: corre transformaciones, otros jobs, chequeos de archivos, SQL, etc., **en secuencia** (una entry por vez, siguiendo hops con condición). No pasa filas entre entries (para eso están los "result rows", fuera de este manual).

Ejemplos verificados con Kitchen: `ejemplos/10_job_secuencia.kjb` (dos `.ktr` en secuencia + rama de error) y `ejemplos/11_job_padre.kjb` (job que llama a otro job + Write to log + parámetro).

---

## 0. Checklist rápido

1. Raíz `<job>`, UTF-8, mismo tratamiento de escapes que en `.ktr` (`&amp;`, `&lt;`).
2. **Tiene que existir una entry llamada exactamente `START`**, de `<type>SPECIAL</type>`, con `<start>Y</start>` **y `<draw>Y</draw>`**. Kitchen la busca por nombre literal y solo entre las entries visibles: si se llama "Inicio", o si le falta `<draw>Y</draw>`, falla con `Couldn't find starting point in this job.` (verificado las dos cosas).
3. Cada entry: `<name>` único, `<type>` (ID del plugin), `<parallel>N</parallel>`, `<draw>Y</draw>`, `<nr>0</nr>`, `<xloc>`, `<yloc>`.
4. Hops en `<hops>` (no en `<order>`), con `from`, `to`, `from_nr`, `to_nr`, `enabled`, `evaluation`, `unconditional`.
5. El hop que sale de START: `<unconditional>Y</unconditional>`. Los demás: `evaluation=Y` (si OK) o `evaluation=N` (si falla).
6. Rutas a `.ktr`/`.kjb`: `${Internal.Entry.Current.Directory}/archivo.ktr`. Una ruta relativa pelada (`01_generar_constantes.ktr`) falla: `The transformation path 01_generar_constantes.ktr is invalid, and will not run successfully.`
7. Si una entry falla y no tiene hop de error, el job termina con error (exit 1 en Kitchen). Para que quede explícito, agregá una rama `evaluation=N` a un `ABORT` (o a un `WRITE_TO_LOG` + `ABORT`).

---

## 1. Esqueleto

### 1.1 Mínimo que Kitchen acepta (verificado)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<job>
  <name>jmin</name>
  <entries>
    <entry><name>START</name><type>SPECIAL</type><start>Y</start><draw>Y</draw><xloc>96</xloc><yloc>112</yloc></entry>
    <entry><name>Correr 01</name><type>TRANS</type><filename>${Internal.Entry.Current.Directory}/01_generar_constantes.ktr</filename><xloc>256</xloc><yloc>112</yloc></entry>
    <entry><name>OK</name><type>SUCCESS</type><xloc>416</xloc><yloc>112</yloc></entry>
  </entries>
  <hops>
    <hop><from>START</from><to>Correr 01</to><unconditional>Y</unconditional></hop>
    <hop><from>Correr 01</from><to>OK</to></hop>
  </hops>
</job>
```

Corre bien, pero las entries sin `draw` quedan ocultas en Spoon. **Usá la plantilla completa.**

### 1.2 Plantilla recomendada

```xml
<?xml version="1.0" encoding="UTF-8"?>
<job>
  <name>mi_job</name>
  <description/>
  <extended_description/>
  <job_version/>
  <job_status>0</job_status>
  <directory>/</directory>
  <created_user>-</created_user>
  <created_date>2026/10/20 10:00:00.000</created_date>
  <modified_user>-</modified_user>
  <modified_date>2026/10/20 10:00:00.000</modified_date>
  <parameters>
  </parameters>
  <slaveservers>
  </slaveservers>
  <pass_batchid>N</pass_batchid>
  <shared_objects_file/>
  <entries>
    <!-- START + entries -->
  </entries>
  <hops>
    <!-- hops -->
  </hops>
  <notepads>
  </notepads>
  <attributes/>
</job>
```

Diferencias con `.ktr`: **no hay `<info>`**; los metadatos van directo bajo `<job>`. Las tablas de log (`job-log-table`, `jobentry-log-table`, `channel-log-table`, `checkpoint-log-table`) que escribe Spoon son omitibles. `<connection>` (mismo formato que en `.ktr`) va antes de `<entries>` si alguna entry SQL la usa. `<notepads>` va **después** de `<hops>` (al revés que en `.ktr`) y con el mismo formato (`heigth` con errata).

---

## 2. `<entry>`: tags comunes

```xml
<entry>
  <name>Generar constantes</name>
  <description/>
  <type>TRANS</type>
  <attributes/>
  <!-- ... tags propios del tipo ... -->
  <parallel>N</parallel>
  <draw>Y</draw>
  <nr>0</nr>
  <xloc>256</xloc>
  <yloc>112</yloc>
  <attributes_kjc/>
</entry>
```

| Tag | Default si falta (código) | Notas |
|---|---|---|
| `name` | — | Único. |
| `type` | — | ID del plugin, case-sensitive. Uno inexistente carga como "missing" y al llegar a esa entry: `Can't run job due to plugin missing` (exit 1). |
| `parallel` | N | `Y` = las entries siguientes arrancan en paralelo. |
| `draw` | **N** | Siempre `Y`. Para START es **obligatorio**. |
| `nr` | 0 | Número de copia. Solo distinto de 0 si la **misma** entry aparece dos veces en el lienzo (p. ej. dos íconos "Success"); en ese caso los hops lo referencian con `from_nr`/`to_nr`. |
| `xloc`, `yloc` | 0 | Misma convención que en `.ktr`: flujo principal en `yloc=112`, `xloc` 96 → 256 → 416 → 576 (paso 160; en los samples de jobs la separación horizontal más común es 160–192); rama de error debajo (`yloc` ≈ 240). |

---

## 3. Tipos de entry más usados

IDs verificados en `kettle-job-entries.xml` (engine) y en las anotaciones de `pdi-core-plugins-impl` (`ABORT`, `ADD_RESULT_FILENAMES`, `CHECK_DB_CONNECTIONS`). Los demás plugins de `plugins/` agregan más.

| GUI | `type` | Notas |
|---|---|---|
| Start | `SPECIAL` + `<start>Y</start>` | Nombre literal `START`. Tags de scheduler: `repeat`, `schedulerType` (0 = sin programación), `intervalSeconds`, `intervalMinutes`, `hour`, `minutes`, `weekDay`, `DayOfMonth`. Con `repeat=N` son decorativos. |
| Dummy | `SPECIAL` + `<start>N</start><dummy>Y</dummy>` | No hace nada. |
| Transformation | `TRANS` | §4. |
| Job | `JOB` | §5. |
| Success | `SUCCESS` | Termina el job en OK. Sin tags propios. |
| Abort job | `ABORT` | `<message>texto</message>`; deja el job en error y escribe el mensaje como ERROR en el log. |
| Write to log | `WRITE_TO_LOG` | `<logmessage>` (sustituye variables), `<loglevel>Basic</loglevel>`, `<logsubject>`. |
| Set variables | `SET_VARIABLES` | `<replacevars>`, `<filename>`, `<file_variable_type>`, y por variable `<variable_name>`, `<variable_value>`, `<variable_type>` (`JVM`, `CURRENT_JOB`, `PARENT_JOB`, `ROOT_JOB`). |
| Evaluate / Simple evaluation | `EVAL` (JavaScript) / `SIMPLE_EVAL` | |
| SQL | `SQL` | Usa una `<connection>` del job. |
| File exists / Create folder / Delete file | `FILE_EXISTS` / `CREATE_FOLDER` / `DELETE_FILE` | |
| Shell | `SHELL` | |
| Otros del engine | `MAIL`, `FTP`, `SFTP`, `HTTP`, `TABLE_EXISTS`, `CREATE_FILE`, `WAIT_FOR_FILE`, `DELAY`, `COPY_FILES`, `MOVE_FILES`, `DELETE_FILES`, `ZIP_FILE`, `UNZIP`, `FILES_EXIST`, `FOLDER_IS_EMPTY`, `COLUMNS_EXIST`, `TRUNCATE_TABLES`, `WAIT_FOR_SQL`, `EVAL_TABLE_CONTENT`, `WRITE_TO_FILE`, `MSGBOX_INFO`, `PING`, … | |

---

## 4. Entry `TRANS` (llamar a una .ktr)

```xml
<entry>
  <name>Generar constantes</name>
  <description/>
  <type>TRANS</type>
  <attributes/>
  <specification_method>filename</specification_method>
  <trans_object_id/>
  <filename>${Internal.Entry.Current.Directory}/01_generar_constantes.ktr</filename>
  <transname/>
  <arg_from_previous>N</arg_from_previous>
  <params_from_previous>N</params_from_previous>
  <exec_per_row>N</exec_per_row>
  <clear_rows>N</clear_rows>
  <clear_files>N</clear_files>
  <set_logfile>N</set_logfile>
  <logfile/>
  <logext/>
  <add_date>N</add_date>
  <add_time>N</add_time>
  <loglevel>Basic</loglevel>
  <cluster>N</cluster>
  <slave_server_name/>
  <set_append_logfile>N</set_append_logfile>
  <wait_until_finished>Y</wait_until_finished>
  <follow_abort_remote>N</follow_abort_remote>
  <create_parent_folder>N</create_parent_folder>
  <logging_remote_work>N</logging_remote_work>
  <run_configuration>Pentaho local</run_configuration>
  <suppress_result_data>N</suppress_result_data>
  <parameters>
    <pass_all_parameters>Y</pass_all_parameters>
    <parameter>
      <name>ARCHIVO_SALIDA</name>   <!-- parámetro declarado en la .ktr -->
      <stream_name/>                <!-- o un campo de result rows -->
      <value>01_desde_job</value>   <!-- valor fijo; acepta ${VAR} -->
    </parameter>
  </parameters>
  <parallel>N</parallel>
  <draw>Y</draw>
  <nr>0</nr>
  <xloc>256</xloc>
  <yloc>112</yloc>
  <attributes_kjc/>
</entry>
```

- `specification_method`: `filename` (archivo) | `rep_name` (repositorio) | `rep_ref`. Si falta y hay `<filename>`, toma el archivo (verificado en el job mínimo). Ponelo igual.
- `filename`: **con `${Internal.Entry.Current.Directory}`** (en un job vale la carpeta del `.kjb`). Los samples viejos usan `${Internal.Job.Filename.Directory}`, que también funciona en jobs (verificado). Una ruta relativa sin variable no funciona.
- `run_configuration`: Spoon 9.x escribe `Pentaho local`; Kitchen loguea `Using run configuration [Pentaho local]`. Si lo omitís también corre local (verificado).
- `wait_until_finished`: `Y`, si no la entry termina "OK" antes que la transformación.
- `loglevel`: nivel de log de la transformación hija (`Nothing`, `Error`, `Minimal`, `Basic`, `Detailed`, `Debug`, `Rowlevel`).
- Parámetros: `pass_all_parameters=Y` pasa los parámetros/variables del job a la ktr; cada `<parameter>` fija uno. El parámetro tiene que estar **declarado** en `<info><parameters>` de la ktr para que se tome por nombre (verificado: `ARCHIVO_SALIDA=01_desde_job` generó `salida/01_desde_job.csv`).
- `exec_per_row=Y` + `arg_from_previous`/`params_from_previous` = loop sobre result rows (no cubierto acá).

Dentro de la `.ktr` llamada, `${Internal.Entry.Current.Directory}` apunta a la carpeta **de la ktr**, no a la del job (verificado con un job en otra carpeta). Así cada archivo resuelve sus rutas relativo a sí mismo.

---

## 5. Entry `JOB` (llamar a otro .kjb)

```xml
<entry>
  <name>Correr job secuencia</name>
  <description/>
  <type>JOB</type>
  <attributes/>
  <specification_method>filename</specification_method>
  <job_object_id/>
  <filename>${Internal.Entry.Current.Directory}/10_job_secuencia.kjb</filename>
  <jobname/>
  <directory/>
  <arg_from_previous>N</arg_from_previous>
  <params_from_previous>N</params_from_previous>
  <exec_per_row>N</exec_per_row>
  <set_logfile>N</set_logfile>
  <logfile/>
  <logext/>
  <add_date>N</add_date>
  <add_time>N</add_time>
  <loglevel>Basic</loglevel>
  <slave_server_name/>
  <wait_until_finished>Y</wait_until_finished>
  <follow_abort_remote>N</follow_abort_remote>
  <expand_remote_job>N</expand_remote_job>
  <create_parent_folder>N</create_parent_folder>
  <pass_export>N</pass_export>
  <run_configuration>Pentaho local</run_configuration>
  <parameters>
    <pass_all_parameters>Y</pass_all_parameters>
  </parameters>
  <set_append_logfile>N</set_append_logfile>
  <parallel>N</parallel>
  <draw>Y</draw>
  <nr>0</nr>
  <xloc>416</xloc>
  <yloc>112</yloc>
  <attributes_kjc/>
</entry>
```

Verificado en `11_job_padre.kjb`. Mismas reglas de ruta y parámetros que TRANS (sin `clear_rows`/`clear_files`/`cluster`).

---

## 6. `<hops>` de un job

```xml
<hop>
  <from>Generar constantes</from>
  <to>Ventas por producto</to>
  <from_nr>0</from_nr>
  <to_nr>0</to_nr>
  <enabled>Y</enabled>
  <evaluation>Y</evaluation>
  <unconditional>N</unconditional>
</hop>
```

| `unconditional` | `evaluation` | Significado | Color en Spoon |
|---|---|---|---|
| `Y` | (se ignora; Spoon pone `Y`) | Siempre sigue | negro (candado) |
| `N` | `Y` | Sigue **si la entry origen terminó OK** | verde |
| `N` | `N` | Sigue **si la entry origen falló** | rojo |

- Defaults si faltan (código): `enabled=Y`, `evaluation=Y`, `unconditional=N`. O sea, un hop pelado es "si OK".
- Desde `START` usá siempre `unconditional=Y` (los samples lo hacen así).
- Distribución en samples: 36 hops `Y/N` (si OK), 19 `Y/Y` (incondicional), 5 `N/N` (si falla).
- Una entry puede tener a la vez un hop verde y uno rojo (patrón del ejemplo 10). Varias entries pueden apuntar a la misma (dos rojos hacia un único `Abortar`).
- Hop a una entry que no existe: no falla al cargar; revienta al ejecutar la entry origen con `java.lang.NullPointerException: Cannot invoke "org.pentaho.di.job.JobHopMeta.isUnconditional()" because "hi" is null` (exit 1).
- `from_nr`/`to_nr`: el `<nr>` de la copia de la entry; 0 salvo entries duplicadas en el lienzo.

### Resultado del job

- El resultado final es el de la **última entry ejecutada** en cada rama. Si una TRANS falla y no tiene hop rojo, la rama termina ahí y el job queda en error (`Kitchen - ERROR … : Finished with errors`, exit 1; verificado).
- Si la rama roja llega a un `SUCCESS`, el job termina **OK** aunque haya fallado algo (verificado: una ktr falló, el hop rojo fue a `SUCCESS` y Kitchen devolvió exit 0). No lo hagas salvo que sea intencional.
- `ABORT` deja el job en error con tu mensaje (`Abortar - ERROR (...) : Fallo una de las transformaciones del job 10_job_secuencia`).

---

## 7. Parámetros del job

Se declaran igual que en la ktr pero directo bajo `<job>`:

```xml
<parameters>
  <parameter>
    <name>FECHA_PROCESO</name>
    <default_value>2026-10-20</default_value>
    <description>Fecha que se muestra en el log</description>
  </parameter>
</parameters>
```

Se pasan con Kitchen `"/param:FECHA_PROCESO=2026-12-31"` (verificado en `11_job_padre.kjb`: el Write to log mostró `FECHA_PROCESO=2026-12-31`). Con `pass_all_parameters=Y` en las entries TRANS/JOB, llegan a los hijos.

---

## 8. Variables internas en jobs (verificado)

- `${Internal.Entry.Current.Directory}` en un job = `file:///C:/…/carpeta-del-kjb` (URI VFS, espacios como `%20`). Ejemplo real del log: `carpeta=file:///C:/ruta/al/repo/skills/pentaho-etl/manual/ejemplos`.
- `${Internal.Job.Filename.Directory}` = misma carpeta (usable en `.kjb`, nunca en `.ktr`).
- Para mover una carpeta entera de ETL (jobs + ktr + datos) sin romper nada: todo relativo con `${Internal.Entry.Current.Directory}`, como en `ejemplos/`.
