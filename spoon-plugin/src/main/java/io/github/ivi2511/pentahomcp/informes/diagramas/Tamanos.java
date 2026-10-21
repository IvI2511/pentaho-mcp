package io.github.ivi2511.pentahomcp.informes.diagramas;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Grupo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Nodo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Tramo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Unidad;
import io.github.ivi2511.pentahomcp.informes.graficos.Medida;

/**
 * Tamanos reales de nodos y grupos (px del dibujo sin escalar) y su version abstracta segun la orientacion.
 * <ul>
 * <li>Flujo: todos los nodos del mismo tamano; ancho entre 112 y 184 px; titulo en negrita de 12 px en hasta
 * 2 lineas; lineas tenues de 10,5 px; lo que no entra, con «…» (el texto completo va en un title).</li>
 * <li>Linaje: campos de 12 px, la operacion en hasta 2 lineas de 10 px; el mismo ancho para todos los campos
 * de un step (entre 64 y 190 px); cabecera del step de 22 px.</li>
 * </ul>
 *
 * @dueño WP6
 */
final class Tamanos {

  static final double T_TIT = 12;
  static final double T_SUB = 10.5;
  static final double T_OP = 10;
  static final double T_GRUPO = 11;
  static final double T_ROTULO = 10;
  static final double L_TIT = 15;
  static final double L_SUB = 13.5;
  static final double L_OP = 12.5;
  static final double CABECERA = 22;
  static final double SEP_CAMPO = 5;

  private Tamanos() {
  }

  static double anchoTexto( String s, double tam, boolean negrita ) {
    return Medida.ancho( s, tam ) * ( negrita ? 1.08 : 1 );
  }

  static void medir( Grafo g ) {
    if ( g.linaje() ) {
      medirLinaje( g );
    } else {
      medirFlujo( g );
    }
  }

  private static void medirFlujo( Grafo g ) {
    double max = 0;
    for ( Nodo n : g.nodos ) {
      max = Math.max( max, anchoTexto( n.titulo, T_TIT, true ) );
      for ( String l : n.lineas ) {
        max = Math.max( max, anchoTexto( l, T_SUB, false ) * 0.85 );
      }
    }
    double w = Math.max( 112, Math.min( 184, max + 20 ) );
    int maxTit = 1;
    int maxSub = 0;
    for ( Nodo n : g.nodos ) {
      n.tit.clear();
      n.sub.clear();
      n.tit.addAll( partir( n.titulo, w - 18, T_TIT, true, 2 ) );
      for ( String l : n.lineas ) {
        if ( l != null && !l.trim().isEmpty() ) {
          n.sub.add( Medida.recortar( l.trim(), w - 14, T_SUB ) );
        }
      }
      maxTit = Math.max( maxTit, n.tit.size() );
      maxSub = Math.max( maxSub, n.sub.size() );
    }
    double h = 8 + maxTit * L_TIT + maxSub * L_SUB + 7;
    for ( Nodo n : g.nodos ) {
      n.w = w;
      n.h = h;
    }
  }

  private static void medirLinaje( Grafo g ) {
    for ( Grupo gr : g.grupos ) {
      double max = 0;
      for ( Nodo n : gr.nodos ) {
        n.tit.clear();
        n.sub.clear();
        n.tit.add( Medida.recortar( n.titulo, 190 - 16, T_TIT ) );
        double tw = anchoTexto( n.tit.get( 0 ), T_TIT, false );
        if ( n.op != null && !n.op.trim().isEmpty() ) {
          n.sub.addAll( partir( n.op.trim(), 190 - 16, T_OP, false, 2 ) );
          for ( String s : n.sub ) {
            tw = Math.max( tw, anchoTexto( s, T_OP, false ) );
          }
        }
        max = Math.max( max, tw );
      }
      double w = Math.max( 64, Math.min( 190, max + 16 ) );
      for ( Nodo n : gr.nodos ) {
        n.w = w;
        n.h = 6 + L_TIT + n.sub.size() * L_OP + 5;
      }
      gr.tit.clear();
      String cab = gr.op == null || gr.op.isEmpty() ? gr.nombre : gr.nombre + " · " + gr.op;
      gr.tit.add( Medida.recortar( cab, Math.max( w + 40, 150 ), T_GRUPO * 1.08 ) );
    }
    // un campo suelto (sin grupo) se mide como un campo
    for ( Nodo n : g.nodos ) {
      if ( n.grupo == null ) {
        n.tit.clear();
        n.sub.clear();
        n.tit.add( Medida.recortar( n.titulo, 190 - 16, T_TIT ) );
        n.w = Math.max( 64, Math.min( 190, anchoTexto( n.tit.get( 0 ), T_TIT, false ) + 16 ) );
        n.h = 6 + L_TIT + 5;
      }
    }
  }

  /** Corta en palabras hasta maxLineas; la ultima con «…» si no entra. */
  static List<String> partir( String s, double max, double tam, boolean negrita, int maxLineas ) {
    List<String> out = new ArrayList<>();
    String resto = s == null ? "" : s.trim();
    double f = negrita ? 1.08 : 1;
    while ( !resto.isEmpty() && out.size() < maxLineas ) {
      if ( Medida.ancho( resto, tam ) * f <= max || out.size() == maxLineas - 1 ) {
        out.add( Medida.recortar( resto, max / f, tam ) );
        break;
      }
      int corte = -1;
      for ( int i = 1; i < resto.length(); i++ ) {
        char ch = resto.charAt( i );
        if ( ( ch == ' ' || ch == '_' || ch == '/' || ch == ',' || ch == '-' || ch == '.' ) && Medida.ancho( resto
          .substring( 0, i + 1 ), tam ) * f <= max ) {
          corte = ch == ' ' ? i : i + 1;
        }
      }
      if ( corte <= 0 ) {
        out.add( Medida.recortar( resto, max / f, tam ) );
        break;
      }
      out.add( resto.substring( 0, corte ).trim() );
      resto = resto.substring( corte ).trim();
    }
    if ( out.isEmpty() ) {
      out.add( "" );
    }
    return out;
  }

  /** Tamanos abstractos (a lo largo de las capas / transversal) y desplazamientos dentro de la unidad. */
  static void orientar( Capas c, boolean tb ) {
    for ( Unidad u : c.unidades ) {
      if ( u.grupo != null && tb ) {
        // linaje TB: cabecera arriba y los campos uno al lado del otro; las flechas entran por arriba
        // cruzando la franja de la cabecera (el texto lleva fondo y queda encima)
        double h = 0;
        for ( Nodo n : u.nodos ) {
          h = Math.max( h, n.h );
        }
        double cabW = anchoTexto( u.grupo.tit.get( 0 ), T_GRUPO, true ) + 16;
        u.cab = CABECERA;
        double x = 8;
        for ( Nodo n : u.nodos ) {
          n.tamA = n.h;
          n.tamC = n.w;
          n.offA = u.cab + ( h - n.h ) / 2;
          n.offC = x;
          x += n.w + 8;
        }
        u.tamC = Math.max( x, cabW );
        double corrimiento = ( u.tamC - x ) / 2;
        for ( Nodo n : u.nodos ) {
          n.offC += corrimiento;
        }
        u.tamA = u.cab + h + 8;
      } else if ( u.grupo != null ) {
        // linaje LR: cabecera arriba, campos apilados
        double w = 0;
        for ( Nodo n : u.nodos ) {
          w = Math.max( w, n.w );
        }
        double cabW = anchoTexto( u.grupo.tit.get( 0 ), T_GRUPO, true ) + 16;
        u.cab = CABECERA;
        u.tamA = Math.max( w + 16, cabW );
        double y = u.cab;
        for ( Nodo n : u.nodos ) {
          n.tamA = w;
          n.tamC = n.h;
          n.offA = ( u.tamA - w ) / 2;
          n.offC = y;
          y += n.h + SEP_CAMPO;
        }
        u.tamC = y - SEP_CAMPO + 8;
      } else {
        Nodo n = u.nodos.get( 0 );
        if ( n.ficticio ) {
          n.tamC = 0;
          u.tamC = 0;
          n.offA = 0;
          n.offC = 0;
        } else {
          n.tamA = tb ? n.h : n.w;
          n.tamC = tb ? n.w : n.h;
          n.offA = 0;
          n.offC = 0;
          u.tamA = n.tamA;
          u.tamC = n.tamC;
        }
      }
    }
  }

  static double[] columnas( Capas c ) {
    double[] col = new double[ c.n() ];
    for ( int l = 0; l < c.n(); l++ ) {
      for ( Unidad u : c.capas.get( l ) ) {
        if ( !u.ficticia() ) {
          col[ l ] = Math.max( col[ l ], u.tamA );
        }
      }
    }
    return col;
  }

  /** Ancho que ocupa un rotulo a lo largo de las capas (en TB, una linea de texto). */
  static double rotuloA( String r, boolean tb ) {
    return tb ? 18 : anchoTexto( r, T_ROTULO, false ) + 10;
  }

  /** Hueco entre la capa l y la l+1: base + pistas + lugar para los rotulos. */
  static double hueco( Capas c, int l, boolean tb, Map<Tramo, String> rotulos, Map<Tramo, Boolean> origen ) {
    int haces = Rutas.haces( c, l ).size();
    double izq = 0;
    double der = 0;
    for ( Unidad u : c.capas.get( l ) ) {
      for ( Nodo n : u.nodos ) {
        for ( Tramo t : n.salen ) {
          String r = rotulos.get( t );
          if ( r != null ) {
            double w = rotuloA( r, tb );
            if ( origen.containsKey( t ) ) {
              izq = Math.max( izq, w );
            } else {
              der = Math.max( der, w );
            }
          }
        }
      }
    }
    double base = c.g.linaje() ? Disposicion.HUECO_LINAJE : Disposicion.HUECO_FLUJO;
    return base + Math.max( 0, haces - 1 ) * Disposicion.PISTA + izq + der;
  }
}
