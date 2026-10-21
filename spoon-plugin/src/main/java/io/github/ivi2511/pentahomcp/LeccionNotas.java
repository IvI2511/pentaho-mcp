package io.github.ivi2511.pentahomcp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;

import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.pentaho.di.core.NotePadMeta;
import org.pentaho.di.core.gui.Point;
import org.pentaho.di.core.xml.XMLHandler;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.ui.core.PropsUI;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import io.github.ivi2511.pentahomcp.LeccionLayout.Banda;
import io.github.ivi2511.pentahomcp.LeccionLayout.Medidor;
import io.github.ivi2511.pentahomcp.LeccionLayout.Plan;
import io.github.ivi2511.pentahomcp.LeccionLayout.Previas;
import io.github.ivi2511.pentahomcp.LeccionLayout.Tipo;
import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.LeccionLayout.Ubicada;

/**
 * Modo clase, WP2: las notas de la leccion en el lienzo (docs/modo-clase-diseno.md §4.1 y §4.5). Estilos por tipo,
 * etiquetas fijas en en/es/pt, el registro en los atributos del documento (grupo {@value #GRUPO}) con huellas CRC32
 * del texto sin espacios, y las operaciones de lesson_notes: add, list, hide, show, remove (+purge), restyle y
 * relayout, cada una UNA accion de undo (un Ctrl+Z) a traves de {@link UndoGrupo}.
 * <p>
 * El registro solo crece (los atributos no entran en el undo de Spoon): {@code note.K = huella|tipo|step} por nota
 * (se parte con limite 3: un nombre de step puede tener '|'); {@code alt.K} guarda las huellas de los textos que la
 * nota tuvo despues de un restyle o relayout (separadas por coma), asi un Ctrl+Z que devuelve el texto anterior no
 * la convierte en una nota del usuario; {@code src.K} es el texto sin cortar (lo que se vuelve a cortar con otra
 * fuente). Una nota cuyo texto el usuario edito deja de reconocerse y pasa a ser suya (a proposito).
 * <p>
 * Solo transformaciones (jobs: fase 2). Hilos: lo que toca el documento, en el hilo de UI (o sin Spoon, en las
 * pruebas); {@link #medidor(int)} usa un GC de SWT si hay Display en el hilo y, si no, metricas aproximadas.
 */
final class LeccionNotas {

  static final String GRUPO = "pentaho_live_lesson";
  static final String SCHEMA = "1";
  static final String K_SCHEMA = "schema";
  static final String K_NOTAS = "notes";
  static final String K_IDIOMA = "language";
  static final String K_FUENTE = "font_size";
  static final String K_OCULTAS = "hidden";
  static final String P_NOTA = "note.";
  static final String P_ALT = "alt.";
  static final String P_SRC = "src.";

  private LeccionNotas() {
  }

  // ---------------------------------------------------------------- estilos (§4.1)

  /** {fondo, borde, letra} en RGB por tipo. */
  static int[][] estilo( Tipo t ) {
    switch ( t ) {
      case TITULO:
        return new int[][] { { 221, 235, 247 }, { 70, 110, 160 }, { 20, 40, 80 } };
      case OJO:
        return new int[][] { { 253, 226, 222 }, { 190, 70, 60 }, { 110, 20, 10 } };
      case TIP:
        return new int[][] { { 228, 243, 228 }, { 80, 140, 80 }, { 20, 60, 20 } };
      case PREGUNTA:
        return new int[][] { { 240, 232, 250 }, { 120, 90, 170 }, { 50, 30, 90 } };
      case PASO:
      default:
        return new int[][] { { 255, 248, 214 }, { 190, 160, 70 }, { 40, 35, 20 } };
    }
  }

  /** Aplica el estilo del tipo: colores, sin sombra, negrita solo en el titulo, tamano de fuente (-1 = Spoon). */
  static void estilizar( NotePadMeta n, Tipo t, int tamFuente ) {
    int[][] e = estilo( t );
    n.setBackGroundColorRed( e[ 0 ][ 0 ] );
    n.setBackGroundColorGreen( e[ 0 ][ 1 ] );
    n.setBackGroundColorBlue( e[ 0 ][ 2 ] );
    n.setBorderColorRed( e[ 1 ][ 0 ] );
    n.setBorderColorGreen( e[ 1 ][ 1 ] );
    n.setBorderColorBlue( e[ 1 ][ 2 ] );
    n.setFontColorRed( e[ 2 ][ 0 ] );
    n.setFontColorGreen( e[ 2 ][ 1 ] );
    n.setFontColorBlue( e[ 2 ][ 2 ] );
    n.setDrawShadow( false );
    n.setFontBold( t == Tipo.TITULO );
    n.setFontItalic( false );
    n.setFontSize( tamFuente > 0 ? tamFuente : -1 );
  }

  // ---------------------------------------------------------------- etiquetas fijas (§4.2)

  private static final Map<String, String[]> ETIQUETAS = new HashMap<String, String[]>();

  static {
    // clave -> {en, es, pt}
    ETIQUETAS.put( "lesson", new String[] { "Lesson", "Lección", "Lição" } );
    ETIQUETAS.put( "step", new String[] { "step", "paso", "passo" } );
    ETIQUETAS.put( "what", new String[] { "What", "Qué", "O quê" } );
    ETIQUETAS.put( "why", new String[] { "Why", "Por qué", "Por quê" } );
    ETIQUETAS.put( "watch_out", new String[] { "Watch out!", "¡Ojo!", "Atenção!" } );
    ETIQUETAS.put( "tip", new String[] { "Tip", "Tip", "Dica" } );
    ETIQUETAS.put( "question", new String[] { "Question?", "¿Pregunta?", "Pergunta?" } );
  }

  /** Etiqueta fija en el idioma pedido (en, es, pt; otro valor = en). */
  static String etiqueta( String clave, String idioma ) {
    String[] v = ETIQUETAS.get( clave );
    if ( v == null ) {
      return clave;
    }
    String i = idioma == null ? "en" : idioma.trim().toLowerCase( Locale.ROOT );
    return "es".equals( i ) ? v[ 1 ] : "pt".equals( i ) ? v[ 2 ] : v[ 0 ];
  }

  /** Texto de una nota suelta: «¡Ojo! (paso 3) …», «Tip (paso 3): …», «¿Pregunta? (paso 3) …». */
  static String textoSuelta( Tipo t, String idioma, String step, int n, String texto ) {
    String ref = "(" + ( n > 0 ? etiqueta( "step", idioma ) + " " + n : step ) + ")";
    String cuerpo = texto == null ? "" : texto.trim();
    switch ( t ) {
      case TIP:
        return etiqueta( "tip", idioma ) + " " + ref + ": " + cuerpo;
      case PREGUNTA:
        return etiqueta( "question", idioma ) + " " + ref + " " + cuerpo;
      case OJO:
      default:
        return etiqueta( "watch_out", idioma ) + " " + ref + " " + cuerpo;
    }
  }

  /** Texto de la nota de un paso: «N · Nombre (Tipo)» / «Qué: …» / «Por qué: …» (WP3 la usa en lesson_start). */
  static String textoPaso( String idioma, int n, String step, String tipoStep, String que, String porque ) {
    StringBuilder sb = new StringBuilder();
    sb.append( n ).append( " · " ).append( step );
    if ( tipoStep != null && !tipoStep.isEmpty() ) {
      sb.append( " (" ).append( tipoStep ).append( ")" );
    }
    if ( que != null && !que.trim().isEmpty() ) {
      sb.append( "\n" ).append( etiqueta( "what", idioma ) ).append( ": " ).append( que.trim() );
    }
    if ( porque != null && !porque.trim().isEmpty() ) {
      sb.append( "\n" ).append( etiqueta( "why", idioma ) ).append( ": " ).append( porque.trim() );
    }
    return sb.toString();
  }

  // ---------------------------------------------------------------- huellas y registro (§4.5)

  /** CRC32 (8 hex) del texto sin espacios ni saltos de linea: no cambia al volver a cortar las lineas. */
  static String huella( String texto ) {
    String s = texto == null ? "" : texto.replaceAll( "[\\s\\u00a0\\u2007\\u202f]+", "" );
    CRC32 c = new CRC32();
    c.update( s.getBytes( StandardCharsets.UTF_8 ) );
    return String.format( Locale.ROOT, "%08x", c.getValue() );
  }

  /** Una entrada del registro. */
  static final class Entrada {
    final int k;
    final String huella;
    final Tipo tipo;
    /** Step al que se refiere, o "" (titulo). */
    final String step;
    final List<String> alternativas = new ArrayList<String>();

    Entrada( int k, String huella, Tipo tipo, String step ) {
      this.k = k;
      this.huella = huella;
      this.tipo = tipo;
      this.step = step == null ? "" : step;
    }

    boolean reconoce( String h ) {
      return huella.equals( h ) || alternativas.contains( h );
    }
  }

  static Tipo tipoDeJson( String s ) {
    for ( Tipo t : Tipo.values() ) {
      if ( t.json.equalsIgnoreCase( s == null ? "" : s.trim() ) ) {
        return t;
      }
    }
    return null;
  }

  private static String attr( TransMeta tm, String k ) {
    try {
      return tm.getAttribute( GRUPO, k );
    } catch ( RuntimeException sinGrupo ) {
      return null;
    }
  }

  static int entero( String s, int def ) {
    try {
      return s == null ? def : Integer.parseInt( s.trim() );
    } catch ( NumberFormatException e ) {
      return def;
    }
  }

  /** Las entradas del registro, en orden de K. Vacio si el documento no tiene leccion. */
  static List<Entrada> registro( TransMeta tm ) {
    List<Entrada> r = new ArrayList<Entrada>();
    int n = entero( attr( tm, K_NOTAS ), 0 );
    for ( int k = 1; k <= n; k++ ) {
      String v = attr( tm, P_NOTA + k );
      if ( v == null ) {
        continue;
      }
      String[] p = v.split( "\\|", 3 );
      Tipo t = p.length > 1 ? tipoDeJson( p[ 1 ] ) : null;
      if ( p.length < 2 || t == null ) {
        continue;
      }
      Entrada e = new Entrada( k, p[ 0 ].trim(), t, p.length > 2 ? p[ 2 ] : "" );
      String alt = attr( tm, P_ALT + k );
      if ( alt != null ) {
        for ( String a : alt.split( "," ) ) {
          if ( !a.trim().isEmpty() ) {
            e.alternativas.add( a.trim() );
          }
        }
      }
      r.add( e );
    }
    return r;
  }

  /** Hay un registro de leccion en el documento. */
  static boolean tieneLeccion( TransMeta tm ) {
    return entero( attr( tm, K_NOTAS ), 0 ) > 0;
  }

  /** Crea el grupo si falta (schema=1, notes=0). */
  static void asegurarGrupo( TransMeta tm, String idioma ) {
    if ( attr( tm, K_SCHEMA ) == null ) {
      tm.setAttribute( GRUPO, K_SCHEMA, SCHEMA );
    }
    if ( attr( tm, K_NOTAS ) == null ) {
      tm.setAttribute( GRUPO, K_NOTAS, "0" );
    }
    if ( idioma != null && attr( tm, K_IDIOMA ) == null ) {
      tm.setAttribute( GRUPO, K_IDIOMA, idioma );
    }
  }

  /** Agrega una entrada al registro (que solo crece) y devuelve su K. */
  static int registrar( TransMeta tm, NotePadMeta n, Tipo t, String step, String fuente ) {
    asegurarGrupo( tm, null );
    int k = entero( attr( tm, K_NOTAS ), 0 ) + 1;
    tm.setAttribute( GRUPO, P_NOTA + k, huella( n.getNote() ) + "|" + t.json + "|" + ( step == null ? "" : step ) );
    if ( fuente != null ) {
      tm.setAttribute( GRUPO, P_SRC + k, fuente );
    }
    tm.setAttribute( GRUPO, K_NOTAS, Integer.toString( k ) );
    return k;
  }

  /** Suma una huella alternativa a la entrada K (texto nuevo tras restyle/relayout). */
  private static void sumarAlternativa( TransMeta tm, Entrada e, String h ) {
    if ( e.reconoce( h ) ) {
      return;
    }
    e.alternativas.add( h );
    StringBuilder sb = new StringBuilder();
    for ( String a : e.alternativas ) {
      sb.append( sb.length() == 0 ? "" : "," ).append( a );
    }
    tm.setAttribute( GRUPO, P_ALT + e.k, sb.toString() );
  }

  /** Las notas del lienzo que son de la leccion (por huella), en orden de indice. Un IdentityHashMap: solo cache. */
  static Map<NotePadMeta, Entrada> reconocer( TransMeta tm ) {
    Map<NotePadMeta, Entrada> r = new LinkedHashMap<NotePadMeta, Entrada>();
    List<Entrada> reg = registro( tm );
    if ( reg.isEmpty() ) {
      return r;
    }
    Map<NotePadMeta, Entrada> id = new IdentityHashMap<NotePadMeta, Entrada>();
    for ( int i = 0; i < tm.nrNotes(); i++ ) {
      NotePadMeta n = tm.getNote( i );
      Entrada e = buscar( reg, huella( n.getNote() ) );
      if ( e != null && !id.containsKey( n ) ) {
        id.put( n, e );
        r.put( n, e );
      }
    }
    return r;
  }

  private static Entrada buscar( List<Entrada> reg, String h ) {
    for ( Entrada e : reg ) {
      if ( e.reconoce( h ) ) {
        return e;
      }
    }
    return null;
  }

  /** Numero de paso de un step en la leccion: el orden de su entrada 'step' en el registro (1..), o 0. */
  static int numeroDePaso( List<Entrada> reg, String step ) {
    int n = 0;
    for ( Entrada e : reg ) {
      if ( e.tipo == Tipo.PASO ) {
        n++;
        if ( e.step.equalsIgnoreCase( step ) ) {
          return n;
        }
      }
    }
    return 0;
  }

  static String idioma( TransMeta tm, String pedido ) {
    if ( pedido != null && !pedido.trim().isEmpty() ) {
      return pedido.trim().toLowerCase( Locale.ROOT );
    }
    String i = attr( tm, K_IDIOMA );
    return i == null || i.trim().isEmpty() ? "en" : i.trim();
  }

  static int tamFuente( TransMeta tm ) {
    return entero( attr( tm, K_FUENTE ), -1 );
  }

  // ---------------------------------------------------------------- medidores

  /** Un medidor que hay que cerrar (libera fuentes y GC si los hay). */
  interface MedidorCerrable extends Medidor {
    void cerrar();
  }

  /**
   * En el hilo de UI de Spoon (hay Display): un GC de SWT con la fuente de notas de Spoon (o tamFuente) y la del
   * lienzo para los nombres. Sin Display (pruebas, Pan): metricas aproximadas (7 px por letra a 9 pt).
   */
  static MedidorCerrable medidor( int tamFuente ) {
    Display d = null;
    try {
      d = Display.getCurrent();
    } catch ( Throwable sinSwt ) {
      d = null;
    }
    if ( d != null ) {
      try {
        return new MedidorSwt( d, tamFuente );
      } catch ( Throwable sinPropsUi ) {
        // sigue con el aproximado
      }
    }
    return new MedidorAprox( tamFuente );
  }

  /** 7 px por letra (8 en negrita) y 15 por linea a 9 pt, escalado por el tamano; nombres 6 x 14. */
  static final class MedidorAprox implements MedidorCerrable {
    private final double esc;

    MedidorAprox( int tamFuente ) {
      this.esc = tamFuente > 0 ? tamFuente / 9.0 : 1.0;
    }

    @Override
    public Point texto( String texto, boolean negrita ) {
      String[] l = ( texto == null ? "" : texto ).split( "\n", -1 );
      int w = 0;
      for ( String s : l ) {
        w = Math.max( w, (int) Math.ceil( s.length() * ( negrita ? 8 : 7 ) * esc ) );
      }
      return new Point( w, (int) Math.ceil( l.length * 15 * esc ) );
    }

    @Override
    public Point nombre( String nombre ) {
      return new Point( ( nombre == null ? 0 : nombre.length() ) * 6, 14 );
    }

    @Override
    public void cerrar() {
    }
  }

  /** GC de SWT sobre una imagen de 1x1 con las fuentes de Spoon. Hilo de UI. */
  static final class MedidorSwt implements MedidorCerrable {
    private final Image img;
    private final GC gc;
    private final Font normal;
    private final Font negrita;
    private final Font grafo;

    MedidorSwt( Display d, int tamFuente ) {
      PropsUI p = PropsUI.getInstance();
      FontData nf = p.getNoteFont();
      int tam = tamFuente > 0 ? tamFuente : nf.getHeight();
      img = new Image( d, 1, 1 );
      gc = new GC( img );
      normal = new Font( d, nf.getName(), tam, org.eclipse.swt.SWT.NORMAL );
      negrita = new Font( d, nf.getName(), tam, org.eclipse.swt.SWT.BOLD );
      FontData gf = p.getGraphFont();
      grafo = new Font( d, gf );
    }

    private Point medir( Font f, String t ) {
      gc.setFont( f );
      org.eclipse.swt.graphics.Point p = gc.textExtent( t == null ? "" : t,
        org.eclipse.swt.SWT.DRAW_DELIMITER | org.eclipse.swt.SWT.DRAW_TAB );
      return new Point( p.x, p.y );
    }

    @Override
    public Point texto( String texto, boolean neg ) {
      return medir( neg ? negrita : normal, texto );
    }

    @Override
    public Point nombre( String nombre ) {
      return medir( grafo, nombre );
    }

    @Override
    public void cerrar() {
      gc.dispose();
      img.dispose();
      normal.dispose();
      negrita.dispose();
      grafo.dispose();
    }
  }

  // ---------------------------------------------------------------- resultado de una operacion

  /** Lo que devuelve una operacion: cuantas notas toco, las del usuario que quedaron y avisos (en ingles). */
  static final class Resultado {
    int notas;
    int notasUsuario;
    int stepsMovidos;
    final List<String> avisos = new ArrayList<String>();
    /** Los steps movidos, para arrange_canvas / build_steps. */
    final TransLayout.Movimientos movimientos = new TransLayout.Movimientos();
  }

  private static int notasUsuario( TransMeta tm, Map<NotePadMeta, Entrada> rec ) {
    int u = 0;
    for ( int i = 0; i < tm.nrNotes(); i++ ) {
      if ( !rec.containsKey( tm.getNote( i ) ) ) {
        u++;
      }
    }
    return u;
  }

  // ---------------------------------------------------------------- relayout / restyle / add

  /**
   * Vuelve a aplicar la regla de clase a los steps y a las notas de la leccion: steps en columnas de 240, notas
   * en sus bandas, texto vuelto a cortar con el medidor. Las notas del usuario se quedan donde estan
   * (existing_notes keep). Registra en 'u' la posicion de los steps y el cambio de las notas (un Ctrl+Z con lo
   * que ya haya en el grupo). Las notas de un step que ya no esta, las de una columna densa (compactas, WP8) y las
   * que la regla deja fuera del lienzo no se tocan (avisos). tamFuente &gt; 0 cambia la fuente de todas. (UI)
   */
  static Resultado reacomodar( TransMeta tm, Medidor m, UndoGrupo u, int tamFuente ) {
    Resultado r = new Resultado();
    Map<NotePadMeta, Entrada> rec = reconocer( tm );
    List<NotePadMeta> deLeccion = new ArrayList<NotePadMeta>( rec.keySet() );
    LeccionLayout.Entrada e = LeccionLayout.desde( tm, m, deLeccion );
    e.modoPrevias = Previas.DEJAR;
    Map<LeccionLayout.Pedida, NotePadMeta> pedidas = new IdentityHashMap<LeccionLayout.Pedida, NotePadMeta>();
    List<Entrada> reg = registro( tm );
    boolean hayTitulo = false;
    for ( Map.Entry<NotePadMeta, Entrada> x : rec.entrySet() ) {
      Entrada en = x.getValue();
      String step = en.step.isEmpty() ? null : en.step;
      if ( en.tipo == Tipo.TITULO ) {
        if ( hayTitulo ) {
          r.avisos.add( "A second copy of the lesson title was left where it was." );
          continue;
        }
        hayTitulo = true;
        step = null;
      } else if ( step == null || tm.findStep( step ) == null ) {
        r.avisos.add( "Lesson note " + en.k + " refers to step '" + en.step + "', which is not in the "
          + "transformation any more (renamed or deleted): left where it was." );
        continue;
      }
      String src = attr( tm, P_SRC + en.k );
      String texto = src != null && en.reconoce( huella( x.getKey().getNote() ) ) && !src.isEmpty() ? src
        : x.getKey().getNote();
      int n = en.tipo == Tipo.PASO ? numeroDePaso( reg, en.step ) : step == null ? 0 : numeroDePaso( reg, en.step );
      pedidas.put( e.nota( en.tipo, step, n, texto ), x.getKey() );
    }
    Plan p;
    try {
      p = LeccionLayout.calcular( e, m );
    } catch ( IllegalArgumentException ex ) {
      r.avisos.add( "The class layout could not be applied: " + ex.getMessage() );
      return r;
    }
    r.avisos.addAll( p.avisos );
    // steps
    List<Object> objs = new ArrayList<Object>();
    List<Integer> idx = new ArrayList<Integer>();
    List<Point> antes = new ArrayList<Point>();
    List<Point> despues = new ArrayList<Point>();
    for ( int i = 0; i < p.steps.size(); i++ ) {
      StepMeta s = tm.findStep( p.steps.get( i ) );
      Point np = p.posiciones[ i ];
      if ( s == null || np == null ) {
        continue;
      }
      Point a = s.getLocation();
      if ( a != null && a.x == np.x && a.y == np.y ) {
        continue;
      }
      objs.add( s );
      idx.add( tm.indexOfStep( s ) );
      antes.add( a == null ? new Point( 0, 0 ) : new Point( a.x, a.y ) );
      despues.add( new Point( np.x, np.y ) );
      s.setLocation( np.x, np.y );
      r.movimientos.objetos.add( s );
      r.movimientos.indices.add( tm.indexOfStep( s ) );
      r.movimientos.antes.add( antes.get( antes.size() - 1 ) );
      r.movimientos.despues.add( new Point( np.x, np.y ) );
    }
    r.movimientos.columnas = p.columnas;
    r.stepsMovidos = objs.size();
    if ( u != null && !objs.isEmpty() ) {
      u.posicion( objs.toArray(), enteros( idx ), antes.toArray( new Point[ 0 ] ), despues.toArray( new Point[ 0 ] ) );
    }
    // notas
    List<Object> nAntes = new ArrayList<Object>();
    List<Object> nDespues = new ArrayList<Object>();
    List<Integer> nIdx = new ArrayList<Integer>();
    for ( Ubicada ub : p.notas ) {
      NotePadMeta n = pedidas.get( ub.pedida );
      if ( n == null ) {
        continue;
      }
      Entrada en = rec.get( n );
      if ( !ub.enLienzo() || ub.banda == Banda.COMPACTA || ub.texto == null ) {
        r.avisos.add( "Lesson note " + en.k + " is in a dense column (" + ub.banda.json + "): left where it was." );
        continue;
      }
      NotePadMeta antesN = (NotePadMeta) n.clone();
      Point loc = n.getLocation();
      boolean igual = ub.texto.equals( n.getNote() ) && loc != null && loc.x == ub.x && loc.y == ub.y
        && n.getWidth() == ub.ancho && n.getHeight() == ub.alto && ( tamFuente <= 0 || n.getFontSize() == tamFuente );
      if ( igual ) {
        r.notas++;
        continue;
      }
      n.setNote( ub.texto );
      n.setLocation( ub.x, ub.y );
      n.setWidth( ub.ancho );
      n.setHeight( ub.alto );
      estilizar( n, en.tipo, tamFuente > 0 ? tamFuente : n.getFontSize() );
      sumarAlternativa( tm, en, huella( ub.texto ) );
      nAntes.add( antesN );
      nDespues.add( n );
      nIdx.add( tm.indexOfNote( n ) );
      r.notas++;
    }
    if ( u != null && !nAntes.isEmpty() ) {
      u.cambio( nAntes.toArray(), nDespues.toArray(), enteros( nIdx ) );
    }
    r.notasUsuario = notasUsuario( tm, rec );
    tm.clearCaches();
    return r;
  }

  /**
   * Agrega una nota suelta (watch_out, tip o question) junto a un step, con estilo y registro, y vuelve a ubicar
   * todas las notas de la leccion con la regla: una accion de undo (nueva nota + posiciones + cambios). (UI)
   */
  static Resultado agregar( TransMeta tm, String step, Tipo t, String texto, String idioma, Medidor m, UndoGrupo u )
    throws ToolError {
    if ( t != Tipo.OJO && t != Tipo.TIP && t != Tipo.PREGUNTA ) {
      throw new ToolError( "kind must be watch_out, tip or question" );
    }
    StepMeta s = step == null ? null : tm.findStep( step );
    if ( s == null ) {
      throw new ToolError( "Step '" + step + "' not found in '" + tm.getName() + "'" );
    }
    if ( texto == null || texto.trim().isEmpty() ) {
      throw new ToolError( "'text' is required for action=add" );
    }
    String lang = idioma( tm, idioma );
    asegurarGrupo( tm, lang );
    int n = numeroDePaso( registro( tm ), s.getName() );
    String completo = textoSuelta( t, lang, s.getName(), n, texto );
    Point loc = s.getLocation();
    NotePadMeta nota = new NotePadMeta( completo, loc == null ? 10 : loc.x, loc == null ? 10 : loc.y,
      LeccionLayout.ANCHO_NOTA, 40 );
    estilizar( nota, t, tamFuente( tm ) );
    tm.addNote( nota );
    u.nuevo( new Object[] { nota }, new int[] { tm.indexOfNote( nota ) } );
    int k = registrar( tm, nota, t, s.getName(), completo );
    Resultado r = reacomodar( tm, m, u, -1 );
    // el texto quedo cortado: su huella es la de la entrada si no cambio nada mas que los espacios
    String h = huella( nota.getNote() );
    for ( Entrada en : registro( tm ) ) {
      if ( en.k == k ) {
        sumarAlternativa( tm, en, h );
      }
    }
    return r;
  }

  // ---------------------------------------------------------------- hide / show / remove

  /** Borra las notas dadas: de atras para adelante, una accion DELETE_NOTE con los indices ascendentes. (UI) */
  private static void borrar( TransMeta tm, List<NotePadMeta> notas, UndoGrupo u ) {
    List<Integer> idx = new ArrayList<Integer>();
    for ( NotePadMeta n : notas ) {
      idx.add( tm.indexOfNote( n ) );
    }
    Collections.sort( idx );
    Object[] objs = new Object[ idx.size() ];
    for ( int i = 0; i < idx.size(); i++ ) {
      objs[ i ] = tm.getNote( idx.get( i ) );
    }
    for ( int i = idx.size() - 1; i >= 0; i-- ) {
      tm.removeNote( idx.get( i ) );
    }
    if ( u != null && objs.length > 0 ) {
      u.borrado( objs, enteros( idx ) );
    }
  }

  /** Quita las notas de la leccion; el registro queda (purge=true lo borra). */
  static Resultado quitar( TransMeta tm, boolean purgar, UndoGrupo u ) {
    Resultado r = new Resultado();
    Map<NotePadMeta, Entrada> rec = reconocer( tm );
    borrar( tm, new ArrayList<NotePadMeta>( rec.keySet() ), u );
    r.notas = rec.size();
    r.notasUsuario = tm.nrNotes();
    if ( purgar ) {
      tm.getAttributesMap().remove( GRUPO );
      r.avisos.add( "The lesson registry was deleted: after a Ctrl+Z the notes come back as user notes." );
    }
    tm.clearCaches();
    return r;
  }

  /** Saca las notas de la leccion y guarda su XML en el atributo hidden (viaja en el .ktr; no es secreto). */
  static Resultado ocultar( TransMeta tm, UndoGrupo u ) throws ToolError {
    Resultado r = new Resultado();
    Map<NotePadMeta, Entrada> rec = reconocer( tm );
    List<NotePadMeta> previas = ocultas( tm );
    Map<String, Boolean> ya = new HashMap<String, Boolean>();
    StringBuilder xml = new StringBuilder( "<notepads>" );
    for ( NotePadMeta n : previas ) {
      ya.put( huella( n.getNote() ), Boolean.TRUE );
      xml.append( n.getXML() );
    }
    for ( NotePadMeta n : rec.keySet() ) {
      if ( ya.put( huella( n.getNote() ), Boolean.TRUE ) == null ) {
        xml.append( n.getXML() );
      }
    }
    xml.append( "</notepads>" );
    borrar( tm, new ArrayList<NotePadMeta>( rec.keySet() ), u );
    tm.setAttribute( GRUPO, K_OCULTAS, xml.toString() );
    r.notas = rec.size();
    r.notasUsuario = tm.nrNotes();
    tm.clearCaches();
    return r;
  }

  /** Las notas guardadas en hidden (vacio si no hay). */
  static List<NotePadMeta> ocultas( TransMeta tm ) throws ToolError {
    List<NotePadMeta> r = new ArrayList<NotePadMeta>();
    String xml = attr( tm, K_OCULTAS );
    if ( xml == null || xml.trim().isEmpty() ) {
      return r;
    }
    try {
      Document d = XMLHandler.loadXMLString( xml );
      Node raiz = XMLHandler.getSubNode( d, "notepads" );
      for ( Node n : XMLHandler.getNodes( raiz, "notepad" ) ) {
        r.add( new NotePadMeta( n ) );
      }
    } catch ( Exception e ) {
      throw new ToolError( "The hidden lesson notes of '" + tm.getName() + "' could not be read: " + e.getMessage() );
    }
    return r;
  }

  /** Repone desde hidden las notas que no esten ya en el lienzo (sin duplicar tras un Ctrl+Z) y vacia hidden. */
  static Resultado mostrar( TransMeta tm, UndoGrupo u ) throws ToolError {
    Resultado r = new Resultado();
    List<NotePadMeta> ocultas = ocultas( tm );
    Map<String, Boolean> presentes = new HashMap<String, Boolean>();
    for ( int i = 0; i < tm.nrNotes(); i++ ) {
      presentes.put( huella( tm.getNote( i ).getNote() ), Boolean.TRUE );
    }
    List<Object> nuevas = new ArrayList<Object>();
    List<Integer> idx = new ArrayList<Integer>();
    for ( NotePadMeta n : ocultas ) {
      if ( presentes.put( huella( n.getNote() ), Boolean.TRUE ) != null ) {
        r.avisos.add( "A hidden note was already on the canvas (a Ctrl+Z undid the hide?): not duplicated." );
        continue;
      }
      tm.addNote( n );
      nuevas.add( n );
      idx.add( tm.indexOfNote( n ) );
    }
    if ( u != null && !nuevas.isEmpty() ) {
      u.nuevo( nuevas.toArray(), enteros( idx ) );
    }
    if ( attr( tm, K_OCULTAS ) != null ) {
      tm.setAttribute( GRUPO, K_OCULTAS, "" );
    }
    r.notas = nuevas.size();
    r.notasUsuario = notasUsuario( tm, reconocer( tm ) );
    tm.clearCaches();
    return r;
  }

  // ---------------------------------------------------------------- list

  /** Estado de cada entrada: present, hidden, missing o step_missing. */
  static List<String[]> listar( TransMeta tm ) throws ToolError {
    List<String[]> r = new ArrayList<String[]>();
    Map<NotePadMeta, Entrada> rec = reconocer( tm );
    Map<Integer, Boolean> presentes = new HashMap<Integer, Boolean>();
    for ( Entrada e : rec.values() ) {
      presentes.put( e.k, Boolean.TRUE );
    }
    List<Entrada> reg = registro( tm );
    Map<Integer, Boolean> ocultas = new HashMap<Integer, Boolean>();
    for ( NotePadMeta n : ocultas( tm ) ) {
      Entrada e = buscar( reg, huella( n.getNote() ) );
      if ( e != null ) {
        ocultas.put( e.k, Boolean.TRUE );
      }
    }
    for ( Entrada e : reg ) {
      String estado;
      if ( !e.step.isEmpty() && tm.findStep( e.step ) == null ) {
        estado = "step_missing";
      } else if ( presentes.containsKey( e.k ) ) {
        estado = "present";
      } else if ( ocultas.containsKey( e.k ) ) {
        estado = "hidden";
      } else {
        estado = "missing";
      }
      r.add( new String[] { Integer.toString( e.k ), e.tipo.json, e.step, estado } );
    }
    return r;
  }

  private static int[] enteros( List<Integer> l ) {
    int[] r = new int[ l.size() ];
    for ( int i = 0; i < r.length; i++ ) {
      r[ i ] = l.get( i );
    }
    return r;
  }
}
