package io.github.ivi2511.pentahomcp.informes.modelo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Imagen PNG o JPG embebida. En JSON los bytes van en base64; {@link Informe#sinBinarios()} los saca.
 *
 * @dueño WP0
 */
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonIgnoreProperties( ignoreUnknown = true )
public final class Imagen extends Bloque {

  public static final String PNG = "image/png";
  public static final String JPG = "image/jpeg";

  /** image/png | image/jpeg. */
  public String mime;
  /** Bytes del archivo. null despues de sinBinarios(). */
  public byte[] datos;
  /** Tamano de {@link #datos} en pixeles. Se muestra a ancho/escala x alto/escala. */
  public int ancho;
  public int alto;
  /** 2 = pintada al doble para nitidez: el HTML la muestra al 50 %. */
  public float escala = 1f;
  /** Texto alternativo. */
  public String alt;

  public Imagen() {
  }

  public Imagen( String mime, byte[] datos, int ancho, int alto, float escala ) {
    this.mime = mime;
    this.datos = datos;
    this.ancho = ancho;
    this.alto = alto;
    this.escala = escala;
  }
}
