package io.github.ivi2511.pentahomcp.informes.diagramas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Nodo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Tramo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Unidad;

/**
 * Paso 2: orden dentro de cada capa para cruzar lo menos posible (Sugiyama). Dos niveles: las unidades de
 * la capa (grupos o nodos sueltos) y, dentro de cada grupo, sus campos.
 * <ol>
 * <li>Orden inicial: DFS desde las fuentes en orden de documento (las ramas quedan juntas).</li>
 * <li>5 arranques (ese orden, el mismo al reves y 3 barajados con semilla fija 42), cada uno con 24
 * barridos alternos (abajo: con la capa anterior; arriba: con la siguiente). Baricentro de cada unidad =
 * media de las ranuras de los vecinos de sus campos; sin vecinos se queda donde esta. Despues, dentro de
 * cada grupo, los campos por su propio baricentro.</li>
 * <li>Tras cada barrido, transposicion: se prueba cambiar cada par de unidades vecinas (y cada par de campos
 * vecinos de un grupo) y se deja si baja la cuenta de cruces de las dos franjas que toca.</li>
 * <li>Se queda el mejor orden visto (menos cruces; empate: el primero). Deterministico.</li>
 * </ol>
 * Cruces entre dos capas: pares de tramos (i, j) con (ranura(a_i) - ranura(a_j)) * (ranura(b_i) -
 * ranura(b_j)) &lt; 0.
 *
 * @dueño WP6
 */
final class Orden {

  static final int ARRANQUES = 6;
  static final int BARRIDOS = 24;
  static final long SEMILLA = 42;

  private Orden() {
  }

  /** Ordena las capas de c y devuelve los cruces que quedan. */
  static int minimizar( Capas c ) {
    if ( c.n() == 0 ) {
      return 0;
    }
    inicial( c );
    List<List<Unidad>> base = copiar( c );
    Map<Unidad, List<Nodo>> baseInt = internos( c );
    int mejor = Integer.MAX_VALUE;
    List<List<Unidad>> mejorCapas = null;
    Map<Unidad, List<Nodo>> mejorInt = null;
    Random azar = new Random( SEMILLA );
    for ( int arranque = 0; arranque < ARRANQUES; arranque++ ) {
      restaurar( c, base, baseInt );
      if ( arranque == 1 ) {
        for ( List<Unidad> capa : c.capas ) {
          Collections.reverse( capa );
        }
      } else if ( arranque == 2 ) {
        periferico( c );
      } else if ( arranque >= 3 ) {
        for ( List<Unidad> capa : c.capas ) {
          Collections.shuffle( capa, azar );
          for ( Unidad u : capa ) {
            if ( u.grupo != null ) {
              Collections.shuffle( u.nodos, azar );
            }
          }
        }
      }
      for ( int l = 0; l < c.n(); l++ ) {
        ranuras( c, l );
      }
      int k = barridos( c );
      if ( k < mejor ) {
        mejor = k;
        mejorCapas = copiar( c );
        mejorInt = internos( c );
      }
      if ( mejor == 0 ) {
        break;
      }
    }
    restaurar( c, mejorCapas, mejorInt );
    return mejor;
  }

  private static void restaurar( Capas c, List<List<Unidad>> capas, Map<Unidad, List<Nodo>> internos ) {
    for ( int l = 0; l < c.n(); l++ ) {
      c.capas.get( l ).clear();
      c.capas.get( l ).addAll( capas.get( l ) );
    }
    for ( Map.Entry<Unidad, List<Nodo>> e : internos.entrySet() ) {
      e.getKey().nodos.clear();
      e.getKey().nodos.addAll( e.getValue() );
    }
    for ( int l = 0; l < c.n(); l++ ) {
      ranuras( c, l );
    }
  }

  private static int barridos( Capas c ) {
    int mejor = cruces( c );
    List<List<Unidad>> copia = copiar( c );
    Map<Unidad, List<Nodo>> internos = internos( c );
    int sinMejora = 0;
    for ( int it = 0; it < BARRIDOS && mejor > 0 && sinMejora < 6; it++ ) {
      boolean abajo = it % 2 == 0;
      if ( abajo ) {
        for ( int l = 1; l < c.n(); l++ ) {
          reordenar( c, l, l - 1 );
        }
      } else {
        for ( int l = c.n() - 2; l >= 0; l-- ) {
          reordenar( c, l, l + 1 );
        }
      }
      transponer( c );
      int k = cruces( c );
      if ( k < mejor ) {
        mejor = k;
        copia = copiar( c );
        internos = internos( c );
        sinMejora = 0;
      } else {
        sinMejora++; // 6 barridos (3 idas y vueltas) sin mejorar: el baricentro ya no se mueve
      }
    }
    restaurar( c, copia, internos );
    // el baricentro se queda en puntos fijos (una escalera con dos pares al reves): tamizado
    if ( mejor > 0 ) {
      tamizar( c );
      int k = cruces( c );
      if ( k < mejor ) {
        mejor = k;
      } else {
        restaurar( c, copia, internos );
      }
    }
    return mejor;
  }

  // ------------------------------------------------------------------ tamizado

  /**
   * Tamizado (sifting): cada unidad de una capa se prueba en todas las posiciones de su capa, con las
   * vecinas fijas, y queda en la de menos cruces. Usa la matriz de cruces por pares de unidades (cuantos
   * cruces hay si u queda a la izquierda de v), asi que cada capa cuesta O(m^2) despues de armarla.
   * Bajadas y subidas hasta que no mejore (como mucho 6).
   */
  static void tamizar( Capas c ) {
    for ( int vuelta = 0; vuelta < 6; vuelta++ ) {
      boolean mejoro = false;
      for ( int l = 0; l < c.n(); l++ ) {
        mejoro |= tamizarCapa( c, l );
      }
      for ( int l = c.n() - 1; l >= 0; l-- ) {
        mejoro |= tamizarCapa( c, l );
      }
      if ( !mejoro ) {
        break;
      }
    }
  }

  private static boolean tamizarCapa( Capas c, int l ) {
    List<Unidad> capa = c.capas.get( l );
    int m = capa.size();
    if ( m < 2 ) {
      return false;
    }
    boolean mejoro = false;
    for ( Unidad x : new ArrayList<>( capa ) ) {
      int[][] par = pares( c, l );
      int i = capa.indexOf( x );
      // costo relativo de poner x en cada posicion p (0..m-1) entre los demas, en su orden actual
      List<Unidad> resto = new ArrayList<>( capa );
      resto.remove( i );
      int[] idx = new int[ m - 1 ];
      for ( int k = 0; k < m - 1; k++ ) {
        idx[ k ] = capa.indexOf( resto.get( k ) );
      }
      // x a la izquierda de todos
      int costo = 0;
      for ( int k = 0; k < m - 1; k++ ) {
        costo += par[ i ][ idx[ k ] ];
      }
      int actual = -1;
      int mejorCosto = Integer.MAX_VALUE;
      int mejorP = 0;
      for ( int p = 0; p < m; p++ ) {
        if ( p > 0 ) {
          // x pasa al otro lado de resto[p-1]
          costo += par[ idx[ p - 1 ] ][ i ] - par[ i ][ idx[ p - 1 ] ];
        }
        if ( p == i ) {
          actual = costo;
        }
        if ( costo < mejorCosto ) {
          mejorCosto = costo;
          mejorP = p;
        }
      }
      if ( mejorCosto < actual ) {
        capa.remove( i );
        capa.add( mejorP, x );
        ranuras( c, l );
        mejoro = true;
      }
    }
    return mejoro;
  }

  /** par[i][j] = cruces entre las aristas de la unidad i y las de la j (con las capas vecinas) si i va antes. */
  private static int[][] pares( Capas c, int l ) {
    List<Unidad> capa = c.capas.get( l );
    int m = capa.size();
    List<List<int[]>> ext = new ArrayList<>();
    for ( Unidad u : capa ) {
      List<int[]> e = new ArrayList<>();
      for ( Nodo n : u.nodos ) {
        for ( Tramo t : n.salen ) {
          if ( t.b.capa == l + 1 ) {
            e.add( new int[] { 1, t.b.ranura } );
          }
        }
        for ( Tramo t : n.entran ) {
          if ( t.a.capa == l - 1 ) {
            e.add( new int[] { -1, t.a.ranura } );
          }
        }
      }
      ext.add( e );
    }
    int[][] par = new int[ m ][ m ];
    for ( int i = 0; i < m; i++ ) {
      for ( int j = 0; j < m; j++ ) {
        if ( i == j ) {
          continue;
        }
        int k = 0;
        for ( int[] a : ext.get( i ) ) {
          for ( int[] b : ext.get( j ) ) {
            if ( a[ 0 ] == b[ 0 ] && a[ 1 ] > b[ 1 ] ) {
              k++;
            }
          }
        }
        par[ i ][ j ] = k;
      }
    }
    return par;
  }

  // ------------------------------------------------------------------ inicial

  private static void inicial( Capas c ) {
    int[] contador = { 0 };
    for ( Unidad u : c.unidades ) {
      u.primera = Integer.MAX_VALUE;
      for ( Nodo n : u.nodos ) {
        n.ranura = 0;
      }
    }
    List<Nodo> reales = new ArrayList<>( c.g.nodos );
    Collections.sort( reales, Comparator.comparingInt( ( Nodo n ) -> n.capa ).thenComparingInt( n -> n.doc ) );
    for ( Nodo n : reales ) {
      if ( n.entran.isEmpty() ) {
        visitar( n, contador );
      }
    }
    for ( Nodo n : reales ) {
      visitar( n, contador );
    }
    for ( Nodo f : c.ficticios ) {
      visitar( f, contador );
    }
    for ( Unidad u : c.unidades ) {
      for ( Nodo n : u.nodos ) {
        u.primera = Math.min( u.primera, n.ranura );
      }
    }
    for ( List<Unidad> capa : c.capas ) {
      Collections.sort( capa, Comparator.comparingInt( ( Unidad u ) -> u.primera ) );
      for ( Unidad u : capa ) {
        if ( u.grupo != null ) {
          Collections.sort( u.nodos, Comparator.comparingInt( ( Nodo n ) -> n.ranura ) );
        }
      }
    }
    for ( int l = 0; l < c.n(); l++ ) {
      ranuras( c, l );
    }
  }

  /**
   * Orden de Cuthill-McKee: recorrido en anchura (sin direccion) desde un nodo periferico de cada
   * componente (el mas lejano del primero del documento); cada capa por orden de descubrimiento. Saca al
   * baricentro de los puntos fijos de las escaleras (cada step alimenta al siguiente de la otra rama).
   */
  private static void periferico( Capas c ) {
    List<Nodo> todos = new ArrayList<>( c.g.nodos );
    todos.addAll( c.ficticios );
    Map<Nodo, Integer> orden = new IdentityHashMap<>();
    int contador = 0;
    for ( Nodo n : todos ) {
      if ( orden.containsKey( n ) ) {
        continue;
      }
      List<Nodo> comp = anchura( n );
      Nodo lejano = comp.get( comp.size() - 1 );
      for ( Nodo x : anchura( lejano ) ) {
        orden.put( x, contador++ );
      }
    }
    for ( List<Unidad> capa : c.capas ) {
      for ( Unidad u : capa ) {
        if ( u.grupo != null ) {
          Collections.sort( u.nodos, Comparator.comparingInt( ( Nodo n ) -> orden.get( n ) ) );
        }
        u.primera = Integer.MAX_VALUE;
        for ( Nodo n : u.nodos ) {
          u.primera = Math.min( u.primera, orden.get( n ) );
        }
      }
      Collections.sort( capa, Comparator.comparingInt( ( Unidad u ) -> u.primera ) );
    }
  }

  /** Recorrido en anchura sin direccion desde n; vecinos en orden de documento (los ficticios al final). */
  private static List<Nodo> anchura( Nodo n ) {
    List<Nodo> out = new ArrayList<>();
    Map<Nodo, Boolean> visto = new IdentityHashMap<>();
    java.util.ArrayDeque<Nodo> q = new java.util.ArrayDeque<>();
    q.add( n );
    visto.put( n, Boolean.TRUE );
    while ( !q.isEmpty() ) {
      Nodo x = q.poll();
      out.add( x );
      List<Nodo> vecinos = new ArrayList<>();
      for ( Tramo t : x.salen ) {
        vecinos.add( t.b );
      }
      for ( Tramo t : x.entran ) {
        vecinos.add( t.a );
      }
      // los del mismo grupo son vecinos tambien (van juntos en la capa)
      if ( x.grupo != null ) {
        vecinos.addAll( x.unidad.nodos );
      }
      Collections.sort( vecinos, Comparator.comparingInt( ( Nodo v ) -> v.ficticio ? Integer.MAX_VALUE / 2 + v.indice
        : v.doc ) );
      for ( Nodo v : vecinos ) {
        if ( visto.put( v, Boolean.TRUE ) == null ) {
          q.add( v );
        }
      }
    }
    return out;
  }

  /** DFS iterativo (las cadenas largas no agotan la pila): salidas en orden de documento. */
  private static void visitar( Nodo inicio, int[] contador ) {
    if ( inicio.ranura > 0 ) {
      return;
    }
    java.util.Deque<Nodo> pila = new java.util.ArrayDeque<>();
    pila.push( inicio );
    while ( !pila.isEmpty() ) {
      Nodo n = pila.pop();
      if ( n.ranura > 0 ) {
        continue;
      }
      n.ranura = ++contador[ 0 ];
      List<Nodo> siguientes = new ArrayList<>();
      List<Tramo> s = new ArrayList<>( n.salen );
      Collections.sort( s, Comparator.comparingInt( ( Tramo t ) -> t.b.ficticio ? Integer.MAX_VALUE : t.b.doc ) );
      for ( Tramo t : s ) {
        siguientes.add( t.b );
      }
      // las fuentes que alimentan a este nodo, cerca
      for ( Tramo t : n.entran ) {
        if ( t.a.ranura == 0 && t.a.entran.isEmpty() ) {
          siguientes.add( t.a );
        }
      }
      for ( int i = siguientes.size() - 1; i >= 0; i-- ) {
        if ( siguientes.get( i ).ranura == 0 ) {
          pila.push( siguientes.get( i ) );
        }
      }
    }
  }

  // ------------------------------------------------------------------ barridos

  static void ranuras( Capas c, int l ) {
    int r = 0;
    for ( Unidad u : c.capas.get( l ) ) {
      for ( Nodo n : u.nodos ) {
        n.ranura = r++;
      }
    }
  }

  private static void reordenar( Capas c, int l, int ref ) {
    List<Unidad> capa = c.capas.get( l );
    for ( Unidad u : capa ) {
      double suma = 0;
      int k = 0;
      for ( Nodo n : u.nodos ) {
        double[] b = bar( n, ref );
        if ( b[ 1 ] > 0 ) {
          suma += b[ 0 ];
          k += (int) b[ 1 ];
        }
      }
      u.bar = k > 0 ? suma / k : ( u.nodos.get( 0 ).ranura + u.nodos.get( u.nodos.size() - 1 ).ranura ) / 2.0;
    }
    List<Unidad> orden = new ArrayList<>( capa );
    Collections.sort( orden, Comparator.comparingDouble( ( Unidad u ) -> u.bar ) );
    capa.clear();
    capa.addAll( orden );
    for ( Unidad u : capa ) {
      if ( u.grupo != null && u.nodos.size() > 1 ) {
        Map<Nodo, Double> b = new IdentityHashMap<>();
        for ( Nodo n : u.nodos ) {
          double[] x = bar( n, ref );
          b.put( n, x[ 1 ] > 0 ? x[ 0 ] / x[ 1 ] : n.ranura );
        }
        List<Nodo> ns = new ArrayList<>( u.nodos );
        Collections.sort( ns, Comparator.comparingDouble( b::get ) );
        u.nodos.clear();
        u.nodos.addAll( ns );
      }
    }
    ranuras( c, l );
  }

  /** {suma de ranuras de los vecinos en la capa ref, cantidad}. */
  private static double[] bar( Nodo n, int ref ) {
    double s = 0;
    int k = 0;
    for ( Tramo t : n.salen ) {
      if ( t.b.capa == ref ) {
        s += t.b.ranura;
        k++;
      }
    }
    for ( Tramo t : n.entran ) {
      if ( t.a.capa == ref ) {
        s += t.a.ranura;
        k++;
      }
    }
    return new double[] { s, k };
  }

  private static void transponer( Capas c ) {
    for ( int pasada = 0; pasada < 6; pasada++ ) {
      boolean mejoro = false;
      for ( int l = 0; l < c.n(); l++ ) {
        List<Unidad> capa = c.capas.get( l );
        for ( int i = 0; i + 1 < capa.size(); i++ ) {
          int antes = local( c, l );
          Collections.swap( capa, i, i + 1 );
          ranuras( c, l );
          int despues = local( c, l );
          if ( despues < antes ) {
            mejoro = true;
          } else {
            Collections.swap( capa, i, i + 1 );
            ranuras( c, l );
          }
        }
        for ( Unidad u : capa ) {
          if ( u.grupo == null ) {
            continue;
          }
          for ( int i = 0; i + 1 < u.nodos.size(); i++ ) {
            int antes = local( c, l );
            Collections.swap( u.nodos, i, i + 1 );
            ranuras( c, l );
            int despues = local( c, l );
            if ( despues < antes ) {
              mejoro = true;
            } else {
              Collections.swap( u.nodos, i, i + 1 );
              ranuras( c, l );
            }
          }
        }
      }
      if ( !mejoro ) {
        break;
      }
    }
  }

  private static int local( Capas c, int l ) {
    return ( l > 0 ? cruces( c, l - 1 ) : 0 ) + ( l + 1 < c.n() ? cruces( c, l ) : 0 );
  }

  /** Cruces de todo el orden (entre capas seguidas). */
  static int cruces( Capas c ) {
    int k = 0;
    for ( int l = 0; l + 1 < c.n(); l++ ) {
      k += cruces( c, l );
    }
    return k;
  }

  /** Cruces entre la capa l y la l+1. */
  static int cruces( Capas c, int l ) {
    // pares (ranura del origen, ranura del destino) ordenados; los cruces son las inversiones estrictas de
    // los destinos (arbol de Fenwick: O(E log V) en vez de comparar todos los pares)
    int e = 0;
    int maxB = 0;
    for ( Unidad u : c.capas.get( l ) ) {
      for ( Nodo n : u.nodos ) {
        for ( Tramo t : n.salen ) {
          if ( t.b.capa == l + 1 ) {
            e++;
            maxB = Math.max( maxB, t.b.ranura );
          }
        }
      }
    }
    if ( e < 2 ) {
      return 0;
    }
    long[] pares = new long[ e ];
    int i = 0;
    for ( Unidad u : c.capas.get( l ) ) {
      for ( Nodo n : u.nodos ) {
        for ( Tramo t : n.salen ) {
          if ( t.b.capa == l + 1 ) {
            pares[ i++ ] = ( (long) n.ranura << 32 ) | t.b.ranura;
          }
        }
      }
    }
    java.util.Arrays.sort( pares );
    int[] arbol = new int[ maxB + 2 ];
    int k = 0;
    for ( int j = 0; j < e; j++ ) {
      int b = (int) ( pares[ j ] & 0xFFFFFFFFL ) + 1;
      // cuantos ya puestos tienen destino <= b
      int menores = 0;
      for ( int x = b; x > 0; x -= x & -x ) {
        menores += arbol[ x ];
      }
      k += j - menores;
      for ( int x = b; x < arbol.length; x += x & -x ) {
        arbol[ x ]++;
      }
    }
    return k;
  }

  private static List<List<Unidad>> copiar( Capas c ) {
    List<List<Unidad>> r = new ArrayList<>();
    for ( List<Unidad> capa : c.capas ) {
      r.add( new ArrayList<>( capa ) );
    }
    return r;
  }

  private static Map<Unidad, List<Nodo>> internos( Capas c ) {
    Map<Unidad, List<Nodo>> m = new IdentityHashMap<>();
    for ( Unidad u : c.unidades ) {
      if ( u.grupo != null ) {
        m.put( u, new ArrayList<>( u.nodos ) );
      }
    }
    return m;
  }
}
