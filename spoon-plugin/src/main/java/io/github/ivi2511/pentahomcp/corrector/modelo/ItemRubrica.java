package io.github.ivi2511.pentahomcp.corrector.modelo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Un renglon de la rubrica (diseño §5): un check y sus puntos.
 * <ul>
 * <li>{@code runs}, {@code columns}, {@code row_count}, {@code values}: checks de salida, se promedian entre casos
 * segun {@link Caso#peso};</li>
 * <li>{@code rule:<id>}: una {@link Regla} del pack;</li>
 * <li>{@code lint:<id>}: una regla del linter por su id (unsorted_input...).</li>
 * </ul>
 * {@code requires} es la compuerta: si alguno de esos checks no se aprobo, este vale 0 (por ejemplo, las reglas
 * no suman si la entrega no corre).
 *
 * @dueño C0
 */
@JsonInclude( JsonInclude.Include.NON_EMPTY )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class ItemRubrica {

  public static final String RUNS = "runs";
  public static final String COLUMNS = "columns";
  public static final String ROW_COUNT = "row_count";
  public static final String VALUES = "values";
  public static final String PREFIJO_REGLA = "rule:";
  public static final String PREFIJO_LINT = "lint:";
  /** Los checks que se calculan por caso, en el orden de la planilla. */
  public static final List<String> DE_SALIDA = Collections.unmodifiableList( Arrays.asList( RUNS, COLUMNS, ROW_COUNT,
    VALUES ) );

  @JsonProperty( "check" )
  public String check;
  @JsonProperty( "points" )
  public double puntos;
  /** Checks que tienen que estar aprobados para que este sume (compuerta). */
  @JsonProperty( "requires" )
  public List<String> requiere = new ArrayList<>();
  /** Texto para la devolucion; si falta, el motor usa el de Rotulos (area corrector) o el de la regla. */
  @JsonProperty( "label" )
  public String etiqueta;

  public ItemRubrica() {
  }

  public ItemRubrica( String check, double puntos, String... requiere ) {
    this.check = check;
    this.puntos = puntos;
    this.requiere = new ArrayList<>( Arrays.asList( requiere ) );
  }

  /** runs, columns, row_count o values. */
  public boolean esDeSalida() {
    return DE_SALIDA.contains( check );
  }

  public boolean esRegla() {
    return check != null && check.startsWith( PREFIJO_REGLA ) && check.length() > PREFIJO_REGLA.length();
  }

  public boolean esLint() {
    return check != null && check.startsWith( PREFIJO_LINT ) && check.length() > PREFIJO_LINT.length();
  }

  /** El id de la regla de {@code rule:<id>}, o null. */
  public String idRegla() {
    return esRegla() ? check.substring( PREFIJO_REGLA.length() ) : null;
  }

  /** El id de la regla del linter de {@code lint:<id>}, o null. */
  public String idLint() {
    return esLint() ? check.substring( PREFIJO_LINT.length() ) : null;
  }

  /** runs, columns, row_count, values, rule:&lt;id&gt; o lint:&lt;id&gt; (sin mirar si la regla existe). */
  public static boolean checkValido( String check ) {
    if ( check == null ) {
      return false;
    }
    if ( DE_SALIDA.contains( check ) ) {
      return true;
    }
    ItemRubrica i = new ItemRubrica( check, 0 );
    return i.esRegla() || i.esLint();
  }
}
