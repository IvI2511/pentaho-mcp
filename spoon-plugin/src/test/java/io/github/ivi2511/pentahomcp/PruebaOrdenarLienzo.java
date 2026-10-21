package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.pentaho.di.core.gui.Point;
import org.pentaho.di.core.undo.TransAction;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.steps.dummytrans.DummyTransMeta;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * «Ordenar lienzo (Pentaho Live)»: la accion compartida con arrange_canvas (un solo Ctrl+Z, idempotente) y el boton y
 * el item de menu que se agregan a la barra y al menu contextual del lienzo. La parte SWT corre en una Shell fuera de
 * la pantalla que nunca se abre (no aparece ninguna ventana ni roba el foco); los clics se simulan con
 * notifyListeners y Action.run.
 */
public class PruebaOrdenarLienzo {

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  /** Tres steps en linea, dibujados en desorden: a la derecha, arriba y amontonados. */
  private static TransMeta desordenada() {
    TransMeta tm = new TransMeta();
    StepMeta a = new StepMeta( "Dummy", "Leer", new DummyTransMeta() );
    StepMeta b = new StepMeta( "Dummy", "Filtrar", new DummyTransMeta() );
    StepMeta c = new StepMeta( "Dummy", "Escribir", new DummyTransMeta() );
    a.setLocation( 600, 40 );
    b.setLocation( 30, 400 );
    c.setLocation( 35, 410 );
    tm.addStep( a );
    tm.addStep( b );
    tm.addStep( c );
    tm.addTransHop( new TransHopMeta( a, b ) );
    tm.addTransHop( new TransHopMeta( b, c ) );
    return tm;
  }

  private static Point pos( TransMeta tm, String paso ) {
    return tm.findStep( paso ).getLocation();
  }

  public void pruebaAccionUnSoloUndoEIdempotente() throws Exception {
    TransMeta tm = desordenada();
    nulo( tm.viewThisUndo(), "sin historial antes" );
    TransLayout.Movimientos mov = OrdenarLienzo.ordenar( null, tm );
    igual( 3, mov.objetos.size(), "se movieron los tres steps" );
    igual( 3, mov.columnas, "una columna por nivel" );
    cierto( pos( tm, "Leer" ).x < pos( tm, "Filtrar" ).x && pos( tm, "Filtrar" ).x < pos( tm, "Escribir" ).x,
      "de izquierda a derecha siguiendo los hops" );
    igual( pos( tm, "Leer" ).y, pos( tm, "Escribir" ).y, "la linea principal queda recta" );

    TransAction ta = tm.viewThisUndo();
    noNulo( ta, "una accion de undo" );
    igual( TransAction.TYPE_ACTION_POSITION_STEP, ta.getType(), "accion de posicion" );
    tm.previousUndo();
    nulo( tm.viewThisUndo(), "una sola accion: un solo Ctrl+Z" );
    tm.nextUndo();

    TransLayout.Movimientos otra = OrdenarLienzo.ordenar( null, tm );
    igual( 0, otra.objetos.size(), "ya ordenada: no mueve nada" );
    cierto( tm.viewThisUndo() == ta, "sin movimientos no agrega undo" );
  }

  public void pruebaTextosEnTresIdiomas() {
    igual( "Arrange canvas (Pentaho Live)", Rotulos.de( OrdenarLienzo.AREA, "en" ).s( "arrange_action" ), "en" );
    igual( "Ordenar lienzo (Pentaho Live)", Rotulos.de( OrdenarLienzo.AREA, "es" ).s( "arrange_action" ), "es" );
    igual( "Organizar tela (Pentaho Live)", Rotulos.de( OrdenarLienzo.AREA, "pt" ).s( "arrange_action" ), "pt" );
    for ( String i : new String[] { "en", "es", "pt" } ) {
      Rotulos r = Rotulos.de( OrdenarLienzo.AREA, i );
      cierto( r.propio( "arrange_tooltip" ) != null && r.propio( "arrange_tooltip" ).contains( "Ctrl+Z" ),
        "tooltip propio en " + i );
    }
    noNulo( OrdenarLienzo.class.getClassLoader().getResource( OrdenarLienzo.RUTA_ICONO ), "el icono SVG en el jar" );
  }

  public void pruebaBotonYMenuEnElLienzo() throws Exception {
    Display d = new Display();
    try {
      // como la barra de TransGraph: botones, el separador del XUL y al final el zoom (un SEPARATOR con un Combo)
      Shell sh = new Shell( d, SWT.NO_TRIM | SWT.TOOL );
      sh.setBounds( -20000, -20000, 600, 80 ); // nunca se abre: fuera de la pantalla igual, por las dudas
      ToolBar tb = new ToolBar( sh, SWT.FLAT );
      new ToolItem( tb, SWT.PUSH ).setText( "run" );
      new ToolItem( tb, SWT.PUSH ).setText( "preview" );
      new ToolItem( tb, SWT.SEPARATOR );
      ToolItem zoom = new ToolItem( tb, SWT.SEPARATOR );
      zoom.setControl( new Combo( tb, SWT.DROP_DOWN ) );
      igual( 3, OrdenarLienzo.posicion( tb ), "el boton va antes del zoom" );
      MenuManager mm = new MenuManager();

      final TransMeta tm = desordenada();
      Rotulos r = Rotulos.de( OrdenarLienzo.AREA, "es" );
      Image img = OrdenarLienzo.imagen( d );
      cierto( OrdenarLienzo.decorar( tb, mm, () -> tm, m -> {
        try {
          OrdenarLienzo.ordenar( null, m );
        } catch ( ToolError e ) {
          throw new IllegalStateException( e );
        }
      }, r, img ), "decora la primera vez" );
      falso( OrdenarLienzo.decorar( tb, mm, () -> tm, m -> { }, r, img ), "la segunda vez no agrega nada" );

      ToolItem[] items = tb.getItems();
      igual( 6, items.length, "boton + su separador" );
      ToolItem boton = items[ 3 ];
      igual( Boolean.TRUE, boton.getData( OrdenarLienzo.MARCA ), "el boton nuevo, antes del zoom" );
      falso( ( boton.getStyle() & SWT.SEPARATOR ) != 0, "es un boton" );
      cierto( ( items[ 4 ].getStyle() & SWT.SEPARATOR ) != 0, "separador entre el boton y el zoom" );
      cierto( items[ 5 ] == zoom, "el zoom sigue al final" );
      cierto( boton.getImage() != null || "Ordenar lienzo (Pentaho Live)".equals( boton.getText() ),
        "icono o, si no carga, el texto" );
      cierto( boton.getToolTipText().startsWith( "Ordenar lienzo (Pentaho Live)" ), "tooltip con el nombre" );

      boton.notifyListeners( SWT.Selection, new Event() );
      cierto( pos( tm, "Leer" ).x < pos( tm, "Filtrar" ).x && pos( tm, "Filtrar" ).x < pos( tm, "Escribir" ).x,
        "el boton ordena el lienzo" );

      IContributionItem it = mm.find( OrdenarLienzo.ID_ACCION );
      noNulo( it, "item en el menu contextual" );
      ActionContributionItem aci = (ActionContributionItem) it;
      igual( "Ordenar lienzo (Pentaho Live)", aci.getAction().getText(), "texto del item" );
      tm.findStep( "Escribir" ).setLocation( 10, 600 );
      aci.getAction().run();
      igual( pos( tm, "Leer" ).y, pos( tm, "Escribir" ).y, "el item del menu ordena el lienzo" );
      sh.dispose();
    } finally {
      d.dispose();
    }
  }
}
