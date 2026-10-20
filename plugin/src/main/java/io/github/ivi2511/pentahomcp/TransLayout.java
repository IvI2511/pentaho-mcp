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
 * Dentro de cada columna, y = 100 + indice*110. Orden en la columna: primero por el orden de sus
 * predecesores (asi las ramas no se cruzan), despues por la posicion actual. En jobs, START va
 * primero. Los ciclos (posibles en jobs) se cortan ignorando las aristas de vuelta.
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

    // Filas: columna por columna, ordenando por la fila media de los predecesores ya ubicados.
    int maxCol = 0;
    for ( int i = 0; i < n; i++ ) {
      maxCol = Math.max( maxCol, prof[ i ] );
    }
    final double[] fila = new double[ n ];
    final double[] clave = new double[ n ];
    Point[] res = new Point[ n ];
    final List<Integer> posEnOrden = new ArrayList<Integer>( Collections.<Integer>nCopies( n, 0 ) );
    for ( int k = 0; k < n; k++ ) {
      posEnOrden.set( orden[ k ], k );
    }
    for ( int c = 0; c <= maxCol; c++ ) {
      List<Integer> enCol = new ArrayList<Integer>();
      for ( int i = 0; i < n; i++ ) {
        if ( prof[ i ] == c ) {
          enCol.add( i );
          double suma = 0;
          int cuenta = 0;
          for ( int p : ent.get( i ) ) {
            if ( prof[ p ] < c ) {
              suma += fila[ p ];
              cuenta++;
            }
          }
          clave[ i ] = cuenta == 0 ? Double.MAX_VALUE : suma / cuenta;
        }
      }
      Collections.sort( enCol, new Comparator<Integer>() {
        @Override
        public int compare( Integer a, Integer b ) {
          int r = Double.compare( clave[ a ], clave[ b ] );
          if ( r != 0 ) {
            return r;
          }
          Point pa = actuales.get( a );
          Point pb = actuales.get( b );
          r = Integer.compare( pa == null ? 0 : pa.y, pb == null ? 0 : pb.y );
          return r != 0 ? r : posEnOrden.get( a ).compareTo( posEnOrden.get( b ) );
        }
      } );
      if ( c == 0 && inicio >= 0 && enCol.remove( Integer.valueOf( inicio ) ) ) {
        enCol.add( 0, inicio );
      }
      for ( int k = 0; k < enCol.size(); k++ ) {
        int i = enCol.get( k );
        fila[ i ] = k;
        res[ i ] = new Point( X0 + c * DX, Y0 + k * DY );
        if ( col != null ) {
          col[ i ] = c;
        }
      }
    }
    return res;
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
