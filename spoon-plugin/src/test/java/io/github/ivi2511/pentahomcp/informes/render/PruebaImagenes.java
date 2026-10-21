package io.github.ivi2511.pentahomcp.informes.render;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.lanza;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CancellationException;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;

import io.github.ivi2511.pentahomcp.ModeloMuestra;
import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Resultado;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;

/**
 * Exportacion de imagenes de WP10 sobre ModeloMuestra, sin Kettle: la carpeta con el lienzo, cada diagrama
 * (PNG y SVG) y cada grafico (SVG y PNG de 1.440 px), con nombres estables; SVG sueltos que se pueden abrir
 * como XML; nada se borra al regenerar; avisos sin navegador. Los archivos quedan en
 * {@code <Salida>\prueba\salida\imagenes}.
 *
 * @dueño WP10
 */
public class PruebaImagenes {

  private File carpeta;

  public void preparar() {
    carpeta = AyudaOffice.carpeta( "imagenes" );
  }

  public void pruebaMuestra() throws Exception {
    Informe inf = ModeloMuestra.crear();
    File dir = new File( carpeta, "muestra" + RenderImagenes.SUFIJO );
    File mio = new File( dir, "mio.txt" );
    dir.mkdirs();
    Files.write( mio.toPath(), "del usuario".getBytes( StandardCharsets.UTF_8 ) );
    Map<String, String> svg = ModeloMuestra.svgPrerender( inf );
    // un SVG sin xmlns ni declaracion, como podria venir de otro motor
    svg.put( "target-model-er", "<svg width=\"10\" height=\"10\"><rect width=\"10\" height=\"10\"/></svg>" );
    Resultado res = new RenderImagenes( ModeloMuestra.pngPrerender( inf ), svg ).conSvgGraficos(
      PruebaDocx.SVG_MUESTRA ).escribir( inf, ModeloMuestra.tema(), dir, Progreso.NULO );

    igual( dir.getAbsoluteFile(), res.archivos.get( 0 ), "la carpeta primero" );
    List<File> fs = RenderImagenes.archivos( res );
    List<String> nombres = new ArrayList<>();
    long bytes = 0;
    for ( File f : fs ) {
      cierto( f.isFile() && f.length() > 0, "escrito: " + f );
      igual( dir.getAbsoluteFile(), f.getParentFile(), "en la carpeta: " + f );
      nombres.add( f.getName() );
      bytes += f.length();
    }
    igual( bytes, res.bytes, "bytes de los archivos" );
    igual( Arrays.asList( "01_diagrama-de-flujo_lienzo.png", "02_diagrama-de-flujo_diagrama.png",
      "02_diagrama-de-flujo_diagrama.svg", "03_linaje-de-campos_diagrama.png", "03_linaje-de-campos_diagrama.svg",
      "04_modelo-destino_diagrama.png", "04_modelo-destino_diagrama.svg",
      "05_perfil-de-datos_porcentaje-de-nulos-por-campo.svg", "05_perfil-de-datos_porcentaje-de-nulos-por-campo.png",
      "06_metricas-de-ejecucion_filas-escritas-y-tiempo-por-step.svg",
      "06_metricas-de-ejecucion_filas-escritas-y-tiempo-por-step.png",
      "07_metricas-de-ejecucion_linea-de-tiempo-de-los-steps.svg",
      "07_metricas-de-ejecucion_linea-de-tiempo-de-los-steps.png", "08_metricas-de-ejecucion_filas-por-intervalo.svg",
      "08_metricas-de-ejecucion_filas-por-intervalo.png" ), nombres, "archivos y nombres" );
    igual( new ArrayList<String>(), res.avisos, "sin avisos" );
    cierto( mio.isFile(), "no borra lo que ya estaba en la carpeta" );

    // SVG sueltos: declaracion XML, xmlns y XML bien formado
    for ( File f : fs ) {
      if ( f.getName().endsWith( ".svg" ) ) {
        String x = new String( Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 );
        cierto( x.startsWith( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" ), "declaracion XML en " + f.getName() );
        contiene( x, "xmlns=\"http://www.w3.org/2000/svg\"", "xmlns en " + f.getName() );
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware( true );
        igual( "svg", dbf.newDocumentBuilder().parse( f ).getDocumentElement().getLocalName(), "SVG valido: " + f
          .getName() );
      }
    }
    // PNG: el lienzo tal cual; los graficos a 1.440 px
    BufferedImage lienzo = ImageIO.read( fs.get( 0 ) );
    igual( 1600, lienzo.getWidth(), "lienzo a escala 2" );
    BufferedImage g = ImageIO.read( new File( dir, "06_metricas-de-ejecucion_filas-escritas-y-tiempo-por-step.png" ) );
    igual( RenderImagenes.ANCHO_PNG_GRAFICO, g.getWidth(), "PNG del grafico a 1.440 px" );

    // regenerar: mismos nombres (se sobrescriben) y el archivo del usuario sigue
    Resultado otra = new RenderImagenes( ModeloMuestra.pngPrerender( inf ), svg ).conSvgGraficos(
      PruebaDocx.SVG_MUESTRA ).escribir( inf, ModeloMuestra.tema(), dir, null );
    igual( fs.size(), RenderImagenes.archivos( otra ).size(), "mismos archivos al regenerar" );
    cierto( mio.isFile(), "sigue el archivo del usuario" );
    File[] hay = dir.listFiles();
    noNulo( hay, "lista de la carpeta" );
    igual( fs.size() + 1, hay.length, "sin archivos de mas (ni .tmp)" );
  }

  /** Sin navegador: el lienzo sale; los Mermaid no, con aviso. En ingles, nombres en ingles. */
  public void pruebaSinNavegadorEnIngles() throws Exception {
    Informe inf = ModeloMuestra.crear();
    inf.idioma = "en";
    File dir = new File( carpeta, "sin-navegador" + RenderImagenes.SUFIJO );
    Resultado res = new RenderImagenes( null, null ).conSvgGraficos( PruebaDocx.SVG_MUESTRA ).escribir( inf, null,
      dir, null );
    List<String> nombres = new ArrayList<>();
    for ( File f : RenderImagenes.archivos( res ) ) {
      nombres.add( f.getName() );
    }
    igual( "01_diagrama-de-flujo_canvas.png", nombres.get( 0 ), "lienzo con nombre en ingles" );
    igual( 1 + 4 * 2, nombres.size(), "lienzo y 4 graficos (SVG + PNG)" );
    contiene( String.join( "\n", res.avisos ), "3 diagram(s) or chart(s) were not exported", "aviso sin navegador" );
  }

  /** Graficos sin PNG posible: queda el SVG con aviso; sin ninguna figura: aviso de carpeta vacia. */
  public void pruebaBordes() throws Exception {
    Informe inf = ModeloMuestra.crear();
    File dir = new File( carpeta, "solo-svg" + RenderImagenes.SUFIJO );
    Resultado res = new RenderImagenes( null, null ).conSvgGraficos( ( g, t, a ) -> "<svg xmlns=\"http://www.w3.org/"
      + "2000/svg\" width=\"10\" height=\"10\"><rect width=\"oops\"" ).escribir( inf, null, dir, null );
    contiene( String.join( "\n", res.avisos ), "van solo en SVG", "aviso de PNG que no salio" );
    Informe vacio = new Informe();
    vacio.idioma = "es";
    Resultado r2 = new RenderImagenes( null, null ).escribir( vacio, null, new File( carpeta, "vacia" ), null );
    igual( 1, r2.archivos.size(), "solo la carpeta" );
    contiene( String.join( "\n", r2.avisos ), "no se escribió ningún archivo", "aviso de carpeta vacia" );
    igual( new File( "C:\\x\\informe" + RenderImagenes.SUFIJO ).getAbsoluteFile(), RenderImagenes.carpeta( new File(
      "C:\\x\\informe" ) ), "carpeta por defecto" );
    igual( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<svg xmlns='http://www.w3.org/2000/svg'/>", RenderImagenes
      .suelto( "<svg xmlns='http://www.w3.org/2000/svg'/>" ), "no repite el xmlns con comillas simples" );
    Set<String> usados = new HashSet<>();
    igual( "01_a_b", RenderImagenes.nombre( usados, 1, "a", "b" ), "nombre" );
    igual( "01_a_b-2", RenderImagenes.nombre( usados, 1, "a", "b" ), "nombre repetido" );
    cierto( RenderImagenes.nombre( usados, 2, new String( new char[ 70 ] ).replace( '\0', 'x' ), "yyyyyyyyyyyyyyy" )
      .length() <= RenderImagenes.MAX_NOMBRE, "nombre de hasta 80" );
  }

  public void pruebaCancelarEIdiomas() throws Exception {
    Progreso cancelado = new Progreso() {
      @Override
      public void paso( String texto, int hecho, int total ) {
      }

      @Override
      public boolean cancelado() {
        return true;
      }
    };
    File dir = new File( carpeta, "cancelada" );
    lanza( CancellationException.class, () -> new RenderImagenes( null, null ).escribir( ModeloMuestra.crear(), null,
      dir, cancelado ), "cancelar corta la exportacion" );
    File[] hay = dir.listFiles();
    falso( hay != null && hay.length > 0, "cancelada antes de escribir nada" );
    Properties es = props( "es" );
    for ( String i : Arrays.asList( "en", "pt" ) ) {
      igual( es.keySet(), props( i ).keySet(), "mismas claves en imagenes_" + i );
    }
    for ( String k : Arrays.asList( "lienzo", "diagrama", "grafico", "imagen" ) ) {
      for ( String i : Arrays.asList( "es", "en", "pt" ) ) {
        String v = Rotulos.de( RenderImagenes.AREA, i ).s( k );
        igual( Html.idSeguro( v ), v, "parte de nombre de archivo segura: " + i + "." + k );
      }
    }
    igual( Formato.deClave( RenderImagenes.CLAVE ), new RenderImagenes( null, null ).formato(), "formato por clave" );
    // un PNG de muestra se puede leer (sanidad de ModeloMuestra.png)
    noNulo( ImageIO.read( new ByteArrayInputStream( ModeloMuestra.png( 20, 10, "x" ) ) ), "PNG de muestra" );
  }

  private static Properties props( String idioma ) throws Exception {
    Properties p = new Properties();
    try ( InputStream in = PruebaImagenes.class.getClassLoader().getResourceAsStream( Rotulos.CARPETA + "imagenes_"
      + idioma + ".properties" ) ) {
      noNulo( in, "imagenes_" + idioma + ".properties en el classpath" );
      p.load( new InputStreamReader( in, StandardCharsets.UTF_8 ) );
    }
    return p;
  }
}
