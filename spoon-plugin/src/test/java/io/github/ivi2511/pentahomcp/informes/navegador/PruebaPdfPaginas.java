package io.github.ivi2511.pentahomcp.informes.navegador;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.saltear;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import io.github.ivi2511.pentahomcp.EntornoKettle;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.graficos.GraficosSvg;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.Grafico;
import io.github.ivi2511.pentahomcp.informes.modelo.Imagen;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.Parrafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tramo;
import io.github.ivi2511.pentahomcp.informes.render.RenderHtml;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * PDF sin paginas en blanco: un diagrama o una imagen mas alta que la pagina dejaba el titulo solo en una
 * pagina, un marco vacio en la siguiente y el dibujo cortado en dos (el linaje de una practica real).
 * Imprime un informe con un Mermaid de 45 pasos en vertical, un lienzo de 300 x 2400 px y un Gantt de 40
 * filas, en A4 y en Letter, y lee el texto de cada pagina con iText (el de PDI, plugins\pdi-core-plugins\lib):
 * ninguna pagina queda vacia ni con solo un titulo, y el diagrama entero (primer y ultimo paso) esta en la
 * pagina de su titulo. Se saltea sin Edge ni Chrome.
 *
 * @dueño WP8
 */
public class PruebaPdfPaginas {

  private static final int PASOS = 45;
  private static final String TITULO_DIAGRAMA = "Linaje hacia «Salida alta»";
  private static final String TITULO_IMAGEN = "Lienzo de Spoon alto";
  private static final String TITULO_GANTT = "Línea de tiempo alta";

  private File exe;
  private File salida;
  private Extractor itext;

  public void preparar() throws Exception {
    exe = Chromium.ubicar();
    if ( exe == null ) {
      saltear( "no hay Edge ni Chrome" );
    }
    if ( RenderHtml.mermaidJs() == null ) {
      saltear( "falta mermaid.min.js" );
    }
    salida = EntornoKettle.salida( "pdf-paginas" );
    itext = Extractor.dePdi();
  }

  public void pruebaA4SinPaginasEnBlanco() throws Exception {
    revisar( Tema.A4 );
  }

  public void pruebaLetterSinPaginasEnBlanco() throws Exception {
    revisar( Tema.LETTER );
  }

  private void revisar( String pagina ) throws Exception {
    Tema t = new Tema();
    t.pagina = pagina;
    File pdf = new File( salida, "alto-" + pagina + ".pdf" );
    new RenderPdf().escribir( informe(), t, pdf, Progreso.NULO );
    cierto( pdf.isFile() && pdf.length() > 1024, "PDF escrito: " + pdf );
    List<String> paginas = itext.paginas( pdf );
    StringBuilder todo = new StringBuilder();
    int conDiagrama = -1;
    int conImagen = -1;
    int conGantt = -1;
    for ( int i = 0; i < paginas.size(); i++ ) {
      String p = sinPie( paginas.get( i ) );
      todo.append( "\n--- p" ).append( i + 1 ).append( ": " ).append( p.replace( '\n', '|' ) );
      cierto( !p.trim().isEmpty(), pagina + ": la pagina " + ( i + 1 ) + " esta en blanco" + todo );
      String sinTitulos = p.replace( TITULO_DIAGRAMA, "" ).replace( TITULO_IMAGEN, "" ).replace( TITULO_GANTT, "" )
        .trim();
      cierto( !sinTitulos.isEmpty(), pagina + ": la pagina " + ( i + 1 ) + " tiene solo un titulo" + todo );
      if ( p.contains( TITULO_DIAGRAMA ) ) {
        conDiagrama = i;
      }
      if ( p.contains( TITULO_GANTT ) ) {
        conGantt = i;
      }
      if ( p.contains( TITULO_IMAGEN ) ) {
        conImagen = i;
      }
    }
    cierto( conDiagrama >= 0, pagina + ": esta el titulo del diagrama" + todo );
    String p = paginas.get( conDiagrama );
    contiene( p, "Paso 0", pagina + ": el primer paso en la pagina del titulo" + todo );
    contiene( p, "Paso " + ( PASOS - 1 ), pagina + ": y el ultimo (el diagrama entero, achicado)" + todo );
    cierto( conImagen >= 0, pagina + ": esta el titulo de la imagen" + todo );
    contiene( paginas.get( conImagen ), "Nota del lienzo", pagina + ": la nota de la imagen va en la misma pagina"
      + todo );
    cierto( conGantt >= 0, pagina + ": esta el titulo del Gantt" + todo );
    String pg = paginas.get( conGantt );
    contiene( pg, "Fila g0", pagina + ": la primera fila del Gantt en la pagina del titulo" + todo );
    contiene( pg, "Fila g" + ( GraficosSvg.MAX_FILAS_GANTT - 1 ), pagina + ": y la ultima (entero, achicado)"
      + todo );
    // portada e indice; cada seccion larga abre pagina con su titulo y resumen, y la figura de pagina entera
    // va en la siguiente (entera, achicada); conexiones. Sin el tope: titulo solo, marco vacio y dibujo partido
    cierto( paginas.size() <= 8, pagina + ": " + paginas.size() + " paginas" + todo );
  }

  /** El texto sin el pie de pagina de @page («Pentaho Live · IDC Studios», «Página 3 de 5»). */
  private static String sinPie( String texto ) {
    StringBuilder sb = new StringBuilder();
    for ( String l : texto.split( "\\r?\\n" ) ) {
      String x = l.replace( Tema.PIE, "" ).replaceAll( "P[aá]gina \\d+ de \\d+", "" ).trim();
      if ( !x.isEmpty() ) {
        sb.append( x ).append( '\n' );
      }
    }
    return sb.toString();
  }

  private static Informe informe() throws Exception {
    Informe inf = new Informe();
    inf.idioma = "es";
    inf.titulo = "Páginas";
    inf.nombreDoc = "paginas";

    Seccion lin = new Seccion( SeccionId.LINAJE, "Linaje de campos" );
    lin.resumen = "Traza 45 pasos de «Salida alta» hasta su origen: un diagrama mucho más alto que la página.";
    lin.con( new Parrafo( "Un párrafo antes del diagrama, como el de para qué sirve el linaje." ) );
    StringBuilder m = new StringBuilder( "flowchart TB\n" );
    for ( int i = 0; i < PASOS - 1; i++ ) {
      m.append( "  n" ).append( i ).append( "[\"Paso " ).append( i ).append( "\"] --> n" ).append( i + 1 ).append(
        "[\"Paso " ).append( i + 1 ).append( "\"]\n" );
    }
    Diagrama d = new Diagrama();
    d.id = "lineage-diagram-1";
    d.clase = Diagrama.LINAJE;
    d.titulo = TITULO_DIAGRAMA;
    d.mermaid = m.toString();
    d.nota = "Nota del diagrama.";
    lin.con( d );
    inf.secciones.add( lin );

    Seccion flujo = new Seccion( SeccionId.FLUJO, "Diagrama de flujo" );
    flujo.resumen = "Un lienzo de Spoon vertical, más alto que la página.";
    Imagen im = new Imagen( Imagen.PNG, png( 300, 2400 ), 300, 2400, 1f );
    im.id = "flow-lienzo";
    im.titulo = TITULO_IMAGEN;
    im.nota = "Nota del lienzo.";
    flujo.con( im );
    inf.secciones.add( flujo );

    // un Gantt de 40 filas (el tope de GraficosSvg): ~960 px de alto, mas que la pagina
    Seccion met = new Seccion( SeccionId.METRICAS, "Métricas de ejecución" );
    met.resumen = "Un Gantt de 40 steps, más alto que la página.";
    Grafico g = new Grafico();
    g.id = "metrics-gantt";
    g.clase = Grafico.GANTT;
    g.titulo = TITULO_GANTT;
    g.unidad = "ms";
    g.nota = "Nota del Gantt.";
    for ( int i = 0; i < GraficosSvg.MAX_FILAS_GANTT; i++ ) {
      String cat = "Fila g" + i;
      g.categorias.add( cat );
      g.tramos.add( new Tramo( cat, Tramo.EXEC, i * 10L, i * 10L + 50 ) );
    }
    met.con( g );
    inf.secciones.add( met );

    Seccion con = new Seccion( SeccionId.CONEXIONES, "Conexiones" );
    con.resumen = "Una sección corta al final.";
    con.con( new Parrafo( "Sin conexiones." ) );
    inf.secciones.add( con );
    return inf;
  }

  /** Un PNG con franjas (no vacio). */
  private static byte[] png( int w, int h ) throws Exception {
    BufferedImage img = new BufferedImage( w, h, BufferedImage.TYPE_INT_RGB );
    Graphics2D g = img.createGraphics();
    g.setColor( Color.WHITE );
    g.fillRect( 0, 0, w, h );
    for ( int y = 0; y < h; y += 100 ) {
      g.setColor( new Color( 30, 90, 160 ) );
      g.fillRect( 20, y + 20, w - 40, 60 );
    }
    g.dispose();
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    ImageIO.write( img, "png", bo );
    return bo.toByteArray();
  }

  /** Texto por pagina con iText 2.1.7 de PDI (no esta en el classpath de las pruebas: se carga aparte). */
  static final class Extractor {
    private final Class<?> lector;
    private final Class<?> extractor;

    private Extractor( ClassLoader cl ) throws Exception {
      lector = Class.forName( "com.lowagie.text.pdf.PdfReader", true, cl );
      extractor = Class.forName( "com.lowagie.text.pdf.parser.PdfTextExtractor", true, cl );
    }

    static Extractor dePdi() throws Exception {
      String pdi = System.getProperty( "pruebas.pdi" );
      File jar = pdi == null ? null : new File( pdi, "plugins/pdi-core-plugins/lib/itext-2.1.7.jar" );
      if ( jar == null || !jar.isFile() ) {
        saltear( "no esta itext-2.1.7.jar de PDI" );
      }
      return new Extractor( new URLClassLoader( new URL[] { jar.toURI().toURL() }, PruebaPdfPaginas.class
        .getClassLoader() ) );
    }

    List<String> paginas( File pdf ) throws Exception {
      Object r = lector.getConstructor( String.class ).newInstance( pdf.getAbsolutePath() );
      try {
        int n = (Integer) lector.getMethod( "getNumberOfPages" ).invoke( r );
        Object e = extractor.getConstructor( lector ).newInstance( r );
        Method m = extractor.getMethod( "getTextFromPage", int.class );
        List<String> l = new ArrayList<>();
        for ( int i = 1; i <= n; i++ ) {
          l.add( (String) m.invoke( e, i ) );
        }
        igual( n, l.size(), "paginas leidas" );
        return l;
      } finally {
        lector.getMethod( "close" ).invoke( r );
      }
    }
  }
}
