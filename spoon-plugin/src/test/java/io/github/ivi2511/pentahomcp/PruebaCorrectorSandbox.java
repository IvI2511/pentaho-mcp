package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falla;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.lanza;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.pentaho.di.core.logging.LogLevel;
import org.pentaho.di.core.plugins.PluginRegistry;
import org.pentaho.di.core.plugins.StepPluginType;
import org.pentaho.di.core.variables.Variables;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.ivi2511.pentahomcp.CorrectorPolitica.Entrega;
import io.github.ivi2511.pentahomcp.CorrectorPolitica.Revision;
import io.github.ivi2511.pentahomcp.CorrectorSandbox.Preparacion;
import io.github.ivi2511.pentahomcp.CorrectorSandbox.SalidaRedirigida;
import io.github.ivi2511.pentahomcp.corrector.modelo.Caso;
import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.PoliticaPack;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCaso;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.Aviso;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.PasoBloqueado;

/**
 * Sandbox y politica del corrector (C3, docs/corrector-diseno.md §3 y §7): cada entrega de las fixtures da el
 * estado y el motivo de casos.json sin ejecutar nada (las {@code never_run} quedan bloqueadas antes de correr);
 * ana redirigida, diego bloqueado, fede sin ejecutar, P14 y P15 corren y escriben su salida en el sandbox, y leer
 * {@code ../../<otro>/} bloquea. Tambien los trucos del XML que Kettle entiende distinto de como se leen (comentarios,
 * tags repetidos o en mayusculas). Las corridas de {@link #pruebaCorridasEnElSandbox()} son en esta JVM y solo de
 * entregas que pasan la politica y el sandbox. Los sandboxes van en {@code <prueba>/sandbox-c3} (datos/ de solo
 * lectura: las copias de CorrectorRecursos no los sabrian borrar) y se borran con CorrectorSandbox.borrar.
 *
 * @dueño C3
 */
public class PruebaCorrectorSandbox {

  private static final String DIR = "${Internal.Entry.Current.Directory}";
  private static final AtomicInteger LOTES = new AtomicInteger();

  public void preparar() throws Exception {
    EntornoKettle.iniciar();
    CorrectorSandbox.borrar( sandboxes() );
  }

  /** Los sandboxes tienen datos/ de solo lectura: fuera de las copias de CorrectorRecursos, que no los sabe borrar. */
  public void cerrar() throws Exception {
    CorrectorSandbox.borrar( sandboxes() );
  }

  private static File sandboxes() {
    return new File( EntornoKettle.carpeta(), "sandbox-c3" );
  }

  /** Una carpeta de lote nueva (como el output_dir de grade_submissions). */
  private static File lote( String nombre ) {
    return new File( sandboxes(), nombre + "-" + LOTES.incrementAndGet() );
  }

  // ------------------------------------------------------------------ casos.json, sin ejecutar

  /** Todas las entregas: estado y motivo de casos.json (load_failed, blocked con su reason, o puede correr). */
  public void pruebaCasosJson() throws Exception {
    File r = CorrectorRecursos.copia();
    int n = 0;
    for ( JsonNode pj : CorrectorRecursos.casos().path( "packs" ) ) {
      String packId = pj.path( "id" ).asText();
      File lote = lote( "casos-" + packId );
      for ( Iterator<Map.Entry<String, JsonNode>> it = pj.path( "expected" ).fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        String archivo = e.getKey();
        String status = e.getValue().path( "status" ).asText();
        Correccion c = corregir( r, packId, archivo, lote, false, null );
        n++;
        if ( ResultadoCorreccion.NO_CARGO.equals( status ) ) {
          noNulo( c.errorCarga, archivo + ": tiene que fallar al cargar" );
          continue;
        }
        if ( c.errorCarga != null ) {
          falla( archivo + ": no carga: " + c.errorCarga );
        }
        if ( ResultadoCorreccion.BLOQUEADA.equals( status ) ) {
          igual( e.getValue().path( "reason" ).asText(), c.motivo(), archivo + ": motivo (" + c.detalle() + ")" );
          cierto( ResultadoCaso.motivoValido( c.motivo() ), archivo + ": motivo del §6" );
          noNulo( c.detalle(), archivo + ": detalle" );
        } else {
          nulo( c.motivo(), archivo + ": no tiene que bloquear (" + c.detalle() + ")" );
          igual( e.getValue().path( "cases" ).size(), c.casos.size(), archivo + ": un sandbox por caso" );
        }
        if ( e.getValue().path( "never_run" ).asBoolean() ) {
          cierto( c.motivo() != null, archivo + ": never_run tiene que quedar bloqueada antes de correr" );
        }
        JsonNode avisos = e.getValue().path( "warnings" );
        for ( JsonNode a : avisos ) {
          cierto( c.tieneAviso( a.asText() ), archivo + ": aviso " + a.asText() );
        }
      }
    }
    igual( 34, n, "entregas revisadas" );
  }

  // ------------------------------------------------------------------ politica

  public void pruebaNiveles() throws Exception {
    igual( PoliticaPack.SEGURO, CorrectorPolitica.nivel( "CsvInput" ), "CsvInput" );
    igual( PoliticaPack.SEGURO, CorrectorPolitica.nivel( "textfileoutput" ), "sin mayusculas" );
    igual( PoliticaPack.SEGURO, CorrectorPolitica.nivel( "Flatterner" ), "alias de Flattener" );
    igual( PoliticaPack.CONDICIONAL, CorrectorPolitica.nivel( "TableOutput" ), "TableOutput" );
    igual( PoliticaPack.CONDICIONAL, CorrectorPolitica.nivel( "ScriptValueMod" ), "JavaScript" );
    igual( PoliticaPack.CONDICIONAL, CorrectorPolitica.nivel( "ScriptValue" ), "alias de ScriptValueMod" );
    igual( "ScriptValueMod", CorrectorPolitica.canonico( "ScriptValue" ), "canonico" );
    igual( PoliticaPack.PELIGROSO, CorrectorPolitica.nivel( "ExecProcess" ), "Execute a process" );
    igual( PoliticaPack.PELIGROSO, CorrectorPolitica.nivel( "UserDefinedJavaClass" ), "UDJC" );
    igual( PoliticaPack.PELIGROSO, CorrectorPolitica.nivel( "TextFileOutputLegacy" ), "is_command" );
    igual( PoliticaPack.PELIGROSO, CorrectorPolitica.nivel( "AcmeBulkLoader" ), "un plugin que suena a carga masiva" );
    igual( PoliticaPack.PELIGROSO, CorrectorPolitica.nivel( "MiClienteHttp" ), "un plugin que suena a red" );
    igual( PoliticaPack.DESCONOCIDO, CorrectorPolitica.nivel( "PasoQueNoExiste" ), "desconocido" );
    igual( PoliticaPack.DESCONOCIDO, CorrectorPolitica.nivel( "" ), "vacio" );
    igual( PoliticaPack.PELIGROSO, CorrectorPolitica.nivelEntrada( "SHELL" ), "entrada Shell" );
    igual( PoliticaPack.SEGURO, CorrectorPolitica.nivelEntrada( "SPECIAL" ), "entrada Start" );
    // las listas no se pisan y los seguros y condicionales existen en PDI 9.4
    for ( String s : CorrectorPolitica.SEGUROS ) {
      falso( CorrectorPolitica.CONDICIONALES.contains( s ) || CorrectorPolitica.PELIGROSOS.contains( s ),
        s + " en dos niveles" );
    }
    for ( String s : CorrectorPolitica.CONDICIONALES ) {
      falso( CorrectorPolitica.PELIGROSOS.contains( s ), s + " en dos niveles" );
    }
    List<String> faltan = new ArrayList<String>();
    for ( String s : union( CorrectorPolitica.SEGUROS, CorrectorPolitica.CONDICIONALES ) ) {
      boolean esta = false;
      for ( org.pentaho.di.core.plugins.PluginInterface p : PluginRegistry.getInstance().getPlugins(
        StepPluginType.class ) ) {
        for ( String id : p.getIds() ) {
          esta |= id.equalsIgnoreCase( s );
        }
      }
      if ( !esta ) {
        faltan.add( s );
      }
    }
    igual( Collections.emptyList(), faltan, "ids de las listas que PDI 9.4 no tiene" );
  }

  public void pruebaPoliticaDeSteps() throws Exception {
    File r = CorrectorRecursos.copia();
    Pack ventas = pack( r, CorrectorRecursos.VENTAS );

    Entrega diego = cargar( r, CorrectorRecursos.VENTAS, "diego_javascript.ktr" );
    Revision rd = CorrectorPolitica.revisar( diego, ventas, false, null );
    igual( ResultadoCaso.STEP_NO_PERMITIDO, rd.motivo, "diego: condicional sin allow_steps" );
    igual( 1, rd.bloqueados.size(), "diego: un step bloqueado" );
    PasoBloqueado pb = rd.bloqueados.get( 0 );
    igual( "ScriptValueMod", pb.tipo, "diego: tipo" );
    igual( PoliticaPack.CONDICIONAL, pb.nivel, "diego: nivel" );
    contiene( rd.detalle, pb.step, "diego: el detalle nombra el step" );
    cierto( rd.usaCondicionales, "diego: usa un condicional" );
    falso( CorrectorPolitica.revisar( diego, ventas, false, Arrays.asList( "scriptvaluemod" ) ).bloquea(),
      "diego: «Volver a corregir con» ScriptValueMod" );
    falso( CorrectorPolitica.revisar( diego, ventas, true, null ).bloquea(), "diego: modo confianza" );
    ResultadoCorreccion res = new ResultadoCorreccion();
    rd.aplicar( res );
    igual( ResultadoCorreccion.BLOQUEADA, res.estado, "diego: estado de la entrega" );
    igual( ResultadoCaso.STEP_NO_PERMITIDO, res.motivo, "diego: reason de la entrega" );
    igual( 1, res.stepsBloqueados.size(), "diego: blocked_steps" );
    ResultadoCaso rc = new ResultadoCaso();
    rd.aplicar( rc );
    igual( ResultadoCaso.BLOQUEADO, rc.corrida.estado, "diego: caso bloqueado" );

    Entrega nico = cargar( r, CorrectorRecursos.VENTAS, "nico_ejecuta_proceso.ktr" );
    Revision rn = CorrectorPolitica.revisar( nico, ventas, false, Arrays.asList( "ExecProcess" ) );
    igual( ResultadoCaso.STEP_PELIGROSO, rn.motivo, "nico: los extra no habilitan peligrosos" );
    igual( "Ejecutar comando", rn.bloqueados.get( 0 ).step, "nico: el step" );
    igual( PoliticaPack.PELIGROSO, rn.bloqueados.get( 0 ).nivel, "nico: nivel" );
    Revision rnc = CorrectorPolitica.revisar( nico, ventas, true, null );
    falso( rnc.bloquea(), "nico: corre con modo confianza" );
    cierto( rnc.usaPeligrosos, "nico: usa un peligroso" );
    cierto( tieneAviso( rnc.avisos, CorrectorPolitica.AVISO_MODO_CONFIANZA ), "nico: aviso del modo confianza" );

    Entrega oscar = cargar( r, CorrectorRecursos.VENTAS, "oscar_step_desconocido.ktr" );
    Revision ro = CorrectorPolitica.revisar( oscar, ventas, false, null );
    igual( ResultadoCaso.STEP_DESCONOCIDO, ro.motivo, "oscar: desconocido" );
    igual( "PasoQueNoExiste", ro.bloqueados.get( 0 ).tipo, "oscar: tipo" );
    contiene( ro.detalle, "not installed", "oscar: plugin faltante" );
    Pack permisivo = CorrectorJson.copia( ventas, Pack.class );
    permisivo.politica.stepsDesconocidos = PoliticaPack.PERMITIR;
    falso( CorrectorPolitica.revisar( oscar, permisivo, false, null ).bloquea(), "oscar: unknown_steps allow" );

    Entrega pia = cargar( r, CorrectorRecursos.VENTAS, "pia_nombre_repetido.ktr" );
    Revision rp = CorrectorPolitica.revisar( pia, ventas, true, null );
    igual( ResultadoCaso.NOMBRE_REPETIDO, rp.motivo, "pia: D1 aun con modo confianza" );
    igual( Arrays.asList( "totales por producto", "Totales por producto" ), rp.nombresRepetidos, "pia: los dos" );
    igual( 4, pia.tm.nrSteps(), "pia: Kettle funde los steps" );
    boolean aSiMismo = false;
    List<String> hops = new ArrayList<String>();
    for ( int i = 0; i < pia.tm.nrTransHops(); i++ ) {
      org.pentaho.di.trans.TransHopMeta h = pia.tm.getTransHop( i );
      aSiMismo |= h.getFromStep() != null && h.getFromStep() == h.getToStep();
      hops.add( h.toString() );
    }
    cierto( aSiMismo, "pia: el fundido deja un hop de un step a si mismo " + hops );
    falso( rp.enCiclo.isEmpty(), "pia: D2 tambien lo ve (hasLoop no ve un hop a si mismo)" );

    Entrega fede = cargar( r, CorrectorRecursos.VENTAS, "fede_bucle.ktr" );
    Revision rf = CorrectorPolitica.revisar( fede, ventas, false, null );
    igual( ResultadoCaso.CICLO, rf.motivo, "fede: D2" );
    cierto( rf.enCiclo.contains( "Calcular importe" ) && rf.enCiclo.contains( "Escribir totales" ),
      "fede: steps del ciclo " + rf.enCiclo );
    cierto( rf.bloqueados.isEmpty(), "fede: ningun step bloqueado por la politica" );

    Entrega yago = cargar( r, CorrectorRecursos.VENTAS, "yago_entrada_en_campo.ktr" );
    Revision ry = CorrectorPolitica.revisar( yago, ventas, true, null );
    igual( ResultadoCaso.ENTRADA_INESPERADA, ry.motivo, "yago: nombre de archivo desde un campo, aun con confianza" );
    igual( "Leer lo que diga el campo", ry.bloqueados.get( 0 ).step, "yago: el step" );

    Entrega ulises = cargar( r, CorrectorRecursos.VENTAS, "ulises_salida_en_campo.ktr" );
    Revision ru = CorrectorPolitica.revisar( ulises, ventas, false, null );
    igual( ResultadoCaso.SALIDA_INESPERADA, ru.motivo, "ulises: salida con el nombre en un campo" );
    contiene( ru.detalle, "destino", "ulises: el campo" );

    Entrega paula = cargar( r, CorrectorRecursos.VENTAS, "paula_correcta.ktr" );
    Revision rpa = CorrectorPolitica.revisar( paula, ventas, false, null );
    falso( rpa.bloquea(), "paula: corre" );
    falso( rpa.usaCondicionales || rpa.usaBase, "paula: solo steps seguros y sin base" );
    igual( 5, rpa.niveles.size(), "paula: niveles de los 5 steps" );
  }

  public void pruebaSqlProhibido() throws Exception {
    igual( "ATTACH DATABASE", CorrectorPolitica.sqlProhibido( "ATTACH DATABASE 'C:/x.sqlite' AS copia;" ), "ATTACH" );
    igual( "ATTACH DATABASE", CorrectorPolitica.sqlProhibido( "attach 'x.db' as y" ), "sin DATABASE" );
    igual( "ATTACH DATABASE", CorrectorPolitica.sqlProhibido( "ATTACH/**/'x.db'/* c */AS y" ), "con comentarios" );
    igual( "ATTACH DATABASE", CorrectorPolitica.sqlProhibido( "DELETE FROM t;\nATTACH ? AS z" ), "segunda sentencia" );
    igual( "VACUUM INTO", CorrectorPolitica.sqlProhibido( "VACUUM INTO 'C:/Users/Public/x.db'" ), "VACUUM INTO" );
    igual( "VACUUM INTO", CorrectorPolitica.sqlProhibido( "vacuum main into 'x'" ), "con esquema" );
    igual( "load_extension", CorrectorPolitica.sqlProhibido( "SELECT load_extension ('evil.dll')" ), "extension" );
    igual( "PRAGMA temp_store_directory",
      CorrectorPolitica.sqlProhibido( "PRAGMA temp_store_directory = 'C:/x'" ), "pragma" );
    nulo( CorrectorPolitica.sqlProhibido( "DELETE FROM totales" ), "DELETE de mora" );
    nulo( CorrectorPolitica.sqlProhibido( "INSERT INTO totales SELECT * FROM t" ), "INSERT INTO" );
    nulo( CorrectorPolitica.sqlProhibido( "VACUUM; INSERT INTO t VALUES (1)" ), "VACUUM e INTO en otra sentencia" );
    nulo( CorrectorPolitica.sqlProhibido( "-- attach database 'x' as y\nSELECT 1" ), "en un comentario" );
    nulo( CorrectorPolitica.sqlProhibido( "SELECT attached FROM t" ), "otra palabra" );
    nulo( CorrectorPolitica.sqlProhibido( null ), "null" );

    File r = CorrectorRecursos.copia();
    Pack tabla = pack( r, CorrectorRecursos.TABLA );
    Revision rl = CorrectorPolitica.revisar( cargar( r, CorrectorRecursos.TABLA, "lara_attach.ktr" ), tabla, false, null );
    igual( ResultadoCaso.SQL_PROHIBIDO, rl.motivo, "lara: ATTACH" );
    igual( "Preparar base", rl.bloqueados.get( 0 ).step, "lara: el step" );
    igual( PoliticaPack.CONDICIONAL, rl.bloqueados.get( 0 ).nivel, "lara: ExecSQL es condicional" );
    cierto( rl.usaBase, "lara: usa la base" );
    Revision rm = CorrectorPolitica.revisar( cargar( r, CorrectorRecursos.TABLA, "mora_borra_antes.ktr" ), tabla, false,
      null );
    falso( rm.bloquea(), "mora: DELETE FROM permitido (" + rm.detalle + ")" );
    cierto( rm.usaCondicionales, "mora: el worker se descarta despues" );
  }

  // ------------------------------------------------------------------ sandbox

  /** ana: rutas de su PC redirigidas, anotadas, y el archivo original intacto. */
  public void pruebaAnaRedirigida() throws Exception {
    File r = CorrectorRecursos.copia();
    File original = new File( new File( new File( r, "entregas" ), CorrectorRecursos.VENTAS ), "ana_orden_distinto.ktr" );
    byte[] antes = Files.readAllBytes( original.toPath() );
    Correccion c = corregir( r, CorrectorRecursos.VENTAS, "ana_orden_distinto.ktr", lote( "x" ), false, null );
    nulo( c.motivo(), "ana no bloquea" );
    Preparacion p = c.casos.get( "visible" );
    igual( Arrays.asList( "input 'Leer ventas': C:\\Users\\ana\\Escritorio\\PDI\\ventas.csv -> datos/ventas.csv",
      "output 'Escribir totales': C:\\Users\\ana\\Escritorio\\PDI\\resultado -> salida/resultado.csv" ),
      p.redirecciones, "ana: redirecciones (los temporales a tmp/ no se anotan)" );
    String xml = texto( p.ktr );
    contiene( xml, "<filename>" + DIR + "/datos/ventas.csv</filename>", "ana: entrada" );
    contiene( xml, "<name>" + DIR + "/salida/resultado</name>", "ana: salida" );
    contiene( xml, "<directory>" + DIR + "/tmp</directory>", "ana: temporales de Sort rows al tmp/ del caso" );
    noContiene( xml, "Escritorio", "ana: no queda ninguna ruta de su PC" );
    igual( 1, p.salidas.size(), "ana: una salida" );
    SalidaRedirigida sr = p.salidas.get( 0 );
    igual( "Escribir totales", sr.step, "salida: step" );
    igual( "TextFileOutput", sr.tipo, "salida: tipo" );
    igual( "salida/resultado", sr.relativa, "salida: relativa" );
    igual( "csv", sr.extension, "salida: extension" );
    igual( "C:\\Users\\ana\\Escritorio\\PDI\\resultado", sr.original, "salida: original" );
    cierto( Arrays.equals( antes, Files.readAllBytes( original.toPath() ) ), "ana: la entrega no se toca" );
    archivo( new File( p.datos, "ventas.csv" ), "ana: datos del caso" );
    falso( new File( p.datos, "ventas.csv" ).canWrite(), "ana: datos/ de solo lectura" );
    cierto( new File( p.dirCaso, CorrectorSandbox.MARCA ).isFile(), "ana: marca del sandbox" );
    cierto( p.db.isDirectory() && p.tmp.isDirectory() && p.salida.isDirectory(), "ana: carpetas del caso" );
    nulo( p.baseDatos, "ana: el pack no tiene base" );
    // el oculto tiene sus propios datos
    Preparacion o = c.casos.get( "oculto" );
    falso( Arrays.equals( Files.readAllBytes( new File( p.datos, "ventas.csv" ).toPath() ),
      Files.readAllBytes( new File( o.datos, "ventas.csv" ).toPath() ) ), "ana: el oculto tiene otras ventas" );
    // aplicar a un caso
    ResultadoCaso rc = new ResultadoCaso();
    p.aplicar( rc );
    igual( p.redirecciones, rc.redirecciones, "ana: redirections del caso" );
    nulo( rc.corrida.estado, "ana: el caso no queda bloqueado" );
  }

  /** P14: el CSV input se llama ventas.csv; la reescritura no toca su nombre ni los hops. */
  public void pruebaNombresYHopsIntactos() throws Exception {
    File r = CorrectorRecursos.copia();
    Correccion c = corregir( r, CorrectorRecursos.VENTAS, "t1_paso_ventas_csv.ktr", lote( "x" ), false,
      null );
    nulo( c.motivo(), "t1 no bloquea (" + c.detalle() + ")" );
    Preparacion p = c.casos.get( "visible" );
    String xml = texto( p.ktr );
    contiene( xml, "<name>ventas.csv</name>", "t1: el nombre del step" );
    contiene( xml, "<from>ventas.csv</from>", "t1: el hop" );
    contiene( xml, "<filename>" + DIR + "/datos/ventas.csv</filename>", "t1: la ruta si" );
    TransMeta tm = new TransMeta( p.ktr.getAbsolutePath() );
    noNulo( tm.findStep( "ventas.csv" ), "t1: el step sigue" );
    igual( 4, tm.nrTransHops(), "t1: los 4 hops" );
    nulo( CorrectorSandbox.diferencias( c.entrega.tm, tm ), "t1: misma estructura" );
  }

  /**
   * Excepciones del §3 en una transformacion armada: Data grid, descripcion, condicion de un Filter rows y un step
   * llamado ventas.csv no se tocan; la entrada en mayusculas, el default del parametro y dos salidas que chocan si.
   */
  public void pruebaExcepcionesDeLaReescritura() throws Exception {
    File r = CorrectorRecursos.copia();
    File ktr = new File( r, "sintetica.ktr" );
    Files.write( ktr.toPath(), sintetica().getBytes( StandardCharsets.UTF_8 ) );
    // solo se lee (CorrectorRecursos.pack apunta a src/test/resources); sin carpeta: los datos salen de inline
    Pack inline = CorrectorJson.leer( CorrectorRecursos.pack( CorrectorRecursos.INLINE ), Pack.class ).completar();
    Entrega e = CorrectorPolitica.cargar( ktr );
    Revision rev = CorrectorPolitica.revisar( e, inline, false, null );
    falso( rev.bloquea(), "sintetica: la politica la deja (" + rev.detalle + ")" );
    File lote = lote( "inline" );
    Preparacion p = CorrectorSandbox.preparar( inline, null, inline.caso( "visible" ), e, new File( lote,
      "_sandbox/sintetica/visible" ), null );
    nulo( p.motivo, "sintetica: no bloquea (" + p.detalle + ")" );
    String xml = texto( p.ktr );
    contiene( xml, "<item>C:\\x\\ventas.csv</item>", "Data grid: valor de datos" );
    contiene( xml, "<item>ventas.csv</item>", "Data grid: valor igual al archivo" );
    contiene( xml, "<description>C:\\x\\ventas.csv</description>", "descripcion del step" );
    contiene( xml, "<send_true_to>ventas.csv</send_true_to>", "referencia a un step" );
    contiene( xml, "<text>ventas.csv</text>", "constante de una condicion" );
    contiene( xml, "<filename>" + DIR + "/datos/ventas.csv</filename>", "entrada en mayusculas (D:\\clase\\VENTAS.CSV)" );
    contiene( xml, "<default_value>" + DIR + "/datos/ventas.csv</default_value>", "default del parametro" );
    contiene( xml, "<default_value>2024-01-01</default_value>", "default que no es archivo" );
    contiene( xml, "<shared_objects_file/>", "shared_objects_file vacio" );
    contiene( String.join( "\n", p.redirecciones ), "shared objects file: C:\\Users\\profe\\.kettle\\shared.xml -> (ignored)",
      "nota del shared.xml" );
    contiene( String.join( "\n", p.redirecciones ), "parameter 'ARCHIVO': C:\\x\\ventas.csv -> datos/ventas.csv",
      "nota del parametro" );
    igual( 2, p.salidas.size(), "dos salidas" );
    igual( "salida/resultado", p.salidas.get( 0 ).relativa, "la primera se queda con el nombre" );
    igual( "salida/resultado_Escribir B", p.salidas.get( 1 ).relativa, "la segunda con el sufijo del step" );
    igual( "2024-05-31", p.parametros.get( "FECHA_CORTE" ), "parametro del pack que declara" );
    falso( tieneAviso( p.avisos, Aviso.PARAMETRO_NO_DECLARADO ), "FECHA_CORTE esta declarado" );
    archivo( new File( p.datos, "ventas.csv" ), "datos del inline" );
    String datos = new String( Files.readAllBytes( new File( p.datos, "ventas.csv" ).toPath() ), StandardCharsets.UTF_8 );
    igual( inline.inline.get( "datos/visible/ventas.csv" ), datos, "el contenido del inline" );
    // paula no declara FECHA_CORTE
    Entrega paula = cargar( r, CorrectorRecursos.VENTAS, "paula_correcta.ktr" );
    Preparacion pp = CorrectorSandbox.preparar( inline, null, inline.caso( "visible" ), paula, new File( lote,
      "_sandbox/paula/visible" ), null );
    cierto( tieneAviso( pp.avisos, Aviso.PARAMETRO_NO_DECLARADO ), "paula: aviso del parametro" );
    cierto( pp.parametros.isEmpty(), "paula: nada que pasar" );
  }

  /**
   * Lo que Kettle entiende y no se ve en el texto: un comentario como primer hijo (Kettle lee su texto), un tag vacio
   * seguido de otro con valor (Kettle toma el segundo) y tags en mayusculas. La politica y el sandbox miran el XML que
   * Kettle regenera, asi que ven lo que de verdad correria.
   */
  public void pruebaTrucosDelXml() throws Exception {
    File r = CorrectorRecursos.copia();
    Pack ventas = pack( r, CorrectorRecursos.VENTAS );
    File dirPack = new File( new File( r, "packs" ), CorrectorRecursos.VENTAS );
    String paula = texto( new File( new File( new File( r, "entregas" ), CorrectorRecursos.VENTAS ),
      "paula_correcta.ktr" ) );
    String entrada = "<filename>" + DIR + "/datos/ventas.csv</filename>";
    String salida = "<name>" + DIR + "/salida/03_ventas_por_producto</name>";
    cierto( paula.contains( entrada ) && paula.contains( salida ) && paula.contains( "<type>Calculator</type>" ),
      "paula tiene lo que se reemplaza" );

    // un comentario como primer hijo de <type>: Kettle corre un ExecProcess aunque el texto diga Calculator
    Entrega a = CorrectorPolitica.cargar( escribirKtr( r, "a.ktr", paula.replace( "<type>Calculator</type>",
      "<type><!--ExecProcess-->Calculator</type>" ) ) );
    igual( "ExecProcess", a.tm.findStep( "Calcular importe" ).getStepID(), "a: Kettle lee el comentario" );
    igual( ResultadoCaso.STEP_PELIGROSO, CorrectorPolitica.revisar( a, ventas, false, null ).motivo,
      "a: la politica ve el ExecProcess" );

    // un comentario como primer hijo de <filename>: Kettle leeria el esperado del docente
    Entrega b = CorrectorPolitica.cargar( escribirKtr( r, "b.ktr", paula.replace( entrada,
      "<filename><!--C:/Users/profe/esperado/oculto.csv-->" + DIR + "/datos/ventas.csv</filename>" ) ) );
    falso( CorrectorPolitica.revisar( b, ventas, false, null ).bloquea(), "b: la politica no tiene nada que decir" );
    Preparacion pb = CorrectorSandbox.preparar( ventas, dirPack, ventas.caso( "visible" ), b, new File( lote( "trucos" ),
      "b/visible" ), null );
    igual( ResultadoCaso.ENTRADA_INESPERADA, pb.motivo, "b: el sandbox ve lo que Kettle leeria" );
    contiene( pb.detalle, "esperado/oculto.csv", "b: la ruta del comentario" );

    // <filename/> vacio y otro <filename> con valor: Kettle toma el segundo
    Entrega c = CorrectorPolitica.cargar( escribirKtr( r, "c.ktr", paula.replace( entrada,
      "<filename/><filename>C:/Users/profe/notas.csv</filename>" ) ) );
    Preparacion pc = CorrectorSandbox.preparar( ventas, dirPack, ventas.caso( "visible" ), c, new File( lote( "trucos" ),
      "c/visible" ), null );
    igual( ResultadoCaso.ENTRADA_INESPERADA, pc.motivo, "c: el segundo filename" );
    contiene( pc.detalle, "notas.csv", "c: la ruta" );

    // <FILE><NAME> en mayusculas: Kettle los lee igual; la salida se redirige igual
    Entrega d = CorrectorPolitica.cargar( escribirKtr( r, "d.ktr", paula.replace( salida,
      "<NAME>C:/Users/Public/robado</NAME>" ).replace( "<file>", "<FILE>" ).replace( "</file>", "</FILE>" ) ) );
    Preparacion pd = CorrectorSandbox.preparar( ventas, dirPack, ventas.caso( "visible" ), d, new File( lote( "trucos" ),
      "d/visible" ), null );
    nulo( pd.motivo, "d: no bloquea (" + pd.detalle + ")" );
    igual( "salida/robado", pd.salidas.get( 0 ).relativa, "d: la salida en mayusculas tambien se redirige" );
    noContiene( texto( pd.ktr ), "Public", "d: la copia no escribe fuera" );

    // un <STEP> en mayusculas con un ExecProcess: si Kettle lo carga, la politica lo ve
    String oculto = "  <STEP><name>Oculto</name><type>ExecProcess</type><processfield>c</processfield>"
      + "<GUI><xloc>10</xloc><yloc>10</yloc><draw>Y</draw></GUI></STEP>\n</transformation>";
    Entrega x = CorrectorPolitica.cargar( escribirKtr( r, "x.ktr", paula.replace( "</transformation>", oculto ) ) );
    if ( x.tm.findStep( "Oculto" ) != null ) {
      igual( ResultadoCaso.STEP_PELIGROSO, CorrectorPolitica.revisar( x, ventas, false, null ).motivo,
        "x: Kettle cargo el <STEP>" );
    } else {
      noContiene( x.tm.getXML(), "ExecProcess", "x: si Kettle no lo carga, no esta en lo que correria" );
      falso( CorrectorPolitica.revisar( x, ventas, false, null ).bloquea(), "x: y no hay nada que bloquear" );
    }
  }

  private static File escribirKtr( File dir, String nombre, String xml ) throws IOException {
    File f = new File( dir, nombre );
    Files.write( f.toPath(), xml.getBytes( StandardCharsets.UTF_8 ) );
    return f;
  }

  /** P15: la conexion MySQL que nadie usa se saca con un aviso; kevin, que la usa, bloquea. */
  public void pruebaConexiones() throws Exception {
    File r = CorrectorRecursos.copia();
    Correccion t2 = corregir( r, CorrectorRecursos.VENTAS, "t2_conexion_suelta.ktr", lote( "x" ), false,
      null );
    nulo( t2.motivo(), "t2 no bloquea" );
    Preparacion p = t2.casos.get( "visible" );
    cierto( tieneAviso( p.avisos, Aviso.CONEXION_QUITADA ), "t2: aviso" );
    contiene( p.avisos.get( 0 ).detalle, "mysql_de_clase", "t2: nombra la conexion" );
    noContiene( texto( p.ktr ), "<connection>", "t2: la copia no tiene la conexion" );
    ResultadoCorreccion res = new ResultadoCorreccion();
    for ( Preparacion x : t2.casos.values() ) {
      x.aplicarAvisos( res );
    }
    igual( 1, res.avisos.size(), "t2: el aviso una sola vez en la entrega" );

    Correccion kevin = corregir( r, CorrectorRecursos.TABLA, "kevin_otra_base.ktr", lote( "x" ), false,
      null );
    igual( ResultadoCaso.CONEXION_AJENA, kevin.motivo(), "kevin: conexion ajena" );
    Preparacion pk = kevin.casos.get( "visible" );
    igual( "Escribir totales", pk.step, "kevin: el step que la usa" );
    contiene( pk.detalle, "MYSQL", "kevin: el tipo" );
    ResultadoCaso rc = new ResultadoCaso();
    pk.aplicar( rc );
    igual( ResultadoCaso.BLOQUEADO, rc.corrida.estado, "kevin: caso bloqueado" );
    igual( ResultadoCaso.CONEXION_AJENA, rc.corrida.motivo, "kevin: reason del caso" );

    Correccion ines = corregir( r, CorrectorRecursos.TABLA, "ines_tabla_ok.ktr", lote( "x" ), false, null );
    nulo( ines.motivo(), "ines no bloquea (" + ines.detalle() + ")" );
    Preparacion pi = ines.casos.get( "visible" );
    archivo( pi.baseDatos, "ines: copia de la plantilla" );
    igual( new File( pi.db, "dw.sqlite" ), pi.baseDatos, "ines: db/dw.sqlite" );
    String xml = texto( pi.ktr );
    contiene( xml, "<database>" + pi.baseDatos.getAbsolutePath() + "</database>", "ines: la conexion al sandbox" );
    noContiene( xml, "D:\\facu", "ines: sin la ruta de su PC" );
    noContiene( xml, "USE_POOLING", "ines: sin pool" );
    contiene( xml, "FORCE_IDENTIFIERS_TO_LOWERCASE", "ines: atributos inofensivos" );
    contiene( String.join( "\n", pi.redirecciones ), "connection 'dw' (SQLITE): D:\\facu\\dw.sqlite -> db/dw.sqlite",
      "ines: nota de la conexion" );
  }

  /** Lecturas fuera del sandbox: el sandbox de otro alumno del lote (walter), una URL (xime). */
  public void pruebaLecturasFuera() throws Exception {
    File r = CorrectorRecursos.copia();
    File lote = lote( "x" );
    nulo( corregir( r, CorrectorRecursos.VENTAS, "paula_correcta.ktr", lote, false, null ).motivo(), "paula" );
    Correccion w = corregir( r, CorrectorRecursos.VENTAS, "walter_copia_companero.ktr", lote, false, null );
    igual( ResultadoCaso.ENTRADA_INESPERADA, w.motivo(), "walter: lee el sandbox de paula" );
    Preparacion pw = w.casos.get( "visible" );
    contiene( pw.detalle, "paula_correcta", "walter: la ruta" );
    contiene( pw.detalle, "outside the sandbox", "walter: por que" );
    igual( "Leer resultado de paula", pw.step, "walter: el step" );

    long t0 = System.currentTimeMillis();
    Correccion x = corregir( r, CorrectorRecursos.VENTAS, "xime_lee_url.ktr", lote, false, null );
    igual( ResultadoCaso.ENTRADA_INESPERADA, x.motivo(), "xime: http" );
    contiene( x.detalle(), "(http:)", "xime: el esquema" );
    cierto( System.currentTimeMillis() - t0 < 10000, "xime: sin intentar la conexion" );

    // rutaFuera
    File caso = new File( lote, "_sandbox/ana/visible" );
    caso.mkdirs();
    File tmp = new File( caso, "tmp" );
    tmp.mkdirs();
    Variables v = new Variables();
    v.initializeVariablesFrom( null );
    v.setVariable( "Internal.Entry.Current.Directory", caso.getAbsolutePath().replace( '\\', '/' ) );
    v.setVariable( "java.io.tmpdir", tmp.getAbsolutePath() );
    List<Path> raices = Collections.singletonList( CorrectorSandbox.canonica( caso ) );
    nulo( CorrectorSandbox.rutaFuera( DIR + "/datos/ventas.csv", v, raices ), "dentro" );
    contiene( CorrectorSandbox.rutaFuera( DIR + "/../../paula_correcta/visible/salida/x.csv", v, raices ),
      "outside the sandbox", "../../<otro>/" );
    contiene( CorrectorSandbox.rutaFuera( DIR + "/../visible2/x.csv", v, raices ), "outside", "prefijo comun" );
    nulo( CorrectorSandbox.rutaFuera( caso.toURI().toString() + "datos/x.csv", v, raices ), "URL file: con %20" );
    nulo( CorrectorSandbox.rutaFuera( "file:///" + caso.getAbsolutePath().replace( '\\', '/' ) + "/datos/a b.csv", v,
      raices ), "URL file: sin codificar" );
    nulo( CorrectorSandbox.rutaFuera( caso.getAbsolutePath().toUpperCase( Locale.ROOT ) + "\\DATOS\\X.CSV", v,
      raices ), "sin mayusculas en Windows" );
    nulo( CorrectorSandbox.rutaFuera( "%%java.io.tmpdir%%/sort.tmp", v, raices ), "el tmp de la corrida" );
    contiene( CorrectorSandbox.rutaFuera( "zip:file:///" + caso.getAbsolutePath().replace( '\\', '/' )
      + "/datos/x.zip!/a.csv", v, raices ), "(zip:)", "zip" );
    contiene( CorrectorSandbox.rutaFuera( "http://192.0.2.10/x.csv", v, raices ), "(http:)", "http" );
    long t1 = System.currentTimeMillis();
    contiene( CorrectorSandbox.rutaFuera( "\\\\192.0.2.10\\recurso\\x.csv", v, raices ), "network path", "UNC" );
    contiene( CorrectorSandbox.rutaFuera( "file://192.0.2.10/recurso/x.csv", v, raices ), "network path",
      "UNC como URL" );
    contiene( CorrectorSandbox.rutaFuera( "//192.0.2.10/recurso/x.csv", v, raices ), "network path", "UNC con /" );
    cierto( System.currentTimeMillis() - t1 < 2000, "UNC: sin tocar la red (SMB)" );
    contiene( CorrectorSandbox.rutaFuera( "${NO_EXISTE}/x.csv", v, raices ), "cannot be resolved", "variable" );
    contiene( CorrectorSandbox.rutaFuera( "x.csv", v, raices ), "outside", "relativa: contra el cwd de la JVM" );
    contiene( CorrectorSandbox.rutaFuera( "${user.home}/x.csv", v, raices ), "outside", "user.home" );
    nulo( CorrectorSandbox.esquema( "C:/x" ), "una unidad no es esquema" );
    igual( "file", CorrectorSandbox.esquema( "FILE:///x" ), "file" );
  }

  /** Salidas: vero apunta al esperado del pack, mati a una URL de macOS; ulises no se puede redirigir. */
  public void pruebaSalidas() throws Exception {
    File r = CorrectorRecursos.copia();
    File lote = lote( "x" );
    Correccion vero = corregir( r, CorrectorRecursos.VENTAS, "vero_pisa_esperado.ktr", lote, false, null );
    nulo( vero.motivo(), "vero no bloquea" );
    igual( "salida/visible", vero.casos.get( "visible" ).salidas.get( 0 ).relativa, "vero: salida/visible" );
    Correccion mati = corregir( r, CorrectorRecursos.VENTAS, "mati_rutas_mac.ktr", lote, false, null );
    nulo( mati.motivo(), "mati no bloquea" );
    Preparacion pm = mati.casos.get( "visible" );
    igual( "salida/resultado", pm.salidas.get( 0 ).relativa, "mati: la URL file:///Users/..." );
    contiene( texto( pm.ktr ), "<filename>" + DIR + "/datos/ventas.csv</filename>", "mati: la entrada de macOS" );

    // ulises: aunque se llame a preparar sin revisar, el sandbox no la deja
    Pack ventas = pack( r, CorrectorRecursos.VENTAS );
    Entrega ulises = cargar( r, CorrectorRecursos.VENTAS, "ulises_salida_en_campo.ktr" );
    Preparacion pu = CorrectorSandbox.preparar( ventas, new File( new File( r, "packs" ), CorrectorRecursos.VENTAS ),
      ventas.caso( "visible" ), ulises, new File( lote, "_sandbox/ulises/visible" ), null );
    igual( ResultadoCaso.SALIDA_INESPERADA, pu.motivo, "ulises: unexpected_output en el sandbox" );

    igual( "resultado", CorrectorSandbox.nombreBase( "C:\\Users\\ana\\resultado" ), "base windows" );
    igual( "visible", CorrectorSandbox.nombreBase( DIR + "/../../../esperado/visible" ), "base con .." );
    igual( "res final", CorrectorSandbox.nombreBase( "file:///C:/a%20b/res%20final" ), "base de una URL" );
    igual( "x", CorrectorSandbox.nombreBase( "/tmp/x/" ), "barra final" );
    igual( "a_b_c", CorrectorSandbox.limpio( "a:b*c" ), "caracteres de Windows" );
    igual( "", CorrectorSandbox.limpio( ".." ), "puntos" );
  }

  /** La carpeta del caso: solo se vacia si es un sandbox; borrar quita el solo-lectura de datos/. */
  public void pruebaCarpetaDelCaso() throws Exception {
    final File r = CorrectorRecursos.copia();
    final Pack ventas = pack( r, CorrectorRecursos.VENTAS );
    final Entrega paula = cargar( r, CorrectorRecursos.VENTAS, "paula_correcta.ktr" );
    final File dirPack = new File( new File( r, "packs" ), CorrectorRecursos.VENTAS );
    final File ajena = new File( lote( "carpeta" ), "no_es_sandbox" );
    ajena.mkdirs();
    Files.write( new File( ajena, "importante.txt" ).toPath(), "no borrar".getBytes( StandardCharsets.UTF_8 ) );
    IOException ex = lanza( IOException.class, new Verificador.Accion() {
      @Override
      public void correr() throws Exception {
        CorrectorSandbox.preparar( ventas, dirPack, ventas.caso( "visible" ), paula, ajena, null );
      }
    }, "una carpeta con otras cosas" );
    contiene( ex.getMessage(), CorrectorSandbox.MARCA, "dice por que" );
    archivo( new File( ajena, "importante.txt" ), "no se toco" );

    File caso = new File( lote( "carpeta" ), "_sandbox/paula/visible" );
    Preparacion p1 = CorrectorSandbox.preparar( ventas, dirPack, ventas.caso( "visible" ), paula, caso, null );
    Files.write( new File( p1.salida, "vieja.csv" ).toPath(), new byte[] { 1 } );
    Preparacion p2 = CorrectorSandbox.preparar( ventas, dirPack, ventas.caso( "visible" ), paula, caso, null );
    falso( new File( p2.salida, "vieja.csv" ).exists(), "se rehace desde cero" );
    CorrectorSandbox.borrar( caso );
    falso( caso.exists(), "borrar: con datos/ de solo lectura" );
  }

  // ------------------------------------------------------------------ corridas

  /**
   * Las que pasan corren en el sandbox (en esta JVM) y escriben solo ahi: paula, ana, t1 (P14), t2 (P15), t7 (P20),
   * mati y vero (el esperado del pack no cambia); ines y mora escriben en la copia de la plantilla, que queda igual.
   */
  public void pruebaCorridasEnElSandbox() throws Exception {
    File r = CorrectorRecursos.copia();
    File lote = lote( "x" );
    File esperado = new File( new File( new File( r, "packs" ), CorrectorRecursos.VENTAS ), "esperado/visible.csv" );
    byte[] esperadoAntes = Files.readAllBytes( esperado.toPath() );
    for ( String a : Arrays.asList( "paula_correcta.ktr", "ana_orden_distinto.ktr", "t1_paso_ventas_csv.ktr",
      "t2_conexion_suelta.ktr", "t7_paso_suelto.ktr", "mati_rutas_mac.ktr", "vero_pisa_esperado.ktr" ) ) {
      Correccion c = corregir( r, CorrectorRecursos.VENTAS, a, lote, false, null );
      nulo( c.motivo(), a + ": no bloquea" );
      for ( Map.Entry<String, Preparacion> e : c.casos.entrySet() ) {
        Preparacion p = e.getValue();
        Trans t = correr( p );
        igual( 0, t.getErrors(), a + "/" + e.getKey() + ": sin errores" );
        SalidaRedirigida sr = p.salidas.get( 0 );
        File out = new File( p.dirCaso, sr.relativa + "." + sr.extension );
        archivo( out, a + "/" + e.getKey() + ": la salida en el sandbox" );
        igual( 5, lineas( out ), a + "/" + e.getKey() + ": encabezado y 4 productos" );
        CorrectorSandbox.borrar( p.dirCaso );
        falso( p.dirCaso.exists(), a + ": el sandbox se puede borrar despues de correr (P12)" );
      }
    }
    cierto( Arrays.equals( esperadoAntes, Files.readAllBytes( esperado.toPath() ) ), "vero: el esperado no cambia" );

    File plantilla = new File( new File( new File( r, "packs" ), CorrectorRecursos.TABLA ), "plantilla/dw.sqlite" );
    byte[] plantillaAntes = Files.readAllBytes( plantilla.toPath() );
    for ( String a : Arrays.asList( "ines_tabla_ok.ktr", "mora_borra_antes.ktr" ) ) {
      Correccion c = corregir( r, CorrectorRecursos.TABLA, a, lote, false, null );
      nulo( c.motivo(), a + ": no bloquea" );
      Preparacion p = c.casos.get( "visible" );
      Trans t = correr( p );
      igual( 0, t.getErrors(), a + ": sin errores" );
      igual( 4, filas( p.baseDatos, "SELECT COUNT(*) FROM totales" ), a + ": 4 filas en db/dw.sqlite" );
      CorrectorSandbox.borrar( p.dirCaso );
      falso( p.dirCaso.exists(), a + ": el sandbox se puede borrar (la base quedo cerrada)" );
    }
    cierto( Arrays.equals( plantillaAntes, Files.readAllBytes( plantilla.toPath() ) ), "P5: la plantilla no cambia" );
  }

  // ------------------------------------------------------------------ ayudas

  /** Lo que haria el worker hasta antes de correr: cargar, revisar y, si pasa, preparar cada caso. */
  private static final class Correccion {

    Throwable errorCarga;
    Entrega entrega;
    Revision revision;
    final Map<String, Preparacion> casos = new LinkedHashMap<String, Preparacion>();

    String motivo() {
      if ( revision != null && revision.bloquea() ) {
        return revision.motivo;
      }
      for ( Preparacion p : casos.values() ) {
        if ( p.bloqueada() ) {
          return p.motivo;
        }
      }
      return null;
    }

    String detalle() {
      if ( revision != null && revision.bloquea() ) {
        return revision.detalle;
      }
      for ( Preparacion p : casos.values() ) {
        if ( p.bloqueada() ) {
          return p.detalle;
        }
      }
      return null;
    }

    boolean tieneAviso( String codigo ) {
      if ( revision != null && PruebaCorrectorSandbox.tieneAviso( revision.avisos, codigo ) ) {
        return true;
      }
      for ( Preparacion p : casos.values() ) {
        if ( PruebaCorrectorSandbox.tieneAviso( p.avisos, codigo ) ) {
          return true;
        }
      }
      return false;
    }
  }

  private static Correccion corregir( File raiz, String packId, String archivo, File lote, boolean confianza,
    List<String> extra ) throws Exception {
    Pack pack = pack( raiz, packId );
    Correccion c = new Correccion();
    try {
      c.entrega = cargar( raiz, packId, archivo );
    } catch ( Exception ex ) {
      c.errorCarga = ex;
      return c;
    }
    c.revision = CorrectorPolitica.revisar( c.entrega, pack, confianza, extra );
    if ( c.revision.bloquea() ) {
      return c;
    }
    String alumno = archivo.replaceAll( "\\.ktr$", "" );
    File dirPack = new File( new File( raiz, "packs" ), packId );
    for ( Caso caso : pack.casos ) {
      c.casos.put( caso.id, CorrectorSandbox.preparar( pack, dirPack, caso, c.entrega, new File( new File( new File(
        lote, "_sandbox" ), alumno ), caso.id ), null ) );
    }
    return c;
  }

  private static Pack pack( File raiz, String packId ) throws IOException {
    return CorrectorJson.leer( new File( new File( new File( raiz, "packs" ), packId ), "pack.json" ), Pack.class )
      .completar();
  }

  private static Entrega cargar( File raiz, String packId, String archivo ) throws Exception {
    return CorrectorPolitica.cargar( new File( new File( new File( raiz, "entregas" ), packId ), archivo ) );
  }

  /** Corre la copia reescrita como lo hara el worker (sin sus topes): solo entregas que pasaron la politica. */
  private static Trans correr( Preparacion p ) throws Exception {
    TransMeta tm = new TransMeta( p.ktr.getAbsolutePath() );
    for ( Map.Entry<String, String> e : p.parametros.entrySet() ) {
      tm.setParameterValue( e.getKey(), e.getValue() );
    }
    Trans t = new Trans( tm );
    t.setLogLevel( LogLevel.MINIMAL );
    tm.setInternalKettleVariables( t );
    t.copyParametersFrom( tm );
    t.activateParameters();
    t.prepareExecution( null );
    t.startThreads();
    long fin = System.currentTimeMillis() + 60000;
    while ( !t.isFinished() && System.currentTimeMillis() < fin ) {
      Thread.sleep( 50 );
    }
    if ( !t.isFinished() ) {
      t.stopAll();
      t.killAll();
      falla( p.ktr.getName() + ": no termino en 60 s" );
    }
    t.waitUntilFinished();
    return t;
  }

  private static int filas( File base, String sql ) throws Exception {
    Class.forName( "org.sqlite.JDBC" );
    try ( Connection cn = DriverManager.getConnection( "jdbc:sqlite:" + base.getAbsolutePath() );
          Statement st = cn.createStatement(); ResultSet rs = st.executeQuery( sql ) ) {
      return rs.next() ? rs.getInt( 1 ) : -1;
    }
  }

  private static int lineas( File f ) throws IOException {
    int n = 0;
    for ( String l : new String( Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 ).split( "\n" ) ) {
      if ( !l.trim().isEmpty() ) {
        n++;
      }
    }
    return n;
  }

  private static String texto( File f ) throws IOException {
    return new String( Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 );
  }

  private static void archivo( File f, String msg ) {
    Verificador.archivo( f, 1, msg );
  }

  static boolean tieneAviso( List<Aviso> avisos, String codigo ) {
    for ( Aviso a : avisos ) {
      if ( codigo.equals( a.codigo ) ) {
        return true;
      }
    }
    return false;
  }

  private static List<String> union( java.util.Set<String> a, java.util.Set<String> b ) {
    List<String> l = new ArrayList<String>( a );
    l.addAll( b );
    Collections.sort( l );
    return l;
  }

  // ------------------------------------------------------------------ la transformacion armada

  private static String sintetica() {
    StringBuilder x = new StringBuilder();
    x.append( "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<transformation>\n  <info>\n    <name>sintetica</name>\n" )
      .append( "    <parameters>\n" )
      .append( "      <parameter><name>ARCHIVO</name><default_value>C:\\x\\ventas.csv</default_value>" )
      .append( "<description>C:\\x\\ventas.csv</description></parameter>\n" )
      .append( "      <parameter><name>FECHA_CORTE</name><default_value>2024-01-01</default_value>" )
      .append( "<description/></parameter>\n    </parameters>\n" )
      .append( "    <shared_objects_file>C:\\Users\\profe\\.kettle\\shared.xml</shared_objects_file>\n  </info>\n" )
      .append( "  <order>\n" ).append( hop( "Datos", "Filtro" ) ).append( hop( "Filtro", "ventas.csv" ) )
      .append( hop( "Filtro", "Otro" ) ).append( "  </order>\n" );
    x.append( step( "Datos", "DataGrid", "C:\\x\\ventas.csv",
      "<fields><field><name>archivo</name><type>String</type><format/><currency/><decimal/><group/>"
        + "<length>-1</length><precision>-1</precision><set_empty_string>N</set_empty_string></field></fields>"
        + "<data><line><item>C:\\x\\ventas.csv</item></line><line><item>ventas.csv</item></line></data>", 100 ) );
    x.append( step( "Filtro", "FilterRows", "", "<send_true_to>ventas.csv</send_true_to>"
      + "<send_false_to>Otro</send_false_to><compare><condition><negated>N</negated><leftvalue>archivo</leftvalue>"
      + "<function>=</function><rightvalue/><value><name>constant</name><type>String</type><text>ventas.csv</text>"
      + "<length>-1</length><precision>-1</precision><isnull>N</isnull><mask/></value></condition></compare>", 200 ) );
    x.append( step( "ventas.csv", "Dummy", "", "", 300 ) );
    x.append( step( "Otro", "Dummy", "", "", 400 ) );
    x.append( step( "Leer", "CsvInput", "", "<filename>D:\\clase\\VENTAS.CSV</filename><filename_field/>"
      + "<rownum_field/><include_filename>N</include_filename><separator>;</separator><enclosure>\"</enclosure>"
      + "<header>Y</header><buffer_size>50000</buffer_size><lazy_conversion>N</lazy_conversion>"
      + "<add_filename_result>N</add_filename_result><parallel>N</parallel><newline_possible>N</newline_possible>"
      + "<encoding>UTF-8</encoding><fields></fields>", 500 ) );
    x.append( step( "Escribir A", "TextFileOutput", "", salida( "C:\\x\\resultado" ), 600 ) );
    x.append( step( "Escribir B", "TextFileOutput", "", salida( "/otra/carpeta/resultado" ), 700 ) );
    x.append( "</transformation>\n" );
    return x.toString();
  }

  private static String hop( String de, String a ) {
    return "    <hop><from>" + de + "</from><to>" + a + "</to><enabled>Y</enabled></hop>\n";
  }

  private static String step( String nombre, String tipo, String descripcion, String cuerpo, int x ) {
    return "  <step>\n    <name>" + nombre + "</name>\n    <type>" + tipo + "</type>\n    "
      + ( descripcion.isEmpty() ? "<description/>" : "<description>" + descripcion + "</description>" )
      + "\n    <distribute>Y</distribute><custom_distribution/><copies>1</copies>"
      + "<partitioning><method>none</method><schema_name/></partitioning>\n    " + cuerpo
      + "\n    <attributes/><cluster_schema/><remotesteps><input></input><output></output></remotesteps>"
      + "<GUI><xloc>" + x + "</xloc><yloc>100</yloc><draw>Y</draw></GUI>\n  </step>\n";
  }

  private static String salida( String nombre ) {
    return "<separator>;</separator><enclosure>\"</enclosure><enclosure_forced>N</enclosure_forced>"
      + "<enclosure_fix_disabled>N</enclosure_fix_disabled><header>Y</header><footer>N</footer><format>UNIX</format>"
      + "<compression>None</compression><encoding>UTF-8</encoding><endedLine/><fileNameInField>N</fileNameInField>"
      + "<fileNameField/><create_parent_folder>Y</create_parent_folder><file><name>" + nombre + "</name>"
      + "<servlet_output>N</servlet_output><do_not_open_new_file_init>Y</do_not_open_new_file_init>"
      + "<extention>csv</extention><append>N</append><split>N</split><haspartno>N</haspartno><add_date>N</add_date>"
      + "<add_time>N</add_time><SpecifyFormat>N</SpecifyFormat><date_time_format/>"
      + "<add_to_result_filenames>Y</add_to_result_filenames><pad>N</pad><fast_dump>N</fast_dump>"
      + "<splitevery>0</splitevery></file><fields></fields>";
  }
}
