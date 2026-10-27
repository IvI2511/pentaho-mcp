package io.github.ivi2511.pentahomcp;

import java.io.File;

import org.eclipse.swt.widgets.Display;
import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.ui.spoon.Spoon;

/**
 * Sólo para la prueba en vivo de «Traducir a Python…» en un Spoon de pruebas (no es una prueba del runner: no empieza
 * por «Prueba»). Como {@link VivoIdc}: un agente de Java adjuntado a la JVM de Spoon define esta clase en el
 * classloader del plugin y llama a {@link #accion}. Abre el diálogo donde se le pide y SIN activarlo (setVisible en
 * lugar de open), para no quitarle el foco a quien usa el PC, y lo toca como lo haría un clic.
 */
public final class VivoTraducir {

  private static TraducirDialogo dialogo;

  private VivoTraducir() {
  }

  /**
   * accion: "abrir" (en x, y), "generar:&lt;ruta&gt;" (notebook pandas con EDA y verificación en esa ruta), "estado" o
   * "cerrar".
   */
  public static String accion( final String accion, final int x, final int y ) throws Exception {
    return UiThread.call( () -> enUi( accion, x, y ), 20000 );
  }

  private static String enUi( String accion, int x, int y ) throws Exception {
    Spoon sp = UiThread.spoon();
    if ( "abrir".equals( accion ) ) {
      AbstractMeta m = IdcMenu.activo();
      if ( m == null ) {
        return "sin documento";
      }
      if ( dialogo != null && !dialogo.shell.isDisposed() ) {
        dialogo.shell.dispose();
      }
      dialogo = new TraducirDialogo( sp.getShell(), m, TraducirDialogo.rotulos() );
      dialogo.shell.pack();
      org.eclipse.swt.graphics.Point t = dialogo.shell.getSize();
      dialogo.shell.setSize( Math.max( t.x, 620 ), t.y );
      dialogo.shell.setLocation( x, y );
      dialogo.shell.setVisible( true );
      dialogo.buscarPython();
      return describir();
    }
    if ( dialogo == null || dialogo.shell.isDisposed() ) {
      return "sin diálogo";
    }
    if ( accion.startsWith( "generar:" ) ) {
      File f = new File( accion.substring( 8 ) );
      f.delete();
      dialogo.ipynb.setSelection( true );
      dialogo.py.setSelection( false );
      dialogo.pandas.setSelection( true );
      dialogo.pyspark.setSelection( false );
      dialogo.titulo.setText( "Ventas por tienda" );
      dialogo.autor.setText( "Equipo de datos" );
      dialogo.intro.setText( "Prueba en vivo del traductor." );
      dialogo.eda.setSelection( true );
      dialogo.verificar.setSelection( dialogo.verificar.isEnabled() );
      dialogo.salida.setText( f.getAbsolutePath() );
      dialogo.salidaEditada = true;
      dialogo.generar();
      return "generando " + f;
    }
    if ( "estado".equals( accion ) ) {
      return describir();
    }
    if ( "cerrar".equals( accion ) ) {
      dialogo.shell.dispose();
      dialogo = null;
      return "cerrado";
    }
    return "acción desconocida: " + accion;
  }

  private static String describir() {
    TraducirDialogo d = dialogo;
    Display.getCurrent();
    return "titulo=" + d.shell.getText() + "\nverificar.enabled=" + d.verificar.isEnabled() + " tip="
      + d.verificar.getToolTipText() + "\nsalida=" + d.salida.getText() + "\nocupado=" + d.ocupado + "\nestado="
      + d.estado.getText() + "\nresumen=" + d.resumen.getText() + "\nabrirArchivo.enabled=" + d.abrirArchivo
        .isEnabled();
  }
}
