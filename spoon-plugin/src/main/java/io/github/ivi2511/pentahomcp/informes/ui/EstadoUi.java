package io.github.ivi2511.pentahomcp.informes.ui;

import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;

import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.tema.SimboloIdc;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Barra de estado (diseno §5.2): ProgressBar indeterminada y Cancelar mientras corre una tarea, el texto
 * del paso (en rojo si es un error) y, a la derecha, el simbolo IDC con «Pentaho Live · IDC Studios».
 * <p>
 * Los workers avisan por {@link #progreso(int)}: los mensajes se agrupan, como mucho un {@code asyncExec}
 * cada 100 ms, y solo se muestran si la tarea sigue vigente. (UI, salvo el Progreso)
 *
 * @dueño WP1
 */
final class EstadoUi {

  /** Intervalo minimo entre dos actualizaciones del texto desde un worker. */
  static final int AGRUPAR_MS = 100;

  final Composite barra;
  final ProgressBar progreso;
  final Label texto;
  final Button cancelar;
  final Label simbolo;
  final Label pie;

  private final PanelInformes panel;
  private final Rotulos r;
  private final Display display;
  private Image imagenSimbolo;
  private boolean esError;
  /** Generacion de la tarea cuyo progreso se muestra; -1 si no hay. Solo cambia en la UI. */
  private volatile int tareaVisible = -1;

  EstadoUi( Composite padre, final PanelInformes panel ) {
    this.panel = panel;
    this.r = panel.r;
    this.display = padre.getDisplay();
    barra = new Composite( padre, SWT.NONE );
    GridLayout gl = new GridLayout( 5, false );
    gl.marginWidth = 8;
    gl.marginHeight = 3;
    barra.setLayout( gl );
    barra.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );

    progreso = new ProgressBar( barra, SWT.HORIZONTAL | SWT.INDETERMINATE );
    GridData gp = new GridData( SWT.BEGINNING, SWT.CENTER, false, false );
    gp.widthHint = 110;
    progreso.setLayoutData( gp );
    progreso.setData( PanelInformes.CLAVE, "progreso" );

    texto = new Label( barra, SWT.NONE );
    texto.setLayoutData( new GridData( SWT.FILL, SWT.CENTER, true, false ) );
    texto.setData( PanelInformes.CLAVE, "estado" );

    cancelar = new Button( barra, SWT.PUSH );
    cancelar.setText( PanelInformes.esc( r.s( "cancel" ) ) );
    cancelar.setData( PanelInformes.CLAVE, "cancelar" );
    cancelar.setLayoutData( new GridData( SWT.END, SWT.CENTER, false, false ) );
    cancelar.addListener( SWT.Selection, e -> panel.cancelar() );

    simbolo = new Label( barra, SWT.NONE );
    simbolo.setLayoutData( new GridData( SWT.END, SWT.CENTER, false, false ) );
    try {
      byte[] png = SimboloIdc.png( 22, SimboloIdc.CLARO, 1f );
      if ( png != null && png.length > 0 ) {
        imagenSimbolo = new Image( display, new ImageData( new ByteArrayInputStream( png ) ) );
        simbolo.setImage( imagenSimbolo );
      }
    } catch ( Throwable t ) {
      imagenSimbolo = null; // sin simbolo: queda el texto del pie
    }
    pie = new Label( barra, SWT.NONE );
    pie.setText( PanelInformes.esc( Tema.PIE ) );
    pie.setForeground( display.getSystemColor( SWT.COLOR_DARK_GRAY ) );
    pie.setLayoutData( new GridData( SWT.END, SWT.CENTER, false, false ) );
    pie.setData( PanelInformes.CLAVE, "pie" );

    ocupado( false );
  }

  /** Arranca la tarea de esa generacion: barra animada y Cancelar visibles. */
  void trabajando( String t, int gen ) {
    tareaVisible = gen;
    ocupado( true );
    poner( t, false );
  }

  /** Termino bien (o no hay nada corriendo). Los avisos de progreso pendientes ya no se muestran. */
  void listo( String t ) {
    tareaVisible = -1;
    ocupado( false );
    poner( t, false );
  }

  /** Error: texto en rojo; ya fue al log. */
  void error( String t ) {
    tareaVisible = -1;
    ocupado( false );
    poner( t, true );
  }

  /** Solo cambia el texto, sin tocar si hay tarea. */
  void info( String t ) {
    poner( t, false );
  }

  String texto() {
    return texto.isDisposed() ? "" : texto.getText().replace( "&&", "&" );
  }

  boolean esError() {
    return esError;
  }

  /** El Progreso de la tarea de esa generacion: agrupa los mensajes y cancela si ya no es vigente. */
  Progreso progreso( final int gen ) {
    return new Progreso() {
      private volatile String pendiente;
      private final AtomicBoolean programado = new AtomicBoolean();

      @Override
      public void paso( String t, int hecho, int total ) {
        if ( cancelado() || t == null ) {
          return;
        }
        pendiente = total > 0 ? r.f( "status_progress", t, hecho, total ) : t;
        if ( programado.compareAndSet( false, true ) ) {
          try {
            display.asyncExec( () -> display.timerExec( AGRUPAR_MS, () -> {
              programado.set( false );
              // solo si esa tarea sigue mostrando su progreso: el texto final no se pisa
              if ( tareaVisible == gen && panel.vigente( gen ) && !texto.isDisposed() ) {
                poner( pendiente, false );
              }
            } ) );
          } catch ( Throwable e ) {
            programado.set( false ); // display cerrado
          }
        }
      }

      @Override
      public boolean cancelado() {
        return !panel.vigente( gen );
      }
    };
  }

  void liberar() {
    if ( imagenSimbolo != null && !imagenSimbolo.isDisposed() ) {
      imagenSimbolo.dispose();
    }
    imagenSimbolo = null;
  }

  private void poner( String t, boolean error ) {
    if ( texto.isDisposed() ) {
      return;
    }
    esError = error;
    texto.setText( PanelInformes.esc( t == null ? "" : t ) );
    texto.setToolTipText( t );
    texto.setForeground( error ? display.getSystemColor( SWT.COLOR_RED ) : null );
    barra.layout( true );
  }

  private void ocupado( boolean si ) {
    mostrar( progreso, si );
    mostrar( cancelar, si );
    barra.layout( true );
  }

  private static void mostrar( Control c, boolean ver ) {
    GridData gd = (GridData) c.getLayoutData();
    gd.exclude = !ver;
    c.setVisible( ver );
  }
}
