package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.ToolTip;
import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.ui.spoon.Spoon;

import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.tema.Ajustes;
import io.github.ivi2511.pentahomcp.pruebas.modelo.ResultadoPrueba;

/**
 * Regresion al guardar (0.7.0, P2) y el indicador de las pruebas en el boton IDC.
 * <ul>
 * <li><b>Opcional y apagado por defecto</b> (usa CPU: una JVM de Kettle por guardado): {@code run_on_save} en
 * {@code <KETTLE_HOME>/.kettle/pentaho-live/pruebas.json}; se enciende en la ventana «Pruebas…».</li>
 * <li><b>Como se entera</b>: cada {@value #MS_VIGILANCIA} ms, en el hilo de UI, mira las transformaciones abiertas que
 * tienen pruebas: si su .ktr cambio en el disco y el documento no tiene cambios sin guardar, se acaba de guardar. Sin
 * ganchos de Spoon (los puntos de extension cargan en otro classloader).</li>
 * <li><b>Que corre</b>: solo las pruebas que toca el cambio ({@link PruebasArchivos#afectada}), comparando la firma de
 * la version guardada con la de la vez anterior que se vio guardada; sin firma anterior (la primera vez), todas las de
 * ese archivo. En segundo plano, contra el .ktr guardado.</li>
 * <li><b>Indicador</b>: el texto del boton IDC pasa a «IDC ✓ 12/12» o «IDC ✗ 1» (con el detalle en el tooltip) y,
 * si alguna falla, un globo debajo del boton que no roba el foco; un clic abre «Pruebas…».</li>
 * </ul>
 * Nada de esto puede tumbar Spoon: todo va en catch (Throwable). (UI)
 *
 * @dueño P2 (0.7.0)
 */
final class PruebasGuardado {

  static final long MS_VIGILANCIA = 2000;
  static final String ARCHIVO = "pruebas.json";

  /** Lo que se sabe de cada .ktr abierta (ruta canonica). */
  private static final Map<String, Estado> ESTADOS = new LinkedHashMap<String, Estado>();
  private static boolean instalado;
  private static ToolTip globo;

  private PruebasGuardado() {
  }

  private static final class Estado {
    /** El documento abierto que se vigila (otro objeto = se cerro y se volvio a abrir: otra linea de base). */
    java.lang.ref.WeakReference<AbstractMeta> doc;
    long modificado;
    Map<String, String> firma;
    PruebasProcesos.Corrida corrida;
    /** Se guardo otra vez mientras corrian las anteriores: correr al terminar (con todas). */
    boolean pendiente;
  }

  // ------------------------------------------------------------------ ajuste

  /** El ajuste {@code run_on_save} (false si no hay archivo o esta roto). */
  static boolean activo() {
    try {
      File f = new File( Ajustes.carpeta(), ARCHIVO );
      if ( !f.isFile() ) {
        return false;
      }
      JsonNode n = CorrectorJson.mapper().readTree( f );
      return n.path( "run_on_save" ).asBoolean( false );
    } catch ( Throwable t ) {
      return false;
    }
  }

  static void activar( boolean si ) {
    try {
      ObjectNode o = Tools.obj();
      o.put( "run_on_save", si );
      CorrectorJson.guardar( o, new File( Ajustes.carpeta(), ARCHIVO ) );
    } catch ( Throwable t ) {
      McpLifecycleListener.LOG.logError( "Pentaho Live: could not save the tests setting", t );
    }
  }

  // ------------------------------------------------------------------ vigilante

  /** Desde McpLifecycleListener.onStart (hilo de UI). Nunca lanza. */
  static void instalarDesde( Object handler ) {
    try {
      final Spoon sp = handler instanceof Spoon ? (Spoon) handler : UiThread.spoon();
      if ( sp == null || sp.getDisplay() == null || instalado ) {
        return;
      }
      instalado = true;
      final Display d = sp.getDisplay();
      d.timerExec( (int) MS_VIGILANCIA, new Runnable() {
        @Override
        public void run() {
          try {
            vigilar( sp );
          } catch ( Throwable t ) {
            McpLifecycleListener.LOG.logDebug( "Pentaho Live: tests on save: " + t );
          }
          if ( !d.isDisposed() ) {
            d.timerExec( (int) MS_VIGILANCIA, this );
          }
        }
      } );
    } catch ( Throwable t ) {
      McpLifecycleListener.LOG.logError( "Pentaho Live: could not start the tests-on-save watcher", t );
    }
  }

  /** Una vuelta del vigilante. (UI) */
  static void vigilar( Spoon sp ) throws Exception {
    if ( !activo() ) {
      ESTADOS.clear();
      return;
    }
    for ( AbstractMeta m : Docs.abiertos() ) {
      if ( !( m instanceof TransMeta ) ) {
        continue;
      }
      File ktr = PruebasTools.archivoDe( m );
      if ( ktr == null || !ktr.isFile() ) {
        continue;
      }
      String k = PruebasArchivos.canonica( ktr );
      long mod = ktr.lastModified();
      Estado e = ESTADOS.get( k );
      if ( e != null && ( e.doc == null || e.doc.get() != m ) ) {
        if ( e.corrida != null && !e.corrida.terminada() ) {
          continue;
        }
        ESTADOS.remove( k ); // otra pestaña con el mismo archivo: la linea de base es la suya
        e = null;
      }
      if ( e != null && e.modificado == mod ) {
        continue;
      }
      List<File> pruebas = PruebasArchivos.deTransformacion( ktr );
      if ( pruebas.isEmpty() ) {
        ESTADOS.remove( k );
        continue;
      }
      boolean guardado = !m.hasChanged();
      if ( e == null ) { // la primera vez: la linea de base, sin correr nada
        e = new Estado();
        e.doc = new java.lang.ref.WeakReference<AbstractMeta>( m );
        e.modificado = mod;
        e.firma = guardado ? PruebasArchivos.firma( (TransMeta) m ) : null;
        ESTADOS.put( k, e );
        continue;
      }
      if ( !guardado ) {
        continue; // cambio en el disco pero el documento no esta guardado: se mira al guardar
      }
      e.modificado = mod;
      Map<String, String> nueva = PruebasArchivos.firma( (TransMeta) m );
      Set<String> cambiados = e.firma == null ? null : PruebasArchivos.cambiados( e.firma, nueva );
      e.firma = nueva;
      if ( cambiados != null && cambiados.isEmpty() ) {
        continue; // solo posiciones, notas o fechas
      }
      List<File> afectadas = new ArrayList<File>();
      for ( File f : pruebas ) {
        try {
          if ( PruebasArchivos.afectada( PruebasMotor.cargar( f ), (TransMeta) m, cambiados ) ) {
            afectadas.add( f );
          }
        } catch ( Exception ex ) {
          afectadas.add( f ); // una prueba invalida: que se vea el error
        }
      }
      String nombre = Docs.nombre( m );
      if ( afectadas.isEmpty() ) {
        Rotulos r = PruebasDialogo.rotulos();
        indicador( sp, null, r.f( "i_none", nombre ) );
        continue;
      }
      if ( e.corrida != null && !e.corrida.terminada() ) {
        e.pendiente = true;
        continue;
      }
      correr( sp, k, nombre, afectadas );
    }
  }

  /** Corre las pruebas afectadas contra el .ktr guardado y pinta el resultado. */
  private static void correr( final Spoon sp, final String k, final String nombre, final List<File> pruebas ) {
    final Estado e = ESTADOS.get( k );
    final Rotulos r = PruebasDialogo.rotulos();
    indicador( sp, "…", r.f( "i_tip_running", nombre, pruebas.size() ) );
    List<PruebasProcesos.Tarea> tareas = new ArrayList<PruebasProcesos.Tarea>();
    for ( File f : pruebas ) {
      tareas.add( PruebasProcesos.Tarea.correr( f, null ) );
    }
    final PruebasProcesos.Corrida c = PruebasProcesos.iniciar( tareas, new PruebasProcesos.Oyente() {
      @Override
      public void empezada( PruebasProcesos.Tarea t ) {
        // el indicador ya dice que corre
      }

      @Override
      public void terminada( PruebasProcesos.Tarea t, ResultadoPrueba res ) {
        PruebasDialogo.avisarAbierto( res );
      }
    } );
    if ( e != null ) {
      e.corrida = c;
      e.pendiente = false;
    }
    Thread h = new Thread( () -> {
      try {
        c.fin.await();
      } catch ( InterruptedException ex ) {
        return;
      }
      final List<ResultadoPrueba> rs = c.resultados();
      Display d = sp.getDisplay();
      if ( d == null || d.isDisposed() ) {
        return;
      }
      d.asyncExec( () -> {
        try {
          mostrar( sp, nombre, rs, true );
          if ( e != null && e.pendiente ) {
            e.pendiente = false;
            e.firma = null; // otro guardado en el medio: todas
            e.modificado = -1;
          }
        } catch ( Throwable t ) {
          McpLifecycleListener.LOG.logDebug( "Pentaho Live: tests on save: " + t );
        }
      } );
    }, "pentaho-live-tests-on-save" );
    h.setDaemon( true );
    h.start();
  }

  // ------------------------------------------------------------------ indicador

  /** Pinta un resultado en el boton IDC y avisa con un globo si algo fallo (aviso = al guardar). (UI) */
  static void mostrar( Spoon sp, String nombre, List<ResultadoPrueba> rs, boolean aviso ) {
    Rotulos r = PruebasDialogo.rotulos();
    int pasaron = 0;
    List<String> rojas = new ArrayList<String>();
    for ( ResultadoPrueba x : rs ) {
      if ( x.paso() ) {
        pasaron++;
      } else if ( !ResultadoPrueba.CANCELADA.equals( x.estado ) ) {
        rojas.add( x.prueba );
      }
    }
    String hora = new SimpleDateFormat( "HH:mm", Locale.ROOT ).format( new Date() );
    String marca = PruebasTools.indicador( pasaron, rs.size() );
    if ( rojas.isEmpty() ) {
      indicador( sp, marca, r.f( "i_tip_ok", nombre, pasaron, rs.size(), hora ) );
      return;
    }
    indicador( sp, marca, r.f( "i_tip_fail", nombre, rojas.size(), rs.size(), hora ) );
    if ( aviso ) {
      globo( sp, r.s( "n_title" ), r.f( "n_text", rojas.size(), rs.size(), nombre, String.join( ", ", rojas ) ) );
    }
  }

  /** El boton IDC de la barra principal, o null. (UI) */
  static ToolItem boton( Spoon sp ) {
    try {
      ToolBar tb = IdcMenu.barraPrincipal( sp.getMainSpoonContainer() );
      if ( tb == null || tb.isDisposed() ) {
        return null;
      }
      for ( ToolItem it : tb.getItems() ) {
        if ( it.getData( IdcMenu.MARCA ) != null ) {
          return it;
        }
      }
    } catch ( Throwable ignored ) {
      // sin barra
    }
    return null;
  }

  /** «IDC ✓ 12/12» (marca null = «IDC» solo) y el tooltip. (UI) */
  static void indicador( Spoon sp, String marca, String tooltip ) {
    ToolItem it = sp == null ? null : boton( sp );
    if ( it == null ) {
      return;
    }
    poner( it, marca, tooltip );
  }

  /** Lo mismo sobre un ToolItem (las pruebas usan uno suyo). (UI) */
  static void poner( ToolItem it, String marca, String tooltip ) {
    it.setText( marca == null ? IdcMenu.TEXTO_BOTON : IdcMenu.TEXTO_BOTON + " " + marca );
    it.setToolTipText( tooltip == null ? IdcMenu.rotulos().s( "menu_tooltip" ) : tooltip );
    try {
      ToolBar tb = it.getParent();
      tb.pack();
      if ( tb.getParent() != null ) {
        tb.getParent().layout( true, true );
      }
    } catch ( Throwable ignored ) {
      // solo el acomodo
    }
  }

  /** Un globo debajo del boton IDC que no roba el foco; un clic abre «Pruebas…». (UI) */
  static void globo( final Spoon sp, String titulo, String texto ) {
    try {
      ToolItem it = boton( sp );
      Shell sh = sp.getShell();
      if ( it == null || sh == null || sh.isDisposed() ) {
        return;
      }
      if ( globo != null && !globo.isDisposed() ) {
        globo.dispose();
      }
      ToolTip t = new ToolTip( sh, SWT.BALLOON | SWT.ICON_WARNING );
      t.setText( titulo );
      t.setMessage( texto );
      Rectangle b = it.getBounds();
      Point p = it.getParent().toDisplay( b.x + b.width / 2, b.y + b.height );
      t.setLocation( p );
      t.setAutoHide( true );
      t.addListener( SWT.Selection, e -> {
        try {
          AbstractMeta m = Docs.activo();
          if ( m instanceof TransMeta ) {
            PruebasDialogo.abrir( sp.getShell(), (TransMeta) m, PruebasDialogo.rotulos() );
          }
        } catch ( Throwable ignored ) {
          // sin documento
        }
      } );
      t.setVisible( true );
      globo = t;
    } catch ( Throwable t ) {
      McpLifecycleListener.LOG.logDebug( "Pentaho Live: tests notification: " + t );
    }
  }

  /** Para que una prueba nueva o una prueba editada cuenten (la ventana avisa). */
  static void olvidar( File ktr ) {
    if ( ktr != null ) {
      ESTADOS.remove( PruebasArchivos.canonica( ktr ) );
    }
  }
}
