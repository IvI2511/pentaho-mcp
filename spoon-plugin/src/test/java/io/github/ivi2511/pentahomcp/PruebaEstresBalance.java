package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.igual;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.ivi2511.pentahomcp.robustez.Balance;
import io.github.ivi2511.pentahomcp.robustez.EstresResultado;
import io.github.ivi2511.pentahomcp.robustez.EstresResultado.Corrida;

/**
 * Las comprobaciones de stress_test (docs/robustez-diseno.md §5.4) con corridas fijas en JSON, sin Kettle ni JVM
 * hija: pérdidas en un filtro sin rama falsa, descartes declarados, hops de error, flujos info, aborto, rechazos sin
 * motivo, entradas que se comen filas y rerun que duplica.
 *
 * @dueño R2c
 */
public class PruebaEstresBalance {

  private static final ObjectMapper OM = new ObjectMapper();

  private static Balance.Nodo nodo( String n, String tipo, String... previos ) {
    Balance.Nodo x = new Balance.Nodo( n, tipo );
    x.previos.addAll( Arrays.asList( previos ) );
    x.destinos = 1;
    return x;
  }

  /** {"name","type",received,put,put_error} → una corrida terminada sin errores. */
  private static Corrida corrida( boolean ok, Object[]... pasos ) throws Exception {
    StringBuilder sb = new StringBuilder( "{\"variant\":\"combined\",\"finished\":" + ok + ",\"errors\":" + ( ok ? 0
      : 1 ) + ",\"error_message\":" + ( ok ? "null" : "\"Unexpected error in Convert types\"" ) + ",\"steps\":[" );
    for ( int i = 0; i < pasos.length; i++ ) {
      Object[] p = pasos[ i ];
      sb.append( i > 0 ? "," : "" ).append( "{\"name\":\"" ).append( p[ 0 ] ).append( "\",\"type\":\"" ).append( p[ 1 ] )
        .append( "\",\"received\":" ).append( p[ 2 ] ).append( ",\"put\":" ).append( p[ 3 ] ).append( ",\"put_error\":" )
        .append( p[ 4 ] ).append( ",\"output\":" ).append( p.length > 5 ? p[ 5 ] : 0 ).append( ",\"errors\":" ).append(
          p.length > 6 ? p[ 6 ] : 0 ).append( "}" );
    }
    return Corrida.desde( OM.readTree( sb.append( "]}" ).toString() ) );
  }

  private static List<Balance.Nodo> lineal( String declaracion ) {
    Balance.Nodo in = nodo( "Read", "CsvInput" );
    in.previos.clear();
    in.entradaArchivo = true;
    Balance.Nodo f = nodo( "Check", "FilterRows", "Read" );
    f.declaracion = declaracion;
    Balance.Nodo w = nodo( "Write", "TextFileOutput", "Check" );
    w.carga = true;
    w.destinos = 0;
    return Arrays.asList( in, f, w );
  }

  private static String estado( Balance.Informe i, String id ) {
    return i.comprobacion( id ).estado;
  }

  public void pruebaFiltroSinRamaFalsaPierdeFilas() throws Exception {
    Corrida c = corrida( true, new Object[] { "Read", "CsvInput", 0, 10, 0 }, new Object[] { "Check", "FilterRows",
      10, 7, 0 }, new Object[] { "Write", "TextFileOutput", 7, 7, 0, 7 } );
    Balance.Informe i = Balance.evaluar( lineal( null ), c, Collections.singletonMap( "Read", 10 ), 3, null, null );
    igual( EstresResultado.FALLA, estado( i, "no_silent_loss" ), "pérdida" );
    contiene( i.comprobacion( "no_silent_loss" ).detalles.get( 0 ), "'Check' (FilterRows) lost 3 row(s)",
      "nombra el step y cuántas" );
    igual( 3L, i.perdidasTotal(), "3 perdidas" );
    igual( 10L, i.leidas, "leídas" );
    igual( 7L, i.cargadas, "cargadas" );
    contiene( i.resumen(), "10 read = 7 loaded + 0 rejected + 0 dropped (declared) + 3 lost", "resumen" );
    igual( EstresResultado.AVISO, estado( i, "bad_rows_rejected" ), "las rotas no se rechazaron" );
    igual( "fragile", Balance.veredicto( Arrays.asList( i ) ), "veredicto" );
  }

  public void pruebaDescarteDeclaradoNoEsPerdida() throws Exception {
    Corrida c = corrida( true, new Object[] { "Read", "CsvInput", 0, 10, 0 }, new Object[] { "Check", "FilterRows",
      10, 7, 0 }, new Object[] { "Write", "TextFileOutput", 7, 7, 0, 7 } );
    Balance.Informe i = Balance.evaluar( lineal( "filter" ), c, null, 0, null, null );
    igual( EstresResultado.PASA, estado( i, "no_silent_loss" ), "declarado" );
    igual( 3L, i.descartesDeclarados.get( "Check" ), "descarta 3 declarado" );
    igual( "filter", i.json( OM ).path( "declared_drops" ).get( 0 ).path( "declaration" ).asText(), "en el JSON" );
  }

  public void pruebaHopDeErrorYRechazosConMotivo() throws Exception {
    Balance.Nodo in = nodo( "Read", "CsvInput" );
    in.previos.clear();
    in.entradaArchivo = true;
    Balance.Nodo conv = nodo( "Convert", "SelectValues", "Read" );
    Balance.Nodo ok = nodo( "Write", "TextFileOutput", "Convert" );
    ok.carga = true;
    Balance.Nodo rej = nodo( "Rejects", "TextFileOutput", "Convert" );
    rej.enRechazo = true;
    rej.escritorRechazo = true;
    Corrida c = corrida( true, new Object[] { "Read", "CsvInput", 0, 10, 0 }, new Object[] { "Convert",
      "SelectValues", 10, 8, 2 }, new Object[] { "Write", "TextFileOutput", 8, 8, 0, 8 }, new Object[] { "Rejects",
        "TextFileOutput", 2, 2, 0, 2 } );
    Balance.Rechazo r = new Balance.Rechazo();
    r.paso = "Rejects";
    r.filas = 2;
    Balance.Informe i = Balance.evaluar( Arrays.asList( in, conv, ok, rej ), c, Collections.singletonMap( "Read",
      10 ), 2, Arrays.asList( r ), null );
    igual( EstresResultado.PASA, estado( i, "no_silent_loss" ), "el hop de error no es pérdida" );
    igual( EstresResultado.PASA, estado( i, "rejects_have_reason" ), "motivos" );
    igual( EstresResultado.PASA, estado( i, "bad_rows_rejected" ), "2 rotas, 2 rechazadas" );
    igual( 8L, i.cargadas, "cargadas" );
    igual( 2L, i.rechazadas, "rechazadas" );
    igual( "robust", Balance.veredicto( Arrays.asList( i ) ), "robusta" );

    r.sinMotivo = 1;
    Balance.Informe i2 = Balance.evaluar( Arrays.asList( in, conv, ok, rej ), c, null, 2, Arrays.asList( r ), null );
    igual( EstresResultado.AVISO, estado( i2, "rejects_have_reason" ), "un rechazo sin motivo" );
    r.sinMotivo = 0;
    r.sinColumnaMotivo = true;
    Balance.Informe i3 = Balance.evaluar( Arrays.asList( in, conv, ok, rej ), c, null, 2, Arrays.asList( r ), null );
    contiene( i3.comprobacion( "rejects_have_reason" ).detalles.get( 0 ), "without a reason column",
      "sin columna de motivo" );
  }

  public void pruebaFlujoInfo() throws Exception {
    Balance.Nodo in = nodo( "Read", "CsvInput" );
    in.previos.clear();
    in.entradaArchivo = true;
    Balance.Nodo dim = nodo( "Read dim", "CsvInput" );
    dim.previos.clear();
    Balance.Nodo lk = nodo( "Lookup", "StreamLookup", "Read" );
    lk.infos.add( "Read dim" );
    Corrida c = corrida( true, new Object[] { "Read", "CsvInput", 0, 10, 0 }, new Object[] { "Read dim", "CsvInput",
      0, 5, 0 }, new Object[] { "Lookup", "StreamLookup", 15, 10, 0 } );
    Balance.Informe i = Balance.evaluar( Arrays.asList( in, dim, lk ), c, null, 0, null, null );
    igual( EstresResultado.PASA, estado( i, "no_silent_loss" ), "15 leídas − 5 info = 10 = 10 puestas" );
    dim.destinos = 2;
    Balance.Informe i2 = Balance.evaluar( Arrays.asList( in, dim, lk ), c, null, 0, null, null );
    cierto( !i2.noEvaluados.isEmpty(), "info que reparte: not_evaluated, nunca un falso «perdidas»" );
    igual( EstresResultado.PASA, estado( i2, "no_silent_loss" ), "sin pérdidas inventadas" );
  }

  public void pruebaAbortoYEntradaIncompleta() throws Exception {
    Corrida c = corrida( false, new Object[] { "Read", "CsvInput", 0, 4, 0 }, new Object[] { "Check", "FilterRows", 4,
      4, 0 }, new Object[] { "Write", "TextFileOutput", 4, 3, 0, 3, 1 } );
    Balance.Informe i = Balance.evaluar( lineal( null ), c, Collections.singletonMap( "Read", 10 ), 3, null, null );
    igual( EstresResultado.FALLA, estado( i, "no_abort" ), "abortó" );
    contiene( i.comprobacion( "no_abort" ).mensaje, "Unexpected error in Convert types", "con el mensaje" );
    contiene( i.comprobacion( "no_abort" ).detalles.get( 0 ), "'Write'", "y el step que falló" );
    igual( EstresResultado.NO_EVALUADA, estado( i, "no_silent_loss" ), "cuentas parciales" );

    Corrida c2 = corrida( true, new Object[] { "Read", "CsvInput", 0, 8, 0 }, new Object[] { "Check", "FilterRows", 8,
      8, 0 }, new Object[] { "Write", "TextFileOutput", 8, 8, 0, 8 } );
    Balance.Informe i2 = Balance.evaluar( lineal( null ), c2, Collections.singletonMap( "Read", 10 ), 0, null, null );
    igual( EstresResultado.AVISO, estado( i2, "input_complete" ), "la entrada se comió 2 líneas" );
  }

  /** Falta la tabla o la BD: es el entorno, no una fila mala; no cuenta como falla de robustez. */
  public void pruebaAbortoPorEntornoNoEsFalla() throws Exception {
    Corrida c = corrida( false, new Object[] { "Read", "CsvInput", 0, 0, 0 }, new Object[] { "Check", "FilterRows", 0,
      0, 0 }, new Object[] { "Write", "TextFileOutput", 0, 0, 0, 0, 1 } );
    c.mensajeError = "Look up customer key - Error looking up row in database | org.sqlite.SQLiteException: "
      + "[SQLITE_ERROR] SQL error or missing database (no such table: dim_cliente)";
    Balance.Informe i = Balance.evaluar( lineal( null ), c, Collections.singletonMap( "Read", 10 ), 3, null, c );
    igual( EstresResultado.NO_EVALUADA, estado( i, "no_abort" ), "no es una falla de robustez" );
    contiene( i.comprobacion( "no_abort" ).mensaje, "the database or table dim_cliente does not exist: run the job "
      + "that creates it first, or point the test at a prepared copy", "dice qué falta y qué hacer" );
    cierto( !i.comprobacion( "no_abort" ).mensaje.contains( "bad row" ), "no culpa a una fila" );
    igual( EstresResultado.NO_EVALUADA, estado( i, "idempotent" ), "la 2ª corrida tampoco cuenta" );
    igual( 0, i.cuenta( EstresResultado.FALLA ), "ninguna falla" );
    igual( 0, i.cuenta( EstresResultado.AVISO ), "ningún aviso" );
    igual( "not_run", Balance.veredicto( Arrays.asList( i ) ), "veredicto: no se pudo correr" );
    contiene( i.json( OM ).path( "environment_problem" ).asText(), "dim_cliente", "en el JSON" );
    contiene( i.resumen(), "environment problem", "el resumen" );

    // el archivo de la BD no existe: lo nombra
    Corrida c2 = corrida( false, new Object[] { "Read", "CsvInput", 0, 0, 0 } );
    c2.mensajeError = "Table output - no such table: fact_venta";
    Balance.Informe i2 = Balance.evaluar( lineal( null ), c2, null, 0, null, null, Arrays.asList(
      "dw.sqlite (connection dw)" ) );
    contiene( i2.entorno, "the database file dw.sqlite (connection dw) does not exist", "nombra el archivo" );

    // H2, PostgreSQL y una BD que no se puede abrir
    c2.mensajeError = "Table \"DIM_CLIENTE\" not found; SQL statement: SELECT 1";
    contiene( Balance.problemaDeEntorno( c2, null ), "table DIM_CLIENTE does not exist", "H2" );
    c2.mensajeError = "ERROR: relation \"public.ventas\" does not exist";
    contiene( Balance.problemaDeEntorno( c2, null ), "table public.ventas does not exist", "PostgreSQL" );
    c2.mensajeError = "path to 'C:/no/está/dw.sqlite': 'C:\\no\\está' does not exist";
    contiene( Balance.problemaDeEntorno( c2, null ), "the database C:/no/está/dw.sqlite does not exist", "SQLite" );

    // una fila mala sigue siendo una falla, y una variante frágil manda sobre una de entorno
    Corrida mala = corrida( false, new Object[] { "Read", "CsvInput", 0, 4, 0 } );
    Balance.Informe im = Balance.evaluar( lineal( null ), mala, null, 0, null, null );
    igual( null, im.entorno, "no es el entorno" );
    igual( EstresResultado.FALLA, estado( im, "no_abort" ), "falla" );
    contiene( im.comprobacion( "no_abort" ).mensaje, "a bad row stopped the load", "culpa a la fila" );
    igual( "fragile", Balance.veredicto( Arrays.asList( i, im ) ), "frágil manda" );
  }

  public void pruebaRerunDuplica() throws Exception {
    Corrida c = corrida( true, new Object[] { "Read", "CsvInput", 0, 3, 0 } );
    Corrida c2 = corrida( true, new Object[] { "Read", "CsvInput", 0, 3, 0 } );
    EstresResultado.TablaCuenta t1 = new EstresResultado.TablaCuenta();
    t1.conexion = "dw";
    t1.tabla = "sales";
    t1.filas = 3;
    t1.huellaClaves = "a";
    EstresResultado.TablaCuenta t2 = new EstresResultado.TablaCuenta();
    t2.conexion = "dw";
    t2.tabla = "sales";
    t2.filas = 6;
    t2.huellaClaves = "b";
    c.tablas.add( t1 );
    c2.tablas.add( t2 );
    List<Balance.Nodo> nodos = new ArrayList<Balance.Nodo>( lineal( null ).subList( 0, 1 ) );
    Balance.Informe i = Balance.evaluar( nodos, c, null, 0, null, c2 );
    igual( EstresResultado.FALLA, estado( i, "idempotent" ), "la 2ª corrida duplica" );
    contiene( i.comprobacion( "idempotent" ).detalles.get( 0 ), "3 rows after the 1st run, 6 after the 2nd",
      "cuántas" );
    t2.filas = 3;
    Balance.Informe i2 = Balance.evaluar( nodos, c, null, 0, null, c2 );
    igual( EstresResultado.AVISO, estado( i2, "idempotent" ), "mismas filas, otras claves" );
  }

  public void pruebaMensajeDeErrorDelLog() {
    String log = "2026/10/22 00:03:01 - Convert types.0 - ERROR (version 9.4.0.0-343, build 0.0) : Unexpected error\n"
      + "2026/10/22 00:03:01 - Convert types.0 - ERROR (version 9.4.0.0-343, build 0.0) : "
      + "org.pentaho.di.core.exception.KettleConversionException: \n"
      + "fecha String : couldn't convert string [31/02/2021] to a date using format [yyyy-MM-dd]\n"
      + "\tat org.pentaho.di.core.row.value.ValueMetaBase.convertData(ValueMetaBase.java:1)\n"
      + "2026/10/22 00:03:01 - Read sales.0 - Finished processing (I=20, O=0, R=0, W=20, U=0, E=0)\n";
    String m = EstresMain.primerError( log );
    contiene( m, "Convert types.0: Unexpected error", "el primer ERROR con su step" );
    contiene( m, "couldn't convert string [31/02/2021]", "y la causa que viene después de la excepción" );
    cierto( !m.contains( "ValueMetaBase.java" ), "sin la pila: " + m );
    cierto( EstresMain.primerError( "2026/10/22 00:03:01 - T - Finished\n" ) == null, "sin errores, null" );
  }

  public void pruebaColumnaMotivo() {
    cierto( Balance.esColumnaMotivo( "Motivo", null ), "motivo" );
    cierto( Balance.esColumnaMotivo( "reason", null ), "reason" );
    cierto( Balance.esColumnaMotivo( "descripcion_error", Collections.singleton( "descripcion_error" ) ),
      "el descriptions_valuename del hop de error" );
    cierto( !Balance.esColumnaMotivo( "cliente", null ), "no cualquiera" );
  }
}
