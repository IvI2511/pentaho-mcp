package io.github.ivi2511.pentahomcp.lint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.w3c.dom.Element;

/**
 * Qué hojas del XML de un step/entrada son rutas de archivo y qué clase de ruta tienen (absolute_path,
 * relative_path, job_variable_in_transformation), con la propuesta de arreglo (§7). Solo texto: no toca el disco.
 */
public final class Rutas {

  public static final String DIR_ACTUAL = "${Internal.Entry.Current.Directory}";
  public static final String PARAM_DATOS = "DIR_DATOS";

  /** Unidad, UNC, file:/zip: con unidad, file:///…, o /x/y (dos segmentos: /x solo es un directorio de repositorio). */
  static final Pattern ABSOLUTA = Pattern.compile(
    "^([A-Za-z]:[\\\\/]|\\\\\\\\[^\\\\]|(file|zip|jar|tar|gz):/*[A-Za-z]:|file:///?[A-Za-z/]|/(?!/)[^\\s/]+/)" );
  static final Pattern TAG_RUTA = Pattern.compile( "(?i).*(file|dir|folder|path).*" );
  static final Pattern TAG_NO_RUTA = Pattern.compile( "(?i).*(xpath|field|extension|mask|type|required|result|subfolder"
    + "|ignore|accept|include|create|clear|append|fail|skip|isafile|donot|do_not|usetemp|check|set_|rownum|pass|prune"
    + "|sqlfrom|exists|wildcard|size|nr|limit|count).*|path" );
  /** Rutas del otro lado (FTP, SFTP, servidores, URLs): /home/u/in en un SFTP es del servidor [NP]. */
  static final Pattern TAG_REMOTO = Pattern.compile( "(?i)(remote|ftp|sftp|scp|ssh|url|uri|host|server).*"
    + "|.*(remote|url|server|host).*" );
  static final Pattern EXTENSION = Pattern.compile( "(?i).*\\.(csv|txt|ktr|kjb|xls|xlsx|json|xml|dat|log|sql|zip|gz|tsv"
    + "|prn|ods|parquet|avro|yaml|yml|properties)$" );
  static final Pattern ESQUEMA = Pattern.compile( "(?i)^[a-z][a-z0-9+.-]*://.*" );
  /** Tags que con specification_method = rep_name/rep_ref son del repositorio, no del disco. */
  private static final Pattern TAG_REPOSITORIO = Pattern.compile( "directory|directory_path|filename|trans_name|job_name" );

  /** Una hoja que es una ruta: el tag (file/name para <file><name>), su valor y su número si el tag se repite. */
  public static final class Ruta {
    public String tag;
    public String valor;
    /** 1, 2… si el mismo tag aparece varias veces en el step; 0 si es único. */
    public int n;
  }

  private Rutas() {
  }

  /** Las hojas de ruta de un step/entrada, en el orden del XML. */
  public static List<Ruta> de( Element raiz ) {
    String spec = Nodos.txt( raiz, "specification_method" );
    boolean repo = spec.equals( "rep_name" ) || spec.equals( "rep_ref" );
    List<Ruta> out = new ArrayList<Ruta>();
    for ( Element[] ep : Nodos.hojas( raiz ) ) {
      Element e = ep[ 0 ];
      Element padre = ep[ 1 ];
      String t = e.getTagName();
      boolean nombreDeFile = t.equals( "name" ) && padre != null && padre.getTagName().equals( "file" );
      if ( !nombreDeFile && ( !TAG_RUTA.matcher( t ).matches() || TAG_NO_RUTA.matcher( t ).matches() ) ) {
        continue;
      }
      if ( TAG_REMOTO.matcher( t ).matches() ) {
        continue;
      }
      if ( repo && TAG_REPOSITORIO.matcher( t ).matches() ) {
        continue;
      }
      String v = e.getTextContent().trim();
      if ( v.isEmpty() || v.equals( "/" ) ) {
        continue;
      }
      Ruta r = new Ruta();
      r.tag = nombreDeFile ? "file/name" : t;
      r.valor = v;
      out.add( r );
    }
    for ( Ruta r : out ) {
      int total = 0;
      int pos = 0;
      for ( Ruta o : out ) {
        if ( o.tag.equals( r.tag ) ) {
          total++;
          if ( o == r ) {
            pos = total;
          }
        }
      }
      r.n = total > 1 ? pos : 0;
    }
    return out;
  }

  public static boolean absoluta( String v ) {
    return ABSOLUTA.matcher( v ).find();
  }

  /** Relativa sin variable: se resuelve contra la carpeta de PDI, no la del .ktr (C15). */
  public static boolean relativaSinVariable( String v ) {
    if ( absoluta( v ) || v.startsWith( "/" ) || v.startsWith( "${" ) || v.startsWith( "%%" ) || ESQUEMA.matcher( v ).matches() ) {
      return false;
    }
    return v.contains( "/" ) || v.contains( "\\" ) || EXTENSION.matcher( v ).matches();
  }

  /**
   * Propuesta para una ruta absoluta (§7): ${Internal.Entry.Current.Directory}/<relativa> si queda a dos niveles
   * o menos del documento; si no (o si el documento no tiene archivo), el parámetro DIR_DATOS con esa carpeta de
   * default. Devuelve {valor nuevo, parámetro o null, default o null}.
   */
  public static String[] propuesta( String archivoDoc, String valor ) {
    String v = normalizar( valor );
    if ( archivoDoc != null && !archivoDoc.trim().isEmpty() ) {
      String d = normalizar( archivoDoc );
      int barra = d.lastIndexOf( '/' );
      if ( barra > 0 ) {
        String rel = relativa( d.substring( 0, barra ), v );
        if ( rel != null ) {
          return new String[] { DIR_ACTUAL + "/" + rel, null, null };
        }
      }
    }
    int b = v.lastIndexOf( '/' );
    String carpeta = b > 0 ? v.substring( 0, b ) : v;
    String archivo = b > 0 ? v.substring( b + 1 ) : "";
    return new String[] { "${" + PARAM_DATOS + "}" + ( archivo.isEmpty() ? "" : "/" + archivo ), PARAM_DATOS, carpeta };
  }

  /** Quita file:, une las barras y deja / como separador. */
  static String normalizar( String p ) {
    String s = p.trim().replace( '\\', '/' );
    if ( s.regionMatches( true, 0, "file:", 0, 5 ) ) {
      s = s.substring( 5 );
      while ( s.startsWith( "//" ) ) {
        s = s.substring( 1 );
      }
      if ( s.length() > 2 && s.charAt( 0 ) == '/' && s.charAt( 2 ) == ':' ) {
        s = s.substring( 1 ); // /C:/x → C:/x
      }
    }
    boolean unc = s.startsWith( "//" );
    s = s.replaceAll( "/{2,}", "/" );
    return unc ? "/" + s : s;
  }

  /** Ruta de destino relativa a la carpeta base, con a lo sumo dos «..»; null si no se puede. */
  static String relativa( String base, String destino ) {
    String[] a = segmentos( base );
    String[] b = segmentos( destino );
    if ( a.length == 0 || b.length == 0 || !raizIgual( a[ 0 ], b[ 0 ] ) ) {
      return null;
    }
    boolean windows = a[ 0 ].endsWith( ":" ) || a[ 0 ].startsWith( "//" );
    int comun = 0;
    while ( comun < a.length && comun < b.length && igual( a[ comun ], b[ comun ], windows ) ) {
      comun++;
    }
    int subir = a.length - comun;
    if ( subir > 2 || comun == b.length ) {
      return null;
    }
    StringBuilder r = new StringBuilder();
    for ( int i = 0; i < subir; i++ ) {
      r.append( "../" );
    }
    for ( int i = comun; i < b.length; i++ ) {
      r.append( b[ i ] ).append( i + 1 < b.length ? "/" : "" );
    }
    return r.toString();
  }

  private static String[] segmentos( String p ) {
    List<String> l = new ArrayList<String>();
    String s = p;
    if ( s.startsWith( "//" ) ) { // UNC: //servidor es la raíz
      int i = s.indexOf( '/', 2 );
      l.add( i < 0 ? s : s.substring( 0, i ) );
      s = i < 0 ? "" : s.substring( i );
    } else if ( s.startsWith( "/" ) ) {
      l.add( "/" );
    }
    for ( String x : s.split( "/" ) ) {
      if ( !x.isEmpty() && !x.equals( "." ) ) {
        l.add( x );
      }
    }
    return l.toArray( new String[ 0 ] );
  }

  private static boolean raizIgual( String a, String b ) {
    return a.equalsIgnoreCase( b );
  }

  private static boolean igual( String a, String b, boolean sinMayusculas ) {
    return sinMayusculas ? a.toLowerCase( Locale.ROOT ).equals( b.toLowerCase( Locale.ROOT ) ) : a.equals( b );
  }
}
