package io.github.ivi2511.pentahomcp.informes.render;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

import io.github.ivi2511.pentahomcp.EntornoKettle;
import io.github.ivi2511.pentahomcp.ModeloMuestra;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.diagramas.Grafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Los diagramas con grafo (flujo, job, linaje) en los renderizadores: SVG inline en el HTML (sin el bundle
 * de Mermaid si no queda ningun diagrama Mermaid; del tamano de la hoja al imprimir), PNG en la carpeta del
 * Markdown con el Mermaid plegado en un {@code <details>}, una diapositiva por parte en el PowerPoint y el
 * anexo del diccionario fuera de la presentacion.
 *
 * @dueño WP6
 */
public class PruebaDiagramaPropio {

  private File carpeta;

  public void preparar() {
    carpeta = EntornoKettle.salida( "diagrama-propio" );
  }

  /** Una cadena de n steps (como el flujo de 26 steps del ejercicio 2). */
  static Grafo cadena( int n ) {
    Grafo g = new Grafo( Grafo.FLUJO, "Cadena" );
    Grafo.Nodo antes = null;
    for ( int i = 1; i <= n; i++ ) {
      Grafo.Nodo x = g.nodo( "s" + i, "Paso numero " + i );
      x.lineas.add( "Calculadora" );
      if ( antes != null ) {
        g.arista( antes, x, Grafo.NORMAL );
      }
      antes = x;
    }
    return g;
  }

  /** ModeloMuestra con grafo en el flujo y el linaje; sin el modelo ER (el unico que queda Mermaid). */
  static Informe conGrafos( boolean conEr ) {
    Informe inf = ModeloMuestra.crear();
    for ( Seccion s : inf.secciones ) {
      List<Bloque> fuera = new ArrayList<>();
      for ( Bloque b : s.bloques ) {
        if ( !( b instanceof Diagrama ) ) {
          continue;
        }
        Diagrama d = (Diagrama) b;
        if ( Diagrama.FLUJO.equals( d.clase ) ) {
          d.grafo = cadena( 26 ).esquema();
        } else if ( Diagrama.LINAJE.equals( d.clase ) ) {
          Grafo g = new Grafo( Grafo.LINAJE, "Linaje" );
          Grafo.Grupo a = g.grupo( "Leer ventas" );
          Grafo.Grupo c = g.grupo( "Escribir CSV" );
          c.destino = true;
          Grafo.Nodo p = g.campo( a, "c0", "producto" );
          Grafo.Nodo q = g.campo( a, "c1", "cantidad" );
          Grafo.Nodo p2 = g.campo( c, "c2", "producto" );
          Grafo.Nodo t = g.campo( c, "c3", "total" );
          t.op = "suma";
          g.arista( p, p2, Grafo.NORMAL );
          g.arista( q, t, Grafo.NORMAL );
          d.grafo = g.esquema();
        } else if ( !conEr ) {
          fuera.add( d );
        }
      }
      s.bloques.removeAll( fuera );
    }
    return inf;
  }

  /** HTML: SVG inline propio, sin pre.mermaid de flujo/linaje y sin el bundle si no hay ER. */
  public void pruebaHtml() throws Exception {
    String html = new RenderHtml( RenderHtml.Modo.INTERACTIVO, null, false ).html( conGrafos( false ), ModeloMuestra.tema() );
    contiene( html, "class=\"diagrama propio\"", "figura propia" );
    contiene( html, "Paso numero 26", "el ultimo step del flujo" );
    noContiene( html, "<pre class=\"mermaid\"", "sin Mermaid de flujo ni linaje" );
    cierto( html.length() < 1500000, "sin el bundle de Mermaid (2,6 MB): " + html.length() );
    String conEr = new RenderHtml( RenderHtml.Modo.INTERACTIVO, null, false ).html( conGrafos( true ), ModeloMuestra.tema() );
    contiene( conEr, "<pre class=\"mermaid\"", "el modelo ER sigue en Mermaid" );
    String estatico = new RenderHtml( RenderHtml.Modo.ESTATICO, null, false ).html( conGrafos( false ), ModeloMuestra.tema() );
    noContiene( estatico, "<script", "estatico sin script" );
    contiene( estatico, "Paso numero 26", "el diagrama propio tambien en la vista previa IE11" );
  }

  /** Para imprimir: cada figura propia con width/height explicitos que entran en la hoja A4 (sin hojas en blanco). */
  public void pruebaImprimirEntraEnLaHoja() throws Exception {
    Tema tema = ModeloMuestra.tema();
    String html = new RenderHtml( RenderHtml.Modo.ESTATICO, null, true ).html( conGrafos( false ), tema );
    double hoja = RenderHtml.altoHojaPx( tema );
    Matcher m = Pattern.compile( "<div class=\"svg\" role=\"img\"[^>]*><svg[^>]* width=\"([0-9.]+)\" height=\"([0-9.]+)\"" )
      .matcher( html );
    int n = 0;
    while ( m.find() ) {
      n++;
      double w = Double.parseDouble( m.group( 1 ) );
      double h = Double.parseDouble( m.group( 2 ) );
      cierto( h <= hoja, "figura " + n + " de " + h + " px en una hoja de " + hoja );
      cierto( w <= 700, "figura " + n + " de " + w + " px de ancho" );
    }
    cierto( n >= 2, "figuras propias: " + n );
  }

  /** Markdown: PNG en la carpeta de archivos y el Mermaid plegado en un details. */
  public void pruebaMarkdown() throws Exception {
    File md = new File( carpeta, "propio.md" );
    EntornoKettle.borrar( RenderMarkdown.carpeta( md ).toPath() );
    new RenderMarkdown().escribir( conGrafos( false ), ModeloMuestra.tema(), md, Progreso.NULO );
    String t = PruebaHtml.leer( md );
    File dir = RenderMarkdown.carpeta( md );
    cierto( new File( dir, "flow-diagrama.png" ).isFile() || new File( dir, "flow-diagrama-1.png" ).isFile(),
      "PNG del flujo en " + dir );
    contiene( t, "<details>\n<summary>", "Mermaid plegado" );
    contiene( t, "```mermaid\nflowchart LR", "el codigo Mermaid se conserva" );
    contiene( t, "</details>", "cierre del details" );
  }

  /**
   * PowerPoint: el flujo largo en diapositivas propias que llenan el area (una imagen grande por parte) y el
   * anexo del diccionario fuera de la presentacion por defecto.
   */
  public void pruebaPptx() throws Exception {
    Informe inf = conGrafos( false );
    Seccion dic = null;
    for ( Seccion s : inf.secciones ) {
      if ( SeccionId.DICCIONARIO.equals( s.id ) ) {
        dic = s;
      }
    }
    if ( dic != null ) {
      for ( int k = 0; k < 6; k++ ) {
        Tabla t = new Tabla( "Campo", "Tipo" );
        t.id = "dictionary-anexo-" + k;
        t.titulo = "Anexo step " + k;
        for ( int i = 0; i < 15; i++ ) {
          t.fila( "campo_" + i, "String" );
        }
        dic.bloques.add( t );
      }
    }
    File f = new File( carpeta, "propio.pptx" );
    new RenderPptx( null ).escribir( inf, ModeloMuestra.tema(), f, Progreso.NULO );
    int grandes = 0;
    boolean anexo = false;
    try ( InputStream in = new FileInputStream( f ); XMLSlideShow ppt = new XMLSlideShow( in ) ) {
      for ( XSLFSlide sl : ppt.getSlides() ) {
        for ( XSLFShape sh : sl.getShapes() ) {
          if ( sh instanceof XSLFPictureShape ) {
            java.awt.geom.Rectangle2D r = sh.getAnchor();
            if ( r.getWidth() >= 600 || r.getHeight() >= 300 ) {
              grandes++;
            }
          } else if ( sh instanceof XSLFTextShape && ( (XSLFTextShape) sh ).getText().contains( "Anexo step" ) ) {
            anexo = true;
          }
        }
      }
    }
    cierto( grandes >= 1, "el diagrama llena la diapositiva: " + grandes );
    cierto( !anexo, "el anexo del diccionario no va en la presentacion" );
    cierto( PptxDiapos.detalle( tablaId( "dictionary-anexo-3" ) ), "anexo del diccionario = detalle" );
    cierto( !PptxDiapos.detalle( tablaId( "dictionary-salida-1" ) ), "la tabla principal no" );
    igual( true, f.length() > 1000, "pptx escrito" );
  }

  private static Tabla tablaId( String id ) {
    Tabla t = new Tabla( "a" );
    t.id = id;
    return t;
  }
}
