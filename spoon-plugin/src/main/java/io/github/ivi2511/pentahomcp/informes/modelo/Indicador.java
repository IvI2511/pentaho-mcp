package io.github.ivi2511.pentahomcp.informes.modelo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Una tarjeta de {@link Indicadores}. No es un bloque.
 *
 * @dueño WP0
 */
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class Indicador {

  public static final String OK = "ok";
  public static final String WARN = "warn";
  public static final String ERROR = "error";

  /** Rotulo chico, ya traducido ("Duracion"). */
  public String etiqueta;
  /** Valor grande, ya formateado ("2,7 s"). */
  public String valor;
  /** Linea chica bajo el valor; opcional. */
  public String detalle;
  /** ok | warn | error | null (neutro). */
  public String nivel;

  public Indicador() {
  }

  public Indicador( String etiqueta, String valor ) {
    this.etiqueta = etiqueta;
    this.valor = valor;
  }

  public Indicador( String etiqueta, String valor, String detalle, String nivel ) {
    this.etiqueta = etiqueta;
    this.valor = valor;
    this.detalle = detalle;
    this.nivel = nivel;
  }
}
