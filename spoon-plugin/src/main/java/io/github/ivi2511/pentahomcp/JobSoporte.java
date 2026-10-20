package io.github.ivi2511.pentahomcp;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.core.gui.Point;
import org.pentaho.di.core.plugins.JobEntryPluginType;
import org.pentaho.di.core.plugins.PluginInterface;
import org.pentaho.di.core.plugins.PluginRegistry;
import org.pentaho.di.core.xml.XMLHandler;
import org.pentaho.di.job.JobHopMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.job.entry.JobEntryInterface;
import org.pentaho.di.repository.Repository;
import org.pentaho.metastore.api.IMetaStore;
import org.w3c.dom.Node;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;

/**
 * Logica de edicion de jobs, sin Spoon: todo recibe el JobMeta y un {@link UndoGrupo} donde registrar
 * los cambios. JobTools la llama dentro de Docs.ui() con un grupo que va a Spoon; las pruebas sin
 * GUI la llaman directo.
 */
final class JobSoporte {

  /** Pseudo-tipos para las entradas especiales (en el XML son SPECIAL con start=Y / dummy=Y). */
  static final String START = "START";
  static final String DUMMY = "DUMMY";
  static final String SPECIAL = "SPECIAL";

  /** Valores de 'condition' que ve la IA. */
  static final String SIEMPRE = "always";
  static final String SI_OK = "on_success";
  static final String SI_FALLA = "on_failure";

  /** Envoltorio de &lt;entry&gt; que no se acepta en config_xml (lo arma el plugin). */
  static final Set<String> IGNORAR = new HashSet<String>( Xml.TAGS_ENVOLTORIO_ENTRY );

  static {
    IGNORAR.add( "attributes_kjc" );
  }

  /** Separacion horizontal entre una entrada y la siguiente. */
  static final int PASO_X = 150;
  static final int PASO_Y = 100;

  private JobSoporte() {
  }

  /*
   * Undo en jobs (PDI 9.4, verificado en el bytecode; detalle en UndoGrupo): SpoonJobDelegate no
   * mira TransAction.getNextAlso, asi que en un job cada accion registrada es un Ctrl+Z aparte. Lo
   * unico que Spoon deshace junto es DELETE_JOB_HOP seguido de DELETE_JOB_ENTRY (caso fijo de
   * Spoon.undoAction), que es justo lo que registra borrar(): delete_entry = un Ctrl+Z.
   *
   * add_entry con 'after' queda en DOS Ctrl+Z (el primero quita el hop, el segundo la
   * entrada; Ctrl+Y al reves). No hay alternativa limpia: un TransAction toma su tipo del primer
   * objeto, asi que no puede llevar entrada y hop juntos; registrar solo la entrada dejaria el hop
   * colgando al deshacer (JobMeta.removeJobEntry no quita hops); y el historial no admite acciones
   * propias, solo los tipos que Spoon sabe deshacer. Las demas herramientas de jobs registran una
   * sola accion. Igual se usa UndoGrupo, que marca nextAlso por si Spoon lo respeta algun dia.
   */

  /** Undo directo sobre el JobMeta (sin Spoon: no actualiza el menu Editar). Para pruebas. */
  static UndoGrupo undoMeta( JobMeta jm ) {
    return UndoGrupo.sinSpoon( jm );
  }

  // ---------------------------------------------------------------- tipos de entrada

  /** Sin filtros: categorias con su cantidad. Con filtros: los tipos que coinciden. */
  static ObjectNode tipos( String buscar, String categoria ) {
    List<String[]> todos = catalogo();
    ObjectNode o = Tools.obj();
    if ( buscar == null && categoria == null ) {
      Map<String, Integer> cats = new TreeMap<String, Integer>( String.CASE_INSENSITIVE_ORDER );
      for ( String[] t : todos ) {
        Integer n = cats.get( t[2] );
        cats.put( t[2], n == null ? 1 : n + 1 );
      }
      ArrayNode a = o.putArray( "categories" );
      for ( Map.Entry<String, Integer> e : cats.entrySet() ) {
        a.addObject().put( "category", e.getKey() ).put( "count", e.getValue() );
      }
      o.put( "total", todos.size() );
      o.put( "note", "Filter with 'search' or 'category'. START and DUMMY are special entries: create them with "
        + "add_entry type=START / type=DUMMY." );
      return o;
    }
    String[] palabras = buscar == null ? new String[0] : normalizar( buscar ).split( "\\s+" );
    String cat = categoria == null ? null : normalizar( categoria );
    ArrayNode a = o.putArray( "types" );
    int n = 0;
    for ( String[] t : todos ) {
      if ( cat != null && !normalizar( t[2] ).contains( cat ) ) {
        continue;
      }
      String heno = normalizar( t[0] + " " + t[1] + " " + t[2] + " " + t[3] );
      boolean ok = true;
      for ( String p : palabras ) {
        if ( !p.isEmpty() && !heno.contains( p ) ) {
          ok = false;
          break;
        }
      }
      if ( ok ) {
        if ( n++ < 80 ) {
          a.addObject().put( "id", t[0] ).put( "name", t[1] ).put( "category", t[2] ).put( "description", t[3] );
        }
      }
    }
    o.put( "total", n );
    if ( n > 80 ) {
      o.put( "note", "Showing 80 of " + n + "; narrow the search." );
    } else if ( n == 0 ) {
      o.put( "note", "Nothing matches. Call without arguments to see the categories." );
    }
    return o;
  }

  /** {id, nombre, categoria, descripcion} de cada tipo; SPECIAL se desdobla en START y DUMMY. */
  private static List<String[]> catalogo() {
    List<String[]> out = new ArrayList<String[]>();
    List<PluginInterface> ps = PluginRegistry.getInstance().getPlugins( JobEntryPluginType.class );
    for ( PluginInterface p : ps ) {
      String id = p.getIds()[0];
      String cat = nz( p.getCategory() );
      if ( SPECIAL.equals( id ) ) {
        out.add( new String[] { START, "Start", cat, "Start of the job (exactly one, required). The hop leaving "
          + "START is always unconditional." } );
        out.add( new String[] { DUMMY, "Dummy", cat, "Does nothing; used to join or split branches." } );
        continue;
      }
      out.add( new String[] { id, nz( p.getName() ), cat, nz( p.getDescription() ) } );
    }
    Collections.sort( out, ( x, y ) -> {
      int c = x[2].compareToIgnoreCase( y[2] );
      return c != 0 ? c : x[1].compareToIgnoreCase( y[1] );
    } );
    return out;
  }

  /**
   * Resuelve el tipo que pide la IA: id exacto (sin importar mayusculas), o nombre en pantalla.
   * Devuelve START, DUMMY o el id del plugin. Si no existe, ToolError con los parecidos.
   */
  static String resolverTipo( String tipo ) throws ToolError {
    String t = tipo.trim();
    if ( t.equalsIgnoreCase( START ) ) {
      return START;
    }
    if ( t.equalsIgnoreCase( DUMMY ) ) {
      return DUMMY;
    }
    PluginRegistry reg = PluginRegistry.getInstance();
    List<PluginInterface> ps = reg.getPlugins( JobEntryPluginType.class );
    for ( PluginInterface p : ps ) {
      for ( String id : p.getIds() ) {
        if ( id.equalsIgnoreCase( t ) ) {
          return p.getIds()[0];
        }
      }
    }
    for ( PluginInterface p : ps ) {
      if ( p.getName() != null && p.getName().equalsIgnoreCase( t ) ) {
        return p.getIds()[0];
      }
    }
    throw new ToolError( "Unknown job entry type '" + tipo + "'. " + parecidos( t )
      + "Use list_entry_types to search." );
  }

  private static String parecidos( String t ) {
    String q = normalizar( t );
    List<String> hits = new ArrayList<String>();
    for ( String[] c : catalogo() ) {
      String id = normalizar( c[0] );
      String nom = normalizar( c[1] );
      if ( id.contains( q ) || q.contains( id ) || nom.contains( q ) || distancia( q, id ) <= 2
        || distancia( q, nom ) <= 2 ) {
        hits.add( c[0] + " (" + c[1] + ")" );
      }
    }
    if ( hits.isEmpty() ) {
      return "";
    }
    if ( hits.size() > 10 ) {
      hits = hits.subList( 0, 10 );
    }
    return "Similar: " + String.join( ", ", hits ) + ". ";
  }

  /** XML de configuracion por defecto (sin envoltorio) de un tipo ya resuelto. */
  static String plantilla( String tipoResuelto ) throws Exception {
    if ( START.equals( tipoResuelto ) || DUMMY.equals( tipoResuelto ) ) {
      JobEntryCopy c = START.equals( tipoResuelto ) ? JobMeta.createStartEntry() : JobMeta.createDummyEntry();
      return Xml.sinEnvoltorio( c.getEntry().getXML(), IGNORAR );
    }
    return Xml.sinEnvoltorio( entradaPorDefecto( tipoResuelto ).getXML(), IGNORAR );
  }

  private static JobEntryInterface entradaPorDefecto( String id ) throws Exception {
    PluginRegistry reg = PluginRegistry.getInstance();
    PluginInterface p = reg.findPluginWithId( JobEntryPluginType.class, id );
    if ( p == null ) {
      throw new ToolError( "Unknown job entry type '" + id + "'" );
    }
    JobEntryInterface jei = reg.loadClass( p, JobEntryInterface.class );
    jei.setPluginId( id );
    jei.setName( p.getName() );
    jei.setParentJobMeta( new JobMeta() ); // algunos getXML() leen el job padre
    return jei;
  }

  // ---------------------------------------------------------------- entradas

  /** La entrada con ese nombre (sin importar mayusculas) o ToolError con las que hay. */
  static JobEntryCopy entrada( JobMeta jm, String nombre ) throws ToolError {
    JobEntryCopy c = buscar( jm, nombre );
    if ( c == null ) {
      throw new ToolError( "There is no entry '" + nombre + "' in the job. " + nombres( jm ) );
    }
    return c;
  }

  private static JobEntryCopy buscar( JobMeta jm, String nombre ) {
    String n = nombre.trim();
    for ( JobEntryCopy c : jm.getJobCopies() ) {
      if ( c.getName() != null && c.getName().equalsIgnoreCase( n ) ) {
        return c;
      }
    }
    return null;
  }

  private static String nombres( JobMeta jm ) {
    if ( jm.nrJobEntries() == 0 ) {
      return "The job has no entries.";
    }
    List<String> ns = new ArrayList<String>();
    for ( JobEntryCopy c : jm.getJobCopies() ) {
      ns.add( "'" + c.getName() + "'" );
    }
    return "Entries: " + String.join( ", ", ns ) + ".";
  }

  private static void nombreLibre( JobMeta jm, String nombre, JobEntryCopy excepto ) throws ToolError {
    JobEntryCopy otra = buscar( jm, nombre );
    if ( otra != null && otra != excepto ) {
      throw new ToolError( "There is already an entry named '" + otra.getName() + "'. Use another name." );
    }
  }

  /**
   * Agrega una entrada. Con 'after' la pone a su derecha y crea el hop; sin posicion, a la
   * derecha de la ultima entrada (o en 100,100 si el job esta vacio). Queda seleccionada.
   */
  static ObjectNode agregar( JobMeta jm, String tipo, String nombre, String config, Integer x, Integer y,
    String despuesDe, String condicion, Repository rep, IMetaStore ms, UndoGrupo undo ) throws Exception {
    String t = resolverTipo( tipo );
    if ( SPECIAL.equals( t ) ) {
      throw new ToolError( "For the special entry use type=START (start of the job) or type=DUMMY." );
    }
    if ( nombre == null || nombre.trim().isEmpty() ) {
      throw new ToolError( "Missing argument 'name'" );
    }
    nombre = nombre.trim();
    nombreLibre( jm, nombre, null );
    if ( START.equals( t ) && jm.findStart() != null ) {
      throw new ToolError( "The job already has a START ('" + jm.findStart().getName() + "'); there can only be "
        + "one." );
    }
    JobEntryCopy origen = despuesDe == null ? null : entrada( jm, despuesDe );
    String cond = origen == null ? null : condicionHop( origen, condicion );

    // posicion
    int px;
    int py;
    if ( x != null || y != null ) {
      Point base = origen != null ? origen.getLocation() : ultimaPosicion( jm );
      px = x != null ? x : ( base == null ? 100 : base.x + PASO_X );
      py = y != null ? y : ( base == null ? 100 : base.y );
    } else {
      Point base = origen != null ? origen.getLocation() : ultimaPosicion( jm );
      px = base == null ? 100 : base.x + PASO_X;
      py = base == null ? 100 : base.y;
      py = yLibre( jm, px, py );
    }
    if ( px < 0 || py < 0 ) {
      throw new ToolError( "The position must not be negative (x=" + px + ", y=" + py + ")" );
    }

    // la entrada
    JobEntryCopy copia;
    if ( START.equals( t ) || DUMMY.equals( t ) ) {
      copia = START.equals( t ) ? JobMeta.createStartEntry() : JobMeta.createDummyEntry();
      copia.setName( nombre );
      copia.setLocation( px, py );
      copia.setDrawn( true );
      if ( config != null && !config.trim().isEmpty() ) {
        copia.setEntry( reconstruir( jm, copia, config, rep, ms ).getEntry() );
      }
    } else {
      String cuerpo = Xml.mezclar( plantilla( t ), config, IGNORAR );
      String xml = "<entry>" + XMLHandler.addTagValue( "name", nombre ) + XMLHandler.addTagValue( "description", "" )
        + XMLHandler.addTagValue( "type", t ) + cuerpo + XMLHandler.addTagValue( "parallel", "N" )
        + XMLHandler.addTagValue( "draw", "Y" ) + XMLHandler.addTagValue( "nr", "0" )
        + XMLHandler.addTagValue( "xloc", String.valueOf( px ) ) + XMLHandler.addTagValue( "yloc", String.valueOf( py ) )
        + "</entry>";
      copia = desdeXml( jm, xml, rep, ms );
    }
    copia.setParentJobMeta( jm );

    jm.addJobEntry( copia );
    undo.nuevo( new Object[] { copia }, new int[] { jm.indexOfJobEntry( copia ) } );
    jm.unselectAll();
    copia.setSelected( true );

    ObjectNode o = ficha( copia );
    if ( origen != null ) {
      JobHopMeta h = new JobHopMeta( origen, copia );
      aplicarCondicion( h, cond );
      jm.addJobHop( h );
      undo.nuevo( new Object[] { h }, new int[] { jm.indexOfJobHop( h ) } );
      o.set( "hop", hop( h ) );
    }
    avisos( o, copia, condicion, origen );
    return o;
  }

  /** Cambia la configuracion: mezcla config_xml sobre la actual; nombre y posicion quedan. */
  static ObjectNode configurar( JobMeta jm, String nombre, String config, Repository rep, IMetaStore ms,
    UndoGrupo undo ) throws Exception {
    JobEntryCopy c = entrada( jm, nombre );
    if ( config == null || config.trim().isEmpty() ) {
      throw new ToolError( "Missing argument 'config_xml'" );
    }
    JobEntryCopy nueva = reconstruir( jm, c, config, rep, ms );
    JobEntryCopy antes = (JobEntryCopy) c.clone();
    c.setEntry( nueva.getEntry() );
    c.setParentJobMeta( jm );
    int idx = jm.indexOfJobEntry( c );
    undo.cambio( new Object[] { antes }, new Object[] { c.clone() }, new int[] { idx } );
    jm.unselectAll();
    c.setSelected( true );
    ObjectNode o = ficha( c );
    avisos( o, c, null, null );
    return o;
  }

  /**
   * Una copia nueva (sin agregar al job) con la configuracion actual de 'c' mezclada con 'config'.
   * Conserva nombre, tipo, atributos, posicion y el resto del envoltorio.
   */
  private static JobEntryCopy reconstruir( JobMeta jm, JobEntryCopy c, String config, Repository rep, IMetaStore ms )
    throws Exception {
    String actual = Xml.sinEnvoltorio( c.getXML(), Collections.<String>emptySet() );
    String cuerpo = Xml.mezclar( actual, config, IGNORAR );
    JobEntryCopy nueva = desdeXml( jm, "<entry>" + cuerpo + "</entry>", rep, ms );
    if ( nueva.isStart() != c.isStart() || nueva.isDummy() != c.isDummy() ) {
      throw new ToolError( "START/DUMMY cannot be turned into another type (or the other way round) by changing "
        + "<start>/<dummy>: delete the entry and add a new one." );
    }
    return nueva;
  }

  private static JobEntryCopy desdeXml( JobMeta jm, String xml, Repository rep, IMetaStore ms ) throws Exception {
    Node nodo = XMLHandler.getSubNode( XMLHandler.loadXMLString( xml ), "entry" );
    JobEntryCopy c = new JobEntryCopy( nodo, jm.getDatabases(), jm.getSlaveServers(), rep, ms );
    if ( c.isMissing() || c.getEntry() == null ) {
      throw new ToolError( "Spoon does not recognize the entry type (missing plugin)" );
    }
    return c;
  }

  /** Configuracion (XML sin contrasenias) y hops que entran y salen. */
  static ObjectNode ver( JobMeta jm, String nombre ) throws Exception {
    JobEntryCopy c = entrada( jm, nombre );
    ObjectNode o = ficha( c );
    if ( c.getDescription() != null && !c.getDescription().isEmpty() ) {
      o.put( "description", c.getDescription() );
    }
    if ( c.isLaunchingInParallel() ) {
      o.put( "parallel", true );
    }
    o.put( "config_xml", Xml.sinPasswords( Xml.sinEnvoltorio( c.getEntry().getXML(), IGNORAR ) ) );
    ArrayNode entran = o.putArray( "incoming_hops" );
    ArrayNode salen = o.putArray( "outgoing_hops" );
    for ( JobHopMeta h : jm.getJobhops() ) {
      if ( h.getToEntry() == c ) {
        entran.add( hop( h ) );
      }
      if ( h.getFromEntry() == c ) {
        salen.add( hop( h ) );
      }
    }
    return o;
  }

  static ObjectNode renombrar( JobMeta jm, String nombre, String nuevo, UndoGrupo undo ) throws ToolError {
    JobEntryCopy c = entrada( jm, nombre );
    if ( nuevo == null || nuevo.trim().isEmpty() ) {
      throw new ToolError( "Missing argument 'new_name'" );
    }
    nuevo = nuevo.trim();
    nombreLibre( jm, nuevo, c );
    String viejo = c.getName();
    JobEntryCopy antes = (JobEntryCopy) c.clone();
    c.setName( nuevo );
    undo.cambio( new Object[] { antes }, new Object[] { c.clone() }, new int[] { jm.indexOfJobEntry( c ) } );
    jm.unselectAll();
    c.setSelected( true );
    ObjectNode o = ficha( c );
    o.put( "previous_name", viejo );
    return o;
  }

  static ObjectNode mover( JobMeta jm, String nombre, int x, int y, UndoGrupo undo ) throws ToolError {
    JobEntryCopy c = entrada( jm, nombre );
    if ( x < 0 || y < 0 ) {
      throw new ToolError( "The position must not be negative (x=" + x + ", y=" + y + ")" );
    }
    Point antes = new Point( c.getLocation().x, c.getLocation().y );
    c.setLocation( x, y );
    undo.posicion( new Object[] { c }, new int[] { jm.indexOfJobEntry( c ) }, new Point[] { antes },
      new Point[] { new Point( x, y ) } );
    jm.unselectAll();
    c.setSelected( true );
    return ficha( c );
  }

  /** Borra la entrada y sus hops (como SpoonJobDelegate.deleteJobEntryCopies: un Ctrl+Z lo restaura todo). */
  static ObjectNode borrar( JobMeta jm, String nombre, UndoGrupo undo ) throws ToolError {
    JobEntryCopy c = entrada( jm, nombre );
    List<JobHopMeta> hops = new ArrayList<JobHopMeta>();
    List<Integer> idxs = new ArrayList<Integer>();
    for ( int i = 0; i < jm.nrJobHops(); i++ ) {
      JobHopMeta h = jm.getJobHop( i );
      if ( h.getFromEntry() == c || h.getToEntry() == c ) {
        hops.add( h );
        idxs.add( i );
      }
    }
    ArrayNode borrados = Tools.arr();
    if ( !hops.isEmpty() ) {
      Object[] clones = new Object[hops.size()];
      int[] pos = new int[hops.size()];
      for ( int i = 0; i < hops.size(); i++ ) {
        clones[i] = hops.get( i ).clone();
        pos[i] = idxs.get( i );
        borrados.add( hop( hops.get( i ) ) );
      }
      for ( int i = hops.size() - 1; i >= 0; i-- ) { // de atras para adelante: los indices no se corren
        jm.removeJobHop( pos[i] );
      }
      undo.borrado( clones, pos ); // el undo los reinserta en orden ascendente
    }
    int idx = jm.indexOfJobEntry( c );
    jm.removeJobEntry( idx );
    undo.borrado( new Object[] { c }, new int[] { idx } );
    ObjectNode o = Tools.obj();
    o.put( "deleted", c.getName() );
    o.set( "deleted_hops", borrados );
    return o;
  }

  // ---------------------------------------------------------------- hops

  static ObjectNode conectar( JobMeta jm, String desde, String hacia, String condicion, boolean permitirBucle,
    UndoGrupo undo ) throws ToolError {
    JobEntryCopy a = entrada( jm, desde );
    JobEntryCopy b = entrada( jm, hacia );
    if ( a == b ) {
      throw new ToolError( "A hop cannot go from an entry to itself" );
    }
    if ( b.isStart() ) {
      throw new ToolError( "A hop cannot go into START: it is the start of the job" );
    }
    if ( jm.findJobHop( a, b, true ) != null ) {
      throw new ToolError( "There is already a hop from '" + a.getName() + "' to '" + b.getName() + "'. To change "
        + "it use set_hop_condition." );
    }
    String cond = condicionHop( a, condicion );
    JobHopMeta h = new JobHopMeta( a, b );
    aplicarCondicion( h, cond );
    jm.addJobHop( h );
    if ( !permitirBucle && ( jm.hasLoop( b ) || jm.hasLoop( a ) ) ) {
      jm.removeJobHop( h );
      throw new ToolError( "The hop '" + a.getName() + "' -> '" + b.getName() + "' closes a loop. If the loop is "
        + "intentional (retries, looping over rows), pass allow_loop=true." );
    }
    undo.nuevo( new Object[] { h }, new int[] { jm.indexOfJobHop( h ) } );
    ObjectNode o = hop( h );
    avisoCondicion( o, a, condicion, cond );
    return o;
  }

  static ObjectNode desconectar( JobMeta jm, String desde, String hacia, UndoGrupo undo ) throws ToolError {
    JobHopMeta h = hopEntre( jm, desde, hacia );
    int idx = jm.indexOfJobHop( h );
    ObjectNode o = hop( h );
    jm.removeJobHop( idx );
    undo.borrado( new Object[] { h }, new int[] { idx } );
    o.put( "deleted", true );
    return o;
  }

  static ObjectNode cambiarCondicion( JobMeta jm, String desde, String hacia, String condicion, Boolean habilitado,
    UndoGrupo undo ) throws ToolError {
    JobHopMeta h = hopEntre( jm, desde, hacia );
    if ( condicion == null && habilitado == null ) {
      throw new ToolError( "Pass 'condition' and/or 'enabled'" );
    }
    int idx = jm.indexOfJobHop( h );
    JobHopMeta antes = (JobHopMeta) h.clone();
    String cond = condicion == null ? condicion( h ) : condicionHop( h.getFromEntry(), condicion );
    aplicarCondicion( h, cond );
    if ( habilitado != null ) {
      h.setEnabled( habilitado );
    }
    h.setChanged();
    undo.cambio( new Object[] { antes }, new Object[] { h.clone() }, new int[] { idx } );
    ObjectNode o = hop( h );
    avisoCondicion( o, h.getFromEntry(), condicion, cond );
    return o;
  }

  private static JobHopMeta hopEntre( JobMeta jm, String desde, String hacia ) throws ToolError {
    JobEntryCopy a = entrada( jm, desde );
    JobEntryCopy b = entrada( jm, hacia );
    JobHopMeta h = jm.findJobHop( a, b, true );
    if ( h == null ) {
      throw new ToolError( "There is no hop from '" + a.getName() + "' to '" + b.getName() + "'" );
    }
    return h;
  }

  /** Si el origen no admite condicion (START, Dummy...), el hop es siempre incondicional. */
  static boolean soloIncondicional( JobEntryCopy origen ) {
    return origen.isStart() || ( !origen.evaluates() && origen.isUnconditional() );
  }

  /** Condicion efectiva: la pedida (on_success por defecto), o 'always' si el origen lo exige. */
  static String condicionHop( JobEntryCopy origen, String pedida ) throws ToolError {
    if ( soloIncondicional( origen ) ) {
      return SIEMPRE;
    }
    if ( pedida == null || pedida.trim().isEmpty() ) {
      return SI_OK;
    }
    String p = pedida.trim().toLowerCase( Locale.ROOT );
    if ( p.equals( SIEMPRE ) || p.equals( SI_OK ) || p.equals( SI_FALLA ) ) {
      return p;
    }
    throw new ToolError( "'condition' must be " + SIEMPRE + ", " + SI_OK + " or " + SI_FALLA + "; got '" + pedida
      + "'" );
  }

  static void aplicarCondicion( JobHopMeta h, String cond ) {
    if ( SIEMPRE.equals( cond ) ) {
      h.setUnconditional( true );
      h.setEvaluation( true );
    } else {
      h.setUnconditional( false );
      h.setEvaluation( SI_OK.equals( cond ) );
    }
  }

  static String condicion( JobHopMeta h ) {
    return h.isUnconditional() ? SIEMPRE : ( h.getEvaluation() ? SI_OK : SI_FALLA );
  }

  private static void avisoCondicion( ObjectNode o, JobEntryCopy origen, String pedida, String efectiva ) {
    if ( pedida != null && !pedida.trim().isEmpty() && !pedida.trim().equalsIgnoreCase( efectiva ) ) {
      o.put( "warning", "'" + origen.getName() + "' " + ( origen.isStart() ? "is START" : "does not evaluate a "
        + "result" ) + ": its hops are always unconditional." );
    }
  }

  // ---------------------------------------------------------------- salida

  static ObjectNode ficha( JobEntryCopy c ) {
    ObjectNode o = Tools.obj();
    o.put( "name", c.getName() );
    o.put( "type", c.isStart() ? START : ( c.isDummy() ? DUMMY : c.getEntry().getPluginId() ) );
    o.put( "x", c.getLocation().x );
    o.put( "y", c.getLocation().y );
    return o;
  }

  static ObjectNode hop( JobHopMeta h ) {
    ObjectNode o = Tools.obj();
    o.put( "from", h.getFromEntry().getName() );
    o.put( "to", h.getToEntry().getName() );
    o.put( "condition", condicion( h ) );
    if ( !h.isEnabled() ) {
      o.put( "enabled", false );
    }
    return o;
  }

  /** Avisos utiles para la IA: rutas absolutas o vacias en TRANS/JOB, condicion forzada. */
  private static void avisos( ObjectNode o, JobEntryCopy c, String condPedida, JobEntryCopy origen ) {
    if ( origen != null && o.has( "hop" ) ) {
      ObjectNode tmp = Tools.obj();
      avisoCondicion( tmp, origen, condPedida, o.get( "hop" ).get( "condition" ).asText() );
      if ( tmp.has( "warning" ) ) {
        o.put( "warning", tmp.get( "warning" ).asText() );
      }
    }
    String id = c.getEntry() == null ? null : c.getEntry().getPluginId();
    if ( "TRANS".equals( id ) || "JOB".equals( id ) ) {
      String xml = c.getEntry().getXML();
      String metodo = valor( xml, "specification_method" );
      String archivo = valor( xml, "filename" );
      if ( "filename".equals( metodo ) || metodo == null ) {
        String ext = "TRANS".equals( id ) ? ".ktr" : ".kjb";
        if ( archivo == null || archivo.isEmpty() ) {
          o.put( "path_warning", "Missing <filename>: set it with config_xml, e.g. "
            + "<filename>${Internal.Entry.Current.Directory}/file" + ext + "</filename>" );
        } else if ( !archivo.contains( "${" ) ) {
          o.put( "path_warning", "The path is absolute; ${Internal.Entry.Current.Directory}/"
            + new java.io.File( archivo ).getName() + " is better, so the job keeps working if the folder is moved." );
        }
      }
    }
  }

  private static String valor( String xml, String tag ) {
    try {
      for ( org.w3c.dom.Element e : Xml.hijos( xml ) ) {
        if ( e.getTagName().equals( tag ) ) {
          return e.getTextContent().trim();
        }
      }
    } catch ( ToolError ignored ) {
      // sin aviso
    }
    return null;
  }

  // ---------------------------------------------------------------- ayudas

  /** Posicion de la ultima entrada agregada (la ultima de la lista), o null si no hay. */
  private static Point ultimaPosicion( JobMeta jm ) {
    int n = jm.nrJobEntries();
    return n == 0 ? null : jm.getJobEntry( n - 1 ).getLocation();
  }

  /** Baja de a PASO_Y hasta que el lugar no se pise con otra entrada. */
  private static int yLibre( JobMeta jm, int x, int y ) {
    int yy = y;
    for ( int i = 0; i < 50; i++ ) {
      boolean ocupado = false;
      for ( JobEntryCopy c : jm.getJobCopies() ) {
        Point p = c.getLocation();
        if ( p != null && Math.abs( p.x - x ) < 60 && Math.abs( p.y - yy ) < 60 ) {
          ocupado = true;
          break;
        }
      }
      if ( !ocupado ) {
        return yy;
      }
      yy += PASO_Y;
    }
    return yy;
  }

  static String normalizar( String s ) {
    String n = Normalizer.normalize( s == null ? "" : s, Normalizer.Form.NFD ).replaceAll( "\\p{M}", "" );
    return n.toLowerCase( Locale.ROOT ).trim();
  }

  private static int distancia( String a, String b ) {
    int[] prev = new int[b.length() + 1];
    int[] cur = new int[b.length() + 1];
    for ( int j = 0; j <= b.length(); j++ ) {
      prev[j] = j;
    }
    for ( int i = 1; i <= a.length(); i++ ) {
      cur[0] = i;
      for ( int j = 1; j <= b.length(); j++ ) {
        int c = a.charAt( i - 1 ) == b.charAt( j - 1 ) ? 0 : 1;
        cur[j] = Math.min( Math.min( cur[j - 1] + 1, prev[j] + 1 ), prev[j - 1] + c );
      }
      int[] t = prev;
      prev = cur;
      cur = t;
    }
    return prev[b.length()];
  }

  private static String nz( String s ) {
    return s == null ? "" : s;
  }
}
