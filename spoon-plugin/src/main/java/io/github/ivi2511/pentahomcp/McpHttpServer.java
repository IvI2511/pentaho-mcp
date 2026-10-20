package io.github.ivi2511.pentahomcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Servidor MCP sobre "Streamable HTTP": un unico endpoint POST /mcp con JSON-RPC y respuesta en
 * application/json (sin SSE, no hace falta: no mandamos notificaciones al cliente). Solo escucha
 * en 127.0.0.1 y rechaza Origin que no sea localhost (DNS rebinding).
 *
 * GET /ping queda para diagnostico rapido con curl.
 */
final class McpHttpServer {

  /** Una herramienta que la IA puede llamar. */
  interface Tool {
    String name();

    String description();

    ObjectNode inputSchema();

    /**
     * Devuelve un String (va tal cual), una {@link Imagen} (bloque image + texto opcional) o cualquier
     * otro objeto (va como JSON compacto).
     */
    Object call( JsonNode args ) throws Exception;
  }

  /** Resultado con imagen: content = [{type:image, data, mimeType}, {type:text, text}] (MCP estandar). */
  static final class Imagen {
    final String base64;
    final String mime;
    /** Texto que acompania la imagen; null para mandar solo la imagen. */
    final String texto;

    Imagen( String base64, String mime, String texto ) {
      this.base64 = base64;
      this.mime = mime;
      this.texto = texto;
    }
  }

  /** Error esperado (argumento mal, step inexistente...): llega a la IA como isError, sin pila. */
  static final class ToolError extends Exception {
    ToolError( String msg ) {
      super( msg );
    }
  }

  /**
   * Error de protocolo con codigo JSON-RPC propio (p. ej. -32002 recurso inexistente, -32602 cursor
   * invalido): sale como "error" de la respuesta, con data opcional.
   */
  static final class RpcError extends Exception {
    final int code;
    final JsonNode data;

    RpcError( int code, String msg, JsonNode data ) {
      super( msg );
      this.code = code;
      this.data = data;
    }
  }

  static final ObjectMapper JSON = new ObjectMapper();

  private static final String[] VERSIONES = { "2025-06-18", "2025-03-26", "2024-11-05" };

  private final int port;
  private final String version;
  private final String instrucciones;
  private final Map<String, Tool> tools = new LinkedHashMap<String, Tool>();
  /** Catalogo y guias como recursos MCP; null = sin recursos (listas vacias). */
  private volatile McpResources recursos;
  private HttpServer http;
  private ExecutorService pool;

  McpHttpServer( int port, String version, String instrucciones ) {
    this.port = port;
    this.version = version;
    this.instrucciones = instrucciones;
  }

  void resources( McpResources r ) {
    this.recursos = r;
  }

  void add( Tool t ) {
    if ( tools.containsKey( t.name() ) ) { // mejor una herramienta menos que el servidor caido
      McpLifecycleListener.LOG.logError( "Pentaho MCP: herramienta duplicada, se ignora: " + t.name() );
      return;
    }
    tools.put( t.name(), t );
  }

  int toolCount() {
    return tools.size();
  }

  void start() throws IOException {
    http = HttpServer.create( new InetSocketAddress( InetAddress.getLoopbackAddress(), port ), 0 );
    pool = Executors.newCachedThreadPool( new ThreadFactory() {
      private final AtomicInteger n = new AtomicInteger();

      @Override
      public Thread newThread( Runnable r ) {
        Thread t = new Thread( r, "pentaho-mcp-http-" + n.incrementAndGet() );
        t.setDaemon( true ); // no impedir que la JVM de Spoon termine
        return t;
      }
    } );
    http.setExecutor( pool );
    http.createContext( "/mcp", ex -> {
      try {
        manejar( ex );
      } catch ( Throwable t ) {
        responder( ex, 500, error( null, -32603, String.valueOf( t ) ).toString() );
      }
    } );
    http.createContext( "/ping", ex -> responder( ex, 200,
      "{\"ok\":true,\"spoon\":" + ( UiThread.spoon() != null ) + ",\"tools\":" + tools.size() + "}" ) );
    http.start();
  }

  void stop() {
    if ( http != null ) {
      http.stop( 0 );
    }
    if ( pool != null ) {
      pool.shutdownNow();
    }
  }

  private void manejar( HttpExchange ex ) throws IOException {
    String origin = ex.getRequestHeaders().getFirst( "Origin" );
    if ( origin != null && !origin.matches( "^https?://(localhost|127\\.0\\.0\\.1|\\[::1\\])(:\\d+)?$" ) ) {
      responder( ex, 403, "{\"error\":\"Origin not allowed\"}" );
      return;
    }
    String metodo = ex.getRequestMethod();
    if ( "DELETE".equals( metodo ) ) { // fin de sesion: no guardamos estado por sesion
      responder( ex, 200, "" );
      return;
    }
    if ( !"POST".equals( metodo ) ) {
      ex.getResponseHeaders().set( "Allow", "POST, DELETE" );
      responder( ex, 405, "" );
      return;
    }
    JsonNode msg;
    try {
      msg = JSON.readTree( leer( ex.getRequestBody() ) );
    } catch ( Exception e ) {
      responder( ex, 400, error( null, -32700, "Invalid JSON: " + e.getMessage() ).toString() );
      return;
    }
    if ( msg != null && msg.isArray() ) { // lotes: solo versiones viejas del protocolo
      ArrayNode salida = JSON.createArrayNode();
      for ( JsonNode m : msg ) {
        ObjectNode r = despachar( m, ex );
        if ( r != null ) {
          salida.add( r );
        }
      }
      responder( ex, salida.size() == 0 ? 202 : 200, salida.size() == 0 ? "" : salida.toString() );
      return;
    }
    ObjectNode r = msg == null ? null : despachar( msg, ex );
    responder( ex, r == null ? 202 : 200, r == null ? "" : r.toString() );
  }

  /** null para notificaciones y respuestas, que no llevan respuesta. */
  private ObjectNode despachar( JsonNode m, HttpExchange ex ) {
    JsonNode id = m.get( "id" );
    String metodo = m.path( "method" ).asText( null );
    if ( metodo == null || id == null || id.isNull() ) {
      return null;
    }
    JsonNode params = m.path( "params" );
    try {
      switch ( metodo ) {
        case "initialize": {
          String pedida = params.path( "protocolVersion" ).asText( "" );
          String elegida = VERSIONES[0];
          for ( String v : VERSIONES ) {
            if ( v.equals( pedida ) ) {
              elegida = v;
            }
          }
          ObjectNode res = JSON.createObjectNode();
          res.put( "protocolVersion", elegida );
          ObjectNode caps = res.putObject( "capabilities" );
          caps.putObject( "tools" ).put( "listChanged", false );
          if ( recursos != null ) {
            caps.putObject( "resources" ).put( "listChanged", false );
            caps.putObject( "completions" ); // ids de las plantillas pentaho://catalog/.../{id}
          }
          res.putObject( "serverInfo" ).put( "name", "pentaho" ).put( "version", version );
          if ( instrucciones != null ) {
            res.put( "instructions", instrucciones );
          }
          ex.getResponseHeaders().set( "Mcp-Session-Id", UUID.randomUUID().toString() );
          return ok( id, res );
        }
        case "ping":
          return ok( id, JSON.createObjectNode() );
        case "tools/list": {
          ObjectNode res = JSON.createObjectNode();
          ArrayNode arr = res.putArray( "tools" );
          for ( Tool t : tools.values() ) {
            ObjectNode o = arr.addObject();
            o.put( "name", t.name() );
            o.put( "description", t.description() );
            o.set( "inputSchema", t.inputSchema() );
          }
          return ok( id, res );
        }
        case "tools/call": {
          String nombre = params.path( "name" ).asText();
          Tool t = tools.get( nombre );
          if ( t == null ) {
            return error( id, -32602, "Unknown tool: " + nombre );
          }
          JsonNode args = params.has( "arguments" ) ? params.get( "arguments" ) : JSON.createObjectNode();
          return ok( id, ejecutar( t, args ) );
        }
        case "resources/list": {
          McpResources r = recursos;
          return ok( id, r == null ? JSON.createObjectNode().set( "resources", JSON.createArrayNode() )
            : r.listar( cursor( params ) ) );
        }
        case "resources/templates/list": {
          McpResources r = recursos;
          return ok( id, r == null ? JSON.createObjectNode().set( "resourceTemplates", JSON.createArrayNode() )
            : r.plantillas( cursor( params ) ) );
        }
        case "resources/read": {
          JsonNode u = params.get( "uri" );
          if ( u == null || !u.isTextual() || u.asText().isEmpty() ) {
            return error( id, -32602, "Missing argument 'uri'" );
          }
          McpResources r = recursos;
          ObjectNode res = r == null ? null : r.leer( u.asText() );
          if ( res == null ) {
            throw new RpcError( -32002, "Resource not found", JSON.createObjectNode().put( "uri", u.asText() ) );
          }
          return ok( id, res );
        }
        case "completion/complete": {
          McpResources r = recursos;
          if ( r == null ) {
            return error( id, -32601, "Unsupported method: " + metodo );
          }
          return ok( id, r.completar( params ) );
        }
        case "prompts/list":
          return ok( id, JSON.createObjectNode().set( "prompts", JSON.createArrayNode() ) );
        default:
          return error( id, -32601, "Unsupported method: " + metodo );
      }
    } catch ( RpcError e ) {
      ObjectNode r = error( id, e.code, e.getMessage() );
      if ( e.data != null ) {
        ( (ObjectNode) r.get( "error" ) ).set( "data", e.data );
      }
      return r;
    } catch ( Throwable e ) {
      return error( id, -32603, String.valueOf( e ) );
    }
  }

  private ObjectNode ejecutar( Tool t, JsonNode args ) {
    ObjectNode res = JSON.createObjectNode();
    ArrayNode content = res.putArray( "content" );
    try {
      Object salida = t.call( args );
      if ( salida instanceof Imagen ) {
        Imagen img = (Imagen) salida;
        content.addObject().put( "type", "image" ).put( "data", img.base64 ).put( "mimeType", img.mime );
        if ( img.texto != null ) {
          content.addObject().put( "type", "text" ).put( "text", img.texto );
        }
      } else {
        // compacto: cada caracter de mas son tokens que paga la IA
        String texto = salida instanceof String ? (String) salida : JSON.writeValueAsString( salida );
        content.addObject().put( "type", "text" ).put( "text", texto );
      }
      res.put( "isError", false );
    } catch ( ToolError e ) {
      content.addObject().put( "type", "text" ).put( "text", "Error: " + e.getMessage() );
      res.put( "isError", true );
    } catch ( Throwable e ) {
      Throwable causa = e;
      while ( causa.getCause() != null && causa.getCause() != causa ) {
        causa = causa.getCause();
      }
      StringWriter sw = new StringWriter();
      causa.printStackTrace( new PrintWriter( sw ) );
      String pila = sw.toString();
      if ( pila.length() > 3000 ) {
        pila = pila.substring( 0, 3000 ) + "\n...";
      }
      McpLifecycleListener.LOG.logError( "Pentaho MCP: fallo " + t.name(), e );
      content.addObject().put( "type", "text" ).put( "text", "Unexpected error in " + t.name() + ": " + causa
        + "\n" + pila );
      res.put( "isError", true );
    }
    return res;
  }

  /** params.cursor de las listas paginadas; null si no vino. */
  private static String cursor( JsonNode params ) {
    JsonNode c = params.get( "cursor" );
    return c == null || c.isNull() ? null : c.asText();
  }

  private static ObjectNode ok( JsonNode id, JsonNode result ) {
    ObjectNode o = JSON.createObjectNode();
    o.put( "jsonrpc", "2.0" );
    o.set( "id", id );
    o.set( "result", result );
    return o;
  }

  private static ObjectNode error( JsonNode id, int code, String msg ) {
    ObjectNode o = JSON.createObjectNode();
    o.put( "jsonrpc", "2.0" );
    if ( id == null ) {
      o.putNull( "id" );
    } else {
      o.set( "id", id );
    }
    o.putObject( "error" ).put( "code", code ).put( "message", msg );
    return o;
  }

  private static String leer( InputStream in ) throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    byte[] b = new byte[8192];
    int n;
    while ( ( n = in.read( b ) ) > 0 ) {
      buf.write( b, 0, n );
    }
    return new String( buf.toByteArray(), StandardCharsets.UTF_8 );
  }

  private static void responder( HttpExchange ex, int code, String cuerpo ) throws IOException {
    byte[] b = cuerpo.getBytes( StandardCharsets.UTF_8 );
    if ( b.length > 0 ) {
      ex.getResponseHeaders().set( "Content-Type", "application/json; charset=utf-8" );
    }
    ex.sendResponseHeaders( code, b.length == 0 ? -1 : b.length );
    if ( b.length > 0 ) {
      try ( OutputStream os = ex.getResponseBody() ) {
        os.write( b );
      }
    }
    ex.close();
  }
}
