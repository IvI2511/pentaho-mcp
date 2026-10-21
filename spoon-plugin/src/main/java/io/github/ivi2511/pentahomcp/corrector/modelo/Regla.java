package io.github.ivi2511.pentahomcp.corrector.modelo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Una regla estructural del pack (diseño §2.2 y §5). Se evalua sin ejecutar y no depende del caso; en la rubrica
 * se referencia como {@code rule:<id>}.
 * <ul>
 * <li>{@code must_use}: algun step de esos tipos (id de plugin), conectado por un hop habilitado; mejor, ancestro
 * del step de salida.</li>
 * <li>{@code must_not_use}: ningun step de esos tipos.</li>
 * <li>{@code max_steps}: como mucho {@code value} steps (todos: tambien los sueltos, P20).</li>
 * <li>{@code error_handling}: los steps de esos tipos tienen manejo de errores [NP].</li>
 * <li>{@code lint}: ningun hallazgo &gt;= warning de la regla {@code rule} del linter [NP].</li>
 * </ul>
 *
 * @dueño C0
 */
@JsonInclude( JsonInclude.Include.NON_EMPTY )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class Regla {

  public static final String DEBE_USAR = "must_use";
  public static final String NO_DEBE_USAR = "must_not_use";
  public static final String MAX_STEPS = "max_steps";
  public static final String MANEJO_ERRORES = "error_handling";
  public static final String LINT = "lint";
  public static final List<String> TIPOS = Collections.unmodifiableList( Arrays.asList( DEBE_USAR, NO_DEBE_USAR,
    MAX_STEPS, MANEJO_ERRORES, LINT ) );

  /** Unico dentro del pack; la rubrica lo usa como {@code rule:<id>}. */
  @JsonProperty( "id" )
  public String id;
  /** Uno de {@link #TIPOS}. */
  @JsonProperty( "type" )
  public String tipo;
  /** Ids de plugin de los steps (GroupBy, ScriptValueMod...): must_use, must_not_use, error_handling. */
  @JsonProperty( "steps" )
  public List<String> steps = new ArrayList<>();
  /** max_steps: el tope. */
  @JsonProperty( "value" )
  public Integer valor;
  /** lint: id de la regla del linter (unsorted_input, absolute_path...). */
  @JsonProperty( "rule" )
  public String reglaLint;
  /** Texto para la devolucion; si falta, el motor arma uno con el tipo. */
  @JsonProperty( "label" )
  public String etiqueta;

  public Regla() {
  }

  public Regla( String id, String tipo, String... steps ) {
    this.id = id;
    this.tipo = tipo;
    this.steps = new ArrayList<>( Arrays.asList( steps ) );
  }

  public static boolean tipoValido( String tipo ) {
    return TIPOS.contains( tipo );
  }
}
