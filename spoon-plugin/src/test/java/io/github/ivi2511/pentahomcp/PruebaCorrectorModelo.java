package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falla;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.corrector.modelo.Caso;
import io.github.ivi2511.pentahomcp.corrector.modelo.Comparacion;
import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;
import io.github.ivi2511.pentahomcp.corrector.modelo.ItemRubrica;
import io.github.ivi2511.pentahomcp.corrector.modelo.OpcionesComparar;
import io.github.ivi2511.pentahomcp.corrector.modelo.Pack;
import io.github.ivi2511.pentahomcp.corrector.modelo.PoliticaPack;
import io.github.ivi2511.pentahomcp.corrector.modelo.Regla;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCaso;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion;

/**
 * Contrato del corrector (C0, docs/corrector-diseno.md §2 y §6): ida y vuelta JSON de los packs y de los
 * resultados sin perder claves, valores por defecto, estados del §6, copia para alumnos, devolucion sin los datos
 * de los ocultos, escritura atomica y coherencia de las fixtures (casos.json contra las carpetas). Solo
 * {@link #pruebaEntregasCargan()} necesita Kettle.
 *
 * @dueño C0
 */
public class PruebaCorrectorModelo {

  // ------------------------------------------------------------------ packs

  public void pruebaPacksIdaYVuelta() throws Exception {
    for ( String id : Arrays.asList( CorrectorRecursos.VENTAS, CorrectorRecursos.TABLA, CorrectorRecursos.INLINE ) ) {
      File f = archivoPack( id );
      JsonNode original = CorrectorJson.mapper().readTree( f );
      Pack p = CorrectorJson.leer( f, Pack.class );
      String j1 = CorrectorJson.escribir( p, true );
      String j2 = CorrectorJson.escribir( CorrectorJson.leer( j1, Pack.class ), true );
      igual( j1, j2, id + ": el JSON tiene que ser identico despues de leerlo y volver a escribirlo" );
      incluido( original, CorrectorJson.mapper().readTree( j1 ), id );
      String compacto = CorrectorJson.escribir( p, false );
      noContiene( compacto, ":null", id + ": los null no salen" );
      noContiene( compacto, "[]", id + ": las listas vacias no salen" );
      noContiene( compacto, "{}", id + ": los mapas vacios no salen" );
    }
  }

  public void pruebaPackVentas() throws Exception {
    Pack p = CorrectorJson.leer( archivoPack( CorrectorRecursos.VENTAS ), Pack.class );
    igual( Pack.FORMATO, p.formato, "formato" );
    igual( "ventas-por-producto", p.id, "id" );
    igual( "es", p.idioma, "idioma" );
    falso( p.esJob(), "es una transformacion" );
    igual( "solucion/03_ventas_por_producto.ktr", p.solucion, "solucion" );
    igual( Collections.singletonList( "ventas.csv" ), p.entradas, "entradas" );
    igual( 2, p.casos.size(), "casos" );
    Caso oculto = p.caso( "oculto" );
    noNulo( oculto, "caso oculto" );
    cierto( oculto.oculto, "el caso oculto es hidden" );
    igual( "datos/oculto", oculto.dirEntradas, "inputs_dir" );
    igual( "esperado/oculto.csv", oculto.esperado, "expected" );
    igual( 1.0, oculto.peso, "weight" );
    igual( 1, p.casosVisibles().size(), "un caso visible" );

    igual( Pack.Salida.CAPTURA_ARCHIVO, p.salida.captura, "capture" );
    igual( "Escribir totales", p.salida.step, "output.step" );
    igual( Arrays.asList( "producto", "unidades", "importe_total", "num_ventas", "importe_medio", "ultima_venta" ),
      p.salida.nombresColumnas(), "output.columns" );
    cierto( p.salida.columna( " IMPORTE_TOTAL " ).esNumerica(), "importe_total es numerica (sin mayusculas)" );
    cierto( p.salida.columna( "unidades" ).esNumerica(), "Integer es numerica" );
    falso( p.salida.columna( "producto" ).esNumerica(), "String no es numerica" );
    cierto( p.salida.columna( "ultima_venta" ).esFecha(), "Date es fecha" );
    igual( "yyyy-MM-dd", p.salida.columna( "ultima_venta" ).formato, "formato de la fecha" );
    nulo( p.salida.columna( "no_existe" ), "columna que no existe" );
    igual( ";", p.salida.csvEsperado.separador, "expected_csv.separator" );
    igual( "\"", p.salida.csvEsperado.encierro, "expected_csv.enclosure" );
    cierto( p.salida.csvEsperado.encabezado, "expected_csv.header" );

    OpcionesComparar c = p.comparar;
    cierto( c.conClave(), "con clave" );
    igual( Collections.singletonList( "producto" ), c.columnasClave, "key_columns" );
    igual( 0.01, c.toleranciaNumerica, "numeric_tolerance" );
    igual( Arrays.asList( "yyyy-MM-dd", "dd/MM/yyyy" ), c.formatosFecha, "date_formats" );
    falso( c.formatoEstricto, "strict_format" );
    igual( OpcionesComparar.EXTRA_AVISAR, c.columnasDeMas, "extra_columns" );

    igual( 3, p.reglas.size(), "reglas" );
    igual( Regla.DEBE_USAR, p.regla( "usa_group_by" ).tipo, "must_use" );
    igual( Arrays.asList( "GroupBy", "MemoryGroupBy" ), p.regla( "usa_group_by" ).steps, "steps de must_use" );
    igual( Regla.NO_DEBE_USAR, p.regla( "sin_codigo" ).tipo, "must_not_use" );
    igual( Integer.valueOf( 8 ), p.regla( "max_pasos" ).valor, "max_steps.value" );
    for ( Regla r : p.reglas ) {
      cierto( Regla.tipoValido( r.tipo ), "tipo de regla valido: " + r.tipo );
    }

    igual( 7, p.rubrica.size(), "renglones de la rubrica" );
    igual( 10.0, p.puntosTotales(), "la rubrica suma 10" );
    igual( 10.0, p.escala, "scale" );
    ItemRubrica g = p.itemRubrica( "rule:usa_group_by" );
    igual( Collections.singletonList( ItemRubrica.RUNS ), g.requiere, "compuerta de usa_group_by" );
    igual( "usa_group_by", g.idRegla(), "id de la regla" );
    for ( ItemRubrica i : p.rubrica ) {
      cierto( ItemRubrica.checkValido( i.check ), "check valido: " + i.check );
      if ( i.esRegla() ) {
        noNulo( p.regla( i.idRegla() ), "la regla de " + i.check + " existe" );
      }
    }

    PoliticaPack pol = p.politica;
    igual( new Locale( "es", "ES" ), pol.localeJava(), "locale" );
    igual( "Europe/Madrid", pol.zonaHoraria, "timezone" );
    igual( "UTF-8", pol.encoding, "encoding" );
    igual( 15, pol.timeoutSeg, "timeout_s" );
    igual( 100000L, pol.maxFilas, "max_rows" );
    igual( 5, pol.maxSalidaMb, "max_output_mb" );
    cierto( pol.bloqueaDesconocidos(), "unknown_steps block" );
    falso( pol.permite( "ScriptValueMod" ), "sin allow_steps" );

    Pack t = CorrectorJson.leer( archivoPack( CorrectorRecursos.TABLA ), Pack.class );
    igual( Pack.Salida.CAPTURA_QUERY, t.salida.captura, "ventas-a-tabla: capture query" );
    igual( "plantilla/dw.sqlite", t.salida.base.plantilla, "database.template" );
    igual( Pack.BaseDatos.SQLITE, t.salida.base.tipo, "database.type" );
    contiene( t.salida.sql, "'unixepoch')", "el SQL convierte la fecha sin localtime" );
    noContiene( t.salida.sql, "localtime", "el SQL no depende de la zona del sistema" );
    cierto( t.politica.permite( "tableoutput" ), "allow_steps sin mayusculas" );
    cierto( t.politica.permite( "ExecSQL" ), "ExecSQL permitido" );
    igual( "UTC", t.politica.zonaHoraria, "zona UTC" );
    igual( 1, t.casos.size(), "un caso" );
    igual( 10.0, t.puntosTotales(), "rubrica de ventas-a-tabla" );

    Pack in = CorrectorJson.leer( archivoPack( CorrectorRecursos.INLINE ), Pack.class );
    igual( Pack.Salida.CAPTURA_STEP, in.salida.captura, "inline: capture step" );
    igual( "2024-05-31", in.parametros.get( "FECHA_CORTE" ), "parameters" );
    igual( "03_ventas_por_producto.ktr", in.archivoEntrega, "submission_file" );
    nulo( in.solucion, "la copia para alumnos no tiene solution" );
    igual( new TreeSet<>( Arrays.asList( "enunciado.md", "datos/visible/ventas.csv", "esperado/visible.csv" ) ),
      new TreeSet<>( in.inline.keySet() ), "archivos inline" );
    contiene( in.inline.get( "datos/visible/ventas.csv" ), "101;1;Café;3;2.50;2024-05-01", "contenido inline" );
    igual( "unsorted_input", in.regla( "orden" ).reglaLint, "regla lint" );
    igual( Regla.MANEJO_ERRORES, in.regla( "errores" ).tipo, "regla error_handling" );
    igual( "absolute_path", in.itemRubrica( "lint:absolute_path" ).idLint(), "check lint:" );
    cierto( in.itemRubrica( "lint:absolute_path" ).esLint(), "esLint" );
    falso( in.comparar.ignorarOrdenFilas, "ignore_row_order false" );
    cierto( in.politica.permite( "SetVariable" ), "allow_steps del inline" );
  }

  public void pruebaPackDefectos() throws Exception {
    Pack p = CorrectorJson.leer( "{\"id\":\"x\",\"cases\":[{\"id\":\"a\"}]}", Pack.class );
    igual( Pack.FORMATO, p.formato, "format por defecto" );
    igual( Pack.TRANSFORMACION, p.tipo, "kind por defecto" );
    igual( 10.0, p.escala, "scale por defecto" );
    igual( 1.0, p.casos.get( 0 ).peso, "weight por defecto" );
    falso( p.casos.get( 0 ).oculto, "hidden por defecto" );
    igual( Pack.Salida.CAPTURA_ARCHIVO, p.salida.captura, "capture por defecto" );
    igual( ";", p.salida.csvEsperado.separador, "separator por defecto" );
    cierto( p.comparar.ignorarOrdenColumnas && p.comparar.ignorarOrdenFilas, "ignorar ordenes por defecto" );
    cierto( p.comparar.recortar && p.comparar.distinguirMayusculas, "trim y case_sensitive por defecto" );
    igual( 5, p.comparar.maxEjemplos, "max_examples por defecto" );
    igual( 0.01, p.comparar.toleranciaNumerica, "numeric_tolerance por defecto" );
    igual( PoliticaPack.BLOQUEAR, p.politica.stepsDesconocidos, "unknown_steps por defecto" );
    igual( 60, p.politica.timeoutSeg, "timeout_s por defecto" );
    igual( 50, p.politica.maxSalidaMb, "max_output_mb por defecto" );
    igual( Locale.US, p.politica.localeJava(), "locale por defecto" );
    igual( "UTC", p.politica.zonaHoraria, "timezone por defecto" );
    igual( "UTF-8", p.politica.encoding, "encoding por defecto" );
    nulo( p.inline, "sin inline" );

    // nulls explicitos: completar() pone los defectos
    Pack n = CorrectorJson.leer( "{\"id\":\"n\",\"inputs\":null,\"cases\":null,\"output\":null,\"compare\":null,"
      + "\"rules\":[{\"id\":\"r\",\"type\":\"must_use\",\"steps\":null}],\"rubric\":[{\"check\":\"runs\","
      + "\"points\":1,\"requires\":null}],\"policy\":{\"allow_steps\":null,\"unknown_steps\":null},\"kind\":null}",
      Pack.class );
    nulo( n.salida, "output null queda null hasta completar" );
    cierto( n == n.completar(), "completar devuelve this" );
    noNulo( n.entradas, "inputs" );
    noNulo( n.casos, "cases" );
    igual( Pack.Salida.CAPTURA_ARCHIVO, n.salida.captura, "output completado" );
    noNulo( n.salida.csvEsperado, "expected_csv completado" );
    noNulo( n.comparar.columnasClave, "compare completado" );
    noNulo( n.reglas.get( 0 ).steps, "steps de la regla" );
    noNulo( n.rubrica.get( 0 ).requiere, "requires" );
    noNulo( n.politica.permitirSteps, "allow_steps" );
    igual( PoliticaPack.BLOQUEAR, n.politica.stepsDesconocidos, "unknown_steps completado" );
    igual( Pack.TRANSFORMACION, n.tipo, "kind completado" );

    PoliticaPack pol = new PoliticaPack();
    pol.locale = "pt-BR";
    igual( new Locale( "pt", "BR" ), pol.localeJava(), "locale con guion" );
    pol.locale = "es";
    igual( new Locale( "es" ), pol.localeJava(), "locale sin pais" );
    pol.locale = " ";
    igual( Locale.US, pol.localeJava(), "locale vacio" );
    pol.stepsDesconocidos = PoliticaPack.PERMITIR;
    falso( pol.bloqueaDesconocidos(), "unknown_steps allow" );
  }

  public void pruebaPackComentariosBomYDesconocidas() throws Exception {
    String j = "\uFEFF{\n  // el docente lo edito a mano\n  \"format\": \"pentaho-live-exercise-pack/1\",\n"
      + "  /* bloque */ \"id\": \"c\", \"futuro\": {\"x\": [1, 2]},\n"
      + "  \"cases\": [ { \"id\": \"visible\", \"nuevo\": true } ]\n}";
    Pack p = CorrectorJson.leer( j, Pack.class );
    igual( "c", p.id, "lee con BOM, comentarios y claves desconocidas" );
    igual( "visible", p.casos.get( 0 ).id, "caso" );

    File f = new File( EntornoKettle.salida( "corrector-modelo" ), "con-bom.json" );
    Files.write( f.toPath(), j.getBytes( StandardCharsets.UTF_8 ) );
    igual( (byte) 0xEF, Files.readAllBytes( f.toPath() )[ 0 ], "el archivo empieza con BOM" );
    igual( "c", CorrectorJson.leer( f, Pack.class ).id, "lee el archivo con BOM" );
  }

  public void pruebaParaAlumnos() throws Exception {
    Pack p = CorrectorJson.leer( archivoPack( CorrectorRecursos.VENTAS ), Pack.class );
    String antes = CorrectorJson.escribir( p, false );
    Pack a = p.paraAlumnos();
    igual( antes, CorrectorJson.escribir( p, false ), "paraAlumnos no cambia el original" );
    igual( 1, a.casos.size(), "sin el caso oculto" );
    igual( "visible", a.casos.get( 0 ).id, "queda el visible" );
    nulo( a.solucion, "sin solution" );
    igual( p.rubrica.size(), a.rubrica.size(), "la rubrica no cambia" );
    noContiene( CorrectorJson.escribir( a, false ), "oculto", "el pack.json para alumnos no nombra el oculto" );

    // inline: se van la solucion y los archivos del oculto, quedan los del visible
    p.inline = new LinkedHashMap<>();
    p.inline.put( "enunciado.md", "#" );
    p.inline.put( "datos/visible/ventas.csv", "v" );
    p.inline.put( "./datos/oculto/ventas.csv", "o" );
    p.inline.put( "datos\\oculto\\otra.csv", "o2" );
    p.inline.put( "datos/oculto2/ventas.csv", "no es del oculto" );
    p.inline.put( "esperado/visible.csv", "ev" );
    p.inline.put( "esperado/oculto.csv", "eo" );
    p.inline.put( "solucion/03_ventas_por_producto.ktr", "<transformation/>" );
    Pack b = p.paraAlumnos();
    igual( new TreeSet<>( Arrays.asList( "enunciado.md", "datos/visible/ventas.csv", "datos/oculto2/ventas.csv",
      "esperado/visible.csv" ) ), new TreeSet<>( b.inline.keySet() ), "inline de la copia para alumnos" );
    igual( 8, p.inline.size(), "el inline original no cambia" );
  }

  public void pruebaRubrica() {
    for ( String c : Arrays.asList( "runs", "columns", "row_count", "values", "rule:x", "lint:unsorted_input" ) ) {
      cierto( ItemRubrica.checkValido( c ), "valido: " + c );
    }
    for ( String c : Arrays.asList( null, "", "rule:", "lint:", "magia", "Runs", "rules:x" ) ) {
      falso( ItemRubrica.checkValido( c ), "invalido: " + c );
    }
    ItemRubrica i = new ItemRubrica( "rule:usa_group_by", 1, ItemRubrica.RUNS );
    cierto( i.esRegla(), "esRegla" );
    falso( i.esLint() || i.esDeSalida(), "no es lint ni de salida" );
    igual( "usa_group_by", i.idRegla(), "idRegla" );
    nulo( i.idLint(), "idLint de una regla" );
    igual( Collections.singletonList( "runs" ), i.requiere, "requires" );
    cierto( new ItemRubrica( "values", 4 ).esDeSalida(), "values es de salida" );
    nulo( new ItemRubrica( "values", 4 ).idRegla(), "idRegla de un check de salida" );
    igual( Arrays.asList( "runs", "columns", "row_count", "values" ), ItemRubrica.DE_SALIDA, "orden de la planilla" );
    igual( Arrays.asList( "must_use", "must_not_use", "max_steps", "error_handling", "lint" ), Regla.TIPOS,
      "tipos de regla del §2.2" );
    igual( Arrays.asList( "safe", "conditional", "dangerous", "unknown" ), PoliticaPack.NIVELES, "niveles del §7" );
  }

  public void pruebaPacksInvalidosSeLeen() throws Exception {
    // El POJO no valida (lo hace CorrectorPack, C1): cada invalido se lee y el problema esta en el modelo.
    File dir = new File( new File( CorrectorRecursos.raiz(), "packs" ), "invalidos" );
    Map<String, Pack> m = new LinkedHashMap<>();
    for ( File f : ordenados( dir, ".json" ) ) {
      m.put( f.getName(), CorrectorJson.leer( f, Pack.class ).completar() );
    }
    igual( new TreeSet<>( Arrays.asList( "caso_repetido.json", "formato_desconocido.json", "inline_fuera.json",
      "rubrica_mala.json", "ruta_absoluta.json", "ruta_con_puntos.json", "sin_casos.json" ) ),
      new TreeSet<>( m.keySet() ), "packs invalidos" );
    contiene( m.get( "ruta_con_puntos.json" ).enunciado, "..", "statement con .." );
    contiene( m.get( "ruta_absoluta.json" ).casos.get( 0 ).esperado, "C:/", "expected absoluto" );
    distintoFormato( m.get( "formato_desconocido.json" ) );
    cierto( m.get( "inline_fuera.json" ).inline.containsKey( "../fuera.csv" ), "clave inline con .." );
    falso( ItemRubrica.checkValido( m.get( "rubrica_mala.json" ).itemRubrica( "magia" ).check ), "check magia" );
    nulo( m.get( "rubrica_mala.json" ).regla( "no_existe" ), "rule: de una regla que no existe" );
    cierto( m.get( "sin_casos.json" ).casos.isEmpty(), "sin casos" );
    igual( 2, m.get( "caso_repetido.json" ).casos.size(), "dos casos" );
    igual( m.get( "caso_repetido.json" ).casos.get( 0 ).id, m.get( "caso_repetido.json" ).casos.get( 1 ).id,
      "con el mismo id" );
  }

  // ------------------------------------------------------------------ resultados

  public void pruebaResultadoIdaYVuelta() throws Exception {
    ResultadoCorreccion r = muestra();
    String j1 = CorrectorJson.escribir( r, true );
    ResultadoCorreccion b = CorrectorJson.leer( j1, ResultadoCorreccion.class );
    igual( j1, CorrectorJson.escribir( b, true ), "el JSON tiene que ser identico despues de ida y vuelta" );
    noContiene( CorrectorJson.escribir( r, false ), ":null", "los null no salen" );

    // las claves del ejemplo del §6
    JsonNode t = CorrectorJson.mapper().readTree( j1 );
    for ( String k : Arrays.asList( "format", "pack", "pack_title", "student", "file", "language", "status", "grade",
      "scale", "capture", "trust_mode", "extra_allowed_steps", "checks", "blocked_steps", "rules", "lint", "cases",
      "hints", "warnings", "timing", "graded_at", "plugin_version" ) ) {
      cierto( t.has( k ), "el resultado tiene " + k );
    }
    igual( ResultadoCorreccion.FORMATO, t.path( "format" ).asText(), "format" );
    JsonNode chk = t.path( "checks" ).get( 0 );
    for ( String k : Arrays.asList( "check", "label", "points", "earned", "passed", "detail", "by_case" ) ) {
      cierto( chk.has( k ), "checks[] tiene " + k );
    }
    igual( "runs", t.path( "checks" ).get( 4 ).path( "gated_by" ).asText(), "gated_by" );
    JsonNode regla = t.path( "rules" ).path( "usa_group_by" );
    cierto( regla.path( "passed" ).asBoolean(), "rules.usa_group_by.passed" );
    cierto( regla.path( "evaluated" ).asBoolean(), "rules.usa_group_by.evaluated" );
    falso( t.path( "lint" ).path( "unsorted_input" ).path( "evaluated" ).asBoolean( true ), "lint no evaluado" );
    JsonNode bl = t.path( "blocked_steps" ).get( 0 );
    igual( "conditional", bl.path( "level" ).asText(), "blocked_steps[].level" );
    igual( "step_not_allowed", bl.path( "reason" ).asText(), "blocked_steps[].reason" );
    JsonNode caso = t.path( "cases" ).get( 0 );
    for ( String k : Arrays.asList( "id", "hidden", "weight", "redirections", "warnings", "run", "comparison" ) ) {
      cierto( caso.has( k ), "cases[] tiene " + k );
    }
    JsonNode run = caso.path( "run" );
    for ( String k : Arrays.asList( "state", "seconds", "errors", "output_step", "output_step_type",
      "captured_from", "rows_captured", "metrics", "log" ) ) {
      cierto( run.has( k ), "run tiene " + k );
    }
    igual( 5, run.path( "metrics" ).get( 0 ).path( "written" ).asInt(), "metrics como las de RunMotor" );
    JsonNode cmp = caso.path( "comparison" );
    for ( String k : Arrays.asList( "mode", "key_columns", "columns", "rows", "examples", "differences_by_column",
      "columns_score", "row_count_ok", "values_score" ) ) {
      cierto( cmp.has( k ), "comparison tiene " + k );
    }
    for ( String k : Arrays.asList( "expected", "actual", "missing", "extra", "renamed_suggestions",
      "order_differs" ) ) {
      cierto( cmp.path( "columns" ).has( k ), "comparison.columns tiene " + k );
    }
    for ( String k : Arrays.asList( "expected", "actual", "matched", "different", "missing", "extra",
      "duplicate_keys" ) ) {
      cierto( cmp.path( "rows" ).has( k ), "comparison.rows tiene " + k );
    }
    JsonNode dif = cmp.path( "examples" ).path( "different" ).get( 0 );
    igual( "Café", dif.path( "key" ).asText(), "examples.different[].key" );
    igual( "importe_medio", dif.path( "column" ).asText(), "examples.different[].column" );
    igual( "5.00", dif.path( "expected" ).asText(), "examples.different[].expected" );
    igual( "7.50", dif.path( "actual" ).asText(), "examples.different[].actual" );
    igual( 4, cmp.path( "examples" ).path( "duplicate_keys" ).get( 0 ).path( "count" ).asInt(), "count" );
    igual( "Jugo", cmp.path( "examples" ).path( "missing" ).get( 0 ).path( "row" ).path( "producto" ).asText(),
      "examples.missing[].row" );
    igual( 6.0, t.path( "timing" ).path( "kettle_init_s" ).asDouble(), "timing.kettle_init_s" );

    // y vuelve con los mismos tipos
    igual( 6.5, b.nota, "grade" );
    igual( "importe_medio", b.caso( "visible" ).comparacion.columnas.renombres.get( 0 ).esperada, "renombre" );
    igual( 1.0, b.caso( "visible" ).comparacion.columnas.renombres.get( 0 ).coincidencia, "ratio" );
    cierto( b.caso( "visible" ).corrioBien(), "corrioBien" );
    falso( b.caso( "oculto" ).corrioBien(), "el oculto termino con errores" );
    igual( 0.25, b.criterio( "values" ).porCaso.get( "visible" ), "by_case" );
    nulo( b.criterio( "no_existe" ), "criterio que no existe" );
    cierto( b.terminada(), "terminada" );
  }

  public void pruebaResultadoParcial() throws Exception {
    ResultadoCorreccion r = new ResultadoCorreccion();
    r.pack = "ventas-por-producto";
    r.alumno = "ana";
    igual( ResultadoCorreccion.EN_CURSO, r.estado, "el parcial nace en curso" );
    falso( r.terminada(), "no terminada" );
    String j = CorrectorJson.escribir( r, false );
    noContiene( j, "\"grade\"", "el parcial no tiene nota" );
    ResultadoCorreccion n = CorrectorJson.leer( "{\"status\":\"crashed\",\"checks\":null,\"cases\":[{\"id\":\"v\","
      + "\"run\":null,\"redirections\":null}],\"rules\":null,\"futuro\":1}", ResultadoCorreccion.class ).completar();
    igual( ResultadoCorreccion.CAIDA, n.estado, "status" );
    noNulo( n.criterios, "checks" );
    noNulo( n.reglas, "rules" );
    noNulo( n.caso( "v" ).corrida, "run completado" );
    noNulo( n.caso( "v" ).redirecciones, "redirections completado" );
    falso( n.caso( "v" ).corrioBien(), "sin estado no corrio bien" );
  }

  public void pruebaEstadosDelDiseno() {
    igual( Arrays.asList( "graded", "blocked", "timeout", "killed_timeout", "load_failed", "crashed", "running" ),
      ResultadoCorreccion.ESTADOS, "estados de la entrega (§6)" );
    igual( Arrays.asList( "finished", "finished_with_errors", "timeout", "too_many_rows", "output_too_big",
      "init_failed", "start_failed", "no_output_step", "blocked" ), ResultadoCaso.ESTADOS, "estados del caso (§6)" );
    for ( String m : Arrays.asList( "unexpected_input", "unexpected_output", "duplicate_step_name", "cycle",
      "foreign_connection", "dangerous_step", "step_not_allowed", "unknown_step", "forbidden_sql" ) ) {
      cierto( ResultadoCaso.motivoValido( m ), "motivo del §3/§7: " + m );
    }
    falso( ResultadoCorreccion.estadoValido( "finished" ), "finished es de caso, no de entrega" );
    falso( ResultadoCaso.estadoValido( "graded" ), "graded es de entrega, no de caso" );
    igual( Arrays.asList( "key", "multiset", "position" ), Arrays.asList( Comparacion.POR_CLAVE,
      Comparacion.MULTICONJUNTO, Comparacion.POR_POSICION ), "modos de comparacion (§4)" );
    igual( Arrays.asList( "file", "step", "query" ), Pack.Salida.CAPTURAS, "capturas (§2.2)" );
  }

  public void pruebaParaAlumnoYSinLog() throws Exception {
    ResultadoCorreccion r = muestra();
    String antes = CorrectorJson.escribir( r, false );
    ResultadoCorreccion a = r.paraAlumno();
    igual( antes, CorrectorJson.escribir( r, false ), "paraAlumno no cambia el original" );
    ResultadoCaso vis = a.caso( "visible" );
    ResultadoCaso ocu = a.caso( "oculto" );
    noNulo( vis.comparacion.ejemplos, "el visible conserva los ejemplos" );
    noNulo( vis.corrida.log, "el visible conserva el log" );
    nulo( ocu.comparacion.ejemplos, "el oculto no lleva ejemplos" );
    nulo( ocu.corrida.log, "el oculto no lleva log" );
    nulo( ocu.corrida.detalle, "el oculto no lleva el detalle del error" );
    igual( 3, ocu.comparacion.filas.distintas, "el oculto conserva los conteos" );
    igual( 3, (int) ocu.comparacion.diferenciasPorColumna.get( "importe_medio" ), "y las diferencias por columna" );
    igual( 0.0, ocu.comparacion.puntajeValores, "y los puntajes" );
    String ja = CorrectorJson.escribir( a, false );
    noContiene( ja, "SECRETO-OCULTO", "ningun valor del oculto en la devolucion" );
    contiene( antes, "SECRETO-OCULTO", "el registro del docente si los tiene" );

    ResultadoCorreccion s = r.sinLog();
    noContiene( CorrectorJson.escribir( s, false ), "\"log\"", "sinLog saca todos los logs" );
    noNulo( s.caso( "oculto" ).comparacion.ejemplos, "sinLog no saca ejemplos" );
    noNulo( r.caso( "visible" ).corrida.log, "sinLog no cambia el original" );
  }

  public void pruebaResultadosDelPrototipo() throws Exception {
    File dir = new File( CorrectorRecursos.raiz(), "resultados" );
    List<File> fs = ordenados( dir, ".json" );
    igual( 10, fs.size(), "los 10 resultados del prototipo" );
    for ( File f : fs ) {
      JsonNode original = CorrectorJson.mapper().readTree( f );
      ResultadoCorreccion r = CorrectorJson.leer( f, ResultadoCorreccion.class );
      String j1 = CorrectorJson.escribir( r, true );
      igual( j1, CorrectorJson.escribir( CorrectorJson.leer( j1, ResultadoCorreccion.class ), true ),
        f.getName() + ": ida y vuelta estable" );
      incluido( original, CorrectorJson.mapper().readTree( j1 ), f.getName() );
      igual( ResultadoCorreccion.FORMATO, r.formato, f.getName() + ": format" );
      cierto( ResultadoCorreccion.estadoValido( r.estado ), f.getName() + ": status valido " + r.estado );
      cierto( r.terminada(), f.getName() + ": terminada" );
      igual( f.getName(), r.alumno + ".json", "student = nombre del archivo" );
      cierto( r.nota != null && r.nota >= 0 && r.nota <= r.escala, f.getName() + ": nota en [0, escala]" );
      double puntos = 0;
      double ganado = 0;
      for ( ResultadoCorreccion.Criterio c : r.criterios ) {
        puntos += c.puntos;
        ganado += c.ganado;
        cierto( c.ganado >= 0 && c.ganado <= c.puntos + 1e-9, f.getName() + ": earned en [0, points] " + c.check );
      }
      cierto( Math.abs( r.nota - ganado / puntos * r.escala ) <= 0.051, f.getName() + ": nota = ganado / puntos" );
      if ( ResultadoCorreccion.BLOQUEADA.equals( r.estado ) ) {
        cierto( ResultadoCaso.motivoValido( r.motivo ), f.getName() + ": motivo valido" );
        cierto( !r.stepsBloqueados.isEmpty(), f.getName() + ": blocked_steps" );
      }
      for ( ResultadoCaso c : r.casos ) {
        cierto( ResultadoCaso.estadoValido( c.corrida.estado ), f.getName() + "/" + c.id + ": estado "
          + c.corrida.estado );
        if ( c.comparacion != null ) {
          Comparacion.Filas fl = c.comparacion.filas;
          cierto( fl.iguales + fl.distintas + fl.faltan + fl.clavesRepetidas >= 0 && fl.iguales <= fl.esperadas,
            f.getName() + "/" + c.id + ": conteos" );
        }
      }
    }
    ResultadoCorreccion bruno = CorrectorJson.leer( CorrectorRecursos.resultado( "bruno_agregado_mal" ),
      ResultadoCorreccion.class );
    igual( 6.5, bruno.nota, "bruno 6,5 (§1)" );
    igual( 0.5, bruno.criterio( "values" ).ganado, "bruno values 0,5" );
    Comparacion.Ejemplo e = bruno.caso( "visible" ).comparacion.ejemplos.distintas.get( 0 );
    igual( "Café|importe_medio|5.00|7.50", e.clave + "|" + e.columna + "|" + e.esperado + "|" + e.real,
      "«Café · importe_medio · esperado 5.00 · tuyo 7.50»" );
    ResultadoCorreccion elena = CorrectorJson.leer( CorrectorRecursos.resultado( "elena_sin_ordenar" ),
      ResultadoCorreccion.class );
    Comparacion.Ejemplo rep = elena.caso( "visible" ).comparacion.ejemplos.clavesRepetidas.get( 0 );
    igual( "Café x4", rep.clave + " x" + rep.veces, "elena: Café repetido 4 veces" );
    ResultadoCorreccion gabi = CorrectorJson.leer( CorrectorRecursos.resultado( "gabi_renombra" ),
      ResultadoCorreccion.class );
    igual( "importe_medio->promedio", gabi.caso( "visible" ).comparacion.columnas.renombres.get( 0 ).esperada + "->"
      + gabi.caso( "visible" ).comparacion.columnas.renombres.get( 0 ).real, "gabi: sugerencia de renombre" );
    ResultadoCorreccion diego = CorrectorJson.leer( CorrectorRecursos.resultado( "diego_javascript" ),
      ResultadoCorreccion.class );
    igual( ResultadoCorreccion.BLOQUEADA, diego.estado, "diego bloqueado" );
    igual( "ScriptValueMod", diego.stepsBloqueados.get( 0 ).tipo, "por ScriptValueMod" );
    cierto( diego.casos.isEmpty(), "diego no tiene casos" );
    ResultadoCorreccion juan = CorrectorJson.leer( CorrectorRecursos.resultado( "juan_tabla_max" ),
      ResultadoCorreccion.class );
    igual( Pack.Salida.CAPTURA_QUERY, juan.captura, "juan: captura query" );
    igual( 5.5, juan.nota, "juan 5,5 (P5)" );
  }

  public void pruebaGuardarAtomico() throws Exception {
    File dir = new File( EntornoKettle.salida( "corrector-modelo" ), "atomico" );
    EntornoKettle.borrar( dir.toPath() );
    File f = new File( new File( dir, "sub" ), "ana.json" );
    ResultadoCorreccion r = muestra();
    r.estado = ResultadoCorreccion.EN_CURSO;
    r.nota = null;
    CorrectorJson.guardar( r, f );
    igual( ResultadoCorreccion.EN_CURSO, CorrectorJson.leer( f, ResultadoCorreccion.class ).estado, "parcial" );
    r.estado = ResultadoCorreccion.CORREGIDA;
    r.nota = 6.5;
    CorrectorJson.guardar( r, f );
    ResultadoCorreccion l = CorrectorJson.leer( f, ResultadoCorreccion.class );
    igual( ResultadoCorreccion.CORREGIDA, l.estado, "el final reemplaza al parcial" );
    igual( 6.5, l.nota, "nota" );
    String[] hijos = f.getParentFile().list();
    igual( Collections.singletonList( "ana.json" ), Arrays.asList( hijos ), "no quedan temporales" );
    contiene( new String( Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 ), "\"Café\"",
      "UTF-8 sin escapar" );
  }

  // ------------------------------------------------------------------ fixtures

  public void pruebaCasosJsonCompleto() throws Exception {
    JsonNode casos = CorrectorRecursos.casos();
    Set<String> packs = new HashSet<>();
    int total = 0;
    for ( JsonNode p : casos.path( "packs" ) ) {
      String id = p.path( "id" ).asText();
      packs.add( id );
      File dirPack = new File( CorrectorRecursos.raiz(), p.path( "pack" ).asText() );
      cierto( dirPack.isDirectory(), id + ": existe " + dirPack );
      Pack pack = CorrectorJson.leer( new File( dirPack, "pack.json" ), Pack.class );
      igual( id, pack.id, "id del pack" );
      igual( pack.escala, p.path( "solution_grade" ).asDouble(), id + ": la solucion saca la nota maxima" );
      File dirEntregas = new File( CorrectorRecursos.raiz(), p.path( "submissions" ).asText() );
      Set<String> archivos = new TreeSet<>();
      for ( File f : ordenados( dirEntregas, ".ktr" ) ) {
        archivos.add( f.getName() );
      }
      Set<String> listados = new TreeSet<>();
      for ( Iterator<String> it = p.path( "expected" ).fieldNames(); it.hasNext(); ) {
        listados.add( it.next() );
      }
      igual( archivos, listados, id + ": casos.json lista exactamente las entregas de la carpeta" );
      for ( Iterator<Map.Entry<String, JsonNode>> it = p.path( "expected" ).fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        String quien = id + "/" + e.getKey();
        JsonNode x = e.getValue();
        String st = x.path( "status" ).asText();
        cierto( ResultadoCorreccion.estadoValido( st ) && !ResultadoCorreccion.EN_CURSO.equals( st ),
          quien + ": status " + st );
        double nota = x.path( "grade" ).asDouble( -1 );
        cierto( nota >= 0 && nota <= pack.escala, quien + ": grade en [0, escala]" );
        cierto( x.path( "tags" ).size() > 0, quien + ": tiene etiquetas" );
        cierto( x.path( "note" ).asText().length() > 10, quien + ": tiene nota explicativa" );
        if ( ResultadoCorreccion.BLOQUEADA.equals( st ) ) {
          cierto( ResultadoCaso.motivoValido( x.path( "reason" ).asText() ), quien + ": reason valido" );
          falso( x.has( "cases" ), quien + ": bloqueada no tiene casos" );
        } else if ( ResultadoCorreccion.CORREGIDA.equals( st ) ) {
          Set<String> ids = new TreeSet<>();
          for ( Iterator<Map.Entry<String, JsonNode>> ic = x.path( "cases" ).fields(); ic.hasNext(); ) {
            Map.Entry<String, JsonNode> c = ic.next();
            ids.add( c.getKey() );
            cierto( ResultadoCaso.estadoValido( c.getValue().asText() ), quien + ": estado de " + c.getKey() );
          }
          Set<String> idsPack = new TreeSet<>();
          for ( Caso c : pack.casos ) {
            idsPack.add( c.id );
          }
          igual( idsPack, ids, quien + ": un estado por caso del pack" );
        }
        total++;
      }
    }
    igual( new HashSet<>( Arrays.asList( CorrectorRecursos.VENTAS, CorrectorRecursos.TABLA ) ), packs, "packs" );
    igual( 34, total, "entregas en casos.json" );
    igual( Arrays.asList( "ana_orden_distinto.ktr", "t1_paso_ventas_csv.ktr", "t2_conexion_suelta.ktr",
      "t3_decimal_coma.ktr", "t4_encoding_vacio.ktr", "t7_paso_suelto.ktr", "mati_rutas_mac.ktr" ),
      CorrectorRecursos.conEtiqueta( CorrectorRecursos.VENTAS, "absolute_paths" ), "conEtiqueta absolute_paths" );
    igual( 6.5, CorrectorRecursos.esperado( CorrectorRecursos.VENTAS, "bruno_agregado_mal.ktr" ).path( "grade" )
      .asDouble(), "esperado de bruno" );
    nulo( CorrectorRecursos.esperado( CorrectorRecursos.VENTAS, "no_existe.ktr" ), "esperado de una que no existe" );
  }

  public void pruebaArchivosDeLosPacks() throws Exception {
    for ( String id : Arrays.asList( CorrectorRecursos.VENTAS, CorrectorRecursos.TABLA ) ) {
      File dir = CorrectorRecursos.pack( id );
      Pack p = CorrectorJson.leer( new File( dir, "pack.json" ), Pack.class );
      for ( String rel : Arrays.asList( p.enunciado, p.solucion ) ) {
        cierto( new File( dir, rel ).isFile(), id + ": existe " + rel );
      }
      if ( p.salida.base != null ) {
        cierto( new File( dir, p.salida.base.plantilla ).isFile(), id + ": existe la plantilla" );
      }
      for ( Caso c : p.casos ) {
        for ( String e : p.entradas ) {
          cierto( new File( new File( dir, c.dirEntradas ), e ).isFile(), id + "/" + c.id + ": existe " + e );
        }
        verificarEsperado( id + "/" + c.id, leerTexto( new File( dir, c.esperado ) ), p );
      }
    }
    Pack in = CorrectorJson.leer( CorrectorRecursos.pack( CorrectorRecursos.INLINE ), Pack.class );
    cierto( in.inline.containsKey( in.enunciado ), "inline: enunciado" );
    for ( Caso c : in.casos ) {
      for ( String e : in.entradas ) {
        cierto( in.inline.containsKey( c.dirEntradas + "/" + e ), "inline: datos de " + c.id );
      }
      verificarEsperado( "inline/" + c.id, in.inline.get( c.esperado ), in );
    }
    long tam = 0;
    for ( String v : in.inline.values() ) {
      tam += v.length();
    }
    cierto( tam < Pack.TOPE_INLINE, "inline bajo el tope de 2 MB" );
  }

  /** Las entregas cargan con new TransMeta (salvo t5, DOCTYPE) y dejan ver lo que dice casos.json. */
  public void pruebaEntregasCargan() throws Exception {
    EntornoKettle.iniciar();
    File r = CorrectorRecursos.copia();
    int n = 0;
    for ( String pack : Arrays.asList( CorrectorRecursos.VENTAS, CorrectorRecursos.TABLA ) ) {
      for ( File f : ordenados( new File( new File( r, "entregas" ), pack ), ".ktr" ) ) {
        n++;
        TransMeta tm;
        try {
          tm = new TransMeta( f.getAbsolutePath() );
        } catch ( Exception e ) {
          if ( f.getName().equals( "t5_xxe.ktr" ) ) {
            continue;
          }
          throw e;
        }
        if ( f.getName().equals( "t5_xxe.ktr" ) ) {
          falla( "t5_xxe tendria que fallar al cargar (DOCTYPE)" );
        }
        boolean faltante = false;
        boolean ciclo = false;
        for ( StepMeta sm : tm.getSteps() ) {
          faltante |= sm.isMissing();
          ciclo |= tm.hasLoop( sm );
        }
        igual( f.getName().equals( "oscar_step_desconocido.ktr" ), faltante, f.getName() + ": step faltante" );
        igual( f.getName().equals( "fede_bucle.ktr" ), ciclo, f.getName() + ": hop circular" );
        if ( f.getName().equals( "pia_nombre_repetido.ktr" ) ) {
          igual( 4, tm.nrSteps(), "pia: Kettle funde los dos steps (D1 solo se ve en el XML)" );
        }
        if ( f.getName().equals( "nico_ejecuta_proceso.ktr" ) ) {
          cierto( tm.findStep( "Ejecutar comando" ).getStepID().equals( "ExecProcess" ), "nico: ExecProcess" );
        }
      }
      for ( File f : ordenados( new File( new File( new File( r, "packs" ), pack ), "solucion" ), ".ktr" ) ) {
        noNulo( new TransMeta( f.getAbsolutePath() ), "carga la solucion de " + pack );
      }
    }
    igual( 34, n, "entregas cargadas" );
  }

  // ------------------------------------------------------------------ ayudas

  private static File archivoPack( String id ) {
    File p = CorrectorRecursos.pack( id );
    return p.isDirectory() ? new File( p, "pack.json" ) : p;
  }

  private static void distintoFormato( Pack p ) {
    cierto( !Pack.FORMATO.equals( p.formato ), "format distinto: " + p.formato );
  }

  /** El CSV esperado: encabezado = output.columns, y la clave unica (§4: si no, la solucion sacaria repetidas). */
  private static void verificarEsperado( String quien, String csv, Pack p ) {
    noNulo( csv, quien + ": esperado" );
    String sep = p.salida.csvEsperado.separador;
    List<String> lineas = new ArrayList<>();
    for ( String l : csv.replace( "\r", "" ).split( "\n" ) ) {
      if ( !l.trim().isEmpty() ) {
        lineas.add( l );
      }
    }
    List<String> cab = Arrays.asList( lineas.get( 0 ).split( java.util.regex.Pattern.quote( sep ), -1 ) );
    igual( p.salida.nombresColumnas(), cab, quien + ": encabezado del esperado" );
    int k = cab.indexOf( p.comparar.columnasClave.get( 0 ) );
    Set<String> claves = new HashSet<>();
    for ( String l : lineas.subList( 1, lineas.size() ) ) {
      String[] v = l.split( java.util.regex.Pattern.quote( sep ), -1 );
      igual( cab.size(), v.length, quien + ": columnas de " + l );
      cierto( claves.add( v[ k ] ), quien + ": clave repetida en el esperado: " + v[ k ] );
    }
    cierto( claves.size() >= 4, quien + ": al menos 4 filas" );
  }

  private static String leerTexto( File f ) throws Exception {
    return new String( Files.readAllBytes( f.toPath() ), StandardCharsets.UTF_8 );
  }

  private static List<File> ordenados( File dir, String ext ) {
    List<File> l = new ArrayList<>();
    File[] fs = dir.listFiles();
    if ( fs != null ) {
      for ( File f : fs ) {
        if ( f.isFile() && f.getName().endsWith( ext ) ) {
          l.add( f );
        }
      }
    }
    Collections.sort( l );
    return l;
  }

  /**
   * Todo lo que dice el original esta en la copia con el mismo valor (los numeros por valor: 1 = 1.0). Un null, []
   * o {} del original no exige nada (al escribir se omiten). Asi una clave mal escrita en un @JsonProperty, que
   * Jackson ignoraria en silencio al leer, hace fallar la prueba.
   */
  static void incluido( JsonNode original, JsonNode copia, String ruta ) {
    if ( vacio( original ) ) {
      return;
    }
    if ( copia == null || copia.isMissingNode() || copia.isNull() ) {
      falla( ruta + ": se perdio al ir y volver (" + original + ")" );
      return;
    }
    if ( original.isObject() ) {
      cierto( copia.isObject(), ruta + ": tendria que ser un objeto" );
      for ( Iterator<Map.Entry<String, JsonNode>> it = original.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        incluido( e.getValue(), copia.get( e.getKey() ), ruta + "." + e.getKey() );
      }
    } else if ( original.isArray() ) {
      cierto( copia.isArray(), ruta + ": tendria que ser una lista" );
      igual( original.size(), copia.size(), ruta + ": largo de la lista" );
      for ( int i = 0; i < original.size(); i++ ) {
        incluido( original.get( i ), copia.get( i ), ruta + "[" + i + "]" );
      }
    } else if ( original.isNumber() ) {
      cierto( copia.isNumber(), ruta + ": tendria que ser un numero" );
      igual( original.asDouble(), copia.asDouble(), ruta );
    } else {
      igual( original.asText(), copia.asText(), ruta );
      igual( original.getNodeType(), copia.getNodeType(), ruta + ": tipo" );
    }
  }

  private static boolean vacio( JsonNode n ) {
    return n == null || n.isNull() || n.isMissingNode() || ( n.isContainerNode() && n.size() == 0 );
  }

  /** Un resultado con todos los campos del §6: bruno con un caso oculto que termino con errores. */
  static ResultadoCorreccion muestra() {
    ResultadoCorreccion r = new ResultadoCorreccion();
    r.pack = "ventas-por-producto";
    r.tituloPack = "Ejercicio 3 - Ventas por producto";
    r.alumno = "bruno_agregado_mal";
    r.archivo = "bruno_agregado_mal.ktr";
    r.idioma = "es";
    r.estado = ResultadoCorreccion.CORREGIDA;
    r.nota = 6.5;
    r.escala = 10;
    r.captura = Pack.Salida.CAPTURA_ARCHIVO;
    r.stepsPermitidosExtra.add( "SetVariable" );
    String[][] checks = { { "runs", "1", "1" }, { "columns", "2", "2" }, { "row_count", "1", "1" },
      { "values", "4", "0.5" }, { "rule:usa_group_by", "1", "0" }, { "rule:sin_codigo", "0.5", "0.5" },
      { "rule:max_pasos", "0.5", "0.5" } };
    for ( String[] c : checks ) {
      ResultadoCorreccion.Criterio k = new ResultadoCorreccion.Criterio( c[ 0 ], Double.parseDouble( c[ 1 ] ),
        Double.parseDouble( c[ 2 ] ) );
      k.etiqueta = "Etiqueta de " + c[ 0 ];
      k.aprobado = k.ganado >= k.puntos;
      k.detalle = "visible 25%, oculto 0%";
      k.porCaso = new LinkedHashMap<>();
      k.porCaso.put( "visible", 0.25 );
      k.porCaso.put( "oculto", 0.0 );
      r.criterios.add( k );
    }
    r.criterios.get( 4 ).compuerta = ItemRubrica.RUNS;
    r.stepsBloqueados.add( new ResultadoCorreccion.PasoBloqueado( "Calcular importe", "ScriptValueMod",
      PoliticaPack.CONDICIONAL, ResultadoCaso.STEP_NO_PERMITIDO ) );
    r.reglas.put( "usa_group_by", new ResultadoCorreccion.ReglaEvaluada( "Usa un Group by", true,
      "Found: [Totales por producto]" ) );
    ResultadoCorreccion.ReglaEvaluada ne = new ResultadoCorreccion.ReglaEvaluada( "unsorted_input", false,
      "not evaluated: linter missing" );
    ne.evaluada = false;
    r.lint.put( "unsorted_input", ne );

    ResultadoCaso v = caso( "visible", false, ResultadoCaso.TERMINO, 0 );
    v.comparacion.columnas.faltan.add( "importe_medio" );
    v.comparacion.columnas.sobran.add( "promedio" );
    v.comparacion.columnas.renombres.add( new Comparacion.Renombre( "importe_medio", "promedio", 1.0 ) );
    v.avisos.add( new ResultadoCorreccion.Aviso( ResultadoCorreccion.Aviso.COMA_DECIMAL, "20,00" ) );
    r.casos.add( v );
    ResultadoCaso o = caso( "oculto", true, ResultadoCaso.TERMINO_CON_ERRORES, 1 );
    o.corrida.detalle = "Unexpected conversion error: SECRETO-OCULTO";
    o.corrida.log = "2026/10/20 - Leer ventas.0 - fila [SECRETO-OCULTO]";
    o.comparacion.ejemplos.distintas.get( 0 ).esperado = "SECRETO-OCULTO";
    o.comparacion.puntajeValores = 0;
    r.casos.add( o );
    r.pistas.add( "Revisá el tipo de agregación de importe_medio." );
    ResultadoCorreccion.Aviso av = new ResultadoCorreccion.Aviso( ResultadoCorreccion.Aviso.CONEXION_QUITADA,
      "mysql_de_clase" );
    av.step = null;
    r.avisos.add( av );
    r.tiempos = new ResultadoCorreccion.Tiempos();
    r.tiempos.inicioKettleSeg = 6.0;
    r.tiempos.procesoSeg = 8.0;
    r.corregidoEl = "2026-10-21T10:00:00+02:00";
    r.versionPlugin = "0.6.0";
    return r;
  }

  private static ResultadoCaso caso( String id, boolean oculto, String estado, long errores ) {
    ResultadoCaso c = new ResultadoCaso();
    c.id = id;
    c.oculto = oculto;
    c.redirecciones.add( "output 'Escribir totales': C:\\Users\\ana\\resultado -> salida/resultado" );
    c.corrida.estado = estado;
    c.corrida.segundos = 0.27;
    c.corrida.errores = errores;
    c.corrida.stepSalida = "Escribir totales";
    c.corrida.tipoStepSalida = "TextFileOutput";
    c.corrida.capturadoDe = "file salida/03_ventas_por_producto.csv";
    c.corrida.filasCapturadas = 4L;
    ArrayNode m = CorrectorJson.mapper().createArrayNode();
    ObjectNode x = m.addObject();
    x.put( "step", "Escribir totales" ).put( "read", 4 ).put( "written", 5 ).put( "errors", 0 );
    c.corrida.metricas = m;
    c.corrida.log = "2026/10/20 - Escribir totales.0 - Finished";
    Comparacion cmp = new Comparacion();
    cmp.modo = Comparacion.POR_CLAVE;
    cmp.columnasClave.add( "producto" );
    cmp.columnas.esperadas.addAll( Arrays.asList( "producto", "importe_medio" ) );
    cmp.columnas.reales.addAll( Arrays.asList( "producto", "importe_medio" ) );
    cmp.filas.esperadas = 4;
    cmp.filas.reales = 4;
    cmp.filas.iguales = 1;
    cmp.filas.distintas = 3;
    cmp.ejemplos.distintas.add( Comparacion.Ejemplo.diferencia( "Café", "importe_medio", "5.00", "7.50" ) );
    cmp.ejemplos.clavesRepetidas.add( Comparacion.Ejemplo.repetida( "Café", 4 ) );
    Comparacion.Ejemplo falta = new Comparacion.Ejemplo();
    falta.clave = "Jugo";
    falta.fila = new LinkedHashMap<>();
    falta.fila.put( "producto", "Jugo" );
    falta.fila.put( "importe_medio", "7.23" );
    cmp.ejemplos.faltan.add( falta );
    cmp.diferenciasPorColumna.put( "importe_medio", 3 );
    cmp.puntajeColumnas = 1;
    cmp.cantidadFilasOk = true;
    cmp.puntajeValores = 0.25;
    c.comparacion = cmp;
    return c;
  }
}
