package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import io.github.ivi2511.pentahomcp.pruebas.modelo.DefPrueba;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.corrector.modelo.Comparacion;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCaso;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba;

/**
 * «Pruebas…» del menu IDC (0.7.0, P1 y P2): las pruebas de la transformacion activa en una ventana no modal. Cada
 * prueba en verde o en rojo con su ultimo resultado; «Ejecutar» y «Ejecutar todas» corren en segundo plano (una JVM
 * aparte, {@link PruebasProcesos}) y se pueden cancelar; el clic en una prueba muestra su diferencia fila a fila (lo
 * que falta, sobra o cambia, con la clave, la columna, lo esperado y lo obtenido) y marca en el lienzo la salida que
 * fallo; «Crear prueba desde la vista previa…» abre {@link PruebasCrear}; «Ejecutar al guardar» enciende la regresion
 * de {@link PruebasGuardado}.
 * <p>
 * La logica no esta aca: el {@link Servicio} de Spoon usa los mismos caminos que las herramientas MCP
 * ({@link PruebasTools#describir}, {@link PruebasTools#crearTarea}, {@link PruebasProcesos}). Las pruebas usan un
 * servicio falso. (UI)
 *
 * @dueño P1 (0.7.0)
 */
final class PruebasDialogo {

  /** Textos en informes/textos/pruebas_*.properties. */
  static final String AREA = "pruebas";

  /** Lo que la ventana pide afuera. correr y crear vuelven enseguida (la corrida avisa por el oyente). */
  interface Servicio {
    /** El nombre de la transformacion. */
    String nombre();

    /** Su .ktr, o null si no esta guardada. */
    File ktr();

    /** Las pruebas de la transformacion. */
    List<File> pruebas();

    /** Lo que muestra la tabla de una prueba (test_list). */
    ObjectNode describir( File prueba );

    /** Corre esas pruebas (con la copia del documento abierto si documento). */
    PruebasProcesos.Corrida correr( List<File> pruebas, boolean documento, PruebasProcesos.Oyente o ) throws Exception;

    /** test_create con esos argumentos (sin document ni transformation_file: los pone el servicio). */
    PruebasProcesos.Corrida crear( ObjectNode args, PruebasProcesos.Oyente o ) throws Exception;

    /** Los steps para el dialogo de crear: {nombre, id de tipo, "sel" si esta elegido, "src" fuente, "end" final}. */
    List<String[]> steps();

    /** Marca el step en el lienzo. Nunca lanza. (UI) */
    void seleccionar( String step );
  }

  /** La ventana abierta (una a la vez). Solo en el hilo de UI. */
  static PruebasDialogo abierta;

  final Shell shell;
  final Servicio servicio;
  final Rotulos r;
  final String docId;

  final Label titulo;
  final Label resumen;
  final Table tabla;
  final Text detalle;
  final Label estado;
  final ProgressBar barra;
  final Button ejecutar;
  final Button ejecutarTodas;
  final Button cancelar;
  final Button crear;
  final Button actualizar;
  final Button documento;
  final Button alGuardar;
  final Button cerrar;

  private final Color rojo;
  private final Color verde;
  private final Color ambar;
  private final Color gris;

  /** Una fila de la tabla. */
  static final class Fila {
    final File archivo;
    final String nombre;
    ObjectNode info;
    ResultadoPrueba resultado;
    /** "running", "queued" o null. */
    String marca;

    Fila( File archivo, String nombre ) {
      this.archivo = archivo;
      this.nombre = nombre;
    }
  }

  final List<Fila> filas = new ArrayList<Fila>();
  volatile boolean ocupado;
  private PruebasProcesos.Corrida corrida;
  private int generacion;

  // ---------------------------------------------------------------- apertura desde el menu

  static Rotulos rotulos() {
    return Rotulos.de( AREA, ReportsPerspective.idiomaUi() );
  }

  /** Abre (o trae al frente) la ventana de la transformacion. (UI) */
  static void abrir( Shell padre, org.pentaho.di.base.AbstractMeta m, Rotulos r ) {
    String id = Docs.id( m );
    if ( abierta != null && !abierta.shell.isDisposed() ) {
      if ( id.equals( abierta.docId ) ) {
        abierta.shell.setActive();
        return;
      }
      abierta.shell.close();
    }
    abierta = new PruebasDialogo( padre, new ServicioSpoon( id ), r, id );
    abierta.abrir();
  }

  /** Un resultado que llego de otro lado (el guardado): si la ventana lo muestra, se actualiza. Cualquier hilo. */
  static void avisarAbierto( final ResultadoPrueba res ) {
    final PruebasDialogo d = abierta;
    if ( d == null || res == null || d.shell.isDisposed() ) {
      return;
    }
    d.shell.getDisplay().asyncExec( () -> {
      if ( !d.shell.isDisposed() && !d.ocupado ) {
        d.resultado( res );
      }
    } );
  }

  PruebasDialogo( Shell padre, Servicio s, Rotulos r, String docId ) {
    this.servicio = s;
    this.r = r;
    this.docId = docId;
    shell = new Shell( padre, SWT.DIALOG_TRIM | SWT.RESIZE | SWT.MAX | SWT.MODELESS );
    shell.setText( r.f( "t_title", s.nombre() ) );
    final org.eclipse.swt.graphics.Image ico = IdcMenu.imagenPng( shell.getDisplay(), 28 );
    if ( ico != null ) {
      shell.setImage( ico );
      shell.addListener( SWT.Dispose, e -> ico.dispose() );
    }
    Display d = shell.getDisplay();
    rojo = new Color( d, 190, 30, 30 );
    verde = new Color( d, 30, 130, 60 );
    ambar = new Color( d, 170, 105, 0 );
    gris = new Color( d, 100, 100, 100 );
    shell.addListener( SWT.Dispose, e -> {
      rojo.dispose();
      verde.dispose();
      ambar.dispose();
      gris.dispose();
      if ( abierta == this ) {
        abierta = null;
      }
    } );
    GridLayout gl = new GridLayout( 1, false );
    gl.marginWidth = 12;
    gl.marginHeight = 10;
    shell.setLayout( gl );

    Composite cab = new Composite( shell, SWT.NONE );
    cab.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    GridLayout gc = new GridLayout( 2, false );
    gc.marginWidth = 0;
    gc.horizontalSpacing = 18;
    cab.setLayout( gc );
    titulo = new Label( cab, SWT.NONE );
    titulo.setFont( IdcCalidad.negrita( titulo, 1.4 ) );
    titulo.setText( "—" );
    resumen = new Label( cab, SWT.WRAP );
    resumen.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
    resumen.setForeground( gris );

    Composite op = new Composite( shell, SWT.NONE );
    op.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    GridLayout go = new GridLayout( 7, false );
    go.marginWidth = 0;
    go.horizontalSpacing = 10;
    op.setLayout( go );
    ejecutar = boton( op, "t_run", null );
    ejecutar.addListener( SWT.Selection, e -> correr( elegidas() ) );
    ejecutarTodas = boton( op, "t_run_all", null );
    ejecutarTodas.addListener( SWT.Selection, e -> correr( todas() ) );
    cancelar = boton( op, "t_cancel", null );
    cancelar.setEnabled( false );
    cancelar.addListener( SWT.Selection, e -> cancelar() );
    barra = new ProgressBar( op, SWT.HORIZONTAL );
    GridData gb = new GridData( SWT.LEFT, SWT.CENTER, false, false );
    gb.widthHint = 150;
    barra.setLayoutData( gb );
    barra.setVisible( false );
    new Label( op, SWT.NONE ).setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
    crear = boton( op, "t_create", "t_create_tip" );
    crear.addListener( SWT.Selection, e -> crearPrueba() );
    actualizar = boton( op, "t_refresh", null );
    actualizar.addListener( SWT.Selection, e -> cargar() );

    estado = new Label( shell, SWT.WRAP );
    estado.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );

    tabla = new Table( shell, SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL );
    tabla.setHeaderVisible( true );
    tabla.setLinesVisible( true );
    GridData gt = new GridData( SWT.FILL, SWT.FILL, true, true );
    gt.heightHint = 200;
    tabla.setLayoutData( gt );
    columna( "col_status", 110 );
    columna( "col_test", 200 );
    columna( "col_outputs", 230 );
    columna( "col_rows", 90 );
    columna( "col_diff", 240 );
    columna( "col_time", 70 );
    tabla.addListener( SWT.Selection, e -> elegida( true ) );
    tabla.addListener( SWT.DefaultSelection, e -> correr( elegidas() ) );

    detalle = new Text( shell, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.READ_ONLY | SWT.V_SCROLL );
    GridData gd = new GridData( SWT.FILL, SWT.FILL, true, true );
    gd.heightHint = 170;
    detalle.setLayoutData( gd );
    detalle.setText( r.s( "t_hint" ) );

    Composite pie = new Composite( shell, SWT.NONE );
    pie.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    GridLayout gp = new GridLayout( 3, false );
    gp.marginWidth = 0;
    gp.horizontalSpacing = 16;
    pie.setLayout( gp );
    documento = new Button( pie, SWT.CHECK );
    documento.setText( r.s( "t_source_open" ) );
    documento.setToolTipText( r.s( "t_source_open_tip" ) );
    documento.setSelection( true );
    alGuardar = new Button( pie, SWT.CHECK );
    alGuardar.setText( r.s( "t_on_save" ) );
    alGuardar.setToolTipText( r.s( "t_on_save_tip" ) );
    alGuardar.setSelection( PruebasGuardado.activo() );
    alGuardar.addListener( SWT.Selection, e -> PruebasGuardado.activar( alGuardar.getSelection() ) );
    cerrar = new Button( pie, SWT.PUSH );
    cerrar.setText( r.s( "close" ) );
    GridData gce = new GridData( SWT.RIGHT, SWT.CENTER, true, false );
    gce.widthHint = 96;
    cerrar.setLayoutData( gce );
    cerrar.addListener( SWT.Selection, e -> shell.close() );

    shell.setSize( 1000, 620 );
  }

  private Button boton( Composite c, String clave, String ayuda ) {
    Button b = new Button( c, SWT.PUSH );
    b.setText( r.s( clave ) );
    if ( ayuda != null ) {
      b.setToolTipText( r.s( ayuda ) );
    }
    return b;
  }

  private void columna( String clave, int ancho ) {
    TableColumn c = new TableColumn( tabla, SWT.LEFT );
    c.setText( r.s( clave ) );
    c.setWidth( ancho );
  }

  /** Muestra la ventana, lee las pruebas y, si alguna esta en rojo, deja elegida la primera con su diferencia. (UI) */
  void abrir() {
    IdcMenu.abrirShell( shell, (Shell) shell.getParent() );
    cargar();
    elegirPrimeraRoja();
  }

  void elegirPrimeraRoja() {
    for ( int i = 0; i < filas.size(); i++ ) {
      ResultadoPrueba x = filas.get( i ).resultado;
      if ( x != null && !x.paso() && !ResultadoPrueba.CANCELADA.equals( x.estado ) ) {
        tabla.setSelection( i );
        elegida( false );
        return;
      }
    }
  }

  // ---------------------------------------------------------------- lista

  /** Lee las pruebas de la transformacion y pinta la tabla (con el ultimo resultado de esta sesion). (UI) */
  void cargar() {
    if ( ocupado ) {
      return;
    }
    Map<String, Fila> antes = new LinkedHashMap<String, Fila>();
    for ( Fila f : filas ) {
      antes.put( f.archivo.getAbsolutePath(), f );
    }
    filas.clear();
    File ktr = servicio.ktr();
    for ( File f : ktr == null ? Collections.<File>emptyList() : servicio.pruebas() ) {
      Fila x = new Fila( f, PruebasMotor.nombreDeArchivo( f ) );
      x.info = servicio.describir( f );
      Fila vieja = antes.get( f.getAbsolutePath() );
      x.resultado = vieja != null && vieja.resultado != null ? vieja.resultado : PruebasArchivos.ultimo( f );
      filas.add( x );
    }
    titulo.setText( r.f( "t_count", filas.size() ) );
    if ( ktr == null ) {
      estado.setText( r.s( "t_unsaved" ) );
    } else if ( filas.isEmpty() ) {
      estado.setText( r.s( "t_none" ) );
    } else {
      estado.setText( r.f( "t_folder", PruebasArchivos.carpetaPorDefecto( ktr ).getPath() ) );
    }
    pintar();
    libre( null );
  }

  /** Pinta toda la tabla con lo que hay en {@link #filas}. (UI) */
  void pintar() {
    int[] sel = tabla.getSelectionIndices();
    tabla.removeAll();
    for ( Fila f : filas ) {
      TableItem it = new TableItem( tabla, SWT.NONE );
      pintar( it, f );
    }
    tabla.setSelection( sel );
    pintarResumen();
  }

  private void pintar( TableItem it, Fila f ) {
    ResultadoPrueba x = f.resultado;
    String est;
    Color c;
    if ( "running".equals( f.marca ) ) {
      est = r.s( "st_running" );
      c = gris;
    } else if ( "queued".equals( f.marca ) ) {
      est = r.s( "st_queued" );
      c = gris;
    } else if ( x == null ) {
      est = r.s( "st_never" );
      c = gris;
    } else {
      est = r.s( "st_" + x.estado );
      c = ResultadoPrueba.PASO.equals( x.estado ) ? verde : ResultadoPrueba.FALLO.equals( x.estado ) ? rojo
        : ResultadoPrueba.ERROR.equals( x.estado ) ? ambar : gris;
    }
    List<String> salidas = new ArrayList<String>();
    if ( f.info != null ) {
      for ( JsonNode o : f.info.path( "outputs" ) ) {
        salidas.add( o.asText() );
      }
      for ( JsonNode o : f.info.path( "tables" ) ) {
        salidas.add( r.f( "t_table", o.asText() ) );
      }
      if ( !f.info.path( "valid" ).asBoolean( true ) ) {
        salidas.add( r.s( "t_invalid" ) );
      }
    }
    String filasTxt = "";
    String dif = "";
    String tiempo = "";
    if ( x != null && f.marca == null ) {
      int esp = 0;
      int real = 0;
      int iguales = 0;
      List<String> q = new ArrayList<String>();
      int cambian = 0;
      int faltan = 0;
      int sobran = 0;
      for ( ResultadoPrueba.Resumen s : x.salidas ) {
        esp += s.filasEsperadas == null ? 0 : s.filasEsperadas;
        real += s.filasReales == null ? 0 : s.filasReales;
        iguales += s.iguales == null ? 0 : s.iguales;
        cambian += s.distintas == null ? 0 : s.distintas;
        faltan += s.faltan == null ? 0 : s.faltan;
        sobran += s.sobran == null ? 0 : s.sobran;
      }
      if ( !x.salidas.isEmpty() ) {
        filasTxt = real + "/" + esp;
      }
      if ( cambian > 0 ) {
        q.add( r.f( "t_changed", cambian ) );
      }
      if ( faltan > 0 ) {
        q.add( r.f( "t_missing", faltan ) );
      }
      if ( sobran > 0 ) {
        q.add( r.f( "t_extra", sobran ) );
      }
      dif = q.isEmpty() ? x.paso() ? r.s( "t_same" ) : IdcCalidad.una( x.mensaje == null ? "" : x.mensaje ) : String
        .join( " · ", q );
      tiempo = x.segundos == null ? "" : r.f( "t_seconds", x.segundos );
    }
    it.setText( new String[] { est, f.nombre, String.join( ", ", salidas ), filasTxt, dif, tiempo } );
    it.setForeground( 0, c );
  }

  private void pintarResumen() {
    int verdes = 0;
    int rojas = 0;
    for ( Fila f : filas ) {
      if ( f.resultado != null && f.marca == null ) {
        if ( f.resultado.paso() ) {
          verdes++;
        } else if ( !ResultadoPrueba.CANCELADA.equals( f.resultado.estado ) ) {
          rojas++;
        }
      }
    }
    resumen.setText( filas.isEmpty() ? "" : r.f( "t_summary", filas.size(), verdes, rojas ) );
    resumen.setForeground( rojas > 0 ? rojo : verdes == filas.size() && verdes > 0 ? verde : gris );
    shell.layout( true, true );
  }

  List<Fila> elegidas() {
    List<Fila> l = new ArrayList<Fila>();
    for ( int i : tabla.getSelectionIndices() ) {
      if ( i >= 0 && i < filas.size() ) {
        l.add( filas.get( i ) );
      }
    }
    return l.isEmpty() ? todas() : l;
  }

  List<Fila> todas() {
    return new ArrayList<Fila>( filas );
  }

  // ---------------------------------------------------------------- correr

  /** Corre esas pruebas en segundo plano, con cancelar. (UI) */
  void correr( final List<Fila> cuales ) {
    if ( ocupado || cuales.isEmpty() || shell.isDisposed() ) {
      return;
    }
    final int gen = ++generacion;
    final Map<String, Fila> porArchivo = new LinkedHashMap<String, Fila>();
    List<File> archivos = new ArrayList<File>();
    for ( Fila f : cuales ) {
      f.marca = "queued";
      porArchivo.put( PruebasArchivos.canonica( f.archivo ), f );
      archivos.add( f.archivo );
    }
    final int total = cuales.size();
    final int[] hechas = { 0 };
    ocupado( r.f( "t_running", total ), total );
    pintar();
    final Display d = shell.getDisplay();
    PruebasProcesos.Oyente oyente = new PruebasProcesos.Oyente() {
      @Override
      public void empezada( final PruebasProcesos.Tarea t ) {
        d.asyncExec( () -> {
          if ( shell.isDisposed() || gen != generacion ) {
            return;
          }
          Fila f = porArchivo.get( PruebasArchivos.canonica( t.prueba ) );
          if ( f != null ) {
            f.marca = "running";
            pintarFila( f );
          }
        } );
      }

      @Override
      public void terminada( final PruebasProcesos.Tarea t, final ResultadoPrueba res ) {
        d.asyncExec( () -> {
          if ( shell.isDisposed() || gen != generacion ) {
            return;
          }
          Fila f = porArchivo.get( PruebasArchivos.canonica( t.prueba ) );
          if ( f != null ) {
            f.marca = null;
            f.resultado = res;
            pintarFila( f );
            if ( esElegida( f ) ) {
              elegida( false );
            }
          }
          hechas[ 0 ]++;
          barra.setSelection( hechas[ 0 ] );
          pintarResumen();
          if ( hechas[ 0 ] >= total ) {
            terminar( cuales );
          }
        } );
      }
    };
    try {
      corrida = servicio.correr( archivos, documento.getSelection(), oyente );
    } catch ( Throwable e ) {
      for ( Fila f : cuales ) {
        f.marca = null;
      }
      pintar();
      libre( r.f( "t_error", IdcMenu.mensaje( e ) ) );
    }
  }

  private void terminar( List<Fila> cuales ) {
    corrida = null;
    int verdes = 0;
    List<ResultadoPrueba> rs = new ArrayList<ResultadoPrueba>();
    boolean cancelada = false;
    for ( Fila f : cuales ) {
      if ( f.resultado != null ) {
        rs.add( f.resultado );
        verdes += f.resultado.paso() ? 1 : 0;
        cancelada |= ResultadoPrueba.CANCELADA.equals( f.resultado.estado );
      }
    }
    libre( cancelada ? r.s( "t_cancelled" ) : r.f( "t_done", verdes, cuales.size() - verdes ) );
    try {
      if ( !cancelada && cuales.size() == filas.size() ) {
        PruebasGuardado.mostrar( UiThread.spoon(), servicio.nombre(), rs, false );
      }
    } catch ( Throwable ignored ) {
      // sin Spoon (pruebas)
    }
  }

  /** Corta la corrida: la prueba en curso se para y las que faltan quedan canceladas. (UI) */
  void cancelar() {
    PruebasProcesos.Corrida c = corrida;
    if ( !ocupado || c == null ) {
      return;
    }
    estado.setText( r.s( "t_cancelling" ) );
    cancelar.setEnabled( false );
    c.cancelar();
  }

  private boolean esElegida( Fila f ) {
    int i = tabla.getSelectionIndex();
    return i >= 0 && i < filas.size() && filas.get( i ) == f;
  }

  private void pintarFila( Fila f ) {
    int i = filas.indexOf( f );
    if ( i >= 0 && i < tabla.getItemCount() ) {
      pintar( tabla.getItem( i ), f );
    }
  }

  /** Un resultado de afuera (el guardado). (UI) */
  void resultado( ResultadoPrueba res ) {
    for ( Fila f : filas ) {
      if ( res.archivoPrueba != null && PruebasArchivos.canonica( f.archivo ).equalsIgnoreCase( PruebasArchivos
        .canonica( new File( res.archivoPrueba ) ) ) ) {
        f.resultado = res;
        pintarFila( f );
        if ( esElegida( f ) ) {
          elegida( false );
        }
      }
    }
    pintarResumen();
  }

  // ---------------------------------------------------------------- detalle

  /** Clic en una prueba: su resultado y, si conLienzo, la salida que fallo marcada en el lienzo. (UI) */
  void elegida( boolean conLienzo ) {
    int i = tabla.getSelectionIndex();
    if ( i < 0 || i >= filas.size() ) {
      return;
    }
    Fila f = filas.get( i );
    detalle.setText( detalle( f ) );
    if ( conLienzo && f.resultado != null && !f.resultado.paso() ) {
      for ( ResultadoPrueba.Resumen s : f.resultado.salidas ) {
        if ( !s.paso && ResultadoPrueba.Resumen.STEP.equals( s.tipo ) ) {
          servicio.seleccionar( s.nombre );
          break;
        }
      }
    }
  }

  /** El texto del detalle de una prueba: estado, por que, la corrida y la diferencia de cada salida. */
  String detalle( Fila f ) {
    StringBuilder b = new StringBuilder();
    ResultadoPrueba x = f.resultado;
    b.append( f.nombre );
    if ( f.info != null && f.info.hasNonNull( "description" ) ) {
      b.append( " — " ).append( f.info.get( "description" ).asText() );
    }
    b.append( '\n' );
    if ( f.info != null && !f.info.path( "valid" ).asBoolean( true ) ) {
      b.append( f.info.path( "problem" ).asText() ).append( '\n' );
    }
    if ( x == null ) {
      b.append( r.s( "d_never" ) );
      return b.toString();
    }
    b.append( r.s( "st_" + x.estado ) );
    if ( x.mensaje != null ) {
      b.append( " · " ).append( x.mensaje );
    }
    b.append( '\n' );
    ResultadoCaso rc = x.caso;
    if ( rc != null && rc.corrida != null && rc.corrida.estado != null ) {
      b.append( r.f( "d_run", rc.corrida.estado, rc.corrida.segundos == null ? 0.0 : rc.corrida.segundos,
        rc.corrida.errores == null ? 0L : rc.corrida.errores ) );
      if ( ResultadoPrueba.DEL_DOCUMENTO.equals( x.origen ) ) {
        b.append( " · " ).append( r.s( "d_open_doc" ) );
      }
      b.append( '\n' );
    }
    for ( ResultadoPrueba.Resumen s : x.salidas ) {
      b.append( '\n' );
      String quien = ResultadoPrueba.Resumen.TABLA.equals( s.tipo ) ? r.f( "t_table", s.nombre ) : s.nombre;
      if ( ResultadoPrueba.Resumen.SIN_STEP.equals( s.problema ) ) {
        b.append( r.f( "d_missing_step", quien ) ).append( '\n' );
        continue;
      }
      if ( ResultadoPrueba.Resumen.SIN_DORADO.equals( s.problema ) ) {
        b.append( r.f( "d_missing_golden", quien, s.dorado ) ).append( '\n' );
        continue;
      }
      if ( ResultadoPrueba.Resumen.SIN_CORRIDA.equals( s.problema ) ) {
        b.append( r.f( "d_not_run", quien ) ).append( '\n' );
        continue;
      }
      b.append( s.paso ? "✓ " : "✗ " ).append( r.f( "d_output", quien, n( s.filasEsperadas ), n(
        s.filasReales ), n( s.iguales ), n( s.distintas ), n( s.faltan ), n( s.sobran ) ) ).append( '\n' );
      if ( s.columnasFaltan != null ) {
        b.append( "   " ).append( r.f( "d_missing_cols", String.join( ", ", s.columnasFaltan ) ) ).append( '\n' );
      }
      if ( s.columnasSobran != null ) {
        b.append( "   " ).append( r.f( "d_extra_cols", String.join( ", ", s.columnasSobran ) ) ).append( '\n' );
      }
      Comparacion c = comparacion( rc, s.nombre );
      if ( c != null && c.ejemplos != null ) {
        for ( Comparacion.Ejemplo e : c.ejemplos.distintas ) {
          b.append( "   " ).append( r.f( "d_changed", clave( e ), e.columna, nulo( e.esperado ), nulo( e.real ) ) )
            .append( '\n' );
        }
        for ( Comparacion.Ejemplo e : c.ejemplos.faltan ) {
          b.append( "   " ).append( r.f( "d_missing_row", fila( e ) ) ).append( '\n' );
        }
        for ( Comparacion.Ejemplo e : c.ejemplos.sobran ) {
          b.append( "   " ).append( r.f( "d_extra_row", fila( e ) ) ).append( '\n' );
        }
        for ( Comparacion.Ejemplo e : c.ejemplos.clavesRepetidas ) {
          b.append( "   " ).append( r.f( "d_dup", clave( e ), e.veces == null ? 0 : e.veces ) ).append( '\n' );
        }
      }
    }
    if ( rc != null && rc.corrida != null && rc.corrida.log != null && !x.paso() && ( rc.corrida.errores != null
      && rc.corrida.errores > 0 || !ResultadoCaso.TERMINO.equals( rc.corrida.estado ) ) ) {
      b.append( '\n' ).append( r.s( "d_log" ) ).append( '\n' ).append( rc.corrida.log ).append( '\n' );
    }
    if ( !x.notas.isEmpty() ) {
      b.append( '\n' ).append( r.s( "d_notes" ) ).append( '\n' );
      for ( String nota : x.notas ) {
        b.append( "· " ).append( nota ).append( '\n' );
      }
    }
    return b.toString().trim();
  }

  private static Comparacion comparacion( ResultadoCaso rc, String nombre ) {
    if ( rc == null || rc.tablas == null ) {
      return null;
    }
    for ( ResultadoCaso.ComparacionTabla t : rc.tablas ) {
      if ( nombre != null && nombre.equals( t.tabla ) ) {
        return t.comparacion;
      }
    }
    return null;
  }

  private String clave( Comparacion.Ejemplo e ) {
    if ( e.clave != null ) {
      return e.clave;
    }
    return e.fila == null ? "" : e.fila.toString();
  }

  private String fila( Comparacion.Ejemplo e ) {
    if ( e.fila != null && !e.fila.isEmpty() ) {
      List<String> l = new ArrayList<String>();
      for ( Map.Entry<String, String> x : e.fila.entrySet() ) {
        l.add( x.getKey() + "=" + nulo( x.getValue() ) );
      }
      return String.join( ", ", l );
    }
    return e.clave == null ? "" : e.clave;
  }

  private String nulo( String v ) {
    return v == null ? r.s( "d_null" ) : v;
  }

  private static int n( Integer v ) {
    return v == null ? 0 : v;
  }

  // ---------------------------------------------------------------- crear

  /** «Crear prueba desde la vista previa…». (UI) */
  void crearPrueba() {
    if ( ocupado ) {
      return;
    }
    if ( servicio.ktr() == null ) {
      estado.setText( r.s( "t_unsaved" ) );
      return;
    }
    abridor.accept( new PruebasCrear( this ) );
  }

  /** Como se muestra la ventana de crear (las pruebas la dejan fuera de la pantalla, sin activarla). */
  static volatile java.util.function.Consumer<PruebasCrear> abridor = PruebasCrear::abrir;

  /** La prueba nueva ya esta: se relee la lista y queda elegida. (UI) */
  void creada( ResultadoPrueba res ) {
    cargar();
    if ( res == null ) {
      return;
    }
    for ( int i = 0; i < filas.size(); i++ ) {
      Fila f = filas.get( i );
      if ( res.archivoPrueba != null && PruebasArchivos.canonica( f.archivo ).equalsIgnoreCase( PruebasArchivos
        .canonica( new File( res.archivoPrueba ) ) ) ) {
        f.resultado = res;
        pintarFila( f );
        tabla.setSelection( i );
        elegida( false );
      }
    }
    pintarResumen();
    try {
      PruebasGuardado.olvidar( servicio.ktr() );
    } catch ( Throwable ignored ) {
      // nada
    }
  }

  // ---------------------------------------------------------------- estado

  void ocupado( String texto, int total ) {
    ocupado = true;
    estado.setText( texto );
    barra.setMaximum( Math.max( 1, total ) );
    barra.setSelection( 0 );
    barra.setVisible( true );
    cancelar.setEnabled( true );
    ejecutar.setEnabled( false );
    ejecutarTodas.setEnabled( false );
    crear.setEnabled( false );
    actualizar.setEnabled( false );
    shell.layout( true, true );
  }

  void libre( String texto ) {
    if ( shell.isDisposed() ) {
      return;
    }
    ocupado = false;
    if ( texto != null ) {
      estado.setText( texto );
    }
    barra.setVisible( false );
    cancelar.setEnabled( false );
    boolean hay = !filas.isEmpty();
    ejecutar.setEnabled( hay );
    ejecutarTodas.setEnabled( hay );
    crear.setEnabled( servicio.ktr() != null );
    actualizar.setEnabled( true );
    shell.layout( true, true );
  }

  // ---------------------------------------------------------------- el servicio de Spoon

  /** Los mismos caminos que las herramientas MCP, sobre el documento abierto. */
  static final class ServicioSpoon implements Servicio {
    final String doc;

    ServicioSpoon( String doc ) {
      this.doc = doc;
    }

    private org.pentaho.di.base.AbstractMeta meta() throws Exception {
      return Docs.resolverSinActivar( Tools.obj().put( "document", doc ) );
    }

    @Override
    public String nombre() {
      try {
        return Docs.nombre( meta() );
      } catch ( Exception e ) {
        return doc;
      }
    }

    @Override
    public File ktr() {
      try {
        return PruebasTools.archivoDe( meta() );
      } catch ( Exception e ) {
        return null;
      }
    }

    @Override
    public List<File> pruebas() {
      File k = ktr();
      return k == null ? new ArrayList<File>() : PruebasArchivos.deTransformacion( k );
    }

    @Override
    public ObjectNode describir( File prueba ) {
      return PruebasTools.describir( prueba );
    }

    @Override
    public PruebasProcesos.Corrida correr( List<File> pruebas, boolean documento, PruebasProcesos.Oyente o )
      throws Exception {
      List<File> temporales = new ArrayList<File>();
      File copia = documento ? PruebasTools.copia( doc, temporales ) : null;
      List<PruebasProcesos.Tarea> tareas = new ArrayList<PruebasProcesos.Tarea>();
      for ( File f : pruebas ) {
        tareas.add( PruebasProcesos.Tarea.correr( f, copia ) );
      }
      return PruebasProcesos.iniciar( tareas, o, temporales );
    }

    @Override
    public PruebasProcesos.Corrida crear( ObjectNode args, PruebasProcesos.Oyente o ) throws Exception {
      List<File> temporales = new ArrayList<File>();
      args.put( "document", doc );
      PruebasProcesos.Tarea t = PruebasTools.crearTarea( args, temporales );
      return PruebasProcesos.iniciar( Collections.singletonList( t ), o, temporales );
    }

    @Override
    public List<String[]> steps() {
      List<String[]> l = new ArrayList<String[]>();
      try {
        org.pentaho.di.base.AbstractMeta m = meta();
        if ( m instanceof org.pentaho.di.job.JobMeta ) { // un job: los steps de sus entradas Transformation
          for ( java.util.Map.Entry<String, TransMeta> e : PruebasJob.transformaciones( (org.pentaho.di.job.JobMeta) m )
            .entrySet() ) {
            for ( String[] s : steps( e.getValue() ) ) {
              s[ 0 ] = DefPrueba.nombreCompleto( e.getKey(), s[ 0 ] );
              s[ 2 ] = s[ 2 ].replace( "sel", "" );
              l.add( s );
            }
          }
          return l;
        }
        l.addAll( steps( (TransMeta) m ) );
      } catch ( Exception ignored ) {
        // sin documento
      }
      return l;
    }

    static List<String[]> steps( TransMeta tm ) {
      List<String[]> l = new ArrayList<String[]>();
      {
        for ( StepMeta sm : tm.getSteps() ) {
          int entran = 0;
          int salen = 0;
          for ( TransHopMeta h : tm.getTransHops() ) {
            if ( h.isEnabled() && h.getToStep() == sm ) {
              entran++;
            }
            if ( h.isEnabled() && h.getFromStep() == sm ) {
              salen++;
            }
          }
          List<String> marcas = new ArrayList<String>();
          if ( sm.isSelected() ) {
            marcas.add( "sel" );
          }
          if ( entran == 0 && salen > 0 && !PruebasMotor.FUENTES_FIJAS.contains( sm.getStepID() ) ) {
            marcas.add( "src" );
          }
          if ( salen == 0 && entran > 0 ) {
            marcas.add( "end" );
          }
          l.add( new String[] { sm.getName(), sm.getStepID(), String.join( ",", marcas ) } );
        }
      }
      return l;
    }

    @Override
    public void seleccionar( String step ) {
      try {
        org.pentaho.di.base.AbstractMeta m = meta();
        if ( !( m instanceof TransMeta ) ) {
          Docs.activar( m ); // un job: la salida "Entrada :: Step" no esta en su lienzo
          return;
        }
        TransMeta tm = (TransMeta) m;
        Docs.activar( tm );
        TransTools.seleccionar( tm, Collections.singletonList( step ) );
      } catch ( Throwable t ) {
        McpLifecycleListener.LOG.logDebug( "Pentaho Live: could not select '" + step + "' on the canvas: " + t );
      }
    }
  }
}
