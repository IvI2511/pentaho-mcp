package io.github.ivi2511.pentahomcp.informes.modelo;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Un documento dentro de un informe combinado (InformeServicio.combinar): sus datos de cabecera, que en
 * un informe simple van en Informe. Sus secciones estan en Informe.secciones con Seccion.capitulo = id.
 *
 * @dueño M
 */
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class Capitulo {

  /** "d1", "d2"...: prefijo de los ids de bloque, de las anclas y de los archivos de ese documento. */
  public String id;
  /** Titulo del informe de ese documento (el nombre de la transformacion o el job). */
  public String titulo;
  public String subtitulo;
  /** transformation | job. */
  public String tipoDoc;
  public String nombreDoc;
  /** Ruta del .ktr/.kjb; null si no esta guardado. */
  public String archivo;
  /** Avisos de ese documento (sin guardar, sin lienzo...). */
  public List<String> avisos = new ArrayList<>();

  public Capitulo() {
  }

  public Capitulo( String id, String titulo ) {
    this.id = id;
    this.titulo = titulo;
  }
}
