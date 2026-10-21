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

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import org.apache.poi.util.IOUtils;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTInline;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDrawing;

import io.github.ivi2511.pentahomcp.ModeloMuestra;
import io.github.ivi2511.pentahomcp.informes.Combinar;
import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Resultado;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Grafico;
import io.github.ivi2511.pentahomcp.informes.modelo.Imagen;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Word de WP10 sobre ModeloMuestra y sobre un informe del tamano de un ejercicio real, sin Kettle: POI lo
 * vuelve a leer y se cuentan titulos Heading 1, tablas e imagenes; se verifican el indice (campo TOC con
 * hipervinculos a los marcadores de las secciones), que no haya saltos de pagina manuales, que ninguna
 * figura pase del area util, el encabezado y el pie, los avisos y el modo actual de Word. Si LibreOffice
 * esta instalado, convierte los .docx a PDF y revisa pagina por pagina (con el iText de PDI) que no haya
 * paginas en blanco y que el diagrama del linaje quede en la misma pagina que su titulo.
 * Los archivos quedan en {@code <Salida>\prueba\salida\docx}.
 *
 * @dueño WP10
 */
public class PruebaDocx {

  private static final String PIE = "Pentaho Live · IDC Studios";
  private static final double EMU_PT = 12700.0;

  /** SVG de muestra para los graficos (lo mismo que daria GraficosSvg, con rect de fondo). */
  static final RenderPptx.ProveedorSvg SVG_MUESTRA = ( g, t, ancho ) -> ModeloMuestra.svgMuestra( "Grafico " + g.clase
    + ": " + g.titulo, ancho, Grafico.GANTT.equals( g.clase ) ? 220 : 180 );

  private File carpeta;

  public void preparar() {
    carpeta = AyudaOffice.carpeta( "docx" );
  }

  // ------------------------------------------------------------------ informe de muestra

  public void pruebaMuestra() throws Exception {
    Informe inf = ModeloMuestra.crear();
    File f = new File( carpeta, "muestra.docx" );
    Resultado res = new RenderDocx( ModeloMuestra.pngPrerender( inf ) ).conSvgGraficos( SVG_MUESTRA ).escribir( inf,
      ModeloMuestra.tema(), f, Progreso.NULO );
    archivo( f, 20000, "docx escrito" );
    igual( 1, res.archivos.size(), "un solo archivo" );
    igual( f.getAbsoluteFile(), res.archivos.get( 0 ), "el principal primero" );
    igual( f.length(), res.bytes, "bytes del resultado" );
    igual( new ArrayList<String>(), res.avisos, "sin avisos: hay PNG de todos los diagramas" );
    falso( new File( carpeta, f.getName() + ".tmp" ).exists(), "no queda el .tmp" );
    falso( new File( carpeta, f.getName() + ".tmp.2" ).exists(), "no queda el .tmp.2" );

    String xml = parte( f, "word/document.xml" );
    String ajustes = parte( f, "word/settings.xml" );
    contiene( ajustes, "w:updateFields w:val=\"true\"", "Word actualiza el indice al abrir" );
    contiene( ajustes, "w:name=\"compatibilityMode\"", "modo actual de Word (no «compatibilidad»)" );
    contiene( ajustes, "w:val=\"15\"", "compatibilityMode 15" );
    noContiene( xml, "w:type=\"page\"", "sin saltos de pagina manuales (las paginas en blanco salen de ahi)" );
    contiene( xml, "TOC \\o \"1-1\" \\h \\z \\u", "campo TOC de un nivel" );
    contiene( xml, "<w:pgSz w:w=\"11906\" w:h=\"16838\"/>", "A4" );
    cierto( xml.indexOf( "<w:pgMar" ) > xml.indexOf( "<w:pgSz" ) && xml.indexOf( "<w:titlePg" ) > xml.indexOf(
      "<w:pgMar" ), "orden del esquema en sectPr: pgSz, pgMar, titlePg" );

    try ( XWPFDocument doc = abrir( f ) ) {
      // un Heading1 por seccion viva (sin portada ni omitidas), en orden y sin el estado en el titulo
      List<String> esperadas = new ArrayList<>();
      for ( Seccion s : RenderHtml.ordenadas( inf ) ) {
        if ( !Seccion.OMITIDA.equals( s.estado ) && !SeccionId.PORTADA.equals( s.id ) ) {
          esperadas.add( s.titulo );
        }
      }
      igual( esperadas, textos( doc, "Heading1" ), "un Heading 1 por seccion, en orden" );
      cierto( textos( doc, "Heading2" ).contains( "Qué hace cada step" ), "titulo de bloque como Heading 2" );
      noContiene( String.join( "|", textos( doc, "Heading1" ) ), "Flujo del job", "la omitida no sale" );

      // indice: un hipervinculo por seccion, cada uno a un marcador que existe en su titulo
      List<String> anclas = new ArrayList<>();
      Matcher m = Pattern.compile( "<w:hyperlink w:anchor=\"([^\"]+)\"" ).matcher( xml );
      while ( m.find() ) {
        anclas.add( m.group( 1 ) );
      }
      igual( esperadas.size(), anclas.size(), "un hipervinculo del indice por seccion" );
      Set<String> marcadores = new HashSet<>();
      Matcher mb = Pattern.compile( "<w:bookmarkStart [^>]*w:name=\"([^\"]+)\"" ).matcher( xml );
      while ( mb.find() ) {
        cierto( marcadores.add( mb.group( 1 ) ), "marcador repetido: " + mb.group( 1 ) );
      }
      for ( String a : anclas ) {
        cierto( marcadores.contains( a ), "el indice apunta a un marcador que existe: " + a );
        cierto( a.matches( "[A-Za-z][A-Za-z0-9_]{0,39}" ), "nombre de marcador valido en Word: " + a );
      }
      contiene( todo( doc ), "Secciones omitidas: Flujo del job: Solo para jobs.", "omitidas en el indice" );
      contiene( todo( doc ), "actualizar los campos", "ayuda del indice" );

      // estado de la seccion en un recuadro, no en el titulo
      XWPFParagraph parcial = conTexto( doc, "Parcial: 1 step sin resolver" );
      noNulo( parcial, "aviso del estado parcial" );
      igual( "PliveAvisoWarn", parcial.getStyle(), "estilo del aviso parcial" );
      igual( "Parcial: 1 step sin resolver: «Leer clientes» necesita la base.", parcial.getText(),
        "el estado sin el rotulo «Atención.» delante" );
      XWPFParagraph error = conTexto( doc, "Error: Tiempo agotado (60 s) al validar." );
      noNulo( error, "aviso del estado error" );
      igual( "PliveAvisoError", error.getStyle(), "estilo del aviso de error" );
      XWPFParagraph errorBloque = conTexto( doc, "La validación no terminó" );
      noNulo( errorBloque, "aviso del modelo" );
      igual( "Error. La validación no terminó: «Leer clientes» no responde.", errorBloque.getText(),
        "un aviso del modelo lleva su rotulo" );

      // portada: banda con titulo, subtitulo y fecha; datos de cover; avisos del informe
      String portada = celdas( doc.getTables().get( 0 ) );
      contiene( portada, inf.titulo, "titulo en la banda" );
      contiene( portada, inf.subtitulo, "subtitulo en la banda" );
      contiene( portada, "Generado el 20 de octubre de 2026, 14:32", "fecha en castellano" );
      contiene( portada, "TRANSFORMACIÓN", "tipo de documento" );
      contiene( todo( doc ), "Autor: Nombre Apellido", "datos de la portada" );
      contiene( todo( doc ), "cambios sin guardar", "avisos del informe en la portada" );

      // tablas: banda, indicadores x2 y las del modelo (con la cabecera repetida); el step raro literal
      int tablasModelo = tablasModelo( inf );
      igual( 1 + 2 + tablasModelo, doc.getTables().size(), "tablas: portada, 2 de indicadores y las del modelo" );
      int conCabecera = 0;
      for ( XWPFTable t : doc.getTables() ) {
        if ( t.getRow( 0 ).isRepeatHeader() ) {
          conCabecera++;
        }
      }
      igual( tablasModelo, conCabecera, "cada tabla del modelo repite su cabecera" );
      contiene( todasLasCeldas( doc ), ModeloMuestra.STEP_RARO, "el step <b>&\"x en una celda, literal" );

      // figuras: logo, lienzo, 3 Mermaid y 4 graficos; ninguna pasa del area util
      List<double[]> figuras = figuras( doc );
      igual( 9, figuras.size(), "logo + lienzo + 3 diagramas + 4 graficos" );
      for ( double[] wh : figuras ) {
        cierto( wh[ 0 ] <= ANCHO_UTIL_A4 + 0.5, "figura de " + wh[ 0 ] + " pt de ancho (util " + ANCHO_UTIL_A4 + ")" );
        cierto( wh[ 1 ] <= ALTO_UTIL_A4 - 89.5, "figura de " + wh[ 1 ] + " pt de alto (util " + ALTO_UTIL_A4 + ")" );
      }

      // IA rotulada; encabezado y pie
      contiene( todo( doc ), "Texto redactado con asistencia de IA", "rotulo de IA" );
      XWPFHeader h = doc.getHeaderFooterPolicy().getDefaultHeader();
      contiene( h.getText(), inf.titulo, "titulo en el encabezado" );
      XWPFFooter pie = doc.getHeaderFooterPolicy().getDefaultFooter();
      contiene( pie.getText(), PIE, "pie IDC" );
      String pieXml = pie._getHdrFtr().xmlText();
      contiene( pieXml, "PAGE", "campo de numero de pagina" );
      contiene( pieXml, "NUMPAGES", "campo de total de paginas" );
      cierto( !pie.getAllPictures().isEmpty(), "simbolo IDC en el pie" );
      noNulo( doc.getHeaderFooterPolicy().getFirstPageHeader(), "la portada tiene su encabezado propio (vacio)" );
      igual( "Ventas por producto", doc.getProperties().getCoreProperties().getTitle(), "propiedad titulo" );
    }
  }

  /** Sin navegador: los diagramas Mermaid sin lienzo van como su tabla alternativa, con aviso. */
  public void pruebaSinNavegador() throws Exception {
    Informe inf = ModeloMuestra.crear();
    File f = new File( carpeta, "muestra-sin-navegador.docx" );
    Resultado res = new RenderDocx( null ).conSvgGraficos( SVG_MUESTRA ).escribir( inf, ModeloMuestra.tema(), f,
      Progreso.NULO );
    contiene( String.join( "\n", res.avisos ), "2 diagrama(s) van como tabla", "aviso de diagramas como tabla" );
    try ( XWPFDocument doc = abrir( f ) ) {
      contiene( todasLasCeldas( doc ), "VARCHAR(30)", "alternativa del ER como tabla" );
      contiene( todo( doc ), "El diagrama va como tabla", "aclaracion bajo la tabla" );
      igual( 1 + 4 + 1, figuras( doc ).size(), "logo, 4 graficos y el lienzo del flujo" );
    }
  }

  /** Graficos que no se pueden dibujar: van sus datos como tabla, con aviso. */
  public void pruebaGraficoComoTabla() throws Exception {
    Informe inf = ModeloMuestra.crear();
    File f = new File( carpeta, "graficos-como-tabla.docx" );
    Resultado res = new RenderDocx( ModeloMuestra.pngPrerender( inf ) ).conSvgGraficos( ( g, t, a ) -> {
      throw new IllegalStateException( "sin SVG" );
    } ).escribir( inf, ModeloMuestra.tema(), f, Progreso.NULO );
    contiene( String.join( "\n", res.avisos ), "4 gráfico(s) van como tabla", "aviso de graficos" );
    try ( XWPFDocument doc = abrir( f ) ) {
      contiene( todasLasCeldas( doc ), "Fin (ms)", "datos del gantt como tabla" );
    }
  }

  // ------------------------------------------------------------------ informe grande y LibreOffice

  /**
   * Un informe del tamano del ejercicio que reporto el usuario (lienzo muy ancho, linaje muy alto, 20 tablas
   * de diccionario): ninguna figura pasa de la pagina y, si hay LibreOffice, ninguna pagina sale en blanco y
   * el diagrama del linaje queda en la pagina de su titulo.
   */
  public void pruebaGrandeSinPaginasEnBlanco() throws Exception {
    Informe inf = AyudaOffice.grande();
    File f = new File( carpeta, "grande.docx" );
    new RenderDocx( AyudaOffice.pngGrande( inf ) ).conSvgGraficos( SVG_MUESTRA ).escribir( inf, ModeloMuestra.tema(),
      f, Progreso.NULO );
    try ( XWPFDocument doc = abrir( f ) ) {
      for ( double[] wh : figuras( doc ) ) {
        cierto( wh[ 0 ] <= ANCHO_UTIL_A4 + 0.5, "figura de " + wh[ 0 ] + " pt de ancho" );
        cierto( wh[ 1 ] <= ALTO_UTIL_A4 - 89.5, "figura de " + wh[ 1 ] + " pt de alto" );
      }
      igual( 1 + 2 + tablasModelo( inf ), doc.getTables().size(), "tablas del informe grande" );
    }
    noContiene( parte( f, "word/document.xml" ), "w:type=\"page\"", "sin saltos de pagina manuales" );

    List<AyudaOffice.Pagina> ps = paginasPdf( f );
    if ( ps == null ) {
      return;
    }
    cierto( ps.size() >= 12 && ps.size() <= 40, "paginas del PDF: " + ps.size() );
    sinPaginasEnBlanco( ps, inf.titulo );
    // la pagina del titulo «Linaje de campos» (no la del indice) tiene el diagrama: no queda el titulo solo
    AyudaOffice.Pagina linaje = null;
    for ( AyudaOffice.Pagina p : ps ) {
      if ( p.numero > 2 && AyudaOffice.contenido( p, inf.titulo ).startsWith( "Linaje de campos" ) ) {
        linaje = p;
      }
    }
    noNulo( linaje, "pagina que empieza con «Linaje de campos»: " + ps );
    cierto( linaje.imagenes >= 2, "el diagrama del linaje en la pagina de su titulo (simbolo del pie + figura): "
      + linaje );
  }

  /** El informe de muestra por LibreOffice: sin paginas en blanco y con la portada sin pie. */
  public void pruebaMuestraEnLibreOffice() throws Exception {
    Informe inf = ModeloMuestra.crear();
    File f = new File( carpeta, "muestra-lo.docx" );
    new RenderDocx( ModeloMuestra.pngPrerender( inf ) ).conSvgGraficos( SVG_MUESTRA ).escribir( inf, ModeloMuestra
      .tema(), f, Progreso.NULO );
    List<AyudaOffice.Pagina> ps = paginasPdf( f );
    if ( ps == null ) {
      return;
    }
    noContiene( ps.get( 0 ).texto, PIE, "la portada no lleva pie" );
    contiene( ps.get( 1 ).texto, "CONTENIDO", "el indice en la pagina 2" );
    contiene( ps.get( 1 ).texto, PIE, "pie desde la pagina 2" );
    sinPaginasEnBlanco( ps, inf.titulo );
  }

  // ------------------------------------------------------------------ diagramas propios

  /**
   * Flujo de 26 steps y linaje con grafo (motor propio, sin navegador): cada parte como PNG del tamano de
   * la hoja, ninguna pasa del area util, sin aviso de diagramas como tabla y, con LibreOffice, sin paginas
   * en blanco.
   */
  public void pruebaDiagramaPropio() throws Exception {
    Informe inf = PruebaDiagramaPropio.conGrafos( false );
    File f = new File( carpeta, "propio.docx" );
    Resultado res = new RenderDocx( null ).conSvgGraficos( SVG_MUESTRA ).escribir( inf, ModeloMuestra.tema(), f,
      Progreso.NULO );
    igual( new ArrayList<String>(), res.avisos, "sin avisos: el motor propio no necesita navegador" );
    try ( XWPFDocument doc = abrir( f ) ) {
      List<double[]> fs = figuras( doc );
      // logo + flujo (1 o mas partes) + lienzo + linaje + 4 graficos
      cierto( fs.size() >= 8, "figuras: " + fs.size() );
      int grandes = 0;
      for ( double[] wh : fs ) {
        cierto( wh[ 0 ] <= ANCHO_UTIL_A4 + 0.5, "figura de " + wh[ 0 ] + " pt de ancho" );
        cierto( wh[ 1 ] <= ALTO_UTIL_A4 - 89.5, "figura de " + wh[ 1 ] + " pt de alto" );
        grandes += wh[ 0 ] >= ANCHO_UTIL_A4 * 0.6 && wh[ 1 ] >= 150 ? 1 : 0;
      }
      cierto( grandes >= 1, "el flujo de 26 steps no es una tira: ocupa la hoja" );
    }
    List<AyudaOffice.Pagina> ps = paginasPdf( f );
    if ( ps == null ) {
      return;
    }
    sinPaginasEnBlanco( ps, inf.titulo );
    // el flujo va en la pagina de su titulo (antes el keepNext de la figura la encadenaba con el lienzo y
    // la tabla siguientes, y la cadena entera saltaba a la pagina siguiente dejando el titulo solo)
    AyudaOffice.Pagina flujo = null;
    for ( AyudaOffice.Pagina p : ps ) {
      if ( p.numero > 2 && AyudaOffice.contenido( p, inf.titulo ).startsWith( "Diagrama de flujo" ) ) {
        flujo = p;
      }
    }
    noNulo( flujo, "pagina que empieza con «Diagrama de flujo»: " + ps );
    cierto( flujo.imagenes >= 2, "el diagrama del flujo en la pagina de su titulo (simbolo del pie + figura): "
      + flujo );
  }

  // ------------------------------------------------------------------ informe combinado

  /**
   * Dos documentos en un archivo (Combinar): la parte general como Heading 1 sin numero, un Heading 1
   * numerado por documento, sus secciones como Heading 2 «1.1», campo TOC de dos niveles, marcadores unicos,
   * figuras dentro de la pagina y, con LibreOffice, sin paginas en blanco.
   */
  public void pruebaCombinado() throws Exception {
    Informe a = ModeloMuestra.crear();
    Informe b = ModeloMuestra.crear();
    b.titulo = "Segundo documento";
    b.nombreDoc = "segundo.ktr";
    Informe inf = Combinar.combinar( Arrays.asList( a, b ), null, "es", ModeloMuestra.tema() );
    File f = new File( carpeta, "combinado.docx" );
    Resultado res = new RenderDocx( ModeloMuestra.pngPrerender( inf ) ).conSvgGraficos( SVG_MUESTRA ).escribir( inf,
      ModeloMuestra.tema(), f, Progreso.NULO );
    archivo( f, 30000, "docx combinado escrito" );
    igual( new ArrayList<String>(), res.avisos, "sin avisos: los PNG de los diagramas tienen el prefijo dN-" );
    String xml = parte( f, "word/document.xml" );
    contiene( xml, "TOC \\o \"1-2\" \\h \\z \\u", "campo TOC de dos niveles" );
    noContiene( xml, "w:type=\"page\"", "sin saltos de pagina manuales" );

    List<String> h1 = new ArrayList<>();
    List<String> h2 = new ArrayList<>();
    for ( Capitulos.Grupo g : Capitulos.grupos( inf ) ) {
      if ( g.capitulo == null ) {
        for ( Seccion s : g.vivas() ) {
          h1.add( s.titulo );
        }
        continue;
      }
      h1.add( g.numero + "  " + g.capitulo.titulo );
      List<Seccion> vs = g.vivas();
      for ( int i = 0; i < vs.size(); i++ ) {
        h2.add( Capitulos.numero( g, i ) + "  " + vs.get( i ).titulo );
      }
    }
    igual( 3, h1.size(), "parte general + 2 documentos" );
    try ( XWPFDocument doc = abrir( f ) ) {
      igual( h1, textos( doc, "Heading1" ), "Heading 1: «Documentos del informe», «1  ...» y «2  ...»" );
      igual( h2, textos( doc, "Heading2" ), "Heading 2 numerados por documento, en orden" );
      contiene( h1.get( 2 ), "Segundo documento", "el titulo de la segunda parte" );
      contiene( todasLasCeldas( doc ), "segundo.ktr", "la tabla de documentos de la parte general" );
      Set<String> marcadores = new HashSet<>();
      Matcher mb = Pattern.compile( "<w:bookmarkStart [^>]*w:name=\"([^\"]+)\"" ).matcher( xml );
      while ( mb.find() ) {
        cierto( marcadores.add( mb.group( 1 ) ), "marcador repetido: " + mb.group( 1 ) );
        cierto( mb.group( 1 ).matches( "[A-Za-z][A-Za-z0-9_]{0,39}" ), "marcador valido: " + mb.group( 1 ) );
      }
      cierto( marcadores.contains( "sec_d1_flow" ) && marcadores.contains( "sec_d2_flow" ),
        "las secciones de cada documento con su propio marcador: " + marcadores );
      Matcher m = Pattern.compile( "<w:hyperlink w:anchor=\"([^\"]+)\"" ).matcher( xml );
      while ( m.find() ) {
        cierto( marcadores.contains( m.group( 1 ) ), "vinculo a un marcador que existe: " + m.group( 1 ) );
      }
      for ( double[] wh : figuras( doc ) ) {
        cierto( wh[ 0 ] <= ANCHO_UTIL_A4 + 0.5, "figura de " + wh[ 0 ] + " pt de ancho" );
        cierto( wh[ 1 ] <= ALTO_UTIL_A4 - 89.5, "figura de " + wh[ 1 ] + " pt de alto" );
      }
    }
    List<AyudaOffice.Pagina> ps = paginasPdf( f );
    if ( ps == null ) {
      return;
    }
    sinPaginasEnBlanco( ps, inf.titulo );
  }

  // ------------------------------------------------------------------ bordes

  public void pruebaTextoRaroYVacio() throws Exception {
    Informe inf = new Informe();
    inf.titulo = "Título\u0001 con control\ty tab";
    inf.idioma = "es";
    Seccion s = new Seccion( SeccionId.RESUMEN, null );
    s.resumen = "Sin bloques\u0007.";
    inf.secciones.add( s );
    Seccion rara = new Seccion();
    rara.titulo = "Rara";
    rara.bloques = null;
    inf.secciones.add( rara );
    inf.secciones.add( null );
    inf.avisos = null;
    Tabla t = new Tabla( "A", "B" ).fila( "con\ttab", null ).fila( "x" );
    t.titulo = "Con tab";
    Imagen rota = new Imagen( Imagen.PNG, new byte[] { 1, 2, 3 }, 0, 0, 1f );
    Seccion d = new Seccion( SeccionId.DICCIONARIO, "Diccionario" ).con( t ).con( rota );
    inf.secciones.add( d );
    File f = new File( carpeta, "texto-raro.docx" );
    new RenderDocx( null ).escribir( inf, null, f, null );
    try ( XWPFDocument doc = abrir( f ) ) {
      contiene( celdas( doc.getTables().get( 0 ) ), "Título con control", "titulo sin el caracter de control" );
      igual( Arrays.asList( "Resumen", "Diccionario", "Rara" ), textos( doc, "Heading1" ),
        "titulo por defecto, el diccionario y la rara al final (la null no)" );
      contiene( todo( doc ), "Sin bloques.", "resumen de la seccion sin bloques" );
      contiene( todo( doc ), "Imagen no disponible", "imagen rota: nota, no excepcion" );
      contiene( todasLasCeldas( doc ), "con\ttab", "tab dentro de la celda, en orden" );
    }
    // sin ninguna seccion
    Informe vacio = new Informe();
    vacio.idioma = "en";
    File g = new File( carpeta, "vacio.docx" );
    new RenderDocx( null ).escribir( vacio, new Tema(), g, null );
    try ( XWPFDocument doc = abrir( g ) ) {
      contiene( celdas( doc.getTables().get( 0 ) ), "Report", "titulo por defecto en ingles" );
      contiene( todo( doc ), "Contents", "indice en ingles" );
    }
  }

  public void pruebaIdiomas() throws Exception {
    Properties es = props( "es" );
    for ( String i : Arrays.asList( "en", "pt" ) ) {
      igual( es.keySet(), props( i ).keySet(), "mismas claves en docx_" + i );
    }
    for ( Object k : es.keySet() ) {
      noContiene( es.getProperty( (String) k ), "vos ", "sin voseo en docx_es: " + k );
    }
    String[][] casos = { { "en", "Contents", "Generated on October 20, 2026, 14:32", "Page" },
      { "pt", "Sumário", "Gerado em 20 de outubro de 2026, 14:32", "Página" } };
    for ( String[] c : casos ) {
      Informe inf = ModeloMuestra.crear();
      inf.idioma = c[ 0 ];
      inf.rotulos.clear();
      File f = new File( carpeta, "muestra-" + c[ 0 ] + ".docx" );
      new RenderDocx( null ).conSvgGraficos( SVG_MUESTRA ).escribir( inf, ModeloMuestra.tema(), f, Progreso.NULO );
      try ( XWPFDocument doc = abrir( f ) ) {
        contiene( todo( doc ), c[ 1 ], "indice en " + c[ 0 ] );
        contiene( celdas( doc.getTables().get( 0 ) ), c[ 2 ], "fecha en " + c[ 0 ] );
        contiene( doc.getHeaderFooterPolicy().getDefaultFooter().getText(), c[ 3 ], "pie en " + c[ 0 ] );
      }
      contiene( parte( f, "word/styles.xml" ), "w:lang w:val=\"" + Rotulos.locale( c[ 0 ] ).toLanguageTag() + "\"",
        "idioma de revision en " + c[ 0 ] );
    }
  }

  public void pruebaLetterYColores() throws Exception {
    Informe inf = ModeloMuestra.crear();
    Tema t = ModeloMuestra.tema();
    t.pagina = Tema.LETTER;
    t.colores.primario = "#7A1F3D";
    File f = new File( carpeta, "letter.docx" );
    new RenderDocx( null ).conSvgGraficos( SVG_MUESTRA ).escribir( inf, t, f, Progreso.NULO );
    contiene( parte( f, "word/document.xml" ), "<w:pgSz w:w=\"12240\" w:h=\"15840\"/>", "Letter" );
    contiene( parte( f, "word/styles.xml" ), "7A1F3D", "color primario en los titulos" );
  }

  public void pruebaCancelar() throws Exception {
    File f = new File( carpeta, "cancelado.docx" );
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
    lanza( CancellationException.class, () -> new RenderDocx( null ).escribir( ModeloMuestra.crear(), ModeloMuestra
      .tema(), f, cancelado ), "cancelar corta la exportacion" );
    falso( f.exists(), "cancelado no deja archivo" );
    falso( new File( carpeta, f.getName() + ".tmp" ).exists(), "ni el .tmp" );
  }

  /** Anchos de columna: suman el ancho util y cada columna entra su palabra mas larga si se puede. */
  public void pruebaAnchosDeColumna() throws Exception {
    try ( org.apache.poi.xwpf.usermodel.XWPFDocument d = new org.apache.poi.xwpf.usermodel.XWPFDocument() ) {
      Informe inf = new Informe();
      RenderDocx.Escritor e = new RenderDocx.Escritor( d, inf, new Tema(), null, SVG_MUESTRA, Progreso.NULO );
      List<String> cols = Arrays.asList( "Campo destino", "Viene de", "Operación", "Tipo" );
      List<List<String>> filas = new ArrayList<>();
      filas.add( Arrays.asList( "campo_10", "Calcular importe › importe (cantidad × precio)",
        "búsqueda (clave: id_cliente)", "aggregate" ) );
      int[] a = e.anchos( cols, filas, 4, 9 );
      int suma = 0;
      for ( int x : a ) {
        suma += x;
      }
      igual( e.anchoUtilTw, suma, "suman el ancho util" );
      double minimo = ( "campo_10".length() * 9 * RenderDocx.Escritor.ANCHO_PALABRA + 10 ) * 20;
      cierto( a[ 0 ] >= minimo - 1, "«campo_10» entra sin partirse: " + a[ 0 ] + " twips (minimo " + minimo + ")" );
      cierto( a[ 1 ] > a[ 0 ] && a[ 1 ] > a[ 3 ], "la columna larga recibe mas: " + Arrays.toString( a ) );
      // muchas columnas con palabras largas: se reparte sin pasarse
      List<String> muchas = new ArrayList<>();
      List<String> fila = new ArrayList<>();
      for ( int i = 0; i < 12; i++ ) {
        muchas.add( "columna_" + i );
        fila.add( "palabra_muy_larga_numero_" + i );
      }
      int[] b = e.anchos( muchas, Arrays.asList( fila ), 12, 8 );
      int s2 = 0;
      for ( int x : b ) {
        cierto( x > 0, "ancho positivo" );
        s2 += x;
      }
      igual( e.anchoUtilTw, s2, "12 columnas suman el ancho util" );
    }
  }

  public void pruebaEntorno() {
    nulo( RenderDocx.problema( "es" ), "XmlBeans 3.1 de lib\\ de PDI" );
    Formato fmt = new RenderDocx( null ).formato();
    igual( Formato.deClave( RenderDocx.CLAVE ), fmt, "formato por clave" );
    if ( fmt != null ) {
      igual( "docx", fmt.extension, "extension" );
    }
    igual( "sec_flow", RenderDocx.Escritor.marcador( "sec-flow" ), "marcador valido" );
    igual( "s_1x", RenderDocx.Escritor.marcador( "1x" ), "marcador que empieza con letra" );
    cierto( RenderDocx.Escritor.marcador( "sec-" + new String( new char[ 80 ] ).replace( '\0', 'a' ) ).length() <= 40,
      "marcador de hasta 40" );
  }

  // ------------------------------------------------------------------ ayudas

  /** Area util de A4 con los margenes de RenderDocx, en puntos. */
  private static final double ANCHO_UTIL_A4 = ( 11906 - 2 * 1134 ) / 20.0;
  private static final double ALTO_UTIL_A4 = ( 16838 - 2 * 1247 ) / 20.0;

  /** Convierte con LibreOffice y lee las paginas; null (y salteada si no hay LibreOffice) si no se puede. */
  private List<AyudaOffice.Pagina> paginasPdf( File docx ) throws Exception {
    File pdf = AyudaOffice.aPdf( docx, new File( carpeta, "pdf" ) );
    if ( pdf == null ) {
      saltear( "no hay LibreOffice para revisar las paginas" );
    }
    List<AyudaOffice.Pagina> ps = AyudaOffice.paginas( pdf );
    if ( ps == null ) {
      saltear( "no esta el iText de PDI para leer el PDF" );
    }
    return ps;
  }

  /** Tablas del modelo en las secciones que se dibujan (sin la portada ni las omitidas). */
  static int tablasModelo( Informe inf ) {
    int n = 0;
    for ( Seccion s : inf.secciones ) {
      if ( s != null && !Seccion.OMITIDA.equals( s.estado ) && !SeccionId.PORTADA.equals( s.id ) ) {
        for ( Object b : s.bloques ) {
          if ( b instanceof Tabla ) {
            n++;
          }
        }
      }
    }
    return n;
  }

  /** Ninguna pagina (salvo la portada) queda solo con el encabezado y el pie. */
  private static void sinPaginasEnBlanco( List<AyudaOffice.Pagina> ps, String titulo ) {
    for ( AyudaOffice.Pagina p : ps ) {
      if ( p.numero == 1 ) {
        continue;
      }
      boolean texto = !AyudaOffice.contenido( p, titulo ).isEmpty();
      cierto( texto || p.imagenes >= 2, "pagina en blanco: " + p + "\n" + ps );
    }
  }

  static XWPFDocument abrir( File f ) throws Exception {
    try ( InputStream in = new FileInputStream( f ) ) {
      return new XWPFDocument( in );
    }
  }

  /** Una parte del zip tal cual (word/document.xml, word/settings.xml...). */
  static String parte( File f, String nombre ) throws Exception {
    try ( ZipFile z = new ZipFile( f ) ) {
      noNulo( z.getEntry( nombre ), nombre + " en el docx" );
      try ( InputStream in = z.getInputStream( z.getEntry( nombre ) ) ) {
        return new String( IOUtils.toByteArray( in ), StandardCharsets.UTF_8 );
      }
    }
  }

  /** Textos de los parrafos del cuerpo con ese estilo, en orden. */
  private static List<String> textos( XWPFDocument doc, String estilo ) {
    List<String> l = new ArrayList<>();
    for ( XWPFParagraph p : doc.getParagraphs() ) {
      if ( estilo.equals( p.getStyle() ) ) {
        l.add( p.getText() );
      }
    }
    return l;
  }

  private static XWPFParagraph conTexto( XWPFDocument doc, String parte ) {
    for ( XWPFParagraph p : doc.getParagraphs() ) {
      if ( p.getText().contains( parte ) ) {
        return p;
      }
    }
    return null;
  }

  /** Todo el texto del cuerpo (parrafos y tablas). */
  private static String todo( XWPFDocument doc ) {
    StringBuilder b = new StringBuilder();
    for ( IBodyElement e : doc.getBodyElements() ) {
      if ( e instanceof XWPFParagraph ) {
        b.append( ( (XWPFParagraph) e ).getText() ).append( '\n' );
      } else if ( e instanceof XWPFTable ) {
        b.append( celdas( (XWPFTable) e ) );
      }
    }
    return b.toString();
  }

  private static String todasLasCeldas( XWPFDocument doc ) {
    StringBuilder b = new StringBuilder();
    for ( XWPFTable t : doc.getTables() ) {
      b.append( celdas( t ) );
    }
    return b.toString();
  }

  private static String celdas( XWPFTable t ) {
    StringBuilder b = new StringBuilder();
    for ( XWPFTableRow r : t.getRows() ) {
      for ( XWPFTableCell c : r.getTableCells() ) {
        b.append( c.getText() ).append( " | " );
      }
      b.append( '\n' );
    }
    return b.toString();
  }

  /** Ancho y alto en puntos de cada imagen del cuerpo (parrafos y celdas). */
  static List<double[]> figuras( XWPFDocument doc ) {
    List<XWPFParagraph> ps = new ArrayList<>( doc.getParagraphs() );
    for ( XWPFTable t : doc.getTables() ) {
      for ( XWPFTableRow r : t.getRows() ) {
        for ( XWPFTableCell c : r.getTableCells() ) {
          ps.addAll( c.getParagraphs() );
        }
      }
    }
    List<double[]> l = new ArrayList<>();
    for ( XWPFParagraph p : ps ) {
      for ( XWPFRun r : p.getRuns() ) {
        for ( CTDrawing d : r.getCTR().getDrawingList() ) {
          for ( CTInline in : d.getInlineList() ) {
            l.add( new double[] { in.getExtent().getCx() / EMU_PT, in.getExtent().getCy() / EMU_PT } );
          }
        }
      }
    }
    return l;
  }

  private static Properties props( String idioma ) throws Exception {
    Properties p = new Properties();
    try ( InputStream in = PruebaDocx.class.getClassLoader().getResourceAsStream( Rotulos.CARPETA + "docx_" + idioma
      + ".properties" ) ) {
      noNulo( in, "docx_" + idioma + ".properties en el classpath" );
      p.load( new InputStreamReader( in, StandardCharsets.UTF_8 ) );
    }
    return p;
  }

}
