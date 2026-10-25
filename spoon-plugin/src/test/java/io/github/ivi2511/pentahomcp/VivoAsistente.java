package io.github.ivi2511.pentahomcp;

import java.io.File;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.pentaho.di.ui.spoon.Spoon;
import org.pentaho.di.ui.spoon.SpoonPerspective;
import org.pentaho.di.ui.spoon.SpoonPerspectiveManager;

/**
 * Solo para la prueba en vivo del asistente «Crear pack» en un Spoon de pruebas (no es una prueba del runner: no empieza
 * por «Prueba»). No va en el plugin: un agente de Java adjuntado a la JVM de Spoon define esta clase en el classloader
 * del plugin (MethodHandles.privateLookupIn, mismo paquete) y llama a {@link #accion}. Mueve Spoon fuera de la pantalla
 * para que el asistente se abra ahi (no tapa nada) y toca sus controles como lo haria un clic. Devuelve un texto con lo
 * que muestra.
 */
public final class VivoAsistente {

  private VivoAsistente() {
  }

  /**
   * accion: "fuera" (Spoon a x,y), "perspectiva" (la del Corrector), "abrir" (el asistente sobre la pestaña activa, en
   * x,y), "estado", "set:&lt;id&gt;=&lt;valor&gt;", "clic:&lt;id&gt;", "lista:&lt;n&gt;", "generar:&lt;conexion&gt;|&lt;archivo&gt;",
   * "resumen", "texto:&lt;id&gt;", "cerrar".
   */
  public static String accion( final String accion, final int x, final int y ) throws Exception {
    return UiThread.call( () -> enUi( accion, x, y ), 30000 );
  }

  private static String enUi( String a, int x, int y ) throws Exception {
    Spoon sp = UiThread.spoon();
    if ( "fuera".equals( a ) ) {
      Shell s = sp.getShell();
      s.setLocation( x, y );
      return "ok " + s.getBounds();
    }
    if ( "perspectiva".equals( a ) ) {
      SpoonPerspectiveManager.getInstance().activatePerspective( GraderPerspective.class );
      return "ok " + ( panel() != null );
    }
    PanelCorrector p = panel();
    if ( p == null ) {
      return "sin panel";
    }
    if ( "abrir".equals( a ) ) {
      p.crearPack();
      AsistentePack w = p.asistente();
      if ( w == null ) {
        return "sin asistente: " + p.estado();
      }
      w.shell.setLocation( x, y );
      return estado( w );
    }
    AsistentePack w = p.asistente();
    if ( w == null || w.cerrado() ) {
      return "sin asistente abierto; panel: " + p.estado();
    }
    if ( "estado".equals( a ) ) {
      return estado( w );
    }
    if ( a.startsWith( "set:" ) ) {
      String[] kv = a.substring( 4 ).split( "=", 2 );
      Control c = w.buscar( kv[ 0 ] );
      if ( c instanceof Text ) {
        ( (Text) c ).setText( kv[ 1 ] );
      } else if ( c instanceof Combo ) {
        Combo cb = (Combo) c;
        for ( int i = 0; i < cb.getItemCount(); i++ ) {
          if ( cb.getItem( i ).contains( kv[ 1 ] ) ) {
            cb.select( i );
            cb.notifyListeners( SWT.Selection, new Event() );
            break;
          }
        }
      } else if ( c instanceof Button ) {
        ( (Button) c ).setSelection( Boolean.parseBoolean( kv[ 1 ] ) );
        c.notifyListeners( SWT.Selection, new Event() );
      } else {
        return "no hay control " + kv[ 0 ];
      }
      return estado( w );
    }
    if ( a.startsWith( "clic:" ) ) {
      Control c = w.buscar( a.substring( 5 ) );
      if ( c == null ) {
        return "no hay control " + a.substring( 5 );
      }
      c.notifyListeners( SWT.Selection, new Event() );
      return estado( w );
    }
    if ( a.startsWith( "lista:" ) ) {
      w.buscar( "as_lista:" + a.substring( 6 ) ).notifyListeners( SWT.MouseUp, new Event() );
      return estado( w );
    }
    if ( a.startsWith( "generar:" ) ) {
      String[] q = a.substring( 8 ).split( "[|]", 2 );
      w.generarScript( q[ 0 ], new File( q[ 1 ] ) );
      return estado( w );
    }
    if ( "resumen".equals( a ) ) {
      return w.resumen();
    }
    if ( a.startsWith( "texto:" ) ) {
      Control c = w.buscar( a.substring( 6 ) );
      return c instanceof Text ? ( (Text) c ).getText() : c instanceof Label ? ( (Label) c ).getText() : c
        instanceof Combo ? ( (Combo) c ).getText() : String.valueOf( c );
    }
    if ( a.startsWith( "error:" ) ) {
      return w.errorDe( a.substring( 6 ) );
    }
    if ( a.startsWith( "base:" ) ) {
      return w.resultadoBase( a.substring( 5 ), false ) + " | " + w.resultadoBase( a.substring( 5 ), true );
    }
    if ( "cerrar".equals( a ) ) {
      w.cerrar();
      return "cerrado";
    }
    return "accion desconocida: " + a;
  }

  private static String estado( AsistentePack w ) {
    return "pagina=" + ( w.pagina() + 1 ) + " trabajando=" + w.trabajando() + " guardado=" + w.guardado() + "\n"
      + w.estado();
  }

  private static PanelCorrector panel() {
    for ( SpoonPerspective s : SpoonPerspectiveManager.getInstance().getPerspectives() ) {
      if ( s instanceof GraderPerspective ) {
        return ( (GraderPerspective) s ).panel();
      }
    }
    return null;
  }
}
