package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.xml.XMLHandler;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.diff.Diff;
import io.github.ivi2511.pentahomcp.diff.Git;
import io.github.ivi2511.pentahomcp.diff.ResultadoDiff;
import io.github.ivi2511.pentahomcp.diff.Salidas;
import io.github.ivi2511.pentahomcp.diff.Xmls;
import io.github.ivi2511.pentahomcp.informes.Rotulos;

/**
 * La herramienta MCP {@code diff_documents} (diseño E1): compara una transformación o un job abierto en Spoon (o un
 * archivo) con su archivo guardado, una revisión de git u otro archivo, por significado. Devuelve JSON con los cambios
 * y el texto legible, o el texto, o Markdown; con show_on_canvas, además pinta las insignias en el lienzo y abre la
 * lista de cambios en Spoon. Nunca devuelve contraseñas.
 */
final class DiffTools {

  /** compare_with: el archivo guardado del documento abierto. */
  static final String GUARDADO = "saved";

  private DiffTools() {
  }

  /** Una comparación hecha: el resultado y las dos versiones (la de antes hace falta para abrirla en una pestaña). */
  static final class Comparacion {
    ResultadoDiff resultado;
    Diff.Fuente antes;
    Diff.Fuente despues;
  }

  static void register( McpHttpServer s ) {
    s.add( Tools.tool( "diff_documents",
      "Semantic diff of a transformation or job: what changed BY MEANING, not as XML lines. Steps/entries added, "
        + "removed or renamed (recognised by type, configuration and neighbours), property changes with before -> "
        + "after (a Filter rows condition as 'saldo > 1000 -> saldo >= 1000', field lists, SQL line by line, file "
        + "paths), hops (added, removed, enabled, error hop, job condition), error handling, connections (passwords "
        + "and users are never shown, only that they changed), parameters, notes and document properties. Compares "
        + "the open document (or 'path', a file) with compare_with: 'saved' (its file on disk: the unsaved changes), "
        + "'git:REV' (HEAD, HEAD~1, a branch or a commit, via the system git) or the path of another .ktr/.kjb. Box "
        + "moves are ignored unless ignore_positions=false. Use it to review what changed before saving or "
        + "committing, to explain a teammate's change or what a student changed from the template, and to write a "
        + "PR description (format=markdown). show_on_canvas=true also marks the changes on the Spoon canvas "
        + "(+ added, - removed as dashed ghosts, ~ changed) and opens the list of changes in Spoon: only if the "
        + "user wants to see them there. The XML is read, never modified.",
      Tools.schema().document()
        .str( "path", "Compare this .ktr/.kjb file instead of an open document (then compare_with defaults to "
          + "git:HEAD).", false )
        .str( "compare_with", "What to compare with: 'saved' (default for an open document: its file on disk), "
          + "'git:REV' (e.g. git:HEAD, git:HEAD~1, git:main; HEAD alone also works) or the path of another "
          + ".ktr/.kjb (relative paths are resolved against the document's folder).", false )
        .bool( "ignore_positions", "Ignore box positions on the canvas (default true).", false )
        .strEnum( "format", "json (default: the changes as data plus a 'text' field with the readable diff), "
          + "text or markdown (for a PR or a review comment).", false, "json", "text", "markdown" )
        .strEnum( "language", "Language of the readable texts: en (default), es or pt.", false, "en", "es", "pt" )
        .bool( "show_on_canvas", "Also show the changes in Spoon: badges on the canvas of the open document and the "
          + "list of changes (clicking one selects its step). Only for an open document. Default false.", false )
        .build(),
      a -> diff( a ) ) );
  }

  static Object diff( JsonNode a ) throws Exception {
    String idioma = Rotulos.normalizar( Tools.optStr( a, "language", "en" ) );
    boolean ignorar = Tools.optBool( a, "ignore_positions", true );
    String formato = Tools.optStr( a, "format", "json" ).trim().toLowerCase( Locale.ROOT );
    if ( !formato.equals( "json" ) && !formato.equals( "text" ) && !formato.equals( "markdown" ) ) {
      throw new ToolError( "format is json, text or markdown" );
    }
    boolean mostrar = Tools.optBool( a, "show_on_canvas", false );
    String ruta = Tools.optStr( a, "path", null );
    Comparacion c;
    final AbstractMeta[] abierto = new AbstractMeta[ 1 ];
    if ( ruta != null ) {
      if ( mostrar ) {
        throw new ToolError( "show_on_canvas works with an open document: open the file first (open_file) or "
          + "leave out 'path'" );
      }
      File f = new File( ruta ).getAbsoluteFile();
      if ( !f.isFile() || !esKettle( f.getName() ) ) {
        throw new ToolError( "Not a .ktr/.kjb file: " + f );
      }
      String con = Tools.optStr( a, "compare_with", "git:HEAD" );
      if ( GUARDADO.equalsIgnoreCase( con.trim() ) ) {
        throw new ToolError( "compare_with='saved' is for an open document; with 'path' use git:REV or another "
          + "file" );
      }
      c = comparar( comoKettle( Diff.archivo( f ) ), f, con, ignorar );
    } else {
      final String[] datos = new String[ 3 ]; // xml, archivo, etiqueta
      Docs.ui( () -> {
        AbstractMeta m = Docs.resolverSinActivar( a );
        abierto[ 0 ] = m;
        datos[ 0 ] = XMLHandler.getXMLHeader() + m.getXML();
        datos[ 1 ] = m.getFilename();
        datos[ 2 ] = etiquetaAbierto( m, idioma );
        return null;
      } );
      String con = Tools.optStr( a, "compare_with", GUARDADO );
      File f = datos[ 1 ] == null ? null : new File( datos[ 1 ] );
      c = comparar( Diff.Fuente.texto( datos[ 0 ], datos[ 2 ] ), f, con, ignorar );
    }
    if ( mostrar ) {
      final Comparacion cc = c;
      Docs.ui( () -> {
        Docs.activar( abierto[ 0 ] );
        IdcComparar.mostrar( Docs.spoon(), abierto[ 0 ], cc, false, idioma );
        return null;
      } );
    }
    if ( formato.equals( "text" ) ) {
      return Salidas.texto( c.resultado, idioma );
    }
    if ( formato.equals( "markdown" ) ) {
      return Salidas.markdown( c.resultado, idioma );
    }
    ObjectNode o = (ObjectNode) McpHttpServer.JSON.readTree( Salidas.json( c.resultado, idioma ) );
    o.put( "text", Salidas.texto( c.resultado, idioma ) );
    if ( mostrar ) {
      o.put( "shown_on_canvas", true );
    }
    return o;
  }

  /** «abierto en Spoon» o «abierto en Spoon (con cambios sin guardar)». (UI) */
  static String etiquetaAbierto( AbstractMeta m, String idioma ) {
    return Salidas.rotulos( idioma ).s( m.hasChanged() ? "src.open_unsaved" : "src.open" );
  }

  /**
   * Compara {@code despues} (un archivo o el XML de un documento abierto, cuyo archivo es {@code archivo}, o null si
   * no tiene) con {@code compararCon}: "saved", "git:REV"/"HEAD..." o la ruta de otro archivo. Fuera del hilo de UI.
   */
  static Comparacion comparar( Diff.Fuente despues, File archivo, String compararCon, boolean ignorarPos )
    throws ToolError {
    String con = compararCon == null || compararCon.trim().isEmpty() ? GUARDADO : compararCon.trim();
    Comparacion c = new Comparacion();
    c.despues = despues;
    try {
      if ( GUARDADO.equalsIgnoreCase( con ) ) {
        if ( archivo == null ) {
          throw new ToolError( "The document has no file yet: there is no saved version to compare with (save it, "
            + "or pass compare_with with a file or git:REV)" );
        }
        if ( !archivo.isFile() ) {
          throw new ToolError( "The saved file no longer exists: " + archivo );
        }
        Diff.Fuente f = Diff.archivo( archivo );
        c.antes = new Diff.Fuente( f.xml, archivo.getPath() );
      } else {
        String rev = Diff.revision( con );
        if ( rev != null && archivo == null ) {
          throw new ToolError( "The document has no file yet, so it is in no git repository: save it first" );
        }
        c.antes = Diff.referencia( con, archivo );
      }
      c.antes = comoKettle( c.antes );
      c.resultado = Diff.comparar( c.antes, despues, ignorarPos );
      return c;
    } catch ( Git.GitError e ) {
      throw new ToolError( e.getMessage() );
    } catch ( Xmls.XmlInvalido e ) {
      throw new ToolError( e.getMessage() );
    } catch ( java.io.IOException e ) {
      throw new ToolError( e.getMessage() );
    }
  }

  /**
   * El XML como lo guardaría Spoon: cargado con Kettle y escrito con getXML(). El documento abierto ya sale así; un
   * archivo escrito a mano (o por otra herramienta, o por otra versión de PDI) no trae los tags con su valor por defecto
   * que Kettle agrega al guardar, y sin esto cada uno saldría como un cambio. Si Kettle no lo puede cargar, el XML tal
   * cual. Fuera del hilo de UI.
   */
  static Diff.Fuente comoKettle( Diff.Fuente f ) {
    try {
      String raiz = Xmls.parsear( f.xml ).getDocumentElement().getTagName();
      org.pentaho.di.base.AbstractMeta m;
      if ( "job".equals( raiz ) ) {
        m = new org.pentaho.di.job.JobMeta( new java.io.ByteArrayInputStream( f.xml ), null, null );
      } else if ( "transformation".equals( raiz ) ) {
        m = new org.pentaho.di.trans.TransMeta( new java.io.ByteArrayInputStream( f.xml ), null, false, null, null );
      } else {
        return f;
      }
      return Diff.Fuente.texto( XMLHandler.getXMLHeader() + m.getXML(), f.etiqueta );
    } catch ( Throwable t ) {
      return f;
    }
  }

  static boolean esKettle( String n ) {
    String x = n.toLowerCase( Locale.ROOT );
    return x.endsWith( ".ktr" ) || x.endsWith( ".kjb" );
  }
}
