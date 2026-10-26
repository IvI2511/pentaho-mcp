package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.pentaho.di.core.gui.Point;

/**
 * Auto-layout por capas (Sugiyama) para el lienzo de transformaciones y jobs: logica pura, sin Spoon ni SWT. Lo usa
 * {@link TransLayout#ordenar} (arrange_canvas, el boton «Ordenar lienzo» y build_steps).
 * <ol>
 * <li><b>Componentes:</b> cada grupo de steps conectados se ordena aparte y despues se apilan en bandas horizontales
 * (los chicos, uno al lado del otro), en el orden en que estaban dibujados (el de START primero).</li>
 * <li><b>Capas:</b> los ciclos (posibles en jobs) se cortan con un DFS; columna = camino mas largo desde una raiz; un
 * step que alimenta a mas de los que lo alimentan (una fuente, el stream de un lookup) se corre a la derecha, justo
 * antes del primero que alimenta. Las columnas vacias se sacan.</li>
 * <li><b>Orden en cada columna:</b> los hops largos se parten en nodos virtuales, uno por columna; pasadas de ida y
 * vuelta por baricentro (el destino de un hop de error tira hacia abajo) mas trasposicion de vecinos, desde dos
 * ordenes iniciales (el dibujado y un DFS); queda el de menos cruces.</li>
 * <li><b>Carriles:</b> metodo de prioridades (los nodos virtuales primero: los hops largos quedan rectos) y despues
 * busqueda local sobre la geometria real, la que pinta Spoon (rectas de centro a centro, nombres medidos): mover un
 * step o un tramo recto entero a otro carril, o intercambiarlo, si baja el costo. El costo pesa, en este orden, un
 * hop que pasa por encima de otro step, uno que pasa por encima de un nombre, un cruce, un hop que no queda recto (el
 * de error quiere su destino abajo, el de lookup su fuente en el carril de al lado) y la altura.</li>
 * <li><b>Columnas:</b> separadas segun el nombre mas ancho de cada una (medido con la fuente del lienzo), asi los
 * nombres no se pisan; todo cae en la grilla de Spoon.</li>
 * <li><b>Notas:</b> nunca debajo de un step, un nombre, un hop u otra nota. Con {@link ModoNotas#CERCA} cada nota
 * sigue a los steps que tenia cerca (o encima) en el dibujo original: va al lugar libre mas cercano a ese grupo
 * (arriba, abajo, a la derecha o a la izquierda); si ya estaba bien y los steps no se movieron, no se mueve. Con
 * {@link ModoNotas#MARGEN} van todas en una banda arriba del dibujo.</li>
 * </ol>
 * Determinista: el mismo documento da siempre el mismo dibujo, y ordenar un lienzo ya ordenado no mueve nada.
 */
final class LienzoLayout {

  /** Tipos de hop. */
  static final int NORMAL = 0;
  static final int INFO = 1;
  static final int ERROR = 2;

  /** Separacion: legible (por defecto) o compacta. */
  enum Modo {
    LEGIBLE( "readable", 144, 32, 112, 64 ), COMPACTO( "compact", 112, 16, 80, 48 );

    final String json;
    /** Distancia minima entre columnas (de icono a icono). */
    final int minDx;
    /** Aire entre dos nombres de columnas vecinas. */
    final int aireRotulos;
    /** Distancia entre carriles. */
    final int dy;
    /** Aire entre componentes y alrededor de las notas. */
    final int aire;

    Modo( String json, int minDx, int aireRotulos, int dy, int aire ) {
      this.json = json;
      this.minDx = minDx;
      this.aireRotulos = aireRotulos;
      this.dy = dy;
      this.aire = aire;
    }

    /** null o vacio = LEGIBLE; un valor desconocido = null. */
    static Modo de( String s ) {
      if ( s == null || s.trim().isEmpty() ) {
        return LEGIBLE;
      }
      for ( Modo m : values() ) {
        if ( m.json.equalsIgnoreCase( s.trim() ) ) {
          return m;
        }
      }
      return null;
    }
  }

  /** Que hacer con las notas. */
  enum ModoNotas {
    CERCA( "keep_near" ), MARGEN( "margin" );

    final String json;

    ModoNotas( String json ) {
      this.json = json;
    }

    static ModoNotas de( String s ) {
      if ( s == null || s.trim().isEmpty() ) {
        return CERCA;
      }
      for ( ModoNotas m : values() ) {
        if ( m.json.equalsIgnoreCase( s.trim() ) ) {
          return m;
        }
      }
      return null;
    }
  }

  static final class Opciones {
    Modo modo = Modo.LEGIBLE;
    ModoNotas notas = ModoNotas.CERCA;

    static Opciones defecto() {
      return new Opciones();
    }
  }

  /** Lo que entra: steps (o entradas) con su posicion y el tamano del nombre, hops y notas. */
  static final class Grafo {
    final List<Point> actuales = new ArrayList<Point>();
    /** {ancho, alto} del nombre, medido con la fuente del lienzo. */
    final List<int[]> rotulos = new ArrayList<int[]>();
    /** {desde, hacia, tipo}; -1 se ignora. */
    final List<int[]> aristas = new ArrayList<int[]>();
    /** {x, y, ancho, alto} de cada nota tal como se pinta. */
    final List<int[]> notas = new ArrayList<int[]>();
    /** El nodo que va primero (START de un job) o -1. */
    int inicio = -1;
    int icono = 32;
    int grilla = 16;

    int n() {
      return actuales.size();
    }

    int nodo( Point actual, int anchoRotulo, int altoRotulo ) {
      actuales.add( actual == null ? null : new Point( actual.x, actual.y ) );
      rotulos.add( new int[] { Math.max( 0, anchoRotulo ), Math.max( 0, altoRotulo ) } );
      return actuales.size() - 1;
    }

    void arista( int desde, int hacia, int tipo ) {
      aristas.add( new int[] { desde, hacia, tipo } );
    }

    int nota( int x, int y, int ancho, int alto ) {
      notas.add( new int[] { x, y, Math.max( 1, ancho ), Math.max( 1, alto ) } );
      return notas.size() - 1;
    }

    /** Copia con los nodos y las notas en otras posiciones (las de un resultado). */
    Grafo en( Resultado r ) {
      Grafo c = new Grafo();
      for ( int i = 0; i < n(); i++ ) {
        c.nodo( r.nodos[ i ] != null ? r.nodos[ i ] : actuales.get( i ), rotulos.get( i )[ 0 ], rotulos.get( i )[ 1 ] );
      }
      for ( int[] a : aristas ) {
        c.arista( a[ 0 ], a[ 1 ], a[ 2 ] );
      }
      for ( int j = 0; j < notas.size(); j++ ) {
        int[] d = notas.get( j );
        Point p = r.notas[ j ];
        c.nota( p != null ? p.x : d[ 0 ], p != null ? p.y : d[ 1 ], d[ 2 ], d[ 3 ] );
      }
      c.inicio = inicio;
      c.icono = icono;
      c.grilla = grilla;
      return c;
    }

    /** Los hops que se dibujan: {desde, hacia} validos y distintos entre si (un hop repetido se dibuja una vez). */
    List<int[]> hopsDibujados() {
      List<int[]> r = new ArrayList<int[]>();
      java.util.Set<Long> vistos = new java.util.HashSet<Long>();
      for ( int[] a : aristas ) {
        if ( a[ 0 ] < 0 || a[ 1 ] < 0 || a[ 0 ] == a[ 1 ] || a[ 0 ] >= n() || a[ 1 ] >= n() ) {
          continue;
        }
        if ( vistos.add( ( (long) a[ 0 ] << 32 ) | a[ 1 ] ) ) {
          r.add( new int[] { a[ 0 ], a[ 1 ], a[ 2 ] } );
        }
      }
      return r;
    }
  }

  /** Lo que sale: posicion nueva de cada nodo y de cada nota (esquina superior izquierda). */
  static final class Resultado {
    Point[] nodos;
    Point[] notas;
    int columnas;
  }

  // pesos del costo de la busqueda local
  static final double P_PASO = 1000;
  static final double P_ROTULO = 300;
  static final double P_CRUCE = 70;
  static final double P_CARRIL = 30;
  static final double P_ALTO = 18;

  /** Aire alrededor de un icono al buscar hops que lo pisan (layout; la metrica usa 0). */
  static final int AIRE_ICONO = 4;
  /** Aire alrededor de una nota contra steps, nombres y hops. */
  static final int AIRE_NOTA = 12;
  /** Tope de evaluaciones de la busqueda local (unidades: aristas recorridas); determinista. */
  static final long PRESUPUESTO = 60_000_000L;

  private LienzoLayout() {
  }

  // ================================================================ entrada

  /**
   * {@link #calcular} estable. El orden inicial sale del dibujo, asi que ordenar el resultado podria cambiarlo: se
   * vuelve a ordenar cada resultado hasta que uno se repite (hasta 12 veces) y queda el mejor de todos los que
   * salieron ({@link #compararCalidad}), o el dibujo actual si ya es tan bueno como el mejor. Ordenar
   * el dibujo elegido recorre un subconjunto de los mismos resultados (mas el mismo dibujo), asi que lo vuelve a
   * elegir: ordenar un lienzo ya ordenado no mueve nada.
   */
  static Resultado calcularEstable( Grafo g, Opciones o ) {
    List<Resultado> vistos = new ArrayList<Resultado>();
    Resultado r = calcular( g, o );
    for ( int it = 0; it < 12 && r != null; it++ ) {
      for ( Resultado v : vistos ) {
        if ( iguales( v, r ) ) {
          r = null;
          break;
        }
      }
      if ( r != null ) {
        vistos.add( r );
        r = calcular( g.en( r ), o );
      }
    }
    Resultado mejor = null;
    for ( Resultado x : vistos ) {
      if ( mejor == null || compararCalidad( g, x, mejor ) < 0 ) {
        mejor = x;
      }
    }
    Resultado actual = actual( g, mejor );
    if ( actual != null && compararCalidad( g, actual, mejor ) <= 0 ) {
      return actual;
    }
    return mejor;
  }

  /** El dibujo actual como resultado (null si algun step no tiene posicion). */
  private static Resultado actual( Grafo g, Resultado ref ) {
    Resultado r = new Resultado();
    r.columnas = ref.columnas;
    r.nodos = new Point[ g.n() ];
    for ( int i = 0; i < g.n(); i++ ) {
      Point p = g.actuales.get( i );
      if ( p == null ) {
        return null;
      }
      r.nodos[ i ] = new Point( p.x, p.y );
    }
    r.notas = new Point[ g.notas.size() ];
    for ( int j = 0; j < r.notas.length; j++ ) {
      r.notas[ j ] = new Point( g.notas.get( j )[ 0 ], g.notas.get( j )[ 1 ] );
    }
    return r;
  }

  /** Menos es mejor: problemas pesados, despues area, despues las coordenadas (para desempatar siempre igual). */
  static int compararCalidad( Grafo g, Resultado a, Resultado b ) {
    int c = Double.compare( puntaje( g, a ), puntaje( g, b ) );
    if ( c != 0 ) {
      return c;
    }
    long[] pa = puntos( a.nodos );
    long[] pb = puntos( b.nodos );
    for ( int i = 0; i < pa.length; i++ ) {
      if ( pa[ i ] != pb[ i ] ) {
        return Long.compare( pa[ i ], pb[ i ] );
      }
    }
    long[] na = puntos( a.notas );
    long[] nb = puntos( b.notas );
    for ( int i = 0; i < na.length; i++ ) {
      if ( na[ i ] != nb[ i ] ) {
        return Long.compare( na[ i ], nb[ i ] );
      }
    }
    return 0;
  }

  static double puntaje( Grafo g, Resultado r ) {
    LienzoMetricas.Cuenta k = LienzoMetricas.medir( g, r.nodos, r.notas );
    double s = P_PASO * ( k.hopsPorPasos + k.notasTapadas + k.hopsPorNotas + k.notasEncimadas + k.pasosEncimados
      + k.rotulosEncimados ) + P_ROTULO * k.hopsPorRotulos + P_CRUCE * k.cruces;
    int x1 = 0;
    int y1 = 0;
    for ( Point p : r.nodos ) {
      x1 = Math.max( x1, p.x );
      y1 = Math.max( y1, p.y );
    }
    // un hop que va hacia atras (de derecha a izquierda) o de costado: el flujo no se lee de izquierda a derecha (salvo
    // el «si falla» hacia un manejador compartido, que va debajo de la fila que lo alimenta)
    int[] errores = new int[ g.n() ];
    for ( int[] h : g.hopsDibujados() ) {
      if ( h[ 2 ] == ERROR ) {
        errores[ h[ 1 ] ]++;
      }
    }
    for ( int[] h : g.hopsDibujados() ) {
      if ( h[ 2 ] == ERROR && errores[ h[ 1 ] ] >= 3 ) {
        continue;
      }
      if ( r.nodos[ h[ 1 ] ].x <= r.nodos[ h[ 0 ] ].x ) {
        s += P_CRUCE;
      }
    }
    return s + ( x1 + y1 ) / 100.0;
  }

  /** Lo que nunca se acepta de mas: hops por encima de steps o nombres, iconos o nombres encimados. */
  static int graves( LienzoMetricas.Cuenta k ) {
    return k.hopsPorPasos + k.hopsPorRotulos + k.pasosEncimados + k.rotulosEncimados;
  }

  static boolean iguales( Resultado a, Resultado b ) {
    return Arrays.equals( puntos( a.nodos ), puntos( b.nodos ) ) && Arrays.equals( puntos( a.notas ), puntos( b.notas ) );
  }

  private static long[] puntos( Point[] p ) {
    long[] r = new long[ p.length ];
    for ( int i = 0; i < p.length; i++ ) {
      r[ i ] = p[ i ] == null ? Long.MIN_VALUE : ( (long) p[ i ].x << 32 ) ^ ( p[ i ].y & 0xffffffffL );
    }
    return r;
  }

  static Resultado calcular( Grafo g, Opciones o ) {
    Resultado r = new Resultado();
    int n = g.n();
    int nn = g.notas.size();
    r.nodos = new Point[ n ];
    r.notas = new Point[ nn ];
    final int gr = Math.max( 1, g.grilla );
    Modo modo = o == null || o.modo == null ? Modo.LEGIBLE : o.modo;
    ModoNotas mn = o == null || o.notas == null ? ModoNotas.CERCA : o.notas;
    if ( n == 0 ) {
      for ( int j = 0; j < nn; j++ ) {
        r.notas[ j ] = new Point( g.notas.get( j )[ 0 ], g.notas.get( j )[ 1 ] );
      }
      return r;
    }
    List<int[]> hops = g.hopsDibujados();

    // componentes conexas
    int[] comp = new int[ n ];
    Arrays.fill( comp, -1 );
    List<List<Integer>> adj = new ArrayList<List<Integer>>();
    for ( int i = 0; i < n; i++ ) {
      adj.add( new ArrayList<Integer>() );
    }
    for ( int[] h : hops ) {
      adj.get( h[ 0 ] ).add( h[ 1 ] );
      adj.get( h[ 1 ] ).add( h[ 0 ] );
    }
    List<Comp> comps = new ArrayList<Comp>();
    for ( int i = 0; i < n; i++ ) {
      if ( comp[ i ] >= 0 ) {
        continue;
      }
      Comp c = new Comp();
      List<Integer> cola = new ArrayList<Integer>();
      cola.add( i );
      comp[ i ] = comps.size();
      for ( int q = 0; q < cola.size(); q++ ) {
        int u = cola.get( q );
        c.nodos.add( u );
        for ( int v : adj.get( u ) ) {
          if ( comp[ v ] < 0 ) {
            comp[ v ] = comps.size();
            cola.add( v );
          }
        }
      }
      Collections.sort( c.nodos );
      comps.add( c );
    }
    for ( Comp c : comps ) {
      c.preparar( g, hops );
    }
    final int ini = g.inicio;
    Collections.sort( comps, new Comparator<Comp>() {
      @Override
      public int compare( Comp a, Comp b ) {
        boolean ia = ini >= 0 && a.nodos.contains( ini );
        boolean ib = ini >= 0 && b.nodos.contains( ini );
        if ( ia != ib ) {
          return ia ? -1 : 1;
        }
        int c = Integer.compare( a.yOrig, b.yOrig );
        if ( c == 0 ) {
          c = Integer.compare( a.xOrig, b.xOrig );
        }
        return c != 0 ? c : Integer.compare( a.nodos.get( 0 ), b.nodos.get( 0 ) );
      }
    } );
    for ( int k = 0; k < comps.size(); k++ ) {
      for ( int v : comps.get( k ).nodos ) {
        comp[ v ] = k;
      }
    }
    for ( Comp c : comps ) {
      c.ordenar( g, modo, gr );
      r.columnas = Math.max( r.columnas, c.columnas );
    }

    // notas: a que componente y a que steps acompanan
    List<List<Integer>> notasDe = new ArrayList<List<Integer>>();
    for ( int k = 0; k < comps.size(); k++ ) {
      notasDe.add( new ArrayList<Integer>() );
    }
    List<Integer> margen = new ArrayList<Integer>();
    int[][] grupo = new int[ nn ][];
    for ( int j = 0; j < nn; j++ ) {
      grupo[ j ] = mn == ModoNotas.CERCA ? grupoDe( g, g.notas.get( j ) ) : new int[ 0 ];
      if ( grupo[ j ].length == 0 ) {
        margen.add( j );
        continue;
      }
      int[] votos = new int[ comps.size() ];
      int mejor = comp[ grupo[ j ][ 0 ] ];
      for ( int v : grupo[ j ] ) {
        votos[ comp[ v ] ]++;
        if ( votos[ comp[ v ] ] > votos[ mejor ] || votos[ comp[ v ] ] == votos[ mejor ] && comp[ v ] < mejor ) {
          mejor = comp[ v ];
        }
      }
      notasDe.get( mejor ).add( j );
    }
    for ( int k = 0; k < comps.size(); k++ ) {
      comps.get( k ).ubicarNotas( g, notasDe.get( k ), grupo, modo, gr );
    }

    // bandas: estantes de componentes de izquierda a derecha
    int ancho = 0;
    for ( Comp c : comps ) {
      ancho = Math.max( ancho, c.bx1 - c.bx0 );
    }
    int maxAncho = Math.max( ancho, modo == Modo.LEGIBLE ? 1200 : 900 );
    int origen = arriba( 32, gr );
    int y = origen;
    // banda de notas del margen, arriba de todo
    if ( !margen.isEmpty() ) {
      Collections.sort( margen, new Comparator<Integer>() {
        @Override
        public int compare( Integer a, Integer b ) {
          int[] na = g.notas.get( a );
          int[] nb = g.notas.get( b );
          int c = Integer.compare( na[ 1 ] / 64, nb[ 1 ] / 64 );
          if ( c == 0 ) {
            c = Integer.compare( na[ 0 ], nb[ 0 ] );
          }
          return c != 0 ? c : a.compareTo( b );
        }
      } );
      int x = origen;
      int alto = 0;
      for ( int j : margen ) {
        int[] d = g.notas.get( j );
        if ( x > origen && x + d[ 2 ] > origen + maxAncho ) {
          x = origen;
          y = arriba( y + alto + modo.aire / 2, gr );
          alto = 0;
        }
        r.notas[ j ] = new Point( x, y );
        x = arriba( x + d[ 2 ] + modo.aire / 2, gr );
        alto = Math.max( alto, d[ 3 ] );
      }
      y = arriba( y + alto + modo.aire, gr );
    }
    int x = origen;
    int altoEstante = 0;
    for ( Comp c : comps ) {
      int w = c.bx1 - c.bx0;
      int h = c.by1 - c.by0;
      if ( x > origen && x + w > origen + maxAncho ) {
        x = origen;
        y = arriba( y + altoEstante + modo.aire, gr );
        altoEstante = 0;
      }
      int dx = arriba( x - c.bx0, gr );
      int dy = arriba( y - c.by0, gr );
      for ( int k = 0; k < c.nodos.size(); k++ ) {
        r.nodos[ c.nodos.get( k ) ] = new Point( c.x[ k ] + dx, c.y[ k ] + dy );
      }
      for ( int[] nl : c.notasUbicadas ) {
        r.notas[ nl[ 0 ] ] = new Point( nl[ 1 ] + dx, nl[ 2 ] + dy );
      }
      x = arriba( c.bx1 + dx + modo.aire, gr );
      altoEstante = Math.max( altoEstante, c.by1 + dy - y );
    }
    return r;
  }

  /** Redondea hacia arriba a la grilla. */
  static int arriba( int v, int gr ) {
    return gr <= 1 ? v : (int) Math.ceil( v / (double) gr ) * gr;
  }

  /** Redondea a la grilla mas cercana. */
  static int cerca( int v, int gr ) {
    return gr <= 1 ? v : Math.round( v / (float) gr ) * gr;
  }

  /** Caja {x0,y0,x1,y1} de un step (icono y nombre) con el icono en (x, y). */
  static int[] caja( int x, int y, int icono, int[] rot ) {
    int[] r = LienzoMetricas.rotulo( x, y, icono, rot[ 0 ], rot[ 1 ] );
    return new int[] { Math.min( x, r[ 0 ] ), y, Math.max( x + icono, r[ 2 ] ), Math.max( y + icono, r[ 3 ] ) };
  }

  /** Distancia entre dos rectangulos (0 si se tocan). */
  static double distancia( int[] a, int[] b ) {
    int dx = Math.max( 0, Math.max( a[ 0 ] - b[ 2 ], b[ 0 ] - a[ 2 ] ) );
    int dy = Math.max( 0, Math.max( a[ 1 ] - b[ 3 ], b[ 1 ] - a[ 3 ] ) );
    return Math.sqrt( (double) dx * dx + (double) dy * dy );
  }

  /**
   * Los steps que una nota describe, por como estaba dibujada: los que tapa y los que estan a menos de 40 px mas que el
   * mas cercano (hasta 320 px), el mas cercano primero. Sin steps con posicion: ninguno (va al margen).
   */
  static int[] grupoDe( Grafo g, int[] nota ) {
    int[] r = { nota[ 0 ], nota[ 1 ], nota[ 0 ] + nota[ 2 ], nota[ 1 ] + nota[ 3 ] };
    int n = g.n();
    double[] d = new double[ n ];
    double min = Double.MAX_VALUE;
    for ( int i = 0; i < n; i++ ) {
      Point p = g.actuales.get( i );
      d[ i ] = p == null ? Double.MAX_VALUE : distancia( r, caja( p.x, p.y, g.icono, g.rotulos.get( i ) ) );
      min = Math.min( min, d[ i ] );
    }
    if ( min == Double.MAX_VALUE ) {
      return new int[ 0 ];
    }
    List<Integer> l = new ArrayList<Integer>();
    for ( int i = 0; i < n; i++ ) {
      if ( d[ i ] <= min + 40 && d[ i ] <= Math.max( 320, min ) ) {
        l.add( i );
      }
    }
    final double[] dd = d;
    Collections.sort( l, new Comparator<Integer>() { // el mas cercano primero
      @Override
      public int compare( Integer a, Integer b ) {
        int c = Double.compare( dd[ a ], dd[ b ] );
        return c != 0 ? c : a.compareTo( b );
      }
    } );
    int[] a = new int[ l.size() ];
    for ( int k = 0; k < a.length; k++ ) {
      a[ k ] = l.get( k );
    }
    return a;
  }

  // ================================================================ una componente

  private static final class Comp {
    final List<Integer> nodos = new ArrayList<Integer>();
    /** Aristas locales {a, b, tipo} (indices en 'nodos'). */
    final List<int[]> aristas = new ArrayList<int[]>();
    int yOrig = Integer.MAX_VALUE;
    int xOrig = Integer.MAX_VALUE;
    int inicio = -1;
    int columnas;
    // salida: posicion local del icono de cada nodo
    int[] x;
    int[] y;
    int[] col;
    int[] carril;
    // caja de la componente con sus notas
    int bx0;
    int by0;
    int bx1;
    int by1;
    final List<int[]> notasUbicadas = new ArrayList<int[]>();

    void preparar( Grafo g, List<int[]> hops ) {
      Map<Integer, Integer> local = new HashMap<Integer, Integer>();
      for ( int k = 0; k < nodos.size(); k++ ) {
        local.put( nodos.get( k ), k );
        Point p = g.actuales.get( nodos.get( k ) );
        if ( p != null ) {
          yOrig = Math.min( yOrig, p.y );
          xOrig = Math.min( xOrig, p.x );
        }
      }
      for ( int[] h : hops ) {
        Integer a = local.get( h[ 0 ] );
        Integer b = local.get( h[ 1 ] );
        if ( a != null && b != null ) {
          aristas.add( new int[] { a, b, h[ 2 ] } );
        }
      }
      Integer i = g.inicio >= 0 ? local.get( g.inicio ) : null;
      inicio = i == null ? -1 : i;
    }

    // ---------------------------------------------------------------- capas, orden y carriles

    void ordenar( Grafo g, Modo modo, int gr ) {
      final int m = nodos.size();
      final Point[] orig = new Point[ m ];
      for ( int k = 0; k < m; k++ ) {
        orig[ k ] = g.actuales.get( nodos.get( k ) );
      }
      List<List<Integer>> sal = new ArrayList<List<Integer>>();
      List<List<Integer>> ent = new ArrayList<List<Integer>>();
      for ( int k = 0; k < m; k++ ) {
        sal.add( new ArrayList<Integer>() );
        ent.add( new ArrayList<Integer>() );
      }
      for ( int e = 0; e < aristas.size(); e++ ) {
        sal.get( aristas.get( e )[ 0 ] ).add( e );
        ent.get( aristas.get( e )[ 1 ] ).add( e );
      }
      // orden estable de los nodos: inicio, despues por x, y del dibujo, despues indice
      final Integer[] orden = new Integer[ m ];
      for ( int k = 0; k < m; k++ ) {
        orden[ k ] = k;
      }
      final int ini = inicio;
      Arrays.sort( orden, new Comparator<Integer>() {
        @Override
        public int compare( Integer a, Integer b ) {
          if ( a == ini || b == ini ) {
            return a == ini ? ( b == ini ? 0 : -1 ) : 1;
          }
          int c = Integer.compare( ox( orig, a ), ox( orig, b ) );
          if ( c == 0 ) {
            c = Integer.compare( oy( orig, a ), oy( orig, b ) );
          }
          return c != 0 ? c : a.compareTo( b );
        }
      } );

      // 1. ciclos: DFS desde las raices; las aristas de vuelta se invierten para las capas
      boolean[] vuelta = new boolean[ aristas.size() ];
      int[] estado = new int[ m ];
      List<Integer> raices = new ArrayList<Integer>();
      for ( int k : orden ) {
        if ( k == inicio || ent.get( k ).isEmpty() ) {
          raices.add( k );
        }
      }
      raices.addAll( Arrays.asList( orden ) );
      for ( int rz : raices ) {
        if ( estado[ rz ] == 0 ) {
          dfs( rz, sal, aristas, estado, vuelta );
        }
      }
      // aristas del DAG {u, v, e}
      List<List<Integer>> dsal = new ArrayList<List<Integer>>();
      List<List<Integer>> dent = new ArrayList<List<Integer>>();
      for ( int k = 0; k < m; k++ ) {
        dsal.add( new ArrayList<Integer>() );
        dent.add( new ArrayList<Integer>() );
      }
      int[] du = new int[ aristas.size() ];
      int[] dv = new int[ aristas.size() ];
      for ( int e = 0; e < aristas.size(); e++ ) {
        int a = aristas.get( e )[ 0 ];
        int b = aristas.get( e )[ 1 ];
        du[ e ] = vuelta[ e ] ? b : a;
        dv[ e ] = vuelta[ e ] ? a : b;
        dsal.get( du[ e ] ).add( e );
        dent.get( dv[ e ] ).add( e );
      }

      // 2. capas: camino mas largo (Kahn en el orden estable)
      int[] grado = new int[ m ];
      for ( int e = 0; e < aristas.size(); e++ ) {
        grado[ dv[ e ] ]++;
      }
      List<Integer> topo = new ArrayList<Integer>();
      for ( int k : orden ) {
        if ( grado[ k ] == 0 ) {
          topo.add( k );
        }
      }
      int[] capa = new int[ m ];
      for ( int q = 0; q < topo.size(); q++ ) {
        int u = topo.get( q );
        for ( int e : dsal.get( u ) ) {
          capa[ dv[ e ] ] = Math.max( capa[ dv[ e ] ], capa[ u ] + 1 );
          if ( --grado[ dv[ e ] ] == 0 ) {
            topo.add( dv[ e ] );
          }
        }
      }
      // un nodo que alimenta a mas de los que lo alimentan va justo antes del primero que alimenta
      for ( int q = topo.size() - 1; q >= 0; q-- ) {
        int u = topo.get( q );
        if ( u == inicio || dsal.get( u ).isEmpty() || dsal.get( u ).size() <= dent.get( u ).size() ) {
          continue;
        }
        int min = Integer.MAX_VALUE;
        for ( int e : dsal.get( u ) ) {
          min = Math.min( min, capa[ dv[ e ] ] );
        }
        capa[ u ] = Math.max( capa[ u ], min - 1 );
      }
      if ( inicio >= 0 ) {
        capa[ inicio ] = 0;
        for ( int k = 0; k < m; k++ ) {
          if ( k != inicio && capa[ k ] == 0 && !dent.get( k ).isEmpty() ) {
            capa[ k ] = 1; // no deberia pasar
          }
        }
      }
      // sin columnas vacias
      int maxCapa = 0;
      for ( int k = 0; k < m; k++ ) {
        maxCapa = Math.max( maxCapa, capa[ k ] );
      }
      int[] nueva = new int[ maxCapa + 1 ];
      Arrays.fill( nueva, -1 );
      for ( int k = 0; k < m; k++ ) {
        nueva[ capa[ k ] ] = 0;
      }
      int nc = 0;
      for ( int c = 0; c <= maxCapa; c++ ) {
        if ( nueva[ c ] == 0 ) {
          nueva[ c ] = nc++;
        }
      }
      for ( int k = 0; k < m; k++ ) {
        capa[ k ] = nueva[ capa[ k ] ];
      }
      columnas = nc;

      // 3. nodos virtuales y orden en cada columna
      Capas cs = new Capas( m, nc, capa, orig );
      for ( int e = 0; e < aristas.size(); e++ ) {
        cs.agregar( du[ e ], dv[ e ], aristas.get( e )[ 2 ], vuelta[ e ] );
      }
      cs.ordenar( orden );

      // 4. carriles
      int[] lane = cs.prioridades();
      col = new int[ m ];
      carril = new int[ m ];
      for ( int k = 0; k < m; k++ ) {
        col[ k ] = capa[ k ];
        carril[ k ] = lane[ k ];
      }
      compactarCarriles();

      // 5. columnas segun los nombres
      int[] mitad = new int[ nc ];
      int icono = g.icono;
      for ( int k = 0; k < m; k++ ) {
        int w = g.rotulos.get( nodos.get( k ) )[ 0 ];
        mitad[ col[ k ] ] = Math.max( mitad[ col[ k ] ], Math.max( icono, w + 2 ) / 2 + 1 );
      }
      int[] colX = new int[ nc ];
      colX[ 0 ] = arriba( Math.max( 0, mitad[ 0 ] - icono / 2 ), gr );
      for ( int c = 1; c < nc; c++ ) {
        colX[ c ] = colX[ c - 1 ] + arriba( Math.max( modo.minDx, mitad[ c - 1 ] + mitad[ c ] + modo.aireRotulos ), gr );
      }
      int pasoY = arriba( Math.max( modo.dy, icono + 5 + maxAlto( g ) + modo.aireRotulos ), gr );

      // 6. busqueda local sobre la geometria real
      Busqueda b = new Busqueda( this, g, colX, pasoY );
      b.correr();
      // sin carriles vacios, salvo que sacarlos haga pasar un hop por encima de algo
      double antes = b.total();
      int[] guardados = carril.clone();
      compactarCarriles();
      if ( b.total() > antes ) {
        System.arraycopy( guardados, 0, carril, 0, m );
        int min = Integer.MAX_VALUE;
        for ( int v : carril ) {
          min = Math.min( min, v );
        }
        for ( int k = 0; k < m; k++ ) {
          carril[ k ] -= min;
        }
      }

      x = new int[ m ];
      y = new int[ m ];
      for ( int k = 0; k < m; k++ ) {
        x[ k ] = colX[ col[ k ] ];
        y[ k ] = carril[ k ] * pasoY;
      }
      // 7. retoques sobre las posiciones: cada uno se queda solo si no empeora nada de lo que se mide
      Grafo l = local( g );
      peine( l, modo, gr, colX );
      abanicos( l, modo, gr, colX, pasoY );
      manejadores( l, gr, pasoY );
      cajaDeNodos( g );
    }

    // ---------------------------------------------------------------- retoques (0.7.1)

    /** La componente sola, con sus aristas locales y sus nombres (las posiciones se pasan al medir). */
    private Grafo local( Grafo g ) {
      Grafo l = new Grafo();
      l.icono = g.icono;
      l.grilla = g.grilla;
      for ( int k = 0; k < nodos.size(); k++ ) {
        int[] r = g.rotulos.get( nodos.get( k ) );
        l.nodo( new Point( 0, 0 ), r[ 0 ], r[ 1 ] );
      }
      for ( int[] a : aristas ) {
        l.arista( a[ 0 ], a[ 1 ], a[ 2 ] );
      }
      return l;
    }

    private static Point[] puntos( int[] xs, int[] ys ) {
      Point[] p = new Point[ xs.length ];
      for ( int k = 0; k < xs.length; k++ ) {
        p[ k ] = new Point( xs[ k ], ys[ k ] );
      }
      return p;
    }

    private static LienzoMetricas.Cuenta medir( Grafo l, int[] xs, int[] ys ) {
      return LienzoMetricas.medir( l, puntos( xs, ys ), null );
    }

    /** Alto de la caja de los iconos y nombres. */
    private static int alto( Grafo l, int[] xs, int[] ys ) {
      int y0 = Integer.MAX_VALUE;
      int y1 = Integer.MIN_VALUE;
      for ( int k = 0; k < xs.length; k++ ) {
        int[] c = caja( xs[ k ], ys[ k ], l.icono, l.rotulos.get( k ) );
        y0 = Math.min( y0, c[ 1 ] );
        y1 = Math.max( y1, c[ 3 ] );
      }
      return y1 - y0;
    }

    /** Media columna para los nombres de unos nodos (como en el paso 5). */
    private static int mitad( Grafo l, List<Integer> vs ) {
      int h = l.icono / 2 + 1;
      for ( int v : vs ) {
        h = Math.max( h, Math.max( l.icono, l.rotulos.get( v )[ 0 ] + 2 ) / 2 + 1 );
      }
      return h;
    }

    private int[][] grados() {
      int m = nodos.size();
      int[][] r = new int[ 2 ][ m ];
      for ( int[] a : aristas ) {
        r[ 1 ][ a[ 0 ] ]++;
        r[ 0 ][ a[ 1 ] ]++;
      }
      return r; // {entradas, salidas}
    }

    /**
     * Peine: k &gt;= 3 fuentes (la columna 0 entera) que alimentan cada una a los MISMOS dos steps S y N de la columna 1
     * (Value Mapper: Same y New). Con las fuentes una debajo de la otra son k(k-1)/2 cruces sí o sí; en una fila, con S
     * arriba a la derecha y N abajo a la derecha, ninguno. Se queda con la fila si baja los cruces sin pasar un hop por
     * encima de nada.
     */
    void peine( Grafo l, Modo modo, int gr, int[] colX ) {
      int m = nodos.size();
      List<Integer> fuentes = new ArrayList<Integer>();
      for ( int k = 0; k < m; k++ ) {
        if ( col[ k ] == 0 ) {
          fuentes.add( k );
        }
      }
      if ( fuentes.size() < 3 ) {
        return;
      }
      int[][] gs = grados();
      int s = -1;
      int n = -1;
      for ( int f : fuentes ) {
        if ( f == inicio || gs[ 0 ][ f ] != 0 || gs[ 1 ][ f ] != 2 ) {
          return;
        }
        int a = -1;
        int b = -1;
        for ( int[] e : aristas ) {
          if ( e[ 0 ] == f ) {
            if ( e[ 2 ] != NORMAL ) {
              return;
            }
            if ( a < 0 ) {
              a = e[ 1 ];
            } else {
              b = e[ 1 ];
            }
          }
        }
        int lo = Math.min( a, b );
        int hi = Math.max( a, b );
        if ( s < 0 ) {
          s = lo;
          n = hi;
        } else if ( s != lo || n != hi ) {
          return;
        }
      }
      if ( s == n || col[ s ] != 1 || col[ n ] != 1 || gs[ 0 ][ s ] != fuentes.size() || gs[ 0 ][ n ] != fuentes
        .size() ) {
        return;
      }
      if ( y[ s ] > y[ n ] ) {
        int t = s;
        s = n;
        n = t;
      }
      final int[] yy = y;
      Collections.sort( fuentes, new Comparator<Integer>() {
        @Override
        public int compare( Integer a, Integer b ) {
          int c = Integer.compare( yy[ a ], yy[ b ] );
          return c != 0 ? c : a.compareTo( b );
        }
      } );
      LienzoMetricas.Cuenta base = medir( l, x, y );
      int k = fuentes.size();
      int dp = arriba( Math.max( modo.minDx, 2 * mitad( l, fuentes ) + modo.aireRotulos ), gr );
      int corrimiento = ( k - 1 ) * dp;
      int yS = y[ s ];
      int yN = y[ n ];
      int fila = cerca( ( yS + yN ) / 2, gr );
      int m2 = nodos.size();
      for ( int a = arriba( l.icono * 2, gr ); a <= 12 * modo.dy; a += gr ) {
        int[] nx = new int[ m2 ];
        int[] ny = new int[ m2 ];
        for ( int v = 0; v < m2; v++ ) {
          if ( col[ v ] == 0 ) {
            continue;
          }
          nx[ v ] = x[ v ] + corrimiento;
          double t = yN == yS ? ( v == n ? 1 : 0 ) : ( y[ v ] - yS ) / (double) ( yN - yS );
          ny[ v ] = cerca( (int) Math.round( fila - a + t * 2 * a ), gr );
        }
        for ( int i = 0; i < k; i++ ) {
          nx[ fuentes.get( i ) ] = colX[ 0 ] + i * dp;
          ny[ fuentes.get( i ) ] = fila;
        }
        LienzoMetricas.Cuenta c = medir( l, nx, ny );
        if ( graves( c ) <= graves( base ) && c.cruces < base.cruces ) {
          x = nx;
          y = ny;
          return;
        }
      }
    }

    /**
     * Abanicos: muchas fuentes (la columna 0 entera son fuentes puras, cada una con un solo hop) que alimentan de a
     * dos o más a un mismo step dejan el dibujo alto, una fuente por carril. Las de cada grupo se reparten en dos
     * columnas, una sí y otra no, y los carriles se juntan (medio carril entre una fuente y la siguiente, un carril
     * entero dentro de cada columna). Grupo por grupo (de arriba a abajo) se queda con la variante más baja que no
     * agrega cruces ni pasa un hop por encima de un step o un nombre.
     */
    void abanicos( Grafo l, Modo modo, int gr, int[] colX, int pasoY ) {
      int m = nodos.size();
      if ( m < 5 ) {
        return;
      }
      int[][] gs = grados();
      int[] dest = new int[ m ];
      int[] tipo = new int[ m ];
      for ( int[] a : aristas ) {
        dest[ a[ 0 ] ] = a[ 1 ];
        tipo[ a[ 0 ] ] = a[ 2 ];
      }
      java.util.TreeMap<Integer, List<Integer>> grupos = new java.util.TreeMap<Integer, List<Integer>>();
      List<Integer> fuentes = new ArrayList<Integer>();
      for ( int k = 0; k < m; k++ ) {
        if ( col[ k ] != 0 ) {
          continue;
        }
        if ( k == inicio || gs[ 0 ][ k ] != 0 || gs[ 1 ][ k ] != 1 || tipo[ k ] != NORMAL || col[ dest[ k ] ] != 1 ) {
          return; // la columna 0 no es solo de fuentes puras
        }
        fuentes.add( k );
        List<Integer> gr0 = grupos.get( dest[ k ] );
        if ( gr0 == null ) {
          gr0 = new ArrayList<Integer>();
          grupos.put( dest[ k ], gr0 );
        }
        gr0.add( k );
      }
      int enGrupo = 0;
      List<Integer> objetivos = new ArrayList<Integer>();
      for ( Map.Entry<Integer, List<Integer>> e : grupos.entrySet() ) {
        if ( e.getValue().size() >= 2 ) {
          enGrupo += e.getValue().size();
          objetivos.add( e.getKey() );
        }
      }
      if ( enGrupo < 4 ) {
        return;
      }
      final int[] cr = carril;
      Comparator<Integer> porCarril = new Comparator<Integer>() {
        @Override
        public int compare( Integer a, Integer b ) {
          int c = Integer.compare( cr[ a ], cr[ b ] );
          return c != 0 ? c : a.compareTo( b );
        }
      };
      Collections.sort( objetivos, porCarril );
      int u = arriba( ( pasoY + 1 ) / 2, gr );
      int dB = arriba( Math.max( modo.minDx, 2 * mitad( l, fuentes ) + modo.aireRotulos ), gr );
      LienzoMetricas.Cuenta base = medir( l, x, y );
      int[][] mejor = null;
      int altoMejor = alto( l, x, y );
      // la columna de atras a una distancia o a una y media (mas lejos, sus hops cruzan la de adelante mas cerca del
      // destino): se queda la que deja el dibujo mas bajo
      for ( int lejos = 0; lejos < 2; lejos++ ) {
        int dBx = lejos == 0 ? dB : arriba( dB + dB / 2, gr );
        int[][] r = abanicosCon( l, objetivos, grupos, porCarril, u, dBx, colX, pasoY, base );
        if ( r != null && alto( l, r[ 0 ], r[ 1 ] ) < altoMejor ) {
          altoMejor = alto( l, r[ 0 ], r[ 1 ] );
          mejor = r;
        }
      }
      if ( mejor != null ) {
        x = mejor[ 0 ];
        y = mejor[ 1 ];
      }
    }

    private int[][] abanicosCon( Grafo l, List<Integer> objetivos, Map<Integer, List<Integer>> grupos,
      Comparator<Integer> porCarril, int u, int dB, int[] colX, int pasoY, LienzoMetricas.Cuenta base ) {
      int m = nodos.size();
      int[] lado = new int[ m ]; // 1 = columna de atras
      int[] cr0 = carril.clone(); // el destino de un grupo puede pasar al carril de una de sus fuentes
      int[][] mejor = null;
      int altoMejor = alto( l, x, y );
      for ( int t : objetivos ) {
        List<Integer> fs = new ArrayList<Integer>( grupos.get( t ) );
        Collections.sort( fs, porCarril );
        List<Integer> carriles = new ArrayList<Integer>();
        carriles.add( cr0[ t ] );
        for ( int f : fs ) {
          boolean libre = !carriles.contains( cr0[ f ] );
          for ( int v = 0; v < m && libre; v++ ) {
            libre = v == t || col[ v ] != col[ t ] || cr0[ v ] != cr0[ f ];
          }
          if ( libre ) {
            carriles.add( cr0[ f ] );
          }
        }
        int[] elegido = null;
        int[] elegidoCr = null;
        for ( int ct : carriles ) {
          // grupos de hasta 5: todas las maneras de repartirlas; mas grandes, una si y otra no
          int variantes = fs.size() <= 5 ? 1 << fs.size() : 2;
          for ( int var = 0; var < variantes; var++ ) {
            int[] ld = lado.clone();
            for ( int i = 0; i < fs.size(); i++ ) {
              ld[ fs.get( i ) ] = fs.size() <= 5 ? ( var >> i ) & 1 : ( i + var ) % 2;
            }
            int[] crs = cr0.clone();
            crs[ t ] = ct;
            int[][] xy = empaquetar( ld, crs, u, dB, colX, pasoY );
            LienzoMetricas.Cuenta c = medir( l, xy[ 0 ], xy[ 1 ] );
            if ( graves( c ) > graves( base ) || c.cruces > base.cruces ) {
              continue;
            }
            int h = alto( l, xy[ 0 ], xy[ 1 ] );
            if ( h < altoMejor ) {
              altoMejor = h;
              elegido = ld;
              elegidoCr = crs;
              mejor = xy;
            }
          }
        }
        if ( elegido != null ) {
          lado = elegido;
          cr0 = elegidoCr;
        }
      }
      return mejor;
    }

    /**
     * Las posiciones con las fuentes de 'lado' = 1 en una columna de atras: los carriles se apilan de arriba a abajo,
     * cada uno medio carril debajo del anterior como minimo y un carril entero debajo del ultimo nodo de cada una de
     * sus columnas.
     */
    private int[][] empaquetar( int[] lado, int[] carril, int u, int dB, int[] colX, int pasoY ) {
      int m = nodos.size();
      int max = 0;
      for ( int k = 0; k < m; k++ ) {
        max = Math.max( max, carril[ k ] );
      }
      List<List<Integer>> porCarril = new ArrayList<List<Integer>>();
      for ( int c = 0; c <= max; c++ ) {
        porCarril.add( new ArrayList<Integer>() );
      }
      for ( int k = 0; k < m; k++ ) {
        porCarril.get( carril[ k ] ).add( k );
      }
      int[] yc = new int[ max + 1 ];
      Map<Integer, Integer> ultimo = new HashMap<Integer, Integer>();
      for ( int c = 0; c <= max; c++ ) {
        int v0 = c == 0 ? 0 : yc[ c - 1 ] + u;
        for ( int v : porCarril.get( c ) ) {
          Integer p = ultimo.get( col[ v ] == 0 && lado[ v ] == 1 ? -1 : col[ v ] );
          if ( p != null ) {
            v0 = Math.max( v0, p + pasoY );
          }
        }
        yc[ c ] = v0;
        for ( int v : porCarril.get( c ) ) {
          ultimo.put( col[ v ] == 0 && lado[ v ] == 1 ? -1 : col[ v ], v0 );
        }
      }
      int[] nx = new int[ m ];
      int[] ny = new int[ m ];
      for ( int k = 0; k < m; k++ ) {
        nx[ k ] = col[ k ] == 0 && lado[ k ] == 1 ? colX[ 0 ] - dB : colX[ col[ k ] ];
        ny[ k ] = yc[ carril[ k ] ];
      }
      return new int[][] { nx, ny };
    }

    /**
     * Manejadores de error compartidos (en un job, «Registrar el error» con ocho entradas que le mandan su «si
     * falla»): si tres o más nodos de una misma fila le mandan su hop de error, el manejador (y lo que cuelga solo de
     * él, como «Abortar») va DEBAJO de esa fila, en el x y la profundidad mas chicos en que ningún hop pasa por encima
     * de un step o de un nombre y no hay más cruces que antes.
     */
    void manejadores( Grafo l, int gr, int pasoY ) {
      int m = nodos.size();
      for ( int h = 0; h < m; h++ ) {
        List<Integer> fs = new ArrayList<Integer>();
        for ( int[] a : aristas ) {
          if ( a[ 1 ] == h && a[ 2 ] == ERROR && !fs.contains( a[ 0 ] ) ) {
            fs.add( a[ 0 ] );
          }
        }
        if ( fs.size() < 3 ) {
          continue;
        }
        int fila = y[ fs.get( 0 ) ];
        java.util.TreeSet<Integer> xs = new java.util.TreeSet<Integer>();
        boolean misma = true;
        for ( int f : fs ) {
          misma &= y[ f ] == fila;
          xs.add( x[ f ] );
        }
        if ( !misma || xs.size() < 3 ) {
          continue;
        }
        // lo que cuelga solo del manejador
        List<Integer> sub = new ArrayList<Integer>();
        sub.add( h );
        boolean crecio = true;
        while ( crecio ) {
          crecio = false;
          for ( int[] a : aristas ) {
            if ( sub.contains( a[ 0 ] ) && !sub.contains( a[ 1 ] ) ) {
              boolean solo = true;
              for ( int[] b : aristas ) {
                if ( b[ 1 ] == a[ 1 ] && !sub.contains( b[ 0 ] ) ) {
                  solo = false;
                }
              }
              if ( solo ) {
                sub.add( a[ 1 ] );
                crecio = true;
              }
            }
          }
        }
        boolean cruzaFila = false;
        for ( int v : sub ) {
          cruzaFila |= fs.contains( v );
        }
        if ( cruzaFila ) {
          continue;
        }
        // x candidatas: las de las fuentes y los puntos medios, la mas cercana a la mediana primero
        List<Integer> cand = new ArrayList<Integer>();
        Integer prev = null;
        for ( int v : xs ) {
          if ( prev != null ) {
            cand.add( arriba( ( prev + v ) / 2, gr ) );
          }
          cand.add( v );
          prev = v;
        }
        List<Integer> lx = new ArrayList<Integer>( xs );
        final int med = lx.get( lx.size() / 2 );
        Collections.sort( cand, new Comparator<Integer>() {
          @Override
          public int compare( Integer a, Integer b ) {
            int c = Integer.compare( Math.abs( a - med ), Math.abs( b - med ) );
            return c != 0 ? c : a.compareTo( b );
          }
        } );
        LienzoMetricas.Cuenta base = medir( l, x, y );
        int[] nx = x.clone();
        int[] ny = y.clone();
        busca: for ( int yh = arriba( fila + pasoY, gr ); yh <= fila + 10 * pasoY; yh += gr ) {
          for ( int xh : cand ) {
            for ( int v : sub ) {
              nx[ v ] = xh + ( x[ v ] - x[ h ] );
              ny[ v ] = yh + ( y[ v ] - y[ h ] );
            }
            LienzoMetricas.Cuenta c = medir( l, nx, ny );
            if ( graves( c ) <= graves( base ) && c.cruces <= base.cruces ) {
              x = nx;
              y = ny;
              break busca;
            }
          }
        }
      }
    }

    private int maxAlto( Grafo g ) {
      int h = 0;
      for ( int v : nodos ) {
        h = Math.max( h, g.rotulos.get( v )[ 1 ] );
      }
      return h;
    }

    /** Saca los carriles sin ningun step y empieza en 0. */
    void compactarCarriles() {
      int m = nodos.size();
      int max = Integer.MIN_VALUE;
      int min = Integer.MAX_VALUE;
      for ( int k = 0; k < m; k++ ) {
        max = Math.max( max, carril[ k ] );
        min = Math.min( min, carril[ k ] );
      }
      boolean[] usado = new boolean[ max - min + 1 ];
      for ( int k = 0; k < m; k++ ) {
        usado[ carril[ k ] - min ] = true;
      }
      int[] nuevo = new int[ usado.length ];
      int c = 0;
      for ( int l = 0; l < usado.length; l++ ) {
        nuevo[ l ] = c;
        if ( usado[ l ] ) {
          c++;
        }
      }
      for ( int k = 0; k < m; k++ ) {
        carril[ k ] = nuevo[ carril[ k ] - min ];
      }
    }

    private void cajaDeNodos( Grafo g ) {
      bx0 = Integer.MAX_VALUE;
      by0 = Integer.MAX_VALUE;
      bx1 = Integer.MIN_VALUE;
      by1 = Integer.MIN_VALUE;
      for ( int k = 0; k < nodos.size(); k++ ) {
        int[] c = caja( x[ k ], y[ k ], g.icono, g.rotulos.get( nodos.get( k ) ) );
        bx0 = Math.min( bx0, c[ 0 ] );
        by0 = Math.min( by0, c[ 1 ] );
        bx1 = Math.max( bx1, c[ 2 ] );
        by1 = Math.max( by1, c[ 3 ] );
      }
    }

    // ---------------------------------------------------------------- notas

    void ubicarNotas( final Grafo g, List<Integer> notas, int[][] grupo, Modo modo, int gr ) {
      if ( notas.isEmpty() ) {
        return;
      }
      Map<Integer, Integer> local = new HashMap<Integer, Integer>();
      for ( int k = 0; k < nodos.size(); k++ ) {
        local.put( nodos.get( k ), k );
      }
      // obstaculos: iconos y nombres (con aire) y hops
      List<int[]> cajas = new ArrayList<int[]>();
      for ( int k = 0; k < nodos.size(); k++ ) {
        cajas.add( new int[] { x[ k ], y[ k ], x[ k ] + g.icono, y[ k ] + g.icono } );
        int[] rot = g.rotulos.get( nodos.get( k ) );
        cajas.add( LienzoMetricas.rotulo( x[ k ], y[ k ], g.icono, rot[ 0 ], rot[ 1 ] ) );
      }
      List<int[]> segs = new ArrayList<int[]>();
      for ( int[] a : aristas ) {
        int c = g.icono / 2;
        segs.add( new int[] { x[ a[ 0 ] ] + c, y[ a[ 0 ] ] + c, x[ a[ 1 ] ] + c, y[ a[ 1 ] ] + c } );
      }
      List<int[]> puestas = new ArrayList<int[]>();
      List<Integer> orden = new ArrayList<Integer>( notas );
      Collections.sort( orden, new Comparator<Integer>() {
        @Override
        public int compare( Integer a, Integer b ) {
          int[] na = g.notas.get( a );
          int[] nb = g.notas.get( b );
          int c = Integer.compare( na[ 1 ], nb[ 1 ] );
          if ( c == 0 ) {
            c = Integer.compare( na[ 0 ], nb[ 0 ] );
          }
          return c != 0 ? c : a.compareTo( b );
        }
      } );
      int aire = Math.max( AIRE_NOTA, modo.aire / 2 );
      for ( int j : orden ) {
        int[] d = g.notas.get( j );
        int w = d[ 2 ];
        int h = d[ 3 ];
        // caja del grupo, antes y despues
        int[] gNuevo = null;
        int[] gViejo = null;
        boolean quietos = true;
        // el grupo despues de ordenar: el step mas cercano y los del grupo que quedaron a menos de 200 px de el
        int[] ancla = null;
        for ( int v : grupo[ j ] ) {
          Integer k = local.get( v );
          if ( k == null ) {
            continue;
          }
          int[] cn = caja( x[ k ], y[ k ], g.icono, g.rotulos.get( v ) );
          if ( ancla == null ) {
            ancla = cn;
          } else if ( distancia( ancla, cn ) > 200 ) {
            continue;
          }
          gNuevo = union( gNuevo, cn );
          Point p = g.actuales.get( v );
          if ( p != null ) {
            gViejo = union( gViejo, caja( p.x, p.y, g.icono, g.rotulos.get( v ) ) );
          }
        }
        if ( gNuevo == null ) {
          gNuevo = new int[] { bx0, by0, bx1, by1 };
        }
        int[] mejor = null;
        double costoMejor = Double.MAX_VALUE;
        // a. donde estaba respecto de su grupo, si estaba cerca y ahi no tapa nada
        if ( gViejo != null ) {
          int[] orig = { d[ 0 ], d[ 1 ], d[ 0 ] + w, d[ 1 ] + h };
          if ( distancia( orig, gViejo ) <= 64 ) {
            int nx = gNuevo[ 0 ] + ( d[ 0 ] - gViejo[ 0 ] );
            int ny = gNuevo[ 1 ] + ( d[ 1 ] - gViejo[ 1 ] );
            if ( gr > 1 && ( nx % gr != 0 || ny % gr != 0 ) && d[ 0 ] % gr == 0 && d[ 1 ] % gr == 0 ) {
              nx = cerca( nx, gr );
              ny = cerca( ny, gr );
            }
            if ( libre( nx, ny, w, h, cajas, segs, puestas, aire ) ) {
              mejor = new int[] { nx, ny };
              costoMejor = -1;
            }
          }
        }
        if ( mejor == null ) {
          int cx = ( gNuevo[ 0 ] + gNuevo[ 2 ] ) / 2;
          int cy = ( gNuevo[ 1 ] + gNuevo[ 3 ] ) / 2;
          // x candidatas para arriba/abajo, y candidatas para derecha/izquierda
          List<Integer> xs = deslizar( new int[] { cx - w / 2, gNuevo[ 0 ], gNuevo[ 2 ] - w }, w, gr );
          List<Integer> ys = deslizar( new int[] { gNuevo[ 1 ], cy - h / 2, gNuevo[ 3 ] - h }, h, gr );
          int topeArriba = by0 - h - 4 * aire;
          int topeAbajo = by1 + 4 * aire;
          for ( int nx : xs ) {
            double corr = 0.5 * Math.abs( nx - ( cx - w / 2 ) );
            // arriba
            for ( int ny = arribaDe( gNuevo[ 1 ] - aire - h, gr ); ny >= topeArriba - gr; ny -= gr ) {
              double c = gNuevo[ 1 ] - ( ny + h ) + corr;
              if ( c >= costoMejor ) {
                break;
              }
              if ( libre( nx, ny, w, h, cajas, segs, puestas, aire ) ) {
                costoMejor = c;
                mejor = new int[] { nx, ny };
                break;
              }
            }
            // abajo
            for ( int ny = arriba( gNuevo[ 3 ] + aire, gr ); ny <= topeAbajo + gr; ny += gr ) {
              double c = ny - gNuevo[ 3 ] + corr + 24;
              if ( c >= costoMejor ) {
                break;
              }
              if ( libre( nx, ny, w, h, cajas, segs, puestas, aire ) ) {
                costoMejor = c;
                mejor = new int[] { nx, ny };
                break;
              }
            }
          }
          for ( int ny : ys ) {
            double corr = 0.5 * Math.abs( ny - gNuevo[ 1 ] );
            // derecha
            for ( int nx = arriba( gNuevo[ 2 ] + aire, gr ); nx <= bx1 + 4 * aire + gr; nx += gr ) {
              double c = nx - gNuevo[ 2 ] + corr + 32;
              if ( c >= costoMejor ) {
                break;
              }
              if ( libre( nx, ny, w, h, cajas, segs, puestas, aire ) ) {
                costoMejor = c;
                mejor = new int[] { nx, ny };
                break;
              }
            }
            // izquierda
            for ( int nx = arribaDe( gNuevo[ 0 ] - aire - w, gr ); nx >= bx0 - w - 4 * aire - gr; nx -= gr ) {
              double c = gNuevo[ 0 ] - ( nx + w ) + corr + 48;
              if ( c >= costoMejor ) {
                break;
              }
              if ( libre( nx, ny, w, h, cajas, segs, puestas, aire ) ) {
                costoMejor = c;
                mejor = new int[] { nx, ny };
                break;
              }
            }
          }
        }
        if ( mejor == null ) {
          // siempre hay lugar arriba de todo
          int ny = arribaDe( by0 - aire - h, gr );
          int nx = arriba( gNuevo[ 0 ], gr );
          while ( !libre( nx, ny, w, h, cajas, segs, puestas, aire ) ) {
            ny -= gr;
          }
          mejor = new int[] { nx, ny };
        }
        int[] r = { mejor[ 0 ], mejor[ 1 ], mejor[ 0 ] + w, mejor[ 1 ] + h };
        puestas.add( r );
        notasUbicadas.add( new int[] { j, mejor[ 0 ], mejor[ 1 ] } );
        bx0 = Math.min( bx0, r[ 0 ] );
        by0 = Math.min( by0, r[ 1 ] );
        bx1 = Math.max( bx1, r[ 2 ] );
        by1 = Math.max( by1, r[ 3 ] );
      }
    }

    private static List<Integer> deslizar( int[] base, int largo, int gr ) {
      List<Integer> r = new ArrayList<Integer>();
      int paso = Math.max( gr, 2 * gr );
      for ( int b : base ) {
        int v = cerca( b, gr );
        if ( !r.contains( v ) ) {
          r.add( v );
        }
      }
      int b0 = cerca( base[ 0 ], gr );
      for ( int k = paso; k <= largo; k += paso ) {
        for ( int v : new int[] { b0 - k, b0 + k } ) {
          if ( !r.contains( v ) ) {
            r.add( v );
          }
        }
      }
      return r;
    }

    private static int arribaDe( int v, int gr ) {
      return gr <= 1 ? v : (int) Math.floor( v / (double) gr ) * gr;
    }

    private static int[] union( int[] a, int[] b ) {
      if ( a == null ) {
        return b.clone();
      }
      return new int[] { Math.min( a[ 0 ], b[ 0 ] ), Math.min( a[ 1 ], b[ 1 ] ), Math.max( a[ 2 ], b[ 2 ] ),
        Math.max( a[ 3 ], b[ 3 ] ) };
    }

    private static boolean libre( int nx, int ny, int w, int h, List<int[]> cajas, List<int[]> segs,
                                  List<int[]> puestas, int aire ) {
      int[] r = { nx, ny, nx + w, ny + h };
      for ( int[] c : cajas ) {
        if ( LienzoMetricas.tocan( r, c, aire ) ) {
          return false;
        }
      }
      for ( int[] p : puestas ) {
        if ( LienzoMetricas.tocan( r, p, aire ) ) {
          return false;
        }
      }
      for ( int[] s : segs ) {
        if ( LienzoMetricas.cortaRect( s[ 0 ], s[ 1 ], s[ 2 ], s[ 3 ], r, aire ) ) {
          return false;
        }
      }
      return true;
    }
  }

  private static int ox( Point[] orig, int k ) {
    return orig[ k ] == null ? Integer.MAX_VALUE : orig[ k ].x;
  }

  private static int oy( Point[] orig, int k ) {
    return orig[ k ] == null ? Integer.MAX_VALUE : orig[ k ].y;
  }

  /** DFS iterativo (los jobs pueden ser largos); 'sal' tiene indices de arista. Marca las aristas de vuelta. */
  private static void dfs( int u, List<List<Integer>> sal, List<int[]> aristas, int[] estado, boolean[] vuelta ) {
    List<int[]> pila = new ArrayList<int[]>();
    pila.add( new int[] { u, 0 } );
    estado[ u ] = 1;
    while ( !pila.isEmpty() ) {
      int[] tope = pila.get( pila.size() - 1 );
      int x = tope[ 0 ];
      if ( tope[ 1 ] < sal.get( x ).size() ) {
        int e = sal.get( x ).get( tope[ 1 ]++ );
        int v = aristas.get( e )[ 1 ];
        if ( estado[ v ] == 1 ) {
          vuelta[ e ] = true;
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

  // ================================================================ capas con nodos virtuales

  /** Nodos reales y virtuales por columna, tramos entre columnas vecinas, y el orden de cada columna. */
  private static final class Capas {
    final int m;
    final int nc;
    final List<Integer> capaDe = new ArrayList<Integer>();
    final List<Double> yIni = new ArrayList<Double>();
    /** Tramos {u, v, tipo} con capa(v) = capa(u) + 1. */
    final List<int[]> tramos = new ArrayList<int[]>();
    final List<List<Integer>> tSal = new ArrayList<List<Integer>>();
    final List<List<Integer>> tEnt = new ArrayList<List<Integer>>();
    List<List<Integer>> capas;
    int[] pos;

    Capas( int m, int nc, int[] capa, Point[] orig ) {
      this.m = m;
      this.nc = nc;
      for ( int k = 0; k < m; k++ ) {
        capaDe.add( capa[ k ] );
        yIni.add( orig[ k ] == null ? 1e9 + k : (double) orig[ k ].y );
        tSal.add( new ArrayList<Integer>() );
        tEnt.add( new ArrayList<Integer>() );
      }
    }

    int total() {
      return capaDe.size();
    }

    boolean virtual( int v ) {
      return v >= m;
    }

    /** Una arista del DAG u -> v (tipo; invertida si en el dibujo va de v a u). */
    void agregar( int u, int v, int tipo, boolean invertida ) {
      int cu = capaDe.get( u );
      int cv = capaDe.get( v );
      int previo = u;
      double y0 = yIni.get( u );
      double y1 = yIni.get( v );
      // el destino de un hop de error tira hacia abajo de su origen (en el dibujo)
      int t = tipo == ERROR ? ( invertida ? -ERROR : ERROR ) : tipo;
      for ( int c = cu + 1; c < cv; c++ ) {
        int d = capaDe.size();
        capaDe.add( c );
        yIni.add( y0 + ( y1 - y0 ) * ( c - cu ) / (double) ( cv - cu ) );
        tSal.add( new ArrayList<Integer>() );
        tEnt.add( new ArrayList<Integer>() );
        tramo( previo, d, t );
        previo = d;
      }
      tramo( previo, v, t );
    }

    private void tramo( int u, int v, int tipo ) {
      tramos.add( new int[] { u, v, tipo } );
      tSal.get( u ).add( tramos.size() - 1 );
      tEnt.get( v ).add( tramos.size() - 1 );
    }

    /** Orden de cada columna: dos arranques (el dibujo y un DFS), barrido + trasposicion; queda el de menos cruces. */
    void ordenar( Integer[] ordenEstable ) {
      final int total = total();
      // arranque 1: el dibujo
      final double[] clave = new double[ total ];
      for ( int v = 0; v < total; v++ ) {
        clave[ v ] = yIni.get( v );
      }
      int[] p1 = inicial( clave );
      // arranque 2: DFS por tramos, en el orden estable de las raices
      final double[] desc = new double[ total ];
      Arrays.fill( desc, -1 );
      int t = 0;
      for ( int r : ordenEstable ) {
        if ( desc[ r ] >= 0 ) {
          continue;
        }
        List<Integer> pila = new ArrayList<Integer>();
        pila.add( r );
        while ( !pila.isEmpty() ) {
          int u = pila.remove( pila.size() - 1 );
          if ( desc[ u ] >= 0 ) {
            continue;
          }
          desc[ u ] = t++;
          List<Integer> hijos = new ArrayList<Integer>();
          for ( int s : tSal.get( u ) ) {
            hijos.add( tramos.get( s )[ 1 ] );
          }
          for ( int s : tEnt.get( u ) ) {
            hijos.add( tramos.get( s )[ 0 ] );
          }
          Collections.sort( hijos, new Comparator<Integer>() {
            @Override
            public int compare( Integer a, Integer b ) {
              int c = Double.compare( yIni.get( b ), yIni.get( a ) ); // pila: el de mas arriba sale primero
              return c != 0 ? c : b.compareTo( a );
            }
          } );
          for ( int h : hijos ) {
            if ( desc[ h ] < 0 ) {
              pila.add( h );
            }
          }
        }
      }
      for ( int v = 0; v < total; v++ ) {
        if ( desc[ v ] < 0 ) {
          desc[ v ] = t++;
        }
      }
      int[] p2 = inicial( desc );
      int[] mejor1 = barrer( p1 );
      int[] mejor2 = barrer( p2 );
      pos = cruces( mejor2 ) < cruces( mejor1 ) ? mejor2 : mejor1;
      capas = porCapa( pos );
    }

    private int[] inicial( final double[] clave ) {
      int total = total();
      List<List<Integer>> cs = new ArrayList<List<Integer>>();
      for ( int c = 0; c < nc; c++ ) {
        cs.add( new ArrayList<Integer>() );
      }
      for ( int v = 0; v < total; v++ ) {
        cs.get( capaDe.get( v ) ).add( v );
      }
      int[] p = new int[ total ];
      for ( List<Integer> l : cs ) {
        Collections.sort( l, new Comparator<Integer>() {
          @Override
          public int compare( Integer a, Integer b ) {
            int c = Double.compare( clave[ a ], clave[ b ] );
            return c != 0 ? c : a.compareTo( b );
          }
        } );
        for ( int k = 0; k < l.size(); k++ ) {
          p[ l.get( k ) ] = k;
        }
      }
      return p;
    }

    private List<List<Integer>> porCapa( final int[] p ) {
      List<List<Integer>> cs = new ArrayList<List<Integer>>();
      for ( int c = 0; c < nc; c++ ) {
        cs.add( new ArrayList<Integer>() );
      }
      for ( int v = 0; v < total(); v++ ) {
        cs.get( capaDe.get( v ) ).add( v );
      }
      for ( List<Integer> l : cs ) {
        Collections.sort( l, new Comparator<Integer>() {
          @Override
          public int compare( Integer a, Integer b ) {
            return Integer.compare( p[ a ], p[ b ] );
          }
        } );
      }
      return cs;
    }

    private int[] barrer( int[] inicio ) {
      int[] p = inicio.clone();
      List<List<Integer>> cs = porCapa( p );
      int[] mejor = p.clone();
      long cMejor = cruces( p );
      for ( int it = 0; it < 16 && cMejor > 0; it++ ) {
        for ( int c = 1; c < nc; c++ ) {
          baricentro( cs.get( c ), p, true );
        }
        trasponer( cs, p );
        for ( int c = nc - 2; c >= 0; c-- ) {
          baricentro( cs.get( c ), p, false );
        }
        trasponer( cs, p );
        long cr = cruces( p );
        if ( cr < cMejor ) {
          cMejor = cr;
          mejor = p.clone();
        }
      }
      return mejor;
    }

    /** Reordena una columna por el baricentro de sus vecinos en la columna anterior (o siguiente). */
    private void baricentro( List<Integer> capa, final int[] p, boolean haciaAtras ) {
      final double[] clave = new double[ total() ];
      for ( int v : capa ) {
        double suma = 0;
        int n = 0;
        for ( int s : haciaAtras ? tEnt.get( v ) : tSal.get( v ) ) {
          int[] tr = tramos.get( s );
          int w = haciaAtras ? tr[ 0 ] : tr[ 1 ];
          double sesgo = 0;
          if ( tr[ 2 ] == ERROR ) {
            sesgo = haciaAtras ? 0.45 : -0.45; // el destino del error abajo de su origen
          } else if ( tr[ 2 ] == -ERROR ) {
            sesgo = haciaAtras ? -0.45 : 0.45;
          }
          suma += p[ w ] + sesgo;
          n++;
        }
        clave[ v ] = n == 0 ? p[ v ] : suma / n;
      }
      Collections.sort( capa, new Comparator<Integer>() {
        @Override
        public int compare( Integer a, Integer b ) {
          int c = Double.compare( clave[ a ], clave[ b ] );
          return c != 0 ? c : Integer.compare( p[ a ], p[ b ] );
        }
      } );
      for ( int k = 0; k < capa.size(); k++ ) {
        p[ capa.get( k ) ] = k;
      }
    }

    /** Cambia vecinos de lugar mientras baje la cantidad de cruces con las dos columnas de al lado. */
    private void trasponer( List<List<Integer>> cs, int[] p ) {
      boolean mejoro = true;
      for ( int vuelta = 0; vuelta < 8 && mejoro; vuelta++ ) {
        mejoro = false;
        for ( List<Integer> capa : cs ) {
          for ( int k = 0; k + 1 < capa.size(); k++ ) {
            int a = capa.get( k );
            int b = capa.get( k + 1 );
            if ( crucesPar( b, a, p ) < crucesPar( a, b, p ) ) {
              capa.set( k, b );
              capa.set( k + 1, a );
              p[ a ] = k + 1;
              p[ b ] = k;
              mejoro = true;
            }
          }
        }
      }
    }

    /** Cruces entre los tramos de a y los de b si a queda arriba de b. */
    private int crucesPar( int a, int b, int[] p ) {
      int c = 0;
      for ( int sa : tEnt.get( a ) ) {
        for ( int sb : tEnt.get( b ) ) {
          if ( p[ tramos.get( sa )[ 0 ] ] > p[ tramos.get( sb )[ 0 ] ] ) {
            c++;
          }
        }
      }
      for ( int sa : tSal.get( a ) ) {
        for ( int sb : tSal.get( b ) ) {
          if ( p[ tramos.get( sa )[ 1 ] ] > p[ tramos.get( sb )[ 1 ] ] ) {
            c++;
          }
        }
      }
      return c;
    }

    long cruces( int[] p ) {
      long c = 0;
      List<List<Integer>> porCapa = new ArrayList<List<Integer>>();
      for ( int k = 0; k < nc; k++ ) {
        porCapa.add( new ArrayList<Integer>() );
      }
      for ( int s = 0; s < tramos.size(); s++ ) {
        porCapa.get( capaDe.get( tramos.get( s )[ 0 ] ) ).add( s );
      }
      for ( List<Integer> l : porCapa ) {
        for ( int i = 0; i < l.size(); i++ ) {
          int[] a = tramos.get( l.get( i ) );
          for ( int j = i + 1; j < l.size(); j++ ) {
            int[] b = tramos.get( l.get( j ) );
            if ( (long) ( p[ a[ 0 ] ] - p[ b[ 0 ] ] ) * ( p[ a[ 1 ] ] - p[ b[ 1 ] ] ) < 0 ) {
              c++;
            }
          }
        }
      }
      return c;
    }

    /**
     * Carriles por prioridades (Sugiyama): pasadas de ida y vuelta; cada nodo quiere el carril de la mediana de sus
     * vecinos y empuja a los de menor prioridad. Los virtuales tienen la maxima (hops largos rectos). Devuelve el
     * carril de cada nodo real.
     */
    int[] prioridades() {
      int total = total();
      int[] lane = new int[ total ];
      for ( List<Integer> capa : capas ) {
        for ( int k = 0; k < capa.size(); k++ ) {
          lane[ capa.get( k ) ] = k;
        }
      }
      for ( int it = 0; it < 4; it++ ) {
        for ( int c = 1; c < nc; c++ ) {
          pasada( capas.get( c ), lane, true );
        }
        for ( int c = nc - 2; c >= 0; c-- ) {
          pasada( capas.get( c ), lane, false );
        }
      }
      for ( int c = 1; c < nc; c++ ) {
        pasada( capas.get( c ), lane, true );
      }
      return Arrays.copyOf( lane, m );
    }

    private void pasada( List<Integer> capa, int[] lane, boolean haciaAtras ) {
      final int s = capa.size();
      final int[] prio = new int[ s ];
      final int[] objetivo = new int[ s ];
      final boolean[] tiene = new boolean[ s ];
      for ( int k = 0; k < s; k++ ) {
        int v = capa.get( k );
        List<Integer> ref = haciaAtras ? tEnt.get( v ) : tSal.get( v );
        List<Double> vals = new ArrayList<Double>();
        List<Double> normales = new ArrayList<Double>();
        for ( int t : ref ) {
          int[] tr = tramos.get( t );
          int w = haciaAtras ? tr[ 0 ] : tr[ 1 ];
          double val = lane[ w ];
          int tipo = tr[ 2 ];
          if ( tipo == ERROR ) {
            val += haciaAtras ? 1 : -1;
          } else if ( tipo == -ERROR ) {
            val += haciaAtras ? -1 : 1;
          }
          vals.add( val );
          if ( tipo == NORMAL ) {
            normales.add( val );
          }
        }
        List<Double> usar = normales.isEmpty() ? vals : normales;
        if ( usar.isEmpty() ) {
          prio[ k ] = -1;
          continue;
        }
        Collections.sort( usar );
        int z = usar.size();
        double med = z % 2 == 1 ? usar.get( z / 2 ) : ( usar.get( z / 2 - 1 ) + usar.get( z / 2 ) ) / 2;
        objetivo[ k ] = (int) Math.floor( med + 0.5 );
        tiene[ k ] = true;
        prio[ k ] = virtual( v ) ? 1_000_000 : ref.size();
      }
      Integer[] porPrio = new Integer[ s ];
      for ( int k = 0; k < s; k++ ) {
        porPrio[ k ] = k;
      }
      Arrays.sort( porPrio, new Comparator<Integer>() {
        @Override
        public int compare( Integer a, Integer b ) {
          int c = Integer.compare( prio[ b ], prio[ a ] );
          return c != 0 ? c : a.compareTo( b );
        }
      } );
      boolean[] fijo = new boolean[ s ];
      for ( int idx : porPrio ) {
        int v = capa.get( idx );
        if ( tiene[ idx ] ) {
          int t = objetivo[ idx ];
          if ( t > lane[ v ] ) {
            int p = t;
            for ( int j = idx + 1; j < s; j++ ) {
              if ( fijo[ j ] ) {
                p = Math.min( p, lane[ capa.get( j ) ] - ( j - idx ) );
                break;
              }
            }
            if ( p > lane[ v ] ) {
              lane[ v ] = p;
              for ( int j = idx + 1; j < s; j++ ) {
                int u = capa.get( j );
                int need = p + ( j - idx );
                if ( lane[ u ] < need ) {
                  lane[ u ] = need;
                } else {
                  break;
                }
              }
            }
          } else if ( t < lane[ v ] ) {
            int p = t;
            for ( int j = idx - 1; j >= 0; j-- ) {
              if ( fijo[ j ] ) {
                p = Math.max( p, lane[ capa.get( j ) ] + ( idx - j ) );
                break;
              }
            }
            if ( p < lane[ v ] ) {
              lane[ v ] = p;
              for ( int j = idx - 1; j >= 0; j-- ) {
                int u = capa.get( j );
                int need = p - ( idx - j );
                if ( lane[ u ] > need ) {
                  lane[ u ] = need;
                } else {
                  break;
                }
              }
            }
          }
        }
        fijo[ idx ] = true;
      }
    }
  }

  // ================================================================ busqueda local

  /**
   * Mejora los carriles de una componente sobre la geometria que pinta Spoon: mueve un step (o un tramo recto entero)
   * a otro carril de su columna, intercambiando si esta ocupado, mientras baje el costo. Determinista: recorre los
   * nodos en orden de columna y carril, prueba los carriles de arriba a abajo y se queda con la mejor mejora.
   */
  private static final class Busqueda {
    final Comp c;
    final int m;
    final int icono;
    final int[] colX;
    final int pasoY;
    final int[][] rot;
    final int[][] ar;
    final List<List<Integer>> inc = new ArrayList<List<Integer>>();
    final Map<Long, Integer> celda = new HashMap<Long, Integer>();
    long gastado;

    Busqueda( Comp c, Grafo g, int[] colX, int pasoY ) {
      this.c = c;
      this.m = c.nodos.size();
      this.icono = g.icono;
      this.colX = colX;
      this.pasoY = pasoY;
      rot = new int[ m ][];
      for ( int k = 0; k < m; k++ ) {
        rot[ k ] = g.rotulos.get( c.nodos.get( k ) );
        inc.add( new ArrayList<Integer>() );
      }
      ar = new int[ c.aristas.size() ][];
      for ( int e = 0; e < ar.length; e++ ) {
        ar[ e ] = c.aristas.get( e );
        inc.get( ar[ e ][ 0 ] ).add( e );
        inc.get( ar[ e ][ 1 ] ).add( e );
      }
      for ( int k = 0; k < m; k++ ) {
        celda.put( clave( c.col[ k ], c.carril[ k ] ), k );
      }
    }

    static long clave( int col, int lane ) {
      return ( (long) col << 32 ) ^ ( lane & 0xffffffffL );
    }

    double cx( int v ) {
      return colX[ c.col[ v ] ] + icono / 2.0;
    }

    double cy( int v ) {
      return c.carril[ v ] * (double) pasoY + icono / 2.0;
    }

    int[] cajaIcono( int v ) {
      int x = colX[ c.col[ v ] ];
      int y = c.carril[ v ] * pasoY;
      return new int[] { x, y, x + icono, y + icono };
    }

    int[] cajaRotulo( int v ) {
      return LienzoMetricas.rotulo( colX[ c.col[ v ] ], c.carril[ v ] * pasoY, icono, rot[ v ][ 0 ], rot[ v ][ 1 ] );
    }

    double torcido( int e ) {
      int[] a = ar[ e ];
      int d = c.carril[ a[ 1 ] ] - c.carril[ a[ 0 ] ];
      int ad = Math.abs( d );
      int largo = Math.abs( c.col[ a[ 1 ] ] - c.col[ a[ 0 ] ] );
      switch ( a[ 2 ] ) {
        case INFO:
          return ad == 0 ? P_CARRIL * 0.5 : P_CARRIL * 0.5 * ( ad - 1 );
        case ERROR:
          return d > 0 ? P_CARRIL * 0.5 * ( d - 1 ) : P_CARRIL * ( 1.5 + ad );
        default:
          return P_CARRIL * ad + ( ad > 0 && largo > 1 ? P_CARRIL * 0.25 * ( largo - 1 ) : 0 );
      }
    }

    double cruce( int e, int f ) {
      int[] a = ar[ e ];
      int[] b = ar[ f ];
      if ( a[ 0 ] == b[ 0 ] || a[ 0 ] == b[ 1 ] || a[ 1 ] == b[ 0 ] || a[ 1 ] == b[ 1 ] ) {
        return 0;
      }
      gastado++;
      return LienzoMetricas.cruzan( cx( a[ 0 ] ), cy( a[ 0 ] ), cx( a[ 1 ] ), cy( a[ 1 ] ), cx( b[ 0 ] ), cy( b[ 0 ] ),
        cx( b[ 1 ] ), cy( b[ 1 ] ) ) ? P_CRUCE : 0;
    }

    double pisa( int e, int v ) {
      int[] a = ar[ e ];
      if ( v == a[ 0 ] || v == a[ 1 ] ) {
        return 0;
      }
      gastado++;
      double x1 = cx( a[ 0 ] );
      double y1 = cy( a[ 0 ] );
      double x2 = cx( a[ 1 ] );
      double y2 = cy( a[ 1 ] );
      if ( LienzoMetricas.cortaRect( x1, y1, x2, y2, cajaIcono( v ), AIRE_ICONO ) ) {
        return P_PASO;
      }
      return LienzoMetricas.cortaRect( x1, y1, x2, y2, cajaRotulo( v ), 2 ) ? P_ROTULO : 0;
    }

    double alto() {
      int min = Integer.MAX_VALUE;
      int max = Integer.MIN_VALUE;
      for ( int k = 0; k < m; k++ ) {
        min = Math.min( min, c.carril[ k ] );
        max = Math.max( max, c.carril[ k ] );
      }
      return P_ALTO * ( max - min );
    }

    /** El costo de todo lo que toca a los nodos 'movidos' (sus aristas contra todo, y todo contra ellos). */
    double parcial( int[] movidos ) {
      boolean[] esMovido = new boolean[ m ];
      for ( int v : movidos ) {
        esMovido[ v ] = true;
      }
      boolean[] afectada = new boolean[ ar.length ];
      List<Integer> afectadas = new ArrayList<Integer>();
      for ( int v : movidos ) {
        for ( int e : inc.get( v ) ) {
          if ( !afectada[ e ] ) {
            afectada[ e ] = true;
            afectadas.add( e );
          }
        }
      }
      double s = 0;
      for ( int e : afectadas ) {
        s += torcido( e );
        for ( int f = 0; f < ar.length; f++ ) {
          if ( f == e || afectada[ f ] && f < e ) {
            continue;
          }
          s += cruce( e, f );
        }
        for ( int w = 0; w < m; w++ ) {
          s += pisa( e, w );
        }
      }
      for ( int v : movidos ) {
        for ( int f = 0; f < ar.length; f++ ) {
          if ( !afectada[ f ] ) {
            s += pisa( f, v );
          }
        }
      }
      return s;
    }

    double total() {
      double s = alto();
      for ( int e = 0; e < ar.length; e++ ) {
        s += torcido( e );
        for ( int f = e + 1; f < ar.length; f++ ) {
          s += cruce( e, f );
        }
        for ( int w = 0; w < m; w++ ) {
          s += pisa( e, w );
        }
      }
      return s;
    }

    /** Mueve los nodos a los carriles dados (sin chequear), actualizando las celdas. */
    void poner( int[] nodos, int[] carriles ) {
      for ( int v : nodos ) {
        celda.remove( clave( c.col[ v ], c.carril[ v ] ) );
      }
      for ( int k = 0; k < nodos.length; k++ ) {
        c.carril[ nodos[ k ] ] = carriles[ k ];
      }
      for ( int v : nodos ) {
        celda.put( clave( c.col[ v ], c.carril[ v ] ), v );
      }
    }

    /** Delta de costo de llevar 'nodos' a 'carriles' (deja todo como estaba). */
    double probar( int[] nodos, int[] carriles ) {
      int[] antes = new int[ nodos.length ];
      for ( int k = 0; k < nodos.length; k++ ) {
        antes[ k ] = c.carril[ nodos[ k ] ];
      }
      double a = parcial( nodos ) + alto();
      poner( nodos, carriles );
      double d = parcial( nodos ) + alto();
      poner( nodos, antes );
      return d - a;
    }

    /** El tramo recto que contiene a v: los vecinos por hops horizontales, transitivamente. */
    int[] tramoRecto( int v ) {
      List<Integer> l = new ArrayList<Integer>();
      boolean[] visto = new boolean[ m ];
      l.add( v );
      visto[ v ] = true;
      for ( int q = 0; q < l.size(); q++ ) {
        int u = l.get( q );
        for ( int e : inc.get( u ) ) {
          int w = ar[ e ][ 0 ] == u ? ar[ e ][ 1 ] : ar[ e ][ 0 ];
          if ( !visto[ w ] && c.carril[ w ] == c.carril[ u ] ) {
            visto[ w ] = true;
            l.add( w );
          }
        }
      }
      Collections.sort( l );
      int[] r = new int[ l.size() ];
      for ( int k = 0; k < r.length; k++ ) {
        r[ k ] = l.get( k );
      }
      return r;
    }

    void correr() {
      if ( m <= 1 ) {
        return;
      }
      for ( int barrido = 0; barrido < 40 && gastado < PRESUPUESTO; barrido++ ) {
        boolean mejoro = false;
        Integer[] orden = new Integer[ m ];
        for ( int k = 0; k < m; k++ ) {
          orden[ k ] = k;
        }
        Arrays.sort( orden, new Comparator<Integer>() {
          @Override
          public int compare( Integer a, Integer b ) {
            int r = Integer.compare( c.col[ a ], c.col[ b ] );
            if ( r == 0 ) {
              r = Integer.compare( c.carril[ a ], c.carril[ b ] );
            }
            return r != 0 ? r : a.compareTo( b );
          }
        } );
        for ( int v : orden ) {
          if ( gastado >= PRESUPUESTO ) {
            break;
          }
          int min = Integer.MAX_VALUE;
          int max = Integer.MIN_VALUE;
          for ( int k = 0; k < m; k++ ) {
            min = Math.min( min, c.carril[ k ] );
            max = Math.max( max, c.carril[ k ] );
          }
          double mejorDelta = -1e-6;
          int[] mejorNodos = null;
          int[] mejorCarriles = null;
          // un step solo (intercambiando si el carril esta ocupado)
          for ( int q = min - 2; q <= max + 2; q++ ) {
            if ( q == c.carril[ v ] ) {
              continue;
            }
            Integer w = celda.get( clave( c.col[ v ], q ) );
            int[] ns = w == null ? new int[] { v } : new int[] { v, w };
            int[] cs = w == null ? new int[] { q } : new int[] { q, c.carril[ v ] };
            double d = probar( ns, cs );
            if ( d < mejorDelta ) {
              mejorDelta = d;
              mejorNodos = ns;
              mejorCarriles = cs;
            }
          }
          // el tramo recto entero, a carriles libres
          int[] tramo = tramoRecto( v );
          if ( tramo.length > 1 && tramo[ 0 ] == v ) {
            for ( int dq = min - 2 - c.carril[ v ]; dq <= max + 2 - c.carril[ v ]; dq++ ) {
              if ( dq == 0 ) {
                continue;
              }
              boolean ok = true;
              for ( int u : tramo ) {
                Integer w = celda.get( clave( c.col[ u ], c.carril[ u ] + dq ) );
                if ( w != null && Arrays.binarySearch( tramo, w ) < 0 ) {
                  ok = false;
                  break;
                }
              }
              if ( !ok ) {
                continue;
              }
              int[] cs = new int[ tramo.length ];
              for ( int k = 0; k < tramo.length; k++ ) {
                cs[ k ] = c.carril[ tramo[ k ] ] + dq;
              }
              double d = probar( tramo, cs );
              if ( d < mejorDelta ) {
                mejorDelta = d;
                mejorNodos = tramo;
                mejorCarriles = cs;
              }
            }
          }
          if ( mejorNodos != null ) {
            poner( mejorNodos, mejorCarriles );
            mejoro = true;
          }
        }
        if ( !mejoro ) {
          break;
        }
      }
    }

  }
}
