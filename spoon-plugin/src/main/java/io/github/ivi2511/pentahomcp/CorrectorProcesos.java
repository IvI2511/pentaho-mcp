package io.github.ivi2511.pentahomcp;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.github.ivi2511.pentahomcp.CorrectorMain.Pedido;
import io.github.ivi2511.pentahomcp.CorrectorMain.Trabajo;
import io.github.ivi2511.pentahomcp.CorrectorTextos.Mensaje;
import io.github.ivi2511.pentahomcp.corrector.modelo.Caso;
import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCaso;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion;

/**
 * El lado del padre del corrector (docs/corrector-diseno.md §7 y §10): lanza los workers ({@link CorrectorMain}) con
 * el entorno del pack, les reparte las entregas por una cola de archivos y los vigila. Nunca corre nada de una entrega
 * en esta JVM (Spoon).
 * <ul>
 * <li><b>La hija</b>: {@code java.home/bin/java} (javaw en Windows: sin consola), {@code -Xmx}, los flags que puso
 * Spoon.bat leidos de {@code RuntimeMXBean} ({@code --add-opens}, {@code -Djava.locale.providers},
 * {@code -Djava.endorsed.dirs}; nada mas: ni agentes, ni -XX, ni el file.encoding del padre), y los del pack:
 * {@code -Duser.language/-Duser.country/-Duser.timezone/-Dfile.encoding}. {@code KETTLE_HOME}, {@code user.home},
 * metastore y {@code java.io.tmpdir} propios y vacios en {@code _sandbox/_workers/<id>} (variable de entorno y -D; se
 * sacan del entorno las KETTLE_*, JAVA_TOOL_OPTIONS y _JAVA_OPTIONS, que pisarian lo anterior), y
 * {@code -DKETTLE_PLUGIN_BASE_FOLDERS=<PDI>/plugins}. Classpath {@code <plugin>;<PDI>/classes;<PDI>/lib/*;
 * <PDI>/libswt/<os>/*}: el jar del plugin y su lib/ salen de donde se cargo esta clase; {@code <PDI>} de ahi
 * ({@code plugins/pentaho-mcp/../..}) o de {@link Opciones#pdi}. El directorio de trabajo es la carpeta del worker y
 * no {@code <PDI>} (el diseño decia {@code <PDI>}): con {@code KETTLE_PLUGIN_BASE_FOLDERS} no hace falta, y asi el
 * {@code logs/pdi.log} de log4j, simple-jndi y las rutas relativas de una entrega no tocan la carpeta de PDI.</li>
 * <li><b>Protocolo</b> (§10): un ticket por entrega en {@code _sandbox/_cola/pendientes/}; cada worker lee su
 * {@code trabajo-<id>.json}, reserva un ticket (archivo creado en exclusiva en {@code reservas/}), lo pasa a
 * {@code tomadas/<worker>__<n>}, escribe {@code <alumno>.json} (primero el parcial) y lo pasa a {@code hechas/}.
 * stdout y stderr van a {@code _logs/worker-<n>.log}: un Write to log del alumno no imita el protocolo ni traba un
 * pipe.</li>
 * <li><b>Vigilante por entrega</b>: {@code timeout_s × casos + 60 s} desde que el worker la toma; si se pasa, se mata
 * el worker (con sus hijos, Java 9+) y la entrega queda {@code killed_timeout} con lo que tenia el parcial. Un worker
 * que muere a mitad de una entrega la deja {@code crashed}. Las dos se puntuan aca con
 * {@link CorrectorNota#puntuar}.</li>
 * <li><b>Pool y reciclado</b>: hasta {@link Opciones#workers} workers vivos; uno que sale con
 * {@value CorrectorMain#SALIDA_RECICLAR} (§7) se reemplaza si quedan entregas. Si dos workers seguidos mueren sin
 * tomar nada, las entregas que faltan quedan {@code crashed} (el log dice por que).</li>
 * </ul>
 * Uso: {@link #corregir(File, Pack, List, File, Opciones)} (bloquea) o {@link #iniciar} + {@link #esperar} /
 * {@link #cancelar} con un {@link Oyente} (lo usa el lote, C6). Al terminar borra {@code _sandbox/} salvo
 * {@link Opciones#conservarSandbox} (en Windows, cuando las hijas ya salieron: P12).
 *
 * @dueño C4
 */
final class CorrectorProcesos implements Closeable {

  static final String SANDBOX = "_sandbox";
  static final String COLA = "_cola";
  static final String WORKERS = "_workers";
  static final String LOGS = "_logs";
  static final String ABIERTOS = "_abiertos";
  /** Workers seguidos que mueren sin tomar ninguna entrega antes de dar el lote por fallido. */
  static final int MAX_FALLOS = 2;
  static final long MS_PASO = 100;
  static final String HILO = "pentaho-live-corrector";

  /** Flags del padre que pasan a la hija tal cual (con su valor si van en dos argumentos). */
  private static final List<String> FLAGS_CON_VALOR = Arrays.asList( "--add-opens", "--add-exports", "--add-reads",
    "--add-modules", "--patch-module" );
  private static final List<String> PREFIJOS_COPIADOS = Arrays.asList( "--add-opens=", "--add-exports=",
    "--add-reads=", "--add-modules=", "--patch-module=", "--illegal-access=", "-Djava.locale.providers=",
    "-Djava.endorsed.dirs=", "-XX:+IgnoreUnrecognizedVMOptions" ); // el de pentaho-live: Java 8 salta los --add-opens

  /** Como lanzar y vigilar. */
  static final class Opciones {

    /** data-integration; null = la de la carpeta del plugin ({@code plugins/pentaho-mcp/../..}) o el user.dir. */
    File pdi;
    /** Classpath del plugin (su jar y lib/*); null = de donde se cargo esta clase. */
    List<String> classpath;
    /** El ejecutable java; null = el de esta JVM (javaw en Windows). */
    File java;
    int workers = 2;
    int xmxMb = 1024;
    /** Los argumentos de esta JVM de donde se copian los flags; null = RuntimeMXBean.getInputArguments(). */
    List<String> argsPadre;
    /** -D extra para la hija (pruebas). */
    final Map<String, String> propiedades = new LinkedHashMap<String, String>();
    /**
     * (0.7.1) La carpeta {@code .kettle} del usuario cuyas conexiones de shared.xml y kettle.properties se copian al
     * KETTLE_HOME temporal de la hija ({@link PruebasKettleHome}); null = KETTLE_HOME vacio. Las pruebas de ETL la
     * ponen por defecto; el corrector no (las entregas corren con un KETTLE_HOME vacio).
     */
    File kettleDelUsuario;
    /** Margen del vigilante por entrega sobre timeout_s × casos. */
    long margenMs = 60000;
    /** Si es mayor que 0, reemplaza al limite por entrega (timeout_s × casos + margen). Pruebas. */
    long limiteEntregaMs;
    /** Lo que puede tardar un worker en tomar su primera entrega (arranque de Kettle) antes de matarlo. */
    long arranqueMs = 180000;
    /** Modo confianza: solo desde la UI (§7). */
    boolean modoConfianza;
    final List<String> permitidosExtra = new ArrayList<String>();
    /** file | step | query; null = la del pack. */
    String captura;
    /** en | es | pt; null = la del pack. */
    String idioma;
    boolean conservarSandbox;
    /** Guardar la copia del caso visible en {@code <salida>/_abiertos/<alumno>} (§9). */
    boolean guardarAbiertos = true;
    /** null = la del plugin. */
    String versionPlugin;
    /**
     * Contraseñas de las bases de prueba por conexion (sin mayusculas), solo en memoria: van a la hija por su entorno.
     * Las que faltan salen de lo que se escribio en esta sesion o de la variable password_variable del pack.
     */
    final Map<String, String> claves = new LinkedHashMap<String, String>();
  }

  /** Avisos del lote, en el hilo del supervisor (no bloquear). */
  interface Oyente {

    /** Un worker tomo la entrega. */
    void tomada( Pedido p, String worker );

    /** El resultado final de la entrega ya esta escrito. */
    void terminada( Pedido p, ResultadoCorreccion r );
  }

  /** Un worker lanzado (una ranura del pool puede tener varios a lo largo del lote). */
  private static final class Worker {

    final int ranura;
    String id;
    Process proceso;
    long lanzado;
    /** ticket → cuando se vio tomado. */
    final Map<String, Long> tomadas = new LinkedHashMap<String, Long>();
    boolean tomoAlguna;
    /** El ticket por el que se lo mato (killed_timeout), o null. */
    String vencido;
    /** Se lo mato por no arrancar a tiempo. */
    boolean sinArranque;
    int generacion;

    Worker( int ranura ) {
      this.ranura = ranura;
    }
  }

  private final File dirPack;
  private final Pack pack;
  private final File dirSalida;
  private final Opciones op;
  private final Oyente oyente;
  private final File dirSandbox;
  private final File dirCola;
  private final File dirLogs;
  private final File pdi;
  private final List<String> comandoBase;
  private final long limiteEntrega;
  private final Map<String, Pedido> porTicket = new LinkedHashMap<String, Pedido>();
  private final Map<String, ResultadoCorreccion> resultados = new LinkedHashMap<String, ResultadoCorreccion>();
  private final List<Worker> workers = new ArrayList<Worker>();
  private final List<String> lanzados = Collections.synchronizedList( new ArrayList<String>() );
  private Thread supervisor;
  private volatile boolean cancelado;
  private volatile boolean terminado;
  private int fallosSeguidos;
  private Throwable error;
  /** Las bases de prueba reservadas en esta JVM mientras dura el lote. */
  private final List<String> reservadas = new ArrayList<String>();
  /** Las contraseñas para el entorno de las hijas. */
  private final Map<String, String> entornoBases;

  /**
   * @param dirPack la carpeta del pack ya abierto ({@code CorrectorPack.raiz()}): un .zip o un .json inline ya son
   *   carpeta. Tiene que seguir existiendo hasta que termine el lote.
   * @param dirSalida {@code output_dir}: resultados, {@code _logs/}, {@code _abiertos/} y {@code _sandbox/}
   */
  CorrectorProcesos( File dirPack, Pack pack, File dirSalida, Opciones op, Oyente oyente ) throws CorrectorBases.Falla {
    this.dirPack = dirPack.getAbsoluteFile();
    this.dirSalida = dirSalida.getAbsoluteFile();
    this.op = op == null ? new Opciones() : op;
    this.pack = CorrectorJson.copia( pack, Pack.class ).completar(); // la captura pedida no toca la de quien llama
    if ( this.op.captura != null && Pack.Salida.CAPTURAS.contains( this.op.captura ) ) {
      this.pack.salida.captura = this.op.captura; // la que ve CorrectorNota al puntuar un worker muerto
    }
    this.oyente = oyente;
    this.dirSandbox = new File( this.dirSalida, SANDBOX );
    this.dirCola = new File( dirSandbox, COLA );
    this.dirLogs = new File( this.dirSalida, LOGS );
    this.pdi = this.op.pdi != null ? this.op.pdi.getAbsoluteFile() : pdiDetectado();
    if ( pdi == null || !esPdi( pdi ) ) {
      throw new IllegalStateException( "data-integration not found (" + pdi + "): set the PDI folder" );
    }
    this.comandoBase = comandoBase( this.op, this.pack, pdi );
    int casos = 0;
    for ( Caso c : this.pack.casos ) {
      casos += c == null ? 0 : c.vecesQueCorre();
    }
    casos = Math.max( 1, casos );
    this.limiteEntrega = this.op.limiteEntregaMs > 0 ? this.op.limiteEntregaMs
      : Math.max( 1, this.pack.politica.timeoutSeg ) * 1000L * casos + this.op.margenMs;
    int n = CorrectorBases.workersPermitidos( this.pack, Math.max( 1, this.op.workers ) );
    if ( !this.pack.bases.isEmpty() ) {
      // las bases de prueba: seguras con el sufijo de cada worker y libres en esta JVM, o no corre nada
      List<String> sufijos = CorrectorBases.sufijos( this.pack, n );
      List<CorrectorTextos.Mensaje> mal = CorrectorBases.inseguras( this.pack, sufijos,
        CorrectorBases.originalesDeSolucion( this.dirPack, this.pack ) );
      if ( !mal.isEmpty() ) {
        throw new CorrectorBases.Falla( new CorrectorTextos.Mensaje( "db.err.unsafe", CorrectorTextos.lista( mal ) ) );
      }
      List<String> uso = CorrectorBases.clavesDeUso( this.pack, sufijos );
      CorrectorBases.reservar( uso );
      reservadas.addAll( uso );
    }
    this.entornoBases = CorrectorBases.entorno( this.pack, this.op.claves );
    for ( int i = 1; i <= n; i++ ) {
      workers.add( new Worker( i ) );
    }
  }

  /** Suelta las bases de prueba reservadas (una vez). */
  private void liberarBases() {
    synchronized ( reservadas ) {
      CorrectorBases.liberar( reservadas );
      reservadas.clear();
    }
  }

  // ------------------------------------------------------------------ uso

  /**
   * Corrige las entregas y espera a que terminen todas (o a que se cancele el hilo). Devuelve los resultados en el
   * orden de {@code pedidos} (null si no termino).
   */
  static List<ResultadoCorreccion> corregir( File dirPack, Pack pack, List<Pedido> pedidos, File dirSalida,
                                             Opciones op ) throws Exception {
    try ( CorrectorProcesos cp = new CorrectorProcesos( dirPack, pack, dirSalida, op, null ) ) {
      cp.iniciar( pedidos );
      while ( !cp.esperar( 1000 ) ) {
        if ( Thread.currentThread().isInterrupted() ) {
          cp.cancelar();
          throw new InterruptedException( "grading cancelled" );
        }
      }
      return cp.resultados( pedidos );
    }
  }

  /** Escribe la cola y arranca el supervisor. Una sola vez por objeto. */
  synchronized void iniciar( List<Pedido> pedidos ) throws IOException {
    if ( supervisor != null ) {
      throw new IllegalStateException( "already started" );
    }
    for ( File d : Arrays.asList( dirSalida, dirLogs, new File( dirCola, CorrectorMain.PENDIENTES ),
      new File( dirCola, CorrectorMain.TOMADAS ), new File( dirCola, CorrectorMain.HECHAS ) ) ) {
      if ( !d.isDirectory() && !d.mkdirs() && !d.isDirectory() ) {
        throw new IOException( "cannot create " + d );
      }
    }
    int n = 0;
    for ( Pedido p : pedidos ) {
      if ( p.alumno == null || p.alumno.trim().isEmpty() || p.archivo == null ) {
        throw new IllegalArgumentException( "each submission needs a student and a file" );
      }
      p.archivo = new File( p.archivo ).getAbsolutePath();
      if ( p.resultado == null ) {
        p.resultado = new File( dirSalida, CorrectorMain.carpeta( p.alumno ) + ".json" ).getAbsolutePath();
      }
      String ticket = String.format( Locale.ROOT, "%05d.json", ++n );
      porTicket.put( ticket, p );
      CorrectorJson.guardar( p, new File( new File( dirCola, CorrectorMain.PENDIENTES ), ticket ) );
    }
    supervisor = new Thread( this::supervisar, HILO + "-supervisor" );
    supervisor.setDaemon( true );
    supervisor.start();
  }

  /** Espera hasta ms a que termine el lote. true si termino. */
  boolean esperar( long ms ) throws InterruptedException {
    Thread s = supervisor;
    if ( s == null ) {
      return true;
    }
    s.join( Math.max( 1, ms ) );
    return !s.isAlive();
  }

  /** Mata los workers y deja lo ya corregido; las entregas a medias no quedan con resultado. */
  void cancelar() {
    cancelado = true;
    Thread s = supervisor;
    if ( s != null ) {
      try {
        s.join( 30000 );
      } catch ( InterruptedException e ) {
        Thread.currentThread().interrupt();
      }
    }
  }

  boolean terminado() {
    return terminado;
  }

  boolean cancelado() {
    return cancelado;
  }

  /** Cuantas entregas ya tienen resultado final. */
  synchronized int hechas() {
    return resultados.size();
  }

  int total() {
    return porTicket.size();
  }

  /** Los resultados finales en el orden de los pedidos (null si no termino). */
  synchronized List<ResultadoCorreccion> resultados( List<Pedido> pedidos ) {
    List<ResultadoCorreccion> l = new ArrayList<ResultadoCorreccion>();
    for ( Pedido p : pedidos ) {
      ResultadoCorreccion r = null;
      for ( Map.Entry<String, Pedido> e : porTicket.entrySet() ) {
        if ( e.getValue() == p ) {
          r = resultados.get( e.getKey() );
        }
      }
      l.add( r );
    }
    return l;
  }

  /** Los ids de los workers lanzados, en orden ("w1_1", "w2_1", "w1_2"...). */
  List<String> workersLanzados() {
    synchronized ( lanzados ) {
      return new ArrayList<String>( lanzados );
    }
  }

  /** Un error del supervisor (no de una entrega), o null. */
  Throwable error() {
    return error;
  }

  File dirSandbox() {
    return dirSandbox;
  }

  /** La carpeta de los logs de los workers. */
  File dirLogs() {
    return dirLogs;
  }

  @Override
  public void close() {
    if ( supervisor != null && supervisor.isAlive() ) {
      cancelar();
    }
    if ( supervisor == null || !supervisor.isAlive() ) {
      liberarBases();
    }
  }

  // ------------------------------------------------------------------ supervisor

  private void supervisar() {
    try {
      while ( true ) {
        if ( cancelado ) {
          matarTodos();
          sinParciales();
          break;
        }
        if ( paso() ) {
          break;
        }
        RunMotor.dormir( MS_PASO );
      }
    } catch ( Throwable t ) {
      error = t;
      matarTodos();
    } finally {
      terminado = true;
      liberarBases();
      if ( !op.conservarSandbox ) {
        try {
          CorrectorSandbox.borrar( dirSandbox );
        } catch ( Throwable ignored ) {
          // algo trabado: queda la carpeta
        }
      }
    }
  }

  /** Una vuelta del supervisor. true cuando no queda nada por hacer. */
  private boolean paso() throws IOException {
    long ahora = System.currentTimeMillis();
    // tickets tomados
    String[] tomadas = new File( dirCola, CorrectorMain.TOMADAS ).list();
    for ( String n : tomadas == null ? new String[ 0 ] : tomadas ) {
      int i = n.indexOf( CorrectorMain.SEP_TOMADA );
      if ( i <= 0 ) {
        continue;
      }
      Worker w = porId( n.substring( 0, i ) );
      String ticket = CorrectorMain.ticket( n );
      if ( w != null && !w.tomadas.containsKey( ticket ) && !resultadoListo( ticket ) ) {
        w.tomadas.put( ticket, ahora );
        w.tomoAlguna = true;
        fallosSeguidos = 0;
        if ( oyente != null && porTicket.containsKey( ticket ) ) {
          avisarTomada( porTicket.get( ticket ), w.id );
        }
      }
    }
    // tickets hechos (hechas/<worker>__<ticket>: el worker los pasa ahi despues de escribir el resultado final)
    String[] hechas = new File( dirCola, CorrectorMain.HECHAS ).list();
    for ( String n : hechas == null ? new String[ 0 ] : hechas ) {
      int i = n.indexOf( CorrectorMain.SEP_TOMADA );
      Worker w = i > 0 ? porId( n.substring( 0, i ) ) : null;
      if ( w != null && !w.tomoAlguna ) {
        w.tomoAlguna = true;
        fallosSeguidos = 0;
      }
      String ticket = CorrectorMain.ticket( n );
      if ( porTicket.containsKey( ticket ) && !resultadoListo( ticket ) ) {
        terminar( ticket, leer( porTicket.get( ticket ) ) );
      }
    }
    // workers
    for ( Worker w : workers ) {
      if ( w.proceso == null ) {
        continue;
      }
      if ( w.proceso.isAlive() ) {
        for ( Map.Entry<String, Long> e : w.tomadas.entrySet() ) {
          if ( !resultadoListo( e.getKey() ) && ahora - e.getValue() > limiteEntrega && w.vencido == null ) {
            w.vencido = e.getKey();
            log( w, "submission " + e.getKey() + " went over " + ( limiteEntrega / 1000 ) + " s: killing the worker" );
            matar( w.proceso );
          }
        }
        if ( !w.tomoAlguna && w.vencido == null && !w.sinArranque && ahora - w.lanzado > op.arranqueMs ) {
          w.sinArranque = true;
          log( w, "did not take any submission in " + ( op.arranqueMs / 1000 ) + " s: killing the worker" );
          matar( w.proceso );
        }
        continue;
      }
      murio( w );
    }
    // demasiados arranques fallidos: lo que falta queda crashed
    List<String> pendientes = pendientes();
    if ( fallosSeguidos >= MAX_FALLOS && !pendientes.isEmpty() ) {
      for ( String ticket : pendientes ) {
        File f = new File( new File( dirCola, CorrectorMain.PENDIENTES ), ticket );
        if ( CorrectorMain.reservar( dirCola, ticket ) && f.delete() ) {
          cerrar( ticket, null, null, ResultadoCorreccion.CAIDA, new Mensaje( "det.worker.start", fallosSeguidos,
            LOGS + "/" ) );
        }
      }
      pendientes = pendientes();
    }
    // lanzar
    int vivosLibres = 0;
    for ( Worker w : workers ) {
      if ( w.proceso != null && w.proceso.isAlive() && w.tomadas.isEmpty() ) {
        vivosLibres++;
      }
    }
    for ( Worker w : workers ) {
      if ( w.proceso == null && pendientes.size() > vivosLibres && fallosSeguidos < MAX_FALLOS ) {
        lanzar( w );
        vivosLibres++;
      }
    }
    // fin
    boolean vivos = false;
    for ( Worker w : workers ) {
      vivos |= w.proceso != null;
    }
    return !vivos && pendientes.isEmpty() && resultados.size() >= porTicket.size();
  }

  /** El worker salio: lo que tenia tomado queda killed_timeout (si lo matamos por eso) o crashed. */
  private void murio( Worker w ) throws IOException {
    int codigo;
    try {
      codigo = w.proceso.exitValue();
    } catch ( IllegalThreadStateException e ) {
      return;
    }
    log( w, "exited with code " + codigo );
    // lo que tenia tomado: lo que se vio y lo que quedo en tomadas/ (pudo tomarlo y morir entre dos vueltas)
    Map<String, Long> suyas = new LinkedHashMap<String, Long>( w.tomadas );
    String[] tomadas = new File( dirCola, CorrectorMain.TOMADAS ).list();
    for ( String n : tomadas == null ? new String[ 0 ] : tomadas ) {
      if ( n.startsWith( w.id + CorrectorMain.SEP_TOMADA ) && !suyas.containsKey( CorrectorMain.ticket( n ) ) ) {
        suyas.put( CorrectorMain.ticket( n ), null );
      }
    }
    for ( Map.Entry<String, Long> e : suyas.entrySet() ) {
      String ticket = e.getKey();
      if ( resultadoListo( ticket ) || !porTicket.containsKey( ticket ) ) {
        continue;
      }
      if ( new File( new File( dirCola, CorrectorMain.HECHAS ), w.id + CorrectorMain.SEP_TOMADA + ticket ).isFile() ) {
        terminar( ticket, leer( porTicket.get( ticket ) ) ); // termino justo antes de salir
      } else if ( ticket.equals( w.vencido ) ) {
        cerrar( ticket, w.id, e.getValue(), ResultadoCorreccion.MATADA, new Mensaje( "det.worker.killed",
          limiteEntrega / 1000 ) );
      } else {
        cerrar( ticket, w.id, e.getValue(), ResultadoCorreccion.CAIDA, new Mensaje( "det.worker.stopped", codigo,
          LOGS + "/worker-" + w.ranura + ".log" ) );
      }
      w.tomoAlguna = true;
    }
    if ( !w.tomoAlguna && !pendientes().isEmpty() ) {
      fallosSeguidos++; // salio (o se lo mato) sin tomar nada y quedan entregas: no arranca
    }
    w.proceso = null;
    w.tomadas.clear();
  }

  /**
   * Pone el resultado final de un ticket que el worker no termino (sobre su parcial, si hay) y pasa el ticket a
   * hechas/. {@code desde}: cuando se vio tomado (para {@code process_s}), o null.
   */
  private void cerrar( String ticket, String worker, Long desde, String estado, Mensaje detalle ) throws IOException {
    Pedido p = porTicket.get( ticket );
    File destino = new File( p.resultado );
    ResultadoCorreccion r = null;
    if ( destino.isFile() ) {
      try {
        r = CorrectorJson.leer( destino, ResultadoCorreccion.class ).completar();
      } catch ( Exception e ) {
        r = null; // un parcial roto: se arma de nuevo
      }
    }
    if ( r == null ) {
      r = vacio( p );
    }
    r.estado = estado;
    r.motivo = null;
    detalle.en( r );
    for ( Caso c : pack.casos ) {
      if ( r.caso( c.id ) == null ) {
        ResultadoCaso rc = new ResultadoCaso();
        rc.id = c.id;
        rc.oculto = c.oculto;
        rc.peso = c.peso;
        r.casos.add( rc );
      }
    }
    if ( r.corregidoEl == null ) {
      r.corregidoEl = CorrectorMain.ahora();
    }
    if ( desde != null ) {
      if ( r.tiempos == null ) {
        r.tiempos = new ResultadoCorreccion.Tiempos();
      }
      r.tiempos.procesoSeg = Math.round( ( System.currentTimeMillis() - desde ) / 10.0 ) / 100.0;
    }
    CorrectorNota.puntuar( pack, r, null );
    CorrectorJson.guardar( r, destino );
    String nombre = worker == null ? ticket : worker + CorrectorMain.SEP_TOMADA + ticket;
    File hecha = new File( new File( dirCola, CorrectorMain.HECHAS ), nombre );
    File tomada = new File( new File( dirCola, CorrectorMain.TOMADAS ), nombre );
    if ( worker != null && tomada.isFile() ) {
      CorrectorMain.mover( tomada, hecha );
    } else {
      CorrectorJson.guardar( p, hecha );
    }
    terminar( ticket, r );
  }

  private ResultadoCorreccion vacio( Pedido p ) {
    ResultadoCorreccion r = new ResultadoCorreccion();
    r.pack = pack.id;
    r.tituloPack = pack.titulo;
    r.alumno = p.alumno;
    r.archivo = p.nombreVisible != null ? p.nombreVisible : new File( p.archivo ).getName();
    r.idioma = op.idioma != null ? op.idioma : pack.idioma;
    r.captura = op.captura != null ? op.captura : pack.salida.captura;
    r.modoConfianza = op.modoConfianza;
    r.stepsPermitidosExtra.addAll( op.permitidosExtra );
    r.versionPlugin = version();
    return r;
  }

  private ResultadoCorreccion leer( Pedido p ) {
    try {
      return CorrectorJson.leer( new File( p.resultado ), ResultadoCorreccion.class ).completar();
    } catch ( Exception e ) {
      ResultadoCorreccion r = vacio( p );
      r.estado = ResultadoCorreccion.CAIDA;
      new Mensaje( "det.result_unreadable", RunMotor.primeraLinea( e ) ).en( r );
      return r;
    }
  }

  private void terminar( String ticket, ResultadoCorreccion r ) {
    synchronized ( this ) {
      resultados.put( ticket, r );
    }
    for ( Worker w : workers ) {
      w.tomadas.remove( ticket );
    }
    if ( oyente != null ) {
      try {
        oyente.terminada( porTicket.get( ticket ), r );
      } catch ( Throwable ignored ) {
        // el oyente no frena el lote
      }
    }
  }

  private void avisarTomada( Pedido p, String worker ) {
    try {
      oyente.tomada( p, worker );
    } catch ( Throwable ignored ) {
      // el oyente no frena el lote
    }
  }

  private synchronized boolean resultadoListo( String ticket ) {
    return resultados.containsKey( ticket );
  }

  private List<String> pendientes() {
    String[] n = new File( dirCola, CorrectorMain.PENDIENTES ).list();
    List<String> l = new ArrayList<String>();
    for ( String x : n == null ? new String[ 0 ] : n ) {
      if ( x.endsWith( ".json" ) && !x.startsWith( "." ) ) {
        l.add( x );
      }
    }
    Collections.sort( l );
    return l;
  }

  private Worker porId( String id ) {
    for ( Worker w : workers ) {
      if ( id.equals( w.id ) ) {
        return w;
      }
    }
    return null;
  }

  // ------------------------------------------------------------------ lanzar y matar

  private void lanzar( Worker w ) throws IOException {
    w.generacion++;
    w.id = "w" + w.ranura + "_" + w.generacion;
    w.tomadas.clear();
    w.tomoAlguna = false;
    w.vencido = null;
    w.sinArranque = false;
    File dw = new File( new File( dirSandbox, WORKERS ), w.id );
    CorrectorSandbox.borrar( dw ); // lanzarHija crea kettle-home, home, metastore y tmp vacios
    Trabajo tr = new Trabajo();
    tr.pack = dirPack.getAbsolutePath();
    tr.dirSalida = dirSalida.getAbsolutePath();
    tr.dirSandbox = dirSandbox.getAbsolutePath();
    tr.dirAbiertos = op.guardarAbiertos ? new File( dirSalida, ABIERTOS ).getAbsolutePath() : null;
    tr.dirCola = dirCola.getAbsolutePath();
    tr.worker = w.id;
    tr.modoConfianza = op.modoConfianza;
    tr.permitidosExtra.addAll( op.permitidosExtra );
    tr.captura = op.captura;
    tr.idioma = op.idioma;
    tr.conservarSandbox = op.conservarSandbox;
    tr.versionPlugin = version();
    tr.sufijoBase = CorrectorBases.sufijoWorker( pack, w.ranura, workers.size() );
    File trabajo = new File( dirCola, "trabajo-" + w.id + ".json" );
    CorrectorJson.guardar( tr, trabajo );

    File log = new File( dirLogs, "worker-" + w.ranura + ".log" );
    w.proceso = lanzarHija( comandoBase, dw, CorrectorMain.class.getName(), Collections.singletonList(
      trabajo.getAbsolutePath() ), log, w.id, entornoBases, op.kettleDelUsuario );
    w.lanzado = System.currentTimeMillis();
    lanzados.add( w.id );
  }

  /**
   * Lanza una JVM hija del corrector (§7): {@code comandoBase} + {@code java.io.tmpdir}, {@code KETTLE_HOME},
   * metastore y {@code user.home} propios en {@code dirHija} (que ya tiene que tener {@code kettle-home/.kettle},
   * {@code home}, {@code metastore} y {@code tmp}; es tambien su directorio de trabajo), sin las KETTLE_*,
   * PENTAHO_METASTORE*, JAVA_TOOL_OPTIONS, _JAVA_OPTIONS ni JDK_JAVA_OPTIONS del entorno, con stdout y stderr al final
   * de {@code log} y sin stdin. La usan los workers y CorrectorCrearPack (C6).
   */
  static Process lanzarHija( List<String> comandoBase, File dirHija, String clase, List<String> args, File log,
                             String id ) throws IOException {
    return lanzarHija( comandoBase, dirHija, clase, args, log, id, null );
  }

  /**
   * Igual, con variables de entorno de mas para la hija (las contraseñas de las bases de prueba: asi no pasan por
   * ningun archivo ni por la linea de comandos, que queda en el log).
   */
  static Process lanzarHija( List<String> comandoBase, File dirHija, String clase, List<String> args, File log,
                             String id, Map<String, String> entornoExtra ) throws IOException {
    return lanzarHija( comandoBase, dirHija, clase, args, log, id, entornoExtra, null );
  }

  /**
   * Igual, y con {@code kettleDelUsuario} (una carpeta {@code .kettle}) copia sus conexiones de shared.xml y su
   * kettle.properties al KETTLE_HOME de la hija antes de lanzarla (0.7.1, las pruebas de ETL).
   */
  static Process lanzarHija( List<String> comandoBase, File dirHija, String clase, List<String> args, File log,
                             String id, Map<String, String> entornoExtra, File kettleDelUsuario ) throws IOException {
    File kettleHome = new File( dirHija, "kettle-home" );
    File home = new File( dirHija, "home" );
    File metastore = new File( dirHija, "metastore" );
    File tmp = new File( dirHija, "tmp" );
    for ( File d : Arrays.asList( new File( kettleHome, ".kettle" ), home, metastore, tmp ) ) {
      if ( !d.isDirectory() && !d.mkdirs() && !d.isDirectory() ) {
        throw new IOException( "cannot create " + d );
      }
    }
    List<String> copiado = kettleDelUsuario == null ? Collections.<String>emptyList() : PruebasKettleHome.copiar(
      kettleDelUsuario, new File( kettleHome, ".kettle" ) );
    List<String> cmd = new ArrayList<String>( comandoBase );
    cmd.add( "-Djava.io.tmpdir=" + tmp.getAbsolutePath() );
    cmd.add( "-DKETTLE_HOME=" + kettleHome.getAbsolutePath() );
    cmd.add( "-DPENTAHO_METASTORE_FOLDER=" + metastore.getAbsolutePath() );
    cmd.add( "-Duser.home=" + home.getAbsolutePath() );
    cmd.add( clase );
    cmd.addAll( args );
    ProcessBuilder pb = new ProcessBuilder( cmd );
    pb.directory( dirHija );
    Map<String, String> env = pb.environment();
    for ( Iterator<String> it = env.keySet().iterator(); it.hasNext(); ) {
      String k = it.next().toUpperCase( Locale.ROOT );
      if ( k.startsWith( "KETTLE_" ) || k.startsWith( "PENTAHO_METASTORE" ) || k.equals( "JAVA_TOOL_OPTIONS" )
        || k.equals( "_JAVA_OPTIONS" ) || k.equals( "JDK_JAVA_OPTIONS" ) ) {
        it.remove();
      }
    }
    env.put( "KETTLE_HOME", kettleHome.getAbsolutePath() );
    env.put( "PENTAHO_METASTORE_FOLDER", metastore.getAbsolutePath() );
    for ( Iterator<String> it = env.keySet().iterator(); it.hasNext(); ) {
      if ( it.next().toUpperCase( Locale.ROOT ).startsWith( CorrectorBases.VARIABLE_ENTORNO ) ) {
        it.remove(); // nada de una corrida anterior: solo las de esta
      }
    }
    if ( entornoExtra != null ) {
      env.putAll( entornoExtra );
    }
    File dirLog = log.getAbsoluteFile().getParentFile();
    if ( dirLog != null && !dirLog.isDirectory() && !dirLog.mkdirs() && !dirLog.isDirectory() ) {
      throw new IOException( "cannot create " + dirLog );
    }
    escribirLinea( log, "=== " + new SimpleDateFormat( "yyyy-MM-dd HH:mm:ss", Locale.ROOT ).format( new Date() ) + " "
      + id + ": " + String.join( " ", cmd ) );
    if ( !copiado.isEmpty() ) {
      escribirLinea( log, "=== " + id + ": user KETTLE_HOME copied: " + String.join( ", ", copiado ) );
    }
    pb.redirectErrorStream( true );
    pb.redirectOutput( ProcessBuilder.Redirect.appendTo( log ) );
    Process p = pb.start();
    try {
      p.getOutputStream().close(); // la entrega no lee nada del padre
    } catch ( IOException ignored ) {
      // ya cerrado
    }
    return p;
  }

  /** Cancelado: las entregas a medias no dejan su parcial (queda solo lo ya corregido). */
  private void sinParciales() {
    for ( Map.Entry<String, Pedido> e : porTicket.entrySet() ) {
      File f = new File( e.getValue().resultado );
      if ( resultadoListo( e.getKey() ) || !f.isFile() ) {
        continue;
      }
      try {
        if ( !CorrectorJson.leer( f, ResultadoCorreccion.class ).terminada() && !f.delete() ) {
          f.deleteOnExit();
        }
      } catch ( Exception ignored ) {
        // un parcial ilegible: queda
      }
    }
  }

  private void matarTodos() {
    for ( Worker w : workers ) {
      if ( w.proceso != null ) {
        matar( w.proceso );
        w.proceso = null;
      }
    }
  }

  /** Mata el worker y sus hijos (Java 9+, ProcessHandle por reflexion: el plugin compila para Java 8). */
  static void matar( Process p ) {
    try {
      Class<?> ph = Class.forName( "java.lang.ProcessHandle" );
      Object h = Process.class.getMethod( "toHandle" ).invoke( p );
      java.util.stream.Stream<?> hijos = (java.util.stream.Stream<?>) ph.getMethod( "descendants" ).invoke( h );
      for ( Object x : hijos.toArray() ) {
        try {
          ph.getMethod( "destroyForcibly" ).invoke( x );
        } catch ( Throwable ignored ) {
          // sigue con los demas
        }
      }
    } catch ( Throwable ignored ) {
      // Java 8: solo el worker
    }
    p.destroyForcibly();
    try {
      p.waitFor( 10, TimeUnit.SECONDS );
    } catch ( InterruptedException e ) {
      Thread.currentThread().interrupt();
    }
  }

  private void log( Worker w, String texto ) {
    escribirLinea( new File( dirLogs, "worker-" + w.ranura + ".log" ), "=== " + w.id + ": " + texto );
  }

  private static void escribirLinea( File f, String linea ) {
    try ( OutputStream os = new FileOutputStream( f, true ) ) {
      os.write( ( linea + System.lineSeparator() ).getBytes( StandardCharsets.UTF_8 ) );
    } catch ( IOException ignored ) {
      // el log no frena el lote
    }
  }

  private String version() {
    return op.versionPlugin != null ? op.versionPlugin : versionDelPlugin();
  }

  /**
   * pentaho-mcp-version.txt del jar (lo escribe build.ps1), leido aca y no con McpLifecycleListener.VERSION: la linea
   * de comandos no inicia Kettle y esa clase arma un LogChannel al cargarse. null si no esta (clases sin build.ps1).
   */
  static String versionDelPlugin() {
    try ( java.io.InputStream in = CorrectorProcesos.class.getResourceAsStream( "pentaho-mcp-version.txt" ) ) {
      if ( in == null ) {
        return null;
      }
      java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
      byte[] buf = new byte[ 256 ];
      for ( int n; ( n = in.read( buf ) ) > 0; ) {
        b.write( buf, 0, n );
      }
      String v = new String( b.toByteArray(), StandardCharsets.UTF_8 ).replace( "﻿", "" ).trim();
      return v.isEmpty() ? null : v;
    } catch ( IOException e ) {
      return null;
    }
  }

  // ------------------------------------------------------------------ comando

  /**
   * El comando de la hija sin lo propio de cada worker (tmpdir, KETTLE_HOME, user.home, metastore, la clase y el
   * trabajo): java, -Xmx, los flags copiados del padre, los del pack y el classpath.
   */
  static List<String> comandoBase( Opciones op, Pack pack, File pdi ) {
    List<String> c = new ArrayList<String>();
    c.add( ( op.java != null ? op.java : java() ).getAbsolutePath() );
    c.add( "-Xmx" + Math.max( 256, op.xmxMb ) + "m" );
    c.addAll( flagsDe( op.argsPadre != null ? op.argsPadre
      : ManagementFactory.getRuntimeMXBean().getInputArguments() ) );
    c.addAll( flagsDelPack( pack ) );
    c.add( "-Djava.awt.headless=true" );
    c.add( "-DKETTLE_PLUGIN_BASE_FOLDERS=" + new File( pdi, "plugins" ).getAbsolutePath() );
    for ( Map.Entry<String, String> e : op.propiedades.entrySet() ) {
      c.add( "-D" + e.getKey() + "=" + e.getValue() );
    }
    c.add( "-cp" );
    c.add( String.join( File.pathSeparator, classpath( pdi, op.classpath != null ? op.classpath
      : classpathDelPlugin() ) ) );
    return c;
  }

  /**
   * Los flags del padre que la hija necesita (los que puso Spoon.bat): {@code --add-opens} y compania (con su valor
   * aparte o con =), {@code --illegal-access}, {@code -Djava.locale.providers} y {@code -Djava.endorsed.dirs}. Nada
   * mas: ni -Xmx ni -XX (la hija pone los suyos), ni agentes, ni file.encoding, user.*, KETTLE_HOME o tmpdir.
   */
  static List<String> flagsDe( List<String> args ) {
    List<String> l = new ArrayList<String>();
    for ( int i = 0; args != null && i < args.size(); i++ ) {
      String a = args.get( i ) == null ? "" : args.get( i ).trim();
      if ( FLAGS_CON_VALOR.contains( a ) ) {
        if ( i + 1 < args.size() ) {
          l.add( a );
          l.add( args.get( ++i ).trim() );
        }
        continue;
      }
      for ( String p : PREFIJOS_COPIADOS ) {
        if ( a.startsWith( p ) ) {
          l.add( a );
          break;
        }
      }
    }
    return l;
  }

  /** {@code -Dfile.encoding}, {@code -Duser.language/country/variant} y {@code -Duser.timezone} del pack. */
  static List<String> flagsDelPack( Pack pack ) {
    List<String> l = new ArrayList<String>();
    String enc = pack.politica.encoding == null || pack.politica.encoding.trim().isEmpty() ? "UTF-8"
      : pack.politica.encoding.trim();
    l.add( "-Dfile.encoding=" + enc );
    l.add( "-Dsun.stdout.encoding=UTF-8" );
    l.add( "-Dsun.stderr.encoding=UTF-8" );
    l.add( "-Dstdout.encoding=UTF-8" );
    l.add( "-Dstderr.encoding=UTF-8" );
    Locale loc = pack.politica.localeJava();
    l.add( "-Duser.language=" + loc.getLanguage() );
    l.add( "-Duser.country=" + loc.getCountry() );
    if ( !loc.getVariant().isEmpty() ) {
      l.add( "-Duser.variant=" + loc.getVariant() );
    }
    String zona = pack.politica.zonaHoraria == null || pack.politica.zonaHoraria.trim().isEmpty() ? "UTC"
      : pack.politica.zonaHoraria.trim();
    l.add( "-Duser.timezone=" + zona );
    return l;
  }

  /** El classpath completo: el del plugin y despues {@code <PDI>/classes}, {@code lib/*} y {@code libswt/<os>/*}. */
  static List<String> classpath( File pdi, List<String> plugin ) {
    List<String> cp = new ArrayList<String>( plugin );
    cp.add( new File( pdi, "classes" ).getAbsolutePath() );
    cp.add( new File( pdi, "lib" ).getAbsolutePath() + File.separator + "*" );
    File swt = new File( new File( pdi, "libswt" ), carpetaSwt() );
    if ( swt.isDirectory() ) {
      cp.add( swt.getAbsolutePath() + File.separator + "*" );
    }
    return cp;
  }

  static String carpetaSwt() {
    String os = System.getProperty( "os.name", "" ).toLowerCase( Locale.ROOT );
    return os.contains( "win" ) ? "win64" : os.contains( "mac" ) ? "osx64" : "linux";
  }

  /** El jar del plugin y su lib/* (o la carpeta de clases, en las pruebas): de donde se cargo esta clase. */
  static List<String> classpathDelPlugin() {
    List<String> l = new ArrayList<String>();
    File f = codigo();
    if ( f == null ) {
      throw new IllegalStateException( "cannot find where the plugin classes are" );
    }
    l.add( f.getAbsolutePath() );
    File lib = new File( f.getParentFile(), "lib" );
    if ( f.isFile() && lib.isDirectory() ) {
      l.add( lib.getAbsolutePath() + File.separator + "*" );
    }
    return l;
  }

  /** {@code plugins/pentaho-mcp/../..} si esta clase viene del jar instalado; si no, el user.dir si es un PDI. */
  static File pdiDetectado() {
    File f = codigo();
    if ( f != null && f.isFile() ) {
      File dir = f.getParentFile();
      File cand = dir == null || dir.getParentFile() == null ? null : dir.getParentFile().getParentFile();
      if ( cand != null && esPdi( cand ) ) {
        return cand;
      }
    }
    File cwd = new File( System.getProperty( "user.dir", "." ) ).getAbsoluteFile();
    return esPdi( cwd ) ? cwd : null;
  }

  /** Una carpeta data-integration: tiene lib/kettle-engine-*.jar. */
  static boolean esPdi( File d ) {
    File[] l = new File( d, "lib" ).listFiles( ( x, n ) -> n.startsWith( "kettle-engine-" ) && n.endsWith( ".jar" ) );
    return l != null && l.length > 0;
  }

  /** El jar o la carpeta de clases de donde se cargo el plugin. */
  private static File codigo() {
    try {
      URL u = CorrectorProcesos.class.getProtectionDomain().getCodeSource().getLocation();
      return new File( u.toURI() );
    } catch ( Exception e ) {
      return null;
    }
  }

  /** El java de esta JVM: javaw en Windows (lanzado desde Spoon, java.exe abriria una consola). */
  static File java() {
    File bin = new File( System.getProperty( "java.home" ), "bin" );
    File javaw = new File( bin, "javaw.exe" );
    if ( javaw.isFile() ) {
      return javaw;
    }
    File exe = new File( bin, "java.exe" );
    return exe.isFile() ? exe : new File( bin, "java" );
  }
}
