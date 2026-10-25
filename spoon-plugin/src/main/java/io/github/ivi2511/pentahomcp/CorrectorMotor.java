package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.logging.LogLevel;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.RowAdapter;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.steps.textfileoutput.TextFileField;
import org.pentaho.di.trans.steps.textfileoutput.TextFileOutputMeta;
import org.sqlite.SQLiteConfig;

import io.github.ivi2511.pentahomcp.CorrectorSandbox.Preparacion;
import io.github.ivi2511.pentahomcp.CorrectorSandbox.SalidaRedirigida;
import io.github.ivi2511.pentahomcp.CorrectorTextos.Mensaje;
import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.corrector.modelo.Caso;
import io.github.ivi2511.pentahomcp.corrector.modelo.Comparacion;
import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.corrector.modelo.OpcionesComparar;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.TablaSalida;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCaso;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.Aviso;

/**
 * Un caso de una entrega en la JVM hija (docs/corrector-diseno.md §3 pasos 4 a 6): corre la copia reescrita del
 * sandbox ({@link CorrectorSandbox#preparar}), captura la salida y la compara con {@code esperado/<caso>.csv}.
 * <ul>
 * <li><b>Bucle propio</b> con la secuencia de {@code RunMotor.ejecutarTrans} (variables internas, parametros,
 * prepareExecution con plazo, startThreads, stopAll y killAll), porque ejecutarTrans no deja enganchar el RowListener
 * entre prepareExecution y startThreads ni vigilar el sandbox. Reusa sus helpers (padreDeLog, prepararVariables,
 * esperarFin, metricas, colaLog, errorDeLog) sin tocar RunMotor.</li>
 * <li><b>Topes</b>: {@code timeout_s} por caso ({@code timeout}); {@code max_output_mb} sobre {@code salida/} +
 * {@code tmp/} del caso + el tmp del worker, medido cada {@value #MS_VIGILANCIA} ms ({@code output_too_big}, P18: una
 * salida desbocada escribe 10 MB por segundo); {@code max_rows} en la captura ({@code too_many_rows}: el lector del
 * CSV corta, el RowListener para la corrida).</li>
 * <li><b>Captura</b> ({@link #elegirSalida}): {@code file}, solo Text file output, leido con su separador, encierro,
 * encabezado, encoding (vacio = el del pack, P17) y el formato de cada campo; {@code step}, RowListener sobre todas
 * las copias del step de salida, numeros sin formato y fechas yyyy-MM-dd; {@code query}, el SQL del pack por JDBC de
 * solo lectura sobre la copia de la base del caso ({@code db/}).</li>
 * </ul>
 * Lo que se lee del alumno (log, nombres) es dato. {@code salida/} se vacia apenas se captura.
 *
 * @dueño C4
 */
final class CorrectorMotor {

  /** Aviso: el Text file output termino sin escribir su archivo (no paso ninguna fila y «no crear al inicio»). */
  static final String AVISO_SIN_ARCHIVO = "output_not_written";
  /** Aviso: una tabla de output.tables no se pudo leer en la base de prueba (no existe). */
  static final String AVISO_SIN_TABLA = "missing_table";
  /** Cada cuanto se mide el tamaño del sandbox mientras corre. */
  static final long MS_VIGILANCIA = 250;
  /** Despues de stopAll, cuanto se espera antes de killAll. */
  static final long MS_PARADA = 10000;
  /** Tope para calcular los campos de un step al elegir la salida (getStepFields puede conectarse a la base). */
  static final long MS_CAMPOS = 20000;
  static final String TEXT_FILE_OUTPUT = "TextFileOutput";

  private CorrectorMotor() {
  }

  // ------------------------------------------------------------------ salida

  /** El step de donde se captura (§3 paso 5): el mismo en todos los casos de la entrega. */
  static final class Salida {

    /** Nombre del step en la entrega, o null si no hay de donde capturar (captura file o step). */
    String step;
    /** Id de plugin del step elegido. */
    String tipo;
    /** Sin step: por que (para {@code run.detail} de {@code no_output_step}), en ingles para el docente. */
    String motivo;
    /** El mismo motivo, para traducirlo. */
    Mensaje mensaje;
    /** Otros candidatos con las mismas columnas en comun (aviso {@code output_ambiguous}). */
    final List<String> empatados = new ArrayList<String>();

    boolean hay() {
      return step != null;
    }
  }

  /**
   * Elige el step de salida en la entrega (§3 paso 5): {@code output.step} si esta y sirve para la captura; si no, el
   * candidato que comparte mas columnas con las esperadas (empate: el primero del XML y aviso). Candidatos: con
   * {@code file}, los Text file output (Excel, JSON o XML output no: para esos, captura {@code step}); con
   * {@code step}, los steps sin hops habilitados de salida; con {@code query}, el step solo se informa (output.step o
   * la primera salida a base): la captura es el SQL del pack. {@code tm}: una copia que no se ejecuta.
   */
  static Salida elegirSalida( Pack pack, TransMeta tm, String captura ) {
    Salida s = new Salida();
    if ( tm == null ) {
      s.mensaje = new Mensaje( "det.output.unanalysed" );
      s.motivo = s.mensaje.en();
      return s;
    }
    String pedido = pack.salida == null ? null : pack.salida.step;
    StepMeta elegido = pedido == null || pedido.trim().isEmpty() ? null : tm.findStep( pedido );
    List<StepMeta> candidatos = new ArrayList<StepMeta>();
    for ( StepMeta sm : tm.getSteps() ) {
      String id = sm.getStepID() == null ? "" : sm.getStepID();
      if ( Pack.Salida.CAPTURA_ARCHIVO.equals( captura ) ) {
        if ( TEXT_FILE_OUTPUT.equalsIgnoreCase( id ) ) {
          candidatos.add( sm );
        }
      } else if ( Pack.Salida.aBase( captura ) ) {
        if ( CorrectorNota.SALIDAS_A_BASE.contains( id ) ) {
          candidatos.add( sm );
        }
      } else if ( siguientesHabilitados( tm, sm ) == 0 ) {
        candidatos.add( sm );
      }
    }
    if ( elegido != null && ( candidatos.contains( elegido ) || Pack.Salida.CAPTURA_STEP.equals( captura ) ) ) {
      poner( s, elegido );
      return s;
    }
    if ( candidatos.isEmpty() ) {
      if ( Pack.Salida.CAPTURA_ARCHIVO.equals( captura ) ) {
        s.mensaje = elegido != null ? new Mensaje( "det.output.not_tfo", elegido.getName(), elegido.getStepID() )
          : new Mensaje( "det.output.no_tfo" );
      } else if ( !Pack.Salida.aBase( captura ) ) {
        s.mensaje = new Mensaje( "det.output.no_final" );
      }
      s.motivo = s.mensaje == null ? null : s.mensaje.en();
      return s;
    }
    if ( Pack.Salida.aBase( captura ) ) {
      poner( s, candidatos.get( 0 ) );
      return s;
    }
    List<String> esperadas = new ArrayList<String>();
    for ( String n : pack.salida == null ? Collections.<String>emptyList() : pack.salida.nombresColumnas() ) {
      esperadas.add( clave( n ) );
    }
    int mejor = -1;
    List<StepMeta> empate = new ArrayList<StepMeta>();
    for ( StepMeta sm : candidatos ) {
      int n = 0;
      for ( String c : columnas( tm, sm ) ) {
        if ( esperadas.contains( clave( c ) ) ) {
          n++;
        }
      }
      if ( n > mejor ) {
        mejor = n;
        empate.clear();
      }
      if ( n == mejor ) {
        empate.add( sm );
      }
    }
    poner( s, empate.get( 0 ) );
    for ( int i = 1; i < empate.size(); i++ ) {
      s.empatados.add( empate.get( i ).getName() );
    }
    return s;
  }

  private static void poner( Salida s, StepMeta sm ) {
    s.step = sm.getName();
    s.tipo = sm.getStepID();
  }

  /** Hops habilitados que salen del step (los de error incluidos). */
  private static int siguientesHabilitados( TransMeta tm, StepMeta sm ) {
    int n = 0;
    for ( int i = 0; i < tm.nrTransHops(); i++ ) {
      org.pentaho.di.trans.TransHopMeta h = tm.getTransHop( i );
      if ( h.isEnabled() && sm.equals( h.getFromStep() ) && h.getToStep() != null ) {
        n++;
      }
    }
    return n;
  }

  /**
   * Las columnas que el step deja como salida: los campos declarados de un Text file output (o, si no declara
   * ninguno, los que le llegan) o los campos del step. Vacia si no se pueden calcular a tiempo.
   */
  static List<String> columnas( final TransMeta tm, final StepMeta sm ) {
    List<String> l = new ArrayList<String>();
    if ( sm.getStepMetaInterface() instanceof TextFileOutputMeta ) {
      TextFileField[] campos = ( (TextFileOutputMeta) sm.getStepMetaInterface() ).getOutputFields();
      if ( campos != null && campos.length > 0 ) {
        for ( TextFileField f : campos ) {
          l.add( f.getName() );
        }
        return l;
      }
    }
    final boolean entrada = sm.getStepMetaInterface() instanceof TextFileOutputMeta;
    try {
      RowMetaInterface rm = RunMotor.enSegundoPlano( () -> entrada ? tm.getPrevStepFields( sm )
        : tm.getStepFields( sm ), MS_CAMPOS, null, "Fields of " + sm.getName() );
      if ( rm != null ) {
        l.addAll( Arrays.asList( rm.getFieldNames() ) );
      }
    } catch ( Throwable ignored ) {
      // sin campos: no suma columnas en comun
    }
    return l;
  }

  private static String clave( String n ) {
    return n == null ? "" : n.trim().toLowerCase( Locale.ROOT );
  }

  // ------------------------------------------------------------------ un caso

  /** Lo de la entrega que todos sus casos comparten. */
  static final class Contexto {

    Pack pack;
    /** Carpeta del pack (esperado/). */
    File dirPack;
    /** file | step | query: la del pack o la que se pidio para esta corrida. */
    String captura;
    Salida salida;
    /** El java.io.tmpdir del worker: cuenta para max_output_mb. */
    File dirTmpWorker;
    /** No vaciar salida/ despues de capturar (keep_sandbox). */
    boolean conservarSalida;
    /** Las bases de prueba (formato 2): se recrean antes de cada caso; null si el pack no tiene. */
    CorrectorBases.Sesion bases;
  }

  /** Como termino la corrida de un caso, para decidir si el worker se recicla (§7). */
  static final class Fin {

    /** Corrio algo (paso de prepareExecution). */
    boolean corrio;
    /** Hubo que pararla (timeout, max_output_mb, max_rows): pueden quedar hilos o archivos abiertos. */
    boolean cortada;
    /** No termino ni con killAll. */
    boolean colgada;
    /** La salida capturada (si termino bien y se capturo), con o sin comparacion: CorrectorCrearPack la escribe. */
    CorrectorTabla tabla;
    /** Captura tables: cada tabla leida, por nombre (CorrectorCrearPack escribe su esperado). */
    final Map<String, CorrectorBases.Captura> tablas = new LinkedHashMap<String, CorrectorBases.Captura>();
    /** No se pudo preparar o leer una base de prueba: es del entorno del docente, no de la entrega. */
    Mensaje fallaBase;
  }

  /**
   * Corre un caso ya preparado y deja en {@code rc} la corrida ({@code run}), la comparacion y los avisos del caso.
   * El sandbox no debe estar bloqueado ({@link Preparacion#bloqueada()}). No lanza: un problema inesperado queda como
   * {@code init_failed} con su detalle.
   */
  static Fin correr( Contexto cx, Caso caso, Preparacion p, ResultadoCaso rc ) {
    Fin fin = new Fin();
    ResultadoCaso.Corrida run = rc.corrida;
    long inicio = System.currentTimeMillis();
    try {
      if ( cx.bases != null ) {
        try {
          cx.bases.recrear( caso ); // antes de cada caso: las bases de prueba vacias y con los scripts
        } catch ( CorrectorBases.Falla f ) {
          run.estado = ResultadoCaso.FALLO_INIT;
          new Mensaje( "det.run.test_db", f.mensaje.anidado() ).en( run );
          fin.fallaBase = f.mensaje;
          return fin;
        }
        inicio = System.currentTimeMillis(); // recrear no cuenta en el plazo de la entrega
      }
      correrInterno( cx, caso, p, rc, fin, inicio );
    } catch ( Throwable t ) {
      run.estado = ResultadoCaso.FALLO_INIT;
      new Mensaje( "det.run.grader", RunMotor.primeraLinea( t ) ).en( run );
    } finally {
      if ( run.segundos == null ) {
        run.segundos = segundos( inicio );
      }
      if ( !cx.conservarSalida ) {
        vaciar( p.salida );
      }
    }
    return fin;
  }

  private static void correrInterno( Contexto cx, Caso caso, Preparacion p, ResultadoCaso rc, Fin fin, long inicio )
    throws Exception {
    ResultadoCaso.Corrida run = rc.corrida;
    Pack pack = cx.pack;
    TransMeta tm = new TransMeta( p.ktr.getAbsolutePath() );
    for ( Map.Entry<String, String> e : p.parametros.entrySet() ) {
      tm.setParameterValue( e.getKey(), e.getValue() );
    }
    // la contraseña de cada base de prueba, solo en memoria: la copia del sandbox la tiene vacia
    for ( DatabaseMeta dm : tm.getDatabases() ) {
      CorrectorBases.Destino d = dm.getName() == null ? null : p.conexionesPrueba.get( dm.getName() );
      if ( d != null ) {
        CorrectorBases.ponerClave( dm, d.clave );
      }
    }
    RunMotor.sinTablasDeLog( tm ); // una correccion no escribe en tablas de log
    StepMeta salida = cx.salida == null || cx.salida.step == null ? null : tm.findStep( cx.salida.step );
    boolean query = Pack.Salida.CAPTURA_QUERY.equals( cx.captura );
    boolean tablas = Pack.Salida.CAPTURA_TABLAS.equals( cx.captura );
    boolean porStep = Pack.Salida.CAPTURA_STEP.equals( cx.captura );
    if ( salida != null ) {
      run.stepSalida = salida.getName();
      run.tipoStepSalida = salida.getStepID();
    }
    if ( salida == null && !query && !tablas ) {
      run.estado = ResultadoCaso.SIN_STEP_DE_SALIDA;
      if ( cx.salida != null && cx.salida.mensaje != null ) {
        cx.salida.mensaje.en( run );
      } else {
        new Mensaje( "det.run.no_output" ).en( run );
      }
      return;
    }
    if ( query && p.baseDatos == null ) {
      run.estado = ResultadoCaso.SIN_STEP_DE_SALIDA;
      new Mensaje( "det.run.no_template" ).en( run );
      return;
    }
    if ( tablas && ( cx.bases == null || pack.salida.tablas.isEmpty() ) ) {
      run.estado = ResultadoCaso.SIN_STEP_DE_SALIDA;
      new Mensaje( "det.run.no_tables" ).en( run );
      return;
    }
    long timeoutMs = Math.max( 1, pack.politica.timeoutSeg ) * 1000L;
    long topeBytes = Math.max( 1, pack.politica.maxSalidaMb ) * 1024L * 1024L;
    long maxFilas = pack.politica.maxFilas <= 0 ? Long.MAX_VALUE : pack.politica.maxFilas;
    int veces = caso == null ? 1 : caso.vecesQueCorre();

    // el tmp del worker ya tiene lo suyo (la DLL de sqlite-jdbc, entregas anteriores): cuenta solo lo que crece
    long baseTmp = tamano( cx.dirTmpWorker );
    Trans t = null;
    String canal = null;
    Escucha escucha = null;
    try {
      for ( int vez = 1; vez <= veces; vez++ ) {
        boolean ultima = vez == veces;
        if ( canal != null ) {
          RunMotor.liberarLog( canal );
        }
        long limite = ( vez == 1 ? inicio : System.currentTimeMillis() ) + timeoutMs;
        t = new Trans( tm, RunMotor.padreDeLog() );
        t.setLogLevel( LogLevel.BASIC );
        RunMotor.prepararVariables( t, tm );
        RunMotor.sinTablasDeLog( tm, t );
        canal = t.getLogChannelId();
        try {
          RunMotor.prepararConPlazo( t, Math.max( 1, limite - System.currentTimeMillis() ), "Preparing the "
            + "transformation" );
        } catch ( ToolError e ) {
          run.estado = ResultadoCaso.TIEMPO_AGOTADO;
          new Mensaje( "det.run.prepare_timeout", timeoutMs / 1000 ).en( run );
          fin.cortada = true;
          return;
        } catch ( Exception e ) {
          run.estado = ResultadoCaso.FALLO_INIT;
          run.errores = (long) Math.max( 1, t.getErrors() );
          run.detalle = enLaVez( RunMotor.errorDeLog( canal, e ), vez, veces );
          run.metricas = RunMotor.metricas( t );
          run.log = RunMotor.colaLog( canal, true );
          return;
        }
        fin.corrio = true;
        escucha = null;
        if ( porStep && ultima ) {
          escucha = new Escucha( maxFilas );
          for ( StepInterface si : t.findStepInterfaces( salida.getName() ) ) {
            si.addRowListener( escucha );
          }
        }
        try {
          t.startThreads();
        } catch ( Exception e ) {
          run.estado = ResultadoCaso.FALLO_ARRANQUE;
          run.errores = (long) Math.max( 1, t.getErrors() );
          run.detalle = enLaVez( RunMotor.errorDeLog( canal, e ), vez, veces );
          parar( t, fin );
          run.metricas = RunMotor.metricas( t );
          run.log = RunMotor.colaLog( canal, true );
          return;
        }
        String corte = vigilar( t, escucha, limite, topeBytes, p, cx.dirTmpWorker, baseTmp );
        if ( corte != null ) {
          parar( t, fin );
          run.estado = corte;
          ( ResultadoCaso.TIEMPO_AGOTADO.equals( corte ) ? new Mensaje( "det.run.timeout", timeoutMs / 1000 )
            : ResultadoCaso.SALIDA_DEMASIADO_GRANDE.equals( corte ) ? new Mensaje( "det.run.too_big",
            pack.politica.maxSalidaMb ) : new Mensaje( "det.run.rows", maxFilas ) ).en( run );
        }
        run.segundos = segundos( inicio );
        int errores = t.getErrors();
        run.errores = (long) errores;
        run.metricas = RunMotor.metricas( t );
        if ( run.estado == null ) {
          run.estado = errores > 0 ? ResultadoCaso.TERMINO_CON_ERRORES : ResultadoCaso.TERMINO;
          if ( errores > 0 ) {
            run.detalle = enLaVez( RunMotor.errorDeLog( canal, null ), vez, veces );
          }
        }
        if ( !ultima && !ResultadoCaso.TERMINO.equals( run.estado ) ) {
          run.log = RunMotor.colaLog( canal, errores > 0 );
          return; // la primera corrida ya fallo: no se vuelve a correr ni se captura
        }
        if ( !ultima ) {
          run.estado = null;
          run.errores = null;
          vaciar( p.salida );
          continue;
        }
        run.log = RunMotor.colaLog( canal, errores > 0 );
      }
      if ( !CorrectorNota.corrio( rc ) ) {
        return; // timeout, tope: la salida esta a medias y no se compara
      }
      if ( tablas ) {
        capturarTablas( cx, caso, rc, fin, maxFilas );
        return;
      }
      CorrectorTabla real;
      if ( query ) {
        real = consultar( pack, p.baseDatos, maxFilas );
        run.capturadoDe = "query " + CorrectorSandbox.DB + "/" + p.baseDatos.getName();
      } else if ( porStep ) {
        real = escucha.tabla( salida, tm );
        run.capturadoDe = "step " + salida.getName();
      } else {
        real = leerArchivo( cx, p, t, tm, salida, rc );
      }
      run.filasCapturadas = (long) real.cantidadFilas();
      if ( real.cortada ) {
        run.estado = ResultadoCaso.DEMASIADAS_FILAS;
        new Mensaje( "det.run.rows_read", maxFilas ).en( run );
        return;
      }
      fin.tabla = real;
      if ( caso.esperado == null ) {
        return; // creando el pack (CorrectorCrearPack): solo se captura, la salida esperada es esta
      }
      CorrectorTabla esperada = CorrectorTabla.esperada( new File( cx.dirPack, caso.esperado ), pack );
      rc.comparacion = CorrectorComparar.comparar( esperada, real, pack, rc.avisos );
    } finally {
      if ( canal != null ) {
        RunMotor.liberarLog( canal );
      }
    }
  }

  /** "Ejecucion 2 de 2: " delante del error si la entrega corre varias veces (el error es de Kettle, en ingles). */
  private static String enLaVez( String detalle, int vez, int veces ) {
    return veces <= 1 ? detalle : "run " + vez + " of " + veces + ": " + detalle;
  }

  /**
   * Captura tables: un SELECT de cada tabla de output.tables en su base de prueba y, si el caso tiene esperado, la
   * comparacion de cada una con {@code <expected>/<tabla>.csv}; la del caso es la suma
   * ({@link CorrectorComparar#juntar}).
   */
  private static void capturarTablas( Contexto cx, Caso caso, ResultadoCaso rc, Fin fin, long maxFilas )
    throws Exception {
    ResultadoCaso.Corrida run = rc.corrida;
    Pack pack = cx.pack;
    List<String> nombres = new ArrayList<String>();
    List<ResultadoCaso.ComparacionTabla> comps = new ArrayList<ResultadoCaso.ComparacionTabla>();
    long total = 0;
    for ( TablaSalida ts : pack.salida.tablas ) {
      CorrectorBases.Captura c;
      try {
        c = cx.bases.capturar( ts, maxFilas );
      } catch ( CorrectorBases.Falla f ) {
        run.estado = ResultadoCaso.FALLO_INIT;
        new Mensaje( "det.run.test_db", f.mensaje.anidado() ).en( run );
        fin.fallaBase = f.mensaje;
        return;
      }
      nombres.add( ts.nombre );
      total += c.tabla.cantidadFilas();
      if ( c.cortada ) {
        run.estado = ResultadoCaso.DEMASIADAS_FILAS;
        new Mensaje( "det.run.rows_read", maxFilas ).en( run );
        run.filasCapturadas = total;
        return;
      }
      if ( c.falta != null ) {
        Aviso av = new Mensaje( "det.table.missing", ts.nombre, c.falta ).aviso( AVISO_SIN_TABLA );
        rc.avisos.add( av );
      }
      fin.tablas.put( ts.nombre, c );
      if ( caso.esperado == null ) {
        continue;
      }
      File archivo = new File( new File( cx.dirPack, caso.esperado ), ts.archivo() );
      CorrectorTabla esperada = CorrectorTabla.esperada( archivo, pack.salida.csvEsperado, nombresDe( ts ) );
      OpcionesComparar oc = CorrectorJson.copia( pack.comparar, OpcionesComparar.class );
      oc.columnasClave = new ArrayList<String>( ts.columnasClave );
      List<Aviso> avisos = new ArrayList<Aviso>();
      Comparacion cmp = CorrectorComparar.comparar( esperada, c.tabla, ts.columnas, oc, pack.politica.localeJava(),
        avisos );
      for ( Aviso av : avisos ) {
        av.detalle = ts.nombre + ": " + ( av.detalle == null ? "" : av.detalle );
        rc.avisos.add( av );
      }
      comps.add( new ResultadoCaso.ComparacionTabla( ts.nombre, ts.conexion, cmp ) );
    }
    run.capturadoDe = "tables " + String.join( ", ", nombres );
    run.filasCapturadas = total;
    if ( caso.esperado == null ) {
      return;
    }
    rc.tablas = comps;
    rc.comparacion = CorrectorComparar.juntar( comps );
  }

  private static List<String> nombresDe( TablaSalida ts ) {
    List<String> l = new ArrayList<String>();
    for ( Pack.Columna c : ts.columnas ) {
      l.add( c.nombre );
    }
    return l;
  }

  /**
   * Espera a que termine vigilando el plazo, las filas capturadas y el tamaño del sandbox (salida/ y tmp/ del caso y lo
   * que crecio el tmp del worker desde baseTmp). Devuelve el estado del corte ({@code timeout}, {@code too_many_rows},
   * {@code output_too_big}) o null si termino sola.
   */
  private static String vigilar( Trans t, Escucha escucha, long limite, long topeBytes, Preparacion p,
                                 File tmpWorker, long baseTmp ) {
    long proximaMedida = 0;
    while ( !t.isFinished() ) {
      long ahora = System.currentTimeMillis();
      if ( ahora > limite ) {
        return ResultadoCaso.TIEMPO_AGOTADO;
      }
      if ( escucha != null && escucha.excedida ) {
        return ResultadoCaso.DEMASIADAS_FILAS;
      }
      if ( ahora >= proximaMedida ) {
        proximaMedida = ahora + MS_VIGILANCIA;
        if ( tamano( p.salida ) + tamano( p.tmp ) + Math.max( 0, tamano( tmpWorker ) - baseTmp ) > topeBytes ) {
          return ResultadoCaso.SALIDA_DEMASIADO_GRANDE;
        }
      }
      RunMotor.dormir( 20 );
    }
    return escucha != null && escucha.excedida ? ResultadoCaso.DEMASIADAS_FILAS : null;
  }

  /** stopAll y, si en {@value #MS_PARADA} ms no termina, killAll. */
  private static void parar( Trans t, Fin fin ) {
    fin.cortada = true;
    try {
      t.stopAll();
    } catch ( Throwable ignored ) {
      // sigue con killAll
    }
    RunMotor.esperarFin( t, MS_PARADA );
    if ( !t.isFinished() ) {
      RunMotor.esperarFin( t, 2000 );
      fin.colgada = !t.isFinished();
    }
  }

  // ------------------------------------------------------------------ captura

  /** RowListener sobre todas las copias del step de salida (como RunMotor.preview), con tope de filas. */
  static final class Escucha extends RowAdapter {

    private final long max;
    final List<Object[]> filas = new ArrayList<Object[]>();
    RowMetaInterface meta;
    volatile boolean excedida;

    Escucha( long max ) {
      this.max = max;
    }

    @Override
    public void rowWrittenEvent( RowMetaInterface rm, Object[] row ) throws KettleStepException {
      synchronized ( filas ) { // varias copias del step escriben a la vez
        if ( filas.size() >= max ) {
          excedida = true;
          return;
        }
        if ( meta == null ) {
          meta = rm.clone();
        }
        try {
          filas.add( rm.cloneRow( row ) ); // los steps reutilizan el array
        } catch ( Exception e ) {
          throw new KettleStepException( e );
        }
      }
    }

    /**
     * Las filas como tabla de texto sin formato (numeros planos, fechas yyyy-MM-dd): de un Text file output, solo sus
     * campos declarados (§3 paso 5); de cualquier otro step, todos sus campos.
     */
    CorrectorTabla tabla( StepMeta sm, TransMeta tm ) {
      synchronized ( filas ) {
        List<String> nombres = new ArrayList<String>();
        if ( sm.getStepMetaInterface() instanceof TextFileOutputMeta ) {
          TextFileField[] campos = ( (TextFileOutputMeta) sm.getStepMetaInterface() ).getOutputFields();
          for ( TextFileField f : campos == null ? new TextFileField[ 0 ] : campos ) {
            nombres.add( f.getName() );
          }
        }
        if ( nombres.isEmpty() ) {
          if ( meta != null ) {
            nombres.addAll( Arrays.asList( meta.getFieldNames() ) );
          } else {
            nombres.addAll( columnas( tm, sm ) );
          }
        }
        List<List<Object>> out = new ArrayList<List<Object>>();
        for ( Object[] f : filas ) {
          List<Object> fila = new ArrayList<Object>();
          for ( String n : nombres ) {
            int i = meta == null ? -1 : meta.indexOfValue( n );
            fila.add( i < 0 || i >= f.length ? null : nativo( meta.getValueMeta( i ), f[ i ] ) );
          }
          out.add( fila );
        }
        CorrectorTabla t = CorrectorTabla.de( nombres, out );
        t.cortada = excedida;
        return t;
      }
    }
  }

  /** El valor en su tipo de Java (las filas con conversion perezosa traen bytes del archivo). */
  private static Object nativo( ValueMetaInterface vm, Object v ) {
    if ( v == null || vm == null ) {
      return v;
    }
    try {
      return vm.getNativeDataType( v );
    } catch ( Exception e ) {
      return String.valueOf( v );
    }
  }

  /**
   * La salida a archivo del alumno leida con lo que declaro su Text file output (§3 paso 5): separador, encierro,
   * encabezado, encoding (vacio = el file.encoding del pack, P17) y el formato de cada campo (sin campos declarados,
   * Kettle escribe los que le llegan con su propia mascara: se leen con esa).
   */
  private static CorrectorTabla leerArchivo( Contexto cx, Preparacion p, Trans t, TransMeta tm, StepMeta sm,
                                             ResultadoCaso rc ) throws IOException {
    TextFileOutputMeta m = (TextFileOutputMeta) sm.getStepMetaInterface();
    List<String> nombres = new ArrayList<String>();
    List<CorrectorTabla.Formato> formatos = new ArrayList<CorrectorTabla.Formato>();
    TextFileField[] campos = m.getOutputFields();
    if ( campos != null && campos.length > 0 ) {
      for ( TextFileField f : campos ) {
        nombres.add( f.getName() );
        formatos.add( new CorrectorTabla.Formato( f.getTypeDesc(), f.getFormat(), f.getDecimalSymbol(),
          f.getGroupingSymbol(), f.getCurrencySymbol(), f.getNullString() ) );
      }
    } else {
      RowMetaInterface rm = null;
      try {
        rm = tm.getPrevStepFields( sm );
      } catch ( Exception ignored ) {
        rm = null;
      }
      for ( int i = 0; rm != null && i < rm.size(); i++ ) {
        ValueMetaInterface vm = rm.getValueMeta( i );
        nombres.add( vm.getName() );
        formatos.add( new CorrectorTabla.Formato( vm.getTypeDesc(), vm.getConversionMask(), vm.getDecimalSymbol(),
          vm.getGroupingSymbol(), vm.getCurrencySymbol(), null ) );
      }
    }
    CorrectorTabla.Lectura l = new CorrectorTabla.Lectura().separador( t.environmentSubstitute( m.getSeparator() ) )
      .encierro( t.environmentSubstitute( m.getEnclosure() ) ).encabezado( m.isHeaderEnabled() )
      .encoding( t.environmentSubstitute( m.getEncoding() ), cx.pack.politica.encoding ).nombres( nombres )
      .formatos( formatos ).maxFilas( cx.pack.politica.maxFilas );
    boolean partida = false;
    try {
      partida = m.getSplitEvery( t ) > 0;
    } catch ( Throwable ignored ) {
      partida = false;
    }
    File f = archivoDeSalida( p, sm, m, t );
    if ( partida ) {
      agregarAviso( rc.avisos, new Aviso( Aviso.SALIDA_PARTIDA, "the output is split in several files: only the "
        + "first one was read" ), sm.getName() );
    }
    if ( f == null ) {
      agregarAviso( rc.avisos, new Aviso( AVISO_SIN_ARCHIVO, "the Text file output did not write its file" ),
        sm.getName() );
      rc.corrida.capturadoDe = "file (not written)";
      return CorrectorTabla.vacia( nombres );
    }
    rc.corrida.capturadoDe = "file " + relativa( p.dirCaso, f );
    return CorrectorTabla.leer( f, l );
  }

  /**
   * El archivo que escribio el Text file output: el nombre de buildFilename (nro. de step 0, sin particion, primer
   * archivo de un split) o, si no esta (fecha u hora en el nombre), el primero de salida/ que empieza con el nombre
   * redirigido y no es la salida de otro step. null si no escribio nada.
   */
  static File archivoDeSalida( Preparacion p, StepMeta sm, TextFileOutputMeta m, Trans t ) {
    try {
      String n = m.buildFilename( t, 0, null, 0, false );
      if ( n != null && !n.trim().isEmpty() ) {
        File f = CorrectorSandbox.aArchivo( t.environmentSubstitute( n ) );
        if ( f.isFile() ) {
          return f;
        }
      }
    } catch ( Throwable ignored ) {
      // se busca por prefijo
    }
    SalidaRedirigida propia = null;
    Set<String> ajenas = new LinkedHashSet<String>();
    for ( SalidaRedirigida sr : p.salidas ) {
      String base = sr.relativa.substring( sr.relativa.lastIndexOf( '/' ) + 1 );
      String completo = base + ( sr.extension == null || sr.extension.isEmpty() ? "" : "." + sr.extension );
      if ( sr.step != null && sr.step.equals( sm.getName() ) ) {
        propia = sr;
      } else {
        ajenas.add( completo.toLowerCase( Locale.ROOT ) );
      }
    }
    if ( propia == null ) {
      return null;
    }
    String base = propia.relativa.substring( propia.relativa.lastIndexOf( '/' ) + 1 ).toLowerCase( Locale.ROOT );
    String ext = propia.extension == null || propia.extension.isEmpty() ? "" : "."
      + propia.extension.toLowerCase( Locale.ROOT );
    File[] hijos = p.salida.listFiles();
    if ( hijos == null ) {
      return null;
    }
    Arrays.sort( hijos );
    for ( File h : hijos ) {
      String n = h.getName().toLowerCase( Locale.ROOT );
      if ( !h.isFile() || ajenas.contains( n ) || !n.endsWith( ext ) ) {
        continue;
      }
      if ( n.equals( base + ext ) || n.startsWith( base + "_" ) ) {
        return h;
      }
    }
    return null;
  }

  /**
   * La consulta del pack sobre la copia de la base del caso, por JDBC de solo lectura (sqlite-jdbc de lib/): el SQL
   * es del docente. Hasta maxFilas filas; si hay mas, la tabla queda {@code cortada}.
   */
  static CorrectorTabla consultar( Pack pack, File base, long maxFilas ) throws Exception {
    SQLiteConfig cfg = new SQLiteConfig();
    cfg.setReadOnly( true );
    List<String> nombres = new ArrayList<String>();
    List<List<Object>> filas = new ArrayList<List<Object>>();
    boolean cortada = false;
    try ( Connection c = cfg.createConnection( "jdbc:sqlite:" + base.getAbsolutePath() );
          Statement st = c.createStatement() ) {
      if ( maxFilas < Integer.MAX_VALUE ) {
        st.setMaxRows( (int) maxFilas + 1 );
      }
      try ( ResultSet rs = st.executeQuery( pack.salida.sql ) ) {
        ResultSetMetaData md = rs.getMetaData();
        for ( int i = 1; i <= md.getColumnCount(); i++ ) {
          nombres.add( md.getColumnLabel( i ) );
        }
        while ( rs.next() ) {
          if ( filas.size() >= maxFilas ) {
            cortada = true;
            break;
          }
          List<Object> f = new ArrayList<Object>();
          for ( int i = 1; i <= nombres.size(); i++ ) {
            f.add( rs.getObject( i ) );
          }
          filas.add( f );
        }
      }
    }
    CorrectorTabla t = CorrectorTabla.de( nombres, filas );
    t.cortada = cortada;
    return t;
  }

  // ------------------------------------------------------------------ ayudas

  /** Bytes de una carpeta (sin seguir enlaces); 0 si no existe. */
  static long tamano( File f ) {
    if ( f == null ) {
      return 0;
    }
    if ( java.nio.file.Files.isSymbolicLink( f.toPath() ) ) {
      return 0;
    }
    if ( f.isFile() ) {
      return f.length();
    }
    long n = 0;
    File[] hijos = f.listFiles();
    if ( hijos != null ) {
      for ( File h : hijos ) {
        n += tamano( h );
      }
    }
    return n;
  }

  /** Borra lo que hay adentro de salida/ (la carpeta queda): §3 paso 6. */
  static void vaciar( File dir ) {
    File[] hijos = dir == null ? null : dir.listFiles();
    if ( hijos == null ) {
      return;
    }
    for ( File h : hijos ) {
      try {
        CorrectorSandbox.borrar( h );
      } catch ( IOException ignored ) {
        // trabado (Windows): se borra con el sandbox
      }
    }
  }

  private static void agregarAviso( List<Aviso> l, Aviso a, String step ) {
    a.step = step;
    for ( Aviso b : l ) {
      if ( b != null && a.codigo.equals( b.codigo ) && eq( a.step, b.step ) ) {
        return;
      }
    }
    l.add( a );
  }

  private static boolean eq( String a, String b ) {
    return a == null ? b == null : a.equals( b );
  }

  /** "salida/x.csv" relativo a la carpeta del caso, con /. */
  static String relativa( File dir, File f ) {
    String d = dir.getAbsolutePath().replace( '\\', '/' );
    String a = f.getAbsolutePath().replace( '\\', '/' );
    return a.regionMatches( true, 0, d + "/", 0, d.length() + 1 ) ? a.substring( d.length() + 1 ) : f.getName();
  }

  private static double segundos( long inicio ) {
    return Math.round( ( System.currentTimeMillis() - inicio ) / 10.0 ) / 100.0;
  }
}
