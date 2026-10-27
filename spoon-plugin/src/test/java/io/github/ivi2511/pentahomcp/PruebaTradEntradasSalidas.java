package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.ArnesTraduccion.hop;
import static io.github.ivi2511.pentahomcp.ArnesTraduccion.step;
import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.ivi2511.pentahomcp.traductor.Bloque;
import io.github.ivi2511.pentahomcp.traductor.Destino;
import io.github.ivi2511.pentahomcp.traductor.EscritorPy;
import io.github.ivi2511.pentahomcp.traductor.Estado;
import io.github.ivi2511.pentahomcp.traductor.Programa;

/**
 * Traductores de entradas (0.8.0, parte A): cada caso es una transformación mínima que lee un archivo o una base
 * creada aquí; se corre en Kettle capturando las filas del step y se compara con el volcado del script generado
 * (pandas y PySpark, todos los casos de cada destino en un solo proceso de Python).
 */
public class PruebaTradEntradasSalidas {

  private static final String DIR = "${Internal.Transformation.Filename.Directory}";

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  private static String campo( String nombre, String tipo, String formato, String dec, String grupo, String trim ) {
    return "<field><name>" + nombre + "</name><type>" + tipo + "</type><format>" + formato + "</format><currency/>"
      + "<decimal>" + dec + "</decimal><group>" + grupo + "</group><length>-1</length><precision>-1</precision>"
      + "<trim_type>" + trim + "</trim_type></field>";
  }

  /** Los casos: nombre → { ktr, step a comparar }. */
  private static Map<String, String[]> casos( File d ) throws Exception {
    Map<String, String[]> r = new LinkedHashMap<>();
    ArnesTraduccion.escribir( d, "datos.csv", "id;nombre;fecha;importe;activo\n1; Ana ;05/01/2024;1.234,50;Y\n"
      + "2;Luis;;7,25;N\n3;\"Eva; M\";31/12/2023;;\n" );
    String camposCsv = "<fields>" + campo( "id", "Integer", "#", "", "", "none" ) + campo( "nombre", "String", "", "",
      "", "both" ) + campo( "fecha", "Date", "dd/MM/yyyy", "", "", "none" ) + campo( "importe", "Number", "#,##0.00",
        ",", ".", "none" ) + campo( "activo", "Boolean", "", "", "", "none" ) + "</fields>";
    r.put( "csv", new String[] { ArnesTraduccion.ktr( "csv", null, "", step( "Leer CSV", "CsvInput",
      "<filename>" + DIR + "/datos.csv</filename><filename_field/><rownum_field>fila</rownum_field>"
        + "<include_filename>N</include_filename><separator>;</separator><enclosure>\"</enclosure><header>Y</header>"
        + "<buffer_size>50000</buffer_size><lazy_conversion>Y</lazy_conversion><add_filename_result>N"
        + "</add_filename_result><parallel>N</parallel><newline_possible>N</newline_possible><encoding>UTF-8"
        + "</encoding>" + camposCsv ) ), "Leer CSV" } );

    r.put( "grilla", new String[] { ArnesTraduccion.ktr( "grilla", null, "", step( "Datos", "DataGrid",
      "<fields>" + campo( "n", "Integer", "", "", "", "none" ).replace( "</field>",
        "<set_empty_string>N</set_empty_string></field>" ) + campo( "x", "Number", "", ".", "", "none" ) + campo( "f",
          "Date", "yyyy-MM-dd", "", "", "none" ) + campo( "t", "String", "", "", "", "none" ) + "</fields><data>"
        + "<line><item>10</item><item>2.5</item><item>2024-02-29</item><item>hola</item></line>"
        + "<line><item>-3</item><item/><item/><item>adiós</item></line></data>" ) ), "Datos" } );

    r.put( "generar", new String[] { ArnesTraduccion.ktr( "generar", null, "", step( "Filas", "RowGenerator",
      "<fields>" + campo( "pais", "String", "", "", "", "none" ).replace( "<length>", "<nullif>ES</nullif><length>" )
        + campo( "n", "Integer", "", "", "", "none" ).replace( "<length>", "<nullif>7</nullif><length>" )
        + "</fields><limit>3</limit><never_ending>N</never_ending>" ) ), "Filas" } );

    File sub = new File( d, "partes" );
    sub.mkdirs();
    ArnesTraduccion.escribir( sub, "p1.txt", "a,b\nx,1\ny,2\n" );
    ArnesTraduccion.escribir( sub, "p2.txt", "a,b\nz,3\n" );
    ArnesTraduccion.escribir( sub, "otro.dat", "a,b\nNO,9\n" );
    r.put( "texto", new String[] { ArnesTraduccion.ktr( "texto", null, "", step( "Texto", "TextFileInput",
      "<accept_filenames>N</accept_filenames><separator>,</separator><enclosure>\"</enclosure><header>Y</header>"
        + "<nr_headerlines>1</nr_headerlines><footer>N</footer><nr_footerlines>1</nr_footerlines><noempty>Y</noempty>"
        + "<include>N</include><rownum>N</rownum><format>mixed</format><encoding>UTF-8</encoding>"
        + "<file><name>" + DIR + "/partes</name><filemask>p.*\\.txt</filemask><exclude_filemask/>"
        + "<file_required>N</file_required><include_subfolders>N</include_subfolders><type>CSV</type>"
        + "<compression>None</compression></file><fields>" + campo( "a", "String", "", "", "", "none" ) + campo( "b",
          "Integer", "", "", "", "none" ) + "</fields><limit>0</limit>" ) ), "Texto" } );

    File db = new File( d, "prueba.sqlite" );
    db.delete();
    try ( Connection c = DriverManager.getConnection( "jdbc:sqlite:" + db.getAbsolutePath() );
      Statement st = c.createStatement() ) {
      st.execute( "CREATE TABLE cliente (id INTEGER, nombre TEXT, saldo REAL)" );
      st.execute( "INSERT INTO cliente VALUES (1, 'Ana', 10.5), (2, 'Luis', NULL), (3, 'Eva', -2)" );
    }
    r.put( "tabla", new String[] { ArnesTraduccion.ktr( "tabla", ArnesTraduccion.sqlite( "base", DIR
      + "/prueba.sqlite" ), "", step( "Consulta", "TableInput", "<connection>base</connection><sql>SELECT id, nombre, "
        + "saldo FROM cliente WHERE id &gt;= ${MINIMO} ORDER BY id</sql><limit>0</limit><lookup/>"
        + "<execute_each_row>N</execute_each_row><variables_active>Y</variables_active>"
        + "<lazy_conversion_active>N</lazy_conversion_active>" ) ).replace( "<parameters></parameters>",
          "<parameters><parameter><name>MINIMO</name><default_value>2</default_value><description/></parameter>"
            + "</parameters>" ), "Consulta" } );

    ArnesTraduccion.escribir( d, "personas.json", "{\"personas\": [{\"nombre\": \"Ana\", \"edad\": 30}, "
      + "{\"nombre\": \"Luis\", \"edad\": 41}]}" );
    r.put( "json", new String[] { ArnesTraduccion.ktr( "json", null, "", step( "JSON", "JsonInput",
      "<include>N</include><include_field/><rownum>N</rownum><addresultfile>N</addresultfile><readurl>N</readurl>"
        + "<removeSourceField>N</removeSourceField><IsIgnoreEmptyFile>N</IsIgnoreEmptyFile><doNotFailIfNoFile>Y"
        + "</doNotFailIfNoFile><ignoreMissingPath>N</ignoreMissingPath><defaultPathLeafToNull>Y"
        + "</defaultPathLeafToNull><rownum_field/><file><name>" + DIR + "/personas.json</name><filemask/>"
        + "<exclude_filemask/><file_required>N</file_required><include_subfolders>N</include_subfolders></file>"
        + "<fields>" + campo( "nombre", "String", "", "", "", "none" ).replace( "<type>", "<path>$..nombre</path>"
          + "<type>" ) + campo( "edad", "Integer", "", "", "", "none" ).replace( "<type>", "<path>$..edad</path>"
            + "<type>" ) + "</fields><limit>0</limit><IsInFields>N</IsInFields><IsAFile>N</IsAFile><valueField/>" ) ),
      "JSON" } );

    ArnesTraduccion.escribir( d, "filas.xml", "<?xml version=\"1.0\"?><raiz><fila id=\"1\"><nombre>Ana</nombre>"
      + "</fila><fila id=\"2\"><nombre>Luis</nombre></fila></raiz>" );
    r.put( "xml", new String[] { ArnesTraduccion.ktr( "xml", null, "", step( "XML", "getXMLData",
      "<include>N</include><include_field/><rownum>N</rownum><addresultfile>N</addresultfile><namespaceaware>N"
        + "</namespaceaware><ignorecomments>N</ignorecomments><readurl>N</readurl><validating>N</validating>"
        + "<usetoken>N</usetoken><IsIgnoreEmptyFile>N</IsIgnoreEmptyFile><doNotFailIfNoFile>Y</doNotFailIfNoFile>"
        + "<rownum_field/><encoding>UTF-8</encoding><file><name>" + DIR + "/filas.xml</name><filemask/>"
        + "<exclude_filemask/><file_required>N</file_required><include_subfolders>N</include_subfolders></file>"
        + "<fields><field><name>id</name><xpath>@id</xpath><element_type>attribute</element_type>"
        + "<result_type>valueof</result_type><type>Integer</type><format/><currency/><decimal/><group/>"
        + "<length>-1</length><precision>-1</precision><trim_type>none</trim_type><repeat>N</repeat></field>"
        + "<field><name>nombre</name><xpath>nombre</xpath><element_type>node</element_type>"
        + "<result_type>valueof</result_type><type>String</type><format/><currency/><decimal/><group/>"
        + "<length>-1</length><precision>-1</precision><trim_type>none</trim_type><repeat>N</repeat></field>"
        + "</fields><limit>0</limit><loopxpath>/raiz/fila</loopxpath><IsInFields>N</IsInFields><IsAFile>N</IsAFile>"
        + "<XmlField/><prunePath/>" ) ), "XML" } );

    return r;
  }

  public void pruebaEquivalenciaEntradas() throws Exception {
    File d = EntornoKettle.salida( "trad-entradas" );
    Map<String, String[]> casos = casos( d );
    Map<String, List<String>> esperado = new LinkedHashMap<>();
    for ( Map.Entry<String, String[]> e : casos.entrySet() ) {
      File ktr = ArnesTraduccion.escribir( d, e.getKey() + ".ktr", e.getValue()[ 0 ] );
      esperado.put( e.getKey(), ArnesTraduccion.kettle( ktr, e.getValue()[ 1 ] ) );
    }
    if ( PythonPrueba.python() == null ) {
      Verificador.saltear( "sin Python" );
    }
    boolean spark = PythonPrueba.tiene( "pyspark" );
    for ( Destino dest : spark ? Destino.values() : new Destino[] { Destino.PANDAS } ) {
      List<File> scripts = new ArrayList<>();
      for ( Map.Entry<String, String[]> e : casos.entrySet() ) {
        File ktr = new File( d, e.getKey() + ".ktr" );
        Programa p = ArnesTraduccion.traducir( ktr, dest, e.getValue()[ 1 ] );
        for ( Bloque b : p.bloques ) {
          cierto( b.estado != Estado.MANUAL, e.getKey() + " " + dest + ": " + b.tipo + " no es MANUAL: " + b.notas );
          cierto( b.lee || "DataGrid".equals( b.tipo ) || "RowGenerator".equals( b.tipo ), b.tipo + " marca lee" );
        }
        File py = ArnesTraduccion.script( ktr, dest );
        scripts.add( py );
        ArnesTraduccion.volcado( ktr, dest, e.getValue()[ 1 ] ).delete();
      }
      String salida = ArnesTraduccion.correr( scripts, 900 );
      for ( Map.Entry<String, String[]> e : casos.entrySet() ) {
        File ktr = new File( d, e.getKey() + ".ktr" );
        File py = ArnesTraduccion.script( ktr, dest );
        List<String> real = ArnesTraduccion.leer( ArnesTraduccion.volcado( ktr, dest, e.getValue()[ 1 ] ) );
        String suya = ArnesTraduccion.salidaDe( salida, py );
        if ( real == null && dest == Destino.PYSPARK && ( suya.contains( "No suitable driver" ) || suya.contains(
          "ClassNotFoundException" ) ) ) {
          System.out.println( "SALTEADO " + e.getKey() + " (PySpark): falta el .jar del driver JDBC en Spark" );
          continue;
        }
        ArnesTraduccion.comparar( esperado.get( e.getKey() ), real, e.getKey() + " (" + dest + ")\n"
          + ArnesTraduccion.salidaDe( salida, py ) );
      }
    }
  }

  /** Las conexiones nunca llevan la contraseña ni rutas resueltas de esta máquina; sin usuario no hay "user". */
  public void pruebaConexiones() throws Exception {
    File d = EntornoKettle.salida( "trad-entradas" );
    File ktr = ArnesTraduccion.escribir( d, "conexion.ktr", ArnesTraduccion.ktr( "conexion", ArnesTraduccion.sqlite(
      "base", DIR + "/prueba.sqlite" ) + "<connection><name>pg</name><server>${PG_HOST}</server><type>POSTGRESQL"
        + "</type><access>Native</access><database>ventas</database><port>5432</port><username>lector</username>"
        + "<password>Encrypted 2be98afc86aa7f2e4cb79ce10df90acde</password></connection>", "", step( "A",
          "TableInput", "<connection>base</connection><sql>SELECT 1 AS x</sql><limit>0</limit>" ), step( "B",
            "TableInput", "<connection>pg</connection><sql>SELECT 1 AS x</sql><limit>0</limit>" ) ) );
    for ( Destino dest : Destino.values() ) {
      Programa p = io.github.ivi2511.pentahomcp.traductor.TraductorTrans.traducir( new org.pentaho.di.trans.TransMeta(
        ktr.getAbsolutePath() ), dest, "es" );
      String py = EscritorPy.escribir( p );
      noContiene( py, d.getAbsolutePath().replace( '\\', '/' ), dest + ": sin la ruta local" );
      noContiene( py, "2be98afc", dest + ": sin la contraseña" );
      noContiene( py, "None}", dest + ": sin user None" );
      contiene( py, "PDI_PASSWORD_PG", dest + ": contraseña de variable de entorno" );
      if ( dest == Destino.PYSPARK ) {
        contiene( py, "jdbc:postgresql://{VARS['PG_HOST']}:5432/ventas", "url JDBC con la variable" );
        contiene( py, "jdbc:sqlite:{VARS['Internal.Transformation.Filename.Directory']}/prueba.sqlite",
          "url sqlite relativa al script" );
      }
      File f = new File( d, "conexion_" + dest.id() + ".py" );
      java.nio.file.Files.write( f.toPath(), py.getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
      PythonPrueba.compila( f );
    }
  }
}
