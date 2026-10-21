package io.github.ivi2511.pentahomcp.informes.diagramas;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Nodo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Tramo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Unidad;

/**
 * Coordenada transversal (c) de cada unidad dentro de una banda: "baricentro con orden fijo". En cada
 * pasada cada unidad quiere quedar a la altura media de sus vecinos (la capa anterior, la siguiente o las
 * dos; los ficticios pesan x3 para que las flechas largas salgan rectas); la posicion mas cercana a esos
 * deseos que respeta el orden y la separacion minima (18 px entre steps, 12 entre grupos, 9 junto a un
 * ficticio) es una regresion isotonica ponderada (pool adjacent violators) sobre z_i = c_i -
 * desplazamiento_i. 10 x (bajada + subida) y 3 pasadas de los dos lados.
 *
 * @dueño WP6
 */
final class Posiciones {

  static final double SEP_FLUJO = 18;
  static final double SEP_GRUPO = 12;
  static final double SEP_FICTICIO = 9;

  private Posiciones() {
  }

  /** Dispone la banda [ini, fin] sin los ficticios que van por el carril exterior; devuelve su alto. */
  static double banda( Capas c, int ini, int fin, boolean tb, Set<Nodo> expreso ) {
    Map<Integer, List<Unidad>> guardadas = new HashMap<>();
    if ( !expreso.isEmpty() ) {
      for ( int l = ini; l <= fin; l++ ) {
        List<Unidad> capa = c.capas.get( l );
        guardadas.put( l, new ArrayList<>( capa ) );
        capa.removeIf( u -> u.ficticia() && expreso.contains( u.nodos.get( 0 ) ) );
      }
    }
    try {
      return banda( c, ini, fin, tb );
    } finally {
      for ( Map.Entry<Integer, List<Unidad>> e : guardadas.entrySet() ) {
        List<Unidad> capa = c.capas.get( e.getKey() );
        capa.clear();
        capa.addAll( e.getValue() );
      }
    }
  }

  private static double banda( Capas c, int ini, int fin, boolean tb ) {
    for ( int l = ini; l <= fin; l++ ) {
      double y = 0;
      List<Unidad> capa = c.capas.get( l );
      for ( int i = 0; i < capa.size(); i++ ) {
        Unidad u = capa.get( i );
        u.posC = y;
        y += u.tamC + ( i + 1 < capa.size() ? sep( u, capa.get( i + 1 ), tb ) : 0 );
      }
    }
    // centrar cada capa respecto de la mas alta
    double max = 0;
    for ( int l = ini; l <= fin; l++ ) {
      max = Math.max( max, extremo( c.capas.get( l ) ) );
    }
    for ( int l = ini; l <= fin; l++ ) {
      double d = ( max - extremo( c.capas.get( l ) ) ) / 2;
      for ( Unidad u : c.capas.get( l ) ) {
        u.posC += d;
      }
    }
    for ( int vuelta = 0; vuelta < 10; vuelta++ ) {
      for ( int l = ini + 1; l <= fin; l++ ) {
        acomodar( c, l, ini, fin, -1, tb );
      }
      for ( int l = fin - 1; l >= ini; l-- ) {
        acomodar( c, l, ini, fin, +1, tb );
      }
    }
    for ( int vuelta = 0; vuelta < 3; vuelta++ ) {
      for ( int l = ini; l <= fin; l++ ) {
        acomodar( c, l, ini, fin, 0, tb );
      }
    }
    double min = Double.MAX_VALUE;
    double maxC = -Double.MAX_VALUE;
    for ( int l = ini; l <= fin; l++ ) {
      for ( Unidad u : c.capas.get( l ) ) {
        min = Math.min( min, u.posC );
        maxC = Math.max( maxC, u.posC + u.tamC );
      }
    }
    if ( min == Double.MAX_VALUE ) {
      return 0;
    }
    for ( int l = ini; l <= fin; l++ ) {
      for ( Unidad u : c.capas.get( l ) ) {
        u.posC -= min;
      }
    }
    return maxC - min;
  }

  private static double extremo( List<Unidad> capa ) {
    return capa.isEmpty() ? 0 : capa.get( capa.size() - 1 ).posC + capa.get( capa.size() - 1 ).tamC - capa.get( 0 )
      .posC;
  }

  static double sep( Unidad a, Unidad b, boolean tb ) {
    if ( a.ficticia() || b.ficticia() ) {
      return SEP_FICTICIO;
    }
    if ( a.grupo != null || b.grupo != null ) {
      return SEP_GRUPO;
    }
    return tb ? SEP_FLUJO + 6 : SEP_FLUJO;
  }

  /** lado -1: vecinos de la capa anterior; +1: de la siguiente; 0: los dos. */
  private static void acomodar( Capas c, int l, int ini, int fin, int lado, boolean tb ) {
    List<Unidad> capa = c.capas.get( l );
    int m = capa.size();
    if ( m == 0 ) {
      return;
    }
    double[] deseo = new double[ m ];
    double[] peso = new double[ m ];
    double[] off = new double[ m ];
    double acum = 0;
    for ( int i = 0; i < m; i++ ) {
      Unidad u = capa.get( i );
      off[ i ] = acum;
      acum += u.tamC + ( i + 1 < m ? sep( u, capa.get( i + 1 ), tb ) : 0 );
      double s = 0;
      double k = 0;
      for ( Nodo n : u.nodos ) {
        double centroEnU = n.offC + n.tamC / 2;
        if ( lado <= 0 && l > ini ) {
          for ( Tramo t : n.entran ) {
            if ( t.a.capa == l - 1 && enCapa( c, t.a ) ) {
              s += t.a.cc() - centroEnU;
              k++;
            }
          }
        }
        if ( lado >= 0 && l < fin ) {
          for ( Tramo t : n.salen ) {
            if ( t.b.capa == l + 1 && enCapa( c, t.b ) ) {
              s += t.b.cc() - centroEnU;
              k++;
            }
          }
        }
      }
      if ( k > 0 ) {
        deseo[ i ] = s / k;
        peso[ i ] = k * ( u.ficticia() ? 3 : 1 );
      } else {
        deseo[ i ] = u.posC;
        peso[ i ] = 0.2;
      }
    }
    double[] z = new double[ m ];
    for ( int i = 0; i < m; i++ ) {
      z[ i ] = deseo[ i ] - off[ i ];
    }
    double[] r = isotonica( z, peso );
    for ( int i = 0; i < m; i++ ) {
      capa.get( i ).posC = r[ i ] + off[ i ];
    }
  }

  /** El vecino esta en la capa que se esta disponiendo (no en el carril exterior). */
  private static boolean enCapa( Capas c, Nodo n ) {
    return !n.ficticio || c.capas.get( n.capa ).contains( n.unidad );
  }

  /** Regresion isotonica (no decreciente) ponderada: pool adjacent violators. */
  static double[] isotonica( double[] y, double[] w ) {
    int n = y.length;
    double[] media = new double[ n ];
    double[] peso = new double[ n ];
    int[] cuenta = new int[ n ];
    int k = 0;
    for ( int i = 0; i < n; i++ ) {
      media[ k ] = y[ i ];
      peso[ k ] = w[ i ];
      cuenta[ k ] = 1;
      while ( k > 0 && media[ k - 1 ] > media[ k ] ) {
        double pw = peso[ k - 1 ] + peso[ k ];
        media[ k - 1 ] = ( media[ k - 1 ] * peso[ k - 1 ] + media[ k ] * peso[ k ] ) / pw;
        peso[ k - 1 ] = pw;
        cuenta[ k - 1 ] += cuenta[ k ];
        k--;
      }
      k++;
    }
    double[] out = new double[ n ];
    int i = 0;
    for ( int b = 0; b < k; b++ ) {
      for ( int j = 0; j < cuenta[ b ]; j++ ) {
        out[ i++ ] = media[ b ];
      }
    }
    return out;
  }
}
