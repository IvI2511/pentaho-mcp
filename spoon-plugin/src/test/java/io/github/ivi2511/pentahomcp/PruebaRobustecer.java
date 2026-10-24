package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.core.NotePadMeta;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.undo.TransAction;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepErrorMeta;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.robustez.Propuesta;
import io.github.ivi2511.pentahomcp.robustez.Receta;

/**
 * harden_etl sin GUI (docs/robustez-diseno.md §4) sobre una carga sintética como la de clase (un CSV de ventas →
 * conversión → dimensión de clientes + búsqueda de su clave → hecho de ventas, en SQLite), con nombres y datos propios:
 * el plan, aplicar todo (un solo Ctrl+Z), deshacer y volver al XML de antes, y correr la copia robustecida con un
 * archivo roto: no aborta, cada fila mala termina en un archivo de rechazos con su motivo y las buenas se cargan.
 *
 * @dueño robustez-B
 */
public class PruebaRobustecer {

  static final String CSV_LIMPIO = "id_cliente;nombre;ciudad;fecha;importe\n"
    + "C01;Ana;Madrid;2021-03-01;10.50\n"
    + "C02;Luis;Sevilla;2021-03-02;20\n"
    + "C03;Marta;Valencia;2021-03-03;30.25\n"
    + "C01;Ana;Madrid;2021-03-04;5\n";

  /** Las 4 limpias + 8 rotas (una por caso) + 1 repetida exacta. */
  static final String CSV_ROTO = CSV_LIMPIO
    + ";Pedro;Bilbao;2021-03-05;7\n" // falta la clave
    + "C04;Juan;Toledo;;8\n" // falta la fecha
    + "C05;Rosa;Cuenca;31/12/2021;9\n" // fecha en otro formato
    + "C06;Eva;Lugo;2021-03-06;mil\n" // número en letras
    + "C07;Un nombre larguísimo que no cabe en la columna de la tabla;Soria;2021-03-07;3\n" // CHECK del nombre
    + "C08;Sol;Teruel;2021-03-08;150000\n" // importe fuera de rango (CHECK)
    + "C09;Leo\n" // línea cortada
    + "C02;Luis;Sevilla;2021-03-02;20\n"; // repetida exacta

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
  }

  // ---------------------------------------------------------------- la carga sintética «antes»

  static TransMeta antes( String sqlite ) throws Exception {
    TransMeta tm = new TransMeta();
    tm.setName( "carga_ventas" );
    DatabaseMeta dw = new DatabaseMeta( "dw", "SQLITE", "Native", "", sqlite, "", "", "" );
    tm.addDatabase( dw );
    StringBuilder campos = new StringBuilder( "<fields>" );
    for ( String c : new String[] { "id_cliente", "nombre", "ciudad", "fecha", "importe" } ) {
      campos.append( "<field><name>" ).append( c ).append( "</name><type>String</type><format/><currency/><decimal/>"
        + "<group/><length>-1</length><precision>-1</precision><trim_type>both</trim_type></field>" );
    }
    campos.append( "</fields>" );
    TransOps.agregarStep( tm, "CsvInput", "Leer ventas", "<filename>${Internal.Entry.Current.Directory}/ventas.csv"
      + "</filename><separator>;</separator><enclosure>\"</enclosure><header>Y</header><lazy_conversion>N"
      + "</lazy_conversion><encoding>UTF-8</encoding>" + campos, 100, 200, null, null, null );
    TransOps.agregarStep( tm, "SelectValues", "Convertir tipos", "<fields><select_unspecified>N</select_unspecified>"
      + meta( "fecha", "Date", "yyyy-MM-dd", "true", "false" ) + meta( "importe", "Number", "#.##", "false", "true" )
      + "</fields>",
      280, 200, "Leer ventas", null, null );
    TransOps.agregarStep( tm, "InsertUpdate", "Cargar clientes", "<connection>dw</connection><commit>100</commit>"
      + "<update_bypassed>N</update_bypassed><lookup><schema/><table>dim_cliente</table>" + clave( "id_cliente" )
      + valor( "id_cliente", "N" ) + valor( "nombre", "Y" ) + valor( "ciudad", "Y" ) + "</lookup>", 480, 100,
      "Convertir tipos", null, null );
    TransOps.agregarStep( tm, "DBLookup", "Buscar clave cliente", "<connection>dw</connection><cache>N</cache>"
      + "<cache_load_all>N</cache_load_all><cache_size>0</cache_size><lookup><schema/><table>dim_cliente</table>"
      + "<orderby/><fail_on_multiple>N</fail_on_multiple><eat_row_on_failure>N</eat_row_on_failure>"
      + clave( "id_cliente" ) + "<value><name>sk_cliente</name><rename>sk_cliente</rename><default/><type>Integer"
      + "</type></value></lookup>", 480, 300, null, null, null );
    tm.addTransHop( new TransHopMeta( tm.findStep( "Convertir tipos" ), tm.findStep( "Buscar clave cliente" ) ) );
    TransOps.agregarStep( tm, "InsertUpdate", "Cargar ventas", "<connection>dw</connection><commit>100</commit>"
      + "<update_bypassed>N</update_bypassed><lookup><schema/><table>fact_venta</table>" + clave( "sk_cliente" )
      + clave( "fecha" ) + valor( "sk_cliente", "N" ) + valor( "fecha", "N" ) + valor( "importe", "Y" ) + "</lookup>",
      700, 300, "Buscar clave cliente", null, null );
    tm.findStep( "Convertir tipos" ).setDistributes( false ); // las dos ramas reciben todas las filas
    TransOps.resolverReferencias( tm );
    tm.setChanged( false );
    return tm;
  }

  private static String meta( String campo, String tipo, String mascara, String fechaLaxa, String numeroLaxo ) {
    return "<meta><name>" + campo + "</name><rename>" + campo + "</rename><type>" + tipo + "</type><length>-2</length>"
      + "<precision>-2</precision><conversion_mask>" + mascara + "</conversion_mask><date_format_lenient>" + fechaLaxa
      + "</date_format_lenient><date_format_locale/><date_format_timezone/><lenient_string_to_number>" + numeroLaxo
      + "</lenient_string_to_number><encoding/><decimal_symbol>.</decimal_symbol><grouping_symbol/>"
      + "<currency_symbol/><storage_type/></meta>";
  }

  private static String clave( String c ) {
    return "<key><name>" + c + "</name><field>" + c + "</field><condition>=</condition><name2/></key>";
  }

  private static String valor( String c, String actualiza ) {
    return "<value><name>" + c + "</name><rename>" + c + "</rename><update>" + actualiza + "</update></value>";
  }

  static RobustecerAnalisis.Pedido pedido( String json ) throws Exception {
    return RobustezTools.pedido( new ObjectMapper().readTree( json ) );
  }

  private static Propuesta propuesta( List<Propuesta> l, String key ) {
    for ( Propuesta p : l ) {
      if ( p.key.equals( key ) ) {
        return p;
      }
    }
    return null;
  }

  private static List<String> keys( List<Propuesta> l ) {
    List<String> r = new ArrayList<String>();
    for ( Propuesta p : l ) {
      r.add( p.key );
    }
    return r;
  }

  // ---------------------------------------------------------------- el plan

  public void pruebaPlanPorDefectoSoloAuto() throws Exception {
    TransMeta tm = antes( "dw.sqlite" );
    RobustecerAnalisis.Pedido p = pedido( "{}" );
    RobustecerAnalisis.Plan plan = RobustecerAnalisis.analizar( tm, p );
    List<Propuesta> vis = RobustecerAnalisis.visibles( plan, p );
    for ( Propuesta x : vis ) {
      igual( Receta.AUTO, x.nivel, "por defecto solo las auto: " + x.key );
    }
    List<String> k = keys( vis );
    cierto( k.contains( "harden:rejects_on_error:Convertir tipos" ), "R01 en la conversión: " + k );
    cierto( k.contains( "harden:rejects_on_error:Cargar clientes" ), "R01 en la dimensión: " + k );
    cierto( k.contains( "harden:rejects_on_error:Cargar ventas" ), "R01 en el hecho: " + k );
    cierto( k.contains( "harden:wait_for_loads:Buscar clave cliente" ), "R11 en la búsqueda: " + k );
    igual( 4, vis.size(), "cuatro auto: " + k );
    ObjectNode o = RobustezTools.respuesta( "T", plan, p, RobustecerAnalisis.elegir( plan, p ),
      RobustecerAplicar.aplicar( tm, RobustecerAnalisis.elegir( plan, p ), UndoGrupo.sinSpoon( tm ), null ) );
    igual( 4, o.path( "would_apply" ).size(), "las cuatro se aplicarían: " + o );
    contiene( o.path( "other_recipes" ).asText(), "recipes=[\"all\"]", "avisa que hay más recetas" );
    contiene( o.toString(), "+ error hop Cargar clientes -> ", "el diff del hop de error" );
  }

  public void pruebaPlanCompleto() throws Exception {
    TransMeta tm = antes( "dw.sqlite" );
    RobustecerAnalisis.Pedido p = pedido( "{\"recipes\":[\"all\"],\"language\":\"es\"}" );
    RobustecerAnalisis.Plan plan = RobustecerAnalisis.analizar( tm, p );
    List<String> k = keys( plan.propuestas );
    for ( String e : new String[] { "harden:required_fields:Leer ventas", "harden:input_file_parameter:Leer ventas",
      "harden:strict_conversion:Convertir tipos", "harden:rejects_on_error:Convertir tipos",
      "harden:dedupe_before_load:Cargar clientes", "harden:wait_for_loads:Buscar clave cliente",
      "harden:orphans_to_rejects:Buscar clave cliente", "harden:aggregate_to_grain:Cargar ventas" } ) {
      cierto( k.contains( e ), e + " en el plan: " + k );
    }
    Propuesta r03 = propuesta( plan.propuestas, "harden:required_fields:Leer ventas" );
    igual( Receta.CONFIRM, r03.nivel, "R03 se confirma" );
    igual( "[\"id_cliente\",\"fecha\"]", r03.detectado.path( "required_fields" ).toString(),
      "obligatorios: las claves que usan las cargas y la búsqueda" );
    contiene( r03.diff(), "+ ¿ventas completo? (Filter rows: id_cliente IS NOT NULL AND fecha IS NOT NULL)", "el filtro" );
    contiene( r03.diff(), "+ Escribir rechazos ventas (Text file output) ${Internal.Entry.Current.Directory}/rechazos/"
      + "rechazos_ventas.csv", "la cola del flujo" );
    // la conversión rechaza filas con el mismo formato que el filtro: misma cola
    Propuesta r01 = propuesta( plan.propuestas, "harden:rejects_on_error:Convertir tipos" );
    contiene( r01.diff(), "Convertir tipos: error handling -> Limpiar motivo ventas (motivo, campos_error)",
      "misma cola que el filtro" );
    // la dimensión y el hecho reciben otro formato (fecha ya es Date; el hecho trae sk_cliente): otras colas
    contiene( propuesta( plan.propuestas, "harden:rejects_on_error:Cargar clientes" ).diff(), "Limpiar motivo ventas_2",
      "cola propia de la dimensión" );
    contiene( propuesta( plan.propuestas, "harden:rejects_on_error:Cargar ventas" ).diff(), "Limpiar motivo ventas_3",
      "cola propia del hecho" );
    Propuesta r12 = propuesta( plan.propuestas, "harden:input_file_parameter:Leer ventas" );
    contiene( r12.diff(), "+ parameter ARCHIVO_VENTAS (default ventas.csv)", "el parámetro" );
    contiene( r12.diff(), "${Internal.Entry.Current.Directory}/${ARCHIVO_VENTAS}", "la ruta con el parámetro" );
    Propuesta r08 = propuesta( plan.propuestas, "harden:orphans_to_rejects:Buscar clave cliente" );
    igual( Receta.CONFIRM, r08.nivel, "R08 se confirma" );
    contiene( r08.diff(), "+ ¿Está en dim_cliente? (Filter rows: sk_cliente IS NOT NULL)", "el filtro de huérfanos" );
    contiene( r08.diff(), "Limpiar motivo ventas_3", "la misma cola que los errores del hecho" );
    nulo( propuesta( plan.propuestas, "harden:dedupe_before_load:Cargar ventas" ), "un hecho no se deduplica" );
    cierto( propuesta( plan.propuestas, "harden:aggregate_to_grain:Cargar ventas" ).semantica, "R10 cambia la semántica" );
    // las confirm no se aplican sin su key
    List<Propuesta> el = RobustecerAnalisis.elegir( plan, p );
    for ( Propuesta x : el ) {
      igual( Receta.AUTO, x.nivel, "sin confirm solo las auto: " + x.key );
    }
    p = pedido( "{\"recipes\":[\"all\"],\"confirm\":[\"harden:required_fields:Leer ventas\"]}" );
    el = RobustecerAnalisis.elegir( RobustecerAnalisis.analizar( antes( "dw.sqlite" ), p ), p );
    cierto( keys( el ).contains( "harden:required_fields:Leer ventas" ), "con su key sí: " + keys( el ) );
    falso( keys( el ).contains( "harden:dedupe_before_load:Cargar clientes" ), "las demás confirm no" );
  }

  public void pruebaArgumentos() throws Exception {
    try {
      pedido( "{\"recipes\":[\"no_existe\"]}" );
      Verificador.falla( "receta desconocida" );
    } catch ( McpHttpServer.ToolError e ) {
      contiene( e.getMessage(), "Unknown recipe 'no_existe'", "receta desconocida" );
    }
    RobustecerAnalisis.Pedido p = pedido( "{\"required_fields\":{\"Leer ventas\":[\"id_cliente\"]},"
      + "\"recipes\":[\"required_fields\"],\"steps\":[\"Leer ventas\"]}" );
    RobustecerAnalisis.Plan plan = RobustecerAnalisis.analizar( antes( "dw.sqlite" ), p );
    Propuesta r03 = propuesta( plan.propuestas, "harden:required_fields:Leer ventas" );
    igual( "[\"id_cliente\"]", r03.detectado.path( "required_fields" ).toString(), "los obligatorios que se pasan" );
    for ( Propuesta x : plan.propuestas ) {
      igual( "Leer ventas", x.step, "steps limita el plan" );
    }
  }

  // ---------------------------------------------------------------- aplicar y deshacer

  /** Todo (recipes=all, confirm=all) en un solo Ctrl+Z; deshacer vuelve al XML de antes (salvo el parámetro). */
  public void pruebaAplicarYDeshacer() throws Exception {
    TransMeta tm = antes( "dw.sqlite" );
    String xmlAntes = sinParametros( tm.getXML() );
    int steps = tm.nrSteps();
    int hops = tm.nrTransHops();
    RobustecerAnalisis.Pedido p = pedido( "{\"recipes\":[\"all\"],\"confirm\":[\"all\"],\"dry_run\":false,"
      + "\"language\":\"es\"}" );
    RobustecerAnalisis.Plan plan = RobustecerAnalisis.analizar( tm, p );
    List<Propuesta> el = RobustecerAnalisis.elegir( plan, p );
    UndoGrupo undo = UndoGrupo.sinSpoon( tm );
    RobustecerAplicar.Hecho h = RobustecerAplicar.aplicar( tm, el, undo, null );
    igual( "[]", saltados( h ), "nada saltado" );
    igual( el.size(), h.aplicados.size(), "todas aplicadas" );
    igual( 1, h.pasosDeshacer, "un solo Ctrl+Z" );
    cierto( tm.nrSteps() > steps + 8, "steps nuevos: " + steps + " -> " + tm.nrSteps() );
    // un Ctrl+Z: todas las acciones del grupo, salvo la primera, se encadenan a la anterior
    int encadenadas = 0;
    for ( TransAction ta = tm.viewThisUndo(); ta != null && ta.getNextAlso(); ) {
      encadenadas++;
      tm.previousUndo();
      ta = tm.viewThisUndo();
    }
    igual( undo.acciones() - 1, encadenadas, "acciones encadenadas" );
    for ( int i = 0; i < encadenadas; i++ ) {
      tm.nextUndo(); // volver al tope del historial
    }
    // la carga queda tolerante
    for ( String n : new String[] { "Convertir tipos", "Cargar clientes", "Cargar ventas" } ) {
      StepErrorMeta em = tm.findStep( n ).getStepErrorMeta();
      noNulo( em, "manejo de errores en " + n );
      cierto( em.isEnabled(), "habilitado en " + n );
      igual( "motivo", em.getErrorDescriptionsValuename(), "motivo en " + n );
      igual( "campos_error", em.getErrorFieldsValuename(), "campos_error en " + n );
      cierto( tm.findTransHop( tm.findStep( n ), em.getTargetStep() ).isErrorHop(), "hop de error de " + n );
    }
    StepMeta filtro = tm.findStep( "¿ventas completo?" );
    noNulo( filtro, "el filtro de obligatorios" );
    contiene( filtro.getXML(), "<send_true_to>Convertir tipos</send_true_to>", "verdadero → la conversión" );
    contiene( filtro.getXML(), "<send_false_to>Motivo ventas incompleto</send_false_to>", "falso → el motivo" );
    igual( "harden_etl:required_fields", filtro.getAttribute( RobustecerAnalisis.GRUPO, RobustecerAnalisis.ADDED_BY ),
      "marcado como agregado" );
    StepMeta quitar = tm.findStep( "Quitar repetidos dim_cliente" );
    noNulo( quitar, "deduplicar antes de la dimensión" );
    igual( "duplicates", quitar.getAttribute( RobustecerAnalisis.GRUPO, RobustecerAnalisis.DROPS ), "declarado" );
    noNulo( tm.findStep( "Esperar dim_cliente" ), "esperar a la dimensión" );
    // la búsqueda y la dimensión comen de la misma fuente: un Blocking step (todas las filas) va antes de la espera
    StepMeta acumular = tm.findStep( "Acumular para dim_cliente" );
    noNulo( acumular, "fuente compartida: Blocking step antes de la espera" );
    igual( "BlockingStep", acumular.getStepID(), "es un Blocking step" );
    contiene( acumular.getXML(), "<pass_all_rows>Y</pass_all_rows>", "que pasa todas las filas" );
    noNulo( tm.findTransHop( tm.findStep( "Convertir tipos" ), acumular ), "fuente -> Acumular" );
    noNulo( tm.findTransHop( acumular, tm.findStep( "Esperar dim_cliente" ) ), "Acumular -> Esperar" );
    nulo( tm.findTransHop( tm.findStep( "Convertir tipos" ), tm.findStep( "Esperar dim_cliente" ) ),
      "la fuente no va directo a la espera" );
    noNulo( tm.findStep( "¿Está en dim_cliente?" ), "el filtro de huérfanos" );
    nulo( tm.findTransHop( tm.findStep( "Leer ventas" ), tm.findStep( "Convertir tipos" ) ), "el hop viejo no está" );
    contiene( tm.findStep( "Leer ventas" ).getXML(), "${Internal.Entry.Current.Directory}/${ARCHIVO_VENTAS}",
      "la entrada con el parámetro" );
    igual( "ventas.csv", tm.getParameterDefault( "ARCHIVO_VENTAS" ), "el parámetro con su default" );
    String xmlDespues = tm.getXML();
    contiene( xmlDespues, "<descriptions_valuename>motivo</descriptions_valuename>", "en el XML" );
    // vuelve a cargar tal cual (lo que haría abrir el .ktr guardado)
    File dir = EntornoKettle.salida( "robustecer" );
    File f = new File( dir, "aplicado.ktr" );
    EntornoKettle.escribir( f, xmlDespues );
    TransMeta otra = new TransMeta( f.getPath() );
    igual( tm.nrSteps(), otra.nrSteps(), "se abre con los mismos steps" );
    igual( tm.nrTransHops(), otra.nrTransHops(), "y los mismos hops" );
    // Ctrl+Z
    deshacer( tm );
    igual( steps, tm.nrSteps(), "deshacer quita los steps" );
    igual( hops, tm.nrTransHops(), "y los hops" );
    EntornoKettle.escribir( new File( dir, "antes.xml" ), xmlAntes );
    String deshecho = sinParametros( tm.getXML() );
    EntornoKettle.escribir( new File( dir, "deshecho.xml" ), deshecho );
    for ( String n : new String[] { "Convertir tipos", "Cargar clientes", "Cargar ventas" } ) {
      StepErrorMeta em = tm.findStep( n ).getStepErrorMeta();
      cierto( em == null || !em.isEnabled(), "tras deshacer, sin manejo de errores en " + n );
      nulo( RobustecerAplicar.manejoDeErrores( tm.findStep( n ) ), "tras deshacer, como antes: ninguno en " + n );
    }
    igual( xmlAntes, deshecho, "deshacer devuelve el XML de antes (salvo el parámetro, fuera del undo)" );
    // guardado y vuelto a abrir: ningún manejo de errores habilitado apunta a un step que ya no está
    File fd = new File( dir, "deshecho.ktr" );
    EntornoKettle.escribir( fd, tm.getXML() );
    TransMeta vuelta = new TransMeta( fd.getPath() );
    nulo( vuelta.findStep( "Limpiar motivo ventas" ), "la cola ya no está" );
    for ( StepMeta s : vuelta.getSteps() ) {
      falso( s.isDoingErrorHandling(), "sin manejo de errores al volver a abrir: " + s.getName() );
      nulo( s.getStepErrorMeta(), "ni un <error> apagado al volver a abrir: " + s.getName() );
    }
    // otra pasada: el plan ya no propone lo aplicado (idempotente)
  }

  /**
   * 0.6.1: aplicar (las recetas auto: colas de rechazo con motivo) y deshacer deja el XML idéntico byte a byte, sin los
   * dos &lt;error&gt; apagados y sin destino que dejaba 0.6.0. Rehacer vuelve a lo aplicado, y deshacer otra vez al
   * original; un manejo de errores apagado que la persona ya tenía se conserva tal cual.
   */
  public void pruebaDeshacerIdentico() throws Exception {
    TransMeta tm = antes( "dw.sqlite" );
    // uno apagado de la persona en Cargar ventas: Ctrl+Z lo devuelve igual
    StepErrorMeta suyo = new StepErrorMeta( tm, tm.findStep( "Cargar ventas" ) );
    suyo.setEnabled( false );
    suyo.setMaxErrors( "7" );
    tm.findStep( "Cargar ventas" ).setStepErrorMeta( suyo );
    String original = tm.getXML();
    RobustecerAnalisis.Pedido p = pedido( "{\"dry_run\":false}" );
    RobustecerAplicar.Hecho h = RobustecerAplicar.aplicar( tm, RobustecerAnalisis.elegir( RobustecerAnalisis.analizar(
      tm, p ), p ), UndoGrupo.sinSpoon( tm ), null );
    igual( "[]", saltados( h ), "nada saltado" );
    cierto( h.aplicados.size() >= 2, "colas aplicadas: " + h.aplicados );
    String aplicado = tm.getXML();
    cierto( !aplicado.equals( original ), "aplicar cambia el XML" );
    deshacer( tm );
    String deshecho = tm.getXML();
    File dir = EntornoKettle.salida( "robustecer-deshacer" );
    EntornoKettle.escribir( new File( dir, "original.ktr" ), original );
    EntornoKettle.escribir( new File( dir, "deshecho.ktr" ), deshecho );
    igual( original, deshecho, "deshacer devuelve el XML idéntico (sin <error> apagados de más)" );
    contiene( deshecho, "<max_errors>7</max_errors>", "el apagado de la persona sigue" );
    igual( 1, deshecho.split( "<is_enabled>N</is_enabled>", -1 ).length - 1, "solo el <error> apagado de la persona" );
    nulo( RobustecerAplicar.manejoDeErrores( tm.findStep( "Convertir tipos" ) ), "Convertir tipos: como antes" );
    // rehacer (como Spoon: replaceMeta con el después) y deshacer otra vez
    for ( TransAction ta = tm.nextUndo(); ta != null; ta = tm.nextUndo() ) {
      int[] idx = ta.getCurrentIndex();
      if ( ta.getType() == TransAction.TYPE_ACTION_NEW_STEP ) {
        for ( int i = 0; i < idx.length; i++ ) {
          tm.addStep( idx[ i ], (StepMeta) ta.getCurrent()[ i ] );
        }
      } else if ( ta.getType() == TransAction.TYPE_ACTION_NEW_HOP ) {
        for ( int i = 0; i < idx.length; i++ ) {
          tm.addTransHop( idx[ i ], (TransHopMeta) ta.getCurrent()[ i ] );
        }
      } else if ( ta.getType() == TransAction.TYPE_ACTION_DELETE_HOP ) {
        for ( int i = idx.length - 1; i >= 0; i-- ) {
          tm.removeTransHop( idx[ i ] );
        }
      } else if ( ta.getType() == TransAction.TYPE_ACTION_CHANGE_STEP ) {
        for ( int i = 0; i < idx.length; i++ ) {
          tm.getStep( idx[ i ] ).replaceMeta( (StepMeta) ( (StepMeta) ta.getCurrent()[ i ] ).clone() );
        }
      } else {
        Verificador.falla( "acción de rehacer inesperada: " + ta.getType() );
      }
      TransAction sig = tm.viewNextUndo();
      if ( sig == null || !sig.getNextAlso() ) {
        break;
      }
    }
    TransOps.resolverReferencias( tm );
    cierto( tm.findStep( "Convertir tipos" ).isDoingErrorHandling(), "rehacer vuelve a poner el manejo de errores" );
    contiene( tm.getXML(), "<descriptions_valuename>motivo</descriptions_valuename>", "y se guarda" );
    deshacer( tm );
    EntornoKettle.escribir( new File( dir, "deshecho2.ktr" ), tm.getXML() );
    igual( original, tm.getXML(), "deshacer otra vez: el XML original" );
    // si la persona habilita después el «no tenía» (el diálogo de Spoon edita ese mismo objeto), se guarda
    StepErrorMeta em = tm.findStep( "Convertir tipos" ).getStepErrorMeta();
    noNulo( em, "el «no tenía» que deja Ctrl+Z" );
    em.setTargetStep( tm.findStep( "Cargar ventas" ) );
    em.setEnabled( true );
    contiene( tm.getXML(), "<target_step>Cargar ventas</target_step>", "habilitado a mano: se guarda" );
  }

  /** Aplicar dos veces no duplica nada: la segunda pasada no propone lo que ya está. */
  public void pruebaSegundaPasada() throws Exception {
    TransMeta tm = antes( "dw.sqlite" );
    RobustecerAnalisis.Pedido p = pedido( "{\"recipes\":[\"all\"],\"confirm\":[\"all\"],\"dry_run\":false}" );
    RobustecerAplicar.aplicar( tm, RobustecerAnalisis.elegir( RobustecerAnalisis.analizar( tm, p ), p ),
      UndoGrupo.sinSpoon( tm ), null );
    RobustecerAnalisis.Plan plan = RobustecerAnalisis.analizar( tm, p );
    List<Propuesta> el = RobustecerAnalisis.elegir( plan, p );
    igual( "[]", keys( el ).toString(), "nada más que aplicar: " + tm.findStep( "Convertir tipos" ).getXML() );
  }

  /** R02: un manejo de errores sin motivo recibe motivo/campos_error y su archivo los escribe. */
  public void pruebaMotivoQueFalta() throws Exception {
    TransMeta tm = antes( "dw.sqlite" );
    TransOps.agregarStep( tm, "TextFileOutput", "Rechazos a mano", "<file><name>rechazos</name><extention>csv"
      + "</extention></file><fields><field><name>id_cliente</name><type>String</type><length>-1</length></field>"
      + "</fields>", 480, 500, null, null, null );
    TransOps.conectar( tm, tm.findStep( "Cargar clientes" ), tm.findStep( "Rechazos a mano" ), true, true );
    RobustecerAnalisis.Pedido p = pedido( "{\"dry_run\":false}" );
    RobustecerAnalisis.Plan plan = RobustecerAnalisis.analizar( tm, p );
    Propuesta r02 = propuesta( plan.propuestas, "harden:reject_reason:Cargar clientes" );
    noNulo( r02, "R02: " + keys( plan.propuestas ) );
    nulo( propuesta( plan.propuestas, "harden:rejects_on_error:Cargar clientes" ), "R01 no: ya tiene hop de error" );
    contiene( r02.diff(), "~ Rechazos a mano: also writes motivo, campos_error", "el archivo escribe el motivo" );
    RobustecerAplicar.Hecho h = RobustecerAplicar.aplicar( tm, RobustecerAnalisis.elegir( plan, p ),
      UndoGrupo.sinSpoon( tm ), null );
    igual( "[]", saltados( h ), "nada saltado" );
    igual( "motivo", tm.findStep( "Cargar clientes" ).getStepErrorMeta().getErrorDescriptionsValuename(), "motivo" );
    contiene( tm.findStep( "Rechazos a mano" ).getXML(), "<name>motivo</name>", "campo motivo declarado" );
  }

  /**
   * Un manejo de errores apagado con nombres propios (desc_error) y un tope de errores: R01 lo reusa con los nombres
   * de su cola (motivo, campos_error) y sin tope; si no, la cola no encontraría 'motivo' y abortaría.
   */
  public void pruebaManejoApagadoConNombresPropios() throws Exception {
    TransMeta tm = antes( "dw.sqlite" );
    StepMeta conv = tm.findStep( "Convertir tipos" );
    StepErrorMeta viejo = new StepErrorMeta( tm, conv, null, "", "desc_error", "campo_mal", "" );
    viejo.setEnabled( false );
    viejo.setMaxErrors( "3" );
    conv.setStepErrorMeta( viejo );
    RobustecerAnalisis.Pedido p = pedido( "{\"dry_run\":false,\"steps\":[\"Convertir tipos\"]}" );
    RobustecerAnalisis.Plan plan = RobustecerAnalisis.analizar( tm, p );
    noNulo( propuesta( plan.propuestas, "harden:rejects_on_error:Convertir tipos" ), "R01: " + keys( plan.propuestas ) );
    RobustecerAplicar.Hecho h = RobustecerAplicar.aplicar( tm, RobustecerAnalisis.elegir( plan, p ),
      UndoGrupo.sinSpoon( tm ), null );
    igual( "[]", saltados( h ), "nada saltado" );
    StepErrorMeta em = tm.findStep( "Convertir tipos" ).getStepErrorMeta();
    cierto( em.isEnabled(), "habilitado" );
    igual( "motivo", em.getErrorDescriptionsValuename(), "el motivo que espera la cola" );
    igual( "campos_error", em.getErrorFieldsValuename(), "los campos que espera la cola" );
    cierto( em.getMaxErrors() == null || em.getMaxErrors().isEmpty(), "sin tope de errores: " + em.getMaxErrors() );
  }

  // ---------------------------------------------------------------- correr con un archivo roto

  /**
   * La copia robustecida con el archivo roto: termina sin errores, cada fila rota está en un archivo de rechazos con
   * su motivo, las buenas se cargan y la repetida se descarta (declarado). La de antes, con el mismo archivo, aborta.
   */
  public void pruebaCorrerConArchivoRoto() throws Exception {
    File dir = EntornoKettle.salida( "robustecer/corrida" );
    EntornoKettle.borrar( dir.toPath() );
    dir.mkdirs();
    File db = new File( dir, "dw.sqlite" );
    crearTablas( db );
    Files.write( new File( dir, "ventas.csv" ).toPath(), CSV_LIMPIO.getBytes( StandardCharsets.UTF_8 ) );
    Files.write( new File( dir, "ventas_rotas.csv" ).toPath(), CSV_ROTO.getBytes( StandardCharsets.UTF_8 ) );

    // antes: el archivo roto tumba la carga
    TransMeta tm = antes( db.getAbsolutePath() );
    File fa = new File( dir, "carga_antes.ktr" );
    EntornoKettle.escribir( fa, tm.getXML() );
    Trans t = correr( fa, "ventas_rotas.csv", false );
    cierto( t.getErrors() > 0, "antes, el archivo roto aborta la carga" );
    crearTablas( db );

    // robustecer todo y guardar
    RobustecerAnalisis.Pedido p = pedido( "{\"recipes\":[\"all\"],\"confirm\":[\"all\"],\"dry_run\":false,"
      + "\"language\":\"es\"}" );
    RobustecerAplicar.Hecho h = RobustecerAplicar.aplicar( tm, RobustecerAnalisis.elegir( RobustecerAnalisis.analizar(
      tm, p ), p ), UndoGrupo.sinSpoon( tm ), null );
    igual( "[]", saltados( h ), "nada saltado" );
    File fd = new File( dir, "carga_robusta.ktr" );
    EntornoKettle.escribir( fd, tm.getXML() );

    // el limpio: 0 rechazos
    t = correr( fd, "ventas.csv", true );
    igual( 0, t.getErrors(), "el limpio corre sin errores" );
    igual( 3, filas( db, "dim_cliente" ), "3 clientes" );
    igual( 4, filas( db, "fact_venta" ), "4 ventas" );
    igual( 0, rechazos( dir ).size(), "sin rechazos: " + rechazos( dir ) );
    String claves = claves( db );
    // otra vez el limpio: mismas filas (Insert/Update) y mismas claves
    t = correr( fd, "ventas.csv", true );
    igual( 0, t.getErrors(), "segunda corrida sin errores" );
    igual( 3, filas( db, "dim_cliente" ), "sigue con 3 clientes" );
    igual( 4, filas( db, "fact_venta" ), "sigue con 4 ventas" );
    igual( claves, claves( db ), "mismas claves" );

    // el roto: no aborta; cada rota a rechazos con motivo
    t = correr( fd, "ventas_rotas.csv", true );
    igual( 0, t.getErrors(), "el roto no aborta" );
    List<String[]> r = rechazos( dir );
    igual( 8, r.size(), "8 filas rotas rechazadas: " + texto( r ) );
    for ( String[] x : r ) {
      cierto( x[ 1 ] != null && !x[ 1 ].trim().isEmpty(), "con motivo: " + Arrays.toString( x ) );
    }
    contiene( texto( r ), "Faltan campos obligatorios: id_cliente", "el motivo de la clave vacía" );
    contiene( texto( r ), "Faltan campos obligatorios: fecha", "el motivo de la fecha vacía" );
    contiene( texto( r ), "CHECK constraint failed", "el motivo de la BD" );
    contiene( texto( r ), "No está en dim_cliente: id_cliente=C07", "el huérfano con su motivo" );
    noContiene( texto( r ), "KettleDatabaseException: |", "sin la clase de la excepción delante" );
    igual( 4, filas( db, "dim_cliente" ), "las buenas se cargan (C08 también)" );
    igual( 4, filas( db, "fact_venta" ), "ninguna venta rota se cargó y no se duplicó nada" );
    igual( claves, claves( db ).substring( 0, claves.length() ), "las claves existentes no cambian" );
  }

  /**
   * R11 con fuente compartida y más filas que el row set (aquí 3, para no necesitar 10000): sin el Blocking step
   * delante de la espera la carga se colgaría (la fuente se queda en putRow y la dimensión no termina nunca). Con las
   * recetas por defecto termina, sin errores, y carga todo.
   */
  public void pruebaEsperaConFuenteCompartidaNoSeCuelga() throws Exception {
    File dir = EntornoKettle.salida( "robustecer/espera" );
    EntornoKettle.borrar( dir.toPath() );
    dir.mkdirs();
    File db = new File( dir, "dw.sqlite" );
    crearTablas( db );
    StringBuilder csv = new StringBuilder( "id_cliente;nombre;ciudad;fecha;importe\n" );
    for ( int i = 1; i <= 30; i++ ) {
      csv.append( String.format( "C%03d;Nombre %d;Ciudad;2021-03-%02d;%d\n", i, i, 1 + i % 28, i ) );
    }
    Files.write( new File( dir, "ventas.csv" ).toPath(), csv.toString().getBytes( StandardCharsets.UTF_8 ) );
    TransMeta tm = antes( db.getAbsolutePath() );
    tm.setSizeRowset( 3 );
    RobustecerAnalisis.Pedido p = pedido( "{\"dry_run\":false,\"language\":\"en\"}" );
    RobustecerAnalisis.Plan plan = RobustecerAnalisis.analizar( tm, p );
    Propuesta r11 = propuesta( plan.propuestas, "harden:wait_for_loads:Buscar clave cliente" );
    noNulo( r11, "R11: " + keys( plan.propuestas ) );
    igual( Receta.AUTO, r11.nivel, "sigue siendo auto" );
    contiene( r11.diff(), "Blocking step", "con el Blocking step delante: " + r11.diff() );
    RobustecerAplicar.aplicar( tm, RobustecerAnalisis.elegir( plan, p ), UndoGrupo.sinSpoon( tm ), null );
    File f = new File( dir, "carga.ktr" );
    EntornoKettle.escribir( f, tm.getXML() );
    Trans t = new Trans( new TransMeta( f.getPath() ) );
    t.execute( null );
    long fin = System.currentTimeMillis() + 90000L;
    while ( !t.isFinished() && System.currentTimeMillis() < fin ) {
      Thread.sleep( 200 );
    }
    boolean termino = t.isFinished();
    if ( !termino ) {
      t.stopAll();
    }
    cierto( termino, "no se cuelga con la fuente compartida" );
    igual( 0, t.getErrors(), "sin errores" );
    igual( 30, filas( db, "dim_cliente" ), "30 clientes" );
    igual( 30, filas( db, "fact_venta" ), "30 ventas, todas con su clave" );

    // la prueba de que hacía falta: la misma copia sin el Blocking step (otra BD) se cuelga
    File db2 = new File( dir, "dw_sin.sqlite" );
    crearTablas( db2 );
    TransMeta sin = new TransMeta( f.getPath() );
    sin.findDatabase( "dw" ).setDBName( db2.getAbsolutePath() );
    StepMeta acu = sin.findStep( "Buffer for dim_cliente" );
    StepMeta esp = sin.findStep( "Wait for dim_cliente" );
    StepMeta conv = sin.findStep( "Convertir tipos" );
    noNulo( acu, "el Blocking step en la copia" );
    sin.removeTransHop( sin.indexOfTransHop( sin.findTransHop( conv, acu ) ) );
    sin.removeTransHop( sin.indexOfTransHop( sin.findTransHop( acu, esp ) ) );
    sin.removeStep( sin.indexOfStep( acu ) );
    sin.addTransHop( new TransHopMeta( conv, esp ) );
    Trans t2 = new Trans( sin );
    t2.execute( null );
    long fin2 = System.currentTimeMillis() + 8000L;
    while ( !t2.isFinished() && System.currentTimeMillis() < fin2 ) {
      Thread.sleep( 200 );
    }
    boolean colgada = !t2.isFinished();
    t2.stopAll();
    long fin3 = System.currentTimeMillis() + 15000L;
    while ( !t2.isFinished() && System.currentTimeMillis() < fin3 ) {
      Thread.sleep( 200 );
    }
    cierto( colgada, "sin el Blocking step se cuelga" );
  }

  static Trans correr( File ktr, String archivo, boolean conParametro ) throws Exception {
    TransMeta tm = new TransMeta( ktr.getPath() );
    if ( !conParametro ) {
      tm.findStep( "Leer ventas" ); // la de antes lee ventas.csv fijo: se cambia el archivo en disco
      Files.copy( new File( ktr.getParentFile(), archivo ).toPath(), new File( ktr.getParentFile(), "ventas_antes.csv" )
        .toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING );
      String x = EntornoKettle.leer( ktr ).replace( "/ventas.csv</filename>", "/ventas_antes.csv</filename>" );
      File otra = new File( ktr.getParentFile(), "antes_con_" + archivo.replace( ".csv", "" ) + ".ktr" );
      EntornoKettle.escribir( otra, x );
      tm = new TransMeta( otra.getPath() );
    }
    Trans t = new Trans( tm );
    if ( conParametro ) {
      t.setParameterValue( "ARCHIVO_VENTAS", archivo );
      t.activateParameters();
    }
    t.execute( null );
    t.waitUntilFinished();
    return t;
  }

  static void crearTablas( File db ) throws Exception {
    Class.forName( "org.sqlite.JDBC" );
    try ( Connection c = DriverManager.getConnection( "jdbc:sqlite:" + db.getAbsolutePath() );
          Statement s = c.createStatement() ) {
      s.execute( "DROP TABLE IF EXISTS dim_cliente" );
      s.execute( "DROP TABLE IF EXISTS fact_venta" );
      s.execute( "CREATE TABLE dim_cliente (sk_cliente INTEGER PRIMARY KEY, id_cliente VARCHAR(10) NOT NULL UNIQUE, "
        + "nombre VARCHAR(40) CHECK(length(nombre) <= 40), ciudad VARCHAR(30))" );
      s.execute( "CREATE TABLE fact_venta (sk_cliente INTEGER NOT NULL, fecha DATE NOT NULL, importe NUMERIC(10,2) "
        + "CHECK(importe BETWEEN 0 AND 99999), PRIMARY KEY (sk_cliente, fecha))" );
    }
  }

  static int filas( File db, String tabla ) throws Exception {
    try ( Connection c = DriverManager.getConnection( "jdbc:sqlite:" + db.getAbsolutePath() );
          Statement s = c.createStatement(); ResultSet r = s.executeQuery( "SELECT COUNT(*) FROM " + tabla ) ) {
      r.next();
      return r.getInt( 1 );
    }
  }

  static String claves( File db ) throws Exception {
    StringBuilder b = new StringBuilder();
    try ( Connection c = DriverManager.getConnection( "jdbc:sqlite:" + db.getAbsolutePath() );
          Statement s = c.createStatement();
          ResultSet r = s.executeQuery( "SELECT id_cliente, sk_cliente FROM dim_cliente ORDER BY sk_cliente" ) ) {
      while ( r.next() ) {
        b.append( r.getString( 1 ) ).append( '=' ).append( r.getInt( 2 ) ).append( ';' );
      }
    }
    return b.toString();
  }

  /** Las filas de todos los archivos de rechazo: {archivo, motivo, línea}. */
  static List<String[]> rechazos( File dir ) throws Exception {
    List<String[]> l = new ArrayList<String[]>();
    File[] fs = new File( dir, "rechazos" ).listFiles();
    if ( fs == null ) {
      return l;
    }
    Arrays.sort( fs );
    for ( File f : fs ) {
      List<String> lineas = Files.readAllLines( f.toPath(), StandardCharsets.UTF_8 );
      if ( lineas.isEmpty() ) {
        continue;
      }
      List<String> cab = Arrays.asList( lineas.get( 0 ).split( ";", -1 ) );
      int im = cab.indexOf( "motivo" );
      for ( String x : lineas.subList( 1, lineas.size() ) ) {
        if ( x.trim().isEmpty() ) {
          continue;
        }
        String[] c = x.split( ";", -1 );
        l.add( new String[] { f.getName(), im >= 0 && im < c.length ? c[ im ].replace( "\"", "" ) : null, x } );
      }
    }
    return l;
  }

  private static String texto( List<String[]> l ) {
    StringBuilder b = new StringBuilder();
    for ( String[] x : l ) {
      b.append( "\n  " ).append( x[ 0 ] ).append( ": " ).append( x[ 2 ] );
    }
    return b.toString();
  }

  private static String saltados( RobustecerAplicar.Hecho h ) {
    List<String> l = new ArrayList<String>();
    for ( String[] s : h.saltados ) {
      l.add( s[ 0 ] + ": " + s[ 1 ] );
    }
    return l.toString();
  }

  private static String sinParametros( String xml ) {
    return xml.replaceAll( "(?s)<parameters>.*?</parameters>", "<parameters/>" );
  }

  /**
   * Lo que hace Spoon con Ctrl+Z en una transformación (SpoonTransformationDelegate.undoTransformationAction): deshace
   * la acción de arriba y, mientras la que acaba de deshacer tenga getNextAlso(), la anterior.
   */
  static void deshacer( TransMeta tm ) {
    TransAction ta = tm.previousUndo();
    while ( ta != null ) {
      int[] idx = ta.getCurrentIndex();
      switch ( ta.getType() ) {
        case TransAction.TYPE_ACTION_NEW_STEP:
          for ( int i = idx.length - 1; i >= 0; i-- ) {
            tm.removeStep( idx[ i ] );
          }
          break;
        case TransAction.TYPE_ACTION_NEW_HOP:
          for ( int i = idx.length - 1; i >= 0; i-- ) {
            tm.removeTransHop( idx[ i ] );
          }
          break;
        case TransAction.TYPE_ACTION_DELETE_HOP:
          for ( int i = 0; i < idx.length; i++ ) {
            tm.addTransHop( idx[ i ], (TransHopMeta) ta.getCurrent()[ i ] );
          }
          break;
        case TransAction.TYPE_ACTION_CHANGE_STEP:
          for ( int i = 0; i < idx.length; i++ ) {
            StepMeta prev = (StepMeta) ( (StepMeta) ta.getPrevious()[ i ] ).clone();
            tm.getStep( idx[ i ] ).replaceMeta( prev );
          }
          break;
        default:
          Verificador.falla( "acción de undo inesperada: " + ta.getType() );
      }
      if ( !ta.getNextAlso() ) {
        break;
      }
      ta = tm.previousUndo();
    }
    TransOps.resolverReferencias( tm );
  }

  /** Aplicar y deshacer todas las recetas sobre una transformación: devuelve el XML de antes. */
  private static RobustecerAplicar.Hecho aplicarYDeshacer( TransMeta tm, String nombre ) throws Exception {
    String xmlAntes = sinParametros( tm.getXML() );
    RobustecerAnalisis.Pedido p = pedido( "{\"recipes\":[\"all\"],\"confirm\":[\"all\"],\"dry_run\":false}" );
    RobustecerAnalisis.Plan plan = RobustecerAnalisis.analizar( tm, p );
    RobustecerAplicar.Hecho h = RobustecerAplicar.aplicar( tm, RobustecerAnalisis.elegir( plan, p ),
      UndoGrupo.sinSpoon( tm ), null );
    for ( String[] x : h.saltados ) {
      falso( x[ 1 ].startsWith( "failed" ), nombre + ": " + x[ 0 ] + " " + x[ 1 ] );
    }
    System.out.println( "  " + nombre + ": " + keys( plan.propuestas ) + " -> " + h.aplicados.size() + " aplicadas" );
    if ( h.cambios > 0 ) {
      igual( 1, h.pasosDeshacer, nombre + ": un Ctrl+Z" );
      deshacer( tm );
    }
    igual( xmlAntes, sinParametros( tm.getXML() ), nombre + ": deshacer devuelve el XML" );
    return h;
  }

  /** Los ejemplos de las pruebas: el análisis no falla, nada se salta por error y deshacer vuelve al XML. */
  public void pruebaEjemplos() throws Exception {
    File[] fs = EntornoKettle.ejemplos().listFiles();
    Arrays.sort( fs );
    int n = 0;
    for ( File f : fs ) {
      if ( f.getName().endsWith( ".ktr" ) ) {
        aplicarYDeshacer( new TransMeta( f.getPath() ), f.getName() );
        n++;
      }
    }
    cierto( n >= 5, "ejemplos: " + n );
  }

  /** Una entrada con dos salidas: el filtro de obligatorios reparte con un Dummy como lo hacía la entrada. */
  public void pruebaEntradaConVariasSalidas() throws Exception {
    TransMeta tm = antes( "dw.sqlite" );
    TransOps.agregarStep( tm, "Dummy", "Contar", null, 280, 400, "Leer ventas", null, null );
    tm.findStep( "Leer ventas" ).setDistributes( false );
    RobustecerAnalisis.Pedido p = pedido( "{\"recipes\":[\"required_fields\"],\"confirm\":[\"all\"],"
      + "\"language\":\"en\"}" );
    RobustecerAplicar.Hecho h = RobustecerAplicar.aplicar( tm, RobustecerAnalisis.elegir( RobustecerAnalisis.analizar(
      tm, p ), p ), UndoGrupo.sinSpoon( tm ), null );
    igual( 1, h.aplicados.size(), "R03 aplicada: " + saltados( h ) );
    StepMeta f = tm.findStep( "ventas complete?" );
    StepMeta d = tm.findStep( "ventas complete" );
    noNulo( f, "el filtro" );
    noNulo( d, "el reparto" );
    falso( d.isDistributes(), "copia como la entrada" );
    noNulo( tm.findTransHop( tm.findStep( "Leer ventas" ), f ), "entrada -> filtro" );
    noNulo( tm.findTransHop( f, d ), "filtro -> reparto" );
    noNulo( tm.findTransHop( d, tm.findStep( "Convertir tipos" ) ), "reparto -> conversión" );
    noNulo( tm.findTransHop( d, tm.findStep( "Contar" ) ), "reparto -> Contar" );
    noNulo( tm.findTransHop( f, tm.findStep( "Reason ventas incomplete" ) ), "filtro -> motivo" );
    contiene( f.getXML(), "<send_true_to>ventas complete</send_true_to>", "verdadero -> el reparto" );
  }

  /**
   * Un CSV que lee un campo como número: «abc» paraba la carga en la propia entrada. R01 le pone el hop de error (la
   * fila llega con el campo en null y el mensaje de Kettle), y una rama de rechazos que ya pone su motivo con un Add
   * constants no recibe otro (R02). Lo encontró la prueba de punta a punta de R3 sobre una copia del ejemplo.
   */
  private static final String CSV_TIPADO = "<filename>${Internal.Entry.Current.Directory}/tipada.csv</filename>"
    + "<separator>;</separator><enclosure>\"</enclosure><header>Y</header><lazy_conversion>N</lazy_conversion>"
    + "<encoding>UTF-8</encoding><fields><field><name>id</name><type>String</type><format/><length>-1</length>"
    + "<precision>-1</precision><trim_type>both</trim_type></field><field><name>cantidad</name><type>Integer</type>"
    + "<format>#</format><length>-1</length><precision>-1</precision><trim_type>both</trim_type></field></fields>";

  public void pruebaEntradaTipadaAlHopDeError() throws Exception {
    File dir = EntornoKettle.salida( "robustecer-tipada" );
    EntornoKettle.borrar( dir.toPath() );
    dir.mkdirs();
    EntornoKettle.escribir( new File( dir, "tipada.csv" ), "id;cantidad\nA;1\nB;abc\nC;3\n" );
    TransMeta tm = new TransMeta();
    tm.setName( "tipada" );
    TransOps.agregarStep( tm, "CsvInput", "Leer", CSV_TIPADO, 100, 100, null, null, null );
    TransOps.agregarStep( tm, "TextFileOutput", "Escribir", "<separator>;</separator><enclosure>\"</enclosure>"
      + "<header>Y</header><encoding>UTF-8</encoding><file><name>${Internal.Entry.Current.Directory}/ok</name>"
      + "<extention>csv</extention></file><fields/>", 300, 100, "Leer", null, null );
    TransOps.resolverReferencias( tm );
    File ktr = new File( dir, "tipada.ktr" );
    EntornoKettle.escribir( ktr, tm.getXML() );
    tm = new TransMeta( ktr.getPath() );
    RobustecerAnalisis.Pedido p = pedido( "{\"dry_run\":false}" );
    RobustecerAnalisis.Plan plan = RobustecerAnalisis.analizar( tm, p );
    List<Propuesta> el = RobustecerAnalisis.elegir( plan, p );
    cierto( keys( el ).contains( "harden:rejects_on_error:Leer" ), "R01 en la entrada tipada: " + keys( el ) );
    RobustecerAplicar.Hecho h = RobustecerAplicar.aplicar( tm, el, UndoGrupo.sinSpoon( tm ), null );
    cierto( tm.findStep( "Leer" ).isDoingErrorHandling(), "la entrada manda sus errores: " + saltados( h ) );
    EntornoKettle.escribir( ktr, tm.getXML() );
    Trans t = new Trans( new TransMeta( ktr.getPath() ) );
    t.execute( null );
    t.waitUntilFinished();
    igual( 0, t.getErrors(), "no aborta" );
    List<String> ok = Files.readAllLines( new File( dir, "ok.csv" ).toPath(), StandardCharsets.UTF_8 );
    igual( 3, ok.size(), "cabecera + A + C: " + ok );
    List<String[]> r = rechazos( dir );
    igual( 1, r.size(), "B rechazada" );
    cierto( r.get( 0 )[ 1 ] != null && !r.get( 0 )[ 1 ].trim().isEmpty(), "con motivo: " + r.get( 0 )[ 2 ] );

    // la rama de rechazos que ya pone su motivo (Add constants motivo_rechazo) no recibe otro
    TransMeta g = new TransMeta();
    g.setName( "tipada_con_motivo" );
    TransOps.agregarStep( g, "CsvInput", "Leer", CSV_TIPADO, 100, 100, null, null, null );
    StepMeta leer = g.findStep( "Leer" );
    TransOps.agregarStep( g, "Constant", "Marcar", "<fields><field><name>motivo_rechazo</name><type>String</type>"
      + "<nullif>CANTIDAD_NO_VALIDA</nullif><length>-1</length><precision>-1</precision></field></fields>", 100, 300,
      null, null, null );
    TransOps.agregarStep( g, "TextFileOutput", "Rechazos", "<separator>;</separator><header>Y</header><file><name>"
      + "${Internal.Entry.Current.Directory}/rech</name><extention>csv</extention></file><fields><field><name>id"
      + "</name><type>String</type></field><field><name>motivo_rechazo</name><type>String</type></field></fields>",
      300, 300, "Marcar", null, null );
    TransHopMeta eh = new TransHopMeta( leer, g.findStep( "Marcar" ) );
    eh.setErrorHop( true );
    g.addTransHop( eh );
    StepErrorMeta em = new StepErrorMeta( g, leer, g.findStep( "Marcar" ) );
    em.setEnabled( true );
    leer.setStepErrorMeta( em );
    TransOps.resolverReferencias( g );
    List<String> k = keys( RobustecerAnalisis.analizar( g, pedido( "{}" ) ).propuestas );
    falso( k.contains( "harden:reject_reason:Leer" ), "la rama ya pone su motivo: " + k );
    io.github.ivi2511.pentahomcp.lint.Opciones op = new io.github.ivi2511.pentahomcp.lint.Opciones();
    op.robustez = true;
    op.entorno = new LintEntorno( g );
    String lint = io.github.ivi2511.pentahomcp.lint.LintJson.resultado( io.github.ivi2511.pentahomcp.lint.LintMotor
      .analizar( g, op ) ).toString();
    noContiene( lint, "rejects_without_reason", "el linter tampoco lo marca" );
  }

  /** Una dimensión cuya clave sale de un Add sequence: deduplicar por esa clave no quita nada, R06 lo explica. */
  public void pruebaDedupeConClaveDeContador() throws Exception {
    TransMeta tm = new TransMeta();
    tm.setName( "dim_contador" );
    tm.addDatabase( new DatabaseMeta( "dw", "SQLITE", "Native", "", "dw_contador.sqlite", "", "", "" ) );
    TransOps.agregarStep( tm, "CsvInput", "Leer", CSV_TIPADO.replace( "<type>Integer</type>", "<type>String</type>" ),
      100, 100, null, null, null );
    TransOps.agregarStep( tm, "Sequence", "Numerar", "<valuename>sk</valuename><use_database>N</use_database>"
      + "<use_counter>Y</use_counter><start_at>1</start_at><increment_by>1</increment_by><max_value>999999999"
      + "</max_value>", 300, 100, "Leer", null, null );
    TransOps.agregarStep( tm, "InsertUpdate", "Cargar", "<connection>dw</connection><commit>100</commit>"
      + "<update_bypassed>N</update_bypassed><lookup><schema/><table>dim</table>" + clave( "sk" ) + valor( "sk", "N" )
      + valor( "id", "Y" ) + "</lookup>", 500, 100, "Numerar", null, null );
    TransOps.resolverReferencias( tm );
    RobustecerAnalisis.Plan plan = RobustecerAnalisis.analizar( tm, pedido( "{\"recipes\":[\"all\"]}" ) );
    falso( keys( plan.propuestas ).contains( "harden:dedupe_before_load:Cargar" ), "sin dedupe por sk: "
      + keys( plan.propuestas ) );
    StringBuilder d = new StringBuilder();
    for ( String[] x : plan.descartadas ) {
      d.append( Arrays.toString( x ) );
    }
    contiene( d.toString(), "comes from the counter 'Numerar'", "lo explica" );
  }

  /** El patrón 06 de la guía ya es tolerante: harden_etl no propone nada automático. */
  public void pruebaPatronTolerante() throws Exception {
    File ktr = new File( EntornoKettle.recursos().getParentFile().getParentFile().getParentFile().getParentFile(),
      "claude/skills/pentaho-etl/patrones/06_carga_tolerante/06_carga_tolerante.ktr" );
    if ( !ktr.isFile() ) {
      Verificador.saltear( "sin el patrón 06" );
    }
    TransMeta tm = new TransMeta( ktr.getPath() );
    RobustecerAnalisis.Pedido p = pedido( "{\"recipes\":[\"all\"]}" );
    RobustecerAnalisis.Plan plan = RobustecerAnalisis.analizar( tm, p );
    List<String> auto = new ArrayList<String>();
    for ( Propuesta x : plan.propuestas ) {
      if ( Receta.AUTO.equals( x.nivel ) ) {
        auto.add( x.key );
      }
    }
    List<String> desc = new ArrayList<String>();
    for ( String[] d : plan.descartadas ) {
      desc.add( Arrays.toString( d ) );
    }
    System.out.println( "  patrón 06: " + keys( plan.propuestas ) + "; descartadas " + desc );
    igual( "[]", auto.toString(), "ninguna auto sobre el patrón ya tolerante" );
  }

  /** El sub-puntaje de robustez del linter sube tras robustecer (si la familia robustness está en esta versión). */
  public void pruebaPuntajeDeRobustez() throws Exception {
    TransMeta tm = antes( "dw.sqlite" );
    ObjectNode a = RobustezTools.puntaje( tm, "en", true );
    if ( a == null ) {
      Verificador.saltear( "el linter no tiene todavía la familia robustness" );
    }
    RobustecerAnalisis.Pedido p = pedido( "{\"recipes\":[\"all\"],\"confirm\":[\"all\"],\"dry_run\":false}" );
    RobustecerAplicar.aplicar( tm, RobustecerAnalisis.elegir( RobustecerAnalisis.analizar( tm, p ), p ),
      UndoGrupo.sinSpoon( tm ), null );
    ObjectNode d = RobustezTools.puntaje( tm, "en", true );
    System.out.println( "  robustez: antes " + a + ", después " + d );
    cierto( d.path( "score" ).asInt() > a.path( "score" ).asInt(), "sube: " + a + " -> " + d );
  }

  /** Para la respuesta de la herramienta: el JSON es válido y en inglés salvo los nombres. */
  public void pruebaRespuesta() throws Exception {
    TransMeta tm = antes( "dw.sqlite" );
    RobustecerAnalisis.Pedido p = pedido( "{\"recipes\":[\"all\"]}" );
    RobustecerAnalisis.Plan plan = RobustecerAnalisis.analizar( tm, p );
    List<Propuesta> el = RobustecerAnalisis.elegir( plan, p );
    ObjectNode o = RobustezTools.respuesta( "T", plan, p, el, RobustecerAplicar.aplicar( tm, el,
      UndoGrupo.sinSpoon( tm ), null ) );
    JsonNode props = o.path( "proposals" );
    cierto( props.size() >= 10, "todas las propuestas: " + props.size() );
    for ( JsonNode x : props ) {
      cierto( x.path( "key" ).asText().startsWith( "harden:" ), "key: " + x );
      cierto( x.has( "level" ) && x.has( "why" ) && x.has( "semantics_change" ), "campos: " + x );
    }
    contiene( o.path( "next" ).asText(), "confirm=[keys]", "pide confirmar" );
    cierto( o.has( "robustness_before" ), "robustness_before (null hasta que la herramienta lo completa)" );
  }
  // ---------------------------------------------------------------- idioma y lugar de los steps nuevos

  /** Sin language: el idioma de los nombres y notas del documento; language manda. */
  public void pruebaIdiomaDelDocumento() throws Exception {
    TransMeta tm = antes( "dw.sqlite" );
    igual( "es", RobustecerAnalisis.idiomaDe( tm ), "nombres en español" );
    RobustecerAnalisis.Plan plan = RobustecerAnalisis.analizar( tm, pedido( "{\"recipes\":[\"all\"]}" ) );
    String todo = diffs( plan );
    contiene( todo, "Limpiar motivo ventas", "por defecto, en el idioma del documento" );
    contiene( todo, "Escribir rechazos ventas", "por defecto, en el idioma del documento" );
    noContiene( todo, "Clean reason", "nada en inglés" );
    plan = RobustecerAnalisis.analizar( antes( "dw.sqlite" ), pedido( "{\"recipes\":[\"all\"],"
      + "\"language\":\"en\"}" ) );
    todo = diffs( plan );
    contiene( todo, "Clean reason ventas", "language manda" );
    contiene( todo, "Write rejects ventas", "language manda" );

    TransMeta en = new TransMeta();
    TransOps.agregarStep( en, "Dummy", "Read sales file", null, 100, 100, null, null, null );
    TransOps.agregarStep( en, "Dummy", "Filter rows", null, 270, 100, "Read sales file", null, null );
    igual( "en", RobustecerAnalisis.idiomaDe( en ), "inglés" );
    TransMeta pt = new TransMeta();
    TransOps.agregarStep( pt, "Dummy", "Ler vendas", null, 100, 100, null, null, null );
    TransOps.agregarStep( pt, "Dummy", "Gravar dados da tabela", null, 270, 100, "Ler vendas", null, null );
    igual( "pt", RobustecerAnalisis.idiomaDe( pt ), "portugués" );
    TransMeta nota = new TransMeta();
    TransOps.agregarStep( nota, "Dummy", "X1", null, 100, 100, null, null, null );
    nulo( RobustecerAnalisis.idiomaDe( nota ), "sin palabras: no se nota" );
    nota.addNote( new NotePadMeta( "Esta transformación carga las ventas del día", 100, 200, 300, 40 ) );
    igual( "es", RobustecerAnalisis.idiomaDe( nota ), "por la nota" );
  }

  private static String diffs( RobustecerAnalisis.Plan plan ) {
    StringBuilder b = new StringBuilder();
    for ( Propuesta x : plan.propuestas ) {
      b.append( x.diff() ).append( '\n' );
    }
    return b.toString();
  }

  /** El ícono de 32 px de un step. */
  private static int[] iconoPrueba( StepMeta s ) {
    return new int[] { s.getLocation().x, s.getLocation().y, s.getLocation().x + 32, s.getLocation().y + 32 };
  }

  /** El rótulo centrado debajo del ícono (estimación propia de la prueba). */
  private static int[] rotuloPrueba( StepMeta s ) {
    int w = Math.max( 32, s.getName().length() * 6 );
    int cx = s.getLocation().x + 16;
    return new int[] { cx - w / 2, s.getLocation().y + 34, cx + w / 2, s.getLocation().y + 48 };
  }

  /** Ícono de 32 px y el rótulo debajo (estimación propia de la prueba, más chica que la del análisis). */
  private static int[] cajaPrueba( StepMeta s ) {
    int w = Math.max( 32, s.getName().length() * 6 );
    int cx = s.getLocation().x + 16;
    return new int[] { cx - w / 2, s.getLocation().y, cx + w / 2, s.getLocation().y + 50 };
  }

  /**
   * Las colas de rechazo (limpiar -> escribir) van a un sitio libre: un carril debajo de su origen, con los rótulos sin
   * pisarse, sin caer sobre otros steps, notas ni hops que ya estaban.
   */
  public void pruebaColasEnLugarLibre() throws Exception {
    TransMeta tm = antes( "dw.sqlite" );
    // una nota justo debajo de la conversión, donde caería la cola
    NotePadMeta nota = new NotePadMeta( "Las filas malas van a rechazos", 230, 290, 230, 50 );
    tm.addNote( nota );
    int[] cajaNota = { 230, 290, 460, 340 };
    List<int[]> hopsAntes = new ArrayList<int[]>();
    for ( TransHopMeta h : tm.getTransHops() ) {
      hopsAntes.add( new int[] { h.getFromStep().getLocation().x + 16, h.getFromStep().getLocation().y + 16,
        h.getToStep().getLocation().x + 16, h.getToStep().getLocation().y + 16 } );
    }
    RobustecerAnalisis.Pedido p = pedido( "{\"recipes\":[\"all\"],\"confirm\":[\"all\"],\"dry_run\":false}" );
    RobustecerAplicar.Hecho h = RobustecerAplicar.aplicar( tm, RobustecerAnalisis.elegir( RobustecerAnalisis.analizar(
      tm, p ), p ), UndoGrupo.sinSpoon( tm ), null );
    igual( "[]", saltados( h ), "nada saltado" );
    List<StepMeta> cola = new ArrayList<StepMeta>();
    for ( StepMeta s : tm.getSteps() ) {
      if ( s.getName().startsWith( "Limpiar motivo " ) || s.getName().startsWith( "Escribir rechazos " ) ) {
        cola.add( s );
      }
    }
    cierto( cola.size() >= 6, "tres colas en español: " + cola );
    for ( StepMeta q : cola ) {
      int[] k = cajaPrueba( q );
      for ( StepMeta s : tm.getSteps() ) {
        if ( s != q ) {
          falso( RobustecerAnalisis.choca( k, cajaPrueba( s ) ), q.getName() + " " + q.getLocation() + " pisa a "
            + s.getName() + " " + s.getLocation() );
        }
      }
      falso( RobustecerAnalisis.choca( k, cajaNota ), q.getName() + " " + q.getLocation() + " sobre la nota" );
      for ( int[] sg : hopsAntes ) {
        falso( RobustecerAnalisis.cruza( sg, k ), q.getName() + " " + q.getLocation() + " sobre el hop "
          + Arrays.toString( sg ) );
      }
    }
    StepMeta l = tm.findStep( "Limpiar motivo ventas" );
    StepMeta e = tm.findStep( "Escribir rechazos ventas" );
    noNulo( l, "la primera cola" );
    noNulo( e, "la primera cola" );
    igual( l.getLocation().y, e.getLocation().y, "limpiar y escribir en el mismo carril" );
    cierto( e.getLocation().x - l.getLocation().x >= TransLayout.DX, "separados como la grilla: " + l.getLocation()
      + " " + e.getLocation() );
    cierto( RobustecerAnalisis.cruza( new int[] { 0, 0, 100, 100 }, new int[] { 40, 40, 60, 60 } ), "cruza: diagonal" );
    falso( RobustecerAnalisis.cruza( new int[] { 0, 0, 100, 0 }, new int[] { 40, 10, 60, 60 } ), "cruza: por fuera" );
  }

  /**
   * 0.6.1: con el lienzo lleno debajo del origen (tres hops verticales largos tapan los cinco carriles de debajo), el
   * último recurso ponía la cola «debajo de todo» y el hop de error bajaba en vertical atravesando los steps de debajo.
   * Ahora la cola va al sitio libre más cercano cuyos hops (origen → limpiar, limpiar → escribir) no pasan por encima de
   * ningún step ni nota.
   */
  public void pruebaColasSinCruzarSteps() throws Exception {
    EntornoKettle.iniciar();
    TransMeta tm = new TransMeta();
    tm.setName( "lleno" );
    TransOps.agregarStep( tm, "CsvInput", "Leer ventas", "<filename>${Internal.Entry.Current.Directory}/ventas.csv"
      + "</filename><separator>;</separator><enclosure>\"</enclosure><header>Y</header><lazy_conversion>N"
      + "</lazy_conversion><encoding>UTF-8</encoding><fields><field><name>id</name><type>String</type><format/>"
      + "<currency/><decimal/><group/><length>-1</length><precision>-1</precision><trim_type>both</trim_type></field>"
      + "<field><name>importe</name><type>Number</type><format>#.##</format><currency/><decimal>.</decimal><group/>"
      + "<length>-1</length><precision>-1</precision><trim_type>both</trim_type></field></fields>", 96, 128, null, null,
      null );
    String previo = "Leer ventas";
    for ( int c = 0; c < 3; c++ ) {
      int x = 96 + 170 * c;
      TransOps.agregarStep( tm, "Dummy", "Arriba " + c, "", x, 200, c == 0 ? previo : null, null, null );
      TransOps.agregarStep( tm, "Dummy", "Abajo " + c, "", x, 3000, "Arriba " + c, null, null );
    }
    TransOps.resolverReferencias( tm );
    String[] mal = cruzanCola( tm, pedido( "{\"dry_run\":false,\"language\":\"es\"}" ), 1 );
    igual( "", mal[ 0 ], "ningún hop de la cola pasa por encima de un step" );
    StepMeta l = tm.findStep( "Limpiar motivo ventas" );
    noNulo( l, "la cola" );
    cierto( l.getLocation().y < 3000, "no se fue debajo de todo: " + l.getLocation().x + "," + l.getLocation().y );
  }

  /**
   * 0.6.1, el lienzo de verdad: la carga de hechos de ejemplo/construido-en-vivo (un hop largo en diagonal, dos notas,
   * steps en tres filas) con más steps debajo. La cola de las ventas la comparten varias fuentes (la entrada, la
   * conversión del precio y el motivo de obligatorios). Con las recetas auto (lo que se vio en Spoon: el hop de error
   * de la entrada bajaba por «Eliminar duplicados») ningún hop pasa por encima de un step; con todas, el filtro de
   * obligatorios encierra a la entrada y la cola va donde menos cruces hay.
   */
  public void pruebaColasEnLienzoLleno() throws Exception {
    EntornoKettle.iniciar();
    File repo = EntornoKettle.recursos().getAbsoluteFile().getParentFile().getParentFile().getParentFile()
      .getParentFile();
    // una copia en la carpeta de la prueba: su base (../salida/…) cae ahí y nunca junto al ejemplo
    File dir = new File( EntornoKettle.salida( "robustecer-lleno" ), "construido" );
    dir.mkdirs();
    File ktr = new File( dir, "04_hechos_ventas.ktr" );
    Files.copy( new File( repo, "ejemplo/construido-en-vivo/04_hechos_ventas.ktr" ).toPath(), ktr.toPath(),
      java.nio.file.StandardCopyOption.REPLACE_EXISTING );
    for ( int vuelta = 0; vuelta < 4; vuelta++ ) {
      String json = vuelta % 2 == 0 ? "{\"dry_run\":false}" : "{\"recipes\":[\"all\"],\"confirm\":[\"all\"],"
        + "\"dry_run\":false}";
      TransMeta tm = new TransMeta( ktr.getAbsolutePath() );
      for ( int i = 0; i < ( vuelta < 2 ? 0 : 8 ); i++ ) { // tal cual, y con una fila más de steps debajo
        StepMeta d = new StepMeta( "Relleno " + i, new org.pentaho.di.trans.steps.dummytrans.DummyTransMeta() );
        d.setLocation( 96 + i * 208, 640 );
        d.setDraw( true );
        tm.addStep( d );
      }
      String[] mal = cruzanCola( tm, pedido( json ), 2 );
      System.out.println( "  lleno " + vuelta + " " + json + ": " + ( mal[ 0 ].isEmpty() ? "sin cruces" : mal[ 0 ] ) );
      if ( json.equals( "{\"dry_run\":false}" ) ) {
        igual( "", mal[ 0 ], "recetas auto: ningún hop de la cola pasa por encima de un step" );
      } else {
        // con todas, el filtro de obligatorios nuevo encierra a la entrada: algún cruce no se puede evitar; los menos
        cierto( mal[ 0 ].split( "\n" ).length <= 2, "todas las recetas: como mucho dos cruces: " + mal[ 0 ] );
      }
    }
  }

  /**
   * Aplica harden_etl y devuelve {los hops de las colas que pasan por encima de un step, uno por línea}; comprueba que
   * hay al menos 'minimo' hops hacia las colas y que sus steps no pisan a ningún otro.
   */
  private static String[] cruzanCola( TransMeta tm, RobustecerAnalisis.Pedido p, int minimo ) throws Exception {
    List<String> originales = new ArrayList<String>();
    for ( StepMeta s : tm.getSteps() ) {
      originales.add( s.getName() );
    }
    RobustecerAnalisis.Plan plan = RobustecerAnalisis.analizar( tm, p );
    RobustecerAplicar.Hecho h = RobustecerAplicar.aplicar( tm, RobustecerAnalisis.elegir( plan, p ), UndoGrupo
      .sinSpoon( tm ), null );
    for ( String[] x : h.saltados ) {
      falso( x[ 1 ].startsWith( "failed" ), x[ 0 ] + " " + x[ 1 ] );
    }
    int hacia = 0;
    StringBuilder malos = new StringBuilder();
    for ( TransHopMeta hop : tm.getTransHops() ) {
      StepMeta de = hop.getFromStep();
      StepMeta a = hop.getToStep();
      if ( !a.getName().startsWith( "Limpiar motivo " ) && !de.getName().startsWith( "Limpiar motivo " ) ) {
        continue;
      }
      hacia += a.getName().startsWith( "Limpiar motivo " ) ? 1 : 0;
      int[] sg = { de.getLocation().x + 16, de.getLocation().y + 16, a.getLocation().x + 16, a.getLocation().y + 16 };
      for ( StepMeta s : tm.getSteps() ) {
        if ( s != de && s != a && ( RobustecerAnalisis.cruza( sg, iconoPrueba( s ) ) || RobustecerAnalisis.cruza( sg,
          rotuloPrueba( s ) ) ) ) {
          malos.append( de.getName() ).append( " (" ).append( de.getLocation().x ).append( ',' ).append( de
            .getLocation().y ).append( ") -> " ).append( a.getName() ).append( " (" ).append( a.getLocation().x )
            .append( ',' ).append( a.getLocation().y ).append( ") pasa por " ).append( s.getName() ).append( '\n' );
        }
      }
    }
    cierto( hacia >= minimo, "hops hacia las colas: " + hacia + "\n" + diffs( plan ) );
    for ( StepMeta q : tm.getSteps() ) {
      if ( originales.contains( q.getName() ) || !q.getName().startsWith( "Limpiar motivo " ) && !q.getName()
        .startsWith( "Escribir rechazos " ) ) {
        continue;
      }
      for ( StepMeta s : tm.getSteps() ) {
        if ( s != q ) {
          falso( RobustecerAnalisis.choca( cajaPrueba( q ), cajaPrueba( s ) ), q.getName() + " " + q.getLocation()
            + " pisa a " + s.getName() + " " + s.getLocation() );
        }
      }
    }
    return new String[] { malos.toString() };
  }
}
