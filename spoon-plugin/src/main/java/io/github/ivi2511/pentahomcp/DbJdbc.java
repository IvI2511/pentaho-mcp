package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.core.Result;
import org.pentaho.di.core.database.Database;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.encryption.Encr;
import org.pentaho.di.core.exception.KettleDatabaseException;
import org.pentaho.di.core.logging.LoggingObjectInterface;
import org.pentaho.di.core.logging.LoggingObjectType;
import org.pentaho.di.core.logging.SimpleLoggingObject;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.variables.VariableSpace;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;

/**
 * Lo que habla con la base de verdad (conectar, listar tablas, consultar). Nunca corre en el hilo
 * de UI: cada trabajo va a un pool propio de hilos daemon, con timeout, y desconecta siempre.
 * Recibe un DatabaseMeta YA COPIADO (clone() hecho en el hilo de UI) y un snapshot de variables.
 */
final class DbJdbc {

  static final int TIMEOUT_CONECTAR_S = 30;
  static final int TIMEOUT_EXPLORAR_S = 60;
  static final int TIMEOUT_CONSULTA_S = 60;
  static final int MAX_FILAS = 1000;
  static final int MAX_NOMBRES = 1000;
  static final int MAX_TEXTO_CELDA = 500;

  private static final LoggingObjectInterface LOG_OBJ =
    new SimpleLoggingObject( "Pentaho MCP", LoggingObjectType.SPOON, null );

  private static final ExecutorService POOL = Executors.newCachedThreadPool( new ThreadFactory() {
    private final AtomicInteger n = new AtomicInteger( 1 );

    @Override
    public Thread newThread( Runnable r ) {
      Thread t = new Thread( r, "pentaho-mcp-db-" + n.getAndIncrement() );
      t.setDaemon( true );
      return t;
    }
  } );

  /** Esquemas de sistema que no se listan salvo que se pidan. */
  private static final Set<String> ESQUEMAS_SISTEMA = new HashSet<String>( Arrays.asList(
    "information_schema", "pg_catalog", "pg_toast", "sys", "mysql", "performance_schema" ) );

  private static final Set<String> SQL_LECTURA = new HashSet<String>( Arrays.asList(
    "SELECT", "WITH", "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "PRAGMA" ) );

  private static final Pattern SQL_ESCRITURA = Pattern.compile( "\\b(INSERT|UPDATE|DELETE|MERGE|UPSERT|DROP|ALTER|"
    + "CREATE|TRUNCATE|GRANT|REVOKE|INTO|CALL|EXEC|EXECUTE|COPY|ATTACH|DETACH|VACUUM|REINDEX|LOCK|RENAME)\\b" );

  private DbJdbc() {
  }

  interface Trabajo<T> {
    T con( Database db ) throws Exception;
  }

  // ---------------------------------------------------------------- conectar con timeout

  /**
   * Conecta en un hilo del pool, corre el trabajo y desconecta en finally. Si la base no responde a
   * tiempo intenta cancelar y devuelve un ToolError (el hilo daemon termina solo y desconecta).
   */
  static <T> T conConexion( DatabaseMeta dm, VariableSpace vars, int timeoutSeg, Trabajo<T> t ) throws Exception {
    try {
      return conConexionCruda( dm, vars, timeoutSeg, t );
    } catch ( TimeoutException e ) {
      throw new ToolError( "Database '" + dm.getName() + "' did not respond within " + timeoutSeg + " s ("
        + destino( dm ) + "). Check that the server is up and reachable, or narrow the query." );
    } catch ( ExecutionException e ) {
      Throwable c = e.getCause();
      if ( c instanceof ToolError ) {
        throw (ToolError) c;
      }
      throw new ToolError( diagnosticar( dm, c )[1] );
    }
  }

  // ---------------------------------------------------------------- probar

  /** Conecta y devuelve producto/version, o ok=false con un diagnostico legible. Nunca tira por la base. */
  static ObjectNode probar( final DatabaseMeta dm, VariableSpace vars, int timeoutSeg ) throws Exception {
    ObjectNode o = Tools.obj();
    o.put( "connection", dm.getName() );
    o.put( "type", dm.getPluginId() );
    o.put( "target", destino( dm ) );
    String aviso = avisoArchivo( dm, vars ); // antes de conectar: SQLite/H2 crean el archivo si no existe
    final long t0 = System.currentTimeMillis();
    try {
      ObjectNode info = conConexionCruda( dm, vars, timeoutSeg, new Trabajo<ObjectNode>() {
        @Override
        public ObjectNode con( Database db ) throws Exception {
          ObjectNode x = Tools.obj();
          try {
            DatabaseMetaData md = db.getConnection().getMetaData();
            x.put( "product", md.getDatabaseProductName() + " " + md.getDatabaseProductVersion() );
            x.put( "driver", md.getDriverName() + " " + md.getDriverVersion() );
          } catch ( Throwable ignored ) {
            // algunos drivers no implementan metadata
          }
          return x;
        }
      } );
      o.put( "ok", true );
      o.setAll( info );
    } catch ( TimeoutException e ) {
      o.put( "ok", false );
      o.put( "problem", "timeout" );
      o.put( "message", "The database did not respond within " + timeoutSeg + " s. Check host, port and "
        + "firewall, or that the server is up." );
    } catch ( ExecutionException e ) {
      String[] d = diagnosticar( dm, e.getCause() );
      o.put( "ok", false );
      o.put( "problem", d[0] );
      o.put( "message", d[1] );
    }
    o.put( "ms", System.currentTimeMillis() - t0 );
    if ( aviso != null ) {
      o.put( "warning", aviso );
    }
    return o;
  }

  /**
   * SQLite y H2 embebido crean una base vacia si el archivo no existe, y la prueba da ok igual. Si el
   * archivo no esta, devuelve un aviso (una ruta mal escrita es el error tipico); si no, null.
   */
  static String avisoArchivo( DatabaseMeta dm ) {
    return avisoArchivo( dm, dm );
  }

  /**
   * Igual, resolviendo las variables con vars. La deteccion es la de los informes
   * (SecDestino.archivoFaltante): SQLite, H2 embebido, HSQLDB en archivo y JDBC generico, y decodifica
   * las rutas "file:///C:/x%20y/..." que da ${Internal.Entry.Current.Directory}.
   */
  static String avisoArchivo( DatabaseMeta dm, VariableSpace vars ) {
    try {
      File f = SecDestino.archivoFaltante( dm, vars == null ? dm : vars );
      if ( f == null ) {
        return null;
      }
      return "The file " + f.getAbsolutePath() + " did not exist: " + dm.getPluginId() + " creates an empty "
        + "database when connecting. If the database already had data, the path is misspelled.";
    } catch ( Throwable t ) {
      return null;
    }
  }

  /** Como conConexion pero deja pasar TimeoutException/ExecutionException para que el que llama decida. */
  private static <T> T conConexionCruda( final DatabaseMeta dm, final VariableSpace vars, int timeoutSeg,
    final Trabajo<T> t ) throws Exception {
    final AtomicReference<Database> ref = new AtomicReference<Database>();
    Future<T> f = POOL.submit( new Callable<T>() {
      @Override
      public T call() throws Exception {
        if ( vars != null ) {
          dm.shareVariablesWith( vars );
        }
        Database db = new Database( LOG_OBJ, dm );
        ref.set( db );
        try {
          if ( vars != null ) {
            db.shareVariablesWith( vars );
          }
          db.connect();
          return t.con( db );
        } finally {
          db.disconnect();
        }
      }
    } );
    try {
      return f.get( timeoutSeg, TimeUnit.SECONDS );
    } catch ( TimeoutException e ) {
      Database db = ref.get();
      if ( db != null ) {
        try {
          db.cancelQuery();
        } catch ( Throwable ignored ) {
          // mejor esfuerzo; el hilo daemon termina solo y desconecta en su finally
        }
      }
      f.cancel( true );
      throw e;
    }
  }

  // ---------------------------------------------------------------- explorar

  /** Tablas y vistas agrupadas por esquema. (worker) */
  static ObjectNode tablas( Database db, String esquema, String buscar, boolean sistema ) throws Exception {
    Map<String, Collection<String>> tablas = db.getTableMap( esquema );
    Map<String, Collection<String>> vistas;
    try {
      vistas = db.getViewMap( esquema );
    } catch ( Exception e ) {
      vistas = new TreeMap<String, Collection<String>>(); // la base no informa vistas
    }
    TreeMap<String, ObjectNode> porEsquema = new TreeMap<String, ObjectNode>( String.CASE_INSENSITIVE_ORDER );
    int[] total = { 0 };
    boolean[] truncado = { false };
    String filtro = buscar == null ? null : buscar.toLowerCase( Locale.ROOT );
    agrupar( tablas, "tables", porEsquema, esquema, filtro, sistema, total, truncado );
    agrupar( vistas, "views", porEsquema, esquema, filtro, sistema, total, truncado );
    ObjectNode o = Tools.obj();
    o.put( "connection", db.getDatabaseMeta().getName() );
    ArrayNode lista = o.putArray( "schemas" );
    for ( ObjectNode e : porEsquema.values() ) {
      lista.add( e );
    }
    o.put( "total", total[0] );
    if ( truncado[0] ) {
      o.put( "truncated", "There are more than " + MAX_NOMBRES + "; filter with 'search' or 'schema'" );
    }
    return o;
  }

  private static void agrupar( Map<String, Collection<String>> mapa, String clave, TreeMap<String, ObjectNode> salida,
    String esquemaPedido, String filtro, boolean sistema, int[] total, boolean[] truncado ) {
    for ( Map.Entry<String, Collection<String>> e : mapa.entrySet() ) {
      String esq = e.getKey() == null ? "" : e.getKey();
      if ( esquemaPedido == null && !sistema && ESQUEMAS_SISTEMA.contains( esq.toLowerCase( Locale.ROOT ) ) ) {
        continue;
      }
      List<String> nombres = new ArrayList<String>( e.getValue() );
      java.util.Collections.sort( nombres, String.CASE_INSENSITIVE_ORDER );
      for ( String n : nombres ) {
        if ( filtro != null && !n.toLowerCase( Locale.ROOT ).contains( filtro ) ) {
          continue;
        }
        if ( total[0] >= MAX_NOMBRES ) {
          truncado[0] = true;
          return;
        }
        ObjectNode g = salida.get( esq );
        if ( g == null ) {
          g = Tools.obj();
          if ( !esq.isEmpty() ) {
            g.put( "schema", esq );
          }
          salida.put( esq, g );
        }
        ArrayNode arr = g.has( clave ) ? (ArrayNode) g.get( clave ) : g.putArray( clave );
        arr.add( n );
        total[0]++;
      }
    }
  }

  /** Columnas de una tabla con su tipo Kettle (y clave primaria si la base la informa). (worker) */
  static ObjectNode columnas( Database db, String esquema, String tabla ) throws Exception {
    DatabaseMeta dm = db.getDatabaseMeta();
    RowMetaInterface rm = null;
    Exception primero = null;
    try {
      rm = db.getTableFieldsMeta( esquema, tabla );
    } catch ( Exception e ) {
      primero = e;
    }
    if ( rm == null ) {
      try {
        rm = db.getTableFields( dm.getQuotedSchemaTableCombination( esquema, tabla ) );
      } catch ( Exception e ) {
        throw primero != null ? primero : e;
      }
    }
    if ( rm == null ) {
      throw new ToolError( "Table '" + ( esquema == null ? "" : esquema + "." ) + tabla
        + "' not found. Use list_tables to see the exact names." );
    }
    Set<String> pk = new HashSet<String>();
    try {
      String[] k = db.getPrimaryKeyColumnNames( esquema == null ? tabla : dm.getSchemaTableCombination( esquema, tabla ) );
      if ( k != null ) {
        for ( String s : k ) {
          pk.add( s.toLowerCase( Locale.ROOT ) );
        }
      }
    } catch ( Throwable ignored ) {
      // la base no informa claves
    }
    ObjectNode o = Tools.obj();
    o.put( "connection", dm.getName() );
    if ( esquema != null ) {
      o.put( "schema", esquema );
    }
    o.put( "table", tabla );
    ArrayNode cols = o.putArray( "columns" );
    for ( ValueMetaInterface v : rm.getValueMetaList() ) {
      ObjectNode c = campo( v );
      if ( pk.contains( v.getName().toLowerCase( Locale.ROOT ) ) ) {
        c.put( "pk", true );
      }
      cols.add( c );
    }
    return o;
  }

  private static ObjectNode campo( ValueMetaInterface v ) {
    ObjectNode c = Tools.obj();
    c.put( "name", v.getName() );
    c.put( "type", v.getTypeDesc() );
    if ( v.getLength() > 0 ) {
      c.put( "length", v.getLength() );
    }
    if ( v.getPrecision() > 0 ) {
      c.put( "precision", v.getPrecision() );
    }
    if ( v.getOriginalColumnTypeName() != null ) {
      c.put( "db_type", v.getOriginalColumnTypeName() );
    }
    return c;
  }

  // ---------------------------------------------------------------- consultar

  /**
   * SELECT y compania: hasta 'rows' filas, todo como texto, con los tipos Kettle (lo que veria un Table
   * input). Si Kettle no puede convertir algun valor (p.ej. fechas guardadas como texto en SQLite) cae a
   * JDBC crudo y lo avisa, porque el Table input fallaria igual. (worker)
   */
  static ObjectNode consultar( Database db, String sql, int filas ) throws Exception {
    try {
      db.getConnection().setReadOnly( true ); // defensa extra (Postgres/MySQL la respetan)
    } catch ( Throwable ignored ) {
      // SQLite y otros no dejan cambiarlo despues de conectar
    }
    db.setQueryLimit( filas + 1 );
    List<Object[]> rows;
    try {
      rows = db.getRows( sql, filas + 1 );
    } catch ( KettleDatabaseException e ) {
      ObjectNode o;
      try {
        o = consultarJdbc( db, sql, filas );
      } catch ( Exception e2 ) {
        throw e; // el error original de Kettle es el que importa (SQL invalido, tabla inexistente...)
      }
      o.put( "warning", "Kettle could not convert the values (a Table input with this query would fail the same "
        + "way): " + diagnosticar( db.getDatabaseMeta(), e )[1] + ". They are shown as the driver returns them." );
      return o;
    }
    RowMetaInterface rm = db.getReturnRowMeta();
    if ( rm == null ) {
      rm = db.getQueryFields( sql, false );
    }
    ObjectNode o = Tools.obj();
    ArrayNode campos = o.putArray( "fields" );
    if ( rm != null ) {
      for ( ValueMetaInterface v : rm.getValueMetaList() ) {
        campos.addObject().put( "name", v.getName() ).put( "type", v.getTypeDesc() );
      }
    }
    ArrayNode out = o.putArray( "rows" );
    int n = 0;
    for ( Object[] r : rows ) {
      if ( n >= filas ) {
        break;
      }
      ArrayNode fila = out.addArray();
      int nc = rm == null ? 0 : rm.size();
      for ( int i = 0; i < nc; i++ ) {
        fila.add( texto( rm.getValueMeta( i ), r == null || i >= r.length ? null : r[i] ) );
      }
      n++;
    }
    o.put( "row_count", n );
    if ( rows.size() > filas ) {
      o.put( "has_more", true );
    }
    return o;
  }

  private static ObjectNode consultarJdbc( Database db, String sql, int filas ) throws Exception {
    Statement st = db.getConnection().createStatement();
    try {
      st.setMaxRows( filas + 1 );
      ResultSet rs = st.executeQuery( sql );
      try {
        ResultSetMetaData md = rs.getMetaData();
        ObjectNode o = Tools.obj();
        ArrayNode campos = o.putArray( "fields" );
        for ( int i = 1; i <= md.getColumnCount(); i++ ) {
          campos.addObject().put( "name", md.getColumnLabel( i ) ).put( "db_type", md.getColumnTypeName( i ) );
        }
        ArrayNode out = o.putArray( "rows" );
        int n = 0;
        boolean mas = false;
        while ( rs.next() ) {
          if ( n >= filas ) {
            mas = true;
            break;
          }
          ArrayNode fila = out.addArray();
          for ( int i = 1; i <= md.getColumnCount(); i++ ) {
            String v = rs.getString( i );
            fila.add( v != null && v.length() > MAX_TEXTO_CELDA ? v.substring( 0, MAX_TEXTO_CELDA ) + "...("
              + v.length() + " characters)" : v );
          }
          n++;
        }
        o.put( "row_count", n );
        if ( mas ) {
          o.put( "has_more", true );
        }
        return o;
      } finally {
        rs.close();
      }
    } finally {
      st.close();
    }
  }

  /** Sentencias de escritura (allow_write=true). (worker) */
  static ObjectNode ejecutar( Database db, String sql ) throws Exception {
    Result r = contarSentencias( sql ) > 1 ? db.execStatements( sql ) : db.execStatement( sql );
    if ( !db.isAutoCommit() ) {
      db.commit( true );
    }
    ObjectNode o = Tools.obj();
    o.put( "ok", true );
    if ( r != null ) {
      long afectadas = r.getNrLinesUpdated() + r.getNrLinesInput() + r.getNrLinesOutput() + r.getNrLinesDeleted();
      o.put( "affected_rows", afectadas );
    }
    return o;
  }

  private static String texto( ValueMetaInterface v, Object x ) {
    if ( x == null ) {
      return null;
    }
    String s;
    try {
      if ( v.isBinary() && x instanceof byte[] ) {
        return "<binary " + ( (byte[]) x ).length + " bytes>";
      }
      s = v.getString( x );
    } catch ( Exception e ) {
      s = String.valueOf( x );
    }
    if ( s != null && s.length() > MAX_TEXTO_CELDA ) {
      s = s.substring( 0, MAX_TEXTO_CELDA ) + "...(" + s.length() + " characters)";
    }
    return s;
  }

  // ---------------------------------------------------------------- solo lectura

  /**
   * Devuelve null si el SQL es de solo lectura, o el motivo por el que no lo es. Ignora literales,
   * identificadores entre comillas y comentarios.
   */
  static String motivoNoLectura( String sql ) {
    String limpio = sinLiterales( sql ).trim();
    while ( limpio.endsWith( ";" ) ) {
      limpio = limpio.substring( 0, limpio.length() - 1 ).trim();
    }
    if ( limpio.isEmpty() ) {
      return "the query is empty";
    }
    if ( limpio.indexOf( ';' ) >= 0 ) {
      return "the query has several statements separated by ';'";
    }
    String sinParen = limpio.replaceFirst( "^\\(+\\s*", "" );
    Matcher m = Pattern.compile( "^([A-Za-z]+)" ).matcher( sinParen );
    String primera = m.find() ? m.group( 1 ).toUpperCase( Locale.ROOT ) : "";
    if ( !SQL_LECTURA.contains( primera ) ) {
      return "the query starts with '" + primera + "' (only SELECT, WITH, SHOW, DESCRIBE, DESC, EXPLAIN and PRAGMA "
        + "are accepted)";
    }
    String mayus = limpio.toUpperCase( Locale.ROOT );
    Matcher w = SQL_ESCRITURA.matcher( mayus );
    if ( w.find() ) {
      return "the query contains '" + w.group( 1 ) + "', which can write or lock";
    }
    if ( "PRAGMA".equals( primera ) && limpio.indexOf( '=' ) >= 0 ) {
      return "a PRAGMA with '=' changes the database configuration";
    }
    return null;
  }

  /** Quita comentarios y reemplaza literales e identificadores entre comillas por espacios. */
  static String sinLiterales( String sql ) {
    StringBuilder sb = new StringBuilder( sql.length() );
    int i = 0;
    int n = sql.length();
    while ( i < n ) {
      char c = sql.charAt( i );
      char sig = i + 1 < n ? sql.charAt( i + 1 ) : '\0';
      if ( c == '-' && sig == '-' ) {
        while ( i < n && sql.charAt( i ) != '\n' ) {
          i++;
        }
        sb.append( ' ' );
      } else if ( c == '/' && sig == '*' ) {
        int fin = sql.indexOf( "*/", i + 2 );
        i = fin < 0 ? n : fin + 2;
        sb.append( ' ' );
      } else if ( c == '\'' || c == '"' || c == '`' || c == '[' ) {
        char cierre = c == '[' ? ']' : c;
        i++;
        while ( i < n ) {
          if ( sql.charAt( i ) == cierre ) {
            if ( cierre != ']' && i + 1 < n && sql.charAt( i + 1 ) == cierre ) {
              i += 2; // comilla duplicada = escapada
              continue;
            }
            break;
          }
          i++;
        }
        i++;
        sb.append( " x " );
      } else {
        sb.append( c );
        i++;
      }
    }
    return sb.toString();
  }

  /** Cantidad de sentencias (separadas por ';' fuera de literales). */
  static int contarSentencias( String sql ) {
    int k = 0;
    for ( String s : sinLiterales( sql ).split( ";" ) ) {
      if ( !s.trim().isEmpty() ) {
        k++;
      }
    }
    return k;
  }

  /** El SQL tal cual pero sin los ';' del final (algunos drivers, como Oracle, se quejan). */
  static String sinPuntoYComaFinal( String sql ) {
    String s = sql.trim();
    while ( s.endsWith( ";" ) ) {
      s = s.substring( 0, s.length() - 1 ).trim();
    }
    return s;
  }

  // ---------------------------------------------------------------- diagnostico de errores

  /** "host:puerto/base" sin credenciales, para mensajes. */
  static String destino( DatabaseMeta dm ) {
    String h = dm.environmentSubstitute( dm.getHostname() );
    String p = dm.environmentSubstitute( dm.getDatabasePortNumberString() );
    String b = dm.environmentSubstitute( dm.getDatabaseName() );
    StringBuilder sb = new StringBuilder();
    if ( h != null && !h.isEmpty() ) {
      sb.append( h );
      if ( p != null && !p.isEmpty() && !"-1".equals( p ) ) {
        sb.append( ':' ).append( p );
      }
      sb.append( '/' );
    }
    sb.append( b == null ? "" : b );
    return sb.toString();
  }

  /**
   * {codigo, mensaje}: missing_driver, bad_credentials, unknown_host, host_unreachable, database_not_found,
   * sql_error, error.
   */
  static String[] diagnosticar( DatabaseMeta dm, Throwable e ) {
    List<Throwable> cadena = new ArrayList<Throwable>();
    for ( Throwable c = e; c != null && cadena.size() < 12; c = c.getCause() ) {
      if ( cadena.contains( c ) ) {
        break;
      }
      cadena.add( c );
    }
    String detalle = detalle( cadena, dm );
    String todo = "";
    String sqlState = null;
    for ( Throwable c : cadena ) {
      todo += " " + String.valueOf( c.getMessage() ).toLowerCase( Locale.ROOT );
      if ( sqlState == null && c instanceof SQLException ) {
        sqlState = ( (SQLException) c ).getSQLState();
      }
    }
    String tipo = dm.getPluginId();
    for ( Throwable c : cadena ) {
      if ( c instanceof ClassNotFoundException || c instanceof NoClassDefFoundError ) {
        String clase = driverClass( dm );
        return new String[] { "missing_driver", "The JDBC driver for " + tipo + " is missing" + ( clase == null ? ""
          : " (" + clase + ")" ) + ". Copy " + DbTools.jarSugerido( tipo ) + " to data-integration/lib and restart "
          + "Spoon." };
      }
    }
    if ( todo.contains( "unabletofindclassmissingdriver" ) || todo.contains( "no suitable driver" ) ) {
      return new String[] { "missing_driver", "The JDBC driver for " + tipo + " is missing. Copy "
        + DbTools.jarSugerido( tipo ) + " to data-integration/lib and restart Spoon. Detail: " + detalle };
    }
    if ( ( sqlState != null && sqlState.startsWith( "28" ) ) || todo.contains( "password authentication failed" )
      || todo.contains( "access denied" ) || todo.contains( "authentication failed" ) || todo.contains( "login failed" )
      || todo.contains( "invalid username/password" ) || todo.contains( "wrong user name or password" )
      || todo.contains( "invalid authorization" ) ) {
      return new String[] { "bad_credentials", "Wrong user name or password (or the user has no permission on "
        + "that database). Detail: " + detalle };
    }
    for ( Throwable c : cadena ) {
      if ( c instanceof UnknownHostException ) {
        return new String[] { "unknown_host", "Cannot resolve host '" + dm.environmentSubstitute(
          dm.getHostname() ) + "'. Check the name (for your own machine: localhost). Detail: " + detalle };
      }
    }
    boolean red = todo.contains( "connection refused" ) || todo.contains( "communications link failure" )
      || todo.contains( "connect timed out" ) || todo.contains( "connection timed out" )
      || todo.contains( "connection to" ) && todo.contains( "refused" );
    for ( Throwable c : cadena ) {
      red |= c instanceof ConnectException || c instanceof NoRouteToHostException
        || c instanceof SocketTimeoutException;
    }
    if ( red || ( sqlState != null && sqlState.startsWith( "08" ) && !todo.contains( "database" ) ) ) {
      return new String[] { "host_unreachable", "Could not reach " + destino( dm ) + ". Is the server up? Is it "
        + "the right port? Detail: " + detalle };
    }
    if ( "3D000".equals( sqlState ) || todo.contains( "unknown database" )
      || ( todo.contains( "database" ) && todo.contains( "does not exist" ) )
      || todo.contains( "database not found" ) ) {
      return new String[] { "database_not_found", "Database '" + dm.environmentSubstitute( dm.getDatabaseName() )
        + "' does not exist on that server. Detail: " + detalle };
    }
    if ( sqlState != null && sqlState.startsWith( "42" ) || todo.contains( "no such table" )
      || todo.contains( "no such column" ) ) {
      return new String[] { "sql_error", detalle + " (check the names with list_tables and list_columns)" };
    }
    return new String[] { "error", detalle };
  }

  /** Mensaje de la causa mas profunda, en una linea, sin la contrasenia. */
  private static String detalle( List<Throwable> cadena, DatabaseMeta dm ) {
    String msg = null;
    for ( int i = cadena.size() - 1; i >= 0 && msg == null; i-- ) {
      String m = cadena.get( i ).getMessage();
      if ( m != null && !m.trim().isEmpty() ) {
        msg = m;
      }
    }
    if ( msg == null ) {
      msg = cadena.isEmpty() ? "unknown error" : cadena.get( cadena.size() - 1 ).toString();
    }
    msg = msg.replaceAll( "\\s+", " " ).trim();
    try {
      String pass = Encr.decryptPasswordOptionallyEncrypted( dm.environmentSubstitute( dm.getPassword() ) );
      if ( pass != null && pass.length() >= 3 ) {
        msg = msg.replace( pass, "****" );
      }
    } catch ( Throwable ignored ) {
      // sin contrasenia
    }
    msg = msg.replaceAll( "(?i)(password=)[^;&\\s]*", "$1****" );
    return msg.length() > 600 ? msg.substring( 0, 600 ) + "..." : msg;
  }

  private static String driverClass( DatabaseMeta dm ) {
    try {
      return dm.getDriverClass();
    } catch ( Throwable t ) {
      return null;
    }
  }
}
