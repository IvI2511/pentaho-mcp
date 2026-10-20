package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.i18n.LanguageChoice;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.ui.spoon.Spoon;
import org.pentaho.di.ui.spoon.SpoonPerspective;
import org.pentaho.di.ui.spoon.SpoonPerspectiveManager;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.informes.Captura;
import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Pedido;
import io.github.ivi2511.pentahomcp.informes.Progreso;
import io.github.ivi2511.pentahomcp.informes.Resultado;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.informes.SeccionId;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.Grafico;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.ModeloJson;
import io.github.ivi2511.pentahomcp.informes.modelo.Narrativa;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.tema.Ajustes;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;
import io.github.ivi2511.pentahomcp.informes.ui.PanelInformes;

/**
 * Documentar lo que se armo: informe Markdown con diagrama Mermaid (generate_report) e imagen PNG
 * del lienzo (canvas_screenshot); desde 0.5.0, los informes completos de la perspectiva Informes
 * (build_report, get_report_data y show_report, abajo).
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
      Tools.schema().document()
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
        final String[] id = new String[ 1 ];
        final AbstractMeta copia = copia( a, id );
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
        o.put( "saved_to", f.getPath() );
        o.put( "document", id[ 0 ] );
        o.put( "name", Docs.nombre( copia ) );
        o.put( copia instanceof JobMeta ? "entries" : "steps", inf.nodos );
        o.put( "hops", inf.hops );
        if ( copia instanceof TransMeta && op.campos ) {
          o.put( "fields_computed", inf.camposOk + "/" + ( inf.camposOk + inf.camposFallidos ) );
        }
        if ( op.validacion != null ) {
          o.put( "validation", op.validacion.path( "errors" ).asInt() + " errors, "
            + op.validacion.path( "warnings" ).asInt() + " warnings" );
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
      Tools.schema().document()
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

    registrarInformes( s );
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

  /**
   * En la UI: resuelve el documento y toma una copia independiente para trabajar fuera de la UI. En
   * id[0] deja el id del documento original (el clon tendria otro).
   */
  private static AbstractMeta copia( final JsonNode a, final String[] id ) throws Exception {
    return Docs.ui( () -> {
      AbstractMeta m = Docs.resolver( a );
      id[ 0 ] = Docs.id( m );
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

  // ======================================================================== informes (0.5.0)
  //
  // build_report, get_report_data y show_report (diseno §1.5). Usan el motor de la perspectiva
  // Informes (InformeServicio). Hilos: en la UI solo se resuelve el documento y se toma la Captura
  // (copia desligada y lienzo, sin activar la pestania ni cambiar de perspectiva); el armado y la
  // exportacion corren fuera de la UI. show_report es la unica que cambia lo que el usuario ve.

  /** Id de la perspectiva Informes. */
  static final String PERSPECTIVA_INFORMES = ReportsPerspective.ID;

  /** Tope de cada texto de la narrativa (diseno §1.5). */
  static final int MAX_NARRATIVA = 2000;
  /** Filas por tabla en get_report_data, por defecto y como maximo. */
  static final int FILAS_DATOS = 50;
  static final int FILAS_DATOS_MAX = 1000;
  /** Tope de profile_rows. */
  static final int PERFIL_MAX = 100000;
  /** Margen sobre Pedido.timeoutMs para esperar el armado (cada seccion ya tiene su propio tope). */
  private static final long MS_MARGEN = 30000;

  /** Las tres maneras de leer los argumentos: cada tool acepta un subconjunto. */
  enum Modo {
    CONSTRUIR, DATOS, MOSTRAR
  }

  /** Lo que pidio la IA, ya validado. */
  static final class Peticion {
    final Pedido pedido = new Pedido();
    final List<Formato> formatos = new ArrayList<>();
    String carpeta;
    String archivo;
    boolean mostrar;
    int maxFilas = FILAS_DATOS;
    /** true si vino "sections": no se completa con los defaults. */
    boolean seccionesExplicitas;
    final List<String> avisos = new ArrayList<>();
  }

  /** Progreso de una tool: no muestra nada; se cancela si se vence el plazo. */
  static final class ProgresoMcp implements Progreso {
    private final AtomicBoolean cancelado = new AtomicBoolean();

    @Override
    public void paso( String texto, int hecho, int total ) {
      // por MCP no hay barra de progreso
    }

    @Override
    public boolean cancelado() {
      return cancelado.get();
    }

    void cancelar() {
      cancelado.set( true );
    }
  }

  private static void registrarInformes( McpHttpServer s ) {

    s.add( Tools.tool( "build_report",
      "Builds a complete report of the transformation or job and writes it to files: cover, summary, flow "
        + "diagram (the real canvas plus a Mermaid diagram), field lineage, data dictionary, target table model, "
        + "data profile, run metrics with charts, validation, connections and parameters (never passwords), and "
        + "an optional narrative. Formats: html (one self-contained file with interactive diagrams), html_light "
        + "(no JavaScript, much smaller), pdf (needs Edge or Chrome; without them you get a printable HTML and a "
        + "warning), md (Markdown plus a folder with the images, for Obsidian or GitHub), pptx (PowerPoint), json "
        + "(the report data). Use it when the user wants to document, present or hand in an ETL. To add a "
        + "narrative, call get_report_data first and write it from the real numbers. It does not switch tabs or "
        + "perspectives (show_in_spoon=true only if the user asks to see it in Spoon). By default it runs "
        + "nothing: metrics=run_copy and profile_step EXECUTE a copy of the transformation and its output steps "
        + "WRITE their files and tables, so use them only if the user agreed. Returns the files written, the "
        + "status of each section and the warnings.",
      esquema( Modo.CONSTRUIR ),
      a -> {
        Tema tema = Ajustes.cargar();
        Peticion op = peticion( a, tema, Modo.CONSTRUIR );
        Captura c = capturar( a );
        final Informe[] hecho = new Informe[ 1 ];
        ObjectNode o = construir( c, op, tema, hecho );
        if ( op.mostrar ) {
          try {
            final Pedido p = op.pedido;
            // el panel muestra el informe ya armado: armarlo otra vez volveria a ejecutar el perfil
            Docs.ui( () -> {
              mostrarEnSpoon( Docs.spoon(), c.docId, p, hecho[ 0 ] );
              return null;
            } );
            o.put( "shown_in_spoon", true );
          } catch ( Exception e ) {
            ( (ArrayNode) o.get( "warnings" ) ).add( "show_in_spoon: " + mensaje( e ) );
          }
        }
        return o;
      } ) );

    s.add( Tools.tool( "get_report_data",
      "Returns the data of the report as JSON, without images: each section with its title, automatic "
        + "summary, status (ok, partial, skipped, error) and reason, and its blocks (paragraphs, lists, tables, "
        + "indicators, chart series, Mermaid text), plus the raw data of the section (lineage paths, run "
        + "metrics, target columns...). Use it before build_report when you are going to write a narrative: base "
        + "every statement on these values, then pass the text in build_report's 'narrative'. Long tables and "
        + "lists are cut to max_rows. It computes fields and validates like build_report, but writes no files, "
        + "never runs the transformation and does not switch tabs or perspectives.",
      esquema( Modo.DATOS ),
      a -> {
        Tema tema = Ajustes.cargar();
        Peticion op = peticion( a, tema, Modo.DATOS );
        return datos( capturar( a ), op );
      } ) );

    s.add( Tools.tool( "show_report",
      "Brings Spoon's Reports perspective to the front with a live preview of the report of this document; "
        + "the user then exports it from the panel. Call it ONLY when the user asks to see the report in Spoon: "
        + "it changes what the user is looking at. To write the files yourself use build_report. The editing "
        + "tools bring the user back to the Data Integration perspective.",
      esquema( Modo.MOSTRAR ),
      a -> {
        Tema tema = Ajustes.cargar();
        final Peticion op = peticion( a, tema, Modo.MOSTRAR );
        return Docs.ui( () -> {
          Spoon sp = Docs.spoon();
          AbstractMeta m = Docs.resolverSinActivar( a );
          String id = Docs.id( m );
          mostrarEnSpoon( sp, id, op.pedido );
          ObjectNode o = Tools.obj();
          o.put( "document", id );
          o.put( "name", Docs.nombre( m ) );
          o.put( "perspective", "reports" );
          o.put( "language", op.pedido.idioma );
          if ( op.pedido.secciones != null ) {
            ArrayNode ss = o.putArray( "sections" );
            for ( String x : op.pedido.secciones ) {
              ss.add( x );
            }
          }
          return o;
        } );
      } ) );
  }

  // ---------------------------------------------------------------- esquemas

  private static ObjectNode esquema( Modo modo ) {
    Tools.Schema sc = Tools.schema().document();
    sc.raw( "sections", listaEnum( "Section ids to include, in any order: cover, summary, flow (transformations), "
      + "job_flow (jobs), lineage (transformations), dictionary (transformations), target_model (transformations "
      + "with Table output or Insert/Update), profile, metrics, validation, connections, narrative. Default: the "
      + "sections marked by default in the user's settings that apply to this document (metrics only if it ran "
      + "in Spoon"
      + ( modo == Modo.CONSTRUIR ? " or with metrics=run_copy; profile only with profile_step; narrative only "
        + "with narrative.conclusions)." : modo == Modo.DATOS ? "). profile is not available here: it runs the "
          + "transformation (use build_report with profile_step)." : ")." ), SeccionId.ORDEN, false ), false );
    if ( modo == Modo.CONSTRUIR ) {
      List<String> fs = new ArrayList<>();
      for ( Formato f : Formato.values() ) {
        fs.add( f.clave );
      }
      sc.raw( "formats", listaEnum( "Formats to write: html, html_light, pdf, md, pptx, json (one or more).", fs,
        true ), true );
      sc.str( "output_dir", "Absolute folder for the files (created if missing); a relative path is an error. "
        + "Default: the folder in the user's report settings, or else an 'informes' folder next to the "
        + ".ktr/.kjb.", false );
      sc.str( "file_name", "Base name of the files, without extension (default: the name of the .ktr/.kjb). "
        + "Existing files with the same name are overwritten, so links to them keep working.", false );
    }
    sc.strEnum( "language", "Language of the report texts (titles, labels, automatic summaries): en, es or pt. "
      + "Default: the one in the user's report settings, or else Spoon's language. Names written by the user "
      + "are kept as they are.", false, "en", "es", "pt" );
    if ( modo == Modo.CONSTRUIR ) {
      sc.str( "title", "Report title. Default: the name of the transformation or job.", false );
    }
    if ( modo != Modo.DATOS ) {
      sc.raw( "narrative", esquemaNarrativa(), false );
    }
    if ( modo == Modo.CONSTRUIR ) {
      sc.str( "lineage_step", "Step whose output fields the lineage traces back to their origin. Default: "
        + "every final step that writes.", false );
      sc.str( "profile_step", "Step whose output is profiled (nulls, distinct values, min, max, most frequent "
        + "values). Adds the profile section. It RUNS a copy of the transformation up to that step, reading "
        + "profile_rows rows; output steps upstream of it write their files or tables. Only if the user agreed.",
        false );
      sc.raw( "profile_rows", Tools.obj().put( "type", "integer" ).put( "minimum", 1 ).put( "maximum", PERFIL_MAX )
        .put( "description", "How many rows the profile reads (the first N). Default 5000." ), false );
      sc.strEnum( "metrics", "Source of the run metrics: last_run (default: the last run made in Spoon, if "
        + "any), run_copy (RUNS a copy of the transformation or job now and measures it; its output steps WRITE "
        + "their files and tables: only if the user agreed) or none.", false, Pedido.METRICAS_ULTIMA,
        Pedido.METRICAS_COPIA, Pedido.METRICAS_NINGUNA );
      sc.bool( "query_database", "target_model also connects to the database to read primary and foreign "
        + "keys of the target tables (read-only; a missing SQLite file is never created). Default false.", false );
      sc.bool( "show_in_spoon", "Also open the Reports perspective with this report. Only if the user asked to "
        + "see it in Spoon. Default false.", false );
    }
    if ( modo == Modo.DATOS ) {
      sc.raw( "max_rows", Tools.obj().put( "type", "integer" ).put( "minimum", 1 ).put( "maximum", FILAS_DATOS_MAX )
        .put( "description", "Maximum rows per table, items per list and points per chart (longer ones are cut "
          + "and their full length is given in '<name>_total'). Default " + FILAS_DATOS + "." ), false );
    }
    return sc.build();
  }

  /** {type: array, items: {type: string, enum}, uniqueItems}. */
  private static ObjectNode listaEnum( String desc, List<String> valores, boolean alMenosUno ) {
    ObjectNode o = Tools.obj().put( "type", "array" ).put( "description", desc );
    ArrayNode e = o.putObject( "items" ).put( "type", "string" ).putArray( "enum" );
    for ( String v : valores ) {
      e.add( v );
    }
    o.put( "uniqueItems", true );
    if ( alMenosUno ) {
      o.put( "minItems", 1 );
    }
    return o;
  }

  private static ObjectNode esquemaNarrativa() {
    ObjectNode o = Tools.obj().put( "type", "object" ).put( "description", "Optional text written by you, shown "
      + "as AI-assisted. Plain text with a little Markdown: paragraphs, '- ' bullets and **bold**; no HTML. "
      + "At most " + MAX_NARRATIVA + " characters per text. Write it in the report language and only from "
      + "facts in get_report_data." );
    ObjectNode pr = o.putObject( "properties" );
    pr.putObject( "summary" ).put( "type", "string" ).put( "maxLength", MAX_NARRATIVA )
      .put( "description", "Goes at the start of the Summary section." );
    ObjectNode ss = pr.putObject( "sections" ).put( "type", "object" ).put( "description", "Section id -> text "
      + "that goes at the start of that section, e.g. {\"lineage\": \"...\", \"metrics\": \"...\"}." );
    ss.putObject( "additionalProperties" ).put( "type", "string" ).put( "maxLength", MAX_NARRATIVA );
    pr.putObject( "conclusions" ).put( "type", "string" ).put( "maxLength", MAX_NARRATIVA )
      .put( "description", "Builds the closing Conclusions section." );
    o.put( "additionalProperties", false );
    return o;
  }

  // ---------------------------------------------------------------- argumentos

  /** Lee y valida los argumentos (sin tocar Spoon). */
  static Peticion peticion( JsonNode a, Tema tema, Modo modo ) throws ToolError {
    Peticion op = new Peticion();
    Pedido p = op.pedido;
    String idioma = Tools.optStr( a, "language", null );
    p.idioma = idioma != null ? ReportMotor.idioma( idioma ) : idiomaPorDefecto( tema );
    List<String> secciones = listaArg( a, "sections" );
    if ( secciones != null ) {
      op.seccionesExplicitas = true;
      p.secciones = ordenar( secciones );
    }
    if ( modo == Modo.DATOS ) {
      if ( p.secciones != null && p.secciones.contains( SeccionId.PERFIL ) ) {
        throw new ToolError( "The 'profile' section runs the transformation, so get_report_data does not build "
          + "it. Use build_report with 'profile_step' (and format json) if the user agreed to run it." );
      }
      int max = Tools.optInt( a, "max_rows", FILAS_DATOS );
      if ( max < 1 || max > FILAS_DATOS_MAX ) {
        throw new ToolError( "'max_rows' must be between 1 and " + FILAS_DATOS_MAX + ", got: " + max );
      }
      op.maxFilas = max;
      p.metricas = Pedido.METRICAS_ULTIMA;
      return op;
    }
    p.narrativa = narrativa( a.get( "narrative" ) );
    if ( p.narrativa != null && p.secciones != null ) {
      if ( p.narrativa.conclusiones != null && !p.secciones.contains( SeccionId.NARRATIVA ) ) {
        p.secciones = ordenar( mas( p.secciones, SeccionId.NARRATIVA ) );
      }
      for ( String id : p.narrativa.porSeccion.keySet() ) {
        if ( !p.secciones.contains( id ) ) {
          op.avisos.add( "narrative for '" + id + "' is not shown: that section is not in 'sections'" );
        }
      }
      if ( p.narrativa.resumen != null && !p.secciones.contains( SeccionId.RESUMEN ) ) {
        op.avisos.add( "narrative summary is not shown: 'summary' is not in 'sections'" );
      }
    }
    if ( modo == Modo.MOSTRAR ) {
      return op;
    }
    List<String> formatos = listaArg( a, "formats" );
    if ( formatos == null || formatos.isEmpty() ) {
      throw new ToolError( "Missing argument 'formats': one or more of " + clavesFormato() );
    }
    for ( String f : formatos ) {
      Formato x = Formato.deClave( f );
      if ( x == null ) {
        throw new ToolError( "Unknown format '" + f + "'. Valid: " + clavesFormato() );
      }
      if ( !op.formatos.contains( x ) ) {
        op.formatos.add( x );
      }
    }
    op.carpeta = Tools.optStr( a, "output_dir", null );
    op.archivo = Tools.optStr( a, "file_name", null );
    String titulo = Tools.optStr( a, "title", null );
    p.titulo = titulo == null ? null : titulo.trim();
    p.linajeStep = sinBlancos( Tools.optStr( a, "lineage_step", null ) );
    p.perfilStep = sinBlancos( Tools.optStr( a, "profile_step", null ) );
    int filasDef = tema != null && tema.perfil != null && tema.perfil.filas > 0 ? tema.perfil.filas : 5000;
    int filas = Tools.optInt( a, "profile_rows", filasDef );
    if ( filas < 1 || filas > PERFIL_MAX ) {
      throw new ToolError( "'profile_rows' must be between 1 and " + PERFIL_MAX + ", got: " + filas );
    }
    p.perfilFilas = filas;
    if ( p.perfilStep != null ) {
      if ( p.secciones != null && !p.secciones.contains( SeccionId.PERFIL ) ) {
        p.secciones = ordenar( mas( p.secciones, SeccionId.PERFIL ) ); // profile_step = quiere el perfil
      }
    } else if ( p.secciones != null && p.secciones.contains( SeccionId.PERFIL ) ) {
      throw new ToolError( "The 'profile' section needs 'profile_step' (the step whose output is profiled). It "
        + "runs a copy of the transformation up to that step: pass it only if the user agreed." );
    }
    String metricas = Tools.optStr( a, "metrics", Pedido.METRICAS_ULTIMA ).trim().toLowerCase( Locale.ROOT );
    if ( !metricas.equals( Pedido.METRICAS_ULTIMA ) && !metricas.equals( Pedido.METRICAS_COPIA ) && !metricas
      .equals( Pedido.METRICAS_NINGUNA ) ) {
      throw new ToolError( "'metrics' must be one of last_run, run_copy, none, got: " + metricas );
    }
    p.metricas = metricas;
    if ( Pedido.METRICAS_COPIA.equals( metricas ) && p.secciones != null && !p.secciones.contains(
      SeccionId.METRICAS ) ) {
      p.secciones = ordenar( mas( p.secciones, SeccionId.METRICAS ) ); // run_copy = quiere las metricas
    }
    p.destinoConsultarBase = Tools.optBool( a, "query_database", false );
    op.mostrar = Tools.optBool( a, "show_in_spoon", false );
    return op;
  }

  /** El idioma del informe cuando no viene "language": el de los ajustes, si no el de Spoon, si no en. */
  static String idiomaPorDefecto( Tema tema ) {
    if ( tema != null && tema.idioma != null && !tema.idioma.trim().isEmpty() ) {
      return Rotulos.normalizar( tema.idioma );
    }
    try {
      Locale l = LanguageChoice.getInstance().getDefaultLocale();
      if ( l != null ) {
        return Rotulos.normalizar( l.toString() );
      }
    } catch ( Throwable t ) {
      // sin Kettle inicializado: ingles
    }
    return "en";
  }

  /**
   * Un argumento lista: array de strings, o un string con un array JSON o valores separados por coma
   * (algunos clientes mandan los arrays como texto). null si no vino.
   */
  static List<String> listaArg( JsonNode a, String k ) throws ToolError {
    JsonNode v = a == null ? null : a.get( k );
    if ( v == null || v.isNull() ) {
      return null;
    }
    if ( v.isTextual() ) {
      String t = v.asText().trim();
      if ( t.startsWith( "[" ) ) {
        try {
          v = McpHttpServer.JSON.readTree( t );
        } catch ( Exception e ) {
          throw new ToolError( "'" + k + "' must be an array of strings, got: " + t );
        }
      } else {
        List<String> out = new ArrayList<>();
        for ( String x : t.split( "[,;\\s]+" ) ) {
          if ( !x.isEmpty() ) {
            out.add( x.toLowerCase( Locale.ROOT ) );
          }
        }
        return out;
      }
    }
    if ( !v.isArray() ) {
      throw new ToolError( "'" + k + "' must be an array of strings, got: " + v );
    }
    List<String> out = new ArrayList<>();
    for ( JsonNode x : v ) {
      if ( !x.isTextual() || x.asText().trim().isEmpty() ) {
        throw new ToolError( "'" + k + "' must be an array of strings, got: " + v );
      }
      out.add( x.asText().trim().toLowerCase( Locale.ROOT ) );
    }
    return out;
  }

  /** Valida los ids, saca repetidos y los pone en el orden del informe. */
  private static List<String> ordenar( List<String> ids ) throws ToolError {
    for ( String id : ids ) {
      if ( !SeccionId.valido( id ) ) {
        throw new ToolError( "Unknown section '" + id + "'. Valid: " + String.join( ", ", SeccionId.ORDEN ) );
      }
    }
    List<String> out = new ArrayList<>();
    for ( String id : SeccionId.ORDEN ) {
      if ( ids.contains( id ) ) {
        out.add( id );
      }
    }
    return out;
  }

  private static List<String> mas( List<String> l, String id ) {
    List<String> out = new ArrayList<>( l );
    out.add( id );
    return out;
  }

  private static String sinBlancos( String s ) {
    return s == null || s.trim().isEmpty() ? null : s.trim();
  }

  private static String clavesFormato() {
    StringBuilder sb = new StringBuilder();
    for ( Formato f : Formato.values() ) {
      sb.append( sb.length() == 0 ? "" : ", " ).append( f.clave );
    }
    return sb.toString();
  }

  /**
   * El argumento "narrative": {summary, sections: {id: texto}, conclusions}. Acepta tambien el objeto
   * como texto JSON, o un texto suelto (van a las conclusiones). null si no hay ningun texto.
   */
  static Narrativa narrativa( JsonNode v ) throws ToolError {
    if ( v == null || v.isNull() ) {
      return null;
    }
    if ( v.isTextual() ) {
      String t = v.asText().trim();
      if ( t.isEmpty() ) {
        return null;
      }
      if ( t.startsWith( "{" ) ) {
        try {
          v = McpHttpServer.JSON.readTree( t );
        } catch ( Exception e ) {
          throw new ToolError( "'narrative' must be an object {summary, sections, conclusions}: " + e
            .getMessage() );
        }
      } else {
        Narrativa n = new Narrativa();
        n.conclusiones = texto( "narrative", t );
        n.origen = Narrativa.IA;
        return n;
      }
    }
    if ( !v.isObject() ) {
      throw new ToolError( "'narrative' must be an object {summary, sections, conclusions}, got: " + v );
    }
    Narrativa n = new Narrativa();
    n.origen = Narrativa.IA;
    for ( Iterator<String> it = v.fieldNames(); it.hasNext(); ) {
      String k = it.next();
      JsonNode x = v.get( k );
      if ( "summary".equals( k ) ) {
        n.resumen = texto( "narrative.summary", x );
      } else if ( "conclusions".equals( k ) ) {
        n.conclusiones = texto( "narrative.conclusions", x );
      } else if ( "sections".equals( k ) ) {
        if ( x == null || x.isNull() ) {
          continue;
        }
        if ( !x.isObject() ) {
          throw new ToolError( "'narrative.sections' must be an object {section id: text}, got: " + x );
        }
        for ( Iterator<String> is = x.fieldNames(); is.hasNext(); ) {
          String id = is.next();
          if ( !SeccionId.valido( id ) ) {
            throw new ToolError( "Unknown section '" + id + "' in narrative.sections. Valid: " + String.join( ", ",
              SeccionId.ORDEN ) );
          }
          String t = texto( "narrative.sections." + id, x.get( id ) );
          if ( t != null ) {
            n.porSeccion.put( id, t );
          }
        }
      } else {
        throw new ToolError( "Unknown key '" + k + "' in 'narrative'. Valid: summary, sections, conclusions" );
      }
    }
    return n.resumen == null && n.conclusiones == null && n.porSeccion.isEmpty() ? null : n;
  }

  private static String texto( String donde, JsonNode x ) throws ToolError {
    if ( x == null || x.isNull() ) {
      return null;
    }
    if ( !x.isTextual() ) {
      throw new ToolError( "'" + donde + "' must be text, got: " + x );
    }
    return texto( donde, x.asText() );
  }

  private static String texto( String donde, String t ) throws ToolError {
    String s = t == null ? "" : t.trim();
    if ( s.isEmpty() ) {
      return null;
    }
    if ( s.length() > MAX_NARRATIVA ) {
      throw new ToolError( "'" + donde + "' has " + s.length() + " characters; the limit is " + MAX_NARRATIVA
        + ". Shorten it." );
    }
    return s;
  }

  // ---------------------------------------------------------------- captura, armado y exportacion

  /** En la UI: resuelve el documento SIN activarlo y toma la Captura (copia y lienzo). */
  private static Captura capturar( final JsonNode a ) throws Exception {
    return Docs.ui( () -> {
      Spoon sp = Docs.spoon();
      AbstractMeta m = Docs.resolverSinActivar( a );
      Captura c = InformeServicio.capturar( sp, m );
      if ( c == null ) {
        throw new ToolError( "Could not capture '" + Docs.nombre( m ) + "'" );
      }
      if ( c.docId == null ) {
        c.docId = Docs.id( m );
      }
      return c;
    } );
  }

  /**
   * build_report sobre una captura ya tomada (de Spoon o de un archivo): arma el informe y escribe cada
   * formato. Fuera de la UI. Es lo que prueba PruebaMcpInformes sin Spoon.
   */
  static ObjectNode construir( Captura c, JsonNode args ) throws Exception {
    Tema tema = Ajustes.cargar();
    return construir( c, peticion( args, tema, Modo.CONSTRUIR ), tema );
  }

  static ObjectNode construir( Captura c, Peticion op, Tema tema ) throws Exception {
    return construir( c, op, tema, null );
  }

  /** Igual, y deja el informe armado en {@code armado[0]} (para mostrarlo en Spoon sin armarlo otra vez). */
  static ObjectNode construir( Captura c, Peticion op, Tema tema, Informe[] armado ) throws Exception {
    File dir = carpetaSalida( op.carpeta, tema, c );
    String base = op.archivo == null ? null : nombreBase( op.archivo, tema, new Date() );
    List<String> avisos = new ArrayList<>( op.avisos );
    avisosDeCaptura( c, op, avisos );
    ProgresoMcp pr = new ProgresoMcp();
    Informe inf = armar( c, op.pedido, pr );
    if ( armado != null && armado.length > 0 ) {
      armado[ 0 ] = inf;
    }
    if ( base == null ) {
      base = InformeServicio.nombreBase( inf, tema ); // el mismo nombre que pone el panel al exportar
    }
    if ( !dir.isDirectory() && !dir.mkdirs() ) {
      throw new ToolError( "Could not create the folder " + dir );
    }
    ObjectNode o = Tools.obj();
    o.put( "document", c.docId );
    o.put( "name", c.nombre );
    o.put( "output_dir", dir.getPath() );
    ArrayNode files = o.putArray( "files" );
    Tema t = tema.copia();
    Thread hilo = Thread.currentThread();
    ClassLoader antes = hilo.getContextClassLoader();
    hilo.setContextClassLoader( ReportTools.class.getClassLoader() ); // POI (JAXB) lo necesita
    try {
      for ( Formato f : op.formatos ) {
        File destino = InformeServicio.archivo( dir, base, f ); // base.html, base.light.html, base.pdf...
        try {
          Resultado r = InformeServicio.exportar( inf, t, f, destino, pr );
          if ( r == null || r.archivos == null || r.archivos.isEmpty() ) {
            avisos.add( f.clave + ": nothing was written" );
          } else {
            File principal = r.archivos.get( 0 );
            ObjectNode x = files.addObject();
            x.put( "format", f.clave );
            x.put( "path", principal.getPath() );
            x.put( "bytes", principal.length() );
            if ( r.archivos.size() > 1 ) {
              x.put( "extra_files", r.archivos.size() - 1 );
            }
          }
          if ( r != null && r.avisos != null ) {
            for ( String w : r.avisos ) {
              avisos.add( f.clave + ": " + w );
            }
          }
        } catch ( Throwable e ) {
          // tambien Error (NoClassDefFoundError o LinkageError de POI/Batik, OutOfMemoryError con un lienzo
          // enorme): falla ese formato, no la tool, y los archivos ya escritos se informan igual
          if ( !( e instanceof ToolError ) ) {
            McpLifecycleListener.LOG.logError( "build_report: fallo el formato " + f.clave, e );
          }
          avisos.add( f.clave + ": " + mensaje( e ) );
        }
      }
    } finally {
      hilo.setContextClassLoader( antes );
    }
    o.set( "sections", estados( inf ) );
    if ( inf.avisos != null ) {
      avisos.addAll( inf.avisos );
    }
    ArrayNode w = o.putArray( "warnings" );
    for ( String x : avisos ) {
      w.add( x );
    }
    if ( files.size() == 0 ) {
      throw new ToolError( "No file was written. " + String.join( "; ", avisos ) );
    }
    return o;
  }

  /** get_report_data sobre una captura ya tomada. Fuera de la UI. */
  static ObjectNode datos( Captura c, JsonNode args ) throws Exception {
    Tema tema = Ajustes.cargar();
    return datos( c, peticion( args, tema, Modo.DATOS ) );
  }

  private static ObjectNode datos( Captura c, Peticion op ) throws Exception {
    List<String> avisos = new ArrayList<>( op.avisos );
    avisosDeCaptura( c, op, avisos );
    Informe inf = armar( c, op.pedido, new ProgresoMcp() );
    ObjectNode o = datosIa( inf, op.maxFilas );
    o.put( "document", c.docId );
    if ( !avisos.isEmpty() ) {
      ArrayNode w = o.has( "warnings" ) && o.get( "warnings" ).isArray() ? (ArrayNode) o.get( "warnings" ) : o
        .putArray( "warnings" );
      for ( String x : avisos ) {
        w.add( x );
      }
    }
    return o;
  }

  /**
   * Avisos que dependen del documento. Sin "sections", Pedido.secciones queda null y el motor elige
   * (InformeServicio: las marcadas por defecto que aplican, mas profile con perfilStep, metrics con
   * una corrida o run_copy, y narrative con conclusiones).
   */
  private static void avisosDeCaptura( Captura c, Peticion op, List<String> avisos ) {
    Pedido p = op.pedido;
    if ( c.esJob() && p.perfilStep != null ) {
      avisos.add( "profile_step is ignored: the data profile is only for transformations" );
    }
    if ( c.esJob() && p.linajeStep != null ) {
      avisos.add( "lineage_step is ignored: field lineage is only for transformations" );
    }
  }

  /** Espera el armado con un tope; si se vence, lo cancela. */
  private static Informe armar( Captura c, Pedido p, ProgresoMcp pr ) throws Exception {
    Future<Informe> f = InformeServicio.armar( c, p, pr );
    long tope = Math.max( 1000, p.timeoutMs ) + MS_MARGEN;
    try {
      Informe inf = f.get( tope, TimeUnit.MILLISECONDS );
      if ( inf == null ) {
        throw new ToolError( "The report engine returned nothing" );
      }
      return inf;
    } catch ( TimeoutException e ) {
      pr.cancelar();
      f.cancel( true );
      throw new ToolError( "The report did not finish in " + ( tope / 1000 ) + " s and was stopped. Ask for "
        + "fewer sections (lineage, dictionary and validation are the slow ones with database steps)." );
    } catch ( ExecutionException e ) {
      Throwable causa = e.getCause();
      if ( causa instanceof Exception ) {
        throw (Exception) causa;
      }
      throw new RuntimeException( causa );
    }
  }

  /** [{id, status, reason}] con los estados en ingles. */
  private static ArrayNode estados( Informe inf ) {
    ArrayNode a = Tools.arr();
    for ( Seccion s : inf.secciones ) {
      if ( s == null ) {
        continue;
      }
      ObjectNode x = a.addObject();
      x.put( "id", s.id );
      x.put( "status", estado( s.estado ) );
      if ( s.motivo != null && !s.motivo.isEmpty() ) {
        x.put( "reason", s.motivo );
      }
    }
    return a;
  }

  /** ok | partial | skipped | error. */
  static String estado( String estado ) {
    if ( Seccion.PARCIAL.equals( estado ) ) {
      return "partial";
    }
    if ( Seccion.OMITIDA.equals( estado ) ) {
      return "skipped";
    }
    return estado == null ? "ok" : estado;
  }

  /**
   * output_dir; si no, la carpeta de los ajustes; si no, &lt;carpeta del .ktr&gt;\informes. Un documento
   * que nunca se guardo necesita output_dir.
   */
  static File carpetaSalida( String argumento, Tema tema, Captura c ) throws ToolError {
    if ( argumento != null && !argumento.trim().isEmpty() ) {
      File f = new File( argumento.trim() );
      // relativa se resolveria contra el directorio de trabajo de Spoon (data-integration)
      if ( !f.isAbsolute() ) {
        throw new ToolError( "'output_dir' must be an absolute folder (got '" + argumento.trim() + "'): a relative "
          + "path would be resolved against Spoon's working folder (data-integration)." );
      }
      return f;
    }
    File porDefecto = InformeServicio.carpetaPorDefecto( c, tema ); // la misma que usa el panel
    if ( porDefecto != null ) {
      return porDefecto.getAbsoluteFile();
    }
    throw new ToolError( "'" + ( c.nombre == null ? "The document" : c.nombre ) + "' has no file yet: pass "
      + "'output_dir' (or save it first)." );
  }

  /**
   * Nombre base que pidio la IA en file_name: sin carpeta, sin extension conocida y sin lo que Windows no
   * acepta; con salida.agregar_fecha suma -AAAAMMDD-HHMM (como InformeServicio.nombreBase). Sin
   * file_name se usa InformeServicio.nombreBase, el mismo nombre que pone el panel.
   */
  static String nombreBase( String argumento, Tema tema, Date fecha ) throws ToolError {
    String n = new File( argumento.trim() ).getName().replaceFirst( "(?i)\\.(html?|pdf|md|pptx|json)$", "" );
    n = limpiarNombre( n );
    if ( n.isEmpty() ) {
      throw new ToolError( "'file_name' is not a valid file name: " + argumento );
    }
    if ( tema != null && tema.salida != null && tema.salida.agregarFecha ) {
      n += "-" + new SimpleDateFormat( "yyyyMMdd-HHmm", Locale.ROOT ).format( fecha );
    }
    return n;
  }

  /** Cambia por "_" lo que Windows no acepta en un nombre de archivo. */
  static String limpiarNombre( String s ) {
    String n = s.replaceAll( "[\\\\/:*?\"<>|\\p{Cntrl}]", "_" ).trim();
    while ( n.endsWith( "." ) ) {
      n = n.substring( 0, n.length() - 1 ).trim();
    }
    return n.length() > 120 ? n.substring( 0, 120 ) : n;
  }

  /**
   * Trae la perspectiva Informes y lanza la vista previa de ese documento. (UI) Falla con un mensaje
   * claro si la perspectiva no esta (PENTAHO_LIVE_REPORTS=off o el jar fuera de data-integration\plugins).
   */
  static void mostrarEnSpoon( Spoon sp, String docId, Pedido p ) throws ToolError {
    mostrarEnSpoon( sp, docId, p, null );
  }

  /** Igual, con el informe que build_report ya armo: el panel solo lo muestra. (UI) */
  static void mostrarEnSpoon( Spoon sp, String docId, Pedido p, Informe hecho ) throws ToolError {
    sp.loadPerspective( PERSPECTIVA_INFORMES );
    SpoonPerspective act = SpoonPerspectiveManager.getInstance().getActivePerspective();
    if ( act == null || !PERSPECTIVA_INFORMES.equals( act.getId() ) ) {
      throw new ToolError( "The Reports perspective is not available in this Spoon (turned off with "
        + "PENTAHO_LIVE_REPORTS=off, or the plugin is not in data-integration/plugins). Use build_report to "
        + "write the files instead." );
    }
    PanelInformes.mostrar( docId, p, hecho );
  }

  static String mensaje( Throwable e ) {
    Throwable t = e;
    while ( t.getCause() != null && t.getCause() != t && ( t instanceof ExecutionException || t
      .getMessage() == null ) ) {
      t = t.getCause();
    }
    String m = t.getMessage();
    if ( m == null || m.trim().isEmpty() ) {
      return t.getClass().getSimpleName();
    }
    return t instanceof ToolError || t instanceof java.io.IOException ? m : t.getClass().getSimpleName() + ": " + m;
  }

  // ---------------------------------------------------------------- get_report_data: el modelo en ingles

  /** Nombre en ingles de cada propiedad del modelo (el JSON del modelo usa los nombres en castellano). */
  private static final Map<String, String> CLAVES = new HashMap<>();
  /** Valores enumerados en castellano -> ingles, por propiedad (ya traducida). */
  private static final Map<String, Map<String, String>> VALORES = new HashMap<>();

  static {
    String[] c = { "modelo", "model", "titulo", "title", "subtitulo", "subtitle", "tipoDoc", "document_type",
      "archivo", "file", "nombreDoc", "document_name", "idioma", "language", "generado", "generated", "secciones",
      "sections", "avisos", "warnings", "resumen", "summary", "estado", "status", "motivo", "reason", "bloques",
      "blocks", "tipo", "type", "nota", "note", "ia", "ai_written", "texto", "text", "columnas", "columns", "filas",
      "rows", "alineacion", "align", "pie", "footer", "etiqueta", "label", "valor", "value", "detalle", "detail",
      "nivel", "level", "clase", "kind", "imagen", "image", "alternativa", "fallback_table", "ancho", "width",
      "alto", "height", "escala", "scale", "unidad", "unit", "categorias", "categories", "nombre", "name",
      "valores", "values", "tramos", "spans", "categoria", "category", "inicioMs", "start_ms", "finMs",
      "end_ms" };
    for ( int i = 0; i < c.length; i += 2 ) {
      CLAVES.put( c[ i ], c[ i + 1 ] );
    }
    Map<String, String> tipos = new HashMap<>();
    tipos.put( Bloque.PARRAFO, "paragraph" );
    tipos.put( Bloque.LISTA, "list" );
    tipos.put( Bloque.TABLA, "table" );
    tipos.put( Bloque.INDICADORES, "indicators" );
    tipos.put( Bloque.DIAGRAMA, "diagram" );
    tipos.put( Bloque.GRAFICO, "chart" );
    tipos.put( Bloque.IMAGEN, "image" );
    tipos.put( Bloque.AVISO, "notice" );
    VALORES.put( "type", tipos );
    Map<String, String> estados = new HashMap<>();
    estados.put( Seccion.PARCIAL, "partial" );
    estados.put( Seccion.OMITIDA, "skipped" );
    VALORES.put( "status", estados );
    Map<String, String> clases = new HashMap<>();
    clases.put( Diagrama.FLUJO, "flow" );
    clases.put( Diagrama.LINAJE, "lineage" );
    clases.put( Grafico.BARRAS_H, "bar" );
    clases.put( Grafico.LINEA, "line" );
    VALORES.put( "kind", clases );
  }

  /**
   * El informe sin binarios, con propiedades y valores en ingles (como el resto de la superficie MCP),
   * sin los rotulos internos ni la alineacion de columnas, y con tablas, listas y arrays de los datos
   * crudos cortados a maxFilas:
   * junto a cada array cortado va "&lt;nombre&gt;_total"; en los datos crudos (que no se tocan de forma) la
   * ruta queda en "truncated".
   */
  static ObjectNode datosIa( Informe inf, int maxFilas ) {
    JsonNode arbol = ModeloJson.mapper().valueToTree( InformeServicio.sinBinarios( inf ) );
    List<String> recortes = new ArrayList<>();
    JsonNode t = traducir( arbol, "", Math.max( 1, maxFilas ), recortes, false );
    ObjectNode o = t instanceof ObjectNode ? (ObjectNode) t : Tools.obj();
    if ( !recortes.isEmpty() ) {
      ArrayNode r = o.putArray( "truncated" );
      for ( String x : recortes ) {
        r.add( x );
      }
    }
    return o;
  }

  private static JsonNode traducir( JsonNode n, String ruta, int max, List<String> recortes, boolean crudo ) {
    if ( n == null ) {
      return null;
    }
    if ( n.isObject() ) {
      ObjectNode out = Tools.obj();
      for ( Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        String k = e.getKey();
        JsonNode v = e.getValue();
        if ( crudo ) {
          out.set( k, recortar( v, ruta + "." + k, max, recortes, true ) );
          continue;
        }
        if ( "rotulos".equals( k ) || "alineacion".equals( k ) || ( "ia".equals( k ) && !v.asBoolean() ) ) {
          continue; // rotulos internos y alineacion de columnas: solo le sirven al renderizador
        }
        if ( "datos".equals( k ) ) {
          if ( v.isObject() || v.isArray() ) { // los datos crudos de la seccion; un byte[] (texto) no va
            out.set( "data", recortar( v, ruta + ".data", max, recortes, true ) );
          }
          continue;
        }
        String clave = CLAVES.containsKey( k ) ? CLAVES.get( k ) : k;
        Map<String, String> vals = VALORES.get( clave );
        if ( vals != null && v.isTextual() && vals.containsKey( v.asText() ) ) {
          out.put( clave, vals.get( v.asText() ) );
          continue;
        }
        if ( recortable( k, v ) && v.size() > max ) {
          out.set( clave, recortar( v, ruta + "." + clave, max, recortes, false ) );
          out.put( clave + "_total", v.size() );
        } else {
          out.set( clave, traducir( v, ruta + "." + clave, max, recortes, false ) );
        }
      }
      return out;
    }
    if ( n.isArray() ) {
      ArrayNode out = Tools.arr();
      for ( int i = 0; i < n.size(); i++ ) {
        JsonNode x = n.get( i );
        String sub = x.isObject() && x.path( "id" ).isTextual() ? "[" + x.get( "id" ).asText() + "]" : "[" + i
          + "]";
        out.add( traducir( x, ruta + sub, max, recortes, crudo ) );
      }
      return out;
    }
    return n;
  }

  /**
   * Arrays del modelo que se cortan a max_rows: filas de tablas, items de listas (de texto: los de los
   * indicadores no), categorias, valores y tramos de los graficos. Secciones, bloques, columnas, series
   * y avisos nunca.
   */
  private static boolean recortable( String clave, JsonNode v ) {
    if ( v == null || !v.isArray() ) {
      return false;
    }
    if ( "items".equals( clave ) ) {
      return v.size() > 0 && v.get( 0 ).isTextual();
    }
    return "filas".equals( clave ) || "categorias".equals( clave ) || "valores".equals( clave ) || "tramos".equals(
      clave );
  }

  /** Traduce y, si es un array mas largo que max, lo corta (y en los datos crudos anota la ruta). */
  private static JsonNode recortar( JsonNode v, String ruta, int max, List<String> recortes, boolean crudo ) {
    if ( v == null || !v.isArray() || v.size() <= max ) {
      return traducir( v, ruta, max, recortes, crudo );
    }
    ArrayNode corto = Tools.arr();
    for ( int i = 0; i < max; i++ ) {
      corto.add( v.get( i ) );
    }
    if ( crudo ) {
      String r = ruta.startsWith( "." ) ? ruta.substring( 1 ) : ruta;
      recortes.add( r + ": " + max + " of " + v.size() );
    }
    return traducir( corto, ruta, max, recortes, crudo );
  }
}
