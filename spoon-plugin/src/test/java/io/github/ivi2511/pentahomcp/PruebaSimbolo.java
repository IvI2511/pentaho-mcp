package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.saltear;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.ivi2511.pentahomcp.informes.graficos.SvgAPng;
import io.github.ivi2511.pentahomcp.informes.modelo.ModeloJson;
import io.github.ivi2511.pentahomcp.informes.tema.SimboloIdc;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Simbolo IDC (diseno §4.3, WP6): la rejilla, el SVG igual al del manual de marca (rect por rect y byte a
 * byte cuando la carpeta del manual existe), los colores de tokens.json, la marca de agua al 8 % y el
 * PNG con alfa. Deja muestras en {@code <prueba>\salida\simbolo}.
 *
 * @dueño WP6
 */
public class PruebaSimbolo {

  private static final String[] VARIANTES = { SimboloIdc.CLARO, SimboloIdc.OSCURO, SimboloIdc.BLANCO };

  public void pruebaRejilla() {
    igual( 37.0, SimboloIdc.ANCHO, "ancho del viewBox" );
    igual( 21.0, SimboloIdc.ALTO, "alto del viewBox" );
    igual( SimboloIdc.A, SimboloIdc.tipo( 0, 1 ), "(0,1)" );
    igual( SimboloIdc.O, SimboloIdc.tipo( 1, 2 ), "(1,2)" );
    igual( SimboloIdc.A, SimboloIdc.tipo( 2, 0 ), "(2,0)" );
    igual( SimboloIdc.A, SimboloIdc.tipo( 2, 3 ), "(2,3)" );
    int apagadas = 0;
    for ( int f = 0; f < SimboloIdc.FILAS; f++ ) {
      for ( int c = 0; c < SimboloIdc.COLUMNAS; c++ ) {
        if ( SimboloIdc.APAGADA.equals( SimboloIdc.tipo( f, c ) ) ) {
          apagadas++;
        }
      }
    }
    igual( 8, apagadas, "8 celdas apagadas y 4 encendidas" );
    igual( SimboloIdc.CLARO, SimboloIdc.variante( "rara" ), "variante desconocida = claro" );
    igual( SimboloIdc.OSCURO, SimboloIdc.variante( " Oscuro " ), "variante sin distinguir mayusculas" );
  }

  public void pruebaSvgValido() throws Exception {
    for ( String v : VARIANTES ) {
      for ( float op : new float[] { 1f, SimboloIdc.OPACIDAD_MARCA } ) {
        String svg = SimboloIdc.svg( v, op );
        Document d = xml( svg );
        Element raiz = d.getDocumentElement();
        igual( "svg", raiz.getLocalName(), "raiz" );
        igual( "0 0 37.00 21.00", raiz.getAttribute( "viewBox" ), "viewBox " + v );
        igual( 12, d.getElementsByTagNameNS( "*", "rect" ).getLength(), "12 celdas en " + v );
        Element g = (Element) d.getElementsByTagNameNS( "*", "g" ).item( 0 );
        if ( op < 1f ) {
          igual( "0.08", g.getAttribute( "opacity" ), "opacidad de la marca de agua en " + v );
        } else {
          igual( "", g.getAttribute( "opacity" ), "sin opacidad cuando es 1 en " + v );
        }
        noContiene( svg, "rgba(", "nada de rgba(): Batik 1.9 no lo entiende" );
        noContiene( svg, "<style", "sin CSS" );
      }
    }
    String pie = SimboloIdc.svgPorAlto( SimboloIdc.CLARO, 1f, SimboloIdc.PIE_ALTO_PX );
    contiene( pie, "height=\"8\"", "pie de 8 px de alto" );
    contiene( pie, "width=\"14.1\"", "ancho del pie por la proporcion 37:21" );
    contiene( SimboloIdc.svg( SimboloIdc.CLARO, 1f, 37 ), "width=\"37\" height=\"21\"", "ancho pedido" );
    String uri = SimboloIdc.dataUri( pie );
    cierto( uri.startsWith( "data:image/svg+xml;base64," ), "data URI" );
  }

  /** Rect por rect y byte a byte contra logo\simbolo-*.svg del manual de marca. */
  public void pruebaIgualAlManual() throws Exception {
    File manual = manual();
    if ( manual == null ) {
      saltear( "no esta la carpeta del Manual de marca de IDC Studios" );
    }
    File dir = new File( manual, "logo" );
    for ( String v : VARIANTES ) {
      File f = new File( dir, "simbolo-" + v + ".svg" );
      if ( !f.isFile() ) {
        throw new Verificador.Falla( "falta " + f );
      }
      String original = new String( Files.readAllBytes( f.toPath() ), "UTF-8" ).trim();
      String nuestro = SimboloIdc.svg( v, 1f );
      List<String[]> a = rects( original );
      List<String[]> b = rects( nuestro );
      igual( a.size(), b.size(), "cantidad de rect en " + v );
      for ( int i = 0; i < a.size(); i++ ) {
        String[] x = a.get( i );
        String[] y = b.get( i );
        for ( int k = 0; k < 5; k++ ) {
          igual( x[ k ], y[ k ], "rect " + i + " de " + v + " atributo " + k );
        }
        // en blanco el manual usa rgba(255,255,255,0.22): nosotros #ffffff con fill-opacity 0.22
        String fillManual = x[ 5 ].replace( " ", "" );
        String fillNuestro = y[ 6 ].isEmpty() ? y[ 5 ] : "rgba(255,255,255," + y[ 6 ] + ")";
        igual( fillManual, fillNuestro, "color del rect " + i + " de " + v );
      }
      if ( !SimboloIdc.BLANCO.equals( v ) ) {
        igual( original, nuestro, "simbolo-" + v + ".svg byte a byte" );
      }
    }
  }

  /** Los colores salen de tokens.json > simbolo, y las encendidas de generar_marca.py. */
  public void pruebaTokensYGenerador() throws Exception {
    File manual = manual();
    if ( manual == null ) {
      saltear( "no esta la carpeta del Manual de marca de IDC Studios" );
    }
    File tokens = new File( manual, "tokens\\tokens.json" );
    JsonNode s = ModeloJson.mapper().readTree( new String( Files.readAllBytes( tokens.toPath() ), "UTF-8" ) ).get(
      "simbolo" );
    for ( String v : new String[] { SimboloIdc.CLARO, SimboloIdc.OSCURO } ) {
      igual( s.get( v ).get( "apagada" ).asText(), SimboloIdc.color( v, SimboloIdc.APAGADA ), v + " apagada" );
      igual( s.get( v ).get( "a" ).asText(), SimboloIdc.color( v, SimboloIdc.A ), v + " a" );
      igual( s.get( v ).get( "o" ).asText(), SimboloIdc.color( v, SimboloIdc.O ), v + " o" );
    }
    igual( "#ffffff", s.get( "blanco" ).get( "a" ).asText(), "blanco a" );
    File py = new File( manual, "herramientas\\generar_marca.py" );
    String fuente = new String( Files.readAllBytes( py.toPath() ), "UTF-8" );
    Matcher m = Pattern.compile( "ENCENDIDAS\\s*=\\s*\\{([^}]*)\\}" ).matcher( fuente );
    cierto( m.find(), "ENCENDIDAS en generar_marca.py" );
    Matcher p = Pattern.compile( "\\((\\d+),\\s*(\\d+)\\)\\s*:\\s*\"(\\w+)\"" ).matcher( m.group( 1 ) );
    int n = 0;
    while ( p.find() ) {
      int f = Integer.parseInt( p.group( 1 ) );
      int c = Integer.parseInt( p.group( 2 ) );
      igual( p.group( 3 ), SimboloIdc.tipo( f, c ), "celda (" + f + "," + c + ") de generar_marca.py" );
      n++;
    }
    igual( 4, n, "4 encendidas en generar_marca.py" );
    Matcher med = Pattern.compile( "CELDA_W, CELDA_H, SEP, RADIO = ([0-9.]+), ([0-9.]+), ([0-9.]+), ([0-9.]+)" )
      .matcher( fuente );
    cierto( med.find(), "medidas en generar_marca.py" );
    igual( SimboloIdc.CELDA_ANCHO, Double.parseDouble( med.group( 1 ) ), "CELDA_W" );
    igual( SimboloIdc.CELDA_ALTO, Double.parseDouble( med.group( 2 ) ), "CELDA_H" );
    igual( SimboloIdc.SEPARACION, Double.parseDouble( med.group( 3 ) ), "SEP" );
    igual( SimboloIdc.RADIO, Double.parseDouble( med.group( 4 ) ), "RADIO" );
  }

  /** PNG ARGB: colores en el centro de las celdas, transparente entre celdas y alfa 8 % en la marca. */
  public void pruebaPng() throws Exception {
    File sal = EntornoKettle.salida( "simbolo" );
    byte[] b = SimboloIdc.png( 370, SimboloIdc.CLARO, 1f );
    Files.write( new File( sal, "simbolo-claro-370.png" ).toPath(), b );
    BufferedImage img = ImageIO.read( new ByteArrayInputStream( b ) );
    igual( 370, img.getWidth(), "ancho" );
    igual( 210, img.getHeight(), "alto (370 x 21 / 37)" );
    cierto( img.getColorModel().hasAlpha(), "con canal alfa" );
    // 10 px por unidad: centro de la celda (f, c) = ((c * 10 + 3,5) * 10, (f * 8 + 2,5) * 10)
    pixel( img, 135, 25, 0xFF3F74D8, "celda (0,1) azul" );
    pixel( img, 235, 105, 0xFF1C9A80, "celda (1,2) verde" );
    pixel( img, 35, 185, 0xFF3F74D8, "celda (2,0) azul" );
    pixel( img, 335, 185, 0xFF3F74D8, "celda (2,3) azul" );
    pixel( img, 35, 25, 0xFFD6DEEA, "celda (0,0) apagada" );
    igual( 0, img.getRGB( 85, 25 ) >>> 24, "transparente entre columnas" );
    igual( 0, img.getRGB( 135, 65 ) >>> 24, "transparente entre filas" );

    byte[] m = SimboloIdc.png( 370, SimboloIdc.CLARO, SimboloIdc.OPACIDAD_MARCA );
    Files.write( new File( sal, "simbolo-marca-8.png" ).toPath(), m );
    BufferedImage marca = ImageIO.read( new ByteArrayInputStream( m ) );
    int alfa = marca.getRGB( 135, 25 ) >>> 24;
    cierto( Math.abs( alfa - 20 ) <= 1, "alfa de la marca de agua = 8 % de 255 (20): " + alfa );
    pixel( marca, 135, 25, ( alfa << 24 ) | 0x3F74D8, 8, "color de la marca de agua (sin premultiplicar)" );

    BufferedImage blanco = ImageIO.read( new ByteArrayInputStream( SimboloIdc.png( 370, SimboloIdc.BLANCO,
      1f ) ) );
    int alfaApagada = blanco.getRGB( 35, 25 ) >>> 24;
    cierto( Math.abs( alfaApagada - 56 ) <= 1, "apagada de blanco al 22 %: " + alfaApagada );
    pixel( blanco, 135, 25, 0xFFFFFFFF, "encendida de blanco" );
  }

  /** Batik dibuja el SVG del simbolo igual que el PNG propio (mismos colores en los centros). */
  public void pruebaBatik() throws Exception {
    File sal = EntornoKettle.salida( "simbolo" );
    String svg = SimboloIdc.svg( SimboloIdc.CLARO, 1f );
    Files.write( new File( sal, "simbolo-claro.svg" ).toPath(), svg.getBytes( "UTF-8" ) );
    byte[] b = SvgAPng.png( svg, 370 );
    Files.write( new File( sal, "simbolo-claro-batik.png" ).toPath(), b );
    BufferedImage img = ImageIO.read( new ByteArrayInputStream( b ) );
    igual( 370, img.getWidth(), "ancho Batik" );
    igual( 210, img.getHeight(), "alto Batik" );
    pixel( img, 135, 25, 0xFF3F74D8, "Batik: celda (0,1)" );
    pixel( img, 235, 105, 0xFF1C9A80, "Batik: celda (1,2)" );
    pixel( img, 35, 25, 0xFFD6DEEA, "Batik: celda apagada" );
    BufferedImage marca = ImageIO.read( new ByteArrayInputStream( SvgAPng.png( SimboloIdc.svg( SimboloIdc.CLARO,
      SimboloIdc.OPACIDAD_MARCA ), 370 ) ) );
    int alfa = marca.getRGB( 135, 25 ) >>> 24;
    cierto( Math.abs( alfa - 20 ) <= 2, "Batik respeta opacity=0.08 del grupo: alfa " + alfa );
    BufferedImage bl = ImageIO.read( new ByteArrayInputStream( SvgAPng.png( SimboloIdc.svg( SimboloIdc.BLANCO, 1f ),
      370 ) ) );
    cierto( Math.abs( ( bl.getRGB( 35, 25 ) >>> 24 ) - 56 ) <= 2, "Batik respeta fill-opacity 0.22 de blanco" );

    // pagina de muestra: pie de una linea y marca de agua, para mirarla en un navegador o IE11
    String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta http-equiv=\"X-UA-Compatible\""
      + " content=\"IE=edge\"><title>Simbolo IDC</title></head><body style=\"font-family:" + Tema.FUENTE
        .replace( '"', '\'' ) + ";color:" + Tema.TEXTO + "\"><h1>Muestra</h1><p>Texto del informe.</p>"
      + "<div style=\"position:fixed;right:12mm;bottom:12mm;width:" + SimboloIdc.MARCA_ANCHO_MM + "mm;opacity:"
      + SimboloIdc.OPACIDAD_MARCA + ";z-index:-1\">" + SimboloIdc.svg( SimboloIdc.CLARO, 1f ).replace(
        "width=\"148\" height=\"84\"", "width=\"100%\" height=\"100%\"" ) + "</div>"
      + "<p style=\"font-size:" + SimboloIdc.PIE_PT + "pt;color:" + Tema.TEXTO_TENUE + "\">" + SimboloIdc
        .svgPorAlto( SimboloIdc.CLARO, 1f, SimboloIdc.PIE_ALTO_PX ) + " " + Tema.PIE + "</p></body></html>";
    Files.write( new File( sal, "simbolo.html" ).toPath(), html.getBytes( "UTF-8" ) );
  }

  // ------------------------------------------------------------------ ayudas

  private static void pixel( BufferedImage img, int x, int y, int argb, String msg ) {
    pixel( img, x, y, argb, 2, msg );
  }

  /** El pixel tiene ese ARGB, con tolerancia por canal. */
  static void pixel( BufferedImage img, int x, int y, int argb, int tolerancia, String msg ) {
    int real = img.getRGB( x, y );
    for ( int k = 0; k < 32; k += 8 ) {
      int a = ( argb >>> k ) & 0xFF;
      int b = ( real >>> k ) & 0xFF;
      if ( Math.abs( a - b ) > tolerancia ) {
        throw new Verificador.Falla( msg + ": se esperaba " + String.format( Locale.ROOT, "%08X", argb )
          + " y llego " + String.format( Locale.ROOT, "%08X", real ) + " en (" + x + "," + y + ")" );
      }
    }
  }

  /** x, y, width, height, rx, fill, fill-opacity de cada rect. */
  private static List<String[]> rects( String svg ) throws Exception {
    NodeList l = xml( svg ).getElementsByTagNameNS( "*", "rect" );
    List<String[]> r = new ArrayList<>();
    for ( int i = 0; i < l.getLength(); i++ ) {
      Element e = (Element) l.item( i );
      r.add( new String[] { e.getAttribute( "x" ), e.getAttribute( "y" ), e.getAttribute( "width" ), e
        .getAttribute( "height" ), e.getAttribute( "rx" ), e.getAttribute( "fill" ), e.getAttribute(
          "fill-opacity" ) } );
    }
    return r;
  }

  static Document xml( String s ) throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware( true );
    f.setFeature( "http://apache.org/xml/features/disallow-doctype-decl", true );
    return f.newDocumentBuilder().parse( new InputSource( new StringReader( s ) ) );
  }

  /** La carpeta del Manual de marca de IDC Studios, o null. */
  static File manual() {
    List<File> c = new ArrayList<>();
    String p = System.getProperty( "pruebas.marca" );
    if ( p != null ) {
      c.add( new File( p ) );
    }
    String perfil = System.getenv( "USERPROFILE" );
    if ( perfil != null ) {
      c.add( new File( perfil, "Documents\\IDC Studios\\Manual de marca" ) );
    }
    String pdi = System.getProperty( "pruebas.pdi" );
    if ( pdi != null ) {
      // <Documents>\Pentaho\PDI 9.4\data-integration
      File docs = new File( pdi ).getAbsoluteFile().getParentFile();
      for ( int i = 0; i < 2 && docs != null; i++ ) {
        docs = docs.getParentFile();
      }
      if ( docs != null ) {
        c.add( new File( docs, "IDC Studios\\Manual de marca" ) );
      }
    }
    for ( File f : c ) {
      if ( new File( f, "logo\\simbolo-claro.svg" ).isFile() ) {
        return f;
      }
    }
    return null;
  }
}
