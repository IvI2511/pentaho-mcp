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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepErrorMeta;
import org.pentaho.di.trans.step.StepMeta;

import com.fasterxml.jackson.databind.JsonNode;

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
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.Aviso;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.Criterio;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.PasoBloqueado;
import io.github.ivi2511.pentahomcp.corrector.modelo.ResultadoCorreccion.ReglaEvaluada;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.lint.Hallazgo;
import io.github.ivi2511.pentahomcp.lint.LintMotor;
import io.github.ivi2511.pentahomcp.lint.Opciones;
import io.github.ivi2511.pentahomcp.lint.Resultado;
import io.github.ivi2511.pentahomcp.lint.Supresiones;

/**
 * La nota (C5, docs/corrector-diseno.md §5 y §6) sin ejecutar nada: la rubrica, las compuertas, la ponderacion por
 * caso, el redondeo y las pistas sobre los 10 resultados fijos del prototipo (resultados/) y sobre resultados armados a
 * mano; las reglas del pack, los lint:* y D1/D2 sobre las entregas cargadas en un TransMeta (eso si necesita Kettle:
 * las pruebas *Estatic*, *Lint*, *Silencios* y *SinEjecutar*); el .md minimo y los textos en/es/pt.
 *
 * @dueño C5
 */
public class PruebaCorrectorNota {

  private static final String VENTAS = CorrectorRecursos.VENTAS;
  private static final String TABLA = CorrectorRecursos.TABLA;
  /** Los resultados del prototipo (resultados/), con el pack de cada uno. */
  private static final String[] PROTOTIPO = { "ana_orden_distinto", "bruno_agregado_mal", "carla_falta_columna",
    "diego_javascript", "elena_sin_ordenar", "fede_bucle", "gabi_renombra", "hugo_decimal", "ines_tabla_ok",
    "juan_tabla_max" };
  /** Una clave de Rotulos que no se resolvio (se devuelve tal cual). */
  private static final Pattern CLAVE_CRUDA = Pattern.compile(
    "\\b(check|detalle|estado|caso|motivo|regla|lint|pista|md)\\.[a-z_]+" );
  private static final double TOL = 0.005;

  private File copia;

  // ================================================================ rubrica sobre los resultados del prototipo

  /** Las notas del diseño (casos.json, con la compuerta de usa_group_by) desde los resultados fijos. */
  public void pruebaNotasDelPrototipo() throws Exception {
    for ( String a : PROTOTIPO ) {
      ResultadoCorreccion r = resultado( a );
      Pack p = pack( r.pack );
      JsonNode esp = CorrectorRecursos.esperado( r.pack, a + ".ktr" );
      noNulo( esp, a + " en casos.json" );
      CorrectorNota.puntuar( p, r, null );
      igual( esp.path( "grade" ).asDouble(), r.nota, a + ": nota" );
      igual( p.rubrica.size(), r.criterios.size(), a + ": un criterio por renglon" );
      igual( 10.0, r.escala, a + ": escala" );
      for ( int i = 0; i < p.rubrica.size(); i++ ) {
        Criterio c = r.criterios.get( i );
        igual( p.rubrica.get( i ).check, c.check, a + ": en el orden de la rubrica" );
        noNulo( c.etiqueta, a + ": etiqueta de " + c.check );
        noNulo( c.detalle, a + ": detalle de " + c.check );
        cierto( c.ganado >= 0 && c.ganado <= c.puntos + TOL, a + ": " + c.check + " entre 0 y sus puntos" );
        sinClaves( c.etiqueta + " " + c.detalle, a );
      }
    }

    ResultadoCorreccion bruno = puntuado( "bruno_agregado_mal" );
    criterio( bruno, "values", 0.5, false );
    igual( "visible 25%, oculto 0%", bruno.criterio( "values" ).detalle, "bruno: detalle por caso" );
    igual( 0.25, bruno.criterio( "values" ).porCaso.get( "visible" ), "bruno: by_case visible" );
    igual( 0.0, bruno.criterio( "values" ).porCaso.get( "oculto" ), "bruno: by_case oculto" );
    criterio( bruno, "runs", 1, true );
    igual( "Corre sin errores", bruno.criterio( "runs" ).etiqueta, "etiqueta de runs" );
    igual( "Valores correctos", bruno.criterio( "values" ).etiqueta, "etiqueta de values" );
    igual( "Usa un Group by (o Memory group by)", bruno.criterio( "rule:usa_group_by" ).etiqueta,
      "etiqueta de la regla (la del pack)" );
    nulo( bruno.criterio( "rule:usa_group_by" ).compuerta, "bruno corre: la compuerta no se aplica" );

    ResultadoCorreccion carla = puntuado( "carla_falta_columna" );
    criterio( carla, "columns", 1.67, false );
    igual( "visible 83%, oculto 83%", carla.criterio( "columns" ).detalle, "carla: columnas 5/6" );
    criterio( carla, "values", 4, true );

    ResultadoCorreccion elena = puntuado( "elena_sin_ordenar" );
    criterio( elena, "row_count", 0, false );
    criterio( elena, "values", 0.5, false );

    // compuertas: diego (bloqueada) y fede (no termina) no suman usa_group_by aunque lo tengan
    ResultadoCorreccion diego = puntuado( "diego_javascript" );
    criterio( diego, "runs", 0, false );
    contiene( diego.criterio( "runs" ).detalle, "step no permitido", "diego: por que no corrio" );
    criterio( diego, "rule:usa_group_by", 0, false );
    igual( "runs", diego.criterio( "rule:usa_group_by" ).compuerta, "diego: gated_by" );
    contiene( diego.criterio( "rule:usa_group_by" ).detalle, "no suma porque falla «Corre sin errores»",
      "diego: el detalle dice la compuerta" );
    criterio( diego, "rule:sin_codigo", 0, false );
    criterio( diego, "rule:max_pasos", 0.5, true );
    igual( 0.0, diego.criterio( "values" ).porCaso.get( "oculto" ), "diego: un caso sin resultado vale 0" );

    ResultadoCorreccion fede = puntuado( "fede_bucle" );
    criterio( fede, "rule:usa_group_by", 0, false );
    igual( "runs", fede.criterio( "rule:usa_group_by" ).compuerta, "fede: gated_by" );
    igual( "visible: no terminó a tiempo, oculto: no terminó a tiempo", fede.criterio( "values" ).detalle,
      "fede: el estado de cada caso" );
  }

  /** Sin compuertas, la misma cuenta que el prototipo: su nota y lo ganado en cada renglon. */
  public void pruebaSinCompuertaComoElPrototipo() throws Exception {
    for ( String a : PROTOTIPO ) {
      ResultadoCorreccion original = resultado( a );
      ResultadoCorreccion r = resultado( a );
      Pack p = pack( r.pack );
      for ( ItemRubrica i : p.rubrica ) {
        i.requiere.clear();
      }
      CorrectorNota.puntuar( p, r, null );
      igual( original.nota, r.nota, a + ": nota del prototipo sin compuerta" );
      for ( Criterio c : original.criterios ) {
        Criterio n = r.criterio( c.check );
        noNulo( n, a + ": " + c.check );
        cierto( Math.abs( c.ganado - n.ganado ) <= TOL, a + ": " + c.check + " gana " + n.ganado + " y el prototipo "
          + c.ganado );
        igual( c.aprobado, n.aprobado, a + ": passed de " + c.check );
      }
    }
  }

  // ================================================================ pistas

  public void pruebaPistasDelPrototipo() throws Exception {
    igual( Collections.emptyList(), pistas( "ana_orden_distinto", null ), "ana: correcta, sin pistas" );
    igual( Collections.emptyList(), pistas( "ines_tabla_ok", null ), "ines: correcta, sin pistas" );
    igual( Arrays.asList( "values" ), pistas( "bruno_agregado_mal", null ), "bruno: revisar importe_medio" );
    contiene( puntuado( "bruno_agregado_mal" ).pistas.get( 0 ), "«importe_medio»", "bruno: la columna" );
    igual( Arrays.asList( "values" ), pistas( "juan_tabla_max", null ), "juan" );
    igual( Arrays.asList( "missing_column" ), pistas( "carla_falta_columna", null ), "carla" );
    igual( "Falta la columna «ultima_venta»: revisá la lista de campos del step de salida.",
      puntuado( "carla_falta_columna" ).pistas.get( 0 ), "carla: la frase del prototipo" );
    igual( Arrays.asList( "blocked_step" ), pistas( "diego_javascript", null ), "diego" );
    contiene( puntuado( "diego_javascript" ).pistas.get( 0 ), "«Calcular importe» (ScriptValueMod)",
      "diego: el step bloqueado" );
    igual( Arrays.asList( "D5" ), pistas( "elena_sin_ordenar", null ), "elena: Group by sin Sort rows" );
    igual( Arrays.asList( "rename" ), pistas( "gabi_renombra", null ), "gabi: renombre, no columna faltante" );
    igual( "La columna «importe_medio» no está, pero «promedio» tiene los mismos valores: ¿la renombraste?",
      puntuado( "gabi_renombra" ).pistas.get( 0 ), "gabi: la frase del prototipo" );
    igual( Arrays.asList( "D3" ), pistas( "hugo_decimal", null ), "hugo: x100 y nada generico" );
    igual( Arrays.asList( "timeout" ), pistas( "fede_bucle", null ), "fede como la corrio el prototipo" );

    // fede como la corrige el diseño: D2 antes de ejecutar, sin casos
    ResultadoCorreccion fede = resultado( "fede_bucle" );
    fede.estado = ResultadoCorreccion.BLOQUEADA;
    fede.motivo = ResultadoCaso.CICLO;
    fede.casos.clear();
    Pack p = pack( VENTAS );
    igual( Arrays.asList( "D2" ), ids( CorrectorNota.pistas( p, fede, null ) ), "fede bloqueada por el ciclo" );
    CorrectorNota.puntuar( p, fede, null );
    igual( 1.0, fede.nota, "fede bloqueada: 1,0" );
    contiene( fede.criterio( "values" ).detalle, "hop circular", "fede: por que no corrio" );

    // las pistas de casos.json estan en las que salen
    for ( String a : PROTOTIPO ) {
      if ( a.startsWith( "fede" ) ) {
        continue;
      }
      ResultadoCorreccion r = resultado( a );
      JsonNode esp = CorrectorRecursos.esperado( r.pack, a + ".ktr" );
      List<String> salen = ids( CorrectorNota.pistas( pack( r.pack ), r, null ) );
      for ( JsonNode h : esp.path( "hints" ) ) {
        cierto( salen.contains( h.asText() ), a + ": falta la pista " + h.asText() + " en " + salen );
      }
    }
  }

  /** Las pistas miran los casos ocultos pero nunca citan sus valores (§6, «Dos lectores»). */
  public void pruebaPistasSinValoresOcultos() throws Exception {
    for ( String a : PROTOTIPO ) {
      ResultadoCorreccion r = puntuado( a );
      Set<String> visibles = new LinkedHashSet<String>();
      Set<String> ocultos = new LinkedHashSet<String>();
      for ( ResultadoCaso c : r.casos ) {
        if ( c.comparacion == null || c.comparacion.ejemplos == null ) {
          continue;
        }
        Comparacion.Ejemplos ej = c.comparacion.ejemplos;
        for ( List<Comparacion.Ejemplo> l : Arrays.asList( ej.distintas, ej.faltan, ej.sobran, ej.clavesRepetidas ) ) {
          for ( Comparacion.Ejemplo e : l ) {
            for ( String v : Arrays.asList( e.clave, e.esperado, e.real ) ) {
              if ( v != null && v.length() >= 3 ) {
                ( c.oculto ? ocultos : visibles ).add( v );
              }
            }
          }
        }
      }
      ocultos.removeAll( visibles );
      for ( String h : r.pistas ) {
        for ( String v : ocultos ) {
          noContiene( h, v, a + ": una pista cita un valor del caso oculto" );
        }
      }
    }
  }

  /** Patrones del §6 sobre resultados armados a mano: cada pista con su disparador, en los tres idiomas. */
  public void pruebaPatronesDePistas() throws Exception {
    Pack p = pack( VENTAS );
    // D3 solo si algun numero es el esperado x100; D4 con calculator_integer_first del analisis estatico
    ResultadoCorreccion r = resultado( "bruno_agregado_mal" );
    CorrectorNota.Estatico est = new CorrectorNota.Estatico();
    est.tipos.addAll( Arrays.asList( "CsvInput", "Calculator", "SortRows", "GroupBy", "TextFileOutput" ) );
    est.lint.add( "calculator_integer_first" );
    igual( Arrays.asList( "D4" ), ids( CorrectorNota.pistas( p, r, est ) ), "D4 reemplaza la pista generica" );

    // claves repetidas sin Group by: la generica con la clave; con Group by: D5
    ResultadoCorreccion e = resultado( "elena_sin_ordenar" );
    CorrectorNota.Estatico sinGb = new CorrectorNota.Estatico();
    sinGb.tipos.addAll( Arrays.asList( "CsvInput", "Unique" ) );
    List<CorrectorNota.Pista> ps = CorrectorNota.pistas( p, e, sinGb );
    igual( Arrays.asList( "duplicate_keys" ), ids( ps ), "repetidas sin Group by" );
    contiene( ps.get( 0 ).texto, "(producto)", "la columna clave" );

    // Merge join que pierde filas: D6 y las filas que faltan
    ResultadoCorreccion m = armado( p, "finished", "finished" );
    m.casos.get( 0 ).comparacion.filas.faltan = 2;
    CorrectorNota.Estatico conMerge = new CorrectorNota.Estatico();
    conMerge.tipos.addAll( Arrays.asList( "CsvInput", "SortRows", "MergeJoin", "TextFileOutput" ) );
    igual( Arrays.asList( "D6", "missing_rows" ), ids( CorrectorNota.pistas( p, m, conMerge ) ), "D6" );
    igual( Arrays.asList( "missing_rows" ), ids( CorrectorNota.pistas( p, m, null ) ), "sin Merge join: solo filas" );

    // mojibake, coma decimal (aviso del caso), columna que sobra, orden de columnas y filas de mas
    ResultadoCorreccion x = armado( p, "finished", "finished" );
    ResultadoCaso v = x.casos.get( 0 );
    v.comparacion.ejemplos.sobran.add( clave( "CafÃ©" ) );
    v.comparacion.filas.sobran = 1;
    v.comparacion.columnas.sobran.add( "comentario" );
    v.comparacion.columnas.ordenDistinto = true;
    v.avisos.add( new Aviso( Aviso.COMA_DECIMAL, "20,00" ) );
    igual( Arrays.asList( "extra_column", "extra_rows", "comma_decimal", "encoding" ),
      ids( CorrectorNota.pistas( p, x, null ) ), "formato (el orden de columnas no importa con ignore_column_order)" );
    p.comparar.ignorarOrdenColumnas = false;
    p.comparar.formatoEstricto = true;
    p.comparar.columnasDeMas = OpcionesComparar.EXTRA_RESTAR;
    List<CorrectorNota.Pista> estrictas = CorrectorNota.pistas( p, x, null );
    igual( Arrays.asList( "extra_column", "column_order", "extra_rows", "comma_decimal", "encoding" ),
      ids( estrictas ), "con orden de columnas y strict_format" );
    contiene( estrictas.get( 0 ).texto, "resta", "extra_columns penalize lo dice" );
    contiene( estrictas.get( 3 ).texto, "pide punto", "strict_format lo dice" );

    // como corrio cada caso: una pista por estado, sin repetir entre casos
    Pack q = pack( VENTAS );
    igual( Arrays.asList( "timeout" ), ids( CorrectorNota.pistas( q, armado( q, "timeout", "timeout" ), null ) ),
      "dos timeouts, una pista" );
    contiene( CorrectorNota.pistas( q, armado( q, "timeout", "finished" ), null ).get( 0 ).texto, "15 s",
      "el timeout del pack" );
    igual( Arrays.asList( "output_too_big", "too_many_rows" ), ids( CorrectorNota.pistas( q, armado( q,
      "output_too_big", "too_many_rows" ), null ) ), "topes" );
    igual( Arrays.asList( "init_failed", "finished_with_errors" ), ids( CorrectorNota.pistas( q, armado( q,
      "init_failed", "finished_with_errors" ), null ) ), "arranque y errores" );
    igual( Arrays.asList( "no_output_step" ), ids( CorrectorNota.pistas( q, armado( q, "no_output_step",
      "no_output_step" ), null ) ), "sin salida" );
    contiene( CorrectorNota.pistas( q, armado( q, "no_output_step", "finished" ), null ).get( 0 ).texto,
      "Text file output", "captura file: pide un Text file output" );
    Pack tabla = pack( TABLA );
    contiene( CorrectorNota.pistas( tabla, armado( tabla, "no_output_step" ), null ).get( 0 ).texto,
      "Table output", "captura query: pide un Table output" );

    // la entrega: no cargo, cortada, caida, y cada motivo de bloqueo
    ResultadoCorreccion n = new ResultadoCorreccion();
    n.estado = ResultadoCorreccion.NO_CARGO;
    igual( Arrays.asList( "load_failed" ), ids( CorrectorNota.pistas( q, n, null ) ), "load_failed" );
    n.estado = ResultadoCorreccion.MATADA;
    igual( Arrays.asList( "timeout" ), ids( CorrectorNota.pistas( q, n, null ) ), "killed_timeout" );
    n.estado = ResultadoCorreccion.CAIDA;
    igual( Arrays.asList( "crashed" ), ids( CorrectorNota.pistas( q, n, null ) ), "crashed" );
    Map<String, String> motivos = new LinkedHashMap<String, String>();
    motivos.put( ResultadoCaso.STEP_NO_PERMITIDO, "blocked_step" );
    motivos.put( ResultadoCaso.STEP_PELIGROSO, "blocked_step" );
    motivos.put( ResultadoCaso.STEP_DESCONOCIDO, "blocked_step" );
    motivos.put( ResultadoCaso.CONEXION_AJENA, "foreign_connection" );
    motivos.put( ResultadoCaso.ENTRADA_INESPERADA, "read_outside_pack" );
    motivos.put( ResultadoCaso.SALIDA_INESPERADA, "unexpected_output" );
    motivos.put( ResultadoCaso.NOMBRE_REPETIDO, "D1" );
    motivos.put( ResultadoCaso.CICLO, "D2" );
    motivos.put( ResultadoCaso.SQL_PROHIBIDO, "forbidden_sql" );
    motivos.put( ResultadoCaso.REESCRITURA_FALLIDA, "rewrite_failed" );
    igual( new TreeSet<String>( ResultadoCaso.MOTIVOS ), new TreeSet<String>( motivos.keySet() ),
      "una pista por cada motivo del §6" );
    for ( Map.Entry<String, String> mo : motivos.entrySet() ) {
      ResultadoCorreccion b = new ResultadoCorreccion();
      b.estado = ResultadoCorreccion.BLOQUEADA;
      b.motivo = mo.getKey();
      igual( Collections.singletonList( mo.getValue() ), ids( CorrectorNota.pistas( q, b, null ) ), mo.getKey() );
      // el mismo motivo desde un caso bloqueado
      ResultadoCorreccion bc = armado( q, "blocked" );
      bc.casos.get( 0 ).corrida.motivo = mo.getKey();
      bc.casos.get( 0 ).comparacion = null;
      igual( Collections.singletonList( mo.getValue() ), ids( CorrectorNota.pistas( q, bc, null ) ),
        mo.getKey() + " desde el caso" );
    }
    ResultadoCorreccion pel = new ResultadoCorreccion();
    pel.estado = ResultadoCorreccion.BLOQUEADA;
    pel.motivo = ResultadoCaso.STEP_PELIGROSO;
    pel.stepsBloqueados.add( new PasoBloqueado( "Ejecutar comando", "ExecProcess", PoliticaPack.PELIGROSO,
      ResultadoCaso.STEP_PELIGROSO ) );
    contiene( CorrectorNota.pistas( q, pel, null ).get( 0 ).texto, "«Ejecutar comando» (ExecProcess) ejecuta",
      "peligroso: con el step" );
    contiene( CorrectorNota.pistas( q, bloqueada( ResultadoCaso.ENTRADA_INESPERADA ), null ).get( 0 ).texto,
      "(ventas.csv)", "lee fuera: nombra los datos del ejercicio" );

    // ningun texto queda como clave, en ningun idioma
    for ( String idioma : Arrays.asList( "en", "es", "pt" ) ) {
      for ( ResultadoCorreccion caso : Arrays.asList( x, m, armado( q, "timeout", "output_too_big" ), armado( q,
        "init_failed", "no_output_step" ), armado( q, "too_many_rows", "finished_with_errors" ), pel ) ) {
        caso.idioma = idioma;
        for ( CorrectorNota.Pista pi : CorrectorNota.pistas( p, caso, conMerge ) ) {
          sinClaves( pi.texto, idioma + " " + pi.id );
        }
      }
      for ( String mo : ResultadoCaso.MOTIVOS ) {
        ResultadoCorreccion b = bloqueada( mo );
        b.idioma = idioma;
        for ( CorrectorNota.Pista pi : CorrectorNota.pistas( q, b, null ) ) {
          sinClaves( pi.texto, idioma + " " + mo );
        }
      }
    }
  }

  // ================================================================ compuertas, ponderacion, redondeo

  public void pruebaCompuertas() throws Exception {
    // cadena: max_pasos requiere sin_codigo, que requiere runs; con un caso que no termina caen las dos
    Pack p = pack( VENTAS );
    p.itemRubrica( "rule:sin_codigo" ).requiere.add( "runs" );
    p.itemRubrica( "rule:max_pasos" ).requiere.add( "rule:sin_codigo" );
    ResultadoCorreccion r = resultado( "ana_orden_distinto" );
    r.caso( "oculto" ).corrida.estado = ResultadoCaso.TIEMPO_AGOTADO;
    CorrectorNota.puntuar( p, r, null );
    criterio( r, "runs", 0.5, false );
    igual( "runs", r.criterio( "rule:sin_codigo" ).compuerta, "sin_codigo requiere runs" );
    igual( "rule:sin_codigo", r.criterio( "rule:max_pasos" ).compuerta, "max_pasos cae por la cadena" );
    criterio( r, "rule:max_pasos", 0, false );
    // runs 0,5 + columns 1 + row_count 0,5 + values 2 = 4 de 10
    igual( 4.0, r.nota, "un caso de dos: la mitad de los de salida y nada de las reglas" );

    // requires de un check que no esta en la rubrica: se calcula igual (bruno: values 12,5 %)
    Pack q = pack( VENTAS );
    q.rubrica.remove( q.itemRubrica( "values" ) );
    q.itemRubrica( "rule:max_pasos" ).requiere.add( "values" );
    ResultadoCorreccion b = CorrectorNota.puntuar( q, resultado( "bruno_agregado_mal" ), null );
    igual( "values", b.criterio( "rule:max_pasos" ).compuerta, "requires de un check fuera de la rubrica" );
    contiene( b.criterio( "rule:max_pasos" ).detalle, "«Valores correctos»", "con su etiqueta" );
    ResultadoCorreccion a = CorrectorNota.puntuar( q, resultado( "ana_orden_distinto" ), null );
    nulo( a.criterio( "rule:max_pasos" ).compuerta, "ana aprueba values aunque no puntue" );
    igual( 10.0, a.nota, "rubrica sin values: 6 de 6" );

    // un check desconocido en requires no se aprueba nunca; un ciclo de requires se corta con el puntaje crudo
    Pack c = pack( VENTAS );
    c.itemRubrica( "rule:max_pasos" ).requiere.add( "magia" );
    c.itemRubrica( "columns" ).requiere.add( "row_count" );
    c.itemRubrica( "row_count" ).requiere.add( "columns" );
    ResultadoCorreccion ana = CorrectorNota.puntuar( c, resultado( "ana_orden_distinto" ), null );
    igual( "magia", ana.criterio( "rule:max_pasos" ).compuerta, "check desconocido" );
    nulo( ana.criterio( "columns" ).compuerta, "ciclo: columns" );
    nulo( ana.criterio( "row_count" ).compuerta, "ciclo: row_count" );
    igual( 9.5, ana.nota, "solo max_pasos cae" );
    ResultadoCorreccion elena = CorrectorNota.puntuar( c, resultado( "elena_sin_ordenar" ), null );
    igual( "row_count", elena.criterio( "columns" ).compuerta, "elena: columns requiere row_count, que falla" );

    // la compuerta usa el estado final de lo requerido: rule:usa_group_by aprobada pero sin compuerta propia
    Pack d = pack( VENTAS );
    d.itemRubrica( "rule:max_pasos" ).requiere.add( "rule:usa_group_by" );
    ResultadoCorreccion diego = CorrectorNota.puntuar( d, resultado( "diego_javascript" ), null );
    igual( "rule:usa_group_by", diego.criterio( "rule:max_pasos" ).compuerta,
      "usa_group_by esta aprobada pero cae por su compuerta" );
    igual( 0.0, diego.nota, "diego sin nada" );
  }

  public void pruebaPonderacion() throws Exception {
    Pack p = pack( VENTAS );
    p.caso( "oculto" ).peso = 3;
    ResultadoCorreccion r = CorrectorNota.puntuar( p, resultado( "bruno_agregado_mal" ), null );
    criterio( r, "values", 0.25, false ); // (0,25 x 1 + 0 x 3) / 4 x 4
    igual( 6.3, r.nota, "1 + 2 + 1 + 0,25 + 1 + 0,5 + 0,5 = 6,25 -> 6,3" );

    Pack cero = pack( VENTAS );
    cero.caso( "visible" ).peso = 0;
    cero.caso( "oculto" ).peso = 0;
    ResultadoCorreccion rc = CorrectorNota.puntuar( cero, resultado( "bruno_agregado_mal" ), null );
    criterio( rc, "values", 0.5, false );

    Pack soloVisible = pack( VENTAS );
    soloVisible.caso( "oculto" ).peso = 0;
    criterio( CorrectorNota.puntuar( soloVisible, resultado( "bruno_agregado_mal" ), null ), "values", 1, false );

    // el pack de la copia para alumnos solo tiene el visible: el oculto del resultado no cuenta
    Pack alumnos = pack( VENTAS ).paraAlumnos();
    ResultadoCorreccion quique = armado( pack( VENTAS ), "finished", "finished" );
    quique.caso( "oculto" ).comparacion.puntajeValores = 0;
    quique.reglas.putAll( resultado( "ana_orden_distinto" ).reglas );
    igual( 10.0, CorrectorNota.puntuar( alumnos, quique, null ).nota, "la autocorreccion solo mira el visible" );
    igual( 1, quique.criterio( "values" ).porCaso.size(), "by_case solo del visible" );

    // un pack sin casos: los del resultado, con sus pesos
    Pack sin = pack( VENTAS );
    sin.casos.clear();
    ResultadoCorreccion b = CorrectorNota.puntuar( sin, resultado( "bruno_agregado_mal" ), null );
    criterio( b, "values", 0.5, false );
  }

  public void pruebaCasosQueNoCorren() throws Exception {
    Pack p = pack( VENTAS );
    for ( String e : ResultadoCaso.ESTADOS ) {
      ResultadoCorreccion r = CorrectorNota.puntuar( p, armado( p, e, e ), null );
      boolean termino = ResultadoCaso.TERMINO.equals( e );
      boolean conErrores = ResultadoCaso.TERMINO_CON_ERRORES.equals( e );
      criterio( r, "runs", termino ? 1 : 0, termino );
      double salida = termino || conErrores ? 1 : 0;
      criterio( r, "columns", 2 * salida, salida == 1 );
      criterio( r, "row_count", salida, salida == 1 );
      criterio( r, "values", 4 * salida, salida == 1 );
      String est = Rotulos.de( "corrector", "es" ).s( "caso." + e );
      if ( ResultadoCaso.BLOQUEADO.equals( e ) ) {
        igual( "No se ejecutó: " + est, r.criterio( "values" ).detalle, e + ": ningun caso corrio" );
      } else if ( !termino && !conErrores ) {
        contiene( r.criterio( "values" ).detalle, "visible: " + est, e + ": el detalle dice el estado" );
      }
    }
    // runs exige 0 errores
    ResultadoCorreccion err = armado( p, "finished", "finished" );
    err.caso( "visible" ).corrida.errores = 2L;
    CorrectorNota.puntuar( p, err, null );
    criterio( err, "runs", 0.5, false );
    contiene( err.criterio( "runs" ).detalle, "visible: terminó", "runs con errores" );
    criterio( err, "values", 4, true );

    // un caso que falta en el resultado vale 0 (el worker murio a mitad)
    ResultadoCorreccion falta = armado( p, "finished", "finished" );
    falta.casos.remove( 1 );
    CorrectorNota.puntuar( p, falta, null );
    criterio( falta, "values", 2, false );
    igual( "visible 100%, oculto: no corrió", falta.criterio( "values" ).detalle, "el que falta" );

    // el parcial de un worker muerto: reglas si, salida no, compuertas cerradas
    ResultadoCorreccion parcial = resultado( "bruno_agregado_mal" );
    parcial.estado = ResultadoCorreccion.CAIDA;
    for ( ResultadoCaso c : parcial.casos ) {
      c.corrida = new ResultadoCaso.Corrida();
      c.comparacion = null;
    }
    CorrectorNota.puntuar( p, parcial, null );
    igual( 1.0, parcial.nota, "crashed: sin_codigo y max_pasos" );
    contiene( parcial.criterio( "values" ).detalle, "interrumpida", "por que no corrio" );

    // load_failed: nada evaluado, 0
    ResultadoCorreccion roto = new ResultadoCorreccion();
    roto.estado = ResultadoCorreccion.NO_CARGO;
    CorrectorNota.analizar( p, null, null, roto );
    CorrectorNota.puntuar( p, roto, null );
    igual( 0.0, roto.nota, "load_failed" );
    falso( roto.reglas.get( "usa_group_by" ).evaluada, "sin transformacion la regla no se evalua" );
    contiene( roto.criterio( "rule:sin_codigo" ).detalle, "No evaluada", "detalle de la regla no evaluada" );
  }

  public void pruebaColumnasDeMas() throws Exception {
    Pack p = pack( VENTAS );
    ResultadoCorreccion r = armado( p, "finished", "finished" );
    for ( ResultadoCaso c : r.casos ) {
      c.comparacion.columnas.sobran.addAll( Arrays.asList( "comentario", "promedio" ) );
      c.comparacion.columnas.faltan.add( "importe_medio" );
      c.comparacion.columnas.renombres.add( new Comparacion.Renombre( "importe_medio", "promedio", 1.0 ) );
      c.comparacion.puntajeColumnas = 5.0 / 6;
    }
    CorrectorNota.puntuar( p, r, null );
    criterio( r, "columns", 1.67, false ); // warn: el columns_score de la comparacion
    p.comparar.columnasDeMas = OpcionesComparar.EXTRA_RESTAR;
    CorrectorNota.puntuar( p, r, null );
    criterio( r, "columns", 1.43, false ); // penalize: 5 / (6 + 1); el renombre no resta dos veces
    igual( Arrays.asList( "rename", "extra_column" ), ids( CorrectorNota.pistas( p, r, null ) ),
      "renombre y la que sobra de verdad" );
  }

  public void pruebaRedondeo() throws Exception {
    igual( 9.7, CorrectorNota.redondear( 29.0 / 3, 1 ), "9,666 -> 9,7" );
    igual( 7.3, CorrectorNota.redondear( 7.25, 1 ), "mitad para arriba" );
    igual( 0.1, CorrectorNota.redondear( 0.05, 1 ), "0,05 -> 0,1" );
    igual( 2.68, CorrectorNota.redondear( 2.675, 2 ), "2,675 -> 2,68 sin el error del double" );
    igual( 6.3, CorrectorNota.redondear( 0.1 + 0.2 + 5.95, 1 ), "ruido del double" );
    igual( 0.0, CorrectorNota.redondear( Double.NaN, 1 ), "NaN" );

    Pack p = pack( VENTAS );
    p.escala = 100;
    ResultadoCorreccion cien = CorrectorNota.puntuar( p, resultado( "bruno_agregado_mal" ), null );
    igual( 65.0, cien.nota, "escala 100" );
    igual( 100.0, cien.escala, "la escala del pack pasa al resultado" );
    p.escala = 5;
    igual( 4.8, CorrectorNota.puntuar( p, resultado( "carla_falta_columna" ), null ).nota, "escala 5: 4,83" );
    Pack vacio = pack( VENTAS );
    vacio.rubrica.clear();
    ResultadoCorreccion r = CorrectorNota.puntuar( vacio, resultado( "ana_orden_distinto" ), null );
    igual( 0.0, r.nota, "rubrica vacia" );
    igual( 0, r.criterios.size(), "sin criterios" );
  }

  /** puntuar escribe el contrato del §6: ida y vuelta, gated_by, by_case, hints; paraAlumno los conserva. */
  public void pruebaJson() throws Exception {
    ResultadoCorreccion r = puntuado( "diego_javascript" );
    String j = CorrectorJson.escribir( r, true );
    contiene( j, "\"gated_by\" : \"runs\"", "gated_by en el JSON" );
    contiene( j, "\"hints\" : [", "hints en el JSON" );
    contiene( j, "\"by_case\"", "by_case en el JSON" );
    ResultadoCorreccion leido = CorrectorJson.leer( j, ResultadoCorreccion.class );
    igual( r.nota, leido.nota, "nota" );
    igual( r.pistas, leido.pistas, "pistas" );
    igual( "runs", leido.criterio( "rule:usa_group_by" ).compuerta, "gated_by leido" );
    igual( j, CorrectorJson.escribir( leido, true ), "ida y vuelta estable" );

    ResultadoCorreccion b = puntuado( "bruno_agregado_mal" );
    ResultadoCorreccion alumno = b.paraAlumno();
    igual( b.pistas, alumno.pistas, "la devolucion del alumno lleva las pistas" );
    igual( b.nota, alumno.nota, "y la nota" );
    igual( b.criterios.size(), alumno.criterios.size(), "y los criterios" );
    // puntuar dos veces da lo mismo
    ResultadoCorreccion otra = CorrectorNota.puntuar( pack( VENTAS ), CorrectorJson.copia( b,
      ResultadoCorreccion.class ), null );
    igual( CorrectorJson.escribir( b, false ), CorrectorJson.escribir( otra, false ), "idempotente" );
    igual( "es", b.idioma, "idioma del pack" );
    igual( "Ejercicio 3 - Ventas por producto", b.tituloPack, "titulo" );

    ResultadoCorreccion en = resultado( "bruno_agregado_mal" );
    en.idioma = "en_US";
    CorrectorNota.puntuar( pack( VENTAS ), en, null );
    igual( "en", en.idioma, "idioma normalizado" );
    igual( "Correct values", en.criterio( "values" ).etiqueta, "etiqueta en ingles" );
    contiene( en.pistas.get( 0 ), "Check how you compute", "pista en ingles" );
  }

  // ================================================================ .md minimo

  public void pruebaMarkdown() throws Exception {
    ResultadoCorreccion b = puntuado( "bruno_agregado_mal" );
    b.caso( "visible" ).corrida.log = "LOG-SECRETO";
    String docente = CorrectorNota.markdown( b, false );
    contiene( docente, "# Corrección: Ejercicio 3 - Ventas por producto", "titulo" );
    contiene( docente, "**Nota: 6,5 / 10**", "nota con coma decimal" );
    contiene( docente, "| ✘ Valores correctos | 0,5 / 4 | visible 25%, oculto 0% |", "renglon de values" );
    contiene( docente, "## Pistas", "pistas" );
    contiene( docente, "Revisá cómo calculás «importe\\_medio»", "pista escapada" );
    contiene( docente, "| Café | importe\\_medio | 5.00 | 7.50 |", "ejemplo del visible" );
    contiene( docente, "Jugo", "el docente ve los ejemplos del oculto" );
    contiene( docente, "Filas: 4 esperadas, 4 tuyas, 1 iguales, 3 distintas", "conteos" );
    noContiene( docente, "LOG-SECRETO", "el .md nunca lleva el log" );
    sinClaves( docente, "md docente" );

    String alumno = CorrectorNota.markdown( b, true );
    contiene( alumno, "Café", "el alumno ve el visible" );
    noContiene( alumno, "Jugo", "el alumno no ve los valores del oculto" );
    noContiene( alumno, "12.40", "ni sus numeros" );
    contiene( alumno, "Caso oculto: solo se muestran los conteos.", "lo dice" );
    contiene( alumno, "Filas: 4 esperadas, 4 tuyas, 0 iguales, 4 distintas", "conteos del oculto" );
    noContiene( alumno, "LOG-SECRETO", "sin log" );
    noNulo( b.caso( "oculto" ).comparacion.ejemplos, "markdown no cambia el resultado" );

    // escape de lo que viene de la entrega
    ResultadoCorreccion d = puntuado( "diego_javascript" );
    d.alumno = "x|y *z* <b>&";
    d.stepsBloqueados.get( 0 ).step = "Paso | raro\n# titulo";
    String md = CorrectorNota.markdown( d, true );
    contiene( md, "x\\|y \\*z\\* &lt;b&gt;&amp;", "nombre escapado" );
    contiene( md, "Paso \\| raro \\# titulo", "step escapado, sin salto de linea" );
    contiene( md, "**Estado:** bloqueada (step no permitido)", "estado y motivo" );
    contiene( md, "## Steps bloqueados", "tabla de bloqueados" );

    ResultadoCorreccion en = resultado( "carla_falta_columna" );
    en.idioma = "en";
    CorrectorNota.puntuar( pack( VENTAS ), en, null );
    String ingles = CorrectorNota.markdown( en, true );
    contiene( ingles, "**Grade: 9.7 / 10**", "en ingles, punto decimal" );
    contiene( ingles, "Missing columns", "columnas que faltan" );
    sinClaves( ingles, "md en" );
    ResultadoCorreccion pt = resultado( "elena_sin_ordenar" );
    pt.idioma = "pt";
    CorrectorNota.puntuar( pack( VENTAS ), pt, null );
    String portugues = CorrectorNota.markdown( pt, false );
    contiene( portugues, "Chaves repetidas", "en portugues" );
    sinClaves( portugues, "md pt" );
    igual( "", CorrectorNota.md( null ), "null" );
  }

  /** Los tres corrector_*.properties tienen las mismas claves. */
  public void pruebaTextos() throws Exception {
    Set<String> en = claves( "en" );
    cierto( en.size() > 100, "hay textos" );
    igual( en, claves( "es" ), "es tiene las mismas claves que en" );
    igual( en, claves( "pt" ), "pt tiene las mismas claves que en" );
  }

  // ================================================================ analisis estatico (Kettle, sin ejecutar)

  public void pruebaReglasEstaticas() throws Exception {
    EntornoKettle.iniciar();
    Pack p = pack( VENTAS );
    ResultadoCorreccion paula = analizado( p, "paula_correcta.ktr" );
    for ( String id : Arrays.asList( "usa_group_by", "sin_codigo", "max_pasos" ) ) {
      cierto( paula.reglas.get( id ).aprobada, "paula: " + id );
      cierto( paula.reglas.get( id ).evaluada, "paula: " + id + " evaluada" );
    }
    igual( "Encontrado: «Totales por producto»", paula.reglas.get( "usa_group_by" ).detalle, "must_use" );
    igual( "No usa ninguno", paula.reglas.get( "sin_codigo" ).detalle, "must_not_use" );
    igual( "5 steps (máximo 8)", paula.reglas.get( "max_pasos" ).detalle, "max_steps" );
    igual( "Usa un Group by (o Memory group by)", paula.reglas.get( "usa_group_by" ).etiqueta, "etiqueta del pack" );

    ResultadoCorreccion diego = analizado( p, "diego_javascript.ktr" );
    falso( diego.reglas.get( "sin_codigo" ).aprobada, "diego usa JavaScript" );
    igual( "Usa «Calcular importe» (ScriptValueMod)", diego.reglas.get( "sin_codigo" ).detalle, "con el step" );
    falso( analizado( p, "quique_dibuja_salida.ktr" ).reglas.get( "usa_group_by" ).aprobada, "quique sin Group by" );
    igual( "Ningún step de tipo GroupBy, MemoryGroupBy", analizado( p, "quique_dibuja_salida.ktr" ).reglas.get(
      "usa_group_by" ).detalle, "detalle sin Group by" );
    igual( "6 steps (máximo 8)", analizado( p, "t7_paso_suelto.ktr" ).reglas.get( "max_pasos" ).detalle,
      "el suelto cuenta (P20)" );
    cierto( analizado( p, "tomas_sin_salida.ktr" ).reglas.get( "usa_group_by" ).aprobada,
      "tomas: sin Text file output, alcanza con que este conectado" );
    ReglaEvaluada nico = analizado( p, "nico_ejecuta_proceso.ktr" ).reglas.get( "max_pasos" );
    cierto( nico.aprobada, "nico: 7 steps" );
    igual( "7 steps (máximo 8)", nico.detalle, "nico: cuenta los sueltos" );

    // un Group by colgado de la entrada no suma: tiene que llegar al step de salida
    TransMeta tm = trans( VENTAS, "paula_correcta.ktr" );
    StepMeta orden = tm.findStep( "Ordenar por producto" );
    StepMeta gb = tm.findStep( "Totales por producto" );
    StepMeta salida = tm.findStep( "Escribir totales" );
    tm.removeTransHop( tm.indexOfTransHop( tm.findTransHop( gb, salida ) ) );
    tm.addTransHop( new TransHopMeta( orden, salida ) );
    ResultadoCorreccion colgado = new ResultadoCorreccion();
    CorrectorNota.analizar( p, tm, null, colgado );
    falso( colgado.reglas.get( "usa_group_by" ).aprobada, "Group by colgado" );
    igual( "«Totales por producto» no llega al step de salida «Escribir totales»", colgado.reglas.get(
      "usa_group_by" ).detalle, "detalle del colgado" );
    // con el step de salida que diga quien llama
    ResultadoCorreccion otraSalida = new ResultadoCorreccion();
    CorrectorNota.analizar( p, tm, "Totales por producto", otraSalida );
    cierto( otraSalida.reglas.get( "usa_group_by" ).aprobada, "pasoSalida manda" );

    // con captura step y sin output.step: alcanza un hop habilitado; con todos deshabilitados no
    Pack sinSalida = pack( VENTAS );
    sinSalida.salida.step = null;
    sinSalida.salida.captura = Pack.Salida.CAPTURA_STEP;
    TransMeta t2 = trans( VENTAS, "paula_correcta.ktr" );
    StepMeta gb2 = t2.findStep( "Totales por producto" );
    ResultadoCorreccion conHop = new ResultadoCorreccion();
    CorrectorNota.analizar( sinSalida, t2, null, conHop );
    cierto( conHop.reglas.get( "usa_group_by" ).aprobada, "conectado" );
    for ( TransHopMeta h : t2.getTransHops() ) {
      if ( h.getFromStep() == gb2 || h.getToStep() == gb2 ) {
        h.setEnabled( false );
      }
    }
    ResultadoCorreccion sinHop = new ResultadoCorreccion();
    CorrectorNota.analizar( sinSalida, t2, null, sinHop );
    falso( sinHop.reglas.get( "usa_group_by" ).aprobada, "hops deshabilitados" );
    contiene( sinHop.reglas.get( "usa_group_by" ).detalle, "no tiene ningún hop habilitado", "detalle" );

    // error_handling (pack inline): sin manejo de errores falla; con el hop de errores habilitado pasa
    Pack in = pack( CorrectorRecursos.INLINE );
    ResultadoCorreccion sinErr = analizado( in, "paula_correcta.ktr" );
    falso( sinErr.reglas.get( "errores" ).aprobada, "sin manejo de errores" );
    igual( "Sin manejo de errores: «Leer ventas»", sinErr.reglas.get( "errores" ).detalle, "detalle" );
    TransMeta t3 = trans( VENTAS, "paula_correcta.ktr" );
    StepMeta leer = t3.findStep( "Leer ventas" );
    StepErrorMeta sem = new StepErrorMeta( t3, leer, t3.findStep( "Escribir totales" ) );
    sem.setEnabled( true );
    leer.setStepErrorMeta( sem );
    ResultadoCorreccion conErr = new ResultadoCorreccion();
    CorrectorNota.analizar( in, t3, null, conErr );
    cierto( conErr.reglas.get( "errores" ).aprobada, "con manejo de errores" );
    ResultadoCorreccion quiqueErr = analizado( in, "quique_dibuja_salida.ktr" );
    igual( "Ningún step de tipo CsvInput", quiqueErr.reglas.get( "errores" ).detalle, "sin el step no aprueba" );

    // tipos raros: regla sin valor, tipo desconocido, etiqueta por tipo sin label
    Pack raro = pack( VENTAS );
    raro.reglas.clear();
    Regla sinValor = new Regla( "tope", Regla.MAX_STEPS );
    Regla desconocida = new Regla( "x", "must_be_pretty" );
    Regla sinLabel = new Regla( "gb", Regla.DEBE_USAR, "GroupBy" );
    raro.reglas.addAll( Arrays.asList( sinValor, desconocida, sinLabel ) );
    ResultadoCorreccion rr = analizado( raro, "paula_correcta.ktr" );
    falso( rr.reglas.get( "tope" ).evaluada, "max_steps sin value" );
    falso( rr.reglas.get( "x" ).evaluada, "tipo desconocido" );
    igual( "Usa GroupBy", rr.reglas.get( "gb" ).etiqueta, "etiqueta por tipo" );
    CorrectorNota.Estatico est = CorrectorNota.analizar( p, trans( VENTAS, "paula_correcta.ktr" ), null,
      new ResultadoCorreccion() );
    igual( new LinkedHashSet<String>( Arrays.asList( "CsvInput", "Calculator", "SortRows", "GroupBy",
      "TextFileOutput" ) ), est.tipos, "tipos de steps" );
  }

  public void pruebaLint() throws Exception {
    EntornoKettle.iniciar();
    Pack p = pack( VENTAS );
    p.rubrica.add( new ItemRubrica( "lint:unsorted_input", 1 ) );
    p.rubrica.add( new ItemRubrica( "lint:Absolute_Path", 0.5 ) );
    p.rubrica.add( new ItemRubrica( "lint:missing_input_file", 0.5 ) );
    p.rubrica.add( new ItemRubrica( "lint:no_existe", 0.5 ) );
    Regla orden = new Regla( "orden", Regla.LINT );
    orden.reglaLint = "unsorted_input";
    orden.etiqueta = "Ordena antes de agrupar";
    p.reglas.add( orden );
    p.rubrica.add( new ItemRubrica( "rule:orden", 0.5, "runs" ) );

    ResultadoCorreccion elena = analizado( p, "elena_sin_ordenar.ktr" );
    ReglaEvaluada un = elena.lint.get( "unsorted_input" );
    noNulo( un, "lint:unsorted_input en r.lint" );
    falso( un.aprobada, "elena: unsorted_input" );
    cierto( un.evaluada, "evaluada" );
    contiene( un.detalle, "Totales por producto", "el mensaje del linter" );
    igual( "Entrada sin ordenar por la clave", un.etiqueta, "titulo de la regla" );
    falso( elena.reglas.get( "orden" ).aprobada, "la regla de tipo lint" );
    igual( "Ordena antes de agrupar", elena.reglas.get( "orden" ).etiqueta, "su etiqueta" );
    ReglaEvaluada env = elena.lint.get( "missing_input_file" );
    falso( env.evaluada, "regla env: no se usa" );
    contiene( env.detalle, "depende de la máquina", "y lo dice" );
    falso( elena.lint.get( "no_existe" ).evaluada, "regla que no existe" );
    cierto( elena.lint.containsKey( "Absolute_Path" ), "clave tal como esta en la rubrica" );

    ResultadoCorreccion paula = analizado( p, "paula_correcta.ktr" );
    cierto( paula.lint.get( "unsorted_input" ).aprobada, "paula ordena" );
    cierto( paula.lint.get( "Absolute_Path" ).aprobada, "paula con rutas relativas" );
    igual( "Sin hallazgos", paula.lint.get( "unsorted_input" ).detalle, "detalle" );
    ResultadoCorreccion ana = analizado( p, "ana_orden_distinto.ktr" );
    falso( ana.lint.get( "Absolute_Path" ).aprobada, "ana con C:\\Users\\ana" );

    // la nota con lint:*: paula corre todo bien (resultado de ana) y pierde solo las no evaluadas
    ResultadoCorreccion r = resultado( "ana_orden_distinto" );
    r.reglas.putAll( paula.reglas );
    r.lint.putAll( paula.lint );
    CorrectorNota.puntuar( p, r, null );
    criterio( r, "lint:unsorted_input", 1, true );
    criterio( r, "lint:missing_input_file", 0, false );
    contiene( r.criterio( "lint:missing_input_file" ).detalle, "No evaluada", "detalle" );
    igual( "Sin problemas de «Entrada sin ordenar por la clave» (linter)", r.criterio( "lint:unsorted_input" ).etiqueta,
      "etiqueta lint" );
    criterio( r, "rule:orden", 0.5, true );
    igual( 9.2, r.nota, "12 de 13: las dos no evaluadas no suman" );

    // los atributos pentaho-live-lint de la entrega no cuentan (honorSuppressions=false)
    TransMeta tm = trans( VENTAS, "elena_sin_ordenar.ktr" );
    StepMeta gb = tm.findStep( "Totales por producto" );
    gb.setAttribute( Supresiones.GRUPO, Supresiones.IGNORAR, "unsorted_input" );
    gb.setAttribute( Supresiones.GRUPO, Supresiones.MOTIVO, "ya viene ordenado" );
    tm.setAttribute( Supresiones.GRUPO, Supresiones.IGNORAR, Supresiones.TODAS );
    Map<String, ReglaEvaluada> m = CorrectorNota.evaluarLint( Collections.singleton( "unsorted_input" ), tm, "es" );
    falso( m.get( "unsorted_input" ).aprobada, "silenciada por el alumno: igual falla" );
    // control: con supresiones el linter la callaria
    Opciones op = new Opciones();
    op.rules = Collections.singleton( "unsorted_input" );
    op.entorno = new LintEntorno( tm );
    Resultado res = LintMotor.analizar( tm, op );
    for ( Hallazgo h : res.findings ) {
      if ( "unsorted_input".equals( h.rule ) ) {
        falla( "con honorSuppressions el hallazgo tendria que estar silenciado" );
      }
    }
    cierto( !res.suppressed.isEmpty(), "control: el linter si lo silencia" );

    // las reglas de las pistas: corren sin bases de datos; con una conexion no
    CorrectorNota.Estatico e = CorrectorNota.analizar( pack( VENTAS ), trans( VENTAS, "elena_sin_ordenar.ktr" ), null,
      new ResultadoCorreccion() );
    cierto( e.lintDePistas, "elena no usa bases" );
    cierto( e.lint.contains( "unsorted_input" ), "elena: unsorted_input para la pista D5" );
    CorrectorNota.Estatico ines = CorrectorNota.analizar( pack( TABLA ), trans( TABLA, "ines_tabla_ok.ktr" ), null,
      new ResultadoCorreccion() );
    falso( ines.lintDePistas, "ines escribe a una base: sin campos" );
    cierto( ines.lint.isEmpty(), "sin reglas de pistas" );
    cierto( CorrectorNota.usaBases( trans( TABLA, "ines_tabla_ok.ktr" ) ), "usaBases" );
    falso( CorrectorNota.usaBases( trans( VENTAS, "paula_correcta.ktr" ) ), "paula no" );
  }

  public void pruebaErroresSilenciosos() throws Exception {
    EntornoKettle.iniciar();
    CorrectorNota.Bloqueo pia = CorrectorNota.erroresSilenciosos( trans( VENTAS, "pia_nombre_repetido.ktr" ), "es" );
    noNulo( pia, "pia: D1" );
    igual( ResultadoCaso.NOMBRE_REPETIDO, pia.motivo, "D1 antes que el ciclo que deja" );
    contiene( pia.detalle, "Totales por producto", "el mensaje nombra el step" );
    CorrectorNota.Bloqueo fede = CorrectorNota.erroresSilenciosos( trans( VENTAS, "fede_bucle.ktr" ), "en" );
    noNulo( fede, "fede: D2" );
    igual( ResultadoCaso.CICLO, fede.motivo, "D2" );
    contiene( fede.detalle, "cycle", "en ingles" );
    nulo( CorrectorNota.erroresSilenciosos( trans( VENTAS, "paula_correcta.ktr" ), "es" ), "paula: nada" );
    nulo( CorrectorNota.erroresSilenciosos( trans( VENTAS, "t7_paso_suelto.ktr" ), "es" ), "t7: un suelto no es D1/D2" );
    nulo( CorrectorNota.erroresSilenciosos( null, "es" ), "null" );
  }

  /**
   * Las entregas que no llegan a comparar nada (bloqueadas, sin salida, desbocadas, rotas): analisis estatico sobre el
   * .ktr y el estado de casos.json dan su nota y sus pistas, sin ejecutar ninguna.
   */
  public void pruebaNotasSinEjecutar() throws Exception {
    EntornoKettle.iniciar();
    String[][] entregas = { { VENTAS, "diego_javascript.ktr" }, { VENTAS, "fede_bucle.ktr" },
      { VENTAS, "nico_ejecuta_proceso.ktr" }, { VENTAS, "oscar_step_desconocido.ktr" },
      { VENTAS, "pia_nombre_repetido.ktr" }, { VENTAS, "ulises_salida_en_campo.ktr" },
      { VENTAS, "walter_copia_companero.ktr" }, { VENTAS, "xime_lee_url.ktr" }, { VENTAS, "yago_entrada_en_campo.ktr" },
      { VENTAS, "tomas_sin_salida.ktr" }, { VENTAS, "t6_infinito.ktr" }, { VENTAS, "t5_xxe.ktr" },
      { TABLA, "kevin_otra_base.ktr" }, { TABLA, "lara_attach.ktr" } };
    Map<String, PasoBloqueado> bloqueados = new LinkedHashMap<String, PasoBloqueado>();
    bloqueados.put( "diego_javascript.ktr", new PasoBloqueado( "Calcular importe", "ScriptValueMod",
      PoliticaPack.CONDICIONAL, ResultadoCaso.STEP_NO_PERMITIDO ) );
    bloqueados.put( "nico_ejecuta_proceso.ktr", new PasoBloqueado( "Ejecutar comando", "ExecProcess",
      PoliticaPack.PELIGROSO, ResultadoCaso.STEP_PELIGROSO ) );
    bloqueados.put( "oscar_step_desconocido.ktr", new PasoBloqueado( "Paso raro", "PasoQueNoExiste",
      PoliticaPack.DESCONOCIDO, ResultadoCaso.STEP_DESCONOCIDO ) );
    for ( String[] e : entregas ) {
      String id = e[ 1 ];
      JsonNode esp = CorrectorRecursos.esperado( e[ 0 ], id );
      Pack p = pack( e[ 0 ] );
      ResultadoCorreccion r = new ResultadoCorreccion();
      r.alumno = id.replace( ".ktr", "" );
      r.archivo = id;
      r.estado = esp.path( "status" ).asText();
      r.motivo = esp.hasNonNull( "reason" ) ? esp.path( "reason" ).asText() : null;
      TransMeta tm = null;
      try {
        tm = trans( e[ 0 ], id );
      } catch ( Exception x ) {
        igual( ResultadoCorreccion.NO_CARGO, r.estado, id + ": solo t5 no carga" );
      }
      CorrectorNota.Estatico est = CorrectorNota.analizar( p, tm, null, r );
      if ( ResultadoCaso.NOMBRE_REPETIDO.equals( r.motivo ) || ResultadoCaso.CICLO.equals( r.motivo ) ) {
        igual( r.motivo, CorrectorNota.erroresSilenciosos( tm, "es" ).motivo, id + ": D1/D2 sin ejecutar" );
      }
      for ( Caso k : p.casos ) {
        String st = esp.path( "cases" ).path( k.id ).asText( null );
        if ( st != null ) {
          ResultadoCaso rc = new ResultadoCaso();
          rc.id = k.id;
          rc.oculto = k.oculto;
          rc.corrida.estado = st;
          r.casos.add( rc );
        }
      }
      if ( bloqueados.containsKey( id ) ) {
        r.stepsBloqueados.add( bloqueados.get( id ) );
      }
      CorrectorNota.puntuar( p, r, est );
      igual( esp.path( "grade" ).asDouble(), r.nota, id + ": nota" );
      List<String> salen = ids( CorrectorNota.pistas( p, r, est ) );
      for ( JsonNode h : esp.path( "hints" ) ) {
        cierto( salen.contains( h.asText() ), id + ": falta la pista " + h.asText() + " en " + salen );
      }
      cierto( !salen.isEmpty(), id + ": alguna pista dice por que" );
      for ( String h : r.pistas ) {
        sinClaves( h, id );
      }
    }
  }

  // ================================================================ ayudas

  private File copia() throws IOException {
    if ( copia == null ) {
      copia = CorrectorRecursos.copia();
    }
    return copia;
  }

  private TransMeta trans( String pack, String archivo ) throws Exception {
    File f = new File( new File( new File( copia(), "entregas" ), pack ), archivo );
    return new TransMeta( f.getAbsolutePath() );
  }

  private ResultadoCorreccion analizado( Pack p, String archivo ) throws Exception {
    ResultadoCorreccion r = new ResultadoCorreccion();
    r.idioma = "es";
    CorrectorNota.analizar( p, trans( VENTAS, archivo ), null, r );
    return r;
  }

  private static ResultadoCorreccion resultado( String alumno ) throws IOException {
    return CorrectorJson.leer( CorrectorRecursos.resultado( alumno ), ResultadoCorreccion.class ).completar();
  }

  private static ResultadoCorreccion puntuado( String alumno ) throws IOException {
    ResultadoCorreccion r = resultado( alumno );
    return CorrectorNota.puntuar( pack( r.pack ), r, null );
  }

  private static Pack pack( String id ) throws IOException {
    File f = CorrectorRecursos.pack( id );
    return CorrectorJson.leer( f.isDirectory() ? new File( f, "pack.json" ) : f, Pack.class ).completar();
  }

  private static List<String> pistas( String alumno, CorrectorNota.Estatico est ) throws IOException {
    ResultadoCorreccion r = resultado( alumno );
    return ids( CorrectorNota.pistas( pack( r.pack ), r, est ) );
  }

  private static List<String> ids( List<CorrectorNota.Pista> l ) {
    List<String> r = new ArrayList<String>();
    for ( CorrectorNota.Pista p : l ) {
      r.add( p.id );
    }
    return r;
  }

  /** Una entrega bloqueada por ese motivo, sin casos. */
  private static ResultadoCorreccion bloqueada( String motivo ) {
    ResultadoCorreccion b = new ResultadoCorreccion();
    b.estado = ResultadoCorreccion.BLOQUEADA;
    b.motivo = motivo;
    return b;
  }

  /** Un resultado perfecto para los casos del pack, con esos estados (en orden). */
  private static ResultadoCorreccion armado( Pack p, String... estados ) {
    ResultadoCorreccion r = new ResultadoCorreccion();
    r.pack = p.id;
    r.estado = ResultadoCorreccion.CORREGIDA;
    for ( int i = 0; i < estados.length && i < p.casos.size(); i++ ) {
      Caso k = p.casos.get( i );
      ResultadoCaso c = new ResultadoCaso();
      c.id = k.id;
      c.oculto = k.oculto;
      c.peso = k.peso;
      c.corrida.estado = estados[ i ];
      c.corrida.errores = 0L;
      Comparacion cmp = new Comparacion();
      cmp.modo = Comparacion.POR_CLAVE;
      cmp.columnas.esperadas.addAll( p.salida.nombresColumnas() );
      cmp.columnas.reales.addAll( p.salida.nombresColumnas() );
      cmp.filas.esperadas = 4;
      cmp.filas.reales = 4;
      cmp.filas.iguales = 4;
      cmp.puntajeColumnas = 1;
      cmp.cantidadFilasOk = true;
      cmp.puntajeValores = 1;
      c.comparacion = cmp;
      r.casos.add( c );
    }
    return r.completar();
  }

  private static Comparacion.Ejemplo clave( String clave ) {
    Comparacion.Ejemplo e = new Comparacion.Ejemplo();
    e.clave = clave;
    return e;
  }

  private static void criterio( ResultadoCorreccion r, String check, double ganado, boolean aprobado ) {
    Criterio c = r.criterio( check );
    noNulo( c, check );
    cierto( Math.abs( c.ganado - ganado ) <= TOL, check + ": se esperaba ganar " + ganado + " y gano " + c.ganado );
    igual( aprobado, c.aprobado, check + ": passed" );
  }

  private static void sinClaves( String texto, String donde ) {
    if ( texto != null && CLAVE_CRUDA.matcher( texto ).find() ) {
      falla( donde + ": queda una clave de Rotulos sin texto en «" + texto + "»" );
    }
  }

  private static Set<String> claves( String idioma ) throws IOException {
    Properties p = new Properties();
    String recurso = Rotulos.CARPETA + "corrector_" + idioma + ".properties";
    try ( InputStream in = PruebaCorrectorNota.class.getClassLoader().getResourceAsStream( recurso ) ) {
      noNulo( in, recurso );
      p.load( new InputStreamReader( in, StandardCharsets.UTF_8 ) );
    }
    return new TreeSet<String>( p.stringPropertyNames() );
  }
}
