package io.github.ivi2511.pentahomcp.lint;

/**
 * Una regla (o un step de una regla) que no se pudo evaluar: no resta puntos y se lista con el motivo.
 * code: cycle | fields | no_file | unsaved | cancelled | order_unknown | error; y los del modo profundo (el hallazgo
 * queda con la severidad del estático): deep_not_read_only | deep_budget | deep_cancelled | deep_failed | deep_no_data.
 */
public final class NoEvaluado {

  public String rule;
  public String step;
  public String entry;
  public String code;
  public String reason;

  @Override
  public String toString() {
    return rule + ( step != null ? " @" + step : "" ) + ( entry != null ? " @" + entry : "" ) + ": " + reason;
  }
}
