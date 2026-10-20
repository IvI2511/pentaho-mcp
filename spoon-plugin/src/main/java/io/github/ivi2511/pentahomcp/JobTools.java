package io.github.ivi2511.pentahomcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.job.JobMeta;
import org.pentaho.di.ui.spoon.Spoon;

/**
 * Herramientas para editar jobs (.kjb) abiertos en Spoon: entradas y hops. La logica vive en
 * {@link JobSoporte} (sin GUI); aca se resuelve el documento, se corre en el hilo de UI, se
 * registra el undo en Spoon y se refresca el lienzo.
 *
 * arrange_canvas y add_note (tambien para jobs) estan en TransTools.
 */
final class JobTools {

  private static final String CONDICION = "When the hop is followed: on_success (green, only if the previous "
    + "entry finished OK), on_failure (red, only if it failed) or always (black, unconditional). Hops leaving "
    + "START are always unconditional.";

  private JobTools() {
  }

  /**
   * Una edicion sobre el job: corre en UI, con un grupo de undo que va al historial de Spoon
   * (Editar > Deshacer, Ctrl+Z), y despues refresca. Cuantos Ctrl+Z lleva cada herramienta de jobs:
   * ver el comentario de undo en JobSoporte.
   */
  private interface Edicion {
    ObjectNode aplicar( Spoon sp, JobMeta jm, UndoGrupo undo ) throws Exception;
  }

  private static Object editar( final JsonNode a, final Edicion e ) throws Exception {
    return Docs.ui( () -> {
      Spoon sp = Docs.spoon();
      JobMeta jm = Docs.job( a );
      ObjectNode o = e.aplicar( sp, jm, new UndoGrupo( sp, jm ) );
      Docs.touched( jm );
      o.put( "document", Docs.id( jm ) );
      return o;
    } );
  }

  static void register( McpHttpServer s ) {

    s.add( Tools.tool( "list_entry_types",
      "Searches the job entry types installed in this Spoon. Use it before add_entry when you do not know the "
        + "exact id (<type>). Without arguments it returns the categories with their count; with 'search' "
        + "(words in the id, name or description) or 'category' it returns id, display name, category and "
        + "description. Common ids: TRANS (run a transformation), JOB (run a job), SUCCESS, ABORT, SQL, "
        + "SET_VARIABLES, WRITE_TO_LOG, FILE_EXISTS, EVAL, SIMPLE_EVAL. START and DUMMY are special: create "
        + "them with add_entry type=START / type=DUMMY.",
      Tools.schema().str( "search", "Words to search for (e.g. 'file exists', 'sql', 'mail')", false )
        .str( "category", "Category (or part of it), e.g. 'General', 'File management', 'Conditions'", false )
        .build(),
      a -> JobSoporte.tipos( Tools.optStr( a, "search", null ), Tools.optStr( a, "category", null ) ) ) );

    s.add( Tools.tool( "get_entry_template",
      "Default configuration XML of a job entry type, without the wrapper (<name>, <type>, position). Use it "
        + "before add_entry or configure_entry to see which tags it takes, then send in config_xml only the "
        + "ones that change. TRANS example: <specification_method>filename</specification_method>"
        + "<filename>${Internal.Entry.Current.Directory}/load.ktr</filename>.",
      Tools.schema().str( "type", "Type id (TRANS, JOB, SUCCESS, ABORT..., or START/DUMMY) or its display name",
        true ).build(),
      a -> {
        String t = JobSoporte.resolverTipo( Tools.str( a, "type" ) );
        if ( JobSoporte.SPECIAL.equals( t ) ) {
          t = JobSoporte.START;
        }
        return "<!-- " + t + " -->\n" + JobSoporte.plantilla( t );
      } ) );

    s.add( Tools.tool( "add_entry",
      "Adds an entry to the open job (it appears live on the canvas, selected) and, with 'after', connects it "
        + "with a hop. This is how to build a job step by step: START first (type=START, only one per job), then "
        + "each entry with after=<the previous one>. Position: with 'after', to its right (+150; if the spot is "
        + "taken, further down); with neither, to the right of the last entry; x/y force it. config_xml: only "
        + "the tags that change from get_entry_template. In a Transformation (TRANS) or Job (JOB) entry the path "
        + "should be relative to the job's folder: <specification_method>filename</specification_method>"
        + "<filename>${Internal.Entry.Current.Directory}/file.ktr</filename> (.kjb for JOB). Parameters for the "
        + "transformation: <parameters><pass_all_parameters>Y</pass_all_parameters><parameter><name>P</name>"
        + "<stream_name/><value>v</value></parameter></parameters>. To close a job: SUCCESS (on_success) and "
        + "ABORT (on_failure).",
      Tools.schema().document()
        .str( "type", "Entry type id: START, DUMMY, TRANS, JOB, SUCCESS, ABORT, SQL... (see list_entry_types)",
          true )
        .str( "name", "Entry name, unique in the job", true )
        .str( "config_xml", "Configuration: top-level tags that replace the template's ones (optional)", false )
        .integer( "x", "Canvas x position (optional)", false )
        .integer( "y", "Canvas y position (optional)", false )
        .str( "after", "Existing entry from which a hop goes to the new one (optional)", false )
        .strEnum( "condition", CONDICION + " Default on_success.", false, JobSoporte.SIEMPRE, JobSoporte.SI_OK,
          JobSoporte.SI_FALLA ).build(),
      a -> editar( a, ( sp, jm, u ) -> JobSoporte.agregar( jm, Tools.str( a, "type" ), Tools.optStr( a, "name",
        null ), Tools.optStr( a, "config_xml", null ), Tools.optInteger( a, "x" ), Tools.optInteger( a, "y" ),
        Tools.optStr( a, "after", null ), Tools.optStr( a, "condition", null ), sp.getRepository(),
        sp.getMetaStore(), u ) ) ) );

    s.add( Tools.tool( "configure_entry",
      "Changes the configuration of an existing entry: merges config_xml over its CURRENT configuration (each "
        + "tag you send replaces the existing one; the rest stays). Keeps name and position; undo with Ctrl+Z. "
        + "Use it to fix the path of a TRANS/JOB, the message of an ABORT, etc. Look at get_entry first. To "
        + "rename use rename_entry.",
      Tools.schema().document().str( "entry", "Entry name", true )
        .str( "config_xml", "Top-level tags to replace, e.g. <filename>${Internal.Entry.Current.Directory}"
          + "/other.ktr</filename>", true ).build(),
      a -> editar( a, ( sp, jm, u ) -> JobSoporte.configurar( jm, Tools.str( a, "entry" ),
        Tools.str( a, "config_xml" ), sp.getRepository(), sp.getMetaStore(), u ) ) ) );

    s.add( Tools.tool( "get_entry",
      "Reads a job entry: type, position, its configuration as XML (passwords masked) and the incoming and "
        + "outgoing hops with their condition. Use it before configure_entry or to review a job the user "
        + "opened. To see the whole job use get_document.",
      Tools.schema().document().str( "entry", "Entry name", true ).build(),
      a -> Docs.ui( () -> {
        JobMeta jm = Docs.job( a );
        ObjectNode o = JobSoporte.ver( jm, Tools.str( a, "entry" ) );
        o.put( "document", Docs.id( jm ) );
        return o;
      } ) ) );

    s.add( Tools.tool( "rename_entry",
      "Renames a job entry (its hops follow it). Fails if the name already exists. Undo with Ctrl+Z.",
      Tools.schema().document().str( "entry", "Current name", true )
        .str( "new_name", "New name, unique in the job", true ).build(),
      a -> editar( a, ( sp, jm, u ) -> JobSoporte.renombrar( jm, Tools.str( a, "entry" ),
        Tools.str( a, "new_name" ), u ) ) ) );

    s.add( Tools.tool( "move_entry",
      "Moves a job entry to another position on the canvas (x, y in pixels). To lay out the whole job at once "
        + "use arrange_canvas.",
      Tools.schema().document().str( "entry", "Entry name", true )
        .integer( "x", "x position", true ).integer( "y", "y position", true ).build(),
      a -> editar( a, ( sp, jm, u ) -> JobSoporte.mover( jm, Tools.str( a, "entry" ), Tools.integer( a, "x" ),
        Tools.integer( a, "y" ), u ) ) ) );

    s.add( Tools.tool( "delete_entry",
      "Deletes a job entry together with all its hops (incoming and outgoing). One Ctrl+Z restores it with its "
        + "hops.",
      Tools.schema().document().str( "entry", "Entry name", true ).build(),
      a -> editar( a, ( sp, jm, u ) -> JobSoporte.borrar( jm, Tools.str( a, "entry" ), u ) ) ) );

    s.add( Tools.tool( "connect_entries",
      "Creates a hop between two existing job entries. For branches: the same source with one on_success hop "
        + "and another on_failure hop (e.g. to an ABORT). Rejects duplicate hops, hops into START and hops that "
        + "close a loop (unless allow_loop=true, for intentional retries).",
      Tools.schema().document().str( "from", "Source entry", true ).str( "to", "Target entry", true )
        .strEnum( "condition", CONDICION + " Default on_success.", false, JobSoporte.SIEMPRE, JobSoporte.SI_OK,
          JobSoporte.SI_FALLA )
        .bool( "allow_loop", "Accept a hop that closes a cycle (default false)", false ).build(),
      a -> editar( a, ( sp, jm, u ) -> JobSoporte.conectar( jm, Tools.str( a, "from" ), Tools.str( a, "to" ),
        Tools.optStr( a, "condition", null ), Tools.optBool( a, "allow_loop", false ), u ) ) ) );

    s.add( Tools.tool( "disconnect_entries",
      "Deletes the hop between two job entries (the entries stay).",
      Tools.schema().document().str( "from", "Source entry", true ).str( "to", "Target entry", true ).build(),
      a -> editar( a, ( sp, jm, u ) -> JobSoporte.desconectar( jm, Tools.str( a, "from" ), Tools.str( a, "to" ),
        u ) ) ) );

    s.add( Tools.tool( "set_hop_condition",
      "Changes when an existing job hop is followed (always / on_success / on_failure) and/or enables or "
        + "disables it. Use it to turn a green hop into an error branch, or to switch a branch off without "
        + "deleting it.",
      Tools.schema().document().str( "from", "Source entry", true ).str( "to", "Target entry", true )
        .strEnum( "condition", CONDICION, false, JobSoporte.SIEMPRE, JobSoporte.SI_OK, JobSoporte.SI_FALLA )
        .bool( "enabled", "false to disable the hop (it turns grey), true to enable it", false ).build(),
      a -> editar( a, ( sp, jm, u ) -> JobSoporte.cambiarCondicion( jm, Tools.str( a, "from" ),
        Tools.str( a, "to" ), Tools.optStr( a, "condition", null ),
        a.hasNonNull( "enabled" ) ? Tools.optBool( a, "enabled", true ) : null, u ) ) ) );
  }
}
