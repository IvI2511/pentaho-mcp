package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.distinto;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noContiene;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.job.JobHopMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entries.special.JobEntrySpecial;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.steps.dummytrans.DummyTransMeta;

import io.github.ivi2511.pentahomcp.lint.Catalogo;
import io.github.ivi2511.pentahomcp.lint.LintJson;
import io.github.ivi2511.pentahomcp.lint.LintMotor;
import io.github.ivi2511.pentahomcp.lint.Opciones;
import io.github.ivi2511.pentahomcp.lint.Resultado;

/**
 * La integracion del linter (docs/linter-integracion.md §2, WP4): el recurso pentaho://lint/rules, validate con
 * los chequeos propios del motor (entradas ocultas, inalcanzables, hops deshabilitados o rotos) y lint_etl sobre
 * un ejemplo, sin Spoon. El motor en si lo prueba el arnes (tools/lint-corpus).
 *
 * @dueño calidad
 */
public class PruebaLint {

  // ---------------------------------------------------------------- recurso

  public void pruebaRecursoReglas() throws Exception {
    McpResources r = new McpResources();
    JsonNode lista = r.listar( null ).path( "resources" );
    JsonNode item = null;
    for ( JsonNode x : lista ) {
      if ( McpResources.LINT_REGLAS.equals( x.path( "uri" ).asText() ) ) {
        item = x;
      }
    }
    noNulo( item, "resources/list trae pentaho://lint/rules: " + lista );
    igual( "lint-rules", item.path( "name" ).asText(), "nombre" );
    igual( "application/json", item.path( "mimeType" ).asText(), "mimeType de la lista" );

    ObjectNode leido = r.leer( McpResources.LINT_REGLAS );
    noNulo( leido, "resources/read de pentaho://lint/rules" );
    JsonNode c = leido.path( "contents" ).path( 0 );
    igual( McpResources.LINT_REGLAS, c.path( "uri" ).asText(), "uri" );
    igual( "application/json", c.path( "mimeType" ).asText(), "mimeType del contenido" );
    String texto = c.path( "text" ).asText();
    igual( texto.getBytes( StandardCharsets.UTF_8 ).length, item.path( "size" ).asInt(), "size = bytes del texto" );

    JsonNode cat = McpHttpServer.JSON.readTree( texto );
    igual( LintMotor.VERSION_REGLAS, cat.path( "rules_version" ).asText(), "rules_version" );
    cierto( cat.path( "scoring" ).has( "error_gate" ), "formula del puntaje" );
    igual( Catalogo.todas().size(), cat.path( "rules" ).size(), "todas las reglas" );
    for ( JsonNode regla : cat.path( "rules" ) ) {
      String id = regla.path( "id" ).asText();
      for ( String l : new String[] { "en", "es", "pt" } ) {
        for ( String k : new String[] { "title", "why", "fix" } ) {
          String t = regla.path( l ).path( k ).asText();
          cierto( !t.isEmpty() && !t.equals( id + "." + k ), "texto " + l + "/" + k + " de " + id + ": " + t );
        }
      }
    }
    // la misma instancia lo cachea: la segunda lectura es identica
    igual( texto, r.leer( McpResources.LINT_REGLAS ).path( "contents" ).path( 0 ).path( "text" ).asText(), "cache" );
  }

  // ---------------------------------------------------------------- validate con el motor

  /**
   * Jobs: la entrada oculta (draw=N) sin hops ya no se marca; la dibujada sin hops si; el hop deshabilitado se
   * anota en la entrada de origen y lo que queda detras, como inalcanzable.
   */
  public void pruebaValidateJob() throws Exception {
    JobMeta jm = EntornoKettle.job( "10" );
    JobEntryCopy oculta = entradaSuelta( jm, "Oculta", 600 );
    oculta.setDrawn( false );
    entradaSuelta( jm, "Suelta", 700 );
    for ( int i = 0; i < jm.nrJobHops(); i++ ) {
      JobHopMeta h = jm.getJobHop( i );
      if ( "START".equals( h.getFromEntry().getName() ) ) {
        h.setEnabled( false );
      }
    }
    ObjectNode v = RunMotor.validar( jm, false, null, null );
    JsonNode ps = v.path( "by_step" );
    falso( ps.has( "Oculta" ), "la entrada oculta no se marca: " + ps );
    contiene( ps.path( "Suelta" ).toString(), "WARNING: Entry 'Suelta' has no hops", "entrada dibujada sin hops" );
    contiene( ps.path( "START" ).toString(), "WARNING: The hop START -> Generar constantes is disabled",
      "hop deshabilitado en la entrada de origen" );
    contiene( ps.path( "Generar constantes" ).toString(), "cannot be reached from START", "inalcanzable" );
    contiene( ps.path( "Success" ).toString(), "cannot be reached from START", "inalcanzable detras" );
    noContiene( ps.toString(), "set_output_mode", "nada de reparto en un job" );

    // sin START: (general), error
    JobMeta sinStart = new JobMeta();
    sinStart.setName( "sin start" );
    entradaSuelta( sinStart, "Una", 100 );
    ObjectNode v2 = RunMotor.validar( sinStart, false, null, null );
    contiene( v2.path( "by_step" ).path( "(general)" ).toString(), "ERROR: The job has no START entry",
      "job sin START: " + v2 );
    falso( v2.path( "valid" ).asBoolean( true ), "un job sin START no es valido" );
  }

  /** Transformaciones: hop roto (general, error) y step suelto sin repetir el aviso de Kettle. */
  public void pruebaValidateTrans() throws Exception {
    TransMeta tm = EntornoKettle.trans( "03" );
    int erroresAntes = RunMotor.validar( tm, false, null, null ).path( "errors" ).asInt();
    StepMeta suelto = new StepMeta( "Dummy", "Suelto", new DummyTransMeta() );
    suelto.setLocation( 100, 400 );
    tm.addStep( suelto );
    tm.addTransHop( new TransHopMeta( tm.findStep( "Leer ventas" ), null ) );
    ObjectNode v = RunMotor.validar( tm, false, null, null );
    JsonNode ps = v.path( "by_step" );
    contiene( ps.path( "(general)" ).toString(), "has an end that does not exist", "hop roto: " + ps );
    cierto( v.path( "errors" ).asInt() > erroresAntes, "el hop roto suma un error: " + v );
    noNulo( ps.get( "Suelto" ), "el step suelto se marca: " + ps );
    int conHops = 0;
    for ( JsonNode linea : ps.path( "Suelto" ) ) {
      cierto( linea.asText().startsWith( "WARNING: " ), "advertencia: " + linea );
      conHops += linea.asText().contains( "has no hops" ) ? 1 : 0;
    }
    cierto( conHops <= 1, "a lo sumo un aviso de step sin hops: " + ps.path( "Suelto" ) );
  }

  // ---------------------------------------------------------------- lint_etl sin Spoon

  /** Lo que hace lint_etl despues de tomar la copia en la UI: el motor con LintEntorno, en segundo plano. */
  public void pruebaLintEjemplo() throws Exception {
    TransMeta tm = EntornoKettle.trans( "03" );
    final TransMeta copia = (TransMeta) ReportTools.clonar( tm );
    final Opciones op = LintTools.opciones( McpHttpServer.JSON.readTree( "{\"language\":\"es\"}" ) );
    final LintEntorno env = new LintEntorno( copia );
    op.entorno = env;
    Resultado r = RunMotor.enSegundoPlano( () -> LintMotor.analizar( copia, op ), LintTools.MS_LINT, env::cancelar,
      "The lint" );
    ObjectNode o = LintJson.resultado( r );
    System.out.println( "  03: score " + r.score + " (" + r.grade + "), " + r.findings.size() + " hallazgos, "
      + r.notEvaluated.size() + " sin evaluar, " + r.ms + " ms" );
    cierto( r.score >= 0 && r.score <= 100, "puntaje 0-100: " + r.score );
    igual( "transformation", o.path( "kind" ).asText(), "kind" );
    igual( LintMotor.VERSION_REGLAS, o.path( "rules_version" ).asText(), "rules_version" );
    cierto( o.has( "findings" ) && o.has( "penalties" ) && o.has( "not_evaluated" ), "forma del JSON: " + o );
    for ( JsonNode n : o.path( "not_evaluated" ) ) {
      distinto( "error", n.path( "code" ).asText(), "ninguna regla revento: " + n );
    }
    // el ejemplo no tiene steps sin hops ni hops deshabilitados
    noContiene( o.path( "findings" ).toString(), "orphan_step", "sin huerfanos" );
  }

  private static JobEntryCopy entradaSuelta( JobMeta jm, String nombre, int x ) {
    JobEntryCopy c = new JobEntryCopy( new JobEntrySpecial( nombre, false, true ) );
    c.setLocation( x, 300 );
    c.setDrawn( true );
    jm.addJobEntry( c );
    return c;
  }
}
