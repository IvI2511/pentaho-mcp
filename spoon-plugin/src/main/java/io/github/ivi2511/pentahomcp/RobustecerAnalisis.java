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
  static final Set<String> QUITAN_REPETIDOS = new HashSet<String>( Arrays.asList( "UniqueRows", "UniqueRowsByHashSet",
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
    String lang = "en";

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

  private RobustecerAnalisis( TransMeta tm, Pedido p ) {
    this.tm = tm;
    this.p = p;
    for ( StepMeta s : tm.getSteps() ) {
      tomados.add( s.getName().toLowerCase( Locale.ROOT ) );
      ocupados.add( new int[] { s.getLocation().x, s.getLocation().y } );
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
    return "es".equals( p.lang ) ? es : "pt".equals( p.lang ) ? pt : en;
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
    return false;
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
    int[] pl = libre( ancla.getLocation().x, ancla.getLocation().y + 120 );
    int[] pe = libre( pl[ 0 ] + 160, pl[ 1 ] );

    StringBuilder r = new StringBuilder( "<fields>" );
    r.append( regla( MOTIVO, "^\\s+|\\s+$", "" ) ).append( regla( MOTIVO, "\\s*[\\r\\n]+\\s*", " | " ) )
      .append( regla( MOTIVO, "^([A-Za-z_$][\\w$]*\\.)+[A-Za-z_$][\\w$]*Exception:\\s*(\\|\\s*)?", "" ) )
      .append( regla( MOTIVO, ",\\s*$", "" ) ).append( regla( CAMPOS_ERROR, ",\\s*$", "" ) ).append( "</fields>" );
    Cambio limpiar = nuevo( c.limpiar, "ReplaceString", r.toString(), pl, "rejects tail" );
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
    l.add( hop( s.getName(), c.limpiar, true ) );
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
      x.cambios.add( hop( motivo, c.limpiar, false ) );
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
      for ( StepMeta w : faltan ) {
        Set<StepMeta> compartidas = new HashSet<StepMeta>( arriba( w ) );
        compartidas.retainAll( arr );
        if ( !compartidas.isEmpty() ) {
          x.avisos.add( "'" + w.getName() + "' and '" + l.getName() + "' get rows from the same source: with more "
            + "rows than the row set size (10000 by default) the wait can stall the source. Split the source or "
            + "raise the row set size if it happens." );
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
      Cambio b = nuevo( nombre, "BlockUntilStepsFinish", pasos.toString(), pos, Receta.WAIT_FOR_LOADS );
      b.diff = "+ " + nombre + " (Block until steps finish: " + String.join( ", ", nombres ) + ")";
      x.cambios.add( b );
      redirigir( f, l, nombre, x );
      x.cambios.add( quitarHop( f.getName(), l.getName() ) );
      x.cambios.add( hop( f.getName(), nombre, false ) );
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
    pr.cambios.add( hop( motivo, c.limpiar, false ) );
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
      if ( "FieldSplitter".equals( id ) && s.getStepErrorMeta() == null ) {
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
