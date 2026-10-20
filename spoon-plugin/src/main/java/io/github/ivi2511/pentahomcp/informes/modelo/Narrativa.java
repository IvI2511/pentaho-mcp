package io.github.ivi2511.pentahomcp.informes.modelo;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Texto opcional del usuario o de la IA (diseno §1.5). Texto plano con poco Markdown, tope de 2.000
 * caracteres por texto. No es un bloque: los recolectores lo convierten en Parrafo con ia segun el origen.
 *
 * @dueño WP0
 */
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class Narrativa {

  public static final String USUARIO = "usuario";
  public static final String IA = "ia";

  /** Va al principio de la seccion "summary". */
  public String resumen;
  /** Arma la seccion "narrative". */
  public String conclusiones;
  /** id de seccion -> texto que va al principio de esa seccion. */
  public Map<String, String> porSeccion = new LinkedHashMap<>();
  /** usuario | ia. */
  public String origen;
}
