package io.github.ivi2511.pentahomcp;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.core.gui.Point;
import org.pentaho.di.core.plugins.PluginInterface;
import org.pentaho.di.core.plugins.PluginRegistry;
import org.pentaho.di.core.plugins.StepPluginType;
import org.pentaho.di.core.xml.XMLHandler;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepErrorMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;
import org.pentaho.metastore.api.IMetaStore;
import org.w3c.dom.Node;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;

/**
 * Logica pura de edicion de transformaciones: recibe un TransMeta y no toca Spoon (ni undo, ni
 * refresco, ni hilo de UI). TransTools la envuelve con Docs.ui + undo + Docs.touched. Asi se puede
 * probar sin GUI.
 */
final class TransOps {

  /** Separacion horizontal entre un step y el siguiente cuando la posicion la elige el plugin. */
  static final int PASO_X = 150;
  /** Cuanto se baja si el lugar elegido ya esta ocupado. */
  static final int PASO_Y = 100;

  private TransOps() {
  }

  // ---------------------------------------------------------------- tipos de step

  /** Minusculas y sin tildes, para buscar sin distinguir. */
  static String norm( String s ) {
    if ( s == null ) {
      return "";
    }
    return Normalizer.normalize( s, Normalizer.Form.NFD ).replaceAll( "\\p{M}", "" ).toLowerCase();
  }

  static List<PluginInterface> plugins() {
    List<PluginInterface> l = new ArrayList<PluginInterface>( PluginRegistry.getInstance().getPlugins( StepPluginType.class ) );
    Collections.sort( l, new Comparator<PluginInterface>() {
      @Override
      public int compare( PluginInterface a, PluginInterface b ) {
        int c = norm( a.getCategory() ).compareTo( norm( b.getCategory() ) );
        return c != 0 ? c : norm( a.getName() ).compareTo( norm( b.getName() ) );
      }
    } );
    return l;
  }

  static String id( PluginInterface p ) {
    return p.getIds()[ 0 ];
  }

  /** El plugin de step con ese id (el valor de &lt;type&gt;), o ToolError con los ids parecidos. */
  static PluginInterface plugin( String tipo ) throws ToolError {
    String t = tipo == null ? "" : tipo.trim();
    PluginRegistry reg = PluginRegistry.getInstance();
    PluginInterface p = reg.findPluginWithId( StepPluginType.class, t );
    if ( p != null ) {
      return p;
    }
    for ( PluginInterface q : plugins() ) { // mismo id con otras mayusculas, o el nombre en pantalla
      for ( String i : q.getIds() ) {
        if ( i.equalsIgnoreCase( t ) ) {
          return q;
        }
      }
    }
    PluginInterface porNombre = null;
    for ( PluginInterface q : plugins() ) {
      if ( norm( q.getName() ).equals( norm( t ) ) ) {
        if ( porNombre != null ) {
          porNombre = null;
          break;
        }
        porNombre = q;
      }
    }
    if ( porNombre != null ) {
      throw new ToolError( "'" + t + "' es el nombre en pantalla; el tipo (id) es '" + id( porNombre ) + "'" );
    }
    throw new ToolError( "No existe el tipo de step '" + t + "'. " + sugerencias( t )
      + " Usa tipos_de_step(buscar=...) para buscar." );
  }

  /** "Parecidos: A (nombre), B (nombre)..." para los mensajes de error. */
  static String sugerencias( String q ) {
    final String n = norm( q );
    final List<Object[]> cands = new ArrayList<Object[]>(); // {plugin, puntaje}
    for ( PluginInterface p : plugins() ) {
      String i = norm( id( p ) );
      String nom = norm( p.getName() );
      int puntaje;
      if ( !n.isEmpty() && ( i.contains( n ) || nom.contains( n ) ) ) {
        puntaje = 0;
      } else if ( !n.isEmpty() && n.length() >= 3 && ( n.contains( i ) || n.contains( nom ) ) ) {
        puntaje = 1;
      } else {
        int d = Math.min( distancia( i, n ), distancia( nom, n ) );
        if ( d > Math.max( 2, n.length() / 3 ) ) {
          continue;
        }
        puntaje = 1 + d;
      }
      cands.add( new Object[] { p, puntaje } );
    }
    Collections.sort( cands, new Comparator<Object[]>() {
      @Override
      public int compare( Object[] a, Object[] b ) {
        return ( (Integer) a[ 1 ] ).compareTo( (Integer) b[ 1 ] );
      }
    } );
    if ( cands.isEmpty() ) {
      return "No encontre ids parecidos.";
    }
    StringBuilder sb = new StringBuilder( "Parecidos:" );
    for ( int k = 0; k < cands.size() && k < 8; k++ ) {
      PluginInterface p = (PluginInterface) cands.get( k )[ 0 ];
      sb.append( k == 0 ? " " : ", " ).append( id( p ) ).append( " (" ).append( p.getName() ).append( ')' );
    }
    return sb.append( '.' ).toString();
  }

  private static int distancia( String a, String b ) {
    int[] prev = new int[ b.length() + 1 ];
    int[] cur = new int[ b.length() + 1 ];
    for ( int j = 0; j <= b.length(); j++ ) {
      prev[ j ] = j;
    }
    for ( int i = 1; i <= a.length(); i++ ) {
      cur[ 0 ] = i;
      for ( int j = 1; j <= b.length(); j++ ) {
        int c = a.charAt( i - 1 ) == b.charAt( j - 1 ) ? 0 : 1;
        cur[ j ] = Math.min( Math.min( cur[ j - 1 ] + 1, prev[ j ] + 1 ), prev[ j - 1 ] + c );
      }
      int[] t = prev;
      prev = cur;
      cur = t;
    }
    return prev[ b.length() ];
  }

  /**
   * Cuerpo XML por defecto del step (setDefault + getXML), sin envoltorio. La meta se cuelga de un
   * StepMeta y un TransMeta de mentira: SortRows, BlockingStep, ZipFile, Avro y Kafka dan NPE si no.
   */
  static String plantilla( PluginInterface p ) throws Exception {
    StepMetaInterface smi = (StepMetaInterface) PluginRegistry.getInstance().loadClass( p );
    smi.setDefault();
    TransMeta falsa = new TransMeta();
    StepMeta sm = new StepMeta( id( p ), p.getName(), smi );
    smi.setParentStepMeta( sm );
    sm.setParentTransMeta( falsa );
    sm.setLocation( 100, 100 );
    falsa.addStep( sm );
    return Xml.sinEnvoltorio( smi.getXML(), Xml.TAGS_ENVOLTORIO_STEP );
  }

  // ---------------------------------------------------------------- buscar

  /** El step con ese nombre, o ToolError con los nombres que hay. */
  static StepMeta paso( TransMeta tm, String nombre ) throws ToolError {
    StepMeta sm = tm.findStep( nombre );
    if ( sm == null ) {
      throw new ToolError( "No hay ningun step '" + nombre + "' en '" + Docs.nombre( tm ) + "'. " + nombres( tm ) );
    }
    return sm;
  }

  static String nombres( TransMeta tm ) {
    if ( tm.nrSteps() == 0 ) {
      return "La transformacion no tiene steps.";
    }
    StringBuilder sb = new StringBuilder( "Steps:" );
    for ( int i = 0; i < tm.nrSteps(); i++ ) {
      sb.append( i == 0 ? " '" : ", '" ).append( tm.getStep( i ).getName() ).append( '\'' );
    }
    return sb.toString();
  }

  static TransHopMeta hop( TransMeta tm, String desde, String hacia ) throws ToolError {
    StepMeta a = paso( tm, desde );
    StepMeta b = paso( tm, hacia );
    TransHopMeta h = tm.findTransHop( a, b, true );
    if ( h == null ) {
      throw new ToolError( "No hay hop de '" + a.getName() + "' a '" + b.getName() + "'" );
    }
    return h;
  }

  // ---------------------------------------------------------------- agregar / configurar

  /** Lo que devuelve agregarStep: el step nuevo y, si se pidio despues_de, el hop creado. */
  static final class Agregado {
    StepMeta step;
    TransHopMeta hop;
    String aviso;
  }

  /**
   * Agrega un step al TransMeta: mezcla los valores por defecto con config, lo ubica y, con
   * despuesDe, crea el hop despuesDe -> nuevo. Deja seleccionado solo el step nuevo.
   */
  static Agregado agregarStep( TransMeta tm, String tipo, String nombre, String config, Integer x, Integer y,
                               String despuesDe, String descripcion, IMetaStore ms ) throws Exception {
    PluginInterface p = plugin( tipo );
    String nom = nombre == null ? "" : nombre.trim();
    if ( nom.isEmpty() ) {
      throw new ToolError( "Falta el nombre del step" );
    }
    StepMeta existente = tm.findStep( nom );
    if ( existente != null ) {
      throw new ToolError( "Ya hay un step llamado '" + existente.getName() + "' (" + existente.getStepID()
        + "). Elegi otro nombre o usa configurar_step para cambiar el existente." );
    }
    StepMeta previo = despuesDe == null ? null : paso( tm, despuesDe );
    Point pos = posicionNueva( tm, previo, x, y );
    String cuerpo = Xml.mezclar( plantilla( p ), config, Xml.TAGS_ENVOLTORIO_STEP );
    StepMeta sm = construir( tm, id( p ), nom, descripcion, 1, true, cuerpo, pos, ms );
    tm.addStep( sm );
    resolverReferencias( tm );
    Agregado r = new Agregado();
    r.step = sm;
    r.aviso = avisoConexion( tm, cuerpo );
    if ( previo != null ) {
      TransHopMeta h = new TransHopMeta( previo, sm, true );
      tm.addTransHop( h );
      r.hop = h;
    }
    tm.unselectAll();
    sm.setSelected( true );
    return r;
  }

  /** Arma un StepMeta desde el &lt;step&gt; completo. Falla si el plugin no carga. */
  static StepMeta construir( TransMeta tm, String tipo, String nombre, String descripcion, int copias,
                             boolean distribuir, String cuerpo, Point pos, IMetaStore ms ) throws Exception {
    StringBuilder xml = new StringBuilder( "<step>" );
    xml.append( XMLHandler.addTagValue( "name", nombre ) );
    xml.append( XMLHandler.addTagValue( "type", tipo ) );
    xml.append( XMLHandler.addTagValue( "description", descripcion ) );
    xml.append( XMLHandler.addTagValue( "distribute", distribuir ) );
    xml.append( XMLHandler.addTagValue( "copies", copias ) );
    xml.append( "<partitioning><method>none</method><schema_name/></partitioning>\n" );
    xml.append( cuerpo == null ? "" : cuerpo );
    xml.append( "<cluster_schema/>\n" );
    xml.append( "<GUI><xloc>" ).append( pos.x ).append( "</xloc><yloc>" ).append( pos.y )
      .append( "</yloc><draw>Y</draw></GUI></step>" );
    Node nodo;
    try {
      nodo = XMLHandler.getSubNode( XMLHandler.loadXMLString( xml.toString() ), "step" );
    } catch ( Exception e ) {
      throw new ToolError( "XML de configuracion invalido: " + mensaje( e ) );
    }
    StepMeta sm;
    try {
      sm = new StepMeta( nodo, tm.getDatabases(), ms );
    } catch ( Exception e ) {
      throw new ToolError( "Kettle no pudo leer la configuracion de '" + nombre + "' (" + tipo + "): " + mensaje( e ) );
    }
    if ( sm.isMissing() ) {
      throw new ToolError( "El plugin del tipo '" + tipo + "' no esta disponible en esta instalacion" );
    }
    sm.getStepMetaInterface().setParentStepMeta( sm );
    return sm;
  }

  /**
   * Mezcla config sobre la configuracion ACTUAL del step y le pone la meta resultante. Mantiene
   * nombre, posicion, copias, descripcion y manejo de errores.
   */
  static String reconfigurar( TransMeta tm, StepMeta sm, String config, IMetaStore ms ) throws Exception {
    String actual = Xml.sinEnvoltorio( sm.getStepMetaInterface().getXML(), Xml.TAGS_ENVOLTORIO_STEP );
    String cuerpo = Xml.mezclar( actual, config, Xml.TAGS_ENVOLTORIO_STEP );
    StepMeta nuevo = construir( tm, sm.getStepID(), sm.getName(), sm.getDescription(), 1, sm.isDistributes(),
      cuerpo, sm.getLocation(), ms );
    StepMetaInterface smi = nuevo.getStepMetaInterface();
    sm.setStepMetaInterface( smi );
    smi.setParentStepMeta( sm );
    sm.setChanged();
    resolverReferencias( tm );
    return avisoConexion( tm, cuerpo );
  }

  /** Si el step nombra una conexion que el documento no tiene, el aviso para la IA (si no, null). */
  static String avisoConexion( TransMeta tm, String cuerpo ) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile( "<connection>([^<]+)</connection>" ).matcher(
      cuerpo == null ? "" : cuerpo );
    while ( m.find() ) {
      String n = m.group( 1 ).trim().replace( "&amp;", "&" ).replace( "&lt;", "<" ).replace( "&gt;", ">" );
      if ( !n.isEmpty() && !n.startsWith( "${" ) && tm.findDatabase( n ) == null ) {
        return "La conexion '" + n + "' no existe en esta transformacion: el step queda sin conexion hasta que "
          + "la agregues y vuelvas a configurarlo.";
      }
    }
    return null;
  }

  /**
   * Resuelve en TODOS los steps las referencias por nombre (send_true_to, &lt;from&gt;, Switch/case...).
   * TransMeta no lo expone; es lo mismo que hace loadXML al abrir un .ktr.
   */
  static void resolverReferencias( TransMeta tm ) {
    List<StepMeta> steps = tm.getSteps();
    for ( StepMeta s : steps ) {
      if ( s.getStepMetaInterface() != null ) {
        try {
          s.getStepMetaInterface().searchInfoAndTargetSteps( steps );
        } catch ( RuntimeException e ) {
          McpLifecycleListener.LOG.logDebug( "searchInfoAndTargetSteps fallo en " + s.getName() + ": " + e );
        }
      }
    }
    tm.clearCaches();
  }

  /**
   * Posicion para un step nuevo. Con 'previo' y sin x/y: a su derecha (bajando si esta ocupado).
   * Sin nada: a la derecha del step mas a la derecha, o (100,100) si esta vacia.
   */
  static Point posicionNueva( TransMeta tm, StepMeta previo, Integer x, Integer y ) {
    if ( x != null && y != null ) {
      return new Point( x, y );
    }
    Point p;
    if ( previo != null ) {
      p = new Point( previo.getLocation().x + PASO_X, previo.getLocation().y );
    } else {
      StepMeta derecha = null;
      for ( StepMeta s : tm.getSteps() ) {
        if ( derecha == null || s.getLocation().x > derecha.getLocation().x ) {
          derecha = s;
        }
      }
      p = derecha == null ? new Point( 100, 100 ) : new Point( derecha.getLocation().x + PASO_X, derecha.getLocation().y );
    }
    if ( x != null ) {
      p.x = x;
    }
    if ( y != null ) {
      p.y = y;
    }
    if ( y == null ) {
      while ( ocupado( tm, p ) ) {
        p.y += PASO_Y;
      }
    }
    return p;
  }

  private static boolean ocupado( TransMeta tm, Point p ) {
    for ( StepMeta s : tm.getSteps() ) {
      if ( Math.abs( s.getLocation().x - p.x ) < 90 && Math.abs( s.getLocation().y - p.y ) < 70 ) {
        return true;
      }
    }
    return false;
  }

  // ---------------------------------------------------------------- renombrar

  /**
   * Renombra un step y actualiza las referencias de los demas. Los steps guardan el nombre referido
   * como texto (lo que leyo loadXML) y ademas el StepMeta resuelto; getXML escribe el del StepMeta,
   * pero searchInfoAndTargetSteps vuelve a buscar por el texto viejo. Por eso: se renombra, se
   * relee la meta de cada step cuyo XML cambio (asi el texto queda con el nombre nuevo) y recien
   * despues se resuelven las referencias.
   *
   * @return los otros steps que cambiaron, como pares {clon de antes, step actual} para el undo.
   */
  static List<StepMeta[]> renombrar( TransMeta tm, StepMeta sm, String nuevo, IMetaStore ms ) throws Exception {
    String n = nuevo == null ? "" : nuevo.trim();
    if ( n.isEmpty() ) {
      throw new ToolError( "Falta el nuevo nombre" );
    }
    StepMeta otro = tm.findStep( n );
    if ( otro != null && otro != sm ) {
      throw new ToolError( "Ya hay un step llamado '" + otro.getName() + "'" );
    }
    List<StepMeta> otros = new ArrayList<StepMeta>();
    List<String> xmlAntes = new ArrayList<String>();
    List<StepMeta> clones = new ArrayList<StepMeta>();
    for ( StepMeta s : tm.getSteps() ) {
      if ( s != sm ) {
        otros.add( s );
        xmlAntes.add( xmlSeguro( s ) );
        clones.add( (StepMeta) s.clone() );
      }
    }
    StepMeta antes = (StepMeta) sm.clone();
    sm.setName( n );
    tm.notifyAllListeners( antes, sm );
    List<StepMeta[]> cambiados = new ArrayList<StepMeta[]>();
    for ( int i = 0; i < otros.size(); i++ ) {
      StepMeta s = otros.get( i );
      String x = xmlSeguro( s );
      if ( x == null || x.equals( xmlAntes.get( i ) ) ) {
        continue;
      }
      try {
        StepMeta releido = new StepMeta( XMLHandler.getSubNode( XMLHandler.loadXMLString( x ), "step" ),
          tm.getDatabases(), ms );
        if ( !releido.isMissing() ) {
          s.setStepMetaInterface( releido.getStepMetaInterface() );
          releido.getStepMetaInterface().setParentStepMeta( s );
        }
      } catch ( Exception e ) {
        McpLifecycleListener.LOG.logDebug( "No se pudo releer " + s.getName() + " tras renombrar: " + e );
      }
      cambiados.add( new StepMeta[] { clones.get( i ), s } );
    }
    resolverReferencias( tm );
    return cambiados;
  }

  private static String xmlSeguro( StepMeta s ) {
    try {
      return s.getXML();
    } catch ( Exception e ) {
      return null;
    }
  }

  // ---------------------------------------------------------------- modo de salida (copiar / repartir)

  /**
   * Hops normales que salen del step: sin los de error. Si el step manda las filas a destinos
   * concretos (Filter rows, Switch/case: target streams), devuelve 0, porque ahi el modo de salida
   * no aplica.
   */
  static int salidasNormales( TransMeta tm, StepMeta a ) {
    try {
      if ( !a.getStepMetaInterface().getStepIOMeta().getTargetStreams().isEmpty() ) {
        return 0;
      }
    } catch ( Exception ignored ) {
      // sin StepIOMeta: se cuenta como un step comun
    }
    int n = 0;
    for ( int i = 0; i < tm.nrTransHops(); i++ ) {
      TransHopMeta h = tm.getTransHop( i );
      if ( h.getFromStep() == a && !h.isErrorHop() ) {
        n++;
      }
    }
    return n;
  }

  static String modoSalida( StepMeta a ) {
    return a.isDistributes() ? "repartir" : "copiar";
  }

  /**
   * Lo que en Spoon es la pregunta "copiar o repartir" al sacar un segundo hop de un step. Con modo
   * explicito ("copiar" / "repartir") lo aplica; sin modo, si el step quedo con 2+ salidas normales y
   * esta repartiendo, lo pasa a copiar (lo que casi siempre se quiere: si no, cada salida recibe solo
   * una parte de las filas). Devuelve una nota para la IA, o null si no hizo nada.
   */
  static String aplicarModoSalida( TransMeta tm, StepMeta a, String modo ) throws ToolError {
    if ( modo != null ) {
      String m = modo.trim().toLowerCase();
      if ( !m.equals( "copiar" ) && !m.equals( "repartir" ) ) {
        throw new ToolError( "modo_salida tiene que ser 'copiar' o 'repartir', llego: " + modo );
      }
      boolean repartir = m.equals( "repartir" );
      if ( a.isDistributes() != repartir ) {
        a.setDistributes( repartir );
        a.setChanged();
      }
      return null;
    }
    if ( salidasNormales( tm, a ) >= 2 && a.isDistributes() ) {
      a.setDistributes( false );
      a.setChanged();
      return "'" + a.getName() + "' tiene varias salidas: quedo en modo COPIAR (cada salida recibe todas las "
        + "filas). Si querias repartirlas entre las salidas, usa modo_salida con 'repartir'.";
    }
    return null;
  }

  // ---------------------------------------------------------------- hops

  /**
   * Crea el hop desde -> hacia. Con deError configura el manejo de errores del origen (StepErrorMeta
   * habilitado con ese destino) y marca el hop como de error.
   */
  static TransHopMeta conectar( TransMeta tm, StepMeta a, StepMeta b, boolean habilitado, boolean deError )
    throws ToolError {
    if ( a == b ) {
      throw new ToolError( "Un step no puede conectarse consigo mismo" );
    }
    TransHopMeta existe = tm.findTransHop( a, b, true );
    if ( existe != null ) {
      throw new ToolError( "Ya hay un hop de '" + a.getName() + "' a '" + b.getName() + "'"
        + ( existe.isEnabled() ? "" : " (deshabilitado: usa habilitar_hop)" ) );
    }
    if ( tm.findTransHop( b, a, true ) != null ) {
      throw new ToolError( "Ya hay un hop de '" + b.getName() + "' a '" + a.getName() + "': crearia un ciclo" );
    }
    if ( deError ) {
      if ( !a.supportsErrorHandling() ) {
        throw new ToolError( "El step '" + a.getName() + "' (" + a.getStepID() + ") no admite manejo de errores" );
      }
      StepErrorMeta prev = a.getStepErrorMeta();
      if ( prev != null && prev.isEnabled() && prev.getTargetStep() != null && prev.getTargetStep() != b
        && tm.findTransHop( a, prev.getTargetStep(), true ) != null ) {
        throw new ToolError( "'" + a.getName() + "' ya manda sus errores a '" + prev.getTargetStep().getName()
          + "'. Desconecta ese hop primero (un step tiene un solo destino de errores)." );
      }
    }
    TransHopMeta h = new TransHopMeta( a, b, habilitado );
    tm.addTransHop( h );
    if ( tm.hasLoop( b ) || tm.hasLoop( a ) ) {
      tm.removeTransHop( tm.indexOfTransHop( h ) );
      throw new ToolError( "El hop '" + a.getName() + "' -> '" + b.getName() + "' crearia un ciclo" );
    }
    if ( deError ) {
      StepErrorMeta sem = a.getStepErrorMeta();
      if ( sem == null ) {
        sem = new StepErrorMeta( tm, a, b );
      }
      sem.setSourceStep( a );
      sem.setTargetStep( b );
      sem.setEnabled( true );
      a.setStepErrorMeta( sem );
      h.setErrorHop( true );
    }
    tm.clearCaches();
    return h;
  }

  /**
   * Despues de quitar un hop: que los steps limpien referencias al otro extremo (Filter rows,
   * Switch/case...) y, si era de error, apagar el manejo de errores del origen.
   */
  static void limpiarTrasQuitarHop( TransHopMeta h ) {
    StepMeta a = h.getFromStep();
    StepMeta b = h.getToStep();
    if ( a != null && a.getStepMetaInterface() != null ) {
      a.getStepMetaInterface().cleanAfterHopFromRemove( b );
    }
    if ( b != null && b.getStepMetaInterface() != null ) {
      b.getStepMetaInterface().cleanAfterHopToRemove( a );
    }
    if ( h.isErrorHop() && a != null && a.getStepErrorMeta() != null && a.getStepErrorMeta().getTargetStep() == b ) {
      a.getStepErrorMeta().setEnabled( false );
    }
  }

  /** Hops que tocan un step (entrada o salida). */
  static List<TransHopMeta> hopsDe( TransMeta tm, StepMeta sm ) {
    List<TransHopMeta> l = new ArrayList<TransHopMeta>();
    for ( int i = 0; i < tm.nrTransHops(); i++ ) {
      TransHopMeta h = tm.getTransHop( i );
      if ( h.getFromStep() == sm || h.getToStep() == sm ) {
        l.add( h );
      }
    }
    return l;
  }

  // ---------------------------------------------------------------- describir

  static ObjectNode ficha( StepMeta sm ) {
    ObjectNode o = Tools.obj();
    o.put( "nombre", sm.getName() );
    o.put( "tipo", sm.getStepID() );
    o.put( "x", sm.getLocation().x );
    o.put( "y", sm.getLocation().y );
    return o;
  }

  static ObjectNode fichaHop( TransHopMeta h ) {
    ObjectNode o = Tools.obj();
    o.put( "desde", h.getFromStep().getName() );
    o.put( "hacia", h.getToStep().getName() );
    if ( !h.isEnabled() ) {
      o.put( "habilitado", false );
    }
    if ( h.isErrorHop() ) {
      o.put( "de_error", true );
    }
    return o;
  }

  /** Ficha del step con su XML completo (sin contrasenias) y sus hops de entrada y salida. */
  static ObjectNode ver( TransMeta tm, StepMeta sm ) throws Exception {
    ObjectNode o = ficha( sm );
    if ( sm.getDescription() != null && !sm.getDescription().isEmpty() ) {
      o.put( "descripcion", sm.getDescription() );
    }
    if ( sm.getCopies() != 1 ) {
      o.put( "copias", sm.getCopies() );
    }
    ArrayNode entrada = o.putArray( "hops_entrada" );
    ArrayNode salida = o.putArray( "hops_salida" );
    for ( TransHopMeta h : hopsDe( tm, sm ) ) {
      ( h.getToStep() == sm ? entrada : salida ).add( fichaHop( h ) );
    }
    StepErrorMeta sem = sm.getStepErrorMeta();
    if ( sem != null && sem.isEnabled() && sem.getTargetStep() != null ) {
      o.put( "errores_a", sem.getTargetStep().getName() );
    }
    o.put( "xml", Xml.sinPasswords( sm.getXML() ) );
    return o;
  }

  static String mensaje( Throwable e ) {
    String m = e.getMessage();
    Throwable c = e.getCause();
    while ( ( m == null || m.trim().isEmpty() ) && c != null ) {
      m = c.getMessage();
      c = c.getCause();
    }
    return m == null ? e.getClass().getSimpleName() : m.trim().replaceAll( "\\s+", " " );
  }
}
