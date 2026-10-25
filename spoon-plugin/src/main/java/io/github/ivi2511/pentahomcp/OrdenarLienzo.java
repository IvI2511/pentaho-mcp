package io.github.ivi2511.pentahomcp;

import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.ui.spoon.Spoon;
import org.pentaho.di.ui.spoon.TabMapEntry;
import org.pentaho.di.ui.spoon.job.JobGraph;
import org.pentaho.di.ui.spoon.trans.TransGraph;
import org.pentaho.di.ui.util.SwtSvgImageUtil;
import org.pentaho.ui.xul.XulComponent;
import org.pentaho.ui.xul.XulDomContainer;
import org.pentaho.ui.xul.containers.XulToolbar;
import org.pentaho.xul.swt.tab.TabItem;
import org.pentaho.xul.swt.tab.TabListener;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * «Ordenar lienzo (Pentaho Live)»: el auto-layout de arrange_canvas ({@link TransLayout}) a mano, con un boton en la
 * barra del lienzo de transformaciones y de jobs y un item en el menu contextual del fondo del lienzo.
 * <p>
 * <b>Mismo camino que la herramienta MCP:</b> {@link #ordenar(Spoon, AbstractMeta)} es lo que llama arrange_canvas
 * (TransTools) y lo que llaman el boton y el menu: TransLayout.ordenar (steps y notas), un solo grupo de undo (un
 * Ctrl+Z en transformaciones) y Docs.touched.
 * <p>
 * <b>Por que no un SpoonPlugin con categoria "trans-graph"/"job-graph":</b> en PDI 9.4 TransGraph y JobGraph llaman a
 * {@code SpoonPluginManager.applyPluginsForContainer} con el campo {@code xulDomContainer} ANTES de asignarlo
 * (leido del bytecode: getfield en 262/232, setXulDomContainer en 271/241), asi que el plugin recibe null. Por eso se
 * decoran los lienzos ya creados: un {@link TabListener} en las pestanas de Spoon (TabSet.setSelected avisa al abrir
 * o cambiar de pestana) recorre las pestanas y agrega el boton y el item a los TransGraph/JobGraph que todavia no los
 * tienen. La barra es la ToolBar SWT de {@code getToolbar()} (id XUL nav-toolbar); el menu es el MenuManager de JFace
 * del menupopup trans-graph-background / job-graph-background (ConstUI.displayMenu lo vuelve a armar con updateAll
 * en cada clic derecho, asi que la accion agregada queda).
 * <p>
 * Nada de esto puede tumbar Spoon: todo va en catch (Throwable) y solo deja una linea en el log. (UI)
 */
final class OrdenarLienzo {

  /** Marca en la ToolBar SWT ya decorada (getData). */
  static final String MARCA = "pentaholive.ordenarLienzo";
  /** Id de la accion en el MenuManager del menu contextual. */
  static final String ID_ACCION = "pentaholive-arrange-canvas";
  /** Textos en informes/textos/lienzo_*.properties. */
  static final String AREA = "lienzo";
  static final String RUTA_ICONO = "io/github/ivi2511/pentahomcp/lienzo/ordenar.svg";

  private static Image icono;

  private OrdenarLienzo() {
  }

  // ---------------------------------------------------------------- la accion (compartida con arrange_canvas)

  /**
   * Ordena el lienzo del documento: TransLayout.ordenar y, si algo se movio, UNA accion de undo de posicion y
   * Docs.touched. Con sp null (pruebas) registra el undo directo en el meta y no redibuja. (UI)
   */
  static TransLayout.Movimientos ordenar( Spoon sp, AbstractMeta m ) throws ToolError {
    return ordenar( sp, m, LienzoLayout.Opciones.defecto() );
  }

  /**
   * Lo mismo con opciones (modo legible/compacto, notas cerca/al margen). Los steps y las notas que se movieron van al
   * mismo grupo de undo: en una transformacion, un Ctrl+Z; en un job Spoon no encadena acciones y las notas son un
   * Ctrl+Z aparte (ver UndoGrupo). (UI)
   */
  static TransLayout.Movimientos ordenar( Spoon sp, AbstractMeta m, LienzoLayout.Opciones o ) throws ToolError {
    if ( m instanceof org.pentaho.di.trans.TransMeta
      && !LeccionNotas.reconocer( (org.pentaho.di.trans.TransMeta) m ).isEmpty() ) {
      // documento con notas de leccion (modo clase): la regla de clase mueve steps y notas, mismo Ctrl+Z
      LeccionNotas.MedidorCerrable med = LeccionNotas.medidor( LeccionNotas.tamFuente( (org.pentaho.di.trans.TransMeta) m ) );
      try {
        UndoGrupo u = new UndoGrupo( sp, m );
        LeccionNotas.Resultado r = LeccionNotas.reacomodar( (org.pentaho.di.trans.TransMeta) m, med, u, -1 );
        if ( sp != null && u.acciones() > 0 ) {
          Docs.touched( m );
        }
        return r.movimientos;
      } finally {
        med.cerrar();
      }
    }
    TransLayout.Movimientos mov = TransLayout.ordenar( m, o );
    if ( !mov.vacio() ) {
      mov.registrar( new UndoGrupo( sp, m ) );
      if ( sp != null ) {
        Docs.touched( m );
      }
    }
    return mov;
  }

  /** El clic del boton o del menu: ordena y nunca deja escapar una excepcion al bucle de SWT. (UI) */
  static void ejecutar( Spoon sp, AbstractMeta m ) {
    if ( m == null ) {
      return;
    }
    try {
      ordenar( sp, m );
    } catch ( Throwable t ) {
      McpLifecycleListener.LOG.logError( "Pentaho Live: could not arrange the canvas", t );
    }
  }

  // ---------------------------------------------------------------- registro en Spoon

  /**
   * Desde McpLifecycleListener.onStart (hilo de UI, con la ventana de Spoon ya creada): el handler es Spoon. Recibe
   * Object para que el listener no nombre clases de kettle-ui-swt. Nunca lanza.
   */
  static void instalarDesde( Object handler ) {
    try {
      instalar( handler instanceof Spoon ? (Spoon) handler : UiThread.spoon() );
    } catch ( Throwable t ) {
      McpLifecycleListener.LOG.logError( "Pentaho Live: could not add the Arrange canvas button", t );
    }
  }

  static void instalar( final Spoon sp ) {
    try {
      if ( sp == null || sp.tabfolder == null ) {
        McpLifecycleListener.LOG.logBasic( "Pentaho Live: no Spoon tabs, the Arrange canvas button is not added" );
        return;
      }
      sp.tabfolder.addListener( new TabListener() {
        @Override
        public void tabSelected( TabItem item ) {
          // despues del evento: al abrir un archivo la pestana se selecciona antes de quedar en el mapa de Spoon
          try {
            Display d = sp.getDisplay();
            if ( d != null && !d.isDisposed() ) {
              d.asyncExec( () -> decorarTodos( sp ) );
            }
          } catch ( Throwable ignorada ) {
            // nunca molestar al cambio de pestana
          }
        }

        @Override
        public void tabDeselected( TabItem item ) {
          // nada
        }

        @Override
        public boolean tabClose( TabItem item ) {
          return true; // TabSet hace un AND de las respuestas: true no impide cerrar
        }
      } );
      decorarTodos( sp );
    } catch ( Throwable t ) {
      McpLifecycleListener.LOG.logError( "Pentaho Live: could not add the Arrange canvas button", t );
    }
  }

  /** Agrega el boton y el item de menu a cada lienzo abierto que no los tenga. Nunca lanza. (UI) */
  static void decorarTodos( final Spoon sp ) {
    try {
      if ( sp == null || sp.delegates == null || sp.delegates.tabs == null ) {
        return;
      }
      Rotulos r = Rotulos.de( AREA, ReportsPerspective.idiomaUi() );
      Consumer<AbstractMeta> accion = m -> ejecutar( sp, m );
      for ( TabMapEntry e : sp.delegates.tabs.getTabs() ) {
        Object o = e.getObject();
        try {
          if ( o instanceof TransGraph ) {
            final TransGraph g = (TransGraph) o;
            decorar( barra( g.getToolbar() ), menu( g.getXulDomContainer(), "trans-graph-background" ),
              () -> g.getTransMeta(), accion, r, imagen( sp.getDisplay() ) );
          } else if ( o instanceof JobGraph ) {
            final JobGraph g = (JobGraph) o;
            decorar( barra( g.getToolbar() ), menu( g.getXulDomContainer(), "job-graph-background" ),
              () -> g.getJobMeta(), accion, r, imagen( sp.getDisplay() ) );
          }
        } catch ( Throwable t ) {
          McpLifecycleListener.LOG.logDebug( "Pentaho Live: canvas tab not decorated: " + t );
        }
      }
    } catch ( Throwable t ) {
      McpLifecycleListener.LOG.logDebug( "Pentaho Live: could not decorate the canvas tabs: " + t );
    }
  }

  /**
   * Agrega el boton a la barra (antes del zoom) y la accion al menu contextual, cada uno una sola vez. La barra o el
   * menu pueden ser null (se saltea esa parte). Devuelve true si agrego algo. (UI)
   */
  static boolean decorar( ToolBar tb, MenuManager mm, final Supplier<AbstractMeta> meta,
    final Consumer<AbstractMeta> accion, Rotulos r, Image img ) {
    boolean algo = false;
    final String texto = r.s( "arrange_action" );
    if ( tb != null && !tb.isDisposed() && tb.getData( MARCA ) == null ) {
      tb.setData( MARCA, Boolean.TRUE );
      int pos = posicion( tb );
      ToolItem b = new ToolItem( tb, SWT.PUSH, pos );
      if ( img != null && !img.isDisposed() ) {
        b.setImage( img );
      } else {
        b.setText( texto );
      }
      b.setToolTipText( texto + "\n" + r.s( "arrange_tooltip" ) );
      b.setData( MARCA, Boolean.TRUE );
      b.addListener( SWT.Selection, e -> accion.accept( meta.get() ) );
      new ToolItem( tb, SWT.SEPARATOR, pos + 1 );
      try {
        tb.pack();
        if ( tb.getParent() != null ) {
          tb.getParent().layout( true, true );
        }
      } catch ( Throwable ignorada ) {
        // solo el acomodo de la barra
      }
      algo = true;
    }
    if ( mm != null && mm.find( ID_ACCION ) == null ) {
      Action a = new Action( texto ) {
        @Override
        public void run() {
          accion.accept( meta.get() );
        }
      };
      a.setId( ID_ACCION );
      a.setToolTipText( r.s( "arrange_tooltip" ) );
      mm.add( new Separator() );
      mm.add( a );
      algo = true;
    }
    return algo;
  }

  /**
   * Donde va el boton: justo antes del zoom (el ToolItem SEPARATOR con el Combo que TransGraph/JobGraph agregan al
   * final), asi queda junto a los botones de la barra. Sin zoom, al final.
   */
  static int posicion( ToolBar tb ) {
    ToolItem[] items = tb.getItems();
    for ( int i = 0; i < items.length; i++ ) {
      if ( items[ i ].getControl() instanceof Combo ) {
        return i;
      }
    }
    return items.length;
  }

  private static ToolBar barra( XulToolbar t ) {
    Object o = t == null ? null : t.getManagedObject();
    return o instanceof ToolBar ? (ToolBar) o : null;
  }

  private static MenuManager menu( XulDomContainer c, String id ) {
    if ( c == null || c.getDocumentRoot() == null ) {
      return null;
    }
    XulComponent p = c.getDocumentRoot().getElementById( id );
    Object o = p == null ? null : p.getManagedObject();
    return o instanceof MenuManager ? (MenuManager) o : null;
  }

  /** El icono SVG de 16 px (una sola Image por Display, vive con Spoon); null si no se pudo cargar. */
  static synchronized Image imagen( Display d ) {
    if ( icono != null && !icono.isDisposed() && icono.getDevice() == d ) {
      return icono;
    }
    try {
      icono = SwtSvgImageUtil.getImage( d, OrdenarLienzo.class.getClassLoader(), RUTA_ICONO, 16, 16 );
    } catch ( Throwable t ) {
      icono = null;
    }
    return icono;
  }
}
