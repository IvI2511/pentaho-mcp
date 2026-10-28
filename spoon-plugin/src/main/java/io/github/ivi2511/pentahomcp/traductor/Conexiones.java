package io.github.ivi2511.pentahomcp.traductor;

import java.util.ArrayList;
import java.util.List;

import org.pentaho.di.core.database.DatabaseMeta;

/**
 * El código que crea cada conexión usada: un engine de SQLAlchemy en pandas, un dict de opciones JDBC en PySpark. La
 * contraseña sale de la variable de entorno PDI_PASSWORD_&lt;CONEXIÓN&gt;: nunca se escribe la del .ktr.
 */
public final class Conexiones {

  private Conexiones() {
  }

  /** Variable de entorno con la contraseña de la conexión. */
  public static String variableClave( DatabaseMeta db ) {
    return "PDI_PASSWORD_" + CtxTrad.ident( db.getName() ).toUpperCase();
  }

  private static boolean vacia( String s ) {
    return s == null || s.trim().isEmpty();
  }

  /** ¿La conexión lleva contraseña? (un servidor con usuario o con contraseña; no un archivo SQLite / H2 sin usuario) */
  static boolean pideClave( DatabaseMeta db, String tipo ) {
    if ( "SQLITE".equals( tipo ) ) {
      return false;
    }
    return !vacia( db.getUsername() ) || !vacia( db.getPassword() );
  }

  /**
   * La función _clave, si alguna de las líneas de conexión la usa: la contraseña sale de la variable de entorno o,
   * si no está, se pide sin mostrarla (en un notebook, un cuadro; en una terminal, por consola) y no queda escrita en
   * ningún archivo. Sin terminal ni notebook (un proceso en segundo plano) no pide nada.
   */
  public static List<String> ayudanteClave( List<String> lineasConexion, CtxTrad ctx ) {
    List<String> r = new ArrayList<>();
    if ( !String.join( "\n", lineasConexion ).contains( "_clave(" ) ) {
      return r;
    }
    r.add( "_CLAVES = {}  # " + ctx.t( "servidor → contraseña ya pedida (dos conexiones al mismo servidor: una vez)",
      "server → password already asked (two connections to the same server: once)", "servidor → senha já pedida (duas "
        + "conexões ao mesmo servidor: uma vez)" ) );
    r.add( "" );
    r.add( "" );
    r.add( "def _clave(variable, conexion, servidor):" );
    r.add( "    \"\"\"" + ctx.t( "Contraseña de una conexión: de la variable de entorno o, si no está, se pide sin "
      + "mostrarla (no queda en ningún archivo).", "Password of a connection: from the environment variable or, if it "
        + "is not set, asked without showing it (it is not saved in any file).", "Senha de uma conexão: da variável de "
          + "ambiente ou, se não estiver definida, pedida sem a mostrar (não fica em nenhum arquivo)." ) + "\"\"\"" );
    r.add( "    valor = os.environ.get(variable, _CLAVES.get(servidor))" );
    r.add( "    if valor is None:" );
    r.add( "        import getpass, sys" );
    r.add( "        try:" );
    r.add( "            interactivo = get_ipython() is not None  # " + ctx.t( "notebook", "notebook", "notebook" ) );
    r.add( "        except NameError:" );
    r.add( "            interactivo = sys.stdin is not None and sys.stdin.isatty()" );
    r.add( "        if not interactivo:" );
    r.add( "            return \"\"" );
    r.add( "        valor = getpass.getpass(" + CtxTrad.lit( ctx.t( "Contraseña de ", "Password of ", "Senha de " ) )
      + " + (servidor or conexion) + \" (\" + conexion + \"; \" + variable + \"): \")" );
    r.add( "        _CLAVES[servidor] = valor  # " + ctx.t( "sólo en memoria, para esta sesión", "only in memory, for "
      + "this session", "só na memória, para esta sessão" ) );
    r.add( "    return valor" );
    r.add( "" );
    r.add( "" );
    return r;
  }

  public static List<String> crear( DatabaseMeta db, CtxTrad ctx ) {
    List<String> r = new ArrayList<>();
    String v = ctx.conexion( db );
    String tipo = db.getPluginId() == null ? "" : db.getPluginId().toUpperCase();
    String host = ctx.texto( db.getHostname() );
    String puerto = ctx.texto( db.getDatabasePortNumberString() );
    String base = ctx.texto( db.getDatabaseName() );
    String usuario = ctx.texto( db.getUsername() );
    String servidor = ( vacia( db.getUsername() ) ? "" : db.getUsername() + "@" ) + ( vacia( db.getHostname() ) ? ""
      : db.getHostname() ) + ( vacia( db.getDatabasePortNumberString() ) ? "" : ":" + db.getDatabasePortNumberString() );
    String clave = pideClave( db, tipo ) ? "_clave(" + CtxTrad.lit( variableClave( db ) ) + ", " + CtxTrad.lit(
      db.getName() ) + ", " + CtxTrad.lit( servidor ) + ")" : "os.environ.get(" + CtxTrad.lit( variableClave( db ) )
        + ", \"\")";
    if ( ctx.pandas() ) {
      ctx.importar( "from sqlalchemy import create_engine, text" );
      ctx.importar( "from sqlalchemy.engine import URL" );
      String driver;
      switch ( tipo ) {
        case "MYSQL": case "MARIADB": driver = "mysql+pymysql"; break;
        case "POSTGRESQL": driver = "postgresql+psycopg2"; break;
        case "MSSQL": case "MSSQLNATIVE": driver = "mssql+pyodbc"; break;
        case "ORACLE": driver = "oracle+oracledb"; break;
        case "SQLITE": driver = "sqlite"; break;
        case "H2": driver = "h2"; break;
        default: driver = null;
      }
      if ( "h2".equals( driver ) ) {
        r.add( "# TODO: " + ctx.t( "H2 no tiene dialecto de SQLAlchemy: usa jaydebeapi o exporta la base",
          "H2 has no SQLAlchemy dialect: use jaydebeapi or export the database",
          "H2 não tem dialeto do SQLAlchemy: use jaydebeapi ou exporte a base" ) );
        r.add( v + " = create_engine(\"sqlite://\")  # " + CtxTrad.lit( urlJdbc( db, tipo ) ) );
      } else if ( "sqlite".equals( driver ) ) {
        r.add( v + " = create_engine(\"sqlite:///\" + " + base + ")" );
      } else if ( driver != null ) {
        r.add( v + " = create_engine(URL.create(" + CtxTrad.lit( driver ) + ", username=" + usuario + ", password="
          + clave + ", host=" + host + ", port=int(" + puerto + ") if " + puerto + " else None, database=" + base
          + "))" );
      } else {
        r.add( "# JDBC: " + urlJdbc( db, tipo ) );
        r.add( "# TODO: " + ctx.t( "conexión de tipo " + tipo + ": completa la URL de SQLAlchemy",
          "connection of type " + tipo + ": fill in the SQLAlchemy URL", "conexão do tipo " + tipo
            + ": complete a URL do SQLAlchemy" ) );
        r.add( v + " = create_engine(\"sqlite://\")" );
      }
    } else {
      String url = urlJdbc( db, tipo );
      StringBuilder d = new StringBuilder( v + " = {\"url\": " + ctx.texto( url ) );
      String drv = db.getDriverClass();
      if ( drv != null && !drv.isEmpty() ) {
        d.append( ", \"driver\": " ).append( CtxTrad.lit( drv ) );
      }
      if ( db.getUsername() != null && !db.getUsername().isEmpty() ) {
        d.append( ", \"user\": " ).append( usuario ).append( ", \"password\": " ).append( clave );
      }
      r.add( "# " + ctx.t( "PySpark necesita el .jar del driver JDBC: spark.jars o --jars al lanzar",
        "PySpark needs the JDBC driver .jar: spark.jars or --jars when launching",
        "O PySpark precisa do .jar do driver JDBC: spark.jars ou --jars ao iniciar" ) );
      r.add( d.append( "}" ).toString() );
    }
    return r;
  }

  /** URL JDBC con los valores tal como están en el .ktr (las variables quedan como ${X}: nunca rutas locales). */
  static String urlJdbc( DatabaseMeta db, String tipo ) {
    String host = nv( db.getHostname() );
    String puerto = nv( db.getDatabasePortNumberString() );
    if ( "-1".equals( puerto ) ) {
      puerto = "";
    }
    String hp = host + ( puerto.isEmpty() ? "" : ":" + puerto );
    String base = nv( db.getDatabaseName() );
    switch ( tipo ) {
      case "SQLITE": return "jdbc:sqlite:" + base;
      case "H2": return "jdbc:h2:" + base;
      case "MYSQL": return "jdbc:mysql://" + hp + "/" + base;
      case "MARIADB": return "jdbc:mariadb://" + hp + "/" + base;
      case "POSTGRESQL": return "jdbc:postgresql://" + hp + "/" + base;
      case "ORACLE": return base.startsWith( "/" ) ? "jdbc:oracle:thin:@//" + hp + base : "jdbc:oracle:thin:@" + hp
        + ":" + base;
      case "MSSQL": case "MSSQLNATIVE": return "jdbc:sqlserver://" + hp + ";databaseName=" + base;
      default:
        String u = db.getAttributes() == null ? null : db.getAttributes().getProperty( "CUSTOM_URL" );
        return u != null && !u.isEmpty() ? u : "jdbc:TODO";
    }
  }

  private static String nv( String s ) {
    return s == null ? "" : s;
  }
}
