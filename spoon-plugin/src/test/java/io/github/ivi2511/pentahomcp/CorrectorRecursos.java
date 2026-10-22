package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.ivi2511.pentahomcp.corrector.modelo.CorrectorJson;

/**
 * Fixtures del corrector: {@code src/test/resources/corrector} (ver su README.md). Las rutas de {@link #raiz()}
 * son de solo lectura; para corregir, usar {@link #copia()}: varias entregas leen o escriben fuera de su carpeta
 * si se corren sin el sandbox.
 *
 * <pre>
 * File r = CorrectorRecursos.copia();
 * File pack = new File( r, "packs/ventas-por-producto" );
 * File bruno = new File( r, "entregas/ventas-por-producto/bruno_agregado_mal.ktr" );
 * JsonNode esp = CorrectorRecursos.esperado( "ventas-por-producto", "bruno_agregado_mal.ktr" ); // grade 6.5
 * </pre>
 *
 * @dueño C0
 */
public final class CorrectorRecursos {

  public static final String VENTAS = "ventas-por-producto";
  public static final String TABLA = "ventas-a-tabla";
  public static final String INLINE = "ventas-inline";

  private static final AtomicInteger COPIAS = new AtomicInteger();
  private static boolean limpio;

  private CorrectorRecursos() {
  }

  /** src/test/resources/corrector (solo lectura). */
  public static File raiz() {
    return new File( EntornoKettle.recursos(), "corrector" );
  }

  /** La carpeta del pack, o el .json si es la variante de un solo archivo (ventas-inline, invalidos/x). */
  public static File pack( String id ) {
    File d = new File( new File( raiz(), "packs" ), id );
    return d.isDirectory() ? d : new File( new File( raiz(), "packs" ), id + ".json" );
  }

  public static File entregas( String packId ) {
    return new File( new File( raiz(), "entregas" ), packId );
  }

  public static File entrega( String packId, String archivo ) {
    return new File( entregas( packId ), archivo );
  }

  /** resultados/&lt;alumno&gt;.json (formato pentaho-live-grade-result/1, notas del prototipo). */
  public static File resultado( String alumno ) {
    return new File( new File( raiz(), "resultados" ), alumno + ".json" );
  }

  /** Copia fresca de todo corrector/ en {@code <prueba>/corrector/<n>}: cada llamada, una carpeta nueva. */
  public static synchronized File copia() throws IOException {
    File base = new File( EntornoKettle.carpeta(), "corrector" );
    if ( !limpio ) {
      EntornoKettle.borrar( base.toPath() ); // las copias de corridas anteriores
      limpio = true;
    }
    File destino = new File( base, "c" + COPIAS.incrementAndGet() );
    copiar( raiz().toPath(), destino.toPath() );
    return destino;
  }

  /** casos.json. */
  public static JsonNode casos() throws IOException {
    return CorrectorJson.mapper().readTree( new File( raiz(), "casos.json" ) );
  }

  /** El nodo de casos.json de esa entrega (status, reason, grade, cases, tags...), o null. */
  public static JsonNode esperado( String packId, String archivo ) throws IOException {
    for ( JsonNode p : casos().path( "packs" ) ) {
      if ( packId.equals( p.path( "id" ).asText() ) ) {
        JsonNode e = p.path( "expected" ).get( archivo );
        return e == null || e.isMissingNode() ? null : e;
      }
    }
    return null;
  }

  /** Las entregas de un pack con esa etiqueta de casos.json ("correct", "reads_outside"...), en orden. */
  public static List<String> conEtiqueta( String packId, String etiqueta ) throws IOException {
    List<String> l = new ArrayList<String>();
    for ( JsonNode p : casos().path( "packs" ) ) {
      if ( !packId.equals( p.path( "id" ).asText() ) ) {
        continue;
      }
      for ( Iterator<Map.Entry<String, JsonNode>> it = p.path( "expected" ).fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        for ( JsonNode t : e.getValue().path( "tags" ) ) {
          if ( etiqueta.equals( t.asText() ) ) {
            l.add( e.getKey() );
          }
        }
      }
    }
    return l;
  }

  private static void copiar( final Path desde, final Path hacia ) throws IOException {
    Files.walkFileTree( desde, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult preVisitDirectory( Path d, BasicFileAttributes a ) throws IOException {
        Files.createDirectories( hacia.resolve( desde.relativize( d ).toString() ) );
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile( Path f, BasicFileAttributes a ) throws IOException {
        Files.copy( f, hacia.resolve( desde.relativize( f ).toString() ), StandardCopyOption.REPLACE_EXISTING );
        return FileVisitResult.CONTINUE;
      }
    } );
  }
}
