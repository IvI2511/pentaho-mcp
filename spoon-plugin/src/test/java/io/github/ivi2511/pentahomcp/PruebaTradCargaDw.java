package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.saltear;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.github.ivi2511.pentahomcp.traductor.Bloque;
import io.github.ivi2511.pentahomcp.traductor.Destino;
import io.github.ivi2511.pentahomcp.traductor.Estado;
import io.github.ivi2511.pentahomcp.traductor.Programa;
import io.github.ivi2511.pentahomcp.traductor.TradFormatosAcceso;

/**
 * Lo que hace falta para una carga de un data warehouse como la de un ejercicio de clase (dimensiones con Insert /
 * update y rechazos a archivos de texto), comparado con Kettle: filas que la base rechaza al hop de error, archivos de
 * rechazos sin filas y en una carpeta nueva, fechas y DECIMAL leídos de la base y unidos con otra rama, y el motivo de
 * rechazo armado en JavaScript con un array (push condicionales y join).
 */
public class PruebaTradCargaDw {

  private static final String DIR = "${Internal.Transformation.Filename.Directory}";

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  private static String campo( String nombre, String tipo, String formato ) {
    return "<field><name>" + nombre + "</name><type>" + tipo + "</type><format>" + formato + "</format><currency/>"
      + "<decimal>.</decimal><group/><length>-1</length><precision>-1</precision>"
      + "<set_empty_string>N</set_empty_string></field>";
  }

  private static String grilla( String nombre, String[] campos, String[][] filas ) {
    StringBuilder b = new StringBuilder( "<fields>" );
    for ( String c : campos ) {
      b.append( c );
    }
    b.append( "</fields><data>" );
    for ( String[] f : filas ) {
      b.append( "<line>" );
      for ( String v : f ) {
        b.append( v == null ? "<item/>" : "<item>" + v + "</item>" );
      }
      b.append( "</line>" );
    }
    return ArnesTraduccion.step( nombre, "DataGrid", b.append( "</data>" ).toString() );
  }

  private static String textoSalida( String nombre, String archivo ) {
    return ArnesTraduccion.step( nombre, "TextFileOutput", "<separator>;</separator><enclosure>\"</enclosure>"
      + "<enclosure_forced>N</enclosure_forced><enclosure_fix_disabled>N</enclosure_fix_disabled><header>Y</header>"
      + "<footer>N</footer><format>UNIX</format><compression>None</compression><encoding>UTF-8</encoding>"
      + "<fileNameInField>N</fileNameInField><create_parent_folder>Y</create_parent_folder><file><name>" + DIR + "/"
      + archivo + "</name><extention>csv</extention><append>N</append><split>N</split><haspartno>N</haspartno>"
      + "<add_date>N</add_date><add_time>N</add_time><SpecifyFormat>N</SpecifyFormat><pad>N</pad>"
      + "<fast_dump>N</fast_dump><do_not_open_new_file_init>N</do_not_open_new_file_init></file><fields/>" );
  }

  private static void sql( File db, String... sentencias ) throws Exception {
    try ( Connection c = DriverManager.getConnection( "jdbc:sqlite:" + db.getAbsolutePath() );
          Statement st = c.createStatement() ) {
      for ( String s : sentencias ) {
        st.execute( s );
      }
    }
  }

  private static List<String> tabla( File db, String consulta ) throws Exception {
    List<String> r = new ArrayList<>();
    try ( Connection c = DriverManager.getConnection( "jdbc:sqlite:" + db.getAbsolutePath() );
          Statement st = c.createStatement(); ResultSet rs = st.executeQuery( consulta ) ) {
      while ( rs.next() ) {
        List<String> f = new ArrayList<>();
        for ( int i = 1; i <= rs.getMetaData().getColumnCount(); i++ ) {
          f.add( String.valueOf( rs.getObject( i ) ) );
        }
        r.add( String.join( "|", f ) );
      }
    }
    return r;
  }

  private static String leer( File f ) throws Exception {
    return f.isFile() ? new String( Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 ) : "<no existe>";
  }

  /** Las líneas del archivo sin la última columna (el motivo: PDI pone el texto de la excepción de JDBC). */
  private static List<String> sinMotivo( File f ) throws Exception {
    List<String> r = new ArrayList<>();
    for ( String l : leer( f ).split( "\n" ) ) {
      if ( l.contains( ";" ) && !l.startsWith( "org.pentaho" ) ) {
        r.add( l.substring( 0, l.lastIndexOf( ';' ) ) );
      }
    }
    return r;
  }

  private static void borrarCarpeta( File d ) {
    File[] hijos = d.listFiles();
    if ( hijos != null ) {
      for ( File h : hijos ) {
        borrarCarpeta( h );
      }
    }
    d.delete();
  }

  private static String correrPython( File py ) throws Exception {
    String salida = ArnesTraduccion.correr( Collections.singletonList( py ), 300 );
    String suya = ArnesTraduccion.salidaDe( salida, py );
    cierto( suya.contains( "OK" ) && !suya.contains( "FALLO" ), py.getName() + " corre:\n" + suya );
    return suya;
  }

  // ================================================================ Insert / update con hop de error

  /**
   * La base rechaza una fila (CHECK, como un varchar(n) de MySQL en modo estricto): en PDI va al hop de error con su
   * motivo y las demás se cargan y siguen; antes Python cortaba en la primera fila rechazada. Además: archivo de
   * rechazos en una carpeta que no existe (create_parent_folder) y otro sin filas (PDI lo deja vacío, sin cabecera).
   */
  public void pruebaInsertUpdateConRechazos() throws Exception {
    if ( PythonPrueba.python() == null || !PythonPrueba.tiene( "sqlalchemy" ) ) {
      saltear( "sin Python con pandas y sqlalchemy" );
      return;
    }
    File dir = EntornoKettle.salida( "trad-carga-dw" );
    final File db = new File( dir, "dw1.sqlite" );
    String[] campos = { campo( "id", "Integer", "" ), campo( "nombre", "String", "" ) };
    String[][] filas = { { "1", "Ana" }, { "2", "Nombre largo" }, { "3", "Eva" }, { "4", "Luis" }, { "1", "Ana B" },
      { "5", "Demasiado" } };
    String errores = "<step_error_handling><error><source_step>Cargar</source_step><target_step>Rechazos"
      + "</target_step><is_enabled>Y</is_enabled><nr_valuename/><descriptions_valuename>motivo"
      + "</descriptions_valuename><fields_valuename/><codes_valuename/><max_errors/><max_pct_errors/>"
      + "<min_pct_rows/></error></step_error_handling>";
    File ktr = ArnesTraduccion.escribir( dir, "carga1.ktr", ArnesTraduccion.ktr( "carga1", ArnesTraduccion.sqlite(
      "dw", DIR + "/dw1.sqlite" ) + errores, ArnesTraduccion.hop( "Datos", "Cargar" ) + ArnesTraduccion.hop(
        "Datos", "Ninguno" ) + ArnesTraduccion.hop( "Cargar", "Siguen" ) + ArnesTraduccion.hop( "Cargar", "Rechazos" )
      + ArnesTraduccion.hop( "Ninguno", "Vacio" ),
      grilla( "Datos", campos, filas ).replace( "<distribute>Y</distribute>", "<distribute>N</distribute>" ),
      ArnesTraduccion.step( "Cargar", "InsertUpdate", "<connection>dw</connection><commit>100</commit>"
        + "<update_bypassed>N</update_bypassed><lookup><schema/><table>clientes</table>"
        + "<key><name>id</name><field>id</field><condition>=</condition><name2/></key>"
        + "<value><name>id</name><rename>id</rename><update>N</update></value>"
        + "<value><name>nombre</name><rename>nombre</rename><update>Y</update></value></lookup>" ),
      ArnesTraduccion.step( "Siguen", "Dummy", "" ),
      textoSalida( "Rechazos", "rechazos_nuevos/rechazos" ),
      ArnesTraduccion.step( "Ninguno", "FilterRows", "<compare><condition><negated>N</negated><leftvalue>id"
        + "</leftvalue><function>&gt;</function><rightvalue/><value><name>constant</name><type>Integer</type>"
        + "<text>100</text><length>-1</length><precision>0</precision><isnull>N</isnull><mask>####0;-####0</mask>"
        + "</value></condition></compare>" ),
      textoSalida( "Vacio", "vacios_nuevos/vacio" ) ) );
    String crear = "CREATE TABLE clientes (id INTEGER PRIMARY KEY, nombre VARCHAR(5), CHECK (length(nombre) <= 5))";
    File rech = new File( dir, "rechazos_nuevos/rechazos.csv" );
    File vacio = new File( dir, "vacios_nuevos/vacio.csv" );

    db.delete();
    sql( db, crear );
    borrarCarpeta( rech.getParentFile() );
    borrarCarpeta( vacio.getParentFile() );
    List<String> kettle = ArnesTraduccion.kettle( ktr, "Siguen" );
    List<String> tablaK = tabla( db, "SELECT id, nombre FROM clientes ORDER BY id" );
    List<String> rechK = sinMotivo( rech );
    String motivoK = leer( rech );
    igual( "", leer( vacio ), "PDI deja vacío el archivo sin filas (ni cabecera)" );
    igual( Arrays.asList( "id;nombre", "2;Nombre largo", "5;Demasiado" ), rechK, "PDI: rechazadas" );
    contiene( motivoK, "CHECK constraint failed", "PDI: el motivo es el error de la base" );

    db.delete();
    sql( db, crear );
    borrarCarpeta( rech.getParentFile() );
    borrarCarpeta( vacio.getParentFile() );
    Programa p = ArnesTraduccion.traducir( ktr, Destino.PANDAS, "Siguen" );
    for ( Bloque b : p.bloques ) {
      igual( Estado.EXACTO, b.estado, b.paso + ": " + b.notas );
    }
    String py = leer( ArnesTraduccion.script( ktr, Destino.PANDAS ) );
    contiene( py, "errores=True", "el Insert / update separa las filas con error" );
    contiene( py, "carpeta=True", "crea la carpeta de los rechazos" );
    correrPython( ArnesTraduccion.script( ktr, Destino.PANDAS ) );
    igual( tablaK, tabla( db, "SELECT id, nombre FROM clientes ORDER BY id" ), "la tabla, como en Kettle" );
    ArnesTraduccion.comparar( kettle, ArnesTraduccion.leer( ArnesTraduccion.volcado( ktr, Destino.PANDAS,
      "Siguen" ) ), "siguen las filas que la base aceptó" );
    igual( rechK, sinMotivo( rech ), "los rechazos, como en Kettle" );
    contiene( leer( rech ), "CHECK constraint failed", "Python: el motivo es el error de la base" );
    igual( "", leer( vacio ), "Python también deja vacío el archivo sin filas" );
  }

  // ================================================================ fechas y DECIMAL de la base

  /**
   * Un Table input con una fecha (SQLite la da como texto; MySQL como date de Python) unido a otra rama con fechas de
   * pandas: antes la columna quedaba de tipo object y «.dt» del Calculator fallaba.
   */
  public void pruebaFechasDeLaBase() throws Exception {
    if ( PythonPrueba.python() == null || !PythonPrueba.tiene( "sqlalchemy" ) ) {
      saltear( "sin Python con pandas y sqlalchemy" );
      return;
    }
    File dir = EntornoKettle.salida( "trad-carga-dw" );
    File db = new File( dir, "origen2.sqlite" );
    db.delete();
    sql( db, "CREATE TABLE ventas (fecha DATE, importe DECIMAL(10,2))", "INSERT INTO ventas VALUES "
      + "('2020-01-15 00:00:00.000', 10.5), ('2021-07-04 00:00:00.000', 3.25), (NULL, 1.0)" );
    File ktr = ArnesTraduccion.escribir( dir, "fechas2.ktr", ArnesTraduccion.ktr( "fechas2", ArnesTraduccion.sqlite(
      "origen", DIR + "/origen2.sqlite" ), ArnesTraduccion.hop( "Leer", "Unir" ) + ArnesTraduccion.hop( "Extra",
        "Unir" ) + ArnesTraduccion.hop( "Unir", "Dia" ) + ArnesTraduccion.hop( "Dia", "Fin" ),
      ArnesTraduccion.step( "Leer", "TableInput", "<connection>origen</connection><sql>SELECT fecha, "
        + "CAST(importe AS REAL) AS importe FROM ventas ORDER BY importe DESC</sql><limit>0</limit>"
        + "<execute_each_row>N</execute_each_row><variables_active>N</variables_active>" ),
      grilla( "Extra", new String[] { campo( "fecha", "Date", "yyyy-MM-dd" ), campo( "importe", "Number", "" ) },
        new String[][] { { "2022-03-09", "7.5" } } ),
      ArnesTraduccion.step( "Unir", "Append", "<head_name>Leer</head_name><tail_name>Extra</tail_name>" ),
      ArnesTraduccion.step( "Dia", "Calculator", "<calculation><field_name>dia</field_name><calc_type>DAY_OF_MONTH"
        + "</calc_type><field_a>fecha</field_a><field_b/><field_c/><value_type>Integer</value_type><value_length>-1"
        + "</value_length><value_precision>-1</value_precision><remove>N</remove></calculation><calculation>"
        + "<field_name>anio</field_name><calc_type>YEAR_OF_DATE</calc_type><field_a>fecha</field_a><field_b/>"
        + "<field_c/><value_type>Integer</value_type><value_length>-1</value_length><value_precision>-1"
        + "</value_precision><remove>N</remove></calculation>" ),
      ArnesTraduccion.step( "Fin", "Dummy", "" ) ) );
    List<String> kettle = ArnesTraduccion.kettle( ktr, "Fin" );
    ArnesTraduccion.traducir( ktr, Destino.PANDAS, "Fin" );
    String py = leer( ArnesTraduccion.script( ktr, Destino.PANDAS ) );
    contiene( py, "_tipos_sql(df_leer, fechas=[\"fecha\"])", "el Table input pasa las fechas a datetime64" );
    correrPython( ArnesTraduccion.script( ktr, Destino.PANDAS ) );
    ArnesTraduccion.comparar( kettle, ArnesTraduccion.leer( ArnesTraduccion.volcado( ktr, Destino.PANDAS, "Fin" ) ),
      "fechas de la base + otra rama + Calculator" );
    // como con MySQL: pymysql da las fechas como date de Python y los DECIMAL como Decimal (columnas object)
    File volcado = ArnesTraduccion.volcado( ktr, Destino.PANDAS, "Fin" );
    volcado.delete();
    File mysql = new File( dir, "como_mysql.py" );
    Files.write( mysql.toPath(), String.join( "\n", "import datetime, decimal, runpy, sys", "import pandas as pd",
      "_leer = pd.read_sql_query", "def _como_mysql(*a, **k):", "    df = _leer(*a, **k)",
      "    df[\"fecha\"] = pd.Series([None if v is None else datetime.date.fromisoformat(str(v)[:10])",
      "                             for v in df[\"fecha\"]], index=df.index, dtype=object)",
      "    df[\"importe\"] = pd.Series([decimal.Decimal(str(v)) for v in df[\"importe\"]], index=df.index, dtype=object)",
      "    return df", "pd.read_sql_query = _como_mysql", "runpy.run_path(sys.argv[1], run_name=\"__main__\")", "" )
      .getBytes( StandardCharsets.UTF_8 ) );
    PythonPrueba.Resultado r = PythonPrueba.correr( dir, 120, PythonPrueba.python(), mysql.getPath(),
      ArnesTraduccion.script( ktr, Destino.PANDAS ).getPath() );
    igual( 0, r.codigo, "corre con los tipos de MySQL: " + r.salida );
    ArnesTraduccion.comparar( kettle, ArnesTraduccion.leer( volcado ), "con date de Python y Decimal (MySQL)" );
  }

  /** Lo que da pymysql (date de Python y Decimal): _tipos_sql lo deja como datetime64 y float, y se puede sumar. */
  public void pruebaTiposComoMysql() throws Exception {
    if ( !PythonPrueba.tiene( "pandas" ) ) {
      saltear( "sin pandas" );
      return;
    }
    String py = String.join( "\n", "import datetime, decimal", "import numpy as np", "import pandas as pd", "",
      TradFormatosAcceso.tiposSql(), "",
      "a = pd.DataFrame({\"f\": [datetime.date(2008, 1, 10), None], \"p\": [decimal.Decimal(\"18.90\"), None],",
      "                  \"g\": [decimal.Decimal(\"1.5\"), decimal.Decimal(\"2\")], \"t\": [\"x\", \"y\"]})",
      "a = _tipos_sql(a, fechas=[\"f\"], grandes=[\"g\"])",
      "b = pd.DataFrame({\"f\": pd.to_datetime([\"2009-05-01\"]), \"p\": [2.1], \"g\": [decimal.Decimal(\"1\")],",
      "                  \"t\": [\"x\"]})",
      "u = pd.concat([a, b], ignore_index=True)",
      "s = u.groupby(\"t\", sort=False).agg(p=(\"p\", \"sum\"), g=(\"g\", \"sum\"))",
      "print(\"R=\", u[\"f\"].dt.day.tolist(), round(s.loc[\"x\", \"p\"], 6), s.loc[\"x\", \"g\"], str(a[\"p\"].dtype),"
        + " type(a.loc[0, \"g\"]).__name__)", "" );
    File f = new File( EntornoKettle.salida( "trad-carga-dw" ), "tipos_mysql.py" );
    Files.write( f.toPath(), py.getBytes( StandardCharsets.UTF_8 ) );
    PythonPrueba.Resultado r = PythonPrueba.correr( f.getParentFile(), 120, PythonPrueba.python(), f.getPath() );
    igual( 0, r.codigo, "corre: " + r.salida );
    contiene( r.salida, "R= [10.0, nan, 1.0] 21.0 2.5 float64 Decimal", "fechas y DECIMAL como en PDI: " + r.salida );
  }

  // ================================================================ JavaScript: array con push y join

  private static final String SCRIPT = "// lista lo que falta\nvar faltan = [];\nif (a == null) faltan.push(\"sin a\");"
    + "\nelse if (b == null) faltan.push(\"a=\" + a + \" sin b\");\nif (c == null) faltan.push(\"sin c\");\n"
    + "var motivo = \"Problemas: \" + faltan.join(\"; \");\nvar lista = faltan.join();";

  /** El motivo de rechazo armado con un array de JavaScript (antes MANUAL: el carácter «[» no se traducía). */
  public void pruebaJavascriptArrays() throws Exception {
    File dir = EntornoKettle.salida( "trad-carga-dw" );
    String js = "<compatible>N</compatible><optimizationLevel>9</optimizationLevel><jsScripts><jsScript>"
      + "<jsScript_type>0</jsScript_type><jsScript_name>Script 1</jsScript_name><jsScript_script>" + SCRIPT
      .replace( "&", "&amp;" ).replace( "<", "&lt;" ) + "</jsScript_script></jsScript></jsScripts><fields>"
      + "<field><name>motivo</name><rename>motivo</rename><type>String</type><length>-1</length><precision>-1"
      + "</precision><replace>N</replace></field><field><name>lista</name><rename>lista</rename><type>String</type>"
      + "<length>-1</length><precision>-1</precision><replace>N</replace></field></fields>";
    File ktr = ArnesTraduccion.escribir( dir, "js_arrays.ktr", ArnesTraduccion.ktr( "js_arrays", null,
      ArnesTraduccion.hop( "Datos", "Motivo" ) + ArnesTraduccion.hop( "Motivo", "Fin" ),
      grilla( "Datos", new String[] { campo( "a", "String", "" ), campo( "b", "Integer", "" ), campo( "c", "String",
        "" ) }, new String[][] { { "x", "1", "z" }, { null, "2", "z" }, { "y", null, null }, { null, null, null },
          { "w", null, "v" } } ),
      ArnesTraduccion.step( "Motivo", "ScriptValueMod", js ), ArnesTraduccion.step( "Fin", "Dummy", "" ) ) );
    List<String> kettle = ArnesTraduccion.kettle( ktr, "Fin" );
    igual( "y\t<null>\t<null>\tProblemas: a=y sin b; sin c\ta=y sin b,sin c", kettle.get( 3 ), "Kettle, fila 3" );
    List<File> scripts = new ArrayList<>();
    for ( Destino d : Destino.values() ) {
      Programa p = ArnesTraduccion.traducir( ktr, d, "Fin" );
      igual( Estado.EXACTO, p.bloques.get( 1 ).estado, d.id() + ": el script se traduce " + p.bloques.get( 1 ).notas );
      contiene( leer( ArnesTraduccion.script( ktr, d ) ), d == Destino.PANDAS ? "_unir_si(" : "F.concat_ws(\"; \"",
        d.id() + ": el join" );
      if ( PythonPrueba.tiene( d == Destino.PANDAS ? "pandas" : "pyspark" ) ) {
        scripts.add( ArnesTraduccion.script( ktr, d ) );
      }
    }
    if ( scripts.isEmpty() ) {
      saltear( "sin Python con pandas" );
      return;
    }
    String salida = ArnesTraduccion.correr( scripts, 600 );
    for ( File py : scripts ) {
      String suya = ArnesTraduccion.salidaDe( salida, py );
      cierto( suya.contains( "OK" ) && !suya.contains( "FALLO" ), py.getName() + " corre:\n" + suya );
      Destino d = py.getName().contains( "pyspark" ) ? Destino.PYSPARK : Destino.PANDAS;
      ArnesTraduccion.comparar( kettle, ArnesTraduccion.leer( ArnesTraduccion.volcado( ktr, d, "Fin" ) ),
        py.getName() + ": motivo armado con el array, como en Kettle" );
    }
  }
}
