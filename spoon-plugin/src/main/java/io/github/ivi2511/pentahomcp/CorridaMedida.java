package io.github.ivi2511.pentahomcp;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TimeZone;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.Result;
import org.pentaho.di.core.gui.JobTracker;
import org.pentaho.di.core.logging.LogLevel;
import org.pentaho.di.core.logging.LoggingRegistry;
import org.pentaho.di.core.logging.Metrics;
import org.pentaho.di.core.logging.MetricsRegistry;
import org.pentaho.di.core.logging.MetricsInterface;
import org.pentaho.di.core.metrics.MetricsSnapshotInterface;
import org.pentaho.di.core.metrics.MetricsSnapshotType;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.job.DelegationListener;
import org.pentaho.di.job.Job;
import org.pentaho.di.job.JobEntryListener;
import org.pentaho.di.job.JobEntryResult;
import org.pentaho.di.job.JobExecutionConfiguration;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entries.trans.JobEntryTrans;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.job.entry.JobEntryInterface;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransExecutionConfiguration;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.performance.StepPerformanceSnapShot;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaDataCombi;

import io.github.ivi2511.pentahomcp.informes.Pedido;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.modelo.ModeloJson;

/**
 * Lo que se sabe de UNA corrida (diseno §2.4.2): la ultima de Spoon (TransGraph.getTrans() /
 * JobGraph.getJob(), solo lectura) o una copia medida ("Medir...", run_copy) que esta clase ejecuta con
 * metricas y snapshots. Lo usa SecMetricas para armar la seccion; tambien da a la UI la lista de lo
 * que escribiria una corrida, para pedir confirmacion antes de medir o perfilar.
 * <p>
 * Trampas que resuelve (todas observadas en 9.4): {@code Trans/Job.getStartDate()} es 1900-01-01 (el
 * inicio es {@code getCurrentDate()} y el fin {@code getLogDate()}); {@code Result.getElapsedTimeMillis()}
 * da 0; {@code getStatusDescription()} y {@code getSpeed()} vienen traducidos y con el formato del
 * locale de la JVM (se usa el enum y la velocidad se calcula aparte); el arbol de
 * {@code getJobTracker()} trae cada entrada dos veces (al empezar con resultado null y al terminar).
 *
 * @dueño WP4
 */
public final class CorridaMedida {

  /** Fuente de la corrida: la ultima de Spoon o una copia medida. */
  static final String ULTIMA = Pedido.METRICAS_ULTIMA;
  static final String COPIA = Pedido.METRICAS_COPIA;

  /** Estados (en el JSON). */
  static final String TERMINADA = "finished";
  static final String CON_ERRORES = "finished_with_errors";
  static final String DETENIDA = "stopped";
  static final String EN_CURSO = "running";
  static final String SIN_EMPEZAR = "not_started";
  static final String FALLO_INICIO = "init_failed";

  /** Tope de filas del Gantt de un job (entradas mas steps de sub-transformaciones). */
  static final int MAX_FILAS_GANTT = 40;
  /** Cada cuanto se toma un snapshot de rendimiento en "Medir...". */
  static final long INTERVALO_SNAPSHOT_MS = 250;

  /** Entradas de job que escriben fuera (ademas de las que ejecutan transformaciones o jobs). */
  static final Set<String> ENTRADAS_QUE_ESCRIBEN = new HashSet<String>( java.util.Arrays.asList(
    "TRANS", "JOB", "SQL", "WRITE_TO_FILE", "CREATE_FILE", "CREATE_FOLDER", "DELETE_FILE", "DELETE_FILES",
    "DELETE_FOLDERS", "COPY_FILES", "MOVE_FILES", "COPY_MOVE_RESULT_FILENAMES", "ADD_RESULT_FILENAMES", "ZIP_FILE",
    "UNZIP", "MAIL", "FTP_PUT", "SFTPPUT", "FTP_DELETE", "TRUNCATE_TABLES", "SHELL", "HTTP", "EXPORT_REPOSITORY",
    "PGP_ENCRYPT_FILES", "PGP_DECRYPT_FILES", "MYSQL_BULK_LOAD", "MSSQL_BULK_LOAD", "SET_VARIABLES", "DOS_UNIX_CONVERTER",
    "XSLT", "DTD_VALIDATOR", "WRITE_TO_LOG_FILE", "EVAL_FILES_METRICS", "TALEND_JOB_EXEC" ) );

  /** Un step de la corrida, sumando sus copias. */
  static final class PasoMedido {
    String step;
    String tipo;
    int copias;
    long leidas;
    long escritas;
    long entrada;
    long salida;
    long actualizadas;
    long rechazadas;
    long errores;
    /**
     * Tiempo del step: la ejecucion medida por MetricsUtil si hay metricas reales (la misma barra que el
     * Gantt); si no, el tiempo activo (BaseStep.getRuntime) de la copia mas lenta.
     */
    long ms;
    /** BaseStep.getRuntime, el mayor de sus copias (incluye el arranque del hilo y el dispose). */
    long runtime;
    /** Nombre del enum StepExecutionStatus (STATUS_FINISHED...), nunca la descripcion traducida. */
    String estado;
    /** Sin steps anteriores: sus filas escritas son las que se leyeron. */
    boolean fuente;
    /** Escribe fuera de la transformacion (RunMotor.escribe). */
    boolean escribe;
    /** Instantes absolutos (ms epoch) de MetricsUtil, o -1. */
    long initIni = -1;
    long initFin = -1;
    long execIni = -1;
    long execFin = -1;

    /** Filas por segundo, calculadas (getSpeed() viene localizado). */
    double filasPorSegundo() {
      long n = Math.max( Math.max( leidas, escritas ), Math.max( entrada, salida ) );
      return ms <= 0 ? 0 : n * 1000.0 / ms;
    }
  }

  /** Una ejecucion de una entrada de job (el tracker la trae al empezar y al terminar). */
  static final class EntradaMedida {
    String nombre;
    int nr;
    /** 0 = entrada del job medido; 1 = de un sub-job; ... */
    int nivel;
    /** Nombre del job que la contiene. */
    String job;
    String archivo;
    /** Que hop se siguio para llegar (texto de Kettle, en el idioma de Spoon: solo para los datos). */
    String razon;
    /** El mismo motivo como codigo: start | entry_start | unconditional | success | failure, o null. */
    String razonCodigo;
    long ini = -1;
    long fin = -1;
    /** null = no termino. */
    Boolean ok;
    long errores;
    /** Si es una transformacion medida (run_copy): sus steps. */
    TransHija hija;

    long ms() {
      return ini < 0 || fin < 0 ? -1 : Math.max( 0, fin - ini );
    }
  }

  /** Una sub-transformacion que corrio dentro del job medido (solo run_copy). */
  static final class TransHija {
    String job;
    String entrada;
    String nombre;
    long ini = -1;
    long fin = -1;
    long errores;
    final List<PasoMedido> pasos = new ArrayList<PasoMedido>();
    boolean usada;
  }

  /** Serie de snapshots de un step (filas escritas por intervalo). */
  static final class Snapshots {
    /** Instantes absolutos de cada punto (los del step con mas puntos). */
    final List<Long> instantes = new ArrayList<Long>();
    /** step -> filas escritas en cada intervalo (sumando copias), alineadas por numero de snapshot. */
    final Map<String, List<Long>> porStep = new LinkedHashMap<String, List<Long>>();
  }

  // ---------------------------------------------------------------- datos de la corrida

  boolean esJob;
  String nombre;
  /** last_run | run_copy. */
  String fuente;
  /** finished | finished_with_errors | stopped | running | not_started | init_failed. */
  String estado;
  /** La corrida de Spoon seguia en curso al capturarla. */
  boolean enCurso;
  /** Medicion cortada por el tope de tiempo (stopAll). */
  boolean cortada;
  /** Medicion cancelada por el usuario. */
  boolean cancelada;
  /** Hay duraciones reales de MetricsUtil (si no, el Gantt es aproximado). */
  boolean metricasReales;
  /** Primera linea del error si prepareExecution fallo. */
  String falloInicio;
  /** Cola del log si hubo errores (solo run_copy: el log de Spoon es suyo). */
  String log;
  /** Instantes absolutos (ms epoch) del inicio y del fin; fin = ahora si sigue en curso. */
  long inicio = -1;
  long fin = -1;
  long errores;
  /** Jobs: resultado final (null si no termino). */
  Boolean resultado;
  final List<PasoMedido> pasos = new ArrayList<PasoMedido>();
  final List<EntradaMedida> entradas = new ArrayList<EntradaMedida>();
  final List<TransHija> hijas = new ArrayList<TransHija>();
  Snapshots snapshots;

  private CorridaMedida() {
  }

  long ms() {
    return inicio < 0 || fin < 0 ? 0 : Math.max( 0, fin - inicio );
  }

  /** Filas leidas: las que escribieron los steps fuente (sin steps anteriores). En jobs, de sus sub-transformaciones. */
  long filasLeidas() {
    long n = 0;
    for ( PasoMedido p : todosLosPasos() ) {
      if ( p.fuente ) {
        n += p.escritas;
      }
    }
    if ( n == 0 ) {
      for ( PasoMedido p : todosLosPasos() ) {
        n += p.entrada;
      }
    }
    return n;
  }

  /** Filas escritas: las que pasaron por steps que escriben fuera. En jobs, de sus sub-transformaciones. */
  long filasEscritas() {
    long n = 0;
    for ( PasoMedido p : todosLosPasos() ) {
      if ( p.escribe ) {
        n += p.escritas;
      }
    }
    if ( n == 0 ) {
      for ( PasoMedido p : todosLosPasos() ) {
        n += p.salida;
      }
    }
    return n;
  }

  /** Hay datos de filas (en un job de Spoon no: las filas de las sub-transformaciones no se ven). */
  boolean hayFilas() {
    return !todosLosPasos().isEmpty();
  }

  List<PasoMedido> todosLosPasos() {
    if ( !esJob ) {
      return pasos;
    }
    List<PasoMedido> l = new ArrayList<PasoMedido>();
    for ( TransHija h : hijas ) {
      l.addAll( h.pasos );
    }
    return l;
  }

  PasoMedido masLento() {
    PasoMedido m = null;
    for ( PasoMedido p : pasos ) {
      if ( m == null || p.ms > m.ms ) {
        m = p;
      }
    }
    return m;
  }

  /** La entrada de job que mas tardo (sin START, que no hace nada). */
  EntradaMedida entradaMasLenta() {
    EntradaMedida m = null;
    for ( EntradaMedida e : entradas ) {
      if ( e.ms() >= 0 && ( m == null || e.ms() > m.ms() ) ) {
        m = e;
      }
    }
    return m;
  }

  // ---------------------------------------------------------------- lo que escribiria una corrida (UI)

  /**
   * Steps (trans) o entradas (job) que escriben fuera si se ejecuta el documento entero: lo que la UI
   * lista al pedir confirmacion para "Medir...". Barato: no consulta campos ni ejecuta nada.
   */
  public static List<String> escritores( AbstractMeta m ) {
    List<String> l = new ArrayList<String>();
    if ( m instanceof TransMeta ) {
      for ( StepMeta s : ( (TransMeta) m ).getSteps() ) {
        if ( RunMotor.escribe( s ) ) {
          l.add( s.getName() );
        }
      }
    } else if ( m instanceof JobMeta ) {
      for ( JobEntryCopy c : ( (JobMeta) m ).getJobCopies() ) {
        String tipo = c.getEntry() == null ? null : c.getEntry().getPluginId();
        if ( tipo != null && ENTRADAS_QUE_ESCRIBEN.contains( tipo.toUpperCase( Locale.ROOT ) ) && !l.contains( c.getName() ) ) {
          l.add( c.getName() );
        }
      }
    }
    return l;
  }

  /**
   * Steps que escriben fuera y que corren al perfilar 'step': sus ancestros por hops habilitados
   * (info streams incluidos) y el propio step. Lo que la UI lista antes de perfilar (diseno §2.4.3).
   */
  public static List<String> escritoresAguasArriba( TransMeta tm, String step ) {
    List<String> l = new ArrayList<String>();
    StepMeta objetivo = buscarStep( tm, step );
    if ( objetivo == null ) {
      return l;
    }
    for ( StepMeta s : ancestros( tm, objetivo ) ) {
      if ( RunMotor.escribe( s ) ) {
        l.add( s.getName() );
      }
    }
    return l;
  }

  /**
   * Texto de confirmacion para "Medir..." (ya traducido), o null si no hay nada que escriba: entonces
   * no hace falta preguntar.
   */
  public static String textoConfirmacion( AbstractMeta m, String idioma ) {
    List<String> l = escritores( m );
    if ( l.isEmpty() ) {
      return null;
    }
    Rotulos r = Rotulos.de( SecMetricas.AREA, idioma );
    return r.f( m instanceof JobMeta ? "confirm.measure_job" : "confirm.measure_trans", lista( l, r ) );
  }

  /** Texto de confirmacion para perfilar 'step' (ya traducido), o null si nada aguas arriba escribe. */
  public static String textoConfirmacionPerfil( TransMeta tm, String step, String idioma ) {
    List<String> l = escritoresAguasArriba( tm, step );
    if ( l.isEmpty() ) {
      return null;
    }
    Rotulos r = Rotulos.de( SecMetricas.AREA, idioma );
    return r.f( "confirm.profile", step, lista( l, r ) );
  }

  static String lista( List<String> l, Rotulos r ) {
    StringBuilder sb = new StringBuilder();
    int max = Math.min( l.size(), 12 );
    for ( int i = 0; i < max; i++ ) {
      sb.append( sb.length() == 0 ? "" : ", " ).append( r.f( "quoted", l.get( i ) ) );
    }
    if ( l.size() > max ) {
      sb.append( ' ' ).append( r.f( "and_more", l.size() - max ) );
    }
    return sb.toString();
  }

  static StepMeta buscarStep( TransMeta tm, String nombre ) {
    if ( tm == null || nombre == null ) {
      return null;
    }
    StepMeta s = tm.findStep( nombre );
    if ( s == null ) {
      for ( StepMeta x : tm.getSteps() ) {
        if ( x.getName().trim().equalsIgnoreCase( nombre.trim() ) ) {
          return x;
        }
      }
    }
    return s;
  }

  /** El step y todo lo que llega a el por hops habilitados (como RunMotor.recortar). */
  static Set<StepMeta> ancestros( TransMeta tm, StepMeta objetivo ) {
    Set<StepMeta> quedan = new LinkedHashSet<StepMeta>();
    quedan.add( objetivo );
    boolean cambio = true;
    while ( cambio ) {
      cambio = false;
      for ( TransHopMeta h : tm.getTransHops() ) {
        if ( h.isEnabled() && h.getFromStep() != null && h.getToStep() != null && contieneNombre( quedan, h.getToStep() )
          && !contieneNombre( quedan, h.getFromStep() ) ) {
          quedan.add( h.getFromStep() );
          cambio = true;
        }
      }
    }
    return quedan;
  }

  /** Por nombre y no por identidad: los hops de un clon pueden apuntar a los StepMeta del original. */
  private static boolean contieneNombre( Set<StepMeta> s, StepMeta x ) {
    for ( StepMeta y : s ) {
      if ( y == x || y.getName().equals( x.getName() ) ) {
        return true;
      }
    }
    return false;
  }

  // ---------------------------------------------------------------- leer una Trans

  /**
   * Lee una Trans ya corrida o en curso (la de Spoon o la de una medicion). Solo lectura: no la toca.
   *
   * @param copia la TransMeta desligada de la captura, para saber que steps son fuente y cuales
   *          escriben sin tocar la del documento vivo; null = la de la Trans
   */
  static CorridaMedida deTrans( Trans t, TransMeta copia, String fuente ) {
    CorridaMedida m = new CorridaMedida();
    m.esJob = false;
    m.fuente = fuente;
    m.nombre = t.getName();
    boolean terminada = t.isFinished();
    if ( terminada ) {
      m.estado = t.getErrors() > 0 ? CON_ERRORES : ( t.isStopped() ? DETENIDA : TERMINADA );
    } else if ( t.isRunning() || t.isPreparing() || t.isInitializing() ) {
      m.estado = EN_CURSO;
      m.enCurso = true;
    } else {
      m.estado = SIN_EMPEZAR;
    }
    m.errores = t.getErrors();

    // pasos (sumando copias), en el orden de la copia si la hay
    TransMeta topo = copia != null ? copia : t.getTransMeta();
    Map<String, PasoMedido> porNombre = new LinkedHashMap<String, PasoMedido>();
    if ( topo != null ) {
      for ( StepMeta s : ReportMotor.ordenTrans( topo ) ) {
        porNombre.put( s.getName(), null );
      }
    }
    Map<String, String> canalAStep = new HashMap<String, String>();
    List<StepMetaDataCombi> combis = t.getSteps() == null ? Collections.<StepMetaDataCombi>emptyList()
      : new ArrayList<StepMetaDataCombi>( t.getSteps() );
    for ( StepMetaDataCombi c : combis ) {
      StepInterface s = c.step;
      if ( s == null ) {
        continue;
      }
      PasoMedido p = porNombre.get( c.stepname );
      if ( p == null ) {
        p = new PasoMedido();
        p.step = c.stepname;
        p.tipo = c.stepMeta == null ? null : c.stepMeta.getStepID();
        p.estado = null;
        porNombre.put( c.stepname, p );
        StepMeta enCopia = topo == null ? null : topo.findStep( c.stepname );
        StepMeta sm = enCopia != null ? enCopia : c.stepMeta;
        if ( sm != null ) {
          p.escribe = RunMotor.escribe( sm );
          p.fuente = topo != null && enCopia != null && !tieneEntrantes( topo, c.stepname );
        }
      }
      p.copias++;
      p.leidas += s.getLinesRead();
      p.escritas += s.getLinesWritten();
      p.entrada += s.getLinesInput();
      p.salida += s.getLinesOutput();
      p.actualizadas += s.getLinesUpdated();
      p.rechazadas += s.getLinesRejected();
      p.errores += s.getErrors();
      p.ms = Math.max( p.ms, s.getRuntime() );
      p.runtime = Math.max( p.runtime, s.getRuntime() );
      String e = null;
      try {
        e = s.getStatus() == null ? null : s.getStatus().name();
      } catch ( Throwable ignored ) {
        // estado opcional
      }
      p.estado = peorEstado( p.estado, e );
      try {
        if ( s.getLogChannel() != null ) {
          canalAStep.put( s.getLogChannel().getLogChannelId(), c.stepname );
        }
      } catch ( Throwable ignored ) {
        // sin canal: el Gantt usa el registro
      }
    }
    for ( PasoMedido p : porNombre.values() ) {
      if ( p != null ) {
        m.pasos.add( p );
      }
    }

    // tiempos: primero las metricas reales (si se recolectaron), si no currentDate/logDate
    long[] trans = duracionesTrans( t, canalAStep, m.pasos );
    m.metricasReales = trans[ 2 ] > 0;
    long ahora = System.currentTimeMillis();
    long ini = trans[ 0 ];
    long fin = trans[ 1 ];
    if ( ini <= 0 ) {
      ini = fechaValida( t.getCurrentDate() );
    }
    if ( fin <= 0 || m.enCurso ) {
      fin = terminada ? fechaValida( t.getLogDate() ) : ahora;
    }
    if ( ini <= 0 ) {
      // sin fechas (fallo antes de empezar): el mayor runtime de los steps
      long mayor = 0;
      for ( PasoMedido p : m.pasos ) {
        mayor = Math.max( mayor, p.ms );
      }
      fin = fin > 0 ? fin : ahora;
      ini = fin - mayor;
    }
    if ( fin < ini ) {
      fin = ini;
    }
    m.inicio = ini;
    m.fin = fin;
    m.snapshots = snapshots( t );
    return m;
  }

  /**
   * Si algun hop habilitado llega al step (info streams incluidos). Recorre los hops y no usa
   * findPreviousSteps, que escribe en una cache de la TransMeta (otros recolectores leen la misma copia).
   */
  static boolean tieneEntrantes( TransMeta tm, String step ) {
    for ( TransHopMeta h : tm.getTransHops() ) {
      if ( h.isEnabled() && h.getFromStep() != null && h.getToStep() != null && step.equals( h.getToStep().getName() ) ) {
        return true;
      }
    }
    return false;
  }

  /** De dos estados de copias, el que mas importa mostrar (en curso antes que terminado). */
  private static String peorEstado( String a, String b ) {
    if ( a == null ) {
      return b;
    }
    if ( b == null ) {
      return a;
    }
    return prioridad( b ) > prioridad( a ) ? b : a;
  }

  private static int prioridad( String e ) {
    if ( "STATUS_HALTED".equals( e ) || "STATUS_HALTING".equals( e ) ) {
      return 6;
    }
    if ( "STATUS_RUNNING".equals( e ) || "STATUS_INIT".equals( e ) || "STATUS_PAUSED".equals( e ) ) {
      return 5;
    }
    if ( "STATUS_IDLE".equals( e ) ) {
      return 4;
    }
    if ( "STATUS_STOPPED".equals( e ) ) {
      return 3;
    }
    if ( "STATUS_FINISHED".equals( e ) || "STATUS_DISPOSED".equals( e ) ) {
      return 2;
    }
    return 1;
  }

  /** Fecha en ms, o -1 si es null o la de "nunca" de Kettle (1900-01-01). */
  static long fechaValida( Date d ) {
    if ( d == null ) {
      return -1;
    }
    long x = d.getTime();
    return x <= 0 ? -1 : x; // 1900 es negativo
  }

  /**
   * Duraciones reales (solo si se recolectaron metricas: Gather performance metrics, el hook o la
   * medicion): llena init/exec de cada paso y devuelve {inicio, fin, cuantas} de la transformacion.
   * <p>
   * No usa MetricsUtil.getAllDurations: Kettle reutiliza el canal de log de una transformacion entre
   * corridas (el registro la reconoce por nombre y archivo) y MetricsUtil SUMA los intervalos de todas
   * ellas con la fecha de la primera. Aca se lee la lista cruda del MetricsRegistry, se toma el ULTIMO
   * intervalo START..STOP de cada metrica y se descarta si es de una corrida anterior.
   */
  private static long[] duracionesTrans( Trans t, Map<String, String> canalAStep, List<PasoMedido> pasos ) {
    long[] r = { 0, 0, 0 };
    String canal = t.getLogChannelId();
    if ( canal == null ) {
      return r;
    }
    long ahora = System.currentTimeMillis();
    long desde = fechaValida( t.getCurrentDate() ); // lo fija prepareExecution antes de inicializar los steps
    long[] tr = ultimoIntervalo( canal, Metrics.METRIC_TRANSFORMATION_EXECUTION_START.getDescription() );
    // el de la corrida anterior termino antes de que empezara esta
    if ( tr != null && ( desde <= 0 || ( tr[ 0 ] <= desde + 50 && ( tr[ 1 ] < 0 || tr[ 1 ] >= desde ) ) ) ) {
      r[ 0 ] = tr[ 0 ];
      r[ 1 ] = tr[ 1 ] < 0 ? ahora : tr[ 1 ];
    }
    Map<String, PasoMedido> porNombre = new HashMap<String, PasoMedido>();
    for ( PasoMedido p : pasos ) {
      porNombre.put( p.step, p );
    }
    String initStep = Metrics.METRIC_STEP_INIT_START.getDescription();
    String execStep = Metrics.METRIC_STEP_EXECUTION_START.getDescription();
    long minimo = Long.MAX_VALUE;
    long maximo = 0;
    int cuantas = 0;
    for ( Map.Entry<String, String> e : canalAStep.entrySet() ) {
      PasoMedido p = porNombre.get( e.getValue() );
      if ( p == null ) {
        continue;
      }
      long[] in = ultimoIntervalo( e.getKey(), initStep );
      if ( in != null && ( desde <= 0 || in[ 0 ] >= desde - 20 ) ) { // los steps se inicializan despues de currentDate
        long fin = in[ 1 ] < 0 ? ahora : in[ 1 ];
        p.initIni = p.initIni < 0 ? in[ 0 ] : Math.min( p.initIni, in[ 0 ] );
        p.initFin = Math.max( p.initFin, fin );
        cuantas++;
        minimo = Math.min( minimo, in[ 0 ] );
        maximo = Math.max( maximo, fin );
      }
      long[] ex = ultimoIntervalo( e.getKey(), execStep );
      if ( ex != null && ( desde <= 0 || ex[ 0 ] >= desde - 20 ) ) {
        long fin = ex[ 1 ] < 0 ? ahora : ex[ 1 ];
        p.execIni = p.execIni < 0 ? ex[ 0 ] : Math.min( p.execIni, ex[ 0 ] );
        p.execFin = Math.max( p.execFin, fin );
        cuantas++;
        minimo = Math.min( minimo, ex[ 0 ] );
        maximo = Math.max( maximo, fin );
      }
    }
    if ( r[ 0 ] <= 0 && cuantas > 0 ) {
      r[ 0 ] = minimo;
      r[ 1 ] = maximo;
    }
    // con metricas reales, el tiempo del step es su barra de ejecucion: la tabla, las barras y el Gantt
    // dicen lo mismo (getRuntime cuenta ademas el arranque del hilo y el dispose)
    for ( PasoMedido p : pasos ) {
      if ( p.execIni > 0 && p.execFin >= p.execIni ) {
        p.ms = p.execFin - p.execIni;
      }
    }
    r[ 2 ] = cuantas;
    return r;
  }

  /**
   * El ultimo intervalo START..STOP de una metrica (por descripcion, como la empareja Kettle) en un
   * canal: {inicio, fin} en ms, fin = -1 si sigue abierto (en curso). null si no hay ninguno.
   */
  static long[] ultimoIntervalo( String canal, String descripcion ) {
    Queue<MetricsSnapshotInterface> q;
    try {
      q = MetricsRegistry.getInstance().getSnapshotLists().get( canal ); // get: no crear listas vacias
    } catch ( Throwable e ) {
      return null;
    }
    if ( q == null ) {
      return null;
    }
    long ini = -1;
    long fin = -1;
    try {
      for ( MetricsSnapshotInterface s : q ) {
        MetricsInterface mi = s.getMetric();
        if ( mi == null || s.getDate() == null || !descripcion.equals( mi.getDescription() ) ) {
          continue;
        }
        if ( mi.getType() == MetricsSnapshotType.START ) {
          ini = s.getDate().getTime();
          fin = -1;
        } else if ( mi.getType() == MetricsSnapshotType.STOP && ini > 0 ) {
          fin = s.getDate().getTime();
        }
      }
    } catch ( RuntimeException e ) {
      return null; // la cola cambio mientras se leia (no deberia: es concurrente)
    }
    return ini > 0 ? new long[] { ini, fin } : null;
  }

  /** Snapshots de rendimiento (solo si la transformacion tenia el monitoreo activado). */
  private static Snapshots snapshots( Trans t ) {
    Map<String, List<StepPerformanceSnapShot>> mapa;
    try {
      mapa = t.getStepPerformanceSnapShots();
    } catch ( Throwable e ) {
      return null;
    }
    if ( mapa == null || mapa.isEmpty() ) {
      return null;
    }
    Snapshots s = new Snapshots();
    int largo = 0;
    for ( Map.Entry<String, List<StepPerformanceSnapShot>> e : new ArrayList<Map.Entry<String, List<StepPerformanceSnapShot>>>(
      mapa.entrySet() ) ) {
      List<StepPerformanceSnapShot> l = e.getValue() == null ? Collections.<StepPerformanceSnapShot>emptyList()
        : new ArrayList<StepPerformanceSnapShot>( e.getValue() );
      if ( l.isEmpty() ) {
        continue;
      }
      String step = l.get( 0 ).getStepName();
      if ( step == null ) {
        step = e.getKey().replaceFirst( "\\.\\d+$", "" );
      }
      List<Long> serie = s.porStep.get( step );
      if ( serie == null ) {
        serie = new ArrayList<Long>();
        s.porStep.put( step, serie );
      }
      for ( int i = 0; i < l.size(); i++ ) {
        long w = Math.max( 0, l.get( i ).getLinesWritten() ); // diff con el anterior: filas en ese intervalo
        if ( i < serie.size() ) {
          serie.set( i, serie.get( i ) + w );
        } else {
          serie.add( w );
        }
      }
      if ( l.size() > largo ) {
        largo = l.size();
        s.instantes.clear();
        for ( StepPerformanceSnapShot x : l ) {
          s.instantes.add( x.getDate() == null ? -1L : x.getDate().getTime() );
        }
      }
    }
    return s.instantes.isEmpty() ? null : s;
  }

  // ---------------------------------------------------------------- leer un Job

  /** Lee un Job ya corrido o en curso (el de Spoon o el de una medicion). Solo lectura. */
  static CorridaMedida deJob( Job j, String fuente, List<TransHija> hijas ) {
    CorridaMedida m = new CorridaMedida();
    m.esJob = true;
    m.fuente = fuente;
    m.nombre = j.getJobname();
    boolean terminado = j.isFinished();
    Result r = j.getResult();
    if ( terminado ) {
      m.resultado = r == null ? null : r.getResult();
      m.estado = j.isStopped() ? DETENIDA : ( r != null && !r.getResult() ? CON_ERRORES : TERMINADA );
    } else if ( j.isActive() ) {
      m.estado = EN_CURSO;
      m.enCurso = true;
    } else {
      m.estado = SIN_EMPEZAR;
    }
    m.errores = r == null ? j.getErrors() : Math.max( r.getNrErrors(), j.getErrors() );

    long[] marcas = { -1, -1 };
    JobTracker raiz = j.getJobTracker();
    if ( raiz != null ) {
      for ( int intento = 0; intento < 3; intento++ ) {
        try {
          m.entradas.clear();
          recorrer( raiz, 0, m.nombre, m.entradas, new HashMap<String, Deque<EntradaMedida>>(), marcas );
          break;
        } catch ( RuntimeException e ) { // el arbol cambia mientras el job corre
          RunMotor.dormir( 20 );
        }
      }
    }
    if ( hijas != null ) {
      m.hijas.addAll( hijas );
      for ( TransHija h : hijas ) { // cada sub-transformacion con la ejecucion de su entrada
        for ( EntradaMedida e : m.entradas ) {
          if ( e.hija == null && h.job != null && h.job.equals( e.job ) && h.entrada != null && h.entrada.equals(
            e.nombre ) && !h.usada ) {
            e.hija = h;
            h.usada = true;
            break;
          }
        }
      }
    }

    long ahora = System.currentTimeMillis();
    long ini = fechaValida( j.getCurrentDate() );
    if ( marcas[ 0 ] > 0 && ( ini <= 0 || marcas[ 0 ] < ini ) ) {
      ini = marcas[ 0 ];
    }
    long fin = terminado ? fechaValida( j.getLogDate() ) : ahora;
    if ( terminado && marcas[ 1 ] > fin ) {
      fin = marcas[ 1 ];
    }
    for ( EntradaMedida e : m.entradas ) {
      if ( ini <= 0 || ( e.ini > 0 && e.ini < ini ) ) {
        ini = e.ini;
      }
      if ( e.fin > fin ) {
        fin = e.fin;
      }
    }
    if ( ini <= 0 ) {
      ini = fin > 0 ? fin : ahora;
    }
    if ( fin < ini ) {
      fin = ini;
    }
    m.inicio = ini;
    m.fin = fin;
    return m;
  }

  /**
   * Recorre el arbol del JobTracker. Cada entrada aparece al empezar (resultado null) y al terminar;
   * un sub-job es un JobTracker hijo sin resultado; las marcas de inicio y fin del job tienen nombre null.
   */
  /** Claves de Kettle (paquete org.pentaho.di.job) de los motivos del JobTracker y su codigo propio. */
  private static final String[][] RAZONES = { { "Job.Reason.Started", "start", "start" },
    { "Job.Reason.StartOfJobentry", "entry_start", "Start of job entry" },
    { "Job.Comment.FollowedUnconditional", "unconditional", "Followed unconditional link" },
    { "Job.Comment.FollowedSuccess", "success", "Followed link after success" },
    { "Job.Comment.FollowedFailure", "failure", "Followed link after failure" } };

  /**
   * El motivo de getReason() (texto de Kettle en el idioma de Spoon) como codigo propio, para que el
   * informe lo traduzca: start, entry_start, unconditional, success o failure; null si no se reconoce.
   * Se compara con el mismo mensaje de Kettle en esta JVM y con el texto en ingles.
   */
  static String codigoRazon( String razon ) {
    if ( razon == null || razon.trim().isEmpty() ) {
      return null;
    }
    String t = razon.trim();
    for ( String[] x : RAZONES ) {
      String local = null;
      try {
        local = BaseMessages.getString( Job.class, x[ 0 ] );
      } catch ( Throwable ignorada ) {
        // sin mensajes: solo el ingles
      }
      if ( t.equalsIgnoreCase( x[ 2 ] ) || ( local != null && !local.startsWith( "!" ) && t.equalsIgnoreCase( local
        .trim() ) ) ) {
        return x[ 1 ];
      }
    }
    return null;
  }

  private static void recorrer( JobTracker jt, int nivel, String job, List<EntradaMedida> salida,
                                Map<String, Deque<EntradaMedida>> abiertas, long[] marcas ) {
    List<JobTracker> hijos = new ArrayList<JobTracker>( jt.getJobTrackers() );
    for ( JobTracker h : hijos ) {
      JobEntryResult r = h.getJobEntryResult();
      if ( r == null ) {
        String sub = h.getJobName() == null ? job : h.getJobName();
        recorrer( h, nivel + 1, sub, salida, abiertas, new long[] { -1, -1 } );
        continue;
      }
      long cuando = fechaValida( r.getLogDate() );
      if ( r.getJobEntryName() == null ) { // inicio o fin del job entero
        if ( r.getResult() == null ) {
          marcas[ 0 ] = cuando;
        } else {
          marcas[ 1 ] = cuando;
        }
        continue;
      }
      String clave = nivel + "|" + job + "|" + r.getJobEntryName() + "#" + r.getJobEntryNr();
      if ( r.getResult() == null ) {
        EntradaMedida e = new EntradaMedida();
        e.nombre = r.getJobEntryName();
        e.nr = r.getJobEntryNr();
        e.nivel = nivel;
        e.job = job;
        e.archivo = r.getJobEntryFilename();
        e.razon = r.getReason();
        e.razonCodigo = codigoRazon( e.razon );
        e.ini = cuando;
        salida.add( e );
        Deque<EntradaMedida> q = abiertas.get( clave );
        if ( q == null ) {
          q = new ArrayDeque<EntradaMedida>();
          abiertas.put( clave, q );
        }
        q.add( e );
      } else {
        Deque<EntradaMedida> q = abiertas.get( clave );
        EntradaMedida e = q == null ? null : q.poll();
        if ( e == null ) { // termino sin marca de inicio (raro): una entrada de duracion 0
          e = new EntradaMedida();
          e.nombre = r.getJobEntryName();
          e.nr = r.getJobEntryNr();
          e.nivel = nivel;
          e.job = job;
          e.archivo = r.getJobEntryFilename();
          e.ini = cuando;
          salida.add( e );
        }
        e.fin = cuando;
        e.ok = r.getResult().getResult();
        e.errores = r.getResult().getNrErrors();
      }
    }
  }

  // ---------------------------------------------------------------- medir: ejecutar una copia

  /**
   * "Medir..." de una transformacion: ejecuta la copia entera (ESCRIBE sus salidas) con metricas y
   * snapshots, y la lee. La copia tiene que ser propia: se le activa el monitoreo.
   */
  static CorridaMedida medirTrans( TransMeta copia, long esperaMs, Progreso pr ) throws Exception {
    copia.setCapturingStepPerformanceSnapShots( true );
    copia.setStepPerformanceCapturingDelay( INTERVALO_SNAPSHOT_MS );
    copia.setStepPerformanceCapturingSizeLimit( "2000" );
    Trans t = new Trans( copia, RunMotor.padreDeLog() );
    t.setLogLevel( LogLevel.BASIC );
    t.setGatheringMetrics( true );
    RunMotor.prepararVariables( t, copia );
    String canal = t.getLogChannelId();
    try {
      try {
        t.prepareExecution( null );
      } catch ( Exception e ) {
        CorridaMedida m = deTrans( t, copia, COPIA );
        m.estado = FALLO_INICIO;
        m.falloInicio = RunMotor.primeraLinea( e );
        m.errores = Math.max( 1, t.getErrors() );
        m.log = RunMotor.colaLog( t.getLogChannelId(), true );
        return m;
      }
      t.startThreads();
      boolean[] corte = esperar( t, null, esperaMs, pr );
      CorridaMedida m = deTrans( t, copia, COPIA );
      m.cortada = corte[ 0 ];
      m.cancelada = corte[ 1 ];
      if ( m.cortada || m.cancelada ) {
        m.estado = DETENIDA;
      }
      if ( t.getErrors() > 0 ) {
        m.log = RunMotor.colaLog( t.getLogChannelId(), true );
      }
      return m;
    } finally {
      limpiar( canal );
    }
  }

  /**
   * "Medir..." de un job: lo ejecuta entero (ESCRIBE lo que escriban sus transformaciones) con
   * metricas. Engancha un JobEntryListener en el job y, por DelegationListener, en cada sub-job, para
   * leer los steps de cada sub-transformacion apenas termina (JobEntryTrans.getTrans()).
   */
  static CorridaMedida medirJob( JobMeta copia, long esperaMs, Progreso pr ) throws Exception {
    final Job j = new Job( copia.getRepository(), copia, RunMotor.padreDeLog() );
    j.setLogLevel( LogLevel.BASIC );
    j.setGatheringMetrics( true );
    j.initializeVariablesFrom( null );
    copia.setInternalKettleVariables( j );
    j.copyParametersFrom( copia );
    j.activateParameters();
    final List<TransHija> hijas = Collections.synchronizedList( new ArrayList<TransHija>() );
    final JobEntryListener alTerminar = new JobEntryListener() {
      @Override
      public void beforeExecution( Job job, JobEntryCopy c, JobEntryInterface e ) {
      }

      @Override
      public void afterExecution( Job job, JobEntryCopy c, JobEntryInterface e, Result r ) {
        if ( e instanceof JobEntryTrans ) {
          try {
            Trans st = ( (JobEntryTrans) e ).getTrans();
            if ( st != null ) {
              hijas.add( hija( job, c, st ) );
            }
          } catch ( Throwable ignored ) {
            // una sub-transformacion sin datos no corta la medicion
          }
        }
      }
    };
    DelegationListener enSubJobs = new DelegationListener() {
      @Override
      public void jobDelegationStarted( Job sub, JobExecutionConfiguration conf ) {
        sub.addJobEntryListener( alTerminar );
        sub.addDelegationListener( this );
      }

      @Override
      public void transformationDelegationStarted( Trans sub, TransExecutionConfiguration conf ) {
      }
    };
    j.addJobEntryListener( alTerminar );
    j.addDelegationListener( enSubJobs );
    String canal = j.getLogChannelId();
    try {
      j.start();
      RunMotor.dormir( 30 );
      boolean[] corte = esperar( null, j, esperaMs, pr );
      CorridaMedida m = deJob( j, COPIA, new ArrayList<TransHija>( hijas ) );
      m.cortada = corte[ 0 ];
      m.cancelada = corte[ 1 ];
      if ( m.cortada || m.cancelada ) {
        m.estado = DETENIDA;
      }
      if ( m.errores > 0 || Boolean.FALSE.equals( m.resultado ) ) {
        m.log = RunMotor.colaLog( j.getLogChannelId(), true );
      }
      return m;
    } finally {
      limpiar( canal );
    }
  }

  /** Lee los steps de una sub-transformacion recien terminada (hilo del job). */
  private static TransHija hija( Job job, JobEntryCopy c, Trans st ) {
    CorridaMedida x = deTrans( st, null, COPIA );
    TransHija h = new TransHija();
    h.job = job.getJobname();
    h.entrada = c.getName();
    h.nombre = st.getName();
    h.ini = x.inicio;
    h.fin = x.fin;
    h.errores = st.getErrors();
    h.pasos.addAll( x.pasos );
    return h;
  }

  /** Espera el fin; corta por tiempo o por cancelacion (stopAll). Devuelve {cortada, cancelada}. */
  private static boolean[] esperar( Trans t, Job j, long esperaMs, Progreso pr ) {
    boolean[] r = { false, false };
    long limite = System.currentTimeMillis() + Math.max( 1000, esperaMs );
    while ( t != null ? !t.isFinished() : !j.isFinished() ) {
      if ( pr != null && pr.cancelado() ) {
        r[ 1 ] = true;
        break;
      }
      if ( System.currentTimeMillis() > limite ) {
        r[ 0 ] = true;
        break;
      }
      RunMotor.dormir( 50 );
    }
    if ( r[ 0 ] || r[ 1 ] ) {
      if ( t != null ) {
        t.stopAll();
        RunMotor.esperarFin( t, 10000 );
      } else {
        j.stopAll();
        j.waitUntilFinished( 10000 );
      }
    }
    return r;
  }

  /** Suelta el log, el registro y las metricas de una corrida propia (nunca la de Spoon). */
  static void limpiar( String canal ) {
    if ( canal == null ) {
      return;
    }
    try {
      Set<String> ids = new LinkedHashSet<String>();
      Deque<String> cola = new ArrayDeque<String>();
      cola.add( canal );
      LoggingRegistry reg = LoggingRegistry.getInstance();
      while ( !cola.isEmpty() && ids.size() < 10000 ) {
        String x = cola.poll();
        if ( ids.add( x ) ) {
          List<String> hijos = reg.getLogChannelChildren( x );
          if ( hijos != null ) {
            cola.addAll( hijos );
          }
        }
      }
      MetricsRegistry mr = MetricsRegistry.getInstance();
      for ( String id : ids ) {
        mr.getSnapshotLists().remove( id );
        mr.getSnapshotMaps().remove( id );
      }
    } catch ( Throwable ignored ) {
      // no es grave
    }
    RunMotor.liberarLog( canal );
  }

  // ---------------------------------------------------------------- JSON (Seccion.datos)

  /**
   * Forma {@code run_metrics} de Seccion.datos: valores crudos (ms, filas, ISO-8601), nada traducido.
   * Los tiempos de pasos y entradas van en ms relativos al inicio de la corrida.
   */
  ObjectNode datos() {
    ObjectNode o = ModeloJson.mapper().createObjectNode();
    o.put( "section", "metrics" );
    o.put( "shape", "run_metrics" );
    o.put( "kind", esJob ? "job" : "transformation" );
    o.put( "name", nombre );
    o.put( "source", fuente );
    o.put( "state", estado );
    if ( resultado != null ) {
      o.put( "result", resultado ? "ok" : "failed" );
    }
    o.put( "start", iso( inicio ) );
    o.put( "end", iso( fin ) );
    o.put( "duration_ms", ms() );
    o.put( "errors", errores );
    o.put( "gantt", metricasReales || esJob ? "real" : "approximate" );
    if ( cortada ) {
      o.put( "stopped_by_timeout", true );
    }
    if ( cancelada ) {
      o.put( "cancelled", true );
    }
    if ( falloInicio != null ) {
      o.put( "init_error", falloInicio );
    }
    if ( hayFilas() ) {
      o.put( "rows_read", filasLeidas() );
      o.put( "rows_written", filasEscritas() );
    }
    if ( !esJob ) {
      PasoMedido lento = masLento();
      if ( lento != null ) {
        o.putObject( "slowest_step" ).put( "step", lento.step ).put( "ms", lento.ms );
      }
      o.set( "steps", pasosJson( pasos, inicio ) );
      if ( snapshots != null ) {
        ObjectNode s = o.putObject( "snapshots" );
        ArrayNode t = s.putArray( "at_ms" );
        for ( Long x : snapshots.instantes ) {
          t.add( x < 0 ? -1 : x - inicio );
        }
        ObjectNode w = s.putObject( "rows_written_per_interval" );
        for ( Map.Entry<String, List<Long>> e : snapshots.porStep.entrySet() ) {
          ArrayNode a = w.putArray( e.getKey() );
          for ( Long x : e.getValue() ) {
            a.add( x );
          }
        }
      }
    } else {
      ArrayNode es = o.putArray( "entries" );
      for ( EntradaMedida e : entradas ) {
        ObjectNode x = es.addObject();
        x.put( "entry", e.nombre );
        x.put( "nr", e.nr );
        x.put( "level", e.nivel );
        x.put( "job", e.job );
        x.put( "start_ms", e.ini < 0 ? -1 : e.ini - inicio );
        x.put( "end_ms", e.fin < 0 ? -1 : e.fin - inicio );
        x.put( "duration_ms", e.ms() );
        if ( e.ok != null ) {
          x.put( "result", e.ok ? "ok" : "failed" );
        }
        if ( e.errores > 0 ) {
          x.put( "errors", e.errores );
        }
        if ( e.razon != null ) {
          x.put( "reason", e.razon );
        }
        if ( e.razonCodigo != null ) {
          x.put( "reason_code", e.razonCodigo );
        }
        if ( e.archivo != null ) {
          x.put( "file", e.archivo );
        }
      }
      if ( !hijas.isEmpty() ) {
        ArrayNode hs = o.putArray( "transformations" );
        for ( TransHija h : hijas ) {
          ObjectNode x = hs.addObject();
          x.put( "job", h.job );
          x.put( "entry", h.entrada );
          x.put( "transformation", h.nombre );
          x.put( "start_ms", h.ini < 0 ? -1 : h.ini - inicio );
          x.put( "duration_ms", h.ini < 0 || h.fin < 0 ? -1 : h.fin - h.ini );
          x.put( "errors", h.errores );
          x.set( "steps", pasosJson( h.pasos, inicio ) );
        }
      }
    }
    return o;
  }

  private static ArrayNode pasosJson( List<PasoMedido> pasos, long base ) {
    ArrayNode a = ModeloJson.mapper().createArrayNode();
    for ( PasoMedido p : pasos ) {
      ObjectNode x = a.addObject();
      x.put( "step", p.step );
      if ( p.tipo != null ) {
        x.put( "type", p.tipo );
      }
      x.put( "copies", p.copias );
      x.put( "read", p.leidas );
      x.put( "written", p.escritas );
      x.put( "input", p.entrada );
      x.put( "output", p.salida );
      x.put( "updated", p.actualizadas );
      x.put( "rejected", p.rechazadas );
      x.put( "errors", p.errores );
      x.put( "status", p.estado );
      x.put( "time_ms", p.ms );
      x.put( "runtime_ms", p.runtime );
      x.put( "rows_per_s", Math.round( p.filasPorSegundo() * 10 ) / 10.0 );
      if ( p.fuente ) {
        x.put( "source", true );
      }
      if ( p.escribe ) {
        x.put( "writes", true );
      }
      if ( p.initIni > 0 ) {
        x.put( "init_start_ms", p.initIni - base );
        x.put( "init_end_ms", p.initFin - base );
      }
      if ( p.execIni > 0 ) {
        x.put( "exec_start_ms", p.execIni - base );
        x.put( "exec_end_ms", p.execFin - base );
      }
    }
    return a;
  }

  /** ISO-8601 con zona, con digitos ASCII (Locale.ROOT), en la zona de la JVM. */
  static String iso( long ms ) {
    if ( ms <= 0 ) {
      return null;
    }
    SimpleDateFormat f = new SimpleDateFormat( "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT );
    f.setTimeZone( TimeZone.getDefault() );
    return f.format( new Date( ms ) );
  }
}
