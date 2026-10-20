package io.github.ivi2511.pentahomcp.informes.modelo;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Fila de tarjetas con un numero grande cada una (duracion, filas, errores...).
 *
 * @dueño WP0
 */
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class Indicadores extends Bloque {

  public List<Indicador> items = new ArrayList<>();

  /** Agrega un indicador y devuelve el bloque, para encadenar. */
  public Indicadores con( Indicador i ) {
    items.add( i );
    return this;
  }
}
