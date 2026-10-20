package io.github.ivi2511.pentahomcp.informes.modelo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Pieza de contenido de una seccion. Los recolectores producen bloques y los renderizadores solo
 * saben dibujar estos ocho tipos (diseno §2.1 y §2.2). En JSON el tipo va en la propiedad "tipo".
 *
 * @dueño WP0
 */
@JsonTypeInfo( use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "tipo" )
@JsonSubTypes( {
  @JsonSubTypes.Type( value = Parrafo.class, name = Bloque.PARRAFO ),
  @JsonSubTypes.Type( value = Lista.class, name = Bloque.LISTA ),
  @JsonSubTypes.Type( value = Tabla.class, name = Bloque.TABLA ),
  @JsonSubTypes.Type( value = Indicadores.class, name = Bloque.INDICADORES ),
  @JsonSubTypes.Type( value = Diagrama.class, name = Bloque.DIAGRAMA ),
  @JsonSubTypes.Type( value = Grafico.class, name = Bloque.GRAFICO ),
  @JsonSubTypes.Type( value = Imagen.class, name = Bloque.IMAGEN ),
  @JsonSubTypes.Type( value = Aviso.class, name = Bloque.AVISO ) } )
@JsonInclude( JsonInclude.Include.NON_NULL )
@JsonIgnoreProperties( ignoreUnknown = true )
public abstract class Bloque {

  /** Valores de la propiedad "tipo" en JSON. */
  public static final String PARRAFO = "parrafo";
  public static final String LISTA = "lista";
  public static final String TABLA = "tabla";
  public static final String INDICADORES = "indicadores";
  public static final String DIAGRAMA = "diagrama";
  public static final String GRAFICO = "grafico";
  public static final String IMAGEN = "imagen";
  public static final String AVISO = "aviso";

  /** Unico dentro del informe (ancla HTML, nombre de archivo en Markdown, clave del pre-render). */
  public String id;
  /** Titulo visible del bloque, ya traducido; opcional. */
  public String titulo;
  /** Nota al pie del bloque, ya traducida; opcional. */
  public String nota;
  /** true si el texto lo redacto una IA (narrativa); con Tema.rotularIa se rotula. */
  public boolean ia;
}
