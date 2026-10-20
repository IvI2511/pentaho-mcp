package io.github.ivi2511.pentahomcp.informes.modelo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Barra de un Gantt. Los ms son relativos al inicio de la corrida. No es un bloque.
 *
 * @dueño WP0
 */
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class Tramo {

  public static final String INIT = "init";
  public static final String EXEC = "exec";

  /** Fila del Gantt (nombre del step o de la entrada). */
  public String categoria;
  /** init | exec. */
  public String tipo;
  public long inicioMs;
  public long finMs;

  public Tramo() {
  }

  public Tramo( String categoria, String tipo, long inicioMs, long finMs ) {
    this.categoria = categoria;
    this.tipo = tipo;
    this.inicioMs = inicioMs;
    this.finMs = finMs;
  }
}
