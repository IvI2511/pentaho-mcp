package io.github.ivi2511.pentahomcp;

import java.util.List;
import java.util.Locale;

import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.ui.spoon.Spoon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.LeccionLayout.Tipo;
import io.github.ivi2511.pentahomcp.LeccionMotor.Entorno;
import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;

/**
 * Herramientas MCP del modo clase (docs/modo-clase-diseno.md §8): lesson_notes (WP2; la logica, sin Spoon, en
 * {@link LeccionNotas}) y lesson_start, lesson_next, lesson_status y lesson_control (WP3; el motor en
 * {@link LeccionMotor}, el guion y el plan en {@link Leccion}). Solo transformaciones.
 */
final class LessonTools {

  private LessonTools() {
  }

  static void register( McpHttpServer s ) {

    s.add( Tools.tool( "lesson_notes",
      "Works on the lesson notes of a transformation (class mode): the numbered notes that explain each step, "
        + "recognised by a registry stored in the .ktr. action=list: every registry entry with its state "
        + "(present, hidden, missing, step_missing). add {step, kind, text}: one extra note (watch_out, tip or "
        + "question) next to a step, styled and placed with the class rule. hide: takes the lesson notes off the "
        + "canvas and keeps their XML in the .ktr (NOT secret: anyone can read it); show: puts them back without "
        + "duplicating. remove: deletes them (the registry stays; purge=true deletes it too). restyle {font_size}: "
        + "bigger or smaller text for a projector. relayout: applies the class rule again (steps and lesson notes). "
        + "Notes the user wrote or edited are never touched. Every action is one Ctrl+Z in Spoon. Transformations "
        + "only.",
      Tools.schema().document()
        .strEnum( "action", "What to do", true, "list", "add", "hide", "show", "remove", "restyle", "relayout" )
        .str( "step", "add: the step the note is about", false )
        .strEnum( "kind", "add: type of note", false, "watch_out", "tip", "question" )
        .str( "text", "add: the text (the fixed label and the step number are added)", false )
        .strEnum( "language", "add: language of the fixed labels (default: the lesson's, or en)", false,
          "en", "es", "pt" )
        .integer( "font_size", "restyle: font size of every lesson note (-1 = Spoon's note font)", false )
        .bool( "purge", "remove: also delete the registry (afterwards a Ctrl+Z brings the notes back as user "
          + "notes). Default false", false ).build(),
      a -> Docs.ui( () -> ejecutar( a ) ) ) );

    s.add( Tools.tool( "lesson_start",
      "Class mode: builds a transformation SLOWLY and explains it on Spoon's canvas, one step at a time, with "
        + "numbered notes («N · Name (Type)» / What / Why) plus watch-out, tip and question notes. Send the WHOLE "
        + "script: each step is either {type, name, config_xml?, after?, output_mode?} (like build_steps, but no x/y: "
        + "the lesson plan places everything) or {step} (an existing step, only annotated), plus what, why, "
        + "watch_out (text or \"auto\" from the glossary), tip, question and preview_rows (0-20; on or after an "
        + "output step it needs allow_write=true). Everything is validated first: if something is wrong nothing "
        + "changes. Step 0 (title note, existing steps moved to the plan) is applied now and is its own Ctrl+Z; "
        + "every later step is one Ctrl+Z too. pace: guided (default: the user clicks Next in the Lesson tab, or "
        + "call lesson_next), auto (every delay_ms) or manual (only lesson_next). Returns the lesson id and the "
        + "plan. Transformations only; one running lesson per document.",
      Tools.schema().document()
        .str( "title", "Lesson title (first line of the title note)", true )
        .strEnum( "level", "beginner (default) or intermediate", false, "beginner", "intermediate" )
        .strEnum( "language", "Language of the fixed labels and of your texts (default en)", false, "en", "es", "pt" )
        .strEnum( "pace", "guided (default), auto or manual", false, "guided", "auto", "manual" )
        .integer( "delay_ms", "auto: ms between steps (1500-60000, default 6000)", false )
        .integer( "font_size", "Font size of the lesson notes for a projector (-1 = Spoon's note font)", false )
        .strEnum( "existing_notes", "Notes already on the canvas: stack_top (default, stacked at the top left) or "
          + "keep (left where they are)", false, "stack_top", "keep" )
        .bool( "allow_write", "Allow preview_rows on/after output steps (the preview really writes). Default false",
          false )
        .str( "intro", "Short introduction (goes in the title note)", false )
        .str( "outro", "Closing text or exercise (returned when the lesson is done)", false )
        .raw( "steps", esquemaPasos(), true )
        .raw( "hops", esquemaHops(), false ).build(),
      a -> {
        Entorno ent = LeccionMotor.spoon();
        TransMeta tm = Docs.ui( () -> Docs.trans( a ) );
        return LeccionMotor.inicioJson( LeccionMotor.empezar( ent, a, tm ) );
      } ) );

    s.add( Tools.tool( "lesson_next",
      "Plays the next step of a lesson (any pace): the step and its hops appear, then its notes, then the preview "
        + "rows if the script asked for them. Returns position, state, done (what changed) and preview {fields, rows} "
        + "so you can comment them. If the user undid lesson steps in Spoon, the state becomes out_of_sync "
        + "(lesson_control resume re-syncs).",
      Tools.schema().document()
        .str( "lesson", "Lesson id (L1...). Default: the latest lesson of the document", false ).build(),
      a -> {
        Entorno ent = LeccionMotor.spoon();
        return LeccionMotor.avanzar( ent, leccion( a ), true );
      } ) );

    s.add( Tools.tool( "lesson_status",
      "State of a lesson: state (ready, waiting_next, playing, paused, out_of_sync, done, stopped), reason, "
        + "position of beats, the current step and its note, clicks on Next. With wait_s (0-120) it waits until "
        + "something changes (a click, a step, a pause...): call it again to keep following the class. since: "
        + "return at once if the position is not this one any more.",
      Tools.schema().document()
        .str( "lesson", "Lesson id (L1...). Default: the latest lesson of the document", false )
        .integer( "wait_s", "Seconds to wait for a change (0-120, default 0)", false )
        .integer( "since", "Position you already know about", false ).build(),
      a -> {
        Entorno ent = LeccionMotor.spoon();
        int espera = Math.max( 0, Math.min( LeccionMotor.ESPERA_MAX_S, Tools.optInt( a, "wait_s", 0 ) ) );
        return LeccionMotor.estado( ent, leccion( a ), espera, Tools.optInteger( a, "since" ) );
      } ) );

    s.add( Tools.tool( "lesson_control",
      "Steers a lesson: pause, resume (also re-syncs after out_of_sync), back (one Ctrl+Z of the last lesson step, "
        + "only if it is still the last change in Spoon's history), goto {to} (back or forward to position 'to'), "
        + "restart (undoes every lesson step back to step 0) and stop (the notes stay). Returns the same as "
        + "lesson_status.",
      Tools.schema().document()
        .str( "lesson", "Lesson id (L1...). Default: the latest lesson of the document", false )
        .strEnum( "action", "What to do", true, "pause", "resume", "back", "goto", "restart", "stop" )
        .integer( "to", "goto: target position (0 = before the first step)", false ).build(),
      a -> {
        Entorno ent = LeccionMotor.spoon();
        String accion = Tools.str( a, "action" ).trim().toLowerCase( Locale.ROOT );
        return LeccionMotor.controlar( ent, leccion( a ), accion, Tools.optInteger( a, "to" ) );
      } ) );
  }

  /** La leccion de los argumentos: 'lesson', o la ultima del documento (si se nombra) o la ultima. */
  private static Leccion leccion( JsonNode a ) throws Exception {
    String id = Tools.optStr( a, "lesson", null );
    TransMeta tm = null;
    if ( ( id == null || id.trim().isEmpty() ) && Tools.optStr( a, "document", null ) != null ) {
      tm = Docs.ui( () -> Docs.trans( a ) );
    }
    return LeccionMotor.buscar( id, tm );
  }

  /** JSON Schema de 'steps' de lesson_start. */
  private static ObjectNode esquemaPasos() {
    ObjectNode item = Tools.obj().put( "type", "object" );
    ObjectNode p = item.putObject( "properties" );
    p.putObject( "type" ).put( "type", "string" ).put( "description", "Step type id to create (e.g. CsvInput)" );
    p.putObject( "name" ).put( "type", "string" ).put( "description", "Name of the new step" );
    p.putObject( "config_xml" ).put( "type", "string" ).put( "description",
      "Only the configuration tags that differ from get_step_template" );
    p.putObject( "after" ).put( "type", "string" ).put( "description",
      "Existing step or an earlier step of the lesson: connected after -> this" );
    ObjectNode modo = p.putObject( "output_mode" ).put( "type", "string" ).put( "description",
      "copy or distribute when it has 2+ outgoing hops" );
    modo.putArray( "enum" ).add( "copy" ).add( "distribute" );
    p.putObject( "step" ).put( "type", "string" ).put( "description",
      "Instead of type/name: an existing step, only annotated" );
    p.putObject( "what" ).put( "type", "string" ).put( "description", "What the step does (one idea)" );
    p.putObject( "why" ).put( "type", "string" ).put( "description", "Why it is here" );
    p.putObject( "watch_out" ).put( "type", "string" ).put( "description",
      "A real pitfall, or \"auto\" (glossary: silent errors for beginners, C and D codes for intermediate)" );
    p.putObject( "tip" ).put( "type", "string" ).put( "description", "Optional tip note" );
    p.putObject( "question" ).put( "type", "string" ).put( "description", "Optional question for the class" );
    p.putObject( "preview_rows" ).put( "type", "integer" ).put( "description",
      "Rows to preview after this step (0-20, default 0)" );
    ObjectNode s = Tools.obj().put( "type", "array" ).put( "minItems", 1 ).put( "maxItems", TransLote.MAX_STEPS )
      .put( "description", "The lesson steps, in order (more than 12 is hard to follow: split it)" );
    s.set( "items", item );
    return s;
  }

  /** JSON Schema de 'hops' de lesson_start. */
  private static ObjectNode esquemaHops() {
    ObjectNode item = Tools.obj().put( "type", "object" );
    ObjectNode p = item.putObject( "properties" );
    p.putObject( "from" ).put( "type", "string" );
    p.putObject( "to" ).put( "type", "string" );
    p.putObject( "error" ).put( "type", "boolean" ).put( "description", "Error-handling hop (default false)" );
    p.putObject( "enabled" ).put( "type", "boolean" ).put( "description", "Default true" );
    item.putArray( "required" ).add( "from" ).add( "to" );
    ObjectNode s = Tools.obj().put( "type", "array" ).put( "description",
      "Extra hops ('after' cannot express them: lookup streams, second Filter rows branch...); each one is created "
        + "in the step where its last end appears" );
    s.set( "items", item );
    return s;
  }

  /** (UI) */
  static ObjectNode ejecutar( JsonNode a ) throws Exception {
    String accion = Tools.str( a, "action" ).trim().toLowerCase( Locale.ROOT );
    Spoon sp = Docs.spoon();
    TransMeta tm = Docs.trans( a );
    return ejecutar( sp, tm, accion, a );
  }

  /** La accion sobre un documento ya resuelto; sp null en las pruebas. (UI) */
  static ObjectNode ejecutar( Spoon sp, TransMeta tm, String accion, JsonNode a ) throws Exception {
    ObjectNode o = Tools.obj();
    o.put( "document", tm.getName() );
    o.put( "action", accion );
    if ( "list".equals( accion ) ) {
      ArrayNode arr = o.putArray( "entries" );
      List<String[]> l = LeccionNotas.listar( tm );
      for ( String[] e : l ) {
        ObjectNode x = arr.addObject().put( "n", Integer.parseInt( e[ 0 ] ) ).put( "kind", e[ 1 ] );
        if ( !e[ 2 ].isEmpty() ) {
          x.put( "step", e[ 2 ] );
        }
        x.put( "state", e[ 3 ] );
      }
      o.put( "has_lesson", LeccionNotas.tieneLeccion( tm ) );
      return o;
    }
    if ( !"add".equals( accion ) && !"relayout".equals( accion ) && !"restyle".equals( accion )
      && !"hide".equals( accion ) && !"show".equals( accion ) && !"remove".equals( accion ) ) {
      throw new ToolError( "Unknown action '" + accion + "': use list, add, hide, show, remove, restyle or relayout" );
    }
    if ( !"add".equals( accion ) && !LeccionNotas.tieneLeccion( tm ) ) {
      throw new ToolError( "'" + tm.getName() + "' has no lesson notes (no lesson registry). Use action=add or "
        + "lesson_start first." );
    }
    UndoGrupo u = new UndoGrupo( sp, tm );
    LeccionNotas.Resultado r;
    switch ( accion ) {
      case "add": {
        String kind = Tools.optStr( a, "kind", "watch_out" );
        Tipo t = LeccionNotas.tipoDeJson( kind );
        LeccionNotas.MedidorCerrable m = LeccionNotas.medidor( LeccionNotas.tamFuente( tm ) );
        try {
          r = LeccionNotas.agregar( tm, Tools.optStr( a, "step", null ), t, Tools.optStr( a, "text", null ),
            Tools.optStr( a, "language", null ), m, u );
        } finally {
          m.cerrar();
        }
        break;
      }
      case "relayout":
      case "restyle": {
        int tam = LeccionNotas.tamFuente( tm );
        if ( "restyle".equals( accion ) ) {
          Integer f = Tools.optInteger( a, "font_size" );
          if ( f == null ) {
            throw new ToolError( "restyle needs font_size (-1 = Spoon's note font)" );
          }
          if ( f != -1 && ( f < 6 || f > 40 ) ) {
            throw new ToolError( "font_size must be -1 or between 6 and 40" );
          }
          tam = f;
          tm.setAttribute( LeccionNotas.GRUPO, LeccionNotas.K_FUENTE, Integer.toString( f ) );
        }
        LeccionNotas.MedidorCerrable m = LeccionNotas.medidor( tam );
        try {
          r = LeccionNotas.reacomodar( tm, m, u, "restyle".equals( accion ) ? ( tam > 0 ? tam : -1 ) : -1 );
        } finally {
          m.cerrar();
        }
        o.put( "steps_moved", r.stepsMovidos );
        break;
      }
      case "hide":
        r = LeccionNotas.ocultar( tm, u );
        break;
      case "show":
        r = LeccionNotas.mostrar( tm, u );
        break;
      default:
        r = LeccionNotas.quitar( tm, Tools.optBool( a, "purge", false ), u );
        break;
    }
    o.put( "notes", r.notas );
    o.put( "kept_user_notes", r.notasUsuario );
    o.put( "undo", u.acciones() > 0 ? "One Ctrl+Z in Spoon undoes this" : "Nothing changed" );
    if ( !r.avisos.isEmpty() ) {
      ArrayNode w = o.putArray( "warnings" );
      for ( String s : r.avisos ) {
        w.add( s );
      }
    }
    if ( sp != null && u.acciones() > 0 ) {
      Docs.touched( tm );
    }
    return o;
  }
}
