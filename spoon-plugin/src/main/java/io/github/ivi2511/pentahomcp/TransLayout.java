package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.gui.Point;
import org.pentaho.di.job.JobHopMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

/**
 * Auto-layout de izquierda a derecha para transformaciones y jobs (logica pura, sin Spoon).
 *
 * Columna = profundidad topologica por hops (camino mas largo desde una raiz): x = 100 + col*170.
 * Orden dentro de cada columna: baricentro de los vecinos con pasadas de ida y vuelta (reduce cruces);
 * en un empate, la rama mas larga primero y despues la posicion actual. Altura: cada nodo a la altura
 * de su predecesor mas alto, con 110 px minimos entre nodos de la misma columna, asi la linea
 * principal queda recta y las ramas van debajo. En jobs, START va primero. Los ciclos (posibles en
 * jobs) se cortan ignorando las aristas de vuelta.
 */
final class TransLayout {

  static final int X0 = 100;
  static final int Y0 = 100;
  static final int DX = 170;
  static final int DY = 110;

  private TransLayout() {
  }

  /** Lo que se movio, listo para spoon.addUndoPosition. */
  static final class Movimientos {
    final List<Object> objetos = new ArrayList<Object>();
    final List<Integer> indices = new ArrayList<Integer>();
    final List<Point> antes = new ArrayList<Point>();
    final List<Point> despues = new ArrayList<Point>();
    int columnas;

    Object[] objetos() {
      return objetos.toArray();
    }

    int[] indices() {
      int[] r = new int[ indices.size() ];
      for ( int i = 0; i < r.length; i++ ) {
        r[ i ] = indices.get( i );
      }
      return r;
    }

    Point[] antes() {
      return antes.toArray( new Point[ 0 ] );
    }

    Point[] despues() {
      return despues.toArray( new Point[ 0 ] );
    }
  }

  /** Ordena el lienzo del documento y devuelve lo que se movio. */
  static Movimientos ordenar( AbstractMeta m ) {
    List<Point> actuales = new ArrayList<Point>();
    List<int[]> aristas = new ArrayList<int[]>();
    int inicio = -1;
    List<Object> nodos = new ArrayList<Object>();
    if ( m instanceof TransMeta ) {
      TransMeta tm = (TransMeta) m;
      for ( StepMeta s : tm.getSteps() ) {
        nodos.add( s );
        actuales.add( s.getLocation() );
      }
      for ( int i = 0; i < tm.nrTransHops(); i++ ) {
        TransHopMeta h = tm.getTransHop( i );
        aristas.add( new int[] { nodos.indexOf( h.getFromStep() ), nodos.indexOf( h.getToStep() ) } );
      }
    } else if ( m instanceof JobMeta ) {
      JobMeta jm = (JobMeta) m;
      for ( JobEntryCopy c : jm.getJobCopies() ) {
        if ( inicio < 0 && c.isStart() ) {
          inicio = nodos.size();
        }
        nodos.add( c );
        actuales.add( c.getLocation() );
      }
      for ( int i = 0; i < jm.nrJobHops(); i++ ) {
        JobHopMeta h = jm.getJobHop( i );
        aristas.add( new int[] { nodos.indexOf( h.getFromEntry() ), nodos.indexOf( h.getToEntry() ) } );
      }
    }
    int[] col = new int[ nodos.size() ];
    Point[] nuevas = calcular( actuales, aristas, inicio, col );
    Movimientos mov = new Movimientos();
    for ( int i = 0; i < nodos.size(); i++ ) {
      mov.columnas = Math.max( mov.columnas, col[ i ] + 1 );
      Point a = actuales.get( i );
      Point n = nuevas[ i ];
      if ( a != null && a.x == n.x && a.y == n.y ) {
        continue;
      }
      Object o = nodos.get( i );
      mov.objetos.add( o );
      mov.indices.add( i ); // en TransMeta/JobMeta el indice del nodo es su posicion en la lista
      mov.antes.add( a == null ? new Point( 0, 0 ) : new Point( a.x, a.y ) );
      mov.despues.add( n );
      if ( o instanceof StepMeta ) {
        ( (StepMeta) o ).setLocation( n.x, n.y );
      } else {
        ( (JobEntryCopy) o ).setLocation( n.x, n.y );
      }
    }
    return mov;
  }

  /**
   * Calcula las posiciones nuevas. aristas = {desde, hacia} en indices de nodo (-1 se ignora).
   * inicio = nodo que va primero (START) o -1. col (salida) = columna de cada nodo.
   */
  static Point[] calcular( final List<Point> actuales, List<int[]> aristas, int inicio, int[] col ) {
    final int n = actuales.size();
    List<List<Integer>> sal = new ArrayList<List<Integer>>();
    List<List<Integer>> ent = new ArrayList<List<Integer>>();
    for ( int i = 0; i < n; i++ ) {
      sal.add( new ArrayList<Integer>() );
      ent.add( new ArrayList<Integer>() );
    }
    for ( int[] a : aristas ) {
      if ( a[ 0 ] >= 0 && a[ 1 ] >= 0 && a[ 0 ] != a[ 1 ] && !sal.get( a[ 0 ] ).contains( a[ 1 ] ) ) {
        sal.get( a[ 0 ] ).add( a[ 1 ] );
        ent.get( a[ 1 ] ).add( a[ 0 ] );
      }
    }

    // Orden estable de los nodos: START, despues por x, y, indice.
    final Integer[] orden = new Integer[ n ];
    for ( int i = 0; i < n; i++ ) {
      orden[ i ] = i;
    }
    final int ini = inicio;
    java.util.Arrays.sort( orden, new Comparator<Integer>() {
      @Override
      public int compare( Integer a, Integer b ) {
        if ( a == ini || b == ini ) {
          return a == ini ? ( b == ini ? 0 : -1 ) : 1;
        }
        Point pa = actuales.get( a );
        Point pb = actuales.get( b );
        int c = Integer.compare( pa == null ? 0 : pa.x, pb == null ? 0 : pb.x );
        if ( c == 0 ) {
          c = Integer.compare( pa == null ? 0 : pa.y, pb == null ? 0 : pb.y );
        }
        return c != 0 ? c : a.compareTo( b );
      }
    } );

    // DFS desde las raices (sin entradas; START primero) marcando aristas de vuelta para cortar ciclos.
    boolean[][] vuelta = new boolean[ n ][];
    for ( int i = 0; i < n; i++ ) {
      vuelta[ i ] = new boolean[ sal.get( i ).size() ];
    }
    int[] estado = new int[ n ]; // 0 sin ver, 1 en la pila, 2 terminado
    List<Integer> raices = new ArrayList<Integer>();
    for ( Integer i : orden ) {
      if ( i == inicio || ent.get( i ).isEmpty() ) {
        raices.add( i );
      }
    }
    for ( Integer i : orden ) { // los que solo estan en ciclos
      raices.add( i );
    }
    for ( Integer r : raices ) {
      if ( estado[ r ] == 0 ) {
        dfs( r, sal, estado, vuelta );
      }
    }

    // Camino mas largo sobre el grafo sin aristas de vuelta (Kahn).
    int[] grado = new int[ n ];
    for ( int i = 0; i < n; i++ ) {
      for ( int k = 0; k < sal.get( i ).size(); k++ ) {
        if ( !vuelta[ i ][ k ] ) {
          grado[ sal.get( i ).get( k ) ]++;
        }
      }
    }
    List<Integer> cola = new ArrayList<Integer>();
    for ( Integer i : orden ) {
      if ( grado[ i ] == 0 ) {
        cola.add( i );
      }
    }
    final int[] prof = new int[ n ];
    for ( int q = 0; q < cola.size(); q++ ) {
      int u = cola.get( q );
      for ( int k = 0; k < sal.get( u ).size(); k++ ) {
        if ( vuelta[ u ][ k ] ) {
          continue;
        }
        int v = sal.get( u ).get( k );
        prof[ v ] = Math.max( prof[ v ], prof[ u ] + 1 );
        if ( --grado[ v ] == 0 ) {
          cola.add( v );
        }
      }
    }
    if ( inicio >= 0 ) {
      prof[ inicio ] = 0;
    }
    // Una raiz que solo alimenta a algo profundo (p.ej. el stream de un Stream lookup) va justo a la
    // izquierda de lo que alimenta, no en la primera columna.
    for ( int i = 0; i < n; i++ ) {
      if ( i == inicio || !ent.get( i ).isEmpty() || sal.get( i ).isEmpty() ) {
        continue;
      }
      int min = Integer.MAX_VALUE;
      for ( int v : sal.get( i ) ) {
        min = Math.min( min, prof[ v ] );
      }
      prof[ i ] = Math.max( 0, min - 1 );
    }

    // Largo de la cadena que sigue a cada nodo (cola esta en orden topologico): en un empate, la rama
    // mas larga va arriba y queda en linea recta.
    final int[] largo = new int[ n ];
    for ( int q = cola.size() - 1; q >= 0; q-- ) {
      int u = cola.get( q );
      for ( int k = 0; k < sal.get( u ).size(); k++ ) {
        if ( !vuelta[ u ][ k ] ) {
          largo[ u ] = Math.max( largo[ u ], largo[ sal.get( u ).get( k ) ] + 1 );
        }
      }
    }

    // Filas, en tres etapas (el metodo de Sugiyama, simplificado):
    //  1. orden inicial de cada columna por la fila media de los predecesores ya ubicados;
    //  2. pasadas de ida y vuelta (baricentro) para reducir cruces;
    //  3. alturas: cada nodo a la altura de su predecesor mas alto, asi la linea principal queda recta
    //     y las ramas (el "falso" de un Filter rows, el stream de un lookup) quedan debajo.
    int maxCol = 0;
    for ( int i = 0; i < n; i++ ) {
      maxCol = Math.max( maxCol, prof[ i ] );
    }
    final double[] fila = new double[ n ];
    final double[] clave = new double[ n ];
    final List<Integer> posEnOrden = new ArrayList<Integer>( Collections.<Integer>nCopies( n, 0 ) );
    for ( int k = 0; k < n; k++ ) {
      posEnOrden.set( orden[ k ], k );
    }
    List<List<Integer>> cols = new ArrayList<List<Integer>>();
    for ( int c = 0; c <= maxCol; c++ ) {
      List<Integer> enCol = new ArrayList<Integer>();
      for ( int i = 0; i < n; i++ ) {
        if ( prof[ i ] == c ) {
          enCol.add( i );
          clave[ i ] = baricentro( ent.get( i ), prof, fila, c, true, Double.MAX_VALUE );
        }
      }
      Collections.sort( enCol, new Comparator<Integer>() {
        @Override
        public int compare( Integer a, Integer b ) {
          int r = Double.compare( clave[ a ], clave[ b ] );
          if ( r != 0 ) {
            return r;
          }
          r = Integer.compare( largo[ b ], largo[ a ] );
          if ( r != 0 ) {
            return r;
          }
          Point pa = actuales.get( a );
          Point pb = actuales.get( b );
          r = Integer.compare( pa == null ? 0 : pa.y, pb == null ? 0 : pb.y );
          return r != 0 ? r : posEnOrden.get( a ).compareTo( posEnOrden.get( b ) );
        }
      } );
      numerar( enCol, fila );
      cols.add( enCol );
    }

    for ( int pasada = 0; pasada < 4; pasada++ ) {
      for ( int c = maxCol - 1; c >= 0; c-- ) { // vuelta: mirando a los sucesores
        reordenar( cols.get( c ), sal, prof, fila, largo, c, false );
      }
      for ( int c = 1; c <= maxCol; c++ ) { // ida: mirando a los predecesores
        reordenar( cols.get( c ), ent, prof, fila, largo, c, true );
      }
    }
    if ( inicio >= 0 && cols.get( 0 ).remove( Integer.valueOf( inicio ) ) ) {
      cols.get( 0 ).add( 0, inicio );
      numerar( cols.get( 0 ), fila );
    }

    Point[] res = new Point[ n ];
    for ( int c = 0; c <= maxCol; c++ ) {
      int previo = Integer.MIN_VALUE;
      for ( int i : cols.get( c ) ) {
        // A la altura del predecesor mas alto; sin predecesores, lo mas arriba posible.
        int deseado = Integer.MAX_VALUE;
        for ( int p : ent.get( i ) ) {
          if ( prof[ p ] < c && res[ p ] != null ) {
            deseado = Math.min( deseado, res[ p ].y );
          }
        }
        if ( deseado == Integer.MAX_VALUE ) {
          deseado = Y0;
        }
        int y = previo == Integer.MIN_VALUE ? Math.max( deseado, Y0 ) : Math.max( deseado, previo + DY );
        res[ i ] = new Point( X0 + c * DX, y );
        previo = y;
        if ( col != null ) {
          col[ i ] = c;
        }
      }
    }
    return res;
  }

  /** Fila media de los vecinos (predecesores o sucesores) en otras columnas; 'sinVecinos' si no hay. */
  private static double baricentro( List<Integer> vecinos, int[] prof, double[] fila, int c, boolean antes,
                                    double sinVecinos ) {
    double suma = 0;
    int cuenta = 0;
    for ( int v : vecinos ) {
      if ( antes ? prof[ v ] < c : prof[ v ] > c ) {
        suma += fila[ v ];
        cuenta++;
      }
    }
    return cuenta == 0 ? sinVecinos : suma / cuenta;
  }

  /** Ordena una columna por el baricentro de sus vecinos; los que no tienen se quedan en su fila. */
  private static void reordenar( List<Integer> enCol, List<List<Integer>> vecinos, final int[] prof,
                                 final double[] fila, final int[] largo, int c, boolean antes ) {
    final double[] clave = new double[ enCol.size() ];
    final List<Integer> copia = new ArrayList<Integer>( enCol );
    for ( int k = 0; k < copia.size(); k++ ) {
      int i = copia.get( k );
      clave[ k ] = baricentro( vecinos.get( i ), prof, fila, c, antes, fila[ i ] );
    }
    final List<Integer> idx = new ArrayList<Integer>();
    for ( int k = 0; k < copia.size(); k++ ) {
      idx.add( k );
    }
    Collections.sort( idx, new Comparator<Integer>() { // estable: empata por fila actual
      @Override
      public int compare( Integer a, Integer b ) {
        int r = Double.compare( clave[ a ], clave[ b ] );
        if ( r == 0 ) {
          r = Integer.compare( largo[ copia.get( b ) ], largo[ copia.get( a ) ] );
        }
        return r != 0 ? r : Double.compare( fila[ copia.get( a ) ], fila[ copia.get( b ) ] );
      }
    } );
    enCol.clear();
    for ( int k : idx ) {
      enCol.add( copia.get( k ) );
    }
    numerar( enCol, fila );
  }

  private static void numerar( List<Integer> enCol, double[] fila ) {
    for ( int k = 0; k < enCol.size(); k++ ) {
      fila[ enCol.get( k ) ] = k;
    }
  }

  private static void dfs( int u, List<List<Integer>> sal, int[] estado, boolean[][] vuelta ) {
    // iterativo: los jobs pueden ser largos
    List<int[]> pila = new ArrayList<int[]>();
    pila.add( new int[] { u, 0 } );
    estado[ u ] = 1;
    while ( !pila.isEmpty() ) {
      int[] tope = pila.get( pila.size() - 1 );
      int x = tope[ 0 ];
      if ( tope[ 1 ] < sal.get( x ).size() ) {
        int k = tope[ 1 ]++;
        int v = sal.get( x ).get( k );
        if ( estado[ v ] == 1 ) {
          vuelta[ x ][ k ] = true;
        } else if ( estado[ v ] == 0 ) {
          estado[ v ] = 1;
          pila.add( new int[] { v, 0 } );
        }
      } else {
        estado[ x ] = 2;
        pila.remove( pila.size() - 1 );
      }
    }
  }
}
