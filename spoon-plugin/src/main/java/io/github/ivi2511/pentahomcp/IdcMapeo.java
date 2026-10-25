package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.util.Locale;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.pentaho.di.trans.TransMeta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * «Hoja de mapeo…» del menú IDC (D3): exportar la hoja origen-destino de la transformación activa (mapping_export) y
 * comprobar una hoja rellenada (mapping_import en ensayo), con «Aplicar propuestas» (un Ctrl+Z). Los mismos caminos
 * que las herramientas MCP, con los mismos argumentos JSON; el trabajo va fuera del hilo de UI. (UI)
 */
final class IdcMapeo {

  /** Lo que el diálogo pide afuera (en Spoon, las herramientas; en las pruebas, un doble). */
  interface Servicio {
    String nombre();

    ObjectNode exportar( File destino ) throws Exception;

    ObjectNode importar( File hoja, boolean aplicar ) throws Exception;
  }

  static IdcMapeo abierto;

  final Shell shell;
  final Servicio servicio;
  final Rotulos r;
  final Button exportar;
  final Button comprobar;
  final Button aplicar;
  final Button cerrar;
  final Text resultado;
  final Label estado;
  File hoja;
  volatile boolean ocupado;

  static void abrir( Shell padre, final TransMeta m, Rotulos r ) {
    final String id = Docs.id( m );
    final String idioma = r.idioma();
    if ( abierto != null && !abierto.shell.isDisposed() ) {
      abierto.shell.close();
    }
    abierto = new IdcMapeo( padre, new Servicio() {
      @Override
      public String nombre() {
        return Docs.nombre( m );
      }

      @Override
      public ObjectNode exportar( File destino ) throws Exception {
        String n = destino.getName();
        int p = n.lastIndexOf( '.' );
        ObjectNode a = Tools.obj().put( "document", id ).put( "language", idioma ).put( "output_dir", destino
          .getAbsoluteFile().getParent() ).put( "file_name", p > 0 ? n.substring( 0, p ) : n );
        a.putArray( "formats" ).add( formato( n ) );
        return MapeoTools.exportar( a );
      }

      @Override
      public ObjectNode importar( File h, boolean aplicar ) throws Exception {
        ObjectNode a = Tools.obj().put( "document", id ).put( "language", idioma ).put( "sheet", h.getAbsolutePath() )
          .put( "apply", aplicar );
        return MapeoTools.importar( a );
      }
    }, r );
    IdcMenu.abrirShell( abierto.shell, padre );
  }

  static String formato( String nombre ) {
    String n = nombre.toLowerCase( Locale.ROOT );
    return n.endsWith( ".md" ) ? "md" : n.endsWith( ".html" ) ? "html" : n.endsWith( ".pdf" ) ? "pdf" : n.endsWith(
      ".docx" ) ? "docx" : "xlsx";
  }

  IdcMapeo( Shell padre, Servicio s, Rotulos r ) {
    this.servicio = s;
    this.r = r;
    shell = new Shell( padre, SWT.DIALOG_TRIM | SWT.RESIZE | SWT.MODELESS );
    shell.setText( r.f( "mapdlg.titulo", s.nombre() ) );
    GridLayout gl = new GridLayout( 1, false );
    gl.marginWidth = 14;
    gl.marginHeight = 12;
    shell.setLayout( gl );
    Label intro = new Label( shell, SWT.WRAP );
    intro.setText( r.s( "mapdlg.intro" ) );
    GridData gi = new GridData( SWT.FILL, SWT.TOP, true, false );
    gi.widthHint = 560;
    intro.setLayoutData( gi );
    Composite botones = new Composite( shell, SWT.NONE );
    GridLayout gb = new GridLayout( 3, false );
    gb.marginWidth = 0;
    botones.setLayout( gb );
    exportar = boton( botones, r.s( "mapdlg.exportar" ) );
    comprobar = boton( botones, r.s( "mapdlg.comprobar" ) );
    aplicar = boton( botones, r.s( "mapdlg.aplicar" ) );
    aplicar.setEnabled( false );
    resultado = new Text( shell, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER );
    GridData gr = new GridData( SWT.FILL, SWT.FILL, true, true );
    gr.heightHint = 280;
    resultado.setLayoutData( gr );
    estado = new Label( shell, SWT.WRAP );
    estado.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    cerrar = new Button( shell, SWT.PUSH );
    cerrar.setText( Rotulos.de( IdcMenu.AREA, r.idioma() ).s( "close" ) );
    GridData gc = new GridData( SWT.RIGHT, SWT.BOTTOM, false, false );
    gc.widthHint = 96;
    cerrar.setLayoutData( gc );
    cerrar.addListener( SWT.Selection, e -> shell.close() );
    exportar.addListener( SWT.Selection, e -> elegirExportar() );
    comprobar.addListener( SWT.Selection, e -> elegirHoja() );
    aplicar.addListener( SWT.Selection, e -> correrImportar( true ) );
    shell.setSize( 640, 520 );
  }

  private static Button boton( Composite c, String t ) {
    Button b = new Button( c, SWT.PUSH );
    b.setText( t );
    return b;
  }

  void elegirExportar() {
    FileDialog fd = new FileDialog( shell, SWT.SAVE );
    fd.setText( r.s( "mapdlg.exportar" ) );
    fd.setFileName( servicio.nombre() + "_" + r.s( "map.sufijo" ) + ".xlsx" );
    fd.setFilterExtensions( new String[] { "*.xlsx", "*.md", "*.html", "*.pdf", "*.docx" } );
    fd.setOverwrite( true );
    String f = fd.open();
    if ( f != null ) {
      correrExportar( new File( f ) );
    }
  }

  void elegirHoja() {
    FileDialog fd = new FileDialog( shell, SWT.OPEN );
    fd.setText( r.s( "mapdlg.comprobar" ) );
    fd.setFilterExtensions( new String[] { "*.xlsx;*.csv", "*.*" } );
    String f = fd.open();
    if ( f != null ) {
      hoja = new File( f );
      correrImportar( false );
    }
  }

  void correrExportar( final File destino ) {
    trabajar( r.s( "mapdlg.exportando" ), () -> {
      ObjectNode o = servicio.exportar( destino );
      StringBuilder sb = new StringBuilder( r.f( "mapdlg.exportado", o.path( "row_count" ).asInt() ) );
      for ( JsonNode f : o.path( "files" ) ) {
        sb.append( "\n" ).append( f.path( "path" ).asText() );
      }
      return sb.toString();
    }, false );
  }

  void correrImportar( final boolean aplicarAhora ) {
    if ( hoja == null ) {
      return;
    }
    trabajar( r.s( aplicarAhora ? "mapdlg.aplicando" : "mapdlg.comprobando" ), () -> texto( servicio.importar( hoja,
      aplicarAhora ), aplicarAhora ), !aplicarAhora );
  }

  /** El informe de mapping_import para leer (y si hay algo que aplicar). */
  String texto( ObjectNode o, boolean aplicado ) {
    StringBuilder sb = new StringBuilder( r.f( "mapdlg.resumen", o.path( "implemented" ).asInt(), o.path( "differs" )
      .asInt(), o.path( "missing" ).asInt() ) );
    propuestas = false;
    for ( JsonNode x : o.path( "rows" ) ) {
      String res = x.path( "result" ).asText();
      if ( MapeoImportar.IMPLEMENTADA.equals( res ) ) {
        continue;
      }
      sb.append( "\n• " ).append( r.s( "mapdlg.res." + res ) ).append( " · " ).append( x.path( "target" ).asText() );
      if ( x.has( "detail" ) ) {
        sb.append( " — " ).append( x.path( "detail" ).asText() );
      }
      propuestas |= x.has( "proposal" ) && !"add_output".equals( x.path( "proposal" ).asText() );
    }
    if ( o.has( "in_etl_not_in_sheet" ) ) {
      sb.append( "\n" ).append( r.f( "mapdlg.extra", o.path( "in_etl_not_in_sheet" ).size() ) );
    }
    if ( aplicado ) {
      propuestas = false;
      sb.append( "\n\n" ).append( r.s( "mapdlg.aplicado" ) );
    }
    return sb.toString();
  }

  /** Si la última comprobación dejó algo que aplicar. */
  volatile boolean propuestas;

  interface Trabajo {
    String hacer() throws Exception;
  }

  void trabajar( String mientras, final Trabajo t, final boolean habilitarAplicar ) {
    if ( ocupado ) {
      return;
    }
    ocupado = true;
    estado.setText( mientras );
    exportar.setEnabled( false );
    comprobar.setEnabled( false );
    aplicar.setEnabled( false );
    IdcMenu.enHilo( shell, "pentaho-live-mapping", () -> t.hacer(), txt -> {
      ocupado = false;
      resultado.setText( txt );
      estado.setText( "" );
      exportar.setEnabled( true );
      comprobar.setEnabled( true );
      aplicar.setEnabled( habilitarAplicar && propuestas );
    }, err -> {
      ocupado = false;
      estado.setText( r.f( "ui.error", IdcMenu.mensaje( err ) ) );
      exportar.setEnabled( true );
      comprobar.setEnabled( true );
    } );
  }
}
