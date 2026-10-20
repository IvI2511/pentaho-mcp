package io.github.ivi2511.pentahomcp.informes.ui;

import java.io.File;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * Lado derecho (diseno §5.3): una banda opcional arriba («Vista previa: los diagramas completos se ven al
 * exportar o en el navegador», solo si faltan los SVG) y el {@code Browser}.
 * <p>
 * El Browser se crea con {@code new Browser(parent, 1 << 18)}: en el SWT 3.115 de PDI ese bit no existe y
 * queda IE11 ({@code getBrowserType() = "ie"}); con un swt.jar mas nuevo (fase 2) es {@code SWT.EDGE}. Siempre
 * se carga con {@code setUrl} sobre el archivo, <b>nunca</b> con {@code setText} (WebView2 corta cerca de 2
 * MB). Si no hay motor, queda un Label con el motivo y el boton «Abrir en el navegador» sigue sirviendo. (UI)
 *
 * @dueño WP1
 */
final class VistaPrevia {

  /** SWT.EDGE en SWT 3.116+; no existe en el 3.115 de PDI (por eso el literal). */
  static final int ESTILO_EDGE = 1 << 18;

  final Composite contenedor;
  final Label banda;
  /** null si no hay motor de navegador. */
  final Browser navegador;
  final Label sinMotor;

  private final Rotulos r;
  private String url;

  VistaPrevia( Composite padre, PanelInformes panel ) {
    this.r = panel.r;
    contenedor = new Composite( padre, SWT.NONE );
    GridLayout gl = new GridLayout( 1, false );
    gl.marginWidth = 0;
    gl.marginHeight = 0;
    gl.verticalSpacing = 0;
    contenedor.setLayout( gl );

    banda = new Label( contenedor, SWT.WRAP );
    banda.setBackground( contenedor.getDisplay().getSystemColor( SWT.COLOR_INFO_BACKGROUND ) );
    banda.setForeground( contenedor.getDisplay().getSystemColor( SWT.COLOR_INFO_FOREGROUND ) );
    banda.setData( PanelInformes.CLAVE, "banda" );
    GridData gb = new GridData( SWT.FILL, SWT.TOP, true, false );
    gb.exclude = true;
    banda.setLayoutData( gb );
    banda.setVisible( false );

    Browser b = null;
    Label sin = null;
    try {
      if ( System.getProperty( "org.eclipse.swt.browser.EdgeDataDir" ) == null ) {
        // solo lo usa Edge (fase 2): perfil de WebView2 en temporales, no junto a Spoon
        System.setProperty( "org.eclipse.swt.browser.EdgeDataDir",
          new File( panel.servicio.temporales(), "edge" ).getAbsolutePath() );
      }
      b = new Browser( contenedor, ESTILO_EDGE );
      b.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, true ) );
      b.setData( PanelInformes.CLAVE, "navegador" );
    } catch ( Throwable t ) {
      panel.servicio.log( "Pentaho Live: no embedded browser for the report preview", t );
      if ( b != null && !b.isDisposed() ) {
        b.dispose();
      }
      b = null;
      sin = new Label( contenedor, SWT.WRAP );
      sin.setText( PanelInformes.esc( r.f( "no_preview_engine", PanelInformes.mensaje( t ) ) ) );
      sin.setLayoutData( new GridData( SWT.FILL, SWT.FILL, true, true ) );
      sin.setData( PanelInformes.CLAVE, "sin_motor" );
    }
    navegador = b;
    sinMotor = sin;
  }

  /** "ie", "edge"... o "none" si no hay motor. */
  String tipo() {
    try {
      return navegador == null || navegador.isDisposed() ? "none" : navegador.getBrowserType();
    } catch ( Throwable t ) {
      return "none";
    }
  }

  /** Carga el archivo (o lo recarga si es el mismo) y muestra la banda si viene texto. */
  void mostrar( File f, String textoBanda ) {
    banda( textoBanda );
    if ( navegador == null || navegador.isDisposed() || f == null ) {
      return;
    }
    String u = f.getAbsoluteFile().toURI().toString();
    if ( u.equals( url ) ) {
      navegador.refresh();
    } else {
      navegador.setUrl( u );
    }
    url = u;
  }

  /** Segunda pasada (con los SVG de Mermaid): recarga y saca la banda. */
  void recargar( File f ) {
    mostrar( f, null );
  }

  /** Sin documento: pagina en blanco. */
  void vaciar() {
    banda( null );
    if ( navegador != null && !navegador.isDisposed() && url != null ) {
      navegador.setUrl( "about:blank" );
      url = null;
    }
  }

  /** null oculta la banda. */
  void banda( String texto ) {
    boolean ver = texto != null && !texto.isEmpty();
    if ( ver ) {
      banda.setText( PanelInformes.esc( texto ) );
    }
    GridData gd = (GridData) banda.getLayoutData();
    if ( gd.exclude == ver || banda.getVisible() != ver ) {
      gd.exclude = !ver;
      banda.setVisible( ver );
      contenedor.layout( true );
    }
  }

  String url() {
    return url;
  }
}
