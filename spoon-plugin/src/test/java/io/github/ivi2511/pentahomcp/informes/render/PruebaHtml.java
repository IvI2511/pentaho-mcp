package io.github.ivi2511.pentahomcp.informes.render;

import static io.github.ivi2511.pentahomcp.Verificador.archivo;
import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falla;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.saltear;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.ivi2511.pentahomcp.EntornoKettle;
import io.github.ivi2511.pentahomcp.ModeloMuestra;
import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Resultado;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.Parrafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * RenderHtml sobre ModeloMuestra (diseno §3.1 y §8.1): sin red (cero URLs http(s) salvo los xmlns),
 * sin &lt;script&gt; en el modo estatico, escapes (un step llamado {@code <b>&"x}), CSS apto IE11,
 * impresion con @page y, si hay Edge, que Mermaid dibuje de verdad los diagramas del modo interactivo
 * (volcado del DOM) y capturas de pantalla de los dos modos en {@code <prueba>\salida\html}.
 *
 * @dueño WP7
 */
public class PruebaHtml {

  /** Cualquier http:// o https://; los permitidos son solo los que siguen a xmlns=" o xmlns:algo=". */
  private static final Pattern URL = Pattern.compile( "https?://", Pattern.CASE_INSENSITIVE );
  private static final Pattern XMLNS = Pattern.compile( "xmlns(:[A-Za-z0-9_-]+)?\\s*=\\s*[\"']$" );

  private File carpeta;

  public void preparar() {
    carpeta = EntornoKettle.salida( "html" );
  }

  public void pruebaInteractivoSinRed() throws Exception {
    Informe inf = ModeloMuestra.crear();
    File f = new File( carpeta, "muestra.html" );
    Resultado r = new RenderHtml( RenderHtml.Modo.INTERACTIVO, null, false ).escribir( inf, ModeloMuestra.tema(), f,
      Progreso.NULO );
    igual( f.getAbsolutePath(), r.archivos.get( 0 ).getAbsolutePath(), "el principal va primero" );
    archivo( f, 2_000_000, "el HTML interactivo lleva Mermaid inline" );
    igual( f.length(), r.bytes, "Resultado.bytes" );
    String html = leer( f );
    sinUrls( html );
    contiene( html, "<meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\">", "IE=edge" );
    contiene( html, "globalThis.mermaid", "el bundle va inline" );
    contiene( html, "securityLevel:'strict'", "config de §3.1" );
    contiene( html, "htmlLabels:true", "exportacion con htmlLabels:true (los rotulos de nodo salen bien)" );
    contiene( RenderHtml.scriptMermaid( new Tema(), true ), "htmlLabels:false", "pre-render IE11 con htmlLabels:false" );
    contiene( RenderHtml.scriptMermaid( new Tema(), true ), "function arreglar()", "y con el arreglo de rotulos" );
    igual( 2, cuenta( html, "<script>" ), "solo el bundle y el arranque" );
    igual( 2, cuenta( html, "</script>" ), "ningun </script> suelto dentro del bundle" );
    igual( 3, cuenta( html, "<pre class=\"mermaid\"" ), "flujo, linaje y ER" );
    noContiene( html, "src=\"http", "nada se carga de la red" );
    String estilo = html.substring( html.indexOf( "<style>" ), html.indexOf( "</style>" ) );
    noContiene( estilo, "@import", "sin @import" );
    noContiene( estilo, "url(", "sin url() en el CSS" );
    contiene( html, "id=\"sec-lineage\"", "ancla de la seccion" );
    contiene( html, "href=\"#sec-lineage\"", "indice con anclas" );
    contiene( html, "class=\"marca\"", "marca de agua por defecto" );
    contiene( html, "Pentaho Live · IDC Studios", "pie con el punto medio literal" );
    contiene( html, "data:image/png;base64,", "imagenes inline" );
    igual( Formato.HTML, new RenderHtml( RenderHtml.Modo.INTERACTIVO, null, false ).formato(), "formato" );
  }

  public void pruebaEstaticoSinScript() throws Exception {
    Informe inf = ModeloMuestra.crear();
    Map<String, String> svg = ModeloMuestra.svgPrerender( inf );
    File f = new File( carpeta, "muestra-estatico.html" );
    new RenderHtml( RenderHtml.Modo.ESTATICO, svg, false ).escribir( inf, ModeloMuestra.tema(), f, Progreso.NULO );
    String html = leer( f );
    cierto( f.length() < 1_000_000, "el liviano no lleva Mermaid: " + f.length() + " bytes" );
    noContiene( html.toLowerCase( Locale.ROOT ), "<script", "el modo estatico no tiene ningun script" );
    noContiene( html.toLowerCase( Locale.ROOT ), "javascript:", "ni javascript:" );
    noContiene( html, "<pre class=\"mermaid\"", "los diagramas van pre-renderizados" );
    igual( 3, cuenta( html, "(pre-render de muestra)" ), "los tres SVG de PrerenderMermaid" );
    noContiene( html, "<?xml", "sin prologo XML dentro del HTML" );
    sinUrls( html );
    igual( Formato.HTML_ESTATICO, new RenderHtml( RenderHtml.Modo.ESTATICO, null, false ).formato(), "formato" );

    // un SVG malo no mete scripts ni manejadores
    String malo = "<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100%\" viewBox=\"0 0 1000 "
      + "500\" onload=\"alert(1)\"><script>alert(2)</script><rect width=\"10\" height=\"10\"/></svg>";
    for ( Diagrama d : ModeloMuestra.diagramas( inf ) ) {
      if ( d.mermaid != null ) {
        svg.put( d.id, malo );
      }
    }
    String html2 = new RenderHtml( RenderHtml.Modo.ESTATICO, svg, false ).html( inf, ModeloMuestra.tema() );
    noContiene( html2.toLowerCase( Locale.ROOT ), "<script", "script sacado del SVG" );
    noContiene( html2.toLowerCase( Locale.ROOT ), "onload", "manejador sacado del SVG" );
    contiene( html2, "width=\"840\" height=\"420\"", "tamano explicito para IE11 (achicado a 840)" );
  }

  public void pruebaEstaticoSinPrerender() throws Exception {
    Informe inf = ModeloMuestra.crear();
    File f = new File( carpeta, "muestra-sin-prerender.html" );
    new RenderHtml( RenderHtml.Modo.ESTATICO, null, false ).escribir( inf, ModeloMuestra.tema(), f, Progreso.NULO );
    String html = leer( f );
    noContiene( html.toLowerCase( Locale.ROOT ), "<script", "sin script" );
    // linaje y ER no tienen imagen: recuadro + tabla alternativa; el flujo muestra el lienzo
    igual( 2, cuenta( html, "Diagrama disponible al exportar o al abrir en el navegador" ), "recuadros" );
    contiene( html, "Leer ventas.producto", "alternativa del linaje" );
    contiene( html, "VARCHAR(30)", "alternativa del ER" );
    sinUrls( html );
  }

  public void pruebaEscapes() throws Exception {
    Informe inf = ModeloMuestra.crear();
    Parrafo malo = new Parrafo( "Cierre </script><script>alert(1)</script> y <img src=x onerror=alert(2)>" );
    malo.id = "p-malo";
    inf.seccion( "summary" ).con( malo );
    inf.titulo = "Título <i>raro</i> & \"comillas\"";
    for ( RenderHtml.Modo m : RenderHtml.Modo.values() ) {
      String html = new RenderHtml( m, null, false ).html( inf, ModeloMuestra.tema() );
      noContiene( html, ModeloMuestra.STEP_RARO, m + ": el nombre raro nunca va crudo" );
      noContiene( html, "<b>", m + ": nunca se genera <b>" );
      contiene( html, "&lt;b&gt;&amp;&quot;x", m + ": el nombre raro escapado" );
      contiene( html, "a|b, &lt;i&gt;, &amp;amp;", m + ": celda con | y entidades" );
      noContiene( html, "<img src=x", m + ": nada de HTML del usuario" );
      contiene( html, "&lt;/script&gt;&lt;script&gt;alert(1)", m + ": cierre de script escapado" );
      contiene( html, "<title>Título &lt;i&gt;raro&lt;/i&gt; &amp; &quot;comillas&quot;</title>", m + ": titulo" );
      contiene( html, "<strong>Conclusión.</strong>", m + ": **negrita** de la narrativa" );
      contiene( html, "<em>lazy conversion</em>", m + ": *cursiva*" );
      contiene( html, "Texto redactado con asistencia de IA", m + ": rotulo de IA" );
      int scripts = cuenta( html.toLowerCase( Locale.ROOT ), "<script" );
      igual( m == RenderHtml.Modo.INTERACTIVO ? 2 : 0, scripts, m + ": solo nuestros scripts" );
    }
    // el texto Mermaid se escapa para el <pre>: Mermaid lo decodifica con entityDecode
    String html = new RenderHtml( RenderHtml.Modo.INTERACTIVO, null, false ).html( ModeloMuestra.crear(),
      new Tema() );
    contiene( html, "s1[&quot;Leer ventas&quot;] --&gt; s2", "flechas y comillas escapadas en el pre" );
    contiene( html, "#60;b#62;#38;#34;x", "las entidades de escaparMermaid quedan" );
  }

  public void pruebaCssIe11() throws Exception {
    String css = Html.recurso( RenderHtml.RECURSO_CSS );
    noNulo( css, "informe.css en el classpath" );
    String html = new RenderHtml( RenderHtml.Modo.ESTATICO, null, true ).html( ModeloMuestra.crear(),
      ModeloMuestra.tema() );
    String estilo = html.substring( html.indexOf( "<style>" ), html.indexOf( "</style>" ) );
    for ( String prohibido : new String[] { "var(", "display: grid;", "display:grid", "display: flex",
      "display:flex", "dominant-baseline", "{{", "inherit;" } ) {
      noContiene( estilo, prohibido, "CSS apto IE11 / sin marcas sin reemplazar" );
    }
    contiene( estilo, "#243B53", "color primario del tema" );
    contiene( estilo, "\"Segoe UI\", Arial, sans-serif", "tipografia" );
  }

  public void pruebaImprimir() throws Exception {
    Tema t = ModeloMuestra.tema();
    String html = new RenderHtml( RenderHtml.Modo.INTERACTIVO, null, true ).html( ModeloMuestra.crear(), t );
    contiene( html, "@page { size: A4;", "A4 por defecto" );
    contiene( html, "@bottom-left { content: \"Pentaho Live · IDC Studios\"", "pie literal" );
    contiene( html, "content: \"Página \" counter(page) \" de \" counter(pages)", "numero de pagina" );
    contiene( html, "break-before: page", "cada seccion en pagina nueva" );
    t.pagina = Tema.LETTER;
    contiene( new RenderHtml( RenderHtml.Modo.INTERACTIVO, null, true ).html( ModeloMuestra.crear(), t ),
      "@page { size: letter;", "Letter" );
    String pantalla = new RenderHtml( RenderHtml.Modo.INTERACTIVO, null, false ).html( ModeloMuestra.crear(), t );
    noContiene( pantalla, "@page", "sin @page si no es para imprimir" );
    // marca de agua: opacidad en el CSS del div, nunca un <g opacity> dentro del SVG (Chromium no la repite)
    int m = html.indexOf( "<div class=\"marca\"" );
    cierto( m > 0, "hay marca de agua" );
    String marca = html.substring( m, html.indexOf( "</div>", m ) );
    noContiene( marca, "opacity=", "simbolo opaco dentro de la marca" );
    contiene( html, "pointer-events: none; opacity: 0.08; }", "opacidad 0,08 en el CSS de .marca" );
  }

  public void pruebaIdiomasYOpciones() throws Exception {
    Informe inf = ModeloMuestra.crear();
    inf.rotulos.clear();
    inf.idioma = "en";
    String en = new RenderHtml( RenderHtml.Modo.ESTATICO, null, false ).html( inf, new Tema() );
    contiene( en, "<h2>Contents</h2>", "indice en ingles desde render_en" );
    contiene( en, "<html lang=\"en\">", "lang" );
    contiene( en, "October 20, 2026", "fecha larga en ingles" );
    inf.idioma = "pt";
    contiene( new RenderHtml( RenderHtml.Modo.ESTATICO, null, false ).html( inf, new Tema() ), "<h2>Sumário</h2>",
      "indice en portugues" );
    Tema sin = new Tema();
    sin.marcaDeAgua = false;
    sin.rotularIa = false;
    String html = new RenderHtml( RenderHtml.Modo.ESTATICO, null, false ).html( ModeloMuestra.crear(), sin );
    noContiene( html, "class=\"marca\"", "sin marca de agua" );
    noContiene( html, "asistencia de IA", "sin rotulo de IA" );
    // seccion omitida: no tiene cuerpo, figura en el indice con su motivo
    contiene( html, "Flujo del job (Solo para jobs)", "omitida con motivo" );
    noContiene( html, "id=\"sec-job_flow\"", "la omitida no se dibuja" );
    contiene( html, "estado estado-error", "insignia de error" );
    contiene( html, "estado estado-parcial", "insignia de parcial" );
    // un color de usuario invalido no entra al CSS
    Tema raro = new Tema();
    raro.colores.primario = "red;}</style><script>alert(1)</script>";
    String h = new RenderHtml( RenderHtml.Modo.ESTATICO, null, false ).html( ModeloMuestra.crear(), raro );
    noContiene( h.toLowerCase( Locale.ROOT ), "<script", "color invalido descartado" );
    // informe vacio y listas null desde JSON: no revienta
    String vacio = new RenderHtml( RenderHtml.Modo.INTERACTIVO, null, false ).html( new Informe(), null );
    contiene( vacio, "</html>", "informe vacio" );
    Informe nulos = io.github.ivi2511.pentahomcp.informes.modelo.ModeloJson.leer( "{\"secciones\":[{\"id\":\"cover\","
      + "\"bloques\":null},{\"id\":\"summary\",\"bloques\":null},{\"id\":\"metrics\",\"bloques\":[{\"tipo\":\"lista\","
      + "\"items\":null},{\"tipo\":\"indicadores\",\"items\":null},{\"tipo\":\"tabla\",\"columnas\":null,\"filas\":null},"
      + "{\"tipo\":\"grafico\",\"clase\":\"gantt\",\"tramos\":null,\"series\":null,\"categorias\":null},{\"tipo\":"
      + "\"diagrama\"},{\"tipo\":\"imagen\"},{\"tipo\":\"aviso\"}]}]}", Informe.class );
    for ( RenderHtml.Modo m : RenderHtml.Modo.values() ) {
      contiene( new RenderHtml( m, null, true ).html( nulos, null ), "</html>", m + ": listas null" );
    }
    new RenderMarkdown().escribir( nulos, null, new File( carpeta, "nulos.md" ), Progreso.NULO );
    // cancelacion
    Progreso cancelado = new Progreso() {
      public void paso( String texto, int hecho, int total ) {
      }

      public boolean cancelado() {
        return true;
      }
    };
    try {
      new RenderHtml( RenderHtml.Modo.ESTATICO, null, false ).escribir( ModeloMuestra.crear(), null, new File( carpeta,
        "cancelado.html" ), cancelado );
      falla( "tenia que cancelarse" );
    } catch ( java.util.concurrent.CancellationException e ) {
      falso( new File( carpeta, "cancelado.html" ).exists(), "cancelado no escribe nada" );
    }
  }

  /**
   * El parrafo que repite el resumen de la seccion no se escribe dos veces (HTML y Markdown), la fecha en
   * portugues va con el mes en minuscula y una seccion corta no abre pagina al imprimir.
   */
  public void pruebaSinRepetirYPaginas() throws Exception {
    Informe inf = new Informe();
    inf.titulo = "Prueba";
    inf.idioma = "pt";
    inf.generado = "2026-10-20T14:32:05-03:00";
    Seccion res = new Seccion( "summary", "Resumo" );
    res.resumen = "Lê 1 arquivo e grava 1 arquivo.";
    res.con( new Parrafo( "Lê 1 arquivo e grava 1 arquivo." ) );
    Seccion met = new Seccion( "metrics", "Métricas" );
    met.resumen = "A última execução processou 10 linhas.";
    met.con( new Parrafo( "A última execução processou 10 linhas.\nO step mais lento foi “Ler”." ) );
    io.github.ivi2511.pentahomcp.informes.modelo.Tabla grande = new io.github.ivi2511.pentahomcp.informes.modelo
      .Tabla( "a", "b" );
    for ( int i = 0; i < 30; i++ ) {
      grande.fila( "x" + i, "y" + i );
    }
    met.con( grande );
    Seccion con = new Seccion( "connections", "Conexões" );
    con.resumen = "Sem conexões de banco de dados nem parâmetros.";
    inf.secciones.add( res );
    inf.secciones.add( met );
    inf.secciones.add( con );
    String html = new RenderHtml( RenderHtml.Modo.ESTATICO, null, true ).html( inf, new Tema() );
    igual( 1, cuenta( html, "Lê 1 arquivo e grava 1 arquivo." ), "el resumen una sola vez" );
    igual( 1, cuenta( html, "A última execução processou 10 linhas." ), "el resumen de metricas una vez" );
    contiene( html, "O step mais lento foi “Ler”.", "lo que agrega el parrafo si sale" );
    contiene( html, "20 de outubro de 2026", "mes en minuscula en portugues" );
    noContiene( html, "Outubro", "sin mayuscula" );
    contiene( html, "<section class=\"seccion\" id=\"sec-summary\"", "la primera seccion abre pagina" );
    contiene( html, "<section class=\"seccion\" id=\"sec-metrics\"", "una seccion larga abre pagina" );
    contiene( html, "<section class=\"seccion corta\" id=\"sec-connections\"", "una corta no abre pagina" );
    contiene( html, ".seccion.corta { page-break-before: auto; break-before: auto;", "CSS de la corta al imprimir" );
    File md = new File( carpeta, "sin-repetir.md" );
    new RenderMarkdown().escribir( inf, new Tema(), md, Progreso.NULO );
    String t = leer( md );
    igual( 1, cuenta( t, "Lê 1 arquivo e grava 1 arquivo." ), "md: el resumen una sola vez" );
    igual( 1, cuenta( t, "A última execução processou 10 linhas." ), "md: el de metricas una vez" );
    contiene( t, "O step mais lento foi “Ler”.", "md: lo que agrega" );
  }

  /**
   * Una celda con una palabra muy larga (la propuesta «${Internal.Entry.Current.Directory}/…» de Calidad tecnica)
   * lleva la clase "largo" y puntos de corte (wbr) en / . _ -: sin eso la tabla se salia de la pagina del PDF y
   * Chromium recortaba la ultima columna.
   */
  public void pruebaPalabrasLargas() throws Exception {
    cierto( Html.palabraLarga( "Usar ${Internal.Entry.Current.Directory} y una ruta", Html.PALABRA_LARGA ),
      "${Internal.Entry.Current.Directory} es larga" );
    falso( Html.palabraLarga( "Poner un Sort rows por producto antes.", Html.PALABRA_LARGA ), "texto normal" );
    falso( Html.palabraLarga( null, Html.PALABRA_LARGA ), "null" );
    igual( "Propuesta: «${Internal.<wbr>Entry.<wbr>Current.<wbr>Directory}/../<wbr>datos/<wbr>a&amp;b.<wbr>csv»."
      + "<br>x", Html.escCortable( "Propuesta: «${Internal.Entry.Current.Directory}/../datos/a&b.csv».\nx" ),
      "corta en . y /, escapa, las palabras cortas quedan igual" );
    igual( "aaaaaaaaaaaaaaaaaaaa<wbr>aaaaaaaaaaaaaaaaaaaa<wbr>a", Html.escCortable( repetir( 'a', 41 ) ),
      "sin donde cortar: cada 20" );
    String html = new RenderHtml( RenderHtml.Modo.ESTATICO, null, true ).html( ModeloMuestra.crear(),
      ModeloMuestra.tema() );
    contiene( html, "<td class=\"largo\">Usar ${Internal.<wbr>Entry.<wbr>Current.<wbr>Directory}",
      "la celda de la propuesta" );
    contiene( html, "<td>Poner un Sort rows por producto antes.", "las demas celdas sin clase" );
    contiene( html, "table.datos td.largo { word-wrap: break-word; overflow-wrap: break-word; }", "el CSS" );
  }

  private static String repetir( char c, int n ) {
    StringBuilder sb = new StringBuilder();
    for ( int i = 0; i < n; i++ ) {
      sb.append( c );
    }
    return sb.toString();
  }

  public void pruebaUtilidades() {
    List<Html.Trozo> t = Html.trozos( "a **b** *c* d*e 2 * 3 **x" );
    igual( "TEXTO:a |NEGRITA:b|TEXTO: |CURSIVA:c|TEXTO: d*e 2 * 3 **x", texto( t ), "trozos" );
    igual( "&lt;b&gt;&amp;&quot;x", Html.esc( ModeloMuestra.STEP_RARO ), "esc" );
    igual( "linaje-de-campos", Html.idSeguro( "Linaje de campos" ), "idSeguro" );
    igual( "accion-n", Html.idSeguro( "Acción ñ" ), "idSeguro sin acentos" );
    igual( "#E9EBEE", Html.claro( "#243B53", 0.10f ), "10 % del primario" );
    cierto( RenderHtml.claro( "#243B53", 0.10f ).matches( "#[0-9A-F]{6}" ), "Paleta.claro o respaldo" );
    String svg = Html.svgInline( "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"100%\" style=\"max-width: 1260px;\""
      + " viewBox=\"0 0 1260.77 164\"><g/></svg>", 840 );
    contiene( svg, "width=\"840\" height=\"109.3\"", "alto desde el viewBox, achicado" );
    noContiene( svg, "max-width", "sin el max-width inline de Mermaid (le ganaba al CSS en IE11)" );
    noContiene( svg, "style=", "style vacio sacado" );
    contiene( Html.svgInline( "<svg viewBox=\"0 0 256.6 372\"></svg>", 840 ), "width=\"256.6\" height=\"372\"",
      "sin achicar" );
    igual( "Texto \\\"x\\\" \\\\ \\3C /style", Html.cadenaCss( "Texto \"x\" \\ </style" ), "cadena CSS" );
  }

  /**
   * Con Edge headless: el HTML interactivo de ModeloMuestra dibuja los 3 diagramas Mermaid (prueba que el
   * bundle con ":\/\/" sigue andando) y quedan capturas de los dos modos para mirarlas.
   */
  public void pruebaEdge() throws Exception {
    File edge = edge();
    if ( edge == null ) {
      saltear( "no hay Edge ni Chrome" );
    }
    File html = new File( carpeta, "muestra.html" );
    new RenderHtml( RenderHtml.Modo.INTERACTIVO, null, false ).escribir( ModeloMuestra.crear(), ModeloMuestra.tema(),
      html, Progreso.NULO );
    File dom = new File( carpeta, "muestra.dom.html" );
    correr( edge, dom, "--dump-dom", "--virtual-time-budget=15000", html.toURI().toString() );
    String d = leer( dom );
    contiene( d, "data-mermaid=\"listo\"", "Mermaid termino" );
    d = d.substring( 0, d.indexOf( "<script>" ) ); // el cuerpo, sin el bundle (que trae sus propios textos)
    Matcher m = Pattern.compile( "<pre class=\"mermaid\"[^>]*data-processed=\"true\"[^>]*>\\s*<svg" ).matcher( d );
    int dibujados = 0;
    while ( m.find() ) {
      dibujados++;
    }
    igual( 3, dibujados, "los 3 diagramas dibujados por Mermaid" );
    noContiene( d, "Syntax error", "sin errores de sintaxis de Mermaid" );
    contiene( d, "Leer ventas", "rotulos dentro del SVG" );
    rotuloRaro( d, "m-flow-diagrama", "interactivo" );

    // config del pre-render IE11 (htmlLabels:false + arreglo): el nodo con el nombre raro tambien sale bien
    Diagrama flujo = ModeloMuestra.diagramas( ModeloMuestra.crear() ).get( 0 );
    File ie = new File( carpeta, "mermaid-ie.html" );
    Html.escribir( ie, "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body><pre class=\"mermaid\" id=\"m\">"
      + Html.esc( flujo.mermaid ) + "</pre><script>" + RenderHtml.mermaidJs() + "</script><script>" + RenderHtml
        .scriptMermaid( ModeloMuestra.tema(), true ) + "</script></body></html>" );
    File domIe = new File( carpeta, "mermaid-ie.dom.html" );
    correr( edge, domIe, "--dump-dom", "--virtual-time-budget=15000", ie.toURI().toString() );
    String di = leer( domIe );
    contiene( di, "data-mermaid=\"listo\"", "pre-render IE: Mermaid termino" );
    di = di.substring( 0, di.indexOf( "<script>" ) );
    noContiene( di, "<foreignObject", "pre-render IE: sin foreignObject" );
    rotuloRaro( di, "m", "pre-render IE" );

    correr( edge, null, "--screenshot=" + new File( carpeta, "muestra.png" ).getAbsolutePath(),
      "--window-size=1100,7000", "--hide-scrollbars", "--virtual-time-budget=15000", html.toURI().toString() );
    archivo( new File( carpeta, "muestra.png" ), 20_000, "captura del interactivo" );
    File est = new File( carpeta, "muestra-estatico.html" );
    Informe inf = ModeloMuestra.crear();
    new RenderHtml( RenderHtml.Modo.ESTATICO, ModeloMuestra.svgPrerender( inf ), false ).escribir( inf,
      ModeloMuestra.tema(), est, Progreso.NULO );
    correr( edge, null, "--screenshot=" + new File( carpeta, "muestra-estatico.png" ).getAbsolutePath(),
      "--window-size=1100,7000", "--hide-scrollbars", est.toURI().toString() );
    archivo( new File( carpeta, "muestra-estatico.png" ), 20_000, "captura del estatico" );

    // el CSS de impresion con Edge: PDF valido (la paginacion y la marca en cada pagina se miraron a mano)
    File imp = new File( carpeta, "muestra-imprimir.html" );
    new RenderHtml( RenderHtml.Modo.INTERACTIVO, null, true ).escribir( ModeloMuestra.crear(), ModeloMuestra.tema(),
      imp, Progreso.NULO );
    File pdf = new File( carpeta, "muestra-imprimir.pdf" );
    pdf.delete();
    correr( edge, null, "--no-pdf-header-footer", "--virtual-time-budget=15000", "--print-to-pdf=" + pdf
      .getAbsolutePath(), imp.toURI().toString() );
    archivo( pdf, 50_000, "PDF de la version para imprimir" );
    byte[] cab = new byte[ 4 ];
    try ( java.io.InputStream in = new java.io.FileInputStream( pdf ) ) {
      igual( 4, in.read( cab ), "cabecera" );
    }
    igual( "%PDF", new String( cab, StandardCharsets.US_ASCII ), "empieza con %PDF" );
  }

  // ---------------------------------------------------------------- ayudas

  /** En el SVG que Mermaid dejo dentro del pre con ese id, el step {@code <b>&"x} se ve decodificado. */
  private static void rotuloRaro( String dom, String idPre, String donde ) {
    int i = dom.indexOf( "id=\"" + idPre + "\"" );
    cierto( i >= 0, donde + ": esta el pre " + idPre );
    String svg = dom.substring( i, dom.indexOf( "</pre>", i ) );
    contiene( svg, "<svg", donde + ": dibujado" );
    contiene( svg, "&lt;b&gt;&amp;\"x", donde + ": el nombre raro se ve como <b>&\"x" );
    noContiene( svg, "#60;", donde + ": sin codigos de escaparMermaid a la vista" );
    noContiene( svg, "&amp;#", donde + ": sin entidades sin decodificar" );
    noContiene( svg, "ﬂ°", donde + ": sin marcadores internos de Mermaid" );
  }

  /** Falla si hay un http(s):// que no sea el valor de un xmlns. */
  static void sinUrls( String html ) {
    Matcher m = URL.matcher( html );
    List<String> malas = new ArrayList<>();
    while ( m.find() ) {
      String antes = html.substring( Math.max( 0, m.start() - 40 ), m.start() );
      if ( !XMLNS.matcher( antes ).find() ) {
        malas.add( html.substring( Math.max( 0, m.start() - 40 ), Math.min( html.length(), m.end() + 40 ) ) );
      }
    }
    cierto( malas.isEmpty(), "URLs que no son xmlns: " + malas.size() + ( malas.isEmpty() ? "" : " -> " + malas.get(
      0 ) ) );
  }

  static int cuenta( String texto, String parte ) {
    int n = 0;
    int i = texto.indexOf( parte );
    while ( i >= 0 ) {
      n++;
      i = texto.indexOf( parte, i + parte.length() );
    }
    return n;
  }

  static String leer( File f ) throws Exception {
    return new String( Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 );
  }

  private static String texto( List<Html.Trozo> l ) {
    StringBuilder sb = new StringBuilder();
    for ( Html.Trozo t : l ) {
      sb.append( sb.length() == 0 ? "" : "|" ).append( t.estilo ).append( ':' ).append( t.texto );
    }
    return sb.toString();
  }

  /** msedge.exe o chrome.exe en las rutas de siempre (la busqueda completa es de WP8, Chromium.ubicar). */
  static File edge() {
    String[] bases = { System.getenv( "ProgramFiles(x86)" ), System.getenv( "ProgramFiles" ), System.getenv(
      "LOCALAPPDATA" ) };
    String[] rel = { "Microsoft\\Edge\\Application\\msedge.exe", "Google\\Chrome\\Application\\chrome.exe" };
    for ( String r : rel ) {
      for ( String b : bases ) {
        if ( b != null && new File( b, r ).isFile() ) {
          return new File( b, r );
        }
      }
    }
    return null;
  }

  /**
   * Corre el navegador headless con la maquinaria del plugin (Chromium.ejecutar: perfil temporal unico,
   * hijos y carpetas borrados al terminar); stdout a {@code salida} si se pide. Antes cada corrida dejaba
   * su perfil plive-wp7-* en %TEMP%: los hijos de Edge sueltan los archivos despues de que sale el padre.
   */
  static void correr( File exe, File salida, String... args ) throws Exception {
    io.github.ivi2511.pentahomcp.informes.navegador.Chromium.Corrida c =
      io.github.ivi2511.pentahomcp.informes.navegador.Chromium.ejecutar( exe, java.util.Arrays.asList( args ), 90000,
        salida != null, null );
    if ( salida != null ) {
      Files.write( salida.toPath(), c.salida == null ? new byte[ 0 ] : c.salida );
    }
  }
}
