package io.github.ivi2511.pentahomcp.informes.modelo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Serie de un {@link Grafico}: un valor por categoria (null = sin dato). No es un bloque.
 *
 * @dueño WP0
 */
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class Serie {

  public String nombre;
  public List<Double> valores = new ArrayList<>();

  public Serie() {
  }

  public Serie( String nombre, Double... valores ) {
    this.nombre = nombre;
    this.valores = new ArrayList<>( Arrays.asList( valores ) );
  }
}
