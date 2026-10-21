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
    igual( Receta.MANUAL, r08.nivel, "R08 es manual en la v1" );
    noNulo( r08.comoHacerlo, "con el cómo" );
    cierto( r08.cambios.isEmpty(), "sin cambios" );
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
    }
    igual( xmlAntes, sinErroresApagados( deshecho ), "deshacer devuelve el XML de antes (salvo el parámetro y los "
      + "manejos de errores apagados)" );
    // guardado y vuelto a abrir: ningún manejo de errores habilitado apunta a un step que ya no está
    File fd = new File( dir, "deshecho.ktr" );
    EntornoKettle.escribir( fd, tm.getXML() );
    TransMeta vuelta = new TransMeta( fd.getPath() );
    nulo( vuelta.findStep( "Limpiar motivo ventas" ), "la cola ya no está" );
    for ( StepMeta s : vuelta.getSteps() ) {
      falso( s.isDoingErrorHandling(), "sin manejo de errores al volver a abrir: " + s.getName() );
    }
    // otra pasada: el plan ya no propone lo aplicado (idempotente)
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
    noContiene( texto( r ), "KettleDatabaseException: |", "sin la clase de la excepción delante" );
    igual( 4, filas( db, "dim_cliente" ), "las buenas se cargan (C08 también)" );
    igual( 4, filas( db, "fact_venta" ), "ninguna venta rota se cargó y no se duplicó nada" );
    igual( claves, claves( db ).substring( 0, claves.length() ), "las claves existentes no cambian" );
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

  /** Los manejos de errores apagados y sin destino que deja Ctrl+Z (ver RobustecerAplicar.instantaneaSinErrores). */
  private static String sinErroresApagados( String xml ) {
    return xml.replaceAll( "(?s)\\s*<error>\\s*<source_step>[^<]*</source_step>\\s*<target_step\\s*/>\\s*"
      + "<is_enabled>N</is_enabled>.*?</error>", "" );
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
    igual( xmlAntes, sinErroresApagados( sinParametros( tm.getXML() ) ), nombre + ": deshacer devuelve el XML" );
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
}
