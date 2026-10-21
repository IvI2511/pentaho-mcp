package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.swt.graphics.FontData;
import org.pentaho.di.core.NotePadMeta;
import org.pentaho.di.core.gui.GCInterface;
import org.pentaho.di.core.gui.Point;
import org.pentaho.di.core.gui.PrimitiveGCInterface.EFont;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.ui.core.PropsUI;

/**
 * Regla de ubicacion del modo clase (docs/modo-clase-diseno.md, §4.2 a §4.4): donde va cada step y cada
 * nota de la leccion. Logica pura: no toca el documento ni Spoon; mide el texto con un {@link Medidor}
 * (en Spoon, el mismo GC y la misma fuente que usa el painter; en las pruebas, metricas fijas).
 *
 * <ol>
 * <li>Layout de clase: {@link TransLayout#calcular} tal cual (filas de 110 px) y, con su {@code col[]},
 * {@code x = 100 + col * 240} (TransLayout usa 170 y no cambia), para que entre una nota de 220 por columna.</li>
 * <li>Columnas: las de {@code col[]}; dentro de cada una, los steps con notas ordenados por {@code y}.</li>
 * <li>Bandas: la nota del step de mas arriba de la columna va en una banda sobre el flujo; las de los demas,
 * en una banda debajo. Un ojo, tip o pregunta va en la banda de su paso, del lado de afuera.</li>
 * <li>x de la nota = centro del icono - 110 (minimo 10).</li>
 * <li>Notas que ya habia (del usuario): se apilan arriba a la izquierda en filas de hasta 4 columnas, en su
 * orden, y el titulo va debajo; con {@link Previas#DEJAR} se quedan donde estan.</li>
 * <li>Alturas: titulo, banda de arriba, 20 px, flujo (se baja entero si hace falta); la banda de abajo empieza
 * en {@code y max + icono + 5 + alto del nombre + 25}, con el tamano de icono de Spoon y el nombre medido.</li>
 * <li>Verificacion: ninguna nota dibujada (+4 px) puede cortar un icono, el nombre de un step, un hop (de centro
 * a centro) u otra nota; si corta, va a una columna lateral a la derecha del flujo.</li>
 * </ol>
 * Densidad (§4.4): una columna con 3 o mas steps anotados lleva solo una nota compacta con el numero del paso,
 * arriba a la derecha del icono (o ninguna, con {@link Densidad#SIN_NOTA}); el texto completo va a la pestana
 * y a la guia. Si la compacta corta algo, queda fuera del lienzo. Mas de 12 pasos: aviso para partir en etapas.
 * <p>
 * Medidas: una nota con {@code width = W} y {@code height = H} se dibuja con {@code W + 2*5} por
 * {@code H + 2*5} px (BasePainter.drawNote suma Const.NOTE_MARGIN a cada lado), y es ese rectangulo el que se
 * verifica. El nombre de un step va centrado bajo el icono, 5 px debajo (TransPainter.getNamePosition).
 * <p>
 * Hilos: {@link #calcular} es pura y se puede llamar desde cualquier hilo si el Medidor lo permite; un
 * {@link MedidorGc} sobre un SWTGC solo en el hilo de UI.
 */
final class LeccionLayout {

  /** Separacion entre columnas en el modo clase (TransLayout usa 170). */
  static final int DX = 240;
  /** Ancho de las notas de la leccion (campo width de NotePadMeta; dibujada ocupa 10 px mas). */
  static final int ANCHO_NOTA = 220;
  /** Const.NOTE_MARGIN: BasePainter.drawNote dibuja la nota con este margen a cada lado del texto. */
  static final int MARGEN = 5;
  /** Ancho maximo de una linea de texto dentro de una nota de 220. */
  static final int ANCHO_TEXTO = ANCHO_NOTA - 2 * MARGEN - 2;
  /** Separacion vertical (y horizontal, entre notas previas) entre notas apiladas. */
  static final int SEP = 12;
  /** Aire alrededor de cada nota en la verificacion (regla 7). */
  static final int AIRE = 4;
  /** Entre el titulo, la banda de arriba y el flujo. */
  static final int AIRE_FLUJO = 20;
  /** Entre el nombre de los steps de mas abajo y la banda de abajo. */
  static final int AIRE_NOMBRE = 25;
  /** TransPainter.getNamePosition: el nombre empieza 5 px debajo del icono. */
  static final int SEP_NOMBRE = 5;
  /** Primera fila del lienzo (notas previas o titulo) y x minima de una nota. */
  static final int Y_INICIO = 20;
  static final int X_MIN = 10;
  /** Lineas maximas de una nota que no es el titulo; lo demas va a la pestana y a la guia. */
  static final int MAX_LINEAS = 6;
  /** Mas pasos que esto: aviso para partir la leccion en etapas. */
  static final int MAX_PASOS = 12;
  /** Desde cuantos steps anotados una columna es densa (nota compacta o ninguna). */
  static final int DENSA = 3;
  /** Distancia nota - step a partir de la cual se avisa que el lienzo queda poco legible. */
  static final int DISTANCIA_LEGIBLE = 300;
  /** Notas previas por fila al apilarlas arriba a la izquierda (y la fila no pasa de 4 columnas de ancho). */
  static final int PREVIAS_POR_FILA = 4;
  /** Entre lo mas a la derecha del flujo y la columna lateral. */
  static final int AIRE_LATERAL = 40;
  /** Nota compacta: separacion con el icono y altura de su borde inferior respecto del centro del icono. */
  static final int SEP_COMPACTA = 6;
  static final int SUBE_COMPACTA = 8;
  /** Ultimo recurso de la columna lateral (nunca deberia hacer falta). */
  private static final int MAX_INTENTOS_LATERAL = 1000;
  private static final String PUNTOS = "…";

  private LeccionLayout() {
  }

  // ---------------------------------------------------------------- tipos

  /** Tipo de nota (§4.1). */
  enum Tipo {
    TITULO( "title" ), PASO( "step" ), OJO( "watch_out" ), TIP( "tip" ), PREGUNTA( "question" );

    final String json;

    Tipo( String json ) {
      this.json = json;
    }
  }

  /** Donde queda una nota. */
  enum Banda {
    TITULO( "title" ), ARRIBA( "above" ), ABAJO( "below" ), COMPACTA( "compact" ), LATERAL( "side" ),
    FUERA( "off_canvas" );

    final String json;

    Banda( String json ) {
      this.json = json;
    }
  }

  /** Que hacer con las notas que ya habia (lesson_start existing_notes). */
  enum Previas {
    APILAR( "stack_top" ), DEJAR( "keep" );

    final String json;

    Previas( String json ) {
      this.json = json;
    }

    /** "stack_top" / "keep" (null = APILAR); otro valor, null. */
    static Previas deJson( String s ) {
      if ( s == null || s.trim().isEmpty() ) {
        return APILAR;
      }
      for ( Previas p : values() ) {
        if ( p.json.equalsIgnoreCase( s.trim() ) ) {
          return p;
        }
      }
      return null;
    }
  }

  /** Que va en el lienzo en una columna densa (§4.4; WP8 decide cual queda por defecto). */
  enum Densidad {
    COMPACTA, SIN_NOTA
  }

  /** Mide texto como lo dibuja Spoon. */
  interface Medidor {
    /** Ancho y alto en px de un texto (puede tener saltos de linea) con la fuente de las notas de la leccion. */
    Point texto( String texto, boolean negrita );

    /** Ancho y alto del nombre de un step con la fuente del lienzo (EFont.GRAPH, la de TransPainter). */
    Point nombre( String nombre );
  }

  /** Medidor sobre un GC de Kettle (en Spoon, un SWTGC: hilo de UI). No desecha el GC. */
  static final class MedidorGc implements Medidor {
    private final GCInterface gc;
    private final String fuente;
    private final int tam;

    MedidorGc( GCInterface gc, String fuente, int tam ) {
      this.gc = gc;
      this.fuente = fuente;
      this.tam = tam;
    }

    /** Con la fuente de notas de Spoon y el tamano pedido (font_size; -1 = el de Spoon). Hilo de UI. */
    static MedidorGc deSpoon( GCInterface gc, int tamPedido ) {
      FontData f = PropsUI.getInstance().getNoteFont();
      return new MedidorGc( gc, f.getName(), tamPedido > 0 ? tamPedido : f.getHeight() );
    }

    @Override
    public Point texto( String texto, boolean negrita ) {
      gc.setFont( fuente, tam, negrita, false );
      return gc.textExtent( texto );
    }

    @Override
    public Point nombre( String nombre ) {
      gc.setFont( EFont.GRAPH );
      return gc.textExtent( nombre );
    }
  }

  /** Una nota pedida. step es null solo en el titulo; n es el numero del paso en la leccion (0 = sin numero). */
  static final class Pedida {
    final Tipo tipo;
    final String step;
    final int n;
    final String texto;

    Pedida( Tipo tipo, String step, int n, String texto ) {
      this.tipo = tipo;
      this.step = step;
      this.n = n;
      this.texto = texto == null ? "" : texto;
    }
  }

  /** Una nota que ya estaba en el lienzo: x, y y los campos width/height de NotePadMeta (sin el margen). */
  static final class Previa {
    final Object ref;
    final int x;
    final int y;
    final int ancho;
    final int alto;

    Previa( Object ref, int x, int y, int ancho, int alto ) {
      this.ref = ref;
      this.x = x;
      this.y = y;
      this.ancho = ancho;
      this.alto = alto;
    }
  }

  /** Lo que entra: el grafo planificado (steps que ya existen y los que todavia no), las notas y las previas. */
  static final class Entrada {
    final List<String> steps = new ArrayList<String>();
    /** Posicion actual de cada step, o null si todavia no existe. */
    final List<Point> actuales = new ArrayList<Point>();
    final List<String[]> hops = new ArrayList<String[]>();
    final List<Pedida> notas = new ArrayList<Pedida>();
    final List<Previa> previas = new ArrayList<Previa>();
    Previas modoPrevias = Previas.APILAR;
    Densidad densidad = Densidad.COMPACTA;
    int icono = tamIcono();

    Entrada step( String nombre, Point actual ) {
      steps.add( nombre );
      actuales.add( actual == null ? null : new Point( actual.x, actual.y ) );
      return this;
    }

    Entrada hop( String desde, String hacia ) {
      hops.add( new String[] { desde, hacia } );
      return this;
    }

    Pedida nota( Tipo tipo, String step, int n, String texto ) {
      Pedida p = new Pedida( tipo, step, n, texto );
      notas.add( p );
      return p;
    }

    Previa previa( Object ref, int x, int y, int ancho, int alto ) {
      Previa p = new Previa( ref, x, y, ancho, alto );
      previas.add( p );
      return p;
    }
  }

  /** Una nota ubicada. ancho/alto son los campos width/height de NotePadMeta; dibujada ocupa 2*MARGEN mas. */
  static final class Ubicada {
    final Pedida pedida;
    Banda banda;
    /** Texto con los saltos de linea del corte (lo que va al lienzo); null si queda fuera del lienzo. */
    String texto;
    boolean negrita;
    int x;
    int y;
    int ancho;
    int alto;
    /** Se corto a MAX_LINEAS. */
    boolean recortada;
    /** Px entre la nota dibujada y el icono de su step; -1 sin step o fuera del lienzo. */
    int distancia = -1;
    /** Por que fue a la columna lateral o quedo fuera (en ingles, para los avisos), o null. */
    String motivo;

    Ubicada( Pedida pedida ) {
      this.pedida = pedida;
    }

    boolean enLienzo() {
      return banda != Banda.FUERA;
    }

    /** Rectangulo dibujado {x, y, ancho, alto}. */
    int[] rect() {
      return new int[] { x, y, ancho + 2 * MARGEN, alto + 2 * MARGEN };
    }
  }

  /** Lo que sale. Listas y arreglos alineados con la Entrada. */
  static final class Plan {
    final List<String> steps;
    final Point[] posiciones;
    final int[] columna;
    /** Banda de la nota principal de cada step, o null si el step no lleva notas. */
    final Banda[] bandaStep;
    final List<Ubicada> notas = new ArrayList<Ubicada>();
    /** Posicion final de cada nota previa. */
    final Point[] previas;
    final List<String> avisos = new ArrayList<String>();
    int columnas;
    int icono;
    /** y donde empiezan la banda de arriba (o el flujo) y la de abajo. */
    int yBandaArriba;
    int yBandaAbajo;
    /** x de la columna lateral, o -1 si no hizo falta. */
    int xLateral = -1;

    Plan( List<String> steps, int previas ) {
      this.steps = new ArrayList<String>( steps );
      this.posiciones = new Point[ steps.size() ];
      this.columna = new int[ steps.size() ];
      this.bandaStep = new Banda[ steps.size() ];
      this.previas = new Point[ previas ];
    }

    /** Posicion planificada de un step (sin distinguir mayusculas), o null. */
    Point posicion( String step ) {
      int i = indice( steps, step );
      return i < 0 ? null : posiciones[ i ];
    }

    int columna( String step ) {
      int i = indice( steps, step );
      return i < 0 ? -1 : columna[ i ];
    }

    Banda banda( String step ) {
      int i = indice( steps, step );
      return i < 0 ? null : bandaStep[ i ];
    }

    /** La nota ubicada para una pedida (por identidad). */
    Ubicada nota( Pedida p ) {
      for ( Ubicada u : notas ) {
        if ( u.pedida == p ) {
          return u;
        }
      }
      return null;
    }
  }

  // ---------------------------------------------------------------- entrada desde un documento

  /** Tamano de icono de Spoon (Opciones), o 32 si PropsUI no esta (pruebas, Pan). */
  static int tamIcono() {
    try {
      int i = PropsUI.getInstance().getIconSize();
      return i > 0 ? i : 32;
    } catch ( Throwable sinPropsUi ) {
      return 32;
    }
  }

  /**
   * Entrada con los steps, los hops y las notas de una transformacion; las notas de 'deLeccion' (por
   * identidad) no van como previas. Las previas se miden con la fuente de la leccion como piso del width/height
   * que dejo el painter.
   */
  static Entrada desde( TransMeta tm, Medidor m, Collection<NotePadMeta> deLeccion ) {
    Entrada e = new Entrada();
    for ( StepMeta s : tm.getSteps() ) {
      e.step( s.getName(), s.getLocation() );
    }
    for ( int i = 0; i < tm.nrTransHops(); i++ ) {
      TransHopMeta h = tm.getTransHop( i );
      if ( h.getFromStep() != null && h.getToStep() != null ) {
        e.hop( h.getFromStep().getName(), h.getToStep().getName() );
      }
    }
    Map<NotePadMeta, Boolean> fuera = new IdentityHashMap<NotePadMeta, Boolean>();
    if ( deLeccion != null ) {
      for ( NotePadMeta n : deLeccion ) {
        fuera.put( n, Boolean.TRUE );
      }
    }
    for ( int i = 0; i < tm.nrNotes(); i++ ) {
      NotePadMeta n = tm.getNote( i );
      if ( fuera.containsKey( n ) ) {
        continue;
      }
      String t = n.getNote();
      Point ext = t == null || t.isEmpty() ? new Point( 10, 10 ) : m.texto( t, n.isFontBold() );
      Point loc = n.getLocation();
      e.previa( n, loc == null ? 0 : loc.x, loc == null ? 0 : loc.y, Math.max( n.getWidth(), ext.x + 2 * MARGEN ),
        Math.max( n.getHeight(), ext.y + 2 * MARGEN ) );
    }
    return e;
  }

  // ---------------------------------------------------------------- la regla

  /**
   * Calcula el plan. Lanza IllegalArgumentException (mensaje en ingles) si la entrada es incoherente: nombres
   * de step repetidos, un hop o una nota que nombra un step que no esta, dos titulos.
   */
  static Plan calcular( Entrada e, Medidor m ) {
    final int n = e.steps.size();
    final int icono = e.icono > 0 ? e.icono : 32;
    Plan plan = new Plan( e.steps, e.previas.size() );
    plan.icono = icono;

    Map<String, Integer> idx = new HashMap<String, Integer>();
    for ( int i = 0; i < n; i++ ) {
      String k = clave( e.steps.get( i ) );
      if ( k.isEmpty() ) {
        throw new IllegalArgumentException( "Step " + i + " has no name" );
      }
      if ( idx.put( k, i ) != null ) {
        throw new IllegalArgumentException( "Duplicate step name: '" + e.steps.get( i ) + "'" );
      }
    }
    List<int[]> aristas = new ArrayList<int[]>();
    for ( String[] h : e.hops ) {
      aristas.add( new int[] { indiceONada( idx, h[ 0 ], "hop" ), indiceONada( idx, h[ 1 ], "hop" ) } );
    }

    // 1. layout de clase: filas de TransLayout, columnas de 240
    int[] col = new int[ n ];
    Point[] base = TransLayout.calcular( e.actuales, aristas, -1, col );
    final int x0 = x0( icono );
    for ( int i = 0; i < n; i++ ) {
      plan.posiciones[ i ] = new Point( x0 + col[ i ] * DX, base[ i ].y );
      plan.columna[ i ] = col[ i ];
      plan.columnas = Math.max( plan.columnas, col[ i ] + 1 );
    }

    // notas por step: primero las de paso (pegadas al flujo), despues ojo/tip/pregunta en su orden
    Ubicada titulo = null;
    Map<Integer, List<Ubicada>> porStep = new HashMap<Integer, List<Ubicada>>();
    List<Integer> ordenSteps = new ArrayList<Integer>(); // steps anotados, en el orden de la leccion
    for ( Pedida p : e.notas ) {
      Ubicada u = new Ubicada( p );
      plan.notas.add( u );
      if ( p.tipo == Tipo.TITULO ) {
        if ( titulo != null ) {
          throw new IllegalArgumentException( "A lesson has only one title note" );
        }
        titulo = u;
        continue;
      }
      if ( p.step == null ) {
        throw new IllegalArgumentException( "A " + p.tipo.json + " note needs a step" );
      }
      int s = indiceONada( idx, p.step, "note" );
      List<Ubicada> l = porStep.get( s );
      if ( l == null ) {
        porStep.put( s, l = new ArrayList<Ubicada>() );
        ordenSteps.add( s );
      }
      l.add( u );
    }
    for ( List<Ubicada> l : porStep.values() ) {
      List<Ubicada> pasos = new ArrayList<Ubicada>();
      List<Ubicada> resto = new ArrayList<Ubicada>();
      for ( Ubicada u : l ) {
        ( u.pedida.tipo == Tipo.PASO ? pasos : resto ).add( u );
      }
      l.clear();
      l.addAll( pasos );
      l.addAll( resto );
    }

    // 2. columnas: steps anotados de cada una, por y
    final Point[] pos = plan.posiciones;
    Map<Integer, List<Integer>> anotados = new HashMap<Integer, List<Integer>>();
    for ( int s : ordenSteps ) {
      List<Integer> l = anotados.get( col[ s ] );
      if ( l == null ) {
        anotados.put( col[ s ], l = new ArrayList<Integer>() );
      }
      l.add( s );
    }
    List<Integer> columnasAnotadas = new ArrayList<Integer>( anotados.keySet() );
    Collections.sort( columnasAnotadas );
    for ( List<Integer> l : anotados.values() ) {
      Collections.sort( l, new Comparator<Integer>() {
        @Override
        public int compare( Integer a, Integer b ) {
          int c = Integer.compare( pos[ a ].y, pos[ b ].y );
          return c != 0 ? c : a.compareTo( b );
        }
      } );
    }

    // 3. bandas (y densidad) y medidas
    Map<Integer, List<Ubicada>> arriba = new HashMap<Integer, List<Ubicada>>();
    Map<Integer, List<Ubicada>> abajo = new HashMap<Integer, List<Ubicada>>();
    List<Ubicada> compactas = new ArrayList<Ubicada>();
    for ( int c : columnasAnotadas ) {
      List<Integer> l = anotados.get( c );
      boolean densa = l.size() >= DENSA;
      if ( densa ) {
        List<String> nombres = new ArrayList<String>();
        for ( int s : l ) {
          nombres.add( "'" + e.steps.get( s ) + "'" );
        }
        plan.avisos.add( "Steps " + String.join( ", ", nombres ) + " share a column: "
          + ( e.densidad == Densidad.COMPACTA ? "only their step number goes on the canvas (compact note)"
          : "their notes stay off the canvas" ) + "; the full text goes to the Lesson tab and the handout." );
      }
      for ( int k = 0; k < l.size(); k++ ) {
        int s = l.get( k );
        List<Ubicada> notas = porStep.get( s );
        if ( densa ) {
          Ubicada principal = notas.get( 0 );
          for ( Ubicada u : notas ) {
            u.banda = Banda.FUERA;
          }
          if ( e.densidad == Densidad.COMPACTA ) {
            principal.banda = Banda.COMPACTA;
            int numero = principal.pedida.n > 0 ? principal.pedida.n : ordenSteps.indexOf( s ) + 1;
            principal.texto = String.valueOf( numero );
            principal.negrita = true;
            Point ext = m.texto( principal.texto, true );
            principal.ancho = ext.x + 2 * MARGEN;
            principal.alto = ext.y + 2 * MARGEN;
            compactas.add( principal );
          }
          plan.bandaStep[ s ] = principal.banda;
          continue;
        }
        Banda b = k == 0 ? Banda.ARRIBA : Banda.ABAJO;
        plan.bandaStep[ s ] = b;
        for ( Ubicada u : notas ) {
          u.banda = b;
          medir( u, m, ANCHO_NOTA, false, MAX_LINEAS );
        }
        Map<Integer, List<Ubicada>> banda = b == Banda.ARRIBA ? arriba : abajo;
        if ( !banda.containsKey( c ) ) {
          banda.put( c, new ArrayList<Ubicada>() );
        }
        banda.get( c ).addAll( notas ); // abajo, en el orden de y de sus steps
      }
    }
    if ( titulo != null ) {
      titulo.banda = Banda.TITULO;
      medir( titulo, m, ANCHO_NOTA + DX * Math.min( 2, Math.max( 0, plan.columnas - 1 ) ), true, Integer.MAX_VALUE );
    }

    // 5. notas previas: apiladas arriba a la izquierda, en filas, en su orden (o donde estaban)
    int y = Y_INICIO;
    if ( e.modoPrevias == Previas.APILAR && !e.previas.isEmpty() ) {
      int xFila = X_MIN;
      int yFila = Y_INICIO;
      int altoFila = 0;
      int enFila = 0;
      for ( int i = 0; i < e.previas.size(); i++ ) {
        Previa p = e.previas.get( i );
        int w = p.ancho + 2 * MARGEN;
        int h = p.alto + 2 * MARGEN;
        if ( enFila > 0 && ( enFila >= PREVIAS_POR_FILA || xFila + w > X_MIN + PREVIAS_POR_FILA * DX ) ) {
          xFila = X_MIN;
          yFila += altoFila + SEP;
          altoFila = 0;
          enFila = 0;
        }
        plan.previas[ i ] = new Point( xFila, yFila );
        xFila += w + SEP;
        altoFila = Math.max( altoFila, h );
        enFila++;
      }
      y = yFila + altoFila + AIRE_FLUJO;
      plan.avisos.add( e.previas.size() + " existing note" + ( e.previas.size() == 1 ? " was" : "s were" )
        + " stacked at the top left: they no longer sit next to the step they described"
        + " (existing_notes \"keep\" leaves them in place)." );
    } else {
      for ( int i = 0; i < e.previas.size(); i++ ) {
        plan.previas[ i ] = new Point( e.previas.get( i ).x, e.previas.get( i ).y );
      }
    }

    // 6. alturas: titulo, banda de arriba, 20 px, flujo; la banda de abajo debajo de los nombres
    if ( titulo != null ) {
      titulo.x = xNota( x0 + icono / 2 );
      titulo.y = y;
      y += titulo.alto + 2 * MARGEN + AIRE_FLUJO;
    }
    plan.yBandaArriba = y;
    int altoArriba = 0;
    for ( List<Ubicada> l : arriba.values() ) {
      altoArriba = Math.max( altoArriba, altoPila( l ) );
    }
    int saliente = 0; // lo que una nota compacta sube por encima de su icono
    for ( Ubicada u : compactas ) {
      saliente = Math.max( saliente, u.alto + 2 * MARGEN + SUBE_COMPACTA - icono / 2 );
    }
    int hace = 0;
    if ( altoArriba > 0 ) {
      hace = altoArriba + AIRE_FLUJO;
    }
    if ( saliente > 0 ) {
      hace = Math.max( hace, saliente + SEP );
    }
    int minY = Integer.MAX_VALUE;
    int maxY = Integer.MIN_VALUE;
    for ( Point p : pos ) {
      minY = Math.min( minY, p.y );
      maxY = Math.max( maxY, p.y );
    }
    if ( n > 0 ) {
      int dy = Math.max( 0, plan.yBandaArriba + hace - minY ); // el flujo se baja entero si hace falta
      for ( Point p : pos ) {
        p.y += dy;
      }
      minY += dy;
      maxY += dy;
    }
    int[][] nombres = new int[ n ][];
    int altoNombre = 0;
    for ( int i = 0; i < n; i++ ) {
      Point ext = m.nombre( e.steps.get( i ) );
      nombres[ i ] = new int[] { pos[ i ].x + icono / 2 - ext.x / 2, pos[ i ].y + icono + SEP_NOMBRE, ext.x, ext.y };
      altoNombre = Math.max( altoNombre, ext.y );
    }
    plan.yBandaAbajo = n == 0 ? y : maxY + icono + SEP_NOMBRE + altoNombre + AIRE_NOMBRE;

    // 3-4. notas en las bandas: la del paso pegada al flujo, ojo/tip/pregunta del lado de afuera
    for ( Map.Entry<Integer, List<Ubicada>> en : arriba.entrySet() ) {
      int yy = minY - AIRE_FLUJO;
      for ( Ubicada u : en.getValue() ) {
        yy -= u.alto + 2 * MARGEN;
        u.x = xNota( x0 + en.getKey() * DX + icono / 2 );
        u.y = yy;
        yy -= SEP;
      }
    }
    for ( Map.Entry<Integer, List<Ubicada>> en : abajo.entrySet() ) {
      int yy = plan.yBandaAbajo;
      for ( Ubicada u : en.getValue() ) {
        u.x = xNota( x0 + en.getKey() * DX + icono / 2 );
        u.y = yy;
        yy += u.alto + 2 * MARGEN + SEP;
      }
    }
    for ( int s = 0; s < n; s++ ) {
      List<Ubicada> l = porStep.get( s );
      if ( l != null && l.get( 0 ).banda == Banda.COMPACTA ) {
        Ubicada u = l.get( 0 );
        u.x = pos[ s ].x + icono + SEP_COMPACTA;
        u.y = pos[ s ].y + icono / 2 - SUBE_COMPACTA - ( u.alto + 2 * MARGEN );
      }
    }

    // 7. verificacion: iconos, nombres, hops, previas y las notas ya aceptadas
    List<Obstaculo> fijos = new ArrayList<Obstaculo>();
    for ( int i = 0; i < n; i++ ) {
      fijos.add( Obstaculo.rect( new int[] { pos[ i ].x, pos[ i ].y, icono, icono },
        "the icon of step '" + e.steps.get( i ) + "'" ) );
      fijos.add( Obstaculo.rect( nombres[ i ], "the name of step '" + e.steps.get( i ) + "'" ) );
    }
    for ( int[] a : aristas ) {
      if ( a[ 0 ] >= 0 && a[ 1 ] >= 0 && a[ 0 ] != a[ 1 ] ) {
        fijos.add( Obstaculo.segmento( new int[] { pos[ a[ 0 ] ].x + icono / 2, pos[ a[ 0 ] ].y + icono / 2,
          pos[ a[ 1 ] ].x + icono / 2, pos[ a[ 1 ] ].y + icono / 2 },
          "the hop '" + e.steps.get( a[ 0 ] ) + "' -> '" + e.steps.get( a[ 1 ] ) + "'" ) );
      }
    }
    for ( int i = 0; i < e.previas.size(); i++ ) {
      Previa p = e.previas.get( i );
      fijos.add( Obstaculo.rect( new int[] { plan.previas[ i ].x, plan.previas[ i ].y, p.ancho + 2 * MARGEN,
        p.alto + 2 * MARGEN }, "an existing note" ) );
    }
    List<Obstaculo> aceptadas = new ArrayList<Obstaculo>();
    List<Ubicada> aLateral = new ArrayList<Ubicada>();
    for ( Ubicada u : plan.notas ) {
      if ( !u.enLienzo() ) {
        continue;
      }
      Obstaculo o = choque( u.rect(), fijos, aceptadas );
      if ( o == null ) {
        aceptadas.add( Obstaculo.rect( u.rect(), quien( u ) ) );
      } else if ( u.banda == Banda.COMPACTA ) {
        u.banda = Banda.FUERA;
        u.motivo = "overlapped " + o.quien;
        plan.bandaStep[ idx.get( clave( u.pedida.step ) ) ] = Banda.FUERA;
        plan.avisos.add( "The compact note of step '" + u.pedida.step + "' overlapped " + o.quien
          + ": it stays off the canvas (Lesson tab and handout only)." );
      } else {
        u.motivo = "overlapped " + o.quien;
        aLateral.add( u );
      }
    }
    if ( !aLateral.isEmpty() ) {
      int xMax = X_MIN;
      for ( int i = 0; i < n; i++ ) {
        xMax = Math.max( xMax, pos[ i ].x + icono );
        xMax = Math.max( xMax, nombres[ i ][ 0 ] + nombres[ i ][ 2 ] );
      }
      for ( Ubicada u : plan.notas ) {
        if ( u.enLienzo() && u.banda != Banda.TITULO && !aLateral.contains( u ) ) {
          xMax = Math.max( xMax, u.x + u.ancho + 2 * MARGEN );
        }
      }
      plan.xLateral = xMax + AIRE_LATERAL;
      for ( Ubicada u : aLateral ) {
        u.x = plan.xLateral;
        u.y = plan.yBandaArriba;
        for ( int intento = 0; intento < MAX_INTENTOS_LATERAL; intento++ ) {
          Obstaculo o = choque( u.rect(), fijos, aceptadas );
          if ( o == null ) {
            break;
          }
          u.y = o.abajo() + Math.max( SEP, AIRE + 1 );
        }
        plan.avisos.add( mayuscula( quien( u ) ) + " " + u.motivo + ": moved to a side column at the right of the flow." );
        if ( u.pedida.tipo == Tipo.PASO ) {
          plan.bandaStep[ idx.get( clave( u.pedida.step ) ) ] = Banda.LATERAL;
        }
        u.banda = Banda.LATERAL;
        aceptadas.add( Obstaculo.rect( u.rect(), quien( u ) ) );
      }
    }

    // distancias y avisos de legibilidad
    int lejos = 0;
    for ( Ubicada u : plan.notas ) {
      if ( u.enLienzo() && u.pedida.step != null ) {
        Point p = pos[ idx.get( clave( u.pedida.step ) ) ];
        u.distancia = distancia( u.rect(), new int[] { p.x, p.y, icono, icono } );
        if ( u.distancia > DISTANCIA_LEGIBLE ) {
          lejos++;
        }
      }
      if ( u.recortada && u.enLienzo() ) {
        plan.avisos.add( mayuscula( quien( u ) ) + " was cut to " + MAX_LINEAS
          + " lines on the canvas; the full text stays in the Lesson tab and the handout." );
      }
    }
    if ( lejos > 0 ) {
      plan.avisos.add( lejos + " note" + ( lejos == 1 ? " ends" : "s end" ) + " up more than " + DISTANCIA_LEGIBLE
        + " px from its step (dense canvas): consider splitting the lesson into stages." );
    }
    int pasos = 0;
    for ( List<Ubicada> l : porStep.values() ) {
      if ( l.get( 0 ).pedida.tipo == Tipo.PASO ) {
        pasos++;
      }
    }
    if ( pasos > MAX_PASOS ) {
      plan.avisos.add( "The lesson has " + pasos + " steps (more than " + MAX_PASOS + "): consider splitting it"
        + " into stages, with one note per stage (\"Steps 1-5: read and clean the data\")." );
    }
    return plan;
  }

  /**
   * x de la primera columna: 100 (TransLayout.X0). Con iconos de menos de 28 px la nota de la columna 0 se
   * topa con X_MIN y quedaria a menos de AIRE de la de la columna 1: ahi el flujo se corre unos px a la derecha.
   */
  static int x0( int icono ) {
    // nota 0 en X_MIN: termina en X_MIN + ANCHO_NOTA + 2*MARGEN; la 1 empieza en x0 + DX + icono/2 - ANCHO_NOTA/2
    return Math.max( TransLayout.X0, X_MIN + ANCHO_NOTA + 2 * MARGEN + AIRE - DX + ANCHO_NOTA / 2 - icono / 2 );
  }

  /** Regla 4: centro del icono - 110, minimo 10. */
  static int xNota( int centro ) {
    return Math.max( X_MIN, centro - ANCHO_NOTA / 2 );
  }

  // ---------------------------------------------------------------- texto

  /** Corta el texto de la nota, lo mide y deja ancho/alto (campos de NotePadMeta). */
  private static void medir( Ubicada u, Medidor m, int ancho, boolean negrita, int maxLineas ) {
    List<String> lineas = envolver( m, u.pedida.texto, negrita, ancho - 2 * MARGEN - 2 );
    if ( lineas.size() > maxLineas ) {
      lineas = recortar( m, lineas, maxLineas, negrita, ancho - 2 * MARGEN - 2 );
      u.recortada = true;
    }
    u.texto = String.join( "\n", lineas );
    u.negrita = negrita;
    Point ext = u.texto.isEmpty() ? new Point( 10, 10 ) : m.texto( u.texto, negrita );
    u.ancho = Math.max( ancho, ext.x + 2 * MARGEN );
    u.alto = ext.y + 2 * MARGEN;
  }

  /**
   * Corte de linea por palabras midiendo cada linea con el Medidor (nunca por cantidad de letras): cada linea
   * mide como mucho 'ancho' px. Una palabra mas ancha (una ruta, un nombre_con_guiones) se corta despues del
   * ultimo / _ . \ que entre, y si no hay, por letra. Respeta los saltos de linea del texto.
   */
  static List<String> envolver( Medidor m, String texto, boolean negrita, int ancho ) {
    List<String> out = new ArrayList<String>();
    String t = texto == null ? "" : texto.replace( "\r\n", "\n" ).replace( '\r', '\n' ).replace( '\t', ' ' );
    for ( String parrafo : t.split( "\n", -1 ) ) {
      String limpio = parrafo.trim();
      if ( limpio.isEmpty() ) {
        out.add( "" );
        continue;
      }
      String linea = "";
      for ( String palabra : limpio.split( " +" ) ) {
        String prueba = linea.isEmpty() ? palabra : linea + " " + palabra;
        if ( m.texto( prueba, negrita ).x <= ancho ) {
          linea = prueba;
          continue;
        }
        if ( !linea.isEmpty() ) {
          out.add( linea );
        }
        String resto = palabra;
        while ( resto.length() > 1 && m.texto( resto, negrita ).x > ancho ) {
          int corte = cortePalabra( m, resto, negrita, ancho );
          out.add( resto.substring( 0, corte ) );
          resto = resto.substring( corte );
        }
        linea = resto;
      }
      out.add( linea );
    }
    while ( out.size() > 1 && out.get( out.size() - 1 ).isEmpty() ) {
      out.remove( out.size() - 1 );
    }
    return out;
  }

  /** Cuantas letras de p van en la linea: hasta el ultimo / _ . \ que entra, o las que entren (minimo 1). */
  static int cortePalabra( Medidor m, String p, boolean negrita, int ancho ) {
    int entra = 1;
    while ( entra < p.length() && m.texto( p.substring( 0, entra + 1 ), negrita ).x <= ancho ) {
      entra++;
    }
    for ( int i = entra; i > 1; i-- ) {
      char c = p.charAt( i - 1 );
      if ( c == '/' || c == '_' || c == '.' || c == '\\' ) {
        return i;
      }
    }
    return entra;
  }

  /** Las primeras maxLineas, la ultima terminada en "..." (una sola letra) y que entre en el ancho. */
  private static List<String> recortar( Medidor m, List<String> lineas, int maxLineas, boolean negrita, int ancho ) {
    List<String> r = new ArrayList<String>( lineas.subList( 0, maxLineas ) );
    String ultima = r.get( maxLineas - 1 ).replaceAll( "\\s+$", "" );
    while ( !ultima.isEmpty() && m.texto( ultima + PUNTOS, negrita ).x > ancho ) {
      ultima = ultima.substring( 0, ultima.length() - 1 ).replaceAll( "\\s+$", "" );
    }
    r.set( maxLineas - 1, ultima + PUNTOS );
    return r;
  }

  // ---------------------------------------------------------------- geometria

  /** Algo que una nota no puede cortar: un rectangulo o un hop (segmento de centro a centro). */
  private static final class Obstaculo {
    final int[] r;
    final int[] seg;
    final String quien;

    private Obstaculo( int[] r, int[] seg, String quien ) {
      this.r = r;
      this.seg = seg;
      this.quien = quien;
    }

    static Obstaculo rect( int[] r, String quien ) {
      return new Obstaculo( r, null, quien );
    }

    static Obstaculo segmento( int[] s, String quien ) {
      return new Obstaculo( null, s, quien );
    }

    boolean corta( int[] nota ) {
      return r != null ? cortan( nota, r ) : segmentoCorta( seg, nota );
    }

    /** y de lo mas bajo del obstaculo. */
    int abajo() {
      return r != null ? r[ 1 ] + r[ 3 ] : Math.max( seg[ 1 ], seg[ 3 ] );
    }
  }

  /** El primer obstaculo que corta la nota (inflada AIRE px), o null. */
  private static Obstaculo choque( int[] nota, List<Obstaculo> fijos, List<Obstaculo> aceptadas ) {
    int[] r = inflar( nota, AIRE );
    for ( Obstaculo o : fijos ) {
      if ( o.corta( r ) ) {
        return o;
      }
    }
    for ( Obstaculo o : aceptadas ) {
      if ( o.corta( r ) ) {
        return o;
      }
    }
    return null;
  }

  static int[] inflar( int[] r, int d ) {
    return new int[] { r[ 0 ] - d, r[ 1 ] - d, r[ 2 ] + 2 * d, r[ 3 ] + 2 * d };
  }

  /** Dos rectangulos {x, y, ancho, alto} se cortan (tocarse en el borde no cuenta). */
  static boolean cortan( int[] a, int[] b ) {
    return a[ 0 ] < b[ 0 ] + b[ 2 ] && b[ 0 ] < a[ 0 ] + a[ 2 ] && a[ 1 ] < b[ 1 ] + b[ 3 ] && b[ 1 ] < a[ 1 ] + a[ 3 ];
  }

  /** El segmento {x1, y1, x2, y2} toca el rectangulo {x, y, ancho, alto} (recorte de Liang-Barsky). */
  static boolean segmentoCorta( int[] s, int[] r ) {
    double x1 = s[ 0 ];
    double y1 = s[ 1 ];
    double dx = s[ 2 ] - x1;
    double dy = s[ 3 ] - y1;
    double[] p = { -dx, dx, -dy, dy };
    double[] q = { x1 - r[ 0 ], r[ 0 ] + r[ 2 ] - x1, y1 - r[ 1 ], r[ 1 ] + r[ 3 ] - y1 };
    double t0 = 0;
    double t1 = 1;
    for ( int i = 0; i < 4; i++ ) {
      if ( p[ i ] == 0 ) {
        if ( q[ i ] < 0 ) {
          return false;
        }
      } else {
        double t = q[ i ] / p[ i ];
        if ( p[ i ] < 0 ) {
          t0 = Math.max( t0, t );
        } else {
          t1 = Math.min( t1, t );
        }
        if ( t0 > t1 ) {
          return false;
        }
      }
    }
    return true;
  }

  /** Distancia en px entre dos rectangulos (0 si se tocan). */
  static int distancia( int[] a, int[] b ) {
    int dx = Math.max( 0, Math.max( b[ 0 ] - ( a[ 0 ] + a[ 2 ] ), a[ 0 ] - ( b[ 0 ] + b[ 2 ] ) ) );
    int dy = Math.max( 0, Math.max( b[ 1 ] - ( a[ 1 ] + a[ 3 ] ), a[ 1 ] - ( b[ 1 ] + b[ 3 ] ) ) );
    return (int) Math.round( Math.sqrt( (double) dx * dx + (double) dy * dy ) );
  }

  // ---------------------------------------------------------------- ayudas

  private static int altoPila( List<Ubicada> l ) {
    int h = 0;
    for ( Ubicada u : l ) {
      h += u.alto + 2 * MARGEN + SEP;
    }
    return l.isEmpty() ? 0 : h - SEP;
  }

  private static String quien( Ubicada u ) {
    return u.pedida.tipo == Tipo.TITULO ? "the title note"
      : "the " + tipoTexto( u.pedida.tipo ) + " note of step '" + u.pedida.step + "'";
  }

  private static String tipoTexto( Tipo t ) {
    return t.json.replace( '_', '-' );
  }

  private static String mayuscula( String s ) {
    return s.isEmpty() ? s : Character.toUpperCase( s.charAt( 0 ) ) + s.substring( 1 );
  }

  private static String clave( String nombre ) {
    return nombre == null ? "" : nombre.trim().toLowerCase( Locale.ROOT );
  }

  private static int indiceONada( Map<String, Integer> idx, String nombre, String que ) {
    Integer i = idx.get( clave( nombre ) );
    if ( i == null ) {
      throw new IllegalArgumentException( "The " + que + " names a step that is not in the plan: '" + nombre + "'" );
    }
    return i;
  }

  private static int indice( List<String> steps, String nombre ) {
    String k = clave( nombre );
    for ( int i = 0; i < steps.size(); i++ ) {
      if ( clave( steps.get( i ) ).equals( k ) ) {
        return i;
      }
    }
    return -1;
  }
}
