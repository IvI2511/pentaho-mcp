package io.github.ivi2511.pentahomcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.CheckResultInterface;
import org.pentaho.di.core.Result;
import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.logging.BaseLogTable;
import org.pentaho.di.core.logging.ChannelLogTable;
import org.pentaho.di.core.logging.KettleLogStore;
import org.pentaho.di.core.logging.LogLevel;
import org.pentaho.di.core.logging.LogTableInterface;
import org.pentaho.di.core.logging.LoggingObjectInterface;
import org.pentaho.di.core.logging.LoggingObjectType;
import org.pentaho.di.core.logging.LoggingRegistry;
import org.pentaho.di.core.logging.MetricsLogTable;
import org.pentaho.di.core.logging.PerformanceLogTable;
import org.pentaho.di.core.logging.SimpleLoggingObject;
import org.pentaho.di.core.logging.StepLogTable;
import org.pentaho.di.core.logging.TransLogTable;
import org.pentaho.di.core.plugins.PluginInterface;
import org.pentaho.di.core.plugins.PluginRegistry;
import org.pentaho.di.core.plugins.StepPluginType;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.variables.VariableSpace;
import org.pentaho.di.job.Job;
import org.pentaho.di.job.JobEntryResult;
import org.pentaho.di.job.JobHopMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.repository.Repository;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.RowAdapter;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepErrorMeta;
import org.pentaho.di.trans.step.StepMetaDataCombi;
import org.pentaho.di.trans.step.StepPartitioningMeta;
import org.pentaho.di.trans.steps.dummytrans.DummyTransMeta;
import org.pentaho.metastore.api.IMetaStore;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.lint.Arreglo;
import io.github.ivi2511.pentahomcp.lint.Hallazgo;
import io.github.ivi2511.pentahomcp.lint.LintMotor;
import io.github.ivi2511.pentahomcp.lint.Opciones;
import io.github.ivi2511.pentahomcp.lint.Resultado;
import io.github.ivi2511.pentahomcp.lint.Sev;

/**
 * La parte de RunTools que no toca Spoon: trabaja sobre COPIAS (realClone) de un TransMeta/JobMeta
 * y corre siempre fuera del hilo de UI. Se puede probar sin GUI.
 */
final class RunMotor {

  /** Cuantas lineas de log se devuelven como maximo. */
  static final int LINEAS_LOG = 60;

  private static final int MAX_VALOR = 500;

  /** Steps que escriben fuera de la transformacion (archivos, tablas, mails, variables de la JVM...). */
  static final Set<String> STEPS_QUE_ESCRIBEN = new HashSet<String>( Arrays.asList(
    "TextFileOutput", "TextFileOutputLegacy", "TableOutput", "InsertUpdate", "Update", "Delete",
    "ExcelOutput", "TypeExitExcelWriterStep", "JsonOutput", "XMLOutput", "ExecSQL", "ExecSQLRow",
    "SynchronizeAfterMerge", "CombinationLookup", "DimensionLookup", "SQLFileOutput", "PropertyOutput",
    "AccessOutput", "Mail", "ProcessFiles", "SetVariable", "ExecProcess", "MySQLBulkLoader", "PGBulkLoader",
    "OraBulkLoader", "MonetDBBulkLoader", "VerticaBulkLoader", "TeraFast", "GPBulkLoader", "LucidDBBulkLoader",
    "ParquetOutput", "AvroOutput", "OrcOutput", "HBaseOutput", "MongoDbOutput", "ElasticSearchBulk",
    "SalesforceInsert", "SalesforceUpdate", "SalesforceUpsert", "SalesforceDelete", "HTTPPOST",
    "SyslogMessage", "WebServiceAvailable", "CubeOutput", "S3FileOutputPlugin", "SetVariables" ) );

  private static final ExecutorService POOL = Executors.newCachedThreadPool( new ThreadFactory() {
    private final AtomicInteger n = new AtomicInteger();

    @Override
    public Thread newThread( Runnable r ) {
      Thread t = new Thread( r, "pentaho-mcp-run-" + n.incrementAndGet() );
      t.setDaemon( true );
      return t;
    }
  } );

  private RunMotor() {
  }

  // ---------------------------------------------------------------- hilos

  /**
   * Corre la tarea en el pool propio y espera hasta timeoutMs. Si se pasa: ejecuta alVencer (por
   * ejemplo stopAll), cancela y lanza ToolError.
   */
  static <T> T enSegundoPlano( Callable<T> tarea, long timeoutMs, Runnable alVencer, String que )
    throws Exception {
    Future<T> f = POOL.submit( tarea );
    try {
      return f.get( timeoutMs, TimeUnit.MILLISECONDS );
    } catch ( TimeoutException e ) {
      if ( alVencer != null ) {
        try {
          alVencer.run();
        } catch ( Throwable ignored ) {
          // lo importante es avisar
        }
      }
      f.cancel( true );
      throw new ToolError( que + " did not finish in " + ( timeoutMs / 1000 ) + " s and was stopped" );
    } catch ( ExecutionException e ) {
      Throwable c = e.getCause();
      if ( c instanceof Exception ) {
        throw (Exception) c;
      }
      throw new RuntimeException( c );
    }
  }

  static void dormir( long ms ) {
    try {
      Thread.sleep( ms );
    } catch ( InterruptedException e ) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * JSON con sangria solo en el primer nivel: cada array (filas, campos, metricas) va en una linea,
   * asi una fila no ocupa una linea por valor.
   */
  static String json( Object o ) throws Exception {
    return McpHttpServer.JSON.writer( new Impresora() ).writeValueAsString( o );
  }

  private static final class Impresora extends DefaultPrettyPrinter {
    private static final long serialVersionUID = 1L;
    private int arrays;

    Impresora() {
      _arrayIndenter = NopIndenter.instance;
    }

    @Override
    public DefaultPrettyPrinter createInstance() {
      return new Impresora();
    }

    @Override
    public void writeStartArray( JsonGenerator g ) throws IOException {
      if ( arrays++ == 0 ) {
        _objectIndenter = NopIndenter.instance;
      }
      super.writeStartArray( g );
    }

    @Override
    public void writeEndArray( JsonGenerator g, int n ) throws IOException {
      super.writeEndArray( g, n );
      if ( --arrays == 0 ) {
        _objectIndenter = DefaultIndenter.SYSTEM_LINEFEED_INSTANCE;
      }
    }
  }

  // ---------------------------------------------------------------- copias

  /**
   * realClone(false) clona steps, hops y StepErrorMeta, pero los hops y los StepErrorMeta del clon
   * siguen apuntando a los StepMeta del ORIGINAL (verificado en 9.4). Sin esto, ejecutar la copia
   * recorre los steps vivos del documento y recortarla para el preview los modificaria. Reengancha
   * todo a los steps del clon, por nombre. Llamar en el hilo de UI, justo despues de realClone.
   */
  static void desligar( TransMeta c ) {
    for ( TransHopMeta h : c.getTransHops() ) {
      if ( h.getFromStep() != null ) {
        h.setFromStep( c.findStep( h.getFromStep().getName() ) );
      }
      if ( h.getToStep() != null ) {
        h.setToStep( c.findStep( h.getToStep().getName() ) );
      }
    }
    for ( StepMeta s : c.getSteps() ) {
      s.setParentTransMeta( c );
      StepErrorMeta em = s.getStepErrorMeta();
      if ( em != null ) {
        em = em.clone();
        em.setSourceStep( s );
        if ( em.getTargetStep() != null ) {
          em.setTargetStep( c.findStep( em.getTargetStep().getName() ) );
        }
        s.setStepErrorMeta( em );
      }
    }
    for ( StepMeta s : c.getSteps() ) {
      s.getStepMetaInterface().searchInfoAndTargetSteps( c.getSteps() );
    }
    c.clearCaches();
  }

  /** Lo mismo para jobs: los JobHopMeta del clon apuntan a las entradas del original. (UI) */
  static void desligar( JobMeta c ) {
    for ( int i = 0; i < c.nrJobHops(); i++ ) {
      JobHopMeta h = c.getJobHop( i );
      if ( h.getFromEntry() != null ) {
        h.setFromEntry( c.findJobEntry( h.getFromEntry().getName(), h.getFromEntry().getNr(), true ) );
      }
      if ( h.getToEntry() != null ) {
        h.setToEntry( c.findJobEntry( h.getToEntry().getName(), h.getToEntry().getNr(), true ) );
      }
    }
    for ( JobEntryCopy e : c.getJobCopies() ) {
      e.setParentJobMeta( c );
      if ( e.getEntry() != null ) {
        e.getEntry().setParentJobMeta( c );
      }
    }
  }

  // ---------------------------------------------------------------- steps

  static StepMeta paso( TransMeta tm, String nombre ) throws ToolError {
    StepMeta sm = tm.findStep( nombre );
    if ( sm == null ) {
      for ( StepMeta s : tm.getSteps() ) { // tolerar mayusculas/espacios
        if ( s.getName().trim().equalsIgnoreCase( nombre.trim() ) ) {
          return s;
        }
      }
      StringBuilder sb = new StringBuilder();
      for ( StepMeta s : tm.getSteps() ) {
        sb.append( sb.length() == 0 ? "" : ", " ).append( '\'' ).append( s.getName() ).append( '\'' );
      }
      throw new ToolError( "There is no step '" + nombre + "'. Steps: " + ( sb.length() == 0 ? "(none)" : sb ) );
    }
    return sm;
  }

  /** Si el step escribe fuera de la transformacion (por id conocido o por categoria de salida). */
  static boolean escribe( StepMeta sm ) {
    String id = sm.getStepID();
    if ( id == null ) {
      return false;
    }
    if ( STEPS_QUE_ESCRIBEN.contains( id ) ) {
      return true;
    }
    try {
      PluginInterface p = PluginRegistry.getInstance().findPluginWithId( StepPluginType.class, id );
      String cat = p == null ? null : p.getCategory();
      if ( cat != null ) {
        String c = cat.toLowerCase();
        return c.equals( "output" ) || c.equals( "salida" ) || c.contains( "bulk" ) || c.contains( "masiva" );
      }
    } catch ( Exception ignored ) {
      // sin registro de plugins: solo la lista
    }
    return false;
  }

  // ---------------------------------------------------------------- campos

  static ArrayNode campos( RowMetaInterface rm, boolean detalle ) {
    ArrayNode arr = Tools.arr();
    if ( rm == null ) {
      return arr;
    }
    for ( ValueMetaInterface v : rm.getValueMetaList() ) {
      ObjectNode c = arr.addObject();
      c.put( "name", v.getName() );
      c.put( "type", v.getTypeDesc() );
      if ( detalle ) {
        if ( v.getLength() >= 0 ) {
          c.put( "length", v.getLength() );
        }
        if ( v.getPrecision() >= 0 ) {
          c.put( "precision", v.getPrecision() );
        }
        if ( v.getConversionMask() != null && !v.getConversionMask().isEmpty() ) {
          c.put( "format", v.getConversionMask() );
        }
        if ( v.getOrigin() != null ) {
          c.put( "origin", v.getOrigin() );
        }
      }
    }
    return arr;
  }

  /** get_step_fields sobre una copia: salida (getStepFields) o entrada (getPrevStepFields + info streams). */
  static ObjectNode camposStep( TransMeta copia, String step, boolean entrada ) throws Exception {
    StepMeta sm = paso( copia, step );
    ObjectNode o = Tools.obj();
    o.put( "step", sm.getName() );
    o.put( "type", sm.getStepID() );
    o.put( "side", entrada ? "input" : "output" );
    RowMetaInterface rm;
    try {
      rm = entrada ? copia.getPrevStepFields( sm ) : copia.getStepFields( sm );
    } catch ( KettleStepException e ) {
      throw new ToolError( "Kettle could not compute the fields of '" + sm.getName() + "': " + mensaje( e ) );
    }
    if ( entrada ) {
      ArrayNode desde = o.putArray( "from" );
      for ( StepMeta p : copia.findPreviousSteps( sm, false ) ) {
        desde.add( p.getName() );
      }
      if ( desde.size() == 0 ) {
        o.put( "note", "No hop comes into it (it is an input step, or it is not connected)" );
      }
    }
    o.put( "count", rm == null ? 0 : rm.size() );
    o.set( "fields", campos( rm, true ) );
    if ( entrada ) {
      StepMeta[] infos = copia.getInfoStep( sm );
      if ( infos != null && infos.length > 0 ) {
        ArrayNode info = o.putArray( "info_streams" ); // lookups: los streams de busqueda
        for ( StepMeta i : infos ) {
          if ( i == null ) {
            continue;
          }
          ObjectNode x = info.addObject();
          x.put( "step", i.getName() );
          x.set( "fields", campos( copia.getStepFields( i ), true ) );
        }
      }
    }
    return o;
  }

  // ---------------------------------------------------------------- validar

  static ObjectNode validar( AbstractMeta copia, boolean detalle, Repository rep, IMetaStore ms ) {
    return validar( copia, detalle, rep, ms, null );
  }

  /**
   * Textos de lo que validar agrega a los avisos de Kettle. Los chequeos propios salen del motor del linter
   * ({@link #REGLAS_DE_VALIDATE}) con los textos de lint_{idioma}.properties; aca solo se elige el idioma y
   * cada uso puede retocar el renglon. La tool validate usa {@link #TEXTOS_MCP} (ingles, con el nombre de la
   * tool que lo arregla); los informes pasan los suyos, traducidos y sin hablarle a la IA.
   */
  interface TextosChequeo {
    /** Claves: kettle_fallo (mensaje de la excepcion de checkSteps / checkJobEntries). */
    String texto( String clave, Object... args );

    /** Idioma de los mensajes del linter: en, es o pt (cualquier otro, en). */
    default String idioma() {
      return "en";
    }

    /** El renglon de un hallazgo del linter (sin el "ERROR: " / "WARNING: " que agrega validar). */
    default String hallazgo( Hallazgo h ) {
      return h.message;
    }
  }

  /** Los textos de la tool validate (MCP). */
  static final TextosChequeo TEXTOS_MCP = new TextosChequeo() {
    @Override
    public String texto( String clave, Object... a ) {
      return "kettle_fallo".equals( clave ) ? "Kettle's validation failed: " + a[ 0 ] : clave;
    }

    @Override
    public String hallazgo( Hallazgo h ) {
      if ( h.fix != null && Arreglo.SET_OUTPUT_MODE.equals( h.fix.kind ) ) {
        return h.message + " If each one must get all of them, use set_output_mode with 'copy'.";
      }
      return h.message;
    }
  };

  /**
   * Los chequeos propios de validate: reglas del motor del linter (docs/linter-integracion.md §2.3), con el
   * mismo criterio de antes salvo orphan_entry, que ya no marca las entradas ocultas (draw=N) de jobs viejos.
   */
  static final Set<String> REGLAS_DE_VALIDATE = Collections.unmodifiableSet( new LinkedHashSet<String>(
    Arrays.asList( "missing_plugin", "orphan_step", "distribute_multiple_outputs", "broken_hop", "disabled_hop",
      "job_without_start", "orphan_entry", "unreachable_entry" ) ) );

  /**
   * Igual, pero checkSteps no revisa los steps de {@code saltear} (por nombre; solo transformaciones). Lo
   * usan los informes para no validar los steps que se conectarian a una base de archivo que no existe:
   * Kettle la crearia vacia. Usa la seleccion de la copia y la restaura al terminar.
   */
  static ObjectNode validar( AbstractMeta copia, boolean detalle, Repository rep, IMetaStore ms,
                             Set<String> saltear ) {
    return validar( copia, detalle, rep, ms, saltear, TEXTOS_MCP );
  }

  /** Igual, con los textos de los chequeos propios que da {@code textos} (los informes los traducen). */
  static ObjectNode validar( AbstractMeta copia, boolean detalle, Repository rep, IMetaStore ms,
                             Set<String> saltear, TextosChequeo textos ) {
    TextosChequeo tx = textos == null ? TEXTOS_MCP : textos;
    List<CheckResultInterface> r = new ArrayList<CheckResultInterface>();
    Map<String, ArrayNode> porStep = new LinkedHashMap<String, ArrayNode>();
    int[] cuenta = new int[ 4 ]; // error, advertencia, ok, comentario
    String falloCheck = null;
    try {
      if ( copia instanceof TransMeta ) {
        TransMeta tm = (TransMeta) copia;
        if ( saltear == null || saltear.isEmpty() ) {
          tm.checkSteps( r, false, null, tm, rep, ms );
        } else {
          List<StepMeta> pasos = tm.getSteps();
          boolean[] antes = new boolean[ pasos.size() ];
          try {
            int quedan = 0;
            for ( int i = 0; i < pasos.size(); i++ ) {
              antes[ i ] = pasos.get( i ).isSelected();
              boolean va = !saltear.contains( pasos.get( i ).getName() );
              pasos.get( i ).setSelected( va );
              quedan += va ? 1 : 0;
            }
            // ojo: con onlySelected y NINGUN step seleccionado, checkSteps revisa todos
            if ( quedan > 0 ) {
              tm.checkSteps( r, true, null, tm, rep, ms );
            }
          } finally {
            for ( int i = 0; i < pasos.size() && i < antes.length; i++ ) {
              pasos.get( i ).setSelected( antes[ i ] );
            }
          }
        }
      } else {
        JobMeta jm = (JobMeta) copia;
        jm.checkJobEntries( r, false, null, jm, rep, ms );
      }
    } catch ( Throwable t ) {
      falloCheck = mensaje( t );
    }
    for ( CheckResultInterface c : r ) {
      String fuente = c.getSourceInfo() == null ? "(general)" : c.getSourceInfo().getName();
      anotar( porStep, cuenta, fuente, c.getType(), c.getText(), detalle );
    }
    if ( falloCheck != null ) {
      anotar( porStep, cuenta, "(general)", CheckResultInterface.TYPE_RESULT_ERROR,
        tx.texto( "kettle_fallo", falloCheck ), detalle );
    }
    chequeosPropios( copia, porStep, cuenta, detalle, tx );
    ObjectNode o = Tools.obj();
    o.put( "valid", cuenta[ 0 ] == 0 );
    o.put( "errors", cuenta[ 0 ] );
    o.put( "warnings", cuenta[ 1 ] );
    if ( detalle ) {
      o.put( "ok", cuenta[ 2 ] );
      o.put( "comments", cuenta[ 3 ] );
    }
    ObjectNode ps = o.putObject( "by_step" );
    for ( Map.Entry<String, ArrayNode> e : porStep.entrySet() ) {
      ps.set( e.getKey(), e.getValue() );
    }
    if ( porStep.isEmpty() ) {
      o.put( "note", detalle ? "Kettle returned no remarks" : "No errors or warnings" );
    }
    return o;
  }

  private static void anotar( Map<String, ArrayNode> porStep, int[] cuenta, String fuente, int tipo, String texto,
                              boolean detalle ) {
    int idx;
    String etiqueta;
    switch ( tipo ) {
      case CheckResultInterface.TYPE_RESULT_ERROR:
        idx = 0;
        etiqueta = "ERROR";
        break;
      case CheckResultInterface.TYPE_RESULT_WARNING:
        idx = 1;
        etiqueta = "WARNING";
        break;
      case CheckResultInterface.TYPE_RESULT_OK:
        idx = 2;
        etiqueta = "OK";
        break;
      default:
        idx = 3;
        etiqueta = "COMMENT";
    }
    String linea = etiqueta + ": " + ( texto == null ? "" : texto.trim().replaceAll( "\\s+", " " ) );
    ArrayNode a = porStep.get( fuente );
    if ( a != null ) {
      // Kettle repite el mismo aviso una vez por cada step que recorre el campo: contarlo una sola vez
      for ( int i = 0; i < a.size(); i++ ) {
        if ( a.get( i ).asText().equals( linea ) ) {
          return;
        }
      }
    }
    cuenta[ idx ]++;
    if ( !detalle && idx >= 2 ) {
      return;
    }
    if ( a == null ) {
      a = Tools.arr();
      porStep.put( fuente, a );
    }
    a.add( linea );
  }

  /** Kettle ya avisa "no se utiliza este paso": no repetirlo con otras palabras. */
  private static boolean tieneAdvertencia( Map<String, ArrayNode> porStep, String step ) {
    ArrayNode a = porStep.get( step );
    if ( a != null ) {
      for ( int i = 0; i < a.size(); i++ ) {
        if ( a.get( i ).asText().startsWith( "WARNING" ) ) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Los chequeos propios (huerfanos, hops deshabilitados o rotos, reparto, plugins que faltan, job sin START,
   * entradas inalcanzables) salen del motor del linter: las mismas reglas que lint_etl, sin supresiones (validate
   * no se silencia) y sin los info (distribute_multiple_outputs a steps del mismo tipo es un reparto de carga).
   */
  private static void chequeosPropios( AbstractMeta copia, Map<String, ArrayNode> porStep, int[] cuenta,
                                       boolean detalle, TextosChequeo tx ) {
    Opciones op = new Opciones();
    op.rules = REGLAS_DE_VALIDATE;
    op.honorSuppressions = false;
    op.minSeverity = Sev.WARNING;
    op.lang = tx.idioma();
    op.entorno = new LintEntorno( copia );
    Resultado r = LintMotor.analizar( copia, op );
    for ( Hallazgo h : r.findings ) {
      String fuente = h.step != null ? h.step : h.entry != null ? h.entry : "(general)";
      if ( "orphan_step".equals( h.rule ) && tieneAdvertencia( porStep, fuente ) ) {
        continue; // Kettle ya avisa "no se utiliza este paso"
      }
      int tipo = h.severity == Sev.ERROR ? CheckResultInterface.TYPE_RESULT_ERROR
        : CheckResultInterface.TYPE_RESULT_WARNING;
      anotar( porStep, cuenta, fuente, tipo, tx.hallazgo( h ), detalle );
    }
  }

  // ---------------------------------------------------------------- preview

  /**
   * Deja en la copia solo el step pedido y sus ancestros (todo lo que llega a el por hops
   * habilitados, info streams incluidos). Los hijos directos de esos steps quedan como Dummy con el
   * mismo nombre, para que las referencias (Filter rows true/false, Switch/case, manejo de errores)
   * sigan resolviendo; todo lo demas se quita. Devuelve cuantos steps se quitaron o vaciaron.
   */
  static int recortar( TransMeta c, StepMeta objetivo ) {
    Set<StepMeta> quedan = new LinkedHashSet<StepMeta>();
    quedan.add( objetivo );
    boolean cambio = true;
    while ( cambio ) {
      cambio = false;
      for ( TransHopMeta h : c.getTransHops() ) {
        if ( h.isEnabled() && h.getFromStep() != null && quedan.contains( h.getToStep() )
          && quedan.add( h.getFromStep() ) ) {
          cambio = true;
        }
      }
    }
    Set<StepMeta> hijos = new LinkedHashSet<StepMeta>();
    for ( TransHopMeta h : c.getTransHops() ) {
      if ( h.isEnabled() && quedan.contains( h.getFromStep() ) && h.getToStep() != null
        && !quedan.contains( h.getToStep() ) ) {
        hijos.add( h.getToStep() );
      }
    }
    for ( int i = c.nrTransHops() - 1; i >= 0; i-- ) {
      TransHopMeta h = c.getTransHop( i );
      boolean sirve = h.isEnabled() && quedan.contains( h.getFromStep() )
        && ( quedan.contains( h.getToStep() ) || hijos.contains( h.getToStep() ) );
      if ( !sirve ) {
        c.removeTransHop( i );
      }
    }
    int tocados = 0;
    for ( int i = c.nrSteps() - 1; i >= 0; i-- ) {
      StepMeta s = c.getStep( i );
      if ( !quedan.contains( s ) && !hijos.contains( s ) ) {
        c.removeStep( i );
        tocados++;
      }
    }
    for ( StepMeta h : hijos ) { // vaciar: Dummy con el mismo nombre (y el mismo objeto)
      DummyTransMeta d = new DummyTransMeta();
      d.setDefault();
      d.setParentStepMeta( h );
      h.setStepID( "Dummy" );
      h.setStepMetaInterface( d );
      h.setCopies( 1 );
      h.setStepErrorMeta( null );
      h.setStepPartitioningMeta( new StepPartitioningMeta() );
      h.setClusterSchema( null );
      tocados++;
    }
    if ( !c.isStepUsedInTransHops( objetivo ) ) {
      // Trans solo arranca los steps que estan en algun hop: un step suelto necesita un destino
      StepMeta fin = new StepMeta( "Dummy", c.getAlternativeStepname( "mcp_preview_end" ), new DummyTransMeta() );
      fin.setLocation( objetivo.getLocation().x + 150, objetivo.getLocation().y );
      c.addStep( fin );
      c.addTransHop( new TransHopMeta( objetivo, fin ) );
    }
    for ( StepMeta s : c.getSteps() ) {
      s.getStepMetaInterface().searchInfoAndTargetSteps( c.getSteps() );
    }
    c.clearCaches();
    return tocados;
  }

  /** Corre en segundo plano solo lo necesario para ver las filas que salen de 'step'. */
  static ObjectNode preview( TransMeta copia, String step, final int maxFilas, long timeoutMs ) throws Exception {
    StepMeta objetivo = paso( copia, step );
    String nombre = objetivo.getName();
    ObjectNode o = Tools.obj();
    o.put( "step", nombre );
    ArrayNode avisos = Tools.arr();
    if ( escribe( objetivo ) ) {
      avisos.add( "'" + nombre + "' is an output step (" + objetivo.getStepID() + "): previewing it REALLY "
        + "WRITES (its file or table). The rows shown are the ones it passes on." );
    }
    if ( objetivo.isMissing() ) {
      throw new ToolError( "The plugin of '" + nombre + "' is missing (type " + objetivo.getStepID() + "): it cannot run" );
    }
    int quitados = recortar( copia, objetivo );
    List<String> escritores = new ArrayList<String>();
    for ( StepMeta s : copia.getSteps() ) {
      if ( s != objetivo && !"Dummy".equals( s.getStepID() ) && escribe( s ) ) {
        escritores.add( s.getName() );
      }
      if ( s.isMissing() ) {
        throw new ToolError( "The plugin of '" + s.getName() + "', which feeds '" + nombre + "', is missing" );
      }
    }
    if ( !escritores.isEmpty() ) {
      avisos.add( "These upstream steps also run and really write: " + escritores );
    }

    final Trans t = new Trans( copia, padreDeLog() );
    t.setLogLevel( LogLevel.BASIC );
    t.setPreview( true );
    prepararVariables( t, copia );
    final List<Object[]> filas = Collections.synchronizedList( new ArrayList<Object[]>() );
    final AtomicReference<RowMetaInterface> meta = new AtomicReference<RowMetaInterface>();
    long inicio = System.currentTimeMillis();
    String fin;
    try {
      try {
        t.prepareExecution( null );
      } catch ( Exception e ) {
        o.put( "state", "init_failed" );
        o.put( "reason", primeraLinea( e ) );
        o.put( "errors", Math.max( 1, t.getErrors() ) );
        o.set( "metrics", metricas( t ) );
        o.put( "log", colaLog( t.getLogChannelId(), true ) );
        agregarAvisos( o, avisos, quitados );
        liberarLog( t.getLogChannelId() );
        return o;
      }
      for ( StepInterface si : t.findStepInterfaces( nombre ) ) {
        si.addRowListener( new RowAdapter() {
          @Override
          public void rowWrittenEvent( RowMetaInterface rm, Object[] row ) throws KettleStepException {
            synchronized ( filas ) { // varias copias del step escriben a la vez
              if ( filas.size() >= maxFilas ) {
                return;
              }
              meta.compareAndSet( null, rm.clone() );
              try {
                filas.add( rm.cloneRow( row ) ); // los steps reutilizan el array
              } catch ( Exception e ) {
                throw new KettleStepException( e );
              }
            }
          }
        } );
      }
      t.startThreads();
      long limite = inicio + timeoutMs;
      fin = "finished";
      while ( !t.isFinished() ) {
        if ( filas.size() >= maxFilas ) {
          fin = "row_limit";
          break;
        }
        if ( System.currentTimeMillis() > limite ) {
          fin = "timeout";
          break;
        }
        dormir( 40 );
      }
      if ( !t.isFinished() ) {
        t.stopAll();
        esperarFin( t, 10000 );
      }
    } catch ( Exception e ) {
      t.stopAll();
      throw e;
    }

    RowMetaInterface rm = meta.get();
    if ( rm == null ) {
      try {
        rm = copia.getStepFields( objetivo );
      } catch ( Exception ignored ) {
        rm = null;
      }
    }
    o.put( "state", fin );
    if ( "timeout".equals( fin ) ) {
      avisos.add( "Stopped after " + ( timeoutMs / 1000 ) + " s with " + filas.size() + " rows; raise timeout_s if needed" );
    }
    o.put( "seconds", ( System.currentTimeMillis() - inicio ) / 1000.0 );
    o.set( "fields", campos( rm, false ) );
    o.put( "row_count", filas.size() );
    o.set( "rows", filas( rm, filas ) );
    int errores = t.getErrors();
    o.put( "errors", errores );
    o.set( "metrics", metricas( t ) );
    if ( errores > 0 ) {
      o.put( "log", colaLog( t.getLogChannelId(), true ) );
    }
    agregarAvisos( o, avisos, quitados );
    liberarLog( t.getLogChannelId() );
    return o;
  }

  // ---------------------------------------------------------------- filas (modo profundo del linter)

  /** Lo que devuelve {@link #filas}: los campos y las filas (objetos de Kettle) que salieron del step. */
  static final class Filas {
    RowMetaInterface meta;
    final List<Object[]> filas = new ArrayList<Object[]>();
    /** finished | row_limit | timeout | cancelled | init_failed */
    String estado;
    /** Terminó sola, sin errores y sin descartar filas: se leyó todo lo que el step tenía para dar. */
    boolean completa;
    int errores;
    /** Lo que dijo Kettle cuando falló (la línea más útil del log), o null. */
    String error;
    long ms;
  }

  /**
   * Como {@link #preview}, pero devuelve los objetos de Kettle (RowMeta y filas, sin pasarlas a texto) y si la
   * corrida terminó sola, sin errores ni filas descartadas: lo usa el modo profundo del linter (LintSondas), que antes
   * decide si todo lo que queda en la copia recortada solo lee. Recorta la copia igual que preview (no la reuses). Es
   * aditivo: preview, que usan Informes y modo clase, no cambia. Fuera del hilo de UI.
   */
  static Filas filas( TransMeta copia, String step, int max, long ms ) throws Exception {
    return filas( copia, step, max, ms, null );
  }

  /** Igual, y corta (estado cancelled) en cuanto {@code cancelado} dice true o el hilo se interrumpe. */
  static Filas filas( TransMeta copia, String step, final int max, long ms, BooleanSupplier cancelado ) throws Exception {
    final StepMeta objetivo = paso( copia, step );
    final String nombre = objetivo.getName();
    if ( objetivo.isMissing() ) {
      throw new ToolError( "The plugin of '" + nombre + "' is missing (type " + objetivo.getStepID() + "): it cannot run" );
    }
    recortar( copia, objetivo );
    sinTablasDeLog( copia ); // una sonda no es una corrida: nada de START/END en las tablas de log
    final Filas r = new Filas();
    final boolean[] descartadas = { false };
    long inicio = System.currentTimeMillis();
    final Trans t = new Trans( copia, padreDeLog() );
    t.setLogLevel( LogLevel.BASIC );
    t.setPreview( true );
    prepararVariables( t, copia );
    sinTablasDeLog( copia, t );
    try {
      try {
        // init conecta a las BD: con una que no contesta, que no se coma el plazo de la sonda
        prepararConPlazo( t, Math.max( 1, ms ), "Preparing '" + nombre + "'" );
      } catch ( ToolError e ) {
        r.estado = "timeout";
        r.error = e.getMessage();
        return r;
      } catch ( Exception e ) {
        r.estado = "init_failed";
        r.errores = Math.max( 1, t.getErrors() );
        r.error = errorDeLog( t.getLogChannelId(), e );
        return r;
      }
      for ( StepInterface si : t.findStepInterfaces( nombre ) ) {
        si.addRowListener( new RowAdapter() {
          @Override
          public void rowWrittenEvent( RowMetaInterface rm, Object[] row ) throws KettleStepException {
            synchronized ( r.filas ) { // varias copias del step escriben a la vez
              if ( r.filas.size() >= max ) {
                descartadas[ 0 ] = true;
                return;
              }
              if ( r.meta == null ) {
                r.meta = rm.clone();
              }
              try {
                r.filas.add( rm.cloneRow( row ) ); // los steps reutilizan el array
              } catch ( Exception e ) {
                throw new KettleStepException( e );
              }
            }
          }
        } );
      }
      t.startThreads();
      long limite = inicio + ms;
      r.estado = "finished";
      while ( !t.isFinished() ) {
        synchronized ( r.filas ) {
          if ( descartadas[ 0 ] ) {
            r.estado = "row_limit";
            break;
          }
        }
        if ( ( cancelado != null && cancelado.getAsBoolean() ) || Thread.currentThread().isInterrupted() ) {
          r.estado = "cancelled";
          break;
        }
        if ( System.currentTimeMillis() > limite ) {
          r.estado = "timeout";
          break;
        }
        dormir( 20 );
      }
      if ( !t.isFinished() ) {
        t.stopAll();
        esperarFin( t, 5000 );
      }
      r.errores = t.getErrors();
      synchronized ( r.filas ) {
        if ( descartadas[ 0 ] && "finished".equals( r.estado ) ) {
          r.estado = "row_limit"; // terminó, pero hubo filas que no entraron
        }
        r.completa = "finished".equals( r.estado ) && r.errores == 0;
      }
      if ( r.errores > 0 ) {
        r.error = errorDeLog( t.getLogChannelId(), null );
      }
      if ( r.meta == null ) {
        try {
          r.meta = copia.getStepFields( objetivo );
        } catch ( Exception ignored ) {
          r.meta = null;
        }
      }
      return r;
    } catch ( Exception e ) {
      t.stopAll();
      throw e;
    } finally {
      r.ms = System.currentTimeMillis() - inicio;
      liberarLog( t.getLogChannelId() );
    }
  }

  /**
   * prepareExecution con plazo (init se conecta a las BD). Si vence, la Trans queda abandonada sin interrumpir su
   * init (con el join interrumpido Kettle libera los steps mientras uno todavía se conecta, y esa conexión no la cierra
   * nadie): cuando el init termina, {@link #liberarPasos} cierra lo que abrió. Lanza ToolError al vencer.
   */
  static void prepararConPlazo( final Trans t, long ms, String que ) throws Exception {
    final AtomicInteger estado = new AtomicInteger( 0 ); // 0 preparando, 1 terminó, 2 abandonada
    Future<Void> f = POOL.submit( () -> {
      boolean ok = false;
      try {
        t.prepareExecution( null );
        ok = true;
        return null;
      } finally {
        // si falló, Kettle ya liberó los steps; si salió bien y nadie la espera, se liberan acá
        if ( !estado.compareAndSet( 0, 1 ) && ok ) {
          liberarPasos( t );
        }
      }
    } );
    try {
      f.get( ms, TimeUnit.MILLISECONDS );
    } catch ( TimeoutException e ) {
      if ( estado.compareAndSet( 0, 2 ) ) {
        throw new ToolError( que + " did not finish in " + Math.max( 1, ms / 1000 ) + " s and was stopped" );
      }
      resultado( f ); // terminó justo al vencer: sigue como si nada
    } catch ( ExecutionException e ) {
      throw causa( e );
    }
  }

  private static void resultado( Future<?> f ) throws Exception {
    try {
      f.get();
    } catch ( ExecutionException e ) {
      throw causa( e );
    }
  }

  private static Exception causa( ExecutionException e ) {
    Throwable c = e.getCause();
    return c instanceof Exception ? (Exception) c : new RuntimeException( c );
  }

  /** Libera los steps de una Trans preparada que no va a correr: dispose cierra sus conexiones y archivos. */
  static void liberarPasos( Trans t ) {
    List<StepMetaDataCombi> pasos = t.getSteps();
    if ( pasos != null ) {
      for ( StepMetaDataCombi c : new ArrayList<StepMetaDataCombi>( pasos ) ) {
        try {
          c.step.dispose( c.meta, c.data );
        } catch ( Throwable ignored ) {
          // los demás igual se liberan
        }
      }
    }
    liberarLog( t.getLogChannelId() );
  }

  /**
   * La copia de una sonda sin tablas de log: prepareExecution escribe el START (y al terminar el END, los steps, la
   * performance y los canales) en las tablas de la transformación o, si no tiene, en las de kettle.properties
   * (KETTLE_TRANS_LOG_DB/TABLE…), aunque sea una preview. Tablas nuevas sin conexión ni nombre y esas variables vacías
   * en la copia (el VariableSpace de las tablas nuevas): isDefined() da false.
   */
  static void sinTablasDeLog( TransMeta tm ) {
    tm.setTransLogTable( TransLogTable.getDefault( tm, tm, tm.getSteps() ) );
    tm.setStepLogTable( StepLogTable.getDefault( tm, tm ) );
    tm.setPerformanceLogTable( PerformanceLogTable.getDefault( tm, tm ) );
    tm.setChannelLogTable( ChannelLogTable.getDefault( tm, tm ) );
    tm.setMetricsLogTable( MetricsLogTable.getDefault( tm, tm ) );
    sinTablasDeLog( tm, tm );
  }

  /** Las variables globales de las tablas de log de tm, vacías en vars (la copia y la Trans). */
  static void sinTablasDeLog( TransMeta tm, VariableSpace vars ) {
    for ( LogTableInterface l : tm.getLogTables() ) {
      if ( l instanceof BaseLogTable ) {
        BaseLogTable b = (BaseLogTable) l;
        for ( String v : new String[] { b.getConnectionNameVariable(), b.getSchemaNameVariable(),
          b.getTableNameVariable() } ) {
          if ( v != null ) {
            vars.setVariable( v, "" );
          }
        }
      }
    }
  }

  /** Qué valor no se pudo convertir (el renglón más largo gana: el que trae el valor). */
  private static final Pattern ERROR_CONVERSION = Pattern.compile( "(?i)couldn'?t convert|could not convert"
    + "|unable to convert" );
  /** Lo que dice qué falló (un archivo, las copias de un step…). */
  private static final Pattern ERROR_UTIL = Pattern.compile( "(?i)can'?t be executed|not found|does not exist"
    + "|no such file|couldn'?t|could not|unable to|cannot|can'?t|conversion error" );
  /** Renglones de error sin información: «Unexpected error», el nombre de la excepción solo. */
  private static final Pattern ERROR_VACIO = Pattern.compile( "(?i)\\s*(unexpected error( during transformation "
    + "execution)?|errors detected!?|[\\w.$]+(exception|error)\\s*:?)?\\s*" );
  /** "Leer ventas.0 - " al principio de un renglón del log. */
  private static final Pattern COPIA_DE_STEP = Pattern.compile( "^.*?\\.\\d+ - " );

  /**
   * La línea más útil del log de una corrida con errores: la que dice qué no se pudo convertir o leer; si no, el
   * primer ERROR con texto. Sin ERROR en el log, la primera línea de la excepción (o null).
   */
  static String errorDeLog( String canal, Throwable e ) {
    String[] crudas = colaLog( canal, true ).split( "\\r?\\n" );
    List<String> ls = new ArrayList<String>();
    int primero = -1;
    for ( int i = 0; i < crudas.length; i++ ) {
      if ( primero < 0 && crudas[ i ].contains( "ERROR" ) ) {
        primero = i;
      }
      String x = crudas[ i ].replaceFirst( "^\\d{2}:\\d{2}:\\d{2} - ", "" );
      java.util.regex.Matcher m = COPIA_DE_STEP.matcher( x );
      if ( m.find() ) {
        x = x.substring( m.end() );
      }
      ls.add( x.replaceFirst( "^ERROR: ?", "" ).trim() );
    }
    String elegido = null;
    if ( primero >= 0 ) {
      int hasta = Math.min( ls.size(), primero + 40 );
      for ( int i = primero; i < hasta; i++ ) {
        String l = ls.get( i );
        if ( ERROR_CONVERSION.matcher( l ).find() && ( elegido == null || l.length() > elegido.length() ) ) {
          elegido = l;
        }
      }
      for ( int i = primero; i < hasta && elegido == null; i++ ) {
        if ( ERROR_UTIL.matcher( ls.get( i ) ).find() ) {
          elegido = ls.get( i );
        }
      }
      for ( int i = primero; i < hasta && elegido == null; i++ ) {
        if ( !ERROR_VACIO.matcher( ls.get( i ) ).matches() ) {
          elegido = ls.get( i );
        }
      }
    }
    if ( elegido == null && e != null ) {
      elegido = primeraLinea( e );
    }
    if ( elegido == null ) {
      return null;
    }
    elegido = elegido.replaceAll( "[\\s.]+$", "" ); // el que lo use pone su propio punto
    return elegido.length() <= 300 ? elegido : elegido.substring( 0, 300 ) + "...";
  }

  private static void agregarAvisos( ObjectNode o, ArrayNode avisos, int quitados ) {
    if ( quitados > 0 ) {
      o.put( "steps_excluded_from_preview", quitados );
    }
    if ( avisos.size() > 0 ) {
      o.set( "warnings", avisos );
    }
  }

  /** Variables internas (Internal.Entry.Current.Directory...) y parametros, como hace Pan. */
  static void prepararVariables( Trans t, TransMeta tm ) {
    tm.setInternalKettleVariables( t );
    t.copyParametersFrom( tm );
    t.activateParameters();
  }

  static ArrayNode filas( RowMetaInterface rm, List<Object[]> filas ) {
    ArrayNode arr = Tools.arr();
    synchronized ( filas ) {
      for ( Object[] f : filas ) {
        ArrayNode x = arr.addArray();
        int n = rm == null ? f.length : rm.size();
        for ( int i = 0; i < n; i++ ) {
          Object v = i < f.length ? f[ i ] : null;
          if ( v == null ) {
            x.addNull();
            continue;
          }
          String s;
          try {
            s = rm == null ? String.valueOf( v ) : rm.getValueMeta( i ).getString( v );
          } catch ( Exception e ) {
            s = String.valueOf( v );
          }
          if ( s != null && s.length() > MAX_VALOR ) {
            s = s.substring( 0, MAX_VALOR ) + "...(" + s.length() + " chars)";
          }
          x.add( s );
        }
      }
    }
    return arr;
  }

  // ---------------------------------------------------------------- ejecutar en segundo plano

  /** Transformacion completa en la JVM, sin GUI: escribe sus salidas. Si se pasa de espera, stopAll. */
  static ObjectNode ejecutarTrans( TransMeta copia, long esperaMs ) throws Exception {
    Trans t = new Trans( copia, padreDeLog() );
    t.setLogLevel( LogLevel.BASIC );
    prepararVariables( t, copia );
    long inicio = System.currentTimeMillis();
    try {
      t.prepareExecution( null );
    } catch ( Exception e ) {
      ObjectNode o = estadoTrans( t, inicio );
      o.put( "state", "init_failed" );
      o.put( "reason", primeraLinea( e ) );
      o.put( "errors", Math.max( 1, t.getErrors() ) );
      o.put( "log", colaLog( t.getLogChannelId(), true ) );
      liberarLog( t.getLogChannelId() );
      return o;
    }
    t.startThreads();
    boolean corte = false;
    long limite = inicio + esperaMs;
    while ( !t.isFinished() ) {
      if ( System.currentTimeMillis() > limite ) {
        corte = true;
        t.stopAll();
        esperarFin( t, 10000 );
        break;
      }
      dormir( 100 );
    }
    ObjectNode o = estadoTrans( t, inicio );
    if ( corte ) {
      o.put( "state", "stopped_by_timeout" );
      o.put( "warning", "Did not finish in " + ( esperaMs / 1000 ) + " s and was stopped (stopAll). Whatever it "
        + "already wrote stays written. Raise wait_s or use mode='visible'." );
    }
    o.put( "log", colaLog( t.getLogChannelId(), t.getErrors() > 0 ) );
    liberarLog( t.getLogChannelId() );
    return o;
  }

  /** Job completo en la JVM, sin GUI. Si se pasa de espera, stopAll. */
  static ObjectNode ejecutarJob( JobMeta copia, Repository rep, long esperaMs ) throws Exception {
    Job j = new Job( rep, copia, padreDeLog() );
    j.setLogLevel( LogLevel.BASIC );
    j.initializeVariablesFrom( null );
    copia.setInternalKettleVariables( j );
    j.copyParametersFrom( copia );
    j.activateParameters();
    long inicio = System.currentTimeMillis();
    j.start();
    boolean corte = false;
    long limite = inicio + esperaMs;
    dormir( 50 );
    while ( !j.isFinished() ) {
      if ( System.currentTimeMillis() > limite ) {
        corte = true;
        j.stopAll();
        j.waitUntilFinished( 10000 );
        break;
      }
      dormir( 100 );
    }
    ObjectNode o = estadoJob( j, inicio );
    if ( corte ) {
      o.put( "state", "stopped_by_timeout" );
      o.put( "warning", "Did not finish in " + ( esperaMs / 1000 ) + " s and was stopped (stopAll). Raise wait_s or "
        + "use mode='visible'." );
    }
    liberarLog( j.getLogChannelId() );
    return o;
  }

  /**
   * Padre de log propio para cada corrida de segundo plano: asi tiene un canal de log nuevo y no
   * hereda lineas de corridas anteriores del mismo documento (el registro reutiliza ids).
   */
  static LoggingObjectInterface padreDeLog() {
    SimpleLoggingObject p = new SimpleLoggingObject( "Pentaho MCP", LoggingObjectType.GENERAL, null );
    p.setContainerObjectId( UUID.randomUUID().toString() );
    p.setLogLevel( LogLevel.BASIC );
    return p;
  }

  static void esperarFin( Trans t, long ms ) {
    long limite = System.currentTimeMillis() + ms;
    while ( !t.isFinished() && System.currentTimeMillis() < limite ) {
      dormir( 50 );
    }
    if ( !t.isFinished() ) {
      t.killAll();
    }
  }

  // ---------------------------------------------------------------- estado

  /** Estado de una Trans (de segundo plano o la de la GUI). inicio <= 0 = no medir segundos. */
  static ObjectNode estadoTrans( Trans t, long inicio ) {
    ObjectNode o = Tools.obj();
    String estado;
    if ( t.isFinished() ) { // un prepareExecution fallido tambien deja la Trans terminada (con errores)
      estado = t.getErrors() > 0 ? "finished_with_errors" : ( t.isStopped() ? "stopped" : "finished" );
    } else if ( t.isPreparing() || t.isInitializing() ) {
      estado = "preparing";
    } else if ( t.isRunning() ) {
      estado = "running";
    } else {
      estado = "not_started";
    }
    o.put( "state", estado );
    o.put( "kettle_status", t.getStatus() );
    if ( inicio > 0 ) {
      o.put( "seconds", ( System.currentTimeMillis() - inicio ) / 1000.0 );
    }
    o.put( "errors", t.getErrors() );
    o.set( "metrics", metricas( t ) );
    return o;
  }

  /** Metricas por step (sumando copias), en el orden de la transformacion. */
  static ArrayNode metricas( Trans t ) {
    Map<String, long[]> m = new LinkedHashMap<String, long[]>();
    Map<String, String> estado = new LinkedHashMap<String, String>();
    List<StepMetaDataCombi> steps = t.getSteps();
    if ( steps != null ) {
      for ( StepMetaDataCombi c : new ArrayList<StepMetaDataCombi>( steps ) ) {
        StepInterface s = c.step;
        if ( s == null ) {
          continue;
        }
        long[] v = m.get( c.stepname );
        if ( v == null ) {
          v = new long[ 7 ];
          m.put( c.stepname, v );
        }
        v[ 0 ] += s.getLinesRead();
        v[ 1 ] += s.getLinesWritten();
        v[ 2 ] += s.getLinesInput();
        v[ 3 ] += s.getLinesOutput();
        v[ 4 ] += s.getLinesUpdated();
        v[ 5 ] += s.getLinesRejected();
        v[ 6 ] += s.getErrors();
        try {
          estado.put( c.stepname, s.getStatus().getDescription() );
        } catch ( Exception ignored ) {
          // estado opcional
        }
      }
    }
    ArrayNode arr = Tools.arr();
    for ( Map.Entry<String, long[]> e : m.entrySet() ) {
      long[] v = e.getValue();
      ObjectNode x = arr.addObject();
      x.put( "step", e.getKey() );
      x.put( "read", v[ 0 ] );
      x.put( "written", v[ 1 ] );
      if ( v[ 2 ] > 0 ) {
        x.put( "input", v[ 2 ] );
      }
      if ( v[ 3 ] > 0 ) {
        x.put( "output", v[ 3 ] );
      }
      if ( v[ 4 ] > 0 ) {
        x.put( "updated", v[ 4 ] );
      }
      x.put( "rejected", v[ 5 ] );
      x.put( "errors", v[ 6 ] );
      if ( estado.get( e.getKey() ) != null ) {
        x.put( "state", estado.get( e.getKey() ) );
      }
    }
    return arr;
  }

  static ObjectNode estadoJob( Job j, long inicio ) {
    ObjectNode o = Tools.obj();
    boolean fin = j.isFinished();
    o.put( "state", fin ? ( j.isStopped() ? "stopped" : "finished" ) : ( j.isActive() ? "running" : "not_started" ) );
    o.put( "kettle_status", j.getStatus() );
    if ( inicio > 0 ) {
      o.put( "seconds", ( System.currentTimeMillis() - inicio ) / 1000.0 );
    }
    Result r = j.getResult();
    long errores = r == null ? j.getErrors() : Math.max( r.getNrErrors(), j.getErrors() );
    if ( fin && r != null ) {
      o.put( "result", r.getResult() ? "ok" : "failed" );
    }
    o.put( "errors", errores );
    if ( r != null ) {
      ObjectNode lineas = Tools.obj();
      putSi( lineas, "read", r.getNrLinesRead() );
      putSi( lineas, "written", r.getNrLinesWritten() );
      putSi( lineas, "input", r.getNrLinesInput() );
      putSi( lineas, "output", r.getNrLinesOutput() );
      putSi( lineas, "updated", r.getNrLinesUpdated() );
      putSi( lineas, "rejected", r.getNrLinesRejected() );
      if ( lineas.size() > 0 ) {
        o.set( "lines", lineas );
      }
    }
    ArrayNode entradas = o.putArray( "entries" );
    List<JobEntryResult> rs = j.getJobEntryResults();
    if ( rs != null ) {
      for ( JobEntryResult er : new ArrayList<JobEntryResult>( rs ) ) {
        ObjectNode x = entradas.addObject();
        x.put( "entry", er.getJobEntryName() );
        Result res = er.getResult();
        if ( res != null ) {
          x.put( "result", res.getResult() ? "ok" : "failed" );
          if ( res.getNrErrors() > 0 ) {
            x.put( "errors", res.getNrErrors() );
          }
        }
        if ( res != null && !res.getResult() && er.getComment() != null && !er.getComment().isEmpty() ) {
          x.put( "detail", er.getComment() );
        }
      }
    }
    o.put( "log", colaLog( j.getLogChannelId(), errores > 0 || ( fin && r != null && !r.getResult() ) ) );
    return o;
  }

  private static void putSi( ObjectNode o, String k, long v ) {
    if ( v > 0 ) {
      o.put( k, v );
    }
  }

  // ---------------------------------------------------------------- log

  /**
   * Cola del log de un canal (con sus hijos). Con errores: desde un poco antes del primer ERROR,
   * y si no entra, el primer tramo con error mas las ultimas lineas.
   */
  static String colaLog( String canal, boolean conErrores ) {
    if ( canal == null ) {
      return "";
    }
    String todo;
    try {
      todo = KettleLogStore.getAppender().getBuffer( canal, false ).toString();
    } catch ( Exception e ) {
      return "(could not read the log: " + e + ")";
    }
    String[] ls = todo.split( "\\r?\\n" );
    List<String> lineas = new ArrayList<String>();
    for ( String l : ls ) {
      if ( !l.trim().isEmpty() ) {
        // "2026/10/20 11:12:04 - Paso.0 - ERROR (version 9.4..., build ... by buildguy) : x" -> "11:12:04 - Paso.0 - ERROR: x"
        l = l.replaceFirst( "^\\d{4}/\\d{2}/\\d{2} ", "" ).replaceAll( "ERROR \\(version [^)]*\\) ?:", "ERROR:" );
        lineas.add( l.length() > 400 ? l.substring( 0, 400 ) + "..." : l );
      }
    }
    int n = lineas.size();
    if ( n <= LINEAS_LOG ) {
      return unir( lineas, 0, n );
    }
    int primerError = -1;
    if ( conErrores ) {
      for ( int i = 0; i < n; i++ ) {
        if ( lineas.get( i ).contains( "ERROR" ) ) {
          primerError = i;
          break;
        }
      }
    }
    int desdeCola = n - LINEAS_LOG;
    if ( primerError < 0 || primerError >= desdeCola ) {
      return "(... " + desdeCola + " earlier lines)\n" + unir( lineas, desdeCola, n );
    }
    int a = Math.max( 0, primerError - 3 );
    int b = Math.min( n, a + 35 );
    int c = Math.max( b, n - 25 );
    StringBuilder sb = new StringBuilder();
    if ( a > 0 ) {
      sb.append( "(... " ).append( a ).append( " earlier lines)\n" );
    }
    sb.append( unir( lineas, a, b ) );
    if ( c > b ) {
      sb.append( "\n(... " ).append( c - b ).append( " lines)\n" );
    }
    sb.append( unir( lineas, c, n ) );
    return sb.toString();
  }

  private static String unir( List<String> l, int desde, int hasta ) {
    StringBuilder sb = new StringBuilder();
    for ( int i = desde; i < hasta; i++ ) {
      if ( sb.length() > 0 ) {
        sb.append( '\n' );
      }
      sb.append( l.get( i ) );
    }
    return sb.toString();
  }

  /** Corridas de segundo plano: soltar sus lineas de log y su registro (no las ve nadie mas). */
  static void liberarLog( String canal ) {
    if ( canal == null ) {
      return;
    }
    try {
      KettleLogStore.discardLines( canal, false );
      LoggingRegistry.getInstance().removeIncludingChildren( canal );
    } catch ( Throwable ignored ) {
      // no es grave
    }
  }

  /** Primera linea con texto del mensaje (las KettleException traen el log pegado debajo). */
  static String primeraLinea( Throwable e ) {
    String m = e.getMessage();
    if ( m != null ) {
      for ( String l : m.split( "\\r?\\n" ) ) {
        if ( !l.trim().isEmpty() ) {
          return l.trim();
        }
      }
    }
    return e.toString();
  }

  static String mensaje( Throwable e ) {
    Throwable c = e;
    StringBuilder sb = new StringBuilder();
    Set<String> vistos = new HashSet<String>();
    while ( c != null ) {
      String m = c.getMessage();
      if ( m != null ) {
        m = m.trim();
        if ( !m.isEmpty() && vistos.add( m ) ) {
          if ( sb.length() > 0 ) {
            sb.append( " | " );
          }
          sb.append( m );
        }
      }
      c = c.getCause() == c ? null : c.getCause();
    }
    String s = sb.length() == 0 ? e.toString() : sb.toString();
    s = s.replaceAll( "\\s*\\r?\\n\\s*", " " );
    return s.length() > 1500 ? s.substring( 0, 1500 ) + "..." : s;
  }
}
