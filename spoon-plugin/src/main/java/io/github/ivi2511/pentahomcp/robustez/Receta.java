package io.github.ivi2511.pentahomcp.robustez;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Las recetas de harden_etl (docs/robustez-diseno.md §2): id, nivel y si cambian la semántica de la carga. El orden
 * de la lista es el orden en que se componen y se aplican (pegado a la entrada primero, la carga al final).
 */
public final class Receta {

  /** Solo cambia el destino de filas que hoy abortan la carga: entra por defecto. */
  public static final String AUTO = "auto";
  /** Cambia qué filas llegan o el diseño: solo con su key en 'confirm'. */
  public static final String CONFIRM = "confirm";
  /** La herramienta no lo aplica: da el cómo. */
  public static final String MANUAL = "manual";

  public static final String REJECTS_ON_ERROR = "rejects_on_error";
  public static final String REJECT_REASON = "reject_reason";
  public static final String REQUIRED_FIELDS = "required_fields";
  public static final String STRICT_CONVERSION = "strict_conversion";
  public static final String SAFE_SPLIT = "safe_split";
  public static final String DEDUPE_BEFORE_LOAD = "dedupe_before_load";
  public static final String STABLE_SURROGATE_KEYS = "stable_surrogate_keys";
  public static final String ORPHANS_TO_REJECTS = "orphans_to_rejects";
  public static final String IDEMPOTENT_LOAD = "idempotent_load";
  public static final String AGGREGATE_TO_GRAIN = "aggregate_to_grain";
  public static final String WAIT_FOR_LOADS = "wait_for_loads";
  public static final String INPUT_FILE_PARAMETER = "input_file_parameter";
  public static final String DEFAULT_OPTIONAL = "default_optional";
  public static final String JOB_GUARD = "job_guard";

  public final String id;
  /** Nivel con el que se propone en la v1 (las de nivel de diseño confirm sin aplicación todavía van como manual). */
  public final String nivel;
  public final boolean semantica;
  public final String numero;

  private Receta( String numero, String id, String nivel, boolean semantica ) {
    this.numero = numero;
    this.id = id;
    this.nivel = nivel;
    this.semantica = semantica;
  }

  public static final List<Receta> TODAS;

  static {
    List<Receta> l = new ArrayList<Receta>();
    l.add( new Receta( "R03", REQUIRED_FIELDS, CONFIRM, false ) );
    l.add( new Receta( "R12", INPUT_FILE_PARAMETER, CONFIRM, false ) );
    l.add( new Receta( "R04", STRICT_CONVERSION, CONFIRM, false ) );
    l.add( new Receta( "R01", REJECTS_ON_ERROR, AUTO, false ) );
    l.add( new Receta( "R02", REJECT_REASON, AUTO, false ) );
    l.add( new Receta( "R06", DEDUPE_BEFORE_LOAD, CONFIRM, false ) );
    l.add( new Receta( "R11", WAIT_FOR_LOADS, AUTO, false ) );
    l.add( new Receta( "R05", SAFE_SPLIT, MANUAL, false ) );
    l.add( new Receta( "R07", STABLE_SURROGATE_KEYS, MANUAL, true ) );
    l.add( new Receta( "R08", ORPHANS_TO_REJECTS, MANUAL, false ) );
    l.add( new Receta( "R09", IDEMPOTENT_LOAD, MANUAL, true ) );
    l.add( new Receta( "R10", AGGREGATE_TO_GRAIN, MANUAL, true ) );
    l.add( new Receta( "R13", DEFAULT_OPTIONAL, MANUAL, false ) );
    l.add( new Receta( "R14", JOB_GUARD, MANUAL, false ) );
    TODAS = Collections.unmodifiableList( l );
  }

  /** La receta con ese id, o null. */
  public static Receta de( String id ) {
    for ( Receta r : TODAS ) {
      if ( r.id.equals( id ) ) {
        return r;
      }
    }
    return null;
  }

  /** Posición en el orden de composición (para ordenar propuestas). */
  public static int orden( String id ) {
    for ( int i = 0; i < TODAS.size(); i++ ) {
      if ( TODAS.get( i ).id.equals( id ) ) {
        return i;
      }
    }
    return TODAS.size();
  }

  /** Los ids, separados por coma (para mensajes y el esquema). */
  public static String ids() {
    StringBuilder b = new StringBuilder();
    for ( Receta r : TODAS ) {
      if ( b.length() > 0 ) {
        b.append( ", " );
      }
      b.append( r.id );
    }
    return b.toString();
  }
}
