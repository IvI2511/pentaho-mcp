package io.github.ivi2511.pentahomcp;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.pentaho.di.core.database.Database;
import org.pentaho.di.core.database.DatabaseMeta;

import io.github.ivi2511.pentahomcp.corrector.modelo.BaseDePrueba;

/**
 * «Generar el script desde la base actual» del asistente «Crear pack» (0.7.0): lee la base ORIGINAL de una conexion
 * de la solucion y escribe un script SQL con sus tablas (y sus datos) para que el docente lo revise y lo use como
 * script de la base de prueba. <b>Nunca escribe en la base</b>:
 * <ul>
 * <li>la conexion se marca de solo lectura ({@code setReadOnly(true)}: en MySQL es {@code SET SESSION TRANSACTION
 * READ ONLY}, en PostgreSQL {@code SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY});</li>
 * <li>lo unico que se ejecuta pasa por {@link #consulta}, que solo deja pasar {@code SELECT} y {@code SHOW}; lo demas
 * son metadatos JDBC ({@code getTables}, {@code getColumns}, {@code getPrimaryKeys}, {@code getImportedKeys});</li>
 * <li>basta un usuario con permiso de lectura.</li>
 * </ul>
 * El script: en MySQL/MariaDB el {@code SHOW CREATE TABLE} de cada tabla (entre {@code SET FOREIGN_KEY_CHECKS=0/1});
 * en PostgreSQL y H2 un {@code CREATE TABLE} con las columnas, sus tipos, NOT NULL y la clave primaria, y las claves
 * foraneas al final. Despues los datos ({@code INSERT} de a {@value #FILAS_POR_INSERT} filas), salvo en las tablas
 * que carga la solucion (van vacias: el alumno las tiene que llenar) y como mucho {@code maxFilas} filas por tabla.
 * Las tablas van en el orden de sus claves foraneas. Sin {@code CREATE DATABASE} ni {@code USE}: el corrector corre
 * el script dentro de la base de prueba. La contraseña solo vive en la conexion (no va al script ni a ningun log).
 *
 * @dueño corrector 0.7.0
 */
final class CorrectorVolcado {

  static final int FILAS_POR_INSERT = 100;
  static final int MAX_FILAS = 100000;
  private static final Pattern SOLO_LECTURA = Pattern.compile( "(?is)^\\s*(select|show)\\b.*" );

  private CorrectorVolcado() {
  }

  /** Lo que se escribio. */
  static final class Volcado {

    File archivo;
    final List<String> tablas = new ArrayList<String>();
    long filas;
    /** Las tablas que carga la solucion: solo su estructura. */
    final List<String> vacias = new ArrayList<String>();
    /** Las que tenian mas de maxFilas filas (quedaron las primeras). */
    final List<String> cortadas = new ArrayList<String>();
  }

  /** Una columna de una tabla (metadatos). */
  private static final class Columna {

    String nombre;
    String tipo;
    int jdbc;
    int tamano;
    int decimales;
    boolean nula;
  }

  /**
   * Lee la base {@code base} del servidor de {@code b} (tipo, servidor, puerto, usuario y opciones; su campo base no
   * se usa) y escribe el script en {@code destino}.
   *
   * @param soloEstructura las tablas (sin mayusculas) que van sin datos
   * @param maxFilas filas por tabla como mucho
   */
  static Volcado volcar( BaseDePrueba b, String base, String clave, Collection<String> soloEstructura, File destino,
                         int maxFilas ) throws Exception {
    DatabaseMeta dm = new DatabaseMeta( "pentaho-live-dump", b.tipoNormal(), "Native", CorrectorBases.n( b.servidor ),
      base, CorrectorBases.n( b.puerto ), CorrectorBases.n( b.usuario ), "" );
    CorrectorBases.ponerClave( dm, clave );
    for ( Map.Entry<String, String> o : b.opciones.entrySet() ) {
      dm.addExtraOption( b.tipoNormal(), o.getKey(), o.getValue() );
    }
    Set<String> vacias = new LinkedHashSet<String>();
    for ( String s : soloEstructura == null ? Collections.<String>emptyList() : soloEstructura ) {
      vacias.add( CorrectorScripts.tabla( s ) );
    }
    Database db = new Database( dm );
    db.connect();
    File tmp = new File( destino.getAbsoluteFile().getParentFile(), destino.getName() + ".parcial" );
    try {
      Connection cn = db.getConnection();
      try {
        cn.setReadOnly( true );
      } catch ( SQLException ignorada ) {
        // H2 y algunos drivers no lo soportan: igual solo se leen metadatos y SELECT
      }
      Volcado v;
      try ( Writer w = new BufferedWriter( new OutputStreamWriter( Files.newOutputStream( tmp.toPath() ),
        StandardCharsets.UTF_8 ) ) ) {
        v = escribir( cn, b, base, vacias, w, Math.max( 1, maxFilas ) );
      }
      Files.move( tmp.toPath(), destino.toPath(), StandardCopyOption.REPLACE_EXISTING );
      v.archivo = destino.getAbsoluteFile();
      return v;
    } finally {
      db.disconnect();
      if ( tmp.exists() && !tmp.delete() ) {
        tmp.deleteOnExit();
      }
    }
  }

  private static Volcado escribir( Connection cn, BaseDePrueba b, String base, Set<String> vacias, Writer w,
                                   int maxFilas ) throws Exception {
    boolean mysql = b.esMysql();
    boolean h2 = BaseDePrueba.H2.equals( b.tipoNormal() );
    String q = mysql ? "`" : "\"";
    DatabaseMetaData md = cn.getMetaData();
    String catalogo = mysql ? base : null;
    String esquema = mysql ? null : h2 ? "PUBLIC" : "public";
    List<String> nombres = new ArrayList<String>();
    try ( ResultSet rs = md.getTables( catalogo, esquema, "%", new String[] { "TABLE" } ) ) {
      while ( rs.next() ) {
        nombres.add( rs.getString( "TABLE_NAME" ) );
      }
    }
    Collections.sort( nombres, String.CASE_INSENSITIVE_ORDER );
    // padres antes que hijos
    Map<String, Set<String>> padres = new LinkedHashMap<String, Set<String>>();
    Map<String, List<String>> foraneas = new LinkedHashMap<String, List<String>>();
    for ( String t : nombres ) {
      Set<String> ps = new LinkedHashSet<String>();
      Map<String, String[]> porNombre = new TreeMap<String, String[]>();
      try ( ResultSet rs = md.getImportedKeys( catalogo, esquema, t ) ) {
        while ( rs.next() ) {
          String padre = rs.getString( "PKTABLE_NAME" );
          ps.add( padre );
          String fk = rs.getString( "FK_NAME" );
          String clave = ( fk == null ? padre : fk ) + "#" + padre;
          String[] x = porNombre.get( clave );
          if ( x == null ) {
            x = new String[] { "", "", padre };
            porNombre.put( clave, x );
          }
          x[ 0 ] += ( x[ 0 ].isEmpty() ? "" : ", " ) + q + rs.getString( "FKCOLUMN_NAME" ) + q;
          x[ 1 ] += ( x[ 1 ].isEmpty() ? "" : ", " ) + q + rs.getString( "PKCOLUMN_NAME" ) + q;
        }
      }
      ps.remove( t );
      padres.put( t, ps );
      List<String> fks = new ArrayList<String>();
      for ( String[] x : porNombre.values() ) {
        fks.add( "ALTER TABLE " + q + t + q + " ADD FOREIGN KEY (" + x[ 0 ] + ") REFERENCES " + q + x[ 2 ] + q + " ("
          + x[ 1 ] + ");" );
      }
      foraneas.put( t, fks );
    }
    List<String> orden = new ArrayList<String>();
    Set<String> puestas = new LinkedHashSet<String>();
    boolean avanzo = true;
    while ( puestas.size() < nombres.size() && avanzo ) {
      avanzo = false;
      for ( String t : nombres ) {
        if ( !puestas.contains( t ) && puestas.containsAll( interseccion( padres.get( t ), nombres ) ) ) {
          orden.add( t );
          puestas.add( t );
          avanzo = true;
        }
      }
    }
    for ( String t : nombres ) {
      if ( puestas.add( t ) ) {
        orden.add( t ); // un ciclo de claves foraneas: en orden de nombre
      }
    }

    Volcado v = new Volcado();
    String ahora = new SimpleDateFormat( "yyyy-MM-dd HH:mm", Locale.ROOT ).format( new Date() );
    w.write( "-- Script generated by Pentaho Live from the database " + base + " (" + b.tipoNormal() + " "
      + CorrectorBases.n( b.servidor ) + ( CorrectorBases.vacio( b.puerto ) ? "" : ":" + b.puerto.trim() ) + ") on "
      + ahora + ".\n" );
    w.write( "-- Read-only: the database was not modified. Review it before using it in a pack: it runs in the\n"
      + "-- empty test database before every case. Tables loaded by the solution are created empty.\n\n" );
    if ( mysql ) {
      w.write( "SET FOREIGN_KEY_CHECKS=0;\n\n" );
    }
    try ( Statement st = cn.createStatement() ) {
      for ( String t : orden ) {
        v.tablas.add( t );
        if ( mysql ) {
          try ( ResultSet rs = consulta( st, "SHOW CREATE TABLE " + q + t + q ) ) {
            if ( rs.next() ) {
              w.write( rs.getString( 2 ).replaceAll( "\\s+AUTO_INCREMENT=\\d+", "" ) + ";\n\n" );
            }
          }
        } else {
          w.write( crearTabla( md, catalogo, esquema, t, q ) + "\n\n" );
        }
      }
      for ( String t : orden ) {
        if ( vacias.contains( t.toLowerCase( Locale.ROOT ) ) ) {
          v.vacias.add( t );
          w.write( "-- " + t + ": loaded by the solution, no rows.\n\n" );
          continue;
        }
        v.filas += datos( st, t, q, mysql, BaseDePrueba.POSTGRESQL.equals( b.tipoNormal() ), maxFilas, w, v );
      }
    }
    if ( mysql ) {
      w.write( "SET FOREIGN_KEY_CHECKS=1;\n" );
    } else {
      for ( String t : orden ) {
        for ( String fk : foraneas.get( t ) ) {
          w.write( fk + "\n" );
        }
      }
    }
    return v;
  }

  private static Set<String> interseccion( Set<String> a, List<String> b ) {
    Set<String> s = new LinkedHashSet<String>( a == null ? Collections.<String>emptySet() : a );
    s.retainAll( b );
    return s;
  }

  /** CREATE TABLE de los metadatos: columnas con su tipo, NOT NULL y la clave primaria. */
  private static String crearTabla( DatabaseMetaData md, String catalogo, String esquema, String t, String q )
    throws SQLException {
    List<Columna> cols = new ArrayList<Columna>();
    try ( ResultSet rs = md.getColumns( catalogo, esquema, t, "%" ) ) {
      while ( rs.next() ) {
        Columna c = new Columna();
        c.nombre = rs.getString( "COLUMN_NAME" );
        c.tipo = rs.getString( "TYPE_NAME" );
        c.jdbc = rs.getInt( "DATA_TYPE" );
        c.tamano = rs.getInt( "COLUMN_SIZE" );
        c.decimales = rs.getInt( "DECIMAL_DIGITS" );
        c.nula = rs.getInt( "NULLABLE" ) != DatabaseMetaData.columnNoNulls;
        cols.add( c );
      }
    }
    Map<Short, String> pk = new TreeMap<Short, String>();
    try ( ResultSet rs = md.getPrimaryKeys( catalogo, esquema, t ) ) {
      while ( rs.next() ) {
        pk.put( rs.getShort( "KEY_SEQ" ), rs.getString( "COLUMN_NAME" ) );
      }
    }
    StringBuilder s = new StringBuilder( "CREATE TABLE " + q + t + q + " (\n" );
    for ( int i = 0; i < cols.size(); i++ ) {
      Columna c = cols.get( i );
      s.append( "  " ).append( q ).append( c.nombre ).append( q ).append( ' ' ).append( tipo( c ) );
      if ( !c.nula ) {
        s.append( " NOT NULL" );
      }
      if ( i < cols.size() - 1 || !pk.isEmpty() ) {
        s.append( ',' );
      }
      s.append( '\n' );
    }
    if ( !pk.isEmpty() ) {
      List<String> ks = new ArrayList<String>();
      for ( String k : pk.values() ) {
        ks.add( q + k + q );
      }
      s.append( "  PRIMARY KEY (" ).append( String.join( ", ", ks ) ).append( ")\n" );
    }
    return s.append( ");" ).toString();
  }

  /** El tipo de una columna con su tamaño cuando lo lleva (VARCHAR(40), DECIMAL(10,2)); sin tamaño si no tiene tope. */
  static String tipo( Columna c ) {
    String t = c.tipo == null ? "VARCHAR" : c.tipo.trim();
    boolean grande = c.tamano <= 0 || c.tamano >= 65535;
    switch ( c.jdbc ) {
      case Types.CHAR:
      case Types.VARCHAR:
      case Types.NCHAR:
      case Types.NVARCHAR:
      case Types.BINARY:
      case Types.VARBINARY:
        return grande || t.contains( "(" ) || t.equalsIgnoreCase( "text" ) ? t : t + "(" + c.tamano + ")";
      case Types.DECIMAL:
      case Types.NUMERIC:
        return grande || t.contains( "(" ) ? t : t + "(" + c.tamano + "," + Math.max( 0, c.decimales ) + ")";
      default:
        return t;
    }
  }

  /** Los INSERT de una tabla, de a FILAS_POR_INSERT filas; devuelve cuantas filas. */
  private static long datos( Statement st, String t, String q, boolean mysql, boolean pg, int maxFilas, Writer w,
                             Volcado v ) throws Exception {
    st.setMaxRows( maxFilas + 1 );
    st.setFetchSize( Math.min( 1000, maxFilas + 1 ) );
    long n = 0;
    try ( ResultSet rs = consulta( st, "SELECT * FROM " + q + t + q ) ) {
      ResultSetMetaData rm = rs.getMetaData();
      int k = rm.getColumnCount();
      StringBuilder cabeza = new StringBuilder( "INSERT INTO " + q + t + q + " (" );
      for ( int i = 1; i <= k; i++ ) {
        cabeza.append( i > 1 ? ", " : "" ).append( q ).append( rm.getColumnName( i ) ).append( q );
      }
      cabeza.append( ") VALUES\n" );
      int enEste = 0;
      while ( rs.next() ) {
        if ( n >= maxFilas ) {
          v.cortadas.add( t );
          break;
        }
        w.write( enEste == 0 ? cabeza.toString() : ",\n" );
        w.write( "(" );
        for ( int i = 1; i <= k; i++ ) {
          if ( i > 1 ) {
            w.write( ", " );
          }
          w.write( literal( rs, i, rm.getColumnType( i ), mysql, pg ) );
        }
        w.write( ")" );
        n++;
        if ( ++enEste == FILAS_POR_INSERT ) {
          w.write( ";\n" );
          enEste = 0;
        }
      }
      if ( enEste > 0 ) {
        w.write( ";\n" );
      }
    }
    w.write( "\n" );
    return n;
  }

  /** Un valor como literal SQL: NULL, numero, TRUE/FALSE, X'..' o texto entre comillas. */
  static String literal( ResultSet rs, int i, int jdbc, boolean mysql, boolean pg ) throws SQLException {
    switch ( jdbc ) {
      case Types.BIT:
      case Types.BOOLEAN: {
        Object o = rs.getObject( i );
        if ( o == null ) {
          return "NULL";
        }
        if ( o instanceof Boolean ) {
          return (Boolean) o ? "TRUE" : "FALSE";
        }
        if ( o instanceof byte[] ) {
          return hex( (byte[]) o, pg );
        }
        return texto( String.valueOf( o ), mysql );
      }
      case Types.TINYINT:
      case Types.SMALLINT:
      case Types.INTEGER:
      case Types.BIGINT:
      case Types.DECIMAL:
      case Types.NUMERIC:
      case Types.REAL:
      case Types.FLOAT:
      case Types.DOUBLE: {
        Object o = rs.getObject( i );
        if ( o == null ) {
          return "NULL";
        }
        if ( o instanceof BigDecimal ) {
          return ( (BigDecimal) o ).toPlainString();
        }
        if ( o instanceof Number ) {
          double d = ( (Number) o ).doubleValue();
          return Double.isNaN( d ) || Double.isInfinite( d ) ? "NULL" : String.valueOf( o );
        }
        return texto( String.valueOf( o ), mysql );
      }
      case Types.BINARY:
      case Types.VARBINARY:
      case Types.LONGVARBINARY:
      case Types.BLOB: {
        byte[] x = rs.getBytes( i );
        return x == null ? "NULL" : hex( x, pg );
      }
      default: {
        String s = rs.getString( i );
        return s == null ? "NULL" : texto( s, mysql );
      }
    }
  }

  static String texto( String s, boolean mysql ) {
    String x = s.replace( "'", "''" );
    if ( mysql ) {
      x = x.replace( "\\", "\\\\" );
    }
    return "'" + x + "'";
  }

  private static String hex( byte[] b, boolean pg ) {
    StringBuilder s = new StringBuilder();
    for ( byte x : b ) {
      s.append( String.format( "%02X", x & 0xff ) );
    }
    return pg ? "decode('" + s + "', 'hex')" : "X'" + s + "'";
  }

  /** La unica forma de ejecutar SQL aca: solo SELECT y SHOW (nada que escriba). */
  static ResultSet consulta( Statement st, String sql ) throws SQLException {
    if ( !SOLO_LECTURA.matcher( sql ).matches() ) {
      throw new IllegalStateException( "read-only dump: refusing to run " + sql );
    }
    return st.executeQuery( sql );
  }

  /** El nombre propuesto para el script de una base: «gym_generado.sql». */
  static String nombreArchivo( String base ) {
    String b = BaseDePrueba.nombreSolo( base ).replaceAll( "[^A-Za-z0-9_.-]", "_" );
    return ( b.isEmpty() ? "base" : b ) + "_generado.sql";
  }

  /** Que no quede un .parcial si algo fallo (pruebas). */
  static boolean sinParcial( File destino ) throws IOException {
    return !new File( destino.getAbsoluteFile().getParentFile(), destino.getName() + ".parcial" ).exists();
  }
}
