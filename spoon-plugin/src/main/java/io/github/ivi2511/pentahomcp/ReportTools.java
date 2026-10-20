package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.ui.spoon.Spoon;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;

/**
 * Documentar lo que se armo: informe Markdown con diagrama Mermaid (generate_report) e imagen PNG
 * del lienzo (canvas_screenshot).
 *
 * Hilos: en la UI solo se toma una copia (realClone) o se pinta el lienzo; el informe (getStepFields,
 * validar) y la codificacion del PNG corren fuera del hilo de UI.
 */
final class ReportTools {

  private static final long MS_CAMPOS = 60000;
  private static final long MS_VALIDAR = 60000;

  private ReportTools() {
  }

  static void register( McpHttpServer s ) {

    s.add( Tools.tool( "generate_report",
      "Writes a Markdown report of the transformation or job: title, file, description, a Mermaid flowchart "
        + "of the steps/entries and hops (error hops, disabled hops and job conditions marked), a table of steps "
        + "with type and what each one does (files, conditions, keys, SQL), the output fields of every step, "
        + "database connections (never passwords), parameters and canvas notes. Use it when the user wants to "
        + "document or hand in the ETL (university assignment, Obsidian note, README), or to get a readable "
        + "overview of a large document. With 'path' it saves a .md file and returns a short summary; without "
        + "it, it returns the Markdown. Runs nothing and never opens dialogs.",
      Tools.schema().documento()
        .str( "path", "Absolute path of the .md file to write (folders are created). If omitted, the Markdown "
          + "is returned as text.", false )
        .bool( "include_fields", "Include the output fields (name and type) of every step. Default true. Only "
          + "for transformations.", false )
        .bool( "include_validation", "Also run the validation (like 'validate') and add its errors and "
          + "warnings. Default false: it can be slow if steps connect to databases.", false )
        .strEnum( "language", "Language of the section titles and fixed labels (en, es, pt). Default en. "
          + "Names written by the user are kept as they are.", false, "en", "es", "pt" ).build(),
      a -> {
        final ReportMotor.Opciones op = new ReportMotor.Opciones();
        op.idioma = ReportMotor.idioma( Tools.optStr( a, "language", "en" ) );
        op.campos = Tools.optBool( a, "include_fields", true );
        op.msCampos = MS_CAMPOS;
        final boolean validar = Tools.optBool( a, "include_validation", false );
        final String ruta = Tools.optStr( a, "path", null );
        final AbstractMeta copia = copia( a );
        if ( validar ) {
          op.validacion = RunMotor.enSegundoPlano(
            () -> RunMotor.validar( copia, false, copia.getRepository(), copia.getMetaStore() ), MS_VALIDAR, null,
            "The validation" );
        }
        // el calculo de campos ya tiene su propio timeout; el margen es para armar el texto
        ReportMotor.Informe inf = RunMotor.enSegundoPlano( () -> ReportMotor.informe( copia, op ),
          MS_CAMPOS + 30000, null, "The report" );
        if ( ruta == null ) {
          return inf.markdown;
        }
        File f = escribir( ruta, inf.markdown );
        ObjectNode o = Tools.obj();
        o.put( "path", f.getPath() );
        o.put( "document", Docs.nombre( copia ) );
        o.put( copia instanceof JobMeta ? "entries" : "steps", inf.nodos );
        o.put( "hops", inf.hops );
        if ( copia instanceof TransMeta && op.campos ) {
          o.put( "fields_computed", inf.camposOk + "/" + ( inf.camposOk + inf.camposFallidos ) );
        }
        if ( op.validacion != null ) {
          o.put( "validation", op.validacion.path( "errores" ).asInt() + " errors, "
            + op.validacion.path( "advertencias" ).asInt() + " warnings" );
        }
        if ( !inf.avisos.isEmpty() ) {
          ArrayNode av = o.putArray( "warnings" );
          for ( String w : inf.avisos ) {
            av.add( w );
          }
        }
        o.put( "bytes", f.length() );
        return o;
      } ) );

    s.add( Tools.tool( "canvas_screenshot",
      "Returns a PNG image of the canvas of the transformation or job, as Spoon draws it (icons, names, hops, "
        + "notes and, if it ran, the status icons), cropped to the used area. Use it to check how the drawing "
        + "looks after building or rearranging (overlapping steps, crossing hops, labels that do not fit), or "
        + "when the user asks what the canvas looks like. Read-only.",
      Tools.schema().documento()
        .raw( "scale", Tools.obj().put( "type", "number" ).put( "minimum", 0.25 ).put( "maximum", 2.0 )
          .put( "description", "Zoom factor of the image, from 0.25 to 2.0 (default 1.0 = 100%). Use less than "
            + "1 for very large canvases." ), false ).build(),
      a -> {
        final float escala = escala( a );
        final Object[] r = Docs.ui( () -> {
          Spoon sp = Docs.spoon();
          AbstractMeta m = Docs.resolver( a );
          return new Object[] { ReportImagen.pintar( sp, m, escala ), Docs.id( m ), Docs.nombre( m ) };
        } );
        ReportImagen.Pintado p = (ReportImagen.Pintado) r[ 0 ];
        byte[] png = ReportImagen.png( p.datos );
        StringBuilder texto = new StringBuilder( "Canvas of " + r[ 1 ] + " '" + r[ 2 ] + "', scale "
          + String.format( java.util.Locale.ROOT, "%.2f", p.escala ) + ", " + png.length / 1024 + " KB." );
        if ( p.nota != null ) {
          texto.append( ' ' ).append( p.nota ).append( '.' );
        }
        return new McpHttpServer.Imagen( ReportImagen.base64( png ), "image/png", texto.toString() );
      } ) );
  }

  /** "scale" como numero (acepta 1, 1.5 o "0.75"). */
  private static float escala( JsonNode a ) throws ToolError {
    JsonNode v = a.get( "scale" );
    if ( v == null || v.isNull() || v.asText().trim().isEmpty() ) {
      return 1.0f;
    }
    float f;
    try {
      f = v.isNumber() ? (float) v.asDouble() : Float.parseFloat( v.asText().trim() );
    } catch ( NumberFormatException e ) {
      throw new ToolError( "'scale' must be a number between 0.25 and 2.0, got: " + v );
    }
    if ( Float.isNaN( f ) || f < 0.25f || f > 2.0f ) {
      throw new ToolError( "'scale' must be between 0.25 and 2.0, got: " + v );
    }
    return f;
  }

  /** En la UI: resuelve el documento y toma una copia independiente para trabajar fuera de la UI. */
  private static AbstractMeta copia( final JsonNode a ) throws Exception {
    return Docs.ui( () -> {
      AbstractMeta m = Docs.resolver( a );
      Spoon sp = Docs.spoon();
      AbstractMeta c = clonar( m );
      c.setRepository( sp.getRepository() );
      c.setMetaStore( sp.getMetaStore() );
      return c;
    } );
  }

  /**
   * Copia desligada del documento (la misma receta que RunTools): realClone(false) y reenganchar hops y
   * StepErrorMeta a los steps del clon. Sin Spoon, para poder probarla sin GUI.
   */
  static AbstractMeta clonar( AbstractMeta m ) {
    AbstractMeta c;
    if ( m instanceof TransMeta ) {
      TransMeta t = (TransMeta) ( (TransMeta) m ).realClone( false );
      RunMotor.desligar( t );
      c = t;
    } else {
      JobMeta j = (JobMeta) ( (JobMeta) m ).realClone( false );
      RunMotor.desligar( j );
      c = j;
    }
    c.setFilename( m.getFilename() );
    c.setInternalKettleVariables();
    return c;
  }

  private static File escribir( String ruta, String md ) throws Exception {
    File f = new File( ruta ).getAbsoluteFile();
    if ( !f.getName().toLowerCase().endsWith( ".md" ) ) {
      f = new File( f.getPath() + ".md" );
    }
    File dir = f.getParentFile();
    if ( dir != null && !dir.isDirectory() && !dir.mkdirs() ) {
      throw new ToolError( "Could not create the folder " + dir );
    }
    try ( Writer w = new OutputStreamWriter( new FileOutputStream( f ), StandardCharsets.UTF_8 ) ) {
      w.write( md );
    }
    return f;
  }
}
