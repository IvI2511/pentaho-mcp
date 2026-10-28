package io.github.ivi2511.pentahomcp.traductor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.trans.step.StepErrorMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Traductores de los steps que escriben (archivos, tablas, dimensiones). Todos dejan pasar las filas tal cual (la
 * salida del step es su entrada), marcan el bloque con {@link Bloque#escribe} y leen la configuración del XML del step
 * (Excel, JSON y XML son plugins: así no dependen de sus clases).
 *
 * <p>En PySpark los archivos se escriben como un único archivo pasando por pandas ({@code toPandas()}): es lo que
 * hace PDI (un archivo), a costa de juntar los datos en el driver. Las escrituras fila a fila en bases (insert /
 * update, dimensiones) usan los mismos ayudantes de SQLAlchemy que en pandas.
 */
final class TradSalidas {

  private TradSalidas() {
  }

  static void registrar() {
    Registro.step( TradSalidas::texto, "TextFileOutput" );
    Registro.step( TradSalidas::excel, "ExcelOutput", "TypeExitExcelWriterStep" );
    Registro.step( TradSalidas::json, "JsonOutput" );
    Registro.step( TradSalidas::xmlSalida, "XMLOutput" );
    Registro.step( TradSalidas::sqlArchivo, "SQLFileOutput" );
    Registro.step( TradSalidas::tabla, "TableOutput" );
    Registro.step( TradSalidas::insertarActualizar, "InsertUpdate", "Update", "Delete", "SynchronizeAfterMerge" );
    Registro.step( TradSalidas::dimension, "DimensionLookup" );
    Registro.step( TradSalidas::combinacion, "CombinationLookup" );
    Registro.step( TradSalidas::sql, "ExecSQL", "ExecSQLRow" );
    Registro.step( TradSalidas::log, "WriteToLog" );
  }

  // ================================================================ comunes

  /** Bloque con la entrada unida; la salida del step es la misma entrada. Devuelve {in, out}. */
  private static String[] paso( StepMeta s, CtxTrad ctx, Bloque b ) {
    String in = ctx.entradaUnida( s, b );
    if ( in == null ) {
      in = ctx.pandas() ? "pd.DataFrame()" : "spark.createDataFrame([], T.StructType([]))";
    }
    String out = ctx.df( s );
    b.l( out + " = " + in );
    b.escribe = true;
    b.escribeDf = out;
    b.vista = out;
    return new String[] { in, out };
  }

  /** En PySpark: una variable con el DataFrame pasado a pandas (para escribir un solo archivo o fila a fila). */
  private static String aPandas( CtxTrad ctx, Bloque b, String df ) {
    if ( ctx.pandas() ) {
      return df;
    }
    ctx.importar( "import pandas as pd" );
    String v = ctx.nuevo( "pdf_" + CtxTrad.ident( b.paso ) );
    b.l( v + " = " + df + ".toPandas()  # " + ctx.t( "junta las filas en el driver", "collects the rows on the "
      + "driver", "junta as linhas no driver" ) );
    return v;
  }

  private static Element hijoEl( Element e, String tag ) {
    if ( e == null ) {
      return null;
    }
    for ( Node n = e.getFirstChild(); n != null; n = n.getNextSibling() ) {
      if ( n instanceof Element && tag.equals( n.getNodeName() ) ) {
        return (Element) n;
      }
    }
    return null;
  }

  private static List<Element> hijos( Element e, String tag ) {
    List<Element> r = new ArrayList<>();
    if ( e == null ) {
      return r;
    }
    for ( Node n = e.getFirstChild(); n != null; n = n.getNextSibling() ) {
      if ( n instanceof Element && tag.equals( n.getNodeName() ) ) {
        r.add( (Element) n );
      }
    }
    return r;
  }

  private static boolean si( Element e, String tag ) {
    return "Y".equalsIgnoreCase( CtxTrad.hijo( e, tag ).trim() );
  }

  private static String v( Element e, String tag ) {
    return CtxTrad.hijo( e, tag );
  }

  private static int entero( String s, int def ) {
    try {
      return Integer.parseInt( s.trim() );
    } catch ( RuntimeException e ) {
      return def;
    }
  }

  private static String lista( List<String> l ) {
    StringBuilder b = new StringBuilder( "[" );
    for ( String x : l ) {
      b.append( b.length() == 1 ? "" : ", " ).append( CtxTrad.lit( x ) );
    }
    return b.append( "]" ).toString();
  }

  private static String dict( Map<String, String> m ) {
    StringBuilder b = new StringBuilder( "{" );
    for ( Map.Entry<String, String> e : m.entrySet() ) {
      b.append( b.length() == 1 ? "" : ", " ).append( CtxTrad.lit( e.getKey() ) ).append( ": " ).append( CtxTrad.lit(
        e.getValue() ) );
    }
    return b.append( "}" ).toString();
  }

  /** La conexión del step (por el nombre en &lt;connection&gt;). */
  private static DatabaseMeta base( StepMeta s, CtxTrad ctx, Element x ) {
    String n = v( x, "connection" );
    DatabaseMeta db = n.isEmpty() ? null : ctx.trans.findDatabase( n );
    if ( db == null && s.getStepMetaInterface().getUsedDatabaseConnections() != null && s.getStepMetaInterface()
      .getUsedDatabaseConnections().length > 0 ) {
      db = s.getStepMetaInterface().getUsedDatabaseConnections()[ 0 ];
    }
    return db;
  }

  /** Expresión del engine de SQLAlchemy: engine_x en pandas; en PySpark, uno armado desde las opciones JDBC. */
  private static String motor( CtxTrad ctx, DatabaseMeta db ) {
    String c = ctx.conexion( db );
    if ( ctx.pandas() ) {
      return c;
    }
    ctx.importar( "from sqlalchemy import create_engine, text" );
    ctx.ayudante( "_engine_jdbc", ENGINE_JDBC );
    return "_engine_jdbc(" + c + ")";
  }

  /** Nombre de tabla (con esquema si lo hay) como expresión, y el esquema por separado. */
  private static String esquema( CtxTrad ctx, String e ) {
    return e == null || e.trim().isEmpty() ? "None" : ctx.texto( e );
  }

  private static String sinConexion( StepMeta s, CtxTrad ctx, Bloque b ) {
    b.manual( ctx.t( "El step no tiene conexión: elige la base a mano.", "The step has no connection: choose the "
      + "database by hand.", "O step não tem conexão: escolha a base à mão." ) );
    return "None";
  }

  // ================================================================ formatos

  /** Máscara de fecha de Java (SimpleDateFormat) a strftime: una sola conversión, la de {@link TradCampos#strftime}. */
  static String fechaPy( String java ) {
    return java == null || java.isEmpty() ? "" : TradCampos.strftime( java );
  }

  /** Código Python que convierte una fecha (Series datetime) a texto con la máscara de Java dada. */
  private static String fechaATexto( String serie, String mascara ) {
    String f = fechaPy( mascara );
    // %f son microsegundos (6 cifras); SSS de Java son milisegundos: se cortan las 3 últimas
    if ( mascara.endsWith( "SSS" ) && f.endsWith( "%f" ) ) {
      return serie + ".dt.strftime(" + CtxTrad.lit( f ) + ").str[:-3]";
    }
    return serie + ".dt.strftime(" + CtxTrad.lit( f ) + ")";
  }

  /** Máscara de número de Java (DecimalFormat) a la llamada del ayudante _numero_texto. */
  private static String numeroATexto( CtxTrad ctx, String serie, String mascara, String dec, String grupo ) {
    String m = mascara.split( ";" )[ 0 ];
    int punto = m.indexOf( '.' );
    String ent = punto < 0 ? m : m.substring( 0, punto );
    String fr = punto < 0 ? "" : m.substring( punto + 1 );
    int decimales = 0;
    int fijos = 0;
    for ( char c : fr.toCharArray() ) {
      if ( c == '0' ) {
        decimales++;
        fijos++;
      } else if ( c == '#' ) {
        decimales++;
      }
    }
    boolean agrupar = ent.contains( "," );
    ctx.ayudante( "_numero_texto", NUMERO_TEXTO );
    return "_numero_texto(" + serie + ", " + decimales + ", " + fijos + ", " + ( agrupar ? "True" : "False" ) + ", "
      + CtxTrad.lit( dec == null || dec.isEmpty() ? "." : dec ) + ", " + CtxTrad.lit( grupo == null || grupo.isEmpty()
        ? "," : grupo ) + ")";
  }

  /**
   * Prepara las columnas a escribir como texto con los formatos de PDI: elige y ordena los campos, fechas con su
   * máscara (o la de PDI por defecto), números con máscara, booleanos Y/N, recorte y valor para nulos. Devuelve la
   * variable con el DataFrame listo y la lista de columnas de texto (para el encierro).
   */
  private static String[] formatear( StepMeta s, CtxTrad ctx, Bloque b, String df, List<Element> campos,
                                     String etiquetaNombre ) {
    RowMetaInterface rm = ctx.camposEntrada( s );
    List<String> nombres = new ArrayList<>();
    for ( Element c : campos ) {
      nombres.add( v( c, etiquetaNombre ) );
    }
    if ( nombres.isEmpty() ) {
      nombres.addAll( java.util.Arrays.asList( rm.getFieldNames() ) );
    }
    String sal = ctx.nuevo( "sal_" + CtxTrad.ident( b.paso ) );
    if ( campos.isEmpty() ) {
      b.l( sal + " = " + df + ".copy()" );
    } else {
      b.l( sal + " = " + df + "[" + lista( nombres ) + "].copy()" );
    }
    List<String> textos = new ArrayList<>();
    for ( int i = 0; i < nombres.size(); i++ ) {
      String n = nombres.get( i );
      Element c = i < campos.size() ? campos.get( i ) : null;
      ValueMetaInterface vm = rm.searchValueMeta( n );
      int tipo = vm == null ? ValueMetaInterface.TYPE_STRING : vm.getType();
      String formato = c == null ? "" : v( c, "format" );
      if ( formato.isEmpty() && vm != null && vm.getConversionMask() != null ) {
        formato = vm.getConversionMask();
      }
      String dec = c == null || v( c, "decimal" ).isEmpty() ? vm == null ? "" : vm.getDecimalSymbol() : v( c,
        "decimal" );
      String grupo = c == null || v( c, "group" ).isEmpty() ? vm == null ? "" : vm.getGroupingSymbol() : v( c,
        "group" );
      String col = sal + "[" + CtxTrad.lit( n ) + "]";
      switch ( tipo ) {
        case ValueMetaInterface.TYPE_DATE:
        case ValueMetaInterface.TYPE_TIMESTAMP:
          b.l( col + " = " + fechaATexto( col, formato.isEmpty() ? "yyyy/MM/dd HH:mm:ss.SSS" : formato ) );
          break;
        case ValueMetaInterface.TYPE_NUMBER:
        case ValueMetaInterface.TYPE_BIGNUMBER:
        case ValueMetaInterface.TYPE_INTEGER:
          boolean simple = tipo == ValueMetaInterface.TYPE_INTEGER && formato.matches( "#*0?(;-#*0?)?" );
          if ( !formato.isEmpty() && !simple ) {
            b.l( col + " = " + numeroATexto( ctx, col, formato, dec, grupo ) );
          }
          break;
        case ValueMetaInterface.TYPE_BOOLEAN:
          b.l( col + " = " + col + ".map({True: \"Y\", False: \"N\"})" );
          break;
        default:
          textos.add( n );
          String trim = c == null ? "" : v( c, "trim_type" );
          if ( "both".equals( trim ) ) {
            b.l( col + " = " + col + ".str.strip()" );
          } else if ( "left".equals( trim ) ) {
            b.l( col + " = " + col + ".str.lstrip()" );
          } else if ( "right".equals( trim ) ) {
            b.l( col + " = " + col + ".str.rstrip()" );
          }
      }
      String nulo = c == null ? "" : v( c, "nullif" );
      if ( !nulo.isEmpty() ) {
        b.l( col + " = " + col + ".astype(object).where(" + col + ".notna(), " + ctx.texto( nulo ) + ")" );
      }
    }
    return new String[] { sal, lista( textos ) };
  }

  /** Expresión del nombre del archivo como lo arma PDI: nombre[_fecha][_hora | fecha a medida][_parte].extensión. */
  private static String nombreArchivo( CtxTrad ctx, Element f, String parte ) {
    String fecha = "";
    if ( si( f, "SpecifyFormat" ) && !v( f, "date_time_format" ).isEmpty() ) {
      fecha = fechaPy( v( f, "date_time_format" ) );
    } else {
      fecha = ( si( f, "add_date" ) ? "_%Y%m%d" : "" ) + ( si( f, "add_time" ) ? "_%H%M%S" : "" );
    }
    ctx.ayudante( "_nombre_salida", NOMBRE_SALIDA );
    return "_nombre_salida(" + ctx.texto( v( f, "name" ) ) + ", " + ctx.texto( v( f, "extention" ) )
      + ( fecha.isEmpty() ? "" : ", fecha=" + CtxTrad.lit( fecha ) ) + ( parte == null ? "" : ", parte=" + parte )
      + ")";
  }

  // ================================================================ archivos

  /** Text file output → el ayudante _escribir_texto (separador, encierro, cabecera, anexar, formatos, partes). */
  static Bloque texto( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = new Bloque( s.getName(), s.getStepID() );
    String[] io = paso( s, ctx, b );
    Element f = hijoEl( x, "file" );
    String pdf = aPandas( ctx, b, io[ 0 ] );
    String[] fm = formatear( s, ctx, b, pdf, hijos( hijoEl( x, "fields" ), "field" ), "name" );
    String sep = v( x, "separator" );
    String encl = v( x, "enclosure" );
    String fin = "UNIX".equalsIgnoreCase( v( x, "format" ) ) ? "\\n" : "None".equalsIgnoreCase( v( x, "format" ) )
      ? "" : "CR".equalsIgnoreCase( v( x, "format" ) ) ? "\\r" : "\\r\\n";
    String enc = v( x, "encoding" ).isEmpty() ? "utf-8" : v( x, "encoding" );
    ctx.ayudante( "_escribir_texto", ESCRIBIR_TEXTO );
    String op1 = "sep=" + ctx.texto( sep ) + ", encierro=" + ctx.texto( encl ) + ( si( x, "enclosure_forced" )
      ? ", forzar=True" : "" ) + ( si( x, "header" ) ? "" : ", cabecera=False" ) + ( si( f, "append" )
        ? ", anexar=True" : "" ) + ", fin=\"" + fin + "\",";
    String op2 = "codificacion=" + CtxTrad.lit( enc ) + ", textos=" + fm[ 1 ] + ( si( x, "create_parent_folder" )
      ? ", carpeta=True" : "" ) + ( si( f, "do_not_open_new_file_init" ) ? ", vacio=False" : "" ) + ")";
    int partes = entero( v( f, "splitevery" ), 0 );
    if ( si( x, "fileNameInField" ) && !v( x, "fileNameField" ).isEmpty() ) {
      String campo = CtxTrad.lit( v( x, "fileNameField" ) );
      b.l( "for _ruta, _filas in " + fm[ 0 ] + ".groupby(" + pdf + "[" + campo + "], sort=False):" );
      b.l( "    _escribir_texto(_filas.drop(columns=[" + campo + "], errors=\"ignore\"), _ruta, " + op1 );
      b.l( "                    " + op2 );
    } else if ( partes > 0 ) {
      b.l( "for _parte, _desde in enumerate(range(0, max(len(" + fm[ 0 ] + "), 1), " + partes + ")):" );
      b.l( "    _escribir_texto(" + fm[ 0 ] + ".iloc[_desde:_desde + " + partes + "], " + nombreArchivo( ctx, f,
        "_parte" ) + ", " + op1 );
      b.l( "                    " + op2 );
    } else {
      String ruta = ctx.nuevo( "ruta_" + CtxTrad.ident( s.getName() ) );
      b.l( ruta + " = " + nombreArchivo( ctx, f, si( f, "split" ) ? "0" : null ) );
      b.l( "_escribir_texto(" + fm[ 0 ] + ", " + ruta + ", " + op1 );
      b.l( "                " + op2 );
    }
    if ( !v( x, "footer" ).isEmpty() && si( x, "footer" ) ) {
      b.aproximado( ctx.t( "El pie (footer) no se escribe.", "The footer is not written.",
        "O rodapé não é escrito." ) );
    }
    if ( !v( x, "compression" ).isEmpty() && !"None".equalsIgnoreCase( v( x, "compression" ) ) ) {
      b.aproximado( ctx.t( "La compresión " + v( x, "compression" ) + " no se aplica: comprime el archivo después.",
        "Compression " + v( x, "compression" ) + " is not applied: compress the file afterwards.", "A compressão "
          + v( x, "compression" ) + " não é aplicada: comprima o arquivo depois." ) );
    }
    if ( si( f, "pad" ) || si( f, "fast_dump" ) ) {
      b.aproximado( ctx.t( "Ni el relleno a ancho fijo ni el volcado rápido se traducen.", "Neither fixed-width "
        + "padding nor fast dump are translated.", "Nem o preenchimento a largura fixa nem o despejo rápido são "
          + "traduzidos." ) );
    }
    if ( ctx.spark() ) {
      b.l( "# " + ctx.t( "en Spark distribuido (una carpeta con varios part-*.csv): ",
        "distributed Spark (a folder with part-*.csv files): ",
        "em Spark distribuído (uma pasta com vários part-*.csv): " ) + io[ 1 ]
        + ".write.mode(\"overwrite\").option(\"header\", True).option(\"sep\", " + ctx.texto( sep ) + ").csv(...)" );
      b.nota( ctx.t( "Spark escribe una carpeta con varios archivos; aquí se escribe un solo archivo como en PDI, "
        + "pasando por pandas (junta las filas en el driver).", "Spark writes a folder with several files; here a "
          + "single file is written as in PDI, through pandas (it collects the rows on the driver).", "O Spark escreve "
            + "uma pasta com vários arquivos; aqui se escreve um só arquivo como no PDI, via pandas (junta as linhas "
            + "no driver)." ) );
    }
    return b;
  }

  /** Microsoft Excel output / writer → DataFrame.to_excel (openpyxl, .xlsx). */
  static Bloque excel( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = new Bloque( s.getName(), s.getStepID() );
    String[] io = paso( s, ctx, b );
    Element f = hijoEl( x, "file" );
    boolean writer = "TypeExitExcelWriterStep".equalsIgnoreCase( s.getStepID() );
    String pdf = aPandas( ctx, b, io[ 0 ] );
    List<Element> campos = hijos( hijoEl( x, "fields" ), "field" );
    List<String> nombres = new ArrayList<>();
    Map<String, String> titulos = new LinkedHashMap<>();
    for ( Element c : campos ) {
      nombres.add( v( c, "name" ) );
      if ( writer && !v( c, "title" ).isEmpty() && !v( c, "title" ).equals( v( c, "name" ) ) ) {
        titulos.put( v( c, "name" ), v( c, "title" ) );
      }
    }
    String sal = nombres.isEmpty() ? pdf : pdf + "[" + lista( nombres ) + "]";
    if ( !titulos.isEmpty() ) {
      sal += ".rename(columns=" + dict( titulos ) + ")";
    }
    String hoja = v( f, "sheetname" ).isEmpty() ? "Sheet1" : v( f, "sheetname" );
    String ext = v( f, "extention" ).isEmpty() ? "xls" : v( f, "extention" );
    if ( "xls".equalsIgnoreCase( ext ) ) {
      ext = "xlsx";
      b.aproximado( ctx.t( "PDI escribe .xls (Excel 97); pandas no escribe .xls: se escribe .xlsx.", "PDI writes .xls "
        + "(Excel 97); pandas cannot write .xls: .xlsx is written.", "O PDI escreve .xls (Excel 97); o pandas não "
          + "escreve .xls: escreve-se .xlsx." ) );
    }
    String fecha = si( f, "SpecifyFormat" ) && !v( f, "date_time_format" ).isEmpty() ? ", fecha=" + CtxTrad.lit(
      fechaPy( v( f, "date_time_format" ) ) ) : ( si( f, "add_date" ) || si( f, "add_time" ) ) ? ", fecha="
        + CtxTrad.lit( ( si( f, "add_date" ) ? "_%Y%m%d" : "" ) + ( si( f, "add_time" ) ? "_%H%M%S" : "" ) ) : "";
    ctx.ayudante( "_nombre_salida", NOMBRE_SALIDA );
    String ruta = ctx.nuevo( "ruta_" + CtxTrad.ident( s.getName() ) );
    b.l( ruta + " = _nombre_salida(" + ctx.texto( v( f, "name" ) ) + ", " + CtxTrad.lit( ext ) + fecha + ")" );
    int fila = 0;
    int columna = 0;
    String celda = writer ? v( x, "startingCell" ) : "";
    java.util.regex.Matcher m = java.util.regex.Pattern.compile( "([A-Za-z]+)(\\d+)" ).matcher( celda );
    if ( m.matches() ) {
      for ( char c : m.group( 1 ).toUpperCase().toCharArray() ) {
        columna = columna * 26 + ( c - 'A' + 1 );
      }
      columna--;
      fila = Integer.parseInt( m.group( 2 ) ) - 1;
    }
    boolean cabecera = si( x, "header" );
    boolean reusar = writer && "reuse".equalsIgnoreCase( v( f, "if_file_exists" ) );
    boolean anexar = !writer && si( x, "append" );
    String pos = ( fila > 0 ? ", startrow=" + fila : "" ) + ( columna > 0 ? ", startcol=" + columna : "" );
    if ( reusar || anexar ) {
      String modo = writer && "reuse".equalsIgnoreCase( v( f, "if_sheet_exists" ) ) ? "overlay" : "replace";
      b.l( "with pd.ExcelWriter(" + ruta + ", engine=\"openpyxl\", mode=\"a\" if os.path.exists(" + ruta
        + ") else \"w\"," );
      b.l( "                   if_sheet_exists=" + CtxTrad.lit( modo ) + " if os.path.exists(" + ruta
        + ") else None) as _xl:" );
      b.l( "    " + sal + ".to_excel(_xl, sheet_name=" + ctx.texto( hoja ) + ", index=False" + ( cabecera ? ""
        : ", header=False" ) + pos + ")" );
      if ( writer && si( x, "appendLines" ) || anexar ) {
        b.aproximado( ctx.t( "Añadir filas al final de una hoja existente no se traduce: la hoja se reescribe.",
          "Appending rows at the end of an existing sheet is not translated: the sheet is rewritten.",
          "Acrescentar linhas ao fim de uma folha existente não é traduzido: a folha é reescrita." ) );
      }
    } else {
      b.l( sal + ".to_excel(" + ruta + ", sheet_name=" + ctx.texto( hoja ) + ", index=False" + ( cabecera ? ""
        : ", header=False" ) + pos + ")" );
    }
    if ( entero( v( f, "splitevery" ), 0 ) > 0 || hijoEl( x, "template" ) != null && si( hijoEl( x, "template" ),
      "enabled" ) ) {
      b.aproximado( ctx.t( "Ni las plantillas ni partir el archivo cada N filas se traducen.", "Neither templates nor "
        + "splitting every N rows are translated.", "Nem modelos nem dividir o arquivo a cada N linhas são "
          + "traduzidos." ) );
    }
    b.nota( ctx.t( "Formatos y estilos de celda de Excel no se copian (sólo los valores).", "Excel cell formats and "
      + "styles are not copied (values only).", "Formatos e estilos de célula do Excel não são copiados (só os "
        + "valores)." ) );
    return b;
  }

  /** JSON output → {"bloque": [ {campo: valor}, ... ]} en un archivo y/o en un campo. */
  static Bloque json( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = new Bloque( s.getName(), s.getStepID() );
    String[] io = paso( s, ctx, b );
    Element f = hijoEl( x, "file" );
    String pdf = aPandas( ctx, b, io[ 0 ] );
    List<Element> campos = hijos( hijoEl( x, "fields" ), "field" );
    Map<String, String> renom = new LinkedHashMap<>();
    for ( Element c : campos ) {
      renom.put( v( c, "name" ), v( c, "element" ).isEmpty() ? v( c, "name" ) : v( c, "element" ) );
    }
    ctx.importar( "import json" );
    ctx.ayudante( "_registros_json", REGISTROS_JSON );
    String bloque = v( x, "jsonBloc" ).isEmpty() ? "data" : v( x, "jsonBloc" );
    int n = entero( v( x, "nrRowsInBloc" ), 0 );
    String op = v( x, "operation_type" );
    String doc = ctx.nuevo( "json_" + CtxTrad.ident( s.getName() ) );
    b.l( doc + " = _registros_json(" + pdf + ", " + dict( renom ) + ", " + CtxTrad.lit( bloque ) + ", " + n + ")" );
    if ( !"outputvalue".equalsIgnoreCase( op ) ) {
      ctx.ayudante( "_nombre_salida", NOMBRE_SALIDA );
      String fecha = ( si( f, "add_date" ) ? "_%Y%m%d" : "" ) + ( si( f, "add_time" ) ? "_%H%M%S" : "" );
      String enc = v( x, "encoding" ).isEmpty() ? "utf-8" : v( x, "encoding" );
      b.l( "with open(_nombre_salida(" + ctx.texto( v( f, "name" ) ) + ", " + ctx.texto( v( f, "extention" ) )
        + ( fecha.isEmpty() ? "" : ", fecha=" + CtxTrad.lit( fecha ) ) + "), " + CtxTrad.lit( si( f, "append" ) ? "a"
          : "w" ) + ", encoding=" + CtxTrad.lit( enc ) + ") as _f:" );
      b.l( "    _f.write(\"\".join(json.dumps(d, ensure_ascii=False, separators=(\",\", \":\")) for d in " + doc
        + "))" );
    }
    if ( !"writetofile".equalsIgnoreCase( op ) && !v( x, "outputValue" ).isEmpty() ) {
      String campo = CtxTrad.lit( v( x, "outputValue" ) );
      if ( ctx.pandas() ) {
        b.l( io[ 1 ] + " = pd.DataFrame({" + campo + ": [json.dumps(d, ensure_ascii=False, separators=(\",\", \":\")) "
          + "for d in " + doc + "]})" );
      } else {
        b.l( io[ 1 ] + " = spark.createDataFrame([(json.dumps(d, ensure_ascii=False, separators=(\",\", \":\")),) "
          + "for d in " + doc + "], [" + campo + "])" );
      }
      b.escribeDf = io[ 0 ];
      b.aproximado( ctx.t( "La salida es una fila por bloque con el JSON en «" + v( x, "outputValue" ) + "»; PDI "
        + "además pasa los demás campos de la última fila de cada bloque.", "The output is one row per block with "
          + "the JSON in '" + v( x, "outputValue" ) + "'; PDI also passes the other fields of the last row of each "
            + "block.", "A saída é uma linha por bloco com o JSON em «" + v( x, "outputValue" )
              + "»; o PDI também "
              + "passa os outros campos da última linha de cada bloco." ) );
    }
    return b;
  }

  /** XML output → DataFrame.to_xml con el elemento raíz y el de fila (atributos o elementos). */
  static Bloque xmlSalida( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = new Bloque( s.getName(), s.getStepID() );
    String[] io = paso( s, ctx, b );
    Element f = hijoEl( x, "file" );
    String pdf = aPandas( ctx, b, io[ 0 ] );
    String[] fm = formatear( s, ctx, b, pdf, hijos( hijoEl( x, "fields" ), "field" ), "name" );
    List<String> atributos = new ArrayList<>();
    List<String> elementos = new ArrayList<>();
    Map<String, String> renom = new LinkedHashMap<>();
    for ( Element c : hijos( hijoEl( x, "fields" ), "field" ) ) {
      ( "Attribute".equalsIgnoreCase( v( c, "content_type" ) ) ? atributos : elementos ).add( v( c, "element" )
        .isEmpty() ? v( c, "name" ) : v( c, "element" ) );
      if ( !v( c, "element" ).isEmpty() && !v( c, "element" ).equals( v( c, "name" ) ) ) {
        renom.put( v( c, "name" ), v( c, "element" ) );
      }
    }
    String enc = v( x, "encoding" ).isEmpty() ? "UTF-8" : v( x, "encoding" );
    String raiz = v( x, "xml_main_element" ).isEmpty() ? "Rows" : v( x, "xml_main_element" );
    String fila = v( x, "xml_repeat_element" ).isEmpty() ? "Row" : v( x, "xml_repeat_element" );
    ctx.ayudante( "_nombre_salida", NOMBRE_SALIDA );
    String fecha = ( si( f, "add_date" ) ? "_%Y%m%d" : "" ) + ( si( f, "add_time" ) ? "_%H%M%S" : "" );
    b.l( fm[ 0 ] + ( renom.isEmpty() ? "" : ".rename(columns=" + dict( renom ) + ")" ) + ".to_xml(_nombre_salida("
      + ctx.texto( v( f, "name" ) ) + ", " + ctx.texto( v( f, "extention" ) ) + ( fecha.isEmpty() ? "" : ", fecha="
        + CtxTrad.lit( fecha ) ) + "), index=False, root_name=" + CtxTrad.lit( raiz ) + ", row_name=" + CtxTrad.lit(
          fila ) + ( atributos.isEmpty() ? "" : ", attr_cols=" + lista( atributos ) + ", elem_cols=" + lista(
            elementos ) ) + ", encoding=" + CtxTrad.lit( enc ) + ", parser=\"etree\")" );
    b.nota( ctx.t( "Mismo contenido que PDI; cambian la sangría y cómo se escriben los nulos (elemento vacío).",
      "Same content as PDI; indentation and how nulls are written (empty element) differ.", "Mesmo conteúdo que o "
        + "PDI; mudam a indentação e como os nulos são escritos (elemento vazio)." ) );
    if ( si( f, "zipped" ) || entero( v( f, "splitevery" ), 0 ) > 0 ) {
      b.aproximado( ctx.t( "Ni el zip ni partir cada N filas se traducen.", "Neither zip nor splitting every N rows "
        + "are translated.", "Nem o zip nem dividir a cada N linhas são traduzidos." ) );
    }
    return b;
  }

  /** SQL file output → un archivo con CREATE / DELETE / INSERT (ayudante _sql_inserts). */
  static Bloque sqlArchivo( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = new Bloque( s.getName(), s.getStepID() );
    String[] io = paso( s, ctx, b );
    Element f = hijoEl( x, "file" );
    String pdf = aPandas( ctx, b, io[ 0 ] );
    ctx.ayudante( "_sql_inserts", SQL_INSERTS );
    ctx.ayudante( "_nombre_salida", NOMBRE_SALIDA );
    String tabla = v( x, "schema" ).isEmpty() ? v( x, "table" ) : v( x, "schema" ) + "." + v( x, "table" );
    String fmt = v( x, "dateformat" ).isEmpty() ? "yyyy-MM-dd HH:mm:ss" : v( x, "dateformat" );
    String enc = v( x, "encoding" ).isEmpty() ? "utf-8" : v( x, "encoding" );
    String fecha = ( si( f, "add_date" ) ? "_%Y%m%d" : "" ) + ( si( f, "add_time" ) ? "_%H%M%S" : "" );
    b.l( "_sql_inserts(" + pdf + ", " + ctx.texto( tabla ) + ", _nombre_salida(" + ctx.texto( v( f, "name" ) ) + ", "
      + ctx.texto( v( f, "extention" ) ) + ( fecha.isEmpty() ? "" : ", fecha=" + CtxTrad.lit( fecha ) ) + "), crear="
      + ( si( x, "create" ) ? "True" : "False" ) + ", vaciar=" + ( si( x, "truncate" ) ? "True" : "False" )
      + ", anexar=" + ( si( f, "append" ) ? "True" : "False" ) + ", formato_fecha=" + CtxTrad.lit( fechaPy( fmt ) )
      + ", codificacion=" + CtxTrad.lit( enc ) + ")" );
    b.aproximado( ctx.t( "El SQL sale con sintaxis genérica: PDI usa el dialecto de la conexión (comillas, tipos del "
      + "CREATE TABLE).", "The SQL uses generic syntax: PDI uses the connection's dialect (quoting, CREATE TABLE "
        + "types).", "O SQL sai com sintaxe genérica: o PDI usa o dialeto da conexão (aspas, tipos do CREATE "
          + "TABLE)." ) );
    return b;
  }

  // ================================================================ bases de datos

  /** Table output → to_sql (pandas) o write JDBC (PySpark), con vaciado previo y mapeo de campos. */
  static Bloque tabla( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = new Bloque( s.getName(), s.getStepID() );
    String[] io = paso( s, ctx, b );
    DatabaseMeta db = base( s, ctx, x );
    String tabla = ctx.texto( v( x, "table" ) );
    String esq = esquema( ctx, v( x, "schema" ) );
    int commit = entero( v( x, "commit" ), 1000 );
    String df = io[ 0 ];
    if ( si( x, "specify_fields" ) ) {
      List<String> flujo = new ArrayList<>();
      Map<String, String> renom = new LinkedHashMap<>();
      for ( Element c : hijos( hijoEl( x, "fields" ), "field" ) ) {
        flujo.add( v( c, "stream_name" ) );
        if ( !v( c, "stream_name" ).equals( v( c, "column_name" ) ) ) {
          renom.put( v( c, "stream_name" ), v( c, "column_name" ) );
        }
      }
      if ( ctx.pandas() ) {
        df = df + "[" + lista( flujo ) + "]" + ( renom.isEmpty() ? "" : ".rename(columns=" + dict( renom ) + ")" );
      } else {
        StringBuilder sel = new StringBuilder();
        for ( String n : flujo ) {
          sel.append( sel.length() == 0 ? "" : ", " );
          if ( renom.containsKey( n ) ) {
            sel.append( "F.col(" ).append( CtxTrad.lit( n ) ).append( ").alias(" ).append( CtxTrad.lit( renom.get(
              n ) ) ).append( ")" );
          } else {
            sel.append( CtxTrad.lit( n ) );
          }
        }
        df = df + ".select(" + sel + ")";
      }
    }
    if ( db == null ) {
      sinConexion( s, ctx, b );
      return b;
    }
    boolean vaciar = si( x, "truncate" );
    if ( si( x, "tablename_in_field" ) && !v( x, "tablename_field" ).isEmpty() ) {
      b.manual( ctx.t( "El nombre de la tabla sale de un campo: separa las filas por ese campo y escribe cada grupo.",
        "The table name comes from a field: split the rows by that field and write each group.", "O nome da tabela "
          + "vem de um campo: separe as linhas por esse campo e escreva cada grupo." ) );
    }
    if ( ctx.pandas() ) {
      String e = ctx.conexion( db );
      String nombre = v( x, "schema" ).isEmpty() ? v( x, "table" ) : v( x, "schema" ) + "." + v( x, "table" );
      if ( vaciar ) {
        b.l( "with " + e + ".begin() as _con:" );
        b.l( "    _con.execute(text(" + ctx.texto( "DELETE FROM " + nombre ) + "))  # " + ctx.t( "vaciar la tabla "
          + "(truncate)", "empty the table (truncate)", "esvaziar a tabela (truncate)" ) );
      }
      b.l( df + ".to_sql(" + tabla + ", " + e + ( "None".equals( esq ) ? "" : ", schema=" + esq )
        + ", if_exists=\"append\", index=False, chunksize=" + commit + ")" );
    } else {
      String j = ctx.conexion( db );
      b.l( "(" + df + ".write.format(\"jdbc\").options(**" + j + ")" );
      b.l( "    .option(\"dbtable\", " + ( "None".equals( esq ) ? tabla : esq + " + \".\" + " + tabla ) + ")" );
      b.l( "    .option(\"batchsize\", " + commit + ")" + ( vaciar ? ".option(\"truncate\", True).mode(\"overwrite\")"
        : ".mode(\"append\")" ) + ".save())" );
      b.nota( ctx.t( "Spark necesita el .jar del driver JDBC (spark.jars).", "Spark needs the JDBC driver .jar "
        + "(spark.jars).", "O Spark precisa do .jar do driver JDBC (spark.jars)." ) );
    }
    if ( si( x, "ignore_errors" ) ) {
      b.aproximado( ctx.t( "«Ignorar errores de inserción» no se traduce: un error corta el programa.", "'Ignore "
        + "insert errors' is not translated: an error stops the program.", "«Ignorar erros de inserção» não é "
          + "traduzido: um erro interrompe o programa." ) );
    }
    if ( si( x, "return_keys" ) ) {
      b.aproximado( ctx.t( "Las claves generadas (return keys) no se devuelven en «" + v( x, "return_field" ) + "».",
        "Generated keys (return keys) are not returned in '" + v( x, "return_field" ) + "'.", "As chaves geradas "
          + "(return keys) não são devolvidas em «" + v( x, "return_field" ) + "»." ) );
    }
    if ( si( x, "partitioning_enabled" ) ) {
      b.aproximado( ctx.t( "El particionado por fecha de la tabla no se traduce.", "Table partitioning by date is not "
        + "translated.", "O particionamento da tabela por data não é traduzido." ) );
    }
    return b;
  }

  /** Insert / update, Update, Delete y Synchronize after merge → ayudante _sincronizar fila a fila (SQLAlchemy). */
  static Bloque insertarActualizar( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = new Bloque( s.getName(), s.getStepID() );
    String[] io = paso( s, ctx, b );
    DatabaseMeta db = base( s, ctx, x );
    if ( db == null ) {
      sinConexion( s, ctx, b );
      return b;
    }
    String id = s.getStepID();
    Element lk = hijoEl( x, "lookup" );
    StringBuilder claves = new StringBuilder( "[" );
    for ( Element k : hijos( lk, "key" ) ) {
      String cond = v( k, "condition" ).isEmpty() ? "=" : v( k, "condition" );
      claves.append( claves.length() == 1 ? "" : ", " ).append( "(" ).append( CtxTrad.lit( v( k, "field" ) ) )
        .append( ", " ).append( CtxTrad.lit( cond ) ).append( ", " ).append( CtxTrad.lit( v( k, "name" ) ) );
      if ( !v( k, "name2" ).isEmpty() ) {
        claves.append( ", " ).append( CtxTrad.lit( v( k, "name2" ) ) );
      }
      claves.append( ")" );
    }
    claves.append( "]" );
    Map<String, String> valores = new LinkedHashMap<>();
    List<String> actualizar = new ArrayList<>();
    for ( Element c : hijos( lk, "value" ) ) {
      String col = v( c, "name" );
      valores.put( col, v( c, "rename" ).isEmpty() ? col : v( c, "rename" ) );
      if ( !"N".equalsIgnoreCase( v( c, "update" ) ) ) {
        actualizar.add( col );
      }
    }
    ctx.importar( "from sqlalchemy import MetaData, Table, and_, select, text" );
    ctx.ayudante( "_nativo", NATIVO );
    ctx.ayudante( "_condicion", CONDICION );
    ctx.ayudante( "_sincronizar", SINCRONIZAR );
    String pdf = aPandas( ctx, b, io[ 0 ] );
    String modo;
    String extra = "";
    switch ( id ) {
      case "InsertUpdate":
        modo = si( x, "update_bypassed" ) ? "insertar" : "insertar_o_actualizar";
        break;
      case "Update":
        modo = "actualizar";
        extra = si( x, "error_ignored" ) ? ", sin_clave_falla=False" : "";
        if ( !v( x, "ignore_flag_field" ).isEmpty() ) {
          b.aproximado( ctx.t( "El campo de marca de «no encontrado» no se agrega.", "The 'not found' flag field is "
            + "not added.", "O campo de marca de «não encontrado» não é adicionado." ) );
        }
        break;
      case "Delete":
        modo = "borrar";
        break;
      default:
        modo = "operacion";
        extra = ", campo_operacion=" + CtxTrad.lit( v( x, "operation_order_field" ) ) + ", ops={\"insertar\": "
          + ctx.texto( v( x, "order_insert" ) ) + ", \"actualizar\": " + ctx.texto( v( x, "order_update" ) )
          + ", \"borrar\": " + ctx.texto( v( x, "order_delete" ) ) + "}";
        if ( si( x, "tablename_in_field" ) ) {
          b.manual( ctx.t( "El nombre de la tabla sale de un campo: llama al ayudante por cada grupo.", "The table "
            + "name comes from a field: call the helper for each group.", "O nome da tabela vem de um campo: chame o "
              + "ajudante para cada grupo." ) );
        }
    }
    boolean errores = CtxTrad.tieneError( s );
    String fallos = errores ? ctx.nuevo( "fallos_" + CtxTrad.ident( s.getName() ) ) : null;
    b.l( ( errores ? fallos + " = " : "" ) + "_sincronizar(" + motor( ctx, db ) + ", " + ctx.texto( v( lk,
      "table" ) ) + ", " + pdf + "," );
    b.l( "             claves=" + claves + "," );
    if ( !valores.isEmpty() ) {
      b.l( "             valores=" + dict( valores ) + "," );
    }
    if ( !actualizar.isEmpty() && !"Delete".equals( id ) ) {
      b.l( "             actualizar=" + lista( actualizar ) + "," );
    }
    b.l( "             modo=" + CtxTrad.lit( modo ) + extra + ( v( lk, "schema" ).isEmpty() ? "" : ", esquema="
      + ctx.texto( v( lk, "schema" ) ) ) + ( errores ? ", errores=True" : "" ) + ")" );
    if ( errores ) {
      filasConError( s, ctx, b, io, pdf, fallos );
    }
    b.nota( ctx.t( "Va fila a fila como PDI (buscar y luego insertar, actualizar o borrar), en una sola transacción.",
      "Goes row by row like PDI (look up, then insert, update or delete), in a single transaction.", "Vai linha a "
        + "linha como o PDI (procurar e depois inserir, atualizar ou apagar), numa só transação." ) );
    if ( ctx.spark() ) {
      b.aproximado( ctx.t( "Spark no actualiza filas por JDBC: se pasa a pandas y se escribe con SQLAlchemy (hace "
        + "falta su driver de Python).", "Spark does not update rows over JDBC: the data goes through pandas and is "
          + "written with SQLAlchemy (its Python driver is needed).", "O Spark não atualiza linhas por JDBC: passa-se "
            + "a pandas e escreve-se com SQLAlchemy (é preciso o driver Python)." ) );
    }
    return b;
  }

  /**
   * Hop de error de Insert / update, Update o Delete: las filas que la base rechazó ({índice: mensaje} de
   * _sincronizar) van al hop de error con los campos de error de PDI (código ISU001) y no siguen por la salida.
   */
  private static void filasConError( StepMeta s, CtxTrad ctx, Bloque b, String[] io, String pdf, String fallos ) {
    String err = ctx.dfError( s );
    StepErrorMeta em = s.getStepErrorMeta();
    String malas = "list(" + fallos + ")";
    String ok = pdf + ".drop(index=" + malas + ").reset_index(drop=True)";
    String errPd = ctx.pandas() ? err : ctx.nuevo( "pdf_" + CtxTrad.ident( s.getName() ) + "_error" );
    b.l( errPd + " = " + pdf + ".loc[" + malas + "].reset_index(drop=True)" );
    String[][] campos = { { em.getNrErrorsValuename(), "1" }, { em.getErrorDescriptionsValuename(), "list("
      + fallos + ".values())" }, { em.getErrorFieldsValuename(), "None" }, { em.getErrorCodesValuename(),
        "\"ISU001\"" } };
    for ( String[] c : campos ) {
      if ( c[ 0 ] != null && !c[ 0 ].trim().isEmpty() ) {
        b.l( errPd + "[" + CtxTrad.lit( c[ 0 ] ) + "] = " + c[ 1 ] );
      }
    }
    if ( ctx.pandas() ) {
      b.l( io[ 1 ] + " = " + ok + "  # " + ctx.t( "sigue lo que la base aceptó", "what the database accepted goes "
        + "on", "segue o que a base aceitou" ) );
    } else {
      b.l( err + " = spark.createDataFrame(" + errPd + ".astype(object).where(" + errPd + ".notna(), None)) if len("
        + errPd + ") else " + io[ 0 ] + ".limit(0)" );
      b.l( io[ 1 ] + " = spark.createDataFrame(" + ok + ", schema=" + io[ 0 ] + ".schema)" );
    }
    ctx.ponerSalidaError( s, err );
    b.nota( ctx.t( "Las filas que la base rechaza van al hop de error (como en PDI) con el mensaje de la base, que no "
      + "es el mismo texto que el de PDI (el de JDBC).", "Rows the database rejects go to the error hop (as in PDI) "
        + "with the database message, which is not the same text as PDI's (the JDBC one).", "As linhas que a base "
          + "rejeita vão para o hop de erro (como no PDI) com a mensagem da base, que não é o mesmo texto do PDI (o "
          + "do JDBC)." ) );
  }

  /** Dimension lookup/update → ayudante _dimension (SCD 1 y 2, o sólo búsqueda), que agrega la clave técnica. */
  static Bloque dimension( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = new Bloque( s.getName(), s.getStepID() );
    String in = ctx.entradaUnida( s, b );
    String out = ctx.df( s );
    b.escribe = si( x, "update" );
    b.escribeDf = in;
    b.vista = out;
    DatabaseMeta db = base( s, ctx, x );
    if ( db == null || in == null ) {
      b.l( out + " = " + ( in == null ? "pd.DataFrame()" : in ) );
      sinConexion( s, ctx, b );
      return b;
    }
    Element fs = hijoEl( x, "fields" );
    Map<String, String> claves = new LinkedHashMap<>();
    for ( Element k : hijos( fs, "key" ) ) {
      claves.put( v( k, "lookup" ), v( k, "name" ) );
    }
    Element fecha = hijoEl( fs, "date" );
    Map<String, String> campos = new LinkedHashMap<>();
    Map<String, String> tipos = new LinkedHashMap<>();
    for ( Element c : hijos( fs, "field" ) ) {
      String col = v( c, "lookup" ).isEmpty() ? v( c, "name" ) : v( c, "lookup" );
      campos.put( col, v( c, "name" ) );
      tipos.put( col, v( c, "update" ).isEmpty() ? "Insert" : v( c, "update" ) );
    }
    Element ret = hijoEl( fs, "return" );
    String tk = v( ret, "name" );
    String renom = v( ret, "rename" ).isEmpty() ? tk : v( ret, "rename" );
    String version = v( ret, "version" );
    int min = entero( v( x, "min_year" ), 1900 );
    int max = entero( v( x, "max_year" ), 2199 );
    ctx.importar( "from sqlalchemy import MetaData, Table, and_, func, select, text" );
    ctx.importar( "import datetime" );
    ctx.ayudante( "_nativo", NATIVO );
    ctx.ayudante( "_dimension", DIMENSION );
    String pdf = aPandas( ctx, b, in );
    String res = ctx.pandas() ? out : ctx.nuevo( "pdf_" + CtxTrad.ident( s.getName() ) + "_tk" );
    b.l( res + " = _dimension(" + motor( ctx, db ) + ", " + ctx.texto( v( x, "table" ) ) + ", " + pdf + "," );
    b.l( "    claves=" + dict( claves ) + "," );
    b.l( "    campos=" + dict( campos ) + "," );
    b.l( "    tipos=" + dict( tipos ) + "," );
    b.l( "    tk=" + CtxTrad.lit( tk ) + ", tk_salida=" + CtxTrad.lit( renom ) + ", version=" + CtxTrad.lit( version )
      + ", desde=" + CtxTrad.lit( v( fecha, "from" ) ) + ", hasta=" + CtxTrad.lit( v( fecha, "to" ) ) + "," );
    b.l( "    campo_fecha=" + ( v( fecha, "name" ).isEmpty() ? "None" : CtxTrad.lit( v( fecha, "name" ) ) )
      + ", actualizar=" + ( si( x, "update" ) ? "True" : "False" ) + ", anio_min=" + min + ", anio_max=" + max
      + ( v( x, "schema" ).isEmpty() ? "" : ", esquema=" + ctx.texto( v( x, "schema" ) ) ) + ")" );
    if ( ctx.spark() ) {
      b.l( out + " = spark.createDataFrame(" + res + ")" );
      b.aproximado( ctx.t( "Spark no actualiza filas por JDBC: la dimensión se mantiene con SQLAlchemy pasando por "
        + "pandas.", "Spark does not update rows over JDBC: the dimension is maintained with SQLAlchemy through "
          + "pandas.", "O Spark não atualiza linhas por JDBC: a dimensão é mantida com SQLAlchemy via pandas." ) );
    }
    String metodo = v( ret, "creation_method" );
    if ( "sequence".equalsIgnoreCase( metodo ) ) {
      b.aproximado( ctx.t( "La clave técnica sale de max()+1, no de la secuencia «" + v( x, "sequence" ) + "».",
        "The technical key comes from max()+1, not from sequence '" + v( x, "sequence" ) + "'.", "A chave técnica "
          + "vem de max()+1, não da sequência «" + v( x, "sequence" ) + "»." ) );
    }
    if ( si( x, "use_start_date_alternative" ) ) {
      b.aproximado( ctx.t( "La fecha de inicio alternativa no se traduce: la primera versión empieza en " + min + ".",
        "The alternative start date is not translated: the first version starts in " + min + ".", "A data de "
          + "início alternativa não é traduzida: a primeira versão começa em " + min + "." ) );
    }
    b.nota( ctx.t( "Tipos de actualización: Insert = nueva versión (SCD 2), Update = cambia la última versión, "
      + "Punch "
      + "through = cambia todas (SCD 1).", "Update types: Insert = new version (SCD 2), Update = changes the last "
        + "version, Punch through = changes all of them (SCD 1).", "Tipos de atualização: Insert = nova versão (SCD "
          + "2), Update = muda a última versão, Punch through = muda todas (SCD 1)." ) );
    return b;
  }

  /** Combination lookup/update → ayudante _combinacion: clave técnica por combinación de claves. */
  static Bloque combinacion( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = new Bloque( s.getName(), s.getStepID() );
    String in = ctx.entradaUnida( s, b );
    String out = ctx.df( s );
    b.escribe = true;
    b.escribeDf = in;
    b.vista = out;
    DatabaseMeta db = base( s, ctx, x );
    if ( db == null || in == null ) {
      b.l( out + " = " + ( in == null ? "pd.DataFrame()" : in ) );
      sinConexion( s, ctx, b );
      return b;
    }
    Element fs = hijoEl( x, "fields" );
    Map<String, String> claves = new LinkedHashMap<>();
    for ( Element k : hijos( fs, "key" ) ) {
      claves.put( v( k, "lookup" ), v( k, "name" ) );
    }
    Element ret = hijoEl( fs, "return" );
    ctx.importar( "from sqlalchemy import MetaData, Table, and_, func, select" );
    ctx.importar( "import datetime" );
    ctx.ayudante( "_nativo", NATIVO );
    ctx.ayudante( "_combinacion", COMBINACION );
    String pdf = aPandas( ctx, b, in );
    String res = ctx.pandas() ? out : ctx.nuevo( "pdf_" + CtxTrad.ident( s.getName() ) + "_tk" );
    b.l( res + " = _combinacion(" + motor( ctx, db ) + ", " + ctx.texto( v( x, "table" ) ) + ", " + pdf + ", claves="
      + dict( claves ) + "," );
    b.l( "    tk=" + CtxTrad.lit( v( ret, "name" ) ) + ", quitar_claves=" + ( si( x, "replace" ) ? "True" : "False" )
      + ( v( x, "last_update_field" ).isEmpty() ? "" : ", campo_fecha=" + CtxTrad.lit( v( x, "last_update_field" ) ) )
      + ( v( x, "schema" ).isEmpty() ? "" : ", esquema=" + ctx.texto( v( x, "schema" ) ) ) + ")" );
    if ( ctx.spark() ) {
      b.l( out + " = spark.createDataFrame(" + res + ")" );
    }
    if ( si( x, "crc" ) ) {
      b.aproximado( ctx.t( "El campo hash (CRC) no se calcula: se busca por todas las claves.", "The hash (CRC) field "
        + "is not computed: the lookup uses all the keys.", "O campo hash (CRC) não é calculado: procura-se por "
          + "todas as chaves." ) );
    }
    if ( ctx.spark() ) {
      b.aproximado( ctx.t( "Pasa por pandas y SQLAlchemy: Spark no actualiza filas por JDBC.", "Goes through pandas "
        + "and SQLAlchemy: Spark does not update rows over JDBC.", "Passa por pandas e SQLAlchemy: o Spark não "
          + "atualiza linhas por JDBC." ) );
    }
    return b;
  }

  /** Execute SQL script / Execute row SQL script → text() de SQLAlchemy, una vez o por fila. */
  static Bloque sql( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = new Bloque( s.getName(), s.getStepID() );
    String[] io = paso( s, ctx, b );
    DatabaseMeta db = base( s, ctx, x );
    if ( db == null ) {
      sinConexion( s, ctx, b );
      return b;
    }
    ctx.importar( "from sqlalchemy import text" );
    ctx.importar( "import pandas as pd" );
    ctx.ayudante( "_nativo", NATIVO );
    ctx.ayudante( "_ejecutar_sql", EJECUTAR_SQL );
    String e = motor( ctx, db );
    if ( "ExecSQLRow".equalsIgnoreCase( s.getStepID() ) ) {
      String pdf = aPandas( ctx, b, io[ 0 ] );
      b.l( "for _sql in " + pdf + "[" + CtxTrad.lit( v( x, "sql_field" ) ) + "].dropna():" );
      b.l( "    _ejecutar_sql(" + e + ", _sql" + ( si( x, "sendOneStatement" ) ? ", partir=False" : "" ) + ")" );
      if ( si( x, "sqlFromfile" ) ) {
        b.aproximado( ctx.t( "El campo trae nombres de archivo .sql: léelos con open() antes de ejecutarlos.",
          "The field holds .sql file names: read them with open() before running them.", "O campo traz nomes de "
            + "arquivo .sql: leia-os com open() antes de executá-los." ) );
      }
    } else {
      String sql = v( x, "sql" );
      String lit = si( x, "replace_variables" ) ? ctx.texto( sql ) : CtxTrad.lit( sql );
      if ( si( x, "execute_each_row" ) ) {
        List<String> params = new ArrayList<>();
        for ( Element a : hijos( hijoEl( x, "arguments" ), "argument" ) ) {
          params.add( v( a, "name" ) );
        }
        String pdf = aPandas( ctx, b, io[ 0 ] );
        b.l( "_ejecutar_sql(" + e + ", " + lit + ", filas=" + pdf + ( params.isEmpty() ? "" : ", parametros=" + lista(
          params ) ) + ( si( x, "single_statement" ) ? ", partir=False" : "" ) + ")" );
      } else {
        b.l( "_ejecutar_sql(" + e + ", " + lit + ( si( x, "single_statement" ) ? ", partir=False" : "" ) + ")" );
        b.nota( ctx.t( "PDI lo ejecuta al iniciar la transformación (antes de que pasen filas).", "PDI runs it when "
          + "the transformation starts (before any rows flow).", "O PDI executa-o ao iniciar a transformação (antes "
            + "de passarem linhas)." ) );
      }
    }
    for ( String t : new String[] { "insert_field", "update_field", "delete_field", "read_field" } ) {
      if ( !v( x, t ).isEmpty() ) {
        b.aproximado( ctx.t( "Los campos con el número de filas afectadas no se agregan.",
          "The fields with the number of affected rows are not added.",
          "Os campos com o número de linhas afetadas não são adicionados." ) );
        break;
      }
    }
    return b;
  }

  /** Write to log → print del mensaje y de las filas (o show en Spark). */
  static Bloque log( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = new Bloque( s.getName(), s.getStepID() );
    String in = ctx.entradaUnida( s, b );
    String out = ctx.df( s );
    if ( in == null ) {
      in = ctx.pandas() ? "pd.DataFrame()" : "spark.createDataFrame([], T.StructType([]))";
    }
    b.l( out + " = " + in );
    b.vista = out;
    List<String> campos = new ArrayList<>();
    for ( Element c : hijos( hijoEl( x, "fields" ), "field" ) ) {
      campos.add( v( c, "name" ) );
    }
    String sel = campos.isEmpty() ? out : ctx.pandas() ? out + "[" + lista( campos ) + "]" : out + ".select("
      + lista( campos ) + ")";
    if ( !v( x, "logmessage" ).isEmpty() ) {
      b.l( "print(" + ctx.texto( v( x, "logmessage" ) ) + ")" );
    }
    int n = si( x, "limitRows" ) ? entero( v( x, "limitRowsNumber" ), 10 ) : 0;
    if ( ctx.pandas() ) {
      b.l( "print(" + sel + ( n > 0 ? ".head(" + n + ")" : "" ) + ".to_string(index=False))" );
    } else {
      b.l( sel + ".show(" + ( n > 0 ? String.valueOf( n ) : out + ".count()" ) + ", truncate=False)" );
    }
    b.nota( ctx.t( "PDI escribe una entrada de log por fila (campo = valor); aquí se imprime una tabla.", "PDI writes "
      + "one log entry per row (field = value); here a table is printed.", "O PDI escreve uma entrada de log por "
        + "linha (campo = valor); aqui imprime-se uma tabela." ) );
    return b;
  }

  // ================================================================ ayudantes (Python)

  static final String NOMBRE_SALIDA = String.join( "\n",
    "def _nombre_salida(nombre, extension=\"\", fecha=\"\", parte=None):",
    "    \"\"\"Nombre del archivo como lo arma PDI: nombre[fecha][_parte].extensión.\"\"\"",
    "    import datetime",
    "    r = nombre + (datetime.datetime.now().strftime(fecha) if fecha else \"\")",
    "    if parte is not None:",
    "        r += f\"_{parte}\"",
    "    return r + (\".\" + extension if extension else \"\")" );

  static final String NUMERO_TEXTO = String.join( "\n",
    "def _numero_texto(serie, decimales, fijos, agrupar, sep_decimal=\".\", sep_grupo=\",\"):",
    "    \"\"\"Número a texto como una máscara de Java (#,##0.00): decimales, mínimos fijos y separadores.\"\"\"",
    "    def f(v):",
    "        if pd.isna(v):",
    "            return None",
    "        t = format(float(v), (\",\" if agrupar else \"\") + f\".{decimales}f\")",
    "        if fijos < decimales and \".\" in t:",
    "            ent, dec = t.split(\".\")",
    "            dec = dec[:fijos] + dec[fijos:].rstrip(\"0\")",
    "            t = ent + (\".\" + dec if dec else \"\")",
    "        return t.replace(\",\", \"\\0\").replace(\".\", sep_decimal).replace(\"\\0\", sep_grupo)",
    "    return serie.map(f)" );

  static final String ESCRIBIR_TEXTO = String.join( "\n",
    "def _escribir_texto(df, ruta, sep=\";\", encierro='\"', forzar=False, cabecera=True, anexar=False,",
    "                    codificacion=\"utf-8\", fin=\"\\r\\n\", textos=(), carpeta=False, vacio=True):",
    "    \"\"\"Escribe df como el Text file output de PDI: encierra los campos de texto (siempre si forzar;",
    "    si no, sólo los que llevan el separador, el encierro o un salto de línea) y pone la cabecera si el",
    "    archivo es nuevo. carpeta: crea la carpeta si falta. Sin filas, PDI no escribe ni la cabecera: deja el",
    "    archivo vacío (o no lo crea, si vacio=False).\"\"\"",
    "    if carpeta:",
    "        os.makedirs(os.path.dirname(os.path.abspath(ruta)), exist_ok=True)",
    "    if len(df) == 0:",
    "        if vacio:",
    "            open(ruta, \"a\" if anexar else \"w\", encoding=codificacion).close()",
    "        return",
    "    nuevo = not (anexar and os.path.exists(ruta) and os.path.getsize(ruta) > 0)",
    "    columnas = []",
    "    for c in df.columns:",
    "        s = df[c].astype(object).map(lambda v: \"\" if v is None or pd.isna(v) else str(v))",
    "        if c in textos and encierro:",
    "            if forzar:",
    "                poner = s != \"\"",
    "            else:",
    "                poner = s.str.contains(sep, regex=False) | s.str.contains(encierro, regex=False) \\",
    "                    | s.str.contains(\"\\n\", regex=False)",
    "            s = s.where(~poner, encierro + s.str.replace(encierro, encierro * 2, regex=False) + encierro)",
    "        columnas.append(s)",
    "    with open(ruta, \"a\" if anexar else \"w\", encoding=codificacion, newline=\"\") as f:",
    "        if cabecera and nuevo:",
    "            f.write(sep.join(encierro + str(c) + encierro if forzar and c in textos else str(c)",
    "                             for c in df.columns) + fin)",
    "        if columnas:",
    "            lineas = columnas[0]",
    "            for s in columnas[1:]:",
    "                lineas = lineas + sep + s",
    "            f.write(\"\".join(linea + fin for linea in lineas))" );

  static final String REGISTROS_JSON = String.join( "\n",
    "def _registros_json(df, campos, bloque=\"data\", por_bloque=0):",
    "    \"\"\"Las filas como el JSON output de PDI: [{bloque: [{elemento: valor}, ...]}, ...]",
    "    (N filas por bloque o todas).",
    "    Fechas como texto (yyyy/MM/dd HH:mm:ss.SSS), nulos como null.\"\"\"",
    "    d = df[list(campos)].rename(columns=campos) if campos else df",
    "    d = d.copy()",
    "    for c in d.columns:",
    "        if pd.api.types.is_datetime64_any_dtype(d[c]):",
    "            d[c] = d[c].dt.strftime(\"%Y/%m/%d %H:%M:%S.%f\").str[:-3]",
    "    filas = [{k: (None if v is None or (not isinstance(v, (list, dict)) and pd.isna(v))",
    "              else v.item() if hasattr(v, \"item\") else v) for k, v in r.items()}",
    "             for r in d.astype(object).to_dict(\"records\")]",
    "    n = por_bloque if por_bloque and por_bloque > 0 else max(len(filas), 1)",
    "    return [{bloque: filas[i:i + n]} for i in range(0, max(len(filas), 1), n)]" );

  static final String SQL_INSERTS = String.join( "\n",
    "def _sql_inserts(df, tabla, ruta, crear=False, vaciar=False, anexar=False, formato_fecha=\"%Y-%m-%d %H:%M:%S\",",
    "                 codificacion=\"utf-8\"):",
    "    \"\"\"Escribe un archivo .sql con un INSERT por fila (y el CREATE TABLE / DELETE si se piden).\"\"\"",
    "    def lit(v):",
    "        if v is None or pd.isna(v):",
    "            return \"NULL\"",
    "        if hasattr(v, \"strftime\"):",
    "            return \"'\" + v.strftime(formato_fecha) + \"'\"",
    "        if isinstance(v, (bool, np.bool_)):",
    "            return \"'Y'\" if v else \"'N'\"",
    "        if isinstance(v, (int, float, np.integer, np.floating)):",
    "            return str(v)",
    "        return \"'\" + str(v).replace(\"'\", \"''\") + \"'\"",
    "    columnas = \", \".join(str(c) for c in df.columns)",
    "    with open(ruta, \"a\" if anexar else \"w\", encoding=codificacion) as f:",
    "        if crear:",
    "            tipos = {\"i\": \"BIGINT\", \"f\": \"DOUBLE\", \"M\": \"TIMESTAMP\", \"b\": \"CHAR(1)\"}",
    "            defs = \", \".join(f\"{c} {tipos.get(df[c].dtype.kind, 'VARCHAR(255)')}\" for c in df.columns)",
    "            f.write(f\"CREATE TABLE {tabla} ({defs});\\n\")",
    "        if vaciar:",
    "            f.write(f\"DELETE FROM {tabla};\\n\")",
    "        for fila in df.astype(object).itertuples(index=False):",
    "            valores = \", \".join(lit(v) for v in fila)",
    "            f.write(f\"INSERT INTO {tabla}({columnas}) VALUES ({valores});\\n\")" );

  static final String NATIVO = String.join( "\n",
    "def _nativo(v):",
    "    \"\"\"Valor de pandas/numpy a Python puro para la base (NaN/NA/NaT a None).\"\"\"",
    "    if v is None:",
    "        return None",
    "    try:",
    "        if pd.isna(v):",
    "            return None",
    "    except (TypeError, ValueError):",
    "        pass",
    "    if hasattr(v, \"to_pydatetime\"):",
    "        return v.to_pydatetime()",
    "    return v.item() if hasattr(v, \"item\") else v" );

  static final String CONDICION = String.join( "\n",
    "def _condicion(t, claves, fila):",
    "    \"\"\"WHERE de la búsqueda: (columna, condición, campo[, campo2]) como en la tabla de claves de PDI.\"\"\"",
    "    partes = []",
    "    for clave in claves:",
    "        c, op, campo = t.c[clave[0]], clave[1].upper(), clave[2]",
    "        v = fila.get(campo)",
    "        if op == \"=\":",
    "            partes.append(c == v)",
    "        elif op in (\"<>\", \"!=\"):",
    "            partes.append(c != v)",
    "        elif op == \"<\":",
    "            partes.append(c < v)",
    "        elif op == \"<=\":",
    "            partes.append(c <= v)",
    "        elif op == \">\":",
    "            partes.append(c > v)",
    "        elif op == \">=\":",
    "            partes.append(c >= v)",
    "        elif op == \"LIKE\":",
    "            partes.append(c.like(v))",
    "        elif op == \"BETWEEN\":",
    "            partes.append(c.between(v, fila.get(clave[3])))",
    "        elif op == \"IS NULL\":",
    "            partes.append(c.is_(None))",
    "        elif op == \"IS NOT NULL\":",
    "            partes.append(c.isnot(None))",
    "        else:",
    "            raise ValueError(f\"condición no soportada: {op}\")",
    "    return and_(*partes)" );

  static final String SINCRONIZAR = String.join( "\n",
    "def _sincronizar(engine, tabla, df, claves, valores=None, actualizar=(), modo=\"insertar_o_actualizar\",",
    "                 esquema=None, sin_clave_falla=True, campo_operacion=None, ops=None, errores=False):",
    "    \"\"\"Insert / update, Update, Delete y Synchronize after merge de PDI, fila a fila en una transacción.",
    "    claves: [(columna, condición, campo[, campo2])]; valores: {columna: campo}; actualizar: columnas que se",
    "    actualizan si cambian (las demás de valores sólo se insertan). errores=True (hay hop de error): la fila",
    "    que la base rechaza se deshace sola (savepoint), sigue la siguiente y se devuelve en {índice: mensaje}.\"\"\"",
    "    t = Table(tabla, MetaData(), schema=esquema, autoload_with=engine)",
    "    valores = valores or {}",
    "    fallos = {}",
    "",
    "    def una(con, fila):",
    "        m = modo",
    "        if campo_operacion:",
    "            op = fila.get(campo_operacion)",
    "            m = next((k for k, v in ops.items() if v == op), None)",
    "            if m is None:",
    "                return",
    "        cond = _condicion(t, claves, fila)",
    "        if m == \"borrar\":",
    "            con.execute(t.delete().where(cond))",
    "            return",
    "        if m == \"insertar\":",
    "            con.execute(t.insert().values({c: fila[k] for c, k in valores.items()}))",
    "            return",
    "        cols = [t.c[c] for c in actualizar] or [t.c[claves[0][0]]]",
    "        actual = con.execute(select(*cols).where(cond)).first()",
    "        if actual is None:",
    "            if m == \"actualizar\":",
    "                if sin_clave_falla:",
    "                    raise ValueError(f\"{tabla}: no se encontró la fila a actualizar: {fila}\")",
    "                return",
    "            con.execute(t.insert().values({c: fila[k] for c, k in valores.items()}))",
    "        elif actualizar and any(actual[i] != fila[valores[c]] for i, c in enumerate(actualizar)):",
    "            con.execute(t.update().where(cond).values({c: fila[valores[c]] for c in actualizar}))",
    "",
    "    with engine.begin() as con:",
    "        for i, r in zip(df.index, df.astype(object).to_dict(\"records\")):",
    "            fila = {k: _nativo(v) for k, v in r.items()}",
    "            if not errores:",
    "                una(con, fila)",
    "                continue",
    "            try:",
    "                with con.begin_nested():",
    "                    una(con, fila)",
    "            except Exception as e:  # lo que la base rechaza va al hop de error, como en PDI",
    "                fallos[i] = f\"{type(e).__name__}: {getattr(e, 'orig', None) or e}\"",
    "    return fallos" );

  static final String DIMENSION = String.join( "\n",
    "def _dimension(engine, tabla, df, claves, campos, tipos, tk, tk_salida, version, desde, hasta, campo_fecha=None,",
    "               actualizar=True, anio_min=1900, anio_max=2199, esquema=None):",
    "    \"\"\"Dimension lookup/update de PDI (dimensión lenta). Por cada fila busca la versión vigente en la",
    "    fecha de la fila (desde <= fecha < hasta) por las claves naturales y devuelve su clave técnica. Si",
    "    actualizar: inserta la clave nueva (versión 1), y si cambió un campo «Insert» cierra la versión y abre",
    "    otra (SCD 2); los «Update»",
    "    cambian la última versión y los «Punch through» todas (SCD 1). claves y campos: {columna: campo}.\"\"\"",
    "    t = Table(tabla, MetaData(), schema=esquema, autoload_with=engine)",
    "    minimo = datetime.datetime(anio_min, 1, 1)",
    "    maximo = datetime.datetime(anio_max, 12, 31, 23, 59, 59, 999000)",
    "    ahora = datetime.datetime.now()",
    "    salida = []",
    "    with engine.begin() as con:",
    "        if actualizar and con.execute(select(t.c[tk]).where(t.c[tk] == 0)).first() is None:",
    "            con.execute(t.insert().values({tk: 0, version: 1}))  # fila «desconocido», como PDI",
    "        for r in df.astype(object).to_dict(\"records\"):",
    "            fila = {k: _nativo(v) for k, v in r.items()}",
    "            fecha = fila.get(campo_fecha) if campo_fecha else None",
    "            fecha = fecha or ahora",
    "            nat = and_(*[t.c[c] == fila[k] for c, k in claves.items()])",
    "            vigente = and_(nat, t.c[desde] <= fecha, t.c[hasta] > fecha)",
    "            actual = con.execute(select(t).where(vigente)).mappings().first()",
    "            if not actualizar:",
    "                salida.append(actual[tk] if actual is not None else 0)",
    "                continue",
    "            valores = {c: fila[k] for c, k in campos.items()}",
    "            if actual is None:",
    "                nueva = con.execute(select(func.coalesce(func.max(t.c[tk]), 0) + 1)).scalar()",
    "                con.execute(t.insert().values({tk: nueva, version: 1, desde: minimo, hasta: maximo,",
    "                                               **{c: fila[k] for c, k in claves.items()}, **valores}))",
    "                salida.append(nueva)",
    "                continue",
    "            cambia = lambda tipo: [c for c in campos",
    "                                   if tipos.get(c, \"Insert\") == tipo and actual[c] != valores[c]]",
    "            if cambia(\"Insert\"):",
    "                nueva = con.execute(select(func.coalesce(func.max(t.c[tk]), 0) + 1)).scalar()",
    "                con.execute(t.update().where(t.c[tk] == actual[tk]).values({hasta: fecha}))",
    "                con.execute(t.insert().values({tk: nueva, version: actual[version] + 1, desde: fecha,",
    "                                               hasta: maximo,",
    "                                               **{c: fila[k] for c, k in claves.items()}, **valores}))",
    "                salida.append(nueva)",
    "                continue",
    "            if cambia(\"Update\"):",
    "                cambios = {c: valores[c] for c in cambia(\"Update\")}",
    "                con.execute(t.update().where(t.c[tk] == actual[tk]).values(cambios))",
    "            punch = cambia(\"Punch through\")",
    "            if punch:",
    "                con.execute(t.update().where(nat).values({c: valores[c] for c in punch}))",
    "            salida.append(actual[tk])",
    "    r = df.copy()",
    "    r[tk_salida] = pd.array(salida, dtype=\"Int64\")",
    "    return r" );

  static final String COMBINACION = String.join( "\n",
    "def _combinacion(engine, tabla, df, claves, tk, quitar_claves=False, campo_fecha=None, esquema=None):",
    "    \"\"\"Combination lookup/update de PDI: una clave técnica por cada combinación de claves (columna: campo);",
    "    si la combinación no está en la tabla, la inserta con max()+1.\"\"\"",
    "    t = Table(tabla, MetaData(), schema=esquema, autoload_with=engine)",
    "    vistas = {}",
    "    salida = []",
    "    with engine.begin() as con:",
    "        for r in df.astype(object).to_dict(\"records\"):",
    "            valores = {c: _nativo(r[k]) for c, k in claves.items()}",
    "            llave = tuple(valores.values())",
    "            if llave not in vistas:",
    "                cond = and_(*[t.c[c].is_(None) if v is None else t.c[c] == v for c, v in valores.items()])",
    "                encontrada = con.execute(select(t.c[tk]).where(cond)).scalar()",
    "                if encontrada is None:",
    "                    encontrada = con.execute(select(func.coalesce(func.max(t.c[tk]), 0) + 1)).scalar()",
    "                    extra = {campo_fecha: datetime.datetime.now()} if campo_fecha else {}",
    "                    con.execute(t.insert().values({tk: encontrada, **valores, **extra}))",
    "                vistas[llave] = encontrada",
    "            salida.append(vistas[llave])",
    "    r = df.drop(columns=list(claves.values())) if quitar_claves else df.copy()",
    "    r[tk] = pd.array(salida, dtype=\"Int64\")",
    "    return r" );

  static final String EJECUTAR_SQL = String.join( "\n",
    "def _ejecutar_sql(engine, sql, filas=None, parametros=(), partir=True):",
    "    \"\"\"Ejecuta un script SQL (sentencias separadas por ;). Con filas: una vez por fila, con los ? reemplazados",
    "    por los campos de parametros en orden.\"\"\"",
    "    import re",
    "    sentencias = [s for s in (sql.split(\";\") if partir else [sql]) if s.strip()]",
    "    n = [0]",
    "    def marca(_):",
    "        n[0] += 1",
    "        return f\":p{n[0]}\"",
    "    sentencias = [re.sub(r\"\\?\", marca, s) for s in sentencias]",
    "    with engine.begin() as con:",
    "        lotes = [{}] if filas is None else filas.astype(object).to_dict(\"records\")",
    "        for fila in lotes:",
    "            valores = {f\"p{i + 1}\": _nativo(fila.get(p)) for i, p in enumerate(parametros)}",
    "            for s in sentencias:",
    "                con.execute(text(s), {k: v for k, v in valores.items() if f\":{k}\" in s})" );

  static final String ENGINE_JDBC = String.join( "\n",
    "def _engine_jdbc(jdbc):",
    "    \"\"\"Engine de SQLAlchemy a partir de las opciones JDBC de Spark, para lo que Spark no hace (actualizar o",
    "    borrar filas, ejecutar SQL). Hace falta el driver de Python de la base (pymysql, psycopg2...).\"\"\"",
    "    import re",
    "    from sqlalchemy.engine import URL",
    "    url = jdbc[\"url\"]",
    "    if url.startswith(\"jdbc:sqlite:\"):",
    "        ruta = url[len(\"jdbc:sqlite:\"):]",
    "        if ruta.startswith(\"file:\"):",
    "            from urllib.parse import unquote, urlparse",
    "            ruta = unquote(urlparse(ruta).path)",
    "            ruta = ruta[1:] if re.match(r\"/[A-Za-z]:\", ruta) else ruta",
    "        return create_engine(\"sqlite:///\" + ruta)",
    "    m = re.match(r\"jdbc:(\\w+)://([^:/;]+)(?::(\\d+))?(?:/|;databaseName=)([^?;]*)\", url)",
    "    dialectos = {\"mysql\": \"mysql+pymysql\", \"mariadb\": \"mysql+pymysql\",",
    "                 \"postgresql\": \"postgresql+psycopg2\",",
    "                 \"sqlserver\": \"mssql+pyodbc\"}",
    "    if not m or m.group(1) not in dialectos:",
    "        raise ValueError(\"Completa la URL de SQLAlchemy para \" + url)",
    "    return create_engine(URL.create(dialectos[m.group(1)], username=jdbc.get(\"user\"),",
    "                                    password=jdbc.get(\"password\"),",
    "                                    host=m.group(2), port=int(m.group(3)) if m.group(3) else None,",
    "                                    database=m.group(4)))" );
}
