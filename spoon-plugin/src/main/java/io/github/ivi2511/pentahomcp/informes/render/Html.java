package io.github.ivi2511.pentahomcp.informes.render;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.ivi2511.pentahomcp.informes.modelo.Aviso;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.Grafico;
import io.github.ivi2511.pentahomcp.informes.modelo.Imagen;
import io.github.ivi2511.pentahomcp.informes.modelo.Indicadores;
import io.github.ivi2511.pentahomcp.informes.modelo.Lista;
import io.github.ivi2511.pentahomcp.informes.modelo.Parrafo;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.modelo.Tabla;

/**
 * Utilidades de texto para los renderizadores (diseno §3.1 y §3.3): escapes de HTML, el poco Markdown
 * de la narrativa (**negrita**, *cursiva*, vinetas "- " y saltos de linea), limpieza de SVG para
 * meterlo inline (sin prologo XML, sin script, con tamano explicito para IE11), colores validados y
 * escritura atomica de archivos.
 * <p>
 * Todo texto que viene del usuario o del documento (nombres de steps, notas, SQL, descripciones) pasa
 * por {@link #esc}. Nunca se acepta HTML crudo.
 *
 * @dueño WP7
 */
public final class Html {

  /** Ancho maximo que se le da a un SVG en la vista previa IE11 (el contenido mide unos 840 px). */
  public static final int ANCHO_MAX_SVG = 840;

  private static final Pattern COLOR = Pattern.compile( "#[0-9A-Fa-f]{6}" );
  private static final Pattern PROLOGO = Pattern.compile( "<\\?xml[^>]*\\?>|<!DOCTYPE[^>]*>", Pattern.CASE_INSENSITIVE );
  private static final Pattern SCRIPT = Pattern.compile( "<script\\b[\\s\\S]*?(</script\\s*>|$)",
    Pattern.CASE_INSENSITIVE );
  private static final Pattern EVENTO = Pattern.compile( "\\s+on[a-zA-Z]+\\s*=\\s*(\"[^\"]*\"|'[^']*')" );
  private static final Pattern RAIZ_SVG = Pattern.compile( "<svg\\b[^>]*>", Pattern.CASE_INSENSITIVE );
  private static final Pattern VIEWBOX = Pattern.compile(
    "viewBox\\s*=\\s*\"\\s*[-0-9.eE]+[\\s,]+[-0-9.eE]+[\\s,]+([0-9.eE]+)[\\s,]+([0-9.eE]+)\\s*\"" );

  private Html() {
  }

  // ---------------------------------------------------------------- escapes

  /** Escapa &amp; &lt; &gt; " y ' para texto y atributos HTML. null = "". */
  public static String esc( String s ) {
    if ( s == null ) {
      return "";
    }
    StringBuilder sb = new StringBuilder( s.length() + 16 );
    for ( int i = 0; i < s.length(); i++ ) {
      char c = s.charAt( i );
      switch ( c ) {
        case '&':
          sb.append( "&amp;" );
          break;
        case '<':
          sb.append( "&lt;" );
          break;
        case '>':
          sb.append( "&gt;" );
          break;
        case '"':
          sb.append( "&quot;" );
          break;
        case '\'':
          sb.append( "&#39;" );
          break;
        case '\r':
          break;
        default:
          if ( c < 0x20 && c != '\n' && c != '\t' ) {
            sb.append( ' ' );
          } else {
            sb.append( c );
          }
      }
    }
    return sb.toString();
  }

  /** Como {@link #esc} y ademas los saltos de linea pasan a &lt;br&gt; (celdas, avisos). */
  public static String escLineas( String s ) {
    return esc( s ).replace( "\n", "<br>" );
  }

  /** Desde cuantos caracteres sin espacios una palabra de una celda es "larga" (una ruta, un ${...}). */
  public static final int PALABRA_LARGA = 30;

  /** Tramo maximo sin punto de corte dentro de una palabra larga (ver {@link #escCortable}). */
  static final int TRAMO_CORTABLE = 20;

  /** Despues de estos se puede cortar una palabra larga. */
  private static final String SEPARADORES = "/\\._-";

  /** Si s tiene una tira de mas de n caracteres sin espacios en blanco. */
  public static boolean palabraLarga( String s, int n ) {
    if ( s == null ) {
      return false;
    }
    int corrida = 0;
    for ( int i = 0; i < s.length(); i++ ) {
      corrida = Character.isWhitespace( s.charAt( i ) ) ? 0 : corrida + 1;
      if ( corrida > n ) {
        return true;
      }
    }
    return false;
  }

  /**
   * Como {@link #escLineas}, y dentro de cada palabra de mas de {@link #PALABRA_LARGA} caracteres agrega &lt;wbr&gt;
   * despues de / \ . _ - y, si un tramo sigue sin donde cortarse, cada {@link #TRAMO_CORTABLE} caracteres. Asi una
   * ruta o un ${Internal.Entry.Current.Directory}/... se corta por ahi y el ancho minimo de su columna es el de un
   * tramo, no el de toda la ruta: sin eso la tabla se salia de la pagina del PDF y Chromium recortaba la ultima
   * columna. El &lt;wbr&gt; no agrega caracteres: copiar la ruta la copia entera.
   */
  public static String escCortable( String s ) {
    if ( s == null ) {
      return "";
    }
    StringBuilder sb = new StringBuilder( s.length() + 32 );
    int i = 0;
    while ( i < s.length() ) {
      int j = i;
      while ( j < s.length() && !Character.isWhitespace( s.charAt( j ) ) ) {
        j++;
      }
      if ( j == i ) {
        sb.append( escLineas( s.substring( i, i + 1 ) ) );
        i++;
        continue;
      }
      String palabra = s.substring( i, j );
      if ( palabra.length() <= PALABRA_LARGA ) {
        sb.append( esc( palabra ) );
      } else {
        int tramo = 0;
        for ( int k = 0; k < palabra.length(); k++ ) {
          char c = palabra.charAt( k );
          sb.append( esc( String.valueOf( c ) ) );
          tramo++;
          if ( k == palabra.length() - 1 || Character.isHighSurrogate( c ) ) {
            continue;
          }
          // despues de una tira de separadores ("../" corta una vez), o cada TRAMO_CORTABLE si no hay ninguno
          boolean separador = SEPARADORES.indexOf( c ) >= 0 && SEPARADORES.indexOf( palabra.charAt( k + 1 ) ) < 0;
          if ( separador || tramo >= TRAMO_CORTABLE ) {
            sb.append( "<wbr>" );
            tramo = 0;
          }
        }
      }
      i = j;
    }
    return sb.toString();
  }

  /** Texto para una cadena CSS entre comillas dobles (content: "..."): escapa \ y " y saca saltos. */
  public static String cadenaCss( String s ) {
    if ( s == null ) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for ( int i = 0; i < s.length(); i++ ) {
      char c = s.charAt( i );
      if ( c == '\\' || c == '"' ) {
        sb.append( '\\' ).append( c );
      } else if ( c == '<' ) {
        sb.append( "\\3C " ); // nunca "</style" dentro del bloque de estilos
      } else if ( c < 0x20 ) {
        sb.append( ' ' );
      } else {
        sb.append( c );
      }
    }
    return sb.toString();
  }

  /** La lista, o una vacia si es null (el JSON puede traer "bloques": null). */
  public static <T> List<T> lista( List<T> l ) {
    return l == null ? java.util.Collections.<T>emptyList() : l;
  }

  /** Id seguro para un ancla o un nombre de archivo: [a-z0-9_-], sin repetir guiones; "x" si queda vacio. */
  public static String idSeguro( String s ) {
    if ( s == null ) {
      return "x";
    }
    String t = java.text.Normalizer.normalize( s, java.text.Normalizer.Form.NFD ).replaceAll( "\\p{M}+", "" );
    StringBuilder sb = new StringBuilder();
    boolean guion = false;
    for ( int i = 0; i < t.length() && sb.length() < 60; i++ ) {
      char c = Character.toLowerCase( t.charAt( i ) );
      if ( ( c >= 'a' && c <= 'z' ) || ( c >= '0' && c <= '9' ) || c == '_' ) {
        sb.append( c );
        guion = false;
      } else if ( !guion && sb.length() > 0 ) {
        sb.append( '-' );
        guion = true;
      }
    }
    while ( sb.length() > 0 && sb.charAt( sb.length() - 1 ) == '-' ) {
      sb.setLength( sb.length() - 1 );
    }
    return sb.length() == 0 ? "x" : sb.toString();
  }

  // ---------------------------------------------------------------- poco Markdown

  /** Tipo de un trozo de texto en linea. */
  public enum Estilo {
    TEXTO, NEGRITA, CURSIVA
  }

  /** Un trozo de texto en linea con su estilo; {@link #texto} va sin escapar. */
  public static final class Trozo {
    public final Estilo estilo;
    public final String texto;

    Trozo( Estilo estilo, String texto ) {
      this.estilo = estilo;
      this.texto = texto;
    }
  }

  /**
   * Parte una linea en trozos: **negrita** y *cursiva* (sin anidar; el texto de adentro no empieza ni
   * termina con espacio). Lo que no cierra queda como texto. Lo usan HTML, Markdown y PPTX.
   */
  public static List<Trozo> trozos( String linea ) {
    List<Trozo> l = new ArrayList<>();
    if ( linea == null || linea.isEmpty() ) {
      return l;
    }
    StringBuilder texto = new StringBuilder();
    int i = 0;
    int n = linea.length();
    while ( i < n ) {
      char c = linea.charAt( i );
      if ( c == '*' ) {
        boolean doble = i + 1 < n && linea.charAt( i + 1 ) == '*';
        String marca = doble ? "**" : "*";
        int desde = i + marca.length();
        int hasta = linea.indexOf( marca, desde );
        if ( !doble ) {
          // *cursiva*: el cierre no puede ser parte de un "**"
          while ( hasta > 0 && hasta + 1 < n && linea.charAt( hasta + 1 ) == '*' ) {
            hasta = linea.indexOf( '*', hasta + 2 );
          }
        }
        if ( hasta > desde ) {
          String adentro = linea.substring( desde, hasta );
          boolean bien = !Character.isWhitespace( adentro.charAt( 0 ) )
            && !Character.isWhitespace( adentro.charAt( adentro.length() - 1 ) )
            && ( doble || adentro.indexOf( '*' ) < 0 );
          if ( bien ) {
            if ( texto.length() > 0 ) {
              l.add( new Trozo( Estilo.TEXTO, texto.toString() ) );
              texto.setLength( 0 );
            }
            l.add( new Trozo( doble ? Estilo.NEGRITA : Estilo.CURSIVA, adentro ) );
            i = hasta + marca.length();
            continue;
          }
        }
        texto.append( marca );
        i += marca.length();
        continue;
      }
      texto.append( c );
      i++;
    }
    if ( texto.length() > 0 ) {
      l.add( new Trozo( Estilo.TEXTO, texto.toString() ) );
    }
    return l;
  }

  /** Una linea de texto con poco Markdown a HTML escapado (&lt;strong&gt;, &lt;em&gt;). */
  public static String enLinea( String linea ) {
    StringBuilder sb = new StringBuilder();
    for ( Trozo t : trozos( linea ) ) {
      switch ( t.estilo ) {
        case NEGRITA:
          sb.append( "<strong>" ).append( esc( t.texto ) ).append( "</strong>" );
          break;
        case CURSIVA:
          sb.append( "<em>" ).append( esc( t.texto ) ).append( "</em>" );
          break;
        default:
          sb.append( esc( t.texto ) );
      }
    }
    return sb.toString();
  }

  /** Un grupo de lineas: parrafo o lista de vinetas. */
  public static final class Grupo {
    /** true = lineas que empezaban con "- " (ya sin el guion). */
    public final boolean lista;
    public final List<String> lineas = new ArrayList<>();

    Grupo( boolean lista ) {
      this.lista = lista;
    }
  }

  /**
   * Parte un texto en grupos: las lineas que empiezan con "- " (o "* ") forman una lista; las demas,
   * parrafos (una linea en blanco corta el parrafo). Lo usan HTML, Markdown y PPTX.
   */
  public static List<Grupo> grupos( String texto ) {
    List<Grupo> l = new ArrayList<>();
    if ( texto == null ) {
      return l;
    }
    Grupo actual = null;
    for ( String crudo : texto.replace( "\r\n", "\n" ).replace( '\r', '\n' ).split( "\n", -1 ) ) {
      String linea = crudo.trim();
      if ( linea.isEmpty() ) {
        actual = null;
        continue;
      }
      boolean vineta = linea.startsWith( "- " ) || linea.startsWith( "* " );
      if ( vineta ) {
        linea = linea.substring( 2 ).trim();
      }
      if ( actual == null || actual.lista != vineta ) {
        actual = new Grupo( vineta );
        l.add( actual );
      }
      actual.lineas.add( linea );
    }
    return l;
  }

  /** Texto con poco Markdown a HTML: &lt;p&gt; con &lt;br&gt; entre lineas y &lt;ul&gt; para las vinetas. */
  /**
   * Un parrafo de la seccion sin lo que ya muestra su resumen (va arriba, destacado): null si repetia el
   * resumen entero; el resto si empezaba con el; si no, el texto tal cual. El resumen de «Resumen» y su
   * parrafo (resumen mas las metricas, para PowerPoint) decian la misma frase dos veces.
   */
  public static String sinResumen( String texto, String resumen ) {
    if ( texto == null || resumen == null || resumen.trim().isEmpty() ) {
      return texto;
    }
    String t = texto.trim();
    String r = resumen.trim();
    if ( t.equals( r ) ) {
      return null;
    }
    if ( t.startsWith( r ) && t.length() > r.length() && Character.isWhitespace( t.charAt( r.length() ) ) ) {
      String resto = t.substring( r.length() ).trim();
      return resto.isEmpty() ? null : resto;
    }
    return texto;
  }

  /**
   * El bloque para escribir debajo del resumen de la seccion: un parrafo (no de IA) que repetia el resumen
   * se saltea (null) o queda solo con lo que agrega; cualquier otro bloque, tal cual.
   */
  public static Bloque sinRepetirResumen( Bloque b, String resumen ) {
    if ( !( b instanceof Parrafo ) || b.ia ) {
      return b;
    }
    Parrafo pa = (Parrafo) b;
    String resto = sinResumen( pa.texto, resumen );
    if ( resto == null ) {
      return null;
    }
    if ( resto.equals( pa.texto ) ) {
      return b;
    }
    Parrafo c = new Parrafo( resto );
    c.id = pa.id;
    c.titulo = pa.titulo;
    c.nota = pa.nota;
    return c;
  }

  /**
   * Seccion corta para imprimir: sin diagramas, graficos ni imagenes y con poco texto y pocas filas. En
   * el PDF no empieza pagina nueva (validacion o conexiones dejaban paginas casi vacias); va entera.
   */
  public static boolean corta( Seccion s ) {
    int peso = s.resumen == null ? 0 : 1 + s.resumen.length() / 160;
    peso += s.motivo == null ? 0 : 1 + s.motivo.length() / 160;
    for ( Bloque b : lista( s.bloques ) ) {
      if ( b == null ) {
        continue;
      }
      if ( b instanceof Diagrama || b instanceof Grafico || b instanceof Imagen ) {
        return false;
      }
      if ( b instanceof Tabla ) {
        Tabla t = (Tabla) b;
        peso += 2 + lista( t.filas ).size() + ( t.pie == null ? 0 : 1 );
      } else if ( b instanceof Indicadores ) {
        peso += 3 + lista( ( (Indicadores) b ).items ).size() / 4;
      } else if ( b instanceof Lista ) {
        peso += 1 + lista( ( (Lista) b ).items ).size();
      } else if ( b instanceof Parrafo ) {
        String x = ( (Parrafo) b ).texto;
        peso += 1 + ( x == null ? 0 : x.length() / 160 );
      } else if ( b instanceof Aviso ) {
        String x = ( (Aviso) b ).texto;
        peso += 2 + ( x == null ? 0 : x.length() / 160 );
      } else {
        peso += 2;
      }
      peso += b.nota == null ? 0 : 1;
    }
    return peso <= PESO_CORTA;
  }

  /** Tope de {@link #corta}: unas 14 lineas (indicadores y una tabla chica, o un par de parrafos). */
  static final int PESO_CORTA = 14;

  public static String parrafos( String texto, String clase ) {
    StringBuilder sb = new StringBuilder();
    String c = clase == null ? "" : " class=\"" + esc( clase ) + "\"";
    for ( Grupo g : grupos( texto ) ) {
      if ( g.lista ) {
        sb.append( "<ul" ).append( c ).append( '>' );
        for ( String li : g.lineas ) {
          sb.append( "<li>" ).append( enLinea( li ) ).append( "</li>" );
        }
        sb.append( "</ul>\n" );
      } else {
        sb.append( "<p" ).append( c ).append( '>' );
        for ( int i = 0; i < g.lineas.size(); i++ ) {
          if ( i > 0 ) {
            sb.append( "<br>" );
          }
          sb.append( enLinea( g.lineas.get( i ) ) );
        }
        sb.append( "</p>\n" );
      }
    }
    return sb.toString();
  }

  // ---------------------------------------------------------------- SVG

  /**
   * Prepara un SVG para meterlo inline en el HTML: saca el prologo XML y el DOCTYPE, cualquier
   * &lt;script&gt; y los atributos on*; y, si la raiz no tiene ancho y alto en pixeles (Mermaid pone
   * width="100%"), se los pone desde el viewBox, achicando a {@code anchoMax} si hace falta. Sin eso IE11
   * dibuja el SVG con 150 px de alto.
   *
   * @return el SVG limpio, o null si no hay un &lt;svg&gt;.
   */
  public static String svgInline( String svg, int anchoMax ) {
    if ( svg == null ) {
      return null;
    }
    String s = PROLOGO.matcher( svg ).replaceAll( "" );
    s = SCRIPT.matcher( s ).replaceAll( "" );
    s = EVENTO.matcher( s ).replaceAll( "" );
    int inicio = s.toLowerCase( Locale.ROOT ).indexOf( "<svg" );
    if ( inicio < 0 ) {
      return null;
    }
    s = s.substring( inicio ).trim();
    Matcher m = RAIZ_SVG.matcher( s );
    if ( !m.find() ) {
      return null;
    }
    String raiz = m.group();
    String nueva = tamanoRaiz( raiz, anchoMax );
    return nueva + s.substring( m.end() );
  }

  /** La etiqueta raiz con width y height numericos (sacados del viewBox si faltan o son "%"). */
  static String tamanoRaiz( String raiz, int anchoMax ) {
    double w = numero( atributo( raiz, "width" ) );
    double h = numero( atributo( raiz, "height" ) );
    Matcher vb = VIEWBOX.matcher( raiz );
    double vw = -1;
    double vh = -1;
    if ( vb.find() ) {
      vw = numero( vb.group( 1 ) );
      vh = numero( vb.group( 2 ) );
    }
    if ( ( w <= 0 || h <= 0 ) && vw > 0 && vh > 0 ) {
      if ( w > 0 ) {
        h = w * vh / vw;
      } else if ( h > 0 ) {
        w = h * vw / vh;
      } else {
        w = vw;
        h = vh;
      }
    }
    if ( w <= 0 || h <= 0 ) {
      return raiz;
    }
    if ( anchoMax > 0 && w > anchoMax ) {
      h = h * anchoMax / w;
      w = anchoMax;
    }
    String r = quitarAtributo( quitarAtributo( raiz, "width" ), "height" );
    // Mermaid pone style="max-width: 1408px;" en la raiz: le gana al max-width:100% del CSS y el SVG se sale
    // de la hoja (probado en IE11). Se saca esa declaracion; el resto del style queda.
    Matcher st = Pattern.compile( "\\sstyle\\s*=\\s*\"([^\"]*)\"" ).matcher( r );
    if ( st.find() ) {
      String estilo = st.group( 1 ).replaceAll( "(?i)max-width\\s*:\\s*[^;]*;?", "" ).trim();
      r = r.substring( 0, st.start() ) + ( estilo.isEmpty() ? "" : " style=\"" + estilo + "\"" ) + r.substring( st
        .end() );
    }
    String extra = " width=\"" + redondo( w ) + "\" height=\"" + redondo( h ) + "\"";
    int fin = r.endsWith( "/>" ) ? r.length() - 2 : r.length() - 1;
    return r.substring( 0, fin ) + extra + r.substring( fin );
  }

  private static String atributo( String etiqueta, String nombre ) {
    Matcher m = Pattern.compile( "\\s" + nombre + "\\s*=\\s*\"([^\"]*)\"" ).matcher( etiqueta );
    return m.find() ? m.group( 1 ) : null;
  }

  private static String quitarAtributo( String etiqueta, String nombre ) {
    return etiqueta.replaceAll( "\\s" + nombre + "\\s*=\\s*\"[^\"]*\"", "" );
  }

  /** "640", "640px" o "640.5" -> numero; "100%", null o basura -> -1. */
  private static double numero( String s ) {
    if ( s == null ) {
      return -1;
    }
    String t = s.trim();
    if ( t.endsWith( "px" ) ) {
      t = t.substring( 0, t.length() - 2 ).trim();
    }
    try {
      return Double.parseDouble( t );
    } catch ( NumberFormatException e ) {
      return -1;
    }
  }

  private static String redondo( double v ) {
    long r = Math.round( v * 10 );
    return r % 10 == 0 ? Long.toString( r / 10 ) : String.format( Locale.ROOT, "%.1f", v );
  }

  // ---------------------------------------------------------------- colores y datos

  /** El color si es "#RRGGBB" (en mayusculas); si no, el por defecto. Evita meter cualquier cosa en el CSS. */
  public static String color( String hex, String porDefecto ) {
    if ( hex != null && COLOR.matcher( hex.trim() ).matches() ) {
      return hex.trim().toUpperCase( Locale.ROOT );
    }
    return porDefecto;
  }

  /** El color mezclado con blanco (fraccion 0.10 = 10 % del color). Respaldo local de Paleta.claro. */
  public static String claro( String hex, float fraccion ) {
    String h = color( hex, "#000000" );
    int r = Integer.parseInt( h.substring( 1, 3 ), 16 );
    int g = Integer.parseInt( h.substring( 3, 5 ), 16 );
    int b = Integer.parseInt( h.substring( 5, 7 ), 16 );
    r = Math.round( 255 - ( 255 - r ) * fraccion );
    g = Math.round( 255 - ( 255 - g ) * fraccion );
    b = Math.round( 255 - ( 255 - b ) * fraccion );
    return String.format( Locale.ROOT, "#%02X%02X%02X", r, g, b );
  }

  /** "data:image/png;base64,..." para un &lt;img&gt;. */
  public static String dataUri( String mime, byte[] datos ) {
    String m = mime == null || mime.trim().isEmpty() ? "image/png" : mime.trim();
    if ( !m.matches( "image/[a-z0-9.+-]+" ) ) {
      m = "image/png";
    }
    return "data:" + m + ";base64," + java.util.Base64.getEncoder().encodeToString( datos );
  }

  // ---------------------------------------------------------------- archivos

  /** Lee un recurso del classpath en UTF-8; null si no esta. */
  public static String recurso( String ruta ) {
    ClassLoader cl = Html.class.getClassLoader();
    try ( InputStream in = cl == null ? null : cl.getResourceAsStream( ruta ) ) {
      if ( in == null ) {
        return null;
      }
      ByteArrayOutputStream bo = new ByteArrayOutputStream();
      byte[] buf = new byte[ 65536 ];
      int n;
      while ( ( n = in.read( buf ) ) > 0 ) {
        bo.write( buf, 0, n );
      }
      return new String( bo.toByteArray(), StandardCharsets.UTF_8 );
    } catch ( IOException e ) {
      return null;
    }
  }

  /** Escribe texto en UTF-8 (sin BOM) de forma atomica: primero un .tmp al lado y despues move. */
  public static void escribir( File destino, String texto ) throws IOException {
    escribir( destino, texto.getBytes( StandardCharsets.UTF_8 ) );
  }

  /** Escribe bytes de forma atomica: primero un .tmp al lado y despues move (con respaldo sin atomico). */
  public static void escribir( File destino, byte[] datos ) throws IOException {
    File dir = destino.getAbsoluteFile().getParentFile();
    if ( dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory() ) {
      throw new IOException( "No se pudo crear la carpeta " + dir );
    }
    File tmp = new File( dir, destino.getName() + ".tmp" );
    try ( OutputStream out = Files.newOutputStream( tmp.toPath() ) ) {
      out.write( datos );
    }
    try {
      Files.move( tmp.toPath(), destino.toPath(), StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE );
    } catch ( AtomicMoveNotSupportedException e ) {
      Files.move( tmp.toPath(), destino.toPath(), StandardCopyOption.REPLACE_EXISTING );
    } catch ( IOException e ) {
      // el destino puede estar tomado (por ejemplo abierto en un visor): se escribe directo
      try {
        Files.write( destino.toPath(), datos );
      } finally {
        Files.deleteIfExists( tmp.toPath() );
      }
    }
  }

  /** Nombre del archivo sin la extension ("informe.html" -> "informe"). */
  public static String base( File f ) {
    String n = f.getName();
    int p = n.lastIndexOf( '.' );
    return p > 0 ? n.substring( 0, p ) : n;
  }

  /** Version del plugin segun el manifiesto del jar; null en las pruebas (clases sueltas). */
  public static String version() {
    Package p = Html.class.getPackage();
    String v = p == null ? null : p.getImplementationVersion();
    return v == null || v.trim().isEmpty() ? null : v.trim();
  }
}
