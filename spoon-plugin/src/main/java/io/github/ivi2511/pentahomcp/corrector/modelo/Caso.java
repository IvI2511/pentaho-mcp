package io.github.ivi2511.pentahomcp.corrector.modelo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Un caso del pack (diseño §2.2): una carpeta de entradas y la salida esperada para ellas. Los casos ocultos
 * corren igual que los visibles, pero la devolucion del alumno no muestra sus valores, ejemplos ni log (§6).
 * Las rutas son relativas a la carpeta del pack y no pueden salir de ella (lo verifica CorrectorPack, C1).
 *
 * @dueño C0
 */
@JsonInclude( JsonInclude.Include.NON_EMPTY )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class Caso {

  /** Unico dentro del pack ("visible", "oculto"); nombra la subcarpeta del sandbox y la columna de la planilla. */
  @JsonProperty( "id" )
  public String id;
  /** Carpeta con los archivos de {@code inputs} para este caso ("datos/visible"). */
  @JsonProperty( "inputs_dir" )
  public String dirEntradas;
  /** CSV con la salida esperada ("esperado/visible.csv"); lo escribe create_exercise_pack. */
  @JsonProperty( "expected" )
  public String esperado;
  /** Peso en el promedio de los checks de salida (§5). */
  @JsonProperty( "weight" )
  public double peso = 1;
  @JsonProperty( "hidden" )
  public boolean oculto;

  public Caso() {
  }

  public Caso( String id, String dirEntradas, String esperado, boolean oculto ) {
    this.id = id;
    this.dirEntradas = dirEntradas;
    this.esperado = esperado;
    this.oculto = oculto;
  }
}
