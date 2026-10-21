package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.NotePadMeta;
import org.pentaho.di.core.gui.Point;
import org.pentaho.di.core.plugins.PluginInterface;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepErrorMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.ui.spoon.Spoon;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;

/**
 * Herramientas para editar en vivo la transformacion abierta: steps, hops, notas, seleccion y
 * orden del lienzo. La logica pura esta en TransOps / TransLayout; aca va el hilo de UI, el undo
 * de Spoon (Ctrl+Z del usuario) y el refresco.
 */
final class TransTools {

  private TransTools() {
  }

  static void register( McpHttpServer s ) {

    // ------------------------------------------------------------ catalogo

    s.add( Tools.tool( "list_step_types",
      "Searches the step types installed in this Spoon. Use it BEFORE add_step to find the exact id (the value "
        + "of <type>, e.g. CsvInput, FilterRows, TableOutput), which does not always look like the name shown on "
        + "screen. With no arguments it returns the categories and how many steps each one has. 'search' filters by "
        + "text in the id, name and description (case- and accent-insensitive).",
      Tools.schema()
        .str( "search", "Text to look for, e.g. 'csv', 'filter', 'lookup', 'excel'", false )
        .str( "category", "Restrict to one category (or part of its name). Category names come in Spoon's "
          + "language, e.g. 'Input' / 'Entrada', 'Transform' / 'Transformar'; call with no arguments to "
          + "list them", false ).build(),
      a -> tiposDeStep( Tools.optStr( a, "search", null ), Tools.optStr( a, "category", null ) ) ) );

    s.add( Tools.tool( "get_step_template",
      "Default configuration XML of a step type (what goes inside <step>, without name/type/GUI). Use it to see "
        + "which tags a step takes before add_step or configure_step: then send only the tags that change.",
      Tools.schema().str( "type", "Step type id (from list_step_types), e.g. 'SelectValues'", true ).build(),
      a -> {
        PluginInterface p = TransOps.plugin( Tools.str( a, "type" ) );
        try {
          String x = TransOps.plantilla( p );
          return x.trim().isEmpty()
            ? "<!-- " + TransOps.id( p ) + " has no configuration of its own: add it without config_xml -->" : x;
        } catch ( ToolError e ) {
          throw e;
        } catch ( Exception e ) {
          throw new ToolError( "Could not generate the template for " + TransOps.id( p ) + ": " + TransOps.mensaje( e ) );
        }
      } ) );

    // ------------------------------------------------------------ steps

    s.add( Tools.tool( "add_step",
      "Adds ONE step to the transformation and highlights it on the canvas: for one-off changes (adding or fixing "
        + "a step in a flow that already exists). To build a whole ETL or a stretch of several steps use "
        + "build_steps (one call, animated, a single Ctrl+Z). With 'after' it is placed to the right of that step "
        + "and connected to it with a hop. "
        + "config_xml carries only the tags that differ from get_step_template (the rest keeps its default); it "
        + "can be bare (<separator>;</separator><fields>...</fields>) or wrapped in <step>. References to other "
        + "steps (send_true_to, <from>...) are resolved automatically, even if the referenced step is added later. "
        + "Fails if a step with that name already exists. Without x/y or 'after', it goes to the right of the "
        + "rightmost step; with 'after' the hop is created even if you pass x/y. Wrapper tags (name, type, copies, "
        + "GUI...) are ignored.",
      Tools.schema().document()
        .str( "type", "Step type id (from list_step_types), e.g. 'CsvInput'", true )
        .str( "name", "Unique step name in the transformation", true )
        .str( "config_xml", "Configuration tags that differ from the template", false )
        .str( "after", "Existing step: the new one is placed to its right and the hop after -> new is created", false )
        .integer( "x", "Canvas x position (optional)", false )
        .integer( "y", "Canvas y position (optional)", false )
        .str( "description", "Step description (optional)", false )
        .strEnum( "output_mode", "If 'after' ends up with 2+ outputs: copy (every output gets all rows, the "
          + "default) or distribute (round robin)", false, "copy", "distribute" ).build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        TransMeta tm = Docs.trans( a );
        String despuesDe = Tools.optStr( a, "after", null );
        StepMeta previo = despuesDe == null ? null : TransOps.paso( tm, despuesDe );
        StepMeta previoAntes = previo == null ? null : (StepMeta) previo.clone();
        TransOps.Agregado r = TransOps.agregarStep( tm, Tools.str( a, "type" ), Tools.str( a, "name" ),
          Tools.optStr( a, "config_xml", null ), Tools.optInteger( a, "x" ), Tools.optInteger( a, "y" ),
          despuesDe, Tools.optStr( a, "description", null ), sp.getMetaStore() );
        // step + hop + modo de salida del previo: un solo Ctrl+Z
        UndoGrupo undo = new UndoGrupo( sp, tm );
        undo.nuevo( new Object[] { r.step }, new int[] { tm.indexOfStep( r.step ) } );
        String notaSalida = null;
        if ( r.hop != null ) {
          undo.nuevo( new Object[] { r.hop }, new int[] { tm.indexOfTransHop( r.hop ) } );
          boolean antes = previo.isDistributes();
          notaSalida = TransOps.aplicarModoSalida( tm, previo, Tools.optStr( a, "output_mode", null ) );
          if ( previo.isDistributes() != antes ) {
            undo.cambio( new Object[] { previoAntes }, new Object[] { previo.clone() },
              new int[] { tm.indexOfStep( previo ) } );
          }
        }
        Docs.touched( tm );
        ObjectNode o = TransOps.ficha( r.step );
        if ( r.hop != null ) {
          o.set( "hop", TransOps.fichaHop( r.hop ) );
          if ( TransOps.salidasNormales( tm, previo ) >= 2 ) {
            o.set( "output_mode_of", modoSalidaDe( previo ) );
          }
        }
        if ( r.aviso != null ) {
          o.put( "warning", r.aviso );
        }
        if ( notaSalida != null ) {
          o.put( "note", notaSalida );
        }
        return o;
      } ) ) );

    s.add( Tools.tool( "build_steps",
      "Builds several steps and their hops in ONE call, animated: the user watches the nodes appear on the canvas "
        + "one by one. Use it to lay out a whole ETL or a whole stretch of one (input -> transforms -> output, "
        + "branches, lookups); use add_step for a one-off change to an existing flow. Everything is "
        + "validated BEFORE touching the canvas (step types exist, names are unique in the document and in the batch, "
        + "'after'/'from'/'to' exist or are created earlier in this batch, config_xml is valid, no repeated hops, no "
        + "cycles): if anything is wrong nothing changes and you get the full list of problems. Per step: 'type' = "
        + "step type id (from list_step_types, e.g. CsvInput, SelectValues, TableOutput); 'name' = unique name; "
        + "'config_xml' = only the tags that differ from get_step_template (same rules as add_step); 'after' = an "
        + "existing step or an EARLIER step of this list: the new one goes to its right, connected by a hop; 'x'/'y' "
        + "force the position; 'output_mode' = how THIS step sends rows once it has 2+ outgoing hops: copy (default: "
        + "every output gets all rows) or distribute (round robin). 'hops' = extra connections that 'after' cannot "
        + "express: a second input (lookup stream, merge/join), the other branch of a Filter rows, error hops "
        + "(error=true). Filter rows / Switch-case targets go in config_xml (send_true_to...) AND need a hop "
        + "('after' or 'hops'); references by name in config_xml are resolved at the end, so they may point to steps "
        + "created later in the batch. 'arrange' (default true) runs the auto-layout at the end. The whole batch is a "
        + "single Ctrl+Z in Spoon. Returns the created steps (name, type, x, y), the hops, notes and warnings (e.g. a "
        + "database connection that does not exist).",
      Tools.schema().document()
        .raw( "steps", esquemaSteps(), true )
        .raw( "hops", esquemaHops(), false )
        .integer( "delay_ms", "Pause between nodes so the user can follow the build, in ms (default "
          + TransLote.DELAY_DEFECTO + ", max " + TransLote.DELAY_MAX + "; 0 = no animation)", false )
        .bool( "arrange", "Run the auto-layout (arrange_canvas) at the end (default true)", false ).build(),
      a -> TransLote.ejecutar( a ) ) );

    s.add( Tools.tool( "configure_step",
      "Changes the configuration of an existing step: config_xml is merged over its CURRENT configuration (each "
        + "tag you send replaces that whole tag; the ones you do not send stay as they are). Keeps the name, "
        + "position and hops. For lists (<fields>...) send the whole list. Look at get_step first.",
      Tools.schema().document()
        .str( "step", "Step name", true )
        .str( "config_xml", "Configuration tags to replace", true ).build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        TransMeta tm = Docs.trans( a );
        StepMeta sm = TransOps.paso( tm, Tools.str( a, "step" ) );
        StepMeta antes = (StepMeta) sm.clone();
        String aviso = TransOps.reconfigurar( tm, sm, Tools.str( a, "config_xml" ), sp.getMetaStore() );
        new UndoGrupo( sp, tm ).cambio( new Object[] { antes }, new Object[] { sm.clone() },
          new int[] { tm.indexOfStep( sm ) } );
        seleccionarSolo( tm, sm );
        Docs.touched( tm );
        ObjectNode o = TransOps.ficha( sm );
        if ( aviso != null ) {
          o.put( "warning", aviso );
        }
        return o;
      } ) ) );

    s.add( Tools.tool( "get_step",
      "Reads a step: its full XML (as in the .ktr, passwords masked), its position and its incoming and outgoing "
        + "hops. Use it before configure_step to see the current configuration.",
      Tools.schema().document().str( "step", "Step name", true ).build(),
      a -> Docs.ui( () -> {
        TransMeta tm = Docs.trans( a );
        return TransOps.ver( tm, TransOps.paso( tm, Tools.str( a, "step" ) ) );
      } ) ) );

    s.add( Tools.tool( "rename_step",
      "Renames a step. References from other steps (send_true_to, <from>...) are updated automatically. Fails if "
        + "there is already a step with the new name.",
      Tools.schema().document()
        .str( "step", "Current name", true )
        .str( "new_name", "New name", true ).build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        TransMeta tm = Docs.trans( a );
        StepMeta sm = TransOps.paso( tm, Tools.str( a, "step" ) );
        StepMeta antes = (StepMeta) sm.clone();
        List<StepMeta[]> otros = TransOps.renombrar( tm, sm, Tools.str( a, "new_name" ), sp.getMetaStore() );
        Object[] desde = new Object[ otros.size() + 1 ];
        Object[] hasta = new Object[ otros.size() + 1 ];
        int[] idx = new int[ otros.size() + 1 ];
        desde[ 0 ] = antes;
        hasta[ 0 ] = sm.clone();
        idx[ 0 ] = tm.indexOfStep( sm );
        ArrayNode refs = Tools.arr();
        for ( int i = 0; i < otros.size(); i++ ) {
          desde[ i + 1 ] = otros.get( i )[ 0 ];
          hasta[ i + 1 ] = otros.get( i )[ 1 ].clone();
          idx[ i + 1 ] = tm.indexOfStep( otros.get( i )[ 1 ] );
          refs.add( otros.get( i )[ 1 ].getName() );
        }
        new UndoGrupo( sp, tm ).cambio( desde, hasta, idx ); // el step y los que lo nombran: una sola accion
        seleccionarSolo( tm, sm );
        Docs.touched( tm );
        ObjectNode o = TransOps.ficha( sm ).put( "previous_name", antes.getName() );
        if ( refs.size() > 0 ) {
          o.set( "references_updated_in", refs );
        }
        return o;
      } ) ) );

    s.add( Tools.tool( "move_step",
      "Moves a step to another position on the canvas (pixel coordinates; ~150 of horizontal spacing between "
        + "steps). To arrange everything at once use arrange_canvas.",
      Tools.schema().document()
        .str( "step", "Step name", true )
        .integer( "x", "New x", true )
        .integer( "y", "New y", true ).build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        TransMeta tm = Docs.trans( a );
        StepMeta sm = TransOps.paso( tm, Tools.str( a, "step" ) );
        int x = Tools.integer( a, "x" );
        int y = Tools.integer( a, "y" );
        Point antes = new Point( sm.getLocation().x, sm.getLocation().y );
        sm.setLocation( x, y );
        new UndoGrupo( sp, tm ).posicion( new Object[] { sm }, new int[] { tm.indexOfStep( sm ) },
          new Point[] { antes }, new Point[] { new Point( x, y ) } );
        seleccionarSolo( tm, sm );
        Docs.touched( tm );
        return TransOps.ficha( sm );
      } ) ) );

    s.add( Tools.tool( "delete_step",
      "Deletes a step and all its hops (a single Ctrl+Z in Spoon brings it back).",
      Tools.schema().document().str( "step", "Step name", true ).build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        TransMeta tm = Docs.trans( a );
        StepMeta sm = TransOps.paso( tm, Tools.str( a, "step" ) );
        List<TransHopMeta> hops = TransOps.hopsDe( tm, sm );
        ArrayNode quitados = Tools.arr();
        UndoGrupo undo = new UndoGrupo( sp, tm );
        List<StepMeta> vecinos = new ArrayList<StepMeta>();
        for ( TransHopMeta h : hops ) {
          StepMeta v = h.getFromStep() == sm ? h.getToStep() : h.getFromStep();
          if ( !vecinos.contains( v ) ) {
            vecinos.add( v );
          }
        }
        Cambios c = Cambios.antes( vecinos );
        if ( !hops.isEmpty() ) {
          // Igual que SpoonStepsDelegate.delSteps: DELETE_HOP y despues DELETE_STEP (Spoon deshace ese
          // par junto); los cambios de los vecinos se encadenan con UndoGrupo. Un solo Ctrl+Z.
          Object[] clones = new Object[ hops.size() ];
          int[] idx = new int[ hops.size() ];
          for ( int i = 0; i < hops.size(); i++ ) {
            TransHopMeta h = hops.get( i );
            clones[ i ] = h.clone();
            idx[ i ] = tm.indexOfTransHop( h );
            quitados.add( TransOps.fichaHop( h ) );
          }
          for ( int i = hops.size() - 1; i >= 0; i-- ) {
            tm.removeTransHop( tm.indexOfTransHop( hops.get( i ) ) );
            TransOps.limpiarTrasQuitarHop( hops.get( i ) ); // que los vecinos olviden al step borrado
          }
          undo.borrado( clones, idx );
        }
        int i = tm.indexOfStep( sm );
        tm.removeStep( i );
        undo.borrado( new Object[] { sm }, new int[] { i } );
        c.registrar( undo, tm );
        Docs.touched( tm );
        ObjectNode o = Tools.obj();
        o.put( "deleted", sm.getName() );
        o.set( "deleted_hops", quitados );
        return o;
      } ) ) );

    // ------------------------------------------------------------ hops

    s.add( Tools.tool( "connect_steps",
      "Creates a hop between two steps (rows flow from 'from' to 'to'). Rejects duplicates and cycles. With "
        + "error=true it is an error hop: it enables the error handling of the source step with that target (the "
        + "step must support it). NOTE: in Filter rows (send_true_to/send_false_to) and in Switch/case "
        + "(case_value_target, default_target_step) the target is set in the step's XML with configure_step, AND "
        + "this hop is needed as well; one without the other does not work. If 'from' ends up with 2+ normal "
        + "outputs, Spoon would ask copy or distribute: here you choose it with output_mode (default copy = every "
        + "output gets all rows).",
      Tools.schema().document()
        .str( "from", "Source step", true )
        .str( "to", "Target step", true )
        .bool( "enabled", "Hop enabled (default true)", false )
        .bool( "error", "Error-handling hop (default false)", false )
        .strEnum( "output_mode", "With 2+ outputs: copy (default) or distribute (round robin)", false,
          "copy", "distribute" ).build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        TransMeta tm = Docs.trans( a );
        StepMeta desde = TransOps.paso( tm, Tools.str( a, "from" ) );
        StepMeta hacia = TransOps.paso( tm, Tools.str( a, "to" ) );
        boolean deError = Tools.optBool( a, "error", false );
        StepMeta antes = (StepMeta) desde.clone();
        TransHopMeta h = TransOps.conectar( tm, desde, hacia, Tools.optBool( a, "enabled", true ), deError );
        String nota = deError ? null
          : TransOps.aplicarModoSalida( tm, desde, Tools.optStr( a, "output_mode", null ) );
        boolean cambioStep = deError || desde.isDistributes() != antes.isDistributes();
        UndoGrupo undo = new UndoGrupo( sp, tm ); // hop + cambio del origen: un solo Ctrl+Z
        undo.nuevo( new Object[] { h }, new int[] { tm.indexOfTransHop( h ) } );
        if ( cambioStep ) {
          undo.cambio( new Object[] { antes }, new Object[] { desde.clone() }, new int[] { tm.indexOfStep( desde ) } );
        }
        Docs.touched( tm );
        ObjectNode o = TransOps.fichaHop( h );
        if ( !deError && TransOps.salidasNormales( tm, desde ) >= 2 ) {
          o.set( "output_mode_of", modoSalidaDe( desde ) );
        }
        if ( nota != null ) {
          o.put( "note", nota );
        }
        return o;
      } ) ) );

    s.add( Tools.tool( "set_output_mode",
      "Changes how a step with several outputs sends its rows (what Spoon asks when you draw the second hop): "
        + "'copy' = every output gets ALL the rows; 'distribute' = round robin, each row goes to only one output. "
        + "Does not apply to Filter rows or Switch/case, which send rows to specific targets.",
      Tools.schema().document()
        .str( "step", "Step name", true )
        .strEnum( "mode", "copy or distribute", true, "copy", "distribute" ).build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        TransMeta tm = Docs.trans( a );
        StepMeta sm = TransOps.paso( tm, Tools.str( a, "step" ) );
        StepMeta antes = (StepMeta) sm.clone();
        TransOps.aplicarModoSalida( tm, sm, Tools.str( a, "mode" ) );
        if ( sm.isDistributes() != antes.isDistributes() ) {
          new UndoGrupo( sp, tm ).cambio( new Object[] { antes }, new Object[] { sm.clone() },
            new int[] { tm.indexOfStep( sm ) } );
          Docs.touched( tm );
        }
        ObjectNode o = Tools.obj();
        o.put( "step", sm.getName() );
        o.put( "output_mode", TransOps.modoSalida( sm ) );
        o.put( "outputs", TransOps.salidasNormales( tm, sm ) );
        return o;
      } ) ) );

    s.add( Tools.tool( "set_error_handling",
      "Configures the error handling of a step (what Spoon shows in 'Define error handling...'): where its "
        + "failing rows go and which extra fields they carry (number of errors, description, field names, error "
        + "codes), plus the limits. Without 'target' it changes the existing error hop; with 'target' it also "
        + "creates the error hop if there is none. The step's own messages for rejected rows, if it has any (e.g. "
        + "Unique rows' error_description), are part of its config: use configure_step for those.",
      Tools.schema().document()
        .str( "step", "Step whose failing rows are handled", true )
        .str( "target", "Step that receives the failing rows (creates the error hop if missing)", false )
        .str( "count_field", "Name of the field with the number of errors of the row (empty = none)", false )
        .str( "description_field", "Name of the field with the error description, e.g. motivo_rechazo", false )
        .str( "fields_field", "Name of the field with the names of the fields in error", false )
        .str( "codes_field", "Name of the field with the error codes", false )
        .str( "max_errors", "Stop the transformation after this many errors (empty = no limit)", false )
        .str( "max_percent_errors", "Stop when this percentage of rows fails (empty = no limit)", false )
        .str( "min_rows_for_percent", "Rows to read before the percentage is checked", false )
        .bool( "enabled", "Error handling on (default true)", false ).build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        TransMeta tm = Docs.trans( a );
        StepMeta sm = TransOps.paso( tm, Tools.str( a, "step" ) );
        if ( !sm.supportsErrorHandling() ) {
          throw new ToolError( "Step '" + sm.getName() + "' (" + sm.getStepID() + ") does not support error handling" );
        }
        UndoGrupo undo = new UndoGrupo( sp, tm ); // hop nuevo + cambio del step: un solo Ctrl+Z
        StepMeta antes = (StepMeta) sm.clone();
        // StepMeta.clone comparte el StepErrorMeta: copia propia para que Ctrl+Z vuelva a lo de antes.
        antes.setStepErrorMeta( sm.getStepErrorMeta() == null ? null : sm.getStepErrorMeta().clone() );
        String destino = Tools.optStr( a, "target", null );
        if ( destino != null && !destino.trim().isEmpty() ) {
          StepMeta hacia = TransOps.paso( tm, destino );
          TransHopMeta existe = tm.findTransHop( sm, hacia, true );
          if ( existe == null ) {
            TransHopMeta h = TransOps.conectar( tm, sm, hacia, true, true );
            undo.nuevo( new Object[] { h.clone() }, new int[] { tm.indexOfTransHop( h ) } );
          } else if ( !existe.isErrorHop() ) {
            throw new ToolError( "There is already a normal hop from '" + sm.getName() + "' to '" + hacia.getName()
              + "'. Delete it with disconnect_steps and run this again to make it the error hop." );
          }
        }
        StepErrorMeta sem = sm.getStepErrorMeta();
        if ( sem == null || sem.getTargetStep() == null ) {
          throw new ToolError( "'" + sm.getName() + "' has no error hop yet: pass 'target' (the step that receives "
            + "the failing rows)" );
        }
        if ( a.has( "count_field" ) ) {
          sem.setNrErrorsValuename( Tools.optStr( a, "count_field", "" ) );
        }
        if ( a.has( "description_field" ) ) {
          sem.setErrorDescriptionsValuename( Tools.optStr( a, "description_field", "" ) );
        }
        if ( a.has( "fields_field" ) ) {
          sem.setErrorFieldsValuename( Tools.optStr( a, "fields_field", "" ) );
        }
        if ( a.has( "codes_field" ) ) {
          sem.setErrorCodesValuename( Tools.optStr( a, "codes_field", "" ) );
        }
        if ( a.has( "max_errors" ) ) {
          sem.setMaxErrors( Tools.optStr( a, "max_errors", "" ) );
        }
        if ( a.has( "max_percent_errors" ) ) {
          sem.setMaxPercentErrors( Tools.optStr( a, "max_percent_errors", "" ) );
        }
        if ( a.has( "min_rows_for_percent" ) ) {
          sem.setMinPercentRows( Tools.optStr( a, "min_rows_for_percent", "" ) );
        }
        sem.setEnabled( Tools.optBool( a, "enabled", true ) );
        StepMeta despues = (StepMeta) sm.clone();
        despues.setStepErrorMeta( sem.clone() );
        undo.cambio( new Object[] { antes }, new Object[] { despues }, new int[] { tm.indexOfStep( sm ) } );
        tm.clearCaches();
        Docs.touched( tm );
        ObjectNode o = Tools.obj();
        o.put( "step", sm.getName() );
        o.put( "errors_to", sem.getTargetStep().getName() );
        o.put( "enabled", sem.isEnabled() );
        o.put( "count_field", nulo( sem.getNrErrorsValuename() ) );
        o.put( "description_field", nulo( sem.getErrorDescriptionsValuename() ) );
        o.put( "fields_field", nulo( sem.getErrorFieldsValuename() ) );
        o.put( "codes_field", nulo( sem.getErrorCodesValuename() ) );
        o.put( "max_errors", nulo( sem.getMaxErrors() ) );
        o.put( "max_percent_errors", nulo( sem.getMaxPercentErrors() ) );
        o.put( "min_rows_for_percent", nulo( sem.getMinPercentRows() ) );
        return o;
      } ) ) );

    s.add( Tools.tool( "disconnect_steps",
      "Deletes the hop between two steps (the steps stay). If it was the error hop, it turns off the error "
        + "handling of the source step.",
      Tools.schema().document()
        .str( "from", "Source step", true )
        .str( "to", "Target step", true ).build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        TransMeta tm = Docs.trans( a );
        TransHopMeta h = TransOps.hop( tm, Tools.str( a, "from" ), Tools.str( a, "to" ) );
        StepMeta desde = h.getFromStep();
        StepMeta hacia = h.getToStep();
        List<StepMeta> extremos = new ArrayList<StepMeta>();
        extremos.add( desde );
        extremos.add( hacia );
        Cambios c = Cambios.antes( extremos );
        int idx = tm.indexOfTransHop( h );
        UndoGrupo undo = new UndoGrupo( sp, tm );
        undo.borrado( new Object[] { h.clone() }, new int[] { idx } );
        tm.removeTransHop( idx );
        TransOps.limpiarTrasQuitarHop( h );
        c.registrar( undo, tm ); // si algun extremo cambio su configuracion, se deshace junto con el hop
        Docs.touched( tm );
        return TransOps.fichaHop( h ).put( "deleted", true );
      } ) ) );

    s.add( Tools.tool( "set_hop_enabled",
      "Enables or disables a hop without deleting it (a disabled hop passes no rows; it is drawn grey).",
      Tools.schema().document()
        .str( "from", "Source step", true )
        .str( "to", "Target step", true )
        .bool( "enabled", "true to enable, false to disable", true ).build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        TransMeta tm = Docs.trans( a );
        if ( a.get( "enabled" ) == null || a.get( "enabled" ).isNull() ) {
          throw new ToolError( "Missing argument 'enabled'" );
        }
        TransHopMeta h = TransOps.hop( tm, Tools.str( a, "from" ), Tools.str( a, "to" ) );
        boolean hab = Tools.optBool( a, "enabled", true );
        if ( h.isEnabled() != hab ) {
          Object antes = h.clone();
          h.setEnabled( hab );
          new UndoGrupo( sp, tm ).cambio( new Object[] { antes }, new Object[] { h.clone() },
            new int[] { tm.indexOfTransHop( h ) } );
          tm.clearCaches();
          Docs.touched( tm );
        }
        return TransOps.fichaHop( h ).put( "enabled", h.isEnabled() );
      } ) ) );

    // ------------------------------------------------------------ lienzo (transformaciones y jobs)

    s.add( Tools.tool( "add_note",
      "Adds a note (sticky note) to the canvas of a transformation or a job, to document what a part of it does. "
        + "Without x/y it goes below everything else on the canvas.",
      Tools.schema().document()
        .str( "text", "Note text (may contain line breaks)", true )
        .integer( "x", "x position (optional)", false )
        .integer( "y", "y position (optional)", false )
        .integer( "width", "Minimum width in pixels (optional; it grows with the text)", false )
        .integer( "height", "Minimum height in pixels (optional)", false ).build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        AbstractMeta m = Docs.resolver( a );
        String texto = Tools.str( a, "text" );
        Integer x = Tools.optInteger( a, "x" );
        Integer y = Tools.optInteger( a, "y" );
        if ( y == null ) {
          y = debajoDeTodo( m );
        }
        NotePadMeta n = new NotePadMeta( texto, x == null ? 100 : x, y, Tools.optInt( a, "width", 20 ),
          Tools.optInt( a, "height", 20 ) );
        m.addNote( n );
        new UndoGrupo( sp, m ).nuevo( new Object[] { n }, new int[] { m.indexOfNote( n ) } );
        Docs.touched( m );
        ObjectNode o = Tools.obj();
        o.put( "index", m.indexOfNote( n ) );
        o.put( "x", n.getLocation().x );
        o.put( "y", n.getLocation().y );
        return o;
      } ) ) );

    s.add( Tools.tool( "select_steps",
      "Highlights steps (or the entries of a job) on the canvas so the user sees which ones you are talking about. "
        + "Does not change the document. Empty list = clear the selection.",
      Tools.schema().document()
        .strList( "steps", "Names of the steps or entries to highlight", true ).build(),
      a -> {
        final List<String> nombres = new ArrayList<String>();
        JsonNode l = a.get( "steps" );
        if ( l != null && l.isArray() ) {
          for ( JsonNode n : l ) {
            nombres.add( n.asText() );
          }
        } else if ( l != null && !l.isNull() && !l.asText().trim().isEmpty() ) {
          nombres.add( l.asText() );
        }
        return Docs.ui( () -> seleccionar( Docs.resolver( a ), nombres ) );
      } ) );

    s.add( Tools.tool( "arrange_canvas",
      "Automatically arranges the canvas of a transformation or a job from left to right following the hops "
        + "(each level in a column, branches one below the other; in jobs START goes on the left). Use it when you "
        + "finish building or when the drawing got messy. Notes are not moved. Undo with Ctrl+Z.",
      Tools.schema().document().build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        AbstractMeta m = Docs.resolver( a );
        // el mismo camino que el boton «Ordenar lienzo» de la barra del lienzo
        TransLayout.Movimientos mov = OrdenarLienzo.ordenar( sp, m );
        ObjectNode o = Tools.obj();
        o.put( "moved", mov.objetos.size() );
        o.put( "columns", mov.columnas );
        return o;
      } ) ) );
  }

  // ---------------------------------------------------------------- ayudas

  /** {"step": nombre, "output_mode": "copy"|"distribute"}: el modo de salida del step previo, con clave fija. */
  private static ObjectNode modoSalidaDe( StepMeta sm ) {
    return Tools.obj().put( "step", sm.getName() ).put( "output_mode", TransOps.modoSalida( sm ) );
  }

  /** JSON Schema del argumento 'steps' de build_steps. */
  private static ObjectNode esquemaSteps() {
    ObjectNode item = Tools.obj().put( "type", "object" );
    ObjectNode p = item.putObject( "properties" );
    p.putObject( "type" ).put( "type", "string" ).put( "description",
      "Step type id (from list_step_types), e.g. 'CsvInput', 'FilterRows', 'TableOutput'" );
    p.putObject( "name" ).put( "type", "string" ).put( "description", "Unique step name" );
    p.putObject( "config_xml" ).put( "type", "string" ).put( "description",
      "Only the configuration tags that differ from get_step_template" );
    p.putObject( "after" ).put( "type", "string" ).put( "description",
      "Existing step, or a step EARLIER in this list: placed to its right and connected after -> this" );
    p.putObject( "x" ).put( "type", "integer" ).put( "description", "Canvas x (optional)" );
    p.putObject( "y" ).put( "type", "integer" ).put( "description", "Canvas y (optional)" );
    ObjectNode modo = p.putObject( "output_mode" ).put( "type", "string" ).put( "description",
      "How this step sends rows when it has 2+ outgoing hops: copy (default) or distribute (round robin)" );
    modo.putArray( "enum" ).add( "copy" ).add( "distribute" );
    item.putArray( "required" ).add( "type" ).add( "name" );
    ObjectNode s = Tools.obj().put( "type", "array" ).put( "minItems", 1 ).put( "maxItems", TransLote.MAX_STEPS )
      .put( "description", "Steps to create, in order (each 'after' must name an existing step or an earlier one)" );
    s.set( "items", item );
    return s;
  }

  /** JSON Schema del argumento 'hops' de build_steps. */
  private static ObjectNode esquemaHops() {
    ObjectNode item = Tools.obj().put( "type", "object" );
    ObjectNode p = item.putObject( "properties" );
    p.putObject( "from" ).put( "type", "string" ).put( "description", "Source step (existing or in 'steps')" );
    p.putObject( "to" ).put( "type", "string" ).put( "description", "Target step (existing or in 'steps')" );
    p.putObject( "error" ).put( "type", "boolean" ).put( "description",
      "Error-handling hop: rows that fail in 'from' go to 'to' (default false)" );
    p.putObject( "enabled" ).put( "type", "boolean" ).put( "description", "Hop enabled (default true)" );
    item.putArray( "required" ).add( "from" ).add( "to" );
    ObjectNode s = Tools.obj().put( "type", "array" ).put( "description",
      "Extra hops that 'after' cannot express (lookup streams, merges, second Filter rows branch, error hops)" );
    s.set( "items", item );
    return s;
  }

  /**
   * Foto de unos steps antes de una operacion que puede cambiarles la configuracion de rebote
   * (limpiezas tras quitar un hop, hops nuevos, referencias resueltas). registrar() agrega al grupo
   * de undo un cambio solo para los que cambiaron. (UI)
   */
  static final class Cambios {
    private final List<StepMeta> steps = new ArrayList<StepMeta>();
    private final List<StepMeta> clones = new ArrayList<StepMeta>();
    private final List<String> xmls = new ArrayList<String>();

    static Cambios antes( List<StepMeta> l ) {
      Cambios c = new Cambios();
      for ( StepMeta s : l ) {
        c.steps.add( s );
        c.clones.add( (StepMeta) s.clone() );
        c.xmls.add( xml( s ) );
      }
      return c;
    }

    void registrar( UndoGrupo undo, TransMeta tm ) {
      List<Object> desde = new ArrayList<Object>();
      List<Object> hasta = new ArrayList<Object>();
      List<Integer> idx = new ArrayList<Integer>();
      for ( int i = 0; i < steps.size(); i++ ) {
        StepMeta s = steps.get( i );
        int k = tm.indexOfStep( s );
        String x = xml( s );
        if ( k >= 0 && ( x == null || !x.equals( xmls.get( i ) ) ) ) {
          desde.add( clones.get( i ) );
          hasta.add( s.clone() );
          idx.add( k );
        }
      }
      if ( !idx.isEmpty() ) {
        int[] ix = new int[ idx.size() ];
        for ( int i = 0; i < ix.length; i++ ) {
          ix[ i ] = idx.get( i );
        }
        undo.cambio( desde.toArray(), hasta.toArray(), ix );
      }
    }

    private static String xml( StepMeta s ) {
      try {
        // el manejo de errores no esta en el <step>: se compara aparte
        return s.getXML() + "|errores=" + s.isDoingErrorHandling();
      } catch ( Exception e ) {
        return null;
      }
    }
  }

  private static void seleccionarSolo( TransMeta tm, StepMeta sm ) {
    tm.unselectAll();
    sm.setSelected( true );
  }

  /** y para algo nuevo debajo de todo lo que hay en el lienzo. */
  private static int debajoDeTodo( AbstractMeta m ) {
    int max = -1;
    if ( m instanceof TransMeta ) {
      for ( StepMeta s : ( (TransMeta) m ).getSteps() ) {
        max = Math.max( max, s.getLocation().y + 60 );
      }
    } else if ( m instanceof JobMeta ) {
      for ( JobEntryCopy c : ( (JobMeta) m ).getJobCopies() ) {
        max = Math.max( max, c.getLocation().y + 60 );
      }
    }
    for ( int i = 0; i < m.nrNotes(); i++ ) {
      NotePadMeta n = m.getNote( i );
      max = Math.max( max, n.getLocation().y + Math.max( n.getHeight(), 40 ) );
    }
    return max < 0 ? 100 : max + 40;
  }

  /** Resalta los nombres pedidos. (UI) */
  static ObjectNode seleccionar( AbstractMeta m, List<String> nombres ) throws ToolError {
    List<String> faltan = new ArrayList<String>();
    List<String> marcados = new ArrayList<String>();
    if ( m instanceof TransMeta ) {
      TransMeta tm = (TransMeta) m;
      List<StepMeta> sel = new ArrayList<StepMeta>();
      for ( String n : nombres ) {
        StepMeta sm = tm.findStep( n );
        if ( sm == null ) {
          faltan.add( n );
        } else {
          sel.add( sm );
        }
      }
      if ( !faltan.isEmpty() ) {
        throw new ToolError( "Not found: " + faltan + ". " + TransOps.nombres( tm ) );
      }
      tm.unselectAll();
      for ( StepMeta sm : sel ) {
        sm.setSelected( true );
        marcados.add( sm.getName() );
      }
    } else if ( m instanceof JobMeta ) {
      JobMeta jm = (JobMeta) m;
      List<JobEntryCopy> sel = new ArrayList<JobEntryCopy>();
      for ( String n : nombres ) {
        JobEntryCopy c = jm.findJobEntry( n );
        if ( c == null ) {
          faltan.add( n );
        } else {
          sel.add( c );
        }
      }
      if ( !faltan.isEmpty() ) {
        StringBuilder hay = new StringBuilder();
        for ( JobEntryCopy c : jm.getJobCopies() ) {
          hay.append( hay.length() == 0 ? "'" : ", '" ).append( c.getName() ).append( '\'' );
        }
        throw new ToolError( "Not found: " + faltan + ". "
          + ( hay.length() == 0 ? "The job has no entries." : "Entries: " + hay ) );
      }
      jm.unselectAll();
      for ( JobEntryCopy c : sel ) {
        c.setSelected( true );
        marcados.add( c.getName() );
      }
    }
    Docs.redibujar( m );
    ObjectNode o = Tools.obj();
    ArrayNode arr = o.putArray( "selected" );
    for ( String n : marcados ) {
      arr.add( n );
    }
    return o;
  }

  /** list_step_types: categorias, o los que coinciden con search/category. */
  static ObjectNode tiposDeStep( String buscar, String categoria ) {
    List<PluginInterface> todos = TransOps.plugins();
    ObjectNode o = Tools.obj();
    if ( buscar == null && categoria == null ) {
      Map<String, Integer> cats = new LinkedHashMap<String, Integer>();
      for ( PluginInterface p : todos ) {
        String c = p.getCategory() == null ? "" : p.getCategory();
        Integer n = cats.get( c );
        cats.put( c, n == null ? 1 : n + 1 );
      }
      ArrayNode arr = o.putArray( "categories" );
      for ( Map.Entry<String, Integer> e : cats.entrySet() ) {
        arr.addObject().put( "category", e.getKey() ).put( "count", e.getValue() );
      }
      o.put( "total", todos.size() );
      o.put( "note", "Pass 'search' (text) or 'category' to see the step types." );
      return o;
    }
    String q = TransOps.norm( buscar );
    String cat = TransOps.norm( categoria );
    List<PluginInterface> exactos = new ArrayList<PluginInterface>();
    List<PluginInterface> enIdONombre = new ArrayList<PluginInterface>();
    List<PluginInterface> enDescripcion = new ArrayList<PluginInterface>();
    for ( PluginInterface p : todos ) {
      if ( !cat.isEmpty() && !TransOps.norm( p.getCategory() ).contains( cat ) ) {
        continue;
      }
      String id = TransOps.norm( TransOps.id( p ) );
      String nom = TransOps.norm( p.getName() );
      if ( q.isEmpty() || id.equals( q ) || nom.equals( q ) ) {
        exactos.add( p );
      } else if ( id.contains( q ) || nom.contains( q ) ) {
        enIdONombre.add( p );
      } else if ( TransOps.norm( p.getDescription() ).contains( q ) ) {
        enDescripcion.add( p );
      }
    }
    List<PluginInterface> hits = new ArrayList<PluginInterface>( exactos );
    hits.addAll( enIdONombre );
    hits.addAll( enDescripcion );
    final int max = 40;
    ArrayNode arr = o.putArray( "types" );
    for ( int i = 0; i < hits.size() && i < max; i++ ) {
      PluginInterface p = hits.get( i );
      arr.addObject().put( "id", TransOps.id( p ) ).put( "name", p.getName() ).put( "category", p.getCategory() )
        .put( "description", p.getDescription() );
    }
    o.put( "total", hits.size() );
    if ( hits.size() > max ) {
      o.put( "note", hits.size() + " matches; showing " + max + ". Narrow 'search' or 'category'." );
    }
    if ( hits.isEmpty() ) {
      o.put( "note", "Nothing matches. " + TransOps.sugerencias( buscar == null ? categoria : buscar ) );
    }
    return o;
  }

  /** Texto para la respuesta: "" en vez de null. */
  private static String nulo( String s ) {
    return s == null ? "" : s;
  }
}
