package io.github.ivi2511.pentahomcp;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.core.NotePadMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.ivi2511.pentahomcp.LeccionLayout.Tipo;
import io.github.ivi2511.pentahomcp.LeccionNotas.Entrada;
import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.informes.Captura;

/**
 * Modo clase, WP6: el contrato {@code LessonHandout} de la guia de la leccion (docs/modo-clase-diseno.md §6 y §9).
 * Junta, en el orden de la leccion, el titulo y el objetivo, cada paso (step, tipo, que, por que, ¡Ojo! con sus
 * codigos del manual, tip, pregunta, glosario del tipo y las filas de la vista previa capturadas en clase), el
 * ejercicio final y los errores comunes de los tipos usados. {@link SecLeccion} lo pasa a la seccion lesson.
 * <p>
 * Dos fuentes: la leccion en memoria ({@link LeccionMotor}) si es de ese documento, con las filas; si Spoon se
 * reinicio (o el documento se abrio de un archivo), el registro del documento (grupo {@value LeccionNotas#GRUPO}) y
 * los textos de las notas (src.K, o la nota del lienzo o de las ocultas por huella), sin filas y sin el ejercicio
 * (el outro no es una nota). La copia de la captura no trae los atributos (realClone los vacia): CapturaUi copia
 * el grupo con {@link #copiarRegistro}. Sin Spoon y sin UI: solo lee.
 */
final class LeccionGuia {

  static final String MEMORIA = "memory";
  static final String NOTAS = "notes";
  /** Codigos de manual/03-errores-comunes.md (C13, D3...). */
  private static final Pattern CODIGO = Pattern.compile( "\\b([CD]\\d{1,2})\\b" );
  /** «3 · Nombre (Tipo)»: numero, nombre y tipo de la primera linea de la nota de un paso. */
  private static final Pattern CABECERA = Pattern.compile( "^\\s*(\\d+)\\s*·\\s*(.*?)(?:\\s*\\(([^()]*)\\))?\\s*$" );

  /** Un paso de la guia. */
  static final class Paso {
    int n;
    String step;
    /** Id del tipo (CsvInput); null si no se sabe. */
    String tipoId;
    /** Nombre del tipo como en la nota («CSV file input» o el del idioma de Spoon). */
    String tipo;
    String que;
    String porque;
    String ojo;
    final List<String> codigos = new ArrayList<String>();
    String tip;
    String pregunta;
    /** Glosario del tipo en el idioma de la guia. */
    String glosarioQue;
    String analogia;
    /** {fields, rows, row_count, errors...} de la vista previa en clase, o null. */
    ObjectNode vista;
    /** false: la leccion en memoria todavia no llego a este paso. */
    boolean dado = true;
  }

  /** Un error comun de los tipos de la leccion. */
  static final class ErrorComun {
    String codigo;
    String titulo;
    final List<String> steps = new ArrayList<String>();
  }

  String id;
  String titulo;
  String nivel;
  String idioma;
  String intro;
  String outro;
  /** {@link #MEMORIA} o {@link #NOTAS}. */
  String origen;
  /** Estado de la leccion en memoria (done, waiting_next...); null desde las notas. */
  String estado;
  int posicion;
  int notasEnLienzo;
  int notasOcultas;
  final List<Paso> pasos = new ArrayList<Paso>();
  final List<ErrorComun> errores = new ArrayList<ErrorComun>();

  private LeccionGuia() {
  }

  // ---------------------------------------------------------------- registro en la copia

  /** Copia el grupo de atributos de la leccion del documento vivo a la copia de la captura (UI). */
  static void copiarRegistro( AbstractMeta vivo, AbstractMeta copia ) {
    if ( !( vivo instanceof TransMeta ) || copia == null ) {
      return;
    }
    try {
      Map<String, String> g = vivo.getAttributes( LeccionNotas.GRUPO );
      if ( g != null && !g.isEmpty() ) {
        copia.setAttributes( LeccionNotas.GRUPO, new HashMap<String, String>( g ) );
      }
    } catch ( RuntimeException ignorada ) {
      // sin atributos: la guia sale de la leccion en memoria o no sale
    }
  }

  private static String attr( TransMeta tm, String k ) {
    try {
      String v = tm.getAttribute( LeccionNotas.GRUPO, k );
      return v == null || v.trim().isEmpty() ? null : v.trim();
    } catch ( RuntimeException sinGrupo ) {
      return null;
    }
  }

  // ---------------------------------------------------------------- de donde sale

  /** Hay algo de que hacer la guia: una leccion en memoria de este documento o un registro. Barato. */
  static boolean hay( Captura c ) {
    if ( c == null || !( c.copia instanceof TransMeta ) ) {
      return false;
    }
    return enMemoria( c ) != null || LeccionNotas.tieneLeccion( (TransMeta) c.copia );
  }

  /**
   * La leccion en memoria de este documento: la del id del registro, si es del mismo documento (docId) o del mismo
   * archivo; o la ultima de ese documento abierto. null si no hay.
   */
  static Leccion enMemoria( Captura c ) {
    if ( c == null || !( c.copia instanceof TransMeta ) ) {
      return null;
    }
    String id = attr( (TransMeta) c.copia, "id" );
    if ( id == null ) {
      return null;
    }
    Leccion l;
    try {
      l = LeccionMotor.buscar( id, null );
    } catch ( ToolError e ) {
      return null;
    }
    if ( c.docId != null && c.docId.equals( l.docId ) ) {
      return l;
    }
    String a = c.archivo;
    String b = l.tm == null ? null : l.tm.getFilename();
    if ( a != null && b != null && new File( a ).getAbsoluteFile().equals( new File( b ).getAbsoluteFile() ) ) {
      return l;
    }
    return null;
  }

  /**
   * La guia de la captura en el idioma {@code idioma} (glosario y titulos de los errores): de la leccion en memoria
   * si es de este documento; si no, del registro y las notas. null si el documento no tiene leccion.
   */
  static LeccionGuia de( Captura c, String idioma ) {
    if ( c == null || !( c.copia instanceof TransMeta ) ) {
      return null;
    }
    TransMeta copia = (TransMeta) c.copia;
    Leccion l = enMemoria( c );
    LeccionGuia g;
    if ( l != null ) {
      g = desdeLeccion( l, copia );
    } else if ( LeccionNotas.tieneLeccion( copia ) ) {
      g = desdeRegistro( copia );
    } else {
      return null;
    }
    g.contarNotas( copia );
    g.completar( copia, idioma == null ? g.idioma : idioma );
    return g;
  }

  /** Desde la leccion en memoria: todos los pasos del guion, con las filas que hubo en clase. */
  static LeccionGuia desdeLeccion( Leccion l, TransMeta copia ) {
    LeccionGuia g = new LeccionGuia();
    g.origen = MEMORIA;
    synchronized ( l ) {
      g.id = l.id;
      g.titulo = l.titulo;
      g.nivel = l.nivel;
      g.idioma = l.idioma;
      g.intro = l.intro;
      g.outro = l.outro;
      g.estado = l.estado.json;
      g.posicion = l.posicion;
      for ( Leccion.Paso p : l.pasos ) {
        Paso x = new Paso();
        x.n = p.n;
        x.step = p.step;
        x.tipo = p.tipoStep;
        x.tipoId = tipoId( copia, p );
        x.que = p.que;
        x.porque = p.porque;
        x.ojo = p.ojo;
        if ( x.ojo == null && p.ojoAuto && x.tipoId != null ) {
          x.ojo = l.ojoAuto( x.tipoId );
        }
        x.tip = p.tip;
        x.pregunta = p.pregunta;
        x.dado = p.n <= l.posicion;
        ObjectNode v = l.filas.get( p.step );
        x.vista = v == null ? null : v.deepCopy();
        g.pasos.add( x );
      }
    }
    return g;
  }

  private static String tipoId( TransMeta copia, Leccion.Paso p ) {
    StepMeta sm = copia == null ? null : copia.findStep( p.step );
    if ( sm != null && sm.getStepID() != null ) {
      return sm.getStepID();
    }
    if ( p.spec != null && p.spec.type != null ) {
      try {
        return TransOps.id( TransOps.plugin( p.spec.type ) );
      } catch ( Exception e ) {
        return p.spec.type;
      }
    }
    return null;
  }

  /**
   * Desde el registro y las notas (Spoon se reinicio o el documento vino de un archivo): el texto de cada nota sale
   * de src.K (sin cortar) o de la nota del lienzo o de las ocultas (por huella, con las lineas unidas). Sin filas y
   * sin ejercicio.
   */
  static LeccionGuia desdeRegistro( TransMeta tm ) {
    LeccionGuia g = new LeccionGuia();
    g.origen = NOTAS;
    g.id = attr( tm, "id" );
    g.titulo = attr( tm, "title" );
    g.nivel = attr( tm, "level" );
    g.idioma = LeccionNotas.idioma( tm, null );
    Map<String, String> porHuella = new HashMap<String, String>();
    for ( int i = 0; i < tm.nrNotes(); i++ ) {
      String t = tm.getNote( i ).getNote();
      porHuella.put( LeccionNotas.huella( t ), t );
    }
    try {
      for ( NotePadMeta n : LeccionNotas.ocultas( tm ) ) {
        porHuella.put( LeccionNotas.huella( n.getNote() ), n.getNote() );
      }
    } catch ( ToolError e ) {
      // el XML de las ocultas no se puede leer: quedan las del lienzo
    }
    Map<String, Paso> porStep = new LinkedHashMap<String, Paso>();
    List<Entrada> reg = LeccionNotas.registro( tm );
    int orden = 0;
    for ( Entrada e : reg ) {
      String texto = attr( tm, LeccionNotas.P_SRC + e.k );
      if ( texto == null ) {
        texto = porHuella.get( e.huella );
        for ( int i = 0; texto == null && i < e.alternativas.size(); i++ ) {
          texto = porHuella.get( e.alternativas.get( i ) );
        }
      }
      if ( texto == null ) {
        continue;
      }
      if ( e.tipo == Tipo.TITULO ) {
        g.leerTitulo( texto );
        continue;
      }
      String clave = e.step.toLowerCase( Locale.ROOT );
      Paso x = porStep.get( clave );
      if ( e.tipo == Tipo.PASO ) {
        if ( x == null ) {
          x = new Paso();
          x.step = e.step;
          x.n = ++orden;
          porStep.put( clave, x );
        }
        leerPaso( x, texto, g.idioma );
        continue;
      }
      if ( x == null ) {
        continue; // una nota suelta de un step sin nota de paso: no es parte del recorrido
      }
      String cuerpo = cuerpoSuelta( e.tipo, texto, g.idioma );
      if ( e.tipo == Tipo.OJO ) {
        x.ojo = cuerpo;
      } else if ( e.tipo == Tipo.TIP ) {
        x.tip = cuerpo;
      } else if ( e.tipo == Tipo.PREGUNTA ) {
        x.pregunta = cuerpo;
      }
    }
    for ( Paso x : porStep.values() ) {
      StepMeta sm = tm.findStep( x.step );
      if ( sm != null ) {
        x.tipoId = sm.getStepID();
      }
      g.pasos.add( x );
    }
    Collections.sort( g.pasos, ( a, b ) -> Integer.compare( a.n, b.n ) );
    return g;
  }

  /** «Lección · titulo» / «principiante · 5 pasos» / intro. */
  private void leerTitulo( String texto ) {
    String[] l = texto.split( "\\r?\\n" );
    if ( titulo == null && l.length > 0 ) {
      int i = l[ 0 ].indexOf( " · " );
      titulo = i >= 0 ? l[ 0 ].substring( i + 3 ).trim() : l[ 0 ].trim();
    }
    StringBuilder sb = new StringBuilder();
    for ( int i = 2; i < l.length; i++ ) {
      sb.append( sb.length() == 0 ? "" : "\n" ).append( l[ i ].trim() );
    }
    if ( sb.length() > 0 ) {
      intro = sb.toString();
    }
  }

  /** «N · Nombre (Tipo)» / «Qué: …» / «Por qué: …»; las lineas cortadas se unen con un espacio. */
  static void leerPaso( Paso x, String texto, String idioma ) {
    String[] l = texto.split( "\\r?\\n" );
    if ( l.length == 0 ) {
      return;
    }
    Matcher m = CABECERA.matcher( l[ 0 ] );
    int desde = 1;
    if ( m.matches() ) {
      try {
        x.n = Integer.parseInt( m.group( 1 ) );
      } catch ( NumberFormatException e ) {
        // queda el orden del registro
      }
      if ( m.group( 3 ) != null && !m.group( 3 ).trim().isEmpty() ) {
        x.tipo = m.group( 3 ).trim();
      }
    } else {
      desde = 0;
    }
    String que = LeccionNotas.etiqueta( "what", idioma ) + ":";
    String porque = LeccionNotas.etiqueta( "why", idioma ) + ":";
    StringBuilder actual = null;
    StringBuilder q = new StringBuilder();
    StringBuilder p = new StringBuilder();
    for ( int i = desde; i < l.length; i++ ) {
      String s = l[ i ].trim();
      if ( s.startsWith( que ) ) {
        actual = q;
        s = s.substring( que.length() ).trim();
      } else if ( s.startsWith( porque ) ) {
        actual = p;
        s = s.substring( porque.length() ).trim();
      }
      if ( actual != null && !s.isEmpty() ) {
        actual.append( actual.length() == 0 ? "" : " " ).append( s );
      }
    }
    x.que = q.length() == 0 ? null : q.toString();
    x.porque = p.length() == 0 ? null : p.toString();
  }

  /** El cuerpo de «¡Ojo! (paso 3) …», «Tip (paso 3): …» o «¿Pregunta? (paso 3) …», con las lineas unidas. */
  static String cuerpoSuelta( Tipo t, String texto, String idioma ) {
    String clave = t == Tipo.TIP ? "tip" : t == Tipo.PREGUNTA ? "question" : "watch_out";
    String s = texto.replaceAll( "\\s*\\r?\\n\\s*", " " ).trim();
    String et = LeccionNotas.etiqueta( clave, idioma );
    if ( s.startsWith( et ) ) {
      s = s.substring( et.length() ).trim();
      if ( s.startsWith( "(" ) && s.indexOf( ')' ) > 0 ) {
        s = s.substring( s.indexOf( ')' ) + 1 ).trim();
      }
      if ( s.startsWith( ":" ) ) {
        s = s.substring( 1 ).trim();
      }
    }
    return s.isEmpty() ? null : s;
  }

  // ---------------------------------------------------------------- completar

  private void contarNotas( TransMeta copia ) {
    notasEnLienzo = LeccionNotas.reconocer( copia ).size();
    try {
      notasOcultas = LeccionNotas.ocultas( copia ).size();
    } catch ( ToolError e ) {
      notasOcultas = 0;
    }
  }

  /** Codigos del ¡Ojo!, glosario de cada tipo y la tabla de errores comunes de los tipos usados. */
  private void completar( TransMeta copia, String lang ) {
    McpResources res = new McpResources();
    Map<String, ErrorComun> porCodigo = new LinkedHashMap<String, ErrorComun>();
    for ( Paso x : pasos ) {
      if ( x.ojo != null ) {
        Matcher m = CODIGO.matcher( x.ojo );
        while ( m.find() ) {
          if ( !x.codigos.contains( m.group( 1 ) ) ) {
            x.codigos.add( m.group( 1 ) );
          }
        }
      }
      if ( x.tipoId == null ) {
        continue;
      }
      ObjectNode e = res.glosario( lang, x.tipoId );
      if ( e == null && lang != null && !lang.equals( idioma ) ) {
        e = res.glosario( idioma, x.tipoId );
      }
      if ( e == null ) {
        continue;
      }
      x.glosarioQue = vacio( e.path( "what" ).asText( null ) );
      x.analogia = vacio( e.path( "analogy" ).asText( null ) );
      for ( JsonNode w : e.path( "watch_out" ) ) {
        String code = w.path( "code" ).asText( "" );
        if ( code.isEmpty() ) {
          continue;
        }
        ErrorComun ec = porCodigo.get( code );
        if ( ec == null ) {
          ec = new ErrorComun();
          ec.codigo = code;
          ec.titulo = vacio( w.path( "title" ).asText( null ) );
          porCodigo.put( code, ec );
        }
        if ( !ec.steps.contains( x.step ) ) {
          ec.steps.add( x.step );
        }
      }
    }
    errores.addAll( porCodigo.values() );
    // C antes que D, por numero (como el manual)
    Collections.sort( errores, ( a, b ) -> {
      int c = a.codigo.substring( 0, 1 ).compareTo( b.codigo.substring( 0, 1 ) );
      return c != 0 ? c : Integer.compare( numero( a.codigo ), numero( b.codigo ) );
    } );
  }

  private static int numero( String codigo ) {
    try {
      return Integer.parseInt( codigo.substring( 1 ) );
    } catch ( RuntimeException e ) {
      return 0;
    }
  }

  private static String vacio( String s ) {
    return s == null || s.trim().isEmpty() ? null : s.trim();
  }

  /** Pasos con filas de la vista previa. */
  int conFilas() {
    int n = 0;
    for ( Paso x : pasos ) {
      if ( x.vista != null && x.vista.path( "rows" ).isArray() ) {
        n++;
      }
    }
    return n;
  }

  /** Ya se dieron todos los pasos (o la guia viene de las notas, que son lo que se dio). */
  boolean completa() {
    return !MEMORIA.equals( origen ) || "done".equals( estado ) || posicion >= pasos.size();
  }

  // ---------------------------------------------------------------- contrato LessonHandout (JSON)

  /** {source, lesson: {...}, notes, steps: [...], common_errors: [...]}. */
  ObjectNode json() {
    ObjectNode o = Tools.obj();
    o.put( "source", origen );
    ObjectNode l = o.putObject( "lesson" );
    put( l, "id", id );
    put( l, "title", titulo );
    put( l, "level", nivel );
    put( l, "language", idioma );
    put( l, "intro", intro );
    put( l, "outro", outro );
    put( l, "state", estado );
    if ( MEMORIA.equals( origen ) ) {
      l.put( "position", posicion );
    }
    l.put( "beats", pasos.size() );
    o.putObject( "notes" ).put( "on_canvas", notasEnLienzo ).put( "hidden", notasOcultas );
    ArrayNode st = o.putArray( "steps" );
    for ( Paso x : pasos ) {
      ObjectNode s = st.addObject().put( "n", x.n ).put( "step", x.step );
      put( s, "type", x.tipo );
      put( s, "type_id", x.tipoId );
      put( s, "what", x.que );
      put( s, "why", x.porque );
      ArrayNode w = s.putArray( "watch_out" );
      if ( x.ojo != null ) {
        w.add( x.ojo );
      }
      ArrayNode c = s.putArray( "watch_out_codes" );
      for ( String k : x.codigos ) {
        c.add( k );
      }
      put( s, "tip", x.tip );
      put( s, "question", x.pregunta );
      if ( x.glosarioQue != null || x.analogia != null ) {
        ObjectNode gl = s.putObject( "glossary" );
        put( gl, "what", x.glosarioQue );
        put( gl, "analogy", x.analogia );
      }
      if ( x.vista != null ) {
        ObjectNode v = s.putObject( "preview" );
        v.set( "fields", x.vista.path( "fields" ).deepCopy() );
        v.set( "rows", x.vista.path( "rows" ).deepCopy() );
      }
      s.put( "played", x.dado );
    }
    ArrayNode ec = o.putArray( "common_errors" );
    for ( ErrorComun e : errores ) {
      ObjectNode x = ec.addObject().put( "code", e.codigo );
      put( x, "title", e.titulo );
      ArrayNode s = x.putArray( "steps" );
      for ( String n : e.steps ) {
        s.add( n );
      }
    }
    return o;
  }

  private static void put( ObjectNode o, String k, String v ) {
    if ( v != null ) {
      o.put( k, v );
    }
  }
}
