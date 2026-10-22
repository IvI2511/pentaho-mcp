package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.gui.Point;
import org.pentaho.di.core.plugins.PluginInterface;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepErrorMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.ui.spoon.Spoon;
import org.pentaho.metastore.api.IMetaStore;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;

/**
 * build_steps: varios steps y hops en una sola llamada, animado (el usuario ve aparecer los nodos
 * de a uno) y deshacible con un solo Ctrl+Z.
 *
 * Pasos: leer() arma el lote desde el JSON; validar() revisa TODO contra el TransMeta sin tocarlo
 * (tipos, nombres, after/from/to, XML, hops repetidos, ciclos); agregar() suma un step (un Docs.ui
 * por step, con una pausa FUERA del hilo de UI entre uno y otro para que Spoon redibuje); cerrar()
 * resuelve referencias, crea los hops extra, aplica los modos de salida y ordena el lienzo. Todo el
 * undo va a un mismo {@link UndoGrupo}. leer/validar/agregar/cerrar no tocan Spoon: se prueban sin
 * GUI con UndoGrupo.sinSpoon.
 */
final class TransLote {

  static final int MAX_STEPS = 50;
  static final int MAX_HOPS = 100;
  static final int DELAY_DEFECTO = 350;
  static final int DELAY_MAX = 2000;

  private TransLote() {
  }

  /** Un step pedido. */
  static final class PasoSpec {
    int pos;
    String type;
    String name;
    String config;
    String after;
    Integer x;
    Integer y;
    /** copy / distribute (normalizado por modo()), o null. */
    String modo;

    String etiqueta() {
      return "steps[" + pos + "]" + ( name == null ? "" : " '" + name + "'" );
    }
  }

  /** Un hop extra pedido. */
  static final class HopSpec {
    int pos;
    String from;
    String to;
    boolean error;
    boolean enabled = true;

    String etiqueta() {
      return "hops[" + pos + "] " + from + " -> " + to;
    }
  }

  static final class Lote {
    final List<PasoSpec> steps = new ArrayList<PasoSpec>();
    final List<HopSpec> hops = new ArrayList<HopSpec>();
    int delayMs = DELAY_DEFECTO;
    boolean arrange = true;
    final List<String> avisos = new ArrayList<String>();

    /** El step del lote con ese nombre (sin distinguir mayusculas, como TransMeta.findStep), o null. */
    PasoSpec paso( String nombre ) {
      if ( nombre == null ) {
        return null;
      }
      for ( PasoSpec p : steps ) {
        if ( p.name != null && p.name.equalsIgnoreCase( nombre.trim() ) ) {
          return p;
        }
      }
      return null;
    }

    /** Modo de salida pedido para un step (null = el automatico: copiar si queda con 2+ salidas). */
    String modoDe( StepMeta sm ) {
      PasoSpec p = sm == null ? null : paso( sm.getName() );
      return p == null ? null : p.modo;
    }
  }

  /** Lo que queda de una ejecucion: el estado que pasa de un Docs.ui al siguiente. */
  static final class Ejecucion {
    TransMeta tm;
    UndoGrupo undo;
    TransTools.Cambios previos;
    IMetaStore ms;
    final List<StepMeta> creados = new ArrayList<StepMeta>();
    final List<TransHopMeta> hopsCreados = new ArrayList<TransHopMeta>();
    /** Steps que pasaron solos a copiar al quedar con 2+ salidas. */
    final List<StepMeta> aCopiar = new ArrayList<StepMeta>();
  }

  // ---------------------------------------------------------------- leer

  /** Arma el lote desde los argumentos; los errores de forma van a 'problemas'. */
  static Lote leer( JsonNode a, List<String> problemas ) {
    Lote l = new Lote();
    JsonNode steps = comoJson( a.get( "steps" ) );
    if ( steps == null || !steps.isArray() || steps.size() == 0 ) {
      problemas.add( "'steps' must be a non-empty array of {type, name, config_xml?, after?, x?, y?, output_mode?}" );
    } else if ( steps.size() > MAX_STEPS ) {
      problemas.add( "At most " + MAX_STEPS + " steps per call (got " + steps.size() + "): split the batch." );
    } else {
      for ( int i = 0; i < steps.size(); i++ ) {
        JsonNode n = steps.get( i );
        PasoSpec p = new PasoSpec();
        p.pos = i;
        if ( n == null || !n.isObject() ) {
          problemas.add( "steps[" + i + "]: must be an object {type, name, ...}" );
          continue;
        }
        p.type = texto( n, "type" );
        p.name = texto( n, "name" );
        p.config = texto( n, "config_xml" );
        p.after = texto( n, "after" );
        try {
          p.x = Tools.optInteger( n, "x" );
          p.y = Tools.optInteger( n, "y" );
        } catch ( ToolError e ) {
          problemas.add( p.etiqueta() + ": " + e.getMessage() );
        }
        String modo = texto( n, "output_mode" );
        if ( modo != null ) {
          p.modo = modo( modo );
          if ( p.modo == null ) {
            problemas.add( p.etiqueta() + ": output_mode must be 'copy' or 'distribute', got '" + modo + "'" );
          }
        }
        l.steps.add( p );
      }
    }
    JsonNode hops = comoJson( a.get( "hops" ) );
    if ( hops != null && !hops.isNull() ) {
      if ( !hops.isArray() ) {
        problemas.add( "'hops' must be an array of {from, to, error?, enabled?}" );
      } else if ( hops.size() > MAX_HOPS ) {
        problemas.add( "At most " + MAX_HOPS + " extra hops per call (got " + hops.size() + ")" );
      } else {
        for ( int i = 0; i < hops.size(); i++ ) {
          JsonNode n = hops.get( i );
          HopSpec h = new HopSpec();
          h.pos = i;
          if ( n == null || !n.isObject() ) {
            problemas.add( "hops[" + i + "]: must be an object {from, to, error?, enabled?}" );
            continue;
          }
          h.from = texto( n, "from" );
          h.to = texto( n, "to" );
          h.error = Tools.optBool( n, "error", false );
          h.enabled = Tools.optBool( n, "enabled", true );
          l.hops.add( h );
        }
      }
    }
    try {
      Integer d = Tools.optInteger( a, "delay_ms" );
      if ( d != null ) {
        l.delayMs = Math.max( 0, Math.min( DELAY_MAX, d ) );
        if ( l.delayMs != d ) {
          l.avisos.add( "delay_ms " + d + " is out of range: used " + l.delayMs + " (0.." + DELAY_MAX + ")." );
        }
      }
    } catch ( ToolError e ) {
      problemas.add( e.getMessage() );
    }
    l.arrange = Tools.optBool( a, "arrange", true );
    return l;
  }

  /** Algunos clientes mandan los arrays como texto JSON: se aceptan igual. */
  private static JsonNode comoJson( JsonNode n ) {
    if ( n != null && n.isTextual() ) {
      String t = n.asText().trim();
      if ( t.startsWith( "[" ) ) {
        try {
          return McpHttpServer.JSON.readTree( t );
        } catch ( Exception ignored ) {
          // queda como texto y falla la validacion
        }
      }
    }
    return n;
  }

  private static String texto( JsonNode n, String k ) {
    String s = Tools.optStr( n, k, null );
    return s == null ? null : ( k.equals( "config_xml" ) ? s : s.trim() );
  }

  /** copy/distribute (sin distinguir mayusculas) a lo que entiende TransOps.aplicarModoSalida; null si no. */
  static String modo( String m ) {
    String s = m.trim().toLowerCase( Locale.ROOT );
    if ( s.equals( "copy" ) ) {
      return "copy";
    }
    if ( s.equals( "distribute" ) ) {
      return "distribute";
    }
    return null;
  }

  // ---------------------------------------------------------------- validar

  /**
   * Revisa el lote contra la transformacion SIN modificarla. Devuelve la lista de problemas (vacia
   * si se puede construir). Arma cada step de prueba (plantilla + config_xml + StepMeta) para que
   * un XML que Kettle no puede leer falle aca y no a mitad de la animacion.
   */
  static List<String> validar( TransMeta tm, Lote l, IMetaStore ms ) {
    List<String> problemas = new ArrayList<String>();
    Map<String, PasoSpec> vistos = new LinkedHashMap<String, PasoSpec>();
    Map<String, StepMeta> prueba = new HashMap<String, StepMeta>();
    for ( PasoSpec p : l.steps ) {
      String et = p.etiqueta();
      if ( p.name == null || p.name.isEmpty() ) {
        problemas.add( et + ": missing 'name'" );
      } else if ( tm.findStep( p.name ) != null ) {
        problemas.add( et + ": a step named '" + tm.findStep( p.name ).getName()
          + "' already exists in the transformation (use configure_step to change it)" );
      } else if ( vistos.containsKey( clave( p.name ) ) ) {
        problemas.add( et + ": name repeated in this batch (also " + vistos.get( clave( p.name ) ).etiqueta() + ")" );
      }
      PluginInterface plugin = null;
      if ( p.type == null || p.type.isEmpty() ) {
        problemas.add( et + ": missing 'type' (step type id from list_step_types, e.g. CsvInput)" );
      } else {
        try {
          plugin = TransOps.plugin( p.type );
        } catch ( ToolError e ) {
          problemas.add( et + ": " + e.getMessage() );
        }
      }
      if ( p.after != null ) {
        if ( p.name != null && p.after.equalsIgnoreCase( p.name ) ) {
          problemas.add( et + ": 'after' cannot be the step itself" );
        } else if ( tm.findStep( p.after ) == null && !vistos.containsKey( clave( p.after ) ) ) {
          PasoSpec luego = l.paso( p.after );
          problemas.add( et + ": after='" + p.after + "' "
            + ( luego != null ? "comes later in the list (" + luego.etiqueta() + "): move it before, or use 'hops'"
            : "does not exist in the transformation nor earlier in this batch. " + TransOps.nombres( tm ) ) );
        }
      }
      if ( ( p.x != null && p.x < 0 ) || ( p.y != null && p.y < 0 ) ) {
        problemas.add( et + ": x/y must be positive" );
      }
      if ( plugin != null && p.name != null && !p.name.isEmpty() ) {
        try {
          String cuerpo = Xml.mezclar( TransOps.plantilla( plugin ), p.config, Xml.TAGS_ENVOLTORIO_STEP );
          StepMeta sm = TransOps.construir( tm, TransOps.id( plugin ), p.name, null, 1, true, cuerpo,
            new Point( 0, 0 ), ms );
          prueba.put( clave( p.name ), sm );
        } catch ( ToolError e ) {
          problemas.add( et + ": config_xml: " + e.getMessage() );
        } catch ( Exception e ) {
          problemas.add( et + ": config_xml: " + TransOps.mensaje( e ) );
        }
      }
      if ( p.name != null && !p.name.isEmpty() && !vistos.containsKey( clave( p.name ) ) ) {
        vistos.put( clave( p.name ), p );
      }
    }

    // Aristas por nombre (en minusculas): las que ya hay, las de 'after' y las de 'hops'.
    List<String[]> aristas = new ArrayList<String[]>();
    Set<String> pares = new HashSet<String>();
    Map<String, String> nombres = new HashMap<String, String>(); // clave -> nombre como lo escribieron
    for ( StepMeta s : tm.getSteps() ) {
      nombres.put( clave( s.getName() ), s.getName() );
    }
    for ( PasoSpec p : l.steps ) {
      if ( p.name != null && !nombres.containsKey( clave( p.name ) ) ) {
        nombres.put( clave( p.name ), p.name );
      }
    }
    for ( int i = 0; i < tm.nrTransHops(); i++ ) {
      TransHopMeta h = tm.getTransHop( i );
      if ( h.getFromStep() != null && h.getToStep() != null ) {
        String[] e = { clave( h.getFromStep().getName() ), clave( h.getToStep().getName() ) };
        aristas.add( e );
        pares.add( e[ 0 ] + "\n" + e[ 1 ] );
      }
    }
    for ( PasoSpec p : l.steps ) {
      if ( p.after != null && p.name != null && !p.name.isEmpty() ) {
        String[] e = { clave( p.after ), clave( p.name ) };
        aristas.add( e );
        pares.add( e[ 0 ] + "\n" + e[ 1 ] );
      }
    }
    Map<String, Integer> errores = new HashMap<String, Integer>();
    for ( HopSpec h : l.hops ) {
      String et = h.etiqueta();
      if ( h.from == null || h.to == null ) {
        problemas.add( "hops[" + h.pos + "]: needs 'from' and 'to'" );
        continue;
      }
      boolean ok = true;
      for ( String n : new String[] { h.from, h.to } ) {
        if ( tm.findStep( n ) == null && !vistos.containsKey( clave( n ) ) ) {
          problemas.add( et + ": no step '" + n + "' in the transformation nor in this batch" );
          ok = false;
        }
      }
      if ( !ok ) {
        continue;
      }
      if ( h.from.equalsIgnoreCase( h.to ) ) {
        problemas.add( et + ": a step cannot be connected to itself" );
        continue;
      }
      String par = clave( h.from ) + "\n" + clave( h.to );
      if ( pares.contains( par ) ) {
        problemas.add( et + ": that hop already exists (in the transformation, or created by 'after' or another "
          + "entry of 'hops')" );
        continue;
      }
      pares.add( par );
      aristas.add( new String[] { clave( h.from ), clave( h.to ) } );
      if ( h.error ) {
        StepMeta origen = tm.findStep( h.from );
        StepMeta s = origen != null ? origen : prueba.get( clave( h.from ) );
        if ( s != null && !s.supportsErrorHandling() ) {
          problemas.add( et + ": '" + h.from + "' (" + s.getStepID() + ") does not support error handling" );
        }
        Integer n = errores.get( clave( h.from ) );
        errores.put( clave( h.from ), n == null ? 1 : n + 1 );
        if ( n != null ) {
          problemas.add( et + ": '" + h.from + "' already has an error hop in this batch (only one per step)" );
        }
        StepErrorMeta sem = origen == null ? null : origen.getStepErrorMeta();
        if ( sem != null && sem.isEnabled() && sem.getTargetStep() != null
          && tm.findTransHop( origen, sem.getTargetStep(), true ) != null ) {
          problemas.add( et + ": '" + origen.getName() + "' already sends its errors to '"
            + sem.getTargetStep().getName() + "'" );
        }
      }
    }
    String ciclo = ciclo( aristas, nombres );
    if ( ciclo != null ) {
      problemas.add( "The hops would create a cycle: " + ciclo );
    }
    return problemas;
  }

  private static String clave( String nombre ) {
    return nombre == null ? "" : nombre.trim().toLowerCase( Locale.ROOT );
  }

  /** Un ciclo del grafo dirigido ("A -> B -> A", con los nombres de 'nombres'), o null si no hay. */
  static String ciclo( List<String[]> aristas, Map<String, String> nombres ) {
    final Map<String, List<String>> sal = new LinkedHashMap<String, List<String>>();
    for ( String[] e : aristas ) {
      List<String> l = sal.get( e[ 0 ] );
      if ( l == null ) {
        l = new ArrayList<String>();
        sal.put( e[ 0 ], l );
      }
      l.add( e[ 1 ] );
    }
    Map<String, Integer> estado = new HashMap<String, Integer>(); // 1 en la pila, 2 terminado
    for ( String raiz : new ArrayList<String>( sal.keySet() ) ) {
      if ( estado.containsKey( raiz ) ) {
        continue;
      }
      List<String> pila = new ArrayList<String>();
      List<Integer> siguiente = new ArrayList<Integer>();
      pila.add( raiz );
      siguiente.add( 0 );
      estado.put( raiz, 1 );
      while ( !pila.isEmpty() ) {
        int t = pila.size() - 1;
        String u = pila.get( t );
        List<String> vs = sal.get( u );
        int k = siguiente.get( t );
        if ( vs != null && k < vs.size() ) {
          siguiente.set( t, k + 1 );
          String v = vs.get( k );
          Integer ev = estado.get( v );
          if ( ev == null ) {
            estado.put( v, 1 );
            pila.add( v );
            siguiente.add( 0 );
          } else if ( ev == 1 ) {
            StringBuilder sb = new StringBuilder();
            for ( int i = pila.indexOf( v ); i < pila.size(); i++ ) {
              sb.append( nombre( nombres, pila.get( i ) ) ).append( " -> " );
            }
            return sb.append( nombre( nombres, v ) ).toString();
          }
        } else {
          estado.put( u, 2 );
          pila.remove( t );
          siguiente.remove( t );
        }
      }
    }
    return null;
  }

  private static String nombre( Map<String, String> nombres, String clave ) {
    String n = nombres == null ? null : nombres.get( clave );
    return "'" + ( n == null ? clave : n ) + "'";
  }

  // ---------------------------------------------------------------- ejecutar (sin Spoon)

  /** Antes de agregar el primer step: foto de los steps que ya estaban, para el undo. */
  static Ejecucion empezar( TransMeta tm, Lote l, UndoGrupo undo, IMetaStore ms ) {
    Ejecucion e = new Ejecucion();
    e.tm = tm;
    e.undo = undo;
    e.ms = ms;
    e.previos = TransTools.Cambios.antes( new ArrayList<StepMeta>( tm.getSteps() ) );
    // Cada step son 2 acciones de undo (step + hop) y al final hasta 3 mas (hops extra, cambios de
    // los steps que ya estaban, posiciones). Si el historial es mas corto que el lote, Spoon tiraria
    // las primeras acciones y el Ctrl+Z dejaria steps sueltos: se agranda (solo en memoria).
    int necesarias = 2 * l.steps.size() + 3;
    if ( tm.getMaxUndo() < necesarias ) {
      tm.setMaxUndo( necesarias );
    }
    return e;
  }

  /**
   * Agrega el step i del lote (con su hop 'after') y lo registra en el undo del lote. Si el previo
   * queda con 2+ salidas, aplica su modo de salida (el pedido, o copiar). Un step que ya estaba y
   * cambio queda en la foto de empezar(); los del lote no necesitan undo de cambio: al deshacer se
   * quitan, y al rehacer vuelve la misma instancia con su estado final.
   */
  static TransOps.Agregado agregar( Ejecucion e, Lote l, int i ) throws Exception {
    PasoSpec p = l.steps.get( i );
    TransMeta tm = e.tm;
    StepMeta previo = p.after == null ? null : TransOps.paso( tm, p.after );
    TransOps.Agregado r = TransOps.agregarStep( tm, p.type, p.name, p.config, p.x, p.y, p.after, null, e.ms );
    e.creados.add( r.step );
    e.undo.nuevo( new Object[] { r.step }, new int[] { tm.indexOfStep( r.step ) } );
    if ( r.hop != null ) {
      e.hopsCreados.add( r.hop );
      e.undo.nuevo( new Object[] { r.hop }, new int[] { tm.indexOfTransHop( r.hop ) } );
      modoSalida( e, l, previo );
    }
    if ( r.aviso != null ) {
      l.avisos.add( p.name + ": " + r.aviso );
    }
    return r;
  }

  /**
   * Cierra el lote: resuelve referencias en todos los steps, crea los hops extra (si 'completo'),
   * aplica los modos de salida, registra los cambios de los steps que ya estaban y, si se pidio,
   * ordena el lienzo. Con completo=false (fallo a mitad) solo deja el undo consistente.
   */
  static TransLayout.Movimientos cerrar( Ejecucion e, Lote l, boolean completo ) throws ToolError {
    TransMeta tm = e.tm;
    TransOps.resolverReferencias( tm );
    TransLayout.Movimientos mov = null;
    if ( completo ) {
      List<TransHopMeta> extra = new ArrayList<TransHopMeta>();
      for ( HopSpec h : l.hops ) {
        StepMeta a = TransOps.paso( tm, h.from );
        StepMeta b = TransOps.paso( tm, h.to );
        TransHopMeta th = TransOps.conectar( tm, a, b, h.enabled, h.error );
        extra.add( th );
        e.hopsCreados.add( th );
        if ( !h.error ) {
          modoSalida( e, l, a );
        }
      }
      registrarHops( e, extra );
      for ( StepMeta s : e.creados ) { // output_mode explicito aunque el step no haya quedado con 2 salidas
        String m = l.modoDe( s );
        if ( m != null ) {
          TransOps.aplicarModoSalida( tm, s, m );
        }
      }
      TransOps.resolverReferencias( tm );
    }
    e.previos.registrar( e.undo, tm ); // antes que las posiciones: ver UndoGrupo / el orden del undo
    if ( completo && l.arrange && !LeccionNotas.reconocer( tm ).isEmpty() ) {
      // documento con notas de leccion (modo clase): la regla de clase, steps y notas en el mismo grupo de undo
      LeccionNotas.MedidorCerrable med = LeccionNotas.medidor( LeccionNotas.tamFuente( tm ) );
      try {
        mov = LeccionNotas.reacomodar( tm, med, e.undo, -1 ).movimientos;
      } finally {
        med.cerrar();
      }
    } else if ( completo && l.arrange ) {
      mov = TransLayout.ordenar( tm );
      if ( !mov.objetos.isEmpty() ) {
        e.undo.posicion( mov.objetos(), mov.indices(), mov.antes(), mov.despues() );
      }
    }
    tm.unselectAll();
    for ( StepMeta s : e.creados ) {
      s.setSelected( true );
    }
    tm.clearCaches();
    return mov;
  }

  /** Todos los hops extra en UNA accion de undo (indices crecientes: Spoon los quita de atras para adelante). */
  private static void registrarHops( Ejecucion e, List<TransHopMeta> hops ) {
    if ( hops.isEmpty() ) {
      return;
    }
    final TransMeta tm = e.tm;
    List<TransHopMeta> orden = new ArrayList<TransHopMeta>( hops );
    Collections.sort( orden, new Comparator<TransHopMeta>() {
      @Override
      public int compare( TransHopMeta a, TransHopMeta b ) {
        return Integer.compare( tm.indexOfTransHop( a ), tm.indexOfTransHop( b ) );
      }
    } );
    Object[] objs = new Object[ orden.size() ];
    int[] idx = new int[ orden.size() ];
    for ( int i = 0; i < orden.size(); i++ ) {
      objs[ i ] = orden.get( i );
      idx[ i ] = tm.indexOfTransHop( orden.get( i ) );
    }
    e.undo.nuevo( objs, idx );
  }

  /**
   * Lo mismo que add_step hace con el step previo: el modo de salida pedido o, sin pedido, copiar
   * si quedo con 2+ salidas. Recuerda los que pasaron a copiar solos, para avisarlo.
   */
  private static void modoSalida( Ejecucion e, Lote l, StepMeta s ) throws ToolError {
    if ( TransOps.aplicarModoSalida( e.tm, s, l.modoDe( s ) ) != null && !e.aCopiar.contains( s ) ) {
      e.aCopiar.add( s );
    }
  }

  /** La respuesta para la IA. */
  static ObjectNode resultado( Ejecucion e, Lote l, TransLayout.Movimientos mov ) {
    ObjectNode o = Tools.obj();
    ArrayNode steps = o.putArray( "steps" );
    for ( StepMeta s : e.creados ) {
      steps.addObject().put( "name", s.getName() ).put( "type", s.getStepID() ).put( "x", s.getLocation().x )
        .put( "y", s.getLocation().y );
    }
    ArrayNode hops = o.putArray( "hops" );
    for ( TransHopMeta h : e.hopsCreados ) {
      ObjectNode x = hops.addObject().put( "from", h.getFromStep().getName() ).put( "to", h.getToStep().getName() );
      if ( !h.isEnabled() ) {
        x.put( "enabled", false );
      }
      if ( h.isErrorHop() ) {
        x.put( "error", true );
      }
    }
    List<String> notas = new ArrayList<String>();
    List<StepMeta> conSalidas = new ArrayList<StepMeta>( e.aCopiar ); // los que ya estaban, primero
    for ( StepMeta s : e.creados ) {
      if ( !conSalidas.contains( s ) ) {
        conSalidas.add( s );
      }
    }
    for ( StepMeta s : conSalidas ) {
      int n = TransOps.salidasNormales( e.tm, s );
      if ( n >= 2 ) {
        String t = "'" + s.getName() + "' has " + n + " outputs: "
          + ( s.isDistributes() ? "distribute (round robin: each row goes to one output)"
          : "copy (every output gets all rows)" );
        if ( e.aCopiar.contains( s ) ) {
          t += ", set automatically; to split the rows instead "
            + ( e.creados.contains( s ) ? "give it output_mode='distribute'" : "use set_output_mode" );
        }
        notas.add( t + "." );
      }
    }
    if ( mov != null ) {
      notas.add( "Canvas arranged: " + mov.objetos.size() + " node(s) moved, " + mov.columnas + " column(s)." );
    }
    if ( !notas.isEmpty() ) {
      ArrayNode n = o.putArray( "notes" );
      for ( String s : notas ) {
        n.add( s );
      }
    }
    if ( !l.avisos.isEmpty() ) {
      ArrayNode w = o.putArray( "warnings" );
      for ( String s : l.avisos ) {
        w.add( s );
      }
    }
    o.put( "undo", "One Ctrl+Z in Spoon removes the whole batch (Ctrl+Y redoes it)." );
    return o;
  }

  // ---------------------------------------------------------------- la herramienta (con Spoon)

  /** build_steps: validar en UI, un Docs.ui por step con pausas afuera, y cerrar. */
  static Object ejecutar( final JsonNode a ) throws Exception {
    final List<String> problemas = new ArrayList<String>();
    final Lote l = leer( a, problemas );
    final Ejecucion e = Docs.ui( () -> {
      Spoon sp = Docs.spoon();
      TransMeta tm = Docs.trans( a );
      if ( problemas.isEmpty() ) {
        problemas.addAll( validar( tm, l, sp.getMetaStore() ) );
      }
      if ( !problemas.isEmpty() ) {
        throw new ToolError( "build_steps: nothing was changed. Fix these problems and call again:\n- "
          + String.join( "\n- ", problemas ) );
      }
      return empezar( tm, l, new UndoGrupo( sp, tm ), sp.getMetaStore() );
    } );

    Exception fallo = null;
    int enCurso = -1;
    for ( int i = 0; i < l.steps.size(); i++ ) {
      if ( i > 0 && !pausa( l.delayMs ) ) {
        fallo = new ToolError( "interrupted" );
        break;
      }
      enCurso = i;
      final int k = i;
      try {
        Docs.ui( () -> {
          seguirAbierta( e.tm );
          agregar( e, l, k );
          Docs.touched( e.tm );
          return null;
        } );
      } catch ( Exception ex ) {
        fallo = ex;
        break;
      }
    }
    if ( fallo == null && l.arrange && l.delayMs > 0 ) {
      pausa( l.delayMs ); // que se vea el ultimo step antes de reacomodar
    }
    final boolean completo = fallo == null;
    ObjectNode res;
    try {
      res = Docs.ui( () -> {
        seguirAbierta( e.tm );
        TransLayout.Movimientos mov = cerrar( e, l, completo );
        Docs.touched( e.tm );
        ObjectNode o = resultado( e, l, mov );
        o.put( "document", Docs.id( e.tm ) );
        return o;
      } );
    } catch ( Exception ex ) {
      if ( fallo == null ) {
        throw new ToolError( "build_steps created all the steps but failed while finishing (extra hops / arrange): "
          + TransOps.mensaje( ex ) + ". " + creadosTexto( e ) + " One Ctrl+Z in Spoon removes the batch." );
      }
      res = null;
    }
    if ( fallo != null ) {
      PasoSpec p = l.steps.get( Math.max( 0, enCurso ) );
      throw new ToolError( "build_steps stopped at " + p.etiqueta() + " (" + p.type + "): " + TransOps.mensaje( fallo )
        + ". " + creadosTexto( e ) + " The extra hops and the arrange were not applied. One Ctrl+Z in Spoon "
        + "removes what was created." );
    }
    return res;
  }

  private static String creadosTexto( Ejecucion e ) {
    if ( e.creados.isEmpty() ) {
      return "No step was created.";
    }
    StringBuilder sb = new StringBuilder( "Created and kept on the canvas:" );
    for ( int i = 0; i < e.creados.size(); i++ ) {
      sb.append( i == 0 ? " '" : ", '" ).append( e.creados.get( i ).getName() ).append( '\'' );
    }
    return sb.append( '.' ).toString();
  }

  /** La transformacion sigue abierta (por identidad) y al frente. (UI) */
  private static void seguirAbierta( TransMeta tm ) throws ToolError {
    for ( AbstractMeta m : Docs.abiertos() ) {
      if ( m == tm ) {
        Docs.activar( tm );
        return;
      }
    }
    throw new ToolError( "the transformation was closed while build_steps was running" );
  }

  /** Pausa fuera del hilo de UI (asi Spoon redibuja). false si interrumpieron el hilo. */
  private static boolean pausa( int ms ) {
    if ( ms <= 0 ) {
      return true;
    }
    try {
      Thread.sleep( ms );
      return true;
    } catch ( InterruptedException ie ) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
