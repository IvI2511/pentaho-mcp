package io.github.ivi2511.pentahomcp.traductor;

import java.util.ArrayList;
import java.util.List;

import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Traductores de los steps que leen datos (archivos, bases, filas generadas, sistema). */
final class TradEntradas {

  private TradEntradas() {
  }

  static void registrar() {
    Registro.step( TradEntradas::csv, "CsvInput" );
    Registro.step( TradEntradas::textoArchivo, "TextFileInput" );
    Registro.step( TradEntradas::fijo, "FixedInput" );
    Registro.step( TradEntradas::grilla, "DataGrid" );
    Registro.step( TradEntradas::generar, "RowGenerator" );
    Registro.step( TradEntradas::tabla, "TableInput" );
    Registro.step( TradEntradas::excel, "ExcelInput" );
    Registro.step( TradEntradas::json, "JsonInput" );
    Registro.step( TradEntradas::xml, "getXMLData" );
    Registro.step( TradEntradas::variables, "GetVariable" );
    Registro.step( TradEntradas::sistema, "SystemInfo" );
    Registro.step( TradEntradas::aleatorio, "RandomValue" );
    Registro.step( TradEntradas::nombresArchivo, "GetFileNames" );
    Registro.step( TradEntradas::cargarArchivo, "LoadFileInput" );
  }

  // ================================================================ utilidades

  private static String v( Element e, String tag ) {
    return CtxTrad.hijo( e, tag );
  }

  private static boolean si( Element e, String tag ) {
    return "Y".equalsIgnoreCase( v( e, tag ).trim() );
  }

  private static int entero( String s, int def ) {
    try {
      return Integer.parseInt( s.trim() );
    } catch ( RuntimeException e ) {
      return def;
    }
  }

  /** Hijos directos con ese tag. */
  static List<Element> hijos( Element e, String tag ) {
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

  private static Element uno( Element e, String tag ) {
    List<Element> l = hijos( e, tag );
    return l.isEmpty() ? null : l.get( 0 );
  }

  /** Los &lt;field&gt; de &lt;fields&gt;. */
  static List<Element> campos( Element x ) {
    return hijos( uno( x, "fields" ), "field" );
  }

  private static String nombres( List<Element> campos, String tag ) {
    StringBuilder b = new StringBuilder( "[" );
    for ( Element c : campos ) {
      b.append( b.length() == 1 ? "" : ", " ).append( CtxTrad.lit( v( c, tag ) ) );
    }
    return b.append( "]" ).toString();
  }

  private static Bloque nuevo( StepMeta s, CtxTrad ctx ) {
    Bloque b = new Bloque( s.getName(), s.getStepID() );
    b.lee = true;
    b.vista = ctx.df( s );
    return b;
  }

  /** Expresión de la ruta (con ${VAR} como VARS); la carpeta del .ktr queda como AQUI. */
  private static String ruta( CtxTrad ctx, String r ) {
    return ctx.texto( r );
  }

  /**
   * Líneas que convierten las columnas de texto del DataFrame a los tipos de PDI con sus máscaras: Integer → Int64 /
   * long, Number → float / double, BigNumber → Decimal, Date → datetime64 / timestamp, Boolean. Recorte (trim) antes.
   */
  static void tipar( CtxTrad ctx, Bloque b, String df, List<Element> campos, String tagNombre ) {
    List<String> spark = new ArrayList<>();
    for ( Element c : campos ) {
      String n = v( c, tagNombre );
      String tipo = v( c, "type" );
      String fmt = v( c, "format" );
      String dec = v( c, "decimal" );
      String grp = v( c, "group" );
      String trim = v( c, "trim_type" ).toLowerCase();
      String col = df + "[" + CtxTrad.lit( n ) + "]";
      if ( ctx.pandas() ) {
        String s = col;
        if ( trim.equals( "both" ) ) {
          s += ".str.strip()";
        } else if ( trim.equals( "left" ) ) {
          s += ".str.lstrip()";
        } else if ( trim.equals( "right" ) ) {
          s += ".str.rstrip()";
        }
        String num = s;
        if ( !grp.isEmpty() && ( tipo.equals( "Integer" ) || tipo.equals( "Number" ) || tipo.equals( "BigNumber" ) ) ) {
          num += ".str.replace(" + CtxTrad.lit( grp ) + ", \"\", regex=False)";
        }
        if ( !dec.isEmpty() && !dec.equals( "." ) && !tipo.equals( "Integer" ) && ( tipo.equals( "Number" )
          || tipo.equals( "BigNumber" ) ) ) {
          num += ".str.replace(" + CtxTrad.lit( dec ) + ", \".\", regex=False)";
        }
        String e;
        switch ( tipo ) {
          case "Integer": e = "pd.to_numeric(" + num + ").astype(\"Int64\")"; break;
          case "Number": e = "pd.to_numeric(" + num + ").astype(\"float64\")"; break;
          case "BigNumber":
            ctx.importar( "from decimal import Decimal" );
            e = num + ".map(Decimal, na_action=\"ignore\")";
            break;
          case "Date": case "Timestamp":
            e = "pd.to_datetime(" + s + ( fmt.isEmpty() ? "" : ", format=" + CtxTrad.lit( TradSalidas.fechaPy( fmt ) ) )
              + ")";
            break;
          case "Boolean":
            e = s + ".str.upper().isin([\"Y\", \"YES\", \"TRUE\", \"1\"]).astype(\"boolean\").mask(" + col + ".isna())";
            break;
          default:
            e = s.equals( col ) ? null : s;
        }
        if ( e != null ) {
          b.l( col + " = " + e );
        }
      } else {
        String s = "F.col(" + CtxTrad.lit( n ) + ")";
        if ( trim.equals( "both" ) ) {
          s = "F.trim(" + s + ")";
        } else if ( trim.equals( "left" ) ) {
          s = "F.ltrim(" + s + ")";
        } else if ( trim.equals( "right" ) ) {
          s = "F.rtrim(" + s + ")";
        }
        String num = s;
        if ( !grp.isEmpty() && ( tipo.equals( "Integer" ) || tipo.equals( "Number" ) || tipo.equals( "BigNumber" ) ) ) {
          num = "F.regexp_replace(" + num + ", " + CtxTrad.lit( "\\Q" + grp + "\\E" ) + ", \"\")";
        }
        if ( !dec.isEmpty() && !dec.equals( "." ) && ( tipo.equals( "Number" ) || tipo.equals( "BigNumber" ) ) ) {
          num = "F.regexp_replace(" + num + ", " + CtxTrad.lit( "[" + dec + "]" ) + ", \".\")";
        }
        String e;
        switch ( tipo ) {
          case "Integer": e = num + ".cast(\"long\")"; break;
          case "Number": e = num + ".cast(\"double\")"; break;
          case "BigNumber": e = num + ".cast(\"decimal(38,18)\")"; break;
          case "Date": case "Timestamp":
            e = fmt.isEmpty() ? s + ".cast(\"timestamp\")" : "F.to_timestamp(" + s + ", " + CtxTrad.lit( fmt ) + ")";
            break;
          case "Boolean":
            e = "F.when(" + s + ".isNotNull(), F.upper(" + s + ").isin(\"Y\", \"YES\", \"TRUE\", \"1\"))";
            break;
          default:
            e = s.startsWith( "F.col(" ) ? null : s;
        }
        if ( e != null ) {
          spark.add( "    " + CtxTrad.lit( n ) + ": " + e + "," );
        }
      }
    }
    if ( !spark.isEmpty() ) {
      b.l( df + " = " + df + ".withColumns({" );
      for ( String l : spark ) {
        b.l( l );
      }
      b.l( "})" );
    }
  }

  /** Esquema Spark de todas las columnas como texto. */
  private static String esquemaTexto( List<Element> campos, String tag ) {
    StringBuilder b = new StringBuilder();
    for ( Element c : campos ) {
      b.append( b.length() == 0 ? "" : ", " ).append( '`' ).append( v( c, tag ).replace( "`", "``" ) ).append(
        "` STRING" );
    }
    return CtxTrad.lit( b.toString() );
  }

  /** Columna con el número de fila (1, 2, ...) y la del nombre de archivo, si se piden. */
  private static void extras( CtxTrad ctx, Bloque b, String df, String campoArchivo, String archivo, String campoFila ) {
    if ( campoArchivo != null && !campoArchivo.isEmpty() ) {
      b.l( ctx.pandas() ? df + "[" + CtxTrad.lit( campoArchivo ) + "] = " + archivo : df + " = " + df
        + ".withColumn(" + CtxTrad.lit( campoArchivo ) + ", F.lit(" + archivo + "))" );
    }
    if ( campoFila != null && !campoFila.isEmpty() ) {
      if ( ctx.pandas() ) {
        b.l( df + "[" + CtxTrad.lit( campoFila ) + "] = np.arange(1, len(" + df + ") + 1)" );
      } else {
        b.l( df + " = " + df + ".withColumn(" + CtxTrad.lit( campoFila ) + ", F.row_number().over(Window.orderBy("
          + "F.monotonically_increasing_id())))" );
      }
    }
  }

  private static void comun( CtxTrad ctx, Bloque b ) {
    b.nota( ctx.t( "Como en PDI, el texto vacío se lee como nulo.", "As in PDI, empty text is read as null.",
      "Como no PDI, o texto vazio é lido como nulo." ) );
  }

  // ================================================================ CSV y texto

  /** CSV file input → pd.read_csv / spark.read.csv con separador, comillas, encabezado, codificación y tipos. */
  static Bloque csv( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = nuevo( s, ctx );
    String df = ctx.df( s );
    List<Element> cs = campos( x );
    String archivo = ctx.nuevo( "archivo_" + CtxTrad.ident( s.getName() ) );
    String campoArchivoEntrada = v( x, "filename_field" );
    String in = ctx.entrada( s );
    if ( in != null && !campoArchivoEntrada.isEmpty() ) {
      b.l( archivo + " = " + ( ctx.pandas() ? in + "[" + CtxTrad.lit( campoArchivoEntrada ) + "].iloc[0]" : in
        + ".first()[" + CtxTrad.lit( campoArchivoEntrada ) + "]" ) );
      b.aproximado( ctx.t( "El nombre del archivo viene de un step anterior: se lee sólo el primero.",
        "The file name comes from a previous step: only the first one is read.",
        "O nome do arquivo vem de um step anterior: só o primeiro é lido." ) );
    } else {
      b.l( archivo + " = " + ruta( ctx, v( x, "filename" ) ) );
    }
    String sep = v( x, "separator" ).isEmpty() ? "," : v( x, "separator" );
    String enc = v( x, "encoding" ).isEmpty() ? "utf-8" : v( x, "encoding" );
    String encl = v( x, "enclosure" );
    leerSeparado( ctx, b, df, archivo, sep, encl, si( x, "header" ) ? 1 : 0, 0, enc, cs, -1 );
    tipar( ctx, b, df, cs, "name" );
    extras( ctx, b, df, si( x, "include_filename" ) ? v( x, "filename_field" ) : null, archivo, v( x,
      "rownum_field" ) );
    if ( si( x, "lazy_conversion" ) ) {
      b.nota( ctx.t( "La conversión perezosa (lazy conversion) de PDI no aplica: se convierte al leer.",
        "PDI lazy conversion does not apply: values are converted on read.",
        "A conversão preguiçosa (lazy conversion) do PDI não se aplica: converte-se ao ler." ) );
    }
    comun( ctx, b );
    return b;
  }

  /** read_csv / spark.read.csv con todas las columnas como texto y los nombres de PDI. */
  private static void leerSeparado( CtxTrad ctx, Bloque b, String df, String archivo, String sep, String encl,
    int cabeceras, int pies, String enc, List<Element> cs, int limite ) {
    if ( ctx.pandas() ) {
      StringBuilder l = new StringBuilder( df + " = pd.read_csv(" + archivo + ", sep=" + ctx.texto( sep ) );
      if ( encl.isEmpty() ) {
        ctx.importar( "import csv" );
        l.append( ", quoting=csv.QUOTE_NONE" );
      } else if ( !encl.equals( "\"" ) ) {
        l.append( ", quotechar=" ).append( CtxTrad.lit( encl ) );
      }
      l.append( ", header=None" );
      if ( cabeceras > 0 ) {
        l.append( ", skiprows=" ).append( cabeceras );
      }
      if ( pies > 0 ) {
        l.append( ", skipfooter=" ).append( pies ).append( ", engine=\"python\"" );
      }
      if ( !cs.isEmpty() ) {
        l.append( ", names=" ).append( nombres( cs, "name" ) ).append( ", usecols=range(" ).append( cs.size() )
          .append( ")" );
      }
      if ( limite > 0 ) {
        l.append( ", nrows=" ).append( limite );
      }
      b.l( l.append( ",\n    encoding=" ).append( CtxTrad.lit( enc ) ).append( ", dtype=str, keep_default_na=False, "
        + "na_values=[\"\"])" ).toString() );
    } else {
      StringBuilder l = new StringBuilder( df + " = spark.read.csv(" + archivo + ", sep=" + ctx.texto( sep )
        + ", quote=" + CtxTrad.lit( encl ) + ", header=" + ( cabeceras > 0 ? "True" : "False" ) + ", encoding="
        + CtxTrad.lit( enc ) );
      if ( !cs.isEmpty() ) {
        l.append( ",\n    schema=" ).append( esquemaTexto( cs, "name" ) );
      }
      b.l( l.append( ")" ).toString() );
      if ( limite > 0 ) {
        b.l( df + " = " + df + ".limit(" + limite + ")" );
      }
      if ( cabeceras > 1 || pies > 0 ) {
        b.aproximado( ctx.t( "Spark sólo salta una línea de encabezado y ninguna de pie: ajusta el archivo o "
          + "filtra esas filas.", "Spark skips only one header line and no footer lines: adjust the file or filter "
            + "those rows.", "O Spark só pula uma linha de cabeçalho e nenhuma de rodapé: ajuste o arquivo ou "
              + "filtre essas linhas." ) );
      }
    }
  }

  /** Text file input: uno o varios archivos (con máscara), CSV o ancho fijo, o los nombres desde un step anterior. */
  static Bloque textoArchivo( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = nuevo( s, ctx );
    String df = ctx.df( s );
    List<Element> cs = campos( x );
    String archivos = ctx.nuevo( "archivos_" + CtxTrad.ident( s.getName() ) );
    listaArchivos( ctx, b, s, x, archivos );
    Element f = uno( x, "file" );
    boolean fijo = "Fixed".equalsIgnoreCase( v( f, "type" ) );
    String sep = v( x, "separator" ).isEmpty() ? ";" : v( x, "separator" );
    String enc = v( x, "encoding" ).isEmpty() ? "utf-8" : v( x, "encoding" );
    int cab = si( x, "header" ) ? Math.max( 1, entero( v( x, "nr_headerlines" ), 1 ) ) : 0;
    int pie = si( x, "footer" ) ? Math.max( 1, entero( v( x, "nr_footerlines" ), 1 ) ) : 0;
    int limite = entero( v( x, "limit" ), 0 );
    String uno = ctx.nuevo( "una_" + CtxTrad.ident( s.getName() ) );
    String campoArchivo = si( x, "include" ) ? v( x, "include_field" ) : null;
    String campoFila = si( x, "rownum" ) ? v( x, "rownum_field" ) : null;
    if ( ctx.pandas() ) {
      b.l( "partes = []" );
      b.l( "for " + uno + " in " + archivos + ":" );
      Bloque t = new Bloque( s.getName(), s.getStepID() );
      if ( fijo ) {
        leerFijo( ctx, t, "parte", uno, cs, cab, enc );
      } else {
        leerSeparado( ctx, t, "parte", uno, sep, v( x, "enclosure" ), cab, pie, enc, cs, limite );
      }
      if ( campoArchivo != null && !campoArchivo.isEmpty() ) {
        t.l( "parte[" + CtxTrad.lit( campoArchivo ) + "] = " + uno );
      }
      t.l( "partes.append(parte)" );
      for ( String l : t.codigo ) {
        b.l( "    " + l.replace( "\n", "\n    " ) );
      }
      b.l( df + " = pd.concat(partes, ignore_index=True)" );
      if ( limite > 0 ) {
        b.l( df + " = " + df + ".head(" + limite + ")" );
      }
    } else {
      if ( fijo ) {
        leerFijo( ctx, b, df, archivos, cs, cab, enc );
      } else {
        leerSeparado( ctx, b, df, archivos, sep, v( x, "enclosure" ), cab, pie, enc, cs, limite );
      }
      if ( campoArchivo != null && !campoArchivo.isEmpty() ) {
        b.l( df + " = " + df + ".withColumn(" + CtxTrad.lit( campoArchivo ) + ", F.input_file_name())" );
        b.aproximado( ctx.t( "input_file_name() da la URI completa (file:/...).", "input_file_name() gives the full "
          + "URI (file:/...).", "input_file_name() dá a URI completa (file:/...)." ) );
      }
    }
    tipar( ctx, b, df, cs, "name" );
    extras( ctx, b, df, null, null, campoFila );
    for ( Element c : cs ) {
      String nullif = v( c, "nullif" );
      String ifnull = v( c, "ifnull" );
      String n = CtxTrad.lit( v( c, "name" ) );
      if ( !ifnull.isEmpty() ) {
        b.l( ctx.pandas() ? df + "[" + n + "] = " + df + "[" + n + "].fillna(" + CtxTrad.lit( ifnull ) + ")" : df
          + " = " + df + ".fillna({" + n + ": " + CtxTrad.lit( ifnull ) + "})" );
        if ( !"String".equals( v( c, "type" ) ) ) {
          b.aproximado( ctx.t( "«Si es nulo» en un campo no texto: revisa el tipo del valor.", "'If null' on a "
            + "non-text field: check the value type.", "«Se nulo» num campo não texto: revise o tipo do valor." ) );
        }
      }
      if ( !nullif.isEmpty() ) {
        b.aproximado( ctx.t( "«Nulo si» del campo " + n + " no se traduce.", "'Null if' of field " + n + " is not "
          + "translated.", "«Nulo se» do campo " + n + " não é traduzido." ) );
      }
    }
    if ( si( x, "line_wrapped" ) || si( x, "layout_paged" ) || uno( x, "filters" ) != null && !hijos( uno( x,
      "filters" ), "filter" ).isEmpty() ) {
      b.aproximado( ctx.t( "Líneas partidas, paginado y filtros de líneas no se traducen.", "Wrapped lines, paged "
        + "layout and line filters are not translated.", "Linhas quebradas, paginação e filtros de linhas não são "
          + "traduzidos." ) );
    }
    comun( ctx, b );
    return b;
  }

  /** La lista de archivos: rutas fijas, carpeta + máscara (regex de PDI) o un campo de un step anterior. */
  private static void listaArchivos( CtxTrad ctx, Bloque b, StepMeta s, Element x, String var ) {
    String in = ctx.entrada( s );
    if ( si( x, "accept_filenames" ) && in != null ) {
      String campo = CtxTrad.lit( v( x, "accept_field" ) );
      b.l( var + " = " + ( ctx.pandas() ? in + "[" + campo + "].tolist()" : "[r[" + campo + "] for r in " + in
        + ".select(" + campo + ").collect()]" ) );
      return;
    }
    List<String> partes = new ArrayList<>();
    List<Element> nombres = hijos( uno( x, "file" ), "name" );
    List<Element> mascaras = hijos( uno( x, "file" ), "filemask" );
    List<Element> excluir = hijos( uno( x, "file" ), "exclude_filemask" );
    boolean conMascara = false;
    for ( int i = 0; i < nombres.size(); i++ ) {
      String n = ruta( ctx, nombres.get( i ).getTextContent() );
      String m = i < mascaras.size() ? mascaras.get( i ).getTextContent() : "";
      String ex = i < excluir.size() ? excluir.get( i ).getTextContent() : "";
      if ( m.isEmpty() ) {
        partes.add( "[" + n + "]" );
      } else {
        conMascara = true;
        partes.add( "_archivos(" + n + ", " + CtxTrad.lit( m ) + ( ex.isEmpty() ? "" : ", " + CtxTrad.lit( ex ) )
          + ")" );
      }
    }
    if ( conMascara ) {
      ctx.importar( "import re" );
      ctx.ayudante( "_archivos", String.join( "\n",
        "def _archivos(carpeta, mascara, excluir=None):",
        "    \"\"\"" + ctx.t( "Archivos de la carpeta cuyo nombre cumple la expresión regular (como en PDI), ordenados.",
          "Files in the folder whose name matches the regular expression (as in PDI), sorted.",
          "Arquivos da pasta cujo nome casa com a expressão regular (como no PDI), ordenados." ) + "\"\"\"",
        "    return sorted(os.path.join(carpeta, n) for n in os.listdir(carpeta)",
        "                  if re.fullmatch(mascara, n) and not (excluir and re.fullmatch(excluir, n))",
        "                  and os.path.isfile(os.path.join(carpeta, n)))" ) );
    }
    b.l( var + " = " + ( partes.isEmpty() ? "[]" : String.join( " + ", partes ) ) );
  }

  /** read_fwf con las posiciones de los campos (o un substring por campo en Spark). */
  private static void leerFijo( CtxTrad ctx, Bloque b, String df, String archivo, List<Element> cs, int cab,
    String enc ) {
    StringBuilder specs = new StringBuilder( "[" );
    int pos = 0;
    for ( Element c : cs ) {
      int p = v( c, "position" ).isEmpty() ? pos : entero( v( c, "position" ), pos );
      int w = entero( v( c, "width" ).isEmpty() ? v( c, "length" ) : v( c, "width" ), 1 );
      specs.append( specs.length() == 1 ? "" : ", " ).append( "(" ).append( p ).append( ", " ).append( p + w )
        .append( ")" );
      pos = p + w;
    }
    specs.append( "]" );
    if ( ctx.pandas() ) {
      b.l( df + " = pd.read_fwf(" + archivo + ", colspecs=" + specs + ", names=" + nombres( cs, "name" )
        + ", header=None" + ( cab > 0 ? ", skiprows=" + cab : "" ) + ",\n    encoding=" + CtxTrad.lit( enc )
        + ", dtype=str, keep_default_na=False, na_values=[\"\"], delimiter=\"\\0\")" );
    } else {
      b.l( df + " = spark.read.text(" + archivo + ")" );
      StringBuilder sel = new StringBuilder();
      pos = 0;
      for ( Element c : cs ) {
        int p = v( c, "position" ).isEmpty() ? pos : entero( v( c, "position" ), pos );
        int w = entero( v( c, "width" ).isEmpty() ? v( c, "length" ) : v( c, "width" ), 1 );
        sel.append( "\n    F.nullif(F.trim(F.substring(\"value\", " ).append( p + 1 ).append( ", " ).append( w )
          .append( ")), F.lit(\"\")).alias(" ).append( CtxTrad.lit( v( c, "name" ) ) ).append( ")," );
        pos = p + w;
      }
      b.l( df + " = " + df + ".select(" + sel + "\n)" );
      if ( cab > 0 ) {
        b.aproximado( ctx.t( "Spark no salta el encabezado de un archivo de ancho fijo: filtra esa fila.",
          "Spark does not skip the header of a fixed-width file: filter that row.",
          "O Spark não pula o cabeçalho de um arquivo de largura fixa: filtre essa linha." ) );
      }
    }
  }

  /** Fixed file input → pd.read_fwf con los anchos de los campos. */
  static Bloque fijo( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = nuevo( s, ctx );
    String df = ctx.df( s );
    List<Element> cs = campos( x );
    String enc = v( x, "encoding" ).isEmpty() ? "utf-8" : v( x, "encoding" );
    leerFijo( ctx, b, df, ruta( ctx, v( x, "filename" ) ), cs, si( x, "header" ) ? 1 : 0, enc );
    if ( ctx.pandas() ) {
      b.nota( ctx.t( "read_fwf recorta los espacios de cada campo.", "read_fwf trims the spaces of each field.",
        "read_fwf corta os espaços de cada campo." ) );
    }
    tipar( ctx, b, df, cs, "name" );
    comun( ctx, b );
    return b;
  }

  // ================================================================ filas literales y generadas

  /** Data grid → un DataFrame con los valores escritos (como texto) convertidos a sus tipos. */
  static Bloque grilla( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = nuevo( s, ctx );
    b.lee = false;
    String df = ctx.df( s );
    List<Element> cs = campos( x );
    List<String> filas = new ArrayList<>();
    for ( Element l : hijos( uno( x, "data" ), "line" ) ) {
      List<Element> items = hijos( l, "item" );
      StringBuilder f = new StringBuilder( ctx.pandas() ? "[" : "(" );
      for ( int i = 0; i < cs.size(); i++ ) {
        String val = i < items.size() ? items.get( i ).getTextContent() : "";
        boolean vacio = si( cs.get( i ), "set_empty_string" );
        f.append( i == 0 ? "" : ", " ).append( val.isEmpty() ? ( vacio ? "\"\"" : "None" ) : CtxTrad.lit( val ) );
      }
      filas.add( f.append( ctx.pandas() ? "]" : cs.size() == 1 ? ",)" : ")" ).toString() );
    }
    String datos = filas.isEmpty() ? "[]" : "[\n    " + String.join( ",\n    ", filas ) + ",\n]";
    if ( ctx.pandas() ) {
      b.l( df + " = pd.DataFrame(" + datos + ", columns=" + nombres( cs, "name" ) + ", dtype=object)" );
    } else {
      b.l( df + " = spark.createDataFrame(" + datos + ", " + esquemaTexto( cs, "name" ) + ")" );
    }
    tipar( ctx, b, df, cs, "name" );
    return b;
  }

  /** Generate rows → N filas con los mismos valores constantes. */
  static Bloque generar( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = nuevo( s, ctx );
    b.lee = false;
    String df = ctx.df( s );
    List<Element> cs = campos( x );
    String n = ctx.texto( v( x, "limit" ).isEmpty() ? "10" : v( x, "limit" ) );
    String nInt = n.startsWith( "f\"" ) ? "int(" + n + ")" : v( x, "limit" ).isEmpty() ? "10" : v( x, "limit" )
      .trim();
    StringBuilder cols = new StringBuilder();
    for ( Element c : cs ) {
      String val = v( c, "nullif" );
      String e = val.isEmpty() ? ( si( c, "set_empty_string" ) ? "\"\"" : "None" ) : CtxTrad.lit( val );
      if ( ctx.pandas() ) {
        cols.append( cols.length() == 0 ? "" : ", " ).append( CtxTrad.lit( v( c, "name" ) ) ).append( ": " ).append(
          e );
      } else {
        cols.append( ",\n    F.lit(" ).append( e ).append( ").cast(\"string\").alias(" ).append( CtxTrad.lit( v( c,
          "name" ) ) ).append( ")" );
      }
    }
    if ( ctx.pandas() ) {
      b.l( df + " = pd.DataFrame({" + cols + "}, index=range(" + nInt + "), dtype=object)" );
    } else {
      b.l( df + " = spark.range(" + nInt + ").select(" + ( cols.length() == 0 ? "" : cols.substring( 1 ) ) + "\n)" );
    }
    tipar( ctx, b, df, cs, "name" );
    if ( si( x, "never_ending" ) ) {
      b.aproximado( ctx.t( "«Sin fin» no se traduce: se generan " + n + " filas.", "'Never ending' is not "
        + "translated: " + n + " rows are generated.", "«Sem fim» não é traduzido: geram-se " + n + " linhas." ) );
    }
    return b;
  }

  // ================================================================ bases de datos

  /** Table input → pd.read_sql / spark.read.format("jdbc") con la consulta (y sus variables). */
  static Bloque tabla( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = nuevo( s, ctx );
    String df = ctx.df( s );
    DatabaseMeta db = ctx.trans.findDatabase( v( x, "connection" ) );
    String con = ctx.conexion( db );
    String sql = v( x, "sql" ).trim();
    String q = si( x, "variables_active" ) ? ctx.texto( sql ) : CtxTrad.lit( sql );
    if ( q.contains( "\\n" ) && q.startsWith( "\"" ) && !q.contains( "\"\"\"" ) ) {
      q = "\"\"\"" + sql.replace( "\\", "\\\\" ).replace( "\"\"\"", "\\\"\\\"\\\"" ) + "\"\"\"";
    }
    int limite = entero( v( x, "limit" ), 0 );
    String info = v( x, "lookup" );
    if ( ctx.pandas() ) {
      ctx.importar( "from sqlalchemy import create_engine, text" );
      if ( !info.isEmpty() && ctx.info( s, info ) != null ) {
        String p = ctx.info( s, info );
        b.l( "consulta = " + q );
        b.l( "# " + ctx.t( "cada ? se reemplaza por los valores de una fila de «" + info + "»", "each ? is replaced "
          + "by the values of a row of '" + info + "'", "cada ? é substituído pelos valores de uma linha de «" + info
            + "»" ) );
        b.l( "partes = []" );
        b.l( "for fila in " + p + ".itertuples(index=False):" );
        b.l( "    marcas = {f\"p{i}\": v for i, v in enumerate(fila)}" );
        b.l( "    sql_fila = consulta" );
        b.l( "    for i in range(len(fila)):" );
        b.l( "        sql_fila = sql_fila.replace(\"?\", f\":p{i}\", 1)" );
        b.l( "    partes.append(pd.read_sql_query(text(sql_fila), " + con + ", params=marcas))" );
        b.l( df + " = pd.concat(partes, ignore_index=True)" );
      } else {
        b.l( df + " = pd.read_sql_query(text(" + q + "), " + con + ")" );
      }
      if ( limite > 0 ) {
        b.l( df + " = " + df + ".head(" + limite + ")" );
      }
    } else {
      b.l( df + " = (spark.read.format(\"jdbc\").options(**" + con + ")" );
      b.l( "    .option(\"query\", " + q + ").load())" );
      if ( limite > 0 ) {
        b.l( df + " = " + df + ".limit(" + limite + ")" );
      }
      if ( !info.isEmpty() ) {
        b.manual( ctx.t( "Ejecutar por cada fila de «" + info + "» no se traduce en PySpark: une los parámetros con "
          + "un join.", "Executing for each row of '" + info + "' is not translated in PySpark: join the "
            + "parameters instead.", "Executar para cada linha de «" + info + "» não é traduzido em PySpark: junte os "
              + "parâmetros com um join." ) );
      }
    }
    b.nota( ctx.t( "Los tipos los decide el driver de Python/JDBC: pueden diferir un poco de los de PDI.",
      "Types are decided by the Python/JDBC driver: they may differ slightly from PDI's.",
      "Os tipos são decididos pelo driver Python/JDBC: podem diferir um pouco dos do PDI." ) );
    return b;
  }

  // ================================================================ Excel, JSON, XML

  /** Microsoft Excel input → pd.read_excel por hoja (encabezado, fila y columna de inicio, varios archivos). */
  static Bloque excel( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = nuevo( s, ctx );
    String df = ctx.df( s );
    List<Element> cs = campos( x );
    String archivos = ctx.nuevo( "archivos_" + CtxTrad.ident( s.getName() ) );
    listaArchivos( ctx, b, s, x, archivos );
    List<Element> hojas = hijos( uno( x, "sheets" ), "sheet" );
    boolean cab = si( x, "header" );
    StringBuilder lh = new StringBuilder( "[" );
    for ( Element h : hojas ) {
      lh.append( lh.length() == 1 ? "" : ", " ).append( "(" ).append( CtxTrad.lit( v( h, "name" ) ) ).append( ", " )
        .append( entero( v( h, "startrow" ), 0 ) ).append( ", " ).append( entero( v( h, "startcol" ), 0 ) ).append(
          ")" );
    }
    lh.append( "]" );
    String hojasPy = hojas.isEmpty() ? "[(0, 0, 0)]" : lh.toString();
    ctx.importar( "import pandas as pd" );
    b.l( "partes = []" );
    b.l( "for archivo in " + archivos + ":" );
    b.l( "    for hoja, fila0, col0 in " + hojasPy + ":" );
    b.l( "        parte = pd.read_excel(archivo, sheet_name=hoja, header=None, skiprows=fila0" + ( cab
      ? " + 1" : "" ) + ", dtype=object)" );
    b.l( "        parte = parte.iloc[:, col0:col0 + " + cs.size() + "]" );
    b.l( "        parte.columns = " + nombres( cs, "name" ) + "[:parte.shape[1]]" );
    if ( !v( x, "sheetfield" ).isEmpty() ) {
      b.l( "        parte[" + CtxTrad.lit( v( x, "sheetfield" ) ) + "] = hoja" );
    }
    if ( !v( x, "filefield" ).isEmpty() ) {
      b.l( "        parte[" + CtxTrad.lit( v( x, "filefield" ) ) + "] = archivo" );
    }
    b.l( "        partes.append(parte)" );
    String pdf = ctx.pandas() ? df : ctx.nuevo( "pdf_" + CtxTrad.ident( s.getName() ) );
    b.l( pdf + " = pd.concat(partes, ignore_index=True)" );
    if ( si( x, "noempty" ) ) {
      b.l( pdf + " = " + pdf + ".dropna(how=\"all\", subset=" + nombres( cs, "name" ) + ").reset_index(drop=True)" );
    }
    int limite = entero( v( x, "limit" ), 0 );
    if ( limite > 0 ) {
      b.l( pdf + " = " + pdf + ".head(" + limite + ")" );
    }
    // las celdas llegan con su tipo de Excel: se pasan a texto y se convierten con las máscaras de PDI
    List<Element> conv = new ArrayList<>();
    for ( Element c : cs ) {
      String t = v( c, "type" );
      if ( "Integer".equals( t ) || "Number".equals( t ) ) {
        b.l( pdf + "[" + CtxTrad.lit( v( c, "name" ) ) + "] = pd.to_numeric(" + pdf + "[" + CtxTrad.lit( v( c,
          "name" ) ) + "])" + ( "Integer".equals( t ) ? ".astype(\"Int64\")" : ".astype(\"float64\")" ) );
      } else if ( "Date".equals( t ) ) {
        b.l( pdf + "[" + CtxTrad.lit( v( c, "name" ) ) + "] = pd.to_datetime(" + pdf + "[" + CtxTrad.lit( v( c,
          "name" ) ) + "])" );
      } else if ( "String".equals( t ) ) {
        b.l( pdf + "[" + CtxTrad.lit( v( c, "name" ) ) + "] = " + pdf + "[" + CtxTrad.lit( v( c, "name" ) )
          + "].astype(\"string\")" + trimPandas( v( c, "trim_type" ) ) );
      } else {
        conv.add( c );
      }
    }
    if ( !conv.isEmpty() ) {
      b.l( pdf + "[" + nombres( conv, "name" ) + "] = " + pdf + "[" + nombres( conv, "name" ) + "].astype(\"string\")" );
    }
    if ( ctx.spark() ) {
      b.l( df + " = spark.createDataFrame(" + pdf + ".astype(object).where(" + pdf + ".notna(), None))" );
      b.nota( ctx.t( "Spark no lee Excel: se lee con pandas (openpyxl) y se pasa a Spark.", "Spark cannot read "
        + "Excel: it is read with pandas (openpyxl) and passed to Spark.", "O Spark não lê Excel: lê-se com pandas "
          + "(openpyxl) e passa-se ao Spark." ) );
      Bloque t = new Bloque( s.getName(), s.getStepID() );
      tipar( ctx, t, df, conv, "name" );
      b.codigo.addAll( t.codigo );
    } else {
      tipar( ctx, b, df, conv, "name" );
    }
    if ( si( x, "stoponempty" ) ) {
      b.aproximado( ctx.t( "«Parar en fila vacía» no se traduce: se quitan las filas vacías.", "'Stop on empty row' "
        + "is not translated: empty rows are dropped.", "«Parar em linha vazia» não é traduzido: removem-se as "
          + "linhas vazias." ) );
    }
    b.nota( ctx.t( "Necesita openpyxl (xlsx) o xlrd (xls antiguos).", "Needs openpyxl (xlsx) or xlrd (old xls).",
      "Precisa de openpyxl (xlsx) ou xlrd (xls antigos)." ) );
    return b;
  }

  private static String trimPandas( String t ) {
    t = t.toLowerCase();
    return t.equals( "both" ) ? ".str.strip()" : t.equals( "left" ) ? ".str.lstrip()" : t.equals( "right" )
      ? ".str.rstrip()" : "";
  }

  /** JSON input → json.load + una columna por JSONPath (de archivo o de un campo). */
  static Bloque json( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = nuevo( s, ctx );
    String df = ctx.df( s );
    List<Element> cs = campos( x );
    ctx.importar( "import json" );
    ctx.ayudante( "_jsonpath", String.join( "\n",
      "def _jsonpath(doc, ruta):",
      "    \"\"\"" + ctx.t( "Valores de un JSONPath sencillo ($.a.b, $..a, [*], [n], ['x']) en orden de documento.",
        "Values of a simple JSONPath ($.a.b, $..a, [*], [n], ['x']) in document order.",
        "Valores de um JSONPath simples ($.a.b, $..a, [*], [n], ['x']) na ordem do documento." ) + "\"\"\"",
      "    import re",
      "    pasos = re.findall(r\"\\.\\.[^.\\[]+|\\.[^.\\[]+|\\[[^\\]]*\\]\", ruta.lstrip(\"$\"))",
      "    nodos = [doc]",
      "    for p in pasos:",
      "        sig = []",
      "        if p.startswith(\"..\"):",
      "            clave = p[2:]",
      "            def bajar(n):",
      "                if isinstance(n, dict):",
      "                    if clave in n:",
      "                        sig.append(n[clave])",
      "                    for v in n.values():",
      "                        bajar(v)",
      "                elif isinstance(n, list):",
      "                    for v in n:",
      "                        bajar(v)",
      "            for n in nodos:",
      "                bajar(n)",
      "        elif p in (\".*\", \"[*]\"):",
      "            for n in nodos:",
      "                sig.extend(n if isinstance(n, list) else n.values() if isinstance(n, dict) else [])",
      "        elif p.startswith(\"[\"):",
      "            k = p[1:-1].strip(\"'\\\"\")",
      "            for n in nodos:",
      "                if isinstance(n, list) and k.lstrip(\"-\").isdigit() and -len(n) <= int(k) < len(n):",
      "                    sig.append(n[int(k)])",
      "                elif isinstance(n, dict) and k in n:",
      "                    sig.append(n[k])",
      "        else:",
      "            for n in nodos:",
      "                if isinstance(n, dict) and p[1:] in n:",
      "                    sig.append(n[p[1:]])",
      "        nodos = sig",
      "    return nodos" ) );
    StringBuilder d = new StringBuilder( "{" );
    for ( Element c : cs ) {
      d.append( d.length() == 1 ? "" : ", " ).append( CtxTrad.lit( v( c, "name" ) ) ).append( ": " ).append(
        "_jsonpath(doc, " + CtxTrad.lit( v( c, "path" ) ) + ")" );
    }
    d.append( "}" );
    String in = ctx.entrada( s );
    String pdf = ctx.pandas() ? df : ctx.nuevo( "pdf_" + CtxTrad.ident( s.getName() ) );
    b.l( "def _filas_json(doc):" );
    b.l( "    cols = " + d );
    b.l( "    n = max((len(v) for v in cols.values()), default=0)" );
    b.l( "    return pd.DataFrame({k: (v + [None] * n)[:n] for k, v in cols.items()}, dtype=object)" );
    ctx.importar( "import pandas as pd" );
    if ( si( x, "IsInFields" ) && in != null ) {
      String campo = CtxTrad.lit( v( x, "valueField" ) );
      String filas = ctx.pandas() ? in + "[" + campo + "]" : "[r[" + campo + "] for r in " + in + ".select(" + campo
        + ").collect()]";
      boolean esArchivo = si( x, "IsAFile" );
      b.l( pdf + " = pd.concat([_filas_json(" + ( esArchivo ? "json.load(open(t, encoding=\"utf-8\"))"
        : "json.loads(t)" ) + ") for t in " + filas + "], ignore_index=True)" );
      b.nota( ctx.t( "El JSON sale del campo " + campo + " de la entrada (los demás campos no se conservan).",
        "The JSON comes from field " + campo + " of the input (the other fields are not kept).",
        "O JSON vem do campo " + campo + " da entrada (os outros campos não são mantidos)." ) );
    } else {
      String archivos = ctx.nuevo( "archivos_" + CtxTrad.ident( s.getName() ) );
      listaArchivos( ctx, b, s, x, archivos );
      String enc = "utf-8";
      b.l( "partes = []" );
      b.l( "for archivo in " + archivos + ":" );
      b.l( "    with open(archivo, encoding=" + CtxTrad.lit( enc ) + ") as fh:" );
      b.l( "        parte = _filas_json(json.load(fh))" );
      if ( si( x, "include" ) && !v( x, "include_field" ).isEmpty() ) {
        b.l( "    parte[" + CtxTrad.lit( v( x, "include_field" ) ) + "] = archivo" );
      }
      b.l( "    partes.append(parte)" );
      b.l( pdf + " = pd.concat(partes, ignore_index=True)" );
    }
    int limite = entero( v( x, "limit" ), 0 );
    if ( limite > 0 ) {
      b.l( pdf + " = " + pdf + ".head(" + limite + ")" );
    }
    b.l( pdf + " = " + pdf + ".astype(object).where(" + pdf + ".notna(), None)" );
    b.l( pdf + "[" + nombres( cs, "name" ) + "] = " + pdf + "[" + nombres( cs, "name" ) + "].map(lambda v: v if v is "
      + "None or isinstance(v, str) else json.dumps(v) if isinstance(v, (dict, list)) else str(v).lower() if "
      + "isinstance(v, bool) else str(v))" );
    if ( ctx.spark() ) {
      b.l( df + " = spark.createDataFrame(" + pdf + ", " + esquemaTexto( cs, "name" ) + ")" );
      b.nota( ctx.t( "El JSON se lee con Python y se pasa a Spark (spark.read.json necesita un objeto por línea).",
        "The JSON is read with Python and passed to Spark (spark.read.json needs one object per line).",
        "O JSON é lido com Python e passado ao Spark (spark.read.json precisa de um objeto por linha)." ) );
    }
    tipar( ctx, b, df, cs, "name" );
    if ( si( x, "rownum" ) ) {
      extras( ctx, b, df, null, null, v( x, "rownum_field" ) );
    }
    b.nota( ctx.t( "_jsonpath cubre los JSONPath habituales; los filtros [?(...)] hay que hacerlos a mano.",
      "_jsonpath covers the usual JSONPaths; [?(...)] filters must be done by hand.",
      "_jsonpath cobre os JSONPath habituais; os filtros [?(...)] devem ser feitos à mão." ) );
    return b;
  }

  /** Get data from XML → lxml/ElementTree: un registro por nodo del XPath de bucle y un XPath relativo por campo. */
  static Bloque xml( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = nuevo( s, ctx );
    String df = ctx.df( s );
    List<Element> cs = campos( x );
    ctx.importar( "import xml.etree.ElementTree as ET" );
    ctx.importar( "import pandas as pd" );
    String bucle = v( x, "loopxpath" );
    String rel = bucle.startsWith( "/" ) ? "." + bucle.replaceFirst( "^/[^/]+", "" ) : bucle;
    if ( rel.equals( "." ) || rel.isEmpty() ) {
      rel = ".";
    }
    StringBuilder d = new StringBuilder();
    for ( Element c : cs ) {
      String xp = v( c, "xpath" );
      boolean atributo = "attribute".equalsIgnoreCase( v( c, "element_type" ) ) || xp.startsWith( "@" );
      String e;
      if ( atributo ) {
        e = "nodo.get(" + CtxTrad.lit( xp.replaceFirst( "^@", "" ) ) + ")";
      } else if ( xp.equals( "." ) || xp.isEmpty() ) {
        e = "nodo.text";
      } else if ( xp.contains( "/@" ) ) {
        int i = xp.lastIndexOf( "/@" );
        e = "_attr(nodo.find(" + CtxTrad.lit( xp.substring( 0, i ) ) + "), " + CtxTrad.lit( xp.substring( i + 2 ) )
          + ")";
      } else {
        e = "nodo.findtext(" + CtxTrad.lit( xp ) + ")";
      }
      d.append( "\n        " ).append( CtxTrad.lit( v( c, "name" ) ) ).append( ": " ).append( e ).append( "," );
    }
    ctx.ayudante( "_attr", "def _attr(nodo, nombre):\n    return None if nodo is None else nodo.get(nombre)" );
    String pdf = ctx.pandas() ? df : ctx.nuevo( "pdf_" + CtxTrad.ident( s.getName() ) );
    String in = ctx.entrada( s );
    String raices;
    if ( si( x, "IsInFields" ) && in != null ) {
      String campo = CtxTrad.lit( v( x, "XmlField" ) );
      String vals = ctx.pandas() ? in + "[" + campo + "]" : "[r[" + campo + "] for r in " + in + ".select(" + campo
        + ").collect()]";
      raices = si( x, "IsAFile" ) ? "[ET.parse(t).getroot() for t in " + vals + "]" : "[ET.fromstring(t) for t in "
        + vals + "]";
    } else {
      String archivos = ctx.nuevo( "archivos_" + CtxTrad.ident( s.getName() ) );
      listaArchivos( ctx, b, s, x, archivos );
      raices = "[ET.parse(a).getroot() for a in " + archivos + "]";
    }
    b.l( pdf + " = pd.DataFrame([" );
    b.l( "    {" + d + "\n    }" );
    b.l( "    for raiz in " + raices );
    b.l( "    for nodo in " + ( rel.equals( "." ) ? "[raiz]" : "raiz.findall(" + CtxTrad.lit( rel ) + ")" ) );
    b.l( "], columns=" + nombres( cs, "name" ) + ", dtype=object)" );
    b.l( pdf + " = " + pdf + ".where(" + pdf + " != \"\", None)" );
    int limite = entero( v( x, "limit" ), 0 );
    if ( limite > 0 ) {
      b.l( pdf + " = " + pdf + ".head(" + limite + ")" );
    }
    if ( ctx.spark() ) {
      b.l( df + " = spark.createDataFrame(" + pdf + ", " + esquemaTexto( cs, "name" ) + ")" );
      b.nota( ctx.t( "El XML se lee con Python (ElementTree) y se pasa a Spark.", "The XML is read with Python "
        + "(ElementTree) and passed to Spark.", "O XML é lido com Python (ElementTree) e passado ao Spark." ) );
    }
    tipar( ctx, b, df, cs, "name" );
    b.nota( ctx.t( "ElementTree sólo entiende un subconjunto de XPath (rutas, @atributo, [n]); con funciones usa lxml.",
      "ElementTree only understands a subset of XPath (paths, @attribute, [n]); for functions use lxml.",
      "O ElementTree só entende um subconjunto de XPath (caminhos, @atributo, [n]); com funções use lxml." ) );
    if ( si( x, "namespaceaware" ) ) {
      b.aproximado( ctx.t( "Con espacios de nombres, escribe las rutas como {uri}etiqueta.", "With namespaces, write "
        + "paths as {uri}tag.", "Com namespaces, escreva os caminhos como {uri}etiqueta." ) );
    }
    return b;
  }

  // ================================================================ sistema

  /** Get variables → una fila (o una columna más por fila de la entrada) con el valor de cada variable. */
  static Bloque variables( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = nuevo( s, ctx );
    b.lee = false;
    String df = ctx.df( s );
    List<Element> cs = campos( x );
    String in = ctx.entradaUnida( s, b );
    StringBuilder d = new StringBuilder();
    for ( Element c : cs ) {
      String e = ctx.texto( v( c, "variable" ) );
      if ( ctx.pandas() ) {
        d.append( d.length() == 0 ? "" : ", " ).append( CtxTrad.lit( v( c, "name" ) ) ).append( ": " ).append( e );
      } else {
        d.append( ",\n    " ).append( CtxTrad.lit( v( c, "name" ) ) ).append( ": F.lit(" ).append( e ).append( ")" );
      }
    }
    if ( ctx.pandas() ) {
      if ( in == null ) {
        b.l( df + " = pd.DataFrame([{" + d + "}], dtype=object)" );
      } else {
        b.l( df + " = " + in + ".assign(**{" + d + "})" );
      }
      b.l( df + " = " + df + ".where(" + df + " != \"\", None)" );
    } else {
      b.l( df + " = " + ( in == null ? "spark.range(1).drop(\"id\")" : in ) + ".withColumns({" + d.substring( Math
        .min( 1, d.length() ) ) + "\n})" );
    }
    tipar( ctx, b, df, cs, "name" );
    b.nota( ctx.t( "Las variables salen del dict VARS (o del entorno).", "Variables come from the VARS dict (or the "
      + "environment).", "As variáveis vêm do dict VARS (ou do ambiente)." ) );
    return b;
  }

  /** Get system info → fecha del sistema, nombre de la transformación, equipo... */
  static Bloque sistema( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = nuevo( s, ctx );
    b.lee = false;
    String df = ctx.df( s );
    String in = ctx.entradaUnida( s, b );
    ctx.importar( "import datetime as dt" );
    StringBuilder d = new StringBuilder();
    for ( Element c : campos( x ) ) {
      String tipo = v( c, "type" ).toLowerCase();
      String e;
      if ( tipo.startsWith( "system date" ) || tipo.equals( "start date range" ) || tipo.equals( "end date range" )
        || tipo.contains( "start date" ) && tipo.contains( "trans" ) ) {
        e = "dt.datetime.now()";
      } else if ( tipo.equals( "today start" ) ) {
        e = "dt.datetime.combine(dt.date.today(), dt.time.min)";
      } else if ( tipo.equals( "today end" ) ) {
        e = "dt.datetime.combine(dt.date.today(), dt.time.max)";
      } else if ( tipo.equals( "yesterday start" ) ) {
        e = "dt.datetime.combine(dt.date.today() - dt.timedelta(days=1), dt.time.min)";
      } else if ( tipo.equals( "tomorrow start" ) ) {
        e = "dt.datetime.combine(dt.date.today() + dt.timedelta(days=1), dt.time.min)";
      } else if ( tipo.equals( "transformation name" ) || tipo.equals( "trans name" ) ) {
        e = "VARS[\"Internal.Transformation.Name\"]";
      } else if ( tipo.equals( "hostname" ) || tipo.equals( "hostname real" ) ) {
        ctx.importar( "import socket" );
        e = "socket.gethostname()";
      } else if ( tipo.equals( "ip address" ) ) {
        ctx.importar( "import socket" );
        e = "socket.gethostbyname(socket.gethostname())";
      } else if ( tipo.equals( "current pid" ) ) {
        e = "os.getpid()";
      } else if ( tipo.equals( "jvm max memory" ) || tipo.startsWith( "kettle" ) ) {
        e = "None";
        b.aproximado( ctx.t( "«" + v( c, "type" ) + "» no tiene equivalente en Python.", "'" + v( c, "type" )
          + "' has no Python equivalent.", "«" + v( c, "type" ) + "» não tem equivalente em Python." ) );
      } else {
        e = "None";
        b.aproximado( ctx.t( "Dato del sistema «" + v( c, "type" ) + "» sin traducir: completa el valor.",
          "System info '" + v( c, "type" ) + "' not translated: fill in the value.",
          "Dado do sistema «" + v( c, "type" ) + "» sem tradução: complete o valor." ) );
      }
      if ( ctx.pandas() ) {
        d.append( d.length() == 0 ? "" : ", " ).append( CtxTrad.lit( v( c, "name" ) ) ).append( ": " ).append( e );
      } else {
        d.append( ",\n    " ).append( CtxTrad.lit( v( c, "name" ) ) ).append( ": F.lit(" ).append( e ).append( ")" );
      }
    }
    if ( ctx.pandas() ) {
      b.l( df + " = " + ( in == null ? "pd.DataFrame([{" + d + "}])" : in + ".assign(**{" + d + "})" ) );
    } else {
      b.l( df + " = " + ( in == null ? "spark.range(1).drop(\"id\")" : in ) + ".withColumns({" + d.substring( Math
        .min( 1, d.length() ) ) + "\n})" );
    }
    return b;
  }

  /** Generate random value → números, enteros, textos y UUID aleatorios por fila. */
  static Bloque aleatorio( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = nuevo( s, ctx );
    b.lee = false;
    String df = ctx.df( s );
    String in = ctx.entradaUnida( s, b );
    if ( in == null ) {
      return TraductorTrans.sinTraduccion( s, ctx, ctx.t( "Generate random value sin entrada.", "Generate random "
        + "value without input.", "Generate random value sem entrada." ) );
    }
    if ( ctx.pandas() ) {
      ctx.importar( "import uuid" );
      b.l( "_rng = np.random.default_rng()" );
      b.l( df + " = " + in + ".copy()" );
    } else {
      b.l( df + " = " + in );
    }
    for ( Element c : campos( x ) ) {
      String n = CtxTrad.lit( v( c, "name" ) );
      String t = v( c, "type" ).toLowerCase();
      String e;
      if ( ctx.pandas() ) {
        switch ( t ) {
          case "random number": e = "_rng.random(len(" + df + "))"; break;
          case "random integer": e = "_rng.integers(-2**31, 2**31, len(" + df + "))"; break;
          case "random string":
            e = "[np.base_repr(int(v), 36).lower() for v in _rng.integers(0, 2**63 - 1, len(" + df + "))]";
            break;
          default: e = "[str(uuid.uuid4()) for _ in range(len(" + df + "))]";
        }
        b.l( df + "[" + n + "] = " + e );
      } else {
        switch ( t ) {
          case "random number": e = "F.rand()"; break;
          case "random integer": e = "(F.rand() * 2**32 - 2**31).cast(\"int\")"; break;
          case "random string": e = "F.substring(F.sha2(F.rand().cast(\"string\"), 256), 1, 13)"; break;
          default: e = "F.expr(\"uuid()\")";
        }
        b.l( df + " = " + df + ".withColumn(" + n + ", " + e + ")" );
      }
      if ( t.contains( "mac" ) ) {
        b.aproximado( ctx.t( "Las claves HMAC aleatorias se generan como UUID.", "Random HMAC keys are generated as "
          + "UUIDs.", "As chaves HMAC aleatórias são geradas como UUID." ) );
      }
    }
    b.nota( ctx.t( "Valores aleatorios: no coinciden con los de PDI (ni entre ejecuciones).", "Random values: they do "
      + "not match PDI's (nor between runs).", "Valores aleatórios: não coincidem com os do PDI (nem entre "
        + "execuções)." ) );
    return b;
  }

  /** Get file names → una fila por archivo (nombre, ruta, tamaño, fecha...). */
  static Bloque nombresArchivo( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = nuevo( s, ctx );
    String df = ctx.df( s );
    String archivos = ctx.nuevo( "archivos_" + CtxTrad.ident( s.getName() ) );
    listaArchivos( ctx, b, s, x, archivos );
    String filtro = v( uno( x, "filter" ), "filterfiletype" );
    String pdf = ctx.pandas() ? df : ctx.nuevo( "pdf_" + CtxTrad.ident( s.getName() ) );
    ctx.importar( "import pandas as pd" );
    b.l( pdf + " = pd.DataFrame([{" );
    b.l( "    \"filename\": os.path.abspath(a), \"short_filename\": os.path.basename(a)," );
    b.l( "    \"path\": os.path.dirname(os.path.abspath(a)), \"type\": \"file\" if os.path.isfile(a) else \"folder\"," );
    b.l( "    \"exists\": os.path.exists(a), \"ishidden\": os.path.basename(a).startswith(\".\")," );
    b.l( "    \"isreadable\": os.access(a, os.R_OK), \"iswriteable\": os.access(a, os.W_OK)," );
    b.l( "    \"lastmodifiedtime\": pd.Timestamp(os.path.getmtime(a), unit=\"s\")," );
    b.l( "    \"size\": os.path.getsize(a), \"extension\": os.path.splitext(a)[1].lstrip(\".\")," );
    b.l( "    \"uri\": \"file:///\" + os.path.abspath(a).replace(os.sep, \"/\").lstrip(\"/\")," );
    b.l( "    \"rooturi\": \"file:///\"," );
    b.l( "} for a in " + archivos + ( "only_folders".equals( filtro ) ? " if os.path.isdir(a)" : "only_files".equals(
      filtro ) || filtro.isEmpty() ? " if os.path.isfile(a)" : " if os.path.exists(a)" ) + "])" );
    if ( si( x, "rownum" ) && !v( x, "rownum_field" ).isEmpty() ) {
      b.l( pdf + "[" + CtxTrad.lit( v( x, "rownum_field" ) ) + "] = np.arange(1, len(" + pdf + ") + 1)" );
      ctx.importar( "import numpy as np" );
    }
    int limite = entero( v( x, "limit" ), 0 );
    if ( limite > 0 ) {
      b.l( pdf + " = " + pdf + ".head(" + limite + ")" );
    }
    if ( ctx.spark() ) {
      b.l( df + " = spark.createDataFrame(" + pdf + ")" );
    }
    b.nota( ctx.t( "lastmodifiedtime va en UTC; la fecha de PDI es la local.", "lastmodifiedtime is in UTC; PDI's is "
      + "local time.", "lastmodifiedtime vai em UTC; a do PDI é a local." ) );
    return b;
  }

  /** Load file content in memory → el contenido de cada archivo en una columna (y su tamaño). */
  static Bloque cargarArchivo( StepMeta s, CtxTrad ctx ) throws Exception {
    Element x = ctx.xml( s );
    Bloque b = nuevo( s, ctx );
    String df = ctx.df( s );
    String archivos = ctx.nuevo( "archivos_" + CtxTrad.ident( s.getName() ) );
    if ( si( x, "IsInFields" ) && ctx.entrada( s ) != null ) {
      String in = ctx.entrada( s );
      String campo = CtxTrad.lit( v( x, "DynamicFilenameField" ) );
      b.l( archivos + " = " + ( ctx.pandas() ? in + "[" + campo + "].tolist()" : "[r[" + campo + "] for r in " + in
        + ".select(" + campo + ").collect()]" ) );
    } else {
      listaArchivos( ctx, b, s, x, archivos );
    }
    String enc = v( x, "encoding" ).isEmpty() ? "utf-8" : v( x, "encoding" );
    StringBuilder d = new StringBuilder();
    for ( Element c : campos( x ) ) {
      String t = v( c, "element_type" ).toLowerCase();
      String e = t.equals( "size" ) ? "os.path.getsize(a)" : "Binary".equals( v( c, "type" ) )
        ? "open(a, \"rb\").read()" : "open(a, encoding=" + CtxTrad.lit( enc ) + ").read()";
      d.append( d.length() == 0 ? "" : ", " ).append( CtxTrad.lit( v( c, "name" ) ) ).append( ": " ).append( e );
    }
    if ( si( x, "include" ) && !v( x, "include_field" ).isEmpty() ) {
      d.append( ", " ).append( CtxTrad.lit( v( x, "include_field" ) ) ).append( ": a" );
    }
    String pdf = ctx.pandas() ? df : ctx.nuevo( "pdf_" + CtxTrad.ident( s.getName() ) );
    ctx.importar( "import pandas as pd" );
    b.l( pdf + " = pd.DataFrame([{" + d + "} for a in " + archivos + "])" );
    if ( ctx.spark() ) {
      b.l( df + " = spark.createDataFrame(" + pdf + ")" );
    }
    return b;
  }
}
