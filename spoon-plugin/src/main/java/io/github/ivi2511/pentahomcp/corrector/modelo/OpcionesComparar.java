package io.github.ivi2511.pentahomcp.corrector.modelo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Bloque {@code compare} del pack (diseño §2.2 y §4): como se emparejan columnas y filas y cuando dos celdas son
 * iguales. Los valores por defecto son los del prototipo salvo la tolerancia: 0,01 porque la salida esperada
 * sale formateada por la solucion ({@code 0.00}) y la captura {@code step} trae los numeros sin redondear
 * (7.2333 contra 7.23).
 *
 * @dueño C0
 */
@JsonInclude( JsonInclude.Include.NON_EMPTY )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class OpcionesComparar {

  /** extra_columns: las columnas que sobran solo se avisan. */
  public static final String EXTRA_AVISAR = "warn";
  /** extra_columns: las columnas que sobran restan en {@code columns} [NP]. */
  public static final String EXTRA_RESTAR = "penalize";
  public static final List<String> EXTRAS = Collections.unmodifiableList( Arrays.asList( EXTRA_AVISAR, EXTRA_RESTAR ) );

  /** Columnas que identifican una fila; vacio = multiconjunto o por posicion (§4). */
  @JsonProperty( "key_columns" )
  public List<String> columnasClave = new ArrayList<>();
  /** true: columnas por nombre (sin mayusculas, con trim); false: por posicion y aviso order_differs. */
  @JsonProperty( "ignore_column_order" )
  public boolean ignorarOrdenColumnas = true;
  /** Sin clave: true = multiconjunto, false = por posicion. */
  @JsonProperty( "ignore_row_order" )
  public boolean ignorarOrdenFilas = true;
  /** |a - b| <= max(tolerancia, relativa * max(|a|, |b|)) para las columnas numericas de output.columns. */
  @JsonProperty( "numeric_tolerance" )
  public double toleranciaNumerica = 0.01;
  @JsonProperty( "relative_tolerance" )
  public double toleranciaRelativa = 0;
  @JsonProperty( "trim" )
  public boolean recortar = true;
  @JsonProperty( "case_sensitive" )
  public boolean distinguirMayusculas = true;
  /** Mascaras con las que se aceptan fechas escritas distinto que la solucion [NP]. */
  @JsonProperty( "date_formats" )
  public List<String> formatosFecha = new ArrayList<>();
  /** true: coma decimal u otra mascara de fecha restan aunque el valor sea el mismo [NP]. */
  @JsonProperty( "strict_format" )
  public boolean formatoEstricto;
  /** {@link #EXTRA_AVISAR} o {@link #EXTRA_RESTAR}. */
  @JsonProperty( "extra_columns" )
  public String columnasDeMas = EXTRA_AVISAR;
  /** Ejemplos por tipo (faltan, sobran, difieren, claves repetidas) en los casos visibles. */
  @JsonProperty( "max_examples" )
  public int maxEjemplos = 5;

  /** true si hay columnas clave. */
  public boolean conClave() {
    return columnasClave != null && !columnasClave.isEmpty();
  }
}
