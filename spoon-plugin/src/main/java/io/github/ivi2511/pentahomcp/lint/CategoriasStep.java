package io.github.ivi2511.pentahomcp.lint;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * La categoría de un step o de una entrada de job por su id de tipo, para las convenciones de nombres del perfil
 * ({@link PerfilEquipo}, naming.steps / naming.entries). No sale del PluginRegistry: ahí la categoría viene traducida
 * al idioma con que arrancó Spoon y el perfil tiene que significar lo mismo en todas las máquinas. Un tipo que no está
 * aquí no tiene categoría (solo «other», si el perfil la pone, o by_type).
 */
public final class CategoriasStep {

  public static final String INPUT = "input";
  public static final String OUTPUT = "output";
  public static final String LOOKUP = "lookup";
  public static final String FILTER = "filter";
  public static final String JOIN = "join";
  public static final String TRANSFORM = "transform";
  public static final String AGGREGATE = "aggregate";
  public static final String FLOW = "flow";
  public static final String SCRIPT = "script";
  public static final String VALIDATION = "validation";
  public static final String UTILITY = "utility";
  public static final String MAPPING = "mapping";
  /** Lo que no tiene categoría conocida. */
  public static final String OTRO = "other";

  public static final List<String> CATEGORIAS_STEP = Collections.unmodifiableList( Arrays.asList( INPUT, OUTPUT,
    LOOKUP, FILTER, JOIN, TRANSFORM, AGGREGATE, FLOW, SCRIPT, VALIDATION, UTILITY, MAPPING, OTRO ) );

  public static final String E_TRANSFORMATION = "transformation";
  public static final String E_JOB = "job";
  public static final String E_SQL = "sql";
  public static final String E_FILE = "file";
  public static final String E_CONDITION = "condition";
  public static final String E_MAIL = "mail";
  public static final String E_UTILITY = "utility";

  public static final List<String> CATEGORIAS_ENTRADA = Collections.unmodifiableList( Arrays.asList( E_TRANSFORMATION,
    E_JOB, E_SQL, E_FILE, E_CONDITION, E_MAIL, E_UTILITY, OTRO ) );

  private static final Map<String, String> STEPS = new HashMap<String, String>();
  private static final Map<String, String> ENTRADAS = new HashMap<String, String>();

  static {
    poner( STEPS, INPUT, "CsvInput", "TextFileInput", "OldTextFileInput", "ExcelInput", "TableInput", "DataGrid",
      "RowGenerator", "RandomValue", "GetFileNames", "GetSubFolders", "GetFilesRowsCount", "getXMLData", "JsonInput",
      "FixedInput", "XBaseInput", "AccessInput", "PropertyInput", "LDIFInput", "LoadFileInput", "ParallelGzipCsvInput",
      "SASInput", "YamlInput", "XMLInputStream", "CubeInput", "ShapeFileReader", "HL7Input", "SystemInfo",
      "AvroInputNew", "ParquetInput", "OrcInput", "MongoDbInput", "SalesforceInput", "RssInput", "GetTableNames",
      "GetRepositoryNames", "LDAPInput", "MailInput", "S3CSVINPUT", "GoogleAnalytics", "KafkaConsumerInput",
      "Jms2Consumer", "MQTTConsumer", "SapInput", "RowsFromResult", "GetFilesFromResult",
      "TeraFast", "CassandraInput", "HBaseInput", "HadoopFileInputPlugin" );
    poner( STEPS, OUTPUT, "TextFileOutput", "TextFileOutputLegacy", "ExcelOutput", "TypeExitExcelWriterStep",
      "TableOutput", "InsertUpdate", "Update", "Delete", "SynchronizeAfterMerge", "JsonOutput", "XMLOutput",
      "SQLFileOutput", "PropertyOutput", "CubeOutput", "AvroOutput", "ParquetOutput", "OrcOutput", "MongoDbOutput",
      "SalesforceInsert", "SalesforceUpdate", "SalesforceUpsert", "SalesforceDelete", "LDAPOutput", "AccessOutput",
      "RssOutput", "KafkaProducerOutput", "Jms2Producer", "MQTTProducer", "PGBulkLoader", "MySQLBulkLoader",
      "OraBulkLoader", "MonetDBBulkLoader", "VerticaBulkLoader", "GPBulkLoader", "GPLoad", "LucidDBStreamingLoader",
      "ElasticSearchBulk", "DimensionLookup", "CombinationLookup", "RowsToResult", "FilesToResult",
      "CassandraOutput", "HBaseOutput", "HadoopFileOutputPlugin", "TableAgileMart", "InfobrightOutput" );
    poner( STEPS, LOOKUP, "StreamLookup", "DBLookup", "DBJoin", "FuzzyMatch", "HTTP", "Rest", "HTTPPOST",
      "WebServiceLookup", "DBProc", "DynamicSQLRow", "FileExists", "TableExists", "ColumnExists", "FileLocked",
      "WebServiceAvailable" );
    poner( STEPS, FILTER, "FilterRows", "SwitchCase", "JavaFilter" );
    poner( STEPS, JOIN, "MergeJoin", "MergeRows", "JoinRows", "MultiwayMergeJoin", "SortedMerge", "XMLJoin" );
    poner( STEPS, TRANSFORM, "SelectValues", "Calculator", "Constant", "SortRows", "Unique", "UniqueRowsByHashSet",
      "ValueMapper", "StringOperations", "StringCut", "ReplaceString", "Denormaliser", "Normaliser", "Flattener",
      "FieldSplitter", "SplitFieldToRows3", "ConcatFields", "NumberRange", "SetValueField", "SetValueConstant",
      "Sequence", "FieldsChangeSequence", "AddXML", "ClosureGenerator", "IfNull", "NullIf", "XSLT", "CloneRow",
      "SampleRows", "ReservoirSampling", "DateDiff", "FieldMetadataAnnotation", "PGPEncryptStream",
      "PGPDecryptStream", "CheckSum", "SecretKeyGenerator", "SymmetricCryptoTrans", "RandomCCNumberGenerator" );
    poner( STEPS, AGGREGATE, "GroupBy", "MemoryGroupBy", "AnalyticQuery", "UnivariateStats", "StepsMetrics" );
    poner( STEPS, FLOW, "Abort", "Append", "BlockingStep", "BlockUntilStepsFinish", "DetectEmptyStream", "Dummy",
      "DetectLastRow", "PrioritizeStreams", "IdentifyLastRow" );
    poner( STEPS, SCRIPT, "ScriptValueMod", "UserDefinedJavaClass", "Janino", "Formula", "RegexEval", "ExecSQL",
      "ExecSQLRow", "RuleExecutor", "RuleAccumulator" );
    poner( STEPS, VALIDATION, "Validator", "CreditCardValidator", "MailValidator", "XSDValidator" );
    poner( STEPS, UTILITY, "WriteToLog", "SetVariable", "GetVariable", "Mail", "ExecProcess", "ZipFile", "Delay",
      "ProcessFiles", "SyslogMessage", "SSH", "StepMetastructure", "ChangeFileEncoding",
      "Edi2Xml", "TableCompare", "GetSlaveSequence", "SetSessionVariables" );
    poner( STEPS, MAPPING, "Mapping", "SimpleMapping", "MappingInput", "MappingOutput", "TransExecutor", "JobExecutor",
      "MetaInject", "SingleThreader", "Injector" );

    poner( ENTRADAS, E_TRANSFORMATION, "TRANS" );
    poner( ENTRADAS, E_JOB, "JOB" );
    poner( ENTRADAS, E_SQL, "SQL", "TRUNCATE_TABLES", "MYSQL_BULK_LOAD", "MYSQL_BULK_FILE", "MSSQL_BULK_LOAD" );
    poner( ENTRADAS, E_FILE, "CREATE_FILE", "DELETE_FILE", "DELETE_FILES", "COPY_FILES", "MOVE_FILES",
      "CREATE_FOLDER", "DELETE_FOLDERS", "ZIP_FILE", "UNZIP", "WRITE_TO_FILE", "COPY_MOVE_RESULT_FILENAMES",
      "ADD_RESULT_FILENAMES", "DELETE_RESULT_FILENAMES", "HTTP", "FTP", "SFTP", "FTP_PUT", "SFTPPUT", "FTP_DELETE",
      "FTPS_GET", "FTPS_PUT", "XSLT", "DOS_UNIX_CONVERTER", "PGP_ENCRYPT_FILES", "PGP_DECRYPT_FILES" );
    poner( ENTRADAS, E_CONDITION, "SIMPLE_EVAL", "EVAL", "CHECK_DB_CONNECTIONS", "TABLE_EXISTS", "COLUMNS_EXIST",
      "EVAL_TABLE_CONTENT", "EVAL_FILES_METRICS", "WAIT_FOR_SQL", "FILE_EXISTS", "FILES_EXIST", "FOLDER_IS_EMPTY",
      "WAIT_FOR_FILE", "FILE_COMPARE", "FOLDERS_COMPARE", "WEBSERVICE_AVAILABLE", "CHECK_FILES_LOCKED", "XSD_VALIDATOR",
      "DTD_VALIDATOR", "PING", "CONNECTED_TO_REPOSITORY", "EXPORT_REPOSITORY" );
    poner( ENTRADAS, E_MAIL, "MAIL", "GET_POP", "MAIL_VALIDATOR" );
    poner( ENTRADAS, E_UTILITY, "WRITE_TO_LOG", "SET_VARIABLES", "DELAY", "SHELL", "SYSLOG", "SNMP_TRAP",
      "MSGBOX_INFO", "TELNET", "SEND_NAGIOS_PASSIVE_CHECK", "HL7MLLPAcknowledge", "HL7MLLPInput" );
  }

  private CategoriasStep() {
  }

  private static void poner( Map<String, String> m, String cat, String... ids ) {
    for ( String id : ids ) {
      m.put( id, cat );
    }
  }

  /** input, output, lookup… o null si el tipo no tiene categoría conocida. */
  public static String deStep( String tipo ) {
    return tipo == null ? null : STEPS.get( tipo );
  }

  /** transformation, job, sql, file, condition, mail, utility, o null. */
  public static String deEntrada( String tipo ) {
    return tipo == null ? null : ENTRADAS.get( tipo );
  }
}
