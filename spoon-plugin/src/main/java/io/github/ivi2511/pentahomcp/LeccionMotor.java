package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.NotePadMeta;
import org.pentaho.di.core.gui.Point;
import org.pentaho.di.core.undo.TransAction;
import org.pentaho.di.repository.Repository;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.ui.spoon.Spoon;
import org.pentaho.metastore.api.IMetaStore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.Leccion.Estado;
import io.github.ivi2511.pentahomcp.Leccion.Paso;
import io.github.ivi2511.pentahomcp.Leccion.Ritmo;
import io.github.ivi2511.pentahomcp.LeccionLayout.Pedida;
import io.github.ivi2511.pentahomcp.LeccionLayout.Previas;
import io.github.ivi2511.pentahomcp.LeccionLayout.Tipo;
import io.github.ivi2511.pentahomcp.LeccionLayout.Ubicada;
import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;

/**
 * Modo clase, WP3: reproduce una {@link Leccion} (docs/modo-clase-diseno.md §5 y §8). lesson_start valida y planifica
 * todo y aplica el paso 0 (titulo, steps que ya estaban en su lugar, notas previas apiladas; su propio Ctrl+Z); cada
 * paso siguiente es otro Ctrl+Z: el step con su hop 'after', los hops del guion que cierran ahi, las referencias por
 * nombre y los cambios de los steps previos, y 400 ms despues sus notas; despues, si se pidio, la vista previa (fuera
 * del hilo de UI, sobre una copia, como preview_step).
 * <p>
 * Ritmos: guided (espera un clic: {@link #clic}; la pestana «Leccion» llega con WP4), auto (cada delay_ms, contado
 * desde el fin del paso anterior) y manual (solo lesson_next). Un hilo {@code pentaho-live-leccion} por leccion en
 * guided/auto; los clics de mas mientras corre un paso se descartan (no se acumulan). Antes de cada paso se verifica
 * que lo anterior siga en el lienzo: si no, out_of_sync (Ctrl+Z del usuario) y «resume» re-sincroniza. Documento
 * cerrado: stopped / document_closed.
 * <p>
 * Todo lo que toca Spoon pasa por un {@link Entorno} (Spoon real o, en las pruebas, sin Spoon).
 */
final class LeccionMotor {

  /** Pausa entre el step y sus notas (§5). */
  static final int PAUSA_NOTA_MS = 400;
  static final int ESPERA_MAX_S = 120;
  static final long TIMEOUT_VISTA_MS = 60000L;

  private static final Map<String, Leccion> LECCIONES = new LinkedHashMap<String, Leccion>();
  private static final AtomicInteger SECUENCIA = new AtomicInteger();

  private LeccionMotor() {
  }

  // ---------------------------------------------------------------- entorno

  /** Lo que el motor necesita de Spoon. */
  interface Entorno {
    <T> T ui( Callable<T> c ) throws Exception;

    UndoGrupo grupo( TransMeta tm );

    /** La transformacion sigue abierta (por identidad). (UI) */
    boolean abierta( TransMeta tm );

    /** Trae la transformacion al frente. (UI) */
    void alFrente( TransMeta tm ) throws ToolError;

    /** Marca el documento como cambiado y redibuja. (UI) */
    void tocado( TransMeta tm ) throws ToolError;

    /** Un Ctrl+Z (con el encadenado de UndoGrupo). (UI) */
    void deshacer( TransMeta tm );

    IMetaStore metaStore();

    Repository repositorio();

    int pausaNotaMs();
  }

  /** Spoon real. */
  static final class EnSpoon implements Entorno {
    @Override
    public <T> T ui( Callable<T> c ) throws Exception {
      return Docs.ui( c );
    }

    @Override
    public UndoGrupo grupo( TransMeta tm ) {
      try {
        return new UndoGrupo( Docs.spoon(), tm );
      } catch ( ToolError e ) {
        return UndoGrupo.sinSpoon( tm );
      }
    }

    @Override
    public boolean abierta( TransMeta tm ) {
      try {
        for ( AbstractMeta m : Docs.abiertos() ) {
          if ( m == tm ) {
            return true;
          }
        }
      } catch ( ToolError e ) {
        return false;
      }
      return false;
    }

    @Override
    public void alFrente( TransMeta tm ) throws ToolError {
      Docs.activar( tm );
    }

    @Override
    public void tocado( TransMeta tm ) throws ToolError {
      Docs.touched( tm );
    }

    @Override
    public void deshacer( TransMeta tm ) {
      try {
        Docs.spoon().undoAction( tm );
      } catch ( ToolError e ) {
        deshacerSinSpoon( tm );
      }
    }

    @Override
    public IMetaStore metaStore() {
      try {
        return Docs.spoon().getMetaStore();
      } catch ( ToolError e ) {
        return null;
      }
    }

    @Override
    public Repository repositorio() {
      try {
        return Docs.spoon().getRepository();
      } catch ( ToolError e ) {
        return null;
      }
    }

    @Override
    public int pausaNotaMs() {
      return PAUSA_NOTA_MS;
    }
  }

  /** Sin Spoon (pruebas): todo en el hilo que llama, undo simulado como Spoon. */
  static final class SinSpoon implements Entorno {
    volatile boolean cerrada;
    volatile int pausa;

    SinSpoon( int pausaMs ) {
      this.pausa = pausaMs;
    }

    @Override
    public synchronized <T> T ui( Callable<T> c ) throws Exception {
      return c.call(); // un "hilo de UI": de a uno
    }

    @Override
    public UndoGrupo grupo( TransMeta tm ) {
      return UndoGrupo.sinSpoon( tm );
    }

    @Override
    public boolean abierta( TransMeta tm ) {
      return !cerrada;
    }

    @Override
    public void alFrente( TransMeta tm ) {
    }

    @Override
    public void tocado( TransMeta tm ) {
    }

    @Override
    public void deshacer( TransMeta tm ) {
      deshacerSinSpoon( tm );
    }

    @Override
    public IMetaStore metaStore() {
      return null;
    }

    @Override
    public Repository repositorio() {
      return null;
    }

    @Override
    public int pausaNotaMs() {
      return pausa;
    }
  }

  /**
   * Un Ctrl+Z como SpoonTransformationDelegate.undoTransformationAction, para los tipos que usa la leccion (y
   * build_steps), siguiendo el encadenado de UndoGrupo (getNextAlso). Sin Spoon. (UI)
   */
  static void deshacerSinSpoon( TransMeta tm ) {
    boolean seguir = true;
    while ( seguir ) {
      TransAction ta = tm.previousUndo();
      if ( ta == null ) {
        return;
      }
      Object[] prev = ta.getPrevious();
      Object[] cur = ta.getCurrent();
      int[] idx = ta.getCurrentIndex();
      switch ( ta.getType() ) {
        case TransAction.TYPE_ACTION_NEW_STEP:
          for ( int i = idx.length - 1; i >= 0; i-- ) {
            tm.removeStep( idx[ i ] );
          }
          break;
        case TransAction.TYPE_ACTION_NEW_HOP:
          for ( int i = idx.length - 1; i >= 0; i-- ) {
            tm.removeTransHop( idx[ i ] );
          }
          break;
        case TransAction.TYPE_ACTION_NEW_NOTE:
          for ( int i = idx.length - 1; i >= 0; i-- ) {
            tm.removeNote( idx[ i ] );
          }
          break;
        case TransAction.TYPE_ACTION_DELETE_NOTE:
          for ( int i = 0; i < idx.length; i++ ) {
            tm.addNote( idx[ i ], (NotePadMeta) cur[ i ] );
          }
          break;
        case TransAction.TYPE_ACTION_CHANGE_STEP:
          for ( int i = 0; i < idx.length; i++ ) {
            StepMeta antes = (StepMeta) ( (StepMeta) prev[ i ] ).clone();
            tm.getStep( idx[ i ] ).replaceMeta( antes );
          }
          break;
        case TransAction.TYPE_ACTION_CHANGE_NOTE:
          for ( int i = 0; i < idx.length; i++ ) {
            tm.removeNote( idx[ i ] );
            tm.addNote( idx[ i ], (NotePadMeta) ( (NotePadMeta) prev[ i ] ).clone() );
          }
          break;
        case TransAction.TYPE_ACTION_POSITION_STEP:
          for ( int i = 0; i < idx.length; i++ ) {
            Point p = ta.getPreviousLocation()[ i ];
            tm.getStep( idx[ i ] ).setLocation( p.x, p.y );
          }
          break;
        case TransAction.TYPE_ACTION_POSITION_NOTE:
          for ( int i = 0; i < idx.length; i++ ) {
            Point p = ta.getPreviousLocation()[ i ];
            tm.getNote( idx[ i ] ).setLocation( p.x, p.y );
          }
          break;
        default:
          throw new IllegalStateException( "undo type not simulated: " + ta.getType() );
      }
      seguir = ta.getNextAlso();
    }
    tm.clearCaches();
  }

  // ---------------------------------------------------------------- registro de lecciones

  /** La leccion pedida: por id, o la ultima de ese documento, o la ultima. */
  static Leccion buscar( String id, TransMeta tm ) throws ToolError {
    synchronized ( LECCIONES ) {
      if ( id != null && !id.trim().isEmpty() ) {
        for ( Leccion l : LECCIONES.values() ) {
          if ( l.id.equalsIgnoreCase( id.trim() ) ) {
            return l;
          }
        }
        throw new ToolError( "No lesson '" + id + "'. Lessons: " + ids() );
      }
      Leccion r = null;
      for ( Leccion l : LECCIONES.values() ) {
        if ( tm == null || l.tm == tm ) {
          r = l;
        }
      }
      if ( r == null ) {
        throw new ToolError( "No lesson" + ( tm == null ? "" : " on '" + tm.getName() + "'" )
          + ": start one with lesson_start" );
      }
      return r;
    }
  }

  private static String ids() {
    List<String> r = new ArrayList<String>();
    for ( Leccion l : LECCIONES.values() ) {
      r.add( l.id + " (" + l.estado.json + ")" );
    }
    return r.isEmpty() ? "none" : String.join( ", ", r );
  }

  // ---------------------------------------------------------------- lesson_start

  /**
   * Valida, planifica y aplica el paso 0. Si algo esta mal, ToolError sin tocar nada. Devuelve la leccion ya
   * registrada (y su hilo en marcha en guided/auto).
   */
  static Leccion empezar( final Entorno ent, final JsonNode a, final TransMeta tm ) throws Exception {
    final List<String> problemas = new ArrayList<String>();
    final Leccion l = Leccion.leer( a, problemas );
    ent.ui( () -> {
      synchronized ( LECCIONES ) {
        for ( Leccion o : LECCIONES.values() ) {
          synchronized ( o ) {
            if ( o.tm == tm && o.estado != Estado.STOPPED && o.estado != Estado.DONE ) {
              problemas.add( "Lesson " + o.id + " is still " + o.estado.json + " on '" + tm.getName()
                + "': lesson_control action=stop first" );
            }
          }
        }
      }
      problemas.addAll( l.validar( tm, ent.metaStore() ) ); // todo junto, aunque el guion ya tenga errores de forma
      if ( !problemas.isEmpty() ) {
        throw new ToolError( "lesson_start: nothing was changed. Fix these problems and call again:\n- "
          + String.join( "\n- ", problemas ) );
      }
      LeccionNotas.MedidorCerrable m = LeccionNotas.medidor( l.fuente );
      try {
        l.planificar( tm, m, ent.metaStore() );
      } finally {
        m.cerrar();
      }
      l.tm = tm;
      l.id = "L" + SECUENCIA.incrementAndGet();
      l.docId = Docs.id( tm );
      ent.alFrente( tm );
      pasoCero( ent, l );
      ent.tocado( tm );
      return null;
    } );
    synchronized ( LECCIONES ) {
      for ( java.util.Iterator<Leccion> it = LECCIONES.values().iterator(); it.hasNext(); ) {
        Leccion o = it.next();
        if ( o.tm == tm ) { // terminada (done/stopped): la nueva la reemplaza
          synchronized ( o ) {
            o.estado = Estado.STOPPED;
            o.motivo = "replaced";
            o.cambio();
          }
          it.remove();
        }
      }
      LECCIONES.put( l.id, l );
    }
    synchronized ( l ) {
      l.estado = Estado.WAITING;
      l.proximo = System.currentTimeMillis() + l.delayMs;
      l.cambio();
    }
    asegurarHilo( ent, l );
    return l;
  }

  /** En guided/auto, un hilo que espera el clic o el temporizador (si no hay uno ya). */
  static void asegurarHilo( final Entorno ent, final Leccion l ) {
    synchronized ( l ) {
      if ( l.ritmo == Ritmo.MANUAL || l.hiloVivo || l.estado == Estado.STOPPED || l.estado == Estado.DONE ) {
        return;
      }
      l.hiloVivo = true;
    }
    Thread t = new Thread( () -> bucle( ent, l ), "pentaho-live-leccion-" + l.id );
    t.setDaemon( true );
    t.start();
  }

  /** Paso 0: steps que ya estaban a su lugar, notas previas, hops del guion entre steps que ya estaban y titulo. (UI) */
  private static void pasoCero( Entorno ent, Leccion l ) throws Exception {
    TransMeta tm = l.tm;
    int necesarias = 6 * l.pasos.size() + 10;
    if ( tm.getMaxUndo() < necesarias ) {
      tm.setMaxUndo( necesarias );
    }
    UndoGrupo u = ent.grupo( tm );
    TransTools.Cambios previos = TransTools.Cambios.antes( new ArrayList<StepMeta>( tm.getSteps() ) );
    // steps que ya estaban
    List<Object> objs = new ArrayList<Object>();
    List<Integer> idx = new ArrayList<Integer>();
    List<Point> antes = new ArrayList<Point>();
    List<Point> despues = new ArrayList<Point>();
    for ( StepMeta s : tm.getSteps() ) {
      Point np = l.plan.posicion( s.getName() );
      Point a = s.getLocation();
      if ( np == null || ( a != null && a.x == np.x && a.y == np.y ) ) {
        continue;
      }
      objs.add( s );
      idx.add( tm.indexOfStep( s ) );
      antes.add( a == null ? new Point( 0, 0 ) : new Point( a.x, a.y ) );
      despues.add( new Point( np.x, np.y ) );
      s.setLocation( np.x, np.y );
    }
    if ( !objs.isEmpty() ) {
      u.posicion( objs.toArray(), enteros( idx ), antes.toArray( new Point[ 0 ] ), despues.toArray( new Point[ 0 ] ) );
    }
    // notas previas (stack_top)
    if ( l.previas == Previas.APILAR && l.plan.previas.length > 0 ) {
      objs.clear();
      idx.clear();
      antes.clear();
      despues.clear();
      LeccionLayout.Entrada e = LeccionLayout.desde( tm, new LeccionNotas.MedidorAprox( l.fuente ), null );
      for ( int i = 0; i < e.previas.size() && i < l.plan.previas.length; i++ ) {
        NotePadMeta n = (NotePadMeta) e.previas.get( i ).ref;
        Point np = l.plan.previas[ i ];
        Point a = n.getLocation();
        if ( np == null || ( a != null && a.x == np.x && a.y == np.y ) ) {
          continue;
        }
        objs.add( n );
        idx.add( tm.indexOfNote( n ) );
        antes.add( a == null ? new Point( 0, 0 ) : new Point( a.x, a.y ) );
        despues.add( new Point( np.x, np.y ) );
        n.setLocation( np.x, np.y );
      }
      if ( !objs.isEmpty() ) {
        u.posicion( objs.toArray(), enteros( idx ), antes.toArray( new Point[ 0 ] ), despues.toArray( new Point[ 0 ] ) );
        l.avisos.add( objs.size() + " existing note(s) were stacked at the top left: they no longer sit next to the "
          + "step they described (existing_notes=keep leaves them where they are)." );
      }
    }
    // hops del guion entre steps que ya estaban
    List<String> hechos = new ArrayList<String>();
    hopsExtra( l, l.hopsCero, u, hechos );
    TransOps.resolverReferencias( tm );
    previos.registrar( u, tm );
    // registro y titulo
    LeccionNotas.asegurarGrupo( tm, l.idioma );
    tm.setAttribute( LeccionNotas.GRUPO, "id", l.id );
    tm.setAttribute( LeccionNotas.GRUPO, "title", l.titulo );
    tm.setAttribute( LeccionNotas.GRUPO, "level", l.nivel );
    tm.setAttribute( LeccionNotas.GRUPO, LeccionNotas.K_IDIOMA, l.idioma );
    if ( l.fuente > 0 ) {
      tm.setAttribute( LeccionNotas.GRUPO, LeccionNotas.K_FUENTE, Integer.toString( l.fuente ) );
    }
    List<Pedida> titulo = new ArrayList<Pedida>();
    titulo.add( l.pedidaTitulo );
    notas( l, titulo, u, l.huellasCero, hechos );
    l.ultimaCero = u.acciones() > 0 ? tm.viewThisUndo() : null;
    tm.clearCaches();
  }

  /** Crea los hops pedidos (una accion, indices crecientes) y aplica el modo de salida del origen. (UI) */
  private static void hopsExtra( Leccion l, List<TransLote.HopSpec> hops, UndoGrupo u, List<String> hechos )
    throws ToolError {
    if ( hops.isEmpty() ) {
      return;
    }
    final TransMeta tm = l.tm;
    List<TransHopMeta> creados = new ArrayList<TransHopMeta>();
    for ( TransLote.HopSpec h : hops ) {
      StepMeta a = TransOps.paso( tm, h.from );
      StepMeta b = TransOps.paso( tm, h.to );
      TransHopMeta th = TransOps.conectar( tm, a, b, h.enabled, h.error );
      creados.add( th );
      if ( !h.error ) {
        TransOps.aplicarModoSalida( tm, a, l.lote.modoDe( a ) );
      }
      hechos.add( "hop '" + a.getName() + "' -> '" + b.getName() + "' added" );
    }
    Collections.sort( creados, new Comparator<TransHopMeta>() {
      @Override
      public int compare( TransHopMeta x, TransHopMeta y ) {
        return Integer.compare( tm.indexOfTransHop( x ), tm.indexOfTransHop( y ) );
      }
    } );
    Object[] objs = new Object[ creados.size() ];
    int[] idx = new int[ creados.size() ];
    for ( int i = 0; i < objs.length; i++ ) {
      objs[ i ] = creados.get( i );
      idx[ i ] = tm.indexOfTransHop( creados.get( i ) );
    }
    u.nuevo( objs, idx );
  }

  /** Pone en el lienzo las notas pedidas que la regla dejo en el lienzo (una accion NEW_NOTE) y las registra. (UI) */
  private static void notas( Leccion l, List<Pedida> pedidas, UndoGrupo u, List<String> huellas, List<String> hechos ) {
    TransMeta tm = l.tm;
    List<NotePadMeta> nuevas = new ArrayList<NotePadMeta>();
    List<Pedida> de = new ArrayList<Pedida>();
    for ( Pedida p : pedidas ) {
      Ubicada ub = l.plan.nota( p );
      if ( ub == null || !ub.enLienzo() || ub.texto == null ) {
        continue;
      }
      NotePadMeta n = new NotePadMeta( ub.texto, ub.x, ub.y, ub.ancho, ub.alto );
      LeccionNotas.estilizar( n, p.tipo, l.fuente );
      tm.addNote( n );
      nuevas.add( n );
      de.add( p );
    }
    if ( nuevas.isEmpty() ) {
      return;
    }
    Object[] objs = new Object[ nuevas.size() ];
    int[] idx = new int[ nuevas.size() ];
    for ( int i = 0; i < objs.length; i++ ) {
      objs[ i ] = nuevas.get( i );
      idx[ i ] = tm.indexOfNote( nuevas.get( i ) );
    }
    u.nuevo( objs, idx );
    for ( int i = 0; i < nuevas.size(); i++ ) {
      Pedida p = de.get( i );
      LeccionNotas.registrar( tm, nuevas.get( i ), p.tipo, p.step == null ? "" : p.step, p.texto );
      huellas.add( LeccionNotas.huella( nuevas.get( i ).getNote() ) );
      hechos.add( p.tipo == Tipo.TITULO ? "title note added" : p.tipo == Tipo.PASO ? "note " + p.n + " added"
        : p.tipo.json.replace( '_', '-' ) + " note added" );
    }
  }

  // ---------------------------------------------------------------- avanzar

  /**
   * Un paso de la leccion (lesson_next, el clic o el temporizador). Devuelve {position, state, done, preview?}.
   * Con el paso en curso (playing) o la leccion terminada, ToolError.
   */
  static ObjectNode avanzar( final Entorno ent, final Leccion l, boolean conPausa ) throws Exception {
    final Estado previo;
    final Paso p;
    synchronized ( l ) {
      if ( l.estado == Estado.PLAYING ) {
        throw new ToolError( "Lesson " + l.id + " is playing a step right now: wait for it (lesson_status wait_s)" );
      }
      if ( l.estado == Estado.STOPPED || l.estado == Estado.DONE || l.estado == Estado.OUT_OF_SYNC ) {
        throw new ToolError( "Lesson " + l.id + " is " + l.estado.json + ( l.motivo == null ? "" : " (" + l.motivo
          + ")" ) + ": " + ( l.estado == Estado.OUT_OF_SYNC ? "lesson_control action=resume or stop"
          : l.estado == Estado.DONE ? "lesson_control back/restart/goto to replay" : "start a new one" ) );
      }
      previo = l.estado;
      p = l.pasos.get( l.posicion );
      l.estado = Estado.PLAYING;
      l.pausaPedida = previo == Estado.PAUSED;
      l.cambio();
    }
    final List<String> hechos = new ArrayList<String>();
    final UndoGrupo[] u = new UndoGrupo[ 1 ];
    String desfase;
    try {
      desfase = ent.ui( () -> {
        if ( !ent.abierta( l.tm ) ) {
          return "document_closed";
        }
        String d = verificar( l, l.posicion );
        if ( d != null ) {
          return d;
        }
        ent.alFrente( l.tm );
        u[ 0 ] = ent.grupo( l.tm );
        aplicarStep( ent, l, p, u[ 0 ], hechos );
        ent.tocado( l.tm );
        return null;
      } );
    } catch ( Exception e ) {
      synchronized ( l ) {
        l.estado = Estado.PAUSED;
        l.motivo = "error";
        l.avisos.add( "Step " + p.n + " failed: " + TransOps.mensaje( e ) + ". One Ctrl+Z in Spoon removes what it "
          + "created; lesson_control resume re-syncs." );
        l.cambio();
      }
      throw e;
    }
    if ( desfase != null ) {
      synchronized ( l ) {
        if ( "document_closed".equals( desfase ) ) {
          l.estado = Estado.STOPPED;
          l.motivo = "document_closed";
        } else {
          l.estado = Estado.OUT_OF_SYNC;
          l.motivo = desfase;
        }
        l.cambio();
      }
      return estadoJson( l, false );
    }
    int pausa = conPausa ? ent.pausaNotaMs() : 0;
    if ( pausa > 0 ) {
      try {
        Thread.sleep( pausa );
      } catch ( InterruptedException ie ) {
        Thread.currentThread().interrupt();
      }
    }
    final boolean[] cerrada = new boolean[ 1 ];
    ent.ui( () -> {
      if ( !ent.abierta( l.tm ) ) {
        cerrada[ 0 ] = true;
        return null;
      }
      p.huellas.clear();
      notas( l, p.pedidas, u[ 0 ], p.huellas, hechos );
      p.ultima = u[ 0 ].acciones() > 0 ? l.tm.viewThisUndo() : null;
      l.tm.clearCaches();
      ent.tocado( l.tm );
      return null;
    } );
    if ( cerrada[ 0 ] ) {
      synchronized ( l ) {
        l.estado = Estado.STOPPED;
        l.motivo = "document_closed";
        l.cambio();
      }
      return estadoJson( l, false );
    }
    // vistas previas (fuera del hilo de UI)
    ObjectNode vista = null;
    for ( String s : p.vistas ) {
      ObjectNode v = vistaPrevia( ent, l, s, filasDe( l, s ) );
      synchronized ( l ) {
        l.filas.put( s, v );
      }
      vista = v;
      hechos.add( "preview of '" + s + "'" );
    }
    synchronized ( l ) {
      l.posicion++;
      l.motivo = null;
      if ( l.posicion >= l.pasos.size() ) {
        l.estado = Estado.DONE;
      } else {
        l.estado = l.pausaPedida ? Estado.PAUSED : Estado.WAITING;
      }
      l.pausaPedida = false;
      l.proximo = System.currentTimeMillis() + l.delayMs;
      l.cambio();
      ObjectNode o = estadoJson( l, true );
      ArrayNode d = o.putArray( "done" );
      for ( String h : hechos ) {
        d.add( h );
      }
      if ( vista != null ) {
        o.set( "preview", vista );
      }
      return o;
    }
  }

  private static int filasDe( Leccion l, String step ) {
    for ( Paso p : l.pasos ) {
      if ( p.step.equalsIgnoreCase( step ) ) {
        return p.filas;
      }
    }
    return 5;
  }

  /** El step del paso con su hop, los hops que cierran aca, referencias y cambios de los previos. (UI) */
  private static void aplicarStep( Entorno ent, Leccion l, Paso p, UndoGrupo u, List<String> hechos ) throws Exception {
    TransMeta tm = l.tm;
    TransTools.Cambios previos = TransTools.Cambios.antes( new ArrayList<StepMeta>( tm.getSteps() ) );
    StepMeta sm;
    if ( p.crea() ) {
      TransLote.PasoSpec s = p.spec;
      Point pos = l.plan.posicion( s.name );
      StepMeta previo = s.after == null ? null : TransOps.paso( tm, s.after );
      TransOps.Agregado r = TransOps.agregarStep( tm, s.type, s.name, s.config, pos == null ? null : pos.x,
        pos == null ? null : pos.y, s.after, null, ent.metaStore() );
      sm = r.step;
      u.nuevo( new Object[] { r.step }, new int[] { tm.indexOfStep( r.step ) } );
      hechos.add( "step '" + r.step.getName() + "' added" );
      if ( r.hop != null ) {
        u.nuevo( new Object[] { r.hop }, new int[] { tm.indexOfTransHop( r.hop ) } );
        TransOps.aplicarModoSalida( tm, previo, l.lote.modoDe( previo ) );
        hechos.add( "hop '" + previo.getName() + "' -> '" + r.step.getName() + "' added" );
      }
      if ( r.aviso != null ) {
        synchronized ( l ) {
          l.avisos.add( s.name + ": " + r.aviso );
        }
      }
    } else {
      sm = tm.findStep( p.step );
    }
    hopsExtra( l, p.hopsExtra, u, hechos );
    if ( p.crea() && p.spec.modo != null ) {
      TransOps.aplicarModoSalida( tm, sm, p.spec.modo );
    }
    TransOps.resolverReferencias( tm );
    previos.registrar( u, tm );
    tm.unselectAll();
    if ( sm != null ) {
      sm.setSelected( true );
    }
    tm.clearCaches();
  }

  /**
   * Lo hecho hasta 'hasta' sigue en el lienzo? null si si; si no, el motivo (user_undo, user_redo). Mira los steps
   * creados y las notas puestas (por huella). (UI)
   */
  static String verificar( Leccion l, int hasta ) {
    Set<String> huellas = huellas( l.tm );
    for ( String h : l.huellasCero ) {
      if ( !huellas.contains( h ) ) {
        return "user_undo";
      }
    }
    for ( int i = 0; i < hasta && i < l.pasos.size(); i++ ) {
      if ( !intacto( l, l.pasos.get( i ), huellas ) ) {
        return "user_undo";
      }
    }
    if ( hasta < l.pasos.size() ) {
      Paso sig = l.pasos.get( hasta );
      if ( sig.crea() && l.tm.findStep( sig.step ) != null ) {
        return "user_redo";
      }
    }
    return null;
  }

  private static boolean intacto( Leccion l, Paso p, Set<String> huellas ) {
    if ( p.crea() && l.tm.findStep( p.step ) == null ) {
      return false;
    }
    if ( !p.crea() && l.tm.findStep( p.step ) == null ) {
      return false;
    }
    for ( String h : p.huellas ) {
      if ( !huellas.contains( h ) ) {
        return false;
      }
    }
    return true;
  }

  private static Set<String> huellas( TransMeta tm ) {
    Set<String> r = new HashSet<String>();
    for ( int i = 0; i < tm.nrNotes(); i++ ) {
      r.add( LeccionNotas.huella( tm.getNote( i ).getNote() ) );
    }
    return r;
  }

  /** Vista previa de un step sobre una copia (UI para copiar, la corrida afuera). Nunca lanza: errores en el JSON. */
  private static ObjectNode vistaPrevia( Entorno ent, final Leccion l, final String step, final int filas ) {
    try {
      final TransMeta c = ent.ui( () -> {
        TransMeta t = (TransMeta) l.tm.realClone( false );
        t.setRepository( ent.repositorio() );
        t.setMetaStore( ent.metaStore() );
        RunMotor.desligar( t );
        t.setFilename( l.tm.getFilename() );
        t.setInternalKettleVariables();
        return t;
      } );
      return RunMotor.preview( c, step, Math.max( 1, filas ), TIMEOUT_VISTA_MS );
    } catch ( Exception e ) {
      ObjectNode o = Tools.obj().put( "step", step );
      o.putArray( "errors" ).add( TransOps.mensaje( e ) );
      return o;
    }
  }

  // ---------------------------------------------------------------- hilo (guided / auto)

  /** Espera el clic (guided) o el temporizador (auto) y avanza; termina con la leccion stopped o done. */
  private static void bucle( Entorno ent, Leccion l ) {
    while ( true ) {
      synchronized ( l ) {
        while ( true ) {
          if ( l.terminada() || l.estado == Estado.DONE ) {
            l.hiloVivo = false;
            return;
          }
          long ahora = System.currentTimeMillis();
          if ( l.pedido ) {
            l.pedido = false;
            if ( l.estado == Estado.WAITING ) {
              break;
            }
            continue;
          }
          if ( l.ritmo == Ritmo.AUTO && l.estado == Estado.WAITING && ahora >= l.proximo ) {
            break;
          }
          long espera = l.ritmo == Ritmo.AUTO && l.estado == Estado.WAITING ? Math.max( 1, l.proximo - ahora ) : 1000;
          try {
            l.wait( espera );
          } catch ( InterruptedException ie ) {
            l.hiloVivo = false;
            return;
          }
        }
      }
      try {
        avanzar( ent, l, true );
      } catch ( Exception e ) {
        synchronized ( l ) {
          if ( l.estado == Estado.PLAYING ) {
            l.estado = Estado.PAUSED;
            l.motivo = "error";
          }
          l.avisos.add( TransOps.mensaje( e ) );
          l.cambio();
        }
      }
    }
  }

  /**
   * Un clic en «Siguiente ▶» (pestana Leccion, WP4). Cuenta siempre; avanza solo si la leccion espera un clic: los
   * clics de mas mientras corre un paso se descartan. true si el clic hizo avanzar.
   */
  static boolean clic( Leccion l ) {
    synchronized ( l ) {
      l.clics++;
      l.ultimoClic = System.currentTimeMillis();
      boolean sirve = l.estado == Estado.WAITING && !l.pedido && l.ritmo != Ritmo.MANUAL;
      if ( sirve ) {
        l.pedido = true;
      }
      l.cambio();
      return sirve;
    }
  }

  // ---------------------------------------------------------------- lesson_status / lesson_control

  /** Espera hasta wait_s a que cambie algo (o a que la posicion difiera de 'since'). */
  static ObjectNode estado( Entorno ent, Leccion l, int esperaS, Integer desde ) throws Exception {
    revisarAbierta( ent, l );
    boolean cambio;
    synchronized ( l ) {
      long v0 = l.cambios;
      long fin = System.currentTimeMillis() + Math.max( 0, Math.min( ESPERA_MAX_S, esperaS ) ) * 1000L;
      if ( desde != null && desde != l.posicion ) {
        cambio = true;
      } else {
        while ( l.cambios == v0 && System.currentTimeMillis() < fin && l.estado != Estado.STOPPED ) {
          l.wait( Math.max( 1, Math.min( 1000, fin - System.currentTimeMillis() ) ) );
        }
        cambio = l.cambios != v0;
      }
    }
    if ( esperaS > 0 ) {
      revisarAbierta( ent, l );
    }
    synchronized ( l ) {
      return estadoJson( l, cambio );
    }
  }

  private static void revisarAbierta( Entorno ent, final Leccion l ) {
    try {
      boolean abierta = ent.ui( () -> ent.abierta( l.tm ) );
      if ( !abierta ) {
        synchronized ( l ) {
          if ( l.estado != Estado.STOPPED ) {
            l.estado = Estado.STOPPED;
            l.motivo = "document_closed";
            l.cambio();
          }
        }
      }
    } catch ( Exception ignored ) {
      // sin Spoon: se vera en el proximo paso
    }
  }

  /** lesson_control: pause, resume, back, goto, restart, stop. */
  static ObjectNode controlar( Entorno ent, Leccion l, String accion, Integer a ) throws Exception {
    List<String> avisos = new ArrayList<String>();
    switch ( accion ) {
      case "pause":
        synchronized ( l ) {
          if ( l.estado == Estado.PLAYING ) {
            l.pausaPedida = true;
          } else if ( l.estado == Estado.WAITING ) {
            l.estado = Estado.PAUSED;
            l.motivo = "paused";
          } else {
            avisos.add( "Nothing to pause: the lesson is " + l.estado.json + "." );
          }
          l.cambio();
        }
        break;
      case "resume":
        if ( esOutOfSync( l ) ) {
          resincronizar( ent, l, avisos );
        } else {
          synchronized ( l ) {
            if ( l.estado == Estado.PAUSED ) {
              l.estado = Estado.WAITING;
              l.motivo = null;
              l.proximo = System.currentTimeMillis() + l.delayMs;
            } else if ( l.estado == Estado.PLAYING ) {
              l.pausaPedida = false;
            } else {
              avisos.add( "Nothing to resume: the lesson is " + l.estado.json + "." );
            }
            l.cambio();
          }
        }
        break;
      case "back":
        atras( ent, l, avisos );
        break;
      case "restart":
        while ( true ) {
          synchronized ( l ) {
            if ( l.posicion == 0 ) {
              break;
            }
          }
          if ( !atras( ent, l, avisos ) ) {
            break;
          }
        }
        break;
      case "goto": {
        if ( a == null ) {
          throw new ToolError( "goto needs 'to' (0 = before the first step, " + l.pasos.size() + " = the end)" );
        }
        if ( a < 0 || a > l.pasos.size() ) {
          throw new ToolError( "'to' must be between 0 and " + l.pasos.size() );
        }
        while ( true ) {
          int pos;
          synchronized ( l ) {
            pos = l.posicion;
          }
          if ( pos == a ) {
            break;
          }
          if ( pos > a ) {
            if ( !atras( ent, l, avisos ) ) {
              break;
            }
          } else {
            ObjectNode r = avanzar( ent, l, false );
            if ( !Estado.WAITING.json.equals( r.path( "state" ).asText() )
              && !Estado.DONE.json.equals( r.path( "state" ).asText() )
              && !Estado.PAUSED.json.equals( r.path( "state" ).asText() ) ) {
              break;
            }
          }
        }
        break;
      }
      case "stop":
        synchronized ( l ) {
          l.estado = Estado.STOPPED;
          l.motivo = "stopped";
          l.cambio();
        }
        avisos.add( "The notes stay on the canvas: lesson_notes hide/remove cleans them up (one Ctrl+Z each)." );
        break;
      default:
        throw new ToolError( "Unknown action '" + accion + "': use pause, resume, back, goto, restart or stop" );
    }
    asegurarHilo( ent, l );
    synchronized ( l ) {
      ObjectNode o = estadoJson( l, true );
      if ( !avisos.isEmpty() ) {
        ArrayNode w = o.has( "warnings" ) ? (ArrayNode) o.get( "warnings" ) : o.putArray( "warnings" );
        for ( String s : avisos ) {
          w.add( s );
        }
      }
      return o;
    }
  }

  private static boolean esOutOfSync( Leccion l ) {
    synchronized ( l ) {
      return l.estado == Estado.OUT_OF_SYNC || ( l.estado == Estado.PAUSED && "error".equals( l.motivo ) );
    }
  }

  /** Retoma desde lo que hay: la posicion es la cantidad de pasos seguidos que siguen intactos. */
  private static void resincronizar( Entorno ent, final Leccion l, List<String> avisos ) throws Exception {
    final int[] r = ent.ui( () -> {
      Set<String> hs = huellas( l.tm );
      int k = 0; // pasos seguidos que se jugaron y siguen en el lienzo
      while ( k < l.pasos.size() ) {
        Paso p = l.pasos.get( k );
        if ( ( p.ultima == null && p.huellas.isEmpty() ) || !intacto( l, p, hs ) ) {
          break;
        }
        k++;
      }
      boolean siguienteEsta = k < l.pasos.size() && l.pasos.get( k ).crea() && l.tm.findStep( l.pasos.get( k ).step )
        != null;
      boolean cero = true;
      for ( String h : l.huellasCero ) {
        cero &= hs.contains( h );
      }
      return new int[] { k, siguienteEsta ? 1 : 0, cero ? 1 : 0 };
    } );
    synchronized ( l ) {
      l.posicion = r[ 0 ];
      if ( r[ 1 ] == 1 ) {
        l.estado = Estado.OUT_OF_SYNC;
        l.motivo = "user_redo";
        avisos.add( "Step '" + l.pasos.get( l.posicion ).step + "' is on the canvas but its lesson step is not: Ctrl+Z "
          + "or delete it in Spoon, then resume again." );
      } else {
        l.estado = l.posicion >= l.pasos.size() ? Estado.DONE : Estado.WAITING;
        l.motivo = null;
        l.proximo = System.currentTimeMillis() + l.delayMs;
        avisos.add( "Re-synced at step " + l.posicion + " of " + l.pasos.size() + "." );
        if ( r[ 2 ] == 0 ) {
          avisos.add( "The lesson title note is not on the canvas any more." );
          l.huellasCero.clear(); // no vuelve a frenar la leccion
        }
      }
      l.cambio();
    }
  }

  /** «Atras»: un Ctrl+Z si lo ultimo del historial sigue siendo el paso anterior. false si no se pudo. */
  private static boolean atras( Entorno ent, final Leccion l, List<String> avisos ) throws Exception {
    final Paso p;
    synchronized ( l ) {
      if ( l.estado == Estado.PLAYING ) {
        avisos.add( "A step is playing: try again when it ends." );
        return false;
      }
      if ( l.estado == Estado.STOPPED ) {
        avisos.add( "The lesson is stopped." );
        return false;
      }
      if ( l.posicion == 0 ) {
        avisos.add( "Already at the start (step 0)." );
        return false;
      }
      p = l.pasos.get( l.posicion - 1 );
    }
    String r = ent.ui( () -> {
      if ( !ent.abierta( l.tm ) ) {
        return "document_closed";
      }
      if ( p.ultima == null ) {
        return null; // el paso no cambio nada en el lienzo
      }
      if ( l.tm.viewThisUndo() != p.ultima ) {
        return "not_top";
      }
      ent.deshacer( l.tm );
      ent.tocado( l.tm );
      return null;
    } );
    synchronized ( l ) {
      if ( "document_closed".equals( r ) ) {
        l.estado = Estado.STOPPED;
        l.motivo = "document_closed";
        l.cambio();
        return false;
      }
      if ( "not_top".equals( r ) ) {
        avisos.add( "The last change in Spoon's history is not lesson step " + p.n + ": nothing was undone (Ctrl+Z it "
          + "yourself, then lesson_control resume)." );
        return false;
      }
      l.posicion--;
      if ( l.estado == Estado.DONE || l.estado == Estado.OUT_OF_SYNC ) {
        l.estado = Estado.WAITING;
      }
      l.motivo = null;
      l.proximo = System.currentTimeMillis() + l.delayMs;
      synchronized ( l.filas ) {
        for ( String s : p.vistas ) {
          l.filas.remove( s );
        }
      }
      l.cambio();
      return true;
    }
  }

  // ---------------------------------------------------------------- JSON

  /** El estado para la IA. (con el monitor) */
  static ObjectNode estadoJson( Leccion l, boolean cambio ) {
    ObjectNode o = Tools.obj();
    o.put( "lesson", l.id );
    o.put( "document", l.docId );
    o.put( "title", l.titulo );
    o.put( "state", l.estado.json );
    if ( l.motivo != null ) {
      o.put( "reason", l.motivo );
    }
    o.put( "position", l.posicion );
    o.put( "beats", l.pasos.size() );
    o.put( "pace", l.ritmo.json );
    if ( l.posicion > 0 ) {
      Paso p = l.pasos.get( l.posicion - 1 );
      o.putObject( "current" ).put( "n", p.n ).put( "step", p.step ).put( "note", p.textoNota );
    }
    if ( l.posicion < l.pasos.size() ) {
      Paso p = l.pasos.get( l.posicion );
      o.putObject( "upcoming" ).put( "n", p.n ).put( "step", p.step );
    }
    ObjectNode c = o.putObject( "clicks" ).put( "count", l.clics );
    if ( l.ultimoClic > 0 ) {
      c.put( "last_at", new java.text.SimpleDateFormat( "yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT ).format(
        new java.util.Date( l.ultimoClic ) ) );
    }
    o.put( "changed", cambio );
    if ( l.estado == Estado.DONE && l.outro != null ) {
      o.put( "outro", l.outro );
    }
    if ( !l.avisos.isEmpty() ) {
      ArrayNode w = o.putArray( "warnings" );
      for ( String s : l.avisos ) {
        w.add( s );
      }
    }
    return o;
  }

  /** La respuesta de lesson_start. */
  static ObjectNode inicioJson( Leccion l ) {
    synchronized ( l ) {
      ObjectNode o = estadoJson( l, true );
      o.set( "plan", l.planJson() );
      o.put( "undo", "Each lesson step is one Ctrl+Z in Spoon (step 0, the title and the arrangement, too)." );
      o.put( "next", l.ritmo == Ritmo.MANUAL ? "Call lesson_next for each step and comment its 'done' and 'preview'."
        : l.ritmo == Ritmo.AUTO ? "The lesson plays every " + l.delayMs + " ms; lesson_status wait_s=60 follows it, "
          + "lesson_control pauses it."
        : "The user clicks 'Next' in the Lesson tab (or call lesson_next); lesson_status wait_s=60 follows it." );
      return o;
    }
  }

  private static int[] enteros( List<Integer> l ) {
    int[] r = new int[ l.size() ];
    for ( int i = 0; i < r.length; i++ ) {
      r[ i ] = l.get( i );
    }
    return r;
  }

  /** Para las pruebas: olvida las lecciones (y frena sus hilos). */
  static void olvidarTodas() {
    synchronized ( LECCIONES ) {
      for ( Leccion l : LECCIONES.values() ) {
        synchronized ( l ) {
          l.estado = Estado.STOPPED;
          l.cambio();
        }
      }
      LECCIONES.clear();
    }
  }

  /** La respuesta de un Spoon real (para LessonTools). */
  static Entorno spoon() {
    return new EnSpoon();
  }

  static Spoon spoonONulo() {
    try {
      return Docs.spoon();
    } catch ( ToolError e ) {
      return null;
    }
  }
}
