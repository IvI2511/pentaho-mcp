package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.pentaho.di.core.NotePadMeta;
import org.pentaho.di.core.gui.Point;
import org.pentaho.di.core.undo.TransAction;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.trans.TransMeta;

/**
 * Los retoques de 0.7.1 del auto-layout ({@link LienzoLayout}): en un job, ordenar se deshace con UN Ctrl+Z aunque se
 * muevan entradas y notas; el manejador de errores compartido (Grano de Oro, «Registrar el error» con ocho entradas)
 * va debajo de la fila sin que un hop pase por encima de un nombre; las fuentes de un abanico (muestra «Data
 * Validator», 22 fuentes) se reparten en dos columnas y el dibujo baja; el peine de «Value Mapper» queda sin cruces.
 * Sin Display: medidor aproximado. Imprime las métricas antes y después (las de LienzoMetricas).
 */
public class PruebaLienzoPulido {

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  private static File repo() {
    return new File( EntornoKettle.recursos(), "../../../.." ).getAbsoluteFile();
  }

  private static File granoDeOro() {
    return new File( repo(), "ejemplo/construido-en-vivo/job_carga_dw.kjb" );
  }

  /** Alto de la caja de iconos, nombres y notas. */
  static int alto( LienzoLayout.Grafo g ) {
    int y0 = Integer.MAX_VALUE;
    int y1 = Integer.MIN_VALUE;
    for ( int i = 0; i < g.n(); i++ ) {
      Point p = g.actuales.get( i );
      int[] c = LienzoLayout.caja( p.x, p.y, g.icono, g.rotulos.get( i ) );
      y0 = Math.min( y0, c[ 1 ] );
      y1 = Math.max( y1, c[ 3 ] );
    }
    return y1 - y0;
  }

  private static TransLayout.Movimientos ordenarYMostrar( String nombre, org.pentaho.di.base.AbstractMeta m ) {
    int altoAntes = alto( LienzoModelo.de( m ).grafo );
    TransLayout.Movimientos mov = TransLayout.ordenar( m );
    System.out.println( "  " + nombre + ": alto " + altoAntes + " -> " + alto( LienzoModelo.de( m ).grafo )
      + "\n    antes:   " + mov.medidaAntes + "\n    despues: " + mov.medidaDespues );
    return mov;
  }

  // ---------------------------------------------------------------- un solo Ctrl+Z en un job

  /** Lo que hace SpoonJobDelegate.undoJobAction / redoJobAction con una acción de cambio de entrada (PDI 9.4). */
  private static void aplicarCambio( JobMeta jm, TransAction ta, boolean deshacer ) {
    igual( TransAction.TYPE_ACTION_CHANGE_JOB_ENTRY, ta.getType(), "una accion de cambio de entrada" );
    Object[] objs = deshacer ? ta.getPrevious() : ta.getCurrent();
    int[] idx = ta.getCurrentIndex();
    for ( int i = 0; i < objs.length; i++ ) {
      jm.getJobEntry( idx[ i ] ).replaceMeta( (JobEntryCopy) ( (JobEntryCopy) objs[ i ] ).clone() );
    }
  }

  private static List<Point> posiciones( JobMeta jm ) {
    List<Point> r = new ArrayList<Point>();
    for ( JobEntryCopy c : jm.getJobCopies() ) {
      r.add( new Point( c.getLocation().x, c.getLocation().y ) );
    }
    for ( int i = 0; i < jm.nrNotes(); i++ ) {
      r.add( new Point( jm.getNote( i ).getLocation().x, jm.getNote( i ).getLocation().y ) );
    }
    return r;
  }

  private static String texto( List<Point> ps ) {
    StringBuilder sb = new StringBuilder();
    for ( Point p : ps ) {
      sb.append( p.x ).append( ',' ).append( p.y ).append( ' ' );
    }
    return sb.toString();
  }

  public void pruebaJobUnSoloCtrlZConEntradasYNotas() throws Exception {
    JobMeta jm = new JobMeta( granoDeOro().getPath(), null );
    // la nota encima de las entradas: ordenar mueve entradas Y notas
    jm.getNote( 0 ).setLocation( 250, 110 );
    List<Point> antes = posiciones( jm );
    nulo( jm.viewThisUndo(), "sin historial" );
    TransLayout.Movimientos mov = OrdenarLienzo.ordenar( null, jm );
    cierto( !mov.objetos.isEmpty() && !mov.notas.isEmpty(), "movio entradas y notas" );
    List<Point> ordenado = posiciones( jm );
    TransAction ta = jm.viewThisUndo();
    cierto( ta != null, "registro el undo" );
    jm.previousUndo();
    nulo( jm.viewThisUndo(), "UNA sola accion de undo" );
    aplicarCambio( jm, ta, true );
    igual( texto( antes ), texto( posiciones( jm ) ), "un Ctrl+Z deja entradas y notas como estaban" );
    TransAction re = jm.nextUndo();
    cierto( re == ta, "Ctrl+Y rehace la misma accion" );
    aplicarCambio( jm, re, false );
    igual( texto( ordenado ), texto( posiciones( jm ) ), "un Ctrl+Y vuelve a ordenar entradas y notas" );
    // la entrada que carga la accion sigue siendo la misma entrada (mismo tipo y nombre)
    igual( "START", jm.getJobEntry( 0 ).getName(), "la primera entrada intacta" );
    cierto( jm.getJobEntry( 0 ).getClass() == JobEntryCopy.class, "en el job queda un JobEntryCopy comun" );
  }

  public void pruebaTransSigueEnDosAccionesEncadenadas() throws Exception {
    TransMeta tm = new TransMeta();
    org.pentaho.di.trans.step.StepMeta a = new org.pentaho.di.trans.step.StepMeta( "Dummy", "Uno",
      new org.pentaho.di.trans.steps.dummytrans.DummyTransMeta() );
    a.setLocation( 100, 100 );
    tm.addStep( a );
    org.pentaho.di.trans.step.StepMeta b = new org.pentaho.di.trans.step.StepMeta( "Dummy", "Dos",
      new org.pentaho.di.trans.steps.dummytrans.DummyTransMeta() );
    b.setLocation( 400, 300 );
    tm.addStep( b );
    tm.addTransHop( new org.pentaho.di.trans.TransHopMeta( a, b ) );
    tm.addNote( new NotePadMeta( "Nota encima", 90, 90, 200, 60 ) );
    OrdenarLienzo.ordenar( null, tm );
    igual( TransAction.TYPE_ACTION_POSITION_NOTE, tm.viewThisUndo().getType(), "en una transformacion, como antes" );
    cierto( tm.viewThisUndo().getNextAlso(), "encadenada (un Ctrl+Z)" );
  }

  // ---------------------------------------------------------------- manejador de errores compartido

  public void pruebaManejadorDeErroresDebajoSinPisarNombres() throws Exception {
    JobMeta jm = new JobMeta( granoDeOro().getPath(), null );
    TransLayout.Movimientos mov = ordenarYMostrar( "Grano de Oro (job_carga_dw)", jm );
    LienzoMetricas.Cuenta k = mov.medidaDespues;
    igual( 0, k.hopsPorRotulos, "ningun hop por encima de un nombre" );
    igual( 0, k.hopsPorPasos, "ningun hop por encima de una entrada" );
    igual( 0, k.cruces, "sin cruces" );
    igual( 0, k.solapes(), "nada encimado" );
    int fila = jm.findJobEntry( "Cargar dim_tienda", 0, true ).getLocation().y;
    JobEntryCopy reg = jm.findJobEntry( "Registrar el error", 0, true );
    JobEntryCopy abortar = jm.findJobEntry( "Abortar la carga", 0, true );
    cierto( reg.getLocation().y > fila, "el manejador va DEBAJO de la fila (" + reg.getLocation().y + " > " + fila
      + ")" );
    igual( reg.getLocation().y, abortar.getLocation().y, "lo que cuelga de el, en su fila" );
    cierto( abortar.getLocation().x > reg.getLocation().x, "y a su derecha" );
    int x0 = jm.findJobEntry( "Comprobar ficheros de entrada", 0, true ).getLocation().x;
    int x1 = jm.findJobEntry( "Calcular KPI", 0, true ).getLocation().x;
    cierto( reg.getLocation().x > x0 && reg.getLocation().x < x1, "en el medio de las entradas que lo alimentan" );
    cierto( TransLayout.ordenar( jm ).vacio(), "ordenar otra vez no mueve nada" );
  }

  // ---------------------------------------------------------------- abanicos y peine

  private static File muestra( String nombre ) {
    File f = new File( EntornoKettle.pdi(), "samples/transformations/" + nombre );
    if ( !f.isFile() ) {
      Verificador.saltear( "sin la muestra " + f );
    }
    return f;
  }

  public void pruebaAbanicoDeFuentesEnDosColumnas() throws Exception {
    TransMeta tm = new TransMeta( muestra( "Data Validator - all usecases with error handling.ktr" ).getPath() );
    // el layout sin el retoque: una fuente por carril
    TransMeta solo = new TransMeta( muestra( "Data Validator - all usecases with error handling.ktr" ).getPath() );
    TransLayout.Movimientos mov = ordenarYMostrar( "Data Validator - all usecases", tm );
    LienzoMetricas.Cuenta k = mov.medidaDespues;
    igual( 0, LienzoLayout.graves( k ), "nada pasa por encima de un step o un nombre" );
    igual( 0, k.solapes(), "nada encimado" );
    cierto( k.cruces <= 15, "no mas cruces que sin el retoque (15, el minimo con los 6 validadores en una columna): "
      + k.cruces );
    java.util.Set<Integer> xs = new java.util.TreeSet<Integer>();
    for ( String f : new String[] { "A1", "B1", "C1", "A7", "B7", "C7", "D7" } ) {
      xs.add( tm.findStep( f ).getLocation().x );
    }
    igual( 2, xs.size(), "las fuentes en dos columnas" );
    int altoNuevo = alto( LienzoModelo.de( tm ).grafo );
    // 22 fuentes, un carril de 112 px cada una: mas de 2300 px
    cierto( altoNuevo < 22 * 112 * 3 / 4, "el dibujo baja al menos un cuarto: " + altoNuevo );
    cierto( TransLayout.ordenar( tm ).vacio(), "ordenar otra vez no mueve nada" );
    cierto( solo.nrSteps() == tm.nrSteps(), "mismo documento" );
  }

  public void pruebaPeineSinCruces() throws Exception {
    TransMeta tm = new TransMeta( muestra( "Value Mapper - Same and new field.ktr" ).getPath() );
    TransLayout.Movimientos mov = ordenarYMostrar( "Value Mapper - Same and new field", tm );
    igual( 15, mov.medidaAntes.cruces, "seis fuentes a dos destinos: 15 cruces en columna" );
    igual( 0, mov.medidaDespues.cruces, "en fila, ninguno" );
    igual( 0, LienzoLayout.graves( mov.medidaDespues ), "sin hops por encima de nada" );
    int ySame = tm.findStep( "Same" ).getLocation().y;
    int yNew = tm.findStep( "New" ).getLocation().y;
    int yFila = tm.findStep( "One" ).getLocation().y;
    cierto( ySame < yFila && yFila < yNew, "Same arriba, las fuentes en el medio, New abajo" );
    cierto( TransLayout.ordenar( tm ).vacio(), "ordenar otra vez no mueve nada" );
  }

  /** Un abanico chico armado a mano: 4 fuentes a un step y 4 a otro. */
  public void pruebaAbanicoChico() throws Exception {
    TransMeta tm = new TransMeta();
    int y = 0;
    for ( String d : new String[] { "Union A", "Union B" } ) {
      org.pentaho.di.trans.step.StepMeta t = new org.pentaho.di.trans.step.StepMeta( "Dummy", d,
        new org.pentaho.di.trans.steps.dummytrans.DummyTransMeta() );
      t.setLocation( 300, y + 150 );
      tm.addStep( t );
      for ( int i = 0; i < 4; i++ ) {
        org.pentaho.di.trans.step.StepMeta s = new org.pentaho.di.trans.step.StepMeta( "Dummy", d + " fuente " + i,
          new org.pentaho.di.trans.steps.dummytrans.DummyTransMeta() );
        s.setLocation( 100, y );
        y += 100;
        tm.addStep( s );
        tm.addTransHop( new org.pentaho.di.trans.TransHopMeta( s, t ) );
      }
    }
    TransLayout.Movimientos mov = ordenarYMostrar( "abanico chico (2 x 4 fuentes)", tm );
    igual( 0, LienzoLayout.graves( mov.medidaDespues ), "sin hops por encima de nada" );
    igual( 0, mov.medidaDespues.cruces, "sin cruces" );
    cierto( alto( LienzoModelo.de( tm ).grafo ) < 8 * 112 - 112, "mas bajo que 8 carriles" );
  }
}
