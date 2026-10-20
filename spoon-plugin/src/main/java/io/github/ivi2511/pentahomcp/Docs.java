package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.ui.spoon.MainSpoonPerspective;
import org.pentaho.di.ui.spoon.Spoon;
import org.pentaho.di.ui.spoon.SpoonPerspective;
import org.pentaho.di.ui.spoon.SpoonPerspectiveManager;
import org.pentaho.di.ui.spoon.TabMapEntry;
import org.pentaho.di.ui.spoon.job.JobGraph;
import org.pentaho.di.ui.spoon.trans.TransGraph;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;

/**
 * Los documentos (transformaciones y jobs) abiertos en Spoon, vistos desde las herramientas.
 *
 * Reglas:
 * - Todo lo que toque Spoon o un TransMeta/JobMeta abierto corre en el hilo de UI: envolverlo
 *   en {@link #ui(Callable)}. Los metodos de esta clase que no son ui() ASUMEN que ya estan ahi.
 * - Cada documento tiene un id corto y estable mientras siga abierto (T1, T2, J1...), que es lo
 *   que la IA usa en el argumento "document".
 * - Al editar un documento se activa su pestania, asi el usuario ve el cambio en vivo.
 * - Despues de mutar: {@link #touched(AbstractMeta)} (marca cambiado, refresca arbol y lienzo).
 */
final class Docs {

  static final long UI_TIMEOUT_MS = 20000;

  private static final List<IdRef> IDS = new ArrayList<IdRef>();
  private static int nextTrans = 1;
  private static int nextJob = 1;

  private Docs() {
  }

  static Spoon spoon() throws ToolError {
    Spoon s = UiThread.spoon();
    if ( s == null || s.getDisplay() == null || s.getDisplay().isDisposed() ) {
      throw new ToolError( "Spoon is not available (closed or still starting)" );
    }
    return s;
  }

  /** Corre en el hilo de UI de Spoon y devuelve el resultado (o propaga la excepcion). */
  static <T> T ui( Callable<T> task ) throws Exception {
    spoon();
    return UiThread.call( task, UI_TIMEOUT_MS );
  }

  /**
   * Por identidad del objeto y no por equals(): TransMeta/JobMeta comparan por nombre y archivo,
   * asi que con un mapa comun el id cambiaba al guardar con otra ruta.
   */
  static synchronized String id( AbstractMeta meta ) {
    for ( Iterator<IdRef> it = IDS.iterator(); it.hasNext(); ) {
      IdRef r = it.next();
      AbstractMeta m = r.get();
      if ( m == null ) {
        it.remove();
      } else if ( m == meta ) {
        return r.id;
      }
    }
    String id = meta instanceof JobMeta ? "J" + nextJob++ : "T" + nextTrans++;
    IDS.add( new IdRef( meta, id ) );
    return id;
  }

  private static final class IdRef extends WeakReference<AbstractMeta> {
    final String id;

    IdRef( AbstractMeta meta, String id ) {
      super( meta );
      this.id = id;
    }
  }

  static String tipo( AbstractMeta meta ) {
    return meta instanceof JobMeta ? "job" : "transformation";
  }

  /** Documentos abiertos, en el orden de las pestanias. (UI) */
  static List<AbstractMeta> abiertos() throws ToolError {
    List<AbstractMeta> out = new ArrayList<AbstractMeta>();
    for ( TabMapEntry e : spoon().delegates.tabs.getTabs() ) {
      AbstractMeta m = metaDe( e );
      if ( m != null ) {
        out.add( m );
      }
    }
    return out;
  }

  /** El documento de la pestania activa, o null. (UI) */
  static AbstractMeta activo() throws ToolError {
    Object m = spoon().getActiveMeta();
    return m instanceof AbstractMeta ? (AbstractMeta) m : null;
  }

  /**
   * Resuelve el argumento "document" (o la pestania activa si falta) y activa su pestania. (UI)
   */
  static AbstractMeta resolver( JsonNode args ) throws ToolError {
    String ref = Tools.optStr( args, "document", null );
    AbstractMeta meta;
    if ( ref == null ) {
      meta = activo();
      if ( meta == null ) {
        throw new ToolError( "There is no active transformation or job in Spoon. Open or create one "
          + "(new_transformation, new_job, open_file) or pass 'document'. " + listado() );
      }
    } else {
      meta = buscar( ref.trim() );
    }
    activar( meta );
    return meta;
  }

  static TransMeta trans( JsonNode args ) throws ToolError {
    AbstractMeta m = resolver( args );
    if ( !( m instanceof TransMeta ) ) {
      throw new ToolError( "'" + nombre( m ) + "' is a job, and this tool is for transformations" );
    }
    return (TransMeta) m;
  }

  static JobMeta job( JsonNode args ) throws ToolError {
    AbstractMeta m = resolver( args );
    if ( !( m instanceof JobMeta ) ) {
      throw new ToolError( "'" + nombre( m ) + "' is a transformation, and this tool is for jobs" );
    }
    return (JobMeta) m;
  }

  private static AbstractMeta buscar( String ref ) throws ToolError {
    List<AbstractMeta> docs = abiertos();
    for ( AbstractMeta m : docs ) { // 1) id exacto
      if ( id( m ).equalsIgnoreCase( ref ) ) {
        return m;
      }
    }
    String refNorm = normalizarRuta( ref );
    List<AbstractMeta> hits = new ArrayList<AbstractMeta>();
    for ( AbstractMeta m : docs ) { // 2) ruta, nombre de archivo o nombre del documento
      String f = m.getFilename();
      if ( f != null ) {
        String fNorm = normalizarRuta( f );
        String base = new File( f ).getName();
        String sinExt = base.replaceFirst( "\\.(ktr|kjb)$", "" );
        if ( fNorm.equals( refNorm ) || base.equalsIgnoreCase( ref ) || sinExt.equalsIgnoreCase( ref ) ) {
          hits.add( m );
          continue;
        }
      }
      if ( m.getName() != null && m.getName().equalsIgnoreCase( ref ) ) {
        hits.add( m );
      }
    }
    if ( hits.size() == 1 ) {
      return hits.get( 0 );
    }
    if ( hits.size() > 1 ) {
      throw new ToolError( "'" + ref + "' matches several documents; use the id. " + listado() );
    }
    throw new ToolError( "No open document matches '" + ref + "'. " + listado() );
  }

  private static String normalizarRuta( String p ) {
    String s = p.replace( "file:///", "" ).replace( "file://", "" ).replace( '/', '\\' );
    try {
      s = new File( s ).getAbsolutePath();
    } catch ( Exception ignored ) {
      // queda como vino
    }
    return s.toLowerCase();
  }

  /** "Open: T1 'ventas' (C:\...\ventas.ktr); J1 ..." para los mensajes de error. (UI) */
  static String listado() throws ToolError {
    StringBuilder sb = new StringBuilder( "Open:" );
    List<AbstractMeta> docs = abiertos();
    if ( docs.isEmpty() ) {
      return "No documents are open.";
    }
    for ( AbstractMeta m : docs ) {
      sb.append( ' ' ).append( id( m ) ).append( " '" ).append( nombre( m ) ).append( '\'' );
      if ( m.getFilename() != null ) {
        sb.append( " (" ).append( m.getFilename() ).append( ')' );
      }
      sb.append( ';' );
    }
    return sb.toString();
  }

  static String nombre( AbstractMeta m ) {
    return m.getName() != null ? m.getName() : ( m.getFilename() != null ? m.getFilename() : "(unnamed)" );
  }

  /** Ficha corta de un documento. (UI) */
  static ObjectNode describir( AbstractMeta m ) throws ToolError {
    ObjectNode o = Tools.obj();
    o.put( "id", id( m ) );
    o.put( "type", tipo( m ) );
    o.put( "name", nombre( m ) );
    o.put( "file", m.getFilename() );
    o.put( "unsaved_changes", m.hasChanged() );
    o.put( "active", m == activo() );
    if ( m instanceof TransMeta ) {
      o.put( "steps", ( (TransMeta) m ).nrSteps() );
      o.put( "hops", ( (TransMeta) m ).nrTransHops() );
    } else if ( m instanceof JobMeta ) {
      o.put( "entries", ( (JobMeta) m ).nrJobEntries() );
      o.put( "hops", ( (JobMeta) m ).nrJobHops() );
    }
    return o;
  }

  /** Trae la perspectiva principal (Data Integration) y la pestania del documento al frente. (UI) */
  static void activar( AbstractMeta meta ) throws ToolError {
    Spoon s = spoon();
    try {
      SpoonPerspective p = SpoonPerspectiveManager.getInstance().getActivePerspective();
      if ( p != null && !( p instanceof MainSpoonPerspective ) ) {
        SpoonPerspectiveManager.getInstance().activatePerspective( MainSpoonPerspective.class );
      }
    } catch ( Exception e ) {
      McpLifecycleListener.LOG.logDebug( "No se pudo activar la perspectiva principal: " + e );
    }
    TabMapEntry e = s.delegates.tabs.findTabMapEntry( meta );
    if ( e != null && s.tabfolder.getSelected() != e.getTabItem() ) {
      s.tabfolder.setSelected( e.getTabItem() );
    }
  }

  /** Despues de mutar un documento: marcarlo cambiado y redibujar todo lo que lo muestra. (UI) */
  static void touched( AbstractMeta meta ) throws ToolError {
    Spoon s = spoon();
    meta.setChanged();
    if ( meta instanceof TransMeta ) {
      ( (TransMeta) meta ).clearCaches();
    }
    s.refreshTree();
    redibujar( meta );
    s.setShellText();
  }

  /** Solo redibuja el lienzo del documento (para seleccion/resaltado, sin marcar cambios). (UI) */
  static void redibujar( AbstractMeta meta ) throws ToolError {
    Spoon s = spoon();
    if ( meta instanceof TransMeta ) {
      TransGraph g = s.delegates.trans.findTransGraphOfTransformation( (TransMeta) meta );
      if ( g != null ) {
        g.redraw();
      }
    } else if ( meta instanceof JobMeta ) {
      JobGraph g = s.delegates.jobs.findJobGraphOfJob( (JobMeta) meta );
      if ( g != null ) {
        g.redraw();
      }
    }
  }

  private static AbstractMeta metaDe( TabMapEntry e ) {
    Object o = e.getObject();
    if ( o instanceof TransGraph ) {
      return ( (TransGraph) o ).getTransMeta();
    }
    if ( o instanceof JobGraph ) {
      return ( (JobGraph) o ).getJobMeta();
    }
    return null;
  }
}
