package io.github.ivi2511.pentahomcp.informes.modelo;

import java.io.IOException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * El ObjectMapper del modelo, para que todos (JSON de exportacion, MCP, pruebas) serialicen igual.
 * Los byte[] salen en base64; las propiedades null no salen; las desconocidas se ignoran al leer.
 *
 * @dueño WP0
 */
public final class ModeloJson {

  private static final ObjectMapper MAPPER = new ObjectMapper()
    .configure( DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false )
    .configure( SerializationFeature.FAIL_ON_EMPTY_BEANS, false );

  private ModeloJson() {
  }

  /** Compartido y ya configurado: no cambiarle la configuracion. */
  public static ObjectMapper mapper() {
    return MAPPER;
  }

  public static String escribir( Object o, boolean indentado ) throws IOException {
    return indentado ? MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString( o )
      : MAPPER.writeValueAsString( o );
  }

  public static <T> T leer( String json, Class<T> tipo ) throws IOException {
    return MAPPER.readValue( json, tipo );
  }

  /** Copia profunda por medio de un arbol Jackson. */
  public static <T> T copia( T o, Class<T> tipo ) {
    if ( o == null ) {
      return null;
    }
    try {
      return MAPPER.treeToValue( MAPPER.valueToTree( o ), tipo );
    } catch ( IOException | IllegalArgumentException e ) {
      throw new IllegalStateException( "No se pudo copiar " + tipo.getSimpleName() + ": " + e.getMessage(), e );
    }
  }
}
