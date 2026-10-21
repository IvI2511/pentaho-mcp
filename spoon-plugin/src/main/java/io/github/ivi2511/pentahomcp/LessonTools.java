package io.github.ivi2511.pentahomcp;

import java.util.List;
import java.util.Locale;

import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.ui.spoon.Spoon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.LeccionLayout.Tipo;
import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;

/**
 * Herramientas MCP del modo clase (docs/modo-clase-diseno.md §8). WP2: lesson_notes (la logica, sin Spoon, en
 * {@link LeccionNotas}). lesson_start/next/status/control llegan con el motor (WP3). Solo transformaciones.
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
