package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.w3c.dom.Element;

import io.github.ivi2511.pentahomcp.CorrectorTextos.Mensaje;
import io.github.ivi2511.pentahomcp.corrector.modelo.BaseDePrueba;
import io.github.ivi2511.pentahomcp.corrector.modelo.ItemRubrica;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.Regla;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.Aviso;
import io.github.ivi2511.pentahomcp.corrector.modelo.TablaSalida;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.ui.PanelInformes;

/**
 * «Crear pack desde esta transformacion» (docs/corrector-diseno.md §9; rediseñado en 0.7.0 para un docente que no es
 * experto): un asistente de 5 pasos sobre la transformacion activa, con la lista de pasos a la izquierda (hecho,
 * actual, pendiente; se vuelve a uno ya hecho con un clic), una frase que explica cada paso, la ayuda de cada campo
 * debajo (que es y un ejemplo) y los errores en rojo al lado del campo. Llama a lo mismo que
 * {@code create_exercise_pack} ({@link CorrectorCrearPack#crear}): el pack y el MCP no cambian.
 * <ol>
 * <li><b>Solucion y salida</b>: titulo, que se compara (las tablas que carga, el archivo que escribe o las filas del
 * ultimo paso, en palabras), donde se guarda el pack, idioma y la copia para alumnos.</li>
 * <li><b>Datos de prueba</b>: el caso oculto (opcional) y, si la solucion usa un servidor de base de datos, la base de
 * prueba de cada conexion: los scripts .sql que estan junto a la solucion se proponen solos
 * ({@link CorrectorScripts}), «Generar el script desde la base actual» lee la base original <b>solo lectura</b>
 * ({@link CorrectorVolcado}) y «Probar conexion» solo crea y borra la base de prueba. «Siguiente» ejecuta la solucion
 * en una JVM hija y arma un pack de prueba en temporales: nada se guarda todavia.</li>
 * <li><b>Que se compara</b>: la salida esperada del caso visible, y que columnas identifican cada fila y cuales no se
 * comparan (casillas por columna); el paso de salida se puede cambiar y volver a ejecutar.</li>
 * <li><b>Reglas y nota</b>: los puntos de cada criterio del resultado y las reglas de diseño sugeridas (opcionales),
 * la escala y el total.</li>
 * <li><b>Revisar y guardar</b>: un resumen (que se compara, casos, base de prueba, nota, que reciben los alumnos) y
 * «Guardar pack», que vuelve a armarlo en el destino, verifica la solucion con el otro idioma y escribe la copia para
 * alumnos. «Guardar pack» ya se puede usar desde el paso 3: el caso comun es abrir, Siguiente, Siguiente, Guardar.</li>
 * </ol>
 * Una Shell modal al panel que no bloquea el hilo (no tiene su propio bucle de eventos): lo pesado va al pool del
 * panel. Solo se abre por un clic. Las contraseñas solo viven en los campos y en la memoria de esta sesion.
 *
 * @dueño C8
 */
final class AsistentePack {

  static final int PAGINAS = 5;
  /** Filas de la salida esperada que se muestran. */
  static final int MAX_FILAS = 30;
  private static final AtomicInteger ANALISIS = new AtomicInteger();

  final PanelCorrector panel;
  final Rotulos t;
  final Shell shell;
  final CorrectorTools.Copia copia;
  /** Temporal de la copia de la solucion y de los packs de analisis; se borra al cerrar. */
  final File tmp;

  // ---- recursos (se liberan al cerrar)
  private final Color azul;
  private final Color verde;
  private final Color gris;
  private final Color rojo;
  private final Color naranja;
  private final Color fondoIzq;
  private final Color fondoError;
  private final Color fondoOk;
  private final Font negrita;
  private final Font grande;

  // ---- armazon
  private final Label[] pasos = new Label[ PAGINAS ];
  private final StackLayout pila = new StackLayout();
  private final Composite contenido;
  private final ScrolledComposite[] tarjetas = new ScrolledComposite[ PAGINAS ];
  private final Composite[] paginas = new Composite[ PAGINAS ];
  private int pagina;
  final Label paso;
  private final Label intro;
  /** El recuadro de errores (rojo) o de exito (verde) sobre los botones. */
  private final Composite caja;
  private final Label cajaTexto;
  final Label estado;
  final Button anterior;
  final Button siguiente;
  final Button guardar;
  final Button usar;
  final Button cerrar;
  /** id de campo → su linea de error (roja, oculta si no hay). */
  private final Map<String, Label> errores = new HashMap<String, Label>();
  /** id de campo → pagina en la que esta. */
  private final Map<String, Integer> paginaDe = new HashMap<String, Integer>();

  /** Los campos de la base de prueba de una conexion de la solucion. */
  static final class FilaBase {

    final BaseDePrueba propuesta;
    Text servidor;
    Text puerto;
    Text base;
    Text usuario;
    Text clave;
    Text scripts;
    Text scriptsOculto;
    Label probado;
    Link generado;
    Label detectados;
    File scriptGenerado;

    FilaBase( BaseDePrueba propuesta ) {
      this.propuesta = propuesta;
    }
  }

  // ---- paso 1
  final Text titulo;
  final Text destino;
  final Combo idioma;
  final Combo captura;
  final Button copiaAlumnos;
  /** Lo que lee y escribe la solucion (solo XML: nada corre en Spoon). */
  final CorrectorBases.Inventario inventario;
  /** Una opcion por item de {@link #captura}: {codigo de captura, tabla o step (o null), texto}. */
  private final List<String[]> capturas = new ArrayList<String[]>();
  // ---- paso 2
  final Text oculto;
  final List<FilaBase> filasBase = new ArrayList<FilaBase>();
  /** Correr dos veces cada caso (una carga que duplica filas al repetirla pierde puntos). */
  final Button dosVeces;
  /** Conexion → scripts encontrados junto a la solucion. */
  private final Map<String, List<File>> detectados;
  // ---- paso 3
  final Label entradas;
  final Combo salida;
  final Button reanalizar;
  final Label pendiente;
  final Combo tablaVista;
  final Button tablaComparar;
  private final Composite cajaColumnas;
  final Label columnas;
  final Table esperada;
  final Label avisos;
  private List<TablaSalida> tablasAnalizadas = new ArrayList<TablaSalida>();
  private Map<String, CorrectorTabla> esperadasTablas = new LinkedHashMap<String, CorrectorTabla>();
  /** Tabla (sin mayusculas) → columnas clave / ignoradas, como las deja el docente. */
  private final Map<String, Set<String>> clavesTabla = new LinkedHashMap<String, Set<String>>();
  private final Map<String, Set<String>> ignorarTabla = new LinkedHashMap<String, Set<String>>();
  private final Set<String> tablasFuera = new LinkedHashSet<String>();
  /** Captura file/step: las columnas clave elegidas. */
  private final Set<String> clavesSalida = new LinkedHashSet<String>();
  private CorrectorTabla esperadaUnica;
  // ---- paso 4
  private final Composite cajaRubrica;
  private final List<Button> marcasReglas = new ArrayList<Button>();
  private final Map<String, Text> puntos = new LinkedHashMap<String, Text>();
  final Text escala;
  final Label total;
  // ---- paso 5
  private final Composite cajaResumen;

  private final List<String> steps = new ArrayList<String>();
  private CorrectorCrearPack.Creado analisis;
  private Pack analizado;
  /** Lo que se uso para analizar: si cambia, «Siguiente» en el paso 2 vuelve a ejecutar. */
  private String firmaAnalisis;
  private List<Regla> sugeridas = new ArrayList<Regla>();
  private List<ItemRubrica> rubrica = new ArrayList<ItemRubrica>();
  private Set<String> reglasDeLaRubrica = new LinkedHashSet<String>();
  private boolean trabajando;
  private int gen;
  private File guardado;
  private boolean cerrado;

  /** Arma y abre el asistente. (UI) */
  AsistentePack( PanelCorrector panel, CorrectorTools.Copia copia, File tmp ) {
    this.panel = panel;
    this.t = panel.t;
    this.copia = copia;
    this.tmp = tmp;
    Element raiz = leerSteps();
    CorrectorBases.Inventario inv;
    try {
      inv = raiz == null ? new CorrectorBases.Inventario() : CorrectorBases.inventario( raiz );
    } catch ( Throwable x ) {
      panel.servicio.log( "Pentaho Live: could not read the connections of the solution", x );
      inv = new CorrectorBases.Inventario();
    }
    inventario = inv;
    Map<String, List<File>> det;
    try {
      det = CorrectorScripts.detectar( copia.dirOriginal, raiz );
    } catch ( Throwable x ) {
      panel.servicio.log( "Pentaho Live: could not look for SQL scripts next to the solution", x );
      det = new LinkedHashMap<String, List<File>>();
    }
    detectados = det;
    List<BaseDePrueba> propuestas = CorrectorBases.propuestas( inventario );
    String base = copia.nombre.replaceFirst( "(?i)\\.ktr$", "" );

    shell = new Shell( panel.raiz().getShell(), SWT.DIALOG_TRIM | SWT.RESIZE | SWT.PRIMARY_MODAL );
    shell.setText( t.f( "ui.as_titulo", base ) );
    Display d = shell.getDisplay();
    azul = new Color( d, 0x1F, 0x5F, 0xA8 );
    verde = new Color( d, 0x2E, 0x7D, 0x32 );
    gris = new Color( d, 0x6B, 0x6B, 0x6B );
    rojo = new Color( d, 0xC6, 0x28, 0x28 );
    naranja = new Color( d, 0xA8, 0x5A, 0x00 );
    fondoIzq = new Color( d, 0xF1, 0xF5, 0xFA );
    fondoError = new Color( d, 0xFD, 0xEC, 0xEA );
    fondoOk = new Color( d, 0xE8, 0xF5, 0xE9 );
    FontData[] fd = shell.getFont().getFontData();
    for ( FontData x : fd ) {
      x.setStyle( SWT.BOLD );
    }
    negrita = new Font( d, fd );
    FontData[] fg = shell.getFont().getFontData();
    for ( FontData x : fg ) {
      x.setStyle( SWT.BOLD );
      x.setHeight( x.getHeight() + 3 );
    }
    grande = new Font( d, fg );
    GridLayout g = new GridLayout( 2, false );
    g.marginWidth = 0;
    g.marginHeight = 0;
    g.horizontalSpacing = 0;
    shell.setLayout( g );
    shell.addDisposeListener( e -> alCerrar() );

    // ---- izquierda: la lista de pasos
    Composite izq = new Composite( shell, SWT.NONE );
    izq.setBackground( fondoIzq );
    GridData gi = new GridData( SWT.FILL, SWT.FILL, false, true );
    gi.widthHint = 215;
    izq.setLayoutData( gi );
    GridLayout gli = new GridLayout( 1, false );
    gli.marginWidth = 14;
    gli.marginHeight = 16;
    gli.verticalSpacing = 12;
    izq.setLayout( gli );
    Label cab = new Label( izq, SWT.WRAP );
    cab.setText( t.s( "ui.as_cabecera" ) );
    cab.setFont( negrita );
    cab.setBackground( fondoIzq );
    cab.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    Cursor mano = d.getSystemCursor( SWT.CURSOR_HAND );
    for ( int i = 0; i < PAGINAS; i++ ) {
      final int n = i;
      Label l = new Label( izq, SWT.NONE );
      l.setBackground( fondoIzq );
      l.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
      l.addListener( SWT.MouseUp, e -> {
        if ( alcanzable( n ) ) {
          ir( n );
        }
      } );
      l.setCursor( mano );
      PanelCorrector.id( l, "as_lista:" + ( i + 1 ) );
      pasos[ i ] = l;
    }
    Label ayudaIzq = new Label( izq, SWT.WRAP );
    ayudaIzq.setText( t.s( "ui.as_izq_ayuda" ) );
    ayudaIzq.setForeground( gris );
    ayudaIzq.setBackground( fondoIzq );
    GridData gai = new GridData( SWT.FILL, SWT.END, true, true );
    gai.widthHint = 180;
    ayudaIzq.setLayoutData( gai );

    // ---- derecha: cabecera, paginas, recuadro, estado y botones
    Composite der = new Composite( shell, SWT.NONE );
    der.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, true ) );
    GridLayout gld = new GridLayout( 1, false );
    gld.marginWidth = 16;
    gld.marginHeight = 12;
    der.setLayout( gld );
    paso = new Label( der, SWT.NONE );
    paso.setFont( grande );
    paso.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    PanelCorrector.id( paso, "as_paso" );
    intro = new Label( der, SWT.WRAP );
    GridData gin = new GridData( SWT.FILL, SWT.TOP, true, false );
    gin.widthHint = 600;
    intro.setLayoutData( gin );
    PanelCorrector.id( intro, "as_intro" );
    Label sep = new Label( der, SWT.SEPARATOR | SWT.HORIZONTAL );
    sep.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    contenido = new Composite( der, SWT.NONE );
    contenido.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, true ) );
    contenido.setLayout( pila );
    for ( int i = 0; i < PAGINAS; i++ ) {
      final ScrolledComposite sc = new ScrolledComposite( contenido, SWT.V_SCROLL );
      sc.setExpandHorizontal( true );
      sc.setExpandVertical( true );
      final Composite p = new Composite( sc, SWT.NONE );
      GridLayout gp = new GridLayout( 3, false );
      gp.marginWidth = 2;
      gp.verticalSpacing = 3;
      p.setLayout( gp );
      sc.setContent( p );
      sc.addListener( SWT.Resize, e -> ajustar( sc ) );
      tarjetas[ i ] = sc;
      paginas[ i ] = p;
    }
    caja = new Composite( der, SWT.NONE );
    GridData gc = new GridData( SWT.FILL, SWT.TOP, true, false );
    gc.exclude = true;
    caja.setLayoutData( gc );
    caja.setVisible( false );
    GridLayout glc = new GridLayout( 1, false );
    glc.marginWidth = 10;
    glc.marginHeight = 8;
    caja.setLayout( glc );
    cajaTexto = new Label( caja, SWT.WRAP );
    GridData gct = new GridData( SWT.FILL, SWT.TOP, true, false );
    gct.widthHint = 600;
    cajaTexto.setLayoutData( gct );
    PanelCorrector.id( cajaTexto, "as_recuadro" );
    Label sep2 = new Label( der, SWT.SEPARATOR | SWT.HORIZONTAL );
    sep2.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    Composite pie = new Composite( der, SWT.NONE );
    pie.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    GridLayout glp = new GridLayout( 2, false );
    glp.marginWidth = 0;
    pie.setLayout( glp );
    estado = new Label( pie, SWT.WRAP );
    GridData gs = new GridData( SWT.FILL, SWT.CENTER, true, false );
    gs.widthHint = 300;
    estado.setLayoutData( gs );
    estado.setForeground( gris );
    PanelCorrector.id( estado, "as_estado" );
    Composite bs = new Composite( pie, SWT.NONE );
    bs.setLayoutData( new GridData( SWT.END, SWT.CENTER, false, false ) );
    bs.setLayout( new RowLayout( SWT.HORIZONTAL ) );
    cerrar = boton( bs, "as_cerrar", "ui.as_cerrar", shell::dispose );
    anterior = boton( bs, "as_anterior", "ui.as_anterior", () -> ir( pagina - 1 ) );
    siguiente = boton( bs, "as_siguiente", "ui.as_siguiente", this::siguiente );
    guardar = boton( bs, "as_guardar", "ui.as_guardar", this::guardar );
    usar = boton( bs, "as_usar", "ui.as_usar", this::usar );

    // ---- 1: solucion y salida
    Composite p1 = paginas[ 0 ];
    Label sol = rotulo( p1, "ui.as_solucion" );
    sol.setFont( negrita );
    Label solInfo = new Label( p1, SWT.WRAP );
    solInfo.setText( PanelInformes.esc( resumenSolucion() ) );
    GridData gsi = new GridData( SWT.FILL, SWT.CENTER, true, false, 2, 1 );
    gsi.widthHint = 300;
    solInfo.setLayoutData( gsi );
    PanelCorrector.id( solInfo, "as_solucion" );
    espacio( p1 );
    titulo = campo( p1, 0, "as_titulo", "ui.as_titulo_ejercicio", "ui.as_titulo_ayuda", 0, null );
    titulo.setText( base );
    captura = combo( p1, 0, "as_captura", "ui.as_captura", "ui.as_captura_ayuda" );
    llenarCapturas( !propuestas.isEmpty() );
    for ( String[] c : capturas ) {
      captura.add( c[ 2 ] );
    }
    captura.select( 0 );
    destino = campo( p1, 0, "as_destino", "ui.as_destino", "ui.as_destino_ayuda", 0, "as_elegir_destino" );
    destino.setText( destinoPropuesto( base ).getPath() );
    idioma = combo( p1, 0, "as_idioma", "ui.as_idioma", "ui.as_idioma_ayuda" );
    idioma.setItems( new String[] { "Español", "English", "Português" } );
    idioma.select( "en".equals( panel.idioma ) ? 1 : "pt".equals( panel.idioma ) ? 2 : 0 );
    copiaAlumnos = casilla( p1, 0, "as_copia", "ui.as_copia", "ui.as_copia_ayuda" );
    copiaAlumnos.setSelection( true );

    // ---- 2: datos de prueba
    Composite p2 = paginas[ 1 ];
    Button dos = null;
    if ( !propuestas.isEmpty() ) {
      Label pq = new Label( p2, SWT.WRAP );
      pq.setText( t.s( "ui.as_base_por_que" ) );
      GridData gpq = new GridData( SWT.FILL, SWT.TOP, true, false, 3, 1 );
      gpq.widthHint = 560;
      pq.setLayoutData( gpq );
      PanelCorrector.id( pq, "as_base_por_que" );
      for ( BaseDePrueba b : propuestas ) {
        grupoBase( p2, b );
      }
      dos = casilla( p2, 1, "as_dos_veces", "ui.as_dos_veces", "ui.as_dos_veces_ayuda" );
    } else {
      Label vis = new Label( p2, SWT.WRAP );
      vis.setText( t.s( "ui.as_visible_archivos" ) );
      GridData gv = new GridData( SWT.FILL, SWT.TOP, true, false, 3, 1 );
      gv.widthHint = 560;
      vis.setLayoutData( gv );
      espacio( p2 );
    }
    dosVeces = dos;
    oculto = campo( p2, 1, "as_oculto", "ui.as_oculto", "ui.as_oculto_ayuda", 0, "as_elegir_oculto" );

    // ---- 3: que se compara
    Composite p3 = paginas[ 2 ];
    entradas = etiqueta( p3, "as_entradas" );
    salida = combo( p3, 2, "as_salida", "ui.as_salida", "ui.as_salida_ayuda" );
    salida.add( t.s( "ui.as_salida_auto" ) );
    for ( String s : steps ) {
      salida.add( s );
    }
    salida.select( 0 );
    salida.addListener( SWT.Selection, e -> marcarPendiente() );
    reanalizar = (Button) buscar( "as_reanalizar" );
    pendiente = etiqueta( p3, "as_pendiente" );
    pendiente.setForeground( naranja );
    tablaVista = combo( p3, 2, "as_tabla_vista", "ui.as_tabla_vista", "ui.as_tabla_vista_ayuda" );
    tablaVista.addListener( SWT.Selection, e -> verTabla() );
    tablaComparar = (Button) buscar( "as_tabla_comparar" );
    Label lc = etiqueta( p3, "as_columnas_titulo" );
    lc.setText( t.s( "ui.as_columnas_titulo" ) );
    lc.setFont( negrita );
    Label ac = etiqueta( p3, "as_claves_ayuda" );
    ac.setText( t.s( "ui.as_claves_ayuda" ) );
    ac.setForeground( gris );
    cajaColumnas = new Composite( p3, SWT.NONE );
    cajaColumnas.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false, 3, 1 ) );
    cajaColumnas.setLayout( new GridLayout( 4, false ) );
    PanelCorrector.id( cajaColumnas, "as_columnas_caja" );
    error( p3, 2, "as_columnas_caja" );
    columnas = etiqueta( p3, "as_columnas" );
    columnas.setForeground( gris );
    Label le = etiqueta( p3, "as_esperada_titulo" );
    le.setText( t.s( "ui.as_esperada_vacia" ) );
    le.setFont( negrita );
    esperada = new Table( p3, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL );
    esperada.setHeaderVisible( true );
    esperada.setLinesVisible( true );
    GridData ge = new GridData( SWT.FILL, SWT.FILL, true, true, 3, 1 );
    ge.heightHint = 170;
    ge.widthHint = 500;
    esperada.setLayoutData( ge );
    PanelCorrector.id( esperada, "as_esperada" );
    avisos = etiqueta( p3, "as_avisos" );
    avisos.setForeground( naranja );

    // ---- 4: reglas y nota
    Composite p4 = paginas[ 3 ];
    cajaRubrica = new Composite( p4, SWT.NONE );
    cajaRubrica.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false, 3, 1 ) );
    GridLayout glr = new GridLayout( 3, false );
    glr.marginWidth = 0;
    cajaRubrica.setLayout( glr );
    PanelCorrector.id( cajaRubrica, "as_rubrica" );
    espacio( p4 );
    escala = campo( p4, 3, "as_escala", "ui.as_escala", "ui.as_escala_ayuda", 0, null );
    ( (GridData) escala.getLayoutData() ).widthHint = 70;
    ( (GridData) escala.getLayoutData() ).grabExcessHorizontalSpace = false;
    ( (GridData) escala.getLayoutData() ).horizontalAlignment = SWT.LEFT;
    escala.setText( "10" );
    escala.addListener( SWT.Modify, e -> sumar() );
    total = etiqueta( p4, "as_total" );
    total.setFont( negrita );

    // ---- 5: revisar y guardar
    Composite p5 = paginas[ 4 ];
    cajaResumen = new Composite( p5, SWT.NONE );
    cajaResumen.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false, 3, 1 ) );
    GridLayout gl5 = new GridLayout( 2, false );
    gl5.marginWidth = 0;
    gl5.verticalSpacing = 8;
    cajaResumen.setLayout( gl5 );
    PanelCorrector.id( cajaResumen, "as_resumen" );

    ir( 0 );
    Rectangle pb = panel.raiz().getShell().getBounds();
    Rectangle pantalla = d.getClientArea();
    int ancho = Math.min( 1020, Math.max( 760, pantalla.width - 40 ) );
    int alto = Math.min( propuestas.isEmpty() ? 680 : 760, Math.max( 560, pantalla.height - 60 ) );
    shell.setSize( ancho, alto );
    Point s = shell.getSize();
    shell.setLocation( pb.x + Math.max( 0, ( pb.width - s.x ) / 2 ), pb.y + Math.max( 0, ( pb.height - s.y ) / 4 ) );
    shell.open();
  }

  // ---------------------------------------------------------------- navegacion

  /** La pagina actual (0..4). */
  int pagina() {
    return pagina;
  }

  boolean trabajando() {
    return trabajando;
  }

  boolean cerrado() {
    return cerrado || shell.isDisposed();
  }

  /** El pack ya guardado, o null. */
  File guardado() {
    return guardado;
  }

  void alFrente() {
    if ( !shell.isDisposed() ) {
      shell.setActive();
    }
  }

  void cerrar() {
    if ( !shell.isDisposed() ) {
      shell.dispose();
    }
  }

  /** Un control del asistente por su id (pruebas). */
  Control buscar( String id ) {
    return PanelCorrector.buscar( shell, id );
  }

  /** Los pasos 3 a 5 necesitan que la solucion ya se haya ejecutado. */
  boolean alcanzable( int n ) {
    return !trabajando && n >= 0 && n < PAGINAS && ( n <= 1 || analisis != null );
  }

  private void siguiente() {
    if ( pagina == 0 ) {
      if ( validar( 0 ) ) {
        ir( 1 );
      }
    } else if ( pagina == 1 ) {
      if ( analisis != null && firma().equals( firmaAnalisis ) ) {
        ir( 2 );
      } else {
        analizar();
      }
    } else {
      ir( pagina + 1 );
    }
  }

  void ir( int n ) {
    if ( n < 0 || n >= PAGINAS ) {
      return;
    }
    if ( n == 3 ) {
      armarRubrica();
    }
    if ( n == 4 ) {
      pintarResumen();
    }
    pagina = n;
    pila.topControl = tarjetas[ n ];
    paso.setText( t.f( "ui.as_paso", n + 1, PAGINAS, t.s( "ui.as_paso" + ( n + 1 ) ) ) );
    intro.setText( PanelInformes.esc( t.s( String.format( "ui.as_intro%d", n + 1 ) ) ) );
    contenido.layout( true, true );
    ajustar( tarjetas[ n ] );
    pintarLista();
    habilitar();
    paso.getParent().layout( true, true );
  }

  private void pintarLista() {
    for ( int i = 0; i < PAGINAS; i++ ) {
      boolean hecho = i < pagina || i == 1 && analisis != null || guardado != null;
      String marca = i == pagina ? "▶" : hecho ? "✓" : "○";
      pasos[ i ].setText( PanelInformes.esc( marca + "  " + ( i + 1 ) + ".  " + t.s( "ui.as_paso" + ( i + 1 ) ) ) );
      pasos[ i ].setFont( i == pagina ? negrita : null );
      pasos[ i ].setForeground( i == pagina ? azul : hecho ? verde : alcanzable( i ) ? null : gris );
      pasos[ i ].setToolTipText( alcanzable( i ) ? null : t.s( "ui.as_paso_bloqueado" ) );
    }
    pasos[ 0 ].getParent().layout( true );
  }

  private void habilitar() {
    boolean hecho = guardado != null;
    anterior.setEnabled( !trabajando && !hecho && pagina > 0 );
    siguiente.setEnabled( !trabajando && !hecho && pagina < PAGINAS - 1 );
    guardar.setEnabled( !trabajando && !hecho && analisis != null && pagina >= 2 );
    usar.setEnabled( hecho );
    reanalizar.setEnabled( !trabajando && !hecho && analisis != null );
    for ( FilaBase f : filasBase ) {
      for ( String id : new String[] { "as_base_probar:", "as_base_generar:" } ) {
        Control c = buscar( id + f.propuesta.conexion );
        if ( c != null ) {
          c.setEnabled( !trabajando );
        }
      }
    }
    siguiente.getParent().layout( true );
    pintarLista();
  }

  // ---------------------------------------------------------------- validacion en linea

  /** Valida los campos de una pagina (0 o 1), marca en rojo lo que esta mal y dice si se puede seguir. */
  boolean validar( int n ) {
    boolean ok = true;
    if ( n == 0 ) {
      limpiar( "as_titulo", "as_destino" );
      if ( titulo.getText().trim().isEmpty() ) {
        ok = marcar( "as_titulo", t.s( "ui.as_falta_titulo" ) );
      }
      String d = destino.getText().trim();
      File fd = new File( d );
      if ( d.isEmpty() ) {
        ok = marcar( "as_destino", t.s( "ui.as_falta_destino" ) );
      } else if ( !fd.isAbsolute() ) {
        ok = marcar( "as_destino", t.f( "ui.ruta_relativa", d ) );
      } else if ( fd.isDirectory() && !vacia( fd ) && !new File( fd, "pack.json" ).isFile() ) {
        ok = marcar( "as_destino", t.s( "ui.as_destino_lleno" ) );
      } else if ( fd.isFile() && !d.toLowerCase( Locale.ROOT ).endsWith( ".zip" ) ) {
        ok = marcar( "as_destino", t.s( "ui.as_destino_lleno" ) );
      } else if ( fd.isDirectory() && new File( fd, "pack.json" ).isFile() || fd.isFile() ) {
        advertir( "as_destino", t.s( "ui.as_destino_pack" ) );
      }
    } else if ( n == 1 ) {
      limpiar( "as_oculto" );
      String o = oculto.getText().trim();
      if ( !o.isEmpty() && ( !new File( o ).isAbsolute() || !new File( o ).isDirectory() ) ) {
        ok = marcar( "as_oculto", t.f( "ui.no_existe", o ) );
      }
      for ( FilaBase f : filasBase ) {
        ok &= validarBase( f, true );
      }
    }
    if ( !ok ) {
      info( t.s( "ui.as_revisa" ), true );
    }
    return ok;
  }

  /** Los campos de la base de prueba de una conexion; con scripts, tambien que existan. */
  private boolean validarBase( FilaBase f, boolean conScripts ) {
    String cx = f.propuesta.conexion;
    limpiar( "as_base_nombre:" + cx, "as_base_puerto:" + cx, "as_base_scripts:" + cx, "as_base_oculto:" + cx );
    boolean ok = true;
    String puerto = f.puerto.getText().trim();
    if ( !puerto.isEmpty() && !puerto.matches( "\\d{1,5}" ) ) {
      ok = marcar( "as_base_puerto:" + cx, t.f( "ui.as_puerto_mal", puerto ) );
    }
    BaseDePrueba b = baseDe( f );
    if ( b.base.isEmpty() ) {
      ok = marcar( "as_base_nombre:" + cx, t.f( "ui.as_base_falta", cx ) );
    } else {
      List<Mensaje> mal = CorrectorBases.inseguras( packDe( b ), null, null );
      if ( !mal.isEmpty() ) {
        ok = marcar( "as_base_nombre:" + cx, mal.get( 0 ).texto( t ) ); // el primero: el mas grave
      }
    }
    if ( conScripts ) {
      List<String> faltan = faltan( f.scripts.getText() );
      if ( !faltan.isEmpty() ) {
        ok = marcar( "as_base_scripts:" + cx, t.f( "ui.as_script_no_existe", String.join( "; ", faltan ) ) );
      } else if ( archivosSinError( f.scripts.getText() ).isEmpty() ) {
        advertir( "as_base_scripts:" + cx, t.s( "ui.as_base_sin_scripts" ) );
      }
      List<String> faltanO = faltan( f.scriptsOculto.getText() );
      if ( !faltanO.isEmpty() ) {
        ok = marcar( "as_base_oculto:" + cx, t.f( "ui.as_script_no_existe", String.join( "; ", faltanO ) ) );
      }
    }
    return ok;
  }

  /** Pone el error de un campo (rojo, debajo) y va a su pagina; devuelve false. */
  boolean marcar( String id, String texto ) {
    Label l = errores.get( id );
    if ( l == null || l.isDisposed() ) {
      info( texto, true );
      return false;
    }
    l.setText( PanelInformes.esc( texto ) );
    l.setForeground( rojo );
    mostrar( l, true );
    Integer p = paginaDe.get( id );
    if ( p != null && p != pagina ) {
      ir( p );
    } else if ( p != null ) {
      ajustar( tarjetas[ p ] );
    }
    return false;
  }

  /** Un aviso (naranja) debajo del campo: no impide seguir. */
  private void advertir( String id, String texto ) {
    Label l = errores.get( id );
    if ( l != null && !l.isDisposed() ) {
      l.setText( PanelInformes.esc( texto ) );
      l.setForeground( naranja );
      mostrar( l, true );
    }
  }

  private void limpiar( String... ids ) {
    for ( String id : ids ) {
      Label l = errores.get( id );
      if ( l != null && !l.isDisposed() && l.getVisible() ) {
        l.setText( "" );
        mostrar( l, false );
      }
    }
  }

  /** El error (o aviso) que muestra un campo, "" si no tiene (pruebas). */
  String errorDe( String id ) {
    Label l = errores.get( id );
    return l == null || l.isDisposed() || !l.getVisible() ? "" : l.getText();
  }

  private void mostrar( Control c, boolean si ) {
    Object ld = c.getLayoutData();
    if ( ld instanceof GridData ) {
      ( (GridData) ld ).exclude = !si;
    }
    c.setVisible( si );
    c.getParent().layout( true, true );
    for ( ScrolledComposite sc : tarjetas ) {
      if ( sc != null && !sc.isDisposed() ) {
        ajustar( sc );
      }
    }
  }

  // ---------------------------------------------------------------- analizar

  /** Corre la solucion en una hija y arma un pack de prueba en temporales (nada se guarda). (UI) */
  void analizar() {
    if ( !validar( 0 ) || !validar( 1 ) ) {
      return;
    }
    final CorrectorCrearPack.Opciones op = opciones( false );
    if ( op == null ) {
      return;
    }
    final String firma = firma();
    op.destino = new File( new File( tmp, "analisis-" + ANALISIS.incrementAndGet() ), "pack" );
    op.verificarLocales = false;
    op.copiaAlumnos = null;
    op.reglas = null;
    op.rubrica = null;
    final int g = ++gen;
    trabajando = true;
    recuadro( null, false );
    habilitar();
    info( t.s( "ui.as_analizando" ), false );
    panel.servicio.enSegundoPlano( () -> {
      try {
        final CorrectorCrearPack.Creado cr = CorrectorCrearPack.crear( op );
        final Pack p;
        final CorrectorTabla tabla;
        final Map<String, CorrectorTabla> tablas = new LinkedHashMap<String, CorrectorTabla>();
        try ( CorrectorPack cp = CorrectorPack.cargar( op.destino ) ) {
          p = cp.pack();
          if ( Pack.Salida.CAPTURA_TABLAS.equals( p.salida.captura ) ) {
            for ( TablaSalida ts : p.salida.tablas ) {
              List<String> nombres = new ArrayList<String>();
              for ( Pack.Columna c : ts.columnas ) {
                nombres.add( c.nombre );
              }
              tablas.put( ts.nombre, CorrectorTabla.esperada( cp.esperado( p.casos.get( 0 ), ts ),
                p.salida.csvEsperado, nombres ) );
            }
            tabla = tablas.isEmpty() ? null : tablas.values().iterator().next();
          } else {
            tabla = cp.pack().casos.isEmpty() ? null : CorrectorTabla.esperada( cp.esperado( cp.pack().casos.get(
              0 ) ), cp.pack() );
          }
        }
        panel.enUi( () -> {
          if ( g != gen || shell.isDisposed() ) {
            return;
          }
          trabajando = false;
          firmaAnalisis = firma;
          esperadasTablas = tablas;
          analizado( cr, p, tabla );
          recordarClaves();
        } );
      } catch ( Throwable x ) {
        panel.enUi( () -> {
          if ( g != gen || shell.isDisposed() ) {
            return;
          }
          trabajando = false;
          habilitar();
          fallo( x );
        } );
      }
      return null;
    } );
  }

  /** Llena los pasos 3, 4 y 5 con lo que encontro la hija y va al 3. (UI) */
  private void analizado( CorrectorCrearPack.Creado cr, Pack p, CorrectorTabla tabla ) {
    analisis = cr;
    analizado = p;
    CorrectorCrearPack.Detectado d = cr.detectado;
    boolean tablas = Pack.Salida.CAPTURA_TABLAS.equals( p.salida.captura );
    entradas.setText( PanelInformes.esc( d.entradas.isEmpty() && !filasBase.isEmpty() ? t.s( "ui.as_entradas_bd" )
      : t.f( "ui.as_entradas", d.entradas.isEmpty() ? "-" : String.join( ", ", d.entradas ) ) ) );
    int i = d.stepSalida == null ? -1 : steps.indexOf( d.stepSalida );
    salida.select( i < 0 ? 0 : i + 1 );
    mostrarFila( "as_salida", !tablas );
    mostrarFila( "as_tabla_vista", tablas );
    mostrar( pendiente, false );
    clavesSalida.clear();
    clavesSalida.addAll( p.comparar.columnasClave );
    esperadaUnica = tablas ? null : tabla;
    tablasAnalizadas = new ArrayList<TablaSalida>( tablas ? p.salida.tablas : new ArrayList<TablaSalida>() );
    clavesTabla.clear();
    ignorarTabla.clear();
    tablasFuera.clear();
    tablaVista.removeAll();
    for ( TablaSalida ts : tablasAnalizadas ) {
      clavesTabla.put( k( ts.nombre ), new LinkedHashSet<String>( ts.columnasClave ) );
      ignorarTabla.put( k( ts.nombre ), new LinkedHashSet<String>( ts.ignorar ) );
      tablaVista.add( ts.nombre );
    }
    if ( !tablasAnalizadas.isEmpty() ) {
      tablaVista.select( 0 );
    }
    List<String> cols = new ArrayList<String>();
    for ( Pack.Columna c : p.salida.columnas ) {
      cols.add( c.nombre + " (" + c.tipo + ( c.formato == null ? "" : " " + c.formato ) + ")" );
    }
    if ( tablas ) {
      for ( TablaSalida ts : p.salida.tablas ) {
        cols.add( t.f( "ui.as_tabla_columnas", ts.nombre, ts.columnas.size() ) );
      }
      columnas.setText( PanelInformes.esc( t.f( "ui.as_tablas", String.join( ", ", cols ) ) ) );
    } else {
      columnas.setText( PanelInformes.esc( t.f( "ui.as_columnas", String.join( ", ", cols ) ) ) );
    }
    if ( tablas ) {
      verTabla();
    } else {
      pintarColumnas();
      llenarEsperada( tabla, null );
    }
    List<String> av = new ArrayList<String>();
    for ( Aviso a : cr.avisos ) {
      av.add( a.detalle == null ? a.codigo : CorrectorTextos.detalle( t, a ) );
    }
    avisos.setText( av.isEmpty() ? "" : PanelInformes.esc( t.f( "ui.as_avisos", String.join( " · ", av ) ) ) );

    // reglas sugeridas, todas marcadas, y la rubrica por defecto
    sugeridas = new ArrayList<Regla>( p.reglas );
    rubrica = new ArrayList<ItemRubrica>( p.rubrica );
    reglasDeLaRubrica = idsReglas( sugeridas );
    escala.setText( numero( p.escala ) );
    pintarRubrica( idsReglas( sugeridas ) );
    info( t.f( "ui.as_analizado", cr.casos.isEmpty() ? 0 : cr.casos.get( 0 ).filas ), false );
    ir( 2 );
  }

  private void llenarEsperada( CorrectorTabla tb, String vista ) {
    esperada.removeAll();
    for ( TableColumn c : esperada.getColumns() ) {
      c.dispose();
    }
    Label titulo2 = (Label) buscar( "as_esperada_titulo" );
    if ( tb == null ) {
      titulo2.setText( t.s( "ui.as_esperada_vacia" ) );
      return;
    }
    titulo2.setText( PanelInformes.esc( vista == null ? t.f( "ui.as_esperada", tb.filas.size() ) : t.f(
      "ui.as_esperada_tabla", vista, tb.filas.size() ) ) );
    for ( String c : tb.columnas ) {
      TableColumn tc = new TableColumn( esperada, SWT.LEFT );
      tc.setText( c );
      tc.setWidth( 110 );
    }
    for ( int i = 0; i < Math.min( MAX_FILAS, tb.filas.size() ); i++ ) {
      TableItem it = new TableItem( esperada, SWT.NONE );
      String[] f = tb.filas.get( i );
      for ( int c = 0; c < tb.columnas.size(); c++ ) {
        it.setText( c, c < f.length && f[ c ] != null ? f[ c ] : "" );
      }
    }
  }

  /** Cambio el paso de salida: hay que volver a ejecutar para ver su resultado. */
  private void marcarPendiente() {
    if ( analisis != null ) {
      pendiente.setText( t.s( "ui.as_reanalizar_pendiente" ) );
      mostrar( pendiente, true );
    }
  }

  // ---------------------------------------------------------------- columnas (paso 3)

  /** La tabla elegida en «Tabla»: sus columnas con las casillas y su salida esperada. (UI) */
  private void verTabla() {
    int i = tablaVista.getSelectionIndex();
    if ( i < 0 || i >= tablasAnalizadas.size() ) {
      return;
    }
    TablaSalida ts = tablasAnalizadas.get( i );
    tablaComparar.setSelection( !tablasFuera.contains( k( ts.nombre ) ) );
    pintarColumnas();
    llenarEsperada( esperadasTablas.get( ts.nombre ), ts.nombre );
  }

  /** Una fila por columna: nombre, tipo, «Identifica la fila» y (con tablas) «No comparar». (UI) */
  private void pintarColumnas() {
    for ( Control c : cajaColumnas.getChildren() ) {
      c.dispose();
    }
    final TablaSalida ts = tablaActual();
    List<Pack.Columna> cols = ts != null ? ts.columnas : analizado == null ? Collections.<Pack.Columna>emptyList()
      : analizado.salida.columnas;
    boolean conIgnorar = ts != null;
    ( (GridLayout) cajaColumnas.getLayout() ).numColumns = conIgnorar ? 4 : 3;
    for ( String k : conIgnorar ? new String[] { "ui.as_col_columna", "ui.as_col_tipo", "ui.as_col_clave",
      "ui.as_col_ignorar" } : new String[] { "ui.as_col_columna", "ui.as_col_tipo", "ui.as_col_clave" } ) {
      Label l = new Label( cajaColumnas, SWT.NONE );
      l.setText( t.s( k ) );
      l.setFont( negrita );
    }
    final Set<String> claves = ts == null ? clavesSalida : clavesTabla.get( k( ts.nombre ) );
    final Set<String> ignorar = ts == null ? null : ignorarTabla.get( k( ts.nombre ) );
    String pre = ts == null ? "" : ts.nombre + ":";
    for ( Pack.Columna c : cols ) {
      final String col = c.nombre;
      Label n = new Label( cajaColumnas, SWT.NONE );
      n.setText( PanelInformes.esc( col ) );
      GridData gn = new GridData( SWT.LEFT, SWT.CENTER, false, false );
      gn.widthHint = 170;
      n.setLayoutData( gn );
      Label tp = new Label( cajaColumnas, SWT.NONE );
      tp.setText( c.tipo == null ? "" : c.tipo );
      tp.setForeground( gris );
      GridData gt = new GridData( SWT.LEFT, SWT.CENTER, false, false );
      gt.widthHint = 90;
      tp.setLayoutData( gt );
      final Button bk = new Button( cajaColumnas, SWT.CHECK );
      bk.setSelection( contiene( claves, col ) );
      PanelCorrector.id( bk, "as_col_clave:" + pre + col );
      GridData gk = new GridData( SWT.CENTER, SWT.CENTER, false, false );
      gk.widthHint = 120;
      bk.setLayoutData( gk );
      bk.addListener( SWT.Selection, e -> poner( claves, col, bk.getSelection() ) );
      if ( conIgnorar ) {
        final Button bi = new Button( cajaColumnas, SWT.CHECK );
        bi.setSelection( contiene( ignorar, col ) );
        PanelCorrector.id( bi, "as_col_ignorar:" + pre + col );
        bi.setLayoutData( new GridData( SWT.CENTER, SWT.CENTER, false, false ) );
        bi.addListener( SWT.Selection, e -> poner( ignorar, col, bi.getSelection() ) );
      }
    }
    cajaColumnas.layout( true, true );
    ajustar( tarjetas[ 2 ] );
  }

  private TablaSalida tablaActual() {
    int i = tablaVista.getSelectionIndex();
    return tablasAnalizadas.isEmpty() || i < 0 || i >= tablasAnalizadas.size() ? null : tablasAnalizadas.get( i );
  }

  private static boolean contiene( Set<String> s, String col ) {
    if ( s == null ) {
      return false;
    }
    for ( String x : s ) {
      if ( x.equalsIgnoreCase( col ) ) {
        return true;
      }
    }
    return false;
  }

  private static void poner( Set<String> s, String col, boolean si ) {
    if ( s == null ) {
      return;
    }
    for ( String x : new ArrayList<String>( s ) ) {
      if ( x.equalsIgnoreCase( col ) ) {
        s.remove( x );
      }
    }
    if ( si ) {
      s.add( col );
    }
  }

  /** «Comparar esta tabla». (UI) */
  private void compararTabla() {
    TablaSalida ts = tablaActual();
    if ( ts != null ) {
      if ( tablaComparar.getSelection() ) {
        tablasFuera.remove( k( ts.nombre ) );
      } else {
        tablasFuera.add( k( ts.nombre ) );
      }
    }
  }

  /** Las claves de la tabla (o de la salida, con null) como las dejo el docente (pruebas). */
  List<String> claves( String tabla ) {
    return new ArrayList<String>( tabla == null ? clavesSalida : clavesTabla.get( k( tabla ) ) );
  }

  List<String> ignoradas( String tabla ) {
    return new ArrayList<String>( ignorarTabla.get( k( tabla ) ) );
  }

  // ---------------------------------------------------------------- rubrica (paso 4)

  /**
   * Si cambiaron las reglas marcadas, la rubrica por defecto para esas reglas (los criterios del resultado conservan
   * lo editado; las reglas desmarcadas siguen en la lista con sus puntos); si no, la que se esta editando. Los puntos
   * se cambian en los campos que ya estan (no se rehace la grilla). (UI)
   */
  private void armarRubrica() {
    if ( analizado == null ) {
      return;
    }
    leerPuntos( false );
    List<Regla> marcadas = marcadas();
    Set<String> ids = idsReglas( marcadas );
    if ( !ids.equals( reglasDeLaRubrica ) ) {
      List<ItemRubrica> nueva = CorrectorCrearPack.rubricaPorDefecto( marcadas );
      for ( ItemRubrica x : nueva ) {
        ItemRubrica antes = buscarItem( rubrica, x.check );
        if ( antes != null && x.esDeSalida() ) {
          x.puntos = antes.puntos;
        }
      }
      for ( Regla r : sugeridas ) {
        if ( buscarItem( nueva, ItemRubrica.PREFIJO_REGLA + r.id ) == null ) {
          ItemRubrica antes = buscarItem( rubrica, ItemRubrica.PREFIJO_REGLA + r.id );
          nueva.add( antes != null ? antes : new ItemRubrica( ItemRubrica.PREFIJO_REGLA + r.id, 0 ) );
        }
      }
      rubrica = nueva;
      reglasDeLaRubrica = ids;
      boolean faltaAlguno = false;
      for ( ItemRubrica x : rubrica ) {
        Text p = puntos.get( x.check );
        if ( p == null || p.isDisposed() ) {
          faltaAlguno = true;
        } else if ( !x.esRegla() || ids.contains( x.idRegla() ) ) {
          p.setText( numero( x.puntos ) );
        }
      }
      if ( faltaAlguno ) {
        pintarRubrica( ids );
      }
    }
    sumar();
  }

  /**
   * «Resultado de la ejecucion» (los criterios de salida, con su explicacion) y «Reglas de diseño» (casilla + puntos),
   * en una grilla de 3 columnas: casilla, texto, puntos. (UI)
   */
  private void pintarRubrica( Set<String> marcadas ) {
    for ( Control c : cajaRubrica.getChildren() ) {
      c.dispose();
    }
    puntos.clear();
    marcasReglas.clear();
    seccion( "ui.as_resultado_titulo" );
    for ( ItemRubrica x : rubrica ) {
      if ( !x.esRegla() ) {
        new Label( cajaRubrica, SWT.NONE );
        filaRubrica( x, x.esDeSalida() && t.tiene( "ui.as_desc." + x.check ) ? t.s( "ui.as_desc." + x.check ) : null );
      }
    }
    seccion( "ui.as_reglas_titulo" );
    Label ri = new Label( cajaRubrica, SWT.WRAP );
    ri.setText( t.s( "ui.as_reglas_intro" ) );
    ri.setForeground( gris );
    GridData gri = new GridData( SWT.FILL, SWT.TOP, true, false, 3, 1 );
    gri.widthHint = 500;
    ri.setLayoutData( gri );
    if ( sugeridas.isEmpty() ) {
      Label l = new Label( cajaRubrica, SWT.NONE );
      l.setText( t.s( "ui.as_sin_reglas" ) );
      l.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false, 3, 1 ) );
    }
    for ( final Regla r : sugeridas ) {
      final Button b = new Button( cajaRubrica, SWT.CHECK );
      b.setSelection( marcadas.contains( r.id ) );
      PanelCorrector.id( b, "as_regla:" + r.id );
      marcasReglas.add( b );
      ItemRubrica x = buscarItem( rubrica, ItemRubrica.PREFIJO_REGLA + r.id );
      if ( x == null ) {
        x = new ItemRubrica( ItemRubrica.PREFIJO_REGLA + r.id, 0 );
        rubrica.add( x );
      }
      filaRubrica( x, descripcion( r ) );
      final Text p = puntos.get( x.check );
      p.setEnabled( b.getSelection() );
      b.addListener( SWT.Selection, e -> {
        p.setEnabled( b.getSelection() );
        armarRubrica();
      } );
    }
    cajaRubrica.layout( true, true );
    ajustar( tarjetas[ 3 ] );
    sumar();
  }

  private void seccion( String clave ) {
    Label l = new Label( cajaRubrica, SWT.NONE );
    l.setText( t.s( clave ) );
    l.setFont( negrita );
    GridData g = new GridData( SWT.FILL, SWT.TOP, true, false, 3, 1 );
    g.verticalIndent = 8;
    l.setLayoutData( g );
  }

  private void filaRubrica( ItemRubrica x, String desc ) {
    Composite c = new Composite( cajaRubrica, SWT.NONE );
    GridLayout gl = new GridLayout( 1, false );
    gl.marginWidth = 0;
    gl.marginHeight = 0;
    gl.verticalSpacing = 0;
    c.setLayout( gl );
    GridData gc = new GridData( SWT.FILL, SWT.CENTER, true, false );
    gc.widthHint = 420;
    c.setLayoutData( gc );
    Label l = new Label( c, SWT.WRAP );
    l.setText( PanelInformes.esc( etiqueta( x ) ) );
    l.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    if ( desc != null ) {
      Label dl = new Label( c, SWT.WRAP );
      dl.setText( PanelInformes.esc( desc ) );
      dl.setForeground( gris );
      dl.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    }
    Composite cp = new Composite( cajaRubrica, SWT.NONE );
    GridLayout glp = new GridLayout( 1, false );
    glp.marginWidth = 0;
    glp.marginHeight = 0;
    cp.setLayout( glp );
    Text p = new Text( cp, SWT.BORDER | SWT.RIGHT );
    GridData g = new GridData( SWT.LEFT, SWT.CENTER, false, false );
    g.widthHint = 60;
    p.setLayoutData( g );
    p.setText( numero( x.puntos ) );
    final String idp = "as_puntos:" + x.check;
    p.addListener( SWT.Modify, e -> {
      limpiar( idp );
      sumar();
    } );
    PanelCorrector.id( p, idp );
    puntos.put( x.check, p );
    Label err = new Label( cp, SWT.NONE );
    GridData ge = new GridData( SWT.LEFT, SWT.TOP, false, false );
    ge.exclude = true;
    err.setLayoutData( ge );
    err.setVisible( false );
    err.setForeground( rojo );
    PanelCorrector.id( err, "as_error:as_puntos:" + x.check );
    errores.put( "as_puntos:" + x.check, err );
    paginaDe.put( "as_puntos:" + x.check, 3 );
  }

  /** Pasa los puntos escritos a la rubrica; false (con el error al lado) si alguno no es un numero. */
  private boolean leerPuntos( boolean conErrores ) {
    boolean ok = true;
    for ( ItemRubrica x : rubrica ) {
      Text p = puntos.get( x.check );
      if ( p == null || p.isDisposed() ) {
        continue;
      }
      if ( conErrores ) {
        limpiar( "as_puntos:" + x.check );
      }
      Double v;
      try {
        v = PanelCorrector.numero( p.getText() );
      } catch ( NumberFormatException e ) {
        v = null;
      }
      if ( v == null || v < 0 ) {
        if ( conErrores ) {
          ok = marcar( "as_puntos:" + x.check, t.s( "ui.as_puntos_mal" ) );
        }
        continue;
      }
      x.puntos = v;
    }
    return ok;
  }

  /** El total de la rubrica (lo marcado) y la escala. (UI) */
  private void sumar() {
    if ( total == null || total.isDisposed() ) {
      return;
    }
    double s = 0;
    Set<String> ids = idsReglas( marcadas() );
    for ( ItemRubrica x : rubrica ) {
      if ( x.esRegla() && !ids.contains( x.idRegla() ) ) {
        continue;
      }
      Text p = puntos.get( x.check );
      try {
        Double v = p == null || p.isDisposed() ? Double.valueOf( x.puntos ) : PanelCorrector.numero( p.getText() );
        s += v == null ? 0 : Math.max( 0, v );
      } catch ( NumberFormatException e ) {
        // se marca al guardar
      }
    }
    String esc = escala.getText().trim().isEmpty() ? "?" : escala.getText().trim();
    total.setText( PanelInformes.esc( t.f( "ui.as_total", numero( s ), numero( s ), esc ) ) );
  }

  private List<Regla> marcadas() {
    List<Regla> l = new ArrayList<Regla>();
    for ( int i = 0; i < sugeridas.size() && i < marcasReglas.size(); i++ ) {
      if ( !marcasReglas.get( i ).isDisposed() && marcasReglas.get( i ).getSelection() ) {
        l.add( sugeridas.get( i ) );
      }
    }
    if ( marcasReglas.isEmpty() ) {
      l.addAll( sugeridas );
    }
    return l;
  }

  private static Set<String> idsReglas( List<Regla> l ) {
    Set<String> s = new LinkedHashSet<String>();
    for ( Regla r : l ) {
      s.add( r.id );
    }
    return s;
  }

  private static ItemRubrica buscarItem( List<ItemRubrica> l, String check ) {
    for ( ItemRubrica x : l ) {
      if ( x.check.equals( check ) ) {
        return x;
      }
    }
    return null;
  }

  /** La rubrica final: lo de salida y las reglas marcadas. */
  private List<ItemRubrica> rubricaFinal( List<Regla> reglas ) {
    List<ItemRubrica> r = new ArrayList<ItemRubrica>();
    Set<String> ids = idsReglas( reglas );
    for ( ItemRubrica x : rubrica ) {
      if ( !x.esRegla() || ids.contains( x.idRegla() ) ) {
        r.add( x );
      }
    }
    return r;
  }

  // ---------------------------------------------------------------- resumen (paso 5)

  /** «Revisar y guardar»: una fila por tema, en palabras. (UI) */
  private void pintarResumen() {
    for ( Control c : cajaResumen.getChildren() ) {
      c.dispose();
    }
    if ( analizado == null ) {
      return;
    }
    leerPuntos( false );
    String d = destino.getText().trim();
    filaResumen( "as_resumen:ejercicio", "ui.as_resumen_ejercicio", "«" + titulo.getText().trim() + "» · " + t.f(
      "ui.as_resumen_pack", d ) );
    String cap = capturas.get( Math.max( 0, captura.getSelectionIndex() ) )[ 0 ];
    StringBuilder compara = new StringBuilder();
    if ( Pack.Salida.CAPTURA_TABLAS.equals( cap ) ) {
      for ( TablaSalida ts : tablasAnalizadas ) {
        if ( tablasFuera.contains( k( ts.nombre ) ) ) {
          continue;
        }
        compara.append( compara.length() == 0 ? "" : "\n" ).append( t.f( "ui.as_resumen_tabla", ts.nombre, lista(
          clavesTabla.get( k( ts.nombre ) ) ), lista( ignorarTabla.get( k( ts.nombre ) ) ) ) );
      }
    } else {
      int i = salida.getSelectionIndex();
      String step = i <= 0 ? analisis.detectado.stepSalida : steps.get( i - 1 );
      compara.append( t.f( Pack.Salida.CAPTURA_ARCHIVO.equals( cap ) ? "ui.as_resumen_archivo"
        : "ui.as_resumen_step", step == null ? "-" : step, lista( clavesSalida ) ) );
    }
    filaResumen( "as_resumen:compara", "ui.as_resumen_compara", compara.toString() );
    StringBuilder casos = new StringBuilder( t.f( "ui.as_resumen_visible", analisis.casos.isEmpty() ? 0
      : analisis.casos.get( 0 ).filas ) );
    if ( tieneOculto() ) {
      casos.append( "\n" ).append( t.s( "ui.as_resumen_oculto" ) );
    }
    if ( dosVeces != null && dosVeces.getSelection() ) {
      casos.append( "\n" ).append( t.s( "ui.as_resumen_dos_veces" ) );
    }
    filaResumen( "as_resumen:casos", "ui.as_resumen_casos", casos.toString() );
    if ( !filasBase.isEmpty() ) {
      StringBuilder bs = new StringBuilder();
      for ( FilaBase f : filasBase ) {
        List<String> ns = new ArrayList<String>();
        for ( File x : archivosSinError( f.scripts.getText() ) ) {
          ns.add( x.getName() );
        }
        BaseDePrueba b = baseDe( f );
        bs.append( bs.length() == 0 ? "" : "\n" ).append( t.f( "ui.as_resumen_base_valor", f.propuesta.conexion,
          b.base, b.tipoNormal() + " " + b.servidor + ( b.puerto.isEmpty() ? "" : ":" + b.puerto ), ns.isEmpty()
            ? t.s( "ui.as_resumen_sin_scripts" ) : String.join( ", ", ns ) ) );
      }
      filaResumen( "as_resumen:base", "ui.as_resumen_base", bs.toString() );
    }
    List<String> partes = new ArrayList<String>();
    double s = 0;
    for ( ItemRubrica x : rubricaFinal( marcadas() ) ) {
      partes.add( etiqueta( x ) + " " + numero( x.puntos ) );
      s += x.puntos;
    }
    filaResumen( "as_resumen:nota", "ui.as_resumen_nota", t.f( "ui.as_resumen_nota_valor", numero( s ),
      escala.getText().trim(), String.join( " · ", partes ) ) );
    filaResumen( "as_resumen:alumnos", "ui.as_resumen_alumnos", copiaAlumnos.getSelection() ? t.f(
      "ui.as_resumen_alumnos_valor", copiaDe( new File( d ) ).getName() ) : t.s( "ui.as_resumen_sin_copia" ) );
    Label nota = new Label( cajaResumen, SWT.WRAP );
    nota.setText( t.s( "ui.as_resumen_guardar" ) );
    nota.setForeground( gris );
    GridData gn = new GridData( SWT.FILL, SWT.TOP, true, false, 2, 1 );
    gn.widthHint = 500;
    gn.verticalIndent = 6;
    nota.setLayoutData( gn );
    cajaResumen.layout( true, true );
    ajustar( tarjetas[ 4 ] );
  }

  private void filaResumen( String id, String clave, String valor ) {
    Label k = new Label( cajaResumen, SWT.NONE );
    k.setText( t.s( clave ) );
    k.setFont( negrita );
    k.setLayoutData( new GridData( SWT.LEFT, SWT.TOP, false, false ) );
    Label v = new Label( cajaResumen, SWT.WRAP );
    v.setText( PanelInformes.esc( valor ) );
    GridData g = new GridData( SWT.FILL, SWT.TOP, true, false );
    g.widthHint = 420;
    v.setLayoutData( g );
    PanelCorrector.id( v, id );
  }

  /** El resumen del paso 5 como texto (pruebas). */
  String resumen() {
    StringBuilder b = new StringBuilder();
    for ( Control c : cajaResumen.getChildren() ) {
      if ( c instanceof Label ) {
        b.append( ( (Label) c ).getText() ).append( '\n' );
      }
    }
    return b.toString();
  }

  private String lista( Set<String> s ) {
    return s == null || s.isEmpty() ? t.s( "ui.as_resumen_ninguna" ) : String.join( ", ", s );
  }

  // ---------------------------------------------------------------- guardar

  /** Arma el pack en el destino con lo elegido (otra hija), verifica el otro idioma y escribe la copia. (UI) */
  void guardar() {
    if ( analisis == null ) {
      return;
    }
    if ( !validar( 0 ) || !validar( 1 ) ) {
      return;
    }
    if ( !leerPuntos( true ) ) {
      info( t.s( "ui.as_revisa" ), true );
      return;
    }
    limpiar( "as_escala" );
    Double esc;
    try {
      esc = PanelCorrector.numero( escala.getText() );
    } catch ( NumberFormatException e ) {
      esc = null;
    }
    if ( esc == null || esc <= 0 ) {
      marcar( "as_escala", t.s( "ui.as_escala_mal" ) );
      info( t.s( "ui.as_revisa" ), true );
      return;
    }
    limpiar( "as_columnas_caja" );
    if ( !tablasAnalizadas.isEmpty() && tablasFuera.size() >= tablasAnalizadas.size() ) {
      marcar( "as_columnas_caja", t.s( "ui.as_tabla_ninguna" ) );
      info( t.s( "ui.as_revisa" ), true );
      return;
    }
    final CorrectorCrearPack.Opciones op = opciones( true );
    if ( op == null ) {
      return;
    }
    op.reglas = marcadas();
    op.rubrica = rubricaFinal( op.reglas );
    op.escala = esc;
    op.verificarLocales = true;
    File fd = op.destino;
    op.reemplazar = fd.isFile() || fd.isDirectory() && new File( fd, "pack.json" ).isFile();
    if ( pagina != 4 ) {
      ir( 4 );
    }
    final int g = ++gen;
    trabajando = true;
    recuadro( null, false );
    habilitar();
    info( t.s( "ui.as_guardando" ), false );
    panel.servicio.enSegundoPlano( () -> {
      try {
        final CorrectorCrearPack.Creado cr = CorrectorCrearPack.crear( op );
        panel.enUi( () -> {
          if ( g != gen || shell.isDisposed() ) {
            return;
          }
          trabajando = false;
          guardado = new File( cr.pack );
          StringBuilder b = new StringBuilder( t.f( "ui.as_guardado", cr.pack ) );
          if ( cr.copiaAlumnos != null ) {
            b.append( "\n" ).append( t.f( "ui.as_copia_lista", cr.copiaAlumnos ) );
          }
          if ( cr.verificacion != null && cr.verificacion.nota != null ) {
            b.append( "\n" ).append( t.f( "ui.as_locale", numero( cr.verificacion.nota ), numero(
              cr.verificacion.escala ), cr.verificacion.locale ) );
          }
          for ( Aviso a : cr.avisos ) {
            if ( CorrectorCrearPack.AVISO_LOCALE.equals( a.codigo ) || CorrectorCrearPack.AVISO_SIN_VERIFICAR.equals(
              a.codigo ) ) {
              b.append( "\n" ).append( CorrectorTextos.detalle( t, a ) );
            }
          }
          recuadro( b.toString(), false );
          info( t.s( "ui.as_guardado_corto" ), false );
          habilitar();
        } );
      } catch ( Throwable x ) {
        panel.enUi( () -> {
          if ( g != gen || shell.isDisposed() ) {
            return;
          }
          trabajando = false;
          habilitar();
          fallo( x );
        } );
      }
      return null;
    } );
  }

  /** «Usar este pack»: lo pone en el panel y cierra. (UI) */
  void usar() {
    if ( guardado != null ) {
      panel.usarPack( guardado );
      shell.dispose();
    }
  }

  /** Las opciones de crear con lo de los pasos 1 a 3; null (con el error marcado) si falta algo. */
  private CorrectorCrearPack.Opciones opciones( boolean final_ ) {
    CorrectorCrearPack.Opciones op = new CorrectorCrearPack.Opciones();
    op.solucion = copia.ktr;
    op.dirOriginal = copia.dirOriginal;
    op.nombreSolucion = copia.nombre;
    op.destino = new File( destino.getText().trim() ).getAbsoluteFile();
    op.titulo = titulo.getText().trim();
    op.idioma = new String[] { "es", "en", "pt" }[ Math.max( 0, idioma.getSelectionIndex() ) ];
    String[] cap = capturas.get( Math.max( 0, captura.getSelectionIndex() ) );
    op.captura = cap[ 0 ];
    boolean tablas = Pack.Salida.CAPTURA_TABLAS.equals( op.captura );
    List<CorrectorCrearPack.CasoNuevo> casos = new ArrayList<CorrectorCrearPack.CasoNuevo>();
    casos.add( new CorrectorCrearPack.CasoNuevo( CorrectorCrearPack.VISIBLE, null, false ) );
    String o = oculto.getText().trim();
    CorrectorCrearPack.CasoNuevo caso2 = null;
    if ( !o.isEmpty() ) {
      caso2 = new CorrectorCrearPack.CasoNuevo( "oculto", new File( o ).getAbsoluteFile(), true );
    }
    if ( !filasBase.isEmpty() ) {
      op.bases = new ArrayList<BaseDePrueba>();
      op.scriptsBase = new LinkedHashMap<String, List<File>>();
      op.claves = new LinkedHashMap<String, String>();
      for ( FilaBase f : filasBase ) {
        BaseDePrueba b = baseDe( f );
        op.bases.add( b );
        op.scriptsBase.put( b.conexion, archivosSinError( f.scripts.getText() ) );
        String clave = f.clave.getText();
        if ( !clave.isEmpty() || !CorrectorBases.recordada( b ) ) {
          op.claves.put( b.conexion.toLowerCase( Locale.ROOT ), clave );
        }
        List<File> l = archivosSinError( f.scriptsOculto.getText() );
        if ( !l.isEmpty() ) {
          if ( caso2 == null ) {
            caso2 = new CorrectorCrearPack.CasoNuevo( "oculto", null, true );
          }
          if ( caso2.scripts == null ) {
            caso2.scripts = new LinkedHashMap<String, List<File>>();
          }
          caso2.scripts.put( f.propuesta.conexion, l );
        }
      }
    }
    if ( caso2 != null ) {
      casos.add( caso2 );
    }
    if ( dosVeces != null && dosVeces.getSelection() ) {
      for ( CorrectorCrearPack.CasoNuevo c : casos ) {
        c.ejecuciones = 2;
      }
    }
    op.casos = casos;
    if ( analisis == null && Pack.Salida.CAPTURA_ARCHIVO.equals( op.captura ) && cap[ 1 ] != null ) {
      op.stepSalida = cap[ 1 ];
    }
    if ( tablas && cap[ 1 ] != null && analisis == null ) {
      op.tablas = new ArrayList<TablaSalida>();
      for ( CorrectorBases.TablaEscrita te : inventario.tablasUnicas() ) {
        if ( te.tabla.equals( cap[ 1 ] ) ) {
          TablaSalida ts = new TablaSalida( te.conexion, te.tabla );
          ts.columnasClave.addAll( te.claves );
          op.tablas.add( ts );
        }
      }
    }
    if ( analisis != null && tablas ) {
      op.tablas = tablasEditadas();
    } else if ( analisis != null ) {
      int i = salida.getSelectionIndex();
      op.stepSalida = i <= 0 ? analisis.detectado.stepSalida : steps.get( i - 1 );
      op.columnasClave = new ArrayList<String>( clavesSalida ); // [] = comparar sin clave
    }
    if ( final_ && copiaAlumnos.getSelection() ) {
      op.copiaAlumnos = copiaDe( op.destino );
    }
    op.procesos = panel.servicio.procesos();
    return op;
  }

  /** Lo que cambia el resultado del analisis (si cambia, hay que volver a ejecutar la solucion). */
  private String firma() {
    StringBuilder b = new StringBuilder();
    b.append( captura.getSelectionIndex() ).append( '|' ).append( oculto.getText().trim() ).append( '|' );
    b.append( dosVeces != null && dosVeces.getSelection() ).append( '|' );
    for ( FilaBase f : filasBase ) {
      for ( Text x : new Text[] { f.servidor, f.puerto, f.base, f.usuario, f.clave, f.scripts, f.scriptsOculto } ) {
        b.append( x.getText().trim().hashCode() ).append( '|' );
      }
    }
    return b.toString();
  }

  private boolean tieneOculto() {
    if ( !oculto.getText().trim().isEmpty() ) {
      return true;
    }
    for ( FilaBase f : filasBase ) {
      if ( !archivosSinError( f.scriptsOculto.getText() ).isEmpty() ) {
        return true;
      }
    }
    return false;
  }

  // ---------------------------------------------------------------- capturas y base de prueba

  /**
   * Las opciones de «Que se compara» en palabras: las tablas que carga la solucion (todas, o una), el archivo de cada
   * Text file output y las filas del ultimo paso. Con tablas y base de prueba, la primera es «las tablas».
   */
  private void llenarCapturas( boolean conBase ) {
    List<CorrectorBases.TablaEscrita> ts = inventario.tablasUnicas();
    if ( conBase && !ts.isEmpty() ) {
      List<String> n = new ArrayList<String>();
      for ( CorrectorBases.TablaEscrita x : ts ) {
        n.add( x.tabla );
      }
      capturas.add( new String[] { Pack.Salida.CAPTURA_TABLAS, null, t.f( "ui.as_captura_tablas", ts.size(),
        String.join( ", ", n ) ) } );
      if ( ts.size() > 1 ) {
        for ( CorrectorBases.TablaEscrita x : ts ) {
          capturas.add( new String[] { Pack.Salida.CAPTURA_TABLAS, x.tabla, t.f( "ui.as_captura_tabla", x.tabla,
            x.step ) } );
        }
      }
    }
    for ( String s : inventario.salidasArchivo ) {
      capturas.add( new String[] { Pack.Salida.CAPTURA_ARCHIVO, s, t.f( "ui.as_captura_file_step", s ) } );
    }
    if ( inventario.salidasArchivo.isEmpty() ) {
      capturas.add( new String[] { Pack.Salida.CAPTURA_ARCHIVO, null, t.s( "ui.as_captura_file" ) } );
    }
    capturas.add( new String[] { Pack.Salida.CAPTURA_STEP, null, t.s( "ui.as_captura_step" ) } );
  }

  /**
   * La base de prueba de una conexion: servidor, puerto, usuario, contraseña, la base de prueba con «Probar
   * conexion», los scripts (los encontrados junto a la solucion ya puestos) con «Generar el script desde la base
   * actual» y los scripts del caso oculto.
   */
  private void grupoBase( Composite p2, BaseDePrueba b ) {
    final FilaBase f = new FilaBase( b );
    final String cx = b.conexion;
    Group g = new Group( p2, SWT.NONE );
    g.setText( PanelInformes.esc( t.f( "ui.as_base_conexion", cx, b.tipoNormal(), b.baseOriginal == null ? ""
      : b.baseOriginal ) ) );
    GridData gg = new GridData( SWT.FILL, SWT.TOP, true, false, 3, 1 );
    gg.verticalIndent = 6;
    g.setLayoutData( gg );
    g.setLayout( new GridLayout( 4, false ) );
    PanelCorrector.id( g, "as_base:" + cx );
    f.servidor = campoBase( g, "as_base_servidor:" + cx, "ui.as_base_servidor", b.servidor, 1, 0 );
    f.puerto = campoBase( g, "as_base_puerto:" + cx, "ui.as_base_puerto", b.puerto, 1, 0 );
    error( g, 4, "as_base_puerto:" + cx );
    f.usuario = campoBase( g, "as_base_usuario:" + cx, "ui.as_base_usuario", b.usuario, 1, 0 );
    f.clave = campoBase( g, "as_base_clave:" + cx, "ui.as_base_clave", "", 1, SWT.PASSWORD );
    if ( CorrectorBases.recordada( b ) ) {
      f.clave.setMessage( t.s( "ui.as_base_clave_recordada" ) );
    }
    ayuda( g, 4, "ui.as_base_clave_ayuda" );
    f.base = campoBase( g, "as_base_nombre:" + cx, "ui.as_base_nombre", b.base, 2, 0 );
    Button probar = panel.boton( g, "as_base_probar:" + cx, "ui.as_base_probar", null, () -> probarConexion( f ) );
    probar.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, false, false ) );
    ayuda( g, 4, "ui.as_base_nombre_ayuda" );
    error( g, 4, "as_base_nombre:" + cx );
    f.probado = new Label( g, SWT.WRAP );
    GridData gp = new GridData( SWT.FILL, SWT.TOP, true, false, 4, 1 );
    gp.widthHint = 400;
    gp.exclude = true;
    f.probado.setLayoutData( gp );
    f.probado.setVisible( false );
    PanelCorrector.id( f.probado, "as_base_probado:" + cx );

    f.scripts = campoBase( g, "as_base_scripts:" + cx, "ui.as_base_scripts", "", 2, 0 );
    final Text sc = f.scripts;
    Button elegir = boton( g, "as_base_elegir:" + cx, "ui.elegir", () -> elegirScripts( sc ) );
    elegir.setLayoutData( new GridData( SWT.LEFT, SWT.CENTER, false, false ) );
    List<File> det = detectados.get( cx );
    List<String> rutasOculto = new ArrayList<String>();
    if ( det != null && !det.isEmpty() ) {
      List<String> rutas = new ArrayList<String>();
      List<String> nombres = new ArrayList<String>();
      for ( File x : det ) {
        ( CorrectorScripts.esOculto( x ) ? rutasOculto : rutas ).add( x.getPath() );
        nombres.add( x.getName() );
      }
      f.scripts.setText( String.join( "; ", rutas ) );
      f.detectados = ayudaTexto( g, 4, t.f( "ui.as_base_detectados", String.join( ", ", nombres ) ) );
      f.detectados.setForeground( verde );
    } else {
      f.detectados = ayudaTexto( g, 4, t.s( "ui.as_base_sin_detectados" ) );
      f.detectados.setForeground( naranja );
    }
    PanelCorrector.id( f.detectados, "as_base_detectados:" + cx );
    ayuda( g, 4, "ui.as_base_scripts_ayuda" );
    error( g, 4, "as_base_scripts:" + cx );
    new Label( g, SWT.NONE );
    Button generar = panel.boton( g, "as_base_generar:" + cx, "ui.as_base_generar", null, () -> elegirYGenerar( f ) );
    generar.setLayoutData( new GridData( SWT.LEFT, SWT.CENTER, false, false, 3, 1 ) );
    ayudaTexto( g, 4, t.f( "ui.as_base_generar_ayuda", b.baseOriginal == null ? "" : b.baseOriginal ) );
    f.generado = new Link( g, SWT.WRAP );
    GridData gl = new GridData( SWT.FILL, SWT.TOP, true, false, 4, 1 );
    gl.widthHint = 400;
    gl.exclude = true;
    f.generado.setLayoutData( gl );
    f.generado.setVisible( false );
    f.generado.addListener( SWT.Selection, e -> {
      if ( f.scriptGenerado != null ) {
        panel.servicio.abrir( f.scriptGenerado );
      }
    } );
    PanelCorrector.id( f.generado, "as_base_generado:" + cx );

    f.scriptsOculto = campoBase( g, "as_base_oculto:" + cx, "ui.as_base_oculto", String.join( "; ", rutasOculto ), 2,
      0 );
    final Text so = f.scriptsOculto;
    Button elegirO = boton( g, "as_base_elegir_oculto:" + cx, "ui.elegir", () -> elegirScripts( so ) );
    elegirO.setLayoutData( new GridData( SWT.LEFT, SWT.CENTER, false, false ) );
    ayuda( g, 4, "ui.as_base_oculto_ayuda" );
    error( g, 4, "as_base_oculto:" + cx );
    for ( String id : new String[] { "as_base_puerto:", "as_base_nombre:", "as_base_scripts:", "as_base_oculto:" } ) {
      paginaDe.put( id + cx, 1 );
    }
    filasBase.add( f );
  }

  private Text campoBase( Composite g, String id, String rotulo, String valor, int columnas, int estilo ) {
    rotulo( g, rotulo );
    Text x = new Text( g, SWT.BORDER | SWT.SINGLE | estilo );
    GridData gd = new GridData( SWT.FILL, SWT.CENTER, true, false, columnas, 1 );
    gd.widthHint = columnas > 1 ? 330 : 150;
    x.setLayoutData( gd );
    x.setText( valor == null ? "" : valor );
    PanelCorrector.id( x, id );
    x.addListener( SWT.Modify, e -> limpiar( id ) );
    return x;
  }

  private BaseDePrueba baseDe( FilaBase f ) {
    BaseDePrueba b = new BaseDePrueba( f.propuesta.conexion, f.propuesta.tipo, f.servidor.getText().trim(),
      f.puerto.getText().trim(), f.base.getText().trim(), f.usuario.getText().trim() );
    b.baseOriginal = f.propuesta.baseOriginal;
    b.opciones.putAll( f.propuesta.opciones );
    return b;
  }

  /** Un pack con solo esa base de prueba (para las reglas de seguridad y para probarla). */
  private static Pack packDe( BaseDePrueba b ) {
    Pack p = new Pack();
    p.formato = Pack.FORMATO_2;
    p.completar();
    p.bases = new ArrayList<BaseDePrueba>( Collections.singletonList( b ) );
    return p;
  }

  /** La contraseña de una conexion: la escrita, o la que se escribio antes en esta sesion. */
  private String claveDe( FilaBase f, BaseDePrueba b ) {
    String c = f.clave.getText();
    if ( c.isEmpty() && CorrectorBases.recordada( b ) ) {
      String r = CorrectorBases.clave( b, null );
      return r == null ? "" : r;
    }
    return c;
  }

  /** «Probar conexion»: crea y borra la base de prueba (nada mas) en segundo plano. (UI) */
  void probarConexion( final FilaBase f ) {
    if ( !validarBase( f, false ) ) {
      return;
    }
    final BaseDePrueba b = baseDe( f );
    final Map<String, String> claves = Collections.singletonMap( b.conexion.toLowerCase( Locale.ROOT ), claveDe( f,
      b ) );
    resultado( f.probado, t.s( "ui.as_base_probando" ), gris );
    trabajando = true;
    habilitar();
    final int g = gen;
    panel.servicio.enSegundoPlano( () -> {
      String mal = null;
      try {
        new CorrectorBases.Sesion( packDe( b ), null, "", claves ).probar();
      } catch ( CorrectorBases.Falla x ) {
        mal = x.mensaje.texto( t );
      } catch ( Throwable x ) {
        mal = PanelCorrector.mensaje( x, t );
      }
      final String m = mal;
      panel.enUi( () -> {
        if ( shell.isDisposed() ) {
          return;
        }
        trabajando = false;
        habilitar();
        if ( g != gen ) {
          return;
        }
        if ( m == null ) {
          resultado( f.probado, t.f( "ui.as_base_probado_ok", b.base ), verde );
          String c = f.clave.getText();
          if ( !c.isEmpty() ) {
            CorrectorBases.recordar( b, c );
          }
        } else {
          resultado( f.probado, t.f( "ui.as_base_probado_mal", m ), rojo );
        }
      } );
      return null;
    } );
  }

  /** «Generar el script desde la base actual»: pregunta donde guardarlo y lo genera. (UI, por clic) */
  private void elegirYGenerar( FilaBase f ) {
    FileDialog fd = new FileDialog( shell, SWT.SAVE );
    fd.setText( t.s( "ui.as_guardar_script" ) );
    fd.setFilterExtensions( new String[] { "*.sql" } );
    fd.setOverwrite( true );
    File dir = copia.dirOriginal != null ? copia.dirOriginal : new File( System.getProperty( "user.home" ) );
    fd.setFilterPath( dir.getPath() );
    fd.setFileName( CorrectorVolcado.nombreArchivo( f.propuesta.baseOriginal ) );
    String r = fd.open();
    if ( r != null ) {
      generarScript( f.propuesta.conexion, new File( r ) );
    }
  }

  /**
   * Lee la base ORIGINAL de la conexion (solo lectura) y escribe el script en {@code destino}; al terminar lo pone el
   * primero en «Scripts». La contraseña es la escrita (o la de esta sesion). (UI)
   */
  void generarScript( String conexion, final File archivo ) {
    final FilaBase f = fila( conexion );
    if ( f == null ) {
      return;
    }
    final BaseDePrueba b = baseDe( f );
    final String original = f.propuesta.baseOriginal == null ? "" : f.propuesta.baseOriginal;
    final String clave = claveDe( f, b );
    final List<String> vacias = new ArrayList<String>();
    for ( CorrectorBases.TablaEscrita te : inventario.tablas ) {
      if ( te.conexion != null && te.conexion.equalsIgnoreCase( conexion ) ) {
        vacias.add( te.tabla );
      }
    }
    resultadoLink( f, t.f( "ui.as_base_generando", original ), gris );
    trabajando = true;
    habilitar();
    panel.servicio.enSegundoPlano( () -> {
      CorrectorVolcado.Volcado v = null;
      String mal = null;
      try {
        v = CorrectorVolcado.volcar( b, original, clave, vacias, archivo, CorrectorVolcado.MAX_FILAS );
      } catch ( Throwable x ) {
        mal = PanelCorrector.mensaje( x, t );
      }
      final CorrectorVolcado.Volcado hecho = v;
      final String m = mal;
      panel.enUi( () -> {
        if ( shell.isDisposed() ) {
          return;
        }
        trabajando = false;
        habilitar();
        if ( hecho == null ) {
          resultadoLink( f, t.f( "ui.as_base_generar_mal", m ), rojo );
          return;
        }
        f.scriptGenerado = hecho.archivo;
        String ruta = hecho.archivo.getPath();
        List<String> otros = new ArrayList<String>();
        for ( String s : f.scripts.getText().split( ";" ) ) {
          if ( !s.trim().isEmpty() && !s.trim().equalsIgnoreCase( ruta ) ) {
            otros.add( s.trim() );
          }
        }
        otros.add( 0, ruta );
        f.scripts.setText( String.join( "; ", otros ) );
        StringBuilder sb = new StringBuilder( t.f( "ui.as_base_generado", "<a>" + hecho.archivo.getName() + "</a>",
          hecho.tablas.size(), hecho.filas ) );
        if ( !hecho.vacias.isEmpty() ) {
          sb.append( ' ' ).append( t.f( "ui.as_base_generado_vacias", String.join( ", ", hecho.vacias ) ) );
        }
        if ( !hecho.cortadas.isEmpty() ) {
          sb.append( ' ' ).append( t.f( "ui.as_base_generado_cortadas", CorrectorVolcado.MAX_FILAS, String.join( ", ",
            hecho.cortadas ) ) );
        }
        resultadoLink( f, sb.toString(), verde );
        limpiar( "as_base_scripts:" + f.propuesta.conexion );
        String c = f.clave.getText();
        if ( !c.isEmpty() ) {
          CorrectorBases.recordar( b, c );
        }
      } );
      return null;
    } );
  }

  private FilaBase fila( String conexion ) {
    for ( FilaBase f : filasBase ) {
      if ( f.propuesta.conexion.equalsIgnoreCase( conexion ) ) {
        return f;
      }
    }
    return null;
  }

  /** El texto del resultado de «Probar conexion» o de «Generar el script» de una conexion (pruebas). */
  String resultadoBase( String conexion, boolean generar ) {
    FilaBase f = fila( conexion );
    return f == null ? "" : generar ? f.generado.getText() : f.probado.getText();
  }

  private void resultado( Label l, String texto, Color color ) {
    l.setText( PanelInformes.esc( texto ) );
    l.setForeground( color );
    mostrar( l, true );
  }

  private void resultadoLink( FilaBase f, String texto, Color color ) {
    f.generado.setText( texto.replace( "&", "&&" ) );
    f.generado.setForeground( color );
    mostrar( f.generado, true );
  }

  private void elegirScripts( Text campo ) {
    FileDialog fd = new FileDialog( shell, SWT.OPEN | SWT.MULTI );
    fd.setFilterExtensions( new String[] { "*.sql", "*.*" } );
    if ( copia.dirOriginal != null ) {
      fd.setFilterPath( copia.dirOriginal.getPath() );
    }
    if ( fd.open() == null ) {
      return;
    }
    List<String> l = new ArrayList<String>();
    for ( String n : fd.getFileNames() ) {
      l.add( new File( fd.getFilterPath(), n ).getPath() );
    }
    String antes = campo.getText().trim();
    campo.setText( ( antes.isEmpty() ? "" : antes + "; " ) + String.join( "; ", l ) );
  }

  /** Los archivos de un campo de scripts («a.sql; b.sql») que no existen. */
  private static List<String> faltan( String texto ) {
    List<String> l = new ArrayList<String>();
    for ( String s : texto.split( ";" ) ) {
      String x = s.trim();
      if ( !x.isEmpty() && ( !new File( x ).isAbsolute() || !new File( x ).isFile() ) ) {
        l.add( x );
      }
    }
    return l;
  }

  /** Los archivos de un campo de scripts que existen. */
  private static List<File> archivosSinError( String texto ) {
    List<File> l = new ArrayList<File>();
    for ( String s : texto.split( ";" ) ) {
      String x = s.trim();
      if ( !x.isEmpty() && new File( x ).isAbsolute() && new File( x ).isFile() ) {
        l.add( new File( x ).getAbsoluteFile() );
      }
    }
    return l;
  }

  /** Lo que el docente escribio queda en memoria para corregir en esta sesion de Spoon (nunca en un archivo). */
  private void recordarClaves() {
    for ( FilaBase f : filasBase ) {
      String clave = f.clave.getText();
      BaseDePrueba b = baseDe( f );
      if ( !clave.isEmpty() || !CorrectorBases.recordada( b ) ) {
        CorrectorBases.recordar( b, clave );
      }
    }
  }

  /** Las tablas que se comparan con la clave y las ignoradas que marco el docente en el paso 3. */
  private List<TablaSalida> tablasEditadas() {
    List<TablaSalida> l = new ArrayList<TablaSalida>();
    for ( TablaSalida a : tablasAnalizadas ) {
      if ( tablasFuera.contains( k( a.nombre ) ) ) {
        continue;
      }
      TablaSalida x = new TablaSalida( a.conexion, a.nombre );
      x.columnasClave = new ArrayList<String>( clavesTabla.get( k( a.nombre ) ) );
      x.ignorar = new ArrayList<String>( ignorarTabla.get( k( a.nombre ) ) );
      l.add( x );
    }
    return l;
  }

  /** Los items del combo de captura (pruebas). */
  List<String> itemsCaptura() {
    List<String> l = new ArrayList<String>();
    for ( String[] c : capturas ) {
      l.add( c[ 0 ] + ( c[ 1 ] == null ? "" : ":" + c[ 1 ] ) );
    }
    return l;
  }

  /** «x.zip» → «x-alumnos.zip»; la carpeta «x» → «x-alumnos.zip» al lado. */
  static File copiaDe( File destino ) {
    String n = destino.getName().replaceFirst( "(?i)\\.zip$", "" );
    File padre = destino.getAbsoluteFile().getParentFile();
    return new File( padre, n + "-alumnos.zip" );
  }

  /** Junto a la solucion: «pack-<nombre>», o «pack-<nombre>-2»... si ya hay una carpeta con archivos. */
  private File destinoPropuesto( String base ) {
    File dir = copia.dirOriginal != null ? copia.dirOriginal : new File( System.getProperty( "user.home" ) );
    String n = "pack-" + CorrectorSandbox.limpio( base );
    File f = new File( dir, n );
    for ( int i = 2; i < 100 && f.exists() && !vacia( f ); i++ ) {
      f = new File( dir, n + "-" + i );
    }
    return f;
  }

  private static boolean vacia( File d ) {
    String[] l = d.list();
    return l == null || l.length == 0;
  }

  // ---------------------------------------------------------------- ayudas de la UI

  /** Los nombres de los steps de la solucion (para elegir el de salida), leidos del XML de la copia. */
  private Element leerSteps() {
    try {
      String xml = new String( Files.readAllBytes( copia.ktr.toPath() ), StandardCharsets.UTF_8 );
      Element raiz = Xml.parse( xml ).getDocumentElement();
      for ( Element e : Xml.elementos( raiz ) ) {
        if ( "step".equals( e.getTagName() ) ) {
          for ( Element h : Xml.elementos( e ) ) {
            if ( "name".equals( h.getTagName() ) && !h.getTextContent().trim().isEmpty() ) {
              steps.add( h.getTextContent().trim() );
            }
          }
        }
      }
      return raiz;
    } catch ( Throwable x ) {
      panel.servicio.log( "Pentaho Live: could not list the steps of the solution", x );
      return null;
    }
  }

  /** «Ejercicio 2.ktr · 23 pasos · base de datos: CLUB_DW (MySQL), CLUB_Origen (MySQL)». */
  private String resumenSolucion() {
    List<String> cxs = new ArrayList<String>();
    for ( CorrectorBases.Conexion c : inventario.usadas() ) {
      cxs.add( c.nombre + " (" + ( c.tipo == null ? "?" : c.tipo ) + ")" );
    }
    String dir = copia.dirOriginal == null ? "" : copia.dirOriginal.getPath();
    return cxs.isEmpty() ? t.f( "ui.as_solucion_info", copia.nombre, steps.size(), dir ) : t.f(
      "ui.as_solucion_info_bd", copia.nombre, steps.size(), dir, String.join( ", ", cxs ) );
  }

  private String descripcion( Regla r ) {
    List<String> p = new ArrayList<String>();
    p.add( r.tipo );
    if ( !r.steps.isEmpty() ) {
      p.add( String.join( ", ", r.steps ) );
    }
    if ( r.valor != null ) {
      p.add( String.valueOf( r.valor ) );
    }
    return String.join( " · ", p );
  }

  private String etiqueta( Regla r ) {
    return r.etiqueta != null && !r.etiqueta.trim().isEmpty() ? r.etiqueta.trim() : r.id;
  }

  private String etiqueta( ItemRubrica x ) {
    if ( x.etiqueta != null && !x.etiqueta.trim().isEmpty() ) {
      return x.etiqueta.trim();
    }
    if ( x.esDeSalida() ) {
      return t.s( "check." + x.check );
    }
    if ( x.esRegla() ) {
      for ( Regla r : sugeridas ) {
        if ( r.id.equals( x.idRegla() ) ) {
          return etiqueta( r );
        }
      }
    }
    return x.check;
  }

  /**
   * Un campo de texto en una pagina (grilla de 3 columnas): rotulo, campo y boton «…» (si {@code idBoton}), y debajo
   * la ayuda (gris) y su error (rojo, oculto).
   */
  private Text campo( Composite p, int pag, String id, String rotulo, String ayuda, int estilo, String idBoton ) {
    rotulo( p, rotulo );
    Text x = new Text( p, SWT.BORDER | SWT.SINGLE | estilo );
    GridData g = new GridData( SWT.FILL, SWT.CENTER, true, false, idBoton == null ? 2 : 1, 1 );
    g.widthHint = 380;
    x.setLayoutData( g );
    PanelCorrector.id( x, id );
    if ( idBoton != null ) {
      final Text campo = x;
      boton( p, idBoton, "ui.elegir", () -> elegirCarpeta( campo ) );
    }
    // la ayuda y el error, juntos en la celda de las columnas 2 y 3 (asi un error oculto no corre la grilla)
    new Label( p, SWT.NONE );
    Composite debajo = new Composite( p, SWT.NONE );
    GridLayout gd = new GridLayout( 1, false );
    gd.marginWidth = 0;
    gd.marginHeight = 0;
    gd.verticalSpacing = 1;
    debajo.setLayout( gd );
    debajo.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false, 2, 1 ) );
    ayuda( debajo, 1, ayuda );
    error( debajo, 1, id );
    paginaDe.put( id, pag );
    x.addListener( SWT.Modify, e -> limpiar( id ) );
    return x;
  }

  private Combo combo( Composite p, int pag, String id, String rotulo, String ayuda ) {
    Label r = rotulo( p, rotulo );
    PanelCorrector.id( r, id + ":rotulo" );
    Combo c = new Combo( p, SWT.READ_ONLY );
    GridData g = new GridData( SWT.FILL, SWT.CENTER, true, false );
    g.widthHint = 380;
    c.setLayoutData( g );
    PanelCorrector.id( c, id );
    if ( "as_salida".equals( id ) ) {
      boton( p, "as_reanalizar", "ui.as_reanalizar", this::analizar );
    } else if ( "as_tabla_vista".equals( id ) ) {
      Button b = new Button( p, SWT.CHECK );
      b.setText( t.s( "ui.as_tabla_comparar" ) );
      b.setSelection( true );
      PanelCorrector.id( b, "as_tabla_comparar" );
      b.addListener( SWT.Selection, e -> compararTabla() );
    } else {
      new Label( p, SWT.NONE );
    }
    Label e1 = new Label( p, SWT.NONE );
    PanelCorrector.id( e1, id + ":espacio" );
    Label a = ayuda( p, 2, ayuda );
    PanelCorrector.id( a, id + ":ayuda" );
    paginaDe.put( id, pag );
    return c;
  }

  /** Muestra u oculta la fila de un combo (rotulo, combo, boton, ayuda) del paso 3. */
  private void mostrarFila( String id, boolean si ) {
    Control c = buscar( id );
    if ( c == null ) {
      return;
    }
    Composite p = c.getParent();
    Control[] hs = p.getChildren();
    for ( int i = 0; i < hs.length; i++ ) {
      if ( hs[ i ] == c ) {
        for ( int j = Math.max( 0, i - 1 ); j <= Math.min( hs.length - 1, i + 3 ); j++ ) {
          Object ld = hs[ j ].getLayoutData();
          if ( !( ld instanceof GridData ) ) {
            ld = new GridData();
            hs[ j ].setLayoutData( ld );
          }
          ( (GridData) ld ).exclude = !si;
          hs[ j ].setVisible( si );
        }
      }
    }
    p.layout( true, true );
  }

  private Button casilla( Composite p, int pag, String id, String rotulo, String ayuda ) {
    Button b = new Button( p, SWT.CHECK );
    b.setText( t.s( rotulo ) );
    GridData g = new GridData( SWT.FILL, SWT.CENTER, true, false, 3, 1 );
    g.verticalIndent = 4;
    b.setLayoutData( g );
    PanelCorrector.id( b, id );
    new Label( p, SWT.NONE );
    ayuda( p, 2, ayuda );
    paginaDe.put( id, pag );
    return b;
  }

  private Label ayuda( Composite p, int columnas, String clave ) {
    return ayudaTexto( p, columnas, t.s( clave ) );
  }

  private Label ayudaTexto( Composite p, int columnas, String texto ) {
    Label l = new Label( p, SWT.WRAP );
    l.setText( PanelInformes.esc( texto ) );
    l.setForeground( gris );
    GridData g = new GridData( SWT.FILL, SWT.TOP, true, false, columnas, 1 );
    g.widthHint = 380;
    l.setLayoutData( g );
    return l;
  }

  private Label error( Composite p, int columnas, String id ) {
    Label l = new Label( p, SWT.WRAP );
    l.setForeground( rojo );
    GridData g = new GridData( SWT.FILL, SWT.TOP, true, false, "as_columnas_caja".equals( id ) ? 3 : columnas, 1 );
    g.widthHint = 380;
    g.exclude = true;
    l.setLayoutData( g );
    l.setVisible( false );
    PanelCorrector.id( l, "as_error:" + id );
    errores.put( id, l );
    return l;
  }

  private Label rotulo( Composite p, String clave ) {
    Label l = new Label( p, SWT.NONE );
    l.setText( t.s( clave ) );
    l.setLayoutData( new GridData( SWT.LEFT, SWT.CENTER, false, false ) );
    return l;
  }

  private void espacio( Composite p ) {
    Label l = new Label( p, SWT.NONE );
    GridData g = new GridData( SWT.FILL, SWT.TOP, true, false, 3, 1 );
    g.heightHint = 4;
    l.setLayoutData( g );
  }

  private Label etiqueta( Composite p, String id ) {
    Label l = new Label( p, SWT.WRAP );
    GridData g = new GridData( SWT.FILL, SWT.TOP, true, false, 3, 1 );
    g.widthHint = 560;
    l.setLayoutData( g );
    PanelCorrector.id( l, id );
    return l;
  }

  private Button boton( Composite p, String id, String rotulo, Runnable accion ) {
    return panel.boton( p, id, rotulo, null, () -> {
      try {
        accion.run();
      } catch ( Throwable x ) {
        fallo( x );
      }
    } );
  }

  private void elegirCarpeta( Text campo ) {
    DirectoryDialog dd = new DirectoryDialog( shell );
    if ( !campo.getText().trim().isEmpty() ) {
      dd.setFilterPath( campo.getText().trim() );
    }
    String r = dd.open();
    if ( r != null ) {
      campo.setText( r );
    }
  }

  private void ajustar( ScrolledComposite sc ) {
    if ( sc == null || sc.isDisposed() || sc.getContent() == null ) {
      return;
    }
    int w = Math.max( 300, sc.getClientArea().width );
    sc.setMinSize( sc.getContent().computeSize( w, SWT.DEFAULT ) );
  }

  /** La linea de estado del pie (gris; roja si es un error). */
  private void info( String texto, boolean error ) {
    if ( estado.isDisposed() ) {
      return;
    }
    estado.setText( PanelInformes.esc( texto ) );
    estado.setForeground( error ? rojo : gris );
    estado.getParent().layout( true, true );
  }

  /** El recuadro sobre los botones: rojo con un error, verde con un exito; null lo oculta. */
  private void recuadro( String texto, boolean error ) {
    if ( caja.isDisposed() ) {
      return;
    }
    boolean ver = texto != null && !texto.isEmpty();
    cajaTexto.setText( ver ? PanelInformes.esc( texto ) : "" );
    Color fondo = error ? fondoError : fondoOk;
    caja.setBackground( fondo );
    cajaTexto.setBackground( fondo );
    cajaTexto.setForeground( error ? rojo : verde );
    ( (GridData) caja.getLayoutData() ).exclude = !ver;
    caja.setVisible( ver );
    caja.getParent().layout( true, true );
  }

  /** Un error de analizar o guardar: el recuadro con el texto entero, y el campo marcado si se sabe cual es. */
  private void fallo( Throwable x ) {
    String texto = textoFallo( x );
    recuadro( texto, true );
    info( t.s( "ui.as_fallo_corto" ), true );
    Throwable c = causa( x );
    if ( c instanceof CorrectorCrearPack.Fallo && CorrectorCrearPack.E_DESTINO.equals( ( (CorrectorCrearPack.Fallo) c )
      .codigo ) ) {
      marcar( "as_destino", ( (CorrectorCrearPack.Fallo) c ).texto( t ) );
    }
  }

  /** Texto del estado y del recuadro del asistente (pruebas). */
  String estado() {
    String r = cajaTexto.isDisposed() || !caja.getVisible() ? "" : cajaTexto.getText();
    return estado.getText() + ( r.isEmpty() ? "" : "\n" + r );
  }

  private static Throwable causa( Throwable x ) {
    Throwable c = x;
    while ( c.getCause() != null && c.getCause() != c && !( c instanceof CorrectorCrearPack.Fallo ) ) {
      c = c.getCause();
    }
    return c;
  }

  private String textoFallo( Throwable x ) {
    Throwable c = causa( x );
    if ( c instanceof CorrectorCrearPack.Fallo ) {
      CorrectorCrearPack.Fallo f = (CorrectorCrearPack.Fallo) c;
      return t.f( "ui.as_error", f.nombre( t ), f.texto( t ) );
    }
    return t.f( "ui.error", PanelCorrector.mensaje( x, t ) );
  }

  private String numero( double x ) {
    return x == Math.rint( x ) ? String.valueOf( (long) x ) : String.format( t.locale(), "%.2f", x ).replaceAll(
      "0+$", "" );
  }

  private static String k( String tabla ) {
    return tabla == null ? "" : tabla.trim().toLowerCase( Locale.ROOT );
  }

  private void alCerrar() {
    cerrado = true;
    gen++;
    for ( org.eclipse.swt.graphics.Resource r : new org.eclipse.swt.graphics.Resource[] { azul, verde, gris, rojo,
      naranja, fondoIzq, fondoError, fondoOk, negrita, grande } ) {
      if ( r != null && !r.isDisposed() ) {
        r.dispose();
      }
    }
    PanelCorrector.borrar( tmp );
  }

  /** Los ids de las reglas marcadas (pruebas). */
  List<String> reglasMarcadas() {
    List<String> l = new ArrayList<String>();
    for ( Regla r : marcadas() ) {
      l.add( r.id );
    }
    return l;
  }

  /** check → puntos de la rubrica que se esta editando, solo lo que cuenta (pruebas). */
  List<String> rubrica() {
    List<String> l = new ArrayList<String>();
    for ( ItemRubrica x : rubricaFinal( marcadas() ) ) {
      Text p = puntos.get( x.check );
      l.add( x.check + "=" + ( p == null || p.isDisposed() ? numero( x.puntos ) : p.getText() ) );
    }
    return l;
  }
}
