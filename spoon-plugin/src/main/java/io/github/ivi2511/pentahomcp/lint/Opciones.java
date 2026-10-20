package io.github.ivi2511.pentahomcp.lint;

import java.util.Set;

/** Cómo se corre el linter (los argumentos de lint_etl, §6, y lo que piden el corrector y el modo clase, §9). */
public final class Opciones {

  /** Ids a correr; null o vacío = todas las que no están apagadas. Las apagadas corren solo si se nombran. */
  public Set<String> rules;
  /** Hallazgos que se muestran: info, warning o error. El puntaje cuenta todos. */
  public Sev minSeverity = Sev.INFO;
  /** Modo profundo (§3.3): corre {@link #sondas} sobre unsorted_input y empty_decimal_symbol. */
  public boolean deep;
  /** Quien corre el modo profundo (LintSondas en el plugin); null = no disponible (Resultado.deep lo dice). */
  public Sondas sondas;
  /** Solo hallazgos sobre estos steps (modo clase); null = todos. */
  public Set<String> steps;
  /** Suma los avisos de checkSteps/checkJobEntries como kettle_check (puede conectarse a la BD). */
  public boolean kettleChecks;
  /** El corrector pasa false: si no, el alumno se silencia y aprueba (§9). */
  public boolean honorSuppressions = true;
  /** Presupuesto de todo el modo profundo (deep_timeout_s); cada sonda, además, 30 s como mucho. */
  public long deepBudgetMs = 60000;
  /** en, es o pt (textos de los hallazgos). */
  public String lang = "en";
  /**
   * El documento abierto tiene cambios sin guardar (Captura.sinGuardar): las reglas de modo archivo no
   * corren (el disco ya no es lo que se ve) y las contraseñas se juzgan en memoria.
   */
  public boolean sinGuardar;
  /** Lo que el motor le pide al plugin (LintEntorno). Obligatorio. */
  public Entorno entorno;
}
