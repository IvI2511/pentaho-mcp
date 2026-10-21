package io.github.ivi2511.pentahomcp.informes.diagramas;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Arista;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Grupo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Nodo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Unidad;

/**
 * Partir en vez de achicar: si en la caja del objetivo la letra queda chica, el diagrama se divide en
 * varios (una figura o diapositiva cada uno).
 * <ul>
 * <li>Linaje (por debajo de la letra ideal): racimos de campos de salida que comparten algun antecesor; se
 * arman diagramas metiendo racimos en orden mientras la letra no baje de la ideal (si salen mas de 4, con la
 * minima). Un racimo que solo ya no entra se parte campo a campo, solo si eso agranda la letra un 15 %.
 * Se parte solo si la peor parte queda al menos 1,10 veces mas grande que el entero.</li>
 * <li>Flujo y job (por debajo de la minima): paginas de capas seguidas, parejas en cantidad de steps, y la
 * misma particion con cada corte corrido una capa; vale la primera cantidad de paginas que llega a la
 * minima, o la mejor si gana al menos un 15 %. Cada salto entre paginas deja un "fantasma" punteado del
 * step del otro lado ("sigue en 2/3", "viene de 1/3").</li>
 * </ul>
 *
 * @dueño WP6
 */
final class Partir {

  static final int MAX_PARTES_LINAJE = 4;
  static final int MAX_PAGINAS_FLUJO = 6;

  private Partir() {
  }

  /** Letra efectiva del grafo en el objetivo (con la leyenda). */
  static double letra( Grafo g, Objetivo obj, Rotulos rot ) {
    double extra = DiagramaSvg.altoLeyenda( g, rot );
    Disposicion.Eleccion e = Disposicion.elegir( g, obj, extra );
    return obj.letra( e.d.ancho, e.d.alto + extra );
  }

  // ================================================================== linaje

  static List<Grafo> linaje( Grafo g, Objetivo obj, Rotulos rot ) {
    List<Grafo> uno = new ArrayList<>();
    uno.add( g );
    Grupo destino = g.destino();
    if ( destino == null || destino.nodos.size() < 2 ) {
      return uno;
    }
    double letra = letra( g, obj, rot );
    if ( letra >= obj.letraIdeal ) {
      return uno;
    }
    List<Nodo> salida = new ArrayList<>( destino.nodos );
    Map<Nodo, Set<Nodo>> ant = new IdentityHashMap<>();
    for ( Nodo n : salida ) {
      ant.put( n, antecesores( g, n ) );
    }
    // racimos (union-find): dos campos de salida van juntos si comparten algun antecesor
    int k = salida.size();
    int[] padre = new int[ k ];
    for ( int i = 0; i < k; i++ ) {
      padre[ i ] = i;
    }
    for ( int i = 0; i < k; i++ ) {
      for ( int j = i + 1; j < k; j++ ) {
        for ( Nodo x : ant.get( salida.get( i ) ) ) {
          if ( x.grupo != destino && ant.get( salida.get( j ) ).contains( x ) ) {
            unir( padre, i, j );
            break;
          }
        }
      }
    }
    Map<Integer, List<Nodo>> racimos = new LinkedHashMap<>();
    for ( int i = 0; i < k; i++ ) {
      Integer r = raiz( padre, i );
      List<Nodo> l = racimos.get( r );
      if ( l == null ) {
        l = new ArrayList<>();
        racimos.put( r, l );
      }
      l.add( salida.get( i ) );
    }
    List<List<Nodo>> rs = new ArrayList<>( racimos.values() );
    List<List<Nodo>> packs = empacar( g, rs, ant, obj, obj.letraIdeal, rot );
    if ( packs.size() > MAX_PARTES_LINAJE ) {
      packs = empacar( g, rs, ant, obj, obj.letraMin, rot );
    }
    double peor = peor( g, packs, ant, obj, rot );
    // el empaque goloso puede dejar una ultima parte minima: se prueba tambien repartir los racimos en la
    // misma cantidad de partes parejas (por cantidad de nodos) y queda la de mejor letra en la peor parte
    if ( packs.size() >= 2 && rs.size() >= packs.size() ) {
      List<List<Nodo>> parejas = parejas( rs, ant, packs.size() );
      double peorParejas = peor( g, parejas, ant, obj, rot );
      if ( peorParejas >= peor * 0.98 ) {
        packs = parejas;
        peor = peorParejas;
      }
    }
    if ( packs.size() <= 1 || peor < letra * 1.10 ) {
      return uno;
    }
    List<Grafo> out = new ArrayList<>();
    for ( List<Nodo> p : packs ) {
      out.add( sub( g, p, ant ) );
    }
    return out;
  }

  private static double peor( Grafo g, List<List<Nodo>> packs, Map<Nodo, Set<Nodo>> ant, Objetivo obj,
                              Rotulos rot ) {
    double peor = Double.MAX_VALUE;
    for ( List<Nodo> p : packs ) {
      peor = Math.min( peor, letra( sub( g, p, ant ), obj, rot ) );
    }
    return peor;
  }

  /** Los racimos, en orden, en p partes seguidas con la cantidad de nodos lo mas pareja posible. */
  private static List<List<Nodo>> parejas( List<List<Nodo>> rs, Map<Nodo, Set<Nodo>> ant, int p ) {
    double[] peso = new double[ rs.size() ];
    for ( int i = 0; i < rs.size(); i++ ) {
      Set<Nodo> s = new LinkedHashSet<>( rs.get( i ) );
      for ( Nodo n : rs.get( i ) ) {
        s.addAll( ant.get( n ) );
      }
      peso[ i ] = s.size();
    }
    int[] ini = Envoltura.particion( peso, p );
    List<List<Nodo>> out = new ArrayList<>();
    for ( int k = 0; k < p; k++ ) {
      int fin = k + 1 < p ? ini[ k + 1 ] : rs.size();
      List<Nodo> pack = new ArrayList<>();
      for ( int i = ini[ k ]; i < fin; i++ ) {
        pack.addAll( rs.get( i ) );
      }
      if ( !pack.isEmpty() ) {
        out.add( pack );
      }
    }
    return out;
  }

  private static List<List<Nodo>> empacar( Grafo g, List<List<Nodo>> rs, Map<Nodo, Set<Nodo>> ant, Objetivo obj,
                                           double umbral, Rotulos rot ) {
    List<List<Nodo>> out = new ArrayList<>();
    List<Nodo> pack = new ArrayList<>();
    for ( List<Nodo> r : rs ) {
      List<Nodo> tent = new ArrayList<>( pack );
      tent.addAll( r );
      if ( letra( sub( g, tent, ant ), obj, rot ) >= umbral ) {
        pack = tent;
        continue;
      }
      if ( !pack.isEmpty() ) {
        out.add( pack );
      }
      pack = new ArrayList<>( r );
      double solo = r.size() > 1 ? letra( sub( g, pack, ant ), obj, rot ) : Double.MAX_VALUE;
      List<Nodo> uno = new ArrayList<>();
      uno.add( r.get( 0 ) );
      // partir el racimo campo a campo solo si de verdad agranda la letra (si lo que manda es el largo, por
      // la cantidad de capas, no gana nada)
      if ( solo < umbral && letra( sub( g, uno, ant ), obj, rot ) >= solo * 1.15 ) {
        pack = new ArrayList<>();
        for ( Nodo n : r ) {
          List<Nodo> t2 = new ArrayList<>( pack );
          t2.add( n );
          if ( pack.isEmpty() || letra( sub( g, t2, ant ), obj, rot ) >= umbral ) {
            pack = t2;
          } else {
            out.add( pack );
            pack = new ArrayList<>();
            pack.add( n );
          }
        }
      }
    }
    if ( !pack.isEmpty() ) {
      out.add( pack );
    }
    return out;
  }

  static Set<Nodo> antecesores( Grafo g, Nodo n ) {
    Set<Nodo> s = new LinkedHashSet<>();
    Deque<Nodo> q = new ArrayDeque<>();
    q.add( n );
    while ( !q.isEmpty() ) {
      Nodo x = q.poll();
      for ( Arista e : g.aristas ) {
        if ( e.b == x && s.add( e.a ) ) {
          q.add( e.a );
        }
      }
    }
    return s;
  }

  /** Copia del linaje con solo esos campos de salida y sus antecesores. */
  static Grafo sub( Grafo g, List<Nodo> campos, Map<Nodo, Set<Nodo>> ant ) {
    Set<Nodo> quedan = new LinkedHashSet<>( campos );
    for ( Nodo n : campos ) {
      quedan.addAll( ant.get( n ) );
    }
    Grafo s = g.vacioComo();
    Map<Nodo, Nodo> copia = new IdentityHashMap<>();
    for ( Grupo gr : g.grupos ) {
      Grupo ng = null;
      for ( Nodo n : gr.nodos ) {
        if ( !quedan.contains( n ) ) {
          continue;
        }
        if ( ng == null ) {
          ng = s.grupo( gr.nombre );
          ng.op = gr.op;
          ng.destino = gr.destino;
        }
        Nodo m = s.campo( ng, n.id, n.titulo );
        m.op = n.op;
        m.doc = n.doc;
        copia.put( n, m );
      }
    }
    for ( Nodo n : g.nodos ) {
      if ( n.grupo == null && quedan.contains( n ) ) {
        Nodo m = s.nodo( n.id, n.titulo );
        m.op = n.op;
        m.doc = n.doc;
        copia.put( n, m );
      }
    }
    for ( Arista e : g.aristas ) {
      if ( copia.containsKey( e.a ) && copia.containsKey( e.b ) ) {
        Arista x = s.arista( copia.get( e.a ), copia.get( e.b ), e.clase );
        x.rotulo = e.rotulo;
        x.activa = e.activa;
      }
    }
    return s;
  }

  // ================================================================== flujo

  static List<Grafo> flujo( Grafo g, Objetivo obj, Rotulos rot ) {
    List<Grafo> uno = new ArrayList<>();
    uno.add( g );
    if ( g.nodos.size() < 4 ) {
      return uno;
    }
    double letra = letra( g, obj, rot );
    if ( letra >= obj.letraMin ) {
      return uno;
    }
    Tamanos.medir( g );
    Capas c = Capas.construir( g );
    Orden.minimizar( c );
    Tamanos.orientar( c, false );
    // peso de cada capa = steps reales (lo que ocupa lugar; cada pagina se vuelve a envolver)
    int n = c.n();
    double[] peso = new double[ n ];
    for ( int l = 0; l < n; l++ ) {
      for ( Unidad u : c.capas.get( l ) ) {
        peso[ l ] += u.ficticia() ? 0.15 : 1;
      }
    }
    Map<Nodo, Integer> capaDe = new IdentityHashMap<>();
    for ( Nodo x : g.nodos ) {
      capaDe.put( x, x.capa );
    }
    List<Grafo> ultimo = uno;
    double mejorLetra = letra;
    for ( int p = 2; p <= Math.min( MAX_PAGINAS_FLUJO, n ); p++ ) {
      int[] base = Envoltura.particion( peso, p );
      // la particion pareja y la misma con un corte corrido una capa: gana la de mejor letra en la peor pagina
      List<int[]> candidatos = new ArrayList<>();
      candidatos.add( base );
      for ( int k = 1; k < p; k++ ) {
        for ( int dd = -1; dd <= 1; dd += 2 ) {
          int[] x = base.clone();
          x[ k ] += dd;
          if ( x[ k ] > x[ k - 1 ] && ( k + 1 == p ? x[ k ] < n : x[ k ] < x[ k + 1 ] ) ) {
            candidatos.add( x );
          }
        }
      }
      double mejor = -1;
      List<Grafo> mejores = null;
      for ( int[] ini : candidatos ) {
        List<Grafo> pags = paginas( g, ini, capaDe, rot );
        double peor = Double.MAX_VALUE;
        for ( Grafo x : pags ) {
          peor = Math.min( peor, letra( x, obj, rot ) );
        }
        if ( peor > mejor ) {
          mejor = peor;
          mejores = pags;
        }
      }
      if ( mejor >= obj.letraMin ) {
        return mejores;
      }
      if ( mejor > mejorLetra ) {
        mejorLetra = mejor;
        ultimo = mejores;
      }
    }
    // ninguna llega a la minima: solo se parte si de verdad agranda la letra
    return mejorLetra >= letra * 1.15 ? ultimo : uno;
  }

  private static List<Grafo> paginas( Grafo g, int[] ini, Map<Nodo, Integer> capaDe, Rotulos rot ) {
    int p = ini.length;
    Map<Nodo, Integer> pag = new IdentityHashMap<>();
    for ( Nodo n : g.nodos ) {
      int k = 0;
      int capa = capaDe.get( n );
      while ( k + 1 < p && capa >= ini[ k + 1 ] ) {
        k++;
      }
      pag.put( n, k );
    }
    List<Grafo> out = new ArrayList<>();
    List<Map<Nodo, Nodo>> copias = new ArrayList<>();
    for ( int k = 0; k < p; k++ ) {
      out.add( g.vacioComo() );
      copias.add( new IdentityHashMap<Nodo, Nodo>() );
    }
    for ( Nodo n : g.nodos ) {
      int k = pag.get( n );
      Nodo m = n.copiar( n.id );
      out.get( k ).agregar( m );
      copias.get( k ).put( n, m );
    }
    for ( Arista e : g.aristas ) {
      int ka = pag.get( e.a );
      int kb = pag.get( e.b );
      if ( ka == kb ) {
        Arista x = out.get( ka ).arista( copias.get( ka ).get( e.a ), copias.get( ka ).get( e.b ), e.clase );
        x.rotulo = e.rotulo;
        x.activa = e.activa;
        continue;
      }
      // en la pagina del origen: fantasma del destino; en la del destino: fantasma del origen
      Nodo fb = fantasma( out.get( ka ), e.b, rot.f( "fantasma.sigue", kb + 1, p ) );
      Arista x = out.get( ka ).arista( copias.get( ka ).get( e.a ), fb, e.clase );
      x.rotulo = e.rotulo;
      x.activa = e.activa;
      Nodo fa = fantasma( out.get( kb ), e.a, rot.f( "fantasma.viene", ka + 1, p ) );
      Arista y = out.get( kb ).arista( fa, copias.get( kb ).get( e.b ), e.clase );
      y.rotulo = e.rotulo;
      y.activa = e.activa;
    }
    return out;
  }

  private static Nodo fantasma( Grafo s, Nodo n, String linea ) {
    Nodo ya = s.buscar( "~" + n.id );
    if ( ya != null ) {
      return ya;
    }
    Nodo f = s.nodo( "~" + n.id, n.titulo );
    f.lineas.add( linea );
    f.fantasma = true;
    f.doc = n.doc;
    return f;
  }

  private static int raiz( int[] p, int i ) {
    while ( p[ i ] != i ) {
      p[ i ] = p[ p[ i ] ];
      i = p[ i ];
    }
    return i;
  }

  private static void unir( int[] p, int a, int b ) {
    p[ raiz( p, a ) ] = raiz( p, b );
  }
}
