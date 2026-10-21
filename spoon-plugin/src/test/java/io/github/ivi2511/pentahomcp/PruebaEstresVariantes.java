package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.lanza;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.ivi2511.pentahomcp.robustez.Variantes;

/**
 * El generador de datos sucios de stress_test (docs/robustez-diseno.md §5.1), sin Kettle: qué inyecta cada variante,
 * el manifiesto y la escritura en texto, JSON y Excel.
 *
 * @dueño R2c
 */
public class PruebaEstresVariantes {

  private static final ObjectMapper OM = new ObjectMapper();

  private static Variantes.Tabla base() {
    Variantes.Tabla t = new Variantes.Tabla();
    t.campos.add( new Variantes.Campo( "id", "Integer", "#" ) );
    t.campos.add( new Variantes.Campo( "fecha", "Date", "yyyy-MM-dd" ) );
    t.campos.add( new Variantes.Campo( "cliente", "String", null ) );
    t.campos.add( new Variantes.Campo( "importe", "Number", "#.##" ) );
    t.filas.add( new String[] { "1", "2024-05-01", "Ana", "12.5" } );
    t.filas.add( new String[] { "2", "2024-05-02", "Luis", "8" } );
    t.filas.add( new String[] { "3", "2024-05-03", "Marta; la de arriba", "1.25" } );
    return t;
  }

  private static File dir( String sub ) {
    File d = new File( new File( EntornoKettle.carpeta(), "estres-variantes" ), sub );
    d.mkdirs();
    return d;
  }

  public void pruebaCombinadaInyectaTodoYConservaLaBase() {
    Variantes.Variante v = Variantes.generar( "combined", base(), null, true, ";" );
    igual( "1", v.filas.get( 0 ).valores[ 0 ], "la base va primero, intacta" );
    for ( String t : Variantes.TIPOS_FILA ) {
      cierto( v.inyectadas.containsKey( t ), "combined inyecta " + t + ": " + v.inyectadas );
    }
    // 4 campos vacíos de a uno + 1 fila vacía; 3 fechas malas; 2 numéricos x 3 números malos; 1 truncada
    igual( 5, v.inyectadas.get( "missing_fields" ), "missing_fields" );
    igual( 3, v.inyectadas.get( "bad_dates" ), "bad_dates" );
    igual( 6, v.inyectadas.get( "bad_numbers" ), "bad_numbers" );
    igual( 5 + 3 + 6 + 1, v.debenRechazarse(), "filas que deben rechazarse" );
    igual( 3 + v.inyectadas.values().stream().mapToInt( Integer::intValue ).sum(), v.datos(), "líneas de datos" );
    JsonNode m = v.manifiesto( OM );
    cierto( m.size() == v.datos() - 3, "el manifiesto anota solo las rotas: " + m.size() );
  }

  public void pruebaObligatoriosLimitanMissingFields() {
    Variantes.Variante v = Variantes.generar( "missing_fields", base(), Arrays.asList( "ID" ), true, ";" );
    igual( 2, v.inyectadas.get( "missing_fields" ), "solo id vacío + la fila vacía" );
  }

  public void pruebaTextoRoundTrip() throws Exception {
    Variantes.FormatoTexto fmt = new Variantes.FormatoTexto();
    fmt.separador = ";";
    fmt.encierro = "\"";
    Variantes.Tabla b = base();
    b.cabecera = Arrays.asList( "id", "fecha", "cliente", "importe" );
    Variantes.Variante v = Variantes.generar( "combined", b, null, true, ";" );
    File f = new File( dir( "texto" ), "combined.csv" );
    Variantes.escribirTexto( v, b, fmt, f );
    List<String> lineas = Files.readAllLines( f.toPath(), StandardCharsets.UTF_8 );
    igual( 1 + v.datos(), lineas.size(), "cabecera + datos" );
    igual( "id;fecha;cliente;importe", lineas.get( 0 ), "cabecera" );
    contiene( lineas.get( 3 ), "\"Marta; la de arriba\"", "el separador dentro de un valor va encerrado" );
    List<String> cab = new ArrayList<String>();
    List<String[]> leidas = Variantes.leerTexto( f, fmt, 3, cab );
    igual( 4, cab.size(), "cabecera leída" );
    igual( "Marta; la de arriba", leidas.get( 2 )[ 2 ], "el encierro se respeta al leer" );
    boolean truncada = false;
    for ( String l : lineas ) {
      truncada |= l.equals( "1;2024-05-01;" );
    }
    cierto( truncada, "hay una línea cortada tras el 2º separador" );
  }

  public void pruebaVaciosYCodificacion() throws Exception {
    Variantes.FormatoTexto fmt = new Variantes.FormatoTexto();
    Variantes.Tabla b = base();
    File vacio = new File( dir( "archivo" ), "vacio.csv" );
    Variantes.escribirTexto( Variantes.generar( "empty_file", b, null, true, ";" ), b, fmt, vacio );
    igual( 0L, vacio.length(), "empty_file = 0 bytes" );
    File cab = new File( dir( "archivo" ), "cabecera.csv" );
    Variantes.Variante hv = Variantes.generar( "header_only", b, null, true, ";" );
    Variantes.escribirTexto( hv, b, fmt, cab );
    igual( 1, Files.readAllLines( cab.toPath() ).size(), "header_only = una línea" );
    igual( 0, hv.datos(), "sin datos" );
    File enc = new File( dir( "archivo" ), "latin1.csv" );
    Variantes.escribirTexto( Variantes.generar( "wrong_encoding", b, null, true, ";" ), b, fmt, enc );
    String latin = new String( Files.readAllBytes( enc.toPath() ), StandardCharsets.ISO_8859_1 );
    contiene( latin, "Ñandú", "escrito en ISO-8859-1 (la entrada es UTF-8)" );
    falso( new String( Files.readAllBytes( enc.toPath() ), StandardCharsets.UTF_8 ).contains( "Ñandú" ),
      "en UTF-8 no se lee bien" );
  }

  public void pruebaJsonYExcel() throws Exception {
    Variantes.Tabla b = base();
    Variantes.Variante v = Variantes.generar( "missing_fields", b, null, false, null );
    File j = new File( dir( "json" ), "v.json" );
    Variantes.escribirJson( v, b, j, OM );
    JsonNode a = OM.readTree( j );
    igual( v.datos(), a.size(), "un objeto por fila" );
    falso( a.get( 3 ).has( "id" ), "en JSON, el campo que falta no tiene clave" );
    List<String[]> leidas = Variantes.leerJson( j, b.campos, 2, OM );
    igual( "Ana", leidas.get( 0 )[ 2 ], "JSON leído por clave" );

    File x = new File( dir( "excel" ), "v.xlsx" );
    Variantes.Variante ve = Variantes.generar( "bad_numbers", b, null, false, null );
    Variantes.escribirExcel( ve, b, "Datos", true, x );
    List<String> cab = new ArrayList<String>();
    List<String[]> filas = Variantes.leerExcel( x, "Datos", 0, true, 4, 100, cab );
    igual( "cliente", cab.get( 2 ), "cabecera en Excel" );
    igual( ve.datos(), filas.size(), "filas en Excel" );
    boolean mil = false;
    for ( String[] f : filas ) {
      mil |= "mil".equals( f[ 0 ] ) || "mil".equals( f[ 3 ] );
    }
    cierto( mil, "el número malo llega como texto a la celda" );
    falso( v.inyectadas.containsKey( "truncated_lines" ), "sin líneas cortadas fuera de texto" );
  }

  public void pruebaExpandir() {
    igual( Arrays.asList( "combined", "empty_file", "header_only" ), Variantes.expandir( null, false ),
      "por defecto, sin BD de archivo" );
    cierto( Variantes.expandir( null, true ).contains( "rerun" ), "rerun por defecto con BD de archivo" );
    List<String> todas = Variantes.expandir( Arrays.asList( "all" ), false );
    cierto( todas.containsAll( Variantes.TIPOS_FILA ) && todas.contains( "wrong_encoding" ) && todas.contains(
      "rerun" ), "all = todas: " + todas );
    lanza( IllegalArgumentException.class, () -> Variantes.expandir( Arrays.asList( "nope" ), false ),
      "variante desconocida" );
  }
}
