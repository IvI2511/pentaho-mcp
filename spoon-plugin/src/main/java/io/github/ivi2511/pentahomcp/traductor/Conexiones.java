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

  public static List<String> crear( DatabaseMeta db, CtxTrad ctx ) {
    List<String> r = new ArrayList<>();
    String v = ctx.conexion( db );
    String tipo = db.getPluginId() == null ? "" : db.getPluginId().toUpperCase();
    String host = ctx.texto( db.getHostname() );
    String puerto = ctx.texto( db.getDatabasePortNumberString() );
    String base = ctx.texto( db.getDatabaseName() );
    String usuario = ctx.texto( db.getUsername() );
    String clave = "os.environ.get(" + CtxTrad.lit( variableClave( db ) ) + ", \"\")";
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
