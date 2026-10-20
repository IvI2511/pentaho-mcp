package io.github.ivi2511.pentahomcp.informes;

import java.io.File;

import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Escribe un informe en un formato (diseno §3 y §7.3). No conoce Kettle ni SWT: se prueba sin PDI.
 *
 * @dueño WP0
 */
public interface Renderizador {

  /** HTML, HTML_ESTATICO, PDF, MARKDOWN, PPTX o JSON. */
  Formato formato();

  /**
   * Escribe el informe. Corre en un worker.
   *
   * @param destino el archivo principal ("C:\...\informe.html"); los archivos extra van al lado
   *                (por ejemplo "informe_archivos\" del Markdown). Se sobrescribe si existe.
   * @return los archivos escritos (el principal primero), avisos traducidos y el total de bytes.
   */
  Resultado escribir( Informe inf, Tema tema, File destino, Progreso p ) throws Exception;
}
