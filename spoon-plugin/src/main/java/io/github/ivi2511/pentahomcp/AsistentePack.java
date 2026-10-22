package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.w3c.dom.Element;

import io.github.ivi2511.pentahomcp.corrector.modelo.ItemRubrica;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.Regla;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.Aviso;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.ui.PanelInformes;

/**
 * «Crear pack desde esta transformacion» (docs/corrector-diseno.md §9): asistente de 4 pasos sobre la transformacion
 * activa, que llama a lo mismo que {@code create_exercise_pack} ({@link CorrectorCrearPack#crear}).
 * <ol>
 * <li><b>Ejercicio</b>: titulo, donde guardar el pack (carpeta o .zip), la carpeta con los datos del caso oculto
 * (opcional), la copia para alumnos, idioma y captura. «Analizar» corre la solucion en una JVM hija y arma un pack de
 * prueba en temporales: nada se guarda todavia.</li>
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
  // paso 2
  final Label entradas;
  final Combo salida;
  final Text claves;
  final Label columnas;
  final Table esperada;
  final Label avisos;
  final Button reanalizar;
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
    for ( int i = 0; i < PAGINAS; i++ ) {
      paginas[ i ] = new Composite( contenido, SWT.NONE );
      GridLayout gp = new GridLayout( 3, false );
      gp.marginWidth = 0;
      paginas[ i ].setLayout( gp );
    }

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
    captura.setItems( new String[] { t.s( "ui.as_captura_file" ), t.s( "ui.as_captura_step" ) } );
    captura.select( 0 );
    PanelCorrector.id( captura, "as_captura" );
    new Label( p1, SWT.NONE );

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
    shell.setSize( 800, 580 );
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
    pila.topControl = paginas[ n ];
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
        try ( CorrectorPack cp = CorrectorPack.cargar( op.destino ) ) {
          p = cp.pack();
          tabla = cp.pack().casos.isEmpty() ? null : CorrectorTabla.esperada( cp.esperado( cp.pack().casos.get(
            0 ) ), cp.pack() );
        }
        panel.enUi( () -> {
          if ( g != gen || shell.isDisposed() ) {
            return;
          }
          trabajando = false;
          analizado( cr, p, tabla );
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
    List<String> cols = new ArrayList<String>();
    for ( Pack.Columna c : p.salida.columnas ) {
      cols.add( c.nombre + " (" + c.tipo + ( c.formato == null ? "" : " " + c.formato ) + ")" );
    }
    columnas.setText( PanelInformes.esc( t.f( "ui.as_columnas", String.join( ", ", cols ) ) ) );
    llenarEsperada( tabla );
    List<String> av = new ArrayList<String>();
    for ( Aviso a : cr.avisos ) {
      av.add( a.codigo + ( a.detalle == null ? "" : ": " + a.detalle ) );
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
    titulo2.setText( t.f( "ui.as_esperada", tb.filas.size() ) );
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
            if ( CorrectorCrearPack.AVISO_LOCALE.equals( a.codigo ) ) {
              b.append( "\n" ).append( a.codigo ).append( ": " ).append( a.detalle );
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
    op.captura = captura.getSelectionIndex() == 1 ? Pack.Salida.CAPTURA_STEP : Pack.Salida.CAPTURA_ARCHIVO;
    List<CorrectorCrearPack.CasoNuevo> casos = new ArrayList<CorrectorCrearPack.CasoNuevo>();
    casos.add( new CorrectorCrearPack.CasoNuevo( CorrectorCrearPack.VISIBLE, null, false ) );
    String o = oculto.getText().trim();
    if ( !o.isEmpty() ) {
      File fo = new File( o );
      if ( !fo.isAbsolute() || !fo.isDirectory() ) {
        info( t.f( "ui.no_existe", o ), true );
        ir( 0 );
        return null;
      }
      casos.add( new CorrectorCrearPack.CasoNuevo( "oculto", fo.getAbsoluteFile(), true ) );
    }
    op.casos = casos;
    if ( analisis != null ) {
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
    if ( x instanceof CorrectorCrearPack.Fallo ) {
      return t.f( "ui.as_error", ( (CorrectorCrearPack.Fallo) x ).codigo, PanelCorrector.mensaje( x ) );
    }
    return t.f( "ui.error", PanelCorrector.mensaje( x ) );
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
