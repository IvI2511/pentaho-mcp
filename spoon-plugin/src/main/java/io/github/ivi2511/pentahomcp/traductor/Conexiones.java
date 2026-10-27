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
        default: driver = null;
      }
      if ( "sqlite".equals( driver ) ) {
        r.add( v + " = create_engine(\"sqlite:///\" + " + base + ")" );
      } else if ( driver != null ) {
        r.add( v + " = create_engine(URL.create(" + CtxTrad.lit( driver ) + ", username=" + usuario + ", password="
          + clave + ", host=" + host + ", port=int(" + puerto + ") if " + puerto + " else None, database=" + base
          + "))" );
      } else {
        r.add( "# TODO: " + ctx.t( "conexión de tipo " + tipo + ": completa la URL de SQLAlchemy",
          "connection of type " + tipo + ": fill in the SQLAlchemy URL", "conexão do tipo " + tipo
            + ": complete a URL do SQLAlchemy" ) );
        r.add( v + " = create_engine(\"sqlite://\")" );
      }
    } else {
      String url;
      try {
        url = db.getURL();
      } catch ( Exception e ) {
        url = "jdbc:TODO";
      }
      r.add( v + " = {\"url\": " + ctx.texto( url ) + ", \"user\": " + usuario + ", \"password\": " + clave
        + "}" );
    }
    return r;
  }
}
