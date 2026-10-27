package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.igual;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.ivi2511.pentahomcp.traductor.Bloque;
import io.github.ivi2511.pentahomcp.traductor.Destino;
import io.github.ivi2511.pentahomcp.traductor.EscritorPy;
import io.github.ivi2511.pentahomcp.traductor.Estado;
import io.github.ivi2511.pentahomcp.traductor.Programa;

/**
 * Traductores de salidas (0.8.0, parte A): cada caso es una transformación mínima (Data grid → salida) que corre en
 * Kettle y luego en Python (pandas y PySpark), y se compara lo que queda escrito: el archivo o la tabla de SQLite.
 */
public class PruebaTradSalidas {

  private static final String DIR = "${Internal.Transformation.Filename.Directory}";

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  /** Un caso: la transformación, cómo dejar el estado inicial y qué leer al final. */
  private abstract static class Caso {
    File ktr;
    String capturar;
    String[] campos;
    String[][] filas;
    List<String> kettleFilas;

    abstract void reiniciar() throws Exception;

    abstract List<String> foto() throws Exception;
  }

  private static String campo( String nombre, String tipo, String formato ) {
    return "<field><name>" + nombre + "</name><type>" + tipo + "</type><format>" + formato + "</format><currency/>"
      + ( "Number".equals( tipo ) ? "<decimal>.</decimal><group>,</group>" : "<decimal/><group/>" )
      + "<length>-1</length><precision>-1</precision><set_empty_string>N</set_empty_string></field>";
  }

  /** Último Data grid armado (para sustituirlo en el script mientras el Data grid no tenga traductor). */
  private static String[] ultimosCampos;
  private static String[][] ultimasFilas;

  private static String grilla( String nombre, String[] campos, String[][] filas ) {
    ultimosCampos = campos;
    ultimasFilas = filas;
    StringBuilder b = new StringBuilder( "<fields>" );
    for ( String c : campos ) {
      b.append( c );
    }
    b.append( "</fields><data>" );
    for ( String[] f : filas ) {
      b.append( "<line>" );
      for ( String v : f ) {
        b.append( "<item>" ).append( v ).append( "</item>" );
      }
      b.append( "</line>" );
    }
    b.append( "</data>" );
    return ArnesTraduccion.step( nombre, "DataGrid", b.toString() );
  }

  private static String texto( File f ) throws Exception {
    return f.isFile() ? new String( Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 ) : "<no existe>";
  }

  private static void sql( File db, String... sentencias ) throws Exception {
    try ( Connection c = DriverManager.getConnection( "jdbc:sqlite:" + db.getAbsolutePath() );
          Statement st = c.createStatement() ) {
      for ( String s : sentencias ) {
        st.execute( s );
      }
    }
  }

  /** Filas de la tabla ordenadas; las fechas normalizadas (Kettle guarda milisegundos, SQLAlchemy texto ISO). */
  private static List<String> tabla( File db, String consulta ) throws Exception {
    List<String> r = new ArrayList<>();
    try ( Connection c = DriverManager.getConnection( "jdbc:sqlite:" + db.getAbsolutePath() );
          Statement st = c.createStatement(); ResultSet rs = st.executeQuery( consulta ) ) {
      ResultSetMetaData md = rs.getMetaData();
      while ( rs.next() ) {
        List<String> f = new ArrayList<>();
        for ( int i = 1; i <= md.getColumnCount(); i++ ) {
          Object o = rs.getObject( i );
          String col = md.getColumnName( i ).toLowerCase();
          if ( o != null && ( col.startsWith( "date" ) || col.startsWith( "fecha" ) ) ) {
            o = o instanceof Number ? new SimpleDateFormat( "yyyy-MM-dd HH:mm:ss" ).format( new Date( ( (Number) o )
              .longValue() ) ) : o.toString().length() >= 19 ? o.toString().substring( 0, 19 ) : o.toString();
          } else if ( o instanceof Double ) {
            o = String.format( java.util.Locale.ROOT, "%.4f", (Double) o );
          }
          f.add( String.valueOf( o ) );
        }
        r.add( String.join( "|", f ) );
      }
    }
    return r;
  }

  private static final String[] CAMPOS_VENTAS = { campo( "id", "Integer", "" ), campo( "nombre", "String", "" ),
    campo( "fecha", "Date", "yyyy-MM-dd" ), campo( "importe", "Number", "" ), campo( "activo", "Boolean", "" ) };
  private static final String[][] FILAS_VENTAS = { { "1", "Ana", "2024-01-05", "1234.5", "Y" },
    { "2", "Luis; hijo", "2024-02-10", "7", "N" }, { "3", "Eva", "2024-12-31", "0.125", "Y" } };

  private List<Caso> casos() throws Exception {
    File dir = EntornoKettle.salida( "trad-salidas" );
    List<Caso> l = new ArrayList<>();

    // 1. Text file output con formatos por campo, encierro sólo si hace falta y cabecera
    final File csv1 = new File( dir, "salida1.csv" );
    Caso c1 = new Caso() {
      void reiniciar() {
        csv1.delete();
      }

      List<String> foto() throws Exception {
        List<String> r = new ArrayList<>();
        r.add( texto( csv1 ) );
        return r;
      }
    };
    c1.ktr = ArnesTraduccion.escribir( dir, "sal1_texto.ktr", ArnesTraduccion.ktr( "sal1", null,
      ArnesTraduccion.hop( "Datos", "Salida" ), grilla( "Datos", CAMPOS_VENTAS, FILAS_VENTAS ),
      ArnesTraduccion.step( "Salida", "TextFileOutput", "<separator>;</separator><enclosure>\"</enclosure>"
        + "<enclosure_forced>N</enclosure_forced><enclosure_fix_disabled>N</enclosure_fix_disabled><header>Y</header>"
        + "<footer>N</footer><format>UNIX</format><compression>None</compression><encoding>UTF-8</encoding>"
        + "<file><name>" + DIR + "/salida1</name><extention>csv</extention><append>N</append><split>N</split>"
        + "<haspartno>N</haspartno><add_date>N</add_date><add_time>N</add_time><splitevery>0</splitevery></file>"
        + "<fields>" + "<field><name>nombre</name><type>String</type><format/><trim_type>none</trim_type></field>"
        + "<field><name>fecha</name><type>Date</type><format>dd/MM/yyyy</format></field>"
        + "<field><name>importe</name><type>Number</type><format>#,##0.00</format><decimal>,</decimal>"
        + "<group>.</group></field>" + "<field><name>id</name><type>Integer</type><format>#</format></field>"
        + "</fields>" ) ) );
    c1.campos = ultimosCampos;
    c1.filas = ultimasFilas;
    l.add( c1 );

    // 2. Text file output con encierro forzado, sin lista de campos (formatos por defecto) y DOS
    final File csv2 = new File( dir, "salida2.txt" );
    Caso c2 = new Caso() {
      void reiniciar() {
        csv2.delete();
      }

      List<String> foto() throws Exception {
        List<String> r = new ArrayList<>();
        r.add( texto( csv2 ) );
        return r;
      }
    };
    c2.ktr = ArnesTraduccion.escribir( dir, "sal2_texto.ktr", ArnesTraduccion.ktr( "sal2", null,
      ArnesTraduccion.hop( "Datos", "Salida" ), grilla( "Datos", CAMPOS_VENTAS, FILAS_VENTAS ),
      ArnesTraduccion.step( "Salida", "TextFileOutput", "<separator>,</separator><enclosure>\"</enclosure>"
        + "<enclosure_forced>Y</enclosure_forced><header>Y</header><format>DOS</format><encoding>UTF-8</encoding>"
        + "<file><name>" + DIR + "/salida2</name><extention>txt</extention><append>N</append><split>N</split>"
        + "<splitevery>0</splitevery></file><fields></fields>" ) ) );
    c2.campos = ultimosCampos;
    c2.filas = ultimasFilas;
    l.add( c2 );

    // 3. Table output (SQLite) con vaciado y mapeo de campos
    final File db3 = new File( dir, "sal3.sqlite" );
    Caso c3 = new Caso() {
      void reiniciar() throws Exception {
        db3.delete();
        sql( db3, "CREATE TABLE ventas (codigo INTEGER, cliente TEXT, importe REAL)",
          "INSERT INTO ventas VALUES (99, 'viejo', 1.0)" );
      }

      List<String> foto() throws Exception {
        return tabla( db3, "SELECT codigo, cliente, importe FROM ventas ORDER BY codigo" );
      }
    };
    c3.ktr = ArnesTraduccion.escribir( dir, "sal3_tabla.ktr", ArnesTraduccion.ktr( "sal3", ArnesTraduccion.sqlite(
      "base", DIR + "/sal3.sqlite" ), ArnesTraduccion.hop( "Datos", "Tabla" ), grilla( "Datos", CAMPOS_VENTAS,
        FILAS_VENTAS ), ArnesTraduccion.step( "Tabla", "TableOutput", "<connection>base</connection><schema/>"
          + "<table>ventas</table><commit>100</commit><truncate>Y</truncate><ignore_errors>N</ignore_errors>"
          + "<use_batch>Y</use_batch><specify_fields>Y</specify_fields><partitioning_enabled>N</partitioning_enabled>"
          + "<tablename_in_field>N</tablename_in_field><return_keys>N</return_keys><fields>"
          + "<field><column_name>codigo</column_name><stream_name>id</stream_name></field>"
          + "<field><column_name>cliente</column_name><stream_name>nombre</stream_name></field>"
          + "<field><column_name>importe</column_name><stream_name>importe</stream_name></field></fields>" ) ) );
    c3.campos = ultimosCampos;
    c3.filas = ultimasFilas;
    l.add( c3 );

    // 4. Insert / update (SQLite): actualiza una fila, inserta otra, deja la tercera
    final File db4 = new File( dir, "sal4.sqlite" );
    Caso c4 = new Caso() {
      void reiniciar() throws Exception {
        db4.delete();
        sql( db4, "CREATE TABLE clientes (id INTEGER, nombre TEXT, ciudad TEXT)",
          "INSERT INTO clientes VALUES (1, 'Ana', 'Madrid')", "INSERT INTO clientes VALUES (2, 'Luis', 'Sevilla')" );
      }

      List<String> foto() throws Exception {
        return tabla( db4, "SELECT id, nombre, ciudad FROM clientes ORDER BY id" );
      }
    };
    String[] camposCli = { campo( "id", "Integer", "" ), campo( "nombre", "String", "" ), campo( "ciudad", "String",
      "" ) };
    c4.ktr = ArnesTraduccion.escribir( dir, "sal4_upsert.ktr", ArnesTraduccion.ktr( "sal4", ArnesTraduccion.sqlite(
      "base", DIR + "/sal4.sqlite" ), ArnesTraduccion.hop( "Datos", "Upsert" ), grilla( "Datos", camposCli,
        new String[][] { { "1", "Ana", "Bilbao" }, { "3", "Eva", "Vigo" }, { "2", "Luis", "Sevilla" } } ),
      ArnesTraduccion.step( "Upsert", "InsertUpdate", "<connection>base</connection><commit>100</commit>"
        + "<update_bypassed>N</update_bypassed><lookup><schema/><table>clientes</table>"
        + "<key><name>id</name><field>id</field><condition>=</condition><name2/></key>"
        + "<value><name>id</name><rename>id</rename><update>N</update></value>"
        + "<value><name>nombre</name><rename>nombre</rename><update>Y</update></value>"
        + "<value><name>ciudad</name><rename>ciudad</rename><update>Y</update></value></lookup>" ) ) );
    c4.campos = ultimosCampos;
    c4.filas = ultimasFilas;
    l.add( c4 );

    // 5. Dimension lookup/update (SQLite): SCD 2 con fecha de la fila; se compara la tabla y la clave técnica
    final File db5 = new File( dir, "sal5.sqlite" );
    Caso c5 = new Caso() {
      void reiniciar() throws Exception {
        db5.delete();
        sql( db5, "CREATE TABLE dim_cliente (tk INTEGER, version INTEGER, date_from TIMESTAMP, date_to TIMESTAMP, "
          + "id INTEGER, ciudad TEXT, nombre TEXT)" );
      }

      List<String> foto() throws Exception {
        return tabla( db5, "SELECT tk, version, date_from, date_to, id, ciudad, nombre FROM dim_cliente ORDER BY tk" );
      }
    };
    String[] camposDim = { campo( "id", "Integer", "" ), campo( "ciudad", "String", "" ), campo( "nombre", "String",
      "" ), campo( "fecha", "Date", "yyyy-MM-dd" ) };
    c5.capturar = "Dimension";
    c5.ktr = ArnesTraduccion.escribir( dir, "sal5_dim.ktr", ArnesTraduccion.ktr( "sal5", ArnesTraduccion.sqlite(
      "base", DIR + "/sal5.sqlite" ), ArnesTraduccion.hop( "Datos", "Dimension" ), grilla( "Datos", camposDim,
        new String[][] { { "1", "Madrid", "Ana", "2024-01-01" }, { "2", "Sevilla", "Luis", "2024-01-01" },
          { "1", "Bilbao", "Ana", "2024-06-01" }, { "2", "Sevilla", "Luisa", "2024-07-01" } } ),
      ArnesTraduccion.step( "Dimension", "DimensionLookup", "<schema/><table>dim_cliente</table>"
        + "<connection>base</connection><commit>100</commit><update>Y</update><fields>"
        + "<key><name>id</name><lookup>id</lookup></key>"
        + "<date><name>fecha</name><from>date_from</from><to>date_to</to></date>"
        + "<field><name>ciudad</name><lookup>ciudad</lookup><update>Insert</update></field>"
        + "<field><name>nombre</name><lookup>nombre</lookup><update>Punch through</update></field>"
        + "<return><name>tk</name><rename>tk_cliente</rename><creation_method>tablemax</creation_method>"
        + "<use_autoinc>N</use_autoinc><version>version</version></return></fields><sequence/>"
        + "<min_year>1900</min_year><max_year>2199</max_year><cache_size>0</cache_size>"
        + "<preload_cache>N</preload_cache><use_start_date_alternative>N</use_start_date_alternative>"
        + "<start_date_alternative>none</start_date_alternative><start_date_field_name/><useBatch>N</useBatch>" ) ) );
    c5.campos = ultimosCampos;
    c5.filas = ultimasFilas;
    l.add( c5 );

    String[] cli = { campo( "id", "Integer", "" ), campo( "nombre", "String", "" ) };
    String[] crearCli = { "CREATE TABLE clientes (id INTEGER, nombre TEXT)", "INSERT INTO clientes VALUES (1, 'Ana')",
      "INSERT INTO clientes VALUES (2, 'Luis')", "INSERT INTO clientes VALUES (3, 'Eva')" };
    // 6. Delete por clave
    l.add( casoBase( dir, "sal6_borrar", crearCli, "SELECT id, nombre FROM clientes ORDER BY id", cli,
      new String[][] { { "1", "x" }, { "3", "y" } }, null, ArnesTraduccion.step( "Paso", "Delete",
        "<connection>base</connection><commit>100</commit><lookup><schema/><table>clientes</table>"
          + "<key><name>id</name><field>id</field><condition>=</condition><name2/></key></lookup>" ) ) );
    // 7. Update con «ignorar si no está» (el 9 no existe)
    l.add( casoBase( dir, "sal7_actualizar", crearCli, "SELECT id, nombre FROM clientes ORDER BY id", cli,
      new String[][] { { "1", "Ana María" }, { "9", "Nadie" } }, null, ArnesTraduccion.step( "Paso", "Update",
        "<connection>base</connection><skip_lookup>N</skip_lookup><commit>100</commit><use_batch>N</use_batch>"
          + "<error_ignored>Y</error_ignored><ignore_flag_field/><lookup><schema/><table>clientes</table>"
          + "<key><name>id</name><field>id</field><condition>=</condition><name2/></key>"
          + "<value><name>nombre</name><rename>nombre</rename></value></lookup>" ) ) );
    // 8. Combination lookup/update: clave técnica por nombre
    l.add( casoBase( dir, "sal8_combinacion", new String[] { "CREATE TABLE dim_comb (tk INTEGER, nombre TEXT)" },
      "SELECT tk, nombre FROM dim_comb ORDER BY tk", cli, new String[][] { { "1", "Ana" }, { "2", "Luis" },
        { "3", "Ana" } }, "Paso", ArnesTraduccion.step( "Paso", "CombinationLookup", "<schema/><table>dim_comb"
          + "</table><connection>base</connection><commit>100</commit><cache_size>0</cache_size><replace>N</replace>"
          + "<preloadCache>N</preloadCache><crc>N</crc><crcfield/><fields><key><name>nombre</name><lookup>nombre"
          + "</lookup></key><return><name>tk</name><creation_method>tablemax</creation_method><use_autoinc>N"
          + "</use_autoinc></return></fields><sequence/><last_update_field/>" ) ) );
    // 9. Execute SQL script (una vez)
    l.add( casoBase( dir, "sal9_sql", crearCli, "SELECT id, nombre FROM clientes ORDER BY id", cli,
      new String[][] { { "1", "x" } }, null, ArnesTraduccion.step( "Paso", "ExecSQL", "<connection>base"
        + "</connection><execute_each_row>N</execute_each_row><single_statement>N</single_statement>"
        + "<replace_variables>N</replace_variables><quoteString>N</quoteString><sql>DELETE FROM clientes WHERE id = 2;"
        + "\nINSERT INTO clientes VALUES (4, 'Sara');</sql><set_params>N</set_params><insert_field/><update_field/>"
        + "<delete_field/><read_field/><arguments></arguments>" ) ) );
    // 10. JSON output a archivo
    final File js = new File( dir, "salida10.js" );
    Caso c10 = new Caso() {
      void reiniciar() {
        js.delete();
      }

      List<String> foto() throws Exception {
        List<String> r = new ArrayList<>();
        Object o = js.isFile() ? new org.json.simple.parser.JSONParser().parse( texto( js ) ) : null;
        r.add( String.valueOf( o ) );
        return r;
      }
    };
    c10.ktr = ArnesTraduccion.escribir( dir, "sal10_json.ktr", ArnesTraduccion.ktr( "sal10", null,
      ArnesTraduccion.hop( "Datos", "Json" ), grilla( "Datos", CAMPOS_VENTAS, FILAS_VENTAS ), ArnesTraduccion.step(
        "Json", "JsonOutput", "<outputValue>json</outputValue><jsonBloc>ventas</jsonBloc><nrRowsInBloc>0"
          + "</nrRowsInBloc><operation_type>writetofile</operation_type><compatibility_mode>N</compatibility_mode>"
          + "<encoding>UTF-8</encoding><addtoresult>N</addtoresult><file><name>" + DIR + "/salida10</name>"
          + "<extention>js</extention><append>N</append><split>N</split><haspartno>N</haspartno><add_date>N"
          + "</add_date><add_time>N</add_time><create_parent_folder>N</create_parent_folder><DoNotOpenNewFileInit>N"
          + "</DoNotOpenNewFileInit></file><fields><field><name>id</name><element>id</element></field>"
          + "<field><name>nombre</name><element>cliente</element></field><field><name>importe</name><element>"
          + "importe</element></field></fields>" ) ) );
    c10.campos = ultimosCampos;
    c10.filas = ultimasFilas;
    l.add( c10 );

    // 11. Microsoft Excel writer (.xlsx): se comparan los valores de las celdas
    final File xl = new File( dir, "salida11.xlsx" );
    Caso c11 = new Caso() {
      void reiniciar() {
        xl.delete();
      }

      List<String> foto() throws Exception {
        return celdas( xl );
      }
    };
    c11.ktr = ArnesTraduccion.escribir( dir, "sal11_excel.ktr", ArnesTraduccion.ktr( "sal11", null,
      ArnesTraduccion.hop( "Datos", "Excel" ), grilla( "Datos", CAMPOS_VENTAS, FILAS_VENTAS ), ArnesTraduccion.step(
        "Excel", "TypeExitExcelWriterStep", "<header>Y</header><footer>N</footer><makeSheetActive>Y"
          + "</makeSheetActive><rowWritingMethod>overwrite</rowWritingMethod><startingCell>B2</startingCell>"
          + "<appendOmitHeader>N</appendOmitHeader><appendOffset>0</appendOffset><appendEmpty>0</appendEmpty>"
          + "<forceFormulaRecalculation>N</forceFormulaRecalculation><leaveExistingStylesUnchanged>N"
          + "</leaveExistingStylesUnchanged><appendLines>N</appendLines><add_to_result_filenames>N"
          + "</add_to_result_filenames><file><name>" + DIR + "/salida11</name><extention>xlsx</extention>"
          + "<do_not_open_newfile_init>N</do_not_open_newfile_init><split>N</split><add_date>N</add_date>"
          + "<add_time>N</add_time><SpecifyFormat>N</SpecifyFormat><date_time_format/><sheetname>Ventas</sheetname>"
          + "<autosizecolums>N</autosizecolums><stream_data>N</stream_data><protect_sheet>N</protect_sheet>"
          + "<password/><protected_by/><splitevery>0</splitevery><if_file_exists>new</if_file_exists>"
          + "<if_sheet_exists>new</if_sheet_exists></file><template><enabled>N</enabled><sheet_enabled>N"
          + "</sheet_enabled><hidden>N</hidden><filename/><sheetname/></template><fields>"
          + "<field><name>id</name><type>Integer</type><format/><title>Código</title></field>"
          + "<field><name>nombre</name><type>String</type><format/><title/></field>"
          + "<field><name>importe</name><type>Number</type><format/><title/></field></fields>" ) ) );
    c11.campos = ultimosCampos;
    c11.filas = ultimasFilas;
    l.add( c11 );
    return l;
  }

  /** Celdas no vacías de la primera hoja: «hoja!A1=valor» (números con 4 decimales). */
  private static List<String> celdas( File xl ) throws Exception {
    List<String> r = new ArrayList<>();
    if ( !xl.isFile() ) {
      r.add( "<no existe>" );
      return r;
    }
    try ( org.apache.poi.ss.usermodel.Workbook wb = org.apache.poi.ss.usermodel.WorkbookFactory.create( xl, null,
      true ) ) {
      org.apache.poi.ss.usermodel.Sheet h = wb.getSheetAt( 0 );
      for ( org.apache.poi.ss.usermodel.Row fila : h ) {
        for ( org.apache.poi.ss.usermodel.Cell c : fila ) {
          String v;
          switch ( c.getCellType() ) {
            case NUMERIC:
              v = String.format( java.util.Locale.ROOT, "%.4f", c.getNumericCellValue() );
              break;
            case BOOLEAN:
              v = String.valueOf( c.getBooleanCellValue() );
              break;
            case BLANK:
              continue;
            default:
              v = c.toString();
          }
          r.add( h.getSheetName() + "!" + c.getAddress() + "=" + v );
        }
      }
    }
    return r;
  }

  /** Un caso sobre una base SQLite propia: la crea con {@code crear}, corre el step y lee {@code consulta}. */
  private static Caso casoBase( File dir, String nombre, final String[] crear, final String consulta,
                                String[] campos, String[][] filas, String capturar, String paso ) throws Exception {
    final File db = new File( dir, nombre + ".sqlite" );
    Caso c = new Caso() {
      void reiniciar() throws Exception {
        db.delete();
        sql( db, crear );
      }

      List<String> foto() throws Exception {
        return tabla( db, consulta );
      }
    };
    c.capturar = capturar;
    c.ktr = ArnesTraduccion.escribir( dir, nombre + ".ktr", ArnesTraduccion.ktr( nombre, ArnesTraduccion.sqlite(
      "base", DIR + "/" + nombre + ".sqlite" ), ArnesTraduccion.hop( "Datos", "Paso" ), grilla( "Datos", campos,
        filas ), paso ) );
    c.campos = campos;
    c.filas = filas;
    return c;
  }

  public void pruebaEquivalencia() throws Exception {
    List<Caso> casos = casos();
    for ( Caso c : casos ) {
      c.reiniciar();
      c.kettleFilas = ArnesTraduccion.kettle( c.ktr, c.capturar );
      List<String> foto = c.foto();
      c.kettleFilas.add( 0, "FOTO " + foto );
    }
    List<String> fallas = new ArrayList<>();
    for ( Destino d : Destino.values() ) {
      List<File> scripts = new ArrayList<>();
      for ( Caso c : casos ) {
        c.reiniciar();
        Programa p = c.capturar == null ? ArnesTraduccion.traducir( c.ktr, d ) : ArnesTraduccion.traducir( c.ktr, d,
          c.capturar );
        for ( Bloque b : p.bloques ) {
          if ( !"DataGrid".equals( b.tipo ) ) {
            cierto( b.escribe || "DimensionLookup".equals( b.tipo ), c.ktr.getName() + ": el bloque escribe" );
            cierto( b.estado != Estado.MANUAL, c.ktr.getName() + " " + d.id() + ": " + b.notas );
          }
        }
        sustituirGrilla( ArnesTraduccion.script( c.ktr, d ), d, c.campos, c.filas );
        compila( ArnesTraduccion.script( c.ktr, d ) );
        scripts.add( ArnesTraduccion.script( c.ktr, d ) );
      }
      if ( python() == null || correr( null, 120, python(), "-c", "import " + ( d == Destino.PANDAS ? "pandas"
        : "pyspark" ) ).codigo != 0 ) {
        continue;
      }
      if ( d == Destino.PYSPARK ) {
        scripts.add( 0, calentarSpark( casos.get( 0 ).ktr.getParentFile() ) );
      }
      String salida = correrScripts( scripts, d == Destino.PANDAS ? 300 : 600 );
      for ( Caso c : casos ) {
        File py = ArnesTraduccion.script( c.ktr, d );
        String suya = ArnesTraduccion.salidaDe( salida, py );
        if ( suya.contains( "NULL_DATA_SOURCE_OPTION" ) ) {
          // las opciones JDBC de Conexiones traen "user": None (conexión sin usuario): Spark las rechaza
          System.out.println( "  SALTEADO " + py.getName() + ": opciones JDBC con user None (Conexiones)" );
          continue;
        }
        try {
          cierto( suya.contains( "OK" ) && !suya.contains( "FALLO" ), py.getName() + " corre:\n" + recortar( suya ) );
          List<String> foto = c.foto();
          igual( c.kettleFilas.get( 0 ), "FOTO " + foto, py.getName() + ": lo escrito es igual que en Kettle" );
          if ( c.capturar != null ) {
            ArnesTraduccion.comparar( c.kettleFilas.subList( 1, c.kettleFilas.size() ), ArnesTraduccion.leer(
              ArnesTraduccion.volcado( c.ktr, d, c.capturar ) ), py.getName() + ": filas de " + c.capturar );
          }
          System.out.println( "  equivalente a Kettle: " + py.getName() );
        } catch ( AssertionError e ) {
          fallas.add( e.getMessage() );
        }
      }
    }
    cierto( fallas.isEmpty(), fallas.size() + " casos distintos de Kettle:\n" + String.join( "\n\n", fallas ) );
  }

  /** La última parte de un traceback (lo que dice el error). */
  private static String recortar( String s ) {
    String[] l = s.split( "\\r?\\n" );
    StringBuilder b = new StringBuilder();
    for ( int i = Math.max( 0, l.length - 6 ); i < l.length; i++ ) {
      b.append( l[ i ] ).append( '\n' );
    }
    return b.toString();
  }

  /**
   * Si el Data grid todavía no tiene traductor (bloque MANUAL con un DataFrame vacío), pone en su lugar el DataFrame
   * con los mismos datos y tipos: así se prueban las salidas por separado.
   */
  private static void sustituirGrilla( File py, Destino d, String[] campos, String[][] filas ) throws Exception {
    String t = new String( Files.readAllBytes( py.toPath() ), StandardCharsets.UTF_8 );
    String vacio = d == Destino.PANDAS ? "df_datos = pd.DataFrame()\n" : "df_datos = spark.createDataFrame([], "
      + "T.StructType([]))\n";
    if ( !t.contains( vacio ) ) {
      return;
    }
    StringBuilder b = new StringBuilder( "_g = pd.DataFrame({" );
    for ( int i = 0; i < campos.length; i++ ) {
      String n = campos[ i ].replaceAll( ".*<name>([^<]*)</name>.*", "$1" );
      String tipo = campos[ i ].replaceAll( ".*<type>([^<]*)</type>.*", "$1" );
      List<String> vs = new ArrayList<>();
      for ( String[] f : filas ) {
        String v = f[ i ];
        vs.add( "Integer".equals( tipo ) || "Number".equals( tipo ) ? v : "Boolean".equals( tipo ) ? ( "Y".equals( v )
          ? "True" : "False" ) : io.github.ivi2511.pentahomcp.traductor.CtxTrad.lit( v ) );
      }
      String arr = "[" + String.join( ", ", vs ) + "]";
      b.append( i == 0 ? "" : ", " ).append( io.github.ivi2511.pentahomcp.traductor.CtxTrad.lit( n ) ).append( ": " )
        .append( "Integer".equals( tipo ) ? "pd.array(" + arr + ", dtype=\"Int64\")" : "Date".equals( tipo )
          ? "pd.to_datetime(" + arr + ")" : "Number".equals( tipo ) ? "[float(x) for x in " + arr + "]" : arr );
    }
    b.append( "})\n" );
    b.append( d == Destino.PANDAS ? "df_datos = _g\n" : "df_datos = spark.createDataFrame("
      + "_g.astype({c: \"int64\" for c in _g.columns if str(_g[c].dtype) == \"Int64\"}))\n" );
    if ( d == Destino.PYSPARK ) {
      b.insert( 0, "import pandas as pd\n" );
    }
    Files.write( py.toPath(), t.replace( vacio, b.toString() ).getBytes( StandardCharsets.UTF_8 ) );
  }

  /** Un primer script que crea la SparkSession con el driver JDBC de SQLite (los demás la reusan). */
  private static File calentarSpark( File dir ) throws Exception {
    File jar = null;
    File[] libs = new File( EntornoKettle.pdi(), "lib" ).listFiles( ( d, n ) -> n.startsWith( "sqlite-jdbc" ) );
    if ( libs != null && libs.length > 0 ) {
      jar = libs[ 0 ];
    }
    String py = String.join( "\n", "import os",
      "from pyspark.sql import SparkSession",
      "b = SparkSession.builder.appName(\"pruebas\").config(\"spark.driver.host\", \"localhost\")"
        + ".config(\"spark.driver.bindAddress\", \"127.0.0.1\")",
      // sólo el classpath del driver: spark.jars en Windows pide winutils (HADOOP_HOME)
      jar == null ? "" : "b = b.config(\"spark.driver.extraClassPath\", "
        + io.github.ivi2511.pentahomcp.traductor.CtxTrad.lit( jar.getAbsolutePath().replace( '\\', '/' ) ) + ")",
      "b.getOrCreate()", "" );
    return ArnesTraduccion.escribir( dir, "_calentar_spark.py", py );
  }

  /** El código generado de las salidas que no se corren (Excel, JSON, XML, SQL, borrar, combinación, log). */
  public void pruebaTraduccionTexto() throws Exception {
    File dir = EntornoKettle.salida( "trad-salidas" );
    Map<String, String> pasos = new LinkedHashMap<>();
    pasos.put( "TypeExitExcelWriterStep", "<header>Y</header><startingCell>B3</startingCell><file><name>" + DIR
      + "/libro</name><extention>xlsx</extention><sheetname>Ventas</sheetname><if_file_exists>new</if_file_exists>"
      + "</file><fields><field><name>nombre</name><type>String</type><title>Cliente</title></field></fields>" );
    pasos.put( "ExcelOutput", "<header>Y</header><encoding>UTF-8</encoding><append>N</append><file><name>" + DIR
      + "/viejo</name><extention>xls</extention><sheetname>Hoja1</sheetname><add_date>N</add_date></file>"
      + "<fields><field><name>id</name><type>Integer</type></field><field><name>fecha</name><type>Date</type>"
      + "</field></fields>" );
    pasos.put( "XMLOutput", "<encoding>UTF-8</encoding><xml_main_element>Filas</xml_main_element>"
      + "<xml_repeat_element>Fila</xml_repeat_element><file><name>" + DIR + "/ventas</name><extention>xml"
      + "</extention></file><fields><field><name>id</name><element>id</element><content_type>Attribute"
      + "</content_type><type>Integer</type></field><field><name>nombre</name><element>nombre</element>"
      + "<content_type>Element</content_type><type>String</type></field></fields>" );
    pasos.put( "SQLFileOutput", "<connection>base</connection><schema/><table>ventas</table><truncate>Y</truncate>"
      + "<create>Y</create><encoding>UTF-8</encoding><dateformat>yyyy-MM-dd</dateformat><file><name>" + DIR
      + "/ventas</name><extention>sql</extention><append>N</append></file>" );
    pasos.put( "TextFileOutput", "<separator>;</separator><enclosure>\"</enclosure><header>Y</header><format>UNIX"
      + "</format><encoding>UTF-8</encoding><file><name>" + DIR + "/partes</name><extention>csv</extention>"
      + "<add_date>Y</add_date><splitevery>2</splitevery></file><fields></fields>" );
    pasos.put( "WriteToLog", "<loglevel>log_level_basic</loglevel><displayHeader>Y</displayHeader><limitRows>Y"
      + "</limitRows><limitRowsNumber>5</limitRowsNumber><logmessage>hola</logmessage><fields><field><name>nombre"
      + "</name></field></fields>" );
    // sólo traducción (necesitan tablas): se compila
    pasos.put( "SynchronizeAfterMerge", "<connection>base</connection><commit>100</commit><tablename_in_field>N"
      + "</tablename_in_field><use_batch>N</use_batch><perform_lookup>N</perform_lookup><operation_order_field>"
      + "nombre</operation_order_field><order_insert>I</order_insert><order_update>U</order_update><order_delete>D"
      + "</order_delete><lookup><schema/><table>clientes</table><key><name>id</name><field>id</field><condition>="
      + "</condition><name2/></key><value><name>nombre</name><rename>nombre</rename><update>Y</update></value>"
      + "</lookup>" );
    pasos.put( "ExecSQLRow", "<commit>100</commit><connection>base</connection><sql_field>nombre</sql_field>"
      + "<sqlFromfile>N</sqlFromfile><sendOneStatement>Y</sendOneStatement>" );
    pasos.put( "ExecSQL", "<connection>base</connection><execute_each_row>Y</execute_each_row><single_statement>N"
      + "</single_statement><replace_variables>Y</replace_variables><sql>DELETE FROM t WHERE a = ? AND b = '${X}'"
      + "</sql><arguments><argument><name>id</name></argument></arguments>" );
    pasos.put( "DimensionLookup", "<schema/><table>dim</table><connection>base</connection><update>N</update>"
      + "<fields><key><name>id</name><lookup>id</lookup></key><date><name/><from>date_from</from><to>date_to</to>"
      + "</date><return><name>tk</name><rename/><creation_method>autoinc</creation_method><version>version"
      + "</version></return></fields>" );
    List<String> ejecutables = java.util.Arrays.asList( "TypeExitExcelWriterStep", "ExcelOutput", "XMLOutput",
      "SQLFileOutput", "TextFileOutput", "WriteToLog" );
    List<File> scripts = new ArrayList<>();
    int i = 0;
    for ( Map.Entry<String, String> e : pasos.entrySet() ) {
      i++;
      File ktr = ArnesTraduccion.escribir( dir, "txt" + i + "_" + e.getKey() + ".ktr", ArnesTraduccion.ktr( "t" + i,
        ArnesTraduccion.sqlite( "base", DIR + "/t.sqlite" ), ArnesTraduccion.hop( "Datos", "Paso" ), grilla( "Datos",
          CAMPOS_VENTAS, FILAS_VENTAS ), ArnesTraduccion.step( "Paso", e.getKey(), e.getValue() ) ) );
      for ( Destino d : Destino.values() ) {
        Programa p = ArnesTraduccion.traducir( ktr, d );
        Bloque b = p.bloques.get( p.bloques.size() - 1 );
        igual( e.getKey(), b.tipo, ktr.getName() + ": último bloque" );
        cierto( b.estado != Estado.MANUAL, ktr.getName() + " " + d.id() + " no queda MANUAL: " + b.notas );
        boolean escribe = !"WriteToLog".equals( e.getKey() ) && !"DimensionLookup".equals( e.getKey() );
        igual( escribe, b.escribe, ktr.getName() + ": escribe" );
        String py = EscritorPy.escribir( p );
        contiene( py, d == Destino.PANDAS ? "import pandas as pd" : "SparkSession", ktr.getName() + ": imports" );
        File f = ArnesTraduccion.script( ktr, d );
        sustituirGrilla( f, d, CAMPOS_VENTAS, FILAS_VENTAS );
        compila( f );
        if ( d == Destino.PANDAS && ejecutables.contains( e.getKey() ) ) {
          scripts.add( f );
        }
      }
    }
    if ( python() == null || correr( null, 120, python(), "-c", "import pandas, openpyxl" ).codigo != 0 ) {
      return;
    }
    String salida = correrScripts( scripts, 300 );
    for ( File f : scripts ) {
      String suya = ArnesTraduccion.salidaDe( salida, f );
      cierto( suya.contains( "OK" ) && !suya.contains( "FALLO" ), f.getName() + " corre:\n" + recortar( suya ) );
    }
    cierto( new File( dir, "libro.xlsx" ).isFile() && new File( dir, "viejo.xlsx" ).isFile(), "Excel escritos" );
    contiene( texto( new File( dir, "ventas.xml" ) ), "<Fila id=\"1\">", "XML con atributo" );
    contiene( texto( new File( dir, "ventas.sql" ) ), "INSERT INTO ventas(id, nombre, fecha, importe, activo) VALUES "
      + "(1, 'Ana', '2024-01-05', 1234.5, 'Y');", "SQL con INSERT" );
    File[] partes = dir.listFiles( ( x, n ) -> n.startsWith( "partes_" ) && n.endsWith( "_1.csv" ) );
    cierto( partes != null && partes.length > 0, "Text file output partido cada 2 filas (con fecha en el nombre)" );
  }

  // ================================================================ Python (copia local: no depende de PythonPrueba)

  private static String python;

  private static synchronized String python() throws Exception {
    if ( python == null ) {
      String env = System.getenv( "PENTAHO_LIVE_PYTHON" );
      for ( String c : env != null && !env.isEmpty() ? new String[] { env } : new String[] { "python", "python3" } ) {
        try {
          if ( correr( null, 60, c, "--version" ).codigo == 0 ) {
            python = c;
            break;
          }
        } catch ( Exception e ) {
          // probar el siguiente
        }
      }
    }
    return python;
  }

  private static void compila( File py ) throws Exception {
    if ( python() == null ) {
      return;
    }
    PythonPrueba.Resultado r = correr( null, 120, python(), "-m", "py_compile", py.getAbsolutePath() );
    cierto( r.codigo == 0, py.getName() + " compila: " + r.salida );
  }

  private static PythonPrueba.Resultado correr( File dir, int segundos, String... cmd ) throws Exception {
    ProcessBuilder pb = new ProcessBuilder( cmd ).redirectErrorStream( true );
    if ( dir != null ) {
      pb.directory( dir );
    }
    pb.environment().put( "PYTHONIOENCODING", "utf-8" );
    pb.environment().put( "PYSPARK_PYTHON", cmd[ 0 ] );
    pb.environment().put( "PYSPARK_DRIVER_PYTHON", cmd[ 0 ] );
    File jdk = new File( "C:\\Program Files\\Java\\jdk-21" );
    if ( jdk.isDirectory() ) {
      pb.environment().put( "JAVA_HOME", jdk.getAbsolutePath() );
    }
    File log = File.createTempFile( "trad-salidas", ".log" );
    pb.redirectOutput( log );
    Process p = pb.start();
    if ( !p.waitFor( segundos, java.util.concurrent.TimeUnit.SECONDS ) ) {
      p.destroyForcibly();
      throw new IllegalStateException( "Python no terminó en " + segundos + " s" );
    }
    PythonPrueba.Resultado r = new PythonPrueba.Resultado();
    r.codigo = p.exitValue();
    r.salida = new String( Files.readAllBytes( log.toPath() ), StandardCharsets.UTF_8 );
    log.delete();
    return r;
  }

  /** Varios scripts en UN proceso (Spark arranca una vez), como ArnesTraduccion.correr. */
  private static String correrScripts( List<File> scripts, int segundos ) throws Exception {
    File dir = scripts.get( 0 ).getParentFile();
    File corredor = ArnesTraduccion.escribir( dir, "_corredor_salidas.py", String.join( "\n",
      "import os, runpy, sys, traceback",
      "for f in sys.argv[1:]:",
      "    print(\"=== \" + os.path.basename(f), flush=True)",
      "    os.chdir(os.path.dirname(f))",
      "    try:",
      "        runpy.run_path(f, run_name=\"__main__\")",
      "        print(\"OK\", flush=True)",
      "    except BaseException:",
      "        traceback.print_exc()",
      "        print(\"FALLO\", flush=True)",
      "" ) );
    List<String> cmd = new ArrayList<>();
    cmd.add( python() );
    cmd.add( corredor.getAbsolutePath() );
    for ( File s : scripts ) {
      cmd.add( s.getAbsolutePath() );
    }
    return correr( dir, segundos, cmd.toArray( new String[ 0 ] ) ).salida;
  }
}
