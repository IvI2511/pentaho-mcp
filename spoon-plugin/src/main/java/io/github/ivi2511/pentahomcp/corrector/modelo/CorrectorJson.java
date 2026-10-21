package io.github.ivi2511.pentahomcp.corrector.modelo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * El ObjectMapper del corrector (pack.json, trabajo.json y resultados), para que el worker, el padre, las tools
 * MCP y las pruebas lean y escriban igual (docs/corrector-diseno.md §2 y §6). Al leer se ignoran las propiedades
 * desconocidas (los formatos /1 pueden crecer), se aceptan comentarios de linea y de bloque (el docente edita
 * pack.json a mano, como el ejemplo jsonc del §2.2) y un BOM UTF-8 al principio (Bloc de notas).
 * Claves JSON en ingles y snake_case; campos Java en castellano.
 *
 * @dueño C0
 */
public final class CorrectorJson {

  private static final ObjectMapper MAPPER = new ObjectMapper()
    .configure( JsonParser.Feature.ALLOW_COMMENTS, true )
    .configure( DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false )
    .configure( SerializationFeature.FAIL_ON_EMPTY_BEANS, false );

  /** Reintentos del rename en Windows (el padre puede estar leyendo el archivo justo en ese momento). */
  private static final int REINTENTOS = 20;

  private CorrectorJson() {
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
    if ( json != null && !json.isEmpty() && json.charAt( 0 ) == '﻿' ) {
      json = json.substring( 1 );
    }
    return MAPPER.readValue( json, tipo );
  }

  /** Lee un archivo JSON en UTF-8, con o sin BOM. */
  public static <T> T leer( File f, Class<T> tipo ) throws IOException {
    byte[] b = Files.readAllBytes( f.toPath() );
    int ini = b.length >= 3 && ( b[ 0 ] & 0xFF ) == 0xEF && ( b[ 1 ] & 0xFF ) == 0xBB && ( b[ 2 ] & 0xFF ) == 0xBF ? 3 : 0;
    return MAPPER.readValue( b, ini, b.length - ini, tipo );
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

  /**
   * Escribe el JSON indentado de forma atomica (diseño §10): primero un temporal en la misma carpeta, despues
   * rename sobre el destino. Quien mira la carpeta nunca ve un JSON a medias. El temporal se escribe con un
   * FileOutputStream propio que se cierra siempre (P12: en Windows un stream abierto traba el borrado).
   */
  public static void guardar( Object o, File destino ) throws IOException {
    File dir = destino.getAbsoluteFile().getParentFile();
    if ( dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory() ) {
      throw new IOException( "No se pudo crear la carpeta " + dir );
    }
    byte[] b = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes( o );
    File tmp = new File( dir, "." + destino.getName() + "." + System.nanoTime() + ".tmp" );
    try ( OutputStream os = new FileOutputStream( tmp ) ) {
      os.write( b );
    }
    IOException ultima = null;
    for ( int i = 0; i < REINTENTOS; i++ ) {
      try {
        try {
          Files.move( tmp.toPath(), destino.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING );
        } catch ( AtomicMoveNotSupportedException e ) {
          Files.move( tmp.toPath(), destino.toPath(), StandardCopyOption.REPLACE_EXISTING );
        }
        return;
      } catch ( IOException e ) {
        ultima = e;
        try {
          Thread.sleep( 50 );
        } catch ( InterruptedException ie ) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    if ( !tmp.delete() ) {
      tmp.deleteOnExit();
    }
    throw ultima != null ? ultima : new IOException( "No se pudo escribir " + destino );
  }
}
