package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.pentaho.di.core.NotePadMeta;
import org.pentaho.di.core.gui.Point;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.ui.spoon.Spoon;
import org.pentaho.metastore.api.IMetaStore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.LeccionLayout.Medidor;
import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.Plano.Destino;
import io.github.ivi2511.pentahomcp.Plano.Etapa;
import io.github.ivi2511.pentahomcp.Plano.Fuente;
import io.github.ivi2511.pentahomcp.informes.Rotulos;
import io.github.ivi2511.pentahomcp.lint.reglas.ReglasBoceto;

/**
 * plan_build (D1) y el lienzo en modo boceto (D2): del plano al esqueleto en Spoon.
 * <ul>
 * <li><b>sketch</b> (por defecto): cada etapa de la transformación es un marcador, un step Dummy con el nombre de la
 * etapa y una nota de color «PENDIENTE · etapa» con lo que tiene que hacer (lee, busca, escribe, reglas, step
 * sugerido). Las notas van en una banda debajo del flujo, una columna por step, sin pisarse.</li>
 * <li><b>steps</b>: el step sugerido por la etapa (step_type, o uno por su clase y formato) sin configurar, con la
 * misma nota; lo que no tiene un step claro (limpiar, transformar) queda como marcador.</li>
 * </ul>
 * Los steps y los hops salen con {@link TransLote} (lo mismo que build_steps: validado antes de tocar nada, animado y
 * ordenado); las notas y todo lo demás van al mismo {@link UndoGrupo}: <b>un solo Ctrl+Z</b>. Con pace=lesson se
 * arma un guion de lesson_start (una etapa por paso, con qué y por qué) y lo reproduce el modo clase.
 * <p>
 * El registro del plano (atributos de Kettle, ver {@link ReglasBoceto}) queda en la transformación: así el menú IDC
 * cuenta el progreso del boceto, el linter marca los marcadores que quedan y plan_check sabe qué etapa es cada step.
 * {@link #rellenar} (fill_placeholder) cambia un marcador por el step o la cadena de steps de verdad.
 */
final class PlanoConstruir {

  static final String SKETCH = "sketch";
  static final String STEPS = "steps";
  /** Caracteres por línea de las notas PENDIENTE (caben en una columna del lienzo ordenado, 170 px). */
  static final int ANCHO_LINEA = 26;
  /** Separación entre las notas y el flujo, y entre notas. */
  static final int AIRE = 14;

  /** El prefijo de la primera línea de una nota PENDIENTE, por idioma (reconoce las notas en cualquiera). */
  static final Map<String, String> PREFIJO = new LinkedHashMap<String, String>();

  static {
    PREFIJO.put( "en", "TO DO · " );
    PREFIJO.put( "es", "PENDIENTE · " );
    PREFIJO.put( "pt", "PENDENTE · " );
  }

  private PlanoConstruir() {
  }

  // ================================================================ qué construir

  /** Las etapas de la transformación en un orden en que cada una viene después de las suyas (las del plano). */
  static List<Etapa> orden( Plano p, String grupo ) {
    List<Etapa> todas = p.etapasDe( grupo );
    List<Etapa> out = new ArrayList<Etapa>();
    Set<String> hechas = new LinkedHashSet<String>();
    boolean avanzo = true;
    while ( out.size() < todas.size() && avanzo ) {
      avanzo = false;
      for ( Etapa e : todas ) {
        if ( hechas.contains( e.id ) ) {
          continue;
        }
        boolean listas = true;
        for ( String a : previas( p, e, grupo ) ) {
          listas &= hechas.contains( a );
        }
        if ( listas ) {
          out.add( e );
          hechas.add( e.id );
          avanzo = true;
        }
      }
    }
    return out; // con un ciclo quedarian afuera, pero Plano.referencias ya los rechaza
  }

  /** Las etapas de la misma transformación que alimentan a e: sus 'after' y la de 'lookup' si es una etapa. */
  static List<String> previas( Plano p, Etapa e, String grupo ) {
    List<String> l = new ArrayList<String>();
    String g = grupo == null ? "" : grupo;
    for ( String a : e.despues ) {
      Etapa x = p.etapa( a );
      if ( x != null && g.equalsIgnoreCase( x.transformacion == null ? "" : x.transformacion ) ) {
        l.add( a );
      }
    }
    if ( e.busca != null ) {
      Etapa x = p.etapa( e.busca );
      if ( x != null && g.equalsIgnoreCase( x.transformacion == null ? "" : x.transformacion ) && !l.contains(
        e.busca ) ) {
        l.add( e.busca );
      }
    }
    return l;
  }

  /** El step para mode=steps: step_type, o uno por la clase de la etapa; Dummy si no hay uno claro. */
  static String tipoSugerido( Plano p, Etapa e ) {
    if ( e.tipoStep != null && !e.tipoStep.trim().isEmpty() ) {
      return e.tipoStep.trim();
    }
    String c = e.clase == null ? "" : e.clase;
    switch ( c ) {
      case "extract": {
        Fuente f = e.lee.isEmpty() ? null : p.fuente( e.lee.get( 0 ) );
        String fmt = f == null || f.formato == null ? "" : f.formato;
        switch ( fmt ) {
          case "csv":
            return "CsvInput";
          case "text":
            return "TextFileInput";
          case "excel":
            return "ExcelInput";
          case "json":
            return "JsonInput";
          case "xml":
            return "getXMLData";
          case "table":
            return "TableInput";
          default:
            return ReglasBoceto.DUMMY;
        }
      }
      case "validate":
        return "FilterRows";
      case "dedupe":
        return "Unique";
      case "lookup":
        return e.busca != null && p.destino( e.busca ) != null ? "DBLookup" : "StreamLookup";
      case "aggregate":
        return "MemoryGroupBy";
      case "reject":
        return "TextFileOutput";
      case "load": {
        Destino d = e.escribe.isEmpty() ? null : p.destino( e.escribe.get( 0 ) );
        if ( d == null ) {
          return ReglasBoceto.DUMMY;
        }
        if ( "file".equals( d.clase ) ) {
          return "TextFileOutput";
        }
        if ( "upsert".equals( d.carga ) ) {
          return "InsertUpdate";
        }
        if ( "scd1".equals( d.carga ) || "scd2".equals( d.carga ) ) {
          return "DimensionLookup";
        }
        return "TableOutput";
      }
      default:
        return ReglasBoceto.DUMMY;
    }
  }

  /** Lo que se va a construir: el lote de build_steps, las etapas que ya estaban y las del lote. */
  static final class Pedido {
    String grupo;
    String modo = SKETCH;
    int delayMs = TransLote.DELAY_DEFECTO;
    final List<Etapa> etapas = new ArrayList<Etapa>();
    final List<String> yaEstaban = new ArrayList<String>();
    ObjectNode lote;
  }

  /**
   * Arma el lote de build_steps para la transformación del plano: un step por etapa (las que ya tienen un step con
   * su nombre no se repiten), 'after' = la primera etapa previa y los demás hops aparte.
   */
  static Pedido pedido( Plano p, String grupo, TransMeta tm, String modo, int delayMs ) throws ToolError {
    Pedido pd = new Pedido();
    pd.grupo = grupo;
    pd.modo = modo == null ? SKETCH : modo;
    pd.delayMs = delayMs;
    ObjectNode o = Tools.obj();
    ArrayNode steps = o.putArray( "steps" );
    ArrayNode hops = o.putArray( "hops" );
    for ( Etapa e : orden( p, grupo ) ) {
      if ( tm != null && tm.findStep( e.nombre ) != null ) {
        pd.yaEstaban.add( e.nombre );
        continue;
      }
      pd.etapas.add( e );
      ObjectNode s = steps.addObject();
      s.put( "type", STEPS.equals( pd.modo ) ? tipoSugerido( p, e ) : ReglasBoceto.DUMMY );
      s.put( "name", e.nombre );
      List<String> prev = previas( p, e, grupo );
      if ( !prev.isEmpty() ) {
        s.put( "after", p.etapa( prev.get( 0 ) ).nombre );
        for ( int i = 1; i < prev.size(); i++ ) {
          hops.addObject().put( "from", p.etapa( prev.get( i ) ).nombre ).put( "to", e.nombre );
        }
      }
    }
    // hops entre etapas que ya estaban y nuevas (las nuevas con 'after' a una vieja ya se conectan solas)
    if ( steps.size() == 0 ) {
      throw new ToolError( pd.yaEstaban.isEmpty() ? "The plan has no stages for transformation '" + nombreGrupo( grupo )
        + "'" : "Every stage of '" + nombreGrupo( grupo ) + "' is already on the canvas (" + String.join( ", ",
          pd.yaEstaban ) + "): nothing to build. Use plan_check to compare it with the plan." );
    }
    if ( steps.size() > TransLote.MAX_STEPS ) {
      throw new ToolError( "Transformation '" + nombreGrupo( grupo ) + "' has " + steps.size() + " stages: at most "
        + TransLote.MAX_STEPS + " per build. Split it into several transformations in the plan." );
    }
    o.put( "delay_ms", delayMs );
    o.put( "arrange", true );
    pd.lote = o;
    return pd;
  }

  static String nombreGrupo( String g ) {
    return g == null || g.isEmpty() ? "(main)" : g;
  }

  // ================================================================ notas

  /** El texto de la nota PENDIENTE de una etapa. */
  static String textoNota( Plano p, Etapa e, String idioma, boolean conTipo ) {
    Rotulos r = Rotulos.de( PlanoInforme.AREA, idioma );
    String pref = PREFIJO.get( Rotulos.normalizar( idioma ) );
    StringBuilder sb = new StringBuilder( pref == null ? PREFIJO.get( "en" ) : pref ).append( e.nombre );
    List<String> cuerpo = new ArrayList<String>();
    if ( e.descripcion != null ) {
      cuerpo.add( e.descripcion );
    } else if ( e.clase != null ) {
      cuerpo.add( r.s( "clase_etapa." + e.clase ) );
    }
    if ( !e.lee.isEmpty() ) {
      cuerpo.add( r.f( "nota.lee", nombres( p, e.lee ) ) );
    }
    if ( e.busca != null ) {
      String falta = e.siFalta == null ? r.s( "nota.si_falta.null" ) : r.s( "si_falta." + e.siFalta );
      cuerpo.add( r.f( "nota.busca", p.nombreDe( e.busca ), e.clave.isEmpty() ? "?" : String.join( ", ", e.clave ),
        falta ) );
    } else if ( !e.clave.isEmpty() ) {
      cuerpo.add( r.f( "nota.clave", String.join( ", ", e.clave ) ) );
    }
    if ( !e.escribe.isEmpty() ) {
      List<String> ds = new ArrayList<String>();
      for ( String id : e.escribe ) {
        Destino d = p.destino( id );
        ds.add( d == null ? id : d.carga == null ? d.nombre : d.nombre + " (" + r.s( "carga_destino." + d.carga )
          + ")" );
      }
      cuerpo.add( r.f( "nota.escribe", String.join( ", ", ds ) ) );
    }
    for ( String id : e.reglas ) {
      Plano.Regla x = p.regla( id );
      if ( x != null ) {
        cuerpo.add( id + ": " + x.texto );
      }
    }
    if ( conTipo ) {
      String t = tipoSugerido( p, e );
      if ( !ReglasBoceto.DUMMY.equals( t ) ) {
        cuerpo.add( r.f( "nota.step", t ) );
      }
    }
    for ( String c : cuerpo ) {
      for ( String l : envolver( c, ANCHO_LINEA ) ) {
        sb.append( '\n' ).append( l );
      }
    }
    return sb.toString();
  }

  /** Parte un texto en líneas de hasta 'ancho' caracteres por palabras (una palabra larga queda sola). */
  static List<String> envolver( String texto, int ancho ) {
    List<String> out = new ArrayList<String>();
    for ( String parrafo : texto.split( "\n" ) ) {
      StringBuilder l = new StringBuilder();
      for ( String w : parrafo.trim().split( "\\s+" ) ) {
        if ( w.isEmpty() ) {
          continue;
        }
        if ( l.length() > 0 && l.length() + 1 + w.length() > ancho ) {
          out.add( l.toString() );
          l.setLength( 0 );
        }
        l.append( l.length() == 0 ? "" : " " ).append( w );
      }
      if ( l.length() > 0 ) {
        out.add( l.toString() );
      }
    }
    return out;
  }

  /** {fondo, borde, letra} por clase de etapa. */
  static int[][] color( String clase ) {
    String c = clase == null ? "" : clase;
    switch ( c ) {
      case "extract":
        return new int[][] { { 221, 235, 247 }, { 70, 110, 160 }, { 20, 40, 80 } };
      case "clean":
      case "transform":
        return new int[][] { { 226, 244, 236 }, { 60, 140, 110 }, { 15, 60, 45 } };
      case "validate":
      case "dedupe":
        return new int[][] { { 255, 239, 213 }, { 200, 130, 40 }, { 90, 50, 10 } };
      case "lookup":
        return new int[][] { { 236, 232, 250 }, { 110, 90, 170 }, { 45, 30, 90 } };
      case "aggregate":
        return new int[][] { { 243, 235, 225 }, { 150, 110, 70 }, { 70, 45, 20 } };
      case "load":
        return new int[][] { { 228, 233, 240 }, { 70, 90, 120 }, { 25, 35, 55 } };
      case "reject":
        return new int[][] { { 253, 226, 222 }, { 190, 70, 60 }, { 110, 20, 10 } };
      default:
        return new int[][] { { 255, 248, 214 }, { 190, 160, 70 }, { 40, 35, 20 } };
    }
  }

  static void estilizar( NotePadMeta n, String clase ) {
    int[][] e = color( clase );
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
    n.setFontBold( false );
    n.setFontItalic( false );
    n.setFontSize( -1 );
  }

  /** La etapa (nombre) de una nota PENDIENTE, o null si la nota no lo es. */
  static String etapaDeNota( NotePadMeta n ) {
    String t = n == null || n.getNote() == null ? "" : n.getNote();
    String primera = t.split( "\n", 2 )[ 0 ].trim();
    for ( String pref : PREFIJO.values() ) {
      if ( primera.startsWith( pref.trim() ) ) {
        return primera.substring( pref.trim().length() ).trim();
      }
    }
    return null;
  }

  /**
   * Crea las notas PENDIENTE de las etapas (una por step creado) y las ubica en una banda debajo del flujo: cada una
   * bajo la columna de su step, apiladas si caen en la misma. Registra todo en u. (UI o sin Spoon)
   */
  static List<NotePadMeta> notas( TransMeta tm, Plano p, List<Etapa> etapas, String idioma, boolean conTipo,
                                  Medidor m, UndoGrupo u ) {
    int yMax = 0;
    for ( StepMeta s : tm.getSteps() ) {
      if ( s.getLocation() != null ) {
        yMax = Math.max( yMax, s.getLocation().y );
      }
    }
    int y0 = yMax + 32 + 30 + AIRE; // icono (32) + nombre del step; las notas que ya estaban cuentan como ocupadas
    List<int[]> ocupado = new ArrayList<int[]>();
    for ( int i = 0; i < tm.nrNotes(); i++ ) {
      NotePadMeta n = tm.getNote( i );
      if ( n.getLocation() != null ) {
        ocupado.add( new int[] { n.getLocation().x, n.getLocation().y, Math.max( n.getWidth(), 20 ), Math.max(
          n.getHeight(), 20 ) } );
      }
    }
    List<NotePadMeta> creadas = new ArrayList<NotePadMeta>();
    List<Object> objs = new ArrayList<Object>();
    for ( Etapa e : etapas ) {
      StepMeta s = tm.findStep( e.nombre );
      if ( s == null ) {
        continue;
      }
      String texto = textoNota( p, e, idioma, conTipo );
      Point tam = m.texto( texto, false );
      int w = tam.x + 12;
      int h = tam.y + 10;
      int x = Math.max( 10, s.getLocation().x - 8 );
      int y = y0;
      while ( choca( ocupado, x, y, w, h ) ) {
        y += AIRE;
      }
      NotePadMeta n = new NotePadMeta( texto, x, y, w, h );
      estilizar( n, e.clase );
      tm.addNote( n );
      ocupado.add( new int[] { x, y, w, h } );
      creadas.add( n );
      objs.add( n );
    }
    if ( !creadas.isEmpty() ) {
      int[] idx = new int[ creadas.size() ];
      for ( int i = 0; i < idx.length; i++ ) {
        idx[ i ] = tm.indexOfNote( creadas.get( i ) );
      }
      u.nuevo( objs.toArray(), idx );
    }
    return creadas;
  }

  private static boolean choca( List<int[]> rs, int x, int y, int w, int h ) {
    for ( int[] r : rs ) {
      if ( x < r[ 0 ] + r[ 2 ] + 6 && r[ 0 ] < x + w + 6 && y < r[ 1 ] + r[ 3 ] + 6 && r[ 1 ] < y + h + 6 ) {
        return true;
      }
    }
    return false;
  }

  // ================================================================ registro

  /** Deja el registro del plano en la transformación y marca los marcadores. */
  static void registrar( TransMeta tm, Plano p, String grupo, List<Etapa> creadas ) {
    if ( p.archivo != null ) {
      tm.setAttribute( ReglasBoceto.GRUPO, ReglasBoceto.K_PLANO, p.archivo.getPath() );
    }
    tm.setAttribute( ReglasBoceto.GRUPO, ReglasBoceto.K_NOMBRE, p.nombre );
    tm.setAttribute( ReglasBoceto.GRUPO, ReglasBoceto.K_TRANS, grupo == null ? "" : grupo );
    List<Etapa> todas = p.etapasDe( grupo );
    tm.setAttribute( ReglasBoceto.GRUPO, ReglasBoceto.K_ETAPAS, Integer.toString( todas.size() ) );
    for ( int i = 0; i < todas.size(); i++ ) {
      tm.setAttribute( ReglasBoceto.GRUPO, ReglasBoceto.P_ETAPA + i, todas.get( i ).id + "\t" + todas.get( i ).nombre );
    }
    for ( Etapa e : creadas ) {
      StepMeta s = tm.findStep( e.nombre );
      if ( s != null ) {
        s.setAttribute( ReglasBoceto.GRUPO, ReglasBoceto.DUMMY.equals( s.getStepID() ) ? ReglasBoceto.K_MARCADOR
          : ReglasBoceto.K_ETAPA, e.id );
        if ( ReglasBoceto.DUMMY.equals( s.getStepID() ) && ( s.getDescription() == null || s.getDescription()
          .isEmpty() ) ) {
          s.setDescription( e.descripcion == null ? e.nombre : e.descripcion );
        }
      }
    }
  }

  /** La ruta del plano registrada en la transformación, o null. */
  static String planoDe( TransMeta tm ) {
    String s = tm == null ? null : tm.getAttribute( ReglasBoceto.GRUPO, ReglasBoceto.K_PLANO );
    return s == null || s.trim().isEmpty() ? null : s.trim();
  }

  // ================================================================ progreso (D2)

  /** Cuánto del boceto está hecho. */
  static final class Progreso {
    String plano;
    String nombrePlano;
    String transformacion;
    int total;
    int construidas;
    final List<String> pendientes = new ArrayList<String>();
    final List<String> faltan = new ArrayList<String>();

    ObjectNode json() {
      ObjectNode o = Tools.obj();
      if ( plano != null ) {
        o.put( "plan", plano );
      }
      if ( transformacion != null ) {
        o.put( "plan_transformation", transformacion );
      }
      o.put( "stages", total );
      o.put( "built", construidas );
      ArrayNode a = o.putArray( "placeholders" );
      for ( String s : pendientes ) {
        a.add( s );
      }
      if ( !faltan.isEmpty() ) {
        ArrayNode b = o.putArray( "without_step" );
        for ( String s : faltan ) {
          b.add( s );
        }
      }
      return o;
    }
  }

  /**
   * El progreso del boceto de la transformación, o null si no tiene registro de plano. Una etapa está construida si
   * tiene un step que no es marcador (el que la reemplazó, o uno con su nombre); pendiente si su marcador sigue; sin
   * step si no hay ninguno (se borró o se renombró).
   */
  static Progreso progreso( TransMeta tm ) {
    Map<String, String> reg = ReglasBoceto.registro( tm );
    if ( reg.isEmpty() ) {
      return null;
    }
    Progreso pr = new Progreso();
    pr.plano = planoDe( tm );
    pr.nombrePlano = tm.getAttribute( ReglasBoceto.GRUPO, ReglasBoceto.K_NOMBRE );
    pr.transformacion = tm.getAttribute( ReglasBoceto.GRUPO, ReglasBoceto.K_TRANS );
    pr.total = reg.size();
    Map<String, StepMeta> marcadores = new HashMap<String, StepMeta>();
    Map<String, StepMeta> reales = new HashMap<String, StepMeta>();
    for ( StepMeta s : tm.getSteps() ) {
      String m = ReglasBoceto.etapaDeMarcador( tm, s, reg );
      if ( m != null ) {
        marcadores.put( m, s );
        continue;
      }
      String e = s.getAttribute( ReglasBoceto.GRUPO, ReglasBoceto.K_ETAPA );
      if ( e != null ) {
        reales.put( e, s );
      }
      for ( Map.Entry<String, String> x : reg.entrySet() ) {
        if ( x.getValue().equalsIgnoreCase( s.getName() ) && !reales.containsKey( x.getKey() ) ) {
          reales.put( x.getKey(), s );
        }
      }
    }
    for ( Map.Entry<String, String> x : reg.entrySet() ) {
      if ( reales.containsKey( x.getKey() ) ) {
        pr.construidas++;
      } else if ( marcadores.containsKey( x.getKey() ) ) {
        pr.pendientes.add( x.getValue() );
      } else {
        pr.faltan.add( x.getValue() );
      }
    }
    return pr;
  }

  // ================================================================ construir sin animar

  /** Lo que devuelve construir: el resultado de TransLote más las notas y las etapas que ya estaban. */
  static ObjectNode construirAhora( Spoon sp, TransMeta tm, Plano p, String grupo, String modo, String idioma,
                                    IMetaStore ms ) throws Exception {
    Pedido pd = pedido( p, grupo, tm, modo, 0 );
    List<String> problemas = new ArrayList<String>();
    TransLote.Lote l = TransLote.leer( pd.lote, problemas );
    if ( problemas.isEmpty() ) {
      problemas.addAll( TransLote.validar( tm, l, ms ) );
    }
    if ( !problemas.isEmpty() ) {
      throw new ToolError( "plan_build: nothing was changed:\n- " + String.join( "\n- ", problemas ) );
    }
    UndoGrupo u = sp == null ? UndoGrupo.sinSpoon( tm ) : new UndoGrupo( sp, tm );
    TransLote.Ejecucion e = TransLote.empezar( tm, l, u, ms );
    for ( int i = 0; i < l.steps.size(); i++ ) {
      TransLote.agregar( e, l, i );
    }
    TransLayout.Movimientos mov = TransLote.cerrar( e, l, true );
    return terminar( e, l, mov, p, pd, idioma );
  }

  /** Después de cerrar el lote: notas, registro y la respuesta. (UI o sin Spoon) */
  static ObjectNode terminar( TransLote.Ejecucion e, TransLote.Lote l, TransLayout.Movimientos mov, Plano p, Pedido pd,
                              String idioma ) {
    LeccionNotas.MedidorCerrable m = LeccionNotas.medidor( -1 );
    List<NotePadMeta> notas;
    try {
      notas = notas( e.tm, p, pd.etapas, idioma, STEPS.equals( pd.modo ), m, e.undo );
    } finally {
      m.cerrar();
    }
    registrar( e.tm, p, pd.grupo, pd.etapas );
    e.tm.setChanged();
    ObjectNode o = TransLote.resultado( e, l, mov );
    o.put( "mode", pd.modo );
    o.put( "plan_transformation", nombreGrupo( pd.grupo ) );
    o.put( "notes_added", notas.size() );
    if ( !pd.yaEstaban.isEmpty() ) {
      ArrayNode a = o.putArray( "skipped_already_on_canvas" );
      for ( String s : pd.yaEstaban ) {
        a.add( s );
      }
    }
    Progreso pr = progreso( e.tm );
    if ( pr != null ) {
      o.set( "sketch", pr.json() );
    }
    o.put( "undo", "One Ctrl+Z in Spoon removes the whole skeleton (steps, hops and notes)." );
    return o;
  }

  // ================================================================ MCP (con Spoon y animado)

  /** plan_build: valida en la UI, un Docs.ui por step con pausas afuera, y cierra con las notas. */
  static Object ejecutar( final JsonNode a ) throws Exception {
    final Plano p = PlanoTools.cargar( a );
    final String grupo = PlanoTools.grupo( p, Tools.optStr( a, "transformation", null ) );
    final String modo = Tools.optStr( a, "mode", SKETCH ).trim().toLowerCase( Locale.ROOT );
    if ( !SKETCH.equals( modo ) && !STEPS.equals( modo ) ) {
      throw new ToolError( "mode must be sketch or steps" );
    }
    final String ritmo = Tools.optStr( a, "pace", "normal" ).trim().toLowerCase( Locale.ROOT );
    final String idioma = PlanoInforme.idioma( p, Tools.optStr( a, "language", null ) );
    int delay = Math.max( 0, Math.min( TransLote.DELAY_MAX, Tools.optInt( a, "delay_ms", TransLote.DELAY_DEFECTO ) ) );
    if ( "lesson".equals( ritmo ) ) {
      return leccion( a, p, grupo, modo, idioma );
    }
    if ( !"normal".equals( ritmo ) ) {
      throw new ToolError( "pace must be normal or lesson" );
    }
    final Pedido[] pd = new Pedido[ 1 ];
    final TransLote.Lote[] l = new TransLote.Lote[ 1 ];
    final TransLote.Ejecucion e = Docs.ui( () -> {
      Spoon sp = Docs.spoon();
      TransMeta tm = destino( sp, a, p, grupo );
      pd[ 0 ] = pedido( p, grupo, tm, modo, delay );
      List<String> problemas = new ArrayList<String>();
      l[ 0 ] = TransLote.leer( pd[ 0 ].lote, problemas );
      if ( problemas.isEmpty() ) {
        problemas.addAll( TransLote.validar( tm, l[ 0 ], sp.getMetaStore() ) );
      }
      if ( !problemas.isEmpty() ) {
        throw new ToolError( "plan_build: nothing was changed. Fix the plan and call again:\n- " + String.join(
          "\n- ", problemas ) );
      }
      return TransLote.empezar( tm, l[ 0 ], new UndoGrupo( sp, tm ), sp.getMetaStore() );
    } );
    Exception fallo = null;
    for ( int i = 0; i < l[ 0 ].steps.size(); i++ ) {
      if ( i > 0 && delay > 0 ) {
        try {
          Thread.sleep( delay );
        } catch ( InterruptedException ie ) {
          Thread.currentThread().interrupt();
          fallo = new ToolError( "interrupted" );
          break;
        }
      }
      final int k = i;
      try {
        Docs.ui( () -> {
          Docs.activar( e.tm );
          TransLote.agregar( e, l[ 0 ], k );
          Docs.touched( e.tm );
          return null;
        } );
      } catch ( Exception ex ) {
        fallo = ex;
        break;
      }
    }
    final boolean completo = fallo == null;
    ObjectNode res = Docs.ui( () -> {
      TransLayout.Movimientos mov = TransLote.cerrar( e, l[ 0 ], completo );
      ObjectNode o = completo ? terminar( e, l[ 0 ], mov, p, pd[ 0 ], idioma ) : null;
      Docs.touched( e.tm );
      if ( o != null ) {
        o.put( "document", Docs.id( e.tm ) );
      }
      return o;
    } );
    if ( fallo != null ) {
      throw new ToolError( "plan_build stopped: " + TransOps.mensaje( fallo ) + ". One Ctrl+Z in Spoon removes what "
        + "was created." );
    }
    return res;
  }

  /**
   * La transformación donde construir: 'document' si viene; si no, una abierta que ya tenga el registro de esta
   * transformación del plano; si no, con new_document=true una nueva con el nombre de la transformación del plano; si
   * no, la activa. (UI)
   */
  static TransMeta destino( Spoon sp, JsonNode a, Plano p, String grupo ) throws Exception {
    if ( Tools.optStr( a, "document", null ) != null ) {
      return Docs.trans( a );
    }
    for ( org.pentaho.di.base.AbstractMeta m : Docs.abiertos() ) {
      if ( m instanceof TransMeta && esDelPlano( (TransMeta) m, p, grupo ) ) {
        Docs.activar( m );
        return (TransMeta) m;
      }
    }
    if ( Tools.optBool( a, "new_document", false ) ) {
      Docs.perspectivaPrincipal();
      sp.newTransFile();
      org.pentaho.di.base.AbstractMeta nuevo = Docs.activo();
      if ( !( nuevo instanceof TransMeta ) ) {
        throw new ToolError( "Spoon did not create the transformation" );
      }
      TransMeta tm = (TransMeta) nuevo;
      tm.setName( grupo == null || grupo.isEmpty() ? p.nombre : grupo );
      sp.setShellText();
      return tm;
    }
    return Docs.trans( a );
  }

  /** La transformación tiene el registro de esta transformación de este plano. */
  static boolean esDelPlano( TransMeta tm, Plano p, String grupo ) {
    String ruta = planoDe( tm );
    String g = tm.getAttribute( ReglasBoceto.GRUPO, ReglasBoceto.K_TRANS );
    if ( ruta == null || p.archivo == null || g == null ) {
      return false;
    }
    return new File( ruta ).getAbsoluteFile().equals( p.archivo.getAbsoluteFile() ) && g.equalsIgnoreCase(
      grupo == null ? "" : grupo );
  }

  /** pace=lesson: el guion de lesson_start con una etapa por paso; el modo clase lo reproduce. */
  static Object leccion( JsonNode a, Plano p, String grupo, String modo, String idioma ) throws Exception {
    LeccionMotor.Entorno ent = LeccionMotor.spoon();
    final TransMeta tm = Docs.ui( () -> destino( Docs.spoon(), a, p, grupo ) );
    Pedido pd = Docs.ui( () -> pedido( p, grupo, tm, modo, 0 ) );
    Rotulos r = Rotulos.de( PlanoInforme.AREA, idioma );
    ObjectNode args = Tools.obj();
    args.put( "document", Docs.ui( () -> Docs.id( tm ) ) );
    args.put( "title", r.f( "leccion.titulo", p.nombre, nombreGrupo( grupo ) ) );
    args.put( "language", idioma );
    args.put( "pace", Tools.optStr( a, "lesson_pace", "guided" ) );
    if ( p.objetivo != null ) {
      args.put( "intro", p.objetivo );
    }
    ArrayNode steps = args.putArray( "steps" );
    ArrayNode hops = args.putArray( "hops" );
    JsonNode lote = pd.lote;
    Map<String, Etapa> porNombre = new HashMap<String, Etapa>();
    for ( Etapa e : pd.etapas ) {
      porNombre.put( e.nombre, e );
    }
    for ( JsonNode s : lote.path( "steps" ) ) {
      Etapa e = porNombre.get( s.path( "name" ).asText() );
      ObjectNode x = steps.addObject();
      x.put( "type", s.path( "type" ).asText() );
      x.put( "name", s.path( "name" ).asText() );
      if ( s.has( "after" ) ) {
        x.put( "after", s.path( "after" ).asText() );
      }
      String nota = textoNota( p, e, idioma, false );
      String cuerpo = nota.indexOf( '\n' ) > 0 ? nota.substring( nota.indexOf( '\n' ) + 1 ).replace( '\n', ' ' )
        : e.nombre;
      x.put( "what", e.descripcion != null ? e.descripcion : cuerpo );
      x.put( "why", porque( p, e, r ) );
    }
    for ( JsonNode h : lote.path( "hops" ) ) {
      hops.add( h );
    }
    Docs.ui( () -> {
      registrar( tm, p, grupo, new ArrayList<Etapa>() );
      return null;
    } );
    Leccion l = LeccionMotor.empezar( ent, args, tm );
    String sinPestana;
    try {
      sinPestana = Docs.ui( () -> LeccionPanel.abrir( l ) );
    } catch ( Throwable t ) {
      sinPestana = TransOps.mensaje( t );
    }
    ObjectNode o = LeccionMotor.inicioJson( l );
    o.put( "mode", modo );
    o.put( "plan_transformation", nombreGrupo( grupo ) );
    o.put( "pace", "lesson" );
    if ( sinPestana != null ) {
      ArrayNode w = o.has( "warnings" ) ? (ArrayNode) o.get( "warnings" ) : o.putArray( "warnings" );
      w.add( "The Lesson tab could not be opened (" + sinPestana + "): use lesson_next to advance." );
    }
    return o;
  }

  private static String porque( Plano p, Etapa e, Rotulos r ) {
    List<String> l = new ArrayList<String>();
    for ( String id : e.reglas ) {
      Plano.Regla x = p.regla( id );
      if ( x != null ) {
        l.add( x.texto );
      }
    }
    if ( !l.isEmpty() ) {
      return String.join( " ", l );
    }
    return e.clase == null ? r.s( "leccion.porque" ) : r.s( "porque." + e.clase );
  }

  // ================================================================ fill_placeholder

  /** Un step de la cadena que reemplaza a un marcador. */
  static final class Pieza {
    String tipo;
    String nombre;
    String config;
  }

  /**
   * Cambia el marcador por la cadena de steps: el primero recibe los hops que entraban al marcador y el último da los
   * que salían; se borran el Dummy y su nota PENDIENTE (salvo keepNote). Todo en u (un Ctrl+Z). (UI o sin Spoon)
   */
  static ObjectNode rellenar( TransMeta tm, String paso, List<Pieza> piezas, boolean conservarNota, UndoGrupo u,
                              IMetaStore ms ) throws Exception {
    StepMeta d = TransOps.paso( tm, paso );
    Map<String, String> reg = ReglasBoceto.registro( tm );
    String etapa = ReglasBoceto.etapaDeMarcador( tm, d, reg );
    if ( etapa == null ) {
      throw new ToolError( "'" + d.getName() + "' is not a placeholder of the sketch (a Dummy step left by plan_build)"
        + ". Use add_step / build_steps instead." );
    }
    if ( piezas.isEmpty() ) {
      throw new ToolError( "steps must have at least one {type, name?, config_xml?}" );
    }
    // validar antes de tocar nada
    Set<String> nombres = new LinkedHashSet<String>();
    for ( int i = 0; i < piezas.size(); i++ ) {
      Pieza x = piezas.get( i );
      if ( x.nombre == null || x.nombre.trim().isEmpty() ) {
        x.nombre = i == 0 ? d.getName() : d.getName() + " " + ( i + 1 );
      }
      x.nombre = x.nombre.trim();
      TransOps.plugin( x.tipo );
      if ( !nombres.add( x.nombre.toLowerCase( Locale.ROOT ) ) ) {
        throw new ToolError( "Two steps of the chain are called '" + x.nombre + "'" );
      }
      StepMeta otro = tm.findStep( x.nombre );
      if ( otro != null && otro != d ) {
        throw new ToolError( "There is already a step named '" + otro.getName() + "'" );
      }
    }
    Point pos = d.getLocation() == null ? new Point( 100, 100 ) : new Point( d.getLocation().x, d.getLocation().y );
    List<TransHopMeta> entran = new ArrayList<TransHopMeta>();
    List<TransHopMeta> salen = new ArrayList<TransHopMeta>();
    for ( TransHopMeta h : TransOps.hopsDe( tm, d ) ) {
      ( h.getToStep() == d ? entran : salen ).add( h );
    }
    List<StepMeta> vecinos = new ArrayList<StepMeta>();
    for ( TransHopMeta h : entran ) {
      if ( !vecinos.contains( h.getFromStep() ) ) {
        vecinos.add( h.getFromStep() );
      }
    }
    for ( TransHopMeta h : salen ) {
      if ( !vecinos.contains( h.getToStep() ) ) {
        vecinos.add( h.getToStep() );
      }
    }
    TransTools.Cambios cambios = TransTools.Cambios.antes( vecinos );
    // 1. borrar hops y el Dummy (el par DELETE_HOP + DELETE_STEP que Spoon deshace junto)
    List<TransHopMeta> todos = new ArrayList<TransHopMeta>( entran );
    todos.addAll( salen );
    if ( !todos.isEmpty() ) {
      Object[] clones = new Object[ todos.size() ];
      int[] idx = new int[ todos.size() ];
      for ( int i = 0; i < todos.size(); i++ ) {
        clones[ i ] = todos.get( i ).clone();
        idx[ i ] = tm.indexOfTransHop( todos.get( i ) );
      }
      for ( TransHopMeta h : todos ) {
        tm.removeTransHop( tm.indexOfTransHop( h ) );
        TransOps.limpiarTrasQuitarHop( h );
      }
      u.borrado( clones, idx );
    }
    int iDummy = tm.indexOfStep( d );
    tm.removeStep( iDummy );
    u.borrado( new Object[] { d }, new int[] { iDummy } );
    // 2. la cadena nueva
    List<StepMeta> nuevos = new ArrayList<StepMeta>();
    List<TransHopMeta> hops = new ArrayList<TransHopMeta>();
    for ( int i = 0; i < piezas.size(); i++ ) {
      Pieza x = piezas.get( i );
      TransOps.Agregado ag = TransOps.agregarStep( tm, x.tipo, x.nombre, x.config, pos.x + i * TransOps.PASO_X,
        pos.y, i == 0 ? null : piezas.get( i - 1 ).nombre, d.getDescription(), ms );
      ag.step.setAttribute( ReglasBoceto.GRUPO, ReglasBoceto.K_ETAPA, etapa );
      nuevos.add( ag.step );
      if ( ag.hop != null ) {
        hops.add( ag.hop );
      }
    }
    Object[] objs = nuevos.toArray();
    int[] idxS = new int[ nuevos.size() ];
    for ( int i = 0; i < idxS.length; i++ ) {
      idxS[ i ] = tm.indexOfStep( nuevos.get( i ) );
    }
    u.nuevo( objs, idxS );
    StepMeta primero = nuevos.get( 0 );
    StepMeta ultimo = nuevos.get( nuevos.size() - 1 );
    for ( TransHopMeta h : entran ) {
      hops.add( TransOps.conectar( tm, h.getFromStep(), primero, h.isEnabled(), h.isErrorHop() ) );
    }
    for ( TransHopMeta h : salen ) {
      hops.add( TransOps.conectar( tm, ultimo, h.getToStep(), h.isEnabled(), false ) );
    }
    if ( !hops.isEmpty() ) {
      Object[] ho = new Object[ hops.size() ];
      int[] hi = new int[ hops.size() ];
      List<TransHopMeta> orden = new ArrayList<TransHopMeta>( hops );
      orden.sort( ( x, y ) -> Integer.compare( tm.indexOfTransHop( x ), tm.indexOfTransHop( y ) ) );
      for ( int i = 0; i < orden.size(); i++ ) {
        ho[ i ] = orden.get( i );
        hi[ i ] = tm.indexOfTransHop( orden.get( i ) );
      }
      u.nuevo( ho, hi );
    }
    TransOps.resolverReferencias( tm );
    cambios.registrar( u, tm );
    // 3. la nota PENDIENTE
    boolean notaQuitada = false;
    if ( !conservarNota ) {
      for ( int i = tm.nrNotes() - 1; i >= 0; i-- ) {
        NotePadMeta n = tm.getNote( i );
        String e = etapaDeNota( n );
        if ( e != null && e.equalsIgnoreCase( d.getName() ) ) {
          tm.removeNote( i );
          u.borrado( new Object[] { n }, new int[] { i } );
          notaQuitada = true;
          break;
        }
      }
    }
    // 4. una cadena larga empuja: ordenar el lienzo
    TransLayout.Movimientos mov = null;
    if ( nuevos.size() > 1 ) {
      mov = TransLayout.ordenar( tm );
      if ( !mov.objetos.isEmpty() ) {
        u.posicion( mov.objetos(), mov.indices(), mov.antes(), mov.despues() );
      }
    }
    tm.unselectAll();
    for ( StepMeta s : nuevos ) {
      s.setSelected( true );
    }
    tm.clearCaches();
    tm.setChanged();
    ObjectNode o = Tools.obj();
    o.put( "replaced", d.getName() );
    o.put( "stage", etapa );
    ArrayNode a = o.putArray( "steps" );
    for ( StepMeta s : nuevos ) {
      a.addObject().put( "name", s.getName() ).put( "type", s.getStepID() );
    }
    o.put( "hops_kept", entran.size() + salen.size() );
    o.put( "note_removed", notaQuitada );
    if ( mov != null && !mov.objetos.isEmpty() ) {
      o.put( "canvas_arranged", true );
    }
    Progreso pr = progreso( tm );
    if ( pr != null ) {
      o.set( "sketch", pr.json() );
    }
    o.put( "undo", "One Ctrl+Z in Spoon brings the placeholder back." );
    return o;
  }

  /** Lee 'steps' (o type/name/config_xml sueltos) de fill_placeholder. */
  static List<Pieza> piezas( JsonNode a ) throws ToolError {
    List<Pieza> l = new ArrayList<Pieza>();
    JsonNode s = a.get( "steps" );
    if ( s != null && s.isTextual() ) {
      try {
        s = McpHttpServer.JSON.readTree( s.asText() );
      } catch ( Exception e ) {
        throw new ToolError( "'steps' must be an array of {type, name?, config_xml?}" );
      }
    }
    if ( s != null && s.isArray() ) {
      for ( JsonNode n : s ) {
        Pieza x = new Pieza();
        x.tipo = Tools.optStr( n, "type", null );
        if ( x.tipo == null ) {
          throw new ToolError( "Every item of 'steps' needs a type" );
        }
        x.nombre = Tools.optStr( n, "name", null );
        x.config = Tools.optStr( n, "config_xml", null );
        l.add( x );
      }
    } else if ( Tools.optStr( a, "type", null ) != null ) {
      Pieza x = new Pieza();
      x.tipo = Tools.optStr( a, "type", null );
      x.nombre = Tools.optStr( a, "name", null );
      x.config = Tools.optStr( a, "config_xml", null );
      l.add( x );
    }
    if ( l.isEmpty() ) {
      throw new ToolError( "Pass 'type' (one step) or 'steps' (a chain of {type, name?, config_xml?})" );
    }
    if ( l.size() > 10 ) {
      throw new ToolError( "At most 10 steps replace one placeholder" );
    }
    return l;
  }

  private static String nombres( Plano p, List<String> ids ) {
    List<String> l = new ArrayList<String>();
    for ( String id : ids ) {
      l.add( p.nombreDe( id ) );
    }
    return String.join( ", ", l );
  }

  /** Para pruebas y diagnóstico: los prefijos en orden. */
  static List<String> prefijos() {
    return new ArrayList<String>( Arrays.asList( PREFIJO.values().toArray( new String[ 0 ] ) ) );
  }
}
