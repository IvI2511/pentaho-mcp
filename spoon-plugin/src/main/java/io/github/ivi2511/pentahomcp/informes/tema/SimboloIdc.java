package io.github.ivi2511.pentahomcp.informes.tema;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

import javax.imageio.ImageIO;

/**
 * Simbolo IDC Studios para el pie y la marca de agua (diseno §4.3).
 * <p>
 * Rejilla de 4 columnas x 3 filas, celdas de 7x5, separacion 3, radio 1,5: viewBox {@code 0 0 37 21}.
 * Encendidas, con clave (fila, columna): (0,1)=a, (1,2)=o, (2,0)=a, (2,3)=a. La fuente de verdad es
 * {@code Manual de marca\herramientas\generar_marca.py} ({@code ENCENDIDAS}); los colores salen de
 * {@code tokens\tokens.json} &gt; {@code simbolo}. {@code svg(CLARO, 1f)} da byte a byte el mismo
 * {@code logo\simbolo-claro.svg} del manual (lo verifica PruebaSimbolo cuando esa carpeta existe).
 * <p>
 * El SVG es apto para IE11, Batik y PowerPoint: atributos inline, sin CSS. En la variante "blanco" las
 * celdas apagadas van como {@code fill="#ffffff" fill-opacity="0.22"} en lugar del {@code rgba()} del
 * manual, que Batik 1.9 no entiende.
 *
 * @dueño WP6
 */
public final class SimboloIdc {

  /** Para fondos claros (la de los informes). */
  public static final String CLARO = "claro";
  /** Para fondos oscuros. */
  public static final String OSCURO = "oscuro";
  /** Monocromo blanco, sobre fondos de color. */
  public static final String BLANCO = "blanco";

  public static final int COLUMNAS = 4;
  public static final int FILAS = 3;
  public static final double CELDA_ANCHO = 7.0;
  public static final double CELDA_ALTO = 5.0;
  public static final double SEPARACION = 3.0;
  public static final double RADIO = 1.5;
  /** 4 x 7 + 3 x 3 = 37. */
  public static final double ANCHO = COLUMNAS * CELDA_ANCHO + ( COLUMNAS - 1 ) * SEPARACION;
  /** 3 x 5 + 2 x 3 = 21. */
  public static final double ALTO = FILAS * CELDA_ALTO + ( FILAS - 1 ) * SEPARACION;

  /** Ancho por defecto del SVG (como el del manual: 4 px por unidad). */
  public static final int ANCHO_PX = 148;

  /** Opacidad de la marca de agua (§4.3). */
  public static final float OPACIDAD_MARCA = 0.08f;
  /** Ancho de la marca de agua en la pagina, en mm. */
  public static final int MARCA_ANCHO_MM = 40;
  /** Alto del simbolo en el pie, en px CSS. */
  public static final int PIE_ALTO_PX = 8;
  /** Tamano del texto del pie, en pt. */
  public static final float PIE_PT = 7.5f;

  /** Tipos de celda. */
  public static final String A = "a";
  public static final String O = "o";
  public static final String APAGADA = "apagada";

  /** Celdas encendidas por fila y columna (null = apagada). */
  private static final String[][] ENCENDIDAS = {
    { null, A, null, null },
    { null, null, O, null },
    { A, null, null, A } };

  private static final String TITULO = "IDC Studios";

  private SimboloIdc() {
  }

  /** "a", "o" o "apagada" para esa celda. */
  public static String tipo( int fila, int columna ) {
    if ( fila < 0 || fila >= FILAS || columna < 0 || columna >= COLUMNAS ) {
      return APAGADA;
    }
    String t = ENCENDIDAS[ fila ][ columna ];
    return t == null ? APAGADA : t;
  }

  /** "claro", "oscuro" o "blanco"; cualquier otra cosa (o null) es "claro". */
  public static String variante( String v ) {
    if ( v != null ) {
      String s = v.trim().toLowerCase( Locale.ROOT );
      if ( OSCURO.equals( s ) || BLANCO.equals( s ) ) {
        return s;
      }
    }
    return CLARO;
  }

  /**
   * Color de un tipo de celda en una variante, como en tokens.json (minuscula). En "blanco" la apagada
   * da "#ffffff" y su transparencia sale de {@link #opacidadCelda}.
   */
  public static String color( String variante, String tipo ) {
    String v = variante( variante );
    boolean apagada = !A.equals( tipo ) && !O.equals( tipo );
    if ( BLANCO.equals( v ) ) {
      return "#ffffff";
    }
    if ( apagada ) {
      return OSCURO.equals( v ) ? "#202b3c" : "#d6deea";
    }
    return A.equals( tipo ) ? "#3f74d8" : "#1c9a80";
  }

  /** Opacidad propia de la celda: 0,22 para las apagadas de "blanco", 1 para el resto. */
  public static float opacidadCelda( String variante, String tipo ) {
    boolean apagada = !A.equals( tipo ) && !O.equals( tipo );
    return BLANCO.equals( variante( variante ) ) && apagada ? 0.22f : 1f;
  }

  /** SVG del simbolo de 148 px de ancho, con esa opacidad (1 = opaco, 0.08 = marca de agua). */
  public static String svg( String variante, float opacidad ) {
    return svg( variante, opacidad, ANCHO_PX );
  }

  /** SVG del simbolo con ese ancho en px (el alto sale de la proporcion 37:21). */
  public static String svg( String variante, float opacidad, double anchoPx ) {
    double w = anchoPx > 0 ? anchoPx : ANCHO_PX;
    return armar( variante( variante ), opacidad, w, w * ALTO / ANCHO );
  }

  /** SVG del simbolo con ese alto en px (para el pie: {@link #PIE_ALTO_PX}). */
  public static String svgPorAlto( String variante, float opacidad, double altoPx ) {
    double h = altoPx > 0 ? altoPx : PIE_ALTO_PX;
    return armar( variante( variante ), opacidad, h * ANCHO / ALTO, h );
  }

  /** {@code data:image/svg+xml;base64,...} para un {@code <img src>} (IE9+ lo dibuja). */
  public static String dataUri( String svg ) {
    return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString( svg.getBytes(
      StandardCharsets.UTF_8 ) );
  }

  /**
   * PNG con alfa (BufferedImage ARGB) de anchoPx de ancho, como bytes de archivo PNG. Fondo
   * transparente; la opacidad multiplica la de cada celda (0.08 = marca de agua de PPTX).
   */
  public static byte[] png( int anchoPx, String variante, float opacidad ) {
    String v = variante( variante );
    int w = Math.max( 1, anchoPx );
    int h = Math.max( 1, (int) Math.round( w * ALTO / ANCHO ) );
    double e = w / ANCHO;
    float op = opacidad( opacidad );
    BufferedImage img = new BufferedImage( w, h, BufferedImage.TYPE_INT_ARGB );
    Graphics2D g = img.createGraphics();
    try {
      g.setRenderingHint( RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON );
      g.setRenderingHint( RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY );
      g.setRenderingHint( RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE );
      for ( int f = 0; f < FILAS; f++ ) {
        for ( int c = 0; c < COLUMNAS; c++ ) {
          String t = tipo( f, c );
          int[] rgb = Paleta.rgb( color( v, t ) );
          int alfa = Math.round( 255f * op * opacidadCelda( v, t ) );
          g.setColor( new Color( rgb[ 0 ], rgb[ 1 ], rgb[ 2 ], Math.max( 0, Math.min( 255, alfa ) ) ) );
          // Java2D pide el diametro del arco: 2 x radio
          g.fill( new RoundRectangle2D.Double( c * ( CELDA_ANCHO + SEPARACION ) * e, f * ( CELDA_ALTO
            + SEPARACION ) * e, CELDA_ANCHO * e, CELDA_ALTO * e, 2 * RADIO * e, 2 * RADIO * e ) );
        }
      }
    } finally {
      g.dispose();
    }
    try {
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      if ( !ImageIO.write( img, "png", bo ) ) {
        throw new IllegalStateException( "ImageIO no tiene escritor PNG" );
      }
      return bo.toByteArray();
    } catch ( IOException e2 ) {
      throw new IllegalStateException( "No se pudo codificar el PNG del simbolo: " + e2.getMessage(), e2 );
    }
  }

  private static float opacidad( float o ) {
    return Float.isNaN( o ) ? 1f : Math.max( 0f, Math.min( 1f, o ) );
  }

  /** Mismo formato que generar_marca.py (celdas con "%.2f"). */
  private static String armar( String v, float opacidad, double w, double h ) {
    float op = opacidad( opacidad );
    StringBuilder sb = new StringBuilder( 1300 );
    sb.append( "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " ).append( dos( ANCHO ) ).append( ' ' )
      .append( dos( ALTO ) ).append( "\" width=\"" ).append( corto( w ) ).append( "\" height=\"" ).append( corto(
        h ) ).append( "\" role=\"img\" aria-label=\"" ).append( TITULO ).append( "\"><title>" ).append( TITULO )
      .append( "</title><g transform=\"translate(0.00 0.00)\"" );
    if ( op < 1f ) {
      sb.append( " opacity=\"" ).append( corto( op ) ).append( '"' );
    }
    sb.append( '>' );
    for ( int f = 0; f < FILAS; f++ ) {
      for ( int c = 0; c < COLUMNAS; c++ ) {
        String t = tipo( f, c );
        sb.append( "<rect x=\"" ).append( dos( c * ( CELDA_ANCHO + SEPARACION ) ) ).append( "\" y=\"" ).append( dos(
          f * ( CELDA_ALTO + SEPARACION ) ) ).append( "\" width=\"" ).append( dos( CELDA_ANCHO ) ).append(
            "\" height=\"" ).append( dos( CELDA_ALTO ) ).append( "\" rx=\"" ).append( dos( RADIO ) ).append(
              "\" fill=\"" ).append( color( v, t ) ).append( '"' );
        float oc = opacidadCelda( v, t );
        if ( oc < 1f ) {
          sb.append( " fill-opacity=\"" ).append( corto( oc ) ).append( '"' );
        }
        sb.append( "/>" );
      }
    }
    sb.append( "</g></svg>" );
    return sb.toString();
  }

  /** Dos decimales fijos, con punto. */
  private static String dos( double d ) {
    return String.format( Locale.ROOT, "%.2f", d );
  }

  /** Hasta dos decimales, sin ceros de mas: 148, 8.5, 0.08. */
  private static String corto( double d ) {
    String s = String.format( Locale.ROOT, "%.2f", d );
    if ( s.indexOf( '.' ) >= 0 ) {
      s = s.replaceAll( "0+$", "" );
      if ( s.endsWith( "." ) ) {
        s = s.substring( 0, s.length() - 1 );
      }
    }
    return s;
  }
}
