package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.ArnesTraduccion.hop;
import static io.github.ivi2511.pentahomcp.ArnesTraduccion.step;
import static io.github.ivi2511.pentahomcp.Verificador.cierto;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.ivi2511.pentahomcp.traductor.Bloque;
import io.github.ivi2511.pentahomcp.traductor.Destino;
import io.github.ivi2511.pentahomcp.traductor.Programa;

/**
 * Manejo de errores (hop de error) como PDI: una conversión de tipos que falla en Select values manda la fila al hop
 * de error con sus campos de error; se compara con Kettle (filas buenas y de error) en pandas y en PySpark.
 */
public class PruebaTradErrores {

  private static String campo( String nombre ) {
    return "<field><name>" + nombre + "</name><type>String</type><format/><currency/><decimal/><group/>"
      + "<length>-1</length><precision>-1</precision><trim_type>none</trim_type></field>";
  }

  private static String meta( String nombre, String tipo, String mascara ) {
    return "<meta><name>" + nombre + "</name><rename/><type>" + tipo + "</type><length>-2</length><precision>-2"
      + "</precision><conversion_mask>" + mascara + "</conversion_mask><date_format_lenient>false"
      + "</date_format_lenient><date_format_locale/><date_format_timezone/><lenient_string_to_number>false"
      + "</lenient_string_to_number><encoding/><decimal_symbol>.</decimal_symbol><grouping_symbol/><currency_symbol/><storage_type/>"
      + "</meta>";
  }

  private static String linea( String... v ) {
    StringBuilder b = new StringBuilder( "<line>" );
    for ( String x : v ) {
      b.append( x.isEmpty() ? "<item/>" : "<item>" + x + "</item>" );
    }
    return b.append( "</line>" ).toString();
  }

  private static String select() {
    String datos = step( "Datos", "DataGrid", "<fields>" + campo( "id" ) + campo( "n" ) + campo( "x" ) + campo( "f" )
      + "</fields><data>" + linea( "1", "10", "2.5", "2024-01-31" ) + linea( "2", "abc", "1", "2024-02-01" )
      + linea( "3", "5", "x1", "2024-02-02" ) + linea( "4", "7", "3", "2024-13-45" ) + linea( "5", "", "", "" )
      + linea( "6", "-8", "4.75", "2024-12-31" ) + "</data>" );
    String tipos = step( "Tipos", "SelectValues", "<fields><select_unspecified>N</select_unspecified>" + meta( "n",
      "Integer", "" ) + meta( "x", "Number", "" ) + meta( "f", "Date", "yyyy-MM-dd" ) + "</fields>" );
    String error = "<step_error_handling><error><source_step>Tipos</source_step><target_step>Errores</target_step>"
      + "<is_enabled>Y</is_enabled><nr_valuename>nerr</nr_valuename><descriptions_valuename/><fields_valuename/>"
      + "<codes_valuename>cod</codes_valuename><max_errors/><max_pct_errors/><min_pct_rows/></error>"
      + "</step_error_handling>";
    return ArnesTraduccion.ktr( "errores_select", null, hop( "Datos", "Tipos" ) + hop( "Tipos", "Buenas" ) + hop(
      "Tipos", "Errores" ), datos, tipos, step( "Buenas", "Dummy", "" ), step( "Errores", "Dummy", "" ), error );
  }

  public void pruebaSelectValues() throws Exception {
    File d = EntornoKettle.salida( "trad-errores" );
    File ktr = ArnesTraduccion.escribir( d, "errores_select.ktr", select() );
    Map<String, List<String>> esperado = new LinkedHashMap<>();
    for ( String paso : new String[] { "Buenas", "Errores" } ) {
      esperado.put( paso, ArnesTraduccion.kettle( ktr, paso ) );
    }
    System.out.println( "Kettle: " + esperado );
    if ( PythonPrueba.python() == null ) {
      Verificador.saltear( "sin Python" );
    }
    boolean spark = PythonPrueba.tiene( "pyspark" );
    for ( Destino dest : spark ? Destino.values() : new Destino[] { Destino.PANDAS } ) {
      Programa p = ArnesTraduccion.traducir( ktr, dest, "Buenas", "Errores" );
      for ( Bloque b : p.bloques ) {
        if ( "SelectValues".equals( b.tipo ) ) {
          cierto( b.codigo.toString().contains( "malo_" ), dest + ": separa las filas con error\n" + b.codigo );
        }
      }
      for ( String paso : esperado.keySet() ) {
        ArnesTraduccion.volcado( ktr, dest, paso ).delete();
      }
      List<File> scripts = new ArrayList<>();
      scripts.add( ArnesTraduccion.script( ktr, dest ) );
      String salida = ArnesTraduccion.correr( scripts, 900 );
      for ( Map.Entry<String, List<String>> e : esperado.entrySet() ) {
        List<String> real = ArnesTraduccion.leer( ArnesTraduccion.volcado( ktr, dest, e.getKey() ) );
        ArnesTraduccion.comparar( e.getValue(), real, e.getKey() + " (" + dest + ")\n" + salida );
      }
    }
  }
}
