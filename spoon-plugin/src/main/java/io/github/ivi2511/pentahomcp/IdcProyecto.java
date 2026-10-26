package io.github.ivi2511.pentahomcp;

import java.io.File;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.pentaho.di.base.AbstractMeta;

import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.lint.PerfilEquipo;

/**
 * «Preparar proyecto…» del menú IDC ({@link IdcMenu}, 0.7 X1): {@code pentaho-live init} en una ventana no modal. La
 * carpeta (por defecto la del documento activo; si se llama etl, la de arriba) y el nombre del perfil; la lista de lo
 * que se crearía y de lo que ya está (un ensayo de {@link ProyectoInit}, que se repite al cambiar la carpeta) y «Crear
 * lo que falta», que nunca pisa nada. Mismo camino que la herramienta MCP project_init y la línea de comandos. (UI)
 */
final class IdcProyecto {

  /** Elige una carpeta: null = cancelado. Las pruebas lo cambian (el diálogo del sistema es modal). */
  interface Elector {
    String elegir( Shell padre, String titulo, String actual );
  }

  static volatile Elector elector = ( padre, titulo, actual ) -> {
    DirectoryDialog d = new DirectoryDialog( padre );
    d.setText( titulo );
    if ( actual != null && new File( actual ).isDirectory() ) {
      d.setFilterPath( actual );
    }
    return d.open();
  };

  /** El diálogo abierto (uno a la vez). Solo en el hilo de UI. */
  static IdcProyecto abierto;

  final Shell shell;
  final Rotulos r;
  final Text carpeta;
  final Button elegir;
  final Text nombre;
  final Table tabla;
  final Label estado;
  final Button crear;
  final Button cerrar;
  private final Color verde;
  private final Color gris;
  /** El último ensayo o la última creación. */
  ProyectoInit.Hecho ultimo;

  /** Abre (o trae al frente) el diálogo. activo puede ser null. (UI) */
  static void abrir( Shell padre, AbstractMeta activo, Rotulos r ) {
    if ( abierto != null && !abierto.shell.isDisposed() ) {
      abierto.shell.setActive();
      return;
    }
    abierto = new IdcProyecto( padre, r, carpetaInicial( activo == null ? null : activo.getFilename() ) );
    IdcMenu.abrirShell( abierto.shell, padre );
  }

  /** La carpeta del archivo del documento; si se llama etl, la de arriba (el proyecto). "" si no tiene archivo. */
  static String carpetaInicial( String filename ) {
    File d = PerfilEquipo.carpetaDe( filename );
    if ( d == null ) {
      return "";
    }
    if ( d.getName().equalsIgnoreCase( "etl" ) && d.getParentFile() != null ) {
      d = d.getParentFile();
    }
    return d.getAbsolutePath();
  }

  IdcProyecto( Shell padre, Rotulos r, String inicial ) {
    this.r = r;
    shell = new Shell( padre, SWT.DIALOG_TRIM | SWT.RESIZE | SWT.MODELESS );
    shell.setText( r.s( "p_title" ) );
    final org.eclipse.swt.graphics.Image ico = IdcMenu.imagenPng( shell.getDisplay(), 28 );
    if ( ico != null ) {
      shell.setImage( ico );
      shell.addListener( SWT.Dispose, e -> ico.dispose() );
    }
    verde = new Color( shell.getDisplay(), 25, 115, 50 );
    gris = new Color( shell.getDisplay(), 100, 100, 100 );
    shell.addListener( SWT.Dispose, e -> {
      verde.dispose();
      gris.dispose();
      if ( abierto == this ) {
        abierto = null;
      }
    } );
    GridLayout gl = new GridLayout( 1, false );
    gl.marginWidth = 12;
    gl.marginHeight = 10;
    gl.verticalSpacing = 8;
    shell.setLayout( gl );

    Label intro = new Label( shell, SWT.WRAP );
    intro.setText( r.s( "p_intro" ) );
    GridData gi = new GridData( SWT.FILL, SWT.TOP, true, false );
    gi.widthHint = 560;
    intro.setLayoutData( gi );

    Composite f = new Composite( shell, SWT.NONE );
    f.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    GridLayout gf = new GridLayout( 3, false );
    gf.marginWidth = 0;
    f.setLayout( gf );
    new Label( f, SWT.NONE ).setText( r.s( "p_folder" ) );
    carpeta = new Text( f, SWT.BORDER | SWT.SINGLE );
    GridData gcar = new GridData( SWT.FILL, SWT.CENTER, true, false );
    gcar.widthHint = 380; // una ruta larga no ensancha la ventana (pack)
    carpeta.setLayoutData( gcar );
    carpeta.setText( inicial == null ? "" : inicial );
    elegir = new Button( f, SWT.PUSH );
    elegir.setText( r.s( "p_choose" ) );
    elegir.addListener( SWT.Selection, e -> {
      String d = elector.elegir( shell, r.s( "p_folder" ), carpeta.getText().trim() );
      if ( d != null ) {
        carpeta.setText( d );
      }
    } );
    new Label( f, SWT.NONE ).setText( r.s( "p_name" ) );
    nombre = new Text( f, SWT.BORDER | SWT.SINGLE );
    nombre.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false, 2, 1 ) );
    nombre.setToolTipText( r.s( "p_name_tip" ) );

    tabla = new Table( shell, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL );
    tabla.setHeaderVisible( true );
    GridData gt = new GridData( SWT.FILL, SWT.FILL, true, true );
    gt.heightHint = 300;
    tabla.setLayoutData( gt );
    TableColumn c1 = new TableColumn( tabla, SWT.LEFT );
    c1.setText( r.s( "p_what" ) );
    c1.setWidth( 360 );
    TableColumn c2 = new TableColumn( tabla, SWT.LEFT );
    c2.setText( r.s( "p_state" ) );
    c2.setWidth( 180 );

    estado = new Label( shell, SWT.WRAP );
    GridData ge = new GridData( SWT.FILL, SWT.TOP, true, false );
    ge.widthHint = 560; // el texto se parte en líneas: un error con una ruta larga no ensancha la ventana
    estado.setLayoutData( ge );

    Composite b = new Composite( shell, SWT.NONE );
    b.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    GridLayout gb = new GridLayout( 3, false );
    gb.marginWidth = 0;
    b.setLayout( gb );
    new Label( b, SWT.NONE ).setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
    crear = new Button( b, SWT.PUSH );
    crear.setText( r.s( "p_create" ) );
    crear.addListener( SWT.Selection, e -> crear() );
    cerrar = new Button( b, SWT.PUSH );
    cerrar.setText( r.s( "close" ) );
    cerrar.addListener( SWT.Selection, e -> shell.close() );
    shell.setDefaultButton( crear );

    carpeta.addListener( SWT.Modify, e -> refrescar() );
    refrescar();
    shell.pack();
    org.eclipse.swt.graphics.Point t = shell.getSize();
    shell.setSize( Math.max( 640, t.x ), Math.max( 520, t.y ) );
  }

  private File carpetaElegida() {
    String c = carpeta.getText().trim();
    if ( c.isEmpty() ) {
      return null;
    }
    File d = new File( c );
    return d.isAbsolute() ? d : null;
  }

  /** El ensayo de init para la carpeta escrita: qué se crearía y qué ya está. (UI) */
  void refrescar() {
    File d = carpetaElegida();
    if ( d != null ) {
      nombre.setMessage( ProyectoInit.nombreDe( d.getAbsoluteFile() ) );
    }
    if ( d == null ) {
      mostrar( null, r.s( "p_no_folder" ), false );
      return;
    }
    try {
      ProyectoInit.Hecho h = ProyectoInit.crear( d, nombreElegido(), r.idioma(), true );
      int n = h.cuantos( ProyectoInit.SE_CREARIA );
      String t = n == 0 ? r.s( "p_nothing" ) : r.f( "p_summary_preview", n, h.cuantos( ProyectoInit.EXISTE ) );
      mostrar( h, t, n > 0 );
    } catch ( Exception e ) {
      mostrar( null, r.f( "p_error", IdcMenu.mensaje( e ) ), false );
    }
  }

  /** Crea lo que falta y muestra qué hizo. (UI) */
  void crear() {
    File d = carpetaElegida();
    if ( d == null ) {
      return;
    }
    try {
      ProyectoInit.Hecho h = ProyectoInit.crear( d, nombreElegido(), r.idioma(), false );
      String t = r.f( "p_summary_done", h.cuantos( ProyectoInit.CREADO ), h.cuantos( ProyectoInit.EXISTE ) );
      mostrar( h, t + " " + r.s( "p_next" ), false );
    } catch ( Exception e ) {
      mostrar( null, r.f( "p_error", IdcMenu.mensaje( e ) ), false );
    }
  }

  private String nombreElegido() {
    String n = nombre.getText().trim();
    return n.isEmpty() ? null : n;
  }

  private void mostrar( ProyectoInit.Hecho h, String texto, boolean puedeCrear ) {
    ultimo = h;
    tabla.removeAll();
    if ( h != null ) {
      for ( ProyectoInit.Item i : h.items ) {
        TableItem it = new TableItem( tabla, SWT.NONE );
        String e = ProyectoInit.CREADO.equals( i.estado ) ? r.s( "p_created" ) : ProyectoInit.SE_CREARIA.equals(
          i.estado ) ? r.s( "p_will_create" ) : r.s( "p_exists" );
        it.setText( new String[] { i.ruta, e } );
        it.setForeground( 1, ProyectoInit.EXISTE.equals( i.estado ) ? gris : verde );
      }
    }
    estado.setText( texto );
    crear.setEnabled( puedeCrear );
    shell.layout( true, true );
  }
}
