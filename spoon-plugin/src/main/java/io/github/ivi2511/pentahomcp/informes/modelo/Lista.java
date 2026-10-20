package io.github.ivi2511.pentahomcp.informes.modelo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Lista de vinetas. Cada item admite el mismo poco Markdown que {@link Parrafo}.
 *
 * @dueño WP0
 */
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class Lista extends Bloque {

  public List<String> items = new ArrayList<>();

  public Lista() {
  }

  public Lista( String... items ) {
    this.items = new ArrayList<>( Arrays.asList( items ) );
  }
}
