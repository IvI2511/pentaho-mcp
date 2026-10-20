package io.github.ivi2511.pentahomcp.lint;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.pentaho.di.trans.TransMeta;

/**
 * El modo profundo (docs/linter-diseno.md §3.3): previews recortadas que LEEN las fuentes para confirmar o descartar lo
 * que el estático no puede saber (el orden real de las filas, el separador decimal de la columna). Lo implementa
 * LintSondas en el paquete base (necesita RunMotor, que es package-private); lint/ solo decide cuándo se llama.
 *
 * LintMotor lo llama sobre la copia, en el hilo de trabajo, después de las reglas y antes del puntaje: lo que cambia
 * (severidad, mensaje, evidencia, Hallazgo.deep) ya cuenta en el puntaje, en los counts y en los filtros.
 */
public interface Sondas {

  /** Las reglas que tienen sonda. */
  Set<String> REGLAS = Collections.unmodifiableSet( new LinkedHashSet<String>( Arrays.asList(
    "unsorted_input", "empty_decimal_symbol" ) ) );

  /**
   * Corre las sondas de estos hallazgos (de {@link #REGLAS}, sin los silenciados). Puede subir o bajar la severidad,
   * completar el mensaje y la evidencia y poner {@link Hallazgo#deep}; lo que no puede evaluar lo agrega a
   * noEvaluados y el hallazgo queda con la severidad del estático. Llena prof. Respeta op.deepBudgetMs y
   * op.entorno.cancelado(), y solo corre lo que op.entorno.soloLectura acepta.
   */
  void sondear( TransMeta copia, List<Hallazgo> hallazgos, Opciones op, Textos tx, Profundo prof,
                List<NoEvaluado> noEvaluados );
}
