package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.custom.StackLayout;
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
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.w3c.dom.Element;

import io.github.ivi2511.pentahomcp.corrector.modelo.BaseDePrueba;
import io.github.ivi2511.pentahomcp.corrector.modelo.ItemRubrica;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.Regla;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.Aviso;
import io.github.ivi2511.pentahomcp.corrector.modelo.TablaSalida;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.ui.PanelInformes;

/**
 * «Crear pack desde esta transformacion» (docs/corrector-diseno.md §9): asistente de 4 pasos sobre la transformacion
 * activa, que llama a lo mismo que {@code create_exercise_pack} ({@link CorrectorCrearPack#crear}).
 * <ol>
 * <li><b>Ejercicio</b>: titulo, donde guardar el pack (carpeta o .zip), la carpeta con los datos del caso oculto
 * (opcional), la copia para alumnos, idioma y captura (las tablas que escribe la solucion, el archivo de cada Text
 * file output o las filas del step de salida). Si la solucion usa conexiones a un servidor (MySQL, PostgreSQL, H2),
 * la «Base de prueba» de cada una: servidor, base de prueba (la original con {@code _correccion}), usuario,
 * contraseña (solo en memoria) y los scripts que la llenan antes de cada caso. «Analizar» corre la solucion en una
 * JVM hija y arma un pack de prueba en temporales: nada se guarda todavia.</li>
 * <li><b>Entradas, salida y claves</b>: lo detectado (entradas, step de salida, columnas con su tipo) y la salida
 * esperada del caso visible <b>antes de guardar</b>; el step de salida y las claves se pueden cambiar y volver a
 * analizar.</li>
 * <li><b>Reglas</b> sugeridas a partir de la solucion (cada una suma en la rubrica), para marcar o desmarcar.</li>
 * <li><b>Rubrica</b>: los puntos de cada criterio y la escala. «Guardar pack» vuelve a armarlo en el destino con lo
 * elegido, verifica la solucion con el otro idioma y escribe la copia para alumnos.</li>
 * </ol>
 * Una Shell modal al panel que no bloquea el hilo (no tiene su propio bucle de eventos): lo pesado va al pool del
 * panel. Solo se abre por un clic.
 *
 * @dueño C8
 */
final class AsistentePack {

  static final int PAGINAS = 4;
  /** Filas de la salida esperada que se muestran. */
  static final int MAX_FILAS = 30;
  private static final AtomicInteger ANALISIS = new AtomicInteger();

  final PanelCorrector panel;
  final Rotulos t;
  final Shell shell;
  final CorrectorTools.Copia copia;
  /** Temporal de la copia de la solucion y de los packs de analisis; se borra al cerrar. */
  final File tmp;
  private final StackLayout pila = new StackLayout();
  private final Composite contenido;
  private final Composite[] paginas = new Composite[ PAGINAS ];
  private int pagina;
  /** Lo que muestra el StackLayout por pagina (la 1, con base de prueba, va dentro de un ScrolledComposite). */
  private final Control[] tarjetas = new Control[ PAGINAS ];

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

    FilaBase( BaseDePrueba propuesta ) {
      this.propuesta = propuesta;
    }
  }

  final Label paso;
  final Label estado;
  final Button anterior;
  final Button siguiente;
  final Button guardar;
  final Button usar;
  final Button cerrar;

  // paso 1
  final Text titulo;
  final Text destino;
  final Text oculto;
  final Button copiaAlumnos;
  final Combo idioma;
  final Combo captura;
  /** Lo que lee y escribe la solucion (solo XML: nada corre en Spoon). */
  final CorrectorBases.Inventario inventario;
  /** Una opcion por item de {@link #captura}: {codigo de captura, tabla o step (o null)}. */
  private final List<String[]> capturas = new ArrayList<String[]>();
  /** La «Base de prueba» de cada conexion de servidor de la solucion. */
  final List<FilaBase> filasBase = new ArrayList<FilaBase>();
  /** Correr dos veces cada caso (una carga que duplica filas al repetirla pierde puntos). */
  final Button dosVeces;
  // paso 2
  final Label entradas;
  final Combo salida;
  final Text claves;
  final Label columnas;
  final Table esperada;
  final Label avisos;
  final Button reanalizar;
  /** Captura tables: la clave y las columnas ignoradas de cada tabla, y que tabla se ve en la salida esperada. */
  private final Composite cajaTablas;
  private final List<Text[]> textosTablas = new ArrayList<Text[]>();
  final Combo tablaVista;
  private List<TablaSalida> tablasAnalizadas = new ArrayList<TablaSalida>();
  private Map<String, CorrectorTabla> esperadasTablas = new LinkedHashMap<String, CorrectorTabla>();
  // paso 3
  private final Composite cajaReglas;
  private final List<Button> marcasReglas = new ArrayList<Button>();
  // paso 4
  private final Composite cajaRubrica;
  private final List<Text> puntos = new ArrayList<Text>();
  final Text escala;

  private final List<String> steps = new ArrayList<String>();
  private CorrectorCrearPack.Creado analisis;
  private Pack analizado;
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
    leerSteps();
    CorrectorBases.Inventario inv;
    try {
      inv = CorrectorBases.inventario( copia.ktr );
    } catch ( Throwable x ) {
      panel.servicio.log( "Pentaho Live: could not read the connections of the solution", x );
      inv = new CorrectorBases.Inventario();
    }
    inventario = inv;
    List<BaseDePrueba> propuestas = CorrectorBases.propuestas( inventario );
    String base = copia.nombre.replaceFirst( "(?i)\\.ktr$", "" );
    shell = new Shell( panel.raiz().getShell(), SWT.DIALOG_TRIM | SWT.RESIZE | SWT.PRIMARY_MODAL );
    shell.setText( t.f( "ui.as_titulo", base ) );
    GridLayout g = new GridLayout( 1, false );
    g.marginWidth = 10;
    g.marginHeight = 10;
    shell.setLayout( g );
    shell.addDisposeListener( e -> alCerrar() );

    paso = new Label( shell, SWT.NONE );
    paso.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    PanelCorrector.id( paso, "as_paso" );
    Label sep = new Label( shell, SWT.SEPARATOR | SWT.HORIZONTAL );
    sep.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );

    contenido = new Composite( shell, SWT.NONE );
    contenido.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, true ) );
    contenido.setLayout( pila );
    final ScrolledComposite sc1 = new ScrolledComposite( contenido, SWT.V_SCROLL );
    sc1.setExpandHorizontal( true );
    sc1.setExpandVertical( true );
    for ( int i = 0; i < PAGINAS; i++ ) {
      paginas[ i ] = new Composite( i == 0 ? sc1 : contenido, SWT.NONE );
      GridLayout gp = new GridLayout( 3, false );
      gp.marginWidth = 0;
      paginas[ i ].setLayout( gp );
      tarjetas[ i ] = i == 0 ? sc1 : paginas[ i ];
    }
    sc1.setContent( paginas[ 0 ] );

    // ---- 1: ejercicio
    Composite p1 = paginas[ 0 ];
    titulo = campo( p1, "as_titulo", "ui.as_titulo_ejercicio", null );
    titulo.setText( base );
    new Label( p1, SWT.NONE );
    destino = campo( p1, "as_destino", "ui.as_destino", "ui.as_destino_tip" );
    File dir = copia.dirOriginal != null ? copia.dirOriginal : new File( System.getProperty( "user.home" ) );
    destino.setText( new File( dir, "pack-" + CorrectorSandbox.limpio( base ) ).getPath() );
    boton( p1, "as_elegir_destino", "ui.elegir", () -> elegirCarpeta( destino ) );
    oculto = campo( p1, "as_oculto", "ui.as_oculto", "ui.as_oculto_tip" );
    boton( p1, "as_elegir_oculto", "ui.elegir", () -> elegirCarpeta( oculto ) );
    copiaAlumnos = new Button( p1, SWT.CHECK );
    copiaAlumnos.setText( t.s( "ui.as_copia" ) );
    copiaAlumnos.setSelection( true );
    copiaAlumnos.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false, 3, 1 ) );
    PanelCorrector.id( copiaAlumnos, "as_copia" );
    rotulo( p1, "ui.as_idioma" );
    idioma = new Combo( p1, SWT.READ_ONLY );
    idioma.setItems( new String[] { "Español", "English", "Português" } );
    idioma.select( "en".equals( panel.idioma ) ? 1 : "pt".equals( panel.idioma ) ? 2 : 0 );
    PanelCorrector.id( idioma, "as_idioma" );
    new Label( p1, SWT.NONE );
    rotulo( p1, "ui.as_captura" );
    captura = new Combo( p1, SWT.READ_ONLY );
    llenarCapturas( !propuestas.isEmpty() );
    for ( String[] c : capturas ) {
      captura.add( c[ 2 ] );
    }
    captura.select( 0 );
    captura.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
    PanelCorrector.id( captura, "as_captura" );
    new Label( p1, SWT.NONE );
    Button dos = null;
    if ( !propuestas.isEmpty() ) {
      dos = grupoBase( p1, propuestas );
    }
    dosVeces = dos;
    final Composite p1f = p1;
    sc1.addListener( SWT.Resize, e -> sc1.setMinSize( p1f.computeSize( sc1.getClientArea().width, SWT.DEFAULT ) ) );

    // ---- 2: entradas, salida y claves
    Composite p2 = paginas[ 1 ];
    entradas = etiqueta( p2, "as_entradas" );
    rotulo( p2, "ui.as_salida" );
    salida = new Combo( p2, SWT.READ_ONLY );
    salida.add( t.s( "ui.as_salida_auto" ) );
    for ( String s : steps ) {
      salida.add( s );
    }
    salida.select( 0 );
    salida.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false, 2, 1 ) );
    PanelCorrector.id( salida, "as_salida" );
    claves = campo( p2, "as_claves", "ui.as_claves", "ui.as_claves_tip" );
    reanalizar = boton( p2, "as_reanalizar", "ui.as_reanalizar", this::analizar );
    columnas = etiqueta( p2, "as_columnas" );
    Label le = etiqueta( p2, "as_esperada_titulo" );
    le.setText( t.s( "ui.as_esperada_vacia" ) );
    esperada = new Table( p2, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL );
    esperada.setHeaderVisible( true );
    esperada.setLinesVisible( true );
    GridData ge = new GridData( SWT.FILL, SWT.FILL, true, true, 3, 1 );
    ge.heightHint = 180;
    esperada.setLayoutData( ge );
    PanelCorrector.id( esperada, "as_esperada" );
    avisos = etiqueta( p2, "as_avisos" );
    avisos.setForeground( shell.getDisplay().getSystemColor( SWT.COLOR_DARK_RED ) );
    cajaTablas = new Composite( p2, SWT.NONE );
    cajaTablas.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false, 3, 1 ) );
    cajaTablas.setLayout( new GridLayout( 3, false ) );
    rotulo( p2, "ui.as_tabla_vista" );
    tablaVista = new Combo( p2, SWT.READ_ONLY );
    tablaVista.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false, 2, 1 ) );
    tablaVista.addListener( SWT.Selection, e -> verTabla() );
    PanelCorrector.id( tablaVista, "as_tabla_vista" );
    tablaVista.setEnabled( false );

    // ---- 3: reglas
    Composite p3 = paginas[ 2 ];
    Label lr = etiqueta( p3, "as_reglas_intro" );
    lr.setText( t.s( "ui.as_reglas_intro" ) );
    cajaReglas = new Composite( p3, SWT.NONE );
    cajaReglas.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, true, 3, 1 ) );
    cajaReglas.setLayout( new GridLayout( 1, false ) );

    // ---- 4: rubrica
    Composite p4 = paginas[ 3 ];
    Label lru = etiqueta( p4, "as_rubrica_intro" );
    lru.setText( t.s( "ui.as_rubrica_intro" ) );
    ScrolledComposite sc = new ScrolledComposite( p4, SWT.V_SCROLL );
    sc.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, true, 3, 1 ) );
    cajaRubrica = new Composite( sc, SWT.NONE );
    cajaRubrica.setLayout( new GridLayout( 2, false ) );
    sc.setContent( cajaRubrica );
    sc.setExpandHorizontal( true );
    sc.setExpandVertical( true );
    sc.addListener( SWT.Resize, e -> sc.setMinSize( cajaRubrica.computeSize( SWT.DEFAULT, SWT.DEFAULT ) ) );
    escala = campo( p4, "as_escala", "ui.as_escala", null );
    escala.setText( "10" );
    new Label( p4, SWT.NONE );

    // ---- pie
    Label sep2 = new Label( shell, SWT.SEPARATOR | SWT.HORIZONTAL );
    sep2.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    estado = new Label( shell, SWT.WRAP );
    GridData gs = new GridData( SWT.FILL, SWT.TOP, true, false );
    gs.widthHint = 600;
    estado.setLayoutData( gs );
    PanelCorrector.id( estado, "as_estado" );
    Composite bs = new Composite( shell, SWT.NONE );
    bs.setLayoutData( new GridData( SWT.END, SWT.CENTER, true, false ) );
    bs.setLayout( new RowLayout( SWT.HORIZONTAL ) );
    anterior = boton( bs, "as_anterior", "ui.as_anterior", () -> ir( pagina - 1 ) );
    siguiente = boton( bs, "as_siguiente", "ui.as_siguiente", this::siguiente );
    guardar = boton( bs, "as_guardar", "ui.as_guardar", this::guardar );
    usar = boton( bs, "as_usar", "ui.as_usar", this::usar );
    cerrar = boton( bs, "as_cerrar", "ui.as_cerrar", shell::dispose );

    ir( 0 );
    shell.setSize( propuestas.isEmpty() ? 800 : 880, propuestas.isEmpty() ? 580 : 760 );
    Rectangle p = panel.raiz().getShell().getBounds();
    Point s = shell.getSize();
    shell.setLocation( p.x + Math.max( 0, ( p.width - s.x ) / 2 ), p.y + Math.max( 0, ( p.height - s.y ) / 4 ) );
    shell.open();
  }

  // ---------------------------------------------------------------- navegacion

  /** La pagina actual (0..3). */
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

  private void siguiente() {
    if ( pagina == 0 ) {
      analizar();
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
    pagina = n;
    pila.topControl = tarjetas[ n ];
    contenido.layout( true, true );
    paso.setText( t.f( "ui.as_paso", n + 1, PAGINAS, t.s( "ui.as_paso" + ( n + 1 ) ) ) );
    habilitar();
  }

  private void habilitar() {
    boolean hecho = guardado != null;
    anterior.setEnabled( !trabajando && !hecho && pagina > 0 );
    siguiente.setEnabled( !trabajando && !hecho && pagina < PAGINAS - 1 && ( pagina == 0 || analisis != null ) );
    siguiente.setText( t.s( pagina == 0 ? "ui.as_analizar" : "ui.as_siguiente" ) );
    siguiente.getParent().layout( true );
    guardar.setEnabled( !trabajando && !hecho && pagina == PAGINAS - 1 && analisis != null );
    usar.setEnabled( hecho );
    reanalizar.setEnabled( !trabajando && !hecho );
  }

  // ---------------------------------------------------------------- analizar

  /** Corre la solucion en una hija y arma un pack de prueba en temporales (nada se guarda). (UI) */
  void analizar() {
    final CorrectorCrearPack.Opciones op = opciones( false );
    if ( op == null ) {
      return;
    }
    op.destino = new File( new File( tmp, "analisis-" + ANALISIS.incrementAndGet() ), "pack" );
    op.verificarLocales = false;
    op.copiaAlumnos = null;
    op.reglas = null;
    op.rubrica = null;
    final int g = ++gen;
    trabajando = true;
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
          info( textoFallo( x ), true );
        } );
      }
      return null;
    } );
  }

  /** Llena los pasos 2, 3 y 4 con lo que encontro la hija. (UI) */
  private void analizado( CorrectorCrearPack.Creado cr, Pack p, CorrectorTabla tabla ) {
    analisis = cr;
    analizado = p;
    CorrectorCrearPack.Detectado d = cr.detectado;
    entradas.setText( PanelInformes.esc( t.f( "ui.as_entradas", d.entradas.isEmpty() ? "-" : String.join( ", ",
      d.entradas ) ) ) );
    int i = d.stepSalida == null ? -1 : steps.indexOf( d.stepSalida );
    salida.select( i < 0 ? 0 : i + 1 );
    claves.setText( String.join( ", ", p.comparar.columnasClave ) );
    boolean tablas = Pack.Salida.CAPTURA_TABLAS.equals( p.salida.captura );
    salida.setEnabled( !tablas );
    claves.setEnabled( !tablas );
    pintarTablas( tablas ? p.salida.tablas : new ArrayList<TablaSalida>() );
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
    llenarEsperada( tabla );
    List<String> av = new ArrayList<String>();
    for ( Aviso a : cr.avisos ) {
      av.add( a.detalle == null ? a.codigo : CorrectorTextos.detalle( t, a ) );
    }
    avisos.setText( av.isEmpty() ? "" : PanelInformes.esc( t.f( "ui.as_avisos", String.join( " · ", av ) ) ) );

    // reglas sugeridas, todas marcadas
    for ( Button b : marcasReglas ) {
      b.dispose();
    }
    marcasReglas.clear();
    sugeridas = new ArrayList<Regla>( p.reglas );
    for ( Regla r : sugeridas ) {
      Button b = new Button( cajaReglas, SWT.CHECK );
      b.setText( PanelInformes.esc( etiqueta( r ) ) );
      b.setSelection( true );
      PanelCorrector.id( b, "as_regla:" + r.id );
      marcasReglas.add( b );
    }
    if ( sugeridas.isEmpty() ) {
      Button b = new Button( cajaReglas, SWT.CHECK );
      b.setText( t.s( "ui.as_sin_reglas" ) );
      b.setEnabled( false );
      marcasReglas.add( b );
    }
    cajaReglas.layout( true, true );
    rubrica = new ArrayList<ItemRubrica>( p.rubrica );
    reglasDeLaRubrica = idsReglas( sugeridas );
    escala.setText( numero( p.escala ) );
    pintarRubrica();
    info( t.f( "ui.as_analizado", cr.casos.isEmpty() ? 0 : cr.casos.get( 0 ).filas ), false );
    ir( 1 );
  }

  private void llenarEsperada( CorrectorTabla tb ) {
    esperada.removeAll();
    for ( TableColumn c : esperada.getColumns() ) {
      c.dispose();
    }
    Label titulo2 = (Label) buscar( "as_esperada_titulo" );
    if ( tb == null ) {
      titulo2.setText( t.s( "ui.as_esperada_vacia" ) );
      return;
    }
    String vista = tablaVista.getEnabled() && tablaVista.getSelectionIndex() >= 0 ? tablaVista.getText() : null;
    titulo2.setText( vista == null ? t.f( "ui.as_esperada", tb.filas.size() ) : t.f( "ui.as_esperada_tabla", vista,
      tb.filas.size() ) );
    for ( String c : tb.columnas ) {
      TableColumn tc = new TableColumn( esperada, SWT.LEFT );
      tc.setText( c );
      tc.setWidth( 110 );
    }
    for ( int i = 0; i < Math.min( MAX_FILAS, tb.filas.size() ); i++ ) {
      TableItem it = new TableItem( esperada, SWT.NONE );
      String[] f = tb.filas.get( i );
      for ( int k = 0; k < tb.columnas.size(); k++ ) {
        it.setText( k, k < f.length && f[ k ] != null ? f[ k ] : "" );
      }
    }
  }

  // ---------------------------------------------------------------- rubrica

  /** Si cambiaron las reglas marcadas, la rubrica por defecto para esas reglas; si no, la que se esta editando. */
  private void armarRubrica() {
    if ( analizado == null ) {
      return;
    }
    leerPuntos(); // lo que el docente ya edito
    List<Regla> marcadas = marcadas();
    Set<String> ids = idsReglas( marcadas );
    if ( !ids.equals( reglasDeLaRubrica ) ) {
      List<ItemRubrica> nueva = CorrectorCrearPack.rubricaPorDefecto( marcadas );
      for ( ItemRubrica x : nueva ) { // los de salida conservan lo editado
        ItemRubrica antes = buscarItem( rubrica, x.check );
        if ( antes != null && x.esDeSalida() ) {
          x.puntos = antes.puntos;
        }
      }
      rubrica = nueva;
      reglasDeLaRubrica = ids;
      pintarRubrica();
    }
  }

  private void pintarRubrica() {
    for ( Control c : cajaRubrica.getChildren() ) {
      c.dispose();
    }
    puntos.clear();
    for ( ItemRubrica x : rubrica ) {
      Label l = new Label( cajaRubrica, SWT.NONE );
      l.setText( PanelInformes.esc( etiqueta( x ) ) );
      Text p = new Text( cajaRubrica, SWT.BORDER | SWT.RIGHT );
      GridData g = new GridData( SWT.LEFT, SWT.CENTER, false, false );
      g.widthHint = 60;
      p.setLayoutData( g );
      p.setText( numero( x.puntos ) );
      PanelCorrector.id( p, "as_puntos:" + x.check );
      puntos.add( p );
    }
    cajaRubrica.layout( true, true );
    Control sc = cajaRubrica.getParent();
    if ( sc instanceof ScrolledComposite ) {
      ( (ScrolledComposite) sc ).setMinSize( cajaRubrica.computeSize( SWT.DEFAULT, SWT.DEFAULT ) );
    }
  }

  /** Pasa los puntos escritos a la rubrica; false (con el error en el estado) si alguno no es un numero. */
  private boolean leerPuntos() {
    for ( int i = 0; i < puntos.size() && i < rubrica.size(); i++ ) {
      Double v;
      try {
        v = PanelCorrector.numero( puntos.get( i ).getText() );
      } catch ( NumberFormatException e ) {
        v = null;
      }
      if ( v == null || v < 0 ) {
        info( t.f( "ui.as_puntos_invalidos", etiqueta( rubrica.get( i ) ), puntos.get( i ).getText() ), true );
        return false;
      }
      rubrica.get( i ).puntos = v;
    }
    return true;
  }

  private List<Regla> marcadas() {
    List<Regla> l = new ArrayList<Regla>();
    for ( int i = 0; i < sugeridas.size() && i < marcasReglas.size(); i++ ) {
      if ( marcasReglas.get( i ).getSelection() ) {
        l.add( sugeridas.get( i ) );
      }
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

  // ---------------------------------------------------------------- guardar

  /** Arma el pack en el destino con lo elegido (otra hija), verifica el otro idioma y escribe la copia. (UI) */
  void guardar() {
    if ( analisis == null || !leerPuntos() ) {
      return;
    }
    Double esc;
    try {
      esc = PanelCorrector.numero( escala.getText() );
    } catch ( NumberFormatException e ) {
      esc = null;
    }
    if ( esc == null || esc <= 0 ) {
      info( t.f( "ui.as_puntos_invalidos", t.s( "ui.as_escala" ), escala.getText() ), true );
      return;
    }
    final CorrectorCrearPack.Opciones op = opciones( true );
    if ( op == null ) {
      return;
    }
    op.reglas = marcadas();
    List<ItemRubrica> r = new ArrayList<ItemRubrica>();
    Set<String> ids = idsReglas( op.reglas );
    for ( ItemRubrica x : rubrica ) {
      if ( !x.esRegla() || ids.contains( x.idRegla() ) ) {
        r.add( x );
      }
    }
    op.rubrica = r;
    op.escala = esc;
    op.verificarLocales = true;
    final int g = ++gen;
    trabajando = true;
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
          info( b.toString(), false );
          habilitar();
        } );
      } catch ( Throwable x ) {
        panel.enUi( () -> {
          if ( g != gen || shell.isDisposed() ) {
            return;
          }
          trabajando = false;
          habilitar();
          info( textoFallo( x ), true );
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

  /** Las opciones de crear con lo de los pasos 1 y 2; null (con el error en el estado) si falta algo. */
  private CorrectorCrearPack.Opciones opciones( boolean final_ ) {
    String tit = titulo.getText().trim();
    if ( tit.isEmpty() ) {
      info( t.s( "ui.as_falta_titulo" ), true );
      ir( 0 );
      return null;
    }
    String d = destino.getText().trim();
    if ( d.isEmpty() || !new File( d ).isAbsolute() ) {
      info( d.isEmpty() ? t.s( "ui.as_falta_destino" ) : t.f( "ui.ruta_relativa", d ), true );
      ir( 0 );
      return null;
    }
    CorrectorCrearPack.Opciones op = new CorrectorCrearPack.Opciones();
    op.solucion = copia.ktr;
    op.dirOriginal = copia.dirOriginal;
    op.nombreSolucion = copia.nombre;
    op.destino = new File( d ).getAbsoluteFile();
    op.titulo = tit;
    op.idioma = new String[] { "es", "en", "pt" }[ Math.max( 0, idioma.getSelectionIndex() ) ];
    String[] cap = capturas.get( Math.max( 0, captura.getSelectionIndex() ) );
    op.captura = cap[ 0 ];
    boolean tablas = Pack.Salida.CAPTURA_TABLAS.equals( op.captura );
    List<CorrectorCrearPack.CasoNuevo> casos = new ArrayList<CorrectorCrearPack.CasoNuevo>();
    CorrectorCrearPack.CasoNuevo visible = new CorrectorCrearPack.CasoNuevo( CorrectorCrearPack.VISIBLE, null, false );
    casos.add( visible );
    String o = oculto.getText().trim();
    CorrectorCrearPack.CasoNuevo caso2 = null;
    if ( !o.isEmpty() ) {
      File fo = new File( o );
      if ( !fo.isAbsolute() || !fo.isDirectory() ) {
        info( t.f( "ui.no_existe", o ), true );
        ir( 0 );
        return null;
      }
      caso2 = new CorrectorCrearPack.CasoNuevo( "oculto", fo.getAbsoluteFile(), true );
    }
    if ( !filasBase.isEmpty() && !leerBases( op ) ) {
      return null;
    }
    for ( FilaBase f : filasBase ) {
      List<File> l = archivos( f.scriptsOculto.getText() );
      if ( l == null ) {
        return null;
      }
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
      op.stepSalida = i <= 0 ? null : steps.get( i - 1 );
      List<String> k = new ArrayList<String>();
      for ( String s : claves.getText().split( "," ) ) {
        if ( !s.trim().isEmpty() ) {
          k.add( s.trim() );
        }
      }
      op.columnasClave = k; // [] = comparar sin clave
    }
    if ( final_ && copiaAlumnos.getSelection() ) {
      op.copiaAlumnos = copiaDe( op.destino );
    }
    op.procesos = panel.servicio.procesos();
    return op;
  }

  // ---------------------------------------------------------------- base de prueba

  /**
   * Las opciones de captura: las tablas que escribe la solucion (todas, o una), el archivo de cada Text file output y
   * las filas del step de salida. Con tablas y base de prueba, la primera es «las tablas».
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

  /** «Base de prueba»: una fila de campos por conexion de servidor, con lo de la solucion y la base _correccion. */
  private Button grupoBase( Composite p1, List<BaseDePrueba> propuestas ) {
    Group g = new Group( p1, SWT.NONE );
    g.setText( t.s( "ui.as_base" ) );
    g.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false, 3, 1 ) );
    g.setLayout( new GridLayout( 6, false ) );
    PanelCorrector.id( g, "as_base" );
    Label nota = new Label( g, SWT.WRAP );
    nota.setText( t.s( "ui.as_base_nota" ) );
    GridData gn = new GridData( SWT.FILL, SWT.TOP, true, false, 6, 1 );
    gn.widthHint = 760;
    nota.setLayoutData( gn );
    for ( BaseDePrueba b : propuestas ) {
      FilaBase f = new FilaBase( b );
      String cx = b.conexion;
      Label l = new Label( g, SWT.NONE );
      l.setText( t.f( "ui.as_base_conexion", cx, b.tipoNormal(), b.baseOriginal == null ? "" : b.baseOriginal ) );
      l.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false, 6, 1 ) );
      f.servidor = campoBase( g, "as_base_servidor:" + cx, "ui.as_base_servidor", b.servidor, 1, 0 );
      f.puerto = campoBase( g, "as_base_puerto:" + cx, "ui.as_base_puerto", b.puerto, 1, 0 );
      f.base = campoBase( g, "as_base_nombre:" + cx, "ui.as_base_nombre", b.base, 1, 0 );
      f.base.setToolTipText( t.s( "ui.as_base_nombre_tip" ) );
      f.usuario = campoBase( g, "as_base_usuario:" + cx, "ui.as_base_usuario", b.usuario, 1, 0 );
      f.clave = campoBase( g, "as_base_clave:" + cx, "ui.as_base_clave", "", 1, SWT.PASSWORD );
      f.clave.setToolTipText( t.s( "ui.as_base_clave_tip" ) );
      if ( CorrectorBases.recordada( b ) ) {
        f.clave.setMessage( t.s( "ui.as_base_clave_recordada" ) );
      }
      new Label( g, SWT.NONE );
      new Label( g, SWT.NONE );
      f.scripts = campoBase( g, "as_base_scripts:" + cx, "ui.as_base_scripts", "", 4, 0 );
      f.scripts.setToolTipText( t.s( "ui.as_base_scripts_tip" ) );
      final Text sc = f.scripts;
      boton( g, "as_base_elegir:" + cx, "ui.elegir", () -> elegirScripts( sc ) );
      f.scriptsOculto = campoBase( g, "as_base_oculto:" + cx, "ui.as_base_oculto", "", 4, 0 );
      f.scriptsOculto.setToolTipText( t.s( "ui.as_base_oculto_tip" ) );
      final Text so = f.scriptsOculto;
      boton( g, "as_base_elegir_oculto:" + cx, "ui.elegir", () -> elegirScripts( so ) );
      filasBase.add( f );
    }
    Button dos = new Button( g, SWT.CHECK );
    dos.setText( t.s( "ui.as_dos_veces" ) );
    dos.setToolTipText( t.s( "ui.as_dos_veces_tip" ) );
    dos.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false, 6, 1 ) );
    PanelCorrector.id( dos, "as_dos_veces" );
    return dos;
  }

  private Text campoBase( Composite g, String id, String rotulo, String valor, int columnas, int estilo ) {
    rotulo( g, rotulo );
    Text x = new Text( g, SWT.BORDER | SWT.SINGLE | estilo );
    GridData gd = new GridData( SWT.FILL, SWT.CENTER, true, false, columnas, 1 );
    gd.widthHint = columnas > 1 ? 480 : 140;
    x.setLayoutData( gd );
    x.setText( valor == null ? "" : valor );
    PanelCorrector.id( x, id );
    return x;
  }

  private void elegirScripts( Text campo ) {
    FileDialog fd = new FileDialog( shell, SWT.OPEN | SWT.MULTI );
    fd.setFilterExtensions( new String[] { "*.sql", "*.*" } );
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

  /** Los archivos de un campo de scripts («a.sql; b.sql»), o null (con el error en el estado) si alguno no existe. */
  private List<File> archivos( String texto ) {
    List<File> l = new ArrayList<File>();
    for ( String s : texto.split( ";" ) ) {
      String x = s.trim();
      if ( x.isEmpty() ) {
        continue;
      }
      File f = new File( x );
      if ( !f.isAbsolute() || !f.isFile() ) {
        info( t.f( "ui.as_script_no_existe", x ), true );
        ir( 0 );
        return null;
      }
      l.add( f.getAbsoluteFile() );
    }
    return l;
  }

  /** Las bases de prueba, sus scripts y sus contraseñas a las opciones; false si falta algo. */
  private boolean leerBases( CorrectorCrearPack.Opciones op ) {
    op.bases = new ArrayList<BaseDePrueba>();
    op.scriptsBase = new LinkedHashMap<String, List<File>>();
    op.claves = new LinkedHashMap<String, String>();
    for ( FilaBase f : filasBase ) {
      BaseDePrueba b = new BaseDePrueba( f.propuesta.conexion, f.propuesta.tipo, f.servidor.getText().trim(),
        f.puerto.getText().trim(), f.base.getText().trim(), f.usuario.getText().trim() );
      b.baseOriginal = f.propuesta.baseOriginal;
      if ( b.base.isEmpty() ) {
        info( t.f( "ui.as_base_falta", b.conexion ), true );
        ir( 0 );
        return false;
      }
      List<File> sc = archivos( f.scripts.getText() );
      if ( sc == null ) {
        return false;
      }
      op.bases.add( b );
      op.scriptsBase.put( b.conexion, sc );
      String clave = f.clave.getText();
      if ( !clave.isEmpty() || !CorrectorBases.recordada( b ) ) {
        op.claves.put( b.conexion.toLowerCase( java.util.Locale.ROOT ), clave );
      }
    }
    return true;
  }

  /** Lo que el docente escribio queda en memoria para corregir en esta sesion de Spoon (nunca en un archivo). */
  private void recordarClaves() {
    for ( FilaBase f : filasBase ) {
      String clave = f.clave.getText();
      BaseDePrueba b = new BaseDePrueba( f.propuesta.conexion, f.propuesta.tipo, f.servidor.getText().trim(),
        f.puerto.getText().trim(), f.base.getText().trim(), f.usuario.getText().trim() );
      if ( !clave.isEmpty() || !CorrectorBases.recordada( b ) ) {
        CorrectorBases.recordar( b, clave );
      }
    }
  }

  /** Captura tables: una fila por tabla con su clave y sus columnas ignoradas, editables. (UI) */
  private void pintarTablas( List<TablaSalida> tablas ) {
    for ( Control c : cajaTablas.getChildren() ) {
      c.dispose();
    }
    textosTablas.clear();
    tablasAnalizadas = new ArrayList<TablaSalida>( tablas );
    tablaVista.removeAll();
    if ( !tablas.isEmpty() ) {
      for ( String k : new String[] { "ui.as_tabla", "ui.as_tabla_claves", "ui.as_tabla_ignorar" } ) {
        Label l = new Label( cajaTablas, SWT.NONE );
        l.setText( t.s( k ) );
      }
    }
    for ( TablaSalida ts : tablas ) {
      Label l = new Label( cajaTablas, SWT.NONE );
      l.setText( ts.nombre );
      Text k = new Text( cajaTablas, SWT.BORDER | SWT.SINGLE );
      k.setText( String.join( ", ", ts.columnasClave ) );
      k.setToolTipText( t.s( "ui.as_claves_tip" ) );
      k.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
      PanelCorrector.id( k, "as_tabla_claves:" + ts.nombre );
      Text ig = new Text( cajaTablas, SWT.BORDER | SWT.SINGLE );
      ig.setText( String.join( ", ", ts.ignorar ) );
      ig.setToolTipText( t.s( "ui.as_tabla_ignorar_tip" ) );
      ig.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
      PanelCorrector.id( ig, "as_tabla_ignorar:" + ts.nombre );
      textosTablas.add( new Text[] { k, ig } );
      tablaVista.add( ts.nombre );
    }
    tablaVista.setEnabled( !tablas.isEmpty() );
    if ( !tablas.isEmpty() ) {
      tablaVista.select( 0 );
    }
    cajaTablas.getParent().layout( true, true );
  }

  private void verTabla() {
    int i = tablaVista.getSelectionIndex();
    if ( i >= 0 && i < tablasAnalizadas.size() ) {
      llenarEsperada( esperadasTablas.get( tablasAnalizadas.get( i ).nombre ) );
    }
  }

  /** Las tablas con la clave y las ignoradas que escribio el docente en el paso 2. */
  private List<TablaSalida> tablasEditadas() {
    List<TablaSalida> l = new ArrayList<TablaSalida>();
    for ( int i = 0; i < tablasAnalizadas.size(); i++ ) {
      TablaSalida x = new TablaSalida( tablasAnalizadas.get( i ).conexion, tablasAnalizadas.get( i ).nombre );
      if ( i < textosTablas.size() ) {
        x.columnasClave = lista( textosTablas.get( i )[ 0 ].getText() );
        x.ignorar = lista( textosTablas.get( i )[ 1 ].getText() );
      }
      l.add( x );
    }
    return l;
  }

  private static List<String> lista( String texto ) {
    List<String> l = new ArrayList<String>();
    for ( String s : texto.split( "," ) ) {
      if ( !s.trim().isEmpty() ) {
        l.add( s.trim() );
      }
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

  // ---------------------------------------------------------------- ayudas

  /** Los nombres de los steps de la solucion (para elegir el de salida), leidos del XML de la copia. */
  private void leerSteps() {
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
    } catch ( Throwable x ) {
      panel.servicio.log( "Pentaho Live: could not list the steps of the solution", x );
    }
  }

  private String etiqueta( Regla r ) {
    String e = r.etiqueta != null && !r.etiqueta.trim().isEmpty() ? r.etiqueta.trim() : r.id;
    return e + " (" + r.tipo + ( r.steps.isEmpty() ? "" : ": " + String.join( ", ", r.steps ) ) + ( r.valor == null
      ? "" : ": " + r.valor ) + ")";
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
        if ( r.id.equals( x.idRegla() ) && r.etiqueta != null ) {
          return r.etiqueta;
        }
      }
    }
    return x.check;
  }

  private Text campo( Composite p, String id, String rotulo, String tip ) {
    rotulo( p, rotulo );
    Text x = new Text( p, SWT.BORDER | SWT.SINGLE );
    GridData g = new GridData( SWT.FILL, SWT.CENTER, true, false );
    g.widthHint = 380;
    x.setLayoutData( g );
    if ( tip != null ) {
      x.setToolTipText( t.s( tip ) );
    }
    PanelCorrector.id( x, id );
    return x;
  }

  private void rotulo( Composite p, String clave ) {
    Label l = new Label( p, SWT.NONE );
    l.setText( t.s( clave ) );
  }

  private Label etiqueta( Composite p, String id ) {
    Label l = new Label( p, SWT.WRAP );
    GridData g = new GridData( SWT.FILL, SWT.TOP, true, false, 3, 1 );
    g.widthHint = 640;
    l.setLayoutData( g );
    PanelCorrector.id( l, id );
    return l;
  }

  private Button boton( Composite p, String id, String rotulo, Runnable accion ) {
    return panel.boton( p, id, rotulo, null, () -> {
      try {
        accion.run();
      } catch ( Throwable x ) {
        info( textoFallo( x ), true );
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

  private void info( String texto, boolean error ) {
    if ( estado.isDisposed() ) {
      return;
    }
    estado.setText( PanelInformes.esc( texto ) );
    estado.setForeground( error ? shell.getDisplay().getSystemColor( SWT.COLOR_RED ) : null );
    shell.layout( true, true );
  }

  /** Texto del estado del asistente (pruebas). */
  String estado() {
    return estado.getText();
  }

  private String textoFallo( Throwable x ) {
    Throwable c = x;
    while ( c.getCause() != null && c.getCause() != c && !( c instanceof CorrectorCrearPack.Fallo ) ) {
      c = c.getCause();
    }
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

  private void alCerrar() {
    cerrado = true;
    gen++;
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

  /** check → puntos de la rubrica que se esta editando (pruebas). */
  List<String> rubrica() {
    List<String> l = new ArrayList<String>();
    for ( int i = 0; i < rubrica.size(); i++ ) {
      l.add( rubrica.get( i ).check + "=" + ( i < puntos.size() ? puntos.get( i ).getText() : "?" ) );
    }
    return l;
  }

}
