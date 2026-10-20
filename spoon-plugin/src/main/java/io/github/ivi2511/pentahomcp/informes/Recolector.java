package io.github.ivi2511.pentahomcp.informes;

import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;

/**
 * Arma una seccion del informe a partir de la captura (diseno §7.3). Una implementacion por seccion
 * (Sec* en io.github.ivi2511.pentahomcp); InformeServicio las registra.
 *
 * @dueño WP0
 */
public interface Recolector {

  /** Uno de {@link SeccionId}. */
  String id();

  /**
   * null si la seccion aplica a esta captura; si no, el motivo ya traducido ("solo para
   * transformaciones"). Barato: no consulta campos, ni la base, ni ejecuta nada; se llama en la UI.
   */
  String noAplica( Captura c, String idioma );

  /**
   * Arma la seccion. Corre en un worker, nunca toca SWT, respeta ctx.cancelado() y ctx.msRestantes().
   * Si algo falla a medias devuelve la seccion con estado "parcial" o "error" y su motivo; una
   * excepcion la convierte InformeServicio en una seccion "error".
   */
  Seccion armar( Contexto ctx ) throws Exception;
}
