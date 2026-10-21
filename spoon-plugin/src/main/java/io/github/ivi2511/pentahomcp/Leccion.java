package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.pentaho.di.core.NotePadMeta;
import org.pentaho.di.core.gui.Point;
import org.pentaho.di.core.plugins.PluginInterface;
import org.pentaho.di.core.undo.TransAction;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.errorhandling.StreamInterface;
import org.pentaho.metastore.api.IMetaStore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.LeccionLayout.Medidor;
import io.github.ivi2511.pentahomcp.LeccionLayout.Pedida;
import io.github.ivi2511.pentahomcp.LeccionLayout.Plan;
import io.github.ivi2511.pentahomcp.LeccionLayout.Previas;
import io.github.ivi2511.pentahomcp.LeccionLayout.Tipo;
import io.github.ivi2511.pentahomcp.LeccionLayout.Ubicada;
import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;

/**
 * Modo clase, WP3: una leccion en memoria (docs/modo-clase-diseno.md §5, §8 y §10). Lee el guion de lesson_start,
 * lo valida ENTERO contra la transformacion sin tocarla (los steps nuevos y los hops con {@link TransLote#validar})
 * y calcula el plan una sola vez ({@link LeccionLayout}): cada step aparece directamente en su lugar final y cada
 * nota en su banda. La reproduccion (hilo, ritmos, undo por paso, desincronizacion) esta en {@link LeccionMotor}.
 * <p>
 * El estado mutable (posicion, estado, clics...) se protege con el monitor de la leccion; el TransMeta solo se
 * toca en el hilo de UI (o sin Spoon, en las pruebas).
 */
final class Leccion {

  static final int MAX_FILAS = 20;
  static final int DELAY_MIN = 1500;
  static final int DELAY_MAX = 60000;
  static final int DELAY_DEFECTO = 6000;

  /** Ritmo (pace). */
  enum Ritmo {
    GUIADO( "guided" ), AUTO( "auto" ), MANUAL( "manual" );

    final String json;

    Ritmo( String json ) {
      this.json = json;
    }
  }

  /** Estado (state). */
  enum Estado {
    READY( "ready" ), WAITING( "waiting_next" ), PLAYING( "playing" ), PAUSED( "paused" ),
    OUT_OF_SYNC( "out_of_sync" ), DONE( "done" ), STOPPED( "stopped" );

    final String json;

    Estado( String json ) {
      this.json = json;
    }
  }

  /** Un paso del guion: crear un step (spec) o anotar uno que ya existe. */
  static final class Paso {
    int n;
    /** null = anotar un step que ya existe. */
    TransLote.PasoSpec spec;
    String step;
    /** Nombre del tipo para la nota («Stream lookup»). */
    String tipoStep;
    String que;
    String porque;
    String ojo;
    boolean ojoAuto;
    String tip;
    String pregunta;
    int filas;
    /** Hops del guion que cierran en este paso (su ultimo extremo aparece aca). */
    final List<TransLote.HopSpec> hopsExtra = new ArrayList<TransLote.HopSpec>();
    /** Las notas pedidas de este paso, en orden (paso, ojo, tip, pregunta). */
    final List<Pedida> pedidas = new ArrayList<Pedida>();
    /** Steps cuya vista previa corre al terminar este paso (el propio o uno pospuesto). */
    final List<String> vistas = new ArrayList<String>();
    /** Texto completo de la nota del paso (para la pestana y lesson_status). */
    String textoNota;
    // ejecucion
    /** Ultima accion de undo del paso (lo que tiene que estar arriba del historial para «Atras»), o null. */
    TransAction ultima;
    /** Huellas de las notas que el paso puso en el lienzo. */
    final List<String> huellas = new ArrayList<String>();

    boolean crea() {
      return spec != null;
    }
  }

  // ---------------------------------------------------------------- configuracion
  String id;
  String docId;
  TransMeta tm;
  String titulo;
  String nivel = "beginner";
  String idioma = "en";
  Ritmo ritmo = Ritmo.GUIADO;
  int delayMs = DELAY_DEFECTO;
  int fuente = -1;
  Previas previas = Previas.APILAR;
  boolean permitirEscritura;
  String intro;
  String outro;
  final List<Paso> pasos = new ArrayList<Paso>();
  /** Hops del guion cuyos dos extremos ya existen: van en el paso 0. */
  final List<TransLote.HopSpec> hopsCero = new ArrayList<TransLote.HopSpec>();
  /** Los steps a crear y los hops, en el formato de build_steps (para validar y para el modo de salida). */
  final TransLote.Lote lote = new TransLote.Lote();
  Plan plan;
  Pedida pedidaTitulo;
  String textoTitulo;
  final List<String> avisos = new ArrayList<String>();

  // ---------------------------------------------------------------- estado (monitor de la leccion)
  int posicion;
  Estado estado = Estado.READY;
  String motivo;
  int clics;
  long ultimoClic;
  long cambios;
  boolean pedido;
  boolean pausaPedida;
  /** Motivo de la pausa pedida mientras corre un paso (panel_hidden), o null. */
  String motivoPausa;
  /** Hay un hilo pentaho-live-leccion esperando clics o el temporizador. */
  boolean hiloVivo;
  long proximo;
  TransAction ultimaCero;
  final List<String> huellasCero = new ArrayList<String>();
  /** Filas de la vista previa por step (en memoria; no van al .ktr). */
  final Map<String, ObjectNode> filas = new LinkedHashMap<String, ObjectNode>();

  /** Algo cambio: despierta a lesson_status y al hilo. (con el monitor) */
  void cambio() {
    cambios++;
    notifyAll();
  }

  boolean terminada() {
    return estado == Estado.STOPPED;
  }

  // ---------------------------------------------------------------- leer

  /** Arma la leccion desde los argumentos de lesson_start; los errores de forma van a 'problemas'. */
  static Leccion leer( JsonNode a, List<String> problemas ) {
    Leccion l = new Leccion();
    l.titulo = texto( a, "title" );
    if ( l.titulo == null ) {
      problemas.add( "'title' is required" );
    }
    String nivel = texto( a, "level" );
    if ( nivel != null ) {
      l.nivel = nivel.toLowerCase( Locale.ROOT );
      if ( !l.nivel.equals( "beginner" ) && !l.nivel.equals( "intermediate" ) ) {
        problemas.add( "level must be 'beginner' or 'intermediate', got '" + nivel + "'" );
      }
    }
    String idioma = texto( a, "language" );
    if ( idioma != null ) {
      l.idioma = idioma.toLowerCase( Locale.ROOT );
      if ( !l.idioma.equals( "en" ) && !l.idioma.equals( "es" ) && !l.idioma.equals( "pt" ) ) {
        problemas.add( "language must be en, es or pt, got '" + idioma + "'" );
      }
    }
    String ritmo = texto( a, "pace" );
    if ( ritmo != null ) {
      l.ritmo = null;
      for ( Ritmo r : Ritmo.values() ) {
        if ( r.json.equalsIgnoreCase( ritmo ) ) {
          l.ritmo = r;
        }
      }
      if ( l.ritmo == null ) {
        problemas.add( "pace must be guided, auto or manual, got '" + ritmo + "'" );
        l.ritmo = Ritmo.GUIADO;
      }
    }
    try {
      Integer d = Tools.optInteger( a, "delay_ms" );
      if ( d != null ) {
        l.delayMs = Math.max( DELAY_MIN, Math.min( DELAY_MAX, d ) );
        if ( l.delayMs != d ) {
          l.avisos.add( "delay_ms " + d + " is out of range: used " + l.delayMs + " (" + DELAY_MIN + ".." + DELAY_MAX
            + ")." );
        }
      }
      Integer f = Tools.optInteger( a, "font_size" );
      if ( f != null ) {
        if ( f != -1 && ( f < 6 || f > 40 ) ) {
          problemas.add( "font_size must be -1 or between 6 and 40" );
        } else {
          l.fuente = f;
        }
      }
    } catch ( ToolError e ) {
      problemas.add( e.getMessage() );
    }
    l.previas = Previas.deJson( texto( a, "existing_notes" ) );
    if ( l.previas == null ) {
      problemas.add( "existing_notes must be 'stack_top' or 'keep'" );
      l.previas = Previas.APILAR;
    }
    l.permitirEscritura = Tools.optBool( a, "allow_write", false );
    l.intro = texto( a, "intro" );
    l.outro = texto( a, "outro" );

    JsonNode steps = comoJson( a.get( "steps" ) );
    if ( steps == null || !steps.isArray() || steps.size() == 0 ) {
      problemas.add( "'steps' must be a non-empty array of {type, name, config_xml?, after?, output_mode?} or "
        + "{step} (an existing step), each with what/why" );
    } else if ( steps.size() > TransLote.MAX_STEPS ) {
      problemas.add( "At most " + TransLote.MAX_STEPS + " steps per lesson (got " + steps.size() + ")" );
    } else {
      for ( int i = 0; i < steps.size(); i++ ) {
        JsonNode n = steps.get( i );
        String et = "steps[" + i + "]";
        if ( n == null || !n.isObject() ) {
          problemas.add( et + ": must be an object" );
          continue;
        }
        Paso p = new Paso();
        p.n = l.pasos.size() + 1;
        String existente = texto( n, "step" );
        if ( existente != null && n.get( "type" ) == null ) {
          p.step = existente;
        } else {
          TransLote.PasoSpec s = new TransLote.PasoSpec();
          s.pos = l.lote.steps.size();
          s.type = texto( n, "type" );
          s.name = texto( n, "name" );
          s.config = Tools.optStr( n, "config_xml", null );
          s.after = texto( n, "after" );
          if ( n.has( "x" ) || n.has( "y" ) ) {
            problemas.add( et + ": a lesson step does not take x/y (the lesson plan places every step)" );
          }
          String modo = texto( n, "output_mode" );
          if ( modo != null ) {
            s.modo = TransLote.modo( modo );
            if ( s.modo == null ) {
              problemas.add( et + ": output_mode must be 'copy' or 'distribute', got '" + modo + "'" );
            }
          }
          p.spec = s;
          p.step = s.name;
          l.lote.steps.add( s );
        }
        p.que = texto( n, "what" );
        p.porque = texto( n, "why" );
        String ojo = texto( n, "watch_out" );
        if ( ojo != null && ojo.equalsIgnoreCase( "auto" ) ) {
          p.ojoAuto = true;
        } else {
          p.ojo = ojo;
        }
        p.tip = texto( n, "tip" );
        p.pregunta = texto( n, "question" );
        try {
          Integer f = Tools.optInteger( n, "preview_rows" );
          if ( f != null ) {
            if ( f < 0 || f > MAX_FILAS ) {
              problemas.add( et + ": preview_rows must be between 0 and " + MAX_FILAS );
            } else {
              p.filas = f;
            }
          }
        } catch ( ToolError e ) {
          problemas.add( et + ": " + e.getMessage() );
        }
        if ( p.que == null && p.porque == null ) {
          l.avisos.add( et + " has no what/why: its note only shows the step name." );
        }
        l.pasos.add( p );
      }
    }
    JsonNode hops = comoJson( a.get( "hops" ) );
    if ( hops != null && !hops.isNull() ) {
      if ( !hops.isArray() ) {
        problemas.add( "'hops' must be an array of {from, to, error?, enabled?}" );
      } else {
        for ( int i = 0; i < hops.size(); i++ ) {
          JsonNode n = hops.get( i );
          if ( n == null || !n.isObject() ) {
            problemas.add( "hops[" + i + "]: must be an object {from, to, error?, enabled?}" );
            continue;
          }
          TransLote.HopSpec h = new TransLote.HopSpec();
          h.pos = i;
          h.from = texto( n, "from" );
          h.to = texto( n, "to" );
          h.error = Tools.optBool( n, "error", false );
          h.enabled = Tools.optBool( n, "enabled", true );
          l.lote.hops.add( h );
        }
      }
    }
    l.lote.arrange = false;
    l.lote.delayMs = 0;
    return l;
  }

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
    if ( s == null ) {
      return null;
    }
    s = s.trim();
    return s.isEmpty() ? null : s;
  }

  private static String clave( String s ) {
    return s == null ? "" : s.trim().toLowerCase( Locale.ROOT );
  }

  // ---------------------------------------------------------------- validar

  /**
   * Revisa el guion contra la transformacion SIN tocarla: steps a anotar que existan, nombres sin repetir, los
   * steps nuevos y los hops con TransLote.validar (tipos, XML, after, ciclos), la vista previa (allow_write y el
   * documento guardado si las rutas usan ${Internal.Entry.Current.Directory}) y que no haya otra leccion en el
   * lienzo. Reparte los hops por paso (el del ultimo extremo). (UI)
   */
  List<String> validar( TransMeta t, IMetaStore ms ) {
    List<String> problemas = new ArrayList<String>();
    // otra leccion en el lienzo
    if ( !LeccionNotas.reconocer( t ).isEmpty() ) {
      problemas.add( "'" + t.getName() + "' already has lesson notes on the canvas: remove them first "
        + "(lesson_notes action=remove) or work on a copy" );
    }
    try {
      if ( !LeccionNotas.ocultas( t ).isEmpty() ) {
        problemas.add( "'" + t.getName() + "' has hidden lesson notes: lesson_notes show, then remove, first" );
      }
    } catch ( ToolError e ) {
      problemas.add( e.getMessage() );
    }
    Map<String, Integer> pasoDe = new HashMap<String, Integer>(); // clave -> n del paso que crea el step
    Map<String, Paso> nombres = new HashMap<String, Paso>();
    for ( Paso p : pasos ) {
      String et = "steps[" + ( p.n - 1 ) + "]";
      if ( p.step == null ) {
        continue; // TransLote.validar lo dice
      }
      if ( nombres.containsKey( clave( p.step ) ) ) {
        problemas.add( et + ": step '" + p.step + "' appears twice in the lesson (also step " + nombres.get( clave(
          p.step ) ).n + ")" );
        continue;
      }
      nombres.put( clave( p.step ), p );
      if ( !p.crea() ) {
        StepMeta sm = t.findStep( p.step );
        if ( sm == null ) {
          problemas.add( et + ": step '" + p.step + "' does not exist (to create it give 'type' and 'name'). "
            + TransOps.nombres( t ) );
        } else {
          p.step = sm.getName();
          p.tipoStep = nombreTipo( sm.getStepID() );
        }
      } else {
        pasoDe.put( clave( p.step ), p.n );
        if ( p.spec.type != null ) {
          p.tipoStep = nombreTipo( p.spec.type );
        }
      }
    }
    if ( !lote.steps.isEmpty() || !lote.hops.isEmpty() ) {
      problemas.addAll( TransLote.validar( t, lote, ms ) );
    }
    if ( !problemas.isEmpty() ) {
      return problemas;
    }
    // hops: en el paso de su ultimo extremo (0 si los dos ya estaban)
    for ( TransLote.HopSpec h : lote.hops ) {
      Integer a = pasoDe.get( clave( h.from ) );
      Integer b = pasoDe.get( clave( h.to ) );
      int k = Math.max( a == null ? 0 : a, b == null ? 0 : b );
      if ( k == 0 ) {
        hopsCero.add( h );
      } else {
        pasos.get( k - 1 ).hopsExtra.add( h );
      }
    }
    // vista previa
    boolean hayVista = false;
    for ( Paso p : pasos ) {
      if ( p.filas <= 0 ) {
        continue;
      }
      hayVista = true;
      if ( !permitirEscritura ) {
        String escritor = escritorAntes( t, p.step );
        if ( escritor != null ) {
          problemas.add( "steps[" + ( p.n - 1 ) + "]: preview_rows on '" + p.step + "' runs '" + escritor
            + "', an output step that really writes: pass allow_write=true for the lesson, or drop preview_rows" );
        }
      }
    }
    if ( hayVista && t.getFilename() == null && usaDirectorioInterno( t ) ) {
      problemas.add( "Some step asks for preview_rows and a path uses ${Internal.Entry.Current.Directory}, but '"
        + t.getName() + "' has no file: save it first in the folder where those files are (e.g. next to datos/)" );
    }
    return problemas;
  }

  /** Nombre visible de un tipo de step («Stream lookup»), o el id. */
  static String nombreTipo( String id ) {
    try {
      PluginInterface p = TransOps.plugin( id );
      String n = p.getName();
      return n == null || n.trim().isEmpty() ? id : n.trim();
    } catch ( Exception e ) {
      return id;
    }
  }

  /** Algun step de la leccion o del documento usa la carpeta del archivo en una ruta. */
  private boolean usaDirectorioInterno( TransMeta t ) {
    StringBuilder sb = new StringBuilder();
    for ( StepMeta s : t.getSteps() ) {
      try {
        sb.append( s.getXML() );
      } catch ( Exception ignored ) {
        // sin XML no se puede saber
      }
    }
    for ( TransLote.PasoSpec s : lote.steps ) {
      if ( s.config != null ) {
        sb.append( s.config );
      }
    }
    String x = sb.toString();
    return x.contains( "Internal.Entry.Current.Directory" ) || x.contains( "Internal.Transformation.Filename.Directory" );
  }

  /** El primer step que escribe entre 'step' y los que lo alimentan (en el grafo final), o null. */
  private String escritorAntes( TransMeta t, String step ) {
    Map<String, List<String>> entradas = new HashMap<String, List<String>>();
    for ( String[] h : hopsFinales( t ) ) {
      List<String> l = entradas.get( clave( h[ 1 ] ) );
      if ( l == null ) {
        l = new ArrayList<String>();
        entradas.put( clave( h[ 1 ] ), l );
      }
      l.add( h[ 0 ] );
    }
    List<String> pila = new ArrayList<String>();
    Map<String, Boolean> visto = new HashMap<String, Boolean>();
    pila.add( step );
    while ( !pila.isEmpty() ) {
      String s = pila.remove( pila.size() - 1 );
      if ( visto.put( clave( s ), Boolean.TRUE ) != null ) {
        continue;
      }
      String tipo = tipoDe( t, s );
      if ( tipo != null && escribeTipo( tipo ) ) {
        return s;
      }
      List<String> l = entradas.get( clave( s ) );
      if ( l != null ) {
        pila.addAll( l );
      }
    }
    return null;
  }

  private String tipoDe( TransMeta t, String step ) {
    StepMeta sm = t.findStep( step );
    if ( sm != null ) {
      return sm.getStepID();
    }
    TransLote.PasoSpec s = lote.paso( step );
    if ( s == null || s.type == null ) {
      return null;
    }
    try {
      return TransOps.id( TransOps.plugin( s.type ) );
    } catch ( Exception e ) {
      return s.type;
    }
  }

  static boolean escribeTipo( String id ) {
    StepMeta sm = new StepMeta();
    sm.setStepID( id );
    return RunMotor.escribe( sm );
  }

  /** Los hops del grafo final: los del documento, los 'after' y los del guion ({desde, hacia}). */
  List<String[]> hopsFinales( TransMeta t ) {
    List<String[]> r = new ArrayList<String[]>();
    for ( int i = 0; i < t.nrTransHops(); i++ ) {
      TransHopMeta h = t.getTransHop( i );
      if ( h.getFromStep() != null && h.getToStep() != null ) {
        r.add( new String[] { h.getFromStep().getName(), h.getToStep().getName() } );
      }
    }
    for ( TransLote.PasoSpec s : lote.steps ) {
      if ( s.after != null ) {
        StepMeta a = t.findStep( s.after );
        r.add( new String[] { a != null ? a.getName() : nombreEnLote( s.after ), s.name } );
      }
    }
    for ( TransLote.HopSpec h : lote.hops ) {
      r.add( new String[] { nombreReal( t, h.from ), nombreReal( t, h.to ) } );
    }
    return r;
  }

  private String nombreEnLote( String n ) {
    TransLote.PasoSpec s = lote.paso( n );
    return s == null ? n : s.name;
  }

  private String nombreReal( TransMeta t, String n ) {
    StepMeta sm = t.findStep( n );
    return sm != null ? sm.getName() : nombreEnLote( n );
  }

  // ---------------------------------------------------------------- textos

  private static final Map<String, String[]> TEXTOS = new HashMap<String, String[]>();

  static {
    TEXTOS.put( "beginner", new String[] { "beginner", "principiante", "iniciante" } );
    TEXTOS.put( "intermediate", new String[] { "intermediate", "intermedio", "intermediário" } );
    TEXTOS.put( "steps", new String[] { "steps", "pasos", "passos" } );
  }

  private String t( String k ) {
    String[] v = TEXTOS.get( k );
    if ( v == null ) {
      return k;
    }
    return "es".equals( idioma ) ? v[ 1 ] : "pt".equals( idioma ) ? v[ 2 ] : v[ 0 ];
  }

  /** «Lección · titulo» / «principiante · 5 pasos» / intro. */
  String textoTitulo() {
    StringBuilder sb = new StringBuilder();
    sb.append( LeccionNotas.etiqueta( "lesson", idioma ) ).append( " · " ).append( titulo );
    sb.append( "\n" ).append( t( nivel ) ).append( " · " ).append( pasos.size() ).append( ' ' ).append( t( "steps" ) );
    if ( intro != null ) {
      sb.append( "\n" ).append( intro );
    }
    return sb.toString();
  }

  /**
   * El texto del «¡Ojo!» automatico (glosario, §7): principiante solo los silenciosos (D), intermedio C y D;
   * null si el glosario no tiene nada para ese tipo.
   */
  String ojoAuto( String tipoId ) {
    ObjectNode g = new McpResources().glosario( idioma, tipoId );
    if ( g == null ) {
      return null;
    }
    JsonNode w = g.get( "watch_out" );
    if ( w == null || !w.isArray() ) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    for ( JsonNode x : w ) {
      String code = x.path( "code" ).asText( "" );
      if ( code.isEmpty() || ( "beginner".equals( nivel ) && !code.startsWith( "D" ) ) ) {
        continue;
      }
      String titulo = x.path( "title" ).asText( "" );
      sb.append( sb.length() == 0 ? "" : "; " ).append( code );
      if ( !titulo.isEmpty() ) {
        sb.append( ' ' ).append( titulo );
      }
    }
    return sb.length() == 0 ? null : sb.toString();
  }

  // ---------------------------------------------------------------- plan

  /**
   * Calcula el plan una vez: el grafo final (steps que ya estan + los del guion), el titulo y las notas de cada
   * paso, con la regla de ubicacion. Decide en que paso corre cada vista previa (un step que nombra destinos que
   * todavia no existen, como Filter rows, la pospone al paso del ultimo destino). Lanza ToolError si la regla no
   * puede con la entrada. (UI)
   */
  void planificar( TransMeta t, Medidor m, IMetaStore ms ) throws ToolError {
    LeccionLayout.Entrada e = LeccionLayout.desde( t, m, null );
    e.modoPrevias = previas;
    for ( TransLote.PasoSpec s : lote.steps ) {
      e.step( s.name, null );
    }
    for ( TransLote.PasoSpec s : lote.steps ) {
      if ( s.after != null ) {
        StepMeta a = t.findStep( s.after );
        e.hop( a != null ? a.getName() : nombreEnLote( s.after ), s.name );
      }
    }
    for ( TransLote.HopSpec h : lote.hops ) {
      e.hop( nombreReal( t, h.from ), nombreReal( t, h.to ) );
    }
    textoTitulo = textoTitulo();
    pedidaTitulo = e.nota( Tipo.TITULO, null, 0, textoTitulo );
    for ( Paso p : pasos ) {
      p.textoNota = LeccionNotas.textoPaso( idioma, p.n, p.step, p.tipoStep, p.que, p.porque );
      p.pedidas.add( e.nota( Tipo.PASO, p.step, p.n, p.textoNota ) );
      String ojo = p.ojo;
      if ( ojo == null && p.ojoAuto ) {
        String id = tipoDe( t, p.step );
        ojo = id == null ? null : ojoAuto( id );
        if ( ojo == null ) {
          avisos.add( "watch_out 'auto' for '" + p.step + "': the glossary has nothing for this step type at level "
            + nivel + "." );
        }
      }
      if ( ojo != null ) {
        p.pedidas.add( e.nota( Tipo.OJO, p.step, p.n, LeccionNotas.textoSuelta( Tipo.OJO, idioma, p.step, p.n, ojo ) ) );
      }
      if ( p.tip != null ) {
        p.pedidas.add( e.nota( Tipo.TIP, p.step, p.n, LeccionNotas.textoSuelta( Tipo.TIP, idioma, p.step, p.n,
          p.tip ) ) );
      }
      if ( p.pregunta != null ) {
        p.pedidas.add( e.nota( Tipo.PREGUNTA, p.step, p.n, LeccionNotas.textoSuelta( Tipo.PREGUNTA, idioma, p.step,
          p.n, p.pregunta ) ) );
      }
    }
    try {
      plan = LeccionLayout.calcular( e, m );
    } catch ( IllegalArgumentException ex ) {
      throw new ToolError( "lesson_start: the lesson plan could not be computed: " + ex.getMessage()
        + ". Nothing was changed." );
    }
    avisos.addAll( plan.avisos );
    // vistas previas: en su paso o en el del ultimo destino que todavia no existe
    Map<String, Integer> pasoDe = new HashMap<String, Integer>();
    for ( Paso p : pasos ) {
      if ( p.crea() ) {
        pasoDe.put( clave( p.step ), p.n );
      }
    }
    for ( Paso p : pasos ) {
      if ( p.filas <= 0 ) {
        continue;
      }
      int k = p.n;
      for ( String d : destinos( t, p, ms ) ) {
        Integer b = pasoDe.get( clave( d ) );
        if ( b != null && b > k ) {
          k = b;
        }
      }
      pasos.get( k - 1 ).vistas.add( p.step );
      if ( k != p.n ) {
        avisos.add( "The preview of '" + p.step + "' runs at step " + k + ", when all its target steps exist." );
      }
    }
  }

  /** Los steps que un step nombra como destino (send_true_to, Switch/case...), por nombre. */
  private List<String> destinos( TransMeta t, Paso p, IMetaStore ms ) {
    List<String> r = new ArrayList<String>();
    try {
      StepMeta sm = t.findStep( p.step );
      if ( sm == null && p.crea() ) {
        PluginInterface plugin = TransOps.plugin( p.spec.type );
        String cuerpo = Xml.mezclar( TransOps.plantilla( plugin ), p.spec.config, Xml.TAGS_ENVOLTORIO_STEP );
        sm = TransOps.construir( t, TransOps.id( plugin ), p.spec.name, null, 1, true, cuerpo, new Point( 0, 0 ), ms );
      }
      if ( sm == null || sm.getStepMetaInterface() == null ) {
        return r;
      }
      for ( StreamInterface s : sm.getStepMetaInterface().getStepIOMeta().getTargetStreams() ) {
        Object sub = s.getSubject();
        if ( sub instanceof StepMeta ) {
          r.add( ( (StepMeta) sub ).getName() );
        } else if ( sub != null ) {
          r.add( sub.toString() );
        } else if ( s.getStepname() != null ) {
          r.add( s.getStepname() );
        }
      }
    } catch ( Exception e ) {
      // sin destinos conocidos: la vista previa va en su paso
    }
    return r;
  }

  // ---------------------------------------------------------------- respuestas

  /** El plan para la IA: {n, step, action, note_band, column}. */
  ArrayNode planJson() {
    ArrayNode arr = Tools.arr();
    for ( Paso p : pasos ) {
      ObjectNode o = arr.addObject().put( "n", p.n ).put( "step", p.step ).put( "action", p.crea() ? "build"
        : "annotate" );
      Ubicada u = p.pedidas.isEmpty() || plan == null ? null : plan.nota( p.pedidas.get( 0 ) );
      if ( u != null ) {
        o.put( "note_band", u.banda.json );
      }
      if ( plan != null && plan.columna( p.step ) >= 0 ) {
        o.put( "column", plan.columna( p.step ) );
      }
      if ( !p.vistas.isEmpty() ) {
        ArrayNode v = o.putArray( "previews" );
        for ( String s : p.vistas ) {
          v.add( s );
        }
      }
    }
    return arr;
  }

  /** Para las pruebas y la pestana: la nota de un paso que quedo en el lienzo, o null. */
  static NotePadMeta buscarNota( TransMeta t, String huella ) {
    for ( int i = 0; i < t.nrNotes(); i++ ) {
      if ( LeccionNotas.huella( t.getNote( i ).getNote() ).equals( huella ) ) {
        return t.getNote( i );
      }
    }
    return null;
  }
}
