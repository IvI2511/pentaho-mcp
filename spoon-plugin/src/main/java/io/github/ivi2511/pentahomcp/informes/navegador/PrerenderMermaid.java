package io.github.ivi2511.pentahomcp.informes.navegador;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.graficos.SvgAPng;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.render.RenderHtml;
import io.github.ivi2511.pentahomcp.informes.tema.Paleta;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Mermaid a SVG estatico y a PNG con Chromium headless (diseno §3.5), con cache por
 * sha1(mermaid + colores).
 * <ul>
 * <li><b>SVG.</b> Una pagina temporal con todos los diagramas y la configuracion de §3.1
 * ({@link #configuracion(Tema)}, {@code htmlLabels:false}). Cada uno se dibuja con
 * {@code mermaid.render}, se le arreglan los rotulos ({@link #JS_ARREGLAR_ROTULOS}), se serializa con
 * XMLSerializer y queda en base64 en un atributo; una sola corrida {@code --dump-dom} trae todos. El SVG
 * resultante es XML bien formado, sin foreignObject, con {@code width}/{@code height} en px (los del
 * viewBox) y {@code style="max-width:100%"}; su id es unico por contenido ({@code plm<sha1>}), asi se
 * pueden poner varios en la misma pagina (IE11, HTML liviano).</li>
 * <li><b>PNG.</b> Los SVG se apilan en una pagina sin JS en posiciones absolutas conocidas; una corrida
 * {@code --screenshot --force-device-scale-factor=2} por cada tanda de hasta {@value #MAX_ALTO_CORRIDA} px
 * de alto, y se recorta cada caja con ImageIO (leyendo solo esa region). Un diagrama de mas de
 * {@value #MAX_LADO} px de lado se reduce para que el PNG no pase de 8000 px. Si la captura falla se
 * intenta Batik ({@link SvgAPng}).</li>
 * </ul>
 * Los que fallan no estan en el mapa: nunca lanza por un diagrama roto ni por falta de navegador.
 *
 * @dueño WP8
 */
public final class PrerenderMermaid {

  /** mermaid.min.js dentro del jar (lo pone WP7). */
  public static final String RECURSO_MERMAID = "io/github/ivi2511/pentahomcp/informes/vendor/mermaid.min.js";
  /** System property opcional: ruta a otro mermaid.min.js (gana sobre el del jar). */
  public static final String PROPIEDAD_MERMAID = "pentaho.live.mermaid";
  /** Escala de los PNG (device scale factor). */
  public static final int ESCALA = 2;
  /** Lado maximo de un diagrama en px CSS (el PNG queda de hasta 2x esto). */
  public static final int MAX_LADO = 4000;
  /** Alto maximo de una pagina de captura, en px CSS. */
  public static final int MAX_ALTO_CORRIDA = 8000;
  /** Presupuesto de tiempo virtual para que Mermaid termine (§3.2). */
  public static final int PRESUPUESTO_MS = 15000;

  private static final int SEPARACION = 16;
  /** Entra en la clave de la cache: cambiarlo invalida los SVG guardados. */
  private static final String VERSION = "mermaid-11.4.1|plive-3";

  /**
   * Funcion JS (ES5) {@code plArreglarRotulos(svg)}. Con {@code htmlLabels:false}, Mermaid 11.4.1 deja mal
   * los codigos que produce ReportMotor.escaparMermaid (probado en Edge con nodos, aristas y subgraph):
   * <ul>
   * <li>en los NODOS de un flowchart quedan literales ({@code #60;} se ve "&amp;#60;"), y en aristas y
   * titulos de subgraph quedan sueltos cuando van seguidos ({@code #38;#34;} se ve "&amp;&amp;#34;"). En todo
   * el texto de un flowchart se decodifican {@code &#NN; &#xHH; &lt; &gt; &amp; &quot; &apos;} en una
   * sola pasada (un nombre que de verdad diga "&amp;lt;" se veria "&lt;": se acepta);</li>
   * <li>a veces queda el marcador interno de Mermaid ({@code ﬂ°°34;} o {@code ﬂ°°34¶ß}): se decodifica en
   * cualquier diagrama, porque ese marcador nunca es texto real.</li>
   * </ul>
   * Mermaid parte el rotulo en un tspan por palabra y a veces corta un codigo al medio ({@code "<b>&ﬂ"} +
   * {@code "°°34;x"}): antes de decodificar, el pedazo se pasa al nodo de texto siguiente del mismo
   * {@code <text>}. El erDiagram ya sale bien y no se toca mas que por el marcador.
   */
  public static final String JS_ARREGLAR_ROTULOS = "function plArreglarRotulos(svg){"
    + "var E={lt:'<',gt:'>',amp:'&',quot:'\"',apos:\"'\",nbsp:'\\u00A0'};"
    + "var M=/\\uFB02\\u00B0(?:\\u00B0(\\d+)|(lt|gt|amp|quot|apos|nbsp))(?:;|\\u00B6\\u00DF)/g;"
    + "var H=/&(?:#(\\d+)|#x([0-9a-fA-F]+)|(lt|gt|amp|quot|apos));/g;"
    + "var C=/^(?:\\uFB02\\u00B0(?:\\u00B0\\d+|lt|gt|amp|quot|apos|nbsp)(?:;|\\u00B6\\u00DF)|&(?:#\\d+|#x[0-9a-fA-F]+|lt|gt|amp|quot|apos);)/;"
    + "var flujo=/^flowchart/.test(svg.getAttribute('aria-roledescription')||'');"
    + "var ts=svg.getElementsByTagName('text');"
    + "for(var i=0;i<ts.length;i++){"
    + "var w=document.createTreeWalker(ts[i],4,null,false),n,ns=[],j,v,k;"
    + "while((n=w.nextNode())){ns.push(n);}"
    + "for(j=0;j<ns.length-1;j++){v=ns[j].nodeValue||'';"
    + "k=Math.max(v.lastIndexOf('\\uFB02'),flujo?v.lastIndexOf('&'):-1);"
    + "if(k>=0&&!C.test(v.substring(k))&&C.test(v.substring(k)+(ns[j+1].nodeValue||''))){"
    + "ns[j+1].nodeValue=v.substring(k)+ns[j+1].nodeValue;ns[j].nodeValue=v.substring(0,k);}}"
    + "for(j=0;j<ns.length;j++){v=ns[j].nodeValue;if(!v){continue;}"
    + "if(v.indexOf('\\uFB02')>=0){v=v.replace(M,function(m,d,x){return d?String.fromCharCode(+d):E[x];});}"
    + "if(flujo&&v.indexOf('&')>=0){v=v.replace(H,function(m,d,x,y){"
    + "return d?String.fromCharCode(+d):x?String.fromCharCode(parseInt(x,16)):E[y];});}"
    + "if(v!==ns[j].nodeValue){ns[j].nodeValue=v;}}}}";

  /**
   * Caches acotadas (LRU por cantidad y por bytes): cada cambio de un diagrama de linaje o ER es una clave
   * nueva, y en una sesion larga de Spoon con muchas vistas previas o exportaciones crecian sin limite.
   */
  private static final Lru<Svg> CACHE_SVG = new Lru<>( 64, 32L << 20, s -> 2L * s.xml.length() );
  private static final Lru<byte[]> CACHE_PNG = new Lru<>( 64, 64L << 20, b -> b.length );
  /** Diagramas que Mermaid rechazo (error de sintaxis): no se reintentan. Los timeouts no entran. */
  private static final Lru<String> CACHE_ERROR = new Lru<>( 256, 1L << 20, x -> 2L * x.length() );
  private static final Object JS = new Object();
  private static volatile File mermaidJs;
  private static volatile String ultimoError;

  private static final Pattern DIV = Pattern.compile( "<div id=\"r(\\d+)\"((?:\\s+[\\w-]+=\"[^\"]*\")*)\\s*>" );
  private static final Pattern ATRIBUTO = Pattern.compile( "([\\w-]+)=\"([^\"]*)\"" );
  private static final Pattern HEX = Pattern.compile( "#[0-9a-fA-F]{6}" );

  private PrerenderMermaid() {
  }

  /** Un SVG ya renderizado y su tamano natural en px CSS. */
  public static final class Svg {
    public final String xml;
    public final int ancho;
    public final int alto;

    Svg( String xml, int ancho, int alto ) {
      this.xml = xml;
      this.ancho = ancho;
      this.alto = alto;
    }
  }

  /**
   * Mapa LRU sincronizado con tope de entradas y de bytes (lo que pesa cada valor lo dice {@code peso}).
   * La entrada recien puesta nunca se descarta, aunque sola pase el tope de bytes.
   */
  static final class Lru<V> {
    private final int maxEntradas;
    private final long maxBytes;
    private final ToLongFunction<V> peso;
    private final LinkedHashMap<String, V> m = new LinkedHashMap<>( 16, 0.75f, true );
    private long bytes;

    Lru( int maxEntradas, long maxBytes, ToLongFunction<V> peso ) {
      this.maxEntradas = maxEntradas;
      this.maxBytes = maxBytes;
      this.peso = peso;
    }

    synchronized V get( String k ) {
      return k == null ? null : m.get( k );
    }

    synchronized boolean containsKey( String k ) {
      return k != null && m.containsKey( k );
    }

    synchronized void put( String k, V v ) {
      if ( k == null || v == null ) {
        return;
      }
      V viejo = m.put( k, v );
      if ( viejo != null ) {
        bytes -= peso.applyAsLong( viejo );
      }
      bytes += peso.applyAsLong( v );
      Iterator<Map.Entry<String, V>> it = m.entrySet().iterator();
      while ( ( m.size() > maxEntradas || bytes > maxBytes ) && m.size() > 1 && it.hasNext() ) {
        Map.Entry<String, V> e = it.next();
        if ( e.getKey().equals( k ) ) {
          continue;
        }
        bytes -= peso.applyAsLong( e.getValue() );
        it.remove();
      }
    }

    synchronized void clear() {
      m.clear();
      bytes = 0;
    }

    synchronized int size() {
      return m.size();
    }

    synchronized long bytes() {
      return bytes;
    }
  }

  // ------------------------------------------------------------------------------------- API

  /** id de Diagrama -> SVG estatico (htmlLabels:false). Los que no se pudieron no estan en el mapa. */
  public static Map<String, String> svg( List<Diagrama> diagramas, Tema t ) throws Exception {
    return svg( diagramas, t, Progreso.NULO );
  }

  /** Igual que {@link #svg(List, Tema)}, cancelable. */
  public static Map<String, String> svg( List<Diagrama> diagramas, Tema t, Progreso p ) throws Exception {
    Map<String, String> r = new LinkedHashMap<>();
    for ( Map.Entry<String, Svg> e : svgs( diagramas, t, p ).entrySet() ) {
      r.put( e.getKey(), e.getValue().xml );
    }
    return r;
  }

  /** id de Diagrama -> bytes PNG a escala 2. Los que no se pudieron no estan en el mapa. */
  public static Map<String, byte[]> png( List<Diagrama> diagramas, Tema t ) throws Exception {
    return png( diagramas, t, Progreso.NULO );
  }

  /** Igual que {@link #png(List, Tema)}, cancelable. */
  public static Map<String, byte[]> png( List<Diagrama> diagramas, Tema t, Progreso p ) throws Exception {
    Map<String, byte[]> r = new LinkedHashMap<>();
    Map<String, Svg> svgs = svgs( diagramas, t, p );
    if ( svgs.isEmpty() ) {
      return r;
    }
    Map<String, String> claves = claves( diagramas, t );
    Map<String, Svg> pendientes = new LinkedHashMap<>();
    for ( Map.Entry<String, Svg> e : svgs.entrySet() ) {
      String clave = claves.get( e.getKey() );
      if ( clave != null && !CACHE_PNG.containsKey( clave ) ) {
        pendientes.put( clave, e.getValue() );
      }
    }
    if ( !pendientes.isEmpty() ) {
      File exe = Chromium.ubicar( navegador( t ) );
      if ( exe != null ) {
        capturar( pendientes, exe, p );
      }
      for ( Map.Entry<String, Svg> e : pendientes.entrySet() ) {
        if ( !CACHE_PNG.containsKey( e.getKey() ) ) {
          byte[] b = conBatik( e.getValue() );
          if ( b != null ) {
            CACHE_PNG.put( e.getKey(), b );
          }
        }
      }
    }
    for ( String id : svgs.keySet() ) {
      byte[] b = CACHE_PNG.get( claves.get( id ) );
      if ( b != null ) {
        r.put( id, b.clone() );
      }
    }
    return r;
  }

  /** Detalle de {@link #svg}: el SVG con su tamano. */
  public static Map<String, Svg> svgs( List<Diagrama> diagramas, Tema t, Progreso p ) throws Exception {
    Map<String, Svg> r = new LinkedHashMap<>();
    if ( diagramas == null || diagramas.isEmpty() ) {
      return r;
    }
    Map<String, String> claves = claves( diagramas, t );
    if ( claves.isEmpty() ) {
      return r;
    }
    Map<String, String> pendientes = new LinkedHashMap<>();
    for ( Diagrama d : diagramas ) {
      String clave = d == null ? null : claves.get( d.id );
      if ( clave != null && !CACHE_SVG.containsKey( clave ) && !CACHE_ERROR.containsKey( clave ) ) {
        pendientes.put( clave, d.mermaid );
      }
    }
    if ( !pendientes.isEmpty() ) {
      File exe = Chromium.ubicar( navegador( t ) );
      File js = mermaidJs();
      if ( exe == null ) {
        ultimoError = "No hay Edge ni Chrome";
      } else if ( js == null ) {
        ultimoError = "Falta " + RECURSO_MERMAID;
      } else {
        renderizar( pendientes, t, exe, js, p );
      }
    }
    for ( Map.Entry<String, String> e : claves.entrySet() ) {
      Svg s = CACHE_SVG.get( e.getValue() );
      if ( s != null ) {
        r.put( e.getKey(), s );
      }
    }
    return r;
  }

  /** true si hay navegador y mermaid.min.js: el pre-render puede andar. */
  public static boolean disponible( Tema t ) {
    return Chromium.ubicar( navegador( t ) ) != null && mermaidJs() != null;
  }

  /** El motivo del ultimo diagrama que no se pudo dibujar (para el log), o null. */
  public static String ultimoError() {
    return ultimoError;
  }

  /** Vacia las caches de SVG y PNG. */
  public static void olvidar() {
    CACHE_SVG.clear();
    CACHE_PNG.clear();
    CACHE_ERROR.clear();
  }

  /**
   * El objeto JS de {@code mermaid.initialize(...)} de §3.1, con los colores del tema: el de
   * {@code RenderHtml.configMermaid(t, true)} (htmlLabels:false, el que necesita IE11; el HTML interactivo y
   * el PDF usan htmlLabels:true), asi los colores y la tipografia son los mismos en todos los formatos. Si
   * RenderHtml fallara, la misma configuracion armada aca.
   */
  public static String configuracion( Tema t ) {
    try {
      String c = RenderHtml.configMermaid( t, true );
      if ( c != null && c.startsWith( "{" ) ) {
        return c;
      }
    } catch ( Throwable e ) {
      // respaldo local
    }
    return configuracionLocal( t );
  }

  static String configuracionLocal( Tema t ) {
    String primario = color( t == null || t.colores == null ? null : t.colores.primario, Tema.PRIMARIO );
    return "{startOnLoad:false,securityLevel:'strict',theme:'base',htmlLabels:false,"
      + "flowchart:{htmlLabels:false},class:{htmlLabels:false},"
      + "themeVariables:{fontFamily:'Segoe UI, Arial, sans-serif',primaryColor:'" + claro( primario ) + "',"
      + "primaryBorderColor:'" + primario + "',lineColor:'" + Tema.TEXTO_TENUE + "',primaryTextColor:'" + Tema.TEXTO
      + "'}}";
  }

  /**
   * mermaid.min.js como archivo local (lo copia una vez a %TEMP%\plive), o null si no esta ni en
   * {@value #PROPIEDAD_MERMAID} ni en el jar.
   */
  public static File mermaidJs() {
    String prop = System.getProperty( PROPIEDAD_MERMAID );
    if ( prop != null && !prop.trim().isEmpty() ) {
      File f = new File( prop.trim() );
      if ( f.isFile() ) {
        return f;
      }
    }
    File f = mermaidJs;
    if ( f != null && f.isFile() ) {
      return f;
    }
    synchronized ( JS ) {
      if ( mermaidJs != null && mermaidJs.isFile() ) {
        return mermaidJs;
      }
      ClassLoader cl = PrerenderMermaid.class.getClassLoader();
      byte[] b;
      try ( InputStream in = cl == null ? null : cl.getResourceAsStream( RECURSO_MERMAID ) ) {
        if ( in == null ) {
          return null;
        }
        b = leer( in );
      } catch ( IOException e ) {
        return null;
      }
      try {
        File dir = new File( Chromium.temporal(), "plive" );
        dir.mkdirs();
        File destino = new File( dir, "mermaid-" + sha1( b ).substring( 0, 10 ) + ".js" );
        if ( !destino.isFile() || destino.length() != b.length ) {
          File tmp = new File( dir, destino.getName() + "." + Chromium.nuevoId() + ".tmp" );
          try ( OutputStream out = new FileOutputStream( tmp ) ) {
            out.write( b );
          }
          try {
            Files.move( tmp.toPath(), destino.toPath(), StandardCopyOption.REPLACE_EXISTING );
          } catch ( IOException e ) {
            // otra JVM lo escribio a la vez: vale el suyo si esta completo
            tmp.delete();
            if ( !destino.isFile() || destino.length() != b.length ) {
              throw e;
            }
          }
        }
        mermaidJs = destino;
        return destino;
      } catch ( IOException e ) {
        ultimoError = "No se pudo copiar mermaid.min.js: " + e.getMessage();
        return null;
      }
    }
  }

  // ------------------------------------------------------------------------------------- SVG

  /** id de Diagrama -> clave de cache, solo los que tienen id y mermaid. */
  private static Map<String, String> claves( List<Diagrama> diagramas, Tema t ) {
    Map<String, String> r = new LinkedHashMap<>();
    if ( diagramas == null ) {
      return r;
    }
    String cfg = configuracion( t );
    for ( Diagrama d : diagramas ) {
      if ( d != null && d.id != null && d.mermaid != null && !d.mermaid.trim().isEmpty() ) {
        r.put( d.id, sha1( ( VERSION + "\n" + cfg + "\n" + d.mermaid ).getBytes( StandardCharsets.UTF_8 ) ) );
      }
    }
    return r;
  }

  private static void renderizar( Map<String, String> pendientes, Tema t, File exe, File js, Progreso p )
    throws Exception {
    List<String> claves = new ArrayList<>( pendientes.keySet() );
    File carpeta = Chromium.carpetaTrabajo();
    try {
      File pagina = new File( carpeta, "m.html" );
      escribir( pagina, paginaRender( claves, pendientes, t, js ) );
      Chromium.Corrida c;
      try {
        c = Chromium.ejecutar( exe, Arrays.asList( "--virtual-time-budget=" + PRESUPUESTO_MS, "--dump-dom",
          Chromium.url( pagina ) ), Chromium.TIMEOUT_MS, true, p );
      } catch ( Chromium.Agotado e ) {
        ultimoError = e.getMessage();
        return;
      }
      String dump = c.salida == null ? "" : new String( c.salida, StandardCharsets.UTF_8 );
      if ( c.codigo != 0 ) {
        ultimoError = "El navegador termino con codigo " + c.codigo + ": " + c.errores;
      }
      Matcher m = DIV.matcher( dump );
      while ( m.find() ) {
        int i = Integer.parseInt( m.group( 1 ) );
        if ( i < 0 || i >= claves.size() ) {
          continue;
        }
        Map<String, String> at = atributos( m.group( 2 ) );
        if ( "1".equals( at.get( "data-ok" ) ) && at.get( "data-svg" ) != null ) {
          try {
            String xml = new String( Base64.getDecoder().decode( at.get( "data-svg" ) ), StandardCharsets.UTF_8 );
            int w = entero( at.get( "data-w" ) );
            int h = entero( at.get( "data-h" ) );
            if ( xml.startsWith( "<svg" ) && w > 0 && h > 0 ) {
              CACHE_SVG.put( claves.get( i ), new Svg( xml, w, h ) );
            }
          } catch ( IllegalArgumentException e ) {
            ultimoError = "SVG ilegible: " + e.getMessage();
          }
        } else if ( at.get( "data-error" ) != null ) {
          ultimoError = desescapar( at.get( "data-error" ) );
          if ( "0".equals( at.get( "data-ok" ) ) && "1".equals( at.get( "data-mermaid" ) ) ) {
            CACHE_ERROR.put( claves.get( i ), ultimoError );
          }
        }
      }
    } finally {
      Chromium.borrar( carpeta );
    }
  }

  private static String paginaRender( List<String> claves, Map<String, String> textos, Tema t, File js ) {
    StringBuilder d = new StringBuilder( "[" );
    StringBuilder ids = new StringBuilder( "[" );
    for ( int i = 0; i < claves.size(); i++ ) {
      d.append( i == 0 ? "" : "," ).append( cadenaJs( textos.get( claves.get( i ) ) ) );
      ids.append( i == 0 ? "" : "," ).append( cadenaJs( "plm" + claves.get( i ).substring( 0, 12 ) ) );
    }
    d.append( ']' );
    ids.append( ']' );
    return "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><title>plive</title>\n"
      + "<script src=\"" + Chromium.url( js ) + "\"></script>\n</head><body><div id=\"salida\"></div>\n<script>\n"
      + JS_ARREGLAR_ROTULOS + "\n"
      + "(async function(){\n"
      + "var D=" + d + ",IDS=" + ids + ",out=document.getElementById('salida');\n"
      + "var listo=false;\n"
      + "try{mermaid.initialize(" + configuracion( t ) + ");listo=true;}catch(e){window.__error=String(e&&e.message||e);}\n"
      + "for(var i=0;i<D.length;i++){\n"
      + " var r=document.createElement('div');r.id='r'+i;out.appendChild(r);\n"
      + " try{\n"
      + "  if(!listo){throw new Error(window.__error||'mermaid no cargo');}\n"
      + "  r.setAttribute('data-mermaid','1');\n"
      + "  var res=await mermaid.render(IDS[i],D[i]);\n"
      + "  r.removeAttribute('data-mermaid');\n"
      + "  var caja=document.createElement('div');caja.innerHTML=res.svg;document.body.appendChild(caja);\n"
      + "  var svg=caja.querySelector('svg');if(!svg){throw new Error('sin svg');}\n"
      + "  plArreglarRotulos(svg);\n"
      + "  var vb=(svg.getAttribute('viewBox')||'').split(/[\\s,]+/).map(Number),w=0,h=0;\n"
      + "  if(vb.length===4&&vb[2]>0&&vb[3]>0){w=vb[2];h=vb[3];}else{var b=svg.getBBox();w=b.width+b.x;h=b.height+b.y;}\n"
      + "  w=Math.max(1,Math.ceil(w));h=Math.max(1,Math.ceil(h));\n"
      + "  svg.setAttribute('width',String(w));svg.setAttribute('height',String(h));\n"
      + "  svg.setAttribute('style','max-width:100%');\n"
      + "  var xml=new XMLSerializer().serializeToString(svg);\n"
      + "  caja.parentNode.removeChild(caja);\n"
      + "  r.setAttribute('data-w',String(w));r.setAttribute('data-h',String(h));\n"
      + "  r.setAttribute('data-svg',btoa(unescape(encodeURIComponent(xml))));r.setAttribute('data-ok','1');\n"
      + " }catch(e){r.setAttribute('data-ok','0');r.setAttribute('data-error',String(e&&e.message||e).substring(0,300));}\n"
      + "}\n"
      + "document.title='LISTO';\n"
      + "})();\n</script>\n</body></html>\n";
  }

  // ------------------------------------------------------------------------------------- PNG

  /** Una caja de la pagina de captura, en px CSS. */
  private static final class Caja {
    final String clave;
    final int y;
    final int ancho;
    final int alto;

    Caja( String clave, int y, int ancho, int alto ) {
      this.clave = clave;
      this.y = y;
      this.ancho = ancho;
      this.alto = alto;
    }
  }

  private static void capturar( Map<String, Svg> pendientes, File exe, Progreso p ) throws Exception {
    List<List<Caja>> tandas = new ArrayList<>();
    List<Caja> tanda = new ArrayList<>();
    int y = 0;
    for ( Map.Entry<String, Svg> e : pendientes.entrySet() ) {
      Svg s = e.getValue();
      double f = Math.min( 1d, (double) MAX_LADO / Math.max( s.ancho, s.alto ) );
      int w = Math.max( 1, (int) Math.round( s.ancho * f ) );
      int h = Math.max( 1, (int) Math.round( s.alto * f ) );
      if ( !tanda.isEmpty() && y + h > MAX_ALTO_CORRIDA ) {
        tandas.add( tanda );
        tanda = new ArrayList<>();
        y = 0;
      }
      tanda.add( new Caja( e.getKey(), y, w, h ) );
      y += h + SEPARACION;
    }
    if ( !tanda.isEmpty() ) {
      tandas.add( tanda );
    }
    for ( List<Caja> t : tandas ) {
      if ( p != null && p.cancelado() ) {
        return;
      }
      capturarTanda( t, pendientes, exe, p );
    }
  }

  private static void capturarTanda( List<Caja> cajas, Map<String, Svg> svgs, File exe, Progreso p )
    throws Exception {
    int ancho = 400;
    int alto = 200;
    StringBuilder html = new StringBuilder( "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><style>"
      + "html,body{margin:0;padding:0;background:#FFFFFF;overflow:hidden}"
      + ".b{position:absolute;left:0;overflow:hidden;background:#FFFFFF}"
      + ".b svg{width:100%!important;height:100%!important;max-width:none!important;display:block}"
      + "</style></head><body>\n" );
    for ( Caja c : cajas ) {
      html.append( "<div class=\"b\" style=\"top:" ).append( c.y ).append( "px;width:" ).append( c.ancho ).append(
        "px;height:" ).append( c.alto ).append( "px\">" ).append( svgs.get( c.clave ).xml ).append( "</div>\n" );
      ancho = Math.max( ancho, c.ancho );
      alto = Math.max( alto, c.y + c.alto );
    }
    html.append( "</body></html>\n" );
    File carpeta = Chromium.carpetaTrabajo();
    try {
      File pagina = new File( carpeta, "p.html" );
      File captura = new File( carpeta, "p.png" );
      escribir( pagina, html.toString() );
      Chromium.Corrida c;
      try {
        c = Chromium.ejecutar( exe, Arrays.asList( "--force-device-scale-factor=" + ESCALA, "--window-size=" + ancho
          + "," + alto, "--screenshot=" + captura.getAbsolutePath(), Chromium.url( pagina ) ), Chromium.TIMEOUT_MS,
          false, p );
      } catch ( Chromium.Agotado e ) {
        ultimoError = e.getMessage();
        return;
      }
      if ( !captura.isFile() || captura.length() == 0 ) {
        ultimoError = "Sin captura (codigo " + c.codigo + "): " + c.errores;
        return;
      }
      recortar( captura, ancho, cajas );
    } finally {
      Chromium.borrar( carpeta );
    }
  }

  /** Lee solo la region de cada caja (no carga la captura entera en memoria). */
  private static void recortar( File captura, int anchoCss, List<Caja> cajas ) throws IOException {
    Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName( "png" );
    if ( !it.hasNext() ) {
      throw new IOException( "ImageIO no lee PNG" );
    }
    ImageReader lector = it.next();
    try ( ImageInputStream in = ImageIO.createImageInputStream( captura ) ) {
      lector.setInput( in, false, true );
      int wImg = lector.getWidth( 0 );
      int hImg = lector.getHeight( 0 );
      double f = (double) wImg / anchoCss;
      for ( Caja c : cajas ) {
        int x0 = 0;
        int y0 = (int) Math.round( c.y * f );
        int w = Math.min( wImg - x0, (int) Math.round( c.ancho * f ) );
        int h = Math.min( hImg - y0, (int) Math.round( c.alto * f ) );
        if ( w <= 0 || h <= 0 ) {
          continue;
        }
        ImageReadParam prm = lector.getDefaultReadParam();
        prm.setSourceRegion( new Rectangle( x0, y0, w, h ) );
        BufferedImage img = lector.read( 0, prm );
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ImageIO.write( img, "png", bo );
        CACHE_PNG.put( c.clave, bo.toByteArray() );
      }
    } finally {
      lector.dispose();
    }
  }

  /** Intento 2 de §3.5: Batik sobre el SVG de Mermaid. Null si no se puede. */
  private static byte[] conBatik( Svg s ) {
    try {
      int w = (int) Math.min( (long) MAX_LADO * ESCALA, (long) s.ancho * ESCALA );
      return SvgAPng.png( s.xml, Math.max( 1, w ) );
    } catch ( Throwable e ) {
      ultimoError = "Batik: " + e;
      return null;
    }
  }

  // ------------------------------------------------------------------------------------- utilidades

  private static String navegador( Tema t ) {
    return t == null ? null : t.navegador;
  }

  /** "#RRGGBB" valido o el defecto: nunca entra texto del usuario sin validar al JS. */
  private static String color( String c, String defecto ) {
    return c != null && HEX.matcher( c.trim() ).matches() ? c.trim().toUpperCase() : defecto;
  }

  /** 10 % del color sobre blanco (Paleta.claro; si todavia no esta, la misma cuenta aca). */
  private static String claro( String hex ) {
    try {
      String c = Paleta.claro( hex, 0.10f );
      if ( c != null && HEX.matcher( c ).matches() ) {
        return c.toUpperCase();
      }
    } catch ( RuntimeException e ) {
      // Paleta (WP6) sin implementar
    }
    int v = Integer.parseInt( hex.substring( 1 ), 16 );
    int r = mezcla( ( v >> 16 ) & 0xFF );
    int g = mezcla( ( v >> 8 ) & 0xFF );
    int b = mezcla( v & 0xFF );
    return String.format( "#%02X%02X%02X", r, g, b );
  }

  private static int mezcla( int canal ) {
    return (int) Math.round( canal * 0.10 + 255 * 0.90 );
  }

  /** Cadena JS entre comillas dobles, segura dentro de un script en HTML. */
  static String cadenaJs( String s ) {
    StringBuilder sb = new StringBuilder( "\"" );
    for ( int i = 0; i < s.length(); i++ ) {
      char c = s.charAt( i );
      switch ( c ) {
        case '"':
          sb.append( "\\\"" );
          break;
        case '\\':
          sb.append( "\\\\" );
          break;
        case '\n':
          sb.append( "\\n" );
          break;
        case '\r':
          sb.append( "\\r" );
          break;
        case '\t':
          sb.append( "\\t" );
          break;
        case '<':
        case '>':
        case '&':
        case ' ':
        case ' ':
          sb.append( String.format( "\\u%04x", (int) c ) );
          break;
        default:
          if ( c < 0x20 ) {
            sb.append( String.format( "\\u%04x", (int) c ) );
          } else {
            sb.append( c );
          }
      }
    }
    return sb.append( '"' ).toString();
  }

  private static Map<String, String> atributos( String s ) {
    Map<String, String> r = new LinkedHashMap<>();
    Matcher m = ATRIBUTO.matcher( s );
    while ( m.find() ) {
      r.put( m.group( 1 ), m.group( 2 ) );
    }
    return r;
  }

  private static String desescapar( String s ) {
    return s.replace( "&quot;", "\"" ).replace( "&lt;", "<" ).replace( "&gt;", ">" ).replace( "&#39;", "'" ).replace(
      "&nbsp;", " " ).replace( "&amp;", "&" );
  }

  private static int entero( String s ) {
    try {
      return s == null ? 0 : (int) Math.ceil( Double.parseDouble( s ) );
    } catch ( NumberFormatException e ) {
      return 0;
    }
  }

  private static void escribir( File f, String texto ) throws IOException {
    try ( OutputStream out = new FileOutputStream( f ) ) {
      out.write( texto.getBytes( StandardCharsets.UTF_8 ) );
    }
  }

  private static byte[] leer( InputStream in ) throws IOException {
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    byte[] buf = new byte[ 65536 ];
    int n;
    while ( ( n = in.read( buf ) ) > 0 ) {
      bo.write( buf, 0, n );
    }
    return bo.toByteArray();
  }

  static String sha1( byte[] b ) {
    try {
      byte[] h = MessageDigest.getInstance( "SHA-1" ).digest( b );
      StringBuilder sb = new StringBuilder();
      for ( byte x : h ) {
        sb.append( String.format( "%02x", x & 0xFF ) );
      }
      return sb.toString();
    } catch ( NoSuchAlgorithmException e ) {
      throw new IllegalStateException( e );
    }
  }
}
