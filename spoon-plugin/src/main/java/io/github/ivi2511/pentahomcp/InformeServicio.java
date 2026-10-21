package io.github.ivi2511.pentahomcp;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import com.fasterxml.jackson.databind.node.ObjectNode;

import org.eclipse.swt.widgets.Display;
import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.logging.LogChannel;
import org.pentaho.di.core.logging.LogChannelInterface;
import org.pentaho.di.core.variables.VariableSpace;
import org.pentaho.di.core.variables.Variables;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.repository.Repository;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.ui.spoon.Spoon;

import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Combinar;
import io.github.ivi2511.pentahomcp.informes.Disponibilidad;
import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Pedido;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Recolector;
import io.github.ivi2511.pentahomcp.informes.Renderizador;
import io.github.ivi2511.pentahomcp.informes.Resultado;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.Imagen;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.ModeloJson;
import io.github.ivi2511.pentahomcp.informes.modelo.Narrativa;
import io.github.ivi2511.pentahomcp.informes.modelo.Parrafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.navegador.Chromium;
import io.github.ivi2511.pentahomcp.informes.navegador.PrerenderMermaid;
import io.github.ivi2511.pentahomcp.informes.navegador.RenderPdf;
import io.github.ivi2511.pentahomcp.informes.render.RenderDocx;
import io.github.ivi2511.pentahomcp.informes.render.RenderHtml;
import io.github.ivi2511.pentahomcp.informes.render.RenderImagenes;
import io.github.ivi2511.pentahomcp.informes.render.RenderMarkdown;
import io.github.ivi2511.pentahomcp.informes.render.RenderPptx;
import io.github.ivi2511.pentahomcp.informes.render.RenderXlsx;
import io.github.ivi2511.pentahomcp.informes.tema.Ajustes;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Motor de informes, todo estatico (diseno §1.1, §1.4 y §7.3). Lo llaman el panel (informes.ui) y
 * las tools MCP (ReportTools). Registra los trece recolectores y los renderizadores.
 * <p>
 * Hilos:
 * <ul>
 * <li>UI: {@link #capturar} (y el pintado de {@link #capturarArchivo}).</li>
 * <li>Pool {@code pentaho-live-informes} (2 hilos daemon, context classloader = el del plugin):
 * {@link #armar} y lo que se mande con {@link #enPool}. Nunca hacer get() en la UI.</li>
 * <li>Cada recolector corre en un hilo aparte con su tope, asi una seccion colgada no traba el
 * informe: queda en "error" con el motivo y el resto sale igual.</li>
 * <li>{@link #exportar} y {@link #vistaPrevia} corren en el hilo que las llama (un worker) y fijan
 * el context classloader mientras duran (POI lo necesita).</li>
 * </ul>
 *
 * @dueño WP2
 */
public final class InformeServicio {

  /** Area de Rotulos del motor y de las secciones estructurales. */
  static final String AREA = "estructura";

  /** Hilos del pool de informes (diseno §1.4). */
  static final int HILOS = 2;
  /** Tope de una seccion, aunque el pedido tenga mas tiempo. */
  static final long TOPE_SECCION_MS = 150000;
  /** Margen sobre el tope: los recolectores ya cortan solos con ctx.msRestantes(). */
  static final long MARGEN_MS = 5000;
  /** Las secciones baratas corren aunque el plazo del pedido se haya agotado, con este tope. */
  static final long TOPE_BARATAS_MS = 30000;
  /** Tope de cada texto de la narrativa (diseno §1.5). */
  static final int MAX_NARRATIVA = 2000;
  /** Espera maxima de la UI al pintar el lienzo de un archivo. */
  static final long MS_UI = 20000;
  /** Las carpetas de vista previa mas viejas que esto se borran. */
  static final long VIDA_TEMPORALES_MS = 24L * 60 * 60 * 1000;

  /** Claves de Informe.rotulos (las lee el renderizador). */
  static final List<String> CLAVES_ROTULOS = Collections.unmodifiableList( Arrays.asList( "page", "of", "contents",
    "generated_with", "ai_label", "generated" ) );

  private static final ExecutorService POOL = new ThreadPoolExecutor( HILOS, HILOS, 60L, TimeUnit.SECONDS,
    new LinkedBlockingQueue<Runnable>(), fabrica( "pentaho-live-informes-" ) );

  /** Un hilo por seccion en curso (cached: crece solo si una seccion vencida sigue corriendo). */
  private static final ExecutorService SECCIONES = Executors.newCachedThreadPool( fabrica(
    "pentaho-live-informes-seccion-" ) );

  /** Los recolectores, en el orden del informe (SeccionId.ORDEN). */
  private static final List<Recolector> RECOLECTORES = Collections.unmodifiableList( Arrays.<Recolector>asList(
    new SecPortada(), new SecResumen(), new SecFlujo( false ), new SecFlujo( true ), new SecLinaje(),
    new SecDiccionario(), new SecDestino(), new SecPerfil(), new SecMetricas(), new SecValidacion(), new SecCalidad(),
    new SecConexiones(), new SecNarrativa() ) );

  /** Secciones marcadas por defecto (diseno §2.3); metrics ademas pide una corrida. */
  private static final Set<String> POR_DEFECTO = Collections.unmodifiableSet( new LinkedHashSet<String>( Arrays.asList(
    SeccionId.PORTADA, SeccionId.RESUMEN, SeccionId.FLUJO, SeccionId.FLUJO_JOB, SeccionId.LINAJE,
    SeccionId.DICCIONARIO, SeccionId.DESTINO, SeccionId.METRICAS, SeccionId.VALIDACION, SeccionId.CALIDAD,
    SeccionId.CONEXIONES ) ) );

  /** docId de cada informe armado, para la carpeta de la vista previa. Por identidad (Informe no redefine equals). */
  private static final Map<Informe, String> DOC_IDS = Collections.synchronizedMap( new WeakHashMap<Informe, String>() );

  /** Base de los temporales (%TEMP%); las pruebas la cambian. */
  static volatile File temporales;

  private static final AtomicBoolean LIMPIADO = new AtomicBoolean();
  private static volatile LogChannelInterface log;

  private InformeServicio() {
  }

  // ================================================================ captura

  /** Captura el documento abierto en Spoon sin activar su pestana. (UI) */
  public static Captura capturar( Spoon sp, AbstractMeta vivo ) {
    return CapturaUi.tomar( sp, vivo );
  }

  /**
   * Carga un .ktr/.kjb sin abrir pestana: lee el archivo en el hilo que llama (un worker) y pinta el
   * lienzo en la UI de {@code d}. Con {@code d == null} la captura sale sin lienzo.
   */
  public static Captura capturarArchivo( String ruta, final Display d ) throws Exception {
    if ( ruta == null || ruta.trim().isEmpty() ) {
      throw new IllegalArgumentException( "No file" );
    }
    File f = new File( ruta.trim() ).getAbsoluteFile();
    if ( !f.isFile() ) {
      throw new FileNotFoundException( f.getPath() );
    }
    String n = f.getName().toLowerCase();
    Spoon sp = UiThread.spoon();
    Repository repo = null;
    if ( sp != null ) {
      try {
        repo = sp.getRepository();
      } catch ( Throwable ignored ) {
        // sin repositorio
      }
    }
    AbstractMeta m;
    if ( n.endsWith( ".kjb" ) ) {
      m = new JobMeta( f.getPath(), repo );
    } else if ( n.endsWith( ".ktr" ) ) {
      m = new TransMeta( f.getPath() );
    } else {
      throw new IllegalArgumentException( "Not a .ktr or .kjb file: " + f.getName() );
    }
    if ( sp != null ) {
      try {
        m.setRepository( repo );
        m.setMetaStore( sp.getMetaStore() );
      } catch ( Throwable ignored ) {
        // sin metastore: los steps que lo necesitan avisan solos
      }
    }
    final Captura c = new Captura();
    c.capturado = new Date();
    c.copia = m;
    c.docId = "file:" + f.getPath();
    c.nombre = Docs.nombre( m );
    c.archivo = f.getPath();
    c.tipo = m instanceof JobMeta ? Captura.JOB : Captura.TRANSFORMACION;
    c.sinGuardar = false;
    if ( d != null && !d.isDisposed() ) {
      enUi( d, new Runnable() {
        @Override
        public void run() {
          CapturaUi.pintarSuelto( d, c );
        }
      } );
    }
    return c;
  }

  /** Corre r en la UI de d y espera hasta MS_UI (sin syncExec: si la UI esta trabada no se cuelga). */
  private static void enUi( Display d, final Runnable r ) throws Exception {
    if ( d.getThread() == Thread.currentThread() ) {
      r.run();
      return;
    }
    final CompletableFuture<Void> f = new CompletableFuture<Void>();
    d.asyncExec( new Runnable() {
      @Override
      public void run() {
        try {
          r.run();
          f.complete( null );
        } catch ( Throwable t ) {
          f.completeExceptionally( t );
        }
      }
    } );
    try {
      f.get( MS_UI, TimeUnit.MILLISECONDS );
    } catch ( TimeoutException e ) {
      throw new TimeoutException( "Spoon's UI did not respond within " + MS_UI + " ms" );
    }
  }

  // ================================================================ disponibilidad

  /** Los recolectores registrados, en el orden del informe. */
  public static List<Recolector> recolectores() {
    return RECOLECTORES;
  }

  /** El recolector de esa seccion, o null. */
  public static Recolector recolector( String id ) {
    for ( Recolector r : RECOLECTORES ) {
      if ( r.id().equals( id ) ) {
        return r;
      }
    }
    return null;
  }

  /** Que secciones aplican y cuanto cuestan. Barato: se puede llamar en la UI. */
  public static List<Disponibilidad> disponibles( Captura c, String idioma ) {
    String i = Rotulos.normalizar( idioma );
    List<Disponibilidad> out = new ArrayList<Disponibilidad>();
    for ( Recolector r : RECOLECTORES ) {
      String motivo = noAplica( r, c, i );
      boolean aplica = motivo == null;
      out.add( new Disponibilidad( r.id(), aplica, motivo, aplica && porDefecto( r.id(), c ), costo( r.id(), c ) ) );
    }
    return out;
  }

  /** Marcada por defecto segun §2.3 (sin mirar si aplica). metrics, solo si hubo corrida. */
  static boolean porDefecto( String id, Captura c ) {
    if ( !POR_DEFECTO.contains( id ) ) {
      return false;
    }
    if ( SeccionId.METRICAS.equals( id ) ) {
      return SecMetricas.hayCorrida( c );
    }
    return true;
  }

  /** Costo de cada seccion (Disponibilidad.COSTO_*). */
  static String costo( String id, Captura c ) {
    if ( SeccionId.FLUJO.equals( id ) || SeccionId.FLUJO_JOB.equals( id ) ) {
      return Disponibilidad.COSTO_UI;
    }
    if ( SeccionId.LINAJE.equals( id ) || SeccionId.DICCIONARIO.equals( id ) || SeccionId.CALIDAD.equals( id ) ) {
      // quality: las reglas que miran los campos los calculan (Pedido.calidadProfunda ademas lee las fuentes)
      return Disponibilidad.COSTO_CAMPOS;
    }
    if ( SeccionId.PERFIL.equals( id ) ) {
      return Disponibilidad.COSTO_EJECUTA;
    }
    if ( SeccionId.VALIDACION.equals( id ) ) {
      // checkSteps calcula campos y, si hay conexiones, puede conectarse
      boolean conBase = c != null && c.copia != null && c.copia.getDatabases() != null
        && !c.copia.getDatabases().isEmpty();
      return conBase ? Disponibilidad.COSTO_BASE : Disponibilidad.COSTO_CAMPOS;
    }
    // cover, summary, connections, narrative, target_model (sin consultar la base), metrics (ultima corrida)
    return Disponibilidad.COSTO_NADA;
  }

  /** Secciones que no consultan campos ni la base ni ejecutan: corren aunque se agote el plazo. */
  static boolean barata( String id ) {
    return SeccionId.PORTADA.equals( id ) || SeccionId.RESUMEN.equals( id ) || SeccionId.FLUJO.equals( id )
      || SeccionId.FLUJO_JOB.equals( id ) || SeccionId.CONEXIONES.equals( id ) || SeccionId.NARRATIVA.equals( id );
  }

  private static String noAplica( Recolector r, Captura c, String idioma ) {
    try {
      return r.noAplica( c, idioma );
    } catch ( Throwable e ) {
      return Rotulos.de( AREA, idioma ).f( "motivo.error", RunMotor.mensaje( e ) );
    }
  }

  /**
   * Las secciones que se arman, en el orden del informe. Con {@code p.secciones} explicitas van todas
   * las validas (las que no aplican salen "omitidas" con su motivo); sin ellas, las marcadas por
   * defecto ({@code tema.secciones} o §2.3) que aplican, mas profile si hay steps a perfilar, quality si se
   * pide la revision profunda y narrative si hay conclusiones.
   */
  static List<String> seleccion( Captura c, Pedido p, Tema tema, List<String> avisos ) {
    String idioma = Rotulos.normalizar( p.idioma );
    Rotulos r = Rotulos.de( AREA, idioma );
    Set<String> elegidas = new LinkedHashSet<String>();
    if ( p.secciones != null ) {
      for ( String id : p.secciones ) {
        String x = id == null ? "" : id.trim();
        if ( SeccionId.valido( x ) ) {
          elegidas.add( x );
        } else if ( avisos != null ) {
          avisos.add( r.f( "aviso.desconocida", x ) );
        }
      }
    } else {
      List<String> base = tema != null && tema.secciones != null ? tema.secciones : null;
      for ( Recolector rec : RECOLECTORES ) {
        String id = rec.id();
        boolean marcada = base == null ? POR_DEFECTO.contains( id ) : base.contains( id );
        if ( SeccionId.METRICAS.equals( id ) ) {
          boolean hubo = SecMetricas.hayCorrida( c );
          marcada = marcada && !Pedido.METRICAS_NINGUNA.equals( p.metricas )
            && ( hubo || Pedido.METRICAS_COPIA.equals( p.metricas ) );
        } else if ( SeccionId.PERFIL.equals( id ) ) {
          marcada = !p.pasosPerfil().isEmpty();
        } else if ( SeccionId.CALIDAD.equals( id ) ) {
          marcada = marcada || p.calidadProfunda; // la revision profunda es de la seccion de calidad
        } else if ( SeccionId.NARRATIVA.equals( id ) ) {
          marcada = p.narrativa != null && Txt.vacio( p.narrativa.conclusiones ) != null;
        }
        if ( marcada && noAplica( rec, c, idioma ) == null ) {
          elegidas.add( id );
        }
      }
    }
    List<String> out = new ArrayList<String>();
    for ( String id : SeccionId.ORDEN ) {
      if ( elegidas.contains( id ) ) {
        out.add( id );
      }
    }
    return out;
  }

  // ================================================================ armar

  /** Corre los recolectores en el pool "pentaho-live-informes" con el tema guardado. Nunca hacer get() en la UI. */
  public static Future<Informe> armar( Captura c, Pedido p, Progreso pr ) {
    return armar( c, p, null, pr );
  }

  /**
   * Igual, con el tema que se esta editando en el panel (la portada usa autor, institucion y logo).
   * Con {@code t == null} usa Ajustes.cargar(). El tema se copia: el que llama puede seguir editandolo.
   */
  public static Future<Informe> armar( final Captura c, final Pedido p, Tema t, final Progreso pr ) {
    if ( c == null ) {
      throw new IllegalArgumentException( "No capture" );
    }
    final Tema tema = t == null ? temaGuardado() : t.copia();
    return POOL.submit( new Callable<Informe>() {
      @Override
      public Informe call() throws Exception {
        return conLoader( new Callable<Informe>() {
          @Override
          public Informe call() throws Exception {
            return armarAhora( c, p, tema, pr );
          }
        } );
      }
    } );
  }

  /** Corre una tarea en el pool de informes (exportar, vista previa...). */
  public static <T> Future<T> enPool( final Callable<T> tarea ) {
    return POOL.submit( new Callable<T>() {
      @Override
      public T call() throws Exception {
        return conLoader( tarea );
      }
    } );
  }

  private static Tema temaGuardado() {
    try {
      Tema t = Ajustes.cargar();
      return t == null ? new Tema() : t;
    } catch ( Throwable e ) {
      return new Tema();
    }
  }

  /**
   * Arma el informe en el hilo que llama (un worker). Las secciones van en el orden de SeccionId.ORDEN;
   * summary se arma al final porque junta los resumenes de las demas. quality sabe si validation ya salio en
   * el informe (va antes): asi no repite sus hallazgos. Si se cancela, lo que falta queda "omitido" con el
   * motivo y el informe se devuelve igual.
   */
  static Informe armarAhora( Captura c, Pedido pedido, Tema tema, Progreso pr ) {
    final Pedido p = pedido == null ? new Pedido() : pedido;
    final Progreso prog = pr == null ? Progreso.NULO : pr;
    final String idioma = Rotulos.normalizar( p.idioma );
    final Rotulos r = Rotulos.de( AREA, idioma );
    synchronized ( c ) { // dos pedidos sobre la misma captura no tocan la copia a la vez
      final ContextoKettle ctx = new ContextoKettle( c, p, tema, prog );
      if ( c.lienzoCrudo != null ) {
        ctx.progreso().paso( r.s( "progreso.lienzo" ), 0, 0 );
        codificarLienzo( c );
      }
      Informe inf = new Informe();
      cabecera( inf, c, p, idioma, r );
      List<String> ids = seleccion( c, p, tema, inf.avisos );
      int total = ids.size();
      int hecho = 0;
      Map<String, Seccion> hechas = new LinkedHashMap<String, Seccion>();
      for ( String id : ids ) {
        if ( SeccionId.RESUMEN.equals( id ) ) {
          continue;
        }
        final Recolector rec = recolector( id );
        ctx.progreso().paso( r.f( "progreso.seccion", SeccionId.titulo( id, idioma ) ), hecho, total );
        final boolean conValidacion = SeccionId.CALIDAD.equals( id ) && salio( hechas.get( SeccionId.VALIDACION ) );
        hechas.put( id, correr( id, rec, new Callable<Seccion>() {
          @Override
          public Seccion call() throws Exception {
            if ( rec instanceof SecCalidad ) {
              return ( (SecCalidad) rec ).armar( ctx, conValidacion );
            }
            return rec.armar( ctx );
          }
        }, ctx, r ) );
        hecho++;
      }
      if ( ids.contains( SeccionId.RESUMEN ) ) {
        final List<Seccion> otras = new ArrayList<Seccion>( hechas.values() );
        final SecResumen res = (SecResumen) recolector( SeccionId.RESUMEN );
        ctx.progreso().paso( r.f( "progreso.seccion", SeccionId.titulo( SeccionId.RESUMEN, idioma ) ), hecho, total );
        hechas.put( SeccionId.RESUMEN, correr( SeccionId.RESUMEN, res, new Callable<Seccion>() {
          @Override
          public Seccion call() throws Exception {
            return res.armar( ctx, otras );
          }
        }, ctx, r ) );
        hecho++;
      }
      for ( String id : ids ) {
        Seccion s = hechas.get( id );
        if ( s != null ) {
          narrativaDeSeccion( s, p.narrativa );
          inf.secciones.add( s );
        }
      }
      if ( ctx.cancelado() ) {
        inf.avisos.add( r.s( "aviso.cancelado" ) );
      }
      for ( String a : ctx.avisos() ) {
        if ( !inf.avisos.contains( a ) ) {
          inf.avisos.add( a );
        }
      }
      DOC_IDS.put( inf, c.docId == null ? "" : c.docId );
      ctx.progreso().paso( r.s( "progreso.listo" ), total, total );
      return inf;
    }
  }

  /** La seccion esta en el informe con su contenido (ok o parcial), no omitida ni en error. */
  static boolean salio( Seccion s ) {
    return s != null && ( Seccion.OK.equals( s.estado ) || Seccion.PARCIAL.equals( s.estado ) );
  }

  private static void cabecera( Informe inf, Captura c, Pedido p, String idioma, Rotulos r ) {
    boolean job = c.esJob() || c.copia instanceof JobMeta;
    String nombre = c.nombre != null ? c.nombre : ( c.copia != null ? Docs.nombre( c.copia ) : "" );
    inf.titulo = Txt.vacio( p.titulo ) != null ? p.titulo.trim() : nombre;
    inf.subtitulo = r.f( job ? "subtitulo.job" : "subtitulo.trans", nombre );
    inf.tipoDoc = job ? Informe.JOB : Informe.TRANSFORMACION;
    inf.archivo = c.archivo;
    inf.nombreDoc = nombre;
    inf.idioma = idioma;
    inf.generado = ZonedDateTime.now().truncatedTo( ChronoUnit.SECONDS ).format( DateTimeFormatter.ISO_OFFSET_DATE_TIME );
    Rotulos render = Rotulos.de( "render", idioma );
    for ( String k : CLAVES_ROTULOS ) {
      inf.rotulos.put( k, render.tiene( k ) ? render.s( k ) : r.s( "rotulo." + k ) );
    }
    if ( c.sinGuardar ) {
      inf.avisos.add( r.s( "aviso.sin_guardar" ) );
    }
    if ( c.lienzoFallo != null ) {
      inf.avisos.add( r.f( "aviso.sin_lienzo", c.lienzoFallo ) );
    }
  }

  /**
   * Corre una seccion en su propio hilo, con tope. Nunca lanza: lo que falla queda como seccion
   * "error" (o "omitida" si se cancelo o se agoto el plazo) con el motivo traducido.
   */
  static Seccion correr( String id, Recolector rec, Callable<Seccion> tarea, ContextoKettle ctx, Rotulos r ) {
    String titulo = SeccionId.titulo( id, ctx.idioma() );
    if ( rec == null ) {
      return error( id, titulo, r.f( "motivo.desconocida", id ) );
    }
    if ( ctx.cancelado() ) {
      return Seccion.omitida( id, titulo, r.s( "motivo.cancelado" ) );
    }
    String na = noAplica( rec, ctx.captura(), ctx.idioma() );
    if ( na != null ) {
      return Seccion.omitida( id, titulo, na );
    }
    boolean barata = barata( id );
    long restante = ctx.msRestantes();
    if ( !barata && restante <= 0 ) {
      return Seccion.omitida( id, titulo, r.f( "motivo.tiempo", ctx.pedido().timeoutMs / 1000 ) );
    }
    long tope = Math.min( TOPE_SECCION_MS, barata ? Math.max( restante, TOPE_BARATAS_MS ) : restante ) + MARGEN_MS;
    final ClassLoader cl = InformeServicio.class.getClassLoader();
    final Callable<Seccion> t = tarea;
    Future<Seccion> f = SECCIONES.submit( new Callable<Seccion>() {
      @Override
      public Seccion call() throws Exception {
        Thread.currentThread().setContextClassLoader( cl );
        return t.call();
      }
    } );
    try {
      Seccion s = f.get( tope, TimeUnit.MILLISECONDS );
      if ( s == null ) {
        return error( id, titulo, r.f( "motivo.error", "null" ) );
      }
      if ( s.id == null ) {
        s.id = id;
      }
      if ( Txt.vacio( s.titulo ) == null ) {
        s.titulo = titulo;
      }
      if ( s.estado == null ) {
        s.estado = Seccion.OK;
      }
      if ( s.bloques == null ) {
        s.bloques = new ArrayList<Bloque>();
      }
      return s;
    } catch ( TimeoutException e ) {
      f.cancel( true );
      return error( id, titulo, r.f( "motivo.no_termino", ( tope + 999 ) / 1000 ) );
    } catch ( InterruptedException e ) {
      f.cancel( true );
      ctx.cancelar();
      Thread.currentThread().interrupt();
      return Seccion.omitida( id, titulo, r.s( "motivo.cancelado" ) );
    } catch ( ExecutionException e ) {
      Throwable causa = e.getCause() == null ? e : e.getCause();
      log().logError( "Informes: la seccion '" + id + "' fallo", causa );
      return error( id, titulo, r.f( "motivo.error", RunMotor.mensaje( causa ) ) );
    } catch ( Throwable e ) {
      return error( id, titulo, r.f( "motivo.error", RunMotor.mensaje( e ) ) );
    }
  }

  private static Seccion error( String id, String titulo, String motivo ) {
    Seccion s = new Seccion( id, titulo );
    s.estado = Seccion.ERROR;
    s.motivo = motivo;
    return s;
  }

  /** narrativa.porSeccion[id] va al principio de esa seccion (diseno §1.5). */
  private static void narrativaDeSeccion( Seccion s, Narrativa n ) {
    if ( n == null || n.porSeccion == null || SeccionId.NARRATIVA.equals( s.id ) || Seccion.OMITIDA.equals( s.estado ) ) {
      return;
    }
    String texto = Txt.recortar( n.porSeccion.get( s.id ), MAX_NARRATIVA );
    if ( texto == null ) {
      return;
    }
    Parrafo p = new Parrafo( texto );
    p.id = s.id + "-narrativa";
    p.ia = Narrativa.IA.equals( n.origen );
    s.bloques.add( 0, p );
  }

  /**
   * Codifica el lienzo crudo de la captura (ReportImagen.png, recortado) y vacia lienzoCrudo. Fuera
   * de la UI: ImageData e ImageLoader son datos puros.
   */
  static void codificarLienzo( Captura c ) {
    synchronized ( c ) {
      if ( c.lienzoCrudo == null ) {
        return;
      }
      float escala = c.lienzo != null && c.lienzo.escala > 0 ? c.lienzo.escala : CapturaUi.ESCALA;
      try {
        byte[] png = ReportImagen.png( c.lienzoCrudo );
        int[] wh = medidas( png );
        Imagen img = new Imagen( Imagen.PNG, png, wh[ 0 ], wh[ 1 ], escala );
        c.lienzo = img;
      } catch ( Throwable e ) {
        log().logError( "Informes: no se pudo codificar el lienzo", e );
        c.lienzo = null;
      }
      c.lienzoCrudo = null;
    }
  }

  /** Ancho y alto de un PNG (cabecera IHDR) o de un JPG (ImageIO). {0, 0} si no se puede leer. */
  static int[] medidas( byte[] img ) {
    if ( img == null ) {
      return new int[] { 0, 0 };
    }
    if ( img.length >= 24 && ( img[ 0 ] & 0xFF ) == 0x89 && img[ 1 ] == 'P' && img[ 2 ] == 'N' && img[ 3 ] == 'G' ) {
      return new int[] { entero( img, 16 ), entero( img, 20 ) };
    }
    try {
      java.awt.image.BufferedImage b = ImageIO.read( new ByteArrayInputStream( img ) );
      if ( b != null ) {
        return new int[] { b.getWidth(), b.getHeight() };
      }
    } catch ( Throwable ignored ) {
      // formato raro
    }
    return new int[] { 0, 0 };
  }

  private static int entero( byte[] b, int i ) {
    return ( ( b[ i ] & 0xFF ) << 24 ) | ( ( b[ i + 1 ] & 0xFF ) << 16 ) | ( ( b[ i + 2 ] & 0xFF ) << 8 )
      | ( b[ i + 3 ] & 0xFF );
  }

  // ================================================================ exportar

  /**
   * Escribe el informe en un formato (worker). destino es el archivo principal; los extras van al
   * lado. HTML_ESTATICO y PPTX piden el pre-render de Mermaid si hay Edge/Chrome; si no lo hay o
   * falla, el renderizador usa la tabla alternativa y el aviso va en el Resultado.
   */
  public static Resultado exportar( final Informe inf, Tema t, final Formato f, File destino, final Progreso pr )
    throws Exception {
    if ( inf == null || f == null || destino == null ) {
      throw new IllegalArgumentException( "Missing report, format or destination" );
    }
    final Tema tema = t == null ? new Tema() : t;
    final File d = destino.getAbsoluteFile();
    File carpeta = d.getParentFile();
    if ( carpeta != null && !carpeta.isDirectory() && !carpeta.mkdirs() ) {
      throw new java.io.IOException( "Could not create the folder " + carpeta );
    }
    return conLoader( new Callable<Resultado>() {
      @Override
      public Resultado call() throws Exception {
        List<String> avisos = new ArrayList<String>();
        Renderizador rz = renderizador( f, inf, tema, avisos, pr );
        Resultado res = rz.escribir( inf, tema, d, pr == null ? Progreso.NULO : pr );
        if ( res == null ) {
          res = new Resultado().archivo( d );
        }
        for ( int i = avisos.size() - 1; i >= 0; i-- ) {
          if ( !res.avisos.contains( avisos.get( i ) ) ) {
            res.avisos.add( 0, avisos.get( i ) );
          }
        }
        return res;
      }
    } );
  }

  /**
   * El renderizador de cada formato (el registro). Los avisos del pre-render van a {@code avisos}; el
   * pre-render (Edge/Chrome) se corta si {@code pr} se cancela.
   */
  static Renderizador renderizador( Formato f, Informe inf, Tema t, List<String> avisos, Progreso pr ) {
    switch ( f ) {
      case HTML:
        return new RenderHtml( RenderHtml.Modo.INTERACTIVO, null, false );
      case HTML_ESTATICO:
        return new RenderHtml( RenderHtml.Modo.ESTATICO, prerenderSvg( inf, t, avisos, pr ), false );
      case PDF:
        return new RenderPdf();
      case MARKDOWN:
        return new RenderMarkdown();
      case PPTX:
        // sin PNG, PptxDiapos pone los diagramas Mermaid como tablas y ya lo avisa: aca no se repite
        return new RenderPptx( prerenderPng( inf, t, null, pr ) ).conAnexo( t != null && t.salida != null
          && t.salida.pptxAnexo );
      case DOCX:
        return new RenderDocx( prerenderPng( inf, t, null, pr ) );
      case XLSX:
        return new RenderXlsx();
      case IMAGENES:
        return new RenderImagenes( prerenderPng( inf, t, null, pr ), prerenderSvg( inf, t, null, pr ) );
      case JSON:
        return new RenderJson();
      default:
        throw new IllegalArgumentException( "Unknown format: " + f );
    }
  }

  /**
   * Los diagramas que se dibujan con Mermaid (los que tienen texto Mermaid y no grafo propio: el modelo
   * entidad-relacion). Flujo, job y linaje los dibuja el motor propio sin navegador.
   */
  static List<Diagrama> diagramas( Informe inf ) {
    List<Diagrama> l = new ArrayList<Diagrama>();
    for ( Seccion s : inf.secciones ) {
      if ( s == null || s.bloques == null ) {
        continue;
      }
      for ( Bloque b : s.bloques ) {
        if ( b instanceof Diagrama && Txt.vacio( ( (Diagrama) b ).mermaid ) != null && ( (Diagrama) b ).grafo
          == null ) {
          l.add( (Diagrama) b );
        }
      }
    }
    return l;
  }

  /**
   * Si hay Edge o Chrome, con la ruta del tema primero (la de informes.json): la misma busqueda que
   * RenderPdf y PrerenderMermaid, asi el PDF, el HTML liviano y el PPTX opinan lo mismo.
   */
  static boolean hayNavegador( Tema t ) {
    try {
      return Chromium.ubicar( t == null ? null : Txt.vacio( t.navegador ) ) != null;
    } catch ( Throwable e ) {
      return false;
    }
  }

  /**
   * id -> SVG estatico, o null si no hay diagramas, navegador o el pre-render fallo. Sin navegador, o si
   * algun diagrama no salio, deja un aviso traducido en {@code avisos} (si no es null): el HTML liviano
   * queda con recuadros y tablas en lugar de esos diagramas. Lanza CancellationException si pr se cancela.
   */
  static Map<String, String> prerenderSvg( Informe inf, Tema t, List<String> avisos, Progreso pr ) {
    List<Diagrama> ds = diagramas( inf );
    if ( ds.isEmpty() ) {
      return null;
    }
    Rotulos r = Rotulos.de( "navegador", inf.idioma );
    if ( !hayNavegador( t ) ) {
      avisar( avisos, r.s( "liviano_sin_navegador" ) );
      return null;
    }
    try {
      Map<String, String> m = PrerenderMermaid.svg( ds, t, pr == null ? Progreso.NULO : pr );
      int faltan = faltan( ds, m );
      if ( faltan > 0 ) {
        avisar( avisos, r.f( "liviano_faltan", faltan, ds.size() ) );
      }
      return m;
    } catch ( CancellationException e ) {
      throw e;
    } catch ( Throwable e ) {
      log().logBasic( "Informes: sin pre-render SVG: " + RunMotor.mensaje( e ) );
      avisar( avisos, r.f( "liviano_faltan", ds.size(), ds.size() ) );
      return null;
    }
  }

  /** id -> PNG de Mermaid, o null. Los que faltan van como tablas en el PPTX (y PptxDiapos lo avisa). */
  static Map<String, byte[]> prerenderPng( Informe inf, Tema t, List<String> avisos, Progreso pr ) {
    List<Diagrama> ds = diagramas( inf );
    if ( ds.isEmpty() || !hayNavegador( t ) ) {
      return null;
    }
    try {
      return PrerenderMermaid.png( ds, t, pr == null ? Progreso.NULO : pr );
    } catch ( CancellationException e ) {
      throw e;
    } catch ( Throwable e ) {
      log().logBasic( "Informes: sin pre-render PNG: " + RunMotor.mensaje( e ) );
      return null;
    }
  }

  /** Cuantos diagramas no tienen su SVG en el mapa. */
  private static int faltan( List<Diagrama> ds, Map<String, ?> m ) {
    int n = 0;
    for ( Diagrama d : ds ) {
      if ( m == null || d.id == null || !m.containsKey( d.id ) ) {
        n++;
      }
    }
    return n;
  }

  private static void avisar( List<String> avisos, String texto ) {
    if ( avisos != null && texto != null && !avisos.contains( texto ) ) {
      avisos.add( texto );
    }
  }

  /** El modelo tal cual (ModeloJson, con sangria y los binarios en base64). */
  static final class RenderJson implements Renderizador {
    @Override
    public Formato formato() {
      return Formato.JSON;
    }

    @Override
    public Resultado escribir( Informe inf, Tema tema, File destino, Progreso p ) throws Exception {
      byte[] json = ModeloJson.escribir( inf, true ).getBytes( StandardCharsets.UTF_8 );
      try ( OutputStream o = new FileOutputStream( destino ) ) {
        o.write( json );
      }
      return new Resultado().archivo( destino );
    }
  }

  /**
   * HTML estatico (sin JS) en %TEMP%\plive\vista\&lt;docId&gt;\vista.html (worker). Con prerender y
   * Edge/Chrome, los diagramas Mermaid van como SVG; si no, recuadro y tabla alternativa.
   */
  public static File vistaPrevia( final Informe inf, Tema t, boolean prerender, final Progreso pr ) throws Exception {
    if ( inf == null ) {
      throw new IllegalArgumentException( "No report" );
    }
    limpiarTemporales();
    String docId = DOC_IDS.get( inf );
    if ( Txt.vacio( docId ) == null ) {
      docId = inf.archivo != null ? inf.archivo : inf.nombreDoc;
    }
    File dir = new File( carpetaVistas(), carpetaSegura( docId ) );
    if ( !dir.isDirectory() && !dir.mkdirs() ) {
      throw new java.io.IOException( "Could not create the folder " + dir );
    }
    final File f = new File( dir, "vista.html" );
    final Tema tema = t == null ? new Tema() : t;
    final Map<String, String> svg = prerender ? prerenderSvg( inf, tema, null, pr ) : null;
    conLoader( new Callable<Resultado>() {
      @Override
      public Resultado call() throws Exception {
        return new RenderHtml( RenderHtml.Modo.ESTATICO, svg, false ).escribir( inf, tema, f,
          pr == null ? Progreso.NULO : pr );
      }
    } );
    return f;
  }

  /** %TEMP%\plive\vista. */
  static File carpetaVistas() {
    File base = temporales != null ? temporales : new File( System.getProperty( "java.io.tmpdir" ) );
    return new File( new File( base, "plive" ), "vista" );
  }

  /** Una vez por sesion: borra las carpetas de vista previa de mas de un dia (diseno §5.3). */
  static void limpiarTemporales() {
    if ( !LIMPIADO.compareAndSet( false, true ) ) {
      return;
    }
    File[] hijos = carpetaVistas().listFiles();
    if ( hijos == null ) {
      return;
    }
    long limite = System.currentTimeMillis() - VIDA_TEMPORALES_MS;
    for ( File h : hijos ) {
      if ( h.isDirectory() && h.lastModified() < limite ) {
        borrar( h );
      }
    }
  }

  private static void borrar( File f ) {
    File[] hijos = f.listFiles();
    if ( hijos != null ) {
      for ( File h : hijos ) {
        borrar( h );
      }
    }
    if ( !f.delete() ) {
      f.deleteOnExit();
    }
  }

  /** Nombre de carpeta seguro y corto para un docId ("T1", "file:C:\...\x.ktr"). */
  static String carpetaSegura( String docId ) {
    String s = docId == null ? "" : docId;
    String limpio = Txt.nombreArchivo( s, 40 );
    if ( limpio.equals( s ) ) {
      return limpio; // "T1", "J2"
    }
    int barra = Math.max( s.lastIndexOf( '\\' ), s.lastIndexOf( '/' ) );
    String base = Txt.nombreArchivo( barra >= 0 ? s.substring( barra + 1 ) : s, 40 );
    return base + "-" + Integer.toHexString( s.hashCode() );
  }

  // ================================================================ nombres de archivo

  /**
   * Nombre base de los archivos exportados: el titulo (o el documento) sin caracteres invalidos, mas
   * "-AAAAMMDD-HHMM" si el tema pide agregar la fecha.
   */
  public static String nombreBase( Informe inf, Tema t ) {
    String n = inf == null ? null : Txt.vacio( inf.nombreDoc ) != null ? inf.nombreDoc : inf.titulo;
    String base = Txt.nombreArchivo( n, 80 );
    if ( t != null && t.salida != null && t.salida.agregarFecha ) {
      base += "-" + new SimpleDateFormat( "yyyyMMdd-HHmm" ).format( new Date() );
    }
    return base;
  }

  /**
   * El archivo de un formato: base.html, base.light.html, base.pdf, base.md, base.pptx, base.docx, base.xlsx,
   * base.json; para las imagenes, la carpeta base_imagenes.
   */
  public static File archivo( File carpeta, String base, Formato f ) {
    String nombre = f == Formato.HTML_ESTATICO ? base + ".light.html" : f == Formato.IMAGENES ? base
      + RenderImagenes.SUFIJO : base + "." + f.extension;
    return new File( carpeta, nombre );
  }

  /**
   * La carpeta de exportacion por defecto: la del tema, o &lt;carpeta del .ktr&gt;\informes; null si no hay.
   * Una carpeta relativa en los ajustes se toma desde la carpeta del documento (no desde data-integration,
   * el directorio de trabajo de Spoon); sin documento guardado, se ignora.
   */
  public static File carpetaPorDefecto( Captura c, Tema t ) {
    if ( t != null && t.salida != null && Txt.vacio( t.salida.carpeta ) != null ) {
      File f = new File( t.salida.carpeta.trim() );
      if ( f.isAbsolute() ) {
        return f;
      }
      File doc = c == null || c.archivo == null ? null : new File( c.archivo ).getAbsoluteFile().getParentFile();
      if ( doc != null ) {
        return new File( doc, t.salida.carpeta.trim() );
      }
    }
    if ( c != null && c.archivo != null ) {
      File p = new File( c.archivo ).getAbsoluteFile().getParentFile();
      if ( p != null ) {
        return new File( p, "informes" );
      }
    }
    return null;
  }

  // ================================================================ varios documentos (0.5.1)

  /** Tope de documentos de un informe combinado o de una exportacion en tanda (memoria). */
  public static final int MAX_DOCUMENTOS = 10;
  /** Niveles de sub-jobs que recorre {@link #transformacionesDeJob}. */
  static final int MAX_NIVELES_JOB = 3;

  /**
   * Junta varios informes ya armados en uno (portada general, documentos del informe y un capitulo por
   * documento). Ver {@link Combinar}.
   */
  public static Informe combinar( List<Informe> partes, String titulo, String idioma, Tema t ) {
    return Combinar.combinar( partes, titulo, idioma, t );
  }

  /**
   * Los archivos que ejecuta un job (Transformation y, con {@code recursivo}, Job y lo que corren esos
   * sub-jobs hasta {@link #MAX_NIVELES_JOB} niveles): rutas locales que existen, en el orden de ejecucion
   * y sin repetidos. Ni el propio job ni lo que vive en un repositorio. Lee los sub-jobs del disco (worker).
   */
  public static List<String> transformacionesDeJob( Captura c, boolean recursivo ) {
    Set<String> out = new LinkedHashSet<String>();
    if ( c == null || !( c.copia instanceof JobMeta ) ) {
      return new ArrayList<String>( out );
    }
    Set<String> vistos = new LinkedHashSet<String>();
    if ( c.archivo != null ) {
      vistos.add( clave( new File( c.archivo ) ) );
    }
    llamadasDe( (JobMeta) c.copia, c.archivo, recursivo ? MAX_NIVELES_JOB : 1, vistos, out );
    return new ArrayList<String>( out );
  }

  private static void llamadasDe( JobMeta jm, String archivo, int niveles, Set<String> vistos, Set<String> out ) {
    if ( niveles <= 0 ) {
      return;
    }
    VariableSpace vs = Txt.variables( jm );
    File carpeta = archivo == null ? null : new File( archivo ).getAbsoluteFile().getParentFile();
    for ( SecFlujo.Llamada l : SecFlujo.llamadas( jm, archivo ) ) {
      if ( !Boolean.TRUE.equals( l.existe ) || l.crudo == null ) {
        continue;
      }
      String local = Txt.local( vs.environmentSubstitute( l.crudo ) );
      if ( local == null ) {
        continue;
      }
      File f = new File( local );
      if ( !f.isAbsolute() && carpeta != null ) {
        f = new File( carpeta, local );
      }
      f = f.getAbsoluteFile();
      if ( !f.isFile() || !vistos.add( clave( f ) ) ) {
        continue;
      }
      out.add( f.getPath() );
      if ( l.job && niveles > 1 ) {
        try {
          llamadasDe( new JobMeta( f.getPath(), null ), f.getPath(), niveles - 1, vistos, out );
        } catch ( Throwable e ) {
          log().logDebug( "Pentaho Live: could not read the sub-job " + f + ": " + e );
        }
      }
    }
  }

  private static String clave( File f ) {
    try {
      return f.getCanonicalPath().toLowerCase( Locale.ROOT );
    } catch ( Exception e ) {
      return f.getAbsolutePath().toLowerCase( Locale.ROOT );
    }
  }

  /**
   * Pregunta (ya traducida) antes de perfilar varios steps: la union de lo que escribe aguas arriba de
   * todos ellos, una sola vez; null si nada escribe. Con un step, el texto de siempre.
   */
  public static String confirmacionPerfil( Captura c, List<String> steps, String idioma ) {
    if ( c == null || !( c.copia instanceof TransMeta ) || steps == null || steps.isEmpty() ) {
      return null;
    }
    TransMeta tm = (TransMeta) c.copia;
    if ( steps.size() == 1 ) {
      return CorridaMedida.textoConfirmacionPerfil( tm, steps.get( 0 ), idioma );
    }
    List<String> escritores = new ArrayList<String>();
    for ( String s : steps ) {
      for ( String w : CorridaMedida.escritoresAguasArriba( tm, s ) ) {
        if ( !escritores.contains( w ) ) {
          escritores.add( w );
        }
      }
    }
    if ( escritores.isEmpty() ) {
      return null;
    }
    Rotulos r = Rotulos.de( SecMetricas.AREA, idioma );
    return r.f( "confirm.profile_n", CorridaMedida.lista( steps, r ), CorridaMedida.lista( escritores, r ) );
  }

  /** De {@code ids}, los que aplican a ese documento (para armar varios con las mismas casillas). */
  public static List<String> queAplican( Captura c, List<String> ids, String idioma ) {
    List<String> out = new ArrayList<String>();
    if ( ids == null ) {
      return out;
    }
    for ( String id : ids ) {
      Recolector rec = recolector( id );
      if ( rec != null && noAplica( rec, c, idioma ) == null && !out.contains( id ) ) {
        out.add( id );
      }
    }
    return out;
  }

  /**
   * Las conclusiones de un informe combinado: la seccion narrative de la parte general (capitulo null),
   * igual que la arma SecNarrativa para un documento. No hace nada sin texto.
   */
  public static void conclusionesCombinadas( Informe inf, Narrativa n ) {
    String texto = n == null ? null : Txt.recortar( n.conclusiones, MAX_NARRATIVA );
    if ( inf == null || texto == null ) {
      return;
    }
    Seccion s = new Seccion( SeccionId.NARRATIVA, SeccionId.titulo( SeccionId.NARRATIVA, inf.idioma ) );
    Parrafo par = new Parrafo( texto );
    par.id = "narrative-texto";
    par.ia = Narrativa.IA.equals( n.origen );
    s.con( par );
    ObjectNode datos = Txt.obj();
    datos.put( "section", SeccionId.NARRATIVA );
    datos.put( "origin", par.ia ? Narrativa.IA : Narrativa.USUARIO );
    datos.put( "characters", texto.length() );
    s.datos = datos;
    inf.secciones.add( s );
  }

  /**
   * Los nombres base sin repetir (sin distinguir mayusculas): el segundo "Ejercicio" pasa a "Ejercicio-2",
   * y asi. Para exportar varios documentos a la misma carpeta sin pisarse.
   */
  public static List<String> basesUnicas( List<String> bases ) {
    List<String> out = new ArrayList<String>();
    Set<String> usadas = new LinkedHashSet<String>();
    for ( String b : bases ) {
      String x = b == null || b.trim().isEmpty() ? "informe" : b.trim();
      String y = x;
      for ( int n = 2; !usadas.add( y.toLowerCase( Locale.ROOT ) ); n++ ) {
        y = x + "-" + n;
      }
      out.add( y );
    }
    return out;
  }

  /** Copia sin byte[] (para get_report_data). Delega en el modelo. */
  public static Informe sinBinarios( Informe inf ) {
    return inf == null ? null : inf.sinBinarios();
  }

  // ================================================================ hilos

  private static ThreadFactory fabrica( final String prefijo ) {
    final ClassLoader cl = InformeServicio.class.getClassLoader();
    return new ThreadFactory() {
      private final AtomicInteger n = new AtomicInteger();

      @Override
      public Thread newThread( Runnable r ) {
        Thread t = new Thread( r, prefijo + n.incrementAndGet() );
        t.setDaemon( true );
        t.setContextClassLoader( cl );
        return t;
      }
    };
  }

  /** Corre con el context classloader del plugin (POI y JAXB lo necesitan) y lo restaura. */
  static <T> T conLoader( Callable<T> c ) throws Exception {
    Thread h = Thread.currentThread();
    ClassLoader antes = h.getContextClassLoader();
    h.setContextClassLoader( InformeServicio.class.getClassLoader() );
    try {
      return c.call();
    } finally {
      h.setContextClassLoader( antes );
    }
  }

  static LogChannelInterface log() {
    if ( log == null ) {
      log = new LogChannel( "PentahoLive-Informes" );
    }
    return log;
  }

  // ================================================================ textos comunes de las secciones

  /** Utilidades de texto que comparten las secciones estructurales (Sec*). */
  static final class Txt {

    private Txt() {
    }

    /** "1 step" / "5 steps": clave base + ".1" o ".n", con %,d. */
    static String n( Rotulos r, String base, long n ) {
      return r.f( base + ( n == 1 ? ".1" : ".n" ), n );
    }

    /** "a", "a y b", "a, b y c" (palabra "y" del idioma). */
    static String lista( Rotulos r, List<String> partes ) {
      return unir( r, partes, false );
    }

    /** Clausulas de una frase: "a", "a y b", "a, b, y c" (con coma antes de la ultima). */
    static String clausulas( Rotulos r, List<String> partes ) {
      return unir( r, partes, true );
    }

    private static String unir( Rotulos r, List<String> partes, boolean comaFinal ) {
      List<String> l = new ArrayList<String>();
      for ( String p : partes ) {
        if ( vacio( p ) != null ) {
          l.add( p );
        }
      }
      String y = " " + r.s( "y" ) + " ";
      if ( l.isEmpty() ) {
        return "";
      }
      if ( l.size() == 1 ) {
        return l.get( 0 );
      }
      // con coma antes de la ultima si son 3 o mas clausulas, o si una anterior ya lleva la "y"
      boolean coma = false;
      if ( comaFinal ) {
        coma = l.size() > 2;
        for ( int i = 0; i < l.size() - 1 && !coma; i++ ) {
          coma = l.get( i ).contains( y );
        }
      }
      if ( l.size() == 2 ) {
        return l.get( 0 ) + ( coma ? "," : "" ) + y + l.get( 1 );
      }
      StringBuilder sb = new StringBuilder();
      for ( int i = 0; i < l.size() - 1; i++ ) {
        sb.append( i == 0 ? "" : ", " ).append( l.get( i ) );
      }
      return sb.append( coma ? "," : "" ).append( y ).append( l.get( l.size() - 1 ) ).toString();
    }

    static String mayuscula( String s ) {
      if ( s == null || s.isEmpty() ) {
        return s;
      }
      return s.substring( 0, 1 ).toUpperCase( Locale.ROOT ) + s.substring( 1 );
    }

    /** null si es null o solo espacios; si no, el texto tal cual. */
    static String vacio( String s ) {
      return s == null || s.trim().isEmpty() ? null : s;
    }

    /** Recorta a max caracteres (con "…"); null si queda vacio. */
    static String recortar( String s, int max ) {
      String v = vacio( s );
      if ( v == null ) {
        return null;
      }
      v = v.trim();
      return v.length() <= max ? v : v.substring( 0, max - 1 ).trim() + "…";
    }

    /** "2026-10-20 14:32", o null. */
    static String fecha( Date d ) {
      return d == null ? null : new SimpleDateFormat( "yyyy-MM-dd HH:mm" ).format( d );
    }

    /**
     * Texto Markdown de ReportMotor (codigo en `...`, | escapado, &amp;lt; &amp;gt;) a texto plano para
     * las celdas del modelo, que cada renderizador escapa a su manera.
     */
    static String plano( String md ) {
      if ( md == null ) {
        return null;
      }
      StringBuilder sb = new StringBuilder();
      int i = 0;
      int n = md.length();
      while ( i < n ) {
        char c = md.charAt( i );
        if ( c == '`' ) {
          int k = i;
          while ( k < n && md.charAt( k ) == '`' ) {
            k++;
          }
          String marca = md.substring( i, k );
          int cierre = md.indexOf( marca, k );
          // el cierre tiene que ser una corrida del mismo largo
          while ( cierre >= 0 && cierre + marca.length() < n && md.charAt( cierre + marca.length() ) == '`' ) {
            cierre = md.indexOf( marca, cierre + marca.length() + 1 );
          }
          if ( cierre < 0 ) {
            sb.append( marca );
            i = k;
            continue;
          }
          String adentro = md.substring( k, cierre );
          if ( adentro.length() > 2 && adentro.startsWith( " " ) && adentro.endsWith( " " ) ) {
            adentro = adentro.substring( 1, adentro.length() - 1 );
          }
          sb.append( adentro.replace( "\\|", "|" ) );
          i = cierre + marca.length();
          continue;
        }
        sb.append( c );
        i++;
      }
      return sb.toString().replace( "\\|", "|" ).replace( "&lt;", "<" ).replace( "&gt;", ">" );
    }

    /** Ruta local de un nombre resuelto ("file:///C:/x%20y/a.csv" -> "C:\x y\a.csv"); tal cual si no es file:. */
    static String local( String ruta ) {
      if ( ruta == null ) {
        return null;
      }
      String r = ruta.trim();
      if ( r.regionMatches( true, 0, "file:", 0, 5 ) ) {
        try {
          return new File( URI.create( r.replace( " ", "%20" ) ) ).getPath();
        } catch ( Exception e ) {
          try {
            String s = r.replaceFirst( "(?i)^file:/*", "" );
            return new File( URLDecoder.decode( s.replace( "+", "%2B" ), "UTF-8" ) ).getPath();
          } catch ( Exception e2 ) {
            return r;
          }
        }
      }
      return r;
    }

    /** Nombre del archivo de una ruta (local, URI o con variables sin resolver). */
    static String baseNombre( String ruta ) {
      String l = local( ruta );
      if ( l == null ) {
        return null;
      }
      int b = Math.max( l.lastIndexOf( '/' ), l.lastIndexOf( '\\' ) );
      return b >= 0 && b < l.length() - 1 ? l.substring( b + 1 ) : l;
    }

    /** La ruta relativa a la carpeta del documento si esta adentro (privacidad, §6 riesgo 20); si no, la local. */
    static String relativa( String ruta, String archivoDoc ) {
      String l = local( ruta );
      if ( l == null || archivoDoc == null || l.contains( "${" ) ) {
        return l;
      }
      try {
        Path dir = new File( archivoDoc ).getAbsoluteFile().getParentFile().toPath().normalize();
        Path p = new File( l ).getAbsoluteFile().toPath().normalize();
        if ( p.startsWith( dir ) ) {
          return dir.relativize( p ).toString().replace( '\\', '/' );
        }
      } catch ( Exception ignored ) {
        // ruta rara: tal cual
      }
      return l;
    }

    /** Nombre de archivo valido en Windows (sin \ / : * ? " &lt; &gt; | ni controles), hasta max caracteres. */
    static String nombreArchivo( String s, int max ) {
      String v = s == null ? "" : s.trim();
      StringBuilder sb = new StringBuilder();
      for ( int i = 0; i < v.length(); i++ ) {
        char c = v.charAt( i );
        sb.append( c < 0x20 || "\\/:*?\"<>|".indexOf( c ) >= 0 ? '_' : c );
      }
      String r = sb.toString().trim();
      while ( r.endsWith( "." ) ) {
        r = r.substring( 0, r.length() - 1 ).trim();
      }
      if ( r.length() > max ) {
        r = r.substring( 0, max ).trim();
      }
      return r.isEmpty() ? "informe" : r;
    }

    static ObjectNode obj() {
      return ModeloJson.mapper().createObjectNode();
    }

    /**
     * Las variables del documento (internas incluidas) mas sus parametros con el valor por defecto, para
     * resolver rutas como "${ARCHIVO_SALIDA}.csv" igual que en una corrida. No toca el documento.
     */
    static VariableSpace variables( AbstractMeta m ) {
      Variables v = new Variables();
      v.copyVariablesFrom( m );
      try {
        String[] ps = m.listParameters();
        if ( ps != null ) {
          for ( String p : ps ) {
            String val = vacio( m.getParameterValue( p ) );
            if ( val == null ) {
              val = vacio( m.getParameterDefault( p ) );
            }
            if ( val != null && vacio( v.getVariable( p ) ) == null ) {
              v.setVariable( p, val );
            }
          }
        }
      } catch ( Exception ignored ) {
        // parametros raros: quedan sin resolver
      }
      return v;
    }

    /** Pone el texto solo si no es null ni vacio (asi el JSON no lleva "x": null). */
    static void poner( ObjectNode o, String clave, String valor ) {
      if ( vacio( valor ) != null ) {
        o.put( clave, valor );
      }
    }
  }
}
