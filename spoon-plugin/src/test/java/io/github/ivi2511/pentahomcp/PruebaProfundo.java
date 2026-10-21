package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.core.row.RowMeta;
import org.pentaho.di.core.row.value.ValueMetaString;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.lint.Hallazgo;
import io.github.ivi2511.pentahomcp.lint.LintJson;
import io.github.ivi2511.pentahomcp.lint.LintMotor;
import io.github.ivi2511.pentahomcp.lint.NoEvaluado;
import io.github.ivi2511.pentahomcp.lint.Opciones;
import io.github.ivi2511.pentahomcp.lint.Profundo;
import io.github.ivi2511.pentahomcp.lint.Resultado;
import io.github.ivi2511.pentahomcp.lint.Sev;

/**
 * El modo profundo del linter (docs/linter-diseno.md §3.3, WP3), sin Spoon, sobre las mutaciones del corpus
 * (tools/lint-corpus/mutar.py, rehechas acá sobre la copia de los ejemplos) y los samples de PDI que tienen
 * unsorted_input o empty_decimal_symbol: el desorden que cambia el resultado sube a error con la prueba diferencial,
 * la entrada ya ordenada baja a info, el decimal con separadores sube a error y los enteros bajan a info. Además la
 * lista blanca de solo lectura, el presupuesto y RunMotor.filas.
 *
 * @dueño calidad
 */
public class PruebaProfundo {

  // ---------------------------------------------------------------- orden

  /** m03: Group by sin Sort rows. Con un Sort rows delante da menos filas (los grupos se juntan): error. */
  public void pruebaGroupBySinOrdenar() throws Exception {
    TransMeta tm = mutacion( "03", "m03_prueba.ktr", x -> {
      x = sinStep( x, "Ordenar por producto" );
      x = sinHop( x, "Calcular importe", "Ordenar por producto" );
      x = sinHop( x, "Ordenar por producto", "Totales por producto" );
      return conHop( x, "Calcular importe", "Totales por producto" );
    } );
    Resultado est = estatico( tm );
    Resultado r = profundo( tm, "en", 60000 );
    Hallazgo h = uno( r, "unsorted_input" );
    mostrar( "m03", r, h );
    igual( Sev.WARNING, uno( est, "unsorted_input" ).severity, "estático: warning" );
    igual( Sev.ERROR, h.severity, "profundo: error con la prueba" );
    igual( Profundo.CONFIRMED, h.deep, "deep confirmed" );
    cierto( Integer.parseInt( h.evidence.get( "deep_rows" ) ) > Integer.parseInt( h.evidence.get( "deep_rows_sorted" ) ),
      "sin ordenar salen más grupos que ordenando: " + h.evidence );
    contiene( h.message, "Deep check:", "el mensaje dice lo que encontró" );
    contiene( h.message, "Totales por producto", "nombra el consumidor" );
    igual( "warning", h.evidence.get( "static_severity" ), "evidencia de la severidad estática" );
    cierto( h.env, "lo que cambia el modo profundo depende de los datos (env)" );
    cierto( r.score <= 74 && r.score < est.score, "el error baja el puntaje: " + est.score + " -> " + r.score );
    JsonNode d = LintJson.resultado( r ).path( "deep" );
    cierto( d.path( "ran" ).asBoolean() && d.path( "raised" ).asInt() == 1 && d.path( "probes" ).asInt() >= 3,
      "deep en el JSON: " + d );
    igual( "confirmed", LintJson.hallazgo( h ).path( "deep" ).asText(), "deep del hallazgo en el JSON" );
  }

  /** m04: Merge join sin ordenar ninguna entrada: clientes.csv viene ordenado (info), ventas no (error). */
  public void pruebaMergeJoinSinOrdenar() throws Exception {
    TransMeta tm = mutacion( "05", "m04_prueba.ktr", x -> {
      x = sinStep( sinStep( x, "Ordenar clientes" ), "Ordenar ventas" );
      x = sinHop( x, "Leer clientes", "Ordenar clientes" );
      x = sinHop( x, "Leer ventas", "Ordenar ventas" );
      x = sinHop( x, "Ordenar clientes", "Unir clientes y ventas" );
      x = sinHop( x, "Ordenar ventas", "Unir clientes y ventas" );
      x = conHop( conHop( x, "Leer clientes", "Unir clientes y ventas" ), "Leer ventas", "Unir clientes y ventas" );
      x = reemplazar( x, "<step1>Ordenar clientes</step1>", "<step1>Leer clientes</step1>" );
      return reemplazar( x, "<step2>Ordenar ventas</step2>", "<step2>Leer ventas</step2>" );
    } );
    Resultado r = profundo( tm, "es", 60000 );
    Hallazgo uno = clave( r, "unsorted_input:step:Unir clientes y ventas:step1" );
    Hallazgo dos = clave( r, "unsorted_input:step:Unir clientes y ventas:step2" );
    mostrar( "m04 step1", r, uno );
    mostrar( "m04 step2", r, dos );
    igual( Sev.ERROR, dos.severity, "ventas desordenadas por cliente_id: error" );
    igual( Profundo.CONFIRMED, dos.deep, "confirmado con la diferencial" );
    contiene( dos.message, "Revisión profunda:", "texto en castellano" );
    igual( Sev.INFO, uno.severity, "clientes.csv ya viene ordenado por cliente_id: info" );
    igual( Profundo.SORTED, uno.deep, "sorted" );
  }

  /** m17: el Sort rows ordena por otra clave: error con la prueba. */
  public void pruebaOrdenadoPorOtraClave() throws Exception {
    TransMeta tm = mutacion( "03", "m17_prueba.ktr", x -> enStep( x, "Ordenar por producto",
      s -> s.replaceFirst( "(<field>\\s*<name>)producto(</name>)", "$1venta_id$2" ) ) );
    Hallazgo h = uno( profundo( tm, "en", 60000 ), "unsorted_input" );
    igual( Sev.ERROR, h.severity, "m17: error" );
    igual( Profundo.CONFIRMED, h.deep, "m17: confirmed" );
  }

  /**
   * m16: el Sort rows de una entrada del Merge join corre en 2 copias: el orden no es determinista y Kettle además se
   * niega a correr el Merge join así. Queda warning, con el motivo.
   */
  public void pruebaCopiasAntesDelJoin() throws Exception {
    TransMeta tm = mutacion( "05", "m16_prueba.ktr", x -> enStep( x, "Ordenar clientes",
      s -> s.replaceFirst( "<copies>1</copies>", "<copies>2</copies>" ) ) );
    Resultado r = profundo( tm, "en", 60000 );
    Hallazgo h = uno( r, "unsorted_input" );
    mostrar( "m16", r, h );
    igual( Sev.WARNING, h.severity, "m16 queda warning" );
    noNulo( h.deep, "m16: la sonda dice algo" );
    falso( Profundo.SORTED.equals( h.deep ), "con 2 copias nunca baja a info" );
  }

  /** Samples de PDI con archivos ya ordenados: las dos entradas del Merge join bajan a info. */
  public void pruebaSampleOrdenadoBajaAInfo() throws Exception {
    TransMeta tm = sample( "Merge Join - Join 2 sorted data sets.ktr" );
    Resultado est = estatico( tm );
    Resultado r = profundo( tm, "en", 60000 );
    List<Hallazgo> hs = todos( r, "unsorted_input" );
    for ( Hallazgo h : hs ) {
      mostrar( "Merge Join sample", r, h );
    }
    igual( 2, hs.size(), "dos entradas" );
    for ( Hallazgo h : hs ) {
      igual( Sev.INFO, h.severity, "ya vienen ordenadas: info" );
      igual( Profundo.SORTED, h.deep, "sorted" );
    }
    cierto( r.score > est.score, "el puntaje sube: " + est.score + " -> " + r.score );
    igual( 2, r.deep.lowered, "dos bajadas" );
  }

  /** «XML file with substitutes»: las dos entradas desordenadas pero en el mismo orden: el mismo resultado, warning. */
  public void pruebaSampleMismoResultado() throws Exception {
    TransMeta tm = sample( "XML file with substitutes.ktr" );
    Resultado r = profundo( tm, "en", 60000 );
    for ( Hallazgo h : todos( r, "unsorted_input" ) ) {
      mostrar( "XML substitutes", r, h );
      igual( Sev.WARNING, h.severity, "anda por casualidad: queda warning" );
    }
  }

  // ---------------------------------------------------------------- decimal

  /** m01 (formato #.##) y m20 (sin formato): la columna trae 2.50, 1.80…: error, y propone '.'. */
  public void pruebaDecimalConSeparadores() throws Exception {
    TransMeta m01 = mutacion( "03", "m01_prueba.ktr", x -> enStep( x, "Leer ventas",
      s -> s.replaceFirst( "(?s)(<name>precio_unitario</name>.*?)<decimal>\\.</decimal>", "$1<decimal/>" ) ) );
    TransMeta m20 = mutacion( "03", "m20_prueba.ktr", x -> enStep( x, "Leer ventas", s -> s.replaceFirst(
      "(?s)(<name>precio_unitario</name>\\s*<type>Number</type>\\s*)<format>#\\.##</format>(.*?)<decimal>\\.</decimal>",
      "$1<format/>$2<decimal/>" ) ) );
    for ( TransMeta tm : Arrays.asList( m01, m20 ) ) {
      Resultado r = profundo( tm, "en", 60000 );
      Hallazgo h = uno( r, "empty_decimal_symbol" );
      mostrar( tm.getFilename(), r, h );
      igual( Sev.ERROR, h.severity, "separadores en la columna: error" );
      igual( Profundo.SEPARATORS, h.deep, "separators" );
      cierto( Integer.parseInt( h.evidence.get( "deep_with_separator" ) ) > 0, "cuenta los valores con separador" );
      igual( ".", h.fix.preview, "el símbolo que usan los datos" );
      cierto( h.evidence.containsKey( "deep_read_as" ) || h.evidence.containsKey( "deep_read_error" ),
        "dice cómo lo lee esta máquina: " + h.evidence );
      if ( h.evidence.containsKey( "deep_read_error" ) ) { // en es_ES con formato #.##: el renglón que trae el valor
        contiene( h.evidence.get( "deep_read_error" ), "2.50", "el error de Kettle nombra el valor" );
      }
    }
  }

  /** Un Number leído de una columna de enteros (cantidad: 3, 1, 2…): baja a info. */
  public void pruebaDecimalEnteros() throws Exception {
    // cantidad ya tiene <decimal/>: alcanza con pasarla de Integer a Number
    TransMeta tm = mutacion( "03", "decimal_enteros_prueba.ktr", x -> enStep( x, "Leer ventas", s -> s.replaceFirst(
      "(<name>cantidad</name>\\s*)<type>Integer</type>", "$1<type>Number</type>" ) ) );
    Resultado est = estatico( tm );
    Hallazgo e = clave( est, "empty_decimal_symbol:step:Leer ventas:cantidad" );
    igual( Sev.WARNING, e.severity, "estático: warning" );
    Resultado r = profundo( tm, "pt", 60000 );
    Hallazgo h = clave( r, "empty_decimal_symbol:step:Leer ventas:cantidad" );
    mostrar( "enteros", r, h );
    igual( Sev.INFO, h.severity, "todos enteros y la columna entera: info" );
    igual( Profundo.INTEGERS, h.deep, "integers" );
    contiene( h.message, "Revisão profunda:", "texto en portugués" );
  }

  /** Samples de PDI con ancho fijo (el prototipo leía 0 valores): con el parser del step se leen y son enteros. */
  public void pruebaSampleAnchoFijo() throws Exception {
    Resultado r = profundo( sample( "Textfile input - fixed length.ktr" ), "en", 60000 );
    List<Hallazgo> hs = todos( r, "empty_decimal_symbol" );
    igual( 2, hs.size(), "Semester y Age" );
    for ( Hallazgo h : hs ) {
      mostrar( "fixed length", r, h );
      cierto( Integer.parseInt( h.evidence.get( "deep_values" ) ) > 0, "lee valores con el parser del step" );
      igual( Sev.INFO, h.severity, "enteros: info" );
    }
  }

  // ---------------------------------------------------------------- seguridad y presupuesto

  /** Un step fuera de la lista blanca en el camino: la sonda no corre y el hallazgo queda warning (not_evaluated). */
  public void pruebaListaBlanca() throws Exception {
    TransMeta tm = mutacion( "03", "m03_secuencia_prueba.ktr", x -> {
      x = sinStep( x, "Ordenar por producto" );
      x = sinHop( x, "Calcular importe", "Ordenar por producto" );
      x = sinHop( x, "Ordenar por producto", "Totales por producto" );
      return conHop( x, "Calcular importe", "Totales por producto" );
    } );
    // un HTTP client entre la lectura y el cálculo: habla con la red, no está en la lista blanca
    StepMeta leer = tm.findStep( "Leer ventas" );
    StepMeta calc = tm.findStep( "Calcular importe" );
    org.pentaho.di.trans.steps.http.HTTPMeta http = new org.pentaho.di.trans.steps.http.HTTPMeta();
    http.setDefault();
    StepMeta s = new StepMeta( "HTTP", "Consultar API", http );
    s.setLocation( 200, 300 );
    tm.addStep( s );
    tm.removeTransHop( tm.indexOfTransHop( tm.findTransHop( leer, calc ) ) );
    tm.addTransHop( new org.pentaho.di.trans.TransHopMeta( leer, s ) );
    tm.addTransHop( new org.pentaho.di.trans.TransHopMeta( s, calc ) );
    falso( new LintEntorno( tm ).soloLectura( s ), "HTTP client no es de solo lectura" );
    Resultado r = profundo( tm, "en", 60000 );
    Hallazgo h = uno( r, "unsorted_input" );
    mostrar( "lista blanca", r, h );
    igual( Sev.WARNING, h.severity, "queda como el estático" );
    igual( Profundo.NOT_EVALUATED, h.deep, "not_evaluated" );
    NoEvaluado n = noEvaluado( r, "unsorted_input" );
    noNulo( n, "un not_evaluated con el motivo: " + r.notEvaluated );
    igual( "deep_not_read_only", n.code, "código" );
    contiene( n.reason, "Consultar API", "nombra el step que no es de solo lectura" );
    igual( 0, r.deep.probes, "no corrió nada" );
  }

  /** Text file input con archivos de líneas malas: escribe, así que no es de solo lectura. */
  public void pruebaEntradaQueEscribeErrores() throws Exception {
    TransMeta tm = sample( "Textfile input - fixed length.ktr" );
    StepMeta s = tm.findStep( "Text file input" );
    LintEntorno env = new LintEntorno( tm );
    cierto( env.soloLectura( s ), "la entrada tal cual es de solo lectura" );
    org.pentaho.di.trans.steps.fileinput.text.TextFileInputMeta m =
      (org.pentaho.di.trans.steps.fileinput.text.TextFileInputMeta) s.getStepMetaInterface();
    m.errorHandling.errorFilesDestinationDirectory = "${java.io.tmpdir}/errores";
    falso( env.soloLectura( s ), "con un directorio de líneas de error escribe" );
  }

  /**
   * Table input: corre su consulta de verdad, así que es de solo lectura solo con un SELECT/WITH que solo lee, sin
   * otra sentencia y con una conexión que no corre SQL al conectarse (también con la consulta resuelta).
   */
  public void pruebaTableInputQueEscribe() throws Exception {
    for ( String sql : new String[] { "SELECT producto, importe FROM ventas", "select * from ventas;",
      "-- ventas del mes\n/* sin filtro */ SELECT a FROM t WHERE fecha > ?", "WITH x AS (SELECT 1 AS a) SELECT a FROM x",
      "(SELECT a FROM x) UNION (SELECT a FROM y)", "SELECT deleted, updated_at FROM t" } ) {
      cierto( LintEntorno.consultaQueSoloLee( sql ), "solo lee: " + sql );
    }
    for ( String sql : new String[] { "WITH lote AS (DELETE FROM cola_eventos RETURNING *) SELECT * FROM lote",
      "EXEC dbo.usp_extraer_y_marcar @desde=?", "SELECT * INTO copia FROM ventas", "SELECT 1; DELETE FROM ventas",
      "SELECT nextval('seq')", "SELECT * FROM t FOR UPDATE", "CALL extraer()", "SET NOCOUNT ON; SELECT 1",
      "-- DELETE FROM t\nSELECT 1", "TRUNCATE t", "" } ) {
      falso( LintEntorno.consultaQueSoloLee( sql ), "no se sabe que solo lee: " + sql );
    }
    EntornoKettle.iniciar();
    TransMeta tm = new TransMeta();
    org.pentaho.di.core.database.DatabaseMeta db = new org.pentaho.di.core.database.DatabaseMeta( "pg", "POSTGRESQL",
      "Native", "localhost", "ventas", "5432", "u", "" );
    tm.addDatabase( db );
    TransOps.agregarStep( tm, "TableInput", "Leer", "<connection>pg</connection><sql>SELECT a FROM ventas</sql>",
      100, 100, null, null, null );
    StepMeta s = tm.findStep( "Leer" );
    org.pentaho.di.trans.steps.tableinput.TableInputMeta m =
      (org.pentaho.di.trans.steps.tableinput.TableInputMeta) s.getStepMetaInterface();
    LintEntorno env = new LintEntorno( tm );
    cierto( env.soloLectura( s ), "un SELECT en una base que no es de archivo" );
    m.setSQL( "WITH lote AS (DELETE FROM cola RETURNING *) SELECT * FROM lote" );
    falso( env.soloLectura( s ), "un CTE que borra" );
    m.setSQL( "SELECT a FROM ${TABLA}" );
    m.setVariableReplacementActive( true );
    tm.setVariable( "TABLA", "ventas" );
    cierto( env.soloLectura( s ), "con la variable resuelta, un SELECT" );
    tm.setVariable( "TABLA", "ventas; DELETE FROM ventas" );
    falso( env.soloLectura( s ), "la variable resuelta trae otra sentencia" );
    tm.setVariable( "TABLA", "ventas" );
    db.setConnectSQL( "UPDATE sesiones SET ultima = now()" );
    falso( env.soloLectura( s ), "la conexión corre SQL al conectarse" );
  }

  /**
   * Una sonda no escribe en las tablas de log, ni en las de la transformación ni en las de kettle.properties
   * (KETTLE_TRANS_LOG_DB/TABLE): con una base SQLite de log que no existe, Kettle la habría creado al escribir el
   * START.
   */
  public void pruebaSondaSinTablasDeLog() throws Exception {
    File base = new File( EntornoKettle.salida( "profundo" ), "log_de_sondas.sqlite" );
    base.delete();
    TransMeta c = (TransMeta) ReportTools.clonar( EntornoKettle.trans( "03" ) );
    c.addDatabase( new org.pentaho.di.core.database.DatabaseMeta( "logs", "SQLITE", "Native", "",
      base.getAbsolutePath(), "-1", "", "" ) );
    // tablas como recién cargadas (las del clon miran las conexiones y variables del original)
    c.setTransLogTable( org.pentaho.di.core.logging.TransLogTable.getDefault( c, c, c.getSteps() ) );
    c.setStepLogTable( org.pentaho.di.core.logging.StepLogTable.getDefault( c, c ) );
    c.setVariable( "KETTLE_TRANS_LOG_DB", "logs" ); // como un kettle.properties con log central
    c.setVariable( "KETTLE_TRANS_LOG_TABLE", "trans_log" );
    c.getStepLogTable().setConnectionName( "logs" ); // y una tabla propia
    c.getStepLogTable().setTableName( "step_log" );
    cierto( c.getTransLogTable().isDefined(), "precondición: tabla de log por las variables globales" );
    cierto( c.getStepLogTable().isDefined(), "precondición: tabla de log propia" );
    RunMotor.Filas f = RunMotor.filas( c, "Leer ventas", 5000, 30000 );
    igual( "finished", f.estado, "la sonda corre: " + f.error );
    cierto( f.completa, "y lee todo" );
    for ( org.pentaho.di.core.logging.LogTableInterface l : c.getLogTables() ) {
      falso( l.isDefined(), "sin tabla de log: " + l.getClass().getSimpleName() );
    }
    falso( base.exists(), "no se conectó a la base de log (Kettle la habría creado)" );
  }

  /**
   * El init que se pasa del plazo no se interrumpe: sigue en su hilo y, cuando termina, sus steps se liberan
   * (dispose cierra las conexiones y archivos que abrieron).
   */
  public void pruebaInitConPlazo() throws Exception {
    TransMeta c = (TransMeta) ReportTools.clonar( EntornoKettle.trans( "03" ) );
    RunMotor.recortar( c, c.findStep( "Leer ventas" ) );
    RunMotor.sinTablasDeLog( c );
    org.pentaho.di.trans.Trans t = new org.pentaho.di.trans.Trans( c );
    t.setPreview( true );
    RunMotor.prepararVariables( t, c );
    boolean vencio = false;
    try {
      RunMotor.prepararConPlazo( t, 1, "Preparing 'Leer ventas'" );
      RunMotor.liberarPasos( t ); // terminó en 1 ms: la sonda la liberaría al terminar
    } catch ( McpHttpServer.ToolError e ) {
      vencio = true;
      contiene( e.getMessage(), "did not finish", "vence con ToolError" );
    }
    long hasta = System.currentTimeMillis() + 15000;
    boolean liberados = false;
    while ( !liberados && System.currentTimeMillis() < hasta ) {
      List<org.pentaho.di.trans.step.StepMetaDataCombi> pasos = t.getSteps();
      liberados = pasos != null && !pasos.isEmpty();
      for ( int i = 0; liberados && i < pasos.size(); i++ ) {
        liberados = pasos.get( i ).data.getStatus() == org.pentaho.di.trans.step.BaseStepData.StepExecutionStatus
          .STATUS_DISPOSED;
      }
      RunMotor.dormir( 50 );
    }
    cierto( liberados, "los steps quedan liberados (" + ( vencio ? "init abandonado" : "init a tiempo" ) + ")" );
  }

  /** Un informe con "mostrar_valores": false: el modo profundo juzga igual, pero no cita los valores que leyó. */
  public void pruebaSinValores() throws Exception {
    TransMeta m01 = mutacion( "03", "m01_sin_valores_prueba.ktr", x -> enStep( x, "Leer ventas",
      s -> s.replaceFirst( "(?s)(<name>precio_unitario</name>.*?)<decimal>\\.</decimal>", "$1<decimal/>" ) ) );
    Opciones op = new Opciones();
    op.deep = true;
    op.lang = "en";
    op.deepBudgetMs = 60000;
    op.sondas = new LintSondas();
    op.ocultarValores = true;
    Hallazgo h = uno( analizar( m01, op ), "empty_decimal_symbol" );
    igual( Sev.ERROR, h.severity, "juzga igual: error" );
    igual( LintSondas.OCULTO, h.evidence.get( "deep_example" ), "sin el ejemplo: " + h.evidence );
    falso( h.evidence.containsKey( "deep_read_as" ) || h.evidence.containsKey( "deep_read_error" ),
      "ni cómo se lee el valor" );
    String profundo = h.message.substring( h.message.indexOf( "Deep check:" ) ); // el estático dice «2.50» de ejemplo
    cierto( !profundo.contains( "2.50" ) && !profundo.contains( "1.80" ) && profundo.contains( LintSondas.OCULTO ),
      "lo que agrega el modo profundo no cita valores: " + profundo );
    igual( ".", h.fix.preview, "el símbolo que usan los datos sí (no es un dato)" );
  }

  /** Sin presupuesto: nada corre, todo queda como el estático y not_evaluated lo dice. */
  public void pruebaPresupuesto() throws Exception {
    TransMeta tm = sample( "Merge Join - Join 2 sorted data sets.ktr" );
    Resultado r = profundo( tm, "en", 0 );
    for ( Hallazgo h : todos( r, "unsorted_input" ) ) {
      igual( Sev.WARNING, h.severity, "sin tiempo queda warning" );
      igual( Profundo.NOT_EVALUATED, h.deep, "not_evaluated" );
    }
    igual( "deep_budget", noEvaluado( r, "unsorted_input" ).code, "deep_budget" );
    igual( 0, r.deep.probes, "ninguna preview" );
  }

  /** Sin sondas (otro llamador), en un job o sin nada que mirar: Resultado.deep dice por qué. */
  public void pruebaCuandoNoCorre() throws Exception {
    TransMeta tm = EntornoKettle.trans( "03" );
    Opciones op = new Opciones();
    op.deep = true;
    Resultado r = analizar( tm, op );
    falso( r.deep.ran, "sin sondas no corre" );
    contiene( r.deep.reason, "not available", "y lo dice" );
    Resultado nada = profundo( tm, "en", 60000 );
    cierto( nada.deep.ran && nada.deep.probes == 0, "sin hallazgos con sonda: nada que correr" );
    contiene( nada.deep.reason, "nothing to check", "lo dice" );
    JobMeta jm = EntornoKettle.job( "10" );
    Opciones oj = new Opciones();
    oj.deep = true;
    oj.sondas = new LintSondas();
    Resultado rj = analizar( jm, oj );
    falso( rj.deep.ran, "en un job no corre" );
    contiene( rj.deep.reason, "only checks transformations", "lo dice" );
    ObjectNode j = LintJson.resultado( rj );
    falso( j.path( "deep" ).path( "ran" ).asBoolean( true ), "JSON" );
  }

  // ---------------------------------------------------------------- RunMotor.filas y utilidades

  /** filas: corta en max (row_limit, no completa) o lee todo (finished, completa), sin tocar preview. */
  public void pruebaFilas() throws Exception {
    TransMeta tm = EntornoKettle.trans( "03" );
    RunMotor.Filas pocas = RunMotor.filas( (TransMeta) ReportTools.clonar( tm ), "Leer ventas", 3, 30000 );
    igual( "row_limit", pocas.estado, "corta en 3" );
    falso( pocas.completa, "no completa" );
    igual( 3, pocas.filas.size(), "3 filas" );
    noNulo( pocas.meta, "RowMeta" );
    RunMotor.Filas todas = RunMotor.filas( (TransMeta) ReportTools.clonar( tm ), "Leer ventas", 5000, 30000 );
    igual( "finished", todas.estado, "termina sola" );
    cierto( todas.completa && todas.filas.size() > 3 && todas.errores == 0, "lee todo: " + todas.filas.size() );
    cierto( todas.meta.getValueMeta( todas.meta.indexOfValue( "precio_unitario" ) ).isNumber(), "objetos de Kettle" );
    // un step que no existe
    Verificador.lanza( McpHttpServer.ToolError.class, () -> RunMotor.filas( (TransMeta) ReportTools.clonar( tm ),
      "No existe", 10, 1000 ), "step inexistente" );
  }

  /** Detectar el desorden y adivinar el separador, sin Kettle corriendo. */
  public void pruebaUtilidades() throws Exception {
    RowMeta rm = new RowMeta();
    rm.addValueMeta( new ValueMetaString( "k" ) );
    List<Object[]> filas = new ArrayList<Object[]>();
    for ( String k : new String[] { "a", "a", "b", "c", "c" } ) {
      filas.add( new Object[] { k } );
    }
    int[] idx = { 0 };
    boolean[] asc = { true };
    nulo( LintSondas.desorden( rm, filas, idx, false, asc ), "agrupado" );
    nulo( LintSondas.desorden( rm, filas, idx, true, asc ), "ascendente" );
    filas.add( new Object[] { "a" } );
    LintSondas.Desorden d = LintSondas.desorden( rm, filas, idx, false, asc );
    cierto( d != null && d.grupo && d.fila == 6 && d.valor.equals( "[a]" ), "el grupo a vuelve en la fila 6" );
    filas.clear();
    for ( String k : new String[] { "c", "c", "b", "b" } ) {
      filas.add( new Object[] { k } );
    }
    nulo( LintSondas.desorden( rm, filas, idx, false, asc ), "descendente pero agrupado: sirve para agrupar" );
    d = LintSondas.desorden( rm, filas, idx, true, asc );
    cierto( d != null && !d.grupo && d.fila == 3 && "[c]".equals( d.anterior ), "para comparar no sirve" );
    nulo( LintSondas.desorden( rm, filas, idx, true, new boolean[] { false } ), "descendente pedido (Sorted merge)" );

    igual( ".", LintSondas.separadores( Arrays.asList( "2.50", "1.80" ) )[ 0 ], "punto" );
    igual( ",", LintSondas.separadores( Arrays.asList( "2,50", "1234,5" ) )[ 0 ], "coma" );
    String[] s = LintSondas.separadores( Arrays.asList( "1.234,56", "2,5" ) );
    igual( ",", s[ 0 ], "decimal coma con miles en punto" );
    igual( ".", s[ 1 ], "agrupación punto" );
    nulo( LintSondas.separadores( Arrays.asList( "2.5", "2,5" ) )[ 0 ], "se contradicen" );
    s = LintSondas.separadores( Arrays.asList( "1.234.567" ) );
    nulo( s[ 0 ], "solo miles: el decimal no se sabe" );
    igual( ".", s[ 1 ], "miles en punto" );
  }

  // ---------------------------------------------------------------- ayudas

  interface Cambio {
    String aplicar( String x );
  }

  /** Copia un ejemplo de la skill con un cambio en su XML (como mutar.py) en la carpeta de ejemplos de la prueba. */
  static TransMeta mutacion( String ejemplo, String nombre, Cambio c ) throws Exception {
    File origen = EntornoKettle.ejemplo( ejemplo );
    File destino = new File( origen.getParentFile(), nombre );
    EntornoKettle.escribir( destino, c.aplicar( EntornoKettle.leer( origen ) ) );
    EntornoKettle.iniciar();
    return new TransMeta( destino.getAbsolutePath() );
  }

  static TransMeta sample( String nombre ) throws Exception {
    EntornoKettle.iniciar();
    File f = new File( EntornoKettle.pdi(), "samples/transformations/" + nombre );
    if ( !f.isFile() ) {
      Verificador.saltear( "no está el sample " + f );
    }
    return new TransMeta( f.getAbsolutePath() );
  }

  static String sinStep( String x, String nombre ) {
    return quitar( x, "\\s*<step>\\s*<name>" + Pattern.quote( nombre ) + "</name>.*?</step>", nombre );
  }

  static String sinHop( String x, String a, String b ) {
    return quitar( x, "\\s*<hop>\\s*<from>" + Pattern.quote( a ) + "</from>\\s*<to>" + Pattern.quote( b ) + "</to>.*?</hop>",
      a + " -> " + b );
  }

  static String conHop( String x, String a, String b ) {
    return x.replaceFirst( "</order>", Matcher.quoteReplacement( "<hop><from>" + a + "</from><to>" + b
      + "</to><enabled>Y</enabled></hop></order>" ) );
  }

  static String enStep( String x, String nombre, Cambio c ) {
    Matcher m = Pattern.compile( "(?s)<step>\\s*<name>" + Pattern.quote( nombre ) + "</name>.*?</step>" ).matcher( x );
    if ( !m.find() ) {
      throw new IllegalStateException( "no está el step " + nombre );
    }
    String nuevo = c.aplicar( m.group() );
    if ( nuevo.equals( m.group() ) ) {
      throw new IllegalStateException( "el cambio no tocó el step " + nombre );
    }
    return x.substring( 0, m.start() ) + nuevo + x.substring( m.end() );
  }

  static String reemplazar( String x, String a, String b ) {
    if ( !x.contains( a ) ) {
      throw new IllegalStateException( "no está: " + a );
    }
    return x.replaceFirst( Pattern.quote( a ), Matcher.quoteReplacement( b ) );
  }

  private static String quitar( String x, String re, String que ) {
    Matcher m = Pattern.compile( "(?s)" + re ).matcher( x );
    if ( !m.find() ) {
      throw new IllegalStateException( "no está " + que );
    }
    return x.substring( 0, m.start() ) + x.substring( m.end() );
  }

  /** Como lint_etl con deep=true, después de tomar la copia en la UI. */
  static Resultado profundo( TransMeta tm, String lang, long presupuesto ) throws Exception {
    Opciones op = new Opciones();
    op.deep = true;
    op.lang = lang;
    op.deepBudgetMs = presupuesto;
    op.sondas = new LintSondas();
    return analizar( tm, op );
  }

  static Resultado estatico( TransMeta tm ) throws Exception {
    return analizar( tm, new Opciones() );
  }

  static Resultado analizar( org.pentaho.di.base.AbstractMeta m, final Opciones op ) throws Exception {
    final org.pentaho.di.base.AbstractMeta copia = ReportTools.clonar( m );
    final LintEntorno env = new LintEntorno( copia );
    op.entorno = env;
    return RunMotor.enSegundoPlano( () -> LintMotor.analizar( copia, op ), LintTools.MS_LINT + op.deepBudgetMs
      + LintTools.MS_CIERRE_PROFUNDO, env::cancelar, "The lint" );
  }

  static List<Hallazgo> todos( Resultado r, String regla ) {
    List<Hallazgo> l = new ArrayList<Hallazgo>();
    for ( Hallazgo h : r.findings ) {
      if ( h.rule.equals( regla ) ) {
        l.add( h );
      }
    }
    return l;
  }

  static Hallazgo uno( Resultado r, String regla ) {
    List<Hallazgo> l = todos( r, regla );
    igual( 1, l.size(), "un hallazgo de " + regla + ": " + r.findings );
    return l.get( 0 );
  }

  static Hallazgo clave( Resultado r, String key ) {
    for ( Hallazgo h : r.findings ) {
      if ( h.key.equals( key ) ) {
        return h;
      }
    }
    throw new AssertionError( "no está la key " + key + " en " + r.findings );
  }

  static NoEvaluado noEvaluado( Resultado r, String regla ) {
    for ( NoEvaluado n : r.notEvaluated ) {
      if ( regla.equals( n.rule ) ) {
        return n;
      }
    }
    return null;
  }

  static void mostrar( String que, Resultado r, Hallazgo h ) {
    System.out.println( "  " + que + ": " + h.severity.codigo() + " deep=" + h.deep + " score=" + r.score + " ("
      + r.deep.probes + " previews, " + r.deep.ms + " ms)\n    " + h.message + "\n    " + h.evidence );
  }
}
