package io.github.ivi2511.pentahomcp;

import static io.github.ivi2511.pentahomcp.Verificador.cierto;
import static io.github.ivi2511.pentahomcp.Verificador.contiene;
import static io.github.ivi2511.pentahomcp.Verificador.falso;
import static io.github.ivi2511.pentahomcp.Verificador.igual;
import static io.github.ivi2511.pentahomcp.Verificador.noNulo;
import static io.github.ivi2511.pentahomcp.Verificador.nulo;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import io.github.ivi2511.pentahomcp.lint.Catalogo;
import io.github.ivi2511.pentahomcp.lint.Hallazgo;
import io.github.ivi2511.pentahomcp.lint.LintJson;
import io.github.ivi2511.pentahomcp.lint.LintMotor;
import io.github.ivi2511.pentahomcp.lint.Opciones;
import io.github.ivi2511.pentahomcp.lint.Regla;
import io.github.ivi2511.pentahomcp.lint.Resultado;
import io.github.ivi2511.pentahomcp.lint.Supresiones;
import io.github.ivi2511.pentahomcp.lint.Textos;
import io.github.ivi2511.pentahomcp.lint.reglas.ReglasRobustez;

/**
 * La familia robustness del linter (docs/robustez-diseno.md §3), sin Spoon: sobre una carga frágil sintética
 * (src/test/resources/robustez/antes.ktr, nombres genéricos) aparecen las 17 reglas; sobre el patrón 06 de la skill
 * (la carga tolerante verificada con Kitchen) ninguna; la familia está apagada por defecto y nunca cambia el puntaje
 * general; declarar un descarte o silenciar la regla la calla. Solo corren reglas estáticas: nada se conecta.
 *
 * @dueño R2a
 */
public class PruebaLintRobustez {

  /** Las 17, en el orden del catálogo: si se suma o se quita una, esta lista y la guía 09 cambian juntas. */
  private static final String[] IDS = { "no_rejects_on_load", "no_rejects_on_conversion", "rejects_without_reason",
    "rejects_dead_end", "silent_filter_drop", "lookup_drops_rows", "unchecked_lookup", "inner_join_in_source",
    "no_required_check", "lenient_conversion", "fragile_split", "no_dedupe_before_load", "counter_as_surrogate_key",
    "insert_only_load", "fact_not_grouped", "lookup_before_load_finishes", "fixed_input_file" };

  public void pruebaCatalogo() throws Exception {
    igual( java.util.Arrays.asList( IDS ), Catalogo.idsRobustez(), "las 17 reglas de la familia, en orden" );
    for ( String id : IDS ) {
      Regla r = Catalogo.buscar( id );
      noNulo( r, "la regla " + id );
      igual( Regla.ROBUSTNESS, r.categoria(), "categoria de " + id );
      cierto( r.apagada() && !r.puntua(), id + " apagada por defecto y fuera del puntaje general" );
      falso( r.env() || r.necesitaCampos() || r.soloArchivo(), id + " es estatica: sin campos, archivo ni entorno" );
      cierto( r.paraTrans() && !r.paraJobs(), id + " solo para transformaciones" );
      for ( String l : Textos.IDIOMAS ) {
        Textos t = Textos.de( l );
        for ( String k : new String[] { ".title", ".why", ".fix", ".msg" } ) {
          cierto( t.claves().contains( id + k ), "texto " + l + " " + id + k );
        }
      }
    }
    // las variantes de mensaje existen en los tres idiomas
    for ( String k : new String[] { "no_rejects_on_load.msg.sqlite", "no_rejects_on_conversion.msg.input",
      "rejects_without_reason.msg.filter", "silent_filter_drop.msg.switch", "lookup_drops_rows.msg.merge_join" } ) {
      for ( String l : Textos.IDIOMAS ) {
        cierto( Textos.de( l ).claves().contains( k ), "texto " + l + " " + k );
      }
    }
  }

  /** La carga frágil: las 17 reglas, cada hallazgo con su receta y su ancla, y el sub-puntaje bajo. */
  public void pruebaAntes() throws Exception {
    TransMeta tm = cargar( new File( new File( EntornoKettle.recursos(), "robustez" ), "antes.ktr" ) );
    Resultado r = lint( tm, "{\"robustness\":true,\"language\":\"es\",\"rules\":" + idsJson() + "}" );
    ObjectNode o = LintJson.resultado( r );
    Set<String> vistas = new TreeSet<String>();
    for ( Hallazgo h : r.findings ) {
      vistas.add( h.rule );
      igual( Regla.ROBUSTNESS, h.category, "categoria de " + h.key );
      noNulo( h.evidence.get( "recipe" ), "receta de " + h.key );
      igual( h.step, h.evidence.get( "anchor" ), "ancla de " + h.key );
      falso( h.message == null || h.message.startsWith( h.rule + "." ), "mensaje traducido de " + h.key + ": "
        + h.message );
      cierto( h.howToFix.contains( "harden_etl recipes=[" ) || h.howToFix.contains( "09-robustez.md" ),
        "como se arregla " + h.key + " (harden_etl o la guia 09): " + h.howToFix );
      nulo( h.fix, "no va por apply_lint_fixes: " + h.key );
    }
    igual( new TreeSet<String>( java.util.Arrays.asList( IDS ) ), vistas, "las 17 reglas aparecen en la carga fragil" );
    // lo concreto de algunas
    igual( "Look up customer", hallazgo( r, "no_required_check", "Read sales" ).evidence.get( "target" ),
      "no_required_check llega a la busqueda" );
    igual( "Load dim_customer", hallazgo( r, "lookup_before_load_finishes", "Look up customer" ).evidence.get(
      "writer" ), "la busqueda lee lo que carga Load dim_customer" );
    igual( "customer_sk", hallazgo( r, "counter_as_surrogate_key", "Customer key" ).evidence.get( "field" ),
      "el contador es la clave" );
    igual( "Bad sales", hallazgo( r, "rejects_dead_end", "Load fact_sales" ).evidence.get( "target" ),
      "los rechazos terminan en un Dummy" );
    igual( "customer_sk, sale_date", hallazgo( r, "fact_not_grouped", "Load fact_sales" ).evidence.get( "keys" ),
      "claves del hecho" );
    igual( "amount", hallazgo( r, "lenient_conversion", "Convert amount" ).evidence.get( "fields" ),
      "conversion permisiva" );
    noNulo( hallazgo( r, "no_rejects_on_load", "Copy products" ), "Table output sin manejo de errores" );
    noNulo( hallazgo( r, "inner_join_in_source", "Read products" ), "JOIN interno en el origen" );
    // el puntaje general no cambia (las de la familia no restan) y el sub-puntaje si
    igual( 100, r.score, "puntaje general intacto" );
    noNulo( r.robustez, "sub-puntaje de robustez" );
    cierto( r.robustez.score <= 74, "la carga fragil queda en C o D: " + r.robustez.score );
    igual( r.robustez.score, o.path( "robustness" ).path( "score" ).asInt(), "robustness.score en el JSON" );
    cierto( o.path( "robustness" ).path( "penalties" ).size() >= 6, "al menos 6 reglas restan: " + o.path(
      "robustness" ) );
    System.out.println( "  antes: robustez " + r.robustez.score + " (" + r.robustez.grade + "), " + r.findings.size()
      + " hallazgos de " + vistas.size() + " reglas" );
  }

  /** El patrón 06 (la carga tolerante de la skill, verificada con Kitchen): robustez 100, ningún hallazgo. */
  public void pruebaPatron06() throws Exception {
    File repo = EntornoKettle.recursos().getAbsoluteFile().getParentFile().getParentFile().getParentFile()
      .getParentFile();
    File ktr = new File( repo, "claude/skills/pentaho-etl/patrones/06_carga_tolerante/06_carga_tolerante.ktr" );
    cierto( ktr.isFile(), "el patron 06 en " + ktr );
    TransMeta tm = cargar( ktr );
    Resultado r = lint( tm, "{\"robustness\":true,\"rules\":" + idsJson() + "}" );
    StringBuilder b = new StringBuilder();
    for ( Hallazgo h : r.findings ) {
      b.append( h ).append( '\n' );
    }
    igual( 0, r.findings.size(), "el patron 06 no tiene hallazgos de robustez:\n" + b );
    igual( 100, r.robustez.score, "robustez 100" );
    igual( 0, r.notEvaluated.size(), "todo evaluado: " + r.notEvaluated );
    // y sus descartes estan declarados
    igual( "duplicates", ReglasRobustez.declaracion( tm.findStep( "Remove duplicate customers" ) ), "declarado" );
    igual( "aggregate", ReglasRobustez.declaracion( tm.findStep( "Aggregate to grain" ) ), "declarado" );
  }

  /** Apagada por defecto: lint_etl sin robustness no la corre; con robustness, el puntaje general es el mismo. */
  public void pruebaApagadaPorDefecto() throws Exception {
    TransMeta tm = EntornoKettle.trans( "03" );
    Resultado sin = lint( tm, "{}" );
    nulo( sin.robustez, "sin robustness no hay sub-puntaje" );
    for ( Hallazgo h : sin.findings ) {
      falso( Regla.ROBUSTNESS.equals( h.category ), "ningun hallazgo de robustez sin pedirlo: " + h );
    }
    falso( LintJson.resultado( sin ).has( "robustness" ), "sin bloque robustness en el JSON" );
    Resultado con = lint( tm, "{\"robustness\":true}" );
    noNulo( con.robustez, "con robustness hay sub-puntaje" );
    igual( sin.score, con.score, "el puntaje general no cambia con robustness" );
    igual( sin.penalties.size(), con.penalties.size(), "ni sus penalidades" );
    // el esquema de lint_etl ofrece el argumento
    contiene( LintTools.opciones( McpHttpServer.JSON.readTree( "{\"robustness\":true}" ) ).robustez ? "si" : "no",
      "si", "lint_etl lee robustness" );
  }

  /** Declarar el descarte (atributo drops) o silenciar la regla la calla; silenciar no resta del sub-puntaje. */
  public void pruebaDeclaraciones() throws Exception {
    TransMeta tm = cargar( new File( new File( EntornoKettle.recursos(), "robustez" ), "antes.ktr" ) );
    Resultado antes = lint( tm, "{\"robustness\":true,\"rules\":" + idsJson() + "}" );
    noNulo( hallazgo( antes, "silent_filter_drop", "Valid date?" ), "el filtro sin rama falsa" );

    StepMeta filtro = tm.findStep( "Valid date?" );
    filtro.setAttribute( ReglasRobustez.GRUPO, ReglasRobustez.DROPS, "filter" );
    Resultado declarado = lint( tm, "{\"robustness\":true,\"rules\":" + idsJson() + "}" );
    nulo( hallazgo( declarado, "silent_filter_drop", "Valid date?" ), "declarado: ya no se marca" );
    cierto( declarado.robustez.score >= antes.robustez.score, "declarar no baja el sub-puntaje" );

    filtro.getAttributesMap().remove( ReglasRobustez.GRUPO );
    Supresiones.agregar( tm.findStep( "Look up supplier" ), "lookup_drops_rows", "orphans are not needed here" );
    Resultado silenciado = lint( tm, "{\"robustness\":true,\"rules\":" + idsJson() + "}" );
    nulo( hallazgo( silenciado, "lookup_drops_rows", "Look up supplier" ), "silenciado: fuera de findings" );
    boolean enSuppressed = false;
    for ( Hallazgo h : silenciado.suppressed ) {
      enSuppressed |= "lookup_drops_rows".equals( h.rule );
    }
    cierto( enSuppressed, "silenciado: en suppressed" );
    cierto( silenciado.robustez.score > antes.robustez.score || antes.robustez.score == 0,
      "silenciar sube el sub-puntaje: " + antes.robustez.score + " -> " + silenciado.robustez.score );
  }

  /**
   * La guía 09-robustez.md también es un recurso (pentaho://guide/robustness) y nombra las tres herramientas, el
   * patrón 06 y las 17 reglas: si se suma una regla, la guía tiene que decir qué hacer con ella.
   */
  public void pruebaGuia() throws Exception {
    McpResources r = new McpResources();
    contiene( r.listar( null ).toString(), "pentaho://guide/robustness", "resources/list trae la guia de robustez" );
    String guia = r.leer( "pentaho://guide/robustness" ).path( "contents" ).path( 0 ).path( "text" ).asText();
    for ( String n : new String[] { "lint_etl", "robustness", "harden_etl", "stress_test", "06_carga_tolerante",
      "motivo", "campos_error", "pentaho-live-robust", "suppress_lint" } ) {
      contiene( guia, n, "la guia nombra " + n );
    }
    for ( String id : IDS ) {
      contiene( guia, "`" + id + "`", "la guia dice que hacer con " + id );
    }
  }

  // ---------------------------------------------------------------- utilidades

  private static TransMeta cargar( File f ) throws Exception {
    EntornoKettle.iniciar();
    return new TransMeta( f.getAbsolutePath() );
  }

  private static Resultado lint( TransMeta tm, String json ) throws Exception {
    final TransMeta copia = (TransMeta) ReportTools.clonar( tm );
    final Opciones op = LintTools.opciones( McpHttpServer.JSON.readTree( json ) );
    final LintEntorno env = new LintEntorno( copia );
    op.entorno = env;
    Resultado r = RunMotor.enSegundoPlano( () -> LintMotor.analizar( copia, op ), LintTools.MS_LINT, env::cancelar,
      "The lint" );
    for ( JsonNode n : LintJson.resultado( r ).path( "not_evaluated" ) ) {
      falso( "error".equals( n.path( "code" ).asText() ), "ninguna regla revento: " + n );
    }
    return r;
  }

  private static Hallazgo hallazgo( Resultado r, String regla, String step ) {
    for ( Hallazgo h : r.findings ) {
      if ( regla.equals( h.rule ) && step.equals( h.step ) ) {
        return h;
      }
    }
    return null;
  }

  private static String idsJson() {
    Set<String> s = new LinkedHashSet<String>( java.util.Arrays.asList( IDS ) );
    StringBuilder b = new StringBuilder( "[" );
    for ( String id : s ) {
      b.append( b.length() == 1 ? "" : "," ).append( '"' ).append( id ).append( '"' );
    }
    return b.append( "]" ).toString();
  }

  @SuppressWarnings( "unused" )
  private static List<String> ids() {
    return Catalogo.idsRobustez();
  }
}
