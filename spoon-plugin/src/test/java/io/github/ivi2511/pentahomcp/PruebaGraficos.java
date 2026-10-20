package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.lanza;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.graficos.GraficosSvg;
import io.github.ivi2511.pentahomcp.informes.graficos.SvgAPng;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Grafico;
import io.github.ivi2511.pentahomcp.informes.modelo.ModeloJson;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Serie;
import io.github.ivi2511.pentahomcp.informes.modelo.Tramo;
import io.github.ivi2511.pentahomcp.informes.tema.Paleta;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Graficos SVG propios y SvgAPng (diseno §3.6, WP6): SVG 1.1 valido (se parsea como XML), apto para IE11
 * (sin CSS, sin dominant-baseline, rect de fondo que cubre el viewBox), numeros con los separadores del
 * idioma, topes (25 barras y «+N mas», 5 series), escapes, y PNG con Batik. Deja cada SVG y PNG en
 * {@code <prueba>\salida\graficos}, mas {@code graficos.html} con todos inline para mirarlos en IE11.
 *
 * @dueño WP6
 */
public class PruebaGraficos {

  /** Lo que se genera, para la pagina de muestra (id -&gt; svg). */
  private final Map<String, String> muestras = new LinkedHashMap<>();

  public void cerrar() throws Exception {
    if ( muestras.isEmpty() ) {
      return;
    }
    StringBuilder sb = new StringBuilder( "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\">"
      + "<meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\"><title>Graficos SVG</title></head>"
      + "<body style=\"font-family:" + Tema.FUENTE.replace( '"', '\'' ) + ";color:" + Tema.TEXTO
      + ";background:#F3F5F8\">" );
    for ( Map.Entry<String, String> e : muestras.entrySet() ) {
      sb.append( "<h3 style=\"font-size:13px;margin:16px 0 4px\">" ).append( e.getKey() ).append( "</h3>\n" )
        .append( e.getValue() ).append( '\n' );
    }
    sb.append( "</body></html>\n" );
    Files.write( new File( EntornoKettle.salida( "graficos" ), "graficos.html" ).toPath(), sb.toString().getBytes(
      StandardCharsets.UTF_8 ) );
  }

  /** Los tres graficos de ModeloMuestra en es y en: estructura, numeros y Batik. */
  public void pruebaModeloMuestra() throws Exception {
    Tema t = ModeloMuestra.tema();
    Map<String, Grafico> gs = graficos( ModeloMuestra.crear() );
    igual( 4, gs.size(), "graficos de ModeloMuestra" );

    Grafico barras = gs.get( "metrics-barras" );
    String es = verificar( "metrics-barras-es", barras, t, "es" );
    contiene( es, ">400.000</text>", "miles con punto en es" );
    contiene( es, ">1.700</text>", "1.700 ms en es" );
    contiene( es, ">Filas</text>", "leyenda de la serie 1" );
    contiene( es, ">ms</text>", "leyenda de la serie 2" );
    contiene( es, "fill=\"" + Tema.ACENTO + "\"", "serie 1 en el acento" );
    contiene( es, "fill=\"" + Tema.SECUNDARIO + "\"", "serie 2 en el secundario" );
    String en = verificar( "metrics-barras-en", barras, t, "en" );
    contiene( en, ">400,000</text>", "miles con coma en en" );

    String nulos = verificar( "profile-nulos-es", gs.get( "profile-nulos" ), t, "es" );
    contiene( nulos, ">2,4 %</text>", "decimal con coma y unidad" );
    contiene( verificar( "profile-nulos-pt", gs.get( "profile-nulos" ), t, "pt" ), ">2,4 %</text>", "pt" );
    contiene( verificar( "profile-nulos-en", gs.get( "profile-nulos" ), t, "en" ), ">2.4 %</text>", "en" );
    // ordenadas de mayor a menor: "precio" (2,4) va primero
    igual( "precio", textos( nulos ).get( 1 ), "categorias ordenadas por valor (despues de la leyenda)" );

    String gantt = verificar( "metrics-gantt-es", gs.get( "metrics-gantt" ), t, "es" );
    contiene( gantt, ">Inicialización</text>", "leyenda init" );
    contiene( gantt, ">Ejecución</text>", "leyenda exec" );
    contiene( gantt, "en paralelo", "la leyenda explica el solapamiento" );
    contiene( gantt, " s</text>", "eje en segundos (2,7 s)" );
    contiene( gantt, "fill=\"" + Paleta.GRIS_CLARO + "\"", "init en gris" );
    contiene( gantt, "fill=\"" + Tema.PRIMARIO + "\"", "exec en el primario" );
    contiene( gantt, ">1,7 s</text>", "duracion al final de la fila" );
    contiene( verificar( "metrics-gantt-en", gs.get( "metrics-gantt" ), t, "en" ), ">Execution</text>", "en" );
    // un job: sus entradas corren una tras otra; la leyenda propia reemplaza la del paralelismo
    Grafico job = ModeloJson.copia( gs.get( "metrics-gantt" ), Grafico.class );
    job.leyenda = "Las entradas del job corren una tras otra.";
    String gj = verificar( "metrics-gantt-job", job, t, "es" );
    contiene( gj, "Las entradas del job corren una tras otra.", "leyenda del job" );
    noContiene( gj, "en paralelo", "sin la nota del paralelismo" );
    job.leyenda = "";
    noContiene( verificar( "metrics-gantt-sin-nota", job, t, "es" ), "en paralelo", "leyenda vacia: sin nota" );

    String linea = verificar( "metrics-linea-es", gs.get( "metrics-linea" ), t, "es" );
    contiene( linea, "<polyline", "lineas" );
    contiene( linea, ">Leer ventas</text>", "leyenda" );
    contiene( linea, ">filas</text>", "unidad del eje" );
    contiene( linea, ">100.000</text>", "marca del eje con miles" );
    // la serie 2 tiene un null en el medio: dos tramos
    igual( 3, cuenta( linea, "<polyline" ), "1 polyline de la serie 1 y 2 de la serie 2 (cortada por el null)" );
  }

  public void pruebaTopeYEscapes() throws Exception {
    Tema t = new Tema();
    Grafico g = barras( "Muchas categorias", 40 );
    // en posiciones con valores altos, para que queden entre las 25 visibles
    g.categorias.set( 8, "<b>&\"x" );
    g.categorias.set( 19, "Un nombre de step larguisimo que no entra de ninguna manera en la columna de rotulos" );
    g.categorias.set( 30, "control\u0001\u0007 y tab\tok" );
    g.categorias.set( 5, "emoji 🚀 y suelto \uD800" );
    String es = verificar( "barras-40-es", g, t, "es" );
    contiene( es, "+15 más", "40 categorias: 25 barras y «+15 más»" );
    igual( 25, cuenta( es, "<rect" ) - 1 - 1, "25 barras (menos el fondo y la muestra de la leyenda)" );
    contiene( es, "&lt;b&gt;&amp;&quot;x", "escape XML" );
    noContiene( es, "<b>", "nada de HTML crudo" );
    contiene( es, "…", "rotulo recortado" );
    contiene( es, ">control y tab ok</text>", "sin caracteres de control" );
    contiene( es, ">emoji 🚀 y suelto", "el par sustituto queda entero" );
    noContiene( es, "\uD800<", "el sustituto suelto se va" );
    contiene( es, "<title>Un nombre de step larguisimo que no entra de ninguna manera en la columna de rotulos</title>",
      "el nombre completo en un title" );
    contiene( verificar( "barras-40-en", g, t, "en" ), "+15 more", "en" );
  }

  public void pruebaCasosBorde() throws Exception {
    Tema t = new Tema();
    // vacio, clase desconocida, null
    Grafico v = new Grafico();
    v.clase = Grafico.BARRAS_H;
    contiene( verificar( "vacio", v, t, "es" ), ">Sin datos</text>", "sin datos" );
    Grafico raro = barras( "Torta", 3 );
    raro.clase = "torta";
    contiene( verificar( "clase-desconocida", raro, t, "pt" ), ">Sem dados</text>", "clase desconocida" );
    contiene( verificar( "null", null, null, "en" ), ">No data</text>", "grafico null" );

    // valores null, NaN, negativos y cero
    Grafico b = new Grafico();
    b.clase = Grafico.BARRAS_H;
    b.titulo = "Raros";
    b.categorias.addAll( Arrays.asList( "a", "b", "c", "d", "e" ) );
    b.series.add( new Serie( "v", 10.0, null, Double.NaN, -5.0, 0.0 ) );
    String raros = verificar( "valores-raros", b, t, "es" );
    contiene( raros, ">–</text>", "sin dato = raya" );
    contiene( raros, ">-5</text>", "negativo escrito" );

    // una sola serie sin nombre: sin leyenda
    Grafico uno = barras( "Una", 3 );
    uno.series.get( 0 ).nombre = null;
    igual( 4, cuenta( verificar( "una-serie", uno, t, "es" ), "<rect" ), "fondo y 3 barras, sin leyenda" );

    // Gantt de 30 filas en ms, tramo de largo 0 y tramo al reves
    Grafico gantt = new Grafico();
    gantt.clase = Grafico.GANTT;
    for ( int i = 0; i < 30; i++ ) {
      gantt.tramos.add( new Tramo( "Entrada " + i, Tramo.EXEC, i * 20, i * 20 + 15 ) );
    }
    gantt.tramos.add( new Tramo( "Entrada 0", Tramo.INIT, 5, 5 ) );
    gantt.tramos.add( new Tramo( "Entrada 1", Tramo.EXEC, 40, 30 ) );
    String g = verificar( "gantt-30", gantt, t, "es" );
    contiene( g, "+5 más", "30 filas: 25 y «+5 más»" );
    contiene( g, " ms</text>", "eje en ms" );

    // Gantt sin tramos: sin datos
    Grafico gv = new Grafico();
    gv.clase = Grafico.GANTT;
    gv.categorias.add( "x" );
    contiene( verificar( "gantt-vacio", gv, t, "es" ), "Sin datos", "gantt sin tramos" );

    // linea con 7 series, una toda null y un solo punto
    Grafico l = new Grafico();
    l.clase = Grafico.LINEA;
    l.categorias.addAll( Arrays.asList( "1", "2", "3", "4" ) );
    for ( int i = 0; i < 7; i++ ) {
      l.series.add( new Serie( "Serie " + i, i * 1.0, i * 2.0, i == 2 ? null : i * 1.5, i * 0.5 ) );
    }
    l.series.get( 1 ).valores = Arrays.asList( null, null, null, null );
    String lin = verificar( "linea-7", l, t, "es" );
    contiene( lin, "(+2 series sin dibujar)", "tope de 5 series" );
    contiene( lin, "stroke-dasharray=\"6 3\"", "serie 4 rayada" );
    Grafico punto = new Grafico();
    punto.clase = Grafico.LINEA;
    punto.categorias.add( "solo" );
    punto.series.add( new Serie( "s", 3.0 ) );
    contiene( verificar( "linea-1-punto", punto, t, "es" ), "<circle", "un punto suelto" );

    // ancho fuera de rango y colores del usuario
    Tema rojo = new Tema();
    rojo.colores.primario = "#aa0000";
    rojo.colores.acento = "no-es-color";
    String chico = verificar( "ancho-100", barras( "Chico", 3 ), rojo, "es" );
    contiene( chico, "width=\"240\"", "ancho minimo 240" );
    contiene( chico, "fill=\"" + Tema.ACENTO + "\"", "acento invalido = default" );
    contiene( verificar( "gantt-rojo", ganttSimple(), rojo, "es" ), "fill=\"#AA0000\"", "exec con el primario" );

    // determinista
    igual( GraficosSvg.svg( barras( "D", 5 ), t, 720, "es" ), GraficosSvg.svg( barras( "D", 5 ), t, 720, "es" ),
      "misma entrada, mismo SVG" );
    int[] tam = GraficosSvg.tamano( chico );
    igual( 240, tam[ 0 ], "tamano: ancho" );
    cierto( tam[ 1 ] > 50, "tamano: alto" );
  }

  /** Paleta: tonos claros de §4.2, orden de las series, normalizacion y contraste. */
  public void pruebaPaleta() {
    Tema t = new Tema();
    igual( "#E9EBEE", Paleta.claro( Tema.PRIMARIO, 0.10f ), "primario al 10 %" );
    igual( "#E9EBEE", Paleta.fondo( t ), "fondo" );
    igual( "#EDEFF1", Paleta.cabeceraTabla( t ), "cabecera al 8 %" );
    igual( "#FFFFFF", Paleta.claro( "#000", 0f ), "fraccion 0 = blanco" );
    igual( Tema.ACENTO, Paleta.serie( t, 0 ), "serie 0 = acento" );
    igual( Tema.SECUNDARIO, Paleta.serie( t, 1 ), "serie 1 = secundario" );
    igual( Tema.PRIMARIO, Paleta.serie( t, 2 ), "serie 2 = primario" );
    igual( Paleta.GRIS_MEDIO, Paleta.serie( t, 3 ), "serie 3 = gris" );
    igual( Paleta.GRIS_CLARO, Paleta.serie( t, 4 ), "serie 4 = gris claro" );
    igual( Tema.ACENTO, Paleta.serie( t, 5 ), "se repiten" );
    igual( Tema.ACENTO, Paleta.serie( null, -1 ), "tema null e indice negativo" );
    igual( "#3388DD", Paleta.normalizar( " 38d ", null ), "3 digitos sin #" );
    igual( "x", Paleta.normalizar( "#GGGGGG", "x" ), "no hexadecimal" );
    igual( "x", Paleta.normalizar( "#٣٣٣", "x" ), "digitos no ASCII" );
    igual( "#FFFFFF", Paleta.textoSobre( Tema.PRIMARIO ), "texto blanco sobre el primario" );
    igual( Tema.TEXTO, Paleta.textoSobre( Paleta.fondo( t ) ), "texto oscuro sobre el fondo claro" );
    cierto( Paleta.contraste( Tema.TEXTO, Tema.FONDO ) > 14, "contraste del texto" );
    cierto( Paleta.contraste( Tema.TEXTO_TENUE, Tema.FONDO ) >= 4.5, "el tenue cumple AA sobre blanco" );
    igual( 21.0, Math.round( Paleta.contraste( "#000000", "#FFFFFF" ) * 100 ) / 100.0, "contraste maximo" );
  }

  /** SvgAPng no carga recursos externos (ni se conecta) y rechaza entradas invalidas. */
  public void pruebaSvgAPngSeguro() throws Exception {
    lanza( IllegalArgumentException.class, () -> SvgAPng.png( "", 100 ), "svg vacio" );
    lanza( IllegalArgumentException.class, () -> SvgAPng.png( ModeloMuestra.svgMuestra( "x", 10, 10 ), 0 ),
      "ancho 0" );
    try ( ServerSocket ss = new ServerSocket( 0 ) ) {
      ss.setSoTimeout( 700 );
      String url = "http://127.0.0.1:" + ss.getLocalPort() + "/x.png";
      String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\""
        + " width=\"100\" height=\"50\" viewBox=\"0 0 100 50\"><rect x=\"0\" y=\"0\" width=\"100\" height=\"50\""
        + " fill=\"#FFFFFF\"/><image x=\"0\" y=\"0\" width=\"10\" height=\"10\" xlink:href=\"" + url + "\"/>"
        + "<script type=\"text/ecmascript\"><![CDATA[ var x = 1; ]]></script></svg>";
      try {
        SvgAPng.png( svg, 200 );
      } catch ( Exception e ) {
        // que falle esta bien; lo que no puede pasar es que se conecte
      }
      try {
        ss.accept().close();
        throw new Verificador.Falla( "Batik se conecto a " + url );
      } catch ( SocketTimeoutException e ) {
        // bien: nadie se conecto
      }
    }
  }

  // ------------------------------------------------------------------ ayudas

  /**
   * Genera el SVG, lo valida (XML, raiz, fondo, sin CSS ni dominant-baseline, font-family en cada texto),
   * lo pasa a PNG con Batik al doble de ancho y deja los dos archivos en salida\graficos.
   */
  private String verificar( String nombre, Grafico g, Tema t, String idioma ) throws Exception {
    int anchoPedido = nombre.startsWith( "ancho-100" ) ? 100 : 720;
    String svg = GraficosSvg.svg( g, t, anchoPedido, idioma );
    Document d = PruebaSimbolo.xml( svg );
    Element raiz = d.getDocumentElement();
    igual( "svg", raiz.getLocalName(), nombre + ": raiz" );
    igual( "http://www.w3.org/2000/svg", raiz.getNamespaceURI(), nombre + ": namespace" );
    igual( "1.1", raiz.getAttribute( "version" ), nombre + ": SVG 1.1" );
    String w = raiz.getAttribute( "width" );
    String h = raiz.getAttribute( "height" );
    igual( "0 0 " + w + " " + h, raiz.getAttribute( "viewBox" ), nombre + ": viewBox = width x height" );
    Element fondo = primerVisible( raiz );
    igual( "rect", fondo.getLocalName(), nombre + ": primero el rect de fondo" );
    igual( "0", fondo.getAttribute( "x" ), nombre + ": fondo x" );
    igual( "0", fondo.getAttribute( "y" ), nombre + ": fondo y" );
    igual( w, fondo.getAttribute( "width" ), nombre + ": fondo cubre el ancho" );
    igual( h, fondo.getAttribute( "height" ), nombre + ": fondo cubre el alto" );
    igual( "#FFFFFF", fondo.getAttribute( "fill" ), nombre + ": fondo blanco" );
    for ( String prohibido : new String[] { "dominant-baseline", "<style", "style=", "var(", "class=",
      "foreignObject", "rgba(", "<script", "href=" } ) {
      noContiene( svg, prohibido, nombre + ": IE11/Batik" );
    }
    NodeList ts = d.getElementsByTagNameNS( "*", "text" );
    for ( int i = 0; i < ts.getLength(); i++ ) {
      Element e = (Element) ts.item( i );
      cierto( e.getAttribute( "font-family" ).contains( "Segoe UI" ), nombre + ": font-family inline en cada texto" );
      cierto( !e.getAttribute( "fill" ).isEmpty(), nombre + ": fill inline en cada texto" );
    }
    double ancho = Double.parseDouble( w );
    double alto = Double.parseDouble( h );
    // nada se sale del lienzo (x de textos y rects)
    for ( String tag : new String[] { "rect", "text", "line", "circle" } ) {
      NodeList l = d.getElementsByTagNameNS( "*", tag );
      for ( int i = 0; i < l.getLength(); i++ ) {
        Element e = (Element) l.item( i );
        String ax = e.hasAttribute( "x" ) ? "x" : e.hasAttribute( "cx" ) ? "cx" : "x1";
        String ay = e.hasAttribute( "y" ) ? "y" : e.hasAttribute( "cy" ) ? "cy" : "y1";
        double x = Double.parseDouble( e.getAttribute( ax ) );
        double y = Double.parseDouble( e.getAttribute( ay ) );
        cierto( x >= 0 && x <= ancho && y >= 0 && y <= alto, nombre + ": " + tag + " dentro del lienzo (" + x + ","
          + y + ")" );
        if ( "rect".equals( tag ) ) {
          double r = x + Double.parseDouble( e.getAttribute( "width" ) );
          cierto( r <= ancho + 0.01, nombre + ": rect que se sale por la derecha (" + r + ")" );
        }
      }
    }
    File sal = EntornoKettle.salida( "graficos" );
    Files.write( new File( sal, nombre + ".svg" ).toPath(), svg.getBytes( StandardCharsets.UTF_8 ) );
    long t0 = System.currentTimeMillis();
    byte[] png = SvgAPng.png( svg, (int) ancho * 2 );
    long ms = System.currentTimeMillis() - t0;
    Files.write( new File( sal, nombre + ".png" ).toPath(), png );
    BufferedImage img = ImageIO.read( new ByteArrayInputStream( png ) );
    igual( (int) ancho * 2, img.getWidth(), nombre + ": ancho del PNG" );
    cierto( Math.abs( img.getHeight() - alto * 2 ) <= 2, nombre + ": alto del PNG " + img.getHeight() );
    cierto( ms < 20000, nombre + ": Batik tardo " + ms + " ms" );
    cierto( coloreados( img ) > 0, nombre + ": el PNG tiene algo dibujado" );
    muestras.put( nombre, svg );
    return svg;
  }

  private static Element primerVisible( Element raiz ) {
    for ( Node n = raiz.getFirstChild(); n != null; n = n.getNextSibling() ) {
      if ( n instanceof Element && !"title".equals( n.getLocalName() ) ) {
        return (Element) n;
      }
    }
    throw new Verificador.Falla( "svg sin elementos" );
  }

  /** Pixeles que no son blancos ni casi blancos. */
  private static int coloreados( BufferedImage img ) {
    int n = 0;
    for ( int y = 0; y < img.getHeight(); y += 2 ) {
      for ( int x = 0; x < img.getWidth(); x += 2 ) {
        int c = img.getRGB( x, y );
        int r = ( c >> 16 ) & 0xFF;
        int g = ( c >> 8 ) & 0xFF;
        int b = c & 0xFF;
        if ( r < 230 || g < 230 || b < 230 ) {
          n++;
        }
      }
    }
    return n;
  }

  /** Contenido de los text, en orden. */
  private static List<String> textos( String svg ) throws Exception {
    NodeList ts = PruebaSimbolo.xml( svg ).getElementsByTagNameNS( "*", "text" );
    List<String> l = new ArrayList<>();
    for ( int i = 0; i < ts.getLength(); i++ ) {
      Node n = ts.item( i ).getLastChild();
      l.add( n == null ? "" : n.getTextContent() );
    }
    return l;
  }

  private static int cuenta( String s, String parte ) {
    int n = 0;
    for ( int i = s.indexOf( parte ); i >= 0; i = s.indexOf( parte, i + 1 ) ) {
      n++;
    }
    return n;
  }

  private static Map<String, Grafico> graficos( Informe inf ) {
    Map<String, Grafico> m = new LinkedHashMap<>();
    for ( String id : new String[] { SeccionId.PERFIL, SeccionId.METRICAS } ) {
      Seccion s = inf.seccion( id );
      for ( Bloque b : s.bloques ) {
        if ( b instanceof Grafico ) {
          m.put( b.id, (Grafico) b );
        }
      }
    }
    falso( m.isEmpty(), "ModeloMuestra sin graficos" );
    return m;
  }

  private static Grafico barras( String titulo, int n ) {
    Grafico g = new Grafico();
    g.clase = Grafico.BARRAS_H;
    g.titulo = titulo;
    Double[] v = new Double[ n ];
    for ( int i = 0; i < n; i++ ) {
      g.categorias.add( "Step " + ( i + 1 ) );
      v[ i ] = ( i * 37 % 11 + 1 ) * 1000.0 + i;
    }
    g.series.add( new Serie( "Filas", v ) );
    return g;
  }

  private static Grafico ganttSimple() {
    Grafico g = new Grafico();
    g.clase = Grafico.GANTT;
    g.tramos.add( new Tramo( "A", Tramo.INIT, 0, 10 ) );
    g.tramos.add( new Tramo( "A", Tramo.EXEC, 10, 500 ) );
    g.tramos.add( new Tramo( "B", Tramo.EXEC, 100, 800 ) );
    return g;
  }
}
