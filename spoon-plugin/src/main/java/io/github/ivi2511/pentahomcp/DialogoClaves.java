package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import io.github.ivi2511.pentahomcp.corrector.modelo.BaseDePrueba;
import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * Pide en Spoon la contraseña de las bases de prueba de un pack (0.6.2) cuando no se sabe: ni se escribio en esta
 * sesion ni esta en la variable {@code password_variable} del pack. Lo que se escribe queda solo en memoria hasta que
 * se cierra Spoon ({@link CorrectorBases#recordar}): nunca va a un archivo ni al pack. Vacia vale (un usuario sin
 * contraseña). Una Shell modal sin bucle propio, como el asistente: al aceptar o cerrar llama a {@code alTerminar}
 * con true (corregir) o false (cancelado). Solo se abre por un clic.
 *
 * @dueño corrector 0.6.2
 */
final class DialogoClaves {

  final Shell shell;
  private final List<BaseDePrueba> bases;
  private final List<Text> claves = new ArrayList<Text>();
  private final Consumer<Boolean> alTerminar;
  private boolean avisado;

  /** Arma y abre el dialogo. (UI) */
  DialogoClaves( PanelCorrector panel, List<BaseDePrueba> bases, Consumer<Boolean> alTerminar ) {
    this.bases = bases;
    this.alTerminar = alTerminar;
    Rotulos t = panel.t;
    shell = new Shell( panel.raiz().getShell(), SWT.DIALOG_TRIM | SWT.PRIMARY_MODAL );
    shell.setText( t.s( "ui.claves_titulo" ) );
    GridLayout g = new GridLayout( 2, false );
    g.marginWidth = 12;
    g.marginHeight = 12;
    shell.setLayout( g );
    Label intro = new Label( shell, SWT.WRAP );
    intro.setText( t.s( "ui.claves_intro" ) );
    GridData gi = new GridData( SWT.FILL, SWT.TOP, true, false, 2, 1 );
    gi.widthHint = 520;
    intro.setLayoutData( gi );
    for ( BaseDePrueba b : bases ) {
      Label l = new Label( shell, SWT.NONE );
      l.setText( t.f( "ui.claves_base", b.conexion, b.tipoNormal(), CorrectorBases.n( b.servidor )
        + ( CorrectorBases.vacio( b.puerto ) ? "" : ":" + b.puerto.trim() ), CorrectorBases.n( b.usuario ),
        CorrectorBases.n( b.base ) ) );
      Text x = new Text( shell, SWT.BORDER | SWT.SINGLE | SWT.PASSWORD );
      GridData gx = new GridData( SWT.FILL, SWT.CENTER, true, false );
      gx.widthHint = 180;
      x.setLayoutData( gx );
      PanelCorrector.id( x, "clave:" + b.conexion );
      claves.add( x );
    }
    Composite bs = new Composite( shell, SWT.NONE );
    bs.setLayoutData( new GridData( SWT.END, SWT.CENTER, true, false, 2, 1 ) );
    bs.setLayout( new RowLayout( SWT.HORIZONTAL ) );
    panel.boton( bs, "claves_aceptar", "ui.claves_aceptar", null, this::aceptar );
    panel.boton( bs, "claves_cancelar", "ui.claves_cancelar", null, shell::dispose );
    shell.addDisposeListener( e -> terminar( false ) );
    shell.pack();
    Rectangle p = panel.raiz().getShell().getBounds();
    Point s = shell.getSize();
    shell.setLocation( p.x + Math.max( 0, ( p.width - s.x ) / 2 ), p.y + Math.max( 0, ( p.height - s.y ) / 3 ) );
    shell.open();
  }

  /** Guarda lo escrito en memoria y sigue con la correccion. (UI) */
  void aceptar() {
    for ( int i = 0; i < bases.size(); i++ ) {
      CorrectorBases.recordar( bases.get( i ), claves.get( i ).getText() );
    }
    terminar( true );
    shell.dispose();
  }

  private void terminar( boolean ok ) {
    if ( !avisado ) {
      avisado = true;
      alTerminar.accept( ok );
    }
  }

  /** Un control por su id (pruebas). */
  Control buscar( String id ) {
    return PanelCorrector.buscar( shell, id );
  }
}
