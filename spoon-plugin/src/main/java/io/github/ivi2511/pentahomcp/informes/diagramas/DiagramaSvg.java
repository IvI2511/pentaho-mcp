package io.github.ivi2511.pentahomcp.informes.diagramas;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Arista;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Nodo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Tramo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo.Unidad;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * SVG 1.1 de una disposicion, con las mismas reglas que GraficosSvg para que Batik, IE11, Chromium y POI lo
 * vean igual: atributos inline (sin CSS, clases ni {@code <style>}), linea de base calculada (y + 0,35 em,
 * sin dominant-baseline), un rect blanco de fondo primero, width/height = viewBox, textos escapados, flechas
 * dibujadas como triangulos (sin marker). Los nombres que no entran van recortados con «…» y el texto
 * completo en un {@code <title>}.
 * <p>
 * Debajo del dibujo, una leyenda con lo que no se explica solo (colores de los saltos de un job, saltos de
 * error o deshabilitados, claves del linaje), solo con lo que aparece en el diagrama.
 *
 * @dueño WP6
 */
final class DiagramaSvg {

  static final String FUENTE = "'Segoe UI', Arial, sans-serif";
  static final String TEXTO = Tema.TEXTO;
  static final String TENUE = Tema.TEXTO_TENUE;
  static final String PRIMARIO = Tema.PRIMARIO;
  static final String ACENTO = Tema.ACENTO;
  static final String BORDE = Tema.BORDE;
  static final String GRIS = "#8794A3";
  static final String GRIS_CLARO = "#B4BEC9";
  static final String ROJO = "#C53030";
  static final String VERDE = "#2F855A";
  static final String FONDO_NODO = "#F3F5F8";
  static final String FONDO_GRUPO = "#F5F6F8";
  static final String FONDO_DESTINO = "#EEF3FB";

  static final double T_LEYENDA = 10;
  static final double L_LEYENDA = 16;
  static final double MUESTRA = 22;

  private final StringBuilder sb = new StringBuilder( 16384 );
  private final Disposicion d;
  private final Rotulos rot;
  /** Cajas dibujadas (x, y, w, h en coordenadas del SVG), para las pruebas. */
  final List<double[]> cajasNodos = new ArrayList<>();
  final List<double[]> cajasRotulos = new ArrayList<>();
  /** La tarjeta de atras de los steps con varias copias (asoma 4 px arriba y a la derecha). */
  final List<double[]> cajasCopias = new ArrayList<>();
  double ancho;
  double alto;
  String svg;

  private DiagramaSvg( Disposicion d, Rotulos rot ) {
    this.d = d;
    this.rot = rot;
  }

  /** Dibuja la disposicion (ya calculada) con su leyenda debajo. */
  static DiagramaSvg dibujar( Disposicion d, String titulo, Rotulos rot ) {
    return dibujar( d, titulo, rot, null );
  }

  /**
   * Dibuja la disposicion (ya calculada) con su leyenda: debajo, o a la derecha si asi el dibujo entra mas
   * grande en la caja del objetivo (diapositivas con un dibujo ancho y bajo).
   */
  static DiagramaSvg dibujar( Disposicion d, String titulo, Rotulos rot, Objetivo obj ) {
    DiagramaSvg s = new DiagramaSvg( d, rot );
    s.todo();
    List<String[]> leyenda = leyenda( d.c.g, rot );
    double wDibujo = Math.ceil( d.ancho );
    double hDibujo = Math.ceil( d.alto );
    double[] abajo = tamanoLeyenda( leyenda, wDibujo );
    int w = (int) Math.ceil( Math.max( wDibujo, abajo[ 0 ] ) );
    int h = (int) Math.ceil( d.alto + abajo[ 1 ] );
    boolean derecha = false;
    if ( !leyenda.isEmpty() && obj != null ) {
      double[] col = columnaLeyenda( leyenda );
      int w2 = (int) Math.ceil( wDibujo + col[ 0 ] );
      int h2 = (int) Math.ceil( Math.max( hDibujo, col[ 1 ] ) );
      if ( obj.escalaFinal( w2, h2 ) > obj.escalaFinal( w, h ) * 1.02 ) {
        derecha = true;
        w = w2;
        h = h2;
      }
    }
    if ( derecha ) {
      s.leyendaDerecha( leyenda, wDibujo );
    } else if ( !leyenda.isEmpty() ) {
      s.leyenda( leyenda, d.alto, w );
    }
    s.ancho = w;
    s.alto = h;
    StringBuilder r = new StringBuilder( s.sb.length() + 400 );
    r.append( "<svg xmlns=\"http://www.w3.org/2000/svg\" version=\"1.1\" width=\"" ).append( w ).append(
      "\" height=\"" ).append( h ).append( "\" viewBox=\"0 0 " ).append( w ).append( ' ' ).append( h ).append(
        "\" role=\"img\" aria-label=\"" ).append( esc( titulo ) ).append( "\"><title>" ).append( esc( titulo ) )
      .append( "</title><rect x=\"0\" y=\"0\" width=\"" ).append( w ).append( "\" height=\"" ).append( h ).append(
        "\" fill=\"#FFFFFF\"/>" );
    r.append( s.sb ).append( "</svg>" );
    s.svg = r.toString();
    return s;
  }

  private void todo() {
    Grafo g = d.c.g;
    // grupos (linaje)
    for ( Unidad u : d.c.unidades ) {
      if ( u.grupo == null ) {
        continue;
      }
      double[] r = rect( u.posA, u.posC, u.tamA, u.tamC );
      boolean dst = u.grupo.destino;
      sb.append( "<rect x=\"" ).append( n( r[ 0 ] ) ).append( "\" y=\"" ).append( n( r[ 1 ] ) ).append( "\" width=\"" )
        .append( n( r[ 2 ] ) ).append( "\" height=\"" ).append( n( r[ 3 ] ) ).append( "\" rx=\"6\" ry=\"6\" fill=\"" )
        .append( dst ? FONDO_DESTINO : FONDO_GRUPO ).append( "\" stroke=\"" ).append( dst ? ACENTO : BORDE ).append(
          "\" stroke-width=\"1\"/>" );
    }
    // tramos
    for ( Tramo t : d.c.tramos ) {
      tramo( t, g.linaje() );
    }
    // cabeceras de los grupos (encima de las flechas que entran por arriba en TB)
    for ( Unidad u : d.c.unidades ) {
      if ( u.grupo == null ) {
        continue;
      }
      double[] r = rect( u.posA, u.posC, u.tamA, u.tamC );
      String t = u.grupo.tit.isEmpty() ? u.grupo.nombre : u.grupo.tit.get( 0 );
      double w = Tamanos.anchoTexto( t, Tamanos.T_GRUPO, true ) + 6;
      sb.append( "<rect x=\"" ).append( n( r[ 0 ] + 5 ) ).append( "\" y=\"" ).append( n( r[ 1 ] + 3 ) ).append(
        "\" width=\"" ).append( n( w ) ).append( "\" height=\"16\" fill=\"" ).append( u.grupo.destino ? FONDO_DESTINO
          : FONDO_GRUPO ).append( "\"/>" );
      String completo = u.grupo.op == null ? u.grupo.nombre : u.grupo.nombre + " · " + u.grupo.op;
      texto( r[ 0 ] + 8, r[ 1 ] + 11, t, "start", Tamanos.T_GRUPO, PRIMARIO, true, completo );
    }
    // nodos
    for ( Nodo x : g.nodos ) {
      nodo( x );
    }
    // rotulos de las flechas (al final: con fondo blanco, encima de las lineas)
    for ( Tramo t : d.c.tramos ) {
      String r = d.rotulos.get( t );
      if ( r == null || t.puntos == null || t.puntos.size() < 2 ) {
        continue;
      }
      rotulo( t, r );
    }
  }

  private void tramo( Tramo t, boolean linaje ) {
    if ( t.puntos == null || t.puntos.size() < 2 ) {
      return;
    }
    String clase = Capas.clase( t );
    boolean activa = Capas.activa( t );
    String color = color( clase, activa, linaje );
    String guion = guion( clase, activa );
    double grosor = linaje ? 1.2 : 1.4;
    boolean invertido = Capas.invertido( t );
    // la punta va en el destino original: al final del tramo, o al principio si la arista va al reves
    boolean puntaFin = !invertido && !t.b.ficticio;
    boolean puntaIni = invertido && !t.a.ficticio;
    List<double[]> p = new ArrayList<>( t.puntos );
    if ( puntaFin ) {
      acortar( p, p.size() - 1, p.size() - 2 );
    }
    if ( puntaIni ) {
      acortar( p, 0, 1 );
    }
    sb.append( "<path d=\"" ).append( camino( p ) ).append( "\" fill=\"none\" stroke=\"" ).append( color ).append(
      "\" stroke-width=\"" ).append( n( grosor ) ).append( '"' );
    if ( guion != null ) {
      sb.append( " stroke-dasharray=\"" ).append( guion ).append( '"' );
    }
    sb.append( "/>" );
    if ( puntaFin || puntaIni ) {
      int i = puntaFin ? t.puntos.size() - 2 : 1;
      int j = puntaFin ? t.puntos.size() - 1 : 0;
      double[] a = d.xy( t.puntos.get( i )[ 0 ], t.puntos.get( i )[ 1 ] );
      double[] b = d.xy( t.puntos.get( j )[ 0 ], t.puntos.get( j )[ 1 ] );
      if ( activa ) {
        punta( a, b, color );
      } else {
        cruz( a, b, color );
      }
    }
  }

  /** Acorta el extremo i (hacia j) para que la linea no asome por la punta. */
  private static void acortar( List<double[]> p, int i, int j ) {
    double[] a = p.get( j );
    double[] b = p.get( i );
    double largo = Math.hypot( b[ 0 ] - a[ 0 ], b[ 1 ] - a[ 1 ] );
    if ( largo > 8 ) {
      double f = ( largo - 6 ) / largo;
      p.set( i, new double[] { a[ 0 ] + ( b[ 0 ] - a[ 0 ] ) * f, a[ 1 ] + ( b[ 1 ] - a[ 1 ] ) * f } );
    }
  }

  private void rotulo( Tramo t, String r ) {
    boolean origen = d.rotuloOrigen.containsKey( t );
    // origen: el pedazo que sale del nodo; destino: el que llega a la punta
    double[] a = origen ? t.puntos.get( 1 ) : t.puntos.get( t.puntos.size() - 2 );
    double[] b = origen ? t.puntos.get( 0 ) : t.puntos.get( t.puntos.size() - 1 );
    if ( !origen && t.b.ficticio ) {
      return;
    }
    String color = color( Capas.clase( t ), Capas.activa( t ), false );
    double w = Tamanos.anchoTexto( r, Tamanos.T_ROTULO, false ) + 6;
    double x;
    double yc;
    if ( !d.tb ) {
      // pegado al extremo que le corresponde: al salir del origen o justo antes de la punta
      // la tarjeta de las copias asoma 4 px a la derecha del origen
      double xa = origen ? b[ 0 ] + 4 + ( t.a.copias > 1 ? 4 : 0 ) : b[ 0 ] - 10 - w;
      double[] q = d.xy( xa, b[ 1 ] );
      x = q[ 0 ];
      yc = q[ 1 ];
    } else {
      // al costado de la linea vertical, pegado al nodo: debajo del origen o justo arriba de la punta
      // la tarjeta de las copias asoma 4 px por encima del destino
      double ya = origen ? b[ 0 ] + 3 + 7 : b[ 0 ] - 3 - 7 - ( t.b.copias > 1 ? 4 : 0 );
      double[] q = d.xy( ya, b[ 1 ] );
      x = q[ 0 ] + 4;
      yc = q[ 1 ];
    }
    sb.append( "<rect x=\"" ).append( n( x ) ).append( "\" y=\"" ).append( n( yc - 7 ) ).append( "\" width=\"" )
      .append( n( w ) ).append( "\" height=\"14\" fill=\"#FFFFFF\"/>" );
    cajasRotulos.add( new double[] { x, yc - 7, w, 14 } );
    texto( x + 3, yc, r, "start", Tamanos.T_ROTULO, color, false, null );
  }

  private void nodo( Nodo x ) {
    if ( x.unidad == null ) {
      return;
    }
    double[] r = rect( x.a0(), x.unidad.posC + x.offC, x.tamA, x.tamC );
    cajasNodos.add( r.clone() );
    if ( x.copias > 1 ) {
      cajasCopias.add( new double[] { r[ 0 ] + 4, r[ 1 ] - 4, r[ 2 ], r[ 3 ] } );
    }
    boolean linaje = d.c.g.linaje();
    String borde = x.falta ? ROJO : x.fantasma ? GRIS : linaje ? ( x.grupo != null && x.grupo.destino ? PRIMARIO
      : GRIS ) : PRIMARIO;
    double rx = x.inicio ? r[ 3 ] / 2 : linaje ? 4 : 6;
    String fondo = linaje || x.inicio || x.fantasma ? "#FFFFFF" : FONDO_NODO;
    double grosor = x.multi ? 2.6 : linaje ? 1 : 1.2;
    if ( x.copias > 1 ) {
      // varias copias: una tarjeta detras, corrida 4 px
      sb.append( "<rect x=\"" ).append( n( r[ 0 ] + 4 ) ).append( "\" y=\"" ).append( n( r[ 1 ] - 4 ) ).append(
        "\" width=\"" ).append( n( r[ 2 ] ) ).append( "\" height=\"" ).append( n( r[ 3 ] ) ).append( "\" rx=\"" )
        .append( n( rx ) ).append( "\" ry=\"" ).append( n( rx ) ).append( "\" fill=\"#E4E8EE\" stroke=\"" ).append(
          borde ).append( "\" stroke-width=\"1\"/>" );
    }
    sb.append( "<rect x=\"" ).append( n( r[ 0 ] ) ).append( "\" y=\"" ).append( n( r[ 1 ] ) ).append( "\" width=\"" )
      .append( n( r[ 2 ] ) ).append( "\" height=\"" ).append( n( r[ 3 ] ) ).append( "\" rx=\"" ).append( n( rx ) )
      .append( "\" ry=\"" ).append( n( rx ) ).append( "\" fill=\"" ).append( fondo ).append( "\" stroke=\"" ).append(
        borde ).append( "\" stroke-width=\"" ).append( n( grosor ) ).append( '"' );
    if ( x.falta || x.fantasma ) {
      sb.append( " stroke-dasharray=\"4 3\"" );
    }
    sb.append( "/>" );
    if ( x.copias > 1 ) {
      String t = "×" + x.copias;
      double w = Tamanos.anchoTexto( t, 10, true ) + 8;
      sb.append( "<rect x=\"" ).append( n( r[ 0 ] + r[ 2 ] - w - 3 ) ).append( "\" y=\"" ).append( n( r[ 1 ] + 3 ) )
        .append( "\" width=\"" ).append( n( w ) ).append( "\" height=\"14\" rx=\"7\" ry=\"7\" fill=\"" ).append(
          PRIMARIO ).append( "\"/>" );
      texto( r[ 0 ] + r[ 2 ] - w / 2 - 3, r[ 1 ] + 10, t, "middle", 10, "#FFFFFF", true, rot.f( "copias",
        x.copias ) );
    }
    double cx = r[ 0 ] + r[ 2 ] / 2;
    double lSub = linaje ? Tamanos.L_OP : Tamanos.L_SUB;
    double lineas = x.tit.size() * Tamanos.L_TIT + x.sub.size() * lSub;
    double y = r[ 1 ] + ( r[ 3 ] - lineas ) / 2;
    for ( String s : x.tit ) {
      texto( cx, y + Tamanos.L_TIT / 2, s, "middle", Tamanos.T_TIT, x.fantasma ? TENUE : TEXTO, !linaje
        && !x.fantasma, x.titulo );
      y += Tamanos.L_TIT;
    }
    List<String> llenas = new ArrayList<>();
    for ( String l : x.lineas ) {
      if ( l != null && !l.trim().isEmpty() ) {
        llenas.add( l.trim() );
      }
    }
    int i = 0;
    for ( String s : x.sub ) {
      String completo = linaje ? x.op : i < llenas.size() ? llenas.get( i ) : null;
      texto( cx, y + lSub / 2, s, "middle", linaje ? Tamanos.T_OP : Tamanos.T_SUB, TENUE, false, completo );
      y += lSub;
      i++;
    }
  }

  // ------------------------------------------------------------------ leyenda

  /** Las entradas de la leyenda {clase, activa, texto} que corresponden a este grafo. */
  static List<String[]> leyenda( Grafo g, Rotulos rot ) {
    List<String[]> out = new ArrayList<>();
    if ( !g.leyenda ) {
      return out;
    }
    boolean ok = false;
    boolean falla = false;
    boolean siempre = false;
    boolean error = false;
    boolean clave = false;
    boolean inactiva = false;
    for ( Arista e : g.aristas ) {
      ok |= Grafo.OK.equals( e.clase );
      falla |= Grafo.FALLA.equals( e.clase );
      siempre |= Grafo.SIEMPRE.equals( e.clase );
      error |= Grafo.ERROR.equals( e.clase );
      clave |= Grafo.CLAVE.equals( e.clase );
      inactiva |= !e.activa;
    }
    if ( g.job() ) {
      if ( ok ) {
        out.add( new String[] { Grafo.OK, "1", rot.s( "leyenda.ok" ) } );
      }
      if ( falla ) {
        out.add( new String[] { Grafo.FALLA, "1", rot.s( "leyenda.falla" ) } );
      }
      if ( siempre ) {
        out.add( new String[] { Grafo.SIEMPRE, "1", rot.s( "leyenda.siempre" ) } );
      }
    } else if ( g.linaje() ) {
      if ( clave ) {
        out.add( new String[] { Grafo.NORMAL, "1", rot.s( "leyenda.valor" ) } );
        out.add( new String[] { Grafo.CLAVE, "1", rot.s( "leyenda.clave" ) } );
      }
    } else if ( error ) {
      out.add( new String[] { Grafo.ERROR, "1", rot.s( "leyenda.error" ) } );
    }
    if ( inactiva ) {
      out.add( new String[] { Grafo.NORMAL, "0", rot.s( "leyenda.deshabilitado" ) } );
    }
    return out;
  }

  /** Ancho minimo y alto que ocupa la leyenda debajo de un dibujo de ese ancho (0, 0 si no hay). */
  static double[] tamanoLeyenda( List<String[]> items, double ancho ) {
    if ( items.isEmpty() ) {
      return new double[] { 0, 0 };
    }
    double disponible = ancho - 2 * Disposicion.MARGEN;
    double x = 0;
    int lineas = 1;
    double maxItem = 0;
    for ( String[] it : items ) {
      double w = anchoItem( it );
      maxItem = Math.max( maxItem, w );
      if ( x > 0 && x + w > disponible ) {
        lineas++;
        x = 0;
      }
      x += w + 16;
    }
    return new double[] { maxItem + 2 * Disposicion.MARGEN, 4 + lineas * L_LEYENDA + Disposicion.MARGEN / 2 };
  }

  /** Ancho y alto de la leyenda en columna, a la derecha del dibujo. */
  static double[] columnaLeyenda( List<String[]> items ) {
    double w = 0;
    for ( String[] it : items ) {
      w = Math.max( w, anchoItem( it ) );
    }
    return new double[] { w + Disposicion.MARGEN, 2 * Disposicion.MARGEN + items.size() * L_LEYENDA };
  }

  /** Alto de la leyenda en una linea (para comparar variantes antes de saber el ancho). */
  static double altoLeyenda( Grafo g, Rotulos rot ) {
    return leyenda( g, rot ).isEmpty() ? 0 : 4 + L_LEYENDA + Disposicion.MARGEN / 2;
  }

  private static double anchoItem( String[] it ) {
    return MUESTRA + 5 + Tamanos.anchoTexto( it[ 2 ], T_LEYENDA, false );
  }

  private void leyenda( List<String[]> items, double y0, double ancho ) {
    double disponible = ancho - 2 * Disposicion.MARGEN;
    double x = 0;
    double y = y0 + 4 + L_LEYENDA / 2 - Disposicion.MARGEN / 2;
    for ( String[] it : items ) {
      double w = anchoItem( it );
      if ( x > 0 && x + w > disponible ) {
        x = 0;
        y += L_LEYENDA;
      }
      muestra( it, Disposicion.MARGEN + x, y );
      x += w + 16;
    }
  }

  /** Un item de la leyenda: un pedazo de flecha con su trazo y el texto, desde (x0, y centro). */
  private void muestra( String[] it, double x0, double y ) {
    boolean activa = "1".equals( it[ 1 ] );
    String color = color( it[ 0 ], activa, d.c.g.linaje() );
    String guion = guion( it[ 0 ], activa );
    sb.append( "<path d=\"M" ).append( n( x0 ) ).append( ',' ).append( n( y ) ).append( " L" ).append( n( x0 + MUESTRA
      - 6 ) ).append( ',' ).append( n( y ) ).append( "\" fill=\"none\" stroke=\"" ).append( color ).append(
        "\" stroke-width=\"1.4\"" );
    if ( guion != null ) {
      sb.append( " stroke-dasharray=\"" ).append( guion ).append( '"' );
    }
    sb.append( "/>" );
    if ( activa ) {
      punta( new double[] { x0, y }, new double[] { x0 + MUESTRA, y }, color );
    } else {
      cruz( new double[] { x0, y }, new double[] { x0 + MUESTRA, y }, color );
    }
    texto( x0 + MUESTRA + 5, y, it[ 2 ], "start", T_LEYENDA, TENUE, false, null );
  }

  private void leyendaDerecha( List<String[]> items, double x0 ) {
    double y = Disposicion.MARGEN + L_LEYENDA / 2;
    for ( String[] it : items ) {
      muestra( it, x0, y );
      y += L_LEYENDA;
    }
  }

  // ------------------------------------------------------------------ piezas

  private double[] rect( double a, double c, double ta, double tc ) {
    double[] p = d.xy( a, c );
    return d.tb ? new double[] { p[ 0 ], p[ 1 ], tc, ta } : new double[] { p[ 0 ], p[ 1 ], ta, tc };
  }

  /** Polilinea con esquinas redondeadas (radio 6 o menos). */
  private String camino( List<double[]> abs ) {
    double[][] p = new double[ abs.size() ][];
    for ( int i = 0; i < p.length; i++ ) {
      p[ i ] = d.xy( abs.get( i )[ 0 ], abs.get( i )[ 1 ] );
    }
    StringBuilder s = new StringBuilder();
    s.append( 'M' ).append( n( p[ 0 ][ 0 ] ) ).append( ',' ).append( n( p[ 0 ][ 1 ] ) );
    for ( int i = 1; i + 1 < p.length; i++ ) {
      double[] a = p[ i - 1 ];
      double[] b = p[ i ];
      double[] c = p[ i + 1 ];
      double l1 = Math.hypot( b[ 0 ] - a[ 0 ], b[ 1 ] - a[ 1 ] );
      double l2 = Math.hypot( c[ 0 ] - b[ 0 ], c[ 1 ] - b[ 1 ] );
      if ( l1 < 0.01 || l2 < 0.01 ) {
        continue;
      }
      double r = Math.min( 6, Math.min( l1, l2 ) / 2 );
      double[] e = { b[ 0 ] - ( b[ 0 ] - a[ 0 ] ) / l1 * r, b[ 1 ] - ( b[ 1 ] - a[ 1 ] ) / l1 * r };
      double[] f = { b[ 0 ] + ( c[ 0 ] - b[ 0 ] ) / l2 * r, b[ 1 ] + ( c[ 1 ] - b[ 1 ] ) / l2 * r };
      s.append( " L" ).append( n( e[ 0 ] ) ).append( ',' ).append( n( e[ 1 ] ) ).append( " Q" ).append( n( b[ 0 ] ) )
        .append( ',' ).append( n( b[ 1 ] ) ).append( ' ' ).append( n( f[ 0 ] ) ).append( ',' ).append( n( f[ 1 ] ) );
    }
    double[] u = p[ p.length - 1 ];
    s.append( " L" ).append( n( u[ 0 ] ) ).append( ',' ).append( n( u[ 1 ] ) );
    return s.toString();
  }

  private void punta( double[] a, double[] b, String color ) {
    double dx = b[ 0 ] - a[ 0 ];
    double dy = b[ 1 ] - a[ 1 ];
    double l = Math.hypot( dx, dy );
    if ( l < 0.01 ) {
      return;
    }
    dx /= l;
    dy /= l;
    double bx = b[ 0 ] - dx * 7;
    double by = b[ 1 ] - dy * 7;
    sb.append( "<path d=\"M" ).append( n( b[ 0 ] ) ).append( ',' ).append( n( b[ 1 ] ) ).append( " L" ).append( n( bx
      - dy * 4 ) ).append( ',' ).append( n( by + dx * 4 ) ).append( " L" ).append( n( bx + dy * 4 ) ).append( ',' )
      .append( n( by - dx * 4 ) ).append( " Z\" fill=\"" ).append( color ).append( "\"/>" );
  }

  private void cruz( double[] a, double[] b, String color ) {
    double dx = b[ 0 ] - a[ 0 ];
    double dy = b[ 1 ] - a[ 1 ];
    double l = Math.hypot( dx, dy );
    if ( l < 0.01 ) {
      return;
    }
    dx /= l;
    dy /= l;
    double cx = b[ 0 ] - dx * 6;
    double cy = b[ 1 ] - dy * 6;
    for ( int s = -1; s <= 1; s += 2 ) {
      sb.append( "<line x1=\"" ).append( n( cx - 4 * ( dx + s * dy ) ) ).append( "\" y1=\"" ).append( n( cy - 4 * ( dy
        - s * dx ) ) ).append( "\" x2=\"" ).append( n( cx + 4 * ( dx + s * dy ) ) ).append( "\" y2=\"" ).append( n( cy
          + 4 * ( dy - s * dx ) ) ).append( "\" stroke=\"" ).append( color ).append( "\" stroke-width=\"1.6\"/>" );
    }
  }

  private void texto( double x, double yCentro, String t, String anchor, double tam, String fill, boolean negrita,
                      String completo ) {
    sb.append( "<text x=\"" ).append( n( x ) ).append( "\" y=\"" ).append( n( yCentro + tam * 0.35 ) ).append( '"' );
    if ( !"start".equals( anchor ) ) {
      sb.append( " text-anchor=\"" ).append( anchor ).append( '"' );
    }
    sb.append( " font-family=\"" ).append( FUENTE ).append( "\" font-size=\"" ).append( n( tam ) ).append(
      "\" fill=\"" ).append( fill ).append( '"' );
    if ( negrita ) {
      sb.append( " font-weight=\"bold\"" );
    }
    sb.append( '>' );
    if ( completo != null && !completo.equals( t ) ) {
      sb.append( "<title>" ).append( esc( completo ) ).append( "</title>" );
    }
    sb.append( esc( t ) ).append( "</text>" );
  }

  static String color( String clase, boolean activa, boolean linaje ) {
    if ( !activa ) {
      return GRIS_CLARO;
    }
    switch ( clase ) {
      case Grafo.ERROR:
      case Grafo.FALLA:
        return ROJO;
      case Grafo.OK:
        return VERDE;
      case Grafo.CLAVE:
        return ACENTO;
      default:
        return linaje ? GRIS : TENUE;
    }
  }

  static String guion( String clase, boolean activa ) {
    if ( !activa ) {
      return "2 3";
    }
    switch ( clase ) {
      case Grafo.ERROR:
      case Grafo.FALLA:
        return "5 3";
      case Grafo.CLAVE:
        return "4 3";
      default:
        return null;
    }
  }

  /** Texto seguro para XML 1.0: escapes y sin caracteres de control ni sustitutos sueltos. */
  static String esc( String s ) {
    if ( s == null ) {
      return "";
    }
    StringBuilder b = new StringBuilder( s.length() + 16 );
    for ( int i = 0; i < s.length(); i++ ) {
      char ch = s.charAt( i );
      switch ( ch ) {
        case '&':
          b.append( "&amp;" );
          break;
        case '<':
          b.append( "&lt;" );
          break;
        case '>':
          b.append( "&gt;" );
          break;
        case '"':
          b.append( "&quot;" );
          break;
        case '\'':
          b.append( "&#39;" );
          break;
        default:
          if ( Character.isHighSurrogate( ch ) && i + 1 < s.length() && Character.isLowSurrogate( s.charAt( i
            + 1 ) ) ) {
            b.append( ch ).append( s.charAt( ++i ) );
          } else if ( ch >= 0x20 && !Character.isSurrogate( ch ) && ch != 0xFFFE && ch != 0xFFFF ) {
            b.append( ch );
          } else if ( ch == '\t' || ch == '\n' || ch == '\r' ) {
            b.append( ' ' );
          }
      }
    }
    return b.toString();
  }

  static String n( double v ) {
    long l = Math.round( v * 100 );
    if ( l % 100 == 0 ) {
      return Long.toString( l / 100 );
    }
    String s = String.format( Locale.ROOT, "%.2f", l / 100.0 );
    return s.endsWith( "0" ) ? s.substring( 0, s.length() - 1 ) : s;
  }
}
