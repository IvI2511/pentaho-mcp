package io.github.ivi2511.pentahomcp.informes;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Ids de las secciones (diseno §2.3). Son los mismos en el modelo, en el JSON y en la tool MCP
 * (argumento sections). Los titulos traducidos estan en textos/secciones_*.properties (area
 * {@link #AREA}), con el id como clave.
 *
 * @dueño WP0
 */
public final class SeccionId {

  public static final String PORTADA = "cover";
  public static final String RESUMEN = "summary";
  public static final String FLUJO = "flow";
  public static final String FLUJO_JOB = "job_flow";
  public static final String LINAJE = "lineage";
  public static final String DICCIONARIO = "dictionary";
  /** Hoja de mapeo origen-destino (D3): una fila por campo destino. Nunca por defecto. */
  public static final String MAPEO = "mapping";
  public static final String DESTINO = "target_model";
  public static final String PERFIL = "profile";
  public static final String METRICAS = "metrics";
  public static final String VALIDACION = "validation";
  /** Calidad tecnica: el linter (docs/linter-diseno.md §8). Va despues de validation, que la complementa. */
  public static final String CALIDAD = "quality";
  /** Guia de la leccion del modo clase (docs/modo-clase-diseno.md §9): solo con una leccion en el documento. */
  public static final String LECCION = "lesson";
  public static final String CONEXIONES = "connections";
  public static final String NARRATIVA = "narrative";

  /** Orden en el informe (el indice va entre la portada y el resumen, lo pone el renderizador). */
  public static final List<String> ORDEN = Collections.unmodifiableList( Arrays.asList(
    PORTADA, RESUMEN, FLUJO, FLUJO_JOB, LINAJE, DICCIONARIO, MAPEO, DESTINO, PERFIL, METRICAS, VALIDACION, CALIDAD,
    LECCION, CONEXIONES, NARRATIVA ) );

  /** Area de Rotulos con los titulos de las secciones. */
  public static final String AREA = "secciones";

  private SeccionId() {
  }

  public static boolean valido( String id ) {
    return id != null && ORDEN.contains( id );
  }

  /** Titulo traducido ("Linaje de campos"); el id si no hay texto. */
  public static String titulo( String id, String idioma ) {
    return Rotulos.de( AREA, idioma ).s( id );
  }
}
