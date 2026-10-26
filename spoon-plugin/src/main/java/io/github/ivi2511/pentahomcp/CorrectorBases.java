package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pentaho.di.core.database.Database;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.encryption.Encr;
import org.w3c.dom.Element;

import io.github.ivi2511.pentahomcp.CorrectorTextos.Mensaje;
import io.github.ivi2511.pentahomcp.corrector.modelo.BaseDePrueba;
import io.github.ivi2511.pentahomcp.corrector.modelo.Caso;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.SeguridadBases;
import io.github.ivi2511.pentahomcp.corrector.modelo.TablaSalida;
import io.github.ivi2511.pentahomcp.lint.Nodos;

/**
 * Las bases de prueba del corrector (0.6.2, formato {@value Pack#FORMATO_2}): ejercicios que leen y escriben un
 * servidor de base de datos de verdad (MySQL, MariaDB, PostgreSQL o H2) sin tocar nunca las bases del docente.
 * <ul>
 * <li><b>Seguridad</b> ({@link #inseguras}): una base de prueba no puede ser la de la solucion
 * ({@code original_database}), una del sistema ({@code mysql}, {@code postgres}...) ni una de
 * {@code database_safety.deny}, y tiene que terminar en un sufijo de prueba ({@code _correccion}, {@code _test}...)
 * salvo {@code allow_any_name}. Se mira al validar el pack, al crearlo, en el padre antes de lanzar los workers y en la
 * hija antes de recrear: con un problema no corre nada.</li>
 * <li><b>Recrear</b> ({@link Sesion#recrear}): antes de cada caso de cada entrega, DROP y CREATE de la base de prueba
 * (en H2, DROP ALL OBJECTS) y los scripts del pack y del caso, con los nombres de las bases originales cambiados por
 * los de prueba. Un script que nombra otra base (USE, CREATE DATABASE...) no corre.</li>
 * <li><b>Workers</b>: con varios workers cada uno usa su base ({@code <base>_w1}, {@code <base>_w2}); con
 * {@code database_safety.single_worker}, uno solo.</li>
 * <li><b>Contraseña</b>: nunca en el pack ni en un archivo. El padre la toma de lo que escribio el docente en Spoon
 * (esta sesion), de la variable {@code password_variable} (kettle.properties o entorno) o de nada (vacia), y la pasa a
 * la hija por el entorno del proceso ({@link #entorno}); la copia de la entrega en el sandbox la tiene vacia y el
 * motor la pone en memoria al cargarla. La hija la borra del resultado por si algun texto la trae.</li>
 * <li><b>Captura</b> ({@link Sesion#capturar}): SELECT * de cada tabla de {@code output.tables}, sin las columnas
 * ignoradas, ordenada por la clave.</li>
 * </ul>
 * El inventario de una transformacion ({@link #inventario}) es solo XML: lo usa tambien el asistente en Spoon.
 *
 * @dueño corrector 0.6.2
 */
final class CorrectorBases {

  /** Variable de entorno de la hija con la contraseña de la base i de test_databases. */
  static final String VARIABLE_ENTORNO = "PENTAHO_LIVE_TESTDB_PASSWORD_";
  /** Lo que se escribe en lugar de una contraseña encontrada en un texto del resultado. */
  static final String TAPADA = "********";
  /** Steps que escriben en una tabla (como CorrectorNota.SALIDAS_A_BASE, mas Delete). */
  static final Set<String> ESCRIBEN = Collections.unmodifiableSet( new LinkedHashSet<String>( java.util.Arrays.asList(
    "TableOutput", "InsertUpdate", "Update", "Delete", "SynchronizeAfterMerge", "DimensionLookup",
    "CombinationLookup" ) ) );
  /** Atributos de una conexion reescrita que se conservan: los de comportamiento de identificadores, nada mas. */
  static final Set<String> ATRIBUTOS = Collections.unmodifiableSet( new HashSet<String>( java.util.Arrays.asList(
    "force_identifiers_to_lowercase", "force_identifiers_to_uppercase", "preserve_reserved_word_case",
    "quote_all_fields", "supports_boolean_data_type", "supports_timestamp_data_type" ) ) );
  private static final Pattern NOMBRE_VALIDO = Pattern.compile( "[A-Za-z0-9_$]{1,64}" );
  private static final Pattern DELIMITADOR = Pattern.compile( "(?i)^\\s*delimiter\\s+(\\S+)\\s*$" );

  private CorrectorBases() {
  }

  // ------------------------------------------------------------------ seguridad

  /**
   * Por que las bases de prueba del pack no se pueden usar (vacio = se pueden): una por problema. Mira cada base con
   * cada sufijo de worker de {@code sufijos} ("" = sin sufijo).
   *
   * @param originalesExtra nombres de bases que nunca se tocan ademas de los {@code original_database} del pack (las
   *   de la solucion, si se leyo)
   */
  static List<Mensaje> inseguras( Pack p, Collection<String> sufijos, Collection<String> originalesExtra ) {
    List<Mensaje> l = new ArrayList<Mensaje>();
    if ( p == null || p.bases == null || p.bases.isEmpty() ) {
      return l;
    }
    SeguridadBases seg = p.seguridadEfectiva();
    Set<String> originales = new LinkedHashSet<String>();
    for ( BaseDePrueba b : p.bases ) {
      if ( b != null && !vacio( b.baseOriginal ) ) {
        originales.add( BaseDePrueba.nombreSolo( b.baseOriginal ).toLowerCase( Locale.ROOT ) );
      }
    }
    for ( String o : originalesExtra == null ? Collections.<String>emptyList() : originalesExtra ) {
      if ( !vacio( o ) ) {
        originales.add( BaseDePrueba.nombreSolo( o ).toLowerCase( Locale.ROOT ) );
      }
    }
    List<String> sufs = new ArrayList<String>( sufijos == null || sufijos.isEmpty() ? Collections.singletonList( "" )
      : sufijos );
    Set<String> vistas = new HashSet<String>();
    Set<String> conexiones = new HashSet<String>();
    for ( BaseDePrueba b : p.bases ) {
      if ( b == null ) {
        continue;
      }
      String cx = b.conexion == null ? "" : b.conexion.trim();
      if ( cx.isEmpty() ) {
        l.add( new Mensaje( "db.unsafe.no_connection" ) );
        continue;
      }
      if ( !conexiones.add( cx.toLowerCase( Locale.ROOT ) ) ) {
        l.add( new Mensaje( "db.unsafe.duplicate_connection", cx ) );
      }
      if ( !BaseDePrueba.TIPOS.contains( b.tipoNormal() ) ) {
        l.add( new Mensaje( "db.unsafe.type", cx, b.tipo == null ? "" : b.tipo, String.join( ", ",
          BaseDePrueba.TIPOS ) ) );
        continue;
      }
      if ( vacio( b.base ) ) {
        l.add( new Mensaje( "db.unsafe.empty", cx ) );
        continue;
      }
      if ( vacio( b.servidor ) && !BaseDePrueba.H2.equals( b.tipoNormal() ) ) {
        l.add( new Mensaje( "db.unsafe.no_host", cx ) );
      }
      String solo = BaseDePrueba.nombreSolo( b.base );
      if ( !NOMBRE_VALIDO.matcher( solo ).matches() ) {
        l.add( new Mensaje( "db.unsafe.chars", cx, b.base ) );
        continue;
      }
      String bajo = solo.toLowerCase( Locale.ROOT );
      for ( String suf : sufs ) {
        String n = ( bajo + suf ).toLowerCase( Locale.ROOT );
        if ( originales.contains( n ) ) {
          l.add( new Mensaje( "db.unsafe.original", cx, solo + suf ) );
        }
        if ( SeguridadBases.DEL_SISTEMA.contains( n ) ) {
          l.add( new Mensaje( "db.unsafe.system", cx, solo + suf ) );
        }
        for ( String d : seg.prohibidas == null ? Collections.<String>emptyList() : seg.prohibidas ) {
          if ( !vacio( d ) && glob( d.trim(), n ) ) {
            l.add( new Mensaje( "db.unsafe.denied", cx, solo + suf, d.trim() ) );
          }
        }
        String clave = b.tipoNormal() + "|" + ( b.servidor == null ? "" : b.servidor.trim().toLowerCase( Locale.ROOT ) )
          + "|" + ( b.puerto == null ? "" : b.puerto.trim() ) + "|" + ( BaseDePrueba.H2.equals( b.tipoNormal() )
          ? b.base.trim().replace( '\\', '/' ).toLowerCase( Locale.ROOT ) + suf : n );
        if ( !vistas.add( clave ) ) {
          l.add( new Mensaje( "db.unsafe.duplicate", solo + suf ) );
        }
      }
      if ( !seg.cualquierNombre && !tieneSufijo( bajo, seg.sufijosEfectivos() ) ) {
        l.add( new Mensaje( "db.unsafe.suffix", cx, solo, String.join( ", ", seg.sufijosEfectivos() ) ) );
      }
    }
    return l;
  }

  static boolean tieneSufijo( String nombre, List<String> sufijos ) {
    for ( String s : sufijos ) {
      if ( nombre.toLowerCase( Locale.ROOT ).endsWith( s.toLowerCase( Locale.ROOT ) ) && nombre.length() > s.length() ) {
        return true;
      }
    }
    return false;
  }

  /** Patron con * y ? sin mayusculas. */
  static boolean glob( String patron, String texto ) {
    StringBuilder re = new StringBuilder();
    for ( char c : patron.toLowerCase( Locale.ROOT ).toCharArray() ) {
      re.append( c == '*' ? ".*" : c == '?' ? "." : Pattern.quote( String.valueOf( c ) ) );
    }
    return texto.toLowerCase( Locale.ROOT ).matches( re.toString() );
  }

  /** El sufijo de la base del worker de la ranura n: "" con un worker, "_w1", "_w2"... con varios. */
  static String sufijoWorker( Pack p, int ranura, int workers ) {
    return p == null || !p.usaBases() || workers <= 1 || p.bases.isEmpty() ? "" : "_w" + ranura;
  }

  /** Los sufijos que usaria un lote con esos workers. */
  static List<String> sufijos( Pack p, int workers ) {
    List<String> l = new ArrayList<String>();
    for ( int i = 1; i <= Math.max( 1, workers ); i++ ) {
      l.add( sufijoWorker( p, i, workers ) );
    }
    return l;
  }

  /** Cuantos workers puede tener un lote con este pack (1 si database_safety.single_worker). */
  static int workersPermitidos( Pack p, int pedidos ) {
    if ( p != null && !p.bases.isEmpty() && p.seguridadEfectiva().unWorker ) {
      return 1;
    }
    return Math.max( 1, pedidos );
  }

  // ------------------------------------------------------------------ en uso (padre)

  /** Las bases de prueba que esta JVM (Spoon) esta usando ahora: dos lotes a la vez no pueden pisarse. */
  private static final Set<String> EN_USO = new HashSet<String>();

  /** tipo|servidor|puerto|base de cada base de prueba con cada sufijo. */
  static List<String> clavesDeUso( Pack p, Collection<String> sufijos ) {
    List<String> l = new ArrayList<String>();
    if ( p == null || p.bases == null ) {
      return l;
    }
    for ( BaseDePrueba b : p.bases ) {
      if ( b == null || vacio( b.base ) ) {
        continue;
      }
      for ( String s : sufijos == null || sufijos.isEmpty() ? Collections.singletonList( "" ) : sufijos ) {
        l.add( ( b.tipoNormal() + "|" + n( b.servidor ) + "|" + n( b.puerto ) + "|" + b.base.trim() + s ).toLowerCase(
          Locale.ROOT ).replace( '\\', '/' ) );
      }
    }
    return l;
  }

  /** Reserva las bases para un lote o una creacion; {@link Falla} si alguna ya esta en uso en esta JVM. */
  static void reservar( List<String> claves ) throws Falla {
    synchronized ( EN_USO ) {
      for ( String c : claves ) {
        if ( EN_USO.contains( c ) ) {
          String[] p = c.split( "\\|", -1 );
          throw new Falla( new Mensaje( "db.err.in_use", p.length > 3 ? BaseDePrueba.nombreSolo( p[ 3 ] ) : c ) );
        }
      }
      EN_USO.addAll( claves );
    }
  }

  static void liberar( List<String> claves ) {
    synchronized ( EN_USO ) {
      EN_USO.removeAll( claves );
    }
  }

  /** Las bases de las conexiones de la solucion del pack (para no tocarlas nunca), si el pack la trae. */
  static List<String> originalesDeSolucion( File dirPack, Pack p ) {
    List<String> l = new ArrayList<String>();
    if ( dirPack == null || p == null || vacio( p.solucion ) || CorrectorPack.motivoRuta( p.solucion ) != null ) {
      return l;
    }
    File f = new File( dirPack, p.solucion.trim().replace( '/', File.separatorChar ) );
    if ( !f.isFile() ) {
      return l;
    }
    try {
      for ( Conexion c : inventario( f ).conexiones ) {
        if ( !vacio( c.base ) ) {
          l.add( c.base.trim() );
        }
      }
    } catch ( Exception ignored ) {
      // sin la solucion: quedan los original_database del pack
    }
    return l;
  }

  // ------------------------------------------------------------------ contraseñas (padre)

  /** Lo que el docente escribio en Spoon en esta sesion: tipo|host|puerto|usuario → contraseña. Nunca a un archivo. */
  private static final Map<String, String> RECORDADAS = Collections.synchronizedMap(
    new LinkedHashMap<String, String>() );

  static String claveRecuerdo( BaseDePrueba b ) {
    return ( b.tipoNormal() + "|" + n( b.servidor ) + "|" + n( b.puerto ) + "|" + n( b.usuario ) ).toLowerCase(
      Locale.ROOT );
  }

  static void recordar( BaseDePrueba b, String clave ) {
    if ( b != null && clave != null ) {
      RECORDADAS.put( claveRecuerdo( b ), clave );
    }
  }

  static boolean recordada( BaseDePrueba b ) {
    return b != null && RECORDADAS.containsKey( claveRecuerdo( b ) );
  }

  /**
   * La contraseña de una base de prueba en el padre: la que se paso ({@code explicitas}, por nombre de conexion sin
   * mayusculas), la que el docente escribio en esta sesion, la de la variable {@code password_variable} (propiedad
   * de sistema, que es donde Spoon deja kettle.properties, o variable de entorno) o null.
   */
  static String clave( BaseDePrueba b, Map<String, String> explicitas ) {
    if ( b == null ) {
      return null;
    }
    String cx = b.conexion == null ? "" : b.conexion.trim().toLowerCase( Locale.ROOT );
    if ( explicitas != null && explicitas.containsKey( cx ) ) {
      return explicitas.get( cx );
    }
    String r = RECORDADAS.get( claveRecuerdo( b ) );
    if ( r != null ) {
      return r;
    }
    if ( !vacio( b.variableClave ) ) {
      String v = b.variableClave.trim();
      String x = System.getProperty( v );
      if ( x == null ) {
        x = System.getenv( v );
      }
      return x;
    }
    return null;
  }

  /** Las bases sin contraseña conocida (para preguntarla en Spoon): ni explicita, ni recordada, ni en su variable. */
  static List<BaseDePrueba> sinClave( Pack p, Map<String, String> explicitas ) {
    List<BaseDePrueba> l = new ArrayList<BaseDePrueba>();
    for ( BaseDePrueba b : p == null ? Collections.<BaseDePrueba>emptyList() : p.bases ) {
      if ( b != null && clave( b, explicitas ) == null ) {
        l.add( b );
      }
    }
    return l;
  }

  /** Las variables de entorno con las contraseñas para la hija (las que se conocen). */
  static Map<String, String> entorno( Pack p, Map<String, String> explicitas ) {
    Map<String, String> m = new LinkedHashMap<String, String>();
    if ( p == null || p.bases == null ) {
      return m;
    }
    for ( int i = 0; i < p.bases.size(); i++ ) {
      String c = clave( p.bases.get( i ), explicitas );
      if ( c != null ) {
        m.put( VARIABLE_ENTORNO + i, c );
      }
    }
    return m;
  }

  /** En la hija: conexion (sin mayusculas) → contraseña, de su entorno. */
  static Map<String, String> clavesDelEntorno( Pack p ) {
    Map<String, String> m = new LinkedHashMap<String, String>();
    if ( p == null || p.bases == null ) {
      return m;
    }
    for ( int i = 0; i < p.bases.size(); i++ ) {
      BaseDePrueba b = p.bases.get( i );
      String c = System.getenv( VARIABLE_ENTORNO + i );
      if ( b != null && b.conexion != null && c != null ) {
        m.put( b.conexion.trim().toLowerCase( Locale.ROOT ), c );
      }
    }
    return m;
  }

  /** Cambia cada contraseña conocida (de 4 o mas caracteres) por {@link #TAPADA} en un texto. */
  static String tapar( String texto, Collection<String> claves ) {
    if ( texto == null || claves == null ) {
      return texto;
    }
    String t = texto;
    for ( String c : claves ) {
      if ( c != null && c.length() >= 4 && t.contains( c ) ) {
        t = t.replace( c, TAPADA );
      }
    }
    return t;
  }

  // ------------------------------------------------------------------ inventario (solo XML)

  /** Una conexion de una transformacion, como esta en el XML. */
  static final class Conexion {

    String nombre;
    String tipo;
    String servidor;
    String puerto;
    String base;
    String usuario;
    /** La usa algun step. */
    boolean usada;
  }

  /** Una tabla que un step escribe. */
  static final class TablaEscrita {

    String step;
    String tipoStep;
    String conexion;
    String tabla;
    String esquema;
    /** Columnas de la tabla que identifican una fila segun el step (claves de Insert/Update, naturales). */
    final List<String> claves = new ArrayList<String>();
  }

  /** Lo que una transformacion lee y escribe, del XML. */
  static final class Inventario {

    final List<Conexion> conexiones = new ArrayList<Conexion>();
    final List<TablaEscrita> tablas = new ArrayList<TablaEscrita>();
    /** Nombres de los Text file output. */
    final List<String> salidasArchivo = new ArrayList<String>();

    Conexion conexion( String nombre ) {
      for ( Conexion c : conexiones ) {
        if ( c.nombre != null && c.nombre.equalsIgnoreCase( nombre == null ? "" : nombre.trim() ) ) {
          return c;
        }
      }
      return null;
    }

    /** Las conexiones que usa algun step. */
    List<Conexion> usadas() {
      List<Conexion> l = new ArrayList<Conexion>();
      for ( Conexion c : conexiones ) {
        if ( c.usada ) {
          l.add( c );
        }
      }
      return l;
    }

    /** Las tablas escritas sin repetir (conexion + tabla), en orden. */
    List<TablaEscrita> tablasUnicas() {
      List<TablaEscrita> l = new ArrayList<TablaEscrita>();
      Set<String> vistas = new HashSet<String>();
      for ( TablaEscrita t : tablas ) {
        if ( vistas.add( ( t.conexion + "/" + t.tabla ).toLowerCase( Locale.ROOT ) ) ) {
          l.add( t );
        }
      }
      return l;
    }
  }

  static Inventario inventario( File ktr ) throws Exception {
    try ( InputStream in = new FileInputStream( ktr ) ) {
      return inventario( Nodos.parse( in ) );
    }
  }

  /** Conexiones, las tablas que escriben los steps de {@link #ESCRIBEN} y los Text file output de una .ktr. */
  static Inventario inventario( Element raiz ) {
    Inventario inv = new Inventario();
    for ( Element c : Nodos.hijos( raiz, "connection" ) ) {
      Conexion x = new Conexion();
      x.nombre = Nodos.txt( c, "name" );
      x.tipo = Nodos.txt( c, "type" );
      x.servidor = Nodos.txt( c, "server" );
      x.puerto = Nodos.txt( c, "port" );
      x.base = Nodos.txt( c, "database" );
      x.usuario = Nodos.txt( c, "username" );
      inv.conexiones.add( x );
    }
    for ( Element s : Nodos.hijos( raiz, "step" ) ) {
      for ( Element[] hp : Nodos.hojas( s ) ) {
        if ( "connection".equals( hp[ 0 ].getTagName() ) ) {
          Conexion c = inv.conexion( hp[ 0 ].getTextContent() );
          if ( c != null ) {
            c.usada = true;
          }
        }
      }
      String nombre = Nodos.crudo( s, "name" );
      String tipo = Nodos.txt( s, "type" );
      if ( "TextFileOutput".equalsIgnoreCase( tipo ) ) {
        inv.salidasArchivo.add( nombre );
      }
      String canon = null;
      for ( String e : ESCRIBEN ) {
        if ( e.equalsIgnoreCase( tipo ) ) {
          canon = e;
        }
      }
      if ( canon == null ) {
        continue;
      }
      if ( "DimensionLookup".equals( canon ) && "N".equalsIgnoreCase( Nodos.txt( s, "update" ) ) ) {
        continue; // solo busca: no escribe
      }
      Element lookup = Nodos.hijo( s, "lookup" );
      String tabla = primero( Nodos.txt( s, "table" ), Nodos.txt( s, "tablename" ), Nodos.txt( s, "table_name" ),
        Nodos.txt( lookup, "table" ), Nodos.txt( lookup, "tablename" ) );
      if ( tabla.isEmpty() ) {
        continue;
      }
      TablaEscrita t = new TablaEscrita();
      t.step = nombre;
      t.tipoStep = canon;
      t.conexion = Nodos.txt( s, "connection" );
      t.tabla = tabla;
      t.esquema = primero( Nodos.txt( s, "schema" ), Nodos.txt( lookup, "schema" ) );
      // claves: Insert/Update, Update, Delete, Synchronize (lookup/key/field); Dimension/Combination (fields/key/lookup)
      for ( Element k : Nodos.hijos( lookup, "key" ) ) {
        String f = Nodos.txt( k, "field" );
        if ( !f.isEmpty() && !t.claves.contains( f ) ) {
          t.claves.add( f );
        }
      }
      for ( Element k : Nodos.hijos( Nodos.hijo( s, "fields" ), "key" ) ) {
        String f = Nodos.txt( k, "lookup" );
        if ( !f.isEmpty() && !t.claves.contains( f ) ) {
          t.claves.add( f );
        }
      }
      inv.tablas.add( t );
    }
    return inv;
  }

  /**
   * Las bases de prueba que el asistente propone para una solucion: una por conexion usada de un tipo que el corrector
   * sabe recrear, con el mismo servidor, puerto y usuario y la base con el sufijo {@code _correccion}.
   */
  static List<BaseDePrueba> propuestas( Inventario inv ) {
    List<BaseDePrueba> l = new ArrayList<BaseDePrueba>();
    for ( Conexion c : inv.usadas() ) {
      String tipo = c.tipo == null ? "" : c.tipo.trim().toUpperCase( Locale.ROOT );
      if ( !BaseDePrueba.TIPOS.contains( tipo ) ) {
        continue;
      }
      BaseDePrueba b = new BaseDePrueba( c.nombre, tipo, c.servidor, c.puerto, propuesta( c.base ), c.usuario );
      b.baseOriginal = c.base;
      l.add( b );
    }
    return l;
  }

  /** "gym_dw" → "gym_dw_correccion"; una base que ya termina en un sufijo de prueba queda igual. */
  static String propuesta( String original ) {
    String o = original == null ? "" : original.trim();
    if ( o.isEmpty() ) {
      return "";
    }
    String solo = BaseDePrueba.nombreSolo( o );
    if ( tieneSufijo( solo, SeguridadBases.SUFIJOS ) ) {
      return o;
    }
    return o + "_correccion";
  }

  // ------------------------------------------------------------------ emparejar conexiones de la entrega

  /**
   * La base de prueba para una conexion de la entrega: por nombre (sin mayusculas); si no, por la base a la que
   * apunta (la original de la solucion o la de prueba); si no, la unica del mismo tipo y servidor que todavia no se
   * emparejo. null = ninguna (la conexion es ajena y la entrega no corre).
   */
  static BaseDePrueba emparejar( Pack p, String nombre, String tipo, String servidor, String base,
                                 Set<BaseDePrueba> yaUsadas, boolean soloNombreOBase ) {
    BaseDePrueba b = p.base( nombre );
    if ( b != null ) {
      return b;
    }
    String bs = BaseDePrueba.nombreSolo( base ).toLowerCase( Locale.ROOT );
    if ( !bs.isEmpty() ) {
      for ( BaseDePrueba x : p.bases ) {
        if ( x != null && ( bs.equals( BaseDePrueba.nombreSolo( x.baseOriginal ).toLowerCase( Locale.ROOT ) )
          || bs.equals( BaseDePrueba.nombreSolo( x.base ).toLowerCase( Locale.ROOT ) ) ) ) {
          return x;
        }
      }
    }
    if ( soloNombreOBase ) {
      return null;
    }
    BaseDePrueba unica = null;
    int n = 0;
    for ( BaseDePrueba x : p.bases ) {
      if ( x != null && !yaUsadas.contains( x ) && x.tipoNormal().equalsIgnoreCase( tipo == null ? "" : tipo.trim() )
        && n( x.servidor ).equalsIgnoreCase( n( servidor ) ) ) {
        unica = x;
        n++;
      }
    }
    return n == 1 ? unica : null;
  }

  // ------------------------------------------------------------------ SQL

  /**
   * Las sentencias de un script: separa por {@code ;} (o el delimitador de una linea {@code DELIMITER $$} de MySQL)
   * fuera de cadenas, identificadores entre comillas, comentarios ({@code --}, {@code #} en MySQL y
   * {@code /* *}{@code /}) y bloques {@code $tag$...$tag$} de PostgreSQL. Las sentencias quedan sin el delimitador ni
   * los comentarios de linea del principio, y las vacias se descartan.
   */
  static List<String> sentencias( String script, boolean mysql ) {
    List<String> l = new ArrayList<String>();
    String s = script == null ? "" : script.replace( "\r\n", "\n" ).replace( '\r', '\n' );
    if ( s.startsWith( "﻿" ) ) {
      s = s.substring( 1 );
    }
    String delim = ";";
    StringBuilder actual = new StringBuilder();
    int i = 0;
    int n = s.length();
    boolean inicioLinea = true;
    while ( i < n ) {
      char c = s.charAt( i );
      if ( inicioLinea && mysql ) {
        int fin = s.indexOf( '\n', i );
        String linea = s.substring( i, fin < 0 ? n : fin );
        Matcher m = DELIMITADOR.matcher( linea );
        if ( m.matches() && actual.toString().trim().isEmpty() ) {
          delim = m.group( 1 );
          i = fin < 0 ? n : fin + 1;
          continue;
        }
      }
      inicioLinea = false;
      if ( c == '\n' ) {
        actual.append( c );
        i++;
        inicioLinea = true;
        continue;
      }
      if ( c == '-' && i + 1 < n && s.charAt( i + 1 ) == '-' || mysql && c == '#' ) {
        int fin = s.indexOf( '\n', i );
        i = fin < 0 ? n : fin;
        continue;
      }
      if ( c == '/' && i + 1 < n && s.charAt( i + 1 ) == '*' ) {
        int fin = s.indexOf( "*/", i + 2 );
        String com = s.substring( i, fin < 0 ? n : fin + 2 );
        if ( mysql && com.startsWith( "/*!" ) ) {
          actual.append( com ); // comentario ejecutable de MySQL (mysqldump): es SQL
        } else {
          actual.append( ' ' );
        }
        i = fin < 0 ? n : fin + 2;
        continue;
      }
      if ( c == '\'' || c == '"' || c == '`' ) {
        int j = i + 1;
        while ( j < n ) {
          char d = s.charAt( j );
          if ( d == '\\' && c != '`' && mysql && j + 1 < n ) {
            j += 2;
            continue;
          }
          if ( d == c ) {
            if ( j + 1 < n && s.charAt( j + 1 ) == c ) {
              j += 2;
              continue;
            }
            break;
          }
          j++;
        }
        actual.append( s, i, Math.min( n, j + 1 ) );
        i = Math.min( n, j + 1 );
        continue;
      }
      if ( c == '$' && !mysql ) {
        Matcher m = Pattern.compile( "\\$[A-Za-z_]*\\$" ).matcher( s );
        if ( m.find( i ) && m.start() == i ) {
          String tag = m.group();
          int fin = s.indexOf( tag, m.end() );
          int hasta = fin < 0 ? n : fin + tag.length();
          actual.append( s, i, hasta );
          i = hasta;
          continue;
        }
      }
      if ( s.startsWith( delim, i ) ) {
        agregarSentencia( l, actual );
        i += delim.length();
        continue;
      }
      actual.append( c );
      i++;
    }
    agregarSentencia( l, actual );
    return l;
  }

  private static void agregarSentencia( List<String> l, StringBuilder b ) {
    String t = b.toString().trim();
    b.setLength( 0 );
    if ( !t.isEmpty() ) {
      l.add( t );
    }
  }

  /**
   * El SQL con cada identificador que es una base original (sin comillas, entre {@code `} o entre {@code "}, sin
   * mayusculas) cambiado por su base de prueba, fuera de cadenas y comentarios: {@code USE gym_dw},
   * {@code gym_dw.dim_cliente}.
   */
  static String renombrar( String sql, Map<String, String> renombres ) {
    if ( sql == null || renombres == null || renombres.isEmpty() ) {
      return sql;
    }
    Map<String, String> m = new LinkedHashMap<String, String>();
    for ( Map.Entry<String, String> e : renombres.entrySet() ) {
      m.put( e.getKey().toLowerCase( Locale.ROOT ), e.getValue() );
    }
    StringBuilder b = new StringBuilder();
    int i = 0;
    int n = sql.length();
    while ( i < n ) {
      char c = sql.charAt( i );
      if ( c == '\'' ) {
        int j = fin( sql, i, '\'' );
        b.append( sql, i, j );
        i = j;
      } else if ( c == '-' && i + 1 < n && sql.charAt( i + 1 ) == '-' ) {
        int j = sql.indexOf( '\n', i );
        j = j < 0 ? n : j;
        b.append( sql, i, j );
        i = j;
      } else if ( c == '/' && i + 1 < n && sql.charAt( i + 1 ) == '*' ) {
        int j = sql.indexOf( "*/", i + 2 );
        j = j < 0 ? n : j + 2;
        b.append( sql, i, j );
        i = j;
      } else if ( c == '`' || c == '"' ) {
        int j = fin( sql, i, c );
        String dentro = sql.substring( i + 1, Math.max( i + 1, j - 1 ) );
        String r = m.get( dentro.toLowerCase( Locale.ROOT ) );
        b.append( r == null ? sql.substring( i, j ) : c + r + c );
        i = j;
      } else if ( Character.isLetter( c ) || c == '_' ) {
        int j = i;
        while ( j < n && ( Character.isLetterOrDigit( sql.charAt( j ) ) || sql.charAt( j ) == '_'
          || sql.charAt( j ) == '$' ) ) {
          j++;
        }
        String w = sql.substring( i, j );
        String r = m.get( w.toLowerCase( Locale.ROOT ) );
        b.append( r == null ? w : r );
        i = j;
      } else {
        b.append( c );
        i++;
      }
    }
    return b.toString();
  }

  /**
   * (0.7.1) Como {@link #renombrar}, pero conservador, para el SQL de los steps: solo cambia un nombre que es una base
   * original cuando califica a otro ({@code gym_dw.ventas}, {@code `gym_dw`.`ventas`}, {@code "gym_dw".ventas}) o va
   * despues de {@code USE}; nunca una tabla, columna o alias que se llame igual. Si en el mismo SQL el nombre tambien
   * aparece solo (una tabla «ventas» con la base «ventas»: {@code ventas.importe} es la columna de la tabla), no se
   * toca y queda en {@code ambiguos} (sin mayusculas).
   */
  static String renombrarCalificados( String sql, Map<String, String> renombres, Set<String> ambiguos ) {
    if ( sql == null || renombres == null || renombres.isEmpty() ) {
      return sql;
    }
    Map<String, String> m = new LinkedHashMap<String, String>();
    for ( Map.Entry<String, String> e : renombres.entrySet() ) {
      m.put( e.getKey().toLowerCase( Locale.ROOT ), e.getValue() );
    }
    // primera pasada: los identificadores (sin mayusculas) y si alguno de los renombrables aparece sin calificar
    List<int[]> tokens = new ArrayList<int[]>(); // {inicio, fin, comilla}
    int i = 0;
    int n = sql.length();
    while ( i < n ) {
      char c = sql.charAt( i );
      if ( c == '\'' ) {
        i = fin( sql, i, '\'' );
      } else if ( c == '-' && i + 1 < n && sql.charAt( i + 1 ) == '-' ) {
        int j = sql.indexOf( '\n', i );
        i = j < 0 ? n : j;
      } else if ( c == '/' && i + 1 < n && sql.charAt( i + 1 ) == '*' ) {
        int j = sql.indexOf( "*/", i + 2 );
        i = j < 0 ? n : j + 2;
      } else if ( c == '`' || c == '"' ) {
        int j = fin( sql, i, c );
        tokens.add( new int[] { i, j, c } );
        i = j;
      } else if ( Character.isLetter( c ) || c == '_' ) {
        int j = i;
        while ( j < n && ( Character.isLetterOrDigit( sql.charAt( j ) ) || sql.charAt( j ) == '_'
          || sql.charAt( j ) == '$' ) ) {
          j++;
        }
        tokens.add( new int[] { i, j, 0 } );
        i = j;
      } else {
        i++;
      }
    }
    Set<String> solos = new HashSet<String>();
    List<int[]> calificadores = new ArrayList<int[]>();
    for ( int k = 0; k < tokens.size(); k++ ) {
      int[] t = tokens.get( k );
      String w = palabra( sql, t ).toLowerCase( Locale.ROOT );
      if ( !m.containsKey( w ) ) {
        continue;
      }
      boolean antesPunto = siguiente( sql, t[ 1 ] ) == '.';
      boolean despuesPunto = anterior( sql, t[ 0 ] ) == '.';
      boolean trasUse = k > 0 && tokens.get( k - 1 )[ 2 ] == 0 && "use".equalsIgnoreCase( palabra( sql, tokens.get(
        k - 1 ) ) ) && soloEspacios( sql, tokens.get( k - 1 )[ 1 ], t[ 0 ] );
      if ( ( antesPunto && !despuesPunto ) || trasUse ) {
        calificadores.add( t );
      } else {
        solos.add( w );
      }
    }
    StringBuilder b = new StringBuilder();
    int desde = 0;
    for ( int[] t : calificadores ) {
      String w = palabra( sql, t ).toLowerCase( Locale.ROOT );
      if ( solos.contains( w ) ) {
        if ( ambiguos != null ) {
          ambiguos.add( w );
        }
        continue;
      }
      b.append( sql, desde, t[ 0 ] );
      String r = m.get( w );
      b.append( t[ 2 ] == 0 ? r : (char) t[ 2 ] + r + (char) t[ 2 ] );
      desde = t[ 1 ];
    }
    b.append( sql.substring( desde ) );
    return b.toString();
  }

  /** El identificador del token, sin sus comillas. */
  private static String palabra( String sql, int[] t ) {
    return t[ 2 ] == 0 ? sql.substring( t[ 0 ], t[ 1 ] ) : sql.substring( t[ 0 ] + 1, Math.max( t[ 0 ] + 1, t[ 1 ]
      - 1 ) );
  }

  private static char siguiente( String s, int i ) {
    while ( i < s.length() && Character.isWhitespace( s.charAt( i ) ) ) {
      i++;
    }
    return i < s.length() ? s.charAt( i ) : 0;
  }

  private static char anterior( String s, int i ) {
    i--;
    while ( i >= 0 && Character.isWhitespace( s.charAt( i ) ) ) {
      i--;
    }
    return i >= 0 ? s.charAt( i ) : 0;
  }

  private static boolean soloEspacios( String s, int a, int b ) {
    for ( int i = a; i < b; i++ ) {
      if ( !Character.isWhitespace( s.charAt( i ) ) ) {
        return false;
      }
    }
    return true;
  }

  /**
   * (0.7.1) Anota la base a la que apuntaba una conexion redirigida (la de la .ktr o la entrega) como una original mas
   * de su base de prueba ({@code m}: sin mayusculas → nombre de prueba), si es un nombre simple que no es una base del
   * sistema ni la de prueba. Asi {@code ventas_dw.hechos} en el SQL de un step tambien pasa a la de prueba.
   */
  static void anotarOriginal( String base, Destino d, Map<String, String> m ) {
    if ( base == null || d == null || base.contains( "${" ) || base.contains( "%%" ) ) {
      return;
    }
    String solo = BaseDePrueba.nombreSolo( base ).trim();
    String prueba = BaseDePrueba.nombreSolo( d.nombre );
    if ( solo.isEmpty() || !NOMBRE_VALIDO.matcher( solo ).matches() || solo.equalsIgnoreCase( prueba )
      || SeguridadBases.DEL_SISTEMA.contains( solo.toLowerCase( Locale.ROOT ) ) ) {
      return;
    }
    m.putIfAbsent( solo.toLowerCase( Locale.ROOT ), prueba );
  }

  /** Despues del cierre de una cadena o identificador que empieza en i con q (q doble = q escapada). */
  private static int fin( String s, int i, char q ) {
    int j = i + 1;
    while ( j < s.length() ) {
      char d = s.charAt( j );
      if ( d == '\\' && q == '\'' && j + 1 < s.length() ) {
        j += 2;
        continue;
      }
      if ( d == q ) {
        if ( j + 1 < s.length() && s.charAt( j + 1 ) == q ) {
          j += 2;
          continue;
        }
        return j + 1;
      }
      j++;
    }
    return s.length();
  }

  /** El SQL sin cadenas (quedan '') ni comentarios, para buscar palabras clave sin falsos positivos. */
  static String sinCadenas( String sql ) {
    StringBuilder b = new StringBuilder();
    int i = 0;
    int n = sql == null ? 0 : sql.length();
    while ( i < n ) {
      char c = sql.charAt( i );
      if ( c == '\'' ) {
        i = fin( sql, i, '\'' );
        b.append( "''" );
      } else if ( c == '-' && i + 1 < n && sql.charAt( i + 1 ) == '-' ) {
        int j = sql.indexOf( '\n', i );
        i = j < 0 ? n : j;
        b.append( ' ' );
      } else if ( c == '/' && i + 1 < n && sql.charAt( i + 1 ) == '*' && !( i + 2 < n && sql.charAt( i + 2 ) == '!' ) ) {
        int j = sql.indexOf( "*/", i + 2 );
        i = j < 0 ? n : j + 2;
        b.append( ' ' );
      } else {
        b.append( c );
        i++;
      }
    }
    return b.toString();
  }

  private static final Pattern USE = Pattern.compile( "(?is)^\\s*use\\s+[`\"]?([\\w$]+)[`\"]?" );
  private static final Pattern BASE_DDL = Pattern.compile(
    "(?is)\\b(create|drop|alter)\\s+(database|schema)\\s+(?:if\\s+(?:not\\s+)?exists\\s+)?[`\"]?([\\w$]+)[`\"]?" );
  private static final Pattern CALIFICADO = Pattern.compile( "(?i)[`\"]?([\\w$]+)[`\"]?\\s*\\.\\s*[`\"]?[\\w$*]" );
  /** Lo que en un servidor de verdad lee o escribe archivos, cambia usuarios o permisos o toca el servidor. */
  private static final String[][] PELIGROSO = {
    { "(?is)\\binto\\s+(outfile|dumpfile)\\b", "INTO OUTFILE" }, { "(?is)\\bload\\s+data\\b", "LOAD DATA" },
    { "(?is)\\bload_file\\s*\\(", "LOAD_FILE" }, { "(?is)\\bgrant\\b", "GRANT" }, { "(?is)\\brevoke\\b", "REVOKE" },
    { "(?is)\\b(create|drop|alter|rename)\\s+(user|role)\\b", "CREATE/DROP USER" },
    { "(?is)\\bset\\s+(global|persist|password)\\b", "SET GLOBAL" }, { "(?is)\\bshutdown\\b", "SHUTDOWN" },
    { "(?is)\\binstall\\s+(plugin|component)\\b", "INSTALL PLUGIN" },
    { "(?is)\\bcopy\\b[^;]*\\b(to|from)\\s+(program|'|stdin|stdout)", "COPY TO/FROM" },
    { "(?is)\\b(pg_read_file|pg_read_binary_file|pg_ls_dir|pg_stat_file|lo_import|lo_export|dblink\\w*|"
      + "pg_execute_server_program)\\s*\\(", "pg_read_file / lo_import / dblink" },
    { "(?is)\\b(csvwrite|csvread|file_read|file_write|link_schema)\\s*\\(", "CSVWRITE / FILE_READ" },
    { "(?is)\\brunscript\\b", "RUNSCRIPT" }, { "(?is)\\bscript\\s+(to|simple|nodata|nopasswords|drop|block)\\b",
      "SCRIPT TO" }, { "(?is)\\bcreate\\s+(force\\s+)?alias\\b", "CREATE ALIAS" },
    { "(?is)\\bbackup\\s+to\\b", "BACKUP TO" }, { "(?is)\\bcreate\\s+linked\\s+table\\b", "CREATE LINKED TABLE" },
    { "(?is)\\battach\\b(?:\\s*\\bdatabase\\b)?\\s*\\S.*?\\bas\\b", "ATTACH DATABASE" },
    { "(?is)\\bload_extension\\s*\\(", "load_extension" } };

  /**
   * Lo que una sentencia de la entrega (Table input, Execute SQL...) haria fuera de las bases de prueba, o null:
   * {@code USE} u otra base en un CREATE/DROP/ALTER DATABASE que no es de prueba, un nombre calificado con una base
   * prohibida (la original, las del sistema, las de deny) y lo que lee o escribe archivos o cambia permisos del
   * servidor (INTO OUTFILE, LOAD DATA, GRANT, COPY TO, CSVWRITE...).
   *
   * @param pruebas  los nombres de las bases de prueba (sin mayusculas)
   * @param prohibidas las originales y las del sistema (sin mayusculas)
   */
  static String sqlAjeno( String sql, Set<String> pruebas, Set<String> prohibidas ) {
    if ( sql == null || sql.trim().isEmpty() ) {
      return null;
    }
    String t = sinCadenas( sql );
    for ( String[] p : PELIGROSO ) {
      if ( Pattern.compile( p[ 0 ] ).matcher( t ).find() ) {
        return p[ 1 ];
      }
    }
    for ( String st : t.split( ";" ) ) {
      Matcher u = USE.matcher( st );
      if ( u.find() && !pruebas.contains( u.group( 1 ).toLowerCase( Locale.ROOT ) ) ) {
        return "USE " + u.group( 1 );
      }
    }
    Matcher d = BASE_DDL.matcher( t );
    while ( d.find() ) {
      if ( !pruebas.contains( d.group( 3 ).toLowerCase( Locale.ROOT ) ) ) {
        return d.group( 1 ).toUpperCase( Locale.ROOT ) + " " + d.group( 2 ).toUpperCase( Locale.ROOT ) + " "
          + d.group( 3 );
      }
    }
    Matcher q = CALIFICADO.matcher( t );
    while ( q.find() ) {
      String n = q.group( 1 ).toLowerCase( Locale.ROOT );
      if ( prohibidas.contains( n ) ) {
        return q.group( 1 ) + ".";
      }
    }
    return null;
  }

  /**
   * Una sentencia de un script del docente que nombra una base que no es de prueba (USE, CREATE/DROP DATABASE...):
   * con los nombres ya cambiados, solo puede pasar si el script nombra otra base que el pack no conoce. null si no.
   */
  static String scriptAjeno( String sentencia, Set<String> pruebas ) {
    String t = sinCadenas( sentencia );
    Matcher u = USE.matcher( t );
    if ( u.find() && !pruebas.contains( u.group( 1 ).toLowerCase( Locale.ROOT ) ) ) {
      return "USE " + u.group( 1 );
    }
    Matcher d = BASE_DDL.matcher( t );
    while ( d.find() ) {
      if ( "database".equalsIgnoreCase( d.group( 2 ) ) || "schema".equalsIgnoreCase( d.group( 2 ) ) ) {
        if ( !pruebas.contains( d.group( 3 ).toLowerCase( Locale.ROOT ) ) ) {
          return d.group( 1 ).toUpperCase( Locale.ROOT ) + " " + d.group( 2 ).toUpperCase( Locale.ROOT ) + " "
            + d.group( 3 );
        }
      }
    }
    return null;
  }

  /** CREATE/DROP DATABASE (o SCHEMA en MySQL) de una base de prueba: el corrector ya la recreo, se salta. */
  static boolean crearOBorrarBase( String sentencia, boolean mysql ) {
    Matcher d = BASE_DDL.matcher( sinCadenas( sentencia ) );
    return d.find() && d.start() < 3 && !"alter".equalsIgnoreCase( d.group( 1 ) )
      && ( "database".equalsIgnoreCase( d.group( 2 ) ) || mysql );
  }

  // ------------------------------------------------------------------ sesion (hija)

  /** Una base de prueba resuelta para esta JVM: el nombre con el sufijo del worker y la contraseña. */
  static final class Destino {

    final BaseDePrueba base;
    final int indice;
    /** "gym_dw_correccion" o "gym_dw_correccion_w2". */
    final String nombre;
    final String clave;

    Destino( BaseDePrueba base, int indice, String nombre, String clave ) {
      this.base = base;
      this.indice = indice;
      this.nombre = nombre;
      this.clave = clave;
    }

    /** "gym_dw_correccion (MYSQL localhost:3306)". */
    String describir() {
      return BaseDePrueba.nombreSolo( nombre ) + " (" + base.tipoNormal() + ( vacio( base.servidor ) ? ""
        : " " + base.servidor.trim() + ( vacio( base.puerto ) ? "" : ":" + base.puerto.trim() ) ) + ")";
    }
  }

  /** Lo que la hija sabe de las bases de prueba: un {@link Destino} por base, ya revisado. */
  static final class Sesion {

    final Pack pack;
    final File dirPack;
    final String sufijo;
    final Map<String, Destino> destinos = new LinkedHashMap<String, Destino>();

    /**
     * @throws Falla si las bases no son seguras (no corre nada)
     */
    Sesion( Pack pack, File dirPack, String sufijo, Map<String, String> claves ) throws Falla {
      this.pack = pack;
      this.dirPack = dirPack;
      this.sufijo = sufijo == null ? "" : sufijo;
      List<Mensaje> mal = inseguras( pack, Collections.singletonList( this.sufijo ), null );
      if ( !mal.isEmpty() ) {
        throw new Falla( new Mensaje( "db.err.unsafe", CorrectorTextos.lista( mal ) ) );
      }
      for ( int i = 0; i < pack.bases.size(); i++ ) {
        BaseDePrueba b = pack.bases.get( i );
        String k = b.conexion.trim().toLowerCase( Locale.ROOT );
        destinos.put( k, new Destino( b, i, BaseDePrueba.conSufijo( b.base.trim(), this.sufijo ), claves == null ? null
          : claves.get( k ) ) );
      }
    }

    Destino destino( String conexion ) {
      return conexion == null ? null : destinos.get( conexion.trim().toLowerCase( Locale.ROOT ) );
    }

    Destino destino( BaseDePrueba b ) {
      return b == null ? null : destino( b.conexion );
    }

    /** Las contraseñas conocidas (para taparlas en el resultado). */
    List<String> claves() {
      List<String> l = new ArrayList<String>();
      for ( Destino d : destinos.values() ) {
        if ( d.clave != null ) {
          l.add( d.clave );
        }
      }
      return l;
    }

    /** Los nombres de las bases de prueba de esta sesion, sin mayusculas (solo el nombre, sin carpeta). */
    Set<String> nombres() {
      Set<String> s = new HashSet<String>();
      for ( Destino d : destinos.values() ) {
        s.add( BaseDePrueba.nombreSolo( d.nombre ).toLowerCase( Locale.ROOT ) );
      }
      return s;
    }

    /** Las originales (de la solucion) y las del sistema y de deny sin comodines, sin mayusculas. */
    Set<String> prohibidas() {
      Set<String> s = new HashSet<String>( SeguridadBases.DEL_SISTEMA );
      for ( BaseDePrueba b : pack.bases ) {
        if ( !vacio( b.baseOriginal ) ) {
          s.add( BaseDePrueba.nombreSolo( b.baseOriginal ).toLowerCase( Locale.ROOT ) );
        }
      }
      for ( String d : pack.seguridadEfectiva().prohibidas ) {
        if ( !vacio( d ) && d.indexOf( '*' ) < 0 && d.indexOf( '?' ) < 0 ) {
          s.add( d.trim().toLowerCase( Locale.ROOT ) );
        }
      }
      s.removeAll( nombres() );
      return s;
    }

    /** Base original (sin mayusculas) → base de prueba de esta sesion: para los scripts y los esquemas de los steps. */
    Map<String, String> renombres() {
      Map<String, String> m = new LinkedHashMap<String, String>();
      for ( Destino d : destinos.values() ) {
        if ( !vacio( d.base.baseOriginal ) ) {
          m.put( BaseDePrueba.nombreSolo( d.base.baseOriginal ).toLowerCase( Locale.ROOT ), BaseDePrueba.nombreSolo(
            d.nombre ) );
        }
        String sinSufijo = BaseDePrueba.nombreSolo( d.base.base );
        if ( !sufijo.isEmpty() ) {
          m.put( sinSufijo.toLowerCase( Locale.ROOT ), BaseDePrueba.nombreSolo( d.nombre ) );
        }
      }
      return m;
    }

    /**
     * Antes de un caso: borra y crea cada base de prueba y corre sus scripts y los del caso (con los nombres
     * originales cambiados). Lanza {@link Falla} si algo no se puede (servidor caido, contraseña, script).
     */
    void recrear( Caso caso ) throws Falla {
      Set<String> pruebas = nombres();
      Map<String, String> ren = renombres();
      for ( Destino d : destinos.values() ) {
        try {
          vaciar( d );
        } catch ( Falla f ) {
          throw f;
        } catch ( Throwable t ) {
          throw new Falla( new Mensaje( "db.err.recreate", d.describir(), RunMotor.primeraLinea( t ) ) );
        }
        List<String> scripts = new ArrayList<String>( d.base.scripts );
        if ( caso != null && caso.scriptsBase != null ) {
          for ( Map.Entry<String, List<String>> e : caso.scriptsBase.entrySet() ) {
            if ( e.getKey() != null && e.getKey().trim().equalsIgnoreCase( d.base.conexion.trim() )
              && e.getValue() != null ) {
              scripts.addAll( e.getValue() );
            }
          }
        }
        if ( scripts.isEmpty() ) {
          continue;
        }
        Database db = conectar( d, d.nombre );
        try {
          for ( String sc : scripts ) {
            correrScript( db, d, sc, pruebas, ren );
          }
        } finally {
          db.disconnect();
        }
      }
    }

    private void correrScript( Database db, Destino d, String ruta, Set<String> pruebas, Map<String, String> ren )
      throws Falla {
      File f = archivoDelPack( ruta );
      String texto;
      try {
        texto = new String( Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 );
      } catch ( Exception e ) {
        throw new Falla( new Mensaje( "db.err.script_missing", ruta ) );
      }
      boolean mysql = d.base.esMysql();
      List<String> sts = sentencias( texto, mysql );
      for ( int i = 0; i < sts.size(); i++ ) {
        String st = renombrar( sts.get( i ), ren );
        if ( crearOBorrarBase( st, mysql ) && scriptAjeno( st, pruebas ) == null ) {
          continue; // CREATE/DROP DATABASE de la de prueba: ya se recreo
        }
        String ajeno = scriptAjeno( st, pruebas );
        if ( ajeno != null ) {
          throw new Falla( new Mensaje( "db.err.script_foreign", ruta, i + 1, ajeno ) );
        }
        try ( Statement s = db.getConnection().createStatement() ) {
          s.execute( st );
        } catch ( SQLException e ) {
          throw new Falla( new Mensaje( "db.err.script", ruta, i + 1, linea( st ), RunMotor.primeraLinea( e ) ) );
        }
      }
    }

    private File archivoDelPack( String ruta ) throws Falla {
      String r = ruta == null ? "" : ruta.trim().replace( '\\', '/' );
      if ( r.isEmpty() || CorrectorPack.motivoRuta( r ) != null || dirPack == null ) {
        throw new Falla( new Mensaje( "db.err.script_missing", ruta ) );
      }
      File f = new File( dirPack, r.replace( '/', File.separatorChar ) );
      if ( !f.isFile() || !CorrectorSandbox.canonica( f ).startsWith( CorrectorSandbox.canonica( dirPack ) ) ) {
        throw new Falla( new Mensaje( "db.err.script_missing", ruta ) );
      }
      return f;
    }

    /**
     * «Probar conexion» del asistente (0.7.0): crea cada base de prueba (como antes de un caso, sin scripts) y la
     * vuelve a borrar (en H2 la deja vacia: por TCP no se puede borrar el archivo). No toca ninguna otra base: las
     * reglas de seguridad ya se miraron al armar la sesion. Lanza {@link Falla} con lo que no se pudo.
     */
    void probar() throws Falla {
      for ( Destino d : destinos.values() ) {
        try {
          vaciar( d );
          if ( !BaseDePrueba.H2.equals( d.base.tipoNormal() ) ) {
            String q = d.base.esMysql() ? "`" : "\"";
            String admin = d.base.esMysql() ? "" : vacio( d.base.baseAdmin ) ? "postgres" : d.base.baseAdmin.trim();
            Database db = conectar( d, admin );
            try {
              ejecutar( db, "DROP DATABASE IF EXISTS " + q + BaseDePrueba.nombreSolo( d.nombre ) + q );
            } finally {
              db.disconnect();
            }
          }
        } catch ( Falla f ) {
          throw f;
        } catch ( Throwable t ) {
          throw new Falla( new Mensaje( "db.err.recreate", d.describir(), RunMotor.primeraLinea( t ) ) );
        }
      }
    }

    /** DROP + CREATE de la base (MySQL, PostgreSQL) o DROP ALL OBJECTS (H2). */
    private void vaciar( Destino d ) throws Exception {
      String tipo = d.base.tipoNormal();
      if ( BaseDePrueba.H2.equals( tipo ) ) {
        Database db = conectar( d, d.nombre );
        try {
          ejecutar( db, "DROP ALL OBJECTS" );
        } finally {
          db.disconnect();
        }
        return;
      }
      String q = d.base.esMysql() ? "`" : "\"";
      String nombre = q + BaseDePrueba.nombreSolo( d.nombre ) + q;
      String admin = d.base.esMysql() ? "" : vacio( d.base.baseAdmin ) ? "postgres" : d.base.baseAdmin.trim();
      Database db = conectar( d, admin );
      try {
        ejecutar( db, "DROP DATABASE IF EXISTS " + nombre );
        ejecutar( db, "CREATE DATABASE " + nombre );
      } finally {
        db.disconnect();
      }
    }

    private static void ejecutar( Database db, String sql ) throws SQLException {
      try ( Statement s = db.getConnection().createStatement() ) {
        s.execute( sql );
      }
    }

    /** Una conexion de Kettle a esa base (la de prueba o la de administracion), ya conectada. */
    Database conectar( Destino d, String base ) throws Falla {
      DatabaseMeta dm = meta( d, base );
      Database db = new Database( dm );
      try {
        db.connect();
        if ( !db.getConnection().getAutoCommit() ) {
          db.getConnection().setAutoCommit( true );
        }
        return db;
      } catch ( Throwable t ) {
        try {
          db.disconnect();
        } catch ( Throwable ignored ) {
          // nada
        }
        throw new Falla( new Mensaje( "db.err.connect", d.describir(), RunMotor.primeraLinea( t ) ) );
      }
    }

    /** El DatabaseMeta de la base de prueba (acceso nativo, opciones del pack, contraseña en memoria). */
    DatabaseMeta meta( Destino d, String base ) {
      BaseDePrueba b = d.base;
      DatabaseMeta dm = new DatabaseMeta( "pentaho-live-test-" + d.indice, b.tipoNormal(), "Native", n( b.servidor ),
        base == null ? "" : base, n( b.puerto ), n( b.usuario ), "" );
      ponerClave( dm, d.clave );
      for ( Map.Entry<String, String> o : b.opciones.entrySet() ) {
        if ( !vacio( o.getKey() ) ) {
          dm.addExtraOption( b.tipoNormal(), o.getKey().trim(), o.getValue() == null ? "" : o.getValue() );
        }
      }
      return dm;
    }

    /**
     * El SELECT de una tabla del resultado: todas sus columnas menos las ignoradas, ordenada por la clave (las
     * columnas que estan), hasta maxFilas filas. Si la tabla no existe, una tabla vacia sin columnas.
     */
    Captura capturar( TablaSalida t, long maxFilas ) throws Falla {
      Destino d = destino( t.conexion );
      if ( d == null && destinos.size() == 1 ) {
        d = destinos.values().iterator().next();
      }
      if ( d == null ) {
        throw new Falla( new Mensaje( "db.err.table_connection", t.nombre, t.conexion ) );
      }
      Captura c = new Captura();
      Database db = conectar( d, d.nombre );
      try {
        Connection cn = db.getConnection();
        String sql = "SELECT * FROM " + db.getDatabaseMeta().quoteField( t.nombre.trim() );
        int punto = t.nombre.trim().indexOf( '.' );
        if ( punto > 0 ) { // 0.7.1: "gym_dw.ventas", con el esquema de la base original llevado a la de prueba
          String esquema = t.nombre.trim().substring( 0, punto ).trim();
          String nuevo = renombres().get( esquema.toLowerCase( Locale.ROOT ) );
          sql = "SELECT * FROM " + db.getDatabaseMeta().getQuotedSchemaTableCombination( nuevo != null ? nuevo
            : esquema, t.nombre.trim().substring( punto + 1 ).trim() );
        }
        List<String> nombres = new ArrayList<String>();
        List<List<Object>> filas = new ArrayList<List<Object>>();
        List<Integer> usar = new ArrayList<Integer>();
        try ( Statement st = cn.createStatement() ) {
          if ( maxFilas < Integer.MAX_VALUE ) {
            st.setMaxRows( (int) maxFilas + 1 );
          }
          try ( ResultSet rs = st.executeQuery( sql ) ) {
            ResultSetMetaData md = rs.getMetaData();
            for ( int i = 1; i <= md.getColumnCount(); i++ ) {
              String n = md.getColumnLabel( i );
              if ( !t.ignora( n ) ) {
                usar.add( i );
                nombres.add( n );
                c.tipos.add( new Pack.Columna( n, tipoKettle( md.getColumnType( i ), md.getScale( i ) ) ) );
              }
            }
            while ( rs.next() ) {
              if ( filas.size() >= maxFilas ) {
                c.cortada = true;
                break;
              }
              List<Object> f = new ArrayList<Object>();
              for ( int i : usar ) {
                f.add( rs.getObject( i ) );
              }
              filas.add( f );
            }
          }
        } catch ( SQLException e ) {
          c.falta = RunMotor.primeraLinea( e );
          c.tabla = CorrectorTabla.vacia( Collections.<String>emptyList() );
          return c;
        }
        c.tabla = CorrectorTabla.de( nombres, filas );
        c.tabla.cortada = c.cortada;
        ordenar( c.tabla, t.columnasClave );
        c.clavePrimaria.addAll( clavePrimaria( cn, d, t.nombre.trim() ) );
        return c;
      } finally {
        db.disconnect();
      }
    }
  }

  /** Una tabla leida de la base de prueba. */
  static final class Captura {

    CorrectorTabla tabla;
    /** Los tipos de Kettle de sus columnas (de los metadatos JDBC). */
    final List<Pack.Columna> tipos = new ArrayList<Pack.Columna>();
    /** La clave primaria de la tabla (metadatos), o vacia. */
    final List<String> clavePrimaria = new ArrayList<String>();
    boolean cortada;
    /** La tabla no se pudo leer (no existe): el error. */
    String falta;
  }

  /** No se pudo preparar o leer una base de prueba: el mensaje traducible. */
  static final class Falla extends Exception {

    private static final long serialVersionUID = 1L;
    final transient Mensaje mensaje;

    Falla( Mensaje m ) {
      super( m.en() );
      this.mensaje = m;
    }
  }

  /** Pone la contraseña en el DatabaseMeta, cifrada como la guarda Kettle (asi no se le sustituyen variables). */
  static void ponerClave( DatabaseMeta dm, String clave ) {
    dm.setPassword( clave == null || clave.isEmpty() ? "" : Encr.PASSWORD_ENCRYPTED_PREFIX + Encr.encryptPassword(
      clave ) );
  }

  /** Ordena las filas por las columnas clave que estan (texto, sin mayusculas): el orden no cambia la nota. */
  static void ordenar( CorrectorTabla t, List<String> claves ) {
    final List<Integer> ix = new ArrayList<Integer>();
    for ( String k : claves == null ? Collections.<String>emptyList() : claves ) {
      int i = t.indice( k );
      if ( i >= 0 ) {
        ix.add( i );
      }
    }
    if ( ix.isEmpty() ) {
      return;
    }
    Collections.sort( t.filas, new Comparator<String[]>() {
      @Override
      public int compare( String[] a, String[] b ) {
        for ( int i : ix ) {
          String x = i < a.length && a[ i ] != null ? a[ i ] : "";
          String y = i < b.length && b[ i ] != null ? b[ i ] : "";
          int c = comparar( x, y );
          if ( c != 0 ) {
            return c;
          }
        }
        return 0;
      }
    } );
  }

  private static int comparar( String x, String y ) {
    try {
      return new java.math.BigDecimal( x ).compareTo( new java.math.BigDecimal( y ) );
    } catch ( NumberFormatException e ) {
      return x.compareToIgnoreCase( y );
    }
  }

  private static List<String> clavePrimaria( Connection cn, Destino d, String tabla ) {
    List<String> l = new ArrayList<String>();
    try {
      DatabaseMetaData md = cn.getMetaData();
      String catalogo = d.base.esMysql() ? BaseDePrueba.nombreSolo( d.nombre ) : null;
      for ( String t : new LinkedHashSet<String>( java.util.Arrays.asList( tabla, tabla.toUpperCase( Locale.ROOT ),
        tabla.toLowerCase( Locale.ROOT ) ) ) ) {
        try ( ResultSet rs = md.getPrimaryKeys( catalogo, null, t ) ) {
          Map<Integer, String> orden = new java.util.TreeMap<Integer, String>();
          while ( rs.next() ) {
            orden.put( rs.getInt( "KEY_SEQ" ), rs.getString( "COLUMN_NAME" ) );
          }
          if ( !orden.isEmpty() ) {
            l.addAll( orden.values() );
            return l;
          }
        }
      }
    } catch ( Throwable ignored ) {
      // sin clave primaria
    }
    return l;
  }

  /** El tipo de Kettle para un tipo JDBC. */
  static String tipoKettle( int jdbc, int escala ) {
    switch ( jdbc ) {
      case Types.TINYINT:
      case Types.SMALLINT:
      case Types.INTEGER:
      case Types.BIGINT:
        return "Integer";
      case Types.DECIMAL:
      case Types.NUMERIC:
        return escala == 0 ? "Integer" : "Number";
      case Types.FLOAT:
      case Types.REAL:
      case Types.DOUBLE:
        return "Number";
      case Types.DATE:
      case Types.TIMESTAMP:
      case Types.TIMESTAMP_WITH_TIMEZONE:
        return "Date";
      case Types.BIT:
      case Types.BOOLEAN:
        return "Boolean";
      default:
        return "String";
    }
  }

  // ------------------------------------------------------------------ ayudas

  private static String primero( String... v ) {
    for ( String s : v ) {
      if ( s != null && !s.trim().isEmpty() ) {
        return s.trim();
      }
    }
    return "";
  }

  private static String linea( String sql ) {
    String t = sql.trim().replaceAll( "\\s+", " " );
    return t.length() > 80 ? t.substring( 0, 77 ) + "..." : t;
  }

  static String n( String s ) {
    return s == null ? "" : s.trim();
  }

  static boolean vacio( String s ) {
    return s == null || s.trim().isEmpty();
  }
}
