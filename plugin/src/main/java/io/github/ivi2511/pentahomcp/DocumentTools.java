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

    s.add( Tools.tool( "estado",
      "Estado de Spoon: que transformaciones y jobs estan abiertos (con su id T1, J1...), cual es el "
        + "activo y si tienen cambios sin guardar. Llamala primero en cada conversacion.",
      Tools.schema().build(),
      a -> Docs.ui( () -> {
        ObjectNode o = Tools.obj();
        o.put( "plugin", "pentaho-mcp " + version );
        o.put( "puerto", port );
        AbstractMeta activo = Docs.activo();
        o.put( "activo", activo == null ? null : Docs.id( activo ) );
        ArrayNode docs = o.putArray( "documentos" );
        for ( AbstractMeta m : Docs.abiertos() ) {
          docs.add( Docs.describir( m ) );
        }
        return o;
      } ) ) );

    s.add( Tools.tool( "nueva_transformacion",
      "Crea una transformacion vacia en una pestania nueva y la deja activa. Todavia no tiene archivo: "
        + "usa 'guardar' con una ruta .ktr.",
      Tools.schema().str( "nombre", "Nombre de la transformacion (opcional)", false ).build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        sp.newTransFile();
        TransMeta tm = sp.getActiveTransformation();
        if ( tm == null ) {
          throw new ToolError( "Spoon no creo la transformacion" );
        }
        renombrar( sp, tm, Tools.optStr( a, "nombre", null ) );
        return Docs.describir( tm );
      } ) ) );

    s.add( Tools.tool( "nuevo_job",
      "Crea un job vacio en una pestania nueva y lo deja activo. Spoon no agrega la entrada START: "
        + "agregala primero con agregar_entrada tipo=START. Usa 'guardar' con una ruta .kjb.",
      Tools.schema().str( "nombre", "Nombre del job (opcional)", false ).build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        sp.newJobFile();
        JobMeta jm = sp.getActiveJob();
        if ( jm == null ) {
          throw new ToolError( "Spoon no creo el job" );
        }
        renombrar( sp, jm, Tools.optStr( a, "nombre", null ) );
        return Docs.describir( jm );
      } ) ) );

    s.add( Tools.tool( "abrir_archivo",
      "Abre un .ktr o .kjb del disco en Spoon (si ya estaba abierto, solo activa su pestania).",
      Tools.schema().str( "ruta", "Ruta absoluta del archivo .ktr o .kjb", true ).build(),
      a -> {
        final File f = new File( Tools.str( a, "ruta" ) ).getAbsoluteFile();
        if ( !f.isFile() ) {
          throw new ToolError( "No existe el archivo " + f );
        }
        String n = f.getName().toLowerCase();
        if ( !n.endsWith( ".ktr" ) && !n.endsWith( ".kjb" ) ) {
          throw new ToolError( "Solo se abren .ktr (transformaciones) y .kjb (jobs): " + f.getName() );
        }
        return Docs.ui( () -> {
          Spoon sp = Docs.spoon();
          for ( AbstractMeta m : Docs.abiertos() ) {
            if ( m.getFilename() != null && new File( m.getFilename() ).getAbsoluteFile().equals( f ) ) {
              Docs.activar( m );
              return Docs.describir( m );
            }
          }
          sp.openFile( f.getPath(), false );
          AbstractMeta m = Docs.activo();
          if ( m == null ) {
            throw new ToolError( "Spoon no pudo abrir " + f + " (mira el log de Spoon)" );
          }
          return Docs.describir( m );
        } );
      } ) );

    s.add( Tools.tool( "guardar",
      "Guarda la transformacion o el job en disco. Sin 'ruta' guarda en su archivo actual; con 'ruta' "
        + "guarda ahi (\"guardar como\"), creando las carpetas que falten. Nunca abre dialogos.",
      Tools.schema().documento()
        .str( "ruta", "Ruta absoluta destino (.ktr para transformaciones, .kjb para jobs). Opcional si "
          + "el documento ya tiene archivo.", false ).build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        AbstractMeta m = Docs.resolver( a );
        String ruta = Tools.optStr( a, "ruta", m.getFilename() );
        if ( ruta == null ) {
          throw new ToolError( "'" + Docs.nombre( m ) + "' todavia no tiene archivo: pasa 'ruta'" );
        }
        File f = new File( ruta ).getAbsoluteFile();
        String ext = m instanceof JobMeta ? ".kjb" : ".ktr";
        if ( !f.getName().toLowerCase().endsWith( ext ) ) {
          throw new ToolError( "Un" + ( m instanceof JobMeta ? " job" : "a transformacion" ) + " se guarda como "
            + ext + ": " + f.getName() );
        }
        File dir = f.getParentFile();
        if ( dir != null && !dir.isDirectory() && !dir.mkdirs() ) {
          throw new ToolError( "No se pudo crear la carpeta " + dir );
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
        o.put( "guardado_en", f.getPath() );
        return o;
      } ) ) );

    s.add( Tools.tool( "activar_documento",
      "Trae al frente la pestania de una transformacion o job abierto.",
      Tools.schema().documento().build(),
      a -> Docs.ui( () -> Docs.describir( Docs.resolver( a ) ) ) ) );

    s.add( Tools.tool( "cerrar_documento",
      "Cierra la pestania de una transformacion o job. Si tiene cambios sin guardar falla, salvo que "
        + "pases descartar_cambios=true.",
      Tools.schema().documento()
        .bool( "descartar_cambios", "Cerrar aunque tenga cambios sin guardar (se pierden)", false ).build(),
      a -> Docs.ui( () -> {
        Spoon sp = Docs.spoon();
        AbstractMeta m = Docs.resolver( a );
        if ( m.hasChanged() && !Tools.optBool( a, "descartar_cambios", false ) ) {
          throw new ToolError( "'" + Docs.nombre( m ) + "' tiene cambios sin guardar. Guardalo o pasa "
            + "descartar_cambios=true." );
        }
        TabMapEntry e = sp.delegates.tabs.findTabMapEntry( m );
        if ( e == null ) {
          throw new ToolError( "No encuentro la pestania de '" + Docs.nombre( m ) + "'" );
        }
        String id = Docs.id( m );
        sp.tabClose( e.getTabItem(), true );
        return "Cerrado " + id + " '" + Docs.nombre( m ) + "'";
      } ) ) );

    s.add( Tools.tool( "ver_documento",
      "Lee una transformacion o un job abierto. formato='resumen' (por defecto): steps/entradas con tipo y "
        + "posicion, hops, notas, conexiones y parametros. formato='xml': el XML completo, igual que en el "
        + "archivo (contrasenias enmascaradas). Para la configuracion de un solo step usa ver_step.",
      Tools.schema().documento().strEnum( "formato", "resumen o xml", false, "resumen", "xml" ).build(),
      a -> Docs.ui( () -> {
        AbstractMeta m = Docs.resolver( a );
        if ( "xml".equals( Tools.optStr( a, "formato", "resumen" ) ) ) {
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
        x.put( "nombre", st.getName() );
        x.put( "tipo", st.getStepID() );
        x.put( "x", st.getLocation().x );
        x.put( "y", st.getLocation().y );
        if ( st.getCopies() != 1 ) {
          x.put( "copias", st.getCopies() );
        }
        if ( st.isMissing() ) {
          x.put( "falta_plugin", true );
        }
      }
      ArrayNode hops = o.putArray( "hops" );
      for ( int i = 0; i < tm.nrTransHops(); i++ ) {
        TransHopMeta h = tm.getTransHop( i );
        ObjectNode x = hops.addObject();
        x.put( "desde", h.getFromStep().getName() );
        x.put( "hacia", h.getToStep().getName() );
        if ( !h.isEnabled() ) {
          x.put( "habilitado", false );
        }
        if ( h.isErrorHop() ) {
          x.put( "de_error", true );
        }
      }
    } else if ( m instanceof JobMeta ) {
      JobMeta jm = (JobMeta) m;
      ArrayNode entradas = o.putArray( "entradas" );
      for ( JobEntryCopy c : jm.getJobCopies() ) {
        ObjectNode x = entradas.addObject();
        x.put( "nombre", c.getName() );
        x.put( "tipo", c.getEntry() == null ? null : c.getEntry().getPluginId() );
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
        x.put( "desde", h.getFromEntry().getName() );
        x.put( "hacia", h.getToEntry().getName() );
        x.put( "condicion", h.isUnconditional() ? "siempre" : ( h.getEvaluation() ? "si_ok" : "si_falla" ) );
        if ( !h.isEnabled() ) {
          x.put( "habilitado", false );
        }
      }
    }
    if ( m.nrNotes() > 0 ) {
      ArrayNode notas = o.putArray( "notas" );
      for ( int i = 0; i < m.nrNotes(); i++ ) {
        NotePadMeta n = m.getNote( i );
        notas.addObject().put( "texto", n.getNote() ).put( "x", n.getLocation().x ).put( "y", n.getLocation().y );
      }
    }
    if ( !m.getDatabases().isEmpty() ) {
      ArrayNode cons = o.putArray( "conexiones" );
      for ( DatabaseMeta d : m.getDatabases() ) {
        cons.addObject().put( "nombre", d.getName() ).put( "tipo", d.getPluginId() )
          .put( "host", d.getHostname() ).put( "base", d.getDatabaseName() );
      }
    }
    String[] params = m.listParameters();
    if ( params.length > 0 ) {
      ArrayNode ps = o.putArray( "parametros" );
      for ( String p : params ) {
        ps.addObject().put( "nombre", p ).put( "por_defecto", m.getParameterDefault( p ) )
          .put( "descripcion", m.getParameterDescription( p ) );
      }
    }
    return o;
  }
}
