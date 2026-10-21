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

  public void pruebaColumnaMotivo() {
    cierto( Balance.esColumnaMotivo( "Motivo", null ), "motivo" );
    cierto( Balance.esColumnaMotivo( "reason", null ), "reason" );
    cierto( Balance.esColumnaMotivo( "descripcion_error", Collections.singleton( "descripcion_error" ) ),
      "el descriptions_valuename del hop de error" );
    cierto( !Balance.esColumnaMotivo( "cliente", null ), "no cualquiera" );
  }
}
