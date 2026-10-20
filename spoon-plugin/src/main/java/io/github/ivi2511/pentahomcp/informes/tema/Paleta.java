package io.github.ivi2511.pentahomcp.informes.tema;

import java.util.Locale;

/**
 * Colores derivados del {@link Tema} (diseno §3.6 y §4.2): series de los graficos, grises neutros y
 * tonos claros (10 % para fondos, 8 % para la cabecera de tablas). Todo en "#RRGGBB" mayuscula.
 * <p>
 * Nunca lanza excepciones: un color roto del usuario (por ejemplo "azul" en informes.json) se reemplaza
 * por el default de la plantilla neutra.
 *
 * @dueño WP6
 */
public final class Paleta {

  /** Gris de las series 4 y de los ejes secundarios. */
  public static final String GRIS_MEDIO = "#8794A3";
  /** Gris de la serie 5 y de los tramos "init" del Gantt. */
  public static final String GRIS_CLARO = "#B4BEC9";

  /** Fraccion del primario para fondos claros (§4.2). */
  public static final float FONDO = 0.10f;
  /** Fraccion del primario para la cabecera clara de tablas (§4.2). */
  public static final float CABECERA = 0.08f;

  /** Cantidad de colores distintos de {@link #serie}; despues se repiten. */
  public static final int SERIES = 5;

  private Paleta() {
  }

  /** El primario del tema, normalizado (default {@link Tema#PRIMARIO}). */
  public static String primario( Tema t ) {
    return normalizar( t == null || t.colores == null ? null : t.colores.primario, Tema.PRIMARIO );
  }

  /** El acento del tema, normalizado (default {@link Tema#ACENTO}). */
  public static String acento( Tema t ) {
    return normalizar( t == null || t.colores == null ? null : t.colores.acento, Tema.ACENTO );
  }

  /** El secundario del tema, normalizado (default {@link Tema#SECUNDARIO}). */
  public static String secundario( Tema t ) {
    return normalizar( t == null || t.colores == null ? null : t.colores.secundario, Tema.SECUNDARIO );
  }

  /**
   * Color de la serie i: 0 = acento, 1 = secundario, 2 = primario, 3 y 4 = grises neutros; de la 5 en
   * adelante se repiten (GraficosSvg las distingue ademas por el trazo). En "#RRGGBB".
   */
  public static String serie( Tema t, int i ) {
    switch ( Math.max( 0, i ) % SERIES ) {
      case 0:
        return acento( t );
      case 1:
        return secundario( t );
      case 2:
        return primario( t );
      case 3:
        return GRIS_MEDIO;
      default:
        return GRIS_CLARO;
    }
  }

  /** Fondo claro de bloques y bandas: el primario al 10 %. */
  public static String fondo( Tema t ) {
    return claro( primario( t ), FONDO );
  }

  /** Cabecera clara de tablas: el primario al 8 %. */
  public static String cabeceraTabla( Tema t ) {
    return claro( primario( t ), CABECERA );
  }

  /** El color mezclado con blanco: fraccion 0.10 = 10 % del color y 90 % de blanco. "#RRGGBB". */
  public static String claro( String hex, float fraccion ) {
    return mezclar( hex, Tema.FONDO, fraccion );
  }

  /** El color mezclado con negro: fraccion 0.80 = 80 % del color y 20 % de negro. "#RRGGBB". */
  public static String oscuro( String hex, float fraccion ) {
    return mezclar( hex, "#000000", fraccion );
  }

  /** fraccionA de a y el resto de b (fraccionA se recorta a [0, 1]). "#RRGGBB". */
  public static String mezclar( String a, String b, float fraccionA ) {
    float f = Float.isNaN( fraccionA ) ? 0f : Math.max( 0f, Math.min( 1f, fraccionA ) );
    int[] x = rgb( normalizar( a, "#000000" ) );
    int[] y = rgb( normalizar( b, Tema.FONDO ) );
    int[] r = new int[ 3 ];
    for ( int i = 0; i < 3; i++ ) {
      r[ i ] = Math.round( x[ i ] * f + y[ i ] * ( 1f - f ) );
    }
    return hex( r[ 0 ], r[ 1 ], r[ 2 ] );
  }

  /**
   * Color de texto legible sobre ese fondo: blanco o {@link Tema#TEXTO}, el de mas contraste (por
   * ejemplo el texto de la cabecera de una tabla pintada con el primario).
   */
  public static String textoSobre( String fondoHex ) {
    String f = normalizar( fondoHex, Tema.FONDO );
    return contraste( f, "#FFFFFF" ) >= contraste( f, Tema.TEXTO ) ? "#FFFFFF" : Tema.TEXTO;
  }

  /** Relacion de contraste WCAG 2 entre dos colores (1 a 21). */
  public static double contraste( String a, String b ) {
    double la = luminancia( normalizar( a, "#000000" ) );
    double lb = luminancia( normalizar( b, "#000000" ) );
    double claro = Math.max( la, lb );
    double oscuro = Math.min( la, lb );
    return ( claro + 0.05 ) / ( oscuro + 0.05 );
  }

  /** Luminancia relativa WCAG 2 (0 negro, 1 blanco). */
  public static double luminancia( String hex ) {
    int[] c = rgb( normalizar( hex, "#000000" ) );
    double[] l = new double[ 3 ];
    for ( int i = 0; i < 3; i++ ) {
      double s = c[ i ] / 255.0;
      l[ i ] = s <= 0.03928 ? s / 12.92 : Math.pow( ( s + 0.055 ) / 1.055, 2.4 );
    }
    return 0.2126 * l[ 0 ] + 0.7152 * l[ 1 ] + 0.0722 * l[ 2 ];
  }

  /** true si es "#RGB" o "#RRGGBB" (el "#" es opcional; espacios alrededor se ignoran). */
  public static boolean valido( String hex ) {
    return limpio( hex ) != null;
  }

  /** "#3f74d8", "3F74D8" o "#38d" a "#3F74D8"; si no es un color, defecto (tal cual). */
  public static String normalizar( String hex, String defecto ) {
    String l = limpio( hex );
    return l == null ? defecto : l;
  }

  /** {r, g, b} de 0 a 255; un color invalido da negro. */
  public static int[] rgb( String hex ) {
    String l = limpio( hex );
    if ( l == null ) {
      return new int[] { 0, 0, 0 };
    }
    int v = Integer.parseInt( l.substring( 1 ), 16 );
    return new int[] { ( v >> 16 ) & 0xFF, ( v >> 8 ) & 0xFF, v & 0xFF };
  }

  /** "#RRGGBB" de tres componentes (se recortan a 0-255). */
  public static String hex( int r, int g, int b ) {
    return String.format( Locale.ROOT, "#%02X%02X%02X", rango( r ), rango( g ), rango( b ) );
  }

  private static int rango( int v ) {
    return Math.max( 0, Math.min( 255, v ) );
  }

  /** "#RRGGBB" en mayuscula, o null si no es un color hexadecimal. */
  private static String limpio( String hex ) {
    if ( hex == null ) {
      return null;
    }
    String s = hex.trim();
    if ( s.startsWith( "#" ) ) {
      s = s.substring( 1 );
    }
    if ( s.length() != 3 && s.length() != 6 ) {
      return null;
    }
    for ( int i = 0; i < s.length(); i++ ) {
      char c = s.charAt( i );
      boolean hexa = ( c >= '0' && c <= '9' ) || ( c >= 'a' && c <= 'f' ) || ( c >= 'A' && c <= 'F' );
      if ( !hexa ) {
        return null;
      }
    }
    if ( s.length() == 3 ) {
      s = new String( new char[] { s.charAt( 0 ), s.charAt( 0 ), s.charAt( 1 ), s.charAt( 1 ), s.charAt( 2 ), s
        .charAt( 2 ) } );
    }
    return "#" + s.toUpperCase( Locale.ROOT );
  }
}
