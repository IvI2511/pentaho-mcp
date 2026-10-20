package io.github.ivi2511.pentahomcp.informes.modelo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Raiz del modelo: Informe -> Secciones -> Bloques (diseno §2.1). POJO con campos publicos,
 * serializable con el Jackson 2.9 de lib\ (ver {@link ModeloJson}).
 *
 * @dueño WP0
 */
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class Informe {

  public static final String TRANSFORMACION = "transformation";
  public static final String JOB = "job";

  /** Version del esquema. */
  public int modelo = 1;
  public String titulo;
  public String subtitulo;
  /** transformation | job. */
  public String tipoDoc;
  /** Ruta del .ktr/.kjb; null si no esta guardado. */
  public String archivo;
  public String nombreDoc;
  /** en | es | pt. */
  public String idioma;
  /** ISO-8601 con zona. */
  public String generado;
  /** "page", "of", "contents", "generated_with", "ai_label"... ya traducidos. */
  public Map<String, String> rotulos = new LinkedHashMap<>();
  public List<Seccion> secciones = new ArrayList<>();
  public List<String> avisos = new ArrayList<>();

  /** La primera seccion con ese id, o null. */
  public Seccion seccion( String id ) {
    for ( Seccion s : secciones ) {
      if ( s != null && id != null && id.equals( s.id ) ) {
        return s;
      }
    }
    return null;
  }

  /** Copia profunda (via Jackson). */
  public Informe copia() {
    return ModeloJson.copia( this, Informe.class );
  }

  /**
   * Copia profunda sin ningun byte[]: las imagenes quedan con mime, ancho y alto, y datos = null.
   * El original no cambia. Es lo que devuelve get_report_data por MCP.
   */
  public Informe sinBinarios() {
    Informe c = copia();
    for ( Seccion s : c.secciones ) {
      if ( s == null || s.bloques == null ) {
        continue;
      }
      for ( Bloque b : s.bloques ) {
        if ( b instanceof Imagen ) {
          ( (Imagen) b ).datos = null;
        } else if ( b instanceof Diagrama && ( (Diagrama) b ).imagen != null ) {
          ( (Diagrama) b ).imagen.datos = null;
        }
      }
    }
    return c;
  }
}
