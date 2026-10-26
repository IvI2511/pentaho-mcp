package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.ui.spoon.Spoon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.informes.Formato;
import io.github.ivi2511.pentahomcp.informes.Resultado;
import io.github.ivi2511.pentahomcp.informes.modelo.Bloque;
import io.github.ivi2511.pentahomcp.informes.modelo.Diagrama;
import io.github.ivi2511.pentahomcp.informes.modelo.Informe;
import io.github.ivi2511.pentahomcp.informes.modelo.Seccion;
import io.github.ivi2511.pentahomcp.informes.tema.Ajustes;
import io.github.ivi2511.pentahomcp.informes.tema.Tema;

/**
 * Herramientas MCP del plano ETL (D1) y del modo boceto (D2): plan_create, plan_get, plan_review, plan_build,
 * plan_check y fill_placeholder. La lógica está en {@link Plano}, {@link PlanoRevision}, {@link PlanoInforme},
 * {@link PlanoConstruir} y {@link PlanoDeriva}; la guía para Claude es manual/10-plano.md de la skill.
 */
final class PlanoTools {

  private PlanoTools() {
  }

  static void register( McpHttpServer s ) {

    s.add( Tools.tool( "plan_create",
      "Writes an ETL plan (a small, versionable design file <name>.plan.yaml, JSON Schema docs/plano.schema.json) "
        + "BEFORE building: goal, grain, load (full|incremental) and incremental_by, sources {id, name, format, "
        + "location, volume, frequency, key, rejects}, targets {id, name, kind fact|dimension|table|file, location, "
        + "connection, key, load insert|append|truncate_insert|upsert|scd1|scd2|overwrite}, rules {id, text}, stages "
        + "{id, name, kind extract|clean|validate|dedupe|lookup|transform|aggregate|load|reject, transformation (the "
        + ".ktr it goes in), after [stage ids], reads [source ids], writes [target ids], lookup, key, on_missing "
        + "reject|default|unknown_member|fail, rules, step_type}, orchestration {name, order, on_failure, notify}, "
        + "environment {connections, folders, runner}, risks {text, mitigation}, decisions {text, why}. Interview the "
        + "user first (grain, full history or only new rows, what to do with orphans...: see the skill's "
        + "manual/10-plano.md). Shape errors are returned and nothing is written; the result includes plan_review's "
        + "counts. Overwrite an existing plan only with overwrite=true.",
      Tools.schema()
        .str( "path", "Absolute path of the plan file: <name>.plan.yaml (or .plan.yml / .plan.json)", true )
        .raw( "plan", Tools.obj().put( "type", "object" ).put( "description", "The plan (keys as in the description)" ),
          true )
        .bool( "overwrite", "Replace an existing file. Default false", false ).build(),
      a -> crear( a ) ) );

    s.add( Tools.tool( "plan_get",
      "Reads an ETL plan file and returns it as JSON plus its three views as Mermaid text (data flow, UML activity of "
        + "the orchestration, components and deployment) and, for every transformation of the plan open in Spoon, the "
        + "sketch progress (pieces built of the total). With formats (html, html_light, md, pdf, docx, xlsx, images, "
        + "json) it also writes the design document with the diagrams to output_dir (default: next to the plan). "
        + "show=true opens Spoon's Design perspective with the plan: only when the user asks to see it.",
      Tools.schema()
        .str( "plan", "Absolute path of the .plan.yaml / .plan.json", true )
        .strEnum( "language", "Language of the texts of the views and the document (default: the plan's)", false,
          "en", "es", "pt" )
        .raw( "formats", formatos(), false )
        .str( "output_dir", "Absolute folder for the files (default: the plan's folder)", false )
        .str( "file_name", "Base name of the files (default: the plan file name without .plan.yaml)", false )
        .bool( "show", "Open the Design perspective in Spoon with this plan. Default false", false ).build(),
      a -> obtener( a ) ) );

    s.add( Tools.tool( "plan_review",
      "Static review of an ETL plan, without Spoon: missing goal or grain, sources without a rejects plan, lookups "
        + "without a key or without on_missing (what to do with orphans), loads that are not idempotent (re-running "
        + "duplicates rows), upsert/SCD without a key, incremental load without a watermark, stages with no input or "
        + "whose rows go nowhere, unused rules, no orchestration or failure path... Each finding has severity "
        + "(error, warning, info), how_to_fix and, when only the user knows the answer, ask_the_user: ask it, update "
        + "the plan (plan_create overwrite=true) and review again.",
      Tools.schema()
        .str( "plan", "Absolute path of the plan file", false )
        .raw( "plan_inline", Tools.obj().put( "type", "object" ).put( "description",
          "Instead of a file: the plan itself (as in plan_create)" ), false )
        .strEnum( "language", "Language of the messages (default: the plan's)", false, "en", "es", "pt" ).build(),
      a -> revisar( a ) ) );

    s.add( Tools.tool( "plan_build",
      "Builds the skeleton of ONE transformation of the plan on Spoon's canvas, animated and undoable with ONE "
        + "Ctrl+Z. mode=sketch (default): every stage becomes a placeholder (a Dummy step named like the stage) with "
        + "a colored TO DO note saying what it must do (reads, lookup, writes, rules, suggested step); fill each one "
        + "later with fill_placeholder. mode=steps: the suggested step type of each stage (step_type, or one from its "
        + "kind and format) unconfigured, with the same notes; stages with no clear step stay placeholders. Stages "
        + "already on the canvas (same name) are skipped. pace=lesson plays it as a class-mode lesson (one stage per "
        + "step, Next in the Lesson tab) instead. The transformation keeps a link to the plan: the IDC menu shows "
        + "the sketch progress, lint_etl flags the placeholders left (placeholder_step) and plan_check compares. "
        + "job=true (or transformation=<orchestration.name>) builds the ORCHESTRATION JOB instead, in a NEW job "
        + "document (close it without saving to undo it; Spoon does not chain undo in jobs) or, if a job linked to the "
        + "plan is open, adding only the missing entries in one Ctrl+Z: START, a Check files exist of the sources, "
        + "Create folder of the outputs, a SQL entry with the CREATE TABLE of the target tables (derived from the "
        + "transformations already built in orchestration.folder; DROP + CREATE for load overwrite), one TRANS per "
        + "transformation in dependency order (dimensions before facts, or orchestration.order), SUCCESS, the failure "
        + "path of on_failure (Write to log + Abort; continue = unconditional hops) and a note. In sketch mode the "
        + "entries are placeholders (DUMMY + TO DO note) that fill_placeholder without 'type' turns into the planned "
        + "entry. Save it as the 'save_as' path of the result.",
      Tools.schema().document()
        .str( "plan", "Absolute path of the plan file", true )
        .str( "transformation", "Which transformation of the plan (its 'transformation' name). Required if the plan "
          + "has several. The orchestration name builds the job", false )
        .bool( "job", "Build the orchestration job of the plan instead of a transformation. Default false", false )
        .strEnum( "mode", "sketch (placeholders, default) or steps (suggested step types; for the job, configured "
          + "entries; 'full' is the same)", false, "sketch", "steps", "full" )
        .strEnum( "pace", "normal (default) or lesson", false, "normal", "lesson" )
        .strEnum( "lesson_pace", "With pace=lesson: guided (default), auto or manual", false, "guided", "auto",
          "manual" )
        .bool( "new_document", "Build in a NEW transformation named like the plan's (when 'document' is not given "
          + "and no open transformation is linked to it). Default false: the active one", false )
        .integer( "delay_ms", "Pause between steps, 0-" + TransLote.DELAY_MAX + " (default "
          + TransLote.DELAY_DEFECTO + ")", false )
        .strEnum( "language", "Language of the notes (default: the plan's)", false, "en", "es", "pt" ).build(),
      a -> PlanoConstruir.ejecutar( a ) ) );

    s.add( Tools.tool( "plan_check",
      "Drift between the plan and what is built (it only warns, never changes anything): for each transformation of "
        + "the plan (the open one linked to it, an open one with its name, or <name>.ktr in the plan's folder or "
        + "orchestration.folder), stages without a step, placeholders left, step types that differ, sources and "
        + "targets no step uses, a load that is not what the plan says (truncate, upsert, SCD), orphans or bad rows "
        + "the plan rejects but with no error hop or rejects output, steps the plan does not know; and for the job "
        + "of the orchestration, transformations it does not run, a different order and a missing failure path.",
      Tools.schema()
        .str( "plan", "Absolute path of the plan file", true )
        .raw( "documents", listaTextos( "Optional: open document ids or .ktr/.kjb paths to compare (matched to the "
          + "plan by link or by name)" ), false )
        .strEnum( "language", "Language of the messages (default: the plan's)", false, "en", "es", "pt" ).build(),
      a -> deriva( a ) ) );

    s.add( Tools.tool( "fill_placeholder",
      "Replaces a placeholder of the sketch (a Dummy left by plan_build) with the real step, or a chain of steps: the "
        + "first one gets the hops that went into the placeholder, the last one gives the hops that went out, and the "
        + "TO DO note is removed (keep_note=true keeps it). The new steps keep the link to the plan's stage. One "
        + "Ctrl+Z brings the placeholder back. Configure them with config_xml here or later with configure_step. In a "
        + "JOB sketch (plan_build job=true) it replaces a placeholder entry with ONE entry: without 'type' the planned "
        + "one (Check files exist, Create folder, SQL, TRANS...), or the 'type' + config_xml you pass; its hops keep "
        + "their conditions; one Ctrl+Z.",
      Tools.schema().document()
        .str( "step", "Name of the placeholder (Dummy step, or DUMMY entry of a job)", true )
        .str( "type", "Step type id of the replacement (one step). Or use 'steps'", false )
        .str( "name", "Name of the replacement (default: the placeholder's)", false )
        .str( "config_xml", "Only the configuration tags that differ from get_step_template", false )
        .raw( "steps", piezas(), false )
        .bool( "keep_note", "Keep the TO DO note. Default false", false ).build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        AbstractMeta doc = Docs.resolver( a );
        if ( doc instanceof JobMeta ) {
          JobMeta jm = (JobMeta) doc;
          boolean pide = a.hasNonNull( "type" ) || a.hasNonNull( "steps" );
          ObjectNode o = PlanoJob.rellenar( sp, jm, Tools.str( a, "step" ), pide ? PlanoConstruir.piezas( a )
            : null, Tools.optBool( a, "keep_note", false ), sp.getMetaStore() );
          Docs.touched( jm );
          o.put( "document", Docs.id( jm ) );
          return o;
        }
        TransMeta tm = Docs.trans( a );
        List<PlanoConstruir.Pieza> ps = PlanoConstruir.piezas( a );
        ObjectNode o = PlanoConstruir.rellenar( tm, Tools.str( a, "step" ), ps, Tools.optBool( a, "keep_note", false ),
          new UndoGrupo( sp, tm ), sp.getMetaStore() );
        Docs.touched( tm );
        o.put( "document", Docs.id( tm ) );
        return o;
      } ) ) );
  }

  private static ObjectNode formatos() {
    ObjectNode o = Tools.obj().put( "type", "array" ).put( "description", "Formats of the design document: html, "
      + "html_light, md, pdf, docx, xlsx, images, json" );
    ArrayNode e = o.putObject( "items" ).put( "type", "string" ).putArray( "enum" );
    for ( Formato f : Formato.values() ) {
      if ( f != Formato.PPTX ) {
        e.add( f.clave );
      }
    }
    o.put( "uniqueItems", true );
    return o;
  }

  private static ObjectNode listaTextos( String desc ) {
    ObjectNode o = Tools.obj().put( "type", "array" ).put( "description", desc );
    o.putObject( "items" ).put( "type", "string" );
    return o;
  }

  private static ObjectNode piezas() {
    ObjectNode item = Tools.obj().put( "type", "object" );
    ObjectNode p = item.putObject( "properties" );
    p.putObject( "type" ).put( "type", "string" ).put( "description", "Step type id" );
    p.putObject( "name" ).put( "type", "string" ).put( "description", "Step name" );
    p.putObject( "config_xml" ).put( "type", "string" ).put( "description", "Configuration tags that change" );
    item.putArray( "required" ).add( "type" );
    ObjectNode s = Tools.obj().put( "type", "array" ).put( "minItems", 1 ).put( "maxItems", 10 ).put( "description",
      "A chain of steps that replaces the placeholder, in flow order" );
    s.set( "items", item );
    return s;
  }

  // ================================================================ apoyo comun

  /** El plano del argumento 'plan' (ruta absoluta), leído y sin errores de forma. */
  static Plano cargar( JsonNode a ) throws Exception {
    return cargar( Tools.str( a, "plan" ) );
  }

  static Plano cargar( String ruta ) throws Exception {
    File f = archivo( ruta, false );
    Plano.Lectura l = Plano.leer( f );
    if ( l.plano == null ) {
      throw new ToolError( "The plan " + f.getName() + " has errors:\n- " + String.join( "\n- ", l.errores ) );
    }
    return l.plano;
  }

  /** La ruta de un plano: absoluta y con nombre .plan.yaml/.plan.yml/.plan.json. */
  static File archivo( String ruta, boolean paraEscribir ) throws ToolError {
    if ( ruta == null || ruta.trim().isEmpty() ) {
      throw new ToolError( "Missing the plan path" );
    }
    File f = new File( ruta.trim() );
    if ( !f.isAbsolute() ) {
      throw new ToolError( "The plan path must be absolute: " + ruta );
    }
    if ( !Plano.esNombreDePlano( f.getName() ) ) {
      throw new ToolError( "A plan file must be named <name>.plan.yaml (or .plan.yml / .plan.json): " + f.getName() );
    }
    if ( !paraEscribir && !f.isFile() ) {
      throw new ToolError( "Plan file not found: " + f.getPath() );
    }
    return f;
  }

  /** La transformación del plano pedida; sin pedirla, la única. */
  static String grupo( Plano p, String pedida ) throws ToolError {
    List<String> ts = p.transformaciones();
    if ( pedida != null && !pedida.trim().isEmpty() ) {
      String g = p.buscarTransformacion( pedida );
      if ( g == null ) {
        throw new ToolError( "The plan has no transformation '" + pedida + "'. Transformations: " + lista( ts ) );
      }
      return g;
    }
    if ( ts.size() == 1 ) {
      return ts.get( 0 );
    }
    if ( ts.isEmpty() ) {
      throw new ToolError( "The plan has no stages" );
    }
    throw new ToolError( "The plan has " + ts.size() + " transformations: say which one with 'transformation' ("
      + lista( ts ) + ")" );
  }

  private static String lista( List<String> ts ) {
    List<String> l = new ArrayList<String>();
    for ( String t : ts ) {
      l.add( PlanoConstruir.nombreGrupo( t ) );
    }
    return String.join( ", ", l );
  }

  // ================================================================ plan_create

  static ObjectNode crear( JsonNode a ) throws Exception {
    File f = archivo( Tools.str( a, "path" ), true );
    JsonNode plan = a.get( "plan" );
    Plano.Lectura l = plan != null && plan.isTextual() ? Plano.leerTexto( plan.asText() ) : Plano.desdeJson( plan );
    if ( l.plano == null ) {
      ObjectNode o = Tools.obj();
      o.put( "written", false );
      ArrayNode e = o.putArray( "errors" );
      for ( String x : l.errores ) {
        e.add( x );
      }
      avisos( o, l.avisos );
      return o;
    }
    if ( f.exists() && !Tools.optBool( a, "overwrite", false ) ) {
      throw new ToolError( f.getName() + " already exists: pass overwrite=true to replace it" );
    }
    l.plano.guardar( f );
    ObjectNode o = Tools.obj();
    o.put( "written", true );
    o.put( "path", f.getAbsolutePath() );
    o.put( "transformations", l.plano.transformaciones().size() );
    o.put( "stages", l.plano.etapas.size() );
    avisos( o, l.avisos );
    ObjectNode rev = PlanoRevision.json( PlanoRevision.revisar( l.plano, null ) );
    o.put( "review_errors", rev.path( "errors" ).asInt() );
    o.put( "review_warnings", rev.path( "warnings" ).asInt() );
    o.put( "next", "Call plan_review to see the findings and the questions for the user, then plan_build." );
    return o;
  }

  private static void avisos( ObjectNode o, List<String> avisos ) {
    if ( !avisos.isEmpty() ) {
      ArrayNode w = o.putArray( "warnings" );
      for ( String x : avisos ) {
        w.add( x );
      }
    }
  }

  // ================================================================ plan_review

  static ObjectNode revisar( JsonNode a ) throws Exception {
    Plano p;
    List<String> avisos = new ArrayList<String>();
    JsonNode inline = a.get( "plan_inline" );
    if ( inline != null && !inline.isNull() ) {
      Plano.Lectura l = Plano.desdeJson( inline );
      if ( l.plano == null ) {
        ObjectNode o = Tools.obj();
        ArrayNode e = o.putArray( "shape_errors" );
        for ( String x : l.errores ) {
          e.add( x );
        }
        return o;
      }
      p = l.plano;
      avisos.addAll( l.avisos );
    } else {
      File f = archivo( Tools.str( a, "plan" ), false );
      Plano.Lectura l = Plano.leer( f );
      if ( l.plano == null ) {
        ObjectNode o = Tools.obj();
        o.put( "plan", f.getAbsolutePath() );
        ArrayNode e = o.putArray( "shape_errors" );
        for ( String x : l.errores ) {
          e.add( x );
        }
        return o;
      }
      p = l.plano;
      avisos.addAll( l.avisos );
    }
    ObjectNode o = PlanoRevision.json( PlanoRevision.revisar( p, Tools.optStr( a, "language", null ) ) );
    if ( p.archivo != null ) {
      o.put( "plan", p.archivo.getAbsolutePath() );
    }
    avisos( o, avisos );
    return o;
  }

  // ================================================================ plan_get

  static ObjectNode obtener( JsonNode a ) throws Exception {
    final Plano p = cargar( a );
    String idioma = PlanoInforme.idioma( p, Tools.optStr( a, "language", null ) );
    List<String[]> progreso = progresoAbierto( p );
    Informe inf = PlanoInforme.armar( p, idioma, progreso );
    ObjectNode o = Tools.obj();
    o.put( "path", p.archivo.getAbsolutePath() );
    o.set( "plan", p.aJson() );
    ArrayNode ts = o.putArray( "transformations" );
    for ( String t : p.transformaciones() ) {
      ts.addObject().put( "name", PlanoConstruir.nombreGrupo( t ) ).put( "stages", p.etapasDe( t ).size() );
    }
    ObjectNode diag = o.putObject( "diagrams_mermaid" );
    for ( Seccion s : inf.secciones ) {
      for ( Bloque b : s.bloques ) {
        if ( b instanceof Diagrama ) {
          // dataflow (o dataflow-<transformación> si hay varias), activity, components
          diag.put( b.id.replace( "plan-", "" ), ( (Diagrama) b ).mermaid );
        }
      }
    }
    if ( !progreso.isEmpty() ) {
      ArrayNode pr = o.putArray( "sketch_progress" );
      for ( String[] x : progreso ) {
        pr.addObject().put( "transformation", x[ 0 ] ).put( "built", Integer.parseInt( x[ 1 ] ) ).put( "stages", Integer
          .parseInt( x[ 2 ] ) ).put( "placeholders", x[ 3 ] );
      }
    }
    List<String> fs = ReportTools.listaArg( a, "formats" );
    if ( fs != null && !fs.isEmpty() ) {
      Tema tema = Ajustes.cargar();
      File dir = Tools.optStr( a, "output_dir", null ) == null ? p.archivo.getParentFile() : new File( Tools.optStr(
        a, "output_dir", null ) );
      if ( !dir.isAbsolute() ) {
        throw new ToolError( "output_dir must be an absolute path" );
      }
      String base = Tools.optStr( a, "file_name", null );
      if ( base == null ) {
        base = p.archivo.getName().replaceAll( "(?i)\\.plan\\.(yaml|yml|json)$", "" );
      }
      ArrayNode archivos = o.putArray( "files" );
      ArrayNode avisos = Tools.arr();
      Thread hilo = Thread.currentThread();
      ClassLoader antes = hilo.getContextClassLoader();
      hilo.setContextClassLoader( PlanoTools.class.getClassLoader() ); // POI (JAXB) lo necesita
      try {
        for ( String clave : fs ) {
          Formato f = Formato.deClave( clave );
          if ( f == null || f == Formato.PPTX ) {
            throw new ToolError( "Unknown format '" + clave + "'" );
          }
          File destino = InformeServicio.archivo( dir, base, f );
          Resultado r = InformeServicio.exportar( inf, tema, f, destino, null );
          for ( File x : r.archivos ) {
            archivos.add( x.getAbsolutePath() );
          }
          for ( String w : r.avisos ) {
            avisos.add( w );
          }
        }
      } finally {
        hilo.setContextClassLoader( antes );
      }
      if ( avisos.size() > 0 ) {
        o.set( "warnings", avisos );
      }
    }
    if ( Tools.optBool( a, "show", false ) ) {
      Docs.ui( () -> {
        DisenoPerspective.mostrar( Docs.spoon(), p.archivo );
        return null;
      } );
      o.put( "shown_in_spoon", true );
    }
    return o;
  }

  /** {transformación, construidas, total, pendientes} de las transformaciones abiertas enlazadas al plano. */
  static List<String[]> progresoAbierto( final Plano p ) {
    try {
      return Docs.ui( () -> {
        List<String[]> l = new ArrayList<String[]>();
        for ( AbstractMeta m : Docs.abiertos() ) {
          if ( !( m instanceof TransMeta ) ) {
            continue;
          }
          TransMeta tm = (TransMeta) m;
          String ruta = PlanoConstruir.planoDe( tm );
          if ( ruta == null || !new File( ruta ).getAbsoluteFile().equals( p.archivo.getAbsoluteFile() ) ) {
            continue;
          }
          PlanoConstruir.Progreso pr = PlanoConstruir.progreso( tm );
          if ( pr != null ) {
            l.add( new String[] { PlanoConstruir.nombreGrupo( pr.transformacion ), Integer.toString( pr.construidas ),
              Integer.toString( pr.total ), String.join( ", ", pr.pendientes ) } );
          }
        }
        return l;
      } );
    } catch ( Throwable t ) {
      return new ArrayList<String[]>(); // sin Spoon (pruebas) o sin documentos
    }
  }

  // ================================================================ plan_check

  static ObjectNode deriva( JsonNode a ) throws Exception {
    final Plano p = cargar( a );
    final List<String> pedidos = textos( a, "documents" );
    final Map<String, Object[]> docs = new LinkedHashMap<String, Object[]>();
    final Object[][] job = new Object[ 1 ][];
    // 1. lo abierto en Spoon (y los documentos pedidos que estén abiertos)
    try {
      Docs.ui( () -> {
        for ( AbstractMeta m : Docs.abiertos() ) {
          asignar( p, m, "open", Docs.id( m ), docs, job, pedidos, true );
        }
        return null;
      } );
    } catch ( Throwable sinSpoon ) {
      // sin Spoon: solo archivos
    }
    // 2. archivos: los pedidos y <nombre>.ktr / .kjb en la carpeta del plano
    if ( pedidos != null ) {
      for ( String ref : pedidos ) {
        File f = new File( ref );
        if ( f.isAbsolute() && f.isFile() ) {
          AbstractMeta m = abrirArchivo( f );
          if ( m != null ) {
            asignar( p, m, "file", f.getAbsolutePath(), docs, job, null, false );
          }
        }
      }
    }
    File carpeta = p.archivo.getParentFile();
    if ( p.orquestacion != null && p.orquestacion.carpeta != null ) {
      File c = new File( p.orquestacion.carpeta );
      carpeta = c.isAbsolute() ? c : new File( p.archivo.getParentFile(), p.orquestacion.carpeta );
    }
    for ( String g : p.transformaciones() ) {
      if ( docs.containsKey( g ) || g.isEmpty() ) {
        continue;
      }
      File f = new File( carpeta, g + ".ktr" );
      if ( f.isFile() ) {
        AbstractMeta m = abrirArchivo( f );
        if ( m instanceof TransMeta ) {
          docs.put( g, new Object[] { m, "file", f.getAbsolutePath() } );
        }
      }
    }
    if ( job[ 0 ] == null && p.orquestacion != null && p.orquestacion.nombre != null ) {
      File f = new File( carpeta, p.orquestacion.nombre + ".kjb" );
      if ( f.isFile() ) {
        AbstractMeta m = abrirArchivo( f );
        if ( m instanceof JobMeta ) {
          job[ 0 ] = new Object[] { m, "file", f.getAbsolutePath() };
        }
      }
    }
    // la transformacion sin nombre ("") solo se compara con lo abierto y enlazado
    PlanoDeriva.Resultado r = PlanoDeriva.revisar( p, docs, job[ 0 ], Tools.optStr( a, "language", null ) );
    ObjectNode o = r.json();
    o.put( "plan", p.archivo.getAbsolutePath() );
    return o;
  }

  /** Una lista de textos tal cual (sin pasar a minúsculas): array JSON o un texto. */
  static List<String> textos( JsonNode a, String k ) {
    JsonNode v = a.get( k );
    if ( v == null || v.isNull() ) {
      return null;
    }
    List<String> l = new ArrayList<String>();
    if ( v.isArray() ) {
      for ( JsonNode x : v ) {
        if ( x != null && !x.asText().trim().isEmpty() ) {
          l.add( x.asText().trim() );
        }
      }
    } else if ( !v.asText().trim().isEmpty() ) {
      l.add( v.asText().trim() );
    }
    return l;
  }

  /** Asigna un documento a la transformación del plano que le toca (por enlace o por nombre), o al job. */
  private static void asignar( Plano p, AbstractMeta m, String donde, String ref, Map<String, Object[]> docs,
                               Object[][] job, List<String> pedidos, boolean soloSiPedido ) {
    if ( m instanceof JobMeta ) {
      if ( p.orquestacion != null && p.orquestacion.nombre != null && coincide( m, p.orquestacion.nombre )
        && job[ 0 ] == null ) {
        job[ 0 ] = new Object[] { m, donde, ref };
      }
      return;
    }
    if ( !( m instanceof TransMeta ) ) {
      return;
    }
    TransMeta tm = (TransMeta) m;
    for ( String g : p.transformaciones() ) {
      if ( PlanoConstruir.esDelPlano( tm, p, g ) ) {
        docs.put( g, new Object[] { tm, donde, ref } );
        return;
      }
    }
    for ( String g : p.transformaciones() ) {
      if ( !g.isEmpty() && !docs.containsKey( g ) && coincide( tm, g ) ) {
        docs.put( g, new Object[] { tm, donde, ref } );
        return;
      }
    }
    if ( pedidos != null && soloSiPedido && p.transformaciones().size() == 1 && !docs.containsKey( p
      .transformaciones().get( 0 ) ) && ( pedidos.contains( ref ) || pedidos.contains( tm.getName() ) ) ) {
      docs.put( p.transformaciones().get( 0 ), new Object[] { tm, donde, ref } );
    }
  }

  private static boolean coincide( AbstractMeta m, String nombre ) {
    String n = nombre.toLowerCase( Locale.ROOT );
    if ( m.getName() != null && m.getName().equalsIgnoreCase( nombre ) ) {
      return true;
    }
    String f = m.getFilename();
    return f != null && Plano.sinKtr( f ).replaceAll( "(?i)\\.kjb$", "" ).toLowerCase( Locale.ROOT ).equals( n );
  }

  /** Carga un .ktr/.kjb sin abrirlo en Spoon (solo lectura). */
  static AbstractMeta abrirArchivo( File f ) {
    try {
      String n = f.getName().toLowerCase( Locale.ROOT );
      if ( n.endsWith( ".ktr" ) ) {
        return new TransMeta( f.getAbsolutePath() );
      }
      if ( n.endsWith( ".kjb" ) ) {
        return new JobMeta( f.getAbsolutePath(), null );
      }
    } catch ( Throwable t ) {
      McpLifecycleListener.LOG.logDebug( "plan_check: could not read " + f + ": " + t );
    }
    return null;
  }
}
