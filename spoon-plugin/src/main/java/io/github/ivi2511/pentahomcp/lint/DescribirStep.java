package io.github.ivi2511.pentahomcp.lint;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Element;

/**
 * La descripción que se propone para un step o una entrada sin descripción (step_without_description, 0.7.1): una línea
 * a partir del tipo y de su configuración, en el idioma del lint. «Lee ventas_2026_09.csv (8 campos)», «Filtra filas
 * con importe &gt; 0», «Ordena por cliente, fecha», «Escribe en la tabla dim_tienda (dw)». Los tipos que no conoce
 * dicen qué tipo de step son. Nunca contraseñas ni SQL entero: el nombre de la tabla, como mucho.
 */
public final class DescribirStep {

  /** Tope de la descripción propuesta. */
  static final int MAX = 140;
  private static final Pattern FROM = Pattern.compile( "(?is)\\bfrom\\s+([\\w.\"`\\[\\]$%{}]+)" );

  private DescribirStep() {
  }

  /** Para un step (raíz = el XML del step, tipo = su id de plugin). */
  public static String step( Ctx ctx, String tipo, Element x, String nombreTipo ) {
    String r = null;
    try {
      r = deStep( ctx, tipo, x );
    } catch ( RuntimeException e ) {
      r = null;
    }
    if ( r == null || r.trim().isEmpty() ) {
      r = ctx.t( "describe.generic", "type", nombreTipo == null || nombreTipo.isEmpty() ? tipo : nombreTipo );
    }
    return corto( r );
  }

  /** Para una entrada de job (pid = su id de plugin). */
  public static String entrada( Ctx ctx, String pid, Element x, String nombreTipo ) {
    String r = null;
    try {
      r = deEntrada( ctx, pid, x );
    } catch ( RuntimeException e ) {
      r = null;
    }
    if ( r == null || r.trim().isEmpty() ) {
      r = ctx.t( "describe.generic.entry", "type", nombreTipo == null || nombreTipo.isEmpty() ? pid : nombreTipo );
    }
    return corto( r );
  }

  static String corto( String s ) {
    String t = s.replace( '\n', ' ' ).replace( '\r', ' ' ).trim();
    return t.length() > MAX ? t.substring( 0, MAX - 1 ) + "…" : t;
  }

  private static String deStep( Ctx ctx, String tipo, Element x ) {
    switch ( tipo ) {
      case "CsvInput":
      case "ParallelGzipCsvInput":
        return lee( ctx, archivo( Nodos.txt( x, "filename" ) ), Nodos.nombres( x, "fields", "field", "name" ).size() );
      case "TextFileInput":
      case "OldTextFileInput":
      case "ExcelInput":
      case "JsonInput":
      case "getXMLData":
      case "AccessInput":
      case "XBaseInput":
        return lee( ctx, archivo( primerArchivo( x ) ), Nodos.nombres( x, "fields", "field", "name" ).size() );
      case "TableInput": {
        String tabla = tablaDe( Nodos.txt( x, "sql" ) );
        String con = Nodos.txt( x, "connection" );
        return tabla == null ? ctx.t( "describe.query", "connection", con ) : ctx.t( "describe.read_table", "table",
          tabla, "connection", con );
      }
      case "TableOutput":
        return ctx.t( "describe.write_table", "table", Nodos.txt( x, "table" ), "connection", Nodos.txt( x,
          "connection" ) );
      case "InsertUpdate":
      case "Update":
        return ctx.t( "describe.upsert_table", "table", Nodos.txt( Nodos.hijo( x, "lookup" ), "table" ), "connection",
          Nodos.txt( x, "connection" ) );
      case "Delete":
        return ctx.t( "describe.delete_table", "table", Nodos.txt( Nodos.hijo( x, "lookup" ), "table" ) );
      case "DBLookup":
        return ctx.t( "describe.db_lookup", "table", Nodos.txt( Nodos.hijo( x, "lookup" ), "table" ) );
      case "TextFileOutput":
      case "TextFileOutputLegacy":
      case "ExcelOutput":
      case "TypeExitExcelWriterStep":
      case "JsonOutput":
      case "XMLOutput": {
        Element f = Nodos.hijo( x, "file" );
        String n = Nodos.txt( f, "name" );
        String ext = Nodos.txt( f, "extension" );
        if ( ext.isEmpty() ) {
          ext = Nodos.txt( f, "extention" );
        }
        String a = archivo( n );
        if ( !ext.isEmpty() && !a.toLowerCase().endsWith( "." + ext.toLowerCase() ) ) {
          a = a + "." + ext;
        }
        return ctx.t( "describe.write_file", "file", a );
      }
      case "FilterRows": {
        String c = condicion( Nodos.hijo( Nodos.hijo( x, "compare" ), "condition" ) );
        return c == null ? ctx.t( "describe.filter_any" ) : ctx.t( "describe.filter", "condition", c );
      }
      case "SortRows":
        return ctx.t( "describe.sort", "fields", lista( Nodos.nombres( x, "fields", "field", "name" ) ) );
      case "GroupBy":
      case "MemoryGroupBy": {
        List<String> g = Nodos.nombres( x, "group", "field", "name" );
        int n = Nodos.nombres( x, "fields", "field", "aggregate" ).size();
        return g.isEmpty() ? ctx.t( "describe.aggregate_all", "n", n ) : ctx.t( "describe.group", "fields", lista( g ),
          "n", n );
      }
      case "StreamLookup":
        return ctx.t( "describe.stream_lookup", "step", Nodos.txt( x, "from" ), "keys", lista( claves( x ) ) );
      case "MergeJoin":
        return ctx.t( "describe.merge_join", "a", Nodos.txt( x, "step1" ), "b", Nodos.txt( x, "step2" ), "type", Nodos
          .txt( x, "join_type" ), "keys", lista( Nodos.nombres( x, "keys_1", "key", null ) ) );
      case "SelectValues": {
        int elegidos = Nodos.nombres( x, "fields", "field", "name" ).size();
        int quitados = Nodos.hijos( Nodos.hijo( x, "fields" ), "remove" ).size();
        int meta = Nodos.hijos( Nodos.hijo( x, "fields" ), "meta" ).size();
        if ( elegidos > 0 ) {
          return ctx.t( "describe.select", "n", elegidos );
        }
        if ( quitados > 0 ) {
          return ctx.t( "describe.remove", "n", quitados );
        }
        return meta > 0 ? ctx.t( "describe.change_types", "n", meta ) : null;
      }
      case "Calculator":
        return ctx.t( "describe.calculate", "fields", lista( nombresDe( x, "calculation", "field_name" ) ) );
      case "Formula":
        return ctx.t( "describe.calculate", "fields", lista( nombresDe( x, "formula", "field_name" ) ) );
      case "Constant":
        return ctx.t( "describe.constants", "fields", lista( Nodos.nombres( x, "fields", "field", "name" ) ) );
      case "DataGrid":
        return ctx.t( "describe.data_grid", "n", Nodos.hijos( Nodos.hijo( x, "data" ), "line" ).size(), "fields", lista(
          Nodos.nombres( x, "fields", "field", "name" ) ) );
      case "RowGenerator":
        return ctx.t( "describe.generate", "n", Nodos.txt( x, "limit" ) );
      case "Unique":
      case "UniqueRowsByHashSet": {
        List<String> f = Nodos.nombres( x, "fields", "field", "name" );
        return f.isEmpty() ? ctx.t( "describe.unique_all" ) : ctx.t( "describe.unique", "fields", lista( f ) );
      }
      case "Dummy":
        return ctx.t( "describe.dummy" );
      case "WriteToLog":
        return ctx.t( "describe.log" );
      case "Abort":
        return ctx.t( "describe.abort" );
      case "ValueMapper":
        return ctx.t( "describe.value_mapper", "field", Nodos.txt( x, "field_to_use" ) );
      case "IfNull":
        return ctx.t( "describe.if_null" );
      case "StringOperations":
      case "ReplaceString":
        return ctx.t( "describe.strings" );
      case "Validator":
        return ctx.t( "describe.validate", "n", Nodos.hijos( x, "validator_field" ).size() );
      case "ScriptValueMod":
      case "UserDefinedJavaClass":
        return ctx.t( "describe.script" );
      case "Append":
        return ctx.t( "describe.append", "a", Nodos.txt( x, "head_name" ), "b", Nodos.txt( x, "tail_name" ) );
      case "SwitchCase":
        return ctx.t( "describe.switch", "field", Nodos.txt( x, "fieldname" ) );
      default:
        return null;
    }
  }

  private static String deEntrada( Ctx ctx, String pid, Element x ) {
    switch ( pid ) {
      case "TRANS": {
        String f = archivo( Nodos.txt( x, "filename" ) );
        return ctx.t( "describe.run_trans", "name", f.isEmpty() ? Nodos.txt( x, "transname" ) : f );
      }
      case "JOB": {
        String f = archivo( Nodos.txt( x, "filename" ) );
        return ctx.t( "describe.run_job", "name", f.isEmpty() ? Nodos.txt( x, "jobname" ) : f );
      }
      case "SQL":
        return ctx.t( "describe.run_sql", "connection", Nodos.txt( x, "connection" ) );
      case "FILES_EXIST":
        return ctx.t( "describe.files_exist", "n", Nodos.hijos( Nodos.hijo( x, "fields" ), "field" ).size() );
      case "FILE_EXISTS":
        return ctx.t( "describe.file_exists", "file", archivo( Nodos.txt( x, "filename" ) ) );
      case "CREATE_FOLDER":
        return ctx.t( "describe.create_folder", "folder", archivo( Nodos.txt( x, "foldername" ) ) );
      case "DELETE_FILE":
      case "DELETE_FILES":
        return ctx.t( "describe.delete_files" );
      case "WRITE_TO_LOG":
        return ctx.t( "describe.write_log", "text", corto20( Nodos.txt( x, "logmessage" ) ) );
      case "ABORT":
        return ctx.t( "describe.abort_job" );
      case "SUCCESS":
        return ctx.t( "describe.success" );
      case "MAIL":
        return ctx.t( "describe.mail", "to", Nodos.txt( x, "destination" ) );
      case "SET_VARIABLES":
        return ctx.t( "describe.set_variables", "n", Nodos.hijos( Nodos.hijo( x, "fields" ), "field" ).size() );
      case "TABLE_EXISTS":
        return ctx.t( "describe.table_exists", "table", Nodos.txt( x, "tablename" ) );
      case "SHELL":
        return ctx.t( "describe.shell" );
      case "EVAL":
      case "SIMPLE_EVAL":
        return ctx.t( "describe.evaluate" );
      default:
        return null;
    }
  }

  // ---------------------------------------------------------------- ayudas

  private static String lee( Ctx ctx, String archivo, int campos ) {
    if ( archivo.isEmpty() ) {
      return null;
    }
    return campos > 0 ? ctx.t( "describe.read_file.fields", "file", archivo, "n", campos ) : ctx.t(
      "describe.read_file", "file", archivo );
  }

  /** El primer archivo de un input de varios archivos (file/name). */
  private static String primerArchivo( Element x ) {
    Element f = Nodos.hijo( x, "file" );
    String n = Nodos.txt( f, "name" );
    return n.isEmpty() ? Nodos.txt( x, "filename" ) : n;
  }

  /** El nombre del archivo, sin carpetas (con su variable si es todo lo que hay). */
  static String archivo( String ruta ) {
    String r = ruta == null ? "" : ruta.trim();
    int i = Math.max( r.lastIndexOf( '/' ), r.lastIndexOf( '\\' ) );
    String n = i >= 0 && i < r.length() - 1 ? r.substring( i + 1 ) : r;
    return n;
  }

  static String tablaDe( String sql ) {
    if ( sql == null ) {
      return null;
    }
    Matcher m = FROM.matcher( sql );
    return m.find() ? m.group( 1 ) : null;
  }

  /** Una condición simple de Filter rows en una línea («importe &gt; 0»); null si es compuesta o no se entiende. */
  static String condicion( Element c ) {
    if ( c == null ) {
      return null;
    }
    List<Element> subs = Nodos.hijos( Nodos.hijo( c, "conditions" ), "condition" );
    if ( subs.size() == 1 ) {
      return condicion( subs.get( 0 ) );
    }
    if ( !subs.isEmpty() ) {
      List<String> partes = new ArrayList<String>();
      for ( Element s : subs ) {
        String p = condicion( s );
        if ( p == null ) {
          return null;
        }
        String op = Nodos.txt( s, "operator" );
        partes.add( ( partes.isEmpty() || op.isEmpty() || "-".equals( op ) ? "" : op + " " ) + p );
      }
      String t = String.join( " ", partes );
      return t.length() > 80 ? null : t;
    }
    String izq = Nodos.txt( c, "leftvalue" );
    String fn = Nodos.txt( c, "function" );
    if ( izq.isEmpty() || fn.isEmpty() ) {
      return null;
    }
    String der = Nodos.txt( c, "rightvalue" );
    if ( der.isEmpty() ) {
      Element v = Nodos.hijo( c, "value" );
      der = Nodos.txt( v, "text" );
      if ( v != null && "String".equals( Nodos.txt( v, "type" ) ) ) {
        der = "'" + der + "'";
      }
    }
    boolean no = "Y".equalsIgnoreCase( Nodos.txt( c, "negated" ) );
    String t = ( izq + " " + fn + ( der.isEmpty() ? "" : " " + der ) ).trim();
    return no ? "NOT (" + t + ")" : t;
  }

  /** Las claves de Stream lookup (lookup/key/name). */
  private static List<String> claves( Element x ) {
    return Nodos.nombres( x, "lookup", "key", "name" );
  }

  private static List<String> nombresDe( Element x, String item, String tag ) {
    List<String> r = new ArrayList<String>();
    for ( Element e : Nodos.hijos( x, item ) ) {
      String n = Nodos.txt( e, tag );
      if ( !n.isEmpty() ) {
        r.add( n );
      }
    }
    return r;
  }

  /** «a, b, c» (los primeros 4 y «…»). */
  static String lista( List<String> l ) {
    if ( l.isEmpty() ) {
      return "?";
    }
    StringBuilder sb = new StringBuilder();
    for ( int i = 0; i < l.size() && i < 4; i++ ) {
      sb.append( i == 0 ? "" : ", " ).append( l.get( i ) );
    }
    if ( l.size() > 4 ) {
      sb.append( ", …" );
    }
    return sb.toString();
  }

  private static String corto20( String s ) {
    String t = s == null ? "" : s.replace( '\n', ' ' ).trim();
    return t.length() > 40 ? t.substring( 0, 39 ) + "…" : t;
  }
}
