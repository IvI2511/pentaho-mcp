package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.lanza;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.informes.Rotulos;
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

  /**
   * 0.6.1: falta una tabla (la búsqueda) y además una fila mala aborta otro step (la conversión). Antes el entorno
   * tapaba la fila mala (not_run); ahora se dicen los dos: environment_problem y no_abort en falla con el step de la
   * fila, y el veredicto es fragile. Con solo el entorno, o sin mensaje propio del step, sigue siendo not_run.
   */
  public void pruebaEntornoYFilaMala() throws Exception {
    Balance.Nodo in = nodo( "Read", "CsvInput" );
    in.previos.clear();
    in.entradaArchivo = true;
    Balance.Nodo conv = nodo( "Convert", "SelectValues", "Read" );
    Balance.Nodo lk = nodo( "Lookup", "DBLookup", "Convert" );
    Balance.Nodo w = nodo( "Write", "TextFileOutput", "Lookup" );
    w.carga = true;
    List<Balance.Nodo> nodos = Arrays.asList( in, conv, lk, w );
    String log = "2026/10/24 10:00:01 - Lookup.0 - ERROR (version 9.4.0.0-343, build 0.0) : Error looking up row "
      + "in database\n"
      + "2026/10/24 10:00:01 - Lookup.0 - ERROR (version 9.4.0.0-343, build 0.0) : "
      + "org.sqlite.SQLiteException: [SQLITE_ERROR] SQL error or missing database (no such table: dim_cliente)\n"
      + "2026/10/24 10:00:01 - Convert.0 - ERROR (version 9.4.0.0-343, build 0.0) : Unexpected error\n"
      + "2026/10/24 10:00:01 - Convert.0 - ERROR (version 9.4.0.0-343, build 0.0) : "
      + "org.pentaho.di.core.exception.KettleConversionException: \n"
      + "fecha String : couldn't convert string [31/02/2021] to a date using format [yyyy-MM-dd]\n"
      + "\tat org.pentaho.di.core.row.value.ValueMetaBase.convertData(ValueMetaBase.java:1)\n"
      + "2026/10/24 10:00:01 - Read.0 - Finished processing (I=20, O=0, R=0, W=20, U=0, E=0)\n";
    // el log por step
    java.util.Map<String, String> pp = Balance.erroresPorPaso( log );
    contiene( pp.get( "Lookup" ), "no such table: dim_cliente", "el error de la búsqueda" );
    contiene( pp.get( "Convert" ), "couldn't convert string [31/02/2021]", "y el de la conversión, con su causa" );
    cierto( !pp.get( "Convert" ).contains( "ValueMetaBase.java" ), "sin la pila" );
    cierto( !pp.containsKey( "Read" ), "sin los que no fallaron" );

    // la hija trae el mensaje de cada step (EstresMain lo saca del log entero)
    Corrida c = corrida( false, new Object[] { "Read", "CsvInput", 0, 6, 0 }, new Object[] { "Convert",
      "SelectValues", 6, 4, 0, 0, 1 }, new Object[] { "Lookup", "DBLookup", 4, 0, 0, 0, 1 }, new Object[] { "Write",
        "TextFileOutput", 0, 0, 0, 0 } );
    c.mensajeError = pp.get( "Lookup" );
    for ( EstresResultado.PasoCuenta p : c.pasos ) {
      p.mensaje = pp.get( p.nombre );
    }
    // y viaja en el JSON de la hija
    Corrida ida = Corrida.desde( c.json( OM ) );
    Balance.Informe i = Balance.evaluar( nodos, ida, Collections.singletonMap( "Read", 10 ), 3, null, null );
    contiene( i.entorno, "dim_cliente", "el problema de entorno se informa" );
    igual( EstresResultado.FALLA, estado( i, "no_abort" ), "la fila mala sigue siendo una falla" );
    contiene( i.comprobacion( "no_abort" ).mensaje, "a bad row stopped the load", "culpa a la fila" );
    contiene( i.comprobacion( "no_abort" ).mensaje, "environment problem", "y nombra el entorno" );
    igual( 1, i.comprobacion( "no_abort" ).detalles.size(), "un step abortó por una fila: "
      + i.comprobacion( "no_abort" ).detalles );
    contiene( i.comprobacion( "no_abort" ).detalles.get( 0 ), "step 'Convert' (SelectValues) failed with 1 error(s)",
      "el step de la fila" );
    contiene( i.comprobacion( "no_abort" ).detalles.get( 0 ), "couldn't convert string", "con su mensaje" );
    igual( "fragile", Balance.veredicto( Arrays.asList( i ) ), "frágil, no not_run" );
    contiene( i.json( OM ).path( "environment_problem" ).asText(), "dim_cliente", "environment_problem en el JSON" );
    contiene( i.resumen(), "bad row", "el resumen dice la fila" );
    contiene( i.resumen(), "environment problem", "y el entorno" );

    // sin el mensaje de la hija (JSON de antes): sale de la cola del log
    Corrida sinMensajes = corrida( false, new Object[] { "Read", "CsvInput", 0, 6, 0 }, new Object[] { "Convert",
      "SelectValues", 6, 4, 0, 0, 1 }, new Object[] { "Lookup", "DBLookup", 4, 0, 0, 0, 1 } );
    sinMensajes.mensajeError = pp.get( "Lookup" );
    sinMensajes.colaLog = log;
    Balance.Informe i2 = Balance.evaluar( nodos, sinMensajes, null, 0, null, null );
    igual( EstresResultado.FALLA, estado( i2, "no_abort" ), "también con la cola del log" );
    igual( "fragile", Balance.veredicto( Arrays.asList( i2 ) ), "frágil" );

    // solo el entorno (la conversión no falló): not_run como antes
    Corrida soloEntorno = corrida( false, new Object[] { "Read", "CsvInput", 0, 6, 0 }, new Object[] { "Lookup",
      "DBLookup", 4, 0, 0, 0, 1 } );
    soloEntorno.mensajeError = pp.get( "Lookup" );
    soloEntorno.colaLog = log.replaceAll( "(?m)^.*Convert\\.0.*$\\n?", "" ).replaceAll( "(?m)^fecha String.*$\\n?", "" );
    Balance.Informe i3 = Balance.evaluar( nodos, soloEntorno, null, 0, null, null );
    igual( EstresResultado.NO_EVALUADA, estado( i3, "no_abort" ), "solo el entorno: no es una falla" );
    igual( "not_run", Balance.veredicto( Arrays.asList( i3 ) ), "not_run" );

    // la corrida no pasó de la inicialización: ninguna fila se procesó, aunque un step diga otra cosa
    Corrida sinArrancar = Corrida.desde( c.json( OM ) );
    sinArrancar.iniciada = false;
    Balance.Informe i4 = Balance.evaluar( nodos, sinArrancar, null, 0, null, null );
    igual( EstresResultado.NO_EVALUADA, estado( i4, "no_abort" ), "sin arrancar: solo el entorno" );
    igual( "not_run", Balance.veredicto( Arrays.asList( i4 ) ), "not_run" );
    cierto( Corrida.desde( OM.readTree( "{\"variant\":\"x\"}" ) ).iniciada, "un JSON de antes: iniciada" );
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

  /** Los ids y estados de las comprobaciones y las cuentas del balance: lo que no cambia con el idioma. */
  private static String codigos( Balance.Informe i ) {
    StringBuilder sb = new StringBuilder();
    for ( EstresResultado.Comprobacion k : i.comprobaciones ) {
      sb.append( k.id ).append( '=' ).append( k.estado ).append( ';' );
    }
    ObjectNode b = i.json( OM );
    b.remove( "summary" );
    b.remove( "not_evaluated" );
    b.remove( "environment_problem" );
    return sb.append( b.toString() ).toString();
  }

  /**
   * language: el mismo caso da los textos en inglés por defecto (idénticos a los de antes) y en español o portugués
   * con es/pt; los ids, los estados y las cuentas no cambian.
   */
  public void pruebaIdiomas() throws Exception {
    Corrida c = corrida( true, new Object[] { "Read", "CsvInput", 0, 10, 0 }, new Object[] { "Check", "FilterRows",
      10, 7, 0 }, new Object[] { "Write", "TextFileOutput", 7, 7, 0, 7 } );
    Map<String, Integer> lineas = Collections.singletonMap( "Read", 10 );
    Balance.Informe def = Balance.evaluar( lineal( null ), c, lineas, 3, null, null );
    Balance.Informe en = Balance.evaluar( lineal( null ), c, lineas, 3, null, null, null, "en" );
    Balance.Informe es = Balance.evaluar( lineal( null ), c, lineas, 3, null, null, null, "es" );
    Balance.Informe pt = Balance.evaluar( lineal( null ), c, lineas, 3, null, null, null, "pt" );
    Balance.Informe otro = Balance.evaluar( lineal( null ), c, lineas, 3, null, null, null, "fr" );
    igual( def.json( OM ).toString(), en.json( OM ).toString(), "en = sin idioma" );
    igual( def.json( OM ).toString(), otro.json( OM ).toString(), "otro idioma = en" );
    igual( "10 read = 7 loaded + 0 rejected + 0 dropped (declared) + 3 lost", def.resumen(), "resumen en" );
    igual( "step 'Check' (FilterRows) lost 3 row(s): a filter without a false branch drops the rows that do not "
      + "match; send them to a rejects file with a reason, or declare the drop", def.comprobacion( "no_silent_loss" )
        .detalles.get( 0 ), "detalle en, igual que antes" );
    igual( "3 row(s) disappeared without an error hop, a false branch or a declaration", def.comprobacion(
      "no_silent_loss" ).mensaje, "mensaje en" );
    igual( "3 broken row(s) were not rejected (3 expected, 0 rejected): they reached the outputs or were lost",
      def.comprobacion( "bad_rows_rejected" ).mensaje, "bad_rows_rejected en" );
    igual( "no rejects file in the transformation", def.comprobacion( "rejects_have_reason" ).mensaje,
      "sin rechazos" );

    igual( "10 leídas = 7 cargadas + 0 rechazadas + 0 descartadas (declaradas) + 3 perdidas", es.resumen(),
      "resumen es" );
    contiene( es.comprobacion( "no_silent_loss" ).detalles.get( 0 ), "el step 'Check' (FilterRows) perdió 3 fila(s)",
      "detalle es" );
    contiene( es.comprobacion( "no_silent_loss" ).mensaje, "desaparecieron sin un hop de error", "mensaje es" );
    contiene( es.comprobacion( "bad_rows_rejected" ).mensaje, "no se rechazaron (3 esperadas, 0 rechazadas)",
      "bad_rows_rejected es" );
    contiene( es.json( OM ).path( "summary" ).asText(), "10 leídas", "el resumen del JSON también" );
    igual( "10 lidas = 7 carregadas + 0 rejeitadas + 0 descartadas (declaradas) + 3 perdidas", pt.resumen(),
      "resumen pt" );
    contiene( pt.comprobacion( "no_silent_loss" ).detalles.get( 0 ), "o step 'Check' (FilterRows) perdeu 3 linha(s)",
      "detalle pt" );
    contiene( pt.comprobacion( "rejects_have_reason" ).mensaje, "arquivo de rejeitos", "pt de Brasil" );
    String cod = codigos( def );
    igual( cod, codigos( es ), "es: mismos ids, estados y cuentas" );
    igual( cod, codigos( pt ), "pt: mismos ids, estados y cuentas" );
    cierto( cod.contains( "no_silent_loss=fail" ) && cod.contains( "\"lost\":[{\"step\":\"Check\",\"rows\":3}]" ),
      "códigos en inglés: " + cod );
    igual( "fragile", Balance.veredicto( Arrays.asList( es ) ), "el veredicto no se traduce" );

    // entorno: el motivo, el resumen y los not_evaluated
    Corrida ce = corrida( false, new Object[] { "Read", "CsvInput", 0, 0, 0 } );
    ce.mensajeError = "Table output - no such table: fact_venta";
    Balance.Informe ie = Balance.evaluar( lineal( null ), ce, null, 0, null, ce, Arrays.asList(
      "dw.sqlite (conexión dw)" ), "es" );
    igual( "el archivo de base de datos dw.sqlite (conexión dw) no existe (así que tampoco la tabla fact_venta): "
      + "ejecuta antes el job que lo crea, o apunta la prueba a una copia preparada", ie.entorno, "entorno es" );
    contiene( ie.comprobacion( "no_abort" ).mensaje, "problema del entorno, no un fallo de robustez: ",
      "no_abort es" );
    contiene( ie.comprobacion( "idempotent" ).mensaje, "problema del entorno: ", "idempotent es" );
    contiene( ie.resumen(), "sin evaluar (problema del entorno)", "resumen es" );
    igual( EstresResultado.NO_EVALUADA, estado( ie, "no_abort" ), "el estado no cambia" );
    igual( "not_run", Balance.veredicto( Arrays.asList( ie ) ), "not_run" );
    ce.mensajeError = "SQLITE_CANTOPEN";
    igual( "the database file does not exist: run the job that creates it first, or point the test at a prepared "
      + "copy", Balance.problemaDeEntorno( ce, null ), "sin nombre, en: igual que antes" );
    contiene( Balance.problemaDeEntorno( ce, null, "pt" ), "o arquivo de banco de dados não existe",
      "sin nombre, pt" );

    // los argumentos: language se valida y llega a los mensajes de error
    igual( "pt", EstresTools.pedido( OM.readTree( "{\"language\":\"pt\"}" ) ).lang, "language=pt" );
    igual( "en", EstresTools.pedido( OM.readTree( "{}" ) ).lang, "por defecto en" );
    McpHttpServer.ToolError e = lanza( McpHttpServer.ToolError.class, () -> EstresTools.pedido( OM.readTree(
      "{\"language\":\"fr\"}" ) ), "language=fr" );
    igual( "'language' must be en, es or pt, got: fr", e.getMessage(), "el error de language" );
    e = lanza( McpHttpServer.ToolError.class, () -> EstresTools.pedido( OM.readTree(
      "{\"language\":\"es\",\"variants\":[\"zzz\"]}" ) ), "variante desconocida" );
    contiene( e.getMessage(), "variante desconocida 'zzz'; válidas: all, ", "en español" );
    e = lanza( McpHttpServer.ToolError.class, () -> EstresTools.pedido( OM.readTree( "{\"variants\":[\"zzz\"]}" ) ),
      "variante desconocida" );
    contiene( e.getMessage(), "unknown variant 'zzz'; valid: all, ", "en inglés por defecto" );
    String vencida = "The stress test did not finish in 300 s and was stopped";
    igual( "La prueba de estrés no terminó en 300 s y se detuvo", EstresTools.traducirVencida(
      new McpHttpServer.ToolError( vencida ), Balance.textos( "es" ) ).getMessage(), "la de RunMotor, en es" );
    igual( vencida, EstresTools.traducirVencida( new McpHttpServer.ToolError( vencida ), Balance.textos( "en" ) )
      .getMessage(), "en: igual" );
  }

  /** estres_en/es/pt.properties: las mismas claves y los mismos %s/%d en el mismo orden. */
  public void pruebaTextosMismasClaves() throws Exception {
    Map<String, Properties> ps = new LinkedHashMap<String, Properties>();
    for ( String l : new String[] { "en", "es", "pt" } ) {
      Properties p = new Properties();
      try ( InputStream in = PruebaEstresBalance.class.getClassLoader().getResourceAsStream( Rotulos.CARPETA
        + "estres_" + l + ".properties" ) ) {
        cierto( in != null, "estres_" + l + ".properties en el classpath" );
        p.load( new InputStreamReader( in, StandardCharsets.UTF_8 ) );
      }
      ps.put( l, p );
    }
    Set<String> en = new TreeSet<String>( ps.get( "en" ).stringPropertyNames() );
    cierto( en.size() >= 60, "claves en: " + en.size() );
    Pattern esp = Pattern.compile( "%[sd%n]" );
    for ( String l : new String[] { "es", "pt" } ) {
      igual( en, new TreeSet<String>( ps.get( l ).stringPropertyNames() ), "las claves de " + l );
      for ( String k : en ) {
        igual( especificadores( esp, ps.get( "en" ).getProperty( k ) ), especificadores( esp, ps.get( l )
          .getProperty( k ) ), l + " " + k + ": los mismos especificadores" );
        cierto( !ps.get( l ).getProperty( k ).trim().isEmpty(), l + " " + k + " no vacío" );
      }
    }
    for ( String x : new String[] { "tenés", "ejecutá", "mirá", "usá", "probá", "fijate", "podés" } ) {
      for ( String k : en ) {
        cierto( !ps.get( "es" ).getProperty( k ).contains( x ), "es " + k + ": sin voseo (" + x + ")" );
      }
    }
  }

  private static String especificadores( Pattern p, String texto ) {
    StringBuilder sb = new StringBuilder();
    Matcher m = p.matcher( texto );
    while ( m.find() ) {
      sb.append( m.group() );
    }
    return sb.toString();
  }

  public void pruebaColumnaMotivo() {
    cierto( Balance.esColumnaMotivo( "Motivo", null ), "motivo" );
    cierto( Balance.esColumnaMotivo( "reason", null ), "reason" );
    cierto( Balance.esColumnaMotivo( "descripcion_error", Collections.singleton( "descripcion_error" ) ),
      "el descriptions_valuename del hop de error" );
    cierto( !Balance.esColumnaMotivo( "cliente", null ), "no cualquiera" );
  }
}
