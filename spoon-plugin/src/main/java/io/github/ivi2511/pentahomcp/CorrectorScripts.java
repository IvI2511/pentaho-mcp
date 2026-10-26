package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Element;

import io.github.ivi2511.pentahomcp.corrector.modelo.BaseDePrueba;
import io.github.ivi2511.pentahomcp.lint.Nodos;

/**
 * Los scripts SQL de un ejercicio con base de datos, buscados al lado de la solucion para el asistente «Crear pack»
 * (0.7.0): el docente casi siempre tiene un {@code crear_club.sql} o un {@code club.sql} junto al .ktr y no sabe que
 * tiene que ponerlo en «Scripts». Solo lee archivos .sql (nada corre ni se conecta):
 * <ul>
 * <li><b>Donde</b>: la carpeta de la solucion y sus subcarpetas (dos niveles) y la carpeta de arriba (solo sus .sql),
 * sin las carpetas de packs ({@code pack.json}), sandboxes ni ocultas; como mucho {@value #MAX_ARCHIVOS} archivos y
 * los primeros {@value #MAX_LEIDO} bytes de cada uno.</li>
 * <li><b>De que conexion</b>: cada script va a la conexion que mas nombra: su base original en un {@code USE} o
 * {@code CREATE DATABASE} (10 puntos), una tabla de la conexion creada (2) o cargada (1). Las tablas de una conexion
 * son las que escriben y leen sus steps (tabla, tabla del lookup, FROM/JOIN del SQL). Un script que no nombra nada de
 * ninguna conexion no se propone.</li>
 * <li><b>Orden</b>: primero los que crean tablas, despues los que solo cargan datos; por nombre.</li>
 * <li><b>Caso oculto</b>: un script cuyo nombre dice «oculto», «hidden» o «secret» ({@link #esOculto}) es de un caso
 * oculto: el asistente lo pone en «Scripts del caso oculto», no en los de todos los casos.</li>
 * </ul>
 *
 * @dueño corrector 0.7.0
 */
final class CorrectorScripts {

  static final int MAX_ARCHIVOS = 200;
  static final int MAX_LEIDO = 2 * 1024 * 1024;
  private static final Pattern CREA = Pattern.compile(
    "(?i)\\bcreate\\s+(?:temporary\\s+)?table\\s+(?:if\\s+not\\s+exists\\s+)?((?:[`\"\\[]?[\\w$]+[`\"\\]]?\\s*\\.\\s*)?"
      + "[`\"\\[]?[\\w$]+[`\"\\]]?)" );
  private static final Pattern CARGA = Pattern.compile(
    "(?i)\\binsert\\s+(?:ignore\\s+)?into\\s+((?:[`\"\\[]?[\\w$]+[`\"\\]]?\\s*\\.\\s*)?[`\"\\[]?[\\w$]+[`\"\\]]?)" );
  private static final Pattern BASE = Pattern.compile(
    "(?i)\\b(?:use|create\\s+(?:database|schema)\\s+(?:if\\s+not\\s+exists\\s+)?)\\s*[`\"]?([\\w$]+)[`\"]?" );
  private static final Pattern DESDE = Pattern.compile(
    "(?i)\\b(?:from|join)\\s+((?:[`\"\\[]?[\\w$]+[`\"\\]]?\\s*\\.\\s*)?[`\"\\[]?[\\w$]+[`\"\\]]?)" );

  private CorrectorScripts() {
  }

  /** Un .sql encontrado y lo que nombra. */
  static final class Script {

    final File archivo;
    final Set<String> bases = new LinkedHashSet<String>();
    final Set<String> creadas = new LinkedHashSet<String>();
    final Set<String> cargadas = new LinkedHashSet<String>();

    Script( File archivo ) {
      this.archivo = archivo;
    }

    boolean creaTablas() {
      return !creadas.isEmpty();
    }
  }

  /**
   * Conexion (como esta en la solucion) → los scripts que le corresponden, en el orden en que tienen que correr.
   * Solo aparecen las conexiones con algun script.
   *
   * @param dirSolucion la carpeta donde esta la solucion (la original, no la copia temporal)
   * @param raiz el XML de la solucion
   */
  static Map<String, List<File>> detectar( File dirSolucion, Element raiz ) {
    Map<String, List<File>> m = new LinkedHashMap<String, List<File>>();
    if ( dirSolucion == null || !dirSolucion.isDirectory() || raiz == null ) {
      return m;
    }
    CorrectorBases.Inventario inv = CorrectorBases.inventario( raiz );
    Map<String, Set<String>> tablas = tablasPorConexion( raiz );
    List<Script> scripts = new ArrayList<Script>();
    for ( File f : buscar( dirSolucion ) ) {
      Script s = leer( f );
      if ( s != null ) {
        scripts.add( s );
      }
    }
    Map<String, List<Script>> porConexion = new LinkedHashMap<String, List<Script>>();
    for ( Script s : scripts ) {
      String mejor = null;
      int puntos = 0;
      for ( CorrectorBases.Conexion c : inv.usadas() ) {
        int p = puntos( s, c, tablas.get( c.nombre.toLowerCase( Locale.ROOT ) ) );
        if ( p > puntos ) {
          puntos = p;
          mejor = c.nombre;
        }
      }
      if ( mejor != null ) {
        List<Script> l = porConexion.get( mejor );
        if ( l == null ) {
          l = new ArrayList<Script>();
          porConexion.put( mejor, l );
        }
        l.add( s );
      }
    }
    for ( Map.Entry<String, List<Script>> e : porConexion.entrySet() ) {
      List<Script> l = e.getValue();
      Collections.sort( l, new Comparator<Script>() {
        @Override
        public int compare( Script a, Script b ) {
          if ( a.creaTablas() != b.creaTablas() ) {
            return a.creaTablas() ? -1 : 1;
          }
          return a.archivo.getName().compareToIgnoreCase( b.archivo.getName() );
        }
      } );
      List<File> fs = new ArrayList<File>();
      for ( Script s : l ) {
        fs.add( s.archivo );
      }
      m.put( e.getKey(), fs );
    }
    return m;
  }

  private static final Pattern OCULTO = Pattern.compile( "(?i)(ocult|hidden|secret)" );

  /** Un script de un caso oculto, por su nombre («datos_oculto.sql», «hidden_data.sql»). */
  static boolean esOculto( File f ) {
    return f != null && OCULTO.matcher( f.getName() ).find();
  }

  /** Los .sql de la carpeta de la solucion (dos niveles) y de la de arriba (uno), sin packs ni sandboxes. */
  static List<File> buscar( File dirSolucion ) {
    List<File> l = new ArrayList<File>();
    juntar( dirSolucion.getAbsoluteFile(), 2, l );
    File arriba = dirSolucion.getAbsoluteFile().getParentFile();
    if ( arriba != null ) {
      juntar( arriba, 0, l );
    }
    return l;
  }

  private static void juntar( File dir, int niveles, List<File> l ) {
    File[] hs = dir.listFiles();
    if ( hs == null ) {
      return;
    }
    Arrays.sort( hs );
    for ( File h : hs ) {
      if ( l.size() >= MAX_ARCHIVOS ) {
        return;
      }
      if ( h.isFile() && h.getName().toLowerCase( Locale.ROOT ).endsWith( ".sql" ) && !l.contains( h ) ) {
        l.add( h );
      }
    }
    if ( niveles <= 0 ) {
      return;
    }
    for ( File h : hs ) {
      if ( h.isDirectory() && !ignorada( h ) ) {
        juntar( h, niveles - 1, l );
      }
    }
  }

  /** Carpetas que no son del docente: packs, sandboxes, ocultas. */
  private static boolean ignorada( File d ) {
    String n = d.getName();
    return n.startsWith( "." ) || n.startsWith( "_" ) || new File( d, "pack.json" ).isFile() || new File( d,
      CorrectorSandbox.MARCA ).isFile() || n.equalsIgnoreCase( "node_modules" );
  }

  /** Lo que nombra un script (sin los comentarios ni las cadenas), o null si no se puede leer. */
  static Script leer( File f ) {
    byte[] b;
    try ( InputStream in = new FileInputStream( f ) ) {
      java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
      byte[] buf = new byte[ 65536 ];
      int n;
      while ( o.size() < MAX_LEIDO && ( n = in.read( buf ) ) > 0 ) {
        o.write( buf, 0, n );
      }
      b = o.toByteArray();
    } catch ( Exception e ) {
      return null;
    }
    String texto = new String( b, StandardCharsets.UTF_8 );
    StringBuilder sinComentarios = new StringBuilder();
    for ( String st : CorrectorBases.sentencias( texto, true ) ) {
      sinComentarios.append( CorrectorBases.sinCadenas( st ) ).append( ";\n" );
    }
    String t = sinComentarios.toString();
    Script s = new Script( f.getAbsoluteFile() );
    Matcher m = BASE.matcher( t );
    while ( m.find() ) {
      s.bases.add( m.group( 1 ).toLowerCase( Locale.ROOT ) );
    }
    m = CREA.matcher( t );
    while ( m.find() ) {
      s.creadas.add( tabla( m.group( 1 ) ) );
    }
    m = CARGA.matcher( t );
    while ( m.find() ) {
      s.cargadas.add( tabla( m.group( 1 ) ) );
    }
    return s;
  }

  private static int puntos( Script s, CorrectorBases.Conexion c, Set<String> tablas ) {
    int p = 0;
    String base = c.base == null ? "" : BaseDePrueba.nombreSolo( c.base ).toLowerCase( Locale.ROOT );
    if ( !base.isEmpty() && s.bases.contains( base ) ) {
      p += 10;
    }
    if ( tablas != null ) {
      for ( String t : s.creadas ) {
        if ( tablas.contains( t ) ) {
          p += 2;
        }
      }
      for ( String t : s.cargadas ) {
        if ( tablas.contains( t ) ) {
          p += 1;
        }
      }
    }
    return p;
  }

  /**
   * Conexion (sin mayusculas) → las tablas que nombran sus steps (sin mayusculas ni base): la tabla que escriben, la
   * del lookup, y las de FROM/JOIN del SQL de un Table input o Execute SQL.
   */
  static Map<String, Set<String>> tablasPorConexion( Element raiz ) {
    Map<String, Set<String>> m = new LinkedHashMap<String, Set<String>>();
    for ( Element s : Nodos.hijos( raiz, "step" ) ) {
      String cx = Nodos.txt( s, "connection" );
      if ( cx.isEmpty() ) {
        continue;
      }
      Set<String> ts = m.get( cx.toLowerCase( Locale.ROOT ) );
      if ( ts == null ) {
        ts = new LinkedHashSet<String>();
        m.put( cx.toLowerCase( Locale.ROOT ), ts );
      }
      Element lookup = Nodos.hijo( s, "lookup" );
      for ( String v : Arrays.asList( Nodos.txt( s, "table" ), Nodos.txt( s, "tablename" ), Nodos.txt( s,
        "table_name" ), Nodos.txt( lookup, "table" ), Nodos.txt( lookup, "tablename" ) ) ) {
        if ( !v.isEmpty() ) {
          ts.add( tabla( v ) );
        }
      }
      String sql = Nodos.txt( s, "sql" );
      if ( !sql.isEmpty() ) {
        Matcher d = DESDE.matcher( CorrectorBases.sinCadenas( sql ) );
        while ( d.find() ) {
          ts.add( tabla( d.group( 1 ) ) );
        }
      }
    }
    return m;
  }

  /** «`club`.`Clientes`» → «clientes». */
  static String tabla( String nombre ) {
    String t = nombre == null ? "" : nombre.replaceAll( "[`\"\\[\\]\\s]", "" );
    int i = t.lastIndexOf( '.' );
    return ( i < 0 ? t : t.substring( i + 1 ) ).toLowerCase( Locale.ROOT );
  }
}
