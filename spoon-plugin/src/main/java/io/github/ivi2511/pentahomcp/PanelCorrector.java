package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.nio.file.Files;
import java.text.Collator;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Widget;

import io.github.ivi2511.pentahomcp.corrector.modelo.ItemRubrica;
import io.github.ivi2511.pentahomcp.corrector.modelo.PoliticaPack;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.Criterio;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.PasoBloqueado;
import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Resultado;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;
import io.github.ivi2511.pentahomcp.informes.ui.PanelInformes;

/**
 * Panel SWT de la perspectiva Corrector (docs/corrector-diseno.md §9, plan B): pack, carpeta de entregas y carpeta
 * de resultados arriba; la tabla de entregas a la izquierda (se llena a medida que terminan los workers, se ordena por
 * columna) y el detalle a la derecha (la correccion en HTML liviano de {@link CorreccionAInforme} en el
 * {@code Browser}, el ajuste del docente, «Abrir su .ktr», «Volver a corregir con...»); abajo el progreso, la planilla,
 * el informe del curso y las devoluciones. «Crear pack desde esta transformacion» abre {@link AsistentePack}.
 * <p>
 * <b>Nada corre en Spoon</b>: el lote es un {@link CorrectorLote} (JVMs hijas) y el panel solo lo consulta cada
 * {@value #REFRESCO_MS} ms con {@code timerExec}. Lo pesado (abrir el pack, armar el HTML, exportar, volver a corregir)
 * va al pool del panel y vuelve con {@code asyncExec}, chequeando {@code isDisposed}; nunca hay {@code Future.get()}
 * en la UI. Los dialogos (FileDialog, DirectoryDialog, «Volver a corregir con...», el asistente) solo se abren por un
 * clic. El modo confianza existe solo aca (§7), por corrida, y queda en el resultado. Todo texto de las entregas es
 * dato del alumno: en los Label va escapado.
 * <p>
 * Lo que necesita de Spoon pasa por {@link Servicio}: en Spoon lo da {@link GraderPerspective}; en las pruebas, uno
 * con el PDI de prueba.
 *
 * @dueño C8
 */
final class PanelCorrector {

  /** Area de textos: corrector_&lt;idioma&gt;.properties, claves ui.*. */
  static final String AREA = CorrectorNota.AREA;
  /** Clave de {@code Widget.setData} con el id estable de cada control (la misma que Informes). */
  static final String CLAVE = PanelInformes.CLAVE;
  static final int REFRESCO_MS = 500;
  static final int DEBOUNCE_MS = 400;
  /** Workers por defecto y tope (como grade_submissions). */
  static final int WORKERS = 2;
  static final int MAX_WORKERS = CorrectorTools.MAX_WORKERS;

  /** Steps condicionales que «Volver a corregir con...» puede habilitar (§7). ScriptValueMod va en rojo. */
  static final List<String> CONDICIONALES = Collections.unmodifiableList( Arrays.asList( "TableInput", "TableOutput",
    "InsertUpdate", "Update", "Delete", "DBLookup", "DBJoin", "ExecSQL", "ExecSQLRow", "DynamicSQLRow",
    "DimensionLookup", "CombinationLookup", "SynchronizeAfterMerge", "TableExists", "ColumnExists", "GetTableNames",
    "SetVariable", "Mapping", "SimpleMapping", "TransExecutor", "ScriptValueMod" ) );
  static final String SCRIPT = "ScriptValueMod";

  /** Los checks de salida que tienen columna propia en la tabla. */
  static final List<String> CHECKS = Collections.unmodifiableList( Arrays.asList( ItemRubrica.RUNS,
    ItemRubrica.COLUMNS, ItemRubrica.ROW_COUNT, ItemRubrica.VALUES ) );
  static final String TODO = "●";
  static final String PARTE = "◐";
  static final String NADA = "○";
  static final String SIN = "–";

  /** Columnas de la tabla, en orden (claves ui.col.*). */
  static final List<String> COLUMNAS = Collections.unmodifiableList( Arrays.asList( "alumno", "nota", "estado",
    ItemRubrica.RUNS, ItemRubrica.COLUMNS, ItemRubrica.ROW_COUNT, ItemRubrica.VALUES, "reglas", "ajuste",
    "comentario" ) );

  // ================================================================ contrato con Spoon

  /** Lo que el panel necesita de Spoon. Los metodos marcados (UI) corren en el hilo de UI. */
  interface Servicio {

    /** Como lanzar las hijas (pruebas: el PDI de prueba); null = el PDI y el jar de donde cargo el plugin. */
    default CorrectorProcesos.Opciones procesos() {
      return null;
    }

    /** La transformacion activa de Data Integration copiada a un .ktr dentro de {@code tmp}. (UI) */
    CorrectorTools.Copia solucionActiva( File tmp ) throws Exception;

    /** Abre el .ktr en una pestana nueva de Data Integration. (UI) */
    void abrirEnSpoon( File ktr ) throws Exception;

    /** Abre un archivo o carpeta con el programa del sistema. (UI) */
    default void abrir( File f ) {
      Program.launch( f.getAbsolutePath() );
    }

    /** El tema de los informes (logo, identidad). (worker) */
    default Tema tema() {
      return CorreccionAInforme.tema();
    }

    /** Temporales del panel (el HTML del detalle). */
    default File temporales() {
      return new File( System.getProperty( "java.io.tmpdir" ), "plive-corrector" );
    }

    /** Corre una tarea fuera de la UI. */
    default <T> Future<T> enSegundoPlano( Callable<T> tarea ) {
      return enPool( tarea );
    }

    default void log( String texto, Throwable t ) {
      GraderPerspective.error( texto, t );
    }
  }

  /** Una entrega en la tabla. */
  static final class Fila {
    String alumno;
    String archivo;
    /** Un estado del §6, pending o running. */
    String estado;
    Double nota;
    Double ajuste;
    String comentario;
    /** El resultado final, o null mientras no termina. */
    ResultadoCorreccion r;
  }

  // ================================================================ pool

  private static ExecutorService pool;

  private static synchronized ExecutorService pool() {
    if ( pool == null ) {
      final AtomicInteger n = new AtomicInteger();
      pool = Executors.newFixedThreadPool( 3, r -> {
        Thread t = new Thread( r, "pentaho-live-corrector-panel-" + n.incrementAndGet() );
        t.setDaemon( true );
        return t;
      } );
    }
    return pool;
  }

  /** En el pool del panel, con el context classloader del plugin (POI y el PDF lo necesitan). */
  static <T> Future<T> enPool( final Callable<T> tarea ) {
    final ClassLoader cl = PanelCorrector.class.getClassLoader();
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

  /** Las rutas de la ultima vez (mientras dure Spoon): pack, entregas y resultados. */
  private static final String[] ULTIMAS = new String[ 3 ];

  // ================================================================ instancia

  final Servicio servicio;
  final Display display;
  final Rotulos t;
  final String idioma;
  private final Composite raiz;

  final Text pack;
  final Text carpeta;
  final Text salida;
  final Label infoPack;
  final Label infoCarpeta;
  final Spinner workers;
  final Button corregir;
  final Button cancelar;
  final Button crearPack;
  final Table tabla;
  final Label tituloDetalle;
  /** null si no hay motor de navegador: entonces {@link #textoDetalle}. */
  final Browser navegador;
  final Text textoDetalle;
  final Button vistaAlumno;
  final Text ajuste;
  final Text comentario;
  final Button guardarAjuste;
  final Button abrirKtr;
  final Button recorregir;
  final Button abrirDevolucion;
  final ProgressBar progreso;
  final Label estado;
  final Button notas;
  final Menu menuNotas;
  final Button exportarDevoluciones;
  final Button abrirCarpeta;

  private CorrectorLote lote;
  private boolean finVisto;
  /** Arrancando un lote, cancelandolo o volviendo a corregir: los botones de accion se apagan. */
  private boolean ocupado;
  private List<Fila> filas = new ArrayList<Fila>();
  private int columnaOrden;
  private boolean ascendente = true;
  private String seleccionado;
  private ResultadoCorreccion detalleDe;
  private boolean detalleAlumno;
  private int genDetalle;
  private int genPack;
  private int debouncePack;
  private int debounceCarpeta;
  private File ultimoDetalle;
  private String urlDetalle;
  private Tema tema;
  private AsistentePack asistente;
  private Recorreccion dialogo;

  /** Arma el panel dentro de {@code padre}. (UI) */
  PanelCorrector( Composite padre, Servicio servicio, String idiomaUi ) {
    if ( servicio == null ) {
      throw new IllegalArgumentException( "servicio" );
    }
    this.servicio = servicio;
    this.display = padre.getDisplay();
    this.idioma = Rotulos.normalizar( idiomaUi );
    this.t = Rotulos.de( AREA, idioma );
    if ( padre.getLayout() == null ) {
      padre.setLayout( new FillLayout() );
    }

    raiz = new Composite( padre, SWT.NONE );
    GridLayout gl = new GridLayout( 1, false );
    gl.marginWidth = 6;
    gl.marginHeight = 6;
    raiz.setLayout( gl );

    // ---- pack, entregas y resultados
    Composite arriba = new Composite( raiz, SWT.NONE );
    arriba.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    GridLayout ga = new GridLayout( 4, false );
    ga.marginWidth = 0;
    ga.marginHeight = 0;
    arriba.setLayout( ga );
    pack = campo( arriba, "pack", "ui.pack", "ui.pack_tip" );
    boton( arriba, "elegir_pack", "ui.elegir", "ui.elegir_pack", this::elegirPack );
    infoPack = info( arriba, "pack_info" );
    carpeta = campo( arriba, "carpeta", "ui.entregas", "ui.entregas_tip" );
    boton( arriba, "elegir_carpeta", "ui.elegir", "ui.elegir_carpeta", () -> elegirCarpeta( carpeta,
      "ui.elegir_carpeta" ) );
    infoCarpeta = info( arriba, "carpeta_info" );
    salida = campo( arriba, "salida", "ui.salida", "ui.salida_tip" );
    boton( arriba, "elegir_salida", "ui.elegir", "ui.elegir_salida", () -> elegirCarpeta( salida,
      "ui.elegir_salida" ) );
    info( arriba, "salida_info" );

    Composite acciones = new Composite( raiz, SWT.NONE );
    acciones.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    GridLayout gc = new GridLayout( 6, false );
    gc.marginWidth = 0;
    gc.marginHeight = 2;
    acciones.setLayout( gc );
    corregir = boton( acciones, "corregir", "ui.corregir", "ui.corregir_tip", this::corregir );
    cancelar = boton( acciones, "cancelar", "ui.cancelar", "ui.cancelar_tip", this::cancelar );
    Label lw = new Label( acciones, SWT.NONE );
    lw.setText( t.s( "ui.workers" ) );
    lw.setToolTipText( t.s( "ui.workers_tip" ) );
    workers = new Spinner( acciones, SWT.BORDER );
    workers.setMinimum( 1 );
    workers.setMaximum( MAX_WORKERS );
    workers.setSelection( WORKERS );
    workers.setToolTipText( t.s( "ui.workers_tip" ) );
    id( workers, "workers" );
    Label hueco = new Label( acciones, SWT.NONE );
    hueco.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
    crearPack = boton( acciones, "crear_pack", "ui.crear_pack", "ui.crear_pack_tip", this::crearPack );
    Label aviso = new Label( raiz, SWT.WRAP );
    aviso.setText( t.s( "ui.aviso_sandbox" ) );
    aviso.setForeground( display.getSystemColor( SWT.COLOR_DARK_GRAY ) );
    aviso.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    id( aviso, "aviso_sandbox" );

    // ---- tabla y detalle
    SashForm sash = new SashForm( raiz, SWT.HORIZONTAL );
    sash.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, true ) );
    tabla = new Table( sash, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL );
    tabla.setHeaderVisible( true );
    tabla.setLinesVisible( true );
    id( tabla, "tabla" );
    int[] anchos = { 170, 50, 92, 50, 46, 50, 62, 58, 62, 160 };
    for ( int i = 0; i < COLUMNAS.size(); i++ ) {
      final int col = i;
      TableColumn c = new TableColumn( tabla, i == 0 || i == 2 || i == 9 ? SWT.LEFT : SWT.CENTER );
      c.setText( t.s( "ui.col." + COLUMNAS.get( i ) ) );
      c.setWidth( anchos[ i ] );
      if ( i >= 3 && i <= 6 ) {
        c.setToolTipText( t.s( "ui.marca_tip" ) );
      }
      c.addListener( SWT.Selection, e -> ordenarPor( col ) );
    }
    tabla.addListener( SWT.Selection, e -> seleccion() );
    tabla.addListener( SWT.DefaultSelection, e -> seleccion() );

    Composite derecha = new Composite( sash, SWT.NONE );
    GridLayout gd = new GridLayout( 1, false );
    gd.marginWidth = 4;
    gd.marginHeight = 0;
    derecha.setLayout( gd );
    tituloDetalle = new Label( derecha, SWT.NONE );
    tituloDetalle.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    tituloDetalle.setText( t.s( "ui.detalle_vacio" ) );
    id( tituloDetalle, "detalle_titulo" );
    Composite marco = new Composite( derecha, SWT.BORDER );
    marco.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, true ) );
    marco.setLayout( new FillLayout() );
    Browser b = null;
    try {
      b = new Browser( marco, 1 << 18 ); // SWT.EDGE si el swt.jar lo trae; con el de PDI, IE11 (VistaPrevia)
      id( b, "detalle" );
    } catch ( Throwable x ) {
      servicio.log( "Pentaho Live: no embedded browser for the grader detail", x );
      if ( b != null && !b.isDisposed() ) {
        b.dispose();
      }
      b = null;
    }
    navegador = b;
    if ( navegador == null ) {
      textoDetalle = new Text( marco, SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL | SWT.WRAP );
      id( textoDetalle, "detalle_texto" );
    } else {
      textoDetalle = null;
    }

    Composite fajuste = new Composite( derecha, SWT.NONE );
    fajuste.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    GridLayout gj = new GridLayout( 5, false );
    gj.marginWidth = 0;
    fajuste.setLayout( gj );
    Label la = new Label( fajuste, SWT.NONE );
    la.setText( t.s( "ui.ajuste" ) );
    ajuste = new Text( fajuste, SWT.BORDER );
    GridData gaj = new GridData( SWT.LEFT, SWT.CENTER, false, false );
    gaj.widthHint = 50;
    ajuste.setLayoutData( gaj );
    ajuste.setToolTipText( t.s( "ui.ajuste_tip" ) );
    id( ajuste, "ajuste" );
    Label lc = new Label( fajuste, SWT.NONE );
    lc.setText( t.s( "ui.comentario" ) );
    comentario = new Text( fajuste, SWT.BORDER );
    comentario.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
    id( comentario, "comentario" );
    guardarAjuste = boton( fajuste, "guardar_ajuste", "ui.guardar_ajuste", "ui.guardar_ajuste_tip",
      this::guardarAjuste );

    Composite fbotones = new Composite( derecha, SWT.NONE );
    fbotones.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    RowLayout rl = new RowLayout( SWT.HORIZONTAL );
    rl.marginLeft = 0;
    rl.center = true;
    fbotones.setLayout( rl );
    abrirKtr = boton( fbotones, "abrir_ktr", "ui.abrir_ktr", "ui.abrir_ktr_tip", this::abrirKtr );
    recorregir = boton( fbotones, "recorregir", "ui.recorregir", "ui.recorregir_tip", this::recorregir );
    abrirDevolucion = boton( fbotones, "abrir_devolucion", "ui.abrir_devolucion", "ui.abrir_devolucion_tip",
      this::abrirDevolucion );
    vistaAlumno = new Button( fbotones, SWT.CHECK );
    vistaAlumno.setText( t.s( "ui.vista_alumno" ) );
    vistaAlumno.setToolTipText( t.s( "ui.vista_alumno_tip" ) );
    id( vistaAlumno, "vista_alumno" );
    vistaAlumno.addListener( SWT.Selection, e -> {
      detalleDe = null;
      mostrarDetalle();
    } );
    sash.setWeights( new int[] { 48, 52 } );

    // ---- pie
    Label sep = new Label( raiz, SWT.SEPARATOR | SWT.HORIZONTAL );
    sep.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
    Composite pie = new Composite( raiz, SWT.NONE );
    pie.setLayoutData( new GridData( SWT.FILL, SWT.BOTTOM, true, false ) );
    GridLayout gp = new GridLayout( 5, false );
    gp.marginWidth = 0;
    gp.marginHeight = 0;
    pie.setLayout( gp );
    progreso = new ProgressBar( pie, SWT.HORIZONTAL | SWT.SMOOTH );
    GridData gpr = new GridData( SWT.LEFT, SWT.CENTER, false, false );
    gpr.widthHint = 140;
    progreso.setLayoutData( gpr );
    id( progreso, "progreso" );
    estado = new Label( pie, SWT.NONE );
    estado.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
    id( estado, "estado" );
    notas = boton( pie, "notas", "ui.notas", "ui.notas_tip", this::menuNotas );
    menuNotas = new Menu( notas );
    item( menuNotas, "abrir_xlsx", "ui.abrir_xlsx", () -> abrirPlanilla( CorrectorPlanilla.XLSX ) );
    item( menuNotas, "abrir_csv", "ui.abrir_csv", () -> abrirPlanilla( CorrectorPlanilla.CSV ) );
    new MenuItem( menuNotas, SWT.SEPARATOR );
    item( menuNotas, "curso_html", "ui.curso_html", () -> informeCurso( Formato.HTML ) );
    item( menuNotas, "curso_pdf", "ui.curso_pdf", () -> informeCurso( Formato.PDF ) );
    exportarDevoluciones = boton( pie, "exportar_devoluciones", "ui.exportar_devoluciones",
      "ui.exportar_devoluciones_tip", this::exportarDevoluciones );
    abrirCarpeta = boton( pie, "abrir_carpeta", "ui.abrir_carpeta", "ui.abrir_carpeta_tip", this::abrirCarpeta );

    // ---- estado inicial
    pack.addListener( SWT.Modify, e -> programarPack() );
    carpeta.addListener( SWT.Modify, e -> programarCarpeta() );
    if ( ULTIMAS[ 0 ] != null ) {
      pack.setText( ULTIMAS[ 0 ] );
    }
    if ( ULTIMAS[ 1 ] != null ) {
      carpeta.setText( ULTIMAS[ 1 ] );
    }
    if ( ULTIMAS[ 2 ] != null ) {
      salida.setText( ULTIMAS[ 2 ] );
    }
    listo( t.s( "ui.listo" ) );
    habilitar();
    raiz.addDisposeListener( e -> alCerrar() );
    CorrectorLote previo = ultimoLote();
    if ( previo != null ) {
      seguir( previo ); // la perspectiva se recreo con un lote de este Spoon en curso o terminado
    }
  }

  // ---------------------------------------------------------------- ciclo

  /** La perspectiva se activo: refresca la tabla si hay un lote. (UI) */
  void alActivar() {
    try {
      if ( lote != null ) {
        refrescar();
      }
      habilitar();
    } catch ( Throwable x ) {
      fallo( x );
    }
  }

  Composite raiz() {
    return raiz;
  }

  /** Texto de la barra de estado. (UI) */
  String estado() {
    return estado.getText();
  }

  /** Arrancando, cancelando o volviendo a corregir. (UI) */
  boolean ocupado() {
    return ocupado;
  }

  CorrectorLote lote() {
    return lote;
  }

  AsistentePack asistente() {
    return asistente;
  }

  Recorreccion dialogo() {
    return dialogo;
  }

  /** La URL que muestra el detalle (pruebas). (UI) */
  String urlDetalle() {
    return urlDetalle;
  }

  /** Las filas de la tabla en el orden en que se ven: los textos de cada celda. (UI) */
  List<String[]> filasVisibles() {
    List<String[]> l = new ArrayList<String[]>();
    for ( TableItem it : tabla.getItems() ) {
      String[] x = new String[ COLUMNAS.size() ];
      for ( int i = 0; i < x.length; i++ ) {
        x[ i ] = it.getText( i );
      }
      l.add( x );
    }
    return l;
  }

  /** Selecciona la fila de ese alumno como un clic. (UI) */
  void seleccionar( String alumno ) {
    TableItem[] items = tabla.getItems();
    for ( int i = 0; i < items.length; i++ ) {
      if ( alumno.equals( items[ i ].getData() ) ) {
        tabla.setSelection( i );
        seleccion();
        return;
      }
    }
  }

  // ---------------------------------------------------------------- pack y carpeta

  private void elegirPack() {
    try {
      FileDialog fd = new FileDialog( raiz.getShell(), SWT.OPEN );
      fd.setText( t.s( "ui.elegir_pack" ) );
      fd.setFilterExtensions( new String[] { "pack.json;*.zip;*.json", "*.*" } );
      fd.setFilterNames( new String[] { t.s( "ui.filtro_pack" ), t.s( "ui.filtro_todo" ) } );
      String r = fd.open();
      if ( r != null ) {
        File f = new File( r );
        pack.setText( CorrectorPack.PACK_JSON.equalsIgnoreCase( f.getName() ) ? f.getParent() : f.getPath() );
      }
    } catch ( Throwable x ) {
      fallo( x );
    }
  }

  private void elegirCarpeta( Text destino, String titulo ) {
    try {
      DirectoryDialog dd = new DirectoryDialog( raiz.getShell() );
      dd.setText( t.s( titulo ) );
      dd.setMessage( t.s( titulo ) );
      if ( !destino.getText().trim().isEmpty() ) {
        dd.setFilterPath( destino.getText().trim() );
      }
      String r = dd.open();
      if ( r != null ) {
        destino.setText( r );
      }
    } catch ( Throwable x ) {
      fallo( x );
    }
  }

  private void programarPack() {
    final int token = ++debouncePack;
    display.timerExec( DEBOUNCE_MS, () -> {
      if ( token == debouncePack && !raiz.isDisposed() ) {
        leerPack();
      }
    } );
  }

  /** Lee el pack en el pool y muestra su resumen (o sus errores) al lado. */
  void leerPack() {
    final String texto = pack.getText().trim();
    final int gen = ++genPack;
    if ( texto.isEmpty() ) {
      info( infoPack, "", false );
      return;
    }
    final File f = new File( texto );
    if ( !f.isAbsolute() ) {
      info( infoPack, t.f( "ui.ruta_relativa", texto ), true );
      return;
    }
    if ( !f.exists() ) {
      info( infoPack, t.f( "ui.no_existe", f.getName() ), true );
      return;
    }
    info( infoPack, t.s( "ui.pack_leyendo" ), false );
    tarea( () -> {
      String txt;
      boolean error;
      try ( CorrectorPack cp = CorrectorPack.abrir( f ) ) {
        error = !cp.valido();
        if ( error ) {
          List<String> l = new ArrayList<String>();
          for ( CorrectorPack.Problema p : cp.errores() ) {
            l.add( p.texto( idioma ) );
          }
          txt = t.f( "ui.pack_invalido", l.isEmpty() ? "?" : l.get( 0 ) + ( l.size() > 1 ? " (+" + ( l.size() - 1 )
            + ")" : "" ) );
        } else {
          txt = cp.resumen( idioma ) + ( cp.solucion() == null ? " " + t.s( "ui.pack_sin_solucion" ) : "" );
        }
      }
      final String fin = txt;
      final boolean err = error;
      enUi( () -> {
        if ( gen == genPack ) {
          info( infoPack, fin, err );
        }
      } );
      return null;
    } );
  }

  private void programarCarpeta() {
    final int token = ++debounceCarpeta;
    display.timerExec( DEBOUNCE_MS, () -> {
      if ( token == debounceCarpeta && !raiz.isDisposed() ) {
        contarEntregas();
      }
    } );
  }

  /** Cuenta lo que parece una entrega (.ktr, .kjb, .zip o carpeta) y propone la carpeta de resultados. (UI) */
  void contarEntregas() {
    String texto = carpeta.getText().trim();
    if ( texto.isEmpty() ) {
      info( infoCarpeta, "", false );
      return;
    }
    File d = new File( texto );
    if ( !d.isAbsolute() ) {
      info( infoCarpeta, t.f( "ui.ruta_relativa", texto ), true );
      return;
    }
    if ( !d.isDirectory() ) {
      info( infoCarpeta, t.f( "ui.no_existe", d.getName() ), true );
      return;
    }
    int n = entregasEn( d );
    info( infoCarpeta, n == 0 ? t.s( "ui.entregas_0" ) : n == 1 ? t.s( "ui.entregas_1" ) : t.f( "ui.entregas_n", n ),
      n == 0 );
    salida.setMessage( salidaPorDefecto( d ).getPath() );
  }

  /** Lo que el lote tomaria como entrega en esa carpeta (aproximado: sin abrir los .zip). */
  static int entregasEn( File d ) {
    File[] hijos = d.listFiles();
    int n = 0;
    for ( File f : hijos == null ? new File[ 0 ] : hijos ) {
      String nl = f.getName().toLowerCase( Locale.ROOT );
      if ( nl.startsWith( "." ) || nl.startsWith( "~$" ) || nl.equals( "__macosx" ) || nl.startsWith( "_" ) && f
        .isDirectory() ) {
        continue;
      }
      if ( f.isDirectory() || nl.endsWith( ".ktr" ) || nl.endsWith( ".kjb" ) || nl.endsWith( ".zip" ) ) {
        n++;
      }
    }
    return n;
  }

  /** La carpeta de resultados si no se elige: al lado de la de entregas («Entregas TP3 - correccion»). */
  File salidaPorDefecto( File entregas ) {
    File padre = entregas.getAbsoluteFile().getParentFile();
    String n = t.f( "ui.salida_defecto", entregas.getName() );
    return padre == null ? new File( entregas, n ) : new File( padre, n );
  }

  // ---------------------------------------------------------------- corregir

  /** «Corregir»: arranca un lote con lo de arriba (abre el pack y busca las entregas en el pool). (UI) */
  void corregir() {
    try {
      if ( ocupado || lote != null && !lote.terminado() ) {
        error( t.s( "ui.ocupado" ) );
        return;
      }
      final File p = absoluta( pack, "ui.falta_pack" );
      final File c = absoluta( carpeta, "ui.falta_carpeta" );
      if ( p == null || c == null ) {
        return;
      }
      if ( !c.isDirectory() ) {
        error( t.f( "ui.no_existe", c.getPath() ) );
        return;
      }
      File s;
      if ( salida.getText().trim().isEmpty() ) {
        s = salidaPorDefecto( c );
        salida.setText( s.getPath() );
      } else {
        s = absoluta( salida, "ui.falta_salida" );
        if ( s == null ) {
          return;
        }
      }
      ULTIMAS[ 0 ] = p.getPath();
      ULTIMAS[ 1 ] = c.getPath();
      ULTIMAS[ 2 ] = s.getPath();
      final CorrectorLote.Opciones op = new CorrectorLote.Opciones();
      op.pack = p;
      op.carpeta = c;
      op.salida = s;
      op.workers = workers.getSelection();
      op.procesos = servicio.procesos();
      ocupado = true;
      habilitar();
      info( t.s( "ui.iniciando" ) );
      servicio.enSegundoPlano( () -> {
        try {
          final CorrectorLote l = CorrectorLote.iniciar( op );
          enUi( () -> {
            ocupado = false;
            seguir( l );
          } );
        } catch ( Throwable x ) {
          enUi( () -> {
            ocupado = false;
            habilitar();
            fallo( x );
          } );
        }
        return null;
      } );
    } catch ( Throwable x ) {
      ocupado = false;
      fallo( x );
    }
  }

  /** Muestra ese lote y lo sigue hasta que termine. (UI) */
  private void seguir( CorrectorLote l ) {
    lote = l;
    finVisto = false;
    seleccionado = null;
    detalleDe = null;
    filas = new ArrayList<Fila>();
    tabla.removeAll();
    vaciarDetalle();
    refrescar();
    habilitar();
    programarRefresco();
  }

  private void programarRefresco() {
    display.timerExec( REFRESCO_MS, () -> {
      if ( raiz.isDisposed() || lote == null ) {
        return;
      }
      try {
        refrescar();
      } catch ( Throwable x ) {
        fallo( x );
      }
      if ( !finVisto ) {
        programarRefresco();
      }
    } );
  }

  /** Lee el estado del lote (en memoria, barato) y actualiza la tabla, el progreso y el detalle. (UI) */
  void refrescar() {
    CorrectorLote l = lote;
    if ( l == null ) {
      return;
    }
    boolean fin = l.terminado();
    CorrectorLote.Estado s = l.estado();
    List<Fila> nuevas = new ArrayList<Fila>();
    for ( CorrectorLote.Renglon x : s.resultados ) {
      Fila f = new Fila();
      f.alumno = x.alumno;
      f.archivo = x.archivo;
      f.estado = x.estado;
      f.nota = x.nota;
      f.ajuste = x.ajuste;
      f.comentario = x.comentario;
      f.r = l.resultado( x.alumno );
      nuevas.add( f );
    }
    filas = nuevas;
    pintarTabla();
    progreso.setMaximum( Math.max( 1, s.total ) );
    progreso.setSelection( s.hechas );
    String resumen = resumen( s, l.pack().escala );
    if ( !fin ) {
      info( t.f( "ui.corrigiendo", s.hechas, s.total ) + ( s.hechas > 0 ? " · " + resumen : "" ) );
    } else if ( CorrectorLote.CANCELADO.equals( s.estado ) ) {
      info( t.f( "ui.cancelado", s.hechas, s.total ) + " · " + resumen );
    } else if ( s.error != null ) {
      error( t.f( "ui.error", s.error ) );
    } else {
      info( resumen + " · " + t.f( "ui.terminado", s.segundos == null ? "?" : uno( s.segundos ) ) );
    }
    if ( fin && !finVisto ) {
      finVisto = true;
      habilitar();
    }
    Fila sel = fila( seleccionado );
    if ( sel != null && sel.r != detalleDe ) {
      mostrarDetalle();
    } else {
      habilitarDetalle( sel );
    }
  }

  /** «5/6 · media 5,9 · 4 aprobadas · 1 bloqueada · 1 por tiempo». */
  String resumen( CorrectorLote.Estado s, double escala ) {
    CorrectorPlanilla.Resumen r = s.resumen;
    if ( r == null ) {
      return s.hechas + "/" + s.total;
    }
    StringBuilder b = new StringBuilder( t.f( "ui.resumen", s.hechas, s.total, r.media == null ? SIN : uno( r.media ),
      r.aprobadas ) );
    if ( r.bloqueadas > 0 ) {
      b.append( " · " ).append( t.f( "ui.resumen_bloqueadas", r.bloqueadas ) );
    }
    if ( r.tiempoAgotado > 0 ) {
      b.append( " · " ).append( t.f( "ui.resumen_tiempo", r.tiempoAgotado ) );
    }
    if ( r.fallidas > 0 ) {
      b.append( " · " ).append( t.f( "ui.resumen_fallidas", r.fallidas ) );
    }
    return b.toString();
  }

  /** «Cancelar»: mata los workers en el pool (espera hasta 30 s); queda lo ya corregido. (UI) */
  void cancelar() {
    final CorrectorLote l = lote;
    if ( l == null || l.terminado() ) {
      return;
    }
    info( t.s( "ui.cancelando" ) );
    cancelar.setEnabled( false );
    tarea( () -> {
      l.cancelar();
      enUi( this::refrescar );
      return null;
    } );
  }

  // ---------------------------------------------------------------- tabla

  private void pintarTabla() {
    List<Fila> orden = new ArrayList<Fila>( filas );
    Collections.sort( orden, comparador() );
    tabla.setRedraw( false );
    try {
      tabla.setItemCount( orden.size() );
      double escala = lote == null ? 10 : lote.pack().escala;
      int sel = -1;
      for ( int i = 0; i < orden.size(); i++ ) {
        Fila f = orden.get( i );
        TableItem it = tabla.getItem( i );
        String[] x = textos( f );
        for ( int k = 0; k < x.length; k++ ) {
          it.setText( k, x[ k ] );
        }
        it.setData( f.alumno );
        Color c = null;
        if ( f.nota != null && f.r != null && f.r.terminada() ) {
          c = f.nota * 2 < escala ? display.getSystemColor( SWT.COLOR_DARK_RED ) : f.nota >= escala - 1e-9
            ? display.getSystemColor( SWT.COLOR_DARK_GREEN ) : null;
        }
        it.setForeground( 1, c );
        boolean malo = f.r != null && !ResultadoCorreccion.CORREGIDA.equals( f.r.estado ) && f.r.terminada();
        it.setForeground( 2, malo ? display.getSystemColor( SWT.COLOR_DARK_RED ) : null );
        if ( f.alumno.equals( seleccionado ) ) {
          sel = i;
        }
      }
      if ( sel >= 0 ) {
        tabla.setSelection( sel );
      } else {
        tabla.deselectAll();
      }
    } finally {
      tabla.setRedraw( true );
    }
  }

  /** Los textos de las columnas de una fila. */
  String[] textos( Fila f ) {
    String[] x = new String[ COLUMNAS.size() ];
    x[ 0 ] = f.alumno;
    x[ 1 ] = f.nota == null ? "" : uno( f.nota );
    x[ 2 ] = estadoTexto( f.estado );
    for ( int i = 0; i < CHECKS.size(); i++ ) {
      x[ 3 + i ] = marca( f.r, CHECKS.get( i ) );
    }
    x[ 7 ] = reglas( f.r );
    x[ 8 ] = f.ajuste == null ? "" : conSigno( f.ajuste );
    x[ 9 ] = f.comentario == null ? "" : f.comentario.replaceAll( "[\\r\\n]+", " " );
    return x;
  }

  String estadoTexto( String e ) {
    if ( e == null ) {
      return "";
    }
    if ( CorrectorLote.PENDIENTE.equals( e ) ) {
      return t.s( "lote.estado.pending" );
    }
    String k = "estado." + e;
    return t.tiene( k ) ? t.s( k ) : e;
  }

  /** ● todos los puntos del check, ◐ una parte, ○ ninguno, – sin resultado o sin ese check en la rubrica. */
  static String marca( ResultadoCorreccion r, String check ) {
    Criterio c = r == null || !r.terminada() ? null : r.criterio( check );
    if ( c == null || c.puntos <= 0 ) {
      return SIN;
    }
    if ( c.ganado >= c.puntos - 1e-9 ) {
      return TODO;
    }
    return c.ganado <= 1e-9 ? NADA : PARTE;
  }

  /** «2/3»: las reglas del pack y del linter aprobadas; – si la rubrica no tiene. */
  static String reglas( ResultadoCorreccion r ) {
    if ( r == null || !r.terminada() ) {
      return SIN;
    }
    int n = 0;
    int ok = 0;
    for ( Criterio c : r.criterios ) {
      if ( c != null && c.check != null && ( c.check.startsWith( ItemRubrica.PREFIJO_REGLA ) || c.check.startsWith(
        ItemRubrica.PREFIJO_LINT ) ) ) {
        n++;
        ok += c.aprobado ? 1 : 0;
      }
    }
    return n == 0 ? SIN : ok + "/" + n;
  }

  private void ordenarPor( int col ) {
    if ( col == columnaOrden ) {
      ascendente = !ascendente;
    } else {
      columnaOrden = col;
      ascendente = col != 1; // la nota, de mayor a menor
    }
    TableColumn c = tabla.getColumn( col );
    tabla.setSortColumn( c );
    tabla.setSortDirection( ascendente ? SWT.UP : SWT.DOWN );
    pintarTabla();
  }

  private Comparator<Fila> comparador() {
    final Collator col = Collator.getInstance( t.locale() );
    col.setStrength( Collator.SECONDARY );
    final int c = columnaOrden;
    final int signo = ascendente ? 1 : -1;
    return ( a, b ) -> {
      int x;
      if ( c == 1 || c == 8 ) {
        Double da = c == 1 ? a.nota : a.ajuste;
        Double db = c == 1 ? b.nota : b.ajuste;
        x = da == null ? db == null ? 0 : 1 : db == null ? -1 : signo * Double.compare( da, db ); // vacios al final
      } else {
        String sa = textos( a )[ c ];
        String sb = textos( b )[ c ];
        x = signo * col.compare( sa, sb );
      }
      return x != 0 ? x : col.compare( a.alumno, b.alumno );
    };
  }

  private Fila fila( String alumno ) {
    if ( alumno == null ) {
      return null;
    }
    for ( Fila f : filas ) {
      if ( alumno.equals( f.alumno ) ) {
        return f;
      }
    }
    return null;
  }

  private void seleccion() {
    try {
      TableItem[] s = tabla.getSelection();
      String nuevo = s.length == 0 ? null : (String) s[ 0 ].getData();
      boolean cambio = nuevo == null ? seleccionado != null : !nuevo.equals( seleccionado );
      seleccionado = nuevo;
      Fila f = fila( nuevo );
      if ( cambio ) {
        ajuste.setText( f == null || f.ajuste == null ? "" : conSigno( f.ajuste ) );
        comentario.setText( f == null || f.comentario == null ? "" : f.comentario );
        detalleDe = null;
      }
      mostrarDetalle();
    } catch ( Throwable x ) {
      fallo( x );
    }
  }

  // ---------------------------------------------------------------- detalle

  /** La correccion de la fila elegida en el Browser (HTML liviano armado en el pool). (UI) */
  void mostrarDetalle() {
    final Fila f = fila( seleccionado );
    habilitarDetalle( f );
    if ( f == null ) {
      vaciarDetalle();
      return;
    }
    tituloDetalle.setText( PanelInformes.esc( t.f( "ui.detalle_titulo", f.alumno, f.archivo == null ? "" : f.archivo )
      + ( f.nota == null ? "" : " · " + t.f( "ui.detalle_nota", uno( f.nota ), uno( lote.pack().escala ) ) ) ) );
    if ( f.r == null ) {
      detalleDe = null;
      mostrarTexto( estadoTexto( f.estado ) );
      return;
    }
    final ResultadoCorreccion r = f.r;
    final boolean comoAlumno = vistaAlumno.getSelection();
    if ( r == detalleDe && comoAlumno == detalleAlumno ) {
      return;
    }
    detalleDe = r;
    detalleAlumno = comoAlumno;
    final int gen = ++genDetalle;
    if ( navegador == null ) {
      textoDetalle.setText( CorrectorNota.markdown( comoAlumno ? CorreccionAInforme.paraAlumno( r ) : r,
        comoAlumno ) );
      return;
    }
    final String nombre = CorrectorMain.carpeta( f.alumno ) + ( comoAlumno ? "-alumno" : "" ) + "-" + gen + ".html";
    tarea( () -> {
      Tema tm = tema();
      Informe inf = CorreccionAInforme.informe( r, comoAlumno, tm );
      File dir = new File( servicio.temporales(), "detalle" );
      if ( !dir.isDirectory() && !dir.mkdirs() ) {
        throw new java.io.IOException( "Could not create " + dir );
      }
      Resultado res = InformeServicio.exportar( inf, tm, Formato.HTML_ESTATICO, new File( dir, nombre ), null );
      final File html = res.archivos.isEmpty() ? new File( dir, nombre ) : res.archivos.get( 0 );
      enUi( () -> {
        if ( gen != genDetalle || navegador.isDisposed() ) {
          borrar( html );
          return;
        }
        File antes = ultimoDetalle;
        ultimoDetalle = html;
        urlDetalle = html.getAbsoluteFile().toURI().toString();
        navegador.setUrl( urlDetalle );
        borrar( antes );
      } );
      return null;
    } );
  }

  private void mostrarTexto( String s ) {
    if ( navegador != null ) {
      genDetalle++;
      urlDetalle = null;
      navegador.setText( "<html><body style=\"font-family:Segoe UI,Arial,sans-serif;color:#555;padding:12px\">"
        + html( s ) + "</body></html>" );
    } else {
      textoDetalle.setText( s );
    }
  }

  private void vaciarDetalle() {
    detalleDe = null;
    tituloDetalle.setText( t.s( "ui.detalle_vacio" ) );
    mostrarTexto( "" );
    habilitarDetalle( null );
  }

  private void habilitarDetalle( Fila f ) {
    boolean hay = f != null && f.r != null && f.r.terminada();
    boolean libre = lote != null && lote.terminado() && !ocupado;
    guardarAjuste.setEnabled( hay && lote != null );
    ajuste.setEnabled( hay );
    comentario.setEnabled( hay );
    abrirKtr.setEnabled( hay && ktrAbierto( f.alumno ) != null );
    recorregir.setEnabled( hay && libre );
    abrirDevolucion.setEnabled( hay && devolucion( f.alumno ) != null );
  }

  /** La copia reescrita del caso visible que se conservo para «Abrir su .ktr» (§9), o null. */
  File ktrAbierto( String alumno ) {
    if ( lote == null || alumno == null ) {
      return null;
    }
    File d = new File( new File( lote.salida(), CorrectorProcesos.ABIERTOS ), CorrectorMain.carpeta( alumno ) );
    File[] k = d.listFiles( ( x, n ) -> n.toLowerCase( Locale.ROOT ).endsWith( ".ktr" ) );
    if ( k == null || k.length == 0 ) {
      return null;
    }
    Arrays.sort( k );
    return k[ 0 ];
  }

  /** La devolucion del alumno en la carpeta de resultados: .html, si no .md; o null. */
  File devolucion( String alumno ) {
    if ( lote == null || alumno == null ) {
      return null;
    }
    for ( String ext : new String[] { ".html", ".md" } ) {
      File f = new File( lote.salida(), CorrectorMain.carpeta( alumno ) + ext );
      if ( f.isFile() ) {
        return f;
      }
    }
    return null;
  }

  // ---------------------------------------------------------------- acciones sobre una entrega

  /** «Guardar»: el ajuste (con signo; vacio = sin ajuste) y el comentario van a la planilla. (UI) */
  void guardarAjuste() {
    final Fila f = fila( seleccionado );
    final CorrectorLote l = lote;
    if ( f == null || l == null ) {
      return;
    }
    final Double a;
    try {
      a = numero( ajuste.getText() );
    } catch ( NumberFormatException x ) {
      error( t.f( "ui.ajuste_invalido", ajuste.getText().trim() ) );
      return;
    }
    final String c = comentario.getText();
    guardarAjuste.setEnabled( false );
    tarea( () -> {
      l.ajustar( f.alumno, a, c );
      enUi( () -> {
        refrescar();
        Fila g = fila( f.alumno );
        info( t.f( "ui.ajuste_guardado", f.alumno, g == null || g.nota == null ? SIN : uno( g.nota ) ) );
      } );
      return null;
    } );
  }

  /** «Abrir su .ktr»: la copia que corrio el caso visible, en una pestana nueva (nunca el original). (UI) */
  void abrirKtr() {
    Fila f = fila( seleccionado );
    if ( f == null ) {
      return;
    }
    File k = ktrAbierto( f.alumno );
    if ( k == null ) {
      error( t.f( "ui.abrir_ktr_no", f.alumno ) );
      return;
    }
    try {
      servicio.abrirEnSpoon( k );
    } catch ( Throwable x ) {
      fallo( x );
    }
  }

  /** «Abrir devolucion»: la del alumno con el programa del sistema. (UI) */
  void abrirDevolucion() {
    Fila f = fila( seleccionado );
    File d = f == null ? null : devolucion( f.alumno );
    if ( d != null ) {
      servicio.abrir( d );
    }
  }

  /** «Volver a corregir con...»: el dialogo con los steps condicionales y el modo confianza. (UI, por clic) */
  void recorregir() {
    Fila f = fila( seleccionado );
    if ( f == null || f.r == null ) {
      return;
    }
    if ( lote == null || !lote.terminado() ) {
      error( t.s( "ui.re_no_terminado" ) );
      return;
    }
    if ( dialogo != null && !dialogo.shell.isDisposed() ) {
      dialogo.shell.dispose();
    }
    dialogo = new Recorreccion( f );
  }

  /** Corre la correccion de nuevo en el pool. */
  void recorregir( final String alumno, final List<String> pasos, final boolean confianza ) {
    final CorrectorLote l = lote;
    if ( l == null ) {
      return;
    }
    Fila f = fila( alumno );
    final String antes = f == null || f.nota == null ? SIN : uno( f.nota );
    ocupado = true;
    habilitar();
    habilitarDetalle( f );
    info( t.f( "ui.re_corriendo", alumno ) );
    servicio.enSegundoPlano( () -> {
      try {
        l.recorregir( alumno, pasos, confianza );
        enUi( () -> {
          ocupado = false;
          habilitar();
          refrescar();
          Fila g = fila( alumno );
          info( t.f( "ui.re_listo", alumno, g == null || g.nota == null ? SIN : uno( g.nota ), antes ) );
        } );
      } catch ( Throwable x ) {
        enUi( () -> {
          ocupado = false;
          habilitar();
          refrescar();
          fallo( x );
        } );
      }
      return null;
    } );
  }

  // ---------------------------------------------------------------- planilla, curso y devoluciones

  private void menuNotas() {
    Rectangle b = notas.getBounds();
    Point p = notas.getParent().toDisplay( b.x, b.y + b.height );
    menuNotas.setLocation( p );
    menuNotas.setVisible( true );
  }

  /** Abre notas.xlsx o notas.csv con el programa del sistema. (UI) */
  void abrirPlanilla( String ext ) {
    if ( lote == null ) {
      return;
    }
    File f = new File( lote.salida(), CorrectorPlanilla.NOMBRE + "." + ext );
    if ( !f.isFile() ) {
      error( t.f( "ui.no_existe", f.getName() ) );
      return;
    }
    servicio.abrir( f );
  }

  /** El informe del curso ({@link CorreccionAInforme#curso}) en la carpeta de resultados, y lo abre. (UI) */
  void informeCurso( final Formato f ) {
    final CorrectorLote l = lote;
    if ( l == null ) {
      return;
    }
    final List<ResultadoCorreccion> rs = new ArrayList<ResultadoCorreccion>();
    final Map<String, Double> finales = new LinkedHashMap<String, Double>();
    for ( Fila x : filas ) {
      if ( x.r != null && x.r.terminada() ) {
        rs.add( x.r );
        if ( x.nota != null ) {
          finales.put( x.alumno, x.nota );
        }
      }
    }
    info( t.s( "ui.curso_armando" ) );
    tarea( () -> {
      Tema tm = tema();
      Informe inf = CorreccionAInforme.curso( rs, finales, null, tm );
      File destino = InformeServicio.archivo( l.salida(), "curso", f );
      Resultado res = CorreccionAInforme.exportar( inf, tm, f, destino, null );
      final File hecho = res.archivos.isEmpty() ? destino : res.archivos.get( 0 );
      final String aviso = res.avisos.isEmpty() ? null : res.avisos.get( 0 );
      enUi( () -> {
        info( t.f( "ui.curso_listo", hecho.getName() ) + ( aviso == null ? "" : " · " + aviso ) );
        servicio.abrir( hecho );
      } );
      return null;
    } );
  }

  /** La devolucion de cada alumno en HTML y PDF en {@code <resultados>/devoluciones}. (UI) */
  void exportarDevoluciones() {
    final CorrectorLote l = lote;
    if ( l == null ) {
      return;
    }
    final List<Fila> con = new ArrayList<Fila>();
    for ( Fila x : filas ) {
      if ( x.r != null && x.r.terminada() ) {
        con.add( x );
      }
    }
    final File dir = new File( l.salida(), "devoluciones" );
    exportarDevoluciones.setEnabled( false );
    tarea( () -> {
      try {
        Tema tm = tema();
        int archivos = 0;
        List<String> avisos = new ArrayList<String>();
        for ( int i = 0; i < con.size(); i++ ) {
          final int n = i + 1;
          enUi( () -> info( t.f( "ui.exportando", n, con.size() ) ) );
          Fila x = con.get( i );
          Map<Formato, Resultado> m = CorreccionAInforme.exportar( x.r, true, Arrays.asList( Formato.HTML,
            Formato.PDF ), dir, CorrectorMain.carpeta( x.alumno ), tm, null );
          for ( Resultado res : m.values() ) {
            archivos += res.archivos.size();
            for ( String a : res.avisos ) {
              if ( !avisos.contains( a ) ) {
                avisos.add( a );
              }
            }
          }
        }
        final String fin = t.f( "ui.exportadas", archivos, dir.getPath() ) + ( avisos.isEmpty() ? "" : " · "
          + avisos.get( 0 ) );
        enUi( () -> info( fin ) );
      } finally {
        enUi( this::habilitar );
      }
      return null;
    } );
  }

  void abrirCarpeta() {
    if ( lote != null && lote.salida().isDirectory() ) {
      servicio.abrir( lote.salida() );
    }
  }

  // ---------------------------------------------------------------- crear pack

  /** «Crear pack desde esta transformacion»: copia la pestana activa y abre el asistente. (UI, por clic) */
  void crearPack() {
    File tmp = null;
    try {
      if ( asistente != null && !asistente.cerrado() ) {
        asistente.alFrente();
        return;
      }
      tmp = Files.createTempDirectory( "pentaho-live-asistente" ).toFile();
      CorrectorTools.Copia c;
      try {
        c = servicio.solucionActiva( tmp );
      } catch ( McpHttpServer.ToolError x ) {
        c = null;
      }
      if ( c == null || c.ktr == null || !c.ktr.isFile() ) {
        borrar( tmp );
        error( t.s( "ui.sin_transformacion" ) );
        return;
      }
      asistente = new AsistentePack( this, c, tmp );
    } catch ( Throwable x ) {
      borrar( tmp );
      fallo( x );
    }
  }

  /** El asistente guardo un pack y el docente quiere usarlo. (UI) */
  void usarPack( File f ) {
    pack.setText( f.getPath() );
    leerPack();
  }

  // ---------------------------------------------------------------- dialogo «Volver a corregir con...»

  /** Steps condicionales extra y el modo confianza para una entrega (§7: el modo confianza solo existe aca). */
  final class Recorreccion {

    final Shell shell;
    final String alumno;
    final List<Button> pasos = new ArrayList<Button>();
    final Button confianza;

    Recorreccion( Fila f ) {
      this.alumno = f.alumno;
      shell = new Shell( raiz.getShell(), SWT.DIALOG_TRIM | SWT.PRIMARY_MODAL | SWT.RESIZE );
      shell.setText( t.f( "ui.re_titulo", f.alumno ) );
      GridLayout g = new GridLayout( 1, false );
      g.marginWidth = 10;
      g.marginHeight = 10;
      shell.setLayout( g );
      List<String> bloqueados = new ArrayList<String>();
      List<String> marcar = new ArrayList<String>();
      for ( PasoBloqueado p : f.r.stepsBloqueados ) {
        if ( p == null || p.tipo == null ) {
          continue;
        }
        bloqueados.add( p.step + " (" + p.tipo + ")" );
        if ( PoliticaPack.CONDICIONAL.equals( p.nivel ) || "condicional".equals( p.nivel ) ) {
          marcar.add( p.tipo.toLowerCase( Locale.ROOT ) );
        }
      }
      Label intro = new Label( shell, SWT.WRAP );
      intro.setText( t.s( "ui.re_intro" ) );
      if ( !bloqueados.isEmpty() ) {
        Label lb = new Label( shell, SWT.WRAP );
        lb.setText( PanelInformes.esc( t.f( "ui.re_bloqueados", String.join( ", ", bloqueados ) ) ) );
        GridData gb = new GridData( SWT.FILL, SWT.TOP, true, false );
        gb.widthHint = 520;
        lb.setLayoutData( gb );
        id( lb, "re_bloqueados" );
      }
      Composite grilla = new Composite( shell, SWT.NONE );
      grilla.setLayout( new GridLayout( 3, true ) );
      for ( String s : CONDICIONALES ) {
        Button b = new Button( grilla, SWT.CHECK );
        b.setText( s );
        b.setSelection( marcar.contains( s.toLowerCase( Locale.ROOT ) ) );
        id( b, "re:" + s );
        if ( SCRIPT.equals( s ) ) {
          b.setForeground( display.getSystemColor( SWT.COLOR_RED ) );
          b.setToolTipText( t.s( "ui.re_codigo" ) );
        }
        pasos.add( b );
      }
      confianza = new Button( shell, SWT.CHECK );
      confianza.setText( t.s( "ui.re_confianza" ) );
      confianza.setForeground( display.getSystemColor( SWT.COLOR_RED ) );
      id( confianza, "re_confianza" );
      Label aviso = new Label( shell, SWT.WRAP );
      aviso.setText( t.s( "ui.re_confianza_aviso" ) );
      GridData ga = new GridData( SWT.FILL, SWT.TOP, true, false );
      ga.widthHint = 520;
      aviso.setLayoutData( ga );
      Composite bs = new Composite( shell, SWT.NONE );
      bs.setLayoutData( new GridData( SWT.END, SWT.CENTER, true, false ) );
      bs.setLayout( new RowLayout( SWT.HORIZONTAL ) );
      Button ok = boton( bs, "re_aceptar", "ui.re_aceptar", null, this::aceptar );
      boton( bs, "re_cancelar", "ui.re_cancelar", null, shell::dispose );
      shell.setDefaultButton( ok );
      shell.pack();
      Rectangle p = raiz.getShell().getBounds();
      Point s = shell.getSize();
      shell.setLocation( p.x + Math.max( 0, ( p.width - s.x ) / 2 ), p.y + Math.max( 0, ( p.height - s.y ) / 3 ) );
      shell.open();
    }

    /** Los steps marcados, en el orden de la lista. */
    List<String> marcados() {
      List<String> l = new ArrayList<String>();
      for ( Button b : pasos ) {
        if ( b.getSelection() ) {
          l.add( b.getText() );
        }
      }
      return l;
    }

    void aceptar() {
      List<String> l = marcados();
      boolean c = confianza.getSelection();
      shell.dispose();
      recorregir( alumno, l, c );
    }

    /** Un control del dialogo por su id (pruebas). */
    Control buscar( String id ) {
      return PanelCorrector.buscar( shell, id );
    }
  }

  // ---------------------------------------------------------------- ayudas

  private void alCerrar() {
    genDetalle++;
    if ( asistente != null ) {
      asistente.cerrar();
    }
    borrar( ultimoDetalle );
  }

  /** Habilita los botones segun haya lote, este corriendo o se este arrancando. (UI) */
  void habilitar() {
    if ( raiz.isDisposed() ) {
      return;
    }
    boolean corre = lote != null && !lote.terminado();
    boolean hay = lote != null;
    corregir.setEnabled( !ocupado && !corre );
    cancelar.setEnabled( corre );
    workers.setEnabled( !ocupado && !corre );
    notas.setEnabled( hay && lote.terminado() );
    exportarDevoluciones.setEnabled( hay && lote.terminado() && !ocupado );
    abrirCarpeta.setEnabled( hay );
    boolean barra = corre || ocupado;
    if ( progreso.getVisible() != barra ) {
      progreso.setVisible( barra );
      ( (GridData) progreso.getLayoutData() ).exclude = !barra;
      progreso.getParent().layout( true );
    }
    habilitarDetalle( fila( seleccionado ) );
  }

  private Tema tema() {
    Tema tm = tema;
    if ( tm == null ) {
      tm = servicio.tema();
      tema = tm;
    }
    return tm;
  }

  /** El ultimo lote de este Spoon (lo pudo arrancar el panel anterior o grade_submissions), o null. */
  private static CorrectorLote ultimoLote() {
    List<CorrectorLote> l = CorrectorLote.lotes();
    return l.isEmpty() ? null : l.get( l.size() - 1 );
  }

  private Text campo( Composite p, String id, String rotulo, String tip ) {
    Label l = new Label( p, SWT.NONE );
    l.setText( t.s( rotulo ) );
    l.setToolTipText( t.s( tip ) );
    Text x = new Text( p, SWT.BORDER | SWT.SINGLE );
    GridData g = new GridData( SWT.FILL, SWT.CENTER, true, false );
    g.widthHint = 320;
    x.setLayoutData( g );
    x.setToolTipText( t.s( tip ) );
    id( x, id );
    return x;
  }

  private Label info( Composite p, String id ) {
    Label l = new Label( p, SWT.NONE );
    GridData g = new GridData( SWT.FILL, SWT.CENTER, true, false );
    g.widthHint = 320;
    l.setLayoutData( g );
    id( l, id );
    return l;
  }

  private void info( Label l, String texto, boolean error ) {
    if ( l.isDisposed() ) {
      return;
    }
    l.setText( PanelInformes.esc( texto ) );
    l.setToolTipText( texto );
    l.setForeground( error ? display.getSystemColor( SWT.COLOR_RED ) : null );
  }

  Button boton( Composite p, String id, String rotulo, String tip, Runnable accion ) {
    Button b = new Button( p, SWT.PUSH );
    b.setText( t.s( rotulo ) );
    if ( tip != null ) {
      b.setToolTipText( t.s( tip ) );
    }
    id( b, id );
    b.addListener( SWT.Selection, e -> {
      try {
        accion.run();
      } catch ( Throwable x ) {
        fallo( x );
      }
    } );
    return b;
  }

  private MenuItem item( Menu m, String id, String rotulo, Runnable accion ) {
    MenuItem it = new MenuItem( m, SWT.PUSH );
    it.setText( t.s( rotulo ) );
    it.setData( CLAVE, id );
    it.addListener( SWT.Selection, e -> {
      try {
        accion.run();
      } catch ( Throwable x ) {
        fallo( x );
      }
    } );
    return it;
  }

  /** El item del menu Notas con ese id (pruebas). */
  MenuItem itemNotas( String id ) {
    for ( MenuItem it : menuNotas.getItems() ) {
      if ( id.equals( it.getData( CLAVE ) ) ) {
        return it;
      }
    }
    throw new IllegalArgumentException( "no menu item " + id );
  }

  static void id( Widget w, String id ) {
    w.setData( CLAVE, id );
  }

  /** El primer control con ese id dentro de raiz (pruebas y dialogos). */
  static Control buscar( Control raiz, String id ) {
    if ( id.equals( raiz.getData( CLAVE ) ) ) {
      return raiz;
    }
    if ( raiz instanceof Composite ) {
      for ( Control h : ( (Composite) raiz ).getChildren() ) {
        Control c = buscar( h, id );
        if ( c != null ) {
          return c;
        }
      }
    }
    return null;
  }

  /** La ruta absoluta del campo, o null con el error en el estado. */
  private File absoluta( Text campo, String falta ) {
    String s = campo.getText().trim();
    if ( s.isEmpty() ) {
      error( t.s( falta ) );
      return null;
    }
    File f = new File( s );
    if ( !f.isAbsolute() ) {
      error( t.f( "ui.ruta_relativa", s ) );
      return null;
    }
    return f.getAbsoluteFile();
  }

  void info( String texto ) {
    if ( !estado.isDisposed() ) {
      estado.setText( PanelInformes.esc( texto ) );
      estado.setToolTipText( texto );
      estado.setForeground( null );
      estado.getParent().layout( true );
    }
  }

  private void listo( String texto ) {
    info( texto );
  }

  void error( String texto ) {
    if ( !estado.isDisposed() ) {
      estado.setText( PanelInformes.esc( texto ) );
      estado.setToolTipText( texto );
      estado.setForeground( display.getSystemColor( SWT.COLOR_RED ) );
      estado.getParent().layout( true );
    }
  }

  void fallo( Throwable x ) {
    try {
      servicio.log( "Pentaho Live: grader panel error", x );
    } catch ( Throwable ignorada ) {
      // un servicio roto no puede tapar el error
    }
    error( t.f( "ui.error", mensaje( x ) ) );
  }

  static String mensaje( Throwable x ) {
    Throwable c = x;
    while ( c.getCause() != null && c.getCause() != c && ( c.getMessage() == null || c instanceof java.util.concurrent
      .ExecutionException ) ) {
      c = c.getCause();
    }
    String m = c.getMessage();
    return m == null || m.trim().isEmpty() ? c.getClass().getSimpleName() : m.split( "\\r?\\n" )[ 0 ];
  }

  /** En la UI si el panel sigue vivo; lo que lance va al estado. */
  void enUi( final Runnable r ) {
    if ( display.isDisposed() ) {
      return;
    }
    display.asyncExec( () -> {
      if ( !raiz.isDisposed() ) {
        try {
          r.run();
        } catch ( Throwable x ) {
          fallo( x );
        }
      }
    } );
  }

  /** En el pool; un error vuelve al estado. */
  void tarea( final Callable<Void> c ) {
    servicio.enSegundoPlano( () -> {
      try {
        c.call();
      } catch ( Throwable x ) {
        enUi( () -> fallo( x ) );
      }
      return null;
    } );
  }

  /** Una nota con un decimal en el idioma del panel («6,5»). */
  String uno( double x ) {
    return String.format( t.locale(), "%.1f", x );
  }

  /** «+0,5», «-1». */
  String conSigno( double x ) {
    DecimalFormat f = new DecimalFormat( "+0.##;-0.##", DecimalFormatSymbols.getInstance( t.locale() ) );
    return f.format( x );
  }

  /** Un numero con coma o punto decimal y signo opcional; vacio = null. */
  static Double numero( String s ) {
    String x = s == null ? "" : s.trim().replace( ',', '.' );
    if ( x.isEmpty() ) {
      return null;
    }
    if ( x.startsWith( "+" ) ) {
      x = x.substring( 1 );
    }
    if ( !x.matches( "-?\\d+(\\.\\d+)?" ) ) {
      throw new NumberFormatException( s );
    }
    return Double.valueOf( x );
  }

  static String html( String s ) {
    return s == null ? "" : s.replace( "&", "&amp;" ).replace( "<", "&lt;" ).replace( ">", "&gt;" );
  }

  static void borrar( File f ) {
    if ( f == null ) {
      return;
    }
    try {
      CorrectorSandbox.borrar( f );
    } catch ( Throwable ignorada ) {
      // queda en temporales
    }
  }
}
