package io.github.ivi2511.pentahomcp.corrector.modelo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
  /**
   * Formato 2: scripts SQL de este caso por conexion ("GYM_OLTP" → ["bd/oculto/datos.sql"]), que corren despues de
   * los de la base ({@code test_databases[].scripts}): los datos de origen de cada caso.
   */
  @JsonProperty( "db_scripts" )
  public Map<String, List<String>> scriptsBase = new LinkedHashMap<>();
  /**
   * Formato 2: cuantas veces se corre la entrega en este caso antes de capturar (sin recrear las bases entre una y
   * otra); null = 1. Con 2 se ve si una carga duplica filas al volver a correrla.
   */
  @JsonProperty( "executions" )
  public Integer ejecuciones;

  /** {@link #ejecuciones} entre 1 y 5. */
  public int vecesQueCorre() {
    return ejecuciones == null ? 1 : Math.max( 1, Math.min( 5, ejecuciones ) );
  }

  public Caso() {
  }

  public Caso( String id, String dirEntradas, String esperado, boolean oculto ) {
    this.id = id;
    this.dirEntradas = dirEntradas;
    this.esperado = esperado;
    this.oculto = oculto;
  }
}
