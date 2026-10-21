package io.github.ivi2511.pentahomcp.informes.diagramas;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;

import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Nodo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Tramo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Unidad;

/**
 * Envoltura en bandas: un flujo largo no va en una sola fila sino en r filas (LR) o columnas (TB), como
 * una serpiente que siempre se lee de izquierda a derecha y de arriba abajo. Donde cortar las capas: una
 * particion lineal por programacion dinamica que minimiza la banda mas larga; cada haz de flechas que
 * cruza un corte cuesta un 6 % mas de largo (y una flecha con rotulo cuenta doble: cortarla la deja lejos
 * de su step).
 *
 * @dueño WP6
 */
final class Envoltura {

  private Envoltura() {
  }

  /** Inicio (capa) de cada una de las r bandas, o null si hay menos de r capas. */
  static int[] cortes( Capas c, boolean tb, int r ) {
    int n = c.n();
    if ( r > n || r < 1 ) {
      return null;
    }
    double[] col = Tamanos.columnas( c );
    double[] hue = new double[ Math.max( 0, n - 1 ) ];
    int[] costo = new int[ Math.max( 0, n - 1 ) ];
    Map<Tramo, String> rot = new IdentityHashMap<>();
    Map<Tramo, Boolean> ori = new IdentityHashMap<>();
    Disposicion.ubicarRotulos( c, rot, ori );
    for ( int l = 0; l + 1 < n; l++ ) {
      hue[ l ] = Tamanos.hueco( c, l, tb, rot, ori );
      costo[ l ] = Rutas.haces( c, l ).size();
      for ( Unidad u : c.capas.get( l ) ) {
        for ( Nodo x : u.nodos ) {
          for ( Tramo t : x.salen ) {
            if ( rot.containsKey( t ) ) {
              costo[ l ] += 2;
            }
          }
        }
      }
    }
    // largo de la banda [i, j]
    double[][] largo = new double[ n ][ n ];
    for ( int i = 0; i < n; i++ ) {
      double s = 0;
      for ( int j = i; j < n; j++ ) {
        s += col[ j ] + ( j > i ? hue[ j - 1 ] : 0 );
        largo[ i ][ j ] = s;
      }
    }
    // dp[k][j] = mejor (max largo, suma de costos) para las capas 0..j en k bandas
    double[][] dpL = new double[ r + 1 ][ n ];
    double[][] dpC = new double[ r + 1 ][ n ];
    int[][] desde = new int[ r + 1 ][ n ];
    for ( double[] f : dpL ) {
      Arrays.fill( f, Double.MAX_VALUE );
    }
    for ( int j = 0; j < n; j++ ) {
      dpL[ 1 ][ j ] = largo[ 0 ][ j ];
      dpC[ 1 ][ j ] = 0;
      desde[ 1 ][ j ] = 0;
    }
    for ( int k = 2; k <= r; k++ ) {
      for ( int j = k - 1; j < n; j++ ) {
        for ( int i = k - 1; i <= j; i++ ) {
          if ( dpL[ k - 1 ][ i - 1 ] == Double.MAX_VALUE ) {
            continue;
          }
          double largoK = Math.max( dpL[ k - 1 ][ i - 1 ], largo[ i ][ j ] );
          double costoK = dpC[ k - 1 ][ i - 1 ] + costo[ i - 1 ];
          double puntaje = largoK * ( 1 + 0.06 * ( costoK - ( k - 1 ) ) );
          double actual = dpL[ k ][ j ] == Double.MAX_VALUE ? Double.MAX_VALUE : dpL[ k ][ j ] * ( 1 + 0.06 * ( dpC[ k
            ][ j ] - ( k - 1 ) ) );
          if ( puntaje < actual - 1e-9 ) {
            dpL[ k ][ j ] = largoK;
            dpC[ k ][ j ] = costoK;
            desde[ k ][ j ] = i;
          }
        }
      }
    }
    if ( dpL[ r ][ n - 1 ] == Double.MAX_VALUE ) {
      return null;
    }
    int[] ini = new int[ r ];
    int j = n - 1;
    for ( int k = r; k >= 1; k-- ) {
      ini[ k - 1 ] = desde[ k ][ j ];
      j = desde[ k ][ j ] - 1;
    }
    return ini;
  }

  /**
   * Particion lineal de pesos en p tramos seguidos con el peso maximo mas chico (inicios). La usa
   * {@link Partir} para repartir las capas de un flujo en paginas parejas.
   */
  static int[] particion( double[] peso, int p ) {
    int n = peso.length;
    double[] acum = new double[ n + 1 ];
    for ( int i = 0; i < n; i++ ) {
      acum[ i + 1 ] = acum[ i ] + peso[ i ];
    }
    double[][] f = new double[ p + 1 ][ n + 1 ];
    int[][] desde = new int[ p + 1 ][ n + 1 ];
    for ( double[] x : f ) {
      Arrays.fill( x, Double.MAX_VALUE );
    }
    f[ 0 ][ 0 ] = 0;
    for ( int k = 1; k <= p; k++ ) {
      for ( int j = k; j <= n; j++ ) {
        for ( int i = k - 1; i < j; i++ ) {
          if ( f[ k - 1 ][ i ] == Double.MAX_VALUE ) {
            continue;
          }
          double v = Math.max( f[ k - 1 ][ i ], acum[ j ] - acum[ i ] );
          if ( v < f[ k ][ j ] ) {
            f[ k ][ j ] = v;
            desde[ k ][ j ] = i;
          }
        }
      }
    }
    int[] ini = new int[ p ];
    int j = n;
    for ( int k = p; k >= 1; k-- ) {
      ini[ k - 1 ] = desde[ k ][ j ];
      j = desde[ k ][ j ];
    }
    return ini;
  }
}
