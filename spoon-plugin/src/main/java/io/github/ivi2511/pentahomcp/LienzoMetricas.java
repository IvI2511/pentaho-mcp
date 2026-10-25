package io.github.ivi2511.pentahomcp;

import org.pentaho.di.core.gui.Point;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Cuanto se ve mal un lienzo, medido con la geometria con la que Spoon lo pinta (logica pura, sin SWT):
 * <ul>
 * <li>los hops son rectas de centro a centro de los iconos (TransPainter/JobPainter.getLine);</li>
 * <li>el nombre de un step va centrado debajo del icono: x = x + icono/2 - ancho/2, y = y + icono + 5
 * (TransPainter.getNamePosition);</li>
 * <li>una nota ocupa max(width, texto + 2*5) por max(height, texto + 2*5) (BasePainter.drawNote, NOTE_MARGIN 5).</li>
 * </ul>
 * Cuenta cruces de hops, hops que pasan por encima de un icono o de un nombre que no son sus extremos, iconos y
 * nombres encimados, y notas tapadas por steps, nombres, hops u otras notas. Es lo que devuelve arrange_canvas antes
 * y despues, y lo que usan las pruebas.
 */
final class LienzoMetricas {

  /** Lo que se mide. */
  static final class Cuenta {
    /** Pares de hops que se cruzan (sin contar los que comparten un step). */
    int cruces;
    /** Hops que pasan por encima del icono de un step que no es ni su origen ni su destino. */
    int hopsPorPasos;
    /** Hops que pasan por encima del nombre de otro step. */
    int hopsPorRotulos;
    /** Pares de iconos encimados. */
    int pasosEncimados;
    /** Pares de nombres encimados entre si o con el icono de otro step. */
    int rotulosEncimados;
    /** Pares nota / (icono o nombre) que se tapan. */
    int notasTapadas;
    /** Hops que pasan por encima de una nota. */
    int hopsPorNotas;
    /** Pares de notas encimadas. */
    int notasEncimadas;

    /** Todo lo que tapa algo (las notas cuentan doble en la prueba, aca no). */
    int solapes() {
      return pasosEncimados + rotulosEncimados + notasTapadas + hopsPorNotas + notasEncimadas;
    }

    ObjectNode json() {
      ObjectNode o = Tools.obj();
      o.put( "hop_crossings", cruces );
      o.put( "hops_through_steps", hopsPorPasos );
      o.put( "hops_through_labels", hopsPorRotulos );
      o.put( "step_overlaps", pasosEncimados );
      o.put( "label_overlaps", rotulosEncimados );
      o.put( "steps_over_notes", notasTapadas );
      o.put( "hops_through_notes", hopsPorNotas );
      o.put( "note_overlaps", notasEncimadas );
      return o;
    }

    @Override
    public String toString() {
      return "cruces=" + cruces + " hopsPorPasos=" + hopsPorPasos + " hopsPorRotulos=" + hopsPorRotulos
        + " pasosEncimados=" + pasosEncimados + " rotulosEncimados=" + rotulosEncimados + " notasTapadas="
        + notasTapadas + " hopsPorNotas=" + hopsPorNotas + " notasEncimadas=" + notasEncimadas;
    }
  }

  private LienzoMetricas() {
  }

  /** Mide el grafo con los nodos y las notas en las posiciones dadas (null = la actual del grafo). */
  static Cuenta medir( LienzoLayout.Grafo g, Point[] nodos, Point[] notas ) {
    int n = g.n();
    int ic = g.icono;
    int[][] icono = new int[ n ][];
    int[][] rotulo = new int[ n ][];
    int[][] centro = new int[ n ][];
    for ( int i = 0; i < n; i++ ) {
      Point p = nodos != null && nodos[ i ] != null ? nodos[ i ] : g.actuales.get( i );
      if ( p == null ) {
        p = new Point( 0, 0 );
      }
      icono[ i ] = new int[] { p.x, p.y, p.x + ic, p.y + ic };
      rotulo[ i ] = rotulo( p.x, p.y, ic, g.rotulos.get( i )[ 0 ], g.rotulos.get( i )[ 1 ] );
      centro[ i ] = new int[] { p.x + ic / 2, p.y + ic / 2 };
    }
    int nn = g.notas.size();
    int[][] nota = new int[ nn ][];
    for ( int j = 0; j < nn; j++ ) {
      int[] d = g.notas.get( j );
      int x = notas != null && notas[ j ] != null ? notas[ j ].x : d[ 0 ];
      int y = notas != null && notas[ j ] != null ? notas[ j ].y : d[ 1 ];
      nota[ j ] = new int[] { x, y, x + d[ 2 ], y + d[ 3 ] };
    }
    Cuenta c = new Cuenta();
    java.util.List<int[]> hops = g.hopsDibujados();
    for ( int a = 0; a < hops.size(); a++ ) {
      int[] h = hops.get( a );
      int[] p1 = centro[ h[ 0 ] ];
      int[] p2 = centro[ h[ 1 ] ];
      for ( int b = a + 1; b < hops.size(); b++ ) {
        int[] k = hops.get( b );
        if ( k[ 0 ] == h[ 0 ] || k[ 0 ] == h[ 1 ] || k[ 1 ] == h[ 0 ] || k[ 1 ] == h[ 1 ] ) {
          continue;
        }
        if ( cruzan( p1[ 0 ], p1[ 1 ], p2[ 0 ], p2[ 1 ], centro[ k[ 0 ] ][ 0 ], centro[ k[ 0 ] ][ 1 ],
          centro[ k[ 1 ] ][ 0 ], centro[ k[ 1 ] ][ 1 ] ) ) {
          c.cruces++;
        }
      }
      for ( int i = 0; i < n; i++ ) {
        if ( i == h[ 0 ] || i == h[ 1 ] ) {
          continue;
        }
        if ( cortaRect( p1[ 0 ], p1[ 1 ], p2[ 0 ], p2[ 1 ], icono[ i ], 0 ) ) {
          c.hopsPorPasos++;
        } else if ( cortaRect( p1[ 0 ], p1[ 1 ], p2[ 0 ], p2[ 1 ], rotulo[ i ], 0 ) ) {
          c.hopsPorRotulos++;
        }
      }
      for ( int j = 0; j < nn; j++ ) {
        if ( cortaRect( p1[ 0 ], p1[ 1 ], p2[ 0 ], p2[ 1 ], nota[ j ], 0 ) ) {
          c.hopsPorNotas++;
        }
      }
    }
    for ( int i = 0; i < n; i++ ) {
      for ( int k = i + 1; k < n; k++ ) {
        if ( tocan( icono[ i ], icono[ k ], 0 ) ) {
          c.pasosEncimados++;
        }
        if ( tocan( rotulo[ i ], rotulo[ k ], 0 ) || tocan( rotulo[ i ], icono[ k ], 0 )
          || tocan( icono[ i ], rotulo[ k ], 0 ) ) {
          c.rotulosEncimados++;
        }
      }
      for ( int j = 0; j < nn; j++ ) {
        if ( tocan( icono[ i ], nota[ j ], 0 ) || tocan( rotulo[ i ], nota[ j ], 0 ) ) {
          c.notasTapadas++;
        }
      }
    }
    for ( int j = 0; j < nn; j++ ) {
      for ( int k = j + 1; k < nn; k++ ) {
        if ( tocan( nota[ j ], nota[ k ], 0 ) ) {
          c.notasEncimadas++;
        }
      }
    }
    return c;
  }

  // ---------------------------------------------------------------- geometria (compartida con LienzoLayout)

  /** Rectangulo {x0, y0, x1, y1} del nombre de un step con el icono en (x, y): como TransPainter.getNamePosition. */
  static int[] rotulo( int x, int y, int icono, int ancho, int alto ) {
    int x0 = x + icono / 2 - ancho / 2;
    int y0 = y + icono + 5;
    return new int[] { x0, y0, x0 + ancho, y0 + alto };
  }

  /** Dos rectangulos {x0, y0, x1, y1} se tocan (con 'aire' px de margen alrededor del primero). */
  static boolean tocan( int[] a, int[] b, int aire ) {
    return a[ 0 ] - aire < b[ 2 ] && b[ 0 ] < a[ 2 ] + aire && a[ 1 ] - aire < b[ 3 ] && b[ 1 ] < a[ 3 ] + aire;
  }

  /** El segmento (x1,y1)-(x2,y2) pasa por el rectangulo r agrandado 'aire' px (Liang-Barsky). */
  static boolean cortaRect( double x1, double y1, double x2, double y2, int[] r, int aire ) {
    double rx0 = r[ 0 ] - aire;
    double ry0 = r[ 1 ] - aire;
    double rx1 = r[ 2 ] + aire;
    double ry1 = r[ 3 ] + aire;
    double dx = x2 - x1;
    double dy = y2 - y1;
    double t0 = 0;
    double t1 = 1;
    double[] p = { -dx, dx, -dy, dy };
    double[] q = { x1 - rx0, rx1 - x1, y1 - ry0, ry1 - y1 };
    for ( int i = 0; i < 4; i++ ) {
      if ( p[ i ] == 0 ) {
        if ( q[ i ] <= 0 ) {
          return false; // paralelo y afuera (o justo en el borde)
        }
      } else {
        double t = q[ i ] / p[ i ];
        if ( p[ i ] < 0 ) {
          if ( t > t1 ) {
            return false;
          }
          if ( t > t0 ) {
            t0 = t;
          }
        } else {
          if ( t < t0 ) {
            return false;
          }
          if ( t < t1 ) {
            t1 = t;
          }
        }
      }
    }
    return t1 - t0 > 1e-9;
  }

  /** Los segmentos se cortan o se superponen (colineales que comparten un tramo). */
  static boolean cruzan( double ax, double ay, double bx, double by, double cx, double cy, double dx, double dy ) {
    double d1 = orient( cx, cy, dx, dy, ax, ay );
    double d2 = orient( cx, cy, dx, dy, bx, by );
    double d3 = orient( ax, ay, bx, by, cx, cy );
    double d4 = orient( ax, ay, bx, by, dx, dy );
    if ( ( d1 > 0 && d2 < 0 || d1 < 0 && d2 > 0 ) && ( d3 > 0 && d4 < 0 || d3 < 0 && d4 > 0 ) ) {
      return true;
    }
    if ( d1 == 0 && d2 == 0 && d3 == 0 && d4 == 0 ) { // colineales: se superponen si las proyecciones se pisan
      boolean horizontal = Math.abs( bx - ax ) + Math.abs( dx - cx ) >= Math.abs( by - ay ) + Math.abs( dy - cy );
      double a0 = horizontal ? Math.min( ax, bx ) : Math.min( ay, by );
      double a1 = horizontal ? Math.max( ax, bx ) : Math.max( ay, by );
      double c0 = horizontal ? Math.min( cx, dx ) : Math.min( cy, dy );
      double c1 = horizontal ? Math.max( cx, dx ) : Math.max( cy, dy );
      return a0 < c1 && c0 < a1;
    }
    return d1 == 0 && enTramo( cx, cy, dx, dy, ax, ay ) || d2 == 0 && enTramo( cx, cy, dx, dy, bx, by )
      || d3 == 0 && enTramo( ax, ay, bx, by, cx, cy ) || d4 == 0 && enTramo( ax, ay, bx, by, dx, dy );
  }

  private static double orient( double ax, double ay, double bx, double by, double cx, double cy ) {
    return ( bx - ax ) * ( cy - ay ) - ( by - ay ) * ( cx - ax );
  }

  private static boolean enTramo( double ax, double ay, double bx, double by, double px, double py ) {
    return Math.min( ax, bx ) <= px && px <= Math.max( ax, bx ) && Math.min( ay, by ) <= py && py <= Math.max( ay, by );
  }
}
