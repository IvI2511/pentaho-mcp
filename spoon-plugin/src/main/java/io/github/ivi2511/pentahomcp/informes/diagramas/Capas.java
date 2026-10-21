package io.github.ivi2511.pentahomcp.informes.diagramas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Arista;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Grupo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Nodo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Tramo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Unidad;

/**
 * Paso 1 de la disposicion: unidades, ciclos, capas y nodos ficticios.
 * <ol>
 * <li>Unidad = el grupo (step del linaje: todos sus campos van en la misma capa) o el nodo suelto.</li>
 * <li>Ciclos (jobs con bucles): DFS en orden de documento; la arista que vuelve a la pila se invierte para
 * la disposicion (se dibuja con la punta en su destino original).</li>
 * <li>Capa = camino mas largo desde las fuentes; despues "compactar": cada unidad se mueve dentro de
 * [max(capa pred)+1, min(capa suc)-1] hacia el lado con mas aristas (acerca las fuentes a quien las usa:
 * "Leer ventas" queda junto a "Unir ventas").</li>
 * <li>Aristas de mas de una capa: cadena de ficticios. Concentracion: si un destino recibe 2 o mas aristas
 * largas de la misma clase comparten UNA cadena (un "bus": los 8 "si falla" de un job); si no, si un
 * origen manda 2 o mas aristas largas de la misma clase, comparten la cadena desde el origen.</li>
 * </ol>
 *
 * @dueño WP6
 */
final class Capas {

  final Grafo g;
  final List<Unidad> unidades = new ArrayList<>();
  final List<List<Unidad>> capas = new ArrayList<>();
  final List<Tramo> tramos = new ArrayList<>();
  final List<Nodo> ficticios = new ArrayList<>();
  private int nf;

  private Capas( Grafo g ) {
    this.g = g;
  }

  static Capas construir( Grafo g ) {
    Capas c = new Capas( g );
    c.unidades();
    c.capasDeUnidades();
    c.ficticiosYTramos();
    return c;
  }

  int n() {
    return capas.size();
  }

  // ------------------------------------------------------------------ unidades

  private void unidades() {
    for ( Arista e : g.aristas ) {
      e.tramos.clear(); // el mismo grafo se dispone varias veces (orientaciones, objetivos, partes)
      e.invertida = false;
    }
    Map<Grupo, Unidad> porGrupo = new IdentityHashMap<>();
    int i = 0;
    for ( Nodo n : g.nodos ) {
      n.salen.clear();
      n.entran.clear();
      n.capa = -1;
      n.indice = i++;
      if ( n.grupo != null ) {
        Unidad u = porGrupo.get( n.grupo );
        if ( u == null ) {
          u = new Unidad();
          u.grupo = n.grupo;
          n.grupo.unidad = u;
          porGrupo.put( n.grupo, u );
          unidades.add( u );
        }
        u.nodos.add( n );
        n.unidad = u;
      } else {
        Unidad u = new Unidad();
        u.nodos.add( n );
        n.unidad = u;
        unidades.add( u );
      }
    }
  }

  // ------------------------------------------------------------------ capas

  private void capasDeUnidades() {
    int k = unidades.size();
    Map<Unidad, Integer> idx = new IdentityHashMap<>();
    for ( int i = 0; i < k; i++ ) {
      idx.put( unidades.get( i ), i );
    }
    // aristas entre unidades (sin repetir, sin lazos)
    List<List<Integer>> suc = new ArrayList<>();
    for ( int i = 0; i < k; i++ ) {
      suc.add( new ArrayList<Integer>() );
    }
    for ( Arista e : g.aristas ) {
      int a = idx.get( e.a.unidad );
      int b = idx.get( e.b.unidad );
      if ( a != b && !suc.get( a ).contains( b ) ) {
        suc.get( a ).add( b );
      }
    }
    // ciclos: DFS en orden de documento; las que vuelven a la pila se invierten
    int[] estado = new int[ k ];
    boolean[][] invertir = new boolean[ k ][];
    for ( int i = 0; i < k; i++ ) {
      invertir[ i ] = new boolean[ suc.get( i ).size() ];
    }
    for ( int i = 0; i < k; i++ ) {
      if ( estado[ i ] == 0 ) {
        dfs( i, suc, estado, invertir );
      }
    }
    List<List<Integer>> s2 = new ArrayList<>();
    for ( int i = 0; i < k; i++ ) {
      s2.add( new ArrayList<Integer>() );
    }
    for ( int i = 0; i < k; i++ ) {
      for ( int j = 0; j < suc.get( i ).size(); j++ ) {
        int b = suc.get( i ).get( j );
        if ( invertir[ i ][ j ] ) {
          if ( !s2.get( b ).contains( i ) ) {
            s2.get( b ).add( i );
          }
        } else if ( !s2.get( i ).contains( b ) ) {
          s2.get( i ).add( b );
        }
      }
    }
    for ( Arista e : g.aristas ) {
      int a = idx.get( e.a.unidad );
      int b = idx.get( e.b.unidad );
      e.invertida = a != b && !s2.get( a ).contains( b ) && s2.get( b ).contains( a );
    }
    List<List<Integer>> pred = new ArrayList<>();
    for ( int i = 0; i < k; i++ ) {
      pred.add( new ArrayList<Integer>() );
    }
    for ( int i = 0; i < k; i++ ) {
      for ( int b : s2.get( i ) ) {
        pred.get( b ).add( i );
      }
    }
    // orden topologico (Kahn, estable por documento)
    int[] grado = new int[ k ];
    for ( int i = 0; i < k; i++ ) {
      grado[ i ] = pred.get( i ).size();
    }
    List<Integer> topo = new ArrayList<>();
    boolean[] hecho = new boolean[ k ];
    while ( topo.size() < k ) {
      int elegido = -1;
      for ( int i = 0; i < k; i++ ) {
        if ( !hecho[ i ] && grado[ i ] == 0 ) {
          elegido = i;
          break;
        }
      }
      if ( elegido < 0 ) {
        // no deberia pasar (ya no hay ciclos): lo que quede, en orden de documento
        for ( int i = 0; i < k; i++ ) {
          if ( !hecho[ i ] ) {
            hecho[ i ] = true;
            topo.add( i );
          }
        }
        break;
      }
      hecho[ elegido ] = true;
      topo.add( elegido );
      for ( int b : s2.get( elegido ) ) {
        grado[ b ]--;
      }
    }
    // camino mas largo
    int[] capa = new int[ k ];
    for ( int u : topo ) {
      for ( int p : pred.get( u ) ) {
        capa[ u ] = Math.max( capa[ u ], capa[ p ] + 1 );
      }
    }
    // compactar: descenso por coordenadas del largo total de las aristas
    for ( int vuelta = 0; vuelta < 8; vuelta++ ) {
      boolean cambio = false;
      for ( int t = topo.size() - 1; t >= 0; t-- ) {
        int u = topo.get( t );
        int ins = pred.get( u ).size();
        int outs = s2.get( u ).size();
        if ( ins == outs ) {
          continue;
        }
        int lo = 0;
        for ( int p : pred.get( u ) ) {
          lo = Math.max( lo, capa[ p ] + 1 );
        }
        int hi = Integer.MAX_VALUE;
        for ( int s : s2.get( u ) ) {
          hi = Math.min( hi, capa[ s ] - 1 );
        }
        int nueva = outs > ins ? ( hi == Integer.MAX_VALUE ? capa[ u ] : hi ) : lo;
        if ( nueva != capa[ u ] && nueva >= lo && nueva <= hi ) {
          capa[ u ] = nueva;
          cambio = true;
        }
      }
      if ( !cambio ) {
        break;
      }
    }
    int min = Integer.MAX_VALUE;
    int max = 0;
    for ( int i = 0; i < k; i++ ) {
      min = Math.min( min, capa[ i ] );
    }
    for ( int i = 0; i < k; i++ ) {
      capa[ i ] -= min;
      max = Math.max( max, capa[ i ] );
    }
    for ( int i = 0; i <= max && k > 0; i++ ) {
      capas.add( new ArrayList<Unidad>() );
    }
    for ( int i = 0; i < k; i++ ) {
      Unidad u = unidades.get( i );
      u.capa = capa[ i ];
      for ( Nodo n : u.nodos ) {
        n.capa = capa[ i ];
      }
      capas.get( capa[ i ] ).add( u );
    }
  }

  private static void dfs( int u, List<List<Integer>> suc, int[] estado, boolean[][] invertir ) {
    // iterativo: un job largo no tiene que agotar la pila
    java.util.Deque<int[]> pila = new java.util.ArrayDeque<>();
    estado[ u ] = 1;
    pila.push( new int[] { u, 0 } );
    while ( !pila.isEmpty() ) {
      int[] f = pila.peek();
      int x = f[ 0 ];
      if ( f[ 1 ] < suc.get( x ).size() ) {
        int j = f[ 1 ]++;
        int v = suc.get( x ).get( j );
        if ( estado[ v ] == 1 ) {
          invertir[ x ][ j ] = true;
        } else if ( estado[ v ] == 0 ) {
          estado[ v ] = 1;
          pila.push( new int[] { v, 0 } );
        }
      } else {
        estado[ x ] = 2;
        pila.pop();
      }
    }
  }

  // ------------------------------------------------------------------ ficticios y tramos

  private void ficticiosYTramos() {
    // aristas dirigidas segun la disposicion (las invertidas, al reves); las de un mismo grupo no se dibujan
    List<Arista> largas = new ArrayList<>();
    for ( Arista e : g.aristas ) {
      Nodo a = ini( e );
      Nodo b = fin( e );
      if ( a.unidad == b.unidad ) {
        continue;
      }
      if ( b.capa - a.capa == 1 ) {
        tramo( a, b, e );
      } else {
        largas.add( e );
      }
    }
    // concentracion
    Map<String, List<Arista>> haces = new LinkedHashMap<>();
    Map<String, Integer> alDestino = new HashMap<>();
    Map<String, Integer> delOrigen = new HashMap<>();
    for ( Arista e : largas ) {
      alDestino.merge( fin( e ).indice + "\u0000" + clave( e ), 1, Integer::sum );
      delOrigen.merge( ini( e ).indice + "\u0000" + clave( e ), 1, Integer::sum );
    }
    int sueltas = 0;
    for ( Arista e : largas ) {
      String kd = fin( e ).indice + "\u0000" + clave( e );
      String ko = ini( e ).indice + "\u0000" + clave( e );
      String k = alDestino.get( kd ) >= 2 ? "D" + kd : delOrigen.get( ko ) >= 2 ? "O" + ko : "A" + sueltas++;
      List<Arista> l = haces.get( k );
      if ( l == null ) {
        l = new ArrayList<>();
        haces.put( k, l );
      }
      l.add( e );
    }
    for ( Map.Entry<String, List<Arista>> h : haces.entrySet() ) {
      List<Arista> l = h.getValue();
      if ( h.getKey().startsWith( "D" ) ) {
        Nodo b = fin( l.get( 0 ) );
        int desde = Integer.MAX_VALUE;
        for ( Arista e : l ) {
          desde = Math.min( desde, ini( e ).capa + 1 );
        }
        Map<Integer, Nodo> cadena = new HashMap<>();
        for ( int c = desde; c < b.capa; c++ ) {
          cadena.put( c, ficticio( c ) );
        }
        for ( int c = desde; c < b.capa - 1; c++ ) {
          Tramo t = tramo( cadena.get( c ), cadena.get( c + 1 ), null );
          for ( Arista e : l ) {
            if ( ini( e ).capa < c ) {
              agregar( t, e );
            }
          }
        }
        Tramo ult = tramo( cadena.get( b.capa - 1 ), b, null );
        for ( Arista e : l ) {
          agregar( ult, e );
          Tramo pri = tramo( ini( e ), cadena.get( ini( e ).capa + 1 ), null );
          agregar( pri, e );
        }
      } else if ( h.getKey().startsWith( "O" ) ) {
        Nodo a = ini( l.get( 0 ) );
        int hasta = 0;
        for ( Arista e : l ) {
          hasta = Math.max( hasta, fin( e ).capa - 1 );
        }
        Map<Integer, Nodo> cadena = new HashMap<>();
        for ( int c = a.capa + 1; c <= hasta; c++ ) {
          cadena.put( c, ficticio( c ) );
        }
        Tramo pri = tramo( a, cadena.get( a.capa + 1 ), null );
        for ( int c = a.capa + 1; c < hasta; c++ ) {
          Tramo t = tramo( cadena.get( c ), cadena.get( c + 1 ), null );
          for ( Arista e : l ) {
            if ( fin( e ).capa > c + 1 ) {
              agregar( t, e );
            }
          }
        }
        for ( Arista e : l ) {
          agregar( pri, e );
          Tramo ult = tramo( cadena.get( fin( e ).capa - 1 ), fin( e ), null );
          agregar( ult, e );
        }
      } else {
        Arista e = l.get( 0 );
        Nodo prev = ini( e );
        for ( int c = ini( e ).capa + 1; c < fin( e ).capa; c++ ) {
          Nodo f = ficticio( c );
          tramo( prev, f, e );
          prev = f;
        }
        tramo( prev, fin( e ), e );
      }
    }
    // los tramos de cada arista en orden de capa
    for ( Arista e : g.aristas ) {
      Collections.sort( e.tramos, ( x, y ) -> Integer.compare( x.a.capa, y.a.capa ) );
    }
  }

  /** Clase para concentrar aristas largas: la misma clase y el mismo estado (activa o no). */
  private static String clave( Arista e ) {
    return e.activa ? e.clase : e.clase + "~";
  }

  static Nodo ini( Arista e ) {
    return e.invertida ? e.b : e.a;
  }

  static Nodo fin( Arista e ) {
    return e.invertida ? e.a : e.b;
  }

  private Nodo ficticio( int capa ) {
    Nodo f = new Nodo( "\u0000f" + ( nf++ ) );
    f.ficticio = true;
    f.capa = capa;
    f.indice = g.nodos.size() + nf;
    Unidad u = new Unidad();
    u.nodos.add( f );
    u.capa = capa;
    f.unidad = u;
    unidades.add( u );
    capas.get( capa ).add( u );
    ficticios.add( f );
    return f;
  }

  /** Tramo a→b (lo reutiliza si ya existe entre esos dos nodos). */
  private Tramo tramo( Nodo a, Nodo b, Arista e ) {
    for ( Tramo t : a.salen ) {
      if ( t.b == b ) {
        if ( e != null ) {
          agregar( t, e );
        }
        return t;
      }
    }
    Tramo t = new Tramo( a, b, e == null ? null : e.clase, tramos.size() );
    a.salen.add( t );
    b.entran.add( t );
    tramos.add( t );
    if ( e != null ) {
      agregar( t, e );
    }
    return t;
  }

  private static void agregar( Tramo t, Arista e ) {
    if ( !t.originales.contains( e ) ) {
      t.originales.add( e );
      e.tramos.add( t );
    }
  }

  /** Clase del tramo: la de sus aristas (todas iguales salvo en tramos directos compartidos). */
  static String clase( Tramo t ) {
    return t.clase != null ? t.clase : t.originales.isEmpty() ? Grafo.NORMAL : t.originales.get( 0 ).clase;
  }

  static boolean activa( Tramo t ) {
    for ( Arista e : t.originales ) {
      if ( e.activa ) {
        return true;
      }
    }
    return t.originales.isEmpty();
  }

  /** Todas las aristas del tramo van al reves en la disposicion (bucle de un job). */
  static boolean invertido( Tramo t ) {
    if ( t.originales.isEmpty() ) {
      return false;
    }
    for ( Arista e : t.originales ) {
      if ( !e.invertida ) {
        return false;
      }
    }
    return true;
  }
}
