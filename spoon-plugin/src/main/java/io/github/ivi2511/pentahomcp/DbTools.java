package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.DBCache;
import org.pentaho.di.core.database.DatabaseInterface;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.exception.KettleXMLException;
import org.pentaho.di.core.logging.LogTableInterface;
import org.pentaho.di.core.parameters.DuplicateParamException;
import org.pentaho.di.core.plugins.DatabasePluginType;
import org.pentaho.di.core.plugins.PluginInterface;
import org.pentaho.di.core.plugins.PluginRegistry;
import org.pentaho.di.core.variables.VariableSpace;
import org.pentaho.di.core.variables.Variables;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.ui.spoon.Spoon;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;

/**
 * Conexiones a bases de datos del documento abierto, explorar tablas/columnas, consultas y
 * parametros del documento. Lo que toca el documento corre en UI; lo que conecta a la base corre
 * en DbJdbc (pool propio, timeout) sobre una COPIA del DatabaseMeta.
 */
final class DbTools {

  private DbTools() {
  }

  /** Copia de una conexion + variables del documento, para usar fuera del hilo de UI. */
  static final class Copia {
    final DatabaseMeta db;
    final VariableSpace vars;

    Copia( DatabaseMeta db, VariableSpace vars ) {
      this.db = db;
      this.vars = vars;
    }
  }

  static void register( McpHttpServer s ) {

    s.add( Tools.tool( "list_database_types",
      "Database types PDI understands (the id goes in 'type' of add_connection), with default port, JDBC "
        + "driver class and whether the driver is installed. Use it before add_connection if you do not know the "
        + "exact id, or when a connection fails because of the driver: if driver_available=false, the connector "
        + ".jar has to be copied to data-integration/lib and Spoon restarted.",
      Tools.schema().str( "search", "Filter by id or name (for example 'mysql', 'postgres', 'sqlite')", false )
        .bool( "available_only", "Only the types whose driver is installed", false ).build(),
      a -> tipos( Tools.optStr( a, "search", null ), Tools.optBool( a, "available_only", false ) ) ) );

    s.add( Tools.tool( "list_connections",
      "Database connections defined in the transformation or job: name, type, host, port, database, user and "
        + "which steps/entries use them (never the password). Use it before configuring a Table input/output "
        + "or before deleting or replacing a connection.",
      Tools.schema().document().build(),
      a -> Docs.ui( () -> listar( Docs.resolver( a ) ) ) ) );

    s.add( Tools.tool( "add_connection",
      "Creates a database connection in the document (or replaces it if one with that name already exists: the "
        + "steps that use it switch to the new configuration). Use it when a step needs a connection that does "
        + "not exist. Then check it with test_connection. Examples: MYSQL localhost 3306 (MySQL 8 usually needs "
        + "options useSSL=false, allowPublicKeyRetrieval=true, serverTimezone=UTC); POSTGRESQL localhost 5432; "
        + "SQLITE or H2: no host, 'database' = file path (H2: without the .mv.db extension). For a driver that "
        + "is not listed: type GENERIC with attributes CUSTOM_URL and CUSTOM_DRIVER_CLASS. Alternative: 'xml' "
        + "with a complete <connection>. Does not create shared connections (shared.xml).",
      Tools.schema().document()
        .str( "name", "Connection name (steps reference it by this name)", false )
        .str( "type", "Database type id (MYSQL, POSTGRESQL, SQLITE, H2, MSSQLNATIVE, ORACLE...; see "
          + "list_database_types)", false )
        .str( "host", "Server (localhost if it runs on your machine). Empty for embedded SQLite/H2", false )
        .str( "port", "Port; if omitted, the default port of the type", false )
        .str( "database", "Database name (or the file path for SQLite/H2, or the JNDI name)", false )
        .str( "user", "User name", false )
        .str( "password", "Password (stored encrypted in the .ktr/.kjb; ${VARIABLE} also works)", false )
        .strEnum( "access", "Access type (default Native = JDBC)", false, "Native", "JNDI", "ODBC", "OCI",
          "Plugin" )
        .raw( "options", mapaStrings( "Extra JDBC driver options (they go in the URL), for example "
          + "{\"useSSL\":\"false\",\"serverTimezone\":\"UTC\"}" ), false )
        .raw( "attributes", mapaStrings( "Connection attributes, for example {\"CUSTOM_URL\":\"jdbc:...\","
          + "\"CUSTOM_DRIVER_CLASS\":\"com.x.Driver\"} for GENERIC, or {\"SUPPORTS_BOOLEAN_DATA_TYPE\":\"Y\"}" ),
          false )
        .str( "xml", "Alternative: a complete <connection>...</connection>, as in the .ktr. If 'name' is also "
          + "given, it overrides the name in the XML", false )
        .build(),
      a -> {
        final DatabaseMeta nueva = construir( a );
        return Docs.ui( () -> {
          AbstractMeta m = Docs.resolver( a );
          ObjectNode o = agregar( Docs.spoon(), m, nueva );
          Docs.touched( m );
          return o;
        } );
      } ) );

    s.add( Tools.tool( "test_connection",
      "Tests a connection of the document: connects in the background (with a timeout) and returns ok or the "
        + "problem (missing_driver, bad_credentials, host_unreachable, unknown_host, database_not_found, timeout) "
        + "with a message to explain to the user. Use it after add_connection and when a step fails to "
        + "connect.",
      Tools.schema().document().str( "name", "Connection name", true )
        .integer( "timeout_s", "Maximum seconds to wait (default " + DbJdbc.TIMEOUT_CONECTAR_S + ")", false )
        .build(),
      a -> {
        Copia c = copia( a, Tools.str( a, "name" ) );
        return DbJdbc.probar( c.db, c.vars, timeout( a, DbJdbc.TIMEOUT_CONECTAR_S ) );
      } ) );

    s.add( Tools.tool( "delete_connection",
      "Removes a connection from the document. Fails if any step, entry or log table uses it (change or delete "
        + "those steps first). Can be undone with Ctrl+Z in transformations.",
      Tools.schema().document().str( "name", "Connection name", true ).build(),
      a -> Docs.ui( () -> {
        AbstractMeta m = Docs.resolver( a );
        String r = borrar( Docs.spoon(), m, Tools.str( a, "name" ) );
        Docs.touched( m );
        return r;
      } ) ) );

    s.add( Tools.tool( "list_tables",
      "Lists the tables and views of a document connection, grouped by schema (system schemas are hidden). Use "
        + "it to see what is in the database before building a Table input or a query.",
      Tools.schema().document().str( "connection", "Connection name", true )
        .str( "schema", "Only this schema (in MySQL, the database)", false )
        .str( "search", "Only names that contain this text", false )
        .bool( "include_system", "Include information_schema, pg_catalog, etc.", false ).build(),
      a -> {
        final Copia c = copia( a, Tools.str( a, "connection" ) );
        final String esquema = Tools.optStr( a, "schema", null );
        final String buscar = Tools.optStr( a, "search", null );
        final boolean sistema = Tools.optBool( a, "include_system", false );
        return DbJdbc.conConexion( c.db, c.vars, DbJdbc.TIMEOUT_EXPLORAR_S,
          db -> DbJdbc.tablas( db, esquema, buscar, sistema ) );
      } ) );

    s.add( Tools.tool( "list_columns",
      "Columns of a table (name, Kettle type, length, precision, database type, pk). Use it to configure the "
        + "fields of a Table output, Insert/Update, Database lookup or the SQL of a Table input.",
      Tools.schema().document().str( "connection", "Connection name", true )
        .str( "table", "Table or view name (accepts schema.table)", true )
        .str( "schema", "Schema, if it is not in 'table'", false ).build(),
      a -> {
        final Copia c = copia( a, Tools.str( a, "connection" ) );
        String tabla = Tools.str( a, "table" ).trim();
        String esquema = Tools.optStr( a, "schema", null );
        if ( esquema == null && tabla.indexOf( '.' ) > 0 && tabla.indexOf( '.' ) < tabla.length() - 1 ) {
          esquema = sinComillas( tabla.substring( 0, tabla.lastIndexOf( '.' ) ) );
          tabla = tabla.substring( tabla.lastIndexOf( '.' ) + 1 );
        }
        final String e = esquema;
        final String t = sinComillas( tabla );
        return DbJdbc.conConexion( c.db, c.vars, DbJdbc.TIMEOUT_EXPLORAR_S, db -> DbJdbc.columnas( db, e, t ) );
      } ) );

    s.add( Tools.tool( "run_sql",
      "Runs a SQL query against a document connection and returns fields and rows (as text). Use it to look at "
        + "real data, to try a SELECT before putting it in a Table input, or to count rows. Read-only: SELECT, "
        + "WITH, SHOW, DESCRIBE, EXPLAIN, PRAGMA. Creating tables or modifying data requires allow_write=true, "
        + "and only if the user asked for it.",
      Tools.schema().document().str( "connection", "Connection name", true )
        .str( "sql", "The query. The document's ${VARIABLES} are substituted", true )
        .integer( "rows", "Maximum rows to return (default 50, max " + DbJdbc.MAX_FILAS + ")", false )
        .bool( "allow_write", "Allows INSERT/UPDATE/DELETE/CREATE... (executed and committed)", false )
        .integer( "timeout_s", "Maximum seconds (default " + DbJdbc.TIMEOUT_CONSULTA_S + ")", false ).build(),
      a -> {
        final String sql = Tools.str( a, "sql" );
        final int filas = Math.max( 1, Math.min( DbJdbc.MAX_FILAS, Tools.optInt( a, "rows", 50 ) ) );
        final boolean escribir = Tools.optBool( a, "allow_write", false );
        if ( !escribir && DbJdbc.motivoNoLectura( sql ) != null ) { // antes de tocar el documento
          throw new ToolError( "run_sql is read-only: " + DbJdbc.motivoNoLectura( sql ) + ". If the user asked "
            + "to modify the database, repeat with allow_write=true." );
        }
        final Copia c = copia( a, Tools.str( a, "connection" ) );
        final String sustituido = c.vars.environmentSubstitute( sql );
        final String motivo = DbJdbc.motivoNoLectura( sustituido ); // de nuevo: una ${VARIABLE} puede traer SQL
        if ( motivo != null && !escribir ) {
          throw new ToolError( "run_sql is read-only and, once the variables are substituted, the query is not: "
            + motivo + "." );
        }
        final String limpio = DbJdbc.sinPuntoYComaFinal( sustituido );
        return DbJdbc.conConexion( c.db, c.vars, timeout( a, DbJdbc.TIMEOUT_CONSULTA_S ),
          db -> motivo == null ? DbJdbc.consultar( db, limpio, filas ) : DbJdbc.ejecutar( db, limpio ) );
      } ) );

    s.add( Tools.tool( "list_parameters",
      "Named parameters of the transformation or job (name, default value, description). Use it before using "
        + "${PARAM} in a step or running with parameters.",
      Tools.schema().document().build(),
      a -> Docs.ui( () -> parametros( Docs.resolver( a ) ) ) ) );

    s.add( Tools.tool( "set_parameter",
      "Creates a document parameter, or changes its default value or description if it already exists (what "
        + "you do not pass stays the same). Use it when a step is going to use ${NAME} and it is not defined.",
      Tools.schema().document().str( "name", "Parameter name, without ${}", true )
        .str( "default", "Default value", false )
        .str( "description", "Description", false ).build(),
      a -> Docs.ui( () -> {
        AbstractMeta m = Docs.resolver( a );
        JsonNode pd = a.get( "default" );
        JsonNode de = a.get( "description" );
        ObjectNode o = definirParametro( m, nombreParametro( a ),
          pd == null || pd.isNull() ? null : pd.asText(), de == null || de.isNull() ? null : de.asText() );
        Docs.touched( m );
        return o;
      } ) ) );

    s.add( Tools.tool( "delete_parameter",
      "Removes a parameter from the document (any ${NAME} that uses it is left without a value).",
      Tools.schema().document().str( "name", "Parameter name", true ).build(),
      a -> Docs.ui( () -> {
        AbstractMeta m = Docs.resolver( a );
        String n = nombreParametro( a );
        borrarParametro( m, n );
        Docs.touched( m );
        return "Parameter '" + n + "' deleted";
      } ) ) );
  }

  // ---------------------------------------------------------------- tipos de base

  /** Tipos de base registrados, con driver y si esta disponible. Puro (no toca la UI). */
  static ArrayNode tipos( String buscar, boolean soloDisponibles ) {
    String f = buscar == null ? null : buscar.toLowerCase( Locale.ROOT );
    PluginRegistry reg = PluginRegistry.getInstance();
    TreeMap<String, DatabaseInterface> mapa = new TreeMap<String, DatabaseInterface>( DatabaseMeta
      .getDatabaseInterfacesMap() );
    ArrayNode out = Tools.arr();
    for ( Map.Entry<String, DatabaseInterface> e : mapa.entrySet() ) {
      String id = e.getKey();
      DatabaseInterface di = e.getValue();
      PluginInterface p = reg.getPlugin( DatabasePluginType.class, id );
      String nombre = p != null ? p.getName() : di.getPluginName();
      if ( f != null && !id.toLowerCase( Locale.ROOT ).contains( f )
        && ( nombre == null || !nombre.toLowerCase( Locale.ROOT ).contains( f ) ) ) {
        continue;
      }
      String driver = null;
      try {
        driver = di.getDriverClass();
      } catch ( Throwable ignored ) {
        // algunos tipos calculan el driver con el acceso
      }
      Boolean disp = driverDisponible( p, driver );
      if ( soloDisponibles && !Boolean.TRUE.equals( disp ) ) {
        continue;
      }
      ObjectNode o = out.addObject();
      o.put( "id", id );
      o.put( "name", nombre );
      if ( di.getDefaultDatabasePort() > 0 ) {
        o.put( "port", di.getDefaultDatabasePort() );
      }
      if ( driver != null && !driver.isEmpty() ) {
        o.put( "driver", driver );
      }
      if ( disp != null ) {
        o.put( "driver_available", disp );
        if ( !disp ) {
          o.put( "jar", jarSugerido( id ) );
        }
      }
      try {
        ArrayNode acc = o.putArray( "access_types" );
        for ( int t : di.getAccessTypeList() ) {
          acc.add( DatabaseMeta.getAccessTypeDesc( t ) );
        }
      } catch ( Throwable ignored ) {
        o.remove( "access_types" );
      }
    }
    return out;
  }

  /** Que jar buscar cuando falta el driver (los mas comunes para un estudiante). */
  static String jarSugerido( String id ) {
    String u = id == null ? "" : id.toUpperCase( Locale.ROOT );
    if ( u.equals( "MYSQL" ) ) {
      return "the MySQL connector (mysql-connector-j-8.x.jar, from dev.mysql.com or Maven Central)";
    }
    if ( u.equals( "MARIADB" ) ) {
      return "mariadb-java-client-x.jar (Maven Central)";
    }
    if ( u.equals( "MSSQLNATIVE" ) ) {
      return "mssql-jdbc-x.jre8.jar (Microsoft JDBC Driver for SQL Server)";
    }
    if ( u.equals( "MSSQL" ) ) {
      return "jtds-1.3.1.jar (or use the MSSQLNATIVE type with mssql-jdbc)";
    }
    if ( u.startsWith( "ORACLE" ) ) {
      return "ojdbc8.jar (Oracle)";
    }
    return "the JDBC driver .jar for that database";
  }

  /** Carga (sin inicializar) la clase del driver con el classloader del plugin de base. null = no aplica. */
  static Boolean driverDisponible( PluginInterface p, String driver ) {
    if ( driver == null || driver.trim().isEmpty() ) {
      return null; // GENERIC y similares: el driver lo define la conexion
    }
    try {
      ClassLoader cl = p != null ? PluginRegistry.getInstance().getClassLoader( p ) : DbTools.class.getClassLoader();
      Class.forName( driver.trim(), false, cl );
      return true;
    } catch ( Throwable t ) {
      return false;
    }
  }

  static Boolean driverDisponible( DatabaseMeta dm ) {
    try {
      PluginInterface p = PluginRegistry.getInstance().getPlugin( DatabasePluginType.class, dm.getDatabaseInterface() );
      String driver = dm.getDriverClass();
      if ( "GENERIC".equals( dm.getPluginId() ) ) {
        driver = dm.getAttributes().getProperty( "CUSTOM_DRIVER_CLASS" );
      }
      return driverDisponible( p, driver == null ? null : dm.environmentSubstitute( driver ) );
    } catch ( Throwable t ) {
      return null;
    }
  }

  // ---------------------------------------------------------------- conexiones del documento

  /** Ficha de una conexion, sin contrasenia. */
  static ObjectNode ficha( DatabaseMeta d ) {
    ObjectNode o = Tools.obj();
    o.put( "name", d.getName() );
    o.put( "type", d.getPluginId() );
    o.put( "access", d.getAccessTypeDesc() );
    putSiHay( o, "host", d.getHostname() );
    String p = d.getDatabasePortNumberString();
    if ( p != null && !p.isEmpty() && !"-1".equals( p ) ) {
      o.put( "port", p );
    }
    putSiHay( o, "database", d.getDatabaseName() );
    putSiHay( o, "user", d.getUsername() );
    if ( d.getExtraOptions() != null && !d.getExtraOptions().isEmpty() ) {
      ObjectNode op = o.putObject( "options" );
      String pref = d.getPluginId() + ".";
      for ( Map.Entry<String, String> e : new TreeMap<String, String>( d.getExtraOptions() ).entrySet() ) {
        String k = e.getKey().startsWith( pref ) ? e.getKey().substring( pref.length() ) : e.getKey();
        if ( !k.toLowerCase( Locale.ROOT ).contains( "password" ) ) {
          op.put( k, e.getValue() );
        }
      }
    }
    if ( d.isShared() ) {
      o.put( "shared", true );
    }
    return o;
  }

  /** Conexiones de un documento con quien las usa. (UI) */
  static ArrayNode listar( AbstractMeta m ) {
    ArrayNode out = Tools.arr();
    for ( DatabaseMeta d : m.getDatabases() ) {
      ObjectNode o = ficha( d );
      List<String> usos = usos( m, d );
      if ( !usos.isEmpty() ) {
        ArrayNode u = o.putArray( "used_by" );
        for ( String x : usos ) {
          u.add( x );
        }
      }
      out.add( o );
    }
    return out;
  }

  /** Quien usa la conexion: steps, entradas de job, tablas de log y la conexion de "max date". */
  static List<String> usos( AbstractMeta m, DatabaseMeta d ) {
    List<String> out = new ArrayList<String>();
    if ( m instanceof TransMeta ) {
      TransMeta tm = (TransMeta) m;
      for ( StepMeta st : tm.getSteps() ) {
        try {
          if ( contiene( st.getStepMetaInterface().getUsedDatabaseConnections(), d ) ) {
            out.add( st.getName() );
          }
        } catch ( Throwable ignored ) {
          // step sin plugin
        }
      }
      if ( mismo( tm.getMaxDateConnection(), d ) ) {
        out.add( "(transformation max date)" );
      }
      logs( tm.getLogTables(), d, out );
    } else if ( m instanceof JobMeta ) {
      JobMeta jm = (JobMeta) m;
      for ( JobEntryCopy c : jm.getJobCopies() ) {
        try {
          if ( c.getEntry() != null && contiene( c.getEntry().getUsedDatabaseConnections(), d )
            && !out.contains( c.getName() ) ) {
            out.add( c.getName() );
          }
        } catch ( Throwable ignored ) {
          // entrada sin plugin
        }
      }
      logs( jm.getLogTables(), d, out );
    }
    return out;
  }

  private static void logs( List<LogTableInterface> tablas, DatabaseMeta d, List<String> out ) {
    if ( tablas == null ) {
      return;
    }
    for ( LogTableInterface lt : tablas ) {
      try {
        if ( mismo( lt.getDatabaseMeta(), d ) ) {
          out.add( "(log table " + lt.getLogTableType() + ")" );
        }
      } catch ( Throwable ignored ) {
        // tabla de log sin conexion
      }
    }
  }

  private static boolean contiene( DatabaseMeta[] usadas, DatabaseMeta d ) {
    if ( usadas != null ) {
      for ( DatabaseMeta u : usadas ) {
        if ( mismo( u, d ) ) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean mismo( DatabaseMeta a, DatabaseMeta b ) {
    return a != null && b != null && ( a == b || ( a.getName() != null && a.getName().equalsIgnoreCase( b.getName() ) ) );
  }

  /** Arma el DatabaseMeta a partir de los argumentos (no toca el documento). */
  static DatabaseMeta construir( JsonNode a ) throws ToolError {
    String xml = Tools.optStr( a, "xml", null );
    String nombre = Tools.optStr( a, "name", null );
    DatabaseMeta d;
    if ( xml != null ) {
      try {
        d = new DatabaseMeta( xml.trim() );
      } catch ( KettleXMLException e ) {
        throw new ToolError( "The <connection> could not be read: " + e.getMessage().trim() );
      }
      if ( nombre != null ) {
        d.setName( nombre );
      }
      if ( d.getName() == null || d.getName().trim().isEmpty() ) {
        throw new ToolError( "The connection has no name: pass 'name' or put <name> in the XML" );
      }
    } else {
      if ( nombre == null ) {
        throw new ToolError( "Missing argument 'name'" );
      }
      DatabaseInterface di = tipo( Tools.str( a, "type" ) );
      String acceso = Tools.optStr( a, "access", "Native" );
      int at = DatabaseMeta.getAccessType( acceso );
      boolean ok = false;
      List<String> validos = new ArrayList<String>();
      for ( int t : di.getAccessTypeList() ) {
        ok |= t == at;
        validos.add( DatabaseMeta.getAccessTypeDesc( t ) );
      }
      if ( !ok ) {
        throw new ToolError( "Type " + di.getPluginId() + " does not support access '" + acceso + "'. Valid: "
          + validos );
      }
      String host = Tools.optStr( a, "host", "" );
      String puerto = Tools.optStr( a, "port", null );
      if ( puerto == null ) {
        puerto = !host.isEmpty() && di.getDefaultDatabasePort() > 0 ? String.valueOf( di.getDefaultDatabasePort() ) : "";
      }
      d = new DatabaseMeta( nombre, di.getPluginId(), DatabaseMeta.getAccessTypeDesc( at ), host,
        Tools.str( a, "database" ), puerto, Tools.optStr( a, "user", "" ), optCrudo( a, "password" ) );
    }
    JsonNode op = a.get( "options" );
    if ( op != null && op.isObject() ) {
      for ( Iterator<Map.Entry<String, JsonNode>> it = op.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        d.addExtraOption( d.getPluginId(), e.getKey(), e.getValue().isTextual() ? e.getValue().asText()
          : e.getValue().toString() );
      }
    }
    JsonNode at = a.get( "attributes" );
    if ( at != null && at.isObject() ) {
      for ( Iterator<Map.Entry<String, JsonNode>> it = at.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        d.getAttributes().put( e.getKey(), e.getValue().isTextual() ? e.getValue().asText() : e.getValue().toString() );
      }
    }
    return d;
  }

  /** Resuelve el tipo por id o por nombre, sin importar mayusculas. */
  static DatabaseInterface tipo( String t ) throws ToolError {
    Map<String, DatabaseInterface> mapa = DatabaseMeta.getDatabaseInterfacesMap();
    for ( Map.Entry<String, DatabaseInterface> e : mapa.entrySet() ) {
      if ( e.getKey().equalsIgnoreCase( t.trim() ) ) {
        return e.getValue();
      }
    }
    PluginRegistry reg = PluginRegistry.getInstance();
    List<String> parecidos = new ArrayList<String>();
    String tl = t.trim().toLowerCase( Locale.ROOT );
    for ( Map.Entry<String, DatabaseInterface> e : mapa.entrySet() ) {
      PluginInterface p = reg.getPlugin( DatabasePluginType.class, e.getKey() );
      String nombre = p != null ? p.getName() : null;
      if ( nombre != null && nombre.equalsIgnoreCase( t.trim() ) ) {
        return e.getValue();
      }
      if ( e.getKey().toLowerCase( Locale.ROOT ).contains( tl )
        || ( nombre != null && nombre.toLowerCase( Locale.ROOT ).contains( tl ) ) ) {
        parecidos.add( e.getKey() );
      }
    }
    throw new ToolError( "There is no database type '" + t + "'." + ( parecidos.isEmpty() ? "" : " Maybe: " + parecidos
      + "." ) + " See list_database_types." );
  }

  /**
   * Agrega o reemplaza la conexion en el documento, con undo en transformaciones. Si spoon es null (pruebas
   * sin GUI) no registra undo. (UI)
   */
  static ObjectNode agregar( Spoon spoon, AbstractMeta m, DatabaseMeta nueva ) {
    DatabaseMeta vieja = m.findDatabase( nueva.getName() );
    if ( vieja != null ) {
      nueva.setName( vieja.getName() ); // addOrReplaceDatabase compara el nombre respetando mayusculas
    }
    DatabaseMeta antes = vieja == null ? null : (DatabaseMeta) vieja.clone();
    boolean compartida = vieja != null && vieja.isShared();
    m.addOrReplaceDatabase( nueva ); // si existe hace replaceMeta: los steps siguen apuntando al mismo objeto
    DatabaseMeta puesta = m.findDatabase( nueva.getName() );
    int idx = m.indexOfDatabase( puesta );
    boolean deshacer = false;
    if ( spoon != null && m instanceof TransMeta ) { // el undo de jobs no maneja conexiones
      if ( antes == null ) {
        spoon.addUndoNew( m, new Object[] { puesta }, new int[] { idx } );
      } else {
        spoon.addUndoChange( m, new Object[] { antes }, new Object[] { puesta.clone() }, new int[] { idx } );
      }
      deshacer = true;
    }
    DBCache.getInstance().clear( puesta.getName() );
    ObjectNode o = ficha( puesta );
    o.put( "replaced", antes != null );
    if ( antes != null ) {
      List<String> usos = usos( m, puesta );
      if ( !usos.isEmpty() ) {
        ArrayNode u = o.putArray( "used_by" );
        for ( String x : usos ) {
          u.add( x );
        }
      }
    }
    if ( compartida ) {
      o.put( "warning", "It was a shared connection (shared.xml): if the user saves from Spoon, the change may "
        + "also be written to shared.xml" );
    }
    Boolean disp = driverDisponible( puesta );
    if ( Boolean.FALSE.equals( disp ) ) {
      o.put( "driver_available", false );
      o.put( "missing_driver", "The JDBC driver for " + puesta.getPluginId() + " is not installed: copy "
        + jarSugerido( puesta.getPluginId() ) + " to data-integration/lib and restart Spoon" );
    }
    String archivo = DbJdbc.avisoArchivo( puesta );
    if ( archivo != null ) {
      o.put( "file_warning", archivo );
    }
    o.put( "undoable", deshacer );
    return o;
  }

  /** Quita una conexion si nadie la usa. (UI) */
  static String borrar( Spoon spoon, AbstractMeta m, String nombre ) throws ToolError {
    DatabaseMeta d = buscarConexion( m, nombre );
    List<String> usos = usos( m, d );
    if ( !usos.isEmpty() ) {
      throw new ToolError( "Connection '" + d.getName() + "' is used by: " + usos + ". Change or delete those steps "
        + "first." );
    }
    int idx = m.indexOfDatabase( d );
    m.removeDatabase( idx );
    if ( spoon != null && m instanceof TransMeta ) {
      spoon.addUndoDelete( m, new Object[] { d }, new int[] { idx } );
    }
    DBCache.getInstance().clear( d.getName() );
    return "Connection '" + d.getName() + "' deleted" + ( d.isShared() ? " from this document (it is shared: it "
      + "stays in shared.xml and comes back when the document is reopened)" : "" );
  }

  static DatabaseMeta buscarConexion( AbstractMeta m, String nombre ) throws ToolError {
    DatabaseMeta d = m.findDatabase( nombre.trim() );
    if ( d == null ) {
      String[] hay = m.getDatabaseNames();
      throw new ToolError( "There is no connection '" + nombre + "' in '" + Docs.nombre( m ) + "'. "
        + ( hay.length == 0 ? "The document has no connections: create one with add_connection."
          : "Available: " + java.util.Arrays.toString( hay ) ) );
    }
    return d;
  }

  /** Copia la conexion y las variables del documento en el hilo de UI, para usarlas afuera. */
  private static Copia copia( final JsonNode a, final String nombre ) throws Exception {
    return Docs.ui( () -> copiaDe( Docs.resolver( a ), nombre ) );
  }

  /** (UI, o sin GUI en pruebas) */
  static Copia copiaDe( AbstractMeta m, String nombre ) throws ToolError {
    DatabaseMeta d = buscarConexion( m, nombre );
    return new Copia( (DatabaseMeta) d.clone(), variables( m ) );
  }

  /** Variables del documento + valores/por defecto de sus parametros. */
  static VariableSpace variables( AbstractMeta m ) {
    Variables v = new Variables();
    v.copyVariablesFrom( m );
    for ( String p : m.listParameters() ) {
      try {
        String actual = v.getVariable( p );
        if ( actual == null || actual.isEmpty() ) {
          String val = m.getParameterValue( p );
          if ( val == null || val.isEmpty() ) {
            val = m.getParameterDefault( p );
          }
          if ( val != null ) {
            v.setVariable( p, val );
          }
        }
      } catch ( Exception ignored ) {
        // parametro desaparecido
      }
    }
    return v;
  }

  // ---------------------------------------------------------------- parametros

  static ArrayNode parametros( AbstractMeta m ) throws Exception {
    ArrayNode out = Tools.arr();
    for ( String p : m.listParameters() ) {
      ObjectNode o = out.addObject();
      o.put( "name", p );
      o.put( "default", m.getParameterDefault( p ) );
      putSiHay( o, "description", m.getParameterDescription( p ) );
      putSiHay( o, "value", m.getParameterValue( p ) );
    }
    return out;
  }

  /**
   * Crea o actualiza un parametro. NamedParams no tiene setParameterDefault ni borrado individual en 9.4,
   * asi que para modificar se reconstruye la lista entera (conservando valores). (UI)
   */
  static ObjectNode definirParametro( AbstractMeta m, String nombre, String porDefecto, String descripcion )
    throws Exception {
    Map<String, String[]> todos = leerParametros( m );
    String clave = claveExistente( todos, nombre );
    boolean existia = clave != null;
    if ( !existia ) {
      m.addParameterDefinition( nombre, porDefecto == null ? "" : porDefecto, descripcion == null ? "" : descripcion );
    } else {
      String[] v = todos.get( clave );
      if ( porDefecto != null ) {
        v[0] = porDefecto;
      }
      if ( descripcion != null ) {
        v[1] = descripcion;
      }
      reescribirParametros( m, todos );
    }
    m.activateParameters();
    ObjectNode o = Tools.obj();
    o.put( "name", existia ? clave : nombre );
    o.put( "default", m.getParameterDefault( existia ? clave : nombre ) );
    putSiHay( o, "description", m.getParameterDescription( existia ? clave : nombre ) );
    o.put( "created", !existia );
    return o;
  }

  static void borrarParametro( AbstractMeta m, String nombre ) throws Exception {
    Map<String, String[]> todos = leerParametros( m );
    String clave = claveExistente( todos, nombre );
    if ( clave == null ) {
      throw new ToolError( "There is no parameter '" + nombre + "'. Available: " + todos.keySet() );
    }
    todos.remove( clave );
    reescribirParametros( m, todos );
    m.activateParameters();
  }

  /** nombre -> {por defecto, descripcion, valor} */
  private static Map<String, String[]> leerParametros( AbstractMeta m ) throws Exception {
    Map<String, String[]> out = new LinkedHashMap<String, String[]>();
    for ( String p : m.listParameters() ) {
      out.put( p, new String[] { m.getParameterDefault( p ), m.getParameterDescription( p ),
        m.getParameterValue( p ) } );
    }
    return out;
  }

  private static void reescribirParametros( AbstractMeta m, Map<String, String[]> todos ) throws Exception {
    m.eraseParameters();
    for ( Map.Entry<String, String[]> e : todos.entrySet() ) {
      String[] v = e.getValue();
      try {
        m.addParameterDefinition( e.getKey(), v[0] == null ? "" : v[0], v[1] == null ? "" : v[1] );
      } catch ( DuplicateParamException ignored ) {
        // no deberia pasar: la lista viene sin duplicados
      }
      if ( v[2] != null && !v[2].isEmpty() ) {
        m.setParameterValue( e.getKey(), v[2] );
      }
    }
  }

  private static String claveExistente( Map<String, String[]> todos, String nombre ) {
    for ( String k : todos.keySet() ) {
      if ( k.equals( nombre ) ) {
        return k;
      }
    }
    return null;
  }

  private static String nombreParametro( JsonNode a ) throws ToolError {
    String n = Tools.str( a, "name" ).trim();
    if ( n.startsWith( "${" ) && n.endsWith( "}" ) ) {
      n = n.substring( 2, n.length() - 1 ).trim();
    } else if ( n.startsWith( "%%" ) && n.endsWith( "%%" ) && n.length() > 4 ) {
      n = n.substring( 2, n.length() - 2 ).trim();
    }
    if ( n.isEmpty() ) {
      throw new ToolError( "The parameter name is empty" );
    }
    return n;
  }

  // ---------------------------------------------------------------- utilidades

  private static ObjectNode mapaStrings( String desc ) {
    ObjectNode o = Tools.obj();
    o.put( "type", "object" );
    o.put( "description", desc );
    o.putObject( "additionalProperties" ).put( "type", "string" );
    return o;
  }

  private static void putSiHay( ObjectNode o, String k, String v ) {
    if ( v != null && !v.isEmpty() ) {
      o.put( k, v );
    }
  }

  /** El valor tal cual (sin trim ni "vacio = falta"), para contrasenias. */
  private static String optCrudo( JsonNode a, String k ) {
    JsonNode v = a.get( k );
    return v == null || v.isNull() ? "" : v.asText();
  }

  private static int timeout( JsonNode a, int def ) throws ToolError {
    return Math.max( 1, Math.min( 600, Tools.optInt( a, "timeout_s", def ) ) );
  }

  private static String sinComillas( String s ) {
    String t = s.trim();
    if ( t.length() >= 2 && ( ( t.startsWith( "\"" ) && t.endsWith( "\"" ) ) || ( t.startsWith( "`" ) && t.endsWith( "`" ) )
      || ( t.startsWith( "[" ) && t.endsWith( "]" ) ) ) ) {
      return t.substring( 1, t.length() - 1 );
    }
    return t;
  }
}
