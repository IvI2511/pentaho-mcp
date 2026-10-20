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
import org.pentaho.di.core.NotePadMeta;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.xml.XMLHandler;
import org.pentaho.di.job.JobHopMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.ui.spoon.Spoon;
import org.pentaho.di.ui.spoon.TabMapEntry;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;

/** Estado de Spoon y ciclo de vida de los documentos: crear, abrir, guardar, cerrar, leer. */
final class DocumentTools {

  private DocumentTools() {
  }

  static void register( McpHttpServer s, final String version, final int port ) {

    s.add( Tools.tool( "status",
      "Spoon status: which transformations and jobs are open (with their id T1, J1...), which one is "
        + "active and whether they have unsaved changes. Call it first in every conversation.",
      Tools.schema().build(),
      a -> Docs.ui( () -> {
        ObjectNode o = Tools.obj();
        o.put( "plugin", "pentaho-mcp " + version );
        o.put( "port", port );
        AbstractMeta activo = Docs.activo();
        o.put( "active", activo == null ? null : Docs.id( activo ) );
        ArrayNode docs = o.putArray( "documents" );
        for ( AbstractMeta m : Docs.abiertos() ) {
          docs.add( Docs.describir( m ) );
        }
        return o;
      } ) ) );

    s.add( Tools.tool( "new_transformation",
      "Creates an empty transformation in a new tab and makes it active. It has no file yet: "
        + "use 'save' with a .ktr path.",
      Tools.schema().str( "name", "Transformation name (optional)", false ).build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        // Primero Data Integration: con otra perspectiva al frente (Informes) getActiveTransformation()
        // le pregunta a esa perspectiva y daba null. La pestania nueva se lee del tab folder.
        Docs.perspectivaPrincipal();
        sp.newTransFile();
        AbstractMeta nuevo = Docs.activo();
        TransMeta tm = nuevo instanceof TransMeta ? (TransMeta) nuevo : null;
        if ( tm == null ) {
          throw new ToolError( "Spoon did not create the transformation" );
        }
        renombrar( sp, tm, Tools.optStr( a, "name", null ) );
        return Docs.describir( tm );
      } ) ) );

    s.add( Tools.tool( "new_job",
      "Creates an empty job in a new tab and makes it active. Spoon does not add the START entry: "
        + "add it first with add_entry type=START. Use 'save' with a .kjb path.",
      Tools.schema().str( "name", "Job name (optional)", false ).build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        Docs.perspectivaPrincipal(); // como en new_transformation
        sp.newJobFile();
        AbstractMeta nuevo = Docs.activo();
        JobMeta jm = nuevo instanceof JobMeta ? (JobMeta) nuevo : null;
        if ( jm == null ) {
          throw new ToolError( "Spoon did not create the job" );
        }
        renombrar( sp, jm, Tools.optStr( a, "name", null ) );
        return Docs.describir( jm );
      } ) ) );

    s.add( Tools.tool( "open_file",
      "Opens a .ktr or .kjb file from disk in Spoon (if it is already open, it just activates its tab).",
      Tools.schema().str( "path", "Absolute path of the .ktr or .kjb file", true ).build(),
      a -> {
        final File f = new File( Tools.str( a, "path" ) ).getAbsoluteFile();
        if ( !f.isFile() ) {
          throw new ToolError( "File not found: " + f );
        }
        String n = f.getName().toLowerCase();
        if ( !n.endsWith( ".ktr" ) && !n.endsWith( ".kjb" ) ) {
          throw new ToolError( "Only .ktr (transformations) and .kjb (jobs) files can be opened: "
            + f.getName() );
        }
        return Docs.ui( () -> {
          Spoon sp = Docs.spoon();
          for ( AbstractMeta m : Docs.abiertos() ) {
            if ( m.getFilename() != null && new File( m.getFilename() ).getAbsoluteFile().equals( f ) ) {
              Docs.activar( m );
              return Docs.describir( m );
            }
          }
          Docs.perspectivaPrincipal(); // el usuario ve la pestania nueva, aunque estuviera en Informes
          sp.openFile( f.getPath(), false );
          AbstractMeta m = Docs.activo();
          if ( m == null ) {
            throw new ToolError( "Spoon could not open " + f + " (check Spoon's log)" );
          }
          return Docs.describir( m );
        } );
      } ) );

    s.add( Tools.tool( "save",
      "Saves the transformation or job to disk. Without 'path' it saves to its current file; with 'path' "
        + "it saves there (\"save as\"), creating any missing folders. It never opens dialogs.",
      Tools.schema().document()
        .str( "path", "Absolute destination path (.ktr for transformations, .kjb for jobs). Optional if "
          + "the document already has a file.", false ).build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        AbstractMeta m = Docs.resolver( a );
        String ruta = Tools.optStr( a, "path", m.getFilename() );
        if ( ruta == null ) {
          throw new ToolError( "'" + Docs.nombre( m ) + "' has no file yet: pass 'path'" );
        }
        File f = new File( ruta ).getAbsoluteFile();
        String ext = m instanceof JobMeta ? ".kjb" : ".ktr";
        if ( !f.getName().toLowerCase().endsWith( ext ) ) {
          throw new ToolError( "A " + ( m instanceof JobMeta ? "job" : "transformation" ) + " is saved as "
            + ext + ": " + f.getName() );
        }
        File dir = f.getParentFile();
        if ( dir != null && !dir.isDirectory() && !dir.mkdirs() ) {
          throw new ToolError( "Could not create the folder " + dir );
        }
        // Escribimos nosotros (lo mismo que Spoon.saveMeta) para no caer en los dialogos de Spoon,
        // por ejemplo el que rechaza rutas con acentos o enies.
        String nombreAntes = m.getName();
        m.setFilename( f.getPath() );
        if ( nombreAntes == null || nombreAntes.trim().isEmpty() ) {
          m.nameFromFilename();
        }
        try ( Writer w = new OutputStreamWriter( new FileOutputStream( f ), StandardCharsets.UTF_8 ) ) {
          w.write( XMLHandler.getXMLHeader() );
          w.write( m.getXML() );
        }
        m.clearChanged();
        sp.delegates.tabs.renameTabs();
        sp.refreshTree();
        sp.setShellText();
        ObjectNode o = Docs.describir( m );
        o.put( "saved_to", f.getPath() );
        return o;
      } ) ) );

    s.add( Tools.tool( "activate_document",
      "Brings the tab of an open transformation or job to the front.",
      Tools.schema().document().build(),
      a -> Docs.ui( () -> Docs.describir( Docs.resolver( a ) ) ) ) );

    s.add( Tools.tool( "close_document",
      "Closes the tab of a transformation or job. If it has unsaved changes it fails, unless you "
        + "pass discard_changes=true.",
      Tools.schema().document()
        .bool( "discard_changes", "Close even if it has unsaved changes (they are lost)", false ).build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        AbstractMeta m = Docs.resolver( a );
        if ( m.hasChanged() && !Tools.optBool( a, "discard_changes", false ) ) {
          throw new ToolError( "'" + Docs.nombre( m ) + "' has unsaved changes. Save it or pass "
            + "discard_changes=true." );
        }
        TabMapEntry e = sp.delegates.tabs.findTabMapEntry( m );
        if ( e == null ) {
          throw new ToolError( "Could not find the tab of '" + Docs.nombre( m ) + "'" );
        }
        String id = Docs.id( m );
        sp.tabClose( e.getTabItem(), true );
        return "Closed " + id + " '" + Docs.nombre( m ) + "'";
      } ) ) );

    s.add( Tools.tool( "get_document",
      "Reads an open transformation or job. format='summary' (default): steps/entries with type and "
        + "position, hops, notes, connections and parameters. format='xml': the full XML, exactly as in the "
        + "file (passwords masked). For the configuration of a single step use get_step.",
      Tools.schema().document().strEnum( "format", "summary or xml", false, "summary", "xml" ).build(),
      a -> Docs.ui( () -> {
        AbstractMeta m = Docs.resolver( a );
        if ( "xml".equals( Tools.optStr( a, "format", "summary" ) ) ) {
          return Xml.sinPasswords( m.getXML() );
        }
        return resumen( m );
      } ) ) );
  }

  private static void renombrar( Spoon sp, AbstractMeta m, String nombre ) {
    if ( nombre != null ) {
      m.setName( nombre );
      sp.delegates.tabs.renameTabs();
      sp.refreshTree();
      sp.setShellText();
    }
  }

  /** Vista compacta de un documento. (UI) */
  static ObjectNode resumen( AbstractMeta m ) throws Exception {
    ObjectNode o = Docs.describir( m );
    if ( m instanceof TransMeta ) {
      TransMeta tm = (TransMeta) m;
      ArrayNode steps = o.putArray( "steps" );
      for ( StepMeta st : tm.getSteps() ) {
        ObjectNode x = steps.addObject();
        x.put( "name", st.getName() );
        x.put( "type", st.getStepID() );
        x.put( "x", st.getLocation().x );
        x.put( "y", st.getLocation().y );
        if ( st.getCopies() != 1 ) {
          x.put( "copies", st.getCopies() );
        }
        if ( st.isMissing() ) {
          x.put( "missing_plugin", true );
        }
        if ( TransOps.salidasNormales( tm, st ) >= 2 ) {
          x.put( "output_mode", TransOps.modoSalida( st ) );
        }
      }
      ArrayNode hops = o.putArray( "hops" );
      for ( int i = 0; i < tm.nrTransHops(); i++ ) {
        TransHopMeta h = tm.getTransHop( i );
        ObjectNode x = hops.addObject();
        x.put( "from", h.getFromStep().getName() );
        x.put( "to", h.getToStep().getName() );
        if ( !h.isEnabled() ) {
          x.put( "enabled", false );
        }
        if ( h.isErrorHop() ) {
          x.put( "error", true );
        }
      }
    } else if ( m instanceof JobMeta ) {
      JobMeta jm = (JobMeta) m;
      ArrayNode entradas = o.putArray( "entries" );
      for ( JobEntryCopy c : jm.getJobCopies() ) {
        ObjectNode x = entradas.addObject();
        x.put( "name", c.getName() );
        x.put( "type", c.getEntry() == null ? null : c.getEntry().getPluginId() );
        if ( c.isStart() ) {
          x.put( "start", true );
        }
        x.put( "x", c.getLocation().x );
        x.put( "y", c.getLocation().y );
      }
      ArrayNode hops = o.putArray( "hops" );
      for ( int i = 0; i < jm.nrJobHops(); i++ ) {
        JobHopMeta h = jm.getJobHop( i );
        ObjectNode x = hops.addObject();
        x.put( "from", h.getFromEntry().getName() );
        x.put( "to", h.getToEntry().getName() );
        x.put( "condition", h.isUnconditional() ? "always" : ( h.getEvaluation() ? "on_success" : "on_failure" ) );
        if ( !h.isEnabled() ) {
          x.put( "enabled", false );
        }
      }
    }
    if ( m.nrNotes() > 0 ) {
      ArrayNode notas = o.putArray( "notes" );
      for ( int i = 0; i < m.nrNotes(); i++ ) {
        NotePadMeta n = m.getNote( i );
        notas.addObject().put( "text", n.getNote() ).put( "x", n.getLocation().x ).put( "y", n.getLocation().y );
      }
    }
    if ( !m.getDatabases().isEmpty() ) {
      ArrayNode cons = o.putArray( "connections" );
      for ( DatabaseMeta d : m.getDatabases() ) {
        cons.addObject().put( "name", d.getName() ).put( "type", d.getPluginId() )
          .put( "host", d.getHostname() ).put( "database", d.getDatabaseName() );
      }
    }
    String[] params = m.listParameters();
    if ( params.length > 0 ) {
      ArrayNode ps = o.putArray( "parameters" );
      for ( String p : params ) {
        ps.addObject().put( "name", p ).put( "default", m.getParameterDefault( p ) )
          .put( "description", m.getParameterDescription( p ) );
      }
    }
    return o;
  }
}
