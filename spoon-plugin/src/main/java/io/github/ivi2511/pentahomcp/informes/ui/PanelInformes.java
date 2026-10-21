package io.github.ivi2511.pentahomcp.informes.ui;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.pentaho.di.core.logging.LogChannel;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.TransMeta;

import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Disponibilidad;
import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Pedido;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Resultado;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.ModeloJson;
import io.github.ivi2511.pentahomcp.informes.modelo.Narrativa;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Panel SWT de la perspectiva Informes (diseno §5): barra de documento arriba, opciones a la izquierda
 * (secciones, identidad, exportar) en un ScrolledComposite, vista previa a la derecha y estado abajo.
 * <p>
 * <b>Hilos (§1.4).</b> Todo metodo publico de instancia corre en el hilo de UI. La captura del documento
 * se toma en la UI; armar, renderizar y exportar corren en el pool del panel (2 hilos daemon, con el
 * context classloader del plugin) y vuelven con {@code asyncExec}, chequeando {@code isDisposed} y el
 * numero de generacion: una tarea nueva cancela la anterior por {@link Progreso#cancelado()}. Nunca se
 * hace {@code Future.get()} en la UI.
 * <p>
 * <b>Dialogos.</b> FileDialog, DirectoryDialog, ColorDialog y la confirmacion solo se abren por un clic;
 * nunca desde {@link #alActivar()} ni desde el MCP. Nada de dialogos de Spoon. Los errores van al estado,
 * en rojo, y al log ({@code LogChannel("PentahoLive-Informes")}).
 * <p>
 * Todo lo que el panel necesita de Spoon y del motor pasa por {@link Servicio}: en Spoon lo da
 * ReportsPerspective; en las pruebas, un servicio falso.
 *
 * @dueño WP1
 */
public class PanelInformes {

  /** Area de textos del panel: textos/ui_&lt;idioma&gt;.properties. */
  public static final String AREA = "ui";

  /** Clave de {@code Widget.setData} con el id estable de cada control (pruebas y automatizacion). */
  public static final String CLAVE = "plive.id";

  /** Espera antes de la vista previa liviana automatica (§5.3). */
  public static final int DEBOUNCE_MS = 400;

  /**
   * Secciones de la vista previa liviana: no consultan la base ni ejecutan nada (§5.3). quality tampoco va: calcula
   * campos, como validation (docs/linter-diseno.md §8).
   */
  public static final List<String> LIVIANAS = Collections.unmodifiableList( Arrays.asList( SeccionId.PORTADA,
    SeccionId.RESUMEN, SeccionId.FLUJO, SeccionId.FLUJO_JOB, SeccionId.CONEXIONES, SeccionId.NARRATIVA ) );

  /** Canal de log. */
  public static final String CANAL = "PentahoLive-Informes";

  /** Ancho por debajo del cual la columna de opciones pasa a scroll horizontal. */
  static final int ANCHO_MINIMO_IZQ = 250;

  /** Margen sobre Pedido.timeoutMs al esperar el informe. */
  static final long MARGEN_ESPERA_MS = 15000;

  // ================================================================ contrato con Spoon y el motor

  /**
   * Lo que el panel necesita de Spoon y del motor. Los metodos marcados (UI) corren en el hilo de UI; los
   * (worker), en el pool del panel.
   */
  public interface Servicio {

    /** Transformaciones y jobs abiertos, en el orden de las pestanas, con los ids de Docs. (UI) */
    List<Documento> documentos();

    /** Captura del documento abierto con ese id, sin activar su pestana. (UI) */
    Captura capturar( String docId ) throws Exception;

    /** Carga un .ktr/.kjb sin abrir pestana; pinta el lienzo en la UI con el display. (worker) */
    Captura capturarArchivo( String ruta, Display d ) throws Exception;

    /** Que secciones aplican; barato. (UI) */
    List<Disponibilidad> disponibles( Captura c, String idioma );

    /**
     * Arma el informe en el pool de informes con el tema que se esta editando (la portada usa autor,
     * institucion y logo aunque todavia no se hayan guardado).
     */
    Future<Informe> armar( Captura c, Pedido p, Tema t, Progreso pr );

    /** HTML estatico de la vista previa, con o sin el pre-render de Mermaid. (worker) */
    File vistaPrevia( Informe inf, Tema t, boolean prerender, Progreso pr ) throws Exception;

    /** Escribe el informe en un formato. (worker) */
    Resultado exportar( Informe inf, Tema t, Formato f, File destino, Progreso pr ) throws Exception;

    /** Si hay Edge o Chrome para el PDF y el pre-render. (worker) */
    boolean hayNavegador();

    /**
     * Igual, mirando primero la ruta del navegador del tema (informes.json), como el PDF y el pre-render.
     * (worker)
     */
    default boolean hayNavegador( Tema t ) {
      return hayNavegador();
    }

    /** Steps o entradas que escriben fuera del documento, para la confirmacion de Medir. (UI) */
    List<String> pasosQueEscriben( Captura c );

    /**
     * Pregunta (ya traducida) antes de perfilar {@code step} cuando hay steps que escriben aguas arriba
     * (diseno §2.4.3: el perfil ejecuta una copia hasta ese step), o null si nada escribe. (UI)
     */
    default String confirmacionPerfil( Captura c, String step, String idioma ) {
      return null;
    }

    /**
     * Igual, para varios steps perfilados en una sola corrida: una sola pregunta con la union de lo que
     * escribe aguas arriba de todos. (UI)
     */
    default String confirmacionPerfil( Captura c, List<String> steps, String idioma ) {
      if ( steps == null || steps.isEmpty() ) {
        return null;
      }
      if ( steps.size() == 1 ) {
        return confirmacionPerfil( c, steps.get( 0 ), idioma );
      }
      return io.github.ivi2511.pentahomcp.InformeServicio.confirmacionPerfil( c, steps, idioma );
    }

    /**
     * Las transformaciones y sub-jobs que ejecuta un job (rutas locales que existen, en orden, sin
     * repetidos). (worker)
     */
    default List<String> transformacionesDeJob( Captura c ) {
      return io.github.ivi2511.pentahomcp.InformeServicio.transformacionesDeJob( c, true );
    }

    /** Un informe con portada, indice y un capitulo por cada informe, mas las conclusiones. (worker) */
    default Informe combinar( List<Informe> partes, String titulo, String idioma, Tema t, Narrativa n ) {
      Informe inf = io.github.ivi2511.pentahomcp.informes.Combinar.combinar( partes, titulo, idioma, t );
      io.github.ivi2511.pentahomcp.InformeServicio.conclusionesCombinadas( inf, n );
      return inf;
    }

    /** Por que no se puede exportar PowerPoint en este proceso (XmlBeans que no es 3.1), o null. (UI) */
    default String problemaPptx( String idioma ) {
      return null;
    }

    /** El tema guardado o la plantilla neutra. Nunca null. (UI) */
    Tema temaGuardado();

    /**
     * Igual, y deja en {@code avisos} los problemas del archivo de ajustes ya traducidos (JSON roto, logo
     * invalido...), para que el panel avise una vez. (UI)
     */
    default Tema temaGuardado( String idioma, List<String> avisos ) {
      return temaGuardado();
    }

    /** Guarda el tema como predeterminado (informes.json y logo). (UI) */
    void guardarTema( Tema t ) throws Exception;

    /** Abre un archivo o carpeta con el programa del sistema. (UI) */
    default void abrir( File f ) {
      Program.launch( f.getAbsolutePath() );
    }

    /** Pregunta si seguir (solo despues de un clic del usuario). (UI) */
    default boolean confirmar( Shell padre, String titulo, String mensaje ) {
      MessageBox mb = new MessageBox( padre, SWT.ICON_WARNING | SWT.OK | SWT.CANCEL );
      mb.setText( titulo );
      mb.setMessage( mensaje );
      return mb.open() == SWT.OK;
    }

    /** Corre una tarea del panel fuera de la UI. */
    default <T> Future<T> enSegundoPlano( Callable<T> tarea ) {
      return enPool( tarea );
    }

    /** Carpeta de temporales: %TEMP%\plive (la vista previa usa vista\&lt;docId&gt;). */
    default File temporales() {
      return new File( System.getProperty( "java.io.tmpdir" ), "plive" );
    }

    /** Log de errores y avisos del panel. */
    default void log( String texto, Throwable t ) {
      registrar( texto, t );
    }

    /** Nombre base de los archivos exportados (el mismo que usa build_report en Spoon). */
    default String nombreBase( Informe inf, Tema t ) {
      String n = inf == null ? null : inf.nombreDoc != null && !inf.nombreDoc.trim().isEmpty() ? inf.nombreDoc
        : inf.titulo;
      n = n == null ? "" : n.replaceAll( "[\\\\/:*?\"<>|\\p{Cntrl}]", "_" ).trim();
      if ( n.isEmpty() ) {
        n = "report";
      }
      if ( t != null && t.salida != null && t.salida.agregarFecha ) {
        n += "-" + new SimpleDateFormat( "yyyyMMdd-HHmm" ).format( new Date() );
      }
      return n;
    }

    /**
     * El archivo de un formato en la carpeta: base.html, base.light.html, base.pdf, base.md, base.pptx...; las
     * imagenes van a la carpeta base_imagenes.
     */
    default File archivo( File carpeta, String base, Formato f ) {
      return new File( carpeta, f == Formato.HTML_ESTATICO ? base + ".light.html" : f.esCarpeta() ? base
        + "_imagenes" : base + "." + f.extension );
    }
  }

  /** Un documento del combo: una pestana de Spoon o un archivo cargado con «Abrir archivo…». */
  public static final class Documento {
    /** Id de Docs (T1, J2...) o "file:&lt;ruta&gt;" para los cargados. */
    public final String id;
    public final String nombre;
    /** transformation | job (Captura.TRANSFORMACION / JOB). */
    public final String tipo;
    /** Ruta del .ktr/.kjb o null. */
    public final String archivo;
    /** Es la pestana activa de Data Integration. */
    public final boolean activo;
    /** Se cargo con «Abrir archivo…», sin pestana. */
    public final boolean cargado;

    public Documento( String id, String nombre, String tipo, String archivo, boolean activo, boolean cargado ) {
      this.id = id;
      this.nombre = nombre;
      this.tipo = tipo;
      this.archivo = archivo;
      this.activo = activo;
      this.cargado = cargado;
    }

    public boolean esJob() {
      return Captura.JOB.equals( tipo );
    }
  }

  // ================================================================ pool del panel

  private static ExecutorService pool;

  private static synchronized ExecutorService pool() {
    if ( pool == null ) {
      final AtomicInteger n = new AtomicInteger();
      pool = Executors.newFixedThreadPool( 2, r -> {
        Thread t = new Thread( r, "pentaho-live-informes-panel-" + n.incrementAndGet() );
        t.setDaemon( true );
        return t;
      } );
    }
    return pool;
  }

  /**
   * Corre la tarea en el pool del panel con el context classloader del plugin (POI lo necesita para
   * JAXB). Es el {@link Servicio#enSegundoPlano} por defecto.
   */
  public static <T> Future<T> enPool( final Callable<T> tarea ) {
    final ClassLoader cl = PanelInformes.class.getClassLoader();
    return pool().submit( () -> {
      Thread h = Thread.currentThread();
      ClassLoader antes = h.getContextClassLoader();
      h.setContextClassLoader( cl );
      try {
        return tarea.call();
      } finally {
        h.setContextClassLoader( antes );
      }
    } );
  }

  // ================================================================ show_report

  private static volatile WeakReference<PanelInformes> actual = new WeakReference<PanelInformes>( null );
  private static boolean hayPendiente;
  private static String pendienteDoc;
  private static Pedido pendientePedido;
  private static Informe pendienteInforme;

  /**
   * Selecciona el documento y lanza la vista previa con ese pedido (secciones, idioma, narrativa). (UI) La
   * llama show_report despues de {@code spoon.loadPerspective(ReportsPerspective.ID)}. Si el panel todavia no
   * existe, el pedido queda pendiente y se aplica al crearlo. Desde otro hilo, se pasa a la UI. Por este
   * camino (el MCP) nunca se ejecuta nada: ni el perfil ni Medir.
   */
  public static void mostrar( final String docId, final Pedido p ) {
    mostrar( docId, p, null );
  }

  /**
   * Igual, con el informe ya armado (build_report con show_in_spoon): el panel solo lo muestra, sin armarlo
   * de nuevo (armarlo otra vez volveria a ejecutar el perfil y a escribir sus salidas).
   */
  public static void mostrar( final String docId, final Pedido p, final Informe hecho ) {
    final PanelInformes panel = actual.get();
    if ( panel == null || panel.raiz.isDisposed() ) {
      synchronized ( PanelInformes.class ) {
        hayPendiente = true;
        pendienteDoc = docId;
        pendientePedido = p;
        pendienteInforme = hecho;
      }
      return;
    }
    if ( Display.getCurrent() == panel.display ) {
      panel.aplicar( docId, p, hecho );
    } else if ( !panel.display.isDisposed() ) {
      panel.display.asyncExec( () -> {
        if ( !panel.raiz.isDisposed() ) {
          panel.aplicar( docId, p, hecho );
        }
      } );
    }
  }

  // ================================================================ instancia

  final Servicio servicio;
  final Display display;
  final Rotulos r;
  final String idiomaUi;
  private final Composite raiz;
  private Tema tema;

  final BarraDocumento barra;
  final GrupoSecciones secciones;
  final GrupoIdentidad identidad;
  final GrupoDocumentos documentos;
  final GrupoExportar exportar;
  final VistaPrevia vista;
  final EstadoUi estado;
  private final SashForm sash;
  /** El Browser es Edge (WebView2): la vista previa es el HTML interactivo; con IE11, el estatico. */
  private final boolean motorEdge;
  private final ScrolledComposite scroll;
  private final Composite izquierda;

  /** Generacion de la tarea vigente; solo cambia en la UI. */
  private volatile int generacion;
  /** Hay una tarea pesada (vista completa, exportar, medir...): botones deshabilitados. */
  private boolean pesado;
  private boolean livianaPendiente;
  /** show_report que llego con una tarea del usuario en curso: se aplica al terminar, sin cortarla. */
  private Object[] mostrarPendiente;
  private int debounce;

  private final Map<String, Captura> archivos = new HashMap<String, Captura>();
  private final List<Documento> cargados = new ArrayList<Documento>();
  private Informe ultimoInforme;
  private String ultimoDoc;
  private boolean ultimoEsCompleto;
  private Completo completo;
  private Narrativa narrativaExterna;
  private String narrativaExternaDoc;
  private String ultimoActivoDi;
  private File ultimaCarpeta;
  /** Problema del archivo de ajustes: se muestra al arrancar y al terminar la primera tarea (una vez). */
  private String avisoTema;
  private final Listener filtroF5;

  /**
   * Arma el panel dentro de {@code padre} (si no tiene layout, le pone FillLayout). (UI)
   *
   * @param idiomaUi idioma de Spoon (en, es, pt; otro = en)
   */
  public PanelInformes( Composite padre, Servicio servicio, String idiomaUi ) {
    if ( servicio == null ) {
      throw new IllegalArgumentException( "servicio" );
    }
    this.servicio = servicio;
    this.display = padre.getDisplay();
    this.idiomaUi = Rotulos.normalizar( idiomaUi );
    this.r = Rotulos.de( AREA, this.idiomaUi );
    List<String> avisosTema = new ArrayList<String>();
    this.tema = temaInicial( servicio, this.idiomaUi, avisosTema );
    this.avisoTema = avisosTema.isEmpty() ? null : avisosTema.get( 0 );
    if ( padre.getLayout() == null ) {
      padre.setLayout( new FillLayout() );
    }

    raiz = new Composite( padre, SWT.NONE );
    GridLayout gl = new GridLayout( 1, false );
    gl.marginWidth = 0;
    gl.marginHeight = 0;
    gl.verticalSpacing = 0;
    raiz.setLayout( gl );

    barra = new BarraDocumento( raiz, this );
    Label linea = new Label( raiz, SWT.SEPARATOR | SWT.HORIZONTAL );
    linea.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );

    sash = new SashForm( raiz, SWT.HORIZONTAL );
    sash.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, true ) );
    scroll = new ScrolledComposite( sash, SWT.V_SCROLL | SWT.H_SCROLL );
    izquierda = new Composite( scroll, SWT.NONE );
    GridLayout gi = new GridLayout( 1, false );
    gi.marginWidth = 6;
    gi.marginHeight = 6;
    izquierda.setLayout( gi );
    secciones = new GrupoSecciones( izquierda, this );
    identidad = new GrupoIdentidad( izquierda, this );
    documentos = new GrupoDocumentos( izquierda, this );
    exportar = new GrupoExportar( izquierda, this );
    scroll.setContent( izquierda );
    scroll.setExpandHorizontal( true );
    scroll.setExpandVertical( true );
    scroll.addListener( SWT.Resize, e -> ajustarScroll() );

    Composite derecha = new Composite( sash, SWT.BORDER );
    derecha.setLayout( new FillLayout() );
    vista = new VistaPrevia( derecha, this );
    motorEdge = "edge".equalsIgnoreCase( vista.tipo() );
    sash.setWeights( new int[] { 28, 72 } );

    Label linea2 = new Label( raiz, SWT.SEPARATOR | SWT.HORIZONTAL );
    linea2.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
    estado = new EstadoUi( raiz, this );

    identidad.cargar( tema );
    exportar.cargar( tema );
    try {
      exportar.pptxNoDisponible( servicio.problemaPptx( this.idiomaUi ) );
    } catch ( Throwable t ) {
      registrar( "Pentaho Live: could not check PowerPoint support", t ); // el servicio mismo puede estar roto
    }
    barra.idioma( tema.idioma != null ? Rotulos.normalizar( tema.idioma ) : this.idiomaUi );
    if ( avisoTema != null ) {
      estado.error( avisoTema );
    } else {
      estado.listo( r.s( "status_ready" ) );
    }
    ajustarScroll();

    filtroF5 = e -> {
      if ( e.keyCode == SWT.F5 && !raiz.isDisposed() && raiz.isVisible() && focoDentro() ) {
        e.doit = false;
        vistaPreviaCompleta();
      }
    };
    display.addFilter( SWT.KeyDown, filtroF5 );
    raiz.addDisposeListener( e -> alCerrar() );

    actual = new WeakReference<PanelInformes>( this );
    aplicarPendiente();
  }

  // ---------------------------------------------------------------- ciclo de la perspectiva

  /**
   * La perspectiva se activo: refresca el combo; si la pestana activa de Data Integration cambio, la
   * elige. Arma la vista previa liviana salvo que ya se este viendo la completa de ese documento. (UI)
   */
  public void alActivar() {
    try {
      List<Documento> docs = refrescarDocumentos();
      for ( Documento d : docs ) {
        if ( d.activo && !d.id.equals( ultimoActivoDi ) ) {
          ultimoActivoDi = d.id;
          barra.seleccionar( d.id );
          break;
        }
      }
      Documento d = barra.seleccionado();
      if ( d != null && d.id.equals( ultimoDoc ) && ultimoEsCompleto ) {
        return; // la vista completa de este documento sigue en pantalla: no se tira
      }
      programarLiviana();
    } catch ( Throwable t ) {
      fallo( t );
    }
  }

  /** La perspectiva dejo de estar al frente: se descarta la vista liviana pendiente. (UI) */
  public void alDesactivar() {
    debounce++;
  }

  /** La raiz del panel (para pruebas y para el re-cuelgue). */
  public Composite raiz() {
    return raiz;
  }

  /** Hay una tarea pesada en curso. (UI) */
  public boolean ocupado() {
    return pesado;
  }

  /** Texto de la barra de estado. (UI) */
  public String estado() {
    return estado.texto();
  }

  // ---------------------------------------------------------------- acciones (UI)

  /** Vista previa con todas las secciones marcadas (boton o F5). */
  public void vistaPreviaCompleta() {
    vistaCompleta( Pedido.METRICAS_ULTIMA, false );
  }

  /** Cancela la tarea en curso: su resultado se ignora y su Progreso pasa a cancelado. */
  public void cancelar() {
    generacion++;
    pesado = false;
    exportar.ocupado( false );
    estado.listo( r.s( "status_cancelled" ) );
    if ( mostrarPendiente != null ) {
      // el usuario corto su tarea: el show_report que la esperaba ya puede aplicarse
      Object[] x = mostrarPendiente;
      mostrarPendiente = null;
      aplicar( (String) x[ 0 ], (Pedido) x[ 1 ], (Informe) x[ 2 ] );
    }
  }

  /** Exporta los formatos marcados a la carpeta (si no hay, la pide). */
  public void exportar() {
    try {
      if ( pesado ) {
        return;
      }
      debounce++;
      final List<Formato> formatos = exportar.formatos( tema );
      if ( formatos.isEmpty() ) {
        estado.error( r.s( "export_no_formats" ) );
        return;
      }
      final Documento d = barra.seleccionado();
      if ( d == null ) {
        estado.error( r.s( "no_document" ) );
        return;
      }
      String texto = exportar.carpeta();
      if ( texto.isEmpty() ) {
        DirectoryDialog dd = new DirectoryDialog( raiz.getShell() );
        dd.setText( r.s( "folder_dialog" ) );
        dd.setMessage( r.s( "folder_dialog" ) );
        File sugerida = carpetaSugerida( d );
        if ( sugerida != null ) {
          dd.setFilterPath( sugerida.getPath() );
        }
        String elegida = dd.open();
        if ( elegida == null ) {
          return;
        }
        exportar.carpeta( elegida );
        texto = elegida;
      }
      final File carpeta = absoluta( texto, d );
      if ( carpeta == null ) {
        estado.error( r.f( "folder_relative", texto ) );
        return;
      }
      if ( documentos.esVarios() ) {
        List<Documento> elegidos = documentos.elegidos( d );
        if ( elegidos.size() < 2 ) {
          estado.error( r.s( "docs_menos_dos" ) );
          return;
        }
        exportarVarios( elegidos, d, formatos, carpeta );
        return;
      }
      final Captura c = capturar( d );
      if ( c == null ) {
        return;
      }
      final Pedido ped = pedido( false, Pedido.METRICAS_ULTIMA );
      final Completo previo = completo;
      // el perfil ejecuta una copia: se pregunta, salvo que se reuse la vista completa ya armada
      boolean perfila = ped.secciones != null && ped.secciones.contains( SeccionId.PERFIL );
      if ( perfila && !( previo != null && previo.sirve( d.id, firma( ped, tema ), firmaDoc( c ) ) )
        && !confirmarPerfil( c, ped ) ) {
        return;
      }
      final Tema t = tema.copia();
      final boolean necesitaNavegador = necesitaNavegador( formatos );
      lanzar( true, r.s( "status_exporting_start" ), ( gen, pr ) -> {
        Informe inf = null;
        if ( previo != null && previo.sirve( d.id, firma( ped, t ), firmaDoc( c ) ) ) {
          inf = previo.informe; // la vista completa de este mismo documento y pedido: no se rearma
        }
        if ( inf == null ) {
          inf = esperar( servicio.armar( c, ped, t, pr ), ped, pr );
          if ( inf == null ) {
            return null;
          }
        }
        final String base = servicio.nombreBase( inf, t );
        if ( !carpeta.isDirectory() && !carpeta.mkdirs() ) {
          throw new IOException( r.f( "folder_error", carpeta.getPath() ) );
        }
        int archivosEscritos = 0;
        List<String> avisos = new ArrayList<String>();
        for ( int i = 0; i < formatos.size(); i++ ) {
          if ( pr.cancelado() ) {
            return null;
          }
          Formato f = formatos.get( i );
          pr.paso( r.f( "status_exporting", nombreFormato( f ) ), i, formatos.size() );
          Resultado res = servicio.exportar( inf, t, f, servicio.archivo( carpeta, base, f ), pr );
          if ( res != null && f == Formato.PDF && !res.archivos.isEmpty()
            && res.archivos.get( 0 ).getName().toLowerCase().endsWith( ".html" ) ) {
            // sin Edge/Chrome (o si fallo) RenderPdf deja <nombre>.imprimir.html, que imprime al abrirse:
            // lo abre la UI con el programa del sistema (§3.2); el aviso va al estado
            final File imprimir = res.archivos.get( 0 );
            enUi( gen, () -> servicio.abrir( imprimir ) );
          }
          if ( res != null ) {
            archivosEscritos += contar( res, f );
            for ( String a : res.avisos ) {
              if ( a != null && !avisos.contains( a ) ) {
                avisos.add( a );
              }
            }
          }
        }
        final Informe usado = inf;
        enUi( gen, () -> {
          ultimaCarpeta = carpeta;
          exportar.exportado( carpeta );
          ultimoInforme = usado;
          ultimoDoc = d.id;
        } );
        String fin = r.f( "status_exported", archivosEscritos, carpeta.getPath() );
        if ( !avisos.isEmpty() ) {
          fin = r.f( "status_more", fin, avisos.get( 0 ) );
        } else if ( necesitaNavegador && !servicio.hayNavegador( t ) ) {
          fin = r.f( "status_more", fin, r.s( "no_browser" ) );
        }
        return fin;
      } );
    } catch ( Throwable t ) {
      fallo( t );
    }
  }

  // ---------------------------------------------------------------- varios documentos (UI)

  /** Los archivos que cuenta el estado: la carpeta de imagenes no es un archivo. */
  static int contar( Resultado res, Formato f ) {
    int n = 0;
    for ( File x : res.archivos ) {
      if ( x != null && !( f.esCarpeta() && x.isDirectory() ) ) {
        n++;
      }
    }
    return n;
  }

  /** PDF, PowerPoint, Word e imagenes usan Edge/Chrome (PDF y diagramas Mermaid). */
  static boolean necesitaNavegador( List<Formato> formatos ) {
    return formatos.contains( Formato.PDF ) || formatos.contains( Formato.PPTX ) || formatos.contains( Formato.DOCX )
      || formatos.contains( Formato.IMAGENES );
  }

  /** Captura un documento de la tanda sin tocar los checks de secciones; null si fallo. (UI) */
  private Captura capturarSuelta( Documento d ) {
    try {
      Captura c = d.cargado ? archivos.get( d.id ) : servicio.capturar( d.id );
      if ( c == null ) {
        throw new IllegalStateException( r.f( "doc_closed", d.nombre ) );
      }
      return c;
    } catch ( Throwable t ) {
      fallo( t );
      return null;
    }
  }

  /**
   * El pedido de otro documento de la tanda: las secciones marcadas que le aplican; sin linaje elegido,
   * sin perfil y sin consultar la base (esas opciones son del documento elegido arriba), sin narrativa.
   */
  private Pedido pedidoPara( Pedido base, Captura c ) {
    Pedido p = new Pedido();
    p.idioma = base.idioma;
    List<String> ids = new ArrayList<String>();
    List<Disponibilidad> disp = null;
    try {
      disp = servicio.disponibles( c, idiomaUi );
    } catch ( Throwable t ) {
      servicio.log( "Pentaho Live: could not tell which sections apply", t );
    }
    for ( String id : base.secciones ) {
      if ( SeccionId.PERFIL.equals( id ) || SeccionId.NARRATIVA.equals( id ) ) {
        continue;
      }
      boolean aplica = disp == null;
      if ( disp != null ) {
        for ( Disponibilidad x : disp ) {
          if ( x != null && id.equals( x.id ) ) {
            aplica = x.aplica;
          }
        }
      }
      if ( aplica ) {
        ids.add( id );
      }
    }
    p.secciones = ids;
    p.perfilFilas = base.perfilFilas;
    p.metricas = base.metricas;
    p.timeoutMs = base.timeoutMs;
    return p;
  }

  /**
   * Exporta varios documentos: cada uno con sus archivos (nombres sin pisarse) o todos en un informe
   * combinado. Captura todo en la UI; el linaje elegido y el perfil son solo del documento de arriba (y se
   * pregunta solo por el); con «Todo en un archivo» las conclusiones van al informe combinado. Reusa la
   * vista completa del documento elegido si sirve. La vista previa sigue siendo de un documento. (UI)
   */
  private void exportarVarios( final List<Documento> docs, final Documento sel, final List<Formato> formatos,
    final File carpeta ) {
    final boolean combinar = documentos.combinar();
    final String titulo = documentos.titulo();
    final Pedido base = pedido( false, Pedido.METRICAS_ULTIMA );
    final List<Captura> cs = new ArrayList<Captura>();
    final List<Pedido> ps = new ArrayList<Pedido>();
    Captura cSel = null;
    for ( Documento x : docs ) {
      boolean esSel = sel != null && x.id.equals( sel.id );
      Captura c = esSel ? capturar( x ) : capturarSuelta( x );
      if ( c == null ) {
        return;
      }
      Pedido p;
      if ( esSel ) {
        cSel = c;
        p = pedido( false, Pedido.METRICAS_ULTIMA );
        if ( combinar ) {
          p.narrativa = null; // las conclusiones van al informe combinado
          if ( p.secciones != null ) {
            p.secciones.remove( SeccionId.NARRATIVA );
          }
        }
      } else {
        p = pedidoPara( base, c );
      }
      cs.add( c );
      ps.add( p );
    }
    final Completo previo = completo;
    if ( cSel != null ) {
      Pedido pSel = ps.get( indice( docs, sel ) );
      boolean perfila = pSel.secciones != null && pSel.secciones.contains( SeccionId.PERFIL );
      if ( perfila && !( previo != null && previo.sirve( sel.id, firma( pSel, tema ), firmaDoc( cSel ) ) )
        && !confirmarPerfil( cSel, pSel ) ) {
        return;
      }
    }
    final Narrativa conclusiones = combinar ? base.narrativa : null;
    final Tema t = tema.copia();
    final boolean navegador = necesitaNavegador( formatos );
    final String idioma = base.idioma;
    lanzar( true, r.s( "status_exporting_start" ), ( gen, pr ) -> {
      List<Informe> infs = new ArrayList<Informe>();
      for ( int i = 0; i < cs.size(); i++ ) {
        if ( pr.cancelado() ) {
          return null;
        }
        Documento x = docs.get( i );
        pr.paso( r.f( "status_building_doc", x.nombre, i + 1, cs.size() ), i, cs.size() );
        Informe inf = null;
        if ( sel != null && x.id.equals( sel.id ) && previo != null && previo.sirve( x.id, firma( ps.get( i ), t ),
          firmaDoc( cs.get( i ) ) ) ) {
          inf = previo.informe;
        }
        if ( inf == null ) {
          inf = esperar( servicio.armar( cs.get( i ), ps.get( i ), t, pr ), ps.get( i ), pr );
          if ( inf == null ) {
            return null;
          }
        }
        infs.add( inf );
      }
      List<Informe> salen = new ArrayList<Informe>();
      List<String> bases = new ArrayList<String>();
      if ( combinar ) {
        Informe comb = servicio.combinar( infs, titulo, idioma, t, conclusiones );
        salen.add( comb );
        bases.add( servicio.nombreBase( comb, t ) );
      } else {
        salen.addAll( infs );
        for ( Informe inf : infs ) {
          bases.add( servicio.nombreBase( inf, t ) );
        }
        bases = basesUnicas( bases );
      }
      if ( !carpeta.isDirectory() && !carpeta.mkdirs() ) {
        throw new IOException( r.f( "folder_error", carpeta.getPath() ) );
      }
      int archivosEscritos = 0;
      List<String> avisos = new ArrayList<String>();
      int total = salen.size() * formatos.size();
      int hecho = 0;
      for ( int k = 0; k < salen.size(); k++ ) {
        for ( Formato f : formatos ) {
          if ( pr.cancelado() ) {
            return null;
          }
          pr.paso( r.f( "status_exporting", nombreFormato( f ) ), hecho++, total );
          Resultado res = servicio.exportar( salen.get( k ), t, f, servicio.archivo( carpeta, bases.get( k ), f ),
            pr );
          if ( res != null ) {
            archivosEscritos += contar( res, f );
            for ( String a : res.avisos ) {
              if ( a != null && !avisos.contains( a ) ) {
                avisos.add( a );
              }
            }
          }
        }
      }
      enUi( gen, () -> {
        ultimaCarpeta = carpeta;
        exportar.exportado( carpeta );
      } );
      String fin = r.f( "status_exported_n", docs.size(), archivosEscritos, carpeta.getPath() );
      if ( !avisos.isEmpty() ) {
        fin = r.f( "status_more", fin, avisos.get( 0 ) );
      } else if ( navegador && !servicio.hayNavegador( t ) ) {
        fin = r.f( "status_more", fin, r.s( "no_browser" ) );
      }
      return fin;
    } );
  }

  private static int indice( List<Documento> docs, Documento d ) {
    for ( int i = 0; i < docs.size(); i++ ) {
      if ( docs.get( i ).id.equals( d.id ) ) {
        return i;
      }
    }
    return 0;
  }

  /** Los nombres base sin repetir (sin distinguir mayusculas): "x", "x-2", "x-3"... */
  static List<String> basesUnicas( List<String> bases ) {
    List<String> out = new ArrayList<String>();
    java.util.Set<String> usadas = new java.util.HashSet<String>();
    for ( String b : bases ) {
      String x = b == null || b.trim().isEmpty() ? "report" : b.trim();
      String y = x;
      for ( int n = 2; !usadas.add( y.toLowerCase( java.util.Locale.ROOT ) ); n++ ) {
        y = x + "-" + n;
      }
      out.add( y );
    }
    return out;
  }

  /**
   * «Añadir transformaciones del job»: con un job elegido arriba, carga (en el worker, sin abrir pestanas)
   * lo que ejecuta, lo pone en la lista justo despues del job y lo marca; lo que ya esta abierto en una
   * pestana se marca sin cargarlo otra vez. (UI)
   */
  void agregarTransformacionesDeJob() {
    try {
      if ( pesado ) {
        return;
      }
      final Documento d = barra.seleccionado();
      if ( d == null || !d.esJob() ) {
        estado.error( r.s( "docs_job_no_job" ) );
        return;
      }
      final Captura c = capturarSuelta( d );
      if ( c == null ) {
        return;
      }
      final List<Documento> abiertos = new ArrayList<Documento>();
      try {
        List<Documento> l = servicio.documentos();
        if ( l != null ) {
          abiertos.addAll( l );
        }
      } catch ( Throwable t ) {
        servicio.log( "Pentaho Live: could not list the open documents", t );
      }
      lanzar( true, r.s( "docs_job" ), ( gen, pr ) -> {
        List<String> rutas = servicio.transformacionesDeJob( c );
        if ( rutas == null || rutas.isEmpty() ) {
          return r.s( "docs_job_none" );
        }
        final List<String> ids = new ArrayList<String>();
        final List<Object[]> nuevos = new ArrayList<Object[]>();
        for ( String ruta : rutas ) {
          if ( pr.cancelado() ) {
            return null;
          }
          File f = new File( ruta ).getAbsoluteFile();
          String abierto = null;
          for ( Documento x : abiertos ) {
            if ( !x.cargado && x.archivo != null && new File( x.archivo ).getAbsoluteFile().equals( f ) ) {
              abierto = x.id;
            }
          }
          if ( abierto != null ) {
            ids.add( abierto );
            continue;
          }
          Captura h = servicio.capturarArchivo( f.getPath(), display );
          if ( h != null ) {
            String id = "file:" + f.getPath();
            ids.add( id );
            nuevos.add( new Object[] { id, h, f } );
          }
        }
        enUi( gen, () -> {
          for ( Object[] n : nuevos ) {
            String id = (String) n[ 0 ];
            Captura h = (Captura) n[ 1 ];
            File f = (File) n[ 2 ];
            String tipo = h.tipo != null ? h.tipo : ( f.getName().toLowerCase().endsWith( ".kjb" ) ? Captura.JOB
              : Captura.TRANSFORMACION );
            archivos.put( id, h );
            for ( Iterator<Documento> it = cargados.iterator(); it.hasNext(); ) {
              if ( it.next().id.equals( id ) ) {
                it.remove();
              }
            }
            cargados.add( new Documento( id, h.nombre != null ? h.nombre : f.getName(), tipo, f.getPath(), false,
              true ) );
          }
          documentos.agregados( d.id, ids );
          if ( !documentos.esVarios() ) {
            documentos.varios.setSelection( true );
            documentos.uno.setSelection( false );
            documentos.modo();
          }
          refrescarDocumentos();
          barra.seleccionar( d.id );
        } );
        return r.f( "docs_job_added", ids.size() );
      } );
    } catch ( Throwable t ) {
      fallo( t );
    }
  }

  // ---------------------------------------------------------------- acciones internas (UI)

  void vistaCompleta( String metricas, boolean medido ) {
    try {
      if ( pesado ) {
        return;
      }
      debounce++;
      Documento d = barra.seleccionado();
      if ( d == null ) {
        estado.error( r.s( "no_document" ) );
        return;
      }
      Captura c = capturar( d );
      if ( c == null ) {
        return;
      }
      Pedido ped = pedido( false, metricas );
      if ( medido && !ped.secciones.contains( SeccionId.METRICAS ) ) {
        ped.secciones.add( SeccionId.METRICAS );
        ordenar( ped.secciones );
      }
      if ( !confirmarPerfil( c, ped ) ) {
        return;
      }
      lanzarVista( d, c, ped, false, medido );
    } catch ( Throwable t ) {
      fallo( t );
    }
  }

  void vistaPreviaLiviana() {
    try {
      if ( pesado ) {
        livianaPendiente = true;
        return;
      }
      Documento d = barra.seleccionado();
      if ( d == null ) {
        vista.vaciar();
        estado.listo( r.s( "no_documents" ) );
        return;
      }
      Captura c = capturar( d );
      if ( c == null ) {
        return;
      }
      lanzarVista( d, c, pedido( true, Pedido.METRICAS_NINGUNA ), true, false );
    } catch ( Throwable t ) {
      fallo( t );
    }
  }

  /** Arma sola la vista liviana dentro de DEBOUNCE_MS, salvo que llegue otra orden antes. */
  void programarLiviana() {
    final int token = ++debounce;
    display.timerExec( DEBOUNCE_MS, () -> {
      if ( token == debounce && !raiz.isDisposed() ) {
        vistaPreviaLiviana();
      }
    } );
  }

  /** El usuario eligio otro documento en el combo. */
  void documentoElegido() {
    programarLiviana();
  }

  /** Boton de refrescar: relee las pestanas y rearma la vista liviana. */
  void refrescar() {
    try {
      refrescarDocumentos();
      programarLiviana();
    } catch ( Throwable t ) {
      fallo( t );
    }
  }

  /** Cambio el idioma del informe. */
  void idiomaCambiado() {
    if ( ultimoEsCompleto ) {
      estado.info( r.s( "status_language_f5" ) );
    } else {
      programarLiviana();
    }
  }

  /** «Abrir archivo…»: carga un .ktr/.kjb sin abrir pestana (FileDialog por clic). */
  void abrirArchivo() {
    try {
      if ( pesado ) {
        return;
      }
      FileDialog fd = new FileDialog( raiz.getShell(), SWT.OPEN );
      fd.setText( r.s( "open_file" ) );
      fd.setFilterExtensions( new String[] { "*.ktr;*.kjb", "*.*" } );
      fd.setFilterNames( new String[] { r.s( "file_filter" ), r.s( "file_filter_all" ) } );
      String ruta = fd.open();
      if ( ruta != null ) {
        cargarArchivo( ruta );
      }
    } catch ( Throwable t ) {
      fallo( t );
    }
  }

  /** Carga el archivo en el worker y lo agrega al combo (sin dialogo: lo usa abrirArchivo). */
  void cargarArchivo( final String ruta ) {
    final File f = new File( ruta ).getAbsoluteFile();
    lanzar( true, r.f( "status_loading_file", f.getName() ), ( gen, pr ) -> {
      final Captura c = servicio.capturarArchivo( f.getPath(), display );
      if ( c == null ) {
        throw new IOException( r.f( "file_load_error", f.getName(), "null" ) );
      }
      enUi( gen, () -> {
        String id = "file:" + f.getPath();
        String tipo = c.tipo != null ? c.tipo : ( f.getName().toLowerCase().endsWith( ".kjb" ) ? Captura.JOB
          : Captura.TRANSFORMACION );
        Documento d = new Documento( id, c.nombre != null ? c.nombre : f.getName(), tipo, f.getPath(), false, true );
        archivos.put( id, c );
        for ( Iterator<Documento> it = cargados.iterator(); it.hasNext(); ) {
          if ( it.next().id.equals( id ) ) {
            it.remove();
          }
        }
        cargados.add( d );
        refrescarDocumentos();
        barra.seleccionar( id );
        livianaPendiente = true; // corre al terminar esta tarea
      } );
      return r.f( "status_file_loaded", f.getName() );
    } );
  }

  /** «Abrir en el navegador»: HTML interactivo en temporales y Program.launch. */
  void abrirEnNavegador() {
    try {
      if ( pesado ) {
        return;
      }
      final Documento d = barra.seleccionado();
      if ( d == null ) {
        estado.error( r.s( "no_document" ) );
        return;
      }
      final Informe hecho = d.id.equals( ultimoDoc ) ? ultimoInforme : null;
      final Captura c = hecho == null ? capturar( d ) : null;
      if ( hecho == null && c == null ) {
        return;
      }
      final Pedido ped = hecho == null ? pedido( true, Pedido.METRICAS_NINGUNA ) : null;
      final Tema t = tema.copia();
      final File destino = new File( new File( new File( servicio.temporales(), "vista" ), carpetaSegura( d.id ) ),
        "informe.html" );
      lanzar( true, r.s( "status_opening_browser" ), ( gen, pr ) -> {
        Informe inf = hecho != null ? hecho : esperar( servicio.armar( c, ped, t, pr ), ped, pr );
        if ( inf == null ) {
          return null;
        }
        destino.getParentFile().mkdirs();
        Resultado res = servicio.exportar( inf, t, Formato.HTML, destino, pr );
        final File f = res != null && !res.archivos.isEmpty() ? res.archivos.get( 0 ) : destino;
        enUi( gen, () -> servicio.abrir( f ) );
        return r.s( "status_opened_browser" );
      } );
    } catch ( Throwable t ) {
      fallo( t );
    }
  }

  /** «Abrir carpeta» despues de exportar. */
  void abrirCarpeta() {
    try {
      if ( ultimaCarpeta != null ) {
        servicio.abrir( ultimaCarpeta );
      }
    } catch ( Throwable t ) {
      fallo( t );
    }
  }

  /** «Medir…»: confirma con los steps que escriben y arma la vista completa con run_copy. */
  void medir() {
    try {
      if ( pesado ) {
        return;
      }
      Documento d = barra.seleccionado();
      if ( d == null ) {
        estado.error( r.s( "no_document" ) );
        return;
      }
      Captura c = capturar( d );
      if ( c == null ) {
        return;
      }
      List<String> escriben = servicio.pasosQueEscriben( c );
      String mensaje;
      if ( escriben == null || escriben.isEmpty() ) {
        mensaje = r.f( "measure_confirm_nowrite", d.nombre );
      } else {
        StringBuilder sb = new StringBuilder();
        for ( String s : escriben ) {
          sb.append( "\n  - " ).append( s );
        }
        mensaje = r.f( "measure_confirm", d.nombre, sb.toString() );
      }
      if ( !servicio.confirmar( raiz.getShell(), r.s( "measure_title" ), mensaje ) ) {
        return;
      }
      secciones.marcar( SeccionId.METRICAS, true );
      vistaCompleta( Pedido.METRICAS_COPIA, true );
    } catch ( Throwable t ) {
      fallo( t );
    }
  }

  /** El usuario marco «consultar la base»: se avisa que se va a conectar. */
  boolean confirmarConsultaBase() {
    return servicio.confirmar( raiz.getShell(), r.s( "query_db_title" ), r.s( "query_db_confirm" ) );
  }

  /** El usuario marco «Perfil de datos»: si se muestran los valores reales, lo avisa en el estado (§2.4.3). */
  void perfilMarcado() {
    try {
      if ( tema == null || tema.perfil == null || tema.perfil.mostrarValores ) {
        estado.info( r.s( "profile_privacy" ) );
      }
    } catch ( Throwable t ) {
      fallo( t );
    }
  }

  /**
   * Si el pedido perfila un step que tiene steps que escriben aguas arriba, pregunta antes: el perfil
   * ejecuta una copia hasta ese step y esos steps escriben de verdad (diseno §2.4.3). true = seguir. (UI)
   */
  private boolean confirmarPerfil( Captura c, Pedido p ) {
    if ( p == null || p.secciones == null || !p.secciones.contains( SeccionId.PERFIL ) || p.pasosPerfil()
      .isEmpty() ) {
      return true;
    }
    List<String> pasos = p.pasosPerfil();
    String texto = pasos.size() == 1 ? servicio.confirmacionPerfil( c, pasos.get( 0 ), idiomaUi ) : servicio
      .confirmacionPerfil( c, pasos, idiomaUi );
    return texto == null || servicio.confirmar( raiz.getShell(), r.s( "profile_title" ), texto );
  }

  /** «Guardar como predeterminado»: identidad, colores, logo, formatos, carpeta e idioma. */
  void guardarPredeterminado() {
    try {
      tema.salida.formatos = exportar.claves();
      tema.salida.carpeta = exportar.carpeta();
      tema.idioma = barra.idioma();
      servicio.guardarTema( tema );
      estado.listo( r.s( "saved_default" ) );
    } catch ( Throwable t ) {
      servicio.log( "Pentaho Live: could not save the report settings", t );
      estado.error( r.f( "save_failed", mensaje( t ) ) );
    }
  }

  /** show_report: documento, secciones, idioma y narrativa del pedido, y vista completa. */
  void aplicar( String docId, Pedido p ) {
    aplicar( docId, p, null );
  }

  /**
   * show_report (y build_report con show_in_spoon, que trae {@code hecho}): documento, secciones, idioma y
   * narrativa del pedido, y la vista completa. Nunca ejecuta nada: el perfil (corre una copia hasta ese
   * step y sus escritores escriben) y Medir quedan para un clic del usuario, con su confirmacion. Si hay
   * una tarea del usuario en curso (exportar, Medir) no se la corta: el pedido espera a que termine.
   */
  void aplicar( String docId, Pedido p, Informe hecho ) {
    try {
      if ( pesado ) {
        mostrarPendiente = new Object[] { docId, p, hecho };
        estado.info( r.s( "status_show_queued" ) );
        return;
      }
      debounce++;
      refrescarDocumentos();
      if ( docId != null && !barra.seleccionar( docId ) ) {
        estado.error( r.f( "doc_not_found", docId ) );
        return;
      }
      Documento d = barra.seleccionado();
      if ( d == null ) {
        estado.error( r.s( "no_documents" ) );
        return;
      }
      Captura c = capturar( d );
      if ( c == null ) {
        return;
      }
      String metricas = Pedido.METRICAS_ULTIMA;
      if ( p != null ) {
        if ( p.idioma != null ) {
          barra.idioma( Rotulos.normalizar( p.idioma ) );
        }
        secciones.aplicar( p, tema );
        narrativaExterna = p.narrativa;
        narrativaExternaDoc = d.id;
        if ( p.narrativa != null && p.narrativa.conclusiones != null ) {
          secciones.conclusiones( p.narrativa.conclusiones );
        }
        if ( Pedido.METRICAS_NINGUNA.equals( p.metricas ) ) {
          metricas = Pedido.METRICAS_NINGUNA; // run_copy nunca sin la confirmacion del usuario
        }
      }
      relayout();
      Pedido ped = pedido( false, metricas );
      // por el MCP no se ejecuta nada: el perfil corre una copia de la transformacion hasta su step
      boolean sinPerfil = hecho == null && ped.secciones != null && ped.secciones.remove( SeccionId.PERFIL );
      if ( p != null ) {
        ped.titulo = p.titulo;
        if ( p.timeoutMs > 0 ) {
          ped.timeoutMs = p.timeoutMs;
        }
      }
      lanzarVista( d, c, ped, false, false, hecho );
      if ( sinPerfil ) {
        estado.info( r.s( "status_profile_skipped" ) );
      }
    } catch ( Throwable t ) {
      fallo( t );
    }
  }

  // ---------------------------------------------------------------- tareas

  /** Una tarea del worker: devuelve el texto final del estado (null si se cancelo). */
  interface Tarea {
    String correr( int gen, Progreso pr ) throws Exception;
  }

  private void lanzarVista( final Documento d, final Captura c, final Pedido ped, final boolean liviano,
                            final boolean medido ) {
    lanzarVista( d, c, ped, liviano, medido, null );
  }

  /** Con {@code hecho} (el informe de build_report) no se arma nada: solo se muestra. */
  private void lanzarVista( final Documento d, final Captura c, final Pedido ped, final boolean liviano,
                            final boolean medido, final Informe hecho ) {
    if ( hecho == null && ( ped.secciones == null || ped.secciones.isEmpty() ) ) {
      estado.listo( r.s( "status_no_sections" ) );
      return;
    }
    final Tema t = tema.copia();
    lanzar( !liviano, r.s( liviano ? "status_light" : "status_building" ), ( gen, pr ) -> {
      final Informe inf = hecho != null ? hecho : esperar( servicio.armar( c, ped, t, pr ), ped, pr );
      if ( inf == null || pr.cancelado() ) {
        return null;
      }
      pr.paso( r.s( "status_rendering" ), 0, 0 );
      final File f;
      if ( motorEdge ) {
        // Edge embebido (swt.jar nuevo, fase 2): el HTML interactivo directo, Mermaid incluido
        File destino = new File( new File( new File( servicio.temporales(), "vista" ), carpetaSegura( d.id ) ),
          "interactivo.html" );
        destino.getParentFile().mkdirs();
        Resultado res = servicio.exportar( inf, t, Formato.HTML, destino, pr );
        f = res != null && !res.archivos.isEmpty() ? res.archivos.get( 0 ) : destino;
      } else {
        f = servicio.vistaPrevia( inf, t, false, pr );
      }
      final boolean diagramas = !motorEdge && tieneMermaid( inf );
      final boolean navegador = diagramas && servicio.hayNavegador( t );
      final String fp = liviano ? null : firma( ped, t );
      final String fd = liviano ? null : firmaDoc( c );
      enUi( gen, () -> {
        vista.mostrar( f, diagramas && !navegador ? r.s( "band_diagrams" ) : null );
        ultimoInforme = inf;
        ultimoDoc = d.id;
        ultimoEsCompleto = !liviano;
        // el de build_report se armo con los ajustes guardados, no con los del panel: no se reusa al exportar
        if ( !liviano && hecho == null ) {
          completo = new Completo( d.id, fp, fd, inf, medido );
        }
      } );
      if ( navegador && !pr.cancelado() ) {
        pr.paso( r.s( "status_diagrams" ), 0, 0 );
        try {
          final File f2 = servicio.vistaPrevia( inf, t, true, pr );
          enUi( gen, () -> vista.recargar( f2 ) );
        } catch ( Exception e ) {
          servicio.log( "Pentaho Live: the diagrams could not be pre-rendered", e );
          enUi( gen, () -> vista.banda( r.s( "band_diagrams" ) ) );
        }
      }
      int n = inf.secciones == null ? 0 : inf.secciones.size();
      return liviano ? r.s( "status_light_done" ) : n == 1 ? r.s( "status_preview_done_1" )
        : r.f( "status_preview_done", n );
    } );
  }

  private void lanzar( final boolean esPesado, String textoInicial, final Tarea tarea ) {
    final int gen = ++generacion;
    pesado = esPesado;
    exportar.ocupado( esPesado );
    estado.trabajando( textoInicial, gen );
    final Progreso pr = estado.progreso( gen );
    try {
      servicio.enSegundoPlano( () -> {
        String fin = null;
        Throwable error = null;
        try {
          fin = tarea.correr( gen, pr );
        } catch ( Throwable t ) {
          error = t;
          servicio.log( "Pentaho Live: report task failed", t );
        }
        final String texto = fin;
        final Throwable e = error;
        enUi( gen, () -> terminar( texto, e ) );
        return null;
      } );
    } catch ( Throwable t ) {
      terminar( null, t );
    }
  }

  private void terminar( String texto, Throwable error ) {
    pesado = false;
    exportar.ocupado( false );
    if ( error != null ) {
      estado.error( r.f( "status_error", mensaje( error ) ) );
    } else if ( avisoTema != null ) {
      estado.error( r.f( "status_more", texto != null ? texto : r.s( "status_ready" ), avisoTema ) );
      avisoTema = null;
    } else {
      estado.listo( texto != null ? texto : r.s( "status_ready" ) );
    }
    if ( mostrarPendiente != null ) {
      // el show_report que espero a la tarea del usuario; reemplaza a la vista liviana pendiente
      Object[] x = mostrarPendiente;
      mostrarPendiente = null;
      livianaPendiente = false;
      aplicar( (String) x[ 0 ], (Pedido) x[ 1 ], (Informe) x[ 2 ] );
      return;
    }
    if ( livianaPendiente ) {
      livianaPendiente = false;
      programarLiviana();
    }
  }

  /** Espera el informe desde el worker, de a 200 ms, hasta el tope del pedido o la cancelacion. */
  private Informe esperar( Future<Informe> f, Pedido ped, Progreso pr ) throws Exception {
    long limite = System.currentTimeMillis() + Math.max( ped.timeoutMs, 1000 ) + MARGEN_ESPERA_MS;
    while ( true ) {
      if ( pr.cancelado() ) {
        return null; // armar ve el mismo Progreso y corta solo
      }
      try {
        return f.get( 200, TimeUnit.MILLISECONDS );
      } catch ( TimeoutException e ) {
        if ( System.currentTimeMillis() > limite ) {
          f.cancel( false );
          throw new TimeoutException( r.f( "status_timeout", ( ped.timeoutMs + MARGEN_ESPERA_MS ) / 1000 ) );
        }
      } catch ( ExecutionException e ) {
        Throwable c = e.getCause();
        throw c instanceof Exception ? (Exception) c : new RuntimeException( c );
      }
    }
  }

  /** asyncExec si la generacion sigue vigente y el panel vive. Desde cualquier hilo. */
  void enUi( final int gen, final Runnable accion ) {
    try {
      if ( display.isDisposed() ) {
        return;
      }
      display.asyncExec( () -> {
        if ( !raiz.isDisposed() && gen == generacion ) {
          try {
            accion.run();
          } catch ( Throwable t ) {
            fallo( t );
          }
        }
      } );
    } catch ( Throwable ignorada ) {
      // display cerrandose
    }
  }

  boolean vigente( int gen ) {
    return gen == generacion && !raiz.isDisposed();
  }

  // ---------------------------------------------------------------- documento y pedido

  /** Relee las pestanas de Spoon (mas los archivos cargados) y llena el combo. (UI) */
  List<Documento> refrescarDocumentos() {
    List<Documento> docs = new ArrayList<Documento>();
    try {
      List<Documento> abiertos = servicio.documentos();
      if ( abiertos != null ) {
        docs.addAll( abiertos );
      }
    } catch ( Throwable t ) {
      servicio.log( "Pentaho Live: could not list the open documents", t );
    }
    docs.addAll( cargados );
    barra.cargar( docs );
    documentos.cargar( docs );
    if ( docs.isEmpty() ) {
      vista.vaciar();
      estado.listo( r.s( "no_documents" ) );
    }
    return docs;
  }

  /** Captura el documento (en la UI) y actualiza los checks de secciones; null si fallo. */
  private Captura capturar( Documento d ) {
    try {
      Captura c = d.cargado ? archivos.get( d.id ) : servicio.capturar( d.id );
      if ( c == null ) {
        throw new IllegalStateException( r.f( "doc_closed", d.nombre ) );
      }
      List<Disponibilidad> disp = null;
      try {
        disp = servicio.disponibles( c, idiomaUi );
      } catch ( Throwable t ) {
        servicio.log( "Pentaho Live: could not tell which sections apply", t );
      }
      secciones.actualizar( d.id, c, disp, tema );
      relayout();
      return c;
    } catch ( Throwable t ) {
      fallo( t );
      return null;
    }
  }

  /** El pedido segun los controles. */
  Pedido pedido( boolean liviano, String metricas ) {
    Pedido p = new Pedido();
    p.idioma = barra.idioma();
    List<String> ids = secciones.seleccionadas();
    if ( liviano ) {
      ids.retainAll( LIVIANAS );
    }
    List<String> pasos = secciones.perfilSteps();
    if ( ids.contains( SeccionId.PERFIL ) && pasos.isEmpty() ) {
      ids.remove( SeccionId.PERFIL ); // sin step la seccion no corre (ejecuta la transformacion)
    }
    p.secciones = ids;
    p.linajeStep = secciones.linajeStep();
    p.perfilStep = pasos.isEmpty() ? null : pasos.get( 0 );
    p.perfilSteps = pasos.size() > 1 ? new ArrayList<String>( pasos.subList( 1, pasos.size() ) )
      : new ArrayList<String>();
    p.perfilFilas = secciones.perfilFilas();
    p.metricas = metricas;
    p.destinoConsultarBase = !liviano && secciones.consultarBase();
    p.calidadProfunda = !liviano && secciones.calidadProfunda();
    p.narrativa = narrativa();
    return p;
  }

  /** La narrativa del usuario (conclusiones) mezclada con la que trajo show_report para este documento. */
  private Narrativa narrativa() {
    String texto = secciones.conclusiones();
    Documento d = barra.seleccionado();
    Narrativa ext = narrativaExterna != null && d != null && d.id.equals( narrativaExternaDoc ) ? narrativaExterna
      : null;
    if ( ext == null && texto.isEmpty() ) {
      return null;
    }
    Narrativa n = new Narrativa();
    n.origen = Narrativa.USUARIO;
    if ( ext != null ) {
      n.resumen = ext.resumen;
      if ( ext.porSeccion != null ) {
        n.porSeccion.putAll( ext.porSeccion );
      }
      if ( ext.origen != null ) {
        n.origen = ext.origen;
      }
    }
    n.conclusiones = texto.isEmpty() ? null : texto;
    if ( !texto.isEmpty() && ( ext == null || !texto.equals( ext.conclusiones ) ) ) {
      n.origen = Narrativa.USUARIO; // el usuario la escribio o la cambio
    }
    return n;
  }

  // ---------------------------------------------------------------- ayudas

  Tema tema() {
    return tema;
  }

  void relayout() {
    if ( !izquierda.isDisposed() ) {
      izquierda.layout( true, true );
      ajustarScroll();
    }
  }

  /**
   * La columna de opciones se arma al ancho visible (los campos se angostan) y solo pasa a scroll
   * horizontal por debajo de {@link #ANCHO_MINIMO_IZQ}; el alto es el que pide ese ancho.
   */
  private void ajustarScroll() {
    if ( scroll.isDisposed() ) {
      return;
    }
    int ancho = Math.max( ANCHO_MINIMO_IZQ, scroll.getClientArea().width );
    Point p = izquierda.computeSize( ancho, SWT.DEFAULT );
    scroll.setMinSize( ANCHO_MINIMO_IZQ, p.y );
  }

  private boolean focoDentro() {
    Control c = display.getFocusControl();
    while ( c != null ) {
      if ( c == raiz ) {
        return true;
      }
      c = c.getParent();
    }
    return false;
  }

  private void alCerrar() {
    try {
      generacion++;
      display.removeFilter( SWT.KeyDown, filtroF5 );
      identidad.liberar();
      estado.liberar();
      if ( actual.get() == this ) {
        actual = new WeakReference<PanelInformes>( null );
      }
    } catch ( Throwable ignorada ) {
      // cerrando Spoon
    }
  }

  private void aplicarPendiente() {
    final String doc;
    final Pedido p;
    final Informe hecho;
    synchronized ( PanelInformes.class ) {
      if ( !hayPendiente ) {
        return;
      }
      hayPendiente = false;
      doc = pendienteDoc;
      p = pendientePedido;
      hecho = pendienteInforme;
      pendienteDoc = null;
      pendientePedido = null;
      pendienteInforme = null;
    }
    display.asyncExec( () -> {
      if ( !raiz.isDisposed() ) {
        aplicar( doc, p, hecho );
      }
    } );
  }

  void fallo( Throwable t ) {
    servicio.log( "Pentaho Live: Reports panel error", t );
    try {
      if ( !raiz.isDisposed() ) {
        estado.error( r.f( "status_error", mensaje( t ) ) );
      }
    } catch ( Throwable ignorada ) {
      // ya en el log
    }
  }

  static String mensaje( Throwable t ) {
    Throwable c = t;
    while ( ( c instanceof ExecutionException || c instanceof java.lang.reflect.InvocationTargetException )
      && c.getCause() != null ) {
      c = c.getCause();
    }
    String m = c.getMessage();
    return m == null || m.trim().isEmpty() ? c.getClass().getSimpleName() : m.trim();
  }

  /** Para Label y Button: '&amp;' es mnemonico en SWT, se escapa como "&amp;&amp;". */
  public static String esc( String s ) {
    return s == null ? "" : s.replace( "&", "&&" );
  }

  /** Log sin lanzar nunca (LogChannel "PentahoLive-Informes"). */
  static void registrar( String texto, Throwable t ) {
    try {
      LogChannel l = new LogChannel( CANAL );
      if ( t == null ) {
        l.logBasic( texto );
      } else {
        l.logError( texto, t );
      }
    } catch ( Throwable ignorada ) {
      // sin Kettle: nada que hacer
    }
  }

  private static Tema temaInicial( Servicio s, String idioma, List<String> avisos ) {
    try {
      Tema t = s.temaGuardado( idioma, avisos );
      return t == null ? new Tema() : t;
    } catch ( Throwable e ) {
      registrar( "Pentaho Live: could not read the report settings; using the defaults", e );
      return new Tema();
    }
  }

  static boolean tieneMermaid( Informe inf ) {
    if ( inf == null || inf.secciones == null ) {
      return false;
    }
    for ( Seccion s : inf.secciones ) {
      if ( s == null || s.bloques == null ) {
        continue;
      }
      for ( Bloque b : s.bloques ) {
        if ( b instanceof Diagrama && ( (Diagrama) b ).mermaid != null && !( (Diagrama) b ).mermaid.trim().isEmpty() ) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Firma del pedido y del tema para reusar la vista completa al exportar. Sin el modo de metricas (lo
   * medido con «Medir…» vale para exportar); con el tema, porque la portada usa autor, institucion y logo.
   */
  static String firma( Pedido p, Tema t ) {
    try {
      JsonNode n = ModeloJson.mapper().valueToTree( p );
      if ( n instanceof ObjectNode ) {
        ( (ObjectNode) n ).remove( "metricas" );
      }
      String tema = t == null ? "" : ModeloJson.mapper().writeValueAsString( t ) + "|"
        + ( t.logoDatos == null ? 0 : Arrays.hashCode( t.logoDatos ) );
      return n.toString() + "|" + tema;
    } catch ( Throwable e ) {
      return null;
    }
  }

  /** Firma del documento capturado: su XML y la ultima corrida. null si no se puede (entonces no se reusa). */
  static String firmaDoc( Captura c ) {
    try {
      String xml = null;
      if ( c.copia instanceof TransMeta ) {
        xml = ( (TransMeta) c.copia ).getXML();
      } else if ( c.copia instanceof JobMeta ) {
        xml = ( (JobMeta) c.copia ).getXML();
      }
      if ( xml == null ) {
        return null;
      }
      Object corrida = c.ultimaTrans != null ? c.ultimaTrans : c.ultimoJob;
      Date fin = c.ultimaTrans != null ? c.ultimaTrans.getEndDate() : c.ultimoJob != null ? c.ultimoJob.getEndDate()
        : null;
      return c.docId + "|" + xml.length() + "|" + xml.hashCode() + "|" + System.identityHashCode( corrida ) + "|"
        + ( fin == null ? 0 : fin.getTime() );
    } catch ( Throwable t ) {
      return null;
    }
  }

  private static void ordenar( List<String> ids ) {
    Collections.sort( ids, ( a, b ) -> SeccionId.ORDEN.indexOf( a ) - SeccionId.ORDEN.indexOf( b ) );
  }

  /**
   * La carpeta de exportacion como ruta absoluta: una relativa (escrita a mano o en informes.json) se toma
   * desde la carpeta del documento, nunca desde data-integration (el directorio de trabajo de Spoon). null
   * si es relativa y el documento no tiene archivo.
   */
  static File absoluta( String texto, Documento d ) {
    File f = new File( texto.trim() );
    if ( f.isAbsolute() ) {
      return f;
    }
    File padre = d == null || d.archivo == null ? null : new File( d.archivo ).getAbsoluteFile().getParentFile();
    return padre == null ? null : new File( padre, texto.trim() );
  }

  private File carpetaSugerida( Documento d ) {
    if ( tema.salida != null && tema.salida.carpeta != null && !tema.salida.carpeta.trim().isEmpty() ) {
      File f = absoluta( tema.salida.carpeta, d );
      if ( f != null ) {
        return f;
      }
    }
    if ( ultimaCarpeta != null ) {
      return ultimaCarpeta;
    }
    if ( d.archivo != null ) {
      File padre = new File( d.archivo ).getAbsoluteFile().getParentFile();
      if ( padre != null ) {
        return new File( padre, "informes" );
      }
    }
    return null;
  }

  private String nombreFormato( Formato f ) {
    switch ( f ) {
      case PDF:
        return r.s( "fmt_pdf" );
      case MARKDOWN:
        return r.s( "fmt_md" );
      case PPTX:
        return r.s( "fmt_pptx" );
      default:
        return r.s( "fmt_html" );
    }
  }

  static String carpetaSegura( String id ) {
    String s = id == null ? "doc" : id.replaceAll( "[^A-Za-z0-9._-]", "_" );
    return s.length() > 60 ? s.substring( s.length() - 60 ) : s;
  }

  /** La ultima vista completa: se reusa al exportar si no cambio ni el documento ni el pedido. */
  private static final class Completo {
    final String docId;
    final String firmaPedido;
    final String firmaDoc;
    final Informe informe;
    final boolean medido;

    Completo( String docId, String firmaPedido, String firmaDoc, Informe informe, boolean medido ) {
      this.docId = docId;
      this.firmaPedido = firmaPedido;
      this.firmaDoc = firmaDoc;
      this.informe = informe;
      this.medido = medido;
    }

    boolean sirve( String doc, String fp, String fd ) {
      return docId.equals( doc ) && firmaPedido != null && firmaPedido.equals( fp ) && firmaDoc != null
        && firmaDoc.equals( fd );
    }
  }
}
