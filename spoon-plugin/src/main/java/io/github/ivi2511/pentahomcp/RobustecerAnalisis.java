package io.github.ivi2511.pentahomcp;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.core.NotePadMeta;
import org.pentaho.di.core.gui.Point;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.row.value.ValueMetaString;
import org.pentaho.di.core.xml.XMLHandler;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepErrorMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.w3c.dom.Element;

import io.github.ivi2511.pentahomcp.lint.Nodos;
import io.github.ivi2511.pentahomcp.lint.reglas.ReglasRobustez;
import io.github.ivi2511.pentahomcp.robustez.Cambio;
import io.github.ivi2511.pentahomcp.robustez.Propuesta;
import io.github.ivi2511.pentahomcp.robustez.Receta;

/**
 * harden_etl, paso 1 (hilo de trabajo, sobre una COPIA del documento): detecta qué recetas aplican
 * (docs/robustez-diseno.md §2) y arma el plan, con todos los cambios por nombre. No toca el documento vivo: eso lo hace
 * {@link RobustecerAplicar} en el hilo de UI. Los campos de cada step (getPrevStepFields) se calculan aquí.
 *
 * Las colas de rechazo (Limpiar motivo → Escribir rechazos) se agrupan por formato de fila: Kettle exige el mismo
 * layout en las filas que llegan a un step, así que cada layout distinto tiene su cola. Dos propuestas que usan la misma
 * cola llevan los mismos cambios de la cola (misma firma): al aplicar se crean una vez.
 */
final class RobustecerAnalisis {

  /** Atributos de Kettle de la robustez (los mismos que Declaraciones del paquete robustez). */
  static final String GRUPO = "pentaho-live-robust";
  static final String DROPS = "drops";
  static final String REASON = "reason";
  static final String ADDED_BY = "added_by";

  static final String MOTIVO = "motivo";
  static final String CAMPOS_ERROR = "campos_error";
  static final String RECHAZOS_DEFECTO = "${Internal.Entry.Current.Directory}/rechazos/rechazos_{flow}.csv";

  static final Set<String> CARGAS = new HashSet<String>( Arrays.asList( "TableOutput", "InsertUpdate", "Update",
    "Delete", "SynchronizeAfterMerge", "DimensionLookup", "CombinationLookup" ) );
  static final Set<String> ESCRIBEN_TABLA = new HashSet<String>( Arrays.asList( "TableOutput", "InsertUpdate",
    "Update", "Delete", "SynchronizeAfterMerge", "DimensionLookup", "CombinationLookup" ) );
  static final Set<String> ENTRADAS_ARCHIVO = new HashSet<String>( Arrays.asList( "CsvInput", "TextFileInput" ) );
  static final Set<String> QUITAN_REPETIDOS = new HashSet<String>( Arrays.asList( "Unique", "UniqueRows", "UniqueRowsByHashSet",
    "GroupBy", "MemoryGroupBy" ) );
  static final Set<String> AGRUPAN = new HashSet<String>( Arrays.asList( "GroupBy", "MemoryGroupBy" ) );
  static final Set<String> CONVERTIDOS = new HashSet<String>( Arrays.asList( "Date", "Number", "Integer", "BigNumber",
    "Timestamp", "Boolean" ) );
  /** Mandan filas a un step por nombre (send_true_to, Switch/case): un hop suyo no se puede mover sin reconfigurarlos. */
  static final Set<String> MANDAN_POR_NOMBRE = new HashSet<String>( Arrays.asList( "FilterRows", "SwitchCase",
    "JavaFilter" ) );
  static final Pattern IDENTIFICADOR = Pattern.compile( "[A-Za-z_][A-Za-z0-9_]*" );
  static final Pattern STAGING = Pattern.compile( "(?i).*(^|[._])(stg|tmp|temp|staging)([._]|$).*|(?i)(stg|tmp)_.*" );
  static final Pattern VARIABLE_PROPIA = Pattern.compile( "\\$\\{(?!Internal\\.)[^}]+\\}|%%[^%]+%%" );

  /** Los argumentos de harden_etl, validados antes de tocar Spoon. */
  static final class Pedido {
    /** null = las auto. */
    Set<String> recetas;
    boolean todas;
    Set<String> steps;
    String rechazos = RECHAZOS_DEFECTO;
    Map<String, List<String>> obligatorios = new HashMap<String, List<String>>();
    Map<String, List<String>> claves = new HashMap<String, List<String>>();
    Set<String> confirmar = new HashSet<String>();
    boolean confirmarTodo;
    boolean ensayo = true;
    /** null = el idioma del documento (sus nombres de steps y notas); si no se nota, en. */
    String lang;

    boolean pideReceta( String id ) {
      if ( todas ) {
        return true;
      }
      if ( recetas == null ) {
        Receta r = Receta.de( id );
        return r != null && Receta.AUTO.equals( r.nivel );
      }
      return recetas.contains( id );
    }
  }

  /** El plan: todas las propuestas detectadas (la elección es aparte) y lo que se descartó con su motivo. */
  static final class Plan {
    final List<Propuesta> propuestas = new ArrayList<Propuesta>();
    /** {receta, step, motivo}. */
    final List<String[]> descartadas = new ArrayList<String[]>();
  }

  /** Una cola de rechazo: un formato de fila, un archivo. */
  private static final class Cola {
    String limpiar;
    String escribir;
    final List<Cambio> cambios = new ArrayList<Cambio>();
    /** Los steps que le mandan filas (por un hop de error o normal), en el orden en que se planearon. */
    final List<String> fuentes = new ArrayList<String>();
    Lugar lugar;
    Cambio cambioLimpiar;
    Cambio cambioEscribir;
  }

  private final TransMeta tm;
  private final Pedido p;
  private final Plan plan = new Plan();
  private final Map<StepMeta, Element> xmls = new HashMap<StepMeta, Element>();
  private final Set<StepMeta> rechazo = new HashSet<StepMeta>();
  private final Map<String, Cola> colas = new LinkedHashMap<String, Cola>();
  private final Map<String, Integer> flujosUsados = new HashMap<String, Integer>();
  private final Set<String> tomados = new HashSet<String>();
  private final List<int[]> ocupados = new ArrayList<int[]>();
  /** Lo que ya ocupa el lienzo: cajas {x1, y1, x2, y2} (steps con su rótulo, notas, steps nuevos de las colas). */
  private final List<int[]> cajas = new ArrayList<int[]>();
  /** Las cajas de los steps nuevos de las colas (los demás steps nuevos las esquivan). */
  private final List<int[]> cajasCola = new ArrayList<int[]>();
  /** Los hops del lienzo: segmentos {x1, y1, x2, y2} de centro a centro. */
  private final List<int[]> segmentos = new ArrayList<int[]>();
  /** Las cajas de las notas (las demás cajas son de steps: ícono y rótulo). */
  private final List<int[]> notas = new ArrayList<int[]>();
  /** Las cajas de los demás steps nuevos del plan (filtros, motivos...): los hops de las colas no las atraviesan. */
  private final List<int[]> cajasNuevas = new ArrayList<int[]>();
  /** Los steps nuevos del plan que no son de una cola: {posición, caja} por nombre (pueden ser fuentes de una cola). */
  private final Map<String, int[][]> posNuevos = new HashMap<String, int[][]>();
  private final String lang;

  private RobustecerAnalisis( TransMeta tm, Pedido p ) {
    this.tm = tm;
    this.p = p;
    String d = p.lang != null ? p.lang : idiomaDe( tm );
    this.lang = d == null ? "en" : d;
    for ( StepMeta s : tm.getSteps() ) {
      tomados.add( s.getName().toLowerCase( Locale.ROOT ) );
      ocupados.add( new int[] { s.getLocation().x, s.getLocation().y } );
      cajas.add( caja( s.getLocation().x, s.getLocation().y, s.getName() ) );
    }
    for ( NotePadMeta n : tm.getNotes() ) {
      int[] kn = cajaNota( n );
      cajas.add( kn );
      notas.add( kn );
    }
    for ( TransHopMeta h : tm.getTransHops() ) {
      if ( h.getFromStep() != null && h.getToStep() != null ) {
        segmentos.add( new int[] { h.getFromStep().getLocation().x + ICONO / 2, h.getFromStep().getLocation().y
          + ICONO / 2, h.getToStep().getLocation().x + ICONO / 2, h.getToStep().getLocation().y + ICONO / 2 } );
      }
    }
  }

  static Plan analizar( TransMeta tm, Pedido p ) {
    RobustecerAnalisis a = new RobustecerAnalisis( tm, p );
    a.marcarRechazos();
    a.requeridos();
    a.parametros();
    a.conversiones();
    a.erroresACola();
    a.motivos();
    a.repetidos();
    a.esperas();
    a.manuales();
    a.recolocarColas();
    final Pedido pp = p;
    Collections.sort( a.plan.propuestas, new Comparator<Propuesta>() {
      @Override
      public int compare( Propuesta x, Propuesta y ) {
        return Receta.orden( x.receta ) - Receta.orden( y.receta );
      }
    } );
    if ( pp.steps != null ) {
      List<Propuesta> l = new ArrayList<Propuesta>();
      for ( Propuesta x : a.plan.propuestas ) {
        if ( contieneSinMayusculas( pp.steps, x.step ) ) {
          l.add( x );
        }
      }
      a.plan.propuestas.clear();
      a.plan.propuestas.addAll( l );
    }
    return a.plan;
  }

  /** Las que se muestran: su receta está pedida o su key viene en confirm. */
  static List<Propuesta> visibles( Plan plan, Pedido p ) {
    List<Propuesta> l = new ArrayList<Propuesta>();
    for ( Propuesta x : plan.propuestas ) {
      if ( p.pideReceta( x.receta ) || p.confirmar.contains( x.key ) ) {
        l.add( x );
      }
    }
    return l;
  }

  /** Las que se aplican: auto pedidas, y confirm con su key (o confirm=all). Las manuales nunca. */
  static List<Propuesta> elegir( Plan plan, Pedido p ) {
    List<Propuesta> l = new ArrayList<Propuesta>();
    for ( Propuesta x : visibles( plan, p ) ) {
      if ( !x.aplicable() ) {
        continue;
      }
      if ( Receta.AUTO.equals( x.nivel ) || p.confirmarTodo || p.confirmar.contains( x.key ) ) {
        l.add( x );
      }
    }
    return l;
  }

  // ---------------------------------------------------------------- grafo

  private Element xml( StepMeta s ) {
    if ( xmls.containsKey( s ) ) {
      return xmls.get( s );
    }
    Element e = null;
    try {
      e = Nodos.parse( s.getXML() );
    } catch ( Exception x ) {
      McpLifecycleListener.LOG.logDebug( "harden_etl: no se pudo leer " + s.getName() + ": " + x );
    }
    xmls.put( s, e );
    return e;
  }

  private static String textoXml( StepMeta s ) {
    try {
      return s.getXML();
    } catch ( Exception x ) {
      return "";
    }
  }

  private String txt( StepMeta s, String tag ) {
    return Nodos.txt( xml( s ), tag );
  }

  private List<TransHopMeta> entrantes( StepMeta s ) {
    List<TransHopMeta> l = new ArrayList<TransHopMeta>();
    for ( TransHopMeta h : tm.getTransHops() ) {
      if ( h.isEnabled() && h.getToStep() == s && h.getFromStep() != null ) {
        l.add( h );
      }
    }
    return l;
  }

  private List<TransHopMeta> salidas( StepMeta s ) {
    List<TransHopMeta> l = new ArrayList<TransHopMeta>();
    for ( TransHopMeta h : tm.getTransHops() ) {
      if ( h.isEnabled() && h.getFromStep() == s && h.getToStep() != null && !h.isErrorHop() ) {
        l.add( h );
      }
    }
    return l;
  }

  /** Aguas abajo por hops habilitados (sin los hops de error). */
  private Set<StepMeta> abajo( StepMeta s ) {
    Set<StepMeta> vistos = new LinkedHashSet<StepMeta>();
    Deque<StepMeta> cola = new ArrayDeque<StepMeta>();
    cola.add( s );
    while ( !cola.isEmpty() ) {
      StepMeta x = cola.poll();
      for ( TransHopMeta h : salidas( x ) ) {
        if ( vistos.add( h.getToStep() ) ) {
          cola.add( h.getToStep() );
        }
      }
    }
    return vistos;
  }

  /** Aguas arriba por todos los hops habilitados (también los de información). */
  private Set<StepMeta> arriba( StepMeta s ) {
    Set<StepMeta> vistos = new LinkedHashSet<StepMeta>();
    Deque<StepMeta> cola = new ArrayDeque<StepMeta>();
    cola.add( s );
    while ( !cola.isEmpty() ) {
      StepMeta x = cola.poll();
      for ( TransHopMeta h : entrantes( x ) ) {
        if ( vistos.add( h.getFromStep() ) ) {
          cola.add( h.getFromStep() );
        }
      }
    }
    return vistos;
  }

  /** La entrada (step sin entradas) de su flujo principal, o el propio step. */
  private StepMeta entradaDe( StepMeta s ) {
    Deque<StepMeta> cola = new ArrayDeque<StepMeta>();
    Set<StepMeta> vistos = new HashSet<StepMeta>();
    cola.add( s );
    StepMeta primera = null;
    while ( !cola.isEmpty() ) {
      StepMeta x = cola.poll();
      List<TransHopMeta> ent = new ArrayList<TransHopMeta>();
      for ( TransHopMeta h : entrantes( x ) ) {
        if ( !h.isErrorHop() ) {
          ent.add( h );
        }
      }
      if ( ent.isEmpty() ) {
        if ( ENTRADAS_ARCHIVO.contains( x.getStepID() ) || "TableInput".equals( x.getStepID() ) ) {
          return x;
        }
        if ( primera == null ) {
          primera = x;
        }
      }
      for ( TransHopMeta h : ent ) {
        if ( vistos.add( h.getFromStep() ) ) {
          cola.add( h.getFromStep() );
        }
      }
    }
    return primera == null ? s : primera;
  }

  /** Aguas abajo de un hop de error o de la rama falsa de un filtro: ya son rechazos. */
  private void marcarRechazos() {
    for ( StepMeta s : tm.getSteps() ) {
      StepErrorMeta em = s.getStepErrorMeta();
      if ( em != null && em.isEnabled() && em.getTargetStep() != null ) {
        rechazo.add( em.getTargetStep() );
        rechazo.addAll( abajo( em.getTargetStep() ) );
      }
      if ( "FilterRows".equals( s.getStepID() ) ) {
        StepMeta f = tm.findStep( txt( s, "send_false_to" ) );
        if ( f != null ) {
          rechazo.add( f );
          rechazo.addAll( abajo( f ) );
        }
      }
    }
  }

  private static boolean propio( StepMeta s ) {
    String a = s.getAttribute( GRUPO, ADDED_BY );
    return a != null && !a.isEmpty();
  }

  private static boolean contieneSinMayusculas( Set<String> set, String v ) {
    for ( String x : set ) {
      if ( x.equalsIgnoreCase( v ) ) {
        return true;
      }
    }
    return false;
  }

  // ---------------------------------------------------------------- nombres y posiciones

  private String t( String en, String es, String pt ) {
    return "es".equals( lang ) ? es : "pt".equals( lang ) ? pt : en;
  }

  private String unico( String nombre ) {
    String n = LintPropuestas.unico( nombre, tomados );
    tomados.add( n.toLowerCase( Locale.ROOT ) );
    return n;
  }

  /** Un lugar libre cerca de (x, y): baja de a 80 px mientras esté ocupado. */
  private int[] libre( int x, int y ) {
    int[] r = new int[] { Math.max( 32, x ), Math.max( 32, y ) };
    for ( int i = 0; i < 40 && ocupado( r ); i++ ) {
      r[ 1 ] += 80;
    }
    ocupados.add( r );
    return r;
  }

  private boolean ocupado( int[] r ) {
    for ( int[] o : ocupados ) {
      if ( Math.abs( o[ 0 ] - r[ 0 ] ) < 90 && Math.abs( o[ 1 ] - r[ 1 ] ) < 60 ) {
        return true;
      }
    }
    int[] k = caja( r[ 0 ], r[ 1 ], null );
    for ( int[] c : cajasCola ) {
      if ( choca( k, c ) ) {
        return true;
      }
    }
    return false;
  }

  // ---------------------------------------------------------------- geometría del lienzo (como lo dibuja Spoon)

  /** Lado del ícono de un step en Spoon. */
  static final int ICONO = 32;
  /** Ancho medio de un carácter del rótulo (la fuente por defecto de Spoon). */
  static final int PX_LETRA = 7;
  private static final int MARGEN = 8;

  /** El ancho del rótulo de un step (centrado bajo el ícono); sin nombre, uno típico. */
  static int anchoRotulo( String nombre ) {
    return nombre == null ? 140 : Math.max( ICONO, nombre.length() * PX_LETRA );
  }

  /** La caja de un step en (x, y): el ícono y su rótulo debajo, con un margen. */
  static int[] caja( int x, int y, String nombre ) {
    int cx = x + ICONO / 2;
    int w = anchoRotulo( nombre );
    return new int[] { cx - w / 2 - MARGEN, y - MARGEN, cx + w / 2 + MARGEN, y + ICONO + 22 + MARGEN };
  }

  private static int[] cajaNota( NotePadMeta n ) {
    int w = n.getWidth();
    int h = n.getHeight();
    if ( w <= 0 || h <= 0 ) {
      String[] lineas = ( n.getNote() == null ? "" : n.getNote() ).split( "\\r?\\n" );
      int largo = 0;
      for ( String l : lineas ) {
        largo = Math.max( largo, l.length() );
      }
      w = Math.max( w, largo * PX_LETRA + 20 );
      h = Math.max( h, lineas.length * 16 + 20 );
    }
    Point q = n.getLocation();
    return new int[] { q.x - MARGEN, q.y - MARGEN, q.x + w + MARGEN, q.y + h + MARGEN };
  }

  static boolean choca( int[] a, int[] b ) {
    return a[ 0 ] < b[ 2 ] && b[ 0 ] < a[ 2 ] && a[ 1 ] < b[ 3 ] && b[ 1 ] < a[ 3 ];
  }

  /** ¿El segmento {x1, y1, x2, y2} toca la caja? (recorte de Liang-Barsky) */
  static boolean cruza( int[] s, int[] k ) {
    double x0 = s[ 0 ];
    double y0 = s[ 1 ];
    double dx = s[ 2 ] - x0;
    double dy = s[ 3 ] - y0;
    double t0 = 0;
    double t1 = 1;
    double[] pp = { -dx, dx, -dy, dy };
    double[] qq = { x0 - k[ 0 ], k[ 2 ] - x0, y0 - k[ 1 ], k[ 3 ] - y0 };
    for ( int i = 0; i < 4; i++ ) {
      if ( pp[ i ] == 0 ) {
        if ( qq[ i ] < 0 ) {
          return false;
        }
      } else {
        double r = qq[ i ] / pp[ i ];
        if ( pp[ i ] < 0 ) {
          t0 = Math.max( t0, r );
        } else {
          t1 = Math.min( t1, r );
        }
        if ( t0 > t1 ) {
          return false;
        }
      }
    }
    return true;
  }

  private boolean libreDeTodo( int[] k ) {
    for ( int[] c : cajas ) {
      if ( choca( k, c ) ) {
        return false;
      }
    }
    for ( int[] o : ocupados ) {
      if ( choca( k, caja( o[ 0 ], o[ 1 ], null ) ) ) {
        return false;
      }
    }
    for ( int[] sg : segmentos ) {
      if ( cruza( sg, k ) ) {
        return false;
      }
    }
    return true;
  }

  /** Dónde quedó una cola: sus dos steps, sus cajas y los hops que se dibujaron por ella (para recolocarla). */
  private static final class Lugar {
    int[] p1;
    int[] p2;
    int[] k1;
    int[] k2;
    final List<int[]> segs = new ArrayList<int[]>();
  }

  /**
   * El lugar de los dos steps de una cola (limpiar → escribir) para sus fuentes (cada una {posición, caja}: los steps
   * que le mandan filas). Un carril debajo de la primera fuente, separados como la grilla de TransLayout y lo justo
   * para que sus rótulos no se pisen, sin tocar steps, notas ni hops, y con sus hops nuevos (cada fuente → limpiar,
   * limpiar → escribir) sin pasar por encima de ningún step ni nota. Si debajo no hay sitio, más abajo o a los lados;
   * si tampoco, el sitio libre de todo el lienzo (debajo de todo incluido) más cerca de las fuentes cuyos hops no
   * crucen nada, y si no lo hay, el que crucen menos cajas.
   */
  private Lugar carril( List<int[][]> fuentes, String limpiar, String escribir ) {
    int ax = fuentes.get( 0 )[ 0 ][ 0 ];
    int ay = fuentes.get( 0 )[ 0 ][ 1 ];
    int dx = Math.max( TransLayout.DX, ( anchoRotulo( limpiar ) + anchoRotulo( escribir ) ) / 2 + 2 * MARGEN + 16 );
    int[] corrimientos = { 0, TransLayout.DX, -TransLayout.DX, 2 * TransLayout.DX, -2 * TransLayout.DX };
    for ( int k = 1; k <= 25; k++ ) {
      int y = ay + k * TransLayout.DY;
      for ( int c : corrimientos ) {
        int x1 = ax + c;
        if ( x1 + ICONO / 2 - anchoRotulo( limpiar ) / 2 < 0 ) {
          continue;
        }
        int[] p1 = { x1, y };
        int[] p2 = { x1 + dx, y };
        int[] k1 = caja( p1[ 0 ], p1[ 1 ], limpiar );
        int[] k2 = caja( p2[ 0 ], p2[ 1 ], escribir );
        if ( libreDeTodo( k1 ) && libreDeTodo( k2 ) && cruces( fuentes, p1, p2, k1, k2 ) == 0 ) {
          return ocupar( p1, p2, k1, k2, fuentes );
        }
      }
    }
    // todo el lienzo, una fila más abajo de todo incluida: sin cruces y lo más cerca posible; si no, menos cruces
    int fondo = ay;
    int derecha = ax;
    for ( int[] c : obstaculos() ) {
      fondo = Math.max( fondo, c[ 3 ] );
      derecha = Math.max( derecha, c[ 2 ] );
    }
    long cx = 0;
    long cy = 0;
    for ( int[][] f : fuentes ) {
      cx += f[ 0 ][ 0 ];
      cy += f[ 0 ][ 1 ];
    }
    cx /= fuentes.size();
    cy /= fuentes.size();
    int[] mejor = null;
    long mejorCosto = Long.MAX_VALUE;
    int pasoX = TransLayout.DX / 2;
    int pasoY = TransLayout.DY / 2;
    for ( int y = 32; y <= fondo + TransLayout.DY / 2; y += pasoY ) {
      for ( int x1 = 32; x1 <= derecha + TransLayout.DX; x1 += pasoX ) {
        if ( x1 + ICONO / 2 - anchoRotulo( limpiar ) / 2 < 0 ) {
          continue;
        }
        int[] p1 = { x1, y };
        int[] p2 = { x1 + dx, y };
        int[] k1 = caja( p1[ 0 ], p1[ 1 ], limpiar );
        int[] k2 = caja( p2[ 0 ], p2[ 1 ], escribir );
        if ( !libreDeTodo( k1 ) || !libreDeTodo( k2 ) ) {
          continue;
        }
        long dist = ( x1 - cx ) * ( x1 - cx ) + ( y - cy ) * ( y - cy );
        long costo = cruces( fuentes, p1, p2, k1, k2 ) * 1000000000000L + dist;
        if ( costo < mejorCosto ) {
          mejorCosto = costo;
          mejor = p1;
        }
      }
    }
    if ( mejor == null ) { // no debería pasar (debajo de todo siempre hay sitio)
      mejor = new int[] { Math.max( 32, ax ), fondo + TransLayout.DY / 2 };
    }
    int[] p2 = { mejor[ 0 ] + dx, mejor[ 1 ] };
    return ocupar( mejor, p2, caja( mejor[ 0 ], mejor[ 1 ], limpiar ), caja( p2[ 0 ], p2[ 1 ], escribir ), fuentes );
  }

  /** Lo que un hop nuevo no debe atravesar: steps con su rótulo, notas, colas y los demás steps nuevos. */
  private List<int[]> obstaculos() {
    List<int[]> l = new ArrayList<int[]>( cajas );
    l.addAll( cajasNuevas );
    return l;
  }

  /**
   * Cuántas cajas (steps con su rótulo, notas, colas, steps nuevos) cruzan los hops nuevos de una cola: de cada fuente
   * a limpiar (p1) y de limpiar a escribir (p2). No cuentan las cajas de los extremos de cada hop.
   */
  private int cruces( List<int[][]> fuentes, int[] p1, int[] p2, int[] k1, int[] k2 ) {
    int[] s2 = { p1[ 0 ] + ICONO / 2, p1[ 1 ] + ICONO / 2, p2[ 0 ] + ICONO / 2, p2[ 1 ] + ICONO / 2 };
    int n = 0;
    for ( int[] c : obstaculos() ) {
      if ( Arrays.equals( c, k1 ) ) {
        continue;
      }
      for ( int[][] f : fuentes ) {
        int[] s1 = { f[ 0 ][ 0 ] + ICONO / 2, f[ 0 ][ 1 ] + ICONO / 2, p1[ 0 ] + ICONO / 2, p1[ 1 ] + ICONO / 2 };
        if ( !Arrays.equals( c, f[ 1 ] ) && atraviesa( s1, c ) ) {
          n++;
        }
      }
      if ( !Arrays.equals( c, k2 ) && atraviesa( s2, c ) ) {
        n++;
      }
    }
    return n;
  }

  /**
   * ¿El hop pasa por encima de lo que se ve de esa caja? Una nota, entera; un step, su ícono o su rótulo (no las
   * esquinas vacías de su caja junto al ícono, por donde un hop pasa sin tocar nada).
   */
  private boolean atraviesa( int[] sg, int[] c ) {
    for ( int[] n : notas ) {
      if ( n == c ) {
        return cruza( sg, c );
      }
    }
    int cx = ( c[ 0 ] + c[ 2 ] ) / 2;
    int y = c[ 1 ] + MARGEN;
    int[] icono = { cx - ICONO / 2 - MARGEN / 2, y - MARGEN / 2, cx + ICONO / 2 + MARGEN / 2, y + ICONO + MARGEN / 2 };
    int[] rotulo = { c[ 0 ], y + ICONO + 2, c[ 2 ], c[ 3 ] };
    return cruza( sg, icono ) || cruza( sg, rotulo );
  }

  private Lugar ocupar( int[] p1, int[] p2, int[] k1, int[] k2, List<int[][]> fuentes ) {
    Lugar l = new Lugar();
    l.p1 = p1;
    l.p2 = p2;
    l.k1 = k1;
    l.k2 = k2;
    cajas.add( k1 );
    cajas.add( k2 );
    cajasCola.add( k1 );
    cajasCola.add( k2 );
    ocupados.add( p1 );
    ocupados.add( p2 );
    for ( int[][] f : fuentes ) {
      l.segs.add( new int[] { f[ 0 ][ 0 ] + ICONO / 2, f[ 0 ][ 1 ] + ICONO / 2, p1[ 0 ] + ICONO / 2, p1[ 1 ] + ICONO
        / 2 } );
    }
    l.segs.add( new int[] { p1[ 0 ] + ICONO / 2, p1[ 1 ] + ICONO / 2, p2[ 0 ] + ICONO / 2, p2[ 1 ] + ICONO / 2 } );
    segmentos.addAll( l.segs );
    return l;
  }

  /** Saca una cola del lienzo (sus cajas, sus lugares y sus hops) para volver a colocarla. */
  private void soltar( Lugar l ) {
    cajas.remove( l.k1 );
    cajas.remove( l.k2 );
    cajasCola.remove( l.k1 );
    cajasCola.remove( l.k2 );
    ocupados.remove( l.p1 );
    ocupados.remove( l.p2 );
    for ( int[] sg : l.segs ) {
      segmentos.remove( sg );
    }
  }

  /** Una fuente de una cola: {posición, caja}; un step del documento o uno nuevo del plan (null si no está). */
  private int[][] fuente( String nombre ) {
    StepMeta s = tm.findStep( nombre );
    if ( s != null ) {
      return new int[][] { { s.getLocation().x, s.getLocation().y }, caja( s.getLocation().x, s.getLocation().y,
        s.getName() ) };
    }
    return posNuevos.get( nombre );
  }

  /** ¿Esta propuesta se aplicará con el pedido? Lo mismo que visibles + elegir + el filtro de steps de analizar. */
  private boolean seAplica( Propuesta x ) {
    if ( !( p.pideReceta( x.receta ) || p.confirmar.contains( x.key ) ) || !x.aplicable() ) {
      return false;
    }
    if ( p.steps != null && !contieneSinMayusculas( p.steps, x.step ) ) {
      return false;
    }
    return Receta.AUTO.equals( x.nivel ) || p.confirmarTodo || p.confirmar.contains( x.key );
  }

  /** El hop de 'de' a la limpieza de la cola, anotando a 'de' como fuente (la cola se coloca para todas). */
  private Cambio alimentar( Cola c, String de, boolean error ) {
    if ( !c.fuentes.contains( de ) ) {
      c.fuentes.add( de );
    }
    return hop( de, c.limpiar, error );
  }

  /**
   * Con el plan entero, cada cola mira los hops de TODAS sus fuentes (varias entradas con el mismo formato de fila, o
   * el motivo de R03/R08, comparten la cola): si alguno pasa por encima de un step, la cola se vuelve a colocar para
   * todas, y sus dos steps nuevos cambian de sitio.
   */
  private void recolocarColas() {
    // las fuentes que de verdad se van a aplicar con este pedido (las de propuestas que no se eligen no dibujan hop)
    Set<String> aplicadas = new HashSet<String>();
    for ( Propuesta x : plan.propuestas ) {
      if ( seAplica( x ) ) {
        for ( Cambio k : x.cambios ) {
          if ( Cambio.ADD_HOP.equals( k.op ) ) {
            aplicadas.add( k.get( "from" ) + "\n" + k.get( "to" ) );
          }
        }
      }
    }
    for ( Cola c : colas.values() ) {
      List<int[][]> fs = new ArrayList<int[][]>();
      for ( String n : c.fuentes ) {
        int[][] f = fuente( n );
        if ( f != null && aplicadas.contains( n + "\n" + c.limpiar ) ) {
          fs.add( f );
        }
      }
      if ( fs.isEmpty() ) { // ninguna se aplica (un plan para mirar): para todas
        for ( String n : c.fuentes ) {
          int[][] f = fuente( n );
          if ( f != null ) {
            fs.add( f );
          }
        }
      }
      if ( fs.isEmpty() || c.lugar == null ) {
        continue;
      }
      Lugar viejo = c.lugar;
      soltar( viejo );
      if ( libreDeTodo( viejo.k1 ) && libreDeTodo( viejo.k2 ) && cruces( fs, viejo.p1, viejo.p2, viejo.k1,
        viejo.k2 ) == 0 ) {
        c.lugar = ocupar( viejo.p1, viejo.p2, viejo.k1, viejo.k2, fs );
      } else {
        c.lugar = carril( fs, c.limpiar, c.escribir );
      }
      c.cambioLimpiar.x = c.lugar.p1[ 0 ];
      c.cambioLimpiar.y = c.lugar.p1[ 1 ];
      c.cambioEscribir.x = c.lugar.p2[ 0 ];
      c.cambioEscribir.y = c.lugar.p2[ 1 ];
    }
  }

  // ---------------------------------------------------------------- el idioma del documento

  private static final Set<String> PALABRAS_ES = new HashSet<String>( Arrays.asList( "leer", "escribir", "filtrar",
    "limpiar", "ordenar", "agrupar", "cargar", "carga", "unir", "insertar", "actualizar", "seleccionar", "datos",
    "ventas", "clientes", "cliente", "productos", "producto", "salida", "tabla", "fichero", "archivo", "fecha", "del",
    "la", "las", "los", "el", "por", "con", "y", "sin", "rechazos", "nuevo", "generar", "quitar", "repetidos",
    "hechos", "dimension", "dimensión", "convertir", "clave", "obligatorios", "filas", "año", "día", "ciudad",
    "nombre", "importe", "sumar", "una", "transformación", "transformacion", "paso", "pasos", "nota" ) );
  private static final Set<String> PALABRAS_PT = new HashSet<String>( Arrays.asList( "ler", "gravar", "limpar",
    "dados", "vendas", "produtos", "arquivo", "tabela", "da", "do", "dos", "das", "e", "não", "nao", "saída",
    "carregar", "juntar", "inserir", "atualizar", "selecionar", "rejeitados", "novo", "gerar", "remover", "fatos",
    "dimensão", "converter", "chave", "obrigatórios", "linhas", "mês", "cidade", "nome", "somar", "uma", "um",
    "transformação", "passo", "em", "no", "na", "ao" ) );
  private static final Set<String> PALABRAS_EN = new HashSet<String>( Arrays.asList( "read", "write", "filter",
    "clean", "sort", "group", "load", "calculate", "join", "lookup", "insert", "update", "select", "values", "data",
    "sales", "customers", "customer", "products", "product", "input", "output", "table", "file", "date", "the", "of",
    "by", "with", "and", "without", "rejects", "reason", "new", "orders", "generate", "remove", "duplicates", "fact",
    "facts", "dimension", "convert", "types", "key", "fields", "required", "rows", "row", "year", "month", "day",
    "text", "city", "name", "amount", "sum", "count", "wait", "for", "to", "from", "is", "add", "get", "set", "step",
    "transformation" ) );

  /**
   * El idioma del documento por sus nombres de steps y sus notas: es, pt o en; null si no se nota (sin texto o en
   * empate). Las palabras propias de cada idioma suman; ñ, «ción», ¿ y ¡ son de español; ã, õ y ç, de portugués.
   */
  static String idiomaDe( TransMeta tm ) {
    StringBuilder b = new StringBuilder();
    for ( StepMeta s : tm.getSteps() ) {
      b.append( s.getName() ).append( '\n' );
    }
    for ( NotePadMeta n : tm.getNotes() ) {
      b.append( n.getNote() ).append( '\n' );
    }
    String texto = b.toString().toLowerCase( Locale.ROOT );
    int es = 0;
    int pt = 0;
    int en = 0;
    for ( String w : texto.split( "[^\\p{L}]+" ) ) {
      if ( w.isEmpty() ) {
        continue;
      }
      boolean e = PALABRAS_ES.contains( w );
      boolean p = PALABRAS_PT.contains( w );
      if ( e && !p ) {
        es += 2;
      } else if ( p && !e ) {
        pt += 2;
      } else if ( e ) {
        es++;
        pt++;
      }
      if ( PALABRAS_EN.contains( w ) ) {
        en += 2;
      }
      if ( w.indexOf( 'ñ' ) >= 0 || w.endsWith( "ción" ) || w.endsWith( "ciones" ) ) {
        es += 2;
      }
      if ( w.indexOf( 'ã' ) >= 0 || w.indexOf( 'õ' ) >= 0 || w.indexOf( 'ç' ) >= 0 ) {
        pt += 2;
      }
    }
    es += 2 * ( contar( texto, '¿' ) + contar( texto, '¡' ) );
    int max = Math.max( es, Math.max( pt, en ) );
    if ( max == 0 ) {
      return null;
    }
    if ( es == max && pt < max && en < max ) {
      return "es";
    }
    if ( pt == max && es < max && en < max ) {
      return "pt";
    }
    if ( en == max && es < max && pt < max ) {
      return "en";
    }
    return null;
  }

  private static int contar( String s, char c ) {
    int n = 0;
    for ( int i = 0; i < s.length(); i++ ) {
      if ( s.charAt( i ) == c ) {
        n++;
      }
    }
    return n;
  }

  /** El nombre del flujo: el archivo que lee la entrada (sin extensión) o su nombre, en minúsculas sin tildes. */
  private String flujo( StepMeta entrada ) {
    String base = null;
    if ( "CsvInput".equals( entrada.getStepID() ) ) {
      base = txt( entrada, "filename" );
    } else if ( "TextFileInput".equals( entrada.getStepID() ) ) {
      base = Nodos.txt( Nodos.hijo( xml( entrada ), "file" ), "name" );
    }
    String n = normalizar( base == null ? "" : quitarRuta( base ) );
    if ( n.isEmpty() ) {
      n = normalizar( entrada.getName() );
    }
    return n.isEmpty() ? "datos" : n;
  }

  static String quitarRuta( String ruta ) {
    String r = ruta.replaceAll( "\\$\\{[^}]*\\}", "" ).replace( '\\', '/' );
    r = r.substring( r.lastIndexOf( '/' ) + 1 );
    int punto = r.lastIndexOf( '.' );
    return punto > 0 ? r.substring( 0, punto ) : r;
  }

  static String normalizar( String s ) {
    String n = Normalizer.normalize( s, Normalizer.Form.NFD ).replaceAll( "\\p{M}", "" ).toLowerCase( Locale.ROOT );
    return n.replaceAll( "[^a-z0-9]+", "_" ).replaceAll( "^_+|_+$", "" );
  }

  private static String layout( RowMetaInterface rm ) {
    StringBuilder b = new StringBuilder();
    for ( ValueMetaInterface v : rm.getValueMetaList() ) {
      b.append( v.getName() ).append( ':' ).append( v.getTypeDesc() ).append( '|' );
    }
    return b.toString();
  }

  /** Los campos de una fila de rechazo: los que llegan + motivo + campos_error. */
  private static RowMetaInterface conMotivo( RowMetaInterface rm ) {
    RowMetaInterface r = rm.clone();
    if ( r.indexOfValue( MOTIVO ) < 0 ) {
      r.addValueMeta( new ValueMetaString( MOTIVO ) );
    }
    if ( r.indexOfValue( CAMPOS_ERROR ) < 0 ) {
      r.addValueMeta( new ValueMetaString( CAMPOS_ERROR ) );
    }
    return r;
  }

  // ---------------------------------------------------------------- la cola de rechazo

  /** La cola del formato 'campos' (las filas ya con motivo), creándola si hace falta, junto al ancla. */
  private Cola cola( RowMetaInterface campos, StepMeta ancla ) {
    String firma = layout( campos );
    Cola c = colas.get( firma );
    if ( c != null ) {
      return c;
    }
    c = new Cola();
    String fl = flujo( entradaDe( ancla ) );
    Integer n = flujosUsados.get( fl );
    flujosUsados.put( fl, n == null ? 1 : n + 1 );
    String archivo = n == null ? fl : fl + "_" + ( n + 1 );
    c.limpiar = unico( t( "Clean reason ", "Limpiar motivo ", "Limpar motivo " ) + archivo );
    c.escribir = unico( t( "Write rejects ", "Escribir rechazos ", "Gravar rejeitados " ) + archivo );
    List<int[][]> primera = new ArrayList<int[][]>();
    primera.add( fuente( ancla.getName() ) );
    c.lugar = carril( primera, c.limpiar, c.escribir );
    int[] pl = c.lugar.p1;
    int[] pe = c.lugar.p2;

    StringBuilder r = new StringBuilder( "<fields>" );
    r.append( regla( MOTIVO, "^\\s+|\\s+$", "" ) ).append( regla( MOTIVO, "\\s*[\\r\\n]+\\s*", " | " ) )
      .append( regla( MOTIVO, "^([A-Za-z_$][\\w$]*\\.)+[A-Za-z_$][\\w$]*Exception:\\s*(\\|\\s*)?", "" ) )
      .append( regla( MOTIVO, ",\\s*$", "" ) ).append( regla( CAMPOS_ERROR, ",\\s*$", "" ) ).append( "</fields>" );
    Cambio limpiar = nuevo( c.limpiar, "ReplaceString", r.toString(), pl, "rejects tail" );
    c.cambioLimpiar = limpiar;
    limpiar.diff = "+ " + c.limpiar + " (Replace in string: one line, no blanks at the edges)";
    c.cambios.add( limpiar );

    String ruta = p.rechazos.replace( "{flow}", archivo );
    String ext = "";
    int barra = Math.max( ruta.lastIndexOf( '/' ), ruta.lastIndexOf( '\\' ) );
    int punto = ruta.lastIndexOf( '.' );
    if ( punto > barra && punto > 0 && !ruta.substring( punto ).contains( "}" ) ) {
      ext = ruta.substring( punto + 1 );
      ruta = ruta.substring( 0, punto );
    }
    String salida = "<separator>;</separator><enclosure>\"</enclosure><enclosure_forced>N</enclosure_forced>"
      + "<header>Y</header><footer>N</footer><encoding>UTF-8</encoding><create_parent_folder>Y</create_parent_folder>"
      + "<file>" + XMLHandler.addTagValue( "name", ruta, false ) + "<servlet_output>N</servlet_output>"
      + "<do_not_open_new_file_init>N</do_not_open_new_file_init>" + XMLHandler.addTagValue( "extention", ext, false )
      + "<append>N</append><split>N</split><haspartno>N</haspartno><add_date>N</add_date><add_time>N</add_time>"
      + "<SpecifyFormat>N</SpecifyFormat><date_time_format/><add_to_result_filenames>Y</add_to_result_filenames>"
      + "<pad>N</pad><fast_dump>N</fast_dump><splitevery/></file>" + LintArreglos.camposDeSalida( campos );
    Cambio escribir = nuevo( c.escribir, "TextFileOutput", salida, pe, "rejects tail" );
    c.cambioEscribir = escribir;
    escribir.p.put( "file", ruta + ( ext.isEmpty() ? "" : "." + ext ) );
    escribir.diff = "+ " + c.escribir + " (Text file output) " + escribir.p.get( "file" ) + " [" + campos.size()
      + " fields, created even when empty]";
    c.cambios.add( escribir );
    c.cambios.add( hop( c.limpiar, c.escribir, false ) );
    colas.put( firma, c );
    return c;
  }

  private static String regla( String campo, String regex, String por ) {
    return "<field>" + XMLHandler.addTagValue( "in_stream_name", campo, false ) + "<out_stream_name/>"
      + "<use_regex>yes</use_regex>" + XMLHandler.addTagValue( "replace_string", regex, false )
      + XMLHandler.addTagValue( "replace_by_string", por, false ) + "<set_empty_string>N</set_empty_string>"
      + "<replace_field_by_string/><whole_word>no</whole_word><case_sensitive>no</case_sensitive>"
      + "<is_unicode>no</is_unicode></field>";
  }

  private Cambio nuevo( String nombre, String tipo, String cuerpo, int[] pos, String receta ) {
    Cambio c = new Cambio( Cambio.ADD_STEP ).con( "name", nombre ).con( "type", tipo );
    c.xml = cuerpo;
    c.x = pos[ 0 ];
    c.y = pos[ 1 ];
    c.atributos.put( ADDED_BY, "harden_etl:" + receta );
    if ( !"rejects tail".equals( receta ) ) { // los de las colas ya están en cajas
      int[] k = caja( pos[ 0 ], pos[ 1 ], nombre );
      cajasNuevas.add( k );
      posNuevos.put( nombre, new int[][] { pos, k } );
    }
    return c;
  }

  private static Cambio hop( String de, String a, boolean error ) {
    Cambio c = new Cambio( Cambio.ADD_HOP ).con( "from", de ).con( "to", a );
    if ( error ) {
      c.p.put( "error", "true" );
    }
    c.diff = "+ " + ( error ? "error hop " : "hop " ) + de + " -> " + a;
    return c;
  }

  private static Cambio quitarHop( String de, String a ) {
    Cambio c = new Cambio( Cambio.REMOVE_HOP ).con( "from", de ).con( "to", a );
    c.diff = "- hop " + de + " -> " + a;
    return c;
  }

  // ---------------------------------------------------------------- R01 rejects_on_error

  private boolean puedeFallarPorFila( StepMeta s ) {
    String id = s.getStepID();
    if ( CARGAS.contains( id ) ) {
      return true;
    }
    if ( "SelectValues".equals( id ) ) {
      return !metasQueConvierten( s ).isEmpty();
    }
    if ( "DBLookup".equals( id ) ) {
      return "Y".equalsIgnoreCase( txt( s, "fail_on_multiple" ) );
    }
    if ( ENTRADAS_ARCHIVO.contains( id ) ) {
      // un campo leído como número o fecha: un valor mal escrito para la carga entera (el CSV input lo manda al hop
      // de error con el campo en null y el mensaje de Kettle)
      return !ReglasRobustez.camposTipados( xml( s ) ).isEmpty();
    }
    return false;
  }

  private List<Element> metasQueConvierten( StepMeta s ) {
    List<Element> l = new ArrayList<Element>();
    for ( Element m : Nodos.hijos( Nodos.hijo( xml( s ), "fields" ), "meta" ) ) {
      if ( CONVERTIDOS.contains( Nodos.txt( m, "type" ) ) ) {
        l.add( m );
      }
    }
    return l;
  }

  /** Los cambios de R01 sobre s (manejo de errores → su cola), o null con el motivo en 'por'. */
  private List<Cambio> aCola( StepMeta s, String[] por ) {
    if ( !s.supportsErrorHandling() ) {
      por[ 0 ] = "TableOutput".equals( s.getStepID() )
        ? "Table output cannot send rows to an error hop on this database (SQLite, for example): use Insert/Update "
          + "(idempotent_load), which can"
        : "the step does not support error handling";
      return null;
    }
    RowMetaInterface prev;
    try {
      // una entrada de archivo manda al hop de error su propia fila (sus campos de salida)
      prev = ENTRADAS_ARCHIVO.contains( s.getStepID() ) ? tm.getStepFields( s ) : tm.getPrevStepFields( s );
    } catch ( Exception x ) {
      por[ 0 ] = "fields unknown: " + RunMotor.primeraLinea( x );
      return null;
    }
    if ( prev == null || prev.isEmpty() ) {
      por[ 0 ] = "fields unknown: no fields reach the step";
      return null;
    }
    Cola c = cola( conMotivo( prev ), s );
    List<Cambio> l = new ArrayList<Cambio>( c.cambios );
    l.add( alimentar( c, s.getName(), true ) );
    Cambio e = new Cambio( Cambio.SET_ERROR_HANDLING ).con( "step", s.getName() ).con( "target", c.limpiar )
      .con( "description_field", MOTIVO ).con( "fields_field", CAMPOS_ERROR );
    e.diff = "~ " + s.getName() + ": error handling -> " + c.limpiar + " (" + MOTIVO + ", " + CAMPOS_ERROR + ")";
    l.add( e );
    return l;
  }

  private void erroresACola() {
    for ( StepMeta s : tm.getSteps() ) {
      if ( rechazo.contains( s ) || propio( s ) || !puedeFallarPorFila( s )
        || ( arriba( s ).isEmpty() && !ENTRADAS_ARCHIVO.contains( s.getStepID() ) ) ) {
        continue;
      }
      StepErrorMeta em = s.getStepErrorMeta();
      if ( em != null && em.isEnabled() && em.getTargetStep() != null ) {
        continue; // ya tiene: R02 mira si le falta el motivo
      }
      String[] por = new String[ 1 ];
      List<Cambio> cs = aCola( s, por );
      if ( cs == null ) {
        plan.descartadas.add( new String[] { Receta.REJECTS_ON_ERROR, s.getName(), por[ 0 ] } );
        continue;
      }
      Propuesta x = new Propuesta( Receta.REJECTS_ON_ERROR, s.getName() );
      x.porQue = CARGAS.contains( s.getStepID() )
        ? "A database error on one row (text too long, value out of range, a constraint) stops the whole load."
        : "One row that cannot be converted stops the whole run.";
      x.cambios.addAll( cs );
      if ( "TableOutput".equals( s.getStepID() ) && "Y".equalsIgnoreCase( txt( s, "use_batch" ) ) ) {
        x.avisos.add( "Table output with batch updates: with error handling Kettle may reject the whole batch; set "
          + "use_batch=N if single rows must be rejected." );
      }
      if ( tm.isUsingUniqueConnections() ) {
        x.avisos.add( "The transformation is transactional (unique connections): on some databases a failed row "
          + "aborts the transaction." );
      }
      plan.propuestas.add( x );
    }
  }

  // ---------------------------------------------------------------- R02 reject_reason

  /**
   * La rama de rechazos ya pone su motivo aunque el hop de error no lo traiga: todos los escritores que alcanza
   * declaran sus campos y uno es un motivo (un Add constants con motivo_rechazo = DUPLICADO antes del archivo, por
   * ejemplo). Misma regla que rejects_without_reason del linter.
   */
  private boolean motivoEnLaRama( StepMeta destino ) {
    Set<StepMeta> rama = new LinkedHashSet<StepMeta>( abajo( destino ) );
    rama.add( destino );
    boolean alguno = false;
    for ( StepMeta w : rama ) {
      if ( !ReglasRobustez.ESCRITORES.contains( w.getStepID() ) || "WriteToLog".equals( w.getStepID() ) ) {
        continue;
      }
      List<String> campos = Nodos.nombres( xml( w ), "fields", "field", "name" );
      if ( campos.isEmpty() || !ReglasRobustez.tieneMotivo( campos ) ) {
        return false;
      }
      alguno = true;
    }
    return alguno;
  }

  private void motivos() {
    for ( StepMeta s : tm.getSteps() ) {
      StepErrorMeta em = s.getStepErrorMeta();
      if ( em == null || !em.isEnabled() || em.getTargetStep() == null ) {
        continue;
      }
      boolean sinMotivo = vacio( em.getErrorDescriptionsValuename() );
      if ( !sinMotivo || motivoEnLaRama( em.getTargetStep() ) ) {
        continue;
      }
      Propuesta x = new Propuesta( Receta.REJECT_REASON, s.getName() );
      x.porQue = "Its rejected rows carry no reason: nobody can tell why they were rejected.";
      boolean conCampos = vacio( em.getErrorFieldsValuename() );
      Cambio e = new Cambio( Cambio.SET_ERROR_HANDLING ).con( "step", s.getName() )
        .con( "target", em.getTargetStep().getName() ).con( "description_field", MOTIVO );
      if ( conCampos ) {
        e.p.put( "fields_field", CAMPOS_ERROR );
      }
      e.diff = "~ " + s.getName() + ": error rows get " + MOTIVO + ( conCampos ? " and " + CAMPOS_ERROR : "" );
      x.cambios.add( e );
      // El escritor de esa rama, si declara sus campos, tiene que escribir el motivo (si no, no sale en el archivo).
      StepMeta w = em.getTargetStep();
      for ( int i = 0; i < 10 && w != null && !"TextFileOutput".equals( w.getStepID() ); i++ ) {
        List<TransHopMeta> sal = salidas( w );
        w = sal.size() == 1 ? sal.get( 0 ).getToStep() : null;
      }
      if ( w != null ) {
        Element campos = Nodos.hijo( xml( w ), "fields" );
        List<Element> fs = Nodos.hijos( campos, "field" );
        if ( !fs.isEmpty() ) {
          Set<String> ya = new HashSet<String>();
          for ( Element f : fs ) {
            ya.add( Nodos.txt( f, "name" ) );
          }
          StringBuilder extra = new StringBuilder();
          List<String> nuevos = new ArrayList<String>();
          for ( String n : conCampos ? new String[] { MOTIVO, CAMPOS_ERROR } : new String[] { MOTIVO } ) {
            if ( !ya.contains( n ) ) {
              nuevos.add( n );
              extra.append( "<field>" ).append( XMLHandler.addTagValue( "name", n, false ) )
                .append( "<type>String</type><format/><currency/><decimal/><group/><nullif/>"
                  + "<trim_type>none</trim_type><length>-1</length><precision>-1</precision></field>" );
            }
          }
          if ( !nuevos.isEmpty() ) {
            try {
              String viejo = Xml.texto( campos );
              String nuevo = viejo.replaceFirst( "</fields>\\s*$", java.util.regex.Matcher.quoteReplacement(
                extra.toString() + "</fields>" ) );
              if ( viejo.trim().endsWith( "/>" ) ) {
                nuevo = "<fields>" + extra + "</fields>";
              }
              Cambio rc = new Cambio( Cambio.RECONFIGURE ).con( "step", w.getName() );
              rc.xml = nuevo;
              rc.diff = "~ " + w.getName() + ": also writes " + String.join( ", ", nuevos );
              x.cambios.add( rc );
            } catch ( Exception ex ) {
              x.avisos.add( "Add " + String.join( ", ", nuevos ) + " to the fields of '" + w.getName() + "' by hand." );
            }
          }
        }
      }
      plan.propuestas.add( x );
    }
  }

  private static boolean vacio( String s ) {
    return s == null || s.trim().isEmpty();
  }

  // ---------------------------------------------------------------- R03 required_fields

  /** Los campos que s usa como clave (búsquedas, cargas, cruces, agrupaciones, deduplicación). */
  private List<String> clavesDe( StepMeta s ) {
    String id = s.getStepID();
    Element x = xml( s );
    List<String> l = new ArrayList<String>();
    if ( "InsertUpdate".equals( id ) || "Update".equals( id ) || "Delete".equals( id ) || "DBLookup".equals( id )
      || "StreamLookup".equals( id ) ) {
      for ( Element k : Nodos.hijos( Nodos.hijo( x, "lookup" ), "key" ) ) {
        l.add( Nodos.txt( k, "name" ) );
      }
    } else if ( "MergeJoin".equals( id ) ) {
      for ( String c : new String[] { "keys_1", "keys_2" } ) {
        for ( Element k : Nodos.hijos( Nodos.hijo( x, c ), "key" ) ) {
          l.add( k.getTextContent().trim() );
        }
      }
    } else if ( AGRUPAN.contains( id ) ) {
      l.addAll( Nodos.nombres( x, "group", "field", "name" ) );
    } else if ( "UniqueRows".equals( id ) || "UniqueRowsByHashSet".equals( id ) ) {
      l.addAll( Nodos.nombres( x, "fields", "field", "name" ) );
    }
    l.remove( "" );
    return l;
  }

  private void requeridos() {
    for ( StepMeta in : tm.getSteps() ) {
      if ( !ENTRADAS_ARCHIVO.contains( in.getStepID() ) || rechazo.contains( in ) || propio( in ) ) {
        continue;
      }
      RowMetaInterface campos;
      try {
        campos = tm.getStepFields( in );
      } catch ( Exception x ) {
        plan.descartadas.add( new String[] { Receta.REQUIRED_FIELDS, in.getName(), "fields unknown: "
          + RunMotor.primeraLinea( x ) } );
        continue;
      }
      Set<StepMeta> desc = abajo( in );
      List<String> req = null;
      for ( Map.Entry<String, List<String>> e : p.obligatorios.entrySet() ) {
        if ( e.getKey().equalsIgnoreCase( in.getName() ) ) {
          req = new ArrayList<String>( e.getValue() );
        }
      }
      boolean detectados = req == null;
      if ( req == null ) {
        Set<String> r = new LinkedHashSet<String>();
        for ( StepMeta c : desc ) {
          if ( rechazo.contains( c ) ) {
            continue;
          }
          for ( String k : clavesDe( c ) ) {
            if ( campos.indexOfValue( k ) >= 0 ) {
              r.add( k );
            }
          }
        }
        // ya controlados: un Filter rows que mira si son nulos, o un Data validator
        for ( StepMeta c : desc ) {
          if ( "Validator".equals( c.getStepID() ) ) {
            r.clear();
          } else if ( "FilterRows".equals( c.getStepID() ) ) {
            for ( String k : new ArrayList<String>( r ) ) {
              String cond = textoXml( c );
              if ( cond != null && cond.contains( "<leftvalue>" + k + "</leftvalue>" ) && cond.contains( "NULL" ) ) {
                r.remove( k );
              }
            }
          }
        }
        req = new ArrayList<String>( r );
      }
      if ( req.isEmpty() ) {
        continue;
      }
      List<TransHopMeta> sal = salidas( in );
      if ( sal.isEmpty() ) {
        continue;
      }
      String fl = flujo( in );
      Propuesta x = new Propuesta( Receta.REQUIRED_FIELDS, in.getName() );
      x.porQue = "A line with " + String.join( ", ", req ) + " empty (or cut short) reaches the load: it is loaded "
        + "with a null key or stops it. It goes to the rejects file with the list of missing fields instead.";
      x.detectado = Tools.obj();
      ArrayNode an = x.detectado.putArray( "required_fields" );
      for ( String r : req ) {
        an.add( r );
      }
      x.detectado.put( "source", detectados ? "detected: keys used downstream" : "required_fields argument" );
      if ( detectados ) {
        x.avisos.add( "Required fields detected from the keys used downstream: correct them with "
          + "required_fields={\"" + in.getName() + "\": [...]} if needed." );
      }
      List<String> malos = new ArrayList<String>();
      for ( String r : req ) {
        if ( !IDENTIFICADOR.matcher( r ).matches() ) {
          malos.add( r );
        } else if ( campos.indexOfValue( r ) < 0 ) {
          malos.add( r + " (not a field of the input)" );
        }
      }
      if ( !malos.isEmpty() ) {
        x.nivel = Receta.MANUAL;
        x.comoHacerlo = "The reason is built with a User Defined Java Expression, which needs field names that are "
          + "Java identifiers; these are not: " + String.join( ", ", malos ) + ". Rename them in the input (or use "
          + "a Filter rows with IS NOT NULL and a Modified Java Script Value for the reason).";
        plan.propuestas.add( x );
        continue;
      }
      RowMetaInterface filas = conMotivo( campos );
      Cola c = cola( filas, in );
      String filtro = unico( t( fl + " complete?", "¿" + fl + " completo?", fl + " completo?" ) );
      String motivo = unico( t( "Reason " + fl + " incomplete", "Motivo " + fl + " incompleto",
        "Motivo " + fl + " incompleto" ) );
      StepMeta primero = sal.get( 0 ).getToStep();
      boolean varias = sal.size() > 1;
      String reparto = varias ? unico( t( fl + " complete", fl + " completo", fl + " completo" ) ) : null;
      int[] pf = libre( ( in.getLocation().x + primero.getLocation().x ) / 2, in.getLocation().y + 60 );
      int[] pm = libre( pf[ 0 ], pf[ 1 ] + 100 );
      StringBuilder cond = new StringBuilder( "<compare><condition><negated>N</negated><conditions>" );
      for ( int i = 0; i < req.size(); i++ ) {
        cond.append( "<condition><negated>N</negated>" ).append( i == 0 ? "" : "<operator>AND</operator>" )
          .append( XMLHandler.addTagValue( "leftvalue", req.get( i ), false ) )
          .append( "<function>IS NOT NULL</function><rightvalue/></condition>" );
      }
      cond.append( "</conditions></condition></compare>" );
      String verdadero = varias ? reparto : primero.getName();
      Cambio cf = nuevo( filtro, "FilterRows", XMLHandler.addTagValue( "send_true_to", verdadero, false )
        + XMLHandler.addTagValue( "send_false_to", motivo, false ) + cond, pf, Receta.REQUIRED_FIELDS );
      cf.diff = "+ " + filtro + " (Filter rows: " + String.join( " AND ", conNotNull( req ) ) + ")";
      x.cambios.add( cf );
      StringBuilder ex = new StringBuilder( "\"" + t( "Missing required fields: ", "Faltan campos obligatorios: ",
        "Faltam campos obrigatórios: " ) + "\"" );
      StringBuilder ec = new StringBuilder( "\"\"" );
      for ( String r : req ) {
        String parte = " + (" + r + " == null ? \"" + r + ", \" : \"\")";
        ex.append( parte );
        ec.append( parte );
      }
      String janino = formula( MOTIVO, ex.toString() ) + formula( CAMPOS_ERROR, ec.toString() );
      Cambio cm = nuevo( motivo, "Janino", janino, pm, Receta.REQUIRED_FIELDS );
      cm.diff = "+ " + motivo + " (User Defined Java Expression: " + MOTIVO + " = the missing fields)";
      x.cambios.add( cm );
      if ( varias ) {
        int[] pr = libre( pf[ 0 ] + 120, pf[ 1 ] );
        Cambio cr = nuevo( reparto, "Dummy", "", pr, Receta.REQUIRED_FIELDS );
        cr.p.put( "distribute", in.isDistributes() ? "true" : "false" );
        cr.diff = "+ " + reparto + " (Dummy: sends the complete rows where '" + in.getName() + "' sent them)";
        x.cambios.add( cr );
      }
      for ( TransHopMeta h : sal ) {
        x.cambios.add( quitarHop( in.getName(), h.getToStep().getName() ) );
      }
      x.cambios.add( hop( in.getName(), filtro, false ) );
      if ( varias ) {
        x.cambios.add( hop( filtro, reparto, false ) );
        for ( TransHopMeta h : sal ) {
          x.cambios.add( hop( reparto, h.getToStep().getName(), false ) );
        }
      } else {
        x.cambios.add( hop( filtro, primero.getName(), false ) );
      }
      x.cambios.add( hop( filtro, motivo, false ) );
      x.cambios.addAll( c.cambios );
      x.cambios.add( alimentar( c, motivo, false ) );
      if ( MANDAN_POR_NOMBRE.contains( in.getStepID() ) ) {
        x.nivel = Receta.MANUAL;
        x.cambios.clear();
        x.comoHacerlo = "Insert the Filter rows by hand: '" + in.getName() + "' sends rows by name.";
      }
      plan.propuestas.add( x );
    }
  }

  private static List<String> conNotNull( List<String> req ) {
    List<String> l = new ArrayList<String>();
    for ( String r : req ) {
      l.add( r + " IS NOT NULL" );
    }
    return l;
  }

  private static String formula( String campo, String expresion ) {
    return "<formula>" + XMLHandler.addTagValue( "field_name", campo, false )
      + XMLHandler.addTagValue( "formula_string", expresion, false ) + "<value_type>String</value_type>"
      + "<value_length>-1</value_length><value_precision>-1</value_precision><replace_field/></formula>";
  }

  /** Manda por nombre y no es un Filter rows (Switch/case, Java filter): insertar detrás es a mano. */
  private static boolean porNombreSinFiltro( StepMeta f ) {
    return MANDAN_POR_NOMBRE.contains( f.getStepID() ) && !"FilterRows".equals( f.getStepID() );
  }

  /** Si f es un Filter rows que manda a 'viejo' por nombre, ahora manda a 'nuevo' (el step que se inserta). */
  private void redirigir( StepMeta f, StepMeta viejo, String nuevo, Propuesta x ) {
    if ( !"FilterRows".equals( f.getStepID() ) ) {
      return;
    }
    for ( String tag : new String[] { "send_true_to", "send_false_to" } ) {
      if ( txt( f, tag ).equalsIgnoreCase( viejo.getName() ) ) {
        Cambio rc = new Cambio( Cambio.RECONFIGURE ).con( "step", f.getName() );
        rc.xml = XMLHandler.addTagValue( tag, nuevo, false );
        rc.diff = "~ " + f.getName() + ": " + tag + " -> " + nuevo;
        x.cambios.add( rc );
      }
    }
  }

  // ---------------------------------------------------------------- R12 input_file_parameter

  private void parametros() {
    Set<String> existentes = new HashSet<String>( Arrays.asList( tm.listParameters() ) );
    for ( StepMeta in : tm.getSteps() ) {
      if ( !ENTRADAS_ARCHIVO.contains( in.getStepID() ) || propio( in ) ) {
        continue;
      }
      Element raiz = xml( in );
      boolean csv = "CsvInput".equals( in.getStepID() );
      Element archivo = csv ? null : Nodos.hijo( raiz, "file" );
      String ruta = csv ? Nodos.crudo( raiz, "filename" ) : Nodos.crudo( archivo, "name" );
      if ( ruta == null || ruta.trim().isEmpty() || VARIABLE_PROPIA.matcher( ruta ).find() ) {
        continue;
      }
      if ( !csv && "Y".equalsIgnoreCase( txt( in, "accept_filenames" ) ) ) {
        continue;
      }
      String nombre = t( "INPUT_FILE_", "ARCHIVO_", "ARQUIVO_" ) + normalizar( flujo( in ) ).toUpperCase( Locale.ROOT );
      if ( existentes.contains( nombre ) ) {
        continue;
      }
      existentes.add( nombre );
      int barra = Math.max( ruta.lastIndexOf( '/' ), ruta.lastIndexOf( '\\' ) );
      String dir = barra >= 0 ? ruta.substring( 0, barra + 1 ) : "";
      String base = ruta.substring( barra + 1 );
      String nueva = dir + "${" + nombre + "}";
      String fragmento;
      try {
        if ( csv ) {
          fragmento = XMLHandler.addTagValue( "filename", nueva, false );
        } else {
          Element copia = (Element) archivo.cloneNode( true );
          Nodos.hijo( copia, "name" ).setTextContent( nueva );
          fragmento = Xml.texto( copia );
        }
      } catch ( Exception x ) {
        continue;
      }
      Propuesta x = new Propuesta( Receta.INPUT_FILE_PARAMETER, in.getName() );
      x.porQue = "The input file is fixed: to try the load with a broken file (or tomorrow's file) the "
        + "transformation has to be edited. With a parameter it is run with another file without touching it.";
      Cambio par = new Cambio( Cambio.ADD_PARAMETER ).con( "name", nombre ).con( "default", base )
        .con( "description", t( "Input file of ", "Archivo de entrada de ", "Arquivo de entrada de " ) + in.getName() );
      par.diff = "+ parameter " + nombre + " (default " + base + ") [not undoable with Ctrl+Z]";
      x.cambios.add( par );
      Cambio rc = new Cambio( Cambio.RECONFIGURE ).con( "step", in.getName() );
      rc.xml = fragmento;
      rc.diff = "~ " + in.getName() + ": file " + ruta + " -> " + nueva;
      x.cambios.add( rc );
      x.avisos.add( "Parameters are outside Spoon's undo: Ctrl+Z restores the file name, the parameter stays." );
      plan.propuestas.add( x );
    }
  }

  // ---------------------------------------------------------------- R04 strict_conversion

  private void conversiones() {
    for ( StepMeta s : tm.getSteps() ) {
      if ( !"SelectValues".equals( s.getStepID() ) || rechazo.contains( s ) || propio( s ) ) {
        continue;
      }
      List<Element> metas = metasQueConvierten( s );
      List<String> laxos = new ArrayList<String>();
      for ( Element m : metas ) {
        String tipo = Nodos.txt( m, "type" );
        boolean fecha = "Date".equals( tipo ) || "Timestamp".equals( tipo );
        if ( fecha && laxo( Nodos.txt( m, "date_format_lenient" ) )
          || !fecha && laxo( Nodos.txt( m, "lenient_string_to_number" ) ) ) {
          laxos.add( Nodos.txt( m, "name" ) );
        }
      }
      if ( laxos.isEmpty() ) {
        continue;
      }
      Element campos = Nodos.hijo( xml( s ), "fields" );
      String fragmento;
      try {
        Element copia = (Element) campos.cloneNode( true );
        for ( Element m : Nodos.hijos( copia, "meta" ) ) {
          if ( CONVERTIDOS.contains( Nodos.txt( m, "type" ) ) ) {
            LintArreglos.texto( m, "date_format_lenient", "false" );
            LintArreglos.texto( m, "lenient_string_to_number", "false" );
          }
        }
        fragmento = Xml.texto( copia );
      } catch ( Exception x ) {
        continue;
      }
      Propuesta x = new Propuesta( Receta.STRICT_CONVERSION, s.getName() );
      x.porQue = "Lenient conversions let badly written values in without an error (31/02 becomes 3 March, "
        + "'12abc' may be read as 12): the row is loaded wrong. Strict, it is rejected with Kettle's message.";
      Cambio rc = new Cambio( Cambio.RECONFIGURE ).con( "step", s.getName() );
      rc.xml = fragmento;
      rc.diff = "~ " + s.getName() + ": strict conversion (not lenient) of " + String.join( ", ", laxos );
      x.cambios.add( rc );
      StepErrorMeta em = s.getStepErrorMeta();
      if ( em == null || !em.isEnabled() || em.getTargetStep() == null ) {
        String[] por = new String[ 1 ];
        List<Cambio> cs = aCola( s, por );
        if ( cs != null ) {
          x.cambios.addAll( cs );
        } else {
          x.avisos.add( "Without error handling a strict conversion stops the run: " + por[ 0 ] );
        }
      }
      plan.propuestas.add( x );
    }
  }

  /**
   * SelectValues 9.4 escribe true/false y lee con Boolean.parseBoolean: solo «true» es laxo («Y» o sin el tag se leen
   * como estricto, comprobado con una corrida).
   */
  private static boolean laxo( String v ) {
    return "true".equalsIgnoreCase( v );
  }

  // ---------------------------------------------------------------- R06 dedupe_before_load

  private void repetidos() {
    for ( StepMeta s : tm.getSteps() ) {
      String id = s.getStepID();
      if ( !( "InsertUpdate".equals( id ) || "Update".equals( id ) ) || rechazo.contains( s ) || propio( s ) ) {
        continue;
      }
      List<String> claves = null;
      for ( Map.Entry<String, List<String>> e : p.claves.entrySet() ) {
        if ( e.getKey().equalsIgnoreCase( s.getName() ) ) {
          claves = e.getValue();
        }
      }
      if ( claves == null ) {
        claves = clavesDe( s );
        if ( claves.size() != 1 ) {
          continue; // una tabla de hechos (2+ claves) se agrupa (aggregate_to_grain), no se deduplica
        }
      }
      if ( claves.isEmpty() ) {
        continue;
      }
      Set<StepMeta> arr = arriba( s );
      boolean fuente = false;
      boolean yaQuita = false;
      for ( StepMeta a : arr ) {
        fuente |= ENTRADAS_ARCHIVO.contains( a.getStepID() ) || "Append".equals( a.getStepID() );
        yaQuita |= QUITAN_REPETIDOS.contains( a.getStepID() );
      }
      if ( !fuente || yaQuita ) {
        continue;
      }
      // la clave sale de un contador (Add sequence): cada fila trae una nueva, así que deduplicar por ella no quita
      // nada (los repetidos por el código de negocio se cargan con otra clave)
      String contador = null;
      for ( StepMeta a : arr ) {
        if ( "Sequence".equals( a.getStepID() ) && claves.contains( txt( a, "valuename" ) ) ) {
          contador = a.getName();
        }
      }
      if ( contador != null ) {
        plan.descartadas.add( new String[] { Receta.DEDUPE_BEFORE_LOAD, s.getName(), "the key " + String.join( ", ",
          claves ) + " comes from the counter '" + contador + "': every row gets a new one, so a dedupe on it removes "
          + "nothing. Pass keys={\"" + s.getName() + "\": [\"<business key>\"]} to dedupe on the business key, and "
          + "make the key stable (stable_surrogate_keys)" } );
        continue;
      }
      List<TransHopMeta> ent = new ArrayList<TransHopMeta>();
      for ( TransHopMeta h : entrantes( s ) ) {
        if ( !h.isErrorHop() ) {
          ent.add( h );
        }
      }
      Propuesta x = new Propuesta( Receta.DEDUPE_BEFORE_LOAD, s.getName() );
      x.porQue = "Two rows with the same " + String.join( ", ", claves ) + " reach the load: the second overwrites "
        + "the first (or breaks the key). The first one wins and the repeated ones are dropped (declared).";
      String tabla = Nodos.txt( Nodos.hijo( xml( s ), "lookup" ), "table" );
      String nombre = unico( t( "Remove duplicate ", "Quitar repetidos ", "Remover repetidos " )
        + ( tabla.isEmpty() ? s.getName() : tabla ) );
      if ( ent.size() != 1 || porNombreSinFiltro( ent.get( 0 ).getFromStep() ) ) {
        x.nivel = Receta.MANUAL;
        x.comoHacerlo = "Insert a Unique rows (HashSet) by " + String.join( ", ", claves ) + " right before '"
          + s.getName() + "' (it gets rows from several hops or by name).";
        plan.propuestas.add( x );
        continue;
      }
      StepMeta f = ent.get( 0 ).getFromStep();
      int[] pos = libre( ( f.getLocation().x + s.getLocation().x ) / 2, ( f.getLocation().y + s.getLocation().y ) / 2 );
      StringBuilder campos = new StringBuilder( "<store_values>N</store_values><reject_duplicate_row>N"
        + "</reject_duplicate_row><error_description/><fields>" );
      for ( String k : claves ) {
        campos.append( "<field>" ).append( XMLHandler.addTagValue( "name", k, false ) ).append( "</field>" );
      }
      campos.append( "</fields>" );
      Cambio u = nuevo( nombre, "UniqueRowsByHashSet", campos.toString(), pos, Receta.DEDUPE_BEFORE_LOAD );
      u.atributos.put( DROPS, "duplicates" );
      u.atributos.put( REASON, "first row per " + String.join( ", ", claves ) + " wins" );
      u.diff = "+ " + nombre + " (Unique rows (HashSet) by " + String.join( ", ", claves ) + "; declared drops=duplicates)";
      x.cambios.add( u );
      redirigir( f, s, nombre, x );
      x.cambios.add( quitarHop( f.getName(), s.getName() ) );
      x.cambios.add( hop( f.getName(), nombre, false ) );
      x.cambios.add( hop( nombre, s.getName(), false ) );
      plan.propuestas.add( x );
    }
  }

  // ---------------------------------------------------------------- R11 wait_for_loads

  private String claveTabla( StepMeta s ) {
    Element x = xml( s );
    String con = Nodos.txt( x, "connection" );
    String tabla;
    String id = s.getStepID();
    if ( "InsertUpdate".equals( id ) || "Update".equals( id ) || "Delete".equals( id ) || "DBLookup".equals( id ) ) {
      tabla = Nodos.txt( Nodos.hijo( x, "lookup" ), "table" );
    } else {
      tabla = Nodos.txt( x, "table" );
    }
    if ( con.isEmpty() || tabla.isEmpty() ) {
      return null;
    }
    return con.toLowerCase( Locale.ROOT ) + "|" + tabla.toLowerCase( Locale.ROOT );
  }

  private void esperas() {
    Map<String, List<StepMeta>> escritores = new HashMap<String, List<StepMeta>>();
    for ( StepMeta s : tm.getSteps() ) {
      if ( ESCRIBEN_TABLA.contains( s.getStepID() ) ) {
        String k = claveTabla( s );
        if ( k != null ) {
          if ( !escritores.containsKey( k ) ) {
            escritores.put( k, new ArrayList<StepMeta>() );
          }
          escritores.get( k ).add( s );
        }
      }
    }
    for ( StepMeta l : tm.getSteps() ) {
      if ( !"DBLookup".equals( l.getStepID() ) || propio( l ) ) {
        continue;
      }
      String k = claveTabla( l );
      List<StepMeta> ws = k == null ? null : escritores.get( k );
      if ( ws == null || ws.isEmpty() ) {
        continue;
      }
      Set<StepMeta> arr = arriba( l );
      List<StepMeta> faltan = new ArrayList<StepMeta>();
      for ( StepMeta w : ws ) {
        boolean cubierto = false;
        for ( StepMeta a : arr ) {
          if ( "BlockUntilStepsFinish".equals( a.getStepID() ) || "BlockingStep".equals( a.getStepID() ) ) {
            cubierto |= Nodos.nombres( xml( a ), "steps", "step", "name" ).contains( w.getName() );
          }
        }
        if ( !cubierto ) {
          faltan.add( w );
        }
      }
      if ( faltan.isEmpty() ) {
        continue;
      }
      List<String> nombres = new ArrayList<String>();
      for ( StepMeta w : faltan ) {
        nombres.add( w.getName() );
      }
      Set<StepMeta> desc = abajo( l );
      for ( StepMeta w : faltan ) {
        if ( desc.contains( w ) ) {
          plan.descartadas.add( new String[] { Receta.WAIT_FOR_LOADS, l.getName(), "would deadlock: '" + w.getName()
            + "' gets its rows through '" + l.getName() + "' (fine when the lookup finds the existing keys of the rows "
            + "being loaded, as in stable_surrogate_keys)" } );
          faltan = null;
          break;
        }
      }
      if ( faltan == null ) {
        continue;
      }
      List<TransHopMeta> ent = new ArrayList<TransHopMeta>();
      for ( TransHopMeta h : entrantes( l ) ) {
        if ( !h.isErrorHop() ) {
          ent.add( h );
        }
      }
      String tabla = Nodos.txt( Nodos.hijo( xml( l ), "lookup" ), "table" );
      Propuesta x = new Propuesta( Receta.WAIT_FOR_LOADS, l.getName() );
      x.porQue = "'" + l.getName() + "' reads " + tabla + " while " + String.join( ", ", nombres ) + " is still "
        + "writing it (in Kettle every step starts at once): rows can miss keys that are being loaded.";
      if ( ent.size() != 1 || porNombreSinFiltro( ent.get( 0 ).getFromStep() ) ) {
        x.nivel = Receta.MANUAL;
        x.comoHacerlo = "Insert a Block until steps finish (waiting for " + String.join( ", ", nombres )
          + ") right before '" + l.getName() + "'.";
        plan.propuestas.add( x );
        continue;
      }
      StepMeta f = ent.get( 0 ).getFromStep();
      // ¿la búsqueda y la carga que espera comen de la misma fuente? Block until steps finish no lee su entrada hasta
      // que terminan los esperados: con más filas que el row set (10000) la fuente se queda en putRow, la carga no
      // termina nunca y la transformación se cuelga. Delante va un Blocking step (pass_all_rows=Y, vuelca a disco) que
      // se lo traga todo, como en el patrón 06 (Buffer → Wait).
      boolean comparte = false;
      for ( StepMeta w : faltan ) {
        Set<StepMeta> compartidas = new HashSet<StepMeta>( arriba( w ) );
        compartidas.retainAll( arr );
        if ( !compartidas.isEmpty() ) {
          comparte = true;
          break;
        }
      }
      String nombre = unico( t( "Wait for ", "Esperar ", "Esperar " ) + ( tabla.isEmpty() ? nombres.get( 0 ) : tabla ) );
      int[] pos = libre( ( f.getLocation().x + l.getLocation().x ) / 2, ( f.getLocation().y + l.getLocation().y ) / 2 );
      StringBuilder pasos = new StringBuilder( "<steps>" );
      for ( String n : nombres ) {
        pasos.append( "<step>" ).append( XMLHandler.addTagValue( "name", n, false ) ).append( "<CopyNr>0</CopyNr></step>" );
      }
      pasos.append( "</steps>" );
      String primero = nombre;
      if ( comparte ) {
        String buffer = unico( t( "Buffer for ", "Acumular para ", "Acumular para " ) + ( tabla.isEmpty() ? nombres
          .get( 0 ) : tabla ) );
        int[] pb = libre( pos[ 0 ] - 60, pos[ 1 ] );
        Cambio bb = nuevo( buffer, "BlockingStep", "<pass_all_rows>Y</pass_all_rows>"
          + "<directory>%%java.io.tmpdir%%</directory><prefix>block</prefix><cache_size>5000</cache_size>"
          + "<compress>Y</compress>", pb, Receta.WAIT_FOR_LOADS );
        bb.diff = "+ " + buffer + " (Blocking step, all rows, spills to disk: the shared source never waits)";
        x.cambios.add( bb );
        x.avisos.add( "'" + l.getName() + "' and the load it waits for get rows from the same source: a Blocking step "
          + "(all rows, spilling to disk) goes before the wait so the source never stalls." );
        primero = buffer;
      }
      Cambio b = nuevo( nombre, "BlockUntilStepsFinish", pasos.toString(), pos, Receta.WAIT_FOR_LOADS );
      b.diff = "+ " + nombre + " (Block until steps finish: " + String.join( ", ", nombres ) + ")";
      x.cambios.add( b );
      redirigir( f, l, primero, x );
      x.cambios.add( quitarHop( f.getName(), l.getName() ) );
      x.cambios.add( hop( f.getName(), primero, false ) );
      if ( comparte ) {
        x.cambios.add( hop( primero, nombre, false ) );
      }
      x.cambios.add( hop( nombre, l.getName(), false ) );
      plan.propuestas.add( x );
    }
  }

  // ---------------------------------------------------------------- R05, R07, R08, R09, R10: manuales en la v1

  /**
   * R08 con aplicación (confirm): tras la búsqueda, un Filter rows con IS NOT NULL en lo que devuelve (sin valor por
   * defecto) → falso: un User Defined Java Expression con el motivo («no está en <tabla>: <clave>=valor») → la cola.
   * Si no se puede (varias salidas, nombres que no son identificadores...), la propuesta queda manual con el cómo.
   */
  private void huerfanos( StepMeta s, Propuesta pr, boolean come ) {
    Element x = xml( s );
    Element lk = Nodos.hijo( x, "lookup" );
    List<String> claves = new ArrayList<String>();
    for ( Element k : Nodos.hijos( lk, "key" ) ) {
      claves.add( Nodos.txt( k, "name" ) );
    }
    List<String> devueltos = new ArrayList<String>();
    for ( Element v : Nodos.hijos( lk, "value" ) ) {
      String n = Nodos.txt( v, "rename" ).isEmpty() ? Nodos.txt( v, "name" ) : Nodos.txt( v, "rename" );
      if ( Nodos.txt( v, "default" ).isEmpty() && !n.isEmpty() ) {
        devueltos.add( n );
      }
    }
    List<TransHopMeta> sal = salidas( s );
    if ( devueltos.isEmpty() || claves.isEmpty() || sal.size() != 1 || s.getStepErrorMeta() != null
      && s.getStepErrorMeta().isEnabled() ) {
      return;
    }
    for ( String n : devueltos ) {
      if ( !IDENTIFICADOR.matcher( n ).matches() ) {
        return;
      }
    }
    for ( String n : claves ) {
      if ( !IDENTIFICADOR.matcher( n ).matches() ) {
        return;
      }
    }
    RowMetaInterface campos;
    try {
      campos = tm.getStepFields( s );
    } catch ( Exception e ) {
      pr.avisos.add( "fields unknown: " + RunMotor.primeraLinea( e ) );
      return;
    }
    if ( campos == null || campos.isEmpty() ) {
      return;
    }
    StepMeta siguiente = sal.get( 0 ).getToStep();
    String tabla = "DBLookup".equals( s.getStepID() ) ? Nodos.txt( lk, "table" ) : txt( s, "from" );
    String fl = normalizar( tabla.isEmpty() ? s.getName() : tabla );
    String filtro = unico( t( "Found in " + fl + "?", "¿Está en " + fl + "?", "Está em " + fl + "?" ) );
    String motivo = unico( t( "Reason not in " + fl, "Motivo no está en " + fl, "Motivo não está em " + fl ) );
    int[] pf = libre( ( s.getLocation().x + siguiente.getLocation().x ) / 2, s.getLocation().y + 60 );
    int[] pm = libre( pf[ 0 ], pf[ 1 ] + 100 );
    StringBuilder cond = new StringBuilder( "<compare><condition><negated>N</negated><conditions>" );
    for ( int i = 0; i < devueltos.size(); i++ ) {
      cond.append( "<condition><negated>N</negated>" ).append( i == 0 ? "" : "<operator>AND</operator>" )
        .append( XMLHandler.addTagValue( "leftvalue", devueltos.get( i ), false ) )
        .append( "<function>IS NOT NULL</function><rightvalue/></condition>" );
    }
    cond.append( "</conditions></condition></compare>" );
    Cambio cf = nuevo( filtro, "FilterRows", XMLHandler.addTagValue( "send_true_to", siguiente.getName(), false )
      + XMLHandler.addTagValue( "send_false_to", motivo, false ) + cond, pf, Receta.ORPHANS_TO_REJECTS );
    cf.diff = "+ " + filtro + " (Filter rows: " + String.join( " AND ", conNotNull( devueltos ) ) + ")";
    String vacio = devueltos.get( 0 ) + " == null";
    StringBuilder ex = new StringBuilder( "\"" + t( "Not in ", "No está en ", "Não está em " ) + tabla + ":\"" );
    StringBuilder ec = new StringBuilder( "\"\"" );
    for ( String k : claves ) {
      ex.append( " + \" " ).append( k ).append( "=\" + " ).append( k );
      ec.append( " + \"" ).append( k ).append( ", \"" );
    }
    String janino = formula( MOTIVO, "(" + vacio + " ? " + ex + " : \"\")" ) + formula( CAMPOS_ERROR, "(" + vacio
      + " ? " + ec + " : \"\")" );
    Cambio cm = nuevo( motivo, "Janino", janino, pm, Receta.ORPHANS_TO_REJECTS );
    cm.diff = "+ " + motivo + " (User Defined Java Expression: " + MOTIVO + " = which key is not in " + tabla + ")";
    Cola c = cola( conMotivo( campos ), s );
    pr.nivel = Receta.CONFIRM;
    pr.comoHacerlo = null;
    pr.cambios.add( cf );
    pr.cambios.add( cm );
    if ( come ) {
      try {
        Element copia = (Element) lk.cloneNode( true );
        LintArreglos.texto( copia, "eat_row_on_failure", "N" );
        Cambio rc = new Cambio( Cambio.RECONFIGURE ).con( "step", s.getName() );
        rc.xml = Xml.texto( copia );
        rc.diff = "~ " + s.getName() + ": eat_row_on_failure=N (rows not found reach the filter)";
        pr.cambios.add( rc );
      } catch ( Exception e ) {
        pr.avisos.add( "Set eat_row_on_failure=N on '" + s.getName() + "' by hand." );
      }
    }
    pr.cambios.add( quitarHop( s.getName(), siguiente.getName() ) );
    pr.cambios.add( hop( s.getName(), filtro, false ) );
    pr.cambios.add( hop( filtro, siguiente.getName(), false ) );
    pr.cambios.add( hop( filtro, motivo, false ) );
    pr.cambios.addAll( c.cambios );
    pr.cambios.add( alimentar( c, motivo, false ) );
    pr.avisos.add( "If the source SQL joins with an INNER JOIN, the orphans disappear before this filter: make it a "
      + "LEFT JOIN (by hand)." );
  }

  private void manuales() {
    List<StepMeta> cargas = new ArrayList<StepMeta>();
    for ( StepMeta s : tm.getSteps() ) {
      if ( CARGAS.contains( s.getStepID() ) && !rechazo.contains( s ) ) {
        cargas.add( s );
      }
    }
    for ( StepMeta s : tm.getSteps() ) {
      if ( rechazo.contains( s ) || propio( s ) ) {
        continue;
      }
      String id = s.getStepID();
      Element x = xml( s );
      if ( "FieldSplitter".equals( id ) && RobustecerAplicar.manejoDeErrores( s ) == null ) {
        boolean deArchivo = false;
        for ( StepMeta a : arriba( s ) ) {
          deArchivo |= ENTRADAS_ARCHIVO.contains( a.getStepID() );
        }
        if ( deArchivo ) {
          Propuesta pr = new Propuesta( Receta.SAFE_SPLIT, s.getName() );
          pr.porQue = "A value with fewer or more parts than expected leaves nulls or shifts the columns.";
          pr.comoHacerlo = "Replace the Field splitter by a Regex evaluation with one capture group per field "
            + "(allowcapturegroups=Y) and a result field (<field>_ok); a Filter rows on it sends the rows that do "
            + "not match to the rejects with 'unexpected format of <field>'.";
          plan.propuestas.add( pr );
        }
      }
      if ( "Sequence".equals( id ) && "Y".equalsIgnoreCase( Nodos.txt( x, "use_counter" ) )
        && !"Y".equalsIgnoreCase( Nodos.txt( x, "use_database" ) ) ) {
        String campo = Nodos.txt( x, "valuename" );
        for ( StepMeta c : cargas ) {
          if ( abajo( s ).contains( c ) && textoXml( c ).contains( ">" + campo + "<" ) ) {
            Propuesta pr = new Propuesta( Receta.STABLE_SURROGATE_KEYS, s.getName() );
            pr.porQue = "'" + campo + "' is a counter that starts at 1 on every run: re-running (or a new row in the "
              + "middle) changes the keys, and the facts already loaded point to another member.";
            pr.comoHacerlo = "Database lookup of the existing key by the natural key -> Join rows with a Table input "
              + "'SELECT COALESCE(MAX(pk),0) AS max_pk FROM <dim>' -> Sequence 1..n -> Calculator pk_new = max_pk + n, "
              + "pk = NVL(existing, pk_new) (remove the temporary fields) -> the load (Insert/Update by the natural "
              + "key). Or use Dimension lookup/update with a technical key (pattern 04).";
            plan.propuestas.add( pr );
            break;
          }
        }
      }
      if ( ( "DBLookup".equals( id ) || "StreamLookup".equals( id ) ) ) {
        boolean come = "Y".equalsIgnoreCase( Nodos.txt( x, "eat_row_on_failure" ) );
        StepMeta carga = null;
        for ( StepMeta c : cargas ) {
          if ( abajo( s ).contains( c ) ) {
            // controlado: un Filter rows entre medio, o un Calculator con NVL (la clave estable de R07: si no
            // está, se numera)
            boolean filtro = false;
            for ( StepMeta m : abajo( s ) ) {
              filtro |= ( "FilterRows".equals( m.getStepID() ) || "Calculator".equals( m.getStepID() )
                && textoXml( m ).contains( "<calc_type>NVL</calc_type>" ) ) && arriba( c ).contains( m );
            }
            if ( !filtro ) {
              carga = c;
              break;
            }
          }
        }
        if ( come || carga != null ) {
          Propuesta pr = new Propuesta( Receta.ORPHANS_TO_REJECTS, s.getName() );
          pr.porQue = come ? "A row whose key is not found disappears without a trace (eat_row_on_failure=Y)."
            : "A row whose key is not found reaches '" + carga.getName() + "' with a null key.";
          pr.comoHacerlo = "After the last lookup: Filter rows '<fact> complete?' (IS NOT NULL on the looked-up "
            + "keys) -> false: a User Defined Java Expression with motivo = \"the <natural key> X is not in <table>\" "
            + "-> the rejects tail. Set eat_row_on_failure=N. If the source SQL has an INNER JOIN, make it a LEFT JOIN "
            + "so orphans reach the filter. Or default 0 = an 'unknown' member that exists in the dimension.";
          pr.nivel = Receta.MANUAL; // pasa a confirm si se puede aplicar
          huerfanos( s, pr, come );
          plan.propuestas.add( pr );
        }
      }
      if ( "TableOutput".equals( id ) && !"Y".equalsIgnoreCase( Nodos.txt( x, "truncate" ) ) ) {
        String tabla = Nodos.txt( x, "table" );
        if ( !STAGING.matcher( tabla ).matches() ) {
          Propuesta pr = new Propuesta( Receta.IDEMPOTENT_LOAD, s.getName() );
          pr.porQue = "Re-running the load inserts every row again (duplicates, or a primary key error).";
          pr.comoHacerlo = "Replace it by an Insert/Update with the same name, position and hops: keys = the natural "
            + "key (fields from the input, not from a sequence or a lookup); update every other column except the "
            + "surrogate key (update=N). For a staging table, truncate=Y instead.";
          plan.propuestas.add( pr );
        }
      }
      if ( "InsertUpdate".equals( id ) && clavesDe( s ).size() >= 2 ) {
        boolean agrupa = false;
        for ( StepMeta a : arriba( s ) ) {
          agrupa |= AGRUPAN.contains( a.getStepID() );
        }
        if ( !agrupa ) {
          Propuesta pr = new Propuesta( Receta.AGGREGATE_TO_GRAIN, s.getName() );
          pr.porQue = "Two rows with the same " + String.join( ", ", clavesDe( s ) ) + " overwrite each other in "
            + "the Insert/Update (the last one wins).";
          pr.comoHacerlo = "Memory group by " + String.join( ", ", clavesDe( s ) ) + " right before it, SUM of the "
            + "measures (ask the person: maybe MAX or AVG), declared with the attribute pentaho-live-robust/drops="
            + "aggregate.";
          plan.propuestas.add( pr );
        }
      }
    }
  }

  /** Para la respuesta: las descartadas como [{recipe, step, reason}]. */
  static ArrayNode descartadas( Plan plan, Pedido p ) {
    ArrayNode a = Tools.arr();
    for ( String[] d : plan.descartadas ) {
      if ( p.pideReceta( d[ 0 ] ) && ( p.steps == null || contieneSinMayusculas( p.steps, d[ 1 ] ) ) ) {
        ObjectNode o = a.addObject();
        o.put( "recipe", d[ 0 ] ).put( "step", d[ 1 ] ).put( "reason", d[ 2 ] );
      }
    }
    return a;
  }
}
