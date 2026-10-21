package io.github.ivi2511.pentahomcp.informes.render;

import static io.github.ivi2511.pentahomcp.Verificador.archivo;
import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.lanza;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;
import static io.github.ivi2511.pentahomcp.Verificador.saltear;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.openxmlformats.schemas.presentationml.x2006.main.CTShape;

import io.github.ivi2511.pentahomcp.ModeloMuestra;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Resultado;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.graficos.GraficosSvg;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.Grafico;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;
import io.github.ivi2511.pentahomcp.informes.tema.SimboloIdc;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * PowerPoint de WP9 (diseno §3.4 y §8.1) sobre ModeloMuestra, sin Kettle: POI lo vuelve a leer y se
 * verifican las diapositivas esperadas, 960x540, titulos en placeholders, notas (sin la fecha ni el
 * numero de la plantilla), imagenes, tablas paginadas con «(cont.)», pie IDC con campo de numero, y que
 * sin navegador los diagramas Mermaid van como tabla. Si hay python con python-pptx, tambien lo abre.
 * Los archivos quedan en {@code <Salida>\prueba\salida\pptx}.
 *
 * @dueño WP9
 */
public class PruebaPptx {

  private static final String PIE = "Pentaho Live · IDC Studios";

  /** SVG de muestra para los graficos mientras GraficosSvg (WP6) no este: lo mismo que daria, con rect de fondo. */
  private static final RenderPptx.ProveedorSvg SVG_MUESTRA = ( g, t, ancho ) -> ModeloMuestra.svgMuestra(
    "Grafico " + g.clase + ": " + g.titulo, ancho, Grafico.GANTT.equals( g.clase ) ? 220 : 180 );

  private File carpeta;

  /** {@code <Salida>\prueba\salida\pptx} (lo mismo que EntornoKettle.salida, sin cargar Kettle). */
  public void preparar() {
    String p = System.getProperty( "pruebas.dir" );
    File base = p == null ? new File( "" ).getAbsoluteFile() : new File( p ).getAbsoluteFile();
    carpeta = new File( new File( base, "salida" ), "pptx" );
    carpeta.mkdirs();
  }

  // ------------------------------------------------------------------ sin navegador

  public void pruebaSinNavegador() throws Exception {
    Informe inf = ModeloMuestra.crear();
    File f = new File( carpeta, "muestra-sin-navegador.pptx" );
    Resultado res = new RenderPptx( null ).conSvgGraficos( SVG_MUESTRA ).escribir( inf, ModeloMuestra.tema(), f,
      Progreso.NULO );
    archivo( f, 20000, "pptx escrito" );
    igual( 1, res.archivos.size(), "un solo archivo" );
    igual( f.getAbsoluteFile(), res.archivos.get( 0 ), "el principal primero" );
    igual( f.length(), res.bytes, "bytes del resultado" );
    falso( new File( carpeta, f.getName() + ".tmp" ).exists(), "no queda el .tmp" );
    contiene( String.join( "\n", res.avisos ), "2 diagrama(s)", "aviso de diagramas como tabla" );

    try ( XMLSlideShow ppt = abrir( f ) ) {
      igual( 960, ppt.getPageSize().width, "ancho 16:9" );
      igual( 540, ppt.getPageSize().height, "alto 16:9" );
      List<XSLFSlide> ds = ppt.getSlides();

      // portada y agenda
      XSLFSlide portada = ds.get( 0 );
      igual( "Title Slide", portada.getSlideLayout().getName(), "plantilla de la portada" );
      igual( inf.titulo, titulo( portada ), "titulo de la portada en su placeholder" );
      String tp = texto( portada );
      contiene( tp, inf.subtitulo, "subtitulo" );
      contiene( tp, "Autor: Nombre Apellido", "autor (de la lista de cover)" );
      contiene( tp, "Generado el 20 de octubre de 2026, 14:32", "fecha en castellano" );
      contiene( tp, "cambios sin guardar", "avisos del informe en la portada" );
      noContiene( tp, PIE, "la portada no lleva pie" );
      cierto( imagenes( portada ) >= 1, "logo en la portada" );
      contiene( notas( portada ), "cambios sin guardar", "avisos en las notas de la portada" );

      XSLFSlide agenda = ds.get( 1 );
      igual( "Title Only", agenda.getSlideLayout().getName(), "plantilla de la agenda" );
      igual( "Contenido", agenda.getTitle(), "titulo de la agenda (rotulo contents del informe)" );

      // una o varias diapositivas por seccion, en orden, sin las omitidas
      List<String> esperadas = new ArrayList<>();
      for ( Seccion s : inf.secciones ) {
        if ( !Seccion.OMITIDA.equals( s.estado ) && !SeccionId.PORTADA.equals( s.id ) ) {
          esperadas.add( s.titulo );
        }
      }
      List<String> primeras = new ArrayList<>();
      for ( int i = 2; i < ds.size(); i++ ) {
        String t = ds.get( i ).getTitle();
        noNulo( t, "titulo de la diapositiva " + ( i + 1 ) );
        igual( "Title Only", ds.get( i ).getSlideLayout().getName(), "plantilla de la diapositiva " + ( i + 1 ) );
        if ( !t.endsWith( "(cont.)" ) ) {
          primeras.add( t );
        } else {
          cierto( primeras.size() > 0 && t.startsWith( primeras.get( primeras.size() - 1 ) ),
            "«(cont.)» sigue a su seccion: " + t );
        }
      }
      igual( esperadas, primeras, "secciones en orden y sin la omitida" );
      for ( XSLFSlide d : ds ) {
        noContiene( d.getTitle(), "Flujo del job", "la seccion omitida no sale" );
      }

      // agenda: cada seccion con el numero de su primera diapositiva
      String ta = texto( agenda );
      for ( String s : esperadas ) {
        int n = primera( ds, s );
        cierto( n > 2, "la seccion " + s + " tiene diapositiva" );
        contiene( ta, s + "\t" + n, "agenda con el numero de diapositiva de " + s );
      }

      // cada placeholder con su ancla en la diapositiva (si no, POI la escribe en la plantilla)
      for ( XSLFSlide d : ds ) {
        for ( XSLFTextShape ph : d.getPlaceholders() ) {
          cierto( ( (CTShape) ph.getXmlObject() ).getSpPr().isSetXfrm(), "ancla propia de " + ph.getShapeName()
            + " en la diapositiva " + d.getSlideNumber() );
        }
      }
      // la agenda sin marL: con el, LibreOffice ignora la tabulacion del numero
      noContiene( agenda.getXmlObject().xmlText(), "marL=", "agenda sin margen izquierdo explicito" );

      // pie y numero en todas menos la portada
      for ( int i = 1; i < ds.size(); i++ ) {
        contiene( texto( ds.get( i ) ), PIE, "pie en la diapositiva " + ( i + 1 ) );
        contiene( ds.get( i ).getXmlObject().xmlText(), "type=\"slidenum\"", "campo de numero en " + ( i + 1 ) );
      }

      // notas: resumen de la seccion, motivo y narrativa; sin la fecha ni el numero de la plantilla
      for ( Seccion s : inf.secciones ) {
        if ( Seccion.OMITIDA.equals( s.estado ) || SeccionId.PORTADA.equals( s.id ) || s.resumen == null ) {
          continue;
        }
        contiene( notas( ds.get( primera( ds, s.titulo ) - 1 ) ), s.resumen, "notas de " + s.titulo );
      }
      contiene( notas( ds.get( primera( ds, "Validación" ) - 1 ) ), "Tiempo agotado", "motivo del error en notas" );
      contiene( notas( ds.get( primera( ds, "Resumen" ) - 1 ) ), "consolida las ventas", "narrativa en notas" );
      for ( XSLFSlide d : ds ) {
        String n = notas( d );
        noContiene( n, "‹#›", "sin el numero de la plantilla de notas" );
        cierto( !n.matches( "(?s).*\\b\\d{1,2}\\.\\d{1,2}\\.\\d{4}\\b.*" ), "sin la fecha de la plantilla de notas: " + n );
      }

      // flujo: el lienzo real
      XSLFSlide flujo = ds.get( primera( ds, "Diagrama de flujo" ) - 1 );
      Diagrama dflujo = (Diagrama) inf.seccion( SeccionId.FLUJO ).bloques.get( 0 );
      cierto( tieneImagen( flujo, dflujo.imagen.datos ), "el lienzo PNG en la diapositiva de flujo" );
      // tabla de steps con el nombre raro tal cual (POI escapa)
      cierto( celdas( ds ).contains( ModeloMuestra.STEP_RARO ), "el step <b>&\"x en una celda, literal" );

      // sin navegador: linaje y ER como tabla, con la aclaracion
      XSLFSlide linaje = ds.get( primera( ds, "Linaje de campos" ) - 1 );
      contiene( texto( linaje ), "Leer ventas.producto", "alternativa del linaje como tabla" );
      contiene( texto( linaje ), "El diagrama va como tabla", "aclaracion del diagrama" );
      contiene( texto( linaje ), "Parcial: 1 step sin resolver", "estado parcial bajo el titulo" );
      cierto( tablas( linaje ) >= 2, "linaje: alternativa y tabla de campos" );
      String destino = textoSeccion( ds, "Modelo destino" );
      contiene( destino, "VARCHAR(30)", "alternativa del ER como tabla" );
      Map<String, byte[]> png = ModeloMuestra.pngPrerender( inf );
      for ( XSLFSlide d : ds ) {
        for ( byte[] b : png.values() ) {
          falso( tieneImagen( d, b ), "sin navegador no hay PNG de Mermaid" );
        }
      }

      // graficos: SVG con PNG de respaldo
      int svg = 0;
      for ( XSLFSlide d : ds ) {
        for ( XSLFShape sh : d.getShapes() ) {
          if ( sh instanceof XSLFPictureShape && ( (XSLFPictureShape) sh ).getSvgImage() != null ) {
            svg++;
            igual( "image/png", ( (XSLFPictureShape) sh ).getPictureData().getContentType(), "respaldo PNG" );
          }
        }
      }
      igual( 4, svg, "los 4 graficos (perfil y metricas) como SVG" );

      // indicadores, avisos, rotulo de IA, pie de tabla
      String metricas = textoSeccion( ds, "Métricas de ejecución" );
      contiene( metricas, "400.000", "indicador de filas" );
      contiene( metricas, "Rechazadas", "sexto indicador" );
      contiene( textoSeccion( ds, "Validación" ), "La validación no terminó", "aviso de error" );
      contiene( textoSeccion( ds, "Conclusiones" ), "Texto redactado con asistencia de IA", "rotulo de IA" );
      contiene( textoSeccion( ds, "Conclusiones" ), "lazy conversion", "vinetas de la narrativa" );
      contiene( textoSeccion( ds, "Diccionario de datos" ), "sin límite", "pie de la tabla" );
      noContiene( textoSeccion( ds, "Conclusiones" ), "**", "sin marcas Markdown" );
    }
  }

  // ------------------------------------------------------------------ con navegador

  public void pruebaConNavegador() throws Exception {
    Informe inf = ModeloMuestra.crear();
    Map<String, byte[]> png = ModeloMuestra.pngPrerender( inf );
    File f = new File( carpeta, "muestra-con-navegador.pptx" );
    Resultado res = new RenderPptx( png ).conSvgGraficos( SVG_MUESTRA ).escribir( inf, ModeloMuestra.tema(), f,
      Progreso.NULO );
    noContiene( String.join( "\n", res.avisos ), "diagrama", "con navegador no hay aviso de diagramas" );
    try ( XMLSlideShow ppt = abrir( f ) ) {
      List<XSLFSlide> ds = ppt.getSlides();
      XSLFSlide linaje = ds.get( primera( ds, "Linaje de campos" ) - 1 );
      cierto( tieneImagen( linaje, png.get( "lineage-diagrama" ) ), "PNG de Mermaid del linaje" );
      noContiene( texto( linaje ), "El diagrama va como tabla", "sin la aclaracion" );
      XSLFSlide er = ds.get( primera( ds, "Modelo destino" ) - 1 );
      cierto( tieneImagen( er, png.get( "target-model-er" ) ), "PNG de Mermaid del ER" );
      // el flujo sigue con el lienzo, no con el PNG de Mermaid
      XSLFSlide flujo = ds.get( primera( ds, "Diagrama de flujo" ) - 1 );
      falso( tieneImagen( flujo, png.get( "flow-diagrama" ) ), "el flujo usa el lienzo" );
    }
  }

  // ------------------------------------------------------------------ paginado

  public void pruebaTablaLarga() throws Exception {
    Informe inf = chico( "es" );
    Tabla t = new Tabla( "N", "Campo", "Descripción" );
    for ( int i = 1; i <= 25; i++ ) {
      t.fila( String.valueOf( i ), "campo_" + i, "Fila " + i );
    }
    t.titulo = "Campos";
    t.alineacion = Arrays.asList( "r", "l", "l" );
    inf.secciones.add( new Seccion( SeccionId.DICCIONARIO, "Diccionario de datos" ).con( t ) );
    File f = new File( carpeta, "tabla-larga.pptx" );
    new RenderPptx( null ).escribir( inf, new Tema(), f, Progreso.NULO );
    try ( XMLSlideShow ppt = abrir( f ) ) {
      List<XSLFSlide> ds = ppt.getSlides();
      // presentacion: una tabla ocupa como mucho PptxDiapos.MAX_DIAPOS_TABLA diapositivas; el resto, una nota
      igual( 4, ds.size(), "portada, agenda y 2 de tabla" );
      igual( "Diccionario de datos", ds.get( 2 ).getTitle(), "primera" );
      igual( "Diccionario de datos (cont.)", ds.get( 3 ).getTitle(), "segunda" );
      contiene( texto( ds.get( 3 ) ), "+5", "nota: las 5 filas que siguen estan en el informe" );
      int[] filas = { 11, 11 };
      for ( int i = 0; i < 2; i++ ) {
        XSLFTable tbl = tabla( ds.get( 2 + i ) );
        noNulo( tbl, "tabla en la diapositiva " + ( 3 + i ) );
        igual( filas[ i ], tbl.getNumberOfRows(), "filas (con cabecera) en la diapositiva " + ( 3 + i ) );
        igual( "Campo", tbl.getCell( 0, 1 ).getText(), "cabecera repetida" );
        contiene( texto( ds.get( 2 + i ) ), i == 0 ? "Campos" : "Campos (cont.)", "subtitulo de la tabla" );
      }
      igual( "20", tabla( ds.get( 3 ) ).getCell( 10, 0 ).getText(), "ultima fila en la presentacion" );
    }
  }

  /**
   * Idioma de todo el texto (PowerPoint revisa la ortografia con el), tablas chicas sin partir y
   * diagramas que se agrandan como mucho al doble de su tamano natural.
   */
  public void pruebaIdiomaYMaquetacion() throws Exception {
    // 1) cada run (cuadros, tablas, notas) con el idioma del informe
    for ( String[] c : new String[][] { { "es", "es-ES" }, { "pt", "pt-BR" }, { "en", "en-US" } } ) {
      Informe inf = ModeloMuestra.crear();
      inf.idioma = c[ 0 ];
      File f = new File( carpeta, "idioma-" + c[ 0 ] + ".pptx" );
      new RenderPptx( null ).conSvgGraficos( SVG_MUESTRA ).escribir( inf, ModeloMuestra.tema(), f, Progreso.NULO );
      try ( XMLSlideShow ppt = abrir( f ) ) {
        int runs = 0;
        for ( XSLFSlide d : ppt.getSlides() ) {
          List<String> xmls = new ArrayList<>();
          xmls.add( d.getXmlObject().xmlText() );
          if ( d.getNotes() != null ) {
            xmls.add( d.getNotes().getXmlObject().xmlText() );
          }
          for ( String x : xmls ) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile( "<a:r>\\s*<a:rPr([^>]*)>" ).matcher( x );
            while ( m.find() ) {
              runs++;
              contiene( m.group( 1 ), "lang=\"" + c[ 1 ] + "\"", "run en " + c[ 1 ] + " en la diapositiva "
                + d.getSlideNumber() );
            }
            cierto( !x.contains( "<a:r><a:t>" ), "ningun run sin propiedades (sin idioma)" );
          }
        }
        cierto( runs > 100, "se revisaron los runs (" + runs + ")" );
      }
    }

    // 2) una tabla de 4 filas que no entra al final de una diapositiva va entera a la siguiente
    Informe inf = chico( "es" );
    StringBuilder largo = new StringBuilder();
    for ( int i = 0; i < 14; i++ ) {
      largo.append( "Linea de texto que ocupa lugar en la diapositiva numero " ).append( i ).append( ".\n" );
    }
    Tabla t = new Tabla( "Step", "Tipo", "Qué hace" );
    for ( int i = 1; i <= 4; i++ ) {
      t.fila( "Step " + i, "Tipo " + i, "Hace algo " + i );
    }
    t.titulo = "Qué hace cada step";
    Seccion s = new Seccion( SeccionId.FLUJO, "Diagrama de flujo" );
    s.con( new io.github.ivi2511.pentahomcp.informes.modelo.Parrafo( largo.toString() ) ).con( t );
    inf.secciones.add( s );
    // 3) un diagrama chico (PNG de 200x80 a escala 2 = 100x40 natural) no se agranda al ancho entero
    java.awt.image.BufferedImage im = new java.awt.image.BufferedImage( 200, 80,
      java.awt.image.BufferedImage.TYPE_INT_RGB );
    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write( im, "png", bo );
    Diagrama dg = new Diagrama();
    dg.id = "chico";
    dg.clase = Diagrama.LINAJE;
    dg.mermaid = "flowchart LR\n  a --> b\n";
    inf.secciones.add( new Seccion( SeccionId.LINAJE, "Linaje de campos" ).con( dg ) );
    File f = new File( carpeta, "maquetacion.pptx" );
    java.util.Map<String, byte[]> png = new java.util.HashMap<>();
    png.put( "chico", bo.toByteArray() );
    new RenderPptx( png ).escribir( inf, new Tema(), f, Progreso.NULO );
    try ( XMLSlideShow ppt = abrir( f ) ) {
      List<XSLFSlide> ds = ppt.getSlides();
      int p = primera( ds, "Diagrama de flujo" ) - 1;
      nulo( tabla( ds.get( p ) ), "la tabla no empieza al pie de la diapositiva del texto" );
      XSLFTable tbl = tabla( ds.get( p + 1 ) );
      noNulo( tbl, "la tabla en la siguiente" );
      igual( 5, tbl.getNumberOfRows(), "las 4 filas juntas (con la cabecera)" );
      contiene( texto( ds.get( p + 1 ) ), "Qué hace cada step", "con su subtitulo sin (cont.)" );
      noContiene( texto( ds.get( p + 1 ) ), "Qué hace cada step (cont.)", "sin (cont.) en el subtitulo" );
      XSLFSlide lin = ds.get( primera( ds, "Linaje de campos" ) - 1 );
      XSLFPictureShape pic = null;
      for ( XSLFShape sh : lin.getShapes() ) {
        if ( sh instanceof XSLFPictureShape && sh.getAnchor().getWidth() > 50 ) {
          pic = (XSLFPictureShape) sh;
        }
      }
      noNulo( pic, "el PNG del diagrama" );
      // presentacion: se agranda hasta PptxDiapos.AMPLIAR_DIAGRAMA veces (200 px a escala 2 = 100 natural)
      igual( 100.0 * PptxDiapos.AMPLIAR_DIAGRAMA, Math.round( pic.getAnchor().getWidth() * 10 ) / 10.0,
        "el doble del tamano natural, no el ancho entero" );
    }
  }

  // ------------------------------------------------------------------ graficos sin SVG

  public void pruebaGraficosSinSvg() throws Exception {
    Informe inf = ModeloMuestra.crear();
    File f = new File( carpeta, "graficos-como-tabla.pptx" );
    Resultado res = new RenderPptx( null ).conSvgGraficos( ( g, t, a ) -> {
      throw new IllegalStateException( "sin SVG" );
    } ).escribir( inf, ModeloMuestra.tema(), f, Progreso.NULO );
    contiene( String.join( "\n", res.avisos ), "4 gráfico(s)", "aviso de graficos como tabla" );
    try ( XMLSlideShow ppt = abrir( f ) ) {
      String m = textoSeccion( ppt.getSlides(), "Métricas de ejecución" );
      contiene( m, "Inicialización", "gantt como tabla con las fases traducidas" );
      contiene( m, "1.700", "valores con separador de miles" );
      contiene( m, "El gráfico va como tabla", "aclaracion" );
      contiene( textoSeccion( ppt.getSlides(), "Perfil de datos" ), "2,40", "decimales con coma" );
    }
  }

  /** Con el GraficosSvg real (WP6), si ya esta: los graficos van como SVG. */
  public void pruebaGraficosReales() throws Exception {
    Informe inf = ModeloMuestra.crear();
    Grafico g = (Grafico) inf.seccion( SeccionId.METRICAS ).bloques.get( 1 );
    try {
      GraficosSvg.svg( g, new Tema(), 880 );
    } catch ( UnsupportedOperationException e ) {
      saltear( "GraficosSvg (WP6) todavia es un stub" );
    }
    File f = new File( carpeta, "graficos-reales.pptx" );
    Resultado res = new RenderPptx( null ).escribir( inf, ModeloMuestra.tema(), f, Progreso.NULO );
    noContiene( String.join( "\n", res.avisos ), "gráfico", "sin graficos como tabla" );
  }

  /** Con SimboloIdc (WP6), si ya esta: marca de agua y simbolo del pie en todas las diapositivas. */
  public void pruebaMarcaDeAgua() throws Exception {
    try {
      SimboloIdc.png( 64, SimboloIdc.CLARO, 1f );
    } catch ( UnsupportedOperationException e ) {
      saltear( "SimboloIdc (WP6) todavia es un stub" );
    }
    Informe inf = ModeloMuestra.crear();
    File f = new File( carpeta, "marca-de-agua.pptx" );
    Tema t = ModeloMuestra.tema();
    new RenderPptx( null ).conSvgGraficos( SVG_MUESTRA ).escribir( inf, t, f, Progreso.NULO );
    try ( XMLSlideShow ppt = abrir( f ) ) {
      for ( XSLFSlide d : ppt.getSlides() ) {
        int n = imagenes( d );
        // portada: marca y logo; las demas: marca y simbolo del pie
        cierto( n >= 2, "marca de agua y simbolo en " + d.getSlideNumber() );
      }
    }
    t.marcaDeAgua = false;
    File g = new File( carpeta, "sin-marca-de-agua.pptx" );
    new RenderPptx( null ).conSvgGraficos( SVG_MUESTRA ).escribir( inf, t, g, Progreso.NULO );
    try ( XMLSlideShow a = abrir( f ); XMLSlideShow b = abrir( g ) ) {
      igual( a.getSlides().size(), b.getSlides().size(), "mismas diapositivas" );
      igual( imagenes( a.getSlides().get( 2 ) ) - 1, imagenes( b.getSlides().get( 2 ) ), "una imagen menos sin marca" );
    }
  }

  // ------------------------------------------------------------------ idiomas, cancelar, entorno

  public void pruebaIdiomas() throws Exception {
    Properties es = props( "es" );
    for ( String i : Arrays.asList( "en", "pt" ) ) {
      igual( es.keySet(), props( i ).keySet(), "mismas claves en pptx_" + i );
    }
    String[][] casos = { { "en", "Contents", "Generated on October 20, 2026, 14:32" },
      { "pt", "Sumário", "Gerado em 20 de outubro de 2026, 14:32" } };
    for ( String[] c : casos ) {
      Informe inf = ModeloMuestra.crear();
      inf.idioma = c[ 0 ];
      inf.rotulos.clear();
      File f = new File( carpeta, "muestra-" + c[ 0 ] + ".pptx" );
      new RenderPptx( null ).conSvgGraficos( SVG_MUESTRA ).escribir( inf, ModeloMuestra.tema(), f, Progreso.NULO );
      try ( XMLSlideShow ppt = abrir( f ) ) {
        igual( c[ 1 ], ppt.getSlides().get( 1 ).getTitle(), "agenda en " + c[ 0 ] );
        String tp = texto( ppt.getSlides().get( 0 ) );
        contiene( tp, c[ 2 ], "fecha en " + c[ 0 ] );
      }
    }
  }

  public void pruebaCancelar() throws Exception {
    File f = new File( carpeta, "cancelado.pptx" );
    if ( f.exists() ) {
      cierto( f.delete(), "borrar el anterior" );
    }
    Progreso cancelado = new Progreso() {
      @Override
      public void paso( String texto, int hecho, int total ) {
      }

      @Override
      public boolean cancelado() {
        return true;
      }
    };
    lanza( CancellationException.class, () -> new RenderPptx( null ).escribir( ModeloMuestra.crear(),
      ModeloMuestra.tema(), f, cancelado ), "cancelar corta la exportacion" );
    falso( f.exists(), "cancelado no deja archivo" );
    falso( new File( carpeta, f.getName() + ".tmp" ).exists(), "ni el .tmp" );
  }

  public void pruebaEntornoYTextoRaro() throws Exception {
    nulo( RenderPptx.problema( "es" ), "XmlBeans 3.1 de lib\\ de PDI" );
    igual( "ab\tc\n", PptxDiapos.limpio( "a\u0001b\tc\n\u0000" ), "caracteres de control fuera" );
    igual( "x😀", PptxDiapos.limpio( "x😀\uD800" ), "pares sustitutos si, sueltos no" );
    double[] t = PptxDiapos.tamanoSvg( ModeloMuestra.svgMuestra( "x", 640, 240 ) );
    igual( 640.0, t[ 0 ], "ancho del SVG" );
    igual( 240.0, t[ 1 ], "alto del SVG" );
    double[] v = PptxDiapos.tamanoSvg( "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 37 21\"></svg>" );
    igual( 37.0, v[ 0 ], "ancho por viewBox" );

    Informe inf = chico( "es" );
    inf.titulo = "Título\u0001 con control";
    inf.secciones.add( new Seccion( SeccionId.RESUMEN, "Resumen" ) );
    inf.secciones.get( 0 ).resumen = "Sin bloques\u0007.";
    Seccion rara = new Seccion();
    rara.titulo = "Rara";
    rara.bloques = null;
    inf.secciones.add( rara );
    inf.secciones.add( null );
    inf.avisos = null;
    File f = new File( carpeta, "texto-raro.pptx" );
    new RenderPptx( null ).escribir( inf, null, f, null );
    try ( XMLSlideShow ppt = abrir( f ) ) {
      igual( "Título con control", titulo( ppt.getSlides().get( 0 ) ), "titulo sin el caracter de control" );
      contiene( texto( ppt.getSlides().get( 2 ) ), "Sin bloques.", "seccion sin bloques muestra su resumen" );
    }
  }

  // ------------------------------------------------------------------ python-pptx (opcional)

  public void pruebaPythonPptx() throws Exception {
    File f = new File( carpeta, "muestra-sin-navegador.pptx" );
    if ( !f.isFile() ) {
      pruebaSinNavegador();
    }
    String script = "import sys\n"
      + "from pptx import Presentation\n"
      + "from pptx.util import Emu\n"
      + "p = Presentation(sys.argv[1])\n"
      + "print('SIZE', round(Emu(p.slide_width).pt), round(Emu(p.slide_height).pt))\n"
      + "print('SLIDES', len(p.slides))\n"
      + "for i, s in enumerate(p.slides, 1):\n"
      + "    t = s.shapes.title.text if s.shapes.title is not None else ''\n"
      + "    n = s.notes_slide.notes_text_frame.text if s.has_notes_slide else ''\n"
      + "    tb = sum(1 for sh in s.shapes if sh.has_table)\n"
      + "    pic = sum(1 for sh in s.shapes if sh.shape_type == 13)\n"
      + "    print('S', i, len(n), tb, pic, t.encode('unicode_escape').decode('ascii'))\n";
    File py = new File( carpeta, "leer_pptx.py" );
    java.nio.file.Files.write( py.toPath(), script.getBytes( StandardCharsets.UTF_8 ) );
    List<String> salida = new ArrayList<>();
    int codigo;
    try {
      ProcessBuilder pb = new ProcessBuilder( "python", "-I", py.getAbsolutePath(), f.getAbsolutePath() );
      pb.redirectErrorStream( true );
      Process pr = pb.start();
      try ( BufferedReader br = new BufferedReader( new InputStreamReader( pr.getInputStream(),
        StandardCharsets.UTF_8 ) ) ) {
        String l;
        while ( ( l = br.readLine() ) != null ) {
          salida.add( l );
        }
      }
      if ( !pr.waitFor( 60, TimeUnit.SECONDS ) ) {
        pr.destroyForcibly();
        saltear( "python no termino" );
      }
      codigo = pr.exitValue();
    } catch ( java.io.IOException e ) {
      saltear( "no hay python en el PATH" );
      return;
    }
    String todo = String.join( "\n", salida );
    if ( codigo != 0 && todo.contains( "No module named" ) ) {
      saltear( "python sin python-pptx" );
    }
    igual( 0, codigo, "python-pptx abre el archivo:\n" + todo );
    contiene( todo, "SIZE 960 540", "tamano segun python-pptx" );
    try ( XMLSlideShow ppt = abrir( f ) ) {
      contiene( todo, "SLIDES " + ppt.getSlides().size(), "misma cantidad de diapositivas" );
    }
  }

  // ------------------------------------------------------------------ ayudas

  /** Informe minimo (sin secciones). */
  private static Informe chico( String idioma ) {
    Informe inf = new Informe();
    inf.titulo = "Prueba";
    inf.idioma = idioma;
    inf.generado = "2026-10-20T14:32:05-03:00";
    return inf;
  }

  private static XMLSlideShow abrir( File f ) throws Exception {
    try ( InputStream in = new FileInputStream( f ) ) {
      return new XMLSlideShow( in );
    }
  }

  private static Properties props( String idioma ) throws Exception {
    Properties p = new Properties();
    try ( InputStream in = PruebaPptx.class.getClassLoader().getResourceAsStream( Rotulos.CARPETA + "pptx_" + idioma
      + ".properties" ) ) {
      noNulo( in, "pptx_" + idioma + ".properties en el classpath" );
      p.load( new InputStreamReader( in, StandardCharsets.UTF_8 ) );
    }
    return p;
  }

  /** Titulo de la diapositiva; la portada lo tiene en un placeholder ctrTitle, que getTitle() no mira. */
  private static String titulo( XSLFSlide d ) {
    String t = d.getTitle();
    if ( t != null ) {
      return t;
    }
    for ( XSLFTextShape sh : d.getPlaceholders() ) {
      if ( sh.getTextType() == Placeholder.CENTERED_TITLE ) {
        return sh.getText();
      }
    }
    return null;
  }

  /** Numero (1..n) de la primera diapositiva con ese titulo exacto; 0 si no hay. */
  private static int primera( List<XSLFSlide> ds, String titulo ) {
    for ( int i = 0; i < ds.size(); i++ ) {
      if ( titulo.equals( ds.get( i ).getTitle() ) ) {
        return i + 1;
      }
    }
    return 0;
  }

  /** El texto de la seccion: su primera diapositiva y las «(cont.)» que siguen. */
  private static String textoSeccion( List<XSLFSlide> ds, String titulo ) {
    int i = primera( ds, titulo );
    cierto( i > 0, "hay diapositiva para " + titulo );
    StringBuilder b = new StringBuilder( texto( ds.get( i - 1 ) ) );
    for ( int k = i; k < ds.size() && ds.get( k ).getTitle().equals( titulo + " (cont.)" ); k++ ) {
      b.append( '\n' ).append( texto( ds.get( k ) ) );
    }
    return b.toString();
  }

  private static String texto( XSLFSlide d ) {
    StringBuilder b = new StringBuilder();
    for ( XSLFShape sh : d.getShapes() ) {
      if ( sh instanceof XSLFTextShape ) {
        b.append( ( (XSLFTextShape) sh ).getText() ).append( '\n' );
      } else if ( sh instanceof XSLFTable ) {
        for ( XSLFTableRow r : (XSLFTable) sh ) {
          for ( XSLFTableCell c : r.getCells() ) {
            b.append( c.getText() ).append( " | " );
          }
          b.append( '\n' );
        }
      }
    }
    return b.toString();
  }

  private static List<String> celdas( List<XSLFSlide> ds ) {
    List<String> l = new ArrayList<>();
    for ( XSLFSlide d : ds ) {
      for ( XSLFShape sh : d.getShapes() ) {
        if ( sh instanceof XSLFTable ) {
          for ( XSLFTableRow r : (XSLFTable) sh ) {
            for ( XSLFTableCell c : r.getCells() ) {
              l.add( c.getText() );
            }
          }
        }
      }
    }
    return l;
  }

  private static String notas( XSLFSlide d ) {
    XSLFNotes n = d.getNotes();
    if ( n == null ) {
      return "";
    }
    StringBuilder b = new StringBuilder();
    for ( List<XSLFTextParagraph> ps : n.getTextParagraphs() ) {
      for ( XSLFTextParagraph p : ps ) {
        b.append( p.getText() ).append( '\n' );
      }
    }
    return b.toString();
  }

  private static int imagenes( XSLFSlide d ) {
    int n = 0;
    for ( XSLFShape sh : d.getShapes() ) {
      if ( sh instanceof XSLFPictureShape ) {
        n++;
      }
    }
    return n;
  }

  private static int tablas( XSLFSlide d ) {
    int n = 0;
    for ( XSLFShape sh : d.getShapes() ) {
      if ( sh instanceof XSLFTable ) {
        n++;
      }
    }
    return n;
  }

  private static XSLFTable tabla( XSLFSlide d ) {
    for ( XSLFShape sh : d.getShapes() ) {
      if ( sh instanceof XSLFTable ) {
        return (XSLFTable) sh;
      }
    }
    return null;
  }

  private static boolean tieneImagen( XSLFSlide d, byte[] datos ) {
    if ( datos == null ) {
      return false;
    }
    for ( XSLFShape sh : d.getShapes() ) {
      if ( sh instanceof XSLFPictureShape && Arrays.equals( datos, ( (XSLFPictureShape) sh ).getPictureData()
        .getData() ) ) {
        return true;
      }
    }
    return false;
  }
}
