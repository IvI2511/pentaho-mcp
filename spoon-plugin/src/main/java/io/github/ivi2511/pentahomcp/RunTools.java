package io.github.ivi2511.pentahomcp;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.logging.LogLevel;
import org.pentaho.di.core.vfs.KettleVFS;
import org.pentaho.di.core.xml.XMLHandler;
import org.pentaho.di.job.Job;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.repository.Repository;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransExecutionConfiguration;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.ui.spoon.Spoon;
import org.pentaho.di.ui.spoon.job.JobGraph;
import org.pentaho.di.ui.spoon.trans.TransGraph;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;

/**
 * Probar lo que se esta armando: campos que salen de cada step, validar, preview de filas y
 * ejecutar (visible en la GUI o en segundo plano).
 *
 * Hilos: en la UI solo se toma un snapshot (realClone) o se aprieta "Run"/"Stop" de Spoon; todo lo
 * pesado (getStepFields, checkSteps, ejecutar, esperar) corre en RunMotor, fuera del hilo de UI.
 */
final class RunTools {

  private static final long MS_CAMPOS = 60000;
  private static final long MS_VALIDAR = 120000;

  private RunTools() {
  }

  /** Snapshot tomado en la UI: la copia y el repositorio de Spoon. */
  private static final class Copia {
    AbstractMeta meta;
    Repository rep;
    String id;
  }

  static void register( McpHttpServer s ) {

    s.add( Tools.tool( "get_step_fields",
      "Which fields (name, type, length, precision, format and origin step) come OUT of a step, or with "
        + "side='input' which ones come IN to it (plus the fields of its info streams, for lookups). Use it before "
        + "configuring the next step, so you write field names that exist. Runs nothing.",
      Tools.schema().document()
        .str( "step", "Step name", true )
        .strEnum( "side", "output (default) or input", false, "output", "input" ).build(),
      a -> {
        final String step = Tools.str( a, "step" );
        final String lado = Tools.optStr( a, "side", "output" ).trim().toLowerCase( Locale.ROOT );
        if ( !lado.equals( "output" ) && !lado.equals( "input" ) ) {
          throw new ToolError( "'side' must be 'output' or 'input', got: " + lado );
        }
        final boolean entrada = lado.equals( "input" );
        final Copia c = copia( a, true );
        ObjectNode o = RunMotor.enSegundoPlano( () -> RunMotor.camposStep( (TransMeta) c.meta, step, entrada ),
          MS_CAMPOS, null, "Computing the fields" );
        return RunMotor.json( o );
      } ) );

    s.add( Tools.tool( "validate",
      "Validates the transformation (checkSteps) or the job (checkJobEntries) like Spoon's 'Verify', without "
        + "opening dialogs, and adds its own checks: steps without hops, disabled hops, missing plugins, steps with "
        + "several outputs in distribute mode, job entries that cannot be reached from START. Returns errors and "
        + "warnings grouped by step (detail=true adds the OK results and comments). Use it after a batch of changes "
        + "and before running.",
      Tools.schema().document()
        .bool( "detail", "Also include the OK results and the comments (default false)", false ).build(),
      a -> {
        final boolean detalle = Tools.optBool( a, "detail", false );
        final Copia c = copia( a, false );
        ObjectNode o = RunMotor.enSegundoPlano(
          () -> RunMotor.validar( c.meta, detalle, c.rep, c.meta.getMetaStore() ), MS_VALIDAR, null, "The validation" );
        ObjectNode r = Tools.obj();
        r.put( "document", c.id );
        r.setAll( o );
        return RunMotor.json( r );
      } ) );

    s.add( Tools.tool( "preview_step",
      "Shows the first rows that come OUT of a step, by running in the background (no dialogs, without touching "
        + "the document) a copy with ONLY that step and the steps that feed it through hops; the steps further "
        + "ahead do not run, so their files and tables are not written. If the requested step or an upstream one is "
        + "an output, that one does write (you get a warning). No need to save first. Returns fields, formatted "
        + "rows, errors, metrics per step and the log if there were errors. Use it to check each step as you build.",
      Tools.schema().document()
        .str( "step", "Name of the step to preview", true )
        .integer( "rows", "How many rows to fetch (default 20, max 1000)", false )
        .integer( "timeout_s", "Stop the run after N seconds (default 60, max 600)", false ).build(),
      a -> {
        final String step = Tools.str( a, "step" );
        final int filas = Math.max( 1, Math.min( 1000, Tools.optInt( a, "rows", 20 ) ) );
        final int timeout = Math.max( 1, Math.min( 600, Tools.optInt( a, "timeout_s", 60 ) ) );
        final Copia c = copia( a, true );
        // el propio preview corta a los timeout_s; el margen es para el stopAll y el armado
        ObjectNode o = RunMotor.enSegundoPlano(
          () -> RunMotor.preview( (TransMeta) c.meta, step, filas, timeout * 1000L ), timeout * 1000L + 30000, null,
          "The preview" );
        ObjectNode r = Tools.obj();
        r.put( "document", c.id );
        r.setAll( o );
        return RunMotor.json( r );
      } ) );

    s.add( Tools.tool( "run",
      "Runs the WHOLE transformation or job. mode='visible' (default): like pressing Run in Spoon, the user sees "
        + "the metrics and the log in the GUI; it saves first if there are changes (save_first) and the document "
        + "needs a file. Waits up to wait_s and returns the state; if it is still running, follow up with "
        + "get_run_status or stop_run. mode='background': runs a copy in the JVM without GUI (no need to save) and "
        + "stops it if it goes past wait_s. In both modes the outputs are REALLY WRITTEN (files, tables). To just "
        + "look at rows, use preview_step.",
      Tools.schema().document()
        .strEnum( "mode", "visible (default) or background", false, "visible", "background" )
        .integer( "wait_s", "Seconds to wait for the end (default 60, max 600)", false )
        .bool( "save_first", "Visible mode: save to its file if there are changes (default true)", false )
        .build(),
      a -> {
        final int espera = Math.max( 0, Math.min( 600, Tools.optInt( a, "wait_s", 60 ) ) );
        final String modo = Tools.optStr( a, "mode", "visible" ).trim().toLowerCase( Locale.ROOT );
        if ( !modo.equals( "visible" ) && !modo.equals( "background" ) ) {
          throw new ToolError( "'mode' must be 'visible' or 'background', got: " + modo );
        }
        if ( modo.equals( "background" ) ) {
          final Copia c = copia( a, false );
          ObjectNode o = RunMotor.enSegundoPlano( () -> c.meta instanceof TransMeta
              ? RunMotor.ejecutarTrans( (TransMeta) c.meta, espera * 1000L )
              : RunMotor.ejecutarJob( (JobMeta) c.meta, c.rep, espera * 1000L ),
            espera * 1000L + 30000, null, "The run" );
          ObjectNode r = Tools.obj();
          r.put( "document", c.id );
          r.put( "mode", "background" );
          if ( c.meta.getFilename() == null ) {
            r.put( "file_warning", "The document has no file: ${Internal.Entry.Current.Directory} and relative "
              + "paths point nowhere" );
          }
          r.setAll( o );
          return RunMotor.json( r );
        }
        return RunMotor.json( ejecutarVisible( a, espera, Tools.optBool( a, "save_first", true ) ) );
      } ) );

    s.add( Tools.tool( "get_run_status",
      "State of the last visible run (the one from Spoon's Run button) of a transformation or job: whether it is "
        + "still running, errors, metrics per step or result per job entry, and the tail of the log. Use it when "
        + "'run' reported it was still running, or to read what the user ran.",
      Tools.schema().document().build(),
      a -> {
        final Object[] g = Docs.ui( () -> corrida( Docs.resolver( a ) ) );
        return RunMotor.json( estado( g ) );
      } ) );

    s.add( Tools.tool( "stop_run",
      "Stops the visible run (like Spoon's Stop button) of a transformation or job and returns how it ended up. "
        + "Opens no dialogs. Runs from preview_step and from mode='background' stop on their own by timeout.",
      Tools.schema().document().build(),
      a -> {
        final Object[] g = Docs.ui( () -> {
          Object[] x = corrida( Docs.resolver( a ) );
          if ( x[ 1 ] instanceof Trans ) {
            Trans t = (Trans) x[ 1 ];
            if ( t.isFinished() ) {
              return x;
            }
            TransGraph tg = (TransGraph) x[ 0 ];
            if ( tg.isRunning() && !campoBooleano( tg, "safeStopping" ) ) {
              tg.stop(); // stopAll + log + botones; con safeStopping abriria un modal
            } else {
              t.stopAll();
            }
          } else {
            Job j = (Job) x[ 1 ];
            if ( !j.isFinished() ) {
              JobGraph jg = (JobGraph) x[ 0 ];
              if ( j.isActive() && j.isInitialized() ) {
                jg.stopJob(); // stopAll + espera hasta 5 s (lo mismo que el boton Stop)
              } else {
                j.stopAll();
              }
            }
          }
          return x;
        } );
        long limite = System.currentTimeMillis() + 15000;
        while ( System.currentTimeMillis() < limite && !terminado( g ) ) {
          RunMotor.dormir( 200 );
        }
        ObjectNode o = estado( g );
        if ( !terminado( g ) ) {
          o.put( "warning", "Stop was requested but some steps/entries are still finishing; check get_run_status" );
        }
        return RunMotor.json( o );
      } ) );
  }

  // ---------------------------------------------------------------- snapshot (UI)

  /** En la UI: resuelve el documento y toma una copia independiente para trabajar fuera de la UI. */
  private static Copia copia( final JsonNode a, final boolean soloTrans ) throws Exception {
    return Docs.ui( () -> {
      AbstractMeta m = soloTrans ? Docs.trans( a ) : Docs.resolver( a );
      Spoon sp = Docs.spoon();
      Copia c = new Copia();
      c.id = Docs.id( m );
      c.rep = sp.getRepository();
      if ( m instanceof TransMeta ) {
        TransMeta t = (TransMeta) ( (TransMeta) m ).realClone( false );
        t.setRepository( c.rep );
        t.setMetaStore( sp.getMetaStore() );
        RunMotor.desligar( t ); // sin esto la copia comparte steps con el documento vivo
        c.meta = t;
      } else {
        JobMeta j = (JobMeta) ( (JobMeta) m ).realClone( false );
        j.setRepository( c.rep );
        j.setMetaStore( sp.getMetaStore() );
        RunMotor.desligar( j );
        c.meta = j;
      }
      c.meta.setFilename( m.getFilename() ); // por si el clon no la trae: la necesitan las variables internas
      c.meta.setInternalKettleVariables();
      return c;
    } );
  }

  // ---------------------------------------------------------------- ejecucion visible

  /**
   * Receta de docs/api-spoon.md 8.a: activar pestania, guardar si hace falta, executeTransformation /
   * executeJob con showDialog=false, y esperar fuera de la UI.
   */
  private static ObjectNode ejecutarVisible( final JsonNode a, int espera, final boolean guardarAntes ) throws Exception {
    final Object[] g = Docs.ui( () -> {
      Spoon sp = Docs.spoon();
      AbstractMeta m = Docs.resolver( a ); // activa la pestania: Spoon ejecuta la activa
      String nombre = Docs.nombre( m );
      if ( m.getFilename() == null ) {
        throw new ToolError( "'" + nombre + "' has no file and Spoon does not run unsaved documents: use 'save' with a "
          + "." + ( m instanceof JobMeta ? "kjb" : "ktr" ) + " path (or run with mode='background')" );
      }
      if ( sp.getRepository() != null && m.getObjectId() == null ) {
        throw new ToolError( "Spoon is connected to a repository and '" + nombre + "' is a file: Spoon's Run would "
          + "open a dialog. Use mode='background'." );
      }
      Object[] antes = corridaOpcional( m );
      if ( antes[ 1 ] != null && !terminado( antes ) ) {
        throw new ToolError( "'" + nombre + "' is already running. Use get_run_status to follow it, or stop_run." );
      }
      String guardado = null;
      if ( m.hasChanged() ) {
        if ( !guardarAntes ) {
          throw new ToolError( "'" + nombre + "' has unsaved changes and Spoon does not run unsaved documents: use "
            + "'save' or pass save_first=true" );
        }
        guardado = guardar( sp, m );
      }
      boolean dialogo = m.isShowDialog();
      m.setShowDialog( false ); // si no, abre el dialogo modal de configuracion de la ejecucion
      try {
        if ( m instanceof TransMeta ) {
          TransMeta tm = (TransMeta) m;
          if ( sp.getActiveTransGraph() == null || sp.getActiveTransGraph().getTransMeta() != tm ) {
            throw new ToolError( "Could not activate the tab of '" + nombre + "'" );
          }
          TransExecutionConfiguration cfg = sp.getTransExecutionConfiguration();
          LogLevel nivel = cfg != null && cfg.getLogLevel() != null ? cfg.getLogLevel() : LogLevel.BASIC;
          // el delegate y no Spoon.executeTransformation: ese lanza un hilo + asyncExec (volveria antes de
          // arrancar, con showDialog ya restaurado) y envuelve los errores en un ErrorDialog modal
          sp.delegates.trans.executeTransformation( tm, true, false, false, false, false, null, false, nivel );
        } else {
          JobMeta jm = (JobMeta) m;
          if ( sp.getActiveJobGraph() == null || sp.getActiveJobGraph().getJobMeta() != jm ) {
            throw new ToolError( "Could not activate the tab of '" + nombre + "'" );
          }
          sp.delegates.jobs.executeJob( jm, true, false, null, false, null, 0 ); // idem: sin ErrorDialog
        }
      } finally {
        m.setShowDialog( dialogo );
      }
      Object[] ahora = corridaOpcional( m );
      if ( ahora[ 1 ] == null || ahora[ 1 ] == antes[ 1 ] ) {
        throw new ToolError( "Spoon did not start the run of '" + nombre + "' (check whether a dialog was left open, "
          + "or Spoon's log)" );
      }
      return new Object[] { ahora[ 0 ], ahora[ 1 ], Docs.id( m ), guardado };
    } );
    long inicio = System.currentTimeMillis();
    long limite = inicio + espera * 1000L;
    RunMotor.dormir( 300 );
    while ( System.currentTimeMillis() < limite && !terminado( g ) ) {
      RunMotor.dormir( 250 );
    }
    ObjectNode o = Tools.obj();
    o.put( "document", (String) g[ 2 ] );
    o.put( "mode", "visible" );
    if ( g[ 3 ] != null ) {
      o.put( "saved_to", (String) g[ 3 ] );
    }
    o.setAll( estado( g ) );
    if ( !terminado( g ) ) {
      o.put( "warning", "Still running after " + espera + " s. Check get_run_status or use stop_run." );
    }
    return o;
  }

  /** {graph, Trans|Job} de la corrida visible del documento; ToolError si nunca se ejecuto. (UI) */
  private static Object[] corrida( AbstractMeta m ) throws ToolError {
    Object[] x = corridaOpcional( m );
    if ( x[ 1 ] == null ) {
      throw new ToolError( "'" + Docs.nombre( m ) + "' has not been run in Spoon in this session (use run with "
        + "mode='visible')" );
    }
    return x;
  }

  /** {graph, Trans|Job o null}. (UI) */
  private static Object[] corridaOpcional( AbstractMeta m ) throws ToolError {
    Spoon sp = Docs.spoon();
    if ( m instanceof TransMeta ) {
      TransGraph tg = sp.delegates.trans.findTransGraphOfTransformation( (TransMeta) m );
      if ( tg == null ) {
        throw new ToolError( "Cannot find the canvas of '" + Docs.nombre( m ) + "'" );
      }
      return new Object[] { tg, tg.getTrans() };
    }
    JobGraph jg = sp.delegates.jobs.findJobGraphOfJob( (JobMeta) m );
    if ( jg == null ) {
      throw new ToolError( "Cannot find the canvas of '" + Docs.nombre( m ) + "'" );
    }
    return new Object[] { jg, jg.getJob() };
  }

  /**
   * Si la corrida visible ya no avanza. Un prepareExecution fallido (Spoon solo lo loguea) tambien
   * deja la Trans en isFinished() con errores (verificado sin GUI).
   */
  private static boolean terminado( Object[] g ) {
    if ( g[ 1 ] instanceof Trans ) {
      return ( (Trans) g[ 1 ] ).isFinished();
    }
    Job j = (Job) g[ 1 ];
    return j == null || j.isFinished() || ( j.isStopped() && !j.isActive() );
  }

  private static ObjectNode estado( Object[] g ) {
    if ( g[ 1 ] instanceof Trans ) {
      Trans t = (Trans) g[ 1 ];
      ObjectNode o = RunMotor.estadoTrans( t, 0 );
      o.put( "log", RunMotor.colaLog( t.getLogChannelId(), t.getErrors() > 0 ) );
      return o;
    }
    return RunMotor.estadoJob( (Job) g[ 1 ], 0 );
  }

  /** Lee un boolean privado del lienzo (safeStopping); false si no se puede. */
  private static boolean campoBooleano( Object o, String nombre ) {
    try {
      Field f = o.getClass().getDeclaredField( nombre );
      f.setAccessible( true );
      return f.getBoolean( o );
    } catch ( Throwable e ) {
      return false;
    }
  }

  /**
   * Guarda en su archivo sin pasar por los dialogos de Spoon (igual que la herramienta 'save'). (UI)
   */
  private static String guardar( Spoon sp, AbstractMeta m ) throws Exception {
    String archivo = m.getFilename();
    // KettleVFS acepta rutas sueltas y file:/// (lo mismo que usa Spoon.saveMeta)
    try ( Writer w = new OutputStreamWriter( KettleVFS.getOutputStream( archivo, false ), StandardCharsets.UTF_8 ) ) {
      w.write( XMLHandler.getXMLHeader() );
      w.write( m.getXML() );
    }
    m.clearChanged();
    sp.delegates.tabs.renameTabs();
    sp.refreshTree();
    sp.setShellText();
    return archivo;
  }
}
