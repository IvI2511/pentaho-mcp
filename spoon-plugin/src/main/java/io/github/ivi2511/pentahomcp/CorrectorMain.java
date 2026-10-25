package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.pentaho.di.core.KettleClientEnvironment;
import org.pentaho.di.core.KettleEnvironment;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.i18n.LanguageChoice;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.CorrectorPolitica.Entrega;
import io.github.ivi2511.pentahomcp.CorrectorPolitica.Revision;
import io.github.ivi2511.pentahomcp.CorrectorSandbox.Preparacion;
import io.github.ivi2511.pentahomcp.CorrectorTextos.Mensaje;
import io.github.ivi2511.pentahomcp.corrector.modelo.Caso;
import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCaso;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.Aviso;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.PasoBloqueado;

/**
 * La JVM hija del corrector (docs/corrector-diseno.md §3 y §10). Dos usos:
 * <ul>
 * <li><b>Worker</b>: {@code java ... CorrectorMain trabajo.json}. Lo lanza {@link CorrectorProcesos} con el entorno
 * del pack (locale, zona, file.encoding, KETTLE_HOME vacio, java.io.tmpdir propio). Inicia Kettle una vez y toma
 * entregas de la cola de archivos ({@code pendientes/} → {@code tomadas/<worker>__<n>}, reservadas con un archivo que
 * se crea en exclusiva: los workers se reparten solos) hasta que no quedan. Por cada una escribe
 * {@code <alumno>.json} de forma atomica, primero el parcial ({@code running}, con las reglas, antes de ejecutar nada)
 * y despues el final, y pasa el ticket a {@code hechas/<worker>__<n>}. Sale con {@value #SALIDA_RECICLAR} cuando hay
 * que descartarlo (§7: corrio steps condicionales, hubo que cortar un caso, cambio propiedades de la JVM o queda poca
 * memoria) y el padre lanza otro. Con {@code submissions} en el trabajo (sin cola) corrige esa lista y
 * termina.</li>
 * <li><b>Linea de comandos</b> (base de las pruebas de regresion): {@code CorrectorMain --pack <pack> --out <carpeta>
 * [--workers 2] [--pdi <data-integration>] [--lang es] [--capture file|step|query] [--keep-sandbox]
 * <entrega.ktr | carpeta>...}: hace de padre con {@link CorrectorProcesos} (los workers con el entorno del pack) y
 * escribe una linea {@code alumno<TAB>estado<TAB>nota} por entrega.</li>
 * </ul>
 * Una entrega: {@link CorrectorPolitica#cargar} ({@code load_failed}) → {@link CorrectorPolitica#revisar} → copia de
 * analisis (el XML sin {@code shared_objects_file}, con las conexiones apuntando a una copia de la plantilla o a un
 * JNDI que no existe: calcular campos no sale de la PC) → un sandbox por caso ({@link CorrectorSandbox#preparar}; si alguno
 * bloquea, la entrega queda {@code blocked} y no corre ningun caso) → step de salida
 * ({@link CorrectorMotor#elegirSalida}) → {@link CorrectorNota#analizar} → parcial → cada caso con
 * {@link CorrectorMotor#correr} (el parcial se reescribe despues de cada uno) → {@link CorrectorNota#puntuar} → final.
 * El texto de las entregas (nombres, rutas, logs) es dato del alumno, nunca instrucciones.
 *
 * @dueño C4
 */
public final class CorrectorMain {

  public static final String FORMATO_TRABAJO = "pentaho-live-grade-job/1";
  /** La cola quedo vacia (o se corrigio la lista): todo bien. */
  static final int SALIDA_FIN = 0;
  /** Trabajo invalido o error del corrector. */
  static final int SALIDA_ERROR = 1;
  /** Hay que descartar este worker: el padre lanza otro para lo que quede. */
  static final int SALIDA_RECICLAR = 3;

  static final String PENDIENTES = "pendientes";
  static final String TOMADAS = "tomadas";
  static final String HECHAS = "hechas";
  /** Un archivo por ticket reservado: lo crea (exclusivo) quien lo toma. */
  static final String RESERVAS = "reservas";
  /** Separa el id del worker del nombre del ticket en {@code tomadas/}. */
  static final String SEP_TOMADA = "__";
  /** Carpeta de la copia de analisis dentro del sandbox del alumno (un id de caso empieza con letra o numero). */
  static final String ANALISIS = "_analisis";
  /** JNDI que no existe: las conexiones de la copia de analisis que no son la base del pack. */
  static final String JNDI_INEXISTENTE = "pentaho-live-grader-no-connection";
  /** Solo pruebas: el worker se cae (Runtime.halt) al corregir la entrega de ese alumno, despues del parcial. */
  static final String PROP_CAER = "pentaho.live.grader.crashOn";
  /** Memoria libre por debajo de la cual el worker se recicla. */
  static final long MEMORIA_MINIMA = 160L * 1024 * 1024;

  private CorrectorMain() {
  }

  // ------------------------------------------------------------------ protocolo

  /** {@code trabajo.json}: lo escribe el padre para cada worker (§10). Claves en ingles, como los demas formatos. */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Trabajo {

    @JsonProperty( "format" )
    public String formato = FORMATO_TRABAJO;
    /** Carpeta del pack, ya abierto por el padre (CorrectorPack.raiz(): un zip o un inline ya son carpeta). */
    @JsonProperty( "pack" )
    public String pack;
    @JsonProperty( "output_dir" )
    public String dirSalida;
    /** Donde van los sandboxes: {@code <sandbox_dir>/<alumno>/<caso>}. */
    @JsonProperty( "sandbox_dir" )
    public String dirSandbox;
    /** Si no es null, la copia reescrita del primer caso visible se guarda en {@code <open_dir>/<alumno>} (§9). */
    @JsonProperty( "open_dir" )
    public String dirAbiertos;
    /** La cola de archivos; null = corregir {@link #entregas} en orden. */
    @JsonProperty( "queue_dir" )
    public String dirCola;
    /** Id de este worker (prefijo de sus tickets en tomadas/). */
    @JsonProperty( "worker" )
    public String worker;
    @JsonProperty( "trust_mode" )
    public boolean modoConfianza;
    @JsonProperty( "extra_allowed_steps" )
    public List<String> permitidosExtra = new ArrayList<String>();
    /** file | step | query; null = la del pack. */
    @JsonProperty( "capture" )
    public String captura;
    /** en | es | pt; null = la del pack. */
    @JsonProperty( "language" )
    public String idioma;
    @JsonProperty( "keep_sandbox" )
    public boolean conservarSandbox;
    @JsonProperty( "plugin_version" )
    public String versionPlugin;
    /** Sufijo de las bases de prueba de este worker ("_w2"); null o "" = sin sufijo. */
    @JsonProperty( "db_suffix" )
    public String sufijoBase;
    @JsonProperty( "submissions" )
    public List<Pedido> entregas;
  }

  /** Una entrega para corregir: un ticket de la cola o un renglon de {@code submissions}. */
  @JsonInclude( JsonInclude.Include.NON_NULL )
  @JsonIgnoreProperties( ignoreUnknown = true )
  public static final class Pedido {

    /** Identidad (§10): nombre del archivo o de la subcarpeta, o la de alumnos.csv. */
    @JsonProperty( "student" )
    public String alumno;
    /** El .ktr a corregir. */
    @JsonProperty( "file" )
    public String archivo;
    /** Donde va el resultado ({@code <output_dir>/<alumno>.json}). */
    @JsonProperty( "result" )
    public String resultado;
    /** Lo que se muestra como {@code file} del resultado (null = el nombre del archivo). */
    @JsonProperty( "display_name" )
    public String nombreVisible;

    public Pedido() {
    }

    public Pedido( String alumno, File archivo, File resultado ) {
      this.alumno = alumno;
      this.archivo = archivo == null ? null : archivo.getAbsolutePath();
      this.resultado = resultado == null ? null : resultado.getAbsolutePath();
    }
  }

  // ------------------------------------------------------------------ main

  public static void main( String[] args ) {
    int codigo;
    try {
      codigo = correr( args );
    } catch ( Throwable t ) {
      t.printStackTrace( System.out );
      codigo = SALIDA_ERROR;
    }
    System.out.flush();
    System.err.flush();
    System.exit( codigo ); // hilos de una entrega que no paran no frenan la salida
  }

  static int correr( String[] args ) throws Exception {
    if ( args.length == 1 && !args[ 0 ].startsWith( "--" ) ) {
      return trabajar( CorrectorJson.leer( new File( args[ 0 ] ), Trabajo.class ) );
    }
    return lineaDeComandos( args );
  }

  // ------------------------------------------------------------------ worker

  /** El worker: Kettle una vez, despues la cola (o la lista) entrega por entrega. */
  static int trabajar( Trabajo tr ) throws Exception {
    if ( tr == null || !FORMATO_TRABAJO.equals( tr.formato ) || tr.pack == null || tr.dirSandbox == null ) {
      System.out.println( "[corrector] invalid job file (format " + ( tr == null ? null : tr.formato ) + ")" );
      return SALIDA_ERROR;
    }
    long t0 = System.nanoTime();
    KettleClientEnvironment.getInstance().setClient( KettleClientEnvironment.ClientType.PAN );
    // los mensajes de Kettle en ingles (logs y detalles para el docente, y los patrones de RunMotor.errorDeLog); los
    // numeros y fechas siguen con el locale del pack: LanguageChoice solo lo usan los mensajes (BaseMessages)
    LanguageChoice.getInstance().setDefaultLocale( Locale.US );
    KettleEnvironment.init( false ); // sin simple-jndi: la entrega no ve conexiones JNDI de nadie
    double inicio = Math.round( ( System.nanoTime() - t0 ) / 1e7 ) / 100.0;
    Worker w = new Worker( tr, inicio );
    System.out.println( "[corrector] worker " + tr.worker + ": Kettle ready in " + inicio + " s" );
    if ( tr.dirCola == null ) {
      for ( Pedido pd : tr.entregas == null ? new ArrayList<Pedido>() : tr.entregas ) {
        w.corregirSeguro( pd );
      }
      return SALIDA_FIN;
    }
    File cola = new File( tr.dirCola );
    while ( true ) {
      File tk = tomar( cola, tr.worker );
      if ( tk == null ) {
        return SALIDA_FIN;
      }
      Pedido pd = CorrectorJson.leer( tk, Pedido.class );
      boolean reciclar = w.corregirSeguro( pd );
      mover( tk, new File( new File( cola, HECHAS ), tk.getName() ) ); // con el id del worker: el padre sabe quien
      if ( reciclar ) {
        System.out.println( "[corrector] worker " + tr.worker + ": recycling" );
        return SALIDA_RECICLAR;
      }
    }
  }

  /**
   * Toma el primer ticket libre de {@code pendientes/}: primero lo reserva creando {@code reservas/<ticket>} (crear
   * un archivo nuevo es exclusivo en cualquier sistema; un rename no lo es en Windows, donde dos workers que abren el
   * mismo ticket a la vez lo pueden mover los dos) y despues el unico que lo reservo lo pasa a
   * {@code tomadas/<worker>__<ticket>}. null si no queda ninguno.
   */
  static File tomar( File cola, String worker ) throws IOException {
    File pend = new File( cola, PENDIENTES );
    File tom = carpetaDe( cola, TOMADAS );
    for ( int vuelta = 0; vuelta < 3; vuelta++ ) {
      String[] nombres = pend.list();
      if ( nombres == null || nombres.length == 0 ) {
        return null;
      }
      Arrays.sort( nombres );
      boolean trabados = false;
      for ( String n : nombres ) {
        if ( !n.endsWith( ".json" ) || n.startsWith( "." ) || !reservar( cola, n ) ) {
          continue;
        }
        File dst = new File( tom, worker + SEP_TOMADA + n );
        try {
          mover( new File( pend, n ), dst );
          return dst;
        } catch ( NoSuchFileException e ) {
          // lo cerro el padre (lote cancelado o sin workers)
        } catch ( FileSystemException e ) {
          trabados = true; // antivirus: se libera la reserva y otra vuelta
          new File( new File( cola, RESERVAS ), n ).delete();
        }
      }
      if ( !trabados ) {
        return null;
      }
      RunMotor.dormir( 200 );
    }
    return null;
  }

  /** Reserva el ticket para quien llama: true si nadie lo habia reservado (worker o padre). */
  static boolean reservar( File cola, String ticket ) throws IOException {
    try {
      Files.createFile( new File( carpetaDe( cola, RESERVAS ), ticket ).toPath() );
      return true;
    } catch ( java.nio.file.FileAlreadyExistsException e ) {
      return false;
    }
  }

  private static File carpetaDe( File cola, String nombre ) throws IOException {
    File d = new File( cola, nombre );
    if ( !d.isDirectory() && !d.mkdirs() && !d.isDirectory() ) {
      throw new IOException( "cannot create " + d );
    }
    return d;
  }

  /** El nombre del ticket sin el prefijo del worker. */
  static String ticket( String tomada ) {
    int i = tomada.indexOf( SEP_TOMADA );
    return i < 0 ? tomada : tomada.substring( i + SEP_TOMADA.length() );
  }

  static void mover( File de, File a ) throws IOException {
    File dir = a.getParentFile();
    if ( dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory() ) {
      throw new IOException( "cannot create " + dir );
    }
    try {
      Files.move( de.toPath(), a.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING );
    } catch ( AtomicMoveNotSupportedException e ) {
      Files.move( de.toPath(), a.toPath(), StandardCopyOption.REPLACE_EXISTING );
    }
  }

  /** Cuanto tardo cada parte de una entrega (solo para el log del worker). */
  static final class Fases {

    private long desde = System.nanoTime();
    private final StringBuilder b = new StringBuilder();

    void marcar( String fase ) {
      long ahora = System.nanoTime();
      b.append( b.length() == 0 ? "" : ", " ).append( fase ).append( ' ' ).append( Math.round( ( ahora - desde )
        / 1e7 ) / 100.0 );
      desde = ahora;
    }

    @Override
    public String toString() {
      return "[" + b + "]";
    }
  }

  /** Lo que un worker sabe de su trabajo y de si hay que reciclarlo. */
  static final class Worker {

    final Trabajo tr;
    final Pack pack;
    final File dirPack;
    final File dirSandbox;
    final File tmp;
    final double inicioKettle;
    /** Las bases de prueba de este worker (con su sufijo y las contraseñas del entorno); null si el pack no tiene. */
    CorrectorBases.Sesion bases;
    /** Las bases de prueba no son seguras o no se pudieron preparar: ninguna entrega corre. */
    Mensaje fallaBases;

    Worker( Trabajo tr, double inicioKettle ) throws IOException {
      this.tr = tr;
      this.dirPack = new File( tr.pack ).getAbsoluteFile();
      this.pack = CorrectorJson.leer( new File( dirPack, CorrectorPack.PACK_JSON ), Pack.class ).completar();
      if ( tr.captura != null && Pack.Salida.CAPTURAS.contains( tr.captura ) ) {
        pack.salida.captura = tr.captura;
      }
      this.dirSandbox = new File( tr.dirSandbox ).getAbsoluteFile();
      this.tmp = new File( System.getProperty( "java.io.tmpdir" ) ).getAbsoluteFile();
      this.inicioKettle = inicioKettle;
      if ( !pack.bases.isEmpty() ) {
        try {
          bases = new CorrectorBases.Sesion( pack, dirPack, tr.sufijoBase, CorrectorBases.clavesDelEntorno( pack ) );
          StringBuilder b = new StringBuilder();
          for ( CorrectorBases.Destino d : bases.destinos.values() ) {
            b.append( b.length() == 0 ? "" : ", " ).append( d.describir() ).append( d.clave == null ? " (no password)"
              : "" );
          }
          System.out.println( "[corrector] worker " + tr.worker + ": test databases " + b );
        } catch ( CorrectorBases.Falla f ) {
          fallaBases = f.mensaje;
          System.out.println( "[corrector] worker " + tr.worker + ": " + f.getMessage() );
        }
      }
    }

    /** Escribe el resultado sin ninguna contraseña de las bases de prueba (por si un texto la trae). */
    void guardar( ResultadoCorreccion r, File destino ) throws IOException {
      if ( bases == null || bases.claves().isEmpty() ) {
        CorrectorJson.guardar( r, destino );
        return;
      }
      com.fasterxml.jackson.databind.JsonNode n = CorrectorJson.mapper().valueToTree( r );
      tapar( n, bases.claves() );
      CorrectorJson.guardar( CorrectorJson.mapper().treeToValue( n, ResultadoCorreccion.class ), destino );
    }

    /** Corrige y escribe el resultado; un error del corrector queda como {@code crashed}. true = reciclar. */
    boolean corregirSeguro( Pedido pd ) {
      try {
        reciclar = false;
        corregir( pd );
        return reciclar;
      } catch ( Throwable t ) {
        t.printStackTrace( System.out );
        try {
          ResultadoCorreccion r = nuevo( pd );
          r.estado = ResultadoCorreccion.CAIDA;
          new Mensaje( "det.grader_error", RunMotor.primeraLinea( t ) ).en( r );
          CorrectorNota.puntuar( pack, r, null );
          guardar( r, new File( pd.resultado ) );
        } catch ( Throwable t2 ) {
          t2.printStackTrace( System.out );
        }
        return true;
      }
    }

    /** Una entrega (ver la clase); deja en {@link #reciclar} si el worker se tiene que descartar. */
    void corregir( Pedido pd ) throws Exception {
      long inicio = System.currentTimeMillis();
      File destino = new File( pd.resultado );
      ResultadoCorreccion r = nuevo( pd );
      File dirAlumno = new File( dirSandbox, carpeta( pd.alumno ) );
      CorrectorSandbox.borrar( dirAlumno ); // de una corrida anterior del mismo lote
      Map<String, String> antes = propiedades();
      Fases fases = new Fases();
      boolean corrio = false;
      boolean cortada = false;
      Revision rev = null;
      try {
        if ( fallaBases != null ) { // las bases de prueba no son seguras: no corre nada (es del pack, no del alumno)
          r.estado = ResultadoCorreccion.CAIDA;
          fallaBases.en( r );
          CorrectorNota.puntuar( pack, r, null );
          return;
        }
        Entrega e;
        try {
          e = CorrectorPolitica.cargar( new File( pd.archivo ) );
        } catch ( Throwable t ) {
          r.estado = ResultadoCorreccion.NO_CARGO;
          r.detalle = RunMotor.primeraLinea( t );
          CorrectorNota.puntuar( pack, r, CorrectorNota.analizar( pack, null, null, r ) );
          return;
        }
        fases.marcar( "load" );
        rev = CorrectorPolitica.revisar( e, pack, tr.modoConfianza, tr.permitidosExtra );
        rev.aplicar( r );
        fases.marcar( "policy" );
        TransMeta analisis = copiaDeAnalisis( e, new File( dirAlumno, ANALISIS ), pack, dirPack );
        fases.marcar( "copy" );

        // un sandbox por caso, todos antes de correr: si uno bloquea, no corre ninguno
        Map<String, Preparacion> preparados = new LinkedHashMap<String, Preparacion>();
        Preparacion bloqueo = null;
        if ( !rev.bloquea() ) {
          CorrectorSandbox.Opciones op = new CorrectorSandbox.Opciones();
          op.dirTmpJvm = tmp;
          op.bases = bases;
          for ( Caso c : pack.casos ) {
            Preparacion p = CorrectorSandbox.preparar( pack, dirPack, c, e, new File( dirAlumno, c.id ), op );
            ResultadoCaso rc = r.caso( c.id );
            p.aplicar( rc );
            p.aplicarAvisos( r );
            preparados.put( c.id, p );
            if ( p.bloqueada() ) {
              bloqueo = p;
              break;
            }
          }
        }
        if ( rev.bloquea() ) {
          for ( ResultadoCaso rc : r.casos ) {
            rev.aplicar( rc );
          }
        } else if ( bloqueo != null ) {
          bloquear( r, e, rev, bloqueo );
        }
        boolean bloqueada = ResultadoCorreccion.BLOQUEADA.equals( r.estado );
        fases.marcar( "sandboxes" );

        CorrectorMotor.Salida salida = bloqueada ? null : CorrectorMotor.elegirSalida( pack, analisis,
          pack.salida.captura );
        if ( salida != null && !salida.empatados.isEmpty() ) {
          Aviso a = new Aviso( Aviso.SALIDA_AMBIGUA, "several output steps share the same columns: "
            + CorrectorPolitica.q( salida.step ) + " was captured (also " + salida.empatados + ")" );
          a.step = salida.step;
          r.avisos.add( a );
        }
        TransMeta paraReglas = analisis != null ? analisis : CorrectorPolitica.usaBase( e ) ? null : e.tm;
        CorrectorNota.Estatico est = CorrectorNota.analizar( pack, paraReglas, salida == null ? null : salida.step,
          r, dirPack );
        fases.marcar( "rules" );
        if ( bloqueada ) {
          CorrectorNota.puntuar( pack, r, est );
          return;
        }
        guardar( r, destino ); // el parcial: si la hija muere, el padre tiene las reglas
        caerSiSePide( pd );

        CorrectorMotor.Contexto cx = new CorrectorMotor.Contexto();
        cx.pack = pack;
        cx.dirPack = dirPack;
        cx.captura = pack.salida.captura;
        cx.salida = salida;
        cx.dirTmpWorker = tmp;
        cx.conservarSalida = tr.conservarSandbox;
        cx.bases = bases;
        boolean vencio = false;
        Mensaje fallaBase = null;
        for ( Caso c : pack.casos ) {
          ResultadoCaso rc = r.caso( c.id );
          CorrectorMotor.Fin f = CorrectorMotor.correr( cx, c, preparados.get( c.id ), rc );
          corrio |= f.corrio;
          cortada |= f.cortada || f.colgada;
          vencio |= ResultadoCaso.TIEMPO_AGOTADO.equals( rc.corrida.estado );
          guardar( r, destino );
          if ( f.fallaBase != null ) {
            fallaBase = new Mensaje( "db.err.case", c.id, f.fallaBase.anidado() );
            break; // la base de prueba no responde: el resto de los casos tampoco correria
          }
        }
        fases.marcar( "cases" );
        if ( fallaBase != null ) {
          r.estado = ResultadoCorreccion.CAIDA;
          fallaBase.en( r );
          cortada = true;
        } else {
          r.estado = vencio ? ResultadoCorreccion.TIEMPO_AGOTADO : ResultadoCorreccion.CORREGIDA;
        }
        CorrectorNota.puntuar( pack, r, est );
      } catch ( Throwable t ) { // un error del corrector: crashed con lo que ya se sabia (reglas, casos corridos)
        t.printStackTrace( System.out );
        r.estado = ResultadoCorreccion.CAIDA;
        r.motivo = null;
        new Mensaje( "det.grader_error", RunMotor.primeraLinea( t ) ).en( r );
        cortada = true;
        try {
          CorrectorNota.puntuar( pack, r, null );
        } catch ( Throwable t2 ) {
          t2.printStackTrace( System.out );
        }
      } finally {
        r.tiempos.procesoSeg = Math.round( ( System.currentTimeMillis() - inicio ) / 10.0 ) / 100.0;
        r.corregidoEl = ahora();
        guardar( r, destino );
        System.out.println( "[corrector] " + pd.alumno + ": " + r.estado + ( r.motivo == null ? "" : " (" + r.motivo
          + ")" ) + " " + r.nota + " in " + r.tiempos.procesoSeg + " s " + fases );
        guardarAbierto( pd, dirAlumno, r );
        if ( !tr.conservarSandbox ) {
          try {
            CorrectorSandbox.borrar( dirAlumno );
          } catch ( IOException ex ) {
            cortada = true; // algo quedo abierto (P12): mejor un worker nuevo
          }
        }
        reciclarSi( pd, rev, corrio, cortada, antes );
      }
    }

    /** La ultima entrega pide descartar el worker (lo decide {@link #reciclarSi}). */
    private boolean reciclar;

    private void reciclarSi( Pedido pd, Revision rev, boolean corrio, boolean cortada, Map<String, String> antes ) {
      List<String> porque = new ArrayList<String>();
      if ( corrio && rev != null && rev.usaCondicionales ) {
        porque.add( "conditional steps" );
      }
      if ( cortada ) {
        porque.add( "a run had to be stopped" );
      }
      if ( corrio ) {
        List<String> cambiadas = cambios( antes, propiedades() );
        if ( !cambiadas.isEmpty() ) {
          porque.add( "JVM properties changed " + cambiadas );
        }
      }
      Runtime rt = Runtime.getRuntime();
      if ( rt.maxMemory() - ( rt.totalMemory() - rt.freeMemory() ) < MEMORIA_MINIMA ) {
        System.gc();
        if ( rt.maxMemory() - ( rt.totalMemory() - rt.freeMemory() ) < MEMORIA_MINIMA ) {
          porque.add( "low memory" );
        }
      }
      reciclar = !porque.isEmpty();
      if ( reciclar ) {
        System.out.println( "[corrector] " + pd.alumno + ": worker will be recycled: " + porque );
      }
    }

    /** La copia del primer caso visible que corrio, para «Abrir su .ktr» (§9); nunca la de un oculto. */
    private void guardarAbierto( Pedido pd, File dirAlumno, ResultadoCorreccion r ) {
      if ( tr.dirAbiertos == null ) {
        return;
      }
      for ( Caso c : pack.casos ) {
        ResultadoCaso rc = r.caso( c.id );
        if ( c.oculto || rc == null || rc.corrida == null || rc.corrida.estado == null
          || ResultadoCaso.BLOQUEADO.equals( rc.corrida.estado ) ) {
          continue;
        }
        File de = new File( dirAlumno, c.id );
        File a = new File( new File( tr.dirAbiertos ), carpeta( pd.alumno ) );
        try {
          CorrectorSandbox.borrar( a );
          if ( a.getParentFile() != null ) {
            a.getParentFile().mkdirs();
          }
          if ( tr.conservarSandbox ) {
            copiarArbol( de, a ); // keep_sandbox: el sandbox queda entero
          } else {
            Files.move( de.toPath(), a.toPath() );
          }
          repuntarBase( a, de, pd );
        } catch ( Throwable t ) {
          System.out.println( "[corrector] " + pd.alumno + ": could not keep the visible case: " + t );
        }
        return;
      }
    }

    /** La copia movida nombra su base con la ruta absoluta del sandbox: se cambia por la nueva. */
    private void repuntarBase( File a, File de, Pedido pd ) throws IOException {
      File[] ktrs = a.listFiles( ( d, n ) -> n.toLowerCase( Locale.ROOT ).endsWith( ".ktr" ) );
      if ( ktrs == null ) {
        return;
      }
      for ( File k : ktrs ) {
        String x = new String( Files.readAllBytes( k.toPath() ), StandardCharsets.UTF_8 );
        String y = x.replace( de.getAbsolutePath(), a.getAbsolutePath() );
        if ( !y.equals( x ) ) {
          Files.write( k.toPath(), y.getBytes( StandardCharsets.UTF_8 ) );
        }
      }
    }

    /** El resultado vacio de una entrega: pack, alumno, archivo, idioma, captura y un caso por caso del pack. */
    ResultadoCorreccion nuevo( Pedido pd ) {
      ResultadoCorreccion r = new ResultadoCorreccion();
      r.pack = pack.id;
      r.tituloPack = pack.titulo;
      r.alumno = pd.alumno;
      r.archivo = pd.nombreVisible != null ? pd.nombreVisible : new File( pd.archivo ).getName();
      r.idioma = tr.idioma != null ? tr.idioma : pack.idioma;
      r.escala = pack.escala;
      r.captura = pack.salida.captura;
      r.modoConfianza = tr.modoConfianza;
      if ( tr.permitidosExtra != null ) {
        r.stepsPermitidosExtra.addAll( tr.permitidosExtra );
      }
      r.versionPlugin = tr.versionPlugin;
      r.tiempos = new ResultadoCorreccion.Tiempos();
      r.tiempos.inicioKettleSeg = inicioKettle;
      for ( Caso c : pack.casos ) {
        ResultadoCaso rc = new ResultadoCaso();
        rc.id = c.id;
        rc.oculto = c.oculto;
        rc.peso = c.peso;
        r.casos.add( rc );
      }
      return r;
    }
  }

  /** Cambia las contraseñas en todos los textos del arbol JSON (solo valores de texto: los numeros no se tocan). */
  static void tapar( com.fasterxml.jackson.databind.JsonNode n, List<String> claves ) {
    if ( n == null ) {
      return;
    }
    if ( n.isObject() ) {
      com.fasterxml.jackson.databind.node.ObjectNode o = (com.fasterxml.jackson.databind.node.ObjectNode) n;
      for ( java.util.Iterator<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> it = o.fields();
            it.hasNext(); ) {
        Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> e = it.next();
        if ( e.getValue().isTextual() ) {
          String t = CorrectorBases.tapar( e.getValue().asText(), claves );
          if ( !t.equals( e.getValue().asText() ) ) {
            e.setValue( new com.fasterxml.jackson.databind.node.TextNode( t ) );
          }
        } else {
          tapar( e.getValue(), claves );
        }
      }
    } else if ( n.isArray() ) {
      com.fasterxml.jackson.databind.node.ArrayNode a = (com.fasterxml.jackson.databind.node.ArrayNode) n;
      for ( int i = 0; i < a.size(); i++ ) {
        if ( a.get( i ).isTextual() ) {
          a.set( i, new com.fasterxml.jackson.databind.node.TextNode( CorrectorBases.tapar( a.get( i ).asText(),
            claves ) ) );
        } else {
          tapar( a.get( i ), claves );
        }
      }
    }
  }

  /** Copia una carpeta (sin seguir enlaces). */
  static void copiarArbol( File de, File a ) throws IOException {
    if ( Files.isSymbolicLink( de.toPath() ) ) {
      return;
    }
    if ( de.isDirectory() ) {
      if ( !a.isDirectory() && !a.mkdirs() ) {
        throw new IOException( "cannot create " + a );
      }
      File[] hijos = de.listFiles();
      for ( File h : hijos == null ? new File[ 0 ] : hijos ) {
        copiarArbol( h, new File( a, h.getName() ) );
      }
    } else if ( de.isFile() ) {
      Files.copy( de.toPath(), a.toPath(), StandardCopyOption.REPLACE_EXISTING );
    }
  }

  /** La entrega queda bloqueada por el sandbox de un caso: el mismo motivo en todos (§6: ningun caso corrio). */
  private static void bloquear( ResultadoCorreccion r, Entrega e, Revision rev, Preparacion bloqueo ) {
    r.estado = ResultadoCorreccion.BLOQUEADA;
    r.motivo = bloqueo.motivo;
    if ( bloqueo.mensaje != null ) {
      bloqueo.mensaje.en( r );
    } else {
      r.detalle = bloqueo.detalle;
    }
    if ( bloqueo.step != null ) {
      StepMeta sm = e.tm.findStep( bloqueo.step );
      r.stepsBloqueados.add( new PasoBloqueado( bloqueo.step, sm == null ? null : sm.getStepID(),
        rev.niveles.get( bloqueo.step ), bloqueo.motivo ) );
    }
    for ( ResultadoCaso rc : r.casos ) {
      if ( rc.corrida.estado == null ) {
        rc.corrida.estado = ResultadoCaso.BLOQUEADO;
        rc.corrida.motivo = bloqueo.motivo;
        if ( bloqueo.mensaje != null ) {
          bloqueo.mensaje.en( rc.corrida );
        } else {
          rc.corrida.detalle = bloqueo.detalle;
        }
      }
    }
  }

  /**
   * La copia para el analisis estatico y para elegir la salida: el XML de la entrega sin {@code shared_objects_file},
   * en {@code _analisis/} (cargada de un archivo: las reglas del linter que leen el XML lo necesitan), con las
   * conexiones neutralizadas para que calcular campos no salga de la PC: las SQLite apuntan a una copia de la plantilla
   * del pack (sin plantilla, a una base que no existe: el linter no se conecta a esas) y las demas a un JNDI que no
   * existe; sin usuario, clave, SQL al conectar ni opciones extra. Nunca se ejecuta. null si no se pudo armar.
   */
  static TransMeta copiaDeAnalisis( Entrega e, File dir, Pack pack, File dirPack ) {
    try {
      if ( !dir.isDirectory() && !dir.mkdirs() ) {
        return null;
      }
      File f = new File( dir, e.archivo == null ? "entrega.ktr" : e.archivo.getName() );
      CorrectorSandbox.escribir( CorrectorPolitica.saneado( e.crudo ), f );
      File base = new File( dir, "base.sqlite" );
      if ( pack.salida != null && pack.salida.base != null && pack.salida.base.plantilla != null && dirPack != null ) {
        File src = new File( dirPack, pack.salida.base.plantilla );
        if ( src.isFile() ) {
          Files.copy( src.toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING );
        }
      }
      TransMeta tm = new TransMeta( f.getAbsolutePath() );
      for ( DatabaseMeta db : tm.getDatabases() ) {
        db.setHostname( "" );
        db.setUsername( "" );
        db.setPassword( "" );
        db.setConnectSQL( "" );
        for ( Object k : new ArrayList<Object>( db.getAttributes().keySet() ) ) {
          String n = String.valueOf( k );
          if ( !CorrectorSandbox.ATRIBUTOS_SQLITE.contains( n.toLowerCase( Locale.ROOT ) )
            || "SQL_CONNECT".equalsIgnoreCase( n ) ) {
            db.getAttributes().remove( k );
          }
        }
        if ( "SQLITE".equalsIgnoreCase( db.getPluginId() ) ) {
          db.setAccessType( DatabaseMeta.TYPE_ACCESS_NATIVE );
          db.setDBName( base.getAbsolutePath() );
        } else {
          db.setAccessType( DatabaseMeta.TYPE_ACCESS_JNDI );
          db.setDBName( JNDI_INEXISTENTE );
        }
      }
      return tm;
    } catch ( Throwable t ) {
      System.out.println( "[corrector] could not build the analysis copy: " + RunMotor.primeraLinea( t ) );
      return null;
    }
  }

  /** Nombre de carpeta para el alumno: sin barras ni caracteres que Windows no acepta, y sin _ ni . al principio. */
  static String carpeta( String alumno ) {
    String n = CorrectorSandbox.limpio( alumno );
    if ( n.isEmpty() ) {
      return "entrega";
    }
    return n.startsWith( "_" ) || n.startsWith( "." ) ? "e" + n : n;
  }

  private static void caerSiSePide( Pedido pd ) {
    String caer = System.getProperty( PROP_CAER );
    if ( caer != null && caer.equals( pd.alumno ) ) {
      System.out.println( "[corrector] " + pd.alumno + ": crashing on purpose (" + PROP_CAER + ")" );
      System.out.flush();
      Runtime.getRuntime().halt( 7 );
    }
  }

  /** Las propiedades de la JVM (para ver si una entrega las cambio: §7, worker reciclado). */
  static Map<String, String> propiedades() {
    Map<String, String> m = new TreeMap<String, String>();
    for ( String k : System.getProperties().stringPropertyNames() ) {
      m.put( k, System.getProperty( k ) );
    }
    return m;
  }

  /** Claves agregadas, cambiadas o quitadas. */
  static List<String> cambios( Map<String, String> antes, Map<String, String> despues ) {
    List<String> l = new ArrayList<String>();
    for ( Map.Entry<String, String> e : despues.entrySet() ) {
      String a = antes.get( e.getKey() );
      if ( a == null ? e.getValue() != null : !a.equals( e.getValue() ) ) {
        l.add( e.getKey() );
      }
    }
    for ( String k : antes.keySet() ) {
      if ( !despues.containsKey( k ) ) {
        l.add( k );
      }
    }
    return l;
  }

  static String ahora() {
    return new SimpleDateFormat( "yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT ).format( new Date() );
  }

  // ------------------------------------------------------------------ linea de comandos

  /** {@code --pack p --out dir [--workers n] [--pdi dir] [--lang es] [--capture x] [--keep-sandbox] entregas...}. */
  static int lineaDeComandos( String[] args ) throws Exception {
    File pack = null;
    File salida = null;
    CorrectorProcesos.Opciones op = new CorrectorProcesos.Opciones();
    List<File> entregas = new ArrayList<File>();
    for ( int i = 0; i < args.length; i++ ) {
      String a = args[ i ];
      String v = i + 1 < args.length ? args[ i + 1 ] : null;
      if ( "--pack".equals( a ) && v != null ) {
        pack = new File( v );
        i++;
      } else if ( "--out".equals( a ) && v != null ) {
        salida = new File( v );
        i++;
      } else if ( "--workers".equals( a ) && v != null ) {
        op.workers = Integer.parseInt( v );
        i++;
      } else if ( "--pdi".equals( a ) && v != null ) {
        op.pdi = new File( v );
        i++;
      } else if ( "--lang".equals( a ) && v != null ) {
        op.idioma = v;
        i++;
      } else if ( "--capture".equals( a ) && v != null ) {
        op.captura = v;
        i++;
      } else if ( "--keep-sandbox".equals( a ) ) {
        op.conservarSandbox = true;
      } else if ( a.startsWith( "--" ) ) {
        System.out.println( "usage: CorrectorMain trabajo.json | --pack <pack> --out <dir> [--workers n] "
          + "[--pdi <data-integration>] [--lang en|es|pt] [--capture file|step|query] [--keep-sandbox] <.ktr|dir>..." );
        return SALIDA_ERROR;
      } else {
        File f = new File( a );
        if ( f.isDirectory() ) {
          File[] ktrs = f.listFiles( ( d, n ) -> n.toLowerCase( Locale.ROOT ).endsWith( ".ktr" ) );
          if ( ktrs != null ) {
            Arrays.sort( ktrs );
            entregas.addAll( Arrays.asList( ktrs ) );
          }
        } else {
          entregas.add( f );
        }
      }
    }
    if ( pack == null || salida == null || entregas.isEmpty() ) {
      System.out.println( "missing --pack, --out or submissions" );
      return SALIDA_ERROR;
    }
    try ( CorrectorPack cp = CorrectorPack.cargar( pack, null ) ) {
      List<Pedido> pedidos = new ArrayList<Pedido>();
      Map<String, Integer> usados = new LinkedHashMap<String, Integer>();
      for ( File f : entregas ) {
        String alumno = f.getName().replaceFirst( "(?i)\\.ktr$", "" );
        Integer n = usados.get( alumno.toLowerCase( Locale.ROOT ) );
        usados.put( alumno.toLowerCase( Locale.ROOT ), n == null ? 1 : n + 1 );
        if ( n != null ) {
          alumno = alumno + "_" + ( n + 1 );
        }
        pedidos.add( new Pedido( alumno, f, new File( salida, carpeta( alumno ) + ".json" ) ) );
      }
      List<ResultadoCorreccion> rs = CorrectorProcesos.corregir( cp.raiz(), cp.pack(), pedidos, salida, op );
      for ( int i = 0; i < pedidos.size(); i++ ) {
        ResultadoCorreccion r = rs.get( i );
        System.out.println( pedidos.get( i ).alumno + "\t" + ( r == null ? "cancelled" : r.estado ) + "\t"
          + ( r == null || r.nota == null ? "" : r.nota ) );
      }
    }
    return SALIDA_FIN;
  }
}
