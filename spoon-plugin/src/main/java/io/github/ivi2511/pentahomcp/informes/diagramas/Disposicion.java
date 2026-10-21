package io.github.ivi2511.pentahomcp.informes.diagramas;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Arista;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Nodo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Tramo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Unidad;

/**
 * Una disposicion concreta (orientacion LR o TB y cortes en bandas) con sus coordenadas y rutas, y la
 * eleccion de la mejor variante para un {@link Objetivo}:
 * <ul>
 * <li>Variantes: LR o TB x de 1 a min(capas, 10) bandas (hasta 3 en el linaje).</li>
 * <li>Puntaje = escala a la que entra en la caja (con el tope de agrandamiento) x 0,97 por banda extra x
 * 0,95 por haz extra que cruza un corte x 0,97 por cruce x 0,95 si es TB. Gana el mayor.</li>
 * <li>Hueco entre capas: 30 px (flujo) o 34 (linaje) + 7 por pista extra + el ancho de los rotulos.</li>
 * <li>Las bandas van una debajo de la otra con un canal entre medio; un margen izquierdo comun deja lugar
 * a las pistas que bajan de una banda a la siguiente.</li>
 * </ul>
 *
 * @dueño WP6
 */
final class Disposicion {

  static final double MARGEN = 12;
  static final double PISTA = 7;
  static final double HUECO_FLUJO = 30;
  static final double HUECO_LINAJE = 34;
  static final double CANAL = 14;
  static final int MAX_BANDAS_FLUJO = 10;
  static final int MAX_BANDAS_LINAJE = 3;

  final Capas c;
  final boolean tb;
  final int[] inicios;
  double[] colA;
  double[] huecoA;
  double[] posCapa;
  double a0;
  /** Lugar para los rotulos del lado del destino en los cortes (entre las pistas izquierdas y la capa). */
  double rotDestino;
  int[] banda;
  double[] bandaC;
  double[] bandaFinA;
  double[] bandaAlto;
  /** Tamano del dibujo (px sin escalar, con margenes; sin la leyenda). */
  double ancho;
  double alto;
  int cruces;
  int hacesEnCortes;
  final Map<Tramo, String> rotulos = new IdentityHashMap<>();
  final Map<Tramo, Boolean> rotuloOrigen = new IdentityHashMap<>();
  /**
   * Ficticios de una banda donde no esta ni el origen ni el destino de ninguna de sus aristas: en vez de
   * cruzar esa banda entera, la arista baja por un carril exterior a la derecha.
   */
  final Set<Nodo> expreso = Collections.newSetFromMap( new IdentityHashMap<Nodo, Boolean>() );
  final List<Carril> carriles = new ArrayList<>();

  /** Carril exterior: el tramo que entra al primer ficticio expreso y los que salen de la zona expresa. */
  static final class Carril {
    Tramo entrada;
    final List<Tramo> salidas = new ArrayList<>();
    double x;
  }

  private Disposicion( Capas c, boolean tb, int[] inicios ) {
    this.c = c;
    this.tb = tb;
    this.inicios = inicios;
  }

  int bandas() {
    return inicios.length;
  }

  /** Resultado elegido para un objetivo. */
  static final class Eleccion {
    Disposicion d;
    double puntaje;
    String detalle;
    final List<String> probadas = new ArrayList<>();
  }

  // ================================================================== eleccion

  /**
   * Mide, ordena y prueba todas las variantes para el objetivo; deja calculada la elegida. extraAlto =
   * lo que el dibujo suma abajo (la leyenda) para comparar bien las proporciones.
   */
  static Eleccion elegir( Grafo g, Objetivo obj, double extraAlto ) {
    Tamanos.medir( g );
    Capas c = Capas.construir( g );
    Orden.minimizar( c );
    Eleccion mejor = new Eleccion();
    mejor.puntaje = -1;
    boolean[] orientaciones = { false, true };
    for ( boolean tb : orientaciones ) {
      Tamanos.orientar( c, tb );
      int maxBandas = Math.max( 1, Math.min( c.n(), g.linaje() ? MAX_BANDAS_LINAJE : MAX_BANDAS_FLUJO ) );
      for ( int r = 1; r <= maxBandas; r++ ) {
        int[] ini = Envoltura.cortes( c, tb, r );
        if ( ini == null ) {
          if ( c.n() == 0 && r == 1 ) {
            ini = new int[] { 0 };
          } else {
            continue;
          }
        }
        Disposicion d = new Disposicion( c, tb, ini );
        d.calcular();
        double s = obj.escalaFinal( d.ancho, d.alto + extraAlto );
        double p = s * Math.pow( 0.97, r - 1 ) * Math.pow( 0.95, Math.max( 0, d.hacesEnCortes - ( r - 1 ) ) ) * Math
          .pow( 0.97, d.cruces ) * ( tb ? 0.95 : 1 );
        String det = String.format( Locale.ROOT, "%s r=%d %.0fx%.0f s=%.2f cruces=%d cortes=%d p=%.3f", tb ? "TB"
          : "LR", r, d.ancho, d.alto, s, d.cruces, d.hacesEnCortes, p );
        mejor.probadas.add( det );
        if ( p > mejor.puntaje + 1e-9 ) {
          mejor.puntaje = p;
          mejor.d = d;
          mejor.detalle = det;
        }
      }
    }
    // dejar calculada la elegida (las demas pisaron las coordenadas)
    Tamanos.orientar( c, mejor.d.tb );
    mejor.d.calcular();
    return mejor;
  }

  /** Disposicion fija (pruebas y comparaciones): orientacion y bandas dadas. */
  static Disposicion fija( Grafo g, boolean tb, int r ) {
    Tamanos.medir( g );
    Capas c = Capas.construir( g );
    Orden.minimizar( c );
    Tamanos.orientar( c, tb );
    int[] ini = Envoltura.cortes( c, tb, r );
    Disposicion d = new Disposicion( c, tb, ini == null ? new int[] { 0 } : ini );
    d.calcular();
    return d;
  }

  // ================================================================== calculo

  void calcular() {
    int n = c.n();
    banda = new int[ n ];
    for ( int b = 0; b < inicios.length; b++ ) {
      int fin = b + 1 < inicios.length ? inicios[ b + 1 ] : n;
      for ( int l = inicios[ b ]; l < fin; l++ ) {
        banda[ l ] = b;
      }
    }
    colA = Tamanos.columnas( c );
    carriles();
    ubicarRotulos( c, rotulos, rotuloOrigen );
    huecoA = new double[ Math.max( 0, n - 1 ) ];
    for ( int l = 0; l + 1 < n; l++ ) {
      huecoA[ l ] = Tamanos.hueco( c, l, tb, rotulos, rotuloOrigen );
    }
    // canal izquierdo comun (lo usan las aristas que bajan de una banda a la siguiente)
    int maxCorte = 0;
    rotDestino = 0;
    for ( int b = 1; b < inicios.length; b++ ) {
      maxCorte = Math.max( maxCorte, hacesCorte( inicios[ b ] - 1 ).size() + carrilesHacia( b ) );
      rotDestino = Math.max( rotDestino, rotuloCorte( inicios[ b ] - 1, false ) );
    }
    a0 = maxCorte == 0 ? 0 : 10 + maxCorte * PISTA + rotDestino;
    // eje a: columnas
    posCapa = new double[ n ];
    bandaFinA = new double[ inicios.length ];
    for ( int l = 0; l < n; l++ ) {
      posCapa[ l ] = l == 0 || banda[ l ] != banda[ l - 1 ] ? a0 : posCapa[ l - 1 ] + colA[ l - 1 ] + huecoA[ l - 1 ];
      bandaFinA[ banda[ l ] ] = posCapa[ l ] + colA[ l ];
      for ( Unidad u : c.capas.get( l ) ) {
        if ( u.ficticia() ) {
          u.posA = posCapa[ l ];
          u.tamA = colA[ l ];
          u.nodos.get( 0 ).tamA = colA[ l ];
        } else {
          u.posA = posCapa[ l ] + ( colA[ l ] - u.tamA ) / 2;
        }
      }
    }
    // eje c: cada banda por separado, y las bandas una debajo de la otra
    bandaC = new double[ inicios.length ];
    bandaAlto = new double[ inicios.length ];
    double cAcum = 0;
    hacesEnCortes = 0;
    for ( int b = 0; b < inicios.length && n > 0; b++ ) {
      int ini = inicios[ b ];
      int fin = b + 1 < inicios.length ? inicios[ b + 1 ] - 1 : n - 1;
      double altoBanda = Posiciones.banda( c, ini, fin, tb, expreso );
      bandaC[ b ] = cAcum;
      bandaAlto[ b ] = altoBanda;
      for ( int l = ini; l <= fin; l++ ) {
        for ( Unidad u : c.capas.get( l ) ) {
          if ( !( u.ficticia() && expreso.contains( u.nodos.get( 0 ) ) ) ) {
            u.posC += cAcum;
          }
        }
      }
      cAcum += altoBanda;
      if ( b + 1 < inicios.length ) {
        int k = hacesCorte( fin ).size() + carrilesHacia( b + 1 );
        hacesEnCortes += k;
        cAcum += CANAL + k * PISTA + 8;
      }
    }
    // rutas
    double finA = 0;
    for ( int b = 0; b < inicios.length; b++ ) {
      int k = b + 1 < inicios.length ? hacesCorte( inicios[ b + 1 ] - 1 ).size() : 0;
      double rot = b + 1 < inicios.length ? rotuloCorte( inicios[ b + 1 ] - 1, true ) : 0;
      finA = Math.max( finA, bandaFinA[ b ] + ( k > 0 ? 10 + rot + k * PISTA : 0 ) );
    }
    for ( int i = 0; i < carriles.size(); i++ ) {
      carriles.get( i ).x = finA + 10 + i * PISTA;
    }
    Rutas.trazar( this );
    double largoA = finA + ( carriles.isEmpty() ? 0 : 10 + carriles.size() * PISTA );
    double largoC = cAcum;
    ancho = ( tb ? largoC : largoA ) + 2 * MARGEN;
    alto = ( tb ? largoA : largoC ) + 2 * MARGEN;
    cruces = Rutas.cruces( c.g );
  }

  /**
   * Ancho de los rotulos de los tramos que cruzan el corte: los del lado del origen van en el canal
   * derecho y los del destino en el izquierdo.
   */
  double rotuloCorte( int l, boolean origen ) {
    double w = 0;
    for ( Unidad u : c.capas.get( l ) ) {
      for ( Nodo n : u.nodos ) {
        for ( Tramo t : n.salen ) {
          String r = rotulos.get( t );
          if ( r != null && rotuloOrigen.containsKey( t ) == origen ) {
            w = Math.max( w, Tamanos.rotuloA( r, tb ) );
          }
        }
      }
    }
    return w;
  }

  /**
   * Donde va el rotulo de cada arista: junto al origen, en su primer tramo, salvo que ese primer pedazo lo
   * compartan otras flechas del mismo origen (un filtro con "verdadero" y "falso"): entonces justo antes de
   * la punta, en el ultimo tramo. Asi el rotulo nunca queda sobre un pedazo comun a varias flechas. Dos
   * rotulos que caerian en la misma punta se juntan en uno ("error, deshabilitado").
   */
  static void ubicarRotulos( Capas c, Map<Tramo, String> rotulos, Map<Tramo, Boolean> origen ) {
    rotulos.clear();
    origen.clear();
    Map<Nodo, Tramo> enPunta = new IdentityHashMap<>();
    for ( Arista e : c.g.aristas ) {
      if ( e.rotulo == null || e.rotulo.trim().isEmpty() || e.tramos.isEmpty() ) {
        continue;
      }
      Tramo pri = e.tramos.get( 0 );
      Tramo ult = e.tramos.get( e.tramos.size() - 1 );
      boolean enOrigen = pri.a.salen.size() < 2 && pri.originales.size() < 2;
      Tramo t = enOrigen ? pri : ult;
      if ( !enOrigen ) {
        Tramo ya = enPunta.get( t.b );
        if ( ya != null && ya != t ) {
          t = ya; // la misma punta: un solo rotulo
        }
        enPunta.put( t.b, t );
      }
      String ya = rotulos.get( t );
      rotulos.put( t, ya == null || ya.equals( e.rotulo ) || ya.contains( e.rotulo ) ? ( ya == null ? e.rotulo : ya )
        : ya + ", " + e.rotulo );
      if ( enOrigen ) {
        origen.put( t, Boolean.TRUE );
      }
    }
  }

  /** Haces de tramos que cruzan el corte entre la capa l y la l+1 (sin los de los carriles exteriores). */
  List<List<Tramo>> hacesCorte( int l ) {
    return Rutas.haces( c, l, false, expreso );
  }

  /** Cuantos carriles exteriores salen al canal de arriba de la banda b. */
  int carrilesHacia( int b ) {
    int k = 0;
    for ( Carril x : carriles ) {
      for ( Tramo t : x.salidas ) {
        if ( banda[ t.b.capa ] == b ) {
          k++;
          break;
        }
      }
    }
    return k;
  }

  private void carriles() {
    expreso.clear();
    carriles.clear();
    if ( inicios.length < 3 ) {
      return;
    }
    for ( Nodo f : c.ficticios ) {
      int b = banda[ f.capa ];
      boolean usa = false;
      List<Tramo> ts = new ArrayList<>( f.entran );
      ts.addAll( f.salen );
      for ( Tramo t : ts ) {
        for ( Arista e : t.originales ) {
          usa |= banda[ Capas.ini( e ).capa ] == b || banda[ Capas.fin( e ).capa ] == b;
        }
      }
      if ( !usa ) {
        expreso.add( f );
      }
    }
    // solo zonas que cruzan la banda entera: si un expreso toca, dentro de su banda, algo que no lo es, deja
    // de serlo (y se repite hasta que no cambie nada)
    boolean cambio = true;
    while ( cambio ) {
      cambio = false;
      for ( Nodo f : new ArrayList<>( expreso ) ) {
        boolean toca = false;
        for ( Tramo t : f.salen ) {
          toca |= banda[ t.b.capa ] == banda[ f.capa ] && !expreso.contains( t.b );
        }
        for ( Tramo t : f.entran ) {
          toca |= banda[ t.a.capa ] == banda[ f.capa ] && !expreso.contains( t.a );
        }
        if ( toca ) {
          expreso.remove( f );
          cambio = true;
        }
      }
    }
    for ( Tramo t : c.tramos ) {
      if ( expreso.contains( t.a ) || !expreso.contains( t.b ) ) {
        continue;
      }
      Carril k = new Carril();
      k.entrada = t;
      Deque<Nodo> q = new ArrayDeque<>();
      q.add( t.b );
      while ( !q.isEmpty() ) {
        Nodo f = q.poll();
        for ( Tramo s2 : f.salen ) {
          if ( expreso.contains( s2.b ) ) {
            q.add( s2.b );
          } else if ( !k.salidas.contains( s2 ) ) {
            k.salidas.add( s2 );
          }
        }
      }
      carriles.add( k );
    }
  }

  /** Abstracto (a, c) a coordenadas finales del dibujo. */
  double[] xy( double a, double cc ) {
    return tb ? new double[] { cc + MARGEN, a + MARGEN } : new double[] { a + MARGEN, cc + MARGEN };
  }
}
