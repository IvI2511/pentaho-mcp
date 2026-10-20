package io.github.ivi2511.pentahomcp.lint;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Forma JSON del resultado de lint_etl (§6) y del recurso pentaho://lint/rules. Claves en snake_case. */
public final class LintJson {

  private static final JsonNodeFactory F = JsonNodeFactory.instance;

  private LintJson() {
  }

  public static ObjectNode resultado( Resultado r ) {
    ObjectNode o = F.objectNode();
    if ( r.document != null ) {
      o.put( "document", r.document );
    }
    o.put( "name", r.name );
    o.put( "kind", r.kind );
    o.put( "rules_version", r.rulesVersion );
    o.put( "score", r.score );
    o.put( "grade", r.grade );
    ObjectNode c = o.putObject( "counts" );
    for ( Map.Entry<String, Integer> e : r.counts.entrySet() ) {
      c.put( e.getKey(), e.getValue() );
    }
    ArrayNode pen = o.putArray( "penalties" );
    for ( Penalidad p : r.penalties ) {
      pen.addObject().put( "rule", p.rule ).put( "severity", p.severity.codigo() ).put( "hits", p.hits )
        .put( "points", p.points );
    }
    if ( r.infoPointsRaw > r.infoPoints || r.gateApplied ) {
      ObjectNode s = o.putObject( "score_caps" );
      if ( r.infoPointsRaw > r.infoPoints ) {
        s.put( "info_points", r.infoPointsRaw ).put( "info_cap", Puntaje.TOPE_INFO );
      }
      if ( r.gateApplied ) {
        s.put( "error_gate", Puntaje.COMPUERTA_ERROR );
      }
    }
    o.set( "findings", hallazgos( r.findings ) );
    if ( r.hidden > 0 ) {
      o.put( "hidden_by_filters", r.hidden );
    }
    o.set( "suppressed", hallazgos( r.suppressed ) );
    if ( !r.kettleChecks.isEmpty() ) {
      o.set( "kettle_checks", hallazgos( r.kettleChecks ) );
    }
    ArrayNode ne = o.putArray( "not_evaluated" );
    for ( NoEvaluado n : r.notEvaluated ) {
      ObjectNode x = ne.addObject();
      x.put( "rule", n.rule );
      if ( n.step != null ) {
        x.put( "step", n.step );
      }
      if ( n.entry != null ) {
        x.put( "entry", n.entry );
      }
      x.put( "code", n.code );
      x.put( "reason", n.reason );
    }
    ObjectNode d = o.putObject( "deep" );
    d.put( "ran", r.deep.ran );
    if ( r.deep.reason != null ) {
      d.put( "reason", r.deep.reason );
    }
    if ( r.deep.ran ) {
      d.put( "ms", r.deep.ms ).put( "budget_s", r.deep.budgetMs / 1000 ).put( "probes", r.deep.probes );
      d.put( "raised", r.deep.raised ).put( "lowered", r.deep.lowered ).put( "unchanged", r.deep.unchanged )
        .put( "not_evaluated", r.deep.notEvaluated );
    }
    return o;
  }

  public static ArrayNode hallazgos( List<Hallazgo> l ) {
    ArrayNode a = F.arrayNode();
    for ( Hallazgo h : l ) {
      a.add( hallazgo( h ) );
    }
    return a;
  }

  public static ObjectNode hallazgo( Hallazgo h ) {
    ObjectNode o = F.objectNode();
    o.put( "key", h.key );
    o.put( "rule", h.rule );
    o.put( "severity", h.severity.codigo() );
    o.put( "category", h.category );
    if ( h.step != null ) {
      o.put( "step", h.step );
    }
    if ( h.entry != null ) {
      o.put( "entry", h.entry );
    }
    if ( h.object != null ) {
      o.put( "object", h.object );
    }
    if ( h.field != null && !h.field.isEmpty() ) {
      o.put( "field", h.field );
    }
    if ( h.attribute != null ) {
      o.put( "attribute", h.attribute );
    }
    o.put( "env", h.env );
    o.put( "message", h.message );
    o.put( "how_to_fix", h.howToFix );
    if ( !h.evidence.isEmpty() ) {
      ObjectNode e = o.putObject( "evidence" );
      for ( Map.Entry<String, String> x : h.evidence.entrySet() ) {
        e.put( x.getKey(), x.getValue() );
      }
    }
    if ( h.fix != null ) {
      o.set( "fix", arreglo( h.fix ) );
    }
    if ( h.deep != null ) {
      o.put( "deep", h.deep );
    }
    return o;
  }

  public static ObjectNode arreglo( Arreglo a ) {
    ObjectNode o = F.objectNode();
    o.put( "kind", a.kind );
    o.put( "auto", a.auto );
    o.put( "confirm", a.confirm );
    if ( a.needsValue ) {
      o.put( "needs_value", true );
    }
    o.put( "undoable", a.undoable );
    if ( a.preview != null ) {
      o.put( "preview", a.preview );
    }
    if ( !a.params.isEmpty() ) {
      ObjectNode p = o.putObject( "params" );
      for ( Map.Entry<String, String> x : a.params.entrySet() ) {
        p.put( x.getKey(), x.getValue() );
      }
    }
    return o;
  }

  /** pentaho://lint/rules: id, severidad, categoría, si está apagada y qué/por qué/arreglo en en/es/pt. */
  public static ObjectNode catalogo() {
    ObjectNode o = F.objectNode();
    o.put( "rules_version", LintMotor.VERSION_REGLAS );
    ObjectNode sc = o.putObject( "scoring" );
    sc.put( "weights", "error 25, warning 8, info 2" );
    sc.put( "per_rule_cap", "2 x the weight of the worst severity of the rule (50 / 16 / 4)" );
    sc.put( "info_cap", Puntaje.TOPE_INFO );
    sc.put( "error_gate", Puntaje.COMPUERTA_ERROR );
    sc.put( "grades", "A 90-100, B 75-89, C 50-74, D <50" );
    ArrayNode a = o.putArray( "rules" );
    for ( Regla r : Catalogo.todas() ) {
      ObjectNode x = a.addObject();
      x.put( "id", r.id() );
      x.put( "severity", r.severidad().codigo() );
      x.put( "category", r.categoria() );
      x.put( "applies_to", r.paraTrans() && r.paraJobs() ? "both" : r.paraTrans() ? "transformation" : "job" );
      x.put( "off_by_default", r.apagada() || r.id().equals( Catalogo.KETTLE_CHECK ) );
      x.put( "scored", r.puntua() );
      x.put( "env", r.env() );
      x.put( "needs_fields", r.necesitaCampos() );
      x.put( "file_mode", r.soloArchivo() );
      for ( String l : Textos.IDIOMAS ) {
        Textos t = Textos.de( l );
        ObjectNode tx = x.putObject( l );
        tx.put( "title", t.t( r.id() + ".title" ) );
        tx.put( "why", t.t( r.id() + ".why" ) );
        tx.put( "fix", t.t( r.id() + ".fix" ) );
      }
    }
    return o;
  }
}
