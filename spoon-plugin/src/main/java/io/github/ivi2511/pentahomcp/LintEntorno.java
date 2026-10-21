package io.github.ivi2511.pentahomcp;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.variables.VariableSpace;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.steps.tableinput.TableInputMeta;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.lint.BaseFaltante;
import io.github.ivi2511.pentahomcp.lint.Entorno;
import io.github.ivi2511.pentahomcp.lint.TiempoAgotado;

/**
 * Lo que el motor del linter (lint/) le pide al plugin: campos con timeout en el pool de RunMotor (nunca contra una
 * base de archivo que no existe, que Kettle crearía vacía: {@link #baseFaltante}), las salidas
 * normales de TransOps y la lista blanca de solo lectura del modo profundo (docs/linter-diseno.md §3.3, §5). Vive
 * en el paquete base porque esas clases son package-private. Una instancia por análisis, sobre la copia.
 */
final class LintEntorno implements Entorno {

  /** Lo máximo que se espera por los campos de un step (getPrevStepFields puede conectarse a la BD). */
  static final long MS_CAMPOS = 20000;
  static final long MS_TOTAL = 120000;

  /**
   * Steps que solo leen (§3.3): entradas de archivo, Table input, Data grid y generadores; transformación,
   * filtro, orden, joins y búsquedas en memoria. Una lista negra no alcanza: RunMotor.escribe deja pasar en 9.4
   * Call DB procedure, Dynamic SQL row, REST/HTTP, Trans/Job executor, Mapping, Metadata injection, SSH, Zip,
   * SFTP put, Set session variables, JavaScript/UDJC y productores de colas. Sin probar contra los 216 tipos [NP].
   * Además, {@link #soloLectura} rechaza los steps cuyo plugin falta, las entradas que escriben archivos de errores, los
   * que se conectarían a una base de archivo que no existe (la crearían vacía) y los Table input cuya consulta no es un
   * SELECT/WITH que solo lee o cuya conexión corre SQL al conectarse ({@link #sqlQueNoSoloLee}).
   */
  static final Set<String> SOLO_LECTURA = new HashSet<String>( Arrays.asList(
    // entrada
    "CsvInput", "DataGrid", "FixedInput", "RandomValue", "RowGenerator", "getXMLData", "GetFileNames",
    "GetFilesRowsCount", "GetSubFolders", "SystemInfo", "ParallelGzipCsvInput", "JsonInput", "LoadFileInput",
    "AccessInput", "ExcelInput", "PropertyInput", "TableInput", "TextFileInput", "OldTextFileInput", "XBaseInput",
    "XMLInputStream", "YamlInput", "SASInput", "LDIFInput", "ShapeFileReader", "CubeInput", "HL7Input",
    // transformación
    "CheckSum", "Constant", "FieldsChangeSequence", "AddXML", "Calculator", "ClosureGenerator", "ConcatFields",
    "NumberRange", "ReplaceString", "Denormaliser", "Flattener", "Normaliser", "SelectValues", "SetValueField",
    "SetValueConstant", "SortRows", "SplitFieldToRows3", "FieldSplitter", "StringOperations", "StringCut",
    "Unique", "UniqueRowsByHashSet", "ValueMapper", "Formula", "RegexEval",
    // flujo
    "Dummy", "FilterRows", "SwitchCase", "Append", "BlockUntilStepsFinish", "BlockingStep", "DetectEmptyStream",
    "DetectLastRow", "PrioritizeStreams", "Abort",
    // joins, búsquedas en memoria, estadística, utilidades sin efectos
    "JoinRows", "MergeJoin", "MergeRows", "MultiwayMergeJoin", "SortedMerge", "XMLJoin", "StreamLookup", "FuzzyMatch",
    "AnalyticQuery", "GroupBy", "MemoryGroupBy", "ReservoirSampling", "SampleRows", "UnivariateStats",
    "CloneRow", "IfNull", "NullIf", "WriteToLog", "StepMetastructure", "CreditCardValidator", "Validator",
    "GetVariable", "RowsFromResult" ) );

  private final TransMeta tm;
  private final long fin;
  private volatile boolean cancelado;

  LintEntorno( AbstractMeta copia ) {
    this( copia, MS_TOTAL );
  }

  LintEntorno( AbstractMeta copia, long presupuestoMs ) {
    this.tm = copia instanceof TransMeta ? (TransMeta) copia : null;
    this.fin = System.currentTimeMillis() + presupuestoMs;
  }

  void cancelar() {
    cancelado = true;
  }

  @Override
  public RowMetaInterface salida( StepMeta s ) throws KettleStepException {
    return campos( s, false );
  }

  @Override
  public RowMetaInterface entrada( StepMeta s ) throws KettleStepException {
    return campos( s, true );
  }

  private RowMetaInterface campos( final StepMeta s, final boolean entrada ) throws KettleStepException {
    if ( tm == null ) {
      throw new KettleStepException( "not a transformation" );
    }
    long ms = Math.min( MS_CAMPOS, msRestantes() );
    if ( ms <= 0 ) {
      throw new TiempoAgotado( "no time left" );
    }
    String[] falta = baseFaltante( s, entrada );
    if ( falta != null ) {
      throw new BaseFaltante( falta[ 0 ], falta[ 1 ] );
    }
    try {
      return RunMotor.enSegundoPlano( () -> entrada ? tm.getPrevStepFields( s ) : tm.getStepFields( s ), ms, null,
        "Computing the fields of '" + s.getName() + "'" );
    } catch ( ToolError e ) { // enSegundoPlano solo lanza ToolError al vencer el plazo
      throw new TiempoAgotado( e.getMessage() );
    } catch ( KettleStepException e ) {
      throw e;
    } catch ( Exception e ) {
      throw new KettleStepException( RunMotor.primeraLinea( e ), e );
    }
  }

  /**
   * {conexión, archivo} si calcular los campos se conectaría a una base de archivo que no existe (SQLite, H2,
   * HSQLDB): un Table input se conecta para dar sus campos y Kettle crearía el archivo vacío. Como
   * ContextoKettle.baseFaltante (docs/informes-diseno.md §2.4.4): para la salida también mira el propio step. null si
   * no (o si el chequeo falla: entonces se calcula como siempre).
   */
  String[] baseFaltante( StepMeta s, boolean entrada ) {
    try {
      return SecDestino.baseFaltantePara( tm, s, !entrada );
    } catch ( RuntimeException e ) {
      return null;
    }
  }

  /** Para kettle_check: el propio step y lo que tiene antes (como SecValidacion). */
  @Override
  public String[] baseFaltante( StepMeta s ) {
    return tm == null ? null : baseFaltante( s, false );
  }

  @Override
  public int salidasNormales( TransMeta t, StepMeta s ) {
    return TransOps.salidasNormales( t, s );
  }

  @Override
  public boolean soloLectura( StepMeta s ) {
    return s.getStepID() != null && !s.isMissing() && SOLO_LECTURA.contains( s.getStepID() ) && !RunMotor.escribe( s )
      && !escribeArchivosDeError( s ) && !creaBase( s ) && !sqlQueNoSoloLee( s );
  }

  /** Lo que hace que una consulta deje de ser solo de lectura (o que no se pueda saber): se busca en el texto crudo. */
  static final Pattern SQL_QUE_ESCRIBE = Pattern.compile( "(?i)\\b(insert|update|delete|merge|upsert|exec|execute|"
    + "call|into|truncate|drop|alter|create|grant|revoke|nextval|setval|lock)\\b" );
  /** Comentarios al principio de la consulta: «-- ventas del mes\nSELECT …». */
  private static final Pattern COMENTARIOS_INICIALES = Pattern.compile( "^(\\s+|--[^\\n]*(\\n|$)|/\\*.*?\\*/)*",
    Pattern.DOTALL );
  private static final Pattern LEE = Pattern.compile( "(?is)^[\\s(]*(select|with)\\b.*" );
  /** Un ';' con algo después: otra sentencia (algunos drivers las corren todas). */
  private static final Pattern VARIAS = Pattern.compile( ";\\s*\\S" );

  /**
   * Un Table input corre su consulta de verdad (y al conectarse, el «SQL to execute after connecting» de la conexión):
   * es de solo lectura solo si la consulta empieza con SELECT o WITH, no tiene otra sentencia ni palabras que escriban
   * (INSERT, UPDATE, DELETE, MERGE, EXEC, CALL, INTO, TRUNCATE, DROP… aunque estén en un comentario: mejor no evaluar
   * que escribir) y la conexión no corre SQL al conectarse. Con la sustitución de variables activa, se mira también la
   * consulta resuelta. Una función del usuario que escribe no se puede ver. true = no es de solo lectura.
   */
  boolean sqlQueNoSoloLee( StepMeta s ) {
    if ( !"TableInput".equals( s.getStepID() ) ) {
      return false;
    }
    try {
      TableInputMeta m = (TableInputMeta) s.getStepMetaInterface();
      DatabaseMeta db = m.getDatabaseMeta();
      if ( db != null && db.getConnectSQL() != null && !db.getConnectSQL().trim().isEmpty() ) {
        return true;
      }
      String sql = m.getSQL() == null ? "" : m.getSQL();
      if ( !consultaQueSoloLee( sql ) ) {
        return true;
      }
      VariableSpace vars = tm != null ? tm : s.getParentTransMeta();
      return m.isVariableReplacementActive() && vars != null && !consultaQueSoloLee( vars.environmentSubstitute( sql ) );
    } catch ( Exception e ) {
      return true;
    }
  }

  static boolean consultaQueSoloLee( String sql ) {
    String q = sql == null ? "" : sql;
    return LEE.matcher( COMENTARIOS_INICIALES.matcher( q ).replaceFirst( "" ) ).matches()
      && !SQL_QUE_ESCRIBE.matcher( q ).find() && !VARIAS.matcher( q ).find();
  }

  /**
   * Un Table input (o cualquier step con conexión) contra una base de archivo que no existe (SQLite, H2, HSQLDB): al
   * conectarse, Kettle la crearía vacía (SecDestino.archivoFaltante, docs/informes-diseno.md §2.4.4). Si no se puede
   * saber, tampoco es de solo lectura.
   */
  boolean creaBase( StepMeta s ) {
    try {
      DatabaseMeta[] usadas = s.getStepMetaInterface() == null ? null
        : s.getStepMetaInterface().getUsedDatabaseConnections();
      for ( int i = 0; usadas != null && i < usadas.length; i++ ) {
        if ( SecDestino.archivoFaltante( usadas[ i ], tm != null ? tm : s.getParentTransMeta() ) != null ) {
          return true;
        }
      }
      return false;
    } catch ( Exception e ) {
      return true;
    }
  }

  /** Un tag *_destination_directory con algo: Text file input, Excel input… escriben ahí las líneas malas. */
  private static final Pattern DESTINO_DE_ERRORES = Pattern.compile( "<([a-z_]*destination_directory)>([^<]*)</\\1>" );

  /**
   * Las entradas de texto y de Excel con el manejo de errores configurado escriben archivos de líneas malas, de
   * errores y de números de línea: con eso dejan de ser de solo lectura. Si no se puede leer su XML, tampoco.
   */
  static boolean escribeArchivosDeError( StepMeta s ) {
    try {
      Matcher m = DESTINO_DE_ERRORES.matcher( s.getXML() );
      while ( m.find() ) {
        if ( !m.group( 2 ).trim().isEmpty() ) {
          return true;
        }
      }
      return false;
    } catch ( Exception e ) {
      return true;
    }
  }

  @Override
  public boolean cancelado() {
    return cancelado;
  }

  @Override
  public long msRestantes() {
    return fin - System.currentTimeMillis();
  }
}
