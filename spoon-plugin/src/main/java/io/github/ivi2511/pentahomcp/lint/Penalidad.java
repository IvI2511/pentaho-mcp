package io.github.ivi2511.pentahomcp.lint;

/** De dónde sale cada punto que falta (§4): una por regla que restó. */
public final class Penalidad {

  public String rule;
  /** La peor severidad de sus hallazgos. */
  public Sev severity;
  public int hits;
  /** min(tope de la regla, suma de pesos). Las info, además, comparten el tope global (Puntaje.TOPE_INFO). */
  public int points;

  @Override
  public String toString() {
    return rule + " " + severity.codigo() + " x" + hits + " = " + points;
  }
}
