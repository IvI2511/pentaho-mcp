package io.github.ivi2511.pentahomcp.informes.diagramas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Arista;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Nodo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Tramo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Unidad;

/**
 * Rutas ortogonales (las esquinas se redondean al dibujar).
 * <ul>
 * <li>Dentro de una banda, cada tramo que cambia de altura usa una "pista" en el hueco entre capas; los
 * tramos del mismo haz comparten pista (haz = mismo origen si sale a 2 o mas; si no, mismo destino si le
 * llegan 2 o mas; partido en los que suben y los que bajan): asi las flechas de un origen se abren como un
 * arbol y las que llegan a un destino se juntan, en vez de cruzarse.</li>
 * <li>El orden de las pistas es un problema de orden lineal: exacto por programacion dinamica sobre
 * subconjuntos hasta 14 haces; si hay mas, insercion local.</li>
 * <li>Entre bandas: pista derecha exterior → canal entre bandas → pista izquierda interior; se prueban dos
 * ordenes (por la altura del origen y por la del destino) y queda el que menos se cruza.</li>
 * <li>Carriles exteriores: las cadenas que cruzan una banda entera bajan por la derecha y entran por el
 * canal de arriba de la banda destino.</li>
 * </ul>
 *
 * @dueño WP6
 */
final class Rutas {

  private Rutas() {
  }

  /** Haces de los tramos que salen de la capa l. */
  static List<List<Tramo>> haces( Capas c, int l ) {
    return haces( c, l, false, Collections.<Nodo>emptySet() );
  }

  /**
   * conDireccion: el haz se parte en los que bajan y los que suben (con coordenadas ya calculadas). Un
   * tronco que junta flechas de arriba y de abajo corta todo lo que pasa entre medio.
   */
  static List<List<Tramo>> haces( Capas c, int l, boolean conDireccion, Set<Nodo> excluir ) {
    Map<String, List<Tramo>> m = new LinkedHashMap<>();
    for ( Unidad u : c.capas.get( l ) ) {
      for ( Nodo n : u.nodos ) {
        for ( Tramo t : n.salen ) {
          if ( excluir.contains( t.a ) || excluir.contains( t.b ) ) {
            continue;
          }
          String k = clave( t );
          if ( conDireccion ) {
            double dc = t.b.cc() - t.a.cc();
            k += dc > 0.5 ? "+" : dc < -0.5 ? "-" : "=" + t.indice;
          }
          List<Tramo> x = m.get( k );
          if ( x == null ) {
            x = new ArrayList<>();
            m.put( k, x );
          }
          x.add( t );
        }
      }
    }
    return new ArrayList<>( m.values() );
  }

  static String clave( Tramo t ) {
    // un salto deshabilitado no comparte tronco con uno activo (el punteado gris taparia al otro)
    String clase = Capas.clase( t ) + ( Capas.activa( t ) ? "" : "~" );
    if ( t.a.salen.size() >= 2 ) {
      return "O" + t.a.indice + clase;
    }
    if ( t.b.entran.size() >= 2 ) {
      return "D" + t.b.indice + clase;
    }
    return "T" + t.indice;
  }

  static void trazar( Disposicion d ) {
    Capas c = d.c;
    for ( Tramo t : c.tramos ) {
      t.puntos = null;
    }
    for ( int l = 0; l + 1 < c.n(); l++ ) {
      if ( d.banda[ l ] == d.banda[ l + 1 ] ) {
        hueco( d, l );
      } else {
        corte( d, l );
      }
    }
    // carriles exteriores: bajan por la derecha y entran por el canal de arriba de la banda destino, por las
    // pistas izquierdas mas internas (asi no cortan las bajadas de los cortes normales)
    int[] usados = new int[ d.inicios.length ];
    for ( Disposicion.Carril k : d.carriles ) {
      Nodo u = k.entrada.a;
      List<double[]> p0 = new ArrayList<>();
      p0.add( new double[] { u.a1(), u.cc() } );
      p0.add( new double[] { k.x, u.cc() } );
      k.entrada.puntos = p0;
      Set<Integer> bandas = new HashSet<>();
      for ( Tramo s2 : k.salidas ) {
        int b = d.banda[ s2.b.capa ];
        int j = usados[ b ];
        bandas.add( b );
        int normales = d.hacesCorte( d.inicios[ b ] - 1 ).size();
        double ch = d.bandaC[ b - 1 ] + d.bandaAlto[ b - 1 ] + Disposicion.CANAL + ( normales + j ) * Disposicion.PISTA;
        double xl = d.a0 - 10 - d.rotDestino - j * Disposicion.PISTA;
        List<double[]> p = new ArrayList<>();
        p.add( new double[] { k.x, u.cc() } );
        p.add( new double[] { k.x, ch } );
        p.add( new double[] { xl, ch } );
        p.add( new double[] { xl, s2.b.cc() } );
        p.add( new double[] { s2.b.a0(), s2.b.cc() } );
        if ( s2.b.ficticio ) {
          p.add( new double[] { s2.b.a1(), s2.b.cc() } );
        }
        s2.puntos = p;
      }
      for ( int b : bandas ) {
        usados[ b ]++;
      }
    }
  }

  /** Un haz en un hueco: extremos izquierdos y derechos (coordenada c) y su intervalo. */
  private static final class Haz {
    final List<Tramo> tramos;
    final List<Double> izq = new ArrayList<>();
    final List<Double> der = new ArrayList<>();
    double min = Double.MAX_VALUE;
    double max = -Double.MAX_VALUE;
    double x;

    Haz( List<Tramo> tramos ) {
      this.tramos = tramos;
      for ( Tramo t : tramos ) {
        if ( !izq.contains( t.a.cc() ) ) {
          izq.add( t.a.cc() );
        }
        if ( !der.contains( t.b.cc() ) ) {
          der.add( t.b.cc() );
        }
        min = Math.min( min, Math.min( t.a.cc(), t.b.cc() ) );
        max = Math.max( max, Math.max( t.a.cc(), t.b.cc() ) );
      }
    }

    boolean recto() {
      return max - min < 0.5;
    }

    double baja() {
      double si = 0;
      double sd = 0;
      for ( double v : izq ) {
        si += v;
      }
      for ( double v : der ) {
        sd += v;
      }
      return sd / der.size() - si / izq.size();
    }
  }

  private static void hueco( Disposicion d, int l ) {
    double x0 = d.posCapa[ l ] + d.colA[ l ];
    double x1 = d.posCapa[ l + 1 ];
    List<Haz> hs = new ArrayList<>();
    double rotIzq = 0;
    double rotDer = 0;
    for ( List<Tramo> ts : haces( d.c, l, true, d.expreso ) ) {
      Haz h = new Haz( ts );
      for ( Tramo t : ts ) {
        String r = d.rotulos.get( t );
        if ( r != null ) {
          double w = Tamanos.rotuloA( r, d.tb );
          if ( d.rotuloOrigen.containsKey( t ) ) {
            rotIzq = Math.max( rotIzq, w );
          } else {
            rotDer = Math.max( rotDer, w );
          }
        }
      }
      if ( h.recto() ) {
        for ( Tramo t : ts ) {
          t.puntos = puntos( t, Double.NaN );
        }
      } else {
        hs.add( h );
      }
    }
    if ( hs.isEmpty() ) {
      return;
    }
    ordenarPistas( hs );
    double lo = x0 + 8 + rotIzq;
    double hi = x1 - 8 - rotDer;
    if ( hi < lo ) {
      hi = lo;
    }
    double paso = hs.size() == 1 ? 0 : Math.min( Disposicion.PISTA, ( hi - lo ) / ( hs.size() - 1 ) );
    double medio = ( lo + hi ) / 2;
    for ( int i = 0; i < hs.size(); i++ ) {
      hs.get( i ).x = medio + ( i - ( hs.size() - 1 ) / 2.0 ) * paso;
      for ( Tramo t : hs.get( i ).tramos ) {
        t.puntos = puntos( t, hs.get( i ).x );
      }
    }
  }

  private static List<double[]> puntos( Tramo t, double x ) {
    List<double[]> p = new ArrayList<>();
    double ca = t.a.cc();
    double cb = t.b.cc();
    p.add( new double[] { t.a.a1(), ca } );
    if ( !Double.isNaN( x ) && Math.abs( ca - cb ) >= 0.5 ) {
      p.add( new double[] { x, ca } );
      p.add( new double[] { x, cb } );
    }
    p.add( new double[] { t.b.a0(), cb } );
    if ( t.b.ficticio ) {
      p.add( new double[] { t.b.a1(), cb } );
    }
    return p;
  }

  /** Hasta cuantos haces (de un mismo grupo que se cruza) el orden de las pistas es exacto. */
  static final int MAX_EXACTO = 14;

  /**
   * Orden de las pistas (izquierda a derecha) con menos cruces. El costo de un orden es la suma de los
   * costos de cada par (quien queda a la izquierda): problema de orden lineal. Los haces que no se pueden
   * cruzar entre si (intervalos que no se tocan) son independientes, asi que se resuelve por grupos: exacto
   * por programacion dinamica sobre subconjuntos hasta {@link #MAX_EXACTO} haces (O(2^m m)); si hay mas,
   * insercion local con costo incremental.
   */
  private static void ordenarPistas( List<Haz> hs ) {
    int k = hs.size();
    if ( k < 2 ) {
      return;
    }
    int[][] par = new int[ k ][ k ]; // par[i][j] = cruces si i queda a la izquierda de j
    int[] padre = new int[ k ];
    for ( int i = 0; i < k; i++ ) {
      padre[ i ] = i;
    }
    for ( int i = 0; i < k; i++ ) {
      for ( int j = 0; j < k; j++ ) {
        if ( i != j ) {
          par[ i ][ j ] = costoPar( hs.get( i ), hs.get( j ) );
        }
      }
    }
    for ( int i = 0; i < k; i++ ) {
      for ( int j = i + 1; j < k; j++ ) {
        if ( par[ i ][ j ] + par[ j ][ i ] > 0 ) {
          padre[ raiz( padre, i ) ] = raiz( padre, j );
        }
      }
    }
    java.util.Map<Integer, List<Integer>> grupos = new java.util.LinkedHashMap<>();
    for ( int i = 0; i < k; i++ ) {
      List<Integer> g = grupos.get( raiz( padre, i ) );
      if ( g == null ) {
        g = new ArrayList<>();
        grupos.put( raiz( padre, i ), g );
      }
      g.add( i );
    }
    List<Haz> orden = new ArrayList<>();
    for ( List<Integer> idx : grupos.values() ) {
      int[] o = idx.size() == 1 ? new int[] { idx.get( 0 ) } : idx.size() <= MAX_EXACTO ? exacto( idx, par )
        : insercion( idx, par, hs );
      for ( int i : o ) {
        orden.add( hs.get( i ) );
      }
    }
    hs.clear();
    hs.addAll( orden );
  }

  private static int raiz( int[] p, int i ) {
    while ( p[ i ] != i ) {
      p[ i ] = p[ p[ i ] ];
      i = p[ i ];
    }
    return i;
  }

  /** Orden exacto: f(S) = min sobre j en S de f(S - j) + costo de poner j despues de todo S - j. */
  private static int[] exacto( List<Integer> idx, int[][] par ) {
    int m = idx.size();
    int n = 1 << m;
    // suma[j][S] = sum de par[i][j] para i en S (se arma sumando el bit mas bajo)
    int[][] suma = new int[ m ][ n ];
    for ( int j = 0; j < m; j++ ) {
      int hj = idx.get( j );
      for ( int s = 1; s < n; s++ ) {
        int bajo = Integer.numberOfTrailingZeros( s );
        suma[ j ][ s ] = suma[ j ][ s & ( s - 1 ) ] + par[ idx.get( bajo ) ][ hj ];
      }
    }
    int[] f = new int[ n ];
    int[] ult = new int[ n ];
    Arrays.fill( f, Integer.MAX_VALUE );
    f[ 0 ] = 0;
    for ( int s = 1; s < n; s++ ) {
      for ( int j = 0; j < m; j++ ) {
        if ( ( s & ( 1 << j ) ) == 0 ) {
          continue;
        }
        int resto = s & ~( 1 << j );
        if ( f[ resto ] == Integer.MAX_VALUE ) {
          continue;
        }
        int costo = f[ resto ] + suma[ j ][ resto ];
        if ( costo < f[ s ] ) {
          f[ s ] = costo;
          ult[ s ] = j;
        }
      }
    }
    int s = n - 1;
    int[] out = new int[ m ];
    for ( int p = m - 1; p >= 0; p-- ) {
      out[ p ] = idx.get( ult[ s ] );
      s &= ~( 1 << ult[ s ] );
    }
    return out;
  }

  /**
   * Muchos haces: arranque con los que bajan a la derecha (por la altura del origen) y despues cada haz se
   * prueba en todas las posiciones (el costo cambia en par[r][x] - par[x][r] al pasar a cada vecino r).
   */
  private static int[] insercion( List<Integer> idx, int[][] par, List<Haz> hs ) {
    List<Integer> o = new ArrayList<>( idx );
    Collections.sort( o, Comparator.comparingDouble( ( Integer i ) -> {
      Haz h = hs.get( i );
      return h.baja() >= 0 ? -h.izq.get( 0 ) - 1e6 : h.izq.get( 0 );
    } ) );
    int m = o.size();
    boolean mejoro = true;
    int vueltas = 0;
    while ( mejoro && vueltas++ < 50 ) {
      mejoro = false;
      for ( int p = 0; p < m; p++ ) {
        int x = o.remove( p );
        int costo = 0;
        for ( int r : o ) {
          costo += par[ x ][ r ];
        }
        int actual = 0;
        int mejorCosto = Integer.MAX_VALUE;
        int mejorQ = 0;
        for ( int q = 0; q < m; q++ ) {
          if ( q > 0 ) {
            int r = o.get( q - 1 );
            costo += par[ r ][ x ] - par[ x ][ r ];
          }
          if ( q == p ) {
            actual = costo;
          }
          if ( costo < mejorCosto ) {
            mejorCosto = costo;
            mejorQ = q;
          }
        }
        if ( mejorCosto < actual ) {
          o.add( mejorQ, x );
          mejoro = true;
        } else {
          o.add( p, x );
        }
      }
    }
    int[] out = new int[ m ];
    for ( int i = 0; i < m; i++ ) {
      out[ i ] = o.get( i );
    }
    return out;
  }

  /** Cruces entre dos haces si a queda a la izquierda de b. */
  private static int costoPar( Haz a, Haz b ) {
    int k = 0;
    for ( double r : a.der ) {
      if ( r > b.min + 0.1 && r < b.max - 0.1 ) {
        k++;
      }
    }
    for ( double v : b.izq ) {
      if ( v > a.min + 0.1 && v < a.max - 0.1 ) {
        k++;
      }
    }
    return k;
  }

  /**
   * Conectores entre bandas. Se prueban dos ordenes (por la altura del origen y por la del destino) y queda
   * el que menos se cruza: con haces que juntan varios origenes no siempre coinciden.
   */
  private static void corte( Disposicion d, int l ) {
    List<List<Tramo>> hs = haces( d.c, l, false, d.expreso );
    int mejor = Integer.MAX_VALUE;
    List<List<Tramo>> elegido = null;
    for ( int modo = 0; modo < 2; modo++ ) {
      final boolean porDestino = modo == 1;
      List<List<Tramo>> orden = new ArrayList<>( hs );
      Collections.sort( orden, Comparator.comparingDouble( ( List<Tramo> h ) -> {
        double m = Double.MAX_VALUE;
        for ( Tramo t : h ) {
          m = Math.min( m, porDestino ? t.b.cc() : t.a.cc() );
        }
        return m;
      } ) );
      corte( d, l, orden );
      int k = 0;
      for ( int i = 0; i < orden.size(); i++ ) {
        for ( int j = i + 1; j < orden.size(); j++ ) {
          for ( Tramo x : orden.get( i ) ) {
            for ( Tramo y : orden.get( j ) ) {
              if ( x.a != y.a && x.b != y.b && seCruzan( x.puntos, y.puntos ) ) {
                k++;
              }
            }
          }
        }
      }
      if ( k < mejor ) {
        mejor = k;
        elegido = orden;
      }
    }
    corte( d, l, elegido );
  }

  private static void corte( Disposicion d, int l, List<List<Tramo>> hs ) {
    int k = hs.size();
    int b = d.banda[ l ];
    double finA = d.bandaFinA[ b ];
    double bajo = d.bandaC[ b ] + d.bandaAlto[ b ];
    double rot = d.rotuloCorte( l, true );
    for ( int i = 0; i < k; i++ ) {
      double xr = finA + 10 + rot + ( k - 1 - i ) * Disposicion.PISTA;
      double ch = bajo + Disposicion.CANAL + ( k - 1 - i ) * Disposicion.PISTA;
      double xl = d.a0 - 10 - d.rotDestino - ( i + d.carrilesHacia( b + 1 ) ) * Disposicion.PISTA;
      for ( Tramo t : hs.get( i ) ) {
        List<double[]> p = new ArrayList<>();
        p.add( new double[] { t.a.a1(), t.a.cc() } );
        p.add( new double[] { xr, t.a.cc() } );
        p.add( new double[] { xr, ch } );
        p.add( new double[] { xl, ch } );
        p.add( new double[] { xl, t.b.cc() } );
        p.add( new double[] { t.b.a0(), t.b.cc() } );
        if ( t.b.ficticio ) {
          p.add( new double[] { t.b.a1(), t.b.cc() } );
        }
        t.puntos = p;
      }
    }
  }

  // -------------------------------------------------------------- medidas

  /** Polilinea (abstracta) de cada arista original, del origen al destino de la disposicion. */
  static List<double[]> ruta( Arista e ) {
    List<double[]> p = new ArrayList<>();
    for ( Tramo t : e.tramos ) {
      if ( t.puntos == null ) {
        continue;
      }
      for ( double[] q : t.puntos ) {
        if ( p.isEmpty() || Math.abs( p.get( p.size() - 1 )[ 0 ] - q[ 0 ] ) > 0.01 || Math.abs( p.get( p.size() - 1 )[ 1 ]
          - q[ 1 ] ) > 0.01 ) {
          p.add( q );
        }
      }
    }
    return p;
  }

  /** Pares de aristas (sin extremos en comun) cuyas rutas se cruzan. */
  static int cruces( Grafo g ) {
    List<List<double[]>> rs = new ArrayList<>();
    List<double[]> cajas = new ArrayList<>();
    for ( Arista e : g.aristas ) {
      List<double[]> r = ruta( e );
      rs.add( r );
      double[] c = { Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
      for ( double[] q : r ) {
        c[ 0 ] = Math.min( c[ 0 ], q[ 0 ] );
        c[ 1 ] = Math.min( c[ 1 ], q[ 1 ] );
        c[ 2 ] = Math.max( c[ 2 ], q[ 0 ] );
        c[ 3 ] = Math.max( c[ 3 ], q[ 1 ] );
      }
      cajas.add( c );
    }
    int k = 0;
    for ( int i = 0; i < g.aristas.size(); i++ ) {
      Arista a = g.aristas.get( i );
      double[] ca = cajas.get( i );
      for ( int j = i + 1; j < g.aristas.size(); j++ ) {
        Arista b = g.aristas.get( j );
        if ( a.a == b.a || a.b == b.b || a.a == b.b || a.b == b.a ) {
          continue;
        }
        double[] cb = cajas.get( j );
        if ( ca[ 2 ] < cb[ 0 ] || cb[ 2 ] < ca[ 0 ] || ca[ 3 ] < cb[ 1 ] || cb[ 3 ] < ca[ 1 ] ) {
          continue; // las rutas no se acercan
        }
        if ( seCruzan( rs.get( i ), rs.get( j ) ) ) {
          k++;
        }
      }
    }
    return k;
  }

  static boolean seCruzan( List<double[]> p, List<double[]> q ) {
    if ( p == null || q == null ) {
      return false;
    }
    for ( int i = 0; i + 1 < p.size(); i++ ) {
      for ( int j = 0; j + 1 < q.size(); j++ ) {
        if ( cruza( p.get( i ), p.get( i + 1 ), q.get( j ), q.get( j + 1 ) ) ) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean cruza( double[] p1, double[] p2, double[] p3, double[] p4 ) {
    double d1 = o( p3, p4, p1 );
    double d2 = o( p3, p4, p2 );
    double d3 = o( p1, p2, p3 );
    double d4 = o( p1, p2, p4 );
    return ( d1 > 1e-6 && d2 < -1e-6 || d1 < -1e-6 && d2 > 1e-6 ) && ( d3 > 1e-6 && d4 < -1e-6 || d3 < -1e-6
      && d4 > 1e-6 );
  }

  private static double o( double[] a, double[] b, double[] c ) {
    return ( b[ 0 ] - a[ 0 ] ) * ( c[ 1 ] - a[ 1 ] ) - ( b[ 1 ] - a[ 1 ] ) * ( c[ 0 ] - a[ 0 ] );
  }
}
