package io.github.ivi2511.pentahomcp.informes.modelo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Texto plano con poco Markdown: **negrita** y saltos de linea. Nunca HTML crudo: cada renderizador
 * lo escapa.
 *
 * @dueño WP0
 */
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class Parrafo extends Bloque {

  public String texto;

  public Parrafo() {
  }

  public Parrafo( String texto ) {
    this.texto = texto;
  }
}
