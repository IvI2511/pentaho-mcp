package io.github.ivi2511.pentahomcp.informes.render;

import java.io.File;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Renderizador;
import io.github.ivi2511.pentahomcp.informes.Resultado;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.diagramas.Diagramas;
import io.github.ivi2511.pentahomcp.informes.diagramas.Dibujo;
import io.github.ivi2511.pentahomcp.informes.diagramas.Objetivo;
import io.github.ivi2511.pentahomcp.informes.graficos.GraficosSvg;
import io.github.ivi2511.pentahomcp.informes.modelo.Aviso;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.Grafico;
import io.github.ivi2511.pentahomcp.informes.modelo.Imagen;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicador;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicadores;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.Lista;
import io.github.ivi2511.pentahomcp.informes.modelo.Parrafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Serie;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;
import io.github.ivi2511.pentahomcp.informes.modelo.Tramo;
import io.github.ivi2511.pentahomcp.informes.tema.Paleta;
import io.github.ivi2511.pentahomcp.informes.tema.SimboloIdc;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * HTML de un solo archivo, autocontenido y offline (diseno §3.1). Dos modos con el mismo codigo y el
 * mismo CSS (apto IE11):
 * <ul>
 * <li>{@link Modo#INTERACTIVO}: el bundle de Mermaid 11.4.1 (unos 2,6 MB) solo si queda algun diagrama
 * Mermaid sin grafo propio (el modelo entidad-relacion). No hace ninguna peticion de red.</li>
 * <li>{@link Modo#ESTATICO}: vista previa IE11 y "HTML liviano". Sin ningun &lt;script&gt;: los diagramas
 * Mermaid van como SVG pre-renderizado (PrerenderMermaid.svg); si falta, un recuadro y la tabla alternativa.</li>
 * </ul>
 * Los diagramas con grafo (flujo, job, linaje) se dibujan con el motor propio (informes.diagramas) como SVG
 * inline en los dos modos: para la pantalla con la caja {@link Objetivo#PANTALLA} y para imprimir con la
 * hoja A4 o carta, partidos en varias figuras si la letra quedaria chica.
 * El archivo no tiene ninguna URL http(s) salvo los xmlns de los SVG: el bundle de Mermaid se mete con
 * "://" escrito como ":\/\/", que en JavaScript es la misma cadena (solo aparece en textos de error,
 * comentarios de licencia y espacios de nombres).
 *
 * @dueño WP7
 */
public final class RenderHtml implements Renderizador {

  public enum Modo {
    INTERACTIVO, ESTATICO
  }

  /** Area de Rotulos de los renderizadores HTML y Markdown. */
  public static final String AREA = "render";
  /** Mermaid 11.4.1 (MIT), byte a byte el dist/mermaid.min.js de npm. */
  public static final String RECURSO_MERMAID = "io/github/ivi2511/pentahomcp/informes/vendor/mermaid.min.js";
  public static final String RECURSO_CSS = "io/github/ivi2511/pentahomcp/informes/render/informe.css";
  /** Ancho en px que se le pide a GraficosSvg. */
  public static final int ANCHO_GRAFICO = 760;

  private static final String MARCA_IMPRIMIR = "/*==IMPRIMIR==*/";
  private static volatile String mermaidJs;

  private final Modo modo;
  private final Map<String, String> svgPrerender;
  private final boolean paraImprimir;

  /**
   * @param svgPrerender id de Diagrama -> SVG ya renderizado (PrerenderMermaid.svg); puede ser null. En
   *                     modo INTERACTIVO no se usa.
   * @param paraImprimir true = @page A4/Letter, saltos de pagina y numeros (lo usa RenderPdf).
   */
  public RenderHtml( Modo modo, Map<String, String> svgPrerender, boolean paraImprimir ) {
    this.modo = modo == null ? Modo.INTERACTIVO : modo;
    this.svgPrerender = svgPrerender == null ? Collections.<String, String>emptyMap() : svgPrerender;
    this.paraImprimir = paraImprimir;
  }

  public Modo modo() {
    return modo;
  }

  @Override
  public Formato formato() {
    return modo == Modo.ESTATICO ? Formato.HTML_ESTATICO : Formato.HTML;
  }

  @Override
  public Resultado escribir( Informe inf, Tema tema, File destino, Progreso p ) throws Exception {
    Resultado r = new Resultado();
    String html = html( inf, tema, p, r.avisos );
    Html.escribir( destino, html );
    return r.archivo( destino );
  }

  /**
   * Alto maximo (mm) de una figura impresa: el alto util de la pagina (A4 297 mm o Letter 279 mm, menos
   * los margenes de 18 y 20 mm de @page) menos ~34 mm para el titulo del bloque, el marco y la nota. Un
   * diagrama o una imagen mas alta quedaba entera en la pagina siguiente y dejaba paginas en blanco.
   */
  public static int altoFiguraMm( Tema tema ) {
    boolean carta = tema != null && Tema.LETTER.equalsIgnoreCase( tema.pagina );
    return carta ? 207 : 225;
  }

  /** Alto util de la hoja impresa en px CSS: A4 297 mm o Letter 279,4 mm menos los margenes de 18 y 20 mm. */
  static double altoHojaPx( Tema tema ) {
    boolean carta = tema != null && Tema.LETTER.equalsIgnoreCase( tema.pagina );
    return ( ( carta ? 279.4 : 297 ) - 38 ) / 25.4 * 96;
  }

  /** La caja de los diagramas propios: la hoja (A4 o carta) para imprimir, la pantalla si no. */
  Objetivo objetivo( Tema tema ) {
    if ( !paraImprimir ) {
      return Objetivo.PANTALLA;
    }
    return tema != null && Tema.LETTER.equalsIgnoreCase( tema.pagina ) ? Objetivo.LETTER : Objetivo.A4;
  }

  /** El HTML completo como texto (lo usan las pruebas y quien necesite el texto sin archivo). */
  public String html( Informe inf, Tema tema ) {
    return html( inf, tema, Progreso.NULO, new ArrayList<String>() );
  }

  private String html( Informe inf, Tema tema, Progreso p, List<String> avisos ) {
    Escritor e = new Escritor( inf == null ? new Informe() : inf, tema == null ? new Tema() : tema,
      p == null ? Progreso.NULO : p, avisos );
    return e.documento();
  }

  // ---------------------------------------------------------------- Mermaid compartido (WP8 lo reusa)

  /**
   * El bundle de Mermaid listo para ir dentro de un &lt;script&gt; inline: "://" escrito como ":\/\/"
   * (misma cadena en JS) y ningun "&lt;/script". null si el recurso no esta en el jar.
   */
  public static String mermaidJs() {
    String js = mermaidJs;
    if ( js == null ) {
      String crudo = Html.recurso( RECURSO_MERMAID );
      if ( crudo == null ) {
        return null;
      }
      js = crudo.replace( "://", ":\\/\\/" ).replaceAll( "(?i)</(script)", "<\\\\/$1" );
      mermaidJs = js;
    }
    return js;
  }

  /**
   * La configuracion de Mermaid de §3.1 como literal de objeto JS (tema, colores, strict).
   * <p>
   * <b>Cambio respecto del diseno (probado en Edge con Mermaid 11.4.1):</b> con htmlLabels:false los
   * rotulos de los NODOS de un flowchart muestran los codigos de escaparMermaid tal cual ("&amp;#60;b&amp;#62;"
   * en vez de "&lt;b&gt;"), y los titulos de subgraph y algunas flechas quedan con basura ("ﬂ°°34;"). Con
   * htmlLabels:true (el default de Mermaid) todo sale bien. Por eso:
   * <ul>
   * <li>{@code paraIe = false} (HTML interactivo y PDF): htmlLabels:true.</li>
   * <li>{@code paraIe = true} (pre-render para la vista previa IE11): htmlLabels:false, que IE necesita porque
   * no dibuja foreignObject; {@link #scriptMermaid} arregla despues los rotulos de los nodos.</li>
   * </ul>
   */
  public static String configMermaid( Tema tema, boolean paraIe ) {
    Tema t = tema == null ? new Tema() : tema;
    String primario = Html.color( t.colores == null ? null : t.colores.primario, Tema.PRIMARIO );
    String rotulos = paraIe ? "false" : "true";
    return "{startOnLoad:false,securityLevel:'strict',theme:'base',htmlLabels:" + rotulos + ",flowchart:{htmlLabels:"
      + rotulos + "},class:{htmlLabels:" + rotulos + "},suppressErrorRendering:true,"
      + "themeVariables:{fontFamily:'Segoe UI, Arial, sans-serif',primaryColor:'" + claro( primario, 0.10f )
      + "',primaryBorderColor:'" + primario + "',lineColor:'" + Tema.TEXTO_TENUE + "',primaryTextColor:'"
      + Tema.TEXTO + "'}}";
  }

  /**
   * Script de arranque (ES5, sin el bundle) que dibuja todos los {@code pre.mermaid} de la pagina con
   * {@link #configMermaid}. Al terminar deja {@code <html data-mermaid="listo|error|sin-mermaid">} (sirve
   * para esperar el dibujo) y, si un diagrama no se pudo dibujar y tiene {@code data-alt="<id>"}, muestra
   * ese elemento (la tabla alternativa) y oculta el pre. Con {@code paraIe} ademas decodifica las entidades
   * que Mermaid deja en los rotulos de los nodos con htmlLabels:false. Lo puede usar PrerenderMermaid (WP8).
   */
  public static String scriptMermaid( Tema tema, boolean paraIe ) {
    StringBuilder sb = new StringBuilder();
    sb.append( "(function () {\n" );
    sb.append( "  var raiz = document.documentElement;\n" );
    if ( paraIe ) {
      sb.append( "  function arreglar() {\n" );
      sb.append( "    var re = /&(#(\\d+)|#x([0-9a-fA-F]+)|lt|gt|amp|quot|apos);/g;\n" );
      sb.append( "    var mapa = { lt: '<', gt: '>', amp: '&', quot: '\"', apos: \"'\" };\n" );
      sb.append( "    var ts = document.querySelectorAll('pre.mermaid svg[aria-roledescription^=\"flowchart\"] g.node text');\n" );
      sb.append( "    for (var i = 0; i < ts.length; i++) {\n" );
      sb.append( "      var w = document.createTreeWalker(ts[i], 4, null, false), n;\n" );
      sb.append( "      while ((n = w.nextNode())) {\n" );
      sb.append( "        if (n.nodeValue.indexOf('&') < 0) { continue; }\n" );
      sb.append( "        n.nodeValue = n.nodeValue.replace(re, function (m, e, d, h) {\n" );
      sb.append( "          return d ? String.fromCharCode(+d) : h ? String.fromCharCode(parseInt(h, 16)) : mapa[e];\n" );
      sb.append( "        });\n" );
      sb.append( "      }\n    }\n  }\n" );
    }
    sb.append( "  function respaldo() {\n" );
    sb.append( "    var pres = document.querySelectorAll('pre.mermaid');\n" );
    sb.append( "    for (var i = 0; i < pres.length; i++) {\n" );
    sb.append( "      if (pres[i].getElementsByTagName('svg').length > 0) { continue; }\n" );
    sb.append( "      var alt = document.getElementById(pres[i].getAttribute('data-alt') || '');\n" );
    sb.append( "      if (alt) { alt.className = 'alternativa'; pres[i].className += ' mermaid-fallo'; }\n" );
    sb.append( "    }\n  }\n" );
    sb.append( "  function fin(estado) {\n" );
    if ( paraIe ) {
      sb.append( "    try { arreglar(); } catch (e) { }\n" );
    }
    sb.append( "    respaldo(); raiz.setAttribute('data-mermaid', estado);\n  }\n" );
    sb.append( "  var m = window.mermaid;\n" );
    sb.append( "  if (!m || !document.querySelector('pre.mermaid')) { fin(m ? 'listo' : 'sin-mermaid'); return; }\n" );
    sb.append( "  try {\n" );
    sb.append( "    m.initialize(" ).append( configMermaid( tema, paraIe ) ).append( ");\n" );
    sb.append( "    m.run({ querySelector: 'pre.mermaid', suppressErrors: true }).then(function () { fin('listo'); },"
      + " function () { fin('error'); });\n" );
    sb.append( "  } catch (e) { fin('error'); }\n" );
    sb.append( "})();\n" );
    return sb.toString();
  }

  /** Paleta.claro (WP6) con respaldo local; siempre "#RRGGBB". */
  static String claro( String hex, float fraccion ) {
    try {
      String c = Paleta.claro( hex, fraccion );
      if ( c != null && c.matches( "#[0-9A-Fa-f]{6}" ) ) {
        return c.toUpperCase( Locale.ROOT );
      }
    } catch ( Throwable e ) {
      // WP6 todavia no esta o fallo: respaldo local
    }
    return Html.claro( hex, fraccion );
  }

  /** Luminancia relativa aproximada (0 negro, 1 blanco) de "#RRGGBB". */
  static double luminancia( String hex ) {
    String h = Html.color( hex, "#000000" );
    double r = Integer.parseInt( h.substring( 1, 3 ), 16 ) / 255.0;
    double g = Integer.parseInt( h.substring( 3, 5 ), 16 ) / 255.0;
    double b = Integer.parseInt( h.substring( 5, 7 ), 16 ) / 255.0;
    return 0.2126 * lin( r ) + 0.7152 * lin( g ) + 0.0722 * lin( b );
  }

  private static double lin( double c ) {
    return c <= 0.03928 ? c / 12.92 : Math.pow( ( c + 0.055 ) / 1.055, 2.4 );
  }

  /** Texto traducido: primero Informe.rotulos (los llena InformeServicio) y si no, el area "render". */
  static String texto( Informe inf, Rotulos r, String clave ) {
    if ( inf != null && inf.rotulos != null ) {
      String v = inf.rotulos.get( clave );
      if ( v != null && !v.trim().isEmpty() ) {
        return v;
      }
    }
    return r.s( clave );
  }

  /**
   * Fecha ISO-8601 del informe en formato largo del idioma ("20 de octubre de 2026, 14:32", "October 20,
   * 2026, 14:32"). Con patron propio y no FormatStyle.LONG: con los datos COMPAT de Spoon el portugues
   * sale "20 de Outubro de 2026", y la ortografia actual escribe los meses en minuscula.
   */
  static String fechaLarga( String iso, Locale loc ) {
    if ( iso == null || iso.trim().isEmpty() ) {
      return null;
    }
    try {
      TemporalAccessor ta = DateTimeFormatter.ISO_DATE_TIME.parse( iso.trim() );
      LocalDateTime t = LocalDateTime.from( ta );
      return fechaLarga( t, loc ) + ", " + t.format( DateTimeFormatter.ofPattern( "HH:mm" ) );
    } catch ( RuntimeException e ) {
      return iso.trim();
    }
  }

  /** "20 de octubre de 2026" (es, pt: mes en minuscula) o "October 20, 2026" (en). */
  public static String fechaLarga( TemporalAccessor t, Locale loc ) {
    Locale l = loc == null ? Locale.US : loc;
    String idioma = l.getLanguage();
    if ( "es".equals( idioma ) || "pt".equals( idioma ) ) {
      return DateTimeFormatter.ofPattern( "d 'de' MMMM 'de' yyyy", l ).format( t ).toLowerCase( l );
    }
    return DateTimeFormatter.ofPattern( "MMMM d, yyyy", l ).format( t );
  }

  /** Las secciones en el orden de SeccionId.ORDEN (estable; ids desconocidos al final). */
  static List<Seccion> ordenadas( Informe inf ) {
    List<Seccion> l = new ArrayList<>();
    if ( inf.secciones != null ) {
      for ( Seccion s : inf.secciones ) {
        if ( s != null ) {
          l.add( s );
        }
      }
    }
    Collections.sort( l, ( a, b ) -> Integer.compare( orden( a.id ), orden( b.id ) ) );
    return l;
  }

  private static int orden( String id ) {
    int i = SeccionId.ORDEN.indexOf( id );
    return i < 0 ? SeccionId.ORDEN.size() : i;
  }

  /** Tabla con los datos de un grafico, para cuando no se puede dibujar. */
  static Tabla tablaDeGrafico( Grafico g, Rotulos r, Locale loc ) {
    NumberFormat nf = NumberFormat.getNumberInstance( loc );
    nf.setMaximumFractionDigits( 2 );
    Tabla t;
    if ( Grafico.GANTT.equals( g.clase ) ) {
      t = new Tabla( r.s( "category" ), r.s( "phase" ), r.s( "start_ms" ), r.s( "end_ms" ) );
      for ( Tramo tr : Html.lista( g.tramos ) ) {
        if ( tr != null ) {
          t.fila( tr.categoria, tr.tipo, nf.format( tr.inicioMs ), nf.format( tr.finMs ) );
        }
      }
      t.alineacion = new ArrayList<>( java.util.Arrays.asList( "l", "l", "r", "r" ) );
      return t;
    }
    t = new Tabla( r.s( "category" ) );
    t.alineacion = new ArrayList<>();
    t.alineacion.add( "l" );
    for ( Serie s : Html.lista( g.series ) ) {
      t.columnas.add( s == null || s.nombre == null ? r.s( "series" ) : s.nombre );
      t.alineacion.add( "r" );
    }
    for ( int i = 0; i < Html.lista( g.categorias ).size(); i++ ) {
      List<String> fila = new ArrayList<>();
      fila.add( Html.lista( g.categorias ).get( i ) );
      for ( Serie s : Html.lista( g.series ) ) {
        Double v = s == null || s.valores == null || i >= s.valores.size() ? null : s.valores.get( i );
        fila.add( v == null ? "" : nf.format( v ) );
      }
      t.filas.add( fila );
    }
    return t;
  }

  // ---------------------------------------------------------------- el documento

  /** Estado de una escritura: un Escritor por llamada (el renderizador no guarda estado mutable). */
  private final class Escritor {
    final Informe inf;
    final Tema tema;
    final Progreso p;
    final List<String> avisos;
    final Rotulos r;
    final Locale loc;
    final StringBuilder sb = new StringBuilder( 64 * 1024 );
    final Set<String> ids = new HashSet<>();
    final String primario;
    final String acento;
    final String secundario;
    /** Algun diagrama quedo como pre.mermaid: el HTML interactivo lleva el bundle. */
    boolean hayMermaid;
    /**
     * Para imprimir: alto estimado (px) de lo que va desde el principio de la pagina de la seccion actual, o
     * NaN si ya no se sabe (despues de una figura o de una tabla larga).
     */
    double usado = Double.NaN;

    Escritor( Informe inf, Tema tema, Progreso p, List<String> avisos ) {
      this.inf = inf;
      this.tema = tema;
      this.p = p;
      this.avisos = avisos;
      String idioma = Rotulos.normalizar( inf.idioma != null ? inf.idioma : tema.idioma );
      this.r = Rotulos.de( AREA, idioma );
      this.loc = Rotulos.locale( idioma );
      Tema.Colores c = tema.colores == null ? new Tema.Colores() : tema.colores;
      this.primario = Html.color( c.primario, Tema.PRIMARIO );
      this.acento = Html.color( c.acento, Tema.ACENTO );
      this.secundario = Html.color( c.secundario, Tema.SECUNDARIO );
    }

    String t( String clave ) {
      return texto( inf, r, clave );
    }

    String documento() {
      String idioma = Rotulos.normalizar( inf.idioma != null ? inf.idioma : tema.idioma );
      String titulo = titulo();
      sb.append( "<!DOCTYPE html>\n<html lang=\"" ).append( idioma ).append( "\">\n<head>\n" );
      sb.append( "<meta charset=\"utf-8\">\n" );
      sb.append( "<meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\">\n" );
      sb.append( "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" );
      String v = Html.version();
      sb.append( "<meta name=\"generator\" content=\"" ).append( Html.esc( "Pentaho Live" + ( v == null ? "" : " "
        + v ) ) ).append( "\">\n" );
      sb.append( "<title>" ).append( Html.esc( titulo ) ).append( "</title>\n" );
      sb.append( "<style>\n" ).append( css() ).append( "</style>\n</head>\n<body>\n" );
      if ( tema.marcaDeAgua ) {
        // simbolo opaco y la opacidad 0,08 en el CSS del div: con un <g opacity> dentro del SVG, Chromium
        // imprime la marca (position:fixed) solo en la primera pagina del PDF (probado en Edge headless)
        String marca = simbolo( 1f );
        if ( marca != null ) {
          sb.append( "<div class=\"marca\" aria-hidden=\"true\">" ).append( marca ).append( "</div>\n" );
        }
      }
      sb.append( "<div class=\"hoja\">\n" );
      List<Seccion> secciones = ordenadas( inf );
      portada( secciones );
      indice( secciones );
      avisosGenerales();
      int n = secciones.size();
      int i = 0;
      boolean primera = true;
      for ( Seccion s : secciones ) {
        if ( p.cancelado() ) {
          throw new CancellationException();
        }
        p.paso( t( "writing_html" ), ++i, n );
        if ( SeccionId.PORTADA.equals( s.id ) || Seccion.OMITIDA.equals( s.estado ) ) {
          continue;
        }
        // en el PDF, una seccion corta despues de otra no abre pagina (validacion, conexiones...)
        seccion( s, !primera && Html.corta( s ) );
        primera = false;
      }
      pie();
      sb.append( "</div>\n" );
      if ( modo == Modo.INTERACTIVO ) {
        scripts();
      }
      sb.append( "</body>\n</html>\n" );
      return sb.toString();
    }

    String titulo() {
      if ( inf.titulo != null && !inf.titulo.trim().isEmpty() ) {
        return inf.titulo.trim();
      }
      if ( inf.nombreDoc != null && !inf.nombreDoc.trim().isEmpty() ) {
        return inf.nombreDoc.trim();
      }
      return t( "report" );
    }

    // ------------------------------------------------------------ CSS

    String css() {
      String base = Html.recurso( RECURSO_CSS );
      if ( base == null ) {
        base = "body{font-family:Arial,sans-serif}" + MARCA_IMPRIMIR;
      }
      int corte = base.indexOf( MARCA_IMPRIMIR );
      String pantalla = corte < 0 ? base : base.substring( 0, corte );
      String imprimir = corte < 0 ? "" : base.substring( corte + MARCA_IMPRIMIR.length() );
      // sin comentarios: son para quien edita el .css, no para el informe
      String css = ( paraImprimir ? pantalla + imprimir : pantalla ).replaceAll( "(?s)/\\*.*?\\*/", "" ).replaceAll(
        "\\n\\s*\\n+", "\n" ).trim() + "\n";
      boolean bandaOscura = luminancia( primario ) < 0.40;
      Map<String, String> m = new HashMap<>();
      m.put( "texto", Tema.TEXTO );
      m.put( "tenue", Tema.TEXTO_TENUE );
      m.put( "borde", Tema.BORDE );
      m.put( "fuente", Tema.FUENTE );
      m.put( "fuenteCodigo", Tema.FUENTE_CODIGO );
      m.put( "primario", primario );
      m.put( "acento", acento );
      m.put( "secundario", secundario );
      m.put( "fondo", claro( primario, 0.10f ) );
      m.put( "fondoTenue", claro( primario, 0.05f ) );
      m.put( "cabecera", claro( primario, 0.08f ) );
      m.put( "acento10", claro( acento, 0.10f ) );
      m.put( "acento40", claro( acento, 0.40f ) );
      m.put( "textoBanda", bandaOscura ? "#FFFFFF" : Tema.TEXTO );
      m.put( "suaveBanda", bandaOscura ? claro( primario, 0.18f ) : Tema.TEXTO );
      m.put( "tenueBanda", bandaOscura ? claro( primario, 0.42f ) : Tema.TEXTO_TENUE );
      m.put( "pagina", Tema.LETTER.equalsIgnoreCase( tema.pagina ) ? "letter" : "A4" );
      m.put( "altoFigura", altoFiguraMm( tema ) + "mm" );
      m.put( "pie", Html.cadenaCss( Tema.PIE ) );
      m.put( "pagina_txt", Html.cadenaCss( t( "page" ) ) );
      m.put( "de_txt", Html.cadenaCss( t( "of" ) ) );
      StringBuilder out = new StringBuilder( css.length() + 256 );
      int i = 0;
      while ( true ) {
        int a = css.indexOf( "{{", i );
        if ( a < 0 ) {
          out.append( css, i, css.length() );
          break;
        }
        int b = css.indexOf( "}}", a );
        if ( b < 0 ) {
          out.append( css, i, css.length() );
          break;
        }
        out.append( css, i, a );
        String clave = css.substring( a + 2, b );
        String valor = m.get( clave );
        out.append( valor == null ? "inherit" : valor );
        i = b + 2;
      }
      return out.toString();
    }

    // ------------------------------------------------------------ portada, indice, avisos y pie

    void portada( List<Seccion> secciones ) {
      Seccion cover = null;
      for ( Seccion s : secciones ) {
        if ( SeccionId.PORTADA.equals( s.id ) ) {
          cover = s;
          break;
        }
      }
      sb.append( "<div class=\"portada\" id=\"" ).append( id( "sec-" + SeccionId.PORTADA ) ).append( "\">\n" );
      sb.append( "<div class=\"banda\">\n" );
      Imagen logo = null;
      if ( cover != null ) {
        for ( Bloque b : Html.lista( cover.bloques ) ) {
          if ( b instanceof Imagen && ( (Imagen) b ).datos != null ) {
            logo = (Imagen) b;
            break;
          }
        }
      }
      if ( logo == null && tema.logoDatos != null && cover != null ) {
        logo = new Imagen( tema.logoMime, tema.logoDatos, 0, 0, 1f );
      }
      if ( logo != null ) {
        sb.append( "<div class=\"logo\"><img src=\"" ).append( Html.dataUri( logo.mime, logo.datos ) ).append(
          "\" alt=\"" ).append( Html.esc( logo.alt == null ? "" : logo.alt ) ).append( "\"></div>\n" );
      }
      String tipo = Informe.JOB.equals( inf.tipoDoc ) ? t( "doc_job" )
        : Informe.TRANSFORMACION.equals( inf.tipoDoc ) ? t( "doc_transformation" ) : null;
      if ( tipo != null || inf.nombreDoc != null ) {
        sb.append( "<p class=\"tipo\">" ).append( Html.esc( tipo == null ? "" : tipo ) );
        if ( tipo != null && inf.nombreDoc != null ) {
          sb.append( " &middot; " );
        }
        sb.append( Html.esc( inf.nombreDoc == null ? "" : inf.nombreDoc ) ).append( "</p>\n" );
      }
      sb.append( "<h1>" ).append( Html.esc( titulo() ) ).append( "</h1>\n" );
      if ( inf.subtitulo != null && !inf.subtitulo.trim().isEmpty() ) {
        sb.append( "<p class=\"subtitulo\">" ).append( Html.esc( inf.subtitulo ) ).append( "</p>\n" );
      }
      String fecha = fechaLarga( inf.generado, loc );
      if ( fecha != null ) {
        sb.append( "<p class=\"generado\">" ).append( Html.esc( r.f( "generated_on", fecha ) ) ).append( "</p>\n" );
      }
      sb.append( "</div>\n" );
      boolean hayResumen = cover != null && cover.resumen != null && !cover.resumen.trim().isEmpty();
      boolean hayBloques = false;
      if ( cover != null ) {
        for ( Bloque b : Html.lista( cover.bloques ) ) {
          hayBloques |= b != null && b != logo;
        }
      }
      if ( hayResumen || hayBloques ) {
        sb.append( "<div class=\"cuerpo\">\n" );
        if ( hayResumen ) {
          sb.append( Html.parrafos( cover.resumen, "resumen-portada" ) );
        }
        for ( Bloque b : Html.lista( cover.bloques ) ) {
          if ( b == null || b == logo ) {
            continue;
          }
          if ( b instanceof Lista && b.titulo == null ) {
            sb.append( "<ul class=\"datos\">" );
            for ( String it : Html.lista( ( (Lista) b ).items ) ) {
              sb.append( "<li>" ).append( Html.enLinea( it ) ).append( "</li>" );
            }
            sb.append( "</ul>\n" );
          } else {
            bloque( b );
          }
        }
        sb.append( "</div>\n" );
      }
      sb.append( "</div>\n" );
    }

    void indice( List<Seccion> secciones ) {
      List<Seccion> vivas = new ArrayList<>();
      List<Seccion> omitidas = new ArrayList<>();
      for ( Seccion s : secciones ) {
        if ( SeccionId.PORTADA.equals( s.id ) ) {
          continue;
        }
        ( Seccion.OMITIDA.equals( s.estado ) ? omitidas : vivas ).add( s );
      }
      if ( vivas.isEmpty() && omitidas.isEmpty() ) {
        return;
      }
      sb.append( "<div class=\"indice\">\n<h2>" ).append( Html.esc( t( "contents" ) ) ).append( "</h2>\n" );
      if ( !vivas.isEmpty() ) {
        sb.append( "<ol>\n" );
        for ( Seccion s : vivas ) {
          sb.append( "<li><a href=\"#" ).append( anclaSeccion( s ) ).append( "\">" ).append( Html.esc(
            tituloSeccion( s ) ) ).append( "</a>" );
          insignia( s );
          sb.append( "</li>\n" );
        }
        sb.append( "</ol>\n" );
      }
      if ( !omitidas.isEmpty() ) {
        sb.append( "<p class=\"motivo\">" ).append( Html.esc( t( "omitted_sections" ) ) ).append( ": " );
        for ( int i = 0; i < omitidas.size(); i++ ) {
          Seccion s = omitidas.get( i );
          if ( i > 0 ) {
            sb.append( "; " );
          }
          sb.append( Html.esc( tituloSeccion( s ) ) );
          if ( s.motivo != null && !s.motivo.trim().isEmpty() ) {
            sb.append( " (" ).append( Html.esc( s.motivo.trim() ) ).append( ')' );
          }
        }
        sb.append( ".</p>\n" );
      }
      sb.append( "</div>\n" );
    }

    void avisosGenerales() {
      if ( inf.avisos == null ) {
        return;
      }
      for ( String a : inf.avisos ) {
        if ( a != null && !a.trim().isEmpty() ) {
          aviso( Aviso.WARN, a );
        }
      }
    }

    void pie() {
      sb.append( "<div class=\"pie\">" );
      String s = simbolo( 1f );
      if ( s != null ) {
        sb.append( s );
      }
      sb.append( Html.esc( t( "generated_with" ) ) ).append( ' ' ).append( Html.esc( Tema.PIE ) ).append( "</div>\n" );
    }

    /** SVG del simbolo IDC (WP6) limpio para inline; null si no esta. */
    String simbolo( float opacidad ) {
      try {
        return Html.svgInline( SimboloIdc.svg( SimboloIdc.CLARO, opacidad ), 0 );
      } catch ( Throwable e ) {
        return null;
      }
    }

    // ------------------------------------------------------------ secciones y bloques

    String tituloSeccion( Seccion s ) {
      if ( s.titulo != null && !s.titulo.trim().isEmpty() ) {
        return s.titulo.trim();
      }
      return SeccionId.titulo( s.id, r.idioma() );
    }

    String anclaSeccion( Seccion s ) {
      return "sec-" + Html.idSeguro( s.id );
    }

    void insignia( Seccion s ) {
      if ( Seccion.PARCIAL.equals( s.estado ) ) {
        sb.append( " <span class=\"estado estado-parcial\">" ).append( Html.esc( t( "status_partial" ) ) ).append(
          "</span>" );
      } else if ( Seccion.ERROR.equals( s.estado ) ) {
        sb.append( " <span class=\"estado estado-error\">" ).append( Html.esc( t( "status_error" ) ) ).append(
          "</span>" );
      }
    }

    void seccion( Seccion s, boolean corta ) {
      // en la hoja impresa una seccion larga empieza pagina: se lleva la cuenta de lo que va arriba de la
      // primera figura (para que el titulo no quede solo en una hoja y la figura en la siguiente)
      usado = paraImprimir && !corta ? 45 + lineas( s.motivo, 100 ) * 20 + lineas( s.resumen, 90 ) * 21 + ( vacio(
        s.resumen ) ? 0 : 30 ) : Double.NaN;
      sb.append( "<section class=\"seccion" ).append( corta ? " corta" : "" ).append( "\" id=\"" ).append( id(
        anclaSeccion( s ) ) ).append( "\">\n<h2>" );
      sb.append( Html.esc( tituloSeccion( s ) ) );
      insignia( s );
      sb.append( "</h2>\n" );
      if ( ( Seccion.PARCIAL.equals( s.estado ) || Seccion.ERROR.equals( s.estado ) ) && s.motivo != null
        && !s.motivo.trim().isEmpty() ) {
        sb.append( "<p class=\"motivo-seccion\">" ).append( Html.escLineas( s.motivo.trim() ) ).append( "</p>\n" );
      }
      if ( s.resumen != null && !s.resumen.trim().isEmpty() ) {
        sb.append( "<div class=\"resumen\">" ).append( Html.parrafos( s.resumen, null ) ).append( "</div>\n" );
      }
      for ( Bloque b : Html.lista( s.bloques ) ) {
        if ( b == null ) {
          continue;
        }
        Bloque x = Html.sinRepetirResumen( b, s.resumen );
        if ( x != null ) {
          bloque( x );
          sumar( x );
        }
      }
      sb.append( "</section>\n" );
    }

    /** Suma el alto estimado de un bloque de texto a {@link #usado}; una figura o una tabla larga lo cortan. */
    void sumar( Bloque b ) {
      if ( Double.isNaN( usado ) ) {
        return;
      }
      double h = 16 + ( vacio( b.titulo ) ? 0 : 42 ) + ( vacio( b.nota ) ? 0 : lineas( b.nota, 110 ) * 18 + 4 );
      if ( b instanceof Parrafo ) {
        h += lineas( ( (Parrafo) b ).texto, 95 ) * 21 + 10;
      } else if ( b instanceof Aviso ) {
        h += lineas( ( (Aviso) b ).texto, 100 ) * 20 + 28;
      } else if ( b instanceof Lista ) {
        for ( String it : Html.lista( ( (Lista) b ).items ) ) {
          h += lineas( it, 90 ) * 21 + 4;
        }
        h += 10;
      } else if ( b instanceof Indicadores ) {
        h += Math.ceil( Html.lista( ( (Indicadores) b ).items ).size() / 4.0 ) * 92;
      } else if ( b instanceof Tabla && Html.lista( ( (Tabla) b ).filas ).size() <= 12 ) {
        h += 34 + Html.lista( ( (Tabla) b ).filas ).size() * 30;
      } else {
        usado = Double.NaN; // figuras y tablas largas: ya no se sabe donde queda la pagina
        return;
      }
      usado += h;
      double hoja = altoHojaPx( tema );
      if ( usado > hoja ) {
        usado = usado % hoja;
      }
    }

    /** Lineas aproximadas de un texto de {@code porLinea} caracteres por linea (0 si esta vacio). */
    int lineas( String texto, int porLinea ) {
      if ( vacio( texto ) ) {
        return 0;
      }
      int n = 0;
      for ( String l : texto.trim().split( "\\r?\\n" ) ) {
        n += Math.max( 1, (int) Math.ceil( l.length() / (double) porLinea ) );
      }
      return n;
    }

    boolean vacio( String t ) {
      return t == null || t.trim().isEmpty();
    }

    void bloque( Bloque b ) {
      String ancla = b.id == null ? null : id( "b-" + Html.idSeguro( b.id ) );
      sb.append( "<div class=\"bloque" ).append( b.ia ? " ia" : "" ).append( '"' );
      if ( ancla != null ) {
        sb.append( " id=\"" ).append( ancla ).append( '"' );
      }
      sb.append( ">\n" );
      if ( b.titulo != null && !b.titulo.trim().isEmpty() ) {
        sb.append( "<h3>" ).append( Html.esc( b.titulo.trim() ) ).append( "</h3>\n" );
      }
      if ( b instanceof Parrafo ) {
        sb.append( Html.parrafos( ( (Parrafo) b ).texto, null ) );
      } else if ( b instanceof Lista ) {
        sb.append( "<ul>" );
        for ( String it : Html.lista( ( (Lista) b ).items ) ) {
          sb.append( "<li>" ).append( Html.enLinea( it ) ).append( "</li>" );
        }
        sb.append( "</ul>\n" );
      } else if ( b instanceof Tabla ) {
        tabla( (Tabla) b );
      } else if ( b instanceof Indicadores ) {
        indicadores( (Indicadores) b );
      } else if ( b instanceof Aviso ) {
        Aviso a = (Aviso) b;
        aviso( a.nivel, a.texto );
      } else if ( b instanceof Diagrama ) {
        diagrama( (Diagrama) b );
      } else if ( b instanceof Grafico ) {
        grafico( (Grafico) b );
      } else if ( b instanceof Imagen ) {
        Imagen im = (Imagen) b;
        sb.append( "<div class=\"figura imagen\">" );
        imagen( im, im.alt );
        sb.append( "</div>\n" );
      }
      if ( b.nota != null && !b.nota.trim().isEmpty() ) {
        sb.append( "<p class=\"nota\">" ).append( Html.escLineas( b.nota.trim() ) ).append( "</p>\n" );
      }
      if ( b.ia && tema.rotularIa ) {
        sb.append( "<p class=\"rotulo-ia\"><span>" ).append( Html.esc( t( "ai_short" ) ) ).append( "</span>" )
          .append( Html.esc( t( "ai_label" ) ) ).append( "</p>\n" );
      }
      sb.append( "</div>\n" );
    }

    void tabla( Tabla t ) {
      sb.append( "<div class=\"tabla\"><table class=\"datos\">" );
      int columnas = t.columnas == null ? 0 : t.columnas.size();
      if ( t.filas != null ) {
        for ( List<String> f : t.filas ) {
          columnas = Math.max( columnas, f == null ? 0 : f.size() );
        }
      }
      if ( t.columnas != null && !t.columnas.isEmpty() ) {
        sb.append( "<thead><tr>" );
        for ( int i = 0; i < columnas; i++ ) {
          String c = i < t.columnas.size() ? t.columnas.get( i ) : "";
          sb.append( "<th" ).append( alineacion( t, i ) ).append( '>' ).append( Html.escLineas( c ) ).append(
            "</th>" );
        }
        sb.append( "</tr></thead>" );
      }
      sb.append( "<tbody>\n" );
      int n = 0;
      if ( t.filas != null ) {
        for ( List<String> f : t.filas ) {
          sb.append( n++ % 2 == 1 ? "<tr class=\"par\">" : "<tr>" );
          for ( int i = 0; i < columnas; i++ ) {
            String c = f == null || i >= f.size() ? null : f.get( i );
            sb.append( "<td" ).append( alineacion( t, i ) ).append( '>' ).append( Html.escLineas( c ) ).append(
              "</td>" );
          }
          sb.append( "</tr>\n" );
        }
      }
      sb.append( "</tbody></table></div>\n" );
      if ( t.pie != null && !t.pie.trim().isEmpty() ) {
        sb.append( "<p class=\"pie-tabla\">" ).append( Html.escLineas( t.pie.trim() ) ).append( "</p>\n" );
      }
    }

    String alineacion( Tabla t, int i ) {
      if ( t.alineacion == null || i >= t.alineacion.size() || t.alineacion.get( i ) == null ) {
        return "";
      }
      String a = t.alineacion.get( i ).trim().toLowerCase( Locale.ROOT );
      return "r".equals( a ) ? " class=\"r\"" : "c".equals( a ) ? " class=\"c\"" : "";
    }

    void indicadores( Indicadores ind ) {
      sb.append( "<div class=\"tarjetas\">" );
      for ( Indicador i : Html.lista( ind.items ) ) {
        if ( i == null ) {
          continue;
        }
        String nivel = Indicador.OK.equals( i.nivel ) || Indicador.WARN.equals( i.nivel ) || Indicador.ERROR.equals(
          i.nivel ) ? " " + i.nivel : "";
        sb.append( "<div class=\"tarjeta" ).append( nivel ).append( "\">" );
        sb.append( "<div class=\"etiqueta\">" ).append( Html.esc( i.etiqueta ) ).append( "</div>" );
        sb.append( "<div class=\"valor\">" ).append( Html.esc( i.valor ) ).append( "</div>" );
        if ( i.detalle != null && !i.detalle.trim().isEmpty() ) {
          sb.append( "<div class=\"detalle\">" ).append( Html.esc( i.detalle ) ).append( "</div>" );
        }
        sb.append( "</div>" );
      }
      sb.append( "</div>\n" );
    }

    void aviso( String nivel, String texto ) {
      String n = Aviso.ERROR.equals( nivel ) ? Aviso.ERROR : Aviso.WARN.equals( nivel ) ? Aviso.WARN : Aviso.INFO;
      sb.append( "<div class=\"aviso aviso-" ).append( n ).append( "\"><strong class=\"nivel\">" ).append( Html.esc( t(
        "level_" + n ) ) ).append( ".</strong>" );
      List<Html.Grupo> grupos = Html.grupos( texto );
      boolean primero = true;
      for ( Html.Grupo g : grupos ) {
        for ( String linea : g.lineas ) {
          if ( !primero ) {
            sb.append( "<br>" );
          }
          sb.append( g.lista ? "&bull; " : "" ).append( Html.enLinea( linea ) );
          primero = false;
        }
      }
      sb.append( "</div>\n" );
    }

    void imagen( Imagen im, String alt ) {
      if ( im == null || im.datos == null || im.datos.length == 0 ) {
        sb.append( "<div class=\"pendiente\">" ).append( Html.esc( t( "image_unavailable" ) ) ).append( "</div>" );
        return;
      }
      float escala = im.escala > 0 ? im.escala : 1f;
      sb.append( "<img src=\"" ).append( Html.dataUri( im.mime, im.datos ) ).append( '"' );
      if ( im.ancho > 0 ) {
        long ancho = Math.round( im.ancho / escala );
        if ( paraImprimir && im.alto > 0 ) {
          // en el PDF una imagen alta (un lienzo de Spoon vertical) no puede pasar del alto de la pagina: se
          // le baja el ancho, asi el alto proporcional entra (el CSS solo pone el tope, y con object-fit)
          ancho = Math.min( ancho, (long) Math.floor( altoFiguraMm( tema ) / 25.4 * 96 * im.ancho / im.alto ) );
        }
        sb.append( " width=\"" ).append( Math.max( 1L, ancho ) ).append( '"' );
      }
      String a = alt != null ? alt : im.alt;
      sb.append( " alt=\"" ).append( Html.esc( a == null ? "" : a ) ).append( "\">" );
    }

    void diagrama( Diagrama d ) {
      List<Dibujo> ds = Figuras.dibujos( d, objetivo( tema ), inf.idioma );
      if ( ds != null ) {
        propio( d, ds );
        return;
      }
      sb.append( "<div class=\"figura diagrama\">\n" );
      boolean hayImagen = d.imagen != null && d.imagen.datos != null && d.imagen.datos.length > 0;
      if ( hayImagen ) {
        sb.append( "<div class=\"lienzo\">" );
        imagen( d.imagen, d.imagen.alt != null ? d.imagen.alt : d.alt );
        sb.append( "</div>\n" );
      }
      boolean hayMermaid = d.mermaid != null && !d.mermaid.trim().isEmpty();
      String base = Html.idSeguro( d.id != null ? d.id : "diagrama" );
      if ( hayMermaid && modo == Modo.INTERACTIVO ) {
        String alt = d.alternativa != null ? id( "alt-" + base ) : null;
        sb.append( "<pre class=\"mermaid\" id=\"" ).append( id( "m-" + base ) ).append( '"' );
        if ( alt != null ) {
          sb.append( " data-alt=\"" ).append( alt ).append( '"' );
        }
        if ( d.alt != null ) {
          sb.append( " title=\"" ).append( Html.esc( d.alt ) ).append( '"' );
        }
        sb.append( '>' ).append( Html.esc( d.mermaid.trim() ) ).append( "</pre>\n" );
        hayMermaid = true;
        if ( alt != null ) {
          sb.append( "<div class=\"alternativa mermaid-fallo\" id=\"" ).append( alt ).append( "\">" );
          tabla( d.alternativa );
          sb.append( "</div>\n" );
        }
      } else if ( hayMermaid ) {
        String svg = d.id == null ? null : Html.svgInline( svgPrerender.get( d.id ), Html.ANCHO_MAX_SVG );
        if ( svg != null ) {
          sb.append( "<div class=\"svg\" role=\"img\"" );
          if ( d.alt != null ) {
            sb.append( " aria-label=\"" ).append( Html.esc( d.alt ) ).append( '"' );
          }
          sb.append( '>' ).append( svg ).append( "</div>\n" );
        } else if ( !hayImagen ) {
          sb.append( "<div class=\"pendiente\">" ).append( Html.esc( t( "diagram_pending" ) ) ).append( "</div>\n" );
          if ( d.alternativa != null ) {
            sb.append( "<div class=\"alternativa\">" );
            tabla( d.alternativa );
            sb.append( "</div>\n" );
          }
        }
      } else if ( !hayImagen && d.alternativa != null ) {
        sb.append( "<div class=\"alternativa\">" );
        tabla( d.alternativa );
        sb.append( "</div>\n" );
      }
      sb.append( "</div>\n" );
    }

    /**
     * Diagrama del motor propio: una figura por parte (SVG inline del tamano final, con «Parte i de n» si se
     * partio) y despues el lienzo de Spoon si no es una tira; una tira va plegada en la pantalla y no se
     * imprime. Al imprimir, la primera figura de la seccion se achica (hasta el 60 % y sin bajar de la letra
     * minima) si asi entra debajo del titulo: si no, pasaria entera a la hoja siguiente.
     */
    void propio( Diagrama d, List<Dibujo> ds ) {
      sb.append( "<div class=\"diagrama propio\">\n" );
      for ( Dibujo x : ds ) {
        double w = x.anchoFinal();
        double h = x.altoFinal();
        if ( paraImprimir && x.parte == 1 && !Double.isNaN( usado ) ) {
          double libre = altoHojaPx( tema ) - usado - ( ds.size() > 1 ? 26 : 0 ) - 24;
          double k = libre / h;
          if ( k < 1 && k >= 0.6 && x.letra * k >= x.objetivo.letraMin ) {
            w *= k;
            h *= k;
          }
        }
        String svg = Html.svgInline( x.conTamano( redondo( w ), redondo( h ) ), Integer.MAX_VALUE );
        sb.append( "<div class=\"figura\">\n" );
        if ( ds.size() > 1 ) {
          sb.append( "<p class=\"parte\">" ).append( Html.esc( Figuras.parte( inf.idioma, x.parte, x.partes ) ) )
            .append( "</p>\n" );
        }
        sb.append( "<div class=\"svg\" role=\"img\"" );
        if ( d.alt != null ) {
          sb.append( " aria-label=\"" ).append( Html.esc( d.alt ) ).append( '"' );
        }
        sb.append( '>' ).append( svg == null ? "" : svg ).append( "</div>\n</div>\n" );
      }
      Imagen lienzo = Figuras.lienzo( d );
      if ( lienzo != null ) {
        String alt = lienzo.alt != null ? lienzo.alt : d.alt;
        if ( Figuras.proporcionada( lienzo ) ) {
          sb.append( "<div class=\"figura lienzo\">" );
          imagen( lienzo, alt );
          sb.append( "</div>\n" );
        } else if ( !paraImprimir ) {
          String resumen = Rotulos.de( Diagramas.AREA, inf.idioma ).s( "lienzo.plegado" );
          sb.append( "<details class=\"lienzo-plegado\"><summary>" ).append( Html.esc( resumen ) ).append(
            "</summary><div class=\"lienzo-ancho\">" );
          imagen( lienzo, alt );
          sb.append( "</div></details>\n" );
        }
      }
      sb.append( "</div>\n" );
      usado = Double.NaN;
    }

    double redondo( double v ) {
      return Math.round( v * 100 ) / 100.0;
    }

    void grafico( Grafico g ) {
      String svg = null;
      try {
        // el idioma del informe (numeros y leyendas), no el del tema, que puede ser null
        svg = Html.svgInline( GraficosSvg.svg( g, tema, ANCHO_GRAFICO, inf.idioma ), Html.ANCHO_MAX_SVG );
      } catch ( Throwable e ) {
        svg = null;
      }
      sb.append( "<div class=\"figura grafico\">" );
      if ( svg != null ) {
        sb.append( "<div class=\"svg\" role=\"img\"" );
        if ( g.titulo != null ) {
          sb.append( " aria-label=\"" ).append( Html.esc( g.titulo ) ).append( '"' );
        }
        sb.append( '>' ).append( svg ).append( "</div>" );
      } else {
        String nombre = g.titulo != null ? g.titulo : g.id != null ? g.id : g.clase;
        avisos.add( r.f( "chart_unavailable", nombre ) );
        tabla( tablaDeGrafico( g, r, loc ) );
      }
      sb.append( "</div>\n" );
    }

    /** Un id unico dentro del documento (agrega -2, -3... si se repite). */
    String id( String deseado ) {
      String b = deseado;
      int n = 2;
      while ( !ids.add( b ) ) {
        b = deseado + "-" + n++;
      }
      return b;
    }

    // ------------------------------------------------------------ scripts (solo INTERACTIVO)

    void scripts() {
      // el bundle (2,6 MB) solo si queda algun pre.mermaid: flujo, job y linaje ya van como SVG propio
      String js = hayMermaid ? mermaidJs() : null;
      if ( hayMermaid && js == null ) {
        avisos.add( t( "no_mermaid" ) );
      } else if ( js != null ) {
        sb.append( "<script>\n" ).append( js ).append( "\n</script>\n" );
      }
      // ES5: corre tambien en IE11, donde el bundle no carga y quedan las tablas alternativas
      sb.append( "<script>\n" ).append( scriptMermaid( tema, false ) ).append( "</script>\n" );
    }
  }
}
