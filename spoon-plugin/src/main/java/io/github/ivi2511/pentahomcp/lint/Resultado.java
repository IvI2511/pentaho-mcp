package io.github.ivi2511.pentahomcp.lint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lo que devuelve LintMotor.analizar (forma JSON: LintJson, §6). */
public final class Resultado {

  /** Id del documento en el plugin (T1, J2…); lo pone quien llama. */
  public String document;
  public String name;
  /** transformation | job */
  public String kind;
  public String rulesVersion;
  public int score;
  public String grade;
  /** error / warning / info de los hallazgos que cuentan (sin silenciados ni kettle_check). */
  public final Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
  public final List<Penalidad> penalties = new ArrayList<Penalidad>();
  /** Puntos de las info antes del tope global (Puntaje.TOPE_INFO); igual a infoPoints si no se aplicó. */
  public int infoPointsRaw;
  public int infoPoints;
  /** true si el puntaje quedó en el máximo de la compuerta (hay un error). */
  public boolean gateApplied;
  public final List<Hallazgo> findings = new ArrayList<Hallazgo>();
  /** Cuántos hallazgos no se muestran por min_severity o steps (igual cuentan en el puntaje). */
  public int hidden;
  public final List<Hallazgo> suppressed = new ArrayList<Hallazgo>();
  /** Lo que dice checkSteps/checkJobEntries (include_kettle_checks): no resta (§4). */
  public final List<Hallazgo> kettleChecks = new ArrayList<Hallazgo>();
  public final List<NoEvaluado> notEvaluated = new ArrayList<NoEvaluado>();
  public final Profundo deep = new Profundo();
  /**
   * Sub-puntaje de robustez (score, grade, counts, penalties; mismo cálculo que el general) sobre los hallazgos de la
   * familia robustness que no están silenciados; null si la familia no corrió. Sus hallazgos van en findings.
   */
  public Resultado robustez;
  public long ms;

  public static String banda( int score ) {
    return score >= 90 ? "A" : score >= 75 ? "B" : score >= 50 ? "C" : "D";
  }
}
