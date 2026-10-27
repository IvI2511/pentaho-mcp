package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.ArnesTraduccion.step;
import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.igual;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.ivi2511.pentahomcp.traductor.Bloque;
import io.github.ivi2511.pentahomcp.traductor.Destino;
import io.github.ivi2511.pentahomcp.traductor.Estado;
import io.github.ivi2511.pentahomcp.traductor.Programa;
import io.github.ivi2511.pentahomcp.traductor.TradFormatosAcceso;

/**
 * Formatos y codificaciones (0.8.0): máscaras de fecha (SimpleDateFormat → strftime), máscaras numéricas con
 * símbolos decimal y de agrupación, moneda y porcentaje, y archivos en UTF-8 con BOM y windows-1252. Cada caso se
 * corre en Kettle y se compara con el script generado (pandas y PySpark).
 */
public class PruebaTradFormatos {

  private static final String DIR = "${Internal.Transformation.Filename.Directory}";

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  /** La tabla de conversión de máscaras de fecha (una sola, compartida por entradas, campos y salidas). */
  public void pruebaMascarasFecha() {
    igual( TradFormatosAcceso.strftime( "yyyy-MM-dd'T'HH:mm:ss.SSS" ), "%Y-%m-%dT%H:%M:%S.%f", "ISO con T" );
    igual( TradFormatosAcceso.strftime( "dd/MM/yy hh:mm a" ), "%d/%m/%y %I:%M %p", "12 horas" );
    igual( TradFormatosAcceso.strftime( "EEE d MMM yyyy" ), "%a %d %b %Y", "nombres cortos" );
    igual( TradFormatosAcceso.strftime( "EEEE, d MMMM yyyy" ), "%A, %d %B %Y", "nombres largos" );
    igual( TradFormatosAcceso.strftime( "yyyyMMddHHmmssZ" ), "%Y%m%d%H%M%S%z", "zona Z" );
    igual( TradFormatosAcceso.strftime( "HH:mm X" ), "%H:%M %z", "zona X" );
    igual( TradFormatosAcceso.strftime( "'Hoy es' dd 'de' MM 'o''clock' 100%" ), "Hoy es %d de %m o'clock 100%%",
      "literales y %" );
    igual( TradFormatosAcceso.strftime( "yyyy''MM" ), "%Y'%m", "comilla escapada" );
    igual( TradFormatosAcceso.fechaPy( "" ), "", "vacía en salidas" );
  }

  private static String campo( String nombre, String tipo, String formato, String dec, String grupo ) {
    return "<field><name>" + nombre + "</name><type>" + tipo + "</type><format>" + formato + "</format><currency/>"
      + "<decimal>" + dec + "</decimal><group>" + grupo + "</group><length>-1</length><precision>-1</precision>"
      + "<trim_type>none</trim_type></field>";
  }

  private static String csv( String archivo, String sep, String cabecera, String enc, String campos ) {
    return "<filename>" + DIR + "/" + archivo + "</filename><filename_field/><rownum_field/>"
      + "<include_filename>N</include_filename><separator>" + sep + "</separator><enclosure>\"</enclosure><header>"
      + cabecera + "</header><buffer_size>50000</buffer_size><lazy_conversion>N</lazy_conversion>"
      + "<add_filename_result>N</add_filename_result><parallel>N</parallel><newline_possible>N</newline_possible>"
      + "<encoding>" + enc + "</encoding><fields>" + campos + "</fields>";
  }

  private static Map<String, String[]> casos( File d ) throws Exception {
    Map<String, String[]> r = new LinkedHashMap<>();
    // UTF-8 con BOM, sin cabecera: el BOM no debe acabar en el primer valor
    Files.write( new File( d, "bom.csv" ).toPath(), ( "﻿uno|2024-03-05T09:07:01|\"dice \"\"hola\"\"\"\n"
      + "dos|2023-12-31T23:59:59|x\n" ).getBytes( "UTF-8" ) );
    r.put( "bom", new String[] { ArnesTraduccion.ktr( "bom", null, "", step( "Leer", "CsvInput", csv( "bom.csv", "|",
      "N", "UTF-8", campo( "clave", "String", "", "", "" ) + campo( "cuando", "Date", "yyyy-MM-dd'T'HH:mm:ss", "",
        "" ) + campo( "texto", "String", "", "", "" ) ) ) ), "Leer" } );
    // windows-1252, separador ;, decimal "," y agrupación ".", moneda y porcentaje
    Files.write( new File( d, "ansi.csv" ).toPath(), ( "nombre;importe;tasa;fecha\nMuñoz;1.234,50 €;12,5%;5/1/24 "
      + "9:05\nPeña;-7,25 €;100,0%;31/12/23 23:59\n" ).getBytes( Charset.forName( "windows-1252" ) ) );
    r.put( "ansi", new String[] { ArnesTraduccion.ktr( "ansi", null, "", step( "Leer", "CsvInput", csv( "ansi.csv",
      ";", "Y", "windows-1252", campo( "nombre", "String", "", "", "" ) + campo( "importe", "Number", "#,##0.00 €",
        ",", "." ) + campo( "tasa", "Number", "0.0%", ",", "." ) + campo( "fecha", "Date", "d/M/yy H:mm", "",
          "" ) ) ) ), "Leer" } );
    // ISO-8859-1, tabulador
    Files.write( new File( d, "latin.csv" ).toPath(), ( "a\tb\nAçaí\t1,5\nÉlan\t\n" ).getBytes( "ISO-8859-1" ) );
    r.put( "latin", new String[] { ArnesTraduccion.ktr( "latin", null, "", step( "Leer", "CsvInput", csv(
      "latin.csv", "\t", "Y", "ISO-8859-1", campo( "a", "String", "", "", "" ) + campo( "b", "Number", "#.#", ",",
        "" ) ) ) ), "Leer" } );
    return r;
  }

  /** Equivalencia con Kettle en pandas y PySpark. */
  public void pruebaEquivalencia() throws Exception {
    File d = EntornoKettle.salida( "trad-formatos" );
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
          cierto( b.estado != Estado.MANUAL, e.getKey() + " " + dest + ": " + b.tipo + " no es MANUAL" );
        }
        scripts.add( ArnesTraduccion.script( ktr, dest ) );
        ArnesTraduccion.volcado( ktr, dest, e.getValue()[ 1 ] ).delete();
      }
      String salida = ArnesTraduccion.correr( scripts, 900 );
      for ( Map.Entry<String, String[]> e : casos.entrySet() ) {
        File ktr = new File( d, e.getKey() + ".ktr" );
        File py = ArnesTraduccion.script( ktr, dest );
        List<String> real = ArnesTraduccion.leer( ArnesTraduccion.volcado( ktr, dest, e.getValue()[ 1 ] ) );
        ArnesTraduccion.comparar( esperado.get( e.getKey() ), real, e.getKey() + " (" + dest + ")\n"
          + ArnesTraduccion.salidaDe( salida, py ) );
      }
    }
  }
}
