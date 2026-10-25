package io.github.ivi2511.pentahomcp;

import com.fasterxml.jackson.databind.JsonNode;

import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.ui.spoon.Spoon;

import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * Solo para la prueba en vivo del menú IDC en un Spoon de pruebas (no es una prueba del runner: no empieza por
 * «Prueba»). No va en el plugin: un agente de Java adjuntado a la JVM de Spoon define esta clase en el classloader del
 * plugin (MethodHandles.privateLookupIn(IdcMenu.class).defineClass, mismo paquete) y llama a {@link #accion}. Abre los
 * diálogos donde se le pide (fuera de la pantalla) y SIN activarlos (setVisible en lugar de open), para no quitarle el
 * foco a quien usa el PC, y los toca como lo haría un clic. Devuelve un texto con lo que muestran.
 */
public final class VivoIdc {

  private static Shell acerca;

  private VivoIdc() {
  }

  /**
   * accion: "menu" (barra principal y modelo del menú), "quality", "harden", "about", "about-close", otro id del menú
   * (arrange, lesson, perspective:&lt;id&gt;), "calidad:&lt;qué&gt;" o "robustez:&lt;qué&gt;".
   */
  public static String accion( final String accion, final int x, final int y ) throws Exception {
    return UiThread.call( () -> enUi( accion, x, y ), 20000 );
  }

  private static String enUi( String accion, int x, int y ) throws Exception {
    Spoon sp = UiThread.spoon();
    Rotulos r = IdcMenu.rotulos();
    if ( "menu".equals( accion ) ) {
      StringBuilder b = new StringBuilder();
      ToolBar tb = IdcMenu.barraPrincipal( sp.getMainSpoonContainer() );
      if ( tb != null ) {
        ToolItem[] items = tb.getItems();
        for ( int i = 0; i < items.length; i++ ) {
          String t = items[ i ].getToolTipText();
          b.append( i ).append( ": " ).append( items[ i ].getData( IdcMenu.MARCA ) != null ? "[IDC] " : "" )
            .append( IdcMenu.esSelector( items[ i ], IdcMenu.textoSelector() ) ? "[perspectives] " : "" )
            .append( items[ i ].getText() ).append( " | " ).append( t == null ? "" : t.replace( '\n', ' ' ) )
            .append( '\n' );
        }
      }
      for ( IdcMenu.Entrada e : IdcMenu.modelo( r, IdcMenu.contexto( sp ) ) ) {
        b.append( "  " ).append( e ).append( '\n' );
      }
      return b.toString();
    }
    if ( "quality".equals( accion ) ) {
      AbstractMeta m = IdcMenu.activo();
      if ( IdcCalidad.abierto != null && !IdcCalidad.abierto.shell.isDisposed() ) {
        IdcCalidad.abierto.shell.close();
      }
      String id = Docs.id( m );
      IdcCalidad d = new IdcCalidad( sp.getShell(), new IdcCalidad.ServicioSpoon( id, Docs.nombre( m ), r.idioma() ),
        r, id );
      IdcCalidad.abierto = d;
      d.shell.setLocation( x, y );
      d.shell.setVisible( true );
      d.revisar();
      return "ok quality";
    }
    if ( "harden".equals( accion ) ) {
      AbstractMeta m = IdcMenu.activo();
      if ( IdcRobustez.abierto != null && !IdcRobustez.abierto.shell.isDisposed() ) {
        IdcRobustez.abierto.shell.close();
      }
      String id = Docs.id( m );
      IdcRobustez d = new IdcRobustez( sp.getShell(), new IdcRobustez.ServicioSpoon( id, Docs.nombre( m ),
        r.idioma() ), r, id );
      IdcRobustez.abierto = d;
      d.shell.setLocation( x, y );
      d.shell.setVisible( true );
      d.cargarPlan();
      return "ok harden " + ( m instanceof TransMeta );
    }
    if ( "about".equals( accion ) ) {
      acerca = IdcMenu.crearAcerca( sp.getShell(), r );
      acerca.setLocation( x, y );
      acerca.setVisible( true );
      return "ok about";
    }
    if ( "about-close".equals( accion ) ) {
      if ( acerca != null && !acerca.isDisposed() ) {
        acerca.close();
      }
      return "closed";
    }
    if ( accion.startsWith( "calidad:" ) ) {
      return calidad( accion.substring( 8 ) );
    }
    if ( accion.startsWith( "robustez:" ) ) {
      return robustez( accion.substring( 9 ) );
    }
    IdcMenu.ejecutar( sp, accion );
    return "ok " + accion;
  }

  // ---------------------------------------------------------------- «Revisar calidad…»

  private static String calidad( String que ) {
    IdcCalidad d = IdcCalidad.abierto;
    if ( d == null || d.shell.isDisposed() ) {
      return "no quality dialog";
    }
    if ( que.equals( "profunda" ) || que.equals( "robustez" ) ) {
      ( que.equals( "profunda" ) ? d.profunda : d.conRobustez ).setSelection( true );
      d.revisar();
    } else if ( que.equals( "revisar" ) ) {
      d.revisar();
    } else if ( que.equals( "cancelar" ) ) {
      d.cancelar();
    } else if ( que.startsWith( "fila:" ) ) {
      d.tabla.setSelection( Integer.parseInt( que.substring( 5 ) ) );
      d.elegida( true );
    } else if ( que.equals( "aplicar" ) ) {
      d.aplicarAuto();
    } else if ( que.startsWith( "silenciar:" ) ) {
      d.tabla.setSelection( Integer.parseInt( que.substring( 10 ) ) );
      d.elegida( false );
      IdcCalidad.Preguntador antes = IdcCalidad.preguntador;
      IdcCalidad.preguntador = ( p, t, x ) -> "IDC live test";
      try {
        d.silenciarElegido();
      } finally {
        IdcCalidad.preguntador = antes;
      }
    } else if ( que.equals( "cerrar" ) ) {
      d.shell.close();
      return "closed";
    }
    StringBuilder b = new StringBuilder();
    b.append( "title=" ).append( d.shell.getText() ).append( "\nscore=" ).append( d.puntaje.getText() )
      .append( "\nrobustness=" ).append( d.robustez.getText() ).append( "\ncounts=" ).append( d.conteos.getText() )
      .append( "\nstatus=" ).append( d.estado.getText() ).append( "\nbusy=" ).append( d.ocupado )
      .append( "\nrows=" ).append( d.tabla.getItemCount() ).append( "\napply=" ).append( d.aplicar.getText() )
      .append( d.aplicar.getEnabled() ? "" : " [off]" ).append( "\nfooter=" ).append( d.pie.getText() );
    for ( int i = 0; i < d.tabla.getItemCount() && i < 40; i++ ) {
      TableItem it = d.tabla.getItem( i );
      b.append( "\n  " ).append( it.getText( 0 ) ).append( " | " ).append( it.getText( 1 ) ).append( " | " )
        .append( it.getText( 2 ) ).append( " | " ).append( it.getText( 5 ) );
    }
    return b.toString();
  }

  // ---------------------------------------------------------------- «Robustecer…»

  private static String robustez( String que ) {
    IdcRobustez d = IdcRobustez.abierto;
    if ( d == null || d.shell.isDisposed() ) {
      return "no harden dialog";
    }
    if ( que.startsWith( "fila:" ) ) {
      d.tabla.setSelection( Integer.parseInt( que.substring( 5 ) ) );
      d.elegida();
    } else if ( que.startsWith( "marcar:" ) || que.startsWith( "desmarcar:" ) ) {
      int i = Integer.parseInt( que.substring( que.indexOf( ':' ) + 1 ) );
      TableItem it = d.tabla.getItem( i );
      it.setChecked( que.startsWith( "marcar:" ) && IdcRobustez.marcable( (JsonNode) it.getData() ) );
      d.contar();
    } else if ( que.equals( "aplicar" ) ) {
      d.aplicarPlan();
    } else if ( que.equals( "probar" ) ) {
      d.probar();
    } else if ( que.equals( "cancelar" ) ) {
      d.cancelarPrueba();
    } else if ( que.equals( "cerrar" ) ) {
      d.shell.close();
      return "closed";
    }
    StringBuilder b = new StringBuilder();
    b.append( "title=" ).append( d.shell.getText() ).append( "\nscore=" ).append( d.puntaje.getText() )
      .append( "\nstatus=" ).append( d.estado.getText() ).append( "\nbusy=" ).append( d.ocupado )
      .append( "\napply=" ).append( d.aplicar.getText() ).append( d.aplicar.getEnabled() ? "" : " [off]" );
    for ( TableItem it : d.tabla.getItems() ) {
      b.append( "\n  [" ).append( it.getChecked() ? "x" : " " ).append( "] " ).append( it.getText( 1 ) )
        .append( " | " ).append( it.getText( 2 ) ).append( " | " ).append( it.getText( 3 ) ).append( " | " )
        .append( it.getText( 4 ) );
    }
    b.append( "\nverdict=" ).append( d.veredicto.getText() ).append( "\nenvironment=" ).append( d.entorno
      .getVisible() ? d.entorno.getText() : "" );
    for ( TableItem it : d.comprobaciones.getItems() ) {
      b.append( "\n  " ).append( it.getText( 0 ) ).append( " | " ).append( it.getText( 1 ) ).append( " | " )
        .append( it.getText( 2 ) ).append( " | " ).append( it.getText( 3 ) );
    }
    b.append( "\nmessages=" ).append( d.mensajes.getText() );
    return b.toString();
  }
}
