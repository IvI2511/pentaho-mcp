package io.github.ivi2511.pentahomcp.informes.modelo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Recuadro de aviso (HTML), "> [!WARNING]" (Markdown), texto en cursiva (PPTX).
 *
 * @dueño WP0
 */
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class Aviso extends Bloque {

  public static final String INFO = "info";
  public static final String WARN = "warn";
  public static final String ERROR = "error";

  /** info | warn | error. */
  public String nivel;
  public String texto;

  public Aviso() {
  }

  public Aviso( String nivel, String texto ) {
    this.nivel = nivel;
    this.texto = texto;
  }
}
