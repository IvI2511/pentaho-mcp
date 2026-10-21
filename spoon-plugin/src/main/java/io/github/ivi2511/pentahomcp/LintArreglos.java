package io.github.ivi2511.pentahomcp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.pentaho.di.base.AbstractMeta;
import org.pentaho.di.cluster.SlaveServer;
import org.pentaho.di.core.AttributesInterface;
import org.pentaho.di.core.database.DatabaseMeta;
import org.pentaho.di.core.gui.Point;
import org.pentaho.di.core.plugins.PluginInterface;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.xml.XMLHandler;
import org.pentaho.di.job.JobHopMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entry.JobEntryCopy;
import org.pentaho.di.job.entry.JobEntryInterface;
import org.pentaho.di.repository.Repository;
import org.pentaho.di.trans.TransHopMeta;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.errorhandling.StreamInterface;
import org.pentaho.metastore.api.IMetaStore;
import org.w3c.dom.Element;

import io.github.ivi2511.pentahomcp.McpHttpServer.ToolError;
import io.github.ivi2511.pentahomcp.lint.Arreglo;
import io.github.ivi2511.pentahomcp.lint.Catalogo;
import io.github.ivi2511.pentahomcp.lint.Hallazgo;
import io.github.ivi2511.pentahomcp.lint.LintMotor;
import io.github.ivi2511.pentahomcp.lint.Nodos;
import io.github.ivi2511.pentahomcp.lint.NombresPorDefecto;
import io.github.ivi2511.pentahomcp.lint.Opciones;
import io.github.ivi2511.pentahomcp.lint.Regla;
import io.github.ivi2511.pentahomcp.lint.Resultado;
import io.github.ivi2511.pentahomcp.lint.Rutas;
import io.github.ivi2511.pentahomcp.lint.Supresiones;
import io.github.ivi2511.pentahomcp.lint.Textos;
import io.github.ivi2511.pentahomcp.lint.reglas.ReglasSeguridad;

/**
 * apply_lint_fixes y suppress_lint (docs/linter-diseno.md §6-§7, WP5), sin Spoon: LintTools pone el hilo de UI, el
 * grupo de undo de Spoon y el refresco; las pruebas llaman directo con UndoGrupo.sinSpoon.
 *
 * apply_lint_fixes en tres pasos:
 * <ol>
 * <li>{@link #analizar} (hilo de trabajo, sobre una copia): vuelve a correr las reglas, propone nombres
 * (LintPropuestas) y elige los arreglos pedidos. Sin estado entre llamadas: una key que ya no sale va a skipped. Los
 * campos que hay que declarar (getPrevStepFields, que puede ir a la BD) se calculan acá y no en la UI.</li>
 * <li>{@link #aplicar} (hilo de UI, sobre el documento vivo): resuelve TODAS las keys a objetos (steps, entradas,
 * hops, conexiones) antes de tocar nada; después aplica, con los renombres al final (cambian las keys de esos steps).
 * Cada cambio va al mismo UndoGrupo: un Ctrl+Z en transformaciones; en jobs, uno por acción (Spoon no encadena).</li>
 * <li>LintTools vuelve a correr las reglas sobre el resultado para score_after.</li>
 * </ol>
 * auto = entra en all_auto (no cambia filas, formatos, rutas ni steps); confirm = el cliente lo confirma con la
 * persona antes de pasar la key (lo dice la descripción de la herramienta); needs_value = falta values[key].
 */
final class LintArreglos {

  static final String TODOS_AUTO = "all_auto";
  static final String YA_NO_ESTA = "no longer found: the rules no longer report it (already fixed, or the document "
    + "changed). Run lint_etl again for the current keys";
  static final Pattern IDENTIFICADOR = Pattern.compile( "[A-Za-z_][A-Za-z0-9_]*" );
  /** Consultas a las que no se les puede agregar un ORDER BY al final sin cambiarles el significado. */
  static final Pattern SQL_NO_ORDENABLE = Pattern.compile( "(?is).*\\b(order\\s+by|limit|offset|fetch|union|intersect"
    + "|except|minus|for\\s+update|into|top)\\b.*" );
  static final Pattern SQL_SELECT = Pattern.compile( "(?is)^\\s*select\\b.*" );
  /** Tags del consumidor que nombran el step del que lee (Merge join, Merge rows): se repuntan al Sort rows nuevo. */
  static final Set<String> TAGS_INFO = new HashSet<String>( Arrays.asList( "step1", "step2", "reference", "compare" ) );
  /** Comparan con RowMeta.compare: el orden de la BD (collation) puede no coincidir, así que siempre Sort rows. */
  static final Set<String> COMPARAN = new HashSet<String>( Arrays.asList( "MergeJoin", "MergeRows", "SortedMerge" ) );
  static final Set<String> SALIDAS_TEXTO = new HashSet<String>( Arrays.asList( "TextFileOutput", "TextFileOutputLegacy" ) );

  private LintArreglos() {
  }

  // ---------------------------------------------------------------- el pedido

  /** Los argumentos de apply_lint_fixes, validados antes de tocar Spoon. */
  static final class Pedido {
    final Set<String> keys = new LinkedHashSet<String>();
    boolean todosAuto;
    final Map<String, String> values = new LinkedHashMap<String, String>();
    boolean dryRun;
    String lang = "en";
  }

  static Pedido pedido( JsonNode a ) throws ToolError {
    Pedido p = new Pedido();
    JsonNode k = comoJson( a.get( "keys" ), "[" );
    if ( k != null && !k.isNull() ) {
      if ( k.isArray() ) {
        for ( JsonNode x : k ) {
          clave( p, x.asText() );
        }
      } else if ( k.isTextual() ) {
        clave( p, k.asText() ); // una sola key (las keys pueden tener comas: no se parte el texto)
      } else {
        throw new ToolError( "'keys' must be a list of strings: the 'key' of each finding of lint_etl, or \"all_auto\"" );
      }
    }
    JsonNode v = comoJson( a.get( "values" ), "{" );
    if ( v != null && !v.isNull() && !( v.isTextual() && v.asText().trim().isEmpty() ) ) {
      if ( !v.isObject() ) {
        throw new ToolError( "'values' must be an object {\"<key>\": \"<value>\"}" );
      }
      for ( java.util.Iterator<Map.Entry<String, JsonNode>> it = v.fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        String key = e.getKey().trim();
        JsonNode x = e.getValue();
        if ( key.isEmpty() || x == null || x.isNull() ) {
          continue;
        }
        p.values.put( key, x.isTextual() ? x.asText() : x.toString() );
        p.keys.add( key ); // un valor elegido es un pedido explícito de esa key
      }
    }
    if ( p.keys.isEmpty() && !p.todosAuto ) {
      throw new ToolError( "Pass 'keys': the keys of the findings to fix (from lint_etl), or [\"all_auto\"] for every "
        + "fix with auto=true and confirm=false. Nothing is ever fixed without keys." );
    }
    p.dryRun = Tools.optBool( a, "dry_run", false );
    String lang = Tools.optStr( a, "language", "en" );
    if ( !Textos.IDIOMAS.contains( lang.trim().toLowerCase( Locale.ROOT ) ) ) {
      throw new ToolError( "'language' must be en, es or pt, got: " + lang );
    }
    p.lang = lang.trim().toLowerCase( Locale.ROOT );
    return p;
  }

  /** Algunos clientes mandan el array o el objeto como texto JSON. */
  private static JsonNode comoJson( JsonNode n, String abre ) throws ToolError {
    if ( n != null && n.isTextual() && n.asText().trim().startsWith( abre ) ) {
      try {
        return McpHttpServer.JSON.readTree( n.asText().trim() );
      } catch ( Exception e ) {
        throw new ToolError( "Invalid JSON in an argument: " + RunMotor.primeraLinea( e ) );
      }
    }
    return n;
  }

  private static void clave( Pedido p, String k ) {
    String x = k == null ? "" : k.trim();
    if ( x.equalsIgnoreCase( TODOS_AUTO ) ) {
      p.todosAuto = true;
    } else if ( !x.isEmpty() ) {
      p.keys.add( x );
    }
  }

  // ---------------------------------------------------------------- 1) hilo de trabajo: qué se arregla

  /** Un arreglo elegido: el hallazgo y el valor con que se aplica (values[key] o la propuesta). */
  static final class Elegido {
    final Hallazgo h;
    final String valor;
    /** declare_output_fields: el <fields> con los campos que llegan (calculado en el hilo de trabajo). */
    String campos;
    String sinCampos;

    Elegido( Hallazgo h, String valor ) {
      this.h = h;
      this.valor = valor;
    }
  }

  /** Lo que sale del hilo de trabajo. */
  static final class Analisis {
    Resultado antes;
    final List<Elegido> elegidos = new ArrayList<Elegido>();
    /** {key, motivo}. */
    final List<String[]> saltados = new ArrayList<String[]>();
  }

  /**
   * Sobre la copia, en un hilo de trabajo: corre las reglas (op.entorno obligatorio), propone nombres y elige los
   * arreglos. all_auto = los auto sin confirm ni needs_value de los hallazgos visibles (nunca los silenciados); una key
   * explícita puede ser también la de un hallazgo silenciado.
   */
  static Analisis analizar( AbstractMeta copia, Opciones op, Pedido p ) {
    Analisis an = new Analisis();
    an.antes = LintMotor.analizar( copia, op );
    LintPropuestas.completar( an.antes, copia, op.lang );
    Map<String, Hallazgo> porClave = new LinkedHashMap<String, Hallazgo>();
    for ( Hallazgo h : an.antes.suppressed ) {
      porClave.put( h.key, h );
    }
    for ( Hallazgo h : an.antes.findings ) {
      porClave.put( h.key, h );
    }
    Set<String> vistas = new HashSet<String>();
    for ( String k : p.keys ) {
      Hallazgo h = porClave.get( k );
      if ( h == null ) {
        an.saltados.add( new String[] { k, YA_NO_ESTA } );
        continue;
      }
      vistas.add( k );
      elegir( an, h, p );
    }
    if ( p.todosAuto ) {
      for ( Hallazgo h : an.antes.findings ) {
        Arreglo a = h.fix;
        if ( a != null && a.auto && !a.confirm && !a.needsValue && vistas.add( h.key ) ) {
          elegir( an, h, p );
        }
      }
    }
    // los campos a declarar: getPrevStepFields puede conectarse a la BD, nunca en el hilo de UI
    for ( Elegido e : an.elegidos ) {
      if ( Arreglo.DECLARE_OUTPUT_FIELDS.equals( e.h.fix.kind ) && copia instanceof TransMeta ) {
        StepMeta s = ( (TransMeta) copia ).findStep( e.h.step );
        try {
          RowMetaInterface rm = s == null ? null : op.entorno.entrada( s );
          if ( rm == null || rm.size() == 0 ) {
            e.sinCampos = "Kettle computes no fields reaching it (they appear only when it runs)";
          } else {
            e.campos = camposDeSalida( rm );
          }
        } catch ( Throwable x ) {
          e.sinCampos = RunMotor.primeraLinea( x );
        }
      }
    }
    return an;
  }

  private static void elegir( Analisis an, Hallazgo h, Pedido p ) {
    Arreglo a = h.fix;
    if ( a == null ) {
      an.saltados.add( new String[] { h.key, "no automatic fix: " + h.howToFix } );
      return;
    }
    String v = p.values.get( h.key );
    if ( v == null && a.needsValue ) {
      an.saltados.add( new String[] { h.key, "needs a value: pass it in values[\"" + h.key + "\"]"
        + ( a.preview != null ? " (proposed: " + a.preview + ")" : "" ) } );
      return;
    }
    an.elegidos.add( new Elegido( h, v != null ? v : a.preview ) );
  }

  /** El <fields> de un Text file output con lo que llega: Strings con largo -1 (sin relleno), el resto como viene. */
  static String camposDeSalida( RowMetaInterface rm ) {
    StringBuilder b = new StringBuilder( "<fields>" );
    for ( ValueMetaInterface v : rm.getValueMetaList() ) {
      boolean texto = v.getType() == ValueMetaInterface.TYPE_STRING;
      b.append( "<field>" ).append( XMLHandler.addTagValue( "name", v.getName(), false ) )
        .append( XMLHandler.addTagValue( "type", v.getTypeDesc(), false ) )
        .append( XMLHandler.addTagValue( "format", texto ? "" : v.getConversionMask(), false ) )
        .append( XMLHandler.addTagValue( "currency", texto ? "" : v.getCurrencySymbol(), false ) )
        .append( XMLHandler.addTagValue( "decimal", texto ? "" : v.getDecimalSymbol(), false ) )
        .append( XMLHandler.addTagValue( "group", texto ? "" : v.getGroupingSymbol(), false ) )
        .append( XMLHandler.addTagValue( "nullif", "", false ) )
        .append( XMLHandler.addTagValue( "trim_type", "none", false ) )
        .append( XMLHandler.addTagValue( "length", texto ? -1 : v.getLength(), false ) )
        .append( XMLHandler.addTagValue( "precision", texto ? -1 : v.getPrecision(), false ) ).append( "</field>" );
    }
    return b.append( "</fields>" ).toString();
  }

  // ---------------------------------------------------------------- 2) hilo de UI: resolver y aplicar

  /** Lo que hizo aplicar(): para la respuesta de apply_lint_fixes. */
  static final class Hecho {
    final ArrayNode aplicados = Tools.arr();
    final List<String[]> saltados = new ArrayList<String[]>();
    final Set<String> notas = new LinkedHashSet<String>();
    int cambios;
    /** Cuántos Ctrl+Z deshacen todo: 1 en transformaciones; en jobs, uno por acción registrada. */
    int pasosDeshacer;
  }

  /** El documento sobre el que se aplica y dónde se registra cada cambio. */
  static final class Aplicacion {
    final AbstractMeta meta;
    final TransMeta tm;
    final JobMeta jm;
    final String lang;
    final UndoGrupo undo;
    final Repository rep;
    final IMetaStore ms;
    final Set<String> notas = new LinkedHashSet<String>();
    /** Nombres (en minúsculas) que van a tomar los renombres de esta llamada: los steps nuevos no los usan. */
    final Set<String> reservados = new HashSet<String>();
    /** Parámetros que declaran los arreglos de rutas de esta llamada, con su default (DIR_DATOS → carpeta). */
    final Map<String, String> parametros = new LinkedHashMap<String, String>();
    /**
     * Table inputs (en minúsculas) que ya reciben un ORDER BY en esta llamada: una consulta tiene un solo ORDER BY,
     * así que el segundo consumidor del mismo Table input lleva un Sort rows.
     */
    final Set<String> consultasOrdenadas = new HashSet<String>();

    Aplicacion( AbstractMeta meta, String lang, UndoGrupo undo, Repository rep, IMetaStore ms ) {
      this.meta = meta;
      this.tm = meta instanceof TransMeta ? (TransMeta) meta : null;
      this.jm = meta instanceof JobMeta ? (JobMeta) meta : null;
      this.lang = lang;
      this.undo = undo;
      this.rep = rep;
      this.ms = ms;
    }
  }

  /** Una key que no se puede aplicar (con el motivo, para skipped). */
  static final class Saltar extends Exception {
    private static final long serialVersionUID = 1L;

    Saltar( String motivo ) {
      super( motivo );
    }
  }

  /**
   * Aplica los elegidos sobre m. Primero resuelve TODAS las keys contra el documento (sin tocar nada); después
   * aplica, con los renombres al final. (UI, o un hilo de trabajo si m es una copia: dry_run y pruebas)
   */
  static Hecho aplicar( AbstractMeta m, Analisis an, String lang, UndoGrupo undo, Repository rep, IMetaStore ms ) {
    Aplicacion ap = new Aplicacion( m, lang, undo, rep, ms );
    Hecho hecho = new Hecho();
    hecho.saltados.addAll( an.saltados );
    List<Accion> acciones = new ArrayList<Accion>();
    for ( Elegido e : an.elegidos ) {
      try {
        acciones.add( resolver( ap, e, ap.reservados ) );
      } catch ( Saltar s ) {
        hecho.saltados.add( new String[] { e.h.key, s.getMessage() } );
      }
    }
    List<Accion> orden = new ArrayList<Accion>();
    for ( Accion a : acciones ) {
      if ( !a.renombre() ) {
        orden.add( a );
      }
    }
    for ( Accion a : acciones ) {
      if ( a.renombre() ) {
        orden.add( a ); // al final: los demás ya tienen su objeto resuelto, el nombre ya no importa
      }
    }
    for ( Accion a : orden ) {
      try {
        String cambio = a.hacer( ap );
        ObjectNode o = hecho.aplicados.addObject();
        o.put( "key", a.e.h.key ).put( "rule", a.e.h.rule ).put( "change", cambio ).put( "undoable", a.deshacible( ap ) );
        hecho.cambios++;
      } catch ( Exception x ) {
        hecho.saltados.add( new String[] { a.e.h.key, "failed: " + RunMotor.primeraLinea( x ) } );
      }
    }
    hecho.notas.addAll( ap.notas );
    hecho.pasosDeshacer = m instanceof TransMeta ? Math.min( 1, undo.acciones() ) : undo.acciones();
    return hecho;
  }

  static Accion resolver( Aplicacion ap, Elegido e, Set<String> nombresNuevos ) throws Saltar {
    String k = e.h.fix.kind;
    if ( Arreglo.SWAP_OPERANDS.equals( k ) ) {
      return new Intercambiar( ap, e );
    }
    if ( Arreglo.SET_OUTPUT_MODE.equals( k ) ) {
      return new ModoCopiar( ap, e );
    }
    if ( Arreglo.RENAME.equals( k ) ) {
      return new RenombrarStep( ap, e, nombresNuevos );
    }
    if ( Arreglo.RENAME_ENTRY.equals( k ) ) {
      return new RenombrarEntrada( ap, e, nombresNuevos );
    }
    if ( Arreglo.REPLACE_VALUE.equals( k ) ) {
      return "password_in_file".equals( e.h.rule ) ? new Clave( ap, e ) : new Ruta( ap, e );
    }
    if ( Arreglo.SET_DECIMAL.equals( k ) ) {
      return new Decimal( ap, e );
    }
    if ( Arreglo.DECLARE_OUTPUT_FIELDS.equals( k ) ) {
      return new DeclararCampos( ap, e );
    }
    if ( Arreglo.INSERT_SORT.equals( k ) ) {
      return Ordenar.para( ap, e );
    }
    if ( Arreglo.RETARGET_HOP.equals( k ) ) {
      return new HopAAbort( ap, e );
    }
    if ( Arreglo.SET_DESCRIPTION.equals( k ) ) {
      return new Descripcion( ap, e );
    }
    if ( Arreglo.ADD_PARAMETER.equals( k ) ) {
      return new Parametro( ap, e );
    }
    throw new Saltar( "the fix '" + k + "' cannot be applied automatically" );
  }

  /** Un arreglo ya resuelto contra el documento: los objetos están, todavía no se tocó nada. */
  abstract static class Accion {
    final Elegido e;

    Accion( Elegido e ) {
      this.e = e;
    }

    boolean renombre() {
      return false;
    }

    boolean deshacible( Aplicacion ap ) {
      return true;
    }

    /** Hace el cambio, lo registra en ap.undo y devuelve qué cambió (para la IA, en inglés). */
    abstract String hacer( Aplicacion ap ) throws Exception;
  }

  // ---------------------------------------------------------------- ayudas

  static StepMeta step( Aplicacion ap, Hallazgo h ) throws Saltar {
    if ( ap.tm == null ) {
      throw new Saltar( "the document is not a transformation" );
    }
    StepMeta s = h.step == null ? null : ap.tm.findStep( h.step );
    if ( s == null ) {
      throw new Saltar( "there is no step '" + h.step + "' in the document" );
    }
    return s;
  }

  static JobEntryCopy entrada( Aplicacion ap, Hallazgo h ) throws Saltar {
    if ( ap.jm == null ) {
      throw new Saltar( "the document is not a job" );
    }
    JobEntryCopy c = h.entry == null ? null : ap.jm.findJobEntry( h.entry, h.entryNr, true );
    if ( c == null ) {
      throw new Saltar( "there is no entry '" + h.entry + "' in the job" );
    }
    return c;
  }

  static Element xml( StepMeta s ) throws Saltar {
    try {
      return Nodos.parse( s.getXML() );
    } catch ( Exception x ) {
      throw new Saltar( "could not read step '" + s.getName() + "': " + RunMotor.primeraLinea( x ) );
    }
  }

  static Element xml( JobEntryCopy c ) throws Saltar {
    try {
      return Nodos.parse( c.getXML() );
    } catch ( Exception x ) {
      throw new Saltar( "could not read entry '" + c.getName() + "': " + RunMotor.primeraLinea( x ) );
    }
  }

  /** Pone el texto de un hijo (lo crea si no está). */
  static void texto( Element padre, String tag, String valor ) {
    Element k = Nodos.hijo( padre, tag );
    if ( k == null ) {
      k = padre.getOwnerDocument().createElement( tag );
      padre.appendChild( k );
    }
    k.setTextContent( valor == null ? "" : valor );
  }

  /**
   * Relee la configuración del step desde su XML cambiado (como TransOps.renombrar: lo que no está en el XML, como el
   * manejo de errores o la posición, queda igual) y registra el cambio en el grupo de undo.
   */
  static void releer( Aplicacion ap, StepMeta s, Element raiz ) throws Exception {
    StepMeta antes = (StepMeta) s.clone();
    StepMeta nuevo = new StepMeta( XMLHandler.getSubNode( XMLHandler.loadXMLString( Xml.texto( raiz ) ), "step" ),
      ap.tm.getDatabases(), ap.ms );
    if ( nuevo.isMissing() || nuevo.getStepMetaInterface() == null ) {
      throw new ToolError( "Kettle could not read the new configuration of '" + s.getName() + "'" );
    }
    s.setStepMetaInterface( nuevo.getStepMetaInterface() );
    nuevo.getStepMetaInterface().setParentStepMeta( s );
    s.setChanged();
    TransOps.resolverReferencias( ap.tm );
    ap.undo.cambio( new Object[] { antes }, new Object[] { s.clone() }, new int[] { ap.tm.indexOfStep( s ) } );
  }

  /** Lo mismo para una entrada de job (todas las copias comparten la entrada). */
  static void releer( Aplicacion ap, JobEntryCopy c, Element raiz ) throws Exception {
    JobEntryCopy antes = (JobEntryCopy) c.clone();
    JobEntryCopy nueva = new JobEntryCopy( XMLHandler.getSubNode( XMLHandler.loadXMLString( Xml.texto( raiz ) ),
      "entry" ), ap.jm.getDatabases(), ap.jm.getSlaveServers(), ap.rep, ap.ms );
    if ( nueva.isMissing() || nueva.getEntry() == null ) {
      throw new ToolError( "Kettle could not read the new configuration of '" + c.getName() + "'" );
    }
    JobEntryInterface vieja = c.getEntry();
    for ( JobEntryCopy x : ap.jm.getJobCopies() ) {
      if ( x.getEntry() == vieja ) {
        x.setEntry( nueva.getEntry() );
      }
    }
    nueva.getEntry().setParentJobMeta( ap.jm );
    c.setParentJobMeta( ap.jm );
    c.setChanged();
    ap.undo.cambio( new Object[] { antes }, new Object[] { c.clone() }, new int[] { ap.jm.indexOfJobEntry( c ) } );
  }

  /** Los nombres del documento y los que van a tomar los renombres de esta llamada. */
  static Set<String> tomados( Aplicacion ap ) {
    Set<String> t = LintPropuestas.nombresTomados( ap.meta );
    t.addAll( ap.reservados );
    return t;
  }

  static boolean tieneParametro( AbstractMeta m, String p ) {
    return p != null && Arrays.asList( m.listParameters() ).contains( p );
  }

  private static String nombreValido( String v ) throws Saltar {
    String n = v == null ? "" : v.trim();
    if ( n.isEmpty() ) {
      throw new Saltar( "no name to use: pass one in values[key]" );
    }
    if ( n.contains( "\n" ) || n.contains( "\r" ) ) {
      throw new Saltar( "a name cannot have line breaks" );
    }
    return n;
  }

  private static int entero( String s ) {
    try {
      return Integer.parseInt( s.trim() );
    } catch ( RuntimeException e ) {
      return -1;
    }
  }

  // ---------------------------------------------------------------- los arreglos

  /** calculator_integer_first (ADD/MULTIPLY): A y B se intercambian; el resultado es el mismo, con decimales. */
  static final class Intercambiar extends Accion {
    final StepMeta s;
    final int i;
    final String a;
    final String b;

    Intercambiar( Aplicacion ap, Elegido e ) throws Saltar {
      super( e );
      s = step( ap, e.h );
      i = entero( e.h.fix.params.get( "calculation" ) ) - 1;
      a = e.h.fix.params.get( "field_a" );
      b = e.h.fix.params.get( "field_b" );
      Element c = calculo( xml( s ), i );
      if ( c == null || a == null || b == null || !Nodos.txt( c, "field_a" ).equals( b ) || !Nodos.txt( c, "field_b" ).equals( a ) ) {
        throw new Saltar( "calculation " + ( i + 1 ) + " of '" + s.getName() + "' changed since the analysis" );
      }
    }

    private static Element calculo( Element raiz, int i ) {
      List<Element> cs = Nodos.hijos( raiz, "calculation" );
      return i >= 0 && i < cs.size() ? cs.get( i ) : null;
    }

    @Override
    String hacer( Aplicacion ap ) throws Exception {
      Element raiz = xml( s );
      Element c = calculo( raiz, i );
      texto( c, "field_a", a );
      texto( c, "field_b", b );
      releer( ap, s, raiz );
      return "In '" + s.getName() + "', calculation " + ( i + 1 ) + " (" + Nodos.txt( c, "field_name" ) + ") now takes "
        + a + " as A and " + b + " as B: same result, computed with decimals";
    }
  }

  /** distribute_multiple_outputs: el step pasa a copiar (cada salida recibe todas las filas). */
  static final class ModoCopiar extends Accion {
    final StepMeta s;

    ModoCopiar( Aplicacion ap, Elegido e ) throws Saltar {
      super( e );
      s = step( ap, e.h );
      if ( !s.isDistributes() ) {
        throw new Saltar( "'" + s.getName() + "' already copies its rows" );
      }
    }

    @Override
    String hacer( Aplicacion ap ) throws Exception {
      StepMeta antes = (StepMeta) s.clone();
      s.setDistributes( false );
      s.setChanged();
      ap.tm.clearCaches();
      ap.undo.cambio( new Object[] { antes }, new Object[] { s.clone() }, new int[] { ap.tm.indexOfStep( s ) } );
      return "'" + s.getName() + "' now COPIES every row to each of its " + TransOps.salidasNormales( ap.tm, s )
        + " outputs (it was distributing them)";
    }
  }

  /** default_step_name: renombra y actualiza las referencias (Filter rows, Stream lookup, Merge join, errores…). */
  static final class RenombrarStep extends Accion {
    final StepMeta s;
    final String nombre;

    RenombrarStep( Aplicacion ap, Elegido e, Set<String> nuevos ) throws Saltar {
      super( e );
      s = step( ap, e.h );
      nombre = nombreValido( e.valor );
      if ( nombre.equals( s.getName() ) ) {
        throw new Saltar( "the step is already called '" + nombre + "'" );
      }
      StepMeta otro = ap.tm.findStep( nombre );
      if ( otro != null && otro != s ) {
        throw new Saltar( "there is already a step named '" + otro.getName() + "'" );
      }
      if ( "default_step_name".equals( e.h.rule ) && NombresPorDefecto.esDeStep( nombre ) ) {
        throw new Saltar( "'" + nombre + "' is a default name too: choose one that says what the step does" );
      }
      if ( !nuevos.add( nombre.toLowerCase( Locale.ROOT ) ) ) {
        throw new Saltar( "another fix of this call already uses the name '" + nombre + "'" );
      }
    }

    @Override
    boolean renombre() {
      return true;
    }

    @Override
    String hacer( Aplicacion ap ) throws Exception {
      String viejo = s.getName();
      StepMeta antes = (StepMeta) s.clone();
      List<StepMeta[]> otros = TransOps.renombrar( ap.tm, s, nombre, ap.ms );
      Object[] desde = new Object[ otros.size() + 1 ];
      Object[] hasta = new Object[ otros.size() + 1 ];
      int[] idx = new int[ otros.size() + 1 ];
      desde[ 0 ] = antes;
      hasta[ 0 ] = s.clone();
      idx[ 0 ] = ap.tm.indexOfStep( s );
      List<String> refs = new ArrayList<String>();
      for ( int i = 0; i < otros.size(); i++ ) {
        desde[ i + 1 ] = otros.get( i )[ 0 ];
        hasta[ i + 1 ] = otros.get( i )[ 1 ].clone();
        idx[ i + 1 ] = ap.tm.indexOfStep( otros.get( i )[ 1 ] );
        refs.add( "'" + otros.get( i )[ 1 ].getName() + "'" );
      }
      ap.undo.cambio( desde, hasta, idx ); // el step y los que lo nombran: una sola acción, como rename_step
      List<String> quedan = TransOps.nombranTodavia( ap.tm, s, viejo );
      if ( !quedan.isEmpty() ) { // un plugin que guarda el nombre como texto y Kettle no lo actualiza
        ap.notas.add( String.join( ", ", quedan ) + " still name '" + viejo + "' as text: check that it points to '"
          + nombre + "' (open it in Spoon), or undo the rename." );
      }
      return "Renamed step '" + viejo + "' to '" + nombre + "'"
        + ( refs.isEmpty() ? "" : " (references updated in " + String.join( ", ", refs ) + ")" );
    }
  }

  /** default_entry_name y start_not_named_start: renombra la entrada (sus hops la siguen). */
  static final class RenombrarEntrada extends Accion {
    final JobEntryCopy c;
    final String nombre;

    RenombrarEntrada( Aplicacion ap, Elegido e, Set<String> nuevos ) throws Saltar {
      super( e );
      c = entrada( ap, e.h );
      nombre = nombreValido( e.valor );
      if ( nombre.equals( c.getName() ) ) {
        throw new Saltar( "the entry is already called '" + nombre + "'" );
      }
      for ( JobEntryCopy x : ap.jm.getJobCopies() ) {
        if ( x.getEntry() != c.getEntry() && x.getName() != null && x.getName().equalsIgnoreCase( nombre ) ) {
          throw new Saltar( "there is already an entry named '" + x.getName() + "'" );
        }
      }
      if ( "default_entry_name".equals( e.h.rule ) && NombresPorDefecto.esDeEntrada( nombre ) ) {
        throw new Saltar( "'" + nombre + "' is a default name too: choose one that says what the entry does" );
      }
      if ( !nuevos.add( nombre.toLowerCase( Locale.ROOT ) ) ) {
        throw new Saltar( "another fix of this call already uses the name '" + nombre + "'" );
      }
    }

    @Override
    boolean renombre() {
      return true;
    }

    @Override
    String hacer( Aplicacion ap ) throws Exception {
      String viejo = c.getName();
      JobEntryCopy antes = (JobEntryCopy) c.clone();
      c.setName( nombre );
      c.setChanged();
      ap.undo.cambio( new Object[] { antes }, new Object[] { c.clone() }, new int[] { ap.jm.indexOfJobEntry( c ) } );
      return "Renamed entry '" + viejo + "' to '" + nombre + "'";
    }
  }

  /** absolute_path, relative_path, job_variable_in_transformation: el valor nuevo de la ruta (y DIR_DATOS si falta). */
  static final class Ruta extends Accion {
    final StepMeta s;
    final JobEntryCopy c;
    final String tag;
    final int n;
    final String valor;
    final String parametro;
    final String porDefecto;

    Ruta( Aplicacion ap, Elegido e ) throws Saltar {
      super( e );
      Hallazgo h = e.h;
      if ( Hallazgo.STEP.equals( h.scope ) ) {
        s = step( ap, h );
        c = null;
      } else if ( Hallazgo.ENTRY.equals( h.scope ) ) {
        c = entrada( ap, h );
        s = null;
      } else {
        throw new Saltar( "only the paths of a step or an entry can be replaced" );
      }
      String t = h.fix.params.get( "tag" );
      tag = t != null ? t : h.attribute;
      n = h.n;
      valor = e.valor == null ? "" : e.valor.trim();
      if ( valor.isEmpty() ) {
        throw new Saltar( "no new path: pass it in values[key]" );
      }
      Rutas.Ruta r = buscar( s != null ? xml( s ) : xml( c ) );
      if ( r == null ) {
        throw new Saltar( "the path " + tag + " is no longer in '" + donde() + "'" );
      }
      String antes = h.evidence.get( "value" );
      if ( antes != null && !antes.equals( r.valor ) ) {
        throw new Saltar( "the path " + tag + " of '" + donde() + "' changed since the analysis (now " + r.valor + ")" );
      }
      if ( valor.equals( r.valor ) ) {
        throw new Saltar( "the path is already " + valor );
      }
      // la propuesta con parámetro: DIR_DATOS = la carpeta del archivo. Si ya existe (o otro arreglo de esta llamada lo
      // toma) para otra carpeta, la ruta quedaría mal: que la elija Claude o la persona
      String p = h.fix.params.get( "parameter" );
      porDefecto = h.fix.params.get( "default" ) == null ? "" : h.fix.params.get( "default" );
      if ( p != null && valor.contains( "${" + p + "}" ) ) {
        String existe = tieneParametro( ap.meta, p ) ? defaultDe( ap.meta, p ) : ap.parametros.get( p );
        if ( existe != null && !existe.equals( porDefecto ) ) {
          throw new Saltar( "parameter " + p + " is already the folder " + existe + ", not " + porDefecto + ": pass the "
            + "new path in values[key]" );
        }
        parametro = tieneParametro( ap.meta, p ) || ap.parametros.containsKey( p ) ? null : p;
        ap.parametros.put( p, porDefecto );
      } else {
        parametro = null;
      }
    }

    private static String defaultDe( AbstractMeta m, String p ) {
      try {
        String d = m.getParameterDefault( p );
        return d == null ? "" : d;
      } catch ( Exception x ) {
        return "";
      }
    }

    private Rutas.Ruta buscar( Element raiz ) {
      for ( Rutas.Ruta r : Rutas.de( raiz ) ) {
        if ( r.tag.equals( tag ) && r.n == n ) {
          return r;
        }
      }
      return null;
    }

    private String donde() {
      return s != null ? s.getName() : c.getName();
    }

    @Override
    String hacer( Aplicacion ap ) throws Exception {
      Element raiz = s != null ? xml( s ) : xml( c );
      Rutas.Ruta r = buscar( raiz );
      String viejo = r.valor;
      r.elemento.setTextContent( valor );
      if ( s != null ) {
        releer( ap, s, raiz );
      } else {
        releer( ap, c, raiz );
      }
      String extra = "";
      if ( parametro != null && !tieneParametro( ap.meta, parametro ) ) {
        ap.meta.addParameterDefinition( parametro, porDefecto, "" );
        ap.meta.setChanged();
        extra = "; parameter " + parametro + " declared with default " + porDefecto;
        ap.notas.add( "Ctrl+Z does not remove the parameters that were declared (" + parametro + "): remove them in the "
          + "document properties if you undo." );
      }
      return "In '" + donde() + "', " + tag + ": " + viejo + " -> " + valor + extra;
    }
  }

  /** password_in_file: la contraseña pasa a ser una variable (nunca se escribe la clave en otro lado). */
  static final class Clave extends Accion {
    DatabaseMeta db;
    SlaveServer ss;
    StepMeta s;
    JobEntryCopy c;
    final String valor;

    Clave( Aplicacion ap, Elegido e ) throws Saltar {
      super( e );
      Hallazgo h = e.h;
      valor = e.valor == null ? "" : e.valor.trim();
      if ( !ReglasSeguridad.esVariable( valor ) ) { // entero: «clave${X}» sigue siendo una clave en el archivo
        throw new Saltar( "the value must be only a variable such as ${DB_PASSWORD} (defined in kettle.properties), "
          + "with no text around it: a fix never writes a password into the file" );
      }
      if ( Hallazgo.CONNECTION.equals( h.scope ) ) {
        db = ap.meta.findDatabase( h.object );
        if ( db == null ) {
          throw new Saltar( "there is no connection '" + h.object + "' in the document" );
        }
        if ( db.isShared() ) {
          throw new Saltar( "'" + db.getName() + "' is a shared connection (shared.xml): change its password there" );
        }
      } else if ( Hallazgo.SLAVE.equals( h.scope ) ) {
        ss = ap.meta.findSlaveServer( h.object );
        if ( ss == null ) {
          throw new Saltar( "there is no slave server '" + h.object + "' in the document" );
        }
      } else if ( Hallazgo.STEP.equals( h.scope ) ) {
        s = step( ap, h );
        if ( hoja( xml( s ) ) == null ) {
          throw new Saltar( "'" + s.getName() + "' no longer stores a password in " + h.attribute );
        }
      } else if ( Hallazgo.ENTRY.equals( h.scope ) ) {
        c = entrada( ap, h );
        if ( hoja( xml( c ) ) == null ) {
          throw new Saltar( "'" + c.getName() + "' no longer stores a password in " + h.attribute );
        }
      } else {
        throw new Saltar( "this password is not in a connection, a step or an entry: change it by hand" );
      }
    }

    /** La hoja con la contraseña guardada: el n-ésimo tag con ese nombre que guarda una (o el primero). */
    private Element hoja( Element raiz ) {
      List<Element> l = new ArrayList<Element>();
      for ( Element[] x : Nodos.hojas( raiz ) ) {
        String v = x[ 0 ].getTextContent().trim();
        if ( x[ 0 ].getTagName().equals( e.h.attribute ) && !v.isEmpty() && !v.equals( "Encrypted" )
          && !v.matches( "(?i)Y|N|true|false" ) && !ReglasSeguridad.esVariable( v ) ) {
          l.add( x[ 0 ] );
        }
      }
      int i = e.h.n > 0 ? e.h.n - 1 : 0;
      return i < l.size() ? l.get( i ) : null;
    }

    @Override
    boolean deshacible( Aplicacion ap ) {
      // Spoon deshace el cambio de una conexión solo en transformaciones; el de un slave server, nunca
      return ss == null && ( db == null || ap.tm != null );
    }

    @Override
    String hacer( Aplicacion ap ) throws Exception {
      ap.notas.add( "Define the variable of each password in kettle.properties (in Spoon: Edit > Edit the "
        + "kettle.properties file) before running; never in the .ktr/.kjb." );
      if ( db != null ) {
        DatabaseMeta antes = (DatabaseMeta) db.clone();
        db.setPassword( valor );
        db.setChanged();
        if ( ap.tm != null ) {
          ap.undo.cambio( new Object[] { antes }, new Object[] { db.clone() }, new int[] { ap.meta.indexOfDatabase( db ) } );
        }
        return "Connection '" + db.getName() + "' now takes its password from " + valor;
      }
      if ( ss != null ) {
        ss.setPassword( valor );
        ss.setChanged();
        ap.meta.setChanged();
        return "Slave server '" + ss.getName() + "' now takes its password from " + valor;
      }
      Element raiz = s != null ? xml( s ) : xml( c );
      hoja( raiz ).setTextContent( valor );
      if ( s != null ) {
        releer( ap, s, raiz );
        return "Step '" + s.getName() + "' now takes " + e.h.attribute + " from " + valor;
      }
      releer( ap, c, raiz );
      return "Entry '" + c.getName() + "' now takes " + e.h.attribute + " from " + valor;
    }
  }

  /** empty_decimal_symbol: el símbolo decimal del campo, explícito ('.' o ','). */
  static final class Decimal extends Accion {
    final StepMeta s;
    final String campo;
    final String valor;

    Decimal( Aplicacion ap, Elegido e ) throws Saltar {
      super( e );
      s = step( ap, e.h );
      String f = e.h.fix.params.get( "field" );
      campo = f != null ? f : e.h.field;
      valor = e.valor == null ? "" : e.valor.trim();
      if ( !valor.equals( "." ) && !valor.equals( "," ) ) {
        throw new Saltar( "the decimal symbol must be '.' or ',' (as the data has it)" );
      }
      Element x = campo( xml( s ) );
      if ( x == null ) {
        throw new Saltar( "field " + campo + " is no longer in '" + s.getName() + "'" );
      }
      if ( !Nodos.txt( x, "decimal" ).isEmpty() ) {
        throw new Saltar( "field " + campo + " of '" + s.getName() + "' already has a decimal symbol" );
      }
    }

    private Element campo( Element raiz ) {
      for ( Element f : Nodos.hijos( Nodos.hijo( raiz, "fields" ), "field" ) ) {
        if ( Nodos.txt( f, "name" ).equals( campo ) ) {
          return f;
        }
      }
      return null;
    }

    @Override
    String hacer( Aplicacion ap ) throws Exception {
      Element raiz = xml( s );
      Element f = campo( raiz );
      texto( f, "decimal", valor );
      String extra = "";
      if ( valor.equals( Nodos.txt( f, "group" ) ) ) {
        texto( f, "group", "" ); // el mismo símbolo para las dos cosas: Kettle no sabría cuál es cuál
        extra = " (its grouping symbol was the same, so it was emptied)";
      }
      releer( ap, s, raiz );
      return "Field " + campo + " of '" + s.getName() + "' now has decimal symbol '" + valor + "'" + extra;
    }
  }

  /** text_output_without_fields: declara los campos que llegan (Strings sin largo, el resto con su formato). */
  static final class DeclararCampos extends Accion {
    final StepMeta s;

    DeclararCampos( Aplicacion ap, Elegido e ) throws Saltar {
      super( e );
      s = step( ap, e.h );
      if ( !SALIDAS_TEXTO.contains( s.getStepID() ) ) {
        throw new Saltar( "'" + s.getName() + "' is not a Text file output" );
      }
      if ( !Nodos.hijos( Nodos.hijo( xml( s ), "fields" ), "field" ).isEmpty() ) {
        throw new Saltar( "'" + s.getName() + "' already declares its fields" );
      }
      if ( e.campos == null ) {
        throw new Saltar( "the fields that reach '" + s.getName() + "' could not be computed"
          + ( e.sinCampos != null ? ": " + e.sinCampos : "" ) );
      }
    }

    @Override
    String hacer( Aplicacion ap ) throws Exception {
      Element raiz = xml( s );
      Element nuevo = (Element) raiz.getOwnerDocument().importNode( Nodos.parse( e.campos ), true );
      Element viejo = Nodos.hijo( raiz, "fields" );
      if ( viejo != null ) {
        raiz.replaceChild( nuevo, viejo );
      } else {
        raiz.appendChild( nuevo );
      }
      releer( ap, s, raiz );
      int n = Nodos.hijos( nuevo, "field" ).size();
      return "'" + s.getName() + "' now declares the " + n + " fields that reach it (Strings with length -1: no padding)";
    }
  }

  /** unsorted_input: un Sort rows por las claves delante del consumidor (y step1/step2… apuntando a él). */
  static final class Ordenar extends Accion {
    final StepMeta s;
    final List<StepMeta> fuentes = new ArrayList<StepMeta>();
    final List<TransHopMeta> hops = new ArrayList<TransHopMeta>();
    final List<String> claves;
    final boolean[] asc;
    final String tagInfo;

    /** ORDER BY en el Table input si se puede (agrupar, entrada directa, SQL simple); si no, Sort rows. */
    static Accion para( Aplicacion ap, Elegido e ) throws Saltar {
      Map<String, String> p = e.h.fix.params;
      String tabla = p.get( "table_input" );
      if ( tabla != null && tabla.equals( p.get( "input" ) ) && ap.tm != null ) {
        StepMeta consumidor = step( ap, e.h );
        StepMeta t = ap.tm.findStep( tabla );
        if ( t != null && "TableInput".equals( t.getStepID() ) && !COMPARAN.contains( consumidor.getStepID() ) ) {
          String sql = Nodos.txt( xml( t ), "sql" );
          List<String> k = partir( p.get( "keys" ) );
          boolean simples = !k.isEmpty();
          for ( String x : k ) {
            simples &= IDENTIFICADOR.matcher( x ).matches();
          }
          if ( simples && SQL_SELECT.matcher( sql ).matches() && !SQL_NO_ORDENABLE.matcher( sql ).matches()
            && !sql.contains( "${" ) && !sql.contains( "%%" )
            && ap.consultasOrdenadas.add( t.getName().toLowerCase( Locale.ROOT ) ) ) {
            return new OrdenEnConsulta( e, t, k );
          }
        }
      }
      return new Ordenar( ap, e );
    }

    Ordenar( Aplicacion ap, Elegido e ) throws Saltar {
      super( e );
      s = step( ap, e.h );
      Map<String, String> p = e.h.fix.params;
      claves = partir( p.get( "keys" ) );
      if ( claves.isEmpty() ) {
        throw new Saltar( "no keys to sort by" );
      }
      if ( "true".equals( p.get( "after_merge" ) ) ) {
        for ( TransHopMeta x : ap.tm.getTransHops() ) {
          if ( x.getToStep() == s && x.getFromStep() != null && x.isEnabled() && !x.isErrorHop() ) {
            hops.add( x );
          }
        }
        if ( hops.size() < 2 ) {
          throw new Saltar( "'" + s.getName() + "' no longer gets rows from several hops" );
        }
        tagInfo = null;
      } else {
        StepMeta f = ap.tm.findStep( p.get( "input" ) );
        TransHopMeta x = f == null ? null : ap.tm.findTransHop( f, s, true );
        if ( x == null ) {
          throw new Saltar( "there is no hop from '" + p.get( "input" ) + "' to '" + s.getName() + "'" );
        }
        hops.add( x );
        tagInfo = TAGS_INFO.contains( e.h.attribute ) ? e.h.attribute : null;
        if ( tagInfo != null && !Nodos.txt( xml( s ), tagInfo ).equalsIgnoreCase( f.getName() ) ) {
          throw new Saltar( "'" + s.getName() + "' no longer reads " + tagInfo + " from '" + f.getName() + "'" );
        }
      }
      for ( TransHopMeta x : hops ) {
        StepMeta f = x.getFromStep();
        if ( x.isErrorHop() ) {
          throw new Saltar( "the hop '" + f.getName() + "' -> '" + s.getName() + "' is an error hop" );
        }
        if ( mandaA( f, s ) ) {
          throw new Saltar( "'" + f.getName() + "' sends rows to specific targets (Filter rows, Switch/case): insert "
            + "the Sort rows by hand and point the target to it" );
        }
        if ( !fuentes.contains( f ) ) {
          fuentes.add( f );
        }
      }
      asc = sentidos( xml( s ) );
    }

    /** Si f manda filas a s por un target stream (Filter rows, Switch/case): el hop no se puede mover solo. */
    private static boolean mandaA( StepMeta f, StepMeta s ) {
      try {
        for ( StreamInterface t : f.getStepMetaInterface().getStepIOMeta().getTargetStreams() ) {
          if ( t.getStepMeta() == s || s.getName().equalsIgnoreCase( t.getStepname() ) ) {
            return true;
          }
        }
      } catch ( RuntimeException ignored ) {
        // sin StepIOMeta: un step común
      }
      return false;
    }

    /** Sorted merge pide el sentido de sus campos; los demás, ascendente. */
    private boolean[] sentidos( Element x ) {
      boolean[] r = new boolean[ claves.size() ];
      Arrays.fill( r, true );
      if ( "SortedMerge".equals( s.getStepID() ) ) {
        for ( Element f : Nodos.hijos( Nodos.hijo( x, "fields" ), "field" ) ) {
          int i = claves.indexOf( Nodos.txt( f, "name" ) );
          if ( i >= 0 ) {
            r[ i ] = !"N".equalsIgnoreCase( Nodos.txt( f, "ascending" ) );
          }
        }
      }
      return r;
    }

    @Override
    String hacer( Aplicacion ap ) throws Exception {
      TransMeta tm = ap.tm;
      String nombre = LintPropuestas.unico( LintPropuestas.nombreOrden( claves, ap.lang ), tomados( ap ) );
      StringBuilder f = new StringBuilder( "<fields>" );
      for ( int i = 0; i < claves.size(); i++ ) {
        f.append( "<field>" ).append( XMLHandler.addTagValue( "name", claves.get( i ), false ) )
          .append( XMLHandler.addTagValue( "ascending", asc[ i ], false ) )
          .append( "<case_sensitive>Y</case_sensitive><collator_enabled>N</collator_enabled>"
            + "<collator_strength>0</collator_strength><presorted>N</presorted></field>" );
      }
      f.append( "</fields>" );
      PluginInterface p = TransOps.plugin( "SortRows" );
      String cuerpo = Xml.mezclar( TransOps.plantilla( p ), f.toString(), Xml.TAGS_ENVOLTORIO_STEP );
      StepMeta orden = TransOps.construir( tm, TransOps.id( p ), nombre, null, 1, true, cuerpo, posicion( tm ), ap.ms );
      // 1) el Sort rows; 2) los hops viejos; 3) los nuevos; 4) el consumidor, si nombra su entrada: un Ctrl+Z
      tm.addStep( orden );
      ap.undo.nuevo( new Object[] { orden }, new int[] { tm.indexOfStep( orden ) } );
      List<TransHopMeta> viejos = new ArrayList<TransHopMeta>( hops );
      Collections.sort( viejos, new Comparator<TransHopMeta>() {
        @Override
        public int compare( TransHopMeta a, TransHopMeta b ) {
          return ap.tm.indexOfTransHop( a ) - ap.tm.indexOfTransHop( b );
        }
      } );
      Object[] clones = new Object[ viejos.size() ];
      int[] idx = new int[ viejos.size() ];
      boolean habilitado = true;
      for ( int i = 0; i < viejos.size(); i++ ) {
        clones[ i ] = viejos.get( i ).clone();
        idx[ i ] = tm.indexOfTransHop( viejos.get( i ) );
        habilitado &= viejos.get( i ).isEnabled();
      }
      for ( int i = viejos.size() - 1; i >= 0; i-- ) { // de atrás para adelante: los índices no se corren
        tm.removeTransHop( idx[ i ] );
      }
      ap.undo.borrado( clones, idx );
      List<TransHopMeta> nuevos = new ArrayList<TransHopMeta>();
      for ( StepMeta x : fuentes ) {
        nuevos.add( new TransHopMeta( x, orden, habilitado ) );
      }
      nuevos.add( new TransHopMeta( orden, s, true ) );
      Object[] hs = new Object[ nuevos.size() ];
      int[] ix = new int[ nuevos.size() ];
      for ( int i = 0; i < nuevos.size(); i++ ) {
        tm.addTransHop( nuevos.get( i ) );
        hs[ i ] = nuevos.get( i );
        ix[ i ] = tm.indexOfTransHop( nuevos.get( i ) );
      }
      ap.undo.nuevo( hs, ix );
      if ( tagInfo != null ) {
        Element raiz = xml( s );
        texto( raiz, tagInfo, nombre );
        releer( ap, s, raiz );
      } else {
        TransOps.resolverReferencias( tm );
      }
      List<String> de = new ArrayList<String>();
      for ( StepMeta x : fuentes ) {
        de.add( "'" + x.getName() + "'" );
      }
      return "Inserted Sort rows '" + nombre + "' (by " + String.join( ", ", claves ) + ") between "
        + String.join( ", ", de ) + " and '" + s.getName() + "'" + ( tagInfo != null ? "; " + tagInfo + " now reads from it" : "" );
    }

    /** Entre las fuentes y el consumidor; si el lugar está ocupado, más abajo. */
    private Point posicion( TransMeta tm ) {
      int x = 0;
      int y = 0;
      for ( StepMeta f : fuentes ) {
        x += f.getLocation().x;
        y += f.getLocation().y;
      }
      x = ( x / fuentes.size() + s.getLocation().x ) / 2;
      y = ( y / fuentes.size() + s.getLocation().y ) / 2;
      Point p = new Point( x, y );
      for ( int i = 0; i < 20 && ocupado( tm, p ); i++ ) {
        p.y += TransOps.PASO_Y;
      }
      return p;
    }

    private static boolean ocupado( TransMeta tm, Point p ) {
      for ( StepMeta x : tm.getSteps() ) {
        if ( Math.abs( x.getLocation().x - p.x ) < 90 && Math.abs( x.getLocation().y - p.y ) < 70 ) {
          return true;
        }
      }
      return false;
    }
  }

  /** «a, b» → [a, b] (así arma las claves ReglasOrden). */
  static List<String> partir( String claves ) {
    List<String> l = new ArrayList<String>();
    if ( claves != null ) {
      for ( String x : claves.split( ", " ) ) {
        if ( !x.trim().isEmpty() ) {
          l.add( x.trim() );
        }
      }
    }
    return l;
  }

  /** unsorted_input con un Table input justo antes de un step que agrupa: ORDER BY al final de la consulta. */
  static final class OrdenEnConsulta extends Accion {
    final StepMeta t;
    final List<String> claves;

    OrdenEnConsulta( Elegido e, StepMeta t, List<String> claves ) {
      super( e );
      this.t = t;
      this.claves = claves;
    }

    @Override
    String hacer( Aplicacion ap ) throws Exception {
      Element raiz = xml( t );
      Element sql = Nodos.hijo( raiz, "sql" );
      String q = sql == null ? "" : sql.getTextContent().replaceAll( "[\\s;]+$", "" );
      if ( !SQL_SELECT.matcher( q ).matches() || SQL_NO_ORDENABLE.matcher( q ).matches() ) {
        throw new Saltar( "the query of '" + t.getName() + "' changed (it already has an ORDER BY, or cannot take "
          + "one): insert a Sort rows instead" );
      }
      sql.setTextContent( q + "\nORDER BY " + String.join( ", ", claves ) );
      releer( ap, t, raiz );
      return "Added ORDER BY " + String.join( ", ", claves ) + " to the query of '" + t.getName() + "'";
    }
  }

  /** failure_branch_ends_in_success: el hop rojo va a un Abort (el que haya, o uno nuevo al lado del Success). */
  static final class HopAAbort extends Accion {
    final JobHopMeta hop;
    JobEntryCopy abort;

    HopAAbort( Aplicacion ap, Elegido e ) throws Saltar {
      super( e );
      if ( ap.jm == null ) {
        throw new Saltar( "the document is not a job" );
      }
      Hallazgo h = e.h;
      JobHopMeta encontrado = null;
      for ( int i = 0; i < ap.jm.nrJobHops() && encontrado == null; i++ ) {
        JobHopMeta x = ap.jm.getJobHop( i );
        JobEntryCopy a = x.getFromEntry();
        JobEntryCopy b = x.getToEntry();
        if ( a != null && b != null && a.getName().equals( h.entry ) && a.getNr() == h.entryNr
          && ( a.getName() + " -> " + b.getName() ).equals( h.object ) && x.isEnabled() && !x.isUnconditional()
          && !x.getEvaluation() && "SUCCESS".equals( pid( b ) ) ) {
          encontrado = x;
        }
      }
      if ( encontrado == null ) {
        throw new Saltar( "the failure hop " + h.object + " to Success is no longer there" );
      }
      hop = encontrado;
      for ( JobEntryCopy x : ap.jm.getJobCopies() ) {
        if ( abort == null && "ABORT".equals( pid( x ) ) ) {
          abort = x;
        }
      }
      if ( abort != null && ap.jm.findJobHop( hop.getFromEntry(), abort, true ) != null ) {
        throw new Saltar( "'" + hop.getFromEntry().getName() + "' already has a hop to '" + abort.getName()
          + "': delete the failure hop to Success by hand" );
      }
    }

    private static String pid( JobEntryCopy c ) {
      return c.getEntry() == null ? "" : c.getEntry().getPluginId();
    }

    @Override
    String hacer( Aplicacion ap ) throws Exception {
      JobMeta jm = ap.jm;
      JobEntryCopy exito = hop.getToEntry();
      boolean nueva = abort == null;
      if ( nueva ) {
        String nombre = LintPropuestas.unico( LintPropuestas.nombreAbort( ap.lang ), tomados( ap ) );
        Point p = exito.getLocation();
        JobSoporte.agregar( jm, "ABORT", nombre, null, p.x, p.y + JobSoporte.PASO_Y, null, null, ap.rep, ap.ms, ap.undo );
        abort = jm.findJobEntry( nombre, 0, true );
      }
      JobHopMeta antes = (JobHopMeta) hop.clone();
      hop.setToEntry( abort );
      hop.setChanged();
      ap.undo.cambio( new Object[] { antes }, new Object[] { hop.clone() }, new int[] { jm.indexOfJobHop( hop ) } );
      String r = "The failure hop of '" + hop.getFromEntry().getName() + "' now goes to '" + abort.getName() + "'"
        + ( nueva ? " (a new Abort job entry)" : "" ) + " instead of '" + exito.getName() + "'";
      if ( !jm.isEntryUsedInHops( exito ) ) {
        r += "; '" + exito.getName() + "' has no hops left: delete it";
      }
      return r;
    }
  }

  /** no_description: la descripción del documento (fuera del undo de Spoon). */
  static final class Descripcion extends Accion {
    final String valor;

    Descripcion( Aplicacion ap, Elegido e ) throws Saltar {
      super( e );
      valor = e.valor == null ? "" : e.valor.trim();
      if ( valor.isEmpty() ) {
        throw new Saltar( "no description: pass it in values[key]" );
      }
    }

    @Override
    boolean deshacible( Aplicacion ap ) {
      return false;
    }

    @Override
    String hacer( Aplicacion ap ) {
      ap.meta.setDescription( valor );
      ap.meta.setChanged();
      ap.notas.add( "Ctrl+Z does not undo the description: change it again in the document properties if needed." );
      return "Set the description of the document";
    }
  }

  /** undeclared_variable: la declara como parámetro con el default elegido (fuera del undo de Spoon). */
  static final class Parametro extends Accion {
    final String nombre;
    final String valor;

    Parametro( Aplicacion ap, Elegido e ) throws Saltar {
      super( e );
      nombre = e.h.fix.params.get( "parameter" );
      valor = e.valor == null ? "" : e.valor;
      if ( nombre == null || nombre.isEmpty() ) {
        throw new Saltar( "no parameter name" );
      }
      if ( tieneParametro( ap.meta, nombre ) ) {
        throw new Saltar( nombre + " is already a parameter" );
      }
    }

    @Override
    boolean deshacible( Aplicacion ap ) {
      return false;
    }

    @Override
    String hacer( Aplicacion ap ) throws Exception {
      ap.meta.addParameterDefinition( nombre, valor, "" );
      ap.meta.setChanged();
      ap.notas.add( "Ctrl+Z does not remove the parameters that were declared: remove them in the document properties "
        + "if you undo." );
      return "Declared parameter " + nombre + " with default '" + valor + "'";
    }
  }

  // ---------------------------------------------------------------- huella del documento

  /**
   * Para saber si el documento cambió entre la copia (que se analizó) y la aplicación: el XML entero. 0 si no se
   * puede armar (entonces no se compara). (UI)
   */
  static int huella( AbstractMeta m ) {
    try {
      String x = m instanceof TransMeta ? ( (TransMeta) m ).getXML() : ( (JobMeta) m ).getXML();
      return x == null ? 0 : x.hashCode();
    } catch ( Exception e ) {
      return 0;
    }
  }

  // ---------------------------------------------------------------- la respuesta

  static ObjectNode respuesta( String doc, Analisis an, Hecho h, Resultado despues, boolean ensayo, boolean sinGuardarAntes,
                               boolean esTrans ) {
    ObjectNode o = Tools.obj();
    if ( doc != null ) {
      o.put( "document", doc );
    }
    o.put( "dry_run", ensayo );
    o.set( "applied", h.aplicados );
    ArrayNode sk = o.putArray( "skipped" );
    for ( String[] s : h.saltados ) {
      sk.addObject().put( "key", s[ 0 ] ).put( "reason", s[ 1 ] );
    }
    o.put( "undo_steps", h.pasosDeshacer );
    o.put( "score_before", an.antes.score ).put( "grade_before", an.antes.grade );
    o.put( "score_after", despues.score ).put( "grade_after", despues.grade );
    ArrayNode notas = o.putArray( "notes" );
    if ( ensayo ) {
      notas.add( "Dry run: nothing was changed. 'applied' is what would change; score_after is the score it would get." );
    }
    if ( h.cambios > 0 ) {
      if ( !sinGuardarAntes ) {
        notas.add( "score_after is computed on the unsaved document: the rules that read the file (duplicate step "
          + "names, passwords in plain text) are judged in memory until it is saved." );
      }
      if ( !ensayo ) {
        notas.add( esTrans ? "One Ctrl+Z in Spoon undoes all the changes of this call."
          : "In a job each change is its own Ctrl+Z in Spoon (undo_steps)." );
        notas.add( "The changes are not saved yet: use 'save' to keep them." );
      }
    }
    for ( String n : h.notas ) {
      notas.add( n );
    }
    return o;
  }

  // ---------------------------------------------------------------- suppress_lint

  /** La regla a silenciar ('*' = todas), validada antes de tocar Spoon. */
  static String reglaParaSilenciar( String id ) throws ToolError {
    String x = id == null ? "" : id.trim();
    if ( x.equals( Supresiones.TODAS ) ) {
      return x;
    }
    Regla r = Catalogo.buscar( x );
    if ( r == null ) {
      List<String> ids = new ArrayList<String>();
      for ( Regla g : Catalogo.todas() ) {
        ids.add( g.id() );
      }
      throw new ToolError( "Unknown lint rule '" + x + "'. Valid ids: " + String.join( ", ", ids ) + " (or * for all)" );
    }
    if ( r.id().equals( Catalogo.KETTLE_CHECK ) ) {
      throw new ToolError( "kettle_check cannot be suppressed: it never lowers the score (it is only listed when "
        + "include_kettle_checks=true)" );
    }
    return r.id();
  }

  /**
   * suppress_lint sin Spoon: escribe (o quita) pentaho-live-lint/ignore y reason en el step, en todas las copias de la
   * entrada o en el documento, y llama setChanged() (setAttribute no lo hace). En un step el cambio va al grupo de
   * undo (StepMeta.replaceMeta copia los atributos); en una entrada no (JobEntryCopy.replaceMeta no los copia) ni en
   * el documento (no hay acción de undo para él). Los hops no tienen atributos: se silencian en el step o la entrada
   * de origen.
   */
  static ObjectNode silenciar( AbstractMeta m, String regla, String step, String entry, String motivo, boolean quitar,
                               UndoGrupo undo ) throws ToolError {
    boolean trans = m instanceof TransMeta;
    if ( step != null && entry != null ) {
      throw new ToolError( "Pass 'step' or 'entry', not both (neither = the whole document)" );
    }
    if ( step != null && !trans ) {
      throw new ToolError( "'" + Docs.nombre( m ) + "' is a job: pass 'entry' instead of 'step'" );
    }
    if ( entry != null && trans ) {
      throw new ToolError( "'" + Docs.nombre( m ) + "' is a transformation: pass 'step' instead of 'entry'" );
    }
    Regla r = Catalogo.buscar( regla );
    if ( r != null && ( trans ? !r.paraTrans() : !r.paraJobs() ) ) {
      throw new ToolError( "The rule '" + regla + "' does not apply to " + ( trans ? "transformations" : "jobs" ) );
    }
    String mot = motivo == null || motivo.trim().isEmpty() ? null : motivo.trim();
    ObjectNode o = Tools.obj();
    o.put( "rule", regla );
    o.put( "action", quitar ? "removed" : "suppressed" );
    boolean cambio = false;
    AttributesInterface donde;
    if ( step != null ) {
      TransMeta tm = (TransMeta) m;
      StepMeta s = TransOps.paso( tm, step );
      StepMeta antes = (StepMeta) s.clone();
      cambio = cambiar( s, regla, mot, quitar );
      if ( cambio ) {
        s.setChanged();
        undo.cambio( new Object[] { antes }, new Object[] { s.clone() }, new int[] { tm.indexOfStep( s ) } );
      }
      o.put( "scope", Hallazgo.STEP ).put( "step", s.getName() ).put( "undoable", true );
      donde = s;
    } else if ( entry != null ) {
      JobMeta jm = (JobMeta) m;
      JobEntryCopy primera = JobSoporte.entrada( jm, entry );
      int copias = 0;
      for ( JobEntryCopy c : jm.getJobCopies() ) {
        if ( c.getName() != null && c.getName().equalsIgnoreCase( primera.getName() ) ) {
          cambio |= cambiar( c, regla, mot, quitar );
          copias++;
        }
      }
      o.put( "scope", Hallazgo.ENTRY ).put( "entry", primera.getName() ).put( "undoable", false );
      if ( copias > 1 ) {
        o.put( "copies", copias );
      }
      donde = primera;
    } else {
      cambio = cambiar( m, regla, mot, quitar );
      o.put( "scope", Hallazgo.DOC ).put( "undoable", false );
      donde = m;
    }
    if ( cambio ) {
      m.setChanged();
    }
    o.put( "changed", cambio );
    ArrayNode ids = o.putArray( "suppressed_rules" );
    for ( String x : Supresiones.ids( donde ) ) {
      ids.add( x );
    }
    String razon = Supresiones.motivo( donde );
    if ( razon != null && ids.size() > 0 ) {
      o.put( "reason", razon );
    }
    if ( !cambio ) {
      o.put( "note", quitar ? "The rule was not suppressed there: nothing changed."
        : "The rule was already suppressed there: nothing changed." );
    } else if ( !o.path( "undoable" ).asBoolean() ) {
      o.put( "note", "Ctrl+Z does not undo this: call suppress_lint again with remove=" + !quitar + ". Save the "
        + "document to keep it (it travels inside the file)." );
    } else {
      o.put( "note", "Save the document to keep it (it travels inside the file)." );
    }
    return o;
  }

  private static boolean cambiar( AttributesInterface a, String regla, String motivo, boolean quitar ) {
    Set<String> antes = Supresiones.ids( a );
    if ( quitar ) {
      if ( !antes.contains( regla ) ) {
        return false;
      }
      Supresiones.quitar( a, regla );
      return true;
    }
    String motivoAntes = Supresiones.motivo( a );
    Supresiones.agregar( a, regla, motivo );
    return !antes.contains( regla ) || motivo != null && !motivo.equals( motivoAntes );
  }
}
