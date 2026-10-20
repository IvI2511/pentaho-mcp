package io.github.ivi2511.pentahomcp.informes.ui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Grupo «Exportar» y botones de accion (diseno §5.1 y §5.2): formatos (HTML, PDF, Markdown, PowerPoint),
 * carpeta, y [Vista previa (F5)] [Exportar] [Abrir en el navegador] [Abrir carpeta]. Mientras corre una
 * tarea pesada, Vista previa, Exportar y Abrir en el navegador quedan deshabilitados. (UI)
 *
 * @dueño WP1
 */
final class GrupoExportar {

  /** Claves de Formato en el orden de los checks. */
  static final String[] CLAVES = { "html", "pdf", "md", "pptx" };

  final Group grupo;
  final Button[] formatos = new Button[ CLAVES.length ];
  final Text carpeta;
  final Button elegirCarpeta;
  final Composite botones;
  final Button vistaPrevia;
  final Button exportar;
  final Button abrirNavegador;
  final Button abrirCarpeta;

  private final Rotulos r;

  GrupoExportar( Composite padre, final PanelInformes panel ) {
    this.r = panel.r;
    grupo = new Group( padre, SWT.NONE );
    grupo.setText( PanelInformes.esc( r.s( "export_group" ) ) );
    grupo.setLayout( new GridLayout( 1, false ) );
    grupo.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );

    Composite fs = new Composite( grupo, SWT.NONE );
    GridLayout gf = new GridLayout( 2, true ); // 2 x 2: la columna es angosta
    gf.marginWidth = 0;
    gf.marginHeight = 0;
    fs.setLayout( gf );
    String[] textos = { "fmt_html", "fmt_pdf", "fmt_md", "fmt_pptx" };
    for ( int i = 0; i < CLAVES.length; i++ ) {
      formatos[ i ] = new Button( fs, SWT.CHECK );
      formatos[ i ].setText( PanelInformes.esc( r.s( textos[ i ] ) ) );
      formatos[ i ].setData( PanelInformes.CLAVE, "fmt:" + CLAVES[ i ] );
    }

    Composite fc = new Composite( grupo, SWT.NONE );
    GridLayout gc = new GridLayout( 3, false );
    gc.marginWidth = 0;
    gc.marginHeight = 0;
    fc.setLayout( gc );
    fc.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
    new Label( fc, SWT.NONE ).setText( PanelInformes.esc( r.s( "folder" ) ) );
    carpeta = new Text( fc, SWT.BORDER );
    GridData gt = new GridData( SWT.FILL, SWT.CENTER, true, false );
    gt.widthHint = 130;
    carpeta.setLayoutData( gt );
    carpeta.setToolTipText( r.s( "folder_tip" ) );
    carpeta.setData( PanelInformes.CLAVE, "carpeta" );
    elegirCarpeta = new Button( fc, SWT.PUSH );
    elegirCarpeta.setText( PanelInformes.esc( r.s( "choose" ) ) );
    elegirCarpeta.setToolTipText( r.s( "folder_choose_tip" ) );
    elegirCarpeta.setData( PanelInformes.CLAVE, "elegir_carpeta" );
    elegirCarpeta.addListener( SWT.Selection, e -> {
      try {
        DirectoryDialog dd = new DirectoryDialog( grupo.getShell() );
        dd.setText( r.s( "folder_dialog" ) );
        dd.setMessage( r.s( "folder_dialog" ) );
        if ( !carpeta().isEmpty() ) {
          dd.setFilterPath( carpeta() );
        }
        String elegida = dd.open();
        if ( elegida != null ) {
          carpeta( elegida );
        }
      } catch ( Throwable t ) {
        panel.fallo( t );
      }
    } );

    botones = new Composite( padre, SWT.NONE );
    GridLayout gb = new GridLayout( 2, true );
    gb.marginWidth = 0;
    botones.setLayout( gb );
    botones.setLayoutData( new GridData( SWT.FILL, SWT.TOP, true, false ) );
    vistaPrevia = boton( "preview_button", "preview_tip", "vista_previa" );
    vistaPrevia.addListener( SWT.Selection, e -> panel.vistaPreviaCompleta() );
    exportar = boton( "export", "export_tip", "exportar" );
    exportar.addListener( SWT.Selection, e -> panel.exportar() );
    abrirNavegador = boton( "open_browser", "open_browser_tip", "abrir_navegador" );
    abrirNavegador.addListener( SWT.Selection, e -> panel.abrirEnNavegador() );
    abrirCarpeta = boton( "open_folder", "open_folder_tip", "abrir_carpeta" );
    abrirCarpeta.setEnabled( false );
    abrirCarpeta.addListener( SWT.Selection, e -> panel.abrirCarpeta() );
  }

  /** Formatos y carpeta del tema (salida.formatos, salida.carpeta). */
  void cargar( Tema t ) {
    List<String> f = t.salida == null || t.salida.formatos == null ? null : t.salida.formatos;
    for ( int i = 0; i < CLAVES.length; i++ ) {
      boolean marcado = f == null ? i < 2 : f.contains( CLAVES[ i ] )
        || ( "html".equals( CLAVES[ i ] ) && f.contains( Formato.HTML_ESTATICO.clave ) );
      formatos[ i ].setSelection( marcado && formatos[ i ].isEnabled() );
    }
    carpeta( t.salida == null || t.salida.carpeta == null ? "" : t.salida.carpeta );
  }

  /**
   * Si POI no puede escribir PPTX en este proceso (RenderPptx.problema: XmlBeans que no es 3.1), el check
   * de PowerPoint queda deshabilitado y su tooltip dice por que. Con {@code motivo == null} no hace nada.
   */
  void pptxNoDisponible( String motivo ) {
    if ( motivo == null ) {
      return;
    }
    for ( int i = 0; i < CLAVES.length; i++ ) {
      if ( "pptx".equals( CLAVES[ i ] ) ) {
        formatos[ i ].setSelection( false );
        formatos[ i ].setEnabled( false );
        formatos[ i ].setToolTipText( motivo );
      }
    }
  }

  /** Los formatos marcados; HTML es el interactivo o el liviano segun salida.html del tema. */
  List<Formato> formatos( Tema t ) {
    List<Formato> out = new ArrayList<Formato>();
    for ( int i = 0; i < CLAVES.length; i++ ) {
      if ( !formatos[ i ].getSelection() || !formatos[ i ].isEnabled() ) {
        continue;
      }
      if ( "html".equals( CLAVES[ i ] ) ) {
        boolean liviano = t != null && t.salida != null && Tema.HTML_LIVIANO.equals( t.salida.html );
        out.add( liviano ? Formato.HTML_ESTATICO : Formato.HTML );
      } else {
        Formato f = Formato.deClave( CLAVES[ i ] );
        if ( f != null ) {
          out.add( f );
        }
      }
    }
    return out;
  }

  /** Claves marcadas, para guardar como predeterminado. */
  List<String> claves() {
    List<String> out = new ArrayList<String>();
    for ( int i = 0; i < CLAVES.length; i++ ) {
      if ( formatos[ i ].getSelection() ) {
        out.add( CLAVES[ i ] );
      }
    }
    return out;
  }

  String carpeta() {
    return carpeta.getText().trim();
  }

  void carpeta( String ruta ) {
    carpeta.setText( ruta == null ? "" : ruta );
  }

  /** Despues de exportar: la carpeta queda como la ultima usada y se habilita «Abrir carpeta». */
  void exportado( File dir ) {
    carpeta( dir.getPath() );
    abrirCarpeta.setEnabled( true );
  }

  void ocupado( boolean ocupado ) {
    vistaPrevia.setEnabled( !ocupado );
    exportar.setEnabled( !ocupado );
    abrirNavegador.setEnabled( !ocupado );
  }

  private Button boton( String texto, String tip, String id ) {
    Button b = new Button( botones, SWT.PUSH );
    b.setText( PanelInformes.esc( r.s( texto ) ) );
    b.setToolTipText( r.s( tip ) );
    b.setData( PanelInformes.CLAVE, id );
    b.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
    return b;
  }
}
