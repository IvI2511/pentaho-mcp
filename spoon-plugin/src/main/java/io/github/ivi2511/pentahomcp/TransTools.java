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

    s.add( Tools.tool( "tipos_de_step",
      "Busca tipos de step instalados en este Spoon. Usala ANTES de agregar_step para encontrar el id exacto "
        + "(el valor de <type>, p.ej. CsvInput, FilterRows, TableOutput), que no siempre se parece al nombre en "
        + "pantalla. Sin argumentos devuelve las categorias con cuantos steps tiene cada una. 'buscar' filtra por "
        + "texto en id, nombre y descripcion (sin distinguir mayusculas ni tildes, en ingles o castellano).",
      Tools.schema()
        .str( "buscar", "Texto a buscar, p.ej. 'csv', 'filtrar', 'lookup', 'excel'", false )
        .str( "categoria", "Limitar a una categoria (p.ej. 'Input', 'Transform', 'Flow')", false ).build(),
      a -> tiposDeStep( Tools.optStr( a, "buscar", null ), Tools.optStr( a, "categoria", null ) ) ) );

    s.add( Tools.tool( "plantilla_step",
      "XML de configuracion por defecto de un tipo de step (lo que va dentro de <step>, sin name/type/GUI). "
        + "Usala para ver que tags lleva un step antes de agregar_step o configurar_step: despues manda solo "
        + "los tags que cambian.",
      Tools.schema().str( "tipo", "Id del tipo de step (de tipos_de_step), p.ej. 'SelectValues'", true ).build(),
      a -> {
        PluginInterface p = TransOps.plugin( Tools.str( a, "tipo" ) );
        try {
          String x = TransOps.plantilla( p );
          return x.trim().isEmpty()
            ? "<!-- " + TransOps.id( p ) + " no tiene configuracion propia: agregalo sin config_xml -->" : x;
        } catch ( ToolError e ) {
          throw e;
        } catch ( Exception e ) {
          throw new ToolError( "No se pudo generar la plantilla de " + TransOps.id( p ) + ": " + TransOps.mensaje( e ) );
        }
      } ) );

    // ------------------------------------------------------------ steps

    s.add( Tools.tool( "agregar_step",
      "Agrega UN step a la transformacion y lo deja resaltado en el lienzo: para cambios puntuales (sumar o "
        + "corregir un step en un flujo que ya existe). Para armar un ETL entero o un tramo de varios steps usa "
        + "build_steps (una llamada, animada, un solo Ctrl+Z). Con 'despues_de' queda a la derecha del anterior y "
        + "conectado con un hop. "
        + "config_xml lleva solo los tags que cambian respecto de plantilla_step (lo demas queda por defecto); "
        + "puede ir suelto (<separator>;</separator><fields>...</fields>) o envuelto en <step>. Las referencias "
        + "a otros steps (send_true_to, <from>...) se resuelven solas, aunque el step referido se agregue despues. "
        + "Falla si ya existe un step con ese nombre. Sin x/y ni despues_de, va a la derecha del step mas a la derecha; "
        + "con despues_de el hop se crea igual aunque pases x/y. Los tags del envoltorio (name, type, copies, GUI...) "
        + "se ignoran.",
      Tools.schema().documento()
        .str( "tipo", "Id del tipo de step (de tipos_de_step), p.ej. 'CsvInput'", true )
        .str( "nombre", "Nombre unico del step en la transformacion", true )
        .str( "config_xml", "Tags de configuracion que cambian respecto de la plantilla", false )
        .str( "despues_de", "Step existente: el nuevo se ubica a su derecha y se crea el hop despues_de -> nuevo", false )
        .integer( "x", "Posicion x en el lienzo (opcional)", false )
        .integer( "y", "Posicion y en el lienzo (opcional)", false )
        .str( "descripcion", "Descripcion del step (opcional)", false )
        .strEnum( "modo_salida", "Si 'despues_de' queda con 2+ salidas: copiar (todas las filas a cada salida, "
          + "por defecto) o repartir (round robin)", false, "copiar", "repartir" ).build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        TransMeta tm = Docs.trans( a );
        String despuesDe = Tools.optStr( a, "despues_de", null );
        StepMeta previo = despuesDe == null ? null : TransOps.paso( tm, despuesDe );
        StepMeta previoAntes = previo == null ? null : (StepMeta) previo.clone();
        TransOps.Agregado r = TransOps.agregarStep( tm, Tools.str( a, "tipo" ), Tools.str( a, "nombre" ),
          Tools.optStr( a, "config_xml", null ), Tools.optInteger( a, "x" ), Tools.optInteger( a, "y" ),
          despuesDe, Tools.optStr( a, "descripcion", null ), sp.getMetaStore() );
        // step + hop + modo de salida del previo: un solo Ctrl+Z
        UndoGrupo undo = new UndoGrupo( sp, tm );
        undo.nuevo( new Object[] { r.step }, new int[] { tm.indexOfStep( r.step ) } );
        String notaSalida = null;
        if ( r.hop != null ) {
          undo.nuevo( new Object[] { r.hop }, new int[] { tm.indexOfTransHop( r.hop ) } );
          boolean antes = previo.isDistributes();
          notaSalida = TransOps.aplicarModoSalida( tm, previo, Tools.optStr( a, "modo_salida", null ) );
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
            o.put( "modo_salida_de_" + previo.getName(), TransOps.modoSalida( previo ) );
          }
        }
        if ( r.aviso != null ) {
          o.put( "aviso", r.aviso );
        }
        if ( notaSalida != null ) {
          o.put( "nota", notaSalida );
        }
        return o;
      } ) ) );

    s.add( Tools.tool( "build_steps",
      "Builds several steps and their hops in ONE call, animated: the user watches the nodes appear on the canvas "
        + "one by one. Use it to lay out a whole ETL or a whole stretch of one (input -> transforms -> output, "
        + "branches, lookups); use agregar_step (add_step) for a one-off change to an existing flow. Everything is "
        + "validated BEFORE touching the canvas (step types exist, names are unique in the document and in the batch, "
        + "'after'/'from'/'to' exist or are created earlier in this batch, config_xml is valid, no repeated hops, no "
        + "cycles): if anything is wrong nothing changes and you get the full list of problems. Per step: 'type' = "
        + "step type id (from tipos_de_step, e.g. CsvInput, SelectValues, TableOutput); 'name' = unique name; "
        + "'config_xml' = only the tags that differ from plantilla_step (same rules as agregar_step); 'after' = an "
        + "existing step or an EARLIER step of this list: the new one goes to its right, connected by a hop; 'x'/'y' "
        + "force the position; 'output_mode' = how THIS step sends rows once it has 2+ outgoing hops: copy (default: "
        + "every output gets all rows) or distribute (round robin). 'hops' = extra connections that 'after' cannot "
        + "express: a second input (lookup stream, merge/join), the other branch of a Filter rows, error hops "
        + "(error=true). Filter rows / Switch-case targets go in config_xml (send_true_to...) AND need a hop "
        + "('after' or 'hops'); references by name in config_xml are resolved at the end, so they may point to steps "
        + "created later in the batch. 'arrange' (default true) runs the auto-layout at the end. The whole batch is a "
        + "single Ctrl+Z in Spoon. Returns the created steps (name, type, x, y), the hops, notes and warnings (e.g. a "
        + "database connection that does not exist).",
      Tools.schema().documento()
        .raw( "steps", esquemaSteps(), true )
        .raw( "hops", esquemaHops(), false )
        .integer( "delay_ms", "Pause between nodes so the user can follow the build, in ms (default "
          + TransLote.DELAY_DEFECTO + ", max " + TransLote.DELAY_MAX + "; 0 = no animation)", false )
        .bool( "arrange", "Run the auto-layout (ordenar_lienzo) at the end (default true)", false ).build(),
      a -> TransLote.ejecutar( a ) ) );

    s.add( Tools.tool( "configurar_step",
      "Cambia la configuracion de un step existente: config_xml se mezcla sobre su configuracion ACTUAL "
        + "(cada tag que mandes reemplaza a ese tag completo; los que no mandes quedan como estan). Mantiene "
        + "nombre, posicion y hops. Para listas (<fields>...) manda la lista entera. Mira ver_step antes.",
      Tools.schema().documento()
        .str( "step", "Nombre del step", true )
        .str( "config_xml", "Tags de configuracion a reemplazar", true ).build(),
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
          o.put( "aviso", aviso );
        }
        return o;
      } ) ) );

    s.add( Tools.tool( "ver_step",
      "Lee un step: su XML completo (igual que en el .ktr, contrasenias enmascaradas), su posicion y sus hops "
        + "de entrada y de salida. Usala antes de configurar_step para ver la configuracion actual.",
      Tools.schema().documento().str( "step", "Nombre del step", true ).build(),
      a -> Docs.ui( () -> {
        TransMeta tm = Docs.trans( a );
        return TransOps.ver( tm, TransOps.paso( tm, Tools.str( a, "step" ) ) );
      } ) ) );

    s.add( Tools.tool( "renombrar_step",
      "Cambia el nombre de un step. Las referencias de otros steps (send_true_to, <from>...) se actualizan "
        + "solas. Falla si ya hay un step con el nombre nuevo.",
      Tools.schema().documento()
        .str( "step", "Nombre actual", true )
        .str( "nuevo_nombre", "Nombre nuevo", true ).build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        TransMeta tm = Docs.trans( a );
        StepMeta sm = TransOps.paso( tm, Tools.str( a, "step" ) );
        StepMeta antes = (StepMeta) sm.clone();
        List<StepMeta[]> otros = TransOps.renombrar( tm, sm, Tools.str( a, "nuevo_nombre" ), sp.getMetaStore() );
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
        ObjectNode o = TransOps.ficha( sm ).put( "nombre_anterior", antes.getName() );
        if ( refs.size() > 0 ) {
          o.set( "referencias_actualizadas_en", refs );
        }
        return o;
      } ) ) );

    s.add( Tools.tool( "mover_step",
      "Mueve un step a otra posicion del lienzo (coordenadas en pixeles; ~150 de separacion horizontal entre "
        + "steps). Para acomodar todo de una vez usa ordenar_lienzo.",
      Tools.schema().documento()
        .str( "step", "Nombre del step", true )
        .integer( "x", "Nueva x", true )
        .integer( "y", "Nueva y", true ).build(),
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

    s.add( Tools.tool( "borrar_step",
      "Borra un step y todos sus hops (un solo Ctrl+Z en Spoon lo recupera).",
      Tools.schema().documento().str( "step", "Nombre del step", true ).build(),
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
        o.put( "borrado", sm.getName() );
        o.set( "hops_borrados", quitados );
        return o;
      } ) ) );

    // ------------------------------------------------------------ hops

    s.add( Tools.tool( "conectar",
      "Crea un hop entre dos steps (las filas van de 'desde' a 'hacia'). Rechaza duplicados y ciclos. "
        + "Con de_error=true el hop es de errores: activa el manejo de errores del step origen con ese destino "
        + "(el step tiene que admitirlo). OJO: en Filter rows (send_true_to/send_false_to) y en Switch/case "
        + "(case_value_target, default_target_step) el destino se configura en el XML del step con "
        + "configurar_step, y ADEMAS hace falta este hop; uno sin el otro no funciona. Si 'desde' queda con 2+ "
        + "salidas normales, Spoon preguntaria copiar o repartir: aca se elige con modo_salida (por defecto "
        + "copiar = cada salida recibe todas las filas).",
      Tools.schema().documento()
        .str( "desde", "Step origen", true )
        .str( "hacia", "Step destino", true )
        .bool( "habilitado", "Hop habilitado (por defecto true)", false )
        .bool( "de_error", "Hop de manejo de errores (por defecto false)", false )
        .strEnum( "modo_salida", "Con 2+ salidas: copiar (por defecto) o repartir (round robin)", false,
          "copiar", "repartir" ).build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        TransMeta tm = Docs.trans( a );
        StepMeta desde = TransOps.paso( tm, Tools.str( a, "desde" ) );
        StepMeta hacia = TransOps.paso( tm, Tools.str( a, "hacia" ) );
        boolean deError = Tools.optBool( a, "de_error", false );
        StepMeta antes = (StepMeta) desde.clone();
        TransHopMeta h = TransOps.conectar( tm, desde, hacia, Tools.optBool( a, "habilitado", true ), deError );
        String nota = deError ? null
          : TransOps.aplicarModoSalida( tm, desde, Tools.optStr( a, "modo_salida", null ) );
        boolean cambioStep = deError || desde.isDistributes() != antes.isDistributes();
        UndoGrupo undo = new UndoGrupo( sp, tm ); // hop + cambio del origen: un solo Ctrl+Z
        undo.nuevo( new Object[] { h }, new int[] { tm.indexOfTransHop( h ) } );
        if ( cambioStep ) {
          undo.cambio( new Object[] { antes }, new Object[] { desde.clone() }, new int[] { tm.indexOfStep( desde ) } );
        }
        Docs.touched( tm );
        ObjectNode o = TransOps.fichaHop( h );
        if ( !deError && TransOps.salidasNormales( tm, desde ) >= 2 ) {
          o.put( "modo_salida_de_" + desde.getName(), TransOps.modoSalida( desde ) );
        }
        if ( nota != null ) {
          o.put( "nota", nota );
        }
        return o;
      } ) ) );

    s.add( Tools.tool( "modo_salida",
      "Cambia como reparte las filas un step con varias salidas (lo que Spoon pregunta al dibujar el segundo "
        + "hop): 'copiar' = cada salida recibe TODAS las filas; 'repartir' = round robin, cada fila va a una sola "
        + "salida. No aplica a Filter rows ni Switch/case, que mandan a destinos concretos.",
      Tools.schema().documento()
        .str( "step", "Nombre del step", true )
        .strEnum( "modo", "copiar o repartir", true, "copiar", "repartir" ).build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        TransMeta tm = Docs.trans( a );
        StepMeta sm = TransOps.paso( tm, Tools.str( a, "step" ) );
        StepMeta antes = (StepMeta) sm.clone();
        TransOps.aplicarModoSalida( tm, sm, Tools.str( a, "modo" ) );
        if ( sm.isDistributes() != antes.isDistributes() ) {
          new UndoGrupo( sp, tm ).cambio( new Object[] { antes }, new Object[] { sm.clone() },
            new int[] { tm.indexOfStep( sm ) } );
          Docs.touched( tm );
        }
        ObjectNode o = Tools.obj();
        o.put( "step", sm.getName() );
        o.put( "modo_salida", TransOps.modoSalida( sm ) );
        o.put( "salidas", TransOps.salidasNormales( tm, sm ) );
        return o;
      } ) ) );

    s.add( Tools.tool( "desconectar",
      "Borra el hop entre dos steps (los steps quedan). Si era el hop de errores, apaga el manejo de errores "
        + "del origen.",
      Tools.schema().documento()
        .str( "desde", "Step origen", true )
        .str( "hacia", "Step destino", true ).build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        TransMeta tm = Docs.trans( a );
        TransHopMeta h = TransOps.hop( tm, Tools.str( a, "desde" ), Tools.str( a, "hacia" ) );
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
        return TransOps.fichaHop( h ).put( "borrado", true );
      } ) ) );

    s.add( Tools.tool( "habilitar_hop",
      "Habilita o deshabilita un hop sin borrarlo (un hop deshabilitado no pasa filas; se dibuja gris).",
      Tools.schema().documento()
        .str( "desde", "Step origen", true )
        .str( "hacia", "Step destino", true )
        .bool( "habilitado", "true para habilitar, false para deshabilitar", true ).build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        TransMeta tm = Docs.trans( a );
        if ( a.get( "habilitado" ) == null || a.get( "habilitado" ).isNull() ) {
          throw new ToolError( "Falta el argumento 'habilitado'" );
        }
        TransHopMeta h = TransOps.hop( tm, Tools.str( a, "desde" ), Tools.str( a, "hacia" ) );
        boolean hab = Tools.optBool( a, "habilitado", true );
        if ( h.isEnabled() != hab ) {
          Object antes = h.clone();
          h.setEnabled( hab );
          new UndoGrupo( sp, tm ).cambio( new Object[] { antes }, new Object[] { h.clone() },
            new int[] { tm.indexOfTransHop( h ) } );
          tm.clearCaches();
          Docs.touched( tm );
        }
        return TransOps.fichaHop( h ).put( "habilitado", h.isEnabled() );
      } ) ) );

    // ------------------------------------------------------------ lienzo (transformaciones y jobs)

    s.add( Tools.tool( "agregar_nota",
      "Agrega una nota (post-it) al lienzo de una transformacion o un job, para documentar que hace una parte. "
        + "Sin x/y va debajo de todo lo que hay.",
      Tools.schema().documento()
        .str( "texto", "Texto de la nota (puede tener saltos de linea)", true )
        .integer( "x", "Posicion x (opcional)", false )
        .integer( "y", "Posicion y (opcional)", false )
        .integer( "ancho", "Ancho minimo en pixeles (opcional; se agranda solo segun el texto)", false )
        .integer( "alto", "Alto minimo en pixeles (opcional)", false ).build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        AbstractMeta m = Docs.resolver( a );
        String texto = Tools.str( a, "texto" );
        Integer x = Tools.optInteger( a, "x" );
        Integer y = Tools.optInteger( a, "y" );
        if ( y == null ) {
          y = debajoDeTodo( m );
        }
        NotePadMeta n = new NotePadMeta( texto, x == null ? 100 : x, y, Tools.optInt( a, "ancho", 20 ),
          Tools.optInt( a, "alto", 20 ) );
        m.addNote( n );
        new UndoGrupo( sp, m ).nuevo( new Object[] { n }, new int[] { m.indexOfNote( n ) } );
        Docs.touched( m );
        ObjectNode o = Tools.obj();
        o.put( "nota", m.indexOfNote( n ) );
        o.put( "x", n.getLocation().x );
        o.put( "y", n.getLocation().y );
        return o;
      } ) ) );

    s.add( Tools.tool( "seleccionar",
      "Resalta steps (o entradas de un job) en el lienzo para que el usuario vea de cuales se esta hablando. "
        + "No cambia nada del documento. Lista vacia = quitar la seleccion.",
      Tools.schema().documento()
        .strList( "steps", "Nombres de los steps o entradas a resaltar", true ).build(),
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

    s.add( Tools.tool( "ordenar_lienzo",
      "Acomoda automaticamente el lienzo de una transformacion o un job de izquierda a derecha siguiendo los "
        + "hops (cada nivel en una columna, las ramas una debajo de otra; en jobs START queda a la izquierda). "
        + "Usala al terminar de armar o cuando el dibujo quedo desordenado. Las notas no se mueven. Se deshace "
        + "con Ctrl+Z.",
      Tools.schema().documento().build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        AbstractMeta m = Docs.resolver( a );
        TransLayout.Movimientos mov = TransLayout.ordenar( m );
        if ( !mov.objetos.isEmpty() ) {
          new UndoGrupo( sp, m ).posicion( mov.objetos(), mov.indices(), mov.antes(), mov.despues() );
          Docs.touched( m );
        }
        ObjectNode o = Tools.obj();
        o.put( "movidos", mov.objetos.size() );
        o.put( "columnas", mov.columnas );
        return o;
      } ) ) );
  }

  // ---------------------------------------------------------------- ayudas

  /** JSON Schema del argumento 'steps' de build_steps. */
  private static ObjectNode esquemaSteps() {
    ObjectNode item = Tools.obj().put( "type", "object" );
    ObjectNode p = item.putObject( "properties" );
    p.putObject( "type" ).put( "type", "string" ).put( "description",
      "Step type id (from tipos_de_step), e.g. 'CsvInput', 'FilterRows', 'TableOutput'" );
    p.putObject( "name" ).put( "type", "string" ).put( "description", "Unique step name" );
    p.putObject( "config_xml" ).put( "type", "string" ).put( "description",
      "Only the configuration tags that differ from plantilla_step" );
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
        throw new ToolError( "No existen: " + faltan + ". " + TransOps.nombres( tm ) );
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
        throw new ToolError( "No existen: " + faltan + ". Entradas: " + hay );
      }
      jm.unselectAll();
      for ( JobEntryCopy c : sel ) {
        c.setSelected( true );
        marcados.add( c.getName() );
      }
    }
    Docs.redibujar( m );
    ObjectNode o = Tools.obj();
    ArrayNode arr = o.putArray( "seleccionados" );
    for ( String n : marcados ) {
      arr.add( n );
    }
    return o;
  }

  /** tipos_de_step: categorias, o los que coinciden con buscar/categoria. */
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
      ArrayNode arr = o.putArray( "categorias" );
      for ( Map.Entry<String, Integer> e : cats.entrySet() ) {
        arr.addObject().put( "categoria", e.getKey() ).put( "steps", e.getValue() );
      }
      o.put( "total", todos.size() );
      o.put( "ayuda", "Pasa 'buscar' (texto) o 'categoria' para ver los steps." );
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
    ArrayNode arr = o.putArray( "steps" );
    for ( int i = 0; i < hits.size() && i < max; i++ ) {
      PluginInterface p = hits.get( i );
      arr.addObject().put( "id", TransOps.id( p ) ).put( "nombre", p.getName() ).put( "categoria", p.getCategory() )
        .put( "descripcion", p.getDescription() );
    }
    o.put( "total", hits.size() );
    if ( hits.size() > max ) {
      o.put( "nota", "Hay " + hits.size() + " coincidencias; se muestran " + max + ". Afina 'buscar' o 'categoria'." );
    }
    if ( hits.isEmpty() ) {
      o.put( "nota", "Nada coincide. " + TransOps.sugerencias( buscar == null ? categoria : buscar ) );
    }
    return o;
  }
}
