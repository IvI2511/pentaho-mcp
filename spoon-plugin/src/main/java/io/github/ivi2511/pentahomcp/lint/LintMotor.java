package io.github.ivi2511.pentahomcp.lint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.AttributesInterface;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.TransMeta;

/**
 * El motor del linter (docs/linter-diseno.md §5). Corre las reglas estáticas sobre una COPIA desligada del
 * documento (ReportTools.clonar), en un hilo de trabajo, sin diálogos; arma las keys, separa lo silenciado y lo
 * que no resta, y calcula el puntaje. El modo profundo (LintSondas, WP3) trabaja después sobre el Resultado.
 */
public final class LintMotor {

  /** Versión de los ids y de su significado: el corrector la congela (§9). */
  public static final String VERSION_REGLAS = "0.6.0";

  private LintMotor() {
  }

  public static Resultado analizar( AbstractMeta copia, Opciones op ) {
    if ( op == null || op.entorno == null ) {
      throw new IllegalArgumentException( "lint: Opciones.entorno is required" );
    }
    long t0 = System.currentTimeMillis();
    boolean trans = copia instanceof TransMeta;
    List<Regla> reglas = Catalogo.seleccionar( op.rules, op.kettleChecks );
    boolean cicloVisible = op.rules == null || op.rules.isEmpty() || contiene( op.rules, Catalogo.CYCLE );
    Ctx ctx = new Ctx( copia, op );
    Map<Hallazgo, Regla> deRegla = new LinkedHashMap<Hallazgo, Regla>();
    for ( Regla r : reglas ) {
      if ( trans ? !r.paraTrans() : !r.paraJobs() ) {
        continue;
      }
      ctx.regla( r );
      if ( op.entorno.cancelado() || op.entorno.msRestantes() <= 0 ) {
        ctx.noEvaluado( "cancelled", null, null, ctx.t( "ne.cancelled" ) );
        continue;
      }
      if ( r.necesitaCampos() && ctx.hayCiclo() ) {
        ctx.noEvaluado( "cycle", null, null, ctx.t( "ne.cycle" ) );
        continue;
      }
      if ( r.soloArchivo() && !ctx.archivoVigente() ) {
        ctx.crudo(); // deja el no evaluado con el motivo (sin archivo o con cambios sin guardar)
        continue;
      }
      List<Hallazgo> out = new ArrayList<Hallazgo>();
      try {
        r.revisar( ctx, out );
      } catch ( Throwable e ) { // StackOverflowError incluido: lo que haya salido hasta ahí vale
        ctx.noEvaluado( "error", null, null, ctx.t( "ne.error", "detail", Ctx.primeraLinea( e ) ) );
      }
      if ( r.id().equals( Catalogo.CYCLE ) ) {
        ctx.ciclo = !out.isEmpty();
        if ( !cicloVisible ) {
          continue;
        }
      }
      for ( Hallazgo h : out ) {
        completar( ctx, r, h );
        deRegla.put( h, r );
      }
    }
    List<Hallazgo> todos = new ArrayList<Hallazgo>( deRegla.keySet() );
    asignarClaves( todos );

    Resultado res = new Resultado();
    res.name = copia.getName();
    res.kind = trans ? "transformation" : "job";
    res.rulesVersion = VERSION_REGLAS;
    List<Hallazgo> cuentan = new ArrayList<Hallazgo>();
    List<Hallazgo> visibles = new ArrayList<Hallazgo>();
    for ( Hallazgo h : todos ) {
      Regla r = deRegla.get( h );
      if ( r.id().equals( Catalogo.KETTLE_CHECK ) ) {
        if ( pasaFiltro( h, op ) ) {
          res.kettleChecks.add( h );
        }
        continue;
      }
      if ( op.honorSuppressions && silenciado( copia, h ) ) {
        res.suppressed.add( h );
        continue;
      }
      visibles.add( h );
      if ( r.puntua() ) {
        cuentan.add( h );
      }
    }
    Puntaje.calcular( cuentan, res );
    res.counts.put( Sev.ERROR.codigo(), 0 );
    res.counts.put( Sev.WARNING.codigo(), 0 );
    res.counts.put( Sev.INFO.codigo(), 0 );
    for ( Hallazgo h : visibles ) {
      res.counts.put( h.severity.codigo(), res.counts.get( h.severity.codigo() ) + 1 );
      if ( pasaFiltro( h, op ) ) {
        res.findings.add( h );
      } else {
        res.hidden++;
      }
    }
    ordenar( res.findings );
    for ( NoEvaluado n : ctx.noEvaluados() ) {
      if ( op.steps == null || op.steps.isEmpty() || n.step == null || op.steps.contains( n.step ) ) {
        res.notEvaluated.add( n );
      }
    }
    res.deep.ran = false;
    if ( op.deep ) {
      res.deep.reason = ctx.t( "deep.not_run" );
    }
    res.ms = System.currentTimeMillis() - t0;
    return res;
  }

  private static boolean contiene( Set<String> ids, String id ) {
    for ( String x : ids ) {
      Regla r = Catalogo.buscar( x );
      if ( r != null && r.id().equals( id ) ) {
        return true;
      }
    }
    return false;
  }

  /** Lo que la regla no puso: categoría, env, el «cómo se arregla» genérico de la regla. */
  private static void completar( Ctx ctx, Regla r, Hallazgo h ) {
    if ( h.rule == null ) {
      h.rule = r.id();
    }
    if ( h.category == null ) {
      h.category = r.categoria();
    }
    h.env |= r.env();
    // los marcadores del texto genérico: dónde y la evidencia ({keys}, {file}…)
    List<Object> pares = new ArrayList<Object>();
    pares.addAll( java.util.Arrays.<Object>asList( "step", h.step, "entry", h.entry, "field", h.field ) );
    for ( Map.Entry<String, String> e : h.evidence.entrySet() ) {
      pares.add( e.getKey() );
      pares.add( e.getValue() );
    }
    if ( h.message == null ) {
      h.message = ctx.t( r.id() + ".msg", pares.toArray() );
    }
    if ( h.howToFix == null ) {
      h.howToFix = ctx.t( r.id() + ".fix", pares.toArray() );
    }
  }

  /** key = rule:ámbito:objeto:atributo[#n]; si dos quedan iguales, se numeran #1, #2… en el orden en que salieron. */
  static void asignarClaves( List<Hallazgo> hs ) {
    Map<String, List<Hallazgo>> grupos = new LinkedHashMap<String, List<Hallazgo>>();
    for ( Hallazgo h : hs ) {
      String k = h.clave();
      List<Hallazgo> g = grupos.get( k );
      if ( g == null ) {
        g = new ArrayList<Hallazgo>();
        grupos.put( k, g );
      }
      g.add( h );
    }
    Set<String> usadas = new HashSet<String>();
    for ( List<Hallazgo> g : grupos.values() ) {
      if ( g.size() == 1 ) {
        Hallazgo h = g.get( 0 );
        h.key = h.clave();
        usadas.add( h.key );
        continue;
      }
      int base = g.get( 0 ).n;
      for ( int i = 0; i < g.size(); i++ ) {
        Hallazgo h = g.get( i );
        h.n = base > 0 ? base * 100 + i + 1 : i + 1; // raro: la regla ya había numerado y aun así chocan
        h.key = h.clave();
        usadas.add( h.key );
      }
    }
  }

  /** Silenciado en el documento o en el step/entrada (en un hop, el de origen). Anota el motivo. */
  static boolean silenciado( AbstractMeta doc, Hallazgo h ) {
    AttributesInterface dueno = null;
    if ( doc instanceof TransMeta && h.step != null
      && ( Hallazgo.STEP.equals( h.scope ) || Hallazgo.HOP.equals( h.scope ) ) ) {
      dueno = ( (TransMeta) doc ).findStep( h.step );
    } else if ( doc instanceof JobMeta && h.entry != null
      && ( Hallazgo.ENTRY.equals( h.scope ) || Hallazgo.HOP.equals( h.scope ) ) ) {
      dueno = ( (JobMeta) doc ).findJobEntry( h.entry, h.entryNr, true );
    }
    if ( dueno != null && Supresiones.silencia( dueno, h.rule ) ) {
      h.evidencia( "suppressed_in", h.scope ).evidencia( "suppressed_reason", Supresiones.motivo( dueno ) );
      return true;
    }
    if ( Supresiones.silencia( doc, h.rule ) ) {
      h.evidencia( "suppressed_in", Hallazgo.DOC ).evidencia( "suppressed_reason", Supresiones.motivo( doc ) );
      return true;
    }
    return false;
  }

  /** min_severity y steps: lo que se muestra (el puntaje cuenta todo). */
  private static boolean pasaFiltro( Hallazgo h, Opciones op ) {
    if ( op.minSeverity != null && !h.severity.almenos( op.minSeverity ) ) {
      return false;
    }
    if ( op.steps != null && !op.steps.isEmpty() ) {
      return h.step != null && op.steps.contains( h.step );
    }
    return true;
  }

  /** Por severidad; dentro de cada una, en el orden del catálogo y del documento (orden estable). */
  private static void ordenar( List<Hallazgo> l ) {
    Collections.sort( l, new Comparator<Hallazgo>() {
      @Override
      public int compare( Hallazgo a, Hallazgo b ) {
        return a.severity.ordinal() - b.severity.ordinal();
      }
    } );
  }
}
