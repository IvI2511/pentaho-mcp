package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.falla;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.NotePadMeta;
import org.pentaho.di.core.gui.Point;
import org.pentaho.di.core.undo.TransAction;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.steps.dummytrans.DummyTransMeta;

/**
 * El auto-layout por capas de arrange_canvas / «Ordenar lienzo» ({@link LienzoLayout}): metricas, carriles de lookup y
 * de error, notas que nunca quedan tapadas, un solo Ctrl+Z para steps y notas, idempotencia, y sobre los ejemplos del
 * repo (skill, patrones, construido-en-vivo) y las muestras de PDI: cero hops por encima de steps, cero notas tapadas
 * y menos cruces que el layout de 0.6.2. Sin Display: medidor aproximado.
 */
public class PruebaLienzoLayout {

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  // ---------------------------------------------------------------- metricas

  public void pruebaMetricasCuentanCrucesYHopsPorEncima() {
    LienzoLayout.Grafo g = new LienzoLayout.Grafo();
    int a = g.nodo( new Point( 0, 0 ), 40, 15 );
    int b = g.nodo( new Point( 200, 200 ), 40, 15 );
    int c = g.nodo( new Point( 0, 200 ), 40, 15 );
    int d = g.nodo( new Point( 200, 0 ), 40, 15 );
    int e = g.nodo( new Point( 100, 100 ), 40, 15 ); // justo en el medio de las dos diagonales
    g.arista( a, b, LienzoLayout.NORMAL );
    g.arista( c, d, LienzoLayout.NORMAL );
    g.nota( 380, 0, 100, 60 );
    g.arista( d, g.nodo( new Point( 500, 0 ), 40, 15 ), LienzoLayout.NORMAL ); // pasa por la nota
    LienzoMetricas.Cuenta k = LienzoMetricas.medir( g, null, null );
    igual( 1, k.cruces, "las dos diagonales se cruzan" );
    igual( 2, k.hopsPorPasos, "las dos pasan por el icono del medio" );
    igual( 1, k.hopsPorNotas, "un hop por encima de la nota" );
    igual( 0, k.pasosEncimados, "sin iconos encimados" );
    cierto( e >= 0, "nodo del medio" );
  }

  // ---------------------------------------------------------------- casos chicos

  /** Paso con nombre, en (x, y). */
  private static StepMeta paso( TransMeta tm, String nombre, int x, int y ) {
    StepMeta s = new StepMeta( "Dummy", nombre, new DummyTransMeta() );
    s.setLocation( x, y );
    tm.addStep( s );
    return s;
  }

  private static void hop( TransMeta tm, StepMeta a, StepMeta b ) {
    tm.addTransHop( new TransHopMeta( a, b ) );
  }

  private static Point pos( TransMeta tm, String paso ) {
    return tm.findStep( paso ).getLocation();
  }

  public void pruebaErrorAbajoYLineaPrincipalRecta() throws Exception {
    TransMeta tm = new TransMeta();
    StepMeta leer = paso( tm, "Leer", 500, 500 );
    org.pentaho.di.trans.steps.tableoutput.TableOutputMeta to = new org.pentaho.di.trans.steps.tableoutput.TableOutputMeta();
    to.setDefault();
    StepMeta cargar = new StepMeta( "TableOutput", "Cargar en la tabla", to );
    cargar.setLocation( 20, 30 );
    tm.addStep( cargar );
    StepMeta rech = paso( tm, "Rechazados", 300, 10 );
    StepMeta fin = paso( tm, "Fin", 40, 600 );
    hop( tm, leer, cargar );
    hop( tm, cargar, fin );
    hop( tm, cargar, rech );
    org.pentaho.di.trans.step.StepErrorMeta em = new org.pentaho.di.trans.step.StepErrorMeta( tm, cargar, rech );
    em.setEnabled( true );
    cargar.setStepErrorMeta( em );
    igual( LienzoLayout.ERROR, LienzoModelo.tipo( cargar, rech ), "hop de error reconocido" );
    TransLayout.ordenar( tm );
    igual( pos( tm, "Leer" ).y, pos( tm, "Cargar en la tabla" ).y, "linea principal recta" );
    igual( pos( tm, "Leer" ).y, pos( tm, "Fin" ).y, "hasta el final" );
    cierto( pos( tm, "Rechazados" ).y > pos( tm, "Cargar en la tabla" ).y, "el destino del error va abajo" );
    cierto( pos( tm, "Leer" ).x < pos( tm, "Cargar en la tabla" ).x, "de izquierda a derecha" );
  }

  public void pruebaFuenteDeLookupEnElCarrilDeAlLado() throws Exception {
    LienzoLayout.Grafo g = new LienzoLayout.Grafo();
    int a = g.nodo( new Point( 0, 0 ), 50, 15 );
    int b = g.nodo( new Point( 0, 0 ), 50, 15 );
    int lk = g.nodo( new Point( 0, 0 ), 50, 15 );
    int c = g.nodo( new Point( 0, 0 ), 50, 15 );
    int ref = g.nodo( new Point( 0, 0 ), 60, 15 );
    g.arista( a, b, LienzoLayout.NORMAL );
    g.arista( b, lk, LienzoLayout.NORMAL );
    g.arista( lk, c, LienzoLayout.NORMAL );
    g.arista( ref, lk, LienzoLayout.INFO );
    LienzoLayout.Resultado r = LienzoLayout.calcular( g, LienzoLayout.Opciones.defecto() );
    igual( r.nodos[ a ].y, r.nodos[ c ].y, "el flujo principal recto" );
    igual( r.nodos[ b ].y, r.nodos[ lk ].y, "la entrada principal del lookup en su carril" );
    cierto( r.nodos[ ref ].y != r.nodos[ lk ].y, "la fuente del lookup en otro carril" );
    igual( r.nodos[ lk ].x - ( r.nodos[ lk ].x - r.nodos[ b ].x ), r.nodos[ ref ].x, "justo antes del lookup" );
  }

  public void pruebaNombresLargosNoSePisanYGrilla() throws Exception {
    LienzoLayout.Grafo g = new LienzoLayout.Grafo();
    int prev = -1;
    for ( int i = 0; i < 5; i++ ) {
      int v = g.nodo( new Point( i * 10, 0 ), 260, 15 );
      if ( prev >= 0 ) {
        g.arista( prev, v, LienzoLayout.NORMAL );
      }
      prev = v;
    }
    LienzoLayout.Resultado r = LienzoLayout.calcular( g, LienzoLayout.Opciones.defecto() );
    LienzoMetricas.Cuenta k = LienzoMetricas.medir( g, r.nodos, r.notas );
    igual( 0, k.rotulosEncimados, "nombres de 260 px sin pisarse" );
    for ( Point p : r.nodos ) {
      igual( 0, p.x % 16, "x en la grilla" );
      igual( 0, p.y % 16, "y en la grilla" );
    }
    LienzoLayout.Opciones compacto = new LienzoLayout.Opciones();
    compacto.modo = LienzoLayout.Modo.COMPACTO;
    LienzoLayout.Resultado rc = LienzoLayout.calcular( g, compacto );
    igual( 0, LienzoMetricas.medir( g, rc.nodos, rc.notas ).rotulosEncimados, "compacto tampoco pisa nombres" );
  }

  // ---------------------------------------------------------------- notas

  /** Una transformacion con una nota grande encima de los steps y otra al lado. */
  private static TransMeta conNotas() {
    TransMeta tm = new TransMeta();
    StepMeta a = paso( tm, "Leer clientes", 100, 100 );
    StepMeta b = paso( tm, "Filtrar activos", 260, 100 );
    StepMeta c = paso( tm, "Escribir", 420, 100 );
    StepMeta d = paso( tm, "Leer ventas", 100, 400 );
    StepMeta e = paso( tm, "Sumar ventas", 260, 400 );
    hop( tm, a, b );
    hop( tm, b, c );
    hop( tm, d, e );
    hop( tm, e, c );
    tm.addNote( new NotePadMeta( "Esta nota tapa los steps\nde clientes y tiene\nvarias lineas", 80, 60, 400, 120 ) );
    tm.addNote( new NotePadMeta( "Ventas: suma por cliente", 100, 480, 200, 40 ) );
    return tm;
  }

  public void pruebaNotasNuncaTapadasYCercaDeSusSteps() throws Exception {
    for ( LienzoLayout.ModoNotas mn : LienzoLayout.ModoNotas.values() ) {
      TransMeta tm = conNotas();
      LienzoLayout.Opciones o = new LienzoLayout.Opciones();
      o.notas = mn;
      TransLayout.Movimientos mov = TransLayout.ordenar( tm, o );
      LienzoMetricas.Cuenta k = mov.medidaDespues;
      cierto( mov.medidaAntes.notasTapadas > 0, "antes la nota tapaba steps" );
      igual( 0, k.notasTapadas, mn + ": ninguna nota tapada" );
      igual( 0, k.hopsPorNotas, mn + ": ningun hop por encima de una nota" );
      igual( 0, k.notasEncimadas, mn + ": notas sin encimarse" );
      igual( 0, k.hopsPorPasos, mn + ": ningun hop por encima de un step" );
      LienzoMetricas.Cuenta real = LienzoMetricas.medir( LienzoModelo.de( tm ).grafo, null, null );
      igual( k.toString(), real.toString(), mn + ": lo aplicado es lo medido" );
      NotePadMeta ventas = tm.getNote( 1 );
      if ( mn == LienzoLayout.ModoNotas.CERCA ) {
        Point s = pos( tm, "Sumar ventas" );
        double dist = Math.hypot( ventas.getLocation().x - s.x, ventas.getLocation().y - s.y );
        cierto( dist < 300, "la nota de ventas sigue cerca de sus steps (" + dist + ")" );
      } else {
        for ( StepMeta s : tm.getSteps() ) {
          cierto( tm.getNote( 0 ).getLocation().y + 120 <= s.getLocation().y, "al margen: arriba de todo" );
          cierto( ventas.getLocation().y + 40 <= s.getLocation().y, "al margen: arriba de todo" );
        }
      }
    }
  }

  public void pruebaUnSoloCtrlZParaStepsYNotasEIdempotente() throws Exception {
    TransMeta tm = conNotas();
    List<Point> antes = new ArrayList<Point>();
    for ( StepMeta s : tm.getSteps() ) {
      antes.add( new Point( s.getLocation().x, s.getLocation().y ) );
    }
    Point n0 = new Point( tm.getNote( 0 ).getLocation().x, tm.getNote( 0 ).getLocation().y );
    nulo( tm.viewThisUndo(), "sin historial" );
    TransLayout.Movimientos mov = OrdenarLienzo.ordenar( null, tm );
    cierto( !mov.notas.isEmpty(), "movio la nota que tapaba" );
    TransAction notas = tm.viewThisUndo();
    igual( TransAction.TYPE_ACTION_POSITION_NOTE, notas.getType(), "ultima accion: las notas" );
    cierto( notas.getNextAlso(), "encadenada a la de los steps (un Ctrl+Z)" );
    deshacer( tm );
    nulo( tm.viewThisUndo(), "un Ctrl+Z deshizo todo el grupo" );
    for ( int i = 0; i < tm.nrSteps(); i++ ) {
      igual( antes.get( i ).x, tm.getStep( i ).getLocation().x, "x restaurada" );
      igual( antes.get( i ).y, tm.getStep( i ).getLocation().y, "y restaurada" );
    }
    igual( n0.x, tm.getNote( 0 ).getLocation().x, "nota restaurada" );
    igual( n0.y, tm.getNote( 0 ).getLocation().y, "nota restaurada" );
    // de nuevo, y una segunda vez: no mueve nada
    OrdenarLienzo.ordenar( null, tm );
    TransAction tope = tm.viewThisUndo();
    TransLayout.Movimientos otra = OrdenarLienzo.ordenar( null, tm );
    cierto( otra.vacio(), "ya ordenada: no mueve steps ni notas" );
    cierto( tm.viewThisUndo() == tope, "sin movimientos no agrega undo" );
  }

  /** Ctrl+Z de Spoon sobre posiciones (SpoonTransformationDelegate.undoTransformationAction, con getNextAlso). */
  static void deshacer( TransMeta tm ) {
    TransAction ta = tm.previousUndo();
    while ( ta != null ) {
      int[] idx = ta.getCurrentIndex();
      Point[] prev = ta.getPreviousLocation();
      for ( int i = 0; i < idx.length; i++ ) {
        if ( ta.getType() == TransAction.TYPE_ACTION_POSITION_STEP ) {
          tm.getStep( idx[ i ] ).setLocation( prev[ i ] );
        } else if ( ta.getType() == TransAction.TYPE_ACTION_POSITION_NOTE ) {
          tm.getNote( idx[ i ] ).setLocation( prev[ i ] );
        } else {
          falla( "accion inesperada " + ta.getType() );
        }
      }
      if ( !ta.getNextAlso() ) {
        break;
      }
      ta = tm.previousUndo();
    }
  }

  // ---------------------------------------------------------------- ejemplos y muestras

  /** Los .ktr/.kjb de una carpeta (recursivo). */
  private static void juntar( File dir, List<File> r ) {
    File[] hijos = dir.listFiles();
    if ( hijos == null ) {
      return;
    }
    java.util.Arrays.sort( hijos );
    for ( File f : hijos ) {
      if ( f.isDirectory() ) {
        juntar( f, r );
      } else if ( f.getName().endsWith( ".ktr" ) || f.getName().endsWith( ".kjb" ) ) {
        r.add( f );
      }
    }
  }

  private static File repo() {
    return new File( EntornoKettle.recursos(), "../../../.." ).getAbsoluteFile();
  }

  /** Suma de metricas de un corpus: antes, con el layout 0.6.2 y con el nuevo. */
  static final class Corpus {
    int archivos;
    int crucesAntes;
    int cruces062;
    int crucesNuevo;
    int hopsPorPasos062;
    int hopsPorPasosNuevo;
    int notasTapadasNuevo;
    int solapesNuevo;
    int hopsPorRotulosNuevo;
    final List<String> malos = new ArrayList<String>();
    final List<String> noIdem = new ArrayList<String>();

    @Override
    public String toString() {
      return archivos + " archivos; cruces antes=" + crucesAntes + " 0.6.2=" + cruces062 + " nuevo=" + crucesNuevo
        + "; hops por steps 0.6.2=" + hopsPorPasos062 + " nuevo=" + hopsPorPasosNuevo + "; hops por nombres nuevo="
        + hopsPorRotulosNuevo + "; notas tapadas nuevo=" + notasTapadasNuevo + "; solapes nuevo=" + solapesNuevo;
    }
  }

  static Corpus medirCorpus( List<File> archivos ) {
    Corpus c = new Corpus();
    for ( File f : archivos ) {
      AbstractMeta m;
      AbstractMeta v;
      try {
        m = f.getName().endsWith( ".kjb" ) ? new JobMeta( f.getPath(), null ) : new TransMeta( f.getPath() );
        v = f.getName().endsWith( ".kjb" ) ? new JobMeta( f.getPath(), null ) : new TransMeta( f.getPath() );
      } catch ( Throwable t ) {
        continue; // una muestra que no carga sin sus plugins
      }
      c.archivos++;
      LienzoModelo mv = LienzoModelo.de( v );
      List<int[]> ar = new ArrayList<int[]>();
      for ( int[] a : mv.grafo.aristas ) {
        ar.add( new int[] { a[ 0 ], a[ 1 ] } );
      }
      Point[] p062 = TransLayout.calcular( mv.grafo.actuales, ar, mv.grafo.inicio, new int[ mv.grafo.n() ] );
      LienzoMetricas.Cuenta k062 = LienzoMetricas.medir( mv.grafo, p062, null );
      TransLayout.Movimientos mov = TransLayout.ordenar( m );
      LienzoMetricas.Cuenta k = mov.medidaDespues;
      c.crucesAntes += mov.medidaAntes.cruces;
      c.cruces062 += k062.cruces;
      c.crucesNuevo += k.cruces;
      c.hopsPorPasos062 += k062.hopsPorPasos;
      c.hopsPorPasosNuevo += k.hopsPorPasos;
      c.hopsPorRotulosNuevo += k.hopsPorRotulos;
      c.notasTapadasNuevo += k.notasTapadas + k.hopsPorNotas + k.notasEncimadas;
      c.solapesNuevo += k.pasosEncimados + k.rotulosEncimados;
      if ( k.hopsPorPasos + k.notasTapadas + k.hopsPorNotas + k.notasEncimadas + k.pasosEncimados
        + k.rotulosEncimados > 0 ) {
        c.malos.add( f.getName() + ": " + k );
      }
      if ( !TransLayout.ordenar( m ).vacio() ) {
        c.noIdem.add( f.getName() );
      }
    }
    return c;
  }

  public void pruebaEjemplosDelRepo() throws Exception {
    List<File> fs = new ArrayList<File>();
    juntar( new File( repo(), "claude/skills/pentaho-etl/manual/ejemplos" ), fs );
    juntar( new File( repo(), "claude/skills/pentaho-etl/patrones" ), fs );
    juntar( new File( repo(), "ejemplo/construido-en-vivo" ), fs );
    cierto( fs.size() >= 20, "encontro los ejemplos del repo (" + fs.size() + ")" );
    Corpus c = medirCorpus( fs );
    System.out.println( "  ejemplos del repo: " + c );
    igual( "[]", c.malos.toString(), "sin hops por encima de steps ni nada tapado" );
    igual( "[]", c.noIdem.toString(), "ordenar dos veces no mueve nada" );
    cierto( c.crucesNuevo <= c.cruces062, "no mas cruces que 0.6.2" );
  }

  public void pruebaMuestrasDePdi() throws Exception {
    File dir = new File( EntornoKettle.pdi(), "samples/transformations" );
    List<File> fs = new ArrayList<File>();
    juntar( dir, fs );
    juntar( new File( EntornoKettle.pdi(), "samples/jobs" ), fs );
    if ( fs.isEmpty() ) {
      Verificador.saltear( "sin samples en " + dir );
    }
    Corpus c = medirCorpus( fs );
    System.out.println( "  muestras de PDI: " + c );
    igual( 0, c.hopsPorPasosNuevo, "ningun hop por encima de un step: " + c.malos );
    igual( 0, c.notasTapadasNuevo, "ninguna nota tapada: " + c.malos );
    igual( 0, c.solapesNuevo, "ningun icono ni nombre encimado: " + c.malos );
    cierto( c.crucesNuevo < c.cruces062, "menos cruces que 0.6.2: " + c );
    cierto( c.crucesNuevo <= c.crucesAntes, "no mas cruces que como estaban dibujadas: " + c );
    igual( "[]", c.noIdem.toString(), "ordenar dos veces no mueve nada" );
  }
}
