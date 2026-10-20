package io.github.ivi2511.pentahomcp.informes.modelo;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Datos de un grafico; el SVG lo genera GraficosSvg (WP6) al renderizar.
 * <ul>
 * <li>barras_h: {@link #categorias} y 1 o 2 {@link #series} (mismo largo que categorias).</li>
 * <li>gantt: {@link #tramos}; {@link #categorias} da el orden de las filas (si es null, el de los tramos).</li>
 * <li>linea: {@link #categorias} es el eje X y hasta 5 {@link #series}.</li>
 * </ul>
 *
 * @dueño WP0
 */
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class Grafico extends Bloque {

  public static final String BARRAS_H = "barras_h";
  public static final String GANTT = "gantt";
  public static final String LINEA = "linea";

  /** barras_h | gantt | linea. */
  public String clase;
  /** Unidad de los valores ("filas", "ms", "%"); opcional. */
  public String unidad;
  public List<String> categorias = new ArrayList<>();
  public List<Serie> series = new ArrayList<>();
  public List<Tramo> tramos = new ArrayList<>();
  /**
   * Gantt: la nota de la leyenda. null = la de siempre (los steps corren en paralelo, que las barras se
   * solapen es normal); "" = ninguna; otro texto = ese (los jobs: las entradas corren una tras otra).
   */
  public String leyenda;
}
