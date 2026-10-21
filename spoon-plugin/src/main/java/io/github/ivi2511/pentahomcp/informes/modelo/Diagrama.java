package io.github.ivi2511.pentahomcp.informes.modelo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Diagrama: el grafo para el motor propio ({@link #grafo}: flujo, job y linaje), el texto Mermaid (el modelo
 * entidad-relacion, y en los demas el codigo que va plegado en el Markdown para Obsidian y GitHub), y
 * opcionalmente la imagen real (el lienzo) y una tabla para cuando no se puede dibujar.
 * <p>
 * Con {@link #grafo} los renderizadores dibujan el diagrama propio (SVG o PNG, sin navegador) y el Mermaid
 * no se pre-renderiza; sin grafo, el Mermaid (Chromium) o la tabla alternativa.
 *
 * @dueño WP0
 */
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class Diagrama extends Bloque {

  public static final String FLUJO = "flujo";
  public static final String JOB = "job";
  public static final String LINAJE = "linaje";
  public static final String ER = "er";

  /** flujo | job | linaje | er. */
  public String clase;
  /** Sintaxis compatible con Mermaid 9+ (flowchart, erDiagram; nada de "@{ shape: ... }"). Puede ser null. */
  public String mermaid;
  /** Grafo del diagrama propio (flujo, job, linaje); null = solo Mermaid (el modelo entidad-relacion). */
  public Esquema grafo;
  /** Imagen real (por ejemplo el lienzo de Spoon); opcional. */
  public Imagen imagen;
  /** Lo que se muestra cuando no se puede dibujar el Mermaid; opcional. */
  public Tabla alternativa;
  /** Texto alternativo (accesibilidad y Markdown). */
  public String alt;
}
