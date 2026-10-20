package io.github.ivi2511.pentahomcp.informes;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Lo que devuelve un {@link Renderizador}: archivos escritos (el principal primero), avisos ya
 * traducidos (por ejemplo "sin navegador: el PDF abre el dialogo de impresion") y el total de bytes.
 *
 * @dueño WP0
 */
public final class Resultado {

  public List<File> archivos = new ArrayList<>();
  public List<String> avisos = new ArrayList<>();
  public long bytes;

  /** Agrega un archivo escrito y suma su tamano. */
  public Resultado archivo( File f ) {
    archivos.add( f );
    if ( f != null && f.isFile() ) {
      bytes += f.length();
    }
    return this;
  }
}
