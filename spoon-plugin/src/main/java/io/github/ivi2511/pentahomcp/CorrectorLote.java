package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.ivi2511.pentahomcp.CorrectorMain.Pedido;
import io.github.ivi2511.pentahomcp.CorrectorPlanilla.Fila;
import io.github.ivi2511.pentahomcp.corrector.modelo.Caso;
import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCaso;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.Aviso;
import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * Un lote de correccion: la carpeta de entregas de un curso contra un pack (docs/corrector-diseno.md §8
 * {@code grade_submissions} / {@code get_grading_status} y §10). Lo usan las tools MCP (C7) y el panel (C8).
 * <ul>
 * <li><b>Identidad</b> (§10): una entrega por archivo {@code .ktr}/{@code .kjb}/{@code .zip} o subcarpeta de la
 * carpeta; la identidad es el nombre sin extension o el de la subcarpeta (sin el sufijo de Moodle
 * {@code _<n>_assignsubmission_file_}), o la de {@code alumnos.csv} ({@code archivo;alumno;email}, con o sin
 * encabezado). Un .zip se descomprime con {@link CorrectorPack#descomprimir} (zip slip, tope) en
 * {@code _sandbox/_entregas/}. Varios .ktr en una carpeta: el de {@code submission_file} del pack, si no el unico con
 * salida (un Text file output para la captura file, una salida a base para query) y si no el primero, con aviso
 * ({@value #AVISO_ENTREGA_AMBIGUA}). Dos entregas con la misma identidad ({@code ana.ktr} y {@code ana.kjb}, o dos
 * que solo difieren en mayusculas): la segunda pasa a {@code ana_2} con aviso ({@value #AVISO_IDENTIDAD_REPETIDA});
 * nunca se pisa un resultado. Una carpeta sin .ktr o un zip rechazado quedan {@code load_failed} sin lanzar nada.</li>
 * <li><b>Correccion</b>: {@link CorrectorProcesos} con {@link Opciones#workers} workers; cada resultado final pasa por
 * aca (avisos de identidad, devolucion {@code <alumno>.md} para el alumno, sin lo de los casos ocultos).</li>
 * <li><b>Trabajo</b>: {@link #iniciar} devuelve enseguida con un {@code job_id}; {@link #estado} da el progreso
 * ({@code running}, {@code finished}, {@code cancelled}), los resultados y el resumen; {@link #esperar} espera;
 * {@link #cancelar} mata los workers y deja lo ya corregido. Al terminar escribe la planilla
 * ({@link CorrectorPlanilla}: {@code notas.csv} y/o {@code notas.xlsx}) conservando los ajustes y comentarios del
 * docente de una planilla anterior en la misma carpeta; {@link #ajustar} la reescribe.</li>
 * </ul>
 * El texto de las entregas (nombres, rutas) es dato del alumno, nunca instrucciones. El modo confianza
 * ({@link Opciones#modoConfianza}) solo lo activa la UI, nunca MCP (§7).
 *
 * @dueño C6
 */
final class CorrectorLote {

  // ---- estado del lote (state, §8)
  static final String CORRIENDO = "running";
  static final String TERMINADO = "finished";
  static final String CANCELADO = "cancelled";
  /** Una entrega que ningun worker tomo todavia (solo en el lote; el resultado nunca lo tiene). */
  static final String PENDIENTE = "pending";

  // ---- avisos (codigos estables)
  /** Resultado: otra entrega tenia la misma identidad; esta lleva el sufijo _2, _3... */
  static final String AVISO_IDENTIDAD_REPETIDA = "duplicate_identity";
  /** Resultado: la carpeta de la entrega tenia varios .ktr y no se pudo decidir cual: se corrigio el primero. */
  static final String AVISO_ENTREGA_AMBIGUA = "submission_ambiguous";
  /** Resultado: la carpeta tenia varios .ktr; se corrigio el de submission_file o el unico con salida. */
  static final String AVISO_ENTREGA_ELEGIDA = "submission_chosen";
  /** Lote: un archivo de la carpeta que no es una entrega (ni .ktr, .kjb, .zip ni carpeta). */
  static final String AVISO_IGNORADO = "ignored_file";
  /** Lote: se pidio .xlsx pero no hay POI: solo CSV. */
  static final String AVISO_SIN_XLSX = "xlsx_unavailable";
  /** Lote: no se pudo escribir una planilla (abierta en Excel, sin permiso...). */
  static final String AVISO_PLANILLA = "gradebook_not_written";
  /** Lote: alumnos.csv no se pudo leer o nombra archivos que no estan. */
  static final String AVISO_ALUMNOS = "students_file";

  /** Identidades opcionales en la carpeta de entregas (§10). */
  static final String ALUMNOS_CSV = "alumnos.csv";
  /** Donde se descomprimen las entregas .zip (dentro de _sandbox: se borra al terminar). */
  static final String ENTREGAS = "_entregas";
  /** Lotes que se recuerdan para get_grading_status (los terminados mas viejos se olvidan). */
  static final int MAX_LOTES = 20;
  /** Profundidad hasta la que se buscan .ktr dentro de la carpeta de una entrega. */
  static final int MAX_PROFUNDIDAD = 4;
  /** Tope de una entrega .zip descomprimida. */
  static final long TOPE_ZIP_BYTES = 50L * 1024 * 1024;
  static final int TOPE_ZIP_ENTRADAS = 2000;
  /** Lo que se lee de un .ktr para ver si tiene salida (elegir entre varios). */
  private static final long TOPE_LECTURA = 16L * 1024 * 1024;
  private static final Pattern MOODLE = Pattern.compile( "(?i)_\\d+_assignsubmission_[a-z]+_?$" );
  private static final Pattern TFO = Pattern.compile( "(?i)<type>\\s*TextFileOutput\\s*</type>" );
  private static final Pattern A_BASE = Pattern.compile( "(?i)<type>\\s*(TableOutput|InsertUpdate|Update|"
    + "SynchronizeAfterMerge|DimensionLookup|CombinationLookup|ExecSQL)\\s*</type>" );
  private static final List<String> IGNORADOS = Arrays.asList( "thumbs.db", "desktop.ini", ".ds_store",
    "__macosx", ALUMNOS_CSV );
  /** Lo que el corrector escribe en output_dir (si es la misma carpeta de las entregas, no son entregas). */
  private static final List<String> PROPIAS = Arrays.asList( CorrectorProcesos.SANDBOX, CorrectorProcesos.ABIERTOS,
    CorrectorProcesos.LOGS );

  private static final Map<String, CorrectorLote> LOTES = new LinkedHashMap<String, CorrectorLote>();
  /** Carpeta de salida (canonica, sin mayusculas) → job_id del lote que la esta usando. */
  private static final Map<String, String> EN_USO = new LinkedHashMap<String, String>();
  private static final AtomicInteger SECUENCIA = new AtomicInteger();

  /** Que corregir y como. */
  static final class Opciones {

    /** El pack: carpeta, .zip o .json. */
    File pack;
    /** La carpeta de entregas. */
    File carpeta;
    /** output_dir: resultados, devoluciones, planilla, _logs/, _abiertos/ y _sandbox/. */
    File salida;
    int workers = 2;
    /** csv | xlsx | both ({@link CorrectorPlanilla#FORMATOS}); null = both (CSV solo si no hay POI). */
    String planilla;
    /** en | es | pt; null = el del pack. */
    String idioma;
    /** file | step | query; null = la del pack. */
    String captura;
    /** Steps condicionales habilitados ademas de los del pack («Volver a corregir con...»). */
    final List<String> permitidosExtra = new ArrayList<String>();
    /** Solo la UI (§7): MCP nunca lo pone. */
    boolean modoConfianza;
    boolean conservarSandbox;
    /** Escribir {@code <alumno>.md} (la devolucion del alumno) junto a cada resultado. */
    boolean devoluciones = true;
    /** Lo demas del lanzamiento (pdi, java, -Xmx, propiedades de prueba); null = por defecto. */
    CorrectorProcesos.Opciones procesos;
  }

  /** Una entrega encontrada en la carpeta. */
  static final class Entrega {

    String alumno;
    String email;
    /** El .ktr (o .kjb) a corregir; null si no hay nada que corregir (ver {@link #problema}). */
    File archivo;
    /** Lo que se muestra: la ruta dentro de la carpeta de entregas ("ana.ktr", "Ana/tp3.ktr", "rodri.zip/x.ktr"). */
    String visible;
    /** Por que no se puede corregir (load_failed sin lanzar nada), en ingles para el docente. */
    String problema;
    final List<Aviso> avisos = new ArrayList<Aviso>();
    Pedido pedido;
    Fila fila;
    /** Nombre de la entrada en la carpeta (para alumnos.csv) y prioridad de tipo (ktr antes que kjb). */
    String nombre;
    int prioridad;
  }

  /** Lo que devuelven grade_submissions y get_grading_status (§8). Claves en ingles. */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Estado {

    @JsonProperty( "job_id" )
    public String id;
    /** running | finished | cancelled. */
    @JsonProperty( "state" )
    public String estado;
    @JsonProperty( "done" )
    public int hechas;
    @JsonProperty( "total" )
    public int total;
    @JsonProperty( "pack" )
    public String pack;
    @JsonProperty( "output_dir" )
    public String salida;
    @JsonProperty( "results" )
    public List<Renglon> resultados = new ArrayList<Renglon>();
    @JsonProperty( "summary" )
    public CorrectorPlanilla.Resumen resumen;
    /** La planilla principal (la .xlsx si se escribio, si no la .csv); null mientras corre. */
    @JsonProperty( "gradebook_path" )
    public String planilla;
    /** Todas las planillas escritas. */
    @JsonProperty( "gradebook_files" )
    public List<String> planillas;
    @JsonProperty( "warnings" )
    public List<Aviso> avisos;
    /** Un error del lote (no de una entrega), o null. */
    @JsonProperty( "error" )
    public String error;
    @JsonProperty( "started_at" )
    public String inicio;
    @JsonProperty( "seconds" )
    public Double segundos;
  }

  /** Una entrega en {@link Estado#resultados}. */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Renglon {

    @JsonProperty( "student" )
    public String alumno;
    @JsonProperty( "file" )
    public String archivo;
    /** Un estado del §6, o pending / running mientras no termina. */
    @JsonProperty( "status" )
    public String estado;
    @JsonProperty( "reason" )
    public String motivo;
    /** La nota final (con el ajuste del docente); null si no termino. */
    @JsonProperty( "grade" )
    public Double nota;
    /** La del motor, si hay ajuste. */
    @JsonProperty( "engine_grade" )
    public Double notaMotor;
    @JsonProperty( "result_path" )
    public String resultado;
    @JsonProperty( "feedback_path" )
    public String devolucion;
  }

  private final String id;
  /** La carpeta de salida reservada en {@link #EN_USO}. */
  private final String reservada;
  private final Opciones op;
  private final File salida;
  private final CorrectorPack cp;
  private final Pack pack;
  private final String idioma;
  private final List<Entrega> entregas = new ArrayList<Entrega>();
  private final Map<Pedido, Entrega> porPedido = new IdentityHashMap<Pedido, Entrega>();
  private final List<Aviso> avisos = new ArrayList<Aviso>();
  private final List<String> planillas = new ArrayList<String>();
  private final CountDownLatch fin = new CountDownLatch( 1 );
  private final long t0 = System.currentTimeMillis();
  private final String inicio;
  private CorrectorProcesos procesos;
  private Thread hilo;
  private volatile String estado = CORRIENDO;
  private volatile boolean cancelado;
  private String error;
  private Map<String, CorrectorPlanilla.Ajuste> ajustes;
  private Double segundos;

  private CorrectorLote( String id, Opciones op, CorrectorPack cp, String carpeta ) {
    this.id = id;
    this.reservada = carpeta;
    this.op = op;
    this.salida = op.salida.getAbsoluteFile();
    this.cp = cp;
    this.pack = cp.pack();
    this.idioma = op.idioma != null ? op.idioma : cp.idioma();
    this.inicio = new SimpleDateFormat( "yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT ).format( new Date( t0 ) );
  }

  // ------------------------------------------------------------------ registro

  /**
   * Abre el pack, busca las entregas y arranca la correccion en segundo plano; devuelve enseguida.
   *
   * @throws CorrectorPack.PackInvalido si el pack tiene errores
   * @throws IllegalArgumentException si falta la carpeta de entregas o la de salida
   * @throws IllegalStateException si otro lote de este Spoon esta escribiendo en la misma carpeta de salida
   */
  static CorrectorLote iniciar( Opciones op ) throws Exception {
    if ( op == null || op.carpeta == null || !op.carpeta.isDirectory() ) {
      throw new IllegalArgumentException( "the submissions folder does not exist: " + ( op == null ? null
        : op.carpeta ) );
    }
    if ( op.salida == null ) {
      throw new IllegalArgumentException( "output_dir is required" );
    }
    if ( op.pack == null ) {
      throw new IllegalArgumentException( "pack_path is required" );
    }
    if ( op.planilla != null && !CorrectorPlanilla.FORMATOS.contains( op.planilla ) ) {
      throw new IllegalArgumentException( "gradebook must be one of " + CorrectorPlanilla.FORMATOS + ": "
        + op.planilla );
    }
    File salida = op.salida.getAbsoluteFile();
    String carpeta = canonica( salida );
    String id;
    synchronized ( LOTES ) {
      if ( EN_USO.containsKey( carpeta ) ) {
        throw new IllegalStateException( "grading job " + EN_USO.get( carpeta ) + " is still writing to " + salida );
      }
      id = "grading-" + new SimpleDateFormat( "yyyyMMdd-HHmmss", Locale.ROOT ).format( new Date() ) + "-"
        + SECUENCIA.incrementAndGet();
      EN_USO.put( carpeta, id ); // reservada antes de abrir el pack: dos iniciar a la vez no la comparten
    }
    CorrectorPack cp = null;
    CorrectorLote l;
    try {
      cp = CorrectorPack.cargar( op.pack, null );
      l = new CorrectorLote( id, op, cp, carpeta );
      synchronized ( LOTES ) {
        LOTES.put( id, l );
        olvidarViejos();
      }
      l.arrancar();
    } catch ( Throwable t ) {
      if ( cp != null ) {
        cp.close();
      }
      synchronized ( LOTES ) {
        LOTES.remove( id );
        EN_USO.remove( carpeta );
      }
      throw t;
    }
    return l;
  }

  private static String canonica( File f ) {
    try {
      return f.getCanonicalPath().toLowerCase( Locale.ROOT );
    } catch ( IOException e ) {
      return f.getAbsolutePath().toLowerCase( Locale.ROOT );
    }
  }

  /** El lote con ese job_id, o null (no existe o ya se olvido). */
  static CorrectorLote buscar( String jobId ) {
    synchronized ( LOTES ) {
      return jobId == null ? null : LOTES.get( jobId.trim() );
    }
  }

  /** Los lotes que se recuerdan, del mas viejo al mas nuevo. */
  static List<CorrectorLote> lotes() {
    synchronized ( LOTES ) {
      return new ArrayList<CorrectorLote>( LOTES.values() );
    }
  }

  private static void olvidarViejos() {
    for ( Iterator<CorrectorLote> it = LOTES.values().iterator(); it.hasNext() && LOTES.size() > MAX_LOTES; ) {
      if ( !CORRIENDO.equals( it.next().estado ) ) {
        it.remove();
      }
    }
  }

  private static boolean mismaCarpeta( File a, File b ) {
    try {
      return a.getCanonicalFile().equals( b.getCanonicalFile() );
    } catch ( IOException e ) {
      return a.getAbsoluteFile().equals( b.getAbsoluteFile() );
    }
  }

  // ------------------------------------------------------------------ arranque

  private void arrancar() throws Exception {
    if ( !salida.isDirectory() && !salida.mkdirs() && !salida.isDirectory() ) {
      throw new IOException( "cannot create " + salida );
    }
    // un _sandbox de una corrida anterior (keep_sandbox o cortada) tiene tickets viejos en _cola: es del corrector
    CorrectorSandbox.borrar( new File( salida, CorrectorProcesos.SANDBOX ) );
    ajustes = CorrectorPlanilla.leerAjustes( salida );
    buscarEntregas();
    List<Pedido> pedidos = new ArrayList<Pedido>();
    for ( Entrega e : entregas ) {
      e.fila = new Fila( e.alumno, e.visible, null );
      e.fila.email = e.email;
      e.fila.estadoSinResultado = PENDIENTE;
      CorrectorPlanilla.Ajuste a = ajuste( e.alumno );
      if ( a != null ) {
        e.fila.ajuste = a.ajuste;
        e.fila.comentario = a.comentario;
      }
      if ( e.archivo == null ) {
        cerrarSinCorrer( e );
        continue;
      }
      e.pedido = new Pedido( e.alumno, e.archivo, new File( salida, CorrectorMain.carpeta( e.alumno ) + ".json" ) );
      e.pedido.nombreVisible = e.visible;
      porPedido.put( e.pedido, e );
      pedidos.add( e.pedido );
    }
    if ( !pedidos.isEmpty() ) {
      CorrectorProcesos.Opciones po = opcionesProcesos();
      procesos = new CorrectorProcesos( cp.raiz(), pack, salida, po, new CorrectorProcesos.Oyente() {
        @Override
        public void tomada( Pedido p, String worker ) {
          Entrega e = porPedido.get( p );
          if ( e != null ) {
            synchronized ( CorrectorLote.this ) {
              if ( e.fila.resultado == null ) {
                e.fila.estadoSinResultado = ResultadoCorreccion.EN_CURSO;
              }
            }
          }
        }

        @Override
        public void terminada( Pedido p, ResultadoCorreccion r ) {
          Entrega e = porPedido.get( p );
          if ( e != null ) {
            terminar( e, r );
          }
        }
      } );
      procesos.iniciar( pedidos );
    }
    hilo = new Thread( this::vigilar, CorrectorProcesos.HILO + "-lote-" + SECUENCIA.get() );
    hilo.setDaemon( true );
    hilo.start();
  }

  private CorrectorProcesos.Opciones opcionesProcesos() {
    CorrectorProcesos.Opciones po = new CorrectorProcesos.Opciones();
    CorrectorProcesos.Opciones b = op.procesos;
    if ( b != null ) {
      po.pdi = b.pdi;
      po.classpath = b.classpath;
      po.java = b.java;
      po.xmxMb = b.xmxMb;
      po.argsPadre = b.argsPadre;
      po.propiedades.putAll( b.propiedades );
      po.margenMs = b.margenMs;
      po.limiteEntregaMs = b.limiteEntregaMs;
      po.arranqueMs = b.arranqueMs;
      po.guardarAbiertos = b.guardarAbiertos;
      po.versionPlugin = b.versionPlugin;
    }
    po.workers = Math.max( 1, op.workers );
    po.captura = op.captura;
    po.idioma = op.idioma;
    po.modoConfianza = op.modoConfianza;
    po.permitidosExtra.addAll( op.permitidosExtra );
    po.conservarSandbox = op.conservarSandbox;
    return po;
  }

  /** Espera a que terminen los workers y cierra el lote (planilla, pack temporal). */
  private void vigilar() {
    try {
      if ( procesos != null ) {
        while ( !procesos.esperar( 500 ) ) {
          // el supervisor de CorrectorProcesos hace el trabajo
        }
        if ( procesos.error() != null ) {
          synchronized ( this ) {
            error = "the grading supervisor failed: " + RunMotor.primeraLinea( procesos.error() );
          }
        }
      }
    } catch ( InterruptedException e ) {
      Thread.currentThread().interrupt();
    } catch ( Throwable t ) {
      synchronized ( this ) {
        error = "the grading job failed: " + RunMotor.primeraLinea( t );
      }
    } finally {
      synchronized ( this ) {
        for ( Entrega e : entregas ) {
          if ( e.fila.resultado == null ) {
            e.fila.estadoSinResultado = PENDIENTE; // cancelado: la que estaba corriendo no termino
          }
        }
      }
      try {
        escribirPlanillas();
      } finally {
        synchronized ( this ) {
          segundos = Math.round( ( System.currentTimeMillis() - t0 ) / 100.0 ) / 10.0;
          estado = cancelado ? CANCELADO : TERMINADO;
        }
        cp.close();
        synchronized ( LOTES ) {
          EN_USO.remove( reservada );
        }
        fin.countDown();
      }
    }
  }

  // ------------------------------------------------------------------ resultados

  /** Un resultado final: avisos de identidad, devolucion .md y la fila de la planilla. En el hilo del supervisor. */
  private void terminar( Entrega e, ResultadoCorreccion r ) {
    try {
      boolean cambio = false;
      for ( Aviso a : e.avisos ) {
        boolean esta = false;
        for ( Aviso b : r.avisos ) {
          esta |= b != null && a.codigo.equals( b.codigo ) && eq( a.detalle, b.detalle );
        }
        if ( !esta ) {
          r.avisos.add( a );
          cambio = true;
        }
      }
      if ( cambio && e.pedido != null && e.pedido.resultado != null ) {
        CorrectorJson.guardar( r, new File( e.pedido.resultado ) );
      }
    } catch ( Throwable t ) {
      // el resultado del worker queda como estaba
    }
    String md = devolucion( e, r );
    synchronized ( this ) {
      e.fila.resultado = r;
      e.fila.devolucion = md;
    }
  }

  /** Escribe {@code <alumno>.md} (la devolucion del alumno, sin lo de los casos ocultos); su nombre o null. */
  private String devolucion( Entrega e, ResultadoCorreccion r ) {
    if ( !op.devoluciones ) {
      return null;
    }
    try {
      File f = new File( salida, CorrectorMain.carpeta( e.alumno ) + ".md" );
      CorrectorPlanilla.escribirAtomico( CorrectorNota.markdown( r, true ).getBytes( StandardCharsets.UTF_8 ), f );
      return f.getName();
    } catch ( Throwable t ) {
      return null;
    }
  }

  /** Una entrega que no se puede corregir (sin .ktr, zip rechazado): load_failed sin lanzar nada. */
  private void cerrarSinCorrer( Entrega e ) {
    ResultadoCorreccion r = new ResultadoCorreccion();
    r.pack = pack.id;
    r.tituloPack = pack.titulo;
    r.alumno = e.alumno;
    r.archivo = e.visible;
    r.idioma = idioma;
    r.escala = pack.escala;
    r.captura = op.captura != null ? op.captura : pack.salida.captura;
    r.estado = ResultadoCorreccion.NO_CARGO;
    r.detalle = e.problema;
    r.versionPlugin = op.procesos != null && op.procesos.versionPlugin != null ? op.procesos.versionPlugin
      : CorrectorProcesos.versionDelPlugin();
    r.corregidoEl = CorrectorMain.ahora();
    r.avisos.addAll( e.avisos );
    for ( Caso c : pack.casos ) {
      ResultadoCaso rc = new ResultadoCaso();
      rc.id = c.id;
      rc.oculto = c.oculto;
      rc.peso = c.peso;
      r.casos.add( rc );
    }
    try {
      CorrectorNota.puntuar( pack, r, null );
      CorrectorJson.guardar( r, new File( salida, CorrectorMain.carpeta( e.alumno ) + ".json" ) );
    } catch ( Throwable t ) {
      // queda en memoria
    }
    String md = devolucion( e, r );
    synchronized ( this ) {
      e.fila.resultado = r;
      e.fila.devolucion = md;
    }
  }

  // ------------------------------------------------------------------ uso

  String id() {
    return id;
  }

  File salida() {
    return salida;
  }

  Pack pack() {
    return pack;
  }

  boolean terminado() {
    return fin.getCount() == 0;
  }

  /** Espera hasta ms a que el lote termine (planilla escrita incluida). true si termino. */
  boolean esperar( long ms ) throws InterruptedException {
    return fin.await( Math.max( 0, ms ), TimeUnit.MILLISECONDS );
  }

  /** Mata los workers; queda lo ya corregido y la planilla con eso. Espera hasta 30 s a que el lote cierre. */
  void cancelar() {
    if ( terminado() ) {
      return;
    }
    cancelado = true;
    if ( procesos != null ) {
      procesos.cancelar();
    }
    try {
      esperar( 30000 );
    } catch ( InterruptedException e ) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Ajuste manual del docente (§5): {@code ajuste} con signo (null = sin ajuste) y comentario. Si el lote ya termino,
   * reescribe la planilla. false si no hay ese alumno.
   */
  boolean ajustar( String alumno, Double ajuste, String comentario ) {
    boolean hay = false;
    synchronized ( this ) {
      for ( Entrega e : entregas ) {
        if ( e.alumno.equals( alumno ) ) {
          e.fila.ajuste = ajuste;
          e.fila.comentario = comentario == null || comentario.trim().isEmpty() ? null : comentario;
          hay = true;
        }
      }
    }
    if ( hay && terminado() ) {
      escribirPlanillas();
    }
    return hay;
  }

  /** Las entregas encontradas (identidad, archivo, avisos), en el orden en que se corrigen. */
  synchronized List<Entrega> entregas() {
    return new ArrayList<Entrega>( entregas );
  }

  /** El resultado final de ese alumno, o null. */
  synchronized ResultadoCorreccion resultado( String alumno ) {
    for ( Entrega e : entregas ) {
      if ( e.alumno.equals( alumno ) ) {
        return e.fila.resultado;
      }
    }
    return null;
  }

  /** El progreso y los resultados ({@code grade_submissions} / {@code get_grading_status}). */
  synchronized Estado estado() {
    Estado s = new Estado();
    s.id = id;
    s.estado = estado;
    s.pack = pack.id;
    s.salida = salida.getAbsolutePath();
    s.total = entregas.size();
    s.inicio = inicio;
    s.segundos = segundos != null ? segundos : Math.round( ( System.currentTimeMillis() - t0 ) / 100.0 ) / 10.0;
    List<Fila> filas = new ArrayList<Fila>();
    for ( Entrega e : entregas ) {
      filas.add( e.fila );
      Renglon r = new Renglon();
      r.alumno = e.alumno;
      r.archivo = e.visible;
      r.estado = e.fila.estado();
      if ( e.fila.resultado != null ) {
        s.hechas++;
        r.motivo = e.fila.resultado.motivo;
        r.nota = e.fila.notaFinal( pack.escala );
        r.notaMotor = e.fila.ajuste == null ? null : e.fila.notaMotor();
        r.resultado = new File( salida, CorrectorMain.carpeta( e.alumno ) + ".json" ).getAbsolutePath();
        r.devolucion = e.fila.devolucion == null ? null : new File( salida, e.fila.devolucion ).getAbsolutePath();
      }
      s.resultados.add( r );
    }
    s.resumen = CorrectorPlanilla.resumir( filas, pack.escala );
    if ( !planillas.isEmpty() ) {
      s.planillas = new ArrayList<String>( planillas );
      s.planilla = planillas.get( planillas.size() - 1 );
    }
    s.avisos = avisos.isEmpty() ? null : new ArrayList<Aviso>( avisos );
    s.error = error;
    return s;
  }

  // ------------------------------------------------------------------ planilla

  /** Escribe notas.csv y/o notas.xlsx con lo que haya (nunca lanza: lo que falla queda en los avisos). */
  void escribirPlanillas() {
    CorrectorPlanilla.Hoja h = new CorrectorPlanilla.Hoja();
    synchronized ( this ) {
      h.pack = pack;
      h.idioma = idioma;
      for ( Entrega e : entregas ) {
        h.filas.add( e.fila );
      }
    }
    final Collator col = Collator.getInstance( Rotulos.locale( idioma ) );
    col.setStrength( Collator.SECONDARY );
    Collections.sort( h.filas, new Comparator<Fila>() {
      @Override
      public int compare( Fila a, Fila b ) {
        int c = col.compare( a.alumno, b.alumno );
        return c != 0 ? c : a.alumno.compareTo( b.alumno );
      }
    } );
    String formato = op.planilla == null ? CorrectorPlanilla.AMBAS : op.planilla;
    boolean xlsx = !CorrectorPlanilla.CSV.equals( formato );
    boolean csv = !CorrectorPlanilla.XLSX.equals( formato );
    List<String> escritas = new ArrayList<String>();
    List<Aviso> nuevos = new ArrayList<Aviso>();
    if ( xlsx && !CorrectorPlanilla.hayPoi() ) {
      xlsx = false;
      csv = true;
      nuevos.add( new Aviso( AVISO_SIN_XLSX, "Apache POI is not available: only notas.csv was written" ) );
    }
    if ( csv ) {
      File f = new File( salida, CorrectorPlanilla.NOMBRE + "." + CorrectorPlanilla.CSV );
      try {
        CorrectorPlanilla.escribirCsv( h, f );
        escritas.add( f.getAbsolutePath() );
      } catch ( Throwable t ) {
        nuevos.add( new Aviso( AVISO_PLANILLA, f.getName() + ": " + RunMotor.primeraLinea( t ) ) );
      }
    }
    if ( xlsx ) {
      File f = new File( salida, CorrectorPlanilla.NOMBRE + "." + CorrectorPlanilla.XLSX );
      try {
        CorrectorPlanilla.escribirXlsx( h, f );
        escritas.add( f.getAbsolutePath() );
      } catch ( Throwable t ) {
        nuevos.add( new Aviso( AVISO_PLANILLA, f.getName() + ": " + RunMotor.primeraLinea( t ) ) );
      }
    }
    synchronized ( this ) {
      planillas.clear();
      planillas.addAll( escritas );
      for ( Aviso a : nuevos ) {
        agregar( avisos, a );
      }
    }
  }

  private CorrectorPlanilla.Ajuste ajuste( String alumno ) {
    if ( ajustes == null || alumno == null ) {
      return null;
    }
    CorrectorPlanilla.Ajuste a = ajustes.get( alumno );
    if ( a != null ) {
      return a;
    }
    for ( Map.Entry<String, CorrectorPlanilla.Ajuste> e : ajustes.entrySet() ) {
      if ( e.getKey().trim().equalsIgnoreCase( alumno.trim() ) ) {
        return e.getValue();
      }
    }
    return null;
  }

  // ------------------------------------------------------------------ entregas

  /** Recorre la carpeta: una entrega por .ktr/.kjb/.zip o subcarpeta, con su identidad unica (§10). */
  private void buscarEntregas() throws IOException {
    File carpeta = op.carpeta.getAbsoluteFile();
    Map<String, String[]> alumnos = leerAlumnos( new File( carpeta, ALUMNOS_CSV ) );
    File[] hijos = carpeta.listFiles();
    List<File> l = new ArrayList<File>( Arrays.asList( hijos == null ? new File[ 0 ] : hijos ) );
    File dirZips = new File( new File( salida, CorrectorProcesos.SANDBOX ), ENTREGAS );
    List<Entrega> encontradas = new ArrayList<Entrega>();
    int nZip = 0;
    boolean mismaSalida = mismaCarpeta( carpeta, salida );
    Collections.sort( l );
    for ( File f : l ) {
      String n = f.getName();
      String nl = n.toLowerCase( Locale.ROOT );
      if ( n.startsWith( "." ) || n.startsWith( "~$" ) || IGNORADOS.contains( nl ) || mismaCarpeta( f, salida )
        || contiene( f, salida ) ) {
        continue;
      }
      if ( mismaSalida && ( PROPIAS.contains( n ) || !f.isDirectory() && !nl.endsWith( ".ktr" ) && !nl.endsWith(
        ".kjb" ) && !nl.endsWith( ".zip" ) ) ) {
        continue; // output_dir es la carpeta de entregas: lo que escribe el corrector no es una entrega
      }
      Entrega e = new Entrega();
      e.nombre = n;
      if ( f.isDirectory() ) {
        e.alumno = MOODLE.matcher( n ).replaceFirst( "" ).trim();
        e.prioridad = 1;
        elegir( e, f, n + "/", dirZips, 0 );
      } else if ( nl.endsWith( ".ktr" ) || nl.endsWith( ".kjb" ) ) {
        e.alumno = n.substring( 0, n.length() - 4 );
        e.prioridad = nl.endsWith( ".ktr" ) ? 0 : 3;
        e.archivo = f;
        e.visible = n;
      } else if ( nl.endsWith( ".zip" ) ) {
        e.alumno = n.substring( 0, n.length() - 4 );
        e.prioridad = 2;
        File dest = new File( dirZips, "z" + ( ++nZip ) );
        try {
          CorrectorPack.descomprimir( f, dest, TOPE_ZIP_BYTES, TOPE_ZIP_ENTRADAS );
          elegir( e, dest, n + "/", dirZips, 1 );
        } catch ( CorrectorPack.ZipRechazado z ) {
          e.problema = "the .zip was rejected: " + z.texto( "en" );
          e.visible = n;
        } catch ( IOException z ) {
          e.problema = "the .zip could not be read: " + RunMotor.primeraLinea( z );
          e.visible = n;
        }
      } else {
        agregar( avisos, new Aviso( AVISO_IGNORADO, "not a submission (only .ktr, .kjb, .zip or a folder): " + n ) );
        continue;
      }
      if ( e.alumno.isEmpty() ) {
        e.alumno = "entrega";
      }
      String[] a = alumno( alumnos, n );
      if ( a != null ) {
        if ( a[ 0 ] != null && !a[ 0 ].trim().isEmpty() ) {
          e.alumno = a[ 0 ].trim();
        }
        e.email = a[ 1 ] == null || a[ 1 ].trim().isEmpty() ? null : a[ 1 ].trim();
      }
      encontradas.add( e );
    }
    for ( String k : alumnos.keySet() ) {
      boolean usada = false;
      for ( Entrega e : encontradas ) {
        usada |= claveAlumno( e.nombre ).equals( k ) || claveAlumno( sinExtension( e.nombre ) ).equals( k );
      }
      if ( !usada ) {
        agregar( avisos, new Aviso( AVISO_ALUMNOS, ALUMNOS_CSV + " names a submission that is not in the folder: "
          + k ) );
      }
    }
    // identidad unica: primero los .ktr, despues las carpetas, los .zip y los .kjb; dentro, por nombre
    final Collator col = Collator.getInstance( Locale.ROOT );
    col.setStrength( Collator.SECONDARY );
    Collections.sort( encontradas, new Comparator<Entrega>() {
      @Override
      public int compare( Entrega a, Entrega b ) {
        int c = col.compare( a.alumno, b.alumno );
        if ( c == 0 ) {
          c = Integer.compare( a.prioridad, b.prioridad );
        }
        return c != 0 ? c : a.nombre.compareTo( b.nombre );
      }
    } );
    Map<String, Integer> usadas = new LinkedHashMap<String, Integer>();
    for ( Entrega e : encontradas ) {
      String base = e.alumno;
      String k = CorrectorMain.carpeta( base ).toLowerCase( Locale.ROOT );
      if ( usadas.containsKey( k ) ) {
        int n = usadas.get( k );
        String nuevo;
        do {
          n++;
          nuevo = base + "_" + n;
        } while ( usadas.containsKey( CorrectorMain.carpeta( nuevo ).toLowerCase( Locale.ROOT ) ) );
        usadas.put( k, n );
        e.alumno = nuevo;
        e.avisos.add( new Aviso( AVISO_IDENTIDAD_REPETIDA, "another submission has the identity " + CorrectorPolitica
          .q( base ) + ": this one (" + e.visible + ") is graded as " + CorrectorPolitica.q( nuevo ) ) );
        k = CorrectorMain.carpeta( nuevo ).toLowerCase( Locale.ROOT );
      }
      usadas.put( k, usadas.containsKey( k ) ? usadas.get( k ) : 1 );
    }
    entregas.addAll( encontradas );
  }

  /** Elige el .ktr de una carpeta de entrega (o de un .zip ya descomprimido). */
  private void elegir( Entrega e, File dir, String prefijo, File dirZips, int zipsAbiertos ) throws IOException {
    List<File> ktrs = new ArrayList<File>();
    List<File> zips = new ArrayList<File>();
    buscarArchivos( dir, 0, ktrs, zips );
    if ( ktrs.isEmpty() && zips.size() == 1 && zipsAbiertos < 2 ) {
      File dest = new File( dirZips, "z" + System.nanoTime() );
      File z = zips.get( 0 );
      String rel = relativa( dir, z );
      try {
        CorrectorPack.descomprimir( z, dest, TOPE_ZIP_BYTES, TOPE_ZIP_ENTRADAS );
      } catch ( CorrectorPack.ZipRechazado x ) {
        e.problema = "the .zip was rejected: " + x.texto( "en" );
        e.visible = prefijo + rel;
        return;
      }
      elegir( e, dest, prefijo + rel + "/", dirZips, zipsAbiertos + 1 );
      return;
    }
    if ( ktrs.isEmpty() ) {
      e.problema = "no .ktr file in the submission";
      e.visible = prefijo.endsWith( "/" ) ? prefijo.substring( 0, prefijo.length() - 1 ) : prefijo;
      return;
    }
    Collections.sort( ktrs, new Comparator<File>() {
      @Override
      public int compare( File a, File b ) {
        boolean ja = a.getName().toLowerCase( Locale.ROOT ).endsWith( ".kjb" );
        boolean jb = b.getName().toLowerCase( Locale.ROOT ).endsWith( ".kjb" );
        if ( ja != jb ) {
          return ja ? 1 : -1; // los .ktr primero: los jobs todavia no se corrigen
        }
        return relativa( dir, a ).compareToIgnoreCase( relativa( dir, b ) );
      }
    } );
    File elegido = null;
    String porque = null;
    if ( ktrs.size() == 1 ) {
      elegido = ktrs.get( 0 );
    }
    if ( elegido == null && pack.archivoEntrega != null && !pack.archivoEntrega.trim().isEmpty() ) {
      String q = pack.archivoEntrega.trim().replace( '\\', '/' );
      for ( File k : ktrs ) {
        if ( relativa( dir, k ).equalsIgnoreCase( q ) || k.getName().equalsIgnoreCase( q ) ) {
          elegido = k;
          porque = "submission_file";
          break;
        }
      }
    }
    if ( elegido == null ) {
      List<File> conSalida = new ArrayList<File>();
      for ( File k : ktrs ) {
        if ( tieneSalida( k ) ) {
          conSalida.add( k );
        }
      }
      if ( conSalida.size() == 1 ) {
        elegido = conSalida.get( 0 );
        porque = "the only one with an output step";
      } else {
        elegido = ( conSalida.isEmpty() ? ktrs : conSalida ).get( 0 );
        e.avisos.add( new Aviso( AVISO_ENTREGA_AMBIGUA, "the submission has " + ktrs.size() + " transformations ("
          + nombres( dir, ktrs ) + ") and none is the pack's submission_file: graded " + relativa( dir, elegido ) ) );
      }
    }
    if ( porque != null ) {
      List<File> otros = new ArrayList<File>( ktrs );
      otros.remove( elegido );
      e.avisos.add( new Aviso( AVISO_ENTREGA_ELEGIDA, "graded " + relativa( dir, elegido ) + " (" + porque
        + "); not graded: " + nombres( dir, otros ) ) );
    }
    e.archivo = elegido;
    e.visible = prefijo + relativa( dir, elegido );
  }

  /** .ktr/.kjb y .zip dentro de la carpeta, hasta {@link #MAX_PROFUNDIDAD} (sin __MACOSX ni ocultos). */
  private static void buscarArchivos( File dir, int nivel, List<File> ktrs, List<File> zips ) {
    File[] hijos = dir.listFiles();
    if ( hijos == null ) {
      return;
    }
    Arrays.sort( hijos );
    for ( File h : hijos ) {
      String n = h.getName();
      String nl = n.toLowerCase( Locale.ROOT );
      if ( n.startsWith( "." ) || n.startsWith( "~$" ) || IGNORADOS.contains( nl ) ) {
        continue;
      }
      if ( java.nio.file.Files.isSymbolicLink( h.toPath() ) ) {
        continue;
      }
      if ( h.isDirectory() ) {
        if ( nivel < MAX_PROFUNDIDAD ) {
          buscarArchivos( h, nivel + 1, ktrs, zips );
        }
      } else if ( nl.endsWith( ".ktr" ) || nl.endsWith( ".kjb" ) ) {
        ktrs.add( h );
      } else if ( nl.endsWith( ".zip" ) ) {
        zips.add( h );
      }
    }
  }

  /** El .ktr tiene un step de salida para la captura del pack (texto crudo: solo sirve para elegir entre varios). */
  private boolean tieneSalida( File ktr ) {
    if ( ktr.getName().toLowerCase( Locale.ROOT ).endsWith( ".kjb" ) || ktr.length() > TOPE_LECTURA ) {
      return false;
    }
    try {
      String x = new String( Files.readAllBytes( ktr.toPath() ), StandardCharsets.UTF_8 );
      String captura = op.captura != null ? op.captura : pack.salida.captura;
      if ( Pack.Salida.CAPTURA_QUERY.equals( captura ) ) {
        return A_BASE.matcher( x ).find();
      }
      if ( Pack.Salida.CAPTURA_STEP.equals( captura ) && pack.salida.step != null ) {
        return x.contains( "<name>" + pack.salida.step + "</name>" );
      }
      return TFO.matcher( x ).find();
    } catch ( IOException e ) {
      return false;
    }
  }

  /**
   * alumnos.csv: {@code archivo;alumno;email} (o con coma), con o sin encabezado, UTF-8 con o sin BOM. La clave es
   * el nombre de la entrada en la carpeta, con o sin extension, sin mayusculas.
   */
  private Map<String, String[]> leerAlumnos( File f ) {
    Map<String, String[]> m = new LinkedHashMap<String, String[]>();
    if ( !f.isFile() ) {
      return m;
    }
    try {
      String texto = CorrectorPlanilla.leerTexto( f ); // UTF-8 o, si lo guardo Excel, windows-1252
      String primera = texto.split( "\r?\n|\r", 2 )[ 0 ];
      String sep = primera.indexOf( ';' ) >= 0 || primera.indexOf( ',' ) < 0 ? ";" : ",";
      CorrectorTabla t = CorrectorTabla.leerTexto( texto, new CorrectorTabla.Lectura().separador( sep ).encierro(
        "\"" ).encabezado( false ) );
      for ( int i = 0; i < t.cantidadFilas(); i++ ) {
        String archivo = t.celda( i, 0 );
        if ( archivo == null || archivo.trim().isEmpty() ) {
          continue;
        }
        String a = t.columnas.size() > 1 ? t.celda( i, 1 ) : null;
        String em = t.columnas.size() > 2 ? t.celda( i, 2 ) : null;
        String k = claveAlumno( archivo );
        if ( i == 0 && ( k.equals( "archivo" ) || k.equals( "file" ) || k.equals( "arquivo" ) ) ) {
          continue; // encabezado
        }
        m.put( k, new String[] { a, em } );
        m.put( claveAlumno( sinExtension( archivo.trim() ) ), new String[] { a, em } );
      }
    } catch ( Throwable t ) {
      agregar( avisos, new Aviso( AVISO_ALUMNOS, ALUMNOS_CSV + " could not be read: " + RunMotor.primeraLinea( t ) ) );
    }
    return m;
  }

  private static String[] alumno( Map<String, String[]> m, String nombre ) {
    String[] a = m.get( claveAlumno( nombre ) );
    return a != null ? a : m.get( claveAlumno( sinExtension( nombre ) ) );
  }

  private static String claveAlumno( String s ) {
    return s == null ? "" : s.replace( "\uFEFF", "" ).trim().toLowerCase( Locale.ROOT );
  }

  private static String sinExtension( String n ) {
    String nl = n.toLowerCase( Locale.ROOT );
    for ( String ext : Arrays.asList( ".ktr", ".kjb", ".zip" ) ) {
      if ( nl.endsWith( ext ) ) {
        return n.substring( 0, n.length() - ext.length() );
      }
    }
    return n;
  }

  /** hijo esta dentro de padre (hijo puede no existir todavia). */
  private static boolean contiene( File padre, File hijo ) {
    try {
      String p = padre.getCanonicalPath() + File.separator;
      String h = hijo.getCanonicalPath();
      return h.length() > p.length() && h.regionMatches( true, 0, p, 0, p.length() );
    } catch ( IOException e ) {
      return false;
    }
  }

  private static String relativa( File dir, File f ) {
    String d = dir.getAbsolutePath().replace( '\\', '/' );
    String a = f.getAbsolutePath().replace( '\\', '/' );
    return a.regionMatches( true, 0, d + "/", 0, d.length() + 1 ) ? a.substring( d.length() + 1 ) : f.getName();
  }

  private static String nombres( File dir, List<File> l ) {
    List<String> n = new ArrayList<String>();
    for ( File f : l ) {
      n.add( relativa( dir, f ) );
    }
    return String.join( ", ", n );
  }

  private static void agregar( List<Aviso> l, Aviso a ) {
    for ( Aviso b : l ) {
      if ( b != null && a.codigo.equals( b.codigo ) && eq( a.detalle, b.detalle ) ) {
        return;
      }
    }
    l.add( a );
  }

  private static boolean eq( String a, String b ) {
    return a == null ? b == null : a.equals( b );
  }
}
