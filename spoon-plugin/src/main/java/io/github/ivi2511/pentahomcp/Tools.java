package io.github.ivi2511.pentahomcp;

import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.McpHttpServer.Tool;
import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;

/**
 * Ayudas para declarar herramientas sin ceremonia: un builder de JSON Schema y lectores de
 * argumentos que fallan con un ToolError legible para la IA.
 *
 * Uso tipico, en cada *Tools.register(server):
 *
 * <pre>
 * s.add( Tools.tool( "move_step", "Moves a step to another position on the canvas.",
 *   Tools.schema().document().str( "step", "Step name", true ).integer( "x", "...", true ).build(),
 *   a -> { ... return resumen; } ) );
 * </pre>
 */
final class Tools {

  private Tools() {
  }

  interface Body {
    Object call( JsonNode args ) throws Exception;
  }

  static Tool tool( final String name, final String description, final ObjectNode schema, final Body body ) {
    return new Tool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return description;
      }

      @Override
      public ObjectNode inputSchema() {
        return schema;
      }

      @Override
      public Object call( JsonNode args ) throws Exception {
        return body.call( args );
      }
    };
  }

  static ToolError error( String msg ) {
    return new ToolError( msg );
  }

  // ---------------------------------------------------------------- JSON Schema

  static Schema schema() {
    return new Schema();
  }

  static final class Schema {
    private final ObjectNode root = McpHttpServer.JSON.createObjectNode();
    private final ObjectNode props;
    private final ArrayNode required;

    private Schema() {
      root.put( "type", "object" );
      props = root.putObject( "properties" );
      required = root.putArray( "required" );
    }

    private Schema prop( String name, String type, String desc, boolean req ) {
      props.putObject( name ).put( "type", type ).put( "description", desc );
      if ( req ) {
        required.add( name );
      }
      return this;
    }

    Schema str( String name, String desc, boolean req ) {
      return prop( name, "string", desc, req );
    }

    Schema integer( String name, String desc, boolean req ) {
      return prop( name, "integer", desc, req );
    }

    Schema bool( String name, String desc, boolean req ) {
      return prop( name, "boolean", desc, req );
    }

    Schema strEnum( String name, String desc, boolean req, String... values ) {
      prop( name, "string", desc, req );
      ArrayNode e = ( (ObjectNode) props.get( name ) ).putArray( "enum" );
      for ( String v : values ) {
        e.add( v );
      }
      return this;
    }

    Schema strList( String name, String desc, boolean req ) {
      prop( name, "array", desc, req );
      ( (ObjectNode) props.get( name ) ).putObject( "items" ).put( "type", "string" );
      return this;
    }

    /** Para propiedades con un schema propio (objetos, arrays de objetos). */
    Schema raw( String name, ObjectNode schema, boolean req ) {
      props.set( name, schema );
      if ( req ) {
        required.add( name );
      }
      return this;
    }

    /** El argumento opcional "document" que aceptan casi todas las herramientas. */
    Schema document() {
      return str( "document", "Transformation or job to work on: its id (T1, J2... from 'status'), "
        + "the file path or file name, or the tab name. If omitted, the active tab.", false );
    }

    ObjectNode build() {
      if ( required.size() == 0 ) {
        root.remove( "required" );
      }
      return root;
    }
  }

  // ---------------------------------------------------------------- argumentos

  static String str( JsonNode a, String k ) throws ToolError {
    JsonNode v = a.get( k );
    if ( v == null || v.isNull() || v.asText().trim().isEmpty() ) {
      throw new ToolError( "Missing argument '" + k + "'" );
    }
    return v.isTextual() ? v.asText() : v.toString();
  }

  static String optStr( JsonNode a, String k, String def ) {
    JsonNode v = a.get( k );
    if ( v == null || v.isNull() ) {
      return def;
    }
    String s = v.isTextual() ? v.asText() : v.toString();
    return s.trim().isEmpty() ? def : s;
  }

  static int integer( JsonNode a, String k ) throws ToolError {
    Integer v = optInteger( a, k );
    if ( v == null ) {
      throw new ToolError( "Missing integer argument '" + k + "'" );
    }
    return v;
  }

  static Integer optInteger( JsonNode a, String k ) throws ToolError {
    JsonNode v = a.get( k );
    if ( v == null || v.isNull() ) {
      return null;
    }
    if ( v.canConvertToInt() ) {
      return v.asInt();
    }
    try {
      return Integer.parseInt( v.asText().trim() );
    } catch ( NumberFormatException e ) {
      throw new ToolError( "'" + k + "' must be an integer, got: " + v );
    }
  }

  static int optInt( JsonNode a, String k, int def ) throws ToolError {
    Integer v = optInteger( a, k );
    return v == null ? def : v;
  }

  static boolean optBool( JsonNode a, String k, boolean def ) {
    JsonNode v = a.get( k );
    if ( v == null || v.isNull() ) {
      return def;
    }
    if ( v.isBoolean() ) {
      return v.asBoolean();
    }
    String s = v.asText().trim().toLowerCase( Locale.ROOT );
    return s.equals( "true" ) || s.equals( "yes" ) || s.equals( "y" ) || s.equals( "1" ) || s.equals( "si" );
  }

  static ObjectNode obj() {
    return McpHttpServer.JSON.createObjectNode();
  }

  static ArrayNode arr() {
    return McpHttpServer.JSON.createArrayNode();
  }
}
