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

    s.add( Tools.tool( "tipos_de_base",
      "Tipos de base de datos que entiende PDI (el id va en 'tipo' de agregar_conexion), con puerto por "
        + "defecto, clase del driver JDBC y si el driver esta instalado. Usala antes de agregar_conexion si no "
        + "sabes el id exacto, o cuando una conexion falla por driver: si driver_disponible=false hay que copiar "
        + "el .jar del conector a data-integration/lib y reiniciar Spoon.",
      Tools.schema().str( "buscar", "Filtra por id o nombre (por ejemplo 'mysql', 'postgres', 'sqlite')", false )
        .bool( "solo_disponibles", "Solo los que tienen el driver instalado", false ).build(),
      a -> tipos( Tools.optStr( a, "buscar", null ), Tools.optBool( a, "solo_disponibles", false ) ) ) );

    s.add( Tools.tool( "listar_conexiones",
      "Conexiones a bases definidas en la transformacion o job: nombre, tipo, host, puerto, base, usuario y "
        + "que steps/entradas las usan (nunca la contrasenia). Usala antes de configurar un Table input/output "
        + "o antes de borrar o reemplazar una conexion.",
      Tools.schema().documento().build(),
      a -> Docs.ui( () -> listar( Docs.resolver( a ) ) ) ) );

    s.add( Tools.tool( "agregar_conexion",
      "Crea una conexion a base de datos en el documento (o la reemplaza si ya hay una con ese nombre: los "
        + "steps que la usan pasan a la nueva configuracion). Usala cuando un step necesita una conexion que no "
        + "existe. Despues proba con probar_conexion. Ejemplos: MYSQL localhost 3306 (MySQL 8 suele necesitar "
        + "opciones useSSL=false, allowPublicKeyRetrieval=true, serverTimezone=UTC); POSTGRESQL localhost 5432; "
        + "SQLITE o H2: sin host, 'base' = ruta del archivo (H2: sin la extension .mv.db). Para un driver no "
        + "listado: tipo GENERIC con atributos CUSTOM_URL y CUSTOM_DRIVER_CLASS. Alternativa: 'xml' con un "
        + "<connection> completo. No crea conexiones compartidas (shared.xml).",
      Tools.schema().documento()
        .str( "nombre", "Nombre de la conexion (asi la referencian los steps)", false )
        .str( "tipo", "Id del tipo de base (MYSQL, POSTGRESQL, SQLITE, H2, MSSQLNATIVE, ORACLE...; ver "
          + "tipos_de_base)", false )
        .str( "host", "Servidor (localhost si esta en tu maquina). Vacio para SQLite/H2 embebido", false )
        .str( "puerto", "Puerto; si se omite, el por defecto del tipo", false )
        .str( "base", "Nombre de la base (o ruta del archivo para SQLite/H2, o nombre JNDI)", false )
        .str( "usuario", "Usuario", false )
        .str( "password", "Contrasenia (se guarda cifrada en el .ktr/.kjb; tambien vale ${VARIABLE})", false )
        .strEnum( "acceso", "Tipo de acceso (por defecto Native = JDBC)", false, "Native", "JNDI", "ODBC", "OCI",
          "Plugin" )
        .raw( "opciones", mapaStrings( "Opciones extra del driver JDBC (van en la URL), por ejemplo "
          + "{\"useSSL\":\"false\",\"serverTimezone\":\"UTC\"}" ), false )
        .raw( "atributos", mapaStrings( "Atributos de la conexion, por ejemplo {\"CUSTOM_URL\":\"jdbc:...\","
          + "\"CUSTOM_DRIVER_CLASS\":\"com.x.Driver\"} para GENERIC, o {\"SUPPORTS_BOOLEAN_DATA_TYPE\":\"Y\"}" ),
          false )
        .str( "xml", "Alternativa: un <connection>...</connection> completo, como en el .ktr. Si viene 'nombre', "
          + "lo pisa", false )
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

    s.add( Tools.tool( "probar_conexion",
      "Prueba una conexion del documento: conecta en segundo plano (con timeout) y devuelve ok o el problema "
        + "(driver_faltante, credenciales, host_inalcanzable, host_desconocido, base_inexistente, timeout) con "
        + "un mensaje para explicarle al usuario. Usala despues de agregar_conexion y cuando un step falla al "
        + "conectar.",
      Tools.schema().documento().str( "nombre", "Nombre de la conexion", true )
        .integer( "timeout_seg", "Segundos maximos de espera (por defecto " + DbJdbc.TIMEOUT_CONECTAR_S + ")", false )
        .build(),
      a -> {
        Copia c = copia( a, Tools.str( a, "nombre" ) );
        return DbJdbc.probar( c.db, c.vars, timeout( a, DbJdbc.TIMEOUT_CONECTAR_S ) );
      } ) );

    s.add( Tools.tool( "borrar_conexion",
      "Quita una conexion del documento. Falla si algun step, entrada o tabla de log la usa (primero cambia o "
        + "borra esos steps). Se puede deshacer con Ctrl+Z en transformaciones.",
      Tools.schema().documento().str( "nombre", "Nombre de la conexion", true ).build(),
      a -> Docs.ui( () -> {
        AbstractMeta m = Docs.resolver( a );
        String r = borrar( Docs.spoon(), m, Tools.str( a, "nombre" ) );
        Docs.touched( m );
        return r;
      } ) ) );

    s.add( Tools.tool( "tablas",
      "Lista las tablas y vistas de una conexion del documento, agrupadas por esquema (oculta los esquemas de "
        + "sistema). Usala para saber que hay en la base antes de armar un Table input o una consulta.",
      Tools.schema().documento().str( "conexion", "Nombre de la conexion", true )
        .str( "esquema", "Solo este esquema (en MySQL, la base)", false )
        .str( "buscar", "Solo nombres que contengan este texto", false )
        .bool( "incluir_sistema", "Incluir information_schema, pg_catalog, etc.", false ).build(),
      a -> {
        final Copia c = copia( a, Tools.str( a, "conexion" ) );
        final String esquema = Tools.optStr( a, "esquema", null );
        final String buscar = Tools.optStr( a, "buscar", null );
        final boolean sistema = Tools.optBool( a, "incluir_sistema", false );
        return DbJdbc.conConexion( c.db, c.vars, DbJdbc.TIMEOUT_EXPLORAR_S,
          db -> DbJdbc.tablas( db, esquema, buscar, sistema ) );
      } ) );

    s.add( Tools.tool( "columnas",
      "Columnas de una tabla (nombre, tipo Kettle, largo, precision, tipo de la base, pk). Usala para "
        + "configurar campos de un Table output, Insert/Update, Database lookup o el SQL de un Table input.",
      Tools.schema().documento().str( "conexion", "Nombre de la conexion", true )
        .str( "tabla", "Nombre de la tabla o vista (acepta esquema.tabla)", true )
        .str( "esquema", "Esquema, si no va en 'tabla'", false ).build(),
      a -> {
        final Copia c = copia( a, Tools.str( a, "conexion" ) );
        String tabla = Tools.str( a, "tabla" ).trim();
        String esquema = Tools.optStr( a, "esquema", null );
        if ( esquema == null && tabla.indexOf( '.' ) > 0 && tabla.indexOf( '.' ) < tabla.length() - 1 ) {
          esquema = sinComillas( tabla.substring( 0, tabla.lastIndexOf( '.' ) ) );
          tabla = tabla.substring( tabla.lastIndexOf( '.' ) + 1 );
        }
        final String e = esquema;
        final String t = sinComillas( tabla );
        return DbJdbc.conConexion( c.db, c.vars, DbJdbc.TIMEOUT_EXPLORAR_S, db -> DbJdbc.columnas( db, e, t ) );
      } ) );

    s.add( Tools.tool( "consultar_sql",
      "Corre una consulta SQL contra una conexion del documento y devuelve campos y filas (como texto). Para "
        + "mirar datos reales, probar un SELECT antes de ponerlo en un Table input o contar filas. Solo lectura: "
        + "SELECT, WITH, SHOW, DESCRIBE, EXPLAIN, PRAGMA. Para crear tablas o modificar datos hace falta "
        + "permitir_escritura=true, y solo si el usuario lo pidio.",
      Tools.schema().documento().str( "conexion", "Nombre de la conexion", true )
        .str( "sql", "La consulta. Las ${VARIABLES} del documento se reemplazan", true )
        .integer( "filas", "Maximo de filas a devolver (por defecto 50, tope " + DbJdbc.MAX_FILAS + ")", false )
        .bool( "permitir_escritura", "Permite INSERT/UPDATE/DELETE/CREATE... (se ejecuta y se confirma)", false )
        .integer( "timeout_seg", "Segundos maximos (por defecto " + DbJdbc.TIMEOUT_CONSULTA_S + ")", false ).build(),
      a -> {
        final String sql = Tools.str( a, "sql" );
        final int filas = Math.max( 1, Math.min( DbJdbc.MAX_FILAS, Tools.optInt( a, "filas", 50 ) ) );
        final boolean escribir = Tools.optBool( a, "permitir_escritura", false );
        if ( !escribir && DbJdbc.motivoNoLectura( sql ) != null ) { // antes de tocar el documento
          throw new ToolError( "consultar_sql es de solo lectura: " + DbJdbc.motivoNoLectura( sql ) + ". Si el "
            + "usuario pidio modificar la base, repeti con permitir_escritura=true." );
        }
        final Copia c = copia( a, Tools.str( a, "conexion" ) );
        final String sustituido = c.vars.environmentSubstitute( sql );
        final String motivo = DbJdbc.motivoNoLectura( sustituido ); // de nuevo: una ${VARIABLE} puede traer SQL
        if ( motivo != null && !escribir ) {
          throw new ToolError( "consultar_sql es de solo lectura y, con las variables reemplazadas, la consulta no lo "
            + "es: " + motivo + "." );
        }
        final String limpio = DbJdbc.sinPuntoYComaFinal( sustituido );
        return DbJdbc.conConexion( c.db, c.vars, timeout( a, DbJdbc.TIMEOUT_CONSULTA_S ),
          db -> motivo == null ? DbJdbc.consultar( db, limpio, filas ) : DbJdbc.ejecutar( db, limpio ) );
      } ) );

    s.add( Tools.tool( "parametros",
      "Parametros con nombre de la transformacion o job (nombre, valor por defecto, descripcion). Usala antes "
        + "de usar ${PARAM} en un step o de ejecutar con parametros.",
      Tools.schema().documento().build(),
      a -> Docs.ui( () -> parametros( Docs.resolver( a ) ) ) ) );

    s.add( Tools.tool( "definir_parametro",
      "Crea un parametro del documento, o cambia el valor por defecto o la descripcion si ya existe (lo que no "
        + "pases queda igual). Usala cuando un step va a usar ${NOMBRE} y no esta definido.",
      Tools.schema().documento().str( "nombre", "Nombre del parametro, sin ${}", true )
        .str( "por_defecto", "Valor por defecto", false )
        .str( "descripcion", "Descripcion", false ).build(),
      a -> Docs.ui( () -> {
        AbstractMeta m = Docs.resolver( a );
        JsonNode pd = a.get( "por_defecto" );
        JsonNode de = a.get( "descripcion" );
        ObjectNode o = definirParametro( m, nombreParametro( a ),
          pd == null || pd.isNull() ? null : pd.asText(), de == null || de.isNull() ? null : de.asText() );
        Docs.touched( m );
        return o;
      } ) ) );

    s.add( Tools.tool( "borrar_parametro",
      "Quita un parametro del documento (los ${NOMBRE} que lo usen quedan sin valor).",
      Tools.schema().documento().str( "nombre", "Nombre del parametro", true ).build(),
      a -> Docs.ui( () -> {
        AbstractMeta m = Docs.resolver( a );
        String n = nombreParametro( a );
        borrarParametro( m, n );
        Docs.touched( m );
        return "Parametro '" + n + "' borrado";
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
      o.put( "nombre", nombre );
      if ( di.getDefaultDatabasePort() > 0 ) {
        o.put( "puerto", di.getDefaultDatabasePort() );
      }
      if ( driver != null && !driver.isEmpty() ) {
        o.put( "driver", driver );
      }
      if ( disp != null ) {
        o.put( "driver_disponible", disp );
        if ( !disp ) {
          o.put( "jar", jarSugerido( id ) );
        }
      }
      try {
        ArrayNode acc = o.putArray( "accesos" );
        for ( int t : di.getAccessTypeList() ) {
          acc.add( DatabaseMeta.getAccessTypeDesc( t ) );
        }
      } catch ( Throwable ignored ) {
        o.remove( "accesos" );
      }
    }
    return out;
  }

  /** Que jar buscar cuando falta el driver (los mas comunes para un estudiante). */
  static String jarSugerido( String id ) {
    String u = id == null ? "" : id.toUpperCase( Locale.ROOT );
    if ( u.equals( "MYSQL" ) ) {
      return "el conector MySQL (mysql-connector-j-8.x.jar, de dev.mysql.com o Maven Central)";
    }
    if ( u.equals( "MARIADB" ) ) {
      return "mariadb-java-client-x.jar (Maven Central)";
    }
    if ( u.equals( "MSSQLNATIVE" ) ) {
      return "mssql-jdbc-x.jre8.jar (Microsoft JDBC Driver for SQL Server)";
    }
    if ( u.equals( "MSSQL" ) ) {
      return "jtds-1.3.1.jar (o usa el tipo MSSQLNATIVE con mssql-jdbc)";
    }
    if ( u.startsWith( "ORACLE" ) ) {
      return "ojdbc8.jar (Oracle)";
    }
    return "el .jar del driver JDBC de esa base";
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
    o.put( "nombre", d.getName() );
    o.put( "tipo", d.getPluginId() );
    o.put( "acceso", d.getAccessTypeDesc() );
    putSiHay( o, "host", d.getHostname() );
    String p = d.getDatabasePortNumberString();
    if ( p != null && !p.isEmpty() && !"-1".equals( p ) ) {
      o.put( "puerto", p );
    }
    putSiHay( o, "base", d.getDatabaseName() );
    putSiHay( o, "usuario", d.getUsername() );
    if ( d.getExtraOptions() != null && !d.getExtraOptions().isEmpty() ) {
      ObjectNode op = o.putObject( "opciones" );
      String pref = d.getPluginId() + ".";
      for ( Map.Entry<String, String> e : new TreeMap<String, String>( d.getExtraOptions() ).entrySet() ) {
        String k = e.getKey().startsWith( pref ) ? e.getKey().substring( pref.length() ) : e.getKey();
        if ( !k.toLowerCase( Locale.ROOT ).contains( "password" ) ) {
          op.put( k, e.getValue() );
        }
      }
    }
    if ( d.isShared() ) {
      o.put( "compartida", true );
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
        ArrayNode u = o.putArray( "usada_por" );
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
        out.add( "(fecha maxima de la transformacion)" );
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
          out.add( "(tabla de log " + lt.getLogTableType() + ")" );
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
    String nombre = Tools.optStr( a, "nombre", null );
    DatabaseMeta d;
    if ( xml != null ) {
      try {
        d = new DatabaseMeta( xml.trim() );
      } catch ( KettleXMLException e ) {
        throw new ToolError( "El <connection> no se pudo leer: " + e.getMessage().trim() );
      }
      if ( nombre != null ) {
        d.setName( nombre );
      }
      if ( d.getName() == null || d.getName().trim().isEmpty() ) {
        throw new ToolError( "La conexion no tiene nombre: pasa 'nombre' o <name> en el XML" );
      }
    } else {
      if ( nombre == null ) {
        throw new ToolError( "Falta el argumento 'nombre'" );
      }
      DatabaseInterface di = tipo( Tools.str( a, "tipo" ) );
      String acceso = Tools.optStr( a, "acceso", "Native" );
      int at = DatabaseMeta.getAccessType( acceso );
      boolean ok = false;
      List<String> validos = new ArrayList<String>();
      for ( int t : di.getAccessTypeList() ) {
        ok |= t == at;
        validos.add( DatabaseMeta.getAccessTypeDesc( t ) );
      }
      if ( !ok ) {
        throw new ToolError( "El tipo " + di.getPluginId() + " no admite acceso '" + acceso + "'. Validos: " + validos );
      }
      String host = Tools.optStr( a, "host", "" );
      String puerto = Tools.optStr( a, "puerto", null );
      if ( puerto == null ) {
        puerto = !host.isEmpty() && di.getDefaultDatabasePort() > 0 ? String.valueOf( di.getDefaultDatabasePort() ) : "";
      }
      d = new DatabaseMeta( nombre, di.getPluginId(), DatabaseMeta.getAccessTypeDesc( at ), host,
        Tools.str( a, "base" ), puerto, Tools.optStr( a, "usuario", "" ), optCrudo( a, "password" ) );
    }
    JsonNode op = a.get( "opciones" );
    if ( op != null && op.isObject() ) {
      for ( Iterator<Map.Entry<String, JsonNode>> it = op.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        d.addExtraOption( d.getPluginId(), e.getKey(), e.getValue().isTextual() ? e.getValue().asText()
          : e.getValue().toString() );
      }
    }
    JsonNode at = a.get( "atributos" );
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
    throw new ToolError( "No hay un tipo de base '" + t + "'." + ( parecidos.isEmpty() ? "" : " Quizas: " + parecidos
      + "." ) + " Mira tipos_de_base." );
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
    o.put( "reemplazada", antes != null );
    if ( antes != null ) {
      List<String> usos = usos( m, puesta );
      if ( !usos.isEmpty() ) {
        ArrayNode u = o.putArray( "usada_por" );
        for ( String x : usos ) {
          u.add( x );
        }
      }
    }
    if ( compartida ) {
      o.put( "aviso", "Era una conexion compartida (shared.xml): si el usuario guarda desde Spoon, el cambio puede "
        + "escribirse tambien en shared.xml" );
    }
    Boolean disp = driverDisponible( puesta );
    if ( Boolean.FALSE.equals( disp ) ) {
      o.put( "driver_disponible", false );
      o.put( "falta_driver", "No esta instalado el driver JDBC de " + puesta.getPluginId() + ": copia "
        + jarSugerido( puesta.getPluginId() ) + " a data-integration/lib y reinicia Spoon" );
    }
    String archivo = DbJdbc.avisoArchivo( puesta );
    if ( archivo != null ) {
      o.put( "aviso_archivo", archivo );
    }
    o.put( "deshacer", deshacer );
    return o;
  }

  /** Quita una conexion si nadie la usa. (UI) */
  static String borrar( Spoon spoon, AbstractMeta m, String nombre ) throws ToolError {
    DatabaseMeta d = buscarConexion( m, nombre );
    List<String> usos = usos( m, d );
    if ( !usos.isEmpty() ) {
      throw new ToolError( "La conexion '" + d.getName() + "' la usan: " + usos + ". Cambia o borra esos steps antes." );
    }
    int idx = m.indexOfDatabase( d );
    m.removeDatabase( idx );
    if ( spoon != null && m instanceof TransMeta ) {
      spoon.addUndoDelete( m, new Object[] { d }, new int[] { idx } );
    }
    DBCache.getInstance().clear( d.getName() );
    return "Conexion '" + d.getName() + "' borrada" + ( d.isShared() ? " de este documento (es compartida: sigue en "
      + "shared.xml y vuelve a aparecer al reabrir)" : "" );
  }

  static DatabaseMeta buscarConexion( AbstractMeta m, String nombre ) throws ToolError {
    DatabaseMeta d = m.findDatabase( nombre.trim() );
    if ( d == null ) {
      String[] hay = m.getDatabaseNames();
      throw new ToolError( "No hay ninguna conexion '" + nombre + "' en '" + Docs.nombre( m ) + "'. "
        + ( hay.length == 0 ? "El documento no tiene conexiones: crea una con agregar_conexion."
          : "Hay: " + java.util.Arrays.toString( hay ) ) );
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
      o.put( "nombre", p );
      o.put( "por_defecto", m.getParameterDefault( p ) );
      putSiHay( o, "descripcion", m.getParameterDescription( p ) );
      putSiHay( o, "valor", m.getParameterValue( p ) );
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
    o.put( "nombre", existia ? clave : nombre );
    o.put( "por_defecto", m.getParameterDefault( existia ? clave : nombre ) );
    putSiHay( o, "descripcion", m.getParameterDescription( existia ? clave : nombre ) );
    o.put( "creado", !existia );
    return o;
  }

  static void borrarParametro( AbstractMeta m, String nombre ) throws Exception {
    Map<String, String[]> todos = leerParametros( m );
    String clave = claveExistente( todos, nombre );
    if ( clave == null ) {
      throw new ToolError( "No hay ningun parametro '" + nombre + "'. Hay: " + todos.keySet() );
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
    String n = Tools.str( a, "nombre" ).trim();
    if ( n.startsWith( "${" ) && n.endsWith( "}" ) ) {
      n = n.substring( 2, n.length() - 1 ).trim();
    } else if ( n.startsWith( "%%" ) && n.endsWith( "%%" ) && n.length() > 4 ) {
      n = n.substring( 2, n.length() - 2 ).trim();
    }
    if ( n.isEmpty() ) {
      throw new ToolError( "El nombre del parametro esta vacio" );
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
    return Math.max( 1, Math.min( 600, Tools.optInt( a, "timeout_seg", def ) ) );
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
