package io.github.ivi2511.pentahomcp.informes.modelo;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Una seccion del informe (diseno §2.3): titulo, resumen automatico, estado, bloques y datos crudos.
 *
 * @dueño WP0
 */
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class Seccion {

  public static final String OK = "ok";
  public static final String PARCIAL = "parcial";
  public static final String OMITIDA = "omitida";
  public static final String ERROR = "error";

  /** Uno de SeccionId. */
  public String id;
  /** Ya traducido. */
  public String titulo;
  /** 1 o 2 frases automaticas (plantilla, sin IA), ya traducidas. */
  public String resumen;
  /** ok | parcial | omitida | error. */
  public String estado = OK;
  /** Por que quedo parcial, omitida o con error (traducido). null si estado = ok. */
  public String motivo;
  public List<Bloque> bloques = new ArrayList<>();
  /** Forma cruda de la seccion (las de §2.4), para JSON y MCP; opcional. */
  public JsonNode datos;
  /** Informe combinado: id del Capitulo al que pertenece; null = la parte general o un informe simple. */
  public String capitulo;

  public Seccion() {
  }

  public Seccion( String id, String titulo ) {
    this.id = id;
    this.titulo = titulo;
  }

  /** Seccion sin bloques con estado "omitida" y su motivo. */
  public static Seccion omitida( String id, String titulo, String motivo ) {
    Seccion s = new Seccion( id, titulo );
    s.estado = OMITIDA;
    s.motivo = motivo;
    return s;
  }

  /** Agrega un bloque y devuelve la seccion, para encadenar. */
  public Seccion con( Bloque b ) {
    bloques.add( b );
    return this;
  }
}
