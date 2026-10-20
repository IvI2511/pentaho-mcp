# 02 · Job format (.kjb) — PDI 9.4

A job orchestrates: it runs transformations, other jobs, file checks, SQL, etc., **in sequence** (one entry at a time, following hops with a condition). It does not pass rows between entries (that is what "result rows" are for, outside the scope of this manual).

Examples verified with Kitchen: `ejemplos/10_job_secuencia.kjb` (two `.ktr` in sequence + error branch) and `ejemplos/11_job_padre.kjb` (job that calls another job + Write to log + parameter).

---

## 0. Quick checklist

1. Root `<job>`, UTF-8, same escaping as in `.ktr` (`&amp;`, `&lt;`).
2. **There must be an entry named exactly `START`**, of `<type>SPECIAL</type>`, with `<start>Y</start>` **and `<draw>Y</draw>`**. Kitchen looks it up by its literal name and only among the visible entries: if it is called "Inicio", or if it lacks `<draw>Y</draw>`, it fails with `Couldn't find starting point in this job.` (both cases verified).
3. Each entry: unique `<name>`, `<type>` (plugin ID), `<parallel>N</parallel>`, `<draw>Y</draw>`, `<nr>0</nr>`, `<xloc>`, `<yloc>`.
4. Hops go in `<hops>` (not in `<order>`), with `from`, `to`, `from_nr`, `to_nr`, `enabled`, `evaluation`, `unconditional`.
5. The hop leaving START: `<unconditional>Y</unconditional>`. All the others: `evaluation=Y` (on success) or `evaluation=N` (on failure).
6. Paths to `.ktr`/`.kjb`: `${Internal.Entry.Current.Directory}/archivo.ktr`. A bare relative path (`01_generar_constantes.ktr`) fails: `The transformation path 01_generar_constantes.ktr is invalid, and will not run successfully.`
7. If an entry fails and has no error hop, the job ends with an error (exit 1 in Kitchen). To make it explicit, add an `evaluation=N` branch to an `ABORT` (or to a `WRITE_TO_LOG` + `ABORT`).

---

## 1. Skeleton

### 1.1 Minimum that Kitchen accepts (verified)

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

It runs fine, but entries without `draw` stay hidden in Spoon. **Use the full template.**

### 1.2 Recommended template

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

Differences from `.ktr`: **there is no `<info>`**; the metadata goes directly under `<job>`. The log tables that Spoon writes (`job-log-table`, `jobentry-log-table`, `channel-log-table`, `checkpoint-log-table`) can be omitted. `<connection>` (same format as in `.ktr`) goes before `<entries>` if any SQL entry uses it. `<notepads>` goes **after** `<hops>` (the opposite of `.ktr`) and uses the same format (`heigth`, typo included).

---

## 2. `<entry>`: common tags

```xml
<entry>
  <name>Generar constantes</name>
  <description/>
  <type>TRANS</type>
  <attributes/>
  <!-- ... type-specific tags ... -->
  <parallel>N</parallel>
  <draw>Y</draw>
  <nr>0</nr>
  <xloc>256</xloc>
  <yloc>112</yloc>
  <attributes_kjc/>
</entry>
```

| Tag | Default if missing (code) | Notes |
|---|---|---|
| `name` | — | Unique. |
| `type` | — | Plugin ID, case-sensitive. A nonexistent one loads as "missing", and when that entry is reached: `Can't run job due to plugin missing` (exit 1). |
| `parallel` | N | `Y` = the following entries start in parallel. |
| `draw` | **N** | Always `Y`. For START it is **mandatory**. |
| `nr` | 0 | Copy number. Only non-zero if the **same** entry appears twice on the canvas (e.g. two "Success" icons); in that case hops reference it with `from_nr`/`to_nr`. |
| `xloc`, `yloc` | 0 | Same convention as in `.ktr`: main flow at `yloc=112`, `xloc` 96 → 256 → 416 → 576 (step of 160; in the job samples the most common horizontal spacing is 160–192); error branch below (`yloc` ≈ 240). |

---

## 3. Most used entry types

IDs verified in `kettle-job-entries.xml` (engine) and in the annotations of `pdi-core-plugins-impl` (`ABORT`, `ADD_RESULT_FILENAMES`, `CHECK_DB_CONNECTIONS`). The other plugins in `plugins/` add more.

| GUI | `type` | Notes |
|---|---|---|
| Start | `SPECIAL` + `<start>Y</start>` | Literal name `START`. Scheduler tags: `repeat`, `schedulerType` (0 = no schedule), `intervalSeconds`, `intervalMinutes`, `hour`, `minutes`, `weekDay`, `DayOfMonth`. With `repeat=N` they are decorative. |
| Dummy | `SPECIAL` + `<start>N</start><dummy>Y</dummy>` | Does nothing. |
| Transformation | `TRANS` | §4. |
| Job | `JOB` | §5. |
| Success | `SUCCESS` | Ends the job as OK. No tags of its own. |
| Abort job | `ABORT` | `<message>texto</message>`; leaves the job in error and writes the message as ERROR in the log. |
| Write to log | `WRITE_TO_LOG` | `<logmessage>` (substitutes variables), `<loglevel>Basic</loglevel>`, `<logsubject>`. |
| Set variables | `SET_VARIABLES` | `<replacevars>`, `<filename>`, `<file_variable_type>`, and per variable `<variable_name>`, `<variable_value>`, `<variable_type>` (`JVM`, `CURRENT_JOB`, `PARENT_JOB`, `ROOT_JOB`). |
| Evaluate / Simple evaluation | `EVAL` (JavaScript) / `SIMPLE_EVAL` | |
| SQL | `SQL` | Uses one of the job's `<connection>`s. |
| File exists / Create folder / Delete file | `FILE_EXISTS` / `CREATE_FOLDER` / `DELETE_FILE` | |
| Shell | `SHELL` | |
| Others in the engine | `MAIL`, `FTP`, `SFTP`, `HTTP`, `TABLE_EXISTS`, `CREATE_FILE`, `WAIT_FOR_FILE`, `DELAY`, `COPY_FILES`, `MOVE_FILES`, `DELETE_FILES`, `ZIP_FILE`, `UNZIP`, `FILES_EXIST`, `FOLDER_IS_EMPTY`, `COLUMNS_EXIST`, `TRUNCATE_TABLES`, `WAIT_FOR_SQL`, `EVAL_TABLE_CONTENT`, `WRITE_TO_FILE`, `MSGBOX_INFO`, `PING`, … | |

---

## 4. `TRANS` entry (calling a .ktr)

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
      <name>ARCHIVO_SALIDA</name>   <!-- parameter declared in the .ktr -->
      <stream_name/>                <!-- or a field from result rows -->
      <value>01_desde_job</value>   <!-- fixed value; accepts ${VAR} -->
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

- `specification_method`: `filename` (file) | `rep_name` (repository) | `rep_ref`. If it is missing and there is a `<filename>`, the file is used (verified in the minimal job). Set it anyway.
- `filename`: **with `${Internal.Entry.Current.Directory}`** (in a job it resolves to the `.kjb`'s folder). Old samples use `${Internal.Job.Filename.Directory}`, which also works in jobs (verified). A relative path without a variable does not work.
- `run_configuration`: Spoon 9.x writes `Pentaho local`; Kitchen logs `Using run configuration [Pentaho local]`. If you omit it, it also runs locally (verified).
- `wait_until_finished`: `Y`; otherwise the entry finishes "OK" before the transformation does.
- `loglevel`: log level of the child transformation (`Nothing`, `Error`, `Minimal`, `Basic`, `Detailed`, `Debug`, `Rowlevel`).
- Parameters: `pass_all_parameters=Y` passes the job's parameters/variables to the ktr; each `<parameter>` sets one. The parameter must be **declared** in the ktr's `<info><parameters>` to be picked up by name (verified: `ARCHIVO_SALIDA=01_desde_job` produced `salida/01_desde_job.csv`).
- `exec_per_row=Y` + `arg_from_previous`/`params_from_previous` = loop over result rows (not covered here).

Inside the called `.ktr`, `${Internal.Entry.Current.Directory}` points to the **ktr's** folder, not the job's (verified with a job in a different folder). This way each file resolves its paths relative to itself.

---

## 5. `JOB` entry (calling another .kjb)

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

Verified in `11_job_padre.kjb`. Same path and parameter rules as TRANS (without `clear_rows`/`clear_files`/`cluster`).

---

## 6. A job's `<hops>`

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

| `unconditional` | `evaluation` | Meaning | Color in Spoon |
|---|---|---|---|
| `Y` | (ignored; Spoon writes `Y`) | Always continues | black (padlock) |
| `N` | `Y` | Continues **if the source entry finished OK** | green |
| `N` | `N` | Continues **if the source entry failed** | red |

- Defaults if missing (code): `enabled=Y`, `evaluation=Y`, `unconditional=N`. In other words, a bare hop means "on success".
- From `START` always use `unconditional=Y` (the samples do it this way).
- Distribution in the samples: 36 hops `Y/N` (on success), 19 `Y/Y` (unconditional), 5 `N/N` (on failure).
- An entry can have a green hop and a red hop at the same time (pattern used in example 10). Several entries can point to the same one (two red hops into a single `Abortar`).
- Hop to an entry that does not exist: it does not fail on load; it blows up when the source entry runs, with `java.lang.NullPointerException: Cannot invoke "org.pentaho.di.job.JobHopMeta.isUnconditional()" because "hi" is null` (exit 1).
- `from_nr`/`to_nr`: the `<nr>` of the entry copy; 0 except for entries duplicated on the canvas.

### Job result

- The final result is that of the **last entry executed** in each branch. If a TRANS fails and has no red hop, the branch ends there and the job ends in error (`Kitchen - ERROR … : Finished with errors`, exit 1; verified).
- If the red branch reaches a `SUCCESS`, the job ends **OK** even though something failed (verified: a ktr failed, the red hop went to `SUCCESS` and Kitchen returned exit 0). Don't do this unless it is intentional.
- `ABORT` leaves the job in error with your message (`Abortar - ERROR (...) : Fallo una de las transformaciones del job 10_job_secuencia` — i.e. "One of the transformations of job 10_job_secuencia failed").

---

## 7. Job parameters

They are declared the same way as in the ktr, but directly under `<job>`:

```xml
<parameters>
  <parameter>
    <name>FECHA_PROCESO</name>
    <default_value>2026-10-20</default_value>
    <description>Fecha que se muestra en el log</description>
  </parameter>
</parameters>
```

They are passed with Kitchen `"/param:FECHA_PROCESO=2026-12-31"` (verified in `11_job_padre.kjb`: the Write to log showed `FECHA_PROCESO=2026-12-31`). With `pass_all_parameters=Y` in the TRANS/JOB entries, they reach the children.

---

## 8. Internal variables in jobs (verified)

- `${Internal.Entry.Current.Directory}` in a job = `file:///C:/…/carpeta-del-kjb`, i.e. the `.kjb`'s folder (VFS URI, spaces as `%20`). Real example from the log: `carpeta=file:///C:/ruta/al/repo/skills/pentaho-etl/manual/ejemplos`.
- `${Internal.Job.Filename.Directory}` = the same folder (usable in `.kjb`, never in `.ktr`).
- To move an entire ETL folder (jobs + ktr + data) without breaking anything: make everything relative with `${Internal.Entry.Current.Directory}`, as in `ejemplos/`.
