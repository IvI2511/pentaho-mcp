package io.github.ivi2511.pentahomcp.informes.modelo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Tabla de texto. Todas las celdas son String ya formateadas (numeros con el separador del idioma).
 * Una celda null se dibuja vacia.
 *
 * @dueño WP0
 */
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class Tabla extends Bloque {

  public List<String> columnas = new ArrayList<>();
  public List<List<String>> filas = new ArrayList<>();
  /** Una por columna: "l", "r" o "c". null = todas a la izquierda. */
  public List<String> alineacion;
  /** Texto bajo la tabla (por ejemplo "+120 filas mas"); opcional. */
  public String pie;

  public Tabla() {
  }

  public Tabla( String... columnas ) {
    this.columnas = new ArrayList<>( Arrays.asList( columnas ) );
  }

  /** Agrega una fila y devuelve la tabla, para encadenar. */
  public Tabla fila( String... celdas ) {
    filas.add( new ArrayList<>( Arrays.asList( celdas ) ) );
    return this;
  }
}
