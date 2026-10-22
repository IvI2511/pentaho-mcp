package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.plugins.PluginInterface;
import org.pentaho.di.core.plugins.PluginRegistry;
import org.pentaho.di.core.plugins.StepPluginType;
import org.pentaho.di.core.xml.XMLHandler;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.PoliticaPack;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCaso;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.Aviso;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.PasoBloqueado;
import io.github.ivi2511.pentahomcp.lint.Nodos;

/**
 * Analisis estatico de una entrega antes de ejecutarla (docs/corrector-diseno.md §3 paso 2 y §7): carga del XML con
 * nuestro parser (DOCTYPE prohibido), politica por tipo de step (seguro / condicional / peligroso / desconocido), D1
 * sobre el XML crudo (Kettle funde al cargar los steps cuyo nombre difiere solo en mayusculas), D2 con hasLoop,
 * entradas y salidas que toman el nombre de archivo de un campo y el SQL que escribiria archivos fuera del sandbox
 * a traves de la base SQLite del pack (ATTACH, VACUUM INTO, load_extension). Corre en la JVM hija, nunca en Spoon.
 * <p>
 * Secuencia del worker (C4): {@link #cargar} (si lanza: {@code load_failed}) → {@link #revisar} (si
 * {@link Revision#bloquea()}: la entrega queda {@code blocked} y no corre ningun caso) → por caso,
 * {@link CorrectorSandbox#preparar}. D1 y D2 son las reglas {@code duplicate_step_name} y {@code cycle} del linter;
 * aca se calculan aparte porque el linter lee el archivo del documento y necesita un Entorno, y estas dos no.
 * <p>
 * Los {@code detail} van en ingles y con los nombres tal cual: son para el docente; las pistas para el alumno las
 * arma CorrectorNota (C5) a partir de {@code reason}.
 *
 * @dueño C3
 */
final class CorrectorPolitica {

  /** Aviso: hay un step que corre SQL armado con datos de la fila (no se puede revisar antes de correr). */
  static final String AVISO_SQL_DINAMICO = "dynamic_sql";
  /** Aviso: el modo confianza dejo correr un step peligroso. */
  static final String AVISO_MODO_CONFIANZA = "trust_mode_step";

  /** Orden en que se elige el {@code reason} de la entrega cuando hay varios problemas. */
  static final List<String> PRIORIDAD = Collections.unmodifiableList( Arrays.asList( ResultadoCaso.NOMBRE_REPETIDO,
    ResultadoCaso.STEP_PELIGROSO, ResultadoCaso.STEP_DESCONOCIDO, ResultadoCaso.STEP_NO_PERMITIDO,
    ResultadoCaso.SALIDA_INESPERADA, ResultadoCaso.ENTRADA_INESPERADA, ResultadoCaso.SQL_PROHIBIDO,
    ResultadoCaso.CICLO ) );

  // ------------------------------------------------------------------ niveles (§7)

  /** Corren siempre (las rutas las revisa CorrectorSandbox): entradas de archivo y generadas, transformacion, flujo. */
  static final Set<String> SEGUROS = ids(
    // entradas
    "CsvInput", "ParallelGzipCsvInput", "TextFileInput", "FixedInput", "ExcelInput", "JsonInput", "getXMLData",
    "GetFileNames", "DataGrid", "RowGenerator", "RandomValue", "RandomCCNumberGenerator", "GetVariable", "SystemInfo",
    "RowsFromResult", "FilesFromResult",
    // transformacion
    "Calculator", "Formula", "SelectValues", "Constant", "Sequence", "SetValueConstant", "SetValueField",
    "StringOperations", "StringCut", "ReplaceString", "RegexEval", "ConcatFields", "FieldSplitter",
    "SplitFieldToRows3", "ValueMapper", "NumberRange", "IfNull", "NullIf", "CheckSum", "FieldsChangeSequence",
    "ClosureGenerator", "Denormaliser", "Normaliser", "Flattener", "SortRows", "Unique", "UniqueRowsByHashSet",
    "CreditCardValidator", "Validator",
    // flujo, uniones y estadistica
    "Dummy", "FilterRows", "SwitchCase", "Append", "Abort", "BlockingStep", "BlockUntilStepsFinish",
    "DetectEmptyStream", "DetectLastRow", "PrioritizeStreams", "Delay", "WriteToLog", "CloneRow", "StreamLookup",
    "FuzzyMatch", "MergeJoin", "MergeRows", "MultiwayMergeJoin", "SortedMerge", "JoinRows", "GroupBy",
    "MemoryGroupBy", "AnalyticQuery", "UnivariateStats", "SampleRows", "ReservoirSampling", "StepMetastructure",
    "StepsMetrics", "RowsToResult", "FilesToResult", "MappingInput", "MappingOutput",
    // salidas a archivo: CorrectorSandbox las redirige a salida/
    "TextFileOutput", "ExcelOutput", "TypeExitExcelWriterStep", "JsonOutput", "XMLOutput" );

  /** Corren si el pack los nombra en allow_steps (o «Volver a corregir con...»): base del pack, variables, codigo. */
  static final Set<String> CONDICIONALES = ids( "TableInput", "TableOutput", "InsertUpdate", "Update", "Delete",
    "DBLookup", "DBJoin", "ExecSQL", "ExecSQLRow", "DynamicSQLRow", "DimensionLookup", "CombinationLookup",
    "SynchronizeAfterMerge", "TableExists", "ColumnExists", "GetTableNames", "SetVariable", "Mapping", "SimpleMapping",
    "TransExecutor", "ScriptValueMod" );

  /** Nunca corren sin modo confianza: codigo Java, procesos, red, archivos fuera del sandbox, cargadores masivos. */
  static final Set<String> PELIGROSOS = ids( "UserDefinedJavaClass", "Janino", "JavaFilter", "Script",
    "RuleExecutor", "RuleAccumulator", "XSLT", "XSDValidator", "MetaInject", "JobExecutor", "SingleThreader",
    "ExecProcess", "SSH", "Mail", "MailInput", "MailValidator", "HTTP", "HTTPPOST", "Rest", "WebServiceLookup",
    "WebServiceAvailable", "CallEndpointStep", "SocketReader", "SocketWriter", "SFTPPut", "LDAPInput", "LDAPOutput",
    "RssInput", "RssOutput", "SyslogMessage", "GetSlaveSequence", "Jms2Consumer", "Jms2Producer", "KafkaConsumerInput",
    "KafkaProducerOutput", "MQTTConsumer", "MQTTProducer", "SalesforceInput", "SalesforceInsert", "SalesforceUpdate",
    "SalesforceUpsert", "SalesforceDelete", "TypeExitGoogleAnalyticsInputStep", "CassandraInput", "CassandraOutput",
    "SSTableOutput", "MongoDbInput", "MongoDbOutput", "ElasticSearchBulk", "OlapInput", "MondrianInput",
    "PaloCellInput", "PaloCellOutput", "PaloDimInput", "PaloDimOutput", "SAPINPUT", "S3CSVINPUT",
    "S3FileOutputPlugin", "AvroInputNew", "AvroOutput", "ProcessFiles", "DeleteFile", "ZipFile",
    "ChangeFileEncoding", "SQLFileOutput", "PropertyOutput", "TextFileOutputLegacy", "CubeOutput", "AccessOutput",
    "AutoDoc", "PentahoReportingOutput", "PGPEncryptStream", "PGPDecryptStream", "DBProc", "GPBulkLoader", "GPLoad",
    "InfobrightOutput", "LucidDBStreamingLoader", "MonetDBBulkLoader", "MySQLBulkLoader", "OraBulkLoader",
    "PGBulkLoader", "TeraDataBulkLoader", "TeraFast", "VerticaBulkLoader", "VectorWiseBulkLoader",
    "SetSessionVariableStep", "GetSessionVariableStep" );

  /**
   * Un id que no esta en ninguna lista pero suena a red, procesos o codigo: peligroso aunque el pack ponga
   * {@code unknown_steps: allow} (cubre plugins de terceros que no conocemos).
   */
  static final Pattern RIESGO = Pattern.compile( "(?i).*(bulk|loader|shell|exec|script|java|janino|groovy|python|"
    + "http|rest|soap|webservice|endpoint|mail|ftp|ssh|scp|socket|ldap|jms|kafka|mqtt|amqp|s3|hdfs|hadoop|hbase|hive|"
    + "spark|salesforce|sap|process|command|cmd|delete|remove|zip|crypt|pgp|rule|inject|executor).*" );

  /** Alias que el XML puede traer en lugar del id principal del plugin. */
  private static final Map<String, String> ALIAS = new HashMap<String, String>();

  static {
    ALIAS.put( "scriptvalue", "ScriptValueMod" );
    ALIAS.put( "flatterner", "Flattener" );
    ALIAS.put( "terafastplugin", "TeraFast" );
  }

  /** Entradas de job (§7), para cuando el corrector acepte .kjb [NP]: hoy {@link #cargar} solo acepta .ktr. */
  static final Set<String> ENTRADAS_SEGURAS = ids( "SPECIAL", "SUCCESS", "ABORT", "DUMMY", "WRITE_TO_LOG", "DELAY",
    "SIMPLE_EVAL", "FILE_EXISTS", "FILES_EXIST", "FOLDER_IS_EMPTY", "TRANS" );
  static final Set<String> ENTRADAS_CONDICIONALES = ids( "JOB", "SET_VARIABLES", "TABLE_EXISTS", "COLUMNS_EXIST",
    "EVAL_TABLE_CONTENT", "CHECK_DB_CONNECTIONS" );
  static final Set<String> ENTRADAS_PELIGROSAS = ids( "SHELL", "SQL", "EVAL", "FTP", "FTP_PUT", "FTP_DELETE",
    "FTPS_GET", "FTPS_PUT", "SFTP", "SFTPPUT", "HTTP", "MAIL", "GET_POP", "MAIL_VALIDATOR", "DELETE_FILE",
    "DELETE_FILES", "DELETE_FOLDERS", "DELETE_RESULT_FILENAMES", "COPY_FILES", "MOVE_FILES",
    "COPY_MOVE_RESULT_FILENAMES", "CREATE_FILE", "CREATE_FOLDER", "WRITE_TO_FILE", "UNZIP", "ZIP_FILE",
    "DOS_UNIX_CONVERTER", "PGP_ENCRYPT_FILES", "PGP_DECRYPT_FILES", "PGP_VERIFY_FILES", "MSSQL_BULK_LOAD",
    "MYSQL_BULK_FILE", "MYSQL_BULK_LOAD", "MS_ACCESS_BULK_LOAD", "TRUNCATE_TABLES", "WAIT_FOR_SQL", "PING", "TELNET",
    "SNMP_TRAP", "SEND_NAGIOS_PASSIVE_CHECK", "SYSLOG", "EXPORT_REPOSITORY", "TALEND_JOB_EXEC", "HL7MLLPInput",
    "HL7MLLPAcknowledge", "WEBSERVICE_AVAILABLE", "XSLT", "DTD_VALIDATOR", "XSD_VALIDATOR" );

  /** Steps que corren SQL armado con los datos de la fila: el texto no esta en el .ktr (§7, SQLite del pack). */
  static final Set<String> SQL_DINAMICO = ids( "ExecSQLRow", "DynamicSQLRow" );

  // ------------------------------------------------------------------ SQL que sale del sandbox (§7)

  /** ATTACH [DATABASE] expr AS nombre: crea o abre otro archivo en cualquier ruta. */
  private static final Pattern ATTACH = Pattern.compile( "(?is)\\battach\\b(?:\\s*\\bdatabase\\b)?\\s*\\S.*?\\bas\\b" );
  /** VACUUM [esquema] INTO 'archivo' (SQLite 3.27, el sqlite-jdbc de PDI 9.4). */
  private static final Pattern VACUUM_INTO = Pattern.compile( "(?is)\\bvacuum\\b[^;]*?\\binto\\b" );
  private static final Pattern CARGAR_EXTENSION = Pattern.compile( "(?i)\\bload_extension\\s*\\(" );
  private static final Pattern PRAGMA_DIRECTORIO = Pattern.compile(
    "(?is)\\bpragma\\b[^;]*?\\b(temp_store_directory|data_store_directory)\\b" );
  private static final Pattern COMENTARIO = Pattern.compile( "(?s)/\\*.*?(\\*/|$)|--[^\\n]*" );

  private CorrectorPolitica() {
  }

  // ------------------------------------------------------------------ carga

  /**
   * Una entrega cargada para el analisis: el XML crudo, la TransMeta del XML saneado y el XML que Kettle regenera de
   * esa TransMeta. La politica y el sandbox miran el regenerado y no el crudo: Kettle lee los tags sin mayusculas,
   * toma el primero que tiene valor y, si el primer hijo es un comentario, su texto ({@code <type><!--ExecProcess-->
   * Dummy</type>} corre un ExecProcess). El regenerado es lo que de verdad correria, sin esas ambiguedades, y es lo
   * que CorrectorSandbox reescribe y corre.
   */
  static final class Entrega {

    final File archivo;
    /** El XML tal como esta en el disco: solo para D1 (al cargar, Kettle funde los repetidos). No se modifica. */
    final Element crudo;
    /** Cargada de {@link #saneado} sin nombre de archivo ni variables internas: solo para analizar. */
    final TransMeta tm;
    /** {@code tm.getXML()}: lo que Kettle entendio, como lo escribiria Spoon al guardar. */
    final Element kettle;

    Entrega( File archivo, Element crudo, TransMeta tm, Element kettle ) {
      this.archivo = archivo;
      this.crudo = crudo;
      this.tm = tm;
      this.kettle = kettle;
    }

    /** Los {@code <step>} del XML regenerado, en orden (los de D1 ya fundidos). */
    List<Element> steps() {
      return Nodos.hijos( kettle, "step" );
    }
  }

  /**
   * Lee la entrega con nuestro parser (sin DTD ni entidades externas: un DOCTYPE lanza, P19), arma la TransMeta del
   * XML saneado, sin leer el {@code shared_objects_file} que la entrega nombre, y la vuelve a escribir con getXML. Si
   * lanza, la entrega es {@code load_failed} con el mensaje. Solo transformaciones: los jobs son [NP].
   */
  static Entrega cargar( File ktr ) throws Exception {
    Element crudo = leerCrudo( ktr );
    if ( !"transformation".equalsIgnoreCase( crudo.getTagName() ) ) {
      throw new IllegalArgumentException( "not a transformation (root <" + crudo.getTagName()
        + ">): only .ktr submissions are graded" );
    }
    TransMeta tm = new TransMeta( saneado( crudo ), null );
    return new Entrega( ktr, crudo, tm, Nodos.parse( tm.getXML() ) );
  }

  /** El {@code shared_objects_file} que Kettle leeria del XML crudo (vacio si no hay). */
  static String objetosCompartidos( Element crudo ) {
    String v = XMLHandler.getTagValue( XMLHandler.getSubNode( crudo, "info" ), "shared_objects_file" );
    return v == null ? "" : v.trim();
  }

  static Element leerCrudo( File ktr ) throws Exception {
    try ( InputStream in = new FileInputStream( ktr ) ) {
      return Nodos.parse( in );
    }
  }

  /**
   * Copia del XML sin lo que TransMeta leeria de afuera al cargarlo: el {@code shared_objects_file} (conexiones de
   * otro archivo, por ejemplo el shared.xml del docente). Sin el, Kettle lee el de KETTLE_HOME, que en la hija esta
   * vacio (§7).
   */
  static Element saneado( Element crudo ) {
    Element c = (Element) crudo.cloneNode( true );
    for ( Element info : hijosSinMayusculas( c, "info" ) ) { // Kettle busca los tags sin mayusculas
      for ( Element so : hijosSinMayusculas( info, "shared_objects_file" ) ) {
        so.setTextContent( "" );
      }
    }
    return c;
  }

  /** Hijos elemento con ese tag sin importar mayusculas, como los busca XMLHandler. */
  static List<Element> hijosSinMayusculas( Element e, String tag ) {
    List<Element> l = new ArrayList<Element>();
    for ( Node n = e == null ? null : e.getFirstChild(); n != null; n = n.getNextSibling() ) {
      if ( n instanceof Element && n.getNodeName().equalsIgnoreCase( tag ) ) {
        l.add( (Element) n );
      }
    }
    return l;
  }

  // ------------------------------------------------------------------ revision

  /** Lo que encontro {@link #revisar}: si bloquea, por que, y que steps no pueden correr. */
  static final class Revision {

    /** Uno de {@link ResultadoCaso#MOTIVOS}, elegido por {@link #PRIORIDAD}; null si la entrega puede correr. */
    String motivo;
    /** Para el docente, en ingles, con los nombres de la entrega. */
    String detalle;
    /** Steps que la politica no deja correr (nivel, campo, SQL), en el orden del XML. */
    final List<PasoBloqueado> bloqueados = new ArrayList<PasoBloqueado>();
    /** D1: los nombres repetidos tal como estan en el XML (todos los de cada grupo). */
    final List<String> nombresRepetidos = new ArrayList<String>();
    /** D2: steps que estan en un ciclo de hops habilitados. */
    final List<String> enCiclo = new ArrayList<String>();
    final List<Aviso> avisos = new ArrayList<Aviso>();
    /** Nombre del step (del XML) → nivel de {@link PoliticaPack#NIVELES}. */
    final Map<String, String> niveles = new LinkedHashMap<String, String>();
    /** Corrio algun step condicional: el worker se descarta despues (§7, worker reciclado). */
    boolean usaCondicionales;
    /** El modo confianza dejo pasar un step peligroso o desconocido. */
    boolean usaPeligrosos;
    /** Algun step (o el log de la transformacion) usa una conexion. */
    boolean usaBase;

    boolean bloquea() {
      return motivo != null;
    }

    /** Pasa la revision al resultado: {@code blocked_steps}, avisos y, si bloquea, estado, motivo y detalle. */
    void aplicar( ResultadoCorreccion r ) {
      for ( PasoBloqueado p : bloqueados ) {
        r.stepsBloqueados.add( new PasoBloqueado( p.step, p.tipo, p.nivel, p.motivo ) );
      }
      r.avisos.addAll( avisos );
      if ( bloquea() ) {
        r.estado = ResultadoCorreccion.BLOQUEADA;
        r.motivo = motivo;
        r.detalle = detalle;
      }
    }

    /** Marca un caso como bloqueado por la revision (no hace nada si no bloquea). */
    void aplicar( ResultadoCaso c ) {
      if ( !bloquea() ) {
        return;
      }
      if ( c.corrida == null ) {
        c.corrida = new ResultadoCaso.Corrida();
      }
      c.corrida.estado = ResultadoCaso.BLOQUEADO;
      c.corrida.motivo = motivo;
      c.corrida.detalle = detalle;
    }
  }

  /**
   * La politica y los chequeos estaticos sobre una entrega cargada. No ejecuta nada ni toca el disco.
   *
   * @param modoConfianza solo desde la UI (§7): deja correr peligrosos, condicionales y desconocidos que existen
   * @param permitidosExtra steps condicionales habilitados para esta corrida ademas de {@code allow_steps}; no
   *   habilitan peligrosos
   */
  static Revision revisar( Entrega e, Pack pack, boolean modoConfianza, Collection<String> permitidosExtra ) {
    Revision r = new Revision();
    PoliticaPack pol = pack == null || pack.politica == null ? new PoliticaPack() : pack.politica;
    List<Element> steps = e.steps();
    Map<String, List<String>> detalles = new LinkedHashMap<String, List<String>>();

    // D1 sobre el XML crudo
    r.nombresRepetidos.addAll( nombresRepetidos( e.crudo ) );
    if ( !r.nombresRepetidos.isEmpty() ) {
      anotar( detalles, ResultadoCaso.NOMBRE_REPETIDO, "duplicate step names (Kettle merges them when loading): "
        + citar( r.nombresRepetidos ) );
    }

    // niveles
    for ( Element s : steps ) {
      String nombre = Nodos.crudo( s, "name" );
      String tipo = Nodos.txt( s, "type" );
      String canon = canonico( tipo );
      boolean falta = faltaPlugin( e.tm, nombre, tipo );
      String nivel = falta ? PoliticaPack.DESCONOCIDO : nivel( tipo );
      r.niveles.put( nombre, nivel );
      String motivo = null;
      String texto = null;
      if ( PoliticaPack.CONDICIONAL.equals( nivel ) ) {
        r.usaCondicionales = true;
        if ( !modoConfianza && !pol.permite( canon ) && !pol.permite( tipo ) && !contiene( permitidosExtra, canon )
          && !contiene( permitidosExtra, tipo ) ) {
          motivo = ResultadoCaso.STEP_NO_PERMITIDO;
          texto = "step " + q( nombre ) + " (" + tipo + ") is conditional and the pack does not list it in allow_steps";
        }
      } else if ( PoliticaPack.PELIGROSO.equals( nivel ) ) {
        if ( modoConfianza ) {
          r.usaPeligrosos = true;
          r.usaCondicionales = true;
          r.avisos.add( aviso( AVISO_MODO_CONFIANZA, "step " + q( nombre ) + " (" + tipo + ") ran in trust mode",
            nombre ) );
        } else {
          motivo = ResultadoCaso.STEP_PELIGROSO;
          texto = "step " + q( nombre ) + " (" + tipo + ") is dangerous: it only runs in trust mode";
        }
      } else if ( PoliticaPack.DESCONOCIDO.equals( nivel ) ) {
        boolean deja = falta ? !pol.bloqueaDesconocidos() : modoConfianza || !pol.bloqueaDesconocidos();
        if ( deja ) {
          r.usaPeligrosos |= modoConfianza;
          r.usaCondicionales = true;
        } else {
          motivo = ResultadoCaso.STEP_DESCONOCIDO;
          texto = "step " + q( nombre ) + " (" + tipo + ") " + ( falta ? "uses a plugin that is not installed"
            : "is not in the grader's policy" ) + " and the pack blocks unknown steps";
        }
      }
      if ( motivo != null ) {
        r.bloqueados.add( new PasoBloqueado( nombre, tipo, nivel, motivo ) );
        anotar( detalles, motivo, texto );
      }
    }

    // entradas y salidas cuyo archivo sale de un campo (no se pueden redirigir ni revisar)
    for ( Element s : steps ) {
      String[] dc = desdeCampo( s, e.tm );
      if ( dc != null ) {
        String nombre = Nodos.crudo( s, "name" );
        r.bloqueados.add( new PasoBloqueado( nombre, Nodos.txt( s, "type" ), r.niveles.get( nombre ), dc[ 0 ] ) );
        anotar( detalles, dc[ 0 ], dc[ 1 ] );
      }
    }

    // SQL contra la base del pack que crearia o leeria archivos fuera del sandbox
    r.usaBase = usaBase( e );
    if ( r.usaBase ) {
      Set<Element> yaBloqueados = new HashSet<Element>();
      for ( Element[] hp : Nodos.hojas( e.kettle ) ) {
        String prohibido = sqlProhibido( hp[ 0 ].getTextContent() );
        if ( prohibido == null ) {
          continue;
        }
        Element dueno = hijoDeRaiz( e.kettle, hp[ 0 ] );
        String donde;
        if ( dueno != null && "step".equals( dueno.getTagName() ) ) {
          String nombre = Nodos.crudo( dueno, "name" );
          donde = "step " + q( nombre ) + " (" + Nodos.txt( dueno, "type" ) + ")";
          if ( yaBloqueados.add( dueno ) ) {
            r.bloqueados.add( new PasoBloqueado( nombre, Nodos.txt( dueno, "type" ), r.niveles.get( nombre ),
              ResultadoCaso.SQL_PROHIBIDO ) );
          }
        } else if ( dueno != null && "connection".equals( dueno.getTagName() ) ) {
          donde = "connection " + q( Nodos.txt( dueno, "name" ) );
        } else {
          donde = "<" + hp[ 0 ].getTagName() + ">";
        }
        anotar( detalles, ResultadoCaso.SQL_PROHIBIDO, donde + ": " + prohibido
          + " is not allowed (it would create or read files outside the sandbox)" );
      }
      for ( Element s : steps ) {
        String tipo = Nodos.txt( s, "type" );
        if ( SQL_DINAMICO.contains( canonico( tipo ).toLowerCase( Locale.ROOT ) ) ) {
          r.avisos.add( aviso( AVISO_SQL_DINAMICO, "step " + q( Nodos.crudo( s, "name" ) ) + " (" + tipo
            + ") runs SQL built from the rows: it cannot be checked before running", Nodos.crudo( s, "name" ) ) );
        }
      }
    }

    // D2
    r.enCiclo.addAll( stepsEnCiclo( e.tm ) );
    if ( !r.enCiclo.isEmpty() ) {
      anotar( detalles, ResultadoCaso.CICLO, "enabled hops form a loop through " + citar( r.enCiclo ) );
    }

    for ( String m : PRIORIDAD ) {
      List<String> d = detalles.get( m );
      if ( d != null && !d.isEmpty() ) {
        r.motivo = m;
        r.detalle = d.get( 0 ) + ( d.size() > 1 ? " (+" + ( d.size() - 1 ) + " more)" : "" );
        break;
      }
    }
    return r;
  }

  // ------------------------------------------------------------------ piezas

  /** Nivel del §7 de un id de plugin de step (sin mayusculas, con alias). */
  static String nivel( String idPlugin ) {
    if ( idPlugin == null || idPlugin.trim().isEmpty() ) {
      return PoliticaPack.DESCONOCIDO;
    }
    String k = canonico( idPlugin ).toLowerCase( Locale.ROOT );
    if ( SEGUROS.contains( k ) ) {
      return PoliticaPack.SEGURO;
    }
    if ( CONDICIONALES.contains( k ) ) {
      return PoliticaPack.CONDICIONAL;
    }
    if ( PELIGROSOS.contains( k ) || RIESGO.matcher( idPlugin ).matches() ) {
      return PoliticaPack.PELIGROSO;
    }
    return PoliticaPack.DESCONOCIDO;
  }

  /** Nivel de una entrada de job (§7) [NP]: los jobs todavia no se corrigen. */
  static String nivelEntrada( String idEntrada ) {
    String k = idEntrada == null ? "" : idEntrada.trim().toLowerCase( Locale.ROOT );
    if ( ENTRADAS_SEGURAS.contains( k ) ) {
      return PoliticaPack.SEGURO;
    }
    if ( ENTRADAS_CONDICIONALES.contains( k ) ) {
      return PoliticaPack.CONDICIONAL;
    }
    if ( ENTRADAS_PELIGROSAS.contains( k ) || RIESGO.matcher( k ).matches() ) {
      return PoliticaPack.PELIGROSO;
    }
    return PoliticaPack.DESCONOCIDO;
  }

  /** El id principal del plugin: ScriptValue → ScriptValueMod. Usa el registro de Kettle si esta cargado. */
  static String canonico( String id ) {
    if ( id == null ) {
      return "";
    }
    String t = id.trim();
    String a = ALIAS.get( t.toLowerCase( Locale.ROOT ) );
    if ( a != null ) {
      return a;
    }
    try {
      PluginInterface p = PluginRegistry.getInstance().findPluginWithId( StepPluginType.class, t );
      if ( p != null && p.getIds() != null && p.getIds().length > 0 && p.getIds()[ 0 ] != null ) {
        return p.getIds()[ 0 ];
      }
    } catch ( Throwable ignored ) {
      // sin Kettle: el id tal cual
    }
    return t;
  }

  /** El step de ese nombre y tipo cargo como MissingTrans (el plugin no esta instalado). */
  private static boolean faltaPlugin( TransMeta tm, String nombre, String tipo ) {
    StepMeta sm = tm == null ? null : tm.findStep( nombre );
    if ( sm != null && tipo.equalsIgnoreCase( sm.getStepID() ) ) {
      return sm.isMissing();
    }
    try { // fundido por D1: se pregunta al registro
      return PluginRegistry.getInstance().getPlugins( StepPluginType.class ).size() > 0
        && PluginRegistry.getInstance().findPluginWithId( StepPluginType.class, tipo ) == null;
    } catch ( Throwable t ) {
      return false;
    }
  }

  /**
   * D1: nombres de {@code <step>} del XML crudo que se repiten sin importar mayusculas (Kettle los funde con
   * findStep + addOrReplaceStep). Leidos como los lee Kettle (XMLHandler: tags sin mayusculas, primer hijo) y sin
   * recortar: «X» y «X » son distintos para Kettle. Todos los de cada grupo, en orden.
   */
  static List<String> nombresRepetidos( Element crudo ) {
    Map<String, List<String>> grupos = new LinkedHashMap<String, List<String>>();
    for ( Element s : hijosSinMayusculas( crudo, "step" ) ) {
      String n = XMLHandler.getTagValue( s, "name" );
      n = n == null ? "" : n;
      String k = n.toLowerCase( Locale.ROOT );
      List<String> g = grupos.get( k );
      if ( g == null ) {
        g = new ArrayList<String>();
        grupos.put( k, g );
      }
      g.add( n );
    }
    List<String> out = new ArrayList<String>();
    for ( List<String> g : grupos.values() ) {
      if ( g.size() > 1 ) {
        out.addAll( g );
      }
    }
    return out;
  }

  /**
   * D2: steps en un ciclo de hops habilitados: hasLoop, mas un hop habilitado de un step a si mismo, que hasLoop no
   * ve (es lo que deja D1 al fundir dos steps unidos por un hop). Si hasLoop revienta, ciclo: mejor no correr.
   */
  static List<String> stepsEnCiclo( TransMeta tm ) {
    Set<StepMeta> aSiMismos = new HashSet<StepMeta>();
    for ( int i = 0; i < tm.nrTransHops(); i++ ) {
      org.pentaho.di.trans.TransHopMeta h = tm.getTransHop( i );
      if ( h.isEnabled() && h.getFromStep() != null && h.getFromStep() == h.getToStep() ) {
        aSiMismos.add( h.getFromStep() );
      }
    }
    List<String> l = new ArrayList<String>();
    for ( StepMeta s : tm.getSteps() ) {
      boolean ciclo = aSiMismos.contains( s );
      try {
        ciclo |= tm.hasLoop( s );
      } catch ( Throwable t ) { // StackOverflowError con grafos raros
        ciclo = true;
      }
      if ( ciclo ) {
        l.add( s.getName() );
      }
    }
    return l;
  }

  /**
   * Si el step toma el nombre del archivo (o una URL) de un campo, {motivo, detalle}; si no, null. Esas rutas no se
   * pueden redirigir ni revisar antes de correr (§3): Text file input y Excel input con «aceptar nombres de archivo»,
   * CSV input con un step previo (lee los nombres del campo filename_field), JSON/XML input con la fuente en un campo
   * o una URL, Get file names con «nombres desde un campo», Execute row SQL con el SQL en un archivo y Text file output
   * con el nombre en un campo (escribiria donde diga el campo: {@code unexpected_output}).
   */
  static String[] desdeCampo( Element s, TransMeta tm ) {
    String nombre = Nodos.crudo( s, "name" );
    String tipo = Nodos.txt( s, "type" );
    String canon = canonico( tipo ).toLowerCase( Locale.ROOT );
    String quien = "step " + q( nombre ) + " (" + tipo + ")";
    if ( si( s, "fileNameInField" ) ) {
      return new String[] { ResultadoCaso.SALIDA_INESPERADA, quien + " writes to the file named in field "
        + q( Nodos.txt( s, "fileNameField" ) ) + ": it cannot be redirected to the sandbox" };
    }
    if ( si( s, "accept_filenames" ) ) {
      return new String[] { ResultadoCaso.ENTRADA_INESPERADA, quien + " reads the files named in field "
        + q( Nodos.txt( s, "accept_field" ) ) + ": they cannot be checked before running" };
    }
    if ( ( canon.equals( "jsoninput" ) || canon.equals( "getxmldata" ) || canon.equals( "loadfileinput" )
      || canon.equals( "yamlinput" ) ) && ( si( s, "readurl" ) || si( s, "IsInFields" ) && si( s, "IsAFile" ) ) ) {
      return new String[] { ResultadoCaso.ENTRADA_INESPERADA, quien + " reads " + ( si( s, "readurl" )
        ? "URLs (readurl)" : "the files named in a field" ) + ": they cannot be checked before running" };
    }
    if ( ( canon.equals( "getfilenames" ) || canon.equals( "getfilesrowscount" ) ) && si( s, "filefield" ) ) {
      return new String[] { ResultadoCaso.ENTRADA_INESPERADA, quien
        + " takes the folder from a field: it cannot be checked before running" };
    }
    if ( canon.equals( "execsqlrow" ) && si( s, "sqlFromfile" ) ) {
      return new String[] { ResultadoCaso.ENTRADA_INESPERADA, quien
        + " reads SQL from the files named in a field: they cannot be checked before running" };
    }
    if ( canon.equals( "csvinput" ) || canon.equals( "parallelgzipcsvinput" ) ) {
      StepMeta sm = tm == null ? null : tm.findStep( nombre );
      if ( sm != null && tm.findNrPrevSteps( sm ) > 0 ) {
        return new String[] { ResultadoCaso.ENTRADA_INESPERADA, quien + " has an incoming hop, so it reads the files "
          + "named in field " + q( Nodos.txt( s, "filename_field" ) ) + ": they cannot be checked before running" };
      }
    }
    return null;
  }

  /** Algun step o el log de la transformacion usa una conexion (hojas {@code <connection>} o getUsedDatabaseConnections). */
  static boolean usaBase( Entrega e ) {
    return !conexionesUsadas( e ).isEmpty();
  }

  /**
   * Nombres de conexion que algo usa: toda hoja {@code <connection>} con texto (steps, tablas de log, maxdate,
   * dependencias) y lo que declaran los steps con getUsedDatabaseConnections. En el orden en que aparecen.
   */
  static Set<String> conexionesUsadas( Entrega e ) {
    Set<String> l = new LinkedHashSet<String>();
    for ( Element[] hp : Nodos.hojas( e.kettle ) ) {
      if ( "connection".equals( hp[ 0 ].getTagName() ) ) {
        String n = hp[ 0 ].getTextContent().trim();
        if ( !n.isEmpty() ) {
          l.add( n );
        }
      }
    }
    if ( e.tm != null ) {
      for ( StepMeta s : e.tm.getSteps() ) {
        try {
          DatabaseMeta[] dbs = s.getStepMetaInterface().getUsedDatabaseConnections();
          if ( dbs != null ) {
            for ( DatabaseMeta d : dbs ) {
              if ( d != null && d.getName() != null && !d.getName().trim().isEmpty() ) {
                l.add( d.getName().trim() );
              }
            }
          }
        } catch ( Throwable ignored ) {
          // el step no sabe decirlo: queda lo del XML
        }
      }
    }
    return l;
  }

  /**
   * Lo que el texto haria con la base SQLite del pack fuera del sandbox: "ATTACH DATABASE", "VACUUM INTO",
   * "load_extension" o "PRAGMA temp_store_directory"; null si nada. Sin comentarios SQL ({@code /* *}{@code /} y
   * {@code --}), que SQLite toma como espacios. Puede dar falsos positivos con texto comun («attach ... as»): se
   * mira solo si la entrega usa una base.
   */
  static String sqlProhibido( String texto ) {
    if ( texto == null || texto.trim().isEmpty() ) {
      return null;
    }
    String t = COMENTARIO.matcher( texto ).replaceAll( " " );
    if ( ATTACH.matcher( t ).find() ) {
      return "ATTACH DATABASE";
    }
    if ( VACUUM_INTO.matcher( t ).find() ) {
      return "VACUUM INTO";
    }
    if ( CARGAR_EXTENSION.matcher( t ).find() ) {
      return "load_extension";
    }
    Matcher m = PRAGMA_DIRECTORIO.matcher( t );
    if ( m.find() ) {
      return "PRAGMA " + m.group( 1 ).toLowerCase( Locale.ROOT );
    }
    return null;
  }

  // ------------------------------------------------------------------ ayudas

  private static Set<String> ids( String... ids ) {
    Set<String> s = new HashSet<String>();
    for ( String i : ids ) {
      s.add( i.toLowerCase( Locale.ROOT ) );
    }
    return Collections.unmodifiableSet( s );
  }

  /** Hijo directo con valor Y (sin mayusculas). */
  private static boolean si( Element s, String tag ) {
    for ( Element h : Nodos.hijos( s, null ) ) {
      if ( h.getTagName().equalsIgnoreCase( tag ) ) {
        String v = h.getTextContent().trim();
        return v.equalsIgnoreCase( "Y" ) || v.equalsIgnoreCase( "true" );
      }
    }
    return false;
  }

  private static boolean contiene( Collection<String> l, String id ) {
    if ( l == null || id == null ) {
      return false;
    }
    for ( String s : l ) {
      if ( s != null && s.trim().equalsIgnoreCase( id.trim() ) ) {
        return true;
      }
    }
    return false;
  }

  /** El hijo directo de la raiz que contiene a e (el {@code <step>}, la {@code <connection>}, {@code <info>}...). */
  static Element hijoDeRaiz( Element raiz, Element e ) {
    Node n = e;
    while ( n != null && n.getParentNode() != raiz ) {
      n = n.getParentNode();
    }
    return n instanceof Element ? (Element) n : null;
  }

  private static void anotar( Map<String, List<String>> d, String motivo, String texto ) {
    List<String> l = d.get( motivo );
    if ( l == null ) {
      l = new ArrayList<String>();
      d.put( motivo, l );
    }
    l.add( texto );
  }

  private static Aviso aviso( String codigo, String detalle, String step ) {
    Aviso a = new Aviso( codigo, detalle );
    a.step = step;
    return a;
  }

  static String q( String s ) {
    return "'" + ( s == null ? "" : s ) + "'";
  }

  private static String citar( List<String> nombres ) {
    StringBuilder sb = new StringBuilder();
    for ( String n : new LinkedHashSet<String>( nombres ) ) {
      sb.append( sb.length() == 0 ? "" : ", " ).append( q( n ) );
    }
    return sb.toString();
  }
}
