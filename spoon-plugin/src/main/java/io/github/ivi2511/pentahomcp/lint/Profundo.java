package io.github.ivi2511.pentahomcp.lint;

/**
 * Qué hizo el modo profundo (§3.3). Lo llena LintSondas (WP3) a través de {@link Sondas}; sin sondas, el motor
 * estático solo dice por qué no corrió.
 */
public final class Profundo {

  // Hallazgo.deep: qué dijo la sonda de ese hallazgo
  /** unsorted_input: la entrada está desordenada y con un Sort rows delante el resultado cambia (sube a error). */
  public static final String CONFIRMED = "confirmed";
  /** unsorted_input: desordenada, pero con un Sort rows el resultado es el mismo: anda por casualidad (queda). */
  public static final String SAME_RESULT = "same_result";
  /** unsorted_input: desordenada; la comparación con un Sort rows no se pudo completar (queda). */
  public static final String UNSORTED = "unsorted";
  /** unsorted_input: se leyó la entrada entera y viene ordenada (baja a info). */
  public static final String SORTED = "sorted";
  /** unsorted_input: la muestra viene ordenada, pero no se leyó entera o el orden no es determinista (queda). */
  public static final String SORTED_SAMPLE = "sorted_sample";
  /** empty_decimal_symbol: hay valores con separador (sube a error). */
  public static final String SEPARATORS = "separators";
  /** empty_decimal_symbol: se leyó la columna entera y son todos enteros (baja a info). */
  public static final String INTEGERS = "integers";
  /** empty_decimal_symbol: los valores leídos son enteros, pero no se leyó todo (queda). */
  public static final String INTEGERS_SAMPLE = "integers_sample";
  /** La sonda no se pudo correr (el motivo va en not_evaluated); el hallazgo queda como lo dejó el estático. */
  public static final String NOT_EVALUATED = "not_evaluated";

  public boolean ran;
  public String reason;
  public long ms;
  /** Presupuesto total (deep_timeout_s). */
  public long budgetMs;
  /** Previews corridas (la prueba diferencial son dos). */
  public int probes;
  public int raised;
  public int lowered;
  public int unchanged;
  public int notEvaluated;
}
