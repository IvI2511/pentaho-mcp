package io.github.ivi2511.pentahomcp;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

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

    s.add( Tools.tool( "campos_step",
      "Que campos (nombre, tipo, longitud, precision, formato y step de origen) SALEN de un step, o con "
        + "lado='entrada' cuales le LLEGAN (mas los de sus streams de busqueda en lookups). Usala antes de "
        + "configurar el step siguiente, para escribir nombres de campo que existan. No ejecuta nada.",
      Tools.schema().documento()
        .str( "step", "Nombre del step", true )
        .strEnum( "lado", "salida (por defecto) o entrada", false, "salida", "entrada" ).build(),
      a -> {
        final String step = Tools.str( a, "step" );
        final boolean entrada = "entrada".equals( Tools.optStr( a, "lado", "salida" ) );
        final Copia c = copia( a, true );
        ObjectNode o = RunMotor.enSegundoPlano( () -> RunMotor.camposStep( (TransMeta) c.meta, step, entrada ),
          MS_CAMPOS, null, "El calculo de campos" );
        return RunMotor.json( o );
      } ) );

    s.add( Tools.tool( "validar",
      "Valida la transformacion (checkSteps) o el job (checkJobEntries) como 'Verificar' de Spoon, sin abrir "
        + "dialogos, y suma chequeos propios: steps sin hops, hops deshabilitados, plugins que faltan, entradas "
        + "de job que no se alcanzan desde START. Devuelve errores y advertencias agrupados por step "
        + "(detalle=true agrega los OK y comentarios). Usala despues de una tanda de cambios y antes de ejecutar.",
      Tools.schema().documento()
        .bool( "detalle", "Incluir tambien los resultados OK y los comentarios (por defecto false)", false ).build(),
      a -> {
        final boolean detalle = Tools.optBool( a, "detalle", false );
        final Copia c = copia( a, false );
        ObjectNode o = RunMotor.enSegundoPlano(
          () -> RunMotor.validar( c.meta, detalle, c.rep, c.meta.getMetaStore() ), MS_VALIDAR, null, "La validacion" );
        ObjectNode r = Tools.obj();
        r.put( "documento", c.id );
        r.setAll( o );
        return RunMotor.json( r );
      } ) );

    s.add( Tools.tool( "preview_step",
      "Muestra las primeras filas que SALEN de un step, ejecutando en segundo plano (sin dialogos y sin tocar "
        + "el documento) una copia con SOLO ese step y los que le llegan por hops; los steps de mas adelante no "
        + "corren, asi que no se escriben sus archivos ni tablas. Si el step pedido o uno anterior es una salida, "
        + "esa si escribe (se avisa). No hace falta guardar antes. Devuelve campos, filas formateadas, errores, "
        + "metricas por step y el log si hubo errores. Usala para comprobar cada step mientras armas.",
      Tools.schema().documento()
        .str( "step", "Nombre del step a previsualizar", true )
        .integer( "filas", "Cuantas filas traer (por defecto 20, maximo 1000)", false )
        .integer( "timeout_s", "Corta la ejecucion a los N segundos (por defecto 60, maximo 600)", false ).build(),
      a -> {
        final String step = Tools.str( a, "step" );
        final int filas = Math.max( 1, Math.min( 1000, Tools.optInt( a, "filas", 20 ) ) );
        final int timeout = Math.max( 1, Math.min( 600, Tools.optInt( a, "timeout_s", 60 ) ) );
        final Copia c = copia( a, true );
        // el propio preview corta a los timeout_s; el margen es para el stopAll y el armado
        ObjectNode o = RunMotor.enSegundoPlano(
          () -> RunMotor.preview( (TransMeta) c.meta, step, filas, timeout * 1000L ), timeout * 1000L + 30000, null,
          "El preview" );
        ObjectNode r = Tools.obj();
        r.put( "documento", c.id );
        r.setAll( o );
        return RunMotor.json( r );
      } ) );

    s.add( Tools.tool( "ejecutar",
      "Ejecuta la transformacion o el job ENTERO. modo='visible' (por defecto): como apretar Run en Spoon, el "
        + "usuario ve las metricas y el log en la GUI; guarda antes si hay cambios (guardar_antes) y necesita que "
        + "el documento tenga archivo. Espera hasta esperar_s y devuelve el estado; si sigue corriendo, segui con "
        + "estado_ejecucion o detener. modo='segundo_plano': corre una copia en la JVM sin GUI (no hace falta "
        + "guardar) y la corta si pasa esperar_s. En los dos modos se ESCRIBEN DE VERDAD las salidas (archivos, "
        + "tablas). Para solo mirar filas usa preview_step.",
      Tools.schema().documento()
        .strEnum( "modo", "visible (por defecto) o segundo_plano", false, "visible", "segundo_plano" )
        .integer( "esperar_s", "Segundos a esperar el final (por defecto 60, maximo 600)", false )
        .bool( "guardar_antes", "Modo visible: guardar en su archivo si hay cambios (por defecto true)", false )
        .build(),
      a -> {
        final int espera = Math.max( 0, Math.min( 600, Tools.optInt( a, "esperar_s", 60 ) ) );
        if ( "segundo_plano".equals( Tools.optStr( a, "modo", "visible" ) ) ) {
          final Copia c = copia( a, false );
          ObjectNode o = RunMotor.enSegundoPlano( () -> c.meta instanceof TransMeta
              ? RunMotor.ejecutarTrans( (TransMeta) c.meta, espera * 1000L )
              : RunMotor.ejecutarJob( (JobMeta) c.meta, c.rep, espera * 1000L ),
            espera * 1000L + 30000, null, "La ejecucion" );
          ObjectNode r = Tools.obj();
          r.put( "documento", c.id );
          r.put( "modo", "segundo_plano" );
          if ( c.meta.getFilename() == null ) {
            r.put( "aviso_archivo", "El documento no tiene archivo: ${Internal.Entry.Current.Directory} y las rutas "
              + "relativas no apuntan a ningun lado" );
          }
          r.setAll( o );
          return RunMotor.json( r );
        }
        return RunMotor.json( ejecutarVisible( a, espera, Tools.optBool( a, "guardar_antes", true ) ) );
      } ) );

    s.add( Tools.tool( "estado_ejecucion",
      "Estado de la ultima ejecucion visible (la del boton Run de Spoon) de una transformacion o job: si sigue "
        + "corriendo, errores, metricas por step o resultado por entrada del job, y la cola del log. Usala cuando "
        + "'ejecutar' devolvio que seguia corriendo, o para leer lo que corrio el usuario.",
      Tools.schema().documento().build(),
      a -> {
        final Object[] g = Docs.ui( () -> corrida( Docs.resolver( a ) ) );
        return RunMotor.json( estado( g ) );
      } ) );

    s.add( Tools.tool( "detener",
      "Detiene la ejecucion visible (como el boton Stop de Spoon) de una transformacion o job y devuelve como "
        + "quedo. No abre dialogos. Las corridas de preview_step y de modo segundo_plano se cortan solas por timeout.",
      Tools.schema().documento().build(),
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
          o.put( "aviso", "Se pidio detener pero todavia hay steps/entradas terminando; consulta estado_ejecucion" );
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
        throw new ToolError( "'" + nombre + "' no tiene archivo y Spoon no ejecuta sin guardar: usa 'guardar' con una "
          + "ruta ." + ( m instanceof JobMeta ? "kjb" : "ktr" ) + " (o ejecutar con modo='segundo_plano')" );
      }
      if ( sp.getRepository() != null && m.getObjectId() == null ) {
        throw new ToolError( "Spoon esta conectado a un repositorio y '" + nombre + "' es un archivo: el Run de Spoon "
          + "abriria un dialogo. Usa modo='segundo_plano'." );
      }
      Object[] antes = corridaOpcional( m );
      if ( antes[ 1 ] != null && !terminado( antes ) ) {
        throw new ToolError( "'" + nombre + "' ya se esta ejecutando. Usa estado_ejecucion para seguirla o detener." );
      }
      String guardado = null;
      if ( m.hasChanged() ) {
        if ( !guardarAntes ) {
          throw new ToolError( "'" + nombre + "' tiene cambios sin guardar y Spoon no ejecuta sin guardar: usa "
            + "'guardar' o pasa guardar_antes=true" );
        }
        guardado = guardar( sp, m );
      }
      boolean dialogo = m.isShowDialog();
      m.setShowDialog( false ); // si no, abre el dialogo modal de configuracion de la ejecucion
      try {
        if ( m instanceof TransMeta ) {
          TransMeta tm = (TransMeta) m;
          if ( sp.getActiveTransGraph() == null || sp.getActiveTransGraph().getTransMeta() != tm ) {
            throw new ToolError( "No se pudo activar la pestania de '" + nombre + "'" );
          }
          TransExecutionConfiguration cfg = sp.getTransExecutionConfiguration();
          LogLevel nivel = cfg != null && cfg.getLogLevel() != null ? cfg.getLogLevel() : LogLevel.BASIC;
          // el delegate y no Spoon.executeTransformation: ese lanza un hilo + asyncExec (volveria antes de
          // arrancar, con showDialog ya restaurado) y envuelve los errores en un ErrorDialog modal
          sp.delegates.trans.executeTransformation( tm, true, false, false, false, false, null, false, nivel );
        } else {
          JobMeta jm = (JobMeta) m;
          if ( sp.getActiveJobGraph() == null || sp.getActiveJobGraph().getJobMeta() != jm ) {
            throw new ToolError( "No se pudo activar la pestania de '" + nombre + "'" );
          }
          sp.delegates.jobs.executeJob( jm, true, false, null, false, null, 0 ); // idem: sin ErrorDialog
        }
      } finally {
        m.setShowDialog( dialogo );
      }
      Object[] ahora = corridaOpcional( m );
      if ( ahora[ 1 ] == null || ahora[ 1 ] == antes[ 1 ] ) {
        throw new ToolError( "Spoon no arranco la ejecucion de '" + nombre + "' (mira si quedo un dialogo abierto "
          + "o el log de Spoon)" );
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
    o.put( "documento", (String) g[ 2 ] );
    o.put( "modo", "visible" );
    if ( g[ 3 ] != null ) {
      o.put( "guardado_en", (String) g[ 3 ] );
    }
    o.setAll( estado( g ) );
    if ( !terminado( g ) ) {
      o.put( "aviso", "Sigue corriendo despues de " + espera + " s. Consulta estado_ejecucion o usa detener." );
    }
    return o;
  }

  /** {graph, Trans|Job} de la corrida visible del documento; ToolError si nunca se ejecuto. (UI) */
  private static Object[] corrida( AbstractMeta m ) throws ToolError {
    Object[] x = corridaOpcional( m );
    if ( x[ 1 ] == null ) {
      throw new ToolError( "'" + Docs.nombre( m ) + "' no se ejecuto en Spoon en esta sesion (ejecutar con modo visible)" );
    }
    return x;
  }

  /** {graph, Trans|Job o null}. (UI) */
  private static Object[] corridaOpcional( AbstractMeta m ) throws ToolError {
    Spoon sp = Docs.spoon();
    if ( m instanceof TransMeta ) {
      TransGraph tg = sp.delegates.trans.findTransGraphOfTransformation( (TransMeta) m );
      if ( tg == null ) {
        throw new ToolError( "No encuentro el lienzo de '" + Docs.nombre( m ) + "'" );
      }
      return new Object[] { tg, tg.getTrans() };
    }
    JobGraph jg = sp.delegates.jobs.findJobGraphOfJob( (JobMeta) m );
    if ( jg == null ) {
      throw new ToolError( "No encuentro el lienzo de '" + Docs.nombre( m ) + "'" );
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
   * Guarda en su archivo sin pasar por los dialogos de Spoon (igual que la herramienta 'guardar'). (UI)
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
