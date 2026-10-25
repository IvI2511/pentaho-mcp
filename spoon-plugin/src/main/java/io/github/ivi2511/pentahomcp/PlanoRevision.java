package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.Plano.Destino;
import io.github.ivi2511.pentahomcp.Plano.Etapa;
import io.github.ivi2511.pentahomcp.Plano.Fuente;
import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * plan_review: revisión estática del plano, sin Spoon ni archivos (D1). Cada hallazgo trae qué falta, cómo arreglarlo
 * y, cuando la respuesta la tiene el usuario, la pregunta que Claude le hace en la entrevista («¿cuál es el grano?»,
 * «¿qué hacemos con un cliente que no existe?»). Los textos están en plano_*.properties (rev.&lt;código&gt;.msg/.fix/.ask).
 */
final class PlanoRevision {

  static final String ERROR = "error";
  static final String AVISO = "warning";
  static final String INFO = "info";

  /** Cargas que se pueden repetir sin duplicar filas. */
  static final Set<String> IDEMPOTENTES = new HashSet<String>( Arrays.asList( "truncate_insert", "upsert", "scd1",
    "scd2", "overwrite" ) );

  /** Los códigos de los hallazgos, en el orden en que se revisan (también los usa la guía 10-plano.md). */
  static final List<String> CODIGOS = Arrays.asList( "no_goal", "no_grain", "no_sources", "no_targets", "no_stages",
    "load_strategy_missing", "incremental_without_watermark", "source_without_format", "source_without_volume",
    "source_without_rejects", "source_not_read", "target_not_written", "non_idempotent_load", "upsert_without_key",
    "dimension_without_history", "lookup_without_key", "lookup_without_on_missing", "orphans_rejected_nowhere",
    "dedupe_without_key", "stage_without_input", "stage_dead_end", "rule_unused", "no_orchestration",
    "orchestration_without_failure_path", "risk_without_mitigation", "decision_without_why" );

  private PlanoRevision() {
  }

  /** Un hallazgo de la revisión. */
  static final class Hallazgo {
    final String codigo;
    final String severidad;
    /** Id de la fuente, destino, etapa... o null si es del plano entero. */
    final String donde;
    String mensaje;
    String arreglo;
    /** La pregunta para el usuario, o null si Claude lo puede arreglar solo. */
    String pregunta;

    Hallazgo( String codigo, String severidad, String donde ) {
      this.codigo = codigo;
      this.severidad = severidad;
      this.donde = donde;
    }
  }

  /** Revisa el plano; textos en el idioma pedido (o el del plano, o inglés). */
  static List<Hallazgo> revisar( Plano p, String idioma ) {
    Rotulos r = Rotulos.de( PlanoInforme.AREA, idioma != null ? idioma : p.idioma );
    List<Hallazgo> out = new ArrayList<Hallazgo>();
    if ( vacio( p.objetivo ) ) {
      agregar( out, r, "no_goal", AVISO, null );
    }
    boolean hayHechos = false;
    for ( Destino d : p.destinos ) {
      hayHechos |= "fact".equals( d.clase );
    }
    if ( vacio( p.grano ) ) {
      agregar( out, r, "no_grain", hayHechos ? ERROR : AVISO, null );
    }
    if ( p.fuentes.isEmpty() ) {
      agregar( out, r, "no_sources", ERROR, null );
    }
    if ( p.destinos.isEmpty() ) {
      agregar( out, r, "no_targets", ERROR, null );
    }
    if ( p.etapas.isEmpty() ) {
      agregar( out, r, "no_stages", AVISO, null );
    }
    if ( vacio( p.carga ) ) {
      agregar( out, r, "load_strategy_missing", INFO, null );
    } else if ( "incremental".equals( p.carga ) && vacio( p.incrementalPor ) ) {
      agregar( out, r, "incremental_without_watermark", AVISO, null );
    }

    for ( Fuente f : p.fuentes ) {
      if ( vacio( f.formato ) ) {
        agregar( out, r, "source_without_format", INFO, f.id, f.nombre );
      }
      if ( vacio( f.volumen ) || vacio( f.frecuencia ) ) {
        agregar( out, r, "source_without_volume", INFO, f.id, f.nombre );
      }
      if ( vacio( f.rechazos ) && !tieneEtapaDeRechazos( p, f ) ) {
        agregar( out, r, "source_without_rejects", AVISO, f.id, f.nombre );
      }
      if ( !leida( p, f.id ) && !p.etapas.isEmpty() ) {
        agregar( out, r, "source_not_read", AVISO, f.id, f.nombre );
      }
    }

    for ( Destino d : p.destinos ) {
      if ( !escrito( p, d.id ) && !p.etapas.isEmpty() ) {
        agregar( out, r, "target_not_written", AVISO, d.id, d.nombre );
      }
      boolean tabla = !"file".equals( d.clase );
      if ( tabla && ( d.carga == null || "insert".equals( d.carga ) || "append".equals( d.carga ) ) ) {
        agregar( out, r, "non_idempotent_load", AVISO, d.id, d.nombre, d.carga == null ? "insert" : d.carga );
      }
      if ( ( "upsert".equals( d.carga ) || "scd1".equals( d.carga ) || "scd2".equals( d.carga ) )
        && d.clave.isEmpty() ) {
        agregar( out, r, "upsert_without_key", ERROR, d.id, d.nombre, d.carga );
      }
      if ( "dimension".equals( d.clase ) && !"scd1".equals( d.carga ) && !"scd2".equals( d.carga )
        && !"truncate_insert".equals( d.carga ) && !"overwrite".equals( d.carga ) ) {
        agregar( out, r, "dimension_without_history", INFO, d.id, d.nombre );
      }
    }

    for ( Etapa e : p.etapas ) {
      boolean esLookup = "lookup".equals( e.clase ) || e.busca != null;
      if ( esLookup && e.clave.isEmpty() ) {
        agregar( out, r, "lookup_without_key", AVISO, e.id, e.nombre );
      }
      if ( esLookup && e.siFalta == null ) {
        agregar( out, r, "lookup_without_on_missing", AVISO, e.id, e.nombre );
      }
      if ( "reject".equals( e.siFalta ) && !rechazosDespues( p, e ) ) {
        agregar( out, r, "orphans_rejected_nowhere", AVISO, e.id, e.nombre );
      }
      if ( "dedupe".equals( e.clase ) && e.clave.isEmpty() && !claveDeFuentes( p, e ) ) {
        agregar( out, r, "dedupe_without_key", AVISO, e.id, e.nombre );
      }
      if ( e.despues.isEmpty() && e.lee.isEmpty() && e.busca == null && !"extract".equals( e.clase ) ) {
        agregar( out, r, "stage_without_input", AVISO, e.id, e.nombre );
      }
      if ( e.escribe.isEmpty() && !"reject".equals( e.clase ) && siguientes( p, e ).isEmpty() ) {
        agregar( out, r, "stage_dead_end", INFO, e.id, e.nombre );
      }
    }

    for ( Plano.Regla x : p.reglas ) {
      boolean usada = false;
      for ( Etapa e : p.etapas ) {
        usada |= e.reglas.contains( x.id );
      }
      if ( !usada && !p.etapas.isEmpty() ) {
        agregar( out, r, "rule_unused", INFO, x.id, x.id, recortar( x.texto, 60 ) );
      }
    }

    List<String> ts = p.transformaciones();
    if ( ts.size() > 1 && p.orquestacion == null ) {
      agregar( out, r, "no_orchestration", INFO, null, ts.size() );
    } else if ( p.orquestacion != null && p.orquestacion.siFalla == null ) {
      agregar( out, r, "orchestration_without_failure_path", INFO, null );
    }
    int k = 0;
    for ( Plano.Riesgo x : p.riesgos ) {
      k++;
      if ( vacio( x.mitigacion ) ) {
        agregar( out, r, "risk_without_mitigation", INFO, "risks[" + ( k - 1 ) + "]", recortar( x.texto, 60 ) );
      }
    }
    k = 0;
    for ( Plano.Decision x : p.decisiones ) {
      k++;
      if ( vacio( x.porque ) ) {
        agregar( out, r, "decision_without_why", INFO, "decisions[" + ( k - 1 ) + "]", recortar( x.texto, 60 ) );
      }
    }
    return out;
  }

  /** {errors, warnings, infos, findings[]}: lo que devuelve plan_review. */
  static ObjectNode json( List<Hallazgo> hs ) {
    ObjectNode o = Tools.obj();
    int e = 0;
    int w = 0;
    int i = 0;
    ArrayNode a = Tools.arr();
    for ( Hallazgo h : hs ) {
      if ( ERROR.equals( h.severidad ) ) {
        e++;
      } else if ( AVISO.equals( h.severidad ) ) {
        w++;
      } else {
        i++;
      }
      ObjectNode x = a.addObject();
      x.put( "code", h.codigo );
      x.put( "severity", h.severidad );
      if ( h.donde != null ) {
        x.put( "where", h.donde );
      }
      x.put( "message", h.mensaje );
      if ( h.arreglo != null ) {
        x.put( "how_to_fix", h.arreglo );
      }
      if ( h.pregunta != null ) {
        x.put( "ask_the_user", h.pregunta );
      }
    }
    o.put( "errors", e );
    o.put( "warnings", w );
    o.put( "infos", i );
    o.set( "findings", a );
    return o;
  }

  // ---------------------------------------------------------------- apoyo

  private static void agregar( List<Hallazgo> out, Rotulos r, String codigo, String sev, String donde,
                               Object... args ) {
    Hallazgo h = new Hallazgo( codigo, sev, donde );
    h.mensaje = r.f( "rev." + codigo + ".msg", args );
    h.arreglo = r.tiene( "rev." + codigo + ".fix" ) ? r.f( "rev." + codigo + ".fix", args ) : null;
    h.pregunta = r.tiene( "rev." + codigo + ".ask" ) ? r.f( "rev." + codigo + ".ask", args ) : null;
    out.add( h );
  }

  static boolean vacio( String s ) {
    return s == null || s.trim().isEmpty();
  }

  static String recortar( String s, int max ) {
    if ( s == null ) {
      return "";
    }
    return s.length() <= max ? s : s.substring( 0, max - 1 ) + "…";
  }

  private static boolean leida( Plano p, String id ) {
    for ( Etapa e : p.etapas ) {
      if ( e.lee.contains( id ) || id.equals( e.busca ) ) {
        return true;
      }
    }
    return false;
  }

  private static boolean escrito( Plano p, String id ) {
    for ( Etapa e : p.etapas ) {
      if ( e.escribe.contains( id ) ) {
        return true;
      }
    }
    return false;
  }

  /** Las etapas que vienen después de e (las que la nombran en 'after'). */
  static List<Etapa> siguientes( Plano p, Etapa e ) {
    List<Etapa> l = new ArrayList<Etapa>();
    for ( Etapa x : p.etapas ) {
      if ( x.despues.contains( e.id ) ) {
        l.add( x );
      }
    }
    return l;
  }

  /** Alguna etapa de rechazos que sale (directa o indirectamente) de una etapa que lee la fuente. */
  private static boolean tieneEtapaDeRechazos( Plano p, Fuente f ) {
    for ( Etapa e : p.etapas ) {
      if ( e.lee.contains( f.id ) && rechazosDespues( p, e ) ) {
        return true;
      }
    }
    return false;
  }

  /** Hay una etapa de rechazos alcanzable desde e (o e misma lo es). */
  static boolean rechazosDespues( Plano p, Etapa e ) {
    Set<String> vistos = new HashSet<String>();
    List<Etapa> cola = new ArrayList<Etapa>();
    cola.add( e );
    while ( !cola.isEmpty() ) {
      Etapa x = cola.remove( 0 );
      if ( !vistos.add( x.id ) ) {
        continue;
      }
      if ( "reject".equals( x.clase ) ) {
        return true;
      }
      cola.addAll( siguientes( p, x ) );
    }
    return false;
  }

  /** La etapa lee fuentes que declaran su clave (la del dedupe puede ser esa). */
  private static boolean claveDeFuentes( Plano p, Etapa e ) {
    Set<String> vistos = new HashSet<String>();
    List<Etapa> cola = new ArrayList<Etapa>();
    cola.add( e );
    while ( !cola.isEmpty() ) {
      Etapa x = cola.remove( 0 );
      if ( !vistos.add( x.id ) ) {
        continue;
      }
      for ( String s : x.lee ) {
        Fuente f = p.fuente( s );
        if ( f != null && !f.clave.isEmpty() ) {
          return true;
        }
      }
      for ( String a : x.despues ) {
        Etapa y = p.etapa( a );
        if ( y != null ) {
          cola.add( y );
        }
      }
    }
    return false;
  }
}
