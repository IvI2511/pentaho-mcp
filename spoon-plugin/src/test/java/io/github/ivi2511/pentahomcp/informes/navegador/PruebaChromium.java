package io.github.ivi2511.pentahomcp.informes.navegador;

import static io.github.ivi2511.pentahomcp.Verificador.archivo;
import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.distinto;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.lanza;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;
import static io.github.ivi2511.pentahomcp.Verificador.saltear;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.ProgressAdapter;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.w3c.dom.Document;

import io.github.ivi2511.pentahomcp.EntornoKettle;
import io.github.ivi2511.pentahomcp.ModeloMuestra;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Resultado;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * WP8: Chromium headless (PDF y pre-render de Mermaid). Se saltea entera si no hay Edge ni Chrome, y
 * las de Mermaid si falta mermaid.min.js (lo trae WP7; mientras tanto, -Dpentaho.live.mermaid=ruta, por
 * ejemplo con JAVA_TOOL_OPTIONS). Deja PNG, SVG, HTML y PDF en {@code <Salida>\prueba\salida\navegador}.
 *
 * @dueño WP8
 */
public class PruebaChromium {

  private File exe;
  private File salida;
  private Tema tema;

  public PruebaChromium() {
  }

  public void preparar() throws Exception {
    salida = EntornoKettle.salida( "navegador" );
    tema = ModeloMuestra.tema();
    exe = Chromium.ubicar();
    if ( exe == null ) {
      saltear( "no hay Edge ni Chrome" );
    }
  }

  // ------------------------------------------------------------------------------------- pruebas

  /** §3.5, lo primero: varios SVG apilados, una captura por tanda y recorte de cada caja. */
  public void pruebaCapturaYRecorte() throws Exception {
    hayMermaid();
    List<Diagrama> ds = ModeloMuestra.diagramas( ModeloMuestra.crear() );
    ds.add( cadena( "alto-1", 45 ) );
    ds.add( cadena( "alto-2", 45 ) );
    PrerenderMermaid.olvidar();
    Map<String, PrerenderMermaid.Svg> svgs = PrerenderMermaid.svgs( ds, tema, Progreso.NULO );
    igual( ds.size(), svgs.size(), "SVG por diagrama (" + PrerenderMermaid.ultimoError() + ")" );
    int altoTotal = 0;
    for ( PrerenderMermaid.Svg s : svgs.values() ) {
      altoTotal += Math.round( s.alto * Math.min( 1f, (float) PrerenderMermaid.MAX_LADO / Math.max( s.ancho,
        s.alto ) ) ) + 16;
    }
    cierto( altoTotal > PrerenderMermaid.MAX_ALTO_CORRIDA, "los diagramas no alcanzan para 2 tandas: " + altoTotal );

    long t0 = System.currentTimeMillis();
    Map<String, byte[]> pngs = PrerenderMermaid.png( ds, tema );
    long ms = System.currentTimeMillis() - t0;
    igual( ds.size(), pngs.size(), "PNG por diagrama (" + PrerenderMermaid.ultimoError() + ")" );
    cierto( ms < 30000, "la captura tardo " + ms + " ms" );
    for ( Map.Entry<String, byte[]> e : pngs.entrySet() ) {
      PrerenderMermaid.Svg s = svgs.get( e.getKey() );
      BufferedImage img = ImageIO.read( new ByteArrayInputStream( e.getValue() ) );
      noNulo( img, "PNG legible de " + e.getKey() );
      double f = Math.min( 1d, (double) PrerenderMermaid.MAX_LADO / Math.max( s.ancho, s.alto ) );
      int w = (int) Math.round( s.ancho * f ) * PrerenderMermaid.ESCALA;
      int h = (int) Math.round( s.alto * f ) * PrerenderMermaid.ESCALA;
      cierto( Math.abs( img.getWidth() - w ) <= 2 && Math.abs( img.getHeight() - h ) <= 2, e.getKey() + ": "
        + img.getWidth() + "x" + img.getHeight() + " y se esperaba " + w + "x" + h );
      cierto( tinta( img ) > 0.005, e.getKey() + ": el recorte esta vacio" );
      // los bordes del recorte son margen blanco: no se metio un pedazo del diagrama vecino
      cierto( bordeBlanco( img ) > 0.97, e.getKey() + ": el borde del recorte no es blanco ("
        + bordeBlanco( img ) + ")" );
      Files.write( new File( salida, "png-" + e.getKey() + ".png" ).toPath(), e.getValue() );
    }
    // un diagrama de mas de MAX_LADO se reduce: el PNG no pasa de 2 x MAX_LADO
    BufferedImage alto = ImageIO.read( new ByteArrayInputStream( pngs.get( "alto-1" ) ) );
    cierto( Math.max( alto.getWidth(), alto.getHeight() ) <= PrerenderMermaid.MAX_LADO * PrerenderMermaid.ESCALA + 2,
      "alto-1 mide " + alto.getWidth() + "x" + alto.getHeight() );
    // cache: la segunda vez no corre el navegador
    t0 = System.currentTimeMillis();
    Map<String, byte[]> otra = PrerenderMermaid.png( ds, tema );
    cierto( System.currentTimeMillis() - t0 < 1000, "la cache de PNG no anduvo" );
    cierto( Arrays.equals( otra.get( "flow-diagrama" ), pngs.get( "flow-diagrama" ) ), "cache: mismo PNG" );
  }

  /** SVG estatico: XML valido, sin foreignObject, con los rotulos (escapes incluidos) y los colores. */
  public void pruebaPrerenderSvg() throws Exception {
    hayMermaid();
    List<Diagrama> ds = ModeloMuestra.diagramas( ModeloMuestra.crear() );
    Diagrama roto = new Diagrama();
    roto.id = "roto";
    roto.clase = Diagrama.FLUJO;
    roto.mermaid = "flowchart LR\n  a --> b[[mal\n";
    ds.add( roto );
    Diagrama sinTexto = new Diagrama();
    sinTexto.id = "sin-texto";
    ds.add( sinTexto );
    // codigos de ReportMotor.escaparMermaid en titulo de subgraph, nodo y arista
    String raro = "#60;b#62;#38;#34;x";
    Diagrama escapes = new Diagrama();
    escapes.id = "escapes";
    escapes.clase = Diagrama.LINAJE;
    escapes.mermaid = "flowchart LR\n  subgraph S1[\"sub " + raro + "\"]\n    f1[\"nodo " + raro + "\"]\n  end\n"
      + "  f1 -->|\"arista " + raro + "\"| f2[\"a#35;b#96;c\"]\n";
    ds.add( escapes );

    Map<String, String> m = PrerenderMermaid.svg( ds, tema );
    igual( 4, m.size(), "flujo, linaje, er y escapes (" + PrerenderMermaid.ultimoError() + ")" );
    // Mermaid parte los rotulos en un tspan por palabra: se compara sin espacios
    String esc = texto( m.get( "escapes" ) ).replaceAll( "\\s+", "" );
    contiene( esc, "sub" + ModeloMuestra.STEP_RARO, "titulo de subgraph" );
    contiene( esc, "nodo" + ModeloMuestra.STEP_RARO, "rotulo de nodo" );
    contiene( esc, "arista" + ModeloMuestra.STEP_RARO, "rotulo de arista" );
    contiene( esc, "a#b`c", "# y backtick" );
    noContiene( esc, "ﬂ", "sin marcadores internos de Mermaid" );
    noContiene( esc, "&#", "sin codigos a la vista" );
    m.remove( "escapes" );
    falso( m.containsKey( "roto" ), "el diagrama roto no esta en el mapa" );
    falso( m.containsKey( "sin-texto" ), "el diagrama sin mermaid no esta en el mapa" );
    noNulo( PrerenderMermaid.ultimoError(), "queda el motivo del diagrama roto" );

    String flujo = texto( m.get( "flow-diagrama" ) );
    contiene( flujo, "Leer ventas", "rotulo del flujo" );
    contiene( flujo, ModeloMuestra.STEP_RARO, "el step <b>&\"x se lee tal cual (escaparMermaid + arreglo)" );
    noContiene( flujo, "#60;", "sin codigos de entidad a la vista" );
    String linaje = texto( m.get( "lineage-diagrama" ) );
    contiene( linaje, "cantidad x precio", "rotulo de arista del linaje" );
    contiene( linaje, "Agrupar por producto", "subgraph del linaje" );
    String er = texto( m.get( "target-model-er" ) );
    contiene( er, "ventas_producto", "entidad del ER" );
    contiene( er, "PK", "clave del ER" );
    Set<String> ids = new HashSet<>();
    for ( Map.Entry<String, String> e : m.entrySet() ) {
      String xml = e.getValue();
      noContiene( xml, "foreignObject", e.getKey() + ": htmlLabels:false" );
      contiene( xml, "<text", e.getKey() + ": texto SVG" );
      contiene( xml, Tema.PRIMARIO, e.getKey() + ": color primario del tema" );
      cierto( xml.matches( "(?s)<svg[^>]*\\swidth=\"\\d+\"[^>]*>.*" ), e.getKey() + ": width en px" );
      Matcher id = Pattern.compile( "<svg[^>]*\\sid=\"(plm[0-9a-f]+)\"" ).matcher( xml );
      cierto( id.find(), e.getKey() + ": id plm<sha1>" );
      cierto( ids.add( id.group( 1 ) ), "ids unicos" );
      EntornoKettle.escribir( new File( salida, "svg-" + e.getKey() + ".svg" ), xml );
    }

    // cache por sha1(mermaid + colores)
    long t0 = System.currentTimeMillis();
    Map<String, String> otra = PrerenderMermaid.svg( ds, tema );
    cierto( System.currentTimeMillis() - t0 < 500, "la cache de SVG no anduvo" );
    igual( m.get( "flow-diagrama" ), otra.get( "flow-diagrama" ), "cache: mismo SVG" );
    Tema rojo = tema.copia();
    rojo.colores.primario = "#7A1F1F";
    Map<String, String> conRojo = PrerenderMermaid.svg( ds.subList( 0, 1 ), rojo );
    contiene( conRojo.get( "flow-diagrama" ), "#7A1F1F", "otro color primario" );
    distinto( m.get( "flow-diagrama" ), conRojo.get( "flow-diagrama" ), "otra clave de cache" );
    // un color invalido no entra al JS
    Tema malo = tema.copia();
    malo.colores.primario = "red'});alert(1);//";
    contiene( PrerenderMermaid.configuracion( malo ), Tema.PRIMARIO, "color invalido -> defecto" );
    noContiene( PrerenderMermaid.configuracion( malo ), "alert", "nada del usuario sin validar" );
  }

  /** §8.2: IE11 (el Browser de SWT 3.115) dibuja los SVG pre-renderizados con sus rotulos. */
  public void pruebaIe11DibujaSvg() throws Exception {
    hayMermaid();
    if ( !Chromium.esWindows() ) {
      saltear( "IE11 solo en Windows" );
    }
    Map<String, String> m = PrerenderMermaid.svg( ModeloMuestra.diagramas( ModeloMuestra.crear() ), tema );
    igual( 3, m.size(), "3 SVG" );
    StringBuilder html = new StringBuilder( "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\">"
      + "<meta http-equiv=\"X-UA-Compatible\" content=\"IE=edge\"><title>ie11</title></head>"
      + "<body style=\"font-family:'Segoe UI',Arial,sans-serif\">\n" );
    for ( Map.Entry<String, String> e : m.entrySet() ) {
      html.append( "<h2>" ).append( e.getKey() ).append( "</h2><div>" ).append( e.getValue() ).append( "</div>\n" );
    }
    html.append( "</body></html>\n" );
    File pagina = new File( salida, "ie11.html" );
    EntornoKettle.escribir( pagina, html.toString() );

    String r = enIe( pagina, new File( salida, "ie11.png" ) );
    String[] l = r.split( "\n" );
    igual( "11", l[ 0 ], "documentMode" );
    igual( "3", l[ 1 ], "SVG en la pagina" );
    String[] nombres = m.keySet().toArray( new String[ 0 ] );
    for ( int i = 0; i < 3; i++ ) {
      String[] c = l[ 2 + i ].split( ":", 3 );
      int textos = Integer.parseInt( c[ 0 ] );
      int dibujados = Integer.parseInt( c[ 1 ] );
      cierto( textos > 0, nombres[ i ] + ": sin <text>" );
      igual( textos, dibujados, nombres[ i ] + ": <text> con ancho > 0 en IE" );
    }
    contiene( l[ 2 ], ModeloMuestra.STEP_RARO, "IE lee el rotulo raro" );
    contiene( l[ 4 ], "ventas_producto", "IE lee el ER" );
  }

  /** PDF del HTML fijo (Mermaid inline como en el HTML interactivo para imprimir). */
  public void pruebaPdfHtmlFijo() throws Exception {
    File js = hayMermaid();
    File dir = new File( salida, "pdf-fijo" );
    dir.mkdirs();
    File html = new File( dir, "diagramas.html" );
    Files.copy( new File( EntornoKettle.recursos(), "html/diagramas.html" ).toPath(), html.toPath(),
      StandardCopyOption.REPLACE_EXISTING );
    Files.copy( js.toPath(), new File( dir, "mermaid.min.js" ).toPath(), StandardCopyOption.REPLACE_EXISTING );
    File pdf = new File( dir, "diagramas.pdf" );
    long t0 = System.currentTimeMillis();
    RenderPdf.pdfDeHtml( html, pdf, null, Progreso.NULO );
    long ms = System.currentTimeMillis() - t0;
    validarPdf( pdf, 3 );
    cierto( ms < 30000, "el PDF tardo " + ms + " ms" );
  }

  /** PDF de ModeloMuestra con RenderPdf (necesita RenderHtml de WP7). */
  public void pruebaPdfModeloMuestra() throws Exception {
    hayMermaid();
    File pdf = new File( salida, "modelo-muestra.pdf" );
    Resultado r;
    try {
      r = new RenderPdf().escribir( ModeloMuestra.crear(), tema, pdf, Progreso.NULO );
    } catch ( UnsupportedOperationException e ) {
      saltear( "RenderHtml todavia es stub (" + e.getMessage() + ")" );
      return;
    }
    igual( 0, r.avisos.size(), "sin avisos: " + r.avisos );
    igual( pdf.getAbsoluteFile(), r.archivos.get( 0 ).getAbsoluteFile(), "archivo principal" );
    igual( pdf.length(), r.bytes, "bytes del Resultado" );
    validarPdf( pdf, 2 );
  }

  /** Sin navegador: &lt;nombre&gt;.imprimir.html con window.print() y un aviso, sin fallar. */
  public void pruebaPdfSinNavegador() throws Exception {
    File pdf = new File( salida, "sin-navegador.pdf" );
    pdf.delete();
    String antes = System.getProperty( Chromium.PROPIEDAD );
    System.setProperty( Chromium.PROPIEDAD, "none" );
    Resultado r;
    try {
      nulo( Chromium.ubicar(), "con la propiedad en none no hay navegador" );
      Informe inf = ModeloMuestra.crear();
      try {
        r = new RenderPdf().escribir( inf, tema, pdf, Progreso.NULO );
      } catch ( UnsupportedOperationException e ) {
        saltear( "RenderHtml todavia es stub (" + e.getMessage() + ")" );
        return;
      }
    } finally {
      if ( antes == null ) {
        System.clearProperty( Chromium.PROPIEDAD );
      } else {
        System.setProperty( Chromium.PROPIEDAD, antes );
      }
    }
    falso( pdf.exists(), "no se escribe un PDF" );
    File imp = new File( salida, "sin-navegador" + RenderPdf.SUFIJO_IMPRIMIR );
    igual( imp.getAbsoluteFile(), r.archivos.get( 0 ).getAbsoluteFile(), "el principal es el .imprimir.html" );
    String texto = EntornoKettle.leer( imp );
    contiene( texto, "window.print()", "imprime al abrir" );
    igual( 1, r.avisos.size(), "un aviso" );
    contiene( r.avisos.get( 0 ), imp.getName(), "el aviso nombra el archivo" );
    contiene( r.avisos.get( 0 ), "Guardar como PDF", "aviso en el idioma del informe" );
  }

  /** Las corridas no pasan de 2 a la vez. */
  public void pruebaSemaforo() throws Exception {
    final List<Throwable> errores = new ArrayList<>();
    List<Thread> hilos = new ArrayList<>();
    for ( int i = 0; i < 4; i++ ) {
      Thread h = new Thread( () -> {
        try {
          Chromium.ejecutar( exe, Arrays.asList( "--dump-dom", "about:blank" ), 30000, false, null );
        } catch ( Throwable e ) {
          synchronized ( errores ) {
            errores.add( e );
          }
        }
      } );
      hilos.add( h );
      h.start();
    }
    for ( Thread h : hilos ) {
      h.join( 120000 );
    }
    igual( 0, errores.size(), "corridas sin error: " + errores );
    igual( 2, Chromium.maximoSimultaneo(), "maximo de corridas a la vez" );
  }

  /** Timeout y cancelacion: se mata el arbol entero y el perfil se borra. */
  public void pruebaTiempoAgotado() throws Exception {
    File dir = Chromium.carpetaTrabajo();
    try {
      File colgada = new File( dir, "colgada.html" );
      EntornoKettle.escribir( colgada, "<!DOCTYPE html><html><body><p>colgada</p>"
        + "<script>while(true){}</script></body></html>" );
      final List<String> args = Arrays.asList( "--print-to-pdf=" + new File( dir, "x.pdf" ).getAbsolutePath(),
        Chromium.url( colgada ) );
      long t0 = System.currentTimeMillis();
      Chromium.Agotado a = lanza( Chromium.Agotado.class, () -> Chromium.ejecutar( exe, args, 4000, false, null ),
        "timeout de 4 s" );
      long ms = System.currentTimeMillis() - t0;
      cierto( ms >= 4000 && ms < 40000, "tardo " + ms + " ms" );
      igual( 0, Chromium.procesos( a.perfil, exe, false ).size(), "procesos vivos con " + a.perfil );
      falso( new File( a.perfil ).exists(), "perfil borrado" );
      falso( new File( dir, "x.pdf" ).exists(), "sin PDF" );

      // cancelacion a mitad de camino
      Set<String> antes = new HashSet<>( Chromium.perfilesUsados() );
      final long t1 = System.currentTimeMillis();
      Progreso cancela = new Progreso() {
        @Override
        public void paso( String texto, int hecho, int total ) {
        }

        @Override
        public boolean cancelado() {
          return System.currentTimeMillis() - t1 > 1500;
        }
      };
      lanza( CancellationException.class, () -> Chromium.ejecutar( exe, args, 60000, false, cancela ),
        "cancelacion" );
      cierto( System.currentTimeMillis() - t1 < 30000, "la cancelacion no espero los 60 s" );
      Set<String> nuevos = new HashSet<>( Chromium.perfilesUsados() );
      nuevos.removeAll( antes );
      igual( 1, nuevos.size(), "un perfil nuevo" );
      String perfil = nuevos.iterator().next();
      igual( 0, Chromium.procesos( perfil, exe, false ).size(), "procesos vivos tras cancelar" );
      falso( new File( perfil ).exists(), "perfil borrado tras cancelar" );
    } finally {
      Chromium.borrar( dir );
    }
  }

  /** ubicar: propiedad, preferido, busqueda y cache. */
  public void pruebaUbicar() throws Exception {
    File f = Chromium.ubicar();
    cierto( f.isFile(), "existe " + f );
    String n = f.getName().toLowerCase();
    cierto( n.equals( "msedge.exe" ) || n.equals( "chrome.exe" ), "es Edge o Chrome: " + f );
    igual( f, Chromium.ubicar( "C:\\no\\existe\\msedge.exe" ), "un preferido que no existe se ignora" );
    igual( f, Chromium.ubicar( "" ), "preferido vacio" );
    File chrome = new File( System.getenv( "ProgramFiles" ) == null ? "C:\\Program Files" : System.getenv(
      "ProgramFiles" ), "Google\\Chrome\\Application\\chrome.exe" );
    if ( chrome.isFile() ) {
      igual( chrome, Chromium.ubicar( chrome.getAbsolutePath() ), "el preferido gana" );
    }
    String antes = System.getProperty( Chromium.PROPIEDAD );
    try {
      System.setProperty( Chromium.PROPIEDAD, "none" );
      nulo( Chromium.ubicar( f.getAbsolutePath() ), "none gana sobre todo" );
    } finally {
      if ( antes == null ) {
        System.clearProperty( Chromium.PROPIEDAD );
      } else {
        System.setProperty( Chromium.PROPIEDAD, antes );
      }
    }
    Chromium.olvidar();
    igual( f, Chromium.ubicar(), "despues de olvidar se vuelve a encontrar" );
    String url = Chromium.url( new File( "C:\\a b\\c.html" ) );
    igual( "file:///C:/a%20b/c.html", url, "url de archivo" );
  }

  /** Las caches del pre-render son LRU con tope de entradas y de bytes (no crecen en una sesion larga). */
  public void pruebaCacheAcotada() {
    PrerenderMermaid.Lru<byte[]> l = new PrerenderMermaid.Lru<>( 3, 100, b -> b.length );
    for ( int i = 0; i < 5; i++ ) {
      l.put( "k" + i, new byte[ 10 ] );
    }
    igual( 3, l.size(), "tope de entradas" );
    nulo( l.get( "k0" ), "la mas vieja se descarto" );
    noNulo( l.get( "k4" ), "la ultima queda" );
    l.get( "k2" ); // usada: pasa a ser la mas nueva
    l.put( "k5", new byte[ 10 ] );
    noNulo( l.get( "k2" ), "LRU: la recien usada queda" );
    nulo( l.get( "k3" ), "LRU: sale la menos usada" );
    l.put( "grande", new byte[ 90 ] );
    cierto( l.bytes() <= 100, "tope de bytes: " + l.bytes() );
    noNulo( l.get( "grande" ), "la nueva queda" );
    l.put( "enorme", new byte[ 500 ] );
    igual( 1, l.size(), "una sola mas grande que el tope: queda sola" );
    noNulo( l.get( "enorme" ), "y es la nueva" );
    l.clear();
    igual( 0L, l.bytes(), "clear" );
  }

  /** Va ultima (orden alfabetico): ningun msedge/chrome con nuestros perfiles queda vivo. */
  public void pruebaZzSinProcesosVivos() throws Exception {
    igual( 0, Chromium.vivos(), "ningun navegador de esta JVM registrado como vivo" );
    igual( 0, Chromium.cerrarTodo(), "el gancho de cierre no encuentra nada que matar" );
    Set<String> perfiles = Chromium.perfilesUsados();
    if ( perfiles.isEmpty() ) {
      saltear( "no corrio ningun navegador" );
    }
    Map<Long, String> vivos = Chromium.lineasDeComando( Chromium.PREFIJO_PERFIL, exe, false );
    List<String> nuestros = new ArrayList<>();
    for ( Map.Entry<Long, String> e : vivos.entrySet() ) {
      for ( String p : perfiles ) {
        if ( e.getValue().contains( p ) ) {
          nuestros.add( e.getKey() + " " + p );
        }
      }
    }
    igual( 0, nuestros.size(), perfiles.size() + " perfiles usados; vivos: " + nuestros );
    for ( String p : perfiles ) {
      falso( new File( p ).exists(), "perfil sin borrar: " + p );
    }
    List<String> quedan = new ArrayList<>();
    for ( String c : Chromium.carpetasUsadas() ) {
      if ( new File( c ).exists() ) {
        quedan.add( c );
      }
    }
    igual( 0, quedan.size(), "carpetas de trabajo sin borrar (stdout/stderr, paginas, capturas): " + quedan );
  }

  // ------------------------------------------------------------------------------------- ayudas

  private File hayMermaid() {
    File js = PrerenderMermaid.mermaidJs();
    if ( js == null ) {
      saltear( "falta mermaid.min.js (" + PrerenderMermaid.RECURSO_MERMAID + ", WP7)" );
    }
    return js;
  }

  /** flowchart TB con n nodos en cadena: alto, para forzar tandas y la reduccion de tamano. */
  private static Diagrama cadena( String id, int n ) {
    StringBuilder sb = new StringBuilder( "flowchart TB\n" );
    for ( int i = 0; i < n - 1; i++ ) {
      sb.append( "  n" ).append( i ).append( "[\"Step " ).append( i ).append( "\"] --> n" ).append( i + 1 ).append(
        "[\"Step " ).append( i + 1 ).append( "\"]\n" );
    }
    Diagrama d = new Diagrama();
    d.id = id;
    d.clase = Diagrama.FLUJO;
    d.mermaid = sb.toString();
    return d;
  }

  /** Todo el texto del SVG (parseado como XML: si no es XML valido, falla). */
  private static String texto( String xml ) throws Exception {
    noNulo( xml, "SVG" );
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware( true );
    Document d = f.newDocumentBuilder().parse( new ByteArrayInputStream( xml.getBytes( StandardCharsets.UTF_8 ) ) );
    igual( "svg", d.getDocumentElement().getLocalName(), "raiz svg" );
    StringBuilder sb = new StringBuilder();
    org.w3c.dom.NodeList ts = d.getElementsByTagNameNS( "*", "text" );
    for ( int i = 0; i < ts.getLength(); i++ ) {
      sb.append( ts.item( i ).getTextContent() ).append( '\n' );
    }
    return sb.toString();
  }

  /** Fraccion de pixeles que no son blancos. */
  private static double tinta( BufferedImage img ) {
    long n = 0;
    for ( int y = 0; y < img.getHeight(); y += 2 ) {
      for ( int x = 0; x < img.getWidth(); x += 2 ) {
        if ( !blanco( img.getRGB( x, y ) ) ) {
          n++;
        }
      }
    }
    return n / ( ( img.getWidth() / 2.0 ) * ( img.getHeight() / 2.0 ) );
  }

  /** Fraccion de pixeles blancos en las dos primeras y dos ultimas filas y columnas. */
  private static double bordeBlanco( BufferedImage img ) {
    long total = 0;
    long blancos = 0;
    int w = img.getWidth();
    int h = img.getHeight();
    for ( int k = 0; k < 2; k++ ) {
      for ( int x = 0; x < w; x++ ) {
        total += 2;
        blancos += ( blanco( img.getRGB( x, k ) ) ? 1 : 0 ) + ( blanco( img.getRGB( x, h - 1 - k ) ) ? 1 : 0 );
      }
      for ( int y = 0; y < h; y++ ) {
        total += 2;
        blancos += ( blanco( img.getRGB( k, y ) ) ? 1 : 0 ) + ( blanco( img.getRGB( w - 1 - k, y ) ) ? 1 : 0 );
      }
    }
    return (double) blancos / total;
  }

  private static boolean blanco( int rgb ) {
    return ( ( rgb >> 16 ) & 0xFF ) > 245 && ( ( rgb >> 8 ) & 0xFF ) > 245 && ( rgb & 0xFF ) > 245;
  }

  /** %PDF, mas de 1 KB, A4 (595x842 pt), al menos minPaginas, fuentes embebidas con ToUnicode. */
  private static void validarPdf( File pdf, int minPaginas ) throws IOException {
    archivo( pdf, 1025, "PDF" );
    byte[] b = Files.readAllBytes( pdf.toPath() );
    String s = new String( b, StandardCharsets.ISO_8859_1 );
    cierto( s.startsWith( "%PDF" ), "empieza con %PDF" );
    cierto( Pattern.compile( "/MediaBox\\s*\\[\\s*0 0 59[45](\\.\\d+)? 84[12](\\.\\d+)?\\s*\\]" ).matcher( s ).find(),
      "pagina A4" );
    Matcher m = Pattern.compile( "/Type\\s*/Page[^s]" ).matcher( s );
    int paginas = 0;
    while ( m.find() ) {
      paginas++;
    }
    cierto( paginas >= minPaginas, paginas + " paginas, se esperaban al menos " + minPaginas );
    contiene( s, "/FontFile", "fuentes embebidas" );
    contiene( s, "/ToUnicode", "texto seleccionable" );
  }

  /**
   * Abre la pagina en el Browser de SWT (IE11) fuera de la pantalla y devuelve: documentMode, cantidad de
   * svg y, por svg, "textosConContenido:conAnchoMayorQueCero:textContent" (Mermaid deja &lt;text&gt; vacios
   * en las aristas sin rotulo: esos no cuentan).
   */
  private static String enIe( File pagina, File captura ) throws Exception {
    Display d;
    try {
      d = new Display();
    } catch ( Throwable e ) {
      saltear( "sin Display de SWT: " + e );
      return null;
    }
    try {
      // IE solo hace el layout (y getBBox da ancho) con la ventana abierta: se abre fuera de la pantalla,
      // como ventana de herramientas (sin boton en la barra de tareas) y sin activarla: open() la traeria al
      // frente con el foco; setVisible(true) de una Shell no modal muestra con SW_SHOWNOACTIVATE.
      Shell s = new Shell( d, SWT.NO_TRIM | SWT.TOOL );
      s.setLayout( new FillLayout() );
      s.setBounds( -20000, -20000, 1200, 900 );
      s.setVisible( true );
      Browser b;
      try {
        b = new Browser( s, SWT.NONE );
      } catch ( Throwable e ) {
        saltear( "sin Browser de SWT: " + e );
        return null;
      }
      s.layout();
      igual( "ie", b.getBrowserType(), "el Browser de este SWT es IE" );
      final boolean[] cargo = new boolean[ 1 ];
      b.addProgressListener( new ProgressAdapter() {
        @Override
        public void completed( ProgressEvent event ) {
          cargo[ 0 ] = true;
        }
      } );
      b.setUrl( pagina.toURI().toString() );
      long limite = System.currentTimeMillis() + 30000;
      while ( !cargo[ 0 ] && System.currentTimeMillis() < limite ) {
        if ( !d.readAndDispatch() ) {
          Thread.sleep( 20 );
        }
      }
      cierto( cargo[ 0 ], "IE no termino de cargar" );
      igual( 1200, b.getSize().x, "ancho del Browser" );
      for ( int i = 0; i < 40; i++ ) {
        while ( d.readAndDispatch() ) {
          // bombear
        }
        Thread.sleep( 25 );
      }
      Object r = b.evaluate( "var r=[String(document.documentMode)],s=document.getElementsByTagName('svg');"
        + "r.push(String(s.length));for(var i=0;i<s.length;i++){var t=s[i].getElementsByTagName('text'),n=0,ok=0;"
        + "for(var j=0;j<t.length;j++){if(!/\\S/.test(t[j].textContent||'')){continue;}n++;"
        + "try{if(t[j].getBBox().width>0){ok++;}}catch(e){}}"
        + "r.push(n+':'+ok+':'+String(s[i].textContent).replace(/\\n/g,' '));}return r.join('\\n');" );
      noNulo( r, "evaluate en IE" );
      if ( Boolean.getBoolean( "pruebas.ie.captura" ) ) {
        capturar( d, s, b, captura );
      }
      return String.valueOf( r );
    } finally {
      d.dispose();
    }
  }

  /**
   * Solo a pedido (-Dpruebas.ie.captura=true): guarda una captura del Browser dibujado sobre una imagen
   * ({@code Control.print(GC)}), con la ventana fuera de la pantalla.
   */
  private static void capturar( Display d, Shell s, Browser b, File captura ) throws Exception {
    for ( int i = 0; i < 40; i++ ) {
      while ( d.readAndDispatch() ) {
        // bombear
      }
      Thread.sleep( 25 );
    }
    org.eclipse.swt.graphics.Point t = b.getSize();
    org.eclipse.swt.graphics.Image img = new org.eclipse.swt.graphics.Image( d, t.x, t.y );
    org.eclipse.swt.graphics.GC gc = new org.eclipse.swt.graphics.GC( img );
    try {
      b.print( gc );
      org.eclipse.swt.graphics.ImageLoader il = new org.eclipse.swt.graphics.ImageLoader();
      il.data = new org.eclipse.swt.graphics.ImageData[] { img.getImageData() };
      try ( OutputStream out = new FileOutputStream( captura ) ) {
        il.save( out, SWT.IMAGE_PNG );
      }
    } finally {
      gc.dispose();
      img.dispose();
    }
  }
}
