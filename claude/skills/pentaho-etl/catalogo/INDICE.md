# Catalog of steps and job entries - PDI 9.4.0.0-343 (CE)

Generated from the installation itself with the Kettle Java API (`generador/GenerarCatalogo.java`; regenerate with `bash generador/generar.sh`). Do not edit by hand: it is overwritten on regeneration.

## Key points

- **The value of `<type>` in a `.ktr` (inside `<step>`) or in a `.kjb` (inside `<entry>`) is the plugin ID**, the first column of these tables: `CsvInput`, `SelectValues`, `FilterRows`, `TextFileOutput`, `TableInput`... It is **not** the display name ("CSV file input"; Spanish UI: «Filtrar filas») nor the class name. It is case-sensitive.
- Some ids do not resemble the name: `ScriptValueMod` = Modified Java Script Value, `Constant` = Add constants, `Sequence` = Add sequence, `Unique` = Unique rows, `UniqueRowsByHashSet` = Unique rows (HashSet), `Janino` = User defined Java expression, `TypeExitExcelWriterStep` = Microsoft Excel writer, `ExcelInput` = Microsoft Excel input, `getXMLData` = Get data from XML, `SystemInfo` = Get system info, `ExecSQL` = Execute SQL script, `DBLookup` = Database lookup, `DimensionLookup` = Dimension lookup/update, `SplitFieldToRows3` = Split field to rows, `FieldSplitter` = Split fields, `IfNull` = If field value is null, `Denormaliser`/`Normaliser` (with an *s*), `RowGenerator` = Generate rows, `DataGrid` = Data grid.
- Job entries: ids are mostly in UPPERCASE (`TRANS`, `JOB`, `SQL`, `SUCCESS`, `ABORT`, `WRITE_TO_LOG`...). **START and DUMMY have no id of their own**: they are `<type>SPECIAL</type>` with `<start>Y</start>` (START) or `<dummy>Y</dummy>` (Dummy).
- `steps/<id>.xml` contains the **complete** `<step>` block produced by `StepMeta.getXML()` after `setDefault()`: `name`, `type`, `distribute`, `copies`, `partitioning`, then **the step's own fragment** (what `StepMetaInterface.getXML()` returns), and finally `attributes`, `cluster_schema`, `remotesteps`, `GUI`. Likewise `job-entries/<id>.xml` with the complete `<entry>` from `JobEntryCopy.getXML()`.
- The default values are those of `setDefault()`; in many steps that leaves empty lists (`<fields></fields>`) or values still to be filled in. For realistic examples see `steps-comunes.md`.
- `nombre_es` = what Spoon shows with the es_ES locale (the one on this machine). "=" means there is no translation.

## Numbers

- Steps: **216** registered, 216 with default XML, 0 with errors.
- Job entries: **77** registered, 77 with default XML, 0 with errors.
- Origin of the steps: 129 native (kettle-engine, `kettle-steps.xml`) + 87 from `plugins/` (`@Step` annotation).
- Distinct steps used in `samples/*.ktr`: 101; distinct job entries in `samples/*.kjb`: 15.

## OSGi plugins (karaf)

**This installation has no `system/` (nor `system/karaf`)**. Therefore **there is no OSGi step at all**: what is in `plugins/` + the native steps is exactly what Spoon can open on this machine. What a full PDI 9.4 gets through karaf (plugin *pentaho-big-data*: Hadoop file input/output, Parquet/ORC input/output, HBase, MapReduce, Sqoop, Oozie, Pig, Spark submit, Hadoop copy files...) **does not exist here**: a `.ktr` that uses them will open with the step flagged as a missing plugin.

Types used in `samples/` that are **not** in the registry:

| Kind | `<type>` | Sample |
|---|---|---|
| job entry | `SparkSubmit` | samples/jobs/Spark Submit/Spark submit.kjb |

Known karaf ids in PDI 9.4 CE (from memory, **not verifiable in this installation**): steps `HadoopFileInputPlugin`, `HadoopFileOutputPlugin`, `ParquetInput`, `ParquetOutput`, `OrcInput`, `OrcOutput`, `HBaseInput`, `HBaseOutput`, `HBaseRowDecoder`, `HadoopEnterPlugin`/`HadoopExitPlugin` (MapReduce input/output); job entries `HadoopCopyFilesPlugin`, `HadoopTransJobExecutorPlugin`, `HadoopJobExecutorPlugin`, `HadoopPigScriptExecutorPlugin`, `SqoopImport`, `SqoopExport`, `OozieJobExecutor`, `SparkSubmit` (this last one is confirmed by `samples/jobs/Spark Submit/Spark submit.kjb`). Do not use them in ETLs for this installation.

## Building steps from XML via the API (notes for the Spoon plugin)

- From a complete `<step>`: `new StepMeta(Node stepnode, transMeta.getDatabases(), transMeta.getMetaStore())` -- looks up the plugin by `<type>` in the `PluginRegistry` (throws `KettlePluginLoaderException` if the id does not exist) and calls `StepMetaInterface.loadXML(...)`. Then `transMeta.addStep(sm)`, `sm.setParentTransMeta(transMeta)`.
- From id + fragment: `PluginInterface p = registry.findPluginWithId(StepPluginType.class, id)`; `StepMetaInterface m = (StepMetaInterface) registry.loadClass(p)`; `m.loadXML(nodo, databases, metaStore)`; `StepMeta sm = new StepMeta(id, nombre, m)` and `m.setParentStepMeta(sm)`.
- Steps that name other steps (`<from>`, `<send_true_to>`, `<step1>`, `<target_step>`...) only store the name when loaded: **after adding all the steps** call `sm.getStepMetaInterface().searchInfoAndTargetSteps(transMeta.getSteps())` on each one (TransMeta does not expose a public method for that). Hops: `transMeta.addTransHop(new TransHopMeta(from, to))`.
- `getXML()` of some steps (SortRows, BlockingStep, ZipFile, Avro, Kafka) throws an NPE if the meta is not attached to `StepMeta` + `TransMeta`; `JobEntryCopy.getXML()` throws an NPE without `setLocation(x, y)`.
- Shortcut in Spoon: `Spoon.getInstance().pasteXML(transMeta, xml, new Point(x, y))` accepts the clipboard format `<transformation-steps><steps><step>...</step></steps><order><hop>...</hop></order><notepads/><step_error_handling/></transformation-steps>` (the same thing Ctrl+C produces in Spoon) and takes care of duplicate names, hops, undo and refresh. For jobs the clipboard uses `<job-jobentries>`. After manual changes: `spoon.refreshGraph()`, `spoon.refreshTree()`, `spoon.addUndoNew(transMeta, new StepMeta[]{sm}, new int[]{transMeta.indexOfStep(sm)})`.

## Steps (transformations, `.ktr`)

Grouped by Spoon palette category (English / Spanish UI). `Deprecated` last: they still work, but do not use them in new ETLs.

### Big Data (7)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`AvroInputNew`](steps/AvroInputNew.xml) | Avro input | = | Reads data from Avro file |  | pdi-avro-format-plugin |
| [`AvroOutput`](steps/AvroOutput.xml) | Avro output | = | Writes data to an Avro file according to a mapping |  | pdi-avro-format-plugin |
| [`CassandraInput`](steps/CassandraInput.xml) | Cassandra input | = | Reads data from a Cassandra table |  | pentaho-cassandra-plugin |
| [`CassandraOutput`](steps/CassandraOutput.xml) | Cassandra output | = | Writes to a Cassandra table |  | pentaho-cassandra-plugin |
| [`MongoDbInput`](steps/MongoDbInput.xml) | MongoDB input | = | Reads from a Mongo DB collection |  | mongodb-plugins |
| [`MongoDbOutput`](steps/MongoDbOutput.xml) | MongoDB output | = | Writes to a Mongo DB collection |  | mongodb-plugins |
| [`SSTableOutput`](steps/SSTableOutput.xml) | SSTable output | = | Writes to a filesystem directory as a Cassandra SSTable |  | pentaho-cassandra-plugin |

### Bulk loading (10)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`GPLoad`](steps/GPLoad.xml) | Greenplum load | = | Greenplum load |  | kettle-gpload-plugin |
| [`InfobrightOutput`](steps/InfobrightOutput.xml) | Infobright loader | = | Load data to an Infobright database table |  | infobright-bulk-loader-plugins |
| [`MonetDBBulkLoader`](steps/MonetDBBulkLoader.xml) | MonetDB bulk loader | = | Load data into MonetDB by using their bulk load command in streaming mode. |  | monet-db-bulk-loader-plugins |
| [`MySQLBulkLoader`](steps/MySQLBulkLoader.xml) | MySQL bulk loader | = | MySQL bulk loader step, loading data over a named pipe (not available on MS Windows) |  | mysql-bulk-loader |
| [`OraBulkLoader`](steps/OraBulkLoader.xml) | Oracle bulk loader | = | Use Oracle bulk loader to load data |  | oracle-bulk-loader-plugins |
| [`PGBulkLoader`](steps/PGBulkLoader.xml) | PostgreSQL bulk loader | = | PostgreSQL Bulk Loader |  | postgresql-db-bulk-loader-plugins |
| [`TeraDataBulkLoader`](steps/TeraDataBulkLoader.xml) | Teradata TPT bulk loader | = | Teradata TPT bulkloader, using tbuild command |  | pdi-teradata-tpt-plugin |
| [`TeraFast`](steps/TeraFast.xml) (alias: `TeraFastPlugin`) | Teradata Fastload bulk loader | = | The Teradata Fastload bulk loader |  | terafast-bulk-loader-plugins |
| [`VectorWiseBulkLoader`](steps/VectorWiseBulkLoader.xml) | Ingres VectorWise bulk loader | = | This step interfaces with the Ingres VectorWise Bulk Loader "COPY TABLE" command. |  | ivw-bulk-loader-plugins |
| [`VerticaBulkLoader`](steps/VerticaBulkLoader.xml) | Vertica bulk loader | = | Bulk load data into a Vertica database table |  | VerticaBulkLoader |

### Cryptography (4)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`PGPDecryptStream`](steps/PGPDecryptStream.xml) | PGP decrypt stream | = | Decrypt data stream with PGP |  | nativo (kettle-engine) |
| [`PGPEncryptStream`](steps/PGPEncryptStream.xml) | PGP encrypt stream | = | Encrypt data stream with PGP |  | nativo (kettle-engine) |
| [`SecretKeyGenerator`](steps/SecretKeyGenerator.xml) | Secret key generator | = | Generate secret key for algorithms such as DES, AES, TripleDES. |  | nativo (kettle-engine) |
| [`SymmetricCryptoTrans`](steps/SymmetricCryptoTrans.xml) | Symmetric cryptography | = | Encrypt or decrypt a string using symmetric encryption. Available algorithms are DES, AES, TripleDES. |  | nativo (kettle-engine) |

### Data Warehouse / Almacén de Datos (2)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`CombinationLookup`](steps/CombinationLookup.xml) | Combination lookup/update | Búsqueda/actualización en combinación | Update a junk dimension in a data warehouse. Alternatively, look up information in this dimension. The primary key of a junk dimension are all the fields. | 1 | pdi-core-plugins |
| [`DimensionLookup`](steps/DimensionLookup.xml) | Dimension lookup/update | Búsqueda/actualización en dimensión | Update a slowly changing dimension in a data warehouse. Alternatively, look up information in this dimension. | 2 | nativo (kettle-engine) |

### Experimental (1)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`SFTPPut`](steps/SFTPPut.xml) | SFTP put | = | Upload a file or a stream file to remote host via SFTP |  | nativo (kettle-engine) |

### Flow (15)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`Abort`](steps/Abort.xml) | Abort | = | Abort a transformation | 5 | pdi-core-plugins |
| [`Append`](steps/Append.xml) | Append streams | = | Append 2 streams in an ordered way | 8 | pdi-core-plugins |
| [`BlockingStep`](steps/BlockingStep.xml) | Blocking step | = | The Blocking step blocks all output until the very last row is received from the previous step. |  | pdi-core-plugins |
| [`BlockUntilStepsFinish`](steps/BlockUntilStepsFinish.xml) | Block this step until steps finish | = | Block this step until selected steps finish. | 1 | pdi-core-plugins |
| [`DetectEmptyStream`](steps/DetectEmptyStream.xml) | Detect empty stream | = | This step will output one empty row if input stream is empty (ie when input stream does not contain any row) |  | nativo (kettle-engine) |
| [`DetectLastRow`](steps/DetectLastRow.xml) | Identify last row in a stream | = | Last row will be marked |  | nativo (kettle-engine) |
| [`Dummy`](steps/Dummy.xml) | Dummy (do nothing) | Transformación simulada (no hace nada) | This step type doesn't do anything. It's useful however when testing things or in certain situations where you want to split streams. | 119 | nativo (kettle-engine) |
| [`FilterRows`](steps/FilterRows.xml) | Filter rows | Filtrar filas | Filter rows using simple equations | 37 | nativo (kettle-engine) |
| [`JavaFilter`](steps/JavaFilter.xml) | Java filter | = | Filter rows using java code | 3 | nativo (kettle-engine) |
| [`JobExecutor`](steps/JobExecutor.xml) | Job executor | = | This step executes a Pentaho Data Integration job, sets parameters and passes rows. | 1 | nativo (kettle-engine) |
| [`MetaInject`](steps/MetaInject.xml) | ETL metadata injection | = |  | 2 | meta-inject-plugins |
| [`PrioritizeStreams`](steps/PrioritizeStreams.xml) | Prioritize streams | = | Prioritize streams in an order way. |  | nativo (kettle-engine) |
| [`SingleThreader`](steps/SingleThreader.xml) | Single threader | = | Executes a transformation snippet in a single thread.  You need a standard mapping or a transformation with an Injector step where data from the parent transformation will arive in blocks. | 2 | nativo (kettle-engine) |
| [`SwitchCase`](steps/SwitchCase.xml) | Switch / case | = | Switch a row to a certain target step based on the case value in a field. | 5 | nativo (kettle-engine) |
| [`TransExecutor`](steps/TransExecutor.xml) | Transformation executor | = | This step executes a Pentaho Data Integration transformation, sets parameters and passes rows. | 2 | nativo (kettle-engine) |

### Inline / Embebido (3)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`Injector`](steps/Injector.xml) | Injector | Inyector de fila | Injector step to allow to inject rows into the transformation through the java API |  | nativo (kettle-engine) |
| [`SocketReader`](steps/SocketReader.xml) | Socket reader | Leer de Socket | Socket reader.  A socket client that connects to a server (Socket Writer step). |  | nativo (kettle-engine) |
| [`SocketWriter`](steps/SocketWriter.xml) | Socket writer | Escribir en Socket | Socket writer.  A socket server that can send rows of data to a socket reader. |  | nativo (kettle-engine) |

### Input / Entrada (37)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`AccessInput`](steps/AccessInput.xml) | Microsoft Access input | = | Read data from a Microsoft Access file |  | ms-access-plugins |
| [`CsvInput`](steps/CsvInput.xml) | CSV file input | = | Simple CSV file input | 13 | nativo (kettle-engine) |
| [`CubeInput`](steps/CubeInput.xml) | De-serialize from file | Des-Serializacion desde Fichero | Read rows of data from a data cube. |  | pdi-core-plugins |
| [`DataGrid`](steps/DataGrid.xml) | Data grid | = | Enter rows of static data in a grid, usually for testing, reference or demo purpose | 26 | nativo (kettle-engine) |
| [`ExcelInput`](steps/ExcelInput.xml) | Microsoft Excel input | = | Read data from Excel and OpenOffice Workbooks (XLS, XLSX, ODS). | 20 | excel-plugins |
| [`FixedInput`](steps/FixedInput.xml) | Fixed file input | = | Fixed file input | 1 | nativo (kettle-engine) |
| [`GetFileNames`](steps/GetFileNames.xml) | Get file names | Obtener nombres de fichero | Get file names from the operating system and send them to the next step. | 11 | nativo (kettle-engine) |
| [`GetFilesRowsCount`](steps/GetFilesRowsCount.xml) | Get files rows count | = | Returns rows count for text files. |  | nativo (kettle-engine) |
| [`GetRepositoryNames`](steps/GetRepositoryNames.xml) | Get repository names | = | Lists detailed information about transformations and/or jobs in a repository | 1 | nativo (kettle-engine) |
| [`GetSubFolders`](steps/GetSubFolders.xml) | Get subfolder names | = | Read a parent folder and return all subfolders | 1 | nativo (kettle-engine) |
| [`GetTableNames`](steps/GetTableNames.xml) | Get table names | = | Get table names from database connection and send them to the next step |  | nativo (kettle-engine) |
| [`getXMLData`](steps/getXMLData.xml) | Get data from XML | = | Get data from XML file by using XPath.  This step also allows you to parse XML defined in a previous field. | 18 | pdi-xml-plugin |
| [`HL7Input`](steps/HL7Input.xml) | HL7 input | = | Reads and parses HL7 messages and outputs a series of values from the messages | 3 | kettle-hl7-plugin |
| [`JsonInput`](steps/JsonInput.xml) | JSON input | = | Extract relevant portions out of JSON structures (file or incoming field) and output rows | 6 | kettle-json-plugin |
| [`LDAPInput`](steps/LDAPInput.xml) | LDAP input | = | Read data from LDAP host |  | ldap-plugins |
| [`LDIFInput`](steps/LDIFInput.xml) | LDIF input | = | Read data from LDIF files |  | nativo (kettle-engine) |
| [`LoadFileInput`](steps/LoadFileInput.xml) | Load file content in memory | = | Load file content in memory | 5 | nativo (kettle-engine) |
| [`MailInput`](steps/MailInput.xml) | Email messages input | = | Read POP3/IMAP server and retrieve messages |  | nativo (kettle-engine) |
| [`MondrianInput`](steps/MondrianInput.xml) | Mondrian input | = | Execute and retrieve data using an MDX query against a Pentaho Analyses OLAP server (Mondrian) |  | mondrianinput |
| [`OlapInput`](steps/OlapInput.xml) | OLAP input | = | Execute and retrieve data using an MDX query against any XML/A OLAP datasource using olap4j |  | nativo (kettle-engine) |
| [`ParallelGzipCsvInput`](steps/ParallelGzipCsvInput.xml) | GZIP CSV input | = | Parallel GZIP CSV file input reader |  | nativo (kettle-engine) |
| [`PropertyInput`](steps/PropertyInput.xml) | Property input | = | Read data (key, value) from properties files. |  | nativo (kettle-engine) |
| [`RandomCCNumberGenerator`](steps/RandomCCNumberGenerator.xml) | Generate random credit card numbers | = | Generate random valide (luhn check) credit card numbers |  | nativo (kettle-engine) |
| [`RandomValue`](steps/RandomValue.xml) | Generate random value | = | Generate random value | 5 | nativo (kettle-engine) |
| [`RowGenerator`](steps/RowGenerator.xml) | Generate rows | Generar filas | Generate a number of empty or equal rows. | 196 | nativo (kettle-engine) |
| [`RssInput`](steps/RssInput.xml) | RSS input | = | Read RSS feeds |  | rss-plugins |
| [`S3CSVINPUT`](steps/S3CSVINPUT.xml) | S3 CSV input | = | Is capable of reading CSV data stored on Amazon S3 in parallel |  | kettle-s3csvinput-plugin |
| [`SalesforceInput`](steps/SalesforceInput.xml) | Salesforce input | = | Extract data from Salesforce |  | pdi-salesforce-plugin |
| [`SASInput`](steps/SASInput.xml) | SAS input | = | This step reads files in sas7bdat (SAS) native format |  | nativo (kettle-engine) |
| [`ShapeFileReader`](steps/ShapeFileReader.xml) | ESRI shapefile reader | = | Reads shape file data from an ESRI shape file and linked DBF file |  | kettle-shapefilereader-plugin |
| [`SystemInfo`](steps/SystemInfo.xml) | Get system info | Información de sistema | Get information from the system like system date, arguments, etc. | 12 | nativo (kettle-engine) |
| [`TableInput`](steps/TableInput.xml) | Table input | Entrada Tabla | Read information from a database table. | 5 | nativo (kettle-engine) |
| [`TextFileInput`](steps/TextFileInput.xml) | Text file input | Entrada Fichero de Texto | Read data from a text file in several formats. This data can then be passed on to the next step(s)... | 17 | nativo (kettle-engine) |
| [`TypeExitGoogleAnalyticsInputStep`](steps/TypeExitGoogleAnalyticsInputStep.xml) | Google Analytics | = | Fetches data from google analytics account |  | pdi-google-analytics-plugin |
| [`XBaseInput`](steps/XBaseInput.xml) | XBase input | Entrada XBase | Reads records from an XBase type of database file (DBF) | 1 | nativo (kettle-engine) |
| [`XMLInputStream`](steps/XMLInputStream.xml) | XML input stream (StAX) | = | This step is capable of processing very large and complex XML files very fast. | 7 | pdi-xml-plugin |
| [`YamlInput`](steps/YamlInput.xml) | YAML input | = | Read YAML source (file or stream) parse them and convert them to rows and writes these to one or more output. | 5 | yaml-input-plugins |

### Job / Trabajo (6)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`FilesFromResult`](steps/FilesFromResult.xml) | Get files from result | Obtener ficheros de resultado anterior | This step allows you to read filenames used or generated in a previous entry in a job. | 1 | nativo (kettle-engine) |
| [`FilesToResult`](steps/FilesToResult.xml) | Set files in result | Copia nombres de fichero al resultado | This step allows you to set filenames in the result of this transformation. Subsequent job entries can then use this information. |  | nativo (kettle-engine) |
| [`GetVariable`](steps/GetVariable.xml) | Get variables | Obtener variables | Determine the values of certain (environment or Kettle) variables and put them in field values. | 6 | nativo (kettle-engine) |
| [`RowsFromResult`](steps/RowsFromResult.xml) | Get rows from result | Obtener filas de resultado anterior | This allows you to read rows from a previous entry in a job. | 8 | nativo (kettle-engine) |
| [`RowsToResult`](steps/RowsToResult.xml) | Copy rows to result | Copia filas a resultado | Use this step to write rows to the executing job. The information will then be passed to the next entry in this job. | 9 | nativo (kettle-engine) |
| [`SetVariable`](steps/SetVariable.xml) | Set variables | Asignar valor a variables | Set environment variables based on a single input row. | 2 | nativo (kettle-engine) |

### Joins / Uniones (6)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`JoinRows`](steps/JoinRows.xml) | Join rows (cartesian product) | Juntar filas (producto cartesiano) | The output of this step is the cartesian product of the input streams. The number of rows is the multiplication of the number of rows in the input streams. | 8 | nativo (kettle-engine) |
| [`MergeJoin`](steps/MergeJoin.xml) | Merge join | Unión por clave | Joins two streams on a given key and outputs a joined set. The input streams must be sorted on the join key | 2 | nativo (kettle-engine) |
| [`MergeRows`](steps/MergeRows.xml) | Merge rows (diff) | Fundir filas | Merge two streams of rows, sorted on a certain key.  The two streams are compared and the equals, changed, deleted and new rows are flagged. | 1 | nativo (kettle-engine) |
| [`MultiwayMergeJoin`](steps/MultiwayMergeJoin.xml) | Multiway merge join | = | Multiway merge join |  | nativo (kettle-engine) |
| [`SortedMerge`](steps/SortedMerge.xml) | Sorted merge | Unión ordenada | Sorted merge | 2 | nativo (kettle-engine) |
| [`XMLJoin`](steps/XMLJoin.xml) | XML join | = | Joins a stream of XML-Tags into a target XML string | 5 | pdi-xml-plugin |

### Lookup / Búsqueda (15)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`ColumnExists`](steps/ColumnExists.xml) | Column exists | = | Check if a column exists | 1 | pdi-core-plugins |
| [`DBJoin`](steps/DBJoin.xml) | Database join | Consulta base de datos | Execute a database query using stream values as parameters |  | nativo (kettle-engine) |
| [`DBLookup`](steps/DBLookup.xml) | Database lookup | Búsqueda en base de datos | Look up values in a database using field values |  | nativo (kettle-engine) |
| [`DBProc`](steps/DBProc.xml) | Call DB procedure | Procedimiento base de datos | Get back information by calling a database procedure. |  | nativo (kettle-engine) |
| [`DynamicSQLRow`](steps/DynamicSQLRow.xml) | Dynamic SQL row | = | Execute dynamic SQL statement build in a previous field |  | nativo (kettle-engine) |
| [`FileExists`](steps/FileExists.xml) | File exists | = | Check if a file exists | 1 | nativo (kettle-engine) |
| [`FileLocked`](steps/FileLocked.xml) | Check if file is locked | = | Check if a file is locked by another process |  | nativo (kettle-engine) |
| [`FuzzyMatch`](steps/FuzzyMatch.xml) | Fuzzy match | = | Finding approximate matches to a string using matching algorithms. Read a field from a main stream and output approximative value from lookup stream. | 1 | nativo (kettle-engine) |
| [`HTTP`](steps/HTTP.xml) | HTTP client | Cliente HTTP | Call a web service over HTTP by supplying a base URL by allowing parameters to be set dynamically | 1 | nativo (kettle-engine) |
| [`HTTPPOST`](steps/HTTPPOST.xml) | HTTP post | = | Call a web service request over HTTP by supplying a base URL by allowing parameters to be set dynamically | 1 | nativo (kettle-engine) |
| [`Rest`](steps/Rest.xml) | REST client | = | Consume RESTfull services. REpresentational State Transfer (REST) is a key design idiom that embraces a stateless client-server architecture in which the web services are viewed as resources and can be identified by their URLs |  | nativo (kettle-engine) |
| [`StreamLookup`](steps/StreamLookup.xml) | Stream lookup | Búsqueda en flujo de datos | Look up values coming from another stream in the transformation. | 8 | nativo (kettle-engine) |
| [`TableExists`](steps/TableExists.xml) | Table exists | = | Check if a table exists on a specified connection |  | nativo (kettle-engine) |
| [`WebServiceAvailable`](steps/WebServiceAvailable.xml) | Check if webservice is available | = | Check if a webservice is available |  | nativo (kettle-engine) |
| [`WebServiceLookup`](steps/WebServiceLookup.xml) | Web services lookup | = | Look up information using web services (WSDL) | 3 | nativo (kettle-engine) |

### Mapping / Mapeado (4)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`Mapping`](steps/Mapping.xml) | Mapping (sub-transformation) | Mapeo (sub-transformación) | Run a mapping (sub-transformation), use MappingInput and MappingOutput to specify the fields interface | 3 | nativo (kettle-engine) |
| [`MappingInput`](steps/MappingInput.xml) | Mapping input specification | Especificación del mapeo de entrada | Specify the input interface of a mapping | 3 | nativo (kettle-engine) |
| [`MappingOutput`](steps/MappingOutput.xml) | Mapping output specification | Especificación del mapeo de salida | Specify the output interface of a mapping | 3 | nativo (kettle-engine) |
| [`SimpleMapping`](steps/SimpleMapping.xml) | Simple mapping (sub-transformation) | = | Run a mapping (sub-transformation), use MappingInput and MappingOutput to specify the fields interface.  This is the simplified version only allowing one input and one output data set. |  | nativo (kettle-engine) |

### Output / Salida (23)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`AccessOutput`](steps/AccessOutput.xml) | Microsoft Access output | Salida Access | Stores records into an MS-Access database table. |  | ms-access-plugins |
| [`AutoDoc`](steps/AutoDoc.xml) | Automatic documentation output | = | This step automatically generates documentation based on input in the form of a list of transformations and jobs | 3 | pdi-core-plugins |
| [`CubeOutput`](steps/CubeOutput.xml) | Serialize to file | Serialización a Fichero | Write rows of data to a data cube |  | pdi-core-plugins |
| [`Delete`](steps/Delete.xml) | Delete | Eliminar | Delete data in a database table based upon keys | 2 | nativo (kettle-engine) |
| [`ExcelOutput`](steps/ExcelOutput.xml) | Microsoft Excel output | = | Stores records into an Excel (XLS) document with formatting information. | 2 | excel-plugins |
| [`InsertUpdate`](steps/InsertUpdate.xml) | Insert / update | Insertar / Actualizar | Update or insert rows in a database based upon keys. | 2 | nativo (kettle-engine) |
| [`JsonOutput`](steps/JsonOutput.xml) | JSON output | = | Create JSON block and output it in a field or a file. | 1 | kettle-json-plugin |
| [`LDAPOutput`](steps/LDAPOutput.xml) | LDAP output | = | Perform Insert, upsert, update, add or delete operations on records based on their DN (Distinguished  Name). |  | ldap-plugins |
| [`PentahoReportingOutput`](steps/PentahoReportingOutput.xml) | Pentaho reporting output | = | Executes an existing report (PRPT) | 1 | pentaho-reporting-plugins |
| [`PropertyOutput`](steps/PropertyOutput.xml) | Properties output | = | Write data to properties file | 1 | nativo (kettle-engine) |
| [`RssOutput`](steps/RssOutput.xml) | RSS output | = | Read RSS stream. |  | rss-plugins |
| [`S3FileOutputPlugin`](steps/S3FileOutputPlugin.xml) | S3 file output | = | Create files in an S3 location |  | pentaho-s3-vfs-plugin |
| [`SalesforceDelete`](steps/SalesforceDelete.xml) | Salesforce delete | = | Delete records in Salesforce module. |  | pdi-salesforce-plugin |
| [`SalesforceInsert`](steps/SalesforceInsert.xml) | Salesforce insert | = | Insert records in Salesforce module. |  | pdi-salesforce-plugin |
| [`SalesforceUpdate`](steps/SalesforceUpdate.xml) | Salesforce update | = | Update records in Salesforce module. |  | pdi-salesforce-plugin |
| [`SalesforceUpsert`](steps/SalesforceUpsert.xml) | Salesforce upsert | = | Insert or update records in Salesforce module. |  | pdi-salesforce-plugin |
| [`SQLFileOutput`](steps/SQLFileOutput.xml) | SQL file output | = | Output SQL INSERT statements to file | 1 | nativo (kettle-engine) |
| [`SynchronizeAfterMerge`](steps/SynchronizeAfterMerge.xml) | Synchronize after merge | = | This step perform insert/update/delete in one go based on the value of a field. |  | nativo (kettle-engine) |
| [`TableOutput`](steps/TableOutput.xml) | Table output | Salida Tabla | Write information to a database table | 9 | nativo (kettle-engine) |
| [`TextFileOutput`](steps/TextFileOutput.xml) | Text file output | Salida Fichero de Texto | Write rows to a text file. | 28 | nativo (kettle-engine) |
| [`TypeExitExcelWriterStep`](steps/TypeExitExcelWriterStep.xml) | Microsoft Excel writer | = | Writes or appends data to an Excel file |  | excel-plugins |
| [`Update`](steps/Update.xml) | Update | Actualizar | Update data in a database table based upon keys | 2 | nativo (kettle-engine) |
| [`XMLOutput`](steps/XMLOutput.xml) | XML output | = | Write data to an XML file | 3 | pdi-xml-plugin |

### Pentaho Server (3)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`CallEndpointStep`](steps/CallEndpointStep.xml) | Call endpoint | = | Call an endpoint of the Pentaho Server. |  | platform-utils-plugin |
| [`GetSessionVariableStep`](steps/GetSessionVariableStep.xml) | Get session variables | = | Get session variables from the current user session. |  | platform-utils-plugin |
| [`SetSessionVariableStep`](steps/SetSessionVariableStep.xml) | Set session variables | = | Set session variables in the current user session. |  | platform-utils-plugin |

### Scripting (9)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`ExecSQL`](steps/ExecSQL.xml) | Execute SQL script | Ejecutar script SQL | Execute an SQL script, optionally parameterized using input rows | 1 | nativo (kettle-engine) |
| [`ExecSQLRow`](steps/ExecSQLRow.xml) | Execute row SQL script | = | Execute SQL script extracted from a field created in a previous step. | 1 | nativo (kettle-engine) |
| [`Formula`](steps/Formula.xml) | Formula | = | Calculate a formula using Pentaho's libformula |  | nativo (kettle-engine) |
| [`Janino`](steps/Janino.xml) | User defined Java expression | = | Calculate the result of a Java Expression using Janino | 3 | nativo (kettle-engine) |
| [`RegexEval`](steps/RegexEval.xml) | Regex evaluation | = | Regular expression Evaluation This step uses a regular expression to evaluate a field. It can also extract new fields out of an existing field with capturing groups. | 1 | nativo (kettle-engine) |
| [`RuleAccumulator`](steps/RuleAccumulator.xml) | Rules accumulator | = | Rules accumulator step | 1 | kettle-drools5-plugin |
| [`RuleExecutor`](steps/RuleExecutor.xml) | Rules executor | = | Rules executor step |  | kettle-drools5-plugin |
| [`ScriptValueMod`](steps/ScriptValueMod.xml) (alias: `ScriptValue`) | Modified JavaScript value | Valor JavaScript modificado | This is a modified plugin for the Scripting Values with improved interface and performance. Written & donated to open source by Martin Lange, Proconis : http://www.proconis.de | 55 | nativo (kettle-engine) |
| [`UserDefinedJavaClass`](steps/UserDefinedJavaClass.xml) | User defined Java class | = | This step allows you to program a step using Java code | 12 | nativo (kettle-engine) |

### Statistics (7)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`AnalyticQuery`](steps/AnalyticQuery.xml) | Analytic query | = | Execute analytic queries over a sorted dataset (LEAD/LAG/FIRST/LAST) | 2 | pdi-core-plugins |
| [`GroupBy`](steps/GroupBy.xml) | Group by | Agrupar por | Builds aggregates in a group by fashion. This works only on a sorted input. If the input is not sorted, only double consecutive rows are handled correctly. | 21 | nativo (kettle-engine) |
| [`MemoryGroupBy`](steps/MemoryGroupBy.xml) | Memory group by | = | Builds aggregates in a group by fashion. This step doesn't require sorted input. | 4 | nativo (kettle-engine) |
| [`ReservoirSampling`](steps/ReservoirSampling.xml) | Reservoir sampling | = | [Transform] Samples a fixed number of rows from the incoming stream |  | nativo (kettle-engine) |
| [`SampleRows`](steps/SampleRows.xml) | Sample rows | = | Filter rows based on the line number. | 1 | nativo (kettle-engine) |
| [`StepsMetrics`](steps/StepsMetrics.xml) | Output steps metrics | = | Return metrics for one or several steps |  | nativo (kettle-engine) |
| [`UnivariateStats`](steps/UnivariateStats.xml) | Univariate statistics | = | This step computes some simple stats based on a single input field |  | nativo (kettle-engine) |

### Streaming (7)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`Jms2Consumer`](steps/Jms2Consumer.xml) | JMS consumer | = | Consumes JMS streams |  | pentaho-streaming-jms-plugin |
| [`Jms2Producer`](steps/Jms2Producer.xml) | JMS producer | = | Produces JMS streams |  | pentaho-streaming-jms-plugin |
| [`KafkaConsumerInput`](steps/KafkaConsumerInput.xml) | Kafka consumer | = | Consume messages from a Kafka topic |  | pentaho-streaming-kafka-plugin |
| [`KafkaProducerOutput`](steps/KafkaProducerOutput.xml) | Kafka producer | = | Produce messages to a Kafka topic |  | pentaho-streaming-kafka-plugin |
| [`MQTTConsumer`](steps/MQTTConsumer.xml) | MQTT consumer | = | Subscribes and streams an MQTT Topic |  | pentaho-streaming-mqtt-plugin |
| [`MQTTProducer`](steps/MQTTProducer.xml) | MQTT producer | = | Produce messages to a MQTT Topic |  | pentaho-streaming-mqtt-plugin |
| [`RecordsFromStream`](steps/RecordsFromStream.xml) | Get records from stream | = | This step allows you to read records from a streaming step. |  | nativo (kettle-engine) |

### Transform / Transformar (26)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`AddXML`](steps/AddXML.xml) | Add XML | = | Encode several fields into an XML fragment | 15 | pdi-xml-plugin |
| [`Calculator`](steps/Calculator.xml) | Calculator | Calculadora | Create new fields by performing simple calculations | 16 | nativo (kettle-engine) |
| [`CheckSum`](steps/CheckSum.xml) | Add a checksum | = | Add a checksum column for each input row | 2 | pdi-core-plugins |
| [`ClosureGenerator`](steps/ClosureGenerator.xml) | Closure generator | = | This step allows you to generates a closure table using parent-child relationships. | 1 | pdi-core-plugins |
| [`ConcatFields`](steps/ConcatFields.xml) | Concat fields | = | Concat fields together into a new field (similar to the Text File Output step) | 1 | pdi-core-plugins |
| [`Constant`](steps/Constant.xml) | Add constants | Añadir constantes | Add one or more constants to the input rows | 13 | nativo (kettle-engine) |
| [`Denormaliser`](steps/Denormaliser.xml) | Row denormaliser | Des-Normalización de fila | Denormalises rows by looking up key-value pairs and by assigning them to new fields in the output rows. This method aggregates and needs the input rows to be sorted on the grouping fields | 9 | nativo (kettle-engine) |
| [`FieldsChangeSequence`](steps/FieldsChangeSequence.xml) | Add value fields changing sequence | = | Add sequence depending of fields value change. Each time value of at least one field change, PDI will reset sequence. | 1 | nativo (kettle-engine) |
| [`FieldSplitter`](steps/FieldSplitter.xml) | Split fields | Partir campos | When you want to split a single field into more then one, use this step type. | 3 | nativo (kettle-engine) |
| [`Flattener`](steps/Flattener.xml) (alias: `Flatterner`) | Row flattener | = | Flattens consecutive rows based on the order in which they appear in the input stream |  | nativo (kettle-engine) |
| [`GetSlaveSequence`](steps/GetSlaveSequence.xml) | Get ID from slave server | = | Retrieves unique IDs in blocks from a slave server.  The referenced sequence needs to be configured on the slave server in the XML configuration file. |  | nativo (kettle-engine) |
| [`Normaliser`](steps/Normaliser.xml) | Row normaliser | Normalización de fila | De-normalised information can be normalised using this step type. | 8 | nativo (kettle-engine) |
| [`NumberRange`](steps/NumberRange.xml) | Number range | = | Create ranges based on numeric field | 1 | nativo (kettle-engine) |
| [`ReplaceString`](steps/ReplaceString.xml) | Replace in string | = | Replace all occurences a word in a string with another word. | 6 | nativo (kettle-engine) |
| [`SelectValues`](steps/SelectValues.xml) | Select values | Selecciona/Renombra valores | Select or remove fields in a row. Optionally, set the field meta-data: type, length and precision. | 45 | nativo (kettle-engine) |
| [`Sequence`](steps/Sequence.xml) | Add sequence | Añadir secuencia | Get the next value from an sequence | 23 | pdi-core-plugins |
| [`SetValueConstant`](steps/SetValueConstant.xml) | Set field value to a constant | = | Set value of a field to a constant | 3 | nativo (kettle-engine) |
| [`SetValueField`](steps/SetValueField.xml) | Set field value | = | Set value of a field with another value field |  | nativo (kettle-engine) |
| [`SortRows`](steps/SortRows.xml) | Sort rows | Ordenar filas | Sort rows based upon field values (ascending or descending) | 23 | nativo (kettle-engine) |
| [`SplitFieldToRows3`](steps/SplitFieldToRows3.xml) | Split field to rows | = | Splits a single string field by delimiter and creates a new row for each split term | 3 | nativo (kettle-engine) |
| [`StringCut`](steps/StringCut.xml) | Strings cut | = | Strings cut (substring). |  | nativo (kettle-engine) |
| [`StringOperations`](steps/StringOperations.xml) | String operations | = | Apply certain operations like trimming, padding and others to string value. |  | nativo (kettle-engine) |
| [`Unique`](steps/Unique.xml) | Unique rows | Filas Unicas | Remove double rows and leave only unique occurrences. This works only on a sorted input. If the input is not sorted, only double consecutive rows are handled correctly. | 6 | nativo (kettle-engine) |
| [`UniqueRowsByHashSet`](steps/UniqueRowsByHashSet.xml) | Unique rows (HashSet) | = | Remove double rows and leave only unique occurrences by using a HashSet. | 6 | nativo (kettle-engine) |
| [`ValueMapper`](steps/ValueMapper.xml) | Value mapper | Mapeo de valores | Maps values of a certain field from one value to another | 5 | nativo (kettle-engine) |
| [`XSLT`](steps/XSLT.xml) | XSL transformation | = | Make an XSL transformation |  | pdi-xml-plugin |

### Utility (15)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`ChangeFileEncoding`](steps/ChangeFileEncoding.xml) | Change file encoding | = | Change file encoding and create a new file |  | pdi-core-plugins |
| [`CloneRow`](steps/CloneRow.xml) | Clone row | = | Clone a row as many times as needed | 1 | pdi-core-plugins |
| [`Delay`](steps/Delay.xml) | Delay row | = | Output each input row after a delay | 4 | pdi-core-plugins |
| [`ExecProcess`](steps/ExecProcess.xml) | Execute a process | = | Execute a process and return the result |  | nativo (kettle-engine) |
| [`IfNull`](steps/IfNull.xml) | If field value is null | = | Sets a field value to a constant if it is null. |  | nativo (kettle-engine) |
| [`Mail`](steps/Mail.xml) | Mail | = | Send eMail. |  | nativo (kettle-engine) |
| [`NullIf`](steps/NullIf.xml) | Null if | Nulo si | Sets a field value to null if it is equal to a constant value |  | nativo (kettle-engine) |
| [`ProcessFiles`](steps/ProcessFiles.xml) | Process files | = | Process one file per row (copy or move or delete). This step only accept filename in input. |  | nativo (kettle-engine) |
| [`SSH`](steps/SSH.xml) | Run SSH commands | = | Run SSH commands and returns result. |  | nativo (kettle-engine) |
| [`StepMetastructure`](steps/StepMetastructure.xml) | Metadata structure of stream | = | This is a step to read the metadata of the incoming stream. | 1 | nativo (kettle-engine) |
| [`SyslogMessage`](steps/SyslogMessage.xml) | Send message to syslog | = | Send message to syslog server |  | nativo (kettle-engine) |
| [`TableCompare`](steps/TableCompare.xml) | Table compare | = | Compares 2 tables and gives back a list of differences |  | nativo (kettle-engine) |
| [`TypeExitEdi2XmlStep`](steps/TypeExitEdi2XmlStep.xml) | EDI to XML | = | Converts Edi text to generic XML | 1 | edi2xml-plugins |
| [`WriteToLog`](steps/WriteToLog.xml) | Write to log | = | Write data to log | 4 | nativo (kettle-engine) |
| [`ZipFile`](steps/ZipFile.xml) | Zip file | = | Zip a file. Filename will be extracted from incoming stream. |  | nativo (kettle-engine) |

### Validation (4)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`CreditCardValidator`](steps/CreditCardValidator.xml) | Credit card validator | = | The Credit card validator step will help you tell: (1) if a credit card number is valid (uses LUHN10 (MOD-10) algorithm) (2) which credit card vendor handles that number (VISA, MasterCard, Diners Club, EnRoute, American Express (AMEX),...) |  | nativo (kettle-engine) |
| [`MailValidator`](steps/MailValidator.xml) | Mail validator | = | Check if an email address is valid. |  | nativo (kettle-engine) |
| [`Validator`](steps/Validator.xml) | Data validator | = | Validates passing data based on a set of rules | 9 | nativo (kettle-engine) |
| [`XSDValidator`](steps/XSDValidator.xml) | XSD validator | = | Validate XML source (files or streams) against XML Schema Definition. |  | pdi-xml-plugin |

### Deprecated / Obsoleto (12)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`DummyStep`](steps/DummyStep.xml) | Example step (deprecated) | Example step | This is a plugin example step |  | kettle-dummy-plugin |
| [`ElasticSearchBulk`](steps/ElasticSearchBulk.xml) | Elasticsearch bulk insert (deprecated) | Elasticsearch bulk insert | Performs bulk inserts into ElasticSearch |  | elasticsearch-bulk-insert-plugin |
| [`GPBulkLoader`](steps/GPBulkLoader.xml) | Greenplum bulk loader (deprecated) | Greenplum bulk loader | Greenplum bulk loader |  | gp-bulk-loader-plugin |
| [`LucidDBStreamingLoader`](steps/LucidDBStreamingLoader.xml) | LucidDB streaming loader (deprecated) | LucidDB streaming loader | Load data into LucidDB by using Remote Rows UDX. |  | lucid-db-streaming-loader-plugin |
| [`OldTextFileInput`](steps/OldTextFileInput.xml) | Text file input (deprecated) | Text file input | Read data from a text file in several formats. This data can then be passed on to the next step(s)... |  | nativo (kettle-engine) |
| [`PaloCellInput`](steps/PaloCellInput.xml) | Palo cell input (deprecated) | Palo cell input | Reads data from a defined Palo Cube |  | kettle-palo-plugin |
| [`PaloCellOutput`](steps/PaloCellOutput.xml) | Palo cell output (deprecated) | Palo cell output | Writes data to a defined Palo Cube |  | kettle-palo-plugin |
| [`PaloDimInput`](steps/PaloDimInput.xml) | Palo dim input (deprecated) | Palo dim input | Reads data from a defined Palo Dimension |  | kettle-palo-plugin |
| [`PaloDimOutput`](steps/PaloDimOutput.xml) | Palo dim output (deprecated) | Palo dim output | Writes data to defined Palo Dimension |  | kettle-palo-plugin |
| [`SAPINPUT`](steps/SAPINPUT.xml) | SAP input (deprecated) | SAP input | Read data from SAP ERP, optionally with parameters |  | kettle-sap-plugin |
| [`Script`](steps/Script.xml) | Script (deprecated) | Script | Calculate values by scripting in Ruby, Python, Groovy, JavaScript, ... (JSR-223) |  | nativo (kettle-engine) |
| [`TextFileOutputLegacy`](steps/TextFileOutputLegacy.xml) | Text file output (deprecated) | Text file output | Write rows to a text file. |  | nativo (kettle-engine) |

## Job entries (jobs, `.kjb`)


### Bulk loading (3)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`MSSQL_BULK_LOAD`](job-entries/MSSQL_BULK_LOAD.xml) | Bulk load into MSSQL | = | Load data from a file into a MSSQL table |  | nativo (kettle-engine) |
| [`MYSQL_BULK_FILE`](job-entries/MYSQL_BULK_FILE.xml) | Bulk load from MySQL into file | = | Load from a mysql table into a file |  | nativo (kettle-engine) |
| [`MYSQL_BULK_LOAD`](job-entries/MYSQL_BULK_LOAD.xml) | Bulk load into MySQL | = | Load data from a file into a Mysql table |  | nativo (kettle-engine) |

### Conditions (12)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`CHECK_DB_CONNECTIONS`](job-entries/CHECK_DB_CONNECTIONS.xml) | Check DB connections | = | Check if we can connect to one or several databases. |  | pdi-core-plugins |
| [`CHECK_FILES_LOCKED`](job-entries/CHECK_FILES_LOCKED.xml) | Check files locked | = | Check if one or several files are locked by another process |  | nativo (kettle-engine) |
| [`COLUMNS_EXIST`](job-entries/COLUMNS_EXIST.xml) | Columns exist in a table | = | Check if one or several columns exist in a table on a specified connection |  | nativo (kettle-engine) |
| [`DELAY`](job-entries/DELAY.xml) | Wait for | = | Wait for a delay | 4 | nativo (kettle-engine) |
| [`EVAL_FILES_METRICS`](job-entries/EVAL_FILES_METRICS.xml) | Evaluate files metrics | = | Evaluate files size or files count |  | nativo (kettle-engine) |
| [`EVAL_TABLE_CONTENT`](job-entries/EVAL_TABLE_CONTENT.xml) | Evaluate rows number in a table | = | Evaluate the content of a table.  You can also specify a SQL query. |  | nativo (kettle-engine) |
| [`FILE_EXISTS`](job-entries/FILE_EXISTS.xml) | File exists | = | Checks if a file exists |  | nativo (kettle-engine) |
| [`FILES_EXIST`](job-entries/FILES_EXIST.xml) | Checks if files exist | = | Checks if files exists |  | nativo (kettle-engine) |
| [`FOLDER_IS_EMPTY`](job-entries/FOLDER_IS_EMPTY.xml) | Check if a folder is empty | = | Check if a folder is empty |  | nativo (kettle-engine) |
| [`SIMPLE_EVAL`](job-entries/SIMPLE_EVAL.xml) | Simple evaluation | = | Evaluate one field or variable |  | nativo (kettle-engine) |
| [`TABLE_EXISTS`](job-entries/TABLE_EXISTS.xml) | Table exists | = | Checks if a table exists on a database connection | 4 | nativo (kettle-engine) |
| [`WEBSERVICE_AVAILABLE`](job-entries/WEBSERVICE_AVAILABLE.xml) | Check webservice availability | = | Check if a webservice is available |  | nativo (kettle-engine) |

### File encryption (3)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`PGP_DECRYPT_FILES`](job-entries/PGP_DECRYPT_FILES.xml) | Decrypt files with PGP | = | Decrypt files encrypted with PGP (Pretty Good Privacy). This job entry need GnuPG to work properly. |  | nativo (kettle-engine) |
| [`PGP_ENCRYPT_FILES`](job-entries/PGP_ENCRYPT_FILES.xml) | Encrypt files with PGP | = | Encrypt files with PGP (Pretty Good Privacy). This job entry need GnuPG to work properly. |  | nativo (kettle-engine) |
| [`PGP_VERIFY_FILES`](job-entries/PGP_VERIFY_FILES.xml) | Verify file signature with PGP | = | Verify file signature with PGP (Pretty Good Privacy). This job entry need GnuPG to work properly. |  | nativo (kettle-engine) |

### File management (18)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`ADD_RESULT_FILENAMES`](job-entries/ADD_RESULT_FILENAMES.xml) | Add filenames to result | = | Add filenames to result | 2 | pdi-core-plugins |
| [`COPY_FILES`](job-entries/COPY_FILES.xml) | Copy files | = | Copy files |  | nativo (kettle-engine) |
| [`COPY_MOVE_RESULT_FILENAMES`](job-entries/COPY_MOVE_RESULT_FILENAMES.xml) | Process result filenames | = | Copy, move or delete result filenames |  | nativo (kettle-engine) |
| [`CREATE_FILE`](job-entries/CREATE_FILE.xml) | Create file | = | Create (an empty) file | 2 | nativo (kettle-engine) |
| [`CREATE_FOLDER`](job-entries/CREATE_FOLDER.xml) | Create a folder | = | Create a folder |  | nativo (kettle-engine) |
| [`DELETE_FILE`](job-entries/DELETE_FILE.xml) | Delete file | = | Delete a file | 2 | nativo (kettle-engine) |
| [`DELETE_FILES`](job-entries/DELETE_FILES.xml) | Delete files | = | Delete files |  | nativo (kettle-engine) |
| [`DELETE_FOLDERS`](job-entries/DELETE_FOLDERS.xml) | Delete folders | = | Delete specified folders. Attention : if a the folder contains files, PDI will delete them all! |  | nativo (kettle-engine) |
| [`DELETE_RESULT_FILENAMES`](job-entries/DELETE_RESULT_FILENAMES.xml) | Delete filenames from result | = | Delete filenames from result |  | nativo (kettle-engine) |
| [`DOS_UNIX_CONVERTER`](job-entries/DOS_UNIX_CONVERTER.xml) | Convert file between Windows and Unix | = | Convert file content between Windows and Unix. Converting to Unix will replace CRLF (Carriage Return and line Feed) by LF (Line Feed) |  | nativo (kettle-engine) |
| [`FILE_COMPARE`](job-entries/FILE_COMPARE.xml) | File compare | = | Compare 2 files |  | nativo (kettle-engine) |
| [`FOLDERS_COMPARE`](job-entries/FOLDERS_COMPARE.xml) | Compare folders | = | compare two folders (or two files) |  | nativo (kettle-engine) |
| [`HTTP`](job-entries/HTTP.xml) | HTTP | = | Gets or uploads a file using HTTP (HyperText Transfer Protocol) |  | nativo (kettle-engine) |
| [`MOVE_FILES`](job-entries/MOVE_FILES.xml) | Move files | = | Move files |  | nativo (kettle-engine) |
| [`UNZIP`](job-entries/UNZIP.xml) | Unzip file | = | Unzip file in a target folder |  | nativo (kettle-engine) |
| [`WAIT_FOR_FILE`](job-entries/WAIT_FOR_FILE.xml) | Wait for file | = | Wait for a file | 2 | nativo (kettle-engine) |
| [`WRITE_TO_FILE`](job-entries/WRITE_TO_FILE.xml) | Write to file | = | Write text content to file. |  | nativo (kettle-engine) |
| [`ZIP_FILE`](job-entries/ZIP_FILE.xml) | Zip file | = | Zip files from a directory and process files |  | nativo (kettle-engine) |

### File transfer (7)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`FTP`](job-entries/FTP.xml) | Get a file with FTP | = | Get files using FTP (File Transfer Protocol) |  | nativo (kettle-engine) |
| [`FTP_DELETE`](job-entries/FTP_DELETE.xml) | FTP delete | = | Delete files in a remote host |  | nativo (kettle-engine) |
| [`FTP_PUT`](job-entries/FTP_PUT.xml) | Put a file with FTP | = | Put a file with FTP |  | nativo (kettle-engine) |
| [`FTPS_GET`](job-entries/FTPS_GET.xml) | Get a file with FTPS | = | Get a file with FTP secure |  | nativo (kettle-engine) |
| [`FTPS_PUT`](job-entries/FTPS_PUT.xml) | Upload files to FTPS | = | Upload files to a FTP secure |  | nativo (kettle-engine) |
| [`SFTP`](job-entries/SFTP.xml) | Get a file with SFTP | = | Get files using Secure FTP (Secure File Transfer Protocol) |  | nativo (kettle-engine) |
| [`SFTPPUT`](job-entries/SFTPPUT.xml) | Put a file with SFTP | = | Put files using SFTP (Secure File Transfer Protocol) |  | nativo (kettle-engine) |

### General (5)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`JOB`](job-entries/JOB.xml) | Job | = | Executes a job | 2 | nativo (kettle-engine) |
| [`SET_VARIABLES`](job-entries/SET_VARIABLES.xml) | Set variables | = | Set one or several variables. |  | nativo (kettle-engine) |
| [`SPECIAL`](job-entries/SPECIAL.xml) | Special entries | = | Special entries: start and dummy | 29 | nativo (kettle-engine) |
| [`SUCCESS`](job-entries/SUCCESS.xml) | Success | = | Success | 1 | nativo (kettle-engine) |
| [`TRANS`](job-entries/TRANS.xml) | Transformation | = | Executes a transformation | 22 | nativo (kettle-engine) |

### Mail (3)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`GET_POP`](job-entries/GET_POP.xml) | Get mails (POP3/IMAP) | = | Get mails (POP3/IMAP) server and save into a local folder |  | nativo (kettle-engine) |
| [`MAIL`](job-entries/MAIL.xml) | Mail | = | Sends an e-Mail |  | nativo (kettle-engine) |
| [`MAIL_VALIDATOR`](job-entries/MAIL_VALIDATOR.xml) (alias: `JobCategory.Category.Mail_VALIDATOR`) | Mail validator | = | Check the validity of an email address |  | nativo (kettle-engine) |

### Repository (2)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`CONNECTED_TO_REPOSITORY`](job-entries/CONNECTED_TO_REPOSITORY.xml) | Check if connected to repository | = | Return true if we are connected to a repository |  | nativo (kettle-engine) |
| [`EXPORT_REPOSITORY`](job-entries/EXPORT_REPOSITORY.xml) | Export repository to XML file | = | Export repository to XML file |  | nativo (kettle-engine) |

### Scripting (3)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`EVAL`](job-entries/EVAL.xml) | JavaScript | = | Evaluates the result of the execution of a previous job entry | 15 | nativo (kettle-engine) |
| [`SHELL`](job-entries/SHELL.xml) | Shell | = | Executes a shell script | 1 | nativo (kettle-engine) |
| [`SQL`](job-entries/SQL.xml) | SQL | = | Executes SQL on a certain database connection | 7 | nativo (kettle-engine) |

### Utility (12)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`ABORT`](job-entries/ABORT.xml) | Abort job | = | Abort the job |  | pdi-core-plugins |
| [`HL7MLLPAcknowledge`](job-entries/HL7MLLPAcknowledge.xml) | HL7 MLLP acknowledge | = | Creates a HL7 MLLP client server and acknowledges the reception of a single HL7 message |  | kettle-hl7-plugin |
| [`HL7MLLPInput`](job-entries/HL7MLLPInput.xml) | HL7 MLLP input | = | Creates a HL7 MLLP client server and accepts a single HL7 message and sets this as a variable in the parent job. |  | kettle-hl7-plugin |
| [`MSGBOX_INFO`](job-entries/MSGBOX_INFO.xml) | Display msgbox info | = | Display a simple Message box Information | 2 | nativo (kettle-engine) |
| [`PING`](job-entries/PING.xml) | Ping a host | = | Ping a host |  | nativo (kettle-engine) |
| [`SEND_NAGIOS_PASSIVE_CHECK`](job-entries/SEND_NAGIOS_PASSIVE_CHECK.xml) | Send Nagios passive check | = | Send passive check to Nagios via NSCA (Nagios Service Check Acceptor) |  | nativo (kettle-engine) |
| [`SNMP_TRAP`](job-entries/SNMP_TRAP.xml) | Send SNMP trap | = | Send SNMP trap to a target host |  | nativo (kettle-engine) |
| [`SYSLOG`](job-entries/SYSLOG.xml) | Send information using syslog | = | Send information to a Syslog server |  | nativo (kettle-engine) |
| [`TELNET`](job-entries/TELNET.xml) | Telnet a host | = | Performs a telnet command |  | nativo (kettle-engine) |
| [`TRUNCATE_TABLES`](job-entries/TRUNCATE_TABLES.xml) | Truncate tables | = | Truncate one or several tables. |  | nativo (kettle-engine) |
| [`WAIT_FOR_SQL`](job-entries/WAIT_FOR_SQL.xml) | Wait for SQL | = | Scan a database and success when a specified condition on returned rows is true. |  | nativo (kettle-engine) |
| [`WRITE_TO_LOG`](job-entries/WRITE_TO_LOG.xml) | Write to log | = | Write message to log |  | nativo (kettle-engine) |

### XML (4)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`DTD_VALIDATOR`](job-entries/DTD_VALIDATOR.xml) | DTD validator | = | DTD validator |  | pdi-xml-plugin |
| [`XML_WELL_FORMED`](job-entries/XML_WELL_FORMED.xml) | Check if XML file is well formed | = | Check if one or several XML files is/are well formed |  | pdi-xml-plugin |
| [`XSD_VALIDATOR`](job-entries/XSD_VALIDATOR.xml) | XSD validator | = | XSD_VALIDATOR.Name |  | pdi-xml-plugin |
| [`XSLT`](job-entries/XSLT.xml) | XSL transformation | = | Make an XSL transformation |  | pdi-xml-plugin |

### Deprecated (5)

| `<type>` (exact id) | Name (en) | Spoon name (Spanish UI) | What it does | Uses in samples | Origin |
|---|---|---|---|---|---|
| [`DummyJob`](job-entries/DummyJob.xml) | Example job (deprecated) | = | This is an example job |  | kettle-dummy-plugin |
| [`MS_ACCESS_BULK_LOAD`](job-entries/MS_ACCESS_BULK_LOAD.xml) | MS Access bulk load (deprecated) | = | Load data into a Microsoft Access table from CSV file format. ATTENTION, at the moment only the insertion is available! If target table exists, a new one will be created and data inserted. |  | ms-access-plugins |
| [`PALO_CUBE_CREATE`](job-entries/PALO_CUBE_CREATE.xml) | Palo cube create (deprecated) | = | Creates a Palo Cube with dimensions |  | kettle-palo-plugin |
| [`PALO_CUBE_DELETE`](job-entries/PALO_CUBE_DELETE.xml) | Palo cube delete (deprecated) | = | Deletes a Palo Cube and related cell data |  | kettle-palo-plugin |
| [`TALEND_JOB_EXEC`](job-entries/TALEND_JOB_EXEC.xml) | Talend job execution (deprecated) | = | This job entry executes an exported Talend Job |  | nativo (kettle-engine) |
